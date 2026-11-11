package com.cbs.access;

import com.cbs.accounts.OperationModels.Change;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import static com.cbs.access.AccessModels.Permission.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class BranchOperations {
 public record Assignment(@PositiveOrZero long expectedVersion,@NotNull UUID accountId,@NotNull UUID branchId,@NotBlank @Size(max=500) String reason) {}
 public record Limit(@PositiveOrZero long expectedVersion,@NotNull UUID branchId,UUID staffId,
  @NotNull @Pattern(regexp="[A-Z]{3}") String currency,
  @NotNull @Pattern(regexp="TRANSFER|WITHDRAWAL|FUNDING_REVERSAL|HOLD|CASH_RECEIVE|CASH_DISPATCH|CASH_TRANSFER|CASH_DEPOSIT|CASH_WITHDRAWAL|CASH_SHORTAGE|CASH_OVERAGE|CASH_REVERSAL|WITHDRAWAL_REVERSAL|TRANSFER_REVERSAL") String operation,
  @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String perTransaction,
  @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String dailyTotal,@NotBlank @Size(max=500) String reason) {}
 public record Scope(UUID bankId,UUID branchId,UUID staffId) {}
 private final JdbcTemplate jdbc;private final BranchService branches;private final AccessService access;private final TenantRepository tenants;
 public BranchOperations(JdbcTemplate jdbc,BranchService branches,AccessService access,TenantRepository tenants){this.jdbc=jdbc;this.branches=branches;this.access=access;this.tenants=tenants;}
 public UUID branch(UUID bank,UUID account){var rows=jdbc.queryForList("SELECT branch_id FROM platform.account_branch WHERE tenant_id=? AND account_id=?",UUID.class,bank,account);return rows.isEmpty()?null:rows.getFirst();}
 public Scope authorize(Actor actor,UUID bank,Change change,boolean review){
  // Final settlement before closure stays an administrator workflow, like the closure itself.
  if(change.kind()==com.cbs.accounts.OperationModels.Kind.CLOSURE_SETTLEMENT){AccessService.admin(actor);return null;}
  UUID branch=branch(bank,change.accountId());
  if(branch==null){AccessService.admin(actor);return null;}
  branches.check(actor,bank,branch,review?APPROVE_BRANCH:CASH_TRANSACT);
  if(change.destinationId()!=null&&!branch.equals(branch(bank,change.destinationId())))throw error(403,"TRANSFER_BRANCH_MISMATCH");
  return new Scope(bank,branch,access.requireAssignable(actor.issuer(),actor.subject()).id());
 }
 public void record(Actor actor,UUID bank,Change change,UUID request){
  var scope=authorize(actor,bank,change,false);if(scope==null)return;
  check(scope,change);
  jdbc.update("INSERT INTO platform.operation_scope(request_id,tenant_id,branch_id,staff_id) VALUES (?,?,?,?)",request,bank,scope.branchId(),scope.staffId());
 }
 public Scope scope(UUID request){var rows=jdbc.query("SELECT tenant_id,branch_id,staff_id FROM platform.operation_scope WHERE request_id=?",(r,n)->new Scope(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class)),request);return rows.isEmpty()?null:rows.getFirst();}
 public void readRequest(Actor actor,UUID request){var scope=scope(request);if(scope==null)AccessService.admin(actor);else branches.check(actor,scope.bankId(),scope.branchId(),READ_BRANCH);}
 public void review(Actor actor,UUID request,boolean cancel){var scope=scope(request);if(scope==null)AccessService.admin(actor);else branches.check(actor,scope.bankId(),scope.branchId(),cancel?CASH_TRANSACT:APPROVE_BRANCH);}
 public void approve(Actor reviewer,UUID bank,Change change,UUID request,String maker){
  var saved=scope(request);var current=authorize(reviewer,bank,change,true);
  if(saved==null){if(current!=null)throw error(409,"STALE_VERSION");return;}
  var owner=new Actor(reviewer.issuer(),maker,false,reviewer.correlationId());var makerScope=authorize(owner,bank,change,false);
  if(!saved.equals(makerScope)||current==null||!saved.branchId().equals(current.branchId()))throw error(409,"STALE_VERSION");
  check(saved,change);
 }
 private void check(Scope scope,Change change){
  if(change.kind()==com.cbs.accounts.OperationModels.Kind.RELEASE)return;
  String currency=jdbc.queryForObject("SELECT currency FROM platform.account_registry WHERE tenant_id=? AND id=?",String.class,scope.bankId(),change.accountId());
  for(UUID scopeId:List.of(scope.branchId(),scope.staffId())){
   var policies=jdbc.query("SELECT per_transaction,daily_total FROM platform.branch_transaction_limit WHERE tenant_id=? AND branch_id=? AND scope_id=? AND currency=? AND operation=?",(r,n)->new BigDecimal[]{r.getBigDecimal(1),r.getBigDecimal(2)},scope.bankId(),scope.branchId(),scopeId,currency,change.kind().name());
   if(policies.isEmpty())throw error(409,"BRANCH_LIMIT_REQUIRED");
   String staff=scopeId.equals(scope.staffId())?" AND s.staff_id=?":"";
   var args=new ArrayList<Object>(List.of(scope.bankId(),scope.branchId(),currency,change.kind().name(),change.businessDate().toString()));if(!staff.isEmpty())args.add(scope.staffId());
   BigDecimal used=jdbc.queryForObject("SELECT coalesce(sum((o.payload->'change'->>'amount')::numeric),0) FROM platform.account_operation o JOIN platform.operation_scope s ON s.request_id=o.request_id WHERE s.tenant_id=? AND s.branch_id=? AND o.payload->'account'->'terms'->>'currency'=? AND o.payload->'change'->>'kind'=? AND o.payload->'change'->>'businessDate'=?"+staff,BigDecimal.class,args.toArray());
   TransactionLimits.enforce(policies.getFirst()[0],policies.getFirst()[1],used,new BigDecimal(change.amount()));
  }
 }
 public void validate(Tenant bank,Assignment change){
  if(bank.version()!=change.expectedVersion())throw error(409,"STALE_VERSION");
  if(bank.status()!=Status.ACTIVE)throw error(409,"ACCOUNT_BANK_NOT_ACTIVE");
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_registry a JOIN platform.account_delivery d ON d.request_id=a.request_id JOIN platform.branch b ON b.tenant_id=a.tenant_id AND b.id=? WHERE a.id=? AND a.tenant_id=? AND a.delivery='APPLIED' AND a.state<>'CLOSED' AND b.active AND d.payload->>'legalEntityId'=b.legal_entity_id::text)",Boolean.class,change.branchId(),change.accountId(),bank.id())))throw error(409,"ACCOUNT_BRANCH_INVALID");
  com.cbs.accounts.OperationService.requireIdle(jdbc,change.accountId());
  if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.funding_delivery WHERE account_id=? AND NOT completed)",Boolean.class,change.accountId())))throw error(409,"ACCOUNT_DELIVERY_PENDING");
 }
 public Map<String,Object> values(Assignment change){return Map.of("accountId",change.accountId(),"branchId",change.branchId());}
 public void apply(Actor actor,UUID bankId,Assignment change,String reason){
  AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,change);
  jdbc.update("INSERT INTO platform.account_branch(tenant_id,account_id,branch_id) VALUES (?,?,?) ON CONFLICT(account_id) DO UPDATE SET branch_id=excluded.branch_id",bankId,change.accountId(),change.branchId());audit(actor,bank,"ACCOUNT_BRANCH_ASSIGNED",reason,values(change));
 }
 public void validate(Tenant bank,Limit change){
  TransactionLimits.validate(bank,new TransactionLimits.Change(change.expectedVersion(),change.currency(),change.operation(),change.perTransaction(),change.dailyTotal(),change.reason()));
  if(change.operation().equals("FUNDING"))throw error(400,"LIMIT_INVALID");
  if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.branch WHERE tenant_id=? AND id=? AND active)",Boolean.class,bank.id(),change.branchId()))throw error(409,"BRANCH_NOT_OPERATIONAL");
  if(change.staffId()!=null&&!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.branch_membership m JOIN platform.staff_identity s ON s.id=m.staff_id WHERE m.tenant_id=? AND m.branch_id=? AND m.staff_id=? AND m.active AND s.active AND m.role IN ('TELLER','BRANCH_MANAGER'))",Boolean.class,bank.id(),change.branchId(),change.staffId()))throw error(409,"BRANCH_ACCESS_DENIED");
 }
 public Map<String,Object> values(Limit change){var m=new LinkedHashMap<String,Object>(TransactionLimits.values(new TransactionLimits.Change(change.expectedVersion(),change.currency(),change.operation(),change.perTransaction(),change.dailyTotal(),change.reason())));m.put("branchId",change.branchId());m.put("scope",change.staffId()==null?"BRANCH":"STAFF");if(change.staffId()!=null)m.put("staffId",change.staffId());return m;}
 public Map<String,Object> before(UUID bank,Limit change){var rows=jdbc.query("SELECT per_transaction,daily_total FROM platform.branch_transaction_limit WHERE tenant_id=? AND branch_id=? AND scope_id=? AND currency=? AND operation=?",(r,n)->Map.<String,Object>of("perTransaction",r.getBigDecimal(1).toPlainString(),"dailyTotal",r.getBigDecimal(2).toPlainString()),bank,change.branchId(),change.staffId()==null?change.branchId():change.staffId(),change.currency(),change.operation());return rows.isEmpty()?Map.of():rows.getFirst();}
 public void apply(Actor actor,UUID bankId,Limit change,String reason){
  AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,change);
  if(change.staffId()!=null&&!access.staff(change.staffId(),false).issuer().equals(actor.issuer()))throw error(403,"BRANCH_ACCESS_DENIED");
  jdbc.update("INSERT INTO platform.branch_transaction_limit(tenant_id,branch_id,scope_id,currency,operation,per_transaction,daily_total) VALUES (?,?,?,?,?,?,?) ON CONFLICT(branch_id,scope_id,currency,operation) DO UPDATE SET per_transaction=excluded.per_transaction,daily_total=excluded.daily_total",bankId,change.branchId(),change.staffId()==null?change.branchId():change.staffId(),change.currency(),change.operation(),new BigDecimal(change.perTransaction()),new BigDecimal(change.dailyTotal()));audit(actor,bank,"BRANCH_LIMIT_CHANGED",reason,values(change));
 }
 private void audit(Actor actor,Tenant bank,String action,String reason,Map<String,Object> values){tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(actor,bank.id(),action,bank.version()+1,bank.status(),bank.status(),reason,null,null,values);}
 private static TenantException error(int status,String code){return new TenantException(status,code,"Branch operation is not permitted");}
}
