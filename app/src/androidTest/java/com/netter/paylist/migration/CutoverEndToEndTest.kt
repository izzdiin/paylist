package com.netter.paylist.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.FakeTransport
import com.netter.paylist.ModeGate
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
import java.util.concurrent.TimeUnit

/**
 * End-to-end cutover tests (ChatGPT Stage C audit, finding 2): the
 * full cutover path — [CutoverPrivacyPreflight] → [CutoverCoordinator]
 * → Room import → sweep — against REAL file-backed Room and the REAL
 * [Store] queue, with the REAL legacy [Sender] where the mode gate is
 * under test.
 *
 * The unit `CutoverTest` already proves the same crash windows
 * (F27/F28/F38/T10) against fake DAOs; these tests prove them on the
 * real stack, where the SQL, the `SharedPreferences` write, and the
 * sender's mode gate are the production code, not a mirror of it.
 *
 * Two honest limits, stated here and not hidden:
 * - `PaylistApplication.runCutover` itself is gated by
 *   `CAPTURE_TO_ROOM_READY = false` until Stage D, so these tests
 *   drive the coordinator + preflight directly, in the SAME order
 *   `runCutover` performs them (requestDrain → sender lock →
 *   preflight → snapshot/save/verify → commitDraining → drain →
 *   commitWorkerOnly). The gate is asserted still closed below.
 * - Room here is FILE-backed (P1-1): an in-memory database dies
 *   with the process, so a "crash" rebuilt over it proves nothing
 *   about durability. A "crash" in this class is still a dropped
 *   coordinator rebuilt over the same durable state (the
 *   `CutoverTest.restart()` pattern) — a fresh process is not
 *   killed inside one test method. The REAL process-death
 *   coverage (kill + relaunch + reconcile, R03/R04/R05) lives in
 *   ProcessDeathPhaseA/B, run by scripts/process_death_tests.sh.
 *
 * EXECUTION NOTE: these tests need a device or an emulator. They
 * COMPILE on laptopgrt2 (`assembleDebugAndroidTest`) but cannot
 * execute there — there is no device/emulator on that box. Do not
 * report them as "passing" from a compile-only gate.
 */
@RunWith(AndroidJUnit4::class)
class CutoverEndToEndTest {

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
        // File-backed Room (P1-1): an in-memory database dies with the
        // process, so a "crash" rebuilt over it proves nothing about
        // durability. The file is deleted first so every test starts
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

    /** A queue source that fails every durable write while armed. */
    private class FailingQueueSource(
        private val delegate: LegacyQueueSnapshotSource,
        var failWrites: Boolean = false,
    ) : LegacyQueueSnapshotSource {
        override fun queueSnapshot(): List<JSONObject> = delegate.queueSnapshot()
        override fun saveQueueSync(items: List<JSONObject>): Boolean =
            if (failWrites) false else delegate.saveQueueSync(items)
    }

    // =====================================================================
    // The golden path
    // =====================================================================

