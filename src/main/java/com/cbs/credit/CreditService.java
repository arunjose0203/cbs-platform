package com.cbs.credit;

import static com.cbs.credit.CreditModels.*;
import com.cbs.access.*;
import com.cbs.accounts.*;
import com.cbs.contracts.ContractModels;
import com.cbs.customers.CustomerService;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class CreditService {
 private final LendingAccess lending;
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final AccountService accounts;private final CustomerService customers;private final ReferenceDataService reference;private final AccessService access;private final TransactionTemplate tx;
 public CreditService(LendingAccess lending,JdbcTemplate jdbc,TenantRepository tenants,AccountService accounts,CustomerService customers,ReferenceDataService reference,AccessService access,PlatformTransactionManager manager){this.lending=lending;this.jdbc=jdbc;this.tenants=tenants;this.accounts=accounts;this.customers=customers;this.reference=reference;this.access=access;tx=new TransactionTemplate(manager);}
 private static TenantException error(String code){return new TenantException(409,code,"Credit application requires attention");}
 private void active(Tenant b,long version){if(b.version()!=version)throw error("STALE_VERSION");if(b.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");}
 private LocalDate date(Tenant b){return reference.read(b).businessDate();}
 private Map<String,Object> row(UUID bank,UUID id){var rows=jdbc.queryForList("SELECT * FROM platform.credit_application WHERE tenant_id=? AND id=?",bank,id);if(rows.isEmpty())throw error("CREDIT_NOT_FOUND");return rows.getFirst();}
 private Application application(Map<String,Object> row){return JSON.readValue(row.get("payload").toString(),Application.class);}
 private Review review(Map<String,Object> row){if(row.get("review")==null)throw error("CREDIT_APPROVAL_REQUIRED");return JSON.readValue(row.get("review").toString(),Review.class);}
 private Policy policy(UUID bank,String currency){var rows=jdbc.queryForList("SELECT payload::text FROM platform.credit_policy WHERE tenant_id=? AND currency=?",String.class,bank,currency);if(rows.isEmpty())throw error("CREDIT_POLICY_REQUIRED");return JSON.readValue(rows.getFirst(),Policy.class);}
 private UUID policyId(UUID bank,String currency){return jdbc.queryForObject("SELECT approval_id FROM platform.credit_policy WHERE tenant_id=? AND currency=?",UUID.class,bank,currency);}
 private static BigDecimal dec(String s){return new BigDecimal(s);}
 private static void amount(String value,String currency,boolean positive){try{var n=dec(value);if(n.signum()<0||positive&&n.signum()==0||n.scale()>Currency.getInstance(currency).getDefaultFractionDigits())throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw error("CREDIT_AMOUNT_INVALID");}}
 private static void document(Document d,LocalDate day){if(d==null||d.expiresOn().isBefore(day))throw error("CREDIT_EVIDENCE_EXPIRED");}
 private AccountModels.Definition account(Tenant b,UUID id){var x=accounts.current(b.id(),id);var a=x.definition();if(!x.delivery().equals("APPLIED")||a.state()!=AccountModels.State.ACTIVE)throw error("FUNDING_ACCOUNT_NOT_READY");accounts.requireMatching(b,a);customers.requireEligible(b,a.customerId());return a;}
 private long customerVersion(UUID bank,UUID id){return jdbc.queryForObject("SELECT version FROM platform.customer_registry WHERE tenant_id=? AND id=?",Long.class,bank,id);}
 private void validateApplication(Tenant b,Application a){
  active(b,a.expectedVersion());var account=account(b,a.accountId());String currency=account.terms().currency();amount(a.principal(),currency,true);amount(a.monthlyIncome(),currency,true);amount(a.monthlyExpenses(),currency,false);amount(a.monthlyDebtPayments(),currency,false);amount(a.existingDebt(),currency,false);
  if(a.dayBasis()!=360&&a.dayBasis()!=365||dec(a.annualRatePercent()).compareTo(new BigDecimal("999"))>0)throw error("CONTRACT_TERMS_INVALID");
  Set<String> refs=new HashSet<>();for(var d:a.documents()){document(d,date(b));if(!refs.add(d.reference()))throw error("CREDIT_DUPLICATE_EVIDENCE");}
  for(var s:a.securities()){amount(s.value(),currency,true);document(s.evidence(),date(b));if(s.valuedOn().isAfter(date(b))||!refs.add(s.reference()))throw error("CREDIT_SECURITY_INVALID");}
  try{assess(a,date(b),Currency.getInstance(currency).getDefaultFractionDigits());}catch(IllegalArgumentException e){throw error("CONTRACT_TERMS_INVALID");}
 }
 public Map<String,Object> create(Actor actor,UUID bank,UUID key,Application input){return tx.execute(t->{
  tenants.get(bank,true);lending.role(actor,bank,input.accountId(),LendingAccess.Role.CREDIT_OFFICER);
  access.requireAssignable(actor.issuer(),actor.subject());jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->{},actor.issuer()+":"+actor.subject()+":"+key);
  var old=jdbc.queryForList("SELECT id,tenant_id,payload=?::jsonb AS same FROM platform.credit_application WHERE issuer=? AND created_by=? AND idempotency_key=?",JSON.writeValueAsString(input),actor.issuer(),actor.subject(),key);
  var b=tenants.get(bank,true);if(!old.isEmpty()){var r=old.getFirst();if(!bank.equals(r.get("tenant_id"))||!Boolean.TRUE.equals(r.get("same")))throw error("IDEMPOTENCY_CONFLICT");return details(b,(UUID)r.get("id"));}
  validateApplication(b,input);if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.credit_application WHERE tenant_id=? AND reference=?) OR EXISTS(SELECT 1 FROM platform.contract WHERE tenant_id=? AND agreement_reference=?)",Boolean.class,bank,input.reference(),bank,input.reference()))throw error("CONTRACT_REFERENCE_IN_USE");
  var a=account(b,input.accountId());UUID id=UUID.randomUUID();jdbc.update("INSERT INTO platform.credit_application(id,tenant_id,account_id,customer_id,customer_version,reference,currency,payload,issuer,created_by,idempotency_key) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?)",id,bank,a.id(),a.customerId(),customerVersion(bank,a.customerId()),input.reference(),a.terms().currency(),JSON.writeValueAsString(input),actor.issuer(),actor.subject(),key);
  event(actor,b,id,null,"APPLICATION_CREATED",input);changed(actor,b,"CREDIT_APPLICATION_CREATED",id,input.reason());return details(tenants.get(bank,false),id);
 });}
 private Map<String,Object> details(Tenant b,UUID id){var r=row(b.id(),id);var a=application(r);var out=new LinkedHashMap<String,Object>();out.put("id",id);out.put("version",b.version());out.put("businessDate",date(b));out.put("status",r.get("status"));out.put("application",a);out.put("currency",r.get("currency"));out.put("createdBy",r.get("created_by"));out.put("review",r.get("review")==null?null:review(r));out.put("assessment",assess(a,date(b),Currency.getInstance(r.get("currency").toString()).getDefaultFractionDigits()));out.put("events",jdbc.query("SELECT kind,subject,payload,created_at FROM platform.credit_event WHERE application_id=? ORDER BY sequence",(rs,n)->Map.of("kind",rs.getString(1),"subject",rs.getString(2),"evidence",JSON.readTree(rs.getString(3)),"at",rs.getTimestamp(4).toInstant()),id));out.put("contractId",jdbc.queryForList("SELECT contract_id FROM platform.credit_reservation WHERE application_id=?",UUID.class,id).stream().findFirst().orElse(null));return out;}
 public Map<String,Object> detail(Actor actor,UUID bank,UUID id){return tx.execute(t->{var b=tenants.get(bank,true);lending.read(actor,bank,application(row(bank,id)).accountId());var result=details(b,id);tenants.audit(actor,bank,"CREDIT_EVIDENCE_VIEWED",b.version(),b.status(),b.status(),"Credit dossier viewed",null,null,Map.of("applicationId",id));return result;});}
 public Map<String,Object> list(Actor actor,UUID bank,int limit,int offset){AccessService.page(limit,offset);return tx.execute(t->{var b=tenants.get(bank,true);String filter=lending.filter(actor,bank,"account_id");return Map.of("version",b.version(),"businessDate",date(b),"items",jdbc.query("SELECT id,reference,currency,status,payload,created_by FROM platform.credit_application WHERE tenant_id=?"+filter+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(r,n)->Map.of("id",r.getObject(1,UUID.class),"reference",r.getString(2),"currency",r.getString(3),"status",r.getString(4),"application",JSON.readValue(r.getString(5),Application.class),"createdBy",r.getString(6)),bank,limit,offset),"total",jdbc.queryForObject("SELECT count(*) FROM platform.credit_application WHERE tenant_id=?"+filter,Long.class,bank),"policies",jdbc.query("SELECT payload FROM platform.credit_policy WHERE tenant_id=? ORDER BY currency",(r,n)->JSON.readValue(r.getString(1),Policy.class),bank));});}
 public void validatePolicy(Actor actor,Tenant b,Policy p){active(b,p.expectedVersion());amount(p.maximumPrincipal(),p.currency(),true);amount(p.minimumMonthlySurplus(),p.currency(),false);if(dec(p.maximumDebtServicePercent()).signum()<=0||dec(p.maximumDebtServicePercent()).compareTo(new BigDecimal("100"))>0||dec(p.minimumCollateralPercent()).signum()<0)throw error("CREDIT_POLICY_INVALID");access.requireAssignable(actor.issuer(),p.approverSubject());}
 public void policy(Actor actor,UUID bank,Policy p,UUID approval){var b=tenants.get(bank,true);validatePolicy(actor,b,p);jdbc.update("INSERT INTO platform.credit_policy(tenant_id,currency,payload,approval_id,issuer) VALUES (?,?,?::jsonb,?,?) ON CONFLICT(tenant_id,currency) DO UPDATE SET payload=excluded.payload,approval_id=excluded.approval_id,issuer=excluded.issuer",bank,p.currency(),JSON.writeValueAsString(p),approval,actor.issuer());changed(actor,b,"CREDIT_POLICY_APPROVED",approval,p.reason());}
 private void unreserved(UUID id){if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.credit_reservation WHERE application_id=?)",Boolean.class,id))throw error("CREDIT_ALREADY_USED");}
 private Assessment eligibility(Tenant b,Map<String,Object> r,Review review){
  var a=application(r);var account=account(b,a.accountId());if(customerVersion(b.id(),account.customerId())!=((Number)r.get("customer_version")).longValue())throw error("CREDIT_CUSTOMER_CHANGED");
  var p=policy(b.id(),r.get("currency").toString());var day=date(b);if(review.validUntil().isBefore(day))throw error("CREDIT_APPROVAL_EXPIRED");document(review.assessment(),day);for(var d:a.documents())document(d,day);
  for(var s:a.securities()){document(s.evidence(),day);if(s.valuedOn().isBefore(day.minusDays(p.maximumValuationAgeDays())))throw error("CREDIT_VALUATION_EXPIRED");}
  var result=assess(a,day,Currency.getInstance(r.get("currency").toString()).getDefaultFractionDigits());
  if(dec(a.principal()).compareTo(dec(p.maximumPrincipal()))>0||review.riskGrade()>p.maximumRiskGrade()||dec(result.debtServicePercent()).compareTo(dec(p.maximumDebtServicePercent()))>0||dec(result.monthlySurplus()).compareTo(dec(p.minimumMonthlySurplus()))<0||dec(result.collateralPercent()).compareTo(dec(p.minimumCollateralPercent()))<0)throw error("CREDIT_POLICY_FAILED");
  return result;
 }
 public Map<String,Object> validateReview(Actor actor,Tenant b,Review input,boolean approving){active(b,input.expectedVersion());var r=row(b.id(),input.applicationId());if(input.decision()==Decision.APPROVE){if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.credit_reservation r JOIN platform.contract_delivery d ON d.contract_id=r.contract_id WHERE r.application_id=? AND d.kind='OPEN' AND d.completed)",Boolean.class,input.applicationId()))throw error("CREDIT_ALREADY_USED");}else unreserved(input.applicationId());if(!actor.issuer().equals(r.get("issuer")))throw error("CREDIT_NOT_FOUND");
  if(input.decision()==Decision.REVOKE&&!Set.of("READY","APPROVED").contains(r.get("status")))throw error("CREDIT_STATE_INVALID");if(input.decision()!=Decision.REVOKE&&!Set.of("SUBMITTED","APPROVED","READY").contains(r.get("status")))throw error("CREDIT_STATE_INVALID");
  if(approving){var p=policy(b.id(),r.get("currency").toString());lending.role(actor,b.id(),application(r).accountId(),LendingAccess.Role.CREDIT_APPROVER);if(actor.subject().equals(r.get("created_by")))throw error("CREDIT_REVIEWER_REQUIRED");}
  var out=new LinkedHashMap<String,Object>();out.put("review",input);out.put("application",application(r));if(input.decision()==Decision.APPROVE)out.put("calculation",eligibility(b,r,input));return out;
 }
 public void review(Actor actor,UUID bank,Review input,UUID approval){var b=tenants.get(bank,true);validateReview(actor,b,input,true);var r=row(bank,input.applicationId());String state=input.decision()==Decision.APPROVE?(input.conditions().isEmpty()?"READY":"APPROVED"):input.decision()==Decision.DECLINE?"DECLINED":"REVOKED";
  jdbc.update("UPDATE platform.credit_application SET status=?,review=?::jsonb,release=NULL,policy_id=?,reviewer=? WHERE id=?",state,JSON.writeValueAsString(input),policyId(bank,r.get("currency").toString()),actor.subject(),input.applicationId());event(actor,b,input.applicationId(),approval,"CREDIT_"+input.decision(),input);changed(actor,b,"CREDIT_REVIEW_APPROVED",input.applicationId(),input.reason());}
 public Map<String,Object> validateRelease(Actor actor,Tenant b,Release input,boolean approving){active(b,input.expectedVersion());var r=row(b.id(),input.applicationId());if(!actor.issuer().equals(r.get("issuer")))throw error("CREDIT_NOT_FOUND");if(!r.get("status").equals("APPROVED"))throw error("CREDIT_STATE_INVALID");var v=review(r);eligibility(b,r,v);if(!policyId(b.id(),r.get("currency").toString()).equals(r.get("policy_id")))throw error("CREDIT_POLICY_CHANGED");if(input.conditionEvidence().size()!=v.conditions().size())throw error("CREDIT_CONDITIONS_REQUIRED");Set<String> refs=new HashSet<>();for(var d:input.conditionEvidence()){document(d,date(b));if(!refs.add(d.reference()))throw error("CREDIT_DUPLICATE_EVIDENCE");}if(approving){lending.role(actor,b.id(),application(r).accountId(),LendingAccess.Role.CREDIT_APPROVER);if(actor.subject().equals(r.get("created_by")))throw error("CREDIT_REVIEWER_REQUIRED");}return Map.of("release",input,"conditions",v.conditions());}
 public void release(Actor actor,UUID bank,Release input,UUID approval){var b=tenants.get(bank,true);validateRelease(actor,b,input,true);jdbc.update("UPDATE platform.credit_application SET status='READY',release=?::jsonb WHERE id=?",JSON.writeValueAsString(input),input.applicationId());event(actor,b,input.applicationId(),approval,"CONDITIONS_VERIFIED",input);changed(actor,b,"CREDIT_CONDITIONS_APPROVED",input.applicationId(),input.reason());}
 /** Called while the tenant lock is held at preview, approval and before first financial delivery. */
 public UUID requireDisbursement(Tenant b,ContractModels.Open terms,UUID contractId){
  var rows=jdbc.queryForList("SELECT id FROM platform.credit_application WHERE tenant_id=? AND reference=?",UUID.class,b.id(),terms.agreementReference());if(rows.isEmpty())throw error("CREDIT_APPROVAL_REQUIRED");UUID id=rows.getFirst();var r=row(b.id(),id);var a=application(r);
  if(!r.get("status").equals("READY"))throw error("CREDIT_CONDITIONS_REQUIRED");var v=review(r);eligibility(b,r,v);if(!policyId(b.id(),r.get("currency").toString()).equals(r.get("policy_id")))throw error("CREDIT_POLICY_CHANGED");
  if(!a.accountId().equals(terms.accountId())||dec(a.principal()).compareTo(dec(terms.principal()))!=0||dec(a.annualRatePercent()).compareTo(dec(terms.annualRatePercent()))!=0||a.dayBasis()!=terms.dayBasis()||a.instalments()!=terms.instalments())throw error("CREDIT_TERMS_MISMATCH");
  if(!v.conditions().isEmpty()){if(r.get("release")==null)throw error("CREDIT_CONDITIONS_REQUIRED");var release=JSON.readValue(r.get("release").toString(),Release.class);for(var d:release.conditionEvidence())document(d,date(b));}
  for(var security:a.securities())if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.credit_reservation r JOIN platform.credit_application a ON a.id=r.application_id JOIN platform.contract c ON c.id=r.contract_id CROSS JOIN LATERAL jsonb_array_elements(a.payload->'securities') s WHERE r.tenant_id=? AND r.contract_id<>? AND c.status IN ('ACTIVE','AWAITING_POSTING','WRITTEN_OFF') AND s->>'reference'=?)",Boolean.class,b.id(),contractId,security.reference()))throw error("CREDIT_SECURITY_ALREADY_PLEDGED");
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.credit_reservation WHERE application_id=? AND contract_id<>?)",Boolean.class,id,contractId))throw error("CREDIT_ALREADY_USED");return id;
 }
 public void reserve(Tenant bank,ContractModels.Open terms,UUID contract){UUID id=requireDisbursement(bank,terms,contract);jdbc.update("INSERT INTO platform.credit_reservation(application_id,contract_id,tenant_id) VALUES (?,?,?)",id,contract,bank.id());}
 public void requireReviewer(Actor actor,Tenant b,String currency,BigDecimal exposure){var p=policy(b.id(),currency);access.requireAssignable(actor.issuer(),actor.subject());lending.role(actor,b.id(),null,LendingAccess.Role.CREDIT_APPROVER);if(exposure.compareTo(dec(p.maximumPrincipal()))>0)throw error("CREDIT_REVIEWER_REQUIRED");}
 private void event(Actor actor,Tenant b,UUID id,UUID approval,String kind,Object payload){jdbc.update("INSERT INTO platform.credit_event(application_id,tenant_id,approval_id,kind,subject,payload) VALUES (?,?,?,?,?,?::jsonb)",id,b.id(),approval,kind,actor.subject(),JSON.writeValueAsString(payload));}
 private void changed(Actor actor,Tenant b,String kind,UUID id,String reason){tenants.change(b,b.status(),b.displayName(),b.defaultLanguage());tenants.audit(actor,b.id(),kind,b.version()+1,b.status(),b.status(),reason,null,null,Map.of("creditId",id));}
}
