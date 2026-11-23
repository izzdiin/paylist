package com.netter.paylist

import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.TransactionRunner
import com.netter.paylist.data.dao.AttemptDao
import com.netter.paylist.data.dao.AttributionOutcome
import com.netter.paylist.data.dao.CaptureLogDao
import com.netter.paylist.data.dao.CollectorStateDao
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.data.dao.DeliveryStateCount
import com.netter.paylist.data.dao.EventDao
import com.netter.paylist.data.dao.MatchResultDao
import com.netter.paylist.data.dao.OrderRegistryDao
import com.netter.paylist.data.dao.RegistryImportMetadataDao
import com.netter.paylist.data.entity.AttemptState
import com.netter.paylist.data.entity.CaptureLogEntity
import com.netter.paylist.data.entity.CaptureStagingDao
import com.netter.paylist.data.entity.CaptureStagingEntity
import com.netter.paylist.data.entity.ClassificationDao
import com.netter.paylist.data.entity.ClassificationEntity
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.DeliveryAttemptEntity
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.data.entity.MatchResultEntity
import com.netter.paylist.data.entity.OrderRegistryEntity
import com.netter.paylist.data.entity.PromotionReceiptEntity
import com.netter.paylist.data.entity.RegistryImportMetadataEntity
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Shared fake DAOs for JVM unit tests (no Robolectric): an
 * in-memory store behind the real DAO interfaces, mirroring the
 * Room SQL semantics the tests rely on — `INSERT OR IGNORE`
 * returning -1 on a unique conflict (never 0), conditional
 * lease-guarded UPDATEs returning 0/1, and the real
 * [DeliveryDao.claimBatch]/[DeliveryDao.reclaimExpiredLeases]
 * transaction bodies inherited from the DAO itself.
 *
 * Extracted from `CutoverTest` (Stage B) in Stage C so the worker
 * tests reuse the same fakes instead of duplicating them.
 */

/** The shared durable store behind all fake DAOs (the "database"). */
class FakeStore {
    val lock: Lock = ReentrantLock()
    val events = LinkedHashMap<String, EventEntity>()
    val eventsByDedupe = LinkedHashMap<String, EventEntity>()
    val eventsByExportRef = LinkedHashMap<String, EventEntity>()
    val deliveries = LinkedHashMap<String, DeliveryEntity>()
    val deliveriesByQkey = LinkedHashMap<String, DeliveryEntity>()
    val attempts = LinkedHashMap<String, DeliveryAttemptEntity>()
    val collector = LinkedHashMap<String, CollectorStateEntity>()
    val receipts = LinkedHashMap<String, PromotionReceiptEntity>()
    val staging = LinkedHashMap<String, CaptureStagingEntity>()
    val classifications = LinkedHashMap<String, ClassificationEntity>()
    /** Keyed `merchant_id|order_number` (the P3(b) uniqueness scope). */
    val registry = LinkedHashMap<String, OrderRegistryEntity>()
    val matchResults = LinkedHashMap<String, MatchResultEntity>()
    /** Keyed by `registry_version` (the PK). */
    val registryImports = LinkedHashMap<String, RegistryImportMetadataEntity>()
    /** Durable capture log (LH-04), keyed by `capture_id`. */
    val captureLog = LinkedHashMap<String, CaptureLogEntity>()

    data class Snapshot(
        val events: Map<String, EventEntity>,
        val eventsByDedupe: Map<String, EventEntity>,
        val eventsByExportRef: Map<String, EventEntity>,
        val deliveries: Map<String, DeliveryEntity>,
        val deliveriesByQkey: Map<String, DeliveryEntity>,
        val attempts: Map<String, DeliveryAttemptEntity>,
        val collector: Map<String, CollectorStateEntity>,
        val receipts: Map<String, PromotionReceiptEntity>,
        val staging: Map<String, CaptureStagingEntity>,
        val classifications: Map<String, ClassificationEntity>,
        val registry: Map<String, OrderRegistryEntity>,
        val matchResults: Map<String, MatchResultEntity>,
        val registryImports: Map<String, RegistryImportMetadataEntity>,
        val captureLog: Map<String, CaptureLogEntity>,
    )

    /** Entities are immutable data classes, so a shallow map copy is a full snapshot. */
    fun snapshot(): Snapshot = Snapshot(
        events.toMap(),
        eventsByDedupe.toMap(),
        eventsByExportRef.toMap(),
        deliveries.toMap(),
        deliveriesByQkey.toMap(),
        attempts.toMap(),
        collector.toMap(),
        receipts.toMap(),
        staging.toMap(),
        classifications.toMap(),
        registry.toMap(),
        matchResults.toMap(),
        registryImports.toMap(),
        captureLog.toMap(),
    )

    fun restore(s: Snapshot) {
        events.clear(); events.putAll(s.events)
        eventsByDedupe.clear(); eventsByDedupe.putAll(s.eventsByDedupe)
        eventsByExportRef.clear(); eventsByExportRef.putAll(s.eventsByExportRef)
        deliveries.clear(); deliveries.putAll(s.deliveries)
        deliveriesByQkey.clear(); deliveriesByQkey.putAll(s.deliveriesByQkey)
        attempts.clear(); attempts.putAll(s.attempts)
        collector.clear(); collector.putAll(s.collector)
        receipts.clear(); receipts.putAll(s.receipts)
        staging.clear(); staging.putAll(s.staging)
        classifications.clear(); classifications.putAll(s.classifications)
        registry.clear(); registry.putAll(s.registry)
        matchResults.clear(); matchResults.putAll(s.matchResults)
        registryImports.clear(); registryImports.putAll(s.registryImports)
        captureLog.clear(); captureLog.putAll(s.captureLog)
    }
}

