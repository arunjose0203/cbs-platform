package com.cbs.contracts;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public final class LoanFeeModels {
    private LoanFeeModels() {}

    public enum Operation { FEE_ASSESS, FEE_PAY, FEE_WAIVE }

    public record Change(
            @PositiveOrZero long expectedVersion,
            @NotNull UUID contractId,
            @NotNull Operation operation,
            UUID feeId,
            @NotNull LocalDate businessDate,
            LocalDate dueDate,
            UUID receivableLedger,
            UUID incomeLedger,
            @NotNull @Pattern(regexp = "[0-9]{1,18}(\\.[0-9]{1,6})?") String amount,
            @NotBlank @Size(max = 160) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]*") String evidenceReference,
            @NotBlank @Size(max = 500) String reason,
            UUID taxLedger
    ) {
        public Change(long expectedVersion, UUID contractId, Operation operation, UUID feeId,
                      LocalDate businessDate, LocalDate dueDate, UUID receivableLedger, UUID incomeLedger,
                      String amount, String evidenceReference, String reason) {
            this(expectedVersion, contractId, operation, feeId, businessDate, dueDate, receivableLedger, incomeLedger, amount, evidenceReference, reason, null);
        }
    }

    public record Details(
            Change change,
            UUID feeId,
            LocalDate dueDate,
            UUID receivableLedger,
            UUID incomeLedger,
            String before,
            String after,
            String tax
    ) {
        public Details(Change change, UUID feeId, LocalDate dueDate, UUID receivableLedger, UUID incomeLedger, String before, String after) {
            this(change, feeId, dueDate, receivableLedger, incomeLedger, before, after, "0");
        }
    }
}
