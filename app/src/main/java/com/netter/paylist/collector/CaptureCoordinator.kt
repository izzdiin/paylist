package com.netter.paylist.collector

import com.netter.paylist.Route
import com.netter.paylist.core.model.CanonicalEvent
import com.netter.paylist.core.model.Delivery
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.core.model.EventData
import com.netter.paylist.core.model.EventMetadata
import com.netter.paylist.core.model.EventSchema
import com.netter.paylist.core.model.EventSource
import com.netter.paylist.core.model.RouteStamp
import com.netter.paylist.data.EventInsertOutcome
import com.netter.paylist.data.EventRepository
import com.netter.paylist.data.dao.CaptureLogDao
import com.netter.paylist.data.entity.CaptureLogEntity
import com.netter.paylist.data.entity.CaptureOutcome
import com.netter.paylist.data.entity.CaptureStagingEntity
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.migration.LegacyEnqueue
import com.netter.paylist.migration.LegacyQkey
import com.netter.paylist.parser.ParserInput
import com.netter.paylist.parser.ParserRegistry
import com.netter.paylist.parser.PaymentParser
import com.netter.paylist.security.SensitiveDataRedactor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Where the capture log / local log lines go (production: `Store`). */
fun interface CaptureLogger {
    fun logEvent(pkg: String, text: String)
}

/** Extended logger: per-package events plus app-level lines. */
interface CaptureLogSink : CaptureLogger {
    fun logLocal(text: String)
}

/** Durable capture counters (design §8.3 "Measured, not assumed"). */
data class CaptureCounters(
    /**
     * Snapshots handed to [CaptureCoordinator.capture] — RECEIVED,
     * not accepted: a received snapshot is only in the in-memory
     * executor queue. The accepted (durable) count is [staged].
     */
    val received: Long = 0,
    val staged: Long = 0,
    val enqueued: Long = 0,
    val parked: Long = 0,
    val droppedOverflow: Long = 0,
    val droppedUnclassified: Long = 0,
    val identityCollision: Long = 0,
    val duplicates: Long = 0,
    val roomCapturesDuringDraining: Long = 0,
    val unlistedPackage: Long = 0,
    val noKeyword: Long = 0,
    val noAmount: Long = 0,
    val sweptOrphaned: Long = 0,
    /** Staging rows held on `payload_sha256` mismatch (F18), never dispatched. */
    val stagingMismatch: Long = 0,
    /** Pure-auth notifications dropped at capture (§7.1(a), §7.3A(j)). */
    val pureAuth: Long = 0,
    /** Raw snapshots evicted from the store (overflow; §7.3B at claim). */
    val rawSnapshotsEvicted: Long = 0,
    /** Capture-log inserts that failed (LH-04(d) — surfaced, never silent). */
    val captureLogFailures: Long = 0,
    /** Notifications dropped: no consenting route (D5, F15). */
    val noConsent: Long = 0,
    /**
     * RETIRED: sweep/control tasks executed inline on the caller
     * thread. The admission redesign (PR #5 follow-up) removed
     * inline execution entirely — this stays in the data class
     * for shape compatibility and is always 0.
     */
    val controlInline: Long = 0,
    /**
     * Sweep/control tasks refused admission because the bounded
     * control backlog was also full (counted + logged, and
     * `sweepNow` throws instead of hanging or running the sweep
     * on the caller thread — never a silent drop).
     */
    val controlDropped: Long = 0,
    /**
     * Overflow records whose durable write ultimately failed:
     * the overflow-writer queue was full, or the database kept
     * failing across all retries. The drop is then evidenced
     * only by the counters + the loud local log line — stated
     * honestly, this is the residual gap when the database
     * itself is the failing component.
     */
    val overflowRecordDropped: Long = 0,
    /** Overflow records currently waiting in the overflow-writer queue. */
    val overflowPending: Long = 0,
    /**
     * Snapshots whose capture task was still active when [close]
     * exhausted its bounded shutdown wait (Stage D audit follow-up,
     * PR #5 re-review 27 Nov): the task was interrupted but did
     * not finish, so its outcome is explicitly UNRESOLVED — it may
     * or may not have committed its staging row (a Room transaction
     * is atomic either way); recovery is the startup sweep (when
     * the staging row committed) plus rescan while the
     * notification is still posted. Never silently assumed lost
     * or saved.
     */
    val unresolvedAtClose: Long = 0,
    /**
     * Captures whose active task was interrupted by [close]
     * BEFORE its staging outcome could be confirmed (PR #5
     * re-review 28 Nov): the task exited after the close
     * interrupt without completing, so its outcome is recorded
     * as PRE-STAGE CANCELLED / UNKNOWN — explicitly NOT
     * "resolved". The staging transaction is atomic (committed
     * or rolled back), but from the coordinator's side the
     * outcome is unconfirmed; recovery is rescan while the
     * notification is still posted plus the startup sweep.
     */
    val preStageInterruptedAtClose: Long = 0,
    /**
     * Terminal failures of the overflow writer loop itself
     * (PR #5 re-review 28 Nov): a [CaptureCoordinator.writeOverflowRecord]
     * pass threw OUTSIDE its guarded insert path (e.g. the log
     * sink threw), the loop caught it at the outer layer, settled
     * the record as lost, and kept running — the writer must
     * never die silently and strand the outstanding accounting.
     */
    val overflowWriterFailures: Long = 0,
)

/**
 * Owner-visible capture health (Stage D audit follow-up, PR #5
 * re-review 27 Nov — the "degraded state" definition the review
 * required). The capture pipeline is DEGRADED when any snapshot
 * that reached this coordinator was dropped by saturation, any
 * durable overflow record was ultimately lost, any capture was
 * left unresolved by a shutdown, or overflow records are still
 * waiting to be persisted. A degraded state is visible three
 * ways: this snapshot (diagnostics), the OVERFLOW_DROPPED rows
 * in History (one per dropped snapshot, metadata-only), and the
 * loud local log lines. Recovery, in order: (1) rescan while the
 * notifications are still posted (listener reconnect / manual
 * rescan re-reads the shade and dedupes), (2) restart the
 * listener (the startup sweep re-promotes committed staging
 * rows), (3) if overflow records keep failing, the database is
 * the failing component — check storage/DB health before
 * trusting the counters again. No zero-loss claim: a snapshot
 * dropped before staging whose notification was dismissed is
 * unrecoverable; the overflow row is its only evidence.
 */
data class CaptureDegradedState(
    val degraded: Boolean,
    /** Snapshots dropped by queue saturation (each overflow-recorded). */
    val overflowDroppedSnapshots: Long,
    /** Overflow records whose durable write ultimately failed. */
    val overflowRecordsLost: Long,
    /** Captures left unresolved by a bounded shutdown (see [CaptureCounters.unresolvedAtClose]). */
    val unresolvedAtClose: Long,
    /** Overflow records not yet persisted. */
    val overflowPending: Long,
    /** Capture-log inserts that failed (all outcomes). */
    val captureLogFailures: Long,
    /** Human-readable reasons (Indonesian, matching the log lines). */
    val reasons: List<String>,
    /**
     * Persistent incident records (PR #5 re-review 28 Nov):
     * incidents recorded in the durable capture-health document,
     * which survive process death / restart — unlike the volatile
     * counters above. Empty when no health store is wired.
     */
    val persistentIncidents: List<CaptureIncident> = emptyList(),
    /**
     * True while a predecessor coordinator's fence is recorded
     * in the health document (its worker/writer were still alive
     * when its bounded close gave up): a replacement coordinator
     * must not process captures until the fence clears.
     */
    val successorFenced: Boolean = false,
)

/** Incident categories in the persistent capture-health document. */
object CaptureIncidentCategory {
    /** A snapshot was dropped by saturation / at close (evidence: overflow row). */
    const val OVERFLOW_SNAPSHOT_DROPPED: String = "overflow_snapshot_dropped"

    /** A durable overflow record was ultimately lost (queue full / writer gone). */
    const val OVERFLOW_RECORD_LOST: String = "overflow_record_lost"

    /** The database kept failing across the whole overflow retry budget. */
    const val DB_PERMANENT_FAILURE: String = "db_permanent_failure"

    /** The overflow writer loop itself failed terminally for a record. */
    const val WRITER_TERMINAL_FAILURE: String = "writer_terminal_failure"

    /** A capture was still running when close() exhausted its bounded wait. */
    const val UNRESOLVED_AT_CLOSE: String = "unresolved_at_close"

    /** A capture was interrupted by close() before its staging outcome was confirmed. */
    const val PRE_STAGE_INTERRUPTED: String = "pre_stage_interrupted"
}

/** Lifecycle of a persistent capture incident. */
object CaptureIncidentStatus {
    /** Still degrading the pipeline; operator attention is owed. */
    const val OPEN: String = "OPEN"

    /** The failing component demonstrably worked again; the incident closed itself. */
    const val RECOVERED: String = "RECOVERED"

    /** The outcome can never be retroactively confirmed (kept as history). */
    const val UNRECOVERABLE: String = "UNRECOVERABLE"
}

/** One persistent capture-health incident (durable, operator-visible). */
data class CaptureIncident(
    val category: String,
    val status: String,
    val firstAtMs: Long,
    val lastAtMs: Long,
    val count: Long,
    /** Metadata only (package + post time + component) — never notification text. */
    val detail: String,
)

/**
 * Durable store for the capture-health document (PR #5 re-review
 * 28 Nov): a single JSON document holding the persistent
 * incidents + the successor fence. Production backs this with
 * the `paylist` SharedPreferences (the [com.netter.paylist.Store]
 * pattern — `apply()`, never a blocking commit on a caller
 * thread); tests use an in-memory fake. Reads/writes are
 * synchronous and cheap; the coordinator serialises them under
 * its health lock and treats every access as best-effort (a
 * failing health store must never break capture).
 */
interface CaptureHealthStore {
    fun readCaptureHealth(): String?
    fun writeCaptureHealth(json: String)
}

