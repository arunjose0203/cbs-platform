package com.cbs.accounting;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.accounting.ChartModels.*;
import com.cbs.approvals.ApprovalModels.Request;
import com.cbs.approvals.ApprovalService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/chart-of-accounts")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ChartController {
    private final ChartService chart;
    private final ApprovalService approvals;
    public ChartController(ChartService chart,ApprovalService approvals){this.chart=chart;this.approvals=approvals;}
    @GetMapping
    Chart list(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam(defaultValue="") String search,
            @RequestParam(required=false) Category category,@RequestParam(required=false) Boolean active,@RequestParam(required=false) Kind kind,
            @RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset) {
        return chart.list(actor(jwt),bankId,search,category,active,kind,limit,offset);
    }
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    Request change(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Change input) {
        return approvals.chart(actor(jwt),key,bankId,input);
    }
}
