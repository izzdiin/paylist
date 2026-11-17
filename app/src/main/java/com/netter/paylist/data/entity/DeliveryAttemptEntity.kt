package com.netter.paylist.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Attempt row states (design §4.3, §8.5 C21(11)).
 *
 * - [ATTEMPT_STARTED]: the admission marker, written before
 *   `openConnection()` — the only durable truth that a hop was entered.
 * - [SOCKET_OPENING]: the claim transaction succeeded immediately before
 *   `connect()` is invoked.
 * - [SOCKET_OPEN]: the checkpoint transaction committed immediately after
 *   `connect()` returned and before the output stream is touched.
 * - [COMPLETED]: the response returned and the completion checkpoint
 *   committed.
 * - [ABANDONED_PRE_SOCKET]: the coordinator abandoned an `ATTEMPT_STARTED`
 *   row older than `ADMISSION_LEASE_MS` (60 s). Terminal.
 * - [NEVER_SENT]: an orphaned `ATTEMPT_STARTED`/`SOCKET_OPENING` row
 *   classified after process death — conditional on the F40 gate
 *   (§8.5 resolution 3): until F40 passes, those orphans classify
 *   `UNKNOWN_PENDING_RECONCILIATION` at the delivery level instead.
 *
 * There is no `UNKNOWN` row state: `UNKNOWN` is a classification of the
 * hop/delivery, not an attempt state. An orphaned `SOCKET_OPEN` row
 * keeps its state; its delivery is held `HELD_AWAITING_RECONCILIATION`.
 */
enum class AttemptState {
    ATTEMPT_STARTED,
    SOCKET_OPENING,
    SOCKET_OPEN,
    COMPLETED,
    ABANDONED_PRE_SOCKET,
    NEVER_SENT,
}

/**
 * The `delivery_attempts` table (design §4.3): bounded diagnostic
 * attempts, one row per admitted hop attempt.
 *
 * `attempt_id` is single-use, bound to exactly one socket open by the
 * thread that admitted it (§8.5(e)). An `attempt_id` never opens a
 * second socket.
 *
 * Never stores a secret, a full request body, or an unredacted
 * response body (§4.3). Attempt retention is bounded: at most 50
 * attempts per delivery (C11).
 */
@Entity(
    tableName = "delivery_attempts",
    foreignKeys = [
        ForeignKey(
            entity = DeliveryEntity::class,
            parentColumns = ["delivery_id"],
            childColumns = ["delivery_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["delivery_id"]),
        Index(value = ["dedupe_key"]),
        Index(value = ["state", "owner_boot_id"]),
    ],
)
data class DeliveryAttemptEntity(
    /** Primary key, UUID, single-use (§8.5(e)). */
    @PrimaryKey
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,

    /** FK -> deliveries, ON DELETE CASCADE. */
    @ColumnInfo(name = "delivery_id")
    val deliveryId: String,

    /** Route attempted, or null when the hop had no route yet. */
    @ColumnInfo(name = "route_id")
    val routeId: String?,

    /** 1-based attempt number within the delivery. */
    @ColumnInfo(name = "attempt_no")
    val attemptNo: Int,

    /** Admission time (epoch ms); also the lease start for abandonment. */
    @ColumnInfo(name = "started_at_ms")
    val startedAtMs: Long,

    /** Completion/abandonment time (epoch ms), or null while live. */
    @ColumnInfo(name = "finished_at_ms")
    val finishedAtMs: Long? = null,

    /** HTTP status, or null on network failure. */
    @ColumnInfo(name = "http_status")
    val httpStatus: Int? = null,

    /** §5.4 result class (`MATCHED_SETTLED`, `NO_POST`, …), or null. */
    @ColumnInfo(name = "result_class")
    val resultClass: String? = null,

    /** Sanitised error code, or null. */
    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,

    /** Attempt duration in ms, or null while live. */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,

    /** Redacted response status summary, or null. */
    @ColumnInfo(name = "response_status")
    val responseStatus: String? = null,

    /** Set when the attempt dispatches the §7.3B fallback after raw loss. */
    @ColumnInfo(name = "raw_lost")
    val rawLost: Boolean = false,

    /** Attempt state (see [AttemptState]). */
    @ColumnInfo(name = "state")
    val state: AttemptState,

    /** Chain hop index this attempt covers. */
    @ColumnInfo(name = "hop_index")
    val hopIndex: Int,

    /** Consent epoch observed at admission (§8.5(a)). */
    @ColumnInfo(name = "consent_epoch")
    val consentEpoch: Long? = null,

    /** Config epoch observed at admission (§8.5(a)). */
    @ColumnInfo(name = "config_epoch")
    val configEpoch: Long? = null,

    /** SHA-256 of the route URL observed at admission (§8.5(a)). */
    @ColumnInfo(name = "url_sha256")
    val urlSha256: String? = null,

    /** Legacy identity key of the event being delivered. */
    @ColumnInfo(name = "dedupe_key")
    val dedupeKey: String,

    /** `boot_id` of the process that admitted this attempt (§8.5(b)). */
    @ColumnInfo(name = "owner_boot_id")
    val ownerBootId: String,

    /** Drain generation observed at admission, or null. */
    @ColumnInfo(name = "drain_generation")
    val drainGeneration: Long? = null,

    /** Fence generation observed at admission, or null. */
    @ColumnInfo(name = "fence_generation")
    val fenceGeneration: Long? = null,

    /** Hop outcomes recorded by this attempt, as a JSON array string. */
    @ColumnInfo(name = "hop_outcomes_json")
    val hopOutcomesJson: String = "[]",
)
