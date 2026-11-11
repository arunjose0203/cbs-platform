package com.cbs.mandates;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class MandateController {
    private final MandateService mandates;private final ApprovalService approvals;
    public MandateController(MandateService mandates,ApprovalService approvals){this.mandates=mandates;this.approvals=approvals;}
    @GetMapping("/customers/{customerId}/parties") List<MandateModels.PartyView> parties(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID customerId){return mandates.parties(actor(jwt),bankId,customerId);}
    @PostMapping("/customers/parties") @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request changeParties(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody MandateModels.Parties input){return approvals.customerParties(actor(jwt),key,bankId,input);}
    @GetMapping("/accounts/{accountId}/mandate") MandateModels.View mandate(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID accountId){return mandates.view(actor(jwt),bankId,accountId);}
    @PostMapping("/accounts/mandates") @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request changeMandate(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody MandateModels.Mandate input){return approvals.accountMandate(actor(jwt),key,bankId,input);}
}
