package com.netter.paylist.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.FakeTransport
import com.netter.paylist.Route
import com.netter.paylist.SenderLock
import com.netter.paylist.Store
import com.netter.paylist.ToggleSecrets
import com.netter.paylist.chainQkey
import com.netter.paylist.chainQueueItem
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.PaylistDatabase
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.delivery.DeliveryProcessor
import com.netter.paylist.delivery.StoreRouteSource
import com.netter.paylist.delivery.TransportRequest
import com.netter.paylist.paymentPayload
import com.netter.paylist.privacy.CutoverPrivacyPreflight
import com.netter.paylist.privacy.GateBackedInspector
import com.netter.paylist.privacy.LegacyQueuePrivacyGate
import com.netter.paylist.security.SecretResult
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
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * P2 — quarantine reconciliation after `WORKER_ONLY` (ChatGPT re-review #5,
 * review 6011401446): "verify visibility, recoverability and durable
 * accounting for quarantined financial items after WORKER_ONLY."
 *
 * Before this change, a quarantined item was visible only as an aggregate:
 * the phase marker held `quarantined_count` + a checksum, the cutover
 * summary logged "N dikarantina (privasi)", and the per-item reason lived
 * only on the queue item's JSON in SharedPreferences — no durable per-item
 * record in Room, nothing reconcilable, nothing an operator could act on
 * per item. The drain now writes a durable per-item [QuarantineRecord]
 * (identity + reason + financial metadata, never content) in the same
 * transaction as the phase marker, and [CutoverCoordinator.reconcileQuarantine]
 * checks the marker against those records.
 *
 * What each test proves:
 * - Visibility: after `WORKER_ONLY`, every quarantined item has a durable
 *   per-item record (qkey, dedupe_key, reason, span classes, amount,
 *   package) and the drain result names each item — distinguishable from
 *   corrupt and expired items, which get no quarantine record.
 * - Recoverability, never-preflighted: an item quarantined by the MAIN
 *   drain for lack of a preflight marker recovers via the R08 re-drain
 *   (preflight + import + sweep) and is delivered by the worker (exactly
 *   one POST + `ACKED`); its record resolves to `imported` and stays as
 *   the audit trail.
 * - Recoverability, genuine privacy quarantine: an `ambiguous_span` /
 *   `pure_auth_no_financial_record` item stays quarantined across the
 *   re-drain — there is NO recovery path for it in Stage C (the gate's
 *   verdict is sticky by design, and the discard/approve review surface
 *   is Stage D work). This test pins the fail-safe behaviour instead of
 *   pretending a path exists: the item stays visible, durably recorded,
 *   never delivered, never lost, never double-owned. OPEN FINDING.
 * - Durable accounting: the conservation equations hold with asserted
 *   numbers (snapshot == importable + quarantined; queue total ==
 *   snapshot + expired + corrupt; imported == importable on a first
 *   drain); the marker + records survive a Room close/reopen and still
 *   reconcile.
 *
 * Honest limits, stated here:
 * - `runCutover` itself cannot be driven in this harness
 *   (`CAPTURE_TO_ROOM_READY` is false until Stage D), so the per-item
 *   log lines it writes from `DrainResult.quarantinedItems` are not
 *   executed here — but the field they format IS asserted in every
 *   test, and the durable records (the surface that outlives the log)
 *   are asserted directly.
 * - Delivery is driven through [DeliveryProcessor.processDueWork],
 *   not a WorkManager SyncWorker (the R08 pattern): the worker is a
 *   thin shell around the processor.
 *
 * EXECUTION NOTE: these tests need a device or an emulator. They
 * COMPILE on laptopgrt2 (`assembleDebugAndroidTest`) but cannot
 * execute there — there is no device/emulator on that box. Do not
 * report them as "passing" from a compile-only gate.
 */
@RunWith(AndroidJUnit4::class)
class QuarantineReconciliationTest {

