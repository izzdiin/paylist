# Capture health: the degraded state (Stage D)

Definition required by the PR #5 re-reviews of 27 and 28 Nov
2026 and implemented by `CaptureCoordinator.degradedState()` /
`CaptureDegradedState`. This document is the owner-facing half of
that definition: when the capture pipeline is degraded, what the
owner sees, and how it recovers.

## Two layers: volatile counters + persistent incidents

The 27 Nov definition was counters in memory — they died with
the process, so a pipeline could crash in a degraded state and
restart looking healthy. Since the 28 Nov re-review there are
two layers:

1. **Volatile counters** (this process only): the table in the
   next section.
2. **A persistent incident record**: every terminal capture
   failure also lands in a health document in the app's
   SharedPreferences (`capture_health_v1` in the `paylist`
   prefs), behind `CaptureHealthStore`. It survives process
   death and restarts, is surfaced at every startup in the local
   log (a "kesehatan capture TERDEGRADASI saat mulai" line plus
   the incident list), and is merged into `degradedState()` by
   any new coordinator over the same store — a fresh process
   with zeroed counters still reports the pipeline degraded
   until the incident recovers.

Incidents aggregate by (category, status, identity) — the
identity is empty for aggregate component incidents and an
**observation identity** for per-snapshot loss incidents:
`obs:<sha256>` over package + post time + the SHA-256 of the
snapshot's combined text (audit 7235948077, A05). The old
`<package>@<postTimeMs>` identity collided across distinct
notifications sharing a package and a millisecond, so an
unrelated record landing could falsely "recover" an earlier
loss; the observation identity discriminates exactly what the
dedupe key discriminates, stays stable across rescans of the
same notification (rescan idempotency is preserved), and
persists only hashes — no raw text, no OTP, no content.
Each carries a status:

| Status | Meaning |
|---|---|
| `OPEN` | The failure is current: the pipeline is degraded NOW. |
| `RECOVERED` | Proof at the granularity of the incident: for a component incident, the component demonstrably worked again (a later durable write landed); for an identity-keyed loss incident, THAT snapshot's evidence demonstrably landed (identity-level reconciliation). History, not degradation. |
| `UNRECOVERABLE` | An outcome that can never be retroactively confirmed (a task interrupted before staging; a capture unresolved at close). History, and never folded into "resolved". Also the result of an explicit operator disposition (`dismissIncident`) — an acknowledgement, never a recovery claim. |

| Category | Raised when | Recovers when |
|---|---|---|
| `OVERFLOW_SNAPSHOT_DROPPED` | A snapshot is dropped by queue saturation before staging (its metadata-only overflow row is the evidence). | Never auto-recovers; the rescan of a still-posted notification is the recovery path (see below). |
| `OVERFLOW_RECORD_LOST` | The durable overflow record itself is given up (writer queue full, writer terminated, or retries exhausted). Keyed to the lost snapshot's observation identity. | ONLY identity-level reconciliation: a durable overflow row for THAT SAME snapshot lands later (e.g. the snapshot is re-dropped by a later saturation after a rescan). A later insert for a DIFFERENT snapshot — even one sharing the package and the post millisecond — proves nothing about this one and never recovers it. Otherwise: operator disposition. Note this is **metadata recovery**: the landed row reconciles the evidence, never the payment (the dropped snapshot's payment is recovered only by an actual rescan capture, and the aggregate `OVERFLOW_SNAPSHOT_DROPPED` incident stays OPEN to say so). |
| `DB_PERMANENT_FAILURE` | An overflow insert exhausts its retry budget — the database is the failing component. Its own category, distinct from losing one record. Aggregate (component health — no identity). | A later overflow insert lands (component-level proof for a component-level incident). |
| `WRITER_TERMINAL_FAILURE` | The overflow writer loop itself caught a terminal failure for one record (e.g. an `Error` escaping the insert guard). The record is settled as lost and the loop continues. | Never auto-recovers. |
| `UNRESOLVED_AT_CLOSE` | A shutdown exhausted its bounded wait with a capture active (staging outcome unconfirmed at shutdown time). Keyed to the snapshot's identity. | ONLY when a staging sweep terminally resolves a staging row whose event carries THAT identity. A sweep with failed/held rows — or an empty sweep — proves nothing and recovers nothing. Otherwise: operator disposition. |
| `HEALTH_DOC_CORRUPT` | The stored health document did not parse. Its raw value was quarantined into the fresh document as evidence (see below), not discarded. | Never auto-recovers (the quarantine block stays in the document as the record). |
| `UNKNOWN_LOSS` | Startup reconciliation of the offer journal (see the durability contract) found incidents a previous process offered but never durably applied and never accounted as dropped — lost before the durable boundary. The count is exact; WHICH incidents they were is unknowable, and the incident's detail says so. | Never auto-recovers. It is the exposure of a loss, not a loss that can be undone; an operator disposition acknowledges it. |
| `LIFECYCLE_CORRUPT` | The stored lifecycle record (the successor fence) did not parse. The record is preserved untouched as evidence; ownership reads as unknown and successors wait fail-safe. | Never auto-recovers (only an operator can retire a corrupt lifecycle record). |
| `FENCE_ORPHAN_CLEARED` | A successor cleared a fence whose owner process was provably dead. Recorded as `RECOVERED` history after the clearing mutation lands. | n/a (it is the record of a recovery action). |
| `PRE_STAGE_INTERRUPTED` | The active task was interrupted BEFORE staging — pre-stage cancelled / unknown outcome, counted separately (`preStageInterruptedAtClose`), never as resolved. | Never: `UNRECOVERABLE` by definition. |

