package com.netter.paylist.collector

import com.netter.paylist.Route
import com.netter.paylist.Store
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.core.model.DeliveryStateMachine
import com.netter.paylist.core.model.RouteStamp
import com.netter.paylist.data.RouteStampMap
import com.netter.paylist.data.TransactionRunner
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.delivery.RouteSource
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The slice of [Store] the lifecycle sweep needs: the legacy
 * queue (hold/release) + route delete/upsert. A narrow
 * interface so the sweep is unit-testable without a Context;
 * production wraps the real [Store].
 */
interface RouteQueueStore {
    fun queue(): List<JSONObject>
    fun saveQueueSync(items: List<JSONObject>): Boolean
    fun deleteRoute(id: String)
    fun upsertRoute(route: Route)
}

/** Production [RouteQueueStore] backed by the real [Store]. */
class StoreRouteQueueStore(private val store: Store) : RouteQueueStore {
    override fun queue(): List<JSONObject> = store.queue()
    override fun saveQueueSync(items: List<JSONObject>): Boolean = store.saveQueueSync(items)
    override fun deleteRoute(id: String) = store.deleteRoute(id)
    override fun upsertRoute(route: Route) = store.upsertRoute(route)
}

/**
 * Route lifecycle sweeps (D5, F15/F16/F21): what happens to
 * pending work when a route is revoked, deleted, disabled, or
 * reconfigured — and the explicit reactivation that returns it
 * to PENDING.
 *
 * - Revoke → no new capture (the coordinator consent gate),
 *   PENDING Room deliveries BLOCKED (`consent_revoked`),
 *   legacy queue items held (never sent, never deleted),
 *   raw snapshots dropped + admission fence bumped.
 * - Delete → the same sweep with `route_deleted`; the consent
 *   record is kept for audit. No auto-revival on recreate.
 * - Disable → the same sweep with `route_disabled`.
 * - URL change → PENDING deliveries BLOCKED (`url_changed`);
 *   re-approval re-stamps the URL snapshot.
 * - Reactivation is an explicit operator action only: it
 *   requires the route to exist, be enabled, and have current
 *   consent; it re-stamps the delivery (current consent epoch +
 *   current URL hash) and returns it to PENDING via
 *   `retryManual` (attempts reset).
 *
 * IN_FLIGHT deliveries are never swept: the in-flight attempt
 * completes, and the claim-time stamp check blocks any further
 * attempt. NEEDS_REVIEW / RAW_UNAVAILABLE / HELD deliveries are
 * already held for review and are left alone.
 *
 * Error codes match the claim-time stamp check's vocabulary
 * (`consent_revoked`, `url_changed`) so one vocabulary covers
 * both the eager sweep and the lazy claim-time block.
 */