    private lateinit var ctx: Context
    private lateinit var db: PaylistDatabase
    private lateinit var store: Store

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE).edit().clear().commit()
        store = Store(ctx)
        store.saveRoutes(
            listOf(
                Route(
                    "route-1", "v2New", listOf("com.gojek.gopaymerchant"),
                    "https://example.invalid/notify-1", Route.DEFAULT_KEYWORDS, true,
                ),
                Route(
                    "route-2", "warganet", listOf("com.gojek.gopaymerchant"),
                    "https://example.invalid/notify-2", Route.DEFAULT_KEYWORDS, true,
                ),
            ),
        )
        // File-backed Room (P1-1): the marker + quarantine records
        // under test are durable state; an in-memory database would
        // prove nothing about it.
        ctx.deleteDatabase(TEST_DB_NAME)
        db = Room.databaseBuilder(ctx, PaylistDatabase::class.java, TEST_DB_NAME).build()
    }

    @After
    fun tearDown() {
        db.close()
        ctx.deleteDatabase(TEST_DB_NAME)
        ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // =====================================================================
    // Harness (the R08 pattern: real coordinator + real Store + real worker)
    // =====================================================================

    /** The queue source over the real [Store], as in production. */
    private fun storeQueueSource(): LegacyQueueSnapshotSource =
        object : LegacyQueueSnapshotSource {
            override fun queueSnapshot(): List<JSONObject> = store.queueSnapshot()
            override fun saveQueueSync(items: List<JSONObject>): Boolean =
                store.saveQueueSync(items)
        }

    private fun newCoordinator(queueSource: LegacyQueueSnapshotSource): CutoverCoordinator {
        val migration = LegacyQueueMigration(
            db.eventDao(),
            db.deliveryDao(),
            db.attemptDao(),
            db.promotionReceiptDao(),
            db.transactionRunner(),
        )
        return CutoverCoordinator(
            db.collectorStateDao(),
            db.deliveryDao(),
            db.attemptDao(),
            db.transactionRunner(),
            migration,
            queueSource,
            SenderLock.lock,
            neverSentGatePassed = true,
        )
    }

    private suspend fun setBootId(bootId: String) {
        db.collectorStateDao().put(
            CollectorStateEntity(
                CollectorStateKeys.BOOT_ID,
                JSONObject().put("boot_id", bootId).put("boot_started_at_ms", NOW).toString(),
                NOW,
            ),
        )
    }

    private fun seedQueue(items: List<JSONObject>) {
        assertTrue("queue seed must commit", store.saveQueueSync(items))
    }

    /** The production worker processor over the test's file-backed Room. */
    private fun newWorkerProcessor(transport: FakeTransport): DeliveryProcessor =
        DeliveryProcessor(
            deliveryDao = db.deliveryDao(),
            eventDao = db.eventDao(),
            attemptDao = db.attemptDao(),
            transactions = db.transactionRunner(),
            routes = StoreRouteSource(store),
            secrets = ToggleSecrets(SecretResult.Present("s3cr3t")),
            transport = transport,
        )

    /**
     * The sender lock around the pre-commit phase, as `runCutover`
     * takes it — with a 5 s test timeout instead of the production
     * 45 s (no contention is expected; fail fast if there is).
     */
    private suspend fun <T> withSenderLock(block: suspend () -> T): T {
        val locked = try {
            SenderLock.lock.tryLock(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        assertTrue("sender lock must be acquirable in tests", locked)
        try {
            return block()
        } finally {
            SenderLock.lock.unlock()
        }
    }

    /** The `dedupe_key` for a payment tag (via the same builder as the payload). */
    private fun dedupeKey(tag: String): String =
        paymentPayload(tag).optString("dedupe_key")

    /** Worker POSTs for one `dedupe_key` (parsed from the wire body). */
    private fun workerPostsForDedupe(dedupe: String, transport: FakeTransport): List<TransportRequest> {
        return transport.requests.filter {
            try {
                JSONObject(it.body).optString("dedupe_key") == dedupe
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun requestDrainOrFail(coordinator: CutoverCoordinator) {
        val requested = coordinator.requestDrain(NOW)
        assertTrue("requestDrain must succeed: $requested", requested is CutoverCommit.Success)
    }

    /**
     * The `runCutover` pre-commit phase, in production order: privacy
     * preflight → durable flush + verify (R07 gate) → commit
     * `DRAINING`, all under the sender lock.
     */
    private suspend fun preflightAndCommitDraining(coordinator: CutoverCoordinator) {
        withSenderLock {
            val preflight = CutoverPrivacyPreflight().run(storeQueueSource(), NOW)
            assertTrue("preflight must succeed: ${preflight.reason}", preflight.ok)
            val snapshot = store.queueSnapshot()
            val flushed = CutoverFlushGate.flushOrAbort(
                coordinator, storeQueueSource(), snapshot, NOW,
            )
            assertTrue(
                "flush gate must verify: ${(flushed as? FlushResult.Failed)?.reason}",
                flushed is FlushResult.Verified,
            )
            val committed = coordinator.commitDraining(NOW)
            assertTrue("commitDraining must succeed: $committed", committed is CutoverCommit.Success)
        }
    }

    private suspend fun drainAndCommitWorkerOnly(
        coordinator: CutoverCoordinator,
        inspector: LegacyQueueInspector = GateBackedInspector,
    ): DrainResult {
        val drained = coordinator.drain(inspector, NOW)
        assertTrue("drain must succeed: ${drained.error}", drained.ok)
        val workerOnly = coordinator.commitWorkerOnly(NOW)
        assertTrue("commitWorkerOnly must succeed: $workerOnly", workerOnly is CutoverCommit.Success)
        assertEquals(CutoverMode.WORKER_ONLY, coordinator.getMode())
        return drained
    }

    /**
     * The R08 post-commit re-drain, in the order `runCutover`
     * performs it: privacy preflight → coordinator re-drain, under
     * the sender lock (so no capture can land unmarked mid-redrain).
     */
    private suspend fun redrainLeftoversOrFail(coordinator: CutoverCoordinator): DrainResult {
        return withSenderLock {
            val preflight = CutoverPrivacyPreflight().run(storeQueueSource(), NOW)
            assertTrue("redrain preflight must succeed: ${preflight.reason}", preflight.ok)
            val redrained = coordinator.redrainLeftovers(GateBackedInspector, NOW)
            assertTrue("redrain must succeed: ${redrained.error}", redrained.ok)
            redrained
        }
    }

    /**
     * Asserts eventual claim/delivery for [tag] through the worker:
     * exactly one Room delivery (`ACKED`), exactly one Room event,
     * exactly one worker POST, and the legacy queue clean of the
     * `qkey` — one durable owner (Room), delivered, no duplicate.
     */
    private fun assertDeliveredByWorker(tag: String, workerTransport: FakeTransport) {
        val qkey = chainQkey(tag, ROUTES)
        val delivery = runBlocking { db.deliveryDao().getByLegacyQkey(qkey) }
        assertNotNull("Room delivery must exist for $tag", delivery)
        assertEquals(DeliveryState.ACKED, delivery!!.state)
        assertNotNull(
            "Room event must exist for $tag",
            runBlocking { db.eventDao().getByDedupeKey(dedupeKey(tag)) },
        )
        assertEquals(
            "the worker must POST exactly once for $tag",
            1,
            workerPostsForDedupe(dedupeKey(tag), workerTransport).size,
        )
        assertTrue(
            "the legacy queue must be clean of $tag after delivery",
            store.queueSnapshot().none { it.optString("qkey") == qkey },
        )
    }

    /**
     * Asserts a quarantined item is neither lost nor double-owned:
     * still in the legacy queue (its only owner), with no Room
     * delivery, no Room event, and no worker POST.
     */
    private fun assertQuarantinedHeld(
        qkey: String,
        dedupe: String,
        workerTransport: FakeTransport,
    ) {
        assertEquals(
            "the quarantined item must still sit in the legacy queue (its only owner)",
            1,
            store.queueSnapshot().count { it.optString("qkey") == qkey },
        )
        assertNull(
            "no Room delivery may exist for a quarantined item",
            runBlocking { db.deliveryDao().getByLegacyQkey(qkey) },
        )
        assertNull(
            "no Room event may exist for a quarantined item",
            runBlocking { db.eventDao().getByDedupeKey(dedupe) },
        )
        assertTrue(
            "the worker must never POST for a quarantined item",
            workerPostsForDedupe(dedupe, workerTransport).isEmpty(),
        )
    }

    // =====================================================================
    // Fixtures
    // =====================================================================

    /** A pure-auth payload: confirmed OTP span, NO financial content, NO amount. */
    private fun authOnlyPayload(tag: String): JSONObject {
        val dedupeKey = "com.gojek.gopaymerchant|1795000000000|otp|$tag"
        return JSONObject().apply {
            put("type", "payment")
            put("device", "moto-g57")
            put("pkg", "com.gojek.gopaymerchant")
            put("title", "Verifikasi")
            put("text", "Kode OTP Anda adalah 123456")
            put("notif_time", "2026-11-18T03:00:00Z")
            put("dedupe_key", dedupeKey)
        }
    }

    private fun authOnlyQkey(tag: String): String =
        LegacyQkey.build(
            authOnlyPayload(tag).optString("dedupe_key"),
            LegacyQkey.KIND_CHAIN,
            ROUTES,
        )

    /**
     * An expired heartbeat: bucket 1994440, four buckets before NOW's
     * bucket (1994444) — past the 2-bucket expiry, so the snapshot
     * skips it (never imported, never quarantined).
     */
    private fun expiredHeartbeatItem(): JSONObject {
        val dedupeKey = "heartbeat-1994440"
        return JSONObject().apply {
            put("qkey", LegacyQkey.build(dedupeKey, LegacyQkey.KIND_SINGLE, listOf("route-1")))
            put("kind", LegacyQkey.KIND_SINGLE)
            put("route_id", "route-1")
            put("next", 0)
            put("attempts", 0)
            put(
                "payload",
                JSONObject().apply {
                    put("type", "heartbeat")
                    put("device", "moto-g57")
                    put("pkg", "com.netter.paylist")
                    put("dedupe_key", dedupeKey)
                },
            )
        }
    }

    /** A corrupt queue item: a `qkey` with no kind, no routes, no payload. */
    private fun corruptItem(): JSONObject =
        JSONObject().apply { put("qkey", "corrupt|chain|route-1") }

    /** A clean payment already carrying the gate's reprocessed marker (no preflight needed). */
    private fun markedCleanItem(tag: String): JSONObject =
        chainQueueItem(paymentPayload(tag), ROUTES).apply {
            put(LegacyQueuePrivacyGate.KEY_REPROCESSED, true)
            put(LegacyQueuePrivacyGate.KEY_REPROCESSED_AT_MS, NOW)
        }

    // =====================================================================
    // Visibility: every quarantined item individually recorded + named
    // =====================================================================

    @Test
    fun mainDrainQuarantine_visiblePerItemInDurableRecords() = runBlocking {
        setBootId(BOOT_A)
        val cleanTag = "p2-vis-clean"
        val ambTag = "p2-vis-amb"
        val pureTag = "p2-vis-pure"
        val neverTag = "p2-vis-never"
        seedQueue(
            listOf(
                chainQueueItem(paymentPayload(cleanTag), ROUTES),
                // Bare code in its own clause: ambiguous, quarantined by the gate.
                chainQueueItem(
                    paymentPayload(ambTag, "Pembayaran Rp100.800 diterima. 123456"),
                    ROUTES,
                ),
                chainQueueItem(authOnlyPayload(pureTag), ROUTES),
            ),
        )
        val coordinator = newCoordinator(storeQueueSource())
        val workerTransport = FakeTransport()
        val processor = newWorkerProcessor(workerTransport)

        requestDrainOrFail(coordinator)
        preflightAndCommitDraining(coordinator)
        // The never-preflighted item lands after DRAINING (B3a-style),
        // so the main drain quarantines it for lack of a marker.
        seedQueue(
            store.queueSnapshot() + chainQueueItem(paymentPayload(neverTag), ROUTES),
        )

        val drained = drainAndCommitWorkerOnly(coordinator)
        assertEquals(1, drained.imported)
        assertEquals(1, drained.removed)
        assertEquals(3, drained.quarantined)
        assertEquals(0, drained.expiredSkipped)
        assertEquals(0, drained.corrupt)

        // The drain result names each quarantined item individually
        // (qkey + dedupe_key + reason) — the data `runCutover` logs.
        assertEquals(3, drained.quarantinedItems.size)
        val reasonsByDedupe = drained.quarantinedItems.associate { it.dedupeKey to it.reason }
        assertEquals(
            LegacyQueuePrivacyGate.REASON_AMBIGUOUS,
            reasonsByDedupe[dedupeKey(ambTag)],
        )
        assertEquals(
            LegacyQueuePrivacyGate.REASON_PURE_AUTH,
            reasonsByDedupe[authOnlyPayload(pureTag).optString("dedupe_key")],
        )
        assertEquals(
            LegacyQueueMigration.REASON_NOT_PREFLIGHTED,
            reasonsByDedupe[dedupeKey(neverTag)],
        )

        // The phase marker still holds the aggregate — and the
        // conservation equation holds with asserted numbers.
        val marker = coordinator.getPhaseMarker()
        assertNotNull("the phase marker must exist after WORKER_ONLY", marker)
        assertEquals("worker_only", marker!!.phase)
        assertEquals(4, marker.snapshotCount)
        assertEquals(1, marker.importableCount)
        assertEquals(3, marker.quarantinedCount)
        assertEquals(1, marker.importedCount)
        assertTrue(marker.verified)
        assertEquals(
            "snapshot == importable + quarantined",
            marker.snapshotCount,
            marker.importableCount + marker.quarantinedCount,
        )
        assertEquals(
            "imported == importable on a first drain",
            marker.importableCount,
            marker.importedCount,
        )

        // The durable per-item records: one per quarantined item, with
        // identity + reason + span classes + financial metadata.
        val records = coordinator.getQuarantineRecords()
        assertEquals(3, records.size)
        val byDedupe = records.associateBy { it.dedupeKey }
        val amb = byDedupe[dedupeKey(ambTag)]
        assertNotNull("the ambiguous item must have a record", amb)
        assertEquals(chainQkey(ambTag, ROUTES), amb!!.qkey)
        assertEquals(LegacyQueuePrivacyGate.REASON_AMBIGUOUS, amb.reason)
        assertEquals("AMBIGUOUS_CODE", amb.spanClasses)
        assertEquals(100_800L, amb.amount)
        assertEquals("com.gojek.gopaymerchant", amb.sourcePackage)
        val pure = byDedupe[authOnlyPayload(pureTag).optString("dedupe_key")]
        assertNotNull("the pure-auth item must have a record", pure)
        assertEquals(authOnlyQkey(pureTag), pure!!.qkey)
        assertEquals(LegacyQueuePrivacyGate.REASON_PURE_AUTH, pure.reason)
        assertEquals("OTP", pure.spanClasses)
        assertNull("a pure-auth item carries no amount", pure.amount)
        val never = byDedupe[dedupeKey(neverTag)]
        assertNotNull("the never-preflighted item must have a record", never)
        assertEquals(chainQkey(neverTag, ROUTES), never!!.qkey)
        assertEquals(LegacyQueueMigration.REASON_NOT_PREFLIGHTED, never.reason)
        assertNull("an uninspected item has no span classes", never.spanClasses)
        assertEquals(100_800L, never.amount)
        for (record in records) {
            assertNull("no record may resolve before any recovery", record.resolution)
            assertEquals(QuarantineRecord.SOURCE_DRAIN, record.source)
            assertEquals("each record names the main drain's fence", marker.fenceId, record.fenceId)
            assertEquals(marker.drainGeneration, record.drainGeneration)
        }

        // Reconciliation: marker vs records, exact.
        val reconciliation = coordinator.reconcileQuarantine()
        assertNotNull("reconciliation must exist once a marker does", reconciliation)
        assertTrue("marker and records must reconcile", reconciliation!!.ok)
        assertEquals(3, reconciliation.markerQuarantinedCount)
        assertEquals(3, reconciliation.recordCount)
        assertEquals(3, reconciliation.unresolvedCount)
        assertEquals(0, reconciliation.resolvedCount)
        assertTrue(reconciliation.countsMatch)
        assertTrue(reconciliation.checksumMatches)

        // Neither lost nor double-owned: still in the queue, nothing in Room.
        assertQuarantinedHeld(chainQkey(ambTag, ROUTES), dedupeKey(ambTag), workerTransport)
        assertQuarantinedHeld(
            authOnlyQkey(pureTag),
            authOnlyPayload(pureTag).optString("dedupe_key"),
            workerTransport,
        )
        assertQuarantinedHeld(chainQkey(neverTag, ROUTES), dedupeKey(neverTag), workerTransport)
        // The queue markers tell the same story per item.
        val remaining = store.queueSnapshot()
        assertEquals(3, remaining.size)
        val ambQueued = remaining.single { it.optString("qkey") == chainQkey(ambTag, ROUTES) }
        assertTrue(ambQueued.optBoolean(LegacyQueuePrivacyGate.KEY_QUARANTINE))
        assertEquals(
            LegacyQueuePrivacyGate.REASON_AMBIGUOUS,
            ambQueued.optString(LegacyQueuePrivacyGate.KEY_QUARANTINE_REASON),
        )
        val neverQueued = remaining.single { it.optString("qkey") == chainQkey(neverTag, ROUTES) }
        assertFalse(neverQueued.optBoolean(LegacyQueuePrivacyGate.KEY_REPROCESSED, false))
        assertFalse(neverQueued.optBoolean(LegacyQueuePrivacyGate.KEY_QUARANTINE, false))

        // The clean item delivers through the worker; the worker never
        // POSTs for a quarantined item.
        assertFalse("no due work must remain after the pass", processor.processDueWork())
        assertDeliveredByWorker(cleanTag, workerTransport)
        assertEquals(
            "the worker must POST exactly once in total (the clean item only)",
            1,
            workerTransport.requests.size,
        )
        assertEquals(1, db.eventDao().count())
    }

    // =====================================================================
    // Recoverability: a never-preflighted item quarantined by the MAIN
    // drain recovers via the re-drain and is delivered (one owner)
    // =====================================================================

    @Test
    fun neverPreflightedMainDrainQuarantine_recoversViaRedrainAndDelivers() = runBlocking {
        setBootId(BOOT_A)
        val tag = "p2-rec-never"
        val coordinator = newCoordinator(storeQueueSource())
        val workerTransport = FakeTransport()
        val processor = newWorkerProcessor(workerTransport)

        // Empty queue at the preflight: the item lands only after
        // DRAINING, so the MAIN drain quarantines it (B3a mechanism).
        requestDrainOrFail(coordinator)
        preflightAndCommitDraining(coordinator)
        seedQueue(listOf(chainQueueItem(paymentPayload(tag), ROUTES)))

        val drained = drainAndCommitWorkerOnly(coordinator)
        assertEquals(0, drained.imported)
        assertEquals(1, drained.quarantined)
        assertEquals(1, drained.quarantinedItems.size)
        assertEquals(
            LegacyQueueMigration.REASON_NOT_PREFLIGHTED,
            drained.quarantinedItems.single().reason,
        )
        val marker = coordinator.getPhaseMarker()
        assertNotNull(marker)
        assertEquals(1, marker!!.snapshotCount)
        assertEquals(0, marker.importedCount)
        assertEquals(1, marker.quarantinedCount)

        // Stranded after WORKER_ONLY, but recorded: one unresolved
        // record, and the worker cannot see the payment.
        val before = coordinator.getQuarantineRecords()
        assertEquals(1, before.size)
        assertEquals(LegacyQueueMigration.REASON_NOT_PREFLIGHTED, before.single().reason)
        assertNull(before.single().resolution)
        assertEquals(marker.fenceId, before.single().fenceId)
        assertFalse(processor.processDueWork())
        assertQuarantinedHeld(chainQkey(tag, ROUTES), dedupeKey(tag), workerTransport)
        assertEquals(0, db.eventDao().count())

        // The recovery: preflight + re-drain imports it, and the
        // worker delivers it — exactly one POST + ACKED, one owner.
        val redrained = redrainLeftoversOrFail(coordinator)
        assertEquals(1, redrained.imported)
        assertEquals(1, redrained.removed)
        assertEquals(0, redrained.quarantined)
        assertFalse("no due work must remain after the pass", processor.processDueWork())
        assertDeliveredByWorker(tag, workerTransport)
        assertEquals(1, db.eventDao().count())
        assertTrue("the queue must be empty at the end", store.queueSnapshot().isEmpty())

        // The record stays as the audit trail — resolved, not deleted —
        // and the marker still reconciles (a resolved record still
        // counts: the item WAS quarantined by the main drain).
        val after = coordinator.getQuarantineRecords()
        assertEquals(1, after.size)
        assertEquals(QuarantineRecord.RESOLUTION_IMPORTED, after.single().resolution)
        assertNotNull(after.single().resolvedAtMs)
        assertEquals(marker.fenceId, after.single().fenceId)
        val reconciliation = coordinator.reconcileQuarantine()
        assertNotNull(reconciliation)
        assertTrue("marker and records must still reconcile", reconciliation!!.ok)
        assertEquals(1, reconciliation.recordCount)
        assertEquals(0, reconciliation.unresolvedCount)
        assertEquals(1, reconciliation.resolvedCount)
        // The phase marker still describes the MAIN drain, untouched
        // by the re-drain.
        val markerAfter = coordinator.getPhaseMarker()
        assertNotNull(markerAfter)
        assertEquals("worker_only", markerAfter!!.phase)
        assertEquals(1, markerAfter.snapshotCount)
        assertEquals(0, markerAfter.importedCount)
        assertEquals(1, markerAfter.quarantinedCount)
    }

    // =====================================================================
    // Recoverability, genuine privacy quarantine: NO path in Stage C.
    // OPEN FINDING — this test pins the fail-safe behaviour: visible,
    // durably recorded, never delivered, never lost, never double-owned.
    // =====================================================================

    @Test
    fun genuinePrivacyQuarantine_staysQuarantinedAfterRedrain() = runBlocking {
        setBootId(BOOT_A)
        val cleanTag = "p2-priv-clean"
        val ambTag = "p2-priv-amb"
        val pureTag = "p2-priv-pure"
        seedQueue(
            listOf(
                chainQueueItem(paymentPayload(cleanTag), ROUTES),
                chainQueueItem(
                    paymentPayload(ambTag, "Pembayaran Rp100.800 diterima. 123456"),
                    ROUTES,
                ),
                chainQueueItem(authOnlyPayload(pureTag), ROUTES),
            ),
        )
        val coordinator = newCoordinator(storeQueueSource())
        val workerTransport = FakeTransport()
        val processor = newWorkerProcessor(workerTransport)

        requestDrainOrFail(coordinator)
        preflightAndCommitDraining(coordinator)
        val drained = drainAndCommitWorkerOnly(coordinator)
        assertEquals(1, drained.imported)
        assertEquals(2, drained.quarantined)

        // The clean item delivers; the quarantined items are held.
        assertFalse(processor.processDueWork())
        assertDeliveredByWorker(cleanTag, workerTransport)
        assertQuarantinedHeld(chainQkey(ambTag, ROUTES), dedupeKey(ambTag), workerTransport)
        assertQuarantinedHeld(
            authOnlyQkey(pureTag),
            authOnlyPayload(pureTag).optString("dedupe_key"),
            workerTransport,
        )

        // The re-drain cannot rescue them: the preflight skips
        // already-quarantined items (their verdict is sticky), and
        // the fail-closed inspector quarantines them again. No
        // discard/approve path exists in Stage C — the review
        // surface that owns that decision is Stage D work.
        val redrained = redrainLeftoversOrFail(coordinator)
        assertEquals(0, redrained.imported)
        assertEquals(0, redrained.removed)
        assertEquals(2, redrained.quarantined)
        assertFalse(processor.processDueWork())
        assertQuarantinedHeld(chainQkey(ambTag, ROUTES), dedupeKey(ambTag), workerTransport)
        assertQuarantinedHeld(
            authOnlyQkey(pureTag),
            authOnlyPayload(pureTag).optString("dedupe_key"),
            workerTransport,
        )
        assertEquals(
            "the worker must POST exactly once in total (the clean item only)",
            1,
            workerTransport.requests.size,
        )

        // ...but they are not lost and not double-recorded: still
        // exactly two records, still unresolved, still naming the
        // main drain's fence (`putIfAbsent` — the re-drain neither
        // duplicates nor overwrites them).
        val records = coordinator.getQuarantineRecords()
        assertEquals(2, records.size)
        assertTrue(records.all { it.resolution == null })
        assertTrue(records.all { it.source == QuarantineRecord.SOURCE_DRAIN })
        val marker = coordinator.getPhaseMarker()
        assertNotNull(marker)
        assertTrue(records.all { it.fenceId == marker!!.fenceId })
        val reconciliation = coordinator.reconcileQuarantine()
        assertNotNull(reconciliation)
        assertTrue("marker and records must reconcile", reconciliation!!.ok)
        assertEquals(2, reconciliation.unresolvedCount)
        assertEquals(0, reconciliation.resolvedCount)
        // Both items still sit in the holding pen with their markers.
        val remaining = store.queueSnapshot()
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.optBoolean(LegacyQueuePrivacyGate.KEY_QUARANTINE) })
    }

    // =====================================================================
    // Durable accounting: conservation + survival across a Room restart
    // =====================================================================

    @Test
    fun quarantineAccounting_survivesRestartAndReconciles() = runBlocking {
        setBootId(BOOT_A)
        val cleanTag = "p2-acct-clean"
        val ambTag = "p2-acct-amb"
        val heartbeatQkey = expiredHeartbeatItem().optString("qkey")
        seedQueue(
            listOf(
                chainQueueItem(paymentPayload(cleanTag), ROUTES),
                chainQueueItem(
                    paymentPayload(ambTag, "Pembayaran Rp100.800 diterima. 123456"),
                    ROUTES,
                ),
                expiredHeartbeatItem(),
            ),
        )
        val coordinator = newCoordinator(storeQueueSource())

        requestDrainOrFail(coordinator)
        preflightAndCommitDraining(coordinator)
        val drained = drainAndCommitWorkerOnly(coordinator)
        assertEquals(1, drained.imported)
        assertEquals(1, drained.quarantined)
        assertEquals(1, drained.expiredSkipped)
        assertEquals(0, drained.corrupt)

        // Conservation, with asserted numbers: the queue total is the
        // snapshot plus the expired and corrupt items the snapshot
        // excludes; the snapshot is importable + quarantined.
        val marker = coordinator.getPhaseMarker()
        assertNotNull(marker)
        assertEquals(2, marker!!.snapshotCount)
        assertEquals(1, marker.importableCount)
        assertEquals(1, marker.quarantinedCount)
        assertEquals(1, marker.importedCount)
        assertEquals(
            "queue total == snapshot + expired + corrupt",
            3,
            marker.snapshotCount + drained.expiredSkipped + drained.corrupt,
        )
        assertEquals(
            "snapshot == importable + quarantined",
            marker.snapshotCount,
            marker.importableCount + marker.quarantinedCount,
        )
        assertEquals(
            "imported == importable on a first drain",
            marker.importableCount,
            marker.importedCount,
        )

        // The expired heartbeat is distinguishable from quarantine:
        // no record, no Room delivery — it is expired, not held.
        val recordsBefore = coordinator.getQuarantineRecords()
        assertEquals(1, recordsBefore.size)
        assertEquals(chainQkey(ambTag, ROUTES), recordsBefore.single().qkey)
        assertNull(
            "no Room delivery may exist for an expired heartbeat",
            db.deliveryDao().getByLegacyQkey(heartbeatQkey),
        )

        // Simulate a process restart: close + reopen the Room file,
        // then read the marker + records back through a fresh
        // coordinator — everything must be intact and reconciling.
        val markerJsonBefore = marker.toJson().toString()
        val recordJsonBefore = recordsBefore.single().toJson().toString()
        db.close()
        db = Room.databaseBuilder(ctx, PaylistDatabase::class.java, TEST_DB_NAME).build()
        val reopened = newCoordinator(storeQueueSource())
        assertEquals(CutoverMode.WORKER_ONLY, reopened.getMode())
        val markerAfter = reopened.getPhaseMarker()
        assertNotNull("the phase marker must survive a restart", markerAfter)
        assertEquals(markerJsonBefore, markerAfter!!.toJson().toString())
        val recordsAfter = reopened.getQuarantineRecords()
        assertEquals(1, recordsAfter.size)
        assertEquals(recordJsonBefore, recordsAfter.single().toJson().toString())
        val reconciliation = reopened.reconcileQuarantine()
        assertNotNull(reconciliation)
        assertTrue("marker and records must reconcile after a restart", reconciliation!!.ok)
        // The quarantined item itself survives too (queue file).
        assertEquals(
            "the quarantined item must still sit in the legacy queue after a restart",
            1,
            store.queueSnapshot().count { it.optString("qkey") == chainQkey(ambTag, ROUTES) },
        )
        assertNull(
            "no Room delivery may exist for the quarantined item after a restart",
            db.deliveryDao().getByLegacyQkey(chainQkey(ambTag, ROUTES)),
        )
    }

    // =====================================================================
    // Corrupt items are counted, not quarantined; conservation holds
    // =====================================================================

    @Test
    fun corruptItems_countedNotQuarantined_conservationHolds() = runBlocking {
        setBootId(BOOT_A)
        val cleanTag = "p2-corrupt-clean"
        seedQueue(listOf(markedCleanItem(cleanTag), corruptItem()))
        val coordinator = newCoordinator(storeQueueSource())

        // No preflight here: the preflight refuses a corrupt queue
        // (runCutover aborts before DRAINING). The coordinator drain
        // itself counts corrupt items — that is what this test proves.
        // The clean item already carries its gate marker, so it is
        // importable without a preflight.
        requestDrainOrFail(coordinator)
        val committed = coordinator.commitDraining(NOW)
        assertTrue("commitDraining must succeed: $committed", committed is CutoverCommit.Success)
        val drained = drainAndCommitWorkerOnly(coordinator)
        assertEquals(1, drained.imported)
        assertEquals(1, drained.removed)
        assertEquals(0, drained.quarantined)
        assertEquals(0, drained.expiredSkipped)
        assertEquals(1, drained.corrupt)
        assertTrue("no item may be named quarantined", drained.quarantinedItems.isEmpty())

        val marker = coordinator.getPhaseMarker()
        assertNotNull(marker)
        assertEquals(1, marker!!.snapshotCount)
        assertEquals(1, marker.importableCount)
        assertEquals(0, marker.quarantinedCount)
        assertEquals(1, marker.importedCount)
        assertEquals(
            "queue total == snapshot + expired + corrupt",
            2,
            marker.snapshotCount + drained.expiredSkipped + drained.corrupt,
        )
        assertEquals(
            "an empty quarantine set checksums to the empty checksum",
            LegacyQueueMigration.quarantineChecksumOf(emptyList()),
            marker.quarantineChecksum,
        )
        assertTrue(
            "a corrupt item gets no quarantine record",
            coordinator.getQuarantineRecords().isEmpty(),
        )
        val reconciliation = coordinator.reconcileQuarantine()
        assertNotNull(reconciliation)
        assertTrue("an empty quarantine reconciles", reconciliation!!.ok)
        assertEquals(0, reconciliation.recordCount)

        // The corrupt item is not lost either: still in the queue,
        // never imported, never swept (the sweep only touches
        // importable items).
        assertEquals(
            "the corrupt item must still sit in the legacy queue",
            1,
            store.queueSnapshot().count { it.optString("qkey") == "corrupt|chain|route-1" },
        )
        assertNull(
            "no Room delivery may exist for a corrupt item",
            db.deliveryDao().getByLegacyQkey("corrupt|chain|route-1"),
        )
    }

    companion object {
        /** File-backed Room database for these tests (P1-1, never in-memory). */
        const val TEST_DB_NAME: String = "paylist-p2-test.db"

        const val NOW: Long = 1_795_000_000_000L

        const val BOOT_A: String = "boot-p2-a"

        val ROUTES: List<String> = listOf("route-1", "route-2")
    }
}
