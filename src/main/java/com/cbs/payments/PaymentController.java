package com.cbs.payments;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bank}/payments")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class PaymentController {
 private final PaymentService service;private final ApprovalService approvals;
 public PaymentController(PaymentService service,ApprovalService approvals){this.service=service;this.approvals=approvals;}
 @GetMapping public Map<String,Object> list(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return service.dashboard(actor(jwt),bank,limit,offset);}
 @GetMapping("/{id}") public Map<String,Object> detail(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){return service.detail(actor(jwt),bank,id);}
 @PostMapping public ApprovalModels.Request order(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody PaymentModels.Order input){return approvals.payment(actor(jwt),key,bank,input);}
 @PostMapping("/statements") public Map<String,UUID> statement(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody PaymentModels.Statement input){return Map.of("id",service.statement(actor(jwt),bank,key,input));}
 @PostMapping("/reconciliation") public ApprovalModels.Request reconcile(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody PaymentModels.Reconcile input){return approvals.paymentReconcile(actor(jwt),key,bank,input);}
 @PostMapping("/{id}/dispatch") public void dispatch(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){service.dispatch(actor(jwt),bank,id);}
 @PostMapping("/{id}/query") public void query(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){service.query(actor(jwt),bank,id);}
 @PostMapping("/{id}/refresh") public void refresh(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){service.refreshRefund(actor(jwt),bank,id);}
}
