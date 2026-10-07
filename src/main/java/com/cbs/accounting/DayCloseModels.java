package com.cbs.accounting;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class DayCloseModels {
    private DayCloseModels(){}
    public record Change(@Min(0) long expectedVersion,@NotNull LocalDate businessDate,@NotNull LocalDate nextBusinessDate,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason){}
    public record Dispatch(UUID requestId,UUID bankId,LocalDate businessDate,LocalDate nextBusinessDate,String maker,String issuer,String checker,UUID correlationId){}
    public record Total(String legalEntityId,String currency,String debitBalance,String creditBalance){}
    public record History(UUID requestId,LocalDate businessDate,LocalDate nextBusinessDate,String status,String failureCode,
            String maker,String checker,List<Total> totals,Instant completedAt){}
    public record Dashboard(UUID bankId,long version,LocalDate businessDate,LocalDate nextBusinessDate,LocalDate closedThrough,
            boolean storageReady,List<String> blockers,List<History> history,int limit,int offset){}
}
