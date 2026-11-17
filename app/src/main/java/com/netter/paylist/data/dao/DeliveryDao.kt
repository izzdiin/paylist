package com.netter.paylist.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.data.entity.DeliveryEntity
import org.json.JSONObject
import java.util.UUID

/** One row of `countByState`: a delivery state and its row count. */
data class DeliveryStateCount(
    val state: DeliveryState,
    val count: Int,
)

/**
 * DAO for [DeliveryEntity] (design §4.2, §5): atomic claim/transition
 * and queue queries.
 *
 * Claim rules (file plan §C; design §5.1, §5.2, §8.3(4), C22(l)/(m)):
 * - The claim transaction reads the mode flag from `collector_state`
 *   in the same transaction and claims zero rows unless the mode is
 *   `WORKER_ONLY`.
 * - The claim joins the consent check: a delivery whose route stamp
 *   disagrees with the current `route_consents` (revoked route, or a
 *   consent epoch newer than the stamp) is not claimed. Routes with
 *   no consent record (legacy imports) pass — there is no revocation
 *   evidence for them. The config-epoch / URL re-check against the
 *   live route config is the Stage C worker's job (the route config
 *   lives in SharedPreferences, outside Room).
 * - The claim takes `PENDING` or due `RETRY_WAIT` rows, FIFO by
 *   `created_at_ms`, in one transaction (§5.2: no separate
 *   state-flip step).
 *
 * Lease rules (§5.1, C7): lease 5 min, no renewal. Each claim sets a
 * fresh `lease_owner` UUID per delivery and `lease_until_ms`. Every
 * worker state transition is conditional on the stored `lease_owner`
 * still matching the worker's token AND the lease being unexpired —
 * a single conditional UPDATE. A worker that finds its lease lost or
 * expired must discard its in-flight result and must not write any
 * state for that delivery.
 */
@Dao
interface DeliveryDao {

    /** Insert; returns 0 when a delivery with this `legacy_qkey` already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(delivery: DeliveryEntity): Long

    @Query("SELECT * FROM deliveries WHERE delivery_id = :id")
    suspend fun getById(id: String): DeliveryEntity?

    @Query("SELECT * FROM deliveries WHERE legacy_qkey = :qkey")
    suspend fun getByLegacyQkey(qkey: String): DeliveryEntity?

    @Query("SELECT * FROM deliveries WHERE event_id = :eventId")
    suspend fun getByEventId(eventId: String): List<DeliveryEntity>

    @Query("SELECT * FROM deliveries WHERE state = :state")
    suspend fun getByState(state: DeliveryState): List<DeliveryEntity>

    @Query("SELECT state, COUNT(*) AS count FROM deliveries GROUP BY state")
    suspend fun countByState(): List<DeliveryStateCount>

    @Query(
        "SELECT COUNT(*) FROM deliveries WHERE state IN ('PENDING', 'RETRY_WAIT') " +
            "AND (next_retry_at_ms IS NULL OR next_retry_at_ms <= :now)",
    )
    suspend fun countDue(now: Long): Int

    /** Deliveries created at/after [sinceMs] that were claimed or resolved (F12 commit check). */
    @Query(
        "SELECT * FROM deliveries WHERE created_at_ms >= :sinceMs " +
            "AND state IN ('IN_FLIGHT', 'ACKED', 'RETRY_WAIT')",
    )
    suspend fun getClaimedSince(sinceMs: Long): List<DeliveryEntity>

    @Query("SELECT * FROM deliveries WHERE state IN ('BLOCKED', 'RETRY_WAIT') ORDER BY updated_at_ms DESC LIMIT :limit")
    suspend fun getRecentProblematic(limit: Int): List<DeliveryEntity>

    // --- Claim (one transaction: mode gate + consent join + lease) ---

    @Query("SELECT value_json FROM collector_state WHERE key = :key")
    suspend fun readCollectorValue(key: String): String?

    @Query(
        "SELECT * FROM deliveries WHERE state IN ('PENDING', 'RETRY_WAIT') " +
            "AND (next_retry_at_ms IS NULL OR next_retry_at_ms <= :now) " +
            "ORDER BY created_at_ms ASC LIMIT :limit",
    )
    suspend fun selectDue(limit: Int, now: Long): List<DeliveryEntity>

    @Query(
        "UPDATE deliveries SET state = 'IN_FLIGHT', lease_owner = :leaseOwner, " +
            "lease_until_ms = :leaseUntilMs, attempts = attempts + 1, updated_at_ms = :now " +
            "WHERE delivery_id = :id AND state IN ('PENDING', 'RETRY_WAIT')",
    )
    suspend fun claimRow(id: String, leaseOwner: String, leaseUntilMs: Long, now: Long): Int

