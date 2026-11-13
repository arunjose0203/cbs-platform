package com.cbs.fx;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FxCompletionTest {

    @Test
    void deriveCrossRateCalculatesCompoundedBidAskSpread() {
        LocalDate date = LocalDate.of(2026, 11, 14);
        Instant expires = Instant.now().plusSeconds(3600);

        // EUR/USD: 1 EUR = 1.0800 (Buy) / 1.0850 (Mid) / 1.0900 (Sell) USD
        var eurUsd = new FxModels.Rate(
                1L, "EUR", "USD", date, date,
                "1.0800", "1.0850", "1.0900",
                expires, "REUTERS/EURUSD", "Market rate"
        );

        // GBP/USD: 1 GBP = 1.2900 (Buy) / 1.2950 (Mid) / 1.3000 (Sell) USD
        var gbpUsd = new FxModels.Rate(
                1L, "GBP", "USD", date, date,
                "1.2900", "1.2950", "1.3000",
                expires, "REUTERS/GBPUSD", "Market rate"
        );

        FxModels.CrossRate cross = FxModels.deriveCrossRate(eurUsd, gbpUsd, date);

        assertEquals("EUR", cross.baseCurrency());
        assertEquals("GBP", cross.quoteCurrency());
        assertEquals("USD", cross.pivotCurrency());
        assertEquals(date, cross.calculatedDate());

        BigDecimal buy = new BigDecimal(cross.buyRate());
        BigDecimal mid = new BigDecimal(cross.midRate());
        BigDecimal sell = new BigDecimal(cross.sellRate());

        assertTrue(buy.signum() > 0);
        assertTrue(buy.compareTo(mid) <= 0, "Buy rate must be <= Mid rate");
        assertTrue(mid.compareTo(sell) <= 0, "Mid rate must be <= Sell rate");

        // Buy = 1.0800 / 1.3000 = 0.83076923
        assertEquals(0, new BigDecimal("0.83076923").compareTo(buy));
        // Mid = 1.0850 / 1.2950 = 0.83783784
        assertEquals(0, new BigDecimal("0.83783784").compareTo(mid));
        // Sell = 1.0900 / 1.2900 = 0.84496124
        assertEquals(0, new BigDecimal("0.84496124").compareTo(sell));
    }

    @Test
    void rateFeedValidationAndItems() {
        LocalDate date = LocalDate.of(2026, 11, 14);
        var item1 = new FxRateFeedModels.FeedItem(
                "USD", "KES", "129.50", "130.00", "130.50",
                date, date, Instant.now().plusSeconds(86400), "CBK/DAILY"
        );
        var item2 = new FxRateFeedModels.FeedItem(
                "EUR", "KES", "139.50", "140.00", "140.50",
                date, date, Instant.now().plusSeconds(86400), "CBK/DAILY"
        );

        var feedImport = new FxRateFeedModels.FeedImport(
                1L, "CENTRAL_BANK_OF_KENYA", date,
                List.of(item1, item2), "Daily official fixing rates"
        );

        assertEquals(2, feedImport.items().size());
        assertEquals("CENTRAL_BANK_OF_KENYA", feedImport.feedSource());
        assertEquals(date, feedImport.effectiveDate());
    }

    @Test
    void monetaryTranslationExposureAggregation() {
        LocalDate date = LocalDate.of(2026, 11, 14);
        UUID bankId = UUID.randomUUID();

        var item1 = new FxMonetaryTranslationModels.MonetaryItem(
                UUID.randomUUID(), "1100", "Nostro USD Account", "ASSET",
                "USD", "10000.00", "130.00", "1300000.00", "KES"
        );
        var item2 = new FxMonetaryTranslationModels.MonetaryItem(
                UUID.randomUUID(), "2100", "Foreign Borrowing EUR", "LIABILITY",
                "EUR", "5000.00", "140.00", "700000.00", "KES"
        );

        BigDecimal assets = new BigDecimal(item1.translatedAmount());
        BigDecimal liabilities = new BigDecimal(item2.translatedAmount());
        BigDecimal netExposure = assets.subtract(liabilities);

        var report = new FxMonetaryTranslationModels.MonetaryTranslationReport(
                bankId, date, "KES", 2,
                assets.toPlainString(), liabilities.toPlainString(),
                netExposure.toPlainString(), List.of(item1, item2), Instant.now()
        );

        assertEquals(2, report.totalItems());
        assertEquals("1300000.00", report.totalForeignAssetsFunctional());
        assertEquals("700000.00", report.totalForeignLiabilitiesFunctional());
        assertEquals("600000.00", report.netForeignCurrencyExposureFunctional());
    }
}
