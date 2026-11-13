package com.cbs.privacy;

import static com.cbs.privacy.PrivacyModels.*;
import com.cbs.access.AccessService;
import com.cbs.customers.CustomerService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Round 18 privacy workflows: legal holds (maker-checker), the data-subject request register with its
 * subject-access report, the retention matrix and review, and the per-customer view log.
 *
 * <p>Nothing here deletes or rewrites records. Ledgers, events and audit stay immutable; erasure is
 * assessed and, while a retention duty or hold applies, refused with the reason.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class PrivacyService {
    /** Proposed for the pilot from the AML record-keeping duty (POCAMLA s.46); the bank's DPO confirms it. */
    public static final int CUSTOMER_RECORD_YEARS = 7;
    /** Audit actions that show someone saw a customer's personal data. */
    static final List<String> VIEW_ACTIONS = List.of("CUSTOMER_PROFILE_VIEWED","PRIVACY_ACCESS_REPORT_EXPORTED");
    static final List<RetentionRule> MATRIX = List.of(
        new RetentionRule("Customer identification (KYC)","Customer profile, identity document number and reference, parties, mandates",CUSTOMER_RECORD_YEARS,"End of the customer relationship","POCAMLA s.46; Data Protection Act 2019 s.39","Anonymize after DPO approval unless a legal hold applies"),
        new RetentionRule("Transactions and accounting","Accounts, ledger and journal entries, funding, operations, cash, payments, contracts, statements",CUSTOMER_RECORD_YEARS,"Date of the transaction","POCAMLA s.46; Companies Act / Tax Procedures Act books of account","Archive; never altered (immutable evidence)"),
        new RetentionRule("AML screening and monitoring","Screening hits, monitoring alerts, compliance cases, STR drafts",CUSTOMER_RECORD_YEARS,"Closure of the case or alert","POCAMLA s.46; FRC guidance","Archive; never disclosed to the customer (tipping-off)"),
        new RetentionRule("Audit and access logs","Tenant audit, approval history, identity audit, customer view log",CUSTOMER_RECORD_YEARS,"Date of the event","CBK Prudential Guidelines; Data Protection Act 2019 s.41 accountability","Archive"),
        new RetentionRule("Customer notifications","Alert destination and message text in the outbox",2,"Date sent","Data Protection Act 2019 s.25(e) storage limitation","Purge message text and destination"),
        new RetentionRule("Data-subject requests","Request register and closing notes",3,"Closure of the request","Data Protection Act 2019 s.41 accountability","Archive"),
        new RetentionRule("Staff sessions","Portal sessions and sign-in records",1,"End of the session","Data Protection Act 2019 s.25(e)","Purge"));

    private final JdbcTemplate jdbc; private final TenantRepository tenants; private final CustomerService customers;
    private final TransactionTemplate tx; private final int dueDays;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public PrivacyService(JdbcTemplate jdbc, TenantRepository tenants, CustomerService customers, PlatformTransactionManager manager,
            @Value("${cbs.privacy.request-due-days:7}") int dueDays) {
        this.jdbc = jdbc; this.tenants = tenants; this.customers = customers; this.tx = new TransactionTemplate(manager);
        if (dueDays < 1 || dueDays > 90) throw new IllegalStateException("cbs.privacy.request-due-days must be between 1 and 90");
        this.dueDays = dueDays;
    }

    private static LocalDate today() { return LocalDate.now(ZoneOffset.UTC); }
    static TenantException error(int status, String code) { return new TenantException(status, code, "Privacy operation could not be completed"); }

    private String customerNumber(UUID bank, UUID customer) {
        var rows = jdbc.queryForList("SELECT customer_number FROM platform.customer_registry WHERE tenant_id=? AND id=?", String.class, bank, customer);
        if (rows.isEmpty()) throw error(404, "CUSTOMER_NOT_FOUND");
        return rows.getFirst();
    }
    private Tenant shared(UUID bank) { jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE", r -> { }, bank); return tenants.get(bank, false); }

    // ---- Legal holds (maker-checker: ApprovalService action LEGAL_HOLD) ----

    public boolean held(UUID bank, UUID customer) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.legal_hold WHERE tenant_id=? AND customer_id=? AND active)", Boolean.class, bank, customer);
    }
    public void validate(Tenant bank, Hold h) {
        if (bank.version() != h.expectedVersion()) throw error(409, "STALE_VERSION");
        if (bank.status() != Status.ACTIVE) throw error(409, "PRIVACY_BANK_NOT_ACTIVE");
        customerNumber(bank.id(), h.customerId());
        boolean held = held(bank.id(), h.customerId());
        if (h.active() && held) throw error(409, "LEGAL_HOLD_ALREADY_ACTIVE");
        if (!h.active() && !held) throw error(409, "LEGAL_HOLD_NOT_ACTIVE");
    }
    public Map<String, Object> before(UUID bank, Hold h) {
        var rows = jdbc.queryForList("SELECT reference FROM platform.legal_hold WHERE tenant_id=? AND customer_id=? AND active", String.class, bank, h.customerId());
        return rows.isEmpty() ? Map.of("legalHold", "none") : Map.of("legalHold", "active", "reference", rows.getFirst());
    }
    public Map<String, Object> values(UUID bank, Hold h) {
        var m = new LinkedHashMap<String, Object>();
        m.put("customer", customerNumber(bank, h.customerId())); m.put("legalHold", h.active() ? "active" : "released");
        m.put("reference", h.reference()); m.put("reason", h.reason()); return m;
    }
    public void apply(Actor actor, UUID bankId, Hold h, UUID approvalId) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, true); validate(bank, h);
        jdbc.update("""
                INSERT INTO platform.legal_hold(tenant_id,customer_id,active,reference,reason,approval_id) VALUES (?,?,?,?,?,?)
                ON CONFLICT(tenant_id,customer_id) DO UPDATE SET active=excluded.active,reference=excluded.reference,reason=excluded.reason,approval_id=excluded.approval_id,updated_at=clock_timestamp()
                """, bankId, h.customerId(), h.active(), h.reference(), h.reason(), approvalId);
        tenants.change(bank, bank.status(), bank.displayName(), bank.defaultLanguage());
        tenants.audit(actor, bankId, h.active() ? "LEGAL_HOLD_PLACED" : "LEGAL_HOLD_RELEASED", bank.version() + 1, bank.status(), bank.status(),
                "Approved request " + approvalId, null, null, Map.of("customerId", h.customerId(), "requestId", approvalId, "reference", h.reference()));
    }
    public List<HoldView> holds(Actor actor, UUID bank) {
        AccessService.admin(actor); tenants.get(bank, false);
        return jdbc.query("SELECT h.*,r.customer_number FROM platform.legal_hold h JOIN platform.customer_registry r ON r.id=h.customer_id WHERE h.tenant_id=? AND h.active ORDER BY r.customer_number",
                (r, n) -> new HoldView(r.getObject("customer_id", UUID.class), r.getString("customer_number"), r.getString("reference"), r.getString("reason"),
                        r.getObject("approval_id", UUID.class), r.getTimestamp("updated_at").toInstant()), bank);
    }

    // ---- Data-subject requests ----

    public SubjectRequestView register(Actor actor, UUID bankId, UUID key, SubjectRequest input) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = shared(bankId);
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", r -> { }, actor.issuer() + ":" + actor.subject() + ":" + key);
            String payload = JSON.writeValueAsString(input);
            var existing = jdbc.query("SELECT id,tenant_id,payload=?::jsonb AS same FROM platform.data_subject_request WHERE created_issuer=? AND created_subject=? AND idempotency_key=?",
                    (r, n) -> r.getBoolean("same") && r.getObject("tenant_id", UUID.class).equals(bankId) ? r.getObject("id", UUID.class) : null, payload, actor.issuer(), actor.subject(), key);
            if (!existing.isEmpty()) {
                if (existing.getFirst() == null) throw error(409, "IDEMPOTENCY_CONFLICT");
                return find(bankId, existing.getFirst());
            }
            if (bank.status() != Status.ACTIVE) throw error(409, "PRIVACY_BANK_NOT_ACTIVE");
            customerNumber(bankId, input.customerId());
            if (input.receivedOn().isAfter(today()) || input.receivedOn().isBefore(today().minusYears(1))) throw error(400, "PRIVACY_REQUEST_DATE_INVALID");
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO platform.data_subject_request(id,tenant_id,customer_id,request_type,received_on,due_on,reference,details,created_issuer,created_subject,idempotency_key,payload)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                    """, id, bankId, input.customerId(), input.type().name(), input.receivedOn(), input.receivedOn().plusDays(dueDays), input.reference(),
                    input.details().strip(), actor.issuer(), actor.subject(), key, payload);
            tenants.audit(actor, bankId, "DATA_SUBJECT_REQUEST_RECEIVED", bank.version(), bank.status(), bank.status(), "Data-subject request " + input.reference(),
                    null, null, Map.of("customerId", input.customerId(), "requestId", id, "type", input.type().name()));
            return find(bankId, id);
        });
    }
    private SubjectRequestView find(UUID bank, UUID id) {
        var rows = jdbc.query(VIEW_SQL + " WHERE d.tenant_id=? AND d.id=?", this::view, bank, id);
        if (rows.isEmpty()) throw error(404, "PRIVACY_REQUEST_NOT_FOUND");
        return rows.getFirst();
    }
    private static final String VIEW_SQL = "SELECT d.*,r.customer_number FROM platform.data_subject_request d JOIN platform.customer_registry r ON r.id=d.customer_id";
    private SubjectRequestView view(ResultSet r, int n) throws SQLException {
        var status = RequestStatus.valueOf(r.getString("status")); var due = r.getObject("due_on", LocalDate.class);
        return new SubjectRequestView(r.getObject("id", UUID.class), r.getObject("customer_id", UUID.class), r.getString("customer_number"),
                RequestType.valueOf(r.getString("request_type")), r.getObject("received_on", LocalDate.class), due, status,
                status == RequestStatus.OPEN && due.isBefore(today()), r.getString("reference"), r.getString("details"), r.getString("outcome_code"),
                r.getString("closing_note"), r.getString("created_subject"), r.getTimestamp("created_at").toInstant(), instant(r, "report_exported_at"),
                r.getString("closed_subject"), instant(r, "closed_at"));
    }
    private static Instant instant(ResultSet r, String column) throws SQLException { var t = r.getTimestamp(column); return t == null ? null : t.toInstant(); }

    public List<SubjectRequestView> requests(Actor actor, UUID bank, RequestStatus status, boolean overdue, UUID customer) {
        AccessService.admin(actor); tenants.get(bank, false);
        String sql = VIEW_SQL + " WHERE d.tenant_id=?"; List<Object> args = new ArrayList<>(List.of(bank));
        if (status != null) { sql += " AND d.status=?"; args.add(status.name()); }
        if (overdue) { sql += " AND d.status='OPEN' AND d.due_on<?"; args.add(today()); }
        if (customer != null) { sql += " AND d.customer_id=?"; args.add(customer); }
        return jdbc.query(sql + " ORDER BY d.due_on,d.created_at LIMIT 200", this::view, args.toArray());
    }

    public SubjectRequestView close(Actor actor, UUID bankId, UUID id, Close input) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = shared(bankId);
            var rows = jdbc.queryForList("SELECT request_type,status,customer_id,report_exported_at IS NOT NULL AS exported FROM platform.data_subject_request WHERE tenant_id=? AND id=? FOR UPDATE", bankId, id);
            if (rows.isEmpty()) throw error(404, "PRIVACY_REQUEST_NOT_FOUND");
            var row = rows.getFirst(); var type = RequestType.valueOf((String) row.get("request_type"));
            if (!"OPEN".equals(row.get("status"))) throw error(409, "PRIVACY_REQUEST_CLOSED");
            if (input.outcome() == RequestStatus.OPEN) throw error(400, "PRIVACY_OUTCOME_INVALID");
            if (input.outcome() == RequestStatus.COMPLETED) {
                // Access and portability are answered with the exported report; erasure is never carried out by closing a request.
                if ((type == RequestType.ACCESS || type == RequestType.PORTABILITY) && !Boolean.TRUE.equals(row.get("exported"))) throw error(409, "PRIVACY_REPORT_NOT_EXPORTED");
                if (type == RequestType.ERASURE) throw error(409, "PRIVACY_ERASURE_NOT_AVAILABLE");
            }
            jdbc.update("UPDATE platform.data_subject_request SET status=?,outcome_code=?,closing_note=?,closed_subject=?,closed_at=clock_timestamp() WHERE id=?",
                    input.outcome().name(), input.code(), input.note(), actor.subject(), id);
            tenants.audit(actor, bankId, "DATA_SUBJECT_REQUEST_CLOSED", bank.version(), bank.status(), bank.status(), input.note(), null, null,
                    Map.of("customerId", row.get("customer_id"), "requestId", id, "outcome", input.outcome().name(), "code", input.code()));
            return find(bankId, id);
        });
    }

    /**
     * Subject-access report for an open access or portability request: the customer's own personal data
     * and relationship. AML screening, monitoring and case data are left out on purpose (tipping-off).
     */
    public Map<String, Object> report(Actor actor, UUID bankId, UUID id) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = shared(bankId); var request = find(bankId, id);
            if (request.type() != RequestType.ACCESS && request.type() != RequestType.PORTABILITY) throw error(409, "PRIVACY_REPORT_NOT_APPLICABLE");
            if (request.status() != RequestStatus.OPEN) throw error(409, "PRIVACY_REQUEST_CLOSED");
            UUID customer = request.customerId();
            var out = new LinkedHashMap<String, Object>();
            out.put("generatedAt", Instant.now().toString()); out.put("bank", bank.displayName()); out.put("request", request.reference());
            out.put("customer", customers.protectedView(bankId, customer));
            out.put("accounts", jdbc.queryForList("SELECT account_number AS \"accountNumber\",currency,state FROM platform.account_registry WHERE tenant_id=? AND customer_id=? ORDER BY account_number", bankId, customer));
            out.put("relatedParties", jdbc.queryForList("""
                    SELECT o.customer_number AS "organization",p.customer_number AS "person",cp.role FROM platform.customer_party cp
                    JOIN platform.customer_registry o ON o.id=cp.organization_id JOIN platform.customer_registry p ON p.id=cp.person_id
                    WHERE cp.tenant_id=? AND (cp.organization_id=? OR cp.person_id=?) ORDER BY 1,2,3
                    """, bankId, customer, customer));
            out.put("notificationPreference", jdbc.queryForList("SELECT channel,destination,language,events::text AS events,consent_reference AS \"consentReference\" FROM platform.notification_preference WHERE tenant_id=? AND customer_id=?", bankId, customer));
            out.put("dataSubjectRequests", jdbc.queryForList("SELECT reference,request_type AS type,received_on::text AS \"receivedOn\",status FROM platform.data_subject_request WHERE tenant_id=? AND customer_id=? ORDER BY received_on,created_at", bankId, customer));
            out.put("retention", MATRIX);
            jdbc.update("UPDATE platform.data_subject_request SET report_exported_at=coalesce(report_exported_at,clock_timestamp()) WHERE id=?", id);
            tenants.audit(actor, bankId, "PRIVACY_ACCESS_REPORT_EXPORTED", bank.version(), bank.status(), bank.status(), "Subject-access report for " + request.reference(),
                    null, null, Map.of("customerId", customer, "requestId", id));
            return out;
        });
    }

    // ---- Retention ----

    public List<RetentionRule> matrix() { return MATRIX; }

    private static final String RELATIONSHIP_SQL = """
            SELECT c.id,c.customer_number,
             EXISTS(SELECT 1 FROM platform.account_registry a WHERE a.tenant_id=c.tenant_id AND a.customer_id=c.id AND a.state<>'CLOSED') AS open,
             GREATEST((SELECT max(d.completed_at) FROM platform.account_delivery d JOIN platform.account_registry a ON a.id=d.account_id WHERE a.tenant_id=c.tenant_id AND a.customer_id=c.id),
                      (SELECT max(cd.completed_at) FROM platform.customer_delivery cd WHERE cd.tenant_id=c.tenant_id AND cd.customer_id=c.id)) AS ended,
             EXISTS(SELECT 1 FROM platform.legal_hold h WHERE h.tenant_id=c.tenant_id AND h.customer_id=c.id AND h.active) AS held,
             EXISTS(SELECT 1 FROM platform.compliance_case k WHERE k.tenant_id=c.tenant_id AND k.customer_id=c.id AND k.status<>'CLEARED') AS cased
            FROM platform.customer_registry c WHERE c.tenant_id=?""";

    /** Blockers for erasing a customer's data now: legal hold, open relationship, open AML case, retention period. */
    public ErasureAssessment assess(Actor actor, UUID bank, UUID customer) {
        AccessService.admin(actor); tenants.get(bank, false); customerNumber(bank, customer);
        return jdbc.query(RELATIONSHIP_SQL + " AND c.id=?", (r, n) -> {
            var blockers = new ArrayList<String>();
            LocalDate ended = r.getBoolean("open") || r.getTimestamp("ended") == null ? null : r.getTimestamp("ended").toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate until = ended == null ? null : ended.plusYears(CUSTOMER_RECORD_YEARS);
            if (r.getBoolean("held")) blockers.add("LEGAL_HOLD");
            if (r.getBoolean("open")) blockers.add("RELATIONSHIP_OPEN");
            if (r.getBoolean("cased")) blockers.add("COMPLIANCE_CASE_OPEN");
            if (until != null && !until.isBefore(today())) blockers.add("RETENTION_PERIOD");
            return new ErasureAssessment(customer, ended, until, blockers);
        }, bank, customer).getFirst();
    }

    /** Customers whose relationship ended more than the retention period ago and who are not on hold: due for archival review. */
    public List<RetentionCandidate> candidates(Actor actor, UUID bank) {
        AccessService.admin(actor); tenants.get(bank, false);
        return jdbc.query("SELECT * FROM (" + RELATIONSHIP_SQL + ") x WHERE NOT open AND NOT held AND NOT cased AND ended IS NOT NULL AND ended < ? ORDER BY ended LIMIT 500", (r, n) -> {
            var ended = r.getTimestamp("ended").toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            return new RetentionCandidate(r.getObject("id", UUID.class), r.getString("customer_number"), ended, ended.plusYears(CUSTOMER_RECORD_YEARS));
        }, bank, java.sql.Timestamp.from(today().minusYears(CUSTOMER_RECORD_YEARS).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    // ---- View log ----

    /** Who saw this customer's record, newest first. Reading the log is itself recorded. */
    public List<View> views(Actor actor, UUID bankId, UUID customer, int limit) {
        AccessService.admin(actor); AccessService.page(limit, 0);
        return tx.execute(t -> {
            var bank = shared(bankId); customerNumber(bankId, customer);
            var rows = jdbc.query(c -> {
                var s = c.prepareStatement("SELECT actor_subject,action,occurred_at,correlation_id FROM platform.tenant_audit WHERE tenant_id=? AND change_details ?? 'customerId' AND change_details->>'customerId'=? AND action=ANY(?) ORDER BY occurred_at DESC LIMIT ?");
                s.setObject(1, bankId); s.setString(2, customer.toString()); s.setArray(3, c.createArrayOf("varchar", VIEW_ACTIONS.toArray())); s.setInt(4, limit); return s;
            }, (r, n) -> new View(r.getString(1), r.getString(2), r.getTimestamp(3).toInstant(), r.getObject(4, UUID.class)));
            tenants.audit(actor, bankId, "CUSTOMER_VIEW_LOG_READ", bank.version(), bank.status(), bank.status(), "Customer view log read", null, null, Map.of("subjectCustomerId", customer));
            return rows;
        });
    }
}
