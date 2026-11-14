# Threat Model and Security Review (P-10)

Production-engineering item P-10 (gates E01, E03) of the [go-live plan](go-live-plan-2026-11-13.md). Exit criteria: comprehensive threat model, internal review of security boundaries, and penetration testing scope/scenarios with zero open critical or high findings.

---

## 1. Executive Summary and Security Principles

The Core Banking System (CBS) processes multi-currency financial accounting, customer deposits, cash movements, lending portfolios, foreign exchange, and payment switch integrations. Security design is founded on five non-negotiable principles:

1. **Balance Sheet & Double-Entry Conservation:** Money cannot be created or destroyed through software errors or unauthorized actions. Every debit must match a credit with identical value and currency; journal lines are append-only and immutable once committed.
2. **Strict Multi-Tenant Database Isolation:** Each banking institution operates on a distinct, physically isolated PostgreSQL tenant schema/database (`bank_*`). Control-plane metadata (`platform` schema) is separated from transactional bank ledgers. Cross-tenant data leakage is structurally impossible at the database engine level.
3. **Mandatory Dual-Control (Maker-Checker):** No single staff member possesses unilateral authority to disburse loans, write off debts, execute manual journals, open products, or mutate bank configurations. Every sensitive action requires independent creation (maker) and approval (checker) by distinct identities.
4. **Zero Trust at Runtime:** The CBS runtime container operates with zero DDL privileges. Flyway migrations run exclusively through an isolated, short-lived operator job (`MigrationMain`). Passwords and tokens are kept in memory only; all personal data (PII) is encrypted at rest using AES-256-GCM.
5. **Fail-Closed Policy Enforcement:** If identity authentication, tenant database connectivity, cryptographic signature verification, or rate-limiting fails, the system refuses the transaction rather than falling back to an unverified state.

---

## 2. System Architecture and Trust Boundaries

```
[ External Actors ]
  │
  ├─ Customer / Teller (Browser) ──> [ Caddy Reverse Proxy (TLS 1.3) ]
  │                                           │
  ├─ Staff / Auditor / Administrator ─────────┤
  │                                           │
  └─ Daraja / External Payment Webhook ───────┘
                                              │
  ┌───────────────────────────────────────────┴──────────────────────────────────────────┐
  │ Trust Boundary 1: Edge & Network                                                     │
  │ - Strict TLS 1.3 termination with ACME certificates                                  │
  │ - Host header validation; isolates IDENTITY_DOMAIN from CBS_DOMAIN                   │
  │ - Filters out internal endpoints (/actuator/*, /metrics) from public ingress         │
  └───────────────────────────────────────────┬──────────────────────────────────────────┘
                                              │
  ┌───────────────────────────────────────────┴──────────────────────────────────────────┐
  │ Trust Boundary 2: Identity & Authentication (Keycloak on PostgreSQL)                 │
  │ - Mandatory password (>=14 chars) + TOTP MFA on all sign-ins                         │
  │ - Ephemeral in-memory PKCE S256 tokens (15-min lifespan, no refresh tokens)         │
  │ - Operator service client disabled by default; isolated administrative port          │
  └───────────────────────────────────────────┬──────────────────────────────────────────┘
                                              │ JWT (Bearer Token)
  ┌───────────────────────────────────────────┴──────────────────────────────────────────┐
  │ Trust Boundary 3: CBS Core Application (Spring Boot 3 / Java 21)                     │
  │ - ProductionGuard checks configuration before socket binding                         │
  │ - Token claim verification (issuer, audience, sid, auth_time, amr: pwd+mfa)          │
  │ - Bank-scoped Role-Based Access Control (RBAC) & Maker-Checker Dual Control          │
  │ - Rate-limiting bucket filter (prevents brute-force / DoS)                           │
  │ - PersonalDataCipher (AES-256-GCM field encryption for PII/KYC)                      │
  └──────────────────────┬──────────────────────────────────────────┬────────────────────┘
                         │                                          │
  ┌──────────────────────┴─────────────┐     ┌──────────────────────┴────────────────────┐
  │ Trust Boundary 4: Control Database │     │ Trust Boundary 5: Tenant Bank Databases   │
  │ - Schema: platform                 │     │ - Schemas: bank_tenant1, bank_tenant2...  │
  │ - Multi-tenant metadata, staff     │     │ - Ledgers, accounts, journals, balances   │
  │ - Audit trail, approvals, limits   │     │ - Isolated credentials & connection pools │
  │ - Runtime has no DDL permissions   │     │ - Continuous WAL archiving (PITR)         │
  └────────────────────────────────────┘     └───────────────────────────────────────────┘
```

