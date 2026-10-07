package com.cbs.accounting;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.accounting.LedgerModels.*;
import com.cbs.approvals.*;
import com.cbs.tenancy.TenantModels.ChangeRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/ledger-accounts")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class LedgerController {
    private final LedgerService ledgers;private final ApprovalService approvals;
    public LedgerController(LedgerService ledgers,ApprovalService approvals){this.ledgers=ledgers;this.approvals=approvals;}
    @GetMapping Directory list(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam(defaultValue="") String search,@RequestParam(required=false) String currency,
            @RequestParam(required=false) Boolean active,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return ledgers.list(actor(jwt),bankId,search,currency,active,limit,offset);}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request change(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Change input){return approvals.ledger(actor(jwt),key,bankId,input);}
    @PostMapping("/prepare") @ResponseStatus(HttpStatus.NO_CONTENT)
    void prepare(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@Valid @RequestBody ChangeRequest input){ledgers.initialize(actor(jwt),bankId,input);}
    @PostMapping("/deliveries/{requestId}/retry") @ResponseStatus(HttpStatus.NO_CONTENT)
    void retry(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID requestId){ledgers.retry(actor(jwt),bankId,requestId);}
}
