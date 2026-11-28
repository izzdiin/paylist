package com.netter.paylist.parser

/**
 * Parser selection and priority (file plan §B, Stage D).
 *
 * Parsers are tried in order; the first that returns a non-null
 * [ParsedEvent] wins. The v1.1 registry holds the single
 * [PaymentParser] (Rp/IDR first-token rule); per-source parsers
 * (F45) register ahead of it when their source package matches.
 */
class ParserRegistry(
    private val parsers: List<EventParser> = listOf(PaymentParser()),
) {
    fun parse(input: ParserInput): ParsedEvent? {
        for (parser in parsers) {
            val parsed = parser.parse(input)
            if (parsed != null) return parsed
        }
        return null
    }
}