/**
 * The capture executor owner (design §8.3 C21, Stage D).
 *
 * Acceptance boundary (Stage D audit, PR #5 review 6049617822 —
 * normative; do not weaken it):
 *
 * - RECEIVED: [capture] returned [CaptureReceipt.QUEUED]. The
 *   snapshot is in the in-memory executor queue. It is NOT durable:
 *   a process death here loses it unless it is still posted in the
 *   shade (see rescan below). The listener callback returning is
 *   NOT an acceptance ack — Android's `NotificationListenerService`
 *   has no ack semantics; the callback returning only means the
 *   snapshot was received into the queue (or overflow-dropped with
 *   a durable record).
 * - ACCEPTED (durable): [EventRepository.stageCapture] returned
 *   without throwing — the event row + staging row are committed in
 *   one Room transaction. Only from this point does the capture
 *   survive process death; [sweepOrphanedStaging] re-promotes
 *   orphaned staging rows after a restart. The accepted count is
 *   [CaptureCounters.staged]; [CaptureCounters.received] counts
 *   queue receipts.
 * - ENQUEUED: the staged capture reached exactly one target store
 *   (legacy `saveQueueSync` + readback, or Room delivery insert +
 *   staging delete in one transaction).
 *
 * The listener callback never touches a store and never blocks
 * on I/O: it builds a [NotificationSnapshot] and hands it to
 * [capture], which makes one bounded admission decision (a short
 * lock, no I/O under it) and returns immediately. On the single
 * capture worker thread, in order:
 *
 * 1. Route/keyword filtering (v2.3 rules, [SourcePolicy]).
 * 2. Amount parsing ([PaymentParser] via [ParserRegistry]).
 * 3. PrivacyGate classification in memory; the post-gate snapshot
 *    is staged ([EventRepository.stageCapture] — event + staging
 *    rows in one Room transaction). No raw secret ever reaches
 *    disk: the staging writer re-runs the class-(a) span scan
 *    before writing (staging gate, §8.3 R11).
 * 4. The mode is read and the snapshot is enqueued to exactly one
 *    store: the legacy queue while `LEGACY_ONLY` (synchronous
 *    `saveQueueSync` + readback, then the staging row is deleted),
 *    the Room outbox once `DRAINING` is committed (outbox insert +
 *    staging delete in one Room transaction).
 *
 * A failed enqueue never drops the capture: the staging row stays
 * and [sweepOrphanedStaging] re-promotes it through the same
 * mode gate (idempotent by `dedupe_key` / `legacy_qkey`).
 *
 * Durable ingress / recovery (no synchronous staging on the
 * callback thread — that would block the Binder callback on Room
 * I/O + PrivacyGate): a RECEIVED-but-not-ACCEPTED snapshot is
 * recovered only by rescan (`PaylistListenerService.rescanActive`,
 * on listener connect + manual), which re-reads notifications
 * still posted in the shade and dedupes by `dedupe_key`.
 * Limitations, stated honestly: rescan recovers only
 * notifications still active in the shade and < 24 h old;
 * dismissed/expired notifications are unrecoverable; the
 * pre-acceptance window itself is unmeasurable by construction —
 * no durable record exists for a snapshot that never reached the
 * executor and was never overflow-evicted. Overflow evictions,
 * by contrast, ARE durably recorded: every snapshot the bounded
 * queue drops gets one metadata-only `capture_log` row
 * ([CaptureOutcome.OVERFLOW_DROPPED] — package and post time
 * ONLY: no title text at all, because the drop happens before
 * route/consent evaluation; no amount, no event link, no
 * content hash), visible to the operator in History, so a burst
 * leaves durable per-snapshot evidence instead of only a counter.
 *
 * No zero-loss claim is made for this design: the RECEIVED →
 * ACCEPTED window above is a real loss window, narrowed to
 * "until the staging write completes" and recoverable only via
 * rescan while the notification is still posted.
 *
 * Admission (PR #5 follow-up — replaces the rejection-handler
 * design the audit rejected): there is NO executor rejection
 * handler, NO path that calls `execute()` from inside a
 * rejection callback (the old handler could re-enter itself
 * recursively under concurrent producers, and a normal return
 * from it was not proof of admission), NO task ever runs inline
 * on a caller thread, and NO database write happens on the
 * listener thread (the old overflow path ran a `runBlocking`
 * insert there). Admission is one synchronous, bounded,
 * non-recursive decision under a lock in `admit()`: when the
 * call returns, the task is either inside the bounded queue (or
 * the bounded control backlog) — that presence IS the proof of
 * admission — or it has been dropped with a counter and an
 * overflow record. All capture and sweep work runs ONLY on the
 * single capture worker thread: a sweep/control task that finds
 * the queue full of non-notifications waits in the control
 * backlog, which the worker drains with priority, so a sweep
 * can never run concurrently with a capture. Overflow
 * `capture_log` rows are written by a dedicated writer thread
 * from a bounded queue; the submitting thread only increments a
 * counter, logs a line, and offers the record (non-blocking).
 * A failed overflow insert is retried by the writer (bounded
 * attempts) instead of degrading immediately to a volatile
 * counter; only a database that keeps failing leaves the
 * counters + local log as the evidence (counted in
 * [CaptureCounters.overflowRecordDropped]).
 *
 * Lock order (design §8.3 R12–R14): this path acquires
 * `Sender.LOCK` only (inside the legacy enqueue), never the
 * Cutover lock; Room transactions are leaves. No lock is held
 * across a network call here.
 */
