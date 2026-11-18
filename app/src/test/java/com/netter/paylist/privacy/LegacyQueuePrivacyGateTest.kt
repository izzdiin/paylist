package com.netter.paylist.privacy

import com.netter.paylist.Store
import com.netter.paylist.migration.Inspection
import com.netter.paylist.migration.LegacyQueueItem
import com.netter.paylist.migration.LegacyQueueSnapshotSource
import com.netter.paylist.migration.LegacyQkey
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P9 gate tests (design §7.7, §10.2): `LegacyQueuePrivacyGateTest`
 * owns F50 (P9 narrowed per-item handling) and co-owns F54 (P9 stage
 * gate) with `CutoverTest`. F50/F54 must PASS before any Stage C
 * legacy import/cutover activation (R30, M2).
 *
 * No Robolectric: plain JVM unit tests. The gate is pure except for
 * the queue store, which is faked here via [LegacyQueueSource].
 */
class LegacyQueuePrivacyGateTest {

    companion object {
        const val NOW: Long = 1_795_000_000_000L
    }

    private class FakeQueueSource(initial: List<JSONObject>) : LegacyQueueSnapshotSource {
        private var queue: List<JSONObject> = initial.map { JSONObject(it.toString()) }
        var writes: Int = 0
            private set
        var failWrites: Boolean = false

        override fun queueSnapshot(): List<JSONObject> =
            queue.map { JSONObject(it.toString()) }

        override fun saveQueueSync(items: List<JSONObject>): Boolean {
            writes++
            if (failWrites) return false
            queue = items.map { JSONObject(it.toString()) }
            return true
        }

        fun current(): List<JSONObject> = queueSnapshot()
    }

    private fun queueItem(
        tag: String,
        title: String,
        text: String,
        amount: Long? = 100_800L,
        next: Int = 0,
        attempts: Int = 0,
        lastError: String? = null,
        hopOutcomes: String? = null,
    ): JSONObject {
        val dedupeKey = "com.gojek.gopaymerchant|1795000000000|100800|$tag"
        val qkey = LegacyQkey.build(dedupeKey, LegacyQkey.KIND_CHAIN, listOf("route-1", "route-2"))
        return JSONObject().apply {
            put("qkey", qkey)
            put("kind", "chain")
            put("route_ids", JSONArray(listOf("route-1", "route-2")))
            put("next", next)
            put("attempts", attempts)
            if (lastError != null) put("last_error", lastError)
            if (hopOutcomes != null) put("hop_outcomes", hopOutcomes)
            put(
                "payload",
                JSONObject().apply {
                    put("type", "payment")
                    put("device", "moto-g57")
                    put("pkg", "com.gojek.gopaymerchant")
                    if (amount != null) put("amount", amount)
                    put("title", title)
                    put("text", text)
                    put("notif_time", "2026-11-18T03:00:00Z")
                    put("dedupe_key", dedupeKey)
                },
            )
        }
    }

    // =====================================================================
    // F50 — P9 narrowed per-item handling
    // =====================================================================

    @Test
    fun f50_cleanItemByteIdentical() {
        val item = queueItem(
            "f50a",
            "GoPay",
            "Pembayaran Rp100.800 berhasil",
        )
        val originalPayload = JSONObject(item.optJSONObject("payload").toString())
        val gate = LegacyQueuePrivacyGate()
        assertEquals(GateVerdict.Clean, gate.inspect(item, NOW))

        val source = FakeQueueSource(listOf(item))
        val report = gate.reprocess(source, NOW)
        assertEquals(1, report.clean)
        assertEquals(0, report.redacted)
        assertEquals(0, report.quarantined)
        assertTrue(report.writeOk)
        val after = source.current().single()
        // Payload bytes unchanged; only markers added.
        assertTrue(
            "clean payload must be byte-identical",
            JSONObject(after.optJSONObject("payload").toString()).similar(originalPayload),
        )
        assertTrue(after.optBoolean(LegacyQueuePrivacyGate.KEY_REPROCESSED))
        assertEquals(0, after.optInt(LegacyQueuePrivacyGate.KEY_REDACTED_SPANS))
        assertEquals(NOW, after.optLong(LegacyQueuePrivacyGate.KEY_REPROCESSED_AT_MS))
        assertFalse(after.optBoolean(LegacyQueuePrivacyGate.KEY_QUARANTINE))
        // No marker leaks into the payload (never POSTed).
        assertFalse(after.optJSONObject("payload").has(LegacyQueuePrivacyGate.KEY_REPROCESSED))
    }

