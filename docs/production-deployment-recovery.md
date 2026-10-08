# Production deployment, backup and disaster recovery

Status: implementation and local verification; **not a deployed or bank-approved production release**.

## Release prerequisites

Record the staging host, public CBS/identity DNS names, certificate contact, hosting jurisdiction, responsible operator, maintenance window, and bank-approved RPO (maximum data loss) and RTO (time to recover). Kenya, Uganda and Rwanda require separate bank decisions; no recovery target or financial limit is assumed by these templates.

Before staging acceptance, configure real staff in Staff & Access → Lending permissions. Record each identity subject, bank, branch, role, currency, operation and approval limit. Use separate authorized maker, approver, teller, collections and auditor identities. Verify MFA sign-in, self-approval rejection, cross-bank/branch denial, above-limit denial and immediate revocation. Do not extend development password-only exceptions into staging. Rotate any database password previously shared in chat and store its replacement outside Git.

## Staging installation

1. Use an independently hosted Linux server with Docker Compose, a firewall exposing only HTTPS/HTTP for certificate issuance, and restricted operator access. Keep PostgreSQL and identity administration private. This PC is not the deployment host.
2. Build the Java 21 application with `mvn verify`. Build `Dockerfile` with `JAVA_RUNTIME_IMAGE` set to a reviewed Ubuntu-based Java 21 runtime **pinned by sha256 digest**. Push the resulting image to your controlled registry; retain its digest and release commit.
3. Fill `deploy/staging/runtime.example.yml` into a private external runtime file. Supply TLS-verified direct database endpoints, distinct restricted runtime users, real HTTPS issuer/JWKS/audience/client/redirect settings. Restrict file ACLs to the container UID 10001 and administrators. Mount a trusted database CA. Do not commit this file.
4. Configure the identity provider with an exact HTTPS redirect, public client with PKCE, required MFA, correct CBS audience and required authentication claims. Back up its database and realm/deployment configuration. The Compose file does not provision an identity provider.
5. Prepare a separate migration directory from `migration.example.json`, with owner credentials in separate private password files. Create databases and distinct owner/runtime roles through the DBA process. Stop all CBS writers before migrations.
6. Set `CBS_APP_IMAGE`, `CADDY_IMAGE` (both immutable digests), `CBS_RUNTIME_CONFIG`, `CBS_DATABASE_CA`, `CBS_MIGRATION_DIRECTORY`, `CBS_DOMAIN`, `ACME_EMAIL` in a private operator environment file. Validate with `docker compose --env-file /etc/cbs/staging.env -f deploy/staging/compose.yml config --quiet`.
7. Run the migration-only service: `docker compose --env-file /etc/cbs/staging.env -f deploy/staging/compose.yml --profile tools run --rm migrate`. It runs Flyway without HTTP or workers. It checks bank ownership before applying bank migrations. New bank provisioning in production requires this operator job; runtime provisioning/upgrade requests cannot run DDL.
8. As DBA/object owner, apply `ops/runtime-privileges.sql` to the control and each bank database using `psql -v owner=OWNER_ROLE -v runtime=RUNTIME_ROLE -f ops/runtime-privileges.sql`. Use a different runtime role for each database. Reapply after every migration. The script removes PUBLIC database access; explicitly grant approved backup/operator roles CONNECT as needed. Never grant runtime membership in an owner role.
9. Start `app` and `edge` with Compose. Verify HTTPS, issuer discovery, MFA login, database role restrictions, readiness and all bank routes. The production guard rejects privileged runtime roles and migration credentials/configuration. Caddy exposes readiness through `/health/ready`; other actuator paths are blocked at the edge.

The image/Compose templates have no credential values. Digest pinning, server installation, firewall rules, certificate issuance and live identity checks must be verified on the actual host. Docker's engine was unavailable on the development PC during implementation.

## Coordinated encrypted backups

Install Python and `ops/requirements.txt` in a dedicated venv; use PostgreSQL client tools compatible with the server version. Configure `ops/config.example.json` outside Git. Inventory **every** prepared bank database, the control database and the self-hosted identity database. Include identity realm/export, deployment configuration, and necessary secret references/material according to the bank's recovery policy. Managed identity services require a provider-specific recovery process before acceptance; omitting identity is not supported by this complete-set tool.

Create a random key with `python ops/recovery.py keygen --directory /secure/cbs-backup.key`. Escrow the key separately under bank control. Protect pgpass, configuration, exports, key and reports with private permissions; Windows ACLs require operator verification. Backups contain sensitive financial data even when encrypted. Keep decrypted scratch storage on an encrypted restricted volume.

This implementation is a **cold coordinated backup**: stop CBS, posting workers, identity writers and connection pools; stop callbacks from re-entering the application. The tool obtains SHARE locks across all inventoried database tables and refuses remaining client sessions. Use dedicated maintenance credentials authorized to read and acquire those locks; this is not an ordinary read-only runtime role. It checks the complete bank inventory, bank ownership and financial consistency, then streams pg_dump archives into AES-256-GCM encryption. It records table/sequence fingerprints, ciphertext hashes and an HMAC-authenticated completion manifest. A directory without a valid completion manifest is incomplete.

