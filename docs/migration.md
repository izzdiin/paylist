# Migration & cutover (Stage B)

The legacy queue (`SharedPreferences` `paylist`, key `queue`) moves to
the Room outbox (`paylist-outbox.db`) in one guarded cutover. This
document describes the Stage B machinery: the schema, the importer,
the coordinator, the sweep, and admission. Normative source: design
R43 (`04-final-design-v1.1.md` §4, §5, §8, §10); file plan R43
(`04-file-plan-v1.1.md`).

**Stage B status: the machinery exists and is tested, but NOTHING
calls it from production code yet.** The legacy sender keeps running
exactly as before (R29). Stage C wires the coordinator to the
sender/worker and may only activate the legacy import/cutover after
F50 + F54 PASS. There is no whole-queue delete anywhere in the tree
(F54); clearing after `WORKER_ONLY` is per individually verified
imported/resolved item only.

## The Room schema (v1)

Eleven tables (`data/PaylistDatabase.kt`, version 1):

| Table | Purpose |
|---|---|
| `events` | One row per canonical captured event (§4.1). `dedupe_key` and `export_ref` unique. `content_sha256` NULL for pre-v1.1 imports: the full pre-redaction text no longer exists, so no hash can be honestly computed. |
| `deliveries` | One row per outbound delivery job (§4.2). One payment event maps to one legacy chain delivery, not one per route. `legacy_qkey` unique (NULLs distinct). |
| `delivery_attempts` | Bounded diagnostic attempts, one row per admitted hop attempt (§4.3). `attempt_id` single-use. |
| `collector_state` | Key/value status store (§4.4). **The authoritative mode store** — no mode value exists in SharedPreferences (R14). |
| `capture_staging` | Pre-import capture staging (§4.5), keyed by `legacy_qkey`. |
| `promotion_receipts` | The promotion ledger (§4.5): `PREPARING → READY → IN_FLIGHT → RESOLVED`. |
| `order_registry` | Active merchant order snapshots (§4.6). |
| `match_results` | Advisory matches + the attribution audit (§4.6). One attribution per order, enforced by a derived `attributed_order_key` unique index (Room cannot declare a partial index; see the KDoc on `MatchResultEntity`). |
| `classification_results` | Amount/classification outcomes (§4.6). |
| `registry_imports` | Registry import metadata (§4.6). One ACTIVE version per merchant. |
| `export_audit` | Reconciliation-export audit rows (§7.7A). |

Sweep fences, sweep intents, and sweep records are JSON documents in
`collector_state` (`sweep_fence:<id>`, `sweep_intent:<id>`,
`sweep_record:<dedupe_key>`); the file plan registers no separate
fence entity, and §4.4 lists sweep fences as `collector_state` keys.

## The frozen snapshot (no import-staging table)

There is no separate import-staging table. The frozen snapshot is:

1. the legacy queue itself, which cannot change during `DRAINING`
   (the sender is stopped and new captures go to Room), plus
2. the canonical checksum over the snapshot, plus
3. the phase marker in `collector_state` (`cutover_import`).

A re-import re-reads the queue file and verifies the checksum; a
mismatch fails the import rather than importing a moved target.

**Checksum (§8.2, normative).** SHA-256 over the per-item
serialisation `qkey + '|' + payload JSON (keys sorted) + '|' +
route_ids + '|' + next + '|' + attempts + '|' + last_error`, items
sorted by `qkey`, joined with `\n`. Count equality alone is never
sufficient verification. The importable set and the quarantine
metadata have separate checksums (R29).

## The import (`LegacyQueueMigration`)

`snapshot → partition → import → verify`, all idempotent:

1. **Snapshot.** Parses the queue; corrupt items are counted and
   logged, never stop the migration; expired heartbeats are skipped
   (same rule as the legacy sender: 2 buckets = 30 min).
2. **Partition.** The P9 inspector (`LegacyQueueInspector`) decides
   per item: `IMPORTABLE` or `QUARANTINED`. There is no bulk
   verdict. Quarantined items are never imported, never POSTed,
   never exported, and are excluded from the import checksum.
