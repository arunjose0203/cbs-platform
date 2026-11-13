package com.cbs.credit;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CreditBureauModels {
    private CreditBureauModels() {}

    public enum BureauProvider {
        METROPOL, TRANSUNION, CREDITINFO
    }

    public enum RiskGrade {
        LOW, MEDIUM, HIGH, VERY_HIGH
    }

    public record InquiryRequest(
            @NotNull UUID customerId,
            @NotBlank @Size(max = 50) String nationalId,
            @NotNull BureauProvider provider,
            @NotBlank @Size(max = 500) String reason
    ) {}

    public record InquiryResponse(
            UUID id,
            UUID customerId,
            String nationalId,
            BureauProvider provider,
            int score,
            RiskGrade riskGrade,
            int openAccounts,
            int delinquentAccounts,
            String totalExposure,
            String delinquentAmount,
            String reportPayload,
            Instant inquiredAt,
            String inquiredBy
    ) {}

    public record CrbLoanRecord(
            String agreementReference,
            UUID accountId,
            UUID customerId,
            String nationalId,
            String customerName,
            String currency,
            String originalPrincipal,
            String currentPrincipal,
            String currentInterest,
            String totalBalance,
            String overdueBalance,
            long daysPastDue,
            String assetClassification,
            LocalDate lastPaymentDate,
            String lastPaymentAmount
    ) {}

    public record CrbReport(
            UUID bankId,
            LocalDate asOf,
            List<CrbLoanRecord> records,
            int totalLoans,
            String totalOutstandingBalance,
            String totalOverdueBalance
    ) {}
}
