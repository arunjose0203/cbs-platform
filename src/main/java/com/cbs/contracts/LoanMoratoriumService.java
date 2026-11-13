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

    public MoratoriumRecord grant(Actor actor, UUID bankId, MoratoriumGrant input) {
        return tx.execute(status -> {
            var bank = tenants.get(bankId, true);
            if (bank.status() != Status.ACTIVE) throw error("ACCOUNT_BANK_NOT_ACTIVE", "Bank is not active");

            var d = contracts.definition(bankId, input.contractId());
            if (d.terms().type() != ContractModels.Type.LOAN) throw error("LOAN_REQUIRED", "Contract must be a loan");

            LocalDate today = reference.read(bank).businessDate();
            if (input.endDate().isBefore(input.startDate())) {
                throw error("MORATORIUM_DATES_INVALID", "Moratorium end date cannot be before start date");
            }
            if (input.endDate().isBefore(today)) {
                throw error("MORATORIUM_EXPIRED", "Moratorium end date cannot be in the past");
            }

            lending.role(actor, bankId, d.account().id(), LendingAccess.Role.CREDIT_APPROVER);

            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            jdbc.update("INSERT INTO platform.loan_moratorium(id,tenant_id,contract_id,moratorium_type,start_date,end_date,reason,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    id, bankId, input.contractId(), input.type().name(), input.startDate(), input.endDate(), input.reason().trim(), actor.subject(), Timestamp.from(now));

            tenants.audit(actor, bankId, "LOAN_MORATORIUM_GRANTED", bank.version(), bank.status(), bank.status(),
                    "Moratorium granted: " + input.type() + " from " + input.startDate() + " to " + input.endDate(), null, null,
                    Map.of("contractId", input.contractId(), "moratoriumId", id, "type", input.type().name(), "startDate", input.startDate().toString(), "endDate", input.endDate().toString()));

            return new MoratoriumRecord(id, input.contractId(), input.type(), input.startDate(), input.endDate(), input.reason(), actor.subject(), now);
        });
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
