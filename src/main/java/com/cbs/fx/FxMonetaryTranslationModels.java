package com.cbs.fx;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class FxMonetaryTranslationModels {
    private FxMonetaryTranslationModels() {}

    public record MonetaryItem(
            UUID ledgerId,
            String chartCode,
            String chartName,
            String category,
            String currency,
            String foreignBalance,
            String closingRate,
            String translatedAmount,
            String functionalCurrency
    ) {}

    public record MonetaryTranslationReport(
            UUID bankId,
            LocalDate businessDate,
            String functionalCurrency,
            int totalItems,
            String totalForeignAssetsFunctional,
            String totalForeignLiabilitiesFunctional,
            String netForeignCurrencyExposureFunctional,
            List<MonetaryItem> items,
            Instant generatedAt
    ) {}
}
