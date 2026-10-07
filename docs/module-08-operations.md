# Account operations and customer statements — 7 October 2026

## Delivered scope

Module 08.3 adds approved holds/releases, recorded withdrawals and full received-deposit reversals. Module 09.1 adds same-bank, same-legal-entity, same-currency transfers between active, funded accounts. Module 13.1 adds read-only customer statements. These are initial implementations, not production acceptance of the complete roadmap.

In Deposit accounts, use **Account operations** or **Statement**. Prepare account storage first when prompted. A second registered administrator must approve each operation. The worker delivers approved operations, or an operator can retry from the history. Never recreate an uncertain posting using a new reference: retry the original request.

A withdrawal records an independently verified payout; it does not initiate an external transfer or manage a cash till. Internal transfers do change both bank subledgers atomically. Test with fictional records before any operational use.

## Money and posting rules

- Posted balance = immutable received-deposit credits plus immutable signed customer entries.
- Held amount = approved, delivered holds without an approved, delivered full release.
- Available balance = posted balance minus held amount.
- Withdrawable = max(available balance minus the pinned product minimum, zero).
- Withdrawal: debit customer liability, credit approved asset ledger.
- Internal transfer: debit source customer liability, credit destination customer liability.
- Funding reversal: exact opposite of the complete original funding journal, on the current business date; no editing of historical entries.
- Hold/release: no GL movement. Releases must match the original hold and its full amount.
- Every debit, including a funding reversal, protects active holds and the product minimum. No overdrafts or negative customer balances.
- Transfers require both accounts to be ACTIVE, delivered, eligible and reconciled. Releases remain possible for frozen accounts.

Control database V14 records approval-driven operations. Bank database V8 stores immutable operation receipts/customer entries and a customer-position view. Bank posting locks affected accounts in a deterministic order. Journal, customer entries and receipt commit together. Deferred database constraints require every customer journal line to match its subledger receipt and prevent negative or over-held balances.

Approved pending operations block conflicting account/funding changes and business-day closing. Generic journal delivery cannot dispatch operation journals independently. Lost control acknowledgements are recovered from immutable bank receipts; retries do not post twice. Holds are not effective until bank delivery is confirmed.

## API

All routes below require an active platform administrator and normal session/MFA checks:

- GET /api/v1/admin/tenants/{bank}/account-operations/{account}?limit=25&offset=0
- POST /api/v1/admin/tenants/{bank}/account-operations with an Idempotency-Key UUID
- POST /api/v1/admin/tenants/{bank}/account-operations/{request}/retry
- GET /api/v1/admin/tenants/{bank}/accounts/{account}/statement?from=YYYY-MM-DD&to=YYYY-MM-DD&limit=25&offset=0

Operation body: expectedVersion, kind (TRANSFER, WITHDRAWAL, FUNDING_REVERSAL, HOLD, RELEASE), accountId, amount as a decimal string, businessDate, reference, reason; destinationId only for transfers, assetLedgerId only for withdrawals, originalId only for funding reversal or hold release. Unknown and contradictory fields are rejected.

Statement balances use business dates and a repeatable-read snapshot. Opening and closing balances cover the full selected interval; paginated running balances include preceding rows. Maximum interval: 367 inclusive calendar days. Holds and unposted requests are excluded. Statement access is audited. This is an administration statement view, not a customer-delivery channel or statutory statement format.

## Remaining work

Branch and bank posting permissions, amount-based approval limits, transfer/withdrawal correction workflows, partial/expiring holds, account closure, teller liquidity controls, cross-entity and cross-currency transfers, statement export/delivery and regulatory acceptance remain outstanding. UI selectors are bounded to the first 100 matches. Some labels have French/Swahili translations; full localization review remains required.