    @Test
    fun happyPathCutover_endToEnd() = runBlocking {
        setBootId(BOOT_A)
        seedQueue(
            listOf(
                chainQueueItem(paymentPayload("e2e-a"), ROUTES),
                chainQueueItem(paymentPayload("e2e-b"), ROUTES),
                // Bare code in its own clause: ambiguous, quarantined by the gate.
                chainQueueItem(
                    paymentPayload("e2e-amb", "Pembayaran Rp100.800 diterima. 123456"),
                    ROUTES,
                ),
            ),
        )
        val coordinator = newCoordinator(storeQueueSource())

        // The runCutover order: requestDrain → lock → preflight →
        // snapshot/save/verify → commitDraining → drain → commitWorkerOnly.
        val requested = coordinator.requestDrain(NOW)
        assertTrue("requestDrain must succeed: $requested", requested is CutoverCommit.Success)
        withSenderLock {
            val preflight = CutoverPrivacyPreflight().run(storeQueueSource(), NOW)
            assertTrue("preflight must succeed: ${preflight.reason}", preflight.ok)
            val snapshot = store.queueSnapshot()
            assertTrue(store.saveQueueSync(snapshot))
            assertEquals(snapshot.size, store.queueSnapshot().size)
            val committed = coordinator.commitDraining(NOW)
            assertTrue("commitDraining must succeed: $committed", committed is CutoverCommit.Success)
        }
        val drained = coordinator.drain(GateBackedInspector, NOW)
        assertTrue("drain must succeed: ${drained.error}", drained.ok)
        assertEquals(2, drained.imported)
        assertEquals(1, drained.quarantined)
        assertEquals(2, drained.removed)
        val workerOnly = coordinator.commitWorkerOnly(NOW)
        assertTrue("commitWorkerOnly must succeed: $workerOnly", workerOnly is CutoverCommit.Success)

        assertEquals(CutoverMode.WORKER_ONLY, coordinator.getMode())
        // Only the quarantined item stays in the queue (the holding pen).
        val remaining = store.queueSnapshot()
        assertEquals(1, remaining.size)
        assertTrue(remaining.single().optBoolean(LegacyQueuePrivacyGate.KEY_QUARANTINE))
        // Room holds exactly the two importable payments, claimable by the worker.
        assertEquals(2, db.eventDao().count())
        for (tag in listOf("e2e-a", "e2e-b")) {
            val delivery = db.deliveryDao().getByLegacyQkey(chainQkey(tag, ROUTES))
            assertNotNull("Room delivery must exist for $tag", delivery)
            assertEquals(DeliveryState.PENDING, delivery!!.state)
        }
        assertNull(
            "the quarantined item must never reach Room",
            db.deliveryDao().getByLegacyQkey(chainQkey("e2e-amb", ROUTES)),
        )
        assertEquals(2, db.deliveryDao().claimBatch(10, NOW, LEASE_MS).size)
    }

    @Test
    fun cutoverGateStaysClosed_untilStageD() {
        // These tests drive the coordinator directly; the production
        // entry point must stay gated until Stage D wires capture.
        assertFalse(
            "CAPTURE_TO_ROOM_READY must stay false until Stage D",
            PaylistApplication.CAPTURE_TO_ROOM_READY,
        )
    }

    // =====================================================================
    // Preflight failure → abort, queue intact
    // =====================================================================

    @Test
    fun preflightFailureAbortsCutover_queueIntact() = runBlocking {
        setBootId(BOOT_A)
        val clean = chainQueueItem(paymentPayload("e2e-clean"), ROUTES)
        val corrupt = JSONObject().apply { put("qkey", "corrupt|chain|route-1") }
        seedQueue(listOf(clean, corrupt))
        val coordinator = newCoordinator(storeQueueSource())

        assertTrue(coordinator.requestDrain(NOW) is CutoverCommit.Success)
        withSenderLock {
            val preflight = CutoverPrivacyPreflight().run(storeQueueSource(), NOW)
            assertFalse("preflight must fail on a corrupt queue item", preflight.ok)
            assertEquals("corrupt_queue_items", preflight.reason)
        }
        val aborted = coordinator.abortCutover(NOW)
        assertTrue("abort must succeed: $aborted", aborted is CutoverCommit.Success)
        assertEquals(CutoverMode.LEGACY_ONLY, coordinator.getMode())
        assertEquals(DrainIntent.ABORTED, coordinator.getDrainIntent())

        // The queue is intact: same items, same order, nothing lost or added.
        val after = store.queueSnapshot()
        assertEquals(2, after.size)
        assertEquals(
            listOf(clean.optString("qkey"), "corrupt|chain|route-1"),
            after.map { it.optString("qkey") },
        )
        // The corrupt item was left alone (no markers); the clean item's
        // durable markers are per-item progress, not queue damage.
        assertFalse(after[1].optBoolean(LegacyQueuePrivacyGate.KEY_REPROCESSED))
        assertTrue(after[0].optBoolean(LegacyQueuePrivacyGate.KEY_REPROCESSED))
        // Nothing reached Room.
        assertEquals(0, db.eventDao().count())
    }

