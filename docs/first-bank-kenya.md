# First bank: Demo Bank Kenya

Reference pilot bank configuration for CBS Kenya deployment, updated 14 November 2026.
Baseline: Control Schema **V56**, Bank Schema **V29**.

## 1. Reference Specification

| Field | Value | Notes |
| --- | --- | --- |
| Legal entity & display name | Demo Bank Kenya | Registered commercial bank reference entity |
| Bank code | `DEMO_KE` | Unique alphanumeric tenant identifier |
| Jurisdiction / Country | `KE` (Kenya) | Governed by CBK Prudential Guidelines & KRA tax rules |
| Functional currency | `KES` | Base pivot currency for subledger and FX operations |
| Default language | `en` (English) | English default with Swahili (`sw`) localization supported |
| Business timezone | `Africa/Nairobi` (EAT, UTC+3) | Pinned business calendar and EOD processing zone |
| Approved database reference | `demo-bank-kenya` | Logical binding key in application config |
| Dedicated database | `cbs_demo_kenya` | Isolated database instance / schema boundary |
| Dedicated runtime login | `cbs_demo_kenya_owner` | Restricted non-superuser role with least privilege |
| Control schema version | **V56** | `V56__kyc_evidence_immutability.sql` |
| Bank schema version | **V29** | `V29__fx_and_term_deposit_completion.sql` |

---

## 2. Security and Isolation Architecture

1. **Schema and Ownership Boundary:**
   - Dedicated `cbs_demo_kenya` database ensures complete physical separation from the `cbs_control` platform database.
   - Database login `cbs_demo_kenya_owner` has no superuser, database creation, role creation, or replication privileges.
   - PUBLIC connection and temporary object permissions are explicitly revoked.
   - Cross-database access to `cbs_control.platform.*` is refused at the Postgres connection and schema boundaries.
2. **Session Advisory Locking & Identity Reservation:**
   - Provisioning takes exclusive Postgres session advisory lock `1128420179` during migration execution.
   - Table `cbs_identity.owner (singleton BOOLEAN PRIMARY KEY CHECK(singleton), tenant_id UUID NOT NULL)` locks the database instance to Demo Bank Kenya's tenant UUID.
   - Any attempt to point another tenant to this database is strictly refused (`TENANT_DATABASE_MISCONFIGURED`).
3. **Transport Security:**
   - In production/staging, database connections strictly require TLS 1.3 with full verification (`sslmode=verify-full`) and channel binding (`channelBinding=require`).

---

## 3. Provisioning Workflows

### Method A: Automated / Offline Provisioning via `MigrationMain` (Recommended for Staging & Production)

Run the offline migration tool to migrate control schema to V56, register the first administrator, and roll out bank schema V29 to Demo Bank Kenya:

```bash
# 1. Prepare plan JSON (plan.json)
cat <<EOF > plan.json
{
  "control": {
    "url": "jdbc:postgresql://db.cbs.internal:5432/cbs_control?sslmode=verify-full",
    "username": "cbs_control_owner",
    "passwordFile": "/run/secrets/control_db_password",
    "tenantId": null
  },
  "banks": {
    "demo-bank-kenya": {
      "url": "jdbc:postgresql://db.cbs.internal:5432/cbs_demo_kenya?sslmode=verify-full",
      "username": "cbs_demo_kenya_owner",
      "passwordFile": "/run/secrets/demo_bank_db_password",
      "tenantId": "00000000-0000-0000-0000-000000000001"
    }
  },
  "firstAdministrator": {
    "issuer": "https://identity.bank.co.ke/realms/cbs",
    "subject": "admin-bootstrap-subject",
    "displayName": "Platform Operations Administrator",
    "email": "cbs-admin@bank.co.ke"
  }
}
EOF

# 2. Execute migration job
java -cp target/cbs-platform-0.1.0-SNAPSHOT.jar com.cbs.operations.MigrationMain plan.json --stop-on-failure --report migration-report.json
```

### Method B: Interactive Registration via CBS Administration Portal

1. Authenticate to the CBS Administration Portal (`https://<cbs-domain>/admin/index.html`) using MFA credentials.
2. In **Bank directory**, select **Register bank**:
   - Bank Code: `DEMO_KE`
   - Bank Name: `Demo Bank Kenya`
   - Default Language: `en`
   - Database Reference: `demo-bank-kenya`
   - Legal Entity: `Demo Bank Kenya`, Country `KE`, Functional Currency `KES`, Timezone `Africa/Nairobi`
3. Click **Register**. The tenant transitions to status `REQUESTED` with audit event `TENANT_REGISTERED`.
4. Open the created tenant and click **Provision**:
   - Provide change reason: `Initial Demo Bank Kenya development setup`.
   - The system acquires advisory lock `1128420179`, applies Flyway migrations V1 through V29 in the `bank` schema, registers ownership, and sets status to `ACTIVE`.
   - Schema version in `platform.database_binding` records `29`.
   - Audit trail records `PROVISIONING_STARTED` and `PROVISIONING_SUCCEEDED`.

---

## 4. Audit Trail and Lifecycle Invariants

The complete tenant lifecycle records an append-only, tamper-evident audit history in `platform.tenant_audit`:

| Timestamp | Action | From Status | To Status | Version | Actor | Reason / Details |
| --- | --- | --- | --- | --- | --- | --- |
| $T_0$ | `TENANT_REGISTERED` | `null` | `REQUESTED` | 0 | `administrator` | Initial tenant registration with legal entity |
| $T_1$ | `PROVISIONING_STARTED` | `REQUESTED` | `PROVISIONING` | 1 | `administrator` | Lease acquired, advisory lock taken |
| $T_2$ | `PROVISIONING_SUCCEEDED`| `PROVISIONING`| `ACTIVE` | 2 | `administrator` | Migrations V1..V29 applied; schema version = 29 |

---

## 5. Automated Verification Evidence

- **Automated Regression Suite:**
  - `TenantAdministrationIntegrationTest.demoBankKenyaRegistrationAndProvisioningCompletesOnCurrentSchema`:
    - Registers `DEMO_KE` with legal entity `KE`/`KES`/`Africa/Nairobi`.
    - Verifies initial status `REQUESTED`.
    - Provisions database binding and executes Flyway V1..V29.
    - Asserts resulting status `ACTIVE` and schema version `29`.
    - Asserts full audit trail (`TENANT_REGISTERED`, `PROVISIONING_STARTED`, `PROVISIONING_SUCCEEDED`).
- **Post-Provisioning Readiness:**
  - Core banking services (customer onboarding, account creation, teller cash, daily accruals, loans, FX) ready on verified baseline.