    @Test
    fun f50_otpSpanRedactedSpanLevel() {
        val item = queueItem(
            "f50b",
            "GoPay",
            "Pembayaran Rp100.800 berhasil. Kode OTP Anda adalah 123456",
        )
        val gate = LegacyQueuePrivacyGate()
        val verdict = gate.inspect(item, NOW)
        assertTrue(verdict is GateVerdict.Redacted)
        verdict as GateVerdict.Redacted
        assertEquals(1, verdict.spanCount)
        assertEquals(setOf(SpanClass.OTP), verdict.spanClasses)
        assertTrue(verdict.redactedText.contains("[REDACTED]"))
        assertFalse("redacted text must not contain the code", verdict.redactedText.contains("123456"))
        assertTrue("the financial part survives redaction", verdict.redactedText.contains("Rp100.800"))

        val source = FakeQueueSource(listOf(item))
        val report = gate.reprocess(source, NOW)
        assertEquals(1, report.redacted)
        assertTrue(report.writeOk)
        val after = source.current().single()
        val payload = after.optJSONObject("payload")
        assertFalse(payload.optString("text").contains("123456"))
        assertTrue(payload.optString("text").contains("[REDACTED]"))
        assertEquals(1, after.optInt(LegacyQueuePrivacyGate.KEY_REDACTED_SPANS))
        assertEquals("OTP", after.optString(LegacyQueuePrivacyGate.KEY_SPAN_CLASSES))
        // dedupe_key / amount / routes untouched; dedupe_key never recomputed.
        assertEquals(
            "com.gojek.gopaymerchant|1795000000000|100800|f50b",
            payload.optString("dedupe_key"),
        )
        assertEquals(100_800L, payload.optLong("amount"))
    }

    @Test
    fun f50_uncertainItemQuarantined() {
        val item = queueItem(
            "f50c",
            "GoPay",
            "Pembayaran Rp100.800 berhasil. 123456",
        )
        val gate = LegacyQueuePrivacyGate()
        val verdict = gate.inspect(item, NOW)
        assertTrue(verdict is GateVerdict.Quarantined)
        verdict as GateVerdict.Quarantined
        assertEquals(LegacyQueuePrivacyGate.REASON_AMBIGUOUS, verdict.reason)
        assertEquals(setOf(SpanClass.AMBIGUOUS_CODE), verdict.spanClasses)

        val source = FakeQueueSource(listOf(item))
        val report = gate.reprocess(source, NOW)
        assertEquals(1, report.quarantined)
        assertTrue(report.writeOk)
        val after = source.current().single()
        assertTrue(after.optBoolean(LegacyQueuePrivacyGate.KEY_QUARANTINE))
        assertEquals(
            LegacyQueuePrivacyGate.REASON_AMBIGUOUS,
            after.optString(LegacyQueuePrivacyGate.KEY_QUARANTINE_REASON),
        )
        // The raw text stays in the holding pen (non-destructive quarantine);
        // the quarantine marker is what keeps it out of POST/import/export.
        assertTrue(after.optJSONObject("payload").optString("text").contains("123456"))
        // The inspector excludes it.
        val parsed = LegacyQueueItem.fromJson(after)!!
        assertEquals(Inspection.QUARANTINED, GateBackedInspector.inspect(parsed))
    }

    @Test
    fun f50_pureAuthItemQuarantined() {
        val item = queueItem(
            "f50d",
            "Verifikasi",
            "Kode OTP Anda adalah 123456",
            amount = null,
        )
        val gate = LegacyQueuePrivacyGate()
        val verdict = gate.inspect(item, NOW)
        assertTrue(verdict is GateVerdict.Quarantined)
        verdict as GateVerdict.Quarantined
        assertEquals(LegacyQueuePrivacyGate.REASON_PURE_AUTH, verdict.reason)
    }

    @Test
    fun f50_financialContentPreserved() {
        val item = queueItem(
            "f50e",
            "GoPay",
            "Pembayaran Rp100.800 berhasil. No. Ref 123456",
        )
        val gate = LegacyQueuePrivacyGate()
        assertEquals(GateVerdict.Clean, gate.inspect(item, NOW))
    }

    @Test
    fun f50_authCueWinsOverFinancial() {
        val item = queueItem(
            "f50f",
            "GoPay",
            "No. Ref 123456. Kode OTP 789012",
        )
        val gate = LegacyQueuePrivacyGate()
        val verdict = gate.inspect(item, NOW)
        assertTrue(verdict is GateVerdict.Redacted)
        verdict as GateVerdict.Redacted
        // Only the OTP span is redacted; the labelled reference survives.
        assertTrue(verdict.redactedText.contains("123456"))
        assertFalse(verdict.redactedText.contains("789012"))
        assertTrue(verdict.redactedText.contains("[REDACTED]"))
    }

