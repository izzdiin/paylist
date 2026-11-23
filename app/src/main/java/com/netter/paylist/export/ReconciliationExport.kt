package com.netter.paylist.export

import com.netter.paylist.Route
import com.netter.paylist.collector.ConsentStore
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.ClassificationDao
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.data.dao.EventDao
import com.netter.paylist.data.dao.MatchResultDao
import com.netter.paylist.data.entity.ClassificationEntity
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.data.entity.HopOutcome
import com.netter.paylist.data.entity.MatchResultEntity
import com.netter.paylist.delivery.RouteSource
import com.netter.paylist.privacy.PrivacySpanScan
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The reconciliation-export filter (§7.7A): date range, source
 * account, direction, advisory matching status. Applied to the
 * Room read (date range + category) and to the frozen row set
 * before anything is written — never by post-filtering a dump.
 */
data class ExportFilter(
    val fromMs: Long? = null,
    val toMs: Long? = null,
    /** Verified source-account key, or null for all accounts. */
    val sourceAccountKey: String? = null,
    /** When true, only events with no verified source account. */
    val unattributedOnly: Boolean = false,
    /** "IN" / "OUT" / "UNKNOWN", or null for all directions. */
    val direction: String? = null,
    /** Advisory match status, or null for all. */
    val advisoryStatus: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (fromMs != null) put("from_ms", fromMs)
        if (toMs != null) put("to_ms", toMs)
        if (sourceAccountKey != null) put("source_account_key", sourceAccountKey)
        if (unattributedOnly) put("unattributed_only", true)
        if (direction != null) put("direction", direction)
        if (advisoryStatus != null) put("advisory_status", advisoryStatus)
    }

    fun describe(): String {
        val parts = mutableListOf<String>()
        if (fromMs != null || toMs != null) {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val from = fromMs?.let { fmt.format(Date(it)) } ?: "…"
            val to = toMs?.let { fmt.format(Date(it)) } ?: "…"
            parts += "tanggal $from s.d. $to"
        }
        if (unattributedOnly) parts += "akun tidak terverifikasi saja"
        if (sourceAccountKey != null) parts += "satu akun sumber"
        if (direction != null) parts += "arah $direction"
        if (advisoryStatus != null) parts += "status $advisoryStatus"
        return if (parts.isEmpty()) "semua transaksi" else parts.joinToString(", ")
    }
}

/**
 * The live consent/config source the authorization snapshot is
 * taken from and revalidated against (§7.7A, R33/MAJOR E).
 *
 * Stage D has no config-epoch counter (route config lives in
 * SharedPreferences without a version): the config fingerprint
 * (SHA-256 of the route's url/enabled/keywords/packages) is the
 * effective config identity — any config change alters it, so a
 * mid-export config change still aborts the write.
 */
interface ConsentSnapshotSource {
    fun isGranted(routeId: String): Boolean
    fun consentEpoch(routeId: String): Long
    fun configFingerprint(routeId: String): String?
}

/** Production [ConsentSnapshotSource]: [ConsentStore] + [RouteSource]. */
class StoreConsentSnapshotSource(
    private val consentStore: ConsentStore,
    private val routes: RouteSource,
) : ConsentSnapshotSource {
    override fun isGranted(routeId: String): Boolean =
        runCatching {
            kotlinx.coroutines.runBlocking { consentStore.get(routeId)?.granted == true }
        }.getOrDefault(false)

    override fun consentEpoch(routeId: String): Long =
        runCatching {
            kotlinx.coroutines.runBlocking { consentStore.epoch(routeId) }
        }.getOrDefault(-1L)

    override fun configFingerprint(routeId: String): String? =
        routes.routeById(routeId)?.let { ConsentSnapshotSourceKt.configFingerprintOf(it) }
}

/** Shared config-fingerprint computation (also used by the fakes). */
internal object ConsentSnapshotSourceKt {
    fun configFingerprintOf(route: Route): String {
        val canonical = listOf(
            route.url, route.enabled.toString(),
            route.keywords.joinToString(","), route.packages.joinToString(","),
        ).joinToString("|")
        return sha256Hex(canonical)
    }
}

internal fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * The authorization snapshot (§7.7A): per-route consent epoch +
 * config fingerprint for every included route, the identifiers
 * choice, and the frozen row set (ordered `row_id` list).
 */
