package com.cbs.access;

import static com.cbs.access.AccessModels.*;
import static com.cbs.access.RequestIdentity.actor;
import com.cbs.tenancy.TenantModels.ChangeRequest;
import com.cbs.tenancy.TenantModels.Page;
import jakarta.servlet.http.HttpServletRequest;
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
public class AccessController {
    private final AccessService service;
    public AccessController(AccessService service) { this.service=service; }
    @GetMapping("/access/me") Me me(HttpServletRequest request) { return (Me)request.getAttribute(AccessPolicyFilter.ME); }
    @GetMapping("/access/sessions") List<Session> sessions(HttpServletRequest request) { return service.sessions(me(request)); }
    @DeleteMapping("/access/sessions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeOwn(JwtAuthenticationToken identity,HttpServletRequest request,@PathVariable UUID id) {
        service.revokeOwn(actor(identity),me(request),id);
    }
    @PostMapping("/admin/staff") @ResponseStatus(HttpStatus.CREATED)
    Staff create(JwtAuthenticationToken identity,@Valid @RequestBody StaffInput input) { return service.create(actor(identity),input); }
    @GetMapping("/admin/staff") Page<Staff> list(JwtAuthenticationToken identity,
            @RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset) { return service.list(actor(identity),limit,offset); }
    @PatchMapping("/admin/staff/{id}") Staff update(JwtAuthenticationToken identity,@PathVariable UUID id,@Valid @RequestBody StaffUpdate input) {
        return service.update(actor(identity),id,input);
    }
    @PostMapping("/admin/staff/{id}/revoke-sessions") Staff revoke(JwtAuthenticationToken identity,@PathVariable UUID id,@Valid @RequestBody ChangeRequest input) {
        return service.revokeSessions(actor(identity),id,input);
    }
    @GetMapping("/admin/identity-audit") Page<IdentityAudit> audit(JwtAuthenticationToken identity,
            @RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset) { return service.audit(actor(identity),limit,offset); }
}
