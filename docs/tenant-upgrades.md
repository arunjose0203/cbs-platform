# P-08: tenant upgrade orchestration

Added 14 November 2026. Production-engineering item P-08 (gate E11) of the [go-live plan](go-live-plan-2026-11-13.md). Exit criterion: **a failed-upgrade drill causes no tenant corruption.** The drill runs as an automated test (below).

## How bank databases are upgraded

The offline migration job (`com.cbs.operations.MigrationMain`, the `migrate` service in `deploy/staging/compose.yml`) works in this order:

1. Migrate the **control database**. If it fails, nothing else runs (exit code 1).
2. Upgrade **bank databases one at a time** (rolling), in reference order. Each bank migration runs in its own PostgreSQL transaction, so a failing migration is rolled back completely. The bank stays at its previous version, and no half-applied migration is recorded.
3. **Continue past a failed bank** by default, so one bad tenant does not hold back the others. `--stop-on-failure` stops at the first failure and marks the remaining banks `SKIPPED`.
4. Print each bank's result (`UPGRADED`, `CURRENT`, `FAILED` or `SKIPPED`, with the version before and after). `--report file.json` writes the same result as JSON, without credentials.

| Exit code | Meaning |
| --- | --- |
| 0 | Control and every selected bank are at the build's version |
| 2 | Control is current; at least one bank failed or was skipped |
| 1 | The job could not run: bad plan, transport refused, or the control migration failed |

**Canary:** `--only ref1,ref2` upgrades only the named banks, for example one pilot bank before the rest. An unknown reference is refused before anything runs.

## Mixed versions at runtime

In production the runtime never migrates (`cbs.banking.migrations-enabled=false`). Every connection to a bank database now checks that bank's schema version against the highest bank migration in the build:

- A bank that is behind, for example after a failed or partial upgrade, is refused with `503 BANK_SCHEMA_UPGRADE_REQUIRED`. The code never runs SQL written for a newer schema against an older one.
- Other banks keep working.
- A bank confirmed current is not rechecked for 60 seconds. A bank found behind is checked again on every connection, so it is served as soon as a re-run upgrades it.

`deploy/staging/up.sh` stops on exit code 2. It starts the application with the failed banks refused only when the operator sets `CBS_ALLOW_PARTIAL_UPGRADE=yes`.

## Upgrade procedure (runbook)

1. Take and verify backups (P-04) of the control database and every bank database.
2. Stop writers: `up.sh` stops the app before migrating.
3. Run a canary: `--only <pilot bank> --report canary.json`. Check that the result is `UPGRADED`.
4. Run all banks: `--report upgrade.json`.
5. If the exit code is 2, read the report. For each `FAILED` bank, decide:
   - **Fix and re-run** (preferred): the bank is unchanged at its old version, so re-running after the fix upgrades it.
   - **Start with the bank refused** (`CBS_ALLOW_PARTIAL_UPGRADE=yes`): other banks resume service, and the failed bank's staff see `BANK_SCHEMA_UPGRADE_REQUIRED` until it is fixed.
   - **Roll back the release:** restore the control database from the backup taken in step 1. Banks that were upgraded must also be restored, because the old build expects the old schema. This is the rollback decision point.
6. Reapply runtime grants (`ops/runtime-privileges.sql`), start, and check readiness.

## The failed-upgrade drill

`TenantAdministrationIntegrationTest.rollingBankUpgradesSurviveAFailedBankAndTheRuntimeRefusesBanksLeftBehind`:

1. Two bank databases are migrated to bank schema version 24. Bank B gets a squatting `bank.period_close` table holding a row, which makes its V25 migration fail.
2. **Canary** on bank A only: A goes from 24 to the latest version; B is untouched.
3. **Full run:**
   - A is `CURRENT` and B is `FAILED` at 24, and the report says so without exposing passwords.
   - B's row is intact and its history has no failed or partial migration.
4. **Runtime with migrations disabled:**
   - A is served.
   - B is refused with `BANK_SCHEMA_UPGRADE_REQUIRED`, and `upgradeOwned` reports `DATABASE_MIGRATION_REQUIRED`.
5. **Fix and re-run:** A stays `CURRENT`, B is `UPGRADED`, and the runtime serves B.

`MigrationMainTest` covers the option parsing. `ops/tests/test_staging_up.py` covers the deploy script's handling of exit codes 1 and 2.

## Limits

- Banks are upgraded one at a time. For many banks this is slower than parallel upgrades, but it limits the impact of a bad migration. Parallelism can be added if the number of banks grows.
- Each Flyway migration is atomic, but a multi-migration upgrade can stop part-way, for example at V25 of V25–V27. The bank is then at an intermediate version. It is consistent but still behind, so the runtime refuses it until the re-run completes it.
