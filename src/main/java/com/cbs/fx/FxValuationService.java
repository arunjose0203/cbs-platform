package com.cbs.fx;

import static com.cbs.fx.FxValuationModels.*;
import com.cbs.access.AccessService;
import com.cbs.accounting.*;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
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
public class FxValuationService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final ReferenceDataService reference;
    private final BankFxValuationStore store;
    private final JournalService journals;
    private final TransactionTemplate tx;
    public FxValuationService(JdbcTemplate jdbc,TenantRepository tenants,ReferenceDataService reference,
            BankFxValuationStore store,JournalService journals,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.tenants=tenants;this.reference=reference;this.store=store;this.journals=journals;this.tx=new TransactionTemplate(manager);
    }
    public Map<String,Object> dashboard(Actor actor,UUID bankId) {
        AccessService.admin(actor);
        return tx.execute(t->{var bank=tenants.get(bankId,true);return Map.of("version",bank.version(),
            "businessDate",reference.read(bank).businessDate(),"storageReady",List.of(Status.ACTIVE,Status.SUSPENDED).contains(bank.status())&&store.ready(bank),
            "books",jdbc.queryForList("SELECT payload::text FROM platform.fx_book WHERE tenant_id=? ORDER BY id",String.class,bankId).stream().map(s->JSON.readValue(s,Book.class)).toList(),
            "closingRates",jdbc.query("SELECT id,payload FROM platform.fx_closing_rate WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT 100",(r,n)->Map.of("id",r.getObject(1,UUID.class),"rate",JSON.readTree(r.getString(2))),bankId),
            "valuations",jdbc.query("SELECT id,payload,completed,failure_code FROM platform.fx_valuation WHERE tenant_id=? ORDER BY created_at DESC,id LIMIT 100",(r,n)->Map.of("id",r.getObject(1,UUID.class),"posting",JSON.readTree(r.getString(2)),"completed",r.getBoolean(3),"failureCode",Objects.toString(r.getString(4),"")),bankId));});
    }
    private void open(Tenant bank,long version) {
        DayCloseService.requireOpen(jdbc,bank.id());
        if(bank.version()!=version)throw error("STALE_VERSION");
        if(bank.status()!=Status.ACTIVE)throw error("LEDGER_BANK_NOT_ACTIVE");
        if(!store.ready(bank))throw error("FX_VALUATION_STORAGE_REQUIRED");
    }
    public Book validatePolicy(Tenant bank,Policy p,UUID id) {
        open(bank,p.expectedVersion());
        var entity=bank.legalEntities().stream().filter(e->e.id().equals(p.legalEntityId())).findFirst().orElseThrow(()->error("LEGAL_ENTITY_NOT_IN_BANK"));
        if(entity.functionalCurrency().equals(p.foreignCurrency()) || p.costBasis()!=CostBasis.WEIGHTED_AVERAGE || new HashSet<>(p.ledgers()).size()!=6)throw error("FX_BOOK_POLICY_INVALID");
        for(String currency:List.of(entity.functionalCurrency(),p.foreignCurrency()))if(reference.read(bank).currencies().stream().noneMatch(c->c.code().equals(currency)&&c.enabled()))throw error("CURRENCY_NOT_ENABLED");
        int scale=Currency.getInstance(p.foreignCurrency()).getDefaultFractionDigits();
        for(String amount:List.of(p.longLimit(),p.shortLimit())) {var n=new BigDecimal(amount);if(n.signum()<0||n.stripTrailingZeros().scale()>scale)throw error("FX_BOOK_POLICY_INVALID");}
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_book WHERE tenant_id=? AND legal_entity_id=? AND foreign_currency=?)",Boolean.class,bank.id(),p.legalEntityId(),p.foreignCurrency()))throw error("FX_BOOK_EXISTS");
        for(int i=0;i<p.ledgers().size();i++) {
            UUID ledger=p.ledgers().get(i);String currency=i==0?p.foreignCurrency():entity.functionalCurrency();String category=i<2?"EQUITY":i%2==0?"INCOME":"EXPENSE";
            if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.ledger_registry l JOIN platform.chart_account c ON c.id=l.chart_account_id WHERE l.id=? AND l.tenant_id=? AND l.legal_entity_id=? AND l.currency=? AND l.active AND l.delivery='APPLIED' AND c.active AND c.kind='POSTING' AND c.category=?)",Boolean.class,ledger,bank.id(),p.legalEntityId(),currency,category))throw error("FX_BOOK_LEDGER_INVALID");
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_book_ledger WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.account_ledger WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.contract_ledger WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.cash_location WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.cash_transit WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.journal_ledger l JOIN platform.journal_delivery j ON j.request_id=l.request_id WHERE l.ledger_id=? AND NOT j.completed)",Boolean.class,ledger,ledger,ledger,ledger,ledger,ledger))throw error("FX_BOOK_LEDGER_INVALID");
        }
        var book=new Book(id,bank.id(),entity.functionalCurrency(),p);
        // Legacy conversion books can be adopted only when their entire history reconciles to this exact pair.
        var snapshot=store.snapshot(bank,book,new ClosingRate(bank.version(),id,reference.read(bank).businessDate(),"1","POLICY-VALIDATION",p.reason()));
        limits(p,new BigDecimal(snapshot.totals().quantity()));
        return book;
    }
    public void policy(Actor actor,UUID bankId,Policy p,UUID id) {
        AccessService.admin(actor);var bank=tenants.get(bankId,true);var book=validatePolicy(bank,p,id);
        jdbc.update("INSERT INTO platform.fx_book(id,tenant_id,legal_entity_id,foreign_currency,payload) VALUES (?,?,?,?,?::jsonb)",id,bankId,p.legalEntityId(),p.foreignCurrency(),JSON.writeValueAsString(book));
        for(UUID ledger:p.ledgers())jdbc.update("INSERT INTO platform.fx_book_ledger(ledger_id,book_id) VALUES (?,?)",ledger,id);
        for(UUID ledger:List.of(p.foreignPositionLedger(),p.functionalPositionLedger()))jdbc.update("INSERT INTO platform.fx_position(ledger_id,tenant_id) VALUES (?,?) ON CONFLICT DO NOTHING",ledger,bankId);
        changed(actor,bank,"FX_BOOK_APPROVED",id,p.reason());
    }
    private Book book(UUID bank,UUID id) {
        var rows=jdbc.queryForList("SELECT payload::text FROM platform.fx_book WHERE tenant_id=? AND id=?",String.class,bank,id);
        if(rows.isEmpty())throw error("FX_BOOK_NOT_FOUND");return JSON.readValue(rows.getFirst(),Book.class);
    }
    public void validateRate(Tenant bank,ClosingRate rate) {
        open(bank,rate.expectedVersion());book(bank.id(),rate.bookId());
        if(!reference.read(bank).businessDate().equals(rate.businessDate()) || new BigDecimal(rate.rate()).signum()<=0)throw error("FX_VALUATION_DATE_INVALID");
        unsealed(rate.bookId(),rate.businessDate());
    }
    public void closingRate(Actor actor,UUID bankId,ClosingRate rate,UUID id) {
        AccessService.admin(actor);var bank=tenants.get(bankId,true);validateRate(bank,rate);
        jdbc.update("INSERT INTO platform.fx_closing_rate(id,tenant_id,book_id,business_date,payload) VALUES (?,?,?,?,?::jsonb)",id,bankId,rate.bookId(),rate.businessDate(),JSON.writeValueAsString(rate));
        changed(actor,bank,"FX_CLOSING_RATE_APPROVED",id,rate.reason());
    }
    private void unsealed(UUID book,LocalDate date) {
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_valuation WHERE book_id=? AND business_date>=?)",Boolean.class,book,date))throw error("FX_BOOK_SEALED");
    }
    public Posting preview(Actor actor,UUID bank,Run run) {AccessService.admin(actor);return tx.execute(t->validateRun(tenants.get(bank,true),run,UUID.randomUUID()));}
    public Posting validateRun(Tenant bank,Run run,UUID id) {
        open(bank,run.expectedVersion());var book=book(bank.id(),run.bookId());unsealed(book.id(),run.businessDate());
        if(!reference.read(bank).businessDate().equals(run.businessDate()))throw error("FX_VALUATION_DATE_INVALID");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_trade WHERE tenant_id=? AND NOT completed)",Boolean.class,bank.id()))throw error("FX_TRADE_PENDING");
        var rows=jdbc.query("SELECT id,payload FROM platform.fx_closing_rate WHERE book_id=? AND business_date=? ORDER BY created_at DESC,id DESC LIMIT 1",(r,n)->Map.entry(r.getObject(1,UUID.class),JSON.readValue(r.getString(2),ClosingRate.class)),book.id(),run.businessDate());
        if(rows.isEmpty()||!rows.getFirst().getKey().equals(run.closingRateId()))throw error("FX_CLOSING_RATE_REQUIRED");
        var rate=rows.getFirst().getValue();var snapshot=store.snapshot(bank,book,rate);
        var lines=lines(book.policy(),snapshot.totals(),snapshot.previousRealized(),snapshot.previousUnrealized());
        JournalModels.Entry journal=null;
        if(!lines.isEmpty()) {
            var change=new JournalModels.Change(bank.version(),"FXV-"+id,run.reason(),run.businessDate(),book.policy().legalEntityId(),book.functionalCurrency(),lines,null,run.reason());
            journals.validateFxValuation(bank,change);
            journal=new JournalModels.Entry(id,bank.id(),change.reference(),run.reason(),run.businessDate(),book.policy().legalEntityId(),book.functionalCurrency(),lines,null);
        }
        return new Posting(id,book,rate,run.closingRateId(),snapshot,journal);
    }
    public void enqueue(Actor actor,UUID bankId,Run run,UUID id,String maker) {
        AccessService.admin(actor);var bank=tenants.get(bankId,true);var posting=validateRun(bank,run,id);
        jdbc.update("INSERT INTO platform.fx_valuation(id,tenant_id,book_id,business_date,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",id,bankId,run.bookId(),run.businessDate(),JSON.writeValueAsString(posting),maker,actor.issuer(),actor.subject(),actor.correlationId());
        var j=posting.journal();if(j!=null){
            jdbc.update("INSERT INTO platform.journal_delivery(request_id,tenant_id,reference,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?::jsonb,?,?,?,?)",id,bankId,j.reference(),JSON.writeValueAsString(j),maker,actor.issuer(),actor.subject(),actor.correlationId());
            for(var line:j.lines())jdbc.update("INSERT INTO platform.journal_ledger VALUES (?,?)",id,line.ledgerId());
        }
        changed(actor,bank,"FX_VALUATION_APPROVED",id,run.reason());
    }
    public List<UUID> pending(){return jdbc.queryForList("SELECT id FROM platform.fx_valuation WHERE NOT completed ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 20",UUID.class);}
    public void retry(Actor actor,UUID bank,UUID id){AccessService.admin(actor);if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_valuation WHERE id=? AND tenant_id=? AND reviewer_issuer=?)",Boolean.class,id,bank,actor.issuer()))throw error("APPROVAL_NOT_FOUND");deliver(id);}
    public void deliver(UUID id) {
        tx.executeWithoutResult(t->{var ids=jdbc.queryForList("SELECT tenant_id FROM platform.fx_valuation WHERE id=?",UUID.class,id);if(ids.isEmpty())return;
            var bank=tenants.get(ids.getFirst(),true);
            var jobs=jdbc.query("SELECT * FROM platform.fx_valuation WHERE id=? AND NOT completed FOR UPDATE",(r,n)->new Dispatch(JSON.readValue(r.getString("payload"),Posting.class),r.getString("maker_subject"),r.getString("reviewer_issuer"),r.getString("reviewer_subject"),r.getObject("correlation_id",UUID.class)),id);
            if(jobs.isEmpty())return;var job=jobs.getFirst();String failure=null;
            try{store.post(bank,job);}catch(TenantException e){failure=e.code();}
            jdbc.update("UPDATE platform.fx_valuation SET completed=?,failure_code=?,attempts=attempts+1,last_attempt_at=clock_timestamp() WHERE id=?",failure==null,failure,id);
            jdbc.update("UPDATE platform.journal_delivery SET completed=?,failure_code=?,completed_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END WHERE request_id=?",failure==null,failure,failure==null,id);
            if(failure==null)tenants.audit(new Actor(job.issuer(),job.checker(),true,job.correlationId()),bank.id(),"FX_VALUATION_POSTED",bank.version(),bank.status(),bank.status(),"Posted approved closing valuation",null,null,Map.of("valuationId",id));
        });
    }
    /** Runs under the tenant lock; approved unposted trades reserve exposure too. */
    public static void requireTrade(JdbcTemplate jdbc,Tenant bank,UUID entity,String sourceCurrency,String targetCurrency,UUID sourceLedger,UUID targetLedger,String sourceAmount,String targetAmount,LocalDate date) {
        var functional=bank.legalEntities().stream().filter(e->e.id().equals(entity)).findFirst().orElseThrow().functionalCurrency();
        if(!sourceCurrency.equals(functional)&&!targetCurrency.equals(functional))throw error("FX_CROSS_CURRENCY_UNSUPPORTED");
        boolean buy=!sourceCurrency.equals(functional);String foreign=buy?sourceCurrency:targetCurrency;
        var rows=jdbc.queryForList("SELECT payload::text FROM platform.fx_book WHERE tenant_id=? AND legal_entity_id=? AND foreign_currency=?",String.class,bank.id(),entity,foreign);
        if(rows.isEmpty())throw error("FX_BOOK_POLICY_REQUIRED");var book=JSON.readValue(rows.getFirst(),Book.class);var p=book.policy();
        if(!p.foreignPositionLedger().equals(buy?sourceLedger:targetLedger)||!p.functionalPositionLedger().equals(buy?targetLedger:sourceLedger))throw error("FX_BOOK_MAPPING_CONFLICT");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_valuation WHERE book_id=? AND business_date>=?)",Boolean.class,book.id(),date))throw error("FX_BOOK_SEALED");
        BigDecimal q=BigDecimal.ZERO;
        for(String payload:jdbc.queryForList("SELECT payload::text FROM platform.fx_trade WHERE tenant_id=?",String.class,bank.id())) {
            var b=JSON.readValue(payload,FxTradeModels.Batch.class);
            if(b.sourceJournal().lines().getLast().ledgerId().equals(p.foreignPositionLedger()))q=q.add(new BigDecimal(b.quote().sourceAmount()));
            if(b.targetJournal().lines().getFirst().ledgerId().equals(p.foreignPositionLedger()))q=q.subtract(new BigDecimal(b.quote().targetAmount()));
        }
        limits(p,q.add(buy?new BigDecimal(sourceAmount):new BigDecimal(targetAmount).negate()));
    }
    private static void limits(Policy p,BigDecimal quantity){if(quantity.compareTo(new BigDecimal(p.longLimit()))>0||quantity.compareTo(new BigDecimal(p.shortLimit()).negate())<0)throw error("FX_POSITION_LIMIT_EXCEEDED");}
    private void changed(Actor actor,Tenant bank,String action,UUID id,String reason){tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(actor,bank.id(),action,bank.version()+1,bank.status(),bank.status(),reason,null,null,Map.of("requestId",id));}
    private static TenantException error(String code){return new TenantException(409,code,"FX valuation requires attention");}
}
