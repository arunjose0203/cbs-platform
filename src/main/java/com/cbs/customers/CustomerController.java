package com.cbs.customers;

import static com.cbs.access.RequestIdentity.actor;
import static com.cbs.customers.CustomerModels.*;
import com.cbs.approvals.*;
import com.cbs.tenancy.TenantModels.ChangeRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tenants/{bankId}/customers")
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class CustomerController {
    private final CustomerService customers;
    private final ApprovalService approvals;
    private final CustomerDocumentService documents;

    public CustomerController(CustomerService customers,ApprovalService approvals,CustomerDocumentService documents){
        this.customers=customers;
        this.approvals=approvals;
        this.documents=documents;
    }

    @GetMapping Directory list(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestParam(defaultValue="") String search,@RequestParam(required=false) State state,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset){return customers.list(actor(jwt),bankId,search,state,limit,offset);}
    @GetMapping("/kyc-reviews/overdue")
    java.util.List<KycReviewSummary> listOverdueKycReviews(JwtAuthenticationToken jwt,@PathVariable UUID bankId){
        return customers.listOverdueReviews(actor(jwt),bankId);
    }
    @GetMapping("/{id}") Detail detail(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id){return customers.detail(actor(jwt),bankId,id);}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    ApprovalModels.Request change(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Change input){return approvals.customer(actor(jwt),key,bankId,input);}
    @PostMapping("/prepare") @ResponseStatus(HttpStatus.NO_CONTENT)
    void prepare(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@Valid @RequestBody ChangeRequest input){customers.initialize(actor(jwt),bankId,input);}
    @PostMapping("/{requestId}/retry") @ResponseStatus(HttpStatus.NO_CONTENT)
    void retry(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID requestId){customers.retry(actor(jwt),bankId,requestId);}

    @PostMapping("/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    DocumentSummary uploadDocument(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id,@Valid @RequestBody DocumentUpload input){
        return documents.upload(actor(jwt),bankId,id,input);
    }

    @GetMapping("/{id}/documents")
    java.util.List<DocumentSummary> listDocuments(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id){
        return documents.list(actor(jwt),bankId,id);
    }

    @GetMapping("/{id}/documents/{documentId}/download")
    org.springframework.http.ResponseEntity<byte[]> downloadDocument(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id,@PathVariable UUID documentId){
        var doc = documents.download(actor(jwt),bankId,id,documentId);
        return org.springframework.http.ResponseEntity.ok()
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+doc.fileName()+"\"")
            .contentType(org.springframework.http.MediaType.parseMediaType(doc.contentType()))
            .body(doc.data());
    }

    @PostMapping("/{id}/documents/{documentId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archiveDocument(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id,@PathVariable UUID documentId){
        documents.archive(actor(jwt),bankId,id,documentId);
    }

    @PostMapping("/{id}/kyc-reviews")
    @ResponseStatus(HttpStatus.CREATED)
    KycReviewSummary recordKycReview(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id,@Valid @RequestBody KycReviewInput input){
        return customers.recordReview(actor(jwt),bankId,id,input);
    }

    @GetMapping("/{id}/kyc-reviews")
    java.util.List<KycReviewSummary> listKycReviews(JwtAuthenticationToken jwt,@PathVariable UUID bankId,@PathVariable UUID id){
        return customers.listReviews(actor(jwt),bankId,id);
    }
}
