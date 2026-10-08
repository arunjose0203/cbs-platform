package com.cbs.credit;
import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping({"/api/v1/admin/tenants/{bank}/credit","/api/v1/banks/{bank}/lending-operations/credit"})
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class CreditController {
 private final CreditService service;private final ApprovalService approvals;
 public CreditController(CreditService service,ApprovalService approvals){this.service=service;this.approvals=approvals;}
 @GetMapping public Map<String,Object> list(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return service.list(actor(jwt),bank,limit,offset);}
 @GetMapping("/{id}") public Map<String,Object> detail(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){return service.detail(actor(jwt),bank,id);}
 @PostMapping public Map<String,Object> create(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody CreditModels.Application input){return service.create(actor(jwt),bank,key,input);}
 @PostMapping("/policy") public ApprovalModels.Request policy(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody CreditModels.Policy input){return approvals.creditPolicy(actor(jwt),key,bank,input);}
 @PostMapping("/reviews") public ApprovalModels.Request review(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody CreditModels.Review input){return approvals.creditReview(actor(jwt),key,bank,input);}
 @PostMapping("/release") public ApprovalModels.Request release(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody CreditModels.Release input){return approvals.creditRelease(actor(jwt),key,bank,input);}
}
