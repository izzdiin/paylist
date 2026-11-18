package com.netter.paylist.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.netter.paylist.data.entity.AttemptState
import com.netter.paylist.data.entity.DeliveryAttemptEntity

/**
 * DAO for [DeliveryAttemptEntity] (design §4.3, §8.5): attempt writes
 * and cleanup; the admission/claim/abandon transactions (C21(8),
 * C21(11)).
 *
 * State transitions are conditional single UPDATEs: each succeeds
 * only from the expected prior state. The claim transaction
 * (`ATTEMPT_STARTED → SOCKET_OPENING`) and the abandon transaction
 * (`ATTEMPT_STARTED → ABANDONED_PRE_SOCKET`) are mutually exclusive
 * on the row state — whichever commits first wins; the loser
 * observes the row state and acts accordingly (§8.5). There is no
 * interleaving in which both the abandon and the socket-open happen.
 */
@Dao
interface AttemptDao {
    /** Insert; a conflicting `attempt_id` is a defect (UUIDs are single-use). */
    @Insert
    suspend fun insert(attempt: DeliveryAttemptEntity)

    @Query("SELECT * FROM delivery_attempts WHERE attempt_id = :id")
    suspend fun getById(id: String): DeliveryAttemptEntity?

    @Query("SELECT * FROM delivery_attempts WHERE delivery_id = :deliveryId ORDER BY attempt_no ASC")
    suspend fun getByDeliveryId(deliveryId: String): List<DeliveryAttemptEntity>

    /** Unresolved attempts for one hop (one-live-attempt-per-hop rule, §8.5). */
    @Query(
        "SELECT * FROM delivery_attempts WHERE delivery_id = :deliveryId AND hop_index = :hop " +
            "AND state IN ('ATTEMPT_STARTED', 'SOCKET_OPENING', 'SOCKET_OPEN')",
    )
    suspend fun getUnresolvedForHop(deliveryId: String, hop: Int): List<DeliveryAttemptEntity>

    /** Unresolved attempts for one event (snapshot join + fence awaited sets). */
    @Query(
        "SELECT * FROM delivery_attempts WHERE dedupe_key = :dedupeKey " +
            "AND state IN ('ATTEMPT_STARTED', 'SOCKET_OPENING', 'SOCKET_OPEN')",
    )
    suspend fun getUnresolvedByDedupeKey(dedupeKey: String): List<DeliveryAttemptEntity>

    /** All unresolved attempts (the drain's awaited-set snapshot, §8.5(c)). */
    @Query(
        "SELECT * FROM delivery_attempts " +
            "WHERE state IN ('ATTEMPT_STARTED', 'SOCKET_OPENING', 'SOCKET_OPEN')",
    )
    suspend fun getAllUnresolved(): List<DeliveryAttemptEntity>

    /** Unresolved attempts owned by one process (quiescence check, §8.5(d)). */
    @Query(
        "SELECT * FROM delivery_attempts WHERE state IN ('ATTEMPT_STARTED', 'SOCKET_OPENING', 'SOCKET_OPEN') " +
            "AND owner_boot_id = :bootId",
    )
    suspend fun getUnresolvedByOwner(bootId: String): List<DeliveryAttemptEntity>

    @Query(
        "SELECT COUNT(*) FROM delivery_attempts WHERE state IN ('ATTEMPT_STARTED', 'SOCKET_OPENING', 'SOCKET_OPEN') " +
            "AND owner_boot_id = :bootId",
    )
    suspend fun countUnresolvedByOwner(bootId: String): Int

    /** Orphaned attempts: unresolved rows owned by a dead process (§8.5(b)). */
    @Query(
        "SELECT * FROM delivery_attempts WHERE state IN ('ATTEMPT_STARTED', 'SOCKET_OPENING', 'SOCKET_OPEN') " +
            "AND owner_boot_id != :currentBootId",
    )
    suspend fun getOrphaned(currentBootId: String): List<DeliveryAttemptEntity>

    /** `SOCKET_OPENING` rows older than [before] (the `OPENING_ALERT_MS` watchdog, §8.5). */
    @Query("SELECT * FROM delivery_attempts WHERE state = 'SOCKET_OPENING' AND started_at_ms < :before")
    suspend fun getStuckOpening(before: Long): List<DeliveryAttemptEntity>