class RouteLifecycle(
    private val deliveryDao: DeliveryDao,
    private val transactions: TransactionRunner,
    private val consentStore: ConsentStore,
    private val routes: RouteSource,
    private val queueStore: RouteQueueStore,
    private val rawSnapshotStore: RawSnapshotStore? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val ERROR_CONSENT_REVOKED: String = "consent_revoked"
        const val ERROR_ROUTE_DELETED: String = "route_deleted"
        const val ERROR_ROUTE_DISABLED: String = "route_disabled"
        const val ERROR_URL_CHANGED: String = "url_changed"

        /** Error codes the lifecycle sweep writes (reactivatable). */
        val LIFECYCLE_ERRORS: Set<String> = setOf(
            ERROR_CONSENT_REVOKED, ERROR_ROUTE_DELETED,
            ERROR_ROUTE_DISABLED, ERROR_URL_CHANGED,
        )

        /** Legacy queue item key: routes holding this item (JSON array of ids). */
        const val HELD_ROUTES_KEY: String = "held_routes"

        /** Legacy queue item key: the latest hold reason (display only). */
        const val HELD_REASON_KEY: String = "held_reason"

        /** Collector doc key for the one-time upgrade migration marker. */
        const val UPGRADE_MIGRATION_KEY: String = "consent_upgrade_migrated"
    }

    data class SweepResult(
        val blockedDeliveries: Int,
        val heldQueueItems: Int,
    )

    data class ReactivateResult(
        val reactivatedDeliveries: Int,
        val releasedQueueItems: Int,
        /** Non-empty when the reactivation was refused. */
        val refusedReason: String? = null,
    )

    /**
     * One-time upgrade migration (D5 judgment call, needs owner
     * confirmation): routes that already exist from v2.3 carry
     * their working consent forward — the v2.3 operator already
     * runs the listener, so the upgrade must not silently stop
     * billing capture. Fresh installs get no grant until the
     * operator adds a route and completes consent in
     * RouteEditActivity. Returns the number of routes granted.
     *
     * The caller guards this with the [UPGRADE_MIGRATION_KEY]
     * marker so it runs once.
     */
    suspend fun migrateUpgradeConsent(routeIds: List<String>): Int {
        var granted = 0
        for (routeId in routeIds) {
            if (consentStore.get(routeId) == null) {
                consentStore.grant(routeId)
                granted++
            }
        }
        return granted
    }

    /** Revokes a route's consent and sweeps its pending work. */
    suspend fun revokeRoute(routeId: String): SweepResult {
        consentStore.revoke(routeId)
        rawSnapshotStore?.dropRoute(routeId)
        return sweepRoute(routeId, ERROR_CONSENT_REVOKED, "persetujuan rute dicabut")
    }

    /** Deletes a route (Store) and sweeps its pending work. */
    suspend fun deleteRoute(routeId: String): SweepResult {
        queueStore.deleteRoute(routeId)
        rawSnapshotStore?.dropRoute(routeId)
        return sweepRoute(routeId, ERROR_ROUTE_DELETED, "rute dihapus")
    }

    /** Disables a route and sweeps its pending work. */
    suspend fun disableRoute(routeId: String): SweepResult {
        val route = routes.routeById(routeId) ?: return SweepResult(0, 0)
        queueStore.upsertRoute(route.copy(enabled = false))
        return sweepRoute(routeId, ERROR_ROUTE_DISABLED, "rute dinonaktifkan")
    }

    /** Enables a route WITHOUT reviving blocked work (explicit reactivation only). */
    suspend fun enableRoute(routeId: String) {
        val route = routes.routeById(routeId) ?: return
        queueStore.upsertRoute(route.copy(enabled = true))
    }

    /** Blocks pending work after a URL change (re-approval re-stamps). */
    suspend fun urlChanged(routeId: String): SweepResult {
        return sweepRoute(routeId, ERROR_URL_CHANGED, "URL rute berubah")
    }

    /**
     * Explicit reactivation (F21): returns lifecycle-blocked
     * deliveries to PENDING and releases held legacy items.
     * Requires the route to exist, be enabled, and have current
     * consent — otherwise refused with a reason. Re-stamps each
     * delivery with the current consent epoch + URL hash so the
     * claim-time check passes.
     */
    suspend fun reactivateRoute(routeId: String): ReactivateResult {
        val route = routes.routeById(routeId)
            ?: return ReactivateResult(0, 0, "rute tidak ditemukan")
        if (!route.enabled) {
            return ReactivateResult(0, 0, "rute masih dinonaktifkan")
        }
        val consent = consentStore.get(routeId)
        if (consent == null || !consent.granted) {
            return ReactivateResult(0, 0, "persetujuan rute belum diberikan")
        }
        val now = clock()
        val epoch = consent.epoch
        val urlSha = sha256Hex(route.url)
        var reactivated = 0
        val blocked = deliveryDao.getByState(DeliveryState.BLOCKED)
        for (delivery in blocked) {
            if (delivery.lastErrorCode !in LIFECYCLE_ERRORS) continue
            if (!targetsRoute(delivery.routeIdsJson, routeId)) continue
            if (!DeliveryStateMachine.canTransition(DeliveryState.BLOCKED, DeliveryState.PENDING)) {
                continue
            }
            transactions.run {
                val stamps = RouteStampMap.fromJsonString(delivery.routeStampsJson).toMutableMap()
                val old = stamps[routeId]
                stamps[routeId] = RouteStamp(
                    consentEpoch = epoch,
                    configEpoch = old?.configEpoch ?: 0L,
                    urlSha256 = urlSha,
                )
                deliveryDao.updateRouteStamps(
                    delivery.deliveryId,
                    RouteStampMap.toJsonString(stamps),
                    now,
                )
                if (deliveryDao.retryManual(delivery.deliveryId, now) == 1) reactivated++
            }
        }
        val released = releaseHeldQueueItems(routeId)
        return ReactivateResult(reactivated, released)
    }

    /**
     * Blocks PENDING deliveries for one route + holds legacy
     * queue items targeting it. Idempotent: re-running a sweep
     * finds nothing left to block/hold.
     */
    suspend fun sweepRoute(routeId: String, errorCode: String, summary: String): SweepResult {
        val now = clock()
        var blocked = 0
        val pending = deliveryDao.getByState(DeliveryState.PENDING)
        for (delivery in pending) {
            if (!targetsRoute(delivery.routeIdsJson, routeId)) continue
            if (!DeliveryStateMachine.canTransition(DeliveryState.PENDING, DeliveryState.BLOCKED)) {
                continue
            }
            if (deliveryDao.blockPending(delivery.deliveryId, errorCode, summary, now) == 1) {
                blocked++
            }
        }
        val held = holdQueueItems(routeId, errorCode)
        return SweepResult(blocked, held)
    }

    /** Adds `routeId` to the held-routes set of queue items targeting it. */
    private fun holdQueueItems(routeId: String, reason: String): Int {
        val queue = queueStore.queue()
        var held = 0
        val updated = queue.map { item ->
            val ids = Route.jsonStrings(item.optJSONArray("route_ids"))
            if (routeId !in ids) return@map item
            val heldRoutes = heldRoutesOf(item).toMutableSet()
            if (heldRoutes.add(routeId)) held++
            item.put(HELD_ROUTES_KEY, JSONArray(heldRoutes.toList()))
            item.put(HELD_REASON_KEY, reason)
            item
        }
        if (held > 0) queueStore.saveQueueSync(updated)
        return held
    }

    /** Removes `routeId` from the held-routes set; items with an empty set resume. */
    private fun releaseHeldQueueItems(routeId: String): Int {
        val queue = queueStore.queue()
        var released = 0
        val updated = queue.map { item ->
            val heldRoutes = heldRoutesOf(item).toMutableSet()
            if (!heldRoutes.remove(routeId)) return@map item
            if (heldRoutes.isEmpty()) {
                item.remove(HELD_ROUTES_KEY)
                item.remove(HELD_REASON_KEY)
            } else {
                item.put(HELD_ROUTES_KEY, JSONArray(heldRoutes.toList()))
            }
            released++
            item
        }
        if (released > 0) queueStore.saveQueueSync(updated)
        return released
    }

    private fun heldRoutesOf(item: JSONObject): Set<String> {
        return try {
            val arr = item.optJSONArray(HELD_ROUTES_KEY) ?: return emptySet()
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun targetsRoute(routeIdsJson: String, routeId: String): Boolean {
        return try {
            val arr = JSONArray(routeIdsJson)
            (0 until arr.length()).any { arr.getString(it) == routeId }
        } catch (e: Exception) {
            false
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
