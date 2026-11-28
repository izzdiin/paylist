package com.netter.paylist.parser

/**
 * Per-source transaction-identity extraction (design §3.1, P3 —
 * owner-decided 2026-11-15, comment id 6903307191).
 *
 * Extracts the structured P3 fields from transient notification
 * text: `bank_reference`, `order_number`, sender/recipient,
 * purpose, source account. Patterns are configured per source
 * package (GoPay, myBCA, …), not one global regex; each pattern
 * is gated by the §3.1.3 evidence bar (≥2 privacy-sanitised real
 * samples per pattern per source, from different days/amounts,
 * showing the exact label string). **Unconfirmed patterns stay OFF
 * and their fields are omitted** — no confirmed pattern exists yet
 * (no owner-supplied samples), so the default table extracts
 * nothing. Fields are omitted when absent, never invented.
 *
 * Hard rules (§3.1.3, §3.1.6):
 * - Labelled spans only: a value is extracted only after its
 *   confirming label. **No bare-digit fallback, ever** — digits
 *   without a confirming label may be an OTP. "OTP is never a
 *   reference" applies to every new field.
 * - A value overlapping a confirmed secret span is dropped, even
 *   when labelled (the caller supplies the spans from
 *   `PrivacySpanScan` over the same combined text).
 * - Account numbers are masked to the last 4 digits at extraction;
 *   the full value never leaves this function.
 * - `dedupe_key` computation is untouched: P3 fields never enter
 *   the key (F-P1-02).
 *
 * The parser is pure (no Android, no I/O). The pattern table is a
 * constructor parameter so tests can inject confirmed patterns;
 * production uses [DEFAULT_TABLES].
 */
