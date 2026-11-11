package com.cbs.payments;

import static com.cbs.payments.PaymentModels.*;
import com.cbs.access.*;
import com.cbs.accounts.*;
import com.cbs.customers.CustomerService;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
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
public class PaymentService {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final OperationService operations;
 private final AccountService accounts;private final CustomerService customers;private final BranchOperations branches;
 private final AccessService access;private final ReferenceDataService reference;private final MpesaGateway gateway;private final TransactionTemplate tx;
 public PaymentService(JdbcTemplate jdbc,TenantRepository tenants,OperationService operations,AccountService accounts,CustomerService customers,BranchOperations branches,AccessService access,ReferenceDataService reference,MpesaGateway gateway,PlatformTransactionManager manager){this.jdbc=jdbc;this.tenants=tenants;this.operations=operations;this.accounts=accounts;this.customers=customers;this.branches=branches;this.access=access;this.reference=reference;this.gateway=gateway;this.tx=new TransactionTemplate(manager);}
 public OperationModels.Change debit(Order input){return new OperationModels.Change(input.expectedVersion(),OperationModels.Kind.WITHDRAWAL,input.accountId(),null,input.settlementLedgerId(),null,input.amount(),input.businessDate(),input.reference(),input.reason(),input.signatories());}
 private void eligible(Tenant bank,UUID account){
  var record=accounts.current(bank.id(),account);var a=record.definition();if(!record.delivery().equals("APPLIED")||a.state()!=AccountModels.State.ACTIVE)throw error("FUNDING_ACCOUNT_NOT_READY");accounts.requireMatching(bank,a);customers.requireEligible(bank,a.customerId());
  if(!a.terms().currency().equals("KES"))throw error("PAYMENT_KES_ONLY");
  if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.compliance_case WHERE tenant_id=? AND customer_id=? AND status='CLEARED')",Boolean.class,bank.id(),a.customerId()))throw error("PAYMENT_VERIFICATION_REQUIRED");
 }
 public void validate(Actor actor,Tenant bank,Order input){
  AccessService.admin(actor);if(!gateway.binding(bank.id(),input.settlementLedgerId()))throw error("PAYMENT_SANDBOX_NOT_CONFIGURED");
  eligible(bank,input.accountId());branches.authorize(actor,bank.id(),debit(input),false);operations.validatePayment(bank,debit(input),UUID.randomUUID());
 }
 public Map<String,Object> values(Tenant bank,Order input){var values=new LinkedHashMap<>(operations.values(bank,debit(input)));values.put("provider","Safaricom M-Pesa Kenya / SANDBOX");values.put("beneficiary",input.beneficiary());values.put("beneficiaryName",input.beneficiaryName());values.put("settlementLedgerId",input.settlementLedgerId());return values;}
 public void record(Actor actor,UUID bank,Order input,UUID request){branches.record(actor,bank,debit(input),request);}
 public void enqueue(Actor actor,UUID bankId,Order input,UUID request,String maker){
  var bank=tenants.get(bankId,true);if(!gateway.binding(bankId,input.settlementLedgerId()))throw error("PAYMENT_SANDBOX_NOT_CONFIGURED");eligible(bank,input.accountId());
  operations.enqueuePayment(actor,bankId,debit(input),request,maker);
  jdbc.update("INSERT INTO platform.payment_order(id,tenant_id,account_id,payload,issuer,maker,provider_profile) VALUES (?,?,?,?::jsonb,?,?,?)",request,bankId,input.accountId(),JSON.writeValueAsString(input),actor.issuer(),maker,gateway.profile(bankId));
  event(bankId,request,actor.subject(),"APPROVED",Map.of("environment","SANDBOX","currency","KES"));
 }
 private Map<String,Object> row(Actor actor,UUID bank,UUID id){
  var rows=jdbc.queryForList("SELECT * FROM platform.payment_order WHERE tenant_id=? AND id=? AND issuer=?",bank,id,actor.issuer());if(rows.isEmpty())throw new TenantException(404,"PAYMENT_NOT_FOUND","Payment not found");return rows.getFirst();
 }
 private Order order(Map<String,Object> row){return JSON.readValue(row.get("payload").toString(),Order.class);}
 private void event(UUID bank,UUID id,String subject,String action,Object value){jdbc.update("INSERT INTO platform.payment_event VALUES (?,?,?,?,?,?::jsonb,clock_timestamp())",UUID.randomUUID(),bank,id,action,subject,JSON.writeValueAsString(value));}
 private void changed(Actor actor,Tenant bank,String action,UUID id){tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(actor,bank.id(),action,bank.version()+1,bank.status(),bank.status(),"Payment "+id,null,null,Map.of("paymentId",id));}
 public Map<String,Object> dashboard(Actor actor,UUID bank,int limit,int offset){
  PaymentAccess.require(jdbc,actor,bank,PaymentAccess.Capability.READ);AccessService.page(limit,offset);return tx.execute(t->{
   jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bank);var tenant=tenants.get(bank,false);
   var items=jdbc.query("SELECT p.*,o.completed,o.failure_code FROM platform.payment_order p JOIN platform.account_operation o ON o.request_id=p.id WHERE p.tenant_id=? AND p.issuer=? ORDER BY p.created_at DESC,p.id LIMIT ? OFFSET ?",(r,n)->{var input=JSON.readValue(r.getString("payload"),Order.class);var item=new LinkedHashMap<String,Object>();item.put("id",r.getObject("id"));item.put("reference",input.reference());item.put("amount",input.amount());item.put("beneficiary","••••••"+input.beneficiary().substring(6));item.put("state",r.getBoolean("reconciliation_conflict")?"RECONCILIATION_CONFLICT":r.getString("state"));item.put("posted",r.getBoolean("completed"));item.put("failureCode",r.getString("failure_code"));return item;},bank,actor.issuer(),limit,offset);
   return Map.of("version",tenant.version(),"configured",gateway.configured(bank),"businessDate",reference.read(tenant).businessDate(),"items",items,"total",jdbc.queryForObject("SELECT count(*) FROM platform.payment_order WHERE tenant_id=? AND issuer=?",Long.class,bank,actor.issuer()),"environment","SANDBOX");
  });
 }
 public Map<String,Object> detail(Actor actor,UUID bank,UUID id){PaymentAccess.require(jdbc,actor,bank,PaymentAccess.Capability.READ);return tx.execute(t->{var tenant=tenants.get(bank,true);var payment=row(actor,bank,id);var safe=new LinkedHashMap<>(payment);safe.remove("callback_hash");safe.remove("query_hash");safe.remove("provider_profile");safe.put("payload",order(payment));safe.put("events",jdbc.query("SELECT action,subject,payload,created_at FROM platform.payment_event WHERE payment_id=? ORDER BY created_at DESC,id LIMIT 100",(r,n)->Map.of("action",r.getString(1),"subject",r.getString(2),"payload",JSON.readTree(r.getString(3)),"at",r.getTimestamp(4).toInstant()),id));safe.put("observations",jdbc.query("SELECT payload FROM platform.payment_observation WHERE payment_id=? ORDER BY created_at DESC LIMIT 100",(r,n)->JSON.readTree(r.getString(1)),id));safe.put("statements",jdbc.query("SELECT id,subject,payload FROM platform.payment_statement WHERE payment_id=? ORDER BY created_at DESC LIMIT 100",(r,n)->Map.of("id",r.getObject(1),"subject",r.getString(2),"payload",JSON.readValue(r.getString(3),Statement.class)),id));safe.put("version",tenant.version());tenants.audit(actor,bank,"PAYMENT_VIEWED",tenant.version(),tenant.status(),tenant.status(),"Payment evidence viewed",null,null,Map.of("paymentId",id));return safe;});}
 private static String token(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
 static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 public void dispatch(Actor actor,UUID bankId,UUID id){
  AccessService.admin(actor);String token=token();
  Order input=tx.execute(t->{var bank=tenants.get(bankId,true);var payment=row(actor,bankId,id);if(!payment.get("state").equals("APPROVED"))return null;
   if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");var value=order(payment);if(!gateway.binding(bankId,value.settlementLedgerId())||!payment.get("provider_profile").equals(gateway.profile(bankId)))throw error("PAYMENT_SANDBOX_NOT_CONFIGURED");
   eligible(bank,value.accountId());branches.review(actor,id,false);access.requireAssignable(actor.issuer(),payment.get("maker").toString());
   var maker=new Actor(actor.issuer(),payment.get("maker").toString(),true,actor.correlationId());branches.authorize(maker,bankId,debit(value),false);
   var scope=branches.scope(id);if(scope!=null&&!scope.branchId().equals(branches.branch(bankId,value.accountId())))throw error("BRANCH_ACCESS_DENIED");
   if(!jdbc.queryForObject("SELECT completed FROM platform.account_operation WHERE request_id=?",Boolean.class,id))throw error("PAYMENT_POSTING_REQUIRED");
   jdbc.update("UPDATE platform.payment_order SET state='SUBMITTING',callback_hash=?,updated_at=clock_timestamp() WHERE id=?",hash(token),id);event(bankId,id,actor.subject(),"DISPATCH_STARTED",Map.of());changed(actor,bank,"PAYMENT_DISPATCH_STARTED",id);return value;});
  if(input==null)return; // Never repeat a network submission after an ambiguous response or process crash.
  Acceptance response=null;try{response=gateway.transfer(bankId,id,input,token);}catch(Exception ignored){}final Acceptance accepted=response;
  tx.executeWithoutResult(t->{var bank=tenants.get(bankId,true);var payment=row(actor,bankId,id);if(!payment.get("state").equals("SUBMITTING"))return;
   if(accepted==null)jdbc.update("UPDATE platform.payment_order SET state='UNKNOWN',updated_at=clock_timestamp() WHERE id=?",id);
   else jdbc.update("UPDATE platform.payment_order SET state='SUBMITTED',conversation_id=?,originator_id=?,updated_at=clock_timestamp() WHERE id=?",accepted.conversationId(),accepted.originatorId(),id);
   event(bankId,id,actor.subject(),accepted==null?"OUTCOME_UNKNOWN":"PROVIDER_ACCEPTED",accepted==null?Map.of():accepted);changed(actor,bank,"PAYMENT_PROVIDER_OBSERVED",id);
  });
 }
 public void query(Actor actor,UUID bankId,UUID id){
  PaymentAccess.require(jdbc,actor,bankId,PaymentAccess.Capability.MAKE);String token=token();String originator=tx.execute(t->{var bank=tenants.get(bankId,true);var payment=row(actor,bankId,id);
   if(!List.of("SUBMITTING","SUBMITTED","UNKNOWN").contains(payment.get("state"))||payment.get("originator_id")==null)throw error("PAYMENT_QUERY_UNAVAILABLE");
   if(!gateway.configured(bankId)||!payment.get("provider_profile").equals(gateway.profile(bankId)))throw error("PAYMENT_SANDBOX_NOT_CONFIGURED");
   if(jdbc.queryForObject("SELECT query_at>clock_timestamp()-interval '1 minute' FROM platform.payment_order WHERE id=?",Boolean.class,id)==Boolean.TRUE)throw error("PAYMENT_QUERY_WAIT");
   jdbc.update("UPDATE platform.payment_order SET query_hash=?,query_at=clock_timestamp() WHERE id=?",hash(token),id);event(bankId,id,actor.subject(),"STATUS_QUERY_STARTED",Map.of());return payment.get("originator_id").toString();});
  try{gateway.query(bankId,id,originator,token);}catch(Exception e){throw error("PAYMENT_PROVIDER_UNCONFIRMED");}
 }
 public void callback(UUID id,String token,String raw){
  if(token==null||!token.matches("[0-9a-f]{64}")||raw.length()>16384)throw error("PAYMENT_CALLBACK_INVALID");
  tx.executeWithoutResult(t->{var found=jdbc.queryForList("SELECT tenant_id FROM platform.payment_order WHERE id=?",UUID.class,id);if(found.isEmpty())throw error("PAYMENT_CALLBACK_INVALID");UUID bankId=found.getFirst();var bank=tenants.get(bankId,true);
   if(!gateway.configured(bankId))throw error("PAYMENT_CALLBACK_INVALID");var rows=jdbc.queryForList("SELECT callback_hash,query_hash FROM platform.payment_order WHERE id=?",id);var row=rows.getFirst();String hashed=hash(token);boolean match=List.of("callback_hash","query_hash").stream().anyMatch(k->row.get(k)!=null&&MessageDigest.isEqual(hashed.getBytes(StandardCharsets.UTF_8),row.get(k).toString().getBytes(StandardCharsets.UTF_8)));if(!match)throw error("PAYMENT_CALLBACK_INVALID");
   Callback observation=parseCallback(raw);String payload=JSON.writeValueAsString(observation),fingerprint=hash(payload);
   if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.payment_observation WHERE payment_id=? AND fingerprint=?)",Boolean.class,id,fingerprint))return;
   if(jdbc.queryForObject("SELECT count(*) FROM platform.payment_observation WHERE payment_id=?",Integer.class,id)>=100)throw error("PAYMENT_CALLBACK_LIMIT");
   jdbc.update("INSERT INTO platform.payment_observation(payment_id,fingerprint,payload) VALUES (?,?,?::jsonb)",id,fingerprint,payload);event(bankId,id,"provider-callback","UNVERIFIED_CALLBACK",observation);
   if(observation.code().equals("0")&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.payment_resolution r JOIN platform.payment_order p ON p.id=r.payment_id WHERE r.payment_id=? AND (r.outcome='REFUND' OR (lower(r.provider_reference)<>lower(?) AND ?<>'') OR (?<>'' AND (p.payload->>'amount')::numeric<>nullif(?,'')::numeric)))",Boolean.class,id,observation.receipt(),observation.receipt(),observation.amount(),observation.amount()))jdbc.update("UPDATE platform.payment_order SET reconciliation_conflict=true WHERE id=?",id);
   // Observations never settle, refund, change balances or establish final provider success.
   tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
  });
 }
 static Callback parseCallback(String raw){
  try{var n=JSON.readTree(raw).path("Result");String code=n.path("ResultCode").asText(),conversation=n.path("ConversationID").asText(),originator=n.path("OriginatorConversationID").asText(),receipt=n.path("TransactionID").asText();
   if(!code.matches("-?[0-9]{1,8}")||!DarajaSandboxGateway.validIdentifier(conversation)||!DarajaSandboxGateway.validIdentifier(originator)||(!receipt.isEmpty()&&!DarajaSandboxGateway.validIdentifier(receipt)))throw error("PAYMENT_CALLBACK_INVALID");
   String amount="";for(var p:n.path("ResultParameters").path("ResultParameter"))if(p.path("Key").asText().equals("TransactionAmount"))amount=p.path("Value").asText();
   if(!amount.isEmpty()&&!amount.matches("[0-9]{1,18}(\\.[0-9]{1,6})?"))throw error("PAYMENT_CALLBACK_INVALID");return new Callback(conversation,originator,code,receipt,amount);
  }catch(Exception e){throw error("PAYMENT_CALLBACK_INVALID");}
 }
 public UUID statement(Actor actor,UUID bankId,UUID key,Statement input){
  PaymentAccess.require(jdbc,actor,bankId,PaymentAccess.Capability.MAKE);return tx.execute(t->{jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->{},actor.issuer()+":"+actor.subject()+":"+key);var bank=tenants.get(bankId,true);PaymentAccess.require(jdbc,actor,bankId,PaymentAccess.Capability.MAKE);var payment=row(actor,bankId,input.paymentId());
   var old=jdbc.queryForList("SELECT id,tenant_id,payload=?::jsonb AS matches FROM platform.payment_statement WHERE issuer=? AND subject=? AND idempotency_key=?",JSON.writeValueAsString(input),actor.issuer(),actor.subject(),key);if(!old.isEmpty()){var previous=old.getFirst();if(!bankId.equals(previous.get("tenant_id"))||!Boolean.TRUE.equals(previous.get("matches")))throw error("IDEMPOTENCY_CONFLICT");return (UUID)previous.get("id");}
   if(bank.version()!=input.expectedVersion())throw error("STALE_VERSION");if(List.of("SETTLED","REFUND_PENDING","REFUNDED","RETURN_PENDING","RETURNED").contains(payment.get("state")))throw error("PAYMENT_ALREADY_RESOLVED");
   if(input.settlementDate().isBefore(order(payment).businessDate())||input.settlementDate().isAfter(LocalDate.now(java.time.ZoneOffset.UTC)))throw error("PAYMENT_STATEMENT_INVALID");
   UUID id=UUID.randomUUID();jdbc.update("INSERT INTO platform.payment_statement(id,tenant_id,payment_id,issuer,subject,idempotency_key,payload) VALUES (?,?,?,?,?,?,?::jsonb)",id,bankId,input.paymentId(),actor.issuer(),actor.subject(),key,JSON.writeValueAsString(input));event(bankId,input.paymentId(),actor.subject(),"STATEMENT_RECORDED",Map.of("statementId",id,"evidenceReference",input.evidenceReference()));changed(actor,bank,"PAYMENT_STATEMENT_RECORDED",input.paymentId());return id;
  });
 }
 private Map<String,Object> statementRow(UUID bank,UUID id){var rows=jdbc.queryForList("SELECT * FROM platform.payment_statement WHERE tenant_id=? AND id=?",bank,id);if(rows.isEmpty())throw error("PAYMENT_STATEMENT_INVALID");return rows.getFirst();}
 public Statement validate(Actor actor,Tenant bank,Reconcile input){
  PaymentAccess.require(jdbc,actor,bank.id(),PaymentAccess.Capability.READ);if(bank.version()!=input.expectedVersion())throw error("STALE_VERSION");var payment=row(actor,bank.id(),input.paymentId());
  if(List.of("SETTLED","REFUND_PENDING","REFUNDED","RETURN_PENDING","RETURNED").contains(payment.get("state")))throw error("PAYMENT_ALREADY_RESOLVED");
  var statement=JSON.readValue(statementRow(bank.id(),input.statementId()).get("payload").toString(),Statement.class);var order=order(payment);
  if(!statement.paymentId().equals(input.paymentId())||!statement.amount().equals(order.amount())||!statement.beneficiary().equals(order.beneficiary())||!statement.currency().equals("KES")||(input.resolution()==Resolution.MATCH)!=(statement.outcome()==Outcome.PAID))throw error("PAYMENT_RECONCILIATION_MISMATCH");
  if(!jdbc.queryForObject("SELECT completed FROM platform.account_operation WHERE request_id=?",Boolean.class,input.paymentId()))throw error("PAYMENT_POSTING_REQUIRED");
  if(payment.get("state").equals("SUBMITTING"))throw error("PAYMENT_SUBMISSION_IN_PROGRESS");
  if(input.resolution()==Resolution.MATCH&&payment.get("state").equals("APPROVED"))throw error("PAYMENT_NOT_SUBMITTED");
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.payment_resolution WHERE tenant_id=? AND lower(provider_reference)=lower(?))",Boolean.class,bank.id(),statement.providerReference()))throw error("PAYMENT_RECEIPT_USED");
  if(input.resolution()==Resolution.REFUND){
   if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.payment_observation WHERE payment_id=? AND payload->>'code'='0')",Boolean.class,input.paymentId()))throw error("PAYMENT_CONFLICTING_EVIDENCE");
   operations.validatePayment(bank,refund(bank,input),UUID.randomUUID());
  }
  return statement;
 }
 public OperationModels.Change refund(Tenant bank,Reconcile input){var rows=jdbc.queryForList("SELECT payload FROM platform.payment_order WHERE tenant_id=? AND id=?",String.class,bank.id(),input.paymentId());if(rows.isEmpty())throw error("PAYMENT_NOT_FOUND");var order=JSON.readValue(rows.getFirst(),Order.class);return new OperationModels.Change(input.expectedVersion(),OperationModels.Kind.WITHDRAWAL_REVERSAL,order.accountId(),null,null,input.paymentId(),order.amount(),reference.read(bank).businessDate(),"MPESA-REFUND-"+input.paymentId(),input.reason());}
 public Map<String,Object> values(Actor actor,Tenant bank,Reconcile input){var statement=validate(actor,bank,input);return Map.of("paymentId",input.paymentId(),"resolution",input.resolution(),"statementId",input.statementId(),"statement",statement,"environment","SANDBOX");}
 public void recordResolution(Actor actor,Tenant bank,Reconcile input,UUID request){if(input.resolution()==Resolution.REFUND)branches.record(actor,bank.id(),refund(bank,input),request);}
 public void reconcile(Actor actor,UUID bankId,Reconcile input,UUID request,String maker){
  PaymentAccess.require(jdbc,actor,bankId,PaymentAccess.Capability.APPROVE);var bank=tenants.get(bankId,true);var statement=validate(actor,bank,input);if(actor.subject().equals(statementRow(bankId,input.statementId()).get("subject")))throw error("PAYMENT_INDEPENDENT_EVIDENCE_REQUIRED");
  if(input.resolution()==Resolution.REFUND)operations.enqueueSettlementReturn(actor,bankId,refund(bank,input),request,maker);
  jdbc.update("INSERT INTO platform.payment_resolution(payment_id,tenant_id,statement_id,approval_id,outcome,provider_reference) VALUES (?,?,?,?,?,?)",input.paymentId(),bankId,input.statementId(),request,input.resolution().name(),statement.providerReference());
  jdbc.update("UPDATE platform.payment_order SET state=?,refund_id=?,updated_at=clock_timestamp() WHERE id=?",input.resolution()==Resolution.MATCH?"SETTLED":"REFUND_PENDING",input.resolution()==Resolution.REFUND?request:null,input.paymentId());event(bankId,input.paymentId(),actor.subject(),"RECONCILIATION_APPROVED",Map.of("approvalId",request,"statementId",input.statementId(),"resolution",input.resolution()));if(input.resolution()==Resolution.MATCH)changed(actor,bank,"PAYMENT_SETTLED",input.paymentId());
 }
 public void refreshRefund(Actor actor,UUID bankId,UUID id){PaymentAccess.require(jdbc,actor,bankId,PaymentAccess.Capability.MAKE);tx.executeWithoutResult(t->{var bank=tenants.get(bankId,true);var payment=row(actor,bankId,id);if(payment.get("state").equals("SUBMITTING")){
    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT updated_at<clock_timestamp()-interval '2 minutes' FROM platform.payment_order WHERE id=?",Boolean.class,id))){jdbc.update("UPDATE platform.payment_order SET state='UNKNOWN',updated_at=clock_timestamp() WHERE id=?",id);event(bankId,id,actor.subject(),"INTERRUPTED_DISPATCH_UNKNOWN",Map.of());changed(actor,bank,"PAYMENT_OUTCOME_UNKNOWN",id);}return;
   }if(!payment.get("state").equals("REFUND_PENDING"))return;if(jdbc.queryForObject("SELECT completed FROM platform.account_operation WHERE request_id=?",Boolean.class,payment.get("refund_id"))){jdbc.update("UPDATE platform.payment_order SET state='REFUNDED',updated_at=clock_timestamp() WHERE id=?",id);event(bankId,id,actor.subject(),"REFUND_POSTED",Map.of());changed(actor,bank,"PAYMENT_REFUNDED",id);}});}
 private static TenantException error(String code){return new TenantException(409,code,"Payment requires attention");}
}
