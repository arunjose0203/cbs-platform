package com.cbs.access;

import static com.cbs.access.AccessModels.*;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels.*;
import com.cbs.tenancy.TenantRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class BranchService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TenantRepository tenants;
    private final AccessService identities;
    public BranchService(JdbcTemplate jdbc,PlatformTransactionManager manager,TenantRepository tenants,AccessService identities) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.tenants=tenants;this.identities=identities;
    }
    public Branch create(Actor actor,UUID tenantId,BranchInput input) {
        AccessService.admin(actor);
        return tx.execute(status->{
            Tenant tenant=tenant(tenantId);
            if(tenant.legalEntities().stream().noneMatch(e->e.id().equals(input.legalEntityId()))) {
                throw fail(400,"LEGAL_ENTITY_NOT_IN_BANK","Choose a legal entity belonging to this bank");
            }
            UUID id=UUID.randomUUID();
            int added=jdbc.update("""
                    INSERT INTO platform.branch(id,tenant_id,legal_entity_id,code,display_name) VALUES (?,?,?,?,?)
                    ON CONFLICT (tenant_id,code) DO NOTHING
                    """,id,tenantId,input.legalEntityId(),input.code(),input.displayName());
            if(added==0) { throw fail(409,"BRANCH_CODE_IN_USE","Branch code is already in use in this bank"); }
            audit(actor,tenant,"BRANCH_CREATED",input.reason(),Map.of("branchId",id,"code",input.code(),"displayName",input.displayName()));
            return branch(tenantId,id,false);
        });
    }
    public Page<Branch> list(Actor actor,UUID tenantId,int limit,int offset) {
        AccessService.admin(actor);AccessService.page(limit,offset);tenants.get(tenantId,false);
        return new Page<>(jdbc.query("SELECT * FROM platform.branch WHERE tenant_id=? ORDER BY code,id LIMIT ? OFFSET ?",
                (row,n)->map(row),tenantId,limit,offset),limit,offset);
    }
    public Branch update(Actor actor,UUID tenantId,UUID id,BranchUpdate input) {
        AccessService.admin(actor);
        return tx.execute(status->{
            Tenant tenant=tenant(tenantId);Branch before=branch(tenantId,id,true);version(before,input.expectedVersion());
            jdbc.update("UPDATE platform.branch SET display_name=?,active=?,version=version+1 WHERE id=?",
                    input.displayName(),input.active(),id);
            audit(actor,tenant,"BRANCH_UPDATED",input.reason(),Map.of("branchId",id,"displayName",input.displayName(),"active",input.active()));
            return branch(tenantId,id,false);
        });
    }
    public Branch grant(Actor actor,UUID tenantId,UUID id,BranchGrant input) {
        AccessService.admin(actor);
        return tx.execute(status->{
            Tenant tenant=tenant(tenantId);Branch branch=branch(tenantId,id,true);version(branch,input.expectedVersion());
            Staff staff=identities.staff(input.staffId(),false);
            if(!staff.issuer().equals(actor.issuer())) { throw fail(404,"STAFF_NOT_FOUND","Staff identity was not found"); }
            if(input.active()) {
                identities.requireAssignable(staff.issuer(),staff.subject());
                BankRole bankRole=tenants.activeRole(tenantId,new Actor(staff.issuer(),staff.subject(),false,actor.correlationId()));
                if(input.role()!=BranchRole.AUDITOR && bankRole!=BankRole.BANK_OPERATOR) {
                    throw fail(409,"BANK_OPERATOR_REQUIRED","Grant bank operator membership before an operational branch role");
                }
            }
            jdbc.update("""
                    INSERT INTO platform.branch_membership(tenant_id,branch_id,staff_id,role,active) VALUES (?,?,?,?,?)
                    ON CONFLICT (branch_id,staff_id) DO UPDATE SET role=excluded.role,active=excluded.active
                    """,tenantId,id,input.staffId(),input.role().name(),input.active());
            jdbc.update("UPDATE platform.branch SET version=version+1 WHERE id=?",id);
            audit(actor,tenant,input.active()?"BRANCH_ACCESS_GRANTED":"BRANCH_ACCESS_REVOKED",input.reason(),
                    Map.of("branchId",id,"staffId",input.staffId(),"role",input.role()));
            return branch(tenantId,id,false);
        });
    }
    public List<Grant> grants(Actor actor,UUID tenantId,UUID id) {
        AccessService.admin(actor);branch(tenantId,id,false);
        return jdbc.query("""
                SELECT m.*,s.display_name FROM platform.branch_membership m JOIN platform.staff_identity s ON s.id=m.staff_id
                WHERE m.tenant_id=? AND m.branch_id=? ORDER BY s.display_name,s.id LIMIT 100
                """,(row,n)->new Grant(row.getObject("staff_id",UUID.class),row.getString("display_name"),
                BranchRole.valueOf(row.getString("role")),row.getBoolean("active")),tenantId,id);
    }
    public BranchAccess check(Actor actor,UUID tenantId,UUID branchId,Permission required) {
        Staff staff=identities.requireAssignable(actor.issuer(),actor.subject());
        BankRole bankRole=tenants.activeRole(tenantId,actor);
        var roles=jdbc.query("""
                SELECT role FROM platform.branch_membership WHERE tenant_id=? AND branch_id=? AND staff_id=? AND active
                """,(row,n)->BranchRole.valueOf(row.getString(1)),tenantId,branchId,staff.id());
        if(roles.isEmpty()) { throw fail(403,"BRANCH_ACCESS_DENIED","Branch membership is required"); }
        Branch branch=branch(tenantId,branchId,false);
        if(!branch.active() || tenants.get(tenantId,false).status()!=Status.ACTIVE) {
            throw fail(409,"BRANCH_NOT_OPERATIONAL","The bank and branch must be active");
        }
        List<Permission> permissions=switch(roles.getFirst()) {
            case AUDITOR -> List.of(Permission.READ_BRANCH);
            case TELLER -> List.of(Permission.READ_BRANCH,Permission.CASH_TRANSACT);
            case BRANCH_MANAGER -> List.of(Permission.READ_BRANCH,Permission.CASH_TRANSACT,Permission.APPROVE_BRANCH);
        };
        if(bankRole!=BankRole.BANK_OPERATOR) { permissions=List.of(Permission.READ_BRANCH); }
        if(!permissions.contains(required)) { throw fail(403,"BRANCH_PERMISSION_DENIED","Your branch role does not allow this action"); }
        return new BranchAccess(tenantId,branchId,permissions);
    }
    private Tenant tenant(UUID id) {
        Tenant tenant=tenants.get(id,true);
        if(tenant.status()==Status.PROVISIONING) { throw fail(409,"PROVISIONING_IN_PROGRESS","Wait for provisioning to complete"); }
        return tenant;
    }
    private void audit(Actor actor,Tenant tenant,String action,String reason,Map<String,?> details) {
        tenants.change(tenant,tenant.status(),tenant.displayName(),tenant.defaultLanguage());
        tenants.audit(actor,tenant.id(),action,tenant.version()+1,tenant.status(),tenant.status(),reason,null,null,details);
    }
    private Branch branch(UUID tenantId,UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM platform.branch WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),(row,n)->map(row),tenantId,id);
        if(rows.isEmpty()) { throw fail(404,"BRANCH_NOT_FOUND","Branch was not found in this bank"); }
        return rows.getFirst();
    }
    private static void version(Branch branch,long version) { if(branch.version()!=version) { throw fail(409,"STALE_VERSION","Refresh the branch before changing it"); } }
    private static Branch map(ResultSet row) throws SQLException {
        return new Branch(row.getObject("id",UUID.class),row.getObject("tenant_id",UUID.class),row.getObject("legal_entity_id",UUID.class),
                row.getString("code"),row.getString("display_name"),row.getBoolean("active"),row.getLong("version"));
    }
    private static TenantException fail(int status,String code,String message) { return new TenantException(status,code,message); }
}
