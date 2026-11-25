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
 * asserted in CI — the test process itself would die, and
 * instrumented tests use an in-memory database anyway. The
 * deterministic equivalent is coordinator replacement over the
 * same durable store (`restartBoundary_...` below) plus the
 * `close()` drain evidence. A file-backed-DB two-phase
 * instrumented kill test is future work, not claimed.
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
    private lateinit var captureLogDao: FakeCaptureLogDao
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
        captureLogDao = FakeCaptureLogDao(store)
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

    private fun newCoordinator(): CaptureCoordinator = CaptureCoordinator(
        repository = repository,
        modeReader = {
            val latch = modeReaderLatch
            if (latch != null) {
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
            mode
        },
        legacyEnqueue = LegacyEnqueue { payload, kind, routeIds ->
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

            // The eviction is synchronous in capture(): the durable
            // overflow record already exists before the latch opens.
            assertEquals(1, coordinator.counters().droppedOverflow)
            val rows = overflowRows()
            assertEquals("one durable overflow row for the evicted snapshot", 1, rows.size)
            val row = rows.single()
            assertEquals(PKG, row.sourcePackage)
            assertEquals(POST_TIME + 1_000L, row.postTimeMs)
            assertEquals("Pembayaran berhasil", row.titleSnippet)
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
            // The eviction happens synchronously inside the helper
            // thread's execute(): wait for the durable record.
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
    fun captureAfterClose_droppedOverflowReceipt_durableRow_noThrow() = runBlocking {
        coordinator.close()
        assertEquals(
            CaptureCoordinator.CaptureReceipt.DROPPED_OVERFLOW,
            coordinator.capture(snapshot()),
        )
        assertEquals(1, coordinator.counters().droppedOverflow)
        assertEquals(1, overflowRows().size)
        assertEquals(POST_TIME, overflowRows().single().postTimeMs)
        assertEquals(0, eventDao.count())
    }
}
