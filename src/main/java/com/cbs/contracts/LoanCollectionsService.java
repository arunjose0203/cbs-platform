package com.cbs.contracts;

import static com.cbs.contracts.ContractModels.*;
import com.cbs.access.*;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import jakarta.validation.constraints.*;
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
public class LoanCollectionsService {
 public enum Action {ASSIGN,CONTACT,PROMISE,CANCEL_PROMISE,ESCALATE,RESOLVE,REOPEN}
 public enum Escalation {NONE,TEAM_LEAD,CREDIT_MANAGER,LEGAL_REVIEW}
 public record Change(@PositiveOrZero long expectedVersion,@NotNull UUID contractId,@NotNull Action action,
   @Size(max=255) String owner,LocalDate followUpOn,@Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,8})?") String promiseAmount,
   LocalDate promiseDue,Escalation escalation,@NotBlank @Size(max=160) String evidenceReference,@NotBlank @Size(max=500) String reason){}
 public record Promise(UUID id,String amount,LocalDate dueDate,List<UUID> baselinePayments){}
 public record CaseState(String status,String owner,LocalDate followUpOn,Escalation escalation,Promise promise){}
 public record PromiseStatus(String status,String paid,String paidByDue,String remaining){}
 private final LendingAccess lending;
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final ContractService contracts;private final AccessService access;private final ReferenceDataService reference;private final TransactionTemplate tx;
 public LoanCollectionsService(LendingAccess lending,JdbcTemplate jdbc,TenantRepository tenants,ContractService contracts,AccessService access,ReferenceDataService reference,PlatformTransactionManager manager){this.lending=lending;this.jdbc=jdbc;this.tenants=tenants;this.contracts=contracts;this.access=access;this.reference=reference;tx=new TransactionTemplate(manager);}
 private static TenantException error(String code){return new TenantException(409,code,"Loan collections requires attention");}
 private List<Posting> events(UUID bank,UUID id){return jdbc.query("SELECT payload FROM platform.contract_delivery WHERE tenant_id=? AND contract_id=? AND completed ORDER BY created_at,id",(r,n)->JSON.readValue(r.getString(1),Posting.class),bank,id);}
 private CaseState state(UUID bank,UUID id){var rows=jdbc.query("SELECT snapshot FROM platform.loan_collection_event WHERE tenant_id=? AND contract_id=? ORDER BY sequence DESC LIMIT 1",(r,n)->JSON.readValue(r.getString(1),CaseState.class),bank,id);return rows.isEmpty()?new CaseState("OPEN",null,null,Escalation.NONE,null):rows.getFirst();}
 public static PromiseStatus promiseStatus(Promise promise,List<Posting> events,LocalDate date){
  if(promise==null)return new PromiseStatus("NONE","0","0","0");var reversed=LoanArrears.reversed(events);var baseline=new HashSet<>(promise.baselinePayments());BigDecimal paid=BigDecimal.ZERO,onTime=BigDecimal.ZERO;
  for(var p:events)if((p.kind()==Kind.REPAY||p.kind()==Kind.FEE_PAY)&&!baseline.contains(p.id())&&!reversed.contains(p.id())&&!p.businessDate().isAfter(date)){var cash=decimal(p.customerMovement()).negate();if(cash.signum()>0){paid=paid.add(cash);if(!p.businessDate().isAfter(promise.dueDate()))onTime=onTime.add(cash);}}
  var target=decimal(promise.amount());String status=onTime.compareTo(target)>=0?"FULFILLED":paid.compareTo(target)>=0?"FULFILLED_LATE":date.isAfter(promise.dueDate())?"BROKEN":"OPEN";
  return new PromiseStatus(status,text(paid),text(onTime),text(target.subtract(paid).max(BigDecimal.ZERO)));
 }
 private Map<String,Object> item(UUID bank,UUID id,LocalDate date){
  var d=contracts.definition(bank,id);if(d.terms().type()!=Type.LOAN)throw error("LOAN_REQUIRED");var events=events(bank,id);var state=state(bank,id);var result=new LinkedHashMap<String,Object>();
  result.put("definition",d);result.put("contractStatus",jdbc.queryForObject("SELECT status FROM platform.contract WHERE tenant_id=? AND id=?",String.class,bank,id));var moratoria=jdbc.query("SELECT start_date,end_date FROM platform.loan_moratorium WHERE tenant_id=? AND contract_id=?",(r,n)->new LoanArrears.MoratoriumInterval(r.getDate(1).toLocalDate(),r.getDate(2).toLocalDate()),bank,id);result.put("report",LoanArrears.calculate(events,date,moratoria));result.put("case",state);result.put("promiseStatus",promiseStatus(state.promise(),events,date));result.put("followUpOverdue",!state.status().equals("RESOLVED")&&state.followUpOn()!=null&&state.followUpOn().isBefore(date));result.put("pendingDelivery",jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_delivery WHERE tenant_id=? AND contract_id=? AND NOT completed)",Boolean.class,bank,id));
  var feeMap=new LinkedHashMap<UUID,LoanFeeModels.Details>();for(var p:events)if(p.feeServicing()!=null)feeMap.put(p.feeServicing().feeId(),p.feeServicing());result.put("fees",feeMap.values());return result;
 }
 public Map<String,Object> list(Actor actor,UUID bank,int limit,int offset){AccessService.page(limit,offset);return tx.execute(t->{var b=tenants.get(bank,true);String filter=lending.filter(actor,bank,"account_id");var date=reference.read(b).businessDate();var ids=jdbc.queryForList("SELECT id FROM platform.contract WHERE tenant_id=? AND kind='LOAN'"+filter+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",UUID.class,bank,limit,offset);return Map.of("version",b.version(),"businessDate",date,"items",ids.stream().map(id->item(bank,id,date)).toList(),"total",jdbc.queryForObject("SELECT count(*) FROM platform.contract WHERE tenant_id=? AND kind='LOAN'"+filter,Long.class,bank));});}
 public Map<String,Object> detail(Actor actor,UUID bank,UUID id,int limit,int offset){AccessService.page(limit,offset);return tx.execute(t->{var b=tenants.get(bank,true);lending.read(actor,bank,contracts.definition(bank,id).account().id());var result=item(bank,id,reference.read(b).businessDate());result.put("version",b.version());result.put("history",jdbc.query("SELECT id,kind,subject,payload,snapshot,created_at FROM platform.loan_collection_event WHERE tenant_id=? AND contract_id=? ORDER BY sequence DESC LIMIT ? OFFSET ?",(r,n)->Map.of("id",r.getObject(1),"kind",r.getString(2),"subject",r.getString(3),"change",JSON.readValue(r.getString(4),Change.class),"snapshot",JSON.readValue(r.getString(5),CaseState.class),"at",r.getTimestamp(6).toInstant()),bank,id,limit,offset));result.put("historyTotal",jdbc.queryForObject("SELECT count(*) FROM platform.loan_collection_event WHERE tenant_id=? AND contract_id=?",Long.class,bank,id));return result;});}
 public void change(Actor actor,UUID bank,UUID key,Change input){tx.executeWithoutResult(t->{
  tenants.get(bank,true);lending.role(actor,bank,contracts.definition(bank,input.contractId()).account().id(),LendingAccess.Role.COLLECTIONS_OFFICER);
  access.requireAssignable(actor.issuer(),actor.subject());jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->{},actor.issuer()+":"+actor.subject()+":"+key);var b=tenants.get(bank,true);var payload=JSON.writeValueAsString(input);
  var old=jdbc.queryForList("SELECT tenant_id,payload=?::jsonb AS same FROM platform.loan_collection_event WHERE issuer=? AND subject=? AND idempotency_key=?",payload,actor.issuer(),actor.subject(),key);if(!old.isEmpty()){if(!bank.equals(old.getFirst().get("tenant_id"))||!Boolean.TRUE.equals(old.getFirst().get("same")))throw error("IDEMPOTENCY_CONFLICT");return;}
  var date=reference.read(b).businessDate();contracts.ready(b,input.expectedVersion(),date);var d=contracts.definition(bank,input.contractId());if(d.terms().type()!=Type.LOAN)throw error("LOAN_REQUIRED");
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_delivery WHERE contract_id=? AND NOT completed)",Boolean.class,d.id()))throw error("CONTRACT_PENDING");
  var events=events(bank,d.id());var moratoria=jdbc.query("SELECT start_date,end_date FROM platform.loan_moratorium WHERE tenant_id=? AND contract_id=?",(r,n)->new LoanArrears.MoratoriumInterval(r.getDate(1).toLocalDate(),r.getDate(2).toLocalDate()),bank,d.id());var report=LoanArrears.calculate(events,date,moratoria);if(!report.reconciled())throw error("ARREARS_UNRECONCILED");var previous=state(bank,d.id());String status=previous.status(),owner=previous.owner();var follow=previous.followUpOn();var escalation=previous.escalation();var promise=previous.promise();UUID eventId=UUID.randomUUID();
  if(status.equals("RESOLVED")&&input.action()!=Action.REOPEN)throw error("COLLECTION_CASE_RESOLVED");
  if(input.owner()!=null&&input.action()!=Action.ASSIGN&&input.action()!=Action.REOPEN||input.promiseAmount()!=null&&input.action()!=Action.PROMISE||input.promiseDue()!=null&&input.action()!=Action.PROMISE||input.escalation()!=null&&input.action()!=Action.ESCALATE)throw error("COLLECTION_FIELDS_INVALID");
  if(input.followUpOn()!=null){if(input.followUpOn().isBefore(date)||input.followUpOn().isAfter(date.plusYears(10)))throw error("COLLECTION_FOLLOW_UP_INVALID");follow=input.followUpOn();}
  switch(input.action()){
   case ASSIGN,REOPEN -> {if(input.owner()==null||input.owner().isBlank()||input.followUpOn()==null)throw error("COLLECTION_OWNER_REQUIRED");access.requireAssignable(actor.issuer(),input.owner());lending.role(new Actor(actor.issuer(),input.owner(),false,actor.correlationId()),bank,d.account().id(),LendingAccess.Role.COLLECTIONS_OFFICER);owner=input.owner();status="OPEN";}
   case CONTACT -> {if(owner==null)throw error("COLLECTION_OWNER_REQUIRED");}
   case PROMISE -> {if(owner==null||input.promiseAmount()==null||input.promiseDue()==null||input.promiseDue().isBefore(date)||input.promiseDue().isAfter(date.plusYears(10)))throw error("COLLECTION_PROMISE_INVALID");var amount=decimal(input.promiseAmount());if(amount.signum()<=0||amount.stripTrailingZeros().scale()>scale(d)||amount.compareTo(decimal(report.outstanding().total()))>0)throw error("COLLECTION_PROMISE_INVALID");var ps=promiseStatus(promise,events,date);if(!List.of("NONE","FULFILLED","FULFILLED_LATE").contains(ps.status()))throw error("COLLECTION_PROMISE_ACTIVE");promise=new Promise(eventId,text(amount),input.promiseDue(),events.stream().filter(p->p.kind()==Kind.REPAY||p.kind()==Kind.FEE_PAY).map(Posting::id).toList());}
   case CANCEL_PROMISE -> {if(promise==null)throw error("COLLECTION_PROMISE_MISSING");promise=null;}
   case ESCALATE -> {if(owner==null||input.escalation()==null)throw error("COLLECTION_ESCALATION_INVALID");escalation=input.escalation();}
   case RESOLVE -> {var ps=promiseStatus(promise,events,date);if(!report.accrualComplete()||decimal(report.overdue().total()).signum()>0||List.of("OPEN","BROKEN").contains(ps.status())||events.stream().anyMatch(p->p.kind()==Kind.WRITE_OFF))throw error("COLLECTION_RESOLUTION_BLOCKED");status="RESOLVED";follow=null;escalation=Escalation.NONE;}
  }
  var snapshot=new CaseState(status,owner,follow,escalation,promise);
  jdbc.update("INSERT INTO platform.loan_collection_event(id,tenant_id,contract_id,issuer,subject,idempotency_key,kind,payload,snapshot,business_date) VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)",eventId,bank,d.id(),actor.issuer(),actor.subject(),key,input.action().name(),payload,JSON.writeValueAsString(snapshot),date);
  tenants.change(b,b.status(),b.displayName(),b.defaultLanguage());tenants.audit(actor,bank,"LOAN_COLLECTION_"+input.action(),b.version()+1,b.status(),b.status(),input.reason(),null,null,Map.of("contractId",d.id(),"eventId",eventId,"evidenceReference",input.evidenceReference()));
 });}
}