    @Test
    fun preflightWriteFailureAbortsCutover_queueByteIdentical_senderResumes() = runBlocking {
        setBootId(BOOT_A)
        val item = chainQueueItem(paymentPayload("e2e-writefail"), ROUTES)
        seedQueue(listOf(item))
        val before = store.queueSnapshot().single().toString()
        val failing = FailingQueueSource(storeQueueSource(), failWrites = true)
        val coordinator = newCoordinator(storeQueueSource())

        assertTrue(coordinator.requestDrain(NOW) is CutoverCommit.Success)
        val preflight = CutoverPrivacyPreflight().run(failing, NOW)
        assertFalse("preflight must fail when the durable write fails", preflight.ok)
        assertEquals("privacy_write_failed", preflight.reason)
        assertTrue(coordinator.abortCutover(NOW) is CutoverCommit.Success)
        assertEquals(CutoverMode.LEGACY_ONLY, coordinator.getMode())
        assertEquals(DrainIntent.ABORTED, coordinator.getDrainIntent())
        // Byte-identical: the failed write changed nothing.
        assertEquals(before, store.queueSnapshot().single().toString())

        // Row 5: the legacy sender resumes normal claiming after the abort.
        val transport = FakeTransport()
        val sender = Sender(
            ctx, ModeGate.ALWAYS_LEGACY,
            ToggleSecrets(SecretResult.Present("s3cr3t")), transport,
        )
        sender.flush()
        assertEquals(1, transport.requests.size)
        assertTrue("the item must deliver after the abort", store.queueSnapshot().isEmpty())
    }

    @Test
    fun abortThenRetryCutover_succeedsWithoutDuplicatesOrLoss() = runBlocking {
        setBootId(BOOT_A)
        seedQueue(
            listOf(
                chainQueueItem(paymentPayload("e2e-retry1"), ROUTES),
                chainQueueItem(paymentPayload("e2e-retry2"), ROUTES),
            ),
        )
        val coordinator = newCoordinator(storeQueueSource())

        // Attempt 1: the preflight write fails → abort (row 5:
        // LEGACY_ONLY / ABORTED), queue untouched.
        val failing = FailingQueueSource(storeQueueSource(), failWrites = true)
        assertTrue(coordinator.requestDrain(NOW) is CutoverCommit.Success)
        assertEquals(1L, coordinator.getDrainGeneration())
        val failed = CutoverPrivacyPreflight().run(failing, NOW)
        assertFalse("attempt 1 preflight must fail on the write failure", failed.ok)
        assertEquals("privacy_write_failed", failed.reason)
        assertTrue(coordinator.abortCutover(NOW) is CutoverCommit.Success)
        assertEquals(CutoverMode.LEGACY_ONLY, coordinator.getMode())
        assertEquals(DrainIntent.ABORTED, coordinator.getDrainIntent())
        assertEquals(2, store.queueSnapshot().size)

        // Attempt 2: the next cutover attempt (design §7.3A(h)) starts
        // from ABORTED with a fresh generation — before the fix this
        // requestDrain was rejected and no retry was possible.
        val retried = coordinator.requestDrain(NOW)
        assertTrue("requestDrain after abort must succeed: $retried", retried is CutoverCommit.Success)
        assertEquals(2L, coordinator.getDrainGeneration())
        assertEquals(DrainIntent.PENDING, coordinator.getDrainIntent())
        withSenderLock {
            val preflight = CutoverPrivacyPreflight().run(storeQueueSource(), NOW)
            assertTrue("attempt 2 preflight must succeed: ${preflight.reason}", preflight.ok)
            val committed = coordinator.commitDraining(NOW)
            assertTrue("commitDraining must succeed on retry: $committed", committed is CutoverCommit.Success)
        }
        val drained = coordinator.drain(GateBackedInspector, NOW)
        assertTrue("drain must succeed: ${drained.error}", drained.ok)
        assertEquals(2, drained.imported)
        assertEquals(2, drained.removed)
        assertTrue(coordinator.commitWorkerOnly(NOW) is CutoverCommit.Success)
        assertEquals(CutoverMode.WORKER_ONLY, coordinator.getMode())
        assertTrue(store.queueSnapshot().isEmpty())
        assertEquals(2, db.eventDao().count())
        assertEquals(2, db.deliveryDao().claimBatch(10, NOW, LEASE_MS).size)
    }

