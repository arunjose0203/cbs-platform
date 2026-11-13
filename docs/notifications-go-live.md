# Round 10: customer notifications go-live

Added 13 November 2026 (control migration V51). Covers functional areas F22 (notifications), F28 (term-deposit notices) and F32 (borrower notices), as planned in [go-live-plan-2026-11-13.md](go-live-plan-2026-11-13.md). Builds on the alert outbox from [statements and alerts](statements-and-alerts.md).

**Not done here: the real SMS/email provider adapter.** The provider (decision D09) is not chosen yet. Everything around it is in place: the sender interface with a per-bank binding, signed delivery receipts, opt-outs, retries, monitoring. The adapter is a single `NotificationSender` implementation added once the contract is signed. The test sender stays refused in production.

## What it adds

| Feature | Mechanism |
| --- | --- |
| **Destination verification** | An approved preference with a new phone number or email gets a one-time six-digit code. Alerts and notices start only once staff enter the code the customer reads back (`POST …/notifications/preferences/{customer}/verify`). The code is kept as a SHA-256 hash, expires after 24 hours and locks after five wrong attempts. It can be resent once a minute. Lists never show the code; the message text is replaced once sent. Re-approving the same destination keeps it verified. Preferences approved before V51 count as verified, because the approver re-entered the destination. |
| **Approved message templates** | Bank wording per event, language and channel goes through maker-checker (`NOTIFICATION_TEMPLATE`, bank-configuration duty). A template may use only its event's placeholders and must contain `{bank}` (and `{code}` for verification). SMS wording must fit two segments (306 characters) with the longest values filled in. Email needs a subject. The preview shows the current wording (approved or built-in) and a filled example. Without an approved template, built-in English, French and Kiswahili wording is used. |
| **Scheduled notices** | A background scan (every 15 minutes, idempotent) queues three kinds of notice. **Term-deposit maturity:** up to 7 days before maturity. **Loan instalment due:** up to 3 days before. **Loan arrears:** when posted arrears cross 1, 30, 60 or 90 days past due, once per threshold per arrears episode. Notices go to every holder who opted in to that event on a verified destination. Messages carry the agreement reference and dates, never balances or amounts owed. The scan never changes the bank version or any financial record. |
| **Opt-outs** | A STOP reported by the provider, or an opt-out recorded at the branch, adds the destination to `platform.notification_opt_out`. Queued and future messages to it are marked `SUPPRESSED` and never sent. Messages resume only when staff remove the opt-out with the reference of the customer's renewed consent. Both actions are audited, with the destination masked. |
| **Delivery receipts** | `POST /api/v1/notifications/receipts/{provider}` is public on purpose and on the declared allowlist. Each request needs `X-CBS-Timestamp` (within 5 minutes) and `X-CBS-Signature: sha256=<hex>`, an HMAC-SHA256 of `timestamp.body` with `cbs.notifications.callback-secret`. The provider must be a configured sender. Receipts are stored append-only and keyed by receipt ID, so replays are ignored. `DELIVERED`/`UNDELIVERED` update the message; `OPTED_OUT` records an opt-out. Without a secret the endpoint answers 404. |
| **Delivery history** | The alert log filters by status (including `SUPPRESSED`), event and customer, and shows the provider receipt. The portal adds template, opt-out and verification dialogs. |
| **Monitoring** | `ops/monitor.py` reports `cbs_notifications_failed_24h` and `cbs_notifications_pending_age_seconds` (counts only, no customer data). Prometheus raises `CbsCustomerNotificationsFailing` and `CbsCustomerNotificationsStuck`. |

## Receipt format (provider-neutral)

```json
{"receiptId":"provider-unique-id","kind":"DELIVERED","providerReference":"id returned when sending","code":"optional provider status"}
{"receiptId":"provider-unique-id","kind":"OPTED_OUT","bankId":"<bank uuid>","channel":"SMS","destination":"+2547…"}
```

A provider adapter translates the provider's own callback into this form, or the provider is configured to sign it directly.

## Configuration

| Setting | Default | Notes |
| --- | --- | --- |
| `cbs.notifications.callback-secret` (`CBS_NOTIFICATIONS_CALLBACK_SECRET`) | blank (webhook disabled) | The production guard refuses a secret shorter than 32 characters. |
| `cbs.notifications.notices.maturity-days` | 7 | 0–60 |
| `cbs.notifications.notices.loan-due-days` | 3 | 0–30 |
| `cbs.notifications.notices.arrears-days` | 1,30,60,90 | Each 1–365 |
| `cbs.notifications.notices.scan-interval-ms` | 900000 | Background scan interval |

## Decisions and limits

- **D09 (provider):** needed for the exit test. A message must reach a test handset through the provider sandbox, and failures must retry and then appear in monitoring.
- **Opt-out scope:** STOP suppresses every message to that destination, including arrears notices. If the bank must send some notices regardless, it has to decide which ones and through which channel.
- **Loan due reminders** use the contractual schedule dates (current terms after a restructure). They do not check whether the instalment was already paid early.
- **Arrears notices** use the same posted-event replay as collections. Loans whose history does not reconcile are skipped and appear on the collections screen instead.
- **French and Kiswahili wording** is machine-assisted. It needs the native-speaker review in Round 17.
- **Merge order:** this branch is rebased on Rounds 12, 18 and 17 (V48–V49). V50 is reserved for Round 14. V51 rewrites the approval action constraint and keeps `LEGAL_HOLD` from V49; a later rewrite must keep `NOTIFICATION_TEMPLATE`.

## Tests

- **Integration:** `TenantAdministrationIntegrationTest.notificationsVerifyDestinationsUseApprovedTemplatesHonourOptOutsReceiptsAndQueueNotices` covers the following. Unverified destinations get nothing. Wrong codes are counted and the code is never logged. Template rules and maker-checker. Signature, freshness, provider and replay checks on receipts. Opt-out suppression and resumption. Scheduled notices, queued once.
- **Existing alert tests:** the statement-alerts and joint-account tests now verify the destination first.
- **Other:** UI tests in `ui/test/notification.test.mjs`; monitor test in `ops/tests/test_monitor.py`; production guard test for the receipt secret.