---

## 3. Asset Inventory and Classification

| Asset Category | Classification | Protection Controls |
|---|---|---|
| **Master Encryption Keys** | Restricted / Secret | 32-byte AES-256 keys split via Shamir 2-of-3 threshold escrow (`ops/key_escrow.py`); never stored in plaintext in git or docker images. |
| **Customer PII / KYC** | Confidential | Encrypted at rest via AES-256-GCM (`PersonalDataCipher`), statutory retention pruning, strict access auditing. |
| **Financial Ledgers & Journals** | Critical Integrity | Double-entry conservation, immutable append-only records, idempotency keys, unchangeable posted business dates. |
| **Staff Passwords & TOTP Seeds** | Restricted / Secret | Stored solely within Keycloak database using PBKDF2 with SHA-512 (27,500 iterations); TOTP secrets never exposed in API. |
| **API Tokens & Session Keys** | Sensitive Ephemeral | 15-minute validity, stored strictly in browser memory (no localStorage/sessionStorage), bound to session ID (`sid`). |
| **Database Credentials** | Restricted / Secret | Injected via Docker secrets / private root-owned files (`mode 600`), validated by `staging_preflight.py`. |

---

## 4. STRIDE Threat Analysis Matrix

| Threat Category | Specific Threat Scenario | Risk Level | Architectural & Code Mitigation | Verification Evidence |
|---|---|---|---|---|
| **Spoofing** | Forged JWT bearer token presented to CBS API. | Critical | `JwtAuthenticationConverter` strictly validates RS256 signature using issuer keys, checks `aud: cbs-api`, verifies `iss`, and requires `amr: [pwd, mfa]`. `ProductionGuard` rejects token signing keys served from outside issuer origin. | `ProductionGuardTest.refusesSigningKeysFromAnotherHost`, `test_identity_realm.py` |
| **Spoofing** | Compromised staff account replayed across sessions. | High | Invalidation of sessions via `cbs.access.staff_identity.active = false`; all active tokens rejected immediately on revocation (`STAFF_DISABLED`). | `TenantAdministrationIntegrationTest.staffLifecycleBlocksExistingTokens` |
| **Tampering** | Modification of posted financial journal lines or balance ledger. | Critical | Database foreign key and check constraints enforce double-entry balance sheet conservation ($\sum \text{debit} = \sum \text{credit}$). Journal lines have no `UPDATE` or `DELETE` API routes. | `MoneyInvariantsTest`, `JournalPostingTest`, `DatabaseIntegrationTest` |
| **Tampering** | Tampering with WAL archive segments during PITR off-host backup. | Critical | `ops/wal_archive.py` uses AES-256-GCM with SHA-256 checksums and HMAC-SHA256 authentication envelopes. Any bit flip causes authenticated decryption failure. | `test_wal_archive.py.test_tampered_metadata_fails_authentication` |
| **Repudiation** | Staff member executes unauthorized payout and denies doing so. | High | Strict dual-control: maker cannot approve own action. Every submission and approval logs staff ID, IP address, user-agent, timestamp, and immutable payload hash to `platform.identity_audit`. | `ApprovalServiceTest`, `BankDutyRoutesTest` |
| **Information Disclosure** | Cross-tenant database access (Tenant A querying Tenant B accounts). | Critical | Multi-tenancy architecture uses physically separated PostgreSQL databases and distinct Hikari connection pools. Tenant UUID in URL path is validated against session context and tenant catalog. | `TenantAdministrationIntegrationTest.bankLifecycleIsIsolated` |
| **Information Disclosure** | CSV Injection (formula execution) in exported financial reports. | Medium | All CSV downloads sanitize fields starting with `=`, `+`, `-`, `@` by prepending a single quote, neutralizing formula execution in spreadsheet applications. | `StatementExportTest`, `TableOutputTest` |
| **Information Disclosure** | Exposure of internal system state via error messages or stack traces. | Medium | Spring Boot `server.error.include-stacktrace = never` and `server.error.include-message = never`. Exceptions mapped to standard error codes (`CBS_ERROR_CODE`). | `ErrorHandlingTest`, `PortalIntegrationTest` |
| **Denial of Service** | Rate limit exhaustion through automated API flooding. | High | In-memory token bucket rate limiter enforced on all unauthenticated and authenticated endpoints. Excessive requests return HTTP 429 Too Many Requests. | `RateLimitFilterTest`, `test_load_probe.py` |
| **Denial of Service** | Delivery worker queue saturation blocking journal settlement. | High | Delivery drain worker implements time-budgeted batching (`cbs.delivery.drain-ms`) and round-robin tenant servicing, preventing any single tenant queue from monopolizing workers. | `DeliveryDrainTest`, `P-06 write_load.py` |
| **Elevation of Privilege** | Platform administrator granting themselves bank teller/approval rights. | High | Separation of duties: platform administrators cannot approve bank financial transactions unless explicitly granted scoped bank duties subject to independent checker approval. | `BankDutyServiceTest`, `TenantAdministrationIntegrationTest` |
| **Elevation of Privilege** | Direct privilege escalation via DDL execution in application context. | Critical | Application database user (`cbs_app`) has only `SELECT, INSERT, UPDATE, DELETE` on data tables and sequence usage. `CREATE, ALTER, DROP` permissions are granted strictly to the offline migration user (`cbs_migrate`). | `RuntimeDatabaseGuardIntegrationTest`, `ProductionGuard` |

