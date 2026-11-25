package com.netter.paylist.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Capture outcomes (addendum 06, LH-04): one row per notification
 * the listener observes from a merchant package, whether or not it
 * becomes an event.
 */
object CaptureOutcome {
    /** The notification became an event (linked via [CaptureLogEntity.eventId]). */
    const val CAPTURED = "CAPTURED"

    /** A route matched the package but no keyword matched the text. */
    const val NO_KEYWORD = "NO_KEYWORD"

    /** A keyword matched but no amount parsed. */
    const val NO_AMOUNT = "NO_AMOUNT"

    /** Same `dedupe_key`, same content hash — the first event is kept. */
    const val DUPLICATE = "DUPLICATE"

    /** Same `dedupe_key`, different content hash — first event kept, never merged. */
    const val COLLISION = "COLLISION"

    /** Pure-auth notification (OTP/PIN, no financial record). Metadata only. */
    const val PURE_AUTH = "PURE_AUTH"

    /**
     * A notification snapshot dropped by capture-queue saturation
     * before durable staging (Stage D audit P1). Metadata only:
     * package + post time + PrivacyGate-scanned title snippet; no
     * amount (never parsed), no event link, no content hash (a hash
     * of un-gated raw text would be a hash of possibly-secret
     * content). The row is the operator-visible evidence (History
     * filter) and the rescan handle while the notification is still
     * posted. Retention: unlinked rows expire on the 90-day rule
     * (LH-06) like any other unlinked row.
     */
    const val OVERFLOW_DROPPED = "OVERFLOW_DROPPED"

    /** The package is on no enabled route. Package + time + outcome only. */
    const val UNLISTED_PACKAGE = "UNLISTED_PACKAGE"
}

/**
 * Durable capture log (addendum 06, LH-04; Stage D D4, Room v2).
 *
 * One row per observed notification. Privacy rules (LH-07):
 * - `title_snippet` is at most 40 chars, PrivacyGate-scanned before
 *   storage; NULL for `PURE_AUTH` and `UNLISTED_PACKAGE` rows.
 * - `amount_minor` is NULL for `PURE_AUTH` / `UNLISTED_PACKAGE` /
 *   `NO_KEYWORD` / `NO_AMOUNT` rows (no amount parsed).
 * - `content_sha256` is stored for the DUPLICATE vs COLLISION
 *   distinction only; it is never displayed, never logged, never
 *   exported (C22(q'')). It is NULL for non-event rows — a hash of
 *   a pure-auth notification would still be a hash of secret content.
 * - No full raw notification text, no OTP/PIN/auth token, no secret
 *   is ever stored here (§7.1, §7.3).
 *
 * Retention (LH-06, LH-D1 = 90 days): unlinked rows (`event_id`
 * NULL) are auto-deleted 90 days after `captured_at_ms` by the
 * periodic cleanup. Linked rows live with their event (payment
 * events are never auto-deleted); deleting an event SETs the link
 * NULL instead of deleting the row, so the diagnostic row survives
 * as unlinked and expires on the 90-day rule.
 */
@Entity(
    tableName = "capture_log",
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["event_id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["source_package", "post_time_ms"]),
        Index(value = ["post_time_ms"]),
        Index(value = ["event_id"]),
    ],
)
data class CaptureLogEntity(
    /** Primary key, UUID. */
    @PrimaryKey
    @ColumnInfo(name = "capture_id")
    val captureId: String,

    /** The posting package. */
    @ColumnInfo(name = "source_package")
    val sourcePackage: String,

    /** `StatusBarNotification.postTime`. */
    @ColumnInfo(name = "post_time_ms")
    val postTimeMs: Long,

    /** When this device observed the notification. */
    @ColumnInfo(name = "captured_at_ms")
    val capturedAtMs: Long,

    /** One of [CaptureOutcome]. */
    @ColumnInfo(name = "outcome")
    val outcome: String,

    /** PrivacyGate-scanned title, at most 40 chars; NULL for PURE_AUTH / UNLISTED_PACKAGE. */
    @ColumnInfo(name = "title_snippet")
    val titleSnippet: String? = null,

    /** Parsed amount in minor units, when an amount parsed. */
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long? = null,

    /** The event this notification became, or NULL (unlinked row). */
    @ColumnInfo(name = "event_id")
    val eventId: String? = null,

    /** Content hash, stored never displayed/logged/exported (C22(q'')). */
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String? = null,
)
