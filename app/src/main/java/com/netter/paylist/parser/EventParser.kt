package com.netter.paylist.parser

/**
 * Parser interface (design §2, file plan §B).
 *
 * Stage A: the interface + its input/output types only. The implementation
 * (`PaymentParser`, extracting the v2.3 `parseAmount` behaviour from
 * `PaylistListenerService`) and `ParserRegistry` land in Stage D; until
 * then the listener keeps its existing inline parser unchanged.
 *
 * Parsing runs in memory over transient raw text. A parser never persists
 * raw text and never treats a bare digit string as a reference — "OTP is
 * never a reference" (design §7.1). Fields that cannot be verified are
 * omitted, never invented (design §3.1.6).
 */

/** Raw notification fields handed to a parser (pre-redaction, transient). */
data class ParserInput(
    val packageName: String,
    val title: String,
    val text: String,
    val bigText: String,
    /** Notification `postTime` (epoch ms); part of the legacy `dedupe_key`. */
    val postTimeMs: Long,
) {
    /** Full raw combined text, as the v2.3 listener builds it. */
    val combined: String get() = "$title $text $bigText"
}

/**
 * Structured result of parsing one notification. Stage A carries amount +
 * currency only; Stage D adds the amount-token list and the ambiguity
 * flag (design §7.6); the P3 identity fields (`bank_reference`,
 * `order_number`, sender/recipient, purpose, source account — design
 * §3.1) are added by Stage D, gated by the §3.1.3 evidence bar.
 */
data class ParsedEvent(
    /** Amount in integer minor units (rupiah), or null when unparseable. */
    val amount: Long?,
    /** Currency code as captured (e.g. `IDR`), or null. */
    val currency: String?,
    /**
     * True when ≥2 currency tokens with different values were found
     * (design §7.6): the event is `amount_ambiguous` and its
     * delivery is created `NEEDS_REVIEW`, never `PENDING`.
     */
    val amountAmbiguous: Boolean = false,
    /** Every currency token found, in position order (empty when none). */
    val tokens: List<AmountToken> = emptyList(),
)

/**
 * Parses one notification into structured fields.
 *
 * Returns null when the notification carries no payment (no parseable
 * amount) — the v2.3 capture rule: such notifications are never enqueued
 * (design §7.3A(j)).
 */
interface EventParser {
    fun parse(input: ParserInput): ParsedEvent?
}
