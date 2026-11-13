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
 private final com.cbs.customers.CustomerService customers;private final TransactionTemplate tx;private final int chunkSize;
 public ServicingService(JdbcTemplate jdbc,TenantRepository tenants,ReferenceDataService reference,AccountService accounts,BankOperationStore balances,BankServicingStore store,JournalService journals,DayCloseService days,com.cbs.customers.CustomerService customers,PlatformTransactionManager manager,@org.springframework.beans.factory.annotation.Value("${cbs.servicing.chunk-size:500}") int chunkSize){if(chunkSize<1||chunkSize>5000)throw new IllegalArgumentException("cbs.servicing.chunk-size must be 1-5000");this.chunkSize=chunkSize;this.jdbc=jdbc;this.tenants=tenants;this.reference=reference;this.accounts=accounts;this.balances=balances;this.store=store;this.journals=journals;this.days=days;this.customers=customers;this.tx=new TransactionTemplate(manager);}
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
  return Map.of("version",bank.version(),"businessDate",reference.read(bank).businessDate(),"storageReady",store.ready(bank),"policies",jdbc.queryForList("SELECT id,currency,product_id,product_revision,legal_entity_id,effective_from,payload->>'annualRatePercent' AS annual_rate,payload->>'feePerBusinessDay' AS daily_fee FROM platform.servicing_policy WHERE tenant_id=? ORDER BY effective_from DESC,revision DESC LIMIT 100",bankId),"runs",jdbc.queryForList("SELECT r.request_id,r.business_date,r.completed,r.failure_code,r.attempts,(SELECT count(*) FROM platform.servicing_chunk c WHERE c.run_id=r.request_id) AS chunks,(SELECT count(*) FROM platform.servicing_chunk c WHERE c.run_id=r.request_id AND c.completed) AS chunks_posted,(SELECT count(*) FROM platform.servicing_exclusion x WHERE x.run_id=r.request_id) AS excluded FROM platform.servicing_run r WHERE r.tenant_id=? ORDER BY r.business_date DESC LIMIT 30",bankId));
 }
 /** Computed run: header, every planned item and the approved exclusions. */
 public record Plan(Batch header,List<Item> items,List<Exclusion> exclusions) {}
 public Preview preview(Actor actor,UUID bankId,Run input){AccessService.admin(actor);return tx.execute(t->{var plan=prepare(tenants.get(bankId,true),input,UUID.randomUUID());var h=plan.header();
  int shown=Math.min(plan.items().size(),200);return new Preview(h.id(),h.businessDate(),h.nextBusinessDate(),plan.items().size(),chunkSize,chunks(plan.items().size()),totals(plan.items()),plan.exclusions(),List.copyOf(plan.items().subList(0,shown)),shown<plan.items().size());});}
 private int chunks(int items){return (items+chunkSize-1)/chunkSize;}
 /**
  * Builds the run from bulk snapshots. Accounting or configuration problems still stop the
  * whole run; account-level conditions (frozen account, ineligible or restricted customer,
  * balance too low for the day's charges) exclude only that account and are listed for approval.
  */
 public Plan prepare(Tenant bank,Run input,UUID id){
  DayCloseService.requireOpen(jdbc,bank.id());if(bank.version()!=input.expectedVersion())throw error("STALE_VERSION");if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");
  var settings=reference.read(bank);if(!settings.businessDate().equals(input.businessDate()))throw error("JOURNAL_BUSINESS_DATE_INVALID");if(!store.ready(bank))throw error("SERVICING_STORAGE_NOT_READY");
  if(!store.chunked(bank))throw error("SERVICING_STORAGE_NOT_READY");
  days.requireReadyForServicing(bank,id);
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.approval_request WHERE bank_id=? AND status='PENDING' AND id<>?) OR EXISTS(SELECT 1 FROM platform.account_delivery WHERE tenant_id=? AND NOT completed) OR EXISTS(SELECT 1 FROM platform.customer_delivery WHERE tenant_id=? AND NOT completed)",Boolean.class,bank.id(),id,bank.id(),bank.id()))throw error("DAY_CLOSE_PENDING_APPROVALS");
  var next=ReferenceDataService.nextWorkingDay(settings.businessDate(),settings.weekendDays(),settings.holidays());int count=Math.toIntExact(ChronoUnit.DAYS.between(input.businessDate(),next));if(count>MAX_PERIOD_DAYS)throw error("SERVICING_PERIOD_INVALID");
  record Row(UUID id,AccountModels.Definition definition,String delivery){}
  var rows=jdbc.query("SELECT a.id,d.payload,a.delivery FROM platform.account_registry a JOIN platform.account_delivery d ON d.request_id=a.request_id JOIN platform.account_ledger l ON l.account_id=a.id WHERE a.tenant_id=? AND a.state<>'CLOSED' AND NOT EXISTS(SELECT 1 FROM platform.account_operation o WHERE o.account_id=a.id AND o.completed AND o.payload->'change'->>'kind'='CLOSURE_SETTLEMENT') ORDER BY a.id",
   (r,n)->new Row(r.getObject(1,UUID.class),JSON.readValue(r.getString(2),AccountModels.Definition.class),r.getString(3)),bank.id());
  var snapshots=store.snapshot(bank,rows.stream().map(Row::id).toList());
  var ineligible=customers.ineligible(bank,rows.stream().map(r->r.definition().customerId()).toList());
  // Policies are few: load once per product revision and legal entity.
  var policies=new HashMap<String,Optional<Map.Entry<UUID,Policy>>>();var validated=new HashSet<UUID>();
  var items=new ArrayList<Item>();var exclusions=new ArrayList<Exclusion>();
  for(var row:rows){
   var a=row.definition();
   if(settings.currencies().stream().noneMatch(c->c.enabled()&&c.code().equals(a.terms().currency())))throw error("CURRENCY_NOT_ENABLED");
   if(!row.delivery().equals("APPLIED"))throw error("SERVICING_ACCOUNT_NOT_READY");
   var snap=snapshots.get(a.id());if(snap==null||!a.equals(snap.account()))throw new TenantException(503,"ACCOUNT_RECONCILIATION_REQUIRED","Account records do not match");
   if(!snap.reconciled())throw error("FUNDING_UNRECONCILED");
   String key=a.terms().id()+":"+a.terms().revision()+":"+a.legalEntityId();
   var found=policies.computeIfAbsent(key,k->jdbc.query("SELECT id,payload FROM platform.servicing_policy WHERE tenant_id=? AND product_id=? AND product_revision=? AND legal_entity_id=? AND effective_from<=? ORDER BY effective_from DESC,revision DESC LIMIT 1",(r,n)->Map.entry(r.getObject(1,UUID.class),JSON.readValue(r.getString(2),Policy.class)),bank.id(),a.terms().id(),a.terms().revision(),a.legalEntityId(),input.businessDate()).stream().findFirst()
    .map(e->{if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_policy WHERE tenant_id=? AND product_id=? AND product_revision=? AND legal_entity_id=? AND effective_from>? AND effective_from<?)",Boolean.class,bank.id(),a.terms().id(),a.terms().revision(),a.legalEntityId(),input.businessDate(),next))throw error("SERVICING_POLICY_PERIOD_SPLIT");return e;}));
   if(found.isEmpty())throw error("SERVICING_POLICY_REQUIRED");
   if(a.state()!=AccountModels.State.ACTIVE){exclusions.add(new Exclusion(a.id(),a.accountNumber(),"ACCOUNT_"+a.state().name()));continue;}
   var customerProblem=ineligible.get(a.customerId());if(customerProblem!=null){exclusions.add(new Exclusion(a.id(),a.accountNumber(),customerProblem));continue;}
   var selected=found.get();var policy=selected.getValue();var opening=snap.posted();var carry=snap.carry();var calculation=calculate(policy,a.terms().currency(),opening,carry,count);
   if(opening.add(new BigDecimal(calculation.netChange())).subtract(snap.held()).compareTo(new BigDecimal(a.terms().minimumBalance()))<0){exclusions.add(new Exclusion(a.id(),a.accountNumber(),"INSUFFICIENT_AVAILABLE_BALANCE"));continue;}
   UUID itemId=UUID.nameUUIDFromBytes((id+":"+a.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));var lines=new ArrayList<JournalModels.Line>();
   add(lines,policy.interestExpenseLedger(),JournalModels.Side.DEBIT,calculation.grossInterest());add(lines,policy.interestPayableLedger(),JournalModels.Side.CREDIT,calculation.grossInterest());
   add(lines,policy.interestPayableLedger(),JournalModels.Side.DEBIT,calculation.grossInterest());add(lines,snap.ledgerId(),JournalModels.Side.CREDIT,text(new BigDecimal(calculation.grossInterest()).subtract(new BigDecimal(calculation.interestTax()))));add(lines,policy.taxPayableLedger(),JournalModels.Side.CREDIT,calculation.interestTax());
   add(lines,snap.ledgerId(),JournalModels.Side.DEBIT,text(new BigDecimal(calculation.fee()).add(new BigDecimal(calculation.feeTax()))));add(lines,policy.feeIncomeLedger(),JournalModels.Side.CREDIT,calculation.fee());add(lines,policy.taxPayableLedger(),JournalModels.Side.CREDIT,calculation.feeTax());
   JournalModels.Entry journal=null;
   if(!lines.isEmpty()){
    String ref="SVC-"+itemId;var change=new JournalModels.Change(bank.version(),ref,"Daily interest capitalization, fees and taxes",input.businessDate(),a.legalEntityId(),a.terms().currency(),lines,null,input.reason());
    // Full ledger validation runs once per policy ledger set; each customer ledger is the
    // account's own bound ledger, already reconciled above and checked in bulk below.
    var shared=lines.stream().map(JournalModels.Line::ledgerId).filter(l->!l.equals(snap.ledgerId())).collect(java.util.stream.Collectors.toSet());
    if(!validated.containsAll(shared)){journals.validateFunding(bank,change);validated.addAll(shared);}else requireBalanced(change);
    journal=new JournalModels.Entry(itemId,bank.id(),ref,"Daily interest capitalization, fees and taxes",input.businessDate(),a.legalEntityId(),a.terms().currency(),lines,null);
   }
   items.add(new Item(itemId,selected.getKey(),policy,a,text(opening),text(snap.held()),text(carry),calculation,journal));
  }
  requireCustomerLedgersReady(bank,items);
  if(rows.isEmpty()&&!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_policy WHERE tenant_id=?)",Boolean.class,bank.id()))throw error("SERVICING_POLICY_REQUIRED");
  return new Plan(new Batch(id,bank.id(),input.businessDate(),next,List.of(),items.size(),chunks(items.size()),exclusions.size()),items,exclusions);
 }
 private static void requireBalanced(JournalModels.Change change){
  int scale=Currency.getInstance(change.currency()).getDefaultFractionDigits();BigDecimal debit=BigDecimal.ZERO,credit=BigDecimal.ZERO;
  for(var line:change.lines()){var amount=new BigDecimal(line.amount());if(amount.signum()<=0||amount.stripTrailingZeros().scale()>scale)throw error("JOURNAL_AMOUNT_INVALID");if(line.side()==JournalModels.Side.DEBIT)debit=debit.add(amount);else credit=credit.add(amount);}
  if(debit.compareTo(credit)!=0)throw error("JOURNAL_UNBALANCED");
 }
 private void requireCustomerLedgersReady(Tenant bank,List<Item> items){
  var ledgers=items.stream().filter(i->i.journal()!=null).map(i->Map.entry(i.account().id(),i.journal().lines().stream().map(JournalModels.Line::ledgerId).toList())).toList();if(ledgers.isEmpty())return;
  var accountIds=ledgers.stream().map(Map.Entry::getKey).toArray();
  var ready=new HashSet<UUID>(jdbc.query(c->{var s=c.prepareStatement("SELECT b.account_id FROM platform.account_ledger b JOIN platform.ledger_registry r ON r.id=b.ledger_id JOIN platform.chart_account c ON c.id=r.chart_account_id WHERE r.tenant_id=? AND b.account_id=ANY(?) AND r.active AND r.delivery='APPLIED' AND c.active AND c.kind='POSTING'");s.setObject(1,bank.id());s.setArray(2,c.createArrayOf("uuid",accountIds));return s;},(r,n)->r.getObject(1,UUID.class)));
  for(var e:ledgers)if(!ready.contains(e.getKey()))throw error("JOURNAL_LEDGER_NOT_READY");
 }
 private static List<Totals> totals(List<Item> items){
  var byCurrency=new TreeMap<String,BigDecimal[]>();var counts=new HashMap<String,Integer>();
  for(var i:items){var c=i.calculation();var t=byCurrency.computeIfAbsent(i.account().terms().currency(),k->new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO});counts.merge(i.account().terms().currency(),1,Integer::sum);
   t[0]=t[0].add(new BigDecimal(c.grossInterest()));t[1]=t[1].add(new BigDecimal(c.interestTax()));t[2]=t[2].add(new BigDecimal(c.fee()));t[3]=t[3].add(new BigDecimal(c.feeTax()));t[4]=t[4].add(new BigDecimal(c.netChange()));}
  return byCurrency.entrySet().stream().map(e->new Totals(e.getKey(),counts.get(e.getKey()),text(e.getValue()[0]),text(e.getValue()[1]),text(e.getValue()[2]),text(e.getValue()[3]),text(e.getValue()[4]))).toList();
 }
 private static void add(List<JournalModels.Line> lines,UUID ledger,JournalModels.Side side,String amount){if(new BigDecimal(amount).signum()>0)lines.add(new JournalModels.Line(ledger,side,amount));}
 /** Approval summary: exact totals per currency, every exclusion, and the first 100 calculations. */
 public Map<String,Object> values(Plan plan){
  var b=plan.header();var result=new LinkedHashMap<String,Object>();
  result.put("businessDate",b.businessDate());result.put("nextBusinessDate",b.nextBusinessDate());result.put("accountCount",plan.items().size());result.put("chunkCount",b.chunkCount());
  result.put("totals",totals(plan.items()));result.put("excludedCount",plan.exclusions().size());result.put("exclusions",plan.exclusions());
  result.put("calculations",plan.items().stream().limit(100).map(i->Map.of("account",i.account().accountNumber(),"currency",i.account().terms().currency(),"openingBalance",i.openingBalance(),"policyId",i.policyId(),"calculation",i.calculation())).toList());
  if(plan.items().size()>100)result.put("calculationsShown",100);
  return result;
 }
 public void enqueue(Actor actor,UUID bankId,Run input,UUID id,String maker){
  AccessService.admin(actor);var bank=tenants.get(bankId,true);var plan=prepare(bank,input,id);var header=plan.header();
  jdbc.update("INSERT INTO platform.servicing_run(request_id,tenant_id,business_date,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?::jsonb,?,?,?,?)",id,bankId,input.businessDate(),JSON.writeValueAsString(header),maker,actor.issuer(),actor.subject(),actor.correlationId());
  var items=plan.items();
  for(int n=0;n<header.chunkCount();n++){var part=List.copyOf(items.subList(n*chunkSize,Math.min(items.size(),(n+1)*chunkSize)));jdbc.update("INSERT INTO platform.servicing_chunk(run_id,chunk_no,payload,item_count) VALUES (?,?,?::jsonb,?)",id,n,JSON.writeValueAsString(new Chunk(id,n,part)),part.size());}
  var journals=items.stream().map(Item::journal).filter(Objects::nonNull).toList();
  jdbc.batchUpdate("INSERT INTO platform.journal_delivery(request_id,approval_id,tenant_id,reference,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",journals,500,(ps,j)->{ps.setObject(1,j.id());ps.setObject(2,id);ps.setObject(3,bankId);ps.setString(4,j.reference());ps.setString(5,JSON.writeValueAsString(j));ps.setString(6,maker);ps.setString(7,actor.issuer());ps.setString(8,actor.subject());ps.setObject(9,actor.correlationId());});
  var ledgerRows=journals.stream().flatMap(j->j.lines().stream().map(l->Map.entry(j.id(),l.ledgerId())).distinct()).toList();
  jdbc.batchUpdate("INSERT INTO platform.journal_ledger(request_id,ledger_id) VALUES (?,?) ON CONFLICT DO NOTHING",ledgerRows,1000,(ps,e)->{ps.setObject(1,e.getKey());ps.setObject(2,e.getValue());});
  jdbc.batchUpdate("INSERT INTO platform.servicing_posting VALUES (?,?)",journals,1000,(ps,j)->{ps.setObject(1,j.id());ps.setObject(2,id);});
  jdbc.batchUpdate("INSERT INTO platform.servicing_exclusion(run_id,account_id,account_number,reason) VALUES (?,?,?,?)",plan.exclusions(),1000,(ps,x)->{ps.setObject(1,id);ps.setObject(2,x.accountId());ps.setString(3,x.accountNumber());ps.setString(4,x.reason());});
  tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(actor,bankId,"SERVICING_RUN_APPROVED",bank.version()+1,bank.status(),bank.status(),input.reason(),null,null,values(plan));
 }
 public List<UUID> pending(){return jdbc.queryForList("SELECT request_id FROM platform.servicing_run WHERE NOT completed ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 10",UUID.class);}
 public void retry(Actor actor,UUID bank,UUID id){AccessService.admin(actor);if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_run WHERE tenant_id=? AND request_id=? AND reviewer_issuer=?)",Boolean.class,bank,id,actor.issuer()))throw error("APPROVAL_NOT_FOUND");deliver(id);}
 private Dispatch dispatch(UUID id,boolean lock){
  var rows=jdbc.query("SELECT * FROM platform.servicing_run WHERE request_id=? AND NOT completed"+(lock?" FOR UPDATE":""),(r,n)->new Dispatch(JSON.readValue(r.getString("payload"),Batch.class),r.getString("maker_subject"),r.getString("reviewer_issuer"),r.getString("reviewer_subject"),r.getObject("correlation_id",UUID.class)),id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /**
  * Posts pending chunks in order, each in its own bank transaction. A failed chunk stops the
  * run with its failure code; a retry resumes at that chunk. Posted chunks are never repeated.
  * The business day stays sealed until every chunk has posted.
  */
    private com.cbs.access.BankDuties duties;
    @org.springframework.beans.factory.annotation.Autowired void duties(com.cbs.access.BankDuties duties){this.duties=duties;}
 public void deliver(UUID id){
  var job=dispatch(id,false);if(job==null)return;
  if(!job.batch().chunked()){deliverLegacy(id);return;}
  for(int number:jdbc.queryForList("SELECT chunk_no FROM platform.servicing_chunk WHERE run_id=? AND NOT completed ORDER BY chunk_no",Integer.class,id))
   if(!Boolean.TRUE.equals(tx.execute(t->deliverChunk(id,number))))return;
  tx.executeWithoutResult(t->finish(id));
 }
 private boolean deliverChunk(UUID id,int number){
  var job=dispatch(id,true);if(job==null)return false;
  var chunks=jdbc.query("SELECT payload FROM platform.servicing_chunk WHERE run_id=? AND chunk_no=? AND NOT completed FOR UPDATE",(r,n)->JSON.readValue(r.getString(1),Chunk.class),id,number);if(chunks.isEmpty())return true;
  var chunk=chunks.getFirst();var bank=tenants.get(job.batch().bankId(),true);String failure=null;
  try{
   if(!store.chunkPosted(bank,id,number)){
    if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");
    duties.requireAtDelivery(id);
    // Eligibility can change after approval; a newly restricted customer stops the chunk for review.
    var problems=customers.ineligible(bank,chunk.items().stream().map(i->i.account().customerId()).toList());
    if(!problems.isEmpty())throw error(problems.values().stream().sorted().findFirst().orElseThrow());
   }
   store.postChunk(bank,job,chunk);
  }catch(TenantException ex){failure=ex.code();}
  var ids=chunk.items().stream().map(Item::id).toArray();final String code=failure;
  jdbc.update("UPDATE platform.servicing_chunk SET attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=?,completed=?,completed_at=CASE WHEN ? THEN clock_timestamp() END WHERE run_id=? AND chunk_no=?",failure,failure==null,failure==null,id,number);
  jdbc.update(c->{var s=c.prepareStatement("UPDATE platform.journal_delivery SET completed=?,failure_code=?,completed_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END WHERE request_id=ANY(?)");s.setBoolean(1,code==null);s.setString(2,code);s.setBoolean(3,code==null);s.setArray(4,c.createArrayOf("uuid",ids));return s;});
  jdbc.update("UPDATE platform.servicing_run SET last_attempt_at=clock_timestamp(),failure_code=?,attempts=attempts+CASE WHEN ? THEN 1 ELSE 0 END WHERE request_id=?",failure,failure!=null,id);
  return failure==null;
 }
 private void finish(UUID id){
  var job=dispatch(id,true);if(job==null)return;
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_chunk WHERE run_id=? AND NOT completed)",Boolean.class,id))return;
  var bank=tenants.get(job.batch().bankId(),true);String failure=null;
  // A run with no account items still seals the bank day.
  if(job.batch().chunkCount()==0)try{if(!store.posted(bank,id)){if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");duties.requireAtDelivery(id);}store.postHeader(bank,job);}catch(TenantException ex){failure=ex.code();}
  jdbc.update("UPDATE platform.servicing_run SET attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=?,completed=? WHERE request_id=?",failure,failure==null,id);
  if(failure==null)tenants.audit(new Actor(job.issuer(),job.checker(),true,job.correlationId()),bank.id(),"SERVICING_RUN_POSTED",bank.version(),bank.status(),bank.status(),"Posted daily processing batch",null,null,Map.of("runId",id,"chunks",job.batch().chunkCount(),"accounts",job.batch().itemCount(),"excluded",job.batch().excludedCount()));
 }
 /** Runs approved before chunking was introduced post atomically as before. */
 private void deliverLegacy(UUID id){tx.executeWithoutResult(t->{
  var job=dispatch(id,true);if(job==null)return;var bank=tenants.get(job.batch().bankId(),true);String failure=null;
  try{if(!store.posted(bank,id)){if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");duties.requireAtDelivery(id);for(var item:job.batch().items())customers.requireEligible(bank,item.account().customerId());}store.post(bank,job);}catch(TenantException ex){failure=ex.code();}
  jdbc.update("UPDATE platform.servicing_run SET attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=?,completed=? WHERE request_id=?",failure,failure==null,id);
  jdbc.update("UPDATE platform.journal_delivery SET completed=?,failure_code=?,completed_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END WHERE request_id IN (SELECT request_id FROM platform.servicing_posting WHERE run_id=?)",failure==null,failure,failure==null,id);
  if(failure==null)tenants.audit(new Actor(job.issuer(),job.checker(),true,job.correlationId()),bank.id(),"SERVICING_RUN_POSTED",bank.version(),bank.status(),bank.status(),"Posted daily processing batch",null,null,Map.of("runId",id));
 });}
 private static TenantException error(String code){return new TenantException(409,code,"Deposit servicing requires attention");}
}
