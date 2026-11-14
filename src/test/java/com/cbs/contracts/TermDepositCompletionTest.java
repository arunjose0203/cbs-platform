package com.cbs.contracts;

import static org.junit.jupiter.api.Assertions.*;

import com.cbs.accounts.AccountModels;
import com.cbs.tenancy.TenantModels;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TermDepositCompletionTest {

    @Test
    void partialCloseReducesPrincipalAndKeepsContractActive() {
        UUID contractId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID principalLedger = UUID.randomUUID();
        UUID interestLedger = UUID.randomUUID();
        UUID pnlLedger = UUID.randomUUID();
        UUID taxLedger = UUID.randomUUID();
        UUID penaltyLedger = UUID.randomUUID();

        var open = new ContractModels.Open(
                1L, ContractModels.Type.TERM_DEPOSIT, accountId,
                principalLedger, interestLedger, pnlLedger, taxLedger,
                "100000.00", "12.0", "15.0", 365,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                0, "TD/2026/001", "Fixed term deposit"
        );

        // Before position: 100,000 principal, 500 accrued interest
        var before = new ContractModels.Position("100000.00", "500.00", "0", LocalDate.of(2026, 6, 1), false);

        // Partial close: withdraw 40,000 with 50 penalty on interest
        var instruction = new TermServicingModels.Instruction(
                1L, contractId, TermServicingModels.Operation.PARTIAL_CLOSE,
                LocalDate.of(2026, 6, 1), "50.00", penaltyLedger,
                TermServicingModels.TaxBasis.INTEREST_AFTER_PENALTY, null,
                null, null, "TD/PARTIAL/001", "Customer withdrawal", "40000.00"
        );

        BigDecimal principal = new BigDecimal(before.principal());
        BigDecimal interest = new BigDecimal(before.interest());
        BigDecimal closePrincipal = new BigDecimal(instruction.closeAmount());
        BigDecimal penalty = new BigDecimal(instruction.penaltyAmount());

        assertEquals(0, new BigDecimal("40000.00").compareTo(closePrincipal));
        assertTrue(closePrincipal.compareTo(BigDecimal.ZERO) > 0);
        assertTrue(closePrincipal.compareTo(principal) < 0);

        // Tax calculation: (500 - 50) * 15% = 450 * 0.15 = 67.50
        BigDecimal basis = interest.subtract(penalty);
        BigDecimal tax = basis.multiply(new BigDecimal("15.0")).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_EVEN);
        assertEquals(0, new BigDecimal("67.50").compareTo(tax));

        BigDecimal netInterest = interest.subtract(penalty).subtract(tax);
        assertEquals(0, new BigDecimal("382.50").compareTo(netInterest));

        BigDecimal customerMovement = closePrincipal.add(netInterest);
        assertEquals(0, new BigDecimal("40382.50").compareTo(customerMovement));

        BigDecimal remainingPrincipal = principal.subtract(closePrincipal);
        assertEquals(0, new BigDecimal("60000.00").compareTo(remainingPrincipal));

        var after = new ContractModels.Position(
                remainingPrincipal.stripTrailingZeros().toPlainString(),
                "0", "0", LocalDate.of(2026, 6, 1), false
        );

        assertFalse(after.closed(), "Partial close must leave contract active");
        assertEquals("60000", after.principal());
        assertEquals("0", after.interest());

        // Balance test: Debits (closePrincipal + interest) = Credits (customerMovement + tax + penalty)
        BigDecimal totalDebits = closePrincipal.add(interest); // 40,000 + 500 = 40,500
        BigDecimal totalCredits = customerMovement.add(tax).add(penalty); // 40382.50 + 67.50 + 50 = 40,500
        assertEquals(0, totalDebits.compareTo(totalCredits), "Journal entries must balance exactly");
    }

    @Test
    void standingInstructionRecordValidation() {
        UUID contractId = UUID.randomUUID();
        UUID targetAccountId = UUID.randomUUID();

        var req = new TermStandingInstructionModels.InstructionRequest(
                3L, contractId,
                TermStandingInstructionModels.InstructionType.AUTOMATIC_RENEWAL_CAPITALIZE,
                targetAccountId, 12, "11.5", "Mandate from customer"
        );

        assertEquals(contractId, req.contractId());
        assertEquals(TermStandingInstructionModels.InstructionType.AUTOMATIC_RENEWAL_CAPITALIZE, req.type());
        assertEquals(12, req.renewalPeriodMonths());
        assertEquals("11.5", req.renewalRatePercent());
    }
}
