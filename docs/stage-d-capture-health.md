# Capture health: the degraded state (Stage D)

Definition required by the PR #5 re-review of 27 Nov 2026 and
implemented by `CaptureCoordinator.degradedState()` /
`CaptureDegradedState`. This document is the owner-facing half of
that definition: when the capture pipeline is degraded, what the
owner sees, and how it recovers.

## When the pipeline is DEGRADED

Any one of these is enough:

| Signal | Meaning |
|---|---|
| `droppedOverflow > 0` | Snapshots were dropped by queue saturation before durable staging. Each has a metadata-only `OVERFLOW_DROPPED` row — unless its record was itself lost (next row). |
| `overflowRecordDropped > 0` | The durable overflow record ultimately failed (writer queue full, writer already terminated, or the database kept failing across all retries). Evidence for those snapshots is counters + log lines only. |
| `unresolvedAtClose > 0` | A capture task was still active when a shutdown exhausted its bounded wait. Its staging transaction either committed or rolled back atomically; nobody verified which at shutdown time. |
| `overflowPending > 0` | Overflow records are still waiting to be persisted. Transient during a burst; a state that never returns to zero means the writer/database is stuck. |

## What the owner sees

- **History** shows one `OVERFLOW_DROPPED` row per dropped
  snapshot: package + post time + outcome, no title text, no
  amount, no event link. (Since the 27 Nov re-review the row
  stores no title snippet: the drop happens before route/consent
  evaluation, so no text of that snapshot may be retained.)
- **The local log** carries the loud overflow line (emitted by
  the overflow-writer thread), the unresolved-at-close line with
  package + post time, and the writer's give-up lines.
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

## What is NOT claimed

No zero-loss claim is made, anywhere. Accounting is not payment
recovery: a snapshot dropped before staging whose notification
was dismissed or expired before a rescan is unrecoverable, and
its overflow row (or, in the worst case, a counter) is the only
evidence it ever existed. The degraded state exists so that this
loss is always *visible and quantified*, never silent.
