# P-07: batch scheduling and operations console

Added 14 November 2026 (control migration V52). Production-engineering item P-07 (functional area F21) of the [go-live plan](go-live-plan-2026-11-13.md). The exit criterion is that the daily cycle runs on schedule with human approvals only.

## Scheduled preparation of the daily cycle

For each bank listed in `cbs.batch.banks`, a background job runs every 5 minutes. Once the business day's cut-off (`cbs.batch.prepare-after`, local time in the bank's business timezone) has passed, it submits the next step of the cycle:

1. **Servicing run** for the business date, if the bank has approved servicing policies.
2. **Month- or year-end close**, if the business date ends a period and a close policy is approved.
3. **Day close** to the next working day.

Each step is submitted only when the previous one has posted. While a request waits for approval or delivery, the job records that and does nothing else.

**People approve every step.** The job submits as a configured **service identity** (`cbs.batch.service-issuer` / `cbs.batch.service-subject`):

- It is an ordinary staff identity, so the bank can disable it at any time to stop automation.
- When the bank enforces duties, it needs the FINANCE duty.
- As the maker, it can never approve its own requests.
- The job never approves, never delivers and never changes the bank's version itself.
- If a checker rejects a scheduled request, the job does not resubmit it that day. It records `BATCH_REQUEST_REJECTED`, and people decide what happens next.
- If a step cannot be submitted (for example a cash count is missing or approvals are pending), the job records the blocker code and tries again later.

Outcomes are kept in the append-only `platform.batch_event` table, but only when the outcome changes, so a blocked evening does not fill the log. Idempotency keys are derived from the bank, step and business date, so a retried submission never creates a second request.

## Operations console

`GET /api/v1/admin/tenants/{bank}/operations-console`, the **Operations console** view in the portal, shows one page per bank:

- the business date and next working day, and whether the scheduler is enabled and its cut-off;
- the status of each cycle step: done, not required today, waiting for approval, posting, or not started;
- everything that blocks today's day close, as human-readable messages;
- pending approvals by action, with the oldest waiting time;
- every delivery queue with undelivered or failing work. Queues are discovered from the control schema, so new queues appear without code changes;
- exceptions: overdue compliance cases, open payment cases, open statement imports and failed customer alerts;
- the scheduler's last 20 outcomes.

The page shows counts only and no customer data. Any staff member with a bank duty can read it.

## Monitoring

`ops/monitor.py` reports `cbs_batch_blocked_banks`, the number of banks whose latest scheduler outcome is `BLOCKED`. Prometheus raises `CbsDailyCycleBlocked` after 30 minutes.

## Configuration

| Setting | Default | Notes |
| --- | --- | --- |
| `cbs.batch.banks` (`CBS_BATCH_BANKS`) | blank (off) | Comma-separated bank IDs |
| `cbs.batch.prepare-after` | 18:00 | Local time in the bank's business timezone |
| `cbs.batch.service-issuer` / `service-subject` | blank | Required when banks are listed; must be an active staff identity |
| `cbs.batch.interval-ms` | 300000 | How often the job checks |

## Tests

- `TenantAdministrationIntegrationTest.dailyCycleIsPreparedOnScheduleByTheServiceIdentityAndApprovedOnlyByPeople` covers:
  - nothing happens before the cut-off;
  - a blocked day close is recorded once;
  - the servicing run comes before the day close;
  - the service identity cannot approve its own request;
  - the request moves from waiting for approval to waiting for delivery;
  - a rejected close is not resubmitted;
  - the console content;
  - events are append-only;
  - the route requires authentication.
- UI: `ui/test/ops-console.test.mjs`. Monitor: `ops/tests/test_monitor.py`.

## Limits

- Banks are enabled by configuration, not by an approved bank setting. That is enough for a pilot with one or two banks.
- A schedule that the bank changes through maker-checker can be added when needed.
- Production needs the service identity provisioned in the identity provider (P-03).
