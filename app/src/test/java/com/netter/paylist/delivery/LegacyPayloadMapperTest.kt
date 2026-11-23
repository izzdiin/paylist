package com.netter.paylist.delivery

import com.netter.paylist.parser.Direction
import com.netter.paylist.parser.DirectionHeuristic
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * `LegacyPayloadMapper` wire-contract tests (§5.3, T02/T03).
 *
 * Compatibility is JSON equality after parsing, not byte-identical:
 * the mapper's output must carry exactly the legacy fields with the
 * legacy values. Imported payloads go out verbatim.
 */
class LegacyPayloadMapperTest {

    private val fields = LegacyPayloadMapper.PaymentFields(
        device = "Moto g57",
        pkg = "com.gojek.gopay",
        amount = 150_000L,
        title = "GoPay",
        text = "Kamu menerima Rp150.000 dari Budi",
        notifTimeMs = 1_794_960_000_000L, // 2026-11-18T00:00:00Z
        dedupeKey = "com.gojek.gopay|1794960000000|150000|12345",
    )

    @Test
    fun payment_carriesExactlyTheEightWireFields() {
        val payload = LegacyPayloadMapper.mapPayment(fields)
        assertEquals(8, payload.length())
        assertEquals("payment", payload.getString("type"))
        assertEquals("Moto g57", payload.getString("device"))
        assertEquals("com.gojek.gopay", payload.getString("pkg"))
        assertEquals(150_000L, payload.getLong("amount"))
        assertEquals("GoPay", payload.getString("title"))
        assertEquals("Kamu menerima Rp150.000 dari Budi", payload.getString("text"))
        assertEquals("com.gojek.gopay|1794960000000|150000|12345", payload.getString("dedupe_key"))
    }

    @Test
    fun payment_notifTime_isIso8601RoundTrippingToTheSameEpoch() {
        val payload = LegacyPayloadMapper.mapPayment(fields)
        val notifTime = payload.getString("notif_time")
        assertEquals("2026-11-18T00:00:00Z", notifTime)
        assertEquals(1_794_960_000_000L, Instant.parse(notifTime).toEpochMilli())
    }

    @Test
    fun payment_titleAndText_truncatedAt500Chars() {
        val long = "x".repeat(600)
        val payload = LegacyPayloadMapper.mapPayment(fields.copy(title = long, text = long))
        assertEquals(500, payload.getString("title").length)
        assertEquals(500, payload.getString("text").length)
        assertEquals("x".repeat(500), payload.getString("title"))
    }

    @Test
    fun payment_carriesNoAdvisoryFields() {
        val payload = LegacyPayloadMapper.mapPayment(fields)
        assertFalse(payload.has("bank_reference"))
        assertFalse(payload.has("order_number"))
        assertFalse(payload.has("match"))
        assertFalse(payload.has("app_version"))
        assertFalse(payload.has("schema_version"))
    }

    @Test
    fun payment_textPassesThroughVerbatim_mapperNeverSubstitutes() {
        // The caller supplies the text — raw, or an A′ text when a
        // recorded gate decision authorises it. The mapper sends
        // exactly what it is given, never choosing A′ itself.
        val redacted = "Kamu menerima Rp150.000 [REDACTED]"
        val payload = LegacyPayloadMapper.mapPayment(fields.copy(text = redacted))
        assertEquals(redacted, payload.getString("text"))
    }

    @Test
    fun heartbeat_matchesLegacyShapeAndBucketMath() {
        // Bucket 0: the first 15 minutes of the epoch.
        val first = LegacyPayloadMapper.mapHeartbeat("Moto g57", "route-1", 899_999L)
        assertEquals(6, first.length())
        assertEquals("heartbeat", first.getString("type"))
        assertEquals("Moto g57", first.getString("device"))
        assertEquals("", first.getString("pkg"))
        assertEquals("hb-route-1-0", first.getString("dedupe_key"))
        assertEquals("", first.getString("title"))
        assertEquals("", first.getString("text"))
        assertFalse(first.has("amount"))
        assertFalse(first.has("notif_time"))

        // Bucket boundary: exactly 900_000 ms is bucket 1.
        val second = LegacyPayloadMapper.mapHeartbeat("Moto g57", "route-1", 900_000L)
        assertEquals("hb-route-1-1", second.getString("dedupe_key"))
    }

    @Test
    fun test_matchesLegacyShape() {
        val payload = LegacyPayloadMapper.mapTest("Moto g57", "route-1", 1_794_960_000_000L)
        assertEquals(6, payload.length())
        assertEquals("test", payload.getString("type"))
        assertEquals("Moto g57", payload.getString("device"))
        assertEquals("test", payload.getString("pkg"))
        assertEquals("test-route-1-1794960000000", payload.getString("dedupe_key"))
        assertEquals("Tes", payload.getString("title"))
        assertEquals("tes endpoint dari Paylist", payload.getString("text"))
        assertFalse(payload.has("amount"))
        assertFalse(payload.has("notif_time"))
    }

