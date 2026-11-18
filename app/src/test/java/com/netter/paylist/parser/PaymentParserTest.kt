package com.netter.paylist.parser

import com.netter.paylist.PaylistListenerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression cases for the EXISTING v2.3 parser behaviour
 * (`PaylistListenerService.parseAmount`, first-token rule, design §7.6).
 * No behaviour change is authorised in Stage A: these tests pin what the
 * v2.3 code does today, quirks included, so Stage D's `PaymentParser`
 * extraction can prove it changed nothing.
 *
 * `parseAmount` is pure JVM (java.util.regex only), so the test
 * instantiates the service directly without Robolectric; no Android
 * method is invoked.
 *
 * F45 (per-source pattern confirmation) is Stage D — not here.
 */
class PaymentParserTest {

    private val service = PaylistListenerService()

    // --- Rp pattern ---

    @Test
    fun rpDotThousands() {
        assertEquals(100800L, service.parseAmount("Pembayaran Rp100.800 berhasil"))
    }

    @Test
    fun rpWithSpaceAndMultipleGroups() {
        assertEquals(1234567L, service.parseAmount("Rp 1.234.567"))
    }

    @Test
    fun rpPlainDigits() {
        assertEquals(100000L, service.parseAmount("Rp100000"))
    }

    @Test
    fun rpSpaceThousandsSeparator() {
        assertEquals(100800L, service.parseAmount("Rp 100 800"))
    }

    @Test
    fun rpTenMillion() {
        assertEquals(10000000L, service.parseAmount("Rp10.000.000"))
    }

    @Test
    fun rpZero() {
        assertEquals(0L, service.parseAmount("Rp 0"))
    }

    @Test
    fun rpTrailingCentsNotPartOfToken() {
        // ",50" has only 2 digits after the separator, so it is not a
        // thousands group: the token ends at "1.000.000".
        assertEquals(1000000L, service.parseAmount("Rp1.000.000,50"))
    }

    @Test
    fun rpWithoutMultiplierWord() {
        // Known v2.3 quirk (13 Nov 2026 GoPay ad): "Rp49 ribu" parses as
        // 49 — the parser takes the digits and ignores the word "ribu".
        // Pinned as-is; fixing it is NOT authorised in Stage A.
        assertEquals(49L, service.parseAmount("Spiker cuma Rp49 ribu!"))
    }

    // --- IDR pattern ---

    @Test
    fun idrEnglishDecimal() {
        assertEquals(36000L, service.parseAmount("IDR 36,000.00"))
    }

    @Test
    fun idrIndonesianDecimal() {
        assertEquals(1234567L, service.parseAmount("IDR 1.234.567,00"))
    }

    @Test
    fun idrThreeDigitGroupIsNotDecimal() {
        // "36.000": 3 digits after the last separator, so it is a
        // thousands group, not cents.
        assertEquals(36000L, service.parseAmount("IDR 36.000"))
    }

    @Test
    fun idrTwoDigitDecimalOnly() {
        assertEquals(99L, service.parseAmount("IDR 99.99"))
    }

    @Test
    fun idrCaseInsensitive() {
        assertEquals(10000L, service.parseAmount("idr 10.000"))
    }

    // --- First-token rule (design §7.6) ---

    @Test
    fun firstTokenWins_balanceBeforePayment() {
        // The documented mis-pick: a balance shown before the payment
        // amount is parsed as the amount. Pinned as-is; the v1.1
        // NEEDS_REVIEW guard (Stage D) is what stops it being sent.
        assertEquals(
            500000L,
            service.parseAmount("Saldo Rp500.000. Pembayaran Rp100.000 berhasil")
        )
    }

    @Test
    fun firstTokenWins_rpBeforeIdr() {
        assertEquals(100000L, service.parseAmount("Rp100.000 IDR 50.000"))
    }

    @Test
    fun firstTokenWins_idrBeforeRp() {
        assertEquals(50000L, service.parseAmount("IDR 50.000 Rp100.000"))
    }

    @Test
    fun firstTokenWins_sameValueTwice() {
        assertEquals(100000L, service.parseAmount("Rp100.000 Rp100.000"))
    }

    // --- No amount ---

    @Test
    fun noAmount_returnsNull() {
        assertNull(service.parseAmount("Pembayaran berhasil"))
    }

    @Test
    fun bareRp_returnsNull() {
        assertNull(service.parseAmount("Rp"))
    }

    @Test
    fun bareDigits_returnsNull() {
        // Digits without an Rp/IDR marker are not an amount.
        assertNull(service.parseAmount("Kode 482913"))
    }

    @Test
    fun emptyText_returnsNull() {
        assertNull(service.parseAmount(""))
    }
}
