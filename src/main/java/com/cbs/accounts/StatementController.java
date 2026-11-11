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
 /** Streams the whole range as CSV with no entry cap. */
 @GetMapping(value="/export",produces="text/csv")
 void export(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID accountId,@RequestParam LocalDate from,@RequestParam LocalDate to,jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException{
  headers(response,"text/csv;charset=UTF-8","statement-"+accountId+"-"+from+"-"+to+".csv");
  var writer=new java.io.BufferedWriter(new java.io.OutputStreamWriter(response.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8));
  statements.stream(actor(jwt),bankId,accountId,from,to,"CSV",StatementCsv.sink(writer));
 }
 /** Printable PDF statement; language defaults to the bank's default language (en, fr or sw). */
 @GetMapping(value="/pdf",produces="application/pdf")
 void pdf(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID accountId,@RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam(required=false) String language,jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException{
  if(language!=null&&!StatementPdf.LANGUAGES.contains(language))throw new com.cbs.tenancy.TenantException(400,"STATEMENT_LANGUAGE_INVALID","Choose en, fr or sw");
  // Render to a temporary buffer first so a failure never sends a truncated PDF.
  var buffer=new org.springframework.util.FastByteArrayOutputStream();
  statements.stream(actor(jwt),bankId,accountId,from,to,"PDF",new StatementPdf(buffer,language,java.time.Instant.now()));
  headers(response,"application/pdf","statement-"+accountId+"-"+from+"-"+to+".pdf");response.setContentLength(buffer.size());buffer.writeTo(response.getOutputStream());
 }
 private static void headers(jakarta.servlet.http.HttpServletResponse response,String type,String file){
  response.setContentType(type);response.setHeader("Content-Disposition","attachment; filename=\""+file+"\"");response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
 }
}
