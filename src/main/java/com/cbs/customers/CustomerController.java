package com.cbs.customers;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.customers.CustomerModels.*;
import com.cbs.approvals.*;
import com.cbs.tenancy.TenantModels.ChangeRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/customers")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class CustomerController {
    private final CustomerService customers;private final ApprovalService approvals;
    public CustomerController(CustomerService customers,ApprovalService approvals){this.customers=customers;this.approvals=approvals;}
    @GetMapping Directory list(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam(defaultValue="") String search,@RequestParam(required=false) State state,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return customers.list(actor(jwt),bankId,search,state,limit,offset);}
    @GetMapping("/{id}") Detail detail(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id){return customers.detail(actor(jwt),bankId,id);}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request change(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Change input){return approvals.customer(actor(jwt),key,bankId,input);}
    @PostMapping("/prepare") @ResponseStatus(HttpStatus.NO_CONTENT)
    void prepare(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@Valid @RequestBody ChangeRequest input){customers.initialize(actor(jwt),bankId,input);}
    @PostMapping("/{requestId}/retry") @ResponseStatus(HttpStatus.NO_CONTENT)
    void retry(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID requestId){customers.retry(actor(jwt),bankId,requestId);}
}
