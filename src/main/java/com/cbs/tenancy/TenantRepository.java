package com.cbs.tenancy;

import static com.cbs.tenancy.TenantModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class TenantRepository {
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    public TenantRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean claimRegistration(Actor actor, UUID key, String hash, UUID tenantId) {
        return jdbc.update("""
                INSERT INTO platform.registration_request(actor_issuer,actor_subject,idempotency_key,request_hash,tenant_id)
                VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
                """, actor.issuer(), actor.subject(), key, hash, tenantId) == 1;
    }

    public UUID replayRegistration(Actor actor, UUID key, String hash) {
        return jdbc.queryForObject("""
                SELECT request_hash,tenant_id FROM platform.registration_request
                WHERE actor_issuer=? AND actor_subject=? AND idempotency_key=?
                """, (row, number) -> {
                    if (!hash.equals(row.getString("request_hash"))) {
                        throw new TenantException(409, "IDEMPOTENCY_CONFLICT", "This key was used for a different registration");
                    }
                    return row.getObject("tenant_id", UUID.class);
                }, actor.issuer(), actor.subject(), key);
    }

    public void insert(UUID id, RegisterRequest request) {
        int created = jdbc.update("""
                INSERT INTO platform.tenant(id,bank_code,display_name,default_language,status)
                VALUES (?,?,?,?,'REQUESTED') ON CONFLICT (bank_code) DO NOTHING
                """, id, request.bankCode(), request.displayName(), request.defaultLanguage());
        if (created == 0) { throw new TenantException(409, "BANK_CODE_IN_USE", "The bank code is already registered"); }
        int bound = jdbc.update("""
                INSERT INTO platform.database_binding(tenant_id,database_reference)
                VALUES (?,?) ON CONFLICT (database_reference) DO NOTHING
                """, id, request.databaseReference());
        if (bound == 0) { throw new TenantException(409, "DATABASE_REFERENCE_IN_USE", "The database reference is already assigned"); }
        addLegalEntity(id, request.legalEntity());
    }

    public void addLegalEntity(UUID tenantId, LegalEntityInput entity) {
        jdbc.update("""
                INSERT INTO platform.legal_entity(id,tenant_id,legal_name,country,functional_currency,business_timezone)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), tenantId, entity.legalName(), entity.country(),
                entity.functionalCurrency(), entity.businessTimezone());
    }

    public Tenant get(UUID id, boolean lock) {
        List<Tenant> rows = jdbc.query("""
                SELECT t.*,b.database_reference,b.schema_version,b.failure_code,b.attempt_id,b.lease_until
                FROM platform.tenant t JOIN platform.database_binding b ON b.tenant_id=t.id WHERE t.id=?
                """ + (lock ? " FOR UPDATE OF t" : ""), (row, number) -> map(row), id);
        if (rows.isEmpty()) { throw new TenantException(404, "TENANT_NOT_FOUND", "Bank tenant was not found"); }
        Tenant base = rows.getFirst();
        List<LegalEntity> entities = jdbc.query("""
                SELECT * FROM platform.legal_entity WHERE tenant_id=? ORDER BY created_at,id
                """, (row, number) -> new LegalEntity(row.getObject("id", UUID.class), row.getString("legal_name"),
                row.getString("country"), row.getString("functional_currency"), row.getString("business_timezone")), id);
        return new Tenant(base.id(), base.bankCode(), base.displayName(), base.defaultLanguage(), base.status(),
                base.version(), base.createdAt(), base.updatedAt(), entities, base.databaseBinding());
    }

    public List<Tenant> list(int limit, int offset) {
        return jdbc.query("SELECT id FROM platform.tenant ORDER BY created_at,id LIMIT ? OFFSET ?",
                (row, number) -> row.getObject("id", UUID.class), limit, offset).stream()
                .map(id -> get(id, false)).toList();
    }

    public void change(Tenant previous, Status status, String displayName, String language) {
        int changed = jdbc.update("""
                UPDATE platform.tenant SET status=?,display_name=?,default_language=?,version=version+1,
                updated_at=clock_timestamp() WHERE id=? AND version=?
                """, status.name(), displayName, language, previous.id(), previous.version());
        if (changed != 1) { throw new TenantException(409, "STALE_VERSION", "Read the current tenant version and retry"); }
    }

    public void startAttempt(UUID tenantId, UUID attemptId) {
        jdbc.update("""
                UPDATE platform.database_binding SET attempt_id=?,lease_until=clock_timestamp()+interval '10 minutes',
                failure_code=NULL WHERE tenant_id=?
                """, attemptId, tenantId);
    }

    public void finishAttempt(UUID tenantId, String schemaVersion, String failureCode) {
        jdbc.update("""
                UPDATE platform.database_binding SET schema_version=?,failure_code=?,attempt_id=NULL,lease_until=NULL
                WHERE tenant_id=?
                """, schemaVersion, failureCode, tenantId);
    }

    public boolean leaseExpired(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT lease_until < clock_timestamp() FROM platform.database_binding WHERE tenant_id=?
                """, Boolean.class, tenantId));
    }

    public void audit(Actor actor, UUID id, String action, long version, Status before, Status after,
            String reason, String targetSubject, String targetRole) {
        audit(actor, id, action, version, before, after, reason, targetSubject, targetRole, Map.of());
    }

    public void audit(Actor actor, UUID id, String action, long version, Status before, Status after,
            String reason, String targetSubject, String targetRole, Map<String, ?> details) {
        jdbc.update("""
                INSERT INTO platform.tenant_audit(id,tenant_id,actor_issuer,actor_subject,action,tenant_version,
                previous_status,new_status,reason,target_subject,target_role,correlation_id,change_details)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                """, UUID.randomUUID(), id, actor.issuer(), actor.subject(), action, version,
                before == null ? null : before.name(), after.name(), reason, targetSubject, targetRole, actor.correlationId(),
                JSON.writeValueAsString(details));
    }

    public List<AuditEvent> auditEvents(UUID tenantId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM platform.tenant_audit WHERE tenant_id=? ORDER BY occurred_at,id LIMIT ? OFFSET ?
                """, (row, number) -> new AuditEvent(row.getObject("id", UUID.class), row.getString("actor_issuer"),
                row.getString("actor_subject"), row.getString("action"), row.getLong("tenant_version"),
                row.getString("previous_status"), row.getString("new_status"), row.getString("reason"),
                row.getString("target_subject"), row.getString("target_role"),
                JSON.readValue(row.getString("change_details"), new tools.jackson.core.type.TypeReference<Map<String,Object>>() { }),
                row.getObject("correlation_id", UUID.class),
                row.getTimestamp("occurred_at").toInstant()), tenantId, limit, offset);
    }

    public void setMembership(UUID tenantId, Actor actor, MembershipRequest request) {
        jdbc.update("""
                INSERT INTO platform.tenant_membership(tenant_id,actor_issuer,actor_subject,role,active)
                VALUES (?,?,?,?,?) ON CONFLICT (tenant_id,actor_issuer,actor_subject)
                DO UPDATE SET role=excluded.role,active=excluded.active
                """, tenantId, actor.issuer(), request.subject(), request.role().name(), request.active());
    }

    public BankRole activeRole(UUID tenantId, Actor actor) {
        List<String> roles = jdbc.query("""
                SELECT role FROM platform.tenant_membership
                WHERE tenant_id=? AND actor_issuer=? AND actor_subject=? AND active
                """, (row, number) -> row.getString(1), tenantId, actor.issuer(), actor.subject());
        if (roles.isEmpty()) { throw new TenantException(403, "BANK_ACCESS_DENIED", "Bank membership is required"); }
        return BankRole.valueOf(roles.getFirst());
    }

    public List<Membership> memberships(UUID tenantId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM platform.tenant_membership WHERE tenant_id=?
                ORDER BY actor_issuer,actor_subject LIMIT ? OFFSET ?
                """, (row, number) -> new Membership(row.getString("actor_issuer"), row.getString("actor_subject"),
                BankRole.valueOf(row.getString("role")), row.getBoolean("active")), tenantId, limit, offset);
    }

    private static Tenant map(ResultSet row) throws SQLException {
        Instant leaseUntil = row.getTimestamp("lease_until") == null ? null : row.getTimestamp("lease_until").toInstant();
        return new Tenant(row.getObject("id", UUID.class), row.getString("bank_code"), row.getString("display_name"),
                row.getString("default_language"), Status.valueOf(row.getString("status")), row.getLong("version"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(), List.of(),
                new Binding(row.getString("database_reference"), row.getString("schema_version"),
                        row.getString("failure_code"), row.getObject("attempt_id", UUID.class), leaseUntil));
    }
}
