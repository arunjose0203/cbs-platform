package com.cbs.fx;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.fx.FxValuationModels.*;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bank}/fx/valuation")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class FxValuationController {
    private final FxValuationService service;
    private final ApprovalService approvals;
    private final FxMonetaryTranslationService translation;

    public FxValuationController(FxValuationService service, ApprovalService approvals, FxMonetaryTranslationService translation){
        this.service = service;
        this.approvals = approvals;
        this.translation = translation;
    }

    @GetMapping public Map<String,Object> dashboard(JwtAuthenticationToken jwt,@PathVariable UUID bank){return service.dashboard(actor(jwt),bank);}
    @PostMapping("/books") public ApprovalModels.Request book(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Policy input){return approvals.fxBook(actor(jwt),key,bank,input);}
    @PostMapping("/closing-rates") public ApprovalModels.Request rate(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody ClosingRate input){return approvals.fxClosingRate(actor(jwt),key,bank,input);}
    @PostMapping("/preview") public Posting preview(JwtAuthenticationToken jwt,@PathVariable UUID bank,@Valid @RequestBody Run input){return service.preview(actor(jwt),bank,input);}
    @PostMapping("/runs") public ApprovalModels.Request run(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Run input){return approvals.fxValuation(actor(jwt),key,bank,input);}
    @PostMapping("/runs/{id}/retry") public void retry(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){service.retry(actor(jwt),bank,id);}

    @PostMapping("/runs/{id}/correct")
    public void correct(JwtAuthenticationToken jwt, @PathVariable UUID bank, @PathVariable UUID id, @RequestParam(defaultValue="Correction of prior valuation") String reason) {
        service.correctValuation(actor(jwt), bank, id, reason);
    }

    @GetMapping("/monetary-translation")
    public FxMonetaryTranslationModels.MonetaryTranslationReport monetaryTranslation(
            JwtAuthenticationToken jwt, @PathVariable UUID bank, @RequestParam(required = false) LocalDate asOf) {
        return translation.translate(actor(jwt), bank, asOf);
    }
}
