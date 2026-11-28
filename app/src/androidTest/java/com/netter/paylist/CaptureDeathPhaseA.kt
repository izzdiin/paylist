package com.netter.paylist

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.collector.CaptureCoordinator
import com.netter.paylist.migration.LegacyEnqueue
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

    /** A legacy enqueue whose delivery stays in-flight forever in this process. */
    private fun blockingEnqueue(): LegacyEnqueue {
        val neverReleased = CountDownLatch(1)
        return LegacyEnqueue { _, _, _ ->
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
    }

    /**
     * Drives the pipeline to the crash window: one capture durably
     * staged in Room, its legacy delivery blocked in-flight (the
     * enqueue latch is never released in this process). Shared by
     * c01 (which then kills) and the c06 negative control (which
     * deliberately does not).
     */
    private suspend fun driveToStagedBeforeDelivery() {
        val db = CaptureDeathHarness.openTestDb(ctx)
        val coordinator = CaptureDeathHarness.newCoordinator(ctx, db, blockingEnqueue())

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
     * c07 (PR #5 audit 28 Nov, P1-2): a persistent health
     * incident must survive a REAL process kill — not just a
     * coordinator replacement. Drives one overflow drop (queue
     * capacity 1, the worker blocked in a delivery), then crosses
     * the durable acceptance boundary EXPLICITLY:
     * `flushHealthIncidents` returns only after the incident's
     * durable write (commit()) has returned from the store. The
     * incident is then verified FROM DURABLE STORAGE in this
     * process, the marker is written, and the process kills
     * itself. PhaseB re-reads the document in a fresh process.
     *
     * The residual window is stated honestly (see the
     * CaptureHealthStore contract): an incident offered but not
     * yet flushed when the process dies is lost — this scenario
     * proves the BOUNDARY (post-flush durability), not zero loss.
     */
    @Test
    fun c07KillAfterDurableIncident() = runBlocking {
        val db = CaptureDeathHarness.openTestDb(ctx)
        val coordinator = CaptureDeathHarness.newCoordinator(
            ctx, db, blockingEnqueue(), executorCapacity = 1,
        )
        CaptureDeathHarness.postPaymentNotification(
            ctx, CaptureDeathHarness.NOTIF_ID_1, "Rp150.000",
        )
        CaptureDeathHarness.awaitCondition("first notification in the shade") {
            CaptureDeathHarness.activeSnapshots(ctx).isNotEmpty()
        }
        val first = CaptureDeathHarness.activeSnapshots(ctx).single()
        assertEquals(
            CaptureCoordinator.CaptureReceipt.QUEUED,
            coordinator.capture(first),
        )
        CaptureDeathHarness.awaitCondition("first capture durably staged") {
            runBlocking { db.eventDao().count() == 1 }
        }
        // Second notification fills the capacity-1 queue; the
        // third evicts it — an overflow drop, hence a persistent
        // OVERFLOW_SNAPSHOT_DROPPED incident.
        CaptureDeathHarness.postPaymentNotification(
            ctx, CaptureDeathHarness.NOTIF_ID_2, "Rp200.000",
        )
        CaptureDeathHarness.awaitCondition("second notification in the shade") {
            CaptureDeathHarness.activeSnapshots(ctx).size >= 2
        }
        val second = CaptureDeathHarness.activeSnapshots(ctx)
            .first { it.postTimeMs != first.postTimeMs }
        assertEquals(
            CaptureCoordinator.CaptureReceipt.QUEUED,
            coordinator.capture(second),
        )
        CaptureDeathHarness.postPaymentNotification(
            ctx, CaptureDeathHarness.NOTIF_ID_3, "Rp300.000",
        )
        CaptureDeathHarness.awaitCondition("third notification in the shade") {
            CaptureDeathHarness.activeSnapshots(ctx).size >= 3
        }
        val third = CaptureDeathHarness.activeSnapshots(ctx)
            .first {
                it.postTimeMs != first.postTimeMs &&
                    it.postTimeMs != second.postTimeMs
            }
        coordinator.capture(third)
        assertEquals(1L, coordinator.counters().droppedOverflow)
        // THE DURABLE ACCEPTANCE BOUNDARY: returns only after
        // the incident's durable write has returned.
        coordinator.flushHealthIncidents(20_000)
        // Prove it from durable storage, in this process, before
        // the kill: the health document in SharedPreferences
        // carries the OPEN incident.
        val raw = ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE)
            .getString(PaylistApplication.CAPTURE_HEALTH_KEY, null)
        org.junit.Assert.assertNotNull("health document must be durable", raw)
        val incidents = org.json.JSONObject(raw!!).getJSONArray("incidents")
        var found = false
        for (i in 0 until incidents.length()) {
            val item = incidents.getJSONObject(i)
            if (item.optString("category") == "overflow_snapshot_dropped" &&
                item.optString("status") == "OPEN"
            ) {
                found = true
            }
        }
        org.junit.Assert.assertTrue(
            "the overflow incident must be durable BEFORE the kill",
            found,
        )
        CaptureDeathHarness.writeMarker(ctx, "c07")
        Process.killProcess(Process.myPid())
        fail("the process must have died")
    }

    /**
     * c08 (PR #5 audit 7235948077, A06): the PRE-durable window
     * must not be silent. Drives the same overflow drop as c07,
     * but the health store's durable write is GATED: the health
     * writer parks inside writeCaptureHealth, so the offered
     * incident is journaled (the offer journal's commit()
     * returns) yet NEVER crosses the durable boundary — no
     * flush, no completed write. The marker is written and the
     * process kills itself with the incident still in flight.
     * PhaseB must then find NO overflow incident in the health
     * document (it is genuinely lost) and an UNKNOWN_LOSS
     * incident exposing exactly that loss — the loss is visible,
     * not silent, and nothing claims the incident itself
     * survived.
     */
    @Test
    fun c08KillBeforeDurableIncident() = runBlocking {
        val db = CaptureDeathHarness.openTestDb(ctx)
        val gate = CaptureDeathHarness.HealthWriteGate()
        val coordinator = CaptureDeathHarness.newCoordinator(
            ctx, db, blockingEnqueue(), executorCapacity = 1,
            healthWriteGate = gate,
        )
        CaptureDeathHarness.postPaymentNotification(
            ctx, CaptureDeathHarness.NOTIF_ID_1, "Rp150.000",
        )
        CaptureDeathHarness.awaitCondition("first notification in the shade") {
            CaptureDeathHarness.activeSnapshots(ctx).isNotEmpty()
        }
        val first = CaptureDeathHarness.activeSnapshots(ctx).single()
        assertEquals(
            CaptureCoordinator.CaptureReceipt.QUEUED,
            coordinator.capture(first),
        )
        CaptureDeathHarness.awaitCondition("first capture durably staged") {
            runBlocking { db.eventDao().count() == 1 }
        }
        CaptureDeathHarness.postPaymentNotification(
            ctx, CaptureDeathHarness.NOTIF_ID_2, "Rp200.000",
        )
        CaptureDeathHarness.awaitCondition("second notification in the shade") {
            CaptureDeathHarness.activeSnapshots(ctx).size >= 2
        }
        val second = CaptureDeathHarness.activeSnapshots(ctx)
            .first { it.postTimeMs != first.postTimeMs }
        assertEquals(
            CaptureCoordinator.CaptureReceipt.QUEUED,
            coordinator.capture(second),
        )
        CaptureDeathHarness.postPaymentNotification(
            ctx, CaptureDeathHarness.NOTIF_ID_3, "Rp300.000",
        )
        CaptureDeathHarness.awaitCondition("third notification in the shade") {
            CaptureDeathHarness.activeSnapshots(ctx).size >= 3
        }
        val third = CaptureDeathHarness.activeSnapshots(ctx)
            .first {
                it.postTimeMs != first.postTimeMs &&
                    it.postTimeMs != second.postTimeMs
            }
        coordinator.capture(third)
        assertEquals(1L, coordinator.counters().droppedOverflow)
        // The incident has been OFFERED and the health writer is
        // parked INSIDE the durable write: the write has not
        // returned, so nothing about the incident is durable.
        org.junit.Assert.assertTrue(
            "the health writer must be parked inside the durable write",
            gate.entered.await(20, java.util.concurrent.TimeUnit.SECONDS),
        )
        // The A06 marker, by contrast, IS durable: the offer
        // journal recorded the offer synchronously at offer time.
        val journalRaw = ctx.getSharedPreferences(
            PaylistApplication.CAPTURE_JOURNAL_PREFS, Context.MODE_PRIVATE,
        ).getString(PaylistApplication.CAPTURE_JOURNAL_KEY, null)
        org.junit.Assert.assertNotNull("the offer journal must be durable", journalRaw)
        org.junit.Assert.assertTrue(
            "the journal must record at least the dropped-snapshot offer",
            org.json.JSONObject(journalRaw!!).optLong("offered", 0L) >= 1L,
        )
        // And the health document carries NO overflow incident:
        // the loss PhaseB must expose is genuinely pre-durable.
        val healthRaw = ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE)
            .getString(PaylistApplication.CAPTURE_HEALTH_KEY, null)
        if (healthRaw != null) {
            val incidents = org.json.JSONObject(healthRaw)
                .optJSONArray("incidents")
            if (incidents != null) {
                for (i in 0 until incidents.length()) {
                    org.junit.Assert.assertTrue(
                        "no incident may be durable before the kill",
                        incidents.getJSONObject(i)
                            .optString("category") != "overflow_snapshot_dropped",
                    )
                }
            }
        }
        CaptureDeathHarness.writeMarker(ctx, "c08")
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
