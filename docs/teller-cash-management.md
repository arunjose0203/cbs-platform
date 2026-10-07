# Teller and cash custody

Implemented on 2026-10-07. This is a working cash custody and customer-cash increment, not a declaration that all teller operations or production acceptance are complete.

## Operator setup

1. Prepare the bank database using Bank directory → Manage bank → Cash locations → Prepare cash storage (or the existing authenticated ledger-accounts/prepare API). Control schema is V21; bank schema is V12. The preparation service verifies database ownership before migration.
2. Create dedicated, unused, debit-normal asset ledgers for each currency's vault and tills. A cash location cannot adopt a ledger with historical or queued journal postings or a customer binding.
3. In Bank directory → Manage bank → Cash locations, submit each vault/till registration. An independent platform administrator approves it in Approval Inbox. The delivery worker creates the bank-side location at zero. No opening cash is invented.
4. Assign bank operator and branch TELLER/BRANCH_MANAGER roles. Assign customer accounts to the same branch and legal entity through approval.
5. Approve actual bank, branch and individual staff limits for CASH_RECEIVE, CASH_DISPATCH, CASH_TRANSFER, CASH_DEPOSIT and CASH_WITHDRAWAL; adjustment managers also need CASH_SHORTAGE and CASH_OVERAGE policies. Customer deposits additionally require the bank FUNDING cap; withdrawals additionally require bank, branch and staff WITHDRAWAL caps. No production financial values are supplied by the application.
6. In Branch workspace → Teller & cash custody, record verified external cash receipts into the vault against the approved external settlement asset ledger. An independent branch manager reviews the evidence.
7. A teller submits a counted till opening. After independent approval and posting, that teller can request vault/till transfers and customer cash transactions. Deposit funds increase both the till and customer liability; withdrawals decrease both. The teller is the session custodian.
8. Submit a physical closing count. A mismatch blocks closure; it never silently posts an adjustment. Managers can submit daily counts for vaults and closed tills, also requiring independent review. Day close requires every cash location to have an approved count for the current business date after its last movement and all tills closed.

A first customer cash deposit requires the customer's dedicated approved liability ledger. Later deposits use its existing binding. Customer eligibility, opening minimum, account state, holds, minimum balance, currency and entity rules remain enforced.

## Accounting and delivery

- RECEIVE: debit vault cash, credit external settlement asset.
- DISPATCH: debit external settlement asset, credit vault cash.
- TRANSFER: debit destination cash, credit source cash. Same branch/entity/currency; direct till-to-till transfers are unavailable.
- DEPOSIT: debit till cash, credit customer liability, update funding receipt and account activation together.
- WITHDRAWAL: debit customer liability, credit till cash, update customer operation and cash receipt together.
- OPEN, CLOSE and COUNT store immutable counted evidence without financial journal entries.

The cash receipt, cash entries, customer receipt/entries/account activation where applicable, journal and ledger balances commit in one bank-database transaction. Approved control jobs are durable, and generic journal/funding/operation workers cannot independently post cash-owned instructions. Lost control acknowledgement retries find the original bank receipt rather than posting again. Database constraints require matching cash and customer receipts and reject negative or unreconciled cash balances. Manual journals, customer funding and external-payout routes cannot bypass the cash subledger on a dedicated cash ledger.

Approval rechecks current maker and reviewer permissions and the bank version. Makers cannot approve their own requests. Vault receipts, dispatches and independent daily counts require managers; operational tellers have scoped customer/till capabilities. Branch auditors are read-only. All branch operational endpoints use the existing recent-MFA requirement. Posting a previously approved instruction does not impersonate a newly signed-in teller.

All financial amounts remain decimal strings/BigDecimal. Count quantities are bounded whole numbers. Duplicate denominations, invalid precision, negative counts and overflow are rejected. Counts accept currency-precision denomination values; an approved physical denomination catalogue and note/coin inventory tracking are still pending.

## API

Administrative registration:

- POST /api/v1/admin/tenants/{bankId}/cash-locations, kind REGISTER, with Idempotency-Key.

Branch workspace:

- GET /api/v1/banks/{bankId}/branches/{branchId}/operations/cash?limit=25&offset=0
- POST the same base path with Idempotency-Key and a cash change.
- GET base/{requestId} for review detail.
- POST base/{requestId}/approve, /reject or /cancel with a reason.

Change fields: expectedVersion, kind, branchId, locationId, destinationId, ledgerId, code, name, currency, type, amount, businessDate, reference, counts, reason, accountId, liabilityLedgerId. Fields irrelevant to the selected operation must be absent/null. Counts are arrays of {denomination: decimal string, quantity: integer}. Customer cash operations require accountId and a till; DEPOSIT also requires liabilityLedgerId. Backend checks account ownership rather than trusting selector choices.

Dashboard location pagination is 25 by default, at most 100. Recent requests/deliveries show the latest 25; customer selectors currently show the first 100 eligible assigned accounts. Historical search/export expansion is pending.

## Validation and remaining work

