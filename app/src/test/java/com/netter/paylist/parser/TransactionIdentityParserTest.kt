package com.netter.paylist.parser

import com.netter.paylist.parser.TransactionIdentityParser.FieldPattern
import com.netter.paylist.parser.TransactionIdentityParser.IdentityField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage D D2b: the P3 identity parser (design §3.1.3, F45).
 *
 * F45 — per-source pattern confirmation: an unconfirmed pattern
 * stays OFF and its fields are omitted; bare-digit input never
 * yields a reference (OTP is never a reference). The default
 * table has NO confirmed pattern (no owner-supplied samples
 * yet), so the default parser extracts nothing; tests inject
 * confirmed patterns to verify the extraction logic itself.
 */
class TransactionIdentityParserTest {

    companion object {
        const val GOPAY = "com.gojek.gopaymerchant"
    }

    @Test
    fun f45_defaultTable_extractsNothing() {
        val parser = TransactionIdentityParser()
        val text = "Pembayaran Rp100.800 berhasil. No. Ref 482913. ID Transaksi TRX-1."
        val result = parser.parse(GOPAY, text)
        assertTrue("no confirmed pattern exists yet — all fields omitted", result.isEmpty)
        assertNull(result.bankReference)
        assertNull(result.orderNumber)
    }

    @Test
    fun f45_unconfirmedPattern_staysOff() {
        val tables = mapOf(
            GOPAY to listOf(
                FieldPattern(IdentityField.BANK_REFERENCE, "No. Ref", confirmed = false),
            ),
        )
        val parser = TransactionIdentityParser(tables)
        val result = parser.parse(GOPAY, "Pembayaran berhasil. No. Ref 482913.")
        assertTrue(result.isEmpty)
    }

    @Test
    fun f45_confirmedPattern_extractsLabelledReference() {
        val tables = mapOf(
            GOPAY to listOf(
                FieldPattern(IdentityField.BANK_REFERENCE, "No. Ref", confirmed = true),
            ),
        )
        val parser = TransactionIdentityParser(tables)
        val result = parser.parse(GOPAY, "Pembayaran Rp100.800 berhasil. No. Ref 482913.")
        assertEquals("482913", result.bankReference)
        val field = result.fields[IdentityField.BANK_REFERENCE]!!
        assertEquals(0.9, field.confidence, 0.0)
        assertTrue(field.sourceNote.contains("No. Ref"))
    }

    @Test
    fun f45_bareDigits_neverAReference() {
        // Digits without a confirming label may be an OTP — no
        // pattern may treat them as a reference, across OTP-shaped
        // strings.
        val tables = mapOf(
            GOPAY to listOf(
                FieldPattern(IdentityField.BANK_REFERENCE, "No. Ref", confirmed = true),
                FieldPattern(IdentityField.ORDER_NUMBER, "No. Pesanan", confirmed = true),
            ),
        )
        val parser = TransactionIdentityParser(tables)
        for (text in listOf(
            "Kode OTP 482913. Pembayaran Rp100.800 berhasil",
            "482913 adalah kode verifikasi Anda",
            "Pembayaran Rp100.800 berhasil 739201",
        )) {
            val result = parser.parse(GOPAY, text)
            assertTrue("bare digits must never yield a reference: $text", result.isEmpty)
        }
    }

    @Test
    fun f45_valueOverlappingConfirmedSpan_dropped() {
        // Even a labelled value is dropped when it overlaps a
        // confirmed secret span: "OTP is never a reference".
        val tables = mapOf(
            GOPAY to listOf(
                FieldPattern(IdentityField.BANK_REFERENCE, "No. Ref", confirmed = true),
            ),
        )
        val parser = TransactionIdentityParser(tables)
        val text = "No. Ref Kode OTP 482913."
        // The confirmed OTP span covers "482913" (indices 16..21).
        val otpStart = text.indexOf("482913")
        val result = parser.parse(GOPAY, text, confirmedSpans = listOf(otpStart until otpStart + 6))
        assertTrue(result.isEmpty)
    }

    @Test
    fun accountValue_maskedToLastFour() {
        val tables = mapOf(
            GOPAY to listOf(
                FieldPattern(IdentityField.SOURCE_ACCOUNT_MASKED, "dari rekening", confirmed = true),
            ),
        )
        val parser = TransactionIdentityParser(tables)
        val result = parser.parse(GOPAY, "Pembayaran dari rekening 1234567890 berhasil.")
        assertEquals("****7890", result.get(IdentityField.SOURCE_ACCOUNT_MASKED))
    }

    @Test
    fun accountValue_tooShort_omitted() {
        val tables = mapOf(
            GOPAY to listOf(
                FieldPattern(IdentityField.SOURCE_ACCOUNT_MASKED, "dari rekening", confirmed = true),
            ),
        )
        val parser = TransactionIdentityParser(tables)
        val result = parser.parse(GOPAY, "Pembayaran dari rekening 12 berhasil.")
        assertNull(result.get(IdentityField.SOURCE_ACCOUNT_MASKED))
    }

    @Test
    fun valueShape_mustMatch() {
        val tables = mapOf(
            GOPAY to listOf(
                FieldPattern(
                    IdentityField.ORDER_NUMBER, "No. Pesanan", confirmed = true,
                    valueShape = Regex("INV-\\d+"),
                ),
            ),
        )
        val parser = TransactionIdentityParser(tables)
        assertEquals(
            "INV-123",
            parser.parse(GOPAY, "No. Pesanan INV-123.").orderNumber,
        )
        assertNull(parser.parse(GOPAY, "No. Pesanan ABC-123.").orderNumber)
    }

    @Test
    fun unknownSource_extractsNothing() {
        val parser = TransactionIdentityParser()
        val result = parser.parse("com.unknown.bank", "No. Ref 482913.")
        assertTrue(result.isEmpty)
    }
}
