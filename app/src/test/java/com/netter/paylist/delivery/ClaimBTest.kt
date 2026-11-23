package com.netter.paylist.delivery

import com.netter.paylist.FakeAttemptDao
import com.netter.paylist.FakeCollectorStateDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeStore
import com.netter.paylist.FakeTransactionRunner
import com.netter.paylist.Route
import com.netter.paylist.collector.ConsentStore
import com.netter.paylist.collector.RawSnapshotStore
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.AttemptState
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.data.entity.DeliveryAttemptEntity
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.security.SecretProvider
import com.netter.paylist.security.SecretResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Stage D D2: the claim-B integration (design §7.3A/§7.3B, F43
 * B1–B4, B9, B11–B14, B16, B20) — the Room worker claim path.
 *
 * The fake transport records every POST body, so each test
 * asserts exactly what crossed the wire: raw bytes only when the
 * snapshot was taken under a valid opt-in, the redacted stored
 * payload for an authorised A′ fallback, and zero bytes for every
 * hold. The B20 latch tests drive the fake transport's hook
 * between the final admission read and the first write.
 *
 * B14 (recorder gate): the `recorderGatePassed` flag defaults
 * FALSE in production wiring (fail-closed) — automatic A′
 * fallback on recorder routes is NOT permitted until B14 passes
 * against live `catat.php` semantics.
 */
class ClaimBTest {

    // --- Fakes ---

    private class FakeRouteSource(var routesList: List<Route>) : RouteSource {
        override fun routes(): List<Route> = routesList
        override fun routeById(id: String): Route? = routesList.firstOrNull { it.id == id }
        override fun deviceLabel(): String = "Moto g57"
    }

    private class FakeSecrets(private val map: Map<String, SecretResult>) : SecretProvider {
        override fun secretFor(routeId: String): SecretResult =
            map[routeId] ?: SecretResult.Missing
    }

    private data class PostedRequest(val url: String, val body: String, val authSecret: String)

    private class FakeTransport : TransportClient {
        val posted = mutableListOf<PostedRequest>()
        var script: (TransportRequest) -> TransportResult = {
            TransportResult.Http(200, emptyMap(), """{"ok":true,"match":{"status":"matched","apply":"ok"}}""")
        }
        /** Runs after the checkpoint callback returns, before the first write (B20 latch). */
        var afterCheckpoint: (suspend () -> Unit)? = null
        /** Runs before the checkpoint callback (a revoke landing between gate and final read). */
        var beforeCheckpoint: (suspend () -> Unit)? = null
        var forceCheckpointFailed: Boolean = false

        override suspend fun post(
            request: TransportRequest,
            onSocketOpen: suspend () -> Boolean,
        ): TransportResult {
            beforeCheckpoint?.invoke()
            if (forceCheckpointFailed) return TransportResult.CheckpointFailed
            if (!onSocketOpen()) return TransportResult.CheckpointFailed
            afterCheckpoint?.invoke()
            posted += PostedRequest(request.url, request.body, request.authSecret)
            return script(request)
        }
    }

    private class Harness {
        val store = FakeStore()
        val eventDao = FakeEventDao(store)
        val deliveryDao = FakeDeliveryDao(store)
        val attemptDao = FakeAttemptDao(store)
        val collectorDao = FakeCollectorStateDao(store)
        val transactions = FakeTransactionRunner(store)
        val routes = FakeRouteSource(emptyList())
        var secretsMap: Map<String, SecretResult> = emptyMap()
        val transport = FakeTransport()
        var now = 1_794_960_000_000L // 2026-11-18T00:00:00Z
        // The snapshot store runs on the harness clock: snapshots
        // are stamped with `now` (2026-11-18), and the real wall
        // clock would TTL-expire them instantly (2 h TTL).
        val snapshots = RawSnapshotStore(clock = { now })
        val consentStore = ConsentStore(collectorDao, clock = { now })
        var recorderGatePassed: Boolean = false
        val fallbackDispatches = mutableListOf<String>()

