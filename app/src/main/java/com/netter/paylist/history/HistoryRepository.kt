package com.netter.paylist.history

import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.dao.AttemptDao
import com.netter.paylist.data.dao.CaptureLogDao
import com.netter.paylist.data.dao.CollectorStateDao
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.data.dao.EventDao
import com.netter.paylist.data.entity.CaptureLogEntity
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.HopOutcome
import org.json.JSONObject

/**
 * History filters (addendum 06, LH-02). Every field is nullable
 * (null = no constraint). Applied in the Room read, never by
 * post-filtering an unfiltered dump.
 */
data class HistoryFilter(
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val minAmount: Long? = null,
    val maxAmount: Long? = null,
    val packageName: String? = null,
    val outcome: String? = null,
    val deliveryState: DeliveryState? = null,
    val routeId: String? = null,
    /** Server `last_match_status` (`matched` / `logged` / `ambiguous`). */
    val matchStatus: String? = null,
    /** Structured-field search only: package substring or exact amount. */
    val query: String? = null,
)

/**
 * One history row (LH-01). Carries NO internal identifiers:
 * no `dedupe_key`, no legacy `qkey`, no `content_sha256`, no
 * internal `event_id` (C22(q''), LH-03). `captureId` is the list
 * key for detail navigation and deletion only — it is never
 * rendered as text.
 */
data class HistoryItem(
    val captureId: String,
    val postTimeMs: Long,
    val sourcePackage: String,
    val outcome: String,
    val titleSnippet: String?,
    val amountMinor: Long?,
    /** Null when the notification never became an event. */
    val deliveryState: DeliveryState?,
    val lastMatchStatus: String?,
    val lastApplyStatus: String?,
    val hasEvent: Boolean,
    /** C1: the operator acknowledged this ambiguous case. */
    val ambiguousReviewed: Boolean = false,
)

/** One delivery, summarised for the detail view (LH-03). */
data class DeliverySummary(
    val state: DeliveryState,
    val lastMatchStatus: String?,
    val lastApplyStatus: String?,
    val lastErrorCode: String?,
    val lastErrorSummary: String?,
    val terminalAtMs: Long?,
    val hopOutcomes: List<HopOutcome>,
)

/** One attempt, summarised for the detail view (LH-03). */
data class AttemptSummary(
    val attemptNo: Int,
    val routeId: String?,
    val startedAtMs: Long,
    val httpStatus: Int?,
    val resultClass: String?,
    val errorCode: String?,
)

/** Per-item detail (LH-03). No identifiers, same rule as [HistoryItem]. */
data class HistoryDetail(
    val item: HistoryItem,
    val occurredAtMs: Long,
    val capturedAtMs: Long,
    val currency: String?,
    val category: String?,
    val deliveries: List<DeliverySummary>,
    val attempts: List<AttemptSummary>,
    /** NEEDS_REVIEW amount question / RAW_UNAVAILABLE raw-loss question. */
    val holdReason: String?,
    /** A COLLISION observation exists for this event (§3 R11). */
    val identityCollision: Boolean,
    /** C1: the operator acknowledged this ambiguous case. */
    val ambiguousReviewed: Boolean = false,
)

/**
 * The history read model (addendum 06, LH-01–LH-03, LH-07(e)).
 *
 * The list is built from `capture_log` (one row per observed
 * notification, LH-04) joined to the linked event + its current
 * delivery, so filtered-out notifications (NO_KEYWORD / NO_AMOUNT /
 * PURE_AUTH / UNLISTED_PACKAGE) are visible alongside captured
 * payments (LH-05: no silent loss).
 *
 * This class has NO export capability by construction (LH-07(c),
 * F55): it returns in-memory DTOs only, and the LH-T08 static check
 * forbids export APIs in the history code.
 */
