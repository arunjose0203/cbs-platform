package com.cbs.fx;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class FxRateFeedModels {
    private FxRateFeedModels() {}

    public record FeedItem(
            @NotNull @Pattern(regexp = "[A-Z]{3}") String baseCurrency,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String quoteCurrency,
            @NotNull @Pattern(regexp = "[0-9]{1,12}(\\.[0-9]{1,12})?") String buyRate,
            @NotNull @Pattern(regexp = "[0-9]{1,12}(\\.[0-9]{1,12})?") String midRate,
            @NotNull @Pattern(regexp = "[0-9]{1,12}(\\.[0-9]{1,12})?") String sellRate,
            @NotNull LocalDate effectiveFrom,
            @NotNull LocalDate effectiveTo,
            @NotNull Instant expiresAt,
            @NotBlank @Size(max = 160) String sourceReference
    ) {}

    public record FeedImport(
            @PositiveOrZero long expectedVersion,
            @NotBlank @Size(max = 100) String feedSource,
            @NotNull LocalDate effectiveDate,
            @NotEmpty List<FeedItem> items,
            @NotBlank @Size(max = 500) String reason
    ) {}

    public record FeedRecord(
            UUID id,
            UUID tenantId,
            String feedSource,
            LocalDate effectiveDate,
            int totalRates,
            List<FeedItem> items,
            String status,
            String createdBy,
            Instant createdAt
    ) {}
}
