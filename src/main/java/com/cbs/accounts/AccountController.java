package com.cbs.accounts;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.accounts.AccountModels.*;
import com.cbs.approvals.*;
import com.cbs.tenancy.TenantModels.ChangeRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/accounts")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class AccountController {
    private final AccountService accounts;private final ApprovalService approvals;private final com.cbs.accounts.FundingService funding;
    public AccountController(AccountService accounts,ApprovalService approvals,com.cbs.accounts.FundingService funding){this.funding=funding;this.accounts=accounts;this.approvals=approvals;}
    @GetMapping Directory list(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam(defaultValue="") String search,@RequestParam(required=false) State state,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return accounts.list(actor(jwt),bankId,search,state,limit,offset);}
    @GetMapping("/dormancy-candidates")
    java.util.List<java.util.Map<String,Object>> dormancyCandidates(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam(defaultValue="365") int thresholdDays){
        return accounts.dormancyCandidates(actor(jwt),bankId,thresholdDays);
    }
    @GetMapping("/unclaimed-assets")
    java.util.List<java.util.Map<String,Object>> unclaimedAssets(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam(defaultValue="1825") int dormancyDays){
        return accounts.unclaimedAssets(actor(jwt),bankId,dormancyDays);
    }
    @GetMapping("/{id}") Detail detail(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id){return accounts.detail(actor(jwt),bankId,id);}
    @GetMapping("/{id}/closure") AccountClosureService.Check closure(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id){return accounts.closure(actor(jwt),bankId,id);}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request change(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Change input){return approvals.account(actor(jwt),key,bankId,input);}
    @PostMapping("/prepare") @ResponseStatus(HttpStatus.NO_CONTENT)
    void prepare(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@Valid @RequestBody ChangeRequest input){accounts.initialize(actor(jwt),bankId,input);}
    @PostMapping("/{requestId}/retry") @ResponseStatus(HttpStatus.NO_CONTENT)
    void retry(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID requestId){if(funding.handles(bankId,requestId))funding.retry(actor(jwt),bankId,requestId);else accounts.retry(actor(jwt),bankId,requestId);}
}
