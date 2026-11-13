package com.cbs.accounts;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import com.cbs.products.ProductModels.Product;
public final class AccountModels {
 private AccountModels() {}
 public enum State { AWAITING_FUNDING, ACTIVE, FROZEN, DORMANT, CLOSED }
 public record Change(@Min(0) long expectedVersion,UUID accountId,@NotNull UUID customerId,@NotNull UUID productId,@Min(1) int productRevision,@NotNull UUID legalEntityId,@NotBlank @Size(max=120) @Pattern(regexp="[^\\p{Cntrl}]+") String name,@NotNull State state,@NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
 public record Definition(UUID id,UUID bankId,String accountNumber,long version,UUID customerId,String customerNumber,UUID legalEntityId,String legalEntityName,String name,State state,Product terms,Instant openedAt) {}
 public record Dispatch(UUID requestId,Definition definition,String makerSubject,String reviewerIssuer,String reviewerSubject,UUID correlationId) {}
 public record Summary(UUID id,String accountNumber,String name,String customerNumber,String productCode,String currency,State state,long version,String delivery,UUID requestId,String failureCode) {}
 public record Directory(UUID bankId,long version,boolean storageReady,List<Summary> items,long total,int limit,int offset) {}
 public record Detail(Definition definition,String delivery,UUID requestId,String failureCode) {}
}