The **successor fence** lives in its OWN durable value — the
**lifecycle record** (`capture_lifecycle_v1` in a dedicated
prefs file), separate from the health document (audit
7235948077, A03). The separation is the point: the health
document can be corrupt, quarantined, or lost without any
effect on the exclusion, because fence evaluation reads ONLY
the lifecycle record. (Stores written before the separation
carry the fence inside the health document; the first
evaluation adopts it into the lifecycle record, which is then
the only authority.) When a coordinator's bounded close gives
up with its worker and/or overflow writer still alive, it
publishes the fence — with the liveness sample taken INSIDE
the store lock, in the same critical section as the write, so
a thread that exits during the publish cannot leave a stale
fence behind (the exit-clear serialises through the same
lock). The fence owner is the publishing PROCESS's identity
(PID + process start time), not a random coordinator UUID. A
replacement coordinator over the same store refuses to
process captures while the predecessor is alive — its threads
exiting clear their halves of the fence — and a fence is
cleared ONLY by proof: both halves cleared by the threads'
own exits, or the owner process provably dead (PID gone, or
PID reused with a different start time). **Age is never
proof** (the old 600 s legacy timeout is gone: a stuck thread
can outlive any bound), and **corruption is never proof**: an
unparseable lifecycle record is preserved untouched as
evidence, ownership reads as unknown, and the successor waits
fail-safe; a legacy fence without a process identity is
unknown ownership and likewise never cleared. When the fence
does prove stale, the successor clears it, records a
`FENCE_ORPHAN_CLEARED` incident, and proceeds — a fresh
process never stalls indefinitely on a PROVABLY dead
predecessor's fence, and never bypasses anything else. If the
store cannot be READ, the successor waits fail-safe: an
unverifiable fence is never treated as no fence. All
read-modify-writes serialise process-wide (a file lock in
production), not per coordinator instance.
`degradedState().successorFenced` reports an active — or
unknown — fence as degradation.

## Durability contract of the health document itself

Tightened by the PR #5 audit of 28 Nov 2026:

- **Bounded nonblocking ingress.** No caller thread — above
  all not the listener (Binder) callback via `recordOverflow`
  — ever performs health-document I/O synchronously. Incidents
  are offered to a bounded queue (100); a dedicated
  health-writer thread performs the durable writes. An offer
  that cannot be queued is counted
  (`healthIncidentsDropped`), logged, and journaled as
  known-dropped (see the offer journal below). The ONE
  synchronous durable write on the offer path is the offer
  journal itself — a single-key `commit()` of two counters to
  a dedicated minimal store, only on incident-offer paths
  (failures and drops, never the per-capture hot path).
- **Durable acceptance boundary.** An incident is ACCEPTED
  (durable) only when its `writeCaptureHealth` call has
  RETURNED — production writes with `SharedPreferences`
  `commit()`, and a failed commit throws (counted in
  `healthStoreFailures`, surfaced in the degraded reasons),
  never a silent no-op. The previous `apply()` was not an
  acknowledgement: a SIGKILL could beat its disk flush.
  `close()` flushes the incident queue (bounded) before
  returning, so incidents a close itself records are durable
  by the time it returns.
