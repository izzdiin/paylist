package com.netter.paylist.collector

import com.netter.paylist.FakeCaptureStagingDao
import com.netter.paylist.FakeClassificationDao
import com.netter.paylist.FakeCollectorStateDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeStore
import com.netter.paylist.FakeTransactionRunner
import com.netter.paylist.Route
import com.netter.paylist.data.EventRepository
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.migration.LegacyEnqueue
import com.netter.paylist.migration.LegacyQkey
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage D D2: the Option B capture-side cases (design §10.2, F43
 * B1/B1b/B5a).
 *
 * B1 — B route, valid opt-in, mixed financial+OTP event ⇒ the raw
 * snapshot is retained in memory, the delivery is flagged
 * `raw_ephemeral`, and the staged/outbox rows contain no raw OTP
 * (asserted by a database dump scan). The "POST carries raw" half
 * is owned by the claim-path tests (fake transport records the
 * body).
 * B1b — pure-auth event on a B route ⇒ dropped at capture,
 * diagnostic counter only, no POST, no raw snapshot retained.
 * B5a — opt-in expired at capture ⇒ A′ from the start (never a B
 * delivery): no snapshot, no `raw_ephemeral` flag.
 */
class CaptureBFlowTest {

    companion object {
        const val PKG = "com.gojek.gopaymerchant"
        const val ROUTE_ID = "route-v2new"
        const val POST_TIME = 1_760_000_000_000L
        const val NOW = POST_TIME + 5_000L
    }

    private lateinit var store: FakeStore
    private lateinit var eventDao: FakeEventDao
    private lateinit var deliveryDao: FakeDeliveryDao
    private lateinit var repository: EventRepository
    private lateinit var consentStore: ConsentStore
    private lateinit var snapshotStore: RawSnapshotStore
    private lateinit var coordinator: CaptureCoordinator

    private val routes = listOf(
        Route(
            id = ROUTE_ID,
            name = "v2New (Netter)",
            packages = listOf(PKG),
            url = "https://bill.netterhome.net/bayarQR/notify.php",
            keywords = Route.DEFAULT_KEYWORDS,
            enabled = true,
        )
    )

    @Before
    fun setUp() {
        store = FakeStore()
        eventDao = FakeEventDao(store)
        deliveryDao = FakeDeliveryDao(store)
        repository = EventRepository(
            eventDao, deliveryDao, FakeCaptureStagingDao(store),
            FakeClassificationDao(store), FakeTransactionRunner(store),
        )
        consentStore = ConsentStore(FakeCollectorStateDao(store), clock = { NOW })
        snapshotStore = RawSnapshotStore()
        coordinator = CaptureCoordinator(
            repository = repository,
            modeReader = { CutoverMode.WORKER_ONLY },
            legacyEnqueue = LegacyEnqueue { _, _, _ -> true },
            routesProvider = { routes },
            deviceIdProvider = { "g57" },
            logger = object : CaptureLogSink {
                override fun logEvent(pkg: String, text: String) {}
                override fun logLocal(text: String) {}
            },
            clock = { NOW },
            privacyGate = PrivacyGate(consentStore),
            rawSnapshotStore = snapshotStore,
            consentStore = consentStore,
        )
    }

    @After
    fun tearDown() {
        coordinator.close()
    }

    private fun snapshot(
        title: String = "Pembayaran berhasil",
        text: String = "Kode OTP 482913. Pembayaran Rp100.800 berhasil",
        postTime: Long = POST_TIME,
    ) = NotificationSnapshot(
        key = "key-$postTime",
        packageName = PKG,
        postTimeMs = postTime,
        flags = 0,
        title = title,
        text = text,
        bigText = "",
        capturedAtMs = postTime + 1_000L,
    )

    private fun captureAndWait(s: NotificationSnapshot) {
        coordinator.capture(s)
        coordinator.sweepNow()
    }

    @Test
    fun b1_mixedEventOnBRoute_snapshotRetained_deliveryFlagged_noRawInRows() = runBlocking {
        consentStore.grant(ROUTE_ID)
        consentStore.setRawOptIn(ROUTE_ID, true, NOW + 3_600_000L)
        val epoch = consentStore.epoch(ROUTE_ID)

        captureAndWait(snapshot())

        val dedupe = CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, 100800L, snapshot().combined)
        // The raw snapshot is retained in memory, keyed by
        // (dedupe_key, route_id, consent epoch).
        val raw = snapshotStore.peek(dedupe, ROUTE_ID, epoch)
        assertNotNull("raw snapshot must be retained for a B delivery", raw)
        assertTrue(raw!!.text.contains("482913"))

        // The delivery is flagged raw_ephemeral.
        val qkey = LegacyQkey.build(dedupe, LegacyQkey.KIND_CHAIN, listOf(ROUTE_ID))
        val delivery = deliveryDao.getByLegacyQkey(qkey)
        assertNotNull(delivery)
        assertTrue(delivery!!.rawEphemeral)

        // No durable row contains the raw OTP (database dump scan).
        val allStored = buildString {
            for (event in store.events.values) {
                append(event.dataJson)
                append(event.metadataJson)
                append(event.legacyPayloadJson)
            }
            for (row in store.staging.values) {
                append(row.legacyQkey)
                append(row.dedupeKey)
            }
            for (doc in store.collector.values) append(doc.valueJson)
        }
        assertFalse("no raw OTP in any durable row", allStored.contains("482913"))
    }

    @Test
    fun b1b_pureAuthOnBRoute_dropped_noSnapshot() = runBlocking {
        consentStore.grant(ROUTE_ID)
        consentStore.setRawOptIn(ROUTE_ID, true, NOW + 3_600_000L)

        captureAndWait(
            snapshot(
                title = "Kode verifikasi",
                // "pembayaran" carries the keyword so the notification
                // reaches the gate (without a keyword it is dropped
                // earlier as no-keyword, never reaching the gate).
                text = "Kode OTP Anda 739201 untuk pembayaran. Jangan berikan kepada siapa pun.",
            ),
        )

        assertEquals(0, eventDao.count())
        assertEquals(0, snapshotStore.size())
        assertEquals(1, coordinator.counters().pureAuth)
    }

    @Test
    fun b5a_optInExpiredAtCapture_aPrimeFromStart() = runBlocking {
        consentStore.grant(ROUTE_ID)
        consentStore.setRawOptIn(ROUTE_ID, true, NOW - 1)

        captureAndWait(snapshot())

        val dedupe = CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, 100800L, snapshot().combined)
        val qkey = LegacyQkey.build(dedupe, LegacyQkey.KIND_CHAIN, listOf(ROUTE_ID))
        val delivery = deliveryDao.getByLegacyQkey(qkey)
        assertNotNull("the financial record is still captured (A′)", delivery)
        assertFalse("never a B delivery", delivery!!.rawEphemeral)
        assertEquals(0, snapshotStore.size())
    }

    @Test
    fun b2_sameEventNonBRoute_noSnapshot() = runBlocking {
        // Consent granted, but NO P6 opt-in: the route is not a B route.
        consentStore.grant(ROUTE_ID)

        captureAndWait(snapshot())

        val dedupe = CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, 100800L, snapshot().combined)
        val qkey = LegacyQkey.build(dedupe, LegacyQkey.KIND_CHAIN, listOf(ROUTE_ID))
        val delivery = deliveryDao.getByLegacyQkey(qkey)
        assertNotNull(delivery)
        assertFalse(delivery!!.rawEphemeral)
        assertEquals(0, snapshotStore.size())
    }
}