/**
 * Serialises transactions on one lock (as Room's single-threaded
 * transaction executor does) and rolls back on failure by
 * restoring the pre-transaction snapshot.
 */
class FakeTransactionRunner(private val store: FakeStore) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T {
        store.lock.lock()
        val snapshot = store.snapshot()
        try {
            return block()
        } catch (e: Throwable) {
            store.restore(snapshot)
            throw e
        } finally {
            store.lock.unlock()
        }
    }
}

class FakeEventDao(private val store: FakeStore) : EventDao {
    /** Test hook: the next insert throws, simulating a Room-insert failure (F12). */
    var failNextInsert: Boolean = false

    // Room IGNORE insert returns -1 on a unique conflict
    // (never 0): the fakes must model that, or they mask
    // the real duplicate-detection behaviour.
    override suspend fun insert(event: EventEntity): Long = store.lock.withLock {
        if (failNextInsert) {
            failNextInsert = false
            throw IllegalStateException("injected insert failure")
        }
        if (store.events.containsKey(event.eventId) ||
            store.eventsByDedupe.containsKey(event.dedupeKey) ||
            store.eventsByExportRef.containsKey(event.exportRef)
        ) {
            return@withLock -1L
        }
        store.events[event.eventId] = event
        store.eventsByDedupe[event.dedupeKey] = event
        store.eventsByExportRef[event.exportRef] = event
        1L
    }

    override suspend fun getById(id: String): EventEntity? = store.lock.withLock { store.events[id] }
    override suspend fun getByDedupeKey(key: String): EventEntity? = store.lock.withLock { store.eventsByDedupe[key] }
    override suspend fun getByExportRef(ref: String): EventEntity? = store.lock.withLock { store.eventsByExportRef[ref] }
    override suspend fun getBySourceAccountKey(key: String): List<EventEntity> = store.lock.withLock {
        store.events.values.filter { it.sourceAccountKey == key }
    }
    override suspend fun count(): Int = store.lock.withLock { store.events.size }

    override suspend fun nullAllSourceAccountKeys(): Int = store.lock.withLock {
        var n = 0
        for ((id, event) in store.events.toMap()) {
            if (event.sourceAccountKey != null) {
                val nulled = event.copy(sourceAccountKey = null)
                store.events[id] = nulled
                store.eventsByDedupe[event.dedupeKey] = nulled
                store.eventsByExportRef[event.exportRef] = nulled
                n++
            }
        }
        n
    }

    override suspend fun deleteExpiredHeartbeatTest(before: Long): Int = store.lock.withLock {
        var deleted = 0
        for (event in store.events.values.toList()) {
            if (event.category != "heartbeat" && event.category != "test") continue
            val eventDeliveries = store.deliveries.values.filter { it.eventId == event.eventId }
            if (eventDeliveries.any { it.state != DeliveryState.ACKED && it.state != DeliveryState.EXPIRED }) continue
            val latestTerminal = eventDeliveries.mapNotNull { it.terminalAtMs }.maxOrNull() ?: continue
            if (latestTerminal >= before) continue
            store.events.remove(event.eventId)
            store.eventsByDedupe.remove(event.dedupeKey)
            store.eventsByExportRef.remove(event.exportRef)
            for (delivery in eventDeliveries) {
                store.deliveries.remove(delivery.deliveryId)
                delivery.legacyQkey?.let { store.deliveriesByQkey.remove(it) }
                store.attempts.entries.removeIf { it.value.deliveryId == delivery.deliveryId }
            }
            // FK SET NULL on capture_log (mirrors the real schema).
            for ((captureId, row) in store.captureLog.toMap()) {
                if (row.eventId == event.eventId) {
                    store.captureLog[captureId] = row.copy(eventId = null)
                }
            }
            deleted++
        }
        deleted
    }

    /**
     * Per-record local deletion (LH-07(e)): cascades to deliveries
     * + attempts (mirroring the FK CASCADE); linked capture-log
     * rows are kept with `event_id` SET NULL (mirroring the FK
     * SET NULL on `capture_log`).
     */
    override suspend fun deleteById(id: String): Int = store.lock.withLock {
        val event = store.events.remove(id) ?: return@withLock 0
        store.eventsByDedupe.remove(event.dedupeKey)
        store.eventsByExportRef.remove(event.exportRef)
        val eventDeliveries = store.deliveries.values.filter { it.eventId == id }
        for (delivery in eventDeliveries) {
            store.deliveries.remove(delivery.deliveryId)
            delivery.legacyQkey?.let { store.deliveriesByQkey.remove(it) }
            store.attempts.entries.removeIf { it.value.deliveryId == delivery.deliveryId }
        }
        for ((captureId, row) in store.captureLog.toMap()) {
            if (row.eventId == id) {
                store.captureLog[captureId] = row.copy(eventId = null)
            }
        }
        1
    }
}

class FakeDeliveryDao(private val store: FakeStore) : DeliveryDao {
    override suspend fun insert(delivery: DeliveryEntity): Long = store.lock.withLock {
        if (store.deliveries.containsKey(delivery.deliveryId)) return@withLock -1L
        val qkey = delivery.legacyQkey
        if (qkey != null && store.deliveriesByQkey.containsKey(qkey)) return@withLock -1L
        store.deliveries[delivery.deliveryId] = delivery
        if (qkey != null) store.deliveriesByQkey[qkey] = delivery
        1L
    }

