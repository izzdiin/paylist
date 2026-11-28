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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.withLock

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

        /** When set, inserts throw an [Error] (not an [Exception]) N times. */
        val errorNextInserts = AtomicInteger(0)

        /** When non-null, inserts block until the latch opens (a stuck writer). */
        @Volatile
        var insertBlockLatch: CountDownLatch? = null
        val insertEntered = AtomicInteger(0)

        override suspend fun insert(row: CaptureLogEntity): Long {
            val latch = insertBlockLatch
            if (latch != null) {
                insertEntered.incrementAndGet()
                // A stuck durable write: waits for the latch,
                // swallowing interrupts (close() must exhaust
                // its bounded joins against this writer).
                val deadline = System.currentTimeMillis() + 30_000L
                while (latch.count > 0 &&
                    System.currentTimeMillis() < deadline
                ) {
                    try {
                        latch.await(50, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Swallowed on purpose (stuck writer).
                    }
                }
            }
            if (errorNextInserts.getAndDecrement() > 0) {
                throw AssertionError("store error (test)")
            }
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

    /**
     * In-memory [CaptureHealthStore] (item 5; extended for the
     * PR #5 audit of 28 Nov): one JSON document in a var, a
     * controllable process identity + live-process set (fence
     * staleness is decided against it), failure switches for
     * reads/writes, a write delay (a slow durable store), and a
     * one-shot gate that parks a fence PUBLISH write mid-flight
     * (the deterministic exit-between-sample-and-publish race).
     * The lock is per-STORE, so two coordinators over this fake
     * serialise against each other — the process-scoped
     * serialisation the production file lock provides.
     */
    private class FakeCaptureHealthStore : CaptureHealthStore {
        @Volatile
        var doc: String? = null

        /** The identity this fake reports for "this process". */
        @Volatile
        var selfIdentity = CaptureProcessIdentity(4242, 111L)

        /** Identities [isProcessAlive] reports as alive (starts with [selfIdentity]). */
        val liveIdentities: MutableSet<CaptureProcessIdentity> =
            java.util.Collections.synchronizedSet(mutableSetOf(selfIdentity))

        @Volatile
        var failReads: Boolean = false

        @Volatile
        var failWrites: Boolean = false

        /** Artificial durable-write latency (a slow store). */
        @Volatile
        var writeDelayMs: Long = 0

        /**
         * One-shot gate on the next write whose document carries
         * an ACTIVE fence: [fenceWriteEntered] counts down when
         * the write is parked, the write completes only after
         * [fenceWriteRelease] opens. Lets a test exit a
         * predecessor thread BETWEEN the old code's isAlive sample
         * and its fence publish.
         */
        @Volatile
        var fenceWriteGateArmed: Boolean = false
        val fenceWriteEntered = CountDownLatch(1)
        val fenceWriteRelease = CountDownLatch(1)

        private val storeLock = java.util.concurrent.locks.ReentrantLock()

        override fun readCaptureHealth(): String? {
            if (failReads) throw IllegalStateException("health read down (test)")
            return doc
        }

        override fun writeCaptureHealth(json: String) {
            if (failWrites) throw IllegalStateException("health write down (test)")
            val delay = writeDelayMs
            if (delay > 0) Thread.sleep(delay)
            if (fenceWriteGateArmed &&
                runCatching {
                    JSONObject(json).optJSONObject("fence")
                        ?.optBoolean("active") == true
                }.getOrDefault(false)
            ) {
                fenceWriteGateArmed = false
                fenceWriteEntered.countDown()
                fenceWriteRelease.await(15, TimeUnit.SECONDS)
            }
            doc = json
        }

        override fun processIdentity(): CaptureProcessIdentity = selfIdentity

        override fun isProcessAlive(identity: CaptureProcessIdentity): Boolean =
            liveIdentities.contains(identity)

        override fun <T> withCaptureHealthLock(block: () -> T): T =
            storeLock.withLock(block)

        fun incidents(): List<org.json.JSONObject> {
            val raw = doc ?: return emptyList()
            val arr = JSONObject(raw).optJSONArray("incidents") ?: return emptyList()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }

        fun incident(category: String): org.json.JSONObject? =
            incidents().firstOrNull { it.optString("category") == category }

        fun incident(category: String, identity: String): org.json.JSONObject? =
            incidents().firstOrNull {
                it.optString("category") == category &&
                    it.optString("identity") == identity
            }

        fun fenceActive(): Boolean {
            val raw = doc ?: return false
            return JSONObject(raw).optJSONObject("fence")
                ?.optBoolean("active") == true
        }

        /** Writes a document directly (a predecessor's hand-written state). */
        fun seedDoc(json: String) {
            doc = json
        }
    }

    /** A logger whose logLocal throws while [throwing] (a failing Store sink). */
    private class ThrowingLogger(
        private val lines: MutableList<String>,
    ) : CaptureLogSink {
        @Volatile
        var throwing: Boolean = false

        override fun logEvent(pkg: String, text: String) {
            lines.add("$pkg: $text")
        }

        override fun logLocal(text: String) {
            if (throwing) throw IllegalStateException("log store down (test)")
            lines.add(text)
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
        healthStore: CaptureHealthStore? = null,
        loggerOverride: CaptureLogSink? = null,
        routesLatch: CountDownLatch? = null,
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
        routesProvider = {
            if (routesLatch != null) {
                // Pre-stage block (item 3 test): the worker is
                // held BEFORE any staging, interruptibly — the
                // close() interrupt surfaces here as an
                // InterruptedException out of processSnapshot.
                routesLatch.await(15, TimeUnit.SECONDS)
            }
            routes
        },
        deviceIdProvider = { "g57" },
        logger = loggerOverride ?: object : CaptureLogSink {
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
        healthStore = healthStore,
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

    // --- Writer failure (PR #5 re-review 28 Nov, item 2) ---

    @Test
    fun writerFailure_throwingLoggerBeforeTry_recordStillPersists() = runBlocking {
        // The production log sink (Store) persists on the calling
        // thread and can throw. writeOverflowRecord's loud line
        // used to be a bare call BEFORE the insert's try: a
        // throwing logger there killed the writer loop. Now the
        // logging is best-effort and separated from the DB
        // attempt — the record must persist regardless.
        val throwing = ThrowingLogger(logLines)
        throwing.throwing = true
        coordinator = newCoordinator(loggerOverride = throwing)
        val latch = blockExecutorWith(POST_TIME)
        try {
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            awaitCondition("overflow row persisted despite the throwing logger") {
                overflowRows().size == 1
            }
            awaitCondition("outstanding settled") {
                coordinator.counters().overflowPending == 0L
            }
            assertEquals(0, coordinator.counters().overflowRecordDropped)
            assertTrue("writer loop survived", coordinator.isOverflowWriterAlive())
        } finally {
            latch.countDown()
        }
        awaitCondition("admitted captures enqueued") { legacyQueue.size == 4 }
    }

    @Test
    fun writerFailure_throwingLoggerInCatchPath_retriesStillSettle() = runBlocking {
        // The catch path also logged bare: DAO failing AND the
        // logger throwing on the failure line must not strand
        // the record — retries continue and the record lands on
        // the third attempt.
        val throwing = ThrowingLogger(logLines)
        throwing.throwing = true
        coordinator = newCoordinator(loggerOverride = throwing)
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
            captureLogDao.failNextInserts.set(2)
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            awaitCondition("overflow row persisted after two DB failures") {
                overflowRows().size == 1
            }
            awaitCondition("outstanding settled") {
                coordinator.counters().overflowPending == 0L
            }
            assertTrue(coordinator.counters().captureLogFailures >= 2)
            assertEquals(0, coordinator.counters().overflowRecordDropped)
            assertTrue("writer loop survived", coordinator.isOverflowWriterAlive())
        } finally {
            latch.countDown()
        }
        awaitCondition("admitted captures enqueued") { legacyQueue.size == 4 }
    }

    @Test
    fun writerFailure_errorEscapesWrite_terminalCleanupLoopSurvives() = runBlocking {
        // An Error (not Exception) from the store escapes
        // writeOverflowRecord's insert guard entirely: the
        // loop's OUTER terminal cleanup must settle the record
        // (counted lost + writer-failure counter), keep the
        // outstanding accounting exact, and keep the loop alive
        // for the next record.
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
            captureLogDao.errorNextInserts.set(1)
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            awaitCondition("escaped record settled as lost") {
                coordinator.counters().overflowWriterFailures == 1L &&
                    coordinator.counters().overflowRecordDropped == 1L
            }
            awaitCondition("outstanding settled after terminal failure") {
                coordinator.counters().overflowPending == 0L
            }
            assertTrue("writer loop survived an Error", coordinator.isOverflowWriterAlive())
            // The next eviction is written normally.
            coordinator.capture(snapshot(postTime = POST_TIME + 5 * 1_000L))
            awaitCondition("next overflow row persisted") {
                overflowRows().size == 1
            }
            assertEquals(POST_TIME + 2_000L, overflowRows().single().postTimeMs)
        } finally {
            latch.countDown()
        }
        // Blocker + V3 + V4 + V5 (V1 lost via the Error, V2 via
        // the second eviction's durable row).
        awaitCondition("admitted captures enqueued") { legacyQueue.size == 4 }
    }

    // --- Close outcomes (PR #5 re-review 28 Nov, item 3) ---

    @Test
    fun closeInterruptedPreStage_cancelledUnknownNotResolved() = runBlocking {
        // The active task is held BEFORE staging (in the routes
        // read) and honours the close interrupt by throwing:
        // its outcome is PRE-STAGE CANCELLED / UNKNOWN — counted
        // in its own counter, recorded as an UNRECOVERABLE
        // persistent incident, and NOT folded into "resolved"
        // or into unresolvedAtClose.
        val health = FakeCaptureHealthStore()
        val routesLatch = CountDownLatch(1)
        coordinator = newCoordinator(
            healthStore = health,
            routesLatch = routesLatch,
            closeTimeoutMs = 2_000L,
        )
        assertEquals(
            CaptureCoordinator.CaptureReceipt.QUEUED,
            coordinator.capture(snapshot()),
        )
        // Wait until the worker is inside the pre-stage block:
        // the routes latch is being awaited (worker alive, no
        // event staged, task active). Poll on the latch having a
        // waiter is not observable — use a short settle sleep
        // after the receipt, then close.
        Thread.sleep(500)
        assertEquals(0, eventDao.count())
        val started = System.currentTimeMillis()
        coordinator.close()
        assertTrue(
            "close() bounded",
            System.currentTimeMillis() - started < 15_000,
        )
        assertEquals(1, coordinator.counters().preStageInterruptedAtClose)
        assertEquals(0, coordinator.counters().unresolvedAtClose)
        assertEquals(0, eventDao.count())
        val incident = health.incident(
            CaptureIncidentCategory.PRE_STAGE_INTERRUPTED,
        )
        assertNotNull("persistent pre-stage incident recorded", incident)
        assertEquals(
            CaptureIncidentStatus.UNRECOVERABLE,
            incident!!.optString("status"),
        )
        assertTrue(coordinator.degradedState().degraded)
        routesLatch.countDown()
    }

    @Test
    fun closeWithStuckWriter_fenceRecorded_successorWaitsForPredecessor() = runBlocking {
        // The overflow writer is stuck inside a durable insert
        // that swallows interrupts: close() exhausts its bounded
        // flush + join and must record the successor fence. A
        // replacement coordinator over the same health store
        // must NOT process captures until the stuck writer
        // actually exits (its exit clears its fence half).
        val health = FakeCaptureHealthStore()
        coordinator = newCoordinator(healthStore = health)
        val latch = blockExecutorWith(POST_TIME)
        val writerLatch = CountDownLatch(1)
        try {
            awaitCondition("blocker capture-log row written") {
                store.captureLog.values.any {
                    it.outcome == CaptureOutcome.CAPTURED
                }
            }
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            captureLogDao.insertBlockLatch = writerLatch
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            awaitCondition("writer stuck inside the overflow insert") {
                captureLogDao.insertEntered.get() >= 1
            }
            val started = System.currentTimeMillis()
            coordinator.close()
            val elapsed = System.currentTimeMillis() - started
            assertTrue("close() bounded against a stuck writer (was ${elapsed}ms)",
                elapsed < 20_000)
            assertTrue(
                "stuck writer outlived close()",
                coordinator.isOverflowWriterAlive(),
            )
            assertTrue("fence recorded", health.fenceActive())
            assertTrue(coordinator.degradedState().successorFenced)

            // The successor: admitted, but its worker is fenced
            // — nothing is staged while the predecessor's writer
            // is still alive.
            val eventsBefore = eventDao.count()
            val successor = newCoordinator(healthStore = health)
            try {
                assertEquals(
                    CaptureCoordinator.CaptureReceipt.QUEUED,
                    successor.capture(
                        snapshot(postTime = POST_TIME + 9_000L),
                    ),
                )
                Thread.sleep(800)
                assertEquals(
                    "successor must not stage while fenced",
                    eventsBefore, eventDao.count(),
                )
                // Release the stuck writer: it drains, exits,
                // and clears the fence; the successor then
                // processes its queued capture.
                writerLatch.countDown()
                awaitCondition("fence cleared after predecessor writer exit") {
                    !health.fenceActive()
                }
                awaitCondition("successor processed after fence cleared") {
                    eventDao.count() == eventsBefore + 1
                }
            } finally {
                runCatching { successor.close() }
            }
        } finally {
            captureLogDao.insertBlockLatch = null
            writerLatch.countDown()
            latch.countDown()
        }
    }

    // --- Persistent degraded state (PR #5 re-review 28 Nov, item 5) ---

    @Test
    fun persistentDegradedState_survivesReplacement_recoversOnDbHealth() = runBlocking {
        val health = FakeCaptureHealthStore()
        coordinator = newCoordinator(healthStore = health)
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
            // Permanent DB failure: the overflow record is given
            // up after the retry budget.
            captureLogDao.failNextInserts.set(Int.MAX_VALUE)
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            awaitCondition("overflow record given up") {
                coordinator.counters().overflowRecordDropped == 1L
            }
            awaitCondition("persistent incidents recorded") {
                health.incident(CaptureIncidentCategory.DB_PERMANENT_FAILURE) != null &&
                    health.incident(CaptureIncidentCategory.OVERFLOW_RECORD_LOST) != null
            }
            val dbIncident = health.incident(
                CaptureIncidentCategory.DB_PERMANENT_FAILURE,
            )
            assertNotNull(dbIncident)
            assertEquals(
                CaptureIncidentStatus.OPEN,
                dbIncident!!.optString("status"),
            )
        } finally {
            captureLogDao.failNextInserts.set(0)
            latch.countDown()
        }
        awaitCondition("admitted captures enqueued") { legacyQueue.size == 4 }
        coordinator.close()

        // "Restart": a NEW coordinator with zeroed volatile
        // counters over the same health store still reports the
        // pipeline degraded — the incident survived.
        coordinator = newCoordinator(healthStore = health)
        assertEquals(0, coordinator.counters().overflowRecordDropped)
        val afterRestart = coordinator.degradedState()
        assertTrue(
            "persistent incident keeps the state degraded across restart",
            afterRestart.degraded,
        )
        assertTrue(
            afterRestart.persistentIncidents.any {
                it.category == CaptureIncidentCategory.DB_PERMANENT_FAILURE &&
                    it.status == CaptureIncidentStatus.OPEN
            },
        )
        // Recovery: the DB is healthy again and an overflow
        // record lands — the permanent-failure incident closes
        // itself as RECOVERED. (Manual block: blockExecutorWith
        // asserts an event count of 1, but four events already
        // exist from the first coordinator.)
        val latch2 = CountDownLatch(1)
        modeReaderLatch = latch2
        try {
            assertEquals(
                CaptureCoordinator.CaptureReceipt.QUEUED,
                coordinator.capture(
                    snapshot(postTime = POST_TIME + 100_000L),
                ),
            )
            awaitCondition("recovery blocker staged") { eventDao.count() == 5 }
            for (i in 1..3) {
                coordinator.capture(
                    snapshot(postTime = POST_TIME + 100_000L + i * 1_000L),
                )
            }
            coordinator.capture(
                snapshot(postTime = POST_TIME + 104_000L),
            )
            awaitCondition("overflow row persisted after recovery") {
                overflowRows().any { it.postTimeMs == POST_TIME + 101_000L }
            }
            awaitCondition("incident recovered") {
                health.incident(CaptureIncidentCategory.DB_PERMANENT_FAILURE)
                    ?.optString("status") == CaptureIncidentStatus.RECOVERED
            }
        } finally {
            modeReaderLatch = null
            latch2.countDown()
        }
    }

    // --- Privacy: pre-consent paths are metadata-only (item 4) ---

    @Test
    fun noKeywordAndNoAmount_metadataOnlyRowsAndLogs() = runBlocking {
        // NO_KEYWORD: no route keyword in the combined text.
        coordinator.capture(
            snapshot(
                title = "Promo Spesial",
                text = "Diskon besar-besaran hari ini saja",
                postTime = POST_TIME,
            ),
        )
        // NO_AMOUNT: keyword matched, no parseable amount.
        coordinator.capture(
            snapshot(
                title = "Pembayaran",
                text = "Pembayaran kamu sedang diproses",
                postTime = POST_TIME + 60_000L,
            ),
        )
        awaitCondition("both reject rows written") {
            store.captureLog.values.any {
                it.outcome == CaptureOutcome.NO_KEYWORD
            } && store.captureLog.values.any {
                it.outcome == CaptureOutcome.NO_AMOUNT
            }
        }
        val noKeyword = store.captureLog.values.first {
            it.outcome == CaptureOutcome.NO_KEYWORD
        }
        val noAmount = store.captureLog.values.first {
            it.outcome == CaptureOutcome.NO_AMOUNT
        }
        assertNull(
            "NO_KEYWORD is pre-consent: no title text at all",
            noKeyword.titleSnippet,
        )
        assertNull(
            "NO_AMOUNT: no title text at all (not even redacted)",
            noAmount.titleSnippet,
        )
        assertNull(noKeyword.contentSha256)
        assertNull(noAmount.contentSha256)
        // And no log line carries either title.
        assertFalse(
            "no persistent log line may carry the NO_KEYWORD title",
            logLines.any { it.contains("Promo Spesial") },
        )
        assertFalse(
            "no persistent log line may carry the NO_AMOUNT title text",
            logLines.any { it.contains("sedang diproses") },
        )
        assertEquals(0, eventDao.count())
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

    // --- Audit 28 Nov P1-1: successor fence (process-scoped) ---

    /** A v2 fence document as a predecessor would have published it. */
    private fun fenceDoc(
        ownerInstance: String,
        ownerIdentity: CaptureProcessIdentity,
        workerAlive: Boolean = true,
        writerAlive: Boolean = true,
    ): String = JSONObject().apply {
        put("version", 2)
        put(
            "fence",
            JSONObject().apply {
                put("active", true)
                put("owner", ownerInstance)
                put("owner_pid", ownerIdentity.pid)
                put("owner_start", ownerIdentity.startTicks)
                put("since_ms", POST_TIME)
                put("worker_alive", workerAlive)
                put("writer_alive", writerAlive)
                put("reason", "close pendahulu melewati batas tunggu")
            },
        )
        put("incidents", org.json.JSONArray())
    }.toString()

    @Test
    fun staleFence_deadPredecessorProcess_clearedBySuccessor() = runBlocking {
        // The fence's owner PROCESS is provably dead (its
        // identity is not in the live set — the predecessor was
        // SIGKILLed with the fence active, so no thread of its
        // will ever clear a half): the successor must clear the
        // fence and process captures, never stall indefinitely.
        val health = FakeCaptureHealthStore()
        health.seedDoc(
            fenceDoc("predecessor-instance", CaptureProcessIdentity(9999, 5L)),
        )
        coordinator = newCoordinator(healthStore = health)
        assertEquals(
            CaptureCoordinator.CaptureReceipt.QUEUED,
            coordinator.capture(snapshot()),
        )
        awaitCondition("successor processed after clearing the stale fence") {
            eventDao.count() == 1
        }
        assertFalse("stale fence cleared", health.fenceActive())
        awaitCondition("orphan clearing recorded as an incident") {
            health.incident(CaptureIncidentCategory.FENCE_ORPHAN_CLEARED) != null
        }
    }

    @Test
    fun livePredecessorFence_neverBypassed_thenDeathAllowsProgress() = runBlocking {
        // The fence's owner process is ALIVE: no successor work
        // may happen while it lives (no fail-open). Once the
        // process provably dies, the same fence becomes stale
        // and the successor proceeds.
        val health = FakeCaptureHealthStore()
        val live = CaptureProcessIdentity(777, 7L)
        health.liveIdentities.add(live)
        health.seedDoc(fenceDoc("live-predecessor", live, writerAlive = false))
        coordinator = newCoordinator(healthStore = health)
        coordinator.capture(snapshot())
        Thread.sleep(800)
        assertEquals(
            "no successor work while the predecessor process lives",
            0, eventDao.count(),
        )
        assertTrue("live fence untouched", health.fenceActive())
        health.liveIdentities.remove(live)
        awaitCondition("successor proceeds once the predecessor is provably dead") {
            eventDao.count() == 1
        }
        assertFalse(health.fenceActive())
    }

    @Test
    fun fencePublishRace_threadExitsDuringPublish_noStaleFence() = runBlocking {
        // The audit's exit-between-sample-and-publish race, made
        // deterministic: the predecessor's overflow writer is
        // stuck (so close() WILL publish a fence), the publish
        // write is parked mid-flight by the store gate, and the
        // writer is released while the publish is parked — it
        // drains, exits, and attempts its exit-clear DURING the
        // publish. Because the liveness sample and the publish
        // now happen inside the same store-lock section, the
        // exit-clear serialises after the publish and clears the
        // writer half: no stale fence may remain.
        val health = FakeCaptureHealthStore()
        coordinator = newCoordinator(healthStore = health)
        val latch = blockExecutorWith(POST_TIME)
        val writerLatch = CountDownLatch(1)
        try {
            awaitCondition("blocker capture-log row written") {
                store.captureLog.values.any {
                    it.outcome == CaptureOutcome.CAPTURED
                }
            }
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            captureLogDao.insertBlockLatch = writerLatch
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            awaitCondition("writer stuck inside the overflow insert") {
                captureLogDao.insertEntered.get() >= 1
            }
            health.fenceWriteGateArmed = true
            val closeThread = Thread { coordinator.close() }
            closeThread.start()
            assertTrue(
                "fence publish reached the store",
                health.fenceWriteEntered.await(20, TimeUnit.SECONDS),
            )
            // The writer exits DURING the parked publish: it
            // drains its queue and its exit-clear queues up on
            // the store lock the publish is holding.
            writerLatch.countDown()
            Thread.sleep(800)
            health.fenceWriteRelease.countDown()
            closeThread.join(20_000)
            assertFalse("close() returned", closeThread.isAlive)
            assertFalse(
                "no stale fence: the exit-clear landed after the publish",
                health.fenceActive(),
            )
            // And a successor is not fenced by the corpse.
            val successor = newCoordinator(healthStore = health)
            try {
                successor.capture(snapshot(postTime = POST_TIME + 9_000L))
                awaitCondition("successor processed immediately") {
                    eventDao.count() == 2
                }
            } finally {
                runCatching { successor.close() }
            }
        } finally {
            captureLogDao.insertBlockLatch = null
            writerLatch.countDown()
            health.fenceWriteRelease.countDown()
            latch.countDown()
        }
    }

    @Test
    fun healthStoreUnreadable_fenceCheckFailsSafe() = runBlocking {
        // The store cannot be READ at all: the fence check must
        // fail SAFE (the successor waits) — the old code
        // defaulted unreadable state to "no fence" (fail-open).
        // Once the store is readable and the fence's owner
        // proves dead, the successor proceeds.
        val health = FakeCaptureHealthStore()
        health.seedDoc(
            fenceDoc("dead-predecessor", CaptureProcessIdentity(9999, 5L)),
        )
        health.failReads = true
        coordinator = newCoordinator(healthStore = health)
        coordinator.capture(snapshot())
        Thread.sleep(800)
        assertEquals(
            "unreadable state must fail safe (wait), never read as 'no fence'",
            0, eventDao.count(),
        )
        health.failReads = false
        awaitCondition("successor proceeds once readable and the fence proves stale") {
            eventDao.count() == 1
        }
    }

    @Test
    fun concurrentHealthMutations_noLostIncidentCounts() = runBlocking {
        // Two coordinators over ONE store record incidents
        // concurrently. The store-scoped lock serialises their
        // read-modify-writes across instances, so the aggregated
        // count is exact — a per-instance lock (the old design)
        // cannot guarantee this.
        val health = FakeCaptureHealthStore()
        val a = newCoordinator(healthStore = health)
        val b = newCoordinator(healthStore = health)
        val latch = CountDownLatch(1)
        modeReaderLatch = latch
        try {
            a.capture(snapshot(postTime = POST_TIME))
            b.capture(snapshot(postTime = POST_TIME + 100_000L))
            awaitCondition("both blockers staged") { eventDao.count() == 2 }
            // Each coordinator: 3 queued + 7 evictions = 7 drops.
            for (i in 1..10) {
                a.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            for (i in 1..10) {
                b.capture(snapshot(postTime = POST_TIME + 100_000L + i * 1_000L))
            }
            assertEquals(7, a.counters().droppedOverflow)
            assertEquals(7, b.counters().droppedOverflow)
            a.flushHealthIncidents(10_000)
            b.flushHealthIncidents(10_000)
            awaitCondition("all incidents applied") {
                health.incident(CaptureIncidentCategory.OVERFLOW_SNAPSHOT_DROPPED)
                    ?.optLong("count") == 14L
            }
        } finally {
            modeReaderLatch = null
            latch.countDown()
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    // --- Audit 28 Nov P1-2: health durability / callback ---

    @Test
    fun corruptHealthDoc_quarantinedNotSilentlyReset() = runBlocking {
        // The stored document does not parse: it must NOT be
        // silently reset. The next mutation carries the raw
        // value into the fresh document as a quarantine block,
        // and the corruption itself becomes a visible incident.
        val health = FakeCaptureHealthStore()
        health.seedDoc("{ini bukan json yang valid")
        coordinator = newCoordinator(healthStore = health)
        val latch = blockExecutorWith(POST_TIME)
        try {
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            awaitCondition("incident written into a fresh, valid document") {
                health.incident(CaptureIncidentCategory.OVERFLOW_SNAPSHOT_DROPPED) != null
            }
            val quarantine = JSONObject(health.doc!!).optJSONObject("quarantine")
            assertNotNull("the corrupt raw value is quarantined as evidence", quarantine)
            assertEquals("{ini bukan json yang valid", quarantine!!.optString("raw"))
            awaitCondition("corruption itself recorded as an incident") {
                health.incident(CaptureIncidentCategory.HEALTH_DOC_CORRUPT) != null
            }
            assertTrue(coordinator.degradedState().degraded)
        } finally {
            latch.countDown()
        }
    }

    @Test
    fun healthWriteFailure_visibleFailureState_captureContinues() = runBlocking {
        // The health store's durable writes fail: capture and
        // the Room overflow path continue unaffected, and the
        // failure is counted + visible in the degraded state —
        // never swallowed (the old code swallowed read/write
        // failures and defaulted the fence check to false).
        val health = FakeCaptureHealthStore()
        health.failWrites = true
        coordinator = newCoordinator(healthStore = health)
        val latch = blockExecutorWith(POST_TIME)
        try {
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            awaitCondition("overflow row still written (Room path unaffected)") {
                overflowRows().size == 1
            }
            awaitCondition("health write failure counted") {
                coordinator.counters().healthStoreFailures > 0
            }
            assertNull("no health document could be written", health.doc)
            val degraded = coordinator.degradedState()
            assertTrue(degraded.degraded)
            assertTrue(
                "failure state is visible in the degraded reasons",
                degraded.reasons.any { it.contains("kesehatan") },
            )
        } finally {
            latch.countDown()
        }
    }

    @Test
    fun recordOverflow_callerNotBlockedBySlowHealthStore() = runBlocking {
        // The audit's callback regression: recordOverflow used
        // to run the health JSON read-modify-write synchronously
        // on the caller (the Binder thread). With a 1 s durable
        // health write, the dropping capture() must still return
        // immediately — the health writer absorbs the latency.
        val health = FakeCaptureHealthStore()
        health.writeDelayMs = 1_000
        coordinator = newCoordinator(healthStore = health)
        val latch = blockExecutorWith(POST_TIME)
        try {
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            val started = System.currentTimeMillis()
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            val elapsed = System.currentTimeMillis() - started
            assertTrue(
                "the dropping capture() must not pay the health store's " +
                    "durable-write latency (was ${elapsed}ms)",
                elapsed < 500,
            )
            awaitCondition("incident eventually durable", 15_000) {
                health.incident(CaptureIncidentCategory.OVERFLOW_SNAPSHOT_DROPPED) != null
            }
        } finally {
            latch.countDown()
        }
    }

    @Test
    fun incidentDurableAcceptance_flushedIncidentVisibleToReplacement() = runBlocking {
        // The unit-level half of the durable acceptance boundary
        // (the real process-kill half is the c07 instrumented
        // scenario): once flushHealthIncidents returns, the
        // incident's durable write has RETURNED from the store,
        // so a replacement coordinator with zeroed counters sees
        // it — this is what "accepted" means for incidents.
        val health = FakeCaptureHealthStore()
        coordinator = newCoordinator(healthStore = health)
        val latch = blockExecutorWith(POST_TIME)
        try {
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
        } finally {
            latch.countDown()
        }
        coordinator.flushHealthIncidents(10_000)
        coordinator.close()
        val replacement = newCoordinator(healthStore = health)
        try {
            assertTrue(
                "flushed incident is durable for a replacement coordinator",
                replacement.degradedState().persistentIncidents.any {
                    it.category == CaptureIncidentCategory.OVERFLOW_SNAPSHOT_DROPPED &&
                        it.status == CaptureIncidentStatus.OPEN
                },
            )
        } finally {
            runCatching { replacement.close() }
        }
    }

    // --- Audit 28 Nov P1-3: recovery proof at identity level ---

    @Test
    fun sweepRecovery_failedPassKeepsUnresolved_resolvingPassRecoversIdentity() =
        runBlocking {
            // An unresolved-at-close incident for the blocker's
            // identity. Its staging row IS durable.
            val health = FakeCaptureHealthStore()
            stubbornModeRead.set(true)
            coordinator = newCoordinator(healthStore = health, closeTimeoutMs = 400L)
            val latch = blockExecutorWith(POST_TIME)
            coordinator.close()
            assertEquals(1, coordinator.counters().unresolvedAtClose)
            val identity = "$PKG@$POST_TIME"
            awaitCondition("unresolved incident recorded") {
                health.incident(
                    CaptureIncidentCategory.UNRESOLVED_AT_CLOSE, identity,
                ) != null
            }
            // Release the stuck task with the legacy enqueue
            // DOWN: its own promotion attempt fails and parks, so
            // the staging row survives for the sweep phase (with
            // the enqueue up, the released task would promote and
            // delete the row itself).
            enqueueShouldFail.set(true)
            latch.countDown()
            awaitCondition("stuck worker exited") {
                !coordinator.isCaptureWorkerAlive()
            }
            assertEquals(1, stagingDao.count())

            val successor = newCoordinator(healthStore = health)
            try {
                // A sweep whose promotion FAILS (the legacy
                // enqueue is down) resolves nothing: the incident
                // must stay OPEN — the old code recovered it after
                // ANY completed pass.
                val failedResult = successor.sweepOrphanedStaging()
                assertEquals(1, failedResult.rowsSeen)
                assertEquals(0, failedResult.rowsResolved)
                successor.flushHealthIncidents(5_000)
                assertEquals(
                    CaptureIncidentStatus.OPEN,
                    health.incident(
                        CaptureIncidentCategory.UNRESOLVED_AT_CLOSE, identity,
                    )?.optString("status"),
                )
                // A resolving sweep promotes the row: THAT
                // identity — and only that identity — recovers.
                enqueueShouldFail.set(false)
                val okResult = successor.sweepOrphanedStaging()
                assertEquals(1, okResult.rowsResolved)
                successor.flushHealthIncidents(5_000)
                assertEquals(
                    CaptureIncidentStatus.RECOVERED,
                    health.incident(
                        CaptureIncidentCategory.UNRESOLVED_AT_CLOSE, identity,
                    )?.optString("status"),
                )
            } finally {
                enqueueShouldFail.set(false)
                stubbornModeRead.set(false)
                runCatching { successor.close() }
            }
        }

    @Test
    fun sweepRecovery_emptyPassRecoversNothing_dispositionIsTheOnlyExit() = runBlocking {
        // No staging rows at all: a sweep cannot establish
        // recovery of an unknown pre-stage outcome (the staging
        // may have rolled back). The incident stays OPEN,
        // exposed as an unresolved loss, until the explicit
        // operator disposition — which acknowledges it as
        // UNRECOVERABLE without ever claiming recovery.
        val health = FakeCaptureHealthStore()
        val identity = "$PKG@$POST_TIME"
        health.seedDoc(
            JSONObject().apply {
                put("version", 2)
                put(
                    "fence",
                    JSONObject().apply {
                        put("active", false)
                        put("owner", "")
                        put("owner_pid", 0)
                        put("owner_start", -1L)
                        put("since_ms", 0L)
                        put("worker_alive", false)
                        put("writer_alive", false)
                        put("reason", "")
                    },
                )
                put(
                    "incidents",
                    org.json.JSONArray().put(
                        JSONObject().apply {
                            put("category", CaptureIncidentCategory.UNRESOLVED_AT_CLOSE)
                            put("status", CaptureIncidentStatus.OPEN)
                            put("first_ms", POST_TIME)
                            put("last_ms", POST_TIME)
                            put("count", 1L)
                            put("detail", "$PKG @$POST_TIME")
                            put("identity", identity)
                        },
                    ),
                )
            }.toString(),
        )
        coordinator = newCoordinator(healthStore = health)
        val result = coordinator.sweepOrphanedStaging()
        assertEquals(0, result.rowsSeen)
        coordinator.flushHealthIncidents(5_000)
        assertEquals(
            CaptureIncidentStatus.OPEN,
            health.incident(
                CaptureIncidentCategory.UNRESOLVED_AT_CLOSE, identity,
            )?.optString("status"),
        )
        assertTrue(
            "exposed as an unresolved loss, independently of component health",
            coordinator.degradedState().unresolvedLosses.any {
                it.category == CaptureIncidentCategory.UNRESOLVED_AT_CLOSE &&
                    it.identity == identity
            },
        )
        coordinator.dismissIncident(
            CaptureIncidentCategory.UNRESOLVED_AT_CLOSE, identity,
        )
        assertEquals(
            CaptureIncidentStatus.UNRECOVERABLE,
            health.incident(
                CaptureIncidentCategory.UNRESOLVED_AT_CLOSE, identity,
            )?.optString("status"),
        )
        assertTrue(
            coordinator.degradedState().unresolvedLosses.none {
                it.category == CaptureIncidentCategory.UNRESOLVED_AT_CLOSE
            },
        )
    }

    @Test
    fun lostRecordRecovery_unrelatedInsertDoesNotRecover_sameIdentityRedropDoes() =
        runBlocking {
            val health = FakeCaptureHealthStore()
            coordinator = newCoordinator(healthStore = health)
            val lostIdentity = "$PKG@${POST_TIME + 1_000L}"
            // Phase 1: V1's overflow record is LOST (the DB fails
            // across the whole retry budget).
            var latch = blockExecutorWith(POST_TIME)
            try {
                for (i in 1..3) {
                    coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
                }
                captureLogDao.failNextInserts.set(Int.MAX_VALUE)
                coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
                awaitCondition("V1's overflow record given up") {
                    coordinator.counters().overflowRecordDropped == 1L
                }
                captureLogDao.failNextInserts.set(0)
                awaitCondition("lost incident recorded for V1's identity") {
                    health.incident(
                        CaptureIncidentCategory.OVERFLOW_RECORD_LOST, lostIdentity,
                    ) != null
                }
            } finally {
                captureLogDao.failNextInserts.set(0)
                latch.countDown()
            }
            awaitCondition("first queue drained") { legacyQueue.size == 4 }

            // Phase 2: an UNRELATED overflow record (W1, evicted
            // by W4) lands successfully. The DB component recovers
            // — but V1's lost record must NOT: its row still does
            // not exist, and an unrelated success proves nothing
            // about it (the old code recovered it wholesale).
            // (Manual block: blockExecutorWith asserts an event
            // count of 1, but four events already exist.)
            val latch2 = CountDownLatch(1)
            modeReaderLatch = latch2
            try {
                coordinator.capture(snapshot(postTime = POST_TIME + 100_000L))
                awaitCondition("second blocker staged") { eventDao.count() == 5 }
                for (i in 1..3) {
                    coordinator.capture(
                        snapshot(postTime = POST_TIME + 100_000L + i * 1_000L),
                    )
                }
                coordinator.capture(snapshot(postTime = POST_TIME + 104_000L))
                awaitCondition("unrelated overflow row persisted") {
                    overflowRows().any { it.postTimeMs == POST_TIME + 101_000L }
                }
                coordinator.flushHealthIncidents(5_000)
                assertEquals(
                    "the DB component incident recovers on a landed insert",
                    CaptureIncidentStatus.RECOVERED,
                    health.incident(CaptureIncidentCategory.DB_PERMANENT_FAILURE)
                        ?.optString("status"),
                )
                assertEquals(
                    "V1's lost record is still lost",
                    CaptureIncidentStatus.OPEN,
                    health.incident(
                        CaptureIncidentCategory.OVERFLOW_RECORD_LOST, lostIdentity,
                    )?.optString("status"),
                )
                assertTrue(
                    coordinator.degradedState().unresolvedLosses.any {
                        it.category == CaptureIncidentCategory.OVERFLOW_RECORD_LOST &&
                            it.identity == lostIdentity
                    },
                )
            } finally {
                modeReaderLatch = null
                latch2.countDown()
            }
            awaitCondition("second queue drained") { legacyQueue.size == 8 }

            // Phase 3: identity-level reconciliation — V1's exact
            // snapshot is dropped by a later saturation and its
            // overflow row lands this time. THAT snapshot's
            // evidence now exists, so its incident recovers.
            val latch3 = CountDownLatch(1)
            modeReaderLatch = latch3
            try {
                coordinator.capture(snapshot(postTime = POST_TIME + 200_000L))
                awaitCondition("third blocker staged") { eventDao.count() == 9 }
                for (i in 1..3) {
                    coordinator.capture(
                        snapshot(postTime = POST_TIME + 200_000L + i * 1_000L),
                    )
                }
                // Admit V1's snapshot, then evict it with three
                // more captures.
                coordinator.capture(snapshot(postTime = POST_TIME + 1_000L))
                coordinator.capture(snapshot(postTime = POST_TIME + 204_000L))
                coordinator.capture(snapshot(postTime = POST_TIME + 205_000L))
                coordinator.capture(snapshot(postTime = POST_TIME + 206_000L))
                awaitCondition("V1's re-dropped overflow row persisted") {
                    overflowRows().any { it.postTimeMs == POST_TIME + 1_000L }
                }
                coordinator.flushHealthIncidents(5_000)
                assertEquals(
                    "identity-level reconciliation recovers V1's incident",
                    CaptureIncidentStatus.RECOVERED,
                    health.incident(
                        CaptureIncidentCategory.OVERFLOW_RECORD_LOST, lostIdentity,
                    )?.optString("status"),
                )
            } finally {
                modeReaderLatch = null
                latch3.countDown()
            }
        }

    @Test
    fun restartWithUnrecoverableIncident_staysExposedAsUnresolvedLoss() = runBlocking {
        // A pre-stage interruption is UNRECOVERABLE by
        // definition; across a restart it must stay exposed in
        // unresolvedLosses — a healthy component status must
        // never hide it.
        val health = FakeCaptureHealthStore()
        val routesLatch = CountDownLatch(1)
        coordinator = newCoordinator(
            healthStore = health,
            routesLatch = routesLatch,
            closeTimeoutMs = 2_000L,
        )
        assertEquals(
            CaptureCoordinator.CaptureReceipt.QUEUED,
            coordinator.capture(snapshot()),
        )
        Thread.sleep(500)
        coordinator.close()
        assertEquals(1, coordinator.counters().preStageInterruptedAtClose)
        routesLatch.countDown()
        val fresh = newCoordinator(healthStore = health)
        try {
            assertTrue(
                "unrecoverable incident stays exposed across restart",
                fresh.degradedState().unresolvedLosses.any {
                    it.category == CaptureIncidentCategory.PRE_STAGE_INTERRUPTED &&
                        it.status == CaptureIncidentStatus.UNRECOVERABLE &&
                        it.identity == "$PKG@$POST_TIME"
                },
            )
        } finally {
            runCatching { fresh.close() }
        }
    }
}
