package com.cbs.accounts;
import static com.cbs.accounts.FundingModels.*;
import com.cbs.access.AccessService;
import com.cbs.accounting.*;
import com.cbs.customers.CustomerService;
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
public class FundingService {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final com.cbs.notifications.NotificationService notifications;private final JdbcTemplate jdbc;private final TenantRepository tenants;private final AccountService accounts;private final CustomerService customers;private final BankFundingStore store;private final JournalService journals;private final TransactionTemplate tx;
 public FundingService(JdbcTemplate jdbc,TenantRepository tenants,AccountService accounts,CustomerService customers,BankFundingStore store,JournalService journals,PlatformTransactionManager manager,com.cbs.notifications.NotificationService notifications){this.notifications=notifications;this.jdbc=jdbc;this.tenants=tenants;this.accounts=accounts;this.customers=customers;this.store=store;this.journals=journals;this.tx=new TransactionTemplate(manager);}
 public Dashboard dashboard(Actor actor,UUID bankId,UUID accountId,int limit,int offset){
  AccessService.admin(actor);AccessService.page(limit,offset);return tx.execute(t->{
   jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);var bank=tenants.get(bankId,false);var a=accounts.detail(actor,bankId,accountId);
   boolean ready=store.ready(bank);var items=jdbc.query("SELECT payload,completed,failure_code FROM platform.funding_delivery WHERE tenant_id=? AND account_id=? ORDER BY created_at DESC,request_id LIMIT ? OFFSET ?",(r,n)->new Item(JSON.readValue(r.getString(1),Entry.class),r.getBoolean(2)?"POSTED":"AWAITING_POSTING",r.getString(3)),bankId,accountId,limit,offset);
   return new Dashboard(bank.version(),ready,a,ready?store.balance(bank,a.definition()):null,items,jdbc.queryForObject("SELECT count(*) FROM platform.funding_delivery WHERE tenant_id=? AND account_id=?",Long.class,bankId,accountId),limit,offset);
  });
 }
 private JournalModels.Change journal(Change input,AccountModels.Definition a){return new JournalModels.Change(input.expectedVersion(),input.reference(),input.reason(),input.businessDate(),a.legalEntityId(),a.terms().currency(),List.of(new JournalModels.Line(input.sourceLedgerId(),JournalModels.Side.DEBIT,input.amount()),new JournalModels.Line(input.liabilityLedgerId(),JournalModels.Side.CREDIT,input.amount())),null,input.reason());}
 public AccountModels.Definition validate(Tenant bank,Change input){return validate(bank,input,false);}
 public AccountModels.Definition validateCash(Tenant bank,Change input){return validate(bank,input,true);}
 private AccountModels.Definition validate(Tenant bank,Change input,boolean cash){
  DayCloseService.requireOpen(jdbc,bank.id());
  if(bank.version()!=input.expectedVersion())throw error(409,"STALE_VERSION");if(bank.status()!=Status.ACTIVE)throw error(409,"ACCOUNT_BANK_NOT_ACTIVE");
  if(!store.ready(bank))throw error(409,"FUNDING_STORAGE_NOT_READY");
  OperationService.requireIdle(jdbc,input.accountId());
  AccountClosureService.requireNotSettling(jdbc,input.accountId());
  var row=accounts.current(bank.id(),input.accountId());var a=row.definition();
  if(!row.delivery().equals("APPLIED")||!List.of(AccountModels.State.AWAITING_FUNDING,AccountModels.State.ACTIVE).contains(a.state()))throw error(409,"FUNDING_ACCOUNT_NOT_READY");
  accounts.requireMatching(bank,a);customers.requireEligible(bank,a.customerId());
  var balance=store.balance(bank,a);if(!balance.reconciled())throw error(409,"FUNDING_UNRECONCILED");
  if(cash)journals.validateCashFunding(bank,journal(input,a));else journals.validateFunding(bank,journal(input,a)); // Decimal precision, date, scope, active delivered ledgers and reference uniqueness.
  if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.ledger_registry l JOIN platform.chart_account c ON c.id=l.chart_account_id WHERE l.id=? AND l.tenant_id=? AND c.category='ASSET' AND c.normal_balance='DEBIT')",Boolean.class,input.sourceLedgerId(),bank.id()))throw error(400,"FUNDING_SOURCE_INVALID");
  if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.ledger_registry WHERE id=? AND tenant_id=? AND chart_account_id=?)",Boolean.class,input.liabilityLedgerId(),bank.id(),a.terms().liabilityAccountId()))throw error(400,"FUNDING_LIABILITY_INVALID");
  com.cbs.access.TransactionLimits.check(jdbc,bank.id(),a.terms().currency(),"FUNDING",input.businessDate(),new BigDecimal(input.amount()));
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_policy WHERE tenant_id=? AND (payload->>'interestExpenseLedger'=? OR payload->>'interestPayableLedger'=? OR payload->>'feeIncomeLedger'=? OR payload->>'taxPayableLedger'=?))",Boolean.class,bank.id(),input.liabilityLedgerId().toString(),input.liabilityLedgerId().toString(),input.liabilityLedgerId().toString(),input.liabilityLedgerId().toString()))throw error(409,"FUNDING_LEDGER_USED");
  var bindings=jdbc.queryForList("SELECT ledger_id FROM platform.account_ledger WHERE account_id=?",UUID.class,a.id());
  if(bindings.isEmpty()){
   if(new BigDecimal(input.amount()).compareTo(new BigDecimal(a.terms().minimumOpening()))<0)throw error(400,"FUNDING_OPENING_MINIMUM");
   if(!store.unused(bank,input.liabilityLedgerId())||jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_ledger WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.journal_ledger l JOIN platform.journal_delivery d ON d.request_id=l.request_id WHERE l.ledger_id=? AND NOT d.completed)",Boolean.class,input.liabilityLedgerId(),input.liabilityLedgerId()))throw error(409,"FUNDING_LEDGER_USED");
  }else if(!bindings.getFirst().equals(input.liabilityLedgerId())||!bindings.getFirst().equals(balance.liabilityLedgerId()))throw error(409,"FUNDING_LIABILITY_IMMUTABLE");
  return a;
 }
 public Map<String,Object> values(UUID bankId,Change input){var a=accounts.current(bankId,input.accountId()).definition();var m=new LinkedHashMap<>(journals.values(bankId,journal(input,a)));m.put("accountNumber",a.accountNumber());m.put("customerNumber",a.customerNumber());m.put("amount",input.amount());m.put("minimumOpening",a.terms().minimumOpening());m.put("operation","Record externally received deposit");return m;}
 public void enqueue(Actor reviewer,UUID bankId,Change input,UUID id,String maker){AccessService.admin(reviewer);enqueue(reviewer,bankId,input,id,maker,false);}
 public Entry enqueueCash(Actor reviewer,UUID bankId,Change input,UUID id,String maker){return enqueue(reviewer,bankId,input,id,maker,true);}
 private Entry enqueue(Actor reviewer,UUID bankId,Change input,UUID id,String maker,boolean cash){
  var bank=tenants.get(bankId,true);var old=validate(bank,input,cash);
  var f=entry(bank,input,id,old);var next=f.account();var j=f.journal();
  jdbc.update("INSERT INTO platform.account_ledger(account_id,ledger_id,tenant_id) VALUES (?,?,?) ON CONFLICT(account_id) DO NOTHING",old.id(),input.liabilityLedgerId(),bankId);
  jdbc.update("INSERT INTO platform.journal_delivery(request_id,tenant_id,reference,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?::jsonb,?,?,?,?)",id,bankId,j.reference(),JSON.writeValueAsString(j),maker,reviewer.issuer(),reviewer.subject(),reviewer.correlationId());
  for(var line:j.lines())jdbc.update("INSERT INTO platform.journal_ledger(request_id,ledger_id) VALUES (?,?)",id,line.ledgerId());
  jdbc.update("INSERT INTO platform.funding_delivery(request_id,tenant_id,account_id,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?::jsonb,?,?,?,?)",id,bankId,old.id(),JSON.writeValueAsString(f),maker,reviewer.issuer(),reviewer.subject(),reviewer.correlationId());
  jdbc.update("INSERT INTO platform.account_delivery(request_id,tenant_id,account_id,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?::jsonb,?,?,?,?)",id,bankId,old.id(),JSON.writeValueAsString(next),maker,reviewer.issuer(),reviewer.subject(),reviewer.correlationId());
  jdbc.update("UPDATE platform.account_registry SET state='ACTIVE',version=?,request_id=?,delivery='PENDING' WHERE tenant_id=? AND id=?",next.version(),id,bankId,old.id());
  tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(reviewer,bankId,"FUNDING_APPROVED",bank.version()+1,bank.status(),bank.status(),"Approved request "+id,null,null,Map.of("accountId",old.id(),"requestId",id,"amount",f.amount(),"currency",j.currency()));return f;
 }
 public Entry cashEntry(Tenant bank,Change input,UUID id){return entry(bank,input,id,validateCash(bank,input));}
 private Entry entry(Tenant bank,Change input,UUID id,AccountModels.Definition old){var bankId=bank.id();
  var next=new AccountModels.Definition(old.id(),old.bankId(),old.accountNumber(),old.version()+1,old.customerId(),old.customerNumber(),old.legalEntityId(),old.legalEntityName(),old.name(),AccountModels.State.ACTIVE,old.terms(),old.openedAt());
  var change=journal(input,old);var j=new JournalModels.Entry(id,bankId,change.reference().trim(),change.narration().trim(),change.businessDate(),change.legalEntityId(),change.currency(),change.lines(),null);
  var f=new Entry(id,bankId,old.id(),input.liabilityLedgerId(),new BigDecimal(input.amount()).stripTrailingZeros().toPlainString(),j,next);
  return f;
 }
 public List<UUID> pending(){return jdbc.query("SELECT request_id FROM platform.funding_delivery WHERE NOT completed AND request_id NOT IN (SELECT request_id FROM platform.cash_delivery) ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 20",(r,n)->r.getObject(1,UUID.class));}
 public boolean handles(UUID bankId,UUID id){return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.funding_delivery WHERE tenant_id=? AND request_id=?)",Boolean.class,bankId,id);}
 public void retry(Actor actor,UUID bankId,UUID id){AccessService.admin(actor);if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.funding_delivery WHERE request_id=? AND tenant_id=? AND reviewer_issuer=?)",Boolean.class,id,bankId,actor.issuer()))throw error(404,"APPROVAL_NOT_FOUND");deliver(id);}
 public void deliver(UUID id){if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_delivery WHERE request_id=?)",Boolean.class,id))throw error(409,"CASH_MANAGED");tx.executeWithoutResult(t->{
  var jobs=jdbc.query("SELECT * FROM platform.funding_delivery WHERE request_id=? AND NOT completed FOR UPDATE",(r,n)->new Dispatch(JSON.readValue(r.getString("payload"),Entry.class),r.getString("maker_subject"),r.getString("reviewer_issuer"),r.getString("reviewer_subject"),r.getObject("correlation_id",UUID.class)),id);
  if(jobs.isEmpty())return;var job=jobs.getFirst();var bank=tenants.get(job.entry().bankId(),true);String failure=null;
  if(bank.status()!=Status.ACTIVE)failure="ACCOUNT_BANK_NOT_ACTIVE";else try{
   // A committed receipt must be acknowledged even if customer eligibility changed later.
   if(!store.posted(bank,id))customers.requireEligible(bank,job.entry().account().customerId());
   store.post(bank,job);
  }catch(TenantException e){failure=e.code();}
  jdbc.update("UPDATE platform.funding_delivery SET attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=? WHERE request_id=?",failure,id);
  jdbc.update("UPDATE platform.account_delivery SET failure_code=? WHERE request_id=?",failure,id);jdbc.update("UPDATE platform.journal_delivery SET failure_code=? WHERE request_id=?",failure,id);
  if(failure!=null)return;
  for(String table:List.of("funding_delivery","account_delivery","journal_delivery"))jdbc.update("UPDATE platform."+table+" SET completed=true,completed_at=clock_timestamp(),failure_code=NULL WHERE request_id=?",id);
  jdbc.update("UPDATE platform.account_registry SET delivery='APPLIED' WHERE id=? AND request_id=?",job.entry().accountId(),id);
  var posted=job.entry();notifications.record(new com.cbs.notifications.NotificationModels.Movement(bank.id(),posted.accountId(),com.cbs.notifications.NotificationModels.Event.CREDIT,id,posted.amount(),posted.journal().currency(),posted.journal().businessDate(),posted.journal().reference()));
  tenants.audit(new Actor(job.issuer(),job.checker(),true,job.correlationId()),bank.id(),"FUNDING_POSTED",bank.version(),bank.status(),bank.status(),"Posted approved funding "+id,null,null,Map.of("accountId",job.entry().accountId(),"requestId",id));
 });}
 private static TenantException error(int status,String code){return new TenantException(status,code,"Account funding could not be completed");}
}
