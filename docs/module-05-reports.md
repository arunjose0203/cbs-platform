# Module 05.4: Trial Balance, Ledger Statements and Reconciliation

Open **Accounting reports**, select the bank, legal entity, currency and report,
then select **Run report**. Ledger Statements additionally require a ledger and
inclusive date range. The page supports English, French and Swahili labels.

## Report definitions

- **Trial Balance** derives cumulative debit and credit turnover from posted
  journal lines through the selected business date, inclusive. Each account's
  debit balance is `max(debits - credits, 0)`; credit balance is the opposite.
  Summary totals cover the entire report. Currencies and entities never mix.
- **Ledger Statement** shows signed opening net debit before the start date,
  period debits/credits, each line's running net debit and closing net debit.
  Order is business date, posting timestamp, journal UUID, then line number.
  Reversal lines retain the original journal link. Negative net debit means a
  credit balance. The start and end dates are inclusive.
- **Reconciliation** compares lifetime journal debit and credit totals separately
  with stored ledger debit and credit totals. Differences are stored minus
  derived. Equal net balances cannot conceal differing turnover. It reports
  mismatches without writing or automatically repairing balances.

Every report reads a single REPEATABLE READ, read-only transaction on the bank's
dedicated database after ownership verification. Approved but unposted requests
are excluded. A journal already committed in the bank is included even if the
control database is still awaiting delivery acknowledgement. Reports use actual
bank records rather than control delivery status.

Inactive ledgers are included. Account names and the ledger catalogue are current;
historical dates filter journal activity, not historical account metadata.
Reports are not frozen period-close records. Until a day is closed, a later
delivery with an older approved business date can change a historical report.
[Business Day Close](module-05-day-close.md) seals journal activity through its
cutoff; report account metadata still reflects the current catalogue. Period
locks remain future work.

## API and export

`GET /api/v1/admin/tenants/{bankId}/accounting-reports`

Required parameters: `kind` (`TRIAL_BALANCE`, `LEDGER_STATEMENT`,
`RECONCILIATION`), `legalEntityId`, ISO `currency`.
`toDate` is required for trial balance and statement. Statements also require
`fromDate` and `ledgerId`. Dates use ISO YYYY-MM-DD, years 1900–9999. Reconciliation
always covers all posted history. ACTIVE and SUSPENDED banks are readable.
Only authenticated platform administrators can use these endpoints.

The JSON response contains scope, generation timestamp, columns, exact decimal
strings, rows, summary and a `reconciled` flag. For trial balance the flag means
debit balances equal credit balances; for reconciliation it means all individual
turnover comparisons match. It has no independent significance for statements.

Add `csv=true` to download UTF-8 CSV with BOM, scope/date metadata, summary and
rows. Values are quoted, embedded quotes escaped, and formula-like text prefixed
with an apostrophe. Signed decimal amounts remain numeric text. Responses use
`Cache-Control: no-store`. Export reruns the last successfully displayed report's
filters and can include newly posted entries; its own timestamp identifies it.
CSV headers remain stable English API labels.

Reports fail explicitly above 10,000 rows rather than returning partial totals.
Statement users can narrow the date range. Larger trial balances or reconciliation
reports need a future streamed operator export. SQL statements have a 30-second
timeout. No schema migration is needed beyond Module 05.3 bank V3.

## Verification and limits

72 backend tests and 13 UI tests pass. Added coverage checks historical cutoffs,
opening and closing balances, exact large decimals, reversals, unposted exclusion,
committed-but-unacknowledged inclusion, turnover drift detection without repair,
tenant/entity/currency scope, access control, invalid filters, CSV response headers,
formula escaping and HTML escaping.

Browser validation uses an isolated server with fictional data; no sample
financial entries or balance changes are made in Neon. Production readiness still
requires realistic volume tests, backup/restore checks and live staff acceptance.
This increment does not provide consolidated FX reports, frozen period reports,
automatic repair, reconciliation of approval queues, or export access auditing.
