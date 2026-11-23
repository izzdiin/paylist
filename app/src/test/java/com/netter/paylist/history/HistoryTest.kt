package com.netter.paylist.history

import com.netter.paylist.FakeAttemptDao
import com.netter.paylist.FakeCaptureLogDao
import com.netter.paylist.FakeCaptureStagingDao
import com.netter.paylist.FakeClassificationDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeStore
import com.netter.paylist.FakeTransactionRunner
import com.netter.paylist.Route
import com.netter.paylist.collector.CaptureCoordinator
import com.netter.paylist.collector.CaptureLogSink
import com.netter.paylist.collector.NotificationSnapshot
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.EventRepository
import com.netter.paylist.data.entity.CaptureLogEntity
import com.netter.paylist.data.entity.CaptureOutcome
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.delivery.DeliveryProcessor
import com.netter.paylist.delivery.RouteSource
import com.netter.paylist.delivery.TransportClient
import com.netter.paylist.delivery.TransportRequest
import com.netter.paylist.delivery.TransportResult
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.migration.LegacyEnqueue
import com.netter.paylist.migration.LegacyQkey
import com.netter.paylist.security.SecretProvider
import com.netter.paylist.security.SecretResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Addendum 06 acceptance tests (LH-T01–LH-T08, D→E gate).
 *
 * LH-T03/T04/T05 drive notifications through the real
 * [CaptureCoordinator] entrypoint; LH-T01/T06 run the real
 * [DeliveryProcessor.runRetentionCleanup]; LH-T02/T07 read through
 * the real [HistoryRepository]; LH-T08 is a static source check
 * (F27 pattern: fail-closed when the source root is not found).
 */
class HistoryTest {

    companion object {
        const val PKG = "com.gojek.gopaymerchant"
        const val ROUTE_ID = "route-v2new"
        const val POST_TIME = 1_794_960_000_000L // 2026-11-18T00:00:00Z
        const val DAY_MS = 24L * 60 * 60 * 1000
    }

    private class FakeRoutes : RouteSource {
        override fun routes(): List<Route> = emptyList()
        override fun routeById(id: String): Route? = null
        override fun deviceLabel(): String = "Moto g57"
    }

    private class FakeSecrets : SecretProvider {
        override fun secretFor(routeId: String): SecretResult = SecretResult.Missing
    }

    private class FakeTransport : TransportClient {
        override suspend fun post(
            request: TransportRequest,
            onSocketOpen: suspend () -> Boolean,
        ): TransportResult = TransportResult.Http(200, emptyMap(), "{}")
    }

