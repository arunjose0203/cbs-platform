# Payment returns, reversals and settlement exceptions

Implemented development increment, 7 October 2026. This extends normalized M-Pesa statement reconciliation; it is not provider certification or production acceptance.

## Operator workflow

1. A platform administrator opens **Payment exceptions → Bank permissions** and proposes a bank-scoped staff grant. Another platform administrator approves it in the platform Approval Inbox.
2. A **RECONCILER** or **SUPERVISOR** imports verified normalized statement evidence in **M-Pesa statements**. An **AUDITOR** can read and export only. The payment workspace requires recent MFA and active CBS staff identity.
3. Open an investigation in **Payment exceptions**, selecting the payment, exception type and provider statement. Return/reversal investigations also select an unresolved provider credit row. Record an evidence repository reference and review deadline; do not paste secrets or unredacted provider files into notes.
4. Add append-only notes and propose owner/deadline changes. Use **Approvals** within this bank workspace for statement, reconciliation and case decisions. The requester cannot approve their own request. The provider statement importer and case opener cannot approve the corresponding financial return. The evidence recorder cannot approve a failed-payment refund.
5. Close statements and run business-day close only after all readiness checks pass.

Payout creation/dispatch remains a platform administrator operation in the configured fictional sandbox bank. Payment role grants do not confer general customer administration or cash-teller privileges. Inbound failed-payment refunds and verified returns use the dedicated bank payment-supervisor permission, with original-payment, customer eligibility, exact amount, accounting and mandatory bank transaction-limit checks preserved. Branch cash authorization does not authorize these provider-return credits.

## Full returns and provider reversals

A provider reversal observation does not prove receipt of funds and does not move customer money. This increment records confirmed provider returns; it does **not** initiate a remote Daraja reversal request.

A return requires an already settled original payment, an open investigation and a matching unresolved `REVERSAL` credit row. The credit must equal the original amount, identify the original payment reference, use the original wallet, and be dated on the current bank business date. A supplied beneficiary must also match. Reusing the original payout receipt is rejected.

Independent approval creates one original-linked `WITHDRAWAL_REVERSAL`. The original payout remains immutable. The payment becomes `RETURN_PENDING`, then `RETURNED` after the customer credit and balanced bank journal are confirmed. The return debits the M-Pesa asset wallet and credits the customer's deposit liability. Posting uses the existing bank receipt for exactly-once financial effects across retries and lost control-database acknowledgements. The investigation becomes resolved only after posting confirmation.

A contradictory callback blocks an unposted return. A separately approved statement conflict review, tied to the previously approved return evidence, can clear it. A callback never initiates the credit. Partial returns, frozen/ineligible-account exceptions, suspense routing and remote reversal requests remain pending scope; they must not be converted into a generic customer or fee journal to bypass validation.

## Settlement carry-forwards

`SETTLEMENT_DELAY` investigations can carry a known, posted `SUBMITTED` or `UNKNOWN` payout absent from the linked current-day statement. A supervisor independently approves the case and due date. Carrying posts no journal and does not assert provider settlement.

For a payout of 100, the CBS wallet is 100 below the provider wallet until the provider settles it. The report retains the raw difference of -100 and separately shows the approved timing difference of -100, leaving unexplained difference zero. The case remains dated and visible across business days. Its original payment journal remains accounted for in the original statement.

On settlement, the next statement's payout is matched through independent approval. Resolve the timing case using verified settlement evidence; its end date comes from the provider settlement date, or the completed failure-refund posting date. Historical reports retain the carry as of their own period. A resolution that would change an already closed reconciliation period is rejected until that period is reopened for review.

Daily close rejects open investigations, pending returns and overdue carries. An approved carry is eligible only if its review deadline covers the next bank business date. The dashboard flags overdue cases against the later of business date and current UTC date. These are in-application alerts; outbound email/SMS escalation is not implemented. Deadlines are explicitly entered by bank staff; the system does not invent provider SLAs.

## Storage and recovery

Control database migration **V27** adds payment permissions, investigations, immutable case events, approval scope and return-state evidence. Existing bank **V14** storage handles original-linked customer returns. No bank schema change is needed. Database constraints protect case transitions, approval evidence, original instructions and completed return receipts. Report hashes and bank/case versions prevent approving changed evidence.

The payment workspace API is `/api/v1/banks/{bank}/payment-operations`; every route checks bank permissions server-side. Case/statement directories paginate. Selection lists currently show the first 100 eligible records, and approval review shows the latest 25 requests. Large-bank search and an overdue notification service remain improvements.

## Verification and external acceptance

Local evidence: 120 backend regression tests passed; the final focused lifecycle test additionally checks scoped lookup routes and partial-return rejection. 65 UI tests passed. A fictional browser fixture verified the investigation list, history and return form. Tests exercise carry through day close, later provider settlement, independent full return, exactly-once recovery, conflicting callbacks, read-only/cross-bank denial, revoked-maker approval denial and immutable case history. These are local automated tests, not live Daraja acceptance.

Before provider acceptance:

- Obtain a redacted export from the actual contracted M-Pesa product. Validate encoding, delimiter, headers, dates/timezone, debit/credit direction, separate fees, running balance, reversal linkage and statement boundaries. Preserve the original in the bank evidence repository. A configurable normalized CSV parser is not evidence that every native Safaricom export is supported.
- Configure the bank-approved Daraja sandbox, wallet binding and HTTPS callback using the server's secret configuration. Never put credentials in chat or repository files.
- Execute the provider-supported success, confirmed failure, timeout/status inquiry, duplicate/out-of-order callback, failed-payment refund and provider reversal scenarios. Where the sandbox cannot produce settlement exports or a scenario, obtain provider-approved evidence or a controlled certification exercise; record that limitation explicitly.
- Reconcile customer, bank GL and provider wallet balances; record provider references, approval identities, business dates, expected/actual results and recovery evidence. Get bank finance/operations sign-off.

No actual statement sample or sandbox acceptance evidence was supplied for this increment. Production dispatch remains disabled. FX & Exchange Rate Management, Term Deposits and Lending follow this payment increment in the roadmap; their accounting/product acceptance is separate.