        val processor: DeliveryProcessor
            get() = DeliveryProcessor(
                deliveryDao, eventDao, attemptDao, transactions,
                routes, FakeSecrets(secretsMap), transport,
                clock = { now }, random = { 0.5 },
                rawSnapshotStore = snapshots,
                consentStore = consentStore,
                recorderGatePassed = recorderGatePassed,
                onFallbackDispatched = { fallbackDispatches += it },
            )

        suspend fun setMode(mode: String) {
            collectorDao.put(
                CollectorStateEntity(
                    CollectorStateKeys.CUTOVER_STATE,
                    """{"mode":"$mode"}""",
                    now
                )
            )
        }

        suspend fun setBootId(id: String = "boot-1") {
            collectorDao.put(
                CollectorStateEntity(
                    CollectorStateKeys.BOOT_ID,
                    """{"boot_id":"$id","boot_started_at_ms":$now}""",
                    now
                )
            )
        }

        fun route(
            id: String,
            url: String = "https://$id.example.invalid/notify.php",
            enabled: Boolean = true,
        ) = Route(id, "Rute $id", listOf("com.gojek.gopay"), url, listOf("gopay"), enabled)

        /** Grants consent + a valid P6 raw opt-in (1 h) for the route. */
        suspend fun grantB(routeId: String) {
            consentStore.grant(routeId)
            consentStore.setRawOptIn(routeId, true, now + 3_600_000L)
        }

        suspend fun stampFor(route: Route): String {
            val epoch = consentStore.epoch(route.id)
            return JSONObject().apply {
                put(route.id, JSONObject().apply {
                    put("consent_epoch", epoch)
                    put("config_epoch", 0)
                    put("url_sha256", processor.sha256Hex(route.url))
                })
            }.toString()
        }

        /** The stored (redacted) B payload for a mixed financial+OTP event. */
        fun bPayloadJson(
            dedupeKey: String,
            direction: String = "in",
            amountAmbiguous: Boolean = false,
        ): String {
            val payload = JSONObject().apply {
                put("type", "payment")
                put("device", "Moto g57")
                put("pkg", "com.gojek.gopay")
                put("amount", 100800)
                put("title", "GoPay")
                put("text", "Kode [REDACTED]. Rp100.800 diterima di GoPay kamu")
                put("notif_time", "2026-11-18T00:00:00Z")
                put("dedupe_key", dedupeKey)
                put("direction", direction)
                put("redacted", true)
                put("raw_ephemeral", true)
                if (amountAmbiguous) put("amount_ambiguous", true)
            }
            return payload.toString()
        }