    override suspend fun getById(id: String): DeliveryEntity? = store.lock.withLock { store.deliveries[id] }
    override suspend fun getByLegacyQkey(qkey: String): DeliveryEntity? = store.lock.withLock { store.deliveriesByQkey[qkey] }
    override suspend fun getByEventId(eventId: String): List<DeliveryEntity> = store.lock.withLock {
        store.deliveries.values.filter { it.eventId == eventId }
    }
    override suspend fun getByState(state: DeliveryState): List<DeliveryEntity> = store.lock.withLock {
        store.deliveries.values.filter { it.state == state }
    }
    override suspend fun countByState(): List<DeliveryStateCount> = store.lock.withLock {
        store.deliveries.values.groupBy { it.state }.map { (state, rows) -> DeliveryStateCount(state, rows.size) }
    }
    override suspend fun countDue(now: Long): Int = store.lock.withLock {
        store.deliveries.values.count {
            (it.state == DeliveryState.PENDING || it.state == DeliveryState.RETRY_WAIT) &&
                (it.nextRetryAtMs == null || it.nextRetryAtMs <= now)
        }
    }
    override suspend fun getClaimedSince(sinceMs: Long): List<DeliveryEntity> = store.lock.withLock {
        store.deliveries.values.filter {
            it.createdAtMs >= sinceMs &&
                (it.state == DeliveryState.IN_FLIGHT || it.state == DeliveryState.ACKED || it.state == DeliveryState.RETRY_WAIT)
        }
    }
    override suspend fun getRecentProblematic(limit: Int): List<DeliveryEntity> = store.lock.withLock {
        store.deliveries.values
            .filter { it.state == DeliveryState.BLOCKED || it.state == DeliveryState.RETRY_WAIT }
            .sortedByDescending { it.updatedAtMs }
            .take(limit)
    }
    override suspend fun getAuditSet(): List<DeliveryEntity> = store.lock.withLock {
        store.deliveries.values
            .filter {
                it.lastApplyStatus == "skip" || it.lastApplyStatus == "error" ||
                    (it.state == DeliveryState.ACKED && it.lastApplyStatus == null)
            }
            .sortedByDescending { it.updatedAtMs }
    }

    override suspend fun readCollectorValue(key: String): String? = store.lock.withLock { store.collector[key]?.valueJson }

    override suspend fun selectDue(limit: Int, now: Long): List<DeliveryEntity> = store.lock.withLock {
        store.deliveries.values
            .filter {
                (it.state == DeliveryState.PENDING || it.state == DeliveryState.RETRY_WAIT) &&
                    (it.nextRetryAtMs == null || it.nextRetryAtMs <= now)
            }
            .sortedBy { it.createdAtMs }
            .take(limit)
    }

    override suspend fun claimRow(id: String, leaseOwner: String, leaseUntilMs: Long, now: Long): Int = store.lock.withLock {
        val row = store.deliveries[id] ?: return@withLock 0
        if (row.state != DeliveryState.PENDING && row.state != DeliveryState.RETRY_WAIT) return@withLock 0
        val claimed = row.copy(
            state = DeliveryState.IN_FLIGHT,
            leaseOwner = leaseOwner,
            leaseUntilMs = leaseUntilMs,
            attempts = row.attempts + 1,
            updatedAtMs = now,
        )
        store.deliveries[id] = claimed
        claimed.legacyQkey?.let { store.deliveriesByQkey[it] = claimed }
        1
    }

    override suspend fun selectExpiredLeases(now: Long): List<DeliveryEntity> = store.lock.withLock {
        store.deliveries.values.filter { delivery ->
            delivery.state == DeliveryState.IN_FLIGHT &&
                delivery.leaseUntilMs != null && delivery.leaseUntilMs <= now &&
                store.attempts.values.none {
                    it.deliveryId == delivery.deliveryId && it.state in AttemptDao.UNRESOLVED_STATES
                }
        }
    }

    override suspend fun reclaimRow(id: String, now: Long): Int = store.lock.withLock {
        val row = store.deliveries[id] ?: return@withLock 0
        if (row.state != DeliveryState.IN_FLIGHT) return@withLock 0
        val reclaimed = row.copy(state = DeliveryState.PENDING, leaseOwner = null, leaseUntilMs = null, updatedAtMs = now)
        store.deliveries[id] = reclaimed
        reclaimed.legacyQkey?.let { store.deliveriesByQkey[it] = reclaimed }
        1
    }

    override suspend fun ackIfLeaseHeld(
        id: String,
        leaseOwner: String,
        now: Long,
        matchStatus: String?,
        applyStatus: String?,
    ): Int = store.lock.withLock {
        val row = store.deliveries[id] ?: return@withLock 0
        if (!leaseHeld(row, leaseOwner, now)) return@withLock 0
        val acked = row.copy(
            state = DeliveryState.ACKED, ackedAtMs = now, terminalAtMs = now,
            lastMatchStatus = matchStatus, lastApplyStatus = applyStatus, updatedAtMs = now,
        )
        store.deliveries[id] = acked
        acked.legacyQkey?.let { store.deliveriesByQkey[it] = acked }
        1
    }

