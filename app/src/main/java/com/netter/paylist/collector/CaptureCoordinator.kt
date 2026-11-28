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
    /**
     * Persistent-health incidents that could not be queued for
     * the health writer (PR #5 audit 28 Nov, comment 7229800304):
     * the bounded incident queue was full, or the health writer
     * had already terminated. The incident is then evidenced only
     * by this counter + a local log line — counted, never silent.
     */
    val healthIncidentsDropped: Long = 0,
    /**
     * Capture-health store accesses that failed (read, parse or
     * durable write) — the persistent layer could not be read or
     * updated, and the failure is surfaced here and in
     * [CaptureDegradedState] instead of being swallowed.
     */
    val healthStoreFailures: Long = 0,
    /**
     * Offer-journal syncs that exceeded the bounded caller wait
     * (audit 7237720579, A06): the journal write continued on
     * the journal thread and landed late, but the offering
     * caller did not wait for it. A non-zero value means the
     * health store is slower than the declared bound (or
     * failing) — and that offers made inside such a window are
     * exposed to the declared residual gap if the process dies
     * before the sync lands.
     */
    val healthJournalTimeouts: Long = 0,
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
     * in the durable lifecycle record (its worker/writer were
     * still alive when its bounded close gave up), or while the
     * fence state is UNKNOWN (lifecycle record corrupt, or a
     * legacy fence whose owner cannot be identified): a
     * replacement coordinator must not process captures until
     * the fence clears by proof of the owner's death.
     */
    val successorFenced: Boolean = false,
    /**
     * The unresolved LOSSES, exposed independently of component
     * health (PR #5 audit 28 Nov, comment 7229800304): persistent
     * incidents that represent a specific snapshot whose outcome
     * is lost or unknown — OPEN `OVERFLOW_RECORD_LOST` and
     * `UNRESOLVED_AT_CLOSE` incidents (identity-keyed), plus
     * UNRECOVERABLE `PRE_STAGE_INTERRUPTED` ones. A component
     * recovering (e.g. the database accepting inserts again)
     * never removes an entry here; only identity-level
     * reconciliation or an explicit operator disposition
     * ([CaptureCoordinator.dismissIncident]) does.
     */
    val unresolvedLosses: List<CaptureIncident> = emptyList(),
)

/**
 * Outcome of one staging-sweep pass (audit P1-3): per-row
 * results, so recovery claims can be keyed to what the sweep
 * actually resolved — a pass with failed or held rows, or with
 * no rows at all, proves nothing about an unresolved capture.
 */
data class SweepResult(
    /** Staging rows present when the pass started. */
    val rowsSeen: Int,
    /** Rows terminally resolved (promoted, or deliberately deleted as already-promoted/corrupt). */
    val rowsResolved: Int,
    /** Rows whose processing threw (they stay staged for the next pass). */
    val rowsFailed: Int,
    /** Rows deliberately NOT resolved (payload mismatch hold, or a failed promotion). */
    val rowsHeld: Int,
)

/** Incident categories in the persistent capture-health document. */
object CaptureIncidentCategory {
    /** A snapshot was dropped by saturation / at close (evidence: overflow row). */
    const val OVERFLOW_SNAPSHOT_DROPPED: String = "overflow_snapshot_dropped"

    /**
     * A durable overflow record was ultimately lost (queue full / writer gone).
     *
     * This is an incident about the RECORD — the metadata
     * evidence for a dropped snapshot — not about the payment.
     * Its identity-level recovery (a durable overflow row for
     * the same observation identity landing later) is therefore
     * METADATA recovery: the evidence now exists. The payment
     * itself is recovered only when the snapshot is actually
     * captured (rescan → staging), which is what
     * [OVERFLOW_SNAPSHOT_DROPPED] and the sweep-side recovery of
     * [UNRESOLVED_AT_CLOSE] track. The two are never conflated
     * (audit 7235948077, A05).
     */
    const val OVERFLOW_RECORD_LOST: String = "overflow_record_lost"

    /** The database kept failing across the whole overflow retry budget. */
    const val DB_PERMANENT_FAILURE: String = "db_permanent_failure"

    /** The overflow writer loop itself failed terminally for a record. */
    const val WRITER_TERMINAL_FAILURE: String = "writer_terminal_failure"

    /** A capture was still running when close() exhausted its bounded wait. */
    const val UNRESOLVED_AT_CLOSE: String = "unresolved_at_close"

    /** A capture was interrupted by close() before its staging outcome was confirmed. */
    const val PRE_STAGE_INTERRUPTED: String = "pre_stage_interrupted"

    /** The stored health document did not parse; its raw value was quarantined, not discarded. */
    const val HEALTH_DOC_CORRUPT: String = "health_doc_corrupt"

    /** The stored lifecycle record did not parse; it is preserved untouched and the successor waits. */
    const val LIFECYCLE_CORRUPT: String = "lifecycle_corrupt"

    /** A successor cleared a fence whose owner process was provably dead (an orphaned fence). */
    const val FENCE_ORPHAN_CLEARED: String = "fence_orphan_cleared"

    /**
     * A close-time fence publish failed on the lifecycle
     * channel AND on the health-document fallback channel
     * (audit 7237720579, A03 residual): a successor cannot be
     * trusted to see the fence, so the failure is recorded as
     * an incident and surfaced in the degraded state rather
     * than passing silently.
     */
    const val FENCE_PUBLISH_FAILED: String = "fence_publish_failed"

    /**
     * Health-incident RECORD operations were OFFERED by a
     * previous process but never durably applied before it
     * died (audit 7235948077, A06): the offer journal survived,
     * the queued incidents did not. The count is exact
     * (journal offered − journal accounted − document
     * applied), but WHICH incidents they were is unknowable —
     * hence UNKNOWN. The count is of incident RECORDS (the
     * persistent health evidence), never of payments or
     * captures (audit 7237720579, A06(b)). Aggregate (no
     * identity); closes only by explicit operator disposition,
     * never by proof.
     */
    const val UNKNOWN_LOSS: String = "unknown_loss"
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
    /**
     * Per-snapshot OBSERVATION identity for loss/unknown
     * incidents (PR #5 audit 28 Nov; strengthened by audit
     * 7235948077, A05; KEYED by audit 7237720579, A05/A06):
     * `obs:` + HMAC-SHA-256, under the durable vault-held key,
     * over package + post time + the SHA-256 of the snapshot's
     * full combined text — the same components the dedupe key
     * discriminates with (the amount is a pure function of the
     * text, and the full-text hash is strictly stronger than
     * the dedupe key's 40-char prefix hash). Two distinct
     * notifications sharing a package AND a millisecond get
     * DIFFERENT identities; the same notification re-observed
     * by a rescan gets the SAME one. Privacy: only the keyed
     * derivation is persisted — no raw text, no OTP, no
     * content, and no UNKEYED hash of the text (an unkeyed
     * deterministic hash of low-entropy text could be guessed
     * offline from the known package + post time; the keyed
     * one cannot be verified against a guess without the key).
     * Recovery of an identity-keyed incident is keyed to THIS
     * identity — proof about a different snapshot never
     * recovers it. Empty for aggregate component-health
     * incidents (e.g. DB_PERMANENT_FAILURE).
     */
    val identity: String = "",
)

/**
 * Identity of an OS process: PID + process start time (clock
 * ticks since boot, field 22 of `/proc/<pid>/stat`). PID +
 * start time cannot collide for two distinct processes, and a
 * reused PID is detected by the start-time mismatch — the same
 * identity the process-death oracle's markers carry.
 */
data class CaptureProcessIdentity(val pid: Int, val startTicks: Long)

/**
 * Tri-state process liveness (audit 7237720579, A03 residual).
 * Only [DEAD] is a death PROOF — the sole state allowed to
 * clear a successor fence. [UNKNOWN] (any read/parse/visibility
 * failure) retains the fence: a proof failure is never a proof
 * of death.
 */
enum class ProcessLiveness { ALIVE, DEAD, UNKNOWN }

/**
 * Reads process identities from `/proc` (works for the app's own
 * process on Android and on a Linux JVM). Anything unreadable
 * degrades to the UNKNOWN identity (-1, -1), whose liveness is
 * always [ProcessLiveness.UNKNOWN]: death must be PROVEN,
 * never assumed (fail-safe).
 */
object CaptureProcessIdentitySource {
    fun current(procRoot: String = "/proc"): CaptureProcessIdentity {
        val read = readProcStat(
            java.nio.file.Paths.get(procRoot, "self", "stat"),
        )
        if (read !is ProcStatRead.Ok) return CaptureProcessIdentity(-1, -1)
        val pid = read.text.substringBefore(' ').toIntOrNull() ?: -1
        return CaptureProcessIdentity(pid, startTicksOf(read.text))
    }

    /** True unless [identity]'s process is PROVABLY dead ([liveness] == DEAD). */
    fun isAlive(identity: CaptureProcessIdentity): Boolean =
        liveness(identity) != ProcessLiveness.DEAD

    /** Outcome of one attempt to read a `/proc/<pid>/stat` file. */
    private sealed interface ProcStatRead {
        class Ok(val text: String) : ProcStatRead

        /** The open itself reported ENOENT: the file does not exist. */
        object Absent : ProcStatRead

        /**
         * The file could not be read for any OTHER reason:
         * EACCES/EPERM, a transient I/O error, the path being a
         * directory, a visibility restriction — everything that
         * is not positive absence.
         */
        object Inaccessible : ProcStatRead
    }

    /**
     * Reads [path] classifying the failure by its KIND (audit
     * 7239229553, A03): [java.nio.file.NoSuchFileException] is
     * the explicit ENOENT signal of the open itself; every
     * other failure is [ProcStatRead.Inaccessible]. This is
     * deliberately NOT a `File.exists()` probe: an exists()
     * false cannot distinguish "not there" from "not visible
     * to you", which is exactly the confusion the audit
     * rejected.
     */
    private fun readProcStat(path: java.nio.file.Path): ProcStatRead = try {
        ProcStatRead.Ok(
            String(java.nio.file.Files.readAllBytes(path), Charsets.UTF_8),
        )
    } catch (e: java.nio.file.NoSuchFileException) {
        ProcStatRead.Absent
    } catch (t: Throwable) {
        ProcStatRead.Inaccessible
    }

    /**
     * The tri-state liveness of [identity] (audit 7237720579,
     * A03 residual; absence proof hardened by audit
     * 7239229553):
     *
     * - [ProcessLiveness.DEAD] only on POSITIVE evidence: the
     *   stat parses and its start time differs (the PID was
     *   reused by a different process), or the stat's absence
     *   is CORROBORATED (see [corroboratedAbsence]).
     * - [ProcessLiveness.ALIVE] when the stat parses and the
     *   start time matches exactly.
     * - [ProcessLiveness.UNKNOWN] for everything else: an
     *   invalid identity, an unreadable SELF stat (our own
     *   /proc view cannot be trusted this call), a permission
     *   denial, a transient I/O error, an unparseable stat, or
     *   an absence that could not be corroborated. UNKNOWN
     *   always retains the fence.
     *
     * SCOPE of the absence proof, stated plainly: the fence
     * only ever asks about THIS APP's own predecessor process
     * (the identity a predecessor recorded for itself — same
     * UID). Kernel /proc hiding (hidepid) filters processes
     * ACROSS uids, not within one uid, so for a same-uid
     * subject an ENOENT that survives corroboration is
     * positive absence, not a visibility artefact; denials
     * that do apply to a same-uid subject (SELinux, I/O
     * errors) surface as non-ENOENT failures and classify
     * UNKNOWN above.
     */
    fun liveness(
        identity: CaptureProcessIdentity,
        procRoot: String = "/proc",
    ): ProcessLiveness {
        if (identity.pid <= 0 || identity.startTicks < 0) {
            return ProcessLiveness.UNKNOWN
        }
        val root = java.nio.file.Paths.get(procRoot)
        // If our own stat cannot be read/parsed, this call's
        // view of /proc is unreliable — prove nothing.
        val self = readProcStat(root.resolve("self").resolve("stat"))
        if (self !is ProcStatRead.Ok || startTicksOf(self.text) < 0) {
            return ProcessLiveness.UNKNOWN
        }
        return when (
            val read = readProcStat(
                root.resolve(identity.pid.toString()).resolve("stat"),
            )
        ) {
            is ProcStatRead.Ok -> {
                val start = startTicksOf(read.text)
                when {
                    start < 0 -> ProcessLiveness.UNKNOWN
                    start == identity.startTicks -> ProcessLiveness.ALIVE
                    // Same PID, different start: the original
                    // process is provably gone (PID reuse).
                    else -> ProcessLiveness.DEAD
                }
            }
            ProcStatRead.Absent -> corroboratedAbsence(root, identity.pid)
            ProcStatRead.Inaccessible -> ProcessLiveness.UNKNOWN
        }
    }

    /**
     * Corroborates an ENOENT on `/proc/<pid>/stat` before it
     * may count as death (audit 7239229553: a single negative
     * probe is not positive proof of absence). DEAD requires
     * BOTH: (1) a listing of the proc root SUCCEEDS and does
     * not contain the pid, and (2) a second, independent stat
     * read also returns ENOENT. A listing failure, the pid
     * appearing in the listing (the directory is there but
     * its stat is not readable — a visibility/race artefact,
     * not death), or any other second-read outcome leaves the
     * death UNPROVEN (UNKNOWN, fence retained).
     */
    private fun corroboratedAbsence(
        root: java.nio.file.Path,
        pid: Int,
    ): ProcessLiveness {
        val names = try {
            java.nio.file.Files.newDirectoryStream(root).use { stream ->
                stream.map { it.fileName.toString() }
            }
        } catch (t: Throwable) {
            return ProcessLiveness.UNKNOWN
        }
        if (names.contains(pid.toString())) {
            return ProcessLiveness.UNKNOWN
        }
        return when (
            readProcStat(root.resolve(pid.toString()).resolve("stat"))
        ) {
            ProcStatRead.Absent -> ProcessLiveness.DEAD
            else -> ProcessLiveness.UNKNOWN
        }
    }

    private fun startTicksOf(stat: String): Long {
        // Field 22 overall = index 19 of the fields after the
        // last ") " (comm may itself contain spaces/parens).
        val afterComm = stat.substringAfterLast(") ").split(" ")
        return afterComm.getOrNull(19)?.toLongOrNull() ?: -1L
    }
}

/** The default process-wide health-document lock (one JVM). */
object CaptureHealthLock {
    private val lock = ReentrantLock()
    fun <T> withLock(block: () -> T): T = lock.withLock(block)
}

/**
 * Process-wide AUTHORITATIVE ownership claims (audit
 * 7239229553, A03 — the acceptance the previous round missed:
 * "process-wide authoritative ownership independent of
 * failing persistence").
 *
 * A coordinator CLAIMS ownership here at the exact moment it
 * would publish its successor fence — its bounded [close]
 * gave up with pipeline threads still alive — BEFORE any
 * durable write is attempted, and the claim is released only
 * when both pipeline threads have actually exited (their exit
 * handlers release it). A successor consults this registry
 * BEFORE the durable records: while a foreign claim stands,
 * the successor WAITS — it does not stage and does not write
 * — no matter what the durable records say (missing, corrupt,
 * or even cleanly inactive). Exclusion therefore no longer
 * depends on a store write succeeding: the dual-write-failure
 * corner of the previous round (lifecycle channel AND health
 * document both failing while predecessor threads live) is
 * closed by construction, because the claim lives in the
 * process's own memory, not in the failing store.
 *
 * CONSTRUCTION BOUNDARY, stated plainly: the registry is
 * authoritative WITHIN one process. That is exactly the
 * shape of the failure it closes — a predecessor whose
 * threads still live is, by construction, a predecessor in
 * a living process, and a successor replacing it in that
 * same process sees the claim. A successor in a NEW process
 * starts only after the old process died (the Android
 * process model for this app), when the predecessor's
 * threads are dead by construction; the transitional
 * evidence for that case remains the durable fence plus the
 * tri-state process-death proof, unchanged.
 */
