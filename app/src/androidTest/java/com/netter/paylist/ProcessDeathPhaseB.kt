package com.netter.paylist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.migration.CutoverCommit
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.migration.DrainIntent
import com.netter.paylist.privacy.GateBackedInspector
import com.netter.paylist.security.SecretResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase B of the real process-death tests (P1-1, R03/R04/R05): a
 * FRESH process (a separate `am instrument` invocation after PhaseA
 * killed the last one) re-opens the same durable state — the
 * file-backed Room database, the SharedPreferences queue, the server
 * ledger — and asserts the recovery.
 *
 * Each method FIRST requires its PhaseA marker AND a fresh process
 * (PID + process start time differ from the identity PhaseA
 * recorded in the marker): if PhaseA never reached its kill point
 * (or never ran), or if the kill never happened and this invocation
 * reused PhaseA's still-alive process, the method fails loudly
 * instead of asserting against an unrelated state.
 *
 * setUp here deliberately does NOT clear anything — the durable
 * state IS the test input. tearDown cleans up so the next scenario
 * starts fresh.
 *
 * NEVER run under `connectedDebugAndroidTest`: without a preceding
 * PhaseA run every method fails on its missing marker. This class
 * is excluded via `notClass` in build.gradle.kts; run it through
 * scripts/process_death_tests.sh instead.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathPhaseB {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // No clearing: PhaseA's durable state is the input.
    }

    @After
    fun tearDown() {
        ProcessDeathHarness.clearAllDurableState(ctx)
    }

    /**
     * Require PhaseA's marker AND a fresh process. The marker alone
     * proves PhaseA reached the kill point; the identity check proves
     * THIS process is not PhaseA's still-alive process — if the kill
     * never happened and the system reused PhaseA's process for this
     * invocation, the PID + start time match and the test fails
     * loudly instead of asserting recovery against a state no crash
     * ever produced (ChatGPT re-review, PR #4 @ a6bfa1f).
     */
    private fun requireFreshProcess(scenario: String) {
        val recorded = ProcessDeathHarness.readMarkerIdentity(ctx, scenario)
        assertNotNull(
            "PhaseA must have reached the kill point for $scenario (run PhaseA first)",
            recorded,
        )
        val mine = ProcessDeathHarness.currentProcessIdentity()
        // Same process iff the PID matches AND (either start time is
        // unknown, or both start times match).
        val sameProcess = mine.pid == recorded!!.pid &&
            (recorded.startTicks == -1L || mine.startTicks == -1L ||
                mine.startTicks == recorded.startTicks)
        assertFalse(
            "PhaseB runs in PhaseA's process (pid=${mine.pid}) — the kill " +
                "never happened for $scenario; refusing to assert recovery",
            sameProcess,
        )
    }

    /**
     * The r03-style recovery: retry the drain after a crash in the
     * "import done, sweep not started" window. Shared by r03 and the
     * r06 negative control (whose PhaseB must pass on state alone, so
     * the ONLY reason it can fail is the freshness check).
     */
    private suspend fun retryCompletesWithoutDuplicatesOrLoss(tagA: String, tagB: String) {
        val store = Store(ctx)
        val db = ProcessDeathHarness.openTestDb(ctx)
        try {
            ProcessDeathHarness.setBootId(db, ProcessDeathHarness.BOOT_B)
            val coordinator = ProcessDeathHarness.newCoordinator(
                db,
                ProcessDeathHarness.storeQueueSource(store),
            )

            // The crash state, read from durable storage alone:
            // DRAINING, import done, sweep not started, queue intact.
            assertEquals(CutoverMode.DRAINING, coordinator.getMode())
            assertEquals(DrainIntent.QUIESCED, coordinator.getDrainIntent())
            assertEquals(2, db.eventDao().count())
            assertEquals(2, store.queueSnapshot().size)

            // Retry the drain: the import is idempotent (0 new), the
            // sweep resumes and removes both items — no duplicates, no loss.
            val retried = coordinator.drain(GateBackedInspector, ProcessDeathHarness.NOW)
            assertTrue("retry drain must succeed: ${retried.error}", retried.ok)
            assertEquals(0, retried.imported)
            assertEquals(2, retried.removed)
            assertTrue(store.queueSnapshot().isEmpty())
            assertEquals(2, db.eventDao().count())
            // qkeys stable: the same two deliveries, PENDING, claimable.
            for (tag in listOf(tagA, tagB)) {
                val delivery = db.deliveryDao()
                    .getByLegacyQkey(chainQkey(tag, ProcessDeathHarness.ROUTES))
                assertNotNull("Room delivery must exist for $tag", delivery)
                assertEquals(DeliveryState.PENDING, delivery!!.state)
            }
            assertTrue(coordinator.commitWorkerOnly(ProcessDeathHarness.NOW) is CutoverCommit.Success)
            assertEquals(CutoverMode.WORKER_ONLY, coordinator.getMode())
            assertEquals(
                2,
                db.deliveryDao().claimBatch(10, ProcessDeathHarness.NOW, ProcessDeathHarness.LEASE_MS).size,
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun r03RetryCompletesWithoutDuplicatesOrLoss() = runBlocking {
        requireFreshProcess("r03")
        retryCompletesWithoutDuplicatesOrLoss("death-r03-a", "death-r03-b")
    }

    @Test
    fun r06NegativeControlMustFail() = runBlocking {
        // Negative control (see ProcessDeathPhaseA): PhaseA wrote the
        // marker but deliberately did NOT kill its process. This
        // method asserts the same recovery as r03, so the ONLY reason
        // it can fail is the freshness check above (or the runner,
        // which refuses to run PhaseB at all when PhaseA's process is
        // still alive). Run ONLY via
        // scripts/process_death_tests.sh --negative-control.
        requireFreshProcess("r06")
        retryCompletesWithoutDuplicatesOrLoss("death-r06-a", "death-r06-b")
    }

    @Test
    fun r04CommitWorkerOnlyOnRestart() = runBlocking {
        requireFreshProcess("r04")
        val store = Store(ctx)
        val db = ProcessDeathHarness.openTestDb(ctx)
        try {
            ProcessDeathHarness.setBootId(db, ProcessDeathHarness.BOOT_B)
            val coordinator = ProcessDeathHarness.newCoordinator(
                db,
                ProcessDeathHarness.storeQueueSource(store),
            )

            // The crash state: sweep done, WORKER_ONLY not committed.
            assertEquals(CutoverMode.DRAINING, coordinator.getMode())
            val marker = coordinator.getPhaseMarker()
            assertNotNull("the phase marker must survive the crash", marker)
            assertEquals("drained", marker!!.phase)
            assertTrue(marker.verified)
            assertTrue(store.queueSnapshot().isEmpty())
            assertEquals(2, db.eventDao().count())

            assertTrue(coordinator.commitWorkerOnly(ProcessDeathHarness.NOW) is CutoverCommit.Success)
            assertEquals(CutoverMode.WORKER_ONLY, coordinator.getMode())
            assertEquals(
                2,
                db.deliveryDao().claimBatch(10, ProcessDeathHarness.NOW, ProcessDeathHarness.LEASE_MS).size,
            )
            // A drain re-run after completion is refused (mode check),
            // never double-applied.
            val rerun = coordinator.drain(GateBackedInspector, ProcessDeathHarness.NOW)
            assertFalse("drain must refuse after WORKER_ONLY", rerun.ok)
        } finally {
            db.close()
        }
    }

    @Test
    fun r05RetryDeliversExactlyOnce() {
        requireFreshProcess("r05")
        val store = Store(ctx)
        val ledger = ServerLedger.open(ctx)
        val dedupeKey = paymentPayload("death-r05").optString("dedupe_key")

        // The crash state: the server committed the effect (the POST
        // went out), but the client died before persisting anything —
        // the queue still holds the item, untouched (attempts 0).
        assertEquals(1, ledger.counts(dedupeKey).effects)
        assertEquals(1, ledger.counts(dedupeKey).requests)
        val queued = store.queue()
        assertEquals(1, queued.size)
        assertEquals(0, queued.single().optInt("attempts"))
        assertEquals(0, queued.single().optInt("next"))

        // Retry after the restart: the server replays the recorded
        // match — no second effect — and the item delivers.
        val transport = LedgerTransport(ledger, LedgerTransport.Behavior.CommitThenMatch)
        val sender = Sender(
            ctx, ModeGate.ALWAYS_LEGACY,
            ToggleSecrets(SecretResult.Present("s3cr3t")), transport,
        )
        sender.flush()
        assertTrue("the item must deliver on retry", Store(ctx).queue().isEmpty())
        val counts = ledger.counts(dedupeKey)
        assertEquals("exactly one business effect per dedupe key", 1, counts.effects)
        assertEquals("at-least-once transport: two POSTs for one key", 2, counts.requests)
    }
}
