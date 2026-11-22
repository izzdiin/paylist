package com.netter.paylist

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.migration.CutoverCommit
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.privacy.CutoverPrivacyPreflight
import com.netter.paylist.privacy.GateBackedInspector
import com.netter.paylist.security.SecretResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase A of the real process-death tests (ChatGPT re-audit P1-1,
 * R03/R04/R05): drive the cutover / sender to the crash point, prove
 * the durable state is what the crash window requires, write the
 * phase marker, then KILL THIS PROCESS with
 * `Process.killProcess(Process.myPid())`.
 *
 * Each method runs in its OWN `am instrument` invocation (see
 * scripts/process_death_tests.sh): the first kill ends the run, so
 * the methods can never share one invocation. PhaseB (a separate
 * invocation, a fresh process) re-opens the same durable state and
 * asserts the recovery.
 *
 * NEVER run under `connectedDebugAndroidTest`: the kill would abort
 * the whole run. This class is excluded via `notClass` in
 * build.gradle.kts.
 *
 * Every method ends in `fail(...)` after the kill call: if the
 * process is still alive to execute it, the kill did not happen and
 * the test must fail loudly — a PhaseA that "passes" without dying
 * would make PhaseB assert against a state no crash ever produced.
 * The ONE exception is [r06NegativeControlMarkerWithoutKill], the
 * negative control, which deliberately returns normally without
 * killing — the runner and PhaseB must catch the missing kill.
 *
 * The Room database is deliberately NOT closed before the kill: a
 * real crash does not close it either (SQLite WAL recovery replays
 * the -wal on the next open — that recovery is part of what PhaseB
 * proves).
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathPhaseA {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ProcessDeathHarness.clearAllDurableState(ctx)
        ProcessDeathHarness.seedRoutes(ctx)
    }

    // NOTE: no @After — the process is killed inside each test; there
    // is nothing to tear down, and PhaseB cleans up after itself.

    /**
     * Drive the cutover to the R03 crash window ("import done, sweep
     * not started": imported=2, removed=0, queue intact) and prove
     * the durable state. Shared by r03 (which then kills) and the r06
     * negative control (which deliberately does not).
     */
    private suspend fun driveToImportBeforeSweep(tagA: String, tagB: String) {
        val store = Store(ctx)
        val db = ProcessDeathHarness.openTestDb(ctx)
        ProcessDeathHarness.setBootId(db, ProcessDeathHarness.BOOT_A)
        val items = listOf(
            chainQueueItem(paymentPayload(tagA), ProcessDeathHarness.ROUTES),
            chainQueueItem(paymentPayload(tagB), ProcessDeathHarness.ROUTES),
        )
        assertTrue(store.saveQueueSync(items))
        val failing = FailingQueueSource(
            ProcessDeathHarness.storeQueueSource(store),
            failWrites = true,
        )
        val coordinator = ProcessDeathHarness.newCoordinator(db, failing)

        assertTrue(coordinator.requestDrain(ProcessDeathHarness.NOW) is CutoverCommit.Success)
        assertTrue(
            CutoverPrivacyPreflight().run(
                ProcessDeathHarness.storeQueueSource(store),
                ProcessDeathHarness.NOW,
            ).ok,
        )
        assertTrue(coordinator.commitDraining(ProcessDeathHarness.NOW) is CutoverCommit.Success)

        // The drain imports into Room, then the sweep's queue write
        // fails: the crash window is exactly "import done, sweep not
        // started" (imported=2, removed=0, queue intact).
        val crashed = coordinator.drain(GateBackedInspector, ProcessDeathHarness.NOW)
        assertEquals(false, crashed.ok)
        assertEquals(2, crashed.imported)
        assertEquals(0, crashed.removed)
        assertEquals(CutoverMode.DRAINING, coordinator.getMode())
        assertEquals(2, db.eventDao().count())
        assertEquals(2, store.queueSnapshot().size)
    }

    @Test
    fun r03KillAfterImportBeforeSweep() = runBlocking {
        driveToImportBeforeSweep("death-r03-a", "death-r03-b")
        ProcessDeathHarness.writeMarker(ctx, "r03")
        Process.killProcess(Process.myPid())
        fail("the process must have died")
    }

    /**
     * NEGATIVE CONTROL — do NOT add a kill here. This method drives
     * to the same crash window as r03 and writes the marker, then
     * returns NORMALLY without killing the process. The runner (the
     * clean-pass / process-alive checks) and PhaseB (the fresh-PID
     * check) must catch the missing kill and fail the run; if this
     * scenario ever passes, the harness has a false-positive. Run
     * ONLY via scripts/process_death_tests.sh --negative-control.
     */
    @Test
    fun r06NegativeControlMarkerWithoutKill() = runBlocking {
        driveToImportBeforeSweep("death-r06-a", "death-r06-b")
        ProcessDeathHarness.writeMarker(ctx, "r06")
        // Deliberately NO killProcess and NO fail(): returning normally
        // IS the control.
    }

    @Test
    fun r04KillAfterSweepBeforeWorkerOnly() = runBlocking {
        val store = Store(ctx)
        val db = ProcessDeathHarness.openTestDb(ctx)
        ProcessDeathHarness.setBootId(db, ProcessDeathHarness.BOOT_A)
        val items = listOf(
            chainQueueItem(paymentPayload("death-r04-a"), ProcessDeathHarness.ROUTES),
            chainQueueItem(paymentPayload("death-r04-b"), ProcessDeathHarness.ROUTES),
        )
        assertTrue(store.saveQueueSync(items))
        val coordinator = ProcessDeathHarness.newCoordinator(
            db,
            ProcessDeathHarness.storeQueueSource(store),
        )

        assertTrue(coordinator.requestDrain(ProcessDeathHarness.NOW) is CutoverCommit.Success)
        assertTrue(
            CutoverPrivacyPreflight().run(
                ProcessDeathHarness.storeQueueSource(store),
                ProcessDeathHarness.NOW,
            ).ok,
        )
        assertTrue(coordinator.commitDraining(ProcessDeathHarness.NOW) is CutoverCommit.Success)
        val drained = coordinator.drain(GateBackedInspector, ProcessDeathHarness.NOW)
        assertTrue("drain must succeed: ${drained.error}", drained.ok)
        assertEquals(2, drained.imported)
        assertEquals(2, drained.removed)
        assertTrue(store.queueSnapshot().isEmpty())
        assertEquals(2, db.eventDao().count())
        val marker = coordinator.getPhaseMarker()
        assertEquals("drained", marker?.phase)
        assertEquals(true, marker?.verified)

        ProcessDeathHarness.writeMarker(ctx, "r04")
        Process.killProcess(Process.myPid())
        fail("the process must have died")
    }

    @Test
    fun r05KillAfterPostBeforeAckPersistence() {
        // No runBlocking: the Sender flush is synchronous.
        val store = Store(ctx)
        val ledger = ServerLedger.open(ctx)
        val item = chainQueueItem(paymentPayload("death-r05"), ProcessDeathHarness.ROUTES)
        assertTrue(store.saveQueueSync(listOf(item)))
        val transport = LedgerTransport(ledger, LedgerTransport.Behavior.CommitThenKill)
        val sender = Sender(
            ctx, ModeGate.ALWAYS_LEGACY,
            ToggleSecrets(SecretResult.Present("s3cr3t")), transport,
        )

        // The marker proves PhaseA reached the flush; the LEDGER proves
        // the POST happened (PhaseB asserts effect == 1).
        ProcessDeathHarness.writeMarker(ctx, "r05")
        // The transport commits to the ledger, then kills this process
        // mid-POST — before the Sender sees any response and before
        // the flush persists anything (the queue is never re-saved).
        sender.flush()
        fail("the transport must have killed the process during the POST")
    }
}
