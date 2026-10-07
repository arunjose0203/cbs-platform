# Module 05.3: Journal Posting and Ledger Balances

The **Journals & balances** page supports manual journal submission, independent
approval, retryable posting, immutable history, full linked reversals and current
ledger balances. English, French and Swahili page labels are included.

## First use

1. Sign in as a registered platform administrator and select an ACTIVE bank.
2. Select **Prepare journal storage** if offered. This upgrades that bank's owned
   database to bank migration V3 through the existing audited preparation API.
3. Prepare at least two active, approved and delivered ledger accounts in the
   same legal entity and enabled currency.
4. Select **New journal**, enter a unique reference, narration, debit/credit
   lines and reason. The date is the bank's configured business date.
5. A different active administrator reviews the lines in **Approval Inbox**.
6. Approval creates an **Awaiting posting** job. The worker runs every 30 seconds;
   **Retry posting** is also safe. Refresh until **Posted** is shown.
7. Open **Ledger balances** for cumulative debits, credits and net debit.

Financial acceptance testing should use a designated test bank and two real
staff identities. No example financial journals are inserted into Neon by the
implementation or its tests.

## Rules and transaction boundaries

- A journal contains 2–100 positive lines and at least two distinct ledgers.
  Every line belongs to one bank, legal entity and currency. FX is excluded.
- Debits must equal credits exactly. Amounts travel as decimal strings, with up
  to 18 integer digits and the currency's supported fractional precision.
  Excess precision is rejected rather than silently rounded. Java uses
  `BigDecimal`; browser totals use integer minor units (`BigInt`).
- Submission and approval both validate the bank configuration version,
  business date, enabled currency, posting definitions and actual bank ledgers.
  Changed configuration makes an older request stale. Approval of a journal
  does not itself change bank configuration or invalidate unrelated journals.
- Approval, durable delivery job, reference/reversal reservation and audit commit
  together in the control database. They do not alter balances.
- Delivery locks the control job and tenant, then posts into the bank's owned
  database. Header, all lines and balance updates commit in a single PostgreSQL
  transaction. Deferred constraints verify balance and account scope. Line
  triggers update balances. An error rolls all of these changes back.
- The journal UUID is the approval UUID. A repeated delivery checks the existing
  payload receipt before writing. If the bank commits but the control database
  loses its acknowledgement, retry confirms that receipt without adding money.
  Concurrent postings serialize on ledger locks in a consistent order.
- These are two database transactions, not a distributed atomic commit. During
  acknowledgement recovery, a bank balance may include a journal still marked
  **Awaiting posting** in the control directory. Retry reconciles that state.
- Pending journals block changes to referenced ledgers. A nonzero net balance
  blocks ledger deactivation. Posted headers and lines reject update/delete;
  new lines cannot be appended to an already committed journal.
- Reversal creates a new approved journal with all original sides exchanged,
  using the current business date. One reversal is allowed per original journal;
  reversing a reversal is rejected. The original remains unchanged.
- Net debit is cumulative debit minus cumulative credit. Negative means credit
  balance. Currencies are never summed together. These are accounting balances,
  not available balances or customer account balances.

## Persistence and API

Control migration V8 adds `platform.journal_delivery` and
`platform.journal_ledger`. Approved payloads and delivery metadata are stored
there for recovery. Bank migration V3 adds `bank.journal`, `bank.journal_line`
and `bank.ledger_balance`. Monetary posting records and balances live in the
bank's dedicated database; every connection verifies bank ownership.

Base: `/api/v1/admin/tenants/{bankId}/journals`. All operations require an
authenticated CBS platform administrator.

| Method | Path | Behavior |
| --- | --- | --- |
| GET | base | Paginated approved journal directory and posting status |
| POST | base | Submit for approval; UUID `Idempotency-Key`; returns 202 |
| GET | `/balances` | Paginated bank ledger balances, including zero-balance ledgers |
| GET | `/{id}` | Read a posted journal from this bank's database |
| POST | `/{id}/reverse` | Submit a full reversal with `expectedVersion`, unique `reference`, `reason` and UUID `Idempotency-Key` |
| POST | `/{id}/retry` | Retry approved posting for this bank and issuer; returns 204; refresh for outcome |

Journal submission fields: `expectedVersion`, `reference`, `narration`,
`businessDate`, `legalEntityId`, `currency`, `lines` (each `ledgerId`, `side`
DEBIT/CREDIT, decimal-string `amount`), nullable `reversalOf`, and `reason`.
Identical retries return the same approval; changed payloads require a new key.
The reversal endpoint constructs the opposite lines on the server.

The existing `cbs.ledger.delivery-enabled=false` property disables both ledger
and journal scheduled delivery, including in deterministic integration tests.
Explicit retries remain available. Suspended banks retain pending jobs until
resumed. Approved jobs retain their approved business date when delivered later.

## Verification and limits

68 backend tests and 12 UI tests pass. Journal integration scenarios cover exact
large decimals, independent approval, reversals, tamper rejection, tenant scope,
precision/date validation, stale approval, pending and balance guards, rollback
after partial line processing, lost acknowledgement and concurrent retries and
postings. Browser fixtures exercise entry validation and approval submission.

Live acceptance with two staff logins remains outstanding. Existing bank schema
upgrades are explicit through the page. This increment does not implement FX,
holds, overdraft limits, period/day closing or customer account operations. Module 05.4 now adds [trial balance, statements, reconciliation and CSV export](module-05-reports.md). Production deployment still needs
separate operational roles, backup/restore validation, monitoring and load tests.
