package com.netter.paylist.delivery

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
}
