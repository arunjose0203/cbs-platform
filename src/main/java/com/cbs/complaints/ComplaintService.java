package com.cbs.complaints;

import static com.cbs.complaints.ComplaintModels.*;
import com.cbs.access.AccessService;
import com.cbs.access.BankDuties;
import com.cbs.privacy.PersonalDataCipher;
import com.cbs.reporting.TableOutput;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantModels.Tenant;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Round 20 complaints and disputes: a register per bank, handling steps recorded as append-only events, and
 * closure only through an approved resolution (maker-checker, action {@code COMPLAINT_RESOLUTION}).
 *
 * <p>Money never moves here. A refund or compensation is decided in the resolution and paid through the
 * normal money paths (account operation, journal, fee reversal) with their own approvals; the complaint
 * then records which approved request paid it. Until that link exists the complaint shows as redress pending.
 *
 * <p>Dates are the bank's own calendar dates (its business time zone), not UTC. The customer's account of
 * events and every handling note are sealed with the bank's field key.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ComplaintService {
    /** Approved requests that can pay a refund or compensation. */
    static final Set<String> REDRESS_ACTIONS = Set.of("ACCOUNT_OPERATION", "ACCOUNT_FUNDING", "JOURNAL_POST", "CASH_OPERATION", "LOAN_FEE", "CONTRACT_CORRECTION", "PAYMENT_ORDER");
    static final int MAX_LEVEL = 3, REGISTER_ROWS = 10_000, BACKDATE_DAYS = 365;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String VIEW_SQL = """
            SELECT c.*,r.customer_number,a.account_number,
             EXISTS(SELECT 1 FROM platform.approval_request q WHERE q.bank_id=c.tenant_id AND q.action='COMPLAINT_RESOLUTION'
                    AND q.status='PENDING' AND q.payload->>'complaintId'=c.id::text) AS resolution_pending
            FROM platform.complaint c JOIN platform.customer_registry r ON r.id=c.customer_id
            LEFT JOIN platform.account_registry a ON a.id=c.account_id""";

    private final JdbcTemplate jdbc; private final TenantRepository tenants; private final AccessService access; private final BankDuties duties;
    private final PersonalDataCipher cipher; private final TransactionTemplate tx; private final int acknowledgeDays, resolveDays;

    public ComplaintService(JdbcTemplate jdbc, TenantRepository tenants, AccessService access, BankDuties duties, PersonalDataCipher cipher,
            PlatformTransactionManager manager, @Value("${cbs.complaints.acknowledge-days:2}") int acknowledgeDays,
            @Value("${cbs.complaints.resolve-days:14}") int resolveDays) {
        this.jdbc = jdbc; this.tenants = tenants; this.access = access; this.duties = duties; this.cipher = cipher; this.tx = new TransactionTemplate(manager);
        if (acknowledgeDays < 0 || acknowledgeDays > 30) throw new IllegalStateException("cbs.complaints.acknowledge-days must be between 0 and 30");
        if (resolveDays < 1 || resolveDays > 180 || resolveDays < acknowledgeDays) throw new IllegalStateException("cbs.complaints.resolve-days must be between 1 and 180 and not shorter than the acknowledgement period");
        this.acknowledgeDays = acknowledgeDays; this.resolveDays = resolveDays;
    }

    static TenantException error(int status, String code) { return new TenantException(status, code, "Complaint operation could not be completed"); }

    /** Today's date where the bank operates. Deadlines are calendar dates for the customer, so UTC would be wrong at night in East Africa. */
    LocalDate today(UUID bank) {
        var zones = jdbc.queryForList("SELECT business_timezone FROM platform.bank_settings WHERE tenant_id=?", String.class, bank);
        return LocalDate.now(zones.isEmpty() ? ZoneOffset.UTC : ZoneId.of(zones.getFirst()));
    }
    private Tenant shared(UUID bank) { jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE", r -> { }, bank); return tenants.get(bank, false); }
    private String seal(UUID bank, String text) {
        // A value that already looks sealed would be stored as typed and then fail to open.
        if (PersonalDataCipher.sealed(text)) throw error(400, "COMPLAINT_TEXT_INVALID");
        return cipher.seal(bank, text.strip());
    }
    private static BigDecimal amount(String amount, String currency, String code) {
        if ((amount == null) != (currency == null)) throw error(400, code);
        if (amount == null) return null;
        var value = new BigDecimal(amount);
        if (value.signum() <= 0) throw error(400, code);
        return value;
    }
    private static String text(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    // ---- Register ----

    public ComplaintView register(Actor actor, UUID bankId, UUID key, Register input) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = shared(bankId);
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", r -> { }, actor.issuer() + ":" + actor.subject() + ":" + key);
            // The narrative is personal data, so the retry check keeps a digest of the request, not the request.
            String digest = sha256(JSON.writeValueAsString(input));
            var existing = jdbc.query("SELECT id,tenant_id,payload_sha256 FROM platform.complaint WHERE created_issuer=? AND created_subject=? AND idempotency_key=?",
                    (r, n) -> r.getString("payload_sha256").equals(digest) && r.getObject("tenant_id", UUID.class).equals(bankId) ? r.getObject("id", UUID.class) : null,
                    actor.issuer(), actor.subject(), key);
            if (!existing.isEmpty()) {
                if (existing.getFirst() == null) throw error(409, "IDEMPOTENCY_CONFLICT");
                return find(bankId, existing.getFirst(), today(bankId));
            }
            if (bank.status() != TenantModels.Status.ACTIVE) throw error(409, "COMPLAINT_BANK_NOT_ACTIVE");
            if (!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.customer_registry WHERE tenant_id=? AND id=?)", Boolean.class, bankId, input.customerId()))
                throw error(404, "CUSTOMER_NOT_FOUND");
            if (input.accountId() != null && !jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_registry WHERE tenant_id=? AND id=? AND customer_id=?)",
                    Boolean.class, bankId, input.accountId(), input.customerId())) throw error(409, "COMPLAINT_ACCOUNT_MISMATCH");
            LocalDate today = today(bankId);
            if (input.receivedOn().isAfter(today) || input.receivedOn().isBefore(today.minusDays(BACKDATE_DAYS))) throw error(400, "COMPLAINT_DATE_INVALID");
            BigDecimal disputed = amount(input.disputedAmount(), input.disputedCurrency(), "COMPLAINT_AMOUNT_INVALID");
            int number = jdbc.queryForObject("""
                    INSERT INTO platform.complaint_counter(tenant_id,year,last) VALUES (?,?,1)
                    ON CONFLICT(tenant_id,year) DO UPDATE SET last=complaint_counter.last+1 RETURNING last
                    """, Integer.class, bankId, today.getYear());
            String reference = String.format(Locale.ROOT, "CMP-%d-%06d", today.getYear(), number);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO platform.complaint(id,tenant_id,reference,customer_id,account_id,channel,category,summary,details,transaction_reference,
                     disputed_amount,disputed_currency,received_on,acknowledge_due,resolve_due,created_issuer,created_subject,idempotency_key,payload_sha256)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, bankId, reference, input.customerId(), input.accountId(), input.channel().name(), input.category().name(), input.summary().strip(),
                    seal(bankId, input.details()), input.transactionReference(), disputed, input.disputedCurrency(), input.receivedOn(),
                    input.receivedOn().plusDays(acknowledgeDays), input.receivedOn().plusDays(resolveDays), actor.issuer(), actor.subject(), key, digest);
            event(actor, bankId, id, "RECEIVED", "Complaint registered", Map.of("channel", input.channel().name(), "category", input.category().name()), 0);
            audit(actor, bank, "COMPLAINT_RECEIVED", reference, input.customerId(), id, Map.of("category", input.category().name()));
            return find(bankId, id, today);
        });
    }

    // ---- Handling steps (each on the complaint version the operator saw) ----

    public ComplaintView acknowledge(Actor actor, UUID bankId, UUID id, Step input) {
        return step(actor, bankId, id, input.caseVersion(), row -> {
            open(row);
            if (row.get("acknowledged_at") != null) throw error(409, "COMPLAINT_ALREADY_ACKNOWLEDGED");
            jdbc.update("UPDATE platform.complaint SET acknowledged_at=clock_timestamp(),acknowledged_on=?,version=version+1 WHERE id=?", today(bankId), id);
            return new Change("ACKNOWLEDGED", "COMPLAINT_ACKNOWLEDGED", input.note(), Map.of());
        });
    }

    /** Hands an open complaint to an active staff identity that holds the customer-operations duty where duties are enforced. */
    public ComplaintView assign(Actor actor, UUID bankId, UUID id, Assign input) {
        return step(actor, bankId, id, input.caseVersion(), row -> {
            open(row);
            access.requireAssignable(actor.issuer(), input.assignee());
            if (!duties.holds(bankId, actor.issuer(), input.assignee(), BankDuties.Duty.CUSTOMER_OPERATIONS)) throw error(409, "COMPLAINT_ASSIGNEE_NOT_AUTHORIZED");
            jdbc.update("UPDATE platform.complaint SET assignee=?,version=version+1 WHERE id=?", input.assignee(), id);
            return new Change("ASSIGNED", "COMPLAINT_ASSIGNED", input.note(), Map.of("assignee", input.assignee(), "previousAssignee", String.valueOf(row.get("assignee"))));
        });
    }

    public ComplaintView note(Actor actor, UUID bankId, UUID id, Step input) {
        return step(actor, bankId, id, input.caseVersion(), row -> {
            jdbc.update("UPDATE platform.complaint SET version=version+1 WHERE id=?", id);
            return new Change("NOTE", "COMPLAINT_NOTE_ADDED", input.note(), Map.of());
        });
    }

    public ComplaintView escalate(Actor actor, UUID bankId, UUID id, Step input) {
        return step(actor, bankId, id, input.caseVersion(), row -> {
            open(row);
            int level = ((Number) row.get("escalation_level")).intValue();
            if (level >= MAX_LEVEL) throw error(409, "COMPLAINT_ESCALATION_LIMIT");
            jdbc.update("UPDATE platform.complaint SET escalation_level=?,version=version+1 WHERE id=?", level + 1, id);
            return new Change("ESCALATED", "COMPLAINT_ESCALATED", input.note(), Map.of("level", level + 1));
        });
    }

    /**
     * Reopens a resolved complaint when the customer does not accept the outcome. The earlier resolution stays in
     * the history; the complaint moves one level up and gets a new resolution date counted from today.
     */
    public ComplaintView reopen(Actor actor, UUID bankId, UUID id, Step input) {
        return step(actor, bankId, id, input.caseVersion(), row -> {
            if (!"RESOLVED".equals(row.get("status"))) throw error(409, "COMPLAINT_NOT_RESOLVED");
            if (tenants.get(bankId, false).status() != TenantModels.Status.ACTIVE) throw error(409, "COMPLAINT_BANK_NOT_ACTIVE");
            int level = Math.min(MAX_LEVEL, ((Number) row.get("escalation_level")).intValue() + 1);
            LocalDate due = today(bankId).plusDays(resolveDays);
            jdbc.update("""
                    UPDATE platform.complaint SET status='OPEN',outcome=NULL,redress=NULL,redress_amount=NULL,redress_currency=NULL,root_cause=NULL,
                     resolved_at=NULL,resolved_on=NULL,resolution_approval_id=NULL,redress_request_id=NULL,resolve_due=?,escalation_level=?,
                     reopened_count=reopened_count+1,version=version+1 WHERE id=?
                    """, due, level, id);
            return new Change("REOPENED", "COMPLAINT_REOPENED", input.note(), Map.of("previousOutcome", String.valueOf(row.get("outcome")), "resolveDue", due.toString(), "level", level));
        });
    }

    /**
     * Records which approved money request paid a refund or compensation. The request must belong to this bank,
     * be approved, be a money action, be newer than the complaint and not already used for another complaint.
     * The amount is not compared: the payload shapes differ per action, so the checker of that request owns it.
     */
    public ComplaintView redressPosting(Actor actor, UUID bankId, UUID id, RedressPosting input) {
        return step(actor, bankId, id, input.caseVersion(), row -> {
            if (!"RESOLVED".equals(row.get("status")) || !Redress.valueOf((String) row.get("redress")).monetary()) throw error(409, "COMPLAINT_REDRESS_NOT_DUE");
            if (row.get("redress_request_id") != null) throw error(409, "COMPLAINT_REDRESS_ALREADY_POSTED");
            var requests = jdbc.queryForList("SELECT action,status,created_at>=? AS newer FROM platform.approval_request WHERE id=? AND bank_id=?",
                    row.get("created_at"), input.requestId(), bankId);
            if (requests.isEmpty()) throw error(404, "COMPLAINT_REDRESS_REQUEST_NOT_FOUND");
            var request = requests.getFirst();
            if (jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.complaint_event WHERE action='REDRESS_POSTED' AND details->>'requestId'=?)", Boolean.class, input.requestId().toString()))
                throw error(409, "COMPLAINT_REDRESS_REQUEST_USED");
            if (!"APPROVED".equals(request.get("status")) || !REDRESS_ACTIONS.contains((String) request.get("action")) || !Boolean.TRUE.equals(request.get("newer")))
                throw error(409, "COMPLAINT_REDRESS_REQUEST_INVALID");
            jdbc.update("UPDATE platform.complaint SET redress_request_id=?,version=version+1 WHERE id=?", input.requestId(), id);
            return new Change("REDRESS_POSTED", "COMPLAINT_REDRESS_POSTED", input.note(), Map.of("requestId", input.requestId().toString(), "action", request.get("action")));
        });
    }

    private record Change(String event, String audit, String note, Map<String, Object> details) { }
    private interface StepBody { Change apply(Map<String, Object> row); }

    private ComplaintView step(Actor actor, UUID bankId, UUID id, long caseVersion, StepBody body) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = shared(bankId);
            var row = locked(bankId, id);
            if (((Number) row.get("version")).longValue() != caseVersion) throw error(409, "COMPLAINT_STALE");
            // A proposed resolution names this version; changing the complaint under it would make the approval fail.
            if ("OPEN".equals(row.get("status")) && resolutionPending(bankId, id)) throw error(409, "COMPLAINT_RESOLUTION_PENDING");
            var change = body.apply(row);
            event(actor, bankId, id, change.event(), change.note(), change.details(), caseVersion + 1);
            audit(actor, bank, change.audit(), (String) row.get("reference"), (UUID) row.get("customer_id"), id, Map.of());
            return find(bankId, id, today(bankId));
        });
    }
    private Map<String, Object> locked(UUID bankId, UUID id) {
        var rows = jdbc.queryForList("SELECT * FROM platform.complaint WHERE tenant_id=? AND id=? FOR UPDATE", bankId, id);
        if (rows.isEmpty()) throw error(404, "COMPLAINT_NOT_FOUND");
        return rows.getFirst();
    }
    private static void open(Map<String, Object> row) { if (!"OPEN".equals(row.get("status"))) throw error(409, "COMPLAINT_NOT_OPEN"); }
    private void event(Actor actor, UUID bankId, UUID id, String action, String note, Map<String, Object> details, long version) {
        jdbc.update("INSERT INTO platform.complaint_event(id,complaint_id,tenant_id,action,actor_issuer,actor_subject,note,details,complaint_version) VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
                UUID.randomUUID(), id, bankId, action, actor.issuer(), actor.subject(), seal(bankId, note), JSON.writeValueAsString(details), version);
    }
    private void audit(Actor actor, Tenant bank, String action, String reference, UUID customer, UUID id, Map<String, Object> more) {
        var details = new LinkedHashMap<String, Object>(more);
        if (customer != null) details.put("customerId", customer);
        if (id != null) details.put("complaintId", id);
        details.put("reference", reference);
        tenants.audit(actor, bank.id(), action, bank.version(), bank.status(), bank.status(), "Complaint " + reference, null, null, details);
    }

    // ---- Resolution (maker-checker: ApprovalService action COMPLAINT_RESOLUTION) ----

    /** Checks made when the resolution is proposed and again, on the locked complaint, when it is approved. */
    public void validate(Tenant bank, Resolution r) {
        if (bank.version() != r.expectedVersion()) throw error(409, "STALE_VERSION");
        if (bank.status() != TenantModels.Status.ACTIVE) throw error(409, "COMPLAINT_BANK_NOT_ACTIVE");
        var row = locked(bank.id(), r.complaintId());
        open(row);
        if (((Number) row.get("version")).longValue() != r.caseVersion()) throw error(409, "COMPLAINT_STALE");
        boolean valid = switch (r.outcome()) {
            case UPHELD, PARTLY_UPHELD -> r.redress() != Redress.NONE;
            case NOT_UPHELD -> r.redress() == Redress.NONE || r.redress() == Redress.EXPLANATION;
            case WITHDRAWN -> r.redress() == Redress.NONE;
        };
        if (!valid) throw error(400, "COMPLAINT_RESOLUTION_INVALID");
        BigDecimal redress = amount(r.redressAmount(), r.redressCurrency(), "COMPLAINT_REDRESS_INVALID");
        if (r.redress().monetary() != (redress != null)) throw error(400, "COMPLAINT_REDRESS_INVALID");
        if (PersonalDataCipher.sealed(r.response())) throw error(400, "COMPLAINT_TEXT_INVALID");
    }
    /** Only one proposed resolution can wait for a decision on a complaint. */
    public void validateNew(Tenant bank, Resolution r) {
        validate(bank, r);
        if (resolutionPending(bank.id(), r.complaintId())) throw error(409, "COMPLAINT_RESOLUTION_PENDING");
    }
    private boolean resolutionPending(UUID bank, UUID id) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.approval_request WHERE bank_id=? AND action='COMPLAINT_RESOLUTION' AND status='PENDING' AND payload->>'complaintId'=?)",
                Boolean.class, bank, id.toString());
    }
    public Map<String, Object> before(UUID bank, Resolution r) {
        var row = jdbc.queryForMap("SELECT reference,status,escalation_level,assignee,received_on,resolve_due FROM platform.complaint WHERE tenant_id=? AND id=?", bank, r.complaintId());
        var m = new LinkedHashMap<String, Object>();
        m.put("complaint", row.get("reference")); m.put("status", row.get("status")); m.put("level", row.get("escalation_level"));
        m.put("assignee", row.get("assignee") == null ? "unassigned" : row.get("assignee"));
        m.put("receivedOn", row.get("received_on").toString()); m.put("resolveDue", row.get("resolve_due").toString());
        return m;
    }
    public Map<String, Object> values(UUID bank, Resolution r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("complaint", reference(bank, r.complaintId())); m.put("status", "RESOLVED"); m.put("outcome", r.outcome().name()); m.put("redress", r.redress().name());
        if (r.redressAmount() != null) { m.put("redressAmount", r.redressAmount()); m.put("redressCurrency", r.redressCurrency()); }
        m.put("rootCause", r.rootCause()); m.put("response", r.response());
        return m;
    }
    public String reference(UUID bank, UUID id) {
        var rows = jdbc.queryForList("SELECT reference FROM platform.complaint WHERE tenant_id=? AND id=?", String.class, bank, id);
        if (rows.isEmpty()) throw error(404, "COMPLAINT_NOT_FOUND");
        return rows.getFirst();
    }
    /** Closes the complaint with the approved outcome. The maker proposed it; the checker is the actor of record on the event. */
    public void apply(Actor checker, UUID bankId, Resolution r, String makerSubject, UUID approvalId) {
        AccessService.admin(checker);
        var bank = tenants.get(bankId, true); validate(bank, r);
        var row = locked(bankId, r.complaintId());
        BigDecimal redress = r.redressAmount() == null ? null : new BigDecimal(r.redressAmount());
        jdbc.update("""
                UPDATE platform.complaint SET status='RESOLVED',outcome=?,redress=?,redress_amount=?,redress_currency=?,root_cause=?,
                 resolved_at=clock_timestamp(),resolved_on=?,resolution_approval_id=?,version=version+1 WHERE id=?
                """, r.outcome().name(), r.redress().name(), redress, r.redressCurrency(), r.rootCause(), today(bankId), approvalId, r.complaintId());
        var details = new LinkedHashMap<String, Object>();
        details.put("outcome", r.outcome().name()); details.put("redress", r.redress().name());
        if (redress != null) { details.put("redressAmount", r.redressAmount()); details.put("redressCurrency", r.redressCurrency()); }
        details.put("rootCause", r.rootCause()); details.put("proposedBy", makerSubject); details.put("requestId", approvalId.toString());
        event(checker, bankId, r.complaintId(), "RESOLVED", r.response(), details, r.caseVersion() + 1);
        tenants.change(bank, bank.status(), bank.displayName(), bank.defaultLanguage());
        tenants.audit(checker, bankId, "COMPLAINT_RESOLVED", bank.version() + 1, bank.status(), bank.status(), "Approved request " + approvalId, null, null,
                Map.of("customerId", row.get("customer_id"), "complaintId", r.complaintId(), "reference", row.get("reference"), "outcome", r.outcome().name(), "requestId", approvalId));
    }

    // ---- Reading ----

    private RowMapper<ComplaintView> view(LocalDate today) {
        return (r, n) -> {
            var status = Status.valueOf(r.getString("status")); boolean open = status == Status.OPEN;
            var ackDue = r.getObject("acknowledge_due", LocalDate.class); var ackOn = r.getObject("acknowledged_on", LocalDate.class);
            var due = r.getObject("resolve_due", LocalDate.class);
            var redress = r.getString("redress") == null ? null : Redress.valueOf(r.getString("redress"));
            UUID posting = r.getObject("redress_request_id", UUID.class);
            return new ComplaintView(r.getObject("id", UUID.class), r.getString("reference"), r.getObject("customer_id", UUID.class), r.getString("customer_number"),
                    r.getObject("account_id", UUID.class), r.getString("account_number"), Channel.valueOf(r.getString("channel")), Category.valueOf(r.getString("category")),
                    r.getString("summary"), r.getString("transaction_reference"), text(r.getBigDecimal("disputed_amount")), r.getString("disputed_currency"),
                    r.getObject("received_on", LocalDate.class), ackDue, ackOn, open && ackOn == null && ackDue.isBefore(today), due, open && due.isBefore(today),
                    status, r.getInt("escalation_level"), r.getString("assignee"), r.getString("outcome") == null ? null : Outcome.valueOf(r.getString("outcome")),
                    redress, text(r.getBigDecimal("redress_amount")), r.getString("redress_currency"), r.getString("root_cause"), r.getObject("resolved_on", LocalDate.class),
                    r.getObject("resolution_approval_id", UUID.class), posting, !open && redress != null && redress.monetary() && posting == null,
                    r.getBoolean("resolution_pending"), r.getInt("reopened_count"), r.getLong("version"), r.getString("created_subject"), r.getTimestamp("created_at").toInstant());
        };
    }
    private ComplaintView find(UUID bank, UUID id, LocalDate today) {
        var rows = jdbc.query(VIEW_SQL + " WHERE c.tenant_id=? AND c.id=?", view(today), bank, id);
        if (rows.isEmpty()) throw error(404, "COMPLAINT_NOT_FOUND");
        return rows.getFirst();
    }

    public Page list(Actor actor, UUID bankId, Filter filter, Category category, UUID customer, int limit, int offset) {
        AccessService.admin(actor); AccessService.page(limit, offset);
        var bank = tenants.get(bankId, false); LocalDate today = today(bankId);
        String where = " WHERE c.tenant_id=?"; List<Object> args = new ArrayList<>(List.of(bankId));
        switch (filter) {
            case ALL -> { }
            case OPEN -> where += " AND c.status='OPEN'";
            case RESOLVED -> where += " AND c.status='RESOLVED'";
            case OVERDUE -> { where += " AND c.status='OPEN' AND (c.resolve_due<? OR (c.acknowledged_on IS NULL AND c.acknowledge_due<?))"; args.add(today); args.add(today); }
            case UNASSIGNED -> where += " AND c.status='OPEN' AND c.assignee IS NULL";
            case ESCALATED -> where += " AND c.status='OPEN' AND c.escalation_level>1";
            case REDRESS_PENDING -> where += " AND c.status='RESOLVED' AND c.redress IN ('REFUND','COMPENSATION') AND c.redress_request_id IS NULL";
        }
        if (category != null) { where += " AND c.category=?"; args.add(category.name()); }
        if (customer != null) { where += " AND c.customer_id=?"; args.add(customer); }
        long total = jdbc.queryForObject("SELECT count(*) FROM platform.complaint c" + where, Long.class, args.toArray());
        args.add(limit); args.add(offset);
        var items = jdbc.query(VIEW_SQL + where + " ORDER BY (c.status='OPEN') DESC,c.resolve_due,c.created_at,c.id LIMIT ? OFFSET ?", view(today), args.toArray());
        return new Page(bank.version(), today, total, items);
    }

    /** The complaint with the customer's own words and the handling history. Reading it is recorded, like a customer profile view. */
    public Detail detail(Actor actor, UUID bankId, UUID id) {
        AccessService.admin(actor);
        return tx.execute(t -> {
            var bank = shared(bankId); LocalDate today = today(bankId);
            var complaint = find(bankId, id, today);
            String details = cipher.open(bankId, jdbc.queryForObject("SELECT details FROM platform.complaint WHERE id=?", String.class, id));
            var events = jdbc.query("SELECT action,actor_subject,note,details::text AS details,complaint_version,occurred_at FROM platform.complaint_event WHERE complaint_id=? ORDER BY complaint_version DESC LIMIT 200",
                    (r, n) -> new EventView(r.getString("action"), r.getString("actor_subject"), cipher.open(bankId, r.getString("note")),
                            JSON.readValue(r.getString("details"), new TypeReference<LinkedHashMap<String, Object>>() { }), r.getLong("complaint_version"), r.getTimestamp("occurred_at").toInstant()), id);
            audit(actor, bank, "COMPLAINT_VIEWED", complaint.reference(), complaint.customerId(), id, Map.of());
            return new Detail(bank.version(), today, complaint, details, events);
        });
    }

    // ---- Reporting ----

    private static void period(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || from.plusDays(366).isBefore(to)) throw error(400, "COMPLAINT_PERIOD_INVALID");
    }

    /** Counts for complaints received between two dates (at most 366 days). */
    public Summary summary(Actor actor, UUID bankId, LocalDate from, LocalDate to) {
        AccessService.admin(actor); period(from, to); tenants.get(bankId, false);
        LocalDate today = today(bankId);
        var c = jdbc.queryForMap("""
                SELECT count(*) AS received,
                 count(*) FILTER (WHERE status='OPEN') AS open,
                 count(*) FILTER (WHERE status='RESOLVED') AS resolved,
                 count(*) FILTER (WHERE status='OPEN' AND resolve_due<?) AS overdue_open,
                 count(*) FILTER (WHERE acknowledged_on>acknowledge_due OR (acknowledged_on IS NULL AND status='OPEN' AND acknowledge_due<?)) AS acknowledged_late,
                 count(*) FILTER (WHERE status='RESOLVED' AND resolved_on<=resolve_due) AS within_due,
                 count(*) FILTER (WHERE status='RESOLVED' AND resolved_on>resolve_due) AS late,
                 count(*) FILTER (WHERE reopened_count>0) AS reopened,
                 count(*) FILTER (WHERE status='RESOLVED' AND redress IN ('REFUND','COMPENSATION') AND redress_request_id IS NULL) AS redress_pending
                FROM platform.complaint WHERE tenant_id=? AND received_on BETWEEN ? AND ?
                """, today, today, bankId, from, to);
        var redress = new LinkedHashMap<String, String>();
        jdbc.query("SELECT redress_currency,sum(redress_amount) FROM platform.complaint WHERE tenant_id=? AND received_on BETWEEN ? AND ? AND redress_amount IS NOT NULL GROUP BY 1 ORDER BY 1",
                r -> { redress.put(r.getString(1), text(r.getBigDecimal(2))); }, bankId, from, to);
        return new Summary(from, to, today, n(c, "received"), n(c, "open"), n(c, "resolved"), n(c, "overdue_open"), n(c, "acknowledged_late"), n(c, "within_due"),
                n(c, "late"), n(c, "reopened"), n(c, "redress_pending"), counts("category", bankId, from, to), counts("channel", bankId, from, to),
                counts("outcome", bankId, from, to), redress);
    }
    private static long n(Map<String, Object> row, String column) { return ((Number) row.get(column)).longValue(); }
    /** {@code column} is one of three fixed names, never request input. */
    private Map<String, Long> counts(String column, UUID bank, LocalDate from, LocalDate to) {
        var out = new LinkedHashMap<String, Long>();
        jdbc.query("SELECT " + column + ",count(*) FROM platform.complaint WHERE tenant_id=? AND received_on BETWEEN ? AND ? AND " + column + " IS NOT NULL GROUP BY 1 ORDER BY 1",
                r -> { out.put(r.getString(1), r.getLong(2)); }, bank, from, to);
        return out;
    }

    /**
     * The complaints register for a period as spreadsheet-safe CSV, without the customer's narrative or notes.
     * A period with more rows than the limit is refused, never cut short.
     */
    public String registerCsv(Actor actor, UUID bankId, LocalDate from, LocalDate to) {
        AccessService.admin(actor); period(from, to);
        return tx.execute(t -> {
            var bank = shared(bankId); LocalDate today = today(bankId);
            long total = jdbc.queryForObject("SELECT count(*) FROM platform.complaint WHERE tenant_id=? AND received_on BETWEEN ? AND ?", Long.class, bankId, from, to);
            if (total > REGISTER_ROWS) throw error(409, "COMPLAINT_REGISTER_TOO_LARGE");
            var rows = jdbc.query(VIEW_SQL + " WHERE c.tenant_id=? AND c.received_on BETWEEN ? AND ? ORDER BY c.received_on,c.reference", view(today), bankId, from, to).stream()
                    .map(v -> Arrays.asList(v.reference(), v.receivedOn().toString(), v.channel().name(), v.category().name(), v.customerNumber(), v.accountNumber(), v.summary(),
                            v.transactionReference(), v.disputedAmount(), v.disputedCurrency(), v.acknowledgeDue().toString(), date(v.acknowledgedOn()), v.resolveDue().toString(),
                            v.status().name(), String.valueOf(v.escalationLevel()), v.assignee(), name(v.outcome()), name(v.redress()), v.redressAmount(), v.redressCurrency(),
                            v.rootCause(), date(v.resolvedOn()), v.redressRequestId() == null ? null : v.redressRequestId().toString(), String.valueOf(v.reopenedCount()))).toList();
            audit(actor, bank, "COMPLAINT_REGISTER_EXPORTED", from + " to " + to, null, null, Map.of("rows", total));
            return TableOutput.csv(new TableOutput.Table("Complaints register", List.of(bank.displayName(), "Received " + from + " to " + to),
                    List.of("Reference", "Received", "Channel", "Category", "Customer", "Account", "Summary", "Transaction reference", "Disputed amount", "Disputed currency",
                            "Acknowledge by", "Acknowledged", "Resolve by", "Status", "Level", "Assignee", "Outcome", "Redress", "Redress amount", "Redress currency",
                            "Root cause", "Resolved", "Redress request", "Reopened"), Set.of(8, 18), rows, Set.of(), Instant.now()));
        });
    }
    private static String date(LocalDate value) { return value == null ? null : value.toString(); }
    private static String name(Enum<?> value) { return value == null ? null : value.name(); }
}
