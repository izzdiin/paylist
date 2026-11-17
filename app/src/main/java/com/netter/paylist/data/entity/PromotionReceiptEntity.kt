package com.netter.paylist.data.entity

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Promotion receipt states (design §4.5): `PREPARING → READY → IN_FLIGHT → RESOLVED`. */
enum class ReceiptState {
    PREPARING,
    READY,
    IN_FLIGHT,
    RESOLVED,
}

/**
 * The `promotion_receipts` table (design §4.5): the durable ledger
 * that shrinks the duplicate-POST window for legacy-queue promotions.
 *
 * Lifecycle: created in `PREPARING` in its own Room transaction;
 * moves to `READY` only after the legacy enqueue is verified durable
 * (`saveQueueSync`: `commit()` + readback proving `qkey` present);
 * to `IN_FLIGHT` immediately before the sender releases `Sender.LOCK`
 * to POST; to `RESOLVED` when the sender writes the resolution ack.
 * The sender is the only code that moves a receipt out of `READY`.
 *
 * Dispatch gate (normative, §4.5): the flush consults the receipt
 * before any POST: `PREPARING` → never POST; `READY` → POST
 * permitted, receipt to `IN_FLIGHT` first; `IN_FLIGHT` (after
 * restart) → outcome unknown, re-POST permitted under at-least-once,
 * counted as a possible duplicate; `RESOLVED` → drop without POST
 * even if the item is still in the queue file.
 *
 * Invariant (normative, §4.5): a `qkey` leaves the legacy queue only
 * after a durable resolution ack exists. Residual window: POST
 * succeeded, before the ack write (`IN_FLIGHT`) — a duplicate POST is
 * possible. Irreducible without server-side idempotency.
 */
@Entity(tableName = "promotion_receipts")
data class PromotionReceiptEntity(
    /** Primary key: the legacy queue key this receipt ledgers. */
    @PrimaryKey
    @ColumnInfo(name = "legacy_qkey")
    val legacyQkey: String,

    /** Receipt state (see [ReceiptState]). */
    @ColumnInfo(name = "state")
    val state: ReceiptState,

    /** Receipt creation time (epoch ms). */
    @ColumnInfo(name = "promoted_at_ms")
    val promotedAtMs: Long,

    /** Resolution time (epoch ms), or null while unresolved. */
    @ColumnInfo(name = "resolved_at_ms")
    val resolvedAtMs: Long? = null,

    /** `dispatched` | `no_post`, once resolved. */
    @ColumnInfo(name = "outcome")
    val outcome: String? = null,

    /** Chain cursor at resolution time. */
    @ColumnInfo(name = "next_route_index")
    val nextRouteIndex: Int = 0,

    /** Hop outcomes recorded for this promotion, as a JSON array string. */
    @ColumnInfo(name = "hop_outcomes_json")
    val hopOutcomesJson: String = "[]",

    /** Last hop outcome name (§5.5), or null while unresolved. */
    @ColumnInfo(name = "last_hop_outcome")
    val lastHopOutcome: String? = null,
)

/**
 * DAO for [PromotionReceiptEntity]. State transitions are conditional
 * single UPDATEs: each succeeds only from the expected prior state,
 * so a concurrent mover observes rows-affected = 0 instead of
 * corrupting the ledger.
 */
@Dao
interface PromotionReceiptDao {
    /** Insert; returns 0 when a receipt for this `legacy_qkey` already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(receipt: PromotionReceiptEntity): Long

    @Query("SELECT * FROM promotion_receipts WHERE legacy_qkey = :qkey")
    suspend fun getByLegacyQkey(qkey: String): PromotionReceiptEntity?

    @Query("SELECT * FROM promotion_receipts WHERE state IN ('PREPARING', 'READY', 'IN_FLIGHT')")
    suspend fun getUnresolved(): List<PromotionReceiptEntity>

    /** `PREPARING → READY`: only after the legacy enqueue is verified durable. */
    @Query("UPDATE promotion_receipts SET state = 'READY' WHERE legacy_qkey = :qkey AND state = 'PREPARING'")
    suspend fun markReady(qkey: String): Int

    /** `READY → IN_FLIGHT`: immediately before the sender releases `Sender.LOCK` to POST. */
    @Query("UPDATE promotion_receipts SET state = 'IN_FLIGHT' WHERE legacy_qkey = :qkey AND state = 'READY'")
    suspend fun markInFlight(qkey: String): Int

    /** `IN_FLIGHT → RESOLVED`: when the sender writes the resolution ack. */
    @Query("UPDATE promotion_receipts SET state = 'RESOLVED', resolved_at_ms = :now, outcome = :outcome WHERE legacy_qkey = :qkey AND state = 'IN_FLIGHT'")
    suspend fun markResolved(qkey: String, now: Long, outcome: String): Int
}