    private inner class Harness {
        val store = FakeStore()
        val eventDao = FakeEventDao(store)
        val deliveryDao = FakeDeliveryDao(store)
        val attemptDao = FakeAttemptDao(store)
        val captureLogDao = FakeCaptureLogDao(store)
        val stagingDao = FakeCaptureStagingDao(store)
        val transactions = FakeTransactionRunner(store)
        val repository = EventRepository(
            eventDao, deliveryDao, stagingDao,
            FakeClassificationDao(store), transactions,
        )
        var now: Long = POST_TIME + 60_000L
        val legacyQueue = mutableListOf<JSONObject>()
        val logLines = mutableListOf<String>()

        val routes = listOf(
            Route(
                id = ROUTE_ID,
                name = "v2New (Netter)",
                packages = listOf(PKG),
                url = "https://bill.netterhome.net/bayarQR/notify.php",
                keywords = Route.DEFAULT_KEYWORDS,
                enabled = true,
            ),
        )

        val coordinator: CaptureCoordinator = CaptureCoordinator(
            repository = repository,
            modeReader = { CutoverMode.LEGACY_ONLY },
            legacyEnqueue = LegacyEnqueue { payload, kind, routeIds ->
                val qkey = LegacyQkey.build(
                    payload.optString("dedupe_key"), kind, routeIds,
                )
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
            clock = { now },
            captureLogDao = captureLogDao,
        )

        val processor: DeliveryProcessor
            get() = DeliveryProcessor(
                deliveryDao, eventDao, attemptDao, transactions,
                FakeRoutes(), FakeSecrets(), FakeTransport(),
                clock = { now },
                captureLogDao = captureLogDao,
            )

        val history = HistoryRepository(captureLogDao, eventDao, deliveryDao, attemptDao)

        fun snapshot(
            title: String = "Pembayaran berhasil",
            text: String = "Pembayaran Rp100.800 berhasil",
            pkg: String = PKG,
            postTime: Long = POST_TIME,
        ) = NotificationSnapshot(
            key = "key-$postTime-${text.hashCode()}",
            packageName = pkg,
            postTimeMs = postTime,
            flags = 0,
            title = title,
            text = text,
            bigText = "",
            capturedAtMs = postTime + 1_000L,
        )

        fun captureAndWait(s: NotificationSnapshot) {
            coordinator.capture(s)
            coordinator.sweepNow()
        }

        suspend fun paymentEvent(
            dedupeKey: String,
            amount: Long = 100800L,
            occurredAtMs: Long = POST_TIME,
            category: String = "payment",
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
                capturedAtMs = now,
                dataJson = """{"amount":$amount,"currency":"IDR"}""",
                metadataJson = """{"parser_version":"payment-v1","privacy_mode":"structured"}""",
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
            state: DeliveryState = DeliveryState.PENDING,
            lastMatchStatus: String? = null,
            lastApplyStatus: String? = null,
            terminalAtMs: Long? = null,
            routeIds: List<String> = listOf(ROUTE_ID),
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
                routeStampsJson = "{}",
                lastMatchStatus = lastMatchStatus,
                lastApplyStatus = lastApplyStatus,
                terminalAtMs = terminalAtMs,
            )
            deliveryDao.insert(delivery)
            return delivery
        }

        suspend fun captureRow(
            outcome: String,
            eventId: String? = null,
            amountMinor: Long? = null,
            postTimeMs: Long = POST_TIME,
            capturedAtMs: Long = now,
            sourcePackage: String = PKG,
            titleSnippet: String? = null,
            contentSha256: String? = null,
        ): CaptureLogEntity {
            val row = CaptureLogEntity(
                captureId = UUID.randomUUID().toString(),
                sourcePackage = sourcePackage,
                postTimeMs = postTimeMs,
                capturedAtMs = capturedAtMs,
                outcome = outcome,
                titleSnippet = titleSnippet,
                amountMinor = amountMinor,
                eventId = eventId,
                contentSha256 = contentSha256,
            )
            captureLogDao.insert(row)
            return row
        }

        fun rows(): List<CaptureLogEntity> = store.captureLog.values.toList()
    }

    private lateinit var h: Harness

    @Before
    fun setUp() {
        h = Harness()
    }

    @After
    fun tearDown() {
        h.coordinator.close()
    }

    // --- LH-T01: payment history survives; heartbeat history expires ---

    @Test
    fun lhT01_paymentSurvives_heartbeatExpires() = runBlocking {
        val old = h.now - 45 * DAY_MS
        val pay = h.paymentEvent("com.gojek.gopay|1|100800|pay", occurredAtMs = old)
        h.delivery(pay, DeliveryState.ACKED, terminalAtMs = old)
        h.captureRow(CaptureOutcome.CAPTURED, eventId = pay.eventId, amountMinor = 100800L, postTimeMs = old)
        val hb = h.paymentEvent("hb|1|0|hb", amount = 0L, occurredAtMs = old, category = "heartbeat")
        h.delivery(hb, DeliveryState.ACKED, terminalAtMs = old)
        h.captureRow(CaptureOutcome.CAPTURED, eventId = hb.eventId, postTimeMs = old)

        val result = h.processor.runRetentionCleanup()

        assertEquals(1, result.heartbeatDeleted)
        assertNotNull("payment event must survive", h.eventDao.getById(pay.eventId))
        assertNull("heartbeat event must expire", h.eventDao.getById(hb.eventId))
        val items = h.history.listPage()
        assertTrue("payment still listed", items.any { it.hasEvent && it.amountMinor == 100800L })
        // The heartbeat capture row survives as unlinked (FK SET NULL),
        // expiring on the 90-day rule — not with the event.
        val hbRow = h.rows().first { it.postTimeMs == old && it.amountMinor == null }
        assertNull(hbRow.eventId)
    }

    // --- LH-T02: filters show matched and unmatched ---

