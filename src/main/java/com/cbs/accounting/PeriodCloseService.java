package com.cbs.accounting;

import static com.cbs.accounting.PeriodCloseModels.*;
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

/**
 * Month-end and year-end close on the last business day of a month. Every close re-checks that
 * the books balance and records an approved trial balance; at fiscal year end it also posts
 * closing journals that move each entity's income and expense balances into retained earnings.
 * Afterwards the day accepts no other postings, and Business Day Close may proceed.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class PeriodCloseService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final TenantRepository tenants;private final ReferenceDataService reference;private final DayCloseService days;private final BankPeriodCloseStore store;private final TransactionTemplate tx;
    public PeriodCloseService(JdbcTemplate jdbc,TenantRepository tenants,ReferenceDataService reference,DayCloseService days,BankPeriodCloseStore store,PlatformTransactionManager manager){this.jdbc=jdbc;this.tenants=tenants;this.reference=reference;this.days=days;this.store=store;this.tx=new TransactionTemplate(manager);}

    // ---- Policy ----
    public void validate(Tenant bank,Policy input){
        if(bank.version()!=input.expectedVersion())throw error("STALE_VERSION");if(bank.status()!=Status.ACTIVE)throw error("LEDGER_BANK_NOT_ACTIVE");
        var keys=new HashSet<String>();
        for(var m:input.retainedEarnings()){
            if(!keys.add(m.legalEntityId()+":"+m.currency()))throw error("PERIOD_CLOSE_POLICY_INVALID");
            if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.ledger_registry l JOIN platform.chart_account c ON c.id=l.chart_account_id WHERE l.id=? AND l.tenant_id=? AND l.legal_entity_id=? AND l.currency=? AND l.active AND l.delivery='APPLIED' AND c.active AND c.kind='POSTING' AND c.category='EQUITY' AND c.normal_balance='CREDIT') AND NOT EXISTS(SELECT 1 FROM platform.account_ledger WHERE ledger_id=?)",Boolean.class,m.ledgerId(),bank.id(),m.legalEntityId(),m.currency(),m.ledgerId()))throw error("PERIOD_CLOSE_LEDGER_INVALID");
        }
    }
    public Map<String,Object> values(Policy input){return Map.of("fiscalYearEndMonth",input.fiscalYearEndMonth(),"retainedEarnings",input.retainedEarnings().stream().map(m->m.legalEntityId()+" · "+m.currency()+" → "+m.ledgerId()).sorted().toList());}
    public Map<String,Object> policyBefore(UUID bankId){
        var rows=jdbc.query("SELECT payload::text FROM platform.period_close_policy WHERE tenant_id=?",(r,n)->JSON.readValue(r.getString(1),Policy.class),bankId);return rows.isEmpty()?Map.of():values(rows.getFirst());
    }
    public void apply(Actor actor,UUID bankId,Policy input,UUID approvalId){
        AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,input);
        jdbc.update("INSERT INTO platform.period_close_policy(tenant_id,fiscal_year_end_month,payload,approval_id) VALUES (?,?,?::jsonb,?) ON CONFLICT(tenant_id) DO UPDATE SET fiscal_year_end_month=excluded.fiscal_year_end_month,payload=excluded.payload,approval_id=excluded.approval_id,updated_at=clock_timestamp()",bankId,input.fiscalYearEndMonth(),JSON.writeValueAsString(input),approvalId);
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(actor,bankId,"PERIOD_CLOSE_POLICY_CHANGED",bank.version()+1,bank.status(),bank.status(),input.reason(),null,null,values(input));
    }
    private Policy policy(UUID bankId){var rows=jdbc.query("SELECT payload::text FROM platform.period_close_policy WHERE tenant_id=?",(r,n)->JSON.readValue(r.getString(1),Policy.class),bankId);return rows.isEmpty()?null:rows.getFirst();}

    // ---- Close ----
    public Plan prepare(Tenant bank,Run input,UUID id){
        DayCloseService.requireOpen(jdbc,bank.id());
        if(bank.version()!=input.expectedVersion())throw error("STALE_VERSION");if(bank.status()!=Status.ACTIVE)throw error("LEDGER_BANK_NOT_ACTIVE");
        var settings=reference.read(bank);if(!settings.businessDate().equals(input.businessDate()))throw error("JOURNAL_BUSINESS_DATE_INVALID");
        if(!store.ready(bank))throw error("PERIOD_CLOSE_STORAGE_NOT_READY");
        var policy=policy(bank.id());if(policy==null)throw error("PERIOD_CLOSE_POLICY_REQUIRED");
        if(!days.periodEnd(bank))throw error("PERIOD_CLOSE_NOT_PERIOD_END");
        // Servicing, valuations, cash counts and deliveries must all be finished first.
        days.requireReadyForPeriodClose(bank,id);
        var type=input.businessDate().getMonthValue()==policy.fiscalYearEndMonth()?Type.YEAR:Type.MONTH;
        record Info(String category,boolean ledgerActive){}
        var info=new HashMap<UUID,Info>();
        jdbc.query("SELECT l.id,c.category,l.active FROM platform.ledger_registry l JOIN platform.chart_account c ON c.id=l.chart_account_id WHERE l.tenant_id=?",r->{info.put(r.getObject(1,UUID.class),new Info(r.getString(2),r.getBoolean(3)));},bank.id());
        record Key(UUID entity,String currency){}
        var groups=new TreeMap<String,List<BankPeriodCloseStore.LedgerBalance>>();var keys=new HashMap<String,Key>();
        for(var b:store.balances(bank)){String k=b.legalEntityId()+":"+b.currency();groups.computeIfAbsent(k,x->new ArrayList<>()).add(b);keys.put(k,new Key(b.legalEntityId(),b.currency()));}
        var totals=new ArrayList<Total>();var journals=new ArrayList<JournalModels.Entry>();
        for(var e:groups.entrySet()){
            var key=keys.get(e.getKey());BigDecimal debits=BigDecimal.ZERO,credits=BigDecimal.ZERO,income=BigDecimal.ZERO,expense=BigDecimal.ZERO;var lines=new ArrayList<JournalModels.Line>();
            for(var b:e.getValue()){
                var net=b.debit().subtract(b.credit());if(net.signum()>0)debits=debits.add(net);else credits=credits.add(net.negate());
                var category=info.containsKey(b.ledgerId())?info.get(b.ledgerId()).category():null;
                if(net.signum()!=0&&("INCOME".equals(category)||"EXPENSE".equals(category))){
                    if("INCOME".equals(category))income=income.add(net.negate());else expense=expense.add(net);
                    if(type==Type.YEAR){
                        if(!info.get(b.ledgerId()).ledgerActive())throw error("PERIOD_CLOSE_LEDGER_INACTIVE");
                        lines.add(new JournalModels.Line(b.ledgerId(),net.signum()>0?JournalModels.Side.CREDIT:JournalModels.Side.DEBIT,text(net.abs())));
                    }
                }
            }
            if(debits.compareTo(credits)!=0)throw error("PERIOD_CLOSE_UNBALANCED");
            var result=income.subtract(expense);
            totals.add(new Total(key.entity(),key.currency(),text(debits),text(credits),text(income),text(expense),text(result)));
            if(type==Type.YEAR&&!lines.isEmpty()){
                var target=policy.retainedEarnings().stream().filter(m->m.legalEntityId().equals(key.entity())&&m.currency().equals(key.currency())).findFirst().orElseThrow(()->error("PERIOD_CLOSE_LEDGER_REQUIRED"));
                // The swept lines net to the year's result; retained earnings takes the other side.
                BigDecimal swept=lines.stream().map(l->l.side()==JournalModels.Side.DEBIT?new BigDecimal(l.amount()):new BigDecimal(l.amount()).negate()).reduce(BigDecimal.ZERO,BigDecimal::add);
                if(swept.signum()==0)continue;
                lines.add(new JournalModels.Line(target.ledgerId(),swept.signum()>0?JournalModels.Side.CREDIT:JournalModels.Side.DEBIT,text(swept.abs())));
                UUID journalId=UUID.nameUUIDFromBytes((id+":"+key.entity()+":"+key.currency()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                journals.add(new JournalModels.Entry(journalId,bank.id(),"YEC-"+input.businessDate()+"-"+journalId.toString().substring(0,8).toUpperCase(Locale.ROOT),"Year-end closing of income and expense to retained earnings",input.businessDate(),key.entity(),key.currency(),List.copyOf(lines),null));
            }
        }
        return new Plan(id,bank.id(),type,input.businessDate(),totals,journals);
    }
    public Map<String,Object> values(Plan plan){
        var m=new LinkedHashMap<String,Object>();m.put("periodType",plan.type());m.put("periodEnd",plan.periodEnd());m.put("totals",plan.totals());
        m.put("closingJournals",plan.journals().stream().map(j->Map.of("reference",j.reference(),"legalEntityId",j.legalEntityId(),"currency",j.currency(),"lines",j.lines().size())).toList());return m;
    }
    public Plan preview(Actor actor,UUID bankId,Run input){AccessService.admin(actor);return tx.execute(t->prepare(tenants.get(bankId,true),input,UUID.randomUUID()));}
    public void enqueue(Actor actor,UUID bankId,Run input,UUID id,String maker){
        AccessService.admin(actor);var bank=tenants.get(bankId,true);var plan=prepare(bank,input,id);
        jdbc.update("INSERT INTO platform.period_close(request_id,tenant_id,period_type,period_end,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",id,bankId,plan.type().name(),plan.periodEnd(),JSON.writeValueAsString(plan),maker,actor.issuer(),actor.subject(),actor.correlationId());
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(actor,bankId,"PERIOD_CLOSE_APPROVED",bank.version()+1,bank.status(),bank.status(),input.reason(),null,null,values(plan));
    }
    public List<UUID> pending(){return jdbc.queryForList("SELECT request_id FROM platform.period_close WHERE NOT completed ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 10",UUID.class);}
    public void retry(Actor actor,UUID bankId,UUID id){AccessService.admin(actor);if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.period_close WHERE tenant_id=? AND request_id=? AND reviewer_issuer=?)",Boolean.class,bankId,id,actor.issuer()))throw error("APPROVAL_NOT_FOUND");deliver(id);}
    private com.cbs.access.BankDuties duties;
    @org.springframework.beans.factory.annotation.Autowired void duties(com.cbs.access.BankDuties duties){this.duties=duties;}
    public void deliver(UUID id){tx.executeWithoutResult(t->{
        var rows=jdbc.query("SELECT * FROM platform.period_close WHERE request_id=? AND NOT completed FOR UPDATE",(r,n)->new Dispatch(JSON.readValue(r.getString("payload"),Plan.class),r.getString("maker_subject"),r.getString("reviewer_issuer"),r.getString("reviewer_subject"),r.getObject("correlation_id",UUID.class)),id);
        if(rows.isEmpty())return;var job=rows.getFirst();var bank=tenants.get(job.plan().bankId(),true);String failure=null;
        try{if(!store.posted(bank,id)){if(bank.status()!=Status.ACTIVE)throw error("LEDGER_BANK_NOT_ACTIVE");duties.requireAtDelivery(id);}store.post(bank,job);}catch(TenantException e){failure=e.code();}
        jdbc.update("UPDATE platform.period_close SET attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=?,completed=?,completed_at=CASE WHEN ? THEN clock_timestamp() END WHERE request_id=?",failure,failure==null,failure==null,id);
        if(failure==null)tenants.audit(new Actor(job.issuer(),job.checker(),true,job.correlationId()),bank.id(),"PERIOD_CLOSE_POSTED",bank.version(),bank.status(),bank.status(),"Posted "+job.plan().type()+" close",null,null,Map.of("requestId",id,"journals",job.plan().journals().size()));
    });}
    public Map<String,Object> dashboard(Actor actor,UUID bankId){
        AccessService.admin(actor);var bank=tenants.get(bankId,false);var p=policy(bankId);var settings=reference.read(bank);
        var m=new LinkedHashMap<String,Object>();m.put("version",bank.version());m.put("businessDate",settings.businessDate());m.put("periodEnd",days.periodEnd(bank));m.put("storageReady",store.ready(bank));
        m.put("policy",p==null?Map.of():Map.of("fiscalYearEndMonth",p.fiscalYearEndMonth(),"retainedEarnings",p.retainedEarnings()));
        m.put("closes",jdbc.queryForList("SELECT request_id,period_type,period_end,completed,failure_code,attempts FROM platform.period_close WHERE tenant_id=? ORDER BY period_end DESC LIMIT 24",bankId));return m;
    }
    private static String text(BigDecimal v){return v.stripTrailingZeros().toPlainString();}
    private static TenantException error(String code){return new TenantException(409,code,"Period close requires attention");}
}
