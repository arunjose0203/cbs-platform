package com.cbs.compliance;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.compliance.AutomationModels.*;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** Screening lists and hits, transaction monitoring rules and alerts, and decisions on both. */
@RestController
@RequestMapping("/api/v1/admin/tenants/{bank}/compliance")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ComplianceAutomationController {
    private final ScreeningService screening;
    private final MonitoringService monitoring;
    private final ComplianceDecisions decisions;
    private final ApprovalService approvals;

    public ComplianceAutomationController(ScreeningService screening, MonitoringService monitoring, ComplianceDecisions decisions, ApprovalService approvals) {
        this.screening = screening; this.monitoring = monitoring; this.decisions = decisions; this.approvals = approvals;
    }

    @GetMapping("/screening/lists")
    public List<Map<String, Object>> lists(JwtAuthenticationToken jwt, @PathVariable UUID bank) { return screening.lists(actor(jwt), bank); }

    @PostMapping("/screening/lists") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(JwtAuthenticationToken jwt, @PathVariable UUID bank, @Valid @RequestBody Upload input) { return screening.upload(actor(jwt), bank, input); }

    @GetMapping("/screening/lists/{list}/entries")
    public Map<String, Object> entries(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID list,
            @RequestParam(defaultValue="50") int limit, @RequestParam(defaultValue="0") int offset) { return screening.entries(actor(jwt), bank, list, limit, offset); }

    @PostMapping("/screening/activations") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApprovalModels.Request activate(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Activation input) { return approvals.screeningList(actor(jwt), key, bank, input); }

    @GetMapping("/screening/hits")
    public Map<String, Object> hits(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestParam(defaultValue="OPEN") String status,
            @RequestParam(defaultValue="25") int limit, @RequestParam(defaultValue="0") int offset) { return screening.hits(actor(jwt), bank, status, limit, offset); }

    @GetMapping("/screening/hits/{id}")
    public Map<String, Object> hit(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id) { return screening.hit(actor(jwt), bank, id); }

    @PostMapping("/screening/hits/{id}/escalate")
    public Map<String, Object> escalateHit(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id, @Valid @RequestBody Escalate input) {
        return decisions.escalate(actor(jwt), bank, ItemType.SCREENING_HIT, id, input);
    }

    @GetMapping("/monitoring/policies")
    public List<Map<String, Object>> policies(JwtAuthenticationToken jwt, @PathVariable UUID bank) { return monitoring.policies(actor(jwt), bank); }

    @PostMapping("/monitoring/policies") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApprovalModels.Request policy(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Policy input) { return approvals.monitoringPolicy(actor(jwt), key, bank, input); }

    @GetMapping("/monitoring/alerts")
    public Map<String, Object> alerts(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestParam(defaultValue="OPEN") String status,
            @RequestParam(defaultValue="25") int limit, @RequestParam(defaultValue="0") int offset) { return monitoring.alerts(actor(jwt), bank, status, limit, offset); }

    @GetMapping("/monitoring/alerts/{id}")
    public Map<String, Object> alert(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id) { return monitoring.alert(actor(jwt), bank, id); }

    @PostMapping("/monitoring/alerts/{id}/escalate")
    public Map<String, Object> escalateAlert(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id, @Valid @RequestBody Escalate input) {
        return decisions.escalate(actor(jwt), bank, ItemType.MONITORING_ALERT, id, input);
    }

    @PostMapping("/dismissals") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApprovalModels.Request dismiss(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Dismiss input) { return approvals.complianceDismiss(actor(jwt), key, bank, input); }
}
