package com.netter.paylist.collector

import com.netter.paylist.FakeCollectorStateDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeMatchResultDao
import com.netter.paylist.FakeStore
import com.netter.paylist.FakeTransactionRunner
import com.netter.paylist.Route
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.AdvisoryMatchStatus
import com.netter.paylist.data.entity.AttributionStatus
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.data.entity.MatchResultEntity
import com.netter.paylist.data.entity.MatchRuleId
import com.netter.paylist.migration.QuarantineRecord
import com.netter.paylist.migration.QuarantineRecordStore
import com.netter.paylist.privacy.LegacyQueuePrivacyGate
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
 * Stage D review workflow tests (§7.6 pick protocol, §7.3B
 * authorise/discard, §7.7 quarantine workflow): durable
 * state-transition tests + audit retention.
 */
class AmountReviewTest {

    companion object {
        private const val PKG = "com.gojek.gopay"
        private const val POST_TIME = 1_794_960_000_000L
        private const val ROUTE_ID = "r1"
        private const val ROUTE_URL = "https://example.test/notify.php"
        private const val RECORDER_URL = "https://example.test/catat.php"
        private const val OPERATOR = "operator-uji"
    }

    private class FakeLegacySweep : LegacySweep {
        val present = mutableSetOf<String>()
        var forced: SweepOutcome? = null
        var sweptKeys = mutableListOf<String>()

        override fun exists(dedupeKey: String): Boolean = present.contains(dedupeKey)

        override fun sweep(dedupeKey: String): SweepOutcome {
            sweptKeys += dedupeKey
            forced?.let { return it }
            return if (present.remove(dedupeKey)) SweepOutcome.REMOVED else SweepOutcome.ABSENT
        }
    }

    private class FakeQuarantineQueue : QuarantineQueue {
        val items = mutableMapOf<String, JSONObject>() // qkey -> item

        override fun findByDedupeKey(dedupeKey: String): JSONObject? =
            items.values.firstOrNull {
                it.optJSONObject("payload")?.optString("dedupe_key") == dedupeKey
            }

        override fun removeByQkey(qkey: String): Boolean = items.remove(qkey) != null
    }

    private class FakeQuarantineRecordStore : QuarantineRecordStore {
        val records = mutableMapOf<String, QuarantineRecord>()

        override suspend fun listAll(): List<QuarantineRecord> =
            records.values.sortedBy { it.qkey }

        override suspend fun get(dedupeKey: String): QuarantineRecord? = records[dedupeKey]

        override suspend fun resolve(
            dedupeKey: String,
            resolution: String,
            operator: String,
            note: String?,
            nowMs: Long,
        ): Boolean {
            val current = records[dedupeKey] ?: return false
            if (current.resolution != null) return current.resolution == resolution
            records[dedupeKey] = current.copy(
                resolution = resolution,
                resolvedAtMs = nowMs,
                operator = operator,
                decisionNote = note,
            )
            return true
        }
    }

    private class FakeReviewRoutes(
        var routes: MutableMap<String, Route> = mutableMapOf(),
    ) : ReviewRouteSource {
        override fun routeById(routeId: String): Route? = routes[routeId]
        override fun deviceLabel(): String = "Moto g57"
    }

    private inner class Harness(
        var recorderGatePassed: Boolean = false,
    ) {
        val store = FakeStore()
        val eventDao = FakeEventDao(store)
        val deliveryDao = FakeDeliveryDao(store)
        val collectorDao = FakeCollectorStateDao(store)
        val matchDao = FakeMatchResultDao(store)
        val transactions = FakeTransactionRunner(store)
        val consentStore = ConsentStore(collectorDao, clock = { now })
        val sweep = FakeLegacySweep()
        val queue = FakeQuarantineQueue()
        val quarantineStore = FakeQuarantineRecordStore()
        val reviewRoutes = FakeReviewRoutes()
        var now = POST_TIME

        val review: AmountReview
            get() = AmountReview(
                deliveryDao, eventDao, collectorDao, matchDao, transactions,
                quarantineStore, sweep, queue, reviewRoutes, consentStore,
                recorderGatePassed = recorderGatePassed,
                clock = { now },
            )

        fun route(
            id: String = ROUTE_ID,
            url: String = ROUTE_URL,
            enabled: Boolean = true,
        ): Route {
            val r = Route(id, "Rute $id", listOf(PKG), url, listOf("pembayaran"), enabled)
            reviewRoutes.routes[id] = r
            return r
        }

        suspend fun grant(routeId: String = ROUTE_ID) {
            consentStore.grant(routeId)
        }

        fun stampFor(route: Route, epoch: Long = 0L): String =
            JSONObject().put(
                route.id,
                JSONObject().apply {
                    put("consent_epoch", epoch)
                    put("config_epoch", 0)
                    put("url_sha256", CaptureCoordinator.sha256Hex(route.url))
                },
            ).toString()

        fun dedupe(amount: Long): String =
            CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, amount, "Saldo Rp500.000. Pembayaran Rp100.000 berhasil")

