# Module 05.1: Chart of Accounts

Open **Chart of Accounts** in the sidebar, or open a bank and select its
**Chart of Accounts** action. Choose a bank, then add its account definitions.
The bank must be ACTIVE to submit or approve changes. The directory can still
be read for inactive banks.

## Workflow

1. Add a root group, for example code `1000`, name `Assets`, category Assets,
   normal balance Debit, kind Group. Codes are bank-defined; no country-specific
   chart or regulatory mapping is installed automatically.
2. Supply a reason and submit for approval.
3. A different active platform administrator reviews the before/after values in
   **Approval Inbox**, request type **Chart account**, and approves or rejects it.
4. Once approved, the group is available as a parent. Add a posting definition,
   such as `1100` / `Cash`, under the approved Assets group.
5. Use **Manage** to request name, description or active-status changes. No delete
   endpoint is provided. Rejected and cancelled requests do not change the chart.

Search matches code or name across all records in the selected bank. Category
and active-status filters apply on the server. Pages contain 25 records; summary
counts describe the bank's entire approved chart. The bank picker shows the first
100 banks; the action on bank detail opens banks beyond that first page.

## Rules

- Codes use 1–32 uppercase letters, digits, hyphens or underscores, beginning
  with a letter or digit. Each code is unique within its bank.
- Categories are ASSET, LIABILITY, EQUITY, INCOME and EXPENSE.
- Normal balance is DEBIT or CREDIT. The form suggests Debit for assets/expenses
  and Credit for liabilities/equity/income; users may choose the opposite for
  contra accounts.
- A parent must be a group in the same bank and category. An active account must
  have an active parent. Groups cannot be deactivated while they have active
  children; deactivate children through approval first.
- Code, category, normal balance, kind and parent are fixed after creation.
  Immutable parentage prevents cycles and avoids silent reclassification.
- Submitted changes are pending until approved. Parent groups must already be
  approved, so submit and approve a group before adding children.
- Chart changes increment the bank configuration version. Any intervening bank
  or chart change makes an older pending request STALE; review current data and
  submit again. This deliberately serializes configuration changes per bank.
- Idempotency keys prevent duplicate submissions. Identical retries return the
  existing request even after approval. A changed payload with the same key is
  rejected. The UI gives corrected form payloads a new key.

## Storage and boundaries

Flyway control-store migration V6 creates `platform.chart_account`, with a
`tenant_id` on every row and a composite foreign key preventing cross-bank
parentage. These are bank-wide, currency-neutral configuration definitions,
stored alongside bank settings so chart updates, approval decisions, tenant
versions and audit records commit in one PostgreSQL transaction.

This increment does not create balances, customer accounts, journals, holds or
postings. [Module 05.2](module-05-ledger-accounts.md) now binds approved posting
definitions to legal entities and currencies in each bank's dedicated database.
Active ledgers and pending deliveries block chart deactivation. No currency
conversion is performed and no sample accounts are inserted into live banks.

Only platform administrators can read or propose changes in this increment;
bank operator and auditor roles do not yet have Chart of Accounts endpoints.
All requests retain the existing authenticated staff, session and MFA checks.
The page supports English, French and Swahili labels. Account names are stored
as entered; per-language account-name translations are not implemented.

## API

Base path: `/api/v1/admin/tenants/{bankId}/chart-of-accounts`.

- `GET`: optional `search` (max 100 characters), `category`, `kind`, `active`,
  `limit` (1–100) and `offset`. Returns bank version, items, filtered total and
  unfiltered account/group/active counts.
- `POST`: requires a UUID `Idempotency-Key`; returns HTTP 202 with a
  `CHART_ACCOUNT` approval request. Body fields: `expectedVersion`, `accountId`
  (null for creation), `code`, `name`, `description`, `category`, `normalBalance`,
  `kind`, `parentId` (nullable), `active`, and `reason`.

Use the existing approval decision endpoints. Successful application emits
`CHART_ACCOUNT_CHANGED` in the bank audit history with before/after values and
the resulting account ID. The reason links to the approval request, where maker
and checker identities and their reasons are retained.

## Verification

The full backend suite passes 58 tests. Five new integration scenarios cover
submission and editing through approval, self-approval rejection, idempotent
replays, search/pagination, parent rules, bank isolation, invalid inputs,
authorization, inactive banks, concurrent approval and transaction rollback.
Eight UI tests pass, including output escaping and bank-context selection.

Browser verification uses the isolated fixture server:
`node ui/test/approval-preview.mjs --chart` on port 18082. This server never
connects to a live database or identity provider. Live two-administrator acceptance
is still required using real registered staff identities before a pilot.