    /**
     * Claim: `ATTEMPT_STARTED → SOCKET_OPENING`, succeeding only while
     * the row is still `ATTEMPT_STARTED` — immediately before
     * `connect()` is invoked (§8.5). Returns rows affected (0 = the
     * coordinator abandoned the attempt first; the thread exits
     * without opening a socket).
     */
    @Query("UPDATE delivery_attempts SET state = 'SOCKET_OPENING' WHERE attempt_id = :id AND state = 'ATTEMPT_STARTED'")
    suspend fun claimForSocketOpen(id: String): Int

    /**
     * Checkpoint: `SOCKET_OPENING → SOCKET_OPEN`, immediately after
     * `connect()` returns and before the output stream is touched
     * (§8.5). If this write fails, the POST does not start.
     */
    @Query("UPDATE delivery_attempts SET state = 'SOCKET_OPEN' WHERE attempt_id = :id AND state = 'SOCKET_OPENING'")
    suspend fun markSocketOpen(id: String): Int

    /**
     * Completion checkpoint: written on every exit path (response
     * returned, observed exception, gate refusal) in a `finally`-style
     * path (§8.5(f)). A live thread never leaves its own row
     * unresolved except by process death.
     */
    @Query(
        "UPDATE delivery_attempts SET state = 'COMPLETED', finished_at_ms = :now, " +
            "http_status = :httpStatus, result_class = :resultClass, error_code = :errorCode, " +
            "duration_ms = :durationMs, response_status = :responseStatus, " +
            "hop_outcomes_json = :hopOutcomesJson WHERE attempt_id = :id " +
            "AND state IN ('ATTEMPT_STARTED', 'SOCKET_OPENING', 'SOCKET_OPEN')",
    )
    suspend fun markCompleted(
        id: String,
        now: Long,
        httpStatus: Int?,
        resultClass: String?,
        errorCode: String?,
        durationMs: Long?,
        responseStatus: String?,
        hopOutcomesJson: String,
    ): Int

    /**
     * Abandon one admitted attempt: `ATTEMPT_STARTED →
     * ABANDONED_PRE_SOCKET`, succeeding only while the row is still
     * `ATTEMPT_STARTED` (§8.5). The coordinator never abandons a
     * `SOCKET_OPENING` or `SOCKET_OPEN` attempt while its owner
     * process is alive.
     */
    @Query(
        "UPDATE delivery_attempts SET state = 'ABANDONED_PRE_SOCKET', finished_at_ms = :now " +
            "WHERE attempt_id = :id AND state = 'ATTEMPT_STARTED'",
    )
    suspend fun abandonIfAdmitted(id: String, now: Long): Int

    /**
     * Abandon every stale admission: `ATTEMPT_STARTED` rows older
     * than `ADMISSION_LEASE_MS` (60 s). An `ABANDONED_PRE_SOCKET`
     * attempt counts as resolved for the awaited set.
     */
    @Query(
        "UPDATE delivery_attempts SET state = 'ABANDONED_PRE_SOCKET', finished_at_ms = :now " +
            "WHERE state = 'ATTEMPT_STARTED' AND started_at_ms < :before",
    )
    suspend fun abandonStaleAdmissions(before: Long, now: Long): Int

    /**
     * Recovery classification (F40-gated, §8.5 resolution 3): an
     * orphaned `ATTEMPT_STARTED`/`SOCKET_OPENING` row becomes
     * `NEVER_SENT` only after F40 has passed on the actual stack.
     * Until then the orphan classifies `UNKNOWN` at the delivery
     * level and this method must not be called for it.
     */
    @Query(
        "UPDATE delivery_attempts SET state = 'NEVER_SENT', finished_at_ms = :now " +
            "WHERE attempt_id = :id AND state IN ('ATTEMPT_STARTED', 'SOCKET_OPENING')",
    )
    suspend fun markNeverSent(id: String, now: Long): Int

    /** Bound attempt retention: keep the [keep] newest attempts per delivery (C11: 50). */
    @Query(
        "DELETE FROM delivery_attempts WHERE delivery_id = :deliveryId AND attempt_id NOT IN " +
            "(SELECT attempt_id FROM delivery_attempts WHERE delivery_id = :deliveryId " +
            "ORDER BY started_at_ms DESC LIMIT :keep)",
    )
    suspend fun pruneToLatest(deliveryId: String, keep: Int): Int

    companion object {
        /** Attempt states that count as unresolved (live or orphaned). */
        val UNRESOLVED_STATES: List<AttemptState> = listOf(
            AttemptState.ATTEMPT_STARTED,
            AttemptState.SOCKET_OPENING,
            AttemptState.SOCKET_OPEN,
        )
    }
}
