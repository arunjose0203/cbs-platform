package com.cbs.complaints;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.complaints.ComplaintModels.*;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** Complaints and disputes (Round 20): register, handling steps, approved resolutions and the register export. */
@RestController
@RequestMapping("/api/v1/admin/tenants/{bank}/complaints")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ComplaintController {
    private final ComplaintService complaints; private final ApprovalService approvals;
    public ComplaintController(ComplaintService complaints, ApprovalService approvals) { this.complaints = complaints; this.approvals = approvals; }

    @GetMapping Page list(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestParam(defaultValue="OPEN") Filter status,
            @RequestParam(required=false) Category category, @RequestParam(required=false) UUID customerId,
            @RequestParam(defaultValue="25") int limit, @RequestParam(defaultValue="0") int offset) {
        return complaints.list(actor(jwt), bank, status, category, customerId, limit, offset);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    ComplaintView register(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Register input) {
        return complaints.register(actor(jwt), bank, key, input);
    }
    @GetMapping("/summary") Summary summary(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return complaints.summary(actor(jwt), bank, from, to);
    }
    /** The register as a CSV download; never cached. */
    @GetMapping("/register") ResponseEntity<byte[]> registerCsv(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        byte[] csv = complaints.registerCsv(actor(jwt), bank, from, to).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Content-Type", "text/csv;charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"complaints-register-" + from + "-" + to + ".csv\"").body(csv);
    }
    @GetMapping("/{id}") ResponseEntity<Detail> detail(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(complaints.detail(actor(jwt), bank, id));
    }

    @PostMapping("/{id}/acknowledgement") ComplaintView acknowledge(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id, @Valid @RequestBody Step input) {
        return complaints.acknowledge(actor(jwt), bank, id, input);
    }
    @PostMapping("/{id}/assignment") ComplaintView assign(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id, @Valid @RequestBody Assign input) {
        return complaints.assign(actor(jwt), bank, id, input);
    }
    @PostMapping("/{id}/notes") ComplaintView note(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id, @Valid @RequestBody Step input) {
        return complaints.note(actor(jwt), bank, id, input);
    }
    @PostMapping("/{id}/escalation") ComplaintView escalate(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id, @Valid @RequestBody Step input) {
        return complaints.escalate(actor(jwt), bank, id, input);
    }
    @PostMapping("/{id}/reopening") ComplaintView reopen(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id, @Valid @RequestBody Step input) {
        return complaints.reopen(actor(jwt), bank, id, input);
    }
    @PostMapping("/{id}/redress-posting") ComplaintView redressPosting(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id, @Valid @RequestBody RedressPosting input) {
        return complaints.redressPosting(actor(jwt), bank, id, input);
    }
    /** Proposes the outcome; another administrator approves it in the Approval Inbox. */
    @PostMapping("/resolutions") @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request resolve(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Resolution input) {
        return approvals.complaintResolution(actor(jwt), key, bank, input);
    }
}