    // =====================================================================
    // Crash windows
    // =====================================================================

    @Test
    fun crashAfterRequestDrain_resumesFromPendingIntent() = runBlocking {
        setBootId(BOOT_A)
        seedQueue(
            listOf(
                chainQueueItem(paymentPayload("e2e-crash1"), ROUTES),
                chainQueueItem(paymentPayload("e2e-crash2"), ROUTES),
            ),
        )
        val coordinator = newCoordinator(storeQueueSource())
        assertTrue(coordinator.requestDrain(NOW) is CutoverCommit.Success)
        assertTrue(
            "preflight must succeed before the crash",
            CutoverPrivacyPreflight().run(storeQueueSource(), NOW).ok,
        )

        // Crash: the process dies before commitDraining. Restart as a
        // new boot over the same durable state (real Room + real Store).
        val restarted = newCoordinator(storeQueueSource())
        setBootId(BOOT_B)
        assertEquals(CutoverMode.LEGACY_ONLY, restarted.getMode())
        assertEquals(DrainIntent.PENDING, restarted.getDrainIntent())
        // The preflight markers survived the crash (durable in the queue).
        assertTrue(
            "preflight markers must survive the crash",
            store.queueSnapshot().all {
                it.optBoolean(LegacyQueuePrivacyGate.KEY_REPROCESSED)
            },
        )

        // Resume from row 2: no new requestDrain — the intent is already PENDING.
        val committed = restarted.commitDraining(NOW)
        assertTrue("commitDraining must succeed after restart: $committed", committed is CutoverCommit.Success)
        val drained = restarted.drain(GateBackedInspector, NOW)
        assertTrue("drain must succeed: ${drained.error}", drained.ok)
        assertEquals(2, drained.imported)
        assertTrue(restarted.commitWorkerOnly(NOW) is CutoverCommit.Success)
        assertEquals(CutoverMode.WORKER_ONLY, restarted.getMode())
        assertTrue(store.queueSnapshot().isEmpty())
        assertEquals(2, db.eventDao().count())
        assertEquals(2, db.deliveryDao().claimBatch(10, NOW, LEASE_MS).size)
    }

    @Test
    fun crashMidDrain_retryCompletesWithoutDuplicatesOrLoss() = runBlocking {
        setBootId(BOOT_A)
        seedQueue(
            listOf(
                chainQueueItem(paymentPayload("e2e-drain1"), ROUTES),
                chainQueueItem(paymentPayload("e2e-drain2"), ROUTES),
            ),
        )
        val failing = FailingQueueSource(storeQueueSource(), failWrites = true)
        val coordinator = newCoordinator(failing)
        assertTrue(coordinator.requestDrain(NOW) is CutoverCommit.Success)
        assertTrue(CutoverPrivacyPreflight().run(storeQueueSource(), NOW).ok)
        assertTrue(coordinator.commitDraining(NOW) is CutoverCommit.Success)

        // The drain imports into Room, then the sweep's queue write
        // fails: a crash window between import and sweep completion.
        val crashed = coordinator.drain(GateBackedInspector, NOW)
        assertFalse("drain must fail when the sweep cannot write the queue", crashed.ok)
        assertEquals(2, crashed.imported)
        assertEquals(0, crashed.removed)
        assertTrue(
            "the failure must name the queue write: ${crashed.error}",
            crashed.error!!.contains("saveQueueSync"),
        )
        assertEquals(CutoverMode.DRAINING, coordinator.getMode())
        // The import already landed in Room; the queue is untouched.
        assertEquals(2, db.eventDao().count())
        assertEquals(2, store.queueSnapshot().size)

        // Retry with a healthy queue source: the import is idempotent,
        // the sweep resumes from its durable intents — no duplicates, no loss.
        failing.failWrites = false
        val retried = coordinator.drain(GateBackedInspector, NOW)
        assertTrue("retry drain must succeed: ${retried.error}", retried.ok)
        assertEquals(0, retried.imported)
        assertEquals(2, retried.removed)
        assertTrue(store.queueSnapshot().isEmpty())
        assertEquals(2, db.eventDao().count())
        assertTrue(coordinator.commitWorkerOnly(NOW) is CutoverCommit.Success)
        assertEquals(2, db.deliveryDao().claimBatch(10, NOW, LEASE_MS).size)
    }

