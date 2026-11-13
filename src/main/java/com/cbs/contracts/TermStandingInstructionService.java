package com.cbs.contracts;

import static com.cbs.contracts.TermStandingInstructionModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantRepository;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "cbs.tenancy.enabled", havingValue = "true", matchIfMissing = true)
public class TermStandingInstructionService {

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final ContractService contracts;
    private final TransactionTemplate tx;

    public TermStandingInstructionService(JdbcTemplate jdbc, TenantRepository tenants,
                                         ContractService contracts, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.contracts = contracts;
        this.tx = new TransactionTemplate(manager);
    }

    public StandingInstruction register(Actor actor, UUID bankId, InstructionRequest input) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = tenants.get(bankId, true);
            var contract = contracts.definition(bankId, input.contractId());
            if (contract.terms().type() != ContractModels.Type.TERM_DEPOSIT) {
                throw new TenantException(409, "TERM_DEPOSIT_ONLY", "Standing instruction only applies to term deposits");
            }
            if (input.type() != InstructionType.TRANSFER_TO_ACCOUNT_AT_MATURITY && (input.renewalPeriodMonths() == null || input.renewalPeriodMonths() <= 0)) {
                throw new TenantException(409, "RENEWAL_PERIOD_REQUIRED", "Renewal period in months is required for automatic renewals");
            }

            // Deactivate any existing active instruction
            jdbc.update("UPDATE platform.term_standing_instruction SET active = false WHERE tenant_id = ? AND contract_id = ? AND active",
                    bankId, input.contractId());

            UUID id = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO platform.term_standing_instruction
                (id, tenant_id, contract_id, instruction_type, target_account_id, renewal_period_months, renewal_rate_percent, active, reason, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, true, ?, ?)
                """, id, bankId, input.contractId(), input.type().name(), input.targetAccountId(),
                    input.renewalPeriodMonths(), input.renewalRatePercent(), input.reason(), actor.subject());

            return new StandingInstruction(id, bankId, input.contractId(), input.type(), input.targetAccountId(),
                    input.renewalPeriodMonths(), input.renewalRatePercent(), true, input.reason(), actor.subject(), java.time.Instant.now());
        });
    }

    public Optional<StandingInstruction> getForContract(Actor actor, UUID bankId, UUID contractId) {
        AccessService.admin(actor);
        var list = jdbc.query("""
            SELECT id, tenant_id, contract_id, instruction_type, target_account_id, renewal_period_months, renewal_rate_percent, active, reason, created_by, created_at
            FROM platform.term_standing_instruction
            WHERE tenant_id = ? AND contract_id = ? AND active
            ORDER BY created_at DESC LIMIT 1
            """, (rs, rowNum) -> new StandingInstruction(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("contract_id", UUID.class),
                InstructionType.valueOf(rs.getString("instruction_type")),
                rs.getObject("target_account_id", UUID.class),
                rs.getObject("renewal_period_months", Integer.class),
                rs.getString("renewal_rate_percent"),
                rs.getBoolean("active"),
                rs.getString("reason"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant()
        ), bankId, contractId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    public List<StandingInstruction> list(Actor actor, UUID bankId) {
        AccessService.admin(actor);
        return jdbc.query("""
            SELECT id, tenant_id, contract_id, instruction_type, target_account_id, renewal_period_months, renewal_rate_percent, active, reason, created_by, created_at
            FROM platform.term_standing_instruction
            WHERE tenant_id = ? AND active
            ORDER BY created_at DESC
            """, (rs, rowNum) -> new StandingInstruction(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("contract_id", UUID.class),
                InstructionType.valueOf(rs.getString("instruction_type")),
                rs.getObject("target_account_id", UUID.class),
                rs.getObject("renewal_period_months", Integer.class),
                rs.getString("renewal_rate_percent"),
                rs.getBoolean("active"),
                rs.getString("reason"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant()
        ), bankId);
    }
}
