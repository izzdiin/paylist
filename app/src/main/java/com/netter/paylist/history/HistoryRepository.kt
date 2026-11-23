package com.netter.paylist.history

import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.dao.AttemptDao
import com.netter.paylist.data.dao.CaptureLogDao
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.data.dao.EventDao
import com.netter.paylist.data.entity.CaptureLogEntity
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
) {
    companion object {
        /** Default page size (LH-08 suggests 50). */
        const val PAGE_SIZE: Int = 50
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
        return rows.map { toItem(it) }
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
        val item = toItem(row)
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
        val items = mutableListOf<HistoryItem>()
        for (delivery in deliveries) {
            val rows = captureLogDao.listByEventId(delivery.eventId)
            val row = rows.firstOrNull() ?: continue
            items += toItem(row, delivery)
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

    private suspend fun toItem(
        row: CaptureLogEntity,
        delivery: DeliveryEntity? = null,
    ): HistoryItem {
        val current = delivery ?: row.eventId?.let { currentDelivery(it) }
        return HistoryItem(
            captureId = row.captureId,
            postTimeMs = row.postTimeMs,
            sourcePackage = row.sourcePackage,
            outcome = row.outcome,
            titleSnippet = row.titleSnippet,
            amountMinor = row.amountMinor,
            deliveryState = current?.state,
            lastMatchStatus = current?.lastMatchStatus,
            lastApplyStatus = current?.lastApplyStatus,
            hasEvent = row.eventId != null,
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
