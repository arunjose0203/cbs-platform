package com.cbs.products;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ProductModels {
    private ProductModels() { }
    public enum Type { SAVINGS, CURRENT }
    public record Change(@Min(0) long expectedVersion, UUID productId,
        @NotBlank @Pattern(regexp="[A-Z0-9][A-Z0-9_-]{0,31}") String code,
        @NotBlank @Size(max=160) @Pattern(regexp="[^\\p{Cntrl}]+") String name,
        @NotNull @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]*") String description,
        @NotNull Type productType, @NotNull @Pattern(regexp="[A-Z]{3}") String currency,
        @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String minimumOpening,
        @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String minimumBalance,
        @NotNull UUID liabilityAccountId, @NotNull Boolean active,
        @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record Product(UUID id,String code,String name,String description,Type productType,String currency,
        String minimumOpening,String minimumBalance,UUID liabilityAccountId,String liabilityAccountCode,
        boolean active,int revision,Instant createdAt,Instant updatedAt) { }
    public record Catalogue(UUID bankId,long version,List<Product> items,long total,int limit,int offset) { }
    public record Revision(int revision,Map<String,Object> definition,String approvedBy,String reason,Instant createdAt) { }
}
