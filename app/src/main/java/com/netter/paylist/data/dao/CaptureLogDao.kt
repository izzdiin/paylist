package com.netter.paylist.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.CaptureLogEntity

/**
 * DAO for [CaptureLogEntity] (addendum 06, LH-04; Stage D D4).
 *
 * History filters (LH-02) are applied in SQL, not by post-filtering
 * an unfiltered dump (mirroring §7.7A). The history list query joins
 * the current (non-superseded) delivery so the caller can filter by
 * delivery state and route; `GROUP BY capture_id` keeps one row per
 * capture when an event briefly has two live deliveries (pick hold).
 *
 * No export path reads this table (LH-07(c), F55): the only readers
 * are the capture coordinator (writes), the history repository
 * (reads), and the periodic retention cleanup (deletes).
 */
@Dao
interface CaptureLogDao {
    /**
     * Insert; returns the row id, or -1 when a row with this
     * `capture_id` already exists (a re-observed notification with
     * an identical id is a duplicate, counted, not an error).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: CaptureLogEntity): Long

    @Query("SELECT * FROM capture_log WHERE capture_id = :id")
    suspend fun getById(id: String): CaptureLogEntity?

    @Query("SELECT * FROM capture_log WHERE event_id = :eventId ORDER BY post_time_ms DESC")
    suspend fun listByEventId(eventId: String): List<CaptureLogEntity>

    /**
     * The history list (LH-01/LH-02), paged, most recent first.
     * Every filter is nullable (null = no constraint):
     * - date range on [CaptureLogEntity.postTimeMs] (inclusive),
     * - amount range on [CaptureLogEntity.amountMinor],
     * - exact [CaptureLogEntity.sourcePackage],
     * - exact [CaptureLogEntity.outcome],
     * - delivery [DeliveryState] of the current delivery (unlinked
     *   rows never match a state filter),
     * - route id contained in the current delivery's route list,
     * - server `last_match_status` of the current delivery
     *   (`matched` / `logged` / `ambiguous`; unlinked rows never
     *   match a match-status filter),
     * - free-text [query]: package substring, or an exact amount
     *   match when the query is numeric (structured fields only —
     *   there is no raw-text search, LH-02).
     */
    @Query(
        "SELECT cl.* FROM capture_log cl " +
            "LEFT JOIN deliveries d ON d.event_id = cl.event_id " +
            "AND d.state NOT IN ('DISCARDED', 'EXPIRED') " +
            "WHERE (:fromMs IS NULL OR cl.post_time_ms >= :fromMs) " +
            "AND (:toMs IS NULL OR cl.post_time_ms <= :toMs) " +
            "AND (:minAmount IS NULL OR cl.amount_minor >= :minAmount) " +
            "AND (:maxAmount IS NULL OR cl.amount_minor <= :maxAmount) " +
            "AND (:packageName IS NULL OR cl.source_package = :packageName) " +
            "AND (:outcome IS NULL OR cl.outcome = :outcome) " +
            "AND (:state IS NULL OR d.state = :state) " +
            "AND (:routeId IS NULL OR d.route_ids_json LIKE '%\"' || :routeId || '\"%') " +
            "AND (:matchStatus IS NULL OR d.last_match_status = :matchStatus) " +
            "AND (:query IS NULL OR cl.source_package LIKE '%' || :query || '%' " +
            "OR CAST(cl.amount_minor AS TEXT) = :query) " +
            "GROUP BY cl.capture_id ORDER BY cl.post_time_ms DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun listHistory(
        fromMs: Long?,
        toMs: Long?,
        minAmount: Long?,
        maxAmount: Long?,
        packageName: String?,
        outcome: String?,
        state: DeliveryState?,
        routeId: String?,
        query: String?,
        limit: Int,
        offset: Int,
        matchStatus: String?,
    ): List<CaptureLogEntity>

    /** Row count for the same filter set as [listHistory] (paging). */
    @Query(
        "SELECT COUNT(DISTINCT cl.capture_id) FROM capture_log cl " +
            "LEFT JOIN deliveries d ON d.event_id = cl.event_id " +
            "AND d.state NOT IN ('DISCARDED', 'EXPIRED') " +
            "WHERE (:fromMs IS NULL OR cl.post_time_ms >= :fromMs) " +
            "AND (:toMs IS NULL OR cl.post_time_ms <= :toMs) " +
            "AND (:minAmount IS NULL OR cl.amount_minor >= :minAmount) " +
            "AND (:maxAmount IS NULL OR cl.amount_minor <= :maxAmount) " +
            "AND (:packageName IS NULL OR cl.source_package = :packageName) " +
            "AND (:outcome IS NULL OR cl.outcome = :outcome) " +
            "AND (:state IS NULL OR d.state = :state) " +
            "AND (:routeId IS NULL OR d.route_ids_json LIKE '%\"' || :routeId || '\"%') " +
            "AND (:matchStatus IS NULL OR d.last_match_status = :matchStatus) " +
            "AND (:query IS NULL OR cl.source_package LIKE '%' || :query || '%' " +
            "OR CAST(cl.amount_minor AS TEXT) = :query)",
    )
    suspend fun countHistory(
        fromMs: Long?,
        toMs: Long?,
        minAmount: Long?,
        maxAmount: Long?,
        packageName: String?,
        outcome: String?,
        state: DeliveryState?,
        routeId: String?,
        query: String?,
        matchStatus: String?,
    ): Int

    /** Distinct posting packages (filter dropdown). */
    @Query("SELECT DISTINCT source_package FROM capture_log ORDER BY source_package")
    suspend fun distinctPackages(): List<String>

    /**
     * Whether a COLLISION observation exists for the same
     * (package, post time, amount) as a captured event (LH-03
     * `identity_collision` flag, derived — the collision row is
     * unlinked by design, LH-06, so it cannot join on `event_id`).
     */
    @Query(
        "SELECT COUNT(*) FROM capture_log WHERE outcome = 'COLLISION' " +
            "AND source_package = :packageName AND post_time_ms = :postTimeMs " +
            "AND amount_minor = :amountMinor",
    )
    suspend fun countCollisionsFor(packageName: String, postTimeMs: Long, amountMinor: Long?): Int

    @Query("SELECT COUNT(*) FROM capture_log")
    suspend fun count(): Int

    /** Per-record local deletion (LH-07(e)). */
    @Query("DELETE FROM capture_log WHERE capture_id = :id")
    suspend fun deleteById(id: String): Int

    /**
     * Retention (LH-06, LH-D1 = 90 days): delete unlinked rows
     * older than [cutoffMs]. Linked rows are never deleted here —
     * they live with their event.
     */
    @Query("DELETE FROM capture_log WHERE event_id IS NULL AND captured_at_ms < :cutoffMs")
    suspend fun deleteUnlinkedOlderThan(cutoffMs: Long): Int

    /** Per-category local deletion (LH-07(e)): unlinked rows of one outcome. */
    @Query("DELETE FROM capture_log WHERE event_id IS NULL AND outcome = :outcome")
    suspend fun deleteUnlinkedByOutcome(outcome: String): Int
}