    /**
     * Claims a bounded batch of due deliveries in one transaction.
     * Returns the claimed deliveries (with their lease owners set).
     * Returns an empty list when the mode is not `WORKER_ONLY`.
     *
     * @param leaseDurationMs lease length (design §5.1: 5 min, no renewal).
     */
    @Transaction
    suspend fun claimBatch(limit: Int, now: Long, leaseDurationMs: Long): List<DeliveryEntity> {
        val modeJson = readCollectorValue(CollectorStateKeys.CUTOVER_STATE)
        val mode = modeJson?.let { parseMode(it) }
        if (mode != "WORKER_ONLY") return emptyList()
        val consentsJson = readCollectorValue(CollectorStateKeys.ROUTE_CONSENTS)
        val candidates = selectDue(limit, now)
        val claimed = mutableListOf<DeliveryEntity>()
        for (candidate in candidates) {
            if (!consentCheckPasses(candidate.routeStampsJson, consentsJson)) continue
            val leaseOwner = UUID.randomUUID().toString()
            val leaseUntilMs = now + leaseDurationMs
            val rows = claimRow(candidate.deliveryId, leaseOwner, leaseUntilMs, now)
            if (rows == 1) {
                claimed.add(
                    candidate.copy(
                        state = DeliveryState.IN_FLIGHT,
                        leaseOwner = leaseOwner,
                        leaseUntilMs = leaseUntilMs,
                        attempts = candidate.attempts + 1,
                        updatedAtMs = now,
                    ),
                )
            }
        }
        return claimed
    }

    // --- Lease expiry reclaim (§5.1 qualification) ---

    @Query(
        "SELECT * FROM deliveries WHERE state = 'IN_FLIGHT' AND lease_until_ms <= :now " +
            "AND NOT EXISTS (SELECT 1 FROM delivery_attempts a " +
            "WHERE a.delivery_id = deliveries.delivery_id " +
            "AND a.state IN ('ATTEMPT_STARTED', 'SOCKET_OPENING', 'SOCKET_OPEN'))",
    )
    suspend fun selectExpiredLeases(now: Long): List<DeliveryEntity>

    @Query(
        "UPDATE deliveries SET state = 'PENDING', lease_owner = NULL, lease_until_ms = NULL, " +
            "updated_at_ms = :now WHERE delivery_id = :id AND state = 'IN_FLIGHT'",
    )
    suspend fun reclaimRow(id: String, now: Long): Int

    /**
     * Reclaims expired leases: `IN_FLIGHT` rows whose lease expired
     * AND which have no unresolved attempt marker return to `PENDING`
     * (§5.1: the `IN_FLIGHT` → `PENDING` line holds only when no
     * `ATTEMPT_STARTED` row for the delivery is unresolved; an
     * unresolved marker holds the delivery in
     * `UNKNOWN_PENDING_RECONCILIATION`).
     */
    @Transaction
    suspend fun reclaimExpiredLeases(now: Long): Int {
        var reclaimed = 0
        for (delivery in selectExpiredLeases(now)) {
            reclaimed += reclaimRow(delivery.deliveryId, now)
        }
        return reclaimed
    }

    // --- Lease-guarded worker transitions (§5.1) ---

    /** `IN_FLIGHT → ACKED`, only while the caller's lease is held and unexpired. */
    @Query(
        "UPDATE deliveries SET state = 'ACKED', acked_at_ms = :now, terminal_at_ms = :now, " +
            "last_match_status = :matchStatus, last_apply_status = :applyStatus, " +
            "updated_at_ms = :now WHERE delivery_id = :id AND state = 'IN_FLIGHT' " +
            "AND lease_owner = :leaseOwner AND lease_until_ms > :now",
    )
    suspend fun ackIfLeaseHeld(
        id: String,
        leaseOwner: String,
        now: Long,
        matchStatus: String?,
        applyStatus: String?,
    ): Int

    /** `IN_FLIGHT → RETRY_WAIT`, only while the caller's lease is held and unexpired. */
    @Query(
        "UPDATE deliveries SET state = 'RETRY_WAIT', next_retry_at_ms = :nextRetryAtMs, " +
            "last_error_code = :errorCode, last_error_summary = :errorSummary, " +
            "next_route_index = :nextRouteIndex, hop_outcomes_json = :hopOutcomesJson, " +
            "updated_at_ms = :now WHERE delivery_id = :id AND state = 'IN_FLIGHT' " +
            "AND lease_owner = :leaseOwner AND lease_until_ms > :now",
    )
    suspend fun retryWaitIfLeaseHeld(
        id: String,
        leaseOwner: String,
        now: Long,
        nextRetryAtMs: Long,
        errorCode: String?,
        errorSummary: String?,
        nextRouteIndex: Int,
        hopOutcomesJson: String,
    ): Int

