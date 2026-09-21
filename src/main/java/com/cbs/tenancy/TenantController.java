package com.cbs.tenancy;

import static com.cbs.tenancy.TenantModels.*;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "cbs.tenancy.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1")
public class TenantController {
    private final TenantService service;
    private final com.cbs.access.AccessService identities;
    public TenantController(TenantService service,com.cbs.access.AccessService identities) { this.service = service; this.identities=identities; }

    @PostMapping("/admin/tenants")
    ResponseEntity<Tenant> register(JwtAuthenticationToken identity,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody RegisterRequest request) {
        Registration result = service.register(actor(identity), key, request);
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .location(URI.create("/api/v1/admin/tenants/" + result.tenant().id()))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(result.tenant());
    }

    @GetMapping("/admin/tenants")
    Page<Tenant> list(JwtAuthenticationToken identity, @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "0") int offset) { return service.list(actor(identity), limit, offset); }

    @GetMapping("/admin/tenants/{id}")
    Tenant get(JwtAuthenticationToken identity, @PathVariable UUID id) { return service.get(actor(identity), id); }

    @PatchMapping("/admin/tenants/{id}")
    Tenant update(JwtAuthenticationToken identity, @PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return service.update(actor(identity), id, request);
    }

    @PostMapping("/admin/tenants/{id}/legal-entities")
    Tenant addLegalEntity(JwtAuthenticationToken identity, @PathVariable UUID id,
            @Valid @RequestBody AddLegalEntityRequest request) { return service.addLegalEntity(actor(identity), id, request); }

    @PostMapping("/admin/tenants/{id}/provision")
    Tenant provision(JwtAuthenticationToken identity, @PathVariable UUID id, @Valid @RequestBody ChangeRequest request) {
        return service.provision(actor(identity), id, request);
    }

    @PostMapping("/admin/tenants/{id}/suspend")
    Tenant suspend(JwtAuthenticationToken identity, @PathVariable UUID id, @Valid @RequestBody ChangeRequest request) {
        return service.suspend(actor(identity), id, request);
    }

    @PostMapping("/admin/tenants/{id}/resume")
    Tenant resume(JwtAuthenticationToken identity, @PathVariable UUID id, @Valid @RequestBody ChangeRequest request) {
        return service.resume(actor(identity), id, request);
    }

    @PutMapping("/admin/tenants/{id}/memberships")
    Tenant setMembership(JwtAuthenticationToken identity, @PathVariable UUID id, @Valid @RequestBody MembershipRequest request) {
        if(request.active()) { identities.requireAssignable(identity.getToken().getIssuer().toString(),request.subject()); }
        return service.setMembership(actor(identity), id, request);
    }

    @GetMapping("/admin/tenants/{id}/memberships")
    Page<Membership> memberships(JwtAuthenticationToken identity, @PathVariable UUID id,
            @RequestParam(defaultValue = "25") int limit, @RequestParam(defaultValue = "0") int offset) {
        return service.memberships(actor(identity), id, limit, offset);
    }

    @GetMapping("/admin/tenants/{id}/audit")
    Page<AuditEvent> audit(JwtAuthenticationToken identity, @PathVariable UUID id,
            @RequestParam(defaultValue = "25") int limit, @RequestParam(defaultValue = "0") int offset) {
        return service.auditEvents(actor(identity), id, limit, offset);
    }

    @GetMapping("/banks/{id}")
    BankContext inspect(JwtAuthenticationToken identity, @PathVariable UUID id) {
        return service.bankContext(actor(identity), id, false);
    }

    @GetMapping("/banks/{id}/operational-context")
    BankContext operationalContext(JwtAuthenticationToken identity, @PathVariable UUID id) {
        return service.bankContext(actor(identity), id, true);
    }

    private static Actor actor(JwtAuthenticationToken identity) {
        return new Actor(identity.getToken().getIssuer().toString(), identity.getToken().getSubject(),
                identity.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN")),
                UUID.fromString(MDC.get("correlationId")));
    }
}
