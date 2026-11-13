package com.cbs.migration;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MigrationModels {
    private MigrationModels() {}

    public enum BatchType {
        CUSTOMERS, ACCOUNTS, LOANS, TERM_DEPOSITS, DELTA
    }

    public enum BatchStatus {
        VALIDATED, STAGED, COMMITTED, ROLLED_BACK, FAILED
    }

    public record CustomerRecord(
            @NotBlank String legacyId,
            @NotBlank String nationalId,
            @NotBlank String fullName,
            String phone,
            String email,
            LocalDate dateOfBirth,
            String nationality,
            String kycDocumentReference
    ) {}

    public record AccountRecord(
            @NotBlank String legacyAccountId,
            @NotBlank String legacyCustomerId,
            @NotBlank String accountNumber,
            @NotBlank String productCode,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull @Pattern(regexp = "[0-9]{1,18}(\\.[0-9]{1,6})?") String openingBalance,
            String interestRate
    ) {}

    public record LoanRecord(
            @NotBlank String legacyLoanId,
            @NotBlank String legacyCustomerId,
            @NotBlank String accountNumber,
            @NotBlank String agreementReference,
            @NotNull @Pattern(regexp = "[0-9]{1,18}(\\.[0-9]{1,6})?") String principalDisbursed,
            @NotNull @Pattern(regexp = "[0-9]{1,18}(\\.[0-9]{1,6})?") String outstandingPrincipal,
            @NotNull @Pattern(regexp = "[0-9]{1,3}(\\.[0-9]{1,8})?") String annualRatePercent,
            @NotNull LocalDate startDate,
            @NotNull LocalDate maturityDate,
            int instalments,
            @Pattern(regexp = "[0-9]{1,18}(\\.[0-9]{1,6})?") String accruedInterest,
            int daysPastDue,
            @Pattern(regexp = "[0-9]{1,18}(\\.[0-9]{1,6})?") String allowance
    ) {}

    public record TermDepositRecord(
            @NotBlank String legacyDepositId,
            @NotBlank String legacyCustomerId,
            @NotBlank String accountNumber,
            @NotBlank String agreementReference,
            @NotNull @Pattern(regexp = "[0-9]{1,18}(\\.[0-9]{1,6})?") String principal,
            @NotNull @Pattern(regexp = "[0-9]{1,3}(\\.[0-9]{1,8})?") String annualRatePercent,
            @Pattern(regexp = "[0-9]{1,3}(\\.[0-9]{1,8})?") String withholdingPercent,
            @NotNull LocalDate startDate,
            @NotNull LocalDate maturityDate,
            @Pattern(regexp = "[0-9]{1,18}(\\.[0-9]{1,6})?") String accruedInterest
    ) {}

    public record ValidationFinding(
            String legacyId,
            String field,
            String code,
            String message,
            String severity
    ) {}

    public record ValidationReport(
            UUID batchId,
            BatchType batchType,
            int totalRecords,
            int validCount,
            int errorCount,
            List<ValidationFinding> findings
    ) {}

    public record BatchSummary(
            UUID id,
            UUID tenantId,
            BatchType batchType,
            BatchStatus status,
            int totalRecords,
            int validRecords,
            int errorRecords,
            Instant createdAt,
            Instant committedAt,
            Instant rolledBackAt
    ) {}

    public record TrialBalanceReconciliation(
            UUID id,
            UUID tenantId,
            LocalDate businessDate,
            String status,
            String legacyTotal,
            String platformTotal,
            String difference,
            Map<String, Object> breakdown,
            Instant reconciledAt
    ) {}
}
