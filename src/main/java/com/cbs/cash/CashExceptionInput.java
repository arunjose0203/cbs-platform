package com.cbs.cash;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.cbs.cash.CashModels.Count;

/** Evidence only: reporting or resolving a count discrepancy never posts accounting. */
public record CashExceptionInput(
 @PositiveOrZero long expectedVersion, @NotNull UUID locationId,
 @NotNull LocalDate businessDate, @NotNull @Size(max=50) List<@NotNull @Valid Count> counts,
 @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
