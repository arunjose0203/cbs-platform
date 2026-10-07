package com.cbs.access;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/transaction-limits")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class TransactionLimitController {
 private final JdbcTemplate jdbc;private final ApprovalService approvals;private final com.cbs.tenancy.TenantRepository tenants;
 public TransactionLimitController(JdbcTemplate jdbc,ApprovalService approvals,com.cbs.tenancy.TenantRepository tenants){this.jdbc=jdbc;this.approvals=approvals;this.tenants=tenants;}
 @GetMapping Map<String,Object> read(JwtAuthenticationToken jwt,@PathVariable UUID bankId){
  AccessService.admin(actor(jwt));var bank=tenants.get(bankId,false);
  return Map.of("version",bank.version(),"missing",jdbc.queryForList("SELECT c->>'code' AS currency,o.operation FROM platform.bank_settings b CROSS JOIN LATERAL jsonb_array_elements(b.currencies) c CROSS JOIN (VALUES ('FUNDING'),('TRANSFER'),('WITHDRAWAL'),('FUNDING_REVERSAL'),('HOLD'),('CASH_RECEIVE'),('CASH_DISPATCH'),('CASH_TRANSFER'),('CASH_DEPOSIT'),('CASH_WITHDRAWAL'),('CASH_SHORTAGE'),('CASH_OVERAGE')) o(operation) WHERE b.tenant_id=? AND (c->>'enabled')::boolean AND NOT EXISTS (SELECT 1 FROM platform.transaction_limit l WHERE l.tenant_id=b.tenant_id AND l.currency=c->>'code' AND l.operation=o.operation) ORDER BY 1,2",bankId),"items",jdbc.query("SELECT currency,operation,per_transaction,daily_total FROM platform.transaction_limit WHERE tenant_id=? ORDER BY currency,operation",(r,n)->Map.of("currency",r.getString(1),"operation",r.getString(2),"perTransaction",r.getBigDecimal(3).toPlainString(),"dailyTotal",r.getBigDecimal(4).toPlainString()),bankId));
 }
 @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 ApprovalModels.Request submit(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody TransactionLimits.Change input){return approvals.limits(actor(jwt),key,bankId,input);}
}
