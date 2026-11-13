# CBS acceptance testing

- [Test plan](cbs-test-plan-2026-10-13.md): execution order, fictional fixtures, financial expectations, roles and release gates.
- [Phase 4 execution report](phase-4-acceptance-execution-2026-11-14.md): complete acceptance execution across W02–W08 with 284 backend, 141 UI, and 49 ops tests.
- [Phase 4 automated evidence](phase-4-automated-evidence-2026-11-14.json): structured evidence and module breakdown for the current V55/V29 baseline.
- [W02/W03 execution report (historical)](w02-w03-acceptance-2026-10-13.md): earlier automated results from 13 October 2026.
- [Automated evidence (historical)](w02-w03-automated-evidence-2026-10-13.json): earlier method-level results from 13 October 2026.
- [Case catalogue](cbs-test-cases-2026-10-10.json): 107 planned tests and 15 evaluated gaps across 34 module areas, re-baselined to Control V55 / Bank V29 with 14 gaps implemented.
- [Excel execution checklist](../../outputs/cbs-test-plan-2026-10-13/CBS-Test-Execution-Checklist.xlsx): record bank acceptance evidence, tester, release and status.

The automated execution report and the bank-acceptance checklist are different evidence sets. Do not mark manual or country-specific acceptance PASS solely because a related automated regression passed. Raw test logs and temporary database files remain ignored under `.local/acceptance-2026-10-13/`.
