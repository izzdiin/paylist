# Stage D P1s (e) + (f) — work notes (PR #5, audit review 6049617822, 25 Nov 07:22 WIB)

Branch: `stage-d/collector-security` (base `phase-1.3`, head `be25429`). PR #5 stays DRAFT — no merge.
Checkout: `/home/warganet/paylist-stage-d` (laptopgrt2). Local notes dir: `~/workspace/c2-impl/review9-staged/`.

Audit review (exact wording, fetched via public API):
- **P1 capture loss before persistence:** CaptureCoordinator's bounded executor rejection handler explicitly polls/drops oldest queued snapshot; if retry fails drops new snapshot. Callback returns before durable staging; process death or a burst can lose accepted payment before Room, with a counter not a durable payment record. Define explicit acceptance boundary, durable ingress or recoverable rescan with limitations, durable overflow alarm/accounting and deterministic saturation + process-kill regression. Do not claim zero-loss captures from this design. Include queue-full sweep/control tasks, not just notification snapshots.
- **P1 integration gate:** PR #5 flips CAPTURE_TO_ROOM_READY=true although PR #4 safety fixes are not integrated. Restore false or isolate activation to non-release test configuration pending combined branch with all R01–R08, process-death and Stage D tests.

## (e) Design — written before coding

### 1. Acceptance boundary (normative, in code + docs)
- **RECEIVED** — `capture()` returned `QUEUED`: the snapshot is in the in-memory bounded executor queue. Not durable.
- **ACCEPTED (durable)** — `EventRepository.stageCapture()` returned without exception: event row + staging row committed in one Room transaction. Only from this point does the capture survive process death; the sweep re-promotes orphaned staging rows after restart.
- **ENQUEUED** — the staged capture reached exactly one target store (legacy `saveQueueSync`+readback, or Room delivery insert + staging delete in one transaction).
- The listener callback's return is NOT an acceptance ack: Android `NotificationListenerService` has no ack semantics; `onNotificationPosted` returning only means the snapshot was received (or overflow-dropped with a durable record).
- `capture()` now returns `CaptureReceipt` (`QUEUED` / `DROPPED_OVERFLOW`); the listener logs `DROPPED_OVERFLOW` loudly, and rescan counts only `QUEUED` receipts as handed off.
- `CaptureCounters.accepted` counted queue receipts, which contradicts the boundary — renamed to `received`; `staged` is the accepted count. No test or main-code caller uses `.accepted` (verified by grep).

### 2. Durable ingress / recovery (no sync staging on the callback thread)
- No synchronous staging on the callback thread: it would block the Binder callback on Room I/O + PrivacyGate.
- Recovery for RECEIVED-but-not-ACCEPTED loss is the rescan path (`rescanActive`, on listener connect + manual): re-reads still-posted notifications, dedupes by `dedupe_key`.
- Stated limitations (in code + docs): rescan recovers only notifications still active in the shade and < 24 h old; dismissed/expired notifications are unrecoverable; the pre-acceptance window itself is unmeasurable by construction (no durable record exists for a snapshot that never reached the executor and was never overflow-evicted).
- Overflow evictions DO get a durable record (below), so burst loss is evidenced per snapshot.

### 3. Durable overflow alarm / accounting
- New outcome `CaptureOutcome.OVERFLOW_DROPPED` in `capture_log` (no schema change, no migration): metadata-only row — package, postTime, PrivacyGate-scanned title snippet (≤ 40 chars, same `snippet()` as `NO_KEYWORD` rows); no amount (unknown pre-parse), no event link, no content hash (hash of un-gated raw text would be a hash of possibly-secret content, consistent with `PURE_AUTH` / `UNLISTED_PACKAGE` practice).
- Written from the rejection handler via `runBlocking` single-row insert (bounded, rare path only). DAO null or insert failure → in-memory `droppedOverflow` + `captureLogFailures` counters + loud `logLocal` line.
- Operator-visible: `HistoryActivity` outcome filter gains `OVERFLOW_DROPPED` → "Antrean penuh (terbuang)"; rows are per-category deletable like other unlinked outcomes. History (LH-02) is the operator surface; no new table.
- Task tagging: every executor submission is a `CaptureTask(kind, snapshot?, block)`; kinds `NOTIFICATION` / `SWEEP` / `CONTROL` (`CONTROL` reserved — no control task exists yet; handler treats `SWEEP` and `CONTROL` identically: never silently dropped).
- Rejection policy:
  - new NOTIFICATION → evict oldest queued NOTIFICATION (durable overflow record for it); if the queue holds no notification, drop the new one (durable record for it).
  - new SWEEP/CONTROL → evict oldest queued NOTIFICATION to admit it (durable record); if none exists (queue all control — unreachable in practice: at most a couple of control tasks ever outstanding) → run it inline on the caller thread, counted (`controlInline`), documented last resort. A sweep task is never silently dropped, so `sweepNow()` keeps its synchronisation property (returns only after the sweep ran).
  - `capture()` after `close()` → `RejectedExecutionException` caught → `DROPPED_OVERFLOW` + durable record attempt (previously an uncaught throw on the Binder thread).
  - `close()` drains `shutdownNow()` leftovers → durable overflow record per queued NOTIFICATION snapshot. Graceful shutdown only — a SIGKILL gives no chance to run it (documented).
