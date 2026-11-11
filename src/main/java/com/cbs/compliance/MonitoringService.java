package com.cbs.compliance;

import static com.cbs.compliance.AutomationModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Post-event transaction monitoring.
 *
 * <p>A scan copies every newly completed customer movement (cash, funding, transfers, withdrawals,
 * mobile payouts) into an immutable monitoring history, then evaluates the bank's approved rules for
 * the movement's currency. Alerts are for investigation only: they never block the customer, so the
 * customer is not tipped off. Escalation opens a compliance case, which does restrict. Rows are read
 * by completion time with a ten-minute overlap and de-duplicated, so a slow commit is not missed.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class MonitoringService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final TransactionTemplate tx;

    public MonitoringService(JdbcTemplate jdbc, TenantRepository tenants, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tenants = tenants; this.tx = new TransactionTemplate(manager);
    }

    // ---- Policy (approval) ----------------------------------------------------------------------

    public void validate(Tenant bank, Policy p) {
        if (bank.version() != p.expectedVersion()) { throw ScreeningService.error(409, "STALE_VERSION", "The bank changed; reload and try again"); }
        if (bank.status() != Status.ACTIVE) { throw ScreeningService.error(409, "COMPLIANCE_BANK_NOT_ACTIVE", "The bank is not active"); }
        boolean structuring = allOrNone(p.structuringTotal(), p.structuringDays(), p.structuringCount());
        boolean unusual = allOrNone(p.unusualMultiplier(), p.unusualLookbackDays(), p.unusualMinHistory());
        if (p.largeCash() == null && !structuring && p.velocityDebits() == null && !unusual) {
            throw ScreeningService.error(400, "MONITORING_POLICY_EMPTY", "Turn on at least one rule");
        }
        for (var amount : Arrays.asList(p.largeCash(), p.structuringTotal())) {
            if (amount != null && new BigDecimal(amount).signum() <= 0) { throw ScreeningService.error(400, "MONITORING_POLICY_INVALID", "Amounts must be positive"); }
        }
    }

    private static boolean allOrNone(Object... values) {
        long set = Arrays.stream(values).filter(Objects::nonNull).count();
        if (set != 0 && set != values.length) { throw ScreeningService.error(400, "MONITORING_POLICY_INVALID", "Fill in every field of a rule, or none"); }
        return set == values.length;
    }

    public Map<String, Object> before(Tenant bank, String currency) {
        var p = policy(bank.id(), currency);
        return p == null ? Map.of() : values(p);
    }

    public Map<String, Object> values(Policy p) {
        var m = new LinkedHashMap<String, Object>();
        m.put("currency", p.currency());
        if (p.largeCash() != null) { m.put("largeCash", "single cash movement ≥ " + p.largeCash()); }
        if (p.structuringTotal() != null) {
            m.put("structuring", p.structuringCount() + "+ cash deposits each below " + p.structuringTotal() + " totalling ≥ " + p.structuringTotal() + " within " + p.structuringDays() + " day(s)");
        }
        if (p.velocityDebits() != null) { m.put("velocity", p.velocityDebits() + "+ debits on one account in a business day"); }
        if (p.unusualMultiplier() != null) {
            m.put("unusualAmount", "movement ≥ " + p.unusualMultiplier().stripTrailingZeros().toPlainString() + "× the account's average over " + p.unusualLookbackDays() + " days (at least " + p.unusualMinHistory() + " prior movements)");
        }
        m.put("reason", p.reason());
        return m;
    }

    public void apply(Actor actor, UUID bankId, Policy p, UUID approvalId) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, true);
        validate(bank, p);
        jdbc.update("INSERT INTO platform.monitoring_policy(tenant_id,currency,rules,approval_id) VALUES (?,?,?::jsonb,?) ON CONFLICT(tenant_id,currency) DO UPDATE SET rules=excluded.rules,approval_id=excluded.approval_id,updated_at=clock_timestamp()",
                bankId, p.currency(), JSON.writeValueAsString(p), approvalId);
        tenants.change(bank, bank.status(), bank.displayName(), bank.defaultLanguage());
        tenants.audit(actor, bankId, "MONITORING_POLICY_CHANGED", bank.version() + 1, bank.status(), bank.status(), p.reason(), null, null, Map.of("currency", p.currency()));
    }

    private Policy policy(UUID bankId, String currency) {
        var rows = jdbc.query("SELECT rules::text FROM platform.monitoring_policy WHERE tenant_id=? AND currency=?", (r, n) -> JSON.readValue(r.getString(1), Policy.class), bankId, currency);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Map<String, Object>> policies(Actor actor, UUID bankId) {
        AccessService.admin(actor); tenants.get(bankId, false);
        return jdbc.query("SELECT rules::text,updated_at FROM platform.monitoring_policy WHERE tenant_id=? ORDER BY currency", (r, n) -> {
            var p = JSON.readValue(r.getString(1), Policy.class);
            var m = new LinkedHashMap<String, Object>(JSON.readValue(r.getString(1), new TypeReference<Map<String, Object>>() { }));
            m.remove("expectedVersion");
            m.put("summary", values(p));
            m.put("updatedAt", r.getTimestamp(2).toInstant());
            return (Map<String, Object>) m;
        }, bankId);
    }

    // ---- Scanning -------------------------------------------------------------------------------

    // A teller deposit also writes a funding record, and a teller withdrawal or mobile payout an account
    // operation, under the same request id: each movement is classified once, by its originating channel.
    private static final String SOURCES = """
        WITH src AS (
         SELECT o.request_id AS source_id,o.account_id,'OUT' AS direction,
                CASE WHEN o.payload->'change'->>'kind'='TRANSFER' THEN 'TRANSFER' ELSE 'WITHDRAWAL' END AS channel,
                (o.payload->'change'->>'amount')::numeric AS amount,
                coalesce((o.payload->'change'->>'businessDate')::date,o.completed_at::date) AS business_date,o.completed_at AS recorded_at
           FROM platform.account_operation o
          WHERE o.tenant_id=:bank AND o.completed AND o.completed_at>:lower AND o.completed_at<=:upper
            AND o.payload->'change'->>'kind' IN ('TRANSFER','WITHDRAWAL')
            AND NOT EXISTS(SELECT 1 FROM platform.cash_delivery x WHERE x.request_id=o.request_id)
            AND NOT EXISTS(SELECT 1 FROM platform.payment_order x WHERE x.id=o.request_id)
         UNION ALL
         SELECT o.request_id,o.destination_id,'IN','TRANSFER',(o.payload->'change'->>'amount')::numeric,
                coalesce((o.payload->'change'->>'businessDate')::date,o.completed_at::date),o.completed_at
           FROM platform.account_operation o
          WHERE o.tenant_id=:bank AND o.completed AND o.completed_at>:lower AND o.completed_at<=:upper
            AND o.payload->'change'->>'kind'='TRANSFER' AND o.destination_id IS NOT NULL
         UNION ALL
         SELECT f.request_id,f.account_id,'IN','FUNDING',(f.payload->>'amount')::numeric,
                coalesce((f.payload->'journal'->>'businessDate')::date,f.completed_at::date),f.completed_at
           FROM platform.funding_delivery f
          WHERE f.tenant_id=:bank AND f.completed AND f.completed_at>:lower AND f.completed_at<=:upper
            AND NOT EXISTS(SELECT 1 FROM platform.cash_delivery x WHERE x.request_id=f.request_id)
         UNION ALL
         SELECT c.request_id,(c.payload->'change'->>'accountId')::uuid,
                CASE WHEN c.payload->'change'->>'kind'='DEPOSIT' THEN 'IN' ELSE 'OUT' END,'CASH',
                (c.payload->'change'->>'amount')::numeric,
                coalesce((c.payload->'change'->>'businessDate')::date,c.completed_at::date),c.completed_at
           FROM platform.cash_delivery c
          WHERE c.tenant_id=:bank AND c.completed AND c.completed_at>:lower AND c.completed_at<=:upper
            AND c.payload->'change'->>'kind' IN ('DEPOSIT','WITHDRAWAL') AND c.payload->'change'->>'accountId' IS NOT NULL
         UNION ALL
         SELECT p.id,p.account_id,'OUT','MOBILE',(p.payload->>'amount')::numeric,
                coalesce((p.payload->>'businessDate')::date,p.created_at::date),p.created_at
           FROM platform.payment_order p
          WHERE p.tenant_id=:bank AND p.created_at>:lower AND p.created_at<=:upper)
        INSERT INTO platform.monitoring_event(source_id,account_id,direction,tenant_id,channel,amount,currency,business_date,recorded_at)
        SELECT s.source_id,s.account_id,s.direction,:bank,s.channel,s.amount,a.currency,s.business_date,s.recorded_at
          FROM src s JOIN platform.account_registry a ON a.id=s.account_id AND a.tenant_id=:bank
         WHERE s.amount>0
        ON CONFLICT DO NOTHING
        RETURNING source_id,account_id,direction,channel,amount,currency,business_date,recorded_at
        """;

    private record Event(UUID source, UUID account, String direction, String channel, BigDecimal amount, String currency, LocalDate date, java.sql.Timestamp recordedAt) { }

    /** Scans every active bank once; returns the number of new alerts. */
    public int scan() {
        int alerts = 0;
        for (UUID bank : jdbc.queryForList("SELECT id FROM platform.tenant WHERE status='ACTIVE' ORDER BY id", UUID.class)) {
            alerts += Objects.requireNonNull(tx.execute(t -> scan(bank)));
        }
        return alerts;
    }

    int scan(UUID bankId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", r -> { }, "monitoring:" + bankId);
        var bounds = jdbc.queryForMap("SELECT coalesce((SELECT watermark FROM platform.monitoring_cursor WHERE tenant_id=?),'-infinity'::timestamptz) - interval '10 minutes' AS lower, clock_timestamp() AS upper", bankId);
        var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        var params = Map.<String, Object>of("bank", bankId, "lower", bounds.get("lower"), "upper", bounds.get("upper"));
        var events = named.query(SOURCES, params, (r, n) -> new Event(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getString(4),
                r.getBigDecimal(5), r.getString(6), r.getObject(7, LocalDate.class), r.getTimestamp(8)));
        jdbc.update("INSERT INTO platform.monitoring_cursor(tenant_id,watermark) VALUES (?,?) ON CONFLICT(tenant_id) DO UPDATE SET watermark=excluded.watermark,updated_at=clock_timestamp()",
                bankId, bounds.get("upper"));
        if (events.isEmpty()) { return 0; }
        var policies = new HashMap<String, Optional<Policy>>();
        int alerts = 0;
        for (var e : events.stream().sorted(Comparator.comparing(Event::recordedAt).thenComparing(Event::source)).toList()) {
            var policy = policies.computeIfAbsent(e.currency(), c -> Optional.ofNullable(policy(bankId, c)));
            if (policy.isPresent()) { alerts += evaluate(bankId, policy.get(), e); }
        }
        return alerts;
    }

    private int evaluate(UUID bankId, Policy p, Event e) {
        int alerts = 0;
        if (p.largeCash() != null && e.channel().equals("CASH") && e.amount().compareTo(new BigDecimal(p.largeCash())) >= 0) {
            alerts += alert(bankId, e, "LARGE_CASH", "LARGE_CASH:" + e.source() + ":" + e.account(), e.amount(),
                    Map.of("sourceId", e.source(), "direction", e.direction(), "threshold", p.largeCash()));
        }
        if (p.structuringTotal() != null && e.channel().equals("CASH") && e.direction().equals("IN")) {
            var limit = new BigDecimal(p.structuringTotal());
            if (e.amount().compareTo(limit) < 0) {
                LocalDate from = e.date().minusDays(p.structuringDays() - 1L);
                var w = jdbc.queryForMap("SELECT count(*) AS n,coalesce(sum(amount),0) AS total FROM platform.monitoring_event WHERE tenant_id=? AND account_id=? AND channel='CASH' AND direction='IN' AND amount<? AND business_date BETWEEN ? AND ?",
                        bankId, e.account(), limit, from, e.date());
                long n = ((Number) w.get("n")).longValue();
                var total = (BigDecimal) w.get("total");
                if (n >= p.structuringCount() && total.compareTo(limit) >= 0) {
                    alerts += alert(bankId, e, "STRUCTURING", "STRUCTURING:" + e.account() + ":" + e.date(), total,
                            Map.of("deposits", n, "from", from.toString(), "to", e.date().toString(), "threshold", p.structuringTotal()));
                }
            }
        }
        if (p.velocityDebits() != null && e.direction().equals("OUT")) {
            long n = jdbc.queryForObject("SELECT count(*) FROM platform.monitoring_event WHERE tenant_id=? AND account_id=? AND direction='OUT' AND business_date=?", Long.class, bankId, e.account(), e.date());
            if (n >= p.velocityDebits()) {
                var total = jdbc.queryForObject("SELECT sum(amount) FROM platform.monitoring_event WHERE tenant_id=? AND account_id=? AND direction='OUT' AND business_date=?", BigDecimal.class, bankId, e.account(), e.date());
                alerts += alert(bankId, e, "VELOCITY", "VELOCITY:" + e.account() + ":" + e.date(), total, Map.of("debits", n, "threshold", p.velocityDebits()));
            }
        }
        if (p.unusualMultiplier() != null) {
            var h = jdbc.queryForMap("SELECT count(*) AS n,avg(amount) AS average FROM platform.monitoring_event WHERE tenant_id=? AND account_id=? AND direction=? AND business_date>=? AND business_date<?",
                    bankId, e.account(), e.direction(), e.date().minusDays(p.unusualLookbackDays()), e.date());
            long n = ((Number) h.get("n")).longValue();
            if (n >= p.unusualMinHistory()) {
                var average = ((BigDecimal) h.get("average")).setScale(6, RoundingMode.HALF_UP);
                if (e.amount().compareTo(average.multiply(p.unusualMultiplier())) >= 0) {
                    alerts += alert(bankId, e, "UNUSUAL_AMOUNT", "UNUSUAL_AMOUNT:" + e.source() + ":" + e.account() + ":" + e.direction(), e.amount(),
                            Map.of("sourceId", e.source(), "direction", e.direction(), "average", average.stripTrailingZeros().toPlainString(),
                                    "history", n, "multiplier", p.unusualMultiplier().stripTrailingZeros().toPlainString()));
                }
            }
        }
        return alerts;
    }

    private int alert(UUID bankId, Event e, String rule, String key, BigDecimal amount, Map<String, Object> details) {
        var d = new LinkedHashMap<String, Object>(details);
        d.put("channel", e.channel());
        return jdbc.update("INSERT INTO platform.monitoring_alert(id,tenant_id,account_id,customer_id,rule,dedupe_key,currency,business_date,amount,details) "
                + "SELECT ?,?,a.id,a.customer_id,?,?,?,?,?,?::jsonb FROM platform.account_registry a WHERE a.id=? AND a.tenant_id=? ON CONFLICT (tenant_id,dedupe_key) DO NOTHING",
                UUID.randomUUID(), bankId, rule, key, e.currency(), e.date(), amount, JSON.writeValueAsString(d), e.account(), bankId);
    }

    // ---- Alerts ---------------------------------------------------------------------------------

    public Map<String, Object> alerts(Actor actor, UUID bankId, String status, int limit, int offset) {
        AccessService.admin(actor); AccessService.page(limit, offset); var bank = tenants.get(bankId, false);
        if (!Set.of("OPEN", "DISMISSED", "ESCALATED", "ALL").contains(status)) { throw ScreeningService.error(400, "INVALID_REQUEST", "Unknown status"); }
        String where = " WHERE m.tenant_id=?" + (status.equals("ALL") ? "" : " AND m.status=?");
        var args = new ArrayList<Object>(List.of(bankId));
        if (!status.equals("ALL")) { args.add(status); }
        long total = jdbc.queryForObject("SELECT count(*) FROM platform.monitoring_alert m" + where, Long.class, args.toArray());
        args.add(limit); args.add(offset);
        var items = jdbc.queryForList("SELECT m.id,m.rule,m.account_id,a.account_number,m.customer_id,r.customer_number,m.currency,m.business_date,m.amount,m.status,m.version,m.case_id,m.created_at "
                + "FROM platform.monitoring_alert m JOIN platform.account_registry a ON a.id=m.account_id JOIN platform.customer_registry r ON r.id=m.customer_id"
                + where + " ORDER BY m.created_at DESC,m.id LIMIT ? OFFSET ?", args.toArray());
        return Map.of("version", bank.version(), "items", items, "total", total, "limit", limit, "offset", offset);
    }

    public Map<String, Object> alert(Actor actor, UUID bankId, UUID id) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = tenants.get(bankId, false);
            var rows = jdbc.queryForList("SELECT m.*,m.details::text AS details_text,m.decision::text AS decision_text,a.account_number,r.customer_number FROM platform.monitoring_alert m "
                    + "JOIN platform.account_registry a ON a.id=m.account_id JOIN platform.customer_registry r ON r.id=m.customer_id WHERE m.tenant_id=? AND m.id=?", bankId, id);
            if (rows.isEmpty()) { throw ScreeningService.error(404, "MONITORING_ALERT_NOT_FOUND", "Monitoring alert not found"); }
            var alert = rows.getFirst();
            alert.put("details", JSON.readValue((String) alert.remove("details_text"), new TypeReference<Map<String, Object>>() { }));
            String decision = (String) alert.remove("decision_text");
            alert.put("decision", decision == null ? null : JSON.readValue(decision, new TypeReference<Map<String, Object>>() { }));
            var date = ((java.sql.Date) alert.get("business_date")).toLocalDate();
            var movements = jdbc.queryForList("SELECT source_id,direction,channel,amount,currency,business_date,recorded_at FROM platform.monitoring_event WHERE tenant_id=? AND account_id=? AND business_date BETWEEN ? AND ? ORDER BY recorded_at DESC LIMIT 100",
                    bankId, alert.get("account_id"), date.minusDays(30), date);
            tenants.audit(actor, bankId, "MONITORING_ALERT_VIEWED", bank.version(), bank.status(), bank.status(), "Monitoring evidence viewed", null, null, Map.of("alertId", id));
            return Map.of("version", bank.version(), "alert", alert, "movements", movements);
        });
    }
}
