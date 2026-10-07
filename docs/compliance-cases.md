# Customer verification and compliance cases

Implemented 7 October 2026. This is a manual investigation and independent-review workflow. It does not perform identity verification, sanctions/PEP screening or automated transaction monitoring, and it does not establish country regulatory compliance.

## Operator workflow

1. Sign in as an active platform administrator with the required authentication assurance. Open **Compliance cases**, choose the bank and select **Open case**.
2. Select an existing customer and a category: KYC review, screening alert or transaction review. Record an evidence reference and a reason. Opening the case immediately restricts ordinary customer financial activity; opening a case is not a finding of wrongdoing.
3. Review the case history and append evidence as the investigation progresses. References identify evidence held in the bank's approved repository; this module does not upload or verify documents. Do not paste credentials or unnecessary personal data into reasons.
4. Propose clearance or confirm restriction. Explicitly select the assessed risk and provide identity and screening/investigation references. Clearance requires a future review date and an applied customer profile. The bank determines the review interval; there is no default risk or invented country policy.
5. A different administrator reviews the request in **Approval Inbox**. The checker cannot be the request maker or the original case opener. A changed bank, case or customer version requires a fresh proposal.
6. A customer is free of this module's restrictions only when every case is cleared against the current customer profile and every review date is still in the future. Other customer, account, funds, permission and limit rules still apply.

Review dates use **UTC calendar dates**, independently of the banking business date. Clearance expires at the start of the selected date in UTC. The stored decision remains CLEARED; the effective activity flag changes to restricted and the case appears in **Review due**. Profile changes also require renewed review. Adding a note alone does not change a decision; open a new case for a new concern requiring an immediate restriction.

## Financial and recovery controls

- Existing customer eligibility checks now include all compliance cases. Funding, debits, transfers, cash and account workflows inherit these checks at their applicable submission, approval and delivery boundaries.
- Queued account opening and daily servicing explicitly recheck eligibility before the first bank posting. A case opened after approval can therefore stop an unposted instruction.
- A receipt already committed in the bank database is acknowledged on retry, even if a restriction was added afterward. This preserves committed accounting without duplicating financial effects.
- Hold release retains its existing exception. Read-only balance/statement access is not a clearance decision.
- Opening a case remains possible while daily servicing has sealed the business day. A blocked approved servicing batch requires investigation and permitted clearance before retry; this release does not automatically skip restricted accounts or finish a partial batch.
- A customer with **no cases** still uses the existing onboarding eligibility rules. This release does not automatically create cases for legacy customers or prove that those customers have been screened. Mandatory onboarding review and backfill are production gaps.

## Storage and API

Control migration **V24** introduces `platform.compliance_case`, `compliance_event` and `compliance_command`, and the `COMPLIANCE_REVIEW` approval action. No new bank migration is required for this module. Cases are bank/customer scoped; immutable event and command records support evidence and idempotency. Deferred database checks require matching event state and approved review evidence. Database owners remain privileged; these controls do not replace restricted runtime database roles or external evidence retention.

Base path: `/api/v1/admin/tenants/{bank}/compliance`.

| Request | Purpose |
| --- | --- |
| GET base, with status/limit/offset | Bank-scoped case list; status ALL, OPEN, CLEARED, RESTRICTED or REVIEW_DUE |
| GET `/{id}` | Case and latest 100 evidence events; records an evidence-view audit event |
| POST base | Open a case with expectedVersion, customerId, category, evidenceReference and reason |
| POST `/{id}/notes` | Append evidence with expectedVersion, caseVersion, evidenceReference and reason |
| POST `/reviews` | Propose decision with bank/case/customer versions, explicit risk, evidence references, reviewDue and reason |

Writes require a UUID `Idempotency-Key`. Reuse the same key and identical request for a network retry; a changed payload conflicts. UI forms pin the versions loaded when opened. Clearance needs a future reviewDue (year at most 2200); restriction requires a null reviewDue. Reference fields accept letters, numbers, period, underscore, slash and hyphen, starting with a letter or number, up to 160 characters. Reasons are required, up to 500 characters.

## Remaining scope

Bank-scoped compliance officer roles, case assignment, priority/SLA, full evidence pagination, document storage, mandatory customer review, corporate/beneficial-owner data, verified screening providers, list refresh, alert generation and tuning, regulatory reporting and bank-approved retention are not implemented. The current UI customer picker is limited to the first 100 customers; the case list is paginated. New compliance text is English and needs complete translation/accessibility acceptance.

See [enterprise review](enterprise-readiness-review-2026-10-07.md) and [verification evidence](verification.md). No live customer case or financial transaction was created during development verification.
