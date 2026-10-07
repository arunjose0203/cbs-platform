package com.cbs.accounts;
import static com.cbs.access.RequestIdentity.actor;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/accounts/{accountId}/statement")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class StatementController {
 private final StatementService statements;
 public StatementController(StatementService statements){this.statements=statements;}
 @GetMapping StatementService.Statement read(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID accountId,@RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return statements.read(actor(jwt),bankId,accountId,from,to,limit,offset);}
 @GetMapping(value="/export",produces="text/csv")
 org.springframework.http.ResponseEntity<byte[]> export(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID accountId,@RequestParam LocalDate from,@RequestParam LocalDate to){
  var statement=statements.export(actor(jwt),bankId,accountId,from,to);
  return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","attachment; filename=\"statement-"+accountId+".csv\"").header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8")).body(StatementCsv.render(statement).getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
}
