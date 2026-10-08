package com.cbs.accounting;

import static com.cbs.accounting.JournalModels.*;
import com.cbs.access.AccessService;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class JournalService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final ReferenceDataService reference;
    private final BankJournalStore store;
    private final BankLedgerStore ledgers;
    private final TransactionTemplate tx;
    public JournalService(JdbcTemplate jdbc,TenantRepository tenants,ReferenceDataService reference,BankJournalStore store,BankLedgerStore ledgers,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.tenants=tenants;this.reference=reference;this.store=store;this.ledgers=ledgers;this.tx=new TransactionTemplate(manager);
    }
    public Directory list(Actor actor,UUID bankId,int limit,int offset){
        AccessService.admin(actor);AccessService.page(limit,offset);
        return tx.execute(s->{
            jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);
            Tenant bank=tenants.get(bankId,false);
            boolean ready=List.of(Status.ACTIVE,Status.SUSPENDED).contains(bank.status())&&store.ready(bank);
            var rows=jdbc.query("SELECT payload::text,completed,failure_code,(EXISTS(SELECT 1 FROM platform.funding_delivery f WHERE f.request_id=platform.journal_delivery.request_id) OR EXISTS(SELECT 1 FROM platform.account_operation o WHERE o.request_id=platform.journal_delivery.request_id) OR EXISTS(SELECT 1 FROM platform.cash_delivery x WHERE x.request_id=platform.journal_delivery.request_id) OR EXISTS(SELECT 1 FROM platform.servicing_posting sp WHERE sp.request_id=platform.journal_delivery.request_id) OR EXISTS(SELECT 1 FROM platform.fx_posting fp WHERE fp.journal_id=platform.journal_delivery.request_id) OR EXISTS(SELECT 1 FROM platform.contract_delivery cd WHERE cd.id=platform.journal_delivery.request_id) OR EXISTS(SELECT 1 FROM platform.fx_valuation v WHERE v.id=platform.journal_delivery.request_id)) funding FROM platform.journal_delivery WHERE tenant_id=? ORDER BY created_at DESC,request_id LIMIT ? OFFSET ?",
                    (r,n)->new Item(JSON.readValue(r.getString(1),Entry.class),r.getBoolean(2)?"POSTED":"AWAITING_POSTING",r.getString(3),r.getBoolean(4)),bankId,limit,offset);
            return new Directory(bankId,bank.version(),ready,rows,jdbc.queryForObject("SELECT count(*) FROM platform.journal_delivery WHERE tenant_id=?",Long.class,bankId),limit,offset);
        });
    }
    public Page<Balance> balances(Actor actor,UUID bankId,int limit,int offset){
        AccessService.admin(actor);AccessService.page(limit,offset);
        return tx.execute(s->{
            jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);
            Tenant bank=tenants.get(bankId,false);
            if(!List.of(Status.ACTIVE,Status.SUSPENDED).contains(bank.status())||!store.ready(bank))throw error(409,"JOURNAL_STORAGE_NOT_READY");
            return new Page<>(store.balances(bank,limit,offset),limit,offset);
        });
    }
    public Entry posted(Actor actor,UUID bankId,UUID id){AccessService.admin(actor);return store.read(tenants.get(bankId,false),id);}
    public Entry postedForReconciliation(Actor actor,UUID bankId,UUID id){com.cbs.payments.PaymentAccess.require(jdbc,actor,bankId,com.cbs.payments.PaymentAccess.Capability.READ);return store.read(tenants.get(bankId,false),id);}
    public Change reversal(Actor actor,UUID bankId,UUID id,Reversal input){
        AccessService.admin(actor);Tenant bank=tenants.get(bankId,false);Entry original=store.read(bank,id);
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_valuation WHERE id=?)",Boolean.class,id))throw error(409,"FX_VALUATION_MANAGED");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_delivery WHERE id=?)",Boolean.class,id))throw error(409,"CONTRACT_MANAGED");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_posting WHERE journal_id=?)",Boolean.class,id))throw error(409,"FX_MANAGED");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_posting WHERE request_id=?)",Boolean.class,id))throw error(409,"SERVICING_MANAGED");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.funding_delivery WHERE request_id=?) OR EXISTS(SELECT 1 FROM platform.account_operation WHERE request_id=?) OR EXISTS(SELECT 1 FROM platform.cash_delivery WHERE request_id=?)",Boolean.class,id,id,id))throw error(409,"FUNDING_MANAGED");
        if(original.reversalOf()!=null)throw error(409,"JOURNAL_REVERSAL_INVALID");
        return new Change(input.expectedVersion(),input.reference(),input.reason(),reference.read(bank).businessDate(),original.legalEntityId(),original.currency(),opposite(original.lines()),id,input.reason());
    }
    private static List<Line> opposite(List<Line> lines){return lines.stream().map(l->new Line(l.ledgerId(),l.side()==Side.DEBIT?Side.CREDIT:Side.DEBIT,l.amount())).toList();}
    /** Must run under the tenant lock, shared by settings, ledger changes and journal dispatch. */
    public void validate(Tenant bank,Change input){validate(bank,input,false,false);}
    public void validateFunding(Tenant bank,Change input){validate(bank,input,true,false);}
    public void validateCashFunding(Tenant bank,Change input){validate(bank,input,true,true);}
    public void validateCash(Tenant bank,Change input){validate(bank,input,false,true);}
    public void validateStatement(Tenant bank,Change input){validate(bank,input,false,false,true);}
    private void validate(Tenant bank,Change input,boolean funding,boolean cash){validate(bank,input,funding,cash,false);}
    public void validateFx(Tenant bank,Change input){validate(bank,input,true,false,false,true);}
    private void validate(Tenant bank,Change input,boolean funding,boolean cash,boolean statement){validate(bank,input,funding,cash,statement,false);}
    public void validateContract(Tenant bank,Change input){validate(bank,input,true,false,false,false,true);}
    private void validate(Tenant bank,Change input,boolean funding,boolean cash,boolean statement,boolean fx){validate(bank,input,funding,cash,statement,fx,false);}
    public void validateFxValuation(Tenant bank,Change input){validate(bank,input,false,false,false,true,false,true);}
    private void validate(Tenant bank,Change input,boolean funding,boolean cash,boolean statement,boolean fx,boolean contract){
        validate(bank,input,funding,cash,statement,fx,contract,false);
    }
    private void validate(Tenant bank,Change input,boolean funding,boolean cash,boolean statement,boolean fx,boolean contract,boolean valuation){
        DayCloseService.requireOpen(jdbc,bank.id());
        if(bank.version()!=input.expectedVersion())throw error(409,"STALE_VERSION");
        if(bank.status()!=Status.ACTIVE)throw error(409,"LEDGER_BANK_NOT_ACTIVE");
        if(!store.ready(bank))throw error(409,"JOURNAL_STORAGE_NOT_READY");
        var settings=reference.read(bank);
        if(!settings.businessDate().equals(input.businessDate()))throw error(400,"JOURNAL_BUSINESS_DATE_INVALID");
        if(settings.currencies().stream().noneMatch(c->c.code().equals(input.currency())&&c.enabled()))throw error(400,"CURRENCY_NOT_ENABLED");
        if(bank.legalEntities().stream().noneMatch(e->e.id().equals(input.legalEntityId())))throw error(400,"LEGAL_ENTITY_NOT_IN_BANK");
        int scale=Currency.getInstance(input.currency()).getDefaultFractionDigits();
        BigDecimal debit=BigDecimal.ZERO,credit=BigDecimal.ZERO;
        if(input.lines()==null||input.lines().size()<2||input.lines().size()>100)throw error(400,"JOURNAL_LINES_INVALID");
        for(Line line:input.lines()){
            if(line==null||line.ledgerId()==null||line.side()==null||line.amount()==null||!line.amount().matches("[0-9]{1,18}(\\.[0-9]{1,6})?"))throw error(400,"JOURNAL_AMOUNT_INVALID");
            BigDecimal amount=new BigDecimal(line.amount());
            if(amount.signum()<=0||scale<0||amount.stripTrailingZeros().scale()>scale)throw error(400,"JOURNAL_AMOUNT_INVALID");
            if(line.side()==Side.DEBIT)debit=debit.add(amount);else credit=credit.add(amount);
        }
        if(debit.compareTo(credit)!=0)throw error(400,"JOURNAL_UNBALANCED");
        var ids=input.lines().stream().map(Line::ledgerId).distinct().toList();
        if(ids.size()<2)throw error(400,"JOURNAL_LINES_INVALID");
        var actual=ledgers.read(bank,ids);
        for(UUID id:ids){
            if(!valuation && jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_book_ledger bl JOIN platform.fx_book b ON b.id=bl.book_id WHERE bl.ledger_id=? AND (?=false OR (bl.ledger_id<>(b.payload->'policy'->>'foreignPositionLedger')::uuid AND bl.ledger_id<>(b.payload->'policy'->>'functionalPositionLedger')::uuid)))",Boolean.class,id,fx))throw error(409,"FX_VALUATION_MANAGED");
            if(!contract&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_ledger WHERE ledger_id=?)",Boolean.class,id))throw error(409,"CONTRACT_MANAGED");
            if(!fx&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_position WHERE ledger_id=?)",Boolean.class,id))throw error(409,"FX_MANAGED");
            com.cbs.payments.StatementImportService.requireWritable(jdbc,bank.id(),id,input.businessDate());
            if(!cash&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_location WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.cash_transit WHERE ledger_id=?)",Boolean.class,id,id))throw error(409,"CASH_MANAGED");
            if(!funding&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_ledger WHERE ledger_id=?)",Boolean.class,id))throw error(409,"FUNDING_MANAGED");
            var rows=jdbc.query("SELECT d.payload::text FROM platform.ledger_registry r JOIN platform.ledger_delivery d ON d.request_id=r.request_id JOIN platform.chart_account c ON c.id=r.chart_account_id WHERE r.id=? AND r.tenant_id=? AND r.active AND r.delivery='APPLIED' AND c.active AND c.kind='POSTING' AND r.currency=? AND r.legal_entity_id=?",
                    (r,n)->JSON.readValue(r.getString(1),LedgerModels.Definition.class),id,bank.id(),input.currency(),input.legalEntityId());
            if(rows.size()!=1||!actual.contains(rows.getFirst()))throw error(409,"JOURNAL_LEDGER_NOT_READY");
        }
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.journal_delivery WHERE tenant_id=? AND reference=?)",Boolean.class,bank.id(),input.reference().trim()))throw error(409,"JOURNAL_REFERENCE_IN_USE");
        if(input.reversalOf()!=null){
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_valuation WHERE id=?)",Boolean.class,input.reversalOf()))throw error(409,"FX_VALUATION_MANAGED");
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_delivery WHERE id=?)",Boolean.class,input.reversalOf()))throw error(409,"CONTRACT_MANAGED");
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_posting WHERE journal_id=?)",Boolean.class,input.reversalOf()))throw error(409,"FX_MANAGED");
            if(!statement&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.statement_decision WHERE journal_id=? AND kind IN ('BOOK','REVERSE'))",Boolean.class,input.reversalOf()))throw error(409,"STATEMENT_MANAGED");
            Entry original=store.read(bank,input.reversalOf());
            if(original.reversalOf()!=null||!original.currency().equals(input.currency())||!original.legalEntityId().equals(input.legalEntityId())||!opposite(original.lines()).equals(input.lines()))throw error(400,"JOURNAL_REVERSAL_INVALID");
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.journal_delivery WHERE reversal_of=?)",Boolean.class,input.reversalOf()))throw error(409,"JOURNAL_ALREADY_REVERSED");
        }
    }
    public Map<String,Object> values(UUID bankId,Change input){
        var bank=tenants.get(bankId,false);
        var names=ledgers.read(bank,input.lines().stream().map(Line::ledgerId).distinct().toList());
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("reference",input.reference().trim());result.put("narration",input.narration().trim());result.put("businessDate",input.businessDate().toString());result.put("currency",input.currency());
        result.put("legalEntity",bank.legalEntities().stream().filter(e->e.id().equals(input.legalEntityId())).findFirst().orElseThrow().legalName());
        result.put("lines",input.lines().stream().map(l->Map.of("ledger",names.stream().filter(a->a.id().equals(l.ledgerId())).findFirst().orElseThrow().code(),"side",l.side().name(),"amount",l.amount())).toList());
        if(input.reversalOf()!=null)result.put("reversalOf",input.reversalOf().toString());return result;
    }
    public void enqueue(Actor reviewer,UUID bankId,Change input,UUID requestId,String maker){enqueue(reviewer,bankId,input,requestId,maker,false);}
    public void enqueueStatement(Actor reviewer,UUID bankId,Change input,UUID requestId,String maker){enqueue(reviewer,bankId,input,requestId,maker,true);}
    private void enqueue(Actor reviewer,UUID bankId,Change input,UUID requestId,String maker,boolean statement){
        if(statement)com.cbs.payments.PaymentAccess.require(jdbc,reviewer,bankId,com.cbs.payments.PaymentAccess.Capability.APPROVE);else AccessService.admin(reviewer);Tenant bank=tenants.get(bankId,true);validate(bank,input,false,false,statement);
        Entry entry=new Entry(requestId,bankId,input.reference().trim(),input.narration().trim(),input.businessDate(),input.legalEntityId(),input.currency(),List.copyOf(input.lines()),input.reversalOf());
        jdbc.update("INSERT INTO platform.journal_delivery(request_id,tenant_id,reference,reversal_of,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",
                requestId,bankId,entry.reference(),entry.reversalOf(),JSON.writeValueAsString(entry),maker,reviewer.issuer(),reviewer.subject(),reviewer.correlationId());
        for(UUID id:input.lines().stream().map(Line::ledgerId).distinct().toList())jdbc.update("INSERT INTO platform.journal_ledger(request_id,ledger_id) VALUES (?,?)",requestId,id);
        tenants.audit(reviewer,bankId,"JOURNAL_APPROVED",bank.version(),bank.status(),bank.status(),"Approved request "+requestId,null,null,Map.of("requestId",requestId,"postingStatus","AWAITING_POSTING"));
    }
    public void retry(Actor actor,UUID bankId,UUID id){
        AccessService.admin(actor);
        if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.journal_delivery WHERE request_id=? AND tenant_id=? AND reviewer_issuer=?)",Boolean.class,id,bankId,actor.issuer()))throw error(404,"APPROVAL_NOT_FOUND");
        deliver(id);
    }
    public List<UUID> pending(){return jdbc.query("SELECT request_id FROM platform.journal_delivery WHERE NOT completed AND request_id NOT IN (SELECT request_id FROM platform.funding_delivery UNION ALL SELECT request_id FROM platform.account_operation UNION ALL SELECT request_id FROM platform.cash_delivery UNION ALL SELECT request_id FROM platform.servicing_posting UNION ALL SELECT journal_id FROM platform.fx_posting UNION ALL SELECT id FROM platform.contract_delivery UNION ALL SELECT id FROM platform.fx_valuation) ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 20",(r,n)->r.getObject(1,UUID.class));}
    public void deliver(UUID id){
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_valuation WHERE id=?)",Boolean.class,id))throw error(409,"FX_VALUATION_MANAGED");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_delivery WHERE id=?)",Boolean.class,id))throw error(409,"CONTRACT_MANAGED");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_posting WHERE journal_id=?)",Boolean.class,id))throw error(409,"FX_MANAGED");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_posting WHERE request_id=?)",Boolean.class,id))throw error(409,"SERVICING_MANAGED");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.funding_delivery WHERE request_id=?) OR EXISTS(SELECT 1 FROM platform.account_operation WHERE request_id=?) OR EXISTS(SELECT 1 FROM platform.cash_delivery WHERE request_id=?)",Boolean.class,id,id,id))throw error(409,"FUNDING_MANAGED");
        tx.executeWithoutResult(s->{
            var jobs=jdbc.query("SELECT * FROM platform.journal_delivery WHERE request_id=? AND NOT completed FOR UPDATE",(r,n)->new Dispatch(JSON.readValue(r.getString("payload"),Entry.class),r.getString("maker_subject"),r.getString("reviewer_issuer"),r.getString("reviewer_subject"),r.getObject("correlation_id",UUID.class)),id);
            if(jobs.isEmpty())return;Dispatch job=jobs.getFirst();Tenant bank=tenants.get(job.entry().bankId(),true);
            String failure=null;
            if(bank.status()!=Status.ACTIVE)failure="LEDGER_BANK_NOT_ACTIVE";
            else try{store.post(bank,job);}catch(TenantException e){failure=e.code();}
            jdbc.update("UPDATE platform.journal_delivery SET attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=? WHERE request_id=?",failure,id);
            if(failure!=null)return;
            jdbc.update("UPDATE platform.journal_delivery SET completed=true,completed_at=clock_timestamp() WHERE request_id=?",id);
            tenants.audit(new Actor(job.issuer(),job.checker(),true,job.correlationId()),bank.id(),"JOURNAL_POSTED",bank.version(),bank.status(),bank.status(),"Posted approved request "+id,null,null,Map.of("requestId",id));
        });
    }
    private static TenantException error(int status,String code){return new TenantException(status,code,"Journal operation could not be completed");}
}