---

## 5. OWASP Top 10 API Security Mitigations

1. **Broken Object Level Authorization (BOLA):**
   - Every API call validating an account, loan, contract, or transaction explicitly verifies tenant scoping (`tenant_id = ?`). Attempting to access an ID belonging to another tenant returns HTTP 404 / 403.
2. **Broken Authentication:**
   - Identity management delegated to Keycloak 26.7.4 with strict TOTP MFA enforcement. Development identity bypasses and local authentication tokens are strictly barred by `ProductionGuard`.
3. **Broken Object Property Level Authorization:**
   - DTOs and command models enforce strict deserialization schemas. Unrecognized or unexpected JSON fields are rejected by Jackson.
4. **Unrestricted Resource Consumption:**
   - Pagination enforced on all directory queries (`limit` capped to 100). Servicing calculations run in chunked batches (default 250 items). Delivery worker has a 20-second time budget.
5. **Broken Function Level Authorization:**
   - Method security checks `@PreAuthorize` and programmatic duty checks verify user role permissions against the active bank branch context before executing operations.
6. **Unrestricted Access to Sensitive Business Flows:**
   - Idempotency keys (`Idempotency-Key` header) required for all state-changing financial mutations. Replayed requests return identical response without executing redundant mutations.
7. **Server-Side Request Forgery (SSRF):**
   - Outbound HTTP requests (e.g. M-Pesa Daraja, SMS providers) strictly validate destination URLs against configured allowlists with enforced HTTPS transport.
8. **Security Misconfiguration:**
   - `ProductionGuard` validates 14 critical security invariants at runtime startup, failing closed if any setting violates production hardening standards.
9. **Improper Inventory Management:**
   - Strict versioning of APIs (`/admin/tenants/{tenant}/...`); deprecated routes decommissioned; staging preflight validates API contracts.
10. **Unsafe Consumption of APIs:**
    - External payment webhook payloads (Daraja callbacks) require HMAC-SHA256 signature verification and payload timestamp validation within 300 seconds to prevent replay attacks.

---

## 6. Cryptographic Implementation and Key Management

- **Field encryption (identity numbers):** `PersonalDataCipher` seals with AES-256-GCM under a per-bank key derived from `cbs.privacy.field-key`. The IV is an HMAC of the value, so sealing is deterministic on purpose (byte-identical payload checks), and a keyed blind index serves duplicate checks.
- **KYC documents:** AES-256-GCM with a random 96-bit IV, under a per-bank key derived from the same field key with its own purpose label (`PersonalDataCipher.documentKey`). Bank, customer and document are bound as associated data. Before P-10 the key was derived from a constant in the source code (finding S-01); those rows are re-encrypted, and new rows under the old key are refused by V56.
- **Secret Sharing & Escrow:** Shamir's threshold secret sharing ($k=2, n=3$) over Galois Field $GF(p)$ with prime $p = 2^{256} + 297$ (`ops/key_escrow.py`).
- **WAL Archive Encryption:** AES-256-GCM continuous WAL streaming encryption with HMAC-SHA256 segment envelopes (`ops/wal_archive.py`).
- **Token Signing & Verification:** RSA-3072 / RS256 asymmetric keys managed in Keycloak, with scheduled rotation and grace-period retirement via `ops/identity_admin.py`.
- **Transport Security:** HTTPS at the Caddy edge, and certificate-verified database connections (`sslmode=verify-full`, enforced by `ProductionGuard` and the staging preflight). The minimum TLS version is not pinned in configuration; record the negotiated version on the staging host and pin it if the bank requires it.

