package com.cbs.contracts;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LoanEclModels {
    private LoanEclModels() {}

    public record EclAssessment(
            UUID contractId,
            String agreementReference,
            UUID accountId,
            int stage,
            long daysPastDue,
            String grossExposure,
            String eclRatePercent,
            String requiredAllowance,
            String currentAllowance,
            String netAdjustment,
            boolean requiresAdjustment
    ) {}

    public record EclReport(
            UUID bankId,
            LocalDate asOf,
            List<EclAssessment> assessments,
            int totalLoans,
            String totalGrossExposure,
            String totalRequiredAllowance,
            String totalCurrentAllowance,
            String netAdjustment
    ) {}
}
