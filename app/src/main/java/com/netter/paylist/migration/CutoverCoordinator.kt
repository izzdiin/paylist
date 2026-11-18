package com.netter.paylist.migration

import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.TransactionRunner
import com.netter.paylist.data.dao.AttemptDao
import com.netter.paylist.data.dao.CollectorStateDao
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.data.entity.AttemptState
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.data.entity.DeliveryAttemptEntity
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.locks.Lock
import kotlin.concurrent.withLock

/** Cutover modes (design §4.4, §8.4). The authoritative mode store is Room `collector_state`. */
enum class CutoverMode {
    LEGACY_ONLY,
    DRAINING,
    WORKER_ONLY,
}

/** Drain intents (design §8.4). */
enum class DrainIntent {
    NONE,
    PENDING,
    QUIESCED,
    ABORTED,
}

/**
 * The cutover import phase marker, stored at the `cutover_import`
 * collector_state key (design §4.4, §8.2). Phases:
 * `draining` (commit done, import not yet verified) →
 * `drained` (import verified, sweep in progress or done) →
 * `worker_only` | `aborted`.
 */
data class CutoverPhaseMarker(
    val phase: String,
    val drainGeneration: Long,
    val fenceId: String,
    val startedAtMs: Long,
    val snapshotCount: Int,
    val snapshotChecksum: String,
    val importableCount: Int,
    val importableChecksum: String,
    val quarantinedCount: Int,
    val quarantineChecksum: String,
    val importedCount: Int,
    val verified: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("phase", phase)
        put("drain_generation", drainGeneration)
        put("fence_id", fenceId)
        put("started_at_ms", startedAtMs)
        put("snapshot_count", snapshotCount)
        put("snapshot_checksum", snapshotChecksum)
        put("importable_count", importableCount)
        put("importable_checksum", importableChecksum)
        put("quarantined_count", quarantinedCount)
        put("quarantine_checksum", quarantineChecksum)
        put("imported_count", importedCount)
        put("verified", verified)
    }

    companion object {
        fun fromJson(o: JSONObject): CutoverPhaseMarker = CutoverPhaseMarker(
            phase = o.optString("phase"),
            drainGeneration = o.optLong("drain_generation"),
            fenceId = o.optString("fence_id"),
            startedAtMs = o.optLong("started_at_ms"),
            snapshotCount = o.optInt("snapshot_count"),
            snapshotChecksum = o.optString("snapshot_checksum"),
            importableCount = o.optInt("importable_count"),
            importableChecksum = o.optString("importable_checksum"),
            quarantinedCount = o.optInt("quarantined_count"),
            quarantineChecksum = o.optString("quarantine_checksum"),
            importedCount = o.optInt("imported_count"),
            verified = o.optBoolean("verified"),
        )
    }
}

/** A sweep fence document, stored at `sweep_fence:<fence_id>` (§8.6). */
data class SweepFence(
    val fenceId: String,
    val scope: String,
    val drainGeneration: Long,
    val createdAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("fence_id", fenceId)
        put("scope", scope)
        put("drain_generation", drainGeneration)
        put("created_at_ms", createdAtMs)
    }

    companion object {
        fun fromJson(o: JSONObject): SweepFence = SweepFence(
            fenceId = o.optString("fence_id"),
            scope = o.optString("scope"),
            drainGeneration = o.optLong("drain_generation"),
            createdAtMs = o.optLong("created_at_ms"),
        )
    }
}

/** A sweep intent document, stored at `sweep_intent:<intent_id>` (§8.6). */
data class SweepIntent(
    val intentId: String,
    val fenceId: String,
    val qkey: String,
    val dedupeKey: String,
    val nextRouteIndex: Int,
    val createdAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("intent_id", intentId)
        put("fence_id", fenceId)
        put("qkey", qkey)
        put("dedupe_key", dedupeKey)
        put("next_route_index", nextRouteIndex)
        put("created_at_ms", createdAtMs)
    }

    companion object {
        fun fromJson(o: JSONObject): SweepIntent = SweepIntent(
            intentId = o.optString("intent_id"),
            fenceId = o.optString("fence_id"),
            qkey = o.optString("qkey"),
            dedupeKey = o.optString("dedupe_key"),
            nextRouteIndex = o.optInt("next_route_index"),
            createdAtMs = o.optLong("created_at_ms"),
        )
    }
}

/**
 * A sweep record, stored at `sweep_record:<dedupe_key>` (§8.6).
 * `outcome` is `removed` (the queue item is gone, the Room copy is
 * the durable truth) or `skipped_held` (the queue copy is kept as
 * evidence while the Room delivery awaits reconciliation/review).
 */
