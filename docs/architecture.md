# Paylist v1.1 — Architecture

This document summarises the approved v1.1 design for implementers. The
normative source is `docs/phase1/04-final-design-v1.1.md` (frozen design
docs — do not edit); the file-by-file work plan is
`docs/phase1/04-file-plan-v1.1.md`. Where this summary and the design
disagree, the design governs.

## 1. What the app does

Paylist listens for payment notifications on an Android phone and forwards
them to billing endpoints through **routes**: each route is a set of source
packages + keywords + one endpoint URL + one secret (`X-QRIS-Secret`).
Route order is priority: the first endpoint answering `matched` (or
`ambiguous`) owns the payment; `logged` advances the chain to the next
route. Recorder endpoints (`catat.php`, e.g. myBCA) always answer
`logged`, so one notification can be recorded in two systems.

v1.1 replaces the v2.3 SharedPreferences queue + direct sender with a Room
persistent outbox + WorkManager delivery, keeping the legacy wire contract
(payload fields, `X-QRIS-Secret`, `dedupe_key`, `matched`/`ambiguous`/
`logged`) unchanged.

## 2. Module map (design §2)

```text
NotificationListenerService -> Source allowlist & consent -> PrivacyGate (reject/redact before durable storage)
  -> NotificationNormalizer + optional PaymentParser -> capture staging (Room, post-PrivacyGate only)
  -> enqueue to exactly one store -> Room: Event + LegacyChainDelivery -> unique OneTimeWork
  -> SyncWorker claims due deliveries transactionally (short lease) -> TransportClient + LegacyPayloadMapper
  -> PHP endpoint / mock universal endpoint -> validate ACK -> transactional delivery state update
PeriodicWork -> best-effort heartbeat event + queue reconciliation
Listener connected/manual rescan -> activeNotifications via listener only
Diagnostics UI -> Room states + listener permission/connection timestamps
```

| Module | Files (planned) | Stage |
|---|---|---|
| Collector | `PaylistListenerService`, `NotificationNormalizer`, `SourcePolicy`, `PrivacyGate`, capture staging + sweeper | D (coordinator/lock from B) |
| Parser | `EventParser`, `ParserRegistry`, `PaymentParser` (v2.3 amount/keyword behaviour preserved) | A (interface) / D (impl) |
| Persistence | `PaylistDatabase`, DAOs, `EventRepository`, `LegacyQueueMigration`, `CutoverCoordinator`, promotion receipts, sweep fences, drain intents | B |
| Delivery | `SyncWorker`, `SyncScheduler`, `TransportClient`, `LegacyPayloadMapper`, `LegacyResponseClassifier`, `HeartbeatWorker` | C |
| Security | `SecretVault` (Keystore), `EndpointValidator`, `SensitiveDataRedactor` | C |
| UI/diagnostics | status, blocked items, last ACK, listener permission, manual rescan/retry, `NEEDS_REVIEW` review surface, onboarding consent, revocation, local deletion | D |

Rules: no HTTP inside the listener callback; no global lock across network
I/O; no unbounded `Thread {}` per event. A callback accepted into an
in-memory executor is **not yet durable**; diagnostics must not report it
as persisted before the Room commit.

## 3. Canonical event (design §3)

```json
{"schema_version": 1, "event_id": "UUIDv4", "source_event_id": "stable-source-key",
 "type": "payment.notification", "category": "payment",
 "source": {"type": "android_notification", "device_id": "device-installation-uuid",
            "package": "com.example.merchant"},
 "occurred_at": "2026-11-14T10:00:00Z", "captured_at": "2026-11-14T10:00:01Z",
 "data": {"amount": 150000, "currency": "IDR"},
 "metadata": {"parser_version": "payment-v1", "privacy_mode": "structured"}}
```

- Events are immutable. An updated notification with the same `dedupe_key`
  is the same event.
- `dedupe_key` (C3) is computed from pre-redaction transient input (raw
  title + text) on BOTH the live and rescan paths, before PrivacyGate:
  `"$pkg|${postTime}|$amount|${combined.take(40).hashCode()}"`. It is
  never recomputed from a stored payload (the stored payload truncates
  text and drops `bigText`).
- `content_sha256` is the SHA-256 of the full pre-redaction combined text,
  computed transiently, never the raw text, never exported/logged (it is a
  hash oracle for low-entropy text).