3. **Import.** Batches of 50 per transaction, `INSERT OR IGNORE`
   by `qkey`. A re-run skips already-imported rows (T10). A fresh
   `export_ref` is assigned on first import; re-import preserves
   the first (F65). A `single` item imports as a `UNIVERSAL`
   delivery; a `chain` item as `LEGACY_CHAIN`.
4. **Verify.** The re-read queue's importable set must checksum to
   the snapshot's importable checksum, every importable `qkey`
   must have a Room delivery, and the imported count must equal
   the importable count.

**The import state rule (§8.5).** The Room attempt rows and the
promotion receipt win the snapshot join over the queue file:

- `RESOLVED` receipt → terminal (`dispatched` → `ACKED` per the
  last hop outcome, `no_post` → `DISCARDED`). Never `PENDING`
  when the receipt proves terminal.
- `IN_FLIGHT` receipt → `PENDING` (re-POST permitted under
  at-least-once; the hop is unknown).
- Orphaned unresolved attempt row → `HELD_AWAITING_RECONCILIATION`.
- With hop records: the last hop decides (chain-terminal →
  `ACKED`; 4xx/gate → `BLOCKED`; unknown → `PENDING`; logged +
  exhausted → `ACKED`; all-skipped + exhausted → `BLOCKED`,
  visible, never silently done).
- Without hop records: `PENDING` at the persisted `next`, except
  a cursor advanced past the chain end with no record — the row-5
  guard: the cursor alone proves nothing, so the delivery is
  `HELD_AWAITING_RECONCILIATION`.

## The cutover (`CutoverCoordinator`)

Five legal (mode, intent) combinations (§8.4):

| # | Mode | Intent |
|---|---|---|
| 1 | `LEGACY_ONLY` | `NONE` |
| 2 | `LEGACY_ONLY` | `PENDING` |
| 3 | `DRAINING` | `QUIESCED` |
| 4 | `WORKER_ONLY` | `QUIESCED` |
| 5 | `LEGACY_ONLY` | `ABORTED` |

Flow:

1. **`requestDrain()`** — installs the intent `PENDING` and, in the
   same transaction, snapshots the awaited set: every unresolved
   attempt row at install time. No attempt can be admitted
   afterwards while the intent is `PENDING`.
2. **`commitDraining()`** — the atomic `PENDING → QUIESCED`
   commit (§8.4): mode `DRAINING`, the sweep fence, the fence
   generation bump, and the phase marker, in ONE Room transaction.
   Requires quiescence: the awaited set resolved (orphaned rows
   owned by dead processes do not block quiescence — recovery
   classifies them, F28) and no unresolved attempt owned by the
   current boot. `DRAINING` requires a requested drain (R19);
   there is no commit from `NONE`.
3. **`drain()`** — snapshot → partition → import → verify →
   sweep-remove each imported item. Idempotent and resumable.
4. **`commitWorkerOnly()`** — requires a verified drain; releases
   the sweep fence. There is no automatic return to `LEGACY_ONLY`.
5. **`abortCutover()`** — back to `LEGACY_ONLY` / `ABORTED`.
   Room events captured during `DRAINING` stay in Room, visible;
   re-promotion to the legacy queue is Stage D work.

A crash mid-transaction rolls back and the old mode stands (§4.4).

## The sweep (§8.6)

One step per queue item: **fence install + intent → queue removal
→ fence release + record**. The per-event fence
(`sweep_fence:<dedupe_key>`) is installed in Room BEFORE the queue
is touched; `RELEASE` commits only in the final Room transaction
together with the sweep record (the (f′′) invariant). Between
installation and `RELEASE`, the fenced copy cannot be dispatched
even if the SharedPreferences write fails: the fence stays
`ACTIVE`, the pick stays `HELD`, zero POSTs follow. A crash
between the queue write and the record leaves the intent behind;
a re-run finds the queue item already gone and completes the
record — the sweep never removes an item twice.

- `REMOVE` when Room is the durable truth (`PENDING`,
  `IN_FLIGHT`, `RETRY_WAIT`, `BLOCKED`, `ACKED`, `EXPIRED`,
  `DISCARDED`).
- `SKIP_HELD` when the queue copy must stay as evidence
  (`HELD_AWAITING_RECONCILIATION`, `HELD_AWAITING_LEGACY_SWEEP`,
  `NEEDS_REVIEW`, `RAW_UNAVAILABLE`); the fence stays `ACTIVE`
  across reboot until reconciled.