data class AuthorizationSnapshot(
    val routeEpochs: Map<String, Long>,
    val routeFingerprints: Map<String, String?>,
    val includeIdentifiers: Boolean,
    val rowIds: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("include_identifiers", includeIdentifiers)
        put("row_count", rowIds.size)
        put("row_ids_sha256", sha256Hex(rowIds.joinToString(",")))
        val routesObj = JSONObject()
        for ((routeId, epoch) in routeEpochs) {
            routesObj.put(
                routeId,
                JSONObject().apply {
                    put("consent_epoch", epoch)
                    routeFingerprints[routeId]?.let { put("config_fingerprint", it) }
                },
            )
        }
        put("routes", routesObj)
    }
}

/** One built export row: the 20 §7.7A columns in schema order. */
data class ExportRow(
    val fields: List<String>,
    /** `occurred_at_ms`, for the ascending-timestamp row order. */
    val occurredAtMs: Long,
) {
    val rowId: String get() = fields[0]
}

/**
 * The frozen, pre-validated export: the rows to write (already
 * sorted), the excluded-row count, the partial-chain flag, and
 * the authorization snapshot the write revalidates per row.
 */
data class FrozenExport(
    val rows: List<ExportRow>,
    val excludedCount: Int,
    val hasPartialChain: Boolean,
    val snapshot: AuthorizationSnapshot,
)

/** One source-account filter entry (key + masked display label). */
data class SourceAccountEntry(val key: String, val label: String)

/**
 * The reconciliation export (§7.7A, Q1 REQUIRED): builds the
 * frozen row set from Room — one row per (event, route) pair
 * with a recorded hop outcome, plus exactly one event-level
 * zero-hop row per event with no attempted hop.
 *
 * This class builds rows only; [ReconciliationExportRunner]
 * streams them to the SAF destination with per-row
 * revalidation and writes the `export_audit` row.
 *
 * Never in any row: raw notification text, OTP/PIN/token,
 * `qkey`, `dedupe_key`, `content_sha256`, internal `event_id`,
 * quarantined content, secrets, log/diagnostic content.
 */
