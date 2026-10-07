# Branch operations and scoped limits

This increment connects branch access to customer operation approvals. It is not a teller till or cash custody implementation.

## Setup

1. Create a branch in the account's legal entity.
2. Register staff, grant bank operator membership and assign TELLER or BRANCH_MANAGER at the branch. AUDITOR remains read-only.
3. In Bank directory → Manage bank → Branch controls, submit an account-to-branch assignment. Another platform administrator must approve it.
4. Submit both branch-wide and individual staff limits for each currency and operation. Another platform administrator must approve each policy. No example financial limits are installed.
5. Staff sign in with recent MFA and open Branch workspace. The current UI supports holds, full hold releases and transfers to another account on the displayed page within the same branch.

## Enforced rules

- Operational routes require MFA independently of the platform administrator role.
- Staff must retain active bank and branch membership. Bank auditors cannot submit or approve even if an old branch role remains operational.
- Account assignments must match the branch's legal entity. Assignment changes require approval and cannot bypass pending account operations or funding delivery.
- Branch operation submission requires CASH_TRANSACT; review requires APPROVE_BRANCH. Makers cannot approve their own requests. Makers may cancel their own pending requests.
- The original branch and staff ID are recorded at submission in an immutable operation scope. Approval rechecks scope and current maker/reviewer permissions.
- Branch and staff caps are both required for branch-bound financial operations and holds; full hold releases are exempt. Configured bank-wide caps still apply.
- Daily usage is per bank business date, currency and operation, including approved instructions awaiting or failing delivery. Holds that were released still count. Posting retries consume no additional allowance.
- Financial approval retains the existing tenant-row serialization and version checks. Concurrent stale approvals cannot post against an older configuration.
- Platform administrators cannot bypass branch roles and scoped limits to submit or approve an operation on an assigned account. Existing unassigned development accounts retain their previous administrator-only workflow.
- Transfers through this workflow stay within one branch. Cross-branch transfers need a separate policy and are not enabled.

## API

Administrative configuration:

- POST `/api/v1/admin/tenants/{bankId}/account-branches`
- GET/POST `/api/v1/admin/tenants/{bankId}/branch-limits`

Both POST routes use an Idempotency-Key and the existing Approval Inbox. Assignment fields: expectedVersion, accountId, branchId, reason. Limit fields: expectedVersion, branchId, optional staffId, currency, operation, perTransaction, dailyTotal, reason. Omit staffId for a branch-wide policy.

Staff workspace:

- GET `/api/v1/banks/operations/branches`
- GET `/api/v1/banks/{bankId}/branches/{branchId}/operations/accounts`
- GET/POST `/api/v1/banks/{bankId}/branches/{branchId}/operations/requests`
- GET `/api/v1/banks/{bankId}/branches/{branchId}/operations/requests/{id}`
- POST the request's `/approve`, `/reject` or `/cancel` route with a reason.

Control migration V16 adds account assignments, scoped policy storage and immutable request attribution. Bank schemas do not change.

## Remaining work

Teller cash custody, counted sessions, customer cash deposits/withdrawals, vault transfers and daily counts are now implemented in teller-cash-management.md. Cash exceptions, reversals and full denomination inventory remain pending. The API's existing WITHDRAWAL operation records an independently completed payout; it does not dispense cash or represent a till movement. The portal deliberately limits its first branch form to holds, releases and within-branch transfers.

Other remaining work includes richer account searches, complete interface translations, maker/checker administration of staff grants, bank-approved values for the now-mandatory bank-wide policies, and bank-specific authorization/limit acceptance. Rwanda, Uganda and Kenya remain the target markets; providers and real policy values are deferred.
