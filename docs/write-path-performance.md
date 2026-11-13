# P-06: write-path performance and tenant serialization

Added 14 November 2026. Production-engineering item P-06 (gate E08) of the [go-live plan](go-live-plan-2026-11-13.md).

## Workload model and write paths

In CBS, account balance mutations (funding, cash transactions, transfers, withdrawals, holds, releases, contract disbursements, repayments) follow the maker-checker lifecycle and post atomically to bank databases. Every mutation is tracked through:

1. **Submission (Maker):** Proposals are submitted with the current bank version. Concurrent submissions on the same bank contend on the tenant version (`STALE_VERSION` retried automatically).
2. **Approval (Checker):** An independent checker reviews the proposal. Approval bumps the bank version and queues the instruction for delivery.
3. **Delivery Drain (Worker):** The `LedgerDeliveryWorker` drains the delivery queue in batches of 20 per pass within a configurable time budget (`cbs.delivery.drain-ms`, default 20s), instead of delivering only 20 items per 30-second interval.

## Delivery queue drain (`LedgerDeliveryWorker.drain`)

Previously, background delivery workers processed at most 20 items per scheduled execution (every 30 seconds), causing queue latency to accumulate during bursts of activity.

P-06 introduces `drain(...)`:
- Empties queues within a time budget (default 20,000 ms).
- Failed items are recorded and moved to the back of the queue.
- Each item is attempted at most once per pass; if a batch yields no unattempted items or has fewer than 20 items, the pass terminates cleanly without spinning.
- Controlled via `application.yml`:
  ```yaml
  cbs:
    delivery:
      interval-ms: ${CBS_DELIVERY_INTERVAL_MS:30000}
      drain-ms: ${CBS_DELIVERY_DRAIN_MS:20000}
  ```

## Staging load driver (`ops/perf/write_load.py`)

The driver exercises the write path over HTTPS against staging:

```bash
CBS_MAKER_TOKEN=... CBS_CHECKER_TOKEN=... python3 ops/perf/write_load.py https://cbs-staging.example \
    --bank <bank-uuid> --accounts accounts.json --cycles 20 --p95-ms 2000 --min-tps 5
```

- Each worker owns an account and executes balance-neutral cycles (`HOLD` followed by `RELEASE`).
- Measures latency (submission to posted status) and counts `STALE_VERSION` retries at submit and approval.
- Enforces the **zero-balance-error check**: after all cycles complete, verifies that every account position is reconciled with zero remaining holds.

## Automated verification

- **Unit tests:**
  - `DeliveryDrainTest`: tests full queue draining, single-attempt failure handling, quiet exit on unavailable database, and loop termination.
  - `ops/tests/test_write_load.py`: tests write load driver against simulated bank version bumps and detects balance errors when holds leak.
- **Integration test:**
  - `TenantAdministrationIntegrationTest.writePathThroughputWithZeroBalanceErrors`: opt-in benchmark (`-Dcbs.perf=true`) evaluating concurrent submissions, approval throughput, delivery drain, and sequential cycle p95 latencies with zero balance drift.
