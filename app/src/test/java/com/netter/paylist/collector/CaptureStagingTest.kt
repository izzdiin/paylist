package com.netter.paylist.collector

import com.netter.paylist.FakeCaptureStagingDao
import com.netter.paylist.FakeClassificationDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeStore
import com.netter.paylist.FakeTransactionRunner
import com.netter.paylist.Route
import com.netter.paylist.core.model.CanonicalEvent
import com.netter.paylist.core.model.EventData
import com.netter.paylist.core.model.EventMetadata
import com.netter.paylist.core.model.EventSchema
import com.netter.paylist.core.model.EventSource
import com.netter.paylist.data.EventRepository
import com.netter.paylist.data.entity.CaptureStagingEntity
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.migration.LegacyEnqueue
import com.netter.paylist.migration.LegacyQkey
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage D D1: capture-staging fixtures (design §12.3, F18 — the
 * capture-path cases).
 *
 * Covered here: atomic staging write; process death after staging
 * (sweep recovery); the staging-write re-scan gate (no class-(a)
 * span in any stored row, even under adversarial input); sweeper
 * re-promotion idempotence; `payload_sha256` mismatch → held;
 * executor burst (no loss, no duplicates).
 *
 * NOT covered here (they belong to the Sender flush restructure,
 * R13/R14, which Stage C did not land and which is outside the
 * Stage D D1–D3 scope): the promotion-receipt cases (`PREPARING`
 * never POSTed, `RESOLVED` dropped without POST, `READY` + absent
 * + no ack → held, crash after `saveQueueSync` read-back before
 * `READY` write, receipt reuse on re-promotion, no `PREPARING`
 * receipt at snapshot time) and F22 (staging promotion with a
 * flush running). Those cases need the restructured flush and are
 * tracked as open work, not claimed here.
 *
 * F28/F31/F36/F37a/F37b are owned by `CutoverTest` (already
 * implemented there); this file does not duplicate them.
 */
class CaptureStagingTest {

    companion object {
        const val PKG = "com.gojek.gopaymerchant"
        const val ROUTE_ID = "route-v2new"
        const val POST_TIME = 1_760_000_000_000L
    }