    @Test
    fun f50_financialCueElsewhereDoesNotPromote() {
        // The bare code sits in its own clause: the amount elsewhere in
        // the message does not promote it (§7.1(b)) — it stays ambiguous.
        val item = queueItem(
            "f50g",
            "GoPay",
            "Pembayaran Rp100.800 berhasil. 123456",
        )
        val gate = LegacyQueuePrivacyGate()
        assertTrue(gate.inspect(item, NOW) is GateVerdict.Quarantined)
    }

    @Test
    fun f50_directionWordsAndMaskedAccountNeverSecret() {
        val item = queueItem(
            "f50h",
            "Bank",
            "Transfer Rp100.800 ke ****1234 berhasil. Dana masuk.",
        )
        val gate = LegacyQueuePrivacyGate()
        assertEquals(GateVerdict.Clean, gate.inspect(item, NOW))
    }

    @Test
    fun f50_longDigitRunNotASpan() {
        // A 20-digit token is not a 4–12 digit code span.
        val item = queueItem(
            "f50i",
            "PLN",
            "Token listrik 12345678901234567890 berhasil dibeli.",
        )
        val gate = LegacyQueuePrivacyGate()
        assertEquals(GateVerdict.Clean, gate.inspect(item, NOW))
    }

    @Test
    fun f50_redactionPreservesCursorAndAudit() {
        val item = queueItem(
            "f50j",
            "GoPay",
            "Pembayaran Rp100.800 berhasil. Kode OTP 123456",
            next = 1,
            attempts = 3,
            lastError = "SocketTimeoutException",
            hopOutcomes = "[{\"route\":\"route-1\",\"outcome\":\"LOGGED\"}]",
        )
        val gate = LegacyQueuePrivacyGate()
        val source = FakeQueueSource(listOf(item))
        val report = gate.reprocess(source, NOW)
        assertEquals(1, report.redacted)
        val after = source.current().single()
        assertEquals(1, after.optInt("next"))
        assertEquals(3, after.optInt("attempts"))
        assertEquals("SocketTimeoutException", after.optString("last_error"))
        assertEquals("[{\"route\":\"route-1\",\"outcome\":\"LOGGED\"}]", after.optString("hop_outcomes"))
        assertEquals(
            JSONArray(listOf("route-1", "route-2")).toString(),
            after.optJSONArray("route_ids").toString(),
        )
    }

    @Test
    fun f50_reprocessMixedQueueAndIdempotentRerun() {
        val clean = queueItem("f50k1", "GoPay", "Pembayaran Rp100.800 berhasil")
        val otp = queueItem("f50k2", "GoPay", "Pembayaran Rp100.800 berhasil. Kode OTP 123456")
        val ambiguous = queueItem("f50k3", "GoPay", "Pembayaran Rp100.800 berhasil. 123456")
        val already = queueItem("f50k4", "GoPay", "Pembayaran Rp100.800 berhasil").apply {
            put(LegacyQueuePrivacyGate.KEY_REPROCESSED, true)
            put(LegacyQueuePrivacyGate.KEY_REDACTED_SPANS, 0)
            put(LegacyQueuePrivacyGate.KEY_REPROCESSED_AT_MS, NOW - 1000L)
        }
        val corrupt = JSONObject().apply { put("qkey", "no-payload|chain|route-1") }
        val gate = LegacyQueuePrivacyGate()
        val source = FakeQueueSource(listOf(clean, otp, ambiguous, already, corrupt))
        val report = gate.reprocess(source, NOW)
        assertEquals(3, report.inspected)
        assertEquals(1, report.clean)
        assertEquals(1, report.redacted)
        assertEquals(1, report.quarantined)
        assertEquals(1, report.alreadyProcessed)
        assertEquals(1, report.corrupt)
        assertTrue(report.writeOk)
        assertEquals(1, source.writes)
        // Idempotent re-run: everything already decided, no second write.
        val second = gate.reprocess(source, NOW)
        assertEquals(0, second.inspected)
        assertEquals(4, second.alreadyProcessed)
        assertEquals(1, second.corrupt)
        assertTrue(second.writeOk)
        assertEquals(1, source.writes)
    }

    @Test
    fun f50_reprocessWriteFailureReported() {
        val item = queueItem("f50l", "GoPay", "Pembayaran Rp100.800 berhasil")
        val gate = LegacyQueuePrivacyGate()
        val source = FakeQueueSource(listOf(item)).apply { failWrites = true }
        val report = gate.reprocess(source, NOW)
        assertEquals(1, report.clean)
        assertFalse("a failed durable write must be reported", report.writeOk)
    }