---

## 7. Penetration Testing Rules of Engagement and Test Scenarios

The external penetration testing firm engaged for Gate E01/E03 must execute against the staging environment (`P-02` host) according to these boundaries:

### Target Scope
- **Domain:** `https://CBS_DOMAIN` (CBS Administration Portal and REST APIs).
- **Identity:** `https://IDENTITY_DOMAIN` (Keycloak realm authentication and JWKS endpoints).
- **Excluded:** Denial of Service testing against cloud ISP infrastructure, physical data center attacks, social engineering of personnel.

### Required Test Scenarios
1. **Multi-Tenant Isolation Drill:**
   - Authenticate as Tenant A staff member. Attempt direct access to Tenant B accounts, loan contracts, customer profiles, and journals using valid IDs.
   - *Pass criteria:* 100% rejection with HTTP 404/403; zero data disclosure.
2. **Maker-Checker Bypass Attempt:**
   - Submit a financial operation (e.g. loan restructuring or payment reversal). Attempt to approve the operation using the same account token, an expired token, or a non-checker role token.
   - *Pass criteria:* HTTP 400 `CANNOT_APPROVE_OWN_REQUEST` or HTTP 403 `DUTY_NOT_PERMITTED`.
3. **JWT Tampering and Algorithm Confusion:**
   - Alter JWT payload claims (`roles: ["PLATFORM_ADMIN"]`, `sub: "admin"`), re-sign with arbitrary key, or change algorithm header to `none` or `HS256`.
   - *Pass criteria:* Immediate token rejection with HTTP 401 Unauthorized.
4. **SQL & Formula Injection Fuzzing:**
   - Submit SQL injection payloads into all search filters, pagination offsets, and text fields. Export search results to CSV and inspect for formula execution triggers.
   - *Pass criteria:* Zero SQL syntax errors or unexpected behavior; all CSV output cells sanitized.
5. **Replay and Concurrency Attack:**
   - Concurrently submit multiple debit or transfer requests using identical and distinct idempotency keys.
   - *Pass criteria:* Exactly one transaction executes; account balance never overdrawn below authorized limit; zero double-spend.

---

## 8. Verification and Compliance Status

- **Automated Security Verification:**
  - `ProductionGuardTest` (10 tests, 100% pass) validates rejection of insecure configurations.
  - `ops/tests/test_staging_preflight.py` (8 tests, 100% pass) validates staging isolation.
  - `ops/tests/test_identity_realm.py` & `test_identity_admin.py` (19 tests, 100% pass) validate Keycloak hardening.
  - `TenantAdministrationIntegrationTest` validates tenant isolation, maker-checker, and MFA enforcement. Note: from Round 15 until the P-10 repairs, this suite could not run (the Spring context failed to start), so it did not protect Rounds 15 to 19; see section 9.
- **Pre-Penetration Review Sign-Off:**
  - Architecture and controls reviewed against CBK Prudential Guidelines, GDPR/Data Protection Act statutory retention, and OWASP API Security Top 10.
  - Runtime dependencies: no Maven dependency scan has run yet (Phase 0.7), so no claim about dependency vulnerabilities is made.
  - Application code: the internal review in section 9 found three high-severity issues and several medium ones. All high findings are fixed on `p10-security`; see the table for status.

---

## 9. Internal code review (14 November 2026)

Line-by-line review of the security boundaries against `round-12-deposit-lifecycle` (Rounds 1-19, P-01 to P-09, P-03). It covered:
- the public callbacks
- the maker-checker coverage of every write endpoint
- the encryption of KYC data
- evidence immutability
- SQL construction
- CSV export
- bank-duty enforcement
- the newer modules from Rounds 12, 15, 16 and 19

Fixes are on branch `p10-security`, each with unit and integration tests.

