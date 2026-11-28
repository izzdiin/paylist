package com.netter.paylist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * Phase B of the Stage D capture process-death oracle (PR #5
 * re-review 28 Nov 2026): a FRESH process (a separate
 * `am instrument` invocation after PhaseA killed the last one)
 * re-opens the same durable state — the file-backed Room
 * database, the SharedPreferences legacy queue, the real
 * notification shade — and asserts the recovery:
 *
 *  1. the staged capture survived the kill exactly once;
 *  2. the orphaned-staging sweep promotes it to the legacy queue
 *     exactly once (no duplicate, no loss);
 *  3. an ACTUAL rescan — this app's notifications still active in
 *     the system shade, mapped with the production
 *     `NotificationNormalizer.from` and handed to the same
 *     `CaptureCoordinator.capture` the listener uses — dedupes
 *     against the recovered capture (still exactly one event);
 *  4. the dismissed cases: a notification dismissed AFTER its
 *     capture never deletes the durable capture, and a
 *     notification dismissed BEFORE any capture is not
 *     resurrected by the rescan (it is no longer in the shade).
 *
 * Each method FIRST requires its PhaseA marker AND a fresh
 * process (PID + process start time differ from the identity
 * PhaseA recorded): if the kill never happened, the method fails
 * loudly instead of asserting against a state no crash produced.
 *
 * setUp deliberately does NOT clear anything — the durable state
 * IS the test input. tearDown cleans up for the next scenario.
 *
 * NEVER run under `connectedDebugAndroidTest`: excluded via
 * `notClass` in app/build.gradle.kts; run through
 * scripts/capture_death_tests.sh instead.
 */
