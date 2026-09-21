package com.cbs.access;

import static com.cbs.access.AccessModels.*;
import static com.cbs.access.RequestIdentity.actor;
import com.cbs.tenancy.TenantModels.Page;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class BranchController {
    private final BranchService service;
    public BranchController(BranchService service) { this.service=service; }
    @PostMapping("/admin/tenants/{tenantId}/branches") @ResponseStatus(HttpStatus.CREATED)
    Branch create(JwtAuthenticationToken identity,@PathVariable UUID tenantId,@Valid @RequestBody BranchInput input) {
        return service.create(actor(identity),tenantId,input);
    }
    @GetMapping("/admin/tenants/{tenantId}/branches") Page<Branch> list(JwtAuthenticationToken identity,@PathVariable UUID tenantId,
            @RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset) { return service.list(actor(identity),tenantId,limit,offset); }
    @PatchMapping("/admin/tenants/{tenantId}/branches/{id}") Branch update(JwtAuthenticationToken identity,@PathVariable UUID tenantId,
            @PathVariable UUID id,@Valid @RequestBody BranchUpdate input) { return service.update(actor(identity),tenantId,id,input); }
    @PutMapping("/admin/tenants/{tenantId}/branches/{id}/memberships") Branch grant(JwtAuthenticationToken identity,@PathVariable UUID tenantId,
            @PathVariable UUID id,@Valid @RequestBody BranchGrant input) { return service.grant(actor(identity),tenantId,id,input); }
    @GetMapping("/admin/tenants/{tenantId}/branches/{id}/memberships") List<Grant> grants(JwtAuthenticationToken identity,@PathVariable UUID tenantId,
            @PathVariable UUID id) { return service.grants(actor(identity),tenantId,id); }
    @GetMapping("/banks/{tenantId}/branches/{id}/access") BranchAccess check(JwtAuthenticationToken identity,@PathVariable UUID tenantId,
            @PathVariable UUID id,@RequestParam(defaultValue="READ_BRANCH") Permission permission) { return service.check(actor(identity),tenantId,id,permission); }
}
