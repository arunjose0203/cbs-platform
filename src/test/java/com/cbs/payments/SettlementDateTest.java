package com.cbs.payments;

import com.cbs.reference.ReferenceModels.Settings;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Settlement and statement dates are bounded by the bank's own calendar, never by today in UTC. */
class SettlementDateTest {
    private static Settings bank(String zone, LocalDate businessDate) {
        return new Settings(UUID.randomUUID(), 1, List.of(), List.of("en"), "en", zone, businessDate, List.of(6, 7), List.of());
    }

    @Test void aBusinessDateRolledPastAWeekendIsAccepted() {
        LocalDate monday = LocalDate.now(ZoneId.of("Africa/Nairobi")).plusDays(3);
        assertThat(PaymentService.latestSettlementDate(bank("Africa/Nairobi", monday))).isEqualTo(monday);
    }

    @Test void otherwiseTodayIsTakenInTheBanksTimeZone() {
        for (String zone : List.of("Africa/Nairobi", "Pacific/Kiritimati", "Pacific/Pago_Pago")) {
            LocalDate past = LocalDate.of(2020, 1, 6);
            assertThat(PaymentService.latestSettlementDate(bank(zone, past))).isIn(
                    LocalDate.now(ZoneId.of(zone)), LocalDate.now(ZoneId.of(zone)).plusDays(1)); // tolerate midnight between calls
        }
    }
}
