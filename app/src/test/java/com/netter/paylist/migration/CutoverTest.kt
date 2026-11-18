package com.netter.paylist.migration

import com.netter.paylist.Store
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.TransactionRunner
import com.netter.paylist.data.dao.AttemptDao
import com.netter.paylist.data.dao.CollectorStateDao
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.data.dao.DeliveryStateCount
import com.netter.paylist.data.dao.EventDao
import com.netter.paylist.data.entity.AttemptState
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.data.entity.DeliveryAttemptEntity
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.data.entity.PromotionReceiptDao
import com.netter.paylist.data.entity.PromotionReceiptEntity
import com.netter.paylist.data.entity.ReceiptState
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Cutover tests (design §10): `CutoverTest` owns F12, F20, F27, F28,
 * F29, F31, F32, F36, F37a, F37b, F38, F54 (F17 deferred, replaced by
 * F53) plus the B→C gates T09/T10/T20.
 *
 * There is no Robolectric in this module, so unit tests run on the
 * plain JVM without the Android framework and cannot use real Room.
 * The tests therefore run the migration and the coordinator against
 * in-memory fake DAOs that implement the DAO interfaces and mirror
 * the SQL contracts (`INSERT OR IGNORE` rows-affected, conditional
 * single UPDATEs, the claim mode gate). Methods with a default body
 * on the DAO (`DeliveryDao.claimBatch`,
 * `DeliveryDao.reclaimExpiredLeases`, `CollectorStateDao.putAll`)
 * are inherited by the fakes unchanged, so the mode gate and the
 * consent join under test are the production bodies, not copies.
 * The fake transaction runner serialises transactions on one lock
 * (as Room does) and rolls back on failure by restoring a snapshot
 * of the fake stores — which is what makes the crash-mid-commit
 * tests (F12/F27) meaningful. Real-SQL correctness is proven by
 * Room at compile time and by the androidTest compile gate, not
 * here.
 *
 * Stage B scope note: several fixture cases belong to the sender,
 * the capture path, or the worker (Stage C/D) and are NOT asserted
 * here — the lock-timeout retry, the staging/rescan paths, the
 * sender checkpoint protocol, the drain timeout, and the
 * `NO_POST`-on-observed-failure classification. Each such case is
 * named in the test that covers its Stage B neighbour. What Stage B
 * owns — the import decision table, the mode/intent commits, the
 * drain, the sweep, admission, and orphan recovery — is asserted
 * in full.
 */
class CutoverTest {

    companion object {
        /** Fixed test time: 2026-11-18 ~04:00 WIB, in epoch ms. */
        const val NOW: Long = 1_795_000_000_000L

        const val BOOT_A: String = "boot-A"
        const val BOOT_B: String = "boot-B"

        const val ROUTE_1: String = "route-1"
        const val ROUTE_2: String = "route-2"
    }

    // =====================================================================
    // Fakes
    // =====================================================================

    /** The shared durable store behind all fake DAOs (the "database"). */
    private class FakeStore {
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
    private class FakeTransactionRunner(private val store: FakeStore) : TransactionRunner {
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

    private class FakeEventDao(private val store: FakeStore) : EventDao {
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

    private class FakeDeliveryDao(private val store: FakeStore) : DeliveryDao {
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

    private class FakeAttemptDao(private val store: FakeStore) : AttemptDao {
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

    private class FakeCollectorStateDao(private val store: FakeStore) : CollectorStateDao {
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

    private class FakePromotionReceiptDao(private val store: FakeStore) : PromotionReceiptDao {
        override suspend fun insert(receipt: PromotionReceiptEntity): Long = store.lock.withLock {
            if (store.receipts.containsKey(receipt.legacyQkey)) return@withLock -1L
            store.receipts[receipt.legacyQkey] = receipt
            1L
        }

        override suspend fun getByLegacyQkey(qkey: String): PromotionReceiptEntity? = store.lock.withLock { store.receipts[qkey] }
        override suspend fun getUnresolved(): List<PromotionReceiptEntity> = store.lock.withLock {
            store.receipts.values.filter {
                it.state == ReceiptState.PREPARING || it.state == ReceiptState.READY || it.state == ReceiptState.IN_FLIGHT
            }
        }

        override suspend fun markReady(qkey: String): Int = store.lock.withLock {
            transition(qkey, ReceiptState.PREPARING, ReceiptState.READY)
        }
        override suspend fun markInFlight(qkey: String): Int = store.lock.withLock {
            transition(qkey, ReceiptState.READY, ReceiptState.IN_FLIGHT)
        }
        override suspend fun markResolved(qkey: String, now: Long, outcome: String): Int = store.lock.withLock {
            val row = store.receipts[qkey] ?: return@withLock 0
            if (row.state != ReceiptState.IN_FLIGHT) return@withLock 0
            store.receipts[qkey] = row.copy(state = ReceiptState.RESOLVED, resolvedAtMs = now, outcome = outcome)
            1
        }

        private fun transition(qkey: String, from: ReceiptState, to: ReceiptState): Int {
            val row = store.receipts[qkey] ?: return 0
            if (row.state != from) return 0
            store.receipts[qkey] = row.copy(state = to)
            return 1
        }
    }

    /** In-memory legacy queue: the queue file plus the untouched route settings (T20). */
    private class FakeQueueSource(
        initialQueue: List<JSONObject> = emptyList(),
    ) : LegacyQueueSnapshotSource {
        private val queue: MutableList<JSONObject> = initialQueue.map { JSONObject(it.toString()) }.toMutableList()

        /** Route settings the migration must never touch (T20). */
        val routes: JSONObject = JSONObject().apply {
            put(ROUTE_1, JSONObject().put("url", "https://a.example/notify").put("enabled", true))
            put(ROUTE_2, JSONObject().put("url", "https://b.example/notify").put("enabled", true))
        }

        /** Test hook: the next N `saveQueueSync` calls fail (F38). */
        var failSaveCount: Int = 0

        override fun queueSnapshot(): List<JSONObject> = queue.map { JSONObject(it.toString()) }

        override fun saveQueueSync(items: List<JSONObject>): Boolean {
            if (failSaveCount > 0) {
                failSaveCount--
                return false
            }
            queue.clear()
            queue.addAll(items.map { JSONObject(it.toString()) })
            // Readback agreement, mirroring Store.saveQueueSync.
            if (queue.size != items.size) return false
            for (i in items.indices) {
                if (queue[i].optString("qkey") != items[i].optString("qkey")) return false
            }
            return true
        }

        fun qkeys(): List<String> = queue.map { it.optString("qkey") }
    }

    // =====================================================================
    // Harness + fixtures
    // =====================================================================

