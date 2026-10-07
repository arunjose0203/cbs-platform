package com.cbs.accounts;
import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.accounts.FundingModels.*;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/funding")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class FundingController {
 private final FundingService funding;private final ApprovalService approvals;
 public FundingController(FundingService funding,ApprovalService approvals){this.funding=funding;this.approvals=approvals;}
 @GetMapping("/{accountId}") Dashboard dashboard(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID accountId,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return funding.dashboard(actor(jwt),bankId,accountId,limit,offset);}
 @PostMapping @ResponseStatus(HttpStatus.ACCEPTED) ApprovalModels.Request submit(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Change input){return approvals.funding(actor(jwt),key,bankId,input);}
 @PostMapping("/{requestId}/retry") @ResponseStatus(HttpStatus.NO_CONTENT) void retry(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID requestId){funding.retry(actor(jwt),bankId,requestId);}
}
