package com.netter.paylist.delivery

import com.netter.paylist.FakeAttemptDao
import com.netter.paylist.FakeCollectorStateDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeStore
import com.netter.paylist.FakeTransactionRunner
import com.netter.paylist.Route
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.AttemptState
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.data.entity.DeliveryAttemptEntity
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.data.entity.HopOutcome
import com.netter.paylist.security.SecretProvider
import com.netter.paylist.security.SecretResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * `DeliveryProcessor` behaviour (the SyncWorker's logic) with fake
 * DAOs, a fake transport and a fake clock. Covers the C→D gate
 * cases T02–T08 (classification → delivery transitions), T21
 * (replay after lease expiry), the §5.5 per-hop table, the §7.5
 * stamp gate, heartbeat TTL, retry exhaustion and fairness.
 *
 * The thin `SyncWorker` shell itself (WorkManager plumbing) cannot
 * be instantiated in JVM unit tests without Robolectric; it is
 * covered by the clean build and the instrumented suite (Stage E).
 */
class SyncWorkerTest {

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
            TransportResult.Http(200, emptyMap(), """{"ok":true,"match":{"status":"logged"}}""")
        }
        var throwOnPost: Boolean = false

        override suspend fun post(
            request: TransportRequest,
            onSocketOpen: suspend () -> Boolean,
        ): TransportResult {
            if (!onSocketOpen()) return TransportResult.CheckpointFailed
            if (throwOnPost) throw IllegalStateException("injected transport failure")
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

        val processor: DeliveryProcessor
            get() = DeliveryProcessor(
                deliveryDao, eventDao, attemptDao, transactions,
                routes, FakeSecrets(secretsMap), transport,
                clock = { now }, random = { 0.5 } // jitter 1.0: deterministic backoff
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

        suspend fun setConsents(json: String) {
            collectorDao.put(
                CollectorStateEntity(CollectorStateKeys.ROUTE_CONSENTS, json, now)
            )
        }

        fun route(
            id: String,
            url: String = "https://$id.example.invalid/notify.php",
            enabled: Boolean = true,
        ) = Route(id, "Rute $id", listOf("com.gojek.gopay"), url, listOf("gopay"), enabled)

        suspend fun paymentEvent(
            dedupeKey: String = "com.gojek.gopay|1794960000000|100800|t1",
            occurredAtMs: Long = now,
            legacyPayload: String? = paymentPayloadJson(dedupeKey),
        ): EventEntity {
            val event = EventEntity(
                eventId = UUID.randomUUID().toString(),
                sourceType = "android_notification",
                sourceEventId = dedupeKey,
                deviceId = "Moto g57",
                eventType = "payment.notification",
                category = "payment",
                sourcePackage = "com.gojek.gopay",
                occurredAtMs = occurredAtMs,
                capturedAtMs = now,
                dataJson = """{"amount":100800,"currency":"IDR"}""",
                metadataJson = """{"parser_version":"payment-v1","privacy_mode":"legacy"}""",
                legacyPayloadJson = legacyPayload,
                privacyMode = "legacy",
                retentionUntilMs = null,
                dedupeKey = dedupeKey,
                contentSha256 = null,
                exportRef = UUID.randomUUID().toString(),
                sourceAccountKey = null,
            )
            eventDao.insert(event)
            return event
        }

        suspend fun heartbeatEvent(
            dedupeKey: String,
            occurredAtMs: Long = now,
        ): EventEntity {
            val event = EventEntity(
                eventId = UUID.randomUUID().toString(),
                sourceType = "heartbeat",
                sourceEventId = dedupeKey,
                deviceId = "Moto g57",
                eventType = "heartbeat.notification",
                category = "heartbeat",
                sourcePackage = null,
                occurredAtMs = occurredAtMs,
                capturedAtMs = now,
                dataJson = "{}",
                metadataJson = """{"parser_version":"heartbeat-v1","privacy_mode":"structured"}""",
                legacyPayloadJson = null,
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
            routeIds: List<String>,
            state: DeliveryState = DeliveryState.PENDING,
            attempts: Int = 0,
            routeStampsJson: String = "{}",
            nextRouteIndex: Int = 0,
            leaseOwner: String? = null,
            leaseUntilMs: Long? = null,
            nextRetryAtMs: Long? = null,
        ): DeliveryEntity {
            val delivery = DeliveryEntity(
                deliveryId = UUID.randomUUID().toString(),
                eventId = event.eventId,
                kind = DeliveryKind.LEGACY_CHAIN,
                state = state,
                legacyQkey = null,
                routeIdsJson = JSONArray(routeIds).toString(),
                nextRouteIndex = nextRouteIndex,
                attempts = attempts,
                nextRetryAtMs = nextRetryAtMs,
                leaseOwner = leaseOwner,
                leaseUntilMs = leaseUntilMs,
                createdAtMs = now,
                updatedAtMs = now,
                routeStampsJson = routeStampsJson,
            )
            deliveryDao.insert(delivery)
            return delivery
        }

        suspend fun deliveryState(id: String): DeliveryEntity? = deliveryDao.getById(id)

        suspend fun attemptsFor(deliveryId: String): List<DeliveryAttemptEntity> =
            attemptDao.getByDeliveryId(deliveryId)

        fun paymentPayloadJson(dedupeKey: String): String =
            """{"type":"payment","device":"Moto g57","pkg":"com.gojek.gopay","amount":100800,"title":"GoPay","text":"Kamu menerima Rp100.800","notif_time":"2026-11-18T00:00:00Z","dedupe_key":"$dedupeKey"}"""
    }

    private fun matchedBody(apply: String): String =
        """{"ok":true,"match":{"status":"matched","apply":"$apply"}}"""

    private fun loggedBody(): String =
        """{"ok":true,"match":{"status":"logged"}}"""

    private fun http(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
        TransportResult.Http(status, headers, body)

    // --- T02–T08: the C→D gate cases ---

    @Test
    fun t02_matchedApplyOk_settlesAndStopsChain() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        val r2 = h.route("r2")
        h.routes.routesList = listOf(r1, r2)
        h.secretsMap = mapOf(
            "r1" to SecretResult.Present("s1"),
            "r2" to SecretResult.Present("s2")
        )
        h.transport.script = { http(200, matchedBody("ok")) }
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1", "r2"))

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.ACKED, after.state)
        assertEquals("matched", after.lastMatchStatus)
        assertEquals("ok", after.lastApplyStatus)
        assertNotNull(after.ackedAtMs)
        // Chain stopped: exactly one POST, to the first route only.
        assertEquals(1, h.transport.posted.size)
        assertEquals(r1.url, h.transport.posted[0].url)
        assertEquals("s1", h.transport.posted[0].authSecret)
        val attempts = h.attemptsFor(delivery.deliveryId)
        assertEquals(1, attempts.size)
        assertEquals(AttemptState.COMPLETED, attempts[0].state)
        assertEquals("MATCHED_SETTLED", attempts[0].resultClass)
    }

    @Test
    fun t03_loggedThenMatched_advancesExactlyOnce() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        val r2 = h.route("r2")
        h.routes.routesList = listOf(r1, r2)
        h.secretsMap = mapOf(
            "r1" to SecretResult.Present("s1"),
            "r2" to SecretResult.Present("s2")
        )
        h.transport.script = { req ->
            if (req.url == r1.url) http(200, loggedBody()) else http(200, matchedBody("ok"))
        }
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1", "r2"))

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.ACKED, after.state)
        assertEquals("matched", after.lastMatchStatus)
        assertEquals("ok", after.lastApplyStatus)
        assertEquals(2, h.transport.posted.size)
        assertEquals(r1.url, h.transport.posted[0].url)
        assertEquals(r2.url, h.transport.posted[1].url)
        // Both hops carry the SAME dedupe_key (no double settlement).
        val keys = h.transport.posted.map {
            JSONObject(it.body).getString("dedupe_key")
        }
        assertEquals(keys[0], keys[1])
        val outcomes = HopOutcome.fromJsonArray(after.hopOutcomesJson)
        assertEquals(listOf("LOGGED", "MATCHED_SETTLED"), outcomes.map { it.outcome })
    }

    @Test
    fun t04_ambiguous_stopsAndFlagsReview() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1, h.route("r2"))
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"), "r2" to SecretResult.Present("s2"))
        h.transport.script = { http(200, """{"ok":true,"match":{"status":"ambiguous"}}""") }
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1", "r2"))

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.ACKED, after.state)
        assertEquals("ambiguous", after.lastMatchStatus)
        assertNull(after.lastApplyStatus) // ambiguous is not settlement
        assertEquals(1, h.transport.posted.size)
    }

    @Test
    fun t05_malformed2xx_retriesSameRouteThenHolds() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1, h.route("r2"))
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"), "r2" to SecretResult.Present("s2"))
        h.transport.script = { http(200, """{"ok":true}""") } // no match block
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1", "r2"))

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.RETRY_WAIT, after.state)
        assertEquals(0, after.nextRouteIndex) // same route, never advanced
        assertEquals(h.now + 60_000L, after.nextRetryAtMs)
        // Second pass after the retry time: the SAME route is posted
        // again (re-POST rules) — and a second malformed response
        // holds the delivery (row 10: a re-POST failure proves
        // nothing about the first attempt).
        h.now += 61_000L
        h.processor.processDueWork()
        assertEquals(2, h.transport.posted.size)
        assertEquals(r1.url, h.transport.posted[1].url)
        assertEquals(
            DeliveryState.HELD_AWAITING_RECONCILIATION,
            h.deliveryState(delivery.deliveryId)!!.state
        )
    }

    @Test
    fun t06_retryAfter429_honorsDelay() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        h.transport.script = { http(429, "", mapOf("Retry-After" to "120")) }
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1"))

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.RETRY_WAIT, after.state)
        assertEquals(h.now + 120_000L, after.nextRetryAtMs)
        assertEquals(0, after.nextRouteIndex)
    }

    @Test
    fun t07_unauthorized401_blockedVisible() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1, h.route("r2"))
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"), "r2" to SecretResult.Present("s2"))
        h.transport.script = { http(401, """{"ok":false}""") }
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1", "r2"))

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.BLOCKED, after.state)
        assertEquals("http_401", after.lastErrorCode)
        assertEquals(0, after.nextRouteIndex) // halted, never advanced
        assertEquals(1, h.transport.posted.size) // chain halted: no second route
        val outcomes = HopOutcome.fromJsonArray(after.hopOutcomesJson)
        assertEquals("BLOCKED_CONSENT", outcomes.single().outcome)
    }

    @Test
    fun t08_server500_retriesOnceThenHoldsOnSecondFailure() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        h.transport.script = { http(500, "") }
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1"))

        // First failure: RETRY_WAIT with backoff (T08).
        h.processor.processDueWork()
        val after1 = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.RETRY_WAIT, after1.state)
        assertEquals(h.now + 60_000L, after1.nextRetryAtMs) // attempts=1: 1 min
        val outcomes1 = HopOutcome.fromJsonArray(after1.hopOutcomesJson)
        assertEquals("UNKNOWN_PENDING_RECONCILIATION", outcomes1.single().outcome)

        // Second failure (the re-POST): row 10 — a re-POST failure
        // proves nothing about the first attempt, so the delivery
        // holds for reconciliation instead of retrying forever.
        h.now += 61_000L
        h.processor.processDueWork()
        val after2 = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.HELD_AWAITING_RECONCILIATION, after2.state)
        assertEquals(2, h.transport.posted.size)
    }

    // --- T21: replay after lease expiry ---

    @Test
    fun t21_leaseExpiry_replayYieldsExactlyOneSettlement() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        var calls = 0
        h.transport.script = {
            calls++
            if (calls == 1) {
                // The lease expires DURING the first POST.
                h.now += 6 * 60 * 1000L
                http(200, matchedBody("ok"))
            } else {
                // The re-POST returns the stored duplicate: matched,
                // no apply field — apply is never assumed.
                http(200, """{"ok":true,"duplicate":true,"match":{"status":"matched"}}""")
            }
        }
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1"))

        // First pass: the POST returns matched+ok, but the lease is
        // gone, so the ACK does not commit. The attempt row IS
        // completed (audit trail + reclaim unblocked).
        h.processor.processDueWork()
        val mid = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.IN_FLIGHT, mid.state)
        assertNull(mid.lastApplyStatus)
        assertEquals(AttemptState.COMPLETED, h.attemptsFor(delivery.deliveryId).single().state)

        // Second pass: the lease is reclaimed, the hop is re-POSTed
        // under the same dedupe_key, and the duplicate response
        // holds the delivery for reconciliation — never settled twice.
        h.processor.processDueWork()
        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.HELD_AWAITING_RECONCILIATION, after.state)
        assertNull(after.lastApplyStatus) // never assumed 'ok'
        assertEquals(2, h.transport.posted.size)
        val keys = h.transport.posted.map { JSONObject(it.body).getString("dedupe_key") }
        assertEquals(keys[0], keys[1])
    }

    // --- §5.5 rows 4/8–10: unknown hops ---

    @Test
    fun readTimeout_retriesOnceThenHoldsOnSecondFailure() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        h.transport.script = { TransportResult.NetworkError("read_timeout") }
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1"))

        // First timeout (row 4): RETRY_WAIT, hop UNKNOWN.
        h.processor.processDueWork()
        val after1 = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.RETRY_WAIT, after1.state)
        assertEquals("read_timeout", after1.lastErrorCode)
        assertEquals(
            "UNKNOWN_PENDING_RECONCILIATION",
            HopOutcome.fromJsonArray(after1.hopOutcomesJson).single().outcome
        )

        // Second timeout (the re-POST, row 10): hold.
        h.now += 61_000L
        h.processor.processDueWork()
        assertEquals(
            DeliveryState.HELD_AWAITING_RECONCILIATION,
            h.deliveryState(delivery.deliveryId)!!.state
        )
    }

    @Test
    fun dnsFailure_retriesSameHopWithDoublingBackoff() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        h.transport.script = { TransportResult.NetworkError("dns") }
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1"))

        h.processor.processDueWork()
        val after1 = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.RETRY_WAIT, after1.state)
        assertEquals("dns", after1.lastErrorCode)
        assertEquals(0, after1.nextRouteIndex)
        assertEquals(h.now + 60_000L, after1.nextRetryAtMs)
        assertEquals("NO_POST", HopOutcome.fromJsonArray(after1.hopOutcomesJson).single().outcome)

        // NO_POST retries use first-attempt semantics (provably never
        // sent), so the backoff keeps doubling instead of holding.
        h.now += 61_000L
        h.processor.processDueWork()
        val after2 = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.RETRY_WAIT, after2.state)
        assertEquals(h.now + 120_000L, after2.nextRetryAtMs) // attempts=2: 2 min
        assertEquals(2, h.transport.posted.size)
    }

    @Test
    fun repostAfterUnknown_loggedStaysUnknown_row9() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1"))

        // First attempt: 5xx -> UNKNOWN + RETRY_WAIT (row 4).
        h.transport.script = { http(500, "") }
        h.processor.processDueWork()
        assertEquals(DeliveryState.RETRY_WAIT, h.deliveryState(delivery.deliveryId)!!.state)

        // Re-POST: `logged` proves nothing about the first attempt
        // (row 9) — the hop stays unknown, the delivery holds.
        h.now += 61_000L
        h.transport.script = { http(200, loggedBody()) }
        h.processor.processDueWork()
        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.HELD_AWAITING_RECONCILIATION, after.state)
        assertEquals(0, after.nextRouteIndex) // never advanced on unknown evidence
    }

    @Test
    fun repostAfterUnknown_matchedOkSettles_row8a() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1"))

        // First attempt: timeout -> UNKNOWN + RETRY_WAIT (row 4).
        h.transport.script = { TransportResult.NetworkError("read_timeout") }
        h.processor.processDueWork()
        assertEquals(DeliveryState.RETRY_WAIT, h.deliveryState(delivery.deliveryId)!!.state)

        // Re-POST: matched+ok is the only in-band way an unknown hop
        // clears as settled (row 8a).
        h.now += 61_000L
        h.transport.script = { http(200, matchedBody("ok")) }
        h.processor.processDueWork()
        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.ACKED, after.state)
        assertEquals("ok", after.lastApplyStatus)
    }

    @Test
    fun unresolvedMarker_stopsDeliveryWithoutSecondSocket() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        h.transport.script = { http(200, matchedBody("ok")) }
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1"))
        // An unresolved SOCKET_OPEN attempt for hop 0 (orphaned by a
        // crash): the worker must never open a second socket for the
        // hop. Startup recovery resolves the marker; until then the
        // delivery stays IN_FLIGHT under its lease.
        h.attemptDao.insert(
            DeliveryAttemptEntity(
                attemptId = UUID.randomUUID().toString(),
                deliveryId = delivery.deliveryId,
                routeId = "r1",
                attemptNo = 1,
                startedAtMs = h.now - 1000,
                state = AttemptState.SOCKET_OPEN,
                hopIndex = 0,
                dedupeKey = event.dedupeKey,
                ownerBootId = "boot-0",
            )
        )

        h.processor.processDueWork()

        assertEquals(DeliveryState.IN_FLIGHT, h.deliveryState(delivery.deliveryId)!!.state)
        assertEquals(0, h.transport.posted.size)
        assertEquals(1, h.attemptsFor(delivery.deliveryId).size) // no new attempt row
    }

    // --- Heartbeat TTL + dispatch ---

    @Test
    fun heartbeatTtlExpired_expiresWithoutPosting() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        val event = h.heartbeatEvent("hb-r1-100", occurredAtMs = h.now - 31 * 60 * 1000L)
        val delivery = h.delivery(event, listOf("r1"))

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.EXPIRED, after.state)
        assertNull(after.ackedAtMs) // EXPIRED is never counted as an ACK
        assertEquals("heartbeat_ttl_expired", after.lastErrorCode)
        assertEquals(0, h.transport.posted.size)
    }

    @Test
    fun heartbeatFresh_postsAndAcks() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        h.transport.script = { http(200, """{"ok":true,"match":null}""") }
        val event = h.heartbeatEvent("hb-r1-1994400")
        val delivery = h.delivery(event, listOf("r1"))

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.ACKED, after.state)
        assertEquals(1, h.transport.posted.size)
        val body = JSONObject(h.transport.posted[0].body)
        assertEquals("heartbeat", body.getString("type"))
        assertEquals("hb-r1-1994400", body.getString("dedupe_key"))
        assertFalse(body.has("amount"))
    }

    // --- §5.5 rows 6–7: gates ---

    @Test
    fun allRoutesSkipped_blockedVisible() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        // Both routes disabled: nothing can receive the payment.
        h.routes.routesList = listOf(h.route("r1", enabled = false), h.route("r2", enabled = false))
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"), "r2" to SecretResult.Present("s2"))
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1", "r2"))

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.BLOCKED, after.state)
        assertEquals("all_routes_skipped", after.lastErrorCode)
        assertEquals(0, h.transport.posted.size)
        val outcomes = HopOutcome.fromJsonArray(after.hopOutcomesJson)
        assertEquals(listOf("NO_POST", "NO_POST"), outcomes.map { it.outcome })
    }

    @Test
    fun missingSecret_skipsHopToNextRoute() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        val r2 = h.route("r2")
        h.routes.routesList = listOf(r1, r2)
        h.secretsMap = mapOf("r2" to SecretResult.Present("s2")) // r1 has no secret
        h.transport.script = { http(200, matchedBody("ok")) }
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1", "r2"))

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.ACKED, after.state)
        assertEquals(1, h.transport.posted.size)
        assertEquals(r2.url, h.transport.posted[0].url)
        val outcomes = HopOutcome.fromJsonArray(after.hopOutcomesJson)
        assertEquals(listOf("NO_POST", "MATCHED_SETTLED"), outcomes.map { it.outcome })
    }

    @Test
    fun secretUnavailable_blocksNeverSkips() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1, h.route("r2"))
        h.secretsMap = mapOf(
            "r1" to SecretResult.Unavailable("keystore locked"),
            "r2" to SecretResult.Present("s2")
        )
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1", "r2"))

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.BLOCKED, after.state)
        assertEquals("secret_unavailable", after.lastErrorCode)
        assertEquals(0, h.transport.posted.size)
    }

    @Test
    fun stampConsentMismatch_skippedByClaim() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        // The delivery was stamped at consent epoch 1; the current epoch is 2.
        val stamp = JSONObject().apply {
            put("r1", JSONObject().apply {
                put("consent_epoch", 1)
                put("config_epoch", 0)
                put("url_sha256", h.processor.sha256Hex(r1.url))
            })
        }.toString()
        h.setConsents("""{"routes":{"r1":{"granted":true,"epoch":2,"disclosure_version":1,"updated_at_ms":0}}}""")
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1"), routeStampsJson = stamp)

        h.processor.processDueWork()

        // The claim itself skips the delivery (consent join), so it
        // stays PENDING — the per-hop gate is defence in depth.
        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.PENDING, after.state)
        assertEquals(0, h.transport.posted.size)
    }

    @Test
    fun stampUrlChanged_blocksBeforePost() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1", url = "https://r1.example.invalid/new.php")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        // Stamped against the OLD url; the route url changed since.
        val stamp = JSONObject().apply {
            put("r1", JSONObject().apply {
                put("consent_epoch", 0)
                put("config_epoch", 0)
                put("url_sha256", h.processor.sha256Hex("https://r1.example.invalid/old.php"))
            })
        }.toString()
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1"), routeStampsJson = stamp)

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.BLOCKED, after.state)
        assertEquals("url_changed", after.lastErrorCode)
        assertEquals(0, h.transport.posted.size)
    }

    // --- Mode gate, lease, exhaustion, fairness, retention ---

    @Test
    fun modeNotWorkerOnly_claimsNothingAndDoesNotReschedule() = runBlocking {
        val h = Harness()
        h.setMode("LEGACY_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1"))

        val remaining = h.processor.processDueWork()

        assertFalse(remaining)
        assertEquals(DeliveryState.PENDING, h.deliveryState(delivery.deliveryId)!!.state)
        assertEquals(0, h.transport.posted.size)
    }

    @Test
    fun expiredLeaseWithoutMarkers_reclaimsAndRetries() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        h.transport.script = { http(200, matchedBody("ok")) }
        val event = h.paymentEvent()
        // Stuck IN_FLIGHT with an expired lease, no attempt markers.
        val delivery = h.delivery(
            event, listOf("r1"),
            state = DeliveryState.IN_FLIGHT,
            leaseOwner = "dead-owner",
            leaseUntilMs = h.now - 1000,
        )

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.ACKED, after.state)
        assertEquals(1, h.transport.posted.size)
    }

    @Test
    fun retryExhaustion_blocksAfterTenAttempts() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        h.transport.script = { http(500, "") }
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1"), attempts = 9)

        h.processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.BLOCKED, after.state)
        assertEquals("retry_exhausted", after.lastErrorCode)
    }

    @Test
    fun fairness_oneFailureDoesNotBlockOthers() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        val r2 = h.route("r2")
        h.routes.routesList = listOf(r1, r2)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"), "r2" to SecretResult.Present("s2"))
        // The first delivery's POST throws inside the transport.
        h.transport.script = { req ->
            if (req.url == r1.url) throw IllegalStateException("boom")
            http(200, matchedBody("ok"))
        }
        val event1 = h.paymentEvent(dedupeKey = "com.gojek.gopay|1794960000000|100800|fail")
        val delivery1 = h.delivery(event1, listOf("r1"))
        val event2 = h.paymentEvent(dedupeKey = "com.gojek.gopay|1794960000000|100800|ok")
        val delivery2 = h.delivery(event2, listOf("r2"))

        h.processor.processDueWork()

        val after1 = h.deliveryState(delivery1.deliveryId)!!
        assertEquals(DeliveryState.RETRY_WAIT, after1.state)
        assertEquals("worker_error", after1.lastErrorCode)
        val after2 = h.deliveryState(delivery2.deliveryId)!!
        assertEquals(DeliveryState.ACKED, after2.state)
    }

    @Test
    fun checkpointFailure_leavesDeliveryInFlight() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        // The transport observes a checkpoint failure: no POST happens.
        val failingTransport = object : TransportClient {
            override suspend fun post(
                request: TransportRequest,
                onSocketOpen: suspend () -> Boolean,
            ): TransportResult {
                onSocketOpen() // the checkpoint runs (and succeeds here)
                return TransportResult.CheckpointFailed
            }
        }
        val processor = DeliveryProcessor(
            h.deliveryDao, h.eventDao, h.attemptDao, h.transactions,
            h.routes, FakeSecrets(h.secretsMap), failingTransport,
            clock = { h.now }, random = { 0.5 }
        )
        val event = h.paymentEvent()
        val delivery = h.delivery(event, listOf("r1"))

        processor.processDueWork()

        val after = h.deliveryState(delivery.deliveryId)!!
        assertEquals(DeliveryState.IN_FLIGHT, after.state)
        val attempts = h.attemptsFor(delivery.deliveryId)
        assertEquals(1, attempts.size)
        assertEquals(AttemptState.COMPLETED, attempts[0].state)
        assertEquals("CHECKPOINT_FAILED", attempts[0].resultClass)
        assertEquals(0, h.transport.posted.size)
    }

    @Test
    fun retentionCleanup_deletesOldHeartbeatEventsOnly() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        // Old ACKED heartbeat: deleted.
        val hb = h.heartbeatEvent("hb-r1-100")
        val hbDelivery = h.delivery(hb, listOf("r1"))
        h.deliveryDao.setStateIfNotTerminal(hbDelivery.deliveryId, DeliveryState.ACKED, h.now)
        // Backdate the terminal timestamp past the 30-day retention.
        val acked = h.deliveryState(hbDelivery.deliveryId)!!.copy(
            terminalAtMs = h.now - 31L * 24 * 60 * 60 * 1000
        )
        h.store.deliveries[hbDelivery.deliveryId] = acked
        // Payment event with an ACKED delivery: never auto-deleted.
        val pay = h.paymentEvent()
        val payDelivery = h.delivery(pay, listOf("r1"))
        h.deliveryDao.setStateIfNotTerminal(payDelivery.deliveryId, DeliveryState.ACKED, h.now)
        val payAcked = h.deliveryState(payDelivery.deliveryId)!!.copy(
            terminalAtMs = h.now - 31L * 24 * 60 * 60 * 1000
        )
        h.store.deliveries[payDelivery.deliveryId] = payAcked

        val deleted = h.processor.runRetentionCleanup()

        assertEquals(1, deleted)
        assertNull(h.eventDao.getById(hb.eventId))
        assertNotNull(h.eventDao.getById(pay.eventId))
    }

    @Test
    fun isStopped_breaksBatch() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        val r1 = h.route("r1")
        h.routes.routesList = listOf(r1)
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))
        h.transport.script = { http(200, matchedBody("ok")) }
        val event1 = h.paymentEvent(dedupeKey = "com.gojek.gopay|1794960000000|100800|first")
        val delivery1 = h.delivery(event1, listOf("r1"))
        val event2 = h.paymentEvent(dedupeKey = "com.gojek.gopay|1794960000000|100800|second")
        val delivery2 = h.delivery(event2, listOf("r1"))
        var processed = 0
        // Stop after the first delivery is processed.
        val stoppingTransport = object : TransportClient by h.transport {
            override suspend fun post(
                request: TransportRequest,
                onSocketOpen: suspend () -> Boolean,
            ): TransportResult {
                processed++
                return h.transport.post(request, onSocketOpen)
            }
        }
        val processor = DeliveryProcessor(
            h.deliveryDao, h.eventDao, h.attemptDao, h.transactions,
            h.routes, FakeSecrets(h.secretsMap), stoppingTransport,
            clock = { h.now }, random = { 0.5 }
        )

        processor.processDueWork(isStopped = { processed >= 1 })

        assertEquals(DeliveryState.ACKED, h.deliveryState(delivery1.deliveryId)!!.state)
        // Claimed but never processed: still IN_FLIGHT until the lease expires.
        assertEquals(DeliveryState.IN_FLIGHT, h.deliveryState(delivery2.deliveryId)!!.state)
    }

    @Test
    fun backoffDelay_starts1MinDoublesCapsAt2h() {
        val h = Harness()
        assertEquals(60_000L, h.processor.backoffDelayMs(1))
        assertEquals(120_000L, h.processor.backoffDelayMs(2))
        assertEquals(240_000L, h.processor.backoffDelayMs(3))
        assertEquals(2 * 60 * 60 * 1000L, h.processor.backoffDelayMs(8))
        assertEquals(2 * 60 * 60 * 1000L, h.processor.backoffDelayMs(100))
    }
}
