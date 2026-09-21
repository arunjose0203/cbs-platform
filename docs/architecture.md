# Core banking architecture

Status: foundation, Module 01 tenant administration, and Module 02 identity,
branch access and administration portal implemented. No banking transaction
capability is production-ready. See module-01-api.md and module-02-access.md
for the implemented APIs and identity-provider setup.

## Application boundary

Use a modular application. Within a bank, financial command state, journals,
balances, holds, idempotency records, and outbox events must commit in one
transaction. Notifications and external payment submission happen after commit.

The platform control plane owns tenant registration and routing metadata. A
bank database owns that bank's customer and banking records. Use separate
database credentials per bank. A shared database cluster still shares an
infrastructure failure boundary.

## Modules

| Module | Owns | Allowed dependencies |
| --- | --- | --- |
| Platform | Bank registry and provisioning state | Shared primitives |
| Identity | Authenticated actors and authorization | Platform |
| Governance | Approvals and audit | Identity |
| Reference | Currency metadata, locale, business calendars | Platform, Governance |
| Accounting | Journals, balances, holds, posting rules | Reference, Governance |
| Products | Effective-dated product versions | Reference, Accounting |
| Customers | Customers, relationships and onboarding | Identity, Governance, Reference |
| Accounts | Product agreements and account lifecycle | Customers, Products, Accounting |
| Transfers | Internal transfer instructions | Accounts, Accounting |
| Payments | External instructions and rail adapters | Accounts, Accounting, Governance |
| Reconciliation | Settlement matching and exceptions | Payments, Accounting |

Modules call public application interfaces; they do not update another module's
tables. Only Accounting changes posted balances. The shared library contains
small immutable primitives, not banking workflows.

## Non-negotiable invariants

- Derive tenant authorization from verified identity. A tenant header is only
  a routing request and never proof of access.
- Scope records, caches, documents, jobs, events and idempotency keys by tenant.
- Store money as exact decimal plus currency. Never use binary floating point.
- Store UTC timestamps, business dates and value dates separately.
- Use stable error codes; translate presentation text rather than business keys.
- Posted journals are immutable. Reversals refer to the original instruction.
- Each required book/currency boundary balances independently.
- Do not expose write APIs until authorization and audit are enforced.
- A retry with the same key and different payload is a conflict.
- Unknown external payment status is investigated, not blindly retried.

## First vertical slice

1. Boot a service and expose an anonymous, minimal liveness endpoint.
2. Validate environment configuration; provide no production credentials.
3. Establish request correlation and a stable API error format.
4. Add CI build and tests and document local execution.
5. Implement tenant registration with migrations, authorization and audit next.

## Deferred decisions

Target jurisdiction, payment rails, production hosting, recovery objectives,
identity provider, customer language set and first banking products require
bank-specific decisions. Development defaults do not establish legal compliance.