- `sweepNow()` switches from `executor.submit` (which wraps the runnable in a `FutureTask`, hiding the tag from the handler) to `executor.execute` + `CountDownLatch`.

### 4. Tests (new `CaptureOverflowTest.kt`, unit, real entrypoint)
- Constructor gains `executorCapacity: Int = EXECUTOR_CAPACITY` so tests use a small queue (e.g. 3); production capacity stays 500. Tests wire `FakeCaptureLogDao`.
- Saturation (notifications): executor blocked deterministically (modeReader awaits a latch), 1 running (already staged) + queue full + 1 extra → oldest queued evicted with an `OVERFLOW_DROPPED` row; release; `sweepNow`; assert every submitted snapshot is either an event row or an overflow row (no silent loss), evicted one has no event.
- Queue-full sweep/control: full queue of notifications + `sweepNow()` → sweep admitted by evicting the oldest notification (overflow row), sweep executes (returns, no hang), no control task silently dropped.
- Restart boundary (process-kill equivalent): blocker ACCEPTED (staged, enqueue blocked) + queued victims RECEIVED-only; `close()` (simulated death) → new coordinator over the same store → sweep → blocker exactly one delivery; victims have no event but each has an `OVERFLOW_DROPPED` row; live recovery for them is rescan-only while still posted.
- Capture-after-close: returns `DROPPED_OVERFLOW` + overflow row, no throw.
- Process-kill limitation (documented honestly): a true SIGKILL mid-flight cannot be asserted in CI — the test process itself would die, and instrumented tests use an in-memory DB anyway. Deterministic equivalent is coordinator replacement over the same durable store + `close()`-drain evidence. A file-backed-DB two-phase instrumented kill test is noted as future work, not claimed.
- Existing `f18_executorBurst_noLossNoDuplicates` renamed to `f18_executorBurst_belowCapacity_allLandExactlyOnce` (same assertions; "no loss" phrasing is the kind of claim the audit rejects).

### 5. No zero-loss claims anywhere (code comments, docs, test names).

## (f) Design
- `PaylistApplication.kt:70` `CAPTURE_TO_ROOM_READY`: `true` → `false`, with an updated KDoc: restored to false per Stage D audit 25 Nov 2026; flip to `true` only when PR #4 and Stage D combine with all R01–R08, process-death and Stage D tests.
- No test on this branch references the flag (verified by grep); the `CutoverEndToEndTest` guard test the plan mentions lives on the PR #4 branch (`phase-1.3`), not on `stage-d/collector-security`. `runCutover()` with `false` returns the "menunggu Stage D" message — the intended gate behaviour.
- Merge note for later: the flag must flip to `true` when PR #4 and Stage D are combined (parent tracks this).

## Files to change (planned)
- (e) commit 1: `collector/CaptureCoordinator.kt`, `data/entity/CaptureLogEntity.kt`, `HistoryActivity.kt`, `PaylistListenerService.kt`, new `test/.../collector/CaptureOverflowTest.kt`, rename F18 burst test in `CaptureStagingTest.kt`. Durable overflow accounting reuses the existing `capture_log` table (rows countable via the existing `countHistory(outcome=...)`); no new DAO method, no Diagnostics change — History is the D4 operator surface for this.
- (f) commit 2: `PaylistApplication.kt` only (+ test adjustments if any exist — none found).