    private class Harness(
        val store: FakeStore,
        val events: FakeEventDao,
        val deliveries: FakeDeliveryDao,
        val attempts: FakeAttemptDao,
        val state: FakeCollectorStateDao,
        val receipts: FakePromotionReceiptDao,
        val queue: FakeQueueSource,
        val tx: FakeTransactionRunner,
        val migration: LegacyQueueMigration,
        val coordinator: CutoverCoordinator,
        val neverSentGatePassed: Boolean,
    ) {
        /** Simulates a process restart: new coordinator + migration over the same durable fakes. */
        fun restart(neverSentGate: Boolean = neverSentGatePassed): Harness {
            val newMigration = LegacyQueueMigration(events, deliveries, attempts, receipts, tx)
            val newCoordinator = CutoverCoordinator(
                state, deliveries, attempts, tx, newMigration, queue,
                ReentrantLock(), neverSentGate,
            )
            return Harness(store, events, deliveries, attempts, state, receipts, queue, tx, newMigration, newCoordinator, neverSentGate)
        }

        suspend fun setBootId(bootId: String) {
            state.put(
                CollectorStateEntity(
                    "boot_id",
                    JSONObject().put("boot_id", bootId).put("boot_started_at_ms", NOW).toString(),
                    NOW,
                ),
            )
        }

        /** Runs the full cutover: request → commit DRAINING → drain → commit WORKER_ONLY. */
        suspend fun fullCutover(inspector: LegacyQueueInspector): DrainResult {
            val requested = coordinator.requestDrain(NOW)
            assertTrue("requestDrain must succeed: $requested", requested is CutoverCommit.Success)
            val committed = coordinator.commitDraining(NOW)
            assertTrue("commitDraining must succeed: $committed", committed is CutoverCommit.Success)
            val drain = coordinator.drain(inspector, NOW)
            assertTrue("drain must succeed: ${drain.error}", drain.ok)
            val workerOnly = coordinator.commitWorkerOnly(NOW)
            assertTrue("commitWorkerOnly must succeed: $workerOnly", workerOnly is CutoverCommit.Success)
            return drain
        }
    }

    private fun newHarness(
        queueItems: List<JSONObject> = emptyList(),
        neverSentGatePassed: Boolean = false,
    ): Harness {
        val store = FakeStore()
        val tx = FakeTransactionRunner(store)
        val events = FakeEventDao(store)
        val deliveries = FakeDeliveryDao(store)
        val attempts = FakeAttemptDao(store)
        val state = FakeCollectorStateDao(store)
        val receipts = FakePromotionReceiptDao(store)
        val queue = FakeQueueSource(queueItems)
        val migration = LegacyQueueMigration(events, deliveries, attempts, receipts, tx)
        val coordinator = CutoverCoordinator(state, deliveries, attempts, tx, migration, queue, ReentrantLock(), neverSentGatePassed)
        val harness = Harness(store, events, deliveries, attempts, state, receipts, queue, tx, migration, coordinator, neverSentGatePassed)
        runBlocking { harness.setBootId(BOOT_A) }
        return harness
    }

    private fun inspectorFor(quarantinedQkeys: Set<String> = emptySet()): LegacyQueueInspector =
        LegacyQueueInspector { item ->
            if (item.qkey in quarantinedQkeys) Inspection.QUARANTINED else Inspection.IMPORTABLE
        }

    private fun paymentPayload(dedupeKey: String, amount: Long = 100_800L): JSONObject =
        JSONObject().apply {
            put("type", "payment")
            put("device", "moto-g57")
            put("pkg", "com.gojek.gopaymerchant")
            put("amount", amount)
            put("title", "GoPay")
            put("text", "Pembayaran Rp100.800 diterima")
            put("notif_time", "2026-11-18T03:00:00Z")
            put("dedupe_key", dedupeKey)
        }

    private fun heartbeatPayload(dedupeKey: String): JSONObject =
        JSONObject().apply {
            put("type", "heartbeat")
            put("device", "moto-g57")
            put("dedupe_key", dedupeKey)
        }

    private fun queueItem(
        dedupeKey: String,
        kind: String = LegacyQkey.KIND_CHAIN,
        routes: List<String> = listOf(ROUTE_1, ROUTE_2),
        next: Int = 0,
        attempts: Int = 0,
        lastError: String? = null,
        payload: JSONObject? = null,
        hopOutcomes: JSONArray? = null,
    ): JSONObject {
        val qkey = LegacyQkey.build(dedupeKey, kind, routes)
        return JSONObject().apply {
            put("qkey", qkey)
            put("kind", kind)
            if (kind == LegacyQkey.KIND_CHAIN) {
                put("route_ids", JSONArray(routes))
            } else {
                put("route_id", routes.first())
            }
            put("next", next)
            put("attempts", attempts)
            if (lastError != null) put("last_error", lastError)
            put("payload", payload ?: paymentPayload(dedupeKey))
            if (hopOutcomes != null) put("hop_outcomes", hopOutcomes)
        }
    }

    private fun hop(routeId: String, outcome: String, apply: String? = null): JSONObject =
        JSONObject().apply {
            put("route_id", routeId)
            put("outcome", outcome)
            if (apply != null) put("apply", apply)
            put("attempted_at_ms", NOW - 60_000L)
        }

    private fun dedupeKey(tag: String): String = "com.gojek.gopaymerchant|1795000000000|100800|$tag"

    private fun attemptRow(
        attemptId: String,
        deliveryId: String,
        dedupeKey: String,
        state: AttemptState,
        ownerBootId: String,
        hopIndex: Int = 0,
        startedAtMs: Long = NOW - 10_000L,
    ): DeliveryAttemptEntity = DeliveryAttemptEntity(
        attemptId = attemptId,
        deliveryId = deliveryId,
        routeId = ROUTE_1,
        attemptNo = 1,
        startedAtMs = startedAtMs,
        state = state,
        hopIndex = hopIndex,
        dedupeKey = dedupeKey,
        ownerBootId = ownerBootId,
        drainGeneration = null,
        fenceGeneration = null,
    )

    private fun admissionRequest(
        deliveryId: String,
        dedupeKey: String,
        ownerBootId: String = BOOT_A,
        drainGeneration: Long? = null,
        hopIndex: Int = 0,
    ): AdmissionRequest = AdmissionRequest(
        deliveryId = deliveryId,
        routeId = ROUTE_1,
        hopIndex = hopIndex,
        attemptNo = 1,
        dedupeKey = dedupeKey,
        ownerBootId = ownerBootId,
        drainGeneration = drainGeneration,
        consentEpoch = null,
        configEpoch = null,
        urlSha256 = null,
    )

    // =====================================================================
    // B→C gates: T09 / T10 / T20
    // =====================================================================

    @Test
    fun t09_eventSurvivesRestartAfterCommit() = runBlocking {
        val h = newHarness(listOf(queueItem(dedupeKey("t09a")), queueItem(dedupeKey("t09b"))))
        h.fullCutover(inspectorFor())
        val before = h.deliveries.getByState(DeliveryState.PENDING)
        assertEquals(2, before.size)
        val eventIds = before.map { it.eventId }.toSet()
        // Kill the process after the DB commit: new objects, same durable store.
        val restarted = h.restart()
        assertEquals(CutoverMode.WORKER_ONLY, restarted.coordinator.getMode())
        val after = restarted.deliveries.getByState(DeliveryState.PENDING)
        assertEquals(2, after.size)
        assertEquals(eventIds, after.map { it.eventId }.toSet())
        for (eventId in eventIds) {
            assertNotNull("event $eventId must survive the restart", restarted.events.getById(eventId))
        }
    }

