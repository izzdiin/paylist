package com.netter.paylist.collector

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.Route
import com.netter.paylist.data.EventRepository
import com.netter.paylist.data.MIGRATION_1_2
import com.netter.paylist.data.PaylistDatabase
import com.netter.paylist.data.entity.CaptureOutcome
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.migration.LegacyEnqueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage D audit follow-up (PR #5 re-review 27 Nov), re-scoped by
 * the 28 Nov re-review: this class is an IN-PROCESS
 * restart-boundary REGRESSION — it is NOT process-death evidence.
 * The 27 Nov runner script for it was rejected as an oracle (it
 * could exit 0 after an instrumentation failure and could not
 * prove the killed PID was the staged process), and the script
 * has been removed. Actual process death for this pipeline is
 * proven by the two-phase oracle `scripts/capture_death_tests.sh`
 * (CaptureDeathPhaseA/B: self-kill at the crash window, runner
 * death checks, fresh-process PhaseB, negative + fake-adb
 * controls), which runs in CI's `process-death-oracle` job.
 *
 * Two scenarios against a FILE-BACKED Room database (a named
 * file, never the in-memory builder), driven here as a
 * same-process coordinator-replacement regression:
 *
 * - AFTER-STAGE: a capture is ACCEPTED (staging row committed)
 *   but its enqueue is parked. The coordinator is replaced. The
 *   startup sweep must promote it exactly once.
 * - BEFORE-STAGE: a capture is RECEIVED-only (queued in memory
 *   behind a held worker) when the coordinator is replaced. No
 *   staging row, no overflow row. It exists only as a still-
 *   posted notification: a rescan re-capture must land it
 *   exactly once, and a second rescan must dedupe.
 *
 * Nothing here is closed or cleaned up in the setup methods:
 * closing would write the graceful-shutdown evidence whose
 * absence the replacement boundary is meant to simulate. The
 * databases are dedicated test files, deleted at each setup.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProcessDeathHarnessTest {

    companion object {
        const val PKG = "com.gojek.gopaymerchant"
        const val ROUTE_ID = "route-pd"
        const val DB_AFTER = "paylist-process-death-after.db"
        const val DB_BEFORE = "paylist-process-death-before.db"
        const val T_AFTER = 1_760_100_000_000L
        const val T_BLOCKER = 1_760_200_000_000L
        const val T_VICTIM = 1_760_200_060_000L
        const val POLL_TIMEOUT_MS = 20_000L
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val routes = listOf(
        Route(
            id = ROUTE_ID,
            name = "Process-death harness (test)",
            packages = listOf(PKG),
            url = "http://127.0.0.1:9/notify-test.php",
            keywords = Route.DEFAULT_KEYWORDS,
            enabled = true,
        ),
    )

    private fun openDb(name: String): PaylistDatabase =
        Room.databaseBuilder(context, PaylistDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2)
            .build()

    private fun repository(db: PaylistDatabase): EventRepository = EventRepository(
        db.eventDao(), db.deliveryDao(), db.captureStagingDao(),
        db.classificationDao(), db.transactionRunner(),
    )

    private val noopLogger = object : CaptureLogSink {
        override fun logEvent(pkg: String, text: String) = Unit
        override fun logLocal(text: String) = Unit
    }

    private fun newCoordinator(
        db: PaylistDatabase,
        enqueueOk: AtomicBoolean = AtomicBoolean(true),
        modeLatch: CountDownLatch? = null,
    ): CaptureCoordinator = CaptureCoordinator(
        repository = repository(db),
        modeReader = {
            if (modeLatch != null) {
                try {
                    modeLatch.await(120, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            CutoverMode.LEGACY_ONLY
        },
        legacyEnqueue = LegacyEnqueue { _, _, _ -> enqueueOk.get() },
        routesProvider = { routes },
        deviceIdProvider = { "process-death-harness" },
        logger = noopLogger,
        clock = { T_AFTER + 5_000L },
        captureLogDao = db.captureLogDao(),
    )

    private fun snapshot(postTime: Long) = NotificationSnapshot(
        key = "key-$postTime",
        packageName = PKG,
        postTimeMs = postTime,
        flags = 0,
        title = "Pembayaran berhasil",
        text = "Pembayaran Rp100.800 berhasil",
        bigText = "",
        capturedAtMs = postTime + 1_000L,
    )

    private fun dedupeOf(postTime: Long): String =
        CaptureCoordinator.legacyDedupeKey(
            PKG, postTime, 100_800L, snapshot(postTime).combined,
        )

    private fun awaitTrue(message: String, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { runBlocking { check() } }.getOrDefault(false)) return
            Thread.sleep(100)
        }
        throw AssertionError("timeout: $message")
    }

    private fun marker(name: String): File = File(context.filesDir, name)

    // --- Phase 1a: ACCEPTED, then the process dies ---

    @Test
    fun test01_afterStage_setup_acceptedParked() {
        context.deleteDatabase(DB_AFTER)
        marker("pd-after.marker").delete()
        val db = openDb(DB_AFTER)
        val repo = repository(db)
        val coordinator = newCoordinator(db, enqueueOk = AtomicBoolean(false))
        assertEquals(
            CaptureCoordinator.CaptureReceipt.QUEUED,
            coordinator.capture(snapshot(T_AFTER)),
        )
        awaitTrue("capture staged (ACCEPTED) and parked") {
            repo.getAllStagingRows().size == 1 &&
                repo.getEventByDedupeKey(dedupeOf(T_AFTER)) != null
        }
        marker("pd-after.marker").writeText(T_AFTER.toString())
        // Deliberately NO close(): the harness kills this process
        // with SIGKILL; a graceful close would falsify the setup.
    }

    // --- Phase 1b: RECEIVED-only, then the process dies ---

    @Test
    fun test02_beforeStage_setup_receivedOnly() {
        context.deleteDatabase(DB_BEFORE)
        marker("pd-before.marker").delete()
        val db = openDb(DB_BEFORE)
        val repo = repository(db)
        val latch = CountDownLatch(1)
        val coordinator = newCoordinator(db, modeLatch = latch)
        // Blocker: stages, then holds the single worker in its
        // mode read — the victim behind it stays RECEIVED-only.
        coordinator.capture(snapshot(T_BLOCKER))
        awaitTrue("blocker staged, worker held") {
            repo.getAllStagingRows().size == 1 &&
                repo.getEventByDedupeKey(dedupeOf(T_BLOCKER)) != null
        }
        assertEquals(
            CaptureCoordinator.CaptureReceipt.QUEUED,
            coordinator.capture(snapshot(T_VICTIM)),
        )
        // Give the worker a moment: it must NOT stage the victim
        // (it is held by the blocker). The victim exists only in
        // this process's memory when the kill lands.
        Thread.sleep(500)
        assertNull(
            "victim must not be staged before the kill",
            runBlocking { repo.getEventByDedupeKey(dedupeOf(T_VICTIM)) },
        )
        marker("pd-before.marker").writeText("$T_BLOCKER,$T_VICTIM")
        // Deliberately NO close() and the latch is never released.
    }

    // --- Phase 2a: restart after the AFTER-STAGE kill ---

    @Test
    fun test03_afterStage_recovery_promotedExactlyOnce() {
        assertTrue(
            "phase-1 marker missing — run via scripts/process-death-harness.sh",
            marker("pd-after.marker").exists(),
        )
        val db = openDb(DB_AFTER)
        val repo = repository(db)
        val coordinator = newCoordinator(db)
        try {
            assertEquals(
                "the ACCEPTED staging row survived the kill",
                1, runBlocking { repo.getAllStagingRows().size },
            )
            runBlocking { coordinator.sweepNow() }
            awaitTrue("orphan promoted after restart") {
                repo.getAllStagingRows().isEmpty()
            }
            val event = runBlocking { repo.getEventByDedupeKey(dedupeOf(T_AFTER)) }
            assertNotNull(event)
            val deliveries = runBlocking {
                repo.getDeliveriesByEventId(event!!.eventId)
            }
            // LEGACY_ONLY promotes via the legacy store, so the
            // exactly-once evidence is: one event, staging gone,
            // and a second sweep changes nothing.
            assertTrue(deliveries.size <= 1)
            runBlocking { coordinator.sweepNow() }
            assertEquals(
                1,
                runBlocking {
                    repo.getAllStagingRows().size +
                        (if (repo.getEventByDedupeKey(dedupeOf(T_AFTER)) != null) 1 else 0)
                },
            )
        } finally {
            runCatching { coordinator.close() }
            db.close()
        }
    }

    // --- Phase 2b: restart after the BEFORE-STAGE kill ---

    @Test
    fun test04_beforeStage_recovery_rescanExactlyOnce() {
        assertTrue(
            "phase-1 marker missing — run via scripts/process-death-harness.sh",
            marker("pd-before.marker").exists(),
        )
        val db = openDb(DB_BEFORE)
        val repo = repository(db)
        val coordinator = newCoordinator(db)
        try {
            // The victim left NOTHING durable: no event, and no
            // overflow row (SIGKILL ran no close()).
            assertNull(
                "victim has no event after a before-stage kill",
                runBlocking { repo.getEventByDedupeKey(dedupeOf(T_VICTIM)) },
            )
            assertEquals(
                "no overflow evidence exists for the victim",
                0,
                runBlocking {
                    db.captureLogDao().countHistory(
                        fromMs = null, toMs = null, minAmount = null,
                        maxAmount = null, packageName = null,
                        outcome = CaptureOutcome.OVERFLOW_DROPPED,
                        state = null, routeId = null, query = null,
                        matchStatus = null,
                    )
                },
            )
            // The blocker, by contrast, WAS staged before the
            // kill: the startup sweep promotes it exactly once.
            runBlocking { coordinator.sweepNow() }
            awaitTrue("blocker staging promoted after restart") {
                repo.getAllStagingRows().isEmpty()
            }
            // Rescan: the victim is still posted in the shade, so
            // the listener re-reads it. First rescan lands it;
            // second rescan dedupes — exactly one event either way.
            coordinator.capture(snapshot(T_VICTIM))
            awaitTrue("victim landed via rescan") {
                repo.getEventByDedupeKey(dedupeOf(T_VICTIM)) != null
            }
            val firstEventId = runBlocking {
                repo.getEventByDedupeKey(dedupeOf(T_VICTIM))
            }!!.eventId
            // Second rescan of the same still-posted notification:
            // the dedupe path must keep the SAME single event —
            // no second event, no leftover staging row.
            coordinator.capture(snapshot(T_VICTIM))
            Thread.sleep(500)
            assertEquals(
                "exactly one event for the victim after two rescans",
                firstEventId,
                runBlocking {
                    repo.getEventByDedupeKey(dedupeOf(T_VICTIM))
                }!!.eventId,
            )
            assertEquals(
                0,
                runBlocking {
                    db.captureStagingDao().getByDedupeKey(dedupeOf(T_VICTIM)).size
                },
            )
        } finally {
            runCatching { coordinator.close() }
            db.close()
        }
    }
}
