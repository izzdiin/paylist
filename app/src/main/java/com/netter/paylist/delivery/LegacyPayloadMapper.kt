package com.netter.paylist.delivery

import org.json.JSONObject
import java.time.Instant

/**
 * Builds the legacy wire payload (design §5.3) for one hop.
 *
 * Wire contract (payment): `type`, `device`, `pkg`, `amount`, `title`,
 * `text`, `notif_time`, `dedupe_key`. Heartbeat/test keep their existing
 * field expectations (no `amount`, no `notif_time`). `X-QRIS-Secret`
 * travels as a header, never in the body. Advisory P3 fields
 * (`bank_reference`, `order_number`, advisory `match.status`) are never
 * transmitted as a settlement signal — this mapper emits none of them.
 *
 * Compatibility is JSON equality after parsing (T02/T03), not
 * byte-identical (§5.3 C10). Title/text keep the legacy 500-char
 * truncation: the server's stored shapes already truncate there.
 *
 * Two sources, two rules:
 * - Imported pre-v1.1 items: the stored payload goes out VERBATIM
 *   ([mapImported]) — the mapper never re-derives or re-truncates it.
 * - New v1.1 events: built from structured fields ([mapPayment],
 *   [mapHeartbeat], [mapTest]).
 *
 * The `text` argument is supplied by the caller: the raw notification
 * text, or an A′ text — and only when a recorded gate decision
 * (§7.3/§7.3A/§7.3B) authorises A′ for this delivery. The mapper
 * NEVER chooses A′ itself and never substitutes text.
 */
object LegacyPayloadMapper {

    /** Max title/text length on the wire (legacy behaviour, kept). */
    const val MAX_TEXT_CHARS = 500

    /** Heartbeat dedupe bucket length in ms (15 min, `Sender.kt`). */
    const val HEARTBEAT_BUCKET_MS = 900_000L

    /**
     * Structured fields for a new v1.1 payment event's wire payload.
     * `text`: raw notification text, or an A′ text when a recorded gate
     * decision authorises it — supplied by the caller, never chosen here.
     */
    data class PaymentFields(
        val device: String,
        val pkg: String,
        val amount: Long,
        val title: String,
        val text: String,
        /** Notification time, epoch ms; emitted as an ISO-8601 string. */
        val notifTimeMs: Long,
        val dedupeKey: String,
    )

    /** Wire payload for a new v1.1 payment event. */
    fun mapPayment(fields: PaymentFields): JSONObject = JSONObject().apply {
        put("type", "payment")
        put("device", fields.device)
        put("pkg", fields.pkg)
        put("amount", fields.amount)
        put("title", fields.title.take(MAX_TEXT_CHARS))
        put("text", fields.text.take(MAX_TEXT_CHARS))
        put("notif_time", Instant.ofEpochMilli(fields.notifTimeMs).toString())
        put("dedupe_key", fields.dedupeKey)
    }

    /** Wire payload for a heartbeat hop (15-minute dedupe bucket). */
    fun mapHeartbeat(device: String, routeId: String, nowMs: Long): JSONObject =
        JSONObject().apply {
            put("type", "heartbeat")
            put("device", device)
            put("pkg", "")
            put("dedupe_key", "hb-$routeId-${nowMs / HEARTBEAT_BUCKET_MS}")
            put("title", "")
            put("text", "")
        }

    /** Wire payload for a manual endpoint test hop. */
    fun mapTest(device: String, routeId: String, nowMs: Long): JSONObject =
        JSONObject().apply {
            put("type", "test")
            put("device", device)
            put("pkg", "test")
            put("dedupe_key", "test-$routeId-$nowMs")
            put("title", "Tes")
            put("text", "tes endpoint dari Paylist")
        }

    /**
     * Verbatim payload for an imported pre-v1.1 item: the stored JSON,
     * parsed and returned as-is. The mapper never re-derives fields,
     * never re-truncates, never drops unknown keys.
     */
    fun mapImported(legacyPayloadJson: String): JSONObject =
        JSONObject(legacyPayloadJson)
}
