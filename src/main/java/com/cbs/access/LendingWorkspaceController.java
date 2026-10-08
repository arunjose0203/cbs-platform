package com.cbs.access;
import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import com.cbs.tenancy.TenantModels.Page;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class LendingWorkspaceController {
 private static final String BASE="/api/v1/banks/{bank}/lending-operations";
 private final LendingAccess lending;private final ApprovalService approvals;private final JdbcTemplate jdbc;
 public LendingWorkspaceController(LendingAccess lending,ApprovalService approvals,JdbcTemplate jdbc){this.lending=lending;this.approvals=approvals;this.jdbc=jdbc;}
 @GetMapping("/api/v1/banks/lending-operations/banks") public Map<String,Object> banks(JwtAuthenticationToken jwt){return Map.of("items",lending.banks(actor(jwt)));}
 @GetMapping(BASE) public Map<String,Object> context(JwtAuthenticationToken jwt,@PathVariable UUID bank){return lending.context(actor(jwt),bank);}
 @GetMapping(BASE+"/approvals") public Page<ApprovalModels.Request> inbox(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return approvals.lendingInbox(actor(jwt),bank,limit,offset);}
 @GetMapping(BASE+"/approvals/{id}") public ApprovalModels.Details detail(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){approvals.lendingRequestBank(actor(jwt),bank,id);return approvals.details(actor(jwt),id);}
 @PostMapping(BASE+"/approvals/{id}/{decision}") public ApprovalModels.Request decide(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id,@PathVariable ApprovalModels.Decision decision,@Valid @RequestBody ApprovalModels.DecisionInput input){approvals.lendingRequestBank(actor(jwt),bank,id);return approvals.decide(actor(jwt),id,decision,input.reason());}
 @GetMapping("/api/v1/admin/tenants/{bank}/lending-access") public Map<String,Object> report(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return lending.report(actor(jwt),bank,limit,offset);}
 @PostMapping("/api/v1/admin/tenants/{bank}/lending-access") public ApprovalModels.Request grant(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody LendingAccess.Grant input){return approvals.lendingGrant(actor(jwt),key,bank,input);}
 @GetMapping("/api/v1/admin/tenants/{bank}/lending-access/branches") public List<Map<String,Object>> branches(JwtAuthenticationToken jwt,@PathVariable UUID bank){AccessService.admin(actor(jwt));return jdbc.queryForList("SELECT id,code,display_name AS name,active FROM platform.branch WHERE tenant_id=? ORDER BY code,id",bank);}
}
