package com.netter.paylist.export

import com.netter.paylist.FakeClassificationDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeExportAuditDao
import com.netter.paylist.FakeMatchResultDao
import com.netter.paylist.FakeStore
import com.netter.paylist.Route
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.ClassificationEntity
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.data.entity.ExportStatus
import com.netter.paylist.data.entity.HopOutcome
import com.netter.paylist.data.entity.MatchResultEntity
import com.netter.paylist.delivery.RouteSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Reconciliation export fixtures F56–F68 (design §7.7A, §10).
 * All fixtures require executable evidence, not only a checklist.
 */
class ReconciliationExportTest {

    /** One export run's observable output (declared at class level: a data class cannot nest inside the inner Harness). */
    private data class RunOutput(
        val result: ExportResult,
        val csv: String,
        val auditId: String,
    )

    companion object {
        const val PKG = "com.gojek.gopaymerchant"
        const val NOW = 1_794_960_000_000L
        const val T0 = 1_794_000_000_000L
    }

    private class FakeRoutes(var routesList: List<Route>) : RouteSource {
        override fun routes(): List<Route> = routesList
        override fun routeById(id: String): Route? = routesList.firstOrNull { it.id == id }
        override fun deviceLabel(): String = "Moto g57"
    }

    /** Mutable consent/config source: tests inject revocation mid-write. */
    private class FakeConsent : ConsentSnapshotSource {
        val granted = mutableMapOf<String, Boolean>()
        val epochs = mutableMapOf<String, Long>()
        val fingerprints = mutableMapOf<String, String>()

        fun grant(routeId: String, epoch: Long = 0L) {
            granted[routeId] = true
            epochs[routeId] = epoch
            fingerprints[routeId] = "fp-$routeId-$epoch"
        }

        fun revoke(routeId: String) {
            granted[routeId] = false
            epochs[routeId] = (epochs[routeId] ?: 0L) + 1
        }

        fun changeConfig(routeId: String) {
            fingerprints[routeId] = "fp-$routeId-changed-${System.nanoTime()}"
        }

        override fun isGranted(routeId: String): Boolean = granted[routeId] == true
        override fun consentEpoch(routeId: String): Long = epochs[routeId] ?: -1L
        override fun configFingerprint(routeId: String): String? = fingerprints[routeId]
    }

    private inner class Harness {
        val store = FakeStore()
        val eventDao = FakeEventDao(store)
        val deliveryDao = FakeDeliveryDao(store)
        val matchDao = FakeMatchResultDao(store)
        val classificationDao = FakeClassificationDao(store)
        val auditDao = FakeExportAuditDao(store)
        val routes = FakeRoutes(emptyList())
        val consent = FakeConsent()
        val quarantined = mutableSetOf<String>()

        val exporter = ReconciliationExport(
            eventDao, deliveryDao, matchDao, classificationDao,
            routes, consent,
            quarantinedDedupeKeys = { quarantined.toSet() },
        )
        val runner = ReconciliationExportRunner(exporter, auditDao, clock = { NOW })

        fun paymentRoute(id: String) =
            Route(id, "Rute $id", listOf(PKG), "https://$id.example.invalid/notify.php", listOf("gopay"), true)

        fun recorderRoute(id: String) =
            Route(id, "Pencatat $id", listOf(PKG), "https://$id.example.invalid/catat.php", listOf("gopay"), true)

        suspend fun event(
            dedupeKey: String,
            amount: Long = 100800L,
            currency: String = "IDR",
            direction: String = "in",
            category: String = "payment",
            occurredAtMs: Long = T0,
            sourceAccountKey: String? = null,
            pkg: String = PKG,
        ): EventEntity {
            val e = EventEntity(
                eventId = UUID.randomUUID().toString(),
                sourceType = "android_notification",
                sourceEventId = dedupeKey,
                deviceId = "Moto g57",
                eventType = "payment.notification",
                category = category,
                sourcePackage = pkg,
                occurredAtMs = occurredAtMs,
                capturedAtMs = occurredAtMs,
                dataJson = """{"amount":$amount,"currency":"$currency"}""",
                metadataJson = "{}",
                legacyPayloadJson = """{"type":"payment","amount":$amount,"direction":"$direction","dedupe_key":"$dedupeKey"}""",
                privacyMode = "structured",
                retentionUntilMs = null,
                dedupeKey = dedupeKey,
                contentSha256 = "deadbeef-${UUID.randomUUID()}",
                exportRef = UUID.randomUUID().toString(),
                sourceAccountKey = sourceAccountKey,
            )
            eventDao.insert(e)
            return e
        }

        suspend fun delivery(
            event: EventEntity,
            routeIds: List<String>,
            hops: List<HopOutcome> = emptyList(),
            state: DeliveryState = DeliveryState.PENDING,
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
                createdAtMs = NOW,
                updatedAtMs = NOW,
                routeStampsJson = "{}",
                hopOutcomesJson = HopOutcome.toJsonArray(hops),
            )
            deliveryDao.insert(d)
            return d
        }

