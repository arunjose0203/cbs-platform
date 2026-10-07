package com.cbs.accounting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;

public final class JournalModels {
    private JournalModels() {}
    public enum Side { DEBIT, CREDIT }
    public record Line(@NotNull UUID ledgerId,@NotNull Side side,
            @NotBlank @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String amount) {}
    public record Change(@Min(0) long expectedVersion,
            @NotBlank @Size(max=80) @Pattern(regexp="[^\\p{Cntrl}]+") String reference,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String narration,
            @NotNull LocalDate businessDate,@NotNull UUID legalEntityId,
            @NotBlank @Pattern(regexp="[A-Z]{3}") String currency,
            @NotNull @Size(min=2,max=100) List<@NotNull @Valid Line> lines,UUID reversalOf,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
    public record Reversal(@Min(0) long expectedVersion,
            @NotBlank @Size(max=80) @Pattern(regexp="[^\\p{Cntrl}]+") String reference,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
    public record Entry(UUID id,UUID bankId,String reference,String narration,LocalDate businessDate,
            UUID legalEntityId,String currency,List<Line> lines,UUID reversalOf) {}
    public record Dispatch(Entry entry,String maker,String issuer,String checker,UUID correlationId) {}
    public record Item(Entry entry,String postingStatus,String failureCode,boolean funding) {}
    public record Directory(UUID bankId,long version,boolean storageReady,List<Item> items,long total,int limit,int offset) {}
    /** debit/credit are cumulative turnover; netDebit is debit minus credit, never a floating point number. */
    public record Balance(UUID ledgerId,String code,String name,UUID legalEntityId,String currency,
            String debit,String credit,String netDebit) {}
}
