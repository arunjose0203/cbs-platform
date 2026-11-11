package com.cbs.compliance;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

/** Requests for screening lists, transaction monitoring and alert decisions. */
public final class AutomationModels {
    private AutomationModels() { }

    public enum ListCategory { SANCTIONS, PEP, INTERNAL }
    public enum ItemType { SCREENING_HIT, MONITORING_ALERT }

    private static final String EVIDENCE = "[A-Za-z0-9][A-Za-z0-9._/-]*";
    private static final String TEXT = "[^\\p{Cntrl}]+";

    /** A list version as CSV: entry_ref,entity_type,name,aliases,birth_date,country,program (aliases ';'-separated). */
    public record Upload(@NotBlank @Pattern(regexp="[A-Z0-9][A-Z0-9_-]{0,39}") String name,
            @NotNull ListCategory category,
            @NotBlank @Size(max=200) @Pattern(regexp=TEXT) String source,
            @NotNull @DecimalMin("0.70") @DecimalMax("1.00") BigDecimal threshold,
            @NotBlank @Size(max=6_000_000) String csv) { }

    public record Activation(@PositiveOrZero long expectedVersion, @NotNull UUID listId,
            @NotBlank @Pattern(regexp="[0-9a-f]{64}") String sha256,
            @NotBlank @Size(max=500) @Pattern(regexp=TEXT) String reason) { }

    private static final String AMOUNT = "[0-9]{1,18}(\\.[0-9]{1,6})?";

    /** Monitoring rules for one currency; a rule is off when its fields are omitted. */
    public record Policy(@PositiveOrZero long expectedVersion, @NotBlank @Pattern(regexp="[A-Z]{3}") String currency,
            @Pattern(regexp=AMOUNT) String largeCash,
            @Pattern(regexp=AMOUNT) String structuringTotal,
            @Min(1) @Max(31) Integer structuringDays,
            @Min(2) @Max(100) Integer structuringCount,
            @Min(2) @Max(1000) Integer velocityDebits,
            @DecimalMin("2") @DecimalMax("100") BigDecimal unusualMultiplier,
            @Min(7) @Max(366) Integer unusualLookbackDays,
            @Min(3) @Max(1000) Integer unusualMinHistory,
            @NotBlank @Size(max=500) @Pattern(regexp=TEXT) String reason) { }

    public record Escalate(@PositiveOrZero long expectedVersion, @PositiveOrZero long itemVersion,
            @NotBlank @Size(max=160) @Pattern(regexp=EVIDENCE) String evidenceReference,
            @NotBlank @Size(max=500) @Pattern(regexp=TEXT) String reason) { }

    /** A false-positive decision; applied only after independent approval. */
    public record Dismiss(@PositiveOrZero long expectedVersion, @NotNull ItemType type, @NotNull UUID itemId,
            @PositiveOrZero long itemVersion,
            @NotBlank @Size(max=160) @Pattern(regexp=EVIDENCE) String evidenceReference,
            @NotBlank @Size(max=500) @Pattern(regexp=TEXT) String reason) { }
}