        suspend fun bEvent(
            dedupeKey: String = "com.gojek.gopay|1794960000000|100800|t1",
            payloadJson: String? = null,
        ): EventEntity {
            val event = EventEntity(
                eventId = UUID.randomUUID().toString(),
                sourceType = "android_notification",
                sourceEventId = dedupeKey,
                deviceId = "Moto g57",
                eventType = "payment.notification",
                category = "payment",
                sourcePackage = "com.gojek.gopay",
                occurredAtMs = now,
                capturedAtMs = now,
                dataJson = """{"amount":100800,"currency":"IDR"}""",
                metadataJson = """{"parser_version":"payment-v1","privacy_mode":"structured"}""",
                legacyPayloadJson = payloadJson ?: bPayloadJson(dedupeKey),
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

        suspend fun bDelivery(
            event: EventEntity,
            routeIds: List<String>,
            routeStampsJson: String = "{}",
            rawEphemeral: Boolean = true,
            state: DeliveryState = DeliveryState.PENDING,
        ): DeliveryEntity {
            val delivery = DeliveryEntity(
                deliveryId = UUID.randomUUID().toString(),
                eventId = event.eventId,
                kind = DeliveryKind.LEGACY_CHAIN,
                state = state,
                legacyQkey = null,
                routeIdsJson = JSONArray(routeIds).toString(),
                nextRouteIndex = 0,
                attempts = 0,
                nextRetryAtMs = null,
                leaseOwner = null,
                leaseUntilMs = null,
                createdAtMs = now,
                updatedAtMs = now,
                routeStampsJson = routeStampsJson,
                rawEphemeral = rawEphemeral,
            )
            deliveryDao.insert(delivery)
            return delivery
        }

        suspend fun putSnapshot(
            dedupeKey: String,
            routeId: String,
            capturedAtMs: Long = now,
        ) {
            snapshots.put(
                RawSnapshotStore.RawSnapshot(
                    dedupeKey = dedupeKey,
                    routeId = routeId,
                    consentEpoch = consentStore.epoch(routeId),
                    title = "GoPay",
                    text = "Kode OTP 482913. Rp100.800 diterima di GoPay kamu",
                    capturedAtMs = capturedAtMs,
                ),
            )
        }

        suspend fun deliveryState(id: String): DeliveryEntity? = deliveryDao.getById(id)

        suspend fun attemptsFor(deliveryId: String): List<DeliveryAttemptEntity> =
            attemptDao.getByDeliveryId(deliveryId)
    }

    /** Common B setup: WORKER_ONLY + one notify route + grant + opt-in. */
    private suspend fun Harness.setupB(routeUrl: String? = null): Route {
        setMode("WORKER_ONLY")
        setBootId()
        val r1 = if (routeUrl != null) route("r1", url = routeUrl) else route("r1")
        routes.routesList = listOf(r1)
        secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        grantB("r1")
        return r1
    }

    // --- B1/B2: the raw path ---

    @Test
    fun b1_rawSnapshotPosted_rawBytesOnWire() = runBlocking {
        val h = Harness()
        val r1 = h.setupB()
        val event = h.bEvent()
        val delivery = h.bDelivery(event, listOf("r1"), routeStampsJson = h.stampFor(r1))
        h.putSnapshot(event.dedupeKey, "r1")

        h.processor.processDueWork()

        assertEquals(1, h.transport.posted.size)
        val body = JSONObject(h.transport.posted[0].body)
        // Raw bytes crossed the wire: the OTP is in the posted text.
        assertTrue(body.getString("text").contains("482913"))
        assertEquals(DeliveryState.ACKED, h.deliveryState(delivery.deliveryId)!!.state)
        // The snapshot was taken (take-don't-copy): no second claim sees it.
        assertNull(h.snapshots.take(event.dedupeKey, "r1", h.consentStore.epoch("r1")))
        // The attempt row records the admission stamp.
        val attempts = h.attemptsFor(delivery.deliveryId)
        assertEquals(1, attempts.size)
        assertEquals(AttemptState.COMPLETED, attempts[0].state)
        assertEquals(0L, attempts[0].fenceGeneration)
        assertFalse(attempts[0].rawLost)
        assertTrue(h.fallbackDispatches.isEmpty())
    }

    @Test
    fun b2_nonBRoute_postsStoredPayload() = runBlocking {
        val h = Harness()
        val r1 = h.setupB()
        val event = h.bEvent()
        // A non-B delivery (rawEphemeral=false) posts the stored payload verbatim.
        val delivery = h.bDelivery(
            event, listOf("r1"), routeStampsJson = h.stampFor(r1), rawEphemeral = false,
        )
        // Even with a snapshot in the map, a non-B delivery never takes it.
        h.putSnapshot(event.dedupeKey, "r1")

        h.processor.processDueWork()

        assertEquals(1, h.transport.posted.size)
        val body = JSONObject(h.transport.posted[0].body)
        assertFalse(body.getString("text").contains("482913"))
        assertTrue(body.getString("text").contains("[REDACTED]"))
        assertEquals(DeliveryState.ACKED, h.deliveryState(delivery.deliveryId)!!.state)
        // The snapshot is untouched (not taken).
        assertNotNull(h.snapshots.take(event.dedupeKey, "r1", h.consentStore.epoch("r1")))
    }

    // --- B3/B20: the revocation fence ---

    @Test
    fun b3_revokeBeforeFinalRead_zeroBytes_blocked() = runBlocking {
        val h = Harness()
        val r1 = h.setupB()
        val event = h.bEvent()
        val delivery = h.bDelivery(event, listOf("r1"), routeStampsJson = h.stampFor(r1))
        h.putSnapshot(event.dedupeKey, "r1")
        // The revoke lands after the take (pre-send gate) but
        // before the final admission read: the checkpoint observes
        // the bumped fence.
        h.transport.beforeCheckpoint = {
            h.consentStore.revoke("r1")
            h.snapshots.dropRoute("r1")
        }

        h.processor.processDueWork()

        assertEquals(0, h.transport.posted.size)
        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.BLOCKED, after.state)
        assertEquals("route_revoked", after.lastErrorCode)
        // The snapshot was dropped unsent (never returned after a fence abort).
        assertNull(h.snapshots.take(event.dedupeKey, "r1", h.consentStore.epoch("r1")))
    }

