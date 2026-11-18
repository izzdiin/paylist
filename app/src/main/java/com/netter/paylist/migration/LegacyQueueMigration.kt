package com.netter.paylist.migration

import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.TransactionRunner
import com.netter.paylist.data.dao.AttemptDao
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.data.dao.EventDao
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.data.entity.HopOutcome
import com.netter.paylist.data.entity.PromotionReceiptDao
import com.netter.paylist.data.entity.PromotionReceiptEntity
import com.netter.paylist.data.entity.ReceiptState
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * The legacy queue key (`qkey`) contract (design §3, R11; `Sender.kt`):
 * `dedupe_key|kind|route-set`, where `kind` is `chain` or `single`
 * and `route-set` is the comma-joined route ids (one id for `single`).
 *
 * The `dedupe_key` itself contains `|` separators
 * (`pkg|postTime|amount|hash`), so parsing takes the LAST two
 * segments as kind + routes and everything before as the dedupe key.
 */
object LegacyQkey {
    const val KIND_CHAIN: String = "chain"
    const val KIND_SINGLE: String = "single"

    data class Parsed(val dedupeKey: String, val kind: String, val routeIds: List<String>)

    fun parse(qkey: String): Parsed? {
        val parts = qkey.split("|")
        if (parts.size < 3) return null
        val kind = parts[parts.size - 2]
        if (kind != KIND_CHAIN && kind != KIND_SINGLE) return null
        val routeIds = parts.last().split(",").filter { it.isNotEmpty() }
        if (routeIds.isEmpty()) return null
        val dedupeKey = parts.subList(0, parts.size - 2).joinToString("|")
        if (dedupeKey.isBlank()) return null
        return Parsed(dedupeKey, kind, routeIds)
    }

    fun build(dedupeKey: String, kind: String, routeIds: List<String>): String =
        "$dedupeKey|$kind|${routeIds.joinToString(",")}"
}

/**
 * Canonical JSON serialisation (design §8.2, normative): object keys
 * sorted lexicographically at every depth; arrays keep order; strings
 * escaped per the RFC 8259 minimal set (only `"` `\` and U+0000–U+001F
 * as `\u00XX`); numbers as plain decimal (integral, no
 * fraction/exponent; non-integral via shortest round-trip decimal,
 * no exponent).
 */
