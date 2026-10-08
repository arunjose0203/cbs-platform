# FX valuation and gain/loss accounting

## Implemented scope

The FX page supports approved closing rates, weighted-average trading-book cost,
functional-currency valuation, cumulative realized/unrealized gain and loss,
dedicated accounting journals, foreign-unit long/short limits and day-close gates.
This is the trading book created by customer FX conversions. It is not a complete
translation of every foreign-currency asset and liability in the bank.

Each book belongs to one bank, legal entity and foreign currency. One conversion
leg must be the entity's functional currency. KES uses two decimal places; RWF
and UGX use zero. Amounts remain decimal strings/BigDecimal throughout; no binary
floating-point money calculations are used. Functional valuation uses HALF_EVEN
rounding, explicitly separate from the bank's conversion quotation rounding.

## First use

1. Open **FX & exchange rates** and prepare valuation storage if prompted.
2. Create six dedicated active ledgers through Ledger Accounts: foreign position
   (foreign currency, Equity), functional position (functional currency, Equity),
   realized gain and unrealized gain (functional currency, Income), and realized
   loss and unrealized loss (functional currency, Expense).
3. Choose **Configure trading book**. Supply the entity, currency, ledger IDs,
   explicit long/short limits in foreign units, and the bank's accounting-policy
   reference. Weighted-average cost is the supported policy. An independent
   administrator approves it in Approval Inbox. No bank rates or limits are seeded.
4. Complete FX conversions/reversals and their deliveries. Use the book's exact
   position ledgers. Transaction and daily operation limits still apply in addition
   to the trading-book exposure limits. Approved unposted trades reserve exposure.
5. Choose **Propose closing rate**. Enter functional units per one foreign unit,
   the current business date and source evidence. Have another administrator approve
   it. A newer approved rate supersedes earlier rates for that date until valuation
   is approved; earlier evidence is retained.
6. **Preview valuation**, inspect the position, cost, market value, cumulative
   gain/loss and proposed adjustment journal, then submit for independent approval.
7. Wait for **Posted**; a failed delivery is safely retried with the same request.
   A no-change valuation records a receipt without inventing a zero-value journal.
8. Complete deposit daily processing and then Business Day Close. Every configured
   book needs a posted current-day valuation, including a zero-position book.

Approval seals the book for that business date, including while its posting is
pending. Further conversions, reversals and closing-rate changes for that book are
blocked until the next business date. Finish valuations before deposit servicing
seals the entire bank's day.

## Accounting behavior

Foreign quantity is positive for a long position and negative for a short. The
functional cost has the same sign. Purchases/sales closing an existing position
release its weighted-average cost; crossing through zero establishes the new
opposite position at the execution price. Calculations retain 30 fractional digits
for proportional cost allocations before functional-currency rounding.

The book identities are:

- Cumulative realized gain/loss = cumulative functional cash flow + remaining cost.
- Market value = foreign quantity × approved closing rate.
- Total gain/loss = cumulative functional cash flow + market value.
- Unrealized gain/loss = total gain/loss − rounded realized gain/loss.

Each closing journal posts the differences from the previous cumulative gain/loss
components. A gain-to-loss transition reverses the previous gain balance and uses
the loss ledger. It does not leave both accumulated gain and loss balances on the
same component. Realization is recognized in this approved closing batch, not in a
separate journal at each intraday trade.

Example: buy USD 1 for KES 129 and close at 130. Unrealized gain is KES 1.
Sell it the next day for KES 131. The next close records cumulative realized gain
of KES 2, releases the prior KES 1 unrealized gain and posts only the remaining
net KES 1 gain. The functional position bridge returns to zero.

This design follows the distinction between monetary-item closing-rate translation
and other measurement bases in [IAS 21](https://www.ifrs.org/issued-standards/list-of-standards/ias-21-the-effects-of-changes-in-foreign-exchange-rates/).
It does not assert IFRS or local regulatory compliance for the whole CBS. A bank's
finance team must approve its classifications, policies and accounting acceptance.

## Integrity and recovery

- Maker/checker, active staff, tenant version, bank ownership and currency checks.
- Dedicated ledger mappings cannot be edited through ordinary ledger maintenance;
  manual journals and generic reversals cannot use valuation P&L ledgers.
- All relevant trades must be delivered before preview/approval. The bank store
  independently reconstructs immutable trade history in posting order and reconciles
  both position bridges and all four P&L ledgers.
- The approved calculation includes a history fingerprint and previous valuation.
  Posting rechecks the snapshot and locks the affected ledgers.
- Journal and receipt commit together in the bank database. A failure rolls back
  both. A lost control acknowledgement recovers from the matching immutable bank
  receipt rather than posting a duplicate journal.
- Control approval and delivery records are immutable except delivery status fields;
  bank receipts are immutable. One valuation is allowed per book/date.
- Existing FX positions without a configured book block new conversion and day close.
  A legacy book is adoptable only when its exact two-ledger history reconciles.
  Shared functional bridges or cross-currency legacy trades are not silently remapped.

## API and storage

Base: `/api/v1/admin/tenants/{bank}/fx/valuation`.
GET returns books, closing-rate records and the latest 100 valuations.
POST `/books`, `/closing-rates`, `/runs` submit independent approvals and require an
`Idempotency-Key`. POST `/preview` is read-only. POST `/runs/{id}/retry` retries delivery.
The existing FX `/prepare` upgrades bank storage. All endpoints currently require
an active platform administrator; a dedicated bank treasury permission model remains
future work.

Control migration V31 adds `platform.fx_book`, `fx_book_ledger`, `fx_closing_rate`
and `fx_valuation`. Bank migration V17 adds `bank.fx_valuation` and receipt/journal
guards. Book configuration/rates/approval state live in the control database;
posted journal lines, balances, original FX trades and valuation receipts live in
the owning bank's database.

## Remaining scope and release gates

- Bank-wide monetary-asset/liability retranslation, functional trial-balance
  translation, historical-rate nonmonetary classifications, consolidation/OCI,
  hedge accounting, multiple trading books and two-foreign-currency cross trades.
- Controlled policy/limit amendments, valuation corrections/reopening and period/
  year-end P&L transfer. Initial book mappings and limits are immutable in this release.
- Original FX reversals settle the opposite flow at the original amounts using
  current weighted-average cost; they do not retroactively restate prior-day cost
  history. Full closed-period correction accounting needs the correction workflow.
- Position limits cover this conversion book in foreign units, not all bank-wide
  market exposure, stress limits or regulator-specific net-open-position ratios.
- Automated approved rate feeds, full multilingual review, larger report exports
  and incremental/partitioned valuation for production-scale histories. This version
  reconstructs full trade history rather than claiming a load-tested batch engine.
- Bank-selected accounting policy/evidence, independent financial acceptance,
  security/load/backup/restore/failover testing and production access configuration.

The next implementation sequence remains contract corrections, term-deposit
servicing, lending controls, then bank-led production acceptance. None is marked
complete merely because the FX trading-book valuation is implemented.
