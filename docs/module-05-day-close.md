# Module 05.5: Business Day Close

Use **Business Day Close** after posting and reconciling the day's journals.
This increment seals accounting dates and advances the bank's business date.
It does not calculate interest, fees, accruals, FX revaluation or year-end entries.

## First use

1. Select the bank. For an existing bank, choose **Prepare closing storage** and
   provide a reason. This audited action upgrades its dedicated database to V4.
2. Resolve pending journal and ledger approvals, finish their deliveries, and
   investigate any reconciliation differences. Reject or cancel unwanted pending
   requests through the Approval Inbox.
3. Check the current business date and next working day. The next date skips the
   bank's configured weekends and holidays.
4. Choose **Request day close**, give a reason, and submit for approval.
5. A different active platform administrator approves the request in **Approval
   Inbox**. Readiness and the bank version are checked again at approval.
6. The delivery worker completes the approved close, normally within 30 seconds.
   Refresh the page to see **Closed**, the new business date and **Closing totals**.
   A failed attempt remains **Closing** with a reason; resolve it and use **Retry
   close**, or let the worker retry.

No live day is closed by deployment or database preparation. Closing cannot be
undone through this module. Existing banks with ledger accounts must advance the
business date here; Bank settings can establish the initial date before ledgers
exist, but cannot bypass the close afterwards.

## Controls and recovery

Submission and approval require an ACTIVE bank, V4 storage, no other pending
accounting approvals or unfinished ledger/journal deliveries, and reconciled
balances. Debit and credit turnover are compared separately against journal lines;
balanced net totals alone are insufficient. Totals balance independently for each
legal entity and currency. A stale request must be resubmitted.

An approved close creates a durable control-database job. Until it completes,
journal, ledger and bank-settings changes are blocked. A merely pending approval
does not freeze work; approval rechecks readiness. Suspending the bank pauses an
approved close until it is resumed.

The bank database transaction locks its business-day state, rechecks balances,
rejects future-dated posted journals, writes an immutable close receipt with exact
decimal closing balances per entity/currency, and seals all dates through the
closing day. A database trigger prevents subsequent journal inserts on those
dates. Journal insertion and closing use shared/exclusive locks on the same state
row, so an in-flight posting cannot pass the cutoff unnoticed.

Only after the bank commits does a control-database transaction advance the
business date, mark the job complete and write its audit event. If that acknowledgement
is lost, the bank receipt allows an idempotent retry: the control date stays old
and new financial work stays blocked until acknowledgement succeeds. Existing
journal receipts also remain safe to acknowledge after their day is closed.

History is paginated. Closing totals are cumulative debit/credit balances by legal
entity and currency, not daily movement or a historical account-name snapshot.
Currencies are never added together. The interface has English, French and Swahili
labels. Only platform administrators can operate these endpoints in this increment.

## API and schema

All routes are under `/api/v1/admin/tenants/{bankId}`:

- `GET /business-day?limit=25&offset=0`: current/next date, closed-through date,
  readiness blockers and closing history.
- `POST /business-day/close`: UUID `Idempotency-Key`; JSON `expectedVersion`,
  `businessDate`, `nextBusinessDate`, `reason`. Returns 202 with an approval request.
- `POST /business-day/{requestId}/retry`: retries an approved close; returns 204.
  Refresh the dashboard to inspect completion or the recorded failure.
- `POST /ledger-accounts/prepare`: existing preparation route, with
  `expectedVersion` and `reason`; applies all pending bank migrations including V4.

Control V9 adds close jobs and the `DAY_CLOSE` approval action. Bank V4 adds the
state, append-only receipts and journal date guard. Storage preparation preserves
ownership checks and existing bank records.

## Verification and boundaries

75 backend tests and 14 UI tests pass. New coverage verifies separate approval,
weekend/holiday advancement, pending-work checks at submission and approval,
transaction rollback, loss of control acknowledgement after bank commit, duplicate
concurrent retries, posting into a closed date, reconciliation failures before and
after approval, retry recovery, access and bank scope, and UI escaping/readiness.

Browser checks use an isolated fixture server with fictional bank data. No real
bank date or balance is changed for demonstrations. Live acceptance with two staff
identities, volume/concurrency testing under realistic load and backup restoration
remain required before a pilot. Period/year closing, reopening, branch-specific
closing, automatic financial batches and holds remain outside this increment.
