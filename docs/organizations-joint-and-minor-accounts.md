# Organizations, joint accounts and minors' accounts — 11 November 2026

This change follows [statements and alerts](statements-and-alerts.md). It covers matrix rows F12 (customer types) and F14/F16 (account operation by mandate). Bank acceptance remains pending.

## Organization customers

A customer is now either an **individual** (the default, so existing records are unchanged) or an **organization**. The organization form reuses the onboarding fields:

| Field | Meaning for an organization |
| --- | --- |
| Full name | Registered name |
| Date of birth | Incorporation date |
| Nationality | Country of registration |
| Document | **Registration certificate**, required. Individuals must use a national ID or passport |

A customer's type can't be changed after onboarding. Duplicate checks, approval, bank-side storage and compliance cases work as for individuals.

**Directors and owners.** Customers → **Directors & owners**, then submit for approval (`CUSTOMER_PARTIES`). This records:
- directors
- beneficial owners, with their ownership percentage (total ≤ 100%)
- representatives

All of them must be onboarded **adult individual** customers. At least one director is required.

**When an organization can transact.** It is eligible only when it has at least one recorded director **and** none of its recorded persons is restricted. This applies to funding, debits, servicing and alerts. A compliance restriction on a beneficial owner therefore stops the organization's accounts (`ORGANIZATION_PARTY_RESTRICTED`). There is no automatic ownership threshold: record every owner at or above the bank's AML threshold (for example 10% or 25%, set by the bank).

## Account mandates

Deposit accounts → **Mandate**, then submit for approval (`ACCOUNT_MANDATE`). The roles allowed depend on the primary holder:

| Primary holder | Roles allowed | Who can authorise debits |
| --- | --- | --- |
| Adult individual | Joint holder, authorised signatory | The holder, joint holders and signatories |
| Minor (under 18 on the business date) | Guardian only (an adult) | Guardians only. The minor cannot sign |
| Organization | Authorised signatory only, who must be one of its recorded directors or representatives | Signatories only |

**Signing rules:** any one, any two, or all of the authorised signers.

**Which debits need signers.** Withdrawals, transfers out and final closure settlements must name the authorising signers (`signatories`) on:
- any account with a mandate
- every organization account
- every minor's account

The check runs at submission and again at approval. Each signer must be distinct, must be an authorised signer, must currently be eligible (for example, not compliance-restricted), and there must be as many as the rule needs.

**Accounts that can't be debited yet.** An organization or minor's account with no approved mandate can't be debited (`MANDATE_REQUIRED`). Existing accounts of minors are affected the same way: they need a guardian mandate.

**What doesn't need signers.** Holds, releases, reversals and credits don't need signers, and are rejected if signers are supplied with them. An adult individual's sole account with no mandate works exactly as before.

**Cash desk.** Cash-desk withdrawals pass the signers through (`signatories` on the cash request). The teller portal's cash withdrawal form doesn't collect them yet, so cash withdrawals from mandated accounts are possible through the API only. M-Pesa payouts from mandated accounts are blocked (they carry no signers) until a payment-signing flow is agreed.

## Statements and alerts

- **Statements** (CSV and PDF) list all account holders: the primary holder, then joint holders and guardians.
- **Alerts** go to every holder and guardian who has an approved alert preference. Each recipient gets their own alert, which is deduplicated per posting.

## Not covered (bank decisions or later work)

- When a minor reaches 18, the guardian mandate stays in force until it is changed through approval. Nothing switches over automatically.
- Signature specimens, and checking signatures against documents, are outside the system. The withdrawal's reference and reason should cite the signed slip or instruction.
- Mandates per amount band (for example "any one up to 100,000, any two above") are not supported.
- Corporate group exposure and related-party lending checks are not supported.
- Joint holders and owners are reference data in the control database, protected by approval and audit. They are not copied into the bank database.

## Database

Control **V41** adds:
- the `CUSTOMER_PARTIES` and `ACCOUNT_MANDATE` approval actions
- `customer_registry.customer_type`
- `platform.customer_party`, `platform.account_mandate` and `platform.account_party`

The bank schema is unchanged.

## Verification

`jointMinorAndOrganizationAccountsEnforceApprovedMandates` covers:
- **Organizations:**
  - document/type mismatch and type-change rejection
  - funding blocked until a director is recorded
  - ownership, adult-party, missing-director and non-organization rejections
  - signatories limited to directors and representatives
  - a beneficial owner's compliance restriction blocking the organization
- **Joint accounts:**
  - unsatisfiable rule, wrong role and minor joint-holder rejections
  - self-approval denied
  - an "any two" debit refused with no signers, one signer, an unrelated signer or a duplicate signer, and accepted with both holders
  - signers rejected on a hold
  - an alert to the opted-in joint holder only
  - both holders named on the CSV statement
  - a restricted signer refused
- **Minors' accounts:**
  - debits blocked with no mandate
  - the minor refused as a signer
  - a guardian's debit accepted

The full suite, UI tests (mandate form roles, signer collection, de-duplication) and PDF holder rendering also pass. All data was fictional.
