# P-09: invariant and property tests on money paths

Added 14 November 2026. Production-engineering item P-09 (gate E13) of the [go-live plan](go-live-plan-2026-11-13.md). Tests only: no migration, no change to production code.

## Property tests (no database)

`src/test/java/com/cbs/invariants/MoneyPropertyTest.java` runs each property over generated inputs from a fixed seed (400 cases each by default). A failure names the property, case and seed. `-Dcbs.property.seed=` and `-Dcbs.property.cases=` reproduce or widen a run.

| Property | What is generated | What must hold |
| --- | --- | --- |
| Accrual carry conservation | Any of 9 currencies (0, 2 and 3 decimals), principal, rate, 360/365 basis, a span up to 800 days cut into random pieces | Interest accrued in pieces plus the final carry equals the exact interest for the whole span. Each carry stays within half a unit. Interest is never negative. |
| Leap days and day count | Fixed leap and common years; random spans up to 1,500 days | 2024 accrues 366 days on a 365 basis. 28 Feb to 1 Mar 2028 is two days. Exact interest equals days × rate ÷ basis. |
| Loan schedules | 1–120 instalments; month-end and 29 February start dates are favoured | The principal is repaid exactly. Due dates are the contractual monthly dates and strictly increase. Totals equal principal plus interest. No amount is finer than the currency allows. |
| Daily servicing | Rates, taxes, per-day fees, HALF_EVEN / HALF_UP / DOWN, up to 60 runs | Net equals gross less taxes and fee. Tax never exceeds gross. Carry stays below one unit. Summed gross plus carry equals the summed exact accrual. |
| FX quotes | Currency pairs, mid rate with a random spread, rounding mode, amount | Output has the target currency's precision. The reported rounding difference is exact and below one unit. DOWN never exceeds the exact amount. Selling and buying straight back never returns more than was sold. |
| Transaction limits | Single and daily limits, 50 random requests | No accepted amount exceeds the single limit, and the accepted total never exceeds the daily limit. |

A deliberate mutation check confirmed the properties bite. Dropping the rounding carry in `ContractModels.interest` makes two of them fail at their first case.

## Invariant test (database)

`TenantAdministrationIntegrationTest.generatedMoneyMovementsKeepTheBooksBalancedThroughReplayAndConcurrentSpends` runs a seeded sequence of 30 steps on two accounts. Each step is a random mix of funding, withdrawals, transfers, holds, releases and withdrawal/transfer reversals. Amounts go up to 1.5 times the withdrawable balance, so refusals are exercised too. After **every** step, the bank database must show:

- every journal balanced, and a trial balance of zero;
- each ledger balance equal to the sum of its journal lines;
- each account position equal to its liability ledger, reconciled, with 0 ≤ held ≤ posted, available = posted − held, and withdrawable ≥ 0.

Then:

- **Replay:** delivering every completed funding and operation again changes no balance.
- **Concurrent spends:** two withdrawals of 60% of the withdrawable balance each are submitted at the same moment, approved by two checkers at the same moment, and delivered at the same moment. Both enter the approval queue, which is by design, since submissions are only proposals. Exactly one posts; the other is refused by the recheck under the bank lock. The books stay balanced.

## In CI

- `verify.yml` runs both suites on every push, with the fixed seed (the database test needs `CBS_INTEGRATION_TESTS=true`, which CI sets).
- `property-exploration.yml` runs the properties nightly with a new seed and 20,000 cases each, and prints the seed so a failure can be reproduced.

## Not covered yet

- Generated sequences for cash custody, FX trades, term deposits and loans. They follow the same pattern and can be added per product as they are confirmed for the pilot (D02).
- Day close in the middle of a sequence.
- Load-level concurrency: that belongs to P-06 performance.