object CaptureOwnershipRegistry {
    private val lock = ReentrantLock()
    private val claims = HashMap<String, CaptureProcessIdentity>()

    /**
     * Per-instance record of which pipeline threads have
     * EXITED (bit 1 = capture worker, bit 2 = overflow
     * writer), written by the exit handlers via
     * [noteThreadExit] under the same lock that serialises
     * [claimIfAlive]. Instance ids are unique per instance
     * and never reused, so an exit record can never be
     * mistaken for a later instance's state.
     */
    private val exitedHalves = HashMap<String, Int>()

    private const val WORKER_HALF = 1
    private const val WRITER_HALF = 2
    private const val BOTH_HALVES = WORKER_HALF or WRITER_HALF

    /**
     * Test seam (audit 7242084700, A03): when set, invoked
     * INSIDE the registry lock by [claimIfAlive] after the
     * liveness sample and before the claim is inserted — the
     * exact point the old code raced. A barrier test parks
     * the publish there, runs an exit handler to completion
     * (it queues on this lock), then lets the publish finish;
     * the handshake must still leave no orphan claim. Null in
     * production.
     */
    @Volatile
    internal var claimBarrierHook: (() -> Unit)? = null

    /**
     * THE ATOMIC HANDSHAKE (audit 7242084700, A03 residual):
     * samples the pipeline threads' liveness and inserts
     * [instanceId]'s ownership claim in ONE critical section,
     * serialised against [noteThreadExit]. A half whose exit
     * has already been recorded never counts as alive, even
     * if the sampled thread object still reports alive in a
     * race window; a half that exits DURING this section
     * queues its [noteThreadExit] on the same lock and
     * completes the release immediately after the claim lands.
     * There is therefore no interleaving in which a claim is
     * inserted for threads that have both already exited, and
     * none in which an exit is recorded but a later claim
     * resurrects it: a claim exists only while at least one
     * pipeline thread is un-exited, and it is removed by the
     * second exit, whenever that exit runs. Returns true when
     * a claim was inserted.
     */
    fun claimIfAlive(
        instanceId: String,
        identity: CaptureProcessIdentity,
        workerAlive: () -> Boolean,
        writerAlive: () -> Boolean,
    ): Boolean {
        lock.withLock {
            val exited = exitedHalves[instanceId] ?: 0
            val workerLive = workerAlive() && (exited and WORKER_HALF) == 0
            val writerLive = writerAlive() && (exited and WRITER_HALF) == 0
            if (!workerLive && !writerLive) return false
            claimBarrierHook?.invoke()
            claims[instanceId] = identity
            return true
        }
    }

    /**
     * Records one pipeline thread's exit (called FIRST by the
     * thread's exit handler, before any store cleanup, so the
     * fact is visible to a concurrent publish immediately).
     * When the second half exits, the instance's ownership
     * claim — whenever it was inserted — is released in the
     * same critical section.
     */
    fun noteThreadExit(instanceId: String, workerHalf: Boolean) {
        lock.withLock {
            val mask = (exitedHalves[instanceId] ?: 0) or
                (if (workerHalf) WORKER_HALF else WRITER_HALF)
            exitedHalves[instanceId] = mask
            if (mask == BOTH_HALVES) {
                claims.remove(instanceId)
            }
        }
    }

    /** True while ANOTHER instance's claim stands. */
    fun hasForeignClaim(selfInstanceId: String): Boolean =
        lock.withLock { claims.keys.any { it != selfInstanceId } }
}

/**
 * Process-wide registry of LIVE coordinator instances (audit
 * 7239229553, A06): an instance registers at construction and
 * unregisters only when its health writer has terminated —
 * the point after which it can never apply another queued
 * health operation. Startup reconciliation consults this
 * registry (for same-process owners) before it may call an
 * owner's unapplied offers a loss: a still-live owner's
 * pending records are DEFERRED, never counted as a dead
 * process's UNKNOWN_LOSS.
 */
object CaptureLiveInstances {
    private val lock = ReentrantLock()
    private val live = HashSet<String>()

    fun register(instanceId: String) {
        lock.withLock { live.add(instanceId) }
    }

    fun unregister(instanceId: String) {
        lock.withLock { live.remove(instanceId) }
    }

    fun isLive(instanceId: String): Boolean =
        lock.withLock { live.contains(instanceId) }
}

/**
 * The process-wide OFFER-JOURNAL lock (one JVM): serialises
 * journal read-modify-writes across coordinator instances.
 * Deliberately NOT the health-document lock (audit
 * 7237720579, A06): a health writer stuck inside a document
 * commit holds the document lock, and the journal must still
 * advance in exactly that state — saturation drops are
 * journaled at the moment they happen, behind no document
 * write.
 */
object CaptureJournalLock {
    private val lock = ReentrantLock()
    fun <T> withLock(block: () -> T): T = lock.withLock(block)
}

/**
 * Durable store for the capture-health state (PR #5 re-review
 * 28 Nov; durability contract tightened by the PR #5 audits of
 * 28 Nov, comments 7229800304 and 7235948077). The store holds
 * THREE separate durable values, deliberately kept apart:
 *
 * 1. The HEALTH DOCUMENT ([readCaptureHealth] /
 *    [writeCaptureHealth]): the persistent incidents. Purely
 *    informational — its loss or corruption degrades the
 *    incident history, never the pipeline's safety properties.
 * 2. The LIFECYCLE RECORD ([readCaptureLifecycle] /
 *    [writeCaptureLifecycle]): the successor fence — the
 *    AUTHORITATIVE lifecycle exclusion (audit 7235948077, A03).
 *    It lives apart from the health document precisely so that
 *    a corrupt or lost health document can never open the
 *    exclusion: fence evaluation reads this record (plus,
 *    for stores written before the separation, a one-time
 *    adoption of the legacy in-document fence — and, since
 *    audit 7237720579, an active in-document fence from an
 *    owner the record has not adopted-and-retired, which is
 *    the fallback a predecessor publishes when THIS record's
 *    write channel is failing). An unparseable or
 *    schema-invalid lifecycle record is never overwritten or
 *    cleared — the successor waits fail-safe.
 * 3. The OFFER JOURNAL ([readCaptureHealthJournal] /
 *    [writeCaptureHealthJournal]): two counters — incident
 *    RECORD operations OFFERED vs offers known-DROPPED in
 *    process (recover operations are never journaled; audit
 *    7237720579, A06(b)). Writes run on the coordinator's
 *    dedicated journal thread as read-modify-writes under a
 *    dedicated journal lock (never the document lock, so a
 *    writer stuck in a document commit cannot stall the
 *    journal) that add only the writing instance's
 *    unsynced delta, so overlapping instances sum exactly;
 *    offering callers wait for a sync at most
 *    `JOURNAL_WRITE_BOUND_MS`, never for an unbounded
 *    commit() (audit 7237720579, A06(a)). At the next start,
 *    journal-minus-applied is the exact count of incident
 *    records that were offered but never durably applied
 *    before the process died; the coordinator exposes that
 *    gap as an UNKNOWN_LOSS incident instead of losing it
 *    silently (audit 7235948077, A06). The journal is a
 *    single-key commit() to a dedicated minimal store; it is
 *    advanced only on incident-offer paths (failures and
 *    drops — never on the per-capture hot path), and it is
 *    the price of making the pre-durable window DETECTABLE.
 *    The incidents in that window are still lost; what is
 *    not lost any more is the KNOWLEDGE that they were lost,
 *    and how many.
 *
 * DURABLE ACCEPTANCE BOUNDARY (the audit's terms): a health
 * mutation is ACCEPTED (durable) only when [writeCaptureHealth]
 * has RETURNED normally. Production therefore writes with
 * `SharedPreferences.commit()` on the dedicated health-writer
 * thread — the previous `apply()` was an in-memory update whose
 * disk flush a SIGKILL could beat, i.e. not an acknowledgement.
 * Callers never write the health document on a caller/ingress
 * thread: incidents are offered to a bounded queue and the
 * health writer performs the durable document writes (the
 * journal marker above is the one deliberate exception, and it
 * is a minimal counter, not the document). [readCaptureHealth]
 * and [writeCaptureHealth] signal failure by THROWING; the
 * coordinator counts and surfaces every failure (never a silent
 * reset, never a blind overwrite of an unreadable document).
 *
 * PROCESS-SCOPED SERIALISATION: every read-modify-write of the
 * document runs inside [withCaptureHealthLock]. The default is a
 * JVM-wide lock (one app process); production overrides it with
 * a file lock so predecessor/successor processes serialise too.
 * A per-coordinator lock cannot do this job (the audit's point:
 * predecessor and successor are different instances, possibly
 * different processes).
 */
interface CaptureHealthStore {
    /** The stored document, or null when none was ever written. Throws on read failure. */
    fun readCaptureHealth(): String?

    /**
     * Durably persists [json]: returns only after the value is
     * committed to durable storage; throws on failure (a false
     * commit result is a throw, not a silent no-op).
     */
    fun writeCaptureHealth(json: String)

    /**
     * The stored lifecycle record (the authoritative successor
     * fence), or null when none was ever written. Throws on
     * read failure. Kept separate from the health document
     * (audit A03): fence evaluation must survive health-document
     * corruption untouched.
     */
    fun readCaptureLifecycle(): String?

    /** Durably persists the lifecycle record; throws on failure (see [writeCaptureHealth]). */
    fun writeCaptureLifecycle(json: String)

    /**
     * The stored offer journal (`{"offered":N,"dropped":N}`),
     * or null when none was ever written. Throws on read
     * failure.
     */
    fun readCaptureHealthJournal(): String?

    /**
     * Durably persists the offer journal; returns only after
     * the value is committed (production: commit()); throws on
     * failure.
     */
    fun writeCaptureHealthJournal(json: String)

    /** Identity of the process that owns this store handle. */
    fun processIdentity(): CaptureProcessIdentity =
        CaptureProcessIdentitySource.current()

    /**
     * Tri-state liveness of [identity]'s process (audit
     * 7237720579, A03 residual). Only
     * [ProcessLiveness.DEAD] — positively established absence
     * or PID reuse — may clear a fence; ALIVE and UNKNOWN both
     * retain it.
     */
    fun processLiveness(identity: CaptureProcessIdentity): ProcessLiveness =
        CaptureProcessIdentitySource.liveness(identity)

    /** Serialises health-document read-modify-writes across the whole process. */
    fun <T> withCaptureHealthLock(block: () -> T): T =
        CaptureHealthLock.withLock(block)
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
     * store (incidents, the successor-fence lifecycle record,
     * and the offer journal — three separate durable values
     * that survive restart). Null disables persistence (bare
     * coordinators in older tests); production always wires it.
     */
    private val healthStore: CaptureHealthStore? = null,
    /**
     * Supplies the durable key for KEYED observation
     * identities (audit 7237720579, A05/A06). Production wires
     * the SecretVault-held key (see PaylistApplication); the
     * key is loaded once and the provider is cheap to call.
     * Null (bare coordinators in older tests) falls back to
     * the unkeyed derivation — safe there because incident
     * recording, the only persistence of identities, is
     * disabled without a health store.
     */
    private val observationKeyProvider: () -> ByteArray? = { null },
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

        /** Bounded queue of pending persistent-health incidents (health-writer thread). */
        const val INCIDENT_QUEUE_CAPACITY: Int = 100

        /** Bounded wait for the health writer to drain during [close]. */
        const val HEALTH_FLUSH_TIMEOUT_MS: Long = 2_000

        /** Bounded join of the health writer at the end of [close]. */
        const val HEALTH_WRITER_JOIN_TIMEOUT_MS: Long = 1_000

        /** Successor-fence evaluation poll interval. */
        const val FENCE_POLL_MS: Long = 25

        /** Lifecycle-channel attempts before a fence publish falls back to the health document. */
        const val FENCE_PUBLISH_ATTEMPTS: Int = 3

        /**
         * The declared bound on how long an incident-offering
         * caller waits for an offer-journal sync (audit
         * 7237720579, A06): the sync itself runs on the
         * dedicated journal thread and may land later; the
         * CALLER never waits longer than this per sync, and an
         * offer performs at most two such waits (offered +
         * dropped accounting).
         */
        const val JOURNAL_WRITE_BOUND_MS: Long = 250

        /**
         * The TOTAL journal-wait budget of one contiguous
         * burst of offers on one calling thread (audit
         * 7239229553, A06; unit corrected by audit 7242084700):
         * the per-sync bound above capped each individual
         * wait, but one listener callback can offer MANY
         * incident records (a burst of saturation drops), and
         * each offer waited again — so callback latency grew
         * per record. The 7239229553 fix shared one window per
         * SYNC EPISODE, but a callback spanning several
         * episodes (each sync settling before the next offer)
         * still waited once per episode, and the window was
         * measured on the WALL clock, which a clock rollback
         * could stretch. Now the window belongs to the burst
         * (see [JournalBudgetWindow]): every offer in the
         * burst waits only inside ONE window of this size,
         * measured on the MONOTONIC clock, however many sync
         * episodes the burst spans; once it is spent, further
         * offers in the burst do not wait at all (the sync
         * lands late on the journal thread and the lag is
         * counted). Total synchronous journal waiting per
         * burst is therefore bounded by this value.
         */
        const val JOURNAL_CALLBACK_BUDGET_MS: Long = 500

        /**
         * Quiet gap that ends a calling thread's offer burst
         * (audit 7242084700, A06): an offer arriving at least
         * this long after the same thread's previous offer
         * opens a fresh budget window. Sized equal to the
         * budget itself — a callback's sequential offers are
         * separated by milliseconds at most, while distinct
         * callbacks are separated by real notification
         * arrivals; a full budget-length silence is the
         * conservative boundary between the two.
         */
        const val JOURNAL_BURST_QUIET_MS: Long = 500

        /** Cap on the quarantined raw health document kept as evidence. */
        const val MAX_QUARANTINED_RAW_CHARS: Int = 4_000

        /** The legacy `dedupe_key` formula (v2.3, C3 — unchanged). */
        fun legacyDedupeKey(pkg: String, postTimeMs: Long, amount: Long, combined: String): String =
            "$pkg|$postTimeMs|$amount|${combined.take(40).hashCode()}"

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        /** HMAC-SHA-256 of [input] under [key], lowercase hex. */
        fun hmacSha256Hex(key: ByteArray, input: String): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
            val out = mac.doFinal(input.toByteArray(Charsets.UTF_8))
            return out.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        /**
         * The UNKEYED observation identity (legacy form): `obs:`
         * + SHA-256 over package + post time + the SHA-256 of
         * the full combined text. Kept for bare coordinators
         * (no durable key wired) whose identities are never
         * persisted — incident recording is disabled without a
         * health store. Production NEVER uses this form: the
         * persisted derivation is the KEYED overload below
         * (audit 7237720579, A05/A06 — an unkeyed deterministic
         * hash of low-entropy text, e.g. an OTP-only
         * notification, is offline-guessable from the known
         * package + post time; the keyed one is not verifiable
         * against any guess without the key).
         */
        fun observationIdentity(
            packageName: String,
            postTimeMs: Long,
            contentSha256: String,
        ): String = "obs:" + sha256Hex("$packageName|$postTimeMs|$contentSha256")

        /**
         * The KEYED observation identity (audit 7237720579,
         * A05/A06): `obs:` + HMAC-SHA-256, under [key], over the
         * same components as the unkeyed form. It discriminates
         * exactly as strongly (distinct notifications sharing a
         * package and a millisecond differ; the same
         * notification re-observed by a rescan is identical),
         * but the persisted value cannot be verified against a
         * guessed text without the key — so a pre-consent /
         * pure-auth loss leaves no offline-guessable fingerprint
         * of the raw text. The key is held in the SecretVault
         * (see PaylistApplication) and is never persisted
         * alongside the identities it derives.
         */
        fun observationIdentity(
            packageName: String,
            postTimeMs: Long,
            contentSha256: String,
            key: ByteArray,
        ): String = "obs:" + hmacSha256Hex(
            key,
            "$packageName|$postTimeMs|$contentSha256",
        )

