package com.netter.paylist.migration

import com.netter.paylist.FakeAttemptDao
import com.netter.paylist.FakeCollectorStateDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeStore
import com.netter.paylist.FakeTransactionRunner
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.PromotionReceiptDao
import com.netter.paylist.data.entity.PromotionReceiptEntity
import com.netter.paylist.data.entity.ReceiptState
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.locks.ReentrantLock

/**
 * R07 readback integrity (review 6011401446): a same-count queue
 * mutation (`qkey`, next hop, payload) must fail closed before
 * `commitDraining`.
 *
 * `PaylistApplication.runCutover` is gated by the
 * `CAPTURE_TO_ROOM_READY` const (false until Stage D), so these
 * tests exercise the extracted production unit instead — the same
 * code `runCutover` calls: [QueueFlushVerifier] for the pure
 * readback comparison, [CutoverFlushGate] for the fail-closed abort
 * (driven against the real [CutoverCoordinator] over fake DAOs, as
 * in `CutoverTest`).
 *
 * Two layers:
 * - Pure [QueueFlushVerifier.verifyReadback] tests: one fault per
 *   mutation class, plus the positive controls (identical round-trip,
 *   empty queue, reorder) and the checksum-coverage distinction.
 * - Gate tests: a [FaultInjectingQueueSource] whose `saveQueueSync`
 *   corrupts the stored copy while reporting success. Each mutation
 *   must abort to `LEGACY_ONLY` / `ABORTED` with `commitDraining`
 *   rejected afterwards — the `DRAINING` transition unreachable.
 *
 * The honest-fake controls (no fault, empty queue, reorder) prove
 * the tests do not merely always fail.
 */
class FlushReadbackTest {

    companion object {
        /** Fixed test time: 2026-11-18 ~04:00 WIB, in epoch ms. */
        const val NOW: Long = 1_795_000_000_000L

        const val BOOT_A: String = "boot-A"

        const val ROUTE_1: String = "route-1"
        const val ROUTE_2: String = "route-2"
    }

    // =====================================================================
    // Fakes
    // =====================================================================

    /** Minimal receipt store (R07 tests never import, so this is inert). */
    private class FakePromotionReceiptDao : PromotionReceiptDao {
        private val receipts = LinkedHashMap<String, PromotionReceiptEntity>()

        override suspend fun insert(receipt: PromotionReceiptEntity): Long {
            if (receipts.containsKey(receipt.legacyQkey)) return -1L
            receipts[receipt.legacyQkey] = receipt
            return 1L
        }

        override suspend fun getByLegacyQkey(qkey: String): PromotionReceiptEntity? =
            receipts[qkey]

        override suspend fun getUnresolved(): List<PromotionReceiptEntity> =
            receipts.values.filter {
                it.state == ReceiptState.PREPARING ||
                    it.state == ReceiptState.READY ||
                    it.state == ReceiptState.IN_FLIGHT
            }

        override suspend fun markReady(qkey: String): Int =
            transition(qkey, ReceiptState.PREPARING, ReceiptState.READY)

        override suspend fun markInFlight(qkey: String): Int =
            transition(qkey, ReceiptState.READY, ReceiptState.IN_FLIGHT)

        override suspend fun markResolved(qkey: String, now: Long, outcome: String): Int {
            val row = receipts[qkey] ?: return 0
            if (row.state != ReceiptState.IN_FLIGHT) return 0
            receipts[qkey] = row.copy(state = ReceiptState.RESOLVED, resolvedAtMs = now, outcome = outcome)
            return 1
        }

        private fun transition(qkey: String, from: ReceiptState, to: ReceiptState): Int {
            val row = receipts[qkey] ?: return 0
            if (row.state != from) return 0
            receipts[qkey] = row.copy(state = to)
            return 1
        }
    }

