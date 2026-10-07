package com.cbs.accounts;

import static com.cbs.accounts.AccountModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class AccountService {
    private final JdbcTemplate jdbc;private final TenantRepository tenants;private final BankAccountStore store;private final BankDatabaseProvisioner databases;private final TransactionTemplate tx;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final com.cbs.customers.CustomerService customers; private final com.cbs.products.ProductService products; private final com.cbs.reference.ReferenceDataService reference; private final com.cbs.accounting.ChartService chart;
    public AccountService(JdbcTemplate jdbc,TenantRepository tenants,BankAccountStore store,BankDatabaseProvisioner databases,PlatformTransactionManager manager,com.cbs.customers.CustomerService customers,com.cbs.products.ProductService products,com.cbs.reference.ReferenceDataService reference,com.cbs.accounting.ChartService chart){this.customers=customers;this.products=products;this.reference=reference;this.chart=chart;this.jdbc=jdbc;this.tenants=tenants;this.store=store;this.databases=databases;this.tx=new TransactionTemplate(manager);}
    private boolean ready(Tenant bank){return List.of(Status.ACTIVE,Status.SUSPENDED).contains(bank.status())&&store.ready(bank);}
    public Directory list(Actor actor,UUID bankId,String search,State state,int limit,int offset){
        AccessService.admin(actor);AccessService.page(limit,offset);if(search.length()>100)throw error(400,"INVALID_REQUEST");
        return tx.execute(t->{
            jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);var bank=tenants.get(bankId,false);
            String where=" WHERE r.tenant_id=?";List<Object> args=new ArrayList<>();args.add(bankId);
            if(!search.isBlank()){where+=" AND position(lower(?) in lower(r.account_number || ' ' || (d.payload->>'name') || ' ' || (d.payload->>'customerNumber')))>0";args.add(search.trim());}
            if(state!=null){where+=" AND d.payload->>'state'=?";args.add(state.name());}
            String from=" FROM platform.account_registry r JOIN platform.account_delivery d ON d.request_id=r.request_id";
            long total=jdbc.queryForObject("SELECT count(*)"+from+where,Long.class,args.toArray());args.add(limit);args.add(offset);
            var rows=jdbc.query("SELECT r.*,d.payload,d.failure_code"+from+where+" ORDER BY r.account_number LIMIT ? OFFSET ?",(r,n)->{
                var d=JSON.readValue(r.getString("payload"),Definition.class);return new Summary(d.id(),d.accountNumber(),d.name(),d.customerNumber(),d.terms().code(),d.terms().currency(),d.state(),d.version(),r.getString("delivery"),r.getObject("request_id",UUID.class),r.getString("failure_code"));
            },args.toArray());
            return new Directory(bankId,bank.version(),ready(bank),rows,total,limit,offset);
        });
    }
    public Detail current(UUID bankId,UUID id){
        var rows=jdbc.query("SELECT d.payload,r.delivery,r.request_id,d.failure_code FROM platform.account_registry r JOIN platform.account_delivery d ON d.request_id=r.request_id WHERE r.tenant_id=? AND r.id=?",(r,n)->new Detail(JSON.readValue(r.getString("payload"),Definition.class),r.getString("delivery"),r.getObject("request_id",UUID.class),r.getString("failure_code")),bankId,id);
        if(rows.isEmpty())throw error(404,"ACCOUNT_NOT_FOUND");return rows.getFirst();
    }
    public void requireMatching(Tenant bank,Definition d){if(!d.equals(store.read(bank,d.id())))throw error(503,"ACCOUNT_RECONCILIATION_REQUIRED");}
    public Detail detail(Actor actor,UUID bankId,UUID id){
        AccessService.admin(actor);return tx.execute(t->{
            jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);var bank=tenants.get(bankId,false);var row=current(bankId,id);
            if(row.delivery().equals("APPLIED")&&!row.definition().equals(store.read(bank,id)))throw error(503,"ACCOUNT_RECONCILIATION_REQUIRED");
            tenants.audit(actor,bankId,"ACCOUNT_PROFILE_VIEWED",bank.version(),bank.status(),bank.status(),"Account detail viewed",null,null,Map.of("accountId",id));return row;
        });
    }
    public void initialize(Actor actor,UUID bankId,ChangeRequest input){
        AccessService.admin(actor);tx.executeWithoutResult(t->{var bank=tenants.get(bankId,true);
            if(bank.version()!=input.expectedVersion())throw error(409,"STALE_VERSION");if(bank.status()!=Status.ACTIVE)throw error(409,"ACCOUNT_BANK_NOT_ACTIVE");
            String version=databases.upgradeOwned(bank.id(),bank.databaseBinding().databaseReference());
            jdbc.update("UPDATE platform.database_binding SET schema_version=? WHERE tenant_id=?",version,bankId);tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
            tenants.audit(actor,bankId,"ACCOUNT_STORAGE_PREPARED",bank.version()+1,bank.status(),bank.status(),input.reason(),null,null,Map.of("schemaVersion",version));
        });
    }
    // Approval and delivery serialize on the tenant row, including dependency changes.
    public void validate(Tenant bank,Change input){
        com.cbs.accounting.DayCloseService.requireOpen(jdbc,bank.id());
        if(bank.version()!=input.expectedVersion())throw error(409,"STALE_VERSION");
        if(bank.status()!=Status.ACTIVE)throw error(409,"ACCOUNT_BANK_NOT_ACTIVE");
        if(!store.ready(bank))throw error(409,"ACCOUNT_STORAGE_NOT_READY");
        if(input.accountId()!=null){
            OperationService.requireIdle(jdbc,input.accountId());
            var row=current(bank.id(),input.accountId());var d=row.definition();
            if(!row.delivery().equals("APPLIED"))throw error(409,"ACCOUNT_DELIVERY_PENDING");
            if(!d.equals(store.read(bank,d.id())))throw error(503,"ACCOUNT_RECONCILIATION_REQUIRED");
            if(!d.customerId().equals(input.customerId())||!d.terms().id().equals(input.productId())||d.terms().revision()!=input.productRevision()||!d.legalEntityId().equals(input.legalEntityId()))throw error(400,"ACCOUNT_STRUCTURE_IMMUTABLE");
            if(d.state()==State.CLOSED)throw error(409,"ACCOUNT_ALREADY_CLOSED");
            boolean funded=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_ledger WHERE account_id=?)",Boolean.class,d.id());
            if(funded&&(input.state()==State.CLOSED||input.state()==State.AWAITING_FUNDING))throw error(409,"FUNDING_BALANCE_NOT_ZERO");
            if(!funded&&input.state()==State.ACTIVE)throw error(409,"FUNDING_REQUIRED");
            // Blocking or closing must remain possible even when the customer is no longer eligible.
            if(input.state()==State.AWAITING_FUNDING||input.state()==State.ACTIVE)customers.requireEligible(bank,input.customerId());
        }else{
            if(input.state()!=State.AWAITING_FUNDING)throw error(400,"ACCOUNT_INITIAL_STATE");
            customers.requireEligible(bank,input.customerId());
            var p=products.get(bank.id(),input.productId());
            if(!p.active()||p.revision()!=input.productRevision())throw error(409,"ACCOUNT_PRODUCT_NOT_READY");
            if(bank.legalEntities().stream().noneMatch(e->e.id().equals(input.legalEntityId())))throw error(400,"ACCOUNT_INVALID_ENTITY");
            if(reference.read(bank).currencies().stream().noneMatch(c->c.enabled()&&c.code().equals(p.currency()))||!chart.get(bank.id(),p.liabilityAccountId()).active())throw error(409,"ACCOUNT_PRODUCT_NOT_READY");
        }
    }
    public Map<String,Object> before(UUID bankId,Change input){return input.accountId()==null?Map.of():values(current(bankId,input.accountId()).definition());}
    public Map<String,Object> values(UUID bankId,Change input){
        var p=input.accountId()==null?products.get(bankId,input.productId()):current(bankId,input.accountId()).definition().terms();
        var bank=tenants.get(bankId,false);
        var c=customers.number(bankId,input.customerId());
        return comparison(input.name().trim(),input.state(),c,p,bank.legalEntities().stream().filter(e->e.id().equals(input.legalEntityId())).findFirst().orElseThrow().legalName());
    }
    private Map<String,Object> values(Definition d){return comparison(d.name(),d.state(),d.customerNumber(),d.terms(),d.legalEntityName());}
    private Map<String,Object> comparison(String name,State state,String customer,com.cbs.products.ProductModels.Product p,String entity){
        Map<String,Object> m=new LinkedHashMap<>();m.put("name",name);m.put("state",state.name());m.put("customerNumber",customer);m.put("productCode",p.code());m.put("productRevision",p.revision());m.put("currency",p.currency());m.put("minimumOpening",p.minimumOpening());m.put("minimumBalance",p.minimumBalance());m.put("liabilityAccountCode",p.liabilityAccountCode());m.put("legalEntityName",entity);return m;
    }
    public void enqueue(Actor reviewer,UUID bankId,Change input,UUID requestId,String maker){
        AccessService.admin(reviewer);var bank=tenants.get(bankId,true);validate(bank,input);
        var old=input.accountId()==null?null:current(bankId,input.accountId()).definition();UUID id=old==null?UUID.randomUUID():old.id();
        var p=old==null?products.get(bankId,input.productId()):old.terms();
        var d=new Definition(id,bankId,old==null?"A"+id.toString().replace("-","").toUpperCase(Locale.ROOT):old.accountNumber(),old==null?0:old.version()+1,input.customerId(),customers.number(bankId,input.customerId()),input.legalEntityId(),bank.legalEntities().stream().filter(e->e.id().equals(input.legalEntityId())).findFirst().orElseThrow().legalName(),input.name().trim(),input.state(),p,old==null?java.time.Instant.now():old.openedAt());
        if(old==null)jdbc.update("INSERT INTO platform.account_registry(id,tenant_id,account_number,customer_id,product_id,product_revision,currency,liability_account_id,state,version,request_id,delivery) VALUES (?,?,?,?,?,?,?,?,?,?,?,'PENDING')",id,bankId,d.accountNumber(),d.customerId(),p.id(),p.revision(),p.currency(),p.liabilityAccountId(),d.state().name(),d.version(),requestId);
        else jdbc.update("UPDATE platform.account_registry SET state=?,version=?,request_id=?,delivery='PENDING' WHERE tenant_id=? AND id=?",d.state().name(),d.version(),requestId,bankId,id);
        jdbc.update("INSERT INTO platform.account_delivery(request_id,tenant_id,account_id,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?::jsonb,?,?,?,?)",requestId,bankId,id,JSON.writeValueAsString(d),maker,reviewer.issuer(),reviewer.subject(),reviewer.correlationId());
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(reviewer,bankId,"ACCOUNT_CHANGE_APPROVED",bank.version()+1,bank.status(),bank.status(),"Approved request "+requestId,null,null,Map.of("accountId",id,"requestId",requestId,"delivery","PENDING"));
    }
    public List<UUID> pending(){return jdbc.query("SELECT request_id FROM platform.account_delivery WHERE NOT completed AND request_id NOT IN (SELECT request_id FROM platform.funding_delivery) ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 20",(r,n)->r.getObject(1,UUID.class));}
    public void retry(Actor actor,UUID bankId,UUID id){AccessService.admin(actor);if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_delivery WHERE request_id=? AND tenant_id=? AND reviewer_issuer=?)",Boolean.class,id,bankId,actor.issuer()))throw error(404,"APPROVAL_NOT_FOUND");deliver(id);}
    public void deliver(UUID id){if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.funding_delivery WHERE request_id=?)",Boolean.class,id))throw error(409,"FUNDING_MANAGED");tx.executeWithoutResult(t->{
        var jobs=jdbc.query("SELECT * FROM platform.account_delivery WHERE request_id=? AND NOT completed FOR UPDATE",(r,n)->new Dispatch(id,JSON.readValue(r.getString("payload"),Definition.class),r.getString("maker_subject"),r.getString("reviewer_issuer"),r.getString("reviewer_subject"),r.getObject("correlation_id",UUID.class)),id);
        if(jobs.isEmpty())return;var job=jobs.getFirst();var bank=tenants.get(job.definition().bankId(),true);String failure=null;
        if(bank.status()!=Status.ACTIVE)failure="ACCOUNT_BANK_NOT_ACTIVE";else try{if(!store.posted(bank,id)&&(job.definition().state()==State.ACTIVE||job.definition().state()==State.AWAITING_FUNDING))customers.requireEligible(bank,job.definition().customerId());store.deliver(bank,job);}catch(TenantException e){failure=e.code();}
        jdbc.update("UPDATE platform.account_delivery SET attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=? WHERE request_id=?",failure,id);if(failure!=null)return;
        jdbc.update("UPDATE platform.account_delivery SET completed=true,completed_at=clock_timestamp() WHERE request_id=?",id);jdbc.update("UPDATE platform.account_registry SET delivery='APPLIED' WHERE id=? AND request_id=?",job.definition().id(),id);
        tenants.audit(new Actor(job.reviewerIssuer(),job.reviewerSubject(),true,job.correlationId()),bank.id(),"ACCOUNT_CHANGE_DELIVERED",bank.version(),bank.status(),bank.status(),"Delivered approved request "+id,null,null,Map.of("accountId",job.definition().id(),"requestId",id));
    });}
    private static TenantException error(int status,String code){return new TenantException(status,code,"Deposit account operation could not be completed");}
}
