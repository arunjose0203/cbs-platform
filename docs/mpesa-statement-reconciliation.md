# M-Pesa statement import and reconciliation

Implemented 7 October 2026. This extends the Kenya KES sandbox payment workflow with complete **normalized CSV** imports, balance reconciliation, controlled accounting adjustments, corrections and closure. It does not certify compatibility with an unprovided Safaricom portal export or activate live payments.

## Operator workflow

1. Open **M-Pesa statements**, choose the bank and select **Import CSV**. Choose the dedicated, delivered KES debit-normal asset ledger representing the provider wallet. Enter the statement reference, wallet/shortcode, period, opening and closing balances, and the original evidence repository reference.
2. Select a UTF-8 CSV. Use **Column mapping** when the headings differ. Mapping changes column names only; normalize dates, amounts and transaction types explicitly. Keep the original file and the transformation record in the bank-approved evidence repository.
3. Validate and review the first ten rows and totals. Confirm the wallet, dates and balances before importing. Import itself does not create a journal or change a customer balance.
4. Review each exception. Matching suggestions use the exact CBS payment reference, then verify amount, beneficiary, wallet and date. Suggestions never settle automatically. Every decision goes to **Approval Inbox**.
5. Have a different administrator approve. The checker must differ from both the decision requester and original importer. A changed bank version or reconciliation snapshot invalidates the proposal; refresh and submit again.
6. Refresh after approved adjustments post. Close only after every row is resolved, opening/closing differences are zero, all CBS wallet journals and payments are covered, and no accounting deliveries or payment conflicts remain.
7. Export the reconciliation CSV. Closure seals the wallet's statement period against new postings. Reopening needs independent approval and retains all history. Daily processing/day close reject open imports.

## CSV contract

Required logical headers:

```csv
receipt,date,type,credit,debit,balance,payment_reference,beneficiary
TOPUP001,2026-10-07,CREDIT,1000,0,1000,,
DEMORECEIPT1,2026-10-07,PAYOUT,0,100,900,DEMO-PAY-001,254700000001
DEMORECEIPT1,2026-10-07,FEE,0,5,895,,
```

This fictional example has opening balance `0` and closing balance `895`. It is a format example, not a file to import into an actual bank. See [sample CSV](samples/mpesa-normalized-example.csv).

| Field | Rule |
| --- | --- |
| receipt | Provider transaction/support reference, 1–80 letters/digits plus `. _ / -`; normalized to uppercase |
| date | ISO `YYYY-MM-DD`, chronological, within the supplied period and no later than today UTC |
| type | Exactly `PAYOUT`, `FEE`, `CREDIT`, `DEBIT` or `REVERSAL`; no inferred success/type mapping |
| credit / debit | Nonnegative plain decimals, at most 18 whole digits and two decimal places; exactly one is positive |
| balance | Signed provider running balance; must equal previous balance + credit − debit |
| payment_reference | Exact original CBS reference for PAYOUT; optional otherwise |
| beneficiary | `254` followed by `7` or `1` and eight digits; required for PAYOUT, optional otherwise |

PAYOUT, FEE and DEBIT require debit; CREDIT requires credit; REVERSAL can be either direction. Fees must be separate rows. No thousands separators, currency symbols, scientific notation, Excel formulas, zero-value movements or implicit locale conversion. Header-only statements can represent a period without movements when opening equals closing. Dates are business dates; timestamp/timezone conversion must be an explicit preparation step.

Quoted fields, escaped double quotes, CRLF/LF and a UTF-8 BOM are supported. Limits: **1 MiB, 5,000 data rows, 40 source columns and 1,024 characters per cell**. Duplicate receipt/type pairs within one file are rejected. Unmapped extra columns are ignored; their values are not retained as normalized evidence. All eight logical columns remain required, even when individual cells may be blank.

The importer stores immutable normalized rows, mappings, metadata and the SHA-256 of the supplied CSV text. It does **not** archive or authenticate the original document. The evidence reference must identify a separately retained original and any normalization record.

## Decisions and accounting effects

