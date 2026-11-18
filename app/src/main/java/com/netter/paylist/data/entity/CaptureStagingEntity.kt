package com.netter.paylist.data.entity

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The `capture_staging` table (design §4.5): durable staging rows for
 * captures between the PrivacyGate classification and the enqueue into
 * exactly one store (legacy queue while `LEGACY_ONLY`, Room outbox
 * once `DRAINING` is committed).
 *
 * Written post-PrivacyGate only, in a synchronous Room transaction.
 * Staging never holds raw text for an inseparable event (§8.3 staging
 * gate); the §7.3A in-memory raw snapshot is never staged.
 *
 * Keying: §4.5 describes the table as "keyed by unique `dedupe_key`",
 * while §8.3 C22(j) fans one snapshot out to one staging row per
 * delivery, "each keyed by its own `legacy_qkey`". The two agree for
 * the common single-delivery case; for multi-delivery fan-out the
 * primary key is `legacy_qkey` (unique per delivery) and `dedupe_key`
 * is a non-unique index. The event row itself (written atomically
 * with the staging rows, C22(j)) carries the snapshot content — the
 * staging row is a promotion marker, not a second copy of the event.
 *
 * Invariant (normative, §4.5): a staging row is deleted only after
 * the target store proves the event is durable — legacy path: after
 * `saveQueueSync` commit + readback proving the item present; Room
 * path: outbox insert + staging delete in one Room transaction. The
 * sweeper re-promotes orphaned staging rows through the same
 * lock/mode/consent gate; re-promotion is idempotent.
 */
@Entity(
    tableName = "capture_staging",
    indices = [
        Index(value = ["dedupe_key"]),
    ],
)
data class CaptureStagingEntity(
    /** Primary key: the delivery key this staging row promotes. */
    @PrimaryKey
    @ColumnInfo(name = "legacy_qkey")
    val legacyQkey: String,

    /** Legacy identity key of the staged event. */
    @ColumnInfo(name = "dedupe_key")
    val dedupeKey: String,

    /** SHA-256 of the full pre-redaction combined text, or null. */
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String?,

    /** SHA-256 of the staged payload (F18 mismatch check). */
    @ColumnInfo(name = "payload_sha256")
    val payloadSha256: String?,

    /** Consent epoch observed at capture. */
    @ColumnInfo(name = "consent_epoch")
    val consentEpoch: Long,

    /** Config epoch observed at capture. */
    @ColumnInfo(name = "config_epoch")
    val configEpoch: Long,

    /** Staging time (epoch ms). */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)

/** DAO for [CaptureStagingEntity] (file plan §B: carried in this file). */
@Dao
interface CaptureStagingDao {
    /** Insert; returns the row id, or -1 when a row with this `legacy_qkey` already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: CaptureStagingEntity): Long

    @Query("SELECT * FROM capture_staging WHERE legacy_qkey = :qkey")
    suspend fun getByLegacyQkey(qkey: String): CaptureStagingEntity?

    @Query("SELECT * FROM capture_staging WHERE dedupe_key = :key")
    suspend fun getByDedupeKey(key: String): List<CaptureStagingEntity>

    /** Oldest first — the sweeper promotes in capture order. */
    @Query("SELECT * FROM capture_staging ORDER BY created_at_ms ASC")
    suspend fun getAll(): List<CaptureStagingEntity>

    @Query("DELETE FROM capture_staging WHERE legacy_qkey = :qkey")
    suspend fun deleteByLegacyQkey(qkey: String): Int

    @Query("SELECT COUNT(*) FROM capture_staging")
    suspend fun count(): Int
}
