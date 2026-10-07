# SBI development database

Created in the existing Neon development project on 2026-09-23 with the user's approval.

| Setting | Value |
| --- | --- |
| Bank code to register | `SBI_01` |
| Approved database reference | `sbi-01-db` |
| Dedicated database | `cbs_sbi_01` |
| Dedicated login | `cbs_sbi_01_owner` |
| Country / functional currency | `IN` / `INR` |
| Default language / timezone | `en-IN` / `Asia/Kolkata` |

The local application loads this reference from ignored `.local/bank-databases.yml`.
Credentials are saved in ignored `.local/sbi-01.json`. The binding uses the direct
Neon endpoint with TLS and required channel binding. Existing database bindings
are retained.

The dedicated login has no superuser, role-creation, database-creation or replication
privileges. PUBLIC connection and temporary-object permissions were revoked only
for this new database. A schema creation and write probe succeeded and was rolled
back. Access to the control database's `platform.tenant` table was denied.

## Finish in CBS

1. Retry **Register bank** with the exact reference `sbi-01-db` and bank code `SBI_01`.
2. Open the registered bank and choose **Provision**, supplying a development setup reason.
3. Confirm that the bank becomes ACTIVE and that provisioning appears in its audit history.

Database creation alone does not register or activate a bank. Registration and
bank schema provisioning still need to be completed through the authenticated CBS
workflow; this setup did not insert tenant records or apply bank migrations directly.
