package com.netter.paylist

import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.dao.DeliveryDao
import org.json.JSONObject

/**
 * Read-only diagnostics aggregates for the D5 Diagnostics screen
 * (plan §D5): HELD counts per route, held legacy-queue items, and
 * the unreviewed ambiguous count.
 *
 * A delivery counts toward every route in its route set — a
 * two-route delivery held for review is held for both routes.
 * The legacy queue snapshot is supplied by the caller (the
 * repository never touches [Store] directly, so it stays unit
 * testable).
 */
class DiagnosticsRepository(
    private val deliveryDao: DeliveryDao,
    private val queueSnapshot: () -> List<JSONObject> = { emptyList() },
    private val ambiguousCount: suspend () -> Int = { 0 },
) {
    data class HeldCounts(
        val needsReview: Int,
        val rawUnavailable: Int,
        val heldAwaitingLegacySweep: Int,
        val heldAwaitingReconciliation: Int,
        val blocked: Int,
        /** routeId → held deliveries targeting that route. */
        val perRoute: Map<String, Int>,
        /** Legacy queue items held by a route lifecycle action. */
        val legacyQueueHeld: Int,
        val ambiguousUnreviewed: Int,
    ) {
        val total: Int
            get() = needsReview + rawUnavailable + heldAwaitingLegacySweep +
                heldAwaitingReconciliation + blocked
    }

    suspend fun heldCounts(): HeldCounts {
        val byState = mutableMapOf<DeliveryState, List<com.netter.paylist.data.entity.DeliveryEntity>>()
        for (state in HELD_STATES) {
            byState[state] = deliveryDao.getByState(state)
        }
        val perRoute = mutableMapOf<String, Int>()
        for (deliveries in byState.values) {
            for (delivery in deliveries) {
                for (routeId in Route.jsonStrings(
                    org.json.JSONArray(delivery.routeIdsJson)
                )) {
                    perRoute[routeId] = (perRoute[routeId] ?: 0) + 1
                }
            }
        }
        var legacyHeld = 0
        for (item in queueSnapshot()) {
            val held = item.optJSONArray(
                com.netter.paylist.collector.RouteLifecycle.HELD_ROUTES_KEY
            )
            if (held != null && held.length() > 0) legacyHeld++
        }
        return HeldCounts(
            needsReview = byState[DeliveryState.NEEDS_REVIEW]?.size ?: 0,
            rawUnavailable = byState[DeliveryState.RAW_UNAVAILABLE]?.size ?: 0,
            heldAwaitingLegacySweep =
                byState[DeliveryState.HELD_AWAITING_LEGACY_SWEEP]?.size ?: 0,
            heldAwaitingReconciliation =
                byState[DeliveryState.HELD_AWAITING_RECONCILIATION]?.size ?: 0,
            blocked = byState[DeliveryState.BLOCKED]?.size ?: 0,
            perRoute = perRoute,
            legacyQueueHeld = legacyHeld,
            ambiguousUnreviewed = ambiguousCount(),
        )
    }

    companion object {
        val HELD_STATES = listOf(
            DeliveryState.NEEDS_REVIEW,
            DeliveryState.RAW_UNAVAILABLE,
            DeliveryState.HELD_AWAITING_LEGACY_SWEEP,
            DeliveryState.HELD_AWAITING_RECONCILIATION,
            DeliveryState.BLOCKED,
        )
    }
}
