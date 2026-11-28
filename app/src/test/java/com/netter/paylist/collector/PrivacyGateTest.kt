package com.netter.paylist.collector

import com.netter.paylist.parser.Direction
import com.netter.paylist.parser.DirectionHeuristic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage D D2: the PrivacyGate fixture set (design §12.3, C20).
 *
 * Owns F1–F11 (F11 superseded — whole-event drop for inseparable
 * spans was replaced by F13 + Option A′), F13–F14, F19 (the
 * gate-applicable parts), F35, and the capture-side F43 cases
 * (B1/B1b/B5a; the claim-side cases live in the processor/sender
 * tests). F50 (the Stage C activation gate) is owned by
 * `LegacyQueuePrivacyGateTest`; this file independently
 * re-verifies the same invariants against the full Stage D policy.
 *
 * The gate is pure (no Android, no I/O); these tests run on the JVM.
 */
class PrivacyGateTest {

    private val gate = PrivacyGate()

    private fun snapshot(
        title: String = "",
        text: String = "",
        bigText: String = "",
        postTime: Long = 1_760_000_000_000L,
    ) = NotificationSnapshot(
        key = "key-$postTime",
        packageName = "com.gojek.gopaymerchant",
        postTimeMs = postTime,
        flags = 0,
        title = title,
        text = text,
        bigText = bigText,
        capturedAtMs = postTime + 1_000L,
    )

    // --- F1: incoming transfer — amount/direction/reference preserved ---

    @Test
    fun f1_incomingTransfer_preserved_dedupeIdenticalLiveVsRescan() {
        val s = snapshot(
            title = "Dana masuk",
            text = "Diterima Rp100.800 dari Budi. No. Ref 482913",
        )
        val result = gate.gate(s, 100800L)
        assertEquals(PrivacyGate.Decision.ALLOW, result.decision)
        assertFalse("no span to redact", result.redacted)
        assertEquals("Diterima Rp100.800 dari Budi. No. Ref 482913", result.redactedText)
        assertEquals(Direction.IN, DirectionHeuristic.classify(s.combined))

        // dedupe_key identical live vs rescan: same snapshot, same key.
        val keyLive = CaptureCoordinator.legacyDedupeKey(
            s.packageName, s.postTimeMs, 100800L, s.combined,
        )
        val keyRescan = CaptureCoordinator.legacyDedupeKey(
            s.packageName, s.postTimeMs, 100800L, s.combined,
        )
        assertEquals(keyLive, keyRescan)
    }

    // --- F2: outgoing transfer — direction out, masked account, reference ---

    @Test
    fun f2_outgoingTransfer_directionOut_maskedAccountPreserved() {
        val s = snapshot(
            title = "Transfer berhasil",
            text = "Transfer ke ****1234 Rp50.000. No. Ref 987654",
        )
        val result = gate.gate(s, 50000L)
        assertEquals(PrivacyGate.Decision.ALLOW, result.decision)
        assertFalse(result.redacted)
        assertTrue(result.redactedText.contains("****1234"))
        assertTrue(result.redactedText.contains("No. Ref 987654"))
        assertEquals(Direction.OUT, DirectionHeuristic.classify(s.combined))
    }

    // --- F3: plain financial text passes verbatim (no spans, no redaction) ---

    @Test
    fun f3_plainFinancialText_verbatim_directionPerLists() {
        val s = snapshot(
            title = "Pembayaran berhasil",
            text = "Pembayaran Rp100.800 berhasil",
        )
        val result = gate.gate(s, 100800L)
        assertEquals(PrivacyGate.Decision.ALLOW, result.decision)
        assertEquals("Pembayaran berhasil", result.redactedTitle)
        assertEquals("Pembayaran Rp100.800 berhasil", result.redactedText)
        // "pembayaran" is an OUT word in both catat.php lists.
        assertEquals(Direction.OUT, DirectionHeuristic.classify(s.combined))
    }

    // --- F4/F14: separable OTP+financial — OTP redacted, financial intact ---

