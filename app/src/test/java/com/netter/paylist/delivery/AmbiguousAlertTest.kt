package com.netter.paylist.delivery

import com.netter.paylist.FakeAttemptDao
import com.netter.paylist.FakeCaptureLogDao
import com.netter.paylist.FakeCollectorStateDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeStore
import com.netter.paylist.FakeTransactionRunner
import com.netter.paylist.Route
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.CaptureLogEntity
import com.netter.paylist.data.entity.CaptureOutcome
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.history.HistoryRepository
import com.netter.paylist.security.SecretProvider
import com.netter.paylist.security.SecretResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * C1 ambiguous-case alert (user decision 2026-11-23): the Room
 * worker path fires `onAmbiguous` when the server answers
 * `match.status == 'ambiguous'`; the badge counts unacknowledged
 * ambiguous cases on both paths (Room deliveries + legacy
 * collector records); the operator's acknowledgement clears the
 * badge. The legacy sender path is covered by the instrumented
 * `SenderBFlushTest` (Sender needs a real Context); the
 * notification itself by the instrumented `ReviewNotifierTest`.
 */
class AmbiguousAlertTest {

    companion object {
        const val PKG = "com.gojek.gopay"
        const val NOW = 1_794_960_000_000L
    }

    private class FakeRoutes(var routesList: List<Route>) : RouteSource {
        override fun routes(): List<Route> = routesList
        override fun routeById(id: String): Route? = routesList.firstOrNull { it.id == id }
        override fun deviceLabel(): String = "Moto g57"
    }

    private class FakeSecrets(private val map: Map<String, SecretResult>) : SecretProvider {
        override fun secretFor(routeId: String): SecretResult =
            map[routeId] ?: SecretResult.Missing
    }

    private class FakeTransport : TransportClient {
        var script: (TransportRequest) -> TransportResult = {
            TransportResult.Http(200, emptyMap(), """{"ok":true,"match":{"status":"ambiguous"}}""")
        }

        override suspend fun post(
            request: TransportRequest,
            onSocketOpen: suspend () -> Boolean,
        ): TransportResult {
            if (!onSocketOpen()) return TransportResult.CheckpointFailed
            return script(request)
        }
    }

