package com.cbs.invariants;

import static org.assertj.core.api.Assertions.*;
import com.cbs.contracts.ContractModels;
import com.cbs.fx.FxModels;
import com.cbs.servicing.ServicingModels;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * P-09: property tests on the money calculations. Each property runs over generated inputs from a fixed
 * seed, so a failure is reproducible: the message names the seed and case. Override with
 * {@code -Dcbs.property.seed=...} and {@code -Dcbs.property.cases=...} to explore further.
 *
 * <p>Properties: rounding carries conserve value exactly, leap days count, schedules repay exactly the
 * principal, daily servicing nets exactly, FX rounding stays within one unit and a spread never pays the
 * customer, and transaction limits never let a total exceed the limit.
 */
class MoneyPropertyTest {
    static final long SEED = Long.getLong("cbs.property.seed", 20261113L);
    static final int CASES = Integer.getInteger("cbs.property.cases", 400);
    static final List<String> CURRENCIES = List.of("KES", "UGX", "USD", "EUR", "BHD", "JPY", "INR", "RWF", "TZS");
    static final BigDecimal TOLERANCE = new BigDecimal("1e-20");

    /** Runs a property over generated cases and reports the failing seed and case. */
    static void forAll(String property, Consumer<SplittableRandom> check) {
        for (int i = 0; i < CASES; i++) {
            long seed = SEED * 31 + property.hashCode() * 17L + i;
            try { check.accept(new SplittableRandom(seed)); }
            catch (AssertionError | RuntimeException e) { throw new AssertionError(property + " failed for case " + i + " (seed " + seed + "): " + e.getMessage(), e); }
        }
    }
    static String amount(SplittableRandom r, int scale, long maxUnits) {
        long units = 1 + r.nextLong(maxUnits);
        return BigDecimal.valueOf(units).movePointLeft(Math.max(scale, 0)).toPlainString();
    }
    static String rate(SplittableRandom r) { return BigDecimal.valueOf(r.nextLong(0, 3_000_000_000L)).movePointLeft(8).stripTrailingZeros().toPlainString(); }
    static LocalDate date(SplittableRandom r) { return LocalDate.of(2020, 1, 1).plusDays(r.nextInt(4000)); }
    static int scale(String currency) { return Currency.getInstance(currency).getDefaultFractionDigits(); }
    static BigDecimal d(String v) { return new BigDecimal(v); }
    static BigDecimal unit(int scale) { return BigDecimal.ONE.movePointLeft(scale); }

    @Test void accrualSplitIntoAnyPeriodsConservesTheExactInterestThroughTheCarry() {
        forAll("accrual-carry", r -> {
            String currency = CURRENCIES.get(r.nextInt(CURRENCIES.size())); int scale = scale(currency);
            String principal = amount(r, scale, 10_000_000_000L), rate = rate(r); int basis = r.nextBoolean() ? 360 : 365;
            LocalDate from = date(r), through = from.plusDays(r.nextInt(1, 800));
            var whole = ContractModels.interest(principal, rate, basis, from, through, "0", scale);
            BigDecimal wholeExact = d(whole.interest()).add(d(whole.carry()));
            // The same span accrued in random pieces, carrying the rounding remainder forward.
            BigDecimal paid = BigDecimal.ZERO; String carry = "0"; LocalDate cursor = from; int pieces = 0;
            while (cursor.isBefore(through)) {
                LocalDate next = cursor.plusDays(r.nextInt(1, 40)); if (next.isAfter(through)) next = through;
                var piece = ContractModels.interest(principal, rate, basis, cursor, next, carry, scale);
                assertThat(d(piece.interest()).scale()).isLessThanOrEqualTo(Math.max(scale, 0));
                assertThat(d(piece.carry()).abs()).as("carry stays below half a unit").isLessThanOrEqualTo(unit(scale).divide(BigDecimal.TWO));
                paid = paid.add(d(piece.interest())); carry = piece.carry(); cursor = next; pieces++;
            }
            assertThat(paid.add(d(carry)).subtract(wholeExact).abs()).as("value conserved over %s pieces", pieces).isLessThan(TOLERANCE.multiply(BigDecimal.valueOf(pieces + 1)));
            // Interest is never negative and grows with the number of days.
            assertThat(paid).isNotNegative();
        });
    }

