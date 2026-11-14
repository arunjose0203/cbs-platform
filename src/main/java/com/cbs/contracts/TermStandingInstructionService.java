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

    // ---- Maker-checker (P-10: ApprovalService action TERM_STANDING_INSTRUCTION) ----
    // Instructions decide where a depositor's money goes at maturity. History is append-only (the table's trigger
    // forbids updates): the latest approved instruction for a deposit is the one in force.

    static final int MAX_RENEWAL_MONTHS = 120;

    public void validate(com.cbs.tenancy.TenantModels.Tenant bank, InstructionRequest input) {
        if (bank.version() != input.expectedVersion()) throw new TenantException(409, "STALE_VERSION", "The bank changed; reload and try again");
        if (bank.status() != com.cbs.tenancy.TenantModels.Status.ACTIVE) throw new TenantException(409, "ACCOUNT_BANK_NOT_ACTIVE", "Bank is not active");
        var contract = contracts.definition(bank.id(), input.contractId());
        if (contract.terms().type() != ContractModels.Type.TERM_DEPOSIT) {
            throw new TenantException(409, "TERM_DEPOSIT_ONLY", "Standing instruction only applies to term deposits");
        }
        if (input.type() == InstructionType.TRANSFER_TO_ACCOUNT_AT_MATURITY) {
            if (input.targetAccountId() == null) throw new TenantException(400, "TARGET_ACCOUNT_REQUIRED", "Choose the account that receives the proceeds");
            // The proceeds stay with the depositor: same customer, same currency, same bank.
            var depositor = jdbc.queryForList("SELECT customer_id,currency FROM platform.account_registry WHERE tenant_id=? AND id=?", bank.id(), contract.account().id());
            var target = jdbc.queryForList("SELECT customer_id,currency FROM platform.account_registry WHERE tenant_id=? AND id=?", bank.id(), input.targetAccountId());
            if (depositor.isEmpty() || target.isEmpty()) throw new TenantException(404, "ACCOUNT_NOT_FOUND", "Account not found");
            if (!depositor.getFirst().get("customer_id").equals(target.getFirst().get("customer_id"))) {
                throw new TenantException(409, "TARGET_ACCOUNT_NOT_DEPOSITOR", "Proceeds can only go to an account of the same customer");
            }
            if (!depositor.getFirst().get("currency").equals(target.getFirst().get("currency"))) {
                throw new TenantException(409, "TARGET_ACCOUNT_CURRENCY", "The receiving account must be in the deposit's currency");
            }
        } else {
            if (input.targetAccountId() != null) throw new TenantException(400, "TARGET_ACCOUNT_NOT_USED", "Renewals do not take a receiving account");
            if (input.renewalPeriodMonths() == null || input.renewalPeriodMonths() <= 0 || input.renewalPeriodMonths() > MAX_RENEWAL_MONTHS) {
                throw new TenantException(409, "RENEWAL_PERIOD_REQUIRED", "Renewal period must be 1 to " + MAX_RENEWAL_MONTHS + " months");
            }
            if (input.renewalRatePercent() != null && new java.math.BigDecimal(input.renewalRatePercent()).compareTo(new java.math.BigDecimal("100")) > 0) {
                throw new TenantException(400, "RENEWAL_RATE_INVALID", "Renewal rate must be at most 100 percent");
            }
        }
    }

    public Map<String, Object> values(InstructionRequest input) {
        var m = new LinkedHashMap<String, Object>();
        m.put("contractId", input.contractId()); m.put("type", input.type().name());
        if (input.targetAccountId() != null) m.put("targetAccountId", input.targetAccountId());
        if (input.renewalPeriodMonths() != null) m.put("renewalPeriodMonths", input.renewalPeriodMonths());
        if (input.renewalRatePercent() != null) m.put("renewalRatePercent", input.renewalRatePercent());
        m.put("reason", input.reason());
        return m;
    }

    public Map<String, Object> before(UUID bankId, UUID contractId) {
        var rows = jdbc.queryForList("SELECT instruction_type,target_account_id FROM platform.term_standing_instruction WHERE tenant_id=? AND contract_id=? ORDER BY created_at DESC,id LIMIT 1", bankId, contractId);
        return rows.isEmpty() ? Map.of("instruction", "none") : Map.of("instruction", rows.getFirst().get("instruction_type"));
    }

    /** Records the approved instruction; the maker stays on record as its author. */
    public UUID apply(Actor checker, UUID bankId, InstructionRequest input, String makerSubject, UUID approvalId) {
        AccessService.admin(checker);
        var bank = tenants.get(bankId, true);
        validate(bank, input);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO platform.term_standing_instruction
            (id, tenant_id, contract_id, instruction_type, target_account_id, renewal_period_months, renewal_rate_percent, active, reason, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, true, ?, ?)
            """, id, bankId, input.contractId(), input.type().name(), input.targetAccountId(),
                input.renewalPeriodMonths(), input.renewalRatePercent(), input.reason(), makerSubject);
        tenants.change(bank, bank.status(), bank.displayName(), bank.defaultLanguage());
        tenants.audit(checker, bankId, "TERM_STANDING_INSTRUCTION_APPROVED", bank.version() + 1, bank.status(), bank.status(),
                "Approved request " + approvalId, null, null, Map.of("contractId", input.contractId(), "instructionId", id, "type", input.type().name(), "requestId", approvalId));
        return id;
    }

    public Optional<StandingInstruction> getForContract(Actor actor, UUID bankId, UUID contractId) {
        AccessService.admin(actor);
        var list = jdbc.query("""
            SELECT id, tenant_id, contract_id, instruction_type, target_account_id, renewal_period_months, renewal_rate_percent, active, reason, created_by, created_at
            FROM platform.term_standing_instruction
            WHERE tenant_id = ? AND contract_id = ? AND active
            ORDER BY created_at DESC, id LIMIT 1
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
            SELECT DISTINCT ON (contract_id) id, tenant_id, contract_id, instruction_type, target_account_id, renewal_period_months, renewal_rate_percent, active, reason, created_by, created_at
            FROM platform.term_standing_instruction
            WHERE tenant_id = ? AND active
            ORDER BY contract_id, created_at DESC, id
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