@RunWith(AndroidJUnit4::class)
class CaptureDeathPhaseB {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // No clearing: PhaseA's durable state is the input.
    }

    @After
    fun tearDown() {
        CaptureDeathHarness.clearAllDurableState(ctx)
    }

    /**
     * Require PhaseA's marker AND a fresh process (the audit
     * oracle's contract): same process iff the PID matches AND
     * (either start time is unknown, or both start times match).
     */
    private fun requireFreshProcess(scenario: String) {
        val recorded = CaptureDeathHarness.readMarkerIdentity(ctx, scenario)
        assertNotNull(
            "PhaseA must have reached the kill point for $scenario (run PhaseA first)",
            recorded,
        )
        val mine = CaptureDeathHarness.currentProcessIdentity()
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
     * The c01 recovery assertions. Shared by c01 and the c06
     * negative control (whose PhaseB asserts the same recovery, so
     * the ONLY reason it can fail is the freshness check).
     */
    private suspend fun recoveryCompletesExactlyOnce() {
        val store = Store(ctx)
        val db = CaptureDeathHarness.openTestDb(ctx)
        val repository = CaptureDeathHarness.newRepository(db)
        try {
            // (1) The crash state, from durable storage alone:
            // one event, one orphaned staging row, empty queue.
            assertEquals(1, db.eventDao().count())
            assertEquals(1, repository.getAllStagingRows().size)
            assertEquals(0, store.queueSnapshot().size)

            val coordinator = CaptureDeathHarness.newCoordinator(
                ctx, db, CaptureDeathHarness.durableLegacyEnqueue(ctx),
            )
            try {
                // (2) Restart recovery: the orphaned staging row
                // is promoted to the legacy queue exactly once.
                coordinator.sweepOrphanedStaging()
                assertEquals(0, repository.getAllStagingRows().size)
                assertEquals(1, store.queueSnapshot().size)
                assertEquals(1, db.eventDao().count())

                // (3) ACTUAL rescan: the notification PhaseA
                // posted is still active in the shade (process
                // death does not clear it). Re-delivering it
                // through the production rescan mapping + capture
                // entry must dedupe — no second event, no second
                // queue item.
                val postTime1 = CaptureDeathHarness
                    .readScenarioLong(ctx, "c01_post_time")
                assertTrue("PhaseA must have recorded the post time", postTime1 > 0)
                val shade = CaptureDeathHarness.activeSnapshots(ctx)
                val rescanned = shade.firstOrNull { it.postTimeMs == postTime1 }
                assertNotNull(
                    "the pre-kill notification must still be in the shade",
                    rescanned,
                )
                coordinator.capture(rescanned!!)
                CaptureDeathHarness.awaitCondition("rescan dedupe settled") {
                    coordinator.counters().duplicates >= 1L
                }
                assertEquals(1, db.eventDao().count())
                assertEquals(1, store.queueSnapshot().size)

                // (4a) Dismissed AFTER capture: capture a second
                // real notification, then dismiss it from the
                // shade — the durable capture and its queue item
                // must survive the dismissal untouched.
                CaptureDeathHarness.postPaymentNotification(
                    ctx, CaptureDeathHarness.NOTIF_ID_2, "Rp200.000",
                )
                CaptureDeathHarness.awaitCondition("second notification in shade") {
                    CaptureDeathHarness.activeSnapshots(ctx).size >= 2
                }
                val second = CaptureDeathHarness.activeSnapshots(ctx)
                    .first { it.postTimeMs != postTime1 }
                coordinator.capture(second)
                CaptureDeathHarness.awaitCondition("second capture enqueued") {
                    store.queueSnapshot().size == 2
                }
                assertEquals(2, db.eventDao().count())
                CaptureDeathHarness.dismissNotification(
                    ctx, CaptureDeathHarness.NOTIF_ID_2,
                )
                CaptureDeathHarness.awaitCondition("second notification dismissed") {
                    CaptureDeathHarness.activeSnapshots(ctx)
                        .none { it.postTimeMs == second.postTimeMs }
                }
                assertEquals(
                    "dismissal must never delete a durable capture",
                    2, db.eventDao().count(),
                )
                assertEquals(2, store.queueSnapshot().size)

                // (4b) Dismissed BEFORE capture: post and dismiss
                // a third notification without capturing it; a
                // rescan of the shade cannot resurrect it (it is
                // no longer active), so no event may appear for
                // it.
                CaptureDeathHarness.postPaymentNotification(
                    ctx, CaptureDeathHarness.NOTIF_ID_3, "Rp300.000",
                )
                CaptureDeathHarness.awaitCondition("third notification in shade") {
                    CaptureDeathHarness.activeSnapshots(ctx)
                        .any { it.title.isNotEmpty() } &&
                        CaptureDeathHarness.activeSnapshots(ctx).size >= 2
                }
                val third = CaptureDeathHarness.activeSnapshots(ctx)
                    .first {
                        it.postTimeMs != postTime1 &&
                            it.postTimeMs != second.postTimeMs
                    }
                CaptureDeathHarness.dismissNotification(
                    ctx, CaptureDeathHarness.NOTIF_ID_3,
                )
                CaptureDeathHarness.awaitCondition("third notification dismissed") {
                    CaptureDeathHarness.activeSnapshots(ctx)
                        .none { it.postTimeMs == third.postTimeMs }
                }
                for (snapshot in CaptureDeathHarness.activeSnapshots(ctx)) {
                    coordinator.capture(snapshot)
                }
                // Give any (incorrect) resurrection a window to
                // land, then assert on the durable state.
                Thread.sleep(2_000)
                assertEquals(
                    "a dismissed notification must not be captured by rescan",
                    2, db.eventDao().count(),
                )
                assertEquals(2, store.queueSnapshot().size)
            } finally {
                coordinator.close()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun c01RecoveryCompletesExactlyOnce() = runBlocking {
        requireFreshProcess("c01")
        recoveryCompletesExactlyOnce()
    }

    /**
     * c07 recovery (PR #5 audit 28 Nov, P1-2): the health
     * incident PhaseA flushed across the durable acceptance
     * boundary and then SIGKILLed itself over must still be in
     * the durable health document in this FRESH process — the
     * real process-kill proof for persisted incidents (a
     * coordinator replacement in one process cannot prove this:
     * an apply()-only write could have survived in memory there;
     * here nothing in memory survived).
     */
    @Test
    fun c07IncidentSurvivedKill() = runBlocking {
        requireFreshProcess("c07")
        val raw = ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE)
            .getString(PaylistApplication.CAPTURE_HEALTH_KEY, null)
        assertNotNull(
            "the health document must survive the process kill",
            raw,
        )
        val incidents = org.json.JSONObject(raw!!).getJSONArray("incidents")
        var found = false
        for (i in 0 until incidents.length()) {
            val item = incidents.getJSONObject(i)
            if (item.optString("category") == "overflow_snapshot_dropped" &&
                item.optString("status") == "OPEN" &&
                item.optLong("count") >= 1L
            ) {
                found = true
            }
        }
        assertTrue(
            "the flushed overflow incident must survive the kill",
            found,
        )
        // And a fresh coordinator over the same store reports it
        // through the persistent layer (not just raw JSON).
        val db = CaptureDeathHarness.openTestDb(ctx)
        try {
            val coordinator = CaptureDeathHarness.newCoordinator(
                ctx, db, CaptureDeathHarness.durableLegacyEnqueue(ctx),
            )
            try {
                assertTrue(
                    coordinator.degradedState().persistentIncidents.any {
                        it.category == "overflow_snapshot_dropped" &&
                            it.status == "OPEN"
                    },
                )
            } finally {
                coordinator.close()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun c06NegativeControlMustFail() = runBlocking {
        // Negative control (see CaptureDeathPhaseA): PhaseA wrote
        // the marker but deliberately did NOT kill its process.
        // This method asserts the same recovery as c01, so the
        // ONLY reason it can fail is the freshness check above
        // (or the runner, which refuses to run PhaseB at all when
        // PhaseA's process is still alive). Run ONLY via
        // scripts/capture_death_tests.sh --negative-control.
        requireFreshProcess("c06")
        recoveryCompletesExactlyOnce()
    }
}
