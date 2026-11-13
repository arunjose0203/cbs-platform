package com.cbs.migration;

import static com.cbs.migration.MigrationModels.*;
import com.cbs.access.AccessService;
import com.cbs.accounting.JournalModels;
import com.cbs.accounting.JournalService;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "cbs.tenancy.enabled", havingValue = "true", matchIfMissing = true)
public class MigrationService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final ReferenceDataService reference;
    private final JournalService journals;
    private final TransactionTemplate tx;

    public MigrationService(JdbcTemplate jdbc, TenantRepository tenants,
                            ReferenceDataService reference, JournalService journals,
                            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.reference = reference;
        this.journals = journals;
        this.tx = new TransactionTemplate(manager);
    }

    public ValidationReport validateCustomers(UUID bankId, List<CustomerRecord> records) {
        Set<String> seenIds = new HashSet<>();
        List<ValidationFinding> findings = new ArrayList<>();
        int validCount = 0;

        for (var rec : records) {
            boolean valid = true;
            if (rec.nationalId() == null || rec.nationalId().isBlank()) {
                findings.add(new ValidationFinding(rec.legacyId(), "nationalId", "NATIONAL_ID_REQUIRED", "National ID cannot be blank", "ERROR"));
                valid = false;
            } else if (!seenIds.add(rec.nationalId())) {
                findings.add(new ValidationFinding(rec.legacyId(), "nationalId", "DUPLICATE_IN_FILE", "National ID duplicated in import file: " + rec.nationalId(), "ERROR"));
                valid = false;
            } else {
                Boolean exists = jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM platform.customer_registry WHERE tenant_id = ? AND identity_number = ?)",
                        Boolean.class, bankId, rec.nationalId());
                if (Boolean.TRUE.equals(exists)) {
                    findings.add(new ValidationFinding(rec.legacyId(), "nationalId", "ALREADY_REGISTERED", "Customer national ID already registered in platform", "WARNING"));
                }
            }

            if (rec.fullName() == null || rec.fullName().isBlank()) {
                findings.add(new ValidationFinding(rec.legacyId(), "fullName", "NAME_REQUIRED", "Full name cannot be blank", "ERROR"));
                valid = false;
            }

            if (valid) validCount++;
        }

        return new ValidationReport(UUID.randomUUID(), BatchType.CUSTOMERS, records.size(), validCount, records.size() - validCount, findings);
    }

    public ValidationReport stageCustomers(Actor actor, UUID bankId, List<CustomerRecord> records) {
        AccessService.admin(actor);
        var report = validateCustomers(bankId, records);
        UUID batchId = report.batchId();

        tx.executeWithoutResult(t -> {
            jdbc.update("""
                INSERT INTO platform.migration_batch (id, tenant_id, batch_type, status, total_records, valid_records, error_records, summary, created_by)
                VALUES (?, ?, 'CUSTOMERS', 'STAGED', ?, ?, ?, ?::jsonb, ?)
                """, batchId, bankId, records.size(), report.validCount(), report.errorCount(),
                    JSON.writeValueAsString(report), actor.subject());

            for (var rec : records) {
                boolean isValid = report.findings().stream().noneMatch(f -> f.legacyId().equals(rec.legacyId()) && f.severity().equals("ERROR"));
                String errMsg = report.findings().stream().filter(f -> f.legacyId().equals(rec.legacyId())).map(ValidationFinding::message).findFirst().orElse(null);

                jdbc.update("""
                    INSERT INTO platform.migration_staged_item (id, batch_id, tenant_id, legacy_id, record_type, payload, valid, error_message)
                    VALUES (?, ?, ?, ?, 'CUSTOMER', ?::jsonb, ?, ?)
                    """, UUID.randomUUID(), batchId, bankId, rec.legacyId(), JSON.writeValueAsString(rec), isValid, errMsg);
            }
        });

        return report;
    }

    public int commitCustomers(Actor actor, UUID bankId, UUID batchId) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var batchRows = jdbc.queryForList("SELECT status FROM platform.migration_batch WHERE tenant_id = ? AND id = ?", String.class, bankId, batchId);
            if (batchRows.isEmpty()) throw error("BATCH_NOT_FOUND");
            if (!batchRows.getFirst().equals("STAGED")) throw error("BATCH_NOT_STAGED");

            var staged = jdbc.query("""
                SELECT id, payload::text FROM platform.migration_staged_item
                WHERE batch_id = ? AND valid AND NOT committed
                """, (rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class), JSON.readValue(rs.getString("payload"), CustomerRecord.class)), batchId);

            int committed = 0;
            for (var entry : staged) {
                CustomerRecord rec = entry.getValue();
                UUID customerId = UUID.randomUUID();
                jdbc.update("""
                    INSERT INTO platform.customer_registry
                    (id, tenant_id, legal_entity_id, kind, identity_number, display_name, status, delivery, created_by)
                    VALUES (?, ?, (SELECT id FROM platform.legal_entity WHERE tenant_id = ? LIMIT 1), 'INDIVIDUAL', ?, ?, 'ACTIVE', 'APPLIED', ?)
                    ON CONFLICT (tenant_id, identity_number) DO NOTHING
                    """, customerId, bankId, bankId, rec.nationalId(), rec.fullName(), actor.subject());

                jdbc.update("UPDATE platform.migration_staged_item SET committed = true WHERE id = ?", entry.getKey());
                committed++;
            }

            jdbc.update("UPDATE platform.migration_batch SET status = 'COMMITTED', committed_at = clock_timestamp() WHERE id = ?", batchId);
            return committed;
        });
    }

    public ValidationReport validateAccounts(UUID bankId, List<AccountRecord> records) {
        var bank = tenants.get(bankId, true);
        var settings = reference.read(bank);
        Set<String> enabledCurrencies = new HashSet<>();
        settings.currencies().forEach(c -> { if (c.enabled()) enabledCurrencies.add(c.code()); });

        Set<String> seenAccounts = new HashSet<>();
        List<ValidationFinding> findings = new ArrayList<>();
        int validCount = 0;

        for (var rec : records) {
            boolean valid = true;
            if (!enabledCurrencies.contains(rec.currency())) {
                findings.add(new ValidationFinding(rec.legacyAccountId(), "currency", "CURRENCY_DISABLED", "Currency not enabled: " + rec.currency(), "ERROR"));
                valid = false;
            }

            if (!seenAccounts.add(rec.accountNumber())) {
                findings.add(new ValidationFinding(rec.legacyAccountId(), "accountNumber", "DUPLICATE_ACCOUNT_NUMBER", "Duplicate account number in file: " + rec.accountNumber(), "ERROR"));
                valid = false;
            } else {
                Boolean exists = jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM platform.account WHERE tenant_id = ? AND payload->'terms'->>'accountNumber' = ?)",
                        Boolean.class, bankId, rec.accountNumber());
                if (Boolean.TRUE.equals(exists)) {
                    findings.add(new ValidationFinding(rec.legacyAccountId(), "accountNumber", "ACCOUNT_EXISTS", "Account already exists in platform", "ERROR"));
                    valid = false;
                }
            }

            BigDecimal bal = new BigDecimal(rec.openingBalance());
            if (bal.signum() < 0) {
                findings.add(new ValidationFinding(rec.legacyAccountId(), "openingBalance", "NEGATIVE_BALANCE", "Opening balance cannot be negative", "ERROR"));
                valid = false;
            }

            if (valid) validCount++;
        }

        return new ValidationReport(UUID.randomUUID(), BatchType.ACCOUNTS, records.size(), validCount, records.size() - validCount, findings);
    }

    public ValidationReport stageAccounts(Actor actor, UUID bankId, List<AccountRecord> records) {
        AccessService.admin(actor);
        var report = validateAccounts(bankId, records);
        UUID batchId = report.batchId();

        tx.executeWithoutResult(t -> {
            jdbc.update("""
                INSERT INTO platform.migration_batch (id, tenant_id, batch_type, status, total_records, valid_records, error_records, summary, created_by)
                VALUES (?, ?, 'ACCOUNTS', 'STAGED', ?, ?, ?, ?::jsonb, ?)
                """, batchId, bankId, records.size(), report.validCount(), report.errorCount(),
                    JSON.writeValueAsString(report), actor.subject());

            for (var rec : records) {
                boolean isValid = report.findings().stream().noneMatch(f -> f.legacyId().equals(rec.legacyAccountId()) && f.severity().equals("ERROR"));
                String errMsg = report.findings().stream().filter(f -> f.legacyId().equals(rec.legacyAccountId())).map(ValidationFinding::message).findFirst().orElse(null);

                jdbc.update("""
                    INSERT INTO platform.migration_staged_item (id, batch_id, tenant_id, legacy_id, record_type, payload, valid, error_message)
                    VALUES (?, ?, ?, ?, 'ACCOUNT', ?::jsonb, ?, ?)
                    """, UUID.randomUUID(), batchId, bankId, rec.legacyAccountId(), JSON.writeValueAsString(rec), isValid, errMsg);
            }
        });

        return report;
    }

    public TrialBalanceReconciliation reconcileTrialBalance(Actor actor, UUID bankId, Map<String, BigDecimal> legacyBalances) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = tenants.get(bankId, true);
            var settings = reference.read(bank);
            LocalDate date = settings.businessDate();

            BigDecimal legacyTotal = BigDecimal.ZERO;
            for (var v : legacyBalances.values()) legacyTotal = legacyTotal.add(v);

            // Compute total customer liability across active deposit accounts in platform
            BigDecimal platformTotal = jdbc.queryForObject("""
                SELECT coalesce(sum((payload->>'openingBalance')::numeric), 0)
                FROM platform.migration_staged_item
                WHERE tenant_id = ? AND record_type = 'ACCOUNT' AND committed
                """, BigDecimal.class, bankId);
            if (platformTotal == null) platformTotal = BigDecimal.ZERO;

            BigDecimal diff = platformTotal.subtract(legacyTotal);
            String status = diff.signum() == 0 ? "MATCHED" : "DISCREPANCY";

            UUID reconId = UUID.randomUUID();
            Map<String, Object> breakdown = Map.of(
                    "currencies", legacyBalances.keySet(),
                    "legacyBalances", legacyBalances,
                    "platformTotal", platformTotal.toPlainString(),
                    "difference", diff.toPlainString()
            );

            jdbc.update("""
                INSERT INTO platform.migration_reconciliation (id, tenant_id, business_date, status, legacy_total, platform_total, difference, breakdown, reconciled_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, reconId, bankId, date, status, legacyTotal, platformTotal, diff,
                    JSON.writeValueAsString(breakdown), actor.subject());

            return new TrialBalanceReconciliation(
                    reconId, bankId, date, status,
                    legacyTotal.stripTrailingZeros().toPlainString(),
                    platformTotal.stripTrailingZeros().toPlainString(),
                    diff.stripTrailingZeros().toPlainString(),
                    breakdown, Instant.now()
            );
        });
    }

    public void rollbackBatch(Actor actor, UUID bankId, UUID batchId) {
        AccessService.admin(actor);
        tx.executeWithoutResult(t -> {
            var rows = jdbc.queryForList("SELECT status FROM platform.migration_batch WHERE tenant_id = ? AND id = ?", String.class, bankId, batchId);
            if (rows.isEmpty()) throw error("BATCH_NOT_FOUND");
            if (rows.getFirst().equals("COMMITTED")) throw error("COMMITTED_BATCH_CANNOT_BE_ROLLED_BACK");

            jdbc.update("UPDATE platform.migration_staged_item SET valid = false, error_message = 'Batch rolled back' WHERE batch_id = ?", batchId);
            jdbc.update("UPDATE platform.migration_batch SET status = 'ROLLED_BACK', rolled_back_at = clock_timestamp() WHERE id = ?", batchId);
        });
    }

    public List<BatchSummary> listBatches(Actor actor, UUID bankId) {
        AccessService.admin(actor);
        return jdbc.query("""
            SELECT id, tenant_id, batch_type, status, total_records, valid_records, error_records, created_at, committed_at, rolled_back_at
            FROM platform.migration_batch
            WHERE tenant_id = ?
            ORDER BY created_at DESC
            """, (rs, rowNum) -> new BatchSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                BatchType.valueOf(rs.getString("batch_type")),
                BatchStatus.valueOf(rs.getString("status")),
                rs.getInt("total_records"),
                rs.getInt("valid_records"),
                rs.getInt("error_records"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("committed_at") != null ? rs.getTimestamp("committed_at").toInstant() : null,
                rs.getTimestamp("rolled_back_at") != null ? rs.getTimestamp("rolled_back_at").toInstant() : null
        ), bankId);
    }

    private static TenantException error(String code) {
        return new TenantException(409, code, "Data migration requires attention");
    }
}
