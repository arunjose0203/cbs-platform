# Contract corrections and term-deposit servicing

Implemented 8 October 2026. These are bounded development workflows, subject to bank accounting and production acceptance.

## Setup

The control database requires V32/V33. Use **Prepare storage** in the contract workspace to upgrade each approved bank database through V19. Configure explicit currency limits for `TD_OPEN_REVERSAL`, `LOAN_DISBURSE_REVERSAL`, `LOAN_REPAY_REVERSAL`, `TD_EARLY_CLOSE` and `TD_RENEW`. No limits or financial policies are invented or seeded. Workspaces currently require platform-administrator access.

## Corrections

Open **Term deposits** or **Lending**, select **History & corrections**, then **Reverse event**. Enter a mandatory reason, review the original event and accounting effects, and submit to Approval Inbox. A different active administrator must approve. Original events, journal entries, agreement data and approval evidence remain immutable.

Only the latest unreversed eligible opening, accrual or repayment on the current open business date can be reversed. Undo dependent events first. Closed days and earlier business dates are blocked. Pending postings must finish before another contract event. Duplicate reversal links and repeated delivery cannot create another financial effect.

* Deposit opening reversal cancels the contract and restores the linked customer balance.
* Loan disbursement reversal cancels the contract and removes the credited proceeds. Available funds, including holds and minimum-balance constraints, must permit that debit.
* Repayment reversal restores the original principal, accrued interest and customer balance, including reopening a fully repaid loan.
* Accrual reversal restores the previous accrual date and fractional carry, as well as reversing its journal. Use **Accrue interest** afterward for a separately approved recalculation. This does not amend the agreed rate.

Within the bank database, the contract event, journal and customer ledger commit together. Control delivery acknowledgement follows; immutable receipt recovery handles a bank commit followed by an interrupted acknowledgement. A pending delivery therefore remains visible until recovery completes.

## Term-deposit servicing

Accrue through the current business date (capped at maturity) before settlement. Both workflows require explicit agreement evidence, preview and independent approval.

**Early closure:** available strictly before maturity. Enter the agreed penalty, select gross-interest or interest-after-penalty withholding basis, and supply an income ledger when the penalty is nonzero. Penalty plus withholding cannot exceed accrued interest; this release does not deduct penalties from principal. Full principal and net interest return to the linked account and the contract closes.

**Renewal:** available on or after maturity. Explicitly choose payment of net interest to the linked account or capitalization into renewed principal. Supply a new annual rate, future maturity and unique agreement reference. The renewed cycle starts on the current business date; existing withholding, day basis and ledger mappings continue. A new immutable terms revision preserves the original agreement and earlier cycles. Subsequent accrual and day-close requirements use the effective renewed terms. The renewal limit applies to the renewed principal, including a rollover without customer cash movement.

**Maturity notifications:** the page provides in-app operations alerts for upcoming and overdue active deposits. The default window is seven days; operators can select up to ninety. These are operational alerts, not delivered customer email or SMS. No customer messages are sent.

## Remaining scope

Prior-day/value-date corrections, reversal of redemption/renewal/early closure, partial term-deposit closure, automatic standing renewal, configurable penalty/repricing policies, customer email/SMS delivery and scoped treasury/credit roles remain pending. Bank-approved examples, country policy review, security/load/recovery testing and bank-led acceptance are still required before production use.