    @Test
    fun imported_payloadGoesOutVerbatim() {
        val stored = """{"type":"payment","device":"Moto g57","pkg":"com.gojek.gopay","amount":150000,"title":"GoPay","text":"Kamu menerima Rp150.000","notif_time":"2026-11-18T00:00:00Z","dedupe_key":"k1","extra_unknown_field":"kept"}"""
        val payload = LegacyPayloadMapper.mapImported(stored)
        // JSON equality after parsing (key order irrelevant), unknown
        // keys preserved — the mapper never re-derives or drops.
        assertTrue(JSONObject(stored).similar(payload))
        assertEquals("kept", payload.getString("extra_unknown_field"))
    }

    @Test
    fun imported_malformedJson_throws() {
        try {
            LegacyPayloadMapper.mapImported("bukan json")
            throw AssertionError("expected JSONException")
        } catch (e: org.json.JSONException) {
            // Expected: the caller (SyncWorker) treats a corrupt stored
            // payload as a blocked delivery, never as a POST.
        }
    }

    // --- Stage D D2: §7.2 constructed recorder text + self-check (F49, B14) ---

    @Test
    fun constructed_outForm_templateAndDirection() {
        val built = LegacyPayloadMapper.buildConstructedText(
            Direction.OUT, 100800L, "****1234", "INV-9",
        )
        assertEquals("Transfer ke ****1234 Rp100.800 Ref INV-9", built.text)
        assertFalse(built.needsReview)
        assertEquals(Direction.OUT, DirectionHeuristic.classify(built.text))
    }

    @Test
    fun constructed_inForm_templateAndDirection() {
        val built = LegacyPayloadMapper.buildConstructedText(
            Direction.IN, 50000L, "****5678", null,
        )
        assertEquals("Diterima Rp50.000 dari ****5678", built.text)
        assertFalse(built.needsReview)
        assertEquals(Direction.IN, DirectionHeuristic.classify(built.text))
    }

    @Test
    fun constructed_unknownForm_noCue() {
        val built = LegacyPayloadMapper.buildConstructedText(
            Direction.UNKNOWN, 100800L, null, null,
        )
        assertEquals("Rp100.800", built.text)
        assertFalse(built.needsReview)
        assertEquals(Direction.UNKNOWN, DirectionHeuristic.classify(built.text))
    }

    @Test
    fun constructed_missingAccountAndReference_omittedCleanly() {
        val out = LegacyPayloadMapper.buildConstructedText(Direction.OUT, 100800L, null, null)
        assertEquals("Transfer ke Rp100.800", out.text)
        val inText = LegacyPayloadMapper.buildConstructedText(Direction.IN, 100800L, null, null)
        assertEquals("Diterima Rp100.800", inText.text)
    }

    @Test
    fun f49_referenceWithDirectionWord_noCueForm_reviewFlag() {
        // "REF BAYAR 9" contains "bayar" (an OUT word) as a
        // substring — the token is omitted, the form falls back
        // to no-cue, and the review flag is set.
        val built = LegacyPayloadMapper.buildConstructedText(
            Direction.IN, 100800L, null, "REF BAYAR 9",
        )
        assertFalse("the offending token must not be inserted", built.text.contains("BAYAR"))
        assertTrue(built.needsReview)
        assertEquals(Direction.UNKNOWN, DirectionHeuristic.classify(built.text))
    }

    @Test
    fun f49_referenceWithTransferKe_noCueForm_reviewFlag() {
        val built = LegacyPayloadMapper.buildConstructedText(
            Direction.IN, 100800L, null, "transfer ke-123",
        )
        assertFalse(built.text.contains("transfer"))
        assertTrue(built.needsReview)
    }

    @Test
    fun f49_bareDigitReference_rejected() {
        // A bare-digit reference is rejected: in the C-b6 shape
        // the digits ARE the OTP — never reused as a reference.
        val built = LegacyPayloadMapper.buildConstructedText(
            Direction.OUT, 100800L, null, "482913",
        )
        assertFalse(built.text.contains("482913"))
        assertFalse(built.text.contains("Ref"))
        assertTrue(built.needsReview)
    }

    @Test
    fun f49_otpDigitsNeverReference_outForm() {
        val built = LegacyPayloadMapper.buildConstructedText(
            Direction.OUT, 100800L, "****1234", "482913",
        )
        assertEquals("Transfer ke ****1234 Rp100.800", built.text)
    }

    @Test
    fun b14_constructedText_classifiesToIntendedDirection() {
        // The B14 client-side fixture: for each direction, the
        // constructed text classifies to that direction under the
        // replicated catat.php heuristic.
        for (direction in listOf(Direction.OUT, Direction.IN, Direction.UNKNOWN)) {
            val built = LegacyPayloadMapper.buildConstructedText(
                direction, 123456L, "****9999", "ORD-1",
            )
            assertEquals(
                "constructed text must classify $direction",
                direction, DirectionHeuristic.classify(built.text),
            )
            assertFalse(built.needsReview)
        }
    }
}
