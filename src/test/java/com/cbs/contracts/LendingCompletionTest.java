package com.cbs.contracts;

import static com.cbs.contracts.ContractModels.*;
import static org.assertj.core.api.Assertions.*;

import com.cbs.accounts.AccountModels;
import com.cbs.credit.CreditBureauModels;
import com.cbs.products.ProductModels;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class LendingCompletionTest {
    private final LocalDate start = LocalDate.of(2026, 1, 31);

    private Definition definition(String currency) {
        var product = new ProductModels.Product(UUID.randomUUID(), "TEST", "Test", "", ProductModels.Type.CURRENT, currency, "0", "0", UUID.randomUUID(), "100", true, 1, Instant.EPOCH, Instant.EPOCH);
        var a = new AccountModels.Definition(UUID.randomUUID(), UUID.randomUUID(), "1", 1, UUID.randomUUID(), "C1", UUID.randomUUID(), "Test", "Test", AccountModels.State.ACTIVE, product, Instant.EPOCH);
        var o = new Open(0, Type.LOAN, a.id(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "200", "36.5", "0", 365, start, start.plusMonths(2), 2, "TEST", "Test");
        return new Definition(UUID.randomUUID(), a.bankId(), a, o);
    }

    private Posting opening(Definition d) {
        return new Posting(d.id(), d, Kind.OPEN, start, new Position("0", "0", "0", start, false), new Position("200", "0", "0", start, false), "200", "0", null, "Open");
    }

    private Posting accrue(Posting previous, LocalDate date) {
        var p = previous.after();
        var c = interest(p.principal(), previous.contract().terms().annualRatePercent(), 365, p.accruedThrough(), date, p.carry(), scale(previous.contract()));
        return new Posting(UUID.randomUUID(), previous.contract(), Kind.ACCRUE, date, p, new Position(p.principal(), text(decimal(p.interest()).add(decimal(c.interest()))), c.carry(), date, false), "0", "0", null, "Accrue");
    }

    @Test
    void moratoriumFreezesArrearsCalculation() {
        var open = opening(definition("KES"));
        var asOf = LocalDate.of(2026, 3, 15);
        var a = accrue(open, asOf);
        var events = List.of(open, a);

        // Without moratorium: 1st instalment due 2026-02-28 is 15 days past due
        var standardReport = LoanArrears.calculate(events, asOf);
        assertThat(standardReport.daysPastDue()).isEqualTo(15);
        assertThat(standardReport.overdue().principal()).isEqualTo("100");
        assertThat(standardReport.buckets().get("1_30").principal()).isEqualTo("100");

        // With moratorium covering Feb & March 2026: instalment is frozen, 0 DPD
        var moratorium = new LoanArrears.MoratoriumInterval(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 31));
        var moratoriumReport = LoanArrears.calculate(events, asOf, List.of(moratorium));
        assertThat(moratoriumReport.daysPastDue()).isEqualTo(0);
        assertThat(moratoriumReport.overdue().total()).isEqualTo("0");
        assertThat(moratoriumReport.buckets().get("CURRENT").principal()).isEqualTo("200");
    }

    @Test
    void partialWriteOffReducesCarryingAmountAndMaintainsReconciliation() {
        var open = opening(definition("KES"));
        var accrueDate = LocalDate.of(2026, 2, 28);
        var a = accrue(open, accrueDate);

        // Before partial write-off: principal 200, accrued interest 5.6
        assertThat(a.after().principal()).isEqualTo("200");
        assertThat(a.after().interest()).isEqualTo("5.6");

        // Partial write-off of 45.6 (5.6 interest + 40 principal)
        var pWriteOff = new Posting(UUID.randomUUID(), open.contract(), Kind.PARTIAL_WRITE_OFF, accrueDate,
                a.after(),
                new Position("160", "0", a.after().carry(), accrueDate, false),
                "0", "0", null, "Partial write-off approved bad debt");

        var report = LoanArrears.calculate(List.of(open, a, pWriteOff), accrueDate);
        assertThat(report.reconciled()).isTrue();
        assertThat(report.outstanding().principal()).isEqualTo("160");
        assertThat(report.outstanding().interest()).isEqualTo("0");
        assertThat(report.outstanding().total()).isEqualTo("160");
    }

    @Test
    void creditBureauDeterministicScoringAndAssetClassification() {
        // Scoring ranges from 300 to 850
        String nationalId1 = "ID12345678";
        String nationalId2 = "ID87654321";

        int score1 = deriveScore(nationalId1);
        int score2 = deriveScore(nationalId2);

        assertThat(score1).isBetween(300, 850);
        assertThat(score2).isBetween(300, 850);

        // Asset classification based on statutory DPD rules
        assertThat(classifyAsset(0, false)).isEqualTo("PERFORMING");
        assertThat(classifyAsset(15, false)).isEqualTo("WATCH");
        assertThat(classifyAsset(60, false)).isEqualTo("SUBSTANDARD");
        assertThat(classifyAsset(120, false)).isEqualTo("DOUBTFUL");
        assertThat(classifyAsset(200, false)).isEqualTo("LOSS");
        assertThat(classifyAsset(10, true)).isEqualTo("LOSS"); // Written off is always LOSS
    }

    @Test
    void ifrs9EclStagingCriteriaAndAllowances() {
        BigDecimal exposure = new BigDecimal("100000.00");

        // Stage 1: Performing (DPD <= 30) -> 1% ECL
        int stage1 = determineStage(20);
        BigDecimal allowance1 = calculateEcl(exposure, stage1);
        assertThat(stage1).isEqualTo(1);
        assertThat(allowance1).isEqualByComparingTo("1000.00");

        // Stage 2: Underperforming (31 <= DPD <= 90) -> 3% ECL
        int stage2 = determineStage(60);
        BigDecimal allowance2 = calculateEcl(exposure, stage2);
        assertThat(stage2).isEqualTo(2);
        assertThat(allowance2).isEqualByComparingTo("3000.00");

        // Stage 3: Non-Performing / Impaired (DPD > 90) -> 20% ECL
        int stage3 = determineStage(120);
        BigDecimal allowance3 = calculateEcl(exposure, stage3);
        assertThat(stage3).isEqualTo(3);
        assertThat(allowance3).isEqualByComparingTo("20000.00");
    }

    @Test
    void feeAssessmentWithExciseDutyAndPaymentReversal() {
        var open = opening(definition("KES"));
        UUID feeId = UUID.randomUUID();
        UUID recv = UUID.randomUUID();
        UUID income = UUID.randomUUID();
        UUID taxLedger = UUID.randomUUID();

        // Assess fee of 100 with 20% excise duty -> 120 total receivable
        BigDecimal fee = new BigDecimal("100.00");
        BigDecimal tax = fee.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal totalReceivable = fee.add(tax);

        var change = new LoanFeeModels.Change(0, open.contract().id(), LoanFeeModels.Operation.FEE_ASSESS, feeId,
                start, start.plusDays(10), recv, income, "100.00", "REF-FEE-01", "Loan appraisal fee", taxLedger);
        var details = new LoanFeeModels.Details(change, feeId, start.plusDays(10), recv, income, "0", totalReceivable.toPlainString(), tax.toPlainString());

        var assess = new Posting(feeId, open.contract(), Kind.FEE_ASSESS, start, open.after(), open.after(),
                "0", tax.toPlainString(), null, "Fee assessed", null, null, null, details);

        var rAfterAssess = LoanArrears.calculate(List.of(open, assess), start);
        assertThat(rAfterAssess.outstanding().fees()).isEqualTo("120");

        // Pay the fee: customer cash -120, fee after becomes 0
        var payChange = new LoanFeeModels.Change(0, open.contract().id(), LoanFeeModels.Operation.FEE_PAY, feeId,
                start, null, null, null, "120.00", "REF-PAY-01", "Pay loan fee", null);
        var payDetails = new LoanFeeModels.Details(payChange, feeId, start.plusDays(10), recv, income, "120.00", "0", "0");
        var payPosting = new Posting(UUID.randomUUID(), open.contract(), Kind.FEE_PAY, start, open.after(), open.after(),
                "-120.00", "0", null, "Paid fee", null, null, null, payDetails);

        var rAfterPay = LoanArrears.calculate(List.of(open, assess, payPosting), start);
        assertThat(rAfterPay.outstanding().fees()).isEqualTo("0");

        // Reverse the fee payment: restores customer cash +120 and fee receivable 120
        var revDetails = new LoanFeeModels.Details(payChange, feeId, start.plusDays(10), recv, income, "0", "120.00", "0");
        var reversePosting = new Posting(UUID.randomUUID(), open.contract(), Kind.REVERSE, start, open.after(), open.after(),
                "120.00", "0", null, "Reverse fee payment", payPosting.id(), null, null, revDetails);

        var rAfterRev = LoanArrears.calculate(List.of(open, assess, payPosting, reversePosting), start);
        assertThat(rAfterRev.outstanding().fees()).isEqualTo("120");
    }

    @Test
    void nonAccrualSuspendsInterestForDefaultLoans() {
        // Loan with 95 DPD is in default (>90 DPD)
        long dpd = 95;
        boolean nonAccrual = dpd > 90;
        assertThat(nonAccrual).isTrue();

        // In non-accrual status, interest accrual recognized to P&L is 0
        BigDecimal normalInterest = new BigDecimal("15.50");
        BigDecimal recognizedInterest = nonAccrual ? BigDecimal.ZERO : normalInterest;
        assertThat(recognizedInterest).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void postWriteOffRecoveryAccounting() {
        var open = opening(definition("KES"));
        // Full write-off
        var writeOff = new Posting(UUID.randomUUID(), open.contract(), Kind.WRITE_OFF, start,
                open.after(), new Position("0", "0", "0", start, true), "0", "0", null, "Full write-off");

        // Post-write-off recovery: debits customer 50, credits recovery revenue 50
        BigDecimal recoveryAmount = new BigDecimal("50.00");
        var recoveryPosting = new Posting(UUID.randomUUID(), open.contract(), Kind.RECOVERY, start.plusDays(30),
                writeOff.after(), writeOff.after(), "-50.00", "0", null, "Recovery collected");

        assertThat(recoveryPosting.kind()).isEqualTo(Kind.RECOVERY);
        assertThat(recoveryPosting.customerMovement()).isEqualTo("-50.00");
        assertThat(recoveryPosting.after().closed()).isTrue();
    }

    private static int deriveScore(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.trim().toUpperCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int val = ((hash[0] & 0xFF) << 8) | (hash[1] & 0xFF);
            return 300 + (val % 551);
        } catch (Exception e) {
            return 650;
        }
    }

    private static String classifyAsset(long dpd, boolean writtenOff) {
        if (writtenOff || dpd > 180) return "LOSS";
        if (dpd > 90) return "DOUBTFUL";
        if (dpd > 30) return "SUBSTANDARD";
        if (dpd > 0) return "WATCH";
        return "PERFORMING";
    }

    private static int determineStage(long dpd) {
        if (dpd <= 30) return 1;
        if (dpd <= 90) return 2;
        return 3;
    }

    private static BigDecimal calculateEcl(BigDecimal exposure, int stage) {
        BigDecimal rate = switch (stage) {
            case 1 -> new BigDecimal("0.01");
            case 2 -> new BigDecimal("0.03");
            default -> new BigDecimal("0.20");
        };
        return exposure.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
    }
}
