package com.cbs.compliance;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public final class ComplianceModels {
    private ComplianceModels() { }
    public enum Category { KYC_REVIEW, SCREENING_ALERT, TRANSACTION_REVIEW }
    public enum Decision { CLEARED, RESTRICTED }
    public enum Risk { LOW, MEDIUM, HIGH }
    public record Open(@PositiveOrZero long expectedVersion, @NotNull UUID customerId,
        @NotNull Category category,
        @NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String evidenceReference,
        @NotBlank @Size(max=500) String reason) { }
    public record Note(@PositiveOrZero long expectedVersion, @PositiveOrZero long caseVersion,
        @NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String evidenceReference,
        @NotBlank @Size(max=500) String reason) { }
    /** Assigns an open case to an investigator (a staff subject) with a due date for the investigation. */
    public record Assign(@PositiveOrZero long expectedVersion, @PositiveOrZero long caseVersion,
        @NotBlank @Size(max=255) @Pattern(regexp="[^\\p{Cntrl}]+") String assignee,
        @NotNull LocalDate dueDate,
        @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record Review(@PositiveOrZero long expectedVersion, @NotNull UUID caseId,
        @PositiveOrZero long caseVersion, @PositiveOrZero long customerVersion,
        @NotNull Decision decision, @NotNull Risk risk,
        @NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String identityEvidence,
        @NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String screeningEvidence,
        LocalDate reviewDue, @NotBlank @Size(max=500) String reason) { }
}
