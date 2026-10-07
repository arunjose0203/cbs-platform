package com.cbs.accounting;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.accounting.JournalModels.*;
import com.cbs.approvals.*;
import com.cbs.tenancy.TenantModels.Page;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/journals")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class JournalController {
    private final com.cbs.accounts.OperationService operations;
    private final JournalService journals;private final ApprovalService approvals;private final com.cbs.accounts.FundingService funding;
    public JournalController(JournalService journals,ApprovalService approvals,com.cbs.accounts.FundingService funding,com.cbs.accounts.OperationService operations){this.operations=operations;this.funding=funding;this.journals=journals;this.approvals=approvals;}
    @GetMapping Directory list(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return journals.list(actor(jwt),bankId,limit,offset);}
    @GetMapping("/balances") Page<Balance> balances(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return journals.balances(actor(jwt),bankId,limit,offset);}
    @GetMapping("/{id}") Entry posted(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id){return journals.posted(actor(jwt),bankId,id);}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request submit(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Change input){return approvals.journal(actor(jwt),key,bankId,input);}
    @PostMapping("/{id}/reverse") @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request reverse(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Reversal input){return approvals.reverseJournal(actor(jwt),key,bankId,id,input);}
    @PostMapping("/{id}/retry") @ResponseStatus(HttpStatus.NO_CONTENT)
    void retry(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id){if(funding.handles(bankId,id))funding.retry(actor(jwt),bankId,id);else if(operations.handles(bankId,id))operations.retry(actor(jwt),bankId,id);else journals.retry(actor(jwt),bankId,id);}
}