    @Test
    fun t10_crashDuringImport_reimportWithoutDuplicatesOrLoss() = runBlocking {
        val items = listOf(queueItem(dedupeKey("t10a")), queueItem(dedupeKey("t10b")), queueItem(dedupeKey("t10c")))
        val h = newHarness(items)
        val snapshot = h.migration.snapshot(items, NOW)
        val partitioned = h.migration.partition(snapshot, inspectorFor())
        val first = h.migration.import(partitioned, NOW)
        assertEquals(3, first.deliveriesImported)
        assertEquals(3, first.eventsImported)
        // Crash after the import commit; re-run the import from the same frozen snapshot.
        val second = h.migration.import(partitioned, NOW)
        assertEquals(0, second.deliveriesImported)
        assertEquals(0, second.eventsImported)
        assertEquals(3, second.duplicatesSkipped)
        assertEquals(3, h.events.count())
        assertEquals(3, h.deliveries.countByState().sumOf { it.count })
        for (item in items) {
            assertNotNull(h.deliveries.getByLegacyQkey(item.optString("qkey")))
        }
    }

    @Test
    fun t20_upgradePreservesQueueAndSettings() = runBlocking {
        val midFlight = queueItem(dedupeKey("t20a"), next = 1, attempts = 2, lastError = "SocketTimeoutException")
        val single = queueItem(dedupeKey("t20b"), kind = LegacyQkey.KIND_SINGLE, routes = listOf(ROUTE_1))
        val quarantined = queueItem(dedupeKey("t20c"))
        val h = newHarness(listOf(midFlight, single, quarantined))
        val routesBefore = h.queue.routes.toString()
        val drain = h.fullCutover(inspectorFor(setOf(quarantined.optString("qkey"))))
        assertEquals(2, drain.imported)
        assertEquals(1, drain.quarantined)
        // Per-item cursor + server-effect audit preserved.
        val imported = h.deliveries.getByLegacyQkey(midFlight.optString("qkey"))
        assertNotNull(imported)
        assertEquals(1, imported!!.nextRouteIndex)
        assertEquals(2, imported.attempts)
        assertEquals("SocketTimeoutException", imported.lastErrorSummary)
        // Quarantined item preserved in the queue through WORKER_ONLY.
        assertEquals(listOf(quarantined.optString("qkey")), h.queue.qkeys())
        // Route settings untouched.
        assertEquals(routesBefore, h.queue.routes.toString())
        assertEquals(CutoverMode.WORKER_ONLY, h.coordinator.getMode())
    }

    // =====================================================================
    // F12 — capture handoff, import failure, boundary ordering
    // =====================================================================

    @Test
    fun f12_noRollbackToLegacyOnly() = runBlocking {
        val h = newHarness()
        h.fullCutover(inspectorFor())
        val abort = h.coordinator.abortCutover(NOW)
        assertTrue("WORKER_ONLY cannot abort: $abort", abort is CutoverCommit.Rejected)
        val redrain = h.coordinator.requestDrain(NOW)
        assertTrue(redrain is CutoverCommit.Rejected)
        assertEquals(CutoverMode.WORKER_ONLY, h.coordinator.getMode())
    }

    @Test
    fun commitDraining_requiresRequestedDrain() = runBlocking {
        val h = newHarness()
        // No drain requested: the commit is refused (R19 correction of F37a).
        val refused = h.coordinator.commitDraining(NOW)
        assertTrue("commit without a requested drain must be refused: $refused", refused is CutoverCommit.Rejected)
        assertEquals(CutoverMode.LEGACY_ONLY, h.coordinator.getMode())
        assertEquals(DrainIntent.NONE, h.coordinator.getDrainIntent())
    }

    @Test
    fun modeIntentCombinations_onlyFiveLegal() {
        assertTrue(CutoverCoordinator.isLegalCombination(CutoverMode.LEGACY_ONLY, DrainIntent.NONE))
        assertTrue(CutoverCoordinator.isLegalCombination(CutoverMode.LEGACY_ONLY, DrainIntent.PENDING))
        assertTrue(CutoverCoordinator.isLegalCombination(CutoverMode.DRAINING, DrainIntent.QUIESCED))
        assertTrue(CutoverCoordinator.isLegalCombination(CutoverMode.WORKER_ONLY, DrainIntent.QUIESCED))
        assertTrue(CutoverCoordinator.isLegalCombination(CutoverMode.LEGACY_ONLY, DrainIntent.ABORTED))
        assertFalse(CutoverCoordinator.isLegalCombination(CutoverMode.DRAINING, DrainIntent.PENDING))
        assertFalse(CutoverCoordinator.isLegalCombination(CutoverMode.WORKER_ONLY, DrainIntent.NONE))
        assertFalse(CutoverCoordinator.isLegalCombination(CutoverMode.DRAINING, DrainIntent.ABORTED))
        assertFalse(CutoverCoordinator.isLegalCombination(CutoverMode.WORKER_ONLY, DrainIntent.PENDING))
    }

    @Test
    fun f12_roomCapturesDuringDrainingAllPendingAtWorkerOnly() = runBlocking {
        val h = newHarness()
        h.coordinator.requestDrain(NOW)
        h.coordinator.commitDraining(NOW)
        assertEquals(CutoverMode.DRAINING, h.coordinator.getMode())
        // A Room capture during DRAINING (seeded via the DAOs directly).
        val deliveryId = h.seedDelivery(dedupeKey("f12cap"))
        val drain = h.coordinator.drain(inspectorFor(), NOW)
        assertTrue(drain.ok)
        h.coordinator.commitWorkerOnly(NOW)
        val delivery = h.deliveries.getById(deliveryId)
        assertEquals(DeliveryState.PENDING, delivery!!.state)
    }

    @Test
    fun f12_cutoverMidChainAfterHop1Logged() = runBlocking {
        val hops = JSONArray().put(hop(ROUTE_1, "LOGGED"))
        val item = queueItem(dedupeKey("f12mid"), next = 1, hopOutcomes = hops)
        val h = newHarness(listOf(item))
        val snapshot = h.migration.snapshot(listOf(item), NOW)
        val partitioned = h.migration.partition(snapshot, inspectorFor())
        h.migration.import(partitioned, NOW)
        val delivery = h.deliveries.getByLegacyQkey(item.optString("qkey"))
        assertNotNull(delivery)
        assertEquals(DeliveryState.PENDING, delivery!!.state)
        assertEquals(1, delivery.nextRouteIndex)
        // The worker resumes hop 2; the logged hop is not re-POSTed.
        assertTrue(delivery.hopOutcomesJson.contains("LOGGED"))
    }

    @Test
    fun f12_timeoutAfterCommit_hopUnknownChainDoesNotAdvance() = runBlocking {
        val hops = JSONArray().put(hop(ROUTE_1, "UNKNOWN_PENDING_RECONCILIATION"))
        val item = queueItem(dedupeKey("f12unk"), next = 0, hopOutcomes = hops)
        val h = newHarness(listOf(item))
        val snapshot = h.migration.snapshot(listOf(item), NOW)
        val partitioned = h.migration.partition(snapshot, inspectorFor())
        h.migration.import(partitioned, NOW)
        val delivery = h.deliveries.getByLegacyQkey(item.optString("qkey"))
        assertNotNull(delivery)
        assertEquals(DeliveryState.PENDING, delivery!!.state)
        assertEquals(0, delivery.nextRouteIndex)
        assertTrue(delivery.hopOutcomesJson.contains("UNKNOWN_PENDING_RECONCILIATION"))
    }

