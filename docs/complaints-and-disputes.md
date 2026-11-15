# Complaints and disputes (Round 20)

**Branch:** `round-20-complaints` (on `p10-security`, which already contains `master` up to `bb617e8`)
**Migration:** control `V57__complaints.sql`. No bank migration.
**Go-live plan:** first item taken from "Explicitly post-pilot unless the bank requires them" (matrix follow-up to F12/F20).

## What it does

Each bank gets a complaints register. Staff record a complaint against a customer, handle it through
recorded steps, and close it only when a second administrator approves the proposed resolution.

| Step | Who | How it is controlled |
| --- | --- | --- |
| Register | Customer operations | `Idempotency-Key`; numbered reference `CMP-<year>-<6 digits>` per bank |
| Acknowledge, assign, add note, escalate (levels 1 to 3) | Customer operations | Each step names the complaint version the operator saw; a replay or a second operator gets `COMPLAINT_STALE` |
| Propose resolution | Maker | Approval action `COMPLAINT_RESOLUTION`; one proposal per complaint at a time; handling steps are refused while it waits (`COMPLAINT_RESOLUTION_PENDING`) |
| Approve resolution | A different administrator | The maker cannot approve; the bank version must be current |
| Link redress payment | Customer operations | Only an approved money request of the same bank, made after the complaint, used once |
| Reopen | Customer operations | Keeps the earlier resolution in the history, moves one level up, restarts the resolution deadline |

## Rules

- **Deadlines.** The acknowledgement date is the received date plus `cbs.complaints.acknowledge-days`
  (default 2) and the resolution date is the received date plus `cbs.complaints.resolve-days` (default 14).
  Both are calendar days. **These defaults are placeholders: the bank's compliance team must confirm them
  against its complaints policy and the regulator's guidance before go-live.**
- **Dates are the bank's own dates.** "Today" comes from the bank's business time zone, not UTC, so a
  complaint is not a day late or early between midnight and 03:00 in East Africa.
- **Outcome and redress must agree.** Upheld or partly upheld needs a redress; not upheld can carry only an
  explanation; withdrawn has none. A refund or compensation needs a positive amount and currency; other
  redress has no amount.
- **Money never moves here.** The resolution records the decision. The refund is paid through the normal
  account operation, funding, journal, cash, fee or payment request with its own maker and checker. The
  complaint then records which approved request paid it. Until then it is listed as **redress pending**.
  The amount on that request is not compared with the redress amount (payload shapes differ per action);
  the checker of that request owns the amount. The link proves the request was approved; whether it has
  posted in the bank ledger is tracked on that request, as for any other money request.
- **Assignment.** The assignee must be an active staff identity and, where the bank enforces duties, hold
  the customer-operations duty.

## Data protection

- The customer's account of events and every handling note are sealed with the bank's field key
  (`pd1:` tokens, as in Round 18). The retry check stores a SHA-256 digest of the request, not the request.
- The short summary is not sealed, because it appears in lists and the register. The screen tells staff
  to keep personal data out of it.
- Opening a complaint is audited (`COMPLAINT_VIEWED`) with the customer ID, like a customer profile view.
- The register export leaves out the customer's account and the notes.

## Evidence

- `platform.complaint_event` is append-only (the shared `reject_audit_change` trigger).
- `platform.complaint` cannot be deleted. What was received (customer, category, summary, details, dates,
  amounts) cannot be rewritten; an acknowledgement cannot be changed; every change must advance the version
  by exactly one; escalation levels and the reopened count never go down.
- A money request can settle one complaint only (unique index over `REDRESS_POSTED` events).

## API

All routes are under `/api/v1/admin/tenants/{bank}/complaints` and need authentication. Bank duty:
customer operations to write; customer operations, compliance or finance to read.

| Method and path | Purpose |
| --- | --- |
| `GET ` `?status=&category=&customerId=&limit=&offset=` | List. Filters: `OPEN` (default), `OVERDUE`, `UNASSIGNED`, `ESCALATED`, `REDRESS_PENDING`, `RESOLVED`, `ALL` |
| `POST ` | Register (201) |
| `GET /{id}` | Complaint, the customer's account and the history (audited, never cached) |
| `POST /{id}/acknowledgement`, `/assignment`, `/notes`, `/escalation`, `/reopening` | Handling steps |
| `POST /{id}/redress-posting` | Link the approved request that paid the redress |
| `POST /resolutions` | Propose a resolution (202, goes to the Approval Inbox) |
| `GET /summary?from=&to=` | Counts for complaints received in a period (at most 366 days) |
| `GET /register?from=&to=` | Register as CSV. More than 10,000 rows is refused, never cut short |

## Portal

**Complaints & disputes** in the navigation: a 30-day summary, the list with deadline and status markers,
the complaint history with the available actions, and the register download. English, French and Kiswahili.

## Tests

- `TenantAdministrationIntegrationTest.complaintsAreRegisteredHandledAndClosedOnlyByAnApprovedResolution`
  (database `complaints`): the full flow over HTTP, including retries, refusals, sealing at rest, the
  immutability triggers, self-approval refusal, redress linking and reuse, reopening, the summary and the CSV.
- `ComplaintServiceTest`: configuration bounds and the resolution rules, without a database.
- `ui/test/complaints.test.mjs`: seven tests (escaping, pinned versions, idempotency keys, translations).

## Not included

- **Complaints from people who are not customers.** A complaint needs a customer record.
- **Automatic customer messages** (acknowledgement or outcome by SMS or email). The response text is
  recorded; sending it is manual until the bank chooses a provider (decision D09).
- **Regulator return layout.** The CSV is a plain register. The regulator's own template has not been supplied.
- **Card chargebacks and M-Pesa reversals with the provider.** A dispute is tracked here; any reversal with
  Safaricom is still done outside the system.
- **Attachments.** Evidence files stay in the KYC document store or outside the system; notes carry references.
- **Deadlines per category or business-day counting.** One pair of calendar-day deadlines applies to all complaints.
- **Operations console and alerting.** Overdue complaints show on this screen only, not yet on the
  operations console or in Prometheus alerts.
