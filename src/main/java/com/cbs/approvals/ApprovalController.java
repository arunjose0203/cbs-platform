package com.cbs.approvals;

import static com.cbs.approvals.ApprovalModels.*;
import static com.cbs.access.RequestIdentity.actor;
import com.cbs.tenancy.TenantModels.Page;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/approvals")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ApprovalController {
    private final ApprovalService service;
    public ApprovalController(ApprovalService service) { this.service=service; }
    @GetMapping Page<Request> list(JwtAuthenticationToken identity,
            @RequestParam(defaultValue="INBOX") Scope scope, @RequestParam(required=false) Action action,
            @RequestParam(required=false) UUID bankId, @RequestParam(defaultValue="25") int limit,
            @RequestParam(defaultValue="0") int offset) { return service.list(actor(identity),scope,action,bankId,limit,offset); }
    @GetMapping("/{id}") Details details(JwtAuthenticationToken identity,@PathVariable UUID id) { return service.details(actor(identity),id); }
    @PostMapping("/{id}/approve") Request approve(JwtAuthenticationToken identity,@PathVariable UUID id,@Valid @RequestBody DecisionInput input) {
        return service.decide(actor(identity),id,Decision.APPROVE,input.reason());
    }
    @PostMapping("/{id}/reject") Request reject(JwtAuthenticationToken identity,@PathVariable UUID id,@Valid @RequestBody DecisionInput input) {
        return service.decide(actor(identity),id,Decision.REJECT,input.reason());
    }
    @PostMapping("/{id}/cancel") Request cancel(JwtAuthenticationToken identity,@PathVariable UUID id,@Valid @RequestBody DecisionInput input) {
        return service.decide(actor(identity),id,Decision.CANCEL,input.reason());
    }
}