data class SweepRecord(
    val dedupeKey: String,
    val qkey: String,
    val outcome: String,
    val state: String?,
    val fenceId: String,
    val recordedAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("dedupe_key", dedupeKey)
        put("qkey", qkey)
        put("outcome", outcome)
        if (state != null) put("state", state)
        put("fence_id", fenceId)
        put("recorded_at_ms", recordedAtMs)
    }

    companion object {
        const val OUTCOME_REMOVED: String = "removed"
        const val OUTCOME_SKIPPED_HELD: String = "skipped_held"

        fun fromJson(o: JSONObject): SweepRecord = SweepRecord(
            dedupeKey = o.optString("dedupe_key"),
            qkey = o.optString("qkey"),
            outcome = o.optString("outcome"),
            state = o.optString("state").ifEmpty { null },
            fenceId = o.optString("fence_id"),
            recordedAtMs = o.optLong("recorded_at_ms"),
        )
    }
}

/** Result of a cutover commit (`commitDraining` / `commitWorkerOnly` / `abortCutover`). */
sealed interface CutoverCommit {
    data class Success(val mode: CutoverMode, val drainGeneration: Long?) : CutoverCommit
    data class Rejected(val reason: String) : CutoverCommit
}

/** Result of one `drain()` run. */
data class DrainResult(
    val ok: Boolean,
    val imported: Int,
    val removed: Int,
    val skippedHeld: Int,
    val quarantined: Int,
    val expiredSkipped: Int,
    val corrupt: Int,
    val verified: Boolean,
    val error: String?,
)

/** One admission request (§8.5(a)): the drain epoch a worker thread presents. */
data class AdmissionRequest(
    val deliveryId: String,
    val routeId: String?,
    val hopIndex: Int,
    val attemptNo: Int,
    val dedupeKey: String,
    val ownerBootId: String,
    val drainGeneration: Long?,
    val consentEpoch: Long?,
    val configEpoch: Long?,
    val urlSha256: String?,
)

/** Which sender path is asking for admission (§8.5(f)): one rule, both paths. */
enum class AdmissionPath {
    /** The legacy flush's hop entry. Admitted only in `LEGACY_ONLY`. */
    LEGACY,

    /** The Room worker's claim. Admitted only in `WORKER_ONLY`. */
    WORKER,
}

/** Admission rejection codes (§8.5). */
enum class AdmissionRejection {
    MODE,
    FENCED,
    DRAIN_PENDING,
    EPOCH_STALE,
    LIVE_ATTEMPT,
}

/** Result of `admitAttempt`. */
sealed interface AdmissionResult {
    data class Admitted(val attemptId: String) : AdmissionResult
    data class Rejected(val code: AdmissionRejection) : AdmissionResult
}

/** Result of `recoverOrphanedAttempts`. */
data class RecoveryReport(
    val abandonedStale: Int,
    val neverSent: Int,
    val unknownHeld: Int,
    val orphanCount: Int,
    val stuckOpeningCount: Int,
)

/**
 * The legacy queue snapshot source: the read-only snapshot plus the
 * synchronous queue write (design §8.2, §8.6). In production this is
 * [com.netter.paylist.Store] (`queueSnapshot()` / `saveQueueSync()`);
 * the coordinator depends on the interface, not on Android.
 */
interface LegacyQueueSnapshotSource {
    fun queueSnapshot(): List<JSONObject>
    fun saveQueueSync(items: List<JSONObject>): Boolean
}

/**
 * Re-promotion of a Room event payload back to the legacy queue
 * (Stage D). Needed if a cutover is aborted after Room captures
 * exist: those events must still be delivered by the legacy sender.
 * Stage B defines the interface; no Stage B path calls it.
 */
fun interface LegacyEnqueue {
    fun enqueue(payload: JSONObject, kind: String, routeIds: List<String>): Boolean
}

/**
 * The cutover coordinator (design §8): the single owner of the
 * cutover mode, the drain, the sweep, attempt admission and
 * orphan recovery.
 *
 * Mode + migration state live in Room `collector_state` and are
 * written in single Room transactions; a crash mid-transaction rolls
 * back and the old mode stands (§4.4). There is no mode value in
 * SharedPreferences (R14; asserted by the F27 static check).
 *
 * Stage B scope: the coordinator, the drain, the sweep and admission
 * are implemented and unit-tested with fake DAOs, but NOTHING calls
 * them from production code yet — the legacy sender keeps running
 * exactly as before (R29). Stage C wires the coordinator to the
 * sender/worker; Stage D wires capture/promotion.
 *
 * The coordinator never holds the sender lock across a suspension:
 * the lock guards only the synchronous `saveQueueSync` calls in the
 * sweep. The capture executor and the legacy re-enqueue path belong
 * to Stage D and are not constructor dependencies here.
 */
