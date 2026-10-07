package com.cbs.cash;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.access.AccessModels.Permission;
import com.cbs.access.BranchService;
import com.cbs.approvals.*;
import com.cbs.tenancy.TenantException;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class CashController {
 private final CashService cash;private final ApprovalService approvals;private final BranchService branches;
 public CashController(CashService cash,ApprovalService approvals,BranchService branches){this.cash=cash;this.approvals=approvals;this.branches=branches;}
 @PostMapping("/admin/tenants/{bankId}/cash-locations") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 ApprovalModels.Request register(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody CashModels.Change input){
  if(input.kind()!=CashModels.Kind.REGISTER)throw CashModels.error("CASH_FIELDS_INVALID");return approvals.cash(actor(jwt),key,bankId,input);
 }
 @GetMapping("/banks/{bankId}/branches/{branchId}/operations/cash")
 Map<String,Object> dashboard(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return cash.dashboard(actor(jwt),bankId,branchId,limit,offset);}
 @PostMapping("/banks/{bankId}/branches/{branchId}/operations/cash") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 ApprovalModels.Request submit(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody CashModels.Change input){
  if(!branchId.equals(input.branchId())||input.kind()==CashModels.Kind.REGISTER)throw new TenantException(403,"BRANCH_ACCESS_DENIED","Use the matching branch route");
  return approvals.cash(actor(jwt),key,bankId,input);
 }
 @GetMapping("/banks/{bankId}/branches/{branchId}/operations/cash/{id}")
 ApprovalModels.Details detail(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId,@PathVariable UUID id){return scoped(jwt,bankId,branchId,id);}
 @PostMapping("/banks/{bankId}/branches/{branchId}/operations/cash/{id}/{decision}")
 ApprovalModels.Request decide(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId,@PathVariable UUID id,@PathVariable String decision,@Valid @RequestBody ApprovalModels.DecisionInput input){
  scoped(jwt,bankId,branchId,id);var action=switch(decision){case "approve"->ApprovalModels.Decision.APPROVE;case "reject"->ApprovalModels.Decision.REJECT;case "cancel"->ApprovalModels.Decision.CANCEL;default->throw CashModels.error("INVALID_REQUEST");};
  return approvals.decide(actor(jwt),id,action,input.reason());
 }
 @GetMapping("/banks/{bankId}/branches/{branchId}/operations/cash/transit")
 List<Map<String,Object>> transit(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId){return cash.transit(actor(jwt),bankId,branchId);}
 @GetMapping("/banks/{bankId}/branches/{branchId}/operations/cash/transit-destinations")
 List<Map<String,Object>> transitDestinations(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId){return cash.transitDestinations(actor(jwt),bankId,branchId);}
 @GetMapping("/banks/{bankId}/branches/{branchId}/operations/cash/reversible")
 List<Map<String,Object>> reversible(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId){return cash.reversible(actor(jwt),bankId,branchId);}
 @GetMapping("/banks/{bankId}/branches/{branchId}/operations/cash/adjustment-ledgers")
 List<Map<String,Object>> adjustmentLedgers(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId,@RequestParam UUID locationId){return cash.adjustmentLedgers(actor(jwt),bankId,branchId,locationId);}
 @GetMapping("/banks/{bankId}/branches/{branchId}/operations/cash/exceptions")
 List<Map<String,Object>> exceptions(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId){return cash.exceptions(actor(jwt),bankId,branchId);}
 @PostMapping("/banks/{bankId}/branches/{branchId}/operations/cash/exceptions") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
 Map<String,UUID> reportException(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody CashExceptionInput input){return Map.of("id",cash.reportException(actor(jwt),bankId,branchId,key,input));}
 @PostMapping("/banks/{bankId}/branches/{branchId}/operations/cash/exceptions/{id}/resolve")
 Map<String,UUID> resolveException(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID branchId,@PathVariable UUID id,@Valid @RequestBody CashExceptionInput input){return Map.of("id",cash.resolveException(actor(jwt),bankId,branchId,id,input));}
 private ApprovalModels.Details scoped(JwtAuthenticationToken jwt,UUID bank,UUID branch,UUID id){
  branches.check(actor(jwt),bank,branch,Permission.READ_BRANCH);var d=approvals.details(actor(jwt),id);
  if(d.request().action()!=ApprovalModels.Action.CASH_OPERATION||!bank.equals(d.request().bankId())||!branch.toString().equals(String.valueOf(d.request().proposedValues().get("branchId"))))throw new TenantException(403,"BRANCH_ACCESS_DENIED","Request does not belong to this branch");return d;
 }
}