    override suspend fun retryWaitIfLeaseHeld(
        id: String,
        leaseOwner: String,
        now: Long,
        nextRetryAtMs: Long,
        errorCode: String?,
        errorSummary: String?,
        nextRouteIndex: Int,
        hopOutcomesJson: String,
    ): Int = store.lock.withLock {
        val row = store.deliveries[id] ?: return@withLock 0
        if (!leaseHeld(row, leaseOwner, now)) return@withLock 0
        val waiting = row.copy(
            state = DeliveryState.RETRY_WAIT, nextRetryAtMs = nextRetryAtMs,
            lastErrorCode = errorCode, lastErrorSummary = errorSummary,
            nextRouteIndex = nextRouteIndex, hopOutcomesJson = hopOutcomesJson, updatedAtMs = now,
        )
        store.deliveries[id] = waiting
        waiting.legacyQkey?.let { store.deliveriesByQkey[it] = waiting }
        1
    }

    override suspend fun blockIfLeaseHeld(
        id: String,
        leaseOwner: String,
        now: Long,
        errorCode: String?,
        errorSummary: String?,
    ): Int = store.lock.withLock {
        val row = store.deliveries[id] ?: return@withLock 0
        if (!leaseHeld(row, leaseOwner, now)) return@withLock 0
        val blocked = row.copy(
            state = DeliveryState.BLOCKED, lastErrorCode = errorCode,
            lastErrorSummary = errorSummary, updatedAtMs = now,
        )
        store.deliveries[id] = blocked
        blocked.legacyQkey?.let { store.deliveriesByQkey[it] = blocked }
        1
    }

    override suspend fun holdForReconciliationIfLeaseHeld(
        id: String,
        leaseOwner: String,
        now: Long,
        errorCode: String?,
        errorSummary: String?,
        nextRouteIndex: Int,
        hopOutcomesJson: String,
    ): Int = store.lock.withLock {
        val row = store.deliveries[id] ?: return@withLock 0
        if (!leaseHeld(row, leaseOwner, now)) return@withLock 0
        val held = row.copy(
            state = DeliveryState.HELD_AWAITING_RECONCILIATION,
            lastErrorCode = errorCode, lastErrorSummary = errorSummary,
            nextRouteIndex = nextRouteIndex, hopOutcomesJson = hopOutcomesJson, updatedAtMs = now,
        )
        store.deliveries[id] = held
        held.legacyQkey?.let { store.deliveriesByQkey[it] = held }
        1
    }

    override suspend fun holdIfLeaseHeld(
        id: String,
        leaseOwner: String,
        now: Long,
        to: DeliveryState,
        errorCode: String?,
        errorSummary: String?,
        rawLost: Boolean,
        nextRouteIndex: Int,
        hopOutcomesJson: String,
    ): Int = store.lock.withLock {
        val row = store.deliveries[id] ?: return@withLock 0
        if (!leaseHeld(row, leaseOwner, now)) return@withLock 0
        val held = row.copy(
            state = to, lastErrorCode = errorCode, lastErrorSummary = errorSummary,
            rawLost = rawLost, nextRouteIndex = nextRouteIndex,
            hopOutcomesJson = hopOutcomesJson, updatedAtMs = now,
        )
        store.deliveries[id] = held
        held.legacyQkey?.let { store.deliveriesByQkey[it] = held }
        1
    }

    override suspend fun markRawLostIfLeaseHeld(
        id: String,
        leaseOwner: String,
        now: Long,
    ): Int = store.lock.withLock {
        val row = store.deliveries[id] ?: return@withLock 0
        if (!leaseHeld(row, leaseOwner, now)) return@withLock 0
        val marked = row.copy(rawLost = true, updatedAtMs = now)
        store.deliveries[id] = marked
        marked.legacyQkey?.let { store.deliveriesByQkey[it] = marked }
        1
    }

    override suspend fun checkpointHopIfLeaseHeld(
        id: String,
        leaseOwner: String,
        now: Long,
        nextRouteIndex: Int,
        hopOutcomesJson: String,
    ): Int = store.lock.withLock {
        val row = store.deliveries[id] ?: return@withLock 0
        if (!leaseHeld(row, leaseOwner, now)) return@withLock 0
        val checkpointed = row.copy(
            nextRouteIndex = nextRouteIndex, hopOutcomesJson = hopOutcomesJson, updatedAtMs = now,
        )
        store.deliveries[id] = checkpointed
        checkpointed.legacyQkey?.let { store.deliveriesByQkey[it] = checkpointed }
        1
    }

    override suspend fun setStateIfNotTerminal(id: String, to: DeliveryState, now: Long): Int = store.lock.withLock {
        val row = store.deliveries[id] ?: return@withLock 0
        if (row.state == DeliveryState.ACKED || row.state == DeliveryState.EXPIRED || row.state == DeliveryState.DISCARDED) {
            return@withLock 0
        }
        val updated = row.copy(state = to, updatedAtMs = now)
        store.deliveries[id] = updated
        updated.legacyQkey?.let { store.deliveriesByQkey[it] = updated }
        1
    }

    override suspend fun markExpired(id: String, now: Long): Int = store.lock.withLock {
        val row = store.deliveries[id] ?: return@withLock 0
        if (row.state != DeliveryState.RETRY_WAIT) return@withLock 0
        val expired = row.copy(state = DeliveryState.EXPIRED, terminalAtMs = now, updatedAtMs = now)
        store.deliveries[id] = expired
        expired.legacyQkey?.let { store.deliveriesByQkey[it] = expired }
        1
    }

