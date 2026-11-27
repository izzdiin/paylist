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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage D audit follow-up (PR #5): the CaptureCoordinator
 * rejection-handler P1. The old design admitted work from inside
 * a `RejectedExecutionHandler` that called `execute()` again
 * (re-entrant under concurrent producers; a normal return was
 * not proof of admission), ran sweep/control tasks inline on the
 * caller thread (breaking the capture worker's single-thread
 * ownership), wrote overflow rows with `runBlocking` on the
 * listener thread, and left only a volatile counter when the
 * database write failed.
 *
 * These tests pin the replacement design at the REAL entrypoints
 * ([CaptureCoordinator.capture] / [CaptureCoordinator.sweepNow]):
 * - admission is one bounded, non-recursive decision: under
 *   concurrent producers every snapshot is accounted for exactly
 *   once (event or durable overflow row), with no duplicates;
 * - a sweep under saturation runs ONLY on the capture worker
 *   thread, never concurrently with a capture;
 * - a failing overflow insert is retried by the writer thread
 *   and persisted once the database recovers — the evidence does
 *   not degrade to a volatile counter on a transient failure.
 *
 * The process-death-before-staging boundary is covered by
 * [CaptureOverflowTest.restartBoundary_acceptedSurvives_receivedOnlyLeavesOverflowEvidence]
 * (coordinator replacement over the same durable store); a true
 * SIGKILL mid-flight cannot be asserted in CI, as documented
 * there.
 */
class CaptureAdmissionTest {

    companion object {
        const val PKG = "com.gojek.gopaymerchant"
        const val ROUTE_ID = "route-v2new"
        const val POST_TIME = 1_760_000_100_000L
        const val CAPACITY = 3
    }

    /** A capture-log DAO that fails the next N inserts, then delegates. */
    private class FlakyCaptureLogDao(
        private val delegate: CaptureLogDao,
    ) : CaptureLogDao by delegate {
        val failNextInserts = AtomicInteger(0)

        override suspend fun insert(row: CaptureLogEntity): Long {
            if (failNextInserts.getAndDecrement() > 0) {
                throw IllegalStateException("db down (test)")
            }
            return delegate.insert(row)
        }
    }

    private lateinit var store: FakeStore
    private lateinit var eventDao: FakeEventDao
    private lateinit var deliveryDao: FakeDeliveryDao
    private lateinit var stagingDao: FakeCaptureStagingDao
    private lateinit var captureLogDao: FlakyCaptureLogDao
    private lateinit var repository: EventRepository
    private lateinit var legacyQueue: MutableList<JSONObject>
    private lateinit var logLines: MutableList<String>
    private var mode: CutoverMode = CutoverMode.LEGACY_ONLY
    private lateinit var coordinator: CaptureCoordinator

    /** When non-null, the worker thread blocks here (mode read) until released. */
    private var modeReaderLatch: CountDownLatch? = null

    /** Thread names observed inside the mode read (worker-thread evidence). */
    private val modeReaderThreads =
        Collections.synchronizedSet(mutableSetOf<String>())
    private val modeActive = AtomicInteger()
    private val modeMaxConcurrent = AtomicInteger()

    /** While true, the legacy enqueue reports failure (captures park). */
    private val enqueueShouldFail = AtomicBoolean(false)

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

    private fun newCoordinator(): CaptureCoordinator = CaptureCoordinator(
        repository = repository,
        modeReader = {
            modeReaderThreads.add(Thread.currentThread().name)
            val active = modeActive.incrementAndGet()
            modeMaxConcurrent.updateAndGet { maxOf(it, active) }
            try {
                val latch = modeReaderLatch
                if (latch != null) {
                    try {
                        latch.await(10, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                mode
            } finally {
                modeActive.decrementAndGet()
            }
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
    )

    private fun snapshot(
        postTime: Long,
        title: String = "Pembayaran berhasil",
        text: String = "Pembayaran Rp100.800 berhasil",
    ) = NotificationSnapshot(
        key = "key-$postTime",
        packageName = PKG,
        postTimeMs = postTime,
        flags = 0,
        title = title,
        text = text,
        bigText = "",
        capturedAtMs = postTime + 1_000L,
    )

    private fun overflowRows(): List<CaptureLogEntity> =
        store.captureLog.values.filter { it.outcome == CaptureOutcome.OVERFLOW_DROPPED }

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

    /** Blocks the worker thread deterministically (see CaptureOverflowTest). */
    private suspend fun blockWorkerWith(
        postTime: Long,
        expectedEvents: Int = 1,
    ): CountDownLatch {
        val latch = CountDownLatch(1)
        modeReaderLatch = latch
        assertEquals(
            CaptureCoordinator.CaptureReceipt.QUEUED,
            coordinator.capture(snapshot(postTime)),
        )
        awaitCondition("blocker staged (worker thread held)") {
            eventDao.count() >= expectedEvents
        }
        return latch
    }

    // --- Concurrent producers: exact accounting, no recursion ---

    @Test
    fun concurrentProducers_exactAccounting_noSilentLossNoDuplicates() = runBlocking {
        val latch = blockWorkerWith(POST_TIME)
        val receipts = Collections.synchronizedList(
            mutableListOf<CaptureCoordinator.CaptureReceipt>(),
        )
        try {
            // 8 producers x 25 captures against a saturated queue
            // (capacity 3): the old rejection handler re-entered
            // itself on this path. Admission must stay a single
            // bounded decision per capture.
            val producers = (0 until 8).map { t ->
                Thread {
                    for (i in 1..25) {
                        receipts.add(
                            coordinator.capture(
                                snapshot(POST_TIME + (t * 25 + i) * 1_000L),
                            ),
                        )
                    }
                }
            }
            producers.forEach { it.start() }
            producers.forEach { it.join(10_000) }
            producers.forEach { assertFalse("producer must not hang", it.isAlive) }
            assertEquals(200, receipts.size)
            // The queue always holds a notification to evict, so
            // every newcomer is admitted by eviction (QUEUED) and
            // every eviction is counted synchronously.
            assertTrue(
                receipts.all { it == CaptureCoordinator.CaptureReceipt.QUEUED },
            )
            assertEquals(197, coordinator.counters().droppedOverflow)
        } finally {
            latch.countDown()
        }
        // 201 submitted (blocker + 200) = 4 events (blocker + the
        // 3 that stayed queued) + 197 durable overflow rows.
        awaitCondition("every snapshot accounted for", timeoutMs = 15_000L) {
            eventDao.count() + overflowRows().size == 201
        }
        coordinator.sweepNow()
        assertEquals(4, eventDao.count())
        assertEquals(4, legacyQueue.size)
        assertEquals(197, overflowRows().size)
        assertEquals(201, coordinator.counters().received)
        assertEquals(4, coordinator.counters().staged)
        assertEquals(0, stagingDao.count())
    }

    // --- DB failure: the overflow record is retried, then persisted ---

    @Test
    fun dbFailure_overflowRecordRetriedAndPersisted_notJustVolatileCounter() = runBlocking {
        val latch = blockWorkerWith(POST_TIME)
        try {
            // Wait until the blocker's own CAPTURED log row is in,
            // so the failure budget below hits ONLY overflow rows.
            awaitCondition("blocker capture-log row written") {
                store.captureLog.values.any { it.outcome == CaptureOutcome.CAPTURED }
            }
            for (i in 1..3) {
                coordinator.capture(snapshot(POST_TIME + i * 1_000L))
            }
            // The next two capture-log inserts fail (the eviction's
            // overflow write + its first retry); the writer must
            // keep retrying and persist the record once inserts
            // succeed again — not degrade to a volatile counter.
            captureLogDao.failNextInserts.set(2)
            assertEquals(
                CaptureCoordinator.CaptureReceipt.QUEUED,
                coordinator.capture(snapshot(POST_TIME + 4 * 1_000L)),
            )
            assertEquals(1, coordinator.counters().droppedOverflow)
            awaitCondition("overflow row persisted after retries") {
                overflowRows().size == 1
            }
            assertEquals(POST_TIME + 1_000L, overflowRows().single().postTimeMs)
            assertTrue(
                "failed attempts are counted",
                coordinator.counters().captureLogFailures >= 2,
            )
            assertEquals(
                "record recovered — nothing degraded to counter-only",
                0, coordinator.counters().overflowRecordDropped,
            )
            assertEquals(0, coordinator.counters().overflowPending)
        } finally {
            latch.countDown()
        }
        awaitCondition("admitted captures enqueued") { legacyQueue.size == 4 }
        coordinator.sweepNow()
        assertEquals(4, eventDao.count())
    }

    // --- Single-thread ownership: sweep never runs inline/concurrently ---

    @Test
    fun sweepUnderSaturation_runsOnlyOnWorkerThread_neverConcurrentWithCapture() =
        runBlocking {
            // Park one capture first (legacy enqueue failing): its
            // staging row is the orphan the sweep will promote.
            enqueueShouldFail.set(true)
            coordinator.capture(snapshot(POST_TIME))
            awaitCondition("orphan staged and parked") { stagingDao.count() == 1 }
            enqueueShouldFail.set(false)

            val latch = blockWorkerWith(POST_TIME + 10_000L, expectedEvents = 2)
            try {
                for (i in 1..3) {
                    coordinator.capture(snapshot(POST_TIME + i * 1_000L))
                }
                // Sweep against the full queue, from a helper
                // thread: it must be admitted by evicting V1 and
                // must run on the worker thread — if it ran inline
                // here, the mode read inside its promotion would
                // execute on this helper thread (and concurrently
                // with the held capture), which the assertions
                // below catch.
                var sweepError: Throwable? = null
                val sweepThread = Thread {
                    try {
                        coordinator.sweepNow()
                    } catch (t: Throwable) {
                        sweepError = t
                    }
                }
                sweepThread.start()
                awaitCondition("sweep admitted by evicting V1") {
                    coordinator.counters().droppedOverflow == 1L
                }
                latch.countDown()
                sweepThread.join(10_000)
                assertFalse("sweepNow() must return (no hang)", sweepThread.isAlive)
                assertNull("sweepNow() must not throw", sweepError)
            } finally {
                latch.countDown()
            }
            coordinator.sweepNow()

            // Every mode read (capture processing AND the sweep's
            // promotion) happened on the single worker thread, and
            // never two at once.
            assertEquals(setOf("paylist-capture"), modeReaderThreads.toSet())
            assertEquals(1, modeMaxConcurrent.get())
            assertEquals(0, coordinator.counters().controlInline)

            // Orphan + blocker + V2 + V3 promoted exactly once;
            // V1 has only its overflow row.
            assertEquals(4, eventDao.count())
            assertEquals(4, legacyQueue.size)
            assertEquals(0, stagingDao.count())
            assertEquals(1, overflowRows().size)
        }
}
