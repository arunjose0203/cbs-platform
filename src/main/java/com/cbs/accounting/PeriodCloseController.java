package com.cbs.accounting;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/period-close")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class PeriodCloseController {
    private final PeriodCloseService periods;private final ApprovalService approvals;
    public PeriodCloseController(PeriodCloseService periods,ApprovalService approvals){this.periods=periods;this.approvals=approvals;}
    @GetMapping Map<String,Object> dashboard(JwtAuthenticationToken jwt,@PathVariable UUID bankId){return periods.dashboard(actor(jwt),bankId);}
    @PostMapping("/policy") @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request policy(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody PeriodCloseModels.Policy input){return approvals.periodClosePolicy(actor(jwt),key,bankId,input);}
    @PostMapping("/preview") PeriodCloseModels.Plan preview(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@Valid @RequestBody PeriodCloseModels.Run input){return periods.preview(actor(jwt),bankId,input);}
    @PostMapping("/runs") @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request run(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody PeriodCloseModels.Run input){return approvals.periodClose(actor(jwt),key,bankId,input);}
    @PostMapping("/runs/{id}/retry") @ResponseStatus(HttpStatus.NO_CONTENT) void retry(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id){periods.retry(actor(jwt),bankId,id);}
}
