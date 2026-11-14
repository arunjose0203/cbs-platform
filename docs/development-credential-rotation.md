# Development Credential Rotation and Secret Hygiene

**Gate:** E03 | **Task:** 0.6 | **Status:** DELIVERED & AUDITED  
**Date:** 14 November 2026 | **Baseline:** Control V56, Bank V29

---

## 1. Executive Summary and Scope

Task 0.6 of the [go-live plan](go-live-plan-2026-11-13.md) mandates that all development credentials, shared access tokens, test certificates, and environment secrets ever utilized during local development are formally audited, rotated, or revoked prior to staging and production activation (Gate E03).

This policy establishes strict separation between ephemeral local development fixtures, automated test stubs, and production secrets. Under no circumstances may credentials stored in `.local/` or referenced in development documentation be deployed to staging or production infrastructure.

---

## 2. Credential Inventory and Rotation Audit Log

| Category | Development Secret | Current Status | Staging / Production Architecture | Rotation Mechanism |
| --- | --- | --- | --- | --- |
| **Control Database** | `cbs_control` / `cbs-local-dev` | **REVOKED for non-local**; confined to local loopback `.local/` | Split roles: `cbs_control_owner` (migrations) & `cbs_control_runtime` (DML only); injected via `/run/secrets/control_db_password` | Managed via cloud secret store (AWS Secrets Manager / GCP Secret Manager); 90-day cycle |
| **Tenant Database (Demo Bank Kenya)** | `cbs_demo_kenya_owner` (Neon development) | **ROTATED**; credentials in `.local/demo-bank-kenya.json` invalidated for staging | Separate tenant database user with `sslmode=verify-full` and `channelBinding=require`; injected via `/run/secrets/demo_bank_db_password` | Staging credentials provisioned independently per tenant; 90-day rotation |
| **Keycloak Identity Signing Key** | Development RSA key in local H2 storage | **ISOLATED to dev loopback** | Production Keycloak on PostgreSQL using RSA-3072 / RS256; realm `cbs` with enforced TOTP MFA | `ops/identity_admin.py rotate-key` with 45-min passive grace period and dual-approval |
| **Keycloak Admin / Master Realm** | Local admin credentials in `.local/identity-credentials.json` | **REVOKED for production**; admin console blocked at edge | Master realm and admin console accessible only via SSH bastion tunnel; operator identity disabled by default | Dedicated per-operator Keycloak admin accounts with physical MFA tokens |
| **CBS Admin Bootstrap** | `FIRST-LOGIN.txt` temporary password | **EXPIRED**; `ProductionGuard` refuses bootstrap admin | `FirstAdministrator` registered offline and audited in `platform.staff_identity` via `MigrationMain` | One-time bootstrap; subsequent staff invited through CBS Admin Portal with required TOTP |
| **Safaricom Daraja M-Pesa** | Daraja Sandbox consumer key/secret/passkey | **CONFINED to sandbox**; live dispatch blocked | Production Daraja credentials injected into `/run/secrets/mpesa_consumer_key` and `/run/secrets/mpesa_consumer_secret` upon bank contract (D09) | Rotated annually or immediately upon provider recommendation |
| **Notification Webhook Secret** | `cbs.notifications.callback-secret` (dev default) | **REVOKED** | Cryptographically random 256-bit hexadecimal string injected via `/run/secrets/notification_callback_secret` | Rotated semi-annually with dual-verification |
| **Key Escrow & Backup Encryption** | Development mock keys | **REVOKED** | Shamir's 2-of-3 threshold split ($k=2, n=3$) over $GF(2^{256} + 297)$; independent key holders | Re-keyed per backup epoch and disaster recovery drill |

---

## 3. Git Repository History and Secret Scan

1. **Commit History Audit:**
   - Git commit history was audited across all branches for accidental credential leaks.
   - No plaintext passwords, private keys, or API tokens were found committed in code files.
   - All secret templates (`runtime.example.yml`, `cbs-production-realm.json`) use placeholder syntax (`CHANGE_ME`, `REPLACE_WITH_*`).
2. **Ignored Local Paths (`.gitignore`):**
   - The `.gitignore` policy strictly excludes `.local/`, `.tools/`, `*.log`, `*.dump`, and `credentials.json`.
   - Temporary setup files (`claude-setup.zip`, inspected and confirmed clean of credentials) are flagged for permanent removal on master branch consolidation.
3. **Automated CI Secret Scanning:**
   - GitHub Actions `security.yml` runs OSV-Scanner, trufflehog / secret linting, and dependency vulnerability scans on every pull request and weekly schedule.

---

## 4. Key Rotation Procedures and Runbooks

### 4.1. Keycloak RS256/3072 Token Signing Key Rotation

Token signing keys must be rotated every 90 days using `ops/identity_admin.py`:

```bash
# 1. Generate and activate new RSA-3072 signing key while retaining previous key as PASSIVE
python ops/identity_admin.py rotate-key \
  --admin-url https://identity.bank.co.ke \
  --realm cbs \
  --admin-username keycloak-admin \
  --admin-password-file /run/secrets/keycloak_admin_password \
  --key-size 3072

# 2. Verify both keys appear in JWKS endpoint
curl -s https://identity.bank.co.ke/realms/cbs/protocol/openid-connect/certs | jq '.keys[].kid'

# 3. Wait for grace period (minimum 45 minutes to allow in-flight JWTs to expire)
# 4. Retire passive keys
python ops/identity_admin.py retire-keys \
  --admin-url https://identity.bank.co.ke \
  --realm cbs \
  --admin-username keycloak-admin \
  --admin-password-file /run/secrets/keycloak_admin_password \
  --max-age-hours 1
```

### 4.2. Database Credential Rotation (Zero-Downtime)

1. Provision second database login with identical permissions (e.g., `cbs_control_runtime_v2`).
2. Update `/run/secrets/control_db_password` and `/run/secrets/control_db_username`.
3. Perform rolling restart of CBS application instances (`docker compose up -d --no-deps cbs`).
4. Validate health probe (`/health/ready`) returns `200 OK`.
5. Revoke connection privileges and drop previous database login (`cbs_control_runtime_v1`).

---

## 5. Verification Sign-Off

- **Gate E03 Compliance:** Development credentials isolated; production profile strictly enforces verified TLS (`sslmode=verify-full`), rejects dev realms (`cbs-dev`), and disables runtime bootstrap users.
- **Audit Logging:** All credential updates and key rotations generate immutable records in Keycloak admin events and `platform.identity_audit`.
