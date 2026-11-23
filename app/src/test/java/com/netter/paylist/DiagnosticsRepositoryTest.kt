package com.netter.paylist

import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.DeliveryEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D5 diagnostics aggregates: HELD counts per state and per route,
 * held legacy-queue items, ambiguous count passthrough.
 */
class DiagnosticsRepositoryTest {

    private fun delivery(
        id: String,
        state: DeliveryState,
        routeIds: List<String>,
    ) = DeliveryEntity(
        deliveryId = id,
        eventId = "ev-$id",
        kind = DeliveryKind.LEGACY_CHAIN,
        state = state,
        legacyQkey = null,
        routeIdsJson = JSONArray(routeIds).toString(),
        nextRouteIndex = 0,
        attempts = 0,
        nextRetryAtMs = null,
        leaseOwner = null,
        leaseUntilMs = null,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
        routeStampsJson = "{}",
        terminalAtMs = null,
    )

    @Test
    fun heldCounts_groupsByStateAndRoute() = runBlocking {
        val store = FakeStore()
        val dao = FakeDeliveryDao(store)
        dao.insert(delivery("d1", DeliveryState.NEEDS_REVIEW, listOf("r1")))
        dao.insert(delivery("d2", DeliveryState.RAW_UNAVAILABLE, listOf("r1", "r2")))
        dao.insert(delivery("d3", DeliveryState.BLOCKED, listOf("r2")))
        dao.insert(delivery("d4", DeliveryState.HELD_AWAITING_LEGACY_SWEEP, listOf("r1")))
        dao.insert(delivery("d5", DeliveryState.HELD_AWAITING_RECONCILIATION, listOf("r2")))
        dao.insert(delivery("d6", DeliveryState.PENDING, listOf("r1")))
        dao.insert(delivery("d7", DeliveryState.ACKED, listOf("r1")))
        val queue = listOf(
            JSONObject().put("qkey", "q1").put(
                "held_routes", JSONArray().put("r1"),
            ),
            JSONObject().put("qkey", "q2"),
            JSONObject().put("qkey", "q3").put(
                "held_routes", JSONArray().put("r1").put("r2"),
            ),
        )
        val repo = DiagnosticsRepository(
            deliveryDao = dao,
            queueSnapshot = { queue },
            ambiguousCount = { 3 },
        )

        val counts = repo.heldCounts()

        assertEquals(1, counts.needsReview)
        assertEquals(1, counts.rawUnavailable)
        assertEquals(1, counts.heldAwaitingLegacySweep)
        assertEquals(1, counts.heldAwaitingReconciliation)
        assertEquals(1, counts.blocked)
        assertEquals(5, counts.total)
        // d2 targets two routes: counted for both.
        assertEquals(3, counts.perRoute["r1"])
        assertEquals(3, counts.perRoute["r2"])
        assertEquals(2, counts.legacyQueueHeld)
        assertEquals(3, counts.ambiguousUnreviewed)
    }

    @Test
    fun heldCounts_emptyWhenNothingHeld() = runBlocking {
        val store = FakeStore()
        val dao = FakeDeliveryDao(store)
        dao.insert(delivery("d1", DeliveryState.PENDING, listOf("r1")))
        val repo = DiagnosticsRepository(deliveryDao = dao)

        val counts = repo.heldCounts()

        assertEquals(0, counts.total)
        assertTrue(counts.perRoute.isEmpty())
        assertEquals(0, counts.legacyQueueHeld)
        assertEquals(0, counts.ambiguousUnreviewed)
    }
}