    override suspend fun retryManual(id: String, now: Long): Int = store.lock.withLock {
        val row = store.deliveries[id] ?: return@withLock 0
        if (row.state != DeliveryState.BLOCKED) return@withLock 0
        val retried = row.copy(
            state = DeliveryState.PENDING, attempts = 0, nextRetryAtMs = null,
            leaseOwner = null, leaseUntilMs = null, lastErrorCode = null,
            lastErrorSummary = null, updatedAtMs = now,
        )
        store.deliveries[id] = retried
        retried.legacyQkey?.let { store.deliveriesByQkey[it] = retried }
        1
    }

    override suspend fun deleteById(id: String): Int = store.lock.withLock {
        val row = store.deliveries.remove(id) ?: return@withLock 0
        row.legacyQkey?.let { store.deliveriesByQkey.remove(it) }
        1
    }

    private fun leaseHeld(row: DeliveryEntity, leaseOwner: String, now: Long): Boolean =
        row.state == DeliveryState.IN_FLIGHT &&
            row.leaseOwner == leaseOwner &&
            row.leaseUntilMs != null && row.leaseUntilMs > now
}

class FakeAttemptDao(private val store: FakeStore) : AttemptDao {
    override suspend fun insert(attempt: DeliveryAttemptEntity): Unit = store.lock.withLock {
        // Room @Insert without a conflict strategy ABORTs on a
        // duplicate primary key. On-device that is a
        // SQLiteConstraintException; on the JVM the fake throws
        // IllegalStateException — the invariant asserted is the
        // same: no second row, the insert fails.
        if (store.attempts.containsKey(attempt.attemptId)) {
            throw IllegalStateException("attempt_id already exists: ${attempt.attemptId}")
        }
        store.attempts[attempt.attemptId] = attempt
    }

    override suspend fun getById(id: String): DeliveryAttemptEntity? = store.lock.withLock { store.attempts[id] }
    override suspend fun getByDeliveryId(deliveryId: String): List<DeliveryAttemptEntity> = store.lock.withLock {
        store.attempts.values.filter { it.deliveryId == deliveryId }.sortedBy { it.attemptNo }
    }
    override suspend fun getUnresolvedForHop(deliveryId: String, hop: Int): List<DeliveryAttemptEntity> = store.lock.withLock {
        store.attempts.values.filter {
            it.deliveryId == deliveryId && it.hopIndex == hop && it.state in AttemptDao.UNRESOLVED_STATES
        }
    }
    override suspend fun getUnresolvedByDedupeKey(dedupeKey: String): List<DeliveryAttemptEntity> = store.lock.withLock {
        store.attempts.values.filter { it.dedupeKey == dedupeKey && it.state in AttemptDao.UNRESOLVED_STATES }
    }
    override suspend fun getAllUnresolved(): List<DeliveryAttemptEntity> = store.lock.withLock {
        store.attempts.values.filter { it.state in AttemptDao.UNRESOLVED_STATES }
    }
    override suspend fun getUnresolvedByOwner(bootId: String): List<DeliveryAttemptEntity> = store.lock.withLock {
        store.attempts.values.filter { it.ownerBootId == bootId && it.state in AttemptDao.UNRESOLVED_STATES }
    }
    override suspend fun countUnresolvedByOwner(bootId: String): Int = store.lock.withLock {
        store.attempts.values.count { it.ownerBootId == bootId && it.state in AttemptDao.UNRESOLVED_STATES }
    }
    override suspend fun getOrphaned(currentBootId: String): List<DeliveryAttemptEntity> = store.lock.withLock {
        store.attempts.values.filter { it.ownerBootId != currentBootId && it.state in AttemptDao.UNRESOLVED_STATES }
    }
    override suspend fun getStuckOpening(before: Long): List<DeliveryAttemptEntity> = store.lock.withLock {
        store.attempts.values.filter { it.state == AttemptState.SOCKET_OPENING && it.startedAtMs < before }
    }

    override suspend fun claimForSocketOpen(id: String): Int = store.lock.withLock {
        transition(id, AttemptState.ATTEMPT_STARTED, AttemptState.SOCKET_OPENING)
    }
    override suspend fun markSocketOpen(id: String): Int = store.lock.withLock {
        transition(id, AttemptState.SOCKET_OPENING, AttemptState.SOCKET_OPEN)
    }

    override suspend fun markCompleted(
        id: String,
        now: Long,
        httpStatus: Int?,
        resultClass: String?,
        errorCode: String?,
        durationMs: Long?,
        responseStatus: String?,
        hopOutcomesJson: String,
    ): Int = store.lock.withLock {
        val row = store.attempts[id] ?: return@withLock 0
        if (row.state !in AttemptDao.UNRESOLVED_STATES) return@withLock 0
        store.attempts[id] = row.copy(
            state = AttemptState.COMPLETED, finishedAtMs = now, httpStatus = httpStatus,
            resultClass = resultClass, errorCode = errorCode, durationMs = durationMs,
            responseStatus = responseStatus, hopOutcomesJson = hopOutcomesJson,
        )
        1
    }

    override suspend fun abandonIfAdmitted(id: String, now: Long): Int = store.lock.withLock {
        val row = store.attempts[id] ?: return@withLock 0
        if (row.state != AttemptState.ATTEMPT_STARTED) return@withLock 0
        store.attempts[id] = row.copy(state = AttemptState.ABANDONED_PRE_SOCKET, finishedAtMs = now)
        1
    }

