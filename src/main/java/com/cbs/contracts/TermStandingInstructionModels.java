package com.cbs.contracts;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class TermStandingInstructionModels {
    private TermStandingInstructionModels() {}

    public enum InstructionType {
        AUTOMATIC_RENEWAL_CAPITALIZE,
        AUTOMATIC_RENEWAL_PAYOUT,
        TRANSFER_TO_ACCOUNT_AT_MATURITY
    }

    public record InstructionRequest(
            @NotNull UUID contractId,
            @NotNull InstructionType type,
            UUID targetAccountId,
            Integer renewalPeriodMonths,
            @Pattern(regexp = "[0-9]{1,3}(\\.[0-9]{1,8})?") String renewalRatePercent,
            @NotBlank @Size(max = 500) String reason
    ) {}

    public record StandingInstruction(
            UUID id,
            UUID tenantId,
            UUID contractId,
            InstructionType type,
            UUID targetAccountId,
            Integer renewalPeriodMonths,
            String renewalRatePercent,
            boolean active,
            String reason,
            String createdBy,
            Instant createdAt
    ) {}

    public record ExecutionRecord(
            UUID contractId,
            InstructionType type,
            String agreementReference,
            String status,
            String details
    ) {}
}