    @Test
    fun f4_separableOtp_redacted_financialIntact_otpNeverReference() {
        val s = snapshot(
            title = "Pembayaran berhasil",
            text = "Kode OTP 482913. Pembayaran Rp100.800 berhasil",
        )
        val result = gate.gate(s, 100800L)
        assertEquals(PrivacyGate.Decision.ALLOW, result.decision)
        assertTrue(result.redacted)
        assertTrue(result.hasConfirmedSecret)
        assertFalse("raw OTP must never survive the gate", result.redactedText.contains("482913"))
        assertTrue(result.redactedText.contains("[REDACTED]"))
        assertTrue("amount survives redaction", result.redactedText.contains("Rp100.800"))
        // OTP is never a reference: no reference is extracted from
        // a class-(a) span (the gate emits no reference at all).
        assertFalse(result.redactedText.contains("Ref 482913"))
    }

    // --- F5: pure OTP notification — dropped, counter only ---

    @Test
    fun f5_pureOtp_dropped() {
        val s = snapshot(
            title = "Kode verifikasi",
            text = "Kode OTP Anda 739201. Jangan berikan kepada siapa pun.",
        )
        val result = gate.gate(s, null)
        assertEquals(PrivacyGate.Decision.DROP_PURE_AUTH, result.decision)
        assertEquals("", result.redactedTitle)
        assertEquals("", result.redactedText)
        assertTrue(result.hasConfirmedSecret)
    }

    @Test
    fun f5_pureOtpWithNoAuthSpan_isNoAmountNotPureAuth() {
        val s = snapshot(title = "Promo", text = "Diskon 50% hari ini")
        val result = gate.gate(s, null)
        assertEquals(PrivacyGate.Decision.DROP_NO_AMOUNT, result.decision)
        assertFalse(result.hasConfirmedSecret)
    }

    // --- F6: bare digit span — redacted, flagged ---

    @Test
    fun f6_bareDigitSpan_redacted() {
        // The bare span sits in its own segment (after the "."),
        // so the amount in the earlier clause is NOT a local cue.
        val s = snapshot(
            title = "Pembayaran berhasil",
            text = "Pembayaran Rp100.000 berhasil. 482913",
        )
        val result = gate.gate(s, 100000L)
        assertEquals(PrivacyGate.Decision.ALLOW, result.decision)
        assertTrue(result.redacted)
        assertFalse(result.redactedText.contains("482913"))
        assertTrue(result.redactedText.contains("[REDACTED]"))
        assertTrue(result.redactedText.contains("Rp100.000"))
    }

    // --- F7: C-b3 vs C-b5 — same digit shape, different local context ---

    @Test
    fun f7_sameDigits_differentLocalContext_differentClassification() {
        val labelled = snapshot(text = "Pembayaran Rp100.000 berhasil. No. Ref 482913")
        val labelledResult = gate.gate(labelled, 100000L)
        assertFalse("labelled reference is financial, preserved", labelledResult.redacted)
        assertTrue(labelledResult.redactedText.contains("482913"))

        val otp = snapshot(text = "Pembayaran Rp100.000 berhasil. Kode OTP 482913")
        val otpResult = gate.gate(otp, 100000L)
        assertTrue("OTP digits are confirmed secrets, redacted", otpResult.redacted)
        assertFalse(otpResult.redactedText.contains("482913"))
    }

    // --- F8: direction regression over the verified keyword lists (T24) ---

    @Test
    fun f8_directionRegression_verifiedLists() {
        for (word in DirectionHeuristic.OUT_WORDS) {
            assertEquals(
                "OUT word '$word' must classify OUT",
                Direction.OUT, DirectionHeuristic.classify("Transaksi $word Rp10.000"),
            )
        }
        for (word in DirectionHeuristic.IN_WORDS) {
            assertEquals(
                "IN word '$word' must classify IN",
                Direction.IN, DirectionHeuristic.classify("Transaksi $word Rp10.000"),
            )
        }
        // OUT-first: both sides present ⇒ OUT.
        assertEquals(
            Direction.OUT,
            DirectionHeuristic.classify("Pembayaran masuk Rp10.000"),
        )
        // No list word ⇒ UNKNOWN, never guessed.
        assertEquals(
            Direction.UNKNOWN,
            DirectionHeuristic.classify("Transaksi Rp10.000 selesai"),
        )
        // Words in only one PHP copy are NOT in the common vocabulary.
        assertEquals(
            "pengeluaran is v2New-only, not common",
            Direction.UNKNOWN, DirectionHeuristic.classify("pengeluaran Rp10.000"),
        )
        assertEquals(
            "spent is v2New-only, not common",
            Direction.UNKNOWN, DirectionHeuristic.classify("spent Rp10.000"),
        )
        assertEquals(
            "received is v2New-only, not common",
            Direction.UNKNOWN, DirectionHeuristic.classify("received Rp10.000"),
        )
        // "pemasukan" is v2New-only as a WORD — but it contains
        // "masuk" as a substring, so the PHP mb_strpos semantics
        // classify it IN on both servers. The client mirrors that.
        assertEquals(
            "pemasukan contains masuk (substring semantics, mirrors PHP)",
            Direction.IN, DirectionHeuristic.classify("pemasukan Rp10.000"),
        )
    }

