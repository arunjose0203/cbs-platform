# Statements and customer alerts — 11 November 2026

This change follows the [chunked daily processing](servicing-chunked-batches.md) work. It covers matrix row F22. Bank acceptance remains pending.

## Statements

| Before | Now |
| --- | --- |
| CSV export refused above **10,000** entries | CSV **streams** through a database cursor with no entry cap and constant memory. The date range limit (367 days) is unchanged |
| No PDF | **PDF statement** (A4): bank and legal-entity header, account and customer details, period, opening and closing balances, total debits and credits, entry count, a running-balance table, and "Page n of N" with the generation time on every page |
| — | Statement labels in **English, French and Swahili**. The `language` parameter is optional and defaults to the bank's default language |

**Endpoints:**
- `GET …/accounts/{id}/statement/export?from&to` returns `text/csv`
- `GET …/accounts/{id}/statement/pdf?from&to[&language=en|fr|sw]` returns `application/pdf`

Both are administrator-only, set `Cache-Control: no-store` and read one repeatable-read snapshot. Each export is audited as `ACCOUNT_STATEMENT_EXPORTED` with the format and entry count. In the portal, the statement page has **Download PDF** next to **Export CSV**.

**How the PDF handles amounts and text:**
- Amounts are **never truncated**. If a value is unusually long, its font shrinks instead.
- Long narrations are shortened with "…".
- The built-in PDF fonts cover English, French and Swahili text. Other scripts in narrations, such as Chinese or Arabic, print as "?". A bank needing them should supply an embedded Unicode font.
- The PDF is built in full before any bytes are sent, so a failure never delivers a truncated file. Pages are buffered in a temporary file, not in memory. CSV starts streaming immediately: an error in the middle of a large export ends the download early, and the audit record is written only when the export completes.

## Customer alerts

**Events:**
- A **credit alert** is sent for funding, withdrawal reversals and incoming transfers.
- A **debit alert** is sent for withdrawals, funding reversals, outgoing transfers (including transfer reversals) and final closure settlements.
- Holds and releases don't move money, so they send no alert.
- Not yet covered: cash-desk postings, daily interest, term-deposit and loan events, and low-balance alerts. They need separate hooks.

**Wording.** Templates are built in, in English, French and Swahili. An alert gives:
- the bank name
- the amount and currency
- the last 4 digits of the account
- the business date and the reference
- for debits, "Not you? Call your branch"

Alerts **never** include balances, full account numbers or names, because SMS messages and lock screens are not private. Inserted values are stripped of control characters and capped in length. Bank-specific wording would need an approved template workflow, which isn't built yet.

**Preferences.** A preference sets the channel (SMS, email or none), the destination, the language, which events to send, and a customer consent reference. Changes go through the Approval Inbox (`NOTIFICATION_PREFERENCE`) and need independent approval, because redirecting a customer's alerts is a common fraud step. Validation rules:
- SMS numbers must be in international format (`+254…`).
- Email addresses must be valid.
- With "none", no destination is accepted.

**Reliable outbox.** An alert is written in the same control-database transaction that marks the posting complete, so it exists if and only if the posting completed. Retries of a posting never duplicate it: each alert has a unique key per event, source and account.

**Delivery:**
- A background worker sends pending alerts every 15 seconds through the configured `NotificationSender`.
- A failed alert is retried up to 5 times and then marked `FAILED`. An administrator can queue it again with **Send again**, which is audited.
- **No real provider is connected yet.** With `cbs.notifications.sender` unset, alerts stay queued and the page says no provider is configured.
- `cbs.notifications.sender=test` enables a development sender that delivers nothing. The production guard refuses to start with it.
- Connecting a provider, such as Africa's Talking or Safaricom for SMS and an email service, means implementing `NotificationSender` with the provider's credentials and delivery receipts.

**Portal page: "Customer alerts".** It shows the alert log with status filters, masked destinations, attempts, failure reasons and **Send again** for failed alerts. **Alert preference** submits a change for approval.

**API:**
- `GET …/notifications?status&customerId&limit&offset`
- `GET …/notifications/preferences/{customerId}`
- `POST …/notifications/preferences`, which goes to approval
- `POST …/notifications/{id}/retry`

**Database:** control **V40** adds the `NOTIFICATION_PREFERENCE` approval action, `platform.notification_preference` and the `platform.notification` outbox. The bank schema is unchanged.

## Verification

- `statementsStreamAsCsvAndPdfAndAlertsFollowApprovedPreferences` checks:
  - no alerts without a preference
  - invalid destinations rejected
  - self-approval denied
  - an approved Swahili SMS preference
  - exactly one credit and one debit alert for real postings, and none for a hold
  - no balance or full account number in any alert
  - delivery through the test sender, with masking in lists and the API
  - a failing destination retried 5 times, marked failed and queued again
  - CSV line count and closing balance
  - a French PDF parsed back with its account number, exact closing balance and page footer
  - language and date-range rejections
  - audit records showing the format
- `StatementPdfTest` checks pagination, exact long amounts, totals, all three languages, the empty-period message and unsupported-character handling. One rendered page was also inspected visually.
- `ProductionGuardTest` checks that the test sender is refused in production.
- UI tests cover the alert log (escaping, retry, provider warning) and the preference approval submission.
- A statement above 10,000 entries was **not** generated in tests, because each entry needs an approved posting. The export path no longer has a cap, and it streams through a cursor.
