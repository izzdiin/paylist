package com.netter.paylist.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import org.json.JSONArray
import org.json.JSONObject

/**
 * One entry of a delivery's `hop_outcomes[]` (design §4.3, §5.5): the
 * durable per-hop record for one attempted hop.
 *
 * `outcome` is one of the §5.5 outcome names: `LOGGED`,
 * `MATCHED_SETTLED`, `MATCHED_NOT_APPLIED`, `MATCHED_UNCONFIRMED`,
 * `AMBIGUOUS`, `REJECTED-4xx`, `UNKNOWN_PENDING_RECONCILIATION`,
 * `NO_POST`, `BLOCKED_CONSENT`, `BLOCKED_CONFIG`.
 *
 * Never carries a secret, a full request body, or an unredacted
 * response body (§4.3).
 */
data class HopOutcome(
    val routeId: String,
    val outcome: String,
    val httpCode: Int?,
    val matchStatus: String?,
    val apply: String?,
    val attemptedAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("route_id", routeId)
        put("outcome", outcome)
        if (httpCode != null) put("http_code", httpCode)
        if (matchStatus != null) put("match_status", matchStatus)
        if (apply != null) put("apply", apply)
        put("attempted_at_ms", attemptedAtMs)
    }

    companion object {
        fun fromJson(o: JSONObject): HopOutcome = HopOutcome(
            routeId = o.optString("route_id"),
            outcome = o.optString("outcome"),
            httpCode = if (o.has("http_code")) o.optInt("http_code") else null,
            matchStatus = o.optString("match_status").ifEmpty { null },
            apply = o.optString("apply").ifEmpty { null },
            attemptedAtMs = o.optLong("attempted_at_ms"),
        )

        /** Serialises a hop list; empty list becomes `[]`. */
        fun toJsonArray(outcomes: List<HopOutcome>): String {
            val arr = JSONArray()
            outcomes.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        /** Parses a hop list; blank/invalid input becomes an empty list. */
        fun fromJsonArray(json: String?): List<HopOutcome> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }

        /** Outcomes that prove a hop terminal with no unknown flag (§5.5 rows 1/3/6). */
        fun isTerminalHopOutcome(outcome: String): Boolean =
            outcome == "LOGGED" || outcome == "REJECTED-4xx" || outcome == "NO_POST"

        /** Outcomes that stop the chain terminally (§5.5 rows 2a–2d). */
        fun isChainTerminalOutcome(outcome: String): Boolean =
            outcome == "MATCHED_SETTLED" || outcome == "MATCHED_NOT_APPLIED" ||
                outcome == "MATCHED_UNCONFIRMED" || outcome == "AMBIGUOUS"
    }
}

/**
 * The `deliveries` table (design §4.2): one row per outbound delivery job.
 *
 * One payment event maps to one legacy chain delivery, not one delivery
 * per route, to preserve claim/priority semantics. `kind` is
 * `LEGACY_CHAIN` for a legacy chain delivery; `UNIVERSAL` for a
 * single-destination delivery (imported legacy `single` items and, from
 * Stage C, the new pipeline's deliveries).
 *
 * `EXPIRED` is a terminal delivery state, distinct from `ACKED` (C6/C11):
 * a heartbeat/test delivery whose TTL expires transitions to `EXPIRED`
 * with `ackedAtMs` staying NULL; `EXPIRED` deliveries are never counted
 * as ACKs and never retried. Payment deliveries are never expired.
 *
 * Retention (C11): heartbeat/test events whose deliveries are all
 * terminal are deleted 30 days after `terminal_at_ms`, with their
 * deliveries and attempts. Payment events are excluded from the 30-day
 * auto-cleanup — they are the settlement audit trail, deleted only by
 * explicit operator policy, never automatically in Phase 1.
 */
@Entity(
    tableName = "deliveries",
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["event_id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["state", "next_retry_at_ms", "created_at_ms"]),
        Index(value = ["event_id"]),
        // SQLite treats NULLs as distinct in a unique index, so this
        // enforces uniqueness only for non-null qkeys (design §4.2).
        Index(value = ["legacy_qkey"], unique = true),
    ],
)
data class DeliveryEntity(
    /** Primary key, UUID. */
    @PrimaryKey
    @ColumnInfo(name = "delivery_id")
    val deliveryId: String,

    /** FK -> events, ON DELETE CASCADE (C11). */
    @ColumnInfo(name = "event_id")
    val eventId: String,

    /** `LEGACY_CHAIN` or `UNIVERSAL`. */
    @ColumnInfo(name = "kind")
    val kind: DeliveryKind,

    /** Delivery state (design §4.2/§5). */
    @ColumnInfo(name = "state")
    val state: DeliveryState,

    /** Set at capture for a §7.3 Option B delivery (marker only). */
    @ColumnInfo(name = "raw_ephemeral")
    val rawEphemeral: Boolean = false,

    /** Set when the raw snapshot is lost (§7.3B). */
    @ColumnInfo(name = "raw_lost")
    val rawLost: Boolean = false,

    /** Unique import/queue key `dedupe_key|kind|route-set` (§3, R11). */
    @ColumnInfo(name = "legacy_qkey")
    val legacyQkey: String?,

    /** Ordered route ids for a legacy chain, as a JSON array string. */
    @ColumnInfo(name = "route_ids_json")
    val routeIdsJson: String,

    /** Current chain position (design §4.2). */
    @ColumnInfo(name = "next_route_index")
    val nextRouteIndex: Int = 0,

    /** Per-delivery attempt count (design §4.2). */
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,

    /** Next retry time (epoch ms), or null when not scheduled. */
    @ColumnInfo(name = "next_retry_at_ms")
    val nextRetryAtMs: Long? = null,

    /** Lease owner UUID (fresh per claim, §5.1). */
    @ColumnInfo(name = "lease_owner")
    val leaseOwner: String? = null,

    /** Lease expiry (epoch ms). Lease 5 min, no renewal (C7). */
    @ColumnInfo(name = "lease_until_ms")
    val leaseUntilMs: Long? = null,

    /** Sanitised last-error code, or null. */
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String? = null,

    /** Sanitised last-error summary, never raw text. */
    @ColumnInfo(name = "last_error_summary")
    val lastErrorSummary: String? = null,

    /** Legacy server `match.status` (`matched`/`logged`/`ambiguous`), never advisory. */
    @ColumnInfo(name = "last_match_status")
    val lastMatchStatus: String? = null,

    /** `ok`/`skip`/`error`, or null (never assumed `'ok'`, §5.4). */
    @ColumnInfo(name = "last_apply_status")
    val lastApplyStatus: String? = null,

    /** Row creation time (epoch ms). */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    /** Last update time (epoch ms). */
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,

    /** ACK time (epoch ms); stays NULL for `EXPIRED` (C6). */
    @ColumnInfo(name = "acked_at_ms")
    val ackedAtMs: Long? = null,

    /** Set on transition to `ACKED` or `EXPIRED` (C11). */
    @ColumnInfo(name = "terminal_at_ms")
    val terminalAtMs: Long? = null,

    /** Per-route stamp map JSON: `route_id -> {consent_epoch, config_epoch, url_sha256}`. */
    @ColumnInfo(name = "route_stamps_json")
    val routeStampsJson: String = "{}",

    /** Durable per-hop record, as a JSON array string (§4.3, §5.5, §7.7A). */
    @ColumnInfo(name = "hop_outcomes_json")
    val hopOutcomesJson: String = "[]",
)
