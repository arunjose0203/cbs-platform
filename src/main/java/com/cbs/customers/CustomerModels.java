package com.cbs.customers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;

public final class CustomerModels {
    private CustomerModels() { }
    public enum State { PROSPECT, ACTIVE, BLOCKED }
    public enum DocumentType { NATIONAL_ID, PASSPORT }
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
        @NotNull @Size(max=160) @Pattern(regexp="[A-Za-z0-9._/-]*") String reviewReference) { }
    public record Change(@Min(0) long expectedVersion,UUID customerId,@NotNull @Valid Profile profile,
        @NotNull @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]*") String duplicateReview,
        @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record Definition(UUID id,UUID bankId,String customerNumber,long version,Profile profile) { }
    public record Dispatch(UUID requestId,Definition definition,String makerSubject,String reviewerIssuer,String reviewerSubject,UUID correlationId) { }
    public record Summary(UUID id,String customerNumber,String fullName,State state,long version,String delivery,UUID requestId,String failureCode) { }
    public record Directory(UUID bankId,long version,boolean storageReady,List<Summary> items,long total,int limit,int offset) { }
    public record Detail(Definition definition,String delivery,UUID requestId,String failureCode) { }
}
