package com.cbs.servicing;
import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/servicing")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ServicingController {
 private final ServicingService service;private final ApprovalService approvals;
 public ServicingController(ServicingService service,ApprovalService approvals){this.service=service;this.approvals=approvals;}
 @GetMapping Map<String,Object> dashboard(JwtAuthenticationToken jwt,@PathVariable UUID bankId){return service.dashboard(actor(jwt),bankId);}
 @PostMapping("/policies") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 ApprovalModels.Request policy(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody ServicingModels.Policy input){return approvals.servicingPolicy(actor(jwt),key,bankId,input);}
 @PostMapping("/preview") ServicingModels.Batch preview(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@Valid @RequestBody ServicingModels.Run input){return service.preview(actor(jwt),bankId,input);}
 @PostMapping("/runs") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 ApprovalModels.Request run(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody ServicingModels.Run input){return approvals.servicingRun(actor(jwt),key,bankId,input);}
 @PostMapping("/runs/{id}/retry") Map<String,String> retry(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id){service.retry(actor(jwt),bankId,id);return Map.of("status","RETRY_CHECKED");}
}