        /** The observation identity of a live snapshot (see the three-arg form). */
        fun observationIdentity(snapshot: NotificationSnapshot): String =
            observationIdentity(
                snapshot.packageName,
                snapshot.postTimeMs,
                sha256Hex(snapshot.combined),
            )
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
    private val healthIncidentsDropped = AtomicLong()
    private val healthStoreFailures = AtomicLong()
    private val healthJournalTimeouts = AtomicLong()
    private val healthDocCorruptions = AtomicLong()

    /** A stored health document failed to parse at least once (see [degradedState]). */
    @Volatile
    private var healthDocCorruptSeen: Boolean = false

    /** A fence evaluation found the health store unreadable (fail-safe wait in progress). */
    @Volatile
    private var healthStoreUnreadable: Boolean = false

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

    /**
     * This instance's observation identity for one snapshot:
     * KEYED (HMAC) when a key provider yields a key, unkeyed
     * only for bare coordinators (see the constructor param).
     * Every identity this coordinator persists goes through
     * here — never through the unkeyed companion form directly.
     */
    private fun obsIdentity(snapshot: NotificationSnapshot): String {
        val key = observationKeyProvider()
        return if (key != null) {
            observationIdentity(
                snapshot.packageName,
                snapshot.postTimeMs,
                sha256Hex(snapshot.combined),
                key,
            )
        } else {
            observationIdentity(snapshot)
        }
    }

    /** [obsIdentity] for a snapshot reconstructed from stored event fields (the sweep path). */
    private fun obsIdentity(
        packageName: String,
        postTimeMs: Long,
        contentSha256: String,
    ): String {
        val key = observationKeyProvider()
        return if (key != null) {
            observationIdentity(packageName, postTimeMs, contentSha256, key)
        } else {
            observationIdentity(packageName, postTimeMs, contentSha256)
        }
    }

    // --- Offer journal state (audit 7235948077, A06) ---
    //
    // Declared here, before every pipeline thread, for the same
    // reason as [healthLock]: threads that start during
    // construction can offer health operations, and an offer
    // touches this state. [journalSnapshotAtInit] is the durable
    // journal as THIS instance first saw it — the baseline the
    // health writer reconciles against before applying anything
    // (operations offered by this instance raise the live
    // counters past the snapshot and are never part of the
    // inherited gap). Null when there is no health store or the
    // journal could not be read at construction: reconciliation
    // is then skipped for this instance (a later instance
    // retries), never guessed.

    /** [offered, dropped] totals read from the durable journal at construction, or null. */
    private val journalSnapshotAtInit: LongArray? = readJournalSnapshot()

    /** Total incident RECORD operations offered, across all processes (seeded from the journal). */
    private val healthOfferCount = AtomicLong(journalSnapshotAtInit?.get(0) ?: 0L)

    /** Total offered RECORD operations known-dropped in process, across all processes (seeded from the journal). */
    private val healthDroppedCount = AtomicLong(journalSnapshotAtInit?.get(1) ?: 0L)

    private fun readJournalSnapshot(): LongArray? {
        val store = healthStore ?: return null
        return try {
            val raw = store.readCaptureHealthJournal()
            if (raw.isNullOrBlank()) {
                longArrayOf(0L, 0L)
            } else {
                val json = JSONObject(raw)
                longArrayOf(
                    json.optLong("offered", 0L),
                    json.optLong("dropped", 0L),
                )
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * One journal owner's attributed totals (audit 7239229553,
     * A06): the offers/drops ONE coordinator instance added to
     * the journal, stamped with the process identity that owned
     * them. Attribution is what lets reconciliation distinguish
     * a LIVE predecessor's pending offers (defer — it may still
     * apply them itself) from a terminated one's losses (claim
     * exactly once) — the global totals of the previous design
     * could not tell the two apart and could report a live
     * predecessor's pending records as UNKNOWN_LOSS.
     */
    private data class JournalInstance(
        val offered: Long,
        val dropped: Long,
        val pid: Int,
        val start: Long,
    )

    /**
     * The per-instance journal entries as THIS instance first
     * saw them (empty for a journal written before attribution
     * existed — that case reconciles by the legacy global rule,
     * see [reconcilePreDurableLoss]).
     */
    private val journalInstancesAtInit: Map<String, JournalInstance> =
        readJournalInstances()

