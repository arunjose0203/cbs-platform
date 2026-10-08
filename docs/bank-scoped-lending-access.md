# Bank-scoped lending roles and approval limits

Implemented 8 October 2026. This extends Staff & Access and the lending, credit and collections workspaces. It does not certify the application for production.

## Operator setup

1. Register the staff identities and bank branches in Staff & Access. Assign each customer account to its operating branch through the existing independently approved account-branch workflow.
2. Open **Staff & Access → Lending permissions → Propose grant**. Select the bank, staff identity, explicit whole-bank or selected-branch scope, roles and justification. A whole-bank grant includes accounts without a branch assignment; a branch grant excludes unassigned accounts and inactive branches.
3. For a credit approver, add explicit operation/currency rows with bank-approved per-transaction and daily amounts. Omitted rows grant no approval authority. Amounts retain decimal precision; no monetary limits are seeded.
4. A different platform administrator approves the grant through **Approval Inbox → Lending Access**. The beneficiary cannot approve it. Replacing a grant replaces the entire role, branch and limit set; revocation follows the same independent workflow. Disabling the staff identity remains the existing emergency session/access control.
5. Bank staff use **Loan applications & credit review**, **Lending**, **Loan arrears & collections**, and **Lending approval inbox**, without platform-administrator membership. Only granted banks are listed; record queries and counts apply branch scope before pagination.
6. Review the permission report and immutable grant/revocation history periodically. Authority changes are effective after the independent approval commits, including on existing sessions and before first posting of queued financial work.

## Role matrix

Roles are additive. Auditor alone is read-only; combining it with another role adds that role's permissions.

| Role | Authority inside granted bank/branch scope |
| --- | --- |
| Credit officer | Create applications; propose credit review, condition release and restructuring |
| Credit approver | Independently approve/reject lending instructions, within matching currency/operation limits; retry authorized pending postings |
| Collections officer | Assign/reopen cases, record contacts, promises and escalations, resolve eligible cases and add legacy collection notes |
| Finance officer | Propose disbursement, repayment, accrual, corrections, impairment, write-off and fee assessment/payment/waiver; retry postings |
| Auditor | Read scoped applications, loan positions/history, collections and approval history |

Every role can read records within its scope. Case assignment requires the recipient to have a current collections role for that account. Platform administrators manage identities, grants, bank-wide policies, ledger setup and storage preparation, but must have an explicit lending grant for operational lending access. Existing cash/payment roles remain separate. This increment does not generalize these five roles to every other CBS module.

## Approval authority

Authority is keyed by bank, staff identity, operation and currency. Supported operations are `CREDIT_REVIEW`, `CREDIT_RELEASE`, `LOAN_DISBURSE`, `LOAN_REPAY`, `LOAN_ACCRUE`, `LOAN_REVERSAL`, `LOAN_RESTRUCTURE`, `LOAN_IMPAIR`, `LOAN_WRITE_OFF`, `LOAN_FEE_ASSESS`, `LOAN_FEE_PAY`, and `LOAN_FEE_WAIVE`.

| Operation | Amount charged against authority |
| --- | --- |
| Credit review / condition release | Requested application principal |
| Disbursement | Opening principal |
| Repayment; fee assessment/payment/waiver | Requested amount |
| Accrual, restructuring, impairment, write-off | Outstanding principal plus posted interest at submission; conservative exposure authority |
| Correction/reversal | Greater of absolute customer movement and absolute interest movement on the original event |

Daily totals use the bank business date of the instruction, not the computer's calendar date. Currency rows are independent: there is no implicit FX conversion or shared equivalent-value limit. Each approval counts once, including approved but unposted work; retries exclude their own reservation. A reversal is a separately authorized operation and does not replenish prior daily authority. Failed/rejected/cancelled/stale decisions do not consume authority. Existing bank-wide transaction limits, credit policy thresholds, customer eligibility and closed-day checks also apply.

The maker cannot approve/reject their own instruction. Credit review and condition release also exclude the application's creator. Maker and checker must remain active with matching current roles and branch scope at decision and before first financial delivery. Tenant-row locking serializes authority checks and financial approval changes. Grant changes advance the bank version, so older pending proposals may become stale and require resubmission.

The legacy credit-policy `approverSubject` is retained as an active registered **policy contact**, not exclusive approval authority. Scoped roles and limits now determine the permitted reviewer. Banks should explicitly review this transition when assigning grants.

## Revocation and retries

A revoked grant immediately blocks subsequent authorized operations and any queued posting that has not reached its dedicated bank database. Restore an independently approved grant when appropriate and retry the same approved posting; do not create a duplicate financial instruction. If the bank receipt already committed but its acknowledgement was lost, recovery checks the immutable matching receipt first and acknowledges that committed transaction even if authority was subsequently revoked. This does not permit a new posting.

## Migration and interfaces

Control migration **V37** adds current grants, immutable grant history, immutable request scopes and daily usage. Bank schema remains **V21**. No real staff grants, bank-approved amounts, financial records or MFA exceptions are created by this release.

Existing loan request scopes are reconstructed from saved approval previews, including their original financial business dates and amounts. Approved financial decisions are imported into authority usage for their original reviewer/day. Existing credit requests did not store an authority business date; their scope is routed using the bank's cutover day, and already-approved historical credit decisions are not retroactively charged to new authority. Pending credit requests undergo current grant/limit and bank-version checks. Existing queued loan work therefore requires explicit current maker/checker grants before first posting.

- Administration: `/api/v1/admin/tenants/{bank}/lending-access` (GET report, POST approval proposal), plus `/branches`.
- Assigned banks: `/api/v1/banks/lending-operations/banks`.
- Scoped workspace: `/api/v1/banks/{bank}/lending-operations`, with `/credit`, `/contracts`, `/loan-collections` and `/approvals`.
- HTTP requires registered active staff, valid session and operational MFA under the existing identity policy. UI button restrictions are convenience; backend checks are authoritative.

## Acceptance and next release

Security integration coverage exercises non-admin HTTP access/approval, mandatory MFA, no platform-admin bypass, branch-filtered lists and details, missing currency/operation caps, per-transaction and daily boundaries, self-approval, beneficiary grant approval, read-only auditors, revoked queued postings, safe lost-acknowledgement recovery, branch closure and immutable history. Existing lifecycle regression covers disbursement, repayment, corrections, restructuring, impairment, write-off and fees.

Before production, bank owners must approve their actual role matrix, branch assignments and monetary limits, conduct two-person access review and execute bank-led acceptance. The next release priority is **Production Deployment, Backup & Disaster Recovery**: hardened identity/HTTPS, secret management, repeatable deployment, coordinated control/bank/identity backup, measured restore and failover drills, monitoring, and bank-approved recovery objectives. Production deployment and recovery success are not implied by development test results.
