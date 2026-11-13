package com.cbs.batch;

import com.cbs.access.AccessService;
import com.cbs.accounting.DayCloseService;
import com.cbs.approvals.ApprovalService;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * P-07: the daily cycle without manual preparation. For each enabled bank, once the business day's cut-off
 * has passed in the bank's timezone, the scheduler submits the next step as a configured service identity:
 * the servicing run (when servicing policies exist), the month- or year-end close (when due), then the day
 * close. Approval always stays with people. The service identity is an ordinary staff identity, so the bank
 * can disable it, must grant it the finance duty when duties are enforced, and can never approve its own requests.
 *
 * <p>The scheduler only submits: it never approves, delivers or changes the bank's version. Each outcome is
 * kept in {@code platform.batch_event} when it changes, for the operations console.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class BatchService {
    public enum Step { SERVICING, PERIOD_CLOSE, DAY_CLOSE }
    public record Outcome(Step step,String outcome,String code,UUID requestId) {}

    private final JdbcTemplate jdbc;private final TenantRepository tenants;private final ReferenceDataService reference;
    private final DayCloseService days;private final ApprovalService approvals;
    private final String issuer,subject;private final Set<UUID> banks;private final LocalTime cutoff;

    public BatchService(JdbcTemplate jdbc,TenantRepository tenants,ReferenceDataService reference,DayCloseService days,ApprovalService approvals,
            @Value("${cbs.batch.service-issuer:}") String issuer,@Value("${cbs.batch.service-subject:}") String subject,
            @Value("${cbs.batch.banks:}") List<String> banks,@Value("${cbs.batch.prepare-after:18:00}") String cutoff){
        this.jdbc=jdbc;this.tenants=tenants;this.reference=reference;this.days=days;this.approvals=approvals;this.issuer=issuer.strip();this.subject=subject.strip();
        var set=new LinkedHashSet<UUID>();for(String b:banks)if(!b.isBlank())set.add(UUID.fromString(b.strip()));this.banks=Set.copyOf(set);
        this.cutoff=LocalTime.parse(cutoff.strip());
        if(!this.banks.isEmpty()&&(this.issuer.isBlank()||this.subject.isBlank()))throw new IllegalStateException("cbs.batch.service-issuer and cbs.batch.service-subject are required when cbs.batch.banks is set");
    }

    public boolean enabled(UUID bank){return banks.contains(bank);}
    public Set<UUID> banks(){return banks;}
    Actor service(){return new Actor(issuer,subject,true,UUID.randomUUID());}
    static UUID key(UUID bank,Step step,LocalDate date){return UUID.nameUUIDFromBytes(("cbs-batch:"+bank+":"+step+":"+date).getBytes(StandardCharsets.UTF_8));}
    private boolean exists(String sql,Object... args){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS("+sql+")",Boolean.class,args));}
    private UUID pending(UUID bank,String action){var ids=jdbc.queryForList("SELECT id FROM platform.approval_request WHERE bank_id=? AND action=? AND status='PENDING' ORDER BY created_at LIMIT 1",UUID.class,bank,action);return ids.isEmpty()?null:ids.getFirst();}

    /** Runs every enabled bank once; returns the outcomes. Errors in one bank never stop the others. */
    public Map<UUID,Outcome> tick(Instant now){
        var result=new LinkedHashMap<UUID,Outcome>();
        for(UUID bank:banks){
            try{var o=prepare(bank,now);if(o!=null)result.put(bank,o);}
            catch(RuntimeException e){LoggerFactory.getLogger(BatchService.class).warn("Batch preparation will be retried: {}",e.getClass().getSimpleName());}
        }
        return result;
    }

    /** The next step for one bank, submitted if it is due and not already waiting; null before the cut-off. */
    public Outcome prepare(UUID bankId,Instant now){
        var bank=tenants.get(bankId,false);if(bank.status()!=Status.ACTIVE)return null;
        var settings=reference.read(bank);LocalDate date=settings.businessDate();
        if(now.isBefore(date.atTime(cutoff).atZone(ZoneId.of(settings.businessTimezone())).toInstant()))return null;
        Outcome outcome=next(bank,date);
        record(bankId,date,outcome);
        return outcome;
    }

    private Outcome next(Tenant bank,LocalDate date){
        UUID id=bank.id();
        if(exists("SELECT 1 FROM platform.servicing_policy WHERE tenant_id=?",id)&&!exists("SELECT 1 FROM platform.servicing_run WHERE tenant_id=? AND business_date=? AND completed",id,date)){
            if(exists("SELECT 1 FROM platform.servicing_run WHERE tenant_id=? AND business_date=? AND NOT completed",id,date))return new Outcome(Step.SERVICING,"WAITING_DELIVERY",null,null);
            UUID waiting=pending(id,"SERVICING_RUN");if(waiting!=null)return new Outcome(Step.SERVICING,"WAITING_APPROVAL",null,waiting);
            return submit(Step.SERVICING,key(id,Step.SERVICING,date),()->approvals.servicingRun(service(),key(id,Step.SERVICING,date),id,new com.cbs.servicing.ServicingModels.Run(bank.version(),date,"Scheduled daily servicing for "+date)).id());
        }
        if(days.periodEnd(bank)&&exists("SELECT 1 FROM platform.period_close_policy WHERE tenant_id=?",id)&&!exists("SELECT 1 FROM platform.period_close WHERE tenant_id=? AND period_end=? AND completed",id,date)){
            if(exists("SELECT 1 FROM platform.period_close WHERE tenant_id=? AND period_end=? AND NOT completed",id,date))return new Outcome(Step.PERIOD_CLOSE,"WAITING_DELIVERY",null,null);
            UUID waiting=pending(id,"PERIOD_CLOSE");if(waiting!=null)return new Outcome(Step.PERIOD_CLOSE,"WAITING_APPROVAL",null,waiting);
            return submit(Step.PERIOD_CLOSE,key(id,Step.PERIOD_CLOSE,date),()->approvals.periodClose(service(),key(id,Step.PERIOD_CLOSE,date),id,new com.cbs.accounting.PeriodCloseModels.Run(bank.version(),date,"Scheduled period close for "+date)).id());
        }
        if(exists("SELECT 1 FROM platform.business_day_close WHERE tenant_id=? AND NOT completed",id))return new Outcome(Step.DAY_CLOSE,"WAITING_DELIVERY",null,null);
        UUID waiting=pending(id,"DAY_CLOSE");if(waiting!=null)return new Outcome(Step.DAY_CLOSE,"WAITING_APPROVAL",null,waiting);
        return submit(Step.DAY_CLOSE,key(id,Step.DAY_CLOSE,date),()->approvals.closeDay(service(),key(id,Step.DAY_CLOSE,date),id,new com.cbs.accounting.DayCloseModels.Change(bank.version(),date,days.nextBusinessDate(bank),"Scheduled day close for "+date)).id());
    }
    private Outcome submit(Step step,UUID key,java.util.function.Supplier<UUID> action){
        // A checker rejected today's request: the scheduler does not resubmit it; people decide what happens next.
        var rejected=jdbc.queryForList("SELECT id FROM platform.approval_request WHERE issuer=? AND maker_subject=? AND idempotency_key=? AND status='REJECTED'",UUID.class,issuer,subject,key);
        if(!rejected.isEmpty())return new Outcome(step,"BLOCKED","BATCH_REQUEST_REJECTED",rejected.getFirst());
        try{return new Outcome(step,"SUBMITTED",null,action.get());}
        catch(TenantException e){return new Outcome(step,"BLOCKED",e.code(),null);}
    }
    /** Keeps an event only when the outcome for the step changes, so a blocked day does not fill the log. */
    private void record(UUID bank,LocalDate date,Outcome o){
        var last=jdbc.queryForList("SELECT step,outcome,coalesce(code,'') AS code FROM platform.batch_event WHERE tenant_id=? AND business_date=? ORDER BY recorded_at DESC LIMIT 1",bank,date);
        if(!last.isEmpty()&&o.step().name().equals(last.getFirst().get("step"))&&o.outcome().equals(last.getFirst().get("outcome"))&&Objects.equals(o.code()==null?"":o.code(),last.getFirst().get("code")))return;
        jdbc.update("INSERT INTO platform.batch_event(id,tenant_id,business_date,step,outcome,code,request_id) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID(),bank,date,o.step().name(),o.outcome(),o.code(),o.requestId());
    }

    // ---- Operations console ----

    /** One page of pending work for a bank: cycle status, approvals, delivery queues and exceptions. Counts only, no customer data. */
    public Map<String,Object> console(Actor actor,UUID bankId){
        AccessService.admin(actor);var bank=tenants.get(bankId,false);var settings=reference.read(bank);UUID id=bank.id();LocalDate date=settings.businessDate();
        var out=new LinkedHashMap<String,Object>();
        out.put("businessDate",date);out.put("nextBusinessDate",days.nextBusinessDate(bank));out.put("timezone",settings.businessTimezone());
        out.put("scheduler",Map.of("enabled",enabled(id),"prepareAfter",cutoff.toString(),"serviceSubject",enabled(id)?subject:""));
        var cycle=new ArrayList<Map<String,Object>>();
        boolean servicing=exists("SELECT 1 FROM platform.servicing_policy WHERE tenant_id=?",id);
        cycle.add(step("SERVICING",!servicing?"NOT_REQUIRED":exists("SELECT 1 FROM platform.servicing_run WHERE tenant_id=? AND business_date=? AND completed",id,date)?"DONE":stateOf(id,"SERVICING_RUN","SELECT 1 FROM platform.servicing_run WHERE tenant_id=? AND business_date=? AND NOT completed",date)));
        boolean periodDue=days.periodEnd(bank)&&exists("SELECT 1 FROM platform.period_close_policy WHERE tenant_id=?",id);
        cycle.add(step("PERIOD_CLOSE",!periodDue?"NOT_REQUIRED":exists("SELECT 1 FROM platform.period_close WHERE tenant_id=? AND period_end=? AND completed",id,date)?"DONE":stateOf(id,"PERIOD_CLOSE","SELECT 1 FROM platform.period_close WHERE tenant_id=? AND period_end=? AND NOT completed",date)));
        cycle.add(step("DAY_CLOSE",exists("SELECT 1 FROM platform.business_day_close WHERE tenant_id=? AND NOT completed",id)?"WAITING_DELIVERY":pending(id,"DAY_CLOSE")!=null?"WAITING_APPROVAL":"OPEN"));
        out.put("cycle",cycle);
        out.put("dayCloseBlockers",days.readiness(bank));
        out.put("approvals",jdbc.queryForList("SELECT action,count(*) AS count,min(created_at) AS oldest FROM platform.approval_request WHERE bank_id=? AND status='PENDING' GROUP BY action ORDER BY min(created_at)",id));
        out.put("queues",queues(id));
        var exceptions=new LinkedHashMap<String,Object>();
        exceptions.put("complianceCasesOverdue",jdbc.queryForObject("SELECT count(*) FROM platform.compliance_case WHERE tenant_id=? AND status='OPEN' AND due_date<?",Long.class,id,date));
        exceptions.put("paymentCasesOpen",jdbc.queryForObject("SELECT count(*) FROM platform.payment_case WHERE tenant_id=? AND state NOT IN ('RESOLVED','CANCELLED')",Long.class,id));
        exceptions.put("statementImportsOpen",jdbc.queryForObject("SELECT count(*) FROM platform.statement_import WHERE tenant_id=? AND status='OPEN'",Long.class,id));
        exceptions.put("notificationsFailed",jdbc.queryForObject("SELECT count(*) FROM platform.notification WHERE tenant_id=? AND status='FAILED'",Long.class,id));
        out.put("exceptions",exceptions);
        out.put("events",jdbc.queryForList("SELECT business_date AS \"businessDate\",step,outcome,code,request_id AS \"requestId\",recorded_at AS \"recordedAt\" FROM platform.batch_event WHERE tenant_id=? ORDER BY recorded_at DESC LIMIT 20",id));
        return out;
    }
    private Map<String,Object> step(String step,String status){return Map.of("step",step,"status",status);}
    private String stateOf(UUID bank,String action,String deliverySql,LocalDate date){
        if(exists(deliverySql,bank,date))return "WAITING_DELIVERY";
        return pending(bank,action)!=null?"WAITING_APPROVAL":"OPEN";
    }
    /** Undelivered and failing work in every delivery queue of the control database (tables with tenant_id and completed). */
    private List<Map<String,Object>> queues(UUID bank){
        var tables=jdbc.queryForList("SELECT c.table_name FROM information_schema.columns c WHERE c.table_schema='platform' AND c.column_name='completed' AND EXISTS(SELECT 1 FROM information_schema.columns t WHERE t.table_schema='platform' AND t.table_name=c.table_name AND t.column_name='tenant_id') AND EXISTS(SELECT 1 FROM information_schema.columns f WHERE f.table_schema='platform' AND f.table_name=c.table_name AND f.column_name='failure_code') ORDER BY 1",String.class);
        var out=new ArrayList<Map<String,Object>>();
        for(String table:tables){
            if(!table.matches("[a-z_]+"))continue;
            var row=jdbc.queryForMap("SELECT count(*) AS pending,count(*) FILTER (WHERE failure_code IS NOT NULL) AS failing FROM platform."+table+" WHERE tenant_id=? AND NOT completed",bank);
            if(((Number)row.get("pending")).longValue()>0)out.add(Map.of("queue",table,"pending",row.get("pending"),"failing",row.get("failing")));
        }
        return out;
    }
}
