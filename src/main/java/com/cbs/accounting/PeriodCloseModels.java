package com.cbs.accounting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;

public final class PeriodCloseModels {
    private PeriodCloseModels() {}
    public enum Type { MONTH, YEAR }
    /** Where one legal entity's profit or loss for one currency is carried at year end. */
    public record RetainedEarnings(@NotNull UUID legalEntityId,@NotNull @Pattern(regexp="[A-Z]{3}") String currency,@NotNull UUID ledgerId) {}
    /** Once approved, every month must be closed before its last business day closes. */
    public record Policy(@PositiveOrZero long expectedVersion,@Min(1) @Max(12) int fiscalYearEndMonth,
        @NotNull @Size(max=50) List<@Valid @NotNull RetainedEarnings> retainedEarnings,
        @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
    public record Run(@PositiveOrZero long expectedVersion,@NotNull LocalDate businessDate,@NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
    /** Trial balance of one legal entity and currency at the period end. */
    public record Total(UUID legalEntityId,String currency,String debits,String credits,String income,String expense,String netResult) {}
    public record Plan(UUID id,UUID bankId,Type type,LocalDate periodEnd,List<Total> totals,List<JournalModels.Entry> journals) {}
    public record Dispatch(Plan plan,String maker,String issuer,String checker,UUID correlationId) {}
}
