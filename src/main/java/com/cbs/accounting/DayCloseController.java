package com.cbs.accounting;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.accounting.DayCloseModels.*;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/business-day")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class DayCloseController {
    private final DayCloseService days;private final ApprovalService approvals;
    public DayCloseController(DayCloseService days,ApprovalService approvals){this.days=days;this.approvals=approvals;}
    @GetMapping Dashboard dashboard(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return days.dashboard(actor(jwt),bankId,limit,offset);}
    @PostMapping("/close") @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request close(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Change input){return approvals.closeDay(actor(jwt),key,bankId,input);}
    @PostMapping("/{requestId}/retry") @ResponseStatus(HttpStatus.NO_CONTENT)
    void retry(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID requestId){days.retry(actor(jwt),bankId,requestId);}
}
