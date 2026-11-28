package com.netter.paylist.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.netter.paylist.data.entity.EventEntity

/**
 * DAO for [EventEntity] (design §4.1).
 *
 * Event insert/query (`INSERT OR IGNORE` on `dedupe_key` — file plan
 * §C). An ignored insert returns -1: a duplicate, counted, not
 * an error (design §3, R11).
 */
@Dao
interface EventDao {
    /**
     * Insert; returns the row id, or -1 when an event with this
     * `dedupe_key` already exists (duplicate — the first event is kept).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE event_id = :id")
    suspend fun getById(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE dedupe_key = :key")
    suspend fun getByDedupeKey(key: String): EventEntity?

    @Query("SELECT * FROM events WHERE export_ref = :ref")
    suspend fun getByExportRef(ref: String): EventEntity?

    /** Reconciliation-export filtering/grouping by verified account key (§7.7A). */
    @Query("SELECT * FROM events WHERE source_account_key = :key")
    suspend fun getBySourceAccountKey(key: String): List<EventEntity>

    /**
     * Payment events in a date range for the reconciliation export
     * (§7.7A): heartbeat/test events are excluded by the category
     * filter at the Room read, never by post-filtering a dump.
     * Ordered by occurrence time; the exporter applies the
     * `row_id` tie-break after building rows.
     */
    @Query(
        "SELECT * FROM events WHERE category = 'payment' " +
            "AND (:fromMs IS NULL OR occurred_at_ms >= :fromMs) " +
            "AND (:toMs IS NULL OR occurred_at_ms <= :toMs) " +
            "ORDER BY occurred_at_ms ASC, event_id ASC",
    )
    suspend fun listPaymentEvents(fromMs: Long?, toMs: Long?): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    /**
     * Per-record local deletion (addendum 06 LH-07(e)): explicit
     * operator policy only, never automatic, never bulk (R29).
     * Deleting an event cascades to its deliveries and attempts;
     * linked capture-log rows are kept with `event_id` SET NULL
     * (they survive as unlinked diagnostic rows, LH-06 90-day rule).
     */
    @Query("DELETE FROM events WHERE event_id = :id")
    suspend fun deleteById(id: String): Int

    /**
     * Key-loss fail-closed (R35): if the Keystore export key is lost or
     * invalidated, all existing `source_account_key` values are set to
     * NULL in one pass. The nulling completes before any new-key
     * grouping becomes active — no mixed old/new HMAC keys are ever
     * live at once. Old keys are never recovered (no re-derivation).
     */
    @Query("UPDATE events SET source_account_key = NULL")
    suspend fun nullAllSourceAccountKeys(): Int

    /**
     * Retention (C11): delete heartbeat/test events whose deliveries
     * are all terminal and whose latest `terminal_at_ms` is older
     * than [before] (30 days). Payment events are never deleted by
     * this query — they are the settlement audit trail. Deleting an
     * event cascades to its deliveries and attempts.
     */
    @Query(
        "DELETE FROM events WHERE category IN ('heartbeat', 'test') " +
            "AND NOT EXISTS (SELECT 1 FROM deliveries d WHERE d.event_id = events.event_id " +
            "AND d.state NOT IN ('ACKED', 'EXPIRED')) " +
            "AND (SELECT MAX(d.terminal_at_ms) FROM deliveries d " +
            "WHERE d.event_id = events.event_id) < :before",
    )
    suspend fun deleteExpiredHeartbeatTest(before: Long): Int
}
