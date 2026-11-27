package com.netter.paylist.collector

import com.netter.paylist.FakeCaptureLogDao
import com.netter.paylist.FakeCaptureStagingDao
import com.netter.paylist.FakeClassificationDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeStore
import com.netter.paylist.FakeTransactionRunner
import com.netter.paylist.Route
import com.netter.paylist.data.EventRepository
import com.netter.paylist.data.dao.CaptureLogDao
import com.netter.paylist.data.entity.CaptureLogEntity
import com.netter.paylist.data.entity.CaptureOutcome
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.migration.LegacyEnqueue
import com.netter.paylist.migration.LegacyQkey
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Stage D audit P1 (PR #5 review 6049617822): capture loss before
 * persistence — the bounded executor's overflow behaviour.
 *
 * The acceptance boundary under test (see [CaptureCoordinator]):
 * RECEIVED = `capture()` returned QUEUED (in the queue, not
 * durable); ACCEPTED = the staging write completed (the capture
 * survives process death); ENQUEUED = it reached exactly one
 * target store.
 *
 * These tests drive the REAL entrypoint — [CaptureCoordinator.capture]
 * — against a coordinator with a small executor queue (capacity 3;
 * production stays 500) and a wired fake capture-log DAO, so every
 * overflow drop must leave a durable OVERFLOW_DROPPED row.
 *
 * What is NOT claimed here: a true SIGKILL mid-flight cannot be
 * asserted inside this JVM suite — the test process itself would
 * die. The deterministic equivalent here is coordinator
 * replacement over the same durable store (`restartBoundary_...`
 * below) plus the `close()` drain evidence. The ACTUAL
 * process-kill evidence lives in the instrumented two-phase
 * harness (`ProcessDeathHarnessTest` in androidTest +
 * `scripts/process-death-harness.sh`): a file-backed Room
 * database, a real `kill -9` of the app process between the
 * setup and recovery phases, and exactly-once assertions after
 * the restart.
 */
class CaptureOverflowTest {

    companion object {
        const val PKG = "com.gojek.gopaymerchant"
        const val ROUTE_ID = "route-v2new"
        const val POST_TIME = 1_760_000_000_000L
        /** Small queue so saturation is deterministic (production: 500). */
        const val CAPACITY = 3
    }

    private lateinit var store: FakeStore
    private lateinit var eventDao: FakeEventDao
    private lateinit var deliveryDao: FakeDeliveryDao
    private lateinit var stagingDao: FakeCaptureStagingDao
    private lateinit var captureLogDao: FlakyCaptureLogDao

    /**
     * Capture-log DAO wrapper for the lifecycle tests: can fail
     * the next N inserts, commit-then-fail the next N inserts
     * (an uncertain commit — the row landed but the call threw),
     * and delay inserts (a slow durable write).
     */
    private class FlakyCaptureLogDao(
        private val delegate: CaptureLogDao,
    ) : CaptureLogDao by delegate {
        val failNextInserts = AtomicInteger(0)
        val commitThenFailNext = AtomicInteger(0)
        val insertDelayMs = AtomicLong(0)

        override suspend fun insert(row: CaptureLogEntity): Long {
            if (failNextInserts.getAndDecrement() > 0) {
                throw IllegalStateException("db down (test)")
            }
            val delay = insertDelayMs.get()
            if (delay > 0) Thread.sleep(delay)
            val result = delegate.insert(row)
            if (commitThenFailNext.getAndDecrement() > 0) {
                throw IllegalStateException("commit uncertain (test)")
            }
            return result
        }
    }

    /** While true, the mode read ignores interrupts and keeps waiting for its latch. */
    private val stubbornModeRead = AtomicBoolean(false)

    /** While true, the legacy enqueue reports failure (captures park). */
    private val enqueueShouldFail = AtomicBoolean(false)
    private lateinit var repository: EventRepository
    private lateinit var legacyQueue: MutableList<JSONObject>
    private lateinit var logLines: MutableList<String>
    private var mode: CutoverMode = CutoverMode.LEGACY_ONLY
    private lateinit var coordinator: CaptureCoordinator

    /** When non-null, the executor thread blocks here (mode read) until released. */
    private var modeReaderLatch: CountDownLatch? = null

    private val routes = listOf(
        Route(
            id = ROUTE_ID,
            name = "v2New (Netter)",
            packages = listOf(PKG),
            url = "https://bill.netterhome.net/bayarQR/notify.php",
            keywords = Route.DEFAULT_KEYWORDS,
            enabled = true,
        )
    )

    @Before
    fun setUp() {
        store = FakeStore()
        eventDao = FakeEventDao(store)
        deliveryDao = FakeDeliveryDao(store)
        stagingDao = FakeCaptureStagingDao(store)
        captureLogDao = FlakyCaptureLogDao(FakeCaptureLogDao(store))
        repository = EventRepository(
            eventDao, deliveryDao, stagingDao,
            FakeClassificationDao(store), FakeTransactionRunner(store),
        )
        legacyQueue = mutableListOf()
        logLines = mutableListOf()
        coordinator = newCoordinator()
    }

    @After
    fun tearDown() {
        runCatching { coordinator.close() }
    }

    private fun newCoordinator(
        closeTimeoutMs: Long = CaptureCoordinator.CLOSE_ACTIVE_TIMEOUT_MS,
    ): CaptureCoordinator = CaptureCoordinator(
        repository = repository,
        modeReader = {
            val latch = modeReaderLatch
            if (latch != null) {
                if (stubbornModeRead.get()) {
                    // A capture task that does NOT honour the
                    // close() interrupt: it keeps waiting for its
                    // latch (bounded) so close() must exhaust its
                    // bounded join and account the task unresolved.
                    val deadline = System.currentTimeMillis() + 30_000L
                    while (latch.count > 0 &&
                        System.currentTimeMillis() < deadline
                    ) {
                        try {
                            latch.await(50, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            // Swallowed on purpose (stubborn task).
                        }
                    }
                } else {
                    try {
                        latch.await(10, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        // Simulated death interrupts the executor thread;
                        // the in-flight capture completes normally
                        // instead of failing noisily (its staging row
                        // is already durable either way).
                        Thread.currentThread().interrupt()
                    }
                }
            }
            mode
        },
        legacyEnqueue = LegacyEnqueue { payload, kind, routeIds ->
            if (enqueueShouldFail.get()) {
                false
            } else {
                val qkey = LegacyQkey.build(
                    payload.optString("dedupe_key"), kind, routeIds,
                )
                if (legacyQueue.none { it.optString("qkey") == qkey }) {
                    legacyQueue.add(JSONObject().apply {
                        put("kind", kind)
                        put("route_ids", org.json.JSONArray(routeIds))
                        put("next", 0)
                        put("payload", payload)
                        put("qkey", qkey)
                    })
                }
                true
            }
        },
        routesProvider = { routes },
        deviceIdProvider = { "g57" },
        logger = object : CaptureLogSink {
            override fun logEvent(pkg: String, text: String) {
                logLines.add("$pkg: $text")
            }
            override fun logLocal(text: String) {
                logLines.add(text)
            }
        },
        clock = { POST_TIME + 5_000L },
        captureLogDao = captureLogDao,
        executorCapacity = CAPACITY,
        closeActiveTimeoutMs = closeTimeoutMs,
    )

    private fun snapshot(
        title: String = "Pembayaran berhasil",
        text: String = "Pembayaran Rp100.800 berhasil",
        bigText: String = "",
        pkg: String = PKG,
        postTime: Long = POST_TIME,
        flags: Int = 0,
    ) = NotificationSnapshot(
        key = "key-$postTime",
        packageName = pkg,
        postTimeMs = postTime,
        flags = flags,
        title = title,
        text = text,
        bigText = bigText,
        capturedAtMs = postTime + 1_000L,
    )

    private fun overflowRows(): List<CaptureLogEntity> =
        store.captureLog.values.filter { it.outcome == CaptureOutcome.OVERFLOW_DROPPED }

    /** Polls until [check] holds, or fails after the timeout. */
    private suspend fun awaitCondition(
        message: String,
        timeoutMs: Long = 5_000L,
        check: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { check() }.getOrDefault(false)) return
            Thread.sleep(50)
        }
        throw AssertionError("timeout menunggu: $message")
    }

    /**
     * Blocks the executor thread deterministically: submit a capture
     * that stages (ACCEPTED) and then waits on the latch in the mode
     * read. Returns the latch; the caller releases it when the queue
     * is arranged. The blocker is released by the caller in a
     * `finally` (via [CountDownLatch.countDown]) so a failed
     * assertion never hangs the test run.
     */
    private suspend fun blockExecutorWith(
        postTime: Long,
    ): CountDownLatch {
        val latch = CountDownLatch(1)
        modeReaderLatch = latch
        assertEquals(
            CaptureCoordinator.CaptureReceipt.QUEUED,
            coordinator.capture(snapshot(postTime = postTime)),
        )
        awaitCondition("blocker staged (executor thread held)") {
            eventDao.count() == 1
        }
        return latch
    }

    // --- Saturation: notifications only ---

    @Test
    fun saturation_oldestQueuedEvicted_durableOverflowRow_noSilentLoss() = runBlocking {
        val latch = blockExecutorWith(POST_TIME)
        try {
            // Queue (capacity 3) fills with V1..V3; the executor
            // thread is held by the blocker.
            for (i in 1..3) {
                assertEquals(
                    CaptureCoordinator.CaptureReceipt.QUEUED,
                    coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L)),
                )
            }
            // One more: the queue is full, so the oldest queued
            // snapshot (V1) is evicted to admit V4.
            assertEquals(
                CaptureCoordinator.CaptureReceipt.QUEUED,
                coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L)),
            )

            // The eviction DECISION is synchronous in capture()
            // (the counter already moved), but the durable row is
            // written by the overflow-writer thread — never by a
            // blocking insert on this (listener) thread. The
            // writer is independent of the blocked capture worker,
            // so the row lands while the latch is still closed.
            assertEquals(1, coordinator.counters().droppedOverflow)
            awaitCondition("durable overflow row written by the writer thread") {
                overflowRows().size == 1
            }
            val rows = overflowRows()
            assertEquals("one durable overflow row for the evicted snapshot", 1, rows.size)
            val row = rows.single()
            assertEquals(PKG, row.sourcePackage)
            assertEquals(POST_TIME + 1_000L, row.postTimeMs)
            assertNull(
                "consent policy: overflow rows store NO title text " +
                    "(the drop precedes route/consent evaluation)",
                row.titleSnippet,
            )
            assertNull("no amount parsed before the drop", row.amountMinor)
            assertNull("overflow rows are never linked to an event", row.eventId)
            assertNull("no hash of un-gated raw text", row.contentSha256)
        } finally {
            latch.countDown()
        }
        // The queue is still full ([V2, V3, V4]) right after the
        // release: a sweepNow() here would have to evict a
        // notification to get in (correct per policy, wrong for
        // this test). Wait for the executor to drain first, then
        // sweepNow() as a final sync.
        awaitCondition("admitted captures enqueued") { legacyQueue.size == 4 }
        coordinator.sweepNow()

        // Every submitted snapshot is accounted for: 5 submitted =
        // 4 events (blocker + V2..V4) + 1 durable overflow row (V1).
        assertEquals(4, eventDao.count())
        assertEquals(4, legacyQueue.size)
        assertEquals(0, stagingDao.count())
        val dedupeV1 = CaptureCoordinator.legacyDedupeKey(
            PKG, POST_TIME + 1_000L, 100800L,
            snapshot(postTime = POST_TIME + 1_000L).combined,
        )
        assertNull("evicted snapshot never became an event", eventDao.getByDedupeKey(dedupeV1))
        assertEquals("accepted (staged) count", 4, coordinator.counters().staged)
        assertEquals("received count", 5, coordinator.counters().received)
    }

    // --- Saturation: sweep/control tasks are never silently dropped ---

    @Test
    fun queueFull_sweepTaskAdmittedByEviction_neverSilentlyDropped() = runBlocking {
        val latch = blockExecutorWith(POST_TIME)
        try {
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            // A sweep submitted against the full queue must NOT be
            // silently dropped: it evicts the oldest notification
            // (V1) and runs once the executor drains. sweepNow()
            // blocks, so submit it from a helper thread.
            var sweepError: Throwable? = null
            val sweepThread = Thread {
                try {
                    coordinator.sweepNow()
                } catch (t: Throwable) {
                    sweepError = t
                }
            }
            sweepThread.start()
            // The eviction decision happens synchronously inside
            // the helper thread's sweepNow(); the overflow-writer
            // thread persists the durable record.
            awaitCondition("sweep evicted V1 with an overflow row") {
                overflowRows().size == 1
            }
            assertEquals(POST_TIME + 1_000L, overflowRows().single().postTimeMs)
            assertEquals(0, coordinator.counters().controlInline)
            latch.countDown()
            sweepThread.join(10_000)
            assertFalse("sweepNow() must return (no hang)", sweepThread.isAlive)
            assertNull("sweepNow() must not throw", sweepError)
        } finally {
            latch.countDown()
        }
        coordinator.sweepNow()

        // Blocker + V2 + V3 became events; V1 has only its overflow row.
        assertEquals(3, eventDao.count())
        assertEquals(3, legacyQueue.size)
        assertEquals(0, stagingDao.count())
        assertEquals(1, overflowRows().size)
    }

    // --- Restart boundary: ACCEPTED survives, RECEIVED-only does not ---

    @Test
    fun restartBoundary_acceptedSurvives_receivedOnlyLeavesOverflowEvidence() = runBlocking {
        val latch = blockExecutorWith(POST_TIME)
        // V1, V2 are RECEIVED-only: queued, never staged (the
        // executor thread is held by the blocker).
        coordinator.capture(snapshot(postTime = POST_TIME + 1_000L))
        coordinator.capture(snapshot(postTime = POST_TIME + 2_000L))
        // Simulated death: close() interrupts the executor thread
        // and drains the queued snapshots into durable overflow
        // records (graceful-shutdown evidence — a SIGKILL would give
        // no chance to run this; see the class KDoc).
        coordinator.close()
        latch.countDown()

        // The drained snapshots have durable evidence but no event;
        // their live recovery is rescan-only while still posted.
        val rows = overflowRows()
        assertEquals(2, rows.size)
        assertEquals(
            setOf(POST_TIME + 1_000L, POST_TIME + 2_000L),
            rows.map { it.postTimeMs }.toSet(),
        )
        assertEquals("only the blocker staged", 1, eventDao.count())

        // "Process restart": a new coordinator over the same durable
        // store, then the startup sweep.
        coordinator = newCoordinator()
        coordinator.sweepNow()

        // The ACCEPTED blocker is promoted exactly once; the
        // RECEIVED-only victims stay absent (no event, no delivery,
        // no duplicate) — each accounted for by its overflow row.
        assertEquals(1, eventDao.count())
        assertEquals(1, legacyQueue.size)
        assertEquals(0, stagingDao.count())
        coordinator.sweepNow()
        assertEquals("second sweep is a no-op", 1, legacyQueue.size)
        assertEquals(2, overflowRows().size)
    }

    // --- Capture after shutdown ---

    @Test
    fun captureAfterClose_droppedOverflowReceipt_countedRecordLost_noThrow() = runBlocking {
        coordinator.close()
        // The overflow writer is terminated by close() now (PR #5
        // re-review 27 Nov — it no longer outlives the coordinator
        // retaining the DAO), so a post-close capture cannot get a
        // durable row any more: the drop is counted as a lost
        // overflow record, explicitly, and capture() still never
        // throws to the caller.
        assertFalse(coordinator.isOverflowWriterAlive())
        assertEquals(
            CaptureCoordinator.CaptureReceipt.DROPPED_OVERFLOW,
            coordinator.capture(snapshot()),
        )
        assertEquals(1, coordinator.counters().droppedOverflow)
        assertEquals(1, coordinator.counters().overflowRecordDropped)
        assertEquals(0, overflowRows().size)
        assertEquals(0, eventDao.count())
        assertTrue(coordinator.degradedState().degraded)
    }

    // --- Shutdown / lifecycle (PR #5 re-review 27 Nov) ---

    @Test
    fun closeWithActivePreStage_resolvesActiveTaskExactlyOnce() = runBlocking {
        val latch = blockExecutorWith(POST_TIME)
        // The blocker is ACTIVE (staged, held in its mode read).
        // close() must settle it — grace, interrupt, bounded join —
        // not return while it is still in flight.
        val closeThread = Thread { coordinator.close() }
        closeThread.start()
        closeThread.join(15_000)
        assertFalse("close() must return (bounded)", closeThread.isAlive)
        latch.countDown()
        // The interrupt honoured by the mode read let the active
        // capture complete exactly once: staged + enqueued, no
        // unresolved accounting, no duplicate after a sweep.
        assertEquals(1, eventDao.count())
        assertEquals(1, legacyQueue.size)
        assertEquals(0, coordinator.counters().unresolvedAtClose)
        assertFalse(coordinator.isCaptureWorkerAlive())
        assertFalse(coordinator.isOverflowWriterAlive())
        coordinator = newCoordinator()
        coordinator.sweepNow()
        assertEquals("no duplicate promotion after close", 1, legacyQueue.size)
    }

    @Test
    fun closeWithStuckActiveTask_unresolvedCountedExplicitly() = runBlocking {
        stubbornModeRead.set(true)
        coordinator = newCoordinator(closeTimeoutMs = 400L)
        val latch = blockExecutorWith(POST_TIME)
        // close() exhausts its bounded wait (the active task
        // swallows the interrupt): it must still return, with the
        // active capture accounted as UNRESOLVED — not silently
        // abandoned, not assumed lost or saved.
        val started = System.currentTimeMillis()
        coordinator.close()
        val elapsed = System.currentTimeMillis() - started
        assertTrue("close() bounded (was ${elapsed}ms)", elapsed < 15_000)
        assertEquals(1, coordinator.counters().unresolvedAtClose)
        assertTrue(coordinator.degradedState().degraded)
        assertEquals(
            1,
            coordinator.degradedState().unresolvedAtClose,
        )
        // Cleanup: release the task; it completes (its staging was
        // already durable) and the worker then exits on its own.
        latch.countDown()
        awaitCondition("stuck worker exits after release") {
            !coordinator.isCaptureWorkerAlive()
        }
        assertEquals(1, eventDao.count())
        assertEquals(1, legacyQueue.size)
    }

    @Test
    fun closeRacingAdmission_noHangNoThrowAccountingConsistent() = runBlocking {
        val receipts = java.util.Collections.synchronizedList(
            mutableListOf<CaptureCoordinator.CaptureReceipt>(),
        )
        val errors = java.util.Collections.synchronizedList(
            mutableListOf<Throwable>(),
        )
        val producers = (0 until 4).map { t ->
            Thread {
                try {
                    for (i in 1..50) {
                        receipts.add(
                            coordinator.capture(
                                snapshot(postTime = POST_TIME + (t * 50 + i) * 1_000L),
                            ),
                        )
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        producers.forEach { it.start() }
        Thread.sleep(50)
        coordinator.close()
        producers.forEach { it.join(15_000) }
        producers.forEach { assertFalse("producer must not hang", it.isAlive) }
        assertTrue("capture() never throws, even racing close: $errors",
            errors.isEmpty())
        assertEquals(200, receipts.size)
        val c = coordinator.counters()
        assertEquals(200, c.received)
        // Every received snapshot is accounted: staged (an event),
        // overflow-dropped (queued-at-close, post-close, or
        // saturation), or explicitly unresolved. Nothing vanishes.
        assertTrue(
            "staged+dropped+unresolved must cover all receipts " +
                "(staged=${c.staged}, dropped=${c.droppedOverflow}, " +
                "unresolved=${c.unresolvedAtClose})",
            c.staged + c.droppedOverflow + c.unresolvedAtClose >= 200,
        )
        assertEquals(0, c.overflowPending)
        assertFalse(coordinator.isOverflowWriterAlive())
    }

    @Test
    fun stableOverflowId_retryAfterUncertainCommit_noDuplicateRow() = runBlocking {
        val latch = blockExecutorWith(POST_TIME)
        try {
            awaitCondition("blocker capture-log row written") {
                store.captureLog.values.any {
                    it.outcome == CaptureOutcome.CAPTURED
                }
            }
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            // The overflow record's first insert COMMITS but the
            // call throws (uncertain commit). The retry must reuse
            // the same capture_id, so the IGNORE-on-conflict insert
            // cannot create a second row (PR #5 re-review 27 Nov).
            captureLogDao.commitThenFailNext.set(1)
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            awaitCondition("overflow row persisted") { overflowRows().size == 1 }
            Thread.sleep(300)
            assertEquals(
                "exactly one row after the uncertain-commit retry",
                1, overflowRows().size,
            )
            assertEquals(POST_TIME + 1_000L, overflowRows().single().postTimeMs)
            assertTrue(coordinator.counters().captureLogFailures >= 1)
            assertEquals(0, coordinator.counters().overflowRecordDropped)
            awaitCondition("outstanding settled after retry success") {
                coordinator.counters().overflowPending == 0L
            }
        } finally {
            latch.countDown()
        }
        awaitCondition("admitted captures enqueued") { legacyQueue.size == 4 }
    }

    @Test
    fun permanentDbFailure_writerGivesUpBounded_closeTerminatesWriter() = runBlocking {
        val latch = blockExecutorWith(POST_TIME)
        try {
            awaitCondition("blocker capture-log row written") {
                store.captureLog.values.any {
                    it.outcome == CaptureOutcome.CAPTURED
                }
            }
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            captureLogDao.failNextInserts.set(Int.MAX_VALUE)
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            // The writer retries (bounded) and gives up: the record
            // is counted lost, pending returns to zero — the writer
            // does not spin forever on a dead database.
            awaitCondition("overflow record given up after retries") {
                coordinator.counters().overflowRecordDropped == 1L
            }
            // The outstanding decrement lands just after the
            // give-up count in the writer thread — poll for it
            // instead of racing it.
            awaitCondition("outstanding settled after give-up") {
                coordinator.counters().overflowPending == 0L
            }
            assertTrue(coordinator.counters().captureLogFailures >= 3)
        } finally {
            latch.countDown()
        }
        awaitCondition("admitted captures enqueued") { legacyQueue.size == 4 }
        captureLogDao.failNextInserts.set(0)
        val started = System.currentTimeMillis()
        coordinator.close()
        assertTrue(
            "close() bounded with a dead-then-healed DB",
            System.currentTimeMillis() - started < 15_000,
        )
        assertFalse(coordinator.isOverflowWriterAlive())
    }

    @Test
    fun close_flushWaitsForDelayedOverflowWrites_noPrematureEmpty() = runBlocking {
        val latch = blockExecutorWith(POST_TIME)
        try {
            awaitCondition("blocker capture-log row written") {
                store.captureLog.values.any {
                    it.outcome == CaptureOutcome.CAPTURED
                }
            }
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            // Every overflow insert now takes 600 ms. Evict V1,
            // then close immediately: close strands V2..V4 into
            // overflow records too, and its flush must wait for
            // ALL FOUR slow writes — the old take-to-active check
            // could observe "empty" while the first record was in
            // flight and return before any row existed.
            captureLogDao.insertDelayMs.set(600)
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            coordinator.close()
            assertEquals(
                "all four overflow rows persisted before close returned",
                4, overflowRows().size,
            )
            assertEquals(0, coordinator.counters().overflowPending)
            assertEquals(0, coordinator.counters().overflowRecordDropped)
        } finally {
            captureLogDao.insertDelayMs.set(0)
            latch.countDown()
        }
    }

    @Test
    fun sweepFailure_propagatesToCaller_notSwallowed() = runBlocking {
        // The staging read itself fails: sweepOrphanedStaging
        // throws before any per-row handling. sweepNow must
        // propagate that failure (PR #5 re-review 27 Nov) instead
        // of returning as if the sweep had succeeded.
        stagingDao.getAllFailure = IllegalStateException("staging read boom (test)")
        try {
            coordinator.sweepNow()
            throw AssertionError("sweepNow must propagate the sweep failure")
        } catch (e: IllegalStateException) {
            assertEquals("staging read boom (test)", e.message)
        } finally {
            stagingDao.getAllFailure = null
        }
        // And the coordinator is still usable afterwards.
        coordinator.sweepNow()
    }
}