class HistoryRepository(
    private val captureLogDao: CaptureLogDao,
    private val eventDao: EventDao,
    private val deliveryDao: DeliveryDao,
    private val attemptDao: AttemptDao,
    /**
     * C1 ambiguous-case accounting (null = no acknowledgement
     * store: every ambiguous case counts as unreviewed).
     */
    private val collectorStateDao: CollectorStateDao? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** Default page size (LH-08 suggests 50). */
        const val PAGE_SIZE: Int = 50
    }

    /** One legacy-path ambiguous case (collector doc). */
    private data class LegacyAmbiguous(
        val qkey: String,
        val dedupeKey: String,
        val amountMinor: Long?,
        val sourcePackage: String?,
    )

    /** The acknowledgement sets, loaded once per read. */
    private data class ReviewedSets(
        val reviewedDeliveryIds: Set<String> = emptySet(),
        val legacyByDedupe: Map<String, LegacyAmbiguous> = emptyMap(),
        val reviewedLegacyQkeys: Set<String> = emptySet(),
    )

    private suspend fun reviewedSets(): ReviewedSets {
        val dao = collectorStateDao ?: return ReviewedSets()
        val docs = dao.getAll()
        val reviewedDeliveries = mutableSetOf<String>()
        val legacyByDedupe = mutableMapOf<String, LegacyAmbiguous>()
        val reviewedLegacy = mutableSetOf<String>()
        for (doc in docs) {
            when {
                doc.key.startsWith(CollectorStateKeys.AMBIGUOUS_REVIEWED_PREFIX) ->
                    reviewedDeliveries += doc.key.removePrefix(CollectorStateKeys.AMBIGUOUS_REVIEWED_PREFIX)
                doc.key.startsWith(CollectorStateKeys.AMBIGUOUS_LEGACY_REVIEWED_PREFIX) ->
                    reviewedLegacy += doc.key.removePrefix(CollectorStateKeys.AMBIGUOUS_LEGACY_REVIEWED_PREFIX)
                doc.key.startsWith(CollectorStateKeys.AMBIGUOUS_LEGACY_PREFIX) -> {
                    val qkey = doc.key.removePrefix(CollectorStateKeys.AMBIGUOUS_LEGACY_PREFIX)
                    val json = try {
                        JSONObject(doc.valueJson)
                    } catch (e: Exception) {
                        JSONObject()
                    }
                    val dedupe = json.optString("dedupe_key")
                    if (dedupe.isNotEmpty()) {
                        legacyByDedupe[dedupe] = LegacyAmbiguous(
                            qkey = qkey,
                            dedupeKey = dedupe,
                            amountMinor = json.optLong("amount").takeIf { json.has("amount") },
                            sourcePackage = json.optString("package").takeIf { it.isNotEmpty() },
                        )
                    }
                }
            }
        }
        return ReviewedSets(reviewedDeliveries, legacyByDedupe, reviewedLegacy)
    }

    /** One page of history, most recent first (LH-01). */
    suspend fun listPage(
        filter: HistoryFilter = HistoryFilter(),
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): List<HistoryItem> {
        val rows = captureLogDao.listHistory(
            fromMs = filter.fromMs,
            toMs = filter.toMs,
            minAmount = filter.minAmount,
            maxAmount = filter.maxAmount,
            packageName = filter.packageName,
            outcome = filter.outcome,
            state = filter.deliveryState,
            routeId = filter.routeId,
            query = filter.query?.takeIf { it.isNotBlank() },
            limit = limit,
            offset = offset,
            matchStatus = filter.matchStatus,
        )
        val reviewed = reviewedSets()
        return rows.map { toItem(it, reviewed = reviewed) }
    }

    /** Row count for the same filter set (paging). */
    suspend fun count(filter: HistoryFilter = HistoryFilter()): Int =
        captureLogDao.countHistory(
            fromMs = filter.fromMs,
            toMs = filter.toMs,
            minAmount = filter.minAmount,
            maxAmount = filter.maxAmount,
            packageName = filter.packageName,
            outcome = filter.outcome,
            state = filter.deliveryState,
            routeId = filter.routeId,
            query = filter.query?.takeIf { it.isNotBlank() },
            matchStatus = filter.matchStatus,
        )

    /** Distinct posting packages (filter dropdown). */
    suspend fun distinctPackages(): List<String> = captureLogDao.distinctPackages()

    /** Per-item detail (LH-03), or null when the row is gone. */
    suspend fun getDetail(captureId: String): HistoryDetail? {
        val row = captureLogDao.getById(captureId) ?: return null
        val reviewed = reviewedSets()
        val item = toItem(row, reviewed = reviewed)
        val eventId = row.eventId
        if (eventId == null) {
            return HistoryDetail(
                item = item,
                occurredAtMs = row.postTimeMs,
                capturedAtMs = row.capturedAtMs,
                currency = null,
                category = null,
                deliveries = emptyList(),
                attempts = emptyList(),
                holdReason = null,
                identityCollision = false,
                ambiguousReviewed = item.ambiguousReviewed,
            )
        }
        val event = eventDao.getById(eventId)
            ?: return HistoryDetail(
                item = item,
                occurredAtMs = row.postTimeMs,
                capturedAtMs = row.capturedAtMs,
                currency = null,
                category = null,
                deliveries = emptyList(),
                attempts = emptyList(),
                holdReason = null,
                identityCollision = false,
                ambiguousReviewed = item.ambiguousReviewed,
            )
        val deliveries = deliveryDao.getByEventId(eventId)
        val summaries = deliveries.map { d ->
            DeliverySummary(
                state = d.state,
                lastMatchStatus = d.lastMatchStatus,
                lastApplyStatus = d.lastApplyStatus,
                lastErrorCode = d.lastErrorCode,
                lastErrorSummary = d.lastErrorSummary,
                terminalAtMs = d.terminalAtMs,
                hopOutcomes = HopOutcome.fromJsonArray(d.hopOutcomesJson),
            )
        }
        val attempts = deliveries.flatMap { d ->
            attemptDao.getByDeliveryId(d.deliveryId)
        }.sortedBy { it.startedAtMs }.map { a ->
            AttemptSummary(
                attemptNo = a.attemptNo,
                routeId = a.routeId,
                startedAtMs = a.startedAtMs,
                httpStatus = a.httpStatus,
                resultClass = a.resultClass,
                errorCode = a.errorCode,
            )
        }
        val data = try {
            JSONObject(event.dataJson)
        } catch (e: Exception) {
            JSONObject()
        }
        val holdReason = when (item.deliveryState) {
            DeliveryState.NEEDS_REVIEW -> "Jumlah ambigu — operator harus memilih jumlah yang benar."
            DeliveryState.RAW_UNAVAILABLE -> "Teks asli hilang — operator harus memutuskan bentuk kirim."
            DeliveryState.HELD_AWAITING_LEGACY_SWEEP -> "Menunggu sapuan salinan legacy."
            DeliveryState.HELD_AWAITING_RECONCILIATION -> "Menunggu rekonsiliasi hasil kirim."
            else -> null
        }
        val collision = captureLogDao.countCollisionsFor(
            row.sourcePackage, row.postTimeMs, row.amountMinor,
        ) > 0
        return HistoryDetail(
            item = item,
            occurredAtMs = event.occurredAtMs,
            capturedAtMs = event.capturedAtMs,
            currency = data.optString("currency").takeIf { it.isNotEmpty() },
            category = event.category,
            deliveries = summaries,
            attempts = attempts,
            holdReason = holdReason,
            identityCollision = collision,
            ambiguousReviewed = item.ambiguousReviewed,
        )
    }

    /**
     * The §8.8 operator-reconciliation audit set (LH-T07):
     * deliveries with `last_apply_status` in {`skip`, `error`} or
     * ACKED with no apply status — returned as history items via
     * their linked capture rows (a delivery with no capture row
     * cannot exist: every Room delivery comes from a capture).
     */
    suspend fun auditSet(): List<HistoryItem> {
        val deliveries = deliveryDao.getAuditSet()
        val reviewed = reviewedSets()
        val items = mutableListOf<HistoryItem>()
        for (delivery in deliveries) {
            val rows = captureLogDao.listByEventId(delivery.eventId)
            val row = rows.firstOrNull() ?: continue
            items += toItem(row, delivery, reviewed)
        }
        return items.sortedByDescending { it.postTimeMs }
    }

    /** Per-record local deletion of a capture row (LH-07(e)). */
    suspend fun deleteCaptureRow(captureId: String): Boolean =
        captureLogDao.deleteById(captureId) > 0

    /**
     * Per-record local deletion of the event behind a capture row
     * (LH-07(e)): explicit operator policy only, never automatic,
     * never bulk. Returns false when the row is unlinked. The
     * capture row itself is kept (its link is SET NULL) as an
     * unlinked diagnostic row under the 90-day rule.
     */
    suspend fun deleteEvent(captureId: String): Boolean {
        val row = captureLogDao.getById(captureId) ?: return false
        val eventId = row.eventId ?: return false
        return eventDao.deleteById(eventId) > 0
    }

    /** Per-category local deletion (LH-07(e)): unlinked rows of one outcome. */
    suspend fun deleteUnlinkedByOutcome(outcome: String): Int =
        captureLogDao.deleteUnlinkedByOutcome(outcome)

    // --- C1 ambiguous-case accounting (user decision 2026-11-23) ---

    /**
     * The badge count: ambiguous cases the operator has not yet
     * acknowledged — Room deliveries with
     * `last_match_status = 'ambiguous'` plus legacy-path cases
     * recorded in collector docs, each minus its acknowledgement.
     *
     * The badge clears on LOCAL acknowledgement (the operator marks
     * the case handled after acting on it, e.g. via the server
     * rematch). It does NOT clear automatically when the server
     * rematch happens — there is no server-to-app push, and no
     * endpoint exists to poll; that would be C2 server work.
     */
    suspend fun ambiguousCount(): Int {
        val reviewed = reviewedSets()
        val roomIds = deliveryDao.getAmbiguousDeliveryIds()
        val roomUnreviewed = roomIds.count { it !in reviewed.reviewedDeliveryIds }
        val legacyUnreviewed = reviewed.legacyByDedupe.values.count {
            it.qkey !in reviewed.reviewedLegacyQkeys
        }
        return roomUnreviewed + legacyUnreviewed
    }

    /**
     * Amount + package for one Room ambiguous case, for the alert
     * text (resolved via the linked capture row). Null when the
     * delivery or its capture row is gone.
     */
    suspend fun ambiguousCaseInfo(deliveryId: String): Pair<Long?, String?>? {
        val delivery = deliveryDao.getById(deliveryId) ?: return null
        val row = captureLogDao.listByEventId(delivery.eventId).firstOrNull() ?: return null
        return row.amountMinor to row.sourcePackage
    }

    /**
     * Records a legacy-path ambiguous case (called from the
     * sender's `onAmbiguous` hook). First record wins — a re-POST
     * of the same queue item does not overwrite the original time.
     */
    suspend fun recordLegacyAmbiguous(
        qkey: String,
        dedupeKey: String,
        amountMinor: Long?,
        sourcePackage: String?,
    ) {
        val dao = collectorStateDao ?: return
        val key = CollectorStateKeys.AMBIGUOUS_LEGACY_PREFIX + qkey
        if (dao.get(key) != null) return
        val json = JSONObject().apply {
            put("dedupe_key", dedupeKey)
            if (amountMinor != null) put("amount", amountMinor)
            if (sourcePackage != null) put("package", sourcePackage)
            put("at_ms", clock())
        }
        dao.put(CollectorStateEntity(key, json.toString(), clock()))
    }

    /**
     * Marks the ambiguous case behind a capture row as handled by
     * the operator (C1 acknowledgement). Covers both paths: the
     * current Room delivery (when ambiguous) and any legacy record
     * for the same event. Returns true when anything was marked.
     */
    suspend fun markAmbiguousReviewed(captureId: String, operator: String): Boolean {
        val dao = collectorStateDao ?: return false
        val row = captureLogDao.getById(captureId) ?: return false
        val eventId = row.eventId ?: return false
        val event = eventDao.getById(eventId) ?: return false
        val reviewed = reviewedSets()
        var marked = false
        val current = currentDelivery(eventId)
        if (current != null && current.lastMatchStatus == "ambiguous" &&
            current.deliveryId !in reviewed.reviewedDeliveryIds
        ) {
            val key = CollectorStateKeys.AMBIGUOUS_REVIEWED_PREFIX + current.deliveryId
            dao.put(
                CollectorStateEntity(
                    key,
                    JSONObject().apply {
                        put("operator", operator)
                        put("reviewed_at_ms", clock())
                    }.toString(),
                    clock(),
                ),
            )
            marked = true
        }
        val legacy = reviewed.legacyByDedupe[event.dedupeKey]
        if (legacy != null && legacy.qkey !in reviewed.reviewedLegacyQkeys) {
            val key = CollectorStateKeys.AMBIGUOUS_LEGACY_REVIEWED_PREFIX + legacy.qkey
            dao.put(
                CollectorStateEntity(
                    key,
                    JSONObject().apply {
                        put("operator", operator)
                        put("reviewed_at_ms", clock())
                    }.toString(),
                    clock(),
                ),
            )
            marked = true
        }
        return marked
    }

    private suspend fun toItem(
        row: CaptureLogEntity,
        delivery: DeliveryEntity? = null,
        reviewed: ReviewedSets = ReviewedSets(),
    ): HistoryItem {
        val current = delivery ?: row.eventId?.let { currentDelivery(it) }
        var matchStatus = current?.lastMatchStatus
        var applyStatus = current?.lastApplyStatus
        var isReviewed = current != null && current.deliveryId in reviewed.reviewedDeliveryIds
        if (current == null && row.eventId != null) {
            // No Room delivery (legacy-path send): the ambiguous
            // state comes from the legacy collector record, linked
            // by the event's dedupe key.
            val event = eventDao.getById(row.eventId)
            val legacy = event?.let { reviewed.legacyByDedupe[it.dedupeKey] }
            if (legacy != null) {
                matchStatus = "ambiguous"
                applyStatus = null
                isReviewed = legacy.qkey in reviewed.reviewedLegacyQkeys
            }
        }
        return HistoryItem(
            captureId = row.captureId,
            postTimeMs = row.postTimeMs,
            sourcePackage = row.sourcePackage,
            outcome = row.outcome,
            titleSnippet = row.titleSnippet,
            amountMinor = row.amountMinor,
            deliveryState = current?.state,
            lastMatchStatus = matchStatus,
            lastApplyStatus = applyStatus,
            hasEvent = row.eventId != null,
            ambiguousReviewed = isReviewed,
        )
    }

    /**
     * The current delivery for an event: the live (non-terminal)
     * one when one exists, else the most recently updated terminal
     * one. Superseded DISCARDED rows never win over a live row.
     */
    private suspend fun currentDelivery(eventId: String): DeliveryEntity? {
        val deliveries = deliveryDao.getByEventId(eventId)
        if (deliveries.isEmpty()) return null
        val terminal = setOf(
            DeliveryState.ACKED, DeliveryState.EXPIRED, DeliveryState.DISCARDED,
        )
        return deliveries
            .sortedWith(
                compareBy<DeliveryEntity> { it.state in terminal }
                    .thenByDescending { it.updatedAtMs },
            )
            .firstOrNull()
    }
}