    @Test
    fun crashAfterDrain_commitWorkerOnlyOnRestart() = runBlocking {
        setBootId(BOOT_A)
        seedQueue(
            listOf(
                chainQueueItem(paymentPayload("e2e-post1"), ROUTES),
                chainQueueItem(paymentPayload("e2e-post2"), ROUTES),
            ),
        )
        val coordinator = newCoordinator(storeQueueSource())
        assertTrue(coordinator.requestDrain(NOW) is CutoverCommit.Success)
        assertTrue(CutoverPrivacyPreflight().run(storeQueueSource(), NOW).ok)
        assertTrue(coordinator.commitDraining(NOW) is CutoverCommit.Success)
        val drained = coordinator.drain(GateBackedInspector, NOW)
        assertTrue(drained.ok)
        assertEquals(2, drained.removed)

        // Crash after a verified drain, before the WORKER_ONLY commit.
        val restarted = newCoordinator(storeQueueSource())
        setBootId(BOOT_B)
        assertEquals(CutoverMode.DRAINING, restarted.getMode())
        val marker = restarted.getPhaseMarker()
        assertNotNull("the phase marker must survive the crash", marker)
        assertEquals("drained", marker!!.phase)
        assertTrue(marker.verified)

        assertTrue(restarted.commitWorkerOnly(NOW) is CutoverCommit.Success)
        assertEquals(CutoverMode.WORKER_ONLY, restarted.getMode())
        assertTrue(store.queueSnapshot().isEmpty())
        assertEquals(2, db.deliveryDao().claimBatch(10, NOW, LEASE_MS).size)
        // A drain re-run after completion is refused (mode check), never double-applied.
        val rerun = restarted.drain(GateBackedInspector, NOW)
        assertFalse("drain must refuse after WORKER_ONLY", rerun.ok)
    }

    // =====================================================================
    // The sender + coordinator seam
    // =====================================================================

    @Test
    fun senderStopsPostingAfterDraining_resumesAfterAbort() = runBlocking {
        setBootId(BOOT_A)
        val payload = paymentPayload("e2e-gate")
        seedQueue(listOf(chainQueueItem(payload, ROUTES)))
        val coordinator = newCoordinator(storeQueueSource())
        val transport = FakeTransport()
        // The production mode gate: the sender reads the coordinator's mode.
        val sender = Sender(
            ctx,
            ModeGate { runBlocking { coordinator.getMode() } == CutoverMode.LEGACY_ONLY },
            ToggleSecrets(SecretResult.Present("s3cr3t")),
            transport,
        )

        assertTrue(coordinator.requestDrain(NOW) is CutoverCommit.Success)
        assertTrue(coordinator.commitDraining(NOW) is CutoverCommit.Success)

        // Past the DRAINING commit, no POST can start; the item stays queued.
        sender.flush()
        assertTrue("no POST may start after DRAINING", transport.requests.isEmpty())
        assertEquals(1, store.queueSnapshot().size)
        assertEquals(
            chainQkey("e2e-gate", ROUTES),
            store.queueSnapshot().single().optString("qkey"),
        )

        // Abort back to LEGACY_ONLY: the gate re-opens and the item delivers.
        assertTrue(coordinator.abortCutover(NOW) is CutoverCommit.Success)
        sender.flush()
        assertEquals(1, transport.requests.size)
        assertTrue(store.queueSnapshot().isEmpty())
    }

    companion object {
        /** File-backed Room database for these tests (P1-1, never in-memory). */
        const val TEST_DB_NAME: String = "paylist-e2e-test.db"

        const val NOW: Long = 1_795_000_000_000L

        /** Lease length, design §5.1: 5 min, no renewal. */
        const val LEASE_MS: Long = 300_000L

        const val BOOT_A: String = "boot-e2e-a"
        const val BOOT_B: String = "boot-e2e-b"

        val ROUTES: List<String> = listOf("route-1", "route-2")
    }
}
