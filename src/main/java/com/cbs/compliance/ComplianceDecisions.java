package com.cbs.compliance;

import static com.cbs.compliance.AutomationModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decisions on screening hits and monitoring alerts. Escalation is immediate, because it only adds
 * control: it opens a compliance case that restricts the customer until independently reviewed.
 * Dismissal as a false positive removes control, so it needs maker-checker approval.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ComplianceDecisions {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final ComplianceService cases;
    private final TransactionTemplate tx;

    public ComplianceDecisions(JdbcTemplate jdbc, TenantRepository tenants, ComplianceService cases, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tenants = tenants; this.cases = cases; this.tx = new TransactionTemplate(manager);
    }

    private static String table(ItemType type) { return type == ItemType.SCREENING_HIT ? "platform.screening_hit" : "platform.monitoring_alert"; }

    private Map<String, Object> item(UUID bankId, ItemType type, UUID id, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM " + table(type) + " WHERE tenant_id=? AND id=?" + (lock ? " FOR UPDATE" : ""), bankId, id);
        if (rows.isEmpty()) {
            throw ScreeningService.error(404, type == ItemType.SCREENING_HIT ? "SCREENING_HIT_NOT_FOUND" : "MONITORING_ALERT_NOT_FOUND", "Alert not found");
        }
        return rows.getFirst();
    }

    private static void requireOpen(Map<String, Object> item, long version) {
        if (!"OPEN".equals(item.get("status")) || ((Number) item.get("version")).longValue() != version) {
            throw ScreeningService.error(409, "COMPLIANCE_ALERT_STALE", "The alert was already decided or changed; reload it");
        }
    }

    private static void active(Tenant bank, long expected) {
        if (bank.version() != expected) { throw ScreeningService.error(409, "STALE_VERSION", "The bank changed; reload and try again"); }
        if (bank.status() != Status.ACTIVE) { throw ScreeningService.error(409, "COMPLIANCE_BANK_NOT_ACTIVE", "The bank is not active"); }
    }

    public Map<String, Object> escalate(Actor actor, UUID bankId, ItemType type, UUID id, Escalate input) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = tenants.get(bankId, true);
            active(bank, input.expectedVersion());
            var item = item(bankId, type, id, true);
            requireOpen(item, input.itemVersion());
            var source = new LinkedHashMap<String, Object>();
            if (type == ItemType.SCREENING_HIT) {
                source.put("screeningHitId", id); source.put("list", item.get("list_name")); source.put("entryRef", item.get("entry_ref"));
                source.put("score", item.get("score").toString()); source.put("matchedName", item.get("matched_name"));
            } else {
                source.put("monitoringAlertId", id); source.put("rule", item.get("rule")); source.put("accountId", item.get("account_id"));
                source.put("amount", item.get("amount").toString()); source.put("currency", item.get("currency"));
            }
            var category = type == ItemType.SCREENING_HIT ? ComplianceModels.Category.SCREENING_ALERT : ComplianceModels.Category.TRANSACTION_REVIEW;
            UUID caseId = cases.openFromAlert(actor, bank, (UUID) item.get("customer_id"), category, input.evidenceReference(), input.reason(), source);
            jdbc.update("UPDATE " + table(type) + " SET status='ESCALATED',case_id=?,decided_by=?,decided_at=clock_timestamp(),version=version+1,decision=?::jsonb WHERE id=?",
                    caseId, actor.subject(), JSON.writeValueAsString(Map.of("action", "ESCALATED", "evidenceReference", input.evidenceReference(), "reason", input.reason())), id);
            return Map.of("version", tenants.get(bankId, false).version(), "caseId", caseId, "status", "ESCALATED");
        });
    }

    // ---- Dismissal (approval) -------------------------------------------------------------------

    public void validate(Tenant bank, Dismiss input) {
        active(bank, input.expectedVersion());
        requireOpen(item(bank.id(), input.type(), input.itemId(), false), input.itemVersion());
    }

    public Map<String, Object> before(Tenant bank, Dismiss input) {
        var item = item(bank.id(), input.type(), input.itemId(), false);
        var m = new LinkedHashMap<String, Object>();
        m.put("status", item.get("status"));
        if (input.type() == ItemType.SCREENING_HIT) {
            for (var k : List.of("customer_name", "matched_name", "list_name", "entry_ref", "score")) { m.put(k, String.valueOf(item.get(k))); }
        } else {
            for (var k : List.of("rule", "account_id", "currency", "business_date", "amount")) { m.put(k, String.valueOf(item.get(k))); }
        }
        return m;
    }

    public Map<String, Object> values(Dismiss input) {
        return Map.of("status", "DISMISSED", "type", input.type().name(), "evidenceReference", input.evidenceReference(), "reason", input.reason());
    }

    public void apply(Actor actor, UUID bankId, Dismiss input, String maker, UUID approvalId) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, true);
        item(bankId, input.type(), input.itemId(), true);
        validate(bank, input);
        jdbc.update("UPDATE " + table(input.type()) + " SET status='DISMISSED',approval_id=?,decided_by=?,decided_at=clock_timestamp(),version=version+1,decision=?::jsonb WHERE id=?",
                approvalId, actor.subject(), JSON.writeValueAsString(Map.of("action", "DISMISSED", "maker", maker, "evidenceReference", input.evidenceReference(), "reason", input.reason())), input.itemId());
        tenants.change(bank, bank.status(), bank.displayName(), bank.defaultLanguage());
        tenants.audit(actor, bankId, input.type() == ItemType.SCREENING_HIT ? "SCREENING_HIT_DISMISSED" : "MONITORING_ALERT_DISMISSED", bank.version() + 1,
                bank.status(), bank.status(), input.reason(), null, null, Map.of("itemId", input.itemId(), "approvalId", approvalId));
    }
}
