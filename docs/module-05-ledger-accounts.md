# Module 05.2: Ledger Accounts

Open **Ledger Accounts** in the sidebar or from a bank's detail page. Select an
ACTIVE bank. If the bank was provisioned by an earlier release, choose **Prepare
ledger storage** and supply a reason. This upgrades only its already-owned database;
it never creates a new database or assigns an unclaimed database to the bank.
Newly provisioned banks receive bank migrations V1 and V2 automatically.

## Create or change a ledger

1. Create and approve an active POSTING definition in Chart of Accounts. A GROUP
   cannot be used as a ledger definition.
2. Choose **Add ledger account**, enter a bank-unique code and name, and select
   the posting definition, legal entity and an enabled currency.
3. Supply a reason and submit for approval. A different active platform
   administrator reviews the **Ledger account** request in Approval Inbox.
4. Approval reserves the code and queues delivery. The directory shows
   **Awaiting delivery** until the bank database confirms the change.
5. The worker retries outstanding deliveries every 30 seconds, in batches of up
   to 20. Refresh the directory to see the result. **Retry delivery** requests an
   immediate attempt; a failure remains visible and retryable.
6. Once **Ready**, **Manage** can propose name and active-status changes through
   the same approval workflow. Code, chart definition, legal entity and currency
   cannot be changed. A ledger with outstanding delivery cannot be edited again.

Search, currency/status filtering and pagination are server-side. Status filters
refer to the last approved target status, including changes awaiting delivery.
An inactive ledger can still show Ready: Ready means delivery is confirmed, not
that the account is active. The bank picker shows the first 100 banks; the bank
detail action can select banks beyond that page. UI labels support English,
French and Swahili; account names remain as entered.

## Database and approval boundary

Control-store migration V7 adds `platform.ledger_registry` and
`platform.ledger_delivery`. The registry reserves bank-unique codes and records
chart/entity/currency references and approved status. Approval payloads and the
outbox contain approved definition snapshots for audit and reliable delivery.
They do not hold monetary balances or journal entries.

Bank migration V2 adds the operational `bank.ledger_account` table and an
append-only `bank.ledger_event` receipt/history table in each bank's own database.
Connections use only the operator-approved reference from the tenant registry.
Every connection checks `cbs_identity.owner`, rejects the control database and
requires an existing bank installation. Directory reads reconcile delivered
definitions against the operational bank rows; mismatches fail closed with
`LEDGER_RECONCILIATION_REQUIRED`.

An approval decision, reserved ledger definition, outbox job, bank configuration
version increment and control audit event commit in one control transaction.
There is deliberately no claim of one atomic transaction across both databases.
The worker commits the bank row and its unique request receipt together, then
marks delivery complete and appends the control delivery audit event.

If the bank commits but control confirmation fails, the durable job remains
pending. The same request ID and payload are recognized on retry; they do not
create another ledger or event. Per-request bank locks and control row locks also
serialize concurrent retry attempts. Other edits to that ledger are blocked
until delivery confirmation. A suspended bank pauses deliveries until resumed.
Approval remains APPROVED when delivery is pending: approval and delivery are
separate states, visible in the Approval Inbox and Ledger Accounts respectively.

## Validation and access

- Only authenticated platform administrators can read or propose ledger changes,
  prepare storage and retry delivery in this increment. Existing staff, session
  and MFA checks apply. Approval must come from a different administrator.
- A legal entity and chart definition must belong to the selected bank. Creation
  and activation require an active POSTING definition and an enabled currency.
- Account codes use 1–32 uppercase letters, digits, hyphens or underscores and
  start with a letter or digit. The same code can be used in different banks.
- Approved definitions use the bank configuration version. An intervening bank
  configuration change makes older pending approvals STALE and applies nothing.
- Active ledgers and outstanding deliveries prevent chart definition deactivation.
  A currency used by any ledger, including an inactive ledger, must remain enabled.
- No physical delete endpoint, opening balances or direct status-update endpoint
  is exposed. Pending approvals can be cancelled or rejected through Approval Inbox.

## API

Base path: `/api/v1/admin/tenants/{bankId}/ledger-accounts`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | base | List by `search`, `currency`, `active`, `limit` (1–100), `offset`; includes storage readiness and delivery state |
| POST | base | Submit a ledger change; UUID `Idempotency-Key`; returns HTTP 202 approval request |
| POST | `/prepare` | Upgrade the owned bank database with `expectedVersion` and `reason`; audited; returns 204 |
| POST | `/deliveries/{requestId}/retry` | Retry only an existing approved delivery for this bank and issuer; returns 204 even if delivery remains pending; refresh to see outcome |

A change contains `expectedVersion`, nullable `ledgerId` for creation, `code`,
`name`, `chartAccountId`, `legalEntityId`, `currency`, `active` and `reason`.
Identical retries reuse the approval request. Changed payloads require a new key.

Set `cbs.ledger.delivery-enabled=false` to disable the scheduled delivery worker,
for example in deterministic integration tests. Explicit retry remains available.

## Verification and remaining work

- 64 backend tests pass, including new scenarios for bank storage/isolation,
  approval and edits, invalid inputs, stale requests, dependency guards, storage
  upgrades, bank write rollback, concurrent retry, and a bank commit followed by
  control-transaction rollback.
- 10 UI tests pass; browser fixtures verify storage preparation, account entry,
  submission into Approval Inbox and pending-to-ready retry behavior.
- Tests use isolated local PostgreSQL databases and a separate UI fixture server;
  they do not insert sample ledgers into Neon. The fixture command is
  `node ui/test/approval-preview.mjs --ledgers --unprepared`.

Module 05.3 now supplies journal posting and balances; see [Journals](module-05-journals.md). Pending journals block ledger changes and nonzero balances block deactivation. Holds, reconciliation reports and currency conversion remain future increments. Production delivery monitoring, dead-letter handling and
automated repair of divergent bank data remain operational readiness work.
Live acceptance with two real staff logins remains necessary before a pilot.