Run `python ops/recovery.py backup --config /etc/cbs/backup.json --directory /encrypted/backups/UNIQUE_SET`. Do not use pooled PostgreSQL endpoints. Copy the whole completed set to an independently controlled off-host immutable store, verify receipt, and retain it according to an approved policy. **Off-host upload, retention enforcement, WAL/PITR and automatic failover are not implemented by this module.** Never delete the last known-good recovery set or its key.

`ops/backup_job.py` and `deploy/staging/systemd` provide Linux maintenance scheduling and writer restart recovery. Adjust installation paths and writer units; only listed CBS/Keycloak units are managed. The sample nightly UTC timer is disabled until the bank approves its downtime and data-loss implications. Multi-host writers require coordinated orchestration beyond this single-host example. ExecStopPost retries interrupted service restarts. Inspect persisted restart state after any crash; do not assume writers resumed successfully.

## Isolated restore drill

1. Keep outbound payment/provider access blocked. Create fresh databases named `cbs_restore_*` on an isolated host/environment. Never point restore targets at live databases. Prepare a target inventory matching names, kinds and bank UUIDs from the complete backup.
2. Set `scratch_dir` to encrypted private storage. Optionally set `recovered_files_directory` to a **new** private directory to export authenticated configuration artifacts after verification. Review/rewrite endpoints and credentials before installation; never start a recovered identity provider with live mail/payment integrations enabled.
3. Run `python ops/recovery.py restore --config /etc/cbs/backup.json --directory /encrypted/backups/SET --targets /etc/cbs/restore-targets.json --report /secure/restore-UNIQUE.json`.
4. The tool authenticates all archives before any restore, refuses occupied/non-empty targets, restores transactionally per database without overwriting existing objects, compares all table and sequence fingerprints, checks bank ownership, balanced journals, ledger/customer balances and pending journal deliveries. A failure can leave some isolated databases restored; keep them isolated, create a fresh target set, and investigate. No automatic promotion occurs.
5. Pending requests already posted at the bank but not acknowledged in control are preserved, not blindly replayed. Test worker idempotency in isolation with real application workflows before promotion. The signed report is technical restore evidence, not bank acceptance.
6. Reapply restricted runtime grants because dumps intentionally exclude ownership and grants. Test actual role boundaries, MFA, approvals, balances, journals, audit, queues and day-close with the restored application. Measure the entire outage-to-service interval against the bank's RTO; the tool's restore duration covers only the restore procedure, not business recovery.

Local drill: `ops/tests/recovery_drill.py` creates uniquely named loopback-only source/target databases, applies all control/bank SQL migrations, seeds fictional balanced journals, independent approvals and pending deliveries, backs up/restores the set, verifies a lost acknowledgement, rejects a second restore into non-empty databases, then drops only its generated databases. The identity fixture is a synthetic table; it does not certify real Keycloak recovery. Customer balances with real funded accounts and complete business workflows still require bank-led acceptance.

## Monitoring and incident handling

Set explicit positive maximum backup age, restore-evidence age and pending-posting age in the monitor configuration. `python ops/monitor.py --config /etc/cbs/backup.json --metrics /var/lib/cbs-monitor/cbs.prom` checks HTTPS readiness, all configured databases, last backup-job outcome, authenticated backup/restore evidence and overdue delivery queues. Prometheus textfile output and example rules are included. Configure and test your actual scraper, receiver, on-call routing and notification delivery separately. A scheduled check alone is not a working notification channel. Monitor checks manifest authentication; full archive integrity is verified by restore, rather than rereading every backup archive each minute. Provider-specific settlement SLAs still require separate monitoring.

For an incident: stop external traffic/writers, preserve logs and current data, identify the failed release/provider/database, and decide with the bank whether to roll back code or restore data. Roll back only to an image compatible with the current schema; never run automatic down-migrations. For data recovery, restore the complete coordinated set in isolation, reconcile external provider statements and post-backup effects, pass bank checks, then deliberately switch endpoints and enable workers. Record actual loss, elapsed recovery time, reconciliation decisions and approval. Do not replay external payments without checking provider status and idempotency keys.

## Remaining acceptance evidence

- Real staff/limits configured and independent MFA sign-in verified.
- Actual host/HTTPS deployment, immutable image review and vulnerability scan.
- DBA verification of runtime denial of DDL, owner membership and cross-bank access.
- Successful scheduled backup, off-host receipt, key escrow and retention test.
- Real identity recovery, funded customer balances and every critical pending workflow verified after restore.
- Delivered failure alerts, restart/crash recovery, load, outage/failover and bank-signed RPO/RTO results.

Official references: [PostgreSQL pg_dump](https://www.postgresql.org/docs/current/app-pgdump.html), [pg_restore](https://www.postgresql.org/docs/current/app-pgrestore.html), [Spring Boot PropertiesLauncher](https://docs.spring.io/spring-boot/specification/executable-jar/property-launcher.html), [Caddy automatic HTTPS](https://caddyserver.com/docs/automatic-https).
