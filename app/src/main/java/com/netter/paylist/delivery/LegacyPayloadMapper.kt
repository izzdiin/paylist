package com.netter.paylist.delivery

import com.netter.paylist.parser.Direction
import com.netter.paylist.parser.DirectionHeuristic
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

    /** Result of building a §7.2 constructed recorder text. */
    data class ConstructedText(
        /** The constructed text (never a raw substring). */
        val text: String,
        /** True when the self-check fell back to the no-cue form. */
        val needsReview: Boolean,
    )

    /**
     * Builds the §7.2 constructed recorder text purely from
     * structured fields, never from any substring of raw text:
     * - `out` → `Transfer ke {masked_account} Rp{amount} Ref {reference}`
     * - `in` → `Diterima Rp{amount} dari {masked_account} Ref {reference}`
     * - `unknown` → `Rp{amount} {masked_account} Ref {reference}`
     *
     * The `Ref` token appears only when a labelled, non-secret
     * reference exists; `{masked_account}` is omitted when absent;
     * `title` is sent empty.
     *
     * Self-check (F49, §7.1 gating): the same two-list heuristic
     * runs over the constructed text including inserted tokens.
     * - A token containing any direction-list word as a substring
     *   is OMITTED (never inserted) and the form falls back to
     *   no-cue + review flag.
     * - A bare-digit reference is rejected (never inserted): OTP
     *   digits are never reused as a reference.
     * - Any direction mismatch falls back to the no-cue form +
     *   review flag.
     */
    fun buildConstructedText(
        direction: Direction,
        amountMinor: Long,
        maskedAccount: String?,
        reference: String?,
    ): ConstructedText {
        var needsReview = false
        // Sanitize inserted tokens: a token containing a
        // direction-list word as a substring is omitted (F49).
        // A direction-word sanitisation forces the NO-CUE form
        // (F49, normative): the inserted token carried a cue
        // that conflicts with the intended direction, so the
        // constructed text must not carry any cue at all.
        // A bare-digit rejection does NOT force no-cue — the
        // directional template still classifies correctly.
        var directionWordSanitised = false
        var safeAccount = maskedAccount
        if (safeAccount != null && DirectionHeuristic.containsDirectionWord(safeAccount)) {
            safeAccount = null
            needsReview = true
            directionWordSanitised = true
        }
        var safeReference = reference
        if (safeReference != null &&
            (safeReference.all { it.isDigit() } || DirectionHeuristic.containsDirectionWord(safeReference))
        ) {
            // Bare-digit reference rejected: OTP digits are never
            // reused as a reference.
            if (DirectionHeuristic.containsDirectionWord(safeReference)) {
                directionWordSanitised = true
            }
            safeReference = null
            needsReview = true
        }

        val amountText = "Rp" + formatMinor(amountMinor)
        val refToken = safeReference?.let { " Ref $it" }.orEmpty()
        if (directionWordSanitised) {
            val account = safeAccount?.let { " $it" }.orEmpty()
            return ConstructedText("$amountText$account$refToken".trim(), true)
        }
        val directional = when (direction) {
            Direction.OUT -> {
                val account = safeAccount?.let { " $it" }.orEmpty()
                "Transfer ke$account $amountText$refToken"
            }
            Direction.IN -> {
                val account = safeAccount?.let { " dari $it" }.orEmpty()
                "Diterima $amountText$account$refToken"
            }
            Direction.UNKNOWN -> {
                val account = safeAccount?.let { " $it" }.orEmpty()
                "$amountText$account$refToken"
            }
        }
        // Self-check: the constructed text must classify to the
        // intended direction (or UNKNOWN for the no-cue form).
        val classified = DirectionHeuristic.classify(directional)
        if (classified == direction) {
            return ConstructedText(directional.trim(), needsReview)
        }
        // Mismatch ⇒ no-cue form + review flag.
        val account = safeAccount?.let { " $it" }.orEmpty()
        val noCue = "$amountText$account$refToken".trim()
        return ConstructedText(noCue, true)
    }

    /** Formats minor units with Indonesian thousands separators (100800 → "100.800"). */
    private fun formatMinor(amountMinor: Long): String {
        val s = amountMinor.toString()
        val out = StringBuilder()
        var count = 0
        for (i in s.length - 1 downTo 0) {
            out.append(s[i])
            count++
            if (count == 3 && i > 0) {
                out.append('.')
                count = 0
            }
        }
        return out.reverse().toString()
    }
}