    override suspend fun abandonStaleAdmissions(before: Long, now: Long): Int = store.lock.withLock {
        var n = 0
        for ((id, row) in store.attempts.toMap()) {
            if (row.state == AttemptState.ATTEMPT_STARTED && row.startedAtMs < before) {
                store.attempts[id] = row.copy(state = AttemptState.ABANDONED_PRE_SOCKET, finishedAtMs = now)
                n++
            }
        }
        n
    }

    override suspend fun markNeverSent(id: String, now: Long): Int = store.lock.withLock {
        val row = store.attempts[id] ?: return@withLock 0
        if (row.state != AttemptState.ATTEMPT_STARTED && row.state != AttemptState.SOCKET_OPENING) return@withLock 0
        store.attempts[id] = row.copy(state = AttemptState.NEVER_SENT, finishedAtMs = now)
        1
    }

    override suspend fun pruneToLatest(deliveryId: String, keep: Int): Int = store.lock.withLock {
        val rows = store.attempts.values.filter { it.deliveryId == deliveryId }.sortedByDescending { it.startedAtMs }
        val surplus = rows.drop(keep)
        surplus.forEach { store.attempts.remove(it.attemptId) }
        surplus.size
    }

    private fun transition(id: String, from: AttemptState, to: AttemptState): Int {
        val row = store.attempts[id] ?: return 0
        if (row.state != from) return 0
        store.attempts[id] = row.copy(state = to)
        return 1
    }
}

class FakeCollectorStateDao(private val store: FakeStore) : CollectorStateDao {
    /** Test hook: the Nth `put` call throws, simulating a kill mid-commit (F27). 0 = never. */
    var failOnPutNumber: Int = 0
    var putCount: Int = 0
        private set

    override suspend fun put(state: CollectorStateEntity): Unit = store.lock.withLock {
        putCount++
        if (failOnPutNumber > 0 && putCount == failOnPutNumber) {
            throw IllegalStateException("injected put failure #$putCount")
        }
        store.collector[state.key] = state
    }

    override suspend fun get(key: String): CollectorStateEntity? = store.lock.withLock { store.collector[key] }
    override suspend fun getValue(key: String): String? = store.lock.withLock { store.collector[key]?.valueJson }
    override suspend fun delete(key: String): Int = store.lock.withLock {
        if (store.collector.remove(key) != null) 1 else 0
    }
    override suspend fun getAll(): List<CollectorStateEntity> = store.lock.withLock { store.collector.values.toList() }
}

/** Stage D: fake `capture_staging` DAO (IGNORE insert, -1 on conflict). */
class FakeCaptureStagingDao(private val store: FakeStore) : CaptureStagingDao {
    override suspend fun insert(row: CaptureStagingEntity): Long = store.lock.withLock {
        if (store.staging.containsKey(row.legacyQkey)) return@withLock -1L
        store.staging[row.legacyQkey] = row
        1L
    }

    override suspend fun getByLegacyQkey(qkey: String): CaptureStagingEntity? =
        store.lock.withLock { store.staging[qkey] }

    override suspend fun getByDedupeKey(key: String): List<CaptureStagingEntity> =
        store.lock.withLock { store.staging.values.filter { it.dedupeKey == key } }

    override suspend fun getAll(): List<CaptureStagingEntity> = store.lock.withLock {
        store.staging.values.sortedBy { it.createdAtMs }
    }

    override suspend fun deleteByLegacyQkey(qkey: String): Int = store.lock.withLock {
        if (store.staging.remove(qkey) != null) 1 else 0
    }

    override suspend fun count(): Int = store.lock.withLock { store.staging.size }
}

/** Stage D: fake `classification_results` DAO (plain insert, ABORT on duplicate PK). */
class FakeClassificationDao(private val store: FakeStore) : ClassificationDao {
    override suspend fun insert(result: ClassificationEntity): Unit = store.lock.withLock {
        if (store.classifications.containsKey(result.classificationId)) {
            throw IllegalStateException("classification_id already exists: ${result.classificationId}")
        }
        store.classifications[result.classificationId] = result
    }

    override suspend fun getByEventId(eventId: String): List<ClassificationEntity> =
        store.lock.withLock { store.classifications.values.filter { it.eventId == eventId } }

    override suspend fun getByCategory(category: String): List<ClassificationEntity> =
        store.lock.withLock { store.classifications.values.filter { it.category == category } }
}

class FakeOrderRegistryDao(private val store: FakeStore) : OrderRegistryDao {
    override suspend fun lookup(merchantId: String, orderNumber: String): OrderRegistryEntity? =
        store.lock.withLock { store.registry["$merchantId|$orderNumber"] }

    override suspend fun getByMerchant(merchantId: String): List<OrderRegistryEntity> =
        store.lock.withLock { store.registry.values.filter { it.merchantId == merchantId } }

    override suspend fun countByMerchant(merchantId: String): Int =
        store.lock.withLock { store.registry.values.count { it.merchantId == merchantId } }

    override suspend fun deleteByMerchant(merchantId: String): Int = store.lock.withLock {
        val keys = store.registry.keys.filter { it.startsWith("$merchantId|") }
        keys.forEach { store.registry.remove(it) }
        keys.size
    }

    override suspend fun insertAll(rows: List<OrderRegistryEntity>): Unit = store.lock.withLock {
        for (row in rows) {
            val key = "${row.merchantId}|${row.orderNumber}"
            if (store.registry.containsKey(key)) {
                throw IllegalStateException("order_registry PK conflict: $key")
            }
            store.registry[key] = row
        }
    }

