package com.netter.paylist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.netter.paylist.collector.CaptureCoordinator
import com.netter.paylist.collector.CaptureHealthStore
import com.netter.paylist.collector.CaptureLogSink
import com.netter.paylist.collector.ConsentStore
import com.netter.paylist.collector.NotificationNormalizer
import com.netter.paylist.collector.NotificationSnapshot
import com.netter.paylist.collector.PrivacyGate
import com.netter.paylist.data.EventRepository
import com.netter.paylist.data.PaylistDatabase
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.migration.LegacyEnqueue
import com.netter.paylist.migration.LegacyQkey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared state for the Stage D capture process-death oracle
 * (PR #5 re-review 28 Nov 2026). Modelled on the Stage C oracle
 * (`ProcessDeathHarness` on the audit branch): PhaseA drives the
 * capture pipeline to a durable point and KILLS ITS OWN PROCESS
 * with `Process.killProcess` (a real SIGKILL to self); PhaseB (a
 * fresh process, separate `am instrument` invocation) re-opens the
 * same durable state and asserts recovery. Everything the phases
 * share is durable: the file-backed Room database, the
 * SharedPreferences legacy queue + phase markers, and the real
 * notification shade (notifications posted by this app survive its
 * process death).
 */
object CaptureDeathHarness {
    /** The file-backed Room database both phases open (never in-memory). */
    const val TEST_DB_NAME: String = "paylist-capture-death-test.db"

    const val ROUTE_ID: String = "route-capture-death"
    const val NOTIF_ID_1: Int = 9101
    const val NOTIF_ID_2: Int = 9102
    const val NOTIF_ID_3: Int = 9103
    private const val CHANNEL_ID: String = "capture-death-tests"

    /**
     * Identity of one OS process: PID + start time (clock ticks
     * since boot, field 22 of /proc/self/stat). Mirrors the audit
     * harness: PID + start time cannot collide for two distinct
     * processes; `startTicks == -1` means unreadable (PID-only
     * fallback).
     */
    data class ProcessIdentity(val pid: Int, val startTicks: Long)

    fun currentProcessIdentity(): ProcessIdentity {
        val startTicks = try {
            val stat = java.io.File("/proc/self/stat").readText()
            val afterComm = stat.substringAfterLast(") ").split(" ")
            afterComm.getOrNull(19)?.toLongOrNull() ?: -1L
        } catch (_: Exception) {
            -1L
        }
        return ProcessIdentity(android.os.Process.myPid(), startTicks)
    }

    /** Marker key in the "paylist" prefs: PhaseA writes, PhaseB requires. */
    fun markerKey(scenario: String): String = "capture_death_$scenario"

    /**
     * Marker value: "a-complete pid=<pid> start=<startTicks>".
     * The marker alone proves PhaseA REACHED the kill point;
     * PhaseB additionally requires a fresh process identity, and
     * the runner script independently requires the process to be
     * gone — the same three-part contract as the audit oracle.
     */
    fun writeMarker(ctx: Context, scenario: String) {
        val id = currentProcessIdentity()
        val value = "a-complete pid=${id.pid} start=${id.startTicks}"
        val ok = ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE).edit()
            .putString(markerKey(scenario), value).commit()
        check(ok) { "marker write must commit for $scenario" }
    }

    fun readMarker(ctx: Context, scenario: String): String? =
        ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE)
            .getString(markerKey(scenario), null)

    fun readMarkerIdentity(ctx: Context, scenario: String): ProcessIdentity? {
        val raw = readMarker(ctx, scenario) ?: return null
        val match = Regex("""^a-complete pid=(\d+) start=(-?\d+)$""")
            .matchEntire(raw.trim()) ?: return null
        return ProcessIdentity(
            match.groupValues[1].toInt(),
            match.groupValues[2].toLong(),
        )
    }

    /** Scenario data (e.g. the posted notification's postTime), durable. */
    fun writeScenarioLong(ctx: Context, key: String, value: Long) {
        val ok = ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE).edit()
            .putLong("capture_death_data_$key", value).commit()
        check(ok) { "scenario data write must commit for $key" }
    }

    fun readScenarioLong(ctx: Context, key: String): Long =
        ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE)
            .getLong("capture_death_data_$key", -1L)

    /** Fresh state for PhaseA: empty prefs, no test DB, empty shade. */
    fun clearAllDurableState(ctx: Context) {
        ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE).edit().clear().commit()
        // The audit-7235948077 stores (lifecycle record + offer
        // journal) live in their own preference files — a
        // scenario's fresh state must clear them too, or a
        // previous scenario's journal would leak into the next
        // one's reconciliation.
        ctx.getSharedPreferences(
            PaylistApplication.CAPTURE_LIFECYCLE_PREFS, Context.MODE_PRIVATE,
        ).edit().clear().commit()
        ctx.getSharedPreferences(
            PaylistApplication.CAPTURE_JOURNAL_PREFS, Context.MODE_PRIVATE,
        ).edit().clear().commit()
        ctx.deleteDatabase(TEST_DB_NAME)
        notificationManager(ctx).cancelAll()
    }

    /**
     * A gate on the harness health store's durable write (c08):
     * the first write parks inside the store — the offered
     * incident is journaled but NEVER becomes durable — until
     * [release] opens. PhaseA kills the process instead of
     * releasing it.
     */
    class HealthWriteGate {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
    }

    /**
     * One route matching THIS app's own package: the harness posts
     * real notifications from the app, so the shade notifications
     * carry package `com.netter.paylist`.
     */
    fun seedRoutes(ctx: Context) {
        Store(ctx).saveRoutes(
            listOf(
                Route(
                    ROUTE_ID, "capture-death", listOf("com.netter.paylist"),
                    "https://example.invalid/notify", Route.DEFAULT_KEYWORDS, true,
                ),
            ),
        )
    }

    fun openTestDb(ctx: Context): PaylistDatabase =
        Room.databaseBuilder(ctx, PaylistDatabase::class.java, TEST_DB_NAME).build()

    fun newRepository(db: PaylistDatabase): EventRepository = EventRepository(
        db.eventDao(),
        db.deliveryDao(),
        db.captureStagingDao(),
        db.classificationDao(),
        db.transactionRunner(),
    )

    /**
     * The durable legacy enqueue: appends the chain item to the
     * Store legacy queue in SharedPreferences (deduped by qkey),
     * the same durable target the production LegacyEnqueue writes
     * through the Sender.
     */
    fun durableLegacyEnqueue(ctx: Context): LegacyEnqueue {
        val store = Store(ctx)
        return LegacyEnqueue { payload, kind, routeIds ->
            if (kind != LegacyQkey.KIND_CHAIN) {
                false
            } else {
                val qkey = LegacyQkey.build(
                    payload.optString("dedupe_key"), kind, routeIds,
                )
                val items = store.queueSnapshot()
                if (items.any { it.optString("qkey") == qkey }) {
                    true
                } else {
                    val item = JSONObject().apply {
                        put("kind", kind)
                        put("route_ids", JSONArray(routeIds))
                        put("next", 0)
                        put("payload", payload)
                        put("qkey", qkey)
                    }
                    store.saveQueueSync(items + item)
                }
            }
        }
    }

    /**
     * A CaptureCoordinator over the file-backed test DB, wired the
     * way PaylistApplication wires the production one (real
     * repository, real ConsentStore with the route's consent
     * granted, real PrivacyGate, SharedPreferences health store),
     * except: mode pinned LEGACY_ONLY (the pre-cutover initial mode),
     * routes from the seeded Store, and the caller's
     * [legacyEnqueue].
     */
    suspend fun newCoordinator(
        ctx: Context,
        db: PaylistDatabase,
        legacyEnqueue: LegacyEnqueue,
        executorCapacity: Int = CaptureCoordinator.EXECUTOR_CAPACITY,
        healthWriteGate: HealthWriteGate? = null,
    ): CaptureCoordinator {
        val store = Store(ctx)
        val consentStore = ConsentStore(db.collectorStateDao())
        consentStore.grant(ROUTE_ID)
        val healthPrefs = ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE)
        val lifecyclePrefs = ctx.getSharedPreferences(
            PaylistApplication.CAPTURE_LIFECYCLE_PREFS, Context.MODE_PRIVATE,
        )
        val journalPrefs = ctx.getSharedPreferences(
            PaylistApplication.CAPTURE_JOURNAL_PREFS, Context.MODE_PRIVATE,
        )
        // The production durability contract (PR #5 audit
        // 28 Nov): health writes are commit() — a durable
        // acknowledgement a SIGKILL cannot beat — never apply().
        // The lifecycle record and the offer journal (audit
        // 7235948077) follow the same contract in their own
        // preference files, exactly like production.
        val healthStore = object : CaptureHealthStore {
            override fun readCaptureHealth(): String? =
                healthPrefs.getString(PaylistApplication.CAPTURE_HEALTH_KEY, null)

            override fun writeCaptureHealth(json: String) {
                if (healthWriteGate != null) {
                    healthWriteGate.entered.countDown()
                    // Parked: this write does not complete until
                    // the gate opens. c08 never opens it — the
                    // kill is the exit — so the offered incident
                    // provably never crosses the durable boundary
                    // in PhaseA's process.
                    val deadline = System.currentTimeMillis() + 120_000L
                    while (System.currentTimeMillis() < deadline &&
                        healthWriteGate.release.count > 0L
                    ) {
                        try {
                            healthWriteGate.release.await(
                                50, java.util.concurrent.TimeUnit.MILLISECONDS,
                            )
                        } catch (_: InterruptedException) {
                            // Swallowed on purpose (parked write).
                        }
                    }
                }
                val ok = healthPrefs.edit()
                    .putString(PaylistApplication.CAPTURE_HEALTH_KEY, json).commit()
                check(ok) { "capture-health commit failed" }
            }

            override fun readCaptureLifecycle(): String? =
                lifecyclePrefs.getString(PaylistApplication.CAPTURE_LIFECYCLE_KEY, null)

            override fun writeCaptureLifecycle(json: String) {
                val ok = lifecyclePrefs.edit()
                    .putString(PaylistApplication.CAPTURE_LIFECYCLE_KEY, json).commit()
                check(ok) { "capture-lifecycle commit failed" }
            }

            override fun readCaptureHealthJournal(): String? =
                journalPrefs.getString(PaylistApplication.CAPTURE_JOURNAL_KEY, null)

            override fun writeCaptureHealthJournal(json: String) {
                val ok = journalPrefs.edit()
                    .putString(PaylistApplication.CAPTURE_JOURNAL_KEY, json).commit()
                check(ok) { "capture-health journal commit failed" }
            }
        }
        return CaptureCoordinator(
            repository = newRepository(db),
            modeReader = { CutoverMode.LEGACY_ONLY },
            legacyEnqueue = legacyEnqueue,
            routesProvider = { store.routes() },
            deviceIdProvider = { "capture-death-emulator" },
            logger = object : CaptureLogSink {
                override fun logEvent(pkg: String, text: String) =
                    store.logEvent(pkg, text)

                override fun logLocal(text: String) = store.logLocal(text)
            },
            privacyGate = PrivacyGate(consentStore),
            consentStore = consentStore,
            captureLogDao = db.captureLogDao(),
            executorCapacity = executorCapacity,
            healthStore = healthStore,
        )
    }

    private fun notificationManager(ctx: Context): NotificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Posts a REAL notification into the system shade. */
    fun postPaymentNotification(ctx: Context, id: Int, amountText: String) {
        val nm = notificationManager(ctx)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Capture death tests",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val notification = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pembayaran QRIS")
            .setContentText("Pembayaran QRIS $amountText dari Pelanggan berhasil")
            .setAutoCancel(false)
            .build()
        nm.notify(id, notification)
    }

    fun dismissNotification(ctx: Context, id: Int) {
        notificationManager(ctx).cancel(id)
    }

    /**
     * The ACTUAL rescan source: this app's notifications still
     * active in the system shade, mapped with the SAME
     * [NotificationNormalizer.from] the production listener's
     * `rescanActive` uses, in the same order.
     */
    fun activeSnapshots(ctx: Context): List<NotificationSnapshot> =
        notificationManager(ctx).activeNotifications
            .filter { it.packageName == "com.netter.paylist" }
            .map { NotificationNormalizer.from(it, System.currentTimeMillis()) }

    /** Polls [check] until it holds or the timeout fails the test. */
    fun awaitCondition(message: String, timeoutMs: Long = 20_000L, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { check() }.getOrDefault(false)) return
            Thread.sleep(100)
        }
        throw AssertionError("timeout menunggu: $message")
    }
}