    @Test
    fun lhT02_filtersByAmountAndMatchStatus() = runBlocking {
        val matched = h.paymentEvent("com.gojek.gopay|1|170286|m")
        h.delivery(matched, DeliveryState.ACKED, lastMatchStatus = "matched", lastApplyStatus = "ok")
        h.captureRow(CaptureOutcome.CAPTURED, eventId = matched.eventId, amountMinor = 170286L, postTimeMs = POST_TIME)
        val logged = h.paymentEvent("com.gojek.gopay|2|170286|l")
        h.delivery(logged, DeliveryState.ACKED, lastMatchStatus = "logged", lastApplyStatus = null)
        h.captureRow(CaptureOutcome.CAPTURED, eventId = logged.eventId, amountMinor = 170286L, postTimeMs = POST_TIME + 1)
        val other = h.paymentEvent("com.gojek.gopay|3|50000|o")
        h.delivery(other, DeliveryState.ACKED, lastMatchStatus = "matched", lastApplyStatus = "ok")
        h.captureRow(CaptureOutcome.CAPTURED, eventId = other.eventId, amountMinor = 50000L, postTimeMs = POST_TIME + 2)

        assertEquals(2, h.history.listPage(HistoryFilter(minAmount = 170286L, maxAmount = 170286L)).size)
        val matchedOnly = h.history.listPage(HistoryFilter(matchStatus = "matched"))
        assertEquals(2, matchedOnly.size)
        assertTrue(matchedOnly.all { it.lastMatchStatus == "matched" })
        val loggedOnly = h.history.listPage(HistoryFilter(matchStatus = "logged"))
        assertEquals(1, loggedOnly.size)
        assertEquals("logged", loggedOnly[0].lastMatchStatus)
        assertEquals(3, h.history.listPage().size)
        assertEquals(3, h.history.count())
    }

    // --- LH-T03: filtered-out notifications are traceable ---

    @Test
    fun lhT03_noKeywordAndNoAmountRows_noEvents() = runBlocking {
        h.captureAndWait(h.snapshot(title = "GoPay", text = "Promo diskon 50% hari ini"))
        h.captureAndWait(
            h.snapshot(
                title = "GoPay",
                text = "Pembayaran kamu sedang diproses",
                postTime = POST_TIME + 60_000L,
            ),
        )

        val rows = h.rows()
        assertEquals(2, rows.size)
        val noKeyword = rows.first { it.outcome == CaptureOutcome.NO_KEYWORD }
        assertEquals(PKG, noKeyword.sourcePackage)
        assertEquals(POST_TIME, noKeyword.postTimeMs)
        assertNull(noKeyword.amountMinor)
        assertNull(noKeyword.eventId)
        val noAmount = rows.first { it.outcome == CaptureOutcome.NO_AMOUNT }
        assertEquals(PKG, noAmount.sourcePackage)
        assertEquals(POST_TIME + 60_000L, noAmount.postTimeMs)
        assertNull(noAmount.amountMinor)
        assertEquals("no event row for either reject", 0, h.eventDao.count())
        // Both are visible in history.
        assertEquals(2, h.history.listPage().size)
    }

    // --- LH-T04: duplicate vs collision ---

    @Test
    fun lhT04_duplicateAndCollision() = runBlocking {
        val first = h.snapshot(text = "Pembayaran Rp100.800 berhasil " + "A".repeat(20))
        h.captureAndWait(first)
        h.captureAndWait(first)
        // Same dedupe (identical first 40 chars of combined), different
        // content after char 40 → same key, different hash.
        val colliding = h.snapshot(
            text = "Pembayaran Rp100.800 berhasil " + "A".repeat(10) + "B".repeat(10),
        )
        h.captureAndWait(colliding)

        assertEquals("one event only", 1, h.eventDao.count())
        assertEquals("one legacy item only", 1, h.legacyQueue.size)
        val rows = h.rows()
        assertEquals(3, rows.size)
        assertEquals(1, rows.count { it.outcome == CaptureOutcome.CAPTURED })
        assertEquals(1, rows.count { it.outcome == CaptureOutcome.DUPLICATE })
        assertEquals(1, rows.count { it.outcome == CaptureOutcome.COLLISION })
        assertEquals(1, h.coordinator.counters().identityCollision)
        val kept = h.eventDao.getByDedupeKey(
            CaptureCoordinator.legacyDedupeKey(PKG, POST_TIME, 100800L, first.combined),
        )
        assertNotNull(kept)
        assertEquals(
            CaptureCoordinator.sha256Hex(first.combined),
            kept!!.contentSha256,
        )
        // The detail view derives the collision flag from the log.
        val capturedRow = rows.first { it.outcome == CaptureOutcome.CAPTURED }
        val detail = h.history.getDetail(capturedRow.captureId)
        assertNotNull(detail)
        assertTrue(detail!!.identityCollision)
    }

    // --- LH-T05: pure-auth leaves no text ---

