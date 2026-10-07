# Safaricom M-Pesa Kenya — sandbox payments and reconciliation

Implemented 7 October 2026 as the first external-payment increment. **Sandbox only; not approved for live banking.** No Daraja account, credentials, real payout or public callback endpoint was created during development. The provider connector has local protocol tests with a mocked transport; actual Safaricom sandbox acceptance is pending credentials and connectivity.

## Supported flow

1. Open **Payments & reconciliation** and select the configured test bank.
2. Request a B2C payout from an active, funded KES account. Supply the beneficiary name, Kenyan mobile number, whole-KES amount, configured M-Pesa asset ledger, reference and purpose.
3. A different administrator approves in **Approval Inbox**. Current customer eligibility and at least one independently cleared compliance case are mandatory. All existing case restrictions still apply. The configured bank/ledger must match the server's sandbox binding.
4. Approval queues the existing atomic account operation: debit the customer's deposit liability, credit the configured M-Pesa settlement asset, and reduce the customer balance. Bank/branch/staff **WITHDRAWAL** policies apply. This is a posted debit, not a hold. Failed posting is retried through the existing account-operation delivery mechanism.
5. After posting completes, **Submit to sandbox** claims the instruction once and sends the B2C request. The provider receives the test mobile number, amount, configured shortcode, generated payment UUID, encrypted credential and callback URLs. Customer names, case evidence and banking credentials are not included in the provider request.
6. An acknowledgement moves the order to SUBMITTED; it does not establish successful payment. A timeout or transport error leaves UNKNOWN. A process interrupted in SUBMITTING can be moved to UNKNOWN using **Refresh recovery** after two minutes. Neither state permits another B2C submission.
7. **Query provider status** sends an asynchronous Transaction Status query when the provider originator reference is available. Queries are limited to one per minute. If the original acknowledgement was lost and no reference is available, investigate through provider statement/support evidence; do not create a replacement payment.
8. Record a provider statement line or confirmed-failure support reference under **Record statement evidence**. Enter the observed amount and beneficiary exactly; mismatches are retained as evidence and cannot be approved as matches.
9. Propose a match or refund. The checker must differ from both the request maker and the evidence recorder. A match requires identical amount, beneficiary and KES currency and a PAID outcome. A refund requires FAILED evidence, the original full amount and no conflicting success observation. Provider references cannot be reused across resolved payments in the same bank, including case-only spelling differences.
10. A match sets SETTLED without another customer/GL posting. An approved refund queues an original-linked full withdrawal reversal under **WITHDRAWAL_REVERSAL** policies. Delivery uses the existing atomic journal/customer posting and receipt recovery. **Refresh recovery** acknowledges it as REFUNDED only after posting completes.

Unresolved orders block daily servicing and day close. A late success observation conflicting with a resolution flags a reconciliation conflict and blocks closing; an unposted refund with such a conflict is blocked as well. Already committed postings are not silently undone. This release has no workflow to dismiss such conflicts: investigate and implement an independently approved correction process before any production use. Never repair financial evidence with ad hoc SQL.

## State and accounting boundaries

| State | Meaning |
| --- | --- |
| APPROVED | Approved debit queued or posted; provider submission has not started |
| SUBMITTING | Durable dispatch claim recorded before the network call; never automatically resend |
| SUBMITTED | Provider accepted the request, final outcome still requires evidence |
| UNKNOWN | Acceptance/outcome is uncertain; query or investigate |
| SETTLED | An independent reviewer matched a provider evidence line to the CBS instruction |
| REFUND_PENDING | Approved failed-payment reversal is awaiting confirmed posting |
| REFUNDED | Original debit was reversed and its delivery acknowledged |

SETTLED here means an individual payout evidence match. It does **not** mean a complete provider statement, opening/closing wallet balances, fees, net settlement or bank-to-provider prefunding have been reconciled. The configured asset ledger represents test provider funds; its opening balance and prefunding must already be accounted for appropriately. The system does not move settlement funds between banks and Safaricom.

The bank receipt commits separately from control-plane acknowledgement, using the existing recoverable delivery pattern. A lost acknowledgement does not cause a second debit/refund. The network dispatch claim commits before contacting the provider; there is intentionally no automatic retry of B2C POST requests. This trades automatic recovery for prevention of duplicate external payouts.

## Server configuration

Use a dedicated bank containing fictional accounts and balances. Set these environment variables in the application process, through an appropriate secret store. `.env.example` documents names but is not automatically loaded by `scripts/run.ps1`.

| Variable | Value |
| --- | --- |
| CBS_PAYMENTS_MPESA_ENABLED | true only when ready for sandbox testing; absent/false disables submission |
| CBS_PAYMENTS_MPESA_BANK_ID | UUID of the dedicated test bank |
| CBS_PAYMENTS_MPESA_SETTLEMENT_LEDGER_ID | UUID of the active, delivered KES debit-normal asset ledger in that bank/entity |
| CBS_PAYMENTS_MPESA_SHORTCODE | Sandbox shortcode from Daraja |
| CBS_PAYMENTS_MPESA_CONSUMER_KEY | Sandbox application consumer key |
| CBS_PAYMENTS_MPESA_CONSUMER_SECRET | Sandbox application consumer secret |
| CBS_PAYMENTS_MPESA_INITIATOR | Sandbox B2C API initiator name |
| CBS_PAYMENTS_MPESA_SECURITY_CREDENTIAL | Encrypted security credential produced using the provider's sandbox certificate/procedure; not the plain initiator password |
| CBS_PAYMENTS_MPESA_CALLBACK_BASE | HTTPS origin only, e.g. https://your-approved-sandbox-host.example; no userinfo, query or fragment |

