package com.netter.paylist.collector

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.PaylistApplication
import com.netter.paylist.PaylistListenerService
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.migration.LegacyQkey
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage D D1: the capture wiring against the REAL listener and the
 * REAL Room database (re-review #6 blocker B — not test doubles).
 *
 * A real `PaylistListenerService` instance (attached to the real
 * `PaylistApplication`) receives a real `StatusBarNotification`
 * built with `Notification.Builder`; the test polls the app's real
 * Room database for the event/delivery rows the capture executor
 * wrote, and the real legacy queue for the LEGACY_ONLY case.
 *
 * NO PRODUCTION TRAFFIC: the seeded route's URL is replaced with a
 * dead loopback URL (`http://127.0.0.1:9/…`) before each test, so
 * the legacy flush thread and the Room worker can only fail fast
 * against localhost — they can never POST a test payment to the
 * real billing endpoint.
 *
 * EXECUTION NOTE: this test needs a device or an emulator. It
 * COMPILES on laptopgrt2 (`compileDebugAndroidTestKotlin`) but
 * cannot execute there — there is no device/emulator on that box.
 * It runs in CI on the emulator. Do not report it as "passing"
 * from a compile-only gate.
 */
@RunWith(AndroidJUnit4::class)
class ListenerCaptureIntegrationTest {

    companion object {
        const val PKG = "com.gojek.gopaymerchant"
        /** Dead loopback URL: connection refused immediately, never a real POST. */
        const val DEAD_URL = "http://127.0.0.1:9/notify-test.php"
        const val POST_TIME = 1_760_000_000_000L
        const val POLL_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 100L
    }

    private lateinit var app: PaylistApplication
    private lateinit var service: TestableListenerService

    /** Exposes the protected `attachBaseContext` for the test. */
    class TestableListenerService : PaylistListenerService() {
        fun attachForTest(ctx: Context) {
            attachBaseContext(ctx)
        }
    }

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        // Fresh durable state: empty Room tables, seeded routes with
        // a dead URL, an empty legacy queue, LEGACY_ONLY mode.
        runCatching { app.db.clearAllTables() }
            .onFailure {
                // The app startup thread may hold a transaction;
                // one retry after a short pause.
                Thread.sleep(500)
                app.db.clearAllTables()
            }
        app.store.seedIfFirstRun()
        val routes = app.store.routes()
        val gopay = routes.first { it.packages.contains(PKG) }
        app.store.upsertRoute(gopay.copy(url = DEAD_URL))
        // D5 consent gate (F15): capture drops notifications for
        // routes without a granted consent record, before any parse
        // or durable write. In production the operator completes the
        // P6 opt-in (or the upgrade migration carries it forward);
        // the test process never launches MainActivity, so the
        // upgrade migration ran with no routes and granted nothing
        // — and setUp's clearAllTables() wipes the consent doc
        // anyway. Grant explicitly here, after every wipe.
        app.consentStore.grant(gopay.id)
        app.store.saveQueueSync(emptyList())
        setMode(CutoverMode.LEGACY_ONLY)

        service = TestableListenerService()
        service.attachForTest(app)
    }

    @After
    fun tearDown() {
        runBlocking {
            runCatching { app.db.clearAllTables() }
            app.store.saveQueueSync(emptyList())
        }
    }

    private suspend fun setMode(mode: CutoverMode) {
        app.db.collectorStateDao().put(
            CollectorStateEntity(
                CollectorStateKeys.CUTOVER_STATE,
                JSONObject().apply { put("mode", mode.name) }.toString(),
                System.currentTimeMillis(),
            )
        )
    }

    private fun postNotification(
        title: String = "Pembayaran berhasil",
        text: String = "Pembayaran Rp100.800 berhasil",
        postTime: Long = POST_TIME,
    ) {
        val notification = Notification.Builder(app, "paylist-test")
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(postTime)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        // API 34 constructor: (pkg, opPkg, id, tag, uid, initialPid,
        // userId, notification, user, postTime) — no overrideGroupKey.
        val sbn = StatusBarNotification(
            PKG, PKG, 1, null, 1000, 0, 0,
            notification, Process.myUserHandle(), postTime,
        )
        service.onNotificationPosted(sbn)
    }

    private fun expectedDedupe(
        title: String = "Pembayaran berhasil",
        text: String = "Pembayaran Rp100.800 berhasil",
        postTime: Long = POST_TIME,
    ): String {
        val combined = "$title $text"
        return CaptureCoordinator.legacyDedupeKey(PKG, postTime, 100800L, combined)
    }

    /** Polls until [check] returns true, or fails after the timeout. */
    private suspend fun await(message: String, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { check() }.getOrDefault(false)) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("timeout menunggu: $message")
    }

    @Test
    fun legacyOnly_realListenerCapture_landsInLegacyQueueAndEventRow() = runBlocking {
        postNotification()

        val dedupe = expectedDedupe()
        await("event row") { app.db.eventDao().getByDedupeKey(dedupe) != null }
        val event = app.db.eventDao().getByDedupeKey(dedupe)
        assertNotNull(event)
        assertEquals(100800L, JSONObject(event!!.dataJson).optLong("amount"))

        await("legacy queue item") { app.store.queueSnapshot().size == 1 }
        val item = app.store.queueSnapshot().single()
        assertEquals(dedupe, item.getJSONObject("payload").optString("dedupe_key"))

        // No Room delivery in LEGACY_ONLY; staging cleaned up.
        assertEquals(0, app.db.captureStagingDao().count())
        assertEquals(0, deliveryCount())
    }

    private suspend fun deliveryCount(): Int =
        app.db.deliveryDao().countByState().sumOf { it.count }

    @Test
    fun draining_realListenerCapture_landsInRoom_legacyQueueUntouched() = runBlocking {
        setMode(CutoverMode.DRAINING)
        postNotification()

        val dedupe = expectedDedupe()
        await("event row") { app.db.eventDao().getByDedupeKey(dedupe) != null }
        val routes = app.store.routes().filter { it.enabled && it.packages.contains(PKG) }
        val qkey = LegacyQkey.build(dedupe, LegacyQkey.KIND_CHAIN, routes.map { it.id })
        await("Room delivery") { app.db.deliveryDao().getByLegacyQkey(qkey) != null }
        val delivery = app.db.deliveryDao().getByLegacyQkey(qkey)
        assertNotNull(delivery)
        // The worker does not claim in DRAINING, so PENDING is stable here.
        assertEquals(DeliveryState.PENDING, delivery!!.state)
        assertEquals(0, app.store.queueSnapshot().size)
        assertEquals(0, app.db.captureStagingDao().count())
    }

    @Test
    fun workerOnly_realListenerCapture_landsInRoom() = runBlocking {
        setMode(CutoverMode.WORKER_ONLY)
        postNotification()

        val dedupe = expectedDedupe()
        await("event row") { app.db.eventDao().getByDedupeKey(dedupe) != null }
        val routes = app.store.routes().filter { it.enabled && it.packages.contains(PKG) }
        val qkey = LegacyQkey.build(dedupe, LegacyQkey.KIND_CHAIN, routes.map { it.id })
        await("Room delivery") { app.db.deliveryDao().getByLegacyQkey(qkey) != null }
        val delivery = app.db.deliveryDao().getByLegacyQkey(qkey)
        assertNotNull(delivery)
        // The worker may claim the delivery while the test polls, so
        // PENDING or IN_FLIGHT both prove the Room landing.
        assertTrue(
            "delivery must be PENDING or IN_FLIGHT, was ${delivery!!.state}",
            delivery.state == DeliveryState.PENDING || delivery.state == DeliveryState.IN_FLIGHT,
        )
        assertEquals(0, app.store.queueSnapshot().size)
    }

    @Test
    fun rescan_realListenerCaptureTwice_onlyOneEventOneDelivery() = runBlocking {
        setMode(CutoverMode.WORKER_ONLY)
        postNotification()
        postNotification()

        val dedupe = expectedDedupe()
        await("event row") { app.db.eventDao().getByDedupeKey(dedupe) != null }
        // Give the second (duplicate) capture time to settle, then
        // assert exactly-once.
        Thread.sleep(2_000)
        assertEquals(1, app.db.eventDao().count())
        assertEquals(1, deliveryCount())
        assertEquals(0, app.db.captureStagingDao().count())
    }
}
