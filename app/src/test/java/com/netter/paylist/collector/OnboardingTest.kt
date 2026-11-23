package com.netter.paylist.collector

import com.netter.paylist.FakeAttemptDao
import com.netter.paylist.FakeCaptureLogDao
import com.netter.paylist.FakeCaptureStagingDao
import com.netter.paylist.FakeClassificationDao
import com.netter.paylist.FakeCollectorStateDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeStore
import com.netter.paylist.FakeTransactionRunner
import com.netter.paylist.Route
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.EventRepository
import com.netter.paylist.data.entity.CaptureLogEntity
import com.netter.paylist.data.entity.CaptureOutcome
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.delivery.DeliveryProcessor
import com.netter.paylist.delivery.RouteSource
import com.netter.paylist.delivery.TransportClient
import com.netter.paylist.delivery.TransportRequest
import com.netter.paylist.delivery.TransportResult
import com.netter.paylist.history.HistoryRepository
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.migration.LegacyEnqueue
import com.netter.paylist.migration.LegacyQkey
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
import java.security.MessageDigest
import java.util.UUID

/**
 * Onboarding fixtures F15, F16, F21 (design §10, §7.5):
 * consent-gated capture, revocation + retention + deletion,
 * and the route lifecycle (revoke/delete/disable/URL change,
 * explicit reactivation).
 *
 * The legacy-queue half of the sweep (hold/release of queue
 * items) is covered here through the [RouteQueueStore] fake;
 * the flush-side skip of held items is covered by the
 * instrumented SenderBFlushTest (Sender needs a real Context).
 */
class OnboardingTest {

    companion object {
        const val PKG = "com.gojek.gopaymerchant"
        const val NOW = 1_794_960_000_000L
        const val DAY_MS = 86_400_000L
    }

    private class FakeRoutes(var routesList: List<Route>) : RouteSource {
        override fun routes(): List<Route> = routesList
        override fun routeById(id: String): Route? = routesList.firstOrNull { it.id == id }
        override fun deviceLabel(): String = "Moto g57"
    }

    private class FakeSecrets(var map: Map<String, SecretResult>) : SecretProvider {
        override fun secretFor(routeId: String): SecretResult =
            map[routeId] ?: SecretResult.Missing
    }

    private class FakeTransport : TransportClient {
        val posted = mutableListOf<String>()
        var script: (TransportRequest) -> TransportResult = {
            TransportResult.Http(200, emptyMap(), """{"ok":true,"match":{"status":"matched","apply":"ok"}}""")
        }

        override suspend fun post(
            request: TransportRequest,
            onSocketOpen: suspend () -> Boolean,
        ): TransportResult {
            if (!onSocketOpen()) return TransportResult.CheckpointFailed
            posted += request.body
            return script(request)
        }
    }

    /** In-memory [RouteQueueStore]: routes + the legacy queue. */
    private class FakeQueueStore : RouteQueueStore {
        val routes = mutableListOf<Route>()
        val queueItems = mutableListOf<JSONObject>()

        override fun queue(): List<JSONObject> = queueItems.toList()
        override fun saveQueueSync(items: List<JSONObject>): Boolean {
            queueItems.clear()
            queueItems.addAll(items)
            return true
        }

        override fun deleteRoute(id: String) {
            routes.removeAll { it.id == id }
        }

        override fun upsertRoute(route: Route) {
            routes.removeAll { it.id == route.id }
            routes += route
        }
    }

