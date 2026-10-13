# CBS acceptance testing

- [Test plan](cbs-test-plan-2026-10-13.md): execution order, fictional fixtures, financial expectations, roles and release gates.
- [W02/W03 execution report](w02-w03-acceptance-2026-10-13.md): automated results and independent acceptance still required.
- [Automated evidence](w02-w03-automated-evidence-2026-10-13.json): sanitized method-level results, baseline and log hashes.
- [Case catalogue](cbs-test-cases-2026-10-10.json): 107 planned tests and 15 known gaps across 34 module areas.
- [Excel execution checklist](../../outputs/cbs-test-plan-2026-10-13/CBS-Test-Execution-Checklist.xlsx): record bank acceptance evidence, tester, release and status.

The automated execution report and the bank-acceptance checklist are different evidence sets. Do not mark manual or country-specific acceptance PASS solely because a related automated regression passed. Raw test logs and temporary database files remain ignored under `.local/acceptance-2026-10-13/`.