class CutoverCoordinator(
    private val stateDao: CollectorStateDao,
    private val deliveryDao: DeliveryDao,
    private val attemptDao: AttemptDao,
    private val transactions: TransactionRunner,
    private val migration: LegacyQueueMigration,
    private val queue: LegacyQueueSnapshotSource,
    private val senderLock: Lock,
    /**
     * The F40 gate (design §8.5 resolution 3): until F40 passes on
     * the actual stack, orphaned `ADMITTED`/`SOCKET_OPENING` rows
     * classify `UNKNOWN` at the delivery level. Only when this is
     * true may recovery mark them `NEVER_SENT`. Default false.
     */
    private val neverSentGatePassed: Boolean = false,
) {
    companion object {
        /** Admission lease: only `ADMITTED` rows older than this may be abandoned (§8.5). */
        const val ADMISSION_LEASE_MS: Long = 60_000L

        /** Watchdog: `SOCKET_OPENING` rows older than this are alerted, never abandoned (§8.5). */
        const val OPENING_ALERT_MS: Long = 300_000L

        /** The five legal (mode, intent) combinations (design §8.4). */
        fun isLegalCombination(mode: CutoverMode, intent: DrainIntent): Boolean = when (mode to intent) {
            CutoverMode.LEGACY_ONLY to DrainIntent.NONE -> true
            CutoverMode.LEGACY_ONLY to DrainIntent.PENDING -> true
            CutoverMode.DRAINING to DrainIntent.QUIESCED -> true
            CutoverMode.WORKER_ONLY to DrainIntent.QUIESCED -> true
            CutoverMode.LEGACY_ONLY to DrainIntent.ABORTED -> true
            else -> false
        }

        /**
         * The sweep decision for one Room delivery (§8.6/§8.7):
         * `REMOVE` when Room is the durable truth and the queue copy
         * is redundant; `SKIP_HELD` when the queue copy must stay as
         * evidence while the Room delivery awaits reconciliation or
         * an operator decision.
         */
        fun sweepDecision(state: DeliveryState): SweepDecision = when (state) {
            DeliveryState.PENDING,
            DeliveryState.IN_FLIGHT,
            DeliveryState.RETRY_WAIT,
            DeliveryState.BLOCKED,
            DeliveryState.ACKED,
            DeliveryState.EXPIRED,
            DeliveryState.DISCARDED,
            -> SweepDecision.REMOVE
            DeliveryState.HELD_AWAITING_RECONCILIATION,
            DeliveryState.HELD_AWAITING_LEGACY_SWEEP,
            DeliveryState.NEEDS_REVIEW,
            DeliveryState.RAW_UNAVAILABLE,
            -> SweepDecision.SKIP_HELD
        }
    }

    enum class SweepDecision {
        REMOVE,
        SKIP_HELD,
    }

    // --- Mode / intent / generation readers ---

    suspend fun getMode(): CutoverMode {
        val doc = readDoc(CollectorStateKeys.CUTOVER_STATE) ?: return CutoverMode.LEGACY_ONLY
        return runCatching { CutoverMode.valueOf(doc.optString("mode")) }
            .getOrDefault(CutoverMode.LEGACY_ONLY)
    }

    suspend fun getDrainIntent(): DrainIntent {
        val doc = readDoc(CollectorStateKeys.DRAIN_INTENT) ?: return DrainIntent.NONE
        return runCatching { DrainIntent.valueOf(doc.optString("state")) }
            .getOrDefault(DrainIntent.NONE)
    }

    suspend fun getDrainGeneration(): Long? {
        val doc = readDoc(CollectorStateKeys.DRAIN_INTENT) ?: return null
        return if (doc.has("generation")) doc.optLong("generation") else null
    }

    suspend fun getFenceGeneration(): Long? {
        val doc = readDoc(CollectorStateKeys.FENCE_GENERATION) ?: return null
        return if (doc.has("generation")) doc.optLong("generation") else null
    }

    suspend fun getPhaseMarker(): CutoverPhaseMarker? {
        val doc = readDoc(CollectorStateKeys.CUTOVER_IMPORT) ?: return null
        return CutoverPhaseMarker.fromJson(doc)
    }

    suspend fun isSenderFenced(): Boolean {
        val doc = readDoc(CollectorStateKeys.SENDER_FENCED) ?: return false
        return doc.optBoolean("fenced")
    }

    // --- Cutover commits (§8.4) ---

    /**
     * Installs the drain intent `PENDING` (§8.5(c)): one Room
     * transaction that writes the intent row and, in the same
     * transaction, snapshots the awaited set — every unresolved
     * attempt row at install time. The awaited set is closed at
     * install time: no attempt can be admitted afterwards while the
     * intent is `PENDING` (`DRAIN_PENDING`).
     *
     * Requires (`LEGACY_ONLY`, `NONE`). The drain itself starts only
     * with [commitDraining], which requires the awaited set resolved.
     */
    suspend fun requestDrain(nowMs: Long): CutoverCommit {
        return transactions.run {
            val mode = getMode()
            val intent = getDrainIntent()
            if (mode != CutoverMode.LEGACY_ONLY || intent != DrainIntent.NONE) {
                return@run CutoverCommit.Rejected(
                    "cannot request drain from mode=$mode intent=$intent",
                )
            }
            val generation = (getDrainGeneration() ?: 0L) + 1
            val awaited = attemptDao.getAllUnresolved().map { it.attemptId }
            stateDao.put(
                state(
                    CollectorStateKeys.DRAIN_INTENT,
                    intentDoc(generation, nowMs, DrainIntent.PENDING, awaited),
                    nowMs,
                ),
            )
            CutoverCommit.Success(CutoverMode.LEGACY_ONLY, generation)
        }
    }

    /**
     * Commits `LEGACY_ONLY` → `DRAINING` with the drain intent
     * `PENDING` → `QUIESCED`, in ONE Room transaction (§8.4).
     * `DRAINING` requires the atomic `PENDING → QUIESCED` commit,
     * which cannot occur without a drain having been requested
     * (R19 correction of F37a): the intent MUST already be `PENDING`.
     * The commit also writes the sweep fence, bumps the fence
     * generation, and writes the phase marker.
     *
     * Requires quiescence: the awaited set snapshotted by
     * [requestDrain] must be resolved, and no unresolved attempt
     * may be owned by the current boot (read from the `boot_id`
     * key). Without a known boot id the commit is rejected —
     * fail-closed.
     */
    suspend fun commitDraining(nowMs: Long): CutoverCommit {
        return transactions.run {
            val mode = getMode()
            val intent = getDrainIntent()
            if (mode != CutoverMode.LEGACY_ONLY || intent != DrainIntent.PENDING) {
                return@run CutoverCommit.Rejected(
                    "cannot commit DRAINING from mode=$mode intent=$intent " +
                        "(a drain must be requested first)",
                )
            }
            val bootDoc = readDoc(CollectorStateKeys.BOOT_ID)
            val bootId = bootDoc?.optString("boot_id")?.ifEmpty { null }
                ?: return@run CutoverCommit.Rejected("boot_id unknown; quiescence cannot be proven")
            val awaited = readAwaitedAttemptIds()
            for (attemptId in awaited) {
                val row = attemptDao.getById(attemptId)
                if (row != null && row.state in AttemptDao.UNRESOLVED_STATES) {
                    // Orphaned rows (owned by a dead process) do not
                    // block quiescence (F28): recovery classifies
                    // them, never deletes them, and the import holds
                    // their deliveries. Only live attempts are awaited.
                    if (row.ownerBootId != bootId) continue
                    return@run CutoverCommit.Rejected(
                        "awaited attempt $attemptId still unresolved; release waits for it",
                    )
                }
                // An absent row was resolved and then pruned/retained
                // away; nothing remains to await.
            }
            if (attemptDao.countUnresolvedByOwner(bootId) > 0) {
                return@run CutoverCommit.Rejected("unresolved attempts owned by this process; not quiescent")
            }
            val generation = getDrainGeneration() ?: 0L
            val fenceGeneration = (getFenceGeneration() ?: 0L) + 1
            val fenceId = UUID.randomUUID().toString()
            val fence = SweepFence(fenceId, "legacy_queue", generation, nowMs)
            val marker = CutoverPhaseMarker(
                phase = "draining",
                drainGeneration = generation,
                fenceId = fenceId,
                startedAtMs = nowMs,
                snapshotCount = 0,
                snapshotChecksum = "",
                importableCount = 0,
                importableChecksum = "",
                quarantinedCount = 0,
                quarantineChecksum = "",
                importedCount = 0,
                verified = false,
            )
            stateDao.putAll(
                listOf(
                    state(CollectorStateKeys.CUTOVER_STATE, modeDoc(CutoverMode.DRAINING), nowMs),
                    state(
                        CollectorStateKeys.DRAIN_INTENT,
                        intentDoc(generation, nowMs, DrainIntent.QUIESCED, awaited),
                        nowMs,
                    ),
                    state(
                        CollectorStateKeys.FENCE_GENERATION,
                        JSONObject().put("generation", fenceGeneration).toString(),
                        nowMs,
                    ),
                    state(
                        CollectorStateKeys.SWEEP_FENCE_PREFIX + fenceId,
                        fence.toJson().toString(),
                        nowMs,
                    ),
                    state(CollectorStateKeys.CUTOVER_IMPORT, marker.toJson().toString(), nowMs),
                ),
            )
            CutoverCommit.Success(CutoverMode.DRAINING, generation)
        }
    }

    /**
     * Drains the legacy queue (design §8.2): snapshot → P9 partition
     * → idempotent import → verify → sweep-remove each imported
     * item. Requires `DRAINING` mode. The import is idempotent and
     * the sweep is resumable (sweep records), so a crashed drain can
     * be re-run safely (T10).
     */
    suspend fun drain(inspector: LegacyQueueInspector, nowMs: Long): DrainResult {
        val mode = getMode()
        if (mode != CutoverMode.DRAINING) {
            return DrainResult(false, 0, 0, 0, 0, 0, 0, false, "mode is $mode, expected DRAINING")
        }
        val marker = getPhaseMarker()
            ?: return DrainResult(false, 0, 0, 0, 0, 0, 0, false, "no phase marker; commitDraining first")
        if (marker.phase != "draining" && marker.phase != "drained") {
            return DrainResult(false, 0, 0, 0, 0, 0, 0, false, "phase marker is ${marker.phase}")
        }
        val snapshot = migration.snapshot(queue.queueSnapshot(), nowMs)
        val partitioned = migration.partition(snapshot, inspector)
        val report = migration.import(partitioned, nowMs)
        val verification = migration.verify(partitioned, queue.queueSnapshot(), nowMs)
        if (!verification.ok) {
            return DrainResult(
                ok = false,
                imported = report.deliveriesImported,
                removed = 0,
                skippedHeld = 0,
                quarantined = report.quarantinedExcluded,
                expiredSkipped = report.expiredSkipped,
                corrupt = report.corruptCount,
                verified = false,
                error = verification.detail,
            )
        }
        if (marker.phase == "draining") {
            val drained = marker.copy(
                phase = "drained",
                snapshotCount = snapshot.items.size,
                snapshotChecksum = snapshot.checksum,
                importableCount = partitioned.importable.size,
                importableChecksum = partitioned.importableChecksum,
                quarantinedCount = partitioned.quarantined.size,
                quarantineChecksum = partitioned.quarantineChecksum,
                importedCount = report.deliveriesImported,
                verified = true,
            )
            transactions.run {
                stateDao.put(state(CollectorStateKeys.CUTOVER_IMPORT, drained.toJson().toString(), nowMs))
            }
        }
        var removed = 0
        var skippedHeld = 0
        for (item in partitioned.importable) {
            when (val step = sweepStep(item, marker.fenceId, nowMs)) {
                SweepStepResult.Removed -> removed++
                SweepStepResult.SkippedHeld -> skippedHeld++
                is SweepStepResult.Failed -> return DrainResult(
                    ok = false,
                    imported = report.deliveriesImported,
                    removed = removed,
                    skippedHeld = skippedHeld,
                    quarantined = report.quarantinedExcluded,
                    expiredSkipped = report.expiredSkipped,
                    corrupt = report.corruptCount,
                    verified = true,
                    error = step.reason,
                )
            }
        }
        writeMigrationClear(marker.fenceId, nowMs)
        return DrainResult(
            ok = true,
            imported = report.deliveriesImported,
            removed = removed,
            skippedHeld = skippedHeld,
            quarantined = report.quarantinedExcluded,
            expiredSkipped = report.expiredSkipped,
            corrupt = report.corruptCount,
            verified = true,
            error = null,
        )
    }

    /**
     * Commits `DRAINING` → `WORKER_ONLY` (§8.4). Requires a verified
     * drain (phase marker `drained`). Releases the sweep fence.
     * There is no automatic return to `LEGACY_ONLY`.
     */
    suspend fun commitWorkerOnly(nowMs: Long): CutoverCommit {
        return transactions.run {
            val mode = getMode()
            val intent = getDrainIntent()
            if (mode != CutoverMode.DRAINING || intent != DrainIntent.QUIESCED) {
                return@run CutoverCommit.Rejected(
                    "cannot commit WORKER_ONLY from mode=$mode intent=$intent",
                )
            }
            val marker = getPhaseMarker()
            if (marker == null || marker.phase != "drained" || !marker.verified) {
                return@run CutoverCommit.Rejected("no verified drain; run drain() first")
            }
            stateDao.put(state(CollectorStateKeys.CUTOVER_STATE, modeDoc(CutoverMode.WORKER_ONLY), nowMs))
            stateDao.put(
                state(
                    CollectorStateKeys.CUTOVER_IMPORT,
                    marker.copy(phase = "worker_only").toJson().toString(),
                    nowMs,
                ),
            )
            stateDao.delete(CollectorStateKeys.SWEEP_FENCE_PREFIX + marker.fenceId)
            CutoverCommit.Success(CutoverMode.WORKER_ONLY, marker.drainGeneration)
        }
    }

    /**
     * Aborts the cutover back to `LEGACY_ONLY` / `ABORTED` (§8.4).
     * The sweep fence is released. Room events captured during
     * `DRAINING` stay in Room, visible — re-promotion to the legacy
     * queue is Stage D work, not done silently here.
     */
    suspend fun abortCutover(nowMs: Long): CutoverCommit {
        return transactions.run {
            val mode = getMode()
            if (mode != CutoverMode.DRAINING && mode != CutoverMode.LEGACY_ONLY) {
                return@run CutoverCommit.Rejected("cannot abort from mode=$mode")
            }
            val marker = getPhaseMarker()
            val generation = getDrainGeneration()
            stateDao.put(state(CollectorStateKeys.CUTOVER_STATE, modeDoc(CutoverMode.LEGACY_ONLY), nowMs))
            stateDao.put(
                state(
                    CollectorStateKeys.DRAIN_INTENT,
                    intentDoc(generation ?: 0L, nowMs, DrainIntent.ABORTED),
                    nowMs,
                ),
            )
            if (marker != null) {
                stateDao.put(
                    state(
                        CollectorStateKeys.CUTOVER_IMPORT,
                        marker.copy(phase = "aborted").toJson().toString(),
                        nowMs,
                    ),
                )
                stateDao.delete(CollectorStateKeys.SWEEP_FENCE_PREFIX + marker.fenceId)
            }
            CutoverCommit.Success(CutoverMode.LEGACY_ONLY, generation)
        }
    }

    // --- Sweep (§8.6) ---

    sealed interface SweepStepResult {
        data object Removed : SweepStepResult
        data object SkippedHeld : SweepStepResult
        data class Failed(val reason: String) : SweepStepResult
    }

    /** The transaction phase of a sweep step: distinct from the public result. */
    private sealed interface SweepStepTx {
        data object AlreadyRemoved : SweepStepTx
        data object SkippedHeld : SweepStepTx
        data object Proceed : SweepStepTx
        data class Failed(val reason: String) : SweepStepTx
    }

    /**
     * One sweep step (§8.6, (f′′)): fence install + intent → queue
     * removal → fence release + record. The per-event fence is
     * installed in Room BEFORE the queue is touched; `RELEASE`
     * commits only in the final Room transaction together with the
     * sweep record. Between fence installation and `RELEASE`, the
     * fenced copy cannot be dispatched even if the
     * SharedPreferences write fails: the fence stays `ACTIVE`, the
     * pick stays `HELD`, and zero POSTs follow for that copy. A
     * crash between the queue write and the record leaves the
     * intent behind; a re-run finds the queue item already gone
     * and completes the record — the sweep is resumable and never
     * removes an item twice.
     *
     * A `SKIP_HELD` step installs the fence and leaves it `ACTIVE`:
     * the held copy stays fenced across reboot until reconciled.
     */
    suspend fun sweepStep(item: LegacyQueueItem, fenceId: String, nowMs: Long): SweepStepResult {
        val decision: SweepStepTx = transactions.run {
            readDoc(CollectorStateKeys.SWEEP_FENCE_PREFIX + fenceId)
                ?: return@run SweepStepTx.Failed("sweep fence $fenceId released")
            val delivery = deliveryDao.getByLegacyQkey(item.qkey)
                ?: return@run SweepStepTx.Failed("no Room delivery for qkey ${item.qkey}")
            val existingRecord = readDoc(CollectorStateKeys.SWEEP_RECORD_PREFIX + item.dedupeKey)
            if (existingRecord != null && SweepRecord.fromJson(existingRecord).outcome == SweepRecord.OUTCOME_REMOVED) {
                return@run SweepStepTx.AlreadyRemoved
            }
            val eventFence = SweepFence(item.dedupeKey, "event_copy", getDrainGeneration() ?: 0L, nowMs)
            when (sweepDecision(delivery.state)) {
                SweepDecision.SKIP_HELD -> {
                    val record = SweepRecord(
                        dedupeKey = item.dedupeKey,
                        qkey = item.qkey,
                        outcome = SweepRecord.OUTCOME_SKIPPED_HELD,
                        state = delivery.state.name,
                        fenceId = fenceId,
                        recordedAtMs = nowMs,
                    )
                    stateDao.putAll(
                        listOf(
                            state(
                                CollectorStateKeys.SWEEP_FENCE_PREFIX + item.dedupeKey,
                                eventFence.toJson().toString(),
                                nowMs,
                            ),
                            state(
                                CollectorStateKeys.SWEEP_RECORD_PREFIX + item.dedupeKey,
                                record.toJson().toString(),
                                nowMs,
                            ),
                        ),
                    )
                    SweepStepTx.SkippedHeld
                }
                SweepDecision.REMOVE -> {
                    val intent = SweepIntent(
                        intentId = "$fenceId:${item.qkey}",
                        fenceId = fenceId,
                        qkey = item.qkey,
                        dedupeKey = item.dedupeKey,
                        nextRouteIndex = item.next,
                        createdAtMs = nowMs,
                    )
                    stateDao.putAll(
                        listOf(
                            state(
                                CollectorStateKeys.SWEEP_FENCE_PREFIX + item.dedupeKey,
                                eventFence.toJson().toString(),
                                nowMs,
                            ),
                            state(
                                CollectorStateKeys.SWEEP_INTENT_PREFIX + intent.intentId,
                                intent.toJson().toString(),
                                nowMs,
                            ),
                        ),
                    )
                    SweepStepTx.Proceed
                }
            }
        }
        when (decision) {
            SweepStepTx.AlreadyRemoved -> return SweepStepResult.Removed
            SweepStepTx.SkippedHeld -> return SweepStepResult.SkippedHeld
            is SweepStepTx.Failed -> return SweepStepResult.Failed(decision.reason)
            SweepStepTx.Proceed -> Unit
        }
        // The intent is durable; now remove the queue item (sender lock:
        // the queue write is synchronous and never suspends).
        val removedFromQueue = senderLock.withLock {
            val current = queue.queueSnapshot()
            val filtered = current.filter { it.optString("qkey") != item.qkey }
            if (filtered.size == current.size) {
                // Already gone (a previous crashed attempt removed it
                // after the intent but before the record): complete
                // the record below instead of failing.
                true
            } else {
                queue.saveQueueSync(filtered)
            }
        }
        if (!removedFromQueue) {
            return SweepStepResult.Failed("saveQueueSync failed for qkey ${item.qkey}; intent retained for retry")
        }
        transactions.run {
            val record = SweepRecord(
                dedupeKey = item.dedupeKey,
                qkey = item.qkey,
                outcome = SweepRecord.OUTCOME_REMOVED,
                state = null,
                fenceId = fenceId,
                recordedAtMs = nowMs,
            )
            stateDao.put(
                state(
                    CollectorStateKeys.SWEEP_RECORD_PREFIX + item.dedupeKey,
                    record.toJson().toString(),
                    nowMs,
                ),
            )
            stateDao.delete(CollectorStateKeys.SWEEP_INTENT_PREFIX + "$fenceId:${item.qkey}")
            // RELEASE commits in this transaction together with the record (f′′).
            stateDao.delete(CollectorStateKeys.SWEEP_FENCE_PREFIX + item.dedupeKey)
        }
        return SweepStepResult.Removed
    }

    /** Writes the step-(6) clear record from the durable sweep records (§8.2). */
    private suspend fun writeMigrationClear(fenceId: String, nowMs: Long) {
        transactions.run {
            val records = stateDao.getAll()
                .filter { it.key.startsWith(CollectorStateKeys.SWEEP_RECORD_PREFIX) }
                .map { SweepRecord.fromJson(JSONObject(it.valueJson)) }
                .filter { it.fenceId == fenceId && it.outcome == SweepRecord.OUTCOME_REMOVED }
            val removedQkeys = records.map { it.qkey }.sorted()
            val doc = JSONObject().apply {
                put("removed_count", removedQkeys.size)
                put("removed_checksum", CanonicalJson.sha256Hex(removedQkeys.joinToString("\n")))
                put("at_ms", nowMs)
            }
            stateDao.put(state(CollectorStateKeys.MIGRATION_CLEAR, doc.toString(), nowMs))
        }
    }

    // --- Admission (§8.5) ---

    /**
     * Admits one hop attempt atomically (§8.5(a)): a single
     * transaction reads the mode + drain generation + the sweep
     * fence for the event's `dedupe_key` + the route epochs and
     * writes the `ATTEMPT_STARTED` row. A rejected admission writes
     * nothing. Codes: `MODE` (the path is not the active sender for
     * this mode — the legacy flush only in `LEGACY_ONLY`, the Room
     * worker only in `WORKER_ONLY`), `DRAIN_PENDING` (a drain is in
     * progress), `FENCED` (sender fenced, or a sweep fence covers
     * the event), `EPOCH_STALE` (drain generation mismatch),
     * `LIVE_ATTEMPT` (one live attempt per hop).
     *
     * The fence generation recorded on the row is the current
     * generation read in this transaction — the attempt does not
     * present one. A barrier installed after admission never
     * invalidates an admitted attempt; it awaits it (§8.5(d)).
     */
    suspend fun admitAttempt(
        request: AdmissionRequest,
        path: AdmissionPath,
        nowMs: Long,
    ): AdmissionResult {
        return transactions.run {
            val mode = getMode()
            val modeOk = when (path) {
                AdmissionPath.LEGACY -> mode == CutoverMode.LEGACY_ONLY
                AdmissionPath.WORKER -> mode == CutoverMode.WORKER_ONLY
            }
            if (!modeOk) {
                return@run AdmissionResult.Rejected(AdmissionRejection.MODE)
            }
            if (getDrainIntent() == DrainIntent.PENDING) {
                return@run AdmissionResult.Rejected(AdmissionRejection.DRAIN_PENDING)
            }
            if (isSenderFenced()) {
                return@run AdmissionResult.Rejected(AdmissionRejection.FENCED)
            }
            if (readDoc(CollectorStateKeys.SWEEP_FENCE_PREFIX + request.dedupeKey) != null) {
                return@run AdmissionResult.Rejected(AdmissionRejection.FENCED)
            }
            val marker = getPhaseMarker()
            val drainFenceActive = marker != null &&
                readDoc(CollectorStateKeys.SWEEP_FENCE_PREFIX + marker.fenceId) != null
            if (drainFenceActive) {
                return@run AdmissionResult.Rejected(AdmissionRejection.FENCED)
            }
            if (request.drainGeneration != getDrainGeneration()) {
                return@run AdmissionResult.Rejected(AdmissionRejection.EPOCH_STALE)
            }
            if (attemptDao.getUnresolvedForHop(request.deliveryId, request.hopIndex).isNotEmpty()) {
                return@run AdmissionResult.Rejected(AdmissionRejection.LIVE_ATTEMPT)
            }
            val attemptId = UUID.randomUUID().toString()
            attemptDao.insert(
                DeliveryAttemptEntity(
                    attemptId = attemptId,
                    deliveryId = request.deliveryId,
                    routeId = request.routeId,
                    attemptNo = request.attemptNo,
                    startedAtMs = nowMs,
                    state = AttemptState.ATTEMPT_STARTED,
                    hopIndex = request.hopIndex,
                    consentEpoch = request.consentEpoch,
                    configEpoch = request.configEpoch,
                    urlSha256 = request.urlSha256,
                    dedupeKey = request.dedupeKey,
                    ownerBootId = request.ownerBootId,
                    drainGeneration = request.drainGeneration,
                    fenceGeneration = getFenceGeneration(),
                ),
            )
            AdmissionResult.Admitted(attemptId)
        }
    }

    // --- Orphan recovery (§8.5(b)) ---

    /**
     * Recovers orphaned attempts after a process death (§8.5(b)).
     * The startup fence is raised while recovery runs and cleared
     * afterwards (§8.5(c)). Stale `ATTEMPT_STARTED` rows are
     * abandoned (only `ADMITTED` rows older than the admission
     * lease may be abandoned); orphaned `ATTEMPT_STARTED` /
     * `SOCKET_OPENING` rows become `NEVER_SENT` only when the F40
     * gate has passed — otherwise they stay unresolved and their
     * deliveries classify `UNKNOWN` at the delivery level.
     * `SOCKET_OPEN` orphans are never reclassified here; their
     * deliveries hold for reconciliation.
     */
    suspend fun recoverOrphanedAttempts(currentBootId: String, nowMs: Long): RecoveryReport {
        transactions.run {
            stateDao.put(
                state(
                    CollectorStateKeys.SENDER_FENCED,
                    JSONObject().put("fenced", true).put("updated_at_ms", nowMs).toString(),
                    nowMs,
                ),
            )
        }
        val abandoned = attemptDao.abandonStaleAdmissions(nowMs - ADMISSION_LEASE_MS, nowMs)
        val orphans = attemptDao.getOrphaned(currentBootId)
        var neverSent = 0
        var unknownHeld = 0
        for (orphan in orphans) {
            when (orphan.state) {
                AttemptState.ATTEMPT_STARTED, AttemptState.SOCKET_OPENING -> {
                    if (neverSentGatePassed) {
                        neverSent += attemptDao.markNeverSent(orphan.attemptId, nowMs)
                    } else {
                        unknownHeld++
                        // §8.5(g): the hop is unknown — hold its
                        // delivery so it never dispatches on unknown
                        // evidence. Without this the delivery wedges
                        // IN_FLIGHT forever (reclaim skips deliveries
                        // with unresolved markers; the worker never
                        // posts past one).
                        deliveryDao.setStateIfNotTerminal(
                            orphan.deliveryId,
                            DeliveryState.HELD_AWAITING_RECONCILIATION,
                            nowMs,
                        )
                    }
                }
                AttemptState.SOCKET_OPEN -> {
                    unknownHeld++
                    deliveryDao.setStateIfNotTerminal(
                        orphan.deliveryId,
                        DeliveryState.HELD_AWAITING_RECONCILIATION,
                        nowMs,
                    )
                }
                else -> Unit
            }
        }
        val stuckOpening = attemptDao.getStuckOpening(nowMs - OPENING_ALERT_MS).size
        transactions.run {
            stateDao.put(
                state(
                    CollectorStateKeys.SENDER_FENCED,
                    JSONObject().put("fenced", false).put("updated_at_ms", nowMs).toString(),
                    nowMs,
                ),
            )
        }
        return RecoveryReport(abandoned, neverSent, unknownHeld, orphans.size, stuckOpening)
    }

    // --- Document helpers ---

    private fun state(key: String, valueJson: String, nowMs: Long): CollectorStateEntity =
        CollectorStateEntity(key, valueJson, nowMs)

    private suspend fun readDoc(key: String): JSONObject? {
        val raw = stateDao.getValue(key) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun modeDoc(mode: CutoverMode): String =
        JSONObject().put("mode", mode.name).put("blocked", false).toString()

    private fun intentDoc(
        generation: Long,
        nowMs: Long,
        intent: DrainIntent,
        awaitedAttemptIds: List<String> = emptyList(),
    ): String =
        JSONObject().apply {
            put("generation", generation)
            put("requested_at_ms", nowMs)
            put("state", intent.name)
            put("requested_by", "cutover_coordinator")
            if (awaitedAttemptIds.isNotEmpty()) {
                put("awaited_attempt_ids", org.json.JSONArray(awaitedAttemptIds))
            }
        }.toString()

    /** The awaited set snapshotted by `requestDrain` (empty when none was snapshotted). */
    private suspend fun readAwaitedAttemptIds(): List<String> {
        val doc = readDoc(CollectorStateKeys.DRAIN_INTENT) ?: return emptyList()
        val arr = doc.optJSONArray("awaited_attempt_ids") ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }
}