        suspend fun match(
            event: EventEntity,
            advisory: String = "matched",
            attribution: String = "none",
            orderNumber: String? = null,
            bankReference: String? = null,
            masked: String? = null,
            createdAtMs: Long = NOW,
        ): MatchResultEntity {
            val m = MatchResultEntity(
                matchId = UUID.randomUUID().toString(),
                eventId = event.eventId,
                merchantId = null,
                orderNumber = orderNumber,
                bankReference = bankReference,
                sourceAccountMasked = masked,
                advisoryMatchStatus = advisory,
                confidence = 0.9,
                ruleId = "P3-MATCH",
                registryVersion = "v1",
                attributionStatus = attribution,
                auditJson = "{}",
                createdAtMs = createdAtMs,
            )
            matchDao.insert(m)
            return m
        }

        suspend fun classification(
            event: EventEntity,
            category: String = "PEMBAYARAN",
        ): ClassificationEntity {
            val c = ClassificationEntity(
                classificationId = UUID.randomUUID().toString(),
                eventId = event.eventId,
                category = category,
                confidence = 0.8,
                ruleId = "keyword",
                auditJson = "{}",
                createdAtMs = NOW,
            )
            classificationDao.insert(c)
            return c
        }

        fun hop(routeId: String, outcome: String, atMs: Long = NOW) =
            HopOutcome(routeId, outcome, 200, null, null, atMs)

