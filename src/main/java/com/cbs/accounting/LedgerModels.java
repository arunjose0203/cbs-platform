package com.cbs.accounting;

import jakarta.validation.constraints.*;
import java.util.*;

public final class LedgerModels {
    private LedgerModels(){}
    public record Change(@Min(0) long expectedVersion,UUID ledgerId,
            @NotBlank @Pattern(regexp="[A-Z0-9][A-Z0-9_-]{0,31}") String code,
            @NotBlank @Size(max=160) @Pattern(regexp="[^\\p{Cntrl}]+") String name,
            @NotNull UUID chartAccountId,@NotNull UUID legalEntityId,
            @NotBlank @Pattern(regexp="[A-Z]{3}") String currency,@NotNull Boolean active,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason){}
    public record Definition(UUID id,UUID bankId,String code,String name,UUID chartAccountId,UUID legalEntityId,
            String currency,boolean active,long version){}
    public record Account(Definition definition,String chartCode,String legalEntityName,String delivery,UUID requestId,String failureCode){}
    public record Directory(UUID bankId,long version,boolean storageReady,List<Account> items,long total,int limit,int offset){}
    public record Dispatch(UUID requestId,Definition definition,String makerSubject,String reviewerIssuer,String reviewerSubject,UUID correlationId){}
}
