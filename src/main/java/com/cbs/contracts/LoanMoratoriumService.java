package com.cbs.contracts;

import static com.cbs.contracts.LoanMoratoriumModels.*;
import com.cbs.access.AccessService;
import com.cbs.access.LendingAccess;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantModels.Status;
import com.cbs.tenancy.TenantRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "cbs.tenancy.enabled", havingValue = "true", matchIfMissing = true)
public class LoanMoratoriumService {
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final ContractService contracts;
    private final LendingAccess lending;
    private final ReferenceDataService reference;
    private final TransactionTemplate tx;

    public LoanMoratoriumService(JdbcTemplate jdbc, TenantRepository tenants, ContractService contracts,
                                 LendingAccess lending, ReferenceDataService reference, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.contracts = contracts;
        this.lending = lending;
        this.reference = reference;
        this.tx = new TransactionTemplate(manager);
    }

    private static TenantException error(String code, String msg) {
        return new TenantException(409, code, msg);
    }

    // ---- Maker-checker (P-10: ApprovalService action LOAN_MORATORIUM; lending grants decide who may submit and approve) ----

    public void validate(com.cbs.tenancy.TenantModels.Tenant bank, MoratoriumGrant input) {
        if (bank.version() != input.expectedVersion()) throw error("STALE_VERSION", "The bank changed; reload and try again");
        if (bank.status() != Status.ACTIVE) throw error("ACCOUNT_BANK_NOT_ACTIVE", "Bank is not active");
        var d = contracts.definition(bank.id(), input.contractId());
        if (d.terms().type() != ContractModels.Type.LOAN) throw error("LOAN_REQUIRED", "Contract must be a loan");
        LocalDate today = reference.read(bank).businessDate();
        if (!input.businessDate().equals(today)) throw error("BUSINESS_DATE_MISMATCH", "Use the bank's current business date");
        if (input.endDate().isBefore(input.startDate())) {
            throw error("MORATORIUM_DATES_INVALID", "Moratorium end date cannot be before start date");
        }
        if (input.endDate().isBefore(today)) {
            throw error("MORATORIUM_EXPIRED", "Moratorium end date cannot be in the past");
        }
    }

    public Map<String, Object> values(MoratoriumGrant input) {
        return Map.of("contractId", input.contractId(), "type", input.type().name(), "startDate", input.startDate().toString(),
                "endDate", input.endDate().toString(), "reason", input.reason().trim());
    }

    /** Records the approved moratorium; the maker stays on record as its author. */
    public UUID apply(Actor checker, UUID bankId, MoratoriumGrant input, String makerSubject, UUID approvalId) {
        var bank = tenants.get(bankId, true);
        validate(bank, input);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO platform.loan_moratorium(id,tenant_id,contract_id,moratorium_type,start_date,end_date,reason,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id, bankId, input.contractId(), input.type().name(), input.startDate(), input.endDate(), input.reason().trim(), makerSubject, Timestamp.from(Instant.now()));
        tenants.change(bank, bank.status(), bank.displayName(), bank.defaultLanguage());
        tenants.audit(checker, bankId, "LOAN_MORATORIUM_GRANTED", bank.version() + 1, bank.status(), bank.status(),
                "Approved request " + approvalId, null, null,
                Map.of("contractId", input.contractId(), "moratoriumId", id, "type", input.type().name(), "startDate", input.startDate().toString(),
                        "endDate", input.endDate().toString(), "requestId", approvalId));
        return id;
    }

    public List<MoratoriumRecord> list(Actor actor, UUID bankId, UUID contractId) {
        AccessService.admin(actor);
        return jdbc.query("SELECT id,contract_id,moratorium_type,start_date,end_date,reason,created_by,created_at FROM platform.loan_moratorium WHERE tenant_id=? AND contract_id=? ORDER BY created_at DESC",
                (rs, n) -> new MoratoriumRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("contract_id", UUID.class),
                        MoratoriumType.valueOf(rs.getString("moratorium_type")),
                        rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date").toLocalDate(),
                        rs.getString("reason"),
                        rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant()
                ), bankId, contractId);
    }

    public boolean hasActiveMoratorium(UUID bankId, UUID contractId, LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform.loan_moratorium WHERE tenant_id=? AND contract_id=? AND ? BETWEEN start_date AND end_date)",
                Boolean.class, bankId, contractId, date));
    }
}
