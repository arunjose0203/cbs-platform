package com.cbs.reference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ReferenceModels {
    private ReferenceModels() { }
    public record CurrencyRule(@NotBlank @Pattern(regexp="[A-Z]{3}") String code,
            @NotBlank String roundingMode, @NotNull Boolean enabled) { }
    public record Holiday(@NotNull LocalDate date, @NotBlank @Size(max=120) @Pattern(regexp="[^\\p{Cntrl}]+") String name) { }
    public record Change(@NotNull @PositiveOrZero Long expectedVersion,
            @NotEmpty @Size(max=200) List<@NotNull @Valid CurrencyRule> currencies,
            @NotEmpty @Size(max=30) List<@NotBlank @Size(max=35) String> languages,
            @NotBlank @Size(max=35) String defaultLanguage,
            @NotBlank @Size(max=64) String businessTimezone,
            @NotNull LocalDate businessDate,
            @NotNull @Size(max=6) List<@NotNull @Min(1) @Max(7) Integer> weekendDays,
            @NotNull @Size(max=1000) List<@NotNull @Valid Holiday> holidays,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record Settings(UUID bankId, long version, List<CurrencyRule> currencies, List<String> languages,
            String defaultLanguage, String businessTimezone, LocalDate businessDate,
            List<Integer> weekendDays, List<Holiday> holidays) { }
    public record CurrencyInfo(String code, String name, int decimalPlaces) { }
    public record LanguageInfo(String code, String name) { }
    public record Catalogue(List<CurrencyInfo> currencies, List<LanguageInfo> languages,
            List<String> timezones, List<String> roundingModes) { }
    public record Preference(@NotBlank @Size(max=35) String language) { }
    public record DayInfo(LocalDate date, boolean workingDay, String holiday, LocalDate nextWorkingDay) { }
    public record AmountPreview(String currency, int decimalPlaces, String roundingMode, String roundedAmount) { }
}