- **Pre-durable loss is exposed, not silent (audit
  7235948077, A06).** An incident offered to the queue but not
  yet written when the process dies is still lost AS AN
  INCIDENT — that much is not claimed otherwise. What changed
  is that the loss no longer vanishes without a trace. Every
  offer first advances the **offer journal** — two durable
  counters (`offered`, `dropped`) in their own prefs file,
  written synchronously at offer time — and every durable
  batch application advances the document's `applied_count`.
  At the next start, before applying anything, the health
  writer reconciles: `offered − dropped − applied` is the
  exact count of incidents lost before the durable boundary,
  recorded as one aggregate `UNKNOWN_LOSS` incident (see the
  category table). Queue-saturation drops are journaled as
  known-dropped at the moment they happen, so they are never
  double-reported as unknown. The residual bound is the
  journal itself: if the journal value is lost or corrupt,
  the gap is undetectable — the journal is a single tiny
  `commit()`-written value precisely to make that window as
  small as the platform allows, and a failed journal write is
  counted as a store failure rather than swallowed.
- **No silent reset.** A store read that fails aborts the
  mutation (an unreadable document is never blindly
  overwritten). A stored health document that does not parse
  is quarantined: its raw text is carried into the fresh
  document in a `quarantine` block as evidence and a
  `HEALTH_DOC_CORRUPT` incident is recorded. The lifecycle
  record is treated more strictly still: a corrupt lifecycle
  record is never overwritten or quarantined into a fresh one
  — it stays byte-identical as evidence while successors wait
  (see the fence section above).
- **Unresolved losses are exposed independently.**
  `degradedState().unresolvedLosses` lists the identity-keyed
  loss/unknown incidents (OPEN `OVERFLOW_RECORD_LOST` and
  `UNRESOLVED_AT_CLOSE`, UNRECOVERABLE
  `PRE_STAGE_INTERRUPTED`) separately from component health: a
  database that starts accepting inserts again closes
  `DB_PERMANENT_FAILURE`, but every lost record stays listed
  until its own reconciliation or an operator disposition.

## When the pipeline is DEGRADED (volatile signals)

Any one of these is enough:

| Signal | Meaning |
|---|---|
| `droppedOverflow > 0` | Snapshots were dropped by queue saturation before durable staging. Each has a metadata-only `OVERFLOW_DROPPED` row — unless its record was itself lost (next row). |
| `overflowRecordDropped > 0` | The durable overflow record ultimately failed (writer queue full, writer already terminated, or the database kept failing across all retries). Evidence for those snapshots is counters + log lines only. |
| `unresolvedAtClose > 0` | A capture task was still active when a shutdown exhausted its bounded wait. Its staging transaction either committed or rolled back atomically; nobody verified which at shutdown time. |
| `preStageInterruptedAtClose > 0` | A capture task was interrupted before staging during shutdown: pre-stage cancelled / unknown outcome — its own category, NOT resolved. |
| `overflowWriterFailures > 0` | The overflow writer loop caught a terminal failure for a record and settled it as lost (the loop itself survived). |
| `overflowPending > 0` | Overflow records are still waiting to be persisted. Transient during a burst; a state that never returns to zero means the writer/database is stuck. |
| any `OPEN` persistent incident, or an active successor fence | See the persistent layer above — these survive restarts. |

Logging can never fail the pipeline: every log call on the
writer/worker paths goes through a best-effort wrapper, separated
from the durable-write attempts it describes (a throwing log
sink — the production Store persists its whole log on the
calling thread and can throw — must not kill the writer loop or
strand the outstanding accounting; the loop additionally has an
outer terminal cleanup that settles the record and records a
`WRITER_TERMINAL_FAILURE` incident).

## Privacy: pre-consent paths are metadata-only

No notification text is retained before consent attaches:

- `OVERFLOW_DROPPED` rows: package + post time only, no title.
- `NO_KEYWORD` rows (no route matched — the gate never ran) and
  `NO_AMOUNT` rows (gate ran, no amount): **no title text at
  all** since the 28 Nov re-review — not even a redacted snippet.
  A redacted title is still the owner's notification text
  retained without a consented capture. The persistent log lines
  for these paths are metadata-only too.

## What the owner sees

- **History** shows one `OVERFLOW_DROPPED` row per dropped
  snapshot: package + post time + outcome, no title text, no
  amount, no event link.
