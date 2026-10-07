package com.cbs.accounting;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.accounting.AccountingReportService.*;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/accounting-reports")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class AccountingReportController {
    private final AccountingReportService reports;
    public AccountingReportController(AccountingReportService reports){this.reports=reports;}
    @GetMapping
    ResponseEntity<?> report(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam Kind kind,
            @RequestParam UUID legalEntityId,@RequestParam String currency,@RequestParam(required=false) LocalDate fromDate,
            @RequestParam(required=false) LocalDate toDate,@RequestParam(required=false) UUID ledgerId,
            @RequestParam(defaultValue="false") boolean csv){
        Report report=reports.generate(actor(jwt),bankId,kind,legalEntityId,currency,fromDate,toDate,ledgerId);
        if(csv)return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=accounting-report.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(ReportCsv.write(report));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(report);
    }
}
