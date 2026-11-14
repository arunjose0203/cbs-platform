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
    private final com.cbs.privacy.PersonalDataCipher cipher;
    private final com.cbs.tenancy.BankDatabaseProvisioner databases;

    public MigrationService(JdbcTemplate jdbc, TenantRepository tenants,
                            ReferenceDataService reference, JournalService journals,
                            PlatformTransactionManager manager, com.cbs.privacy.PersonalDataCipher cipher,
                            com.cbs.tenancy.BankDatabaseProvisioner databases) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.reference = reference;
        this.journals = journals;
        this.tx = new TransactionTemplate(manager);
        this.cipher = cipher;
        this.databases = databases;
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
            } else if (!seenIds.add(rec.nationalId().replaceAll("[ -]", "").toUpperCase(Locale.ROOT))) {
                // P-10: findings are stored and returned; they never repeat the identity number.
                findings.add(new ValidationFinding(rec.legacyId(), "nationalId", "DUPLICATE_IN_FILE", "National ID duplicated in import file", "ERROR"));
                valid = false;
            }
            // Duplicates against existing customers are checked by customer onboarding (document key blind index),
            // which needs the document type and country that this import format does not carry.

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
                    """, UUID.randomUUID(), batchId, bankId, rec.legacyId(), JSON.writeValueAsString(sealed(bankId, rec)), isValid, errMsg);
            }
        });

        return report;
    }

    /** Staged copies keep the identity number sealed with the bank's field key, never in plaintext (Round 18 rule). */
    private CustomerRecord sealed(UUID bankId, CustomerRecord rec) {
        return new CustomerRecord(rec.legacyId(), cipher.seal(bankId, rec.nationalId()), rec.fullName(), rec.phone(), rec.email(),
                rec.dateOfBirth(), rec.nationality(), rec.kycDocumentReference());
    }

    /**
     * P-10 (S-09): refused. The earlier implementation wrote ACTIVE customers straight into the registry: no approval,
     * plaintext identity numbers, no screening, no audit and nothing delivered to the bank database. This import
     * format also lacks fields every customer needs (address, document type and country, KYC reference). Until a
     * batch path exists that submits each customer through onboarding approval, migrated customers are onboarded
     * through the normal customer approval flow.
     */
    public int commitCustomers(Actor actor, UUID bankId, UUID batchId) {
        AccessService.admin(actor);
        throw new TenantException(409, "MIGRATION_COMMIT_UNAVAILABLE",
                "Batch commit is disabled; onboard migrated customers through customer approval");
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
                        "SELECT EXISTS(SELECT 1 FROM platform.account_registry WHERE tenant_id = ? AND account_number = ?)",
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

    /**
     * Cutover reconciliation for one currency: the legacy system's customer deposit total against the balances actually
     * posted in the bank's own ledger (sum of {@code bank.customer_position.posted} for accounts whose ledger is in that
     * currency).
     *
     * <p>P-10 (S-10): the earlier version compared the legacy total with the staged import file itself and summed
     * across currencies, so it could never find a difference, and recorded the result as immutable evidence.
     */
    public TrialBalanceReconciliation reconcileTrialBalance(Actor actor, UUID bankId, Map<String, BigDecimal> legacyBalances) {
        AccessService.admin(actor);
        if (legacyBalances == null || legacyBalances.size() != 1) throw error("RECONCILIATION_ONE_CURRENCY");
        var entry = legacyBalances.entrySet().iterator().next();
        String currency = entry.getKey();
        BigDecimal legacyTotal = entry.getValue();
        if (currency == null || !currency.matches("[A-Z]{3}") || legacyTotal == null || legacyTotal.signum() < 0) throw error("RECONCILIATION_INVALID");
        return tx.execute(t -> {
            var bank = tenants.get(bankId, true);
            var settings = reference.read(bank);
            if (settings.currencies().stream().noneMatch(c -> c.enabled() && c.code().equals(currency))) throw error("CURRENCY_DISABLED");
            LocalDate date = settings.businessDate();
            BigDecimal platformTotal = postedCustomerBalances(bank, currency);
            BigDecimal diff = platformTotal.subtract(legacyTotal);
            String status = diff.signum() == 0 ? "MATCHED" : "DISCREPANCY";

            UUID reconId = UUID.randomUUID();
            Map<String, Object> breakdown = Map.of(
                    "currency", currency,
                    "source", "bank.customer_position (posted)",
                    "legacyTotal", legacyTotal.toPlainString(),
                    "platformTotal", platformTotal.toPlainString(),
                    "difference", diff.toPlainString());

            jdbc.update("""
                INSERT INTO platform.migration_reconciliation (id, tenant_id, business_date, status, legacy_total, platform_total, difference, breakdown, reconciled_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, reconId, bankId, date, status, legacyTotal, platformTotal, diff,
                    JSON.writeValueAsString(breakdown), actor.subject());

            return new TrialBalanceReconciliation(
                    reconId, bankId, date, status,
                    legacyTotal.toPlainString(), platformTotal.toPlainString(), diff.toPlainString(),
                    breakdown, Instant.now());
        });
    }

    /** Posted customer balances in one currency, read from the bank's own database. */
    BigDecimal postedCustomerBalances(com.cbs.tenancy.TenantModels.Tenant bank, String currency) {
        try (var c = databases.openOwned(bank.id(), bank.databaseBinding().databaseReference())) {
            c.setReadOnly(true);
            try (var s = c.prepareStatement("""
                    SELECT coalesce(sum(p.posted),0) FROM bank.customer_position p
                    JOIN bank.ledger_account l ON l.id = p.ledger_id
                    WHERE l.currency = ?
                    """)) {
                s.setString(1, currency);
                try (var r = s.executeQuery()) { r.next(); return r.getBigDecimal(1); }
            }
        } catch (java.sql.SQLException e) {
            throw new TenantException(503, "RECONCILIATION_BANK_UNAVAILABLE", "Bank balances could not be read; retry");
        }
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
