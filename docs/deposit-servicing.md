# Daily deposit servicing: first release

Implemented 7 October 2026. Control schema V23 and dedicated bank schema V14.

## Operator sequence

1. Prepare storage from Deposit servicing (or bank ledger/cash preparation).
2. Identify each funded account's pinned product revision and legal entity. Create four distinct approved GLs per entity/currency: debit-normal interest expense, credit-normal accrued-interest liability, credit-normal fee income and credit-normal tax liability. None may be a customer liability ledger.
3. Configure policy with explicit annual interest, fee per business day, interest withholding percentage, fee tax percentage, day-count basis and rounding. Enter 0 explicitly when a charge does not apply. No rates for Kenya, Uganda or Rwanda are supplied or inferred.
4. An independent administrator approves the policy. Effective dates must be working dates on/after the bank business date. Immutable later approvals may supersede the same effective date; previous policy evidence remains. Each account keeps its pinned product terms.
5. Finish all cash counts, close tills, settle shipments, resolve exceptions, and complete pending approvals/deliveries. Every funded nonclosed account must be active, eligible, reconciled and covered by a policy.
6. Preview daily processing, review each account calculation and submit it. Another administrator approves. Approval seals financial activity for that date. The delivery worker posts the whole batch atomically to the bank database; failed runs retry the same approval.
7. Confirm Posted and customer/GL reconciliation, then independently approve Business Day Close. The next date requires its own servicing run once servicing policies exist.

## Calculation and accounting

Interest basis is closing posted balance. Calendar days run from business date inclusive to next working date exclusive, using Actual/365 fixed or Actual/360. This is daily capitalization; a Friday-to-Monday run accrues three calendar days without weekend compounding. Interest = balance × annual percentage × days / (100 × basis), calculated to 18 decimal places before currency rounding. Unpaid fractional interest carries forward; rounded-up fractions may produce a negative carry corrected by later accruals. Currency precision follows Java currency metadata (KES two decimals; RWF/UGX zero).

The fixed fee applies once per business-day run, not once per calendar day. Interest withholding applies to rounded gross interest; fee tax applies to the fixed fee. Percentages are explicit 0–100; rounding is HALF_EVEN, HALF_UP or DOWN. Fees must fit currency precision. Net credit = gross interest − withholding − fee − fee tax. Holds and agreed minimum balances must remain covered; insufficient funds blocks the whole batch, with no automatic waiver or overdraft.

Per account, the journal accrues Dr interest expense / Cr accrued-interest liability, capitalizes Dr accrued-interest liability / Cr customer liability and tax liability, then charges Dr customer liability / Cr fee income and tax liability. Zero lines are omitted. Zero-money runs still retain fractional carry evidence. Customer statements include the net servicing journal entry. Accrual and capitalization occur in the same transaction; deferred monthly capitalization is not implemented.

The batch approval explicitly authorizes its deterministic per-account journal IDs. Generic journal workers and reversal APIs cannot post/reverse those entries independently. Bank customer balances, journals, carry and immutable run receipts commit together. Retry after a lost control acknowledgement finds the identical bank receipt and does not repost. One run per bank business date is enforced. Bank-side journal checks also reject further ordinary journals for the serviced date.

## Supported boundaries

- Maximum 1,000 funded nonclosed accounts per bank run; larger batches fail explicitly. No chunked processing or performance certification yet.
- Maximum 31 calendar days to the next working date. A policy change inside that interval blocks processing.
- Frozen/ineligible accounts block the batch; there is no automatic exemption or alternate frozen-account accrual treatment.
- Latest 100 policies and 30 runs shown in the UI. Product picker shows the first 100 products.
- Policies and batches require platform administrators and independent approval. Delivery retry runs automatically; scheduling creation/approval of daily runs is not automatic.
- Tiered rates, value dates, backdated recalculation, monthly/quarterly capitalization, tax exemptions/certificates, fee schedules/waivers, funded-account closure settlement, servicing reversal/correction, period/year close and regulatory acceptance remain pending.
- New servicing screens currently use English copy. Complete French/Swahili localization and bank UAT remain pending.

This is a tested development increment, not production certification or completed country tax compliance. No live financial policies or customer batches were created.

## API

Under /api/v1/admin/tenants/{bank}/servicing: GET dashboard; POST /policies; POST /preview; POST /runs; POST /runs/{id}/retry. Policy/run submissions require Idempotency-Key and current expectedVersion. Existing access, recent-MFA and maker/checker requirements remain in force.