    @Test
    fun f12_importChecksumCoversSnapshotRowsOnly() {
        val clean = queueItem(dedupeKey("f12c1"))
        val quarantined = queueItem(dedupeKey("f12c2"))
        val h = newHarness()
        val snapshot = h.migration.snapshot(listOf(clean, quarantined), NOW)
        val partitioned = h.migration.partition(snapshot, inspectorFor(setOf(quarantined.optString("qkey"))))
        assertNotEquals(snapshot.checksum, partitioned.importableChecksum)
        // A change to a quarantined row changes the snapshot checksum
        // but NOT the importable checksum.
        val changed = queueItem(dedupeKey("f12c2"), attempts = 5)
        val snapshot2 = h.migration.snapshot(listOf(clean, changed), NOW)
        val partitioned2 = h.migration.partition(snapshot2, inspectorFor(setOf(quarantined.optString("qkey"))))
        assertNotEquals(snapshot.checksum, snapshot2.checksum)
        assertEquals(partitioned.importableChecksum, partitioned2.importableChecksum)
        assertEquals(
            LegacyQueueMigration.quarantineChecksum(partitioned.quarantined),
            partitioned.quarantineChecksum,
        )
    }

    @Test
    fun f12_rescanDuringDrainingCreatesNoSecondRow() = runBlocking {
        val dedupe = dedupeKey("f12rescan")
        val first = queueItem(dedupe, routes = listOf(ROUTE_1, ROUTE_2))
        val rescan = queueItem(dedupe, routes = listOf(ROUTE_1))
        val h = newHarness(listOf(first, rescan))
        val snapshot = h.migration.snapshot(listOf(first, rescan), NOW)
        val partitioned = h.migration.partition(snapshot, inspectorFor())
        val report = h.migration.import(partitioned, NOW)
        assertEquals(1, report.eventsImported)
        assertEquals(1, report.duplicatesSkipped)
        assertEquals(2, report.deliveriesImported)
        assertEquals(1, h.events.count())
        // Both deliveries reference the SAME event row (no FK breakage).
        val d1 = h.deliveries.getByLegacyQkey(first.optString("qkey"))
        val d2 = h.deliveries.getByLegacyQkey(rescan.optString("qkey"))
        assertNotNull(d1)
        assertNotNull(d2)
        assertEquals(d1!!.eventId, d2!!.eventId)
    }

    @Test
    fun f12_roomInsertFailureRollsBackStagingRetained() = runBlocking {
        val items = listOf(queueItem(dedupeKey("f12fail1")), queueItem(dedupeKey("f12fail2")))
        val h = newHarness(items)
        h.events.failNextInsert = true
        val snapshot = h.migration.snapshot(items, NOW)
        val partitioned = h.migration.partition(snapshot, inspectorFor())
        try {
            h.migration.import(partitioned, NOW)
            fail("import must throw on Room insert failure")
        } catch (e: IllegalStateException) {
            // expected: the injected failure
        }
        // The batch transaction rolled back: no partial rows.
        assertEquals(0, h.events.count())
        assertEquals(0, h.deliveries.countByState().sumOf { it.count })
        // The staging (legacy queue) is retained untouched.
        assertEquals(2, h.queue.qkeys().size)
        // A retry after the failure succeeds.
        val retry = h.migration.import(partitioned, NOW)
        assertEquals(2, retry.deliveriesImported)
    }

    // =====================================================================
    // F20 — concurrency: exactly one delivery per legacy_qkey
    // =====================================================================

