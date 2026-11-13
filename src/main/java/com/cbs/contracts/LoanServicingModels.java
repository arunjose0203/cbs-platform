package com.cbs.contracts;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public final class LoanServicingModels {
    private LoanServicingModels() {}

    public enum Operation {
        RESTRUCTURE, IMPAIR, WRITE_OFF, PARTIAL_WRITE_OFF, RECOVERY
    }

    public record Instruction(
            @PositiveOrZero long expectedVersion,
            @NotNull UUID contractId,
            @NotNull Operation operation,
            @NotNull LocalDate businessDate,
            @NotNull @Pattern(regexp = "[0-9]{1,18}(\\.[0-9]{1,6})?") String allowance,
            @Min(1) @Max(3) int stage,
            UUID allowanceLedger,
            UUID impairmentExpenseLedger,
            @Pattern(regexp = "[0-9]{1,3}(\\.[0-9]{1,8})?") String newAnnualRatePercent,
            @Min(0) @Max(120) int newInstalments,
            @NotBlank @Size(max = 160) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]*") String evidenceReference,
            @NotBlank @Size(max = 500) String reason,
            @Pattern(regexp = "[0-9]{1,18}(\\.[0-9]{1,6})?") String writeOffAmount,
            UUID recoveryIncomeLedger
    ) {
        public Instruction(long expectedVersion, UUID contractId, Operation operation, LocalDate businessDate,
                           String allowance, int stage, UUID allowanceLedger, UUID impairmentExpenseLedger,
                           String newAnnualRatePercent, int newInstalments,
                           String evidenceReference, String reason) {
            this(expectedVersion, contractId, operation, businessDate, allowance, stage,
                    allowanceLedger, impairmentExpenseLedger, newAnnualRatePercent, newInstalments,
                    evidenceReference, reason, null, null);
        }
    }

    public record Details(Instruction instruction, String allowanceBefore, String allowanceAfter, ContractModels.Open renewedTerms) {}

    public record Collection(
            @PositiveOrZero long expectedVersion,
            @NotNull UUID contractId,
            @NotBlank @Size(max = 255) String owner,
            @NotNull LocalDate followUpOn,
            @NotBlank @Size(max = 160) String evidenceReference,
            @NotBlank @Size(max = 500) String note
    ) {}
}