    @Test
    fun lhT05_pureAuthMetadataOnly_noOtpAnywhere() = runBlocking {
        h.captureAndWait(
            h.snapshot(
                title = "GoPay",
                text = "Kode OTP 482913 untuk verifikasi pembayaran kamu",
            ),
        )

        val rows = h.rows()
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals(CaptureOutcome.PURE_AUTH, row.outcome)
        assertEquals(PKG, row.sourcePackage)
        assertEquals(POST_TIME, row.postTimeMs)
        assertNull("no snippet for pure-auth", row.titleSnippet)
        assertNull("no amount for pure-auth", row.amountMinor)
        assertNull("no content hash for pure-auth", row.contentSha256)
        assertNull(row.eventId)
        assertEquals(0, h.eventDao.count())
        assertEquals(1, h.coordinator.counters().pureAuth)
        // A DB scan finds no OTP/PIN span in any stored row.
        val storedText = buildString {
            h.store.captureLog.values.forEach { append(it.toString()) }
            h.store.events.values.forEach { append(it.toString()) }
            h.store.collector.values.forEach { append(it.toString()) }
            h.store.staging.values.forEach { append(it.toString()) }
            h.logLines.forEach { append(it) }
        }
        assertFalse("OTP must never be stored or logged", storedText.contains("482913"))
    }

    // --- LH-T06: unlinked capture rows expire after 90 days ---

    @Test
    fun lhT06_unlinkedRowsExpire_linkedRowsSurvive() = runBlocking {
        val old = h.now - 91 * DAY_MS
        h.captureRow(CaptureOutcome.NO_KEYWORD, capturedAtMs = old, postTimeMs = old)
        val pay = h.paymentEvent("com.gojek.gopay|1|100800|old", occurredAtMs = old)
        h.delivery(pay, DeliveryState.ACKED, terminalAtMs = old)
        val linked = h.captureRow(
            CaptureOutcome.CAPTURED, eventId = pay.eventId,
            amountMinor = 100800L, capturedAtMs = old, postTimeMs = old,
        )

        val result = h.processor.runRetentionCleanup()

        assertEquals(1, result.captureLogDeleted)
        assertEquals(1, h.captureLogDao.count())
        assertNotNull(h.captureLogDao.getById(linked.captureId))
        assertNotNull("payment event survives with its row", h.eventDao.getById(pay.eventId))
    }

    // --- LH-T07: skip/ambiguous outcomes are visible ---

    @Test
    fun lhT07_skipAndAmbiguousVisible_auditSetDerivable() = runBlocking {
        val skipped = h.paymentEvent("com.gojek.gopay|1|100800|s")
        h.delivery(skipped, DeliveryState.ACKED, lastMatchStatus = "matched", lastApplyStatus = "skip")
        h.captureRow(CaptureOutcome.CAPTURED, eventId = skipped.eventId, amountMinor = 100800L, postTimeMs = POST_TIME)
        val ambiguous = h.paymentEvent("com.gojek.gopay|2|100800|a")
        h.delivery(ambiguous, DeliveryState.ACKED, lastMatchStatus = "ambiguous", lastApplyStatus = null)
        h.captureRow(CaptureOutcome.CAPTURED, eventId = ambiguous.eventId, amountMinor = 100800L, postTimeMs = POST_TIME + 1)
        val settled = h.paymentEvent("com.gojek.gopay|3|100800|ok")
        h.delivery(settled, DeliveryState.ACKED, lastMatchStatus = "matched", lastApplyStatus = "ok")
        h.captureRow(CaptureOutcome.CAPTURED, eventId = settled.eventId, amountMinor = 100800L, postTimeMs = POST_TIME + 2)

        val items = h.history.listPage()
        assertEquals(3, items.size)
        assertTrue(items.any { it.lastMatchStatus == "matched" && it.lastApplyStatus == "skip" })
        assertTrue(items.any { it.lastMatchStatus == "ambiguous" })
        val audit = h.history.auditSet()
        assertEquals(2, audit.size)
        assertTrue(audit.any { it.lastApplyStatus == "skip" })
        assertTrue(audit.any { it.lastMatchStatus == "ambiguous" })
        // The settled delivery is not in the audit set.
        assertFalse(audit.any { it.lastApplyStatus == "ok" })
    }

    // --- LH-T08: no export, no identifier leak (F55 extension) ---

