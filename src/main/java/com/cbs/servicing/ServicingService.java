package com.cbs.servicing;
import static com.cbs.servicing.ServicingModels.*;
import com.cbs.access.AccessService;
import com.cbs.accounting.*;
import com.cbs.accounts.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import com.cbs.reference.ReferenceDataService;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ServicingService {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final ReferenceDataService reference;private final AccountService accounts;
 private final BankOperationStore balances;private final BankServicingStore store;private final JournalService journals;private final DayCloseService days;
 private final com.cbs.customers.CustomerService customers;private final TransactionTemplate tx;
 public ServicingService(JdbcTemplate jdbc,TenantRepository tenants,ReferenceDataService reference,AccountService accounts,BankOperationStore balances,BankServicingStore store,JournalService journals,DayCloseService days,com.cbs.customers.CustomerService customers,PlatformTransactionManager manager){this.jdbc=jdbc;this.tenants=tenants;this.reference=reference;this.accounts=accounts;this.balances=balances;this.store=store;this.journals=journals;this.days=days;this.customers=customers;this.tx=new TransactionTemplate(manager);}
 public String validatePolicy(Tenant bank,Policy input){
  DayCloseService.requireOpen(jdbc,bank.id());if(bank.version()!=input.expectedVersion())throw error("STALE_VERSION");if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");
  var currencies=jdbc.queryForList("SELECT definition->>'currency' FROM platform.deposit_product_revision WHERE tenant_id=? AND product_id=? AND revision=?",String.class,bank.id(),input.productId(),input.productRevision());
  if(currencies.size()!=1||bank.legalEntities().stream().noneMatch(x->x.id().equals(input.legalEntityId())))throw error("SERVICING_POLICY_INVALID");String currency=currencies.getFirst();if(reference.read(bank).currencies().stream().noneMatch(c->c.enabled()&&c.code().equals(currency)))throw error("CURRENCY_NOT_ENABLED");
  if(input.effectiveFrom().isBefore(reference.read(bank).businessDate())||input.effectiveFrom().getYear()>2200||!ReferenceDataService.working(input.effectiveFrom(),reference.read(bank).weekendDays(),reference.read(bank).holidays()))throw error("SERVICING_POLICY_DATE_INVALID");
  try{calculate(input,currency,BigDecimal.ZERO,BigDecimal.ZERO,1);}catch(RuntimeException ex){throw error("SERVICING_POLICY_INVALID");}
  var mappings=List.of(input.interestExpenseLedger(),input.interestPayableLedger(),input.feeIncomeLedger(),input.taxPayableLedger());
  if(new HashSet<>(mappings).size()!=4)throw error("SERVICING_LEDGER_INVALID");
  for(int i=0;i<4;i++){
   String category=i==0?"EXPENSE":i==2?"INCOME":"LIABILITY",normal=i==0?"DEBIT":"CREDIT";
   if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.ledger_registry l JOIN platform.chart_account c ON c.id=l.chart_account_id WHERE l.id=? AND l.tenant_id=? AND l.legal_entity_id=? AND l.currency=? AND l.active AND l.delivery='APPLIED' AND c.active AND c.kind='POSTING' AND c.category=? AND c.normal_balance=?) AND NOT EXISTS(SELECT 1 FROM platform.account_ledger WHERE ledger_id=?)",Boolean.class,mappings.get(i),bank.id(),input.legalEntityId(),currency,category,normal,mappings.get(i)))throw error("SERVICING_LEDGER_INVALID");
  }
  return currency;
 }
 public Map<String,Object> policyValues(Policy input){return Map.of("policy",input,"capitalization","DAILY","feeFrequency","ONCE_PER_BUSINESS_DAY","balanceBasis","CLOSING_POSTED","taxBasis","INTEREST_WITHHOLDING_AND_FEE_TAX","calendarDays","Business date inclusive to next working date exclusive");}
 public void applyPolicy(Actor actor,UUID bankId,Policy input,UUID id){
  AccessService.admin(actor);var bank=tenants.get(bankId,true);var currency=validatePolicy(bank,input);
  jdbc.update("INSERT INTO platform.servicing_policy(id,tenant_id,product_id,product_revision,legal_entity_id,effective_from,currency,payload) VALUES (?,?,?,?,?,?,?,?::jsonb)",id,bankId,input.productId(),input.productRevision(),input.legalEntityId(),input.effectiveFrom(),currency,JSON.writeValueAsString(input));
  tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(actor,bankId,"SERVICING_POLICY_APPROVED",bank.version()+1,bank.status(),bank.status(),input.reason(),null,null,policyValues(input));
 }
 public Map<String,Object> dashboard(Actor actor,UUID bankId){
  AccessService.admin(actor);var bank=tenants.get(bankId,false);
  return Map.of("version",bank.version(),"businessDate",reference.read(bank).businessDate(),"storageReady",store.ready(bank),"policies",jdbc.queryForList("SELECT id,currency,product_id,product_revision,legal_entity_id,effective_from,payload->>'annualRatePercent' AS annual_rate,payload->>'feePerBusinessDay' AS daily_fee FROM platform.servicing_policy WHERE tenant_id=? ORDER BY effective_from DESC,revision DESC LIMIT 100",bankId),"runs",jdbc.queryForList("SELECT request_id,business_date,completed,failure_code,attempts FROM platform.servicing_run WHERE tenant_id=? ORDER BY business_date DESC LIMIT 30",bankId));
 }
 public Batch preview(Actor actor,UUID bankId,Run input){AccessService.admin(actor);return tx.execute(t->prepare(tenants.get(bankId,true),input,UUID.randomUUID()));}
 public Batch prepare(Tenant bank,Run input,UUID id){
  DayCloseService.requireOpen(jdbc,bank.id());if(bank.version()!=input.expectedVersion())throw error("STALE_VERSION");if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");
  var settings=reference.read(bank);if(!settings.businessDate().equals(input.businessDate()))throw error("JOURNAL_BUSINESS_DATE_INVALID");if(!store.ready(bank))throw error("SERVICING_STORAGE_NOT_READY");
  days.requireReadyForServicing(bank,id);
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.approval_request WHERE bank_id=? AND status='PENDING' AND id<>?) OR EXISTS(SELECT 1 FROM platform.account_delivery WHERE tenant_id=? AND NOT completed) OR EXISTS(SELECT 1 FROM platform.customer_delivery WHERE tenant_id=? AND NOT completed)",Boolean.class,bank.id(),id,bank.id(),bank.id()))throw error("DAY_CLOSE_PENDING_APPROVALS");
  var next=ReferenceDataService.nextWorkingDay(settings.businessDate(),settings.weekendDays(),settings.holidays());int count=Math.toIntExact(ChronoUnit.DAYS.between(input.businessDate(),next));if(count>31)throw error("SERVICING_PERIOD_INVALID");
  var ids=jdbc.queryForList("SELECT a.id FROM platform.account_registry a JOIN platform.account_ledger l ON l.account_id=a.id WHERE a.tenant_id=? AND a.state<>'CLOSED' AND NOT EXISTS(SELECT 1 FROM platform.account_operation o WHERE o.account_id=a.id AND o.completed AND o.payload->'change'->>'kind'='CLOSURE_SETTLEMENT') ORDER BY a.id LIMIT 1001",UUID.class,bank.id());if(ids.size()>1000)throw error("SERVICING_BATCH_TOO_LARGE");
  var items=new ArrayList<Item>();
  for(UUID accountId:ids){
   var detail=accounts.current(bank.id(),accountId);var a=detail.definition();if(settings.currencies().stream().noneMatch(c->c.enabled()&&c.code().equals(a.terms().currency())))throw error("CURRENCY_NOT_ENABLED");if(!detail.delivery().equals("APPLIED")||a.state()!=AccountModels.State.ACTIVE)throw error("SERVICING_ACCOUNT_NOT_READY");accounts.requireMatching(bank,a);customers.requireEligible(bank,a.customerId());
   var policies=jdbc.query("SELECT id,payload FROM platform.servicing_policy WHERE tenant_id=? AND product_id=? AND product_revision=? AND legal_entity_id=? AND effective_from<=? ORDER BY effective_from DESC,revision DESC LIMIT 1",(r,n)->Map.entry(r.getObject(1,UUID.class),JSON.readValue(r.getString(2),Policy.class)),bank.id(),a.terms().id(),a.terms().revision(),a.legalEntityId(),input.businessDate());
   if(policies.isEmpty())throw error("SERVICING_POLICY_REQUIRED");var selected=policies.getFirst();var policy=selected.getValue();
   if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_policy WHERE tenant_id=? AND product_id=? AND product_revision=? AND legal_entity_id=? AND effective_from>? AND effective_from<?)",Boolean.class,bank.id(),a.terms().id(),a.terms().revision(),a.legalEntityId(),input.businessDate(),next))throw error("SERVICING_POLICY_PERIOD_SPLIT");
   var position=balances.position(bank,a);if(!position.reconciled())throw error("FUNDING_UNRECONCILED");var opening=new BigDecimal(position.posted());var carry=store.carry(bank,a.id());var calculation=calculate(policy,a.terms().currency(),opening,carry,count);
   if(opening.add(new BigDecimal(calculation.netChange())).subtract(new BigDecimal(position.held())).compareTo(new BigDecimal(a.terms().minimumBalance()))<0)throw error("INSUFFICIENT_AVAILABLE_BALANCE");
   UUID itemId=UUID.nameUUIDFromBytes((id+":"+a.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));var lines=new ArrayList<JournalModels.Line>();
   add(lines,policy.interestExpenseLedger(),JournalModels.Side.DEBIT,calculation.grossInterest());add(lines,policy.interestPayableLedger(),JournalModels.Side.CREDIT,calculation.grossInterest());
   add(lines,policy.interestPayableLedger(),JournalModels.Side.DEBIT,calculation.grossInterest());add(lines,position.ledgerId(),JournalModels.Side.CREDIT,text(new BigDecimal(calculation.grossInterest()).subtract(new BigDecimal(calculation.interestTax()))));add(lines,policy.taxPayableLedger(),JournalModels.Side.CREDIT,calculation.interestTax());
   add(lines,position.ledgerId(),JournalModels.Side.DEBIT,text(new BigDecimal(calculation.fee()).add(new BigDecimal(calculation.feeTax()))));add(lines,policy.feeIncomeLedger(),JournalModels.Side.CREDIT,calculation.fee());add(lines,policy.taxPayableLedger(),JournalModels.Side.CREDIT,calculation.feeTax());
   JournalModels.Entry journal=null;
   if(!lines.isEmpty()){
    String ref="SVC-"+itemId;journals.validateFunding(bank,new JournalModels.Change(bank.version(),ref,"Daily interest capitalization, fees and taxes",input.businessDate(),a.legalEntityId(),a.terms().currency(),lines,null,input.reason()));
    journal=new JournalModels.Entry(itemId,bank.id(),ref,"Daily interest capitalization, fees and taxes",input.businessDate(),a.legalEntityId(),a.terms().currency(),lines,null);
   }
   items.add(new Item(itemId,selected.getKey(),policy,a,position.posted(),position.held(),text(carry),calculation,journal));
  }
  if(ids.isEmpty()&&!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_policy WHERE tenant_id=?)",Boolean.class,bank.id()))throw error("SERVICING_POLICY_REQUIRED");
  return new Batch(id,bank.id(),input.businessDate(),next,items);
 }
 private static void add(List<JournalModels.Line> lines,UUID ledger,JournalModels.Side side,String amount){if(new BigDecimal(amount).signum()>0)lines.add(new JournalModels.Line(ledger,side,amount));}
 public Map<String,Object> values(Batch batch){return Map.of("businessDate",batch.businessDate(),"nextBusinessDate",batch.nextBusinessDate(),"accountCount",batch.items().size(),"calculations",batch.items().stream().map(i->Map.of("account",i.account().accountNumber(),"currency",i.account().terms().currency(),"openingBalance",i.openingBalance(),"policyId",i.policyId(),"calculation",i.calculation())).toList());}
 public void enqueue(Actor actor,UUID bankId,Run input,UUID id,String maker){
  AccessService.admin(actor);var bank=tenants.get(bankId,true);var batch=prepare(bank,input,id);
  jdbc.update("INSERT INTO platform.servicing_run(request_id,tenant_id,business_date,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?::jsonb,?,?,?,?)",id,bankId,input.businessDate(),JSON.writeValueAsString(batch),maker,actor.issuer(),actor.subject(),actor.correlationId());
  for(var item:batch.items())if(item.journal()!=null){var j=item.journal();jdbc.update("INSERT INTO platform.journal_delivery(request_id,approval_id,tenant_id,reference,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",j.id(),id,bankId,j.reference(),JSON.writeValueAsString(j),maker,actor.issuer(),actor.subject(),actor.correlationId());for(var line:j.lines())jdbc.update("INSERT INTO platform.journal_ledger(request_id,ledger_id) VALUES (?,?) ON CONFLICT DO NOTHING",j.id(),line.ledgerId());jdbc.update("INSERT INTO platform.servicing_posting VALUES (?,?)",j.id(),id);}
  tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(actor,bankId,"SERVICING_RUN_APPROVED",bank.version()+1,bank.status(),bank.status(),input.reason(),null,null,values(batch));
 }
 public List<UUID> pending(){return jdbc.queryForList("SELECT request_id FROM platform.servicing_run WHERE NOT completed ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 10",UUID.class);}
 public void retry(Actor actor,UUID bank,UUID id){AccessService.admin(actor);if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_run WHERE tenant_id=? AND request_id=? AND reviewer_issuer=?)",Boolean.class,bank,id,actor.issuer()))throw error("APPROVAL_NOT_FOUND");deliver(id);}
 public void deliver(UUID id){tx.executeWithoutResult(t->{
  var rows=jdbc.query("SELECT * FROM platform.servicing_run WHERE request_id=? AND NOT completed FOR UPDATE",(r,n)->new Dispatch(JSON.readValue(r.getString("payload"),Batch.class),r.getString("maker_subject"),r.getString("reviewer_issuer"),r.getString("reviewer_subject"),r.getObject("correlation_id",UUID.class)),id);if(rows.isEmpty())return;var job=rows.getFirst();var bank=tenants.get(job.batch().bankId(),true);String failure=null;
  try{if(!store.posted(bank,id)){if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");for(var item:job.batch().items())customers.requireEligible(bank,item.account().customerId());}store.post(bank,job);}catch(TenantException ex){failure=ex.code();}
  jdbc.update("UPDATE platform.servicing_run SET attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=?,completed=? WHERE request_id=?",failure,failure==null,id);
  jdbc.update("UPDATE platform.journal_delivery SET completed=?,failure_code=?,completed_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END WHERE request_id IN (SELECT request_id FROM platform.servicing_posting WHERE run_id=?)",failure==null,failure,failure==null,id);
  if(failure==null)tenants.audit(new Actor(job.issuer(),job.checker(),true,job.correlationId()),bank.id(),"SERVICING_RUN_POSTED",bank.version(),bank.status(),bank.status(),"Posted daily processing batch",null,null,Map.of("runId",id));
 });}
 private static TenantException error(String code){return new TenantException(409,code,"Deposit servicing requires attention");}
}