    private lateinit var store: FakeStore
    private lateinit var eventDao: FakeEventDao
    private lateinit var deliveryDao: FakeDeliveryDao
    private lateinit var stagingDao: FakeCaptureStagingDao
    private lateinit var repository: EventRepository
    private lateinit var legacyQueue: MutableList<JSONObject>
    private lateinit var logLines: MutableList<String>
    private var mode: CutoverMode = CutoverMode.LEGACY_ONLY
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
        stagingDao = FakeCaptureStagingDao(store)
        repository = EventRepository(
            eventDao, deliveryDao, stagingDao,
            FakeClassificationDao(store), FakeTransactionRunner(store),
        )
        legacyQueue = mutableListOf()
        logLines = mutableListOf()
        coordinator = CaptureCoordinator(
            repository = repository,
            modeReader = { mode },
            legacyEnqueue = LegacyEnqueue { payload, kind, routeIds ->
                val qkey = LegacyQkey.build(payload.optString("dedupe_key"), kind, routeIds)
                if (legacyQueue.none { it.optString("qkey") == qkey }) {
                    legacyQueue.add(JSONObject().apply {
                        put("kind", kind)
                        put("payload", payload)
                        put("qkey", qkey)
                    })
                }
                true
            },
            routesProvider = { routes },
            deviceIdProvider = { "g57" },
            logger = object : CaptureLogSink {
                override fun logEvent(pkg: String, text: String) {
                    logLines.add("$pkg: $text")
                }
                override fun logLocal(text: String) {
                    logLines.add(text)
                }
            },
            clock = { POST_TIME + 5_000L },
        )
    }

    @After
    fun tearDown() {
        coordinator.close()
    }

    private fun snapshot(
        title: String = "Pembayaran berhasil",
        text: String = "Pembayaran Rp100.800 berhasil",
        bigText: String = "",
        postTime: Long = POST_TIME,
    ) = NotificationSnapshot(
        key = "key-$postTime",
        packageName = PKG,
        postTimeMs = postTime,
        flags = 0,
        title = title,
        text = text,
        bigText = bigText,
        capturedAtMs = postTime + 1_000L,
    )

    private fun captureAndWait(s: NotificationSnapshot) {
        coordinator.capture(s)
        coordinator.sweepNow()
    }

    /** F18: the staging write is atomic — event + staging rows land together or not at all. */
    @Test
    fun f18_stagingWriteAtomic_eventInsertFailureLeavesNothing() = runBlocking {
        eventDao.failNextInsert = true
        captureAndWait(snapshot())

        assertEquals(0, eventDao.count())
        assertEquals(0, stagingDao.count())
        assertEquals(0, legacyQueue.size)
        assertTrue("failure is surfaced, never silent", coordinator.counters().parked >= 1)
    }

    /** F18: staging-write re-scan gate — no class-(a) span in any stored row, adversarial input. */
    @Test
    fun f18_rescanGate_noSecretInAnyStoredRow() = runBlocking {
        mode = CutoverMode.WORKER_ONLY
        val otp = "739201"
        val pin = "8842"
        captureAndWait(
            snapshot(
                title = "Kode OTP $otp",
                text = "PIN $pin. Pembayaran Rp100.800 berhasil. Token ab12cd34",
                bigText = "Kode verifikasi 556677 untuk pembayaran Rp100.800",
            ),
        )

        // Scan every stored row for every secret span.
        val allStored = buildString {
            for (event in store.events.values) {
                append(event.dataJson)
                append(event.metadataJson)
                append(event.legacyPayloadJson)
            }
            for (delivery in store.deliveries.values) {
                append(delivery.legacyQkey)
            }
            for (row in store.staging.values) {
                append(row.legacyQkey)
                append(row.dedupeKey)
            }
        }
        assertFalse("OTP must not be stored", allStored.contains(otp))
        assertFalse("PIN must not be stored", allStored.contains(pin))
        assertFalse("auth token must not be stored", allStored.contains("ab12cd34"))
        assertFalse("verification code must not be stored", allStored.contains("556677"))
        // The payment itself still landed (financial record preserved, D8).
        assertEquals(1, eventDao.count())
    }

    /** F18: process death after staging — the sweep re-promotes the orphan exactly once. */
    @Test
    fun f18_deathAfterStaging_sweepRecoversExactlyOnce() = runBlocking {
        mode = CutoverMode.WORKER_ONLY
        val dedupe = CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, 100800L, snapshot().combined)
        val qkey = LegacyQkey.build(dedupe, LegacyQkey.KIND_CHAIN, listOf(ROUTE_ID))
        val payload = JSONObject().apply {
            put("type", "payment")
            put("device", "g57")
            put("pkg", PKG)
            put("amount", 100800L)
            put("title", "Pembayaran berhasil")
            put("text", "Pembayaran Rp100.800 berhasil")
            put("dedupe_key", dedupe)
        }
        val event = CanonicalEvent(
            eventId = "evt-1",
            sourceEventId = "$PKG|$POST_TIME",
            type = EventSchema.TYPE_PAYMENT_NOTIFICATION,
            category = EventSchema.CATEGORY_PAYMENT,
            source = EventSource(EventSchema.SOURCE_TYPE_ANDROID_NOTIFICATION, "g57", PKG),
            occurredAtMs = POST_TIME,
            capturedAtMs = POST_TIME + 1_000L,
            data = EventData(100800L, "IDR"),
            metadata = EventMetadata("payment-v1", "structured"),
            dedupeKey = dedupe,
            contentSha256 = CaptureCoordinator.sha256Hex(snapshot().combined),
            exportRef = "exp-1",
            sourceAccountKey = null,
            legacyPayloadJson = payload.toString(),
            retentionUntilMs = null,
        )
        repository.stageCapture(
            event,
            listOf(
                CaptureStagingEntity(
                    legacyQkey = qkey,
                    dedupeKey = dedupe,
                    contentSha256 = event.contentSha256,
                    payloadSha256 = CaptureCoordinator.sha256Hex(payload.toString()),
                    consentEpoch = 0,
                    configEpoch = 0,
                    createdAtMs = POST_TIME,
                ),
            ),
        )
        assertEquals(1, stagingDao.count())

        coordinator.sweepNow()
        assertNotNullDelivery(qkey)
        assertEquals(0, stagingDao.count())
        coordinator.sweepNow()
        assertEquals("second sweep must not duplicate", 1, store.deliveries.size)
    }

    /** F18: `payload_sha256` mismatch → held `staging_mismatch`, never dispatched. */
    @Test
    fun f18_payloadMismatch_heldNeverDispatched() = runBlocking {
        mode = CutoverMode.WORKER_ONLY
        val dedupe = CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, 100800L, snapshot().combined)
        val qkey = LegacyQkey.build(dedupe, LegacyQkey.KIND_CHAIN, listOf(ROUTE_ID))
        val payload = JSONObject().apply {
            put("type", "payment")
            put("device", "g57")
            put("pkg", PKG)
            put("amount", 100800L)
            put("title", "Pembayaran berhasil")
            put("text", "Pembayaran Rp100.800 berhasil")
            put("dedupe_key", dedupe)
        }
        val event = CanonicalEvent(
            eventId = "evt-1",
            sourceEventId = "$PKG|$POST_TIME",
            type = EventSchema.TYPE_PAYMENT_NOTIFICATION,
            category = EventSchema.CATEGORY_PAYMENT,
            source = EventSource(EventSchema.SOURCE_TYPE_ANDROID_NOTIFICATION, "g57", PKG),
            occurredAtMs = POST_TIME,
            capturedAtMs = POST_TIME + 1_000L,
            data = EventData(100800L, "IDR"),
            metadata = EventMetadata("payment-v1", "structured"),
            dedupeKey = dedupe,
            contentSha256 = CaptureCoordinator.sha256Hex(snapshot().combined),
            exportRef = "exp-1",
            sourceAccountKey = null,
            // The stored payload differs from the staged hash below.
            legacyPayloadJson = payload.toString().replace("100.800", "999.999"),
            retentionUntilMs = null,
        )
        repository.stageCapture(
            event,
            listOf(
                CaptureStagingEntity(
                    legacyQkey = qkey,
                    dedupeKey = dedupe,
                    contentSha256 = event.contentSha256,
                    payloadSha256 = CaptureCoordinator.sha256Hex(payload.toString()),
                    consentEpoch = 0,
                    configEpoch = 0,
                    createdAtMs = POST_TIME,
                ),
            ),
        )

        coordinator.sweepNow()
        assertEquals("mismatched staging must not promote", 0, store.deliveries.size)
        assertEquals("mismatched row is retained for inspection", 1, stagingDao.count())
        assertEquals(1, coordinator.counters().stagingMismatch)
        assertTrue(logLines.any { it.contains("staging_mismatch") })
    }

    /** F18: executor burst — 50 rapid captures below capacity land exactly once each. */
    @Test
    fun f18_executorBurst_belowCapacity_allLandExactlyOnce() = runBlocking {
        val n = 50
        for (i in 0 until n) {
            coordinator.capture(snapshot(postTime = POST_TIME + i * 1_000L))
        }
        coordinator.sweepNow()

        assertEquals(n, eventDao.count())
        assertEquals(n, legacyQueue.size)
        assertEquals(0, stagingDao.count())
        assertEquals(0, coordinator.counters().droppedOverflow)
    }

    private suspend fun assertNotNullDelivery(qkey: String) {
        val delivery = deliveryDao.getByLegacyQkey(qkey)
        assertTrue("delivery must exist for $qkey", delivery != null)
    }
}
