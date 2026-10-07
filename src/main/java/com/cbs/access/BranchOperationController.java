package com.cbs.access;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.accounts.OperationModels;
import com.cbs.approvals.*;
import com.cbs.tenancy.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class BranchOperationController {
 private final BranchOperations scope;private final BranchService branches;private final ApprovalService approvals;private final JdbcTemplate jdbc;private final TenantRepository tenants;
 public BranchOperationController(BranchOperations scope,BranchService branches,ApprovalService approvals,JdbcTemplate jdbc,TenantRepository tenants){this.scope=scope;this.branches=branches;this.approvals=approvals;this.jdbc=jdbc;this.tenants=tenants;}
 @GetMapping("/banks/operations/branches") List<Map<String,Object>> directory(JwtAuthenticationToken jwt){
  var who=actor(jwt);
  return jdbc.queryForList("SELECT b.tenant_id AS bank_id,b.id AS branch_id,b.display_name AS branch_name,t.display_name AS bank_name,CASE WHEN tm.role='BANK_AUDITOR' THEN 'AUDITOR' ELSE m.role END AS role FROM platform.branch_membership m JOIN platform.staff_identity s ON s.id=m.staff_id JOIN platform.branch b ON b.id=m.branch_id AND b.tenant_id=m.tenant_id JOIN platform.tenant t ON t.id=b.tenant_id JOIN platform.tenant_membership tm ON tm.tenant_id=t.id AND tm.actor_issuer=s.issuer AND tm.actor_subject=s.subject WHERE s.issuer=? AND s.subject=? AND s.active AND m.active AND b.active AND t.status='ACTIVE' AND tm.active ORDER BY t.display_name,b.code LIMIT 100",who.issuer(),who.subject());
 }
 @PostMapping("/admin/tenants/{bankId}/account-branches") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 ApprovalModels.Request assign(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody BranchOperations.Assignment input){return approvals.assignment(actor(jwt),key,bankId,input);}
 @PostMapping("/admin/tenants/{bankId}/branch-limits") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 ApprovalModels.Request limit(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody BranchOperations.Limit input){return approvals.branchLimit(actor(jwt),key,bankId,input);}
 @GetMapping("/admin/tenants/{bankId}/branch-limits") Map<String,Object> limits(JwtAuthenticationToken jwt,@PathVariable UUID bankId){
  AccessService.admin(actor(jwt));var bank=tenants.get(bankId,false);
  return Map.of("version",bank.version(),"items",jdbc.queryForList("SELECT branch_id,scope_id,currency,operation,per_transaction::text,daily_total::text FROM platform.branch_transaction_limit WHERE tenant_id=? ORDER BY branch_id,scope_id,currency,operation",bankId));
 }
 @GetMapping("/banks/{bankId}/branches/{branchId}/operations/accounts") Map<String,Object> accounts(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){
  branches.check(actor(jwt),bankId,branchId,AccessModels.Permission.READ_BRANCH);AccessService.page(limit,offset);
  return Map.of("version",tenants.get(bankId,false).version(),"businessDate",jdbc.queryForObject("SELECT business_date FROM platform.bank_settings WHERE tenant_id=?",java.time.LocalDate.class,bankId),"items",jdbc.queryForList("SELECT a.id,a.account_number,a.currency,a.state,a.delivery FROM platform.account_registry a JOIN platform.account_branch b ON b.account_id=a.id AND b.tenant_id=a.tenant_id WHERE b.tenant_id=? AND b.branch_id=? ORDER BY a.account_number LIMIT ? OFFSET ?",bankId,branchId,limit,offset),"limit",limit,"offset",offset);
 }
 @PostMapping("/banks/{bankId}/branches/{branchId}/operations/requests") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 ApprovalModels.Request submit(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody OperationModels.Change input){
  branches.check(actor(jwt),bankId,branchId,AccessModels.Permission.CASH_TRANSACT);
  if(!branchId.equals(scope.branch(bankId,input.accountId())))throw denied();
  return approvals.branchOperation(actor(jwt),key,bankId,branchId,input);
 }
 @GetMapping("/banks/{bankId}/branches/{branchId}/operations/requests") Map<String,Object> requests(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){
  branches.check(actor(jwt),bankId,branchId,AccessModels.Permission.READ_BRANCH);AccessService.page(limit,offset);
  return Map.of("items",jdbc.queryForList("SELECT r.id,r.status,r.target_label,r.maker_subject,r.created_at FROM platform.approval_request r JOIN platform.operation_scope s ON s.request_id=r.id WHERE s.tenant_id=? AND s.branch_id=? AND r.issuer=? ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?",bankId,branchId,actor(jwt).issuer(),limit,offset),"limit",limit,"offset",offset);
 }
 @GetMapping("/banks/{bankId}/branches/{branchId}/operations/requests/{id}") ApprovalModels.Details detail(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId,@PathVariable UUID id){requireScope(bankId,branchId,id);return approvals.details(actor(jwt),id);}
 @PostMapping("/banks/{bankId}/branches/{branchId}/operations/requests/{id}/{decision}") ApprovalModels.Request decide(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId,@PathVariable UUID id,@PathVariable String decision,@Valid @RequestBody ApprovalModels.DecisionInput input){
  requireScope(bankId,branchId,id);
  var action=switch(decision){case "approve"->ApprovalModels.Decision.APPROVE;case "reject"->ApprovalModels.Decision.REJECT;case "cancel"->ApprovalModels.Decision.CANCEL;default->throw new TenantException(400,"INVALID_REQUEST","Unknown decision");};
  return approvals.decide(actor(jwt),id,action,input.reason());
 }
 private void requireScope(UUID bank,UUID branch,UUID id){var s=scope.scope(id);if(s==null||!s.bankId().equals(bank)||!s.branchId().equals(branch))throw denied();}
 private static TenantException denied(){return new TenantException(403,"BRANCH_ACCESS_DENIED","The request does not belong to this branch");}
}