    @Test
    fun lhT08_noExportInHistory_noIdentifierLeak() {
        val root = findSourceRoot()
            ?: throw AssertionError("source root not found — fail-closed: cannot prove LH-T08")
        val exportApis = listOf(
            "FileOutputStream", "openOutputStream", "ACTION_CREATE_DOCUMENT",
            "ACTION_SEND", "createDocument", "OutputStreamWriter",
            "FileWriter", "PrintWriter", ".csv",
        )
        val historyFiles = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter {
                it.path.contains("/history/") || it.name == "HistoryActivity.kt" ||
                    it.name == "CaptureLogDao.kt" || it.name == "CaptureLogEntity.kt"
            }
            .toList()
        assertTrue("history sources must exist for LH-T08", historyFiles.isNotEmpty())
        val exportViolations = mutableListOf<String>()
        for (file in historyFiles) {
            file.readLines().forEachIndexed { index, line ->
                if (isComment(line)) return@forEachIndexed
                for (api in exportApis) {
                    if (line.contains(api)) {
                        exportViolations.add("${file.relativeTo(root)}:${index + 1}: ${line.trim()}")
                    }
                }
            }
        }
        assertTrue(
            "export API in history/capture-log code (LH-T08/F55):\n${exportViolations.joinToString("\n")}",
            exportViolations.isEmpty(),
        )

        // The display layer must not reference internal identifiers
        // at all — the DTOs carry none, so the Activity cannot
        // display them (LH-03, C22(q'')).
        val activity = File(root, "com/netter/paylist/HistoryActivity.kt")
        assertTrue("HistoryActivity.kt must exist", activity.isFile)
        val identifiers = listOf(
            "dedupeKey", "contentSha256", "eventId", "legacyQkey",
            "dedupe_key", "content_sha256", "legacy_qkey", "event_id",
        )
        val idViolations = mutableListOf<String>()
        activity.readLines().forEachIndexed { index, line ->
            if (isComment(line)) return@forEachIndexed
            for (id in identifiers) {
                if (line.contains(id)) {
                    idViolations.add("${index + 1}: ${line.trim()}")
                }
            }
        }
        assertTrue(
            "internal identifier in HistoryActivity (LH-T08):\n${idViolations.joinToString("\n")}",
            idViolations.isEmpty(),
        )

        // Only allowlisted files may touch the capture log: the
        // coordinator (writes), the repository (reads), the
        // retention cleanup (deletes), the schema, and the tests.
        // In particular no export path reads it (LH-07(c)).
        // Scanned across main + unit-test + instrumented-test
        // sources (the module dir is the main root's grandparent).
        val allowed = setOf(
            "CaptureCoordinator.kt", "CaptureLogDao.kt", "CaptureLogEntity.kt",
            "HistoryRepository.kt", "PaylistDatabase.kt", "Migrations.kt",
            "DeliveryProcessor.kt", "SyncWorkerFactory.kt", "PaylistApplication.kt",
            "TestDoubles.kt", "HistoryTest.kt", "CaptureLogDaoTest.kt",
            "MigrationTest.kt", "OutboxMigrationTest.kt",
        )
        val moduleDir = root.absoluteFile.parentFile?.parentFile?.parentFile
            ?: throw AssertionError("cannot derive the module dir for LH-T08")
        val touchRoots = listOf(
            File(moduleDir, "src/main/java"),
            File(moduleDir, "src/test/java"),
            File(moduleDir, "src/androidTest/java"),
        ).filter { it.isDirectory }
        assertTrue("no source roots found for LH-T08", touchRoots.isNotEmpty())
        val touchViolations = mutableListOf<String>()
        for (scanRoot in touchRoots) {
            scanRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    if (file.name in allowed) return@forEach
                    file.readLines().forEachIndexed { index, line ->
                        if (isComment(line)) return@forEachIndexed
                        if (line.contains("CaptureLogDao") || line.contains("captureLogDao") ||
                            line.contains("capture_log")
                        ) {
                            touchViolations.add("${file.relativeTo(moduleDir)}:${index + 1}: ${line.trim()}")
                        }
                    }
                }
        }
        assertTrue(
            "capture_log touched outside the allowlist (LH-T08/F55):\n${touchViolations.joinToString("\n")}",
            touchViolations.isEmpty(),
        )
    }

    private fun isComment(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("*") || trimmed.startsWith("//") ||
            trimmed.startsWith("/*")
    }

    /**
     * Locates `src/main/java` for the static check (mirrors
     * CutoverTest F27). Returns null when not found — the caller
     * fails closed.
     */
    private fun findSourceRoot(): File? {
        listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }?.let { return it }
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val appSrc = File(dir, "app/src/main/java")
            if (appSrc.isDirectory) return appSrc
            val src = File(dir, "src/main/java")
            if (src.isDirectory) return src
            dir = dir.parentFile
        }
        return null
    }
}