    // --- F9: local-span precedence ---

    @Test
    fun f9_localSpanPrecedence_unlabelledRedacted_labelledPreserved() {
        val s = snapshot(
            text = "No. Ref 482913. Kode OTP 739201. Pembayaran Rp100.000 berhasil. 556677",
        )
        val result = gate.gate(s, 100000L)
        assertEquals(PrivacyGate.Decision.ALLOW, result.decision)
        assertTrue("labelled reference preserved", result.redactedText.contains("No. Ref 482913"))
        assertFalse("OTP redacted", result.redactedText.contains("739201"))
        assertFalse(
            "unlabelled span redacted despite the amount elsewhere",
            result.redactedText.contains("556677"),
        )
    }

    // --- F10: no-leak audit (gate level; the full-store audit is F18) ---

    @Test
    fun f10_noLeak_secretAbsentFromGateOutput() {
        val s = snapshot(
            title = "Kode OTP 482913",
            text = "PIN 8842. Token ab12cd34. Pembayaran Rp100.800 berhasil",
            bigText = "Kode verifikasi 556677",
        )
        val result = gate.gate(s, 100800L)
        val out = result.redactedTitle + " " + result.redactedText
        assertFalse(out.contains("482913"))
        assertFalse(out.contains("8842"))
        assertFalse(out.contains("ab12cd34"))
        assertFalse(out.contains("556677"))
        assertTrue(out.contains("Rp100.800"))
    }

    // --- F13: inseparable OTP+financial — event NOT dropped, text dropped ---

    @Test
    fun f13_inseparable_eventPreserved_textDropped_flagged() {
        // The OTP digits are split across title/text: neither field
        // alone contains a 4+ digit run, but the merged re-scan
        // finds the spaced group "123 456" with the local OTP cue
        // — a surviving class-(a) span the first pass could not
        // separate. The gate drops the text, keeps the event.
        val s = snapshot(
            title = "Kode 123",
            text = "456 OTP untuk pembayaran Rp100.800",
        )
        val result = gate.gate(s, 100800L)
        assertEquals(
            "the event is NOT dropped (Option A′)",
            PrivacyGate.Decision.ALLOW, result.decision,
        )
        assertTrue(result.redacted)
        assertTrue(result.inseparable)
        assertEquals("", result.redactedTitle)
        assertEquals("", result.redactedText)
    }

    // --- F19 (gate parts): recorder direction still classified after redaction ---

    @Test
    fun f19_directionAfterRedaction_unchanged() {
        val s = snapshot(
            text = "Kode OTP 482913. Transfer ke ****1234 Rp50.000",
        )
        val before = DirectionHeuristic.classify(s.combined)
        val result = gate.gate(s, 50000L)
        val after = DirectionHeuristic.classify(
            "${result.redactedTitle} ${result.redactedText}",
        )
        assertEquals(Direction.OUT, before)
        assertEquals("redaction must not change the direction", before, after)
        assertFalse(result.redactedText.contains("482913"))
    }

    // --- F35: pre-v1.1 item with OTP span — redacted, identity fields intact ---

    @Test
    fun f35_legacyItemWithOtp_redacted_identityUnchanged() {
        // A legacy-shaped payload text (title + text as stored by v2.3).
        val s = snapshot(
            title = "Pembayaran berhasil",
            text = "Kode OTP 482913. Pembayaran Rp100.800 berhasil",
        )
        val result = gate.gate(s, 100800L)
        assertTrue(result.redacted)
        assertFalse(result.redactedText.contains("482913"))
        // Identity inputs are computed from PRE-redaction text (C3)
        // and are unchanged by the gate.
        val key = CaptureCoordinator.legacyDedupeKey(
            s.packageName, s.postTimeMs, 100800L, s.combined,
        )
        assertTrue(key.startsWith("com.gojek.gopaymerchant|1760000000000|100800|"))
    }
}
