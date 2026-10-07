# Module 08.2 — Funding, customer balances and accounting integration

Implemented 6 October 2026. Records externally received deposits after independent approval. It does not initiate transfers, collect cash or verify bank statements automatically.

## First use

1. Sign in with a registered platform administrator. A different registered administrator must approve the funding request.
2. Open **Deposit accounts → Balance and funding**. If prompted, **Prepare funding storage** upgrades only that bank's ownership-verified database to V7. This is also available through Prepare account storage.
3. Approve and deliver an ACTIVE customer and deposit account. The account must be Awaiting funding or Active, with no pending account/funding delivery and no expired identity document where an expiry applies.
4. In Ledger Accounts, approve and deliver a debit-normal ASSET posting ledger for the bank's received funds. Prepare a separate, unused liability ledger for each customer account, using the pinned product's liability chart mapping, the same legal entity and currency. A pooled existing deposit ledger is not suitable.
5. Choose **Record funding**. Select the receiving asset ledger and dedicated liability ledger, enter the exact amount, current business date, unique receipt reference and evidence/reason. Confirm independently that the funds actually arrived. The reference is unique across the bank's journal history.
6. Submit and review through Approval Inbox. Approval reserves the reference and account/ledger binding, but does not itself post funds. The scheduled worker or **Retry posting** completes posting. The first deposit must meet the pinned product minimum; positive subsequent deposits may be smaller.
7. Check **Balance and funding** for the posted customer balance, liability ledger balance, reconciliation result and paged history. Journals & balances and accounting reports contain the generated entries.

## Posting contract

For amount X in the account currency:

| Entry | Ledger | Amount |
| --- | --- | --- |
| Debit | Approved receiving asset ledger | X |
| Credit | Dedicated customer liability ledger | X |

The bank transaction writes the journal and both lines, updates GL turnover through the existing triggers, appends the customer funding record and updates the account to ACTIVE together. No separate commit can expose a journal without its customer entry. All amounts remain decimal strings in APIs/UI and BigDecimal/PostgreSQL NUMERIC in storage; no floating-point money arithmetic.

Posted customer balance is the sum of immutable funding amounts. It must match credit minus debit on the dedicated liability ledger. No available/withdrawable balance is advertised: holds and debits are not implemented. Account and funding records retain the approved product revision and minimum terms.

## Approval, isolation and recovery

Control V13 adds account_ledger and funding_delivery plus ACCOUNT_FUNDING approvals. Bank V7 adds account_ledger and immutable funding records, expands status to ACTIVE, and adds deferred database constraints tying each customer-ledger journal to its matching customer entry. Existing account history stays intact.

Submission and approval enforce bank version, bank/ledger/entity/currency scope, customer eligibility, reconciled balance, exact currency precision, current business date, first-deposit minimum and unique reference. Same-maker approval is forbidden. Idempotency keys serialize concurrent retries; a changed payload with an existing key conflicts. A rejected/stale request does not post funds.

The first approved funding binds an unused liability ledger permanently to the account. It cannot already have postings, be reserved for another customer, or have approved journal work pending. Later funding must use that same binding. Manual journals/reversals and ledger edits cannot bypass the customer subledger. PostgreSQL rejects a direct customer-ledger posting without the matching funding row even if service checks are bypassed.

Approval commits the funding outbox, journal projection, account update projection and audit atomically in the control database. Only the funding worker can apply the linked projections. Generic account/journal retry endpoints route funding requests to that worker. Bank receipts serialize posting by request ID. If the bank commits but control acknowledgement fails, retry acknowledges the existing receipt without crediting again. A receipt replay is acknowledged even if the customer's eligibility subsequently changes. Before first posting, current customer eligibility is rechecked.

An approved pending funding blocks further funding/account edits. Its pending journal protects the source ledger from edits and prevents business-day close. Pending funding approvals also block day close. Closed-date triggers protect posting in the bank database. Day closing checks customer subledger/GL reconciliation in addition to existing journal/GL checks.

Bank balances are authoritative. After a lost acknowledgement, the balance may include a committed deposit while the control history still says Awaiting confirmation; retry resolves that status. Do not resubmit it under a different reference.

## Lifecycle

Initial opening remains AWAITING_FUNDING; successful first funding moves to ACTIVE. Active accounts can be frozen and restored through account approval. Frozen/closed accounts cannot be funded. A funded account cannot be closed or reset to Awaiting funding in this release. An administrator cannot manually activate an unfunded account.

## APIs

Base: /api/v1/admin/tenants/{bankId}/funding

| Method | Path | Purpose |
| --- | --- | --- |
| GET | /{accountId}?limit=25&offset=0 | Authoritative balance/reconciliation, approved account and funding history |
| POST | / | Request funding; Idempotency-Key UUID required |
| POST | /{requestId}/retry | Retry or acknowledge approved funding |

Funding fields: expectedVersion, accountId, sourceLedgerId, liabilityLedgerId, amount (string), businessDate, reference, reason. Currency/entity are derived from the account, not client-selected.

## Verified scope and remaining work

89 backend tests and 26 UI tests pass. Coverage includes large exact decimal amounts, approval separation, minimum/precision/date/status validation, tenant isolation, stale decisions, duplicate references, concurrent idempotency and delivery retries, rollback of control approval, bank failure rollback, lost acknowledgement, immutable receipts, direct database bypass rejection, balance drift and day-close blockers. Browser verification uses fictional data only.

Remaining: funding reversal/correction workflow, withdrawals, holds, internal transfers, teller/cash controls, settlement/receipt reconciliation, transaction limits and bank/branch-specific posting roles. Product minimum balance is stored but not yet used for debit authorization. Selectors currently display the first 100 matching ledgers/chart definitions. Translation review, penetration/performance tests, backup/restore, monitoring and operator acceptance are required before a live pilot. No live funds or customer transactions were created during development.