- Identity is DB-enforced, never query-then-insert: unique index on
  `events.dedupe_key`, unique index on `deliveries.legacy_qkey`
  (`dedupe_key|kind|route-set`). All inserts use `INSERT OR IGNORE`.

## 4. Room schema v1 (design §4)

| Table | Key facts |
|---|---|
| `events` | `event_id` PK, `schema_version` (=1), `dedupe_key` unique, `content_sha256`, `export_ref` (random UUID v4, unique — reconciliation export identity), `source_account_key` (opaque Keystore-HMAC key, nullable, fail-closed) |
| `deliveries` | `delivery_id` PK, `event_id` FK CASCADE, `kind` (`LEGACY_CHAIN`/`UNIVERSAL`), `state`, `raw_ephemeral`, `raw_lost`, `legacy_qkey` unique, `route_ids_json`, `next_route_index`, `attempts`, `next_retry_at_ms`, lease (`lease_owner`, `lease_until_ms`, 5 min, no renewal), `last_match_status`, `last_apply_status`, `terminal_at_ms`, per-route stamp map |
| `delivery_attempts` | `attempt_id` PK (UUID, single-use), `state` (`ATTEMPT_STARTED`/`SOCKET_OPENING`/`SOCKET_OPEN`/`ABANDONED_PRE_SOCKET`/`NEVER_SENT`/`COMPLETED`), `hop_outcomes[]`, `owner_boot_id`, `drain_generation`, `fence_generation`. Never stores secret, full request body, or unredacted response body |
| `collector_state` | `key` PK, `value_json`. The authoritative mode store (no SharedPreferences mode value exists in v1.1): `cutover_state`, `migration_state`, `cutover_import`, `drain_intent`, sweep fences, `boot_id`, `sender_fenced`, `route_consents` |
| `capture_staging` | Keyed by unique `dedupe_key`; post-PrivacyGate only. Deleted only after a verified durable commit in the target store |
| `promotion_receipts` | `legacy_qkey` PK, `PREPARING -> READY -> IN_FLIGHT -> RESOLVED`. `PREPARING` never POSTs; `RESOLVED` drops without POST |
| `order_registry`, `match_results`, `classification_results`, `registry_imports` | P3 advisory matching/classification (Stage B tables, Stage D logic). Unique (`merchant_id`, `order_number`) on the active snapshot; unique partial index for one live attribution per order |
| `export_audit` | One row per reconciliation-export attempt (`COMPLETED`/`FAILED`/`CANCELLED`), incl. `auth`, `epochs_json`, `notes`. Never contains exported content |

## 5. Delivery state machine (design §5)

```text
PENDING --claim--> IN_FLIGHT
IN_FLIGHT --2xx valid matched/ambiguous--> ACKED (chain stops)
IN_FLIGHT --2xx valid logged--> persist next_route_index; continue chain
IN_FLIGHT --network/408/429/5xx/protocol invalid--> RETRY_WAIT
RETRY_WAIT --due--> PENDING (then claim)
IN_FLIGHT --nonretryable 4xx/missing route--> BLOCKED
IN_FLIGHT --lease expired--> PENDING (only when no unresolved attempt marker)
BLOCKED --explicit operator action--> PENDING
RETRY_WAIT --TTL expired (heartbeat/test only)--> EXPIRED
```

Held states (never conflated, §7.3B): `NEEDS_REVIEW` (the amount
question), `RAW_UNAVAILABLE` (the payload-form question), `BLOCKED` (the
consent/config question). `HELD_AWAITING_LEGACY_SWEEP` (amount pick
awaiting the legacy sweep) and `HELD_AWAITING_RECONCILIATION` (unknown
hop awaiting server evidence) are the two sweep/reconciliation holds.
Terminal: `ACKED`, `EXPIRED`, `DISCARDED`. Payment deliveries are never
expired; heartbeat/test events whose deliveries are all terminal are
deleted 30 days after `terminal_at_ms` (payment events never auto-deleted).

Backoff: start 1 min, x2, cap 2 h, jitter ±25%; honour bounded
`Retry-After`. Batch up to 20 due deliveries per worker invocation.

## 6. Legacy response classification (design §5.4)

`matched` is **not** settlement. Only `matched + apply=ok` is
`MATCHED_SETTLED`. The full taxonomy:

| Response | Classification | Chain |
|---|---|---|
| 2xx payment `matched` + `apply=ok` | `MATCHED_SETTLED` | stops |
| 2xx payment `matched` + `apply=skip` | `MATCHED_NOT_APPLIED` (flag review) | stops |
| 2xx payment `matched` + `apply=error`, or `apply` absent | `MATCHED_UNCONFIRMED` | stops, reconcile |
| 2xx payment `matched` + `duplicate=true` | `MATCHED_UNCONFIRMED` (never trust `apply` on a duplicate) | stops |
| 2xx `ambiguous` | `AMBIGUOUS` (flag review) | stops |
| 2xx `logged` | advance; ACK if last route | next hop |
| 2xx missing/malformed `match.status` (payment) | retry same route | same hop |
| 2xx heartbeat/test with `ok=true` | ACK, whatever `match` contains | — |
| 2xx heartbeat/test without `ok=true` | failed attempt, retry per backoff | — |
| 408 / 429 | retry; parse bounded `Retry-After` | — |
| 5xx / timeout / network | retry, exponential backoff + jitter | — |
| 400/401/403/404/other nonretryable 4xx | `BLOCKED`, chain halts | held |
| 3xx | `BLOCKED` (redirects disabled; never followed, secret never leaves the route host) | held |

At-least-once delivery; idempotent server record/settlement keyed by
`dedupe_key`; apply outcome confirmed only by the first response or by
server-side reconciliation. "Exactly-once" is struck everywhere.

## 7. Privacy (design §7)

PrivacyGate runs before **any** durable write, on both live and rescan
paths. Four classes (C4, final form):

- **(a) CONFIRMED** (OTP/PIN/password/auth token): never persisted in raw
  form on any route. Separable span → `[REDACTED]`; pure-auth event →
  dropped, diagnostic counter only. Raw transmission only under the §7.3
  Option B per-route opt-in, from an in-memory snapshot, never from disk.
- **(b) AMBIGUOUS** (bare 4–12 digit strings, unlabelled codes): redacted;
  classification binds each span to its **local** label/cue only.
- **(c) FINANCIAL_IDENTIFIER** (amount, labelled reference/ID, masked
  account, direction cue, names): preserved. A labelled reference is
  class (c) whether or not an amount is present.
- **(d) Legacy exception**: ordinary financial notifications on legacy
  routes preserve the payload verbatim — except mandatory credential
  redaction. Verbatim persistence covers only the pre-existing v2.3 raw
  queue; newly captured v1.1 items persist the §7.2 constructed text +
  structured fields, never raw class-(a) text.

**OTP is never a reference.** Option A′ (§7.2, default) constructs recorder
text purely from structured fields (`Transfer ke {masked} Rp{amount} Ref
{reference}` / `Diterima Rp{amount} dari {masked} Ref {reference}` /
no-cue form), with a self-check that the constructed text classifies to
the intended direction under the `catat.php` keyword lists.

Option B (§7.3, owner-decided): per-route granular informed opt-in, plain-
words disclosure naming authentication codes / transfer information /
reference numbers / account numbers; revocation stops future raw
forwarding immediately (already-forwarded content cannot be recalled);
raw snapshots live only in a bounded in-memory map (capacity 50, TTL 2 h,
LRU); the point of no return is the first write to the request-body
output stream; a per-route `sendFence` generation is re-read immediately
before that single first-write call site (§7.3A(e)).

On raw loss, the guarded best-effort fallback (§7.3B) authorises an
automatic A′ dispatch only if **all six** criteria hold (amount reliable,
direction reliable, identity reliable, endpoint accepts the fallback,
prior outcome reconciled via the full Resolution 4 test, consent/
privacy re-checked); otherwise the delivery holds in `RAW_UNAVAILABLE`
with operator alerting (§7.9).

Secrets: Android Keystore AES-GCM (`SecretVault`, Stage C); the one-time
plaintext migration runs at first v1.1 launch (`SecretMigration`, Stage
C). No plaintext fallback, ever. Backup: P10 option C — the Room database
and queue are excluded from backup/device transfer; the device-loss
limitation is disclosed conspicuously.

## 8. Migration and cutover (design §8)

Modes (`collector_state`, Room only): `LEGACY_ONLY` → `DRAINING` →
`WORKER_ONLY`. Cutover is monotonic: once `DRAINING` is durably
committed, the mode never returns to `LEGACY_ONLY` automatically.