| Decision | Effect / restrictions |
| --- | --- |
| MATCH | Links a PAYOUT row to an exact posted CBS payment and its provider receipt. Settles submitted/unknown payouts through the existing payment reconciliation engine; already-settled payouts must have the same receipt. No second debit. |
| LINK | Links a non-PAYOUT row to an existing posted journal with one exact signed wallet movement, matching currency and date. A wallet journal cannot be claimed by another row. |
| UNLINK | Removes a mistaken LINK from current reconciliation coverage, with an immutable decision. Does not reverse the journal. Re-link the correct journal afterwards. |
| BOOK | Posts the missing wallet movement against an independently approved contra ledger. Provider credit: debit wallet/credit contra; provider debit: debit contra/credit wallet. Fees require an expense contra. Customer and cash ledgers are prohibited; no automatic customer-credit inference. |
| REVERSE | Reverses a posted BOOK in full through the bank journal engine, retaining original and reversal. The row then requires a corrected booking. Generic journal reversal cannot bypass this workflow. |
| DUPLICATE | Acknowledges identical evidence already resolved in another closed statement for the same wallet. Reuses its accounting coverage without posting again. Reference the original resolved row, not another duplicate acknowledgement. |
| CLEAR_CONFLICT | Independently attests authoritative provider evidence resolving an unverified conflicting callback. Settled payments need a matching receipt row. Refunded payments require a complete balanced statement with no payout row for that reference. A blocked pending refund has stricter checks: it must be the only pending posting/missing payment, with exactly its debit explaining the difference. This action does not itself post a refund. |
| CLOSE / REOPEN | Seals/unseals the statement period. A statement referenced by a closed duplicate statement cannot reopen until dependents are reopened. |
| SUPERSEDE | Retains an untouched incorrect import and marks it superseded in favour of a different import for the same ledger and period. Imports with decisions require the correction workflow instead. |

BOOK/REVERSE use the current bank business date, which must be within the statement period. Unknown external payouts can be booked only when no CBS candidate exists, with an approved bank-defined contra mapping and evidence. Do not invent suspense/fee accounting policies. Existing CBS payment mismatches require investigation rather than a second booking. A missing provider row alone never automatically refunds a customer.

Approved internal failed-payment refunds explain both the original and reversal journals without inventing external statement movements. Actual provider reversals/returns do not automatically reverse customer balances; those require the corresponding verified payment lifecycle.

## Integrity and recovery

- Import commands use a UUID `Idempotency-Key` scoped to issuer and actor. Retrying identical input returns the original batch. Reusing a key with changed input conflicts. Identical normalized financial content returns the existing import even under a new label; a reused statement label with different content conflicts.
- Bank locking serializes reconciliation decisions and accounting changes. A report hash captures the rows, balances and coverage; approval checks it again, including changes from asynchronous posting that do not change the bank version.
- Receipt/type claims cannot be booked or matched again through a different import. Overlapping legitimate files use duplicate acknowledgement after the original is closed.
- Approved adjustments use existing immutable bank journals and idempotent posting receipts. Lost acknowledgement is recovered by retrying the same approved delivery, never by creating a replacement booking.
- Deferred database checks require matching independent approvals for decisions and status/row projections. Source rows, decisions and journal claims cannot be edited or deleted through ordinary DML.
- Reconciliation exports are audited and quote cells with spreadsheet-formula protection. They include balances, blockers, missing CBS/provider items and all rows, independent of UI pagination.

## API and storage

Base: `/api/v1/admin/tenants/{bank}/statement-imports`. Existing authenticated platform-administrator authorization applies to every endpoint.

| Route | Purpose |
| --- | --- |
| GET base, limit/offset | Import directory |
| POST `/preview` | Validate complete CSV and return totals and first ten rows |
| POST base | Import immutable evidence; UUID idempotency header required |
| GET `/{id}`, limit/offset/exceptions | Reconciliation report, row page, latest 100 decisions |
| GET `/{id}/export` | Audited JSON envelope containing the full report CSV |
| POST `/actions` | Submit `STATEMENT_ACTION` approval; UUID idempotency header required |

Control migration **V26** adds import, command, row, decision and journal-claim tables. Financial entries still reside in the selected bank's database through the existing journal engine; no new bank migration is needed (latest V14). Existing applied migrations are unchanged.

## Release boundaries

This is a bounded, operator-reviewed reconciliation implementation. Remaining production acceptance includes:

- Actual provider export samples, original-document authenticity/retention, verified mappings and sandbox/provider acceptance.
- Bank-scoped reconciler roles, privacy/retention controls, full translations, searchable selectors beyond the first 100 banks/ledgers and complete history pagination.
- Scheduled/SFTP/API statement acquisition, large asynchronous imports, bulk decisions and production-scale processing. Current reports cap wallet journal rows and historical wallet payment scanning at 10,000.
- Explicit timing differences carried across business days, partial/split/net settlements, multiple source wallets per account, provider reversal/recall and dispute workflows. Current closure requires exact same-period balance and coverage; it does not silently write off differences.
- Independent bank accounting sign-off for fee/suspense rules, security review, restore/failover and production load testing.

No live statement, adjustment, payment or financial policy is seeded by installing this module.
