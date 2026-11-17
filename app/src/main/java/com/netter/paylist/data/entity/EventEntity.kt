package com.netter.paylist.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The `events` table (design §4.1): one row per canonical captured event.
 *
 * Identity rules (design §3, normative):
 * - `eventId` identifies a stored event; `sourceEventId` a source
 *   occurrence/update; neither proves a bank transaction's identity.
 * - `dedupeKey` is the legacy identity, unique. On INSERT conflict on
 *   `dedupe_key`: same `contentSha256` = duplicate (expected); different
 *   hash = genuine collision/update — first event kept, counted as
 *   `identity_collision`, never silently merged.
 * - `contentSha256` is the SHA-256 of the full pre-redaction combined
 *   text, computed transiently. It is a hash oracle for low-entropy
 *   text: never exported, never printed, never logged (C22(q'')).
 *   NULL for pre-v1.1 imports: the full pre-redaction text no longer
 *   exists (the stored payload truncates to 500 chars and drops
 *   `bigText`), so no hash can be honestly computed — a hash over the
 *   truncated payload would be a false hash and would break the F51
 *   hash-comparison semantics. NULL means "no verified hash available".
 * - `exportRef` is a random UUID v4, generated once at event creation,
 *   the opaque stable identity for the reconciliation export (§7.7A).
 *   A legacy import assigns a fresh `exportRef`; re-import preserves
 *   the first one (INSERT OR IGNORE, F65).
 * - `sourceAccountKey` is an opaque Keystore-HMAC key for the verified
 *   source account (§4.1, R34/R35); NULL when the source notification
 *   lacks sufficient verified identity (fail-closed). Never exported,
 *   never logged. On Keystore key loss, all values are set to NULL in
 *   one pass (see [com.netter.paylist.data.dao.EventDao.nullAllSourceAccountKeys]).
 */
@Entity(
    tableName = "events",
    indices = [
        Index(value = ["dedupe_key"], unique = true),
        Index(value = ["export_ref"], unique = true),
        Index(value = ["source_account_key"]),
        Index(value = ["category"]),
        Index(value = ["captured_at_ms"]),
    ],
)
data class EventEntity(
    /** Primary key, UUID v4. */
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,

    /** Always 1 for v1.1 events (design §3/§4.1). */
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int = 1,

    /** e.g. `android_notification`, `heartbeat`, `test`. */
    @ColumnInfo(name = "source_type")
    val sourceType: String,

    /** Stable source occurrence/update key. */
    @ColumnInfo(name = "source_event_id")
    val sourceEventId: String,

    /** Device-installation UUID (legacy imports: the payload `device` label). */
    @ColumnInfo(name = "device_id")
    val deviceId: String,

    /** e.g. `payment.notification`. */
    @ColumnInfo(name = "event_type")
    val eventType: String,

    /** `payment` | `heartbeat` | `test` (design §3). */
    @ColumnInfo(name = "category")
    val category: String,

    /** Source application package; null for heartbeat/test. */
    @ColumnInfo(name = "source_package")
    val sourcePackage: String?,

    /** When the notification occurred (epoch ms). */
    @ColumnInfo(name = "occurred_at_ms")
    val occurredAtMs: Long,

    /** When this device captured it (epoch ms). */
    @ColumnInfo(name = "captured_at_ms")
    val capturedAtMs: Long,

    /** Canonical `data` JSON (amount/currency; may be `{}`). */
    @ColumnInfo(name = "data_json")
    val dataJson: String,

    /** Canonical `metadata` JSON (parser version, privacy mode). */
    @ColumnInfo(name = "metadata_json")
    val metadataJson: String,

    /** Compatibility payload for the legacy chain, sanitised. Null unless needed. */
    @ColumnInfo(name = "legacy_payload_json")
    val legacyPayloadJson: String?,

    /** Privacy mode at capture: `structured` (default) or `legacy` (import). */
    @ColumnInfo(name = "privacy_mode")
    val privacyMode: String,

    /** Retention deadline, or null (payment events: no auto-cleanup, §4.2). */
    @ColumnInfo(name = "retention_until_ms")
    val retentionUntilMs: Long?,

    /** Legacy identity key (design §3, C3). Unique index. */
    @ColumnInfo(name = "dedupe_key")
    val dedupeKey: String,

    /** SHA-256 of the full pre-redaction combined text, or null (see KDoc). */
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String?,

    /** Random UUID v4, opaque export identity (design §4.1/§7.7A). */
    @ColumnInfo(name = "export_ref")
    val exportRef: String,

    /** Opaque verified-account key, or null (fail-closed, §4.1). */
    @ColumnInfo(name = "source_account_key")
    val sourceAccountKey: String?,
)
