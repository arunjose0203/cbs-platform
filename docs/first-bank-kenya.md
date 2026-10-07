# First bank: Demo Bank Kenya

Development setup selected by the user on 2026-09-22:

| Field | Value |
| --- | --- |
| Display and legal entity name | Demo Bank Kenya |
| Bank code | DEMO_KE |
| Country | KE (Kenya) |
| Functional currency | KES |
| Default language | en (English) |
| Business timezone | Africa/Nairobi |
| Approved database reference | demo-bank-kenya |
| Separate Neon database | cbs_demo_kenya |
| Dedicated database login | cbs_demo_kenya_owner |

## Current status

The separate database and restricted login have been created. The local CBS
application loads the binding from ignored `.local/bank-databases.yml`. Credentials
are in ignored `.local/demo-bank-kenya.json`; the registration request, without
credentials, is saved in `.local/demo-bank-kenya-registration.json`.

Bank registration and provisioning are pending the administrator's first login.
No bank ownership marker or bank migration has been applied yet. Database creation
alone does not make this tenant ACTIVE in CBS.

## Finish registration

1. Complete CBS administrator password change and MFA enrollment as described in
   [local identity setup](local-identity.md).
2. In **Bank directory**, choose **Register bank** and use the details above.
3. Open the resulting bank and choose **Provision**, with a reason such as
   `Initial Demo Bank Kenya development setup`.
4. Confirm the bank is ACTIVE, its schema version is 2 (current release), and its audit history
   includes registration and provisioning. Provisioning reserves the database
   ownership marker and applies the bank foundation migration.

The binding uses Neon's direct endpoint with TLS and required channel binding.
Direct connections preserve the session advisory lock used during provisioning.
The existing `neondb` control database remains separate.

## Verification

- Dedicated login connects to its own database and can create/write schema objects.
  The connectivity write test was rolled back and left no probe data.
- The login has no superuser, database-creation, role-creation or replication
  privileges. PUBLIC connection and temporary-object permissions were removed
  only from the newly created bank database.
- A read of `neondb.platform.tenant` using the bank login is denied at the schema
  boundary. Existing control database connection permissions were preserved, so
  the login can establish a control-database connection but cannot read its tables.
- Two targeted bank URL configuration tests pass, and the application packages
  successfully. CBS readiness returns UP with the new binding loaded.

Registration/API authorization, provisioning success and resulting ownership
must still be checked after administrator sign-in.