    @Test
    fun f20_concurrentImportExactlyOneDeliveryPerQkey() {
        val items = (1..10).map { queueItem(dedupeKey("f20-$it")) }
        val h = newHarness(items)
        val snapshot = h.migration.snapshot(items, NOW)
        val partitioned = h.migration.partition(snapshot, inspectorFor())
        val latch = CountDownLatch(1)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (1..2).map {
            thread {
                latch.await()
                try {
                    runBlocking { h.migration.import(partitioned, NOW) }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        latch.countDown()
        threads.forEach { it.join(30_000) }
        assertFalse("import thread still running", threads.any { it.isAlive })
        assertTrue("import threads threw: $errors", errors.isEmpty())
        runBlocking {
            assertEquals(10, h.events.count())
            assertEquals(10, h.deliveries.countByState().sumOf { it.count })
            for (item in items) {
                assertNotNull(h.deliveries.getByLegacyQkey(item.optString("qkey")))
            }
        }
    }

    // =====================================================================
    // F27 — mode commit atomicity + no SharedPreferences mode
    // =====================================================================

    @Test
    fun f27_killMidModeCommit_oldModeStands() = runBlocking {
        val h = newHarness()
        h.coordinator.requestDrain(NOW)
        assertEquals(DrainIntent.PENDING, h.coordinator.getDrainIntent())
        // Kill mid-commit: the 3rd put inside commitDraining's putAll throws.
        h.state.failOnPutNumber = h.state.putCount + 3
        try {
            h.coordinator.commitDraining(NOW)
            fail("commitDraining must throw on a mid-commit kill")
        } catch (e: IllegalStateException) {
            // expected: the simulated kill
        }
        // The transaction rolled back: the old mode stands.
        assertEquals(CutoverMode.LEGACY_ONLY, h.coordinator.getMode())
        assertEquals(DrainIntent.PENDING, h.coordinator.getDrainIntent())
        assertNull(h.coordinator.getPhaseMarker())
        // A retry after the kill succeeds.
        val retry = h.coordinator.commitDraining(NOW)
        assertTrue("retry must succeed: $retry", retry is CutoverCommit.Success)
        assertEquals(CutoverMode.DRAINING, h.coordinator.getMode())
    }

    @Test
    fun f27_killAfterCommit_newModeStands() = runBlocking {
        val h = newHarness()
        h.coordinator.requestDrain(NOW)
        h.coordinator.commitDraining(NOW)
        val restarted = h.restart()
        assertEquals(CutoverMode.DRAINING, restarted.coordinator.getMode())
        assertEquals(DrainIntent.QUIESCED, restarted.coordinator.getDrainIntent())
        assertNotNull(restarted.coordinator.getPhaseMarker())
    }

    @Test
    fun f27_workerClaimsZeroRowsWhileDraining() = runBlocking {
        val h = newHarness(listOf(queueItem(dedupeKey("f27a")), queueItem(dedupeKey("f27b"))))
        h.coordinator.requestDrain(NOW)
        h.coordinator.commitDraining(NOW)
        val drain = h.coordinator.drain(inspectorFor(), NOW)
        assertTrue(drain.ok)
        assertEquals(2, h.deliveries.countDue(NOW))
        // While DRAINING the worker claims zero rows.
        assertTrue(h.deliveries.claimBatch(10, NOW, 300_000L).isEmpty())
        h.coordinator.commitWorkerOnly(NOW)
        // After WORKER_ONLY the same rows are claimable.
        val claimed = h.deliveries.claimBatch(10, NOW, 300_000L)
        assertEquals(2, claimed.size)
        assertTrue(claimed.all { it.state == DeliveryState.IN_FLIGHT && it.leaseOwner != null })
    }

    @Test
    fun f27_noModeReadFromSharedPreferences_staticCheck() {
        val root = findSourceRoot()
            ?: throw AssertionError("source root not found — fail-closed: cannot prove the F27 static check")
        val modeKeys = listOf(
            "cutover_state", "drain_intent", "migration_state", "sender_fenced",
            "cutover_import", "migration_clear", "drain_state", "fence_generation",
        )
        val modeConstants = listOf(
            "CUTOVER_STATE", "DRAIN_INTENT", "MIGRATION_STATE", "SENDER_FENCED",
            "CUTOVER_IMPORT", "MIGRATION_CLEAR", "DRAIN_STATE", "FENCE_GENERATION",
        )
        // A SharedPreferences accessor call whose key is a mode key
        // (literal or CollectorStateKeys constant). Mere mentions of
        // SharedPreferences or of the keys in prose/KDoc do not match.
        val accessor = Regex(
            """\b((get|put)(String|Int|Long|Float|Boolean|StringSet)|contains|remove)\s*\(\s*("(?:${
                modeKeys.joinToString("|")
            })"|(?:CollectorStateKeys\.)?(?:${modeConstants.joinToString("|")})\b)""",
        )
        val violations = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) return@forEachIndexed
                if (accessor.containsMatchIn(line)) {
                    violations.add("${file.relativeTo(root)}:${index + 1}: $trimmed")
                }
            }
        }
        assertTrue("mode read from SharedPreferences (F27):\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    // =====================================================================
    // F28 — restart with a PENDING intent / orphaned rows
    // =====================================================================

    @Test
    fun f28_restartWithPendingIntent_refencesBeforeClaim() = runBlocking {
        val h = newHarness()
        h.coordinator.requestDrain(NOW)
        val restarted = h.restart()
        assertEquals(DrainIntent.PENDING, restarted.coordinator.getDrainIntent())
        assertEquals(CutoverMode.LEGACY_ONLY, restarted.coordinator.getMode())
        // No new claim begins while PENDING. The legacy path is refused
        // DRAIN_PENDING; the worker path is refused MODE first (the mode
        // check precedes the intent check) — either way, no admission.
        val legacy = restarted.coordinator.admitAttempt(
            admissionRequest("d-f28", dedupeKey("f28"), drainGeneration = 1L),
            AdmissionPath.LEGACY, NOW,
        )
        assertEquals(AdmissionResult.Rejected(AdmissionRejection.DRAIN_PENDING), legacy)
        val worker = restarted.coordinator.admitAttempt(
            admissionRequest("d-f28", dedupeKey("f28"), drainGeneration = 1L),
            AdmissionPath.WORKER, NOW,
        )
        assertEquals(AdmissionResult.Rejected(AdmissionRejection.MODE), worker)
        // The worker claims nothing while PENDING (mode gate).
        assertTrue(restarted.deliveries.claimBatch(10, NOW, 300_000L).isEmpty())
    }

    @Test
    fun f28_restartWithOrphanedAttemptAndPendingIntent_quiescenceHolds() = runBlocking {
        val h = newHarness()
        // An attempt orphaned by a dead process (boot-A), still unresolved.
        h.attempts.insert(attemptRow("att-orphan", "d-orphan", dedupeKey("f28o"), AttemptState.ATTEMPT_STARTED, BOOT_A))
        h.coordinator.requestDrain(NOW)
        // Restart as boot-B.
        val restarted = h.restart()
        restarted.setBootId(BOOT_B)
        // Recovery classifies the orphan; it does NOT delete it.
        val report = restarted.coordinator.recoverOrphanedAttempts(BOOT_B, NOW)
        assertEquals(1, report.orphanCount)
        assertNotNull(restarted.attempts.getById("att-orphan"))
        // Quiescence holds despite the orphaned row: the commit succeeds.
        val commit = restarted.coordinator.commitDraining(NOW)
        assertTrue("commit must succeed despite orphans: $commit", commit is CutoverCommit.Success)
    }

    // =====================================================================
    // F29 — the per-hop decision table (§8.5 snapshot import rule)
    // =====================================================================

    @Test
    fun f29_perHopDecisionTable() {
        val h = newHarness()
        fun decide(
            item: LegacyQueueItem,
            attempts: List<DeliveryAttemptEntity> = emptyList(),
            receipt: PromotionReceiptEntity? = null,
        ) = h.migration.importStateFor(item, attempts, receipt)

        // Row 1: logged hops, chain exhausted -> ACKED.
        val logged = LegacyQueueItem.fromJson(
            queueItem(
                dedupeKey("f29a"), next = 2,
                hopOutcomes = JSONArray().put(hop(ROUTE_1, "LOGGED")).put(hop(ROUTE_2, "LOGGED")),
            ),
        )!!
        val loggedDecision = decide(logged)
        assertEquals(DeliveryState.ACKED, loggedDecision.state)
        assertEquals("logged", loggedDecision.lastMatchStatus)

        // Row 2a: matched-settled -> ACKED with the apply status.
        val matched = LegacyQueueItem.fromJson(
            queueItem(
                dedupeKey("f29b"), next = 1,
                hopOutcomes = JSONArray().put(hop(ROUTE_1, "MATCHED_SETTLED", "ok")),
            ),
        )!!
        val matchedDecision = decide(matched)
        assertEquals(DeliveryState.ACKED, matchedDecision.state)
        assertEquals("matched", matchedDecision.lastMatchStatus)
        assertEquals("ok", matchedDecision.lastApplyStatus)

        // Row 3: 4xx -> BLOCKED.
        val rejected = LegacyQueueItem.fromJson(
            queueItem(
                dedupeKey("f29c"), next = 0,
                hopOutcomes = JSONArray().put(hop(ROUTE_1, "REJECTED-4xx")),
            ),
        )!!
        assertEquals(DeliveryState.BLOCKED, decide(rejected).state)

        // Row 4: unknown hop -> PENDING (re-POST at-least-once).
        val unknown = LegacyQueueItem.fromJson(
            queueItem(
                dedupeKey("f29d"), next = 0,
                hopOutcomes = JSONArray().put(hop(ROUTE_1, "UNKNOWN_PENDING_RECONCILIATION")),
            ),
        )!!
        assertEquals(DeliveryState.PENDING, decide(unknown).state)

        // Row 5 guard: cursor past the chain end with no record -> HELD.
        val cursorOnly = LegacyQueueItem.fromJson(queueItem(dedupeKey("f29e"), next = 2))!!
        assertEquals(DeliveryState.HELD_AWAITING_RECONCILIATION, decide(cursorOnly).state)

        // Row 6: every hop a skip, exhausted -> BLOCKED (visible, never silently done).
        val skipped = LegacyQueueItem.fromJson(
            queueItem(
                dedupeKey("f29f"), next = 2,
                hopOutcomes = JSONArray().put(hop(ROUTE_1, "NO_POST")).put(hop(ROUTE_2, "NO_POST")),
            ),
        )!!
        assertEquals(DeliveryState.BLOCKED, decide(skipped).state)

        // No records, cursor inside the chain -> PENDING.
        val fresh = LegacyQueueItem.fromJson(queueItem(dedupeKey("f29g"), next = 0))!!
        assertEquals(DeliveryState.PENDING, decide(fresh).state)

        // Orphaned unresolved row -> HELD (§8.5(g)).
        val orphanAttempt = attemptRow("att-f29", "d-f29", dedupeKey("f29h"), AttemptState.SOCKET_OPEN, BOOT_A)
        val heldItem = LegacyQueueItem.fromJson(queueItem(dedupeKey("f29h"), next = 0))!!
        assertEquals(DeliveryState.HELD_AWAITING_RECONCILIATION, decide(heldItem, listOf(orphanAttempt)).state)

        // RESOLVED/dispatched receipt -> terminal; RESOLVED/no_post -> DISCARDED.
        val resolvedItem = LegacyQueueItem.fromJson(queueItem(dedupeKey("f29i"), next = 0))!!
        val dispatched = PromotionReceiptEntity(
            resolvedItem.qkey, ReceiptState.RESOLVED, NOW - 1000L, NOW,
            "dispatched", 1, "[]", "MATCHED_SETTLED",
        )
        assertEquals(DeliveryState.ACKED, decide(resolvedItem, receipt = dispatched).state)
        val noPost = dispatched.copy(outcome = "no_post", lastHopOutcome = null)
        assertEquals(DeliveryState.DISCARDED, decide(resolvedItem, receipt = noPost).state)

        // IN_FLIGHT receipt -> PENDING (re-POST permitted under at-least-once).
        val inFlight = dispatched.copy(state = ReceiptState.IN_FLIGHT, outcome = null, resolvedAtMs = null)
        assertEquals(DeliveryState.PENDING, decide(resolvedItem, receipt = inFlight).state)
    }

    // =====================================================================
    // F31 — the §8.5 crash matrix (NEVER_SENT conditional on F40)
    // =====================================================================

    @Test
    fun f31_crashMatrix_neverSentConditionalOnF40() = runBlocking {
        // F40 NOT passed: no orphan becomes NEVER_SENT.
        val hClosed = newHarness(neverSentGatePassed = false)
        hClosed.attempts.insert(attemptRow("att-1a", "d-1a", dedupeKey("f31a"), AttemptState.ATTEMPT_STARTED, BOOT_A))
        hClosed.attempts.insert(attemptRow("att-2a", "d-2a", dedupeKey("f31b"), AttemptState.SOCKET_OPENING, BOOT_A))
        hClosed.attempts.insert(attemptRow("att-2b", "d-2b", dedupeKey("f31c"), AttemptState.SOCKET_OPEN, BOOT_A))
        val closed = hClosed.coordinator.recoverOrphanedAttempts(BOOT_B, NOW)
        assertEquals(0, closed.neverSent)
        assertEquals(3, closed.unknownHeld)
        assertEquals(AttemptState.ATTEMPT_STARTED, hClosed.attempts.getById("att-1a")!!.state)
        assertEquals(AttemptState.SOCKET_OPENING, hClosed.attempts.getById("att-2a")!!.state)
        assertEquals(AttemptState.SOCKET_OPEN, hClosed.attempts.getById("att-2b")!!.state)

        // F40 passed: (1a)/(1b)/(2a) -> NEVER_SENT; (2b)-(5) stay UNKNOWN.
        val hOpen = newHarness(neverSentGatePassed = true)
        hOpen.attempts.insert(attemptRow("att-1a", "d-1a", dedupeKey("f31a"), AttemptState.ATTEMPT_STARTED, BOOT_A))
        hOpen.attempts.insert(attemptRow("att-2a", "d-2a", dedupeKey("f31b"), AttemptState.SOCKET_OPENING, BOOT_A))
        hOpen.attempts.insert(attemptRow("att-2b", "d-2b", dedupeKey("f31c"), AttemptState.SOCKET_OPEN, BOOT_A))
        val open = hOpen.coordinator.recoverOrphanedAttempts(BOOT_B, NOW)
        assertEquals(2, open.neverSent)
        assertEquals(1, open.unknownHeld)
        assertEquals(AttemptState.NEVER_SENT, hOpen.attempts.getById("att-1a")!!.state)
        assertEquals(AttemptState.NEVER_SENT, hOpen.attempts.getById("att-2a")!!.state)
        assertEquals(AttemptState.SOCKET_OPEN, hOpen.attempts.getById("att-2b")!!.state)
        // The startup fence is cleared after recovery.
        assertFalse(hOpen.coordinator.isSenderFenced())
    }

    @Test
    fun f31_staleAdmissionAbandoned() = runBlocking {
        val h = newHarness()
        h.attempts.insert(
            attemptRow(
                "att-stale", "d-stale", dedupeKey("f31s"), AttemptState.ATTEMPT_STARTED, BOOT_A,
                startedAtMs = NOW - 120_000L,
            ),
        )
        val report = h.coordinator.recoverOrphanedAttempts(BOOT_B, NOW)
        assertEquals(1, report.abandonedStale)
        assertEquals(AttemptState.ABANDONED_PRE_SOCKET, h.attempts.getById("att-stale")!!.state)
    }

    // =====================================================================
    // F32 — drain intent: no claim while PENDING; abort resumes legacy
    // =====================================================================

    @Test
    fun f32_abortResumesLegacyClaiming() = runBlocking {
        val h = newHarness()
        h.coordinator.requestDrain(NOW)
        val abort = h.coordinator.abortCutover(NOW)
        assertTrue("abort must succeed: $abort", abort is CutoverCommit.Success)
        // Row 5: LEGACY_ONLY / ABORTED — a legal combination.
        assertEquals(CutoverMode.LEGACY_ONLY, h.coordinator.getMode())
        assertEquals(DrainIntent.ABORTED, h.coordinator.getDrainIntent())
        assertTrue(CutoverCoordinator.isLegalCombination(CutoverMode.LEGACY_ONLY, DrainIntent.ABORTED))
        // The legacy sender resumes claiming (presenting the current drain generation).
        val deliveryId = h.seedDelivery(dedupeKey("f32"))
        val admitted = h.coordinator.admitAttempt(
            admissionRequest(deliveryId, dedupeKey("f32"), drainGeneration = 1L),
            AdmissionPath.LEGACY, NOW,
        )
        assertTrue("legacy admission must succeed after abort: $admitted", admitted is AdmissionResult.Admitted)
    }

    // =====================================================================
    // F36 — admission vs install: exactly one of the two orders
    // =====================================================================

    @Test
    fun f36_admissionVsInstall_bothOrders() = runBlocking {
        // Order 1: marker commit -> install -> POST. The install observes
        // the unresolved marker; the release waits for it; the POST proceeds.
        val h1 = newHarness()
        val deliveryId1 = h1.seedDelivery(dedupeKey("f36a"))
        val admitted = h1.coordinator.admitAttempt(
            admissionRequest(deliveryId1, dedupeKey("f36a")),
            AdmissionPath.LEGACY, NOW,
        )
        assertTrue(admitted is AdmissionResult.Admitted)
        val requested = h1.coordinator.requestDrain(NOW)
        assertTrue(requested is CutoverCommit.Success)
        val blocked = h1.coordinator.commitDraining(NOW)
        assertTrue("commit must wait for the awaited attempt: $blocked", blocked is CutoverCommit.Rejected)
        // The POST proceeds and completes; the release follows the checkpoint.
        val attemptId = (admitted as AdmissionResult.Admitted).attemptId
        assertEquals(1, h1.attempts.markCompleted(attemptId, NOW, 200, "MATCHED_SETTLED", null, 100L, "ok", "[]"))
        val committed = h1.coordinator.commitDraining(NOW)
        assertTrue("commit must succeed after the awaited attempt resolves: $committed", committed is CutoverCommit.Success)

        // Order 2: install -> admission -> POST. Admission is refused; no marker, no socket.
        val h2 = newHarness()
        h2.coordinator.requestDrain(NOW)
        val refused = h2.coordinator.admitAttempt(
            admissionRequest("d-f36b", dedupeKey("f36b"), drainGeneration = 1L),
            AdmissionPath.LEGACY, NOW,
        )
        assertEquals(AdmissionResult.Rejected(AdmissionRejection.DRAIN_PENDING), refused)
        assertTrue(h2.attempts.getAllUnresolved().isEmpty())
    }

    // =====================================================================
    // F37a / F37b — restart with an orphaned attempt
    // =====================================================================

    @Test
    fun f37a_restartWithoutIntent_staysLegacyOnly() = runBlocking {
        val h = newHarness()
        val deliveryId = h.seedDelivery(dedupeKey("f37a"))
        h.attempts.insert(attemptRow("att-f37a", deliveryId, dedupeKey("f37a"), AttemptState.ATTEMPT_STARTED, BOOT_A))
        // Kill with no HTTP opened; restart as boot-B.
        val restarted = h.restart()
        restarted.setBootId(BOOT_B)
        // Row 1: LEGACY_ONLY / NONE — the coordinator does NOT enter DRAINING.
        assertEquals(CutoverMode.LEGACY_ONLY, restarted.coordinator.getMode())
        assertEquals(DrainIntent.NONE, restarted.coordinator.getDrainIntent())
        // The orphan is classified per §8.5 (UNKNOWN while F40 is unpassed).
        val report = restarted.coordinator.recoverOrphanedAttempts(BOOT_B, NOW)
        assertEquals(1, report.unknownHeld)
        // A fresh capture is admitted and dispatched normally by the legacy sender.
        val freshDelivery = restarted.seedDelivery(dedupeKey("f37a-fresh"))
        val admitted = restarted.coordinator.admitAttempt(
            admissionRequest(freshDelivery, dedupeKey("f37a-fresh"), ownerBootId = BOOT_B),
            AdmissionPath.LEGACY, NOW,
        )
        assertTrue(admitted is AdmissionResult.Admitted)
    }

    @Test
    fun f37b_restartWithPendingIntent_stagedHeld() = runBlocking {
        val h = newHarness()
        val deliveryId = h.seedDelivery(dedupeKey("f37b"))
        h.attempts.insert(attemptRow("att-f37b", deliveryId, dedupeKey("f37b"), AttemptState.ATTEMPT_STARTED, BOOT_A))
        h.coordinator.requestDrain(NOW)
        // Kill; restart as boot-B.
        val restarted = h.restart()
        restarted.setBootId(BOOT_B)
        // Row 2: the durable intent re-fences the sender before any claim.
        assertEquals(DrainIntent.PENDING, restarted.coordinator.getDrainIntent())
        val refused = restarted.coordinator.admitAttempt(
            admissionRequest(deliveryId, dedupeKey("f37b"), ownerBootId = BOOT_B, drainGeneration = 1L),
            AdmissionPath.LEGACY, NOW,
        )
        assertEquals(AdmissionResult.Rejected(AdmissionRejection.DRAIN_PENDING), refused)
        // After the intent is ABORTED (row 5), the legacy sender dispatches again.
        restarted.coordinator.abortCutover(NOW)
        val freshDelivery = restarted.seedDelivery(dedupeKey("f37b-fresh"))
        val admitted = restarted.coordinator.admitAttempt(
            admissionRequest(freshDelivery, dedupeKey("f37b-fresh"), ownerBootId = BOOT_B, drainGeneration = 1L),
            AdmissionPath.LEGACY, NOW,
        )
        assertTrue(admitted is AdmissionResult.Admitted)
    }

    // =====================================================================
    // F38 — the fence invariant (f'')
    // =====================================================================

    @Test
    fun f38_spWriteFailureMidSweep_fenceStaysActive() = runBlocking {
        val item = queueItem(dedupeKey("f38a"))
        val h = newHarness(listOf(item))
        h.coordinator.requestDrain(NOW)
        h.coordinator.commitDraining(NOW)
        val fenceId = h.coordinator.getPhaseMarker()!!.fenceId
        // The SharedPreferences write fails mid-sweep.
        h.queue.failSaveCount = 1
        val drain = h.coordinator.drain(inspectorFor(), NOW)
        assertFalse(drain.ok)
        // The fence stays ACTIVE, the intent stays, no record, zero POSTs for the copy.
        val fenceDoc = h.state.getValue(CollectorStateKeys.SWEEP_FENCE_PREFIX + dedupeKey("f38a"))
        assertNotNull("per-event fence must stay ACTIVE after an SP write failure", fenceDoc)
        val intentDoc = h.state.getValue(CollectorStateKeys.SWEEP_INTENT_PREFIX + "$fenceId:${item.optString("qkey")}")
        assertNotNull("sweep intent must be retained for retry", intentDoc)
        assertNull(h.state.getValue(CollectorStateKeys.SWEEP_RECORD_PREFIX + dedupeKey("f38a")))
        assertEquals(listOf(item.optString("qkey")), h.queue.qkeys())
        // A retry after the failure clears completes the sweep.
        val retry = h.coordinator.drain(inspectorFor(), NOW)
        assertTrue("retry must succeed: ${retry.error}", retry.ok)
        assertNull("fence released together with the record", h.state.getValue(CollectorStateKeys.SWEEP_FENCE_PREFIX + dedupeKey("f38a")))
        assertNotNull(h.state.getValue(CollectorStateKeys.SWEEP_RECORD_PREFIX + dedupeKey("f38a")))
        assertTrue(h.queue.qkeys().isEmpty())
    }

    @Test
    fun f38_crashBetweenQueueWriteAndRecord_resumesIdempotently() = runBlocking {
        val item = queueItem(dedupeKey("f38b"))
        val h = newHarness(listOf(item))
        h.coordinator.requestDrain(NOW)
        h.coordinator.commitDraining(NOW)
        val fenceId = h.coordinator.getPhaseMarker()!!.fenceId
        // Import the item, then simulate the crash state directly: the
        // queue write committed, the record did not (intent + fence remain).
        val snapshot = h.migration.snapshot(listOf(item), NOW)
        val partitioned = h.migration.partition(snapshot, inspectorFor())
        h.migration.import(partitioned, NOW)
        val parsed = partitioned.importable.first()
        h.tx.run {
            h.state.put(
                CollectorStateEntity(
                    CollectorStateKeys.SWEEP_INTENT_PREFIX + "$fenceId:${item.optString("qkey")}",
                    SweepIntent(
                        "$fenceId:${item.optString("qkey")}", fenceId,
                        item.optString("qkey"), dedupeKey("f38b"), 0, NOW,
                    ).toJson().toString(),
                    NOW,
                ),
            )
            h.state.put(
                CollectorStateEntity(
                    CollectorStateKeys.SWEEP_FENCE_PREFIX + dedupeKey("f38b"),
                    SweepFence(dedupeKey("f38b"), "event_copy", 1L, NOW).toJson().toString(),
                    NOW,
                ),
            )
        }
        assertTrue(h.queue.saveQueueSync(emptyList()))
        // The resumed sweep completes the record; the item is not removed twice.
        val step = h.coordinator.sweepStep(parsed, fenceId, NOW)
        assertEquals(CutoverCoordinator.SweepStepResult.Removed, step)
        assertNotNull(h.state.getValue(CollectorStateKeys.SWEEP_RECORD_PREFIX + dedupeKey("f38b")))
        assertNull(h.state.getValue(CollectorStateKeys.SWEEP_FENCE_PREFIX + dedupeKey("f38b")))
        assertTrue(h.queue.qkeys().isEmpty())
    }

    @Test
    fun f38_noSecondSocketForOneAttemptId() = runBlocking {
        val h = newHarness()
        val row = attemptRow("att-once", "d-once", dedupeKey("f38c"), AttemptState.ATTEMPT_STARTED, BOOT_A)
        h.attempts.insert(row)
        try {
            h.attempts.insert(row.copy(deliveryId = "d-other"))
            fail("a second insert with the same attempt_id must fail")
        } catch (e: IllegalStateException) {
            // expected: the attempt_id is single-use
        }
        assertEquals(1, h.attempts.getByDeliveryId("d-once").size)
        assertTrue(h.attempts.getByDeliveryId("d-other").isEmpty())
    }

    // =====================================================================
    // F54 — the P9 stage gate (shared with LegacyQueuePrivacyGateTest, Stage C)
    // =====================================================================

    @Test
    fun f54_quarantineExcludedFromImportChecksumAndPreserved() = runBlocking {
        val clean1 = queueItem(dedupeKey("f54a"))
        val clean2 = queueItem(dedupeKey("f54b"))
        val quarantined = queueItem(dedupeKey("f54c"))
        val h = newHarness(listOf(clean1, clean2, quarantined))
        val drain = h.fullCutover(inspectorFor(setOf(quarantined.optString("qkey"))))
        assertEquals(2, drain.imported)
        assertEquals(1, drain.quarantined)
        // Quarantined items are excluded from the import checksum...
        val marker = h.coordinator.getPhaseMarker()!!
        assertEquals(2, marker.importableCount)
        assertEquals(1, marker.quarantinedCount)
        assertNotEquals(marker.importableChecksum, marker.snapshotChecksum)
        // ...and preserved through WORKER_ONLY, never imported.
        assertEquals(listOf(quarantined.optString("qkey")), h.queue.qkeys())
        assertNull(h.deliveries.getByLegacyQkey(quarantined.optString("qkey")))
        assertEquals(CutoverMode.WORKER_ONLY, h.coordinator.getMode())
    }

    @Test
    fun f54_noWholeQueueDelete() {
        // Reflection: no whole-queue delete exists on Store (no instantiation needed).
        val banned = setOf(
            "clearQueue", "deleteQueue", "clearAllQueue", "deleteAllQueue",
            "wipeQueue", "purgeQueue", "clearAll", "deleteAll",
        )
        val found = Store::class.java.methods.map { it.name }.filter { it in banned }
        assertTrue("whole-queue delete must not exist on Store: $found", found.isEmpty())
        // Source scan: no whole-queue delete anywhere in the tree
        // (the per-item clearQueueItem is allowed and must not match).
        val root = findSourceRoot()
            ?: throw AssertionError("source root not found — fail-closed")
        val pattern = Regex("""\bfun\s+(clearQueue|deleteQueue|clearAllQueue|deleteAllQueue|wipeQueue|purgeQueue)\s*\(""")
        val violations = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (pattern.containsMatchIn(line)) {
                    violations.add("${file.relativeTo(root)}:${index + 1}: ${line.trim()}")
                }
            }
        }
        assertTrue("whole-queue delete must not exist in the tree: $violations", violations.isEmpty())
    }

