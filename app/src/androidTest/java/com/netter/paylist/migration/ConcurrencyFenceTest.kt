package com.netter.paylist.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.FakeTransport
import com.netter.paylist.PaylistApplication
import com.netter.paylist.Route
import com.netter.paylist.Sender
import com.netter.paylist.SenderLock
import com.netter.paylist.Store
import com.netter.paylist.ToggleSecrets
import com.netter.paylist.chainQkey
import com.netter.paylist.chainQueueItem
import com.netter.paylist.data.PaylistDatabase
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.delivery.TransportClient
import com.netter.paylist.delivery.TransportRequest
import com.netter.paylist.delivery.TransportResult
import com.netter.paylist.paymentPayload
import com.netter.paylist.privacy.CutoverPrivacyPreflight
import com.netter.paylist.privacy.GateBackedInspector
import com.netter.paylist.security.SecretResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R06 — the concurrency fence (ChatGPT re-review #5, review 6011401446):
 * the production-like [com.netter.paylist.ModeGate], the REAL legacy
 * [Sender] flush, and the REAL [CutoverCoordinator] under contention,
 * against file-backed Room and the REAL [Store] queue.
 *
 * Proves, per payment — keyed by `dedupe_key`/`qkey`, never by totals
 * alone:
 * 1. No POST starts after the `DRAINING` commit, under contention
 *    (every recorded POST observed `LEGACY_ONLY` at start and started
 *    before the commit returned).
 * 2. No lost queued payment: every seeded payment ends up either
 *    settled via POST or imported into Room (conservation).
 * 3. Exactly one durable owner after cutover: each payment is in
 *    exactly one of {settled via POST, Room delivery} — never both,
 *    never neither — with the legacy queue empty at the end.
 * 4. Abort/retry: [CutoverCoordinator.abortCutover] restores
 *    `LEGACY_ONLY` and the sender resumes posting; `requestDrain`
 *    from `ABORTED` succeeds and the retry cutover completes with
 *    the same conservation + one-owner guarantees.
 *
 * Determinism: no timing assumptions. Contention is arranged with
 * latches and barriers (the cutover thread waits for the first
 * settled POST before attempting the lock; the slow-POST test gates
 * one POST on a latch the test releases), and every wait has a
 * bounded timeout that fails loudly instead of hanging CI. The
 * "slow" payments are slow by CONSTRUCTION, not by timing: the fake
 * transport answers them `NetworkError` forever, so they can never
 * settle via POST and must be imported by the drain — the race is
 * guaranteed to straddle the commit on both sides.
 *
 * EXECUTION NOTE: these tests need a device or an emulator. They
 * COMPILE on laptopgrt2 (`assembleDebugAndroidTest`) but cannot
 * execute there — there is no device/emulator on that box. Do not
 * report them as "passing" from a compile-only gate.
 */
@RunWith(AndroidJUnit4::class)
class ConcurrencyFenceTest {

    private lateinit var ctx: Context
    private lateinit var db: PaylistDatabase
    private lateinit var store: Store

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE).edit().clear().commit()
        store = Store(ctx)
        store.saveRoutes(
            listOf(
                Route(
                    "route-1", "v2New", listOf("com.gojek.gopaymerchant"),
                    "https://example.invalid/notify-1", Route.DEFAULT_KEYWORDS, true,
                ),
                Route(
                    "route-2", "warganet", listOf("com.gojek.gopaymerchant"),
                    "https://example.invalid/notify-2", Route.DEFAULT_KEYWORDS, true,
                ),
            ),
        )
        // File-backed Room (P1-1): the mode + drain state under test is
        // durable state; an in-memory database would prove nothing
        // about it. The file is deleted first so every test starts
        // from an empty schema.
        ctx.deleteDatabase(TEST_DB_NAME)
        db = Room.databaseBuilder(ctx, PaylistDatabase::class.java, TEST_DB_NAME).build()
    }

    @After
    fun tearDown() {
        db.close()
        ctx.deleteDatabase(TEST_DB_NAME)
        ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // =====================================================================
    // Harness
    // =====================================================================

    /** The queue source over the real [Store], as in production. */
    private fun storeQueueSource(): LegacyQueueSnapshotSource =
        object : LegacyQueueSnapshotSource {
            override fun queueSnapshot(): List<JSONObject> = store.queueSnapshot()
            override fun saveQueueSync(items: List<JSONObject>): Boolean =
                store.saveQueueSync(items)
        }

    private fun newCoordinator(queueSource: LegacyQueueSnapshotSource): CutoverCoordinator {
        val migration = LegacyQueueMigration(
            db.eventDao(),
            db.deliveryDao(),
            db.attemptDao(),
            db.promotionReceiptDao(),
            db.transactionRunner(),
        )
        return CutoverCoordinator(
            db.collectorStateDao(),
            db.deliveryDao(),
            db.attemptDao(),
            db.transactionRunner(),
            migration,
            queueSource,
            SenderLock.lock,
            neverSentGatePassed = true,
        )
    }

    private suspend fun setBootId(bootId: String) {
        db.collectorStateDao().put(
            CollectorStateEntity(
                CollectorStateKeys.BOOT_ID,
                JSONObject().put("boot_id", bootId).put("boot_started_at_ms", NOW).toString(),
                NOW,
            ),
        )
    }

    private fun seedQueue(items: List<JSONObject>) {
        assertTrue("queue seed must commit", store.saveQueueSync(items))
    }

    /** The production sender over the production gate factory (not a copy). */
    private fun newSender(coordinator: CutoverCoordinator, transport: TransportClient): Sender =
        Sender(
            ctx,
            PaylistApplication.productionModeGate(coordinator),
            ToggleSecrets(SecretResult.Present("s3cr3t")),
            transport,
        )

    private fun waitUntil(timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("timed out waiting for condition", condition())
    }

    /** A POST outcome, as the fake server answers it. */
    private enum class PostOutcome {
        /** Matched: the payment settled on this POST (chain stops). */
        MATCHED,

        /** Network error: the POST never reached the server — no effect. */
        NETWORK_ERROR,
    }

    /**
     * A fake transport that records every POST start: its
     * `dedupe_key`, its start time on the shared monotonic clock, the
     * coordinator mode observed AT THE POST (read inside `post()`),
     * and the outcome it answered. Keys in [slowDedupeKeys] always
     * answer `NetworkError`: they can never settle via POST, so they
     * must survive in the queue until the drain imports them.
     */
    private class RecordingTransport(
        private val coordinator: CutoverCoordinator,
        private val slowDedupeKeys: Set<String> = emptySet(),
    ) : TransportClient {

        data class PostRecord(
            val dedupeKey: String,
            val startedNanos: Long,
            val modeAtStart: CutoverMode,
            val outcome: PostOutcome,
            val threadName: String,
        )

        private val _posts = Collections.synchronizedList(mutableListOf<PostRecord>())

        /** All recorded POSTs, in start order (a defensive copy). */
        val posts: List<PostRecord> get() = synchronized(_posts) { _posts.toList() }

        /** Fires on the first MATCHED post (a payment settled via POST). */
        val firstSettled = CountDownLatch(1)

        /**
         * Hook run synchronously inside `post()`, after the record is
         * taken and before the response — the slow-POST test blocks
         * here to hold the sender lock mid-flush.
         */
        var onPostStart: ((PostRecord) -> Unit)? = null

        override suspend fun post(
            request: TransportRequest,
            onSocketOpen: suspend () -> Boolean,
        ): TransportResult {
            // Mirror the real client ordering: the SOCKET_OPEN checkpoint
            // runs before the first request byte (here: before the record).
            if (!onSocketOpen()) return TransportResult.CheckpointFailed
            val dedupeKey = try {
                JSONObject(request.body).optString("dedupe_key")
            } catch (_: Exception) {
                ""
            }
            val outcome =
                if (slowDedupeKeys.contains(dedupeKey)) PostOutcome.NETWORK_ERROR
                else PostOutcome.MATCHED
            val record = PostRecord(
                dedupeKey = dedupeKey,
                startedNanos = System.nanoTime(),
                // The mode the POST itself observed: past the DRAINING
                // commit this must never read LEGACY_ONLY — and no POST
                // may start at all.
                modeAtStart = coordinator.getMode(),
                outcome = outcome,
                threadName = Thread.currentThread().name,
            )
            _posts.add(record)
            onPostStart?.invoke(record)
            return when (outcome) {
                PostOutcome.MATCHED -> {
                    firstSettled.countDown()
                    FakeTransport.matchedResponse()
                }
                PostOutcome.NETWORK_ERROR -> TransportResult.NetworkError("io")
            }
        }
    }

    /** Loops `sender.flush()` until [stop] is set; captures any throw. */
    private class FlushLoop(
        private val sender: Sender,
        private val stop: AtomicBoolean,
    ) : Runnable {
        @Volatile var error: Throwable? = null

        override fun run() {
            try {
                while (!stop.get()) {
                    sender.flush()
                    Thread.yield()
                }
            } catch (t: Throwable) {
                error = t
            }
        }
    }

    /** A one-shot runnable that captures any throw instead of dying silently. */
    private class ErrorCatchingRunnable(private val block: () -> Unit) : Runnable {
        @Volatile var error: Throwable? = null

        override fun run() {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            }
        }
    }

    /**
     * The cutover's fence-critical section, in the exact order
     * `PaylistApplication.runCutover` performs it: wait for
     * [awaitStart] (the race precondition) → take the sender lock
     * (timed) → privacy preflight → durable flush + verify → commit
     * `DRAINING` → release the lock. Records the commit result and
     * the earliest observable "commit is durable" timestamp
     * ([commitReturnNanos], taken the moment `commitDraining`
     * returns).
     */
    private inner class CutoverRun(
        private val coordinator: CutoverCoordinator,
        private val lockTimeoutSeconds: Long,
        private val awaitStart: CountDownLatch? = null,
    ) : Runnable {
        @Volatile var result: CutoverCommit? = null
        @Volatile var commitReturnNanos: Long = -1L
        @Volatile var error: Throwable? = null

        /** Fires just before the lock attempt (the test's sync point). */
        val attemptingLock = CountDownLatch(1)

        /** Fires when the run finishes (success, rejection, or throw). */
        val done = CountDownLatch(1)

        override fun run() {
            try {
                if (awaitStart != null && !awaitStart.await(15, TimeUnit.SECONDS)) {
                    error = AssertionError("timed out waiting for the race precondition")
                    return
                }
                attemptingLock.countDown()
                val locked = try {
                    SenderLock.lock.tryLock(lockTimeoutSeconds, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
                if (!locked) {
                    result = CutoverCommit.Rejected(
                        "sender lock not acquired in ${lockTimeoutSeconds}s",
                    )
                    return
                }
                try {
                    val preflight = CutoverPrivacyPreflight().run(storeQueueSource(), NOW)
                    if (!preflight.ok) {
                        result = CutoverCommit.Rejected("preflight: ${preflight.reason}")
                        return
                    }
                    val snapshot = store.queueSnapshot()
                    if (!store.saveQueueSync(snapshot)) {
                        result = CutoverCommit.Rejected("durable flush failed")
                        return
                    }
                    if (store.queueSnapshot().size != snapshot.size) {
                        result = CutoverCommit.Rejected("queue verify mismatch")
                        return
                    }
                    val committed = runBlocking { coordinator.commitDraining(NOW) }
                    commitReturnNanos = System.nanoTime()
                    result = committed
                } finally {
                    SenderLock.lock.unlock()
                }
            } catch (t: Throwable) {
                error = t
            } finally {
                done.countDown()
            }
        }
    }

    /** Starts [count] flush loops; returns the (loops, threads) pair. */
    private fun startFlushLoops(sender: Sender, stop: AtomicBoolean, count: Int = 4):
        Pair<List<FlushLoop>, List<Thread>> {
        val loops = (1..count).map { FlushLoop(sender, stop) }
        val threads = loops.mapIndexed { i, loop -> Thread(loop, "r06-flush-$i") }
        threads.forEach { it.start() }
        return loops to threads
    }

    private fun stopFlushLoops(
        loops: List<FlushLoop>,
        threads: List<Thread>,
        stop: AtomicBoolean,
    ) {
        stop.set(true)
        threads.forEach { it.join(30_000) }
        threads.forEach { assertFalse("flush thread must stop", it.isAlive) }
        loops.forEach { assertNull("flush thread must not throw: ${it.error}", it.error) }
    }

    /** The `dedupe_key` for a tag (via the same builder as the payload). */
    private fun dedupeKey(tag: String): String =
        paymentPayload(tag).optString("dedupe_key")

    /**
     * Asserts conservation + exactly-one-owner for [tags] after a
     * completed cutover: every tag's payment was either settled via
     * POST ([settledQkeys]) or imported into Room — never both,
     * never neither — and the legacy queue is empty.
     */
    private fun assertConservationAndOneOwner(
        tags: List<String>,
        settledQkeys: Set<String>,
    ) {
        val allQkeys = tags.map { chainQkey(it, ROUTES) }.toSet()
        val importedQkeys = allQkeys.filter { qkey ->
            runBlocking { db.deliveryDao().getByLegacyQkey(qkey) } != null
        }.toSet()
        assertTrue(
            "a payment must never be both settled via POST and imported: " +
                settledQkeys.intersect(importedQkeys),
            settledQkeys.intersect(importedQkeys).isEmpty(),
        )
        assertEquals(
            "every payment must have exactly one owner (settled XOR imported)",
            allQkeys,
            settledQkeys.union(importedQkeys),
        )
        assertEquals(
            "the Room event count must match the imported set",
            importedQkeys.size,
            runBlocking { db.eventDao().count() },
        )
        assertTrue(
            "the legacy queue must be empty after cutover, leftovers: " +
                store.queueSnapshot().map { it.optString("qkey") },
            store.queueSnapshot().isEmpty(),
        )
    }

    // =====================================================================
    // 1 + 2 + 3: racing flushes stop at DRAINING; no loss, one owner
    // =====================================================================

    @Test
    fun racingFlushes_stopPostingAtDraining_noLossNoDuplicate() = runBlocking {
        setBootId(BOOT_A)
        val fastTags = listOf("r06-a-f1", "r06-a-f2", "r06-a-f3", "r06-a-f4")
        val slowTags = listOf("r06-a-s1", "r06-a-s2", "r06-a-s3", "r06-a-s4")
        val allTags = fastTags + slowTags
        seedQueue(allTags.map { chainQueueItem(paymentPayload(it), ROUTES) })
        val coordinator = newCoordinator(storeQueueSource())
        val transport = RecordingTransport(
            coordinator,
            slowTags.map { dedupeKey(it) }.toSet(),
        )
        val sender = newSender(coordinator, transport)

        assertTrue(coordinator.requestDrain(NOW) is CutoverCommit.Success)

        // Four flush loops hammer the sender while the cutover thread
        // waits for the first settled POST, then takes the lock and
        // commits DRAINING — the race is real on both sides: the fast
        // payments settle via POST, the slow ones stay queued.
        val stop = AtomicBoolean(false)
        val (loops, flushThreads) = startFlushLoops(sender, stop)
        val cutover = CutoverRun(
            coordinator,
            lockTimeoutSeconds = 10,
            awaitStart = transport.firstSettled,
        )
        val cutoverThread = Thread(cutover, "r06-cutover")
        cutoverThread.start()

        assertTrue("cutover must finish", cutover.done.await(30, TimeUnit.SECONDS))
        assertNull("cutover thread must not throw: ${cutover.error}", cutover.error)
        val committed = cutover.result
        assertTrue("commitDraining must succeed: $committed", committed is CutoverCommit.Success)
        assertEquals(CutoverMode.DRAINING, coordinator.getMode())

        // The drain + WORKER_ONLY commit run while the flush loops
        // keep hammering the (closed) gate — POSTs must stay at zero
        // through the whole drain, not just at the commit instant.
        val drained = coordinator.drain(GateBackedInspector, NOW)
        assertTrue("drain must succeed: ${drained.error}", drained.ok)
        assertTrue(coordinator.commitWorkerOnly(NOW) is CutoverCommit.Success)
        assertEquals(CutoverMode.WORKER_ONLY, coordinator.getMode())

        stopFlushLoops(loops, flushThreads, stop)

        val posts = transport.posts
        // 1. No POST after DRAINING, by two independent witnesses: the
        // mode each POST observed at its own start, and the shared
        // monotonic clock against the commit's return.
        assertTrue(
            "every POST must observe LEGACY_ONLY at its start",
            posts.all { it.modeAtStart == CutoverMode.LEGACY_ONLY },
        )
        assertTrue(
            "no POST may start after the DRAINING commit",
            posts.all { it.startedNanos < cutover.commitReturnNanos },
        )
        // The slow keys were attempted (the race was live) but never
        // settled; the fast keys settled exactly once each.
        val slowQkeys = slowTags.map { chainQkey(it, ROUTES) }.toSet()
        val qkeyByDedupe = allTags.associate { dedupeKey(it) to chainQkey(it, ROUTES) }
        val matchedByQkey = posts.filter { it.outcome == PostOutcome.MATCHED }
            .groupBy { qkeyByDedupe.getValue(it.dedupeKey) }
        val attemptedSlowQkeys = posts.filter { it.outcome == PostOutcome.NETWORK_ERROR }
            .map { qkeyByDedupe.getValue(it.dedupeKey) }.toSet()
        assertEquals(
            "every slow payment must have been attempted before the commit",
            slowQkeys,
            attemptedSlowQkeys,
        )
        assertTrue(
            "a slow payment must never settle via POST: " +
                matchedByQkey.keys.intersect(slowQkeys),
            matchedByQkey.keys.intersect(slowQkeys).isEmpty(),
        )
        assertTrue(
            "each settled payment must settle exactly once",
            matchedByQkey.values.all { it.size == 1 },
        )
        // The race genuinely straddled the commit on both sides.
        assertTrue(
            "at least one payment must settle via POST before the commit",
            matchedByQkey.isNotEmpty(),
        )
        assertEquals(
            "the drain must import exactly the un-settled payments",
            slowQkeys.size,
            drained.imported,
        )
        assertEquals(slowQkeys.size, drained.removed)

        // 2 + 3. Conservation + exactly one durable owner, per qkey.
        assertConservationAndOneOwner(allTags, matchedByQkey.keys)
    }

    // =====================================================================
    // 1 (reverse): an in-flight flush holds the commit until it finishes
    // =====================================================================

    @Test
    fun inFlightFlushHoldsDrainingCommit_untilItFinishes() = runBlocking {
        setBootId(BOOT_A)
        val tag = "r06-b-slow"
        seedQueue(listOf(chainQueueItem(paymentPayload(tag), ROUTES)))
        val coordinator = newCoordinator(storeQueueSource())
        val transport = RecordingTransport(coordinator)
        // Gate ONE post on a latch: the flush holds the sender lock
        // mid-POST until the test releases it.
        val postStarted = CountDownLatch(1)
        val releasePost = CountDownLatch(1)
        transport.onPostStart = {
            postStarted.countDown()
            assertTrue(
                "the test must release the gated POST",
                releasePost.await(15, TimeUnit.SECONDS),
            )
        }
        val sender = newSender(coordinator, transport)

        assertTrue(coordinator.requestDrain(NOW) is CutoverCommit.Success)

        val flushRun = ErrorCatchingRunnable { sender.flush() }
        val flushThread = Thread(flushRun, "r06-b-flush")
        flushThread.start()
        assertTrue("the POST must start", postStarted.await(15, TimeUnit.SECONDS))

        // The cutover thread now runs the production path with the
        // production 45 s lock wait. It must NOT commit while the
        // flush holds the lock — and it must NOT time out or silently
        // cancel either: it waits, then commits once the flush ends.
        val cutover = CutoverRun(coordinator, lockTimeoutSeconds = 45)
        val cutoverThread = Thread(cutover, "r06-b-cutover")
        cutoverThread.start()
        assertTrue(
            "cutover must reach the lock attempt",
            cutover.attemptingLock.await(15, TimeUnit.SECONDS),
        )
        // Deterministic, not timing: the flush still holds the lock
        // (its POST is gated on releasePost), so the commit cannot
        // have happened yet — whatever the scheduler did.
        assertEquals(CutoverMode.LEGACY_ONLY, coordinator.getMode())
        assertEquals("the cutover must still be waiting on the lock", 1L, cutover.done.count)

        releasePost.countDown()
        flushThread.join(15_000)
        assertFalse("flush must finish after the release", flushThread.isAlive)
        assertNull("flush thread must not throw: ${flushRun.error}", flushRun.error)
        assertTrue(
            "cutover must finish after the flush releases the lock",
            cutover.done.await(30, TimeUnit.SECONDS),
        )
        assertNull("cutover thread must not throw: ${cutover.error}", cutover.error)
        assertTrue(
            "commitDraining must succeed after the flush: ${cutover.result}",
            cutover.result is CutoverCommit.Success,
        )
        assertEquals(CutoverMode.DRAINING, coordinator.getMode())

        // The in-flight POST completed exactly once, under
        // LEGACY_ONLY, and started before the commit — the flush was
        // held, never cancelled, never replayed.
        val posts = transport.posts
        assertEquals(1, posts.size)
        assertEquals(CutoverMode.LEGACY_ONLY, posts.single().modeAtStart)
        assertEquals(PostOutcome.MATCHED, posts.single().outcome)
        assertTrue(
            "the held POST must start before the DRAINING commit",
            posts.single().startedNanos < cutover.commitReturnNanos,
        )

        // Conservation: the payment was delivered by the held flush —
        // it is neither in the queue nor imported by the (empty) drain.
        assertTrue(store.queueSnapshot().isEmpty())
        val drained = coordinator.drain(GateBackedInspector, NOW)
        assertTrue("drain must succeed: ${drained.error}", drained.ok)
        assertEquals(0, drained.imported)
        assertTrue(coordinator.commitWorkerOnly(NOW) is CutoverCommit.Success)
        assertEquals(CutoverMode.WORKER_ONLY, coordinator.getMode())
        assertNull(
            "the delivered payment must not also exist in Room",
            db.deliveryDao().getByLegacyQkey(chainQkey(tag, ROUTES)),
        )
        assertEquals(0, db.eventDao().count())
    }

    // =====================================================================
    // 4: abort restores the sender; retry from ABORTED completes
    // =====================================================================

    @Test
    fun abortRestoresLegacySender_retryCutoverSucceeds_noLossNoDuplicate() = runBlocking {
        setBootId(BOOT_A)
        val batch1Fast = listOf("r06-c1-f1", "r06-c1-f2")
        val batch1Slow = listOf("r06-c1-s1", "r06-c1-s2")
        val batch1 = batch1Fast + batch1Slow
        seedQueue(batch1.map { chainQueueItem(paymentPayload(it), ROUTES) })
        val coordinator = newCoordinator(storeQueueSource())
        val transport = RecordingTransport(
            coordinator,
            batch1Slow.map { dedupeKey(it) }.toSet(),
        )
        val sender = newSender(coordinator, transport)

        // --- Attempt 1: race to DRAINING, then abort. ---
        assertTrue(coordinator.requestDrain(NOW) is CutoverCommit.Success)
        assertEquals(1L, coordinator.getDrainGeneration())

        val stop1 = AtomicBoolean(false)
        val (loops1, flushThreads1) = startFlushLoops(sender, stop1)
        val cutover1 = CutoverRun(
            coordinator,
            lockTimeoutSeconds = 10,
            awaitStart = transport.firstSettled,
        )
        Thread(cutover1, "r06-c-cutover-1").start()
        assertTrue("cutover 1 must finish", cutover1.done.await(30, TimeUnit.SECONDS))
        assertNull("cutover 1 must not throw: ${cutover1.error}", cutover1.error)
        assertTrue(
            "commitDraining 1 must succeed: ${cutover1.result}",
            cutover1.result is CutoverCommit.Success,
        )
        assertEquals(CutoverMode.DRAINING, coordinator.getMode())

        // Abort while the flush loops are still hammering: the mode
        // flips back to LEGACY_ONLY and the sender resumes posting.
        // (Production reaches abort only from runCutover's pre-drain
        // failure paths, so no drain is in flight here — the same
        // shape as the production abort.)
        val aborted = coordinator.abortCutover(NOW)
        val abortReturnNanos = System.nanoTime()
        assertTrue("abort must succeed: $aborted", aborted is CutoverCommit.Success)
        assertEquals(CutoverMode.LEGACY_ONLY, coordinator.getMode())
        assertEquals(DrainIntent.ABORTED, coordinator.getDrainIntent())

        // The resume is observable, not assumed: at least one POST
        // must start after the abort (the slow keys are re-attempted
        // on every flush pass once the gate re-opens).
        waitUntil {
            transport.posts.any { it.startedNanos > abortReturnNanos }
        }
        stopFlushLoops(loops1, flushThreads1, stop1)

        // --- Batch 2 arrives while LEGACY_ONLY (Stage C capture still
        // targets the legacy queue): it must survive the retry too. ---
        val batch2 = listOf("r06-c2-a", "r06-c2-b")
        val remaining = store.queueSnapshot()
        seedQueue(remaining + batch2.map { chainQueueItem(paymentPayload(it), ROUTES) })

        // --- Attempt 2: requestDrain from ABORTED (the cf823c3 path),
        // then race to DRAINING again under contention. ---
        val retried = coordinator.requestDrain(NOW)
        assertTrue("requestDrain after abort must succeed: $retried", retried is CutoverCommit.Success)
        assertEquals(2L, coordinator.getDrainGeneration())
        assertEquals(DrainIntent.PENDING, coordinator.getDrainIntent())

        val stop2 = AtomicBoolean(false)
        val (loops2, flushThreads2) = startFlushLoops(sender, stop2)
        val cutover2 = CutoverRun(coordinator, lockTimeoutSeconds = 10)
        Thread(cutover2, "r06-c-cutover-2").start()
        assertTrue("cutover 2 must finish", cutover2.done.await(30, TimeUnit.SECONDS))
        assertNull("cutover 2 must not throw: ${cutover2.error}", cutover2.error)
        assertTrue(
            "commitDraining 2 must succeed: ${cutover2.result}",
            cutover2.result is CutoverCommit.Success,
        )
        assertEquals(CutoverMode.DRAINING, coordinator.getMode())

        val drained = coordinator.drain(GateBackedInspector, NOW)
        assertTrue("drain must succeed: ${drained.error}", drained.ok)
        assertTrue(coordinator.commitWorkerOnly(NOW) is CutoverCommit.Success)
        assertEquals(CutoverMode.WORKER_ONLY, coordinator.getMode())

        stopFlushLoops(loops2, flushThreads2, stop2)

        // --- Assertions over both attempts. ---
        val posts = transport.posts
        assertTrue(
            "every POST must observe LEGACY_ONLY at its start",
            posts.all { it.modeAtStart == CutoverMode.LEGACY_ONLY },
        )
        // No POST may start inside either DRAINING window: [commit 1,
        // abort] or [commit 2, end of test].
        assertTrue(
            "no POST may start while DRAINING (attempt 1)",
            posts.none {
                it.startedNanos >= cutover1.commitReturnNanos &&
                    it.startedNanos <= abortReturnNanos
            },
        )
        assertTrue(
            "no POST may start after the second DRAINING commit",
            posts.all { it.startedNanos < cutover2.commitReturnNanos },
        )
        // ...but the sender DID resume between the abort and commit 2.
        assertTrue(
            "at least one POST must start after the abort (sender resumed)",
            posts.any {
                it.startedNanos > abortReturnNanos &&
                    it.startedNanos < cutover2.commitReturnNanos
            },
        )

        // Conservation + exactly one owner across BOTH batches.
        val allTags = batch1 + batch2
        val qkeyByDedupe = allTags.associate { dedupeKey(it) to chainQkey(it, ROUTES) }
        val matchedByQkey = posts.filter { it.outcome == PostOutcome.MATCHED }
            .groupBy { qkeyByDedupe.getValue(it.dedupeKey) }
        assertTrue(
            "each settled payment must settle exactly once",
            matchedByQkey.values.all { it.size == 1 },
        )
        assertConservationAndOneOwner(allTags, matchedByQkey.keys)
    }

    companion object {
        /** File-backed Room database for these tests (P1-1, never in-memory). */
        const val TEST_DB_NAME: String = "paylist-r06-test.db"

        const val NOW: Long = 1_795_000_000_000L

        const val BOOT_A: String = "boot-r06-a"

        val ROUTES: List<String> = listOf("route-1", "route-2")
    }
}