The connector is disabled under the Spring **production** profile regardless of these settings. It always calls `https://sandbox.safaricom.co.ke`; there is no configurable/live provider base URL. Redirects are disabled; connect/request timeouts are bounded. The provider profile is pinned to bank, ledger, shortcode and consumer key so an approved instruction cannot be dispatched under another configured account.

The current implementation uses OAuth, `/mpesa/b2c/v1/paymentrequest` with BusinessPayment, and `/mpesa/transactionstatus/v1/query`. Confirm the enabled B2C API version and payload in the bank's actual Daraja sandbox application during contract testing. No live API compatibility or certification is claimed.

Configure HTTPS ingress specifically for the callback path and restrict the administration portal separately. The current development app binds to loopback and is not a public callback service. TLS ingress/network deployment is a separate task. Do not expose the entire local administration app or change MFA merely to test a callback.

## Callback security

Callbacks use `POST /api/v1/payments/mpesa/callback/{paymentId}/{randomToken}`. A separate random 256-bit token is generated for payout submission and status query; only its SHA-256 hash is stored. The token, configured bank, bounded payload and required identifiers are checked. Duplicate observations are deduplicated and each payment is limited to 100 distinct observations. Stored fields exclude provider free-text descriptions and unrelated customer data.

The token is a correlation capability, **not a Safaricom digital signature**. A callback is explicitly unverified evidence. It cannot settle a payment, refund money or change posted balances. No claim is made that Safaricom signs these callbacks. Production requires bank/provider-approved ingress authentication, request-size/rate controls and a verified reconciliation source. Redact callback tokens from reverse-proxy access logs, telemetry and support exports. A new status query rotates its query token, so a callback from an older query may be rejected; check provider evidence if that occurs.

## APIs and storage

Admin base: `/api/v1/admin/tenants/{bank}/payments`. All admin routes retain active-staff/MFA/platform-admin checks; assigned accounts additionally enforce existing branch authorization at submission/approval and dispatch. This release does not yet provide a payment-specific bank officer role or separate payment limit category.

| Method/path | Purpose |
| --- | --- |
| GET base, limit/offset | Paginated masked payment directory and sandbox setup status |
| GET `/{id}` | Audited detail, latest 100 events/observations/statement records |
| POST base | Submit PAYMENT_ORDER approval; UUID Idempotency-Key required |
| POST `/{id}/dispatch` | Single sandbox submission after accounting confirmation |
| POST `/{id}/query` | Asynchronous provider status request; no B2C resend |
| POST `/{id}/refresh` | Acknowledge posted refund or recover interrupted dispatch as UNKNOWN |
| POST `/statements` | Record immutable statement evidence; UUID Idempotency-Key required |
| POST `/reconciliation` | Submit PAYMENT_RECONCILE approval; UUID Idempotency-Key required |

Control migration **V25** adds payment orders, events, callback observations, statement evidence and resolutions. Instructions/evidence are immutable; deferred checks require matching approved accounting and reconciliation records. Bank migration remains V14; posting reuses existing bank receipts. Existing V24 and earlier migrations were not edited.

Statement evidence can be entered per payment or imported through the [full normalized CSV reconciliation workflow](mpesa-statement-reconciliation.md). That workflow adds unknown external rows, wallet balances, fees, corrections and closure. An actual provider-native export format has not yet been validated. Evidence references point to an external bank-approved repository; no document is uploaded or authenticated by this module. The decision is a manual attestation by two different administrators, not automatic proof supplied by a callback.

## Remaining release work

- Actual Daraja sandbox credentials, HTTPS ingress and provider contract tests, followed by production onboarding/certification.
- Provider-native export validation, timing/split/net settlement and production-scale automated statement acquisition. Normalized CSV imports, unmatched rows, wallet/fee reconciliation and controlled corrections are implemented in V26.
- Verified beneficiary checks, sanctions screening of external beneficiaries, current provider amount/velocity rules, payment-specific limits and bank-scoped payment roles.
- Cancellation before debit, insufficient-wallet handling, failed-accounting abandonment, partial refunds, provider reversal/recall and returned-payment lifecycles.
- C2B collections, STK Push, B2B and additional providers/markets; this release is B2C sandbox only.
- Recipient-data encryption/retention, rate-limited ingress, queue/exception alerts, operational SLAs, production-scale concurrency/load and disaster-recovery acceptance.
- Full translation/accessibility, searchable selectors beyond the first 100 accounts/ledgers and complete historical evidence pagination.

## Provider references

- [Safaricom Daraja API catalogue](https://developer.safaricom.co.ke/apis) identifies B2C as the disbursement product.
- [Safaricom Transaction Status](https://developer.safaricom.co.ke/apis/TransactionStatus) documents asynchronous status queries and provider reconciliation identifiers.
- [Safaricom's published Node integration library](https://github.com/safaricom/mpesa-node-library) provides B2C integration examples. It is a reference only; this Java application does not install that library.

See verification.md for the local test evidence and enterprise-readiness-review-2026-10-07.md for broader production gaps.
