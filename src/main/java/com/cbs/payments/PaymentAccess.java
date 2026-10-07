package com.cbs.payments;

import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Explicit bank scope; never turns a bank operator into a platform administrator. */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class PaymentAccess {
 public enum Capability { READ, MAKE, APPROVE }
 public enum Role { RECONCILER, SUPERVISOR, AUDITOR }
 public record Grant(@PositiveOrZero long expectedVersion,@NotNull UUID staffId,@NotNull Role role,boolean active,@NotBlank @Size(max=500) String reason) {}
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final AccessService access;
 public PaymentAccess(JdbcTemplate jdbc,TenantRepository tenants,AccessService access){this.jdbc=jdbc;this.tenants=tenants;this.access=access;}
 public static void require(JdbcTemplate jdbc,Actor actor,UUID bank,Capability capability){
  if(actor.platformAdmin())return;
  var roles=jdbc.queryForList("SELECT g.role FROM platform.payment_access g JOIN platform.staff_identity s ON s.id=g.staff_id WHERE g.tenant_id=? AND s.issuer=? AND s.subject=? AND s.active AND g.active",String.class,bank,actor.issuer(),actor.subject());
  if(roles.isEmpty()||(capability==Capability.MAKE&&!List.of("RECONCILER","SUPERVISOR").contains(roles.getFirst()))||(capability==Capability.APPROVE&&!roles.getFirst().equals("SUPERVISOR")))throw new TenantException(403,"PAYMENT_ACCESS_DENIED","Payment permission is not assigned for this bank");
 }
 public void require(Actor actor,UUID bank,Capability capability){require(jdbc,actor,bank,capability);}
 public List<Map<String,Object>> banks(Actor actor){
  if(actor.platformAdmin())return jdbc.queryForList("SELECT id,display_name AS \"displayName\",status FROM platform.tenant ORDER BY display_name,id LIMIT 100");
  return jdbc.queryForList("SELECT t.id,t.display_name AS \"displayName\",t.status FROM platform.tenant t JOIN platform.payment_access g ON g.tenant_id=t.id JOIN platform.staff_identity s ON s.id=g.staff_id WHERE s.issuer=? AND s.subject=? AND s.active AND g.active ORDER BY t.display_name,t.id LIMIT 100",actor.issuer(),actor.subject());
 }
 public Map<String,Object> context(Actor actor,UUID bank){require(actor,bank,Capability.READ);var t=tenants.get(bank,false);var role=actor.platformAdmin()?"PLATFORM_ADMIN":jdbc.queryForObject("SELECT g.role FROM platform.payment_access g JOIN platform.staff_identity s ON s.id=g.staff_id WHERE g.tenant_id=? AND s.issuer=? AND s.subject=?",String.class,bank,actor.issuer(),actor.subject());return Map.of("id",bank,"displayName",t.displayName(),"version",t.version(),"role",role,"canMake",!role.equals("AUDITOR"),"canApprove",List.of("PLATFORM_ADMIN","SUPERVISOR").contains(role));}
 public List<Map<String,Object>> grants(Actor actor,UUID bank){AccessService.admin(actor);return jdbc.queryForList("SELECT g.*,s.display_name,s.subject FROM platform.payment_access g JOIN platform.staff_identity s ON s.id=g.staff_id WHERE g.tenant_id=? AND s.issuer=? ORDER BY s.display_name,s.id",bank,actor.issuer());}
 public void validate(Actor actor,Tenant bank,Grant g){AccessService.admin(actor);if(bank.version()!=g.expectedVersion())throw new TenantException(409,"STALE_VERSION","Refresh the bank");var staff=access.staff(g.staffId(),false);if(!staff.issuer().equals(actor.issuer())||(g.active()&&!staff.active()))throw new TenantException(403,"PAYMENT_ACCESS_DENIED","Staff is unavailable");}
 public void apply(Actor actor,UUID bankId,Grant g,UUID approval){var bank=tenants.get(bankId,true);validate(actor,bank,g);jdbc.update("INSERT INTO platform.payment_access(tenant_id,staff_id,role,active,approval_id) VALUES (?,?,?,?,?) ON CONFLICT(tenant_id,staff_id) DO UPDATE SET role=excluded.role,active=excluded.active,approval_id=excluded.approval_id",bankId,g.staffId(),g.role().name(),g.active(),approval);tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(actor,bankId,"PAYMENT_ACCESS_CHANGED",bank.version()+1,bank.status(),bank.status(),g.reason(),null,null,Map.of("staffId",g.staffId(),"role",g.role(),"active",g.active(),"approvalId",approval));}
}
