package com.netter.paylist.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cases for the v2.3 parser behaviour, now owned by the
 * extracted `PaymentParser` (Stage D; first-token rule, design §7.6).
 * The extraction changed nothing: these tests pin what the v2.3 code
 * does, quirks included — including the documented balance-before-
 * payment mis-pick, which the v1.1 NEEDS_REVIEW guard (not the
 * parser) stops from being sent.
 *
 * `PaymentParser` is pure JVM (java.util.regex only); no Android
 * method is invoked.
 *
 * F45 (per-source pattern confirmation) is Stage D — see the F45
 * tests at the bottom of this file.
 */
class PaymentParserTest {

    private val parser = PaymentParser()

    private fun input(text: String) = ParserInput(
        packageName = "com.gojek.gopaymerchant",
        title = "",
        text = text,
        bigText = "",
        postTimeMs = 1_000L,
    )

    // --- Rp pattern ---

    @Test
    fun rpDotThousands() {
        assertEquals(100800L, parser.parseAmount("Pembayaran Rp100.800 berhasil"))
    }

    @Test
    fun rpWithSpaceAndMultipleGroups() {
        assertEquals(1234567L, parser.parseAmount("Rp 1.234.567"))
    }

    @Test
    fun rpPlainDigits() {
        assertEquals(100000L, parser.parseAmount("Rp100000"))
    }

    @Test
    fun rpSpaceThousandsSeparator() {
        assertEquals(100800L, parser.parseAmount("Rp 100 800"))
    }

    @Test
    fun rpTenMillion() {
        assertEquals(10000000L, parser.parseAmount("Rp10.000.000"))
    }

    @Test
    fun rpZero() {
        assertEquals(0L, parser.parseAmount("Rp 0"))
    }

    @Test
    fun rpTrailingCentsNotPartOfToken() {
        // ",50" has only 2 digits after the separator, so it is not a
        // thousands group: the token ends at "1.000.000".
        assertEquals(1000000L, parser.parseAmount("Rp1.000.000,50"))
    }

    @Test
    fun rpWithoutMultiplierWord() {
        // Known v2.3 quirk (13 Nov 2026 GoPay ad): "Rp49 ribu" parses as
        // 49 — the parser takes the digits and ignores the word "ribu".
        // Pinned as-is; fixing it is NOT authorised in Stage A.
        assertEquals(49L, parser.parseAmount("Spiker cuma Rp49 ribu!"))
    }

    // --- IDR pattern ---

    @Test
    fun idrEnglishDecimal() {
        assertEquals(36000L, parser.parseAmount("IDR 36,000.00"))
    }

    @Test
    fun idrIndonesianDecimal() {
        assertEquals(1234567L, parser.parseAmount("IDR 1.234.567,00"))
    }

    @Test
    fun idrThreeDigitGroupIsNotDecimal() {
        // "36.000": 3 digits after the last separator, so it is a
        // thousands group, not cents.
        assertEquals(36000L, parser.parseAmount("IDR 36.000"))
    }

    @Test
    fun idrTwoDigitDecimalOnly() {
        assertEquals(99L, parser.parseAmount("IDR 99.99"))
    }

    @Test
    fun idrCaseInsensitive() {
        assertEquals(10000L, parser.parseAmount("idr 10.000"))
    }

    // --- First-token rule (design §7.6) ---

    @Test
    fun firstTokenWins_balanceBeforePayment() {
        // The documented mis-pick: a balance shown before the payment
        // amount is parsed as the amount. Pinned as-is; the v1.1
        // NEEDS_REVIEW guard (Stage D) is what stops it being sent.
        assertEquals(
            500000L,
            parser.parseAmount("Saldo Rp500.000. Pembayaran Rp100.000 berhasil")
        )
    }

    @Test
    fun firstTokenWins_rpBeforeIdr() {
        assertEquals(100000L, parser.parseAmount("Rp100.000 IDR 50.000"))
    }

    @Test
    fun firstTokenWins_idrBeforeRp() {
        assertEquals(50000L, parser.parseAmount("IDR 50.000 Rp100.000"))
    }

    @Test
    fun firstTokenWins_sameValueTwice() {
        assertEquals(100000L, parser.parseAmount("Rp100.000 Rp100.000"))
    }

    // --- No amount ---

    @Test
    fun noAmount_returnsNull() {
        assertNull(parser.parseAmount("Pembayaran berhasil"))
    }

    @Test
    fun bareRp_returnsNull() {
        assertNull(parser.parseAmount("Rp"))
    }

    @Test
    fun bareDigits_returnsNull() {
        // Digits without an Rp/IDR marker are not an amount.
        assertNull(parser.parseAmount("Kode 482913"))
    }

    @Test
    fun emptyText_returnsNull() {
        assertNull(parser.parseAmount(""))
    }

    // --- Token list + ambiguity (Stage D, design §7.6) ---

    @Test
    fun parse_singleAmount_notAmbiguous() {
        val parsed = parser.parse(input("Pembayaran Rp100.800 berhasil"))!!
        assertEquals(100800L, parsed.amount)
        assertEquals("IDR", parsed.currency)
        assertFalse(parsed.amountAmbiguous)
        assertEquals(1, parsed.tokens.size)
        assertEquals(100800L, parsed.tokens[0].value)
    }

    @Test
    fun parse_twoDifferentAmounts_ambiguous() {
        val parsed = parser.parse(input("Saldo Rp500.000. Pembayaran Rp100.000 berhasil"))!!
        // First-token rule: the amount is still the first token…
        assertEquals(500000L, parsed.amount)
        // …but the event is flagged ambiguous for NEEDS_REVIEW.
        assertTrue(parsed.amountAmbiguous)
        assertEquals(listOf(500000L, 100000L), parsed.tokens.map { it.value })
    }

    @Test
    fun parse_twoSameAmounts_notAmbiguous() {
        val parsed = parser.parse(input("Rp100.000 Rp100.000"))!!
        assertEquals(100000L, parsed.amount)
        assertFalse(parsed.amountAmbiguous)
        assertEquals(2, parsed.tokens.size)
    }

    @Test
    fun parse_noAmount_returnsNull() {
        assertNull(parser.parse(input("Pembayaran berhasil")))
    }

    @Test
    fun parseTokens_positionsInOrder() {
        val tokens = parser.parseTokens("IDR 50.000 lalu Rp100.000")
        assertEquals(listOf(50000L, 100000L), tokens.map { it.value })
        assertTrue(tokens[0].start < tokens[1].start)
    }

    // --- F45: per-source pattern confirmation (Stage D) ---
    // The v2.3 Rp/IDR patterns are the confirmed baseline for every
    // source: the same first-token rule applies to GoPay Merchant
    // and to bank apps (IDR form). A per-source pattern that
    // deviates from this baseline needs its own F45 confirmation
    // (≥2 sanitised real samples) before it may ship; until then
    // the baseline parser below is the only parser in the registry.

    @Test
    fun f45_baselineParserCoversGoPayAndBank() {
        val registry = ParserRegistry()
        val gopay = registry.parse(
            ParserInput("com.gojek.gopaymerchant", "", "Pembayaran Rp100.800 berhasil", "", 1_000L)
        )
        assertEquals(100800L, gopay?.amount)
        val bank = registry.parse(
            ParserInput("com.bca", "", "Transfer IDR 36,000.00 berhasil", "", 1_000L)
        )
        assertEquals(36000L, bank?.amount)
    }
}
