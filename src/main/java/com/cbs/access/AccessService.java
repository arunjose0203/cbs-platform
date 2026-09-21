package com.cbs.access;

import static com.cbs.access.AccessModels.*;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantModels.ChangeRequest;
import com.cbs.tenancy.TenantModels.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class AccessService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AccessConfiguration.Settings settings;
    public AccessService(JdbcTemplate jdbc, PlatformTransactionManager manager, AccessConfiguration.Settings settings) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.settings=settings;
    }

    public Me authenticate(Jwt jwt, boolean admin) {
        Instant now = Instant.now();
        Instant issued = jwt.getIssuedAt();
        Object rawSid = jwt.getClaims().get("sid");
        Object rawAuth = jwt.getClaims().get("auth_time");
        if (!(rawSid instanceof String sid) || sid.isBlank() || sid.length()>200 || !(rawAuth instanceof Number number)
                || issued==null) { throw deny(401,"INVALID_SESSION_CLAIMS","Sign in again with a supported identity provider"); }
        Instant authTime;
        try { authTime=Instant.ofEpochSecond(number.longValue()); }
        catch (RuntimeException exception) { throw deny(401,"INVALID_SESSION_CLAIMS","Invalid authentication time"); }
        if (issued.isAfter(now.plusSeconds(60)) || authTime.isAfter(now.plusSeconds(60)) || authTime.isAfter(issued.plusSeconds(60))
                || !issued.plus(settings.maximumTokenAge()).isAfter(now)
                || !authTime.plus(settings.maximumSessionAge()).isAfter(now)) {
            throw deny(401,"SESSION_EXPIRED","Your session has expired; sign in again");
        }
        if (admin) {
            Object amr = jwt.getClaims().get("amr");
            if (!(amr instanceof List<?> methods) || !methods.contains("mfa")
                    || !authTime.plus(settings.administratorMfaAge()).isAfter(now)) {
                throw deny(403,"MFA_REQUIRED","Sign in with recent multifactor authentication");
            }
        }
        return tx.execute(status -> {
            Staff staff = bySubject(jwt.getIssuer().toString(),jwt.getSubject(),true);
            if (!staff.active()) { throw deny(403,"STAFF_DISABLED","Your CBS access has been disabled"); }
            if (staff.revokedBefore()!=null && (!issued.isAfter(staff.revokedBefore()) || !authTime.isAfter(staff.revokedBefore()))) {
                throw deny(401,"SESSION_REVOKED","Your access was revoked; sign in again");
            }
            String hash=hash(sid+":"+authTime.getEpochSecond());
            jdbc.update("""
                    INSERT INTO platform.access_session(id,staff_id,sid_hash,authenticated_at,expires_at,last_seen_at)
                    VALUES (?,?,?,?,?,?) ON CONFLICT (staff_id,sid_hash) DO NOTHING
                    """,UUID.randomUUID(),staff.id(),hash,java.sql.Timestamp.from(authTime),
                    java.sql.Timestamp.from(authTime.plus(settings.maximumSessionAge())),java.sql.Timestamp.from(now));
            Session session=jdbc.queryForObject("SELECT * FROM platform.access_session WHERE staff_id=? AND sid_hash=? FOR UPDATE",
                    (row,n)->session(row),staff.id(),hash);
            if (session.revokedAt()!=null) { throw deny(401,"SESSION_REVOKED","This session has been revoked"); }
            if (!session.authenticatedAt().equals(authTime)) { throw deny(401,"INVALID_SESSION_CLAIMS","A fresh sign-in must use a new session identifier"); }
            if (!session.expiresAt().isAfter(now) || !session.lastSeenAt().plus(settings.idleTimeout()).isAfter(now)) {
                throw deny(401,"SESSION_EXPIRED","Your session expired; sign in again");
            }
            jdbc.update("UPDATE platform.access_session SET last_seen_at=? WHERE id=?",java.sql.Timestamp.from(now),session.id());
            return new Me(staff,session.id(),admin,session.expiresAt());
        });
    }

    public void bootstrap(String issuer,String subject) {
        if (subject.length()>200 || subject.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("Invalid bootstrap administrator subject");
        }
        tx.executeWithoutResult(status->{
            UUID id=UUID.randomUUID();
            int inserted=jdbc.update("""
                    INSERT INTO platform.staff_identity(id,issuer,subject,display_name) VALUES (?,?,?,'Platform administrator')
                    ON CONFLICT (issuer,subject) DO NOTHING
                    """,id,issuer,subject);
            if (inserted==1) { audit(new Actor(issuer,"system:bootstrap",true,UUID.randomUUID()),id,"STAFF_BOOTSTRAPPED",
                    "Operator-configured initial identity",Map.of("subject",subject)); }
        });
    }

    public Staff create(Actor actor,StaffInput input) {
        admin(actor);
        return tx.execute(status->{
            UUID id=UUID.randomUUID();
            int inserted=jdbc.update("""
                    INSERT INTO platform.staff_identity(id,issuer,subject,display_name,email) VALUES (?,?,?,?,?)
                    ON CONFLICT (issuer,subject) DO NOTHING
                    """,id,actor.issuer(),input.subject(),input.displayName(),input.email());
            if (inserted==0) { throw deny(409,"STAFF_ALREADY_EXISTS","This identity is already registered"); }
            audit(actor,id,"STAFF_CREATED",input.reason(),Map.of("subject",input.subject(),"displayName",input.displayName()));
            return staff(id,false);
        });
    }

    public Page<Staff> list(Actor actor,int limit,int offset) {
        admin(actor); page(limit,offset);
        return new Page<>(jdbc.query("SELECT * FROM platform.staff_identity WHERE issuer=? ORDER BY created_at,id LIMIT ? OFFSET ?",
                (row,n)->staff(row),actor.issuer(),limit,offset),limit,offset);
    }

    public Staff update(Actor actor,UUID id,StaffUpdate input) {
        admin(actor);
        return tx.execute(status->{
            Staff before=managed(actor,id,input.expectedVersion());
            if (!input.active() && before.subject().equals(actor.subject())) {
                throw deny(409,"SELF_DEACTIVATION_FORBIDDEN","Another administrator must deactivate your identity");
            }
            jdbc.update("""
                    UPDATE platform.staff_identity SET display_name=?,email=?,active=?,version=version+1,
                    revoked_before=CASE WHEN ? THEN revoked_before ELSE clock_timestamp() END WHERE id=?
                    """,input.displayName(),input.email(),input.active(),input.active(),id);
            if (!input.active()) { revokeAll(id); }
            audit(actor,id,input.active()?"STAFF_UPDATED":"STAFF_DISABLED",input.reason(),Map.of(
                    "before",Map.of("displayName",before.displayName(),"active",before.active(),"email",before.email()==null?"":before.email()),
                    "after",Map.of("displayName",input.displayName(),"active",input.active(),"email",input.email()==null?"":input.email())));
            return staff(id,false);
        });
    }

    public Staff revokeSessions(Actor actor,UUID id,ChangeRequest input) {
        admin(actor);
        return tx.execute(status->{
            managed(actor,id,input.expectedVersion());
            jdbc.update("UPDATE platform.staff_identity SET revoked_before=clock_timestamp(),version=version+1 WHERE id=?",id);
            revokeAll(id);
            audit(actor,id,"ALL_SESSIONS_REVOKED",input.reason(),Map.of());
            return staff(id,false);
        });
    }

    public List<Session> sessions(Me me) {
        return jdbc.query("SELECT * FROM platform.access_session WHERE staff_id=? ORDER BY last_seen_at DESC LIMIT 100",
                (row,n)->session(row),me.staff().id());
    }

    public void revokeOwn(Actor actor,Me me,UUID id) {
        tx.executeWithoutResult(status->{
            staff(me.staff().id(),true);
            int updated=jdbc.update("UPDATE platform.access_session SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE id=? AND staff_id=?",
                    id,me.staff().id());
            if(updated==0) { throw deny(404,"SESSION_NOT_FOUND","Session was not found"); }
            audit(actor,me.staff().id(),"SESSION_REVOKED","User ended session",Map.of("sessionId",id));
        });
    }

    public Staff requireAssignable(String issuer,String subject) {
        Staff result=bySubject(issuer,subject,false);
        if(!result.active()) { throw deny(409,"STAFF_DISABLED","Enable the staff identity before granting access"); }
        return result;
    }

    public Staff staff(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM platform.staff_identity WHERE id=?"+(lock?" FOR UPDATE":""),(row,n)->staff(row),id);
        if(rows.isEmpty()) { throw deny(404,"STAFF_NOT_FOUND","Staff identity was not found"); }
        return rows.getFirst();
    }

    public Page<IdentityAudit> audit(Actor actor,int limit,int offset) {
        admin(actor);page(limit,offset);
        return new Page<>(jdbc.query("""
                SELECT a.* FROM platform.identity_audit a JOIN platform.staff_identity s ON s.id=a.staff_id
                WHERE s.issuer=? ORDER BY a.occurred_at DESC,a.id LIMIT ? OFFSET ?
                """,(row,n)->new IdentityAudit(row.getObject("id",UUID.class),row.getString("actor_subject"),
                row.getObject("staff_id",UUID.class),row.getString("action"),row.getString("reason"),
                JSON.readValue(row.getString("details"),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){}),
                row.getTimestamp("occurred_at").toInstant()),actor.issuer(),limit,offset),limit,offset);
    }

    private Staff managed(Actor actor,UUID id,long version) {
        Staff staff=staff(id,true);
        if(!staff.issuer().equals(actor.issuer())) { throw deny(404,"STAFF_NOT_FOUND","Staff identity was not found"); }
        if(staff.version()!=version) { throw deny(409,"STALE_VERSION","Refresh the staff record before changing it"); }
        return staff;
    }
    private Staff bySubject(String issuer,String subject,boolean lock) {
        var rows=jdbc.query("SELECT * FROM platform.staff_identity WHERE issuer=? AND subject=?"+(lock?" FOR UPDATE":""),
                (row,n)->staff(row),issuer,subject);
        if(rows.isEmpty()) { throw deny(403,"STAFF_NOT_REGISTERED","Ask an administrator to register your CBS identity"); }
        return rows.getFirst();
    }
    private void revokeAll(UUID id) { jdbc.update("UPDATE platform.access_session SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE staff_id=?",id); }
    private void audit(Actor actor,UUID staffId,String action,String reason,Map<String,?> details) {
        jdbc.update("""
                INSERT INTO platform.identity_audit(id,actor_issuer,actor_subject,staff_id,action,reason,details,correlation_id)
                VALUES (?,?,?,?,?,?,?::jsonb,?)
                """,UUID.randomUUID(),actor.issuer(),actor.subject(),staffId,action,reason,JSON.writeValueAsString(details),actor.correlationId());
    }
    private static Staff staff(ResultSet row) throws SQLException {
        return new Staff(row.getObject("id",UUID.class),row.getString("issuer"),row.getString("subject"),row.getString("display_name"),
                row.getString("email"),row.getBoolean("active"),row.getLong("version"),instant(row,"revoked_before"),instant(row,"created_at"));
    }
    private static Session session(ResultSet row) throws SQLException {
        return new Session(row.getObject("id",UUID.class),instant(row,"authenticated_at"),instant(row,"expires_at"),instant(row,"last_seen_at"),instant(row,"revoked_at"));
    }
    private static Instant instant(ResultSet row,String field) throws SQLException { var value=row.getTimestamp(field);return value==null?null:value.toInstant(); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public static void admin(Actor actor) { if(!actor.platformAdmin()) { throw deny(403,"ACCESS_DENIED","Platform administrator access is required"); } }
    public static void page(int limit,int offset) { if(limit<1||limit>100||offset<0||offset>1000000) { throw deny(400,"INVALID_PAGINATION","Invalid page limits"); } }
    private static TenantException deny(int status,String code,String message) { return new TenantException(status,code,message); }
}