Tests cover maker/checker separation, branch access, required limits, exact precision including RWF/UGX/KES, session custody, insufficient cash/customer balance, mismatch blocking, daily count invalidation, transaction rollback, lost acknowledgement and repeat delivery, immutable entries, and direct-journal bypass rejection. Tests run against isolated local PostgreSQL; browser preview uses fictional data without a database connection.

Still pending in this stage: investigation assignment/escalation and suspense/recovery accounting policies; adjustment reversal and case reopening; cash-in-transit and inter-branch custody; note/coin stock inventory and denomination catalogue approval; teller receipt printing; richer account/history searches; emergency custody reassignment; complete translations; and bank acceptance. An unresolved physical loss or gain blocks the location and day close until an independently approved exact adjustment posts; verified counting errors can be resolved by independent recount. Existing staff grants also still need the planned independent administration approval workflow.

No payment provider, financial limit, tax rate, regulatory approval, MFA exception or live financial transaction was invented or enabled by this implementation.

## Cash exception cases (7 October 2026)

Control migration V18 adds immutable discrepancy evidence and immutable independent recount resolutions. It does not change bank schema V9 or post any accounting entries.

In Branch workspace → Teller & cash custody, Report cash difference records denomination counts, the current book amount, currency, business date, reporter and investigation evidence. A nonzero difference immediately freezes both incoming and outgoing cash operations for that location and blocks day close. Reporting is allowed to assigned operational tellers and managers. An independent branch manager can verify a corrected count only when the physical total equals the current reconciled cash/GL balance. The reporter cannot resolve their own case. Unresolved losses/gains remain frozen until the approved accounting adjustment described below posts.

Reports and resolutions lock the bank row, require the current bank version and business date, and reject pending cash deliveries. Existing pending cash requests become stale when a case is reported or resolved. Exact replays do not duplicate evidence or audit events. A resolution does not close a till, change balances, or replace the existing daily count process. Evidence cannot be updated or deleted.

Routes under the cash base path:

- GET /exceptions: latest 100 cases, open cases first, restricted to the current bank, branch and issuer.
- POST /exceptions: Idempotency-Key plus expectedVersion, locationId, businessDate, counts and reason.
- POST /exceptions/{id}/resolve: same evidence fields; independent manager only, safely replayable.

The list currently has a 100-case display cap; full historical case search and pagination remain pending. Server-side location freezes and day-close checks cover all open cases regardless of that display cap.

## Approved shortage and overage adjustments

Control migration V19 reserves each approved case to one immutable adjustment request and links the final resolution to the posted cash delivery. Bank migration V10 adds a unique case reference to the immutable receipt and validates the resulting physical count. Existing banks must use Bank profile → Cash locations → Prepare cash storage before operating with the new cash schema. No existing applied migration is rewritten.

1. A branch manager opens an unresolved exception and selects Request adjustment.
2. The amount and direction come from the exact original discrepancy. The manager confirms denominations, selects an approved ledger and supplies a reference and justification.
3. SHORTAGE debits an active debit-normal expense ledger and credits cash. OVERAGE debits cash and credits an active credit-normal income ledger. Both ledgers must belong to the same bank, legal entity and currency. The selector shows the first 100 eligible ledgers; the server validates every submitted ledger independently.
4. Bank, branch and requesting staff limits for CASH_SHORTAGE or CASH_OVERAGE must already be approved. Missing policies block submission; approved pending deliveries count toward usage. No real limits or accounting mappings are seeded.
5. Another branch manager reviews the immutable request. Both the requester and the original discrepancy reporter are excluded from approving it. Current permissions, business date, bank version, cash balance, count and case state are rechecked.
6. The worker posts the balanced journal, signed cash entry and unique case receipt in one bank transaction. Only after successful posting does the control transaction resolve the case. Failures retain the freeze and retry safely; a lost control acknowledgement reuses the original receipt instead of posting twice.
7. The adjustment invalidates the daily cash count. The ordinary approved count/till-close process must still be completed before day close. Customer balances are not adjusted by a cash shortage/overage journal.

These changes extend the existing CASH_OPERATION approval action with SHORTAGE and OVERAGE kinds and exceptionId. The existing cash POST endpoint accepts them. GET cash/adjustment-ledgers?locationId={id} supplies branch-scoped ledger choices; exception lists expose pending_adjustment and adjustment_request_id so the UI distinguishes an approved queued adjustment from a posted resolution. Null exceptionId is omitted from serialization to retain compatibility with existing cash request and receipt payloads.

This version supports direct expense/income recognition only. Bank-approved suspense, employee recovery, insurance recovery and later reclassification policies are separate future work; the application does not decide those accounting policies. Cash/customer transaction reversals are documented in transaction-reversals.md. Adjustment reversals remain a separate case-reopening workflow.

## Cash/customer transaction reversal increment

See [Transaction reversals](transaction-reversals.md) for supported original transactions, approvals, limits, atomic posting, recovery and operator setup. Current bank storage is V11 and control storage V20.

## Inter-branch shipments

Vault dispatch, partial destination receipts and physical returns now use linked cash-in-transit accounting. See [Inter-branch cash](interbranch-cash.md) for configuration, approval, limits and remaining scope.