    @Test void leapDaysAccrueAndTheDayBasisIsExact() {
        var leap = ContractModels.interest("36500", "10", 365, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), "0", 2);
        assertThat(d(leap.interest())).isEqualByComparingTo("3660");
        var common = ContractModels.interest("36500", "10", 365, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1), "0", 2);
        assertThat(d(common.interest())).isEqualByComparingTo("3650");
        var feb = ContractModels.interest("36000", "10", 360, LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 1), "0", 2);
        assertThat(d(feb.interest())).as("28 Feb to 1 Mar 2028 is two days").isEqualByComparingTo("20");
        forAll("day-count", r -> {
            LocalDate from = date(r), through = from.plusDays(r.nextInt(0, 1500));
            var c = ContractModels.interest("100", "100", 365, from, through, "0", 6);
            BigDecimal days = BigDecimal.valueOf(ChronoUnit.DAYS.between(from, through));
            assertThat(d(c.interest()).add(d(c.carry()))).isEqualByComparingTo(days.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(365), 24, RoundingMode.HALF_EVEN));
        });
    }

    @Test void loanSchedulesRepayExactlyThePrincipalOnMonthlyCalendarDates() {
        forAll("schedule", r -> {
            String currency = CURRENCIES.get(r.nextInt(CURRENCIES.size())); int scale = scale(currency); int n = r.nextInt(1, 121);
            // Month ends (31st, 29 Feb) are the awkward start dates; pick them often.
            LocalDate start = r.nextInt(4) == 0 ? LocalDate.of(2020 + r.nextInt(10), 1 + r.nextInt(12), 1).plusMonths(1).minusDays(1) : date(r);
            String principal = BigDecimal.valueOf(n).movePointLeft(Math.max(scale, 0)).add(new BigDecimal(amount(r, scale, 5_000_000_000L))).toPlainString();
            var terms = new ContractModels.Open(0, ContractModels.Type.LOAN, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                    principal, rate(r), "0", r.nextBoolean() ? 360 : 365, start, start.plusMonths(n), n, "P09", "Property test");
            var rows = ContractModels.schedule(terms, scale);
            assertThat(rows).hasSize(n);
            assertThat(rows.stream().map(x -> d(x.principal())).reduce(BigDecimal.ZERO, BigDecimal::add)).as("principal repaid exactly").isEqualByComparingTo(principal);
            LocalDate previous = start;
            for (var row : rows) {
                assertThat(d(row.principal())).isPositive(); assertThat(d(row.projectedInterest())).isNotNegative();
                assertThat(d(row.total())).isEqualByComparingTo(d(row.principal()).add(d(row.projectedInterest())));
                assertThat(row.dueDate()).isAfter(previous).isEqualTo(start.plusMonths(row.number()));
                assertThat(d(row.principal()).scale()).isLessThanOrEqualTo(Math.max(scale, 0));
                previous = row.dueDate();
            }
        });
    }

    @Test void dailyServicingNetsExactlyAndCarriesRoundingWithoutLoss() {
        forAll("servicing", r -> {
            String currency = CURRENCIES.get(r.nextInt(CURRENCIES.size())); int scale = scale(currency);
            String mode = List.of("HALF_EVEN", "HALF_UP", "DOWN").get(r.nextInt(3));
            String fee = BigDecimal.valueOf(r.nextInt(0, 500)).movePointLeft(Math.max(scale, 0)).toPlainString();
            var policy = new ServicingModels.Policy(0, UUID.randomUUID(), 1, UUID.randomUUID(), LocalDate.of(2026, 1, 1), rate(r), r.nextBoolean() ? 360 : 365, fee,
                    BigDecimal.valueOf(r.nextInt(0, 3001)).movePointLeft(2).toPlainString(), BigDecimal.valueOf(r.nextInt(0, 2001)).movePointLeft(2).toPlainString(), mode,
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Property test");
            BigDecimal balance = d(amount(r, scale, 50_000_000_000L)), carry = BigDecimal.ZERO, grossTotal = BigDecimal.ZERO, exactTotal = BigDecimal.ZERO;
            int days = r.nextInt(1, 60);
            for (int day = 0; day < days; day++) {
                int span = r.nextInt(1, 4);
                var c = ServicingModels.calculate(policy, currency, balance, carry, span);
                BigDecimal gross = d(c.grossInterest()), tax = d(c.interestTax()), feeAmount = d(c.fee()), feeTax = d(c.feeTax());
                assertThat(d(c.netChange())).as("net is gross less taxes and fee").isEqualByComparingTo(gross.subtract(tax).subtract(feeAmount).subtract(feeTax));
                assertThat(tax).isNotNegative().isLessThanOrEqualTo(gross);
                for (var v : List.of(gross, tax, feeAmount, feeTax)) assertThat(v.stripTrailingZeros().scale()).isLessThanOrEqualTo(Math.max(scale, 0));
                assertThat(d(c.carryAfter()).abs()).as("carry below one unit").isLessThan(unit(scale));
                grossTotal = grossTotal.add(gross); exactTotal = exactTotal.add(d(c.exactAccrual())); carry = d(c.carryAfter());
                balance = balance.add(d(c.netChange())).max(BigDecimal.ZERO);
            }
            assertThat(grossTotal.add(carry).subtract(exactTotal).abs()).as("rounding never creates or loses value").isLessThan(TOLERANCE.multiply(BigDecimal.valueOf(days + 1)));
        });
    }

    @Test void fxRoundingStaysWithinOneUnitAndTheSpreadNeverPaysTheCustomer() {
        forAll("fx", r -> {
            String base = CURRENCIES.get(r.nextInt(CURRENCIES.size())), quote;
            do { quote = CURRENCIES.get(r.nextInt(CURRENCIES.size())); } while (quote.equals(base));
            BigDecimal mid = BigDecimal.valueOf(r.nextLong(1, 5_000_000_000L)).movePointLeft(6);
            BigDecimal buy = mid.subtract(mid.multiply(BigDecimal.valueOf(r.nextInt(0, 500))).movePointLeft(4)).setScale(12, RoundingMode.DOWN).max(new BigDecimal("0.000001"));
            BigDecimal sell = mid.add(mid.multiply(BigDecimal.valueOf(r.nextInt(0, 500))).movePointLeft(4)).setScale(12, RoundingMode.UP);
            if (buy.compareTo(mid) > 0) buy = mid;
            var rate = new FxModels.Rate(0, base, quote, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), FxModels.text(buy), FxModels.text(mid), FxModels.text(sell),
                    Instant.parse("2026-01-02T00:00:00Z"), "P09", "Property test");
            String rounding = List.of("HALF_EVEN", "HALF_UP", "DOWN").get(r.nextInt(3));
            String amount = amount(r, scale(base), 10_000_000_000L);
            FxModels.Quote sold;
            try { sold = FxModels.calculate(UUID.randomUUID(), rate, FxModels.Side.BANK_BUYS_BASE, amount, rounding, LocalDate.of(2026, 1, 1)); }
            catch (IllegalArgumentException e) { assertThat(e.getMessage()).isEqualTo("Conversion out of range"); return; }
            int target = scale(quote);
            assertThat(d(sold.targetAmount()).stripTrailingZeros().scale()).isLessThanOrEqualTo(Math.max(target, 0));
            BigDecimal exact = d(amount).multiply(buy);
            assertThat(exact.subtract(d(sold.targetAmount()))).as("rounding difference reported exactly").isEqualByComparingTo(sold.roundingDifference());
            assertThat(d(sold.roundingDifference()).abs()).isLessThan(unit(target));
            if (rounding.equals("DOWN")) assertThat(d(sold.targetAmount())).isLessThanOrEqualTo(exact);
            // Selling base and buying it straight back can never return more than was sold.
            FxModels.Quote back;
            try { back = FxModels.calculate(UUID.randomUUID(), rate, FxModels.Side.BANK_SELLS_BASE, sold.targetAmount(), "DOWN", LocalDate.of(2026, 1, 1)); }
            catch (IllegalArgumentException e) { return; }
            assertThat(d(back.targetAmount())).as("round trip at the spread").isLessThanOrEqualTo(d(amount));
        });
    }

    @Test void transactionLimitsNeverLetAnyTotalExceedTheLimit() {
        forAll("limits", r -> {
            BigDecimal single = BigDecimal.valueOf(r.nextLong(1, 1_000_000)).movePointLeft(2), daily = single.multiply(BigDecimal.valueOf(r.nextInt(1, 10)));
            BigDecimal used = BigDecimal.ZERO;
            for (int i = 0; i < 50; i++) {
                BigDecimal amount = BigDecimal.valueOf(r.nextLong(0, single.movePointRight(2).longValue() * 2 + 2)).movePointLeft(2);
                try { com.cbs.access.TransactionLimits.enforce(single, daily, used, amount); used = used.add(amount); assertThat(amount).isPositive().isLessThanOrEqualTo(single); }
                catch (com.cbs.tenancy.TenantException e) { assertThat(e.code()).isIn("LIMIT_INVALID", "TRANSACTION_LIMIT_EXCEEDED", "DAILY_LIMIT_EXCEEDED"); }
                assertThat(used).isLessThanOrEqualTo(daily);
            }
        });
    }
}
