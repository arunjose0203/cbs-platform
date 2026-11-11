package com.cbs.reporting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public final class ReportModels {
    private ReportModels() {}
    public enum Statement { BALANCE_SHEET, INCOME_STATEMENT }
    /**
     * One printed line. {@code amount} and {@code comparative} use the account's normal balance,
     * so assets and expenses are positive when debit, liabilities, equity and income when credit.
     */
    public record Row(String section,int level,String code,String name,String amount,String comparative,boolean total) {}
    public record Report(Statement kind,String bankName,UUID legalEntityId,String legalEntityName,String currency,
        LocalDate from,LocalDate to,LocalDate compareFrom,LocalDate compareTo,Instant generatedAt,List<Row> rows,Map<String,String> totals,boolean balanced) {}

    /** Lines of a regulatory or management template, each the signed sum of chart accounts. */
    public record Line(@NotBlank @Size(max=20) @Pattern(regexp="[A-Za-z0-9._-]+") String code,
        @NotBlank @Size(max=200) @Pattern(regexp="[^\\p{Cntrl}]+") String label,
        @NotNull @Size(max=200) List<@NotNull UUID> chartAccounts,
        @Min(-1) @Max(1) int sign) {}
    /** basis BALANCE reads balances as at a date; MOVEMENT reads activity over a period. */
    public enum Basis { BALANCE, MOVEMENT }
    public record Definition(@PositiveOrZero long expectedVersion,@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{1,31}") String code,
        @NotBlank @Size(max=200) @Pattern(regexp="[^\\p{Cntrl}]+") String name,@NotNull Basis basis,
        @NotNull @Size(min=1,max=500) List<@Valid @NotNull Line> lines,
        @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
    public record Result(String code,String name,Basis basis,UUID legalEntityId,String currency,LocalDate from,LocalDate to,Instant generatedAt,List<List<String>> lines) {}
}
