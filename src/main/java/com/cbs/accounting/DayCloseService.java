package com.cbs.accounting;

import static com.cbs.accounting.DayCloseModels.*;
import com.cbs.access.AccessService;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class DayCloseService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final TenantRepository tenants;private final ReferenceDataService reference;
    private final BankDayCloseStore store;private final TransactionTemplate tx;
    public DayCloseService(JdbcTemplate jdbc,TenantRepository tenants,ReferenceDataService reference,BankDayCloseStore store,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.tenants=tenants;this.reference=reference;this.store=store;this.tx=new TransactionTemplate(manager);
    }
    public static void requireOpen(JdbcTemplate jdbc,UUID bankId){
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_run r JOIN platform.bank_settings b ON b.tenant_id=r.tenant_id WHERE r.tenant_id=? AND r.business_date=b.business_date)",Boolean.class,bankId))throw error(409,"SERVICING_DAY_FINALIZED");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.business_day_close WHERE tenant_id=? AND NOT completed)",Boolean.class,bankId))throw error(409,"DAY_CLOSE_IN_PROGRESS");
    }
    private LocalDate next(Tenant bank){var s=reference.read(bank);return ReferenceDataService.nextWorkingDay(s.businessDate(),s.weekendDays(),s.holidays());}
    private List<String> blockers(Tenant bank,UUID ownRequest){
        List<String> result=new ArrayList<>();
        if(bank.status()!=Status.ACTIVE){result.add("LEDGER_BANK_NOT_ACTIVE");return result;}
        if(!store.ready(bank)){result.add("DAY_CLOSE_STORAGE_NOT_READY");return result;}
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.business_day_close WHERE tenant_id=? AND NOT completed)",Boolean.class,bank.id()))result.add("DAY_CLOSE_IN_PROGRESS");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.approval_request WHERE bank_id=? AND status='PENDING' AND action IN ('JOURNAL_POST','LEDGER_ACCOUNT','DAY_CLOSE','ACCOUNT_FUNDING','ACCOUNT_OPERATION','CASH_OPERATION','SERVICING_RUN','SERVICING_POLICY','CASH_DENOMINATIONS','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS') AND id<>?)",Boolean.class,bank.id(),ownRequest==null?new UUID(0,0):ownRequest))result.add("DAY_CLOSE_PENDING_APPROVALS");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.journal_delivery WHERE tenant_id=? AND NOT completed) OR EXISTS(SELECT 1 FROM platform.ledger_delivery WHERE tenant_id=? AND NOT completed)",Boolean.class,bank.id(),bank.id()))result.add("DAY_CLOSE_PENDING_DELIVERIES");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_operation WHERE tenant_id=? AND NOT completed)",Boolean.class,bank.id())&&!result.contains("DAY_CLOSE_PENDING_DELIVERIES"))result.add("DAY_CLOSE_PENDING_DELIVERIES");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_delivery WHERE tenant_id=? AND NOT completed)",Boolean.class,bank.id()))result.add("CASH_DELIVERY_PENDING");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_location WHERE tenant_id=? AND session_open)",Boolean.class,bank.id()))result.add("CASH_SESSION_OPEN");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_location l JOIN platform.bank_settings s ON s.tenant_id=l.tenant_id WHERE l.tenant_id=? AND l.counted_date IS DISTINCT FROM s.business_date)",Boolean.class,bank.id()))result.add("CASH_COUNT_REQUIRED");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_exception e WHERE e.tenant_id=? AND NOT EXISTS(SELECT 1 FROM platform.cash_exception_resolution r WHERE r.case_id=e.id))",Boolean.class,bank.id()))result.add("CASH_EXCEPTION_OPEN");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_transit t JOIN platform.cash_delivery c ON c.request_id=t.dispatch_id WHERE t.tenant_id=? AND (c.payload->'change'->>'amount')::numeric>coalesce((SELECT sum((r.payload->'change'->>'amount')::numeric) FROM platform.cash_delivery r WHERE r.completed AND r.tenant_id=t.tenant_id AND r.payload->'change'->'transit'->>'dispatchId'=t.dispatch_id::text),0))",Boolean.class,bank.id()))result.add("CASH_IN_TRANSIT_OUTSTANDING");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_location l WHERE l.tenant_id=? AND NOT l.inventory_enabled AND EXISTS(SELECT 1 FROM platform.cash_denomination d WHERE d.tenant_id=l.tenant_id AND d.currency=l.currency))",Boolean.class,bank.id()))result.add("CASH_INVENTORY_REQUIRED");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_policy WHERE tenant_id=?) AND NOT EXISTS(SELECT 1 FROM platform.servicing_run r JOIN platform.bank_settings b ON b.tenant_id=r.tenant_id WHERE r.tenant_id=? AND r.business_date=b.business_date AND r.completed)",Boolean.class,bank.id(),bank.id()))result.add("SERVICING_REQUIRED");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.statement_import WHERE tenant_id=? AND status='OPEN')",Boolean.class,bank.id()))result.add("STATEMENT_IMPORT_OPEN");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.payment_order p WHERE p.tenant_id=? AND (p.reconciliation_conflict OR (p.state NOT IN ('SETTLED','REFUNDED','RETURNED') AND NOT EXISTS(SELECT 1 FROM platform.payment_case c WHERE c.payment_id=p.id AND c.kind='SETTLEMENT_DELAY' AND c.state='CARRIED' AND c.due_date>=? AND p.state IN ('SUBMITTED','UNKNOWN')))))",Boolean.class,bank.id(),next(bank)))result.add("PAYMENT_UNRESOLVED");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.payment_case WHERE tenant_id=? AND state NOT IN ('RESOLVED','CANCELLED') AND (state<>'CARRIED' OR due_date<?))",Boolean.class,bank.id(),next(bank)))result.add("PAYMENT_CASE_OPEN");
        if(!store.balanced(bank))result.add("DAY_CLOSE_UNRECONCILED");
        return result;
    }
    public void requireReadyForServicing(Tenant bank,UUID request){var problems=blockers(bank,request).stream().filter(x->!x.equals("SERVICING_REQUIRED")).toList();if(!problems.isEmpty())throw error(409,problems.getFirst());}
    public Dashboard dashboard(Actor actor,UUID bankId,int limit,int offset){
        AccessService.admin(actor);AccessService.page(limit,offset);
        return tx.execute(t->{
            jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);Tenant bank=tenants.get(bankId,false);
            boolean ready=List.of(Status.ACTIVE,Status.SUSPENDED).contains(bank.status())&&store.ready(bank);
            var history=jdbc.query("SELECT * FROM platform.business_day_close WHERE tenant_id=? ORDER BY business_date DESC,request_id LIMIT ? OFFSET ?",(r,n)->{
                var at=r.getTimestamp("completed_at");String totals=r.getString("totals");
                return new History(r.getObject("request_id",UUID.class),r.getObject("business_date",LocalDate.class),r.getObject("next_business_date",LocalDate.class),r.getBoolean("completed")?"CLOSED":"CLOSING",r.getString("failure_code"),r.getString("maker_subject"),r.getString("reviewer_subject"),totals==null?List.of():JSON.readValue(totals,new TypeReference<List<Total>>(){}),at==null?null:at.toInstant());
            },bankId,limit,offset);
            return new Dashboard(bankId,bank.version(),reference.read(bank).businessDate(),next(bank),ready?store.closedThrough(bank):null,ready,blockers(bank,null),history,limit,offset);
        });
    }
    public void validate(Tenant bank,Change input,UUID ownRequest){
        if(bank.version()!=input.expectedVersion())throw error(409,"STALE_VERSION");
        if(!reference.read(bank).businessDate().equals(input.businessDate())||!next(bank).equals(input.nextBusinessDate())||input.nextBusinessDate().getYear()>2200)throw error(400,"DAY_CLOSE_DATE_INVALID");
        var problems=blockers(bank,ownRequest);if(!problems.isEmpty())throw error(409,problems.getFirst());
        var closed=store.closedThrough(bank);if(closed!=null&&!input.businessDate().isAfter(closed))throw error(409,"BUSINESS_DAY_CLOSED");
    }
    public Map<String,Object> values(Change input){return Map.of("businessDate",input.businessDate().toString(),"nextBusinessDate",input.nextBusinessDate().toString(),"operation","Close the current business day and advance to the next working day");}
    public void enqueue(Actor reviewer,UUID bankId,Change input,UUID requestId,String maker){
        AccessService.admin(reviewer);Tenant bank=tenants.get(bankId,true);validate(bank,input,requestId);
        jdbc.update("INSERT INTO platform.business_day_close(request_id,tenant_id,business_date,next_business_date,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?,?,?,?,?)",requestId,bankId,input.businessDate(),input.nextBusinessDate(),maker,reviewer.issuer(),reviewer.subject(),reviewer.correlationId());
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(reviewer,bankId,"BUSINESS_DAY_CLOSE_APPROVED",bank.version()+1,bank.status(),bank.status(),"Approved request "+requestId,null,null,values(input));
    }
    public List<UUID> pending(){return jdbc.query("SELECT request_id FROM platform.business_day_close WHERE NOT completed ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 20",(r,n)->r.getObject(1,UUID.class));}
    public void retry(Actor actor,UUID bankId,UUID id){
        AccessService.admin(actor);
        if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.business_day_close WHERE request_id=? AND tenant_id=? AND reviewer_issuer=?)",Boolean.class,id,bankId,actor.issuer()))throw error(404,"APPROVAL_NOT_FOUND");
        deliver(id);
    }
    public void deliver(UUID id){
        tx.executeWithoutResult(t->{
            var jobs=jdbc.query("SELECT * FROM platform.business_day_close WHERE request_id=? AND NOT completed FOR UPDATE",(r,n)->new Dispatch(id,r.getObject("tenant_id",UUID.class),r.getObject("business_date",LocalDate.class),r.getObject("next_business_date",LocalDate.class),r.getString("maker_subject"),r.getString("reviewer_issuer"),r.getString("reviewer_subject"),r.getObject("correlation_id",UUID.class)),id);
            if(jobs.isEmpty())return;var job=jobs.getFirst();Tenant bank=tenants.get(job.bankId(),true);
            String failure=null;List<Total> totals=null;
            if(bank.status()!=Status.ACTIVE)failure="LEDGER_BANK_NOT_ACTIVE";
            else if(!reference.read(bank).businessDate().equals(job.businessDate()))failure="DAY_CLOSE_DATE_INVALID";
            else try{totals=store.close(bank,job);}catch(TenantException e){failure=e.code();}
            jdbc.update("UPDATE platform.business_day_close SET attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=? WHERE request_id=?",failure,id);
            if(failure!=null)return;
            jdbc.update("UPDATE platform.bank_settings SET business_date=?,updated_at=clock_timestamp() WHERE tenant_id=?",job.nextBusinessDate(),bank.id());
            jdbc.update("UPDATE platform.business_day_close SET completed=true,completed_at=clock_timestamp(),totals=?::jsonb WHERE request_id=?",JSON.writeValueAsString(totals),id);
            tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
            tenants.audit(new Actor(job.issuer(),job.checker(),true,job.correlationId()),bank.id(),"BUSINESS_DAY_CLOSED",bank.version()+1,bank.status(),bank.status(),"Completed approved request "+id,null,null,Map.of("businessDate",job.businessDate().toString(),"nextBusinessDate",job.nextBusinessDate().toString(),"totals",totals));
        });
    }
    private static TenantException error(int status,String code){return new TenantException(status,code,"Business day close could not be completed");}
}
