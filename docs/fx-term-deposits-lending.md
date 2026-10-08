# FX, term deposits and lending — first operational release

Implemented on 8 October 2026. These are bounded development modules, not production acceptance or complete treasury/credit systems. They use existing platform-administrator authentication, independent approval, bank version checks, mandatory transaction limits, immutable bank evidence and recoverable posting. No bank rate, country tax rate or financial limit is installed automatically.

## Storage and activation

- Control migrations V28–V30 add approved FX rate history, conversion delivery, contract terms and contract delivery queues.
- Bank migrations V15–V16 add atomic FX/customer entries and contract principal/interest records. Existing bank databases require **Prepare FX storage** or **Prepare storage** in Term deposits/Lending. New bank provisioning includes them.
- Control data stays in the configured control database. Financial journals and customer/contract balances stay in each bank's owned database.
- Prepare storage does not register products, create ledgers, approve rates or move funds. No financial test data is written to development banks by the tests.
- All money is decimal text over HTTP and `BigDecimal`/PostgreSQL numeric internally. Currency fraction digits include zero-decimal RWF/UGX and two-decimal KES. Real country/product policy approval remains necessary.

## FX operator workflow

1. Enable both currencies in Bank settings. Prepare bank storage.
2. Propose a rate in **FX & exchange rates**: base and quote currency, bank buy/mid/sell, inclusive business-date period, UTC expiry and source evidence reference. `1 base = rate × quote`. Buy/sell are from the bank's perspective. Buy must be positive and no greater than mid, which must not exceed sell.
3. Another administrator approves. An inverse pair occupies the same period as the direct pair; overlapping active rate records are rejected. Withdraw an incorrect rate through approval before replacing it. History is immutable.
4. Configure an approved FX trading book with the two position ledgers, four dedicated gain/loss ledgers, weighted-average cost and explicit long/short limits. See [FX valuation setup](fx-valuation.md). Each foreign currency requires its own functional-currency bridge.
5. Approve bank-wide `FX_CONVERSION` limits in the source currency and `FX_REVERSAL` limits in the reverse source currency. Approved, unposted conversions consume daily limits too.
6. Select an active rate, choose **Convert customer funds**, and search for active funded accounts. Both accounts must belong to the same customer and legal entity. Review exact source/target amounts before submitting.
7. Independent approval rechecks rate availability, expiry, bank version, currency configuration, account status, funds and limits. After approval the fixed instruction can recover safely even after the quote expires.
8. Two balanced currency journals and two customer movements commit in one bank transaction. A failure on either leg rolls back both. Retry the original approved conversion after a connection failure; do not submit a replacement.
9. **Reverse conversion** restores the exact original amounts as one approved operation. It needs sufficient available funds in the original receiving account. Partial and individual-journal reversals are rejected.

Bank buys base: source base × buy = target quote. Bank sells base: source quote ÷ sell = target base. Target rounding uses the configured currency mode. The quote records the target-currency rounding difference (division residual is represented to 30 decimal places). The mathematical residual is informational; it is not a separate booked residual or profit/loss entry.

The current release covers own-customer, same-entity spot conversion and approved functional-currency trading-book valuation with realized/unrealized gain/loss and long/short limits. One leg must be the entity’s functional currency. Full-bank monetary-item translation, cross-rate derivation, feeds, rate tolerances, bank-wide market exposure, forwards, cross-customer settlement and dedicated treasury roles remain pending. See [the valuation guide](fx-valuation.md) for scope and operating order.

## Term deposits

The first product is a fixed-rate, simple-interest deposit with full payout at maturity. Terms are approved per contract; there is no reusable term-deposit product catalogue yet.

1. Use an active funded savings/current account as the linked settlement account.
2. Create separate unused LIABILITY ledgers for this contract's principal and accrued interest, an EXPENSE ledger for interest, and a LIABILITY tax ledger when withholding is nonzero. Principal and accrual ledgers are dedicated permanently.
3. Approve `TD_OPEN` and `TD_REDEEM` limits. Redemption limits apply to the net customer credit, including net interest.
4. Open **Term deposits**, enter the actual agreement reference, principal, fixed annual percentage, actual/360 or actual/365-fixed basis, maturity and explicit withholding percentage. Preview and obtain independent approval.
5. Opening debits the linked customer's liability and credits the term principal liability atomically.
6. **Accrue interest** computes actual calendar days from the prior accrued-through date to the business date, capped at maturity. It debits interest expense and credits accrued-interest liability. HALF_EVEN rounding and fractional carry prevent daily rounding loss. A zero-interest accrual still records the processed date without a zero journal.
7. After accruing through maturity, **Redeem at maturity** pays principal plus accrued interest less withholding to the linked account, clears the dedicated balances and closes the contract. The withholding amount is posted to the configured tax liability.

