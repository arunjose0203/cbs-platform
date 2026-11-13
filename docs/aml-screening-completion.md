# Round 11: AML and screening completion

Added 13 November 2026 (control migration V47). Completes functional area F13 (AML, sanctions, and customer screening completion) as planned in [go-live-plan-2026-11-13.md](go-live-plan-2026-11-13.md).

## What it adds

| Feature | Mechanism |
| --- | --- |
| **UN Consolidated List XML Adapter** | Native support for UN Security Council consolidated list XML files (`CONSOLIDATED_LIST`). Converts `<INDIVIDUAL>` and `<ENTITY>` elements directly into standard screening rows. Hardened against XXE (DOCTYPE and external entities disallowed). Prioritizes good-quality aliases over low-quality aliases up to the 20-name limit, and strictly excludes approximate/year-only dates of birth. |
| **Scheduled Periodic Re-Screening** | Activated lists can declare an optional re-screen interval (`rescreen_days`, 1–366 days) during maker-checker activation. Background scheduling queues runs with `reason='SCHEDULED'` when the interval elapses since the last run. Runs process in resumable chunks without transaction locking. |
| **Beneficiary Screening (M-Pesa)** | Payee names in M-Pesa B2C payouts are screened against active lists at `SUBMIT`, `APPROVAL`, and `DISPATCH` stages. An undismissed match records an immutable hit (`platform.beneficiary_hit`) in an autonomous transaction and refuses the payout (`PAYMENT_BENEFICIARY_SCREENING_MATCH`). Approved dismissals (`BENEFICIARY_HIT`) carry over for the same payee and list entry. |
| **Investigator Assignment & Case SLAs** | Open compliance cases can be assigned to an investigator with an SLA due date (`due_date`, between today and 366 days). Assignees must hold the bank's `COMPLIANCE` duty when duties are enforced. Changes record an immutable `ASSIGNED` case event. Overdue open cases surface in the UI and trigger Prometheus alert `CbsComplianceCasesOverdue`. |
| **STR / SAR Draft Export** | Produces a canonical, reproducible JSON draft (`cbs-str-draft/1`) gathering reporting bank entity, subject customer profile, investigator grounds, screening hits, beneficiary matches, monitoring alerts, transaction movements (180-day window, up to 500 movements), and audit history. The case records an immutable `STR_DRAFT` event containing the document's SHA-256 hash. |

## UN XML list format

The portal upload dialog accepts either CSV or UN Security Council consolidated XML.
When `UN_XML` format is chosen:
- XML parser enforces secure processing features and disallows `DOCTYPE`.
- Concatenates `FIRST_NAME` through `FOURTH_NAME`.
- Reads `INDIVIDUAL_ALIAS` and `ENTITY_ALIAS`, placing `QUALITY="Good"` ahead of `QUALITY="Low"`.
- Filters dates of birth to only keep `TYPE_OF_DATE="EXACT"`.
- Sets `program` to `UN_LIST_TYPE`.

## Beneficiary screening lifecycle

```
Payout Order (Submit / Approval / Dispatch)
       │
       ▼
Screen payee name against all ACTIVE lists
       │
       ├── No matches ───────────────► Payout proceeds
       │
       └── Matches found
             │
             ├── Was hit already DISMISSED by approval? ──► Payout proceeds
             │
             └── Un-dismissed match
                   │
                   ▼
             Insert into platform.beneficiary_hit (REQUIRES_NEW)
                   │
                   ▼
             Throw PAYMENT_BENEFICIARY_SCREENING_MATCH (Fail closed)
                   │
                   ▼
             Compliance review in UI:
               - Escalate (opens case)
               - Request dismissal (independent maker-checker)
```

## STR draft schema (`cbs-str-draft/1`)

The exported draft produces a stable JSON structure:
- `format`: `"cbs-str-draft/1"`
- `status`: `"DRAFT_NOT_FILED"`
- `reportingEntity`: bank ID, display name, legal entity name, country
- `case`: ID, category, status, risk, assignee, due date, opened by
- `subject`: customer ID, customer number, customer type, name key, birth date
- `grounds`: investigator-provided text
- `screeningMatches`: list of screening hits recorded for the customer
- `beneficiaryMatches`: list of payee hits involving the customer
- `monitoringAlerts`: monitoring rules triggered on customer accounts
- `movements`: recent transaction history with business dates, channels, directions, and exact amounts
- `caseHistory`: immutable audit log of case events

A SHA-256 digest is generated from the exact serialized document and recorded in `platform.compliance_event` with action `STR_DRAFT`.

## Monitoring & Alerting

- `ops/monitor.py` exports `cbs_compliance_overdue_cases` counting open cases past their `due_date`.
- `deploy/staging/alerts.yml` triggers `CbsComplianceCasesOverdue` with severity `warning` if overdue cases persist > 15m.
