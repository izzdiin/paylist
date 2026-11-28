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

        /**
         * The fixed observation key for this suite (audit
         * 7237720579, A05/A06): coordinators built by
         * newCoordinator derive KEYED observation identities
         * with it, so the identity assertions compute their
         * expectations through the keyed companion derivation.
         */
        val TEST_OBSERVATION_KEY: ByteArray =
            ByteArray(32) { (it * 7 + 3).toByte() }
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

        /** The lifecycle record (audit A03: separate from the health document). */
        @Volatile
        var lifecycle: String? = null

        /** The offer journal (audit A06). */
        @Volatile
        var journal: String? = null

        /** When set, lifecycle writes block until the latch opens (a stuck health writer for A06). */
        @Volatile
        var healthWriteBlockLatch: CountDownLatch? = null
        val healthWriteEntered = AtomicInteger(0)

        /** The identity this fake reports for "this process". */
        @Volatile
        var selfIdentity = CaptureProcessIdentity(4242, 111L)

        /** Identities [processLiveness] reports as alive (starts with [selfIdentity]). */
        val liveIdentities: MutableSet<CaptureProcessIdentity> =
            java.util.Collections.synchronizedSet(mutableSetOf(selfIdentity))

        /**
         * Tri-state liveness overrides (audit 7237720579, A03
         * residual): an identity mapped here reports exactly
         * this liveness — UNKNOWN stands in for an injected
         * permission / I/O / visibility failure on /proc.
         */
        val livenessOverrides: MutableMap<CaptureProcessIdentity, ProcessLiveness> =
            java.util.Collections.synchronizedMap(mutableMapOf())

        @Volatile
        var failReads: Boolean = false

        @Volatile
        var failWrites: Boolean = false

        /** Only the health DOCUMENT's writes fail (journal + lifecycle keep working). */
        @Volatile
        var failDocWrites: Boolean = false

        /** Only LIFECYCLE writes fail (the fence publish channel). */
        @Volatile
        var failLifecycleWrites: Boolean = false

        /** Only JOURNAL writes fail. */
        @Volatile
        var failJournalWrites: Boolean = false

        /** Artificial latency on journal writes only (a slow journal store). */
        @Volatile
        var journalWriteDelayMs: Long = 0

        /** Artificial durable-write latency (a slow store). */
        @Volatile
        var writeDelayMs: Long = 0

        /**
         * One-shot gate on the next LIFECYCLE write carrying an
         * ACTIVE fence: [fenceWriteEntered] counts down when the
         * write is parked, the write completes only after
         * [fenceWriteRelease] opens. Lets a test exit a
         * predecessor thread BETWEEN the old code's isAlive
         * sample and its fence publish.
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
            if (failWrites || failDocWrites) {
                throw IllegalStateException("health write down (test)")
            }
            val latch = healthWriteBlockLatch
            if (latch != null) {
                healthWriteEntered.incrementAndGet()
                val deadline = System.currentTimeMillis() + 30_000L
                while (latch.count > 0 &&
                    System.currentTimeMillis() < deadline
                ) {
                    try {
                        latch.await(50, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Swallowed on purpose (stuck store).
                    }
                }
            }
            val delay = writeDelayMs
            if (delay > 0) Thread.sleep(delay)
            doc = json
        }

        override fun readCaptureLifecycle(): String? {
            if (failReads) throw IllegalStateException("lifecycle read down (test)")
            return lifecycle
        }

        override fun writeCaptureLifecycle(json: String) {
            if (failWrites || failLifecycleWrites) {
                throw IllegalStateException("lifecycle write down (test)")
            }
            if (fenceWriteGateArmed &&
                runCatching {
                    JSONObject(json).optBoolean("active")
                }.getOrDefault(false)
            ) {
                fenceWriteGateArmed = false
                fenceWriteEntered.countDown()
                fenceWriteRelease.await(15, TimeUnit.SECONDS)
            }
            lifecycle = json
        }

        override fun readCaptureHealthJournal(): String? {
            if (failReads) throw IllegalStateException("journal read down (test)")
            return journal
        }

        override fun writeCaptureHealthJournal(json: String) {
            if (failWrites || failJournalWrites) {
                throw IllegalStateException("journal write down (test)")
            }
            val delay = journalWriteDelayMs
            if (delay > 0) Thread.sleep(delay)
            journal = json
        }

        override fun processIdentity(): CaptureProcessIdentity = selfIdentity

        override fun processLiveness(identity: CaptureProcessIdentity): ProcessLiveness =
            livenessOverrides[identity]
                ?: if (liveIdentities.contains(identity)) {
                    ProcessLiveness.ALIVE
                } else {
                    ProcessLiveness.DEAD
                }

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

        /**
         * The AUTHORITATIVE fence state, mirroring the
         * production evaluation: active when the lifecycle
         * record carries an active fence, OR the health
         * document carries an active fence from an owner the
         * lifecycle record has NOT adopted-and-retired (the
         * legacy / fallback location, audit 7237720579). A
         * document fence from the SAME owner as an inactive
         * lifecycle record is a retired leftover.
         */
        fun fenceActive(): Boolean {
            val lc = lifecycle
            var lcOwner: String? = null
            if (lc != null) {
                val parsed = runCatching { JSONObject(lc) }.getOrNull()
                if (parsed != null) {
                    if (parsed.optBoolean("active")) return true
                    lcOwner = parsed.optString("owner")
                }
            }
            val raw = doc ?: return false
            val docFence = JSONObject(raw).optJSONObject("fence")
                ?: return false
            if (!docFence.optBoolean("active")) return false
            return lcOwner == null || docFence.optString("owner") != lcOwner
        }

        /** Writes a document directly (a predecessor's hand-written state). */
        fun seedDoc(json: String) {
            doc = json
        }

        /** Writes a lifecycle record directly (a predecessor's hand-written state). */
        fun seedLifecycle(json: String) {
            lifecycle = json
        }

        /** A lifecycle fence document as a predecessor would have published it. */
        fun lifecycleFence(
            ownerInstance: String,
            ownerIdentity: CaptureProcessIdentity,
            workerAlive: Boolean = true,
            writerAlive: Boolean = true,
        ): String = JSONObject().apply {
            put("version", 1)
            put("active", true)
            put("owner", ownerInstance)
            put("owner_pid", ownerIdentity.pid)
            put("owner_start", ownerIdentity.startTicks)
            put("owner_known", ownerIdentity.pid > 0)
            put("since_ms", POST_TIME)
            put("worker_alive", workerAlive)
            put("writer_alive", writerAlive)
            put("reason", "close pendahulu melewati batas tunggu")
        }.toString()
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
        observationKey: ByteArray? = TEST_OBSERVATION_KEY,
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
        observationKeyProvider = { observationKey },
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
        //
        // Audit 7235948077 (A03) changed which mutation can be
        // "next" for the CAPTURE path: with no lifecycle record
        // yet, a corrupt document cannot rule out a live
        // predecessor's fence, so fence evaluation adopts a
        // conservative unknown-owner fence and the worker waits
        // fail-safe — corruption closes the gate, never opens it
        // (asserted at the end of this test). The mutation that
        // quarantines here is therefore an operator-path
        // mutation (dismissIncident), which writes the health
        // document directly, fenced or not.
        val health = FakeCaptureHealthStore()
        health.seedDoc("{ini bukan json yang valid")
        coordinator = newCoordinator(healthStore = health)
        coordinator.dismissIncident(
            CaptureIncidentCategory.OVERFLOW_RECORD_LOST, "obs:tidak-ada",
        )
        awaitCondition("corrupt raw value quarantined into a fresh document") {
            val raw = health.doc ?: return@awaitCondition false
            JSONObject(raw).optJSONObject("quarantine")
                ?.optString("raw") == "{ini bukan json yang valid"
        }
        val quarantine = JSONObject(health.doc!!).optJSONObject("quarantine")
        assertNotNull("the corrupt raw value is quarantined as evidence", quarantine)
        assertEquals("{ini bukan json yang valid", quarantine!!.optString("raw"))
        awaitCondition("corruption itself recorded as an incident") {
            health.incident(CaptureIncidentCategory.HEALTH_DOC_CORRUPT) != null
        }
        assertTrue(coordinator.degradedState().degraded)
        // A03: the capture path stays fenced — the corrupt
        // document could have carried a live predecessor's
        // fence, so nothing processes while ownership is
        // unknown.
        coordinator.capture(snapshot())
        Thread.sleep(600)
        assertEquals(
            "corruption never opens the fence",
            0, eventDao.count(),
        )
        assertTrue(
            "the adopted unknown-owner fence reports as fenced",
            coordinator.degradedState().successorFenced,
        )
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
            val identity = keyedIdentity(snapshot(postTime = POST_TIME))
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
        val identity = keyedIdentity(snapshot())
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
            val lostIdentity = keyedIdentity(snapshot(postTime = POST_TIME + 1_000L))
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
                        it.identity == keyedIdentity(snapshot())
                },
            )
        } finally {
            runCatching { fresh.close() }
        }
    }
    // ------------------------------------------------------------------
    // Audit 7235948077 (28 Nov 2026): A03 fence proof, A05 identity,
    // A06 pre-durable loss exposure.
    // ------------------------------------------------------------------

    @Test
    fun legacyFenceWithoutIdentity_neverClearedByAge_successorWaits() = runBlocking {
        // Audit A03: a legacy v1 fence carries NO process identity
        // (owner_pid = 0). The old code cleared it once its age
        // passed 600 s — but a stuck predecessor thread can outlive
        // any age bound, so age is not proof of death. The fence's
        // since_ms is ancient relative to the fixed test clock (its
        // age is far beyond 600 s): the successor must STILL wait
        // and the fence must survive untouched.
        val health = FakeCaptureHealthStore()
        health.seedDoc(
            JSONObject().apply {
                put("version", 1)
                put(
                    "fence",
                    JSONObject().apply {
                        put("active", true)
                        put("owner", "legacy-predecessor")
                        put("owner_pid", 0)
                        put("owner_start", -1L)
                        put("since_ms", 0L)
                        put("worker_alive", true)
                        put("writer_alive", true)
                        put("reason", "close pendahulu melewati batas tunggu")
                    },
                )
                put("incidents", org.json.JSONArray())
            }.toString(),
        )
        coordinator = newCoordinator(healthStore = health)
        coordinator.capture(snapshot())
        Thread.sleep(900)
        assertEquals(
            "unknown ownership is never cleared by age",
            0, eventDao.count(),
        )
        assertTrue("legacy fence still active", health.fenceActive())
        assertTrue(
            "successor reports itself fenced",
            coordinator.degradedState().successorFenced,
        )
    }

    @Test
    fun corruptHealthDoc_liveLifecycleFence_successorWaits_quarantinePreserved() =
        runBlocking {
            // Audit A03, the corruption path: the fence lives in
            // the lifecycle record, so a corrupt HEALTH document
            // cannot open it (the old code replaced a corrupt
            // document with a fresh one whose empty fence let a
            // successor in under a live predecessor). The corrupt
            // document is quarantined as evidence by the next
            // health mutation; the successor proceeds only when
            // the owner process provably dies.
            val health = FakeCaptureHealthStore()
            val live = CaptureProcessIdentity(777, 7L)
            health.liveIdentities.add(live)
            health.seedLifecycle(health.lifecycleFence("live-predecessor", live))
            health.seedDoc("{ini bukan json")
            coordinator = newCoordinator(healthStore = health)
            coordinator.capture(snapshot())
            Thread.sleep(900)
            assertEquals(
                "no successor work while the fence owner lives",
                0, eventDao.count(),
            )
            assertTrue("lifecycle fence untouched", health.fenceActive())
            health.liveIdentities.remove(live)
            awaitCondition("successor proceeds once the owner provably dies") {
                eventDao.count() == 1
            }
            assertFalse("fence cleared by proof of death", health.fenceActive())
            awaitCondition("corrupt document quarantined by the clearing mutation") {
                val raw = health.doc ?: return@awaitCondition false
                JSONObject(raw).optJSONObject("quarantine")
                    ?.optString("raw") == "{ini bukan json"
            }
        }

    @Test
    fun corruptLifecycleRecord_successorWaits_recordPreservedUntouched() = runBlocking {
        // Audit A03: an unparseable LIFECYCLE record is evidence,
        // not an obstacle to sweep away: it is never overwritten
        // or cleared, ownership reads as unknown, and the
        // successor waits fail-safe.
        val health = FakeCaptureHealthStore()
        health.seedLifecycle("{rusak total")
        coordinator = newCoordinator(healthStore = health)
        coordinator.capture(snapshot())
        Thread.sleep(900)
        assertEquals(
            "a corrupt lifecycle record never reads as 'no fence'",
            0, eventDao.count(),
        )
        assertEquals(
            "corrupt record preserved byte-identical",
            "{rusak total", health.lifecycle,
        )
        assertTrue(
            "successor reports itself fenced",
            coordinator.degradedState().successorFenced,
        )
    }

    /** The KEYED observation identity the suite's coordinators derive for [snap]. */
    private fun keyedIdentity(snap: NotificationSnapshot): String =
        CaptureCoordinator.observationIdentity(
            snap.packageName,
            snap.postTimeMs,
            CaptureCoordinator.sha256Hex(snap.combined),
            TEST_OBSERVATION_KEY,
        )

    @Test
    fun observationIdentity_distinguishesSameMillisecond_stableAcrossRescan() {
        // Audit A05: two DISTINCT notifications sharing package
        // AND post millisecond (the old identity collided) get
        // distinct observation identities; the SAME notification
        // seen again (a rescan) keeps its identity, so rescan
        // stays idempotent. Since audit 7237720579 the identity
        // is KEYED (HMAC under the suite's test key): it carries
        // no raw text and no unkeyed derivation of the text.
        val a = snapshot(
            postTime = POST_TIME,
            text = "Pembayaran Rp100.800 berhasil",
        )
        val b = snapshot(
            postTime = POST_TIME,
            text = "Pembayaran Rp200.000 berhasil",
        )
        val idA = keyedIdentity(a)
        val idB = keyedIdentity(b)
        assertTrue(
            "distinct same-millisecond events must not collide",
            idA != idB,
        )
        assertEquals(
            "the same snapshot re-observed keeps its identity",
            idA, keyedIdentity(a),
        )
        assertTrue(idA.startsWith("obs:"))
        assertFalse("identity persists no raw text", idA.contains("Rp100"))
        assertFalse("identity persists no raw text", idA.contains("Pembayaran"))
        assertTrue(
            "the keyed identity differs from the unkeyed derivation",
            idA != CaptureCoordinator.observationIdentity(a),
        )
        assertTrue(
            "a different key derives a different identity",
            idA != CaptureCoordinator.observationIdentity(
                a.packageName,
                a.postTimeMs,
                CaptureCoordinator.sha256Hex(a.combined),
                ByteArray(32) { 0x5A },
            ),
        )
    }

    @Test
    fun sameMillisecondCollision_unrelatedLandingDoesNotRecoverLostRecord() =
        runBlocking {
            // Audit A05 regression: the lost record and the
            // unrelated record share package AND post millisecond
            // — under the old `<pkg>@<postTimeMs>` identity they
            // were the same identity, so the unrelated landing
            // would have falsely recovered the loss. Under the
            // observation identity it must not; only the lost
            // snapshot's own re-drop reconciles it. A landed
            // metadata row is also NOT a payment recovery: the
            // aggregate dropped-snapshot incident stays OPEN.
            val health = FakeCaptureHealthStore()
            coordinator = newCoordinator(healthStore = health)
            val lostSnap = snapshot(
                postTime = POST_TIME + 1_000L,
                text = "Pembayaran Rp111.000 berhasil",
            )
            val unrelatedSnap = snapshot(
                postTime = POST_TIME + 1_000L,
                text = "Pembayaran Rp222.000 berhasil",
            )
            val lostIdentity = keyedIdentity(lostSnap)
            val unrelatedIdentity = keyedIdentity(unrelatedSnap)
            assertTrue(lostIdentity != unrelatedIdentity)

            // Phase 1: the lost snapshot's overflow record is
            // LOST (the DB fails across the whole retry budget).
            var latch = blockExecutorWith(POST_TIME)
            try {
                coordinator.capture(lostSnap)
                coordinator.capture(snapshot(postTime = POST_TIME + 2_000L))
                coordinator.capture(snapshot(postTime = POST_TIME + 3_000L))
                captureLogDao.failNextInserts.set(Int.MAX_VALUE)
                coordinator.capture(snapshot(postTime = POST_TIME + 4_000L))
                awaitCondition("lost snapshot's overflow record given up") {
                    coordinator.counters().overflowRecordDropped == 1L
                }
                captureLogDao.failNextInserts.set(0)
                awaitCondition("lost incident recorded under the observation identity") {
                    health.incident(
                        CaptureIncidentCategory.OVERFLOW_RECORD_LOST, lostIdentity,
                    ) != null
                }
            } finally {
                captureLogDao.failNextInserts.set(0)
                latch.countDown()
            }
            awaitCondition("first queue drained") { legacyQueue.size == 4 }

            // Phase 2: the UNRELATED same-millisecond snapshot is
            // evicted and its overflow row LANDS.
            val latch2 = CountDownLatch(1)
            modeReaderLatch = latch2
            try {
                coordinator.capture(snapshot(postTime = POST_TIME + 100_000L))
                awaitCondition("second blocker staged") { eventDao.count() == 5 }
                coordinator.capture(unrelatedSnap)
                coordinator.capture(snapshot(postTime = POST_TIME + 102_000L))
                coordinator.capture(snapshot(postTime = POST_TIME + 103_000L))
                coordinator.capture(snapshot(postTime = POST_TIME + 104_000L))
                awaitCondition("unrelated overflow row persisted") {
                    overflowRows().any { it.postTimeMs == POST_TIME + 1_000L }
                }
                // The row is visible the moment the DAO insert
                // returns; the writer offers the recovery just
                // after. Await the status itself (the assertion is
                // unchanged) instead of racing a flush against
                // that offer.
                awaitCondition("DB component incident recovered by the landed insert") {
                    health.incident(CaptureIncidentCategory.DB_PERMANENT_FAILURE)
                        ?.optString("status") == CaptureIncidentStatus.RECOVERED
                }
                coordinator.flushHealthIncidents(5_000)
                assertEquals(
                    "the same-millisecond loss is NOT recovered by an unrelated landing",
                    CaptureIncidentStatus.OPEN,
                    health.incident(
                        CaptureIncidentCategory.OVERFLOW_RECORD_LOST, lostIdentity,
                    )?.optString("status"),
                )
                assertEquals(
                    "a landed metadata row is not a payment recovery",
                    CaptureIncidentStatus.OPEN,
                    health.incident(CaptureIncidentCategory.OVERFLOW_SNAPSHOT_DROPPED)
                        ?.optString("status"),
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

            // Phase 3: the lost snapshot ITSELF is re-dropped and
            // its row lands — THAT reconciles its incident.
            val latch3 = CountDownLatch(1)
            modeReaderLatch = latch3
            try {
                coordinator.capture(snapshot(postTime = POST_TIME + 200_000L))
                awaitCondition("third blocker staged") { eventDao.count() == 9 }
                coordinator.capture(snapshot(postTime = POST_TIME + 201_000L))
                coordinator.capture(snapshot(postTime = POST_TIME + 202_000L))
                coordinator.capture(snapshot(postTime = POST_TIME + 203_000L))
                coordinator.capture(lostSnap)
                coordinator.capture(snapshot(postTime = POST_TIME + 204_000L))
                coordinator.capture(snapshot(postTime = POST_TIME + 205_000L))
                coordinator.capture(snapshot(postTime = POST_TIME + 206_000L))
                awaitCondition("re-dropped overflow row persisted") {
                    overflowRows().count { it.postTimeMs == POST_TIME + 1_000L } >= 2
                }
                awaitCondition("the lost snapshot's own landing reconciles its incident") {
                    health.incident(
                        CaptureIncidentCategory.OVERFLOW_RECORD_LOST, lostIdentity,
                    )?.optString("status") == CaptureIncidentStatus.RECOVERED
                }
                coordinator.flushHealthIncidents(5_000)
                assertEquals(
                    "the lost snapshot's own landing reconciles its incident",
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
    fun preDurableLoss_exposedAsUnknownLossAfterRestart_exactCount() = runBlocking {
        // Audit A06: incidents offered while the health document
        // can never be written (the unit stand-in for a process
        // dying before the durable boundary) must not vanish:
        // the offer journal survives, and the next coordinator
        // exposes the exact gap as one UNKNOWN_LOSS incident.
        val health = FakeCaptureHealthStore()
        health.failDocWrites = true
        coordinator = newCoordinator(healthStore = health)
        val latch = blockExecutorWith(POST_TIME)
        try {
            coordinator.capture(snapshot(postTime = POST_TIME + 1_000L))
            coordinator.capture(snapshot(postTime = POST_TIME + 2_000L))
            coordinator.capture(snapshot(postTime = POST_TIME + 3_000L))
            captureLogDao.failNextInserts.set(Int.MAX_VALUE)
            coordinator.capture(snapshot(postTime = POST_TIME + 4_000L))
            awaitCondition("overflow record given up behind the failing store") {
                coordinator.counters().overflowRecordDropped == 1L
            }
            // Give the health writer time to attempt (and fail)
            // its durable writes for every offered incident.
            Thread.sleep(1_500)
        } finally {
            captureLogDao.failNextInserts.set(0)
            latch.countDown()
        }
        coordinator.close()
        val journal = JSONObject(health.journal ?: "{}")
        val offered = journal.optLong("offered", 0L)
        val dropped = journal.optLong("dropped", 0L)
        assertTrue("incidents were offered and journaled", offered >= 3L)
        assertEquals("the document never became durable", null, health.doc)

        health.failDocWrites = false
        val successor = newCoordinator(healthStore = health)
        try {
            awaitCondition("unknown loss exposed after restart") {
                health.incident(CaptureIncidentCategory.UNKNOWN_LOSS) != null
            }
            assertEquals(
                "the exposed count is exact: offered - dropped - applied(0)",
                offered - dropped,
                health.incident(CaptureIncidentCategory.UNKNOWN_LOSS)
                    ?.optLong("count"),
            )
            assertTrue(
                "operator-visible through the successor's degraded state",
                successor.degradedState().persistentIncidents.any {
                    it.category == CaptureIncidentCategory.UNKNOWN_LOSS &&
                        it.status == CaptureIncidentStatus.OPEN
                },
            )
        } finally {
            runCatching { successor.close() }
        }
    }

    @Test
    fun queueSaturation_dropsAreJournaled_noUnknownLossAfterRestart() = runBlocking {
        // Audit A06, saturation: with the health writer stuck
        // inside a durable write, offers overflow the bounded
        // queue. Those drops are journaled as known-dropped at
        // the moment they happen — so after a clean drain and
        // close, the next start sees NO unknown loss: every
        // offer is either applied or accounted as dropped.
        val health = FakeCaptureHealthStore()
        val gate = CountDownLatch(1)
        health.healthWriteBlockLatch = gate
        coordinator = newCoordinator(healthStore = health)
        val latch = blockExecutorWith(POST_TIME)
        try {
            // One eviction first: its incident offer is what the
            // health writer picks up and parks inside the gated
            // durable write.
            coordinator.capture(snapshot(postTime = POST_TIME + 1_000L))
            coordinator.capture(snapshot(postTime = POST_TIME + 2_000L))
            coordinator.capture(snapshot(postTime = POST_TIME + 3_000L))
            coordinator.capture(snapshot(postTime = POST_TIME + 4_000L))
            awaitCondition("health writer stuck inside its first durable write") {
                health.healthWriteEntered.get() >= 1
            }
            // Each further eviction offers at least one incident;
            // drive far past the bounded queue (100) while the
            // writer is stuck.
            for (i in 5..140) {
                coordinator.capture(
                    snapshot(postTime = POST_TIME + i * 1_000L),
                )
            }
            awaitCondition("queue saturation dropped offers in process") {
                coordinator.counters().healthIncidentsDropped > 0L
            }
            val journal = JSONObject(health.journal ?: "{}")
            assertTrue(
                "saturation drops are journaled as known-dropped",
                journal.optLong("dropped", 0L) > 0L,
            )
        } finally {
            gate.countDown()
            health.healthWriteBlockLatch = null
            latch.countDown()
        }
        coordinator.close()
        awaitCondition("every offer applied or accounted as dropped", 15_000L) {
            val j = JSONObject(health.journal ?: "{}")
            val d = JSONObject(health.doc ?: "{}")
            j.optLong("offered", 0L) - j.optLong("dropped", 0L) ==
                d.optLong("applied_count", 0L)
        }
        val journal = JSONObject(health.journal ?: "{}")
        val doc = JSONObject(health.doc ?: "{}")
        assertEquals(
            "every offer is applied or accounted as dropped",
            journal.optLong("offered", 0L) - journal.optLong("dropped", 0L),
            doc.optLong("applied_count", 0L),
        )
        val successor = newCoordinator(healthStore = health)
        try {
            // The successor's reconciliation runs before its
            // first flush returns; after it, a gap would be
            // recorded — there must be none.
            successor.flushHealthIncidents(5_000)
            assertEquals(
                "no unknown loss after a fully accounted life",
                null, health.incident(CaptureIncidentCategory.UNKNOWN_LOSS),
            )
        } finally {
            runCatching { successor.close() }
        }
    }

    // --- Audit 7237720579: A03 tri-state liveness, lifecycle
    // schema + publish fallback; A05 keyed identities; A06
    // journal latency / meaning / cross-instance exactness ---

    @Test
    fun processLiveness_triState_realProc() {
        // Directly against the production /proc source (the
        // unit tests run on a Linux JVM, so /proc is real):
        // this process with its exact identity is ALIVE; a pid
        // that cannot exist is DEAD (positive absence); this
        // pid with a different start time is DEAD (PID reuse);
        // an invalid identity is UNKNOWN — never a death proof.
        val self = CaptureProcessIdentitySource.current()
        assertTrue(self.pid > 0)
        assertEquals(
            ProcessLiveness.ALIVE,
            CaptureProcessIdentitySource.liveness(self),
        )
        assertEquals(
            ProcessLiveness.DEAD,
            CaptureProcessIdentitySource.liveness(
                CaptureProcessIdentity(999_999_999, 1L),
            ),
        )
        assertEquals(
            ProcessLiveness.DEAD,
            CaptureProcessIdentitySource.liveness(
                CaptureProcessIdentity(self.pid, self.startTicks + 1),
            ),
        )
        assertEquals(
            ProcessLiveness.UNKNOWN,
            CaptureProcessIdentitySource.liveness(
                CaptureProcessIdentity(0, 0),
            ),
        )
        assertEquals(
            ProcessLiveness.UNKNOWN,
            CaptureProcessIdentitySource.liveness(
                CaptureProcessIdentity(-3, 10L),
            ),
        )
    }

    @Test
    fun livenessUnknown_predecessorFenceRetained_successorWaitsUntilProvenDead() =
        runBlocking {
            // Audit 7237720579 (A03 residual): an injected /proc
            // read failure (permission / I/O / visibility)
            // reports UNKNOWN — the fence is RETAINED, the
            // record is untouched, and the successor waits.
            // Only a positively proven DEAD clears it.
            val health = FakeCaptureHealthStore()
            val predecessor = CaptureProcessIdentity(7777, 222L)
            val seeded = health.lifecycleFence("predecessor-instance", predecessor)
            health.seedLifecycle(seeded)
            health.livenessOverrides[predecessor] = ProcessLiveness.UNKNOWN
            coordinator = newCoordinator(healthStore = health)
            val eventsBefore = eventDao.count()
            assertEquals(
                CaptureCoordinator.CaptureReceipt.QUEUED,
                coordinator.capture(snapshot(postTime = POST_TIME + 9_000L)),
            )
            Thread.sleep(800)
            assertEquals(
                "successor must not stage while liveness is UNKNOWN",
                eventsBefore, eventDao.count(),
            )
            assertTrue(coordinator.degradedState().successorFenced)
            assertEquals(
                "fence record retained byte-for-byte under UNKNOWN",
                seeded, health.lifecycle,
            )
            // Proof of death arrives: the fence clears and the
            // successor proceeds.
            health.livenessOverrides[predecessor] = ProcessLiveness.DEAD
            awaitCondition("fence cleared on proven death") {
                !health.fenceActive()
            }
            awaitCondition("successor processed after proven death") {
                eventDao.count() == eventsBefore + 1
            }
        }

    @Test
    fun lifecycleSchemaInvalid_successorWaits_recordPreserved() = runBlocking {
        // Audit 7237720579 (A03 residual): a lifecycle record
        // that PARSES as JSON but violates the schema (wrong
        // types, missing fields) is corrupt evidence — the
        // successor waits fail-safe and the record is preserved
        // byte-for-byte, never overwritten, never cleared.
        val health = FakeCaptureHealthStore()
        val broken = JSONObject().apply {
            put("version", "satu")
            put("active", true)
            put("owner", 42)
        }.toString()
        health.seedLifecycle(broken)
        coordinator = newCoordinator(healthStore = health)
        val eventsBefore = eventDao.count()
        assertEquals(
            CaptureCoordinator.CaptureReceipt.QUEUED,
            coordinator.capture(snapshot(postTime = POST_TIME + 9_000L)),
        )
        Thread.sleep(800)
        assertEquals(
            "successor must not stage on a schema-invalid fence",
            eventsBefore, eventDao.count(),
        )
        assertTrue(coordinator.degradedState().successorFenced)
        assertEquals(
            "schema-invalid record preserved byte-for-byte",
            broken, health.lifecycle,
        )
    }

    @Test
    fun lifecycleWriteFailure_fenceFallsBackToHealthDocument_successorWaits() =
        runBlocking {
            // Audit 7237720579 (A03 residual): when the
            // lifecycle WRITE channel itself fails at publish
            // time, the fence must not vanish — a vanished fence
            // would let a successor run alongside live
            // predecessor threads. The publish falls back to the
            // health document's fence (the channel successors
            // adopt from when no lifecycle record binds them),
            // and the successor waits until the predecessor's
            // writer actually exits.
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
                health.failLifecycleWrites = true
                coordinator.close()
                assertTrue(
                    "stuck writer outlived close()",
                    coordinator.isOverflowWriterAlive(),
                )
                assertFalse(
                    "the lifecycle channel carries no fence (its writes failed)",
                    JSONObject(health.lifecycle ?: "{}").optBoolean("active"),
                )
                assertTrue(
                    "fence fell back to the health document",
                    health.fenceActive(),
                )
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
                        "successor must not stage while the fallback fence stands",
                        eventsBefore, eventDao.count(),
                    )
                    health.failLifecycleWrites = false
                    writerLatch.countDown()
                    awaitCondition("fallback fence cleared after writer exit") {
                        !health.fenceActive()
                    }
                    awaitCondition("successor processed after fallback fence cleared") {
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

    @Test
    fun lifecycleAndDocumentWriteFailure_publishFailureSurfacedNotSilent() =
        runBlocking {
            // Audit 7237720579 (A03 residual), the hard corner:
            // BOTH fence channels fail at publish time (lifecycle
            // writes down AND health-document writes down) while
            // a predecessor thread outlives close. No fence can
            // be recorded anywhere — that residual is
            // unavoidable — but it must be VISIBLE: the degraded
            // state names the failed publish, and the failure is
            // counted, never silent.
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
                health.failLifecycleWrites = true
                health.failDocWrites = true
                coordinator.close()
                assertTrue(
                    "stuck writer outlived close()",
                    coordinator.isOverflowWriterAlive(),
                )
                assertFalse(
                    "no fence could be recorded on either channel",
                    health.fenceActive(),
                )
                assertTrue(
                    "the failed publish is surfaced in the degraded state",
                    coordinator.degradedState().reasons.any {
                        it.contains("fence_publish_failed")
                    },
                )
            } finally {
                captureLogDao.insertBlockLatch = null
                writerLatch.countDown()
                latch.countDown()
            }
        }

    @Test
    fun preConsentLoss_persistsOnlyKeyedIdentity_noUnkeyedFingerprint() =
        runBlocking {
            // Audit 7237720579 (A05/A06 regression): a lost
            // record for a low-entropy, OTP-only, pre-consent
            // snapshot persists ONLY the keyed (HMAC) observation
            // identity. The unkeyed derivation of the TRUE text
            // must NOT match the stored identity (a correct
            // offline guess cannot be verified without the key),
            // and neither the unkeyed text hash nor the raw OTP
            // may appear anywhere in the persisted document.
            val health = FakeCaptureHealthStore()
            coordinator = newCoordinator(healthStore = health)
            val otpSnap = snapshot(
                postTime = POST_TIME + 1_000L,
                title = "Kode verifikasi",
                text = "Kode OTP Anda 482916. Jangan bagikan ke siapa pun.",
            )
            var latch = blockExecutorWith(POST_TIME)
            try {
                coordinator.capture(otpSnap)
                coordinator.capture(snapshot(postTime = POST_TIME + 2_000L))
                coordinator.capture(snapshot(postTime = POST_TIME + 3_000L))
                captureLogDao.failNextInserts.set(Int.MAX_VALUE)
                coordinator.capture(snapshot(postTime = POST_TIME + 4_000L))
                awaitCondition("OTP snapshot's overflow record given up") {
                    coordinator.counters().overflowRecordDropped == 1L
                }
                captureLogDao.failNextInserts.set(0)
                awaitCondition("lost incident recorded under the keyed identity") {
                    health.incident(
                        CaptureIncidentCategory.OVERFLOW_RECORD_LOST,
                        keyedIdentity(otpSnap),
                    ) != null
                }
            } finally {
                captureLogDao.failNextInserts.set(0)
                latch.countDown()
            }
            val docRaw = health.doc ?: error("health document missing")
            assertFalse(
                "no unkeyed hash of the raw text is persisted",
                docRaw.contains(CaptureCoordinator.sha256Hex(otpSnap.combined)),
            )
            assertFalse(
                "the unkeyed observation identity is not persisted",
                docRaw.contains(CaptureCoordinator.observationIdentity(otpSnap)),
            )
            assertFalse("no raw OTP is persisted", docRaw.contains("482916"))
            assertTrue(
                "the stored identity is the keyed derivation",
                docRaw.contains(keyedIdentity(otpSnap)),
            )
        }

    @Test
    fun journalLatency_captureBoundedUnderSlowJournal_saturation() = runBlocking {
        // Audit 7237720579 (A06(a)): with the journal store
        // artificially slow (3 s per write) and the capture
        // queue saturated, the capture() call that drops a
        // snapshot — and thereby offers a health incident —
        // returns within the declared bound (at most two
        // JOURNAL_WRITE_BOUND_MS waits), NOT after the 3 s
        // write. The lag is counted, and once the store heals
        // the journal catches up exactly: a successor finds no
        // UNKNOWN_LOSS.
        val health = FakeCaptureHealthStore()
        health.journalWriteDelayMs = 3_000
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
                "capture() bounded under a 3s journal write (was ${elapsed}ms)",
                elapsed < 2_000,
            )
            assertEquals(1, coordinator.counters().droppedOverflow)
            awaitCondition("journal lag counted") {
                coordinator.counters().healthJournalTimeouts >= 1
            }
        } finally {
            latch.countDown()
        }
        health.journalWriteDelayMs = 0
        coordinator.close()
        awaitCondition("journal caught up after the store healed") {
            val j = JSONObject(health.journal ?: "{}")
            val d = JSONObject(health.doc ?: "{}")
            j.optLong("offered", 0L) - j.optLong("dropped", 0L) ==
                d.optLong("applied_count", 0L)
        }
        val successor = newCoordinator(healthStore = health)
        try {
            successor.flushHealthIncidents(5_000)
            assertEquals(
                "no unknown loss once the journal caught up",
                null, health.incident(CaptureIncidentCategory.UNKNOWN_LOSS),
            )
        } finally {
            runCatching { successor.close() }
        }
    }

    @Test
    fun overlappingInstances_journalTotalsExact_noUnknownLoss() = runBlocking {
        // Audit 7237720579 (A06(c)): two coordinators alive at
        // the SAME time over one store (predecessor still
        // draining while its successor runs) offer incidents
        // concurrently. Journal writes are delta
        // read-modify-writes under the store lock, so the
        // totals reconcile EXACTLY across instances — the old
        // absolute per-instance writes could overwrite each
        // other. A third coordinator must find no gap.
        val health = FakeCaptureHealthStore()
        val a = newCoordinator(healthStore = health)
        val b = newCoordinator(healthStore = health)
        val latch = CountDownLatch(1)
        modeReaderLatch = latch
        try {
            a.capture(snapshot(postTime = POST_TIME))
            b.capture(snapshot(postTime = POST_TIME + 100_000L))
            awaitCondition("both blockers staged") { eventDao.count() == 2 }
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
        } finally {
            modeReaderLatch = null
            latch.countDown()
            runCatching { a.close() }
            runCatching { b.close() }
        }
        awaitCondition("journal reconciles exactly across overlapping instances") {
            val j = JSONObject(health.journal ?: "{}")
            val d = JSONObject(health.doc ?: "{}")
            d.optLong("applied_count", 0L) >= 14L &&
                j.optLong("offered", 0L) - j.optLong("dropped", 0L) ==
                d.optLong("applied_count", 0L)
        }
        val third = newCoordinator(healthStore = health)
        try {
            third.flushHealthIncidents(5_000)
            assertEquals(
                "no unknown loss across overlapping instances",
                null, health.incident(CaptureIncidentCategory.UNKNOWN_LOSS),
            )
        } finally {
            runCatching { third.close() }
        }
    }

    @Test
    fun recoverOps_doNotInflateJournalGap() = runBlocking {
        // Audit 7237720579 (A06(b)): recover operations are
        // maintenance — they must move NEITHER the journal's
        // offered counter NOR the document's applied_count.
        // Phase 1 loses a record (RECORD offers land); phase 2
        // lands an unrelated record, which recovers the DB
        // component incident: exactly ONE new RECORD offer (the
        // phase-2 eviction itself) may appear — the recovery
        // adds none.
        val health = FakeCaptureHealthStore()
        coordinator = newCoordinator(healthStore = health)
        var latch = blockExecutorWith(POST_TIME)
        try {
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            captureLogDao.failNextInserts.set(Int.MAX_VALUE)
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            awaitCondition("overflow record given up") {
                coordinator.counters().overflowRecordDropped == 1L
            }
            captureLogDao.failNextInserts.set(0)
            awaitCondition("lost incident recorded") {
                health.incident(CaptureIncidentCategory.OVERFLOW_RECORD_LOST) != null
            }
        } finally {
            captureLogDao.failNextInserts.set(0)
            latch.countDown()
        }
        awaitCondition("first queue drained") { legacyQueue.size == 4 }
        coordinator.flushHealthIncidents(5_000)
        val offeredBefore = JSONObject(health.journal ?: "{}")
            .optLong("offered", 0L)
        val appliedBefore = JSONObject(health.doc ?: "{}")
            .optLong("applied_count", 0L)
        assertTrue("phase 1 journaled its records", offeredBefore > 0)

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
                "the DB component incident recovered",
                CaptureIncidentStatus.RECOVERED,
                health.incident(CaptureIncidentCategory.DB_PERMANENT_FAILURE)
                    ?.optString("status"),
            )
        } finally {
            modeReaderLatch = null
            latch2.countDown()
        }
        val offeredAfter = JSONObject(health.journal ?: "{}")
            .optLong("offered", 0L)
        val appliedAfter = JSONObject(health.doc ?: "{}")
            .optLong("applied_count", 0L)
        assertEquals(
            "only the phase-2 eviction's RECORD offer was journaled; " +
                "the recovery journaled nothing",
            offeredBefore + 1, offeredAfter,
        )
        assertEquals(
            "applied_count likewise counts records only",
            appliedBefore + 1, appliedAfter,
        )
    }

    // --- Audit 7239229553: A03 authoritative ownership +
    // corroborated /proc death proof; A06 per-callback journal
    // budget + per-instance reconciliation ---

    /** A minimal parseable `/proc/<pid>/stat` body for a fake proc root. */
    private fun fakeProcStat(pid: Int, startTicks: Long): String =
        "$pid (fake) S " + (1..18).joinToString(" ") { "0" } +
            " $startTicks 0"

    @Test
    fun processLiveness_fakeProcRoot_absenceMustBeCorroborated() {
        // Audit 7239229553 (A03): a single negative probe
        // (File.exists()-style) is not death proof. Against a
        // FAKE proc root — a real directory tree on disk, not
        // an injected enum:
        // - a pid whose directory is entirely absent, with the
        //   root listable, is DEAD (absence corroborated by a
        //   root listing plus a second ENOENT read);
        // - a pid whose DIRECTORY exists but whose stat is
        //   missing is UNKNOWN (the subject is there; its stat
        //   is not — visibility/race, not death);
        // - a stat path that is a DIRECTORY is UNKNOWN (an I/O
        //   error, not absence);
        // - an unlistable proc root is UNKNOWN (our own view
        //   cannot even be established);
        // - a present stat decides by start time: match ALIVE,
        //   mismatch DEAD (PID reuse).
        val root = java.nio.file.Files.createTempDirectory("fakeproc")
        try {
            val selfDir = root.resolve("self")
            java.nio.file.Files.createDirectories(selfDir)
            java.nio.file.Files.write(
                selfDir.resolve("stat"),
                fakeProcStat(4242, 111L).toByteArray(),
            )
            val target = CaptureProcessIdentity(777, 55L)
            assertEquals(
                "corroborated absence is DEAD",
                ProcessLiveness.DEAD,
                CaptureProcessIdentitySource.liveness(target, root.toString()),
            )
            val dirOnly = root.resolve("777")
            java.nio.file.Files.createDirectories(dirOnly)
            assertEquals(
                "directory present, stat missing: UNKNOWN, not death",
                ProcessLiveness.UNKNOWN,
                CaptureProcessIdentitySource.liveness(target, root.toString()),
            )
            java.nio.file.Files.createDirectories(dirOnly.resolve("stat"))
            assertEquals(
                "stat unreadable (it is a directory): UNKNOWN",
                ProcessLiveness.UNKNOWN,
                CaptureProcessIdentitySource.liveness(target, root.toString()),
            )
            java.nio.file.Files.delete(dirOnly.resolve("stat"))
            java.nio.file.Files.write(
                dirOnly.resolve("stat"),
                fakeProcStat(777, 55L).toByteArray(),
            )
            assertEquals(
                "matching start: ALIVE",
                ProcessLiveness.ALIVE,
                CaptureProcessIdentitySource.liveness(target, root.toString()),
            )
            assertEquals(
                "different start: DEAD (PID reuse)",
                ProcessLiveness.DEAD,
                CaptureProcessIdentitySource.liveness(
                    CaptureProcessIdentity(777, 56L), root.toString(),
                ),
            )
            assertEquals(
                "missing proc root: UNKNOWN (no trustworthy view)",
                ProcessLiveness.UNKNOWN,
                CaptureProcessIdentitySource.liveness(
                    target, root.resolve("nope").toString(),
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun processLiveness_fakeProcRoot_permissionDeniedIsUnknown() {
        // A stat file that exists but denies the read (EACCES)
        // is UNKNOWN — never death. Skipped where the test runs
        // as root, because root reads through permission bits
        // and the denial cannot be staged.
        val root = java.nio.file.Files.createTempDirectory("fakeproc")
        try {
            val selfDir = root.resolve("self")
            java.nio.file.Files.createDirectories(selfDir)
            java.nio.file.Files.write(
                selfDir.resolve("stat"),
                fakeProcStat(4242, 111L).toByteArray(),
            )
            val dir = root.resolve("888")
            java.nio.file.Files.createDirectories(dir)
            val statPath = dir.resolve("stat")
            java.nio.file.Files.write(
                statPath, fakeProcStat(888, 9L).toByteArray(),
            )
            val statFile = java.io.File(statPath.toUri())
            statFile.setReadable(false, false)
            org.junit.Assume.assumeTrue(
                "chmod denial must actually deny this user",
                !statFile.canRead(),
            )
            assertEquals(
                "permission-denied stat: UNKNOWN, fence-safe",
                ProcessLiveness.UNKNOWN,
                CaptureProcessIdentitySource.liveness(
                    CaptureProcessIdentity(888, 9L), root.toString(),
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun dualWriteFailure_livePredecessorClaim_successorCannotWorkUntilThreadsExit() =
        runBlocking {
            // Audit 7239229553 (A03), the acceptance the
            // previous round missed: BOTH durable fence
            // channels fail at publish time while a predecessor
            // thread outlives close. Logging/degraded state is
            // not exclusion — exclusion now comes from the
            // process-wide authoritative ownership claim, which
            // lives in process memory and does not depend on
            // the failing store. The successor ATTEMPTS work
            // and provably cannot stage; once the predecessor's
            // threads actually exit, the claim is released and
            // the successor proceeds.
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
                health.failLifecycleWrites = true
                health.failDocWrites = true
                coordinator.close()
                assertTrue(
                    "stuck writer outlived close()",
                    coordinator.isOverflowWriterAlive(),
                )
                assertFalse(
                    "no durable fence exists on either channel",
                    health.fenceActive(),
                )
                assertTrue(
                    "the failed durable publish is still surfaced",
                    coordinator.degradedState().reasons.any {
                        it.contains("fence_publish_failed")
                    },
                )
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
                        "successor must not stage while the ownership " +
                            "claim stands (dual write failure is not a gap)",
                        eventsBefore, eventDao.count(),
                    )
                    assertTrue(
                        "successor reports itself fenced by the claim",
                        successor.degradedState().successorFenced,
                    )
                    writerLatch.countDown()
                    awaitCondition(
                        "successor proceeds once the predecessor's " +
                            "threads exited and released the claim",
                    ) {
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

    @Test
    fun journalBudget_burstOfDrops_totalWaitBoundedPerEpisode() = runBlocking {
        // Audit 7239229553 (A06): one callback can offer MANY
        // incident records (a burst of saturation drops). With
        // the journal store artificially slow (3 s per write),
        // ten consecutive dropping captures share ONE budget
        // window: their TOTAL journal waiting stays near
        // JOURNAL_CALLBACK_BUDGET_MS — not ten per-record
        // waits. Every skipped wait is counted as journal lag,
        // and once the store heals the journal still catches
        // up exactly (a successor finds no UNKNOWN_LOSS).
        val health = FakeCaptureHealthStore()
        health.journalWriteDelayMs = 3_000
        coordinator = newCoordinator(healthStore = health)
        val latch = blockExecutorWith(POST_TIME)
        try {
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            val started = System.currentTimeMillis()
            for (i in 4..13) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            val elapsed = System.currentTimeMillis() - started
            assertTrue(
                "a 10-record burst waits one budget window, not ten " +
                    "(was ${elapsed}ms)",
                elapsed < 1_500,
            )
            assertEquals(10, coordinator.counters().droppedOverflow)
            awaitCondition("skipped waits counted as journal lag") {
                coordinator.counters().healthJournalTimeouts >= 3
            }
        } finally {
            latch.countDown()
        }
        health.journalWriteDelayMs = 0
        coordinator.close()
        awaitCondition("journal caught up after the store healed", 15_000L) {
            val j = JSONObject(health.journal ?: "{}")
            val d = JSONObject(health.doc ?: "{}")
            j.optLong("offered", 0L) - j.optLong("dropped", 0L) ==
                d.optLong("applied_count", 0L)
        }
        val successor = newCoordinator(healthStore = health)
        try {
            successor.flushHealthIncidents(5_000)
            assertEquals(
                "no unknown loss once the journal caught up",
                null, health.incident(CaptureIncidentCategory.UNKNOWN_LOSS),
            )
        } finally {
            runCatching { successor.close() }
        }
    }

    @Test
    fun reconcile_livePredecessorPending_noFalseUnknownLoss_claimedOnceAfterStop() =
        runBlocking {
            // Audit 7239229553 (A06): startup reconciliation
            // must not call a LIVE predecessor's pending records
            // a dead process's loss. Phase 1: the predecessor's
            // health writer is parked inside a durable write
            // with records still unapplied while a successor
            // starts — no UNKNOWN_LOSS may appear, neither while
            // parked nor after the predecessor applies them.
            // Phase 2: a second predecessor stops with records
            // never applied — the next start claims the gap
            // exactly once, and the start after that claims
            // nothing more (no double counting).
            val health = FakeCaptureHealthStore()
            val gate = CountDownLatch(1)
            health.healthWriteBlockLatch = gate
            coordinator = newCoordinator(healthStore = health)
            val latch = blockExecutorWith(POST_TIME)
            try {
                for (i in 1..3) {
                    coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
                }
                captureLogDao.failNextInserts.set(Int.MAX_VALUE)
                coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
                awaitCondition("overflow record given up") {
                    coordinator.counters().overflowRecordDropped == 1L
                }
                awaitCondition(
                    "predecessor health writer parked in its durable write",
                ) {
                    health.healthWriteEntered.get() >= 1
                }
            } finally {
                captureLogDao.failNextInserts.set(0)
                latch.countDown()
            }
            val successor = newCoordinator(healthStore = health)
            try {
                Thread.sleep(800)
                assertEquals(
                    "a live predecessor's pending records are not a loss",
                    null, health.incident(CaptureIncidentCategory.UNKNOWN_LOSS),
                )
                gate.countDown()
                health.healthWriteBlockLatch = null
                awaitCondition("predecessor's pending records applied") {
                    health.incident(
                        CaptureIncidentCategory.OVERFLOW_RECORD_LOST,
                    ) != null
                }
                successor.flushHealthIncidents(5_000)
                assertEquals(
                    "still no unknown loss after the predecessor applied them",
                    null, health.incident(CaptureIncidentCategory.UNKNOWN_LOSS),
                )
            } finally {
                gate.countDown()
                health.healthWriteBlockLatch = null
                runCatching { successor.close() }
            }
            coordinator.close()

            // Phase 2: records offered, never applied, owner stopped.
            health.failDocWrites = true
            val predecessor2 = newCoordinator(healthStore = health)
            coordinator = predecessor2
            val latch2 = CountDownLatch(1)
            modeReaderLatch = latch2
            try {
                val eventsBefore = eventDao.count()
                predecessor2.capture(snapshot(postTime = POST_TIME + 200_000L))
                awaitCondition("second predecessor blocker staged") {
                    eventDao.count() == eventsBefore + 1
                }
                for (i in 1..3) {
                    predecessor2.capture(
                        snapshot(postTime = POST_TIME + 200_000L + i * 1_000L),
                    )
                }
                captureLogDao.failNextInserts.set(Int.MAX_VALUE)
                predecessor2.capture(snapshot(postTime = POST_TIME + 204_000L))
                awaitCondition("second predecessor record given up") {
                    predecessor2.counters().overflowRecordDropped == 1L
                }
                // Give its health writer time to attempt (and
                // fail) the durable applications.
                Thread.sleep(1_000)
            } finally {
                captureLogDao.failNextInserts.set(0)
                latch2.countDown()
                modeReaderLatch = null
            }
            predecessor2.close()
            health.failDocWrites = false
            val journal = JSONObject(health.journal ?: "{}")
            val docNow = JSONObject(health.doc ?: "{}")
            val expectedGap = journal.optLong("offered", 0L) -
                journal.optLong("dropped", 0L) -
                docNow.optLong("applied_count", 0L)
            assertTrue("phase 2 left a real gap", expectedGap > 0L)
            val claimer = newCoordinator(healthStore = health)
            try {
                awaitCondition("gap claimed once the owner has stopped") {
                    health.incident(CaptureIncidentCategory.UNKNOWN_LOSS) != null
                }
                assertEquals(
                    "the claimed count is exact",
                    expectedGap,
                    health.incident(CaptureIncidentCategory.UNKNOWN_LOSS)
                        ?.optLong("count"),
                )
            } finally {
                runCatching { claimer.close() }
            }
            val second = newCoordinator(healthStore = health)
            try {
                // Reconciliation is the second instance's health
                // writer's first action; give it time to run,
                // then the claim must be unchanged.
                Thread.sleep(1_500)
                second.flushHealthIncidents(5_000)
                assertEquals(
                    "the claim is not repeated by the next start",
                    expectedGap,
                    health.incident(CaptureIncidentCategory.UNKNOWN_LOSS)
                        ?.optLong("count"),
                )
            } finally {
                runCatching { second.close() }
            }
        }

    @Test
    fun ownershipRegistry_handshakeVariants_noOrphanClaim() {
        // Audit 7242084700 (A03), the registry handshake in
        // isolation, all three interleavings:
        // (1) both exits recorded BEFORE the claim is
        //     attempted — the claim must NOT be inserted, even
        //     if the sampled threads (stale objects) still
        //     report alive;
        // (2) claim inserted while both halves live — it
        //     stands;
        // (3) exits AFTER the claim — the first exit leaves
        //     the claim standing (one half still lives), the
        //     second releases it in the same critical section.
        val identity = CaptureProcessIdentity(4242, 111L)
        val exitedFirst = "handshake-variant-exited-first"
        CaptureOwnershipRegistry.noteThreadExit(exitedFirst, workerHalf = true)
        CaptureOwnershipRegistry.noteThreadExit(exitedFirst, workerHalf = false)
        assertFalse(
            "exits recorded before the claim: no claim may be inserted",
            CaptureOwnershipRegistry.claimIfAlive(
                exitedFirst, identity, { true }, { true },
            ),
        )
        assertFalse(
            CaptureOwnershipRegistry.hasForeignClaim("handshake-observer"),
        )
        val claimedFirst = "handshake-variant-claimed-first"
        assertTrue(
            CaptureOwnershipRegistry.claimIfAlive(
                claimedFirst, identity, { true }, { true },
            ),
        )
        assertTrue(
            "claim stands while both halves live",
            CaptureOwnershipRegistry.hasForeignClaim("handshake-observer"),
        )
        CaptureOwnershipRegistry.noteThreadExit(claimedFirst, workerHalf = true)
        assertTrue(
            "one half still lives: claim still stands",
            CaptureOwnershipRegistry.hasForeignClaim("handshake-observer"),
        )
        CaptureOwnershipRegistry.noteThreadExit(claimedFirst, workerHalf = false)
        assertFalse(
            "second exit releases the claim",
            CaptureOwnershipRegistry.hasForeignClaim("handshake-observer"),
        )
    }

    @Test
    fun ownershipHandshake_exitBetweenSampleAndClaim_noOrphanClaim_successorProceeds() =
        runBlocking {
            // Audit 7242084700 (A03), the deterministic barrier
            // test: the publish is parked INSIDE the registry
            // handshake — after the liveness sample, before the
            // claim insertion — and the stuck writer is then
            // released so its exit handler runs INTO the parked
            // handshake (it queues on the registry lock). The
            // old code inserted the claim after both exits had
            // already released, stranding a permanent foreign
            // claim; the atomic handshake records the exit the
            // moment the publish completes and releases the
            // claim in the same section. The successor then
            // proceeds WITHOUT a restart.
            val health = FakeCaptureHealthStore()
            coordinator = newCoordinator(healthStore = health)
            val latch = blockExecutorWith(POST_TIME)
            val writerLatch = CountDownLatch(1)
            val claimParked = CountDownLatch(1)
            val claimResume = CountDownLatch(1)
            CaptureOwnershipRegistry.claimBarrierHook = {
                claimParked.countDown()
                claimResume.await(15, TimeUnit.SECONDS)
            }
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
                health.failLifecycleWrites = true
                health.failDocWrites = true
                val closeThread = Thread { coordinator.close() }
                closeThread.start()
                assertTrue(
                    "publish parked between liveness sample and claim",
                    claimParked.await(15, TimeUnit.SECONDS),
                )
                // The writer exits into the parked handshake.
                writerLatch.countDown()
                Thread.sleep(500)
                claimResume.countDown()
                closeThread.join(20_000)
                assertFalse("close() returned", closeThread.isAlive)
                awaitCondition("writer thread exited") {
                    !coordinator.isOverflowWriterAlive()
                }
                Thread.sleep(500)
                val successor = newCoordinator(healthStore = health)
                try {
                    assertFalse(
                        "no permanent foreign claim from the raced publish",
                        successor.degradedState().successorFenced,
                    )
                    val eventsBefore = eventDao.count()
                    successor.capture(snapshot(postTime = POST_TIME + 9_000L))
                    awaitCondition("successor proceeds without a restart") {
                        eventDao.count() == eventsBefore + 1
                    }
                } finally {
                    runCatching { successor.close() }
                }
            } finally {
                CaptureOwnershipRegistry.claimBarrierHook = null
                claimResume.countDown()
                captureLogDao.insertBlockLatch = null
                writerLatch.countDown()
                latch.countDown()
            }
        }

    @Test
    fun constructionDuringClose_successorWaits_concurrentCloseIsOneShot() =
        runBlocking {
            // Audit 7242084700 (A03): construction/close
            // serialisation. The predecessor's close is parked
            // mid-publish (both durable channels failing, writer
            // stuck); a successor constructed DURING that close
            // must already be excluded — the ownership claim is
            // taken at the START of close, not only at publish —
            // and a second, concurrent close() call must be a
            // no-op (the one-shot sequence). Once the writer
            // exits, the successor proceeds.
            val health = FakeCaptureHealthStore()
            coordinator = newCoordinator(healthStore = health)
            val latch = blockExecutorWith(POST_TIME)
            val writerLatch = CountDownLatch(1)
            val claimParked = CountDownLatch(1)
            val claimResume = CountDownLatch(1)
            CaptureOwnershipRegistry.claimBarrierHook = {
                claimParked.countDown()
                claimResume.await(15, TimeUnit.SECONDS)
            }
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
                health.failLifecycleWrites = true
                health.failDocWrites = true
                val closeThread = Thread { coordinator.close() }
                closeThread.start()
                assertTrue(
                    "close parked mid-publish",
                    claimParked.await(15, TimeUnit.SECONDS),
                )
                // Concurrent second close: returns at once,
                // changes nothing.
                val secondClose = Thread { coordinator.close() }
                secondClose.start()
                secondClose.join(5_000)
                assertFalse(
                    "concurrent close() is a no-op and returns",
                    secondClose.isAlive,
                )
                // Successor constructed during the parked close.
                val eventsBefore = eventDao.count()
                val successor = newCoordinator(healthStore = health)
                try {
                    successor.capture(snapshot(postTime = POST_TIME + 9_000L))
                    Thread.sleep(800)
                    assertEquals(
                        "successor constructed during close must not stage",
                        eventsBefore, eventDao.count(),
                    )
                    assertTrue(
                        "successor reports itself fenced during the close",
                        successor.degradedState().successorFenced,
                    )
                    claimResume.countDown()
                    writerLatch.countDown()
                    closeThread.join(20_000)
                    assertFalse("close() returned", closeThread.isAlive)
                    awaitCondition(
                        "successor proceeds once the predecessor's threads exited",
                    ) {
                        eventDao.count() == eventsBefore + 1
                    }
                } finally {
                    runCatching { successor.close() }
                }
            } finally {
                CaptureOwnershipRegistry.claimBarrierHook = null
                claimResume.countDown()
                captureLogDao.insertBlockLatch = null
                writerLatch.countDown()
                latch.countDown()
            }
        }

    @Test
    fun journalBudget_multiEpisodeBurstAndSlowLogger_totalWaitBounded() = runBlocking {
        // Audit 7242084700 (A06): three corrections over the
        // previous round. (1) The budget window is per BURST,
        // not per sync episode: with a journal store that
        // answers in ~150 ms, every offer's sync SETTLES before
        // the next offer, so the old per-episode window handed
        // each offer a fresh budget — twelve offers would have
        // waited ~1.8 s. (2) The deadline is MONOTONIC: the
        // window cannot be stretched by wall-clock movement
        // (the budget path no longer reads the wall clock at
        // all — the injected coordinator clock below is the
        // wall clock the rest of the pipeline uses, and
        // swinging it mid-burst changes nothing). (3) The lag
        // log lines are OFF the caller: the logger below sleeps
        // 300 ms per line, and the previous round emitted the
        // lag lines synchronously, outside the wait budget.
        val slowLines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val slowLogger = object : CaptureLogSink {
            override fun logEvent(pkg: String, text: String) {
                slowLines.add("$pkg: $text")
            }

            override fun logLocal(text: String) {
                Thread.sleep(300)
                slowLines.add(text)
            }
        }
        val health = FakeCaptureHealthStore()
        health.journalWriteDelayMs = 150
        coordinator = newCoordinator(healthStore = health, loggerOverride = slowLogger)
        val latch = blockExecutorWith(POST_TIME)
        try {
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            val started = System.nanoTime()
            for (i in 4..15) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(12, coordinator.counters().droppedOverflow)
            assertTrue(
                "a 12-record multi-episode burst with a 300 ms logger " +
                    "stays inside one monotonic budget window " +
                    "(was ${elapsedMs}ms; per-episode waits alone " +
                    "would exceed 1.8 s)",
                elapsedMs < 1_200,
            )
            awaitCondition("skipped waits counted as journal lag") {
                coordinator.counters().healthJournalTimeouts >= 3
            }
        } finally {
            latch.countDown()
        }
        health.journalWriteDelayMs = 0
        coordinator.close()
        awaitCondition("journal caught up after the store healed", 15_000L) {
            val j = JSONObject(health.journal ?: "{}")
            val d = JSONObject(health.doc ?: "{}")
            j.optLong("offered", 0L) - j.optLong("dropped", 0L) ==
                d.optLong("applied_count", 0L)
        }
        val successor = newCoordinator(healthStore = health)
        try {
            successor.flushHealthIncidents(5_000)
            assertEquals(
                "no unknown loss once the journal caught up",
                null, health.incident(CaptureIncidentCategory.UNKNOWN_LOSS),
            )
        } finally {
            runCatching { successor.close() }
        }
    }

    @Test
    fun journalBudget_monotonicDeadline_fakeClockJump_endsBurstWaits() = runBlocking {
        // Audit 7242084700 (A06): the deadline arithmetic is
        // driven by the monotonic source, deterministically:
        // with the journal store slow (3 s), the first offers
        // wait inside the window; when the monotonic clock is
        // jumped past the deadline mid-burst (the deterministic
        // stand-in for any real clock disturbance — the wall
        // clock is not consulted on this path at all), every
        // further offer in the burst returns WITHOUT waiting
        // and is counted as lag, and the journal still catches
        // up exactly once the store heals.
        val health = FakeCaptureHealthStore()
        health.journalWriteDelayMs = 3_000
        coordinator = newCoordinator(healthStore = health)
        val realNanos = { System.nanoTime() }
        val jumpNanos = java.util.concurrent.atomic.AtomicLong(0L)
        coordinator.monotonicNowNanos = { realNanos() + jumpNanos.get() }
        val latch = blockExecutorWith(POST_TIME)
        try {
            for (i in 1..3) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            coordinator.capture(snapshot(postTime = POST_TIME + 4 * 1_000L))
            // Jump the monotonic source PAST the burst
            // deadline: the first offer's wait consumed ~250 ms
            // of the 500 ms window, so +300 ms from here lands
            // beyond the deadline while staying inside the
            // burst's quiet gap measured from the last offer
            // ACTIVITY (the waiting offer was never quiet) —
            // the burst is the same burst, its window is simply
            // over, whatever the wall clock does. (A jump of a
            // minute would be indistinguishable from a minute
            // of real quiet and would legitimately open a NEW
            // burst; monotonic time cannot tell the two apart,
            // by design.)
            jumpNanos.set(300_000_000L)
            val started = System.nanoTime()
            for (i in 5..9) {
                coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(
                "offers after the monotonic deadline do not wait " +
                    "(was ${elapsedMs}ms)",
                elapsedMs < 400,
            )
            awaitCondition("post-deadline offers counted as journal lag") {
                coordinator.counters().healthJournalTimeouts >= 5
            }
        } finally {
            latch.countDown()
        }
        health.journalWriteDelayMs = 0
        coordinator.close()
        awaitCondition("journal caught up after the store healed", 15_000L) {
            val j = JSONObject(health.journal ?: "{}")
            val d = JSONObject(health.doc ?: "{}")
            j.optLong("offered", 0L) - j.optLong("dropped", 0L) ==
                d.optLong("applied_count", 0L)
        }
    }

    @Test
    fun reconcile_mixedJournal_legacyRemainderAndAttributed_claimedExactlyOnce() =
        runBlocking {
            // Audit 7242084700 (A06): the mixed-journal
            // migration. The durable journal carries BOTH
            // pre-attribution totals (offered 9, dropped 1) AND
            // one attributed entry (dead-owner: offered 4,
            // dropped 0) — the state left when the first
            // attributed instance synced its entry, then died
            // before a start reconciled the legacy totals. The
            // document applied 2 in total, 1 of them attributed
            // to dead-owner. Exact decomposition:
            //   attributed gap = (4 - 0) - 1 = 3
            //   legacy remainder = (9 - 4) - (1 - 0) = 4
            //     accounted, applied share = 2 - 1 = 1 -> gap 3
            // Total claim must be exactly 6, applied_count must
            // land on offered - dropped = 8, and the next start
            // must claim nothing more.
            val health = FakeCaptureHealthStore()
            health.journal = JSONObject().apply {
                put("offered", 9L)
                put("dropped", 1L)
                put(
                    "instances",
                    JSONObject().apply {
                        put(
                            "dead-owner",
                            JSONObject().apply {
                                put("offered", 4L)
                                put("dropped", 0L)
                                put("pid", 9999)
                                put("start", 5L)
                            },
                        )
                    },
                )
            }.toString()
            health.seedDoc(
                JSONObject().apply {
                    put("version", 2)
                    put("applied_count", 2L)
                    put(
                        "applied_by",
                        JSONObject().apply { put("dead-owner", 1L) },
                    )
                    put("incidents", org.json.JSONArray())
                }.toString(),
            )
            val claimer = newCoordinator(healthStore = health)
            coordinator = claimer
            try {
                awaitCondition("mixed journal claimed") {
                    health.incident(CaptureIncidentCategory.UNKNOWN_LOSS) != null
                }
                assertEquals(
                    "legacy remainder + attributed gap claimed exactly",
                    6L,
                    health.incident(CaptureIncidentCategory.UNKNOWN_LOSS)
                        ?.optLong("count"),
                )
                awaitCondition("applied_count lands on offered - dropped") {
                    JSONObject(health.doc ?: "{}")
                        .optLong("applied_count", 0L) == 8L
                }
            } finally {
                runCatching { claimer.close() }
            }
            val second = newCoordinator(healthStore = health)
            coordinator = second
            try {
                Thread.sleep(1_500)
                second.flushHealthIncidents(5_000)
                assertEquals(
                    "the mixed claim is not repeated by the next start",
                    6L,
                    health.incident(CaptureIncidentCategory.UNKNOWN_LOSS)
                        ?.optLong("count"),
                )
            } finally {
                runCatching { second.close() }
            }
        }

    @Test
    fun reconcile_lateJournalUpdate_claimedExactlyOnceOnNextStart() = runBlocking {
        // Audit 7242084700 (A06): a LATE journal update — the
        // dead owner's lagging sync lands AFTER a successor
        // already reconciled the owner's then-current gap.
        // Phase 1: journal says dead-owner offered 3, applied
        // 1 -> the first successor claims exactly 2 and marks
        // reconciled_by = 2. Phase 2: the journal entry grows
        // to offered 5 (the late delta) -> the next start
        // claims exactly the growth (2 more, total 4), never
        // the whole gap again. Phase 3: a further start claims
        // nothing.
        val health = FakeCaptureHealthStore()
        fun journalWith(offered: Long): String = JSONObject().apply {
            put("offered", offered)
            put("dropped", 0L)
            put(
                "instances",
                JSONObject().apply {
                    put(
                        "dead-owner",
                        JSONObject().apply {
                            put("offered", offered)
                            put("dropped", 0L)
                            put("pid", 9999)
                            put("start", 5L)
                        },
                    )
                },
            )
        }.toString()
        health.journal = journalWith(3L)
        health.seedDoc(
            JSONObject().apply {
                put("version", 2)
                put("applied_count", 1L)
                put(
                    "applied_by",
                    JSONObject().apply { put("dead-owner", 1L) },
                )
                put("incidents", org.json.JSONArray())
            }.toString(),
        )
        val first = newCoordinator(healthStore = health)
        coordinator = first
        try {
            awaitCondition("first claim lands") {
                health.incident(CaptureIncidentCategory.UNKNOWN_LOSS)
                    ?.optLong("count") == 2L
            }
        } finally {
            runCatching { first.close() }
        }
        // The late delta lands after the first reconciliation.
        health.journal = journalWith(5L)
        val second = newCoordinator(healthStore = health)
        coordinator = second
        try {
            awaitCondition("late growth claimed exactly once") {
                health.incident(CaptureIncidentCategory.UNKNOWN_LOSS)
                    ?.optLong("count") == 4L
            }
        } finally {
            runCatching { second.close() }
        }
        val third = newCoordinator(healthStore = health)
        coordinator = third
        try {
            Thread.sleep(1_500)
            third.flushHealthIncidents(5_000)
            assertEquals(
                "no third claim: the late update was counted exactly once",
                4L,
                health.incident(CaptureIncidentCategory.UNKNOWN_LOSS)
                    ?.optLong("count"),
            )
        } finally {
            runCatching { third.close() }
        }
    }

}