- **Handoff**: the coordinator acquires the fair timed sender lock
  (45 s, fail-closed), durably flushes the queue (`saveQueueSync` =
  `commit()` + read-back), commits `DRAINING`, snapshots the queue,
  releases the lock. No legacy flush runs during `DRAINING`.
- **Import**: freeze (copy the queue read-only into Room import-staging)
  → import idempotently (`INSERT OR IGNORE` by `qkey`) → verify
  (count + checksum over the canonical serialisation) → commit
  `WORKER_ONLY` → clear only individually verified imported/resolved
  entries (quarantined entries retained, §7.7 R29 barrier).
- **Capture gate** (§8.3): the listener callback never acquires the
  cutover lock and never touches a store; it hands an in-memory snapshot
  to a dedicated single-thread capture executor, which runs PrivacyGate,
  writes a durable staging row, then enqueues to exactly one store.
- **Admission** (§8.5): one Room transaction reads mode + drain
  generation + sweep fence + consent/config epochs and inserts an
  `ATTEMPT_STARTED` row, or refuses with a durable reason code. The
  transport state machine is `ADMITTED -> SOCKET_OPENING -> SOCKET_OPEN
  -> COMPLETED`, with `connect()` → `SOCKET_OPEN` checkpoint → first
  write ordering. The `NEVER_SENT` classification for orphaned
  pre-checkpoint attempts is conditional on fixture F40 passing on the
  actual stack (consolidation-gate resolution 3).
- **Sweep** (§8.6/§8.7): two-store protocol with a durable sweep intent
  written before the queue is touched; queue absence is never evidence
  by itself; pre-v1.1 copies release only on an authoritative
  reconciliation record + an operator-confirmed historical route/system
  binding + release-time re-gating.

## 9. Server reconciliation — P12 (design §9)

Status endpoints `GET /paylist/status.php` (settlement) and
`GET /paylist/catat-status.php` (recorder), authenticated with
`X-QRIS-Secret`, read-only. `notify.php` acquires a MySQL named lock
(hex SHA-256 of `dedupe_key`, 5 s timeout → 503) held until after the
`completed_at` write; the status query acquires the same lock with
timeout 0 and holds it through the authoritative SELECT
(`in_flight:true` when held). A `found:false` answer is authoritative
only when writer coverage is COVERED + no UNKNOWN hop exists for the key
+ the system/endpoint binding matches + the decision is recorded
durably (Resolution 4). Attribution needs unique mapping + position
consistency + terminality (§9.4); a server row alone never attributes a
hop. v2New is unverified until P11; its copies stay `HELD`.

## 10. Reconciliation export (design §7.7A)

The only export function in v1.1: a user-initiated, foreground, CSV
(RFC 4180 CRLF, UTF-8 with BOM) export of structured records for manual
reconciliation. Exact 20-column schema, one row per (event, route) pair
with a recorded hop outcome plus one zero-hop event-level row
(`route_kind` NONE, `NOT_DISPATCHED`) per event with no attempted hop.
Stable opaque IDs (`row_id` = SHA-256 of `event_ref + "|" + route_id`);
decimal amounts (integer minor units internally, no float); formula-
injection guard (reversible `'` prefix, residual risk documented);
sender/recipient identifiers behind a default-OFF, never-persisted
opt-in + device-auth gate (fail-closed without a secure lock);
authorization snapshot revalidated before every row write; SAF write
with a partial-failure protocol; one `export_audit` row per attempt.
Read-only: zero side effects. No diagnostic/recovery export exists
(F55). Automated distinct-bank-transaction totals from the CSV are
prohibited.

## 11. Stages and gates (design §11)

| Stage | Work | Gate |
|---|---|---|
| A Foundation | clean build, wrapper/icons, deps, model/parser stubs, tests, docs | T01 + contract fixtures |
| B Room | schema, DAOs, transactions, lease, disabled migration machinery | T09/T10/T20 + OutboxMigrationTest |
| C Delivery | HTTP classification, WorkManager, legacy chain, ACK, heartbeat | F50/F54 activation gate, then T02–T08 + T21 + T22 |
| D Collector/security/UI | listener, privacy, parser, Keystore, diagnostics | T13–T15 + T23/T24 + F43 B14/B20/B21 + F45–F52 + F56–F68 |
| E Verification | clean build, instrumented tests, Doze pilot, rollback rehearsal | G11 owner approval + F53 + F55 (+ F61 manual evidence) |

Never deploy to the production device automatically. E→release needs
G11 owner approval on evidence.