class ReconciliationExport(
    private val eventDao: EventDao,
    private val deliveryDao: DeliveryDao,
    private val matchResultDao: MatchResultDao,
    private val classificationDao: ClassificationDao,
    private val routes: RouteSource,
    private val consentSource: ConsentSnapshotSource,
    /** Quarantined dedupe keys (legacy queue, outside Room). */
    private val quarantinedDedupeKeys: () -> Set<String> = { emptySet() },
) {
    companion object {
        /** The exact 20-column §7.7A schema, in order. */
        val COLUMNS: List<String> = listOf(
            "row_id", "event_ref", "timestamp", "amount", "currency",
            "direction", "bank_reference", "order_number", "sender_id",
            "recipient_id", "category", "source_application",
            "source_account", "route_label", "route_kind",
            "advisory_match_status", "attribution_status",
            "server_outcome", "resolution_label", "excluded_fields",
        )

        /** Columns whose values are untrusted text (F61 injection guard). */
        val UNTRUSTED_COLUMNS: Set<String> = setOf(
            "bank_reference", "order_number", "sender_id", "recipient_id",
            "category", "source_application", "source_account", "route_label",
        )

        /** Zero-hop sentinel route id (§7.7A). */
        const val ZERO_HOP_ROUTE_ID: String = "none"

        /** The partial-chain warning shown by the UI (§7.7A, R34). */
        const val PARTIAL_CHAIN_WARNING: String =
            "Ekspor ini hanya menampilkan rute yang sudah dicapai " +
                "peristiwa. Rute berikutnya dalam rantai mungkin masih " +
                "tertunda — periksa layar pengiriman untuk status rantai lengkap."

        /** ISO 4217 minor-unit scales (F66); unknown currencies use 0. */
        val CURRENCY_SCALES: Map<String, Int> = mapOf(
            "IDR" to 0, "JPY" to 0, "KRW" to 0, "VND" to 0,
            "USD" to 2, "EUR" to 2, "SGD" to 2, "MYR" to 2,
            "GBP" to 2, "AUD" to 2, "BHD" to 3, "KWD" to 3, "OMR" to 3,
        )

        /**
         * Formats integer minor units as a currency-appropriate
         * decimal string with integer arithmetic only (F66) —
         * binary floating-point is never used.
         */
        fun formatAmount(amountMinor: Long, currency: String): String {
            val scale = CURRENCY_SCALES[currency.uppercase(Locale.US)] ?: 0
            if (scale == 0) return amountMinor.toString()
            val sign = if (amountMinor < 0) "-" else ""
            val abs = kotlin.math.abs(amountMinor)
            var divisor = 1L
            repeat(scale) { divisor *= 10 }
            val whole = abs / divisor
            val frac = (abs % divisor).toString().padStart(scale, '0')
            return "$sign$whole.$frac"
        }

        /**
         * Parses a decimal amount string back to minor units with
         * integer arithmetic (F66 round-trip).
         */
        fun parseAmount(decimal: String, currency: String): Long? {
            val scale = CURRENCY_SCALES[currency.uppercase(Locale.US)] ?: 0
            val text = decimal.trim()
            if (text.isEmpty()) return null
            val negative = text.startsWith("-")
            val unsigned = if (negative) text.substring(1) else text
            val parts = unsigned.split(".")
            if (parts.size > 2) return null
            val whole = parts[0].toLongOrNull() ?: return null
            val fracText = if (parts.size == 2) parts[1] else ""
            if (fracText.length > scale) return null
            // No fraction text (including every scale-0 value)
            // means zero minor units — "".toLongOrNull() is null,
            // so the empty case must not go through the parser.
            val frac = if (fracText.isEmpty()) 0L
            else fracText.padEnd(scale, '0').toLongOrNull() ?: return null
            var divisor = 1L
            repeat(scale) { divisor *= 10 }
            val minor = whole * divisor + frac
            return if (negative) -minor else minor
        }

        /** RFC 4180 field encoding + F61 injection prefix. */
        fun encodeField(column: String, value: String): String {
            val guarded = if (column in UNTRUSTED_COLUMNS) guardInjection(value) else value
            return if (guarded.contains(',') || guarded.contains('"') ||
                guarded.contains('\r') || guarded.contains('\n')
            ) {
                "\"" + guarded.replace("\"", "\"\"") + "\""
            } else {
                guarded
            }
        }

        /**
         * The F61 injection guard: untrusted text whose first
         * non-whitespace/control character is a formula trigger
         * (`=`, `+`, `-`, `@`) gets a reversible `'` prefix. The
         * original value is never trimmed or altered — the guard
         * only adds the prefix. Residual risk (R33, MODERATE G):
         * the prefix is a mitigation, not a guarantee across
         * every spreadsheet import path.
         */
        fun guardInjection(value: String): String {
            val first = value.firstOrNull { it.code > 0x20 && it.code != 0x7F }
                ?: return value
            return if (first == '=' || first == '+' || first == '-' || first == '@') {
                "'$value"
            } else {
                value
            }
        }

        /**
         * The documented strip rule: removes exactly one leading
         * `'` when the remainder (after trimming leading
         * whitespace/control characters) starts with a trigger
         * character; otherwise returns the field unchanged.
         */
        fun stripInjectionPrefix(field: String): String {
            if (!field.startsWith("'")) return field
            val rest = field.substring(1)
            val first = rest.firstOrNull { it.code > 0x20 && it.code != 0x7F } ?: return field
            return if (first == '=' || first == '+' || first == '-' || first == '@') rest else field
        }

        /** ISO 8601 UTC with millisecond precision. */
        fun formatTimestamp(ms: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return fmt.format(Date(ms))
        }

        /** Suggested file name: `paylist-reconciliation-YYYYMMDD-HHMMSS.csv`. */
        fun suggestedFileName(nowMs: Long): String {
            val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return "paylist-reconciliation-${fmt.format(Date(nowMs))}.csv"
        }

        /** Package display label (always present; falls back to the package). */
        fun packageLabel(pkg: String?): String = when (pkg) {
            "com.gojek.gopaymerchant" -> "GoPay Merchant"
            "com.gojek.gopay" -> "GoPay"
            "com.bca" -> "BCA mobile"
            "id.co.bri.brimo" -> "BRImo"
            null -> ""
            else -> pkg
        }

        /** Recorder routes are the `catat.php` endpoints (§7.3B). */
        fun routeKind(route: Route): String =
            if (route.url.contains("catat.php")) "RECORDER" else "PAYMENT"
    }

    /**
     * Builds the frozen export for a filter. The row set is
     * frozen here: rows arriving after this call are never added
     * mid-export.
     */
    suspend fun build(filter: ExportFilter, includeIdentifiers: Boolean): FrozenExport {
        val events = eventDao.listPaymentEvents(filter.fromMs, filter.toMs)
        val quarantined = quarantinedDedupeKeys()
        val rows = mutableListOf<ExportRow>()
        var excluded = 0
        var hasPartialChain = false
        val includedRoutes = mutableSetOf<String>()

        for (event in events) {
            if (event.dedupeKey in quarantined) {
                excluded++
                continue
            }
            if (filter.unattributedOnly && event.sourceAccountKey != null) continue
            if (filter.sourceAccountKey != null &&
                event.sourceAccountKey != filter.sourceAccountKey
            ) {
                continue
            }
            val match = latestMatch(event.eventId)
            if (filter.advisoryStatus != null &&
                match?.advisoryMatchStatus != filter.advisoryStatus
            ) {
                continue
            }
            val direction = eventDirection(event)
            if (filter.direction != null && direction != filter.direction) continue

            val delivery = currentDelivery(event.eventId)
            val hops = delivery?.let { HopOutcome.fromJsonArray(it.hopOutcomesJson) }.orEmpty()
            if (hops.isEmpty()) {
                val targets = delivery?.let { targetRouteIds(it) }.orEmpty()
                val consenting = targets.any { consentSource.isGranted(it) }
                if (!consenting) {
                    // Multi-route zero-hop consent (R34): no
                    // consenting target route => no row at all.
                    excluded++
                    continue
                }
                includedRoutes += targets.filter { consentSource.isGranted(it) }
                rows += zeroHopRow(event, delivery, match, direction)
            } else {
                val latestByRoute = hops.groupBy { it.routeId }
                    .mapValues { (_, list) -> list.last() }
                for ((routeId, hop) in latestByRoute) {
                    val route = routes.routeById(routeId)
                    if (route == null) {
                        // The route is gone: kind and config are
                        // unverifiable — fail-closed exclusion.
                        excluded++
                        continue
                    }
                    if (!consentSource.isGranted(routeId)) {
                        excluded++
                        continue
                    }
                    includedRoutes += routeId
                    rows += hopRow(event, delivery, match, direction, route, hop)
                }
                // Partial chain: reached hops exist, an unreached
                // target route remains, and no hop stopped the
                // chain terminally (a MATCHED_* stop is completion
                // by design, not a pending chain).
                val targets = delivery?.let { targetRouteIds(it) }.orEmpty()
                val unreached = targets.any { it !in latestByRoute }
                val chainTerminal = hops.any { HopOutcome.isChainTerminalOutcome(it.outcome) }
                if (unreached && !chainTerminal) hasPartialChain = true
            }
        }

        val sorted = rows.sortedWith(compareBy({ it.occurredAtMs }, { it.rowId }))
        val snapshot = AuthorizationSnapshot(
            routeEpochs = includedRoutes.associateWith { consentSource.consentEpoch(it) },
            routeFingerprints = includedRoutes.associateWith { consentSource.configFingerprint(it) },
            includeIdentifiers = includeIdentifiers,
            rowIds = sorted.map { it.rowId },
        )
        return FrozenExport(sorted, excluded, hasPartialChain, snapshot)
    }

    /** Revalidates a snapshot against current consent/config state. */
    fun revalidate(snapshot: AuthorizationSnapshot): Boolean {
        for ((routeId, epoch) in snapshot.routeEpochs) {
            if (!consentSource.isGranted(routeId)) return false
            if (consentSource.consentEpoch(routeId) != epoch) return false
            if (consentSource.configFingerprint(routeId) != snapshot.routeFingerprints[routeId]) {
                return false
            }
        }
        return true
    }

    /**
     * Source-account filter entries for the export UI: one entry
     * per verified account key in range, labelled by its masked
     * display value. Two accounts sharing one masked display
     * value stay separate entries (keyed internally, never
     * merged); where no distinguishing label exists the entries
     * carry the same label and the UI shows the ambiguity.
     */
    suspend fun listSourceAccounts(fromMs: Long?, toMs: Long?): List<SourceAccountEntry> {
        val events = eventDao.listPaymentEvents(fromMs, toMs)
        val keys = events.mapNotNull { it.sourceAccountKey }.distinct()
        val entries = mutableListOf<SourceAccountEntry>()
        for (key in keys) {
            val event = events.first { it.sourceAccountKey == key }
            val masked = latestMatch(event.eventId)?.sourceAccountMasked
            entries += SourceAccountEntry(key, masked ?: "akun terverifikasi")
        }
        return entries.sortedBy { it.label }
    }

    // --- Row construction ---

    private suspend fun latestMatch(eventId: String): MatchResultEntity? =
        matchResultDao.getByEventId(eventId).maxByOrNull { it.createdAtMs }

    private suspend fun latestClassification(eventId: String): ClassificationEntity? =
        classificationDao.getByEventId(eventId).maxByOrNull { it.createdAtMs }

    private suspend fun currentDelivery(eventId: String): DeliveryEntity? {
        val deliveries = deliveryDao.getByEventId(eventId)
        if (deliveries.isEmpty()) return null
        val nonTerminal = deliveries.filter { it.state != DeliveryState.ACKED }
            .filter { it.state != DeliveryState.EXPIRED }
            .filter { it.state != DeliveryState.DISCARDED }
        return nonTerminal.maxByOrNull { it.updatedAtMs }
            ?: deliveries.maxByOrNull { it.updatedAtMs }
    }

    private fun targetRouteIds(delivery: DeliveryEntity): List<String> {
        return try {
            val arr = JSONArray(delivery.routeIdsJson)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun eventDirection(event: EventEntity): String {
        val payload = try {
            event.legacyPayloadJson?.let { JSONObject(it) }
        } catch (e: Exception) {
            null
        }
        return when (payload?.optString("direction")?.lowercase(Locale.US)) {
            "in" -> "IN"
            "out" -> "OUT"
            else -> "UNKNOWN"
        }
    }

    private fun eventAmount(event: EventEntity): Pair<Long?, String> {
        val data = try {
            JSONObject(event.dataJson)
        } catch (e: Exception) {
            JSONObject()
        }
        if (data.has("amount")) {
            val currency = data.optString("currency").ifEmpty { "IDR" }
            return data.optLong("amount") to currency
        }
        val payload = try {
            event.legacyPayloadJson?.let { JSONObject(it) }
        } catch (e: Exception) {
            null
        }
        if (payload != null && payload.has("amount")) {
            return payload.optLong("amount") to "IDR"
        }
        return null to "IDR"
    }

    /**
     * A free-text field (never a P3 value) is exportable only
     * when the PrivacyGate class-(a)/(b) scan finds no unresolved
     * sensitive span; otherwise it is omitted and named in
     * `excluded_fields` (F57) — never exported.
     *
     * P3 fields (`bank_reference`, `order_number`,
     * `source_account`) are NOT re-scanned here: they passed the
     * §3.1.3 evidence bar at extraction (confirmed per-source
     * pattern, labelled context), and re-scanning the bare value
     * would strip that confirming context — a confirmed reference
     * like `TRX-9988` would read as an ambiguous bare-digit run.
     * The P3 bar is the scan for those fields.
     */
    private fun privacyField(value: String?, excluded: MutableList<String>, column: String): String {
        if (value.isNullOrEmpty()) return ""
        val scan = PrivacySpanScan.scan(value)
        if (scan.confirmedCount > 0 || scan.ambiguousCount > 0) {
            excluded += column
            return ""
        }
        return value
    }

    private suspend fun commonFields(
        event: EventEntity,
        match: MatchResultEntity?,
        direction: String,
        excluded: MutableList<String>,
    ): Map<String, String> {
        val classification = latestClassification(event.eventId)
        val (amountMinor, currency) = eventAmount(event)
        // sender_id / recipient_id: no P3 sender/recipient
        // extraction exists in v1.1, so both columns are empty;
        // the opt-in + device-auth gate are built and audited,
        // and the columns stay empty until extraction lands.
        val senderId = ""
        val recipientId = ""
        return mapOf(
            "event_ref" to event.exportRef,
            "timestamp" to formatTimestamp(event.occurredAtMs),
            "amount" to (amountMinor?.let { formatAmount(it, currency) } ?: ""),
            "currency" to currency,
            "direction" to direction,
            // P3 values: confirmed by the §3.1.3 evidence bar at
            // extraction — exported as stored (see privacyField).
            "bank_reference" to (match?.bankReference ?: ""),
            "order_number" to (match?.orderNumber ?: ""),
            "sender_id" to senderId,
            "recipient_id" to recipientId,
            "category" to privacyField(
                classification?.category ?: "UNCLASSIFIED", excluded, "category",
            ),
            "source_application" to privacyField(
                packageLabel(event.sourcePackage), excluded, "source_application",
            ),
            "source_account" to (match?.sourceAccountMasked ?: ""),
            "advisory_match_status" to (match?.advisoryMatchStatus ?: ""),
            "attribution_status" to (match?.attributionStatus ?: "none"),
        )
    }

    private fun resolutionFor(
        serverOutcome: String,
        deliveryState: DeliveryState?,
        advisoryStatus: String?,
    ): String = when {
        serverOutcome == "MATCHED_SETTLED" || serverOutcome == "RECORDED" -> "RESOLVED"
        deliveryState == DeliveryState.NEEDS_REVIEW ||
            deliveryState == DeliveryState.RAW_UNAVAILABLE ||
            serverOutcome == "AMBIGUOUS" ||
            advisoryStatus == "manual_review" ||
            advisoryStatus == "ambiguous" -> "UNRESOLVED_NEEDS_REVIEW"
        else -> "UNRESOLVED_UNKNOWN"
    }

    private suspend fun zeroHopRow(
        event: EventEntity,
        delivery: DeliveryEntity?,
        match: MatchResultEntity?,
        direction: String,
    ): ExportRow {
        val excluded = mutableListOf<String>()
        val common = commonFields(event, match, direction, excluded)
        val state = delivery?.state
        val resolution = if (
            state == DeliveryState.NEEDS_REVIEW || state == DeliveryState.RAW_UNAVAILABLE
        ) {
            "UNRESOLVED_NEEDS_REVIEW"
        } else {
            "UNRESOLVED_UNKNOWN"
        }
        val fields = COLUMNS.map { column ->
            when (column) {
                "row_id" -> sha256Hex("${event.exportRef}|$ZERO_HOP_ROUTE_ID")
                "route_label" -> ""
                "route_kind" -> "NONE"
                "server_outcome" -> "NOT_DISPATCHED"
                "resolution_label" -> resolution
                "excluded_fields" -> excluded.joinToString(",")
                else -> common[column] ?: ""
            }
        }
        return ExportRow(fields, event.occurredAtMs)
    }

    private suspend fun hopRow(
        event: EventEntity,
        delivery: DeliveryEntity?,
        match: MatchResultEntity?,
        direction: String,
        route: Route,
        hop: HopOutcome,
    ): ExportRow {
        val excluded = mutableListOf<String>()
        val common = commonFields(event, match, direction, excluded)
        val kind = routeKind(route)
        val serverOutcome = mapServerOutcome(kind, hop)
        val resolution = resolutionFor(serverOutcome, delivery?.state, match?.advisoryMatchStatus)
        val routeLabel = privacyField(route.name, excluded, "route_label")
        val fields = COLUMNS.map { column ->
            when (column) {
                "row_id" -> sha256Hex("${event.exportRef}|${route.id}")
                "route_label" -> routeLabel
                "route_kind" -> kind
                "server_outcome" -> serverOutcome
                "resolution_label" -> resolution
                "excluded_fields" -> excluded.joinToString(",")
                else -> common[column] ?: ""
            }
        }
        return ExportRow(fields, event.occurredAtMs)
    }

    /**
     * Maps a hop outcome to the §7.7A `server_outcome` vocabulary.
     * Payment routes use the §5.4 taxonomy; recorder routes use
     * RECORDED/NOT_RECORDED/UNKNOWN — never conflated, and
     * advisory `matched` is never collapsed into
     * MATCHED_SETTLED (the hop outcome already carries the
     * server's own classification).
     *
     * NOT_RECORDED needs authoritative absence coverage (P12 §9
     * status query), which does not exist yet in Stage D — so a
     * recorder hop without a confirmed recording is UNKNOWN,
     * never NOT_RECORDED.
     */
    private fun mapServerOutcome(kind: String, hop: HopOutcome): String {
        if (kind == "RECORDER") {
            return when (hop.outcome) {
                "LOGGED", "MATCHED_SETTLED", "MATCHED_NOT_APPLIED",
                "MATCHED_UNCONFIRMED" -> "RECORDED"
                else -> "UNKNOWN"
            }
        }
        return when (hop.outcome) {
            "MATCHED_SETTLED", "MATCHED_NOT_APPLIED", "MATCHED_UNCONFIRMED",
            "AMBIGUOUS", "LOGGED" -> hop.outcome
            "NO_POST", "BLOCKED_CONSENT", "BLOCKED_CONFIG", "BLOCKED_SECRET" -> "HELD"
            else -> "UNKNOWN"
        }
    }
}
