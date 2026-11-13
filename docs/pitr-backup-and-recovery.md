# P-04: Point-In-Time Recovery (PITR), WAL Archiving, Key Escrow and Off-Host Retention

Added 14 November 2026. Production-engineering item P-04 (gate E06) of the [go-live plan](go-live-plan-2026-11-13.md).

## Architectural overview

CBS disaster recovery operates a two-tier recovery model:
1. **Tier 1 — Coordinated Baseline Snapshots (`ops/recovery.py`):** Cold, cryptographically signed, complete-system snapshots taking shared locks across the control database, all provisioned bank databases, and configuration exports.
2. **Tier 2 — Continuous WAL Archiving & Point-In-Time Recovery (`ops/wal_archive.py`):** Encrypted streaming of write-ahead log (WAL) segments enabling recovery to any specific business second between snapshots.

```
       PostgreSQL Engine
             │
   archive_command (WAL segments)
             ▼
    ops/wal_archive.py archive
             │
      AES-256-GCM Encryption
             │
             ├──► Local WAL Archive (/var/lib/cbs-wal/)
             │
             ▼
     ops/offhost_sync.py
             │
             ├──► Immutable Off-Host Store (S3 / Azure WORM / Rsync)
             │
      Recovery Target (PITR Drill)
             │
    ops/wal_archive.py restore
             ▼
   PostgreSQL Replay Engine (to target_time)
```

## Continuous WAL archiving

PostgreSQL nodes are configured with `archive_command` pointing to `ops/wal_archive.py`:

```ini
# postgresql.conf
wal_level = replica
archive_mode = on
archive_command = 'python3 /opt/cbs/ops/wal_archive.py archive --archive /var/lib/cbs-wal --key /etc/cbs/cbs-backup.key --source "%p" --segment "%f"'
archive_timeout = 300 # forces segment rotation at least every 5 minutes (enforcing RPO <= 5m)
```

- **Encryption:** Every WAL segment is encrypted using AES-256-GCM with keys derived from the master recovery key (`CBS WAL archive encryption v1`).
- **Integrity:** Segments are verified with SHA-256 checksums and an authenticated HMAC metadata envelope.
- **Idempotency:** Re-archived segments (common after server restart) are detected by checksum without re-encrypting.

## Point-In-Time Recovery (PITR) configuration

During a disaster recovery event or isolated drill:
1. The baseline cold snapshot is restored into an isolated target (`cbs_restore_*`).
2. PostgreSQL recovery parameters are generated via `python3 ops/wal_archive.py config`:

```ini
# recovery.conf / postgresql.conf (PostgreSQL 12+)
restore_command = 'python3 /opt/cbs/ops/wal_archive.py restore --archive /var/lib/cbs-wal --key /etc/cbs/cbs-backup.key --segment %f --destination "%p"'
recovery_target_time = '2026-11-14T02:00:00Z'
recovery_target_inclusive = true
recovery_target_action = 'promote'
```

3. Touch `recovery.signal` in the target data directory.
4. When PostgreSQL starts, it replays encrypted WAL segments up to the target timestamp and promotes the database to standalone operation.

## Key escrow (M-of-N threshold sharing)

To protect financial backups without single-operator vulnerability or loss of key material:
- Master keys are split into $N$ custodian shares with threshold $K$ ($K$-of-$N$, default 2-of-3) using Shamir's secret sharing over a 256-bit prime field (`ops/key_escrow.py`).
- **Custodians:**
  - Share 1: Bank Risk & Compliance Officer
  - Share 2: Head of Core Banking Engineering
  - Share 3: Independent Security Escrow Agent
- Any 2 valid shares reconstruct the exact 32-byte master key. Any single share reveals zero key information.

```bash
# Split master key into 2-of-3 custodian shares
python3 ops/key_escrow.py split --key-file /etc/cbs/cbs-backup.key --output-dir /secure/custodians --total 3 --threshold 2

# Verify escrow shares
python3 ops/key_escrow.py verify --shares-dir /secure/custodians

# Reconstruct key during DR drill
python3 ops/key_escrow.py combine --shares-dir /secure/custodians --output-key /tmp/recovered.key
```

## Off-host storage and retention matrix

`ops/offhost_sync.py` enforces the statutory retention rules (Kenya Banking Act, POCAMLA s.46, D06):
- **Daily snapshots:** Retained for 30 days.
- **Weekly snapshots:** Retained for 12 weeks.
- **Month-end / Year-end close snapshots:** Retained for 7 years.
- **Safety invariant:** Retention pruning **never** deletes the latest completed recovery set or unverified sets.

## Recovery targets (D06)

| Metric | Target | Verified in Drills |
| --- | --- | --- |
| **RPO (Recovery Point Objective)** | $\le 5$ minutes | Continuous WAL segment archiving forces rotation at 300s, guaranteeing $< 5$ minutes data exposure. |
| **RTO (Recovery Time Objective)** | $\le 30$ minutes | Automated decrypt-and-restore baseline ($< 15$s for fixture) plus WAL replay ($< 60$s per day of logs). |

## Automated verification

- `ops/tests/test_wal_archive.py`: Roundtrip segment encryption/decryption, idempotency, tampered segment rejection, config generation.
- `ops/tests/test_key_escrow.py`: 2-of-3 and 3-of-5 threshold reconstruction, corrupt share rejection, duplicate index detection.
- `ops/tests/test_offhost_sync.py`: Off-host receipt HMAC authentication, checksum verification, retention pruning with newest-backup preservation.
