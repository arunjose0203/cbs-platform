package com.cbs.reporting;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.reporting.ReportModels.*;
import com.cbs.approvals.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/reporting")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ReportingController {
    private final FinancialStatementService statements;
    private final ManagementDashboardService dashboard;
    private final ReportDefinitionService definitions;
    private final ApprovalService approvals;
    private final OperationalReportService operational;

    public ReportingController(FinancialStatementService statements,
                               ManagementDashboardService dashboard,
                               ReportDefinitionService definitions,
                               ApprovalService approvals,
                               OperationalReportService operational) {
        this.statements = statements;
        this.dashboard = dashboard;
        this.definitions = definitions;
        this.approvals = approvals;
        this.operational = operational;
    }

    @GetMapping("/dashboard") Map<String,Object> dashboard(JwtAuthenticationToken jwt,@PathVariable UUID bankId){return dashboard.dashboard(actor(jwt),bankId);}

    @GetMapping("/deposit-register")
    public Object depositRegister(JwtAuthenticationToken jwt, @PathVariable UUID bankId,
                                  @RequestParam(required = false) String currency,
                                  @RequestParam(required = false) UUID branchId,
                                  @RequestParam(defaultValue = "json") String format,
                                  HttpServletResponse response) throws IOException {
        return renderOperational(operational.depositRegister(actor(jwt), bankId, currency, branchId), format, response);
    }

    @GetMapping("/loan-register")
    public Object loanRegister(JwtAuthenticationToken jwt, @PathVariable UUID bankId,
                               @RequestParam(required = false) String currency,
                               @RequestParam(defaultValue = "json") String format,
                               HttpServletResponse response) throws IOException {
        return renderOperational(operational.loanRegister(actor(jwt), bankId, currency), format, response);
    }

    @GetMapping("/cash-report")
    public Object cashReport(JwtAuthenticationToken jwt, @PathVariable UUID bankId,
                             @RequestParam(required = false) UUID branchId,
                             @RequestParam(defaultValue = "json") String format,
                             HttpServletResponse response) throws IOException {
        return renderOperational(operational.cashReport(actor(jwt), bankId, branchId), format, response);
    }

    @GetMapping("/tax-remittance")
    public Object taxRemittance(JwtAuthenticationToken jwt, @PathVariable UUID bankId,
                                @RequestParam(required = false) LocalDate from,
                                @RequestParam(required = false) LocalDate to,
                                @RequestParam(defaultValue = "json") String format,
                                HttpServletResponse response) throws IOException {
        return renderOperational(operational.taxRemittance(actor(jwt), bankId, from, to), format, response);
    }

    @GetMapping("/exception-ageing")
    public Object exceptionAgeing(JwtAuthenticationToken jwt, @PathVariable UUID bankId,
                                  @RequestParam(defaultValue = "json") String format,
                                  HttpServletResponse response) throws IOException {
        return renderOperational(operational.exceptionAgeing(actor(jwt), bankId), format, response);
    }

    @GetMapping("/loan-provisions")
    public Object loanProvisions(JwtAuthenticationToken jwt, @PathVariable UUID bankId,
                                 @RequestParam(required = false) String currency,
                                 @RequestParam(defaultValue = "json") String format,
                                 HttpServletResponse response) throws IOException {
        return renderOperational(operational.loanProvisions(actor(jwt), bankId, currency), format, response);
    }

    private Object renderOperational(OperationalReportService.ReportResult r, String format, HttpServletResponse response) throws IOException {
        if ("json".equalsIgnoreCase(format)) return r;
        write(r.table(), format, r.reportName() + "-" + r.businessDate(), response);
        return null;
    }

    /** format: json (default), csv or pdf. */
    @GetMapping("/balance-sheet") Object balanceSheet(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam UUID legalEntityId,@RequestParam String currency,@RequestParam LocalDate asOf,
        @RequestParam(required=false) LocalDate compareTo,@RequestParam(defaultValue="json") String format,HttpServletResponse response)throws IOException{
        return render(statements.balanceSheet(actor(jwt),bankId,legalEntityId,currency,asOf,compareTo),format,response);
    }
    @GetMapping("/income-statement") Object incomeStatement(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam UUID legalEntityId,@RequestParam String currency,@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(required=false) LocalDate compareFrom,@RequestParam(required=false) LocalDate compareTo,@RequestParam(defaultValue="json") String format,HttpServletResponse response)throws IOException{
        return render(statements.incomeStatement(actor(jwt),bankId,legalEntityId,currency,from,to,compareFrom,compareTo),format,response);
    }
    @GetMapping("/definitions") List<Map<String,Object>> definitions(JwtAuthenticationToken jwt,@PathVariable UUID bankId){return definitions.list(actor(jwt),bankId);}
    @GetMapping("/definitions/{code}") Definition definition(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable String code){return definitions.read(actor(jwt),bankId,code);}
    @PostMapping("/definitions") @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request change(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Definition input){return approvals.reportDefinition(actor(jwt),key,bankId,input);}
    @GetMapping("/definitions/{code}/run") Object run(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable String code,@RequestParam UUID legalEntityId,@RequestParam String currency,
        @RequestParam(required=false) LocalDate from,@RequestParam LocalDate to,@RequestParam(defaultValue="json") String format,HttpServletResponse response)throws IOException{
        var r=definitions.run(actor(jwt),bankId,code,legalEntityId,currency,from,to);if(format.equals("json"))return r;
        var period=r.from()==null?"As at "+r.to():r.from()+" to "+r.to();
        write(new TableOutput.Table(r.name()+" ("+r.code()+")",List.of(period+" · "+r.currency()),List.of("Line","Description","Amount"),Set.of(2),r.lines(),Set.of(),r.generatedAt()),format,"report-"+r.code(),response);return null;
    }

    private Object render(Report r,String format,HttpServletResponse response)throws IOException{
        if(format.equals("json"))return r;
        var rows=new ArrayList<List<String>>();var bold=new HashSet<Integer>();
        for(var row:r.rows()){if(row.total())bold.add(rows.size());rows.add(List.of(row.code(),"  ".repeat(Math.max(0,row.level()-1))+row.name(),row.amount(),row.comparative()));}
        String title=(r.kind()==Statement.BALANCE_SHEET?"Balance sheet":"Income statement")+" — "+r.bankName();
        String period=r.kind()==Statement.BALANCE_SHEET?"As at "+r.to()+" (comparative "+r.compareTo()+")":r.from()+" to "+r.to()+" (comparative "+r.compareFrom()+" to "+r.compareTo()+")";
        var subtitle=new ArrayList<>(List.of(r.legalEntityName()+" · "+r.currency(),period));if(!r.balanced())subtitle.add("WARNING: the statement does not balance");
        write(new TableOutput.Table(title,subtitle,List.of("Code","Account",r.kind()==Statement.BALANCE_SHEET?String.valueOf(r.to()):"Current",r.kind()==Statement.BALANCE_SHEET?String.valueOf(r.compareTo()):"Comparative"),Set.of(2,3),rows,bold,r.generatedAt()),format,r.kind()==Statement.BALANCE_SHEET?"balance-sheet":"income-statement",response);
        return null;
    }
    private static void write(TableOutput.Table table,String format,String name,HttpServletResponse response)throws IOException{
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
        if(format.equals("csv")){response.setContentType("text/csv;charset=UTF-8");response.setHeader("Content-Disposition","attachment; filename=\""+name+".csv\"");response.getOutputStream().write(TableOutput.csv(table).getBytes(java.nio.charset.StandardCharsets.UTF_8));return;}
        if(format.equals("pdf")){var buffer=new org.springframework.util.FastByteArrayOutputStream();TableOutput.pdf(table,buffer);response.setContentType("application/pdf");response.setHeader("Content-Disposition","attachment; filename=\""+name+".pdf\"");response.setContentLength(buffer.size());buffer.writeTo(response.getOutputStream());return;}
        throw new com.cbs.tenancy.TenantException(400,"REPORT_FORMAT_INVALID","Choose json, csv or pdf");
    }
}
