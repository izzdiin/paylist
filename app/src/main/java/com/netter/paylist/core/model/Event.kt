package com.netter.paylist.core.model

/**
 * Canonical event model (design §3, schema v1) and the durable identity
 * columns of the `events` table (design §4.1).
 *
 * Stage A: types only, no behaviour. Room entities, DAOs and the repository
 * land in Stage B; the parser that fills these fields lands in Stage D.
 *
 * Identity rules (design §3, normative):
 * - `eventId` identifies a stored event; `sourceEventId` a source
 *   occurrence/update; neither proves a bank transaction's identity.
 * - `dedupeKey` is the legacy identity, computed from pre-redaction
 *   transient input identically on the live and rescan paths (C3) and
 *   never recomputed from a stored payload.
 * - `contentSha256` is the SHA-256 of the full pre-redaction combined
 *   text, computed transiently. It is a hash oracle for low-entropy
 *   text: it is never exported, never printed, never logged (C22(q'')).
 * - `exportRef` is a random UUID v4, generated once at event creation,
 *   the opaque stable identity for the reconciliation export (§7.7A).
 * - `sourceAccountKey` is an opaque Keystore-HMAC key for the verified
 *   source account (§4.1, R34/R35); NULL when the source notification
 *   lacks sufficient verified identity (fail-closed). Never exported.
 */
object EventSchema {
    /** Canonical event schema version (design §3/§4.1: 1). */
    const val SCHEMA_VERSION: Int = 1

    /** Canonical event type for a payment notification (design §3). */
    const val TYPE_PAYMENT_NOTIFICATION: String = "payment.notification"

    /** Canonical event category for payment events (design §3). */
    const val CATEGORY_PAYMENT: String = "payment"

    /** Source type for events captured from Android notifications. */
    const val SOURCE_TYPE_ANDROID_NOTIFICATION: String = "android_notification"
}

/** Where an event came from (design §3 `source`). */
data class EventSource(
    /** e.g. [EventSchema.SOURCE_TYPE_ANDROID_NOTIFICATION]. */
    val type: String,
    /** Device-installation UUID. */
    val deviceId: String,
    /** Source application package, e.g. `com.gojek.gopaymerchant`. */
    val packageName: String?,
)

/**
 * Structured payment data (design §3 `data`). May be empty; amount and
 * currency are optional. Amount is in integer minor units (rupiah);
 * binary floating-point is never used for money (§7.7A).
 */
data class EventData(
    val amount: Long?,
    val currency: String?,
)

/** Capture metadata (design §3 `metadata`). */
data class EventMetadata(
    /** Parser that produced the structured fields, e.g. `payment-v1`. */
    val parserVersion: String,
    /** Privacy mode at capture: `structured` (default) or legacy. */
    val privacyMode: String,
)

/**
 * One canonical captured event. Immutable: an updated notification with
 * the same [dedupeKey] is the same event, not a new one (design §3).
 */
data class CanonicalEvent(
    /** Primary key, UUID v4. */
    val eventId: String,
    /** Always [EventSchema.SCHEMA_VERSION] for v1.1 events. */
    val schemaVersion: Int = EventSchema.SCHEMA_VERSION,
    /** Stable source occurrence/update key. */
    val sourceEventId: String,
    /** e.g. [EventSchema.TYPE_PAYMENT_NOTIFICATION]. */
    val type: String,
    /** e.g. [EventSchema.CATEGORY_PAYMENT]. */
    val category: String,
    val source: EventSource,
    /** When the notification occurred (epoch ms). */
    val occurredAtMs: Long,
    /** When this device captured it (epoch ms). */
    val capturedAtMs: Long,
    val data: EventData,
    val metadata: EventMetadata,
    /** Legacy identity key (design §3, C3). Unique index in Room. */
    val dedupeKey: String,
    /** SHA-256 of the full pre-redaction combined text. Never logged. */
    val contentSha256: String,
    /** Random UUID v4, opaque export identity (design §4.1/§7.7A). */
    val exportRef: String,
    /** Opaque verified-account key, or null (fail-closed, §4.1). */
    val sourceAccountKey: String?,
    /** Compatibility payload for the legacy chain, sanitised. Null unless needed. */
    val legacyPayloadJson: String?,
    /** Retention deadline, or null (payment events: no auto-cleanup, §4.2). */
    val retentionUntilMs: Long?,
)