| ID | Severity | Finding | Status |
| --- | --- | --- | --- |
| S-01 | High | KYC document key derived from a constant in the source code plus the bank ID: a database or backup copy alone exposed every document. | Fixed: key derived from the field key, ciphertext bound to bank, customer and document; legacy rows re-encrypted; new legacy-key rows refused (V56). |
| S-09 | High | Data-migration "commit customers" wrote ACTIVE customers straight into the registry. There was no approval, no screening, no audit and no delivery to the bank database, and identity numbers were stored in plaintext. | Fixed (fails closed): commit refused until a batch path submits each customer through onboarding approval. Staged copies sealed. |
| S-10 | High | Cutover reconciliation compared the legacy total with the staged import file itself, summed across currencies, and recorded the result as immutable evidence, so it could never find a difference. | Fixed: one currency at a time, against posted balances in the bank's own ledger. |
| S-02 | Medium | KYC reviews (risk rating, next due date) and document archiving applied directly; review dates could be backdated without limit. | Fixed: approval actions `KYC_REVIEW` and `CUSTOMER_DOCUMENT_ARCHIVE`; at most 30 days back. |
| S-07 | Medium | Every valid public M-Pesa callback bumped the bank version, making all pending maker requests for that bank stale. | Fixed: callbacks no longer bump it. |
| S-08 | Medium | KYC reviews and documents had no immutability triggers. | Fixed (V56). |
| S-12 | Medium (latent) | Term-deposit maturity instructions: one administrator, any target account, any renewal rate, no audit. Nothing executes them yet. | Fixed: approval action `TERM_STANDING_INSTRUCTION`; proceeds only to the depositor's own account in the same currency; bounded renewals; append-only. |
| S-13 | Medium | Loan moratoria (which suspend arrears ageing and due notices) granted by one person. | Fixed: approval action `LOAN_MORATORIUM`, governed like a restructure (credit officer submits, a different credit approver approves within limits). |
| S-03 | Low | KYC download served the stored, user-supplied content type and an unescaped file name. | Fixed: PDF/PNG/JPEG/TIFF only, sanitised names, RFC 6266 header, `nosniff`. |
| S-04 | Low | Document errors echoed exception messages. | Fixed. |
| S-14 | Low | FX rate feed approval has its own maker-checker that compares the subject but not the issuer, and bypasses the approval inbox and audit trail. | Open: production trusts one issuer, so not exploitable today; move to an approval action before a second identity provider is trusted. |
| S-15 | Low | KYC documents are stored in the control database, not in the bank's own database. | Open (design decision for the bank: isolation and data residency). |
| S-16 | Low | The portal's KYC download is a plain link, so it carries no bearer token and fails with 401. | Open (functional): download through the API client. |

Defects found during the review (functional, not security) and fixed on the same branch:
- The application context failed to start from Round 15 onwards, because `LoanServicingService` had two constructors and neither was marked `@Autowired`. Every database integration test failed, so the regressions below went undetected.
- Bank migration V29 dropped `RESTRUCTURE`, `IMPAIR`, `WRITE_OFF` and the loan fee kinds from the contract event constraint. Those postings were refused, and V29 would fail on any bank that already holds them. It was corrected in place, since it had not been released.
- The credit application detail query was missing a parameter, so every view failed.
- Data-migration account staging queried a table that does not exist.
- A second standing instruction for a deposit always failed, because the service updated a row that an immutability trigger protects.
- Payment statements were bounded by today in UTC, so East African banks could not record same-day evidence from 00:00 to 03:00 local time, or over weekends.

Checked with no finding:
- **Notification receipt webhook:** HMAC-SHA256 over the timestamp and body, constant-time comparison, a 5-minute window, and 404 when no secret is set.
- **M-Pesa callback:** a 64-hex token compared by hash in constant time, body and observation caps, and strict parsing.
- **Concatenated SQL:** constant fragments with bound values (about 30 sites sampled).
- **CSV exports:** formula injection is neutralised.
- **Bank duties:** default-deny for unmapped routes, checked by `BankDutyRoutesTest`.
- **Notification codes:** stored hashed, with expiry and a 5-attempt lockout.

What the external penetration test should focus on:
- the maker-checker side paths (bulk import, lending, KYC)
- cross-bank and cross-customer IDs in URLs
- callback replay and forgery
- active content in uploads
- expired, revoked or non-MFA tokens
- edge exposure of the Keycloak admin console, the master realm, the actuator and metrics
- body-size and rate limits
