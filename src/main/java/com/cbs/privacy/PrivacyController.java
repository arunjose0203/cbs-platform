package com.cbs.privacy;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.privacy.PrivacyModels.*;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** Privacy and data protection (Round 18): legal holds, data-subject requests, retention and the customer view log. */
@RestController
@RequestMapping("/api/v1/admin/tenants/{bank}/privacy")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class PrivacyController {
    private final PrivacyService privacy; private final ApprovalService approvals;
    public PrivacyController(PrivacyService privacy, ApprovalService approvals) { this.privacy = privacy; this.approvals = approvals; }

    @GetMapping("/legal-holds") List<HoldView> holds(JwtAuthenticationToken jwt, @PathVariable UUID bank) { return privacy.holds(actor(jwt), bank); }
    @PostMapping("/legal-holds") @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request hold(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Hold input) {
        return approvals.legalHold(actor(jwt), key, bank, input);
    }

    @GetMapping("/requests") List<SubjectRequestView> requests(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestParam(required=false) RequestStatus status,
            @RequestParam(defaultValue="false") boolean overdue, @RequestParam(required=false) UUID customerId) {
        return privacy.requests(actor(jwt), bank, status, overdue, customerId);
    }
    @PostMapping("/requests") @ResponseStatus(HttpStatus.CREATED)
    SubjectRequestView register(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody SubjectRequest input) {
        return privacy.register(actor(jwt), bank, key, input);
    }
    @PostMapping("/requests/{id}/close") SubjectRequestView close(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id, @Valid @RequestBody Close input) {
        return privacy.close(actor(jwt), bank, id, input);
    }
    /** The subject-access report as a JSON download; never cached. */
    @GetMapping("/requests/{id}/report") ResponseEntity<Map<String, Object>> report(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Content-Disposition", "attachment; filename=\"subject-access-" + id + ".json\"").body(privacy.report(actor(jwt), bank, id));
    }

    @GetMapping("/retention") List<RetentionRule> retention(JwtAuthenticationToken jwt, @PathVariable UUID bank) { com.cbs.access.AccessService.admin(actor(jwt)); return privacy.matrix(); }
    @GetMapping("/retention/candidates") List<RetentionCandidate> candidates(JwtAuthenticationToken jwt, @PathVariable UUID bank) { return privacy.candidates(actor(jwt), bank); }
    @GetMapping("/customers/{customerId}/erasure") ErasureAssessment erasure(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID customerId) {
        return privacy.assess(actor(jwt), bank, customerId);
    }
    @GetMapping("/customers/{customerId}/views") List<View> views(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID customerId,
            @RequestParam(defaultValue="50") int limit) {
        return privacy.views(actor(jwt), bank, customerId, limit);
    }
}
