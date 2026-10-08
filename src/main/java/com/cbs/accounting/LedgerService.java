package com.cbs.accounting;

import static com.cbs.accounting.LedgerModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class LedgerService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final ChartService chart;
    private final BankLedgerStore store;
    private final BankJournalStore journals;
    private final BankDatabaseProvisioner databases;
    private final TransactionTemplate tx;
    public LedgerService(JdbcTemplate jdbc,TenantRepository tenants,ChartService chart,BankLedgerStore store,
            BankDatabaseProvisioner databases,PlatformTransactionManager manager,BankJournalStore journals){
        this.journals=journals;
        this.jdbc=jdbc;this.tenants=tenants;this.chart=chart;this.store=store;this.databases=databases;this.tx=new TransactionTemplate(manager);
    }
    public Directory list(Actor actor,UUID bankId,String search,String currency,Boolean active,int limit,int offset){
        AccessService.admin(actor);AccessService.page(limit,offset);
        if(search.length()>100||(currency!=null&&!currency.matches("[A-Z]{3}")))throw error(400,"INVALID_REQUEST");
        return tx.execute(s->{
            jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);
            Tenant bank=tenants.get(bankId,false);
            boolean ready=List.of(Status.ACTIVE,Status.SUSPENDED).contains(bank.status())&&store.ready(bank);
            String where=" WHERE r.tenant_id=?";List<Object> args=new ArrayList<>();args.add(bankId);
            if(!search.isBlank()){where+=" AND position(lower(?) in lower(r.code || ' ' || (d.payload->>'name'))) > 0";args.add(search.trim());}
            if(currency!=null){where+=" AND r.currency=?";args.add(currency);}
            if(active!=null){where+=" AND r.active=?";args.add(active);}
            String from=" FROM platform.ledger_registry r JOIN platform.ledger_delivery d ON d.request_id=r.request_id JOIN platform.chart_account c ON c.id=r.chart_account_id JOIN platform.legal_entity e ON e.id=r.legal_entity_id";
            long total=jdbc.queryForObject("SELECT count(*)"+from+where,Long.class,args.toArray());args.add(limit);args.add(offset);
            List<Account> rows=jdbc.query("SELECT d.payload::text,c.code chart_code,e.legal_name,r.delivery,r.request_id,d.failure_code"+from+where+" ORDER BY r.code,r.id LIMIT ? OFFSET ?",
                    (r,n)->new Account(JSON.readValue(r.getString("payload"),Definition.class),r.getString("chart_code"),r.getString("legal_name"),r.getString("delivery"),r.getObject("request_id",UUID.class),r.getString("failure_code")),args.toArray());
            var applied=rows.stream().filter(a->a.delivery().equals("APPLIED")).map(a->a.definition().id()).toList();
            if(!applied.isEmpty()){
                if(!ready)throw error(503,"LEDGER_STORAGE_NOT_READY");
                var live=store.read(bank,applied);
                for(Account account:rows)if(account.delivery().equals("APPLIED")&&!live.contains(account.definition()))throw error(503,"LEDGER_RECONCILIATION_REQUIRED");
            }
            return new Directory(bankId,bank.version(),ready,rows,total,limit,offset);
        });
    }
    public void initialize(Actor actor,UUID bankId,ChangeRequest input){
        AccessService.admin(actor);
        tx.executeWithoutResult(s->{
            Tenant bank=tenants.get(bankId,true);
            if(bank.version()!=input.expectedVersion())throw error(409,"STALE_VERSION");
            if(bank.status()!=Status.ACTIVE)throw error(409,"LEDGER_BANK_NOT_ACTIVE");
            String version=databases.upgradeOwned(bank.id(),bank.databaseBinding().databaseReference());
            jdbc.update("UPDATE platform.database_binding SET schema_version=? WHERE tenant_id=?",version,bankId);
            tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
            tenants.audit(actor,bankId,"LEDGER_STORAGE_PREPARED",bank.version()+1,bank.status(),bank.status(),input.reason(),null,null,Map.of("schemaVersion",version));
        });
    }
    // Invoked under the same tenant lock as all other bank configuration mutations.
    public void validate(Tenant bank,Change input){
        DayCloseService.requireOpen(jdbc,bank.id());
        if(bank.version()!=input.expectedVersion())throw error(409,"STALE_VERSION");
        if(bank.status()!=Status.ACTIVE)throw error(409,"LEDGER_BANK_NOT_ACTIVE");
        if(bank.legalEntities().stream().noneMatch(e->e.id().equals(input.legalEntityId())))throw error(400,"LEGAL_ENTITY_NOT_IN_BANK");
        var definition=chart.get(bank.id(),input.chartAccountId());
        if(definition.kind()!=ChartModels.Kind.POSTING||((input.active()||input.ledgerId()==null)&&!definition.active()))throw error(400,"LEDGER_POSTING_REQUIRED");
        if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.bank_settings, jsonb_array_elements(currencies) c WHERE tenant_id=? AND c->>'code'=? AND (c->>'enabled')::boolean)",Boolean.class,bank.id(),input.currency()))throw error(400,"CURRENCY_NOT_ENABLED");
        if(!store.ready(bank))throw error(409,"LEDGER_STORAGE_NOT_READY");
        if(input.ledgerId()==null){
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.ledger_registry WHERE tenant_id=? AND code=?)",Boolean.class,bank.id(),input.code()))throw error(409,"LEDGER_CODE_IN_USE");
        }else{
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_location WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.cash_transit WHERE ledger_id=?)",Boolean.class,input.ledgerId(),input.ledgerId()))throw error(409,"CASH_MANAGED");
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_ledger WHERE ledger_id=?)",Boolean.class,input.ledgerId()))throw error(409,"CONTRACT_MANAGED");
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_book_ledger WHERE ledger_id=?)",Boolean.class,input.ledgerId()))throw error(409,"FX_VALUATION_MANAGED");
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_position WHERE ledger_id=?)",Boolean.class,input.ledgerId()))throw error(409,"FX_MANAGED");
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_ledger WHERE ledger_id=?)",Boolean.class,input.ledgerId()))throw error(409,"FUNDING_MANAGED");
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.journal_ledger l JOIN platform.journal_delivery d ON d.request_id=l.request_id WHERE l.ledger_id=? AND NOT d.completed)",Boolean.class,input.ledgerId()))throw error(409,"LEDGER_JOURNAL_PENDING");
            if(!input.active()&&journals.hasBalance(bank,input.ledgerId()))throw error(409,"LEDGER_BALANCE_NOT_ZERO");
            Definition current=current(bank.id(),input.ledgerId());
            if(!current.code().equals(input.code())||!current.chartAccountId().equals(input.chartAccountId())||!current.legalEntityId().equals(input.legalEntityId())||!current.currency().equals(input.currency()))throw error(400,"LEDGER_STRUCTURE_IMMUTABLE");
            String delivery=jdbc.queryForObject("SELECT delivery FROM platform.ledger_registry WHERE tenant_id=? AND id=?",String.class,bank.id(),input.ledgerId());
            if(!delivery.equals("APPLIED"))throw error(409,"LEDGER_DELIVERY_PENDING");
            if(!store.read(bank,List.of(current.id())).contains(current))throw error(503,"LEDGER_RECONCILIATION_REQUIRED");
        }
    }
    private Definition current(UUID bankId,UUID id){
        var rows=jdbc.query("SELECT d.payload::text FROM platform.ledger_registry r JOIN platform.ledger_delivery d ON r.request_id=d.request_id WHERE r.tenant_id=? AND r.id=?",
                (r,n)->JSON.readValue(r.getString(1),Definition.class),bankId,id);
        if(rows.isEmpty())throw error(404,"LEDGER_NOT_FOUND");return rows.getFirst();
    }
    public Map<String,Object> before(UUID bankId,Change input){return input.ledgerId()==null?Map.of():values(bankId,current(bankId,input.ledgerId()));}
    public Map<String,Object> values(UUID bankId,Change input){return values(bankId,new Definition(input.ledgerId(),bankId,input.code(),input.name().trim(),input.chartAccountId(),input.legalEntityId(),input.currency(),input.active(),0));}
    private Map<String,Object> values(UUID bankId,Definition d){
        String entity=tenants.get(bankId,false).legalEntities().stream().filter(e->e.id().equals(d.legalEntityId())).findFirst().orElseThrow().legalName();
        return Map.of("code",d.code(),"name",d.name(),"chartAccount",chart.get(bankId,d.chartAccountId()).code(),"legalEntity",entity,"currency",d.currency(),"active",d.active());
    }
    public void enqueue(Actor reviewer,UUID bankId,Change input,UUID requestId,String maker){
        AccessService.admin(reviewer);Tenant bank=tenants.get(bankId,true);validate(bank,input);
        UUID id=input.ledgerId()==null?UUID.randomUUID():input.ledgerId();
        long version=input.ledgerId()==null?0:current(bankId,id).version()+1;
        Definition d=new Definition(id,bankId,input.code(),input.name().trim(),input.chartAccountId(),input.legalEntityId(),input.currency(),input.active(),version);
        if(input.ledgerId()==null)jdbc.update("INSERT INTO platform.ledger_registry(id,tenant_id,code,chart_account_id,legal_entity_id,currency,active,version,delivery,request_id) VALUES (?,?,?,?,?,?,?,?,'PENDING',?)",
                id,bankId,d.code(),d.chartAccountId(),d.legalEntityId(),d.currency(),d.active(),version,requestId);
        else jdbc.update("UPDATE platform.ledger_registry SET active=?,version=?,delivery='PENDING',request_id=? WHERE tenant_id=? AND id=?",d.active(),version,requestId,bankId,id);
        jdbc.update("INSERT INTO platform.ledger_delivery(request_id,tenant_id,ledger_id,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?::jsonb,?,?,?,?)",
                requestId,bankId,id,JSON.writeValueAsString(d),maker,reviewer.issuer(),reviewer.subject(),reviewer.correlationId());
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(reviewer,bankId,"LEDGER_CHANGE_APPROVED",bank.version()+1,bank.status(),bank.status(),"Approved request "+requestId,null,null,Map.of("ledgerId",id,"requestId",requestId,"delivery","PENDING"));
    }
    public void retry(Actor actor,UUID bankId,UUID requestId){
        AccessService.admin(actor);
        if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.ledger_delivery WHERE request_id=? AND tenant_id=? AND reviewer_issuer=?)",Boolean.class,requestId,bankId,actor.issuer()))throw error(404,"APPROVAL_NOT_FOUND");
        deliver(requestId);
    }
    public List<UUID> pending(){return jdbc.query("SELECT request_id FROM platform.ledger_delivery WHERE NOT completed ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 20",(r,n)->r.getObject(1,UUID.class));}
    public void deliver(UUID requestId){
        tx.executeWithoutResult(s->{
            var jobs=jdbc.query("SELECT * FROM platform.ledger_delivery WHERE request_id=? AND NOT completed FOR UPDATE",(r,n)->new Dispatch(requestId,JSON.readValue(r.getString("payload"),Definition.class),r.getString("maker_subject"),r.getString("reviewer_issuer"),r.getString("reviewer_subject"),r.getObject("correlation_id",UUID.class)),requestId);
            if(jobs.isEmpty())return;
            Dispatch job=jobs.getFirst();Tenant bank=tenants.get(job.definition().bankId(),true);
            String failure=null;
            if(bank.status()!=Status.ACTIVE)failure="LEDGER_BANK_NOT_ACTIVE";
            else try{store.deliver(bank,job);}catch(TenantException exception){failure=exception.code();}
            jdbc.update("UPDATE platform.ledger_delivery SET attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=? WHERE request_id=?",failure,requestId);
            if(failure!=null)return;
            jdbc.update("UPDATE platform.ledger_delivery SET completed=true,completed_at=clock_timestamp() WHERE request_id=?",requestId);
            jdbc.update("UPDATE platform.ledger_registry SET delivery='APPLIED' WHERE id=? AND request_id=?",job.definition().id(),requestId);
            Actor reviewer=new Actor(job.reviewerIssuer(),job.reviewerSubject(),true,job.correlationId());
            tenants.audit(reviewer,bank.id(),"LEDGER_CHANGE_DELIVERED",bank.version(),bank.status(),bank.status(),"Delivered approved request "+requestId,null,null,Map.of("ledgerId",job.definition().id(),"requestId",requestId));
        });
    }
    private static TenantException error(int status,String code){return new TenantException(status,code,"Ledger account operation could not be completed");}
}