The first release does not support early termination, partial redemption, renewal/rollover, variable/tiered rates, periodic interest payouts, liens, notifications, correction/reversal of posted contract events or country tax exemptions. Sub-minor-unit carry is discarded when the contract closes; it is not a money amount booked to the ledger. Contract terms do not change after opening approval.

## Lending

The first product is a fixed-rate loan with equal-principal monthly instalments and actual-day interest on outstanding principal. This is **not an equal-payment annuity/EMI engine**. The schedule is projected assuming payments occur on contractual calendar dates; actual prepayments change subsequent interest.

1. Complete the bank's assessment and approval evidence outside this initial origination page. The agreement/assessment reference is required, but the application does not yet verify underwriting, affordability, collateral or bureau evidence.
2. Use an active funded linked customer account; configure unused ASSET ledgers for this contract's loan principal and accrued interest, plus an INCOME ledger for earned interest.
3. Approve `LOAN_DISBURSE` and `LOAN_REPAY` currency limits.
4. Open **Lending**, enter the principal, annual rate, day basis and 1–120 monthly instalments. Maturity must equal start date plus that many calendar months. Month-end dates use Java calendar month arithmetic. Withholding is zero for this loan product.
5. Independent opening approval creates an atomic debit to the loan asset and credit to the customer's linked account. This is an internal account credit, not an external provider payout.
6. Accrue through the current business date before repayment. Accrual debits accrued-interest receivable and credits interest income. Unpaid principal continues accruing at the contracted rate after maturity; this release adds no penalty rate or interest-on-interest.
7. Repay from the linked account. Allocation is accrued interest first, then principal. Overpayment and insufficient available balance are rejected. Partial principal payments are supported. Full payoff closes the contract.
8. Details show the contractual projected schedule and past-due principal, allowing credit for principal already repaid. This is not a full delinquency/interest-arrears or impairment calculation.

Credit-policy/product catalogues, debt-service assessment, exposure aggregation, collateral/guarantors, disbursement conditions, fees, penalties, variable rates, grace periods, restructures, moratoria, collections, impairment, write-offs/recoveries, bureau reporting, automated repayment and broader event corrections remain pending. Contract-level administrator approval is not a replacement for a bank's credit approval process.

## Shared controls and operations

- An account involved in an approved unfinished FX/contract posting is locked against other customer operations and definition changes until delivery is resolved.
- Contract principal and accrued-interest balances reconcile to their dedicated GL balances. Customer entries reconcile to customer liability ledgers; ordinary/manual posting cannot use registered contract ledgers.
- Statements include FX and contract cash movements, with historical migrations supported by capability checks.
- Daily close/servicing blocks pending financial approvals, unfinished deliveries and required contract accrual through the current business date (term accrual capped at maturity). Contract accrual runs before deposit servicing seals the day. Operators initiate and independently approve accrual; no scheduler invents approvals.
- Delivery workers retry the original immutable instruction. A bank commit followed by a lost control acknowledgement is recovered by matching the immutable receipt, without posting a second financial event.
- Authentication/MFA settings are unchanged. These workspaces are platform-administrator only in this release; scoped treasury/credit roles are still required for a production deployment.
- Review bank agreements, financial mappings, country requirements, recovery and security/load evidence before considering a pilot. No production-readiness percentage is asserted.

## Subsequent FX valuation increment

Trading-book closing valuation, realized/unrealized gain/loss, explicit long/short limits and day-close gates are now implemented. See [the valuation guide](fx-valuation.md). Full-bank translation and production accounting acceptance remain outstanding.

## Subsequent corrections and servicing increment

Current-open-day opening/accrual/repayment reversals, early term closure, renewal and in-app maturity alerts are now implemented. See [the corrections and servicing guide](contract-corrections-servicing.md) for supported operations and remaining limits; it supersedes earlier pending statements for those bounded flows.

## Subsequent lending controls increment

New loans now require approved credit applications with verified conditions. Manually evidenced affordability/collateral review, principal days past due, collection notes, controlled rescheduling, assessed impairment and full provided write-off are implemented. [Lending controls](lending-controls.md) supersedes earlier pending statements for these bounded capabilities and details remaining production work.
