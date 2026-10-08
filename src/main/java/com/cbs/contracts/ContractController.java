package com.cbs.contracts;

import static com.cbs.access.RequestIdentity.actor;
import com.cbs.approvals.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bank}/contracts")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ContractController {
    private final LoanFeeService fees;private final LoanServicingService loans;private final TermServicingService servicing;private final ContractService service;private final ApprovalService approvals;
    public ContractController(LoanFeeService fees,LoanServicingService loans,TermServicingService servicing,ContractService service,ApprovalService approvals){this.fees=fees;this.loans=loans;this.servicing=servicing;this.service=service;this.approvals=approvals;}
    @PostMapping("/fees/preview") public ContractModels.Posting feePreview(JwtAuthenticationToken jwt,@PathVariable UUID bank,@Valid @RequestBody LoanFeeModels.Change input){return fees.preview(actor(jwt),bank,input);}
    @PostMapping("/fees") public ApprovalModels.Request fee(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody LoanFeeModels.Change input){return approvals.loanFee(actor(jwt),key,bank,input);}
    @PostMapping("/loan-servicing/preview") public ContractModels.Posting loanPreview(JwtAuthenticationToken jwt,@PathVariable UUID bank,@Valid @RequestBody LoanServicingModels.Instruction input){return loans.preview(actor(jwt),bank,input);}
    @PostMapping("/loan-servicing") public ApprovalModels.Request loanService(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody LoanServicingModels.Instruction input){return approvals.loanServicing(actor(jwt),key,bank,input);}
    @GetMapping("/{id}/collections") public Map<String,Object> collections(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){return loans.collections(actor(jwt),bank,id);}
    @PostMapping("/collections") public void collection(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody LoanServicingModels.Collection input){loans.collection(actor(jwt),bank,key,input);}
    @GetMapping public Map<String,Object> list(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam ContractModels.Type type,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return service.list(actor(jwt),bank,type,limit,offset);}
    @GetMapping("/{id}/history") public Map<String,Object> history(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return service.history(actor(jwt),bank,id,limit,offset);}
    @PostMapping("/corrections/preview") public ContractModels.Posting previewCorrection(JwtAuthenticationToken jwt,@PathVariable UUID bank,@Valid @RequestBody ContractModels.Correction input){return service.previewCorrection(actor(jwt),bank,input);}
    @PostMapping("/corrections") public ApprovalModels.Request correction(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody ContractModels.Correction input){return approvals.contractCorrection(actor(jwt),key,bank,input);}
    @GetMapping("/maturity-alerts") public Map<String,Object> alerts(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestParam(defaultValue="7") int days,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return servicing.alerts(actor(jwt),bank,days,limit,offset);}
    @PostMapping("/servicing/preview") public ContractModels.Posting previewServicing(JwtAuthenticationToken jwt,@PathVariable UUID bank,@Valid @RequestBody TermServicingModels.Instruction input){return servicing.preview(actor(jwt),bank,input);}
    @PostMapping("/servicing") public ApprovalModels.Request servicing(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody TermServicingModels.Instruction input){return approvals.termServicing(actor(jwt),key,bank,input);}
    @PostMapping("/prepare") public void prepare(JwtAuthenticationToken jwt,@PathVariable UUID bank){service.prepare(actor(jwt),bank);}
    @PostMapping("/preview") public ContractModels.Posting preview(JwtAuthenticationToken jwt,@PathVariable UUID bank,@Valid @RequestBody ContractModels.Open input){return service.previewOpen(actor(jwt),bank,input);}
    @PostMapping public ApprovalModels.Request open(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody ContractModels.Open input){return approvals.contractOpen(actor(jwt),key,bank,input);}
    @PostMapping("/events/preview") public ContractModels.Posting previewEvent(JwtAuthenticationToken jwt,@PathVariable UUID bank,@Valid @RequestBody ContractModels.Event input){return service.previewEvent(actor(jwt),bank,input);}
    @PostMapping("/events") public ApprovalModels.Request event(JwtAuthenticationToken jwt,@PathVariable UUID bank,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody ContractModels.Event input){return approvals.contractEvent(actor(jwt),key,bank,input);}
    @PostMapping("/events/{id}/retry") public void retry(JwtAuthenticationToken jwt,@PathVariable UUID bank,@PathVariable UUID id){service.retry(actor(jwt),bank,id);}
}