    private inner class Harness {
        val store = FakeStore()
        val eventDao = FakeEventDao(store)
        val deliveryDao = FakeDeliveryDao(store)
        val attemptDao = FakeAttemptDao(store)
        val stagingDao = FakeCaptureStagingDao(store)
        val classificationDao = FakeClassificationDao(store)
        val captureLogDao = FakeCaptureLogDao(store)
        val collectorDao = FakeCollectorStateDao(store)
        val transactions = FakeTransactionRunner(store)
        val routes = FakeRoutes(emptyList())
        val secrets = FakeSecrets(emptyMap())
        val transport = FakeTransport()
        val consentStore = ConsentStore(collectorDao)
        val rawSnapshots = RawSnapshotStore(clock = { NOW })
        val queueStore = FakeQueueStore()
        val legacyQueue = mutableListOf<JSONObject>()
        val logLines = mutableListOf<String>()
        var mode: CutoverMode = CutoverMode.LEGACY_ONLY
        var now: Long = NOW

        val repository = EventRepository(
            eventDao, deliveryDao, stagingDao, classificationDao, transactions,
        )

        val legacyEnqueue = LegacyEnqueue { payload, kind, routeIds ->
            val qkey = LegacyQkey.build(payload.optString("dedupe_key"), kind, routeIds)
            if (legacyQueue.none { it.optString("qkey") == qkey }) {
                legacyQueue.add(JSONObject().apply {
                    put("kind", kind)
                    put("route_ids", JSONArray(routeIds))
                    put("next", 0)
                    put("payload", payload)
                    put("qkey", qkey)
                })
            }
            true
        }

        fun coordinator(): CaptureCoordinator = CaptureCoordinator(
            repository = repository,
            modeReader = { mode },
            legacyEnqueue = legacyEnqueue,
            routesProvider = { routes.routes() },
            deviceIdProvider = { "g57" },
            logger = object : CaptureLogSink {
                override fun logEvent(pkg: String, text: String) {
                    logLines.add("$pkg: $text")
                }

                override fun logLocal(text: String) {
                    logLines.add(text)
                }
            },
            clock = { now },
            consentStore = consentStore,
            captureLogDao = captureLogDao,
        )

        fun lifecycle(): RouteLifecycle = RouteLifecycle(
            deliveryDao, transactions, consentStore, routes,
            queueStore = queueStore, rawSnapshotStore = rawSnapshots,
            clock = { now },
        )

        val processor: DeliveryProcessor
            get() = DeliveryProcessor(
                deliveryDao, eventDao, attemptDao, transactions,
                routes, secrets, transport, clock = { now },
            )

        val history = HistoryRepository(
            captureLogDao, eventDao, deliveryDao, attemptDao,
            collectorStateDao = collectorDao, clock = { now },
        )

        suspend fun setMode(m: String) {
            collectorDao.put(
                CollectorStateEntity(
                    CollectorStateKeys.CUTOVER_STATE,
                    """{"mode":"$m"}""",
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

        fun route(id: String, url: String = "https://$id.example.invalid/notify.php") =
            Route(id, "Rute $id", listOf(PKG), url, listOf("gopay"), true)

        /** Registers a route in BOTH the RouteSource and the queue store. */
        fun addRoute(r: Route) {
            routes.routesList = routes.routesList + r
            queueStore.upsertRoute(r)
        }

        fun snapshot(amountText: String = "Rp100.800 diterima di GoPay kamu"): NotificationSnapshot =
            NotificationSnapshot(
                key = "key-$NOW",
                packageName = PKG,
                postTimeMs = NOW,
                flags = 0,
                title = "GoPay",
                text = amountText,
                bigText = "",
                capturedAtMs = NOW + 1_000L,
            )

        suspend fun paymentEvent(
            dedupeKey: String,
            category: String = "payment",
            occurredAtMs: Long = NOW,
        ): EventEntity {
            val event = EventEntity(
                eventId = UUID.randomUUID().toString(),
                sourceType = "android_notification",
                sourceEventId = dedupeKey,
                deviceId = "Moto g57",
                eventType = "payment.notification",
                category = category,
                sourcePackage = PKG,
                occurredAtMs = occurredAtMs,
                capturedAtMs = occurredAtMs,
                dataJson = """{"amount":100800,"currency":"IDR"}""",
                metadataJson = "{}",
                legacyPayloadJson = """{"type":"payment","amount":100800,"dedupe_key":"$dedupeKey"}""",
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
            routeIds: List<String> = listOf("r1"),
            state: DeliveryState = DeliveryState.PENDING,
            routeStampsJson: String = "{}",
            terminalAtMs: Long? = null,
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
                terminalAtMs = terminalAtMs,
            )
            deliveryDao.insert(d)
            return d
        }

        suspend fun captureRow(event: EventEntity): CaptureLogEntity {
            val row = CaptureLogEntity(
                captureId = UUID.randomUUID().toString(),
                sourcePackage = PKG,
                postTimeMs = NOW,
                capturedAtMs = NOW,
                outcome = CaptureOutcome.CAPTURED,
                titleSnippet = "GoPay",
                amountMinor = 100800L,
                eventId = event.eventId,
                contentSha256 = null,
            )
            captureLogDao.insert(row)
            return row
        }

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    // --- F15: onboarding consent ---

    @Test
    fun f15_noCaptureBeforeOptIn_grantEnablesCapture() = runBlocking {
        val h = Harness()
        h.addRoute(h.route("r1"))
        val coordinator = h.coordinator()
        // No consent record at all: the notification is dropped
        // before any parse, gate, or durable write.
        coordinator.processSnapshot(h.snapshot())
        assertEquals(0, h.eventDao.count())
        assertEquals(1L, coordinator.counters().noConsent)

        // Grant (onboarding opt-in completes): the same
        // notification now captures.
        h.consentStore.grant("r1")
        coordinator.processSnapshot(h.snapshot())
        assertEquals(1, h.eventDao.count())
        coordinator.close()
    }

    @Test
    fun f15_revocationStopsCapture_regrantResumes() = runBlocking {
        val h = Harness()
        h.addRoute(h.route("r1"))
        h.consentStore.grant("r1")
        val coordinator = h.coordinator()
        coordinator.processSnapshot(h.snapshot())
        assertEquals(1, h.eventDao.count())

        h.consentStore.revoke("r1")
        coordinator.processSnapshot(h.snapshot())
        assertEquals(1, h.eventDao.count())
        assertEquals(1L, coordinator.counters().noConsent)

        h.consentStore.grant("r1")
        coordinator.processSnapshot(h.snapshot())
        // The re-capture is a duplicate of the first event (same
        // dedupe key), so the event count stays 1 — but the
        // capture path ran again (no new noConsent count).
        assertEquals(1, h.eventDao.count())
        assertEquals(1L, coordinator.counters().noConsent)
        coordinator.close()
    }

    @Test
    fun f15_consentRecordPersisted_withDisclosureVersion() = runBlocking {
        val h = Harness()
        h.consentStore.grant("r1")
        val record = h.consentStore.get("r1")
        assertNotNull(record)
        assertTrue(record!!.granted)
        assertEquals(ConsentStore.DISCLOSURE_VERSION, record.disclosureVersion)
        assertEquals(0L, record.epoch)
        // A second route has an independent record.
        assertNull(h.consentStore.get("r2"))
        h.consentStore.grant("r2")
        assertEquals(0L, h.consentStore.epoch("r2"))
        assertEquals(0L, h.consentStore.epoch("r1"))
    }

    @Test
    fun f15_rawOptIn_defaultOff_requiresExplicitGrant() = runBlocking {
        val h = Harness()
        h.consentStore.grant("r1")
        // Grant alone never grants the raw opt-in (P6 default OFF).
        assertFalse(h.consentStore.isRawOptInValid("r1", NOW))
        // An explicit opt-in without an expiry is invalid (fail-closed).
        h.consentStore.setRawOptIn("r1", true, null)
        assertFalse(h.consentStore.isRawOptInValid("r1", NOW))
        // An explicit opt-in with a future expiry is valid.
        h.consentStore.setRawOptIn("r1", true, NOW + DAY_MS)
        assertTrue(h.consentStore.isRawOptInValid("r1", NOW))
        // Revocation clears the opt-in.
        h.consentStore.revoke("r1")
        assertFalse(h.consentStore.isRawOptInValid("r1", NOW))
    }

    @Test
    fun f15_upgradeMigration_grantsExistingRoutes_onlyOnce() = runBlocking {
        val h = Harness()
        // The v2.3 upgrade path: routes exist, no consent records.
        val lifecycle = h.lifecycle()
        assertEquals(2, lifecycle.migrateUpgradeConsent(listOf("r1", "r2")))
        assertTrue(h.consentStore.get("r1")!!.granted)
        assertTrue(h.consentStore.get("r2")!!.granted)
        // A second run grants nothing (records already exist).
        assertEquals(0, lifecycle.migrateUpgradeConsent(listOf("r1", "r2")))
        // A revoked route is NOT re-granted by the migration.
        h.consentStore.revoke("r1")
        assertEquals(0, lifecycle.migrateUpgradeConsent(listOf("r1")))
        assertFalse(h.consentStore.get("r1")!!.granted)
    }

    // --- F16: revocation + retention + deletion ---

    @Test
    fun f16_paymentRetainedPast30Days_heartbeatDeleted() = runBlocking {
        val h = Harness()
        val old = NOW - 45 * DAY_MS
        val payment = h.paymentEvent("com.gojek.gopaymerchant|1|100800|pay", occurredAtMs = old)
        h.delivery(payment, state = DeliveryState.ACKED, terminalAtMs = old)
        val heartbeat = h.paymentEvent(
            "com.gojek.gopaymerchant|2|0|hb", category = "heartbeat", occurredAtMs = old,
        )
        h.delivery(heartbeat, state = DeliveryState.ACKED, terminalAtMs = old)

        val deleted = h.processor.runRetentionCleanup()

        assertEquals(1, deleted.heartbeatDeleted)
        assertNotNull(h.eventDao.getById(payment.eventId))
        assertNull(h.eventDao.getById(heartbeat.eventId))
    }

    @Test
    fun f16_revoke_blocksPending_settledUntouched_noFurtherAttempts() = runBlocking {
        val h = Harness()
        h.addRoute(h.route("r1"))
        h.consentStore.grant("r1")
        h.setMode("WORKER_ONLY")
        h.setBootId()
        h.secrets.map = mapOf("r1" to SecretResult.Present("s1"))
        val e1 = h.paymentEvent("com.gojek.gopaymerchant|1|100800|a")
        val pending = h.delivery(e1)
        val e2 = h.paymentEvent("com.gojek.gopaymerchant|2|100800|b")
        val acked = h.delivery(e2, state = DeliveryState.ACKED)

        val result = h.lifecycle().revokeRoute("r1")
        assertEquals(1, result.blockedDeliveries)
        val after = h.deliveryDao.getById(pending.deliveryId)!!
        assertEquals(DeliveryState.BLOCKED, after.state)
        assertEquals(RouteLifecycle.ERROR_CONSENT_REVOKED, after.lastErrorCode)
        // The settled delivery is untouched.
        assertEquals(DeliveryState.ACKED, h.deliveryDao.getById(acked.deliveryId)!!.state)
        // The worker makes no further attempt for the blocked delivery.
        h.processor.processDueWork()
        assertTrue(h.transport.posted.isEmpty())
    }

    @Test
    fun f16_claimSkipsRevokedRoute_noDispatch() = runBlocking {
        val h = Harness()
        h.addRoute(h.route("r1"))
        h.consentStore.grant("r1")
        h.setMode("WORKER_ONLY")
        h.setBootId()
        h.secrets.map = mapOf("r1" to SecretResult.Present("s1"))
        // A delivery stamped at capture (epoch 0), then revoked
        // WITHOUT the eager sweep: the DAO claim check skips it
        // (defence in depth — the sweep is what BLOCKs it).
        val e = h.paymentEvent("com.gojek.gopaymerchant|1|100800|a")
        val d = h.delivery(
            e,
            routeStampsJson = """{"r1":{"consent_epoch":0,"config_epoch":0,"url_sha256":""}}""",
        )
        h.consentStore.revoke("r1")

        h.processor.processDueWork()

        assertEquals(DeliveryState.PENDING, h.deliveryDao.getById(d.deliveryId)!!.state)
        assertTrue(h.transport.posted.isEmpty())
    }

    @Test
    fun f16_claimTimeUrlChange_blocksDelivery() = runBlocking {
        val h = Harness()
        val oldUrl = "https://r1.example.invalid/notify.php"
        h.addRoute(h.route("r1", url = oldUrl))
        h.consentStore.grant("r1")
        h.setMode("WORKER_ONLY")
        h.setBootId()
        h.secrets.map = mapOf("r1" to SecretResult.Present("s1"))
        val e = h.paymentEvent("com.gojek.gopaymerchant|1|100800|a")
        val d = h.delivery(
            e,
            routeStampsJson = """{"r1":{"consent_epoch":0,"config_epoch":0,"url_sha256":"${h.sha256Hex(oldUrl)}"}}""",
        )
        // The operator changes the URL: the claim-time stamp
        // check blocks the delivery (url_changed).
        h.routes.routesList = listOf(h.route("r1", url = "https://r1.example.invalid/notify-v2.php"))

        h.processor.processDueWork()

        val after = h.deliveryDao.getById(d.deliveryId)!!
        assertEquals(DeliveryState.BLOCKED, after.state)
        assertEquals("url_changed", after.lastErrorCode)
        assertTrue(h.transport.posted.isEmpty())
    }

    @Test
    fun f16_localDelete_removesLocalRowsOnly() = runBlocking {
        val h = Harness()
        val e = h.paymentEvent("com.gojek.gopaymerchant|1|100800|a")
        h.delivery(e, state = DeliveryState.ACKED)
        val row = h.captureRow(e)
        // Local deletion removes the event + its deliveries from
        // this device; the server copy (already sent) is untouched
        // by construction — there is no recall call.
        assertTrue(h.history.deleteEvent(row.captureId))
        assertNull(h.eventDao.getById(e.eventId))
        assertTrue(h.deliveryDao.getByEventId(e.eventId).isEmpty())
        // The capture row survives, unlinked (FK SET NULL).
        val kept = h.captureLogDao.getById(row.captureId)
        assertNotNull(kept)
        assertNull(kept!!.eventId)
    }

    // --- F21: route lifecycle ---

    @Test
    fun f21_deleteRoute_blocksPending_noAutoRevivalOnRecreate() = runBlocking {
        val h = Harness()
        h.addRoute(h.route("r1"))
        h.consentStore.grant("r1")
        val e = h.paymentEvent("com.gojek.gopaymerchant|1|100800|a")
        val d = h.delivery(e)
        val lifecycle = h.lifecycle()

        val result = lifecycle.deleteRoute("r1")
        assertEquals(1, result.blockedDeliveries)
        assertEquals(DeliveryState.BLOCKED, h.deliveryDao.getById(d.deliveryId)!!.state)
        assertEquals(
            RouteLifecycle.ERROR_ROUTE_DELETED,
            h.deliveryDao.getById(d.deliveryId)!!.lastErrorCode,
        )
        // In production the RouteSource and the queue store are the
        // same Store, so one delete removes the route from both
        // views; the harness holds two lists — refresh the read
        // side from the store side (same as disable/enable).
        h.routes.routesList = h.queueStore.routes.toList()
        assertNull(h.routes.routeById("r1"))

        // Recreate the route: the blocked delivery does NOT revive.
        h.addRoute(h.route("r1"))
        assertEquals(DeliveryState.BLOCKED, h.deliveryDao.getById(d.deliveryId)!!.state)

        // Explicit reactivation revives it (consent still granted).
        val reactivated = lifecycle.reactivateRoute("r1")
        assertNull(reactivated.refusedReason)
        assertEquals(1, reactivated.reactivatedDeliveries)
        assertEquals(DeliveryState.PENDING, h.deliveryDao.getById(d.deliveryId)!!.state)
    }

    @Test
    fun f21_disableRoute_blocks_reenableNeedsExplicitReactivation() = runBlocking {
        val h = Harness()
        h.addRoute(h.route("r1"))
        h.consentStore.grant("r1")
        val e = h.paymentEvent("com.gojek.gopaymerchant|1|100800|a")
        val d = h.delivery(e)
        val lifecycle = h.lifecycle()

        lifecycle.disableRoute("r1")
        assertEquals(DeliveryState.BLOCKED, h.deliveryDao.getById(d.deliveryId)!!.state)
        assertEquals(
            RouteLifecycle.ERROR_ROUTE_DISABLED,
            h.deliveryDao.getById(d.deliveryId)!!.lastErrorCode,
        )
        // The RouteSource still serves the old list object: refresh it.
        h.routes.routesList = h.queueStore.routes.toList()
        assertFalse(h.routes.routeById("r1")!!.enabled)

        // Re-enable alone does not revive.
        lifecycle.enableRoute("r1")
        h.routes.routesList = h.queueStore.routes.toList()
        assertTrue(h.routes.routeById("r1")!!.enabled)
        assertEquals(DeliveryState.BLOCKED, h.deliveryDao.getById(d.deliveryId)!!.state)

        // Explicit reactivation revives.
        val reactivated = lifecycle.reactivateRoute("r1")
        assertNull(reactivated.refusedReason)
        assertEquals(1, reactivated.reactivatedDeliveries)
        assertEquals(DeliveryState.PENDING, h.deliveryDao.getById(d.deliveryId)!!.state)
    }

    @Test
    fun f21_reactivateRefused_withoutConsentOrRoute() = runBlocking {
        val h = Harness()
        h.addRoute(h.route("r1"))
        val e = h.paymentEvent("com.gojek.gopaymerchant|1|100800|a")
        val d = h.delivery(e)
        val lifecycle = h.lifecycle()
        // Blocked by a sweep while consent existed, then consent revoked.
        h.consentStore.grant("r1")
        lifecycle.disableRoute("r1")
        h.consentStore.revoke("r1")
        lifecycle.enableRoute("r1")
        h.routes.routesList = h.queueStore.routes.toList()

        val refused = lifecycle.reactivateRoute("r1")
        assertNotNull(refused.refusedReason)
        assertEquals(0, refused.reactivatedDeliveries)
        assertEquals(DeliveryState.BLOCKED, h.deliveryDao.getById(d.deliveryId)!!.state)

        // Unknown route: refused too.
        val unknown = lifecycle.reactivateRoute("no-such-route")
        assertNotNull(unknown.refusedReason)
    }

    @Test
    fun f21_urlChange_blocks_reapprovalRestamps() = runBlocking {
        val h = Harness()
        val oldUrl = "https://r1.example.invalid/notify.php"
        h.addRoute(h.route("r1", url = oldUrl))
        h.consentStore.grant("r1")
        h.setMode("WORKER_ONLY")
        h.setBootId()
        h.secrets.map = mapOf("r1" to SecretResult.Present("s1"))
        val e = h.paymentEvent("com.gojek.gopaymerchant|1|100800|a")
        val d = h.delivery(e)
        val lifecycle = h.lifecycle()

        // The operator saves a new URL: pending work blocks.
        val newRoute = h.route("r1", url = "https://r1.example.invalid/notify-v2.php")
        h.queueStore.upsertRoute(newRoute)
        h.routes.routesList = listOf(newRoute)
        val swept = lifecycle.urlChanged("r1")
        assertEquals(1, swept.blockedDeliveries)
        assertEquals(
            RouteLifecycle.ERROR_URL_CHANGED,
            h.deliveryDao.getById(d.deliveryId)!!.lastErrorCode,
        )

        // Re-approval re-stamps the URL snapshot and revives.
        val reactivated = lifecycle.reactivateRoute("r1")
        assertNull(reactivated.refusedReason)
        assertEquals(1, reactivated.reactivatedDeliveries)
        val revived = h.deliveryDao.getById(d.deliveryId)!!
        assertEquals(DeliveryState.PENDING, revived.state)

        // The claim now passes the stamp check and dispatches
        // under the NEW url (PENDING picks up new config at claim).
        h.processor.processDueWork()
        assertEquals(1, h.transport.posted.size)
        assertEquals(DeliveryState.ACKED, h.deliveryDao.getById(d.deliveryId)!!.state)
    }

    @Test
    fun f21_secretRotation_dispatchedUnderSameUrl_loggedWithoutValue() = runBlocking {
        val h = Harness()
        h.addRoute(h.route("r1"))
        h.consentStore.grant("r1")
        h.setMode("WORKER_ONLY")
        h.setBootId()
        h.secrets.map = mapOf("r1" to SecretResult.Present("secret-pertama"))
        val e = h.paymentEvent("com.gojek.gopaymerchant|1|100800|a")
        val d = h.delivery(e)

        // Rotate the secret before the claim: the dispatch uses
        // the current secret under the same URL.
        h.secrets.map = mapOf("r1" to SecretResult.Present("secret-kedua"))
        h.processor.processDueWork()

        assertEquals(DeliveryState.ACKED, h.deliveryDao.getById(d.deliveryId)!!.state)
        assertEquals(1, h.transport.posted.size)
        // The secret travels in the Authorization header, never
        // in the POST body — and never in a log line. (Processor
        // logging goes to Logcat, unobservable in a unit test;
        // the body assertion is the executable half here.)
        assertFalse(h.transport.posted[0].contains("secret-pertama"))
        assertFalse(h.transport.posted[0].contains("secret-kedua"))
        for (line in h.logLines) {
            assertFalse(line.contains("secret-pertama"))
            assertFalse(line.contains("secret-kedua"))
        }
    }

    @Test
    fun f21_stagingRowsNotPromotedAfterRevocation() = runBlocking {
        val h = Harness()
        h.addRoute(h.route("r1"))
        h.consentStore.grant("r1")
        h.mode = CutoverMode.WORKER_ONLY
        val coordinator = h.coordinator()
        // Capture stages the event (WORKER_ONLY promotes to Room).
        coordinator.processSnapshot(h.snapshot())
        assertEquals(1, h.eventDao.count())
        // Revoke, then capture again with a different amount: the
        // new notification never stages.
        h.consentStore.revoke("r1")
        coordinator.processSnapshot(
            h.snapshot("Rp200.500 diterima di GoPay kamu"),
        )
        assertEquals(1, h.eventDao.count())
        assertEquals(1L, coordinator.counters().noConsent)
        coordinator.close()
    }

    @Test
    fun f21_revokeHoldsLegacyQueueItems_reactivateReleases() = runBlocking {
        val h = Harness()
        h.addRoute(h.route("r1"))
        h.addRoute(h.route("r2"))
        h.consentStore.grant("r1")
        h.consentStore.grant("r2")
        val item = JSONObject().apply {
            put("kind", "chain")
            put("route_ids", JSONArray(listOf("r1", "r2")))
            put("next", 0)
            put("payload", JSONObject().put("type", "payment"))
            put("qkey", "k1|chain|r1,r2")
        }
        h.queueStore.queueItems += item
        val lifecycle = h.lifecycle()

        // Revoke r1: the item is held (never sent, never deleted).
        val swept = lifecycle.revokeRoute("r1")
        assertEquals(1, swept.heldQueueItems)
        val held = h.queueStore.queue().first()
        assertEquals(1, held.getJSONArray(RouteLifecycle.HELD_ROUTES_KEY).length())
        assertEquals("r1", held.getJSONArray(RouteLifecycle.HELD_ROUTES_KEY).getString(0))

        // Revoke r2 too: the item is now held for both routes.
        lifecycle.revokeRoute("r2")
        assertEquals(2, h.queueStore.queue().first().getJSONArray(RouteLifecycle.HELD_ROUTES_KEY).length())

        // Reactivate r1 only (re-grant first): the r2 hold remains.
        h.consentStore.grant("r1")
        val released = lifecycle.reactivateRoute("r1")
        assertEquals(1, released.releasedQueueItems)
        val stillHeld = h.queueStore.queue().first()
        assertEquals(1, stillHeld.getJSONArray(RouteLifecycle.HELD_ROUTES_KEY).length())
        assertEquals("r2", stillHeld.getJSONArray(RouteLifecycle.HELD_ROUTES_KEY).getString(0))

        // Reactivate r2: the item is fully released.
        h.consentStore.grant("r2")
        lifecycle.reactivateRoute("r2")
        assertFalse(h.queueStore.queue().first().has(RouteLifecycle.HELD_ROUTES_KEY))
    }
}
