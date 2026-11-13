package com.cbs.migration;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.migration.MigrationModels.*;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bank}/migration")
@ConditionalOnProperty(name = "cbs.tenancy.enabled", havingValue = "true", matchIfMissing = true)
public class MigrationController {

    private final MigrationService service;

    public MigrationController(MigrationService service) {
        this.service = service;
    }

    @PostMapping("/customers/validate")
    public ValidationReport validateCustomers(@PathVariable UUID bank, @Valid @RequestBody List<CustomerRecord> records) {
        return service.validateCustomers(bank, records);
    }

    @PostMapping("/customers/stage")
    public ValidationReport stageCustomers(JwtAuthenticationToken jwt, @PathVariable UUID bank, @Valid @RequestBody List<CustomerRecord> records) {
        return service.stageCustomers(actor(jwt), bank, records);
    }

    @PostMapping("/customers/commit/{batchId}")
    public Map<String, Object> commitCustomers(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID batchId) {
        int committed = service.commitCustomers(actor(jwt), bank, batchId);
        return Map.of("batchId", batchId, "committedRecords", committed, "status", "COMMITTED");
    }

    @PostMapping("/accounts/validate")
    public ValidationReport validateAccounts(@PathVariable UUID bank, @Valid @RequestBody List<AccountRecord> records) {
        return service.validateAccounts(bank, records);
    }

    @PostMapping("/accounts/stage")
    public ValidationReport stageAccounts(JwtAuthenticationToken jwt, @PathVariable UUID bank, @Valid @RequestBody List<AccountRecord> records) {
        return service.stageAccounts(actor(jwt), bank, records);
    }

    @PostMapping("/reconcile")
    public TrialBalanceReconciliation reconcile(JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestBody Map<String, BigDecimal> legacyBalances) {
        return service.reconcileTrialBalance(actor(jwt), bank, legacyBalances);
    }

    @GetMapping("/batches")
    public List<BatchSummary> listBatches(JwtAuthenticationToken jwt, @PathVariable UUID bank) {
        return service.listBatches(actor(jwt), bank);
    }

    @PostMapping("/batches/{batchId}/rollback")
    public Map<String, Object> rollback(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID batchId) {
        service.rollbackBatch(actor(jwt), bank, batchId);
        return Map.of("batchId", batchId, "status", "ROLLED_BACK");
    }
}
