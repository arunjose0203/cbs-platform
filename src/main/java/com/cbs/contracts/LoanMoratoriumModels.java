package com.cbs.contracts;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class LoanMoratoriumModels {
    private LoanMoratoriumModels() {}

    public enum MoratoriumType {
        INTEREST_ONLY, FULL_PAYMENT_HOLIDAY
    }

    /**
     * A moratorium changes what the customer owes and when (arrears ageing and due notices), so it is a maker-checker
     * request (P-10: ApprovalService action LOAN_MORATORIUM, governed by lending grants like a restructure).
     */
    public record MoratoriumGrant(
            @Min(0) long expectedVersion,
            @NotNull LocalDate businessDate,
            @NotNull UUID contractId,
            @NotNull MoratoriumType type,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotBlank @Size(max = 500) String reason
    ) {}

    public record MoratoriumRecord(
            UUID id,
            UUID contractId,
            MoratoriumType type,
            LocalDate startDate,
            LocalDate endDate,
            String reason,
            String createdBy,
            Instant createdAt
    ) {}
}
