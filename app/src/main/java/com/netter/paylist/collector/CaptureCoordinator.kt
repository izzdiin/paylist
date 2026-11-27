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
)

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
 * ([CaptureOutcome.OVERFLOW_DROPPED] — package, post time,
 * PrivacyGate-scanned title snippet; no amount, no event link, no
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
            } ?: return
            if (!task.cancelled) {
                try {
                    task.run()
                } catch (e: Exception) {
                    logger.logLocal(
                        "tugas capture gagal (${task.kind}): ${e.message?.take(120)}",
                    )
                }
            }
        }
    }

    // --- Durable overflow accounting (writer thread) ---

    /** One pending durable overflow record, with its write-attempt count. */
    private class OverflowRecord(val snapshot: NotificationSnapshot) {
        var attempts: Int = 0
    }

    private val overflowQueue = LinkedBlockingQueue<OverflowRecord>(OVERFLOW_QUEUE_CAPACITY)

    @Volatile
    private var overflowActive: OverflowRecord? = null

    /**
     * The overflow writer: a dedicated daemon thread, deliberately
     * NOT stopped by [close] (like a logger, it outlives the
     * capture pipeline so a capture attempted after shutdown
     * still gets its record attempted; [close] only waits,
     * bounded, for it to drain). It exits with the process.
     */
    private val overflowWriter = Thread({ overflowLoop() }, "paylist-overflow-writer").apply {
        isDaemon = true
        start()
    }

    /**
     * Overflow accounting for one dropped snapshot: the in-memory
     * counter + a loud local log line synchronously (cheap, safe
     * on any caller thread), and the durable `capture_log` row
     * ASYNCHRONOUSLY on the overflow-writer thread — this method
     * never touches the database and never blocks the caller on
     * I/O, so the listener (Binder) thread is never stalled by an
     * overflow write.
     *
     * The row is metadata-only ([CaptureOutcome.OVERFLOW_DROPPED]
     * — package, post time, PrivacyGate-scanned title snippet; no
     * amount, no event link, no content hash: the amount was
     * never parsed and a hash of un-gated raw text would be a
     * hash of possibly-secret content). It is the operator-visible
     * evidence (History filter) and the rescan handle while the
     * notification is still posted.
     *
     * A null DAO (a bare coordinator in tests) leaves only the
     * counter + log. A full writer queue leaves the counter + log
     * and is counted in [CaptureCounters.overflowRecordDropped] —
     * never silent either way.
     */
    private fun recordOverflow(snapshot: NotificationSnapshot?) {
        droppedOverflow.incrementAndGet()
        if (snapshot == null) {
            logger.logLocal("capture overflow: snapshot tidak dikenal terbuang (tanpa catatan tahan)")
            return
        }
        logger.logLocal(
            "capture overflow: ${snapshot.packageName} @${snapshot.postTimeMs} " +
                "terbuang sebelum staging (antrean penuh); pindai ulang selagi notifikasi masih aktif",
        )
        if (captureLogDao == null) return
        if (!overflowQueue.offer(OverflowRecord(snapshot))) {
            overflowRecordDropped.incrementAndGet()
            logger.logLocal(
                "catatan overflow tidak masuk antrean penulis (${snapshot.packageName}): " +
                    "bukti hanya counter + log lokal",
            )
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
        while (true) {
            val record = try {
                overflowQueue.take()
            } catch (_: InterruptedException) {
                continue
            }
            overflowActive = record
            try {
                val dao = captureLogDao
                if (dao != null) {
                    val snapshot = record.snapshot
                    runBlocking {
                        dao.insert(
                            CaptureLogEntity(
                                captureId = UUID.randomUUID().toString(),
                                sourcePackage = snapshot.packageName,
                                postTimeMs = snapshot.postTimeMs,
                                capturedAtMs = clock(),
                                outcome = CaptureOutcome.OVERFLOW_DROPPED,
                                titleSnippet = snippet(snapshot.title),
                                amountMinor = null,
                                eventId = null,
                                contentSha256 = null,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                captureLogFailures.incrementAndGet()
                val snapshot = record.snapshot
                logger.logLocal(
                    "catatan overflow gagal ditulis (${snapshot.packageName}): " +
                        e.message?.take(120),
                )
                record.attempts += 1
                if (record.attempts < MAX_OVERFLOW_WRITE_ATTEMPTS) {
                    if (!overflowQueue.offer(record)) {
                        overflowRecordDropped.incrementAndGet()
                        logger.logLocal(
                            "catatan overflow hilang saat retry (${snapshot.packageName})",
                        )
                    }
                } else {
                    overflowRecordDropped.incrementAndGet()
                    logger.logLocal(
                        "catatan overflow menyerah setelah ${record.attempts} percobaan " +
                            "(${snapshot.packageName}): bukti hanya counter + log lokal",
                    )
                }
            } finally {
                overflowActive = null
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
            if (overflowQueue.isEmpty() && overflowActive == null) return
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

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
        overflowPending = (overflowQueue.size + if (overflowActive != null) 1 else 0).toLong(),
    )

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
        val task = CaptureTask(CaptureTaskKind.SWEEP, null) {
            try {
                runBlocking { sweepOrphanedStaging() }
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
     * Stops the capture worker and the sweep timer (tests /
     * shutdown).
     *
     * Graceful-shutdown evidence: snapshots still queued
     * (RECEIVED, never staged) are stranded and each gets a
     * durable overflow record ([recordOverflow]); a stranded
     * sweep releases its [sweepNow] caller with a throw instead
     * of a hang. [close] then waits, bounded, for the overflow
     * writer to persist the queued records before returning.
     * This covers `close()` only — a SIGKILL gives no chance to
     * run it, and those snapshots are recoverable only via
     * rescan while still posted (see the class KDoc).
     */
    fun close() {
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
        flushOverflow(OVERFLOW_FLUSH_TIMEOUT_MS)
        captureWorker.interrupt()
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
            logger.logEvent(pkg, "target-lain: $pkg - ${snapshot.title.take(40)}")
            // LH-04(a): the gate never ran here, so the snippet is
            // scanned directly (confirmed + ambiguous spans redacted).
            writeCaptureLog(
                snapshot, CaptureOutcome.NO_KEYWORD,
                titleSnippet = snippet(snapshot.title),
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
                logger.logEvent(pkg, "target-tanpa-nominal: $pkg - ${snapshot.title.take(40)}")
                writeCaptureLog(
                    snapshot, CaptureOutcome.NO_AMOUNT,
                    titleSnippet = gate.redactedTitle.take(40),
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