## Status — DONE (25 Nov 2026, ~13:0x WIB)
- [x] (e) implemented + unit tests green locally (492 total)
- [x] (e) pushed as `bfc59f8`, CI green — run 45553020145 (both jobs success)
- [x] (f) implemented + unit tests green locally (492 total)
- [x] (f) pushed as `c598f33`, CI green — run 45553968019 (both jobs success)
- PR #5 head is now `c598f33`, still DRAFT, open. Working tree clean on laptopgrt2.

## Commits (branch `stage-d/collector-security`, base `phase-1.3`)
- (e) `bfc59f8` "Stage D audit P1 (e): durable capture-overflow accounting + acceptance boundary" — 7 files, +787/−37.
  - `app/src/main/java/com/netter/paylist/collector/CaptureCoordinator.kt`
  - `app/src/main/java/com/netter/paylist/data/entity/CaptureLogEntity.kt` (new `OVERFLOW_DROPPED` outcome)
  - `app/src/main/java/com/netter/paylist/HistoryActivity.kt` (filter entry "Antrean penuh (terbuang)")
  - `app/src/main/java/com/netter/paylist/PaylistListenerService.kt` (receipt handling; rescan counts only QUEUED)
  - `app/src/test/java/com/netter/paylist/collector/CaptureOverflowTest.kt` (new, 4 tests)
  - `app/src/test/java/com/netter/paylist/collector/CaptureStagingTest.kt` (F18 test renamed, same assertions)
  - `app/src/test/java/com/netter/paylist/history/HistoryTest.kt` (LH-T08 allowlist += CaptureOverflowTest)
- (f) `c598f33` "Stage D audit P1 (f): restore CAPTURE_TO_ROOM_READY to false" — 1 file, +12/−5.
  - `app/src/main/java/com/netter/paylist/PaylistApplication.kt` only; no test referenced the flag on this branch, so no test adjustment was needed. Verified: the `CutoverEndToEndTest` guard test lives on the PR #4 branch (`phase-1.3`), not on `stage-d/collector-security`.

## Test evidence
- Local (laptopgrt2, gradle 8.9, ANDROID_HOME=/opt/android-sdk): 492 unit tests, 0 failures, 0 errors — after (e) and again after (f).
- CI run 45553020145 (head `bfc59f8`, (e)): success — assembleDebug + unit tests success; instrumented tests (API 28 emulator) success. Instrumented total still 30 (no new instrumented test was added; the overflow tests are JVM unit tests).
- CI run 45553968019 (head `c598f33`, (f)): success — both jobs success.
- Two real bugs found by the tests during the work (fixed, not waived):
  1. `sweepNow()` submitted against a still-full queue (correctly, per policy) evicts a notification — the saturation test now waits for drain before the final `sweepNow()`.
  2. With a custom `RejectedExecutionHandler`, `execute()` does NOT throw on shutdown — the handler runs instead. Fixed with a per-task `dropped` flag (`CaptureTask.dropped`) + `tryAdmit()` guard; this is what makes capture-after-close report `DROPPED_OVERFLOW`.

## Documented limitations (honest, in code + tests)
- No zero-loss claim anywhere. The RECEIVED → ACCEPTED window is a real loss window; rescan recovers only still-posted (< 24 h) notifications; dismissed/expired ones are unrecoverable; the window itself is unmeasurable by construction.
- True SIGKILL mid-flight cannot be asserted in CI (test process would die; instrumented tests use an in-memory DB). Deterministic equivalent implemented: coordinator replacement over the same durable store + `close()`-drain evidence. A file-backed-DB two-phase instrumented kill test is noted as future work, not claimed.
- `close()`-drain covers graceful shutdown only, not SIGKILL.
- `CONTROL` task kind is reserved (no control task is submitted yet); the handler treats it like SWEEP.
- The audit's other asks in the same review (real entrypoint boundary tests, durable ownership conservation at commit/abort/startup recovery, missing secret + live capture after redrain, D2/D3 focused audit) are OUT of scope for (e)/(f) and were not attempted.

## Merge note (for parent / later)
- `CAPTURE_TO_ROOM_READY` must flip back to `true` when PR #4 and Stage D are combined on one branch with all R01–R08, process-death and Stage D tests green. The flag's KDoc now says this in code.
