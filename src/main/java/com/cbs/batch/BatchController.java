package com.cbs.batch;

import static com.cbs.access.RequestIdentity.actor;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** P-07 operations console: the bank's daily cycle and all pending work on one page. */
@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/operations-console")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class BatchController {
    private final BatchService batch;
    public BatchController(BatchService batch){this.batch=batch;}
    @GetMapping Map<String,Object> console(JwtAuthenticationToken jwt,@PathVariable UUID bankId){return batch.console(actor(jwt),bankId);}
}
