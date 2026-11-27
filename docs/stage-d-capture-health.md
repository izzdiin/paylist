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

Incidents aggregate by category. Each carries a status:

| Status | Meaning |
|---|---|
| `OPEN` | The failure is current: the pipeline is degraded NOW. |
| `RECOVERED` | The component that failed demonstrably worked again (a later durable write landed / a later full staging sweep completed). History, not degradation. |
| `UNRECOVERABLE` | An outcome that can never be retroactively confirmed (a task interrupted before staging; a capture unresolved at close). History, and never folded into "resolved". |

| Category | Raised when | Recovers when |
|---|---|---|
| `OVERFLOW_SNAPSHOT_DROPPED` | A snapshot is dropped by queue saturation before staging (its metadata-only overflow row is the evidence). | Never auto-recovers; the rescan of a still-posted notification is the recovery path (see below). |
| `OVERFLOW_RECORD_LOST` | The durable overflow record itself is given up (writer queue full, writer terminated, or retries exhausted). | A later overflow insert that needed retries lands. |
| `DB_PERMANENT_FAILURE` | An overflow insert exhausts its retry budget — the database is the failing component. Its own category, distinct from losing one record. | A later overflow insert lands. |
| `WRITER_TERMINAL_FAILURE` | The overflow writer loop itself caught a terminal failure for one record (e.g. an `Error` escaping the insert guard). The record is settled as lost and the loop continues. | Never auto-recovers. |
| `UNRESOLVED_AT_CLOSE` | A shutdown exhausted its bounded wait with a capture active (staging outcome unconfirmed at shutdown time). | A later full staging sweep completes (the sweep is the component that resolves it). |
| `PRE_STAGE_INTERRUPTED` | The active task was interrupted BEFORE staging — pre-stage cancelled / unknown outcome, counted separately (`preStageInterruptedAtClose`), never as resolved. | Never: `UNRECOVERABLE` by definition. |

The same document carries the **successor fence**: when a
coordinator's bounded close gives up with its worker and/or
overflow writer still alive, it records the fence (owner,
timestamp, which threads are alive). A replacement coordinator
over the same store refuses to process captures until the
predecessor's threads have actually exited (their exit clears
their half of the fence). `degradedState().successorFenced`
reports an active fence as degradation, with the predecessor's
identity in the reasons.

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
   both the accepted-then-killed case and an unresolved-at-close
   capture whose transaction did commit.
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
dismissed cases. A negative control (marker without kill) and a
fake-adb preflight control run with it in CI.

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
