# Deposit Lifecycle Completion (Round 12)

**Scope & Governance:** Acceptance Matrix Rows F11, F12, F14, F20, F21  
**Migrations:** Control `V48__deposit_lifecycle_completion.sql`, Bank `V27__deposit_lifecycle.sql`  
**Applicability:** Core Banking System (CBS) - Deposit Servicing & Customer Lifecycle  

---

## 1. Executive Summary

Round 12 delivers production-grade deposit and customer lifecycle capabilities required for CBS go-live:
1. **Encrypted KYC Document Object Storage**: Moving from reference-only metadata to tamper-resistant, AES-256-GCM encrypted document payloads with per-tenant key derivation, SHA-256 checksum verification, and statutory retention enforcement.
2. **Risk-Based KYC Periodic Reviews**: Scheduling and monitoring review cycles according to AML risk ratings (`HIGH`: 1 year, `MEDIUM`: 2 years, `LOW`: 3 years), with overdue detection and validation against future timestamps.
3. **Account Dormancy & Unclaimed Assets (D14)**: Formal `DORMANT` state lifecycle, fail-closed operation blocking (`ACCOUNT_DORMANT`), reactivation via maker-checker approvals, and administrative monitoring for dormancy and escheatment candidates.
4. **Deceased Customer Workflow**: Fail-closed operational handling for customer mortality (`DECEASED` status), blocking all debit operations while maintaining auditability and terminal state immutability.
5. **Contract & Payment Aware Account Closure**: Unblocking account closure for accounts possessing historical closed contracts or settled payment orders, and adding an immutable **Closure Settlement Reversal** (`CLOSURE_SETTLEMENT_REVERSAL`) procedure with maker-checker controls.

---

## 2. Technical Architecture & Security Controls

### 2.1. Encrypted KYC Document Storage (`CustomerDocumentService`)
- **Encryption Scheme**: AES-256 in Galois/Counter Mode (GCM) with 128-bit authentication tags and unique 12-byte initialization vectors (IVs) per file.
- **Key Derivation**: Tenant-isolated keys derived using HMAC-SHA256 (`HmacSHA256(KEY_SALT, bankId)`).
- **Integrity**: Plaintext SHA-256 hash calculated prior to encryption and stored alongside the ciphertext. On download, decrypted data is hashed and compared against the stored digest; any discrepancy triggers `DOCUMENT_INTEGRITY_COMPROMISED`.
- **Retention Enforcement**: Retention periods default to 7 years. Purging or archiving prior to expiration date is strictly rejected with `DOCUMENT_RETENTION_ACTIVE`.
- **Audit Logging**: Every upload, download, and archive operation emits an immutable audit event (`CUSTOMER_DOCUMENT_UPLOADED`, `CUSTOMER_DOCUMENT_DOWNLOADED`, `CUSTOMER_DOCUMENT_ARCHIVED`).

### 2.2. Risk-Based KYC Reviews
- **Scheduling Intervals**:
  - `HIGH`: 1 year (12 months)
  - `MEDIUM`: 2 years (24 months)
  - `LOW`: 3 years (36 months)
- **Validation**: Review timestamps in the future are rejected with `REVIEW_DATE_FUTURE`.
- **Overdue Monitoring**: `platform.customer_kyc_review` queried with `DISTINCT ON (customer_id)` to track current compliance status and alert on overdue reviews.

### 2.3. Account Dormancy Lifecycle (D14)
- **State Transition**: `ACTIVE -> DORMANT` executed via approved change request.
- **Fail-Closed Operations**: `OperationService` checks account status; any debit, withdrawal, or transfer attempt on a dormant account is denied with `ACCOUNT_DORMANT`.
- **Reactivation**: Reactivation requires administrative review and maker-checker dual authorization.
- **Dormancy Candidates & Unclaimed Assets**: System queries accounts inactive past specified threshold days (`/dormancy-candidates` and `/unclaimed-assets`).

### 2.4. Deceased Customer Workflow
- **Immutability**: Once marked `DECEASED`, a customer record cannot transition back to active (`CUSTOMER_ALREADY_DECEASED`).
- **Eligibility Barrier**: `CustomerService.checkEligible()` immediately throws `CUSTOMER_DECEASED`.
- **Operation Enforcement**: All operations invoking `customers.requireEligible()` fail-closed.

### 2.5. Account Closure & Settlement Reversal
- **Closure Unblocking**:
  - `platform.contract`: Checked with `status <> 'CLOSED'`. Accounts with fully closed contracts proceed to closure without blocker `ACCOUNT_CLOSURE_CONTRACT_REVIEW`.
  - `platform.payment_order`: Checked with `state NOT IN ('SETTLED', 'REFUNDED')`. Accounts with settled payments proceed without blocker `ACCOUNT_CLOSURE_PAYMENT_REVIEW`.
  - Database trigger `bank.guard_account_closure()` inspects contract payloads and non-zero balances to permit closed contracts.
- **Closure Settlement Reversal**:
  - Implemented as an explicit operation kind: `CLOSURE_SETTLEMENT_REVERSAL`.
  - Admin duty and dual authorization required.
  - Automatically reverses the settlement journal lines, restores customer position, and cancels posting locks.

---

## 3. Migration Reference

### Control Migration `V48__deposit_lifecycle_completion.sql`
- Updates `platform.account_registry.state` check to include `'DORMANT'`.
- Adds `'CLOSURE_SETTLEMENT_REVERSAL'` to `platform.transaction_limit.operation`.
- Creates `platform.customer_document` (encrypted payload, IV, SHA-256, retention, audit metadata).
- Creates `platform.customer_kyc_review` (risk rating, review dates, reviewer reference).

### Bank Migration `V27__deposit_lifecycle.sql`
- Updates `bank.account` payload state check to include `'DORMANT'`.
- Updates `bank.account_operation` kind check to include `'CLOSURE_SETTLEMENT_REVERSAL'`.
- Creates `bank.closure_settlement_reversal` table.
- Updates `bank.guard_account_closure()` to verify non-zero contract balances instead of naive existence.
- Updates `bank.check_account_operation()` to credit customer accounts on settlement reversals.
- Updates `bank.guard_closed_account_posting()` to allow operations when settlement is reversed.
