package com.cbs.credit;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/admin/tenants/{bank}/credit", "/api/v1/banks/{bank}/lending-operations/credit"})
@ConditionalOnProperty(name = "cbs.tenancy.enabled", havingValue = "true", matchIfMissing = true)
public class CreditController {
    private final CreditService service;
    private final ApprovalService approvals;
    private final CreditBureauService bureau;

    public CreditController(CreditService service, ApprovalService approvals, CreditBureauService bureau) {
        this.service = service;
        this.approvals = approvals;
        this.bureau = bureau;
    }

    @GetMapping
    public Map<String, Object> list(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestParam(defaultValue = "25") int limit, @RequestParam(defaultValue = "0") int offset) {
        return service.list(actor(jwt), bank, limit, offset);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id) {
        return service.detail(actor(jwt), bank, id);
    }

    @PostMapping
    public Map<String, Object> create(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody CreditModels.Application input) {
        return service.create(actor(jwt), bank, key, input);
    }

    @PostMapping("/policy")
    public ApprovalModels.Request policy(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody CreditModels.Policy input) {
        return approvals.creditPolicy(actor(jwt), key, bank, input);
    }

    @PostMapping("/reviews")
    public ApprovalModels.Request review(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody CreditModels.Review input) {
        return approvals.creditReview(actor(jwt), key, bank, input);
    }

    @PostMapping("/release")
    public ApprovalModels.Request release(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody CreditModels.Release input) {
        return approvals.creditRelease(actor(jwt), key, bank, input);
    }

    @PostMapping("/{applicationId}/documents/verify")
    public Map<String, Object> verifyDocument(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID applicationId, @RequestParam UUID documentId, @RequestParam String securityReference) {
        return service.verifyCollateralDocument(actor(jwt), bank, applicationId, documentId, securityReference);
    }

    @PostMapping("/bureau/inquire")
    public CreditBureauModels.InquiryResponse inquireBureau(JwtAuthenticationToken jwt, @PathVariable UUID bank, @Valid @RequestBody CreditBureauModels.InquiryRequest input) {
        return bureau.inquire(actor(jwt), bank, input);
    }

    @GetMapping("/bureau/{customerId}")
    public List<CreditBureauModels.InquiryResponse> listBureauInquiries(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID customerId) {
        return bureau.listInquiries(actor(jwt), bank, customerId);
    }

    @GetMapping("/bureau/report")
    public CreditBureauModels.CrbReport generateBureauReport(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestParam(required = false) LocalDate asOf) {
        return bureau.generateReport(actor(jwt), bank, asOf);
    }
}
