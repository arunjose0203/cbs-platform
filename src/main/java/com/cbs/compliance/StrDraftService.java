package com.cbs.compliance;

import com.cbs.access.AccessService;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantRepository;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Suspicious transaction report (STR) drafts from a compliance case.
 *
 * <p>The draft gathers what an investigator files: the reporting bank, the subject customer, the grounds,
 * the screening and beneficiary matches, the monitoring alerts, the customer's recorded movements and
 * the case history. It is a neutral, documented JSON structure ({@code cbs-str-draft/1}), not the
 * regulator's filing format, which waits for decision D10. Nothing is sent anywhere. Each draft is
 * recorded on the case as an immutable STR_DRAFT event carrying the SHA-256 of the exact document,
 * so a filed report can later be matched to the draft it came from.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class StrDraftService {
    public record Request(@PositiveOrZero long expectedVersion, @PositiveOrZero long caseVersion,
            @NotBlank @Size(max=4000) @Pattern(regexp="[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]+") String grounds,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }

    static final String FORMAT = "cbs-str-draft/1";
    static final int LOOKBACK_DAYS = 180, MAX_MOVEMENTS = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final TransactionTemplate tx;

    public StrDraftService(JdbcTemplate jdbc, TenantRepository tenants, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tenants = tenants; this.tx = new TransactionTemplate(manager);
    }

    public Map<String, Object> draft(Actor actor, UUID bankId, UUID caseId, Request input) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = tenants.get(bankId, true);
            if (bank.version() != input.expectedVersion()) throw new TenantException(409, "STALE_VERSION", "The bank changed; reload and try again");
            var cases = jdbc.queryForList("SELECT c.*,r.customer_number,r.name_key,r.birth_date,r.customer_type FROM platform.compliance_case c "
                    + "JOIN platform.customer_registry r ON r.id=c.customer_id WHERE c.tenant_id=? AND c.id=? AND c.issuer=? FOR UPDATE OF c", bankId, caseId, actor.issuer());
            if (cases.isEmpty()) throw new TenantException(404, "COMPLIANCE_CASE_NOT_FOUND", "Compliance case not found");
            var c = cases.getFirst();
            if (((Number) c.get("version")).longValue() != input.caseVersion()) throw new TenantException(409, "COMPLIANCE_CASE_STALE", "The case changed; reload it");
            UUID customer = (UUID) c.get("customer_id");
            Instant generated = Instant.now();

            var document = new LinkedHashMap<String, Object>();
            document.put("format", FORMAT);
            document.put("status", "DRAFT_NOT_FILED");
            document.put("regulatorFormat", "Pending decision D10; map this draft to the regulator's template before filing");
            document.put("generatedAt", generated.toString());
            document.put("generatedBy", actor.subject());
            var entity = bank.legalEntities().getFirst();
            document.put("reportingEntity", Map.of("bankId", bankId, "displayName", bank.displayName(), "legalName", entity.legalName(), "country", entity.country()));
            var kase = new LinkedHashMap<String, Object>();
            for (var k : List.of("id", "category", "status", "risk", "assignee", "due_date", "opened_by", "created_at")) kase.put(k, text(c.get(k)));
            document.put("case", kase);
            var subject = new LinkedHashMap<String, Object>();
            subject.put("customerId", customer); subject.put("customerNumber", c.get("customer_number")); subject.put("customerType", c.get("customer_type"));
            subject.put("screeningName", c.get("name_key")); subject.put("birthDate", text(c.get("birth_date")));
            subject.put("note", "Identity documents and addresses are held in the bank's customer record; attach them when filing");
            document.put("subject", subject);
            document.put("grounds", input.grounds());
            document.put("screeningMatches", rows("SELECT id,list_name,entry_ref,matched_name,score,status,created_at FROM platform.screening_hit WHERE tenant_id=? AND customer_id=? ORDER BY created_at", bankId, customer));
            document.put("beneficiaryMatches", rows("SELECT id,beneficiary,beneficiary_name,list_name,entry_ref,matched_name,score,stage,status,created_at FROM platform.beneficiary_hit WHERE tenant_id=? AND customer_id=? ORDER BY created_at", bankId, customer));
            document.put("monitoringAlerts", rows("SELECT id,rule,account_id,currency,business_date,amount,status,created_at FROM platform.monitoring_alert WHERE tenant_id=? AND customer_id=? ORDER BY created_at", bankId, customer));
            var movements = rows("""
                    SELECT e.source_id,a.account_number,e.direction,e.channel,e.amount,e.currency,e.business_date FROM platform.monitoring_event e
                    JOIN platform.account_registry a ON a.id=e.account_id WHERE e.tenant_id=? AND a.customer_id=? AND e.business_date>=current_date-?
                    ORDER BY e.business_date,e.recorded_at,e.source_id LIMIT ?
                    """, bankId, customer, LOOKBACK_DAYS, MAX_MOVEMENTS + 1);
            boolean truncated = movements.size() > MAX_MOVEMENTS;
            document.put("movements", truncated ? movements.subList(0, MAX_MOVEMENTS) : movements);
            document.put("movementsWindowDays", LOOKBACK_DAYS);
            document.put("movementsTruncated", truncated);
            document.put("caseHistory", rows("SELECT version,action,subject,created_at FROM platform.compliance_event WHERE tenant_id=? AND case_id=? ORDER BY version", bankId, caseId));

            String body = JSON.writeValueAsString(document);
            String sha = ScreeningService.sha256(body);
            jdbc.update("UPDATE platform.compliance_case SET version=version+1,updated_at=clock_timestamp() WHERE id=?", caseId);
            var state = jdbc.queryForMap("SELECT version,status,risk,reviewed_customer_version,review_due,assignee,due_date FROM platform.compliance_case WHERE id=?", caseId);
            var payload = new LinkedHashMap<String, Object>();
            payload.put("sha256", sha); payload.put("format", FORMAT); payload.put("movements", Math.min(movements.size(), MAX_MOVEMENTS)); payload.put("reason", input.reason());
            payload.put("status", state.get("status")); payload.put("risk", state.get("risk"));
            payload.put("reviewedCustomerVersion", state.get("reviewed_customer_version"));
            payload.put("reviewDue", text(state.get("review_due"))); payload.put("assignee", state.get("assignee")); payload.put("dueDate", text(state.get("due_date")));
            jdbc.update("INSERT INTO platform.compliance_event(id,tenant_id,case_id,version,action,issuer,subject,correlation_id,payload) VALUES (?,?,?,?,'STR_DRAFT',?,?,?,?::jsonb)",
                    UUID.randomUUID(), bankId, caseId, state.get("version"), actor.issuer(), actor.subject(), actor.correlationId(), JSON.writeValueAsString(payload));
            tenants.audit(actor, bankId, "STR_DRAFT_EXPORTED", bank.version(), bank.status(), bank.status(), input.reason(), null, null, Map.of("caseId", caseId, "sha256", sha));
            return Map.of("sha256", sha, "fileName", "str-draft-" + c.get("customer_number") + "-" + generated.toString().substring(0, 10) + ".json",
                    "document", body);
        });
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        var out = new ArrayList<Map<String, Object>>();
        for (var row : jdbc.queryForList(sql, args)) {
            var clean = new LinkedHashMap<String, Object>();
            row.forEach((k, v) -> clean.put(k, text(v)));
            out.add(clean);
        }
        return out;
    }

    /** Plain JSON values: numbers and dates as text, so the hashed document is stable and amounts are never rounded. */
    private static Object text(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().toString();
        if (value instanceof java.math.BigDecimal amount) return amount.toPlainString();
        return value.toString();
    }
}
