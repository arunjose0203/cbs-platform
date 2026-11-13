package com.cbs.compliance;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bank}/compliance")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ComplianceController {
    private final ComplianceService service;private final ApprovalService approvals;
    public ComplianceController(ComplianceService service,ApprovalService approvals){this.service=service;this.approvals=approvals;}
    private StrDraftService strDrafts;
    @org.springframework.beans.factory.annotation.Autowired void strDrafts(StrDraftService strDrafts){this.strDrafts=strDrafts;}
    @GetMapping public Map<String,Object> list(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="ALL") String status,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return service.list(actor(jwt),bank,status,limit,offset);}
    @GetMapping("/{id}") public Map<String,Object> detail(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){return service.detail(actor(jwt),bank,id);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> open(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody ComplianceModels.Open input){return service.open(actor(jwt),bank,key,input);}
    @PostMapping("/{id}/notes") public Map<String,Object> note(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody ComplianceModels.Note input){return service.note(actor(jwt),bank,id,key,input);}
    @PostMapping("/{id}/assignment") public Map<String,Object> assign(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody ComplianceModels.Assign input){return service.assign(actor(jwt),bank,id,key,input);}
    @PostMapping("/{id}/str-drafts") public Map<String,Object> strDraft(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id,@Valid @RequestBody StrDraftService.Request input){return strDrafts.draft(actor(jwt),bank,id,input);}
    @PostMapping("/reviews") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApprovalModels.Request review(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody ComplianceModels.Review input){return approvals.compliance(actor(jwt),key,bank,input);}
}