    /**
     * Mirrors Room's transaction serialisation for the default
     * `replaceSnapshot` (manual lock/unlock — `withLock` cannot
     * hold a suspend call; the nested DAO calls re-lock on the
     * same thread, as `FakeTransactionRunner` does).
     */
    override suspend fun replaceSnapshot(merchantId: String, rows: List<OrderRegistryEntity>) {
        store.lock.lock()
        try {
            super.replaceSnapshot(merchantId, rows)
        } finally {
            store.lock.unlock()
        }
    }
}

class FakeMatchResultDao(private val store: FakeStore) : MatchResultDao {
    override suspend fun insert(result: MatchResultEntity): Unit = store.lock.withLock {
        if (store.matchResults.containsKey(result.matchId)) {
            throw IllegalStateException("match_id already exists: ${result.matchId}")
        }
        // The unique index on attributed_order_key (NULLs distinct).
        val key = result.attributedOrderKey
        if (key != null && store.matchResults.values.any { it.attributedOrderKey == key }) {
            throw IllegalStateException("attributed_order_key conflict: $key")
        }
        store.matchResults[result.matchId] = result
    }

    override suspend fun getById(id: String): MatchResultEntity? =
        store.lock.withLock { store.matchResults[id] }

    override suspend fun getByEventId(eventId: String): List<MatchResultEntity> =
        store.lock.withLock { store.matchResults.values.filter { it.eventId == eventId } }

    override suspend fun getByOrder(merchantId: String, orderNumber: String): List<MatchResultEntity> =
        store.lock.withLock {
            store.matchResults.values.filter {
                it.merchantId == merchantId && it.orderNumber == orderNumber
            }
        }

    override suspend fun getNeedingReview(): List<MatchResultEntity> =
        store.lock.withLock {
            store.matchResults.values.filter {
                it.advisoryMatchStatus ==
                    com.netter.paylist.data.entity.AdvisoryMatchStatus.AMBIGUOUS ||
                    it.advisoryMatchStatus ==
                    com.netter.paylist.data.entity.AdvisoryMatchStatus.MANUAL_REVIEW
            }.sortedByDescending { it.createdAtMs }
        }

    override suspend fun getAttributed(merchantId: String, orderNumber: String): MatchResultEntity? =
        store.lock.withLock {
            store.matchResults.values.firstOrNull {
                it.merchantId == merchantId && it.orderNumber == orderNumber &&
                    it.attributionStatus == com.netter.paylist.data.entity.AttributionStatus.ATTRIBUTED
            }
        }

    /**
     * Mirrors Room's transaction serialisation for the default
     * `attribute` (manual lock/unlock — see [FakeOrderRegistryDao]).
     */
    override suspend fun attribute(
        candidate: MatchResultEntity,
        verifiedSameBankTransaction: Boolean,
    ): AttributionOutcome {
        store.lock.lock()
        try {
            return super.attribute(candidate, verifiedSameBankTransaction)
        } finally {
            store.lock.unlock()
        }
    }
}

class FakeRegistryImportMetadataDao(private val store: FakeStore) : RegistryImportMetadataDao {
    override suspend fun insert(metadata: RegistryImportMetadataEntity): Unit = store.lock.withLock {
        // REPLACE on the composite PK (merchant_id, registry_version).
        store.registryImports["${metadata.merchantId}|${metadata.registryVersion}"] = metadata
    }

    override suspend fun getByVersion(merchantId: String, version: String): RegistryImportMetadataEntity? =
        store.lock.withLock { store.registryImports["$merchantId|$version"] }

    override suspend fun getActive(merchantId: String): RegistryImportMetadataEntity? =
        store.lock.withLock {
            store.registryImports.values.firstOrNull {
                it.merchantId == merchantId &&
                    it.status == com.netter.paylist.data.entity.RegistrySnapshotStatus.ACTIVE
            }
        }

    override suspend fun getHistory(merchantId: String): List<RegistryImportMetadataEntity> =
        store.lock.withLock {
            store.registryImports.values
                .filter { it.merchantId == merchantId }
                .sortedByDescending { it.importedAtMs }
        }

    override suspend fun supersedeActive(merchantId: String): Int = store.lock.withLock {
        var count = 0
        for ((version, meta) in store.registryImports.toMap()) {
            if (meta.merchantId == merchantId &&
                meta.status == com.netter.paylist.data.entity.RegistrySnapshotStatus.ACTIVE
            ) {
                store.registryImports[version] =
                    meta.copy(status = com.netter.paylist.data.entity.RegistrySnapshotStatus.SUPERSEDED)
                count++
            }
        }
        count
    }

    override suspend fun deleteRegistryRows(merchantId: String): Int = store.lock.withLock {
        val keys = store.registry.keys.filter { it.startsWith("$merchantId|") }
        keys.forEach { store.registry.remove(it) }
        keys.size
    }

    override suspend fun insertRegistryRows(rows: List<OrderRegistryEntity>): Unit = store.lock.withLock {
        for (row in rows) {
            val key = "${row.merchantId}|${row.orderNumber}"
            if (store.registry.containsKey(key)) {
                throw IllegalStateException("order_registry PK conflict: $key")
            }
            store.registry[key] = row
        }
    }