    /** `IN_FLIGHT → BLOCKED`, only while the caller's lease is held and unexpired. */
    @Query(
        "UPDATE deliveries SET state = 'BLOCKED', last_error_code = :errorCode, " +
            "last_error_summary = :errorSummary, updated_at_ms = :now " +
            "WHERE delivery_id = :id AND state = 'IN_FLIGHT' " +
            "AND lease_owner = :leaseOwner AND lease_until_ms > :now",
    )
    suspend fun blockIfLeaseHeld(
        id: String,
        leaseOwner: String,
        now: Long,
        errorCode: String?,
        errorSummary: String?,
    ): Int

    /**
     * Per-hop checkpoint (§5.5): persist `next_route_index` +
     * `hop_outcomes` after a POST returns, only while the caller's
     * lease is held and unexpired.
     */
    @Query(
        "UPDATE deliveries SET next_route_index = :nextRouteIndex, " +
            "hop_outcomes_json = :hopOutcomesJson, updated_at_ms = :now " +
            "WHERE delivery_id = :id AND state = 'IN_FLIGHT' " +
            "AND lease_owner = :leaseOwner AND lease_until_ms > :now",
    )
    suspend fun checkpointHopIfLeaseHeld(
        id: String,
        leaseOwner: String,
        now: Long,
        nextRouteIndex: Int,
        hopOutcomesJson: String,
    ): Int

    // --- Coordinator / operator transitions (no lease: not worker actions) ---

    /**
     * Coordinator-driven state change (holds, review states, operator
     * decisions). Refuses terminal deliveries: nothing leaves `ACKED`,
     * `EXPIRED`, or `DISCARDED`. The caller validates the transition
     * against `DeliveryStateMachine` before calling.
     */
    @Query(
        "UPDATE deliveries SET state = :to, updated_at_ms = :now WHERE delivery_id = :id " +
            "AND state NOT IN ('ACKED', 'EXPIRED', 'DISCARDED')",
    )
    suspend fun setStateIfNotTerminal(id: String, to: DeliveryState, now: Long): Int

    /** `RETRY_WAIT → EXPIRED` (heartbeat/test TTL only, C6; payment deliveries never expire). */
    @Query(
        "UPDATE deliveries SET state = 'EXPIRED', terminal_at_ms = :now, updated_at_ms = :now " +
            "WHERE delivery_id = :id AND state = 'RETRY_WAIT'",
    )
    suspend fun markExpired(id: String, now: Long): Int

    /** `BLOCKED → PENDING` on explicit operator action: attempts reset (design §5). */
    @Query(
        "UPDATE deliveries SET state = 'PENDING', attempts = 0, next_retry_at_ms = NULL, " +
            "lease_owner = NULL, lease_until_ms = NULL, last_error_code = NULL, " +
            "last_error_summary = NULL, updated_at_ms = :now " +
            "WHERE delivery_id = :id AND state = 'BLOCKED'",
    )
    suspend fun retryManual(id: String, now: Long): Int

    /** Local-only deletion (Stage D UI). */
    @Query("DELETE FROM deliveries WHERE delivery_id = :id")
    suspend fun deleteById(id: String): Int

    companion object {
        /** Parses the `mode` field out of a `cutover_state` JSON document. */
        internal fun parseMode(modeJson: String): String? {
            return try {
                JSONObject(modeJson).optString("mode").ifEmpty { null }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Consent join for the claim: every route stamp on the delivery
         * must agree with the current `route_consents` document. A
         * route with no consent record passes (no revocation evidence);
         * a revoked route (`granted=false`) or a consent epoch newer
         * than the stamp fails the delivery.
         */
        internal fun consentCheckPasses(routeStampsJson: String, consentsJson: String?): Boolean {
            val stamps = try {
                JSONObject(routeStampsJson)
            } catch (e: Exception) {
                return true // no stamps to check (e.g. legacy import)
            }
            if (stamps.length() == 0) return true
            val consents = try {
                consentsJson?.let { JSONObject(it).optJSONObject("routes") }
            } catch (e: Exception) {
                null
            } ?: return true // no consent records at all: nothing revoked
            val routeIds = stamps.keys()
            while (routeIds.hasNext()) {
                val routeId = routeIds.next()
                val stamp = stamps.optJSONObject(routeId) ?: continue
                val record = consents.optJSONObject(routeId) ?: continue
                if (!record.optBoolean("granted", true)) return false
                val currentEpoch = record.optLong("epoch", -1)
                if (currentEpoch >= 0 && currentEpoch != stamp.optLong("consent_epoch", -1)) {
                    return false
                }
            }
            return true
        }
    }
}