    private fun readJournalInstances(): Map<String, JournalInstance> {
        val store = healthStore ?: return emptyMap()
        return try {
            val raw = store.readCaptureHealthJournal()
            if (raw.isNullOrBlank()) return emptyMap()
            val instances = JSONObject(raw).optJSONObject("instances")
                ?: return emptyMap()
            val out = HashMap<String, JournalInstance>()
            val keys = instances.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = instances.optJSONObject(key) ?: continue
                out[key] = JournalInstance(
                    offered = entry.optLong("offered", 0L),
                    dropped = entry.optLong("dropped", 0L),
                    pid = entry.optLong("pid", -1L).toInt(),
                    start = entry.optLong("start", -1L),
                )
            }
            out
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    // --- Pipeline-thread exit handshake + live registration
    // (audits 7239229553, 7242084700) ---
    //
    // Declared here, before every pipeline thread, for the
    // same init-order reason as the journal state above: the
    // exit handlers run ON the pipeline threads and touch this
    // state the moment a thread dies, whenever that is. The
    // exit facts themselves live in [CaptureOwnershipRegistry]
    // (see [CaptureOwnershipRegistry.noteThreadExit]): the
    // previous per-instance AtomicBoolean pair was sampled by
    // the exit handlers OUTSIDE the registry lock, so both
    // handlers could finish between a publish's liveness
    // sample and its claim insertion and strand a permanent
    // foreign claim (audit 7242084700, A03). The registry's
    // locked handshake is now the only exit/claim state.

    /**
     * Registers this instance as LIVE in this process (audit
     * 7239229553, A06): from this moment a successor's startup
     * reconciliation defers this instance's unapplied offers
     * instead of reporting them as a dead process's loss. The
     * registration is released by the health writer's exit
     * (see [healthWriterLoop]) — the point after which this
     * instance can never apply another queued operation.
     */
    private val liveInstanceRegistration: Unit =
        CaptureLiveInstances.register(instanceId)

    // --- Bounded, cross-instance-exact journal sync (audit
    // 7237720579, A06) ---
    //
    // The previous design wrote the journal SYNCHRONOUSLY on
    // the offering thread, under the ingress state lock, with
    // ABSOLUTE per-instance counter values. Three defects,
    // per the audit: (a) a slow commit() blocked the caller —
    // the listener thread on the overflow path — precisely
    // during overload, and serialised every other offer behind
    // it; (b) absolute writes from two overlapping instances
    // (a predecessor still draining while its successor starts)
    // could overwrite each other's totals, so counts across
    // instances were not exact; (c) recover operations were
    // journaled as "offered", inflating the gap with
    // maintenance traffic.
    //
    // Now: ONE dedicated journal thread owns every journal
    // write. A caller that needs the journal synced submits (or
    // joins) the in-flight sync and waits at most
    // [JOURNAL_WRITE_BOUND_MS] for it; a slower store makes the
    // sync land LATE (the thread keeps writing until the
    // counters are durable), never blocks the caller longer.
    // The sync itself is a read-modify-write under a
    // DEDICATED JOURNAL LOCK that adds only THIS instance's
    // not-yet-synced DELTA
    // to the durable totals — so overlapping instances' offers
    // sum exactly, each delta applied once, whatever the
    // interleaving. Only incident RECORD operations are
    // journaled; recoveries are maintenance and never enter
    // the offered/dropped accounting (the gap therefore counts
    // lost health-incident records — never payments, never
    // captures; see [reconcilePreDurableLoss]).

    /** Guards [journalFuture] and the synced baselines below. */
    private val journalLock = ReentrantLock()

    /** The in-flight journal sync on [journalExecutor], or null when the journal is synced. */
    private var journalFuture: java.util.concurrent.Future<*>? = null

    /** This instance's offered total already added to the durable journal. */
    private var journalSyncedOffered: Long = journalSnapshotAtInit?.get(0) ?: 0L

    /** This instance's dropped total already added to the durable journal. */
    private var journalSyncedDropped: Long = journalSnapshotAtInit?.get(1) ?: 0L

    /** True while a journal sync is known to be lagging behind the counters (slow storage). */
    @Volatile
    private var journalLagging: Boolean = false

    /**
     * The calling thread's current journal-wait budget window
     * (audit 7242084700, A06 — replaces the wall-clock,
     * per-sync-episode deadline of audit 7239229553). THE
     * ACCOUNTING UNIT, stated honestly: the coordinator
     * cannot see listener-callback boundaries, so the budget
     * unit is ONE CONTIGUOUS BURST OF OFFERS ON ONE CALLING
     * THREAD — the closest boundary the coordinator can
     * actually observe, since one listener callback offers
     * its records sequentially on one thread with no quiet
     * gap. A burst opens at the thread's first offer and ends
     * after [JOURNAL_BURST_QUIET_MS] of quiet on that thread;
     * sync episodes that start and settle INSIDE the burst do
     * NOT reopen the budget (the previous per-episode window
     * let a multi-episode callback wait the budget once per
     * episode). All deadline arithmetic uses the MONOTONIC
     * clock ([monotonicNowNanos]): a wall-clock rollback or
     * jump can neither extend nor fabricate budget. Total
     * synchronous journal waiting inside one burst is at most
     * [JOURNAL_CALLBACK_BUDGET_MS]; the synchronous lag
     * logging that used to sit outside the wait is no longer
     * on the caller at all (see [logOfferPathAsync]).
     */
    private class JournalBudgetWindow(
        val deadlineNanos: Long,
        var lastActivityNanos: Long,
    )

    /** Per-calling-thread burst window (see [JournalBudgetWindow]). */
    private val journalBudgetWindow = ThreadLocal<JournalBudgetWindow?>()

    /**
     * The monotonic time source for every journal-budget
     * measurement. A property (not a direct
     * [System.nanoTime] call) so a regression test can drive
     * the deadline deterministically; production always uses
     * the real monotonic clock.
     */
    internal var monotonicNowNanos: () -> Long = { System.nanoTime() }

    /** Owns every journal write; created lazily, threads start on first submit. */
    private val journalExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor(
            daemonFactory("paylist-health-journal"),
        )

    /**
     * Owns the offer-path log lines that used to be emitted
     * synchronously inside the journal-wait budget's scope
     * (audit 7242084700, A06): the production log sink
     * persists synchronously and can be slow, so a lag or
     * drop notification emitted on the offering thread was
     * synchronous work OUTSIDE the wait budget — a slow
     * logger could extend a callback past the budget the
     * wait alone respected. Those lines now hand off to this
     * daemon thread; the caller pays one queue offer. The
     * events themselves are always counted synchronously
     * before the hand-off, so a lost log line never loses
     * the accounting.
     */
    private val lagLogExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor(
            daemonFactory("paylist-journal-laglog"),
        )

    /** Emits an offer-path log line off the offering thread (see [lagLogExecutor]). */
    private fun logOfferPathAsync(text: String) {
        try {
            lagLogExecutor.execute { logSafely(text) }
        } catch (t: Throwable) {
            // Executor gone (never shut down in production;
            // a test may race it): the lag was already
            // counted by the caller — the line is best-effort.
        }
    }

    /**
     * Adds this instance's unsynced counter delta to the
     * durable journal (ONE journal-locked read-modify-write;
     * runs on the journal thread). Returns false when the
     * store failed — the delta stays unsynced and a later
     * offer retries; the failure is counted + logged here so
     * it is never silent.
     */
    private fun syncJournalOnce(): Boolean {
        val store = healthStore ?: return true
        val offeredNow = healthOfferCount.get()
        val droppedNow = healthDroppedCount.get()
        val deltaOffered = offeredNow - journalSyncedOffered
        val deltaDropped = droppedNow - journalSyncedDropped
        if (deltaOffered < 0 || deltaDropped < 0) {
            // Counters never move backwards within an instance;
            // a negative delta means the baselines are wrong —
            // refuse to write a regressed journal.
            healthStoreFailures.incrementAndGet()
            logSafely(
                "capture-health: delta jurnal negatif (ditawari=$deltaOffered, " +
                    "hilang=$deltaDropped) — sinkronisasi jurnal dibatalkan",
            )
            return false
        }
        if (deltaOffered == 0L && deltaDropped == 0L) return true
        return try {
            CaptureJournalLock.withLock {
                val raw = store.readCaptureHealthJournal()
                val json = if (raw.isNullOrBlank()) {
                    JSONObject()
                } else {
                    JSONObject(raw)
                }
                val newOffered = json.optLong("offered", 0L) + deltaOffered
                val newDropped = json.optLong("dropped", 0L) + deltaDropped
                json.put("offered", newOffered)
                json.put("dropped", newDropped)
                // Per-instance attribution (audit 7239229553,
                // A06): this instance's delta also lands under
                // its OWN id, stamped with the owning process
                // identity — the input startup reconciliation
                // needs to tell a live predecessor's pending
                // offers from a terminated one's losses. The
                // global totals above are kept unchanged for
                // every existing reader.
                val instances = json.optJSONObject("instances")
                    ?: JSONObject().also { json.put("instances", it) }
                val mine = instances.optJSONObject(instanceId)
                    ?: JSONObject().also { instances.put(instanceId, it) }
                mine.put(
                    "offered",
                    mine.optLong("offered", 0L) + deltaOffered,
                )
                mine.put(
                    "dropped",
                    mine.optLong("dropped", 0L) + deltaDropped,
                )
                val identity = store.processIdentity()
                mine.put("pid", identity.pid)
                mine.put("start", identity.startTicks)
                store.writeCaptureHealthJournal(json.toString())
                journalSyncedOffered = offeredNow
                journalSyncedDropped = droppedNow
            }
            true
        } catch (t: Throwable) {
            healthStoreFailures.incrementAndGet()
            logSafely(
                "capture-health: sinkronisasi jurnal penawaran gagal " +
                    "(${t.javaClass.simpleName}) — delta belum tersinkron, " +
                    "penawaran berikutnya mencoba lagi; deteksi kehilangan " +
                    "pra-durable menurun selama kegagalan ini",
            )
            false
        }
    }

    /**
     * The journal thread's drain loop: syncs until the durable
     * journal covers the live counters, then releases the
     * in-flight handle. A store failure ends the drain (the
     * next offer resubmits); a slow store simply makes the
     * loop's one write take long — callers never wait for more
     * than the bounded wait in [writeJournalBounded].
     */
    private fun drainJournalWrites() {
        while (true) {
            val offeredAtSync = healthOfferCount.get()
            val droppedAtSync = healthDroppedCount.get()
            if (!syncJournalOnce()) {
                journalLock.withLock {
                    journalFuture = null
                    journalLagging = true
                }
                return
            }
            val settled = journalLock.withLock {
                if (healthOfferCount.get() == offeredAtSync &&
                    healthDroppedCount.get() == droppedAtSync
                ) {
                    journalFuture = null
                    journalLagging = false
                    true
                } else {
                    false
                }
            }
            if (settled) return
        }
    }

    /**
     * Ensures a journal sync is in flight and waits for it —
     * bounded TWICE (audit 7237720579, A06; burst bound by
     * audits 7239229553 + 7242084700): at most
     * [JOURNAL_WRITE_BOUND_MS] for this one wait, and at most
     * [JOURNAL_CALLBACK_BUDGET_MS] of total waiting across ALL
     * offers in this calling thread's current burst (see
     * [JournalBudgetWindow] for the accounting unit and why).
     * The burst bound is the one a multi-record listener
     * callback feels: a burst of saturation drops offers many
     * records in one callback, and under the per-wait bound
     * alone each of them waited again, so the callback's
     * latency grew per record. All budget arithmetic is
     * MONOTONIC ([monotonicNowNanos]): a wall-clock rollback
     * or jump can neither stretch nor fabricate the window —
     * the previous wall-clock, per-sync-episode window could
     * be exceeded by exactly those two (a rollback stretched
     * it; several episodes inside one callback each reopened
     * it). Once the burst window is spent, offers return
     * without waiting (the sync lands late on the journal
     * thread; the lag is counted in
     * [CaptureCounters.healthJournalTimeouts] and exposed in
     * [degradedState]). The lag LOG LINES are emitted off the
     * caller ([logOfferPathAsync]): a slow synchronous
     * logger on this path was synchronous work outside the
     * wait budget (audit 7242084700).
     *
     * THE RESIDUAL WINDOW, stated for owner review (audit
     * 7239229553; STILL PENDING owner review — the auditor
     * explicitly did not approve it, audit 7242084700): an
     * offer is undetectable at the next start only if the
     * process dies after the offer but before EITHER durable
     * channel lands — the journal sync started here, or the
     * health writer's application of the queued operation
     * (which stamps the document's applied counts in the same
     * write as the incident itself). Both channels start at
     * offer time and each is a single minimal commit(); on a
     * healthy store the window is zero (the sync completes
     * inside the wait, exactly as with the old synchronous
     * write). Under failing storage the lagging state is
     * surfaced in-process, but a kill inside that state can
     * still lose the KNOWLEDGE of an offer — the tradeoff this
     * design accepts: bounded, nonblocking ingress with a
     * detectable-in-the-next-start gap, over an unbounded
     * synchronous commit on the listener thread. No zero-loss
     * claim is made for that corner, and this round does not
     * change the tradeoff's construction.
     */
    private fun writeJournalBounded() {
        if (healthStore == null) return
        // Open or join this thread's burst window (monotonic).
        val nowNanos = monotonicNowNanos()
        var window = journalBudgetWindow.get()
        if (window == null ||
            nowNanos - window.lastActivityNanos >
            TimeUnit.MILLISECONDS.toNanos(JOURNAL_BURST_QUIET_MS)
        ) {
            window = JournalBudgetWindow(
                deadlineNanos = nowNanos +
                    TimeUnit.MILLISECONDS.toNanos(JOURNAL_CALLBACK_BUDGET_MS),
                lastActivityNanos = nowNanos,
            )
            journalBudgetWindow.set(window)
        }
        window.lastActivityNanos = nowNanos
        try {
            val future = journalLock.withLock {
                journalFuture ?: try {
                    journalExecutor.submit { drainJournalWrites() }
                        .also { journalFuture = it }
                } catch (t: Throwable) {
                    // Executor shut down (post-close offers):
                    // the delta stays unsynced; counted as a
                    // lag, never a crash on a caller thread.
                    journalLagging = true
                    healthJournalTimeouts.incrementAndGet()
                    logOfferPathAsync(
                        "capture-health: thread jurnal sudah berhenti; " +
                            "penawaran sesudah close tidak terjurnal " +
                            "(tercatat sebagai lag jurnal)",
                    )
                    null
                }
            } ?: return
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(
                window.deadlineNanos - monotonicNowNanos(),
            )
            if (remainingMs <= 0L) {
                // The burst's budget is spent: do not wait.
                // The sync is still in flight and lands late;
                // this offer's lag is counted exactly like a
                // timed-out wait.
                journalLagging = true
                healthJournalTimeouts.incrementAndGet()
                logOfferPathAsync(
                    "capture-health: anggaran tunggu jurnal per burst " +
                        "(${JOURNAL_CALLBACK_BUDGET_MS}ms monotonik) habis — " +
                        "penawaran lanjut tanpa menunggu; jurnal mengejar " +
                        "di thread jurnal dan lag ini tercatat",
                )
                return
            }
            try {
                future.get(
                    minOf(JOURNAL_WRITE_BOUND_MS, remainingMs),
                    TimeUnit.MILLISECONDS,
                )
            } catch (e: java.util.concurrent.TimeoutException) {
                journalLagging = true
                healthJournalTimeouts.incrementAndGet()
                logOfferPathAsync(
                    "capture-health: penulisan jurnal melampaui batas " +
                        "${JOURNAL_WRITE_BOUND_MS}ms (penyimpanan lambat) — " +
                        "penawaran lanjut tanpa menunggu; jurnal mengejar " +
                        "di thread jurnal dan lag ini tercatat",
                )
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                // The drain guards its own store access;
                // anything escaping it is counted there already.
            }
        } finally {
            // The burst's quiet gap is measured from the last
            // offer ACTIVITY, not only its arrival: an offer
            // that spent its wait inside the window was never
            // quiet, so a following offer arriving right after
            // it returns still belongs to the same burst.
            window.lastActivityNanos = monotonicNowNanos()
        }
    }

    // --- Health ingress state (audit 7235948077) ---
    //
    // Declared HERE, before every pipeline thread, for the same
    // reason as [healthLock] and the journal state above: the
    // capture worker and the overflow writer start during
    // construction, and their fence path can offer an incident
    // (clearStaleFence records FENCE_ORPHAN_CLEARED;
    // awaitSuccessorFence records LIFECYCLE_CORRUPT) the moment
    // they start. When this state was declared down by the
    // health-writer section — after both thread starts — a fast
    // worker reached enqueueHealthOp with the lock and queue
    // still null and died on an NPE before processing anything
    // (intermittent lost captures on exactly the stale-fence
    // path; proven by the thread dump + system-err of the
    // failing staleFence test, 28 Nov).

    /** Bounded queue of pending health operations (see [enqueueHealthOp]). */
    private val incidentQueue = LinkedBlockingQueue<HealthOp>(INCIDENT_QUEUE_CAPACITY)

    /** Guards the health-writer termination handshake (see the overflow writer's). */
    private val healthStateLock = ReentrantLock()

    /** Guarded by [healthStateLock]: the health writer has exited for good. */
    private var healthWriterTerminated: Boolean = false

    /** Set by [close]: the health writer exits once its queue is drained. */
    @Volatile
    private var healthWriterShutdown: Boolean = false

    /** True while the health writer is applying a batch (read by [flushHealthIncidents]). */
    @Volatile
    private var healthWriterBusy: Boolean = false

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
        // predecessor coordinator recorded a fence in the
        // lifecycle record (its threads were still alive when
        // its bounded close gave up), this worker must not
        // process anything until the predecessor's threads have
        // actually exited (their exit clears their half of the
        // fence).
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
    // --- Persistent capture health (PR #5 re-review 28 Nov;
    // durability + recovery-proof contract tightened by the
    // PR #5 audit of 28 Nov, comment 7229800304) ---
    //
    // The volatile counters above die with the process. The
    // reviewer required the degraded state to be an incident /
    // recovery record that PERSISTS, is operator-visible, and
    // survives restart — so every terminal capture failure also
    // lands in the health document behind [healthStore]:
    // incidents aggregate by (category, status, identity),
    // distinguish OPEN (still degrading) from RECOVERED (proof
    // at the same granularity: component health for component
    // incidents, the exact observation identity for loss
    // incidents) from UNRECOVERABLE (an outcome that can never
    // be retroactively confirmed).
    //
    // The successor fence does NOT live in this document any
    // more (audit 7235948077, A03): it is a separate durable
    // value in the same store (the lifecycle record), so health
    // -document corruption or loss can never open the lifecycle
    // exclusion. See the fence section below.
    //
    // Four audit rules govern every access:
    // 1. BOUNDED NONBLOCKING INGRESS: no caller thread (above
    //    all not the listener Binder thread via [recordOverflow])
    //    ever performs health-DOCUMENT I/O. Incidents are
    //    OFFERED to a bounded queue; the dedicated health-writer
    //    thread does the durable document writes. An offer that
    //    cannot be queued is counted
    //    ([CaptureCounters.healthIncidentsDropped]), journaled
    //    as accounted (rule 4), and logged — never silent,
    //    never blocking.
    // 2. NO SILENT RESET, NO BLIND OVERWRITE: a store read that
    //    FAILS aborts the mutation (the unread document is never
    //    clobbered); a stored value that does not PARSE is
    //    quarantined — its raw text is carried into the fresh
    //    document as evidence and a HEALTH_DOC_CORRUPT incident
    //    is recorded — instead of being silently reset.
    // 3. FAILURES ARE STATE: every store failure is counted
    //    ([CaptureCounters.healthStoreFailures]) and surfaced in
    //    [degradedState]; a failing health store never breaks
    //    capture itself, but it is never invisible either.
    // 4. OFFERS ARE JOURNALED BEFORE THEY ARE QUEUED (audit
    //    A06): the offer journal's durable counters are advanced
    //    synchronously at offer time, so a process death in the
    //    pre-durable window leaves an exact, reconcilable gap
    //    (journal offered − journal accounted − document
    //    applied) that the next start exposes as UNKNOWN_LOSS.
    //    The window still loses the incidents themselves; it no
    //    longer loses the fact that they were lost.
    // No notification text is ever written into the document
    // (metadata only: package + post time + component).
    // (healthLock itself is declared next to instanceId, before
    // the thread properties — see its comment. It is the INNER
    // lock; the store's process-wide lock is always taken first.)

    /** A loaded health document; [corruptRaw] is the unparseable stored value, if any. */
    private class LoadedHealthDoc(val doc: JSONObject, val corruptRaw: String?)

    private fun emptyHealthDoc(): JSONObject = JSONObject().apply {
        put("version", 3)
        put("incidents", JSONArray())
    }

    /**
     * Loads the health document (caller holds the store lock).
     * Returns null ONLY when the store read itself failed — the
     * caller must then abort any write (fail-safe: never clobber
     * a document that could not be read). An unparseable stored
     * value is quarantined into the fresh document (see the
     * section comment), never silently dropped.
     */
    private fun loadHealthDocLocked(): LoadedHealthDoc? {
        val raw = try {
            healthStore?.readCaptureHealth()
        } catch (t: Throwable) {
            healthStoreFailures.incrementAndGet()
            logSafely(
                "capture-health: pembacaan dokumen gagal " +
                    "(${t.javaClass.simpleName}) — mutasi dibatalkan agar " +
                    "dokumen yang tidak terbaca tidak tertimpa buta",
            )
            return null
        }
        if (raw.isNullOrBlank()) return LoadedHealthDoc(emptyHealthDoc(), null)
        return try {
            LoadedHealthDoc(JSONObject(raw), null)
        } catch (e: Exception) {
            healthDocCorruptions.incrementAndGet()
            healthDocCorruptSeen = true
            val doc = emptyHealthDoc()
            doc.put(
                "quarantine",
                JSONObject().apply {
                    put("raw", raw.take(MAX_QUARANTINED_RAW_CHARS))
                    put("truncated", raw.length > MAX_QUARANTINED_RAW_CHARS)
                    put("at_ms", clock())
                    put(
                        "note",
                        "dokumen kesehatan sebelumnya tidak dapat di-parse; " +
                            "nilai mentahnya dipertahankan di sini sebagai bukti, " +
                            "bukan dibuang diam-diam",
                    )
                },
            )
            LoadedHealthDoc(doc, raw)
        }
    }

    /**
     * One serialised health-document read-modify-write: the
     * store's process-wide lock outside, this instance's
     * [healthLock] inside (the only order ever taken). Returns
     * true when the mutation was durably written (the store's
     * acceptance boundary — see [CaptureHealthStore]). A read
     * failure aborts without writing; a write failure is
     * counted + logged. Either way capture itself never breaks.
     */
    private fun mutateHealth(block: (JSONObject) -> Unit): Boolean {
        val store = healthStore ?: return false
        var corruptRaw: String? = null
        val ok = try {
            store.withCaptureHealthLock {
                healthLock.withLock {
                    val loaded = loadHealthDocLocked()
                        ?: return@withLock false
                    corruptRaw = loaded.corruptRaw
                    block(loaded.doc)
                    loaded.doc.put("updated_ms", clock())
                    store.writeCaptureHealth(loaded.doc.toString())
                    true
                }
            }
        } catch (t: Throwable) {
            healthStoreFailures.incrementAndGet()
            logSafely(
                "capture-health: penulisan dokumen gagal " +
                    "(${t.javaClass.simpleName}: ${t.message?.take(80)}) — " +
                    "status persisten tidak terbarui; kegagalan tercatat, " +
                    "capture tidak terpengaruh",
            )
            false
        }
        if (ok && corruptRaw != null) {
            recordIncident(
                CaptureIncidentCategory.HEALTH_DOC_CORRUPT,
                CaptureIncidentStatus.OPEN,
                "dokumen kesehatan rusak (${corruptRaw!!.length} char) — " +
                    "nilai mentah di-quarantine di dalam dokumen baru, " +
                    "tidak dibuang diam-diam",
            )
        }
        return ok
    }

    /**
     * Read-only load of the health document for diagnostics:
     * the parsed document, or null when the store read failed
     * or the value does not parse. Never writes, never
     * quarantines (that happens on the next mutation); a corrupt
     * value sets the volatile corrupt-seen flag for
     * [degradedState]. (Fence evaluation does NOT read this
     * document — the lifecycle record is its authority, audit
     * A03.)
     */
    private fun readHealthDoc(): JSONObject? {
        val store = healthStore ?: return null
        return try {
            store.withCaptureHealthLock {
                val raw = store.readCaptureHealth()
                if (raw.isNullOrBlank()) {
                    emptyHealthDoc()
                } else {
                    try {
                        JSONObject(raw)
                    } catch (e: Exception) {
                        healthDocCorruptSeen = true
                        null
                    }
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    // --- Health-writer thread: bounded nonblocking ingress ---
    //
    // Every persistent-incident mutation from a pipeline path
    // is an OFFER to this queue (audit rule 1 above); the single
    // health-writer thread applies the queued operations, in
    // order, through [mutateHealth]. Lifecycle mirrors the
    // overflow writer: [close] flushes (bounded) then terminates
    // it; the termination handshake under [healthStateLock]
    // guarantees an offer can never land in a queue whose writer
    // has already decided to exit — such an offer is counted as
    // dropped instead.

    /** One queued persistent-health mutation (applied in queue order). */
    private sealed interface HealthOp {
        /** Record (or aggregate) an incident under [status]. */
        class Record(
            val category: String,
            val status: String,
            val detail: String,
            val identity: String,
        ) : HealthOp

        /**
         * Close OPEN incidents of [category] as RECOVERED.
         * [identity] null = component-level (every OPEN incident
         * of the category); non-null = ONLY the incident whose
         * per-snapshot identity matches — proof about one
         * snapshot never recovers another's loss (audit P1-3).
         */
        class Recover(val category: String, val identity: String?) : HealthOp
    }

    // The incident queue and its lock live with the early state
    // above (before every pipeline thread): a thread started
    // during construction can offer an incident from the fence
    // path before this section would otherwise be initialised.

    private val healthWriter = Thread({ healthWriterLoop() }, "paylist-health-writer").apply {
        isDaemon = true
        start()
    }

    private fun healthWriterLoop() {
        try {
            healthWriterLoopBody()
        } finally {
            // This instance can never apply another queued
            // health operation: from here a successor's startup
            // reconciliation may treat its still-unapplied
            // offers as losses (audit 7239229553, A06). While
            // this writer lived, those offers were deferred —
            // never falsely reported.
            CaptureLiveInstances.unregister(instanceId)
        }
    }

    private fun healthWriterLoopBody() {
        // FIRST action, before any queued operation is applied:
        // reconcile the offer journal (audit A06). Running here
        // — on the only thread that applies queued operations,
        // before its first batch — is what makes the gap exact:
        // the document's applied_count at this moment reflects
        // only PREVIOUS processes, and the journal snapshot was
        // taken at this instance's construction.
        try {
            reconcilePreDurableLoss()
        } catch (t: Throwable) {
            healthStoreFailures.incrementAndGet()
            logSafely(
                "capture-health: rekonsiliasi jurnal gagal " +
                    "(${t.javaClass.simpleName}) — penulis kesehatan tetap jalan",
            )
        }
        while (true) {
            val first = try {
                incidentQueue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (first == null) {
                if (healthWriterShutdown) {
                    val exit = healthStateLock.withLock {
                        if (incidentQueue.isEmpty()) {
                            healthWriterTerminated = true
                            true
                        } else {
                            false
                        }
                    }
                    if (exit) return
                }
                continue
            }
            healthWriterBusy = true
            try {
                val batch = ArrayList<HealthOp>()
                batch.add(first)
                incidentQueue.drainTo(batch)
                applyHealthOps(batch)
            } catch (t: Throwable) {
                // applyHealthOps → mutateHealth already guards
                // its store access; this outer guard keeps the
                // writer alive whatever escapes, and the failure
                // is counted, not silent.
                healthStoreFailures.incrementAndGet()
                logSafely(
                    "capture-health: penulis kesehatan gagal menerapkan batch " +
                        "(${t.javaClass.simpleName}) — loop tetap hidup",
                )
            } finally {
                healthWriterBusy = false
            }
        }
    }

    /**
     * Applies one drained batch in ONE serialised document
     * mutation. The batch's RECORD count is added to the
     * document's `applied_count` in the SAME write, so the
     * count is durable exactly when the batch is — the
     * reconciliation baseline for the offer journal (audit
     * A06). Only RECORD operations count (audit 7237720579):
     * recoveries are maintenance — they were never journaled
     * as offered, so counting them as applied would corrupt
     * the gap in the other direction.
     */
    private fun applyHealthOps(ops: List<HealthOp>) {
        if (ops.isEmpty()) return
        val recordCount = ops.count { it is HealthOp.Record }
        mutateHealth { doc ->
            for (op in ops) {
                when (op) {
                    is HealthOp.Record -> applyRecordIncident(doc, op)
                    is HealthOp.Recover -> applyRecoverIncident(doc, op)
                }
            }
            // A06 accounting: the document counts how many
            // offered RECORD operations it has durably applied.
            // A COUNT, not a highest-sequence stamp: drops are
            // accounted out of order (the moment they happen),
            // so only order-independent totals reconcile
            // exactly at the next start (see
            // [reconcilePreDurableLoss]).
            doc.put(
                "applied_count",
                doc.optLong("applied_count", 0L) + recordCount,
            )
            // Per-instance attribution of the same count (audit
            // 7239229553, A06): reconciliation debits each
            // journal owner only with what THAT owner failed to
            // apply — a successor's own applications must never
            // mask a predecessor's gap, nor a predecessor's
            // applications a successor's.
            if (recordCount > 0) {
                val appliedBy = doc.optJSONObject("applied_by")
                    ?: JSONObject().also { doc.put("applied_by", it) }
                appliedBy.put(
                    instanceId,
                    appliedBy.optLong(instanceId, 0L) + recordCount,
                )
            }
        }
    }

    /** The document half of incident recording (caller holds the store lock). */
    private fun applyRecordIncident(doc: JSONObject, op: HealthOp.Record) {
        applyIncidentCounted(doc, op.category, op.status, op.detail, op.identity, 1L)
    }

    /**
     * Aggregates one incident into the document by (category,
     * status, identity), adding [count] to its tally (caller
     * holds the store lock). [count] is 1 for ordinary incident
     * recording; the startup reconciliation uses it to record an
     * exact multi-incident UNKNOWN_LOSS gap in one entry.
     */
    private fun applyIncidentCounted(
        doc: JSONObject,
        category: String,
        status: String,
        detail: String,
        identity: String,
        count: Long,
    ) {
        val incidents = doc.optJSONArray("incidents") ?: JSONArray()
            .also { doc.put("incidents", it) }
        var found: JSONObject? = null
        for (i in 0 until incidents.length()) {
            val item = incidents.optJSONObject(i) ?: continue
            if (item.optString("category") == category &&
                item.optString("status") == status &&
                item.optString("identity") == identity
            ) {
                found = item
                break
            }
        }
        val now = clock()
        if (found != null) {
            found.put("count", found.optLong("count", 0) + count)
            found.put("last_ms", now)
            found.put("detail", detail)
        } else {
            incidents.put(
                JSONObject().apply {
                    put("category", category)
                    put("status", status)
                    put("first_ms", now)
                    put("last_ms", now)
                    put("count", count)
                    put("detail", detail)
                    put("identity", identity)
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

    /** The document half of incident recovery (caller holds the store lock). */
    private fun applyRecoverIncident(doc: JSONObject, op: HealthOp.Recover) {
        val incidents = doc.optJSONArray("incidents") ?: return
        for (i in 0 until incidents.length()) {
            val item = incidents.optJSONObject(i) ?: continue
            if (item.optString("category") == op.category &&
                item.optString("status") == CaptureIncidentStatus.OPEN &&
                (op.identity == null || item.optString("identity") == op.identity)
            ) {
                item.put("status", CaptureIncidentStatus.RECOVERED)
                item.put("last_ms", clock())
            }
        }
    }

    /**
     * Offers one health operation to the health writer (audit
     * rule 1: bounded, nonblocking, no document I/O on the
     * caller). RECORD operations are journaled (audit A06):
     * the offer counter is raised and a journal sync is awaited
     * — BOUNDED by [JOURNAL_WRITE_BOUND_MS] on the dedicated
     * journal thread, never an unbounded synchronous commit
     * under the ingress lock (audit 7237720579: the old design
     * reintroduced blocking precisely during overload, and its
     * absolute per-instance writes were not exact across
     * overlapping instances). The sync precedes the queue
     * offer, so on a healthy store a process death anywhere
     * after this point leaves a reconcilable gap instead of a
     * silent loss. An offer that cannot be queued — full
     * queue, or the writer already terminated — is counted in
     * [CaptureCounters.healthIncidentsDropped], logged, and
     * journaled as DROPPED (known-lost in process), so the
     * next start does not double-report it as unknown.
     * RECOVER operations are maintenance: they are queued the
     * same way but are NEVER journaled as offered and never
     * counted as applied — the journal gap counts lost
     * incident records only (audit 7237720579, A06(b)).
     */
    private fun enqueueHealthOp(op: HealthOp) {
        if (healthStore == null) return
        val isRecord = op is HealthOp.Record
        if (isRecord) {
            healthOfferCount.incrementAndGet()
            writeJournalBounded()
        }
        var droppedNow = false
        var writerGone = false
        healthStateLock.withLock {
            if (healthWriterTerminated) {
                droppedNow = true
                writerGone = true
            } else if (!incidentQueue.offer(op)) {
                droppedNow = true
            }
            if (droppedNow) {
                if (isRecord) healthDroppedCount.incrementAndGet()
                healthIncidentsDropped.incrementAndGet()
            }
        }
        if (droppedNow) {
            if (isRecord) writeJournalBounded()
            // Async (audit 7242084700, A06): this line is on
            // the offering thread, inside the same latency
            // scope as the journal wait — a slow synchronous
            // logger here would be uncounted synchronous work
            // on the offer path. The drop was already counted
            // above; the line is evidence, not accounting.
            logOfferPathAsync(
                if (writerGone) {
                    "capture-health: penulis kesehatan sudah berhenti; " +
                        "operasi kesehatan tidak terantre (dihitung hilang)"
                } else {
                    "capture-health: antrean insiden penuh; operasi kesehatan " +
                        "dihitung hilang (bukti: counter + log ini)"
                },
            )
        }
    }

    /**
     * Startup reconciliation of the pre-durable window (audit
     * 7235948077, A06; per-instance attribution added by audit
     * 7239229553), run by the health writer before it applies
     * anything: incident RECORD operations a PREVIOUS instance
     * offered (journal `offered`), minus the ones it accounted
     * as dropped in process (journal `dropped`), minus the ones
     * the document durably applied (`applied_count`), are an
     * exact count of health-incident records lost before the
     * durable boundary. WHAT THE GAP COUNTS — stated precisely
     * (audit 7237720579, A06(b)): health-incident RECORD
     * operations, and nothing else. Recover operations are
     * never journaled and never counted as applied, so
     * maintenance traffic cannot inflate or deflate the gap;
     * and the gap is about incident RECORDS (the persistent
     * evidence), never about payments or captures — a lost
     * incident record does not mean a lost payment, and this
     * reconciliation makes no claim about either.
     *
     * PER-INSTANCE RULE (audit 7239229553): the journal now
     * attributes offers to the instance (and process) that made
     * them, and the document attributes applications the same
     * way (`applied_by`). An owner's gap may be CLAIMED —
     * recorded as UNKNOWN_LOSS and marked reconciled — only
     * once that owner's termination is ESTABLISHED
     * ([instanceTerminationEstablished]): a same-process owner
     * must have left the live registry (its health writer
     * terminated), a cross-process owner must be provably DEAD
     * by the tri-state liveness proof. While termination is
     * not established the gap is DEFERRED untouched: the owner
     * may still apply its pending records itself, and counting
     * them as a dead process's loss would be a false
     * UNKNOWN_LOSS (the previous design's global snapshot could
     * do exactly that to a live predecessor). A later start,
     * after the owner has terminated, claims the deferred gap
     * exactly once (`reconciled_by` is the durable claim
     * marker). The gap is recorded as one aggregate
     * UNKNOWN_LOSS incident — WHICH incidents they were is
     * unknowable, and the record says so — and `applied_count`
     * advances by the claimed amount so the global invariant
     * (offered − dropped == applied, once every owner is
     * applied or claimed) still holds. No gap, no incident: a
     * clean close (which flushes the queue) leaves offered −
     * dropped == applied.
     *
     * A journal with NO per-instance entries predates
     * attribution; its totals reconcile by the legacy global
     * rule in [reconcileLegacyJournal] — the only reading the
     * data supports. A journal with BOTH (the first attributed
     * instance synced, then died before a start reconciled
     * the legacy totals) reconciles its attributed entries
     * per-instance above AND its unattributed remainder by
     * the same legacy rule, decomposed against the attributed
     * sums — neither part is dropped or counted twice (audit
     * 7242084700, A06 migration verification).
     */
    private fun reconcilePreDurableLoss() {
        val snapshot = journalSnapshotAtInit ?: return
        if (healthStore == null) return
        if (journalInstancesAtInit.isEmpty()) {
            reconcileLegacyJournal(snapshot)
            return
        }
        mutateHealth { doc ->
            val appliedBy = doc.optJSONObject("applied_by")
                ?: JSONObject().also { doc.put("applied_by", it) }
            val reconciledBy = doc.optJSONObject("reconciled_by")
                ?: JSONObject().also { doc.put("reconciled_by", it) }
            var claimed = 0L
            for ((ownerId, entry) in journalInstancesAtInit) {
                if (ownerId == instanceId) continue
                val accounted = entry.offered - entry.dropped
                val unaccounted = accounted - appliedBy.optLong(ownerId, 0L)
                val already = reconciledBy.optLong(ownerId, 0L)
                if (unaccounted <= already) continue
                if (!instanceTerminationEstablished(ownerId, entry)) {
                    // DEFERRED: the owner may still apply these
                    // records itself. Claiming them now would
                    // report a live predecessor's pending work
                    // as a dead process's loss.
                    continue
                }
                // Claiming only the part not already claimed
                // is also what makes LATE JOURNAL UPDATES
                // exact (audit 7242084700, A06): if the
                // owner's journal entry grows after a previous
                // reconciliation claimed its then-current gap
                // (a lagging sync landing after the successor
                // started), the next start sees
                // unaccounted > already and claims exactly the
                // growth — never the whole gap twice, and the
                // late delta is never lost.
                claimed += unaccounted - already
                reconciledBy.put(ownerId, unaccounted)
            }
            // MIXED-JOURNAL MIGRATION (audit 7242084700, A06):
            // a journal that carries BOTH pre-attribution
            // totals AND per-instance entries — the state left
            // when the first attributed instance synced its
            // entry but died before any start reconciled the
            // legacy totals. The per-instance loop above never
            // sees the unattributed remainder, so without this
            // step those offers would be silently dropped from
            // accounting the moment any instance entry exists.
            // The remainder reconciles by the legacy global
            // rule, decomposed exactly: its offered/dropped are
            // the global totals minus the attributed sums, and
            // its applied share is the document's applied_count
            // minus every attributed owner's applied_by (the
            // two are stamped in the same write, so the
            // decomposition is exact). Claim-once is the same
            // mechanism as the legacy path: claiming advances
            // applied_count, so a later start recomputes a
            // remainder gap of zero.
            val attributedOffered =
                journalInstancesAtInit.values.sumOf { it.offered }
            val attributedDropped =
                journalInstancesAtInit.values.sumOf { it.dropped }
            val legacyAccounted =
                (snapshot[0] - attributedOffered) -
                    (snapshot[1] - attributedDropped)
            if (legacyAccounted > 0) {
                var attributedApplied = 0L
                val appliedKeys = appliedBy.keys()
                while (appliedKeys.hasNext()) {
                    attributedApplied += appliedBy.optLong(appliedKeys.next(), 0L)
                }
                val legacyApplied =
                    doc.optLong("applied_count", 0L) - attributedApplied
                val legacyUnknown = legacyAccounted - legacyApplied
                if (legacyUnknown > 0) {
                    claimed += legacyUnknown
                }
            }
            if (claimed > 0) {
                applyIncidentCounted(
                    doc,
                    CaptureIncidentCategory.UNKNOWN_LOSS,
                    CaptureIncidentStatus.OPEN,
                    "$claimed insiden kesehatan terantre dari instance " +
                        "sebelumnya yang terbukti berhenti tidak pernah " +
                        "mencapai batas durable (isi insidennya tidak " +
                        "diketahui — hanya jumlahnya yang pasti, dari " +
                        "jurnal penawaran per-instance dan sisa " +
                        "pra-atribusinya)",
                    "",
                    claimed,
                )
                doc.put(
                    "applied_count",
                    doc.optLong("applied_count", 0L) + claimed,
                )
                logSafely(
                    "capture-health: $claimed insiden dari instance " +
                        "sebelumnya yang terbukti berhenti hilang sebelum " +
                        "batas durable — tercatat sebagai unknown_loss, " +
                        "bukan hilang diam-diam",
                )
            }
        }
    }

    /**
     * May [ownerId]'s unapplied offers be claimed as losses —
     * i.e. is its termination ESTABLISHED (audit 7239229553,
     * A06)? A same-process owner (its journal entry carries
     * THIS process's identity) is terminated exactly when it
     * has left [CaptureLiveInstances] — its health writer
     * exited, so it can never apply another record. A
     * cross-process owner is terminated only on the tri-state
     * DEAD proof; ALIVE and UNKNOWN both defer. An entry
     * without a usable process identity can never be proven
     * terminated and is deferred forever rather than guessed.
     */
    private fun instanceTerminationEstablished(
        ownerId: String,
        entry: JournalInstance,
    ): Boolean {
        val store = healthStore ?: return false
        if (entry.pid <= 0 || entry.start < 0) return false
        val identity = CaptureProcessIdentity(entry.pid, entry.start)
        return if (identity == store.processIdentity()) {
            !CaptureLiveInstances.isLive(ownerId)
        } else {
            store.processLiveness(identity) == ProcessLiveness.DEAD
        }
    }

    /**
     * The pre-attribution reconciliation (journal totals only):
     * offered − dropped − applied, claimed in one mutation.
     * Kept for journals written before per-instance attribution
     * existed; new journals never take this path.
     */
    private fun reconcileLegacyJournal(snapshot: LongArray) {
        val offeredAtInit = snapshot[0]
        val droppedAtInit = snapshot[1]
        if (offeredAtInit <= 0L) return
        mutateHealth { doc ->
            val applied = doc.optLong("applied_count", 0L)
            val accountedTotal = offeredAtInit - droppedAtInit
            val unknown = accountedTotal - applied
            if (unknown > 0) {
                applyIncidentCounted(
                    doc,
                    CaptureIncidentCategory.UNKNOWN_LOSS,
                    CaptureIncidentStatus.OPEN,
                    "$unknown insiden kesehatan terantre dari proses sebelumnya " +
                        "tidak pernah mencapai batas durable sebelum proses mati " +
                        "(isi insidennya tidak diketahui — hanya jumlahnya yang " +
                        "pasti, dari jurnal penawaran)",
                    "",
                    unknown,
                )
                logSafely(
                    "capture-health: $unknown insiden dari proses sebelumnya " +
                        "hilang sebelum batas durable (jurnal menawarkan " +
                        "$offeredAtInit, terkonfirmasi $applied, terhitung " +
                        "hilang di proses $droppedAtInit) — tercatat sebagai " +
                        "unknown_loss, bukan hilang diam-diam",
                )
            }
            if (accountedTotal > applied) {
                doc.put("applied_count", accountedTotal)
            }
        }
    }

    /**
     * Records (or aggregates) one persistent incident. [status]
     * is the status the incident is recorded under: OPEN for
     * component failures that can still recover, UNRECOVERABLE
     * for outcomes that can never be confirmed after the fact.
     * [identity] keys a loss to ONE snapshot (its observation
     * identity — see [observationIdentity]); empty = aggregate
     * component incident.
     */
    private fun recordIncident(
        category: String,
        status: String,
        detail: String,
        identity: String = "",
    ) {
        enqueueHealthOp(HealthOp.Record(category, status, detail, identity))
    }

    /**
     * Closes OPEN incidents of [category] as RECOVERED. Callers
     * must pass the granularity their proof actually has
     * (audit P1-3): [identity] null ONLY for component-health
     * incidents whose proof is component-level (a landed insert
     * proves the database works); a snapshot identity for
     * loss incidents, where only THAT snapshot's reconciliation
     * is proof.
     */
    private fun recoverIncident(category: String, identity: String?) {
        enqueueHealthOp(HealthOp.Recover(category, identity))
    }

    /**
     * METADATA recovery, explicitly NOT payment recovery (audit
     * 7235948077, A05): a durable overflow row for observation
     * [identity] has landed, so the lost-RECORD incident for
     * that identity closes — the evidence for the dropped
     * snapshot now exists in History. The snapshot itself was
     * still dropped and its payment is still uncaptured: that
     * loss stays represented by the aggregate
     * OVERFLOW_SNAPSHOT_DROPPED incident (which never
     * auto-recovers) and is healed only by an actual rescan
     * capture. Keeping the two apart is what stops a landed
     * metadata row from masquerading as a recovered payment.
     */
    private fun recoverOverflowRecordMetadata(identity: String) {
        recoverIncident(CaptureIncidentCategory.OVERFLOW_RECORD_LOST, identity)
    }

    /**
     * Bounded wait until every queued health operation has been
     * applied (the durable acceptance boundary for incidents:
     * when this returns, the writes have RETURNED from the
     * store). Used by [close] and by the process-death harness,
     * which must know an incident is durable BEFORE the kill.
     */
    internal fun flushHealthIncidents(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (incidentQueue.isEmpty() && !healthWriterBusy) return
            try {
                Thread.sleep(5)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /**
     * Explicit operator disposition (audit P1-3): acknowledges
     * ONE OPEN identity incident as UNRECOVERABLE — the loss /
     * unknown outcome is kept as history and stops holding the
     * degraded state, WITHOUT claiming the record was recovered.
     * This is the only non-proof path out of OPEN for identity
     * losses. Synchronous (an operator action, never a pipeline
     * path): applied directly, not via the health queue.
     */
    fun dismissIncident(category: String, identity: String) {
        mutateHealth { doc ->
            val incidents = doc.optJSONArray("incidents") ?: return@mutateHealth
            for (i in 0 until incidents.length()) {
                val item = incidents.optJSONObject(i) ?: continue
                if (item.optString("category") == category &&
                    item.optString("status") == CaptureIncidentStatus.OPEN &&
                    item.optString("identity") == identity
                ) {
                    item.put("status", CaptureIncidentStatus.UNRECOVERABLE)
                    item.put("last_ms", clock())
                    item.put(
                        "detail",
                        item.optString("detail") +
                            " [disposisi operator: diakui tidak terkonfirmasi]",
                    )
                }
            }
        }
    }

    /** The persistent incidents, as recorded (empty without a health store). */
    private fun persistentIncidents(): List<CaptureIncident> {
        if (healthStore == null) return emptyList()
        val doc = readHealthDoc() ?: return emptyList()
        val incidents = doc.optJSONArray("incidents") ?: return emptyList()
        return (0 until incidents.length()).mapNotNull { i ->
            val item = incidents.optJSONObject(i) ?: return@mapNotNull null
            CaptureIncident(
                category = item.optString("category"),
                status = item.optString("status"),
                firstAtMs = item.optLong("first_ms"),
                lastAtMs = item.optLong("last_ms"),
                count = item.optLong("count"),
                detail = item.optString("detail"),
                identity = item.optString("identity"),
            )
        }
    }

    // --- Successor fence (audit P1-1: process-scoped, fail-safe;
    // separation + no age/corruption clearing: audit 7235948077,
    // A03) ---
    //
    // The fence is the AUTHORITATIVE lifecycle exclusion, and it
    // lives in its OWN durable value — the lifecycle record —
    // not in the health document. The audit's A03 finding was
    // that the in-document fence could be defeated by the
    // document's own failure modes: a legacy fence was cleared
    // by AGE (600 s) although a stuck thread can outlive any
    // bound, and a corrupt document was replaced by a fresh one
    // whose empty fence let a successor in while its predecessor
    // was alive. Both are now impossible by construction:
    //
    // - Fence evaluation reads the lifecycle record. The
    //   health document can be corrupt, quarantined, or lost
    //   without any effect on the exclusion — with ONE bounded
    //   exception (audit 7237720579): an ACTIVE in-document
    //   fence from an owner the lifecycle record has not
    //   already adopted-and-retired is honored, because that
    //   is where a predecessor publishes its fence when the
    //   lifecycle write channel itself is failing (the
    //   fallback channel), and where pre-separation stores
    //   kept it. A document fence from the SAME owner as an
    //   inactive lifecycle record is a retired leftover and is
    //   ignored.
    // - A fence is cleared ONLY by proof: both thread halves
    //   cleared by the threads' own exits (recorded in the same
    //   authoritative record), or the owner PROCESS provably
    //   dead (PID gone, or PID reused with a different start
    //   time). Age is never proof. Corruption is never proof.
    // - Ownership that is UNKNOWN — a legacy fence without a
    //   process identity, a fence adopted conservatively because
    //   the health document was corrupt while the lifecycle
    //   state was still unseparated, or an unparseable lifecycle
    //   record — is NEVER cleared: the successor waits fail-safe
    //   (bounded only by its own close), and the corrupt record
    //   is preserved untouched as evidence.
    // - The fence owner is a PROCESS identity (PID + start
    //   time), not a random coordinator UUID: a fence left
    //   behind by a SIGKILLed process is recognisably STALE and
    //   a fresh process clears it (recording FENCE_ORPHAN_CLEARED)
    //   and proceeds — no indefinite stall for provable deaths,
    //   no bypass for anything else.

    /** A stored lifecycle record failed to parse at least once (see [degradedState]). */
    @Volatile
    private var lifecycleCorruptSeen: Boolean = false

    /**
     * A close-time fence publish failed on BOTH durable
     * channels while predecessor threads were still alive
     * (audit 7237720579): the fence may be invisible to a
     * successor, so the state is degraded until a later publish
     * succeeds.
     */
    @Volatile
    private var fencePublishFailed: Boolean = false

    /** The result of evaluating the fence state. */
    private sealed class LifecycleEval {
        /** No active fence: the way is clear. */
        object Clear : LifecycleEval()

        /** An active fence (from the lifecycle record, or adopted from a legacy document). */
        class Active(val fence: JSONObject) : LifecycleEval()

        /** The lifecycle record exists but does not parse: ownership unknown. */
        object Corrupt : LifecycleEval()

        /** The store could not be read: ownership unverifiable. */
        object Unreadable : LifecycleEval()
    }

    private enum class FenceVerdict {
        CLEAR, WAIT, WAIT_STORE_UNREADABLE, WAIT_LIFECYCLE_CORRUPT, STALE,
    }

    /**
     * Lifecycle-record SCHEMA validation (audit 7237720579,
     * A03 residual): JSON parsing alone is not validation — a
     * record can parse and still be meaningless (wrong types,
     * missing fields, inconsistent ownership). Every required
     * field must be present with the right JSON type, and a
     * record claiming known ownership must carry a usable
     * identity. A schema-invalid record takes the corrupt
     * path: preserved untouched, successor waits fail-safe.
     */
    private fun isValidLifecycleSchema(fence: JSONObject): Boolean {
        fun number(key: String): Boolean {
            val value = fence.opt(key) ?: return false
            return value is Number
        }
        fun bool(key: String): Boolean = fence.opt(key) is Boolean
        fun text(key: String): Boolean = fence.opt(key) is String
        if (!number("version") || !bool("active") || !text("owner") ||
            !number("owner_pid") || !number("owner_start") ||
            !bool("owner_known") || !number("since_ms") ||
            !bool("worker_alive") || !bool("writer_alive") ||
            !text("reason")
        ) {
            return false
        }
        // Consistency: known ownership requires a usable
        // process identity (a positive PID and a non-negative
        // start time); without one the record cannot support
        // any liveness proof and is not a valid fence.
        if (fence.optBoolean("owner_known")) {
            if (fence.optLong("owner_pid", 0L) <= 0L ||
                fence.optLong("owner_start", -1L) < 0L
            ) {
                return false
            }
        }
        return true
    }

    /** An inactive lifecycle fence (the record's empty state). */
    private fun emptyLifecycleFence(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("active", false)
        put("owner", "")
        put("owner_pid", 0)
        put("owner_start", -1L)
        put("owner_known", false)
        put("since_ms", 0L)
        put("worker_alive", false)
        put("writer_alive", false)
        put("reason", "")
    }

    /**
     * An ACTIVE fence whose owner is UNKNOWN (both halves
     * assumed alive): the conservative state adopted when
     * ownership cannot be ruled out — e.g. the health document
     * was corrupt while no lifecycle record existed yet. It can
     * never prove stale; only an operator can retire it.
     */
    private fun unknownOwnerFence(reason: String): JSONObject {
        val fence = emptyLifecycleFence()
        fence.put("active", true)
        fence.put("owner_known", false)
        fence.put("since_ms", clock())
        fence.put("worker_alive", true)
        fence.put("writer_alive", true)
        fence.put("reason", reason)
        return fence
    }

    /** Writes the lifecycle record (caller holds both locks); failures are counted + logged. */
    private fun writeLifecycleLocked(fence: JSONObject) {
        try {
            healthStore?.writeCaptureLifecycle(fence.toString())
        } catch (t: Throwable) {
            healthStoreFailures.incrementAndGet()
            logSafely(
                "capture-lifecycle: penulisan catatan pagar gagal " +
                    "(${t.javaClass.simpleName}) — kegagalan tercatat, " +
                    "evaluasi pagar memakai nilai di memori",
            )
        }
    }

    /**
     * Evaluates the fence state (the store lock outside, this
     * instance's [healthLock] inside — the only order ever
     * taken). When [adopt] is set and no lifecycle record exists
     * yet, the legacy in-document fence (written before the
     * separation) is adopted into the lifecycle record, which
     * from then on is the only authority: identity-bearing
     * fences verbatim (owner_known = true), identity-less ones
     * as unknown ownership, and — when the health document is
     * corrupt at that moment — a conservative unknown-owner
     * fence, because a corrupt document cannot rule a live
     * predecessor out (audit A03).
     */
    private fun evaluateLifecycleFence(adopt: Boolean): LifecycleEval {
        val store = healthStore ?: return LifecycleEval.Clear
        return try {
            store.withCaptureHealthLock {
                healthLock.withLock {
                    evaluateLifecycleFenceLocked(adopt)
                }
            }
        } catch (t: Throwable) {
            LifecycleEval.Unreadable
        }
    }

    /** [evaluateLifecycleFence] under both locks. */
    private fun evaluateLifecycleFenceLocked(adopt: Boolean): LifecycleEval {
        val store = healthStore ?: return LifecycleEval.Clear
        val raw = try {
            store.readCaptureLifecycle()
        } catch (t: Throwable) {
            return LifecycleEval.Unreadable
        }
        // The owner of an INACTIVE lifecycle record, when one
        // exists: an in-document fence from that SAME owner was
        // already adopted from the document and retired in this
        // record, so the document copy is a leftover and must
        // not resurrect it. An in-document fence from a
        // DIFFERENT owner was never adopted — above all the
        // fallback fence a predecessor published into the
        // document when the lifecycle write channel itself was
        // failing (audit 7237720579) — and it binds.
        var inactiveLifecycleOwner: String? = null
        if (!raw.isNullOrBlank()) {
            val fence = try {
                JSONObject(raw)
            } catch (e: Exception) {
                // The record itself is corrupt: preserved
                // untouched (it is the evidence), ownership
                // unknown, successor waits. Never overwritten,
                // never cleared.
                lifecycleCorruptSeen = true
                return LifecycleEval.Corrupt
            }
            if (!isValidLifecycleSchema(fence)) {
                // Parses as JSON but violates the schema
                // (audit 7237720579): same corrupt path —
                // preserved, never acted on.
                lifecycleCorruptSeen = true
                return LifecycleEval.Corrupt
            }
            if (fence.optBoolean("active")) {
                return LifecycleEval.Active(fence)
            }
            inactiveLifecycleOwner = fence.optString("owner")
        }
        // No lifecycle record yet, or an inactive one: a store
        // written before the separation may carry the fence
        // inside the health document — and so may a predecessor
        // whose lifecycle writes failed at publish time (the
        // fallback channel). Adopt or evaluate it, never
        // ignore it (subject to the retired-leftover rule
        // above).
        val healthRaw = try {
            store.readCaptureHealth()
        } catch (t: Throwable) {
            return LifecycleEval.Unreadable
        }
        if (healthRaw.isNullOrBlank()) {
            if (adopt && inactiveLifecycleOwner == null) {
                writeLifecycleLocked(emptyLifecycleFence())
            }
            return LifecycleEval.Clear
        }
        val healthDoc = try {
            JSONObject(healthRaw)
        } catch (e: Exception) {
            if (inactiveLifecycleOwner != null) {
                // Post-separation the lifecycle record is the
                // authority and it says INACTIVE; a corrupt
                // document cannot resurrect a fence here (its
                // corruption is quarantined as an incident by
                // the health path, separately). The
                // unknown-owner conservatism below guards only
                // the UNSEPARATED state, where the document was
                // the fence's only home.
                return LifecycleEval.Clear
            }
            val fence = unknownOwnerFence(
                "dokumen kesehatan rusak saat catatan pagar belum " +
                    "terpisah — kepemilikan pendahulu tidak dapat " +
                    "disingkirkan, jadi pagar dianggap aktif",
            )
            if (adopt) writeLifecycleLocked(fence)
            return LifecycleEval.Active(fence)
        }
        val legacy = healthDoc.optJSONObject("fence")
        if (legacy == null || !legacy.optBoolean("active")) {
            if (adopt && inactiveLifecycleOwner == null) {
                writeLifecycleLocked(emptyLifecycleFence())
            }
            return LifecycleEval.Clear
        }
        if (inactiveLifecycleOwner != null &&
            legacy.optString("owner") == inactiveLifecycleOwner
        ) {
            // The same fence the inactive lifecycle record
            // already adopted and retired: a leftover document
            // copy, not a live fence.
            return LifecycleEval.Clear
        }
        val fence = emptyLifecycleFence()
        fence.put("active", true)
        fence.put("owner", legacy.optString("owner"))
        fence.put("owner_pid", legacy.optLong("owner_pid", 0L))
        fence.put("owner_start", legacy.optLong("owner_start", -1L))
        fence.put("owner_known", legacy.optLong("owner_pid", 0L) > 0L)
        fence.put("since_ms", legacy.optLong("since_ms", 0L))
        fence.put("worker_alive", legacy.optBoolean("worker_alive"))
        fence.put("writer_alive", legacy.optBoolean("writer_alive"))
        fence.put("reason", legacy.optString("reason"))
        if (adopt) writeLifecycleLocked(fence)
        return LifecycleEval.Active(fence)
    }

    /**
     * One serialised lifecycle-record read-modify-write (the
     * store lock outside, [healthLock] inside — the same order
     * as [mutateHealth], and the same critical section the
     * fence publish shares with the thread exit-clears, which
     * is what closes the sample-then-publish race). The load
     * ADOPTS a legacy in-document fence when no record exists
     * yet, so a mutation can never trample an unmigrated fence.
     * Returns false — aborting the write — when the record is
     * unreadable or corrupt (a corrupt record is evidence and
     * is never overwritten).
     */
    private fun mutateLifecycle(block: (JSONObject) -> Unit): Boolean {
        val store = healthStore ?: return false
        return try {
            store.withCaptureHealthLock {
                healthLock.withLock {
                    val raw = try {
                        store.readCaptureLifecycle()
                    } catch (t: Throwable) {
                        return@withLock false
                    }
                    val fence: JSONObject = if (!raw.isNullOrBlank()) {
                        val parsed = try {
                            JSONObject(raw)
                        } catch (e: Exception) {
                            lifecycleCorruptSeen = true
                            return@withLock false
                        }
                        if (!isValidLifecycleSchema(parsed)) {
                            // Schema-invalid (audit
                            // 7237720579): evidence, never
                            // overwritten by a mutation.
                            lifecycleCorruptSeen = true
                            return@withLock false
                        }
                        parsed
                    } else {
                        when (val eval = evaluateLifecycleFenceLocked(adopt = true)) {
                            is LifecycleEval.Active -> eval.fence
                            LifecycleEval.Clear -> emptyLifecycleFence()
                            else -> return@withLock false
                        }
                    }
                    block(fence)
                    store.writeCaptureLifecycle(fence.toString())
                    true
                }
            }
        } catch (t: Throwable) {
            healthStoreFailures.incrementAndGet()
            logSafely(
                "capture-lifecycle: mutasi catatan pagar gagal " +
                    "(${t.javaClass.simpleName}: ${t.message?.take(80)}) — " +
                    "kegagalan tercatat, capture tidak terpengaruh",
            )
            false
        }
    }

    /**
     * Is [fence] provably orphaned? Proof, and ONLY proof, in
     * order: both thread halves are already cleared (the
     * predecessor's threads exited — their exit, recorded in
     * this same authoritative record, IS the proof); or the
     * owner process is provably dead / its PID reused. Unknown
     * ownership (no identity, or `owner_known` false) is never
     * stale, and NEITHER IS AGE: a stuck thread can outlive any
     * bound, so an old fence proves nothing (audit A03 — the
     * 600 s legacy timeout is gone).
     */
    private fun fenceStale(fence: JSONObject): Boolean {
        if (!fence.optBoolean("worker_alive") && !fence.optBoolean("writer_alive")) {
            return true
        }
        if (!fence.optBoolean("owner_known")) return false
        val pid = fence.optLong("owner_pid", 0L)
        if (pid <= 0L) return false
        val store = healthStore ?: return false
        val identity = CaptureProcessIdentity(
            pid.toInt(),
            fence.optLong("owner_start", -1L),
        )
        if (identity == store.processIdentity()) {
            // A predecessor coordinator in THIS process: its
            // threads are real threads here and clear their
            // halves when they exit — staleness is decided by
            // the halves above, never by process death (this
            // process is alive by construction).
            return false
        }
        // Audit 7237720579 (A03 residual): ONLY a positively
        // proven DEAD clears the fence. ALIVE retains it, and
        // UNKNOWN — a permission denial, a transient I/O error,
        // a /proc visibility restriction, an unparseable stat —
        // retains it too: a failed proof is not a proof.
        return store.processLiveness(identity) == ProcessLiveness.DEAD
    }

    /** Evaluates a PREDECESSOR's fence from the lifecycle record. */
    private fun predecessorFenceVerdict(): FenceVerdict {
        if (healthStore == null) return FenceVerdict.CLEAR
        // Authoritative ownership FIRST (audit 7239229553,
        // A03): a foreign claim in the process-wide registry
        // binds this successor regardless of what the durable
        // records say — the claim exists precisely for the
        // states the records cannot represent (both fence
        // write channels failed while the predecessor's
        // threads live). The claim is released only when both
        // predecessor threads have actually exited, so WAIT
        // here is always backed by live threads, never by a
        // stale record.
        if (CaptureOwnershipRegistry.hasForeignClaim(instanceId)) {
            return FenceVerdict.WAIT
        }
        return when (val eval = evaluateLifecycleFence(adopt = true)) {
            LifecycleEval.Clear -> FenceVerdict.CLEAR
            LifecycleEval.Corrupt -> FenceVerdict.WAIT_LIFECYCLE_CORRUPT
            LifecycleEval.Unreadable -> FenceVerdict.WAIT_STORE_UNREADABLE
            is LifecycleEval.Active -> {
                if (eval.fence.optString("owner") == instanceId) {
                    FenceVerdict.CLEAR
                } else if (fenceStale(eval.fence)) {
                    FenceVerdict.STALE
                } else {
                    FenceVerdict.WAIT
                }
            }
        }
    }

    /**
     * Clears a provably orphaned fence: the staleness proof is
     * RE-RUN inside the store lock on the freshly loaded record
     * (the fence could have changed since the verdict), and the
     * clearing is recorded as a FENCE_ORPHAN_CLEARED incident —
     * visible history, not a silent takeover. Both fence
     * locations are attempted: the lifecycle record, and the
     * health document (the legacy / fallback location, audit
     * 7237720579 — a fence published there by a predecessor
     * whose lifecycle channel failed must clear by the same
     * proof, never strand a successor).
     */
    private fun clearStaleFence() {
        var didClear = false
        var clearedOwner = ""
        var clearedPid = 0L
        mutateLifecycle { fence ->
            if (!fence.optBoolean("active") ||
                fence.optString("owner") == instanceId ||
                !fenceStale(fence)
            ) {
                return@mutateLifecycle
            }
            clearedOwner = fence.optString("owner")
            clearedPid = fence.optLong("owner_pid", 0L)
            fence.put("active", false)
            fence.put("worker_alive", false)
            fence.put("writer_alive", false)
            didClear = true
        }
        if (!didClear) {
            mutateHealth { doc ->
                val fence = doc.optJSONObject("fence")
                if (fence != null &&
                    fence.optBoolean("active", false) &&
                    fence.optString("owner") != instanceId &&
                    fenceStale(fence)
                ) {
                    clearedOwner = fence.optString("owner")
                    clearedPid = fence.optLong("owner_pid", 0L)
                    fence.put("active", false)
                    fence.put("worker_alive", false)
                    fence.put("writer_alive", false)
                    didClear = true
                }
            }
        }
        if (didClear) {
            recordIncident(
                CaptureIncidentCategory.FENCE_ORPHAN_CLEARED,
                CaptureIncidentStatus.RECOVERED,
                "pagar penerus yatim dibersihkan: proses pemilik " +
                    "terbukti mati (owner=$clearedOwner pid=$clearedPid)",
                identity = clearedOwner,
            )
            logSafely(
                "pagar penerus yatim dibersihkan: proses pemilik terbukti mati " +
                    "(owner=$clearedOwner pid=$clearedPid); penerus melanjutkan",
            )
        }
    }

    /**
     * True while ANY coordinator's fence is recorded active —
     * or while the fence state is UNKNOWN (a corrupt lifecycle
     * record, or a corrupt health document before adoption):
     * diagnostics report the fail-safe reading, fenced.
     */
    private fun fenceRecordedActive(): Boolean {
        if (healthStore == null) return false
        // A standing ownership claim fences successors exactly
        // like a recorded fence (audit 7239229553, A03) —
        // diagnostics report the same fail-safe reading.
        if (CaptureOwnershipRegistry.hasForeignClaim(instanceId)) {
            return true
        }
        return when (evaluateLifecycleFence(adopt = false)) {
            is LifecycleEval.Active -> true
            LifecycleEval.Corrupt -> true
            else -> false
        }
    }

    /**
     * Blocks a replacement coordinator's threads until the
     * predecessor's fence clears — its threads exiting clear
     * their halves ([onWorkerThreadExit] / [onWriterThreadExit]),
     * or the fence proves stale by process death
     * ([clearStaleFence]). Every other state waits FAIL-SAFE: an
     * unreadable store, a corrupt lifecycle record, and unknown
     * ownership are never treated as no fence, and age never
     * clears anything. Bounded by [close]: a close during the
     * wait sets the closed flag and ends the wait, after which
     * the loops observe the closed state and exit.
     */
    private fun awaitSuccessorFence() {
        if (healthStore == null) return
        var unreadableLogged = false
        var corruptLogged = false
        while (!closedFlag) {
            when (predecessorFenceVerdict()) {
                FenceVerdict.CLEAR -> return
                FenceVerdict.STALE -> clearStaleFence()
                FenceVerdict.WAIT_STORE_UNREADABLE -> {
                    healthStoreUnreadable = true
                    if (!unreadableLogged) {
                        unreadableLogged = true
                        logSafely(
                            "capture-lifecycle: catatan pagar tidak terbaca — penerus " +
                                "menunggu fail-safe (pagar yang tidak " +
                                "terverifikasi tidak pernah diterobos)",
                        )
                    }
                    if (!sleepFencePoll()) return
                }
                FenceVerdict.WAIT_LIFECYCLE_CORRUPT -> {
                    if (!corruptLogged) {
                        corruptLogged = true
                        logSafely(
                            "capture-lifecycle: catatan pagar rusak — penerus " +
                                "menunggu fail-safe; catatan yang rusak " +
                                "dipertahankan sebagai bukti, tidak ditimpa",
                        )
                        recordIncident(
                            CaptureIncidentCategory.LIFECYCLE_CORRUPT,
                            CaptureIncidentStatus.OPEN,
                            "catatan lifecycle (pagar penerus) tidak dapat " +
                                "di-parse — dipertahankan utuh sebagai bukti; " +
                                "penerus menunggu fail-safe",
                        )
                    }
                    if (!sleepFencePoll()) return
                }
                FenceVerdict.WAIT -> if (!sleepFencePoll()) return
            }
        }
    }

    /** The fence poll sleep; false when interrupted (the wait ends). */
    private fun sleepFencePoll(): Boolean = try {
        Thread.sleep(FENCE_POLL_MS)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /**
     * Records this instance's fence when a pipeline thread
     * outlives [close]'s bounded wait. THE SAMPLING RACE THE
     * AUDIT FOUND is closed by construction: the `isAlive`
     * sample and the fence write happen INSIDE the same
     * store-lock critical section ([mutateLifecycle]), and an
     * exiting thread clears its half only through that same
     * lock — so either the exit-clear lands first (this sample
     * then reads not-alive and no fence is written) or this
     * write lands first (the exit-clear then runs and clears
     * the half). There is no interleaving in which a fence is
     * published for a thread that already exited and cleared.
     * The recorded owner is this PROCESS's identity (PID + start
     * time), so a later process can prove this one dead. The
     * fence is written to the lifecycle record — never to the
     * health document (audit A03).
     *
     * WRITE-FAILURE HARDENING (audit 7237720579, A03 residual):
     * a failed lifecycle write must never silently leave a
     * live-thread predecessor UNFENCED (a successor would then
     * run alongside it). The publish retries the lifecycle
     * channel, then falls back to writing the fence into the
     * HEALTH DOCUMENT — the legacy location successors already
     * adopt from when no lifecycle record exists, so the fence
     * still binds them (their thread-exit clears cover that
     * location too). Only when BOTH channels fail is the
     * publish declared failed: a FENCE_PUBLISH_FAILED incident
     * is recorded and the degraded state reports it — visible,
     * never silent.
     *
     * AUTHORITATIVE OWNERSHIP (audit 7239229553, A03): the
     * durable channels above are now the SECOND line of
     * exclusion, not the only one. Before any of them is
     * attempted, this instance claims ownership in
     * [CaptureOwnershipRegistry] — process memory, independent
     * of the failing store — and the claim is released only
     * when both pipeline threads have actually exited. A
     * successor in this process consults the registry before
     * the durable records and waits while a foreign claim
     * stands, so even the both-channels-failed corner excludes
     * it: logging/degraded state was never the exclusion, and
     * now it no longer needs to be. The FENCE_PUBLISH_FAILED
     * incident remains as the visible record that the DURABLE
     * fence could not be published.
     */
    private fun publishSuccessorFenceIfNeeded(reason: String) {
        // The authoritative claim comes FIRST, before any
        // durable write is attempted (audit 7239229553): if
        // either pipeline thread outlived close, this process
        // records that fact in its own memory, where a store
        // failure cannot erase it. The claim is inserted by
        // the registry's ATOMIC handshake (audit 7242084700):
        // the liveness sample and the insertion are one
        // critical section serialised against the exit
        // handlers' release, so an exit that lands between
        // the sample and the insertion can no longer strand a
        // permanent foreign claim — the exit either precedes
        // the sample (the half counts as exited and no claim
        // is owed for it) or follows the insertion (and its
        // recorded exit completes the release).
        CaptureOwnershipRegistry.claimIfAlive(
            instanceId,
            healthStore?.processIdentity()
                ?: CaptureProcessIdentity(-1, -1),
            workerAlive = { captureWorker.isAlive },
            writerAlive = { overflowWriter.isAlive },
        )
        var publishedWorker = false
        var publishedWriter = false
        val fenceBlock: (JSONObject) -> Unit = { fence ->
            val workerAlive = captureWorker.isAlive
            val writerAlive = overflowWriter.isAlive
            if (workerAlive || writerAlive) {
                val identity = healthStore?.processIdentity()
                    ?: CaptureProcessIdentity(-1, -1)
                fence.put("active", true)
                fence.put("owner", instanceId)
                fence.put("owner_pid", identity.pid)
                fence.put("owner_start", identity.startTicks)
                fence.put("owner_known", identity.pid > 0)
                fence.put("since_ms", clock())
                fence.put("worker_alive", workerAlive)
                fence.put("writer_alive", writerAlive)
                fence.put("reason", reason)
                publishedWorker = workerAlive
                publishedWriter = writerAlive
            }
        }
        var published = false
        var attempt = 0
        while (!published && attempt < FENCE_PUBLISH_ATTEMPTS) {
            attempt++
            publishedWorker = false
            publishedWriter = false
            published = mutateLifecycle(fenceBlock)
        }
        if (published && (publishedWorker || publishedWriter)) {
            logSafely(
                "pagar penerus dicatat: worker/penulis pendahulu masih hidup " +
                    "sesudah close (worker=$publishedWorker, " +
                    "penulis=$publishedWriter); koordinator pengganti " +
                    "ter-fence sampai keduanya berhenti",
            )
            return
        }
        if (!captureWorker.isAlive && !overflowWriter.isAlive) {
            // Nothing outlived close: no fence is owed, and a
            // failed write of an unneeded fence is not a
            // publish failure.
            return
        }
        // Fallback channel: the fence inside the health
        // document, in the exact legacy shape the successor
        // adoption path reads.
        publishedWorker = false
        publishedWriter = false
        val fallbackWritten = mutateHealth { doc ->
            val workerAlive = captureWorker.isAlive
            val writerAlive = overflowWriter.isAlive
            if (workerAlive || writerAlive) {
                val identity = healthStore?.processIdentity()
                    ?: CaptureProcessIdentity(-1, -1)
                doc.put(
                    "fence",
                    JSONObject().apply {
                        put("active", true)
                        put("owner", instanceId)
                        put("owner_pid", identity.pid)
                        put("owner_start", identity.startTicks)
                        put("owner_known", identity.pid > 0)
                        put("since_ms", clock())
                        put("worker_alive", workerAlive)
                        put("writer_alive", writerAlive)
                        put("reason", reason)
                    },
                )
                publishedWorker = workerAlive
                publishedWriter = writerAlive
            }
        }
        if (fallbackWritten && (publishedWorker || publishedWriter)) {
            logSafely(
                "pagar penerus dicatat lewat DOKUMEN KESEHATAN (saluran " +
                    "lifecycle gagal $attempt kali): worker/penulis pendahulu " +
                    "masih hidup sesudah close (worker=$publishedWorker, " +
                    "penulis=$publishedWriter); pengganti ter-fence lewat " +
                    "adopsi dokumen sampai keduanya berhenti",
            )
            return
        }
        // Both channels failed while threads are alive: the
        // fence may be invisible to a successor. Record it —
        // visible, never silent (audit 7237720579).
        fencePublishFailed = true
        recordIncident(
            CaptureIncidentCategory.FENCE_PUBLISH_FAILED,
            CaptureIncidentStatus.OPEN,
            "publikasi pagar penerus gagal di saluran lifecycle " +
                "($attempt percobaan) dan di dokumen kesehatan, padahal " +
                "thread pendahulu masih hidup sesudah close — pengganti " +
                "mungkin tidak melihat pagar ini",
        )
        logSafely(
            "capture-lifecycle: publikasi pagar penerus GAGAL di kedua " +
                "saluran padahal thread masih hidup — tercatat sebagai " +
                "insiden fence_publish_failed",
        )
    }

    /**
     * Clears one half of this instance's fence in the HEALTH
     * DOCUMENT — the fallback fence location (audit 7237720579:
     * used when the lifecycle write channel itself failed at
     * publish time). Mutates only when the document fence is
     * ours and active, so the normal lifecycle path pays only
     * one extra document mutation per thread exit.
     */
    private fun clearDocFenceHalf(workerHalf: Boolean) {
        mutateHealth { doc ->
            val fence = doc.optJSONObject("fence")
            if (fence != null &&
                fence.optBoolean("active", false) &&
                fence.optString("owner") == instanceId
            ) {
                if (workerHalf) {
                    fence.put("worker_alive", false)
                } else {
                    fence.put("writer_alive", false)
                }
                if (!fence.optBoolean("worker_alive", false) &&
                    !fence.optBoolean("writer_alive", false)
                ) {
                    fence.put("active", false)
                }
            }
        }
    }

    /** The capture worker exited: clears its half of this instance's fence. */
    private fun onWorkerThreadExit() {
        // The exit is recorded in the ownership registry
        // FIRST (audit 7242084700, A03): the atomic handshake
        // serialises this record against any in-flight claim
        // insertion, so the claim's release can never be lost
        // between a publish's liveness sample and its claim —
        // and being in-memory, no store failure below can
        // strand the claim either. The durable fence cleanup
        // follows as before.
        CaptureOwnershipRegistry.noteThreadExit(instanceId, workerHalf = true)
        mutateLifecycle { fence ->
            if (fence.optString("owner") != instanceId || !fence.optBoolean("active")) {
                return@mutateLifecycle
            }
            fence.put("worker_alive", false)
            if (!fence.optBoolean("writer_alive")) fence.put("active", false)
        }
        clearDocFenceHalf(workerHalf = true)
    }

    /** The overflow writer exited: clears its half of this instance's fence. */
    private fun onWriterThreadExit() {
        // See onWorkerThreadExit: the registry records the
        // exit before any store cleanup runs.
        CaptureOwnershipRegistry.noteThreadExit(instanceId, workerHalf = false)
        mutateLifecycle { fence ->
            if (fence.optString("owner") != instanceId || !fence.optBoolean("active")) {
                return@mutateLifecycle
            }
            fence.put("writer_alive", false)
            if (!fence.optBoolean("worker_alive")) fence.put("active", false)
        }
        clearDocFenceHalf(workerHalf = false)
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
                    identity = obsIdentity(snapshot),
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
                    identity = obsIdentity(snapshot),
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
                    // Recovery proof at the granularity the proof
                    // actually has (audit P1-3 — the previous code
                    // recovered OVERFLOW_RECORD_LOST wholesale when
                    // any retried record landed, although the lost
                    // records themselves stayed lost):
                    // - COMPONENT proof: a landed insert proves
                    //   the database component works again, so the
                    //   aggregate DB_PERMANENT_FAILURE incident
                    //   closes.
                    // - IDENTITY proof (METADATA only): if THIS
                    //   snapshot had a lost-record incident open,
                    //   the durable overflow row for THIS
                    //   observation identity now exists — the
                    //   EVIDENCE is reconciled, and only that
                    //   identity's. This is metadata recovery,
                    //   never payment recovery (see
                    //   [recoverOverflowRecordMetadata]): the
                    //   payment is recovered only by an actual
                    //   capture of the snapshot. A record that
                    //   merely needed retries was never lost, and
                    //   an unrelated record's success proves
                    //   nothing about a lost one.
                    recoverIncident(CaptureIncidentCategory.DB_PERMANENT_FAILURE, "")
                    recoverOverflowRecordMetadata(
                        obsIdentity(record.snapshot),
                    )
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
                        identity = obsIdentity(snapshot),
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
                    identity = obsIdentity(snapshot),
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
        healthIncidentsDropped = healthIncidentsDropped.get(),
        healthStoreFailures = healthStoreFailures.get(),
        healthJournalTimeouts = healthJournalTimeouts.get(),
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
        // Health-store failure state (audit P1-2): the
        // persistent layer's own failures are degradation too —
        // surfaced, never swallowed.
        if (c.healthStoreFailures > 0) {
            reasons += "penyimpanan status kesehatan capture gagal " +
                "${c.healthStoreFailures} kali (insiden persisten mungkin tidak " +
                "terbarui; periksa penyimpanan aplikasi)"
        }
        if (c.healthIncidentsDropped > 0) {
            reasons += "${c.healthIncidentsDropped} insiden kesehatan tidak " +
                "terantre/tertulis (antrean penuh atau penulis kesehatan sudah " +
                "berhenti; bukti: counter + log lokal)"
        }
        if (healthDocCorruptions.get() > 0 || healthDocCorruptSeen) {
            reasons += "dokumen kesehatan capture pernah rusak — nilai mentahnya " +
                "di-quarantine di dalam dokumen sebagai bukti, tidak dibuang " +
                "diam-diam"
        }
        if (healthStoreUnreadable) {
            reasons += "dokumen kesehatan tidak terbaca saat pemeriksaan pagar — " +
                "penerus menunggu fail-safe, tidak menerobos"
        }
        if (lifecycleCorruptSeen) {
            reasons += "catatan lifecycle (pagar penerus) rusak — dipertahankan " +
                "utuh sebagai bukti dan tidak pernah ditimpa; kepemilikan " +
                "dianggap tidak dikenal, penerus menunggu fail-safe"
        }
        if (fencePublishFailed) {
            reasons += "publikasi pagar penerus gagal di saluran lifecycle " +
                "dan di dokumen kesehatan padahal thread pendahulu masih " +
                "hidup — pengganti mungkin tidak melihat pagar ini " +
                "(insiden fence_publish_failed tercatat)"
        }
        if (journalLagging || c.healthJournalTimeouts > 0) {
            reasons += "jurnal penawaran insiden tertinggal dari counter " +
                "(penyimpanan kesehatan lebih lambat dari batas " +
                "${JOURNAL_WRITE_BOUND_MS}ms atau gagal): " +
                "${c.healthJournalTimeouts} sinkronisasi melampaui batas — " +
                "penawaran di jendela lag ini terpapar celah residual yang " +
                "dinyatakan bila proses mati sebelum jurnal mengejar"
        }
        // Unresolved losses, exposed independently of component
        // health (audit P1-3): identity-keyed loss/unknown
        // incidents. A recovered component never removes these;
        // only identity-level reconciliation or an explicit
        // operator disposition does.
        val unresolvedLosses = incidents.filter {
            (it.category == CaptureIncidentCategory.OVERFLOW_RECORD_LOST &&
                it.status == CaptureIncidentStatus.OPEN) ||
                (it.category == CaptureIncidentCategory.UNRESOLVED_AT_CLOSE &&
                    it.status == CaptureIncidentStatus.OPEN) ||
                (it.category == CaptureIncidentCategory.PRE_STAGE_INTERRUPTED &&
                    it.status == CaptureIncidentStatus.UNRECOVERABLE)
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
            unresolvedLosses = unresolvedLosses,
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
        // Construction/close serialisation (audit 7242084700,
        // A03): the ownership claim is taken NOW, at the start
        // of the one-shot close sequence — not only later in
        // publishSuccessorFenceIfNeeded. A successor
        // constructed at any point during this close therefore
        // sees the claim and waits, instead of racing a close
        // whose fence publish has not happened yet. The claim
        // is released by the exit handlers as the pipeline
        // threads actually exit during the close (the normal
        // clean-close path removes it before close returns);
        // publishSuccessorFenceIfNeeded re-asserts it through
        // the same atomic handshake for any thread that
        // outlives the bounded joins.
        CaptureOwnershipRegistry.claimIfAlive(
            instanceId,
            healthStore?.processIdentity()
                ?: CaptureProcessIdentity(-1, -1),
            workerAlive = { captureWorker.isAlive },
            writerAlive = { overflowWriter.isAlive },
        )
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
        // Successor fence (PR #5 re-review 28 Nov; audit P1-1):
        // if either pipeline thread outlived this bounded close,
        // publish the fence — with the liveness sample taken
        // INSIDE the health store's lock (see
        // publishSuccessorFenceIfNeeded), so a thread that exits
        // during the publish cannot leave a stale fence behind.
        // A replacement coordinator's threads wait on that fence
        // until the predecessor's threads actually exit (their
        // exit clears their halves), or until the fence proves
        // stale (owner process dead), so a successor can never
        // run concurrently with a live predecessor.
        publishSuccessorFenceIfNeeded(
            "close pendahulu melewati batas tunggu",
        )
        // Flush + terminate the health writer (audit P1-2): the
        // incidents this close queued (stranded overflow records,
        // the active-task settlement) must cross the durable
        // acceptance boundary before close() returns — close() is
        // their acceptance point. The writer then exits instead
        // of looping forever; incidents offered afterwards are
        // counted as dropped (see enqueueHealthOp), never queued
        // to a dead writer. If the bounded join expires, the
        // daemon writer keeps draining in the background — the
        // incidents still land; only a process death inside that
        // window loses them (the documented pre-durable window).
        flushHealthIncidents(HEALTH_FLUSH_TIMEOUT_MS)
        healthWriterShutdown = true
        healthWriter.interrupt()
        try {
            healthWriter.join(HEALTH_WRITER_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (healthWriter.isAlive) {
            logSafely(
                "penulis kesehatan masih menguras antrean sesudah close; " +
                    "insiden terantre tetap ditulis di latar belakang",
            )
        }
        // Final offer-journal sync (audit 7237720579, A06):
        // every RECORD offered during this close (stranded
        // overflow records, close settlements) must be durable
        // in the journal before close returns, so the next
        // start's reconciliation sees exact totals. Bounded
        // like every journal wait; the journal executor then
        // shuts down (later offers are counted as journal lag,
        // never queued to a dead executor).
        writeJournalBounded()
        journalExecutor.shutdown()
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
                    identity = active.snapshot?.let { obsIdentity(it) } ?: "",
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
                    identity = interrupted.snapshot?.let { obsIdentity(it) } ?: "",
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
    suspend fun sweepOrphanedStaging(): SweepResult {
        val rows = repository.getAllStagingRows()
        var resolved = 0
        var failed = 0
        var held = 0
        val resolvedIdentities = mutableSetOf<String>()
        for (row in rows) {
            try {
                val outcome = sweepOne(row)
                if (outcome.resolved) {
                    resolved++
                    outcome.identity?.let { resolvedIdentities += it }
                } else {
                    held++
                }
            } catch (e: Exception) {
                failed++
                logger.logLocal("sweep staging gagal (${row.legacyQkey}): ${e.message?.take(120)}")
            }
        }
        // Recovery proof is IDENTITY-LEVEL (audit P1-3 — the
        // previous code recovered UNRESOLVED_AT_CLOSE
        // unconditionally after any completed pass): an
        // unresolved-at-close incident closes ONLY when this
        // pass terminally resolved a staging row whose event
        // carries that exact snapshot identity. A pass with
        // failed rows proves nothing about those rows; an empty
        // pass proves nothing at all (the unresolved snapshot's
        // staging may have rolled back — its outcome stays
        // unknown until rescan, a later resolving sweep, or an
        // explicit operator disposition).
        for (identity in resolvedIdentities) {
            recoverIncident(CaptureIncidentCategory.UNRESOLVED_AT_CLOSE, identity)
        }
        return SweepResult(
            rowsSeen = rows.size,
            rowsResolved = resolved,
            rowsFailed = failed,
            rowsHeld = held,
        )
    }

    /** One sweep row's outcome: terminally resolved, and the snapshot identity it proved (if any). */
    private class SweepRowOutcome(val resolved: Boolean, val identity: String?)

    private suspend fun sweepOne(row: CaptureStagingEntity): SweepRowOutcome {
        val event = repository.getEventByDedupeKey(row.dedupeKey)
        // The observation identity this row can prove recovery
        // for: its event's package + occurrence time + content
        // hash — the same identity the close path recorded for
        // the snapshot (audit A05: package+millisecond alone
        // collides across distinct notifications). An event
        // without a content hash (legacy imports) proves
        // nothing at identity level: identity stays null.
        val identity = event?.let {
            val pkg = it.sourcePackage
            val sha = it.contentSha256
            if (pkg != null && sha != null) {
                obsIdentity(pkg, it.occurredAtMs, sha)
            } else {
                null
            }
        }
        val existing = repository.getDeliveryByQkey(row.legacyQkey)
        if (existing != null) {
            // Already promoted (crash between enqueue and staging
            // delete): delete the orphan row, no second delivery.
            repository.deleteStagingRow(row.legacyQkey)
            sweptOrphaned.incrementAndGet()
            return SweepRowOutcome(resolved = true, identity = identity)
        }
        if (event == null) {
            // No event row: the staging row cannot be promoted
            // (nothing to deliver). Delete it — counted, visible.
            // This resolves the ROW but proves nothing about any
            // unresolved snapshot (identity stays null).
            repository.deleteStagingRow(row.legacyQkey)
            sweptOrphaned.incrementAndGet()
            logger.logLocal("staging tanpa event dihapus: ${row.legacyQkey}")
            return SweepRowOutcome(resolved = true, identity = null)
        }
        val parsed = LegacyQkey.parse(row.legacyQkey) ?: run {
            repository.deleteStagingRow(row.legacyQkey)
            sweptOrphaned.incrementAndGet()
            logger.logLocal("staging qkey rusak dihapus: ${row.legacyQkey}")
            return SweepRowOutcome(resolved = true, identity = identity)
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
            return SweepRowOutcome(resolved = false, identity = null)
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
        return if (enqueueStaged(row, delivery, payload, snapshot, parsed.routeIds)) {
            sweptOrphaned.incrementAndGet()
            SweepRowOutcome(resolved = true, identity = identity)
        } else {
            // Promotion failed: the row stays staged and proves
            // nothing yet — NOT resolved (audit P1-3).
            SweepRowOutcome(resolved = false, identity = null)
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
