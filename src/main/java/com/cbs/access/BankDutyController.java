package com.cbs.access;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.ApprovalModels;
import com.cbs.approvals.ApprovalService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** Bank duty grants, the enforcement switch and periodic access reviews for one bank. */
@RestController
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class BankDutyController {
    private static final String BASE="/api/v1/admin/tenants/{bank}/bank-duties";
    private final BankDuties duties;
    private final ApprovalService approvals;
    public BankDutyController(BankDuties duties, ApprovalService approvals) { this.duties=duties; this.approvals=approvals; }

    @GetMapping(BASE)
    public Map<String,Object> report(JwtAuthenticationToken jwt, @PathVariable UUID bank) {
        return duties.report(actor(jwt),bank);
    }

    @PostMapping(BASE)
    public ApprovalModels.Request grant(JwtAuthenticationToken jwt, @PathVariable UUID bank,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody BankDuties.Grant input) {
        return approvals.bankDuty(actor(jwt),key,bank,input);
    }

    @PostMapping(BASE+"/policy")
    public ApprovalModels.Request policy(JwtAuthenticationToken jwt, @PathVariable UUID bank,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody BankDuties.Policy input) {
        return approvals.bankDutyPolicy(actor(jwt),key,bank,input);
    }

    @PostMapping(BASE+"/reviews")
    public Map<String,Object> attest(JwtAuthenticationToken jwt, @PathVariable UUID bank, @Valid @RequestBody BankDuties.Review input) {
        return duties.attest(actor(jwt),bank,input);
    }
}