        fun payloadJson(
            amount: Long,
            dedupeKey: String,
            tokens: List<Long>? = null,
            direction: String = "in",
        ): String = JSONObject().apply {
            put("type", "payment")
            put("amount", amount)
            put("title", "GoPay")
            put("text", "Pembayaran Rp100.000 berhasil")
            put("dedupe_key", dedupeKey)
            put("direction", direction)
            if (tokens != null) {
                put("amount_ambiguous", true)
                put("amount_tokens", JSONArray().apply {
                    for ((i, v) in tokens.withIndex()) {
                        put(JSONObject().apply {
                            put("value", v)
                            put("start", i * 10)
                            put("end", i * 10 + 5)
                        })
                    }
                })
            }
        }.toString()

        suspend fun event(
            amount: Long = 500000L,
            tokens: List<Long>? = listOf(500000L, 100000L),
            direction: String = "in",
            dedupeKey: String? = null,
        ): EventEntity {
            val key = dedupeKey ?: dedupe(amount)
            val e = EventEntity(
                eventId = UUID.randomUUID().toString(),
                sourceType = "android_notification",
                sourceEventId = key,
                deviceId = "Moto g57",
                eventType = "payment.notification",
                category = "payment",
                sourcePackage = PKG,
                occurredAtMs = POST_TIME,
                capturedAtMs = POST_TIME,
                dataJson = """{"amount":$amount,"currency":"IDR"}""",
                metadataJson = """{"parser_version":"payment-v1","privacy_mode":"structured"}""",
                legacyPayloadJson = payloadJson(amount, key, tokens, direction),
                privacyMode = "structured",
                retentionUntilMs = null,
                dedupeKey = key,
                contentSha256 = CaptureCoordinator.sha256Hex("combined-$key"),
                exportRef = UUID.randomUUID().toString(),
                sourceAccountKey = null,
            )
            eventDao.insert(e)
            return e
        }

        suspend fun delivery(
            event: EventEntity,
            state: DeliveryState,
            routeIds: List<String> = listOf(ROUTE_ID),
            routeStampsJson: String = "{}",
            rawEphemeral: Boolean = false,
            lastErrorCode: String? = null,
        ): DeliveryEntity {
            val d = DeliveryEntity(
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
                lastErrorCode = lastErrorCode,
            )
            deliveryDao.insert(d)
            return d
        }

        suspend fun auditDocs(): List<JSONObject> =
            collectorDao.getAll()
                .filter { it.key.startsWith(CollectorStateKeys.REVIEW_AUDIT_PREFIX) }
                .map { JSONObject(it.valueJson) }

        suspend fun pickedPayload(deliveryId: String): JSONObject? =
            collectorDao.getValue(CollectorStateKeys.PICKED_PAYLOAD_PREFIX + deliveryId)
                ?.let { JSONObject(it) }