    private inner class Harness {
        val store = FakeStore()
        val eventDao = FakeEventDao(store)
        val deliveryDao = FakeDeliveryDao(store)
        val attemptDao = FakeAttemptDao(store)
        val captureLogDao = FakeCaptureLogDao(store)
        val collectorDao = FakeCollectorStateDao(store)
        val transactions = FakeTransactionRunner(store)
        val routes = FakeRoutes(emptyList())
        var secretsMap: Map<String, SecretResult> = emptyMap()
        val transport = FakeTransport()
        var now: Long = NOW
        val ambiguousFired = mutableListOf<String>()

        val processor: DeliveryProcessor
            get() = DeliveryProcessor(
                deliveryDao, eventDao, attemptDao, transactions,
                routes, FakeSecrets(secretsMap), transport,
                clock = { now },
                onAmbiguous = { ambiguousFired += it },
            )

        val history = HistoryRepository(
            captureLogDao, eventDao, deliveryDao, attemptDao,
            collectorStateDao = collectorDao, clock = { now },
        )

        suspend fun setMode(mode: String) {
            collectorDao.put(
                CollectorStateEntity(
                    CollectorStateKeys.CUTOVER_STATE,
                    """{"mode":"$mode"}""",
                    now,
                ),
            )
        }

        suspend fun setBootId(id: String = "boot-1") {
            collectorDao.put(
                CollectorStateEntity(
                    CollectorStateKeys.BOOT_ID,
                    """{"boot_id":"$id","boot_started_at_ms":$now}""",
                    now,
                ),
            )
        }

        fun route(id: String) =
            Route(id, "Rute $id", listOf(PKG), "https://$id.example.invalid/notify.php", listOf("gopay"), true)

        suspend fun paymentEvent(dedupeKey: String, amount: Long = 100800L): EventEntity {
            val event = EventEntity(
                eventId = UUID.randomUUID().toString(),
                sourceType = "android_notification",
                sourceEventId = dedupeKey,
                deviceId = "Moto g57",
                eventType = "payment.notification",
                category = "payment",
                sourcePackage = PKG,
                occurredAtMs = now,
                capturedAtMs = now,
                dataJson = """{"amount":$amount,"currency":"IDR"}""",
                metadataJson = "{}",
                legacyPayloadJson = """{"type":"payment","amount":$amount,"dedupe_key":"$dedupeKey"}""",
                privacyMode = "structured",
                retentionUntilMs = null,
                dedupeKey = dedupeKey,
                contentSha256 = null,
                exportRef = UUID.randomUUID().toString(),
                sourceAccountKey = null,
            )
            eventDao.insert(event)
            return event
        }

        suspend fun delivery(
            event: EventEntity,
            state: DeliveryState = DeliveryState.PENDING,
            lastMatchStatus: String? = null,
        ): DeliveryEntity {
            val delivery = DeliveryEntity(
                deliveryId = UUID.randomUUID().toString(),
                eventId = event.eventId,
                kind = DeliveryKind.LEGACY_CHAIN,
                state = state,
                legacyQkey = null,
                routeIdsJson = JSONArray(listOf("r1")).toString(),
                nextRouteIndex = 0,
                attempts = 0,
                nextRetryAtMs = null,
                leaseOwner = null,
                leaseUntilMs = null,
                createdAtMs = now,
                updatedAtMs = now,
                routeStampsJson = "{}",
                lastMatchStatus = lastMatchStatus,
            )
            deliveryDao.insert(delivery)
            return delivery
        }

        suspend fun captureRow(
            event: EventEntity,
            amountMinor: Long = 100800L,
            postTimeMs: Long = NOW,
        ): CaptureLogEntity {
            val row = CaptureLogEntity(
                captureId = UUID.randomUUID().toString(),
                sourcePackage = PKG,
                postTimeMs = postTimeMs,
                capturedAtMs = now,
                outcome = CaptureOutcome.CAPTURED,
                titleSnippet = "GoPay",
                amountMinor = amountMinor,
                eventId = event.eventId,
                contentSha256 = null,
            )
            captureLogDao.insert(row)
            return row
        }
    }

    @Test
    fun processor_firesOnAmbiguous_afterDurableAck() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        h.setBootId()
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        val event = h.paymentEvent("com.gojek.gopay|1|100800|amb")
        val delivery = h.delivery(event)

        h.processor.processDueWork()

