package com.cbs.products;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.products.ProductModels.*;
import com.cbs.approvals.*;
import com.cbs.tenancy.TenantModels.Page;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/products")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ProductController {
    private final ProductService products;private final ApprovalService approvals;
    public ProductController(ProductService products,ApprovalService approvals){this.products=products;this.approvals=approvals;}
    @GetMapping Catalogue list(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam(defaultValue="") String search,@RequestParam(required=false) Type type,@RequestParam(required=false) Boolean active,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return products.list(actor(jwt),bankId,search,type,active,limit,offset);}
    @GetMapping("/{id}/revisions") Page<Revision> history(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return products.history(actor(jwt),bankId,id,limit,offset);}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request change(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Change input){return approvals.product(actor(jwt),key,bankId,input);}
}