The step-(6) clear record (`migration_clear`) is computed from
the durable sweep records: removed count + checksum over the
removed `qkey`s.

## Admission (§8.5)

One transaction reads the mode + drain generation + the sweep
fence for the event's `dedupe_key` + the route epochs, and writes
the `ATTEMPT_STARTED` row. One rule, both paths: the legacy flush
is admitted only in `LEGACY_ONLY`, the Room worker only in
`WORKER_ONLY`. Rejection codes: `MODE`, `DRAIN_PENDING`,
`FENCED`, `EPOCH_STALE`, `LIVE_ATTEMPT` (one live attempt per
hop). A rejected admission writes nothing. A barrier installed
after admission never invalidates an admitted attempt — it awaits
it.

**Crash matrix (§8.5).** Kill (1a) after the marker write, (1b)
after `connect()` returns before the checkpoint, (2a) during
`connect()` ⇒ `NEVER_SENT` — **conditional on the F40 gate**;
until F40 passes on the actual stack, those orphans classify
`UNKNOWN` at the delivery level. Kill (2b)–(5) (after the
`SOCKET_OPEN` checkpoint) ⇒ `UNKNOWN`. In-process observed
connect/DNS/output-stream failure (no crash) is `NO_POST`.
`recoverOrphanedAttempts()` implements this; the F40 gate is the
`neverSentGatePassed` constructor flag (default false).

## Leases (§5.1)

Lease 5 min, no renewal. Each claim sets a fresh `lease_owner`
UUID per delivery. Every worker transition is a single conditional
UPDATE on the stored `lease_owner` + unexpired lease. A worker
that finds its lease lost or expired discards its in-flight
result and writes nothing. Expired leases are reclaimed to
`PENDING` only when no unresolved attempt marker exists for the
delivery. The claim reads the mode in the same transaction and
claims zero rows unless the mode is `WORKER_ONLY`; the claim
joins the consent check (a revoked route, or a consent epoch
newer than the stamp, is not claimed; routes with no consent
record pass — no revocation evidence).

## Tests

- **`CutoverTest`** (unit, JVM): T09/T10/T20 + F12, F20, F27,
  F28, F29, F31, F32, F36, F37a, F37b, F38, F54. There is no
  Robolectric in this module, so the tests run the migration and
  the coordinator against in-memory fake DAOs that mirror the SQL
  contracts. DAO methods with a default body (`claimBatch`,
  `reclaimExpiredLeases`, `putAll`) are inherited by the fakes
  unchanged, so the mode gate and the consent join under test are
  the production bodies. The fake transaction runner serialises
  transactions on one lock and rolls back on failure via a
  snapshot — that is what makes the crash-mid-commit tests
  meaningful. F27 (no mode read from SharedPreferences) and the
  F54 tree half (no whole-queue delete) are static checks over
  `src/main/java`, fail-closed when the source root is not found.
  Fixture cases owned by the sender, the capture path, or the
  worker (lock-timeout retry, staging/rescan, the sender
  checkpoint protocol, the drain timeout, `NO_POST` on observed
  failure) belong to Stage C/D and are named, not asserted, in
  `CutoverTest`.
- **`OutboxMigrationTest`** / **`DeliveryLeaseTest`**
  (androidTest): the import and the claim/lease rules against
  real Room. They COMPILE on laptopgrt2 but cannot EXECUTE there
  (no device/emulator); execution needs a device or an emulator.

## What Stage B does NOT do

- No production caller: the coordinator, the migration, and the
  sweep are unreachable from the app until Stage C wires them.
- No P9 inspector implementation (`LegacyQueuePrivacyGate` is
  Stage C); the migration takes the inspector as a parameter.
- No sender/worker integration (claim loop, checkpoint protocol,
  retry/backoff): Stage C.
- No capture/promotion path (staging → Room → legacy re-enqueue):
  Stage D.
- No reconciliation export: Stage E.
- No migration from a previous Room version: v1 is the first
  schema (`exportSchema=false`); a future v2 needs a
  `Migration` object and a `MigrationTestHelper` test.
