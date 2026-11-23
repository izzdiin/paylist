package com.netter.paylist.collector

import com.netter.paylist.FakeCaptureStagingDao
import com.netter.paylist.FakeClassificationDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeStore
import com.netter.paylist.FakeTransactionRunner
import com.netter.paylist.Route
import com.netter.paylist.core.model.DeliveryState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage D D1: the capture executor wires the listener to Room
 * (design §8.3 C21, reviewer blocker B).
 *
 * The tests drive the REAL entrypoint — [CaptureCoordinator.capture]
 * — and synchronise on [CaptureCoordinator.sweepNow], which runs
 * on the same single-thread executor FIFO after the capture, so
 * every assertion observes the capture's completed effect.
 *
 * The instrumented twin of these cases
 * (`ListenerCaptureIntegrationTest`, androidTest) posts a real
 * notification through the real `PaylistListenerService` on an
 * emulator; these unit tests pin the executor/mode/staging rules
 * on the JVM.
 */
class CaptureCoordinatorTest {

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
    private lateinit var legacyEnqueue: LegacyEnqueue
    private lateinit var logLines: MutableList<String>
    private var mode: CutoverMode = CutoverMode.LEGACY_ONLY
    private var legacyShouldFail: Boolean = false
    private var syncRequests: Int = 0
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
        legacyEnqueue = LegacyEnqueue { payload, kind, routeIds ->
            if (legacyShouldFail) return@LegacyEnqueue false
            val qkey = LegacyQkey.build(
                payload.optString("dedupe_key"), kind, routeIds,
            )
            if (legacyQueue.none { it.optString("qkey") == qkey }) {
                legacyQueue.add(JSONObject().apply {
                    put("kind", kind)
                    put("route_ids", org.json.JSONArray(routeIds))
                    put("next", 0)
                    put("payload", payload)
                    put("qkey", qkey)
                })
            }
            true
        }
        coordinator = newCoordinator()
    }

    @After
    fun tearDown() {
        coordinator.close()
    }

    private fun newCoordinator(): CaptureCoordinator = CaptureCoordinator(
        repository = repository,
        modeReader = { mode },
        legacyEnqueue = legacyEnqueue,
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
        onRoomEnqueued = { syncRequests++ },
    )

    private fun snapshot(
        title: String = "Pembayaran berhasil",
        text: String = "Pembayaran Rp100.800 berhasil",
        bigText: String = "",
        pkg: String = PKG,
        postTime: Long = POST_TIME,
        flags: Int = 0,
    ) = NotificationSnapshot(
        key = "key-$postTime",
        packageName = pkg,
        postTimeMs = postTime,
        flags = flags,
        title = title,
        text = text,
        bigText = bigText,
        capturedAtMs = postTime + 1_000L,
    )

    /** Drives one capture through the real entrypoint and waits for it. */
    private fun captureAndWait(s: NotificationSnapshot) {
        coordinator.capture(s)
        coordinator.sweepNow()
    }

    // --- Mode-gated enqueue (exactly one store) ---

    @Test
    fun legacyOnly_captureLandsInLegacyQueue_eventRowExists() = runBlocking {
        captureAndWait(snapshot())

        val dedupe = CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, 100800L, snapshot().combined)
        val event = eventDao.getByDedupeKey(dedupe)
        assertNotNull("event row must exist", event)
        assertEquals(100800L, event!!.dataJson.let { JSONObject(it).optLong("amount") })
        assertEquals(1, legacyQueue.size)
        assertEquals(dedupe, legacyQueue[0].getJSONObject("payload").optString("dedupe_key"))
        assertEquals(0, stagingDao.count())
        assertNull("no Room delivery in LEGACY_ONLY", deliveryDao.getByLegacyQkey(legacyQueue[0].optString("qkey")))
        assertEquals(1, coordinator.counters().enqueued)
    }

    @Test
    fun draining_captureLandsInRoom_legacyQueueUntouched() = runBlocking {
        mode = CutoverMode.DRAINING
        captureAndWait(snapshot())

        val dedupe = CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, 100800L, snapshot().combined)
        val qkey = LegacyQkey.build(dedupe, LegacyQkey.KIND_CHAIN, listOf(ROUTE_ID))
        val delivery = deliveryDao.getByLegacyQkey(qkey)
        assertNotNull("Room delivery must exist", delivery)
        assertEquals(DeliveryState.PENDING, delivery!!.state)
        assertEquals(0, legacyQueue.size)
        assertEquals(0, stagingDao.count())
        assertEquals(1, coordinator.counters().roomCapturesDuringDraining)
        assertEquals("no sync request while DRAINING (worker does not claim yet)", 0, syncRequests)
    }

    @Test
    fun workerOnly_captureLandsInRoom_andRequestsSync() = runBlocking {
        mode = CutoverMode.WORKER_ONLY
        captureAndWait(snapshot())

        val dedupe = CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, 100800L, snapshot().combined)
        val qkey = LegacyQkey.build(dedupe, LegacyQkey.KIND_CHAIN, listOf(ROUTE_ID))
        assertNotNull(deliveryDao.getByLegacyQkey(qkey))
        assertEquals(0, legacyQueue.size)
        assertEquals(1, syncRequests)
    }

    // --- Dedupe / collision (§3 R11, §6 rescan rule) ---

    @Test
    fun rescanDuplicate_noSecondEvent_noSecondDelivery() = runBlocking {
        mode = CutoverMode.WORKER_ONLY
        captureAndWait(snapshot())
        captureAndWait(snapshot())

        assertEquals(1, eventDao.count())
        assertEquals(1, store.deliveries.size)
        assertEquals(0, stagingDao.count())
        assertEquals(1, coordinator.counters().duplicates)
    }

    @Test
    fun collision_firstEventKept_noDelivery() = runBlocking {
        mode = CutoverMode.WORKER_ONLY
        captureAndWait(snapshot())
        // Same dedupe_key, different content: same postTime + amount
        // but a different first-40-chars hash cannot collide by
        // accident — force it by reusing the same combined prefix
        // with different trailing text past char 40.
        val other = snapshot(
            text = "Pembayaran Rp100.800 berhasil. Catatan lain yang berbeda total",
        )
        // Both combined texts share the first 40 chars only if the
        // prefix matches; assert the test setup really collides.
        val dedupe1 = CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, 100800L, snapshot().combined)
        val dedupe2 = CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, 100800L, other.combined)
        assertEquals("test setup must produce the same dedupe_key", dedupe1, dedupe2)
        captureAndWait(other)

        assertEquals(1, eventDao.count())
        assertEquals("collision creates no second delivery", 1, store.deliveries.size)
        assertEquals(1, coordinator.counters().identityCollision)
    }

    // --- Filter outcomes (v2.3 rules, unchanged) ---

    @Test
    fun unlistedPackage_noEvent_counterAndLog() = runBlocking {
        captureAndWait(snapshot(pkg = "com.example.other"))
        assertEquals(0, eventDao.count())
        assertEquals(0, legacyQueue.size)
        assertEquals(1, coordinator.counters().unlistedPackage)
        assertTrue(logLines.any { it.contains("paket-terlihat") })
    }

    @Test
    fun noKeyword_noEvent_counterAndLog() = runBlocking {
        captureAndWait(snapshot(title = "Promo", text = "Diskon 50% hari ini"))
        assertEquals(0, eventDao.count())
        assertEquals(1, coordinator.counters().noKeyword)
        assertTrue(logLines.any { it.contains("target-lain") })
    }

    @Test
    fun noAmount_noEvent_counterAndLog() = runBlocking {
        captureAndWait(snapshot(text = "Pembayaran berhasil tanpa nominal"))
        assertEquals(0, eventDao.count())
        assertEquals(1, coordinator.counters().noAmount)
        assertTrue(logLines.any { it.contains("target-tanpa-nominal") })
    }

    @Test
    fun groupSummary_ignored() = runBlocking {
        captureAndWait(
            snapshot(flags = android.app.Notification.FLAG_GROUP_SUMMARY),
        )
        assertEquals(0, eventDao.count())
        assertEquals(0, legacyQueue.size)
    }

    // --- Amount ambiguity (§7.6) ---

    @Test
    fun twoAmounts_deliveryNeedsReview_neverPending() = runBlocking {
        mode = CutoverMode.WORKER_ONLY
        captureAndWait(snapshot(text = "Saldo Rp500.000. Pembayaran Rp100.000 berhasil"))

        val dedupe = CaptureCoordinator.legacyDedupeKey(
            PKG, POST_TIME, 500000L,
            snapshot(text = "Saldo Rp500.000. Pembayaran Rp100.000 berhasil").combined,
        )
        val qkey = LegacyQkey.build(dedupe, LegacyQkey.KIND_CHAIN, listOf(ROUTE_ID))
        val delivery = deliveryDao.getByLegacyQkey(qkey)
        assertNotNull(delivery)
        assertEquals(DeliveryState.NEEDS_REVIEW, delivery!!.state)
        val event = eventDao.getByDedupeKey(dedupe)!!
        val payload = JSONObject(event.legacyPayloadJson!!)
        assertTrue(payload.optBoolean("amount_ambiguous"))
        assertEquals(2, payload.getJSONArray("amount_tokens").length())
    }

    // --- Privacy staging gate (§8.3 R11, §7.1) ---

    @Test
    fun otpSpan_redactedInStoredPayload_neverRaw() = runBlocking {
        mode = CutoverMode.WORKER_ONLY
        captureAndWait(
            snapshot(text = "Kode OTP 482913. Pembayaran Rp100.800 berhasil"),
        )

        val dedupe = CaptureCoordinator.legacyDedupeKey(
            PKG, POST_TIME, 100800L,
            snapshot(text = "Kode OTP 482913. Pembayaran Rp100.800 berhasil").combined,
        )
        val event = eventDao.getByDedupeKey(dedupe)
        assertNotNull(event)
        val payloadJson = event!!.legacyPayloadJson!!
        assertFalse("raw OTP must never be stored", payloadJson.contains("482913"))
        assertTrue(payloadJson.contains("[REDACTED]"))
    }

    // --- Staging durability + sweep (process-restart simulation) ---

    @Test
    fun processRestartBetweenStagingAndEnqueue_sweepRecoversExactlyOnce() = runBlocking {
        mode = CutoverMode.WORKER_ONLY
        // Fail the legacy path is N/A in WORKER_ONLY; instead simulate
        // the crash by staging directly, then "restarting" the
        // coordinator (a new instance over the same store).
        val dedupe = CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, 100800L, snapshot().combined)
        val qkey = LegacyQkey.build(dedupe, LegacyQkey.KIND_CHAIN, listOf(ROUTE_ID))
        val event = com.netter.paylist.core.model.CanonicalEvent(
            eventId = "evt-1",
            sourceEventId = "$PKG|$POST_TIME",
            type = com.netter.paylist.core.model.EventSchema.TYPE_PAYMENT_NOTIFICATION,
            category = com.netter.paylist.core.model.EventSchema.CATEGORY_PAYMENT,
            source = com.netter.paylist.core.model.EventSource(
                com.netter.paylist.core.model.EventSchema.SOURCE_TYPE_ANDROID_NOTIFICATION,
                "g57", PKG,
            ),
            occurredAtMs = POST_TIME,
            capturedAtMs = POST_TIME + 1_000L,
            data = com.netter.paylist.core.model.EventData(100800L, "IDR"),
            metadata = com.netter.paylist.core.model.EventMetadata("payment-v1", "structured"),
            dedupeKey = dedupe,
            contentSha256 = CaptureCoordinator.sha256Hex(snapshot().combined),
            exportRef = "exp-1",
            sourceAccountKey = null,
            legacyPayloadJson = JSONObject().apply {
                put("type", "payment")
                put("device", "g57")
                put("pkg", PKG)
                put("amount", 100800L)
                put("title", "Pembayaran berhasil")
                put("text", "Pembayaran Rp100.800 berhasil")
                put("dedupe_key", dedupe)
            }.toString(),
            retentionUntilMs = null,
        )
        repository.stageCapture(
            event,
            listOf(
                com.netter.paylist.data.entity.CaptureStagingEntity(
                    legacyQkey = qkey,
                    dedupeKey = dedupe,
                    contentSha256 = event.contentSha256,
                    payloadSha256 = null,
                    consentEpoch = 0,
                    configEpoch = 0,
                    createdAtMs = POST_TIME,
                ),
            ),
        )
        // "Process death": drop the old coordinator, build a new one
        // over the same durable store, and sweep.
        coordinator.close()
        coordinator = newCoordinator()
        coordinator.sweepNow()

        val delivery = deliveryDao.getByLegacyQkey(qkey)
        assertNotNull("sweep must promote the orphaned staging row", delivery)
        assertEquals(DeliveryState.PENDING, delivery!!.state)
        assertEquals(0, stagingDao.count())
        // A second sweep is a no-op: no duplicate delivery.
        coordinator.sweepNow()
        assertEquals(1, store.deliveries.size)
    }

    @Test
    fun legacyEnqueueFailure_stagingRetained_thenSweepSucceeds() = runBlocking {
        legacyShouldFail = true
        captureAndWait(snapshot())
        assertEquals("staging row retained on failure", 1, stagingDao.count())
        assertEquals(0, legacyQueue.size)
        assertTrue(coordinator.counters().parked >= 1)

        legacyShouldFail = false
        coordinator.sweepNow()
        assertEquals(1, legacyQueue.size)
        assertEquals(0, stagingDao.count())
    }

    @Test
    fun sweepSkipsAlreadyPromotedRow_noDuplicateDelivery() = runBlocking {
        mode = CutoverMode.WORKER_ONLY
        captureAndWait(snapshot())
        // Manually re-insert the staging row (as a crash between
        // promotion and staging-delete would leave it — impossible
        // in one transaction, but the sweep must still be safe).
        val dedupe = CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, 100800L, snapshot().combined)
        val qkey = LegacyQkey.build(dedupe, LegacyQkey.KIND_CHAIN, listOf(ROUTE_ID))
        stagingDao.insert(
            com.netter.paylist.data.entity.CaptureStagingEntity(
                legacyQkey = qkey,
                dedupeKey = dedupe,
                contentSha256 = null,
                payloadSha256 = null,
                consentEpoch = 0,
                configEpoch = 0,
                createdAtMs = POST_TIME,
            ),
        )
        coordinator.sweepNow()
        assertEquals(1, store.deliveries.size)
        assertEquals(0, stagingDao.count())
    }

    // --- §7.6: amount-ambiguous is NEVER enqueued to the legacy sender ---

    @Test
    fun legacyOnly_ambiguousCapture_promotesToRoomNeedsReview_neverLegacy() = runBlocking {
        // Two differing amounts → amount_ambiguous, in LEGACY_ONLY mode.
        captureAndWait(snapshot(text = "Pembayaran Rp100.800 berhasil, saldo Rp50.000"))

        val dedupe = CaptureCoordinator.legacyDedupeKey(
            PKG, POST_TIME, 100800L,
            snapshot(text = "Pembayaran Rp100.800 berhasil, saldo Rp50.000").combined,
        )
        val event = eventDao.getByDedupeKey(dedupe)
        assertNotNull("event row must exist", event)
        assertEquals(
            "ambiguous event must never reach the legacy queue in any mode",
            0, legacyQueue.size,
        )
        assertEquals(0, stagingDao.count())
        val deliveries = deliveryDao.getByEventId(event!!.eventId)
        assertEquals(1, deliveries.size)
        assertEquals(DeliveryState.NEEDS_REVIEW, deliveries[0].state)
        // The event carries the token list for the review surface.
        val payload = JSONObject(event.legacyPayloadJson!!)
        assertTrue(payload.optBoolean("amount_ambiguous"))
        val tokens = payload.optJSONArray("amount_tokens")!!
        assertEquals(2, tokens.length())
        assertEquals(100800L, tokens.getJSONObject(0).optLong("value"))
        assertEquals(50000L, tokens.getJSONObject(1).optLong("value"))
    }
}
