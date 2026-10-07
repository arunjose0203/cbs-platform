package com.cbs.payments;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import com.cbs.tenancy.TenantModels.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class PaymentWorkspaceController {
 private static final String BASE="/api/v1/banks/{bank}/payment-operations";
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final PaymentAccess access;private final PaymentCaseService cases;private final PaymentService payments;private final PaymentReturnService returns;private final StatementImportService statements;private final ApprovalService approvals;private final JdbcTemplate jdbc;
 public PaymentWorkspaceController(PaymentAccess access,PaymentCaseService cases,PaymentService payments,PaymentReturnService returns,StatementImportService statements,ApprovalService approvals,JdbcTemplate jdbc){this.access=access;this.cases=cases;this.payments=payments;this.returns=returns;this.statements=statements;this.approvals=approvals;this.jdbc=jdbc;}
 @GetMapping("/api/v1/banks/payment-operations/banks") public Map<String,Object> banks(JwtAuthenticationToken jwt){return Map.of("items",access.banks(actor(jwt)));}
 @GetMapping(BASE) public Map<String,Object> context(JwtAuthenticationToken jwt,@PathVariable UUID bank){return access.context(actor(jwt),bank);}
 @GetMapping(BASE+"/ledgers") public Map<String,Object> ledgers(JwtAuthenticationToken jwt,@PathVariable UUID bank){access.require(actor(jwt),bank,PaymentAccess.Capability.READ);return Map.of("items",jdbc.query("SELECT d.payload,l.delivery FROM platform.ledger_registry l JOIN platform.ledger_delivery d ON d.request_id=l.request_id WHERE l.tenant_id=? AND l.currency='KES' ORDER BY l.code,l.id LIMIT 100",(r,n)->Map.of("definition",JSON.readTree(r.getString(1)),"delivery",r.getString(2)),bank));}
 @GetMapping(BASE+"/operators") public List<Map<String,Object>> operators(JwtAuthenticationToken jwt,@PathVariable UUID bank){access.require(actor(jwt),bank,PaymentAccess.Capability.READ);return jdbc.queryForList("SELECT s.id,s.display_name,g.role FROM platform.payment_access g JOIN platform.staff_identity s ON s.id=g.staff_id WHERE g.tenant_id=? AND s.issuer=? AND s.active AND g.active AND g.role IN ('RECONCILER','SUPERVISOR') ORDER BY s.display_name,s.id LIMIT 100",bank,actor(jwt).issuer());}
 @GetMapping(BASE+"/statement-imports/{id}/return-candidates") public List<Map<String,Object>> returnCandidates(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id,@RequestParam UUID paymentId){access.require(actor(jwt),bank,PaymentAccess.Capability.READ);payments.detail(actor(jwt),bank,paymentId);return jdbc.query("SELECT l.id,l.source FROM platform.statement_line l JOIN platform.statement_import b ON b.id=l.batch_id JOIN platform.payment_order p ON p.tenant_id=b.tenant_id AND p.id=? WHERE l.tenant_id=? AND l.batch_id=? AND b.issuer=? AND b.status='OPEN' AND p.payload->>'settlementLedgerId'=b.ledger_id::text AND l.decision_id IS NULL AND l.source->>'type'='REVERSAL' AND l.source->>'paymentReference'=p.payload->>'reference' ORDER BY l.line_number LIMIT 100",(r,n)->Map.of("id",r.getObject(1,UUID.class),"source",JSON.readTree(r.getString(2))),paymentId,bank,id,actor(jwt).issuer());}
 @GetMapping(BASE+"/cases") public Map<String,Object> cases(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return cases.dashboard(actor(jwt),bank,limit,offset);}
 @PostMapping(BASE+"/cases") public Map<String,UUID> open(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody PaymentCaseModels.Open input){return Map.of("id",cases.open(actor(jwt),bank,key,input));}
 @GetMapping(BASE+"/cases/{id}") public Map<String,Object> detail(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){return cases.detail(actor(jwt),bank,id);}
 @PostMapping(BASE+"/cases/{id}/notes") public void note(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id,@Valid @RequestBody PaymentCaseModels.Note input){cases.note(actor(jwt),bank,id,input);}
 @PostMapping(BASE+"/case-decisions") public ApprovalModels.Request decideCase(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody PaymentCaseModels.Change input){return approvals.paymentCase(actor(jwt),key,bank,input);}
 @GetMapping(BASE+"/approvals") public Page<ApprovalModels.Request> inbox(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return approvals.paymentInbox(actor(jwt),bank,limit,offset);}
 @GetMapping(BASE+"/approvals/{id}") public ApprovalModels.Details approval(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){approvals.paymentRequestBank(actor(jwt),bank,id);return approvals.details(actor(jwt),id);}
 @PostMapping(BASE+"/approvals/{id}/{decision}") public ApprovalModels.Request decision(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id,@PathVariable ApprovalModels.Decision decision,@Valid @RequestBody ApprovalModels.DecisionInput input){approvals.paymentRequestBank(actor(jwt),bank,id);return approvals.decide(actor(jwt),id,decision,input.reason());}
 @GetMapping(BASE+"/payments") public Map<String,Object> payments(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return payments.dashboard(actor(jwt),bank,limit,offset);}
 @GetMapping(BASE+"/payments/{id}") public Map<String,Object> payment(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){return payments.detail(actor(jwt),bank,id);}
 @PostMapping(BASE+"/payments") public ApprovalModels.Request payout(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody PaymentModels.Order input){return approvals.payment(actor(jwt),key,bank,input);}
 @PostMapping(BASE+"/payments/statements") public Map<String,UUID> evidence(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody PaymentModels.Statement input){return Map.of("id",payments.statement(actor(jwt),bank,key,input));}
 @PostMapping(BASE+"/payments/reconciliation") public ApprovalModels.Request reconciliation(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody PaymentModels.Reconcile input){return approvals.paymentReconcile(actor(jwt),key,bank,input);}
 @PostMapping(BASE+"/payments/{id}/dispatch") public void dispatch(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){payments.dispatch(actor(jwt),bank,id);}
 @PostMapping(BASE+"/payments/{id}/query") public void query(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){payments.query(actor(jwt),bank,id);}
 @PostMapping(BASE+"/payments/{id}/refresh") public void refresh(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){returns.refreshPayment(actor(jwt),bank,id);payments.refreshRefund(actor(jwt),bank,id);}
 @GetMapping(BASE+"/statement-imports") public Map<String,Object> imports(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return statements.list(actor(jwt),bank,limit,offset);}
 @GetMapping(BASE+"/statement-imports/{id}") public Map<String,Object> statement(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="false") boolean exceptions){return statements.detail(actor(jwt),bank,limit,offset,id,exceptions);}
 @PostMapping(BASE+"/statement-imports/preview") public Map<String,Object> preview(JwtAuthenticationToken jwt,@PathVariable UUID bank,@Valid @RequestBody StatementImportModels.Upload input){return statements.preview(actor(jwt),bank,input);}
 @PostMapping(BASE+"/statement-imports") public Map<String,UUID> upload(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody StatementImportModels.Upload input){return Map.of("id",statements.upload(actor(jwt),bank,key,input));}
 @GetMapping(BASE+"/statement-imports/{id}/export") public Map<String,String> export(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){return statements.export(actor(jwt),bank,id);}
 @PostMapping(BASE+"/statement-imports/actions") public ApprovalModels.Request action(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody StatementImportModels.Action input){return approvals.statementAction(actor(jwt),key,bank,input);}
 @GetMapping("/api/v1/admin/tenants/{bank}/payment-access") public List<Map<String,Object>> grants(JwtAuthenticationToken jwt,@PathVariable UUID bank){return access.grants(actor(jwt),bank);}
 @PostMapping("/api/v1/admin/tenants/{bank}/payment-access") public ApprovalModels.Request grant(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody PaymentAccess.Grant input){return approvals.paymentGrant(actor(jwt),key,bank,input);}
}
