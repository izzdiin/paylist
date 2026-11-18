package com.netter.paylist.core.model

/**
 * Delivery states and typed transitions (design §4.2, §5, §7.3B, §7.6, §8.5).
 *
 * Stage A: types + the pure transition table only, no behaviour. The Room
 * entity, DAOs and the worker that performs these transitions land in
 * Stages B/C. [DeliveryStateMachine.canTransition] encodes exactly the
 * transitions the design names; anything not listed is forbidden.
 */

/** One payment event maps to one legacy chain delivery, not one per route (§4.2). */
enum class DeliveryKind {
    LEGACY_CHAIN,
    UNIVERSAL,
}

/**
 * Delivery states (design §4.2). Terminal states: [ACKED], [EXPIRED],
 * [DISCARDED] — nothing leaves them.
 *
 * Held states answer different questions and are never conflated (§7.3B):
 * - [NEEDS_REVIEW]: the amount question (§7.6).
 * - [RAW_UNAVAILABLE]: the payload-form question (§7.3B).
 * - [BLOCKED]: the consent/config question (§7.5).
 */
enum class DeliveryState {
    PENDING,
    IN_FLIGHT,
    RETRY_WAIT,
    BLOCKED,
    ACKED,
    EXPIRED,
    NEEDS_REVIEW,
    RAW_UNAVAILABLE,
    DISCARDED,
    HELD_AWAITING_LEGACY_SWEEP,
    HELD_AWAITING_RECONCILIATION,
}

/**
 * Per-route destination snapshot observed at capture/promotion/claim
 * (design §7.5): consent epoch, config epoch, SHA-256 of the URL.
 * Never the URL itself, never the secret.
 */
data class RouteStamp(
    val consentEpoch: Long,
    val configEpoch: Long,
    val urlSha256: String,
)

/**
 * One outbound delivery job (design §4.2 `deliveries`).
 * `deliveryId` identifies the job; `eventId` the event it delivers.
 */
data class Delivery(
    val deliveryId: String,
    val eventId: String,
    val kind: DeliveryKind,
    val state: DeliveryState,
    /** Set at capture for a §7.3 Option B delivery (marker only). */
    val rawEphemeral: Boolean = false,
    /** Set when the raw snapshot is lost (§7.3B). */
    val rawLost: Boolean = false,
    /** Unique import/queue key `dedupe_key|kind|route-set` (§3, R11). */
    val legacyQkey: String?,
    /** Ordered route ids for a legacy chain. */
    val routeIds: List<String>,
    val nextRouteIndex: Int = 0,
    val attempts: Int = 0,
    val nextRetryAtMs: Long? = null,
    val leaseOwner: String? = null,
    val leaseUntilMs: Long? = null,
    val lastErrorCode: String? = null,
    /** Sanitised error summary, never raw text. */
    val lastErrorSummary: String? = null,
    /** Legacy server `match.status` (`matched`/`logged`/`ambiguous`), never advisory. */
    val lastMatchStatus: String? = null,
    /** `ok`/`skip`/`error`, or null (never assumed `'ok'`, §5.4). */
    val lastApplyStatus: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val ackedAtMs: Long? = null,
    /** Set on transition to ACKED or EXPIRED (§4.2, C11). */
    val terminalAtMs: Long? = null,
    /** `route_id ->` stamp observed at capture/promotion (C22(l)/(p)). */
    val routeStamps: Map<String, RouteStamp> = emptyMap(),
)

/** The §5 delivery state machine as a pure transition table. */
object DeliveryStateMachine {

    /** States with no outgoing transition (§4.2, §5). */
    fun isTerminal(state: DeliveryState): Boolean =
        state == DeliveryState.ACKED ||
            state == DeliveryState.EXPIRED ||
            state == DeliveryState.DISCARDED

    /**
     * Whether [from] -> [to] is a transition the design names. Guards that
     * need runtime context (lease ownership, attempt markers, event type,
     * reconciliation evidence) are enforced by the caller, not here:
     * - `IN_FLIGHT -> PENDING` only when no unresolved attempt marker
     *   exists for the delivery (§5.1); otherwise the delivery holds.
     * - `RETRY_WAIT -> EXPIRED` only for heartbeat/test deliveries;
     *   payment deliveries are never expired (§4.2, C6).
     * - `HELD_AWAITING_RECONCILIATION -> PENDING/ACKED` only on
     *   reconciliation evidence (§5.5 row 11, §9).
     * - `RAW_UNAVAILABLE -> PENDING` only on explicit operator
     *   authorisation of an A′ dispatch, subject to the §7.3B hard stop.
     */
    fun canTransition(from: DeliveryState, to: DeliveryState): Boolean {
        if (from == to) return false
        return when (from) {
            DeliveryState.PENDING -> to == DeliveryState.IN_FLIGHT || // claim (§5)
                to == DeliveryState.BLOCKED || // consent/config gate (§7.5)
                to == DeliveryState.RAW_UNAVAILABLE || // raw loss, fallback refused (§7.3B)
                to == DeliveryState.HELD_AWAITING_RECONCILIATION // orphaned unknown hop (§8.5(g))
            DeliveryState.IN_FLIGHT -> to == DeliveryState.ACKED || // 2xx valid (§5)
                to == DeliveryState.RETRY_WAIT || // network/408/429/5xx (§5)
                to == DeliveryState.BLOCKED || // nonretryable 4xx / 3xx (§5.4)
                to == DeliveryState.PENDING || // lease expiry, guarded (§5.1)
                to == DeliveryState.HELD_AWAITING_RECONCILIATION // unknown hop (§5.5 row 4)
            DeliveryState.RETRY_WAIT -> to == DeliveryState.PENDING || // claim when due (§5.2)
                to == DeliveryState.EXPIRED || // heartbeat/test TTL (§5, C6)
                to == DeliveryState.BLOCKED // revocation sweep (§7.5)
            DeliveryState.BLOCKED -> to == DeliveryState.PENDING // explicit operator action (§5)
            DeliveryState.NEEDS_REVIEW -> to == DeliveryState.HELD_AWAITING_LEGACY_SWEEP || // amount pick (§7.6)
                to == DeliveryState.DISCARDED // operator discard (§7.6)
            DeliveryState.HELD_AWAITING_LEGACY_SWEEP -> to == DeliveryState.PENDING || // sweep release (§8.6/§8.7)
                to == DeliveryState.BLOCKED // prior_copy_dispatched (§7.6/§8.7)
            DeliveryState.HELD_AWAITING_RECONCILIATION -> to == DeliveryState.PENDING || // evidence: advance (§5.5 row 11)
                to == DeliveryState.ACKED || // evidence: terminal (§5.5 row 11)
                to == DeliveryState.BLOCKED // operator decision on the evidence (§8.7)
            DeliveryState.RAW_UNAVAILABLE -> to == DeliveryState.PENDING || // operator authorises A′ (§7.3B)
                to == DeliveryState.DISCARDED || // local disposition only (§7.3B)
                to == DeliveryState.BLOCKED // opt-in expired/revoked while held (§7.3)
            DeliveryState.ACKED,
            DeliveryState.EXPIRED,
            DeliveryState.DISCARDED,
            -> false
        }
    }
}