    @Test
    fun b20_caseB_revokeAfterFinalRead_firstWriteProceeds() = runBlocking {
        val h = Harness()
        val r1 = h.setupB()
        val event = h.bEvent()
        val delivery = h.bDelivery(event, listOf("r1"), routeStampsJson = h.stampFor(r1))
        h.putSnapshot(event.dedupeKey, "r1")
        // The revoke lands AFTER the final admission read but
        // before the first write (the disclosed residual): the
        // first write proceeds, the attempt runs to its outcome.
        h.transport.afterCheckpoint = {
            h.consentStore.revoke("r1")
            h.snapshots.dropRoute("r1")
        }

        h.processor.processDueWork()

        assertEquals(1, h.transport.posted.size)
        assertTrue(JSONObject(h.transport.posted[0].body).getString("text").contains("482913"))
        assertEquals(DeliveryState.ACKED, h.deliveryState(delivery.deliveryId)!!.state)
        // The attempt row records the PRE-revoke admission stamp.
        val attempts = h.attemptsFor(delivery.deliveryId)
        assertEquals(1, attempts.size)
        assertEquals(0L, attempts[0].fenceGeneration)
    }

    // --- B4/B9/B11: raw loss + authorised fallback ---

    @Test
    fun b4_processRestart_rawLost_fallbackDispatched() = runBlocking {
        val h = Harness()
        val r1 = h.setupB()
        val event = h.bEvent()
        val delivery = h.bDelivery(event, listOf("r1"), routeStampsJson = h.stampFor(r1))
        // No snapshot in the map (process restart): §7.3B evaluation,
        // all criteria met ⇒ automatic A′ fallback.

        h.processor.processDueWork()

        assertEquals(1, h.transport.posted.size)
        val body = JSONObject(h.transport.posted[0].body)
        assertFalse(body.getString("text").contains("482913"))
        assertEquals(100800, body.getLong("amount"))
        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.ACKED, after.state)
        assertTrue(after.rawLost)
        val attempts = h.attemptsFor(delivery.deliveryId)
        assertEquals(1, attempts.size)
        assertTrue(attempts[0].rawLost)
        assertEquals(listOf("r1"), h.fallbackDispatches)
    }

    @Test
    fun b9_overflow_rawLost_fallbackDispatched() = runBlocking {
        val h = Harness()
        val r1 = h.setupB()
        val event = h.bEvent()
        val delivery = h.bDelivery(event, listOf("r1"), routeStampsJson = h.stampFor(r1))
        h.putSnapshot(event.dedupeKey, "r1")
        // Overflow the map (capacity 50): the oldest snapshot —
        // the target — is evicted LRU.
        for (i in 0 until RawSnapshotStore.DEFAULT_CAPACITY) {
            h.snapshots.put(
                RawSnapshotStore.RawSnapshot(
                    dedupeKey = "overflow-$i",
                    routeId = "r1",
                    consentEpoch = h.consentStore.epoch("r1"),
                    title = "T",
                    text = "overflow $i",
                    capturedAtMs = h.now,
                ),
            )
        }
        assertNull(h.snapshots.take(event.dedupeKey, "r1", h.consentStore.epoch("r1")))

        h.processor.processDueWork()

        assertEquals(1, h.transport.posted.size)
        assertTrue(h.deliveryState(delivery.deliveryId)!!.rawLost)
        assertEquals(listOf("r1"), h.fallbackDispatches)
    }