        assertEquals(listOf(delivery.deliveryId), h.ambiguousFired)
        val after = h.deliveryDao.getById(delivery.deliveryId)!!
        assertEquals(DeliveryState.ACKED, after.state)
        assertEquals("ambiguous", after.lastMatchStatus)
        // The badge counts it until the operator acknowledges it.
        assertEquals(1, h.history.ambiguousCount())
    }

    @Test
    fun processor_doesNotFireOnMatched() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        h.setBootId()
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        h.transport.script = {
            TransportResult.Http(200, emptyMap(), """{"ok":true,"match":{"status":"matched","apply":"ok"}}""")
        }
        val event = h.paymentEvent("com.gojek.gopay|1|100800|ok")
        h.delivery(event)

        h.processor.processDueWork()

        assertTrue(h.ambiguousFired.isEmpty())
        assertEquals(0, h.history.ambiguousCount())
    }

    @Test
    fun badgeCountsRoomAndLegacy_minusAcknowledged() = runBlocking {
        val h = Harness()
        val roomEvent = h.paymentEvent("com.gojek.gopay|1|100800|room")
        h.delivery(roomEvent, DeliveryState.ACKED, lastMatchStatus = "ambiguous")
        h.captureRow(roomEvent)
        val legacyEvent = h.paymentEvent("com.gojek.gopay|2|100800|legacy")
        h.captureRow(legacyEvent, postTimeMs = NOW + 1)
        h.history.recordLegacyAmbiguous(
            "com.gojek.gopay|2|100800|legacy|chain|r1",
            legacyEvent.dedupeKey, 100800L, PKG,
        )
        val settledEvent = h.paymentEvent("com.gojek.gopay|3|100800|ok")
        h.delivery(settledEvent, DeliveryState.ACKED, lastMatchStatus = "matched")
        h.captureRow(settledEvent, postTimeMs = NOW + 2)

        assertEquals(2, h.history.ambiguousCount())

        // The legacy case shows as ambiguous in history (no Room delivery).
        val items = h.history.listPage()
        val legacyItem = items.first { it.amountMinor == 100800L && it.deliveryState == null }
        assertEquals("ambiguous", legacyItem.lastMatchStatus)
        assertFalse(legacyItem.ambiguousReviewed)
    }

    @Test
    fun markReviewed_clearsBadge_roomAndLegacy() = runBlocking {
        val h = Harness()
        val roomEvent = h.paymentEvent("com.gojek.gopay|1|100800|room")
        h.delivery(roomEvent, DeliveryState.ACKED, lastMatchStatus = "ambiguous")
        val roomRow = h.captureRow(roomEvent)
        val legacyEvent = h.paymentEvent("com.gojek.gopay|2|100800|legacy")
        val legacyRow = h.captureRow(legacyEvent, postTimeMs = NOW + 1)
        h.history.recordLegacyAmbiguous(
            "com.gojek.gopay|2|100800|legacy|chain|r1",
            legacyEvent.dedupeKey, 100800L, PKG,
        )
        assertEquals(2, h.history.ambiguousCount())

        assertTrue(h.history.markAmbiguousReviewed(roomRow.captureId, "operator-uji"))
        assertEquals(1, h.history.ambiguousCount())
        assertTrue(h.history.markAmbiguousReviewed(legacyRow.captureId, "operator-uji"))
        assertEquals(0, h.history.ambiguousCount())

        // Marking twice is a no-op (already acknowledged).
        assertFalse(h.history.markAmbiguousReviewed(roomRow.captureId, "operator-uji"))
        // The detail view reports the acknowledgement.
        val detail = h.history.getDetail(roomRow.captureId)
        assertNotNull(detail)
        assertTrue(detail!!.ambiguousReviewed)
        // ... and the list item too.
        val item = h.history.listPage().first { it.captureId == roomRow.captureId }
        assertTrue(item.ambiguousReviewed)
    }

    @Test
    fun markReviewed_nonAmbiguous_returnsFalse() = runBlocking {
        val h = Harness()
        val event = h.paymentEvent("com.gojek.gopay|1|100800|ok")
        h.delivery(event, DeliveryState.ACKED, lastMatchStatus = "matched")
        val row = h.captureRow(event)

        assertFalse(h.history.markAmbiguousReviewed(row.captureId, "operator-uji"))
        assertEquals(0, h.history.ambiguousCount())
    }

    @Test
    fun ambiguousCaseInfo_resolvesAmountAndPackage() = runBlocking {
        val h = Harness()
        val event = h.paymentEvent("com.gojek.gopay|1|170286|amb", amount = 170286L)
        val delivery = h.delivery(event, DeliveryState.ACKED, lastMatchStatus = "ambiguous")
        h.captureRow(event, amountMinor = 170286L)

        val info = h.history.ambiguousCaseInfo(delivery.deliveryId)
        assertNotNull(info)
        assertEquals(170286L, info!!.first)
        assertEquals(PKG, info.second)
        assertNull(h.history.ambiguousCaseInfo("no-such-delivery"))
    }

    @Test
    fun notificationIdFor_stableDistinctInRange() {
        val a1 = ReviewNotifier.notificationIdFor("delivery-a")
        val a2 = ReviewNotifier.notificationIdFor("delivery-a")
        val b = ReviewNotifier.notificationIdFor("delivery-b")
        assertEquals(a1, a2)
        assertTrue("distinct keys should usually differ", a1 != b)
        assertTrue(a1 >= ReviewNotifier.NOTIFICATION_ID_BASE)
        assertTrue(a1 < ReviewNotifier.NOTIFICATION_ID_BASE + 10_000)
        assertTrue(b >= ReviewNotifier.NOTIFICATION_ID_BASE)
    }
}