object CanonicalJson {
    fun stringify(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            val parts = keys.map { k -> escape(k) + ":" + stringify(value.opt(k)) }
            "{" + parts.joinToString(",") + "}"
        }
        is JSONArray -> {
            val parts = (0 until value.length()).map { stringify(value.opt(it)) }
            "[" + parts.joinToString(",") + "]"
        }
        is String -> escape(value)
        is Boolean -> value.toString()
        is Number -> numberString(value)
        else -> escape(value.toString())
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun escape(s: String): String {
        val out = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                else -> if (ch.code < 0x20) {
                    out.append("\\u%04x".format(ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append("\"")
        return out.toString()
    }

    private fun numberString(value: Number): String = when (value) {
        is Int, is Long -> value.toLong().toString()
        is Double -> {
            if (value.isNaN() || value.isInfinite()) {
                throw IllegalArgumentException("non-finite number in canonical JSON")
            }
            val asLong = value.toLong()
            if (value == asLong.toDouble() && value >= Long.MIN_VALUE.toDouble() &&
                value <= Long.MAX_VALUE.toDouble()
            ) {
                asLong.toString()
            } else {
                BigDecimal.valueOf(value).toPlainString()
            }
        }
        else -> BigDecimal(value.toString()).toPlainString()
    }
}

/** One parsed legacy queue item (the frozen snapshot's unit). */
data class LegacyQueueItem(
    val qkey: String,
    val kind: String,
    val routeIds: List<String>,
    val next: Int,
    val attempts: Int,
    val lastError: String?,
    val lastTryMs: Long?,
    val payloadJson: JSONObject,
    val dedupeKey: String,
    val type: String,
    /** v1.1 flush checkpoint (`hop_outcomes`), absent for pre-v1.1 items. */
    val hopOutcomesJson: String?,
    /**
     * P9 gate markers (Stage C, design §7.7): set by
     * `LegacyQueuePrivacyGate.reprocess()` on the raw queue item.
     * Absent (false) on items that never went through the gate.
     */
    val privacyReprocessed: Boolean = false,
    val privacyQuarantined: Boolean = false,
) {
    companion object {
        /**
         * Parses one queue item. Returns null for a corrupt item
         * (missing `qkey`, `kind`, payload, `dedupe_key`, `type`, or
         * an empty route set) — corrupt items are counted and logged,
         * never stop the migration of other items (§4.2).
         */
        fun fromJson(o: JSONObject): LegacyQueueItem? {
            val qkey = o.optString("qkey").ifEmpty { return null }
            val kind = o.optString("kind")
            if (kind != LegacyQkey.KIND_CHAIN && kind != LegacyQkey.KIND_SINGLE) return null
            val routeIds = when (kind) {
                LegacyQkey.KIND_CHAIN -> {
                    val arr = o.optJSONArray("route_ids") ?: return null
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
                }
                else -> {
                    val single = o.optString("route_id").ifEmpty { return null }
                    listOf(single)
                }
            }
            if (routeIds.isEmpty()) return null
            val payload = o.optJSONObject("payload") ?: return null
            val dedupeKey = payload.optString("dedupe_key").ifEmpty { return null }
            val type = payload.optString("type").ifEmpty { return null }
            val hopOutcomes = when (val h = o.opt("hop_outcomes")) {
                is JSONArray -> h.toString()
                is String -> h.ifEmpty { null }
                else -> null
            }
            return LegacyQueueItem(
                qkey = qkey,
                kind = kind,
                routeIds = routeIds,
                next = o.optInt("next", 0),
                attempts = o.optInt("attempts", 0),
                lastError = o.optString("last_error").ifEmpty { null },
                lastTryMs = if (o.has("last_try")) o.optLong("last_try") else null,
                payloadJson = payload,
                dedupeKey = dedupeKey,
                type = type,
                hopOutcomesJson = hopOutcomes,
                privacyReprocessed = o.optBoolean("privacy_reprocessed", false),
                privacyQuarantined = o.optBoolean("privacy_quarantine", false),
            )
        }
    }
}

/** P9 inspection verdict for one legacy item (Stage C gate input). */
enum class Inspection {
    /** Clean: byte-identical passthrough is permitted. */
    IMPORTABLE,

    /** Uncertain: quarantined — never POSTed, imported, or exported. */
    QUARANTINED,
}

/**
 * The P9 per-item inspector (design §7.7, R29). Stage C implements
 * it (`LegacyQueuePrivacyGate`); Stage B takes it as a parameter so
 * the migration can be tested without the gate. The inspector
 * decides per item; there is no bulk verdict.
 */
fun interface LegacyQueueInspector {
    fun inspect(item: LegacyQueueItem): Inspection
}

/** A frozen legacy-queue snapshot (design §8.2 step 2). */
data class LegacyQueueSnapshot(
    /** Parsed items, in queue order. */
    val items: List<LegacyQueueItem>,
    /** Queue positions that failed to parse (corrupt items). */
    val corruptIndices: List<Int>,
    /** Expired heartbeat items, skipped (not migrated, §4.2). */
    val expiredSkippedQkeys: List<String>,
    /** Canonical checksum over the whole snapshot (all parsed items). */
    val checksum: String,
    /** Snapshot time (epoch ms). */
    val snapshotAtMs: Long,
)

/** A snapshot partitioned by the P9 gate (design §8.2, R29 override). */
data class PartitionedSnapshot(
    val snapshot: LegacyQueueSnapshot,
    val importable: List<LegacyQueueItem>,
    val quarantined: List<LegacyQueueItem>,
    /** Canonical checksum over the importable set only. */
    val importableChecksum: String,
    /** Canonical checksum over the quarantine metadata (`qkey` + reason). */
    val quarantineChecksum: String,
)

/** Result of one import run. */
data class ImportReport(
    /** New event rows created. */
    val eventsImported: Int,
    /** New delivery rows created. */
    val deliveriesImported: Int,
    /** Items skipped because their event already existed (re-run idempotency). */
    val duplicatesSkipped: Int,
    /** Items skipped as expired heartbeats. */
    val expiredSkipped: Int,
    /** Items that failed to parse. */
    val corruptCount: Int,
    /** Quarantined items excluded from the import. */
    val quarantinedExcluded: Int,
    /** Imported deliveries by state. */
    val stateCounts: Map<DeliveryState, Int>,
)

/** Result of import verification (design §8.2 step 4). */
data class VerifyResult(
    val ok: Boolean,
    val detail: String,
)

/**
 * The idempotent legacy-queue importer (design §8.1(3), §8.2).
 *
 * Stage B: implement and test the importer DISABLED for real cutover
 * (file plan §C; R29). Stage C activation requires F50/F54 to PASS,
 * per-item P9 inspection, quarantined-set exclusion, separate
 * importable/quarantine counts/checksums, payload/chain-cursor
 * preservation, and clearing only individually verified
 * imported/resolved items — never the whole queue.
 *
 * The importer NEVER writes to the legacy queue and NEVER deletes
 * anything. It reads a frozen snapshot, writes Room rows with
 * `INSERT OR IGNORE` (idempotent by `qkey`), and verifies count +
 * `qkey` set + checksum. Quarantined legacy entries remain outside
 * Room and outside the worker, with a separately verified holding-pen
 * count.
 */
class LegacyQueueMigration(
    private val eventDao: EventDao,
    private val deliveryDao: DeliveryDao,
    private val attemptDao: AttemptDao,
    private val promotionReceiptDao: PromotionReceiptDao,
    private val transactions: TransactionRunner,
) {
    companion object {
        /** Import batch size: one transaction per batch (§4.2). */
        const val IMPORT_BATCH_SIZE: Int = 50

        /** Heartbeat dedupe bucket length (15 min, `Sender.kt`). */
        const val HEARTBEAT_BUCKET_MS: Long = 900_000L

        /** Heartbeat expiry: 2 buckets (30 min, `Sender.kt`). */
        const val HEARTBEAT_EXPIRY_BUCKETS: Long = 2L

        /**
         * Canonical checksum (design §8.2, normative): SHA-256 over
         * the per-item serialisation
         * `qkey + '|' + payload JSON (keys sorted) + '|' + route_ids +
         * '|' + next + '|' + attempts + '|' + last_error`, items
         * sorted by `qkey`, joined with `\n`. Count equality alone is
         * not sufficient verification.
         */
        fun checksum(items: List<LegacyQueueItem>): String {
            val serialised = items.sortedBy { it.qkey }.joinToString("\n") { item ->
                listOf(
                    item.qkey,
                    CanonicalJson.stringify(item.payloadJson),
                    CanonicalJson.stringify(JSONArray(item.routeIds)),
                    item.next.toString(),
                    item.attempts.toString(),
                    item.lastError.orEmpty(),
                ).joinToString("|")
            }
            return CanonicalJson.sha256Hex(serialised)
        }

        /** Checksum over quarantine metadata: `qkey` + reason, sorted by `qkey`. */
        fun quarantineChecksum(quarantined: List<LegacyQueueItem>): String {
            val serialised = quarantined.sortedBy { it.qkey }.joinToString("\n") { it.qkey }
            return CanonicalJson.sha256Hex(serialised)
        }

        /** Expired-heartbeat rule, identical to `Sender.kt` (2 buckets = 30 min). */
        fun isExpiredHeartbeat(item: LegacyQueueItem, nowMs: Long): Boolean {
            if (item.type != "heartbeat") return false
            val bucket = item.dedupeKey.substringAfterLast('-').toLongOrNull() ?: return false
            val nowBucket = nowMs / HEARTBEAT_BUCKET_MS
            return nowBucket - bucket >= HEARTBEAT_EXPIRY_BUCKETS
        }
    }

    /**
     * Freezes a read-only snapshot of the legacy queue (design §8.2
     * step 2). The live legacy queue is not modified, not emptied,
     * not moved. Corrupt items are counted; expired heartbeats are
     * skipped (not migrated, counted in the report — the same
     * behaviour as today).
     */
    fun snapshot(queueItems: List<JSONObject>, nowMs: Long): LegacyQueueSnapshot {
        val parsed = mutableListOf<LegacyQueueItem>()
        val corrupt = mutableListOf<Int>()
        val expired = mutableListOf<String>()
        for ((index, raw) in queueItems.withIndex()) {
            val item = LegacyQueueItem.fromJson(raw)
            if (item == null) {
                corrupt.add(index)
                continue
            }
            if (isExpiredHeartbeat(item, nowMs)) {
                expired.add(item.qkey)
                continue
            }
            parsed.add(item)
        }
        return LegacyQueueSnapshot(
            items = parsed,
            corruptIndices = corrupt,
            expiredSkippedQkeys = expired,
            checksum = checksum(parsed),
            snapshotAtMs = nowMs,
        )
    }

    /** Partitions a snapshot by the P9 inspector (R29 override). */
    fun partition(snapshot: LegacyQueueSnapshot, inspector: LegacyQueueInspector): PartitionedSnapshot {
        val importable = mutableListOf<LegacyQueueItem>()
        val quarantined = mutableListOf<LegacyQueueItem>()
        for (item in snapshot.items) {
            when (inspector.inspect(item)) {
                Inspection.IMPORTABLE -> importable.add(item)
                Inspection.QUARANTINED -> quarantined.add(item)
            }
        }
        return PartitionedSnapshot(
            snapshot = snapshot,
            importable = importable,
            quarantined = quarantined,
            importableChecksum = checksum(importable),
            quarantineChecksum = quarantineChecksum(quarantined),
        )
    }

    /**
     * Imports the importable set idempotently into the Room outbox
     * (`INSERT OR IGNORE` by `qkey`), in batches of [IMPORT_BATCH_SIZE]
     * per transaction. A re-run skips already-imported rows (no
     * duplicates, no loss — T10).
     *
     * Per item, the import state follows the §8.5 snapshot import
     * rule (see [importStateFor]): the Room attempt rows and the
     * promotion receipt win the snapshot join over the queue file.
     */
    suspend fun import(partitioned: PartitionedSnapshot, nowMs: Long): ImportReport {
        var eventsImported = 0
        var deliveriesImported = 0
        var duplicatesSkipped = 0
        val stateCounts = mutableMapOf<DeliveryState, Int>()
        val importable = partitioned.importable
        for (batch in importable.chunked(IMPORT_BATCH_SIZE)) {
            val batchResult = transactions.run {
                var batchEvents = 0
                var batchDeliveries = 0
                var batchDuplicates = 0
                val batchStates = mutableMapOf<DeliveryState, Int>()
                for (item in batch) {
                    val unresolved = attemptDao.getUnresolvedByDedupeKey(item.dedupeKey)
                    val receipt = promotionReceiptDao.getByLegacyQkey(item.qkey)
                    val decision = importStateFor(item, unresolved, receipt)
                    val event = item.toEventEntity(nowMs)
                    // Room IGNORE insert returns -1 on a unique
                    // conflict (never 0): -1 means "already there".
                    val eventRow = eventDao.insert(event)
                    val eventId = if (eventRow != -1L) {
                        batchEvents++
                        event.eventId
                    } else {
                        batchDuplicates++
                        eventDao.getByDedupeKey(item.dedupeKey)?.eventId ?: event.eventId
                    }
                    val delivery = decision.toDeliveryEntity(item, eventId, nowMs)
                    if (deliveryDao.insert(delivery) != -1L) {
                        batchDeliveries++
                        batchStates[delivery.state] = (batchStates[delivery.state] ?: 0) + 1
                    }
                }
                BatchResult(batchEvents, batchDeliveries, batchDuplicates, batchStates)
            }
            eventsImported += batchResult.events
            deliveriesImported += batchResult.deliveries
            duplicatesSkipped += batchResult.duplicates
            for ((state, count) in batchResult.states) {
                stateCounts[state] = (stateCounts[state] ?: 0) + count
            }
        }
        return ImportReport(
            eventsImported = eventsImported,
            deliveriesImported = deliveriesImported,
            duplicatesSkipped = duplicatesSkipped,
            expiredSkipped = partitioned.snapshot.expiredSkippedQkeys.size,
            corruptCount = partitioned.snapshot.corruptIndices.size,
            quarantinedExcluded = partitioned.quarantined.size,
            stateCounts = stateCounts,
        )
    }

    /**
     * Verifies an import (design §8.2 step 4): the re-read queue's
     * importable set still checksums to the snapshot's importable
     * checksum, every importable `qkey` has a Room delivery, and the
     * imported count equals the importable count. Count equality
     * alone is not sufficient — the checksum and the `qkey` set are
     * checked too.
     */
    suspend fun verify(
        partitioned: PartitionedSnapshot,
        currentQueueItems: List<JSONObject>,
        nowMs: Long,
    ): VerifyResult {
        val reSnapshot = snapshot(currentQueueItems, nowMs)
        val reImportable = reSnapshot.items.filter { item ->
            partitioned.importable.any { it.qkey == item.qkey }
        }
        if (checksum(reImportable) != partitioned.importableChecksum) {
            return VerifyResult(false, "importable checksum mismatch: the frozen queue changed during import")
        }
        val expectedQkeys = partitioned.importable.map { it.qkey }.toSet()
        val foundQkeys = reImportable.map { it.qkey }.toSet()
        if (foundQkeys != expectedQkeys) {
            return VerifyResult(false, "importable qkey set changed during import")
        }
        for (item in partitioned.importable) {
            if (deliveryDao.getByLegacyQkey(item.qkey) == null) {
                return VerifyResult(false, "missing Room delivery for qkey ${item.qkey}")
            }
        }
        return VerifyResult(true, "import verified: ${partitioned.importable.size} deliveries, checksum match")
    }

    /**
     * The §8.5 snapshot import rule, as a pure decision.
     *
     * - A `RESOLVED` receipt proves the terminal outcome: the item
     *   imports terminal (never `PENDING` when the receipt proves
     *   `RESOLVED`-terminal).
     * - An `IN_FLIGHT` receipt means the outcome is unknown but a
     *   re-POST is permitted under at-least-once (§4.5): `PENDING`
     *   with the hop marked unknown.
     * - An unresolved attempt row (orphaned) holds the delivery
     *   `HELD_AWAITING_RECONCILIATION` (§8.5(g)): never dispatched,
     *   never re-POSTed as if new.
     * - With hop records: the last hop's outcome decides (chain-terminal
     *   → `ACKED`; 4xx/gate → `BLOCKED`; unknown → `PENDING` with the
     *   hop re-POSTed at-least-once; logged/skipped + exhausted →
     *   `ACKED`, except an all-skipped chain → `BLOCKED`, visible,
     *   never silently dropped).
     * - Without hop records: `PENDING` with the persisted `next`,
     *   except a cursor advanced past the chain end with no record —
     *   the row-5 guard: a cursor alone proves nothing, so the
     *   delivery is `HELD_AWAITING_RECONCILIATION`.
     */
    fun importStateFor(
        item: LegacyQueueItem,
        unresolvedAttempts: List<com.netter.paylist.data.entity.DeliveryAttemptEntity>,
        receipt: PromotionReceiptEntity?,
    ): ImportDecision {
        if (receipt != null && receipt.state == ReceiptState.RESOLVED) {
            return when (receipt.outcome) {
                "no_post" -> ImportDecision(DeliveryState.DISCARDED, null, null, null)
                else -> when (receipt.lastHopOutcome) {
                    "MATCHED_SETTLED", "MATCHED_NOT_APPLIED", "MATCHED_UNCONFIRMED" ->
                        ImportDecision(DeliveryState.ACKED, "matched", null, null)
                    "AMBIGUOUS" -> ImportDecision(DeliveryState.ACKED, "ambiguous", null, null)
                    "LOGGED" -> ImportDecision(DeliveryState.ACKED, "logged", null, null)
                    "REJECTED-4xx" -> ImportDecision(
                        DeliveryState.BLOCKED, null, null, "rejected-4xx",
                    )
                    "UNKNOWN_PENDING_RECONCILIATION" ->
                        ImportDecision(DeliveryState.HELD_AWAITING_RECONCILIATION, null, null, null)
                    else -> ImportDecision(DeliveryState.HELD_AWAITING_RECONCILIATION, null, null, null)
                }
            }
        }
        if (receipt != null && receipt.state == ReceiptState.IN_FLIGHT) {
            return ImportDecision(DeliveryState.PENDING, null, null, null)
        }
        if (unresolvedAttempts.isNotEmpty()) {
            return ImportDecision(DeliveryState.HELD_AWAITING_RECONCILIATION, null, null, null)
        }
        val hops = HopOutcome.fromJsonArray(item.hopOutcomesJson)
        if (hops.isNotEmpty()) {
            val last = hops.last()
            return when {
                HopOutcome.isChainTerminalOutcome(last.outcome) -> {
                    val match = if (last.outcome == "AMBIGUOUS") "ambiguous" else "matched"
                    ImportDecision(DeliveryState.ACKED, match, last.apply, null)
                }
                last.outcome == "REJECTED-4xx" ->
                    ImportDecision(DeliveryState.BLOCKED, null, null, "rejected-4xx")
                last.outcome == "BLOCKED_CONSENT" || last.outcome == "BLOCKED_CONFIG" ->
                    ImportDecision(DeliveryState.BLOCKED, null, null, last.outcome)
                last.outcome == "UNKNOWN_PENDING_RECONCILIATION" ->
                    ImportDecision(DeliveryState.PENDING, null, null, null)
                item.next >= item.routeIds.size -> {
                    // Chain exhausted with records: terminal, unless
                    // every hop was a skip (nothing was ever sent) —
                    // that is BLOCKED (visible), never silently done.
                    if (hops.all { it.outcome == "NO_POST" }) {
                        ImportDecision(DeliveryState.BLOCKED, null, null, "route_missing_or_disabled")
                    } else {
                        val match = if (last.outcome == "LOGGED") "logged" else null
                        ImportDecision(DeliveryState.ACKED, match, null, null)
                    }
                }
                else -> ImportDecision(DeliveryState.PENDING, null, null, null)
            }
        }
        return if (item.next >= item.routeIds.size) {
            // Cursor advanced with no per-hop record: the row-5 guard —
            // the cursor alone proves nothing about any hop.
            ImportDecision(DeliveryState.HELD_AWAITING_RECONCILIATION, null, null, null)
        } else {
            ImportDecision(DeliveryState.PENDING, null, null, null)
        }
    }

    private data class BatchResult(
        val events: Int,
        val deliveries: Int,
        val duplicates: Int,
        val states: Map<DeliveryState, Int>,
    )
}

/** The import's per-item state decision (see `LegacyQueueMigration.importStateFor`). */
data class ImportDecision(
    val state: DeliveryState,
    val lastMatchStatus: String?,
    val lastApplyStatus: String?,
    val lastErrorSummary: String?,
) {
    fun toDeliveryEntity(item: LegacyQueueItem, eventId: String, nowMs: Long): DeliveryEntity {
        val terminal = state == DeliveryState.ACKED || state == DeliveryState.DISCARDED
        return DeliveryEntity(
            deliveryId = UUID.randomUUID().toString(),
            eventId = eventId,
            kind = if (item.kind == LegacyQkey.KIND_CHAIN) DeliveryKind.LEGACY_CHAIN else DeliveryKind.UNIVERSAL,
            state = state,
            legacyQkey = item.qkey,
            routeIdsJson = JSONArray(item.routeIds).toString(),
            nextRouteIndex = item.next,
            attempts = item.attempts,
            lastErrorSummary = lastErrorSummary ?: item.lastError?.take(200),
            lastMatchStatus = lastMatchStatus,
            lastApplyStatus = lastApplyStatus,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            ackedAtMs = if (state == DeliveryState.ACKED) nowMs else null,
            terminalAtMs = if (terminal) nowMs else null,
            routeStampsJson = "{}",
            hopOutcomesJson = item.hopOutcomesJson ?: "[]",
        )
    }
}

/** Legacy queue item -> Room event entity (design §8.1(3)). */
fun LegacyQueueItem.toEventEntity(nowMs: Long): EventEntity {
    val amount = if (payloadJson.has("amount")) payloadJson.optLong("amount") else null
    val dataJson = JSONObject().apply {
        if (amount != null) {
            put("amount", amount)
            put("currency", "IDR")
        }
    }.toString()
    val metadataJson = JSONObject().apply {
        put("parser_version", "legacy-import-v1")
        put("privacy_mode", "legacy")
    }.toString()
    val occurredAtMs = try {
        Instant.parse(payloadJson.optString("notif_time")).toEpochMilli()
    } catch (e: Exception) {
        lastTryMs ?: nowMs
    }
    return EventEntity(
        eventId = UUID.randomUUID().toString(),
        schemaVersion = 1,
        sourceType = if (type == "payment") "android_notification" else type,
        sourceEventId = dedupeKey,
        deviceId = payloadJson.optString("device"),
        eventType = if (type == "payment") "payment.notification" else type,
        category = type,
        sourcePackage = payloadJson.optString("pkg").ifEmpty { null },
        occurredAtMs = occurredAtMs,
        capturedAtMs = occurredAtMs,
        dataJson = dataJson,
        metadataJson = metadataJson,
        legacyPayloadJson = payloadJson.toString(),
        privacyMode = "legacy",
        retentionUntilMs = null,
        dedupeKey = dedupeKey,
        // Unknown for pre-v1.1 items: the full pre-redaction text no
        // longer exists, so no hash can be honestly computed.
        contentSha256 = null,
        // Fresh export identity; re-import preserves the first (F65).
        exportRef = UUID.randomUUID().toString(),
        sourceAccountKey = null,
    )
}
