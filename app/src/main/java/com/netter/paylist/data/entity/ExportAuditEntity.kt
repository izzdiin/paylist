package com.netter.paylist.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Export attempt statuses (design §4.7). */
object ExportStatus {
    const val COMPLETED: String = "COMPLETED"
    const val FAILED: String = "FAILED"
    const val CANCELLED: String = "CANCELLED"
}

/** Export authorisation methods (design §4.7). */
object ExportAuth {
    const val NONE: String = "NONE"
    const val BIOMETRIC: String = "BIOMETRIC"
    const val DEVICE_CREDENTIAL: String = "DEVICE_CREDENTIAL"
}

/**
 * The `export_audit` table (design §4.7, R31–R33): one row per
 * reconciliation-export attempt, including cancelled and failed
 * attempts (with `row_count` 0 when nothing was written).
 *
 * The audit row never contains exported content. For FAILED/CANCELLED
 * attempts after writing began, `row_count` is the truthful
 * rows-written count and `notes` records the partial-file and
 * cleanup detail.
 *
 * `epochs_json` (R33) is the authorisation snapshot for the export:
 * per-route `consent_epoch` + `config_epoch`, identifiers opt-in,
 * and frozen row-set fingerprint at write start — recorded so the
 * exported fields correspond to the authorisation that governed
 * the write.
 */
@Entity(tableName = "export_audit")
data class ExportAuditEntity(
    /** Primary key, UUID. */
    @PrimaryKey
    @ColumnInfo(name = "export_id")
    val exportId: String,

    /** Export attempt time (epoch ms). */
    @ColumnInfo(name = "exported_at_ms")
    val exportedAtMs: Long,

    /** The operator (single-user app: the device user). */
    @ColumnInfo(name = "actor")
    val actor: String,

    /** Applied filter JSON: date range, source account, direction, status, identifiers flag. */
    @ColumnInfo(name = "filter_json")
    val filterJson: String,

    /** Rows written to the file (truthful count, 0 when nothing was written). */
    @ColumnInfo(name = "row_count")
    val rowCount: Int,

    /** Rows omitted for privacy/quarantine/consent. */
    @ColumnInfo(name = "excluded_row_count")
    val excludedRowCount: Int,

    /** Suggested file name as written. */
    @ColumnInfo(name = "file_name")
    val fileName: String,

    /** `COMPLETED` / `FAILED` / `CANCELLED`. */
    @ColumnInfo(name = "status")
    val status: String,

    /** `NONE` / `BIOMETRIC` / `DEVICE_CREDENTIAL` (§7.7A). */
    @ColumnInfo(name = "auth")
    val auth: String,

    /** Authorisation snapshot JSON (nullable, R33). */
    @ColumnInfo(name = "epochs_json")
    val epochsJson: String?,

    /** Failure/cleanup detail for FAILED/CANCELLED attempts; never exported content. */
    @ColumnInfo(name = "notes")
    val notes: String?,
)
