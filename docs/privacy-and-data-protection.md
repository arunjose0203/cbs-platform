# Privacy and Data Protection (Round 18)

**Scope & Governance:** Kenya Data Protection Act 2019, POCAMLA s.46, Gate E04  
**Migrations:** Control `V49__privacy_and_data_protection.sql`  
**Dependencies:** Builds upon Round 12 (`V48__deposit_lifecycle_completion.sql`, `V27__deposit_lifecycle.sql`)  

---

## 1. Executive Summary

Round 18 implements the data protection and privacy controls required for CBS production readiness:
1. **Field-Level Identifier Protection (`PersonalDataCipher`)**: Customer identity document numbers are sealed with deterministic AES-256-GCM under tenant-isolated derived keys (`pd1:` tokens) and indexed via keyed blind HMAC indices (`bi1:` tokens). Plaintext numbers are never persisted in approval requests, delivery outboxes, registries, or bank schemas.
2. **Statutory Retention Matrix & Archival Review**: Seven statutory retention categories aligned with POCAMLA s.46, the Data Protection Act 2019, and CBK Prudential Guidelines. Automatic detection of archival review candidates.
3. **Legal Holds**: Dual-controlled (maker-checker) legal holds (`platform.legal_hold`) preventing data alteration or disposal under judicial orders.
4. **Data-Subject Rights Management**: A structured register for data-subject requests (`ACCESS`, `RECTIFICATION`, `ERASURE`, `OBJECTION`, `PORTABILITY`) with configurable statutory response deadlines (default 7 days).
5. **Subject-Access Report Export**: Automated generation of data-subject disclosure reports, purposefully excluding AML screening and suspicious transaction investigations to prevent unlawful tipping-off.
6. **Erasure Assessment**: Systematic blocker evaluation (`LEGAL_HOLD`, `RELATIONSHIP_OPEN`, `COMPLIANCE_CASE_OPEN`, `RETENTION_PERIOD`) ensuring data is retained as long as required by financial regulations and no longer.
7. **Customer Record View Auditing**: Dedicated tracking and audit logging (`CUSTOMER_PROFILE_VIEWED`, `CUSTOMER_VIEW_LOG_READ`) whenever a customer's personal data is accessed by staff.

---

## 2. Technical Architecture & Security Controls

### 2.1. Field-Level Encryption & Blind Indexing
- **Cipher**: AES-256 in Galois/Counter Mode (GCM).
- **Deterministic Tokenization**: The IV is derived from HMAC-SHA256 of the plaintext, ensuring identical inputs produce identical ciphertext tokens (`pd1:...`). This preserves byte-identical payload validation across retries, bank receipts, and platform-bank reconciliation without revealing the underlying data.
- **Key Hierarchy**: Master field key configured via `cbs.privacy.field-key` (Base64 of 32 random bytes). Tenant keys are derived as `HmacSHA256(masterKey, "cbs-pd1:" + purpose + ":" + bankId)`. Production refuses launch if the development key or an insecure key is detected (`ProductionGuard`).
- **Keyed Blind Indexing**: Uniqueness and duplicate-document checks use HMAC blind indexes (`bi1:...`) rather than storing the document number directly in `document_key`. Older plaintext entries remain supported via dual-match query logic.

### 2.2. Legal Holds
- **Immutability & Approval**: Enacted via `LEGAL_HOLD` approval action in `ApprovalService`.
- **Enforcement**: Active holds immediately block erasure requests (`LEGAL_HOLD` blocker) and freeze records from archiving.

### 2.3. Data-Subject Request Register
- **SLA Tracking**: Each request receives a `due_on` deadline based on statutory allowances (e.g. 7 days).
- **Completion Safeguards**: Access and portability requests cannot be marked `COMPLETED` without an exported subject-access report (`PRIVACY_REPORT_NOT_EXPORTED`).
- **Erasure Guard**: Direct completion of erasure requests is prohibited (`PRIVACY_ERASURE_NOT_AVAILABLE`); retention rules and ledger immutability are preserved.

### 2.4. Statutory Retention Rules

| Category | Scope | Period | Trigger | Legal Basis |
|---|---|---|---|---|
| Customer identification (KYC) | Profile, document number/ref, parties, mandates | 7 years | End of customer relationship | POCAMLA s.46; DPA 2019 s.39 |
| Transactions and accounting | Accounts, ledger/journal entries, operations, payments | 7 years | Transaction date | POCAMLA s.46; Tax Procedures Act |
| AML screening & monitoring | Screening hits, monitoring alerts, cases, STR drafts | 7 years | Case closure | POCAMLA s.46; FRC guidance |
| Audit and access logs | Tenant audit, approval history, view logs | 7 years | Event timestamp | CBK Prudential Guidelines; DPA s.41 |
| Customer notifications | Alert destination & outbox text | 2 years | Dispatch date | DPA 2019 s.25(e) storage limitation |
| Data-subject requests | Request register and closing notes | 3 years | Request closure | DPA 2019 s.41 accountability |
| Staff sessions | Portal sessions and sign-in records | 1 year | Session end | DPA 2019 s.25(e) |

---

## 3. Database Migration Reference

### Control Migration `V49__privacy_and_data_protection.sql`
- Appends `LEGAL_HOLD` to `platform.approval_request.action` check constraint.
- Creates `platform.legal_hold` with foreign key to `platform.customer_registry(tenant_id, id)`.
- Creates `platform.data_subject_request` with indexes on open deadlines and customer references.