    /**
     * In-memory legacy queue with fault injection: [fault] rewrites
     * the STORED copy inside `saveQueueSync` while the call still
     * reports success — modelling silent persistence corruption
     * beneath the save call (a torn write, a concurrent writer, a
     * store anomaly). That is the exact failure R07's readback must
     * catch: the save's own boolean cannot be trusted, so the
     * readback above it must verify content. Null [fault] is an
     * honest store (the negative control).
     */
    private class FaultInjectingQueueSource(
        initialQueue: List<JSONObject> = emptyList(),
    ) : LegacyQueueSnapshotSource {
        private val queue: MutableList<JSONObject> =
            initialQueue.map { JSONObject(it.toString()) }.toMutableList()

        var fault: ((List<JSONObject>) -> List<JSONObject>)? = null

        /** When true, `saveQueueSync` reports failure without storing. */
        var failSave: Boolean = false

        override fun queueSnapshot(): List<JSONObject> = queue.map { JSONObject(it.toString()) }

        override fun saveQueueSync(items: List<JSONObject>): Boolean {
            if (failSave) return false
            val stored = items.map { JSONObject(it.toString()) }
            val faulty = fault?.invoke(stored) ?: stored
            queue.clear()
            queue.addAll(faulty.map { JSONObject(it.toString()) })
            return true
        }
    }

    // =====================================================================
    // Harness + fixtures
    // =====================================================================

    private class Harness(
        val state: FakeCollectorStateDao,
        val queue: FaultInjectingQueueSource,
        val coordinator: CutoverCoordinator,
    ) {
        suspend fun setBootId(bootId: String) {
            state.put(
                CollectorStateEntity(
                    "boot_id",
                    JSONObject().put("boot_id", bootId).put("boot_started_at_ms", NOW).toString(),
                    NOW,
                ),
            )
        }
    }