class CaptureCoordinator(
    private val repository: EventRepository,
    private val modeReader: suspend () -> CutoverMode,
    private val legacyEnqueue: LegacyEnqueue,
    private val routesProvider: () -> List<Route>,
    private val deviceIdProvider: () -> String,
    private val logger: CaptureLogSink,
    private val parser: ParserRegistry = ParserRegistry(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Called after a Room enqueue (production: request a sync run). */
    private val onRoomEnqueued: () -> Unit = {},
    /** Stage D D2: the PrivacyGate (C4 four-class rule + staging re-scan gate). */
    private val privacyGate: PrivacyGate = PrivacyGate(),
    /** Stage D D2: the §7.3A raw-snapshot store (null = B disabled, A′ only). */
    private val rawSnapshotStore: RawSnapshotStore? = null,
    /** Stage D D2: per-route consent records (null = no consent evidence). */
    private val consentStore: ConsentStore? = null,
    /**
     * Stage D D4: the durable capture log (LH-04). Null disables
     * capture-log writes (tests that predate D4); production always
     * wires the real DAO. A failed insert is counted + logged, never
     * silent (LH-04(d)) — the capture itself continues.
     */
    private val captureLogDao: CaptureLogDao? = null,
    /**
     * Executor work-queue capacity (tests use a small value to
     * saturate the queue deterministically; production uses
     * [EXECUTOR_CAPACITY]).
     */
    private val executorCapacity: Int = EXECUTOR_CAPACITY,
    /**
     * Bounded wait for the active capture task during [close]
     * (tests use a small value; production uses
     * [CLOSE_ACTIVE_TIMEOUT_MS]).
     */
    private val closeActiveTimeoutMs: Long = CLOSE_ACTIVE_TIMEOUT_MS,
    /**
     * PR #5 re-review 28 Nov: the persistent capture-health
     * store (incidents + successor fence that survive restart).
     * Null disables persistence (bare coordinators in older
     * tests); production always wires it.
     */
    private val healthStore: CaptureHealthStore? = null,
) {
    companion object {
        /** Executor work-queue capacity (design §8.3 (6′), proposed value). */
        const val EXECUTOR_CAPACITY: Int = 500

        /** Bounded backlog for sweep/control tasks that cannot evict a notification. */
        const val CONTROL_BACKLOG_CAPACITY: Int = 8

        /** Bounded queue of pending durable overflow records (writer thread). */
        const val OVERFLOW_QUEUE_CAPACITY: Int = 500

        /** Durable overflow-write attempts per record before the writer gives up. */
        const val MAX_OVERFLOW_WRITE_ATTEMPTS: Int = 3

        /** Bounded wait for the overflow writer to drain during [close]. */
        const val OVERFLOW_FLUSH_TIMEOUT_MS: Long = 5_000

        /** Grace for the active capture task to finish before [close] interrupts it. */
        const val CLOSE_ACTIVE_GRACE_MS: Long = 250

        /** Bounded wait for the active capture task during [close] (after the grace + interrupt). */
        const val CLOSE_ACTIVE_TIMEOUT_MS: Long = 5_000

        /** Bounded join of the overflow writer at the end of [close]. */
        const val WRITER_JOIN_TIMEOUT_MS: Long = 2_000

        /** Periodic staging-sweep interval (matches the 15-minute periodic work). */
        const val SWEEP_INTERVAL_MINUTES: Long = 15

        /** The legacy `dedupe_key` formula (v2.3, C3 — unchanged). */
        fun legacyDedupeKey(pkg: String, postTimeMs: Long, amount: Long, combined: String): String =
            "$pkg|$postTimeMs|$amount|${combined.take(40).hashCode()}"

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }

    private val received = AtomicLong()
    private val staged = AtomicLong()
    private val enqueued = AtomicLong()
    private val parked = AtomicLong()
    private val droppedOverflow = AtomicLong()
    private val droppedUnclassified = AtomicLong()
    private val identityCollision = AtomicLong()
    private val duplicates = AtomicLong()
    private val roomCapturesDuringDraining = AtomicLong()
    private val unlistedPackage = AtomicLong()
    private val noKeyword = AtomicLong()
    private val noAmount = AtomicLong()
    private val sweptOrphaned = AtomicLong()
    private val stagingMismatch = AtomicLong()
    private val pureAuth = AtomicLong()
    private val rawSnapshotsEvicted = AtomicLong()
    private val captureLogFailures = AtomicLong()
    private val noConsent = AtomicLong()
    private val controlInline = AtomicLong()
    private val controlDropped = AtomicLong()
    private val overflowRecordDropped = AtomicLong()
    private val unresolvedAtClose = AtomicLong()
    private val preStageInterruptedAtClose = AtomicLong()
    private val overflowWriterFailures = AtomicLong()

    /** This coordinator instance's identity in the health document's fence. */
    private val instanceId: String = UUID.randomUUID().toString()

    /**
     * Serialises every health-document read-modify-write. Declared
     * BEFORE the worker/writer thread properties below on purpose:
     * those threads start during construction and their fence
     * checks touch this lock — a lock declared after them can
     * still be uninitialised when a fast thread reaches it.
     */
    private val healthLock = ReentrantLock()

    private fun daemonFactory(name: String): ThreadFactory = ThreadFactory { r ->
        Thread(r, name).apply { isDaemon = true }
    }

    /**
     * What [capture] hands back to the caller: the handoff outcome,
     * NOT an acceptance ack. QUEUED means RECEIVED (in the queue);
     * the capture becomes ACCEPTED only when the staging write
     * completes on the capture worker thread.
     */
    enum class CaptureReceipt {
        /** The snapshot is queued for the capture executor (received, not yet durable). */
        QUEUED,

        /** The snapshot was dropped by queue saturation (a durable overflow record was attempted). */
        DROPPED_OVERFLOW,
    }

    /** Kinds of work for the capture worker (admission policy differs per kind). */
    private enum class CaptureTaskKind {
        /** A notification snapshot (may be evicted under saturation). */
        NOTIFICATION,

        /** A staging-sweep pass (never silently dropped). */
        SWEEP,

        /**
         * Control work (reserved — no control task is submitted
         * yet; admission treats it like SWEEP: never silently
         * dropped).
         */
        CONTROL,
    }

    /**
     * One unit of work for the capture worker, tagged with its
     * kind (and its snapshot, for notifications) so admission can
     * tell a droppable snapshot from a sweep/control task that
     * must never be silently dropped.
     *
     * Admission side-band (written by `admit()` under the
     * admission lock, settled by the submitter AFTER the lock is
     * released, so no logging or overflow accounting ever happens
     * under the lock):
     * - [dropped]: this task was refused and must be
     *   overflow-recorded by the submitter.
     * - [evictedSnapshot]: a queued snapshot evicted to admit
     *   this task; the submitter overflow-records it.
     * - [controlRejected]: this sweep/control task was refused
     *   because the control backlog was full; the submitter
     *   counts + logs it.
     * - [cancelled]: [close] stranded this task; it will never
     *   run, and [completionLatch] has been counted down.
     */
    private class CaptureTask(
        val kind: CaptureTaskKind,
        val snapshot: NotificationSnapshot?,
        private val block: () -> Unit,
    ) : Runnable {
        @Volatile
        var dropped: Boolean = false

        @Volatile
        var evictedSnapshot: NotificationSnapshot? = null

        @Volatile
        var controlRejected: Boolean = false

        @Volatile
        var cancelled: Boolean = false

        /** [sweepNow]'s completion latch (counted down by the block, or by [close]). */
        @Volatile
        var completionLatch: CountDownLatch? = null

        /**
         * How this task's run ended (PR #5 re-review 28 Nov):
         * written by the worker loop, read by [close] — an
         * interrupted task is PRE-STAGE CANCELLED / UNKNOWN,
         * never silently "resolved".
         */
        enum class Outcome { NOT_RUN, COMPLETED, FAILED, INTERRUPTED }

        @Volatile
        var outcome: Outcome = Outcome.NOT_RUN

        /** Set by [close] when it interrupts the worker while this task is active. */
        @Volatile
        var closeInterruptIssued: Boolean = false

        override fun run() = block()
    }

    // --- Admission & execution (bounded, non-recursive) ---

    private val admissionLock = ReentrantLock()
    private val workAvailable = admissionLock.newCondition()

    /** Bounded task queue (all kinds); guarded by [admissionLock]. */
    private val taskQueue = ArrayDeque<CaptureTask>()

    /**
     * Bounded backlog for sweep/control tasks admitted while the
     * main queue held no notification to evict; guarded by
     * [admissionLock], drained by the worker with priority — the
     * tasks still run ONLY on the worker thread.
     */
    private val controlBacklog = ArrayDeque<CaptureTask>()

    @Volatile
    private var closedFlag: Boolean = false

    /**
     * The task the capture worker is running right now (guarded
     * by [admissionLock]; null when the worker is idle). [close]
     * settles this task explicitly — bounded wait, interrupt,
     * then an unresolved accounting — instead of returning while
     * a pre-stage snapshot is still in flight (PR #5 re-review
     * 27 Nov).
     */
    private var activeTask: CaptureTask? = null

    /** Guards the one-shot [close] sequence. */
    private val closeStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The ONLY thread that ever runs capture/sweep tasks. */
    private val captureWorker = Thread({ workerLoop() }, "paylist-capture").apply {
        isDaemon = true
        start()
    }

    /**
     * The single admission decision for every task (see the class
     * KDoc): synchronous, bounded (at most one queue scan),
     * non-recursive — there is no rejection handler to re-enter.
     * Returns true iff the task is inside the bounded queue (or
     * the control backlog) when this call returns; that presence
     * is the proof of admission.
     *
     * Saturation policy (§8.3 (6′)): the OLDEST queued
     * NOTIFICATION is evicted to make room (never a sweep/control
     * task). A notification that finds nothing to evict is itself
     * dropped. A sweep/control task that finds nothing to evict
     * waits in the bounded control backlog; only a full backlog
     * refuses it. Nothing is recorded or logged under the lock —
     * the task's side-band fields tell the submitter what to
     * settle after the lock is released ([settleAdmission]).
     *
     * A closed coordinator admits nothing and marks nothing: the
     * caller owns the closed-path handling.
     */
    private fun admit(task: CaptureTask): Boolean {
        admissionLock.withLock {
            if (closedFlag) return false
            if (taskQueue.size < executorCapacity) {
                taskQueue.addLast(task)
                workAvailable.signal()
                return true
            }
            val evicted = evictOldestNotificationLocked()
            if (evicted != null) {
                task.evictedSnapshot = evicted
                taskQueue.addLast(task)
                workAvailable.signal()
                return true
            }
            if (task.kind == CaptureTaskKind.NOTIFICATION) {
                task.dropped = true
                return false
            }
            if (controlBacklog.size < CONTROL_BACKLOG_CAPACITY) {
                controlBacklog.addLast(task)
                workAvailable.signal()
                return true
            }
            task.controlRejected = true
            return false
        }
    }

    /**
     * Settles a task's admission side-band AFTER [admit] returned
     * (never under the admission lock): overflow-records an
     * evicted snapshot, overflow-records a dropped notification,
     * counts + logs a refused sweep/control task.
     */
    private fun settleAdmission(task: CaptureTask, admitted: Boolean) {
        task.evictedSnapshot?.let { recordOverflow(it) }
        if (!admitted && task.dropped) recordOverflow(task.snapshot)
        if (!admitted && task.controlRejected) {
            controlDropped.incrementAndGet()
            logger.logLocal(
                "tugas ${task.kind} ditolak: antrean penuh dan backlog kontrol penuh " +
                    "(dihitung; tidak dijalankan di thread pemanggil)",
            )
        }
    }

    /**
     * Removes the oldest queued NOTIFICATION task (FIFO scan of
     * [taskQueue], caller holds [admissionLock]) and returns its
     * snapshot. Sweep/control tasks are never evicted. Returns
     * null when the queue holds no notification.
     */
    private fun evictOldestNotificationLocked(): NotificationSnapshot? {
        val iterator = taskQueue.iterator()
        while (iterator.hasNext()) {
            val queued = iterator.next()
            if (queued.kind != CaptureTaskKind.NOTIFICATION) continue
            iterator.remove()
            return queued.snapshot
        }
        return null
    }

    /**
     * The capture worker loop: takes the next task — control
     * backlog first, then the main queue — and runs it. This is
     * the only place tasks run, so a sweep can never execute
     * concurrently with a capture, and no task ever executes on
     * a submitting thread.
     */
    private fun workerLoop() {
        // Successor fence (PR #5 re-review 28 Nov): if a
        // predecessor coordinator recorded a fence in the health
        // document (its threads were still alive when its bounded
        // close gave up), this worker must not process anything
        // until the predecessor's threads have actually exited
        // (their exit clears their half of the fence).
        awaitSuccessorFence()
        while (true) {
            val task: CaptureTask = admissionLock.withLock {
                while (!closedFlag && controlBacklog.isEmpty() && taskQueue.isEmpty()) {
                    try {
                        workAvailable.await()
                    } catch (_: InterruptedException) {
                        // close() interrupts: fall through and
                        // re-check the state under the lock.
                    }
                }
                when {
                    controlBacklog.isNotEmpty() -> controlBacklog.removeFirst()
                    taskQueue.isNotEmpty() -> taskQueue.removeFirst()
                    else -> null
                }
            } ?: break
            admissionLock.withLock { activeTask = task }
            try {
                if (!task.cancelled) {
                    try {
                        task.run()
                        task.outcome = CaptureTask.Outcome.COMPLETED
                    } catch (e: InterruptedException) {
                        // Interrupted (close() is the only
                        // interrupter): the task did NOT complete
                        // — its staging outcome is unconfirmed
                        // (PR #5 re-review 28 Nov). Recorded, not
                        // folded into a generic failure.
                        task.outcome = CaptureTask.Outcome.INTERRUPTED
                        logSafely(
                            "tugas capture terinterupsi (${task.kind}): " +
                                "hasil staging tidak terkonfirmasi",
                        )
                    } catch (e: Exception) {
                        task.outcome = CaptureTask.Outcome.FAILED
                        logSafely(
                            "tugas capture gagal (${task.kind}): ${e.message?.take(120)}",
                        )
                    }
                }
            } finally {
                admissionLock.withLock {
                    if (activeTask === task) activeTask = null
                }
            }
        }
        // This worker is exiting (close()): if this instance set
        // the successor fence, its worker half is now stopped.
        onWorkerThreadExit()
    }

    // --- Durable overflow accounting (writer thread) ---

    /**
     * One pending durable overflow record, with its write-attempt
     * count. The `capture_id` is minted ONCE, here at creation,
     * and reused by every retry (PR #5 re-review 27 Nov): the DAO
     * insert is IGNORE-on-conflict, so a retry after an uncertain
     * commit (the row landed but the call threw) hits the same
     * primary key and cannot create a duplicate row.
     */
    private class OverflowRecord(val snapshot: NotificationSnapshot) {
        val captureId: String = UUID.randomUUID().toString()
        var attempts: Int = 0

        /** Set by the writer when the durable insert actually landed. */
        @Volatile
        var persisted: Boolean = false
    }

    private val overflowQueue = LinkedBlockingQueue<OverflowRecord>(OVERFLOW_QUEUE_CAPACITY)

    /**
     * Overflow records accepted by [recordOverflow] but not yet
     * terminally settled (persisted, or given up after the retry
     * budget). Incremented BEFORE the record is offered and
     * decremented exactly once when the writer settles it, all
     * under the writer's control — this replaces the old
     * take-to-`overflowActive` check, whose gap between `take()`
     * and the volatile assignment let [flushOverflow] observe a
     * premature "empty" while a record was in flight (PR #5
     * re-review 27 Nov). [flushOverflow] and
     * [CaptureCounters.overflowPending] read only this counter.
     */
    private val overflowOutstanding = AtomicLong()

    /** Set by [close] after the final flush: the writer exits once its queue is empty. */
    @Volatile
    private var overflowWriterShutdown: Boolean = false

    /**
     * Guards the writer-termination handshake: [recordOverflow]
     * checks [overflowWriterTerminated] and offers its record
     * under this lock, and the writer sets
     * [overflowWriterTerminated] under the same lock only after
     * observing an empty queue — so a record can never be offered
     * into a queue whose writer has already decided to exit
     * (which would strand its outstanding count forever).
     */
    private val overflowStateLock = ReentrantLock()

    /** Guarded by [overflowStateLock]: the writer thread has exited for good. */
    private var overflowWriterTerminated: Boolean = false

    /**
     * The overflow writer: a dedicated daemon thread that owns
     * every durable overflow write AND the loud overflow log
     * lines, so neither happens on a caller thread. It is stopped
     * by [close] — bounded join after the final flush (PR #5
     * re-review 27 Nov: a writer that loops forever per
     * coordinator retains the DAO and makes the coordinator's
     * lifecycle unbounded). A coordinator that is never closed
     * (the production singleton) keeps its writer for its own
     * lifetime, which is the coordinator's lifetime by design.
     */
    private val overflowWriter = Thread({ overflowLoop() }, "paylist-overflow-writer").apply {
        isDaemon = true
        start()
    }

    /**
     * Overflow accounting for one dropped snapshot: the in-memory
     * counter and the outstanding accounting synchronously (cheap,
     * no I/O, safe on any caller thread), and EVERYTHING else —
     * the loud local log line and the durable `capture_log` row —
     * on the overflow-writer thread. This method never calls the
     * logger and never touches a store: the production log sink
     * (Store) serialises and persists its whole log on the
     * calling thread, which must never happen on the listener
     * (Binder) callback (PR #5 re-review 27 Nov).
     *
     * The row is metadata-only ([CaptureOutcome.OVERFLOW_DROPPED]
     * — package + post time; no title text: the drop precedes
     * route/consent evaluation, so no text of this snapshot may
     * be retained; no amount — never parsed; no event link; no
     * content hash — a hash of un-gated raw text would be a hash
     * of possibly-secret content). It is the operator-visible
     * evidence (History filter) and the rescan handle while the
     * notification is still posted.
     *
     * A null DAO (a bare coordinator in tests) leaves only the
     * counter. A full writer queue, a terminated writer
     * (post-close), or a database that keeps failing leaves the
     * counter + [CaptureCounters.overflowRecordDropped] — never
     * silent either way.
     */
    // --- Persistent capture health (PR #5 re-review 28 Nov) ---
    //
    // The volatile counters above die with the process. The
    // reviewer required the degraded state to be an incident /
    // recovery record that PERSISTS, is operator-visible, and
    // survives restart — so every terminal capture failure also
    // lands in the health document behind [healthStore]:
    // incidents aggregate by (category, status), distinguish
    // OPEN (still degrading) from RECOVERED (the failing
    // component demonstrably worked again) from UNRECOVERABLE
    // (an outcome that can never be retroactively confirmed),
    // and the document carries the successor fence. All access
    // is best-effort: a failing health store never breaks
    // capture, and no notification text is ever written into it
    // (metadata only: package + post time + component).
    // (healthLock itself is declared next to instanceId, before
    // the thread properties — see its comment.)

    private fun loadHealthDoc(): JSONObject {
        val raw = runCatching { healthStore?.readCaptureHealth() }.getOrNull()
        if (!raw.isNullOrBlank()) {
            runCatching { JSONObject(raw) }.getOrNull()?.let { return it }
        }
        return JSONObject().apply {
            put("version", 1)
            put(
                "fence",
                JSONObject().apply {
                    put("active", false)
                    put("owner", "")
                    put("since_ms", 0L)
                    put("worker_alive", false)
                    put("writer_alive", false)
                    put("reason", "")
                },
            )
            put("incidents", JSONArray())
        }
    }

    private fun mutateHealth(block: (JSONObject) -> Unit) {
        if (healthStore == null) return
        healthLock.withLock {
            runCatching {
                val doc = loadHealthDoc()
                block(doc)
                doc.put("updated_ms", clock())
                healthStore?.writeCaptureHealth(doc.toString())
            }
        }
    }

    /**
     * Records (or aggregates) one persistent incident. [status]
     * is the status the incident is recorded under: OPEN for
     * component failures that can still recover, UNRECOVERABLE
     * for outcomes that can never be confirmed after the fact.
     */
    private fun recordIncident(category: String, status: String, detail: String) {
        mutateHealth { doc ->
            val incidents = doc.optJSONArray("incidents") ?: JSONArray()
                .also { doc.put("incidents", it) }
            var found: JSONObject? = null
            for (i in 0 until incidents.length()) {
                val item = incidents.optJSONObject(i) ?: continue
                if (item.optString("category") == category &&
                    item.optString("status") == status
                ) {
                    found = item
                    break
                }
            }
            val now = clock()
            if (found != null) {
                found.put("count", found.optLong("count", 0) + 1)
                found.put("last_ms", now)
                found.put("detail", detail)
            } else {
                incidents.put(
                    JSONObject().apply {
                        put("category", category)
                        put("status", status)
                        put("first_ms", now)
                        put("last_ms", now)
                        put("count", 1L)
                        put("detail", detail)
                    },
                )
            }
            // Bound the document: keep at most 50 incidents,
            // dropping the oldest RECOVERED ones first.
            while (incidents.length() > 50) {
                var dropIdx = 0
                for (i in 0 until incidents.length()) {
                    if (incidents.optJSONObject(i)
                            ?.optString("status") == CaptureIncidentStatus.RECOVERED
                    ) {
                        dropIdx = i
                        break
                    }
                }
                incidents.remove(dropIdx)
            }
        }
    }

    /** Closes OPEN incidents of [category] as RECOVERED (the component works again). */
    private fun recoverIncidents(category: String) {
        mutateHealth { doc ->
            val incidents = doc.optJSONArray("incidents") ?: return@mutateHealth
            for (i in 0 until incidents.length()) {
                val item = incidents.optJSONObject(i) ?: continue
                if (item.optString("category") == category &&
                    item.optString("status") == CaptureIncidentStatus.OPEN
                ) {
                    item.put("status", CaptureIncidentStatus.RECOVERED)
                    item.put("last_ms", clock())
                }
            }
        }
    }

    /** The persistent incidents, as recorded (empty without a health store). */
    private fun persistentIncidents(): List<CaptureIncident> {
        if (healthStore == null) return emptyList()
        return healthLock.withLock {
            runCatching {
                val doc = loadHealthDoc()
                val incidents = doc.optJSONArray("incidents") ?: JSONArray()
                (0 until incidents.length()).mapNotNull { i ->
                    val item = incidents.optJSONObject(i) ?: return@mapNotNull null
                    CaptureIncident(
                        category = item.optString("category"),
                        status = item.optString("status"),
                        firstAtMs = item.optLong("first_ms"),
                        lastAtMs = item.optLong("last_ms"),
                        count = item.optLong("count"),
                        detail = item.optString("detail"),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    /** True while ANY coordinator's fence is recorded active. */
    private fun fenceRecordedActive(): Boolean {
        if (healthStore == null) return false
        return healthLock.withLock {
            runCatching {
                loadHealthDoc().optJSONObject("fence")?.optBoolean("active") == true
            }.getOrDefault(false)
        }
    }

    /** True while a PREDECESSOR's fence is active (this instance must wait). */
    private fun predecessorFenceActive(): Boolean {
        if (healthStore == null) return false
        return healthLock.withLock {
            runCatching {
                val fence = loadHealthDoc().optJSONObject("fence")
                fence != null && fence.optBoolean("active") &&
                    fence.optString("owner") != instanceId
            }.getOrDefault(false)
        }
    }

    /**
     * Blocks a replacement coordinator's threads until the
     * predecessor's fence clears (the predecessor's thread exits
     * clear their halves — see [onWorkerThreadExit] /
     * [onWriterThreadExit]). Bounded by [close]: a close during
     * the wait sets the closed flag and ends the wait, after
     * which the loops observe the closed state and exit.
     */
    private fun awaitSuccessorFence() {
        if (healthStore == null) return
        while (!closedFlag && predecessorFenceActive()) {
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** Records this instance's fence (called by [close] when a thread outlives it). */
    private fun setSuccessorFence(workerAlive: Boolean, writerAlive: Boolean, reason: String) {
        mutateHealth { doc ->
            doc.put(
                "fence",
                JSONObject().apply {
                    put("active", true)
                    put("owner", instanceId)
                    put("since_ms", clock())
                    put("worker_alive", workerAlive)
                    put("writer_alive", writerAlive)
                    put("reason", reason)
                },
            )
        }
    }

    /** The capture worker exited: clears its half of this instance's fence. */
    private fun onWorkerThreadExit() {
        mutateHealth { doc ->
            val fence = doc.optJSONObject("fence") ?: return@mutateHealth
            if (fence.optString("owner") != instanceId || !fence.optBoolean("active")) {
                return@mutateHealth
            }
            fence.put("worker_alive", false)
            if (!fence.optBoolean("writer_alive")) fence.put("active", false)
        }
    }

    /** The overflow writer exited: clears its half of this instance's fence. */
    private fun onWriterThreadExit() {
        mutateHealth { doc ->
            val fence = doc.optJSONObject("fence") ?: return@mutateHealth
            if (fence.optString("owner") != instanceId || !fence.optBoolean("active")) {
                return@mutateHealth
            }
            fence.put("writer_alive", false)
            if (!fence.optBoolean("worker_alive")) fence.put("active", false)
        }
    }

    /**
     * Best-effort local log (PR #5 re-review 28 Nov): the
     * production log sink persists on the calling thread and CAN
     * throw; a throwing logger must never kill a pipeline thread
     * or strand accounting, so every pipeline-critical log call
     * goes through here.
     */
    private fun logSafely(text: String) {
        runCatching { logger.logLocal(text) }
    }

    /** Best-effort per-package event log (see [logSafely]). */
    private fun logEventSafely(pkg: String, text: String) {
        runCatching { logger.logEvent(pkg, text) }
    }

    private fun recordOverflow(snapshot: NotificationSnapshot?) {
        droppedOverflow.incrementAndGet()
        recordIncident(
            CaptureIncidentCategory.OVERFLOW_SNAPSHOT_DROPPED,
            CaptureIncidentStatus.OPEN,
            "${snapshot?.packageName} @${snapshot?.postTimeMs}",
        )
        if (snapshot == null) {
            // Unknown snapshot: the counter is the only evidence
            // (there is nothing to write a row about).
            return
        }
        if (captureLogDao == null) return
        // This method runs on caller threads — including the
        // NotificationListenerService Binder thread — so it must
        // not touch the production log sink or any store
        // synchronously (PR #5 re-review 27 Nov: Store.logLocal
        // serialises + persists the whole log on the calling
        // thread, and its exceptions must never reach the
        // callback). It only counts, accounts, and offers the
        // record; the writer thread emits the loud log line and
        // performs the insert.
        overflowStateLock.withLock {
            if (overflowWriterTerminated) {
                // The writer is gone (post-close): the record
                // cannot be persisted any more. Counted
                // explicitly as a lost record, never silently
                // queued to a dead writer.
                overflowRecordDropped.incrementAndGet()
                recordIncident(
                    CaptureIncidentCategory.OVERFLOW_RECORD_LOST,
                    CaptureIncidentStatus.OPEN,
                    "${snapshot.packageName} @${snapshot.postTimeMs} (penulis overflow sudah berhenti)",
                )
                return
            }
            overflowOutstanding.incrementAndGet()
            if (!overflowQueue.offer(OverflowRecord(snapshot))) {
                overflowOutstanding.decrementAndGet()
                overflowRecordDropped.incrementAndGet()
                recordIncident(
                    CaptureIncidentCategory.OVERFLOW_RECORD_LOST,
                    CaptureIncidentStatus.OPEN,
                    "${snapshot.packageName} @${snapshot.postTimeMs} (antrean penulis overflow penuh)",
                )
            }
        }
    }

    /**
     * Writes overflow records on the writer thread. A failed
     * insert is counted ([CaptureCounters.captureLogFailures]) +
     * logged, and the record is RETRIED at the tail (bounded
     * attempts) — a transient database failure must not degrade
     * the evidence to a volatile counter. Only after the final
     * attempt is the record given up, counted in
     * [CaptureCounters.overflowRecordDropped].
     */
    private fun overflowLoop() {
        // The writer's fence half clears however this thread dies.
        try {
            overflowLoopBody()
        } finally {
            onWriterThreadExit()
        }
    }

    private fun overflowLoopBody() {
        // Successor fence (PR #5 re-review 28 Nov): a replacement
        // coordinator's writer waits for the predecessor's fence,
        // exactly like its capture worker.
        awaitSuccessorFence()
        while (true) {
            val record = try {
                overflowQueue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // An interrupt is close()'s stop signal: do NOT
                // exit here — fall into the same termination
                // handshake as an empty poll below, so the
                // terminated flag is always set under the lock
                // before this thread dies. (Exiting directly from
                // this catch once left the flag unset, and a
                // post-close recordOverflow then queued a record
                // to a dead writer.)
                null
            }
            if (record == null) {
                if (overflowWriterShutdown) {
                    // Termination handshake (see
                    // overflowStateLock): exit only when the
                    // queue is provably empty under the lock.
                    val exit = overflowStateLock.withLock {
                        if (overflowQueue.isEmpty()) {
                            overflowWriterTerminated = true
                            true
                        } else {
                            false
                        }
                    }
                    if (exit) return
                }
                continue
            }
            // Outer terminal cleanup (PR #5 re-review 28 Nov):
            // writeOverflowRecord guards its insert, but its log
            // calls sit OUTSIDE that guard — a throwing log sink
            // (the production Store persists on the calling
            // thread and can throw) must never kill this loop,
            // strand the record's outstanding count, or leave
            // the terminated flag unset. Any escape settles the
            // record as lost, records the writer failure as a
            // health incident, and the loop CONTINUES.
            val settled = try {
                writeOverflowRecord(record)
            } catch (t: Throwable) {
                overflowWriterFailures.incrementAndGet()
                overflowRecordDropped.incrementAndGet()
                recordIncident(
                    CaptureIncidentCategory.WRITER_TERMINAL_FAILURE,
                    CaptureIncidentStatus.OPEN,
                    "${record.snapshot.packageName} @${record.snapshot.postTimeMs} " +
                        "(${t.javaClass.simpleName})",
                )
                logSafely(
                    "penulis overflow gagal terminal untuk satu catatan " +
                        "(${record.snapshot.packageName}): ${t.message?.take(120)} — " +
                        "catatan dihitung hilang, loop penulis tetap hidup",
                )
                true
            }
            if (settled) {
                overflowOutstanding.decrementAndGet()
                if (record.persisted) {
                    // A landed insert proves the database works
                    // again: permanent-failure incidents close as
                    // RECOVERED (and, when the record needed
                    // retries, so do lost-record incidents — the
                    // component that lost them is healthy again).
                    recoverIncidents(CaptureIncidentCategory.DB_PERMANENT_FAILURE)
                    if (record.attempts > 0) {
                        recoverIncidents(CaptureIncidentCategory.OVERFLOW_RECORD_LOST)
                    }
                }
            }
        }
    }

    /**
     * One writer pass over [record]: emits the loud overflow log
     * line (on this writer thread, never on a submitting thread)
     * and attempts the durable insert with the record's stable
     * `capture_id`. Returns true when the record is terminally
     * settled — persisted, or given up (retry budget exhausted,
     * retry queue full, or the writer is shutting down) — and
     * false when it was re-queued for another attempt (ownership
     * of the outstanding count moves with it).
     */
    private fun writeOverflowRecord(record: OverflowRecord): Boolean {
        val snapshot = record.snapshot
        // Best-effort logging, separated from the DB attempt
        // (PR #5 re-review 28 Nov): this loud line used to be a
        // bare logger call BEFORE the try, and the failure lines
        // inside the catch — a throwing log sink (the production
        // Store persists its whole log on the calling thread)
        // could kill the writer loop and strand the outstanding
        // accounting. Logging now goes through logSafely and can
        // never fail the record; the loop's outer catch is the
        // second line of defence.
        logSafely(
            "capture overflow: ${snapshot.packageName} @${snapshot.postTimeMs} " +
                "terbuang sebelum staging (antrean penuh); " +
                "pindai ulang selagi notifikasi masih aktif",
        )
        val dao = captureLogDao ?: return true
        return try {
            runBlocking {
                dao.insert(
                    CaptureLogEntity(
                        captureId = record.captureId,
                        sourcePackage = snapshot.packageName,
                        postTimeMs = snapshot.postTimeMs,
                        capturedAtMs = clock(),
                        outcome = CaptureOutcome.OVERFLOW_DROPPED,
                        // Consent policy (PR #5 re-review 27 Nov):
                        // an overflow drop happens BEFORE route /
                        // consent evaluation, so NO title text is
                        // stored at all — the row is metadata-only
                        // (package, post time, outcome). A
                        // pre-consent snippet, however well
                        // redacted, is still text the owner never
                        // consented to retain for this snapshot.
                        titleSnippet = null,
                        amountMinor = null,
                        eventId = null,
                        contentSha256 = null,
                    ),
                )
            }
            record.persisted = true
            true
        } catch (e: Exception) {
            captureLogFailures.incrementAndGet()
            logSafely(
                "catatan overflow gagal ditulis (${snapshot.packageName}): " +
                    e.message?.take(120),
            )
            record.attempts += 1
            if (!overflowWriterShutdown &&
                record.attempts < MAX_OVERFLOW_WRITE_ATTEMPTS
            ) {
                if (overflowQueue.offer(record)) {
                    false
                } else {
                    overflowRecordDropped.incrementAndGet()
                    recordIncident(
                        CaptureIncidentCategory.OVERFLOW_RECORD_LOST,
                        CaptureIncidentStatus.OPEN,
                        "${snapshot.packageName} @${snapshot.postTimeMs} (antrean retry penuh)",
                    )
                    logSafely(
                        "catatan overflow hilang saat retry (${snapshot.packageName})",
                    )
                    true
                }
            } else {
                overflowRecordDropped.incrementAndGet()
                // The retry budget is exhausted against a failing
                // database: that is a permanent-DB-failure
                // incident (its own category) on top of the lost
                // record — unless the give-up is only because the
                // writer is shutting down.
                if (!overflowWriterShutdown) {
                    recordIncident(
                        CaptureIncidentCategory.DB_PERMANENT_FAILURE,
                        CaptureIncidentStatus.OPEN,
                        "${snapshot.packageName} @${snapshot.postTimeMs} " +
                            "(insert overflow gagal ${record.attempts} percobaan)",
                    )
                }
                recordIncident(
                    CaptureIncidentCategory.OVERFLOW_RECORD_LOST,
                    CaptureIncidentStatus.OPEN,
                    "${snapshot.packageName} @${snapshot.postTimeMs} " +
                        "(retry habis: ${record.attempts} percobaan)",
                )
                logSafely(
                    "catatan overflow menyerah setelah ${record.attempts} percobaan " +
                        "(${snapshot.packageName}): bukti hanya counter + log lokal",
                )
                true
            }
        }
    }

    /**
     * Bounded wait for the overflow writer to drain (graceful-
     * shutdown evidence in [close]): returns when the writer
     * queue is empty and no record is in flight, or when the
     * timeout elapses — records still queued then stay with the
     * still-running writer, and a hard process exit can still
     * lose them (documented in [close]).
     */
    private fun flushOverflow(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (overflowOutstanding.get() == 0L) return
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** Test/diagnostic visibility into the bounded shutdown's outcome. */
    internal fun isCaptureWorkerAlive(): Boolean = captureWorker.isAlive

    /** Test/diagnostic visibility: the overflow writer thread's liveness. */
    internal fun isOverflowWriterAlive(): Boolean = overflowWriter.isAlive

    /** Timer for the periodic staging sweep (submits onto the capture worker). */
    private val sweepTimer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(daemonFactory("paylist-sweep-timer"))

    /** Current counter values (for diagnostics and tests). */
    fun counters(): CaptureCounters = CaptureCounters(
        received = received.get(),
        staged = staged.get(),
        enqueued = enqueued.get(),
        parked = parked.get(),
        droppedOverflow = droppedOverflow.get(),
        droppedUnclassified = droppedUnclassified.get(),
        identityCollision = identityCollision.get(),
        duplicates = duplicates.get(),
        roomCapturesDuringDraining = roomCapturesDuringDraining.get(),
        unlistedPackage = unlistedPackage.get(),
        noKeyword = noKeyword.get(),
        noAmount = noAmount.get(),
        sweptOrphaned = sweptOrphaned.get(),
        stagingMismatch = stagingMismatch.get(),
        pureAuth = pureAuth.get(),
        rawSnapshotsEvicted = rawSnapshotsEvicted.get(),
        captureLogFailures = captureLogFailures.get(),
        noConsent = noConsent.get(),
        controlInline = controlInline.get(),
        controlDropped = controlDropped.get(),
        overflowRecordDropped = overflowRecordDropped.get(),
        overflowPending = overflowOutstanding.get(),
        unresolvedAtClose = unresolvedAtClose.get(),
        preStageInterruptedAtClose = preStageInterruptedAtClose.get(),
        overflowWriterFailures = overflowWriterFailures.get(),
    )

    /**
     * The owner-visible degraded state (definition on
     * [CaptureDegradedState]): degraded when any snapshot was
     * saturation-dropped, any overflow record was lost, any
     * capture was left unresolved by a shutdown, or overflow
     * records are still waiting to persist. Accounting is not
     * recovery — this state says the pipeline owes the owner
     * attention (rescan / restart / DB check), not that the
     * dropped payments were recovered.
     */
    fun degradedState(): CaptureDegradedState {
        val c = counters()
        val reasons = mutableListOf<String>()
        if (c.droppedOverflow > 0) {
            reasons += "ada ${c.droppedOverflow} snapshot terbuang sebelum staging " +
                "(bukti: baris OVERFLOW_DROPPED di Riwayat; pulih hanya lewat pindai ulang " +
                "selagi notifikasi masih aktif)"
        }
        if (c.overflowRecordDropped > 0) {
            reasons += "${c.overflowRecordDropped} catatan overflow gagal ditulis permanen " +
                "(basis data gagal berulang; periksa kesehatan basis data/penyimpanan)"
        }
        if (c.unresolvedAtClose > 0) {
            reasons += "${c.unresolvedAtClose} capture tidak terselesaikan saat penutupan " +
                "(periksa hasil sweep awal + pindai ulang setelah listener jalan lagi)"
        }
        if (c.preStageInterruptedAtClose > 0) {
            reasons += "${c.preStageInterruptedAtClose} capture dibatalkan pra-stage / " +
                "hasilnya tidak diketahui saat penutupan (bukan resolved; periksa sweep " +
                "awal + pindai ulang)"
        }
        if (c.overflowPending > 0) {
            reasons += "${c.overflowPending} catatan overflow masih menunggu ditulis"
        }
        // Persistent layer (PR #5 re-review 28 Nov): incidents
        // recorded by EARLIER processes count too — a fresh
        // coordinator over the same health store reports the
        // pipeline degraded until the OPEN incidents recover.
        // UNRECOVERABLE incidents stay listed as history (they
        // are in persistentIncidents) but do not by themselves
        // hold the state degraded forever; RECOVERED ones are
        // history too.
        val incidents = persistentIncidents()
        val openIncidents = incidents.filter {
            it.status == CaptureIncidentStatus.OPEN
        }
        for (incident in openIncidents) {
            reasons += "insiden persisten ${incident.category} x${incident.count} " +
                "sejak ${incident.firstAtMs}: ${incident.detail} " +
                "(bertahan lintas restart; pulih otomatis bila komponen terkait " +
                "terbukti bekerja lagi, atau periksa manual)"
        }
        val fenced = fenceRecordedActive()
        if (fenced) {
            reasons += "pagar penerus aktif: koordinator pendahulu belum berhenti " +
                "sepenuhnya; pengganti ter-fence sampai thread pendahulu berhenti"
        }
        return CaptureDegradedState(
            degraded = reasons.isNotEmpty(),
            overflowDroppedSnapshots = c.droppedOverflow,
            overflowRecordsLost = c.overflowRecordDropped,
            unresolvedAtClose = c.unresolvedAtClose,
            overflowPending = c.overflowPending,
            captureLogFailures = c.captureLogFailures,
            reasons = reasons,
            persistentIncidents = incidents,
            successorFenced = fenced,
        )
    }

    /**
     * Hands a snapshot to the capture worker. Non-blocking: one
     * bounded admission decision (a short lock, no I/O under it),
     * never touches a store, never blocks the callback thread on
     * I/O (§8.3 (1′)).
     *
     * Returns [CaptureReceipt.QUEUED] (RECEIVED — in the queue,
     * not yet durable) or [CaptureReceipt.DROPPED_OVERFLOW] when
     * the snapshot was dropped by saturation (with a durable
     * overflow record attempted) or when the coordinator is
     * already closed. The return value is the handoff outcome,
     * not an acceptance ack: ACCEPTED means the staging write
     * completed.
     */
    fun capture(snapshot: NotificationSnapshot): CaptureReceipt {
        received.incrementAndGet()
        val task = CaptureTask(CaptureTaskKind.NOTIFICATION, snapshot) {
            runBlocking { processSnapshot(snapshot) }
        }
        val admitted = admit(task)
        settleAdmission(task, admitted)
        if (!admitted) {
            // Saturation drops were already overflow-recorded by
            // settleAdmission (task.dropped); the closed path is
            // recorded here. Either way: a counted drop with a
            // durable record attempt, never a throw to the caller
            // (the old design could throw on the Binder thread).
            if (!task.dropped) recordOverflow(snapshot)
            return CaptureReceipt.DROPPED_OVERFLOW
        }
        return CaptureReceipt.QUEUED
    }

    /**
     * Runs one sweep pass on the capture worker thread (for tests
     * and for the periodic timer). Blocks until the pass finishes.
     *
     * Under saturation the sweep is admitted by evicting the
     * oldest queued notification, or — when there is none — by
     * waiting in the bounded control backlog, which the worker
     * drains with priority. The sweep therefore keeps its
     * synchronisation property (this call returns only after the
     * sweep ran) WITHOUT ever running on the caller thread. It
     * throws [RejectedExecutionException] — instead of hanging or
     * running inline — when the coordinator is closed, when
     * [close] strands the admitted sweep, or when even the
     * control backlog is full (counted, never silent).
     */
    fun sweepNow() {
        if (closedFlag) throw RejectedExecutionException("capture executor shut down")
        val done = CountDownLatch(1)
        val failureBox = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val task = CaptureTask(CaptureTaskKind.SWEEP, null) {
            try {
                runBlocking { sweepOrphanedStaging() }
            } catch (t: Throwable) {
                // Propagated to the caller below (PR #5 re-review
                // 27 Nov): a sweep failure must never look like a
                // successful sweep. The worker loop's generic
                // catch does not see this — the task owns it.
                failureBox.set(t)
            } finally {
                done.countDown()
            }
        }
        task.completionLatch = done
        val admitted = admit(task)
        settleAdmission(task, admitted)
        if (!admitted) {
            throw RejectedExecutionException(
                "capture admission refused (control backlog full or shut down)",
            )
        }
        done.await()
        if (task.cancelled) throw RejectedExecutionException("capture executor shut down")
        failureBox.get()?.let { throw it }
    }

    /** Starts the periodic staging sweep (every [SWEEP_INTERVAL_MINUTES]). */
    fun startPeriodicSweep() {
        sweepTimer.scheduleAtFixedRate(
            {
                val task = CaptureTask(CaptureTaskKind.SWEEP, null) {
                    runBlocking { sweepOrphanedStaging() }
                }
                settleAdmission(task, admit(task))
            },
            SWEEP_INTERVAL_MINUTES, SWEEP_INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
    }

    /**
     * Orderly, bounded shutdown (PR #5 re-review 27 Nov —
     * replaces the old interrupt-and-return close):
     *
     * 1. Admission stops; queued tasks are stranded — queued
     *    notifications each get a durable overflow record,
     *    stranded sweeps release their [sweepNow] caller with a
     *    throw instead of a hang.
     * 2. The ACTIVE task is settled explicitly (see
     *    [settleActiveTaskAtClose]): grace, interrupt, bounded
     *    join; a task still running after the bound is counted
     *    as unresolved, never silently abandoned.
     * 3. The overflow writer is flushed (bounded), then
     *    TERMINATED — it no longer loops forever retaining the
     *    DAO. Captures attempted after close are refused with a
     *    counted, record-lost overflow outcome (the writer is
     *    gone), never a throw to the caller.
     *
     * Idempotent: a second call returns immediately. This covers
     * `close()` only — a SIGKILL gives no chance to run it, and
     * those snapshots are recoverable only via rescan while
     * still posted (see the class KDoc).
     */
    fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        sweepTimer.shutdownNow()
        val stranded: List<CaptureTask> = admissionLock.withLock {
            closedFlag = true
            val all = taskQueue.toList() + controlBacklog.toList()
            taskQueue.clear()
            controlBacklog.clear()
            workAvailable.signalAll()
            all
        }
        for (task in stranded) {
            if (task.kind == CaptureTaskKind.NOTIFICATION) {
                runCatching { recordOverflow(task.snapshot) }
            } else {
                task.cancelled = true
                task.completionLatch?.countDown()
            }
        }
        settleActiveTaskAtClose()
        flushOverflow(OVERFLOW_FLUSH_TIMEOUT_MS)
        // Terminate the overflow writer (PR #5 re-review 27 Nov):
        // after the final flush the queue is drained; the writer
        // exits instead of looping forever retaining the DAO.
        // Records offered after this point are counted as lost
        // (see recordOverflow), never queued to a dead writer.
        overflowWriterShutdown = true
        overflowWriter.interrupt()
        try {
            overflowWriter.join(WRITER_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // Successor fence (PR #5 re-review 28 Nov): if either
        // pipeline thread outlived this bounded close, record
        // the fence in the persistent health document. A
        // replacement coordinator's threads wait on that fence
        // (see awaitSuccessorFence) until these threads actually
        // exit — their exit clears their half of the fence — so
        // a successor can never run concurrently with a zombie
        // predecessor worker/writer.
        val workerAlive = captureWorker.isAlive
        val writerAlive = overflowWriter.isAlive
        if (workerAlive || writerAlive) {
            setSuccessorFence(
                workerAlive = workerAlive,
                writerAlive = writerAlive,
                reason = "close pendahulu melewati batas tunggu " +
                    "(worker hidup=$workerAlive, penulis hidup=$writerAlive)",
            )
            logSafely(
                "pagar penerus dicatat: worker/penulis pendahulu masih hidup " +
                    "sesudah close (worker=$workerAlive, penulis=$writerAlive); " +
                    "koordinator pengganti ter-fence sampai keduanya berhenti",
            )
        }
    }

    /**
     * Settles the capture worker's ACTIVE task at [close] (PR #5
     * re-review 27 Nov): a short grace for it to finish on its
     * own, then an interrupt (the cooperative cancellation signal
     * the capture path already honours), then a bounded join. A
     * task that finishes — before or after the interrupt — is
     * fully resolved: its staging transaction either committed
     * (the startup sweep re-promotes it) or rolled back (the
     * notification is rescan-recoverable while still posted).
     * Only a task still running after the bounded join is
     * UNRESOLVED: counted in [CaptureCounters.unresolvedAtClose]
     * and logged with metadata only (package + post time — no
     * text), so the outcome is explicit instead of an unexamined
     * "worker may still run after close".
     */
    private fun settleActiveTaskAtClose() {
        if (!captureWorker.isAlive) return
        var interruptedTask: CaptureTask? = null
        try {
            captureWorker.join(CLOSE_ACTIVE_GRACE_MS)
            if (captureWorker.isAlive) {
                // Mark the active task BEFORE interrupting: if it
                // then exits without completing, close() must
                // classify it as pre-stage cancelled / unknown —
                // never as resolved (PR #5 re-review 28 Nov).
                interruptedTask = admissionLock.withLock { activeTask }
                interruptedTask?.closeInterruptIssued = true
                captureWorker.interrupt()
                captureWorker.join(closeActiveTimeoutMs)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (captureWorker.isAlive) {
            val active = admissionLock.withLock { activeTask }
            if (active != null && active.kind == CaptureTaskKind.NOTIFICATION) {
                unresolvedAtClose.incrementAndGet()
                recordIncident(
                    CaptureIncidentCategory.UNRESOLVED_AT_CLOSE,
                    CaptureIncidentStatus.OPEN,
                    "${active.snapshot?.packageName} @${active.snapshot?.postTimeMs}",
                )
                logSafely(
                    "capture tidak terselesaikan saat penutupan: " +
                        "${active.snapshot?.packageName} @${active.snapshot?.postTimeMs} " +
                        "(tugas aktif melewati batas tunggu; hasil staging tidak pasti — " +
                        "pulihkan lewat sweep awal + pindai ulang)",
                )
            } else if (active != null) {
                logSafely(
                    "tugas ${active.kind} masih berjalan saat penutupan " +
                        "melewati batas tunggu (diinterupsi; tanpa snapshot aktif)",
                )
            }
            return
        }
        // The worker exited inside the bounded wait. A task that
        // was active, was interrupted by this close, and did NOT
        // complete is PRE-STAGE CANCELLED / UNKNOWN OUTCOME
        // (PR #5 re-review 28 Nov): its staging transaction
        // either committed or rolled back, but the coordinator
        // never confirmed which — labelling it "resolved" (the
        // old behaviour) was wrong. It is counted, logged, and
        // recorded as an UNRECOVERABLE persistent incident;
        // recovery is rescan + the startup sweep, which is also
        // what verifies the staging side.
        val interrupted = interruptedTask
        if (interrupted != null && interrupted.closeInterruptIssued &&
            interrupted.outcome != CaptureTask.Outcome.COMPLETED
        ) {
            if (interrupted.kind == CaptureTaskKind.NOTIFICATION) {
                preStageInterruptedAtClose.incrementAndGet()
                recordIncident(
                    CaptureIncidentCategory.PRE_STAGE_INTERRUPTED,
                    CaptureIncidentStatus.UNRECOVERABLE,
                    "${interrupted.snapshot?.packageName} " +
                        "@${interrupted.snapshot?.postTimeMs} (outcome=${interrupted.outcome})",
                )
            }
            logSafely(
                "capture dibatalkan pra-stage / hasil tidak diketahui saat penutupan: " +
                    "${interrupted.snapshot?.packageName} @${interrupted.snapshot?.postTimeMs} " +
                    "(tugas terinterupsi sebelum selesai, outcome=${interrupted.outcome}; " +
                    "BUKAN resolved — pulihkan lewat sweep awal + pindai ulang)",
            )
        }
    }

    // --- Capture path (capture worker thread) ---

    internal suspend fun processSnapshot(snapshot: NotificationSnapshot) {
        if (snapshot.isGroupSummary) return
        val pkg = snapshot.packageName
        val routes = SourcePolicy.routesForPackage(routesProvider(), pkg)
        if (routes.isEmpty()) {
            unlistedPackage.incrementAndGet()
            logger.logEvent(pkg, "paket-terlihat: $pkg (belum di daftar rute)")
            // LH-04(b): package + time + outcome only — no text, no amount.
            writeCaptureLog(
                snapshot, CaptureOutcome.UNLISTED_PACKAGE,
                titleSnippet = null, amountMinor = null,
                eventId = null, contentSha256 = null,
            )
            return
        }
        val matched = SourcePolicy.keywordMatchedRoutes(routes, snapshot.combined)
        if (matched.isEmpty()) {
            noKeyword.incrementAndGet()
            // PR #5 re-review 28 Nov (tightening the 27 Nov fix):
            // this path is PRE-consent — the gate has not run and
            // no route consented — so the policy is now
            // metadata-only, exactly like an overflow drop: NO
            // title text at all, not even a redacted snippet, in
            // either the persistent log line or the capture-log
            // row. A redacted title is still the owner's
            // notification text retained without consent.
            logEventSafely(pkg, "target-lain: $pkg (kata kunci rute tidak cocok)")
            writeCaptureLog(
                snapshot, CaptureOutcome.NO_KEYWORD,
                titleSnippet = null,
                amountMinor = null, eventId = null, contentSha256 = null,
            )
            return
        }
        // D5 consent gate (F15): no capture before the onboarding
        // opt-in completes. Only routes with a granted consent
        // record proceed; a notification matching no consenting
        // route is dropped before any parse, gate, or durable
        // write. A null consent store (bare coordinator in tests)
        // cannot verify consent, so the gate is skipped there;
        // production always wires the store.
        val consented = consentStore?.let { store ->
            matched.filter { route ->
                runCatching { store.get(route.id)?.granted == true }.getOrDefault(false)
            }
        } ?: matched
        if (consented.isEmpty()) {
            noConsent.incrementAndGet()
            logger.logEvent(pkg, "tanpa-persetujuan: $pkg (tidak ada rute yang disetujui)")
            return
        }
        val input = ParserInput(pkg, snapshot.title, snapshot.text, snapshot.bigText, snapshot.postTimeMs)
        val parsed = parser.parse(input)
        val now = clock()
        if (parsed == null) {
            // No parseable amount: PrivacyGate distinguishes a
            // pure-auth notification (confirmed auth span, no
            // financial record — dropped, §7.1(a)) from a plain
            // no-amount notification.
            val gate = privacyGate.gate(snapshot, null)
            if (gate.decision == PrivacyGate.Decision.DROP_PURE_AUTH) {
                pureAuth.incrementAndGet()
                logger.logEvent(pkg, "pure-auth: $pkg (kode autentikasi, tanpa nominal)")
                // LH-07(b) + LH-D2 (accepted): metadata-only row —
                // package, time, outcome. No snippet, no amount, no
                // hash: a hash of an OTP notification would still be
                // a hash of secret content.
                writeCaptureLog(
                    snapshot, CaptureOutcome.PURE_AUTH,
                    titleSnippet = null, amountMinor = null,
                    eventId = null, contentSha256 = null,
                )
            } else {
                noAmount.incrementAndGet()
                // Metadata-only (PR #5 re-review 28 Nov, same
                // policy as NO_KEYWORD): the PrivacyGate ran, but
                // consent attaches to the payment capture, not to
                // retaining this rejected notification's title —
                // so no title text (raw or redacted) is persisted
                // on this path either.
                logEventSafely(pkg, "target-tanpa-nominal: $pkg (nominal tidak terbaca)")
                writeCaptureLog(
                    snapshot, CaptureOutcome.NO_AMOUNT,
                    titleSnippet = null,
                    amountMinor = null, eventId = null, contentSha256 = null,
                )
            }
            return
        }
        val amount = parsed.amount ?: return
        // C3: identity from PRE-redaction transient input, identically
        // on the live and rescan paths; never recomputed from storage.
        val dedupeKey = legacyDedupeKey(pkg, snapshot.postTimeMs, amount, snapshot.combined)
        val contentSha = sha256Hex(snapshot.combined)
        // PrivacyGate (§7.1 C4 + §8.3 R11 re-scan gate): redaction
        // before ANY durable write. The gate result's redacted
        // fields are the only text that ever reaches storage.
        val gate = privacyGate.gate(snapshot, amount)
        val routeIds = consented.map { it.id }
        // §7.3A: the B-eligible routes for this capture (mixed
        // financial+OTP event + valid P6 opt-in). Computed before
        // the payload so the legacy queue item carries the
        // `raw_ephemeral` flag the flush needs to take the snapshot
        // at its per-hop claim step (§7.3A(d)).
        val bRoutes = if (rawSnapshotStore != null && gate.hasConfirmedSecret) {
            consented.filter { privacyGate.isBEligible(it.id, snapshot, amount, now) }
        } else {
            emptyList()
        }
        val payload = buildPayload(
            snapshot = snapshot,
            amount = amount,
            dedupeKey = dedupeKey,
            redactedTitle = gate.redactedTitle,
            redactedText = gate.redactedText,
            amountAmbiguous = parsed.amountAmbiguous,
            tokens = parsed.tokens,
            redacted = gate.redacted,
            inseparable = gate.inseparable,
            rawEphemeral = bRoutes.isNotEmpty(),
        )
        val event = CanonicalEvent(
            eventId = UUID.randomUUID().toString(),
            sourceEventId = "$pkg|${snapshot.postTimeMs}",
            type = EventSchema.TYPE_PAYMENT_NOTIFICATION,
            category = EventSchema.CATEGORY_PAYMENT,
            source = EventSource(
                EventSchema.SOURCE_TYPE_ANDROID_NOTIFICATION,
                deviceIdProvider(),
                pkg,
            ),
            occurredAtMs = snapshot.postTimeMs,
            capturedAtMs = now,
            data = EventData(amount, parsed.currency),
            metadata = EventMetadata(PaymentParser.PARSER_VERSION, "structured"),
            dedupeKey = dedupeKey,
            contentSha256 = contentSha,
            exportRef = UUID.randomUUID().toString(),
            sourceAccountKey = null,
            legacyPayloadJson = payload.toString(),
            retentionUntilMs = null,
        )
        val qkey = LegacyQkey.build(dedupeKey, LegacyQkey.KIND_CHAIN, routeIds)
        // Per-route stamps (§7.5): the consent epoch current at
        // capture + the URL hash. Config epoch has no live source
        // in Stage D (route config lives in SharedPreferences) —
        // stamped 0 until the D5 route editor tracks it.
        val stamps = consented.associate { route ->
            val epoch = consentStore?.epoch(route.id) ?: -1L
            route.id to RouteStamp(
                consentEpoch = epoch,
                configEpoch = 0,
                urlSha256 = sha256Hex(route.url),
            )
        }
        // §7.3A: retain a raw snapshot per B-eligible route (mixed
        // financial+OTP event + valid P6 opt-in at capture). The
        // snapshot is in-memory only, keyed by (dedupe_key,
        // route_id, consent epoch). An evicted snapshot is counted
        // and logged; its delivery is evaluated by the §7.3B gate
        // at claim time (the eviction itself never dispatches).
        var rawEphemeral = false
        val store = rawSnapshotStore
        if (store != null && gate.hasConfirmedSecret) {
            for (route in bRoutes) {
                val epoch = stamps[route.id]?.consentEpoch ?: -1L
                val evicted = store.put(
                    RawSnapshotStore.RawSnapshot(
                        dedupeKey = dedupeKey,
                        routeId = route.id,
                        consentEpoch = epoch,
                        title = snapshot.title.take(RawSnapshotStore.MAX_TEXT_CHARS),
                        text = snapshot.text.ifEmpty { snapshot.bigText }
                            .take(RawSnapshotStore.MAX_TEXT_CHARS),
                        capturedAtMs = now,
                    ),
                )
                if (evicted != null) {
                    rawSnapshotsEvicted.incrementAndGet()
                    logger.logLocal("snapshot raw diusir (kapasitas): ${evicted.dedupeKey.take(24)}…")
                }
                rawEphemeral = true
            }
        }
        val stagingRow = CaptureStagingEntity(
            legacyQkey = qkey,
            dedupeKey = dedupeKey,
            contentSha256 = contentSha,
            payloadSha256 = sha256Hex(payload.toString()),
            // The staging row carries one epoch: the first
            // consenting route's (per-route epochs live on the
            // delivery's routeStampsJson; the sweep's gate reads
            // those).
            consentEpoch = stamps.values.firstOrNull()?.consentEpoch ?: -1L,
            configEpoch = 0,
            createdAtMs = now,
        )
        val deliveryState =
            if (parsed.amountAmbiguous) DeliveryState.NEEDS_REVIEW else DeliveryState.PENDING
        val delivery = Delivery(
            deliveryId = UUID.randomUUID().toString(),
            eventId = event.eventId,
            kind = DeliveryKind.LEGACY_CHAIN,
            state = deliveryState,
            rawEphemeral = rawEphemeral,
            legacyQkey = qkey,
            routeIds = routeIds,
            createdAtMs = now,
            updatedAtMs = now,
            routeStamps = stamps,
        )
        val stagedResult = try {
            repository.stageCapture(event, listOf(stagingRow))
        } catch (e: Exception) {
            // Staging write failed: the capture is parked in memory
            // only if the caller retries; here the snapshot is lost
            // to this attempt but the notification may still be
            // recovered by rescan while active. Surfaced, never silent.
            parked.incrementAndGet()
            logger.logLocal("capture gagal staging ($pkg): ${e.message?.take(120)}")
            return
        }
        staged.incrementAndGet()
        when (stagedResult.eventOutcome) {
            EventInsertOutcome.COLLISION -> {
                // Same dedupe_key, different content: the first event
                // is kept, no delivery is created (§3 R11).
                identityCollision.incrementAndGet()
                logger.logLocal("capture collision (dedupe sama, isi beda): $pkg @${snapshot.postTimeMs}")
                // LH-04(c): unlinked row (LH-06) — the colliding
                // observation is not the kept event.
                writeCaptureLog(
                    snapshot, CaptureOutcome.COLLISION,
                    titleSnippet = gate.redactedTitle.take(40),
                    amountMinor = amount, eventId = null, contentSha256 = contentSha,
                )
                return
            }
            EventInsertOutcome.DUPLICATE -> {
                // Rescan cross-store rule (§6): an existing
                // PENDING/IN_FLIGHT delivery for this dedupe_key
                // (found via the event) means the capture is already
                // durable — drop the staging row, no second delivery.
                // (Checked by event, not by qkey: the route set may
                // have changed between the live capture and a rescan,
                // and one event maps to one chain delivery, §4.2.)
                duplicates.incrementAndGet()
                val existingEvent = repository.getEventByDedupeKey(dedupeKey)
                val existingDeliveries = existingEvent?.let {
                    repository.getDeliveriesByEventId(it.eventId)
                }.orEmpty()
                if (existingDeliveries.any {
                        it.state == DeliveryState.PENDING || it.state == DeliveryState.IN_FLIGHT
                    }
                ) {
                    repository.deleteStagingRow(qkey)
                    writeCaptureLog(
                        snapshot, CaptureOutcome.DUPLICATE,
                        titleSnippet = gate.redactedTitle.take(40),
                        amountMinor = amount, eventId = null, contentSha256 = contentSha,
                    )
                    return
                }
                if (repository.getDeliveryByQkey(qkey) != null) {
                    repository.deleteStagingRow(qkey)
                    writeCaptureLog(
                        snapshot, CaptureOutcome.DUPLICATE,
                        titleSnippet = gate.redactedTitle.take(40),
                        amountMinor = amount, eventId = null, contentSha256 = contentSha,
                    )
                    return
                }
                // Event exists but no live delivery (a partial
                // earlier capture, or a terminal delivery): fall
                // through and promote.
            }
            EventInsertOutcome.INSERTED -> Unit
        }
        // The insert outcome, recorded durably (LH-04(c)): INSERTED
        // links to the new event; the DUPLICATE fall-through (a
        // partial earlier capture now promoted) stays unlinked.
        writeCaptureLog(
            snapshot,
            if (stagedResult.eventOutcome == EventInsertOutcome.INSERTED) {
                CaptureOutcome.CAPTURED
            } else {
                CaptureOutcome.DUPLICATE
            },
            titleSnippet = gate.redactedTitle.take(40),
            amountMinor = amount,
            eventId = if (stagedResult.eventOutcome == EventInsertOutcome.INSERTED) {
                event.eventId
            } else {
                null
            },
            contentSha256 = contentSha,
        )
        enqueueStaged(stagingRow, delivery, payload, snapshot, routeIds)
    }

    /**
     * One durable capture-log row (LH-04), on the capture executor
     * thread — a single-row insert, never holding a DB transaction
     * during network I/O (LH-04(d)). A failed insert is counted and
     * logged explicitly, never silently skipped; the capture itself
     * continues (the event row is already durable).
     */
    private suspend fun writeCaptureLog(
        snapshot: NotificationSnapshot,
        outcome: String,
        titleSnippet: String?,
        amountMinor: Long?,
        eventId: String?,
        contentSha256: String?,
    ) {
        val dao = captureLogDao ?: return
        try {
            dao.insert(
                CaptureLogEntity(
                    captureId = UUID.randomUUID().toString(),
                    sourcePackage = snapshot.packageName,
                    postTimeMs = snapshot.postTimeMs,
                    capturedAtMs = clock(),
                    outcome = outcome,
                    titleSnippet = titleSnippet?.take(40),
                    amountMinor = amountMinor,
                    eventId = eventId,
                    contentSha256 = contentSha256,
                ),
            )
        } catch (e: Exception) {
            captureLogFailures.incrementAndGet()
            logger.logLocal(
                "capture-log gagal ditulis ($outcome ${snapshot.packageName}): " +
                    e.message?.take(120),
            )
        }
    }

    /** A PrivacyGate-scanned title snippet (≤40 chars, LH-04). */
    private fun snippet(title: String): String =
        SensitiveDataRedactor.redact(title).take(40)

    /**
     * Mode-gated enqueue of one staged capture (§8.3 (2′)/(4)):
     * exactly one store, decided by the mode read on this thread.
     * Returns true when the capture is durable in its target store
     * (and the staging row deleted).
     */
    private suspend fun enqueueStaged(
        stagingRow: CaptureStagingEntity,
        delivery: Delivery,
        payload: JSONObject,
        snapshot: NotificationSnapshot,
        routeIds: List<String>,
    ): Boolean {
        val mode = modeReader()
        // §7.6 is absolute: an amount-ambiguous event is NEVER
        // enqueued to the legacy sender (the first-token amount may
        // be a balance, and the legacy sender cannot hold it for
        // review). It promotes to Room as NEEDS_REVIEW in EVERY
        // mode — in LEGACY_ONLY the delivery is inert (the worker
        // claims only in WORKER_ONLY) and the pick releases it to
        // PENDING; it dispatches after cutover. The review surface
        // discloses the wait in LEGACY_ONLY mode.
        if (delivery.state == DeliveryState.NEEDS_REVIEW) {
            return promoteToRoom(stagingRow, delivery, payload, snapshot, routeIds, mode)
        }
        return when (mode) {
            CutoverMode.LEGACY_ONLY -> {
                val ok = legacyEnqueue.enqueue(payload, LegacyQkey.KIND_CHAIN, routeIds)
                if (!ok) {
                    parked.incrementAndGet()
                    logger.logLocal(
                        "capture parkir (gagal antre legacy): ${snapshot.packageName} " +
                            "@${snapshot.postTimeMs}; dicoba lagi oleh sweep",
                    )
                    return false
                }
                repository.deleteStagingRow(stagingRow.legacyQkey)
                enqueued.incrementAndGet()
                logger.logEvent(
                    snapshot.packageName,
                    "payment: Rp${payload.optLong("amount")} dari ${snapshot.packageName} -> " +
                        routeIds.joinToString(" > "),
                )
                true
            }
            CutoverMode.DRAINING, CutoverMode.WORKER_ONLY ->
                promoteToRoom(stagingRow, delivery, payload, snapshot, routeIds, mode)
        }
    }

    /** Promotes one staged capture to a Room delivery (all modes for NEEDS_REVIEW). */
    private suspend fun promoteToRoom(
        stagingRow: CaptureStagingEntity,
        delivery: Delivery,
        payload: JSONObject,
        snapshot: NotificationSnapshot,
        routeIds: List<String>,
        mode: CutoverMode,
    ): Boolean {
        val result = repository.promoteStagingToRoom(stagingRow, delivery)
        if (!result.deliveryInserted && repository.getDeliveryByQkey(stagingRow.legacyQkey) == null) {
            // Insert failed AND no delivery exists: the staging row
            // is retained for the sweep (§8.3 (7′)).
            parked.incrementAndGet()
            logger.logLocal(
                "capture parkir (gagal promote Room): ${snapshot.packageName} " +
                    "@${snapshot.postTimeMs}; dicoba lagi oleh sweep",
            )
            return false
        }
        if (mode == CutoverMode.DRAINING) roomCapturesDuringDraining.incrementAndGet()
        enqueued.incrementAndGet()
        logger.logEvent(
            snapshot.packageName,
            "payment: Rp${payload.optLong("amount")} dari ${snapshot.packageName} -> " +
                routeIds.joinToString(" > ") + " (Room)",
        )
        if (mode == CutoverMode.WORKER_ONLY) onRoomEnqueued()
        return true
    }

    // --- Staging sweep (capture worker thread) ---

    /**
     * Re-promotes orphaned staging rows through the same lock/mode
     * gate (§8.3 (2′)): e.g. after process death between staging
     * and enqueue. Idempotent by `dedupe_key` / `legacy_qkey`:
     * a row whose delivery already exists is deleted without a
     * second enqueue; a legacy re-enqueue dedupes by `qkey`.
     */
    suspend fun sweepOrphanedStaging() {
        val rows = repository.getAllStagingRows()
        for (row in rows) {
            try {
                sweepOne(row)
            } catch (e: Exception) {
                logger.logLocal("sweep staging gagal (${row.legacyQkey}): ${e.message?.take(120)}")
            }
        }
        // A completed full staging sweep is the recovery proof
        // for an unresolved-at-close incident (PR #5 re-review
        // 28 Nov): whatever staging survived was re-promoted or
        // explicitly deleted by the sweep above.
        recoverIncidents(CaptureIncidentCategory.UNRESOLVED_AT_CLOSE)
    }

    private suspend fun sweepOne(row: CaptureStagingEntity) {
        val existing = repository.getDeliveryByQkey(row.legacyQkey)
        if (existing != null) {
            // Already promoted (crash between enqueue and staging
            // delete): delete the orphan row, no second delivery.
            repository.deleteStagingRow(row.legacyQkey)
            sweptOrphaned.incrementAndGet()
            return
        }
        val event = repository.getEventByDedupeKey(row.dedupeKey)
        if (event == null) {
            // No event row: the staging row cannot be promoted
            // (nothing to deliver). Delete it — counted, visible.
            repository.deleteStagingRow(row.legacyQkey)
            sweptOrphaned.incrementAndGet()
            logger.logLocal("staging tanpa event dihapus: ${row.legacyQkey}")
            return
        }
        val parsed = LegacyQkey.parse(row.legacyQkey) ?: run {
            repository.deleteStagingRow(row.legacyQkey)
            sweptOrphaned.incrementAndGet()
            logger.logLocal("staging qkey rusak dihapus: ${row.legacyQkey}")
            return
        }
        // F18: a payload_sha256 mismatch means the staged payload no
        // longer matches the event row — hold the row (`staging_
        // mismatch`), never dispatch it. The row stays for operator
        // inspection; the sweep does not delete it.
        val currentPayloadJson = event.legacyPayloadJson ?: ""
        if (row.payloadSha256 != null &&
            sha256Hex(currentPayloadJson) != row.payloadSha256
        ) {
            stagingMismatch.incrementAndGet()
            logger.logLocal("staging_mismatch, tidak dipromosikan: ${row.legacyQkey}")
            return
        }
        val payload = try {
            JSONObject(event.legacyPayloadJson ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        val ambiguous = payload.optBoolean("amount_ambiguous", false)
        val now = clock()
        val delivery = Delivery(
            deliveryId = UUID.randomUUID().toString(),
            eventId = event.eventId,
            kind = DeliveryKind.LEGACY_CHAIN,
            state = if (ambiguous) DeliveryState.NEEDS_REVIEW else DeliveryState.PENDING,
            // B4/B19: the snapshot is gone after a process restart,
            // but the delivery is still a B delivery — the worker
            // runs the §7.3B evaluation at claim time instead of
            // silently posting the redacted payload.
            rawEphemeral = payload.optBoolean("raw_ephemeral", false),
            legacyQkey = row.legacyQkey,
            routeIds = parsed.routeIds,
            createdAtMs = now,
            updatedAtMs = now,
        )
        val snapshot = NotificationSnapshot(
            key = "",
            packageName = event.sourcePackage ?: "",
            postTimeMs = event.occurredAtMs,
            flags = 0,
            title = "",
            text = "",
            bigText = "",
            capturedAtMs = now,
        )
        if (enqueueStaged(row, delivery, payload, snapshot, parsed.routeIds)) {
            sweptOrphaned.incrementAndGet()
        }
    }

    // --- Helpers ---

    /**
     * Staging re-scan gate (§8.3 R11, defence in depth) now lives
     * in [PrivacyGate.gate]: redact every classified span
     * (confirmed AND ambiguous — §7.1(b) redacts ambiguous spans
     * too), then re-scan the redacted text; if a class-(a) span
     * survives, the text is dropped and only structured fields
     * are staged. This coordinator calls the gate; it never
     * re-implements the scan.
     */

    /** The legacy wire payload (v2.3 shape), built from REDACTED text only. */
    private fun buildPayload(
        snapshot: NotificationSnapshot,
        amount: Long,
        dedupeKey: String,
        redactedTitle: String,
        redactedText: String,
        amountAmbiguous: Boolean,
        tokens: List<com.netter.paylist.parser.AmountToken>,
        redacted: Boolean,
        inseparable: Boolean,
        rawEphemeral: Boolean,
    ): JSONObject = JSONObject().apply {
        put("type", "payment")
        put("device", deviceIdProvider())
        put("pkg", snapshot.packageName)
        put("amount", amount)
        put("title", redactedTitle.take(500))
        put("text", redactedText.take(500))
        put("notif_time", java.time.Instant.ofEpochMilli(snapshot.postTimeMs).toString())
        put("dedupe_key", dedupeKey)
        // §7.3A: the flush takes the raw snapshot at its per-hop
        // claim step only for B items; the flag survives the
        // staging sweep so a post-restart promotion still runs
        // the §7.3B evaluation (B4/B19) instead of silently A′.
        if (rawEphemeral) put("raw_ephemeral", true)
        // Direction, computed in memory from the transient raw text
        // with the §7.2 two-list heuristic (§7.1 confidence matrix).
        // Advisory only (PHP ignores extra fields); the claim-time
        // §7.3B gate reads it for criterion 2, and the A′
        // constructed text uses it for recorder routes.
        put(
            "direction",
            com.netter.paylist.parser.DirectionHeuristic.classify(snapshot.combined)
                .name.lowercase(),
        )
        if (redacted) put("redacted", true)
        if (inseparable) put("inseparable", true)
        if (amountAmbiguous) {
            put("amount_ambiguous", true)
            val arr = JSONArray()
            for (token in tokens) {
                arr.put(JSONObject().apply {
                    put("value", token.value)
                    put("start", token.start)
                    put("end", token.end)
                })
            }
            put("amount_tokens", arr)
        }
    }
}
