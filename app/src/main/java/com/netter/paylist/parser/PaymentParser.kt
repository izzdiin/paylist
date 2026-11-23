package com.netter.paylist.parser

import java.util.regex.Pattern

/**
 * One currency token found in notification text: its normalised
 * rupiah value and its position in the combined text (design §7.6:
 * the review surface shows the token list with positions, never raw
 * text).
 */
data class AmountToken(
    /** Normalised rupiah value (thousands separators stripped, IDR cents dropped). */
    val value: Long,
    /** Start offset in the combined text. */
    val start: Int,
    /** End offset (exclusive) in the combined text. */
    val end: Int,
    /** The raw matched token (kept transiently; never persisted raw). */
    val raw: String,
)

/**
 * The v2.3 payment parser, extracted verbatim from
 * `PaylistListenerService` in Stage D (file plan §B; design §7.6).
 * No behaviour change: the first-token rule, the Rp/IDR patterns and
 * the IDR normalisation are byte-for-byte the v2.3 rules, pinned by
 * `PaymentParserTest`.
 *
 * Stage D additions (design §7.6, normative):
 * - [parseTokens] lists every currency token (Rp and IDR) with its
 *   position, so capture can detect two-amount notifications:
 *   ≥2 tokens with different values ⇒ `amount_ambiguous`, the
 *   delivery is created `NEEDS_REVIEW`, never `PENDING`.
 * - [parse] fills [ParsedEvent.amountAmbiguous] and
 *   [ParsedEvent.tokens]; the `amount` field itself is still the
 *   first token (the v2.3 rule, quirks included).
 *
 * Parsing runs in memory over transient raw text. A parser never
 * persists raw text and never treats a bare digit string as a
 * reference — "OTP is never a reference" (design §7.1).
 */
class PaymentParser : EventParser {

    companion object {
        /** Parser version recorded on events parsed by this parser. */
        const val PARSER_VERSION: String = "payment-v1"
    }

    private val amountPattern =
        Pattern.compile("Rp\\s?([0-9]{1,3}(?:[.,\\s][0-9]{3})+|[0-9]+)")
    private val idrPattern =
        Pattern.compile(
            "IDR\\s?([0-9]{1,3}(?:[.,][0-9]{3})+(?:[.,][0-9]{2})?|[0-9]+(?:[.,][0-9]{2})?)",
            Pattern.CASE_INSENSITIVE
        )

    override fun parse(input: ParserInput): ParsedEvent? {
        val amount = parseAmount(input.combined) ?: return null
        val tokens = parseTokens(input.combined)
        val distinct = tokens.map { it.value }.distinct()
        return ParsedEvent(
            amount = amount,
            currency = "IDR",
            amountAmbiguous = distinct.size > 1,
            tokens = tokens,
        )
    }

    /**
     * Ambil nominal pertama dari teks: pola "Rp" atau "IDR", mana yang muncul
     * lebih dulu. Pemisah ribuan (titik/spasi/koma) dibuang; untuk IDR,
     * kelompok 2 digit terakhir sesudah pemisah terakhir dianggap desimal
     * ("36,000.00" -> 36000; "1.234.567,00" -> 1234567; "36.000" -> 36000).
     * Verbatim v2.3 (`PaylistListenerService.parseAmount`).
     */
    fun parseAmount(text: String): Long? {
        val rm = amountPattern.matcher(text)
        val rFound = rm.find()
        val im = idrPattern.matcher(text)
        val iFound = im.find()
        if (!rFound && !iFound) return null
        if (rFound && (!iFound || rm.start() <= im.start())) {
            val digits = rm.group(1).orEmpty().replace(Regex("[.,\\s]"), "")
            return digits.toLongOrNull()
        }
        return normalizeIdr(im.group(1).orEmpty())
    }

    /** Normalkan token nominal IDR (lihat parseAmount) menjadi rupiah bulat. */
    private fun normalizeIdr(token: String): Long? {
        var t = token.trim()
        if (t.isEmpty()) return null
        val lastSep = maxOf(t.lastIndexOf('.'), t.lastIndexOf(','))
        if (lastSep >= 0 && t.length - lastSep - 1 == 2) {
            t = t.substring(0, lastSep)
        }
        val digits = t.replace(Regex("[.,\\s]"), "")
        return digits.toLongOrNull()
    }

    /**
     * Every currency token in the text, in position order: Rp tokens
     * (thousands separators stripped) and IDR tokens (cents dropped
     * per [normalizeIdr]). Tokens that fail to normalise to a number
     * are skipped — they are not amounts.
     */
    fun parseTokens(text: String): List<AmountToken> {
        val out = mutableListOf<AmountToken>()
        val rm = amountPattern.matcher(text)
        while (rm.find()) {
            val digits = rm.group(1).orEmpty().replace(Regex("[.,\\s]"), "")
            val value = digits.toLongOrNull() ?: continue
            out.add(AmountToken(value, rm.start(), rm.end(), rm.group(0).orEmpty()))
        }
        val im = idrPattern.matcher(text)
        while (im.find()) {
            val value = normalizeIdr(im.group(1).orEmpty()) ?: continue
            out.add(AmountToken(value, im.start(), im.end(), im.group(0).orEmpty()))
        }
        return out.sortedBy { it.start }
    }
}
