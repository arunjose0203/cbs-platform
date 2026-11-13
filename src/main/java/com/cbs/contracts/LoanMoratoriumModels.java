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

    public record MoratoriumGrant(
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
