package com.cbs.contracts;
import static com.cbs.access.RequestIdentity.actor;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping({"/api/v1/admin/tenants/{bank}/loan-collections","/api/v1/banks/{bank}/lending-operations/loan-collections"})
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class LoanCollectionsController {
 private final LoanCollectionsService service;
 public LoanCollectionsController(LoanCollectionsService service){this.service=service;}
 @GetMapping public Map<String,Object> list(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return service.list(actor(jwt),bank,limit,offset);}
 @GetMapping("/{id}") public Map<String,Object> detail(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return service.detail(actor(jwt),bank,id,limit,offset);}
 @PostMapping public void change(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody LoanCollectionsService.Change input){service.change(actor(jwt),bank,key,input);}
}