- **The local log** carries the loud overflow line (emitted by
  the overflow-writer thread), the unresolved-at-close and
  pre-stage-interrupted lines with package + post time, the
  writer's give-up lines, and — at every startup — the
  persistent degraded state and incident list when present.
- **Diagnostics** can read the full state via
  `CaptureCoordinator.degradedState()` / `counters()`.

## Recovery, in order

1. **Rescan while the notifications are still posted.** The
   listener re-reads the shade on reconnect (and on manual
   rescan) and re-captures; dedupe by `dedupe_key` makes the
   re-capture land exactly once. This is the ONLY recovery for a
   snapshot dropped before staging — and only while its
   notification is still posted and < 24 h old.
2. **Restart the listener / app.** The startup staging sweep
   re-promotes every committed staging row exactly once
   (idempotent by `dedupe_key` / `legacy_qkey`), which resolves
   the accepted-then-killed case. An unresolved-at-close
   incident closes only when the sweep terminally resolves the
   row carrying that snapshot's identity — if its transaction
   rolled back there is no row, the sweep proves nothing, and
   the incident stays OPEN until rescan (path 1) or an
   operator disposition (`CaptureCoordinator.dismissIncident`,
   which marks it UNRECOVERABLE — acknowledged, not recovered).
3. **If overflow records keep failing, fix the database first.**
   A persistently failing DB is the one component whose failure
   degrades the evidence itself to counters; check storage
   health before trusting any count.

## Process-death evidence (28 Nov)

Actual process death for this pipeline is proven by the
two-phase oracle in `scripts/capture_death_tests.sh`
(`CaptureDeathPhaseA` / `CaptureDeathPhaseB`, modelled on the
Stage C oracle): PhaseA stages a capture durably from a real
shade notification, blocks its delivery, writes the marker, and
kills its own process; the runner fails closed unless the
process is independently observed gone and PhaseB runs in a
fresh process (PID + start time). PhaseB asserts exactly-once
sweep recovery, an actual shade rescan dedupe, and both
dismissed cases. Scenario **c07** (PR #5 audit 28 Nov) applies
the same contract to the health document: PhaseA drives an
overflow drop, crosses the durable acceptance boundary
(`flushHealthIncidents` — the incident's `commit()` write has
returned), verifies the incident from durable storage, and
kills itself; PhaseB requires the incident to still be in the
document in the fresh process. Scenario **c08** (audit
7235948077, A06) covers the pre-durable side: PhaseA drives
the same overflow drop with the health store's durable write
gated (the writer parks inside the write — the incident is
offered and journaled but never durable, no flush) and kills
itself; PhaseB requires the incident itself to be ABSENT from
the document (it is genuinely lost) and an `UNKNOWN_LOSS`
incident with count ≥ 1 to be present — the loss exposed, not
the incident survived. A negative control (marker without
kill) and a fake-adb preflight control run with it in CI.

Honest bounds: the kill is a **self-kill**
(`Process.killProcess`, SIGKILL to self) at the crash window —
not a host-side `force-stop` / `kill -9` — and it executes only
where an emulator exists (the CI `process-death-oracle` job).
The older `ProcessDeathHarnessTest` remains as an in-process
restart-boundary regression; it is not, and is no longer
presented as, kill evidence.

## What is NOT claimed

No zero-loss claim is made, anywhere. Accounting is not payment
recovery: a snapshot dropped before staging whose notification
was dismissed or expired before a rescan is unrecoverable, and
its overflow row (or, in the worst case, a counter) is the only
evidence it ever existed. The degraded state exists so that this
loss is always *visible and quantified*, never silent.

Two sharper forms of the same honesty, from audit 7235948077:

- **Metadata recovery is not payment recovery.** An
  `OVERFLOW_RECORD_LOST` incident closing means the dropped
  snapshot's evidence row landed — the payment itself is still
  uncaptured until a rescan actually captures it, and the
  aggregate `OVERFLOW_SNAPSHOT_DROPPED` incident stays OPEN to
  keep that distinction visible.
- **Pre-durable incidents are lost; only their count
  survives.** `UNKNOWN_LOSS` proves THAT incidents were lost
  before the durable boundary and exactly how many — never
  which ones. If the offer journal itself is lost, even the
  count is gone; that residual window is documented above, not
  hidden.