    @Test
    fun f50_noQueueWideFunction() {
        // Reflection: no wholesale queue function exists on Store.
        val banned = setOf(
            "clearQueue", "deleteQueue", "clearAllQueue", "deleteAllQueue",
            "wipeQueue", "purgeQueue", "sanitizeQueue", "sanitiseQueue",
            "sanitizeAllQueue", "sanitiseAllQueue",
            "sanitizeAll", "sanitiseAll", "transmitQueue", "transmitAll",
            "sendAllQueue", "postAllQueue", "clearAll", "deleteAll",
        )
        val found = Store::class.java.methods.map { it.name }.filter { it in banned }
        assertTrue("wholesale queue function must not exist on Store: $found", found.isEmpty())
        // Source scan: no wholesale queue function anywhere in the tree
        // (the per-item clearQueueItem is allowed and must not match).
        val root = findSourceRoot()
            ?: throw AssertionError("source root not found — fail-closed")
        val pattern = Regex(
            """\bfun\s+(clearQueue|deleteQueue|clearAllQueue|deleteAllQueue|wipeQueue|purgeQueue|sanitizeQueue|sanitiseQueue|sanitizeAllQueue|sanitiseAllQueue|sanitizeAll|sanitiseAll|transmitQueue|transmitAll|sendAllQueue|postAllQueue)\s*\(""",
        )
        val violations = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (pattern.containsMatchIn(line)) {
                    violations.add("${file.relativeTo(root)}:${index + 1}: ${line.trim()}")
                }
            }
        }
        assertTrue("wholesale queue function must not exist in the tree: $violations", violations.isEmpty())
    }

    // =====================================================================
    // F54 — P9 stage gate (gate side; migration side lives in CutoverTest)
    // =====================================================================

    @Test
    fun f54_inspectorDecisions() {
        // Uninspected (no markers) -> QUARANTINED (fail-closed).
        val uninspected = LegacyQueueItem.fromJson(
            queueItem("f54a", "GoPay", "Pembayaran Rp100.800 berhasil"),
        )!!
        assertFalse(uninspected.privacyReprocessed)
        assertFalse(uninspected.privacyQuarantined)
        assertEquals(Inspection.QUARANTINED, GateBackedInspector.inspect(uninspected))
        // Inspected clean -> IMPORTABLE.
        val cleanRaw = queueItem("f54b", "GoPay", "Pembayaran Rp100.800 berhasil").apply {
            put(LegacyQueuePrivacyGate.KEY_REPROCESSED, true)
        }
        val clean = LegacyQueueItem.fromJson(cleanRaw)!!
        assertTrue(clean.privacyReprocessed)
        assertEquals(Inspection.IMPORTABLE, GateBackedInspector.inspect(clean))
        // Inspected redacted -> IMPORTABLE (the redacted form is the importable form).
        val redactedRaw = queueItem("f54c", "GoPay", "Pembayaran Rp100.800 berhasil").apply {
            put(LegacyQueuePrivacyGate.KEY_REPROCESSED, true)
            put(LegacyQueuePrivacyGate.KEY_REDACTED_SPANS, 1)
        }
        assertEquals(
            Inspection.IMPORTABLE,
            GateBackedInspector.inspect(LegacyQueueItem.fromJson(redactedRaw)!!),
        )
        // Quarantined -> QUARANTINED even if also marked reprocessed.
        val quarantinedRaw = queueItem("f54d", "GoPay", "Pembayaran Rp100.800 berhasil").apply {
            put(LegacyQueuePrivacyGate.KEY_QUARANTINE, true)
            put(LegacyQueuePrivacyGate.KEY_REPROCESSED, true)
        }
        val quarantined = LegacyQueueItem.fromJson(quarantinedRaw)!!
        assertTrue(quarantined.privacyQuarantined)
        assertEquals(Inspection.QUARANTINED, GateBackedInspector.inspect(quarantined))
    }

    /**
     * Locates `src/main/java` for the static check (mirrors CutoverTest).
     * Tries the module dir, then the project root, then walks up from
     * the working directory. Returns null when not found — the caller
     * fails closed.
     */
    private fun findSourceRoot(): File? {
        listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }?.let { return it }
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val base = dir ?: break
            val appSrc = File(base, "app/src/main/java")
            if (appSrc.isDirectory) return appSrc
            val src = File(base, "src/main/java")
            if (src.isDirectory) return src
            dir = base.parentFile
        }
        return null
    }
}
