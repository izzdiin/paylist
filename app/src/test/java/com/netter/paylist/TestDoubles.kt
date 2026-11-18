package com.netter.paylist

import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.TransactionRunner
import com.netter.paylist.data.dao.AttemptDao
import com.netter.paylist.data.dao.CollectorStateDao
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.data.dao.DeliveryStateCount
import com.netter.paylist.data.dao.EventDao
import com.netter.paylist.data.entity.AttemptState
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.DeliveryAttemptEntity
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.data.entity.PromotionReceiptEntity
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

    data class Snapshot(
        val events: Map<String, EventEntity>,
        val eventsByDedupe: Map<String, EventEntity>,
        val eventsByExportRef: Map<String, EventEntity>,
        val deliveries: Map<String, DeliveryEntity>,
        val deliveriesByQkey: Map<String, DeliveryEntity>,
        val attempts: Map<String, DeliveryAttemptEntity>,
        val collector: Map<String, CollectorStateEntity>,
        val receipts: Map<String, PromotionReceiptEntity>,
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
            deleted++
        }
        deleted
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