    /**
     * Mirrors Room's transaction serialisation + rollback for the
     * default `swapSnapshot` (manual lock/unlock — see
     * [FakeOrderRegistryDao]).
     */
    override suspend fun swapSnapshot(
        metadata: RegistryImportMetadataEntity,
        rows: List<OrderRegistryEntity>,
    ) {
        store.lock.lock()
        val snapshot = store.snapshot()
        try {
            super.swapSnapshot(metadata, rows)
        } catch (e: Throwable) {
            store.restore(snapshot)
            throw e
        } finally {
            store.lock.unlock()
        }
    }
}

/**
 * Fake [CaptureLogDao] (Stage D D4): mirrors the real SQL filter
 * semantics in Kotlin — date/amount ranges, package/outcome exact
 * match, delivery-state + route via the current (non-superseded)
 * deliveries, structured-field query (package substring or exact
 * amount), `post_time_ms` DESC, LIMIT/OFFSET.
 */
class FakeCaptureLogDao(private val store: FakeStore) : CaptureLogDao {
    override suspend fun insert(row: CaptureLogEntity): Long = store.lock.withLock {
        if (store.captureLog.containsKey(row.captureId)) return@withLock -1L
        store.captureLog[row.captureId] = row
        1L
    }

    override suspend fun getById(id: String): CaptureLogEntity? =
        store.lock.withLock { store.captureLog[id] }

    override suspend fun listByEventId(eventId: String): List<CaptureLogEntity> =
        store.lock.withLock {
            store.captureLog.values
                .filter { it.eventId == eventId }
                .sortedByDescending { it.postTimeMs }
        }

    private fun currentDeliveries(eventId: String?): List<DeliveryEntity> {
        if (eventId == null) return emptyList()
        return store.deliveries.values.filter {
            it.eventId == eventId &&
                it.state != DeliveryState.DISCARDED && it.state != DeliveryState.EXPIRED
        }
    }

    private fun matches(
        row: CaptureLogEntity,
        fromMs: Long?,
        toMs: Long?,
        minAmount: Long?,
        maxAmount: Long?,
        packageName: String?,
        outcome: String?,
        state: DeliveryState?,
        routeId: String?,
        query: String?,
        matchStatus: String?,
    ): Boolean {
        if (fromMs != null && row.postTimeMs < fromMs) return false
        if (toMs != null && row.postTimeMs > toMs) return false
        if (minAmount != null && (row.amountMinor ?: Long.MIN_VALUE) < minAmount) return false
        if (maxAmount != null && (row.amountMinor ?: Long.MAX_VALUE) > maxAmount) return false
        if (packageName != null && row.sourcePackage != packageName) return false
        if (outcome != null && row.outcome != outcome) return false
        val joined = currentDeliveries(row.eventId)
        if (state != null && joined.none { it.state == state }) return false
        if (routeId != null && joined.none { it.routeIdsJson.contains("\"$routeId\"") }) return false
        if (matchStatus != null && joined.none { it.lastMatchStatus == matchStatus }) return false
        if (query != null) {
            val packageHit = row.sourcePackage.contains(query, ignoreCase = true)
            val amountHit = row.amountMinor?.toString() == query
            if (!packageHit && !amountHit) return false
        }
        return true
    }

    override suspend fun listHistory(
        fromMs: Long?,
        toMs: Long?,
        minAmount: Long?,
        maxAmount: Long?,
        packageName: String?,
        outcome: String?,
        state: DeliveryState?,
        routeId: String?,
        query: String?,
        limit: Int,
        offset: Int,
        matchStatus: String?,
    ): List<CaptureLogEntity> = store.lock.withLock {
        store.captureLog.values
            .filter {
                matches(it, fromMs, toMs, minAmount, maxAmount, packageName, outcome, state, routeId, query, matchStatus)
            }
            .sortedByDescending { it.postTimeMs }
            .drop(offset)
            .take(limit)
    }

    override suspend fun countHistory(
        fromMs: Long?,
        toMs: Long?,
        minAmount: Long?,
        maxAmount: Long?,
        packageName: String?,
        outcome: String?,
        state: DeliveryState?,
        routeId: String?,
        query: String?,
        matchStatus: String?,
    ): Int = store.lock.withLock {
        store.captureLog.values.count {
            matches(it, fromMs, toMs, minAmount, maxAmount, packageName, outcome, state, routeId, query, matchStatus)
        }
    }

    override suspend fun distinctPackages(): List<String> = store.lock.withLock {
        store.captureLog.values.map { it.sourcePackage }.distinct().sorted()
    }

    override suspend fun count(): Int = store.lock.withLock { store.captureLog.size }

    override suspend fun countCollisionsFor(
        packageName: String,
        postTimeMs: Long,
        amountMinor: Long?,
    ): Int = store.lock.withLock {
        store.captureLog.values.count {
            it.outcome == "COLLISION" && it.sourcePackage == packageName &&
                it.postTimeMs == postTimeMs && it.amountMinor == amountMinor
        }
    }

    override suspend fun deleteById(id: String): Int = store.lock.withLock {
        if (store.captureLog.remove(id) != null) 1 else 0
    }

    override suspend fun deleteUnlinkedOlderThan(cutoffMs: Long): Int = store.lock.withLock {
        val doomed = store.captureLog.values.filter {
            it.eventId == null && it.capturedAtMs < cutoffMs
        }
        doomed.forEach { store.captureLog.remove(it.captureId) }
        doomed.size
    }

    override suspend fun deleteUnlinkedByOutcome(outcome: String): Int = store.lock.withLock {
        val doomed = store.captureLog.values.filter {
            it.eventId == null && it.outcome == outcome
        }
        doomed.forEach { store.captureLog.remove(it.captureId) }
        doomed.size
    }
}
