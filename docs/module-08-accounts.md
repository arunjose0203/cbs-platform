<!-- Historical opening increment; current funding capability is documented in module-08-funding.md. -->
# Module 08.1 — Deposit account opening

Implemented on 6 October 2026. **Module 08.2 now adds funding and balances; see [funding guide](module-08-funding.md).** This increment creates approved, unfunded savings/current account records. It does not accept or move money.

## First use

1. Sign in as a registered platform administrator. Use a second administrator for approval.
2. Select an active bank in **Deposit accounts** and choose **Prepare account storage** if prompted. This upgrades only its configured, ownership-verified database through bank migration V6.
3. Prepare customer storage, approve an ACTIVE individual with a manual review reference and valid document expiry, and complete customer delivery. Approve an active deposit product with enabled currency and liability chart mapping.
4. Choose **Open deposit account**; select the customer, product and legal entity, enter an account name and reason, then submit.
5. Another administrator reviews the customer number, entity, currency, product revision and minimum terms in Approval Inbox. Approval creates a durable delivery request; successful bank delivery creates the account.
6. Refresh to see **Applied** and **Awaiting funding**. Failed delivery can be retried manually; the scheduled worker also retries. Manage allows name changes, freeze, return to awaiting funding, and irreversible closure of this unfunded record. Closed records remain visible and cannot be reopened.

## Rules and boundaries

- Unique immutable account number: A followed by the uppercase UUID without separators. This is an internal CBS identifier, not an IBAN or country-specific domestic account-number format.
- One individual, legal entity and currency per account. Each account pins the full approved product definition and revision at opening. Subsequent product edits/deactivation do not rewrite existing account terms. Different accounts may use different enabled currencies; no FX conversion.
- Opening requires ACTIVE bank, APPLIED and ACTIVE customer, unexpired document if an expiry exists, active product at its current revision, enabled currency and active liability mapping. Rechecked at submission and approval. Authoritative customer eligibility is checked again during first bank delivery; a receipt replay remains safe after later customer changes.
- Customer/product/entity/revision cannot be changed after approval. Freeze and close remain possible if the customer becomes blocked. Returning to awaiting funding requires current customer eligibility. Closed is terminal.
- No monetary postings, balance field, customer ledger, overdraft, joint holders, holds, interest or fees exist in this increment. Minimum opening/balance amounts are preserved contractual terms; no claim is made that funds satisfy them. A future funding transaction must enforce the pinned opening minimum and create balanced journal/subledger records before marking an account operational.
- Account IDs are absent from the ledger registry and cannot receive manual journal postings. Closing here only applies to these unfunded opening records. Before introducing funded states, closure must check balances, holds and unsettled work atomically.
- Nonclosed accounts, including pending deliveries, prevent disabling their currency or chart mapping even after product deactivation. Bank version checks make competing approved configuration changes stale. Account changes pause while an approved day close is unfinished; unfunded records have no accounting-date effect.
- Platform administrators only in this increment. Bank/branch operator permissions and transaction limits remain future work. Customer blocking does not automatically freeze existing account records; the future transaction service must enforce both statuses on every monetary operation.
- Selectors currently show the first 100 active customers/products and banks; API lists support pagination. Full translation/content review remains required before a pilot.

## Storage and recovery

Control migration V12 creates platform.account_registry and platform.account_delivery and adds DEPOSIT_ACCOUNT to approvals. Bank migration V6 creates bank.account and append-only bank.account_event. Product revision foreign keys prevent orphaned contracts. Control approval, registry, outbox and audit share one transaction. Bank delivery writes account and receipt in one transaction under an idempotency lock. A failed control acknowledgement replays the same immutable receipt; no duplicate account is created. Updates require the expected prior account version. Reads of applied detail reconcile the bank record against its control definition and audit access.

Customer/customer-document metadata is not copied into account terms: linkage uses customer ID and number. Account names may contain personal information and are retained in approval/delivery history; encryption, retention policies and operational access review remain part of production readiness.

## API

All paths below start with /api/v1/admin/tenants/{bankId}/accounts.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | / | Paged directory; search, state, limit, offset |
| GET | /{id} | Reconciled detail and pinned terms |
| POST | / | Submit opening or status/name change; Idempotency-Key required |
| POST | /prepare | Apply owned bank migrations; expectedVersion and reason |
| POST | /{requestId}/retry | Retry approved delivery |

Change fields: expectedVersion, accountId (null for opening), customerId, productId, productRevision, legalEntityId, name, state, reason. States: AWAITING_FUNDING, FROZEN, CLOSED. The same idempotency key/payload returns the existing request; changed payload requires a new key. Same-maker approval is forbidden. Stale approvals never deliver.

## Verification

Full local suite: 85 backend tests, zero failures/skips; 23 UI tests passed. Account tests cover HTTP permissions/validation, maker-checker separation, stale requests, customer eligibility/expiry, product deactivation, pinned terms, mapping protection, immutable structure, terminal closure, bank isolation, no journal effects, concurrent retries, approval rollback, bank commit/control rollback recovery, and immutable receipts. Added guard assertions were verified by rerunning the three account integration tests. Browser fixture checked opening submission, approval comparison and details using fictional data only.

Next increment: Module 08.2 funding and customer subledger integrity, then Module 09 transfers and Module 10 teller/cash. Do not treat this account-opening increment as production-ready deposit processing.
