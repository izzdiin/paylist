package com.netter.paylist

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.collector.CaptureCoordinator
import com.netter.paylist.collector.LegacyEnqueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch

/**
 * Phase A of the Stage D capture process-death oracle (PR #5
 * re-review 28 Nov 2026): post a REAL notification to the system
 * shade, hand its normalised snapshot to a real CaptureCoordinator
 * over the file-backed test Room database, prove the capture is
 * DURABLY STAGED (event + staging row committed) while its legacy
 * delivery is still blocked in-flight, write the phase marker,
 * then KILL THIS PROCESS with
 * `Process.killProcess(Process.myPid())` (SIGKILL to self — the
 * same mechanism as the Stage C oracle).
 *
 * Each method runs in its OWN `am instrument` invocation (see
 * scripts/capture_death_tests.sh): the kill ends the run, so the
 * methods can never share one invocation, and they are excluded
 * from `connectedDebugAndroidTest` via `notClass` in
 * app/build.gradle.kts.
 *
 * The Room database is deliberately NOT closed before the kill: a
 * real crash does not close it either (SQLite WAL recovery on the
 * next open is part of what PhaseB proves). The notification is
 * deliberately left posted: it must still be in the shade when
 * PhaseB runs its actual rescan.
 *
 * Every kill method ends in `fail(...)` after the kill call: if
 * the process is still alive to execute it, the kill did not
 * happen and the run must fail loudly. The ONE exception is
 * [c06NegativeControlMarkerWithoutKill], the negative control,
 * which deliberately returns normally — the runner and PhaseB
 * must catch the missing kill.
 */
@RunWith(AndroidJUnit4::class)
class CaptureDeathPhaseA {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        CaptureDeathHarness.clearAllDurableState(ctx)
        CaptureDeathHarness.seedRoutes(ctx)
    }

    // NOTE: no @After — the process is killed inside the test.

    /**
     * Drives the pipeline to the crash window: one capture durably
     * staged in Room, its legacy delivery blocked in-flight (the
     * enqueue latch is never released in this process). Shared by
     * c01 (which then kills) and the c06 negative control (which
     * deliberately does not).
     */
    private suspend fun driveToStagedBeforeDelivery() {
        val db = CaptureDeathHarness.openTestDb(ctx)
        val neverReleased = CountDownLatch(1)
        val blockingEnqueue = LegacyEnqueue { _, _, _ ->
            // Delivery in-flight forever (this process): waits,
            // swallowing interrupts — the kill is the only way out,
            // exactly like a crash mid-delivery.
            val deadline = System.currentTimeMillis() + 120_000L
            while (System.currentTimeMillis() < deadline) {
                try {
                    neverReleased.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // Swallowed on purpose (blocked delivery).
                }
                if (neverReleased.count == 0L) break
            }
            false
        }
        val coordinator = CaptureDeathHarness.newCoordinator(ctx, db, blockingEnqueue)

        // A REAL notification in the system shade; the snapshot is
        // the same normalisation the listener hands the pipeline.
        CaptureDeathHarness.postPaymentNotification(
            ctx, CaptureDeathHarness.NOTIF_ID_1, "Rp150.000",
        )
        CaptureDeathHarness.awaitCondition("notification active in the shade") {
            CaptureDeathHarness.activeSnapshots(ctx).isNotEmpty()
        }
        val snapshot = CaptureDeathHarness.activeSnapshots(ctx).single()
        CaptureDeathHarness.writeScenarioLong(ctx, "c01_post_time", snapshot.postTimeMs)
        assertEquals(
            CaptureCoordinator.CaptureReceipt.QUEUED,
            coordinator.capture(snapshot),
        )
        // The durable precondition: the staging write committed
        // (event + staging row in the file-backed DB) even though
        // the delivery never completed.
        CaptureDeathHarness.awaitCondition("capture durably staged") {
            runBlocking {
                db.eventDao().count() == 1 &&
                    CaptureDeathHarness.newRepository(db)
                        .getAllStagingRows().size == 1
            }
        }
        // The legacy queue must still be empty: delivery is the
        // step the crash interrupts.
        assertEquals(0, Store(ctx).queueSnapshot().size)
    }

    @Test
    fun c01KillAfterStagingBeforeDelivery() = runBlocking {
        driveToStagedBeforeDelivery()
        CaptureDeathHarness.writeMarker(ctx, "c01")
        Process.killProcess(Process.myPid())
        fail("the process must have died")
    }

    /**
     * NEGATIVE CONTROL — do NOT add a kill here. Drives to the
     * same crash window as c01 and writes the marker, then returns
     * NORMALLY without killing. The runner (clean-pass /
     * process-alive checks + marker verification) and PhaseB (the
     * fresh-process check) must catch the missing kill; if this
     * scenario ever passes, the oracle has a false positive. Run
     * ONLY via scripts/capture_death_tests.sh --negative-control.
     */
    @Test
    fun c06NegativeControlMarkerWithoutKill() = runBlocking {
        driveToStagedBeforeDelivery()
        CaptureDeathHarness.writeMarker(ctx, "c06")
        // Deliberately NO killProcess and NO fail(): returning
        // normally IS the control.
    }
}
