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
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.PaylistDatabase
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.delivery.DeliveryProcessor
import com.netter.paylist.delivery.StoreRouteSource
import com.netter.paylist.delivery.TransportClient
import com.netter.paylist.delivery.TransportRequest
import com.netter.paylist.delivery.TransportResult
import com.netter.paylist.paymentPayload
import com.netter.paylist.privacy.CutoverPrivacyPreflight
import com.netter.paylist.privacy.GateBackedInspector
import com.netter.paylist.privacy.LegacyQueuePrivacyGate
import com.netter.paylist.security.SecretResult
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
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R08 — capture during cutover (ChatGPT re-review #5, review 6011401446):
 * "force new payment notification at each boundary; prove exactly one
 * durable owner (legacy queue vs Room) and eventual claim/delivery, not
 * just import count."
 *
 * One new payment is forced through the REAL capture enqueue path at
 * each cutover boundary, and each boundary proves, per `qkey`:
 * exactly one durable owner (legacy queue XOR Room delivery) AND
 * eventual claim/delivery through the real worker path
 * ([DeliveryProcessor.processDueWork] with a fake transport: exactly
 * one POST + `ACKED`) — never just an import count.
 *
 * Boundaries:
 * - B1: capture BEFORE `requestDrain` → imported by the drain, delivered
 *   by the worker.
 * - B2: capture AFTER `requestDrain`, BEFORE `commitDraining` (intent
 *   `PENDING`) → imported, delivered.
 * - B3a: capture AFTER `commitDraining`, BEFORE the drain snapshot →
 *   STRANDED (see below).
 * - B3b: capture AFTER the drain snapshot, BEFORE `commitWorkerOnly` →
 *   STRANDED (see below).
 * - B3c: capture AFTER `commitWorkerOnly` → STRANDED (see below).
 *
 * The B3a/B3b/B3c tests assert the ACTUAL current behaviour — the
 * payment stays in the legacy queue with no Room delivery and no
 * worker claim — so the stranding is proven by a green test, not by
 * reading the code. The fix (a post-commit re-drain) flips these
 * three tests to delivery assertions in a follow-up commit.
 *
 * Why B3a/B3b/B3c strand, as the tests prove it:
 * - B3a: the capture lands after the privacy preflight, so the
 *   fail-closed [GateBackedInspector] quarantines it instead of
 *   importing it (`drained.quarantined == 1`, `imported == 0`).
 * - B3b: the capture lands after the drain snapshot, so the drain
 *   never sees it (`imported` counts the anchor only).
 * - B3c: the capture lands after `WORKER_ONLY`, when the legacy
 *   sender is gated off and the worker only claims Room rows.
 * In all three the legacy sender records zero POST attempts for the
 * tag (the mode gate is closed) and the worker records zero POSTs
 * (no Room delivery exists) — one owner (legacy only), no delivery.
 *
 * Capture path: the test drives [Sender.enqueueChain] on a REAL
 * [Sender] — the exact method `PaylistListenerService` calls — with
 * a listener-shaped payload. Two honest limits, stated here:
 * - The listener service itself cannot be driven in this harness
 *   (it needs a system-bound service + a real `StatusBarNotification`);
 *   the enqueue itself — the queue write + the `qkey` dedupe + the
 *   auto-flush — is the production code under test.
 * - `appSender()` is NOT used: it binds to the application's own
 *   coordinator/database, not the test's file-backed one. The test
 *   [Sender] uses the production gate factory over the test
 *   coordinator (the R06 pattern), so the gate under test is the
 *   production gate.
 * - Delivery is driven through [DeliveryProcessor.processDueWork],
 *   not a WorkManager [com.netter.paylist.delivery.SyncWorker]:
 *   the worker is a thin shell around the processor (its KDoc says
 *   so), and the processor is the real claim path. Note
 *   [CutoverCoordinator.admitAttempt] has NO production caller in
 *   Stage C — the worker claims via `claimBatch` + attempt rows —
 *   so the test proves the path production actually takes.
 *
 * Determinism: no timing assumptions. B3b injects inside the drain's
 * inspector, which runs during `partition()` — strictly after the
 * snapshot — so the capture is deterministically inside the window.
 * Every wait has a bounded timeout that fails loudly instead of
 * hanging CI.
 *
 * EXECUTION NOTE: these tests need a device or an emulator. They
 * COMPILE on laptopgrt2 (`assembleDebugAndroidTest`) but cannot
 * execute there — there is no device/emulator on that box. Do not
 * report them as "passing" from a compile-only gate.
 */
@RunWith(AndroidJUnit4::class)
class CaptureDuringCutoverTest {

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

    /** The production worker processor over the test's file-backed Room. */
    private fun newWorkerProcessor(transport: TransportClient): DeliveryProcessor =
        DeliveryProcessor(
            deliveryDao = db.deliveryDao(),
            eventDao = db.eventDao(),
            attemptDao = db.attemptDao(),
            transactions = db.transactionRunner(),
            routes = StoreRouteSource(store),
            secrets = ToggleSecrets(SecretResult.Present("s3cr3t")),
            transport = transport,
        )

    private fun waitUntil(timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("timed out waiting for condition", condition())
    }

    /**
     * A fake legacy transport that records every POST attempt (its
     * `dedupe_key` and the coordinator mode observed at the POST) and
     * always answers `NetworkError`: a payment attempted through it
     * can never settle via the legacy sender, so it must survive in
     * the queue until the drain imports it.
     */
    private class RecordingLegacyTransport(
        private val coordinator: CutoverCoordinator,
    ) : TransportClient {

        data class Attempt(
            val dedupeKey: String,
            val modeAtStart: CutoverMode,
        )

        private val _attempts = Collections.synchronizedList(mutableListOf<Attempt>())

        /** All recorded attempts, in start order (a defensive copy). */
        val attempts: List<Attempt> get() = synchronized(_attempts) { _attempts.toList() }

        fun attemptsFor(dedupeKey: String): List<Attempt> =
            attempts.filter { it.dedupeKey == dedupeKey }

        override suspend fun post(
            request: TransportRequest,
            onSocketOpen: suspend () -> Boolean,
        ): TransportResult {
            if (!onSocketOpen()) return TransportResult.CheckpointFailed
            val dedupeKey = try {
                JSONObject(request.body).optString("dedupe_key")
            } catch (_: Exception) {
                ""
            }
            _attempts.add(Attempt(dedupeKey, coordinator.getMode()))
            return TransportResult.NetworkError("io")
        }
    }

    /**
     * Wraps an inspector: runs [onFirstInspect] once, on the first
     * item inspected — the B3b injection seam (the drain calls the
     * inspector during `partition()`, strictly after the snapshot).
     */
    private class InjectingInspector(
        private val delegate: LegacyQueueInspector,
        private val onFirstInspect: () -> Unit,
    ) : LegacyQueueInspector {
        private val fired = AtomicBoolean(false)

        override fun inspect(item: LegacyQueueItem): Inspection {
            if (fired.compareAndSet(false, true)) onFirstInspect()
            return delegate.inspect(item)
        }
    }

    /**
     * The sender lock around the pre-commit phase, as `runCutover`
     * takes it — with a 5 s test timeout instead of the production
     * 45 s (no contention is expected; fail fast if there is).
     */
    private suspend fun <T> withSenderLock(block: suspend () -> T): T {
        val locked = try {
            SenderLock.lock.tryLock(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        assertTrue("sender lock must be acquirable in tests", locked)
        try {
            return block()
        } finally {
            SenderLock.lock.unlock()
        }
    }

    /** The `dedupe_key` for a tag (via the same builder as the payload). */
    private fun dedupeKey(tag: String): String =
        paymentPayload(tag).optString("dedupe_key")

    /** Worker POSTs for one tag (parsed from the wire body). */
    private fun workerPostsFor(tag: String, transport: FakeTransport): List<TransportRequest> {
        val dedupe = dedupeKey(tag)
        return transport.requests.filter {
            try {
                JSONObject(it.body).optString("dedupe_key") == dedupe
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun requestDrainOrFail(coordinator: CutoverCoordinator) {
        val requested = coordinator.requestDrain(NOW)
        assertTrue("requestDrain must succeed: $requested", requested is CutoverCommit.Success)
    }

    /**
     * The `runCutover` pre-commit phase, in production order: privacy
     * preflight → durable flush + verify (R07 gate) → commit
     * `DRAINING`, all under the sender lock.
     */
    private suspend fun preflightAndCommitDraining(coordinator: CutoverCoordinator) {
        withSenderLock {
            val preflight = CutoverPrivacyPreflight().run(storeQueueSource(), NOW)
            assertTrue("preflight must succeed: ${preflight.reason}", preflight.ok)
            val snapshot = store.queueSnapshot()
            val flushed = CutoverFlushGate.flushOrAbort(
                coordinator, storeQueueSource(), snapshot, NOW,
            )
            assertTrue(
                "flush gate must verify: ${(flushed as? FlushResult.Failed)?.reason}",
                flushed is FlushResult.Verified,
            )
            val committed = coordinator.commitDraining(NOW)
            assertTrue("commitDraining must succeed: $committed", committed is CutoverCommit.Success)
        }
    }

    private suspend fun drainAndCommitWorkerOnly(
        coordinator: CutoverCoordinator,
        inspector: LegacyQueueInspector = GateBackedInspector,
    ): DrainResult {
        val drained = coordinator.drain(inspector, NOW)
        assertTrue("drain must succeed: ${drained.error}", drained.ok)
        val workerOnly = coordinator.commitWorkerOnly(NOW)
        assertTrue("commitWorkerOnly must succeed: $workerOnly", workerOnly is CutoverCommit.Success)
        assertEquals(CutoverMode.WORKER_ONLY, coordinator.getMode())
        return drained
    }

    /**
     * Asserts eventual claim/delivery for [tag] through the worker:
     * exactly one Room delivery (`ACKED`), exactly one Room event,
     * exactly one worker POST, and the legacy queue clean of the
     * `qkey` — one durable owner (Room), delivered, no duplicate.
     */
    private fun assertDeliveredByWorker(tag: String, workerTransport: FakeTransport) {
        val qkey = chainQkey(tag, ROUTES)
        val delivery = runBlocking { db.deliveryDao().getByLegacyQkey(qkey) }
        assertNotNull("Room delivery must exist for $tag", delivery)
        assertEquals(DeliveryState.ACKED, delivery!!.state)
        assertNotNull(
            "Room event must exist for $tag",
            runBlocking { db.eventDao().getByDedupeKey(dedupeKey(tag)) },
        )
        assertEquals(
            "the worker must POST exactly once for $tag",
            1,
            workerPostsFor(tag, workerTransport).size,
        )
        assertTrue(
            "the legacy queue must be clean of $tag after delivery",
            store.queueSnapshot().none { it.optString("qkey") == qkey },
        )
    }

    /**
     * Asserts the stranded state for [tag]: the payment sits in the
     * legacy queue (its only owner), with no Room delivery, no Room
     * event, and no worker POST — conservation holds (nothing lost,
     * nothing duplicated) but there is no eventual delivery.
     */
    private fun assertStrandedInLegacyQueue(tag: String, workerTransport: FakeTransport) {
        val qkey = chainQkey(tag, ROUTES)
        assertEquals(
            "the payment must still sit in the legacy queue (its only owner)",
            1,
            store.queueSnapshot().count { it.optString("qkey") == qkey },
        )
        assertNull(
            "no Room delivery may exist for a stranded payment",
            runBlocking { db.deliveryDao().getByLegacyQkey(qkey) },
        )
        assertNull(
            "no Room event may exist for a stranded payment",
            runBlocking { db.eventDao().getByDedupeKey(dedupeKey(tag)) },
        )
        assertTrue(
            "the worker must never POST for a payment it cannot see",
            workerPostsFor(tag, workerTransport).isEmpty(),
        )
    }

    // =====================================================================
    // B1: capture BEFORE requestDrain → imported, delivered
    // =====================================================================

    @Test
    fun captureBeforeRequestDrain_importedAndDeliveredByWorker() = runBlocking {
        setBootId(BOOT_A)
        val tag = "r08-b1"
        val coordinator = newCoordinator(storeQueueSource())
        val legacyTransport = RecordingLegacyTransport(coordinator)
        val sender = newSender(coordinator, legacyTransport)
        val workerTransport = FakeTransport()
        val processor = newWorkerProcessor(workerTransport)

        // B1: the capture lands before the drain is even requested —
        // through the real enqueue path, with the real auto-flush.
        sender.enqueueChain(paymentPayload(tag), ROUTES)
        waitUntil { legacyTransport.attemptsFor(dedupeKey(tag)).size == 1 }

        requestDrainOrFail(coordinator)
        preflightAndCommitDraining(coordinator)
        val drained = drainAndCommitWorkerOnly(coordinator)
        assertEquals(1, drained.imported)
        assertEquals(1, drained.removed)

        // The legacy sender attempted exactly once, under LEGACY_ONLY,
        // and never settled (NetworkError) — the payment survived to
        // the drain instead of settling before it.
        val attempts = legacyTransport.attemptsFor(dedupeKey(tag))
        assertEquals(1, attempts.size)
        assertEquals(CutoverMode.LEGACY_ONLY, attempts.single().modeAtStart)

        // Eventual claim/delivery through the real worker path.
        assertFalse("no due work must remain after the pass", processor.processDueWork())
        assertDeliveredByWorker(tag, workerTransport)
        assertEquals(1, db.eventDao().count())
    }

    // =====================================================================
    // B2: capture AFTER requestDrain, BEFORE commitDraining → imported, delivered
    // =====================================================================

    @Test
    fun captureAfterRequestDrain_importedAndDeliveredByWorker() = runBlocking {
        setBootId(BOOT_A)
        val tag = "r08-b2"
        val coordinator = newCoordinator(storeQueueSource())
        val legacyTransport = RecordingLegacyTransport(coordinator)
        val sender = newSender(coordinator, legacyTransport)
        val workerTransport = FakeTransport()
        val processor = newWorkerProcessor(workerTransport)

        requestDrainOrFail(coordinator)
        assertEquals(DrainIntent.PENDING, coordinator.getDrainIntent())

        // B2: the capture lands while the drain intent is PENDING —
        // before the preflight, so it is marked and importable.
        sender.enqueueChain(paymentPayload(tag), ROUTES)
        waitUntil { legacyTransport.attemptsFor(dedupeKey(tag)).size == 1 }

        preflightAndCommitDraining(coordinator)
        val drained = drainAndCommitWorkerOnly(coordinator)
        assertEquals(1, drained.imported)
        assertEquals(1, drained.removed)

        val attempts = legacyTransport.attemptsFor(dedupeKey(tag))
        assertEquals(1, attempts.size)
        assertEquals(CutoverMode.LEGACY_ONLY, attempts.single().modeAtStart)

        assertFalse("no due work must remain after the pass", processor.processDueWork())
        assertDeliveredByWorker(tag, workerTransport)
        assertEquals(1, db.eventDao().count())
    }

    // =====================================================================
    // B3a: capture AFTER commitDraining, BEFORE the snapshot → STRANDED
    // =====================================================================

    @Test
    fun captureAfterCommitDraining_strandedWithoutRedrain() = runBlocking {
        setBootId(BOOT_A)
        val tag = "r08-b3a"
        val coordinator = newCoordinator(storeQueueSource())
        val legacyTransport = RecordingLegacyTransport(coordinator)
        val sender = newSender(coordinator, legacyTransport)
        val workerTransport = FakeTransport()
        val processor = newWorkerProcessor(workerTransport)

        requestDrainOrFail(coordinator)
        preflightAndCommitDraining(coordinator)
        assertEquals(CutoverMode.DRAINING, coordinator.getMode())

        // B3a: the capture lands after DRAINING, before the drain
        // snapshot — after the preflight, so it carries no markers.
        sender.enqueueChain(paymentPayload(tag), ROUTES)
        assertEquals(
            "the capture must land in the legacy queue",
            1,
            store.queueSnapshot().count { it.optString("qkey") == chainQkey(tag, ROUTES) },
        )

        val drained = drainAndCommitWorkerOnly(coordinator)
        // The fail-closed P9 inspector quarantines the
        // never-preflighted item instead of importing it — this is
        // the stranding mechanism for B3a.
        assertEquals(0, drained.imported)
        assertEquals(1, drained.quarantined)

        assertFalse(processor.processDueWork())
        assertStrandedInLegacyQueue(tag, workerTransport)
        assertTrue(
            "the legacy sender must never POST after DRAINING",
            legacyTransport.attemptsFor(dedupeKey(tag)).isEmpty(),
        )
        assertEquals(0, db.eventDao().count())
    }

    // =====================================================================
    // B3b: capture AFTER the snapshot, BEFORE commitWorkerOnly → STRANDED
    // =====================================================================

    @Test
    fun captureAfterDrainSnapshot_strandedWithoutRedrain() = runBlocking {
        setBootId(BOOT_A)
        val anchorTag = "r08-b3b-anchor"
        val tag = "r08-b3b"
        seedQueue(listOf(chainQueueItem(paymentPayload(anchorTag), ROUTES)))
        val coordinator = newCoordinator(storeQueueSource())
        val legacyTransport = RecordingLegacyTransport(coordinator)
        val sender = newSender(coordinator, legacyTransport)
        val workerTransport = FakeTransport()
        val processor = newWorkerProcessor(workerTransport)

        requestDrainOrFail(coordinator)
        preflightAndCommitDraining(coordinator)

        // B3b: the capture lands after the drain snapshot, before
        // WORKER_ONLY. The inspector fires during partition() —
        // strictly after the snapshot — so the injection is
        // deterministically inside the window, with no timing
        // assumption. The anchor keeps the snapshot non-empty so the
        // inspector fires at all.
        val snapshotTaken = CountDownLatch(1)
        val injecting = InjectingInspector(GateBackedInspector) {
            snapshotTaken.countDown()
            sender.enqueueChain(paymentPayload(tag), ROUTES)
        }
        val drained = drainAndCommitWorkerOnly(coordinator, injecting)
        assertEquals("the injection must fire during the drain", 0L, snapshotTaken.count)
        assertEquals(1, drained.imported) // the anchor only
        assertEquals(1, drained.removed)
        assertEquals(0, drained.quarantined)

        assertFalse(processor.processDueWork())
        assertDeliveredByWorker(anchorTag, workerTransport)
        assertStrandedInLegacyQueue(tag, workerTransport)
        assertTrue(
            "the legacy sender must never POST after DRAINING",
            legacyTransport.attemptsFor(dedupeKey(tag)).isEmpty(),
        )
        // Diagnostic: the item never went through the privacy gate,
        // so a plain re-drain (without a preflight first) would
        // quarantine it too — the fix must re-run the gate.
        val stranded = store.queueSnapshot()
            .single { it.optString("qkey") == chainQkey(tag, ROUTES) }
        assertFalse(stranded.optBoolean(LegacyQueuePrivacyGate.KEY_REPROCESSED, false))
        assertEquals(1, db.eventDao().count())
    }

    // =====================================================================
    // B3c: capture AFTER commitWorkerOnly → STRANDED
    // =====================================================================

    @Test
    fun captureAfterCommitWorkerOnly_strandedWithoutRedrain() = runBlocking {
        setBootId(BOOT_A)
        val anchorTag = "r08-b3c-anchor"
        val tag = "r08-b3c"
        seedQueue(listOf(chainQueueItem(paymentPayload(anchorTag), ROUTES)))
        val coordinator = newCoordinator(storeQueueSource())
        val legacyTransport = RecordingLegacyTransport(coordinator)
        val sender = newSender(coordinator, legacyTransport)
        val workerTransport = FakeTransport()
        val processor = newWorkerProcessor(workerTransport)

        requestDrainOrFail(coordinator)
        preflightAndCommitDraining(coordinator)
        val drained = drainAndCommitWorkerOnly(coordinator)
        assertEquals(1, drained.imported)
        assertEquals(1, drained.removed)
        assertTrue("the queue must be empty after the drain", store.queueSnapshot().isEmpty())

        // B3c: the capture lands after WORKER_ONLY. Stage C capture
        // still targets the legacy queue; the sender is gated off.
        sender.enqueueChain(paymentPayload(tag), ROUTES)
        assertEquals(
            "the capture must land in the legacy queue",
            1,
            store.queueSnapshot().count { it.optString("qkey") == chainQkey(tag, ROUTES) },
        )

        assertFalse(processor.processDueWork())
        assertDeliveredByWorker(anchorTag, workerTransport)
        assertStrandedInLegacyQueue(tag, workerTransport)
        assertTrue(
            "the legacy sender must never POST after WORKER_ONLY",
            legacyTransport.attemptsFor(dedupeKey(tag)).isEmpty(),
        )
        assertEquals(1, db.eventDao().count())
    }

    companion object {
        /** File-backed Room database for these tests (P1-1, never in-memory). */
        const val TEST_DB_NAME: String = "paylist-r08-test.db"

        const val NOW: Long = 1_795_000_000_000L

        const val BOOT_A: String = "boot-r08-a"

        val ROUTES: List<String> = listOf("route-1", "route-2")
    }
}