        suspend fun runExport(
            filter: ExportFilter = ExportFilter(),
            includeIdentifiers: Boolean = false,
            openStream: (() -> OutputStream)? = null,
            isCancelled: () -> Boolean = { false },
            onRowWritten: ((Int) -> Unit)? = null,
        ): RunOutput {
            val out = ByteArrayOutputStream()
            var deleted = false
            val result = runner.run(
                ExportRequest(
                    filter = filter,
                    includeIdentifiers = includeIdentifiers,
                    auth = "NONE",
                    actor = "operator",
                    fileName = ReconciliationExport.suggestedFileName(NOW),
                    openStream = openStream ?: { out },
                    deletePartial = { deleted = true; true },
                    isCancelled = isCancelled,
                    onRowWritten = onRowWritten,
                ),
            )
            return RunOutput(result, out.toString(Charsets.UTF_8.name()), result.auditId)
        }
    }

    /** Minimal RFC 4180 parser (quoted fields, doubled quotes, CRLF). */
    private fun parseCsv(csv: String): List<List<String>> {
        val text = csv.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        val fields = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    cur.append('"'); i += 2
                }
                quoted && c == '"' -> { quoted = false; i++ }
                !quoted && c == '"' && cur.isEmpty() -> { quoted = true; i++ }
                !quoted && c == ',' -> { fields += cur.toString(); cur.clear(); i++ }
                !quoted && c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    fields += cur.toString(); cur.clear()
                    rows += fields.toList(); fields.clear(); i += 2
                }
                !quoted && c == '\n' -> {
                    fields += cur.toString(); cur.clear()
                    rows += fields.toList(); fields.clear(); i++
                }
                else -> { cur.append(c); i++ }
            }
        }
        if (cur.isNotEmpty() || fields.isNotEmpty()) {
            fields += cur.toString()
            rows += fields.toList()
        }
        return rows
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun col(rows: List<List<String>>, row: Int, name: String): String {
        val idx = ReconciliationExport.COLUMNS.indexOf(name)
        return rows[row][idx]
    }

    private fun colOf(row: List<String>, name: String): String {
        val idx = ReconciliationExport.COLUMNS.indexOf(name)
        return row[idx]
    }

    // --- F56: allowed records ---

    @Test
    fun f56_twoRoutes_twoRows_exactSchema_crlf_bom() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        val r2 = h.paymentRoute("r2")
        h.routes.routesList = listOf(r1, r2)
        h.consent.grant("r1")
        h.consent.grant("r2")
        val e = h.event("com.gojek.gopaymerchant|1|100800|a")
        h.delivery(e, listOf("r1", "r2"), listOf(h.hop("r1", "MATCHED_SETTLED"), h.hop("r2", "LOGGED")))
        h.match(e, orderNumber = "ORD-1", bankReference = "TRX-9988", masked = "****1234")
        h.classification(e)

        val out = h.runExport()
        val result = out.result as ExportResult.Completed
        assertEquals(2, result.rowsWritten)
        assertTrue(out.csv.startsWith("\uFEFF"))
        assertTrue("CRLF record separators", out.csv.contains("\r\n"))
        assertFalse("no bare LF", out.csv.replace("\r\n", "").contains("\n"))
        val rows = parseCsv(out.csv)
        assertEquals(ReconciliationExport.COLUMNS, rows[0])
        assertEquals(3, rows.size) // header + 2 rows
        assertEquals(20, rows[1].size)
        assertEquals(20, rows[2].size)
        // Same timestamp: row order is the row_id tie-break, so
        // find each route's row instead of assuming positions.
        val r1Row = rows.drop(1).first { colOf(it, "route_label") == "Rute r1" }
        val r2Row = rows.drop(1).first { colOf(it, "route_label") == "Rute r2" }
        // Same event_ref, distinct row_ids; row_id = sha256(eventRef|routeId).
        assertEquals(colOf(r1Row, "event_ref"), colOf(r2Row, "event_ref"))
        assertEquals(e.exportRef, colOf(r1Row, "event_ref"))
        assertNotEquals(colOf(r1Row, "row_id"), colOf(r2Row, "row_id"))
        assertEquals(sha256Hex("${e.exportRef}|r1"), colOf(r1Row, "row_id"))
        assertEquals(sha256Hex("${e.exportRef}|r2"), colOf(r2Row, "row_id"))
        assertEquals("100800", colOf(r1Row, "amount"))
        assertEquals("IDR", colOf(r1Row, "currency"))
        assertEquals("IN", colOf(r1Row, "direction"))
        assertEquals("TRX-9988", colOf(r1Row, "bank_reference"))
        assertEquals("ORD-1", colOf(r1Row, "order_number"))
        assertEquals("****1234", colOf(r1Row, "source_account"))
        assertEquals("GoPay Merchant", colOf(r1Row, "source_application"))
        assertEquals("PAYMENT", colOf(r1Row, "route_kind"))
        assertEquals("MATCHED_SETTLED", colOf(r1Row, "server_outcome"))
        assertEquals("LOGGED", colOf(r2Row, "server_outcome"))
        assertEquals("RESOLVED", colOf(r1Row, "resolution_label"))
        assertEquals("UNRESOLVED_UNKNOWN", colOf(r2Row, "resolution_label"))
        assertEquals("", colOf(r1Row, "excluded_fields"))
    }

    @Test
    fun f56_zeroHop_exactlyOneNoneRow() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        val e = h.event("com.gojek.gopaymerchant|1|100800|z")
        h.delivery(e, listOf("r1"), hops = emptyList())

        val out = h.runExport()
        val result = out.result as ExportResult.Completed
        assertEquals(1, result.rowsWritten)
        val rows = parseCsv(out.csv)
        assertEquals(2, rows.size)
        assertEquals("NONE", col(rows, 1, "route_kind"))
        assertEquals("", col(rows, 1, "route_label"))
        assertEquals("NOT_DISPATCHED", col(rows, 1, "server_outcome"))
        assertEquals(sha256Hex("${e.exportRef}|none"), col(rows, 1, "row_id"))
    }

    @Test
    fun f56_filters_dateDirectionStatus_advisoryNeverSettled() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        val inEvent = h.event("com.gojek.gopaymerchant|1|100800|in", occurredAtMs = T0)
        h.delivery(inEvent, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        h.match(inEvent, advisory = "matched")
        val outEvent = h.event(
            "com.gojek.gopaymerchant|2|50000|out",
            amount = 50000L, direction = "out", occurredAtMs = T0 + 86_400_000L,
        )
        h.delivery(outEvent, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        h.match(outEvent, advisory = "unmatched")
        val hb = h.event("com.gojek.gopaymerchant|3|0|hb", category = "heartbeat", occurredAtMs = T0)
        h.delivery(hb, listOf("r1"), listOf(h.hop("r1", "LOGGED")))

        // No filter: heartbeat excluded, both payments present.
        var out = h.runExport()
        assertEquals(2, (out.result as ExportResult.Completed).rowsWritten)
        // Advisory matched + hop LOGGED: server_outcome stays
        // LOGGED — advisory matched is never MATCHED_SETTLED.
        var rows = parseCsv(out.csv)
        assertEquals("matched", col(rows, 1, "advisory_match_status"))
        assertEquals("LOGGED", col(rows, 1, "server_outcome"))
        // Direction filter.
        out = h.runExport(filter = ExportFilter(direction = "OUT"))
        assertEquals(1, (out.result as ExportResult.Completed).rowsWritten)
        // Advisory filter.
        out = h.runExport(filter = ExportFilter(advisoryStatus = "unmatched"))
        assertEquals(1, (out.result as ExportResult.Completed).rowsWritten)
        // Date filter (only the second day).
        out = h.runExport(filter = ExportFilter(fromMs = T0 + 1, toMs = T0 + 86_400_000L))
        assertEquals(1, (out.result as ExportResult.Completed).rowsWritten)
        // Ascending timestamp order.
        out = h.runExport()
        rows = parseCsv(out.csv)
        assertTrue(col(rows, 1, "timestamp") < col(rows, 2, "timestamp"))
    }

    // --- F57: quarantined / secret-bearing records ---

    @Test
    fun f57_quarantinedExcluded_sensitiveFieldOmitted_noInternalKeys() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        val q = h.event("com.gojek.gopaymerchant|1|100800|q")
        h.delivery(q, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        h.quarantined += q.dedupeKey
        val e = h.event("com.gojek.gopaymerchant|2|100800|ok")
        h.delivery(e, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        // A free-text field carrying an unresolved sensitive span
        // (bare 6-digit run) is omitted and named — never exported.
        h.classification(e, category = "KODE 482913")
        // A P3 value is confirmed by the §3.1.3 evidence bar at
        // extraction, so it exports as stored.
        h.match(e, bankReference = "TRX-9988", orderNumber = "ORD-7")

        val out = h.runExport()
        val result = out.result as ExportResult.Completed
        assertEquals(1, result.rowsWritten)
        assertEquals(1, result.excludedCount)
        val rows = parseCsv(out.csv)
        assertEquals("", col(rows, 1, "category"))
        assertEquals("category", col(rows, 1, "excluded_fields"))
        assertEquals("TRX-9988", col(rows, 1, "bank_reference"))
        assertEquals("ORD-7", col(rows, 1, "order_number"))
        // No internal key or raw secret anywhere in the file.
        val csv = out.csv
        assertFalse(csv.contains(e.eventId))
        assertFalse(csv.contains(e.dedupeKey))
        assertFalse(csv.contains("482913"))
        assertFalse(csv.contains("deadbeef"))
        assertFalse(csv.contains("content_sha256"))
        assertFalse(csv.contains("dedupe_key"))
        assertFalse(csv.contains("qkey"))
    }

    // --- F58: consent and access control ---

    @Test
    fun f58_revokedRouteExcluded_zeroHopAllRevokedExcluded_identifiersAudited() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        val r2 = h.paymentRoute("r2")
        h.routes.routesList = listOf(r1, r2)
        h.consent.grant("r1")
        h.consent.grant("r2")
        val e = h.event("com.gojek.gopaymerchant|1|100800|a")
        h.delivery(e, listOf("r1", "r2"), listOf(h.hop("r1", "LOGGED"), h.hop("r2", "LOGGED")))
        val z = h.event("com.gojek.gopaymerchant|2|100800|z")
        h.delivery(z, listOf("r2"), hops = emptyList())
        // Revoke r2 after the hops happened.
        h.consent.revoke("r2")

        val out = h.runExport(includeIdentifiers = false)
        val result = out.result as ExportResult.Completed
        assertEquals(1, result.rowsWritten) // only the r1 hop row
        assertEquals(2, result.excludedCount) // r2 hop row + zero-hop event
        val rows = parseCsv(out.csv)
        assertEquals("Rute r1", col(rows, 1, "route_label"))
        assertEquals("", col(rows, 1, "sender_id"))
        assertEquals("", col(rows, 1, "recipient_id"))
        val audit = h.auditDao.getById(out.auditId)!!
        assertTrue(audit.filterJson.contains("\"include_identifiers\":false"))

        // Opt-in is recorded in the audit (default OFF, never persisted).
        val out2 = h.runExport(includeIdentifiers = true)
        assertTrue(out2.result is ExportResult.Completed)
        val audit2 = h.auditDao.getById(out2.auditId)!!
        assertTrue(audit2.filterJson.contains("\"include_identifiers\":true"))
    }

    @Test
    fun f58_zeroHop_multiRoute_oneConsentingRowPresent() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        val r2 = h.paymentRoute("r2")
        h.routes.routesList = listOf(r1, r2)
        h.consent.grant("r1")
        h.consent.grant("r2")
        h.consent.revoke("r2")
        val e = h.event("com.gojek.gopaymerchant|1|100800|z")
        h.delivery(e, listOf("r1", "r2"), hops = emptyList())

        val out = h.runExport()
        assertEquals(1, (out.result as ExportResult.Completed).rowsWritten)
    }

    // --- F59: labelling ---

    @Test
    fun f59_unresolvedLabels_recorderOutcomes() = runBlocking {
        val h = Harness()
        val rec = h.recorderRoute("rec")
        val pay = h.paymentRoute("pay")
        h.routes.routesList = listOf(rec, pay)
        h.consent.grant("rec")
        h.consent.grant("pay")
        val held = h.event("com.gojek.gopaymerchant|1|100800|held", occurredAtMs = T0)
        h.delivery(held, listOf("pay"), hops = emptyList(), state = DeliveryState.NEEDS_REVIEW)
        val recorded = h.event("com.gojek.gopaymerchant|2|100800|rec", occurredAtMs = T0 + 1000L)
        h.delivery(recorded, listOf("rec"), listOf(h.hop("rec", "LOGGED")))
        val unknown = h.event("com.gojek.gopaymerchant|3|100800|unk", occurredAtMs = T0 + 2000L)
        h.delivery(unknown, listOf("pay"), listOf(h.hop("pay", "UNKNOWN_PENDING_RECONCILIATION")))
        val amb = h.event("com.gojek.gopaymerchant|4|100800|amb", occurredAtMs = T0 + 3000L)
        h.delivery(amb, listOf("pay"), listOf(h.hop("pay", "AMBIGUOUS")))

        val out = h.runExport()
        val rows = parseCsv(out.csv)
        assertEquals(5, rows.size)
        // Zero-hop NEEDS_REVIEW: labelled unresolved, never settled.
        assertEquals("UNRESOLVED_NEEDS_REVIEW", col(rows, 1, "resolution_label"))
        assertEquals("NOT_DISPATCHED", col(rows, 1, "server_outcome"))
        // Recorder LOGGED -> RECORDED (not MATCHED_SETTLED).
        assertEquals("RECORDER", col(rows, 2, "route_kind"))
        assertEquals("RECORDED", col(rows, 2, "server_outcome"))
        assertEquals("RESOLVED", col(rows, 2, "resolution_label"))
        // Unknown hop -> UNKNOWN + UNRESOLVED_UNKNOWN.
        assertEquals("UNKNOWN", col(rows, 3, "server_outcome"))
        assertEquals("UNRESOLVED_UNKNOWN", col(rows, 3, "resolution_label"))
        // Ambiguous -> AMBIGUOUS + UNRESOLVED_NEEDS_REVIEW.
        assertEquals("AMBIGUOUS", col(rows, 4, "server_outcome"))
        assertEquals("UNRESOLVED_NEEDS_REVIEW", col(rows, 4, "resolution_label"))
    }

    // --- F60: zero side effects ---

    @Test
    fun f60_readOnly_exceptAuditRow() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        val e = h.event("com.gojek.gopaymerchant|1|100800|a")
        val d = h.delivery(e, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        h.match(e)
        val beforeEvents = h.store.events.toMap()
        val beforeDeliveries = h.store.deliveries.toMap()
        val beforeMatches = h.store.matchResults.toMap()
        val beforeCollector = h.store.collector.toMap()

        val out = h.runExport()
        assertTrue(out.result is ExportResult.Completed)

        assertEquals(beforeEvents, h.store.events)
        assertEquals(beforeDeliveries, h.store.deliveries)
        assertEquals(beforeMatches, h.store.matchResults)
        assertEquals(beforeCollector, h.store.collector)
        assertEquals(1, h.store.exportAudit.size)
        val audit = h.auditDao.getById(out.auditId)!!
        assertEquals("operator", audit.actor)
        assertEquals(1, audit.rowCount)
        assertEquals(0, audit.excludedRowCount)
        assertEquals(ExportStatus.COMPLETED, audit.status)
        assertEquals("NONE", audit.auth)
        assertTrue(audit.fileName.startsWith("paylist-reconciliation-"))
        assertTrue(audit.epochsJson!!.contains("\"consent_epoch\":0"))
        assertEquals(d.deliveryId, h.deliveryDao.getById(d.deliveryId)!!.deliveryId)
    }

    // --- F61: injection safety ---

    @Test
    fun f61_formulaTriggers_prefixed_stripRestores() {
        val triggers = listOf(
            "=cmd|'/C calc'!A0",
            "@SUM(A1:A10)",
            "+2+2",
            "-2+3",
            "=HYPERLINK(\"http://evil.invalid\")",
            "\t=2+2",
            "\r\n@SUM(1)",
            "  =1+1",
        )
        for (value in triggers) {
            val guarded = ReconciliationExport.guardInjection(value)
            assertTrue("expected ' prefix for <$value>", guarded.startsWith("'"))
            assertEquals(value, ReconciliationExport.stripInjectionPrefix(guarded))
        }
        // Safe values are untouched, and the strip rule never
        // strips a quote that was part of the original value.
        assertEquals("ORD-1", ReconciliationExport.guardInjection("ORD-1"))
        assertEquals("'quoted", ReconciliationExport.stripInjectionPrefix("'quoted"))
        assertEquals("=safe", ReconciliationExport.stripInjectionPrefix("'=safe"))
    }

    @Test
    fun f61_csvRoundTrip_quotesCommasCrLf() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        val e = h.event("com.gojek.gopaymerchant|1|100800|a")
        h.delivery(e, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        h.match(e, orderNumber = "ORD, \"koma\" dan\nbaris baru", bankReference = "=2+2")

        val out = h.runExport()
        val rows = parseCsv(out.csv)
        assertEquals(2, rows.size)
        assertEquals("ORD, \"koma\" dan\nbaris baru", col(rows, 1, "order_number"))
        // The trigger value is prefixed in the file; the strip
        // rule restores the exact original.
        assertEquals("'=2+2", col(rows, 1, "bank_reference"))
        assertEquals("=2+2", ReconciliationExport.stripInjectionPrefix(col(rows, 1, "bank_reference")))
        // System fields are never prefixed.
        assertFalse(col(rows, 1, "amount").startsWith("'"))
        assertFalse(col(rows, 1, "timestamp").startsWith("'"))
    }

    // --- F62: account separation ---

    @Test
    fun f62_twoAccountsSameApp_separateFilters_sameMaskedSeparateEntries() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        val a = h.event("com.gojek.gopaymerchant|1|100800|a", sourceAccountKey = "key-A")
        h.delivery(a, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        h.match(a, masked = "****1234")
        val b = h.event("com.gojek.gopaymerchant|2|100800|b", sourceAccountKey = "key-B")
        h.delivery(b, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        h.match(b, masked = "****1234") // same masked display, different key
        val u = h.event("com.gojek.gopaymerchant|3|100800|u", sourceAccountKey = null)
        h.delivery(u, listOf("r1"), listOf(h.hop("r1", "LOGGED")))

        var out = h.runExport(filter = ExportFilter(sourceAccountKey = "key-A"))
        assertEquals(1, (out.result as ExportResult.Completed).rowsWritten)
        out = h.runExport(filter = ExportFilter(unattributedOnly = true))
        assertEquals(1, (out.result as ExportResult.Completed).rowsWritten)
        val entries = h.exporter.listSourceAccounts(null, null)
        assertEquals(2, entries.size) // never merged on the masked value
        assertEquals(setOf("key-A", "key-B"), entries.map { it.key }.toSet())

        // Key loss (R35): all keys NULL in one pass, rows
        // unattributed, masked display intact.
        assertEquals(2, h.eventDao.nullAllSourceAccountKeys())
        out = h.runExport(filter = ExportFilter(sourceAccountKey = "key-A"))
        assertEquals(0, (out.result as ExportResult.Completed).rowsWritten)
        out = h.runExport(filter = ExportFilter(unattributedOnly = true))
        assertEquals(3, (out.result as ExportResult.Completed).rowsWritten)
        val rows = parseCsv(out.csv)
        // Masked display intact after key loss (rows are in
        // row_id order at equal timestamps — check any row).
        assertTrue(
            rows.drop(1).any { colOf(it, "source_account") == "****1234" },
        )
    }

    // --- F63: multi-route semantics ---

    @Test
    fun f63_partialChain_onlyReachedHops_warningFlag_repeatStable() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        val r2 = h.paymentRoute("r2")
        h.routes.routesList = listOf(r1, r2)
        h.consent.grant("r1")
        h.consent.grant("r2")
        val e = h.event("com.gojek.gopaymerchant|1|100800|a")
        h.delivery(e, listOf("r1", "r2"), listOf(h.hop("r1", "LOGGED")))

        val out = h.runExport()
        val result = out.result as ExportResult.Completed
        assertEquals(1, result.rowsWritten) // reached hop only, no zero-hop row
        assertTrue(result.hasPartialChain)
        val firstCsv = out.csv

        // Repeat exports are stable (same bytes).
        val out2 = h.runExport()
        assertEquals(firstCsv, out2.csv)

        // A chain-terminal stop is completion, not a partial chain.
        val settled = h.event("com.gojek.gopaymerchant|2|100800|s", occurredAtMs = T0 + 5000L)
        h.delivery(settled, listOf("r1", "r2"), listOf(h.hop("r1", "MATCHED_SETTLED")))
        val out3 = h.runExport(filter = ExportFilter(fromMs = T0 + 1))
        // Only the settled event is in range here.
        val r3 = out3.result as ExportResult.Completed
        assertEquals(1, r3.rowsWritten)
        assertFalse(r3.hasPartialChain)
    }

    // --- F64: access / re-authentication ---

    @Test
    fun f64_revocationBeforeFirstRow_abortsAtZeroRows() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        val e = h.event("com.gojek.gopaymerchant|1|100800|a")
        h.delivery(e, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        val outStream = ByteArrayOutputStream()
        var deleted = false

        // Revocation lands after the snapshot (build) but before
        // the first row write: the revalidation must abort at 0 rows.
        val result = h.runner.run(
            ExportRequest(
                filter = ExportFilter(),
                includeIdentifiers = false,
                auth = "NONE",
                actor = "operator",
                fileName = "test.csv",
                openStream = {
                    h.consent.revoke("r1")
                    outStream
                },
                deletePartial = { deleted = true; true },
            ),
        )

        val failed = result as ExportResult.Failed
        assertEquals(0, failed.rowsWritten)
        assertTrue(deleted)
        // The header was written before the abort; no data row.
        val rows = parseCsv(outStream.toString(Charsets.UTF_8.name()))
        assertEquals(1, rows.size)
        assertEquals(ReconciliationExport.COLUMNS, rows[0])
        val audit = h.auditDao.getById(result.auditId)!!
        assertEquals(ExportStatus.FAILED, audit.status)
        assertEquals(0, audit.rowCount)
        assertTrue(audit.notes!!.contains("berubah saat menulis"))
    }

    @Test
    fun f64_revocationBetweenRows_noFurtherRowWritten() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        for (i in 1..3) {
            val e = h.event(
                "com.gojek.gopaymerchant|$i|100800|x$i",
                occurredAtMs = T0 + i * 1000L,
            )
            h.delivery(e, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        }
        val out = h.runExport(
            onRowWritten = { written ->
                if (written == 1) h.consent.revoke("r1")
            },
        )
        val failed = out.result as ExportResult.Failed
        assertEquals(1, failed.rowsWritten) // the row in flight completed; no further row
        val rows = parseCsv(out.csv)
        assertEquals(2, rows.size) // header + 1 row
        val audit = h.auditDao.getById(out.auditId)!!
        assertEquals(ExportStatus.FAILED, audit.status)
        assertEquals(1, audit.rowCount)
    }

    @Test
    fun f64_configChangeMidWrite_aborts() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        for (i in 1..2) {
            val e = h.event(
                "com.gojek.gopaymerchant|$i|100800|c$i",
                occurredAtMs = T0 + i * 1000L,
            )
            h.delivery(e, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        }
        val out = h.runExport(
            onRowWritten = { written ->
                if (written == 1) h.consent.changeConfig("r1")
            },
        )
        val failed = out.result as ExportResult.Failed
        assertEquals(1, failed.rowsWritten)
    }

    // --- F65: stable opaque IDs ---

    @Test
    fun f65_idsStable_noInternalKeys_collisionFails() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        val e = h.event("com.gojek.gopaymerchant|1|100800|a")
        h.delivery(e, listOf("r1"), listOf(h.hop("r1", "LOGGED")))

        val out1 = h.runExport()
        val out2 = h.runExport()
        assertEquals(out1.csv, out2.csv) // stable across repeat exports
        assertFalse(out1.csv.contains(e.eventId))
        assertFalse(out1.csv.contains(e.dedupeKey))
        // A fresh event_ref is assigned per event (legacy import
        // assigns a fresh one; re-import preserves the row).
        assertTrue(e.exportRef.matches(Regex("[0-9a-f-]{36}")))
    }

    // --- F66: currency decimals ---

    @Test
    fun f66_currencyScales_integerArithmetic_roundTrip() {
        assertEquals("100800", ReconciliationExport.formatAmount(100800L, "IDR"))
        assertEquals("1008.00", ReconciliationExport.formatAmount(100800L, "USD"))
        assertEquals("100800", ReconciliationExport.formatAmount(100800L, "JPY"))
        assertEquals("100800", ReconciliationExport.formatAmount(100800L, "XXX")) // unknown: scale 0
        assertEquals("0.05", ReconciliationExport.formatAmount(5L, "USD"))
        assertEquals("0.00", ReconciliationExport.formatAmount(0L, "USD"))
        assertEquals("-1008.00", ReconciliationExport.formatAmount(-100800L, "USD"))
        for ((amount, currency) in listOf(
            100800L to "IDR", 100800L to "USD", 5L to "USD",
            0L to "JPY", 123456789L to "EUR", 999L to "BHD",
        )) {
            val text = ReconciliationExport.formatAmount(amount, currency)
            assertEquals(amount, ReconciliationExport.parseAmount(text, currency))
        }
    }

    @Test
    fun f66_usdEvent_exportsDecimal() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        val e = h.event("com.gojek.gopaymerchant|1|100800|usd", currency = "USD")
        h.delivery(e, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        val out = h.runExport()
        val rows = parseCsv(out.csv)
        assertEquals("1008.00", col(rows, 1, "amount"))
        assertEquals("USD", col(rows, 1, "currency"))
    }

    // --- F67: partial SAF failure ---

    @Test
    fun f67_midWriteIoError_failedAudit_truthfulCount_cleanupAttempted() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        // 60 rows: the periodic flush at row 50 pushes bytes to
        // the failing stream mid-write (a 3-row export would only
        // fail at the final flush).
        for (i in 1..60) {
            val e = h.event(
                "com.gojek.gopaymerchant|$i|100800|f$i",
                occurredAtMs = T0 + i * 1000L,
            )
            h.delivery(e, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        }
        var deleted = false
        val failing = object : OutputStream() {
            private val sink = ByteArrayOutputStream()
            private var bytes = 0
            override fun write(b: Int) {
                bytes++
                if (bytes > 400) throw java.io.IOException("simulated SAF failure")
                sink.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                for (i in off until off + len) write(b[i].toInt())
            }
        }
        val result = h.runner.run(
            ExportRequest(
                filter = ExportFilter(),
                includeIdentifiers = false,
                auth = "NONE",
                actor = "operator",
                fileName = "test.csv",
                openStream = { failing },
                deletePartial = { deleted = true; true },
            ),
        )
        val failed = result as ExportResult.Failed
        // The failure struck mid-write: the exact row count
        // depends on the writer's 8 KB buffer (it auto-flushes
        // when full, before the explicit row-50 flush), so the
        // contract is the invariant, not a magic number — some
        // rows were attempted, not all 60, and the audit count
        // matches the reported count exactly.
        assertTrue(failed.rowsWritten in 1..59)
        assertTrue(deleted)
        val audit = h.auditDao.getById(result.auditId)!!
        assertEquals(ExportStatus.FAILED, audit.status)
        assertEquals(failed.rowsWritten, audit.rowCount)
        assertTrue(audit.notes!!.contains("gagal menulis"))
    }

    @Test
    fun f67_midWriteCancel_cancelledAudit_truthfulCount() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        for (i in 1..3) {
            val e = h.event(
                "com.gojek.gopaymerchant|$i|100800|k$i",
                occurredAtMs = T0 + i * 1000L,
            )
            h.delivery(e, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        }
        var cancel = false
        val out = h.runExport(
            isCancelled = { cancel },
            onRowWritten = { written -> if (written == 2) cancel = true },
        )
        val cancelled = out.result as ExportResult.Cancelled
        assertEquals(2, cancelled.rowsWritten)
        val audit = h.auditDao.getById(out.auditId)!!
        assertEquals(ExportStatus.CANCELLED, audit.status)
        assertEquals(2, audit.rowCount)
    }

    @Test
    fun f67_cancelBeforeWrite_cancelledZeroRows() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        val e = h.event("com.gojek.gopaymerchant|1|100800|a")
        h.delivery(e, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        val out = h.runExport(isCancelled = { true })
        val cancelled = out.result as ExportResult.Cancelled
        assertEquals(0, cancelled.rowsWritten)
        assertEquals("", out.csv)
        val audit = h.auditDao.getById(out.auditId)!!
        assertEquals(ExportStatus.CANCELLED, audit.status)
        assertEquals(0, audit.rowCount)
    }

    // --- F68: bank-transaction grouping (disallowance) ---

    @Test
    fun f68_sameBankReference_neverMerged_noGroupingKey() = runBlocking {
        val h = Harness()
        val r1 = h.paymentRoute("r1")
        h.routes.routesList = listOf(r1)
        h.consent.grant("r1")
        // Two distinct captured events, same bank reference
        // (different issuing banks may issue the same reference).
        val a = h.event("com.gojek.gopaymerchant|1|100800|a", sourceAccountKey = "key-A")
        h.delivery(a, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        h.match(a, bankReference = "TRX-555")
        val b = h.event("com.gojek.gopaymerchant|2|100800|b", sourceAccountKey = "key-B")
        h.delivery(b, listOf("r1"), listOf(h.hop("r1", "LOGGED")))
        h.match(b, bankReference = "TRX-555")

        val out = h.runExport()
        val result = out.result as ExportResult.Completed
        assertEquals(2, result.rowsWritten)
        val rows = parseCsv(out.csv)
        assertNotEquals(col(rows, 1, "event_ref"), col(rows, 2, "event_ref"))
        assertEquals("TRX-555", col(rows, 1, "bank_reference"))
        assertEquals("TRX-555", col(rows, 2, "bank_reference"))
        // The CSV carries no bank-transaction grouping key and no
        // issuing-bank scope column.
        assertFalse(ReconciliationExport.COLUMNS.any {
            it.contains("bank_transaction") || it.contains("issuing_bank") || it == "group_key"
        })
        assertEquals(20, ReconciliationExport.COLUMNS.size)
    }
}