    @Test
    fun b11_ttlExpiry_rawLost_fallbackDispatched() = runBlocking {
        val h = Harness()
        val r1 = h.setupB()
        val event = h.bEvent()
        val delivery = h.bDelivery(event, listOf("r1"), routeStampsJson = h.stampFor(r1))
        // Snapshot captured 3 h ago (TTL 2 h): expired, never returned.
        h.putSnapshot(event.dedupeKey, "r1", capturedAtMs = h.now - 3 * 3_600_000L)

        h.processor.processDueWork()

        assertEquals(1, h.transport.posted.size)
        assertTrue(h.deliveryState(delivery.deliveryId)!!.rawLost)
        assertEquals(listOf("r1"), h.fallbackDispatches)
    }

    // --- B12/B13: fallback refused → hold states ---

    @Test
    fun b12_criterion1_amountAmbiguous_needsReview() = runBlocking {
        val h = Harness()
        val r1 = h.setupB()
        val payload = h.bPayloadJson(
            "com.gojek.gopay|1794960000000|100800|t1", amountAmbiguous = true,
        )
        val event = h.bEvent(payloadJson = payload)
        val delivery = h.bDelivery(event, listOf("r1"), routeStampsJson = h.stampFor(r1))
        // Raw lost + ambiguous amount ⇒ NEEDS_REVIEW takes precedence (B13).

        h.processor.processDueWork()

        assertEquals(0, h.transport.posted.size)
        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.NEEDS_REVIEW, after.state)
        assertTrue(after.rawLost)
        assertTrue(h.fallbackDispatches.isEmpty())
    }

    @Test
    fun b12_criterion2_directionUnknown_rawUnavailable() = runBlocking {
        val h = Harness()
        val r1 = h.setupB()
        val payload = h.bPayloadJson(
            "com.gojek.gopay|1794960000000|100800|t1", direction = "unknown",
        )
        val event = h.bEvent(payloadJson = payload)
        val delivery = h.bDelivery(event, listOf("r1"), routeStampsJson = h.stampFor(r1))

        h.processor.processDueWork()

        assertEquals(0, h.transport.posted.size)
        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.RAW_UNAVAILABLE, after.state)
        assertTrue(after.rawLost)
        assertTrue(h.fallbackDispatches.isEmpty())
    }

    @Test
    fun b12_criterion6_optInExpired_blocked_snapshotKept() = runBlocking {
        val h = Harness()
        val r1 = h.setupB()
        val event = h.bEvent()
        val delivery = h.bDelivery(event, listOf("r1"), routeStampsJson = h.stampFor(r1))
        h.putSnapshot(event.dedupeKey, "r1")
        // The opt-in expires before the claim (clock advance 1.5 h:
        // past the 1 h opt-in expiry, inside the 2 h snapshot TTL):
        // the claim join still passes (granted + epoch match), but
        // the pre-send re-check fails ⇒ BLOCKED, raw never transmitted.
        h.now += 90 * 60_000L

        h.processor.processDueWork()

        assertEquals(0, h.transport.posted.size)
        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.BLOCKED, after.state)
        assertEquals("consent_expired", after.lastErrorCode)
        // The snapshot was never taken — it stays in the map.
        assertNotNull(h.snapshots.take(event.dedupeKey, "r1", h.consentStore.epoch("r1")))
    }

    @Test
    fun b16_revokeBeforeClaim_neverDispatched() = runBlocking {
        val h = Harness()
        val r1 = h.setupB()
        val event = h.bEvent()
        val delivery = h.bDelivery(event, listOf("r1"), routeStampsJson = h.stampFor(r1))
        h.putSnapshot(event.dedupeKey, "r1")
        // Revoke before the claim: the claim's consent join skips
        // the delivery (epoch mismatch) — it stays PENDING and is
        // never dispatched. The §7.5 revocation sweep transitions
        // it to BLOCKED; the worker never posts it.
        h.consentStore.revoke("r1")
        h.snapshots.dropRoute("r1")

        h.processor.processDueWork()

        assertEquals(0, h.transport.posted.size)
        assertEquals(DeliveryState.PENDING, h.deliveryState(delivery.deliveryId)!!.state)
    }

    // --- B14: the recorder gate ---

    @Test
    fun b14_recorderRoute_fallbackRefused_withoutB14() = runBlocking {
        val h = Harness()
        val r1 = h.setupB(routeUrl = "https://r1.example.invalid/catat.php")
        val event = h.bEvent()
        val delivery = h.bDelivery(event, listOf("r1"), routeStampsJson = h.stampFor(r1))
        // Raw lost, all other criteria met — but B14 has not passed
        // (recorderGatePassed=false, the production default) ⇒ the
        // fallback is NOT authorised on a recorder route.
        assertFalse(h.recorderGatePassed)

        h.processor.processDueWork()

        assertEquals(0, h.transport.posted.size)
        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.RAW_UNAVAILABLE, after.state)
        assertTrue(after.rawLost)
    }

    @Test
    fun b14_recorderRoute_fallbackAllowed_withB14() = runBlocking {
        val h = Harness()
        h.recorderGatePassed = true
        val r1 = h.setupB(routeUrl = "https://r1.example.invalid/catat.php")
        val event = h.bEvent()
        val delivery = h.bDelivery(event, listOf("r1"), routeStampsJson = h.stampFor(r1))

        h.processor.processDueWork()

        assertEquals(1, h.transport.posted.size)
        val body = JSONObject(h.transport.posted[0].body)
        // The §7.2 constructed text (IN, no account/reference yet).
        assertEquals("Diterima Rp100.800", body.getString("text"))
        assertFalse(body.getString("text").contains("482913"))
        assertEquals(DeliveryState.ACKED, h.deliveryState(delivery.deliveryId)!!.state)
        assertTrue(h.deliveryState(delivery.deliveryId)!!.rawLost)
        assertEquals(listOf("r1"), h.fallbackDispatches)
    }

    // --- Claim-transaction failure: the snapshot is returned ---

    @Test
    fun claimFailure_snapshotReturned_retriedRaw() = runBlocking {
        val h = Harness()
        val r1 = h.setupB()
        val event = h.bEvent()
        val delivery = h.bDelivery(event, listOf("r1"), routeStampsJson = h.stampFor(r1))
        h.putSnapshot(event.dedupeKey, "r1")
        // The claim transaction fails after the take (§7.3A(d)):
        // the snapshot is returned to the map, the delivery stays
        // IN_FLIGHT until the lease expires.
        h.transport.forceCheckpointFailed = true

        h.processor.processDueWork()

        assertEquals(0, h.transport.posted.size)
        assertEquals(DeliveryState.IN_FLIGHT, h.deliveryState(delivery.deliveryId)!!.state)
        // The snapshot is back in the map for the next claim.
        assertNotNull(h.snapshots.peek(event.dedupeKey, "r1", h.consentStore.epoch("r1")))
        // Lease expiry (5 min) reclaims the delivery to PENDING;
        // the retry posts raw.
        h.transport.forceCheckpointFailed = false
        h.now += 6 * 60_000L

        h.processor.processDueWork()

        assertEquals(1, h.transport.posted.size)
        assertTrue(JSONObject(h.transport.posted[0].body).getString("text").contains("482913"))
        assertEquals(DeliveryState.ACKED, h.deliveryState(delivery.deliveryId)!!.state)
    }
}