    private fun newHarness(queueItems: List<JSONObject> = baseQueue()): Harness {
        val store = FakeStore()
        val tx = FakeTransactionRunner(store)
        val events = FakeEventDao(store)
        val deliveries = FakeDeliveryDao(store)
        val attempts = FakeAttemptDao(store)
        val state = FakeCollectorStateDao(store)
        val receipts = FakePromotionReceiptDao()
        val queue = FaultInjectingQueueSource(queueItems)
        val migration = LegacyQueueMigration(events, deliveries, attempts, receipts, tx)
        val coordinator = CutoverCoordinator(
            state, deliveries, attempts, tx, migration, queue, ReentrantLock(), false,
        )
        val harness = Harness(state, queue, coordinator)
        runBlocking { harness.setBootId(BOOT_A) }
        return harness
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

    private fun queueItem(
        dedupeKey: String,
        kind: String = LegacyQkey.KIND_CHAIN,
        routes: List<String> = listOf(ROUTE_1, ROUTE_2),
        next: Int = 0,
        attempts: Int = 0,
        lastError: String? = null,
        payload: JSONObject? = null,
        hopOutcomes: JSONArray? = null,
        lastTryMs: Long? = null,
        privacyReprocessed: Boolean = false,
        privacyQuarantined: Boolean = false,
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
            if (lastTryMs != null) put("last_try", lastTryMs)
            if (privacyReprocessed) put("privacy_reprocessed", true)
            if (privacyQuarantined) put("privacy_quarantine", true)
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

    /** Three valid items: plain chain, rich chain, single. */
    private fun baseQueue(): List<JSONObject> = listOf(
        queueItem(dedupeKey("a")),
        queueItem(
            dedupeKey("b"),
            next = 1,
            attempts = 2,
            lastError = "timeout",
            hopOutcomes = JSONArray().put(hop(ROUTE_1, "LOGGED")),
            lastTryMs = NOW - 60_000L,
            privacyReprocessed = true,
        ),
        queueItem(dedupeKey("c"), kind = LegacyQkey.KIND_SINGLE, routes = listOf(ROUTE_1)),
    )

    /** Unparseable item: no `route_ids`, no `payload` (`fromJson` returns null). */
    private fun corruptItem(): JSONObject = JSONObject().apply {
        put("qkey", "corrupt-1")
        put("kind", LegacyQkey.KIND_CHAIN)
    }

    /** Item carrying both `route_ids` and `route_id` (kind-flip fixture). */
    private fun dualRouteItem(): JSONObject = JSONObject().apply {
        put("qkey", LegacyQkey.build(dedupeKey("ganda"), LegacyQkey.KIND_CHAIN, listOf(ROUTE_1, ROUTE_2)))
        put("kind", LegacyQkey.KIND_CHAIN)
        put("route_ids", JSONArray(listOf(ROUTE_1, ROUTE_2)))
        put("route_id", ROUTE_1)
        put("next", 0)
        put("attempts", 0)
        put("payload", paymentPayload(dedupeKey("ganda")))
    }

    private fun deepCopy(items: List<JSONObject>): List<JSONObject> =
        items.map { JSONObject(it.toString()) }

    // =====================================================================
    // Faults: one per mutation class (each keeps the item count)
    // =====================================================================

    /** (a) One item's `qkey` changed. */
    private fun mutateQkey(items: List<JSONObject>): List<JSONObject> =
        deepCopy(items).also {
            it[0].put(
                "qkey",
                LegacyQkey.build(dedupeKey("zombi"), LegacyQkey.KIND_CHAIN, listOf(ROUTE_1, ROUTE_2)),
            )
        }

    /** (b) One item's `next` hop advanced. */
    private fun mutateNext(items: List<JSONObject>): List<JSONObject> =
        deepCopy(items).also { it[1].put("next", it[1].optInt("next") + 1) }

    /** (c) One item's payload `amount` changed. */
    private fun mutatePayloadAmount(items: List<JSONObject>): List<JSONObject> =
        deepCopy(items).also { it[0].getJSONObject("payload").put("amount", 999L) }

    /** (d) One item replaced by another item (different `qkey`), same count. */
    private fun replaceItem(items: List<JSONObject>): List<JSONObject> =
        deepCopy(items).toMutableList().also { it[1] = queueItem(dedupeKey("pengganti")) }

    /** (e) Item order reversed (must NOT fail: comparison is keyed by `qkey`). */
    private fun reorder(items: List<JSONObject>): List<JSONObject> = deepCopy(items).reversed()

    /** (f) One item duplicated, another deleted — same count. */
    private fun duplicatePlusDelete(items: List<JSONObject>): List<JSONObject> =
        deepCopy(listOf(items[0], items[0], items[2]))

    /** `route_ids` changed while the `qkey` stays put. */
    private fun mutateRouteIds(items: List<JSONObject>): List<JSONObject> =
        deepCopy(items).also { it[0].put("route_ids", JSONArray(listOf(ROUTE_2))) }

    /** `hop_outcomes` changed (beyond the §8.2 checksum's scope). */
    private fun mutateHopOutcomes(items: List<JSONObject>): List<JSONObject> =
        deepCopy(items).also {
            it[1].getJSONArray("hop_outcomes").getJSONObject(0).put("outcome", "REJECTED-4xx")
        }

    /** `last_try` changed (beyond the §8.2 checksum's scope). */
    private fun mutateLastTry(items: List<JSONObject>): List<JSONObject> =
        deepCopy(items).also { it[1].put("last_try", NOW) }

    /** `attempts` changed. */
    private fun mutateAttempts(items: List<JSONObject>): List<JSONObject> =
        deepCopy(items).also { it[1].put("attempts", it[1].optInt("attempts") + 1) }

    /** `last_error` changed. */
    private fun mutateLastError(items: List<JSONObject>): List<JSONObject> =
        deepCopy(items).also { it[1].put("last_error", "ditolak") }

    /** P9 privacy marker flipped. */
    private fun mutatePrivacy(items: List<JSONObject>): List<JSONObject> =
        deepCopy(items).also { it[1].put("privacy_quarantine", true) }

    /** `kind` flipped on the dual-route fixture (both route fields present). */
    private fun mutateKind(items: List<JSONObject>): List<JSONObject> =
        deepCopy(items).also { it[0].put("kind", LegacyQkey.KIND_SINGLE) }

    /** A corrupt item's raw JSON changed (still corrupt). */
    private fun mutateCorrupt(items: List<JSONObject>): List<JSONObject> =
        deepCopy(items).also { it[3].put("kind", LegacyQkey.KIND_SINGLE) }

    /** A valid item corrupted in place (payload removed). */
    private fun corruptInPlace(items: List<JSONObject>): List<JSONObject> =
        deepCopy(items).also { it[1].remove("payload") }

    // =====================================================================
    // Assertion helpers
    // =====================================================================

    private fun assertVerified(result: FlushResult) {
        assertTrue("expected Verified, got $result", result is FlushResult.Verified)
    }

    private fun assertFailedWith(result: FlushResult, needle: String) {
        assertTrue("expected Failed, got $result", result is FlushResult.Failed)
        val reason = (result as FlushResult.Failed).reason
        assertTrue("reason \"$reason\" should mention \"$needle\"", reason.contains(needle))
    }

    /** Runs `requestDrain` + the production gate over the harness queue. */
    private fun runGate(harness: Harness): FlushResult = runBlocking {
        val requested = harness.coordinator.requestDrain(NOW)
        assertTrue("requestDrain must succeed: $requested", requested is CutoverCommit.Success)
        val snapshot = harness.queue.queueSnapshot()
        CutoverFlushGate.flushOrAbort(harness.coordinator, harness.queue, snapshot, NOW)
    }

    /** After a failed readback: `LEGACY_ONLY` / `ABORTED`, `DRAINING` unreachable. */
    private fun assertAbortedBeforeDraining(harness: Harness) = runBlocking {
        assertEquals(CutoverMode.LEGACY_ONLY, harness.coordinator.getMode())
        assertEquals(DrainIntent.ABORTED, harness.coordinator.getDrainIntent())
        val draining = harness.coordinator.commitDraining(NOW)
        assertTrue(
            "commitDraining must be rejected after abort: $draining",
            draining is CutoverCommit.Rejected,
        )
    }

    private fun assertStillPending(harness: Harness) = runBlocking {
        assertEquals(CutoverMode.LEGACY_ONLY, harness.coordinator.getMode())
        assertEquals(DrainIntent.PENDING, harness.coordinator.getDrainIntent())
    }

    // =====================================================================
    // Pure readback: positive controls
    // =====================================================================

    @Test
    fun identicalRoundTrip_verifies() {
        val snapshot = baseQueue()
        assertVerified(QueueFlushVerifier.verifyReadback(snapshot, deepCopy(snapshot)))
    }

    @Test
    fun emptyQueue_verifies() {
        assertVerified(QueueFlushVerifier.verifyReadback(emptyList(), emptyList()))
    }

    @Test
    fun roundTripWithCorruptItem_verifies() {
        val snapshot = baseQueue() + corruptItem()
        assertVerified(QueueFlushVerifier.verifyReadback(snapshot, deepCopy(snapshot)))
    }

    @Test
    fun reorder_verifies() {
        // (e) Order is not identity: the comparison is keyed by `qkey`
        // (as is the design §8.2 checksum), so a reorder must NOT abort.
        val snapshot = baseQueue()
        assertVerified(QueueFlushVerifier.verifyReadback(snapshot, reorder(snapshot)))
    }

    // =====================================================================
    // Pure readback: one fault per mutation class
    // =====================================================================

    @Test
    fun qkeyChanged_fails() {
        val snapshot = baseQueue()
        assertFailedWith(QueueFlushVerifier.verifyReadback(snapshot, mutateQkey(snapshot)), "qkey")
    }

    @Test
    fun nextHopChanged_fails() {
        val snapshot = baseQueue()
        assertFailedWith(QueueFlushVerifier.verifyReadback(snapshot, mutateNext(snapshot)), "hop next")
    }

    @Test
    fun payloadAmountChanged_fails() {
        val snapshot = baseQueue()
        assertFailedWith(
            QueueFlushVerifier.verifyReadback(snapshot, mutatePayloadAmount(snapshot)),
            "payload",
        )
    }

    @Test
    fun itemReplaced_fails() {
        val snapshot = baseQueue()
        assertFailedWith(QueueFlushVerifier.verifyReadback(snapshot, replaceItem(snapshot)), "qkey")
    }

    @Test
    fun duplicatePlusDelete_fails() {
        val snapshot = baseQueue()
        assertFailedWith(
            QueueFlushVerifier.verifyReadback(snapshot, duplicatePlusDelete(snapshot)),
            "qkey",
        )
    }

    @Test
    fun routeIdsChanged_fails() {
        val snapshot = baseQueue()
        assertFailedWith(
            QueueFlushVerifier.verifyReadback(snapshot, mutateRouteIds(snapshot)),
            "route_ids",
        )
    }

    @Test
    fun hopOutcomesChanged_fails() {
        val snapshot = baseQueue()
        assertFailedWith(
            QueueFlushVerifier.verifyReadback(snapshot, mutateHopOutcomes(snapshot)),
            "hop_outcomes",
        )
    }

    @Test
    fun lastTryChanged_fails() {
        val snapshot = baseQueue()
        assertFailedWith(
            QueueFlushVerifier.verifyReadback(snapshot, mutateLastTry(snapshot)),
            "last_try",
        )
    }

    @Test
    fun attemptsChanged_fails() {
        val snapshot = baseQueue()
        assertFailedWith(
            QueueFlushVerifier.verifyReadback(snapshot, mutateAttempts(snapshot)),
            "attempts",
        )
    }

    @Test
    fun lastErrorChanged_fails() {
        val snapshot = baseQueue()
        assertFailedWith(
            QueueFlushVerifier.verifyReadback(snapshot, mutateLastError(snapshot)),
            "last_error",
        )
    }

    @Test
    fun privacyMarkerChanged_fails() {
        val snapshot = baseQueue()
        assertFailedWith(
            QueueFlushVerifier.verifyReadback(snapshot, mutatePrivacy(snapshot)),
            "privasi",
        )
    }

    @Test
    fun kindChanged_fails() {
        val snapshot = listOf(dualRouteItem())
        assertFailedWith(QueueFlushVerifier.verifyReadback(snapshot, mutateKind(snapshot)), "kind")
    }

    @Test
    fun corruptItemChanged_fails() {
        val snapshot = baseQueue() + corruptItem()
        assertFailedWith(
            QueueFlushVerifier.verifyReadback(snapshot, mutateCorrupt(snapshot)),
            "rusak",
        )
    }

    @Test
    fun itemCorruptedInPlace_fails() {
        val snapshot = baseQueue()
        assertFailedWith(
            QueueFlushVerifier.verifyReadback(snapshot, corruptInPlace(snapshot)),
            "rusak",
        )
    }

    @Test
    fun countChanged_fails() {
        // The old size check, preserved: a dropped item still fails.
        val snapshot = baseQueue()
        val dropped = deepCopy(snapshot).drop(1)
        assertFailedWith(QueueFlushVerifier.verifyReadback(snapshot, dropped), "jumlah item")
    }

    // =====================================================================
    // Checksum-coverage distinction
    // =====================================================================

    @Test
    fun checksumBlindSpot_hopOutcomesInvisibleToChecksum() {
        // The §8.2 checksum does NOT cover `hop_outcomes`: the checksum
        // is unchanged by this mutation, while the readback fails.
        // This is why the readback cannot reuse the checksum alone.
        val before = baseQueue()
        val after = mutateHopOutcomes(before)
        val parsedBefore = before.mapNotNull { LegacyQueueItem.fromJson(it) }
        val parsedAfter = after.mapNotNull { LegacyQueueItem.fromJson(it) }
        assertEquals(
            LegacyQueueMigration.checksum(parsedBefore),
            LegacyQueueMigration.checksum(parsedAfter),
        )
        assertFailedWith(QueueFlushVerifier.verifyReadback(before, after), "hop_outcomes")
    }

    @Test
    fun checksumCoversPayload_amountChangeChangesChecksum() {
        // The complementary half: a payload mutation DOES change the
        // §8.2 checksum, so the blind-spot test above is meaningful —
        // the checksum is not simply insensitive to everything.
        val before = baseQueue()
        val after = mutatePayloadAmount(before)
        val parsedBefore = before.mapNotNull { LegacyQueueItem.fromJson(it) }
        val parsedAfter = after.mapNotNull { LegacyQueueItem.fromJson(it) }
        assertNotEquals(
            LegacyQueueMigration.checksum(parsedBefore),
            LegacyQueueMigration.checksum(parsedAfter),
        )
    }

    // =====================================================================
    // Flush over a queue source: honest control + save failure
    // =====================================================================

    @Test
    fun flushAndVerify_honestQueue_verifies() {
        // Negative control: an honest store + the same snapshot passes.
        val queue = FaultInjectingQueueSource(baseQueue())
        val snapshot = queue.queueSnapshot()
        assertVerified(QueueFlushVerifier.flushAndVerify(queue, snapshot))
    }

    @Test
    fun flushAndVerify_emptyQueue_verifies() {
        val queue = FaultInjectingQueueSource()
        assertVerified(QueueFlushVerifier.flushAndVerify(queue, emptyList()))
    }

    @Test
    fun flushAndVerify_saveFailure_fails() {
        val queue = FaultInjectingQueueSource(baseQueue())
        queue.failSave = true
        assertFailedWith(
            QueueFlushVerifier.flushAndVerify(queue, queue.queueSnapshot()),
            "penulisan",
        )
    }

    // =====================================================================
    // Gate: fail closed before commitDraining (real coordinator)
    // =====================================================================

    @Test
    fun gate_honestQueue_verifiesAndStaysCommittable() = runBlocking {
        val harness = newHarness()
        assertVerified(runGate(harness))
        assertStillPending(harness)
        // The happy path is intact: DRAINING still commits afterwards.
        val draining = harness.coordinator.commitDraining(NOW)
        assertTrue("commitDraining must succeed on the happy path: $draining", draining is CutoverCommit.Success)
    }

    @Test
    fun gate_emptyQueue_verifies() = runBlocking {
        val harness = newHarness(emptyList())
        assertVerified(runGate(harness))
        assertStillPending(harness)
    }

    @Test
    fun gate_reorder_verifies() {
        val harness = newHarness()
        harness.queue.fault = ::reorder
        assertVerified(runGate(harness))
        assertStillPending(harness)
    }

    @Test
    fun gate_qkeyMutation_abortsBeforeCommitDraining() {
        val harness = newHarness()
        harness.queue.fault = ::mutateQkey
        assertFailedWith(runGate(harness), "qkey")
        assertAbortedBeforeDraining(harness)
    }

    @Test
    fun gate_nextHopMutation_abortsBeforeCommitDraining() {
        val harness = newHarness()
        harness.queue.fault = ::mutateNext
        assertFailedWith(runGate(harness), "hop next")
        assertAbortedBeforeDraining(harness)
    }

    @Test
    fun gate_payloadMutation_abortsBeforeCommitDraining() {
        val harness = newHarness()
        harness.queue.fault = ::mutatePayloadAmount
        assertFailedWith(runGate(harness), "payload")
        assertAbortedBeforeDraining(harness)
    }

    @Test
    fun gate_replacement_abortsBeforeCommitDraining() {
        val harness = newHarness()
        harness.queue.fault = ::replaceItem
        assertFailedWith(runGate(harness), "qkey")
        assertAbortedBeforeDraining(harness)
    }

    @Test
    fun gate_duplicatePlusDelete_abortsBeforeCommitDraining() {
        val harness = newHarness()
        harness.queue.fault = ::duplicatePlusDelete
        assertFailedWith(runGate(harness), "qkey")
        assertAbortedBeforeDraining(harness)
    }

    @Test
    fun gate_saveFailure_abortsBeforeCommitDraining() {
        val harness = newHarness()
        harness.queue.failSave = true
        assertFailedWith(runGate(harness), "penulisan")
        assertAbortedBeforeDraining(harness)
    }
}