class TransactionIdentityParser(
    private val tables: Map<String, List<FieldPattern>> = DEFAULT_TABLES,
) {

    /** P3 identity fields (design §3.1 field model). */
    enum class IdentityField {
        BANK_REFERENCE,
        ORDER_NUMBER,
        SENDER_NAME,
        SENDER_ACCOUNT_MASKED,
        RECIPIENT_NAME,
        RECIPIENT_ACCOUNT_MASKED,
        PURPOSE_TEXT,
        SOURCE_ACCOUNT_MASKED,
    }

    /**
     * One per-source extraction pattern (§3.1.3): the label string
     * that must appear in the text, and the field it fills.
     * [confirmed] is the §3.1.3 evidence bar — unconfirmed
     * patterns stay OFF. [valueShape] optionally constrains the
     * value after the label (full-match regex).
     */
    data class FieldPattern(
        val field: IdentityField,
        val label: String,
        val confirmed: Boolean,
        val valueShape: Regex? = null,
    )

    /** One extracted field, with per-field confidence + source note. */
    data class ExtractedField(
        val field: IdentityField,
        val value: String,
        /** Labelled confirmed match: 0.9, never 1.0 (a label is evidence, not proof). */
        val confidence: Double,
        /** Source note for the audit row, e.g. "label 'No. Ref' (com.gojek.gopaymerchant)". */
        val sourceNote: String,
    )

    /** The extraction result: the fields found, keyed by field. Absent fields are omitted. */
    data class IdentityExtraction(
        val fields: Map<IdentityField, ExtractedField>,
    ) {
        fun get(field: IdentityField): String? = fields[field]?.value

        val bankReference: String? get() = get(IdentityField.BANK_REFERENCE)
        val orderNumber: String? get() = get(IdentityField.ORDER_NUMBER)
        val isEmpty: Boolean get() = fields.isEmpty()
    }

    companion object {
        /** Max value length (a labelled value longer than this is truncated, never grown). */
        const val MAX_VALUE_CHARS: Int = 64

        /** Confidence for a labelled confirmed match. */
        const val LABELLED_CONFIDENCE: Double = 0.9

        /**
         * The default pattern table: the §3.1 label vocabulary for
         * the known sources — ALL UNCONFIRMED (no owner-supplied
         * samples yet), so the default parser extracts nothing
         * (F45). A pattern becomes `confirmed = true` only after
         * the §3.1.3 evidence bar is met for that source+label.
         *
         * Sources keyed by package as seen in the repo (GoPay /
         * GoPay Merchant). myBCA has no package registered here —
         * its package + labels await owner samples, never guessed.
         */
        val DEFAULT_TABLES: Map<String, List<FieldPattern>> = mapOf(
            "com.gojek.gopaymerchant" to listOf(
                FieldPattern(IdentityField.BANK_REFERENCE, "No. Ref", confirmed = false),
                FieldPattern(IdentityField.BANK_REFERENCE, "Ref", confirmed = false),
                FieldPattern(IdentityField.BANK_REFERENCE, "ID Transaksi", confirmed = false),
                FieldPattern(IdentityField.BANK_REFERENCE, "Kode Transaksi", confirmed = false),
            ),
            "com.gojek.gopay" to listOf(
                FieldPattern(IdentityField.BANK_REFERENCE, "No. Ref", confirmed = false),
                FieldPattern(IdentityField.BANK_REFERENCE, "Ref", confirmed = false),
                FieldPattern(IdentityField.BANK_REFERENCE, "ID Transaksi", confirmed = false),
                FieldPattern(IdentityField.BANK_REFERENCE, "Kode Transaksi", confirmed = false),
            ),
        )
    }

    /**
     * Extracts the P3 fields from [combinedText] (`"$title $text
     * $bigText"`, transient raw text at capture).
     *
     * @param confirmedSpans ranges of confirmed secret spans in
     * [combinedText] (from `PrivacySpanScan` over the same text);
     * any value overlapping one is dropped.
     */
    fun parse(
        sourcePackage: String,
        combinedText: String,
        confirmedSpans: List<IntRange> = emptyList(),
    ): IdentityExtraction {
        val patterns = tables[sourcePackage].orEmpty()
        val found = LinkedHashMap<IdentityField, ExtractedField>()
        val lower = combinedText.lowercase()
        for (pattern in patterns) {
            if (!pattern.confirmed) continue
            if (found.containsKey(pattern.field)) continue
            val value = extractAfterLabel(combinedText, lower, pattern, confirmedSpans)
                ?: continue
            found[pattern.field] = ExtractedField(
                field = pattern.field,
                value = value,
                confidence = LABELLED_CONFIDENCE,
                sourceNote = "label '${pattern.label}' ($sourcePackage)",
            )
        }
        return IdentityExtraction(found)
    }

    /**
     * Finds [pattern]'s label (case-insensitive) and reads the
     * value after it: separators (`:`, `-`, whitespace) skipped,
     * then up to the next segment delimiter (`.!?;\n`) or
     * [MAX_VALUE_CHARS]. Returns null when the label is absent,
     * the value is empty, the value fails [FieldPattern.valueShape],
     * or the value overlaps a confirmed secret span.
     */
    private fun extractAfterLabel(
        text: String,
        lower: String,
        pattern: FieldPattern,
        confirmedSpans: List<IntRange>,
    ): String? {
        val labelAt = lower.indexOf(pattern.label.lowercase())
        if (labelAt < 0) return null
        var start = labelAt + pattern.label.length
        // Skip separators between the label and the value.
        while (start < text.length && (text[start] == ':' || text[start] == '-' || text[start].isWhitespace())) {
            start++
        }
        var end = start
        while (end < text.length && end - start < MAX_VALUE_CHARS &&
            text[end] != '.' && text[end] != '!' && text[end] != '?' &&
            text[end] != ';' && text[end] != '\n'
        ) {
            end++
        }
        val raw = text.substring(start, end).trim()
        if (raw.isEmpty()) return null
        // The value must not overlap a confirmed secret span —
        // OTP digits are never a reference, even when labelled.
        val valueRange = start until end
        if (confirmedSpans.any { it.first < valueRange.last + 1 && valueRange.first < it.last + 1 }) {
            return null
        }
        if (pattern.valueShape != null && !pattern.valueShape.matches(raw)) return null
        return when (pattern.field) {
            IdentityField.SENDER_ACCOUNT_MASKED,
            IdentityField.RECIPIENT_ACCOUNT_MASKED,
            IdentityField.SOURCE_ACCOUNT_MASKED,
            -> maskAccount(raw)
            else -> raw
        }
    }

    /**
     * Masks an account value to the last 4 digits (`****1234`).
     * Returns null when the value has fewer than 4 digits
     * (unverified — omitted, never guessed).
     */
    private fun maskAccount(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 4) return null
        return "****" + digits.takeLast(4)
    }
}