    @Test
    fun f54_cleanBytesUnchangedAndCursorPreserved() = runBlocking {
        val item = queueItem(dedupeKey("f54d"), next = 1, attempts = 3, lastError = "SocketTimeoutException")
        val h = newHarness(listOf(item))
        val snapshot = h.migration.snapshot(listOf(item), NOW)
        val partitioned = h.migration.partition(snapshot, inspectorFor())
        h.migration.import(partitioned, NOW)
        val delivery = h.deliveries.getByLegacyQkey(item.optString("qkey"))!!
        val event = h.events.getById(delivery.eventId)!!
        // Clean bytes unchanged: the stored payload is the original payload JSON.
        assertTrue(
            "legacy payload must equal the original payload",
            JSONObject(event.legacyPayloadJson).similar(item.optJSONObject("payload")),
        )
        // Per-item cursor and server-effect audit preserved.
        assertEquals(1, delivery.nextRouteIndex)
        assertEquals(3, delivery.attempts)
        assertEquals("SocketTimeoutException", delivery.lastErrorSummary)
        assertEquals(JSONArray(listOf(ROUTE_1, ROUTE_2)).toString(), delivery.routeIdsJson)
        assertEquals(dedupeKey("f54d"), event.dedupeKey)
    }

    @Test
    fun f54_preV11ItemCannotImportBeforeInspection() = runBlocking {
        val items = listOf(queueItem(dedupeKey("f54e1")), queueItem(dedupeKey("f54e2")))
        val h = newHarness(items)
        // An inspector that quarantines everything imports nothing.
        val drain = h.fullCutover(inspectorFor(items.map { it.optString("qkey") }.toSet()))
        assertEquals(0, drain.imported)
        assertEquals(2, drain.quarantined)
        assertEquals(0, h.events.count())
        assertEquals(2, h.queue.qkeys().size)
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Seeds one Room event + PENDING delivery; returns the delivery id. */
    private suspend fun Harness.seedDelivery(dedupe: String): String {
        val event = EventEntity(
            eventId = java.util.UUID.randomUUID().toString(),
            schemaVersion = 1,
            sourceType = "android_notification",
            sourceEventId = dedupe,
            deviceId = "moto-g57",
            eventType = "payment.notification",
            category = "payment",
            sourcePackage = "com.gojek.gopaymerchant",
            occurredAtMs = NOW,
            capturedAtMs = NOW,
            dataJson = "{}",
            metadataJson = "{}",
            legacyPayloadJson = null,
            privacyMode = "structured",
            retentionUntilMs = null,
            dedupeKey = dedupe,
            contentSha256 = "deadbeef",
            exportRef = java.util.UUID.randomUUID().toString(),
            sourceAccountKey = null,
        )
        events.insert(event)
        val delivery = DeliveryEntity(
            deliveryId = java.util.UUID.randomUUID().toString(),
            eventId = event.eventId,
            kind = com.netter.paylist.core.model.DeliveryKind.LEGACY_CHAIN,
            state = DeliveryState.PENDING,
            legacyQkey = null,
            routeIdsJson = JSONArray(listOf(ROUTE_1)).toString(),
            createdAtMs = NOW,
            updatedAtMs = NOW,
        )
        deliveries.insert(delivery)
        return delivery.deliveryId
    }

    /**
     * Locates `src/main/java` for the static checks (F27/F54).
     * Tries the module dir, then the project root, then walks up
     * from the working directory. Returns null when not found —
     * the callers fail closed.
     */
    private fun findSourceRoot(): File? {
        listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }?.let { return it }
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val appSrc = File(dir, "app/src/main/java")
            if (appSrc.isDirectory) return appSrc
            val src = File(dir, "src/main/java")
            if (src.isDirectory) return src
            dir = dir.parentFile
        }
        return null
    }
}