        fun quarantineRecord(
            dedupeKey: String,
            reason: String = LegacyQueuePrivacyGate.REASON_AMBIGUOUS,
            amount: Long? = 100800L,
        ): QuarantineRecord = QuarantineRecord(
            dedupeKey = dedupeKey,
            qkey = "$dedupeKey|chain|$ROUTE_ID",
            reason = reason,
            spanClasses = "b",
            amount = amount,
            sourcePackage = PKG,
            quarantinedAtMs = now,
            fenceId = "fence-1",
            drainGeneration = 1L,
            source = QuarantineRecord.SOURCE_DRAIN,
            resolution = null,
            resolvedAtMs = null,
        )
    }

    // --- Amount pick (§7.6) ---

    @Test
    fun pick_releasesToPending_newKey_auditKept() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val event = h.event()
        val delivery = h.delivery(event, DeliveryState.NEEDS_REVIEW, routeStampsJson = h.stampFor(route))
        val originalKey = event.dedupeKey

        val outcome = h.review.pickAmount(delivery.deliveryId, 100000L, OPERATOR)

        assertTrue(outcome is PickOutcome.Released)
        val released = outcome as PickOutcome.Released
        assertEquals(DeliveryState.DISCARDED, h.deliveryDao.getById(delivery.deliveryId)!!.state)
        val picked = h.deliveryDao.getById(released.deliveryId)!!
        assertEquals(DeliveryState.PENDING, picked.state)
        assertEquals(event.eventId, picked.eventId)
        // New key: same segments except the amount.
        val expectedKey = AmountReview.recomputeDedupeKey(originalKey, 100000L)!!
        assertEquals(expectedKey, released.newDedupeKey)
        assertTrue(expectedKey.contains("|100000|"))
        // Picked-payload override is durable.
        val override = h.pickedPayload(released.deliveryId)!!
        assertEquals(100000L, override.optLong("amount"))
        assertEquals(expectedKey, override.optString("dedupe_key"))
        // Audit: pick + release, operator + both keys recorded.
        val audits = h.auditDocs()
        assertEquals(2, audits.size)
        val pick = audits.first { it.optString("action") == "pick" }
        assertEquals(originalKey, pick.optString("original_dedupe_key"))
        assertEquals(expectedKey, pick.optString("new_dedupe_key"))
        assertEquals(100000L, pick.optLong("chosen_amount"))
        assertEquals(OPERATOR, pick.optString("operator"))
        assertTrue(audits.any { it.optString("action") == "pick_release" })
    }

    @Test
    fun pick_sameAmount_sameKey() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val event = h.event()
        val delivery = h.delivery(event, DeliveryState.NEEDS_REVIEW, routeStampsJson = h.stampFor(route))

        val outcome = h.review.pickAmount(delivery.deliveryId, 500000L, OPERATOR)

        assertTrue(outcome is PickOutcome.Released)
        assertEquals(event.dedupeKey, (outcome as PickOutcome.Released).newDedupeKey)
    }

    @Test
    fun pick_removesLegacyCopy() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val event = h.event()
        val delivery = h.delivery(event, DeliveryState.NEEDS_REVIEW, routeStampsJson = h.stampFor(route))
        h.sweep.present += event.dedupeKey

        val outcome = h.review.pickAmount(delivery.deliveryId, 100000L, OPERATOR)

        assertTrue(outcome is PickOutcome.Released)
        assertFalse(h.sweep.present.contains(event.dedupeKey))
        assertEquals(listOf(event.dedupeKey), h.sweep.sweptKeys)
        val release = h.auditDocs().first { it.optString("action") == "pick_release" }
        assertEquals("removed", release.optString("sweep"))
    }

    @Test
    fun pick_dispatchedCopy_blocked() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val event = h.event()
        val delivery = h.delivery(event, DeliveryState.NEEDS_REVIEW, routeStampsJson = h.stampFor(route))
        h.sweep.forced = SweepOutcome.DISPATCHED

        val outcome = h.review.pickAmount(delivery.deliveryId, 100000L, OPERATOR)

        assertTrue(outcome is PickOutcome.Blocked)
        val blocked = outcome as PickOutcome.Blocked
        assertEquals(DeliveryState.BLOCKED, h.deliveryDao.getById(blocked.deliveryId)!!.state)
        val audit = h.auditDocs().first { it.optString("action") == "pick_blocked" }
        assertEquals("prior_copy_dispatched", audit.optString("reason"))
    }

    @Test
    fun pick_unknownCopy_held_thenRecoveryReleases() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val event = h.event()
        val delivery = h.delivery(event, DeliveryState.NEEDS_REVIEW, routeStampsJson = h.stampFor(route))
        h.sweep.forced = SweepOutcome.UNKNOWN

        val outcome = h.review.pickAmount(delivery.deliveryId, 100000L, OPERATOR)

        assertTrue(outcome is PickOutcome.Held)
        val held = outcome as PickOutcome.Held
        assertEquals(
            DeliveryState.HELD_AWAITING_LEGACY_SWEEP,
            h.deliveryDao.getById(held.deliveryId)!!.state,
        )
        // Recovery re-sweeps: the copy is now absent, so it releases.
        h.sweep.forced = null
        val (released, blocked, stillHeld) = h.review.recoverHeldPicks()
        assertEquals(1, released)
        assertEquals(0, blocked)
        assertEquals(0, stillHeld)
        assertEquals(DeliveryState.PENDING, h.deliveryDao.getById(held.deliveryId)!!.state)
    }

    @Test
    fun pick_refusesNonTokenAmount() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val event = h.event()
        val delivery = h.delivery(event, DeliveryState.NEEDS_REVIEW, routeStampsJson = h.stampFor(route))

        val outcome = h.review.pickAmount(delivery.deliveryId, 999L, OPERATOR)

        assertTrue(outcome is PickOutcome.Refused)
        assertEquals(DeliveryState.NEEDS_REVIEW, h.deliveryDao.getById(delivery.deliveryId)!!.state)
        assertEquals(0, h.auditDocs().size)
    }

    @Test
    fun pick_refusesRevokedRoute() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val event = h.event()
        val delivery = h.delivery(event, DeliveryState.NEEDS_REVIEW, routeStampsJson = h.stampFor(route))
        h.consentStore.revoke(ROUTE_ID)

        val outcome = h.review.pickAmount(delivery.deliveryId, 100000L, OPERATOR)

        assertTrue(outcome is PickOutcome.Refused)
        assertEquals(DeliveryState.NEEDS_REVIEW, h.deliveryDao.getById(delivery.deliveryId)!!.state)
    }

    @Test
    fun pick_refusesChangedUrl() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val event = h.event()
        val delivery = h.delivery(event, DeliveryState.NEEDS_REVIEW, routeStampsJson = h.stampFor(route))
        h.route(url = "https://example.test/notify-baru.php")

        val outcome = h.review.pickAmount(delivery.deliveryId, 100000L, OPERATOR)

        assertTrue(outcome is PickOutcome.Refused)
        assertEquals(DeliveryState.NEEDS_REVIEW, h.deliveryDao.getById(delivery.deliveryId)!!.state)
    }

    @Test
    fun pick_refusesLiveSibling() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val event = h.event()
        val delivery = h.delivery(event, DeliveryState.NEEDS_REVIEW, routeStampsJson = h.stampFor(route))
        h.delivery(event, DeliveryState.PENDING, routeStampsJson = h.stampFor(route))

        val outcome = h.review.pickAmount(delivery.deliveryId, 100000L, OPERATOR)

        assertTrue(outcome is PickOutcome.Refused)
        assertEquals(DeliveryState.NEEDS_REVIEW, h.deliveryDao.getById(delivery.deliveryId)!!.state)
    }

    @Test
    fun pick_twice_secondRefused() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val event = h.event()
        val delivery = h.delivery(event, DeliveryState.NEEDS_REVIEW, routeStampsJson = h.stampFor(route))

        assertTrue(h.review.pickAmount(delivery.deliveryId, 100000L, OPERATOR) is PickOutcome.Released)
        val second = h.review.pickAmount(delivery.deliveryId, 500000L, OPERATOR)
        assertTrue(second is PickOutcome.Refused)
        // Exactly one live delivery for the event.
        val live = h.deliveryDao.getByEventId(event.eventId)
            .filter { it.state == DeliveryState.PENDING }
        assertEquals(1, live.size)
    }

    @Test
    fun recomputeDedupeKey_segments() {
        assertEquals(
            "pkg|123|100|hash",
            AmountReview.recomputeDedupeKey("pkg|123|500|hash", 100L),
        )
        assertEquals(
            "pkg|123|500|hash",
            AmountReview.recomputeDedupeKey("pkg|123|500|hash", 500L),
        )
        assertNull(AmountReview.recomputeDedupeKey("tidak-valid", 100L))
        assertNull(AmountReview.recomputeDedupeKey("pkg|bukan-angka|500|hash", 100L))
    }

    // --- Discard (§7.6(c)) ---

    @Test
    fun discard_keepsRecord_warnsLegacyCopy() = runBlocking {
        val h = Harness()
        h.route()
        val event = h.event()
        val delivery = h.delivery(event, DeliveryState.NEEDS_REVIEW)
        h.sweep.present += event.dedupeKey

        val outcome = h.review.discardAmount(delivery.deliveryId, OPERATOR, "salah kirim")

        assertTrue(outcome is DiscardOutcome.Discarded)
        assertTrue((outcome as DiscardOutcome.Discarded).legacyCopyExisted)
        assertEquals(DeliveryState.DISCARDED, h.deliveryDao.getById(delivery.deliveryId)!!.state)
        // The event row, token list, and audit are retained.
        val kept = h.eventDao.getById(event.eventId)!!
        assertTrue(JSONObject(kept.legacyPayloadJson!!).optBoolean("amount_ambiguous"))
        val audit = h.auditDocs().first { it.optString("action") == "discard" }
        assertTrue(audit.optBoolean("legacy_copy_existed"))
        assertEquals(OPERATOR, audit.optString("operator"))
    }

    // --- RAW_UNAVAILABLE authorise / discard (§7.3B) ---

    @Test
    fun authorizeFallback_passes_recordsOperatorVerification() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val event = h.event(amount = 100800L, tokens = null)
        val delivery = h.delivery(
            event, DeliveryState.RAW_UNAVAILABLE,
            routeStampsJson = h.stampFor(route), rawEphemeral = true,
            lastErrorCode = "fallback_refused",
        )

        val outcome = h.review.authorizeFallback(delivery.deliveryId, OPERATOR, "cek log server: belum ada")

        assertTrue(outcome is AuthorizeOutcome.Authorized)
        assertEquals(DeliveryState.PENDING, h.deliveryDao.getById(delivery.deliveryId)!!.state)
        val audit = h.auditDocs().first { it.optString("action") == "authorize_fallback" }
        assertTrue(audit.optBoolean("operator_verified_no_prior_post"))
        assertEquals(OPERATOR, audit.optString("operator"))
    }

    @Test
    fun authorizeFallback_refusesRecorderWithoutB14_thenPassesWithB14() = runBlocking {
        val h = Harness(recorderGatePassed = false)
        val route = h.route(url = RECORDER_URL)
        h.grant()
        val event = h.event(amount = 100800L, tokens = null)
        val delivery = h.delivery(
            event, DeliveryState.RAW_UNAVAILABLE,
            routeStampsJson = h.stampFor(route), rawEphemeral = true,
        )

        val refused = h.review.authorizeFallback(delivery.deliveryId, OPERATOR)
        assertTrue(refused is AuthorizeOutcome.Refused)
        assertEquals(DeliveryState.RAW_UNAVAILABLE, h.deliveryDao.getById(delivery.deliveryId)!!.state)

        // With B14 passed, the same authorisation goes through.
        h.recorderGatePassed = true
        val authorized = h.review.authorizeFallback(delivery.deliveryId, OPERATOR)
        assertTrue(authorized is AuthorizeOutcome.Authorized)
        assertEquals(DeliveryState.PENDING, h.deliveryDao.getById(delivery.deliveryId)!!.state)
    }

    @Test
    fun authorizeFallback_refusesUnknownDirection() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val event = h.event(amount = 100800L, tokens = null, direction = "unknown")
        val delivery = h.delivery(
            event, DeliveryState.RAW_UNAVAILABLE,
            routeStampsJson = h.stampFor(route), rawEphemeral = true,
        )

        val outcome = h.review.authorizeFallback(delivery.deliveryId, OPERATOR)
        assertTrue(outcome is AuthorizeOutcome.Refused)
        assertEquals(DeliveryState.RAW_UNAVAILABLE, h.deliveryDao.getById(delivery.deliveryId)!!.state)
    }

    @Test
    fun authorizeFallback_refusesRevokedConsent() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val event = h.event(amount = 100800L, tokens = null)
        val delivery = h.delivery(
            event, DeliveryState.RAW_UNAVAILABLE,
            routeStampsJson = h.stampFor(route), rawEphemeral = true,
        )
        h.consentStore.revoke(ROUTE_ID)

        val outcome = h.review.authorizeFallback(delivery.deliveryId, OPERATOR)
        assertTrue(outcome is AuthorizeOutcome.Refused)
        assertEquals(DeliveryState.RAW_UNAVAILABLE, h.deliveryDao.getById(delivery.deliveryId)!!.state)
    }

    @Test
    fun discardRawLost_keepsAudit_localDispositionOnly() = runBlocking {
        val h = Harness()
        h.route()
        val event = h.event(amount = 100800L, tokens = null)
        val delivery = h.delivery(
            event, DeliveryState.RAW_UNAVAILABLE, lastErrorCode = "fallback_refused",
        )

        val outcome = h.review.discardRawLost(delivery.deliveryId, OPERATOR)

        assertTrue(outcome is DiscardOutcome.Discarded)
        assertEquals(DeliveryState.DISCARDED, h.deliveryDao.getById(delivery.deliveryId)!!.state)
        val audit = h.auditDocs().first { it.optString("action") == "discard_raw" }
        assertTrue(audit.optBoolean("local_disposition_only"))
        assertEquals(100800L, audit.optLong("amount"))
        assertEquals(event.dedupeKey, audit.optString("original_dedupe_key"))
        assertNotNull(h.eventDao.getById(event.eventId))
    }

    // --- Quarantine workflow (§7.7, blocker C) ---

    @Test
    fun quarantine_approvePureAuth_noImport_removesRaw() = runBlocking {
        val h = Harness()
        h.route()
        val dedupe = h.dedupe(100800L)
        val record = h.quarantineRecord(dedupe, LegacyQueuePrivacyGate.REASON_PURE_AUTH)
        h.quarantineStore.records[dedupe] = record
        h.queue.items[record.qkey] = JSONObject().apply {
            put("qkey", record.qkey)
            put("payload", JSONObject().apply {
                put("dedupe_key", dedupe)
                put("type", "payment")
                put("title", "GoPay")
                put("text", "Kode OTP 482913")
            })
        }

        val outcome = h.review.approveQuarantine(dedupe, OPERATOR)

        assertTrue(outcome is QuarantineOutcome.Approved)
        assertFalse((outcome as QuarantineOutcome.Approved).imported)
        val resolved = h.quarantineStore.records[dedupe]!!
        assertEquals(QuarantineRecord.RESOLUTION_APPROVED, resolved.resolution)
        assertEquals(OPERATOR, resolved.operator)
        assertNotNull(resolved.resolvedAtMs)
        assertNull(h.queue.items[record.qkey])
        assertEquals(0, h.eventDao.count())
    }

    @Test
    fun quarantine_approveAmbiguous_importsRedacted() = runBlocking {
        val h = Harness()
        val route = h.route()
        h.grant()
        val dedupe = "com.gojek.gopay|1794960000000|100800|t9"
        val record = h.quarantineRecord(dedupe)
        h.quarantineStore.records[dedupe] = record
        h.queue.items[record.qkey] = JSONObject().apply {
            put("qkey", record.qkey)
            put("kind", "chain")
            put("route_ids", JSONArray().put(ROUTE_ID))
            put("payload", JSONObject().apply {
                put("dedupe_key", dedupe)
                put("type", "payment")
                put("amount", 100800L)
                put("title", "GoPay")
                put("text", "Kode OTP 482913. Rp100.800 diterima di GoPay kamu")
                put("direction", "in")
            })
        }

        val outcome = h.review.approveQuarantine(dedupe, OPERATOR)

        assertTrue(outcome is QuarantineOutcome.Approved)
        assertTrue((outcome as QuarantineOutcome.Approved).imported)
        assertEquals(QuarantineRecord.RESOLUTION_APPROVED, h.quarantineStore.records[dedupe]!!.resolution)
        // The raw queue item is gone; the imported event is redacted.
        assertNull(h.queue.items[record.qkey])
        val imported = h.eventDao.getByDedupeKey(dedupe)!!
        assertFalse("raw OTP must never be imported", imported.legacyPayloadJson!!.contains("482913"))
        assertTrue(imported.legacyPayloadJson!!.contains("[REDACTED]"))
        val deliveries = h.deliveryDao.getByEventId(imported.eventId)
        assertEquals(1, deliveries.size)
        assertEquals(DeliveryState.PENDING, deliveries[0].state)
        assertEquals(record.qkey, deliveries[0].legacyQkey)
        assertEquals(route.id, JSONArray(deliveries[0].routeIdsJson).optString(0))
    }

    @Test
    fun quarantine_approveAmbiguous_missingItem_refused_recordStaysOpen() = runBlocking {
        val h = Harness()
        h.route()
        val dedupe = h.dedupe(100800L)
        val record = h.quarantineRecord(dedupe)
        h.quarantineStore.records[dedupe] = record

        val outcome = h.review.approveQuarantine(dedupe, OPERATOR)

        assertTrue(outcome is QuarantineOutcome.Refused)
        assertNull(h.quarantineStore.records[dedupe]!!.resolution)
    }

    @Test
    fun quarantine_discard_removesRaw_keepsAudit() = runBlocking {
        val h = Harness()
        h.route()
        val dedupe = h.dedupe(100800L)
        val record = h.quarantineRecord(dedupe)
        h.quarantineStore.records[dedupe] = record
        h.queue.items[record.qkey] = JSONObject().apply {
            put("qkey", record.qkey)
            put("payload", JSONObject().apply {
                put("dedupe_key", dedupe)
                put("type", "payment")
                put("text", "Kode OTP 482913. Rp100.800 diterima")
            })
        }

        val outcome = h.review.discardQuarantine(dedupe, OPERATOR, "bukan pembayaran")

        assertTrue(outcome is QuarantineOutcome.Discarded)
        assertNull(h.queue.items[record.qkey])
        val resolved = h.quarantineStore.records[dedupe]!!
        assertEquals(QuarantineRecord.RESOLUTION_DISCARDED, resolved.resolution)
        assertEquals(OPERATOR, resolved.operator)
        assertEquals("bukan pembayaran", resolved.decisionNote)
        assertEquals(100800L, resolved.amount)
        // The audit retains the unresolved server-effect evidence.
        val audit = h.auditDocs().first { it.optString("action") == "quarantine_discard" }
        assertTrue(audit.optBoolean("local_disposition_only"))
        assertEquals(100800L, audit.optLong("amount"))
        // Unresolved count drops to zero.
        assertEquals(0, h.quarantineStore.listAll().count { it.resolution == null })
    }

    @Test
    fun quarantine_resolutionIsFinal() = runBlocking {
        val h = Harness()
        h.route()
        val dedupe = h.dedupe(100800L)
        h.quarantineStore.records[dedupe] =
            h.quarantineRecord(dedupe, LegacyQueuePrivacyGate.REASON_PURE_AUTH)

        assertTrue(h.review.approveQuarantine(dedupe, OPERATOR) is QuarantineOutcome.Approved)
        val second = h.review.discardQuarantine(dedupe, OPERATOR)
        assertTrue(second is QuarantineOutcome.AlreadyResolved)
        assertEquals(
            QuarantineRecord.RESOLUTION_APPROVED,
            (second as QuarantineOutcome.AlreadyResolved).resolution,
        )
    }

    // --- Review queue ---

    @Test
    fun listReviewQueue_allKinds_newestFirst() = runBlocking {
        val h = Harness()
        h.route()
        h.grant()
        val now0 = h.now
        val amountEvent = h.event()
        h.delivery(amountEvent, DeliveryState.NEEDS_REVIEW)
        h.now = now0 + 60_000L
        val rawEvent = h.event(amount = 100800L, tokens = null, dedupeKey = h.dedupe(100800L) + "x")
        h.delivery(rawEvent, DeliveryState.RAW_UNAVAILABLE)
        h.now = now0 + 120_000L
        val dedupe = h.dedupe(100800L)
        h.quarantineStore.records[dedupe] = h.quarantineRecord(dedupe).copy(quarantinedAtMs = h.now)
        h.now = now0 + 180_000L
        h.matchDao.insert(
            MatchResultEntity(
                matchId = UUID.randomUUID().toString(),
                eventId = amountEvent.eventId,
                merchantId = "merchant-a",
                orderNumber = "INV-100",
                bankReference = null,
                sourceAccountMasked = null,
                advisoryMatchStatus = AdvisoryMatchStatus.MANUAL_REVIEW,
                confidence = 0.5,
                ruleId = MatchRuleId.NO_ORDER,
                registryVersion = "v1",
                attributionStatus = AttributionStatus.NONE,
                auditJson = "{}",
                evidenceIdentity = null,
                reviewReason = "no order number",
                createdAtMs = h.now,
            ),
        )

        val queue = h.review.listReviewQueue()

        assertEquals(4, queue.size)
        assertEquals(4, h.review.reviewCount())
        assertTrue(queue[0] is ReviewItem.MatchQuestion)
        assertTrue(queue[1] is ReviewItem.Quarantined)
        assertTrue(queue[2] is ReviewItem.RawLost)
        assertTrue(queue[3] is ReviewItem.AmountQuestion)
        val amount = queue[3] as ReviewItem.AmountQuestion
        assertEquals(listOf(500000L, 100000L), amount.tokens.map { it.value })
    }

    // --- D5: P3 manual attribution pick ---

    private fun Harness.matchRow(
        matchId: String = UUID.randomUUID().toString(),
        eventId: String = UUID.randomUUID().toString(),
        merchantId: String? = "merchant-a",
        orderNumber: String? = "INV-100",
        status: String = AdvisoryMatchStatus.AMBIGUOUS,
        attribution: String = AttributionStatus.NONE,
    ) = MatchResultEntity(
        matchId = matchId,
        eventId = eventId,
        merchantId = merchantId,
        orderNumber = orderNumber,
        bankReference = "TRX-555",
        sourceAccountMasked = "****1234",
        advisoryMatchStatus = status,
        confidence = 0.5,
        ruleId = MatchRuleId.CONCURRENT_LOSER,
        registryVersion = "v1",
        attributionStatus = attribution,
        auditJson = "{}",
        evidenceIdentity = null,
        reviewReason = "ambiguous",
        createdAtMs = now,
    )

    @Test
    fun resolveMatch_attribute_becomesTheAttribution() = runBlocking {
        val h = Harness()
        val row = h.matchRow()
        h.matchDao.insert(row)

        val outcome = h.review.resolveMatch(row.matchId, attribute = true, OPERATOR)

        assertTrue(outcome is MatchResolveOutcome.Attributed)
        val updated = h.matchDao.getById(row.matchId)!!
        assertEquals(AttributionStatus.ATTRIBUTED, updated.attributionStatus)
        assertEquals(AdvisoryMatchStatus.MATCHED, updated.advisoryMatchStatus)
        assertEquals("merchant-a|INV-100", updated.attributedOrderKey)
        // The row leaves the review queue.
        assertTrue(h.review.listReviewQueue().none { it is ReviewItem.MatchQuestion })
        // The decision is audited.
        val audits = h.collectorDao.getAll().filter {
            it.key.startsWith(CollectorStateKeys.REVIEW_AUDIT_PREFIX)
        }
        assertEquals(1, audits.size)
        assertTrue(audits[0].valueJson.contains("match_attribute"))
        assertTrue(audits[0].valueJson.contains(OPERATOR))
    }

    @Test
    fun resolveMatch_attribute_existingAttribution_becomesDuplicateEvidence() = runBlocking {
        val h = Harness()
        val first = h.matchRow(matchId = "m-first")
        h.matchDao.insert(first)
        assertTrue(
            h.review.resolveMatch(first.matchId, attribute = true, OPERATOR)
                is MatchResolveOutcome.Attributed
        )
        val second = h.matchRow(matchId = "m-second")
        h.matchDao.insert(second)

        val outcome = h.review.resolveMatch(second.matchId, attribute = true, OPERATOR)

        assertTrue(outcome is MatchResolveOutcome.DuplicateEvidence)
        val updated = h.matchDao.getById(second.matchId)!!
        assertEquals(AttributionStatus.DUPLICATE_EVIDENCE, updated.attributionStatus)
        assertEquals("m-first", updated.attributedToMatchId)
        // Still exactly one attributed row for the order.
        assertEquals(
            "m-first",
            h.matchDao.getAttributed("merchant-a", "INV-100")!!.matchId,
        )
    }

    @Test
    fun resolveMatch_attribute_noOrder_refused() = runBlocking {
        val h = Harness()
        val row = h.matchRow(orderNumber = null, status = AdvisoryMatchStatus.MANUAL_REVIEW)
        h.matchDao.insert(row)

        val outcome = h.review.resolveMatch(row.matchId, attribute = true, OPERATOR)

        assertTrue(outcome is MatchResolveOutcome.Refused)
        assertEquals(
            AdvisoryMatchStatus.MANUAL_REVIEW,
            h.matchDao.getById(row.matchId)!!.advisoryMatchStatus,
        )
    }

    @Test
    fun resolveMatch_unmatched_marksRowAndLeavesQueue() = runBlocking {
        val h = Harness()
        val row = h.matchRow()
        h.matchDao.insert(row)

        val outcome = h.review.resolveMatch(row.matchId, attribute = false, OPERATOR)

        assertTrue(outcome is MatchResolveOutcome.MarkedUnmatched)
        val updated = h.matchDao.getById(row.matchId)!!
        assertEquals(AdvisoryMatchStatus.UNMATCHED, updated.advisoryMatchStatus)
        assertTrue(updated.reviewReason!!.contains(OPERATOR))
        assertTrue(h.review.listReviewQueue().none { it is ReviewItem.MatchQuestion })
    }

    @Test
    fun resolveMatch_alreadyDecided_refused() = runBlocking {
        val h = Harness()
        val row = h.matchRow()
        h.matchDao.insert(row)
        assertTrue(
            h.review.resolveMatch(row.matchId, attribute = false, OPERATOR)
                is MatchResolveOutcome.MarkedUnmatched
        )

        val outcome = h.review.resolveMatch(row.matchId, attribute = true, OPERATOR)

        assertTrue(outcome is MatchResolveOutcome.Refused)
    }
}
