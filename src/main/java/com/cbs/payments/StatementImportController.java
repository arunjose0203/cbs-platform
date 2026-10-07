package com.cbs.payments;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bank}/statement-imports")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class StatementImportController {
 private final StatementImportService service;private final ApprovalService approvals;
 public StatementImportController(StatementImportService service,ApprovalService approvals){this.service=service;this.approvals=approvals;}
 @GetMapping public Map<String,Object> list(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return service.list(actor(jwt),bank,limit,offset);}
 @PostMapping("/preview") public Map<String,Object> preview(JwtAuthenticationToken jwt,@PathVariable UUID bank,@Valid @RequestBody StatementImportModels.Upload input){return service.preview(actor(jwt),bank,input);}
 @PostMapping public Map<String,UUID> upload(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody StatementImportModels.Upload input){return Map.of("id",service.upload(actor(jwt),bank,key,input));}
 @GetMapping("/{id}") public Map<String,Object> detail(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="false") boolean exceptions){return service.detail(actor(jwt),bank,limit,offset,id,exceptions);}
 @GetMapping("/{id}/export") public Map<String,String> export(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){return service.export(actor(jwt),bank,id);}
 @PostMapping("/actions") public ApprovalModels.Request action(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody StatementImportModels.Action input){return approvals.statementAction(actor(jwt),key,bank,input);}
}
