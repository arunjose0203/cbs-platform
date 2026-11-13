package com.cbs.accounts;

import static com.cbs.accounts.OperationModels.*;
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
public class OperationService {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final com.cbs.access.BranchOperations branches;
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final AccountService accounts;
 private final CustomerService customers;private final BankOperationStore store;private final JournalService journals;
 private final ReferenceDataService reference;private final TransactionTemplate tx;private final AccountClosureService closures;private final com.cbs.notifications.NotificationService notifications;
 private com.cbs.mandates.MandateService mandates;
 @org.springframework.beans.factory.annotation.Autowired void mandates(com.cbs.mandates.MandateService mandates){this.mandates=mandates;}
 public OperationService(com.cbs.access.BranchOperations branches,JdbcTemplate jdbc,TenantRepository tenants,AccountService accounts,CustomerService customers,BankOperationStore store,JournalService journals,ReferenceDataService reference,PlatformTransactionManager manager,AccountClosureService closures,com.cbs.notifications.NotificationService notifications){this.closures=closures;this.notifications=notifications;this.branches=branches;this.jdbc=jdbc;this.tenants=tenants;this.accounts=accounts;this.customers=customers;this.store=store;this.journals=journals;this.reference=reference;this.tx=new TransactionTemplate(manager);}
 public static void requireIdle(JdbcTemplate jdbc,UUID account){
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_delivery WHERE account_id=? AND NOT completed)",Boolean.class,account))throw error("ACCOUNT_OPERATION_PENDING");
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_trade WHERE (source_account=? OR target_account=?) AND NOT completed)",Boolean.class,account,account))throw error("ACCOUNT_OPERATION_PENDING");
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_operation WHERE (account_id=? OR destination_id=?) AND NOT completed)",Boolean.class,account,account))throw error("ACCOUNT_OPERATION_PENDING");
 }
 public Dashboard dashboard(Actor actor,UUID bankId,UUID accountId,int limit,int offset){
  AccessService.admin(actor);AccessService.page(limit,offset);return tx.execute(t->{
   jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);var bank=tenants.get(bankId,false);var account=accounts.detail(actor,bankId,accountId).definition();
   boolean ready=store.ready(bank);var items=jdbc.query("SELECT payload,completed,failure_code,EXISTS(SELECT 1 FROM platform.cash_delivery c WHERE c.request_id=platform.account_operation.request_id) AS cash_managed FROM platform.account_operation WHERE tenant_id=? AND (account_id=? OR destination_id=?) ORDER BY created_at DESC,request_id LIMIT ? OFFSET ?",(r,n)->new Item(JSON.readValue(r.getString(1),Entry.class),r.getBoolean(2)?"POSTED":"AWAITING_POSTING",r.getString(3),r.getBoolean(4)),bankId,accountId,accountId,limit,offset);
   boolean funded=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_ledger WHERE account_id=?)",Boolean.class,accountId);
   return new Dashboard(bank.version(),ready,ready&&funded?store.position(bank,account):null,items,jdbc.queryForObject("SELECT count(*) FROM platform.account_operation WHERE tenant_id=? AND (account_id=? OR destination_id=?)",Long.class,bankId,accountId,accountId),limit,offset);
  });
 }
 private AccountModels.Definition account(Tenant bank,UUID id,boolean release){
  requireIdle(jdbc,id);if(!release)AccountClosureService.requireNotSettling(jdbc,id);var row=accounts.current(bank.id(),id);var a=row.definition();
  if(a.state()==AccountModels.State.DORMANT)throw error("ACCOUNT_DORMANT");
  if(!row.delivery().equals("APPLIED")||(!release&&a.state()!=AccountModels.State.ACTIVE)||a.state()==AccountModels.State.CLOSED)throw error("FUNDING_ACCOUNT_NOT_READY");
  accounts.requireMatching(bank,a);if(!release)customers.requireEligible(bank,a.customerId());return a;
 }
 public Entry validate(Tenant bank,Change input,UUID id){return validate(bank,input,id,false);}
 public Entry validateCash(Tenant bank,Change input,UUID id){return validate(bank,input,id,true);}
 public Entry validatePayment(Tenant bank,Change input,UUID id){return validate(bank,input,id,false,true);}
 private Entry validate(Tenant bank,Change input,UUID id,boolean cash){return validate(bank,input,id,cash,false);}
 private Entry validate(Tenant bank,Change input,UUID id,boolean cash,boolean payment){
  DayCloseService.requireOpen(jdbc,bank.id());if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");
  if(bank.version()!=input.expectedVersion())throw error("STALE_VERSION");if(!store.ready(bank))throw error("OPERATION_STORAGE_NOT_READY");
  if(!reference.read(bank).businessDate().equals(input.businessDate()))throw error("JOURNAL_BUSINESS_DATE_INVALID");
  if(input.kind()==null||input.amount()==null||!input.amount().matches("[0-9]{1,18}(\\.[0-9]{1,6})?"))throw error("JOURNAL_AMOUNT_INVALID");
  boolean isSettlementReversal=input.kind()==Kind.CLOSURE_SETTLEMENT_REVERSAL;
  var a=account(bank,input.accountId(),input.kind()==Kind.RELEASE||isSettlementReversal);var amount=new BigDecimal(input.amount());
  if(amount.signum()<=0||amount.stripTrailingZeros().scale()>Currency.getInstance(a.terms().currency()).getDefaultFractionDigits())throw error("JOURNAL_AMOUNT_INVALID");
  boolean reverseOperation=input.kind()==Kind.WITHDRAWAL_REVERSAL||input.kind()==Kind.TRANSFER_REVERSAL||isSettlementReversal;
  if((input.kind()==Kind.TRANSFER||input.kind()==Kind.TRANSFER_REVERSAL)!=(input.destinationId()!=null)||(input.kind()==Kind.WITHDRAWAL||input.kind()==Kind.CLOSURE_SETTLEMENT)!=(input.assetLedgerId()!=null)||(input.kind()==Kind.RELEASE||input.kind()==Kind.FUNDING_REVERSAL||reverseOperation)!=(input.originalId()!=null))throw error("OPERATION_FIELDS_INVALID");
  if(reverseOperation)if(!payment&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.payment_order WHERE id=?)",Boolean.class,input.originalId()))throw error("PAYMENT_MANAGED");
  // Customer debits follow the account mandate: joint, organization and minors' accounts need authorised signers.
  if(input.kind()==Kind.WITHDRAWAL||input.kind()==Kind.TRANSFER||input.kind()==Kind.CLOSURE_SETTLEMENT)mandates.requireAuthorized(bank,a.id(),input.signatories());
  else if(input.signatories()!=null&&!input.signatories().isEmpty())throw error("OPERATION_FIELDS_INVALID");
  com.cbs.access.TransactionLimits.check(jdbc,bank.id(),a.terms().currency(),input.kind().name(),input.businessDate(),amount);
  AccountModels.Definition target=null;UUID credit=null,reversal=null;
  var source=store.position(bank,a);
  if(reverseOperation){

   var originals=jdbc.query("SELECT payload FROM platform.account_operation WHERE request_id=? AND tenant_id=? AND completed",(r,n)->JSON.readValue(r.getString(1),Entry.class),input.originalId(),bank.id());
   if(originals.size()!=1)throw error("OPERATION_REVERSAL_INVALID");var original=originals.getFirst();var old=original.change();
   if(!cash&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_delivery WHERE request_id=?)",Boolean.class,input.originalId()))throw error("CASH_MANAGED");
   if(amount.compareTo(new BigDecimal(old.amount()))!=0||original.journal()==null||original.journal().reversalOf()!=null)throw error("OPERATION_REVERSAL_INVALID");
   if(input.kind()==Kind.WITHDRAWAL_REVERSAL){if(old.kind()!=Kind.WITHDRAWAL||!a.id().equals(old.accountId()))throw error("OPERATION_REVERSAL_INVALID");}
   else if(isSettlementReversal){
    if(old.kind()!=Kind.CLOSURE_SETTLEMENT||!a.id().equals(old.accountId()))throw error("OPERATION_REVERSAL_INVALID");
    if(a.state()==AccountModels.State.CLOSED)throw error("ACCOUNT_ALREADY_CLOSED");
    if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_operation WHERE tenant_id=? AND payload->'change'->>'kind'='CLOSURE_SETTLEMENT_REVERSAL' AND (payload->'change'->>'originalId')::uuid=? AND completed)",Boolean.class,bank.id(),input.originalId()))throw error("OPERATION_ALREADY_REVERSED");
   }
   else{if(old.kind()!=Kind.TRANSFER||!a.id().equals(old.destinationId())||!input.destinationId().equals(old.accountId()))throw error("OPERATION_REVERSAL_INVALID");target=account(bank,input.destinationId(),false);}
   var lines=original.journal().lines().stream().map(l->new JournalModels.Line(l.ledgerId(),l.side()==JournalModels.Side.DEBIT?JournalModels.Side.CREDIT:JournalModels.Side.DEBIT,l.amount())).toList();
   var j=new JournalModels.Change(input.expectedVersion(),input.reference(),input.reason(),input.businessDate(),a.legalEntityId(),a.terms().currency(),lines,input.originalId(),input.reason());
   if(cash)journals.validateCashFunding(bank,j);else journals.validateFunding(bank,j);
   var entry=new Entry(id,bank.id(),input,a,target,new JournalModels.Entry(id,bank.id(),input.reference().trim(),input.reason().trim(),input.businessDate(),a.legalEntityId(),a.terms().currency(),lines,input.originalId()));store.validate(bank,entry);return entry;
  }
  if(input.kind()==Kind.TRANSFER){
   if(input.accountId().equals(input.destinationId()))throw error("OPERATION_FIELDS_INVALID");target=account(bank,input.destinationId(),false);
   if(!a.terms().currency().equals(target.terms().currency())||!a.legalEntityId().equals(target.legalEntityId()))throw error("TRANSFER_SCOPE_MISMATCH");credit=store.position(bank,target).ledgerId();
  }else if(input.kind()==Kind.WITHDRAWAL||input.kind()==Kind.CLOSURE_SETTLEMENT){
   if(input.kind()==Kind.CLOSURE_SETTLEMENT){
    // Pays out the whole posted balance, including any product minimum; holds must be released first.
    var check=closures.requireSettleable(bank,a);
    if(amount.compareTo(new BigDecimal(check.posted()))!=0||new BigDecimal(source.held()).signum()!=0)throw error("CLOSURE_SETTLEMENT_AMOUNT_MISMATCH");
   }
   credit=input.assetLedgerId();if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.ledger_registry l JOIN platform.chart_account c ON c.id=l.chart_account_id WHERE l.id=? AND l.tenant_id=? AND c.category='ASSET' AND c.normal_balance='DEBIT')",Boolean.class,credit,bank.id()))throw error("FUNDING_SOURCE_INVALID");
  }else if(input.kind()==Kind.FUNDING_REVERSAL){
   var rows=jdbc.query("SELECT payload FROM platform.funding_delivery WHERE request_id=? AND account_id=? AND tenant_id=? AND completed",(r,n)->JSON.readValue(r.getString(1),FundingModels.Entry.class),input.originalId(),a.id(),bank.id());
   if(rows.size()!=1||new BigDecimal(rows.getFirst().amount()).compareTo(amount)!=0)throw error("FUNDING_REVERSAL_INVALID");
   credit=rows.getFirst().journal().lines().stream().filter(l->l.side()==JournalModels.Side.DEBIT).findFirst().orElseThrow().ledgerId();reversal=input.originalId();
  }
  JournalModels.Entry journal=null;
  if(credit!=null){
   var lines=List.of(new JournalModels.Line(source.ledgerId(),JournalModels.Side.DEBIT,input.amount()),new JournalModels.Line(credit,JournalModels.Side.CREDIT,input.amount()));
   // Preserve original line ordering for the immutable full-reversal contract.
   if(reversal!=null)lines=List.of(lines.get(1),lines.get(0));
   var j=new JournalModels.Change(input.expectedVersion(),input.reference(),input.reason(),input.businessDate(),a.legalEntityId(),a.terms().currency(),lines,reversal,input.reason());if(cash)journals.validateCashFunding(bank,j);else journals.validateFunding(bank,j);
   journal=new JournalModels.Entry(id,bank.id(),input.reference().trim(),input.reason().trim(),input.businessDate(),a.legalEntityId(),a.terms().currency(),lines,reversal);
  }
  var e=new Entry(id,bank.id(),input,a,target,journal);store.validate(bank,e);return e;
 }
 public Map<String,Object> values(Tenant bank,Change input){var a=accounts.current(bank.id(),input.accountId()).definition();var result=new LinkedHashMap<String,Object>();result.put("operation",input.kind().name());result.put("accountNumber",a.accountNumber());result.put("amount",input.amount());result.put("currency",a.terms().currency());result.put("reference",input.reference());result.put("businessDate",input.businessDate().toString());if(input.destinationId()!=null)result.put("destinationAccount",accounts.current(bank.id(),input.destinationId()).definition().accountNumber());if(input.originalId()!=null)result.put("originalId",input.originalId());return result;}
 public void enqueue(Actor reviewer,UUID bankId,Change input,UUID id,String maker){enqueue(reviewer,bankId,input,id,maker,false);}
 public Entry enqueueCash(Actor reviewer,UUID bankId,Change input,UUID id,String maker){return enqueue(reviewer,bankId,input,id,maker,true);}
 public Entry enqueuePayment(Actor reviewer,UUID bankId,Change input,UUID id,String maker){return enqueue(reviewer,bankId,input,id,maker,false,true);}
 private Entry enqueue(Actor reviewer,UUID bankId,Change input,UUID id,String maker,boolean cash){return enqueue(reviewer,bankId,input,id,maker,cash,false);}
 private Entry enqueue(Actor reviewer,UUID bankId,Change input,UUID id,String maker,boolean cash,boolean payment){return enqueue(reviewer,bankId,input,id,maker,cash,payment,false);}
 public Entry enqueueSettlementReturn(Actor reviewer,UUID bankId,Change input,UUID id,String maker){com.cbs.payments.PaymentAccess.require(jdbc,reviewer,bankId,com.cbs.payments.PaymentAccess.Capability.APPROVE);if(input.kind()!=Kind.WITHDRAWAL_REVERSAL)throw error("PAYMENT_RETURN_INVALID");return enqueue(reviewer,bankId,input,id,maker,false,true,true);}
 private Entry enqueue(Actor reviewer,UUID bankId,Change input,UUID id,String maker,boolean cash,boolean payment,boolean settlementReturn){
  var bank=tenants.get(bankId,true);if(!settlementReturn)branches.approve(reviewer,bankId,input,id,maker);var e=validate(bank,input,id,cash,payment);
  if(e.journal()!=null){var j=e.journal();jdbc.update("INSERT INTO platform.journal_delivery(request_id,tenant_id,reference,reversal_of,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",id,bankId,j.reference(),j.reversalOf(),JSON.writeValueAsString(j),maker,reviewer.issuer(),reviewer.subject(),reviewer.correlationId());for(var line:j.lines())jdbc.update("INSERT INTO platform.journal_ledger(request_id,ledger_id) VALUES (?,?)",id,line.ledgerId());}
  jdbc.update("INSERT INTO platform.account_operation(request_id,tenant_id,account_id,destination_id,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",id,bankId,input.accountId(),input.destinationId(),JSON.writeValueAsString(e),maker,reviewer.issuer(),reviewer.subject(),reviewer.correlationId());
  tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(reviewer,bankId,"ACCOUNT_OPERATION_APPROVED",bank.version()+1,bank.status(),bank.status(),"Approved request "+id,null,null,values(bank,input));return e;
 }
 public List<UUID> pending(){return jdbc.query("SELECT request_id FROM platform.account_operation WHERE NOT completed AND request_id NOT IN (SELECT request_id FROM platform.cash_delivery) ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 20",(r,n)->r.getObject(1,UUID.class));}
 public boolean handles(UUID bankId,UUID id){return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_operation WHERE tenant_id=? AND request_id=?)",Boolean.class,bankId,id);}
 public void retry(Actor actor,UUID bankId,UUID id){AccessService.admin(actor);if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_operation WHERE request_id=? AND tenant_id=? AND reviewer_issuer=?)",Boolean.class,id,bankId,actor.issuer()))throw error("APPROVAL_NOT_FOUND");deliver(id);}
    private com.cbs.access.BankDuties duties;
    @org.springframework.beans.factory.annotation.Autowired void duties(com.cbs.access.BankDuties duties){this.duties=duties;}
 public void deliver(UUID id){if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_delivery WHERE request_id=?)",Boolean.class,id))throw error("CASH_MANAGED");tx.executeWithoutResult(t->{
  var rows=jdbc.query("SELECT * FROM platform.account_operation WHERE request_id=? AND NOT completed FOR UPDATE",(r,n)->new Dispatch(JSON.readValue(r.getString("payload"),Entry.class),r.getString("maker_subject"),r.getString("reviewer_issuer"),r.getString("reviewer_subject"),r.getObject("correlation_id",UUID.class)),id);if(rows.isEmpty())return;
  var job=rows.getFirst();var bank=tenants.get(job.entry().bankId(),true);String failure=null;
  try{if(!store.posted(bank,id)){duties.requireAtDelivery(id);if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.payment_order WHERE (refund_id=? OR return_id=?) AND reconciliation_conflict)",Boolean.class,id,id))throw error("PAYMENT_CONFLICTING_EVIDENCE");if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");if(job.entry().change().kind()!=Kind.RELEASE){customers.requireEligible(bank,job.entry().account().customerId());if(job.entry().destination()!=null)customers.requireEligible(bank,job.entry().destination().customerId());}}store.post(bank,job);}catch(TenantException e){failure=e.code();}
  jdbc.update("UPDATE platform.account_operation SET attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=? WHERE request_id=?",failure,id);
  jdbc.update("UPDATE platform.journal_delivery SET failure_code=? WHERE request_id=?",failure,id);if(failure!=null)return;
  jdbc.update("UPDATE platform.account_operation SET completed=true,completed_at=clock_timestamp() WHERE request_id=?",id);
  jdbc.update("UPDATE platform.journal_delivery SET completed=true,completed_at=clock_timestamp(),failure_code=NULL WHERE request_id=?",id);
  alert(bank.id(),job.entry());
  tenants.audit(new Actor(job.issuer(),job.checker(),true,job.correlationId()),bank.id(),"ACCOUNT_OPERATION_POSTED",bank.version(),bank.status(),bank.status(),"Posted approved request "+id,null,null,Map.of("requestId",id,"operation",job.entry().change().kind().name()));
 });}
 /** Records customer alerts for posted money movements (holds and releases move no money). */
 private void alert(UUID bankId,Entry e){
  var c=e.change();if(e.journal()==null)return;var N=com.cbs.notifications.NotificationModels.Event.class;
  java.util.function.BiConsumer<UUID,com.cbs.notifications.NotificationModels.Event> note=(account,event)->notifications.record(new com.cbs.notifications.NotificationModels.Movement(bankId,account,event,e.id(),c.amount(),e.journal().currency(),c.businessDate(),c.reference()));
  switch(c.kind()){
   case WITHDRAWAL,FUNDING_REVERSAL,CLOSURE_SETTLEMENT->note.accept(c.accountId(),com.cbs.notifications.NotificationModels.Event.DEBIT);
   case WITHDRAWAL_REVERSAL,CLOSURE_SETTLEMENT_REVERSAL->note.accept(c.accountId(),com.cbs.notifications.NotificationModels.Event.CREDIT);
   case TRANSFER,TRANSFER_REVERSAL->{note.accept(c.accountId(),com.cbs.notifications.NotificationModels.Event.DEBIT);note.accept(c.destinationId(),com.cbs.notifications.NotificationModels.Event.CREDIT);}
   default->{}
  }
 }
 private static TenantException error(String code){return new TenantException(409,code,"Account operation could not be completed");}
}
