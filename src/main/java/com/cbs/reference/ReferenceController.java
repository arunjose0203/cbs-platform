package com.cbs.reference;

import static com.cbs.reference.ReferenceModels.*;
import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.ApprovalModels.Request;
import com.cbs.approvals.ApprovalService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ReferenceController {
    private final ReferenceDataService reference;
    private final ApprovalService approvals;
    public ReferenceController(ReferenceDataService reference,ApprovalService approvals) { this.reference=reference;this.approvals=approvals; }
    @GetMapping("/admin/reference-data/catalogue") Catalogue catalogue(JwtAuthenticationToken jwt) {return reference.catalogue(actor(jwt));}
    @GetMapping("/admin/tenants/{id}/settings") Settings settings(JwtAuthenticationToken jwt,@PathVariable UUID id) {
        com.cbs.access.AccessService.admin(actor(jwt));return reference.get(actor(jwt),id);
    }
    @PutMapping("/admin/tenants/{id}/settings") @ResponseStatus(HttpStatus.ACCEPTED)
    Request update(JwtAuthenticationToken jwt,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Change input) {
        return approvals.settings(actor(jwt),key,id,input);
    }
    @GetMapping("/banks/{id}/reference-data") Settings bankSettings(JwtAuthenticationToken jwt,@PathVariable UUID id) {
        // Bank routes always require bank membership, including for platform administrators.
        var bankActor=new com.cbs.tenancy.TenantModels.Actor(actor(jwt).issuer(),actor(jwt).subject(),false,actor(jwt).correlationId());
        return reference.get(bankActor,id);
    }
    @GetMapping("/admin/tenants/{id}/calendar-day") DayInfo day(JwtAuthenticationToken jwt,@PathVariable UUID id,@RequestParam LocalDate date) {
        com.cbs.access.AccessService.admin(actor(jwt));return reference.day(actor(jwt),id,date);
    }
    @GetMapping("/admin/tenants/{id}/rounding-preview") AmountPreview round(JwtAuthenticationToken jwt,@PathVariable UUID id,@RequestParam String currency,@RequestParam String amount) {
        com.cbs.access.AccessService.admin(actor(jwt));return reference.round(actor(jwt),id,currency,amount);
    }
    @GetMapping("/access/preferences") Preference preference(JwtAuthenticationToken jwt) {return reference.preference(actor(jwt));}
    @PutMapping("/access/preferences") Preference preference(JwtAuthenticationToken jwt,@Valid @RequestBody Preference input) {return reference.preference(actor(jwt),input);}
}
