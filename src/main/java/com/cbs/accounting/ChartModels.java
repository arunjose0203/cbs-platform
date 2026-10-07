package com.cbs.accounting;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChartModels {
    private ChartModels() { }
    public enum Category { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE }
    public enum Balance { DEBIT, CREDIT }
    public enum Kind { GROUP, POSTING }
    public record Change(@Min(0) long expectedVersion, UUID accountId,
            @NotBlank @Pattern(regexp="[A-Z0-9][A-Z0-9_-]{0,31}") String code,
            @NotBlank @Size(max=160) @Pattern(regexp="[^\\p{Cntrl}]+") String name,
            @NotNull @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]*") String description,
            @NotNull Category category, @NotNull Balance normalBalance, @NotNull Kind kind,
            UUID parentId, @NotNull Boolean active,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record Account(UUID id, String code, String name, String description, Category category,
            Balance normalBalance, Kind kind, UUID parentId, String parentCode, boolean active,
            Instant createdAt, Instant updatedAt) { }
    public record Chart(UUID bankId, long version, List<Account> items, long total,
            long accountCount, long groupCount, long activeCount, int limit, int offset) { }
}
