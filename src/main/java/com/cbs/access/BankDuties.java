package com.cbs.access;

import com.cbs.approvals.ApprovalModels.Action;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantModels.Tenant;
import com.cbs.tenancy.TenantRepository;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bank-scoped duties for platform administrators (segregation of duties).
 *
 * <p>Platform administration is configuration authority across banks. When a bank enforces duties,
 * an administrator must also hold an approved, unexpired duty for that bank before submitting or
 * approving the bank's requests, and only sees the bank's requests for duties they hold. Lending,
 * payment-operator, branch and cash grants keep their own models; this class covers the
 * platform-administrator path into each bank.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class BankDuties {
    public enum Duty { ACCESS_ADMINISTRATION, BANK_CONFIGURATION, FINANCE, CUSTOMER_OPERATIONS, PAYMENTS, TREASURY, COMPLIANCE }

    /** Duties that move money or customer positions; combining them with access administration is a conflict. */
    static final Set<Duty> TRANSACTIONAL = EnumSet.of(Duty.FINANCE, Duty.CUSTOMER_OPERATIONS, Duty.PAYMENTS, Duty.TREASURY);

    private static final String TEXT = "[^\\p{Cntrl}]+";
    public record Grant(@PositiveOrZero long expectedVersion, @NotNull UUID staffId, boolean active,
            @NotNull @Size(max=7) List<@NotNull Duty> submit, @NotNull @Size(max=7) List<@NotNull Duty> approve,
            boolean audit, Instant expiresAt, @NotBlank @Size(max=500) @Pattern(regexp=TEXT) String reason) { }
    public record Policy(@PositiveOrZero long expectedVersion, boolean enforced,
            @NotBlank @Size(max=500) @Pattern(regexp=TEXT) String reason) { }
    public record Review(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String snapshotHash,
            @NotBlank @Size(max=500) @Pattern(regexp=TEXT) String statement) { }

    static final int DORMANT_DAYS = 90;
    private static final int MAXIMUM_REVIEWED_STAFF = 1000;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final AccessService access;
    private final TransactionTemplate tx;

    public BankDuties(JdbcTemplate jdbc, TenantRepository tenants, AccessService access, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.tenants=tenants; this.access=access; this.tx=new TransactionTemplate(manager);
    }

    /**
     * The duty that governs an action when it is taken on the platform-administrator path, or null when no
     * bank duty applies: platform-wide actions, and lending actions that are only ever governed by lending
     * grants. The switch is exhaustive on purpose, so a new action cannot be added without classifying it.
     */
    public static Duty duty(Action action) {
        return switch (action) {
            case BANK_SUSPEND, BANK_RESUME, STAFF_UPDATE -> null;
            case CREDIT_REVIEW, CREDIT_RELEASE, LOAN_SERVICING, LOAN_FEE -> null;
            case LENDING_ACCESS, PAYMENT_ACCESS, BANK_DUTY, BANK_DUTY_POLICY -> Duty.ACCESS_ADMINISTRATION;
            case BANK_SETTINGS, TRANSACTION_LIMIT, ACCOUNT_BRANCH, BRANCH_LIMIT, CASH_DENOMINATIONS, CASH_OPERATION,
                 DEPOSIT_PRODUCT, SERVICING_POLICY, CREDIT_POLICY, NOTIFICATION_TEMPLATE -> Duty.BANK_CONFIGURATION;
            case CHART_ACCOUNT, LEDGER_ACCOUNT, JOURNAL_POST, DAY_CLOSE, SERVICING_RUN, PERIOD_CLOSE_POLICY,
                 PERIOD_CLOSE, REPORT_DEFINITION -> Duty.FINANCE;
            case CUSTOMER_CHANGE, DEPOSIT_ACCOUNT, ACCOUNT_FUNDING, ACCOUNT_OPERATION, CUSTOMER_PARTIES, ACCOUNT_MANDATE,
                 NOTIFICATION_PREFERENCE, TERM_SERVICING, CONTRACT_OPEN, CONTRACT_EVENT, CONTRACT_CORRECTION,
                 KYC_REVIEW, CUSTOMER_DOCUMENT_ARCHIVE -> Duty.CUSTOMER_OPERATIONS;
            case PAYMENT_ORDER, PAYMENT_RECONCILE, STATEMENT_ACTION, PAYMENT_CASE -> Duty.PAYMENTS;
            case FX_BOOK, FX_CLOSING_RATE, FX_VALUATION, FX_RATE, FX_RATE_WITHDRAW, FX_TRADE, FX_REVERSAL -> Duty.TREASURY;
            case COMPLIANCE_REVIEW, SCREENING_LIST, MONITORING_POLICY, COMPLIANCE_ALERT_DISMISS, LEGAL_HOLD -> Duty.COMPLIANCE;
        };
    }

    /** Approval actions reviewed on the payment workspace; a bank duty applies only when an administrator made them. */
    public static final Set<Action> PAYMENT_SCOPED = EnumSet.of(Action.STATEMENT_ACTION, Action.PAYMENT_CASE, Action.PAYMENT_RECONCILE);

    /**
     * Duty of a stored request on the administrator path, or null when another model governs it: lending-scoped
     * requests, payment-workspace requests, account operations submitted through a branch, and cash operations
     * other than location registration. Shared by approval decisions and the delivery recheck.
     */
    public Duty storedDuty(UUID request) {
        var rows=jdbc.queryForList("SELECT action,bank_id,payload::text AS payload FROM platform.approval_request WHERE id=?",request);
        if(rows.isEmpty() || rows.getFirst().get("bank_id")==null) return null;
        Action action=Action.valueOf((String)rows.getFirst().get("action"));
        if(PAYMENT_SCOPED.contains(action)) return null;
        if(exists("SELECT EXISTS(SELECT 1 FROM platform.lending_approval_scope WHERE request_id=?)",request)) return null;
        if(action==Action.ACCOUNT_OPERATION && exists("SELECT EXISTS(SELECT 1 FROM platform.operation_scope WHERE request_id=?)",request)) return null;
        if(action==Action.CASH_OPERATION && !"REGISTER".equals(JSON.readTree((String)rows.getFirst().get("payload")).path("kind").asText())) return null;
        return duty(action);
    }

    /** Submit duty the maker of a stored request must still hold; payment-workspace requests only when an administrator made them. */
    public Duty makerDuty(UUID request) {
        var actions=jdbc.queryForList("SELECT action FROM platform.approval_request WHERE id=? AND bank_id IS NOT NULL",String.class,request);
        if(!actions.isEmpty() && PAYMENT_SCOPED.contains(Action.valueOf(actions.getFirst()))) {
            var admin=jdbc.queryForList("SELECT maker_admin FROM platform.payment_approval_scope WHERE request_id=?",Boolean.class,request);
            return !admin.isEmpty() && Boolean.TRUE.equals(admin.getFirst()) ? Duty.PAYMENTS : null;
        }
        return storedDuty(request);
    }

    private boolean exists(String sql, UUID request) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,request)); }

    /** SQL expression mapping an approval action column to its duty name (NULL when none applies). */
    public static String dutySql(String column) {
        return Arrays.stream(Action.values()).filter(a->duty(a)!=null)
                .map(a->" WHEN '"+a.name()+"' THEN '"+duty(a).name()+"'")
                .collect(Collectors.joining("", "(CASE "+column, " END)"));
    }

    public boolean enforced(UUID bank) {
        var rows=jdbc.queryForList("SELECT enforced FROM platform.bank_duty_policy WHERE tenant_id=?",Boolean.class,bank);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst());
    }

    /** The active, unexpired grant of an active staff identity, or null. */
    Grant current(UUID bank, String issuer, String subject) {
        var rows=jdbc.queryForList("""
                SELECT g.payload::text FROM platform.bank_duty g JOIN platform.staff_identity s ON s.id=g.staff_id
                WHERE g.tenant_id=? AND s.issuer=? AND s.subject=? AND s.active AND g.active
                """,String.class,bank,issuer,subject);
        if(rows.isEmpty()) return null;
        Grant grant=JSON.readValue(rows.getFirst(),Grant.class);
        return expired(grant,Instant.now()) ? null : grant;
    }

    /** Throws unless the bank does not enforce duties or the actor holds the duty for this step. */
    public void require(Actor actor, UUID bank, Duty duty, boolean approve) {
        if(!enforced(bank)) return;
        Grant grant=current(bank,actor.issuer(),actor.subject());
        if(grant==null || !(approve?grant.approve():grant.submit()).contains(duty))
            throw deny(approve?"BANK_DUTY_APPROVAL_REQUIRED":"BANK_DUTY_REQUIRED");
    }

    /** At approval time the maker must still hold the submit duty (it may have expired or been revoked). */
    public void requireMaker(String issuer, String maker, UUID bank, Duty duty) {
        if(!enforced(bank)) return;
        Grant grant=current(bank,issuer,maker);
        if(grant==null || !grant.submit().contains(duty))
            throw new TenantException(409,"BANK_DUTY_MAKER_REVOKED","The maker no longer holds the duty for this request");
    }

    /** Reading a bank's request needs any grant of its duty, or the audit flag. */
    public void read(Actor actor, UUID bank, Duty duty) {
        if(!enforced(bank)) return;
        Grant grant=current(bank,actor.issuer(),actor.subject());
        if(grant==null || !(grant.audit() || grant.submit().contains(duty) || grant.approve().contains(duty)))
            throw deny("BANK_DUTY_REQUIRED");
    }

    /**
     * Direct-route read: audit, or any grant of one of the duties. An empty set accepts any active grant,
     * for reference data every duty holder needs.
     */
    public void readAny(Actor actor, UUID bank, Set<Duty> duties) {
        if(!enforced(bank)) return;
        Grant grant=current(bank,actor.issuer(),actor.subject());
        boolean held=grant!=null && (grant.audit() || (duties.isEmpty()
                ? !grant.submit().isEmpty() || !grant.approve().isEmpty()
                : duties.stream().anyMatch(d->grant.submit().contains(d) || grant.approve().contains(d))));
        if(!held) throw deny("BANK_DUTY_REQUIRED");
    }

    /**
     * Delivery recheck: an approved request reaches the bank database only while the maker still holds the
     * submit duty and the checker the approve duty. A failed check leaves the delivery pending with this code;
     * it succeeds on retry once the duties are restored. Nothing is checked once the bank has the receipt.
     */
    public void requireAtDelivery(UUID request) {
        var rows=jdbc.queryForList("SELECT bank_id,issuer,maker_subject,reviewer_subject FROM platform.approval_request WHERE id=? AND status='APPROVED'",request);
        if(rows.isEmpty() || rows.getFirst().get("bank_id")==null) return;
        var row=rows.getFirst();
        UUID bank=(UUID)row.get("bank_id");
        if(!enforced(bank)) return;
        // Same maker classification as the approval-time recheck, including administrator-made payment-workspace requests.
        Duty makerDuty=makerDuty(request);
        if(makerDuty==null) return;
        // The checker of a payment-workspace request may be a payment operator, who never needs a bank duty; the
        // delivery cannot tell which path they approved on, so only the maker is rechecked for those requests.
        Duty checkerDuty=storedDuty(request);
        String issuer=(String)row.get("issuer");
        Grant makerGrant=current(bank,issuer,(String)row.get("maker_subject"));
        Grant checkerGrant=checkerDuty==null?null:current(bank,issuer,(String)row.get("reviewer_subject"));
        if(makerGrant==null || !makerGrant.submit().contains(makerDuty)
                || (checkerDuty!=null && (checkerGrant==null || !checkerGrant.approve().contains(checkerDuty))))
            throw new TenantException(409,"BANK_DUTY_REVOKED_BEFORE_DELIVERY","An approval participant no longer holds the duty for this request");
    }

    /** True when the bank does not enforce duties, or the staff identity holds the duty (submit or approve). */
    public boolean holds(UUID bank, String issuer, String subject, Duty duty) {
        if(!enforced(bank)) return true;
        Grant grant=current(bank,issuer,subject);
        return grant!=null && (grant.submit().contains(duty) || grant.approve().contains(duty));
    }

    /** Enforced, but fewer than two access administrators remain: only switching enforcement off is possible. */
    public boolean lockedOut(UUID bank) {
        return enforced(bank) && accessAdministrators(bank,null).size()<2;
    }

    public Map<String,Object> before(UUID bank, UUID staff) {
        var rows=jdbc.queryForList("SELECT payload::text FROM platform.bank_duty WHERE tenant_id=? AND staff_id=?",String.class,bank,staff);
        return rows.isEmpty() ? Map.of() : Map.of("grant",JSON.readValue(rows.getFirst(),Grant.class));
    }

    public void validateGrant(Actor actor, Tenant bank, Grant grant) {
        AccessService.admin(actor);
        if(bank.version()!=grant.expectedVersion()) throw new TenantException(409,"STALE_VERSION","Refresh the bank");
        var staff=access.staff(grant.staffId(),false);
        if(!staff.issuer().equals(actor.issuer())) throw new TenantException(404,"STAFF_NOT_FOUND","Staff identity was not found");
        if(grant.active() && !staff.active()) throw new TenantException(409,"STAFF_DISABLED","Enable the staff identity before granting access");
        boolean duplicates=new HashSet<>(grant.submit()).size()!=grant.submit().size() || new HashSet<>(grant.approve()).size()!=grant.approve().size();
        boolean empty=grant.active() && grant.submit().isEmpty() && grant.approve().isEmpty() && !grant.audit();
        boolean badExpiry=grant.expiresAt()!=null && (!grant.active() || !grant.expiresAt().isAfter(Instant.now()));
        if(duplicates || empty || badExpiry) throw invalid();
        if(enforced(bank.id()) && accessAdministrators(bank.id(),grant).size()<2)
            throw new TenantException(409,"BANK_DUTY_LOCKOUT","At least two access administrators must remain while duties are enforced");
    }

    public void validatePolicy(Actor actor, Tenant bank, Policy policy) {
        AccessService.admin(actor);
        if(bank.version()!=policy.expectedVersion()) throw new TenantException(409,"STALE_VERSION","Refresh the bank");
        if(policy.enforced()==enforced(bank.id())) throw new TenantException(409,"BANK_DUTY_POLICY_UNCHANGED","The bank already uses this setting");
        if(policy.enforced() && accessAdministrators(bank.id(),null).size()<2)
            throw new TenantException(409,"BANK_DUTY_LOCKOUT","Grant access administration to at least two staff before enforcing duties");
    }

    public void applyGrant(Actor actor, UUID bankId, Grant grant, UUID approval) {
        var bank=tenants.get(bankId,true);
        validateGrant(actor,bank,grant);
        if(access.staff(grant.staffId(),false).subject().equals(actor.subject()))
            throw new TenantException(403,"BANK_DUTY_SELF_GRANT_APPROVAL","Another administrator must approve your own duties");
        String payload=JSON.writeValueAsString(grant);
        jdbc.update("INSERT INTO platform.bank_duty_event(approval_id,tenant_id,kind,staff_id,payload) VALUES (?,?,'GRANT',?,?::jsonb)",
                approval,bankId,grant.staffId(),payload);
        jdbc.update("""
                INSERT INTO platform.bank_duty(tenant_id,staff_id,active,payload,approval_id) VALUES (?,?,?,?::jsonb,?)
                ON CONFLICT(tenant_id,staff_id) DO UPDATE SET active=excluded.active,payload=excluded.payload,approval_id=excluded.approval_id
                """,bankId,grant.staffId(),grant.active(),payload,approval);
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(actor,bankId,"BANK_DUTY_CHANGED",bank.version()+1,bank.status(),bank.status(),grant.reason(),null,null,
                Map.of("staffId",grant.staffId(),"active",grant.active(),"approvalId",approval));
    }

    public void applyPolicy(Actor actor, UUID bankId, Policy policy, UUID approval) {
        var bank=tenants.get(bankId,true);
        validatePolicy(actor,bank,policy);
        jdbc.update("INSERT INTO platform.bank_duty_event(approval_id,tenant_id,kind,payload) VALUES (?,?,'POLICY',?::jsonb)",
                approval,bankId,JSON.writeValueAsString(policy));
        jdbc.update("""
                INSERT INTO platform.bank_duty_policy(tenant_id,enforced,approval_id) VALUES (?,?,?)
                ON CONFLICT(tenant_id) DO UPDATE SET enforced=excluded.enforced,approval_id=excluded.approval_id,changed_at=clock_timestamp()
                """,bankId,policy.enforced(),approval);
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(actor,bankId,"BANK_DUTY_POLICY_CHANGED",bank.version()+1,bank.status(),bank.status(),policy.reason(),null,null,
                Map.of("enforced",policy.enforced(),"approvalId",approval));
    }

    /** Consolidated "who can do what in this bank" with findings, plus history and past reviews. */
    public Map<String,Object> report(Actor actor, UUID bankId) {
        return tx.execute(t->{
            var bank=tenants.get(bankId,false);
            requireReviewer(actor,bankId,false);
            var snapshot=snapshot(actor.issuer(),bank);
            var result=new LinkedHashMap<String,Object>();
            result.put("version",bank.version());
            result.put("snapshotHash",hash(snapshot));
            result.putAll(snapshot);
            result.put("history",jdbc.query("""
                    SELECT e.sequence,e.kind,e.payload::text,s.display_name,a.maker_subject,a.reviewer_subject,a.decided_at
                    FROM platform.bank_duty_event e JOIN platform.approval_request a ON a.id=e.approval_id
                    LEFT JOIN platform.staff_identity s ON s.id=e.staff_id
                    WHERE e.tenant_id=? AND a.issuer=? ORDER BY e.sequence DESC LIMIT 50
                    """,(r,n)->{var item=new LinkedHashMap<String,Object>();item.put("kind",r.getString(2));
                        item.put("change",JSON.readTree(r.getString(3)));item.put("displayName",r.getString(4));
                        item.put("maker",r.getString(5));item.put("checker",r.getString(6));
                        item.put("at",r.getTimestamp(7).toInstant());return item;},bankId,actor.issuer()));
            result.put("reviews",jdbc.query("""
                    SELECT id,reviewer_subject,snapshot_hash,findings,statement,reviewed_at FROM platform.access_review
                    WHERE tenant_id=? AND reviewer_issuer=? ORDER BY reviewed_at DESC,id LIMIT 20
                    """,(r,n)->Map.of("id",r.getObject(1,UUID.class),"reviewer",r.getString(2),"snapshotHash",r.getString(3),
                        "findings",r.getInt(4),"statement",r.getString(5),"at",r.getTimestamp(6).toInstant()),bankId,actor.issuer()));
            return result;
        });
    }

    /** Records that the reviewer examined exactly the snapshot they saw; a changed snapshot is refused. */
    public Map<String,Object> attest(Actor actor, UUID bankId, Review input) {
        return tx.execute(t->{
            var bank=tenants.get(bankId,true);
            requireReviewer(actor,bankId,true);
            var snapshot=snapshot(actor.issuer(),bank);
            String json=JSON.writeValueAsString(snapshot);
            String hash=hash(snapshot);
            if(!hash.equals(input.snapshotHash()))
                throw new TenantException(409,"ACCESS_REVIEW_STALE","Access changed since the report was read; review the current report");
            UUID id=UUID.randomUUID();
            int findings=((Number)snapshot.get("findingCount")).intValue();
            jdbc.update("""
                    INSERT INTO platform.access_review(id,tenant_id,reviewer_issuer,reviewer_subject,snapshot_hash,snapshot,findings,statement)
                    VALUES (?,?,?,?,?,?::jsonb,?,?)
                    """,id,bankId,actor.issuer(),actor.subject(),hash,json,findings,input.statement());
            return Map.of("id",id,"snapshotHash",hash,"findings",findings);
        });
    }

    /** With duties enforced, reports need access administration or audit; attestations need audit. */
    private void requireReviewer(Actor actor, UUID bank, boolean attest) {
        AccessService.admin(actor);
        if(!enforced(bank)) return;
        Grant grant=current(bank,actor.issuer(),actor.subject());
        boolean administrator=grant!=null && (grant.submit().contains(Duty.ACCESS_ADMINISTRATION) || grant.approve().contains(Duty.ACCESS_ADMINISTRATION));
        if(grant==null || !(grant.audit() || (!attest && administrator))) throw deny(attest?"BANK_DUTY_AUDIT_REQUIRED":"BANK_DUTY_REQUIRED");
    }

    /** Staff ids holding access administration for both submit and approve, with an optional proposed grant applied. */
    private Set<UUID> accessAdministrators(UUID bank, Grant proposed) {
        var holders=new HashSet<UUID>();
        Instant now=Instant.now();
        jdbc.query("""
                SELECT g.staff_id,g.payload::text FROM platform.bank_duty g JOIN platform.staff_identity s ON s.id=g.staff_id
                WHERE g.tenant_id=? AND g.active AND s.active
                """,(org.springframework.jdbc.core.RowCallbackHandler)r->{
                    Grant grant=JSON.readValue(r.getString(2),Grant.class);
                    if(administers(grant,now)) holders.add(r.getObject(1,UUID.class));
                },bank);
        if(proposed!=null) {
            holders.remove(proposed.staffId());
            if(administers(proposed,now)) holders.add(proposed.staffId());
        }
        return holders;
    }
    private static boolean administers(Grant grant, Instant now) {
        return grant.active() && !expired(grant,now)
                && grant.submit().contains(Duty.ACCESS_ADMINISTRATION) && grant.approve().contains(Duty.ACCESS_ADMINISTRATION);
    }
    static boolean expired(Grant grant, Instant now) { return grant.expiresAt()!=null && !grant.expiresAt().isAfter(now); }

    /**
     * Deterministic, hashable view of the bank's access. It holds only facts that do not change while a
     * reviewer reads it (no session times), so the same state always gives the same hash.
     */
    Map<String,Object> snapshot(String issuer, Tenant bank) {
        Instant now=Instant.now();
        UUID bankId=bank.id();
        var staff=jdbc.query("""
                SELECT s.id,s.subject,s.display_name,s.active,
                  (SELECT max(x.last_seen_at) FROM platform.access_session x WHERE x.staff_id=s.id) AS last_seen
                FROM platform.staff_identity s WHERE s.issuer=? AND (
                  EXISTS(SELECT 1 FROM platform.bank_duty g WHERE g.tenant_id=? AND g.staff_id=s.id AND g.active)
                  OR EXISTS(SELECT 1 FROM platform.lending_access g WHERE g.tenant_id=? AND g.staff_id=s.id AND g.active)
                  OR EXISTS(SELECT 1 FROM platform.payment_access g WHERE g.tenant_id=? AND g.staff_id=s.id AND g.active)
                  OR EXISTS(SELECT 1 FROM platform.branch_membership g WHERE g.tenant_id=? AND g.staff_id=s.id AND g.active))
                ORDER BY s.display_name,s.id LIMIT ?
                """,(r,n)->{var row=new LinkedHashMap<String,Object>();row.put("id",r.getObject(1,UUID.class));row.put("subject",r.getString(2));
                    row.put("displayName",r.getString(3));row.put("active",r.getBoolean(4));
                    var seen=r.getTimestamp(5);row.put("lastSeen",seen==null?null:seen.toInstant());return row;},
                issuer,bankId,bankId,bankId,bankId,MAXIMUM_REVIEWED_STAFF+1);
        if(staff.size()>MAXIMUM_REVIEWED_STAFF)
            throw new TenantException(409,"ACCESS_REVIEW_TOO_LARGE","Too many staff hold access to review in one report");
        var items=new ArrayList<Map<String,Object>>();
        int findingCount=0;
        Set<UUID> administrators=accessAdministrators(bankId,null);
        for(var row:staff) {
            UUID id=(UUID)row.get("id");
            var item=new LinkedHashMap<String,Object>();
            item.put("staffId",id);item.put("subject",row.get("subject"));item.put("displayName",row.get("displayName"));item.put("staffActive",row.get("active"));
            item.put("lastSeen",row.get("lastSeen"));
            var dutyRows=jdbc.queryForList("SELECT payload::text FROM platform.bank_duty WHERE tenant_id=? AND staff_id=? AND active",String.class,bankId,id);
            Grant duty=dutyRows.isEmpty()?null:JSON.readValue(dutyRows.getFirst(),Grant.class);
            item.put("duties",duty==null?null:dutiesView(duty));
            var lendingRoles=new ArrayList<String>();
            for(String payload:jdbc.queryForList("SELECT payload::text FROM platform.lending_access WHERE tenant_id=? AND staff_id=? AND active",String.class,bankId,id))
                for(var role:JSON.readTree(payload).path("roles")) lendingRoles.add(role.asText());
            Collections.sort(lendingRoles);
            item.put("lendingRoles",lendingRoles);
            var paymentRoles=jdbc.queryForList("SELECT role FROM platform.payment_access WHERE tenant_id=? AND staff_id=? AND active ORDER BY role",String.class,bankId,id);
            item.put("paymentRoles",paymentRoles);
            var branchRoles=jdbc.queryForList("""
                    SELECT b.code||':'||m.role FROM platform.branch_membership m JOIN platform.branch b ON b.id=m.branch_id
                    WHERE m.tenant_id=? AND m.staff_id=? AND m.active ORDER BY b.code,m.role
                    """,String.class,bankId,id);
            item.put("branchRoles",branchRoles);
            // Auditor roles only read; every other lending, payment or branch role can move money.
            boolean otherTransactional=lendingRoles.stream().anyMatch(r->!r.equals("AUDITOR"))
                    || paymentRoles.stream().anyMatch(r->!r.equals("AUDITOR"))
                    || branchRoles.stream().anyMatch(r->!r.endsWith(":AUDITOR"));
            var findings=findings(duty,(Boolean)row.get("active"),(Instant)row.get("lastSeen"),now,otherTransactional);
            item.put("findings",findings);
            findingCount+=findings.size();
            items.add(item);
        }
        var bankFindings=new ArrayList<String>();
        boolean enforced=enforced(bankId);
        if(!enforced) bankFindings.add("DUTIES_NOT_ENFORCED");
        if(administrators.size()<2) bankFindings.add("FEWER_THAN_TWO_ACCESS_ADMINISTRATORS");
        findingCount+=bankFindings.size();
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("bankId",bankId);
        snapshot.put("enforced",enforced);
        snapshot.put("bankFindings",bankFindings);
        snapshot.put("findingCount",findingCount);
        snapshot.put("items",items);
        return snapshot;
    }

    /** Review findings for one staff member. Session recency is reduced to a dormancy flag so the hash stays stable. */
    static List<String> findings(Grant duty, boolean staffActive, Instant lastSeen, Instant now, boolean otherTransactional) {
        var result=new ArrayList<String>();
        if(!staffActive) result.add("STAFF_DISABLED_WITH_GRANT");
        if(lastSeen==null || lastSeen.isBefore(now.minus(DORMANT_DAYS,ChronoUnit.DAYS))) result.add("DORMANT");
        if(duty!=null) {
            var all=EnumSet.noneOf(Duty.class);all.addAll(duty.submit());all.addAll(duty.approve());
            if(expired(duty,now)) result.add("DUTY_EXPIRED");
            else if(duty.expiresAt()!=null) result.add("TIME_LIMITED_GRANT");
            if(all.contains(Duty.ACCESS_ADMINISTRATION) && (all.stream().anyMatch(TRANSACTIONAL::contains) || otherTransactional))
                result.add("CONFLICT_ACCESS_AND_TRANSACTIONS");
            if(all.contains(Duty.CUSTOMER_OPERATIONS) && all.contains(Duty.COMPLIANCE)) result.add("CONFLICT_CUSTOMER_AND_COMPLIANCE");
        }
        return result;
    }

    private static List<String> sorted(List<Duty> duties) { return duties.stream().map(Enum::name).sorted().toList(); }

    /** Fixed key order: the snapshot hash must be identical across restarts and application instances. */
    static Map<String,Object> dutiesView(Grant duty) {
        var view=new LinkedHashMap<String,Object>();
        view.put("submit",sorted(duty.submit()));
        view.put("approve",sorted(duty.approve()));
        view.put("audit",duty.audit());
        view.put("expiresAt",duty.expiresAt()==null?"":duty.expiresAt().toString());
        return view;
    }

    static String hash(Map<String,Object> snapshot) {
        try {
            var stable=new LinkedHashMap<>(snapshot);
            // Session recency is shown to the reviewer but is not part of what they attest to.
            stable.put("items",((List<?>)snapshot.get("items")).stream().map(i->{var copy=new LinkedHashMap<>((Map<?,?>)i);copy.remove("lastSeen");return copy;}).toList());
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(stable).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch(java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static TenantException invalid() { return new TenantException(409,"BANK_DUTY_GRANT_INVALID","The duty grant is not valid"); }
    private static TenantException deny(String code) { return new TenantException(403,code,"This bank duty is not assigned to you"); }
}
