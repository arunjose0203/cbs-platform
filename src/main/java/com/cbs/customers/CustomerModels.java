package com.cbs.customers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;

public final class CustomerModels {
    private CustomerModels() { }
    public enum State { PROSPECT, ACTIVE, BLOCKED, DECEASED }
    public enum DocumentType { NATIONAL_ID, PASSPORT, REGISTRATION_CERTIFICATE }
    /** INDIVIDUAL is assumed when absent, so existing profiles are unchanged. */
    public enum CustomerType { INDIVIDUAL, ORGANIZATION }
    /** Age of majority used for minors' accounts. */
    public static final int MAJORITY_AGE=18;
    public record DocumentUpload(
        @NotNull DocumentType documentType,
        @NotBlank @Size(max=255) String fileName,
        @NotBlank @Size(max=100) String contentType,
        LocalDate retentionUntil,
        @NotBlank String base64Content) {}
    public record DocumentSummary(UUID id,UUID customerId,String documentType,String fileName,String contentType,long fileSize,String sha256,LocalDate retentionUntil,java.time.Instant uploadedAt,String uploadedBy,boolean archived) {}
    public record DecryptedDocument(UUID id,String fileName,String contentType,byte[] data) {}
    /** Body of a KYC review submission (P-10: maker-checker through ApprovalService action KYC_REVIEW). */
    public record KycReviewInput(
        long expectedVersion,
        @NotBlank @Pattern(regexp="LOW|MEDIUM|HIGH") String riskRating,
        @NotNull LocalDate reviewedAt,
        @NotBlank @Size(max=160) String reviewReference,
        @Size(max=1000) String notes) {}
    /** Approval payload for KYC_REVIEW: the maker's review of one customer. */
    public record KycReviewChange(long expectedVersion, UUID customerId, String riskRating, LocalDate reviewedAt, String reviewReference, String notes) {}
    /** Body of a document archive submission (ApprovalService action CUSTOMER_DOCUMENT_ARCHIVE). */
    public record DocumentArchiveInput(long expectedVersion, @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
    /** Approval payload for CUSTOMER_DOCUMENT_ARCHIVE. */
    public record DocumentArchive(long expectedVersion, UUID customerId, UUID documentId, String reason) {}
    public record KycReviewSummary(UUID id,UUID customerId,String riskRating,LocalDate reviewedAt,LocalDate nextReviewDue,String reviewerSubject,String reviewReference,String notes,boolean overdue) {}
    public record Profile(
        @NotBlank @Size(max=160) @Pattern(regexp="[^\\p{Cntrl}]+") String fullName,
        @NotNull LocalDate birthDate,
        @NotNull @Pattern(regexp="[A-Z]{2}") String nationality,
        @NotNull @Pattern(regexp="[A-Z]{2}") String residenceCountry,
        @NotBlank @Size(max=300) @Pattern(regexp="[^\\p{Cntrl}]+") String address,
        @NotBlank @Pattern(regexp="\\+[1-9][0-9]{6,14}") String phone,
        @NotNull @Email @Size(max=200) String email,
        @NotNull DocumentType documentType,
        @NotNull @Pattern(regexp="[A-Z]{2}") String documentCountry,
        @NotBlank @Pattern(regexp="[A-Za-z0-9 -]{3,40}") String documentNumber,
        LocalDate documentExpiry,
        @NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]{0,159}") String documentReference,
        @NotNull State state,
        @NotNull @Size(max=160) @Pattern(regexp="[A-Za-z0-9._/-]*") String reviewReference,
        // For organizations: fullName is the registered name, birthDate the incorporation date,
        // nationality the country of registration and the document the registration certificate.
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) CustomerType type) {
        public Profile(String fullName,LocalDate birthDate,String nationality,String residenceCountry,String address,String phone,String email,DocumentType documentType,String documentCountry,String documentNumber,LocalDate documentExpiry,String documentReference,State state,String reviewReference){
            this(fullName,birthDate,nationality,residenceCountry,address,phone,email,documentType,documentCountry,documentNumber,documentExpiry,documentReference,state,reviewReference,null);
        }
        public CustomerType kind(){return type==null?CustomerType.INDIVIDUAL:type;}
        /** Same profile with another document number (sealed or opened by PersonalDataCipher). */
        public Profile withDocumentNumber(String number){return new Profile(fullName,birthDate,nationality,residenceCountry,address,phone,email,documentType,documentCountry,number,documentExpiry,documentReference,state,reviewReference,type);}
        public boolean minorOn(LocalDate date){return kind()==CustomerType.INDIVIDUAL&&birthDate.plusYears(MAJORITY_AGE).isAfter(date);}
    }
    public record Change(@Min(0) long expectedVersion,UUID customerId,@NotNull @Valid Profile profile,
        @NotNull @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]*") String duplicateReview,
        @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record Definition(UUID id,UUID bankId,String customerNumber,long version,Profile profile) { }
    public record Dispatch(UUID requestId,Definition definition,String makerSubject,String reviewerIssuer,String reviewerSubject,UUID correlationId) { }
    public record Summary(UUID id,String customerNumber,String fullName,State state,long version,String delivery,UUID requestId,String failureCode,CustomerType type) { }
    public record Directory(UUID bankId,long version,boolean storageReady,List<Summary> items,long total,int limit,int offset) { }
    public record Detail(Definition definition,String delivery,UUID requestId,String failureCode) { }
}
