package com.netter.paylist.delivery

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production `LegacyResponseClassifier` against the §5.4 taxonomy.
 *
 * The fixture cases mirror `LegacyContractFixturesTest` one-for-one:
 * the same recorded response shapes, the same expectations — the
 * production classifier must keep every fixture green. The re-POST
 * cases (§5.5 rows 8–10) are direct calls: no fixture file exists for
 * them because the re-POST flag is attempt history, not wire shape.
 */
class LegacyResponseClassifierTest {

    // --- Fixture loading (same files as LegacyContractFixturesTest) ---

    private data class Fixture(
        val name: String,
        val eventType: String,
        val httpStatus: Int,
        val headers: Map<String, String>,
        val bodyText: String,
        val bodyJson: JSONObject?,
    )

    private fun loadFixture(name: String): Fixture {
        val stream = javaClass.classLoader
            .getResourceAsStream("fixtures/legacy/$name.json")
            ?: throw AssertionError("fixture missing: $name.json")
        val raw = stream.bufferedReader().readText()
        val json = JSONObject(raw)
        val headers = mutableMapOf<String, String>()
        val headerJson = json.optJSONObject("headers")
        if (headerJson != null) {
            for (key in headerJson.keys()) headers[key] = headerJson.getString(key)
        }
        val bodyObj = json.opt("body")
        val bodyText = when (bodyObj) {
            is String -> bodyObj
            is JSONObject -> bodyObj.toString()
            else -> throw AssertionError("fixture $name: body must be an object or a string")
        }
        val bodyJson = try {
            JSONObject(bodyText)
        } catch (e: Exception) {
            null
        }
        return Fixture(name, json.getString("event_type"), json.getInt("http_status"), headers, bodyText, bodyJson)
    }

    private fun outcomeOf(name: String): ClassificationOutcome {
        val f = loadFixture(name)
        return LegacyResponseClassifier.classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
    }

    private fun classifyDirect(
        status: Int,
        body: String,
        eventType: String = "payment",
        headers: Map<String, String> = emptyMap(),
        repost: Boolean = false,
    ): ClassificationOutcome =
        LegacyResponseClassifier.classify(status, headers, body, eventType, repost)

    // --- §5.4 payment taxonomy (fixtures) ---

    @Test
    fun matchedApplyOk_settled_chainStops() {
        val o = outcomeOf("notify-payment-matched-apply-ok")
        assertEquals(ResponseClassification.MATCHED_SETTLED, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
        assertEquals("ok", o.lastApplyStatus)
        assertFalse(o.reviewFlag)
    }

    @Test
    fun matchedApplySkip_notApplied_chainStops_reviewFlag() {
        val o = outcomeOf("notify-payment-matched-apply-skip")
        assertEquals(ResponseClassification.MATCHED_NOT_APPLIED, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
        assertEquals("skip", o.lastApplyStatus)
        assertTrue(o.reviewFlag)
    }

    @Test
    fun matchedApplyError_unconfirmed_chainStops() {
        val o = outcomeOf("notify-payment-matched-apply-error")
        assertEquals(ResponseClassification.MATCHED_UNCONFIRMED, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
        assertEquals("error", o.lastApplyStatus)
        assertTrue(o.reviewFlag)
    }

    @Test
    fun matchedApplyAbsent_unconfirmed_neverAssumesOk() {
        val o = outcomeOf("notify-payment-matched-apply-absent")
        assertEquals(ResponseClassification.MATCHED_UNCONFIRMED, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
        assertNull(o.lastApplyStatus)
        assertTrue(o.reviewFlag)
    }

    @Test
    fun matchedDuplicate_unconfirmed_noApplyField() {
        val f = loadFixture("notify-payment-matched-duplicate")
        assertTrue(f.bodyJson!!.getBoolean("duplicate"))
        assertFalse(f.bodyJson.getJSONObject("match").has("apply"))
        val o = LegacyResponseClassifier.classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(ResponseClassification.MATCHED_UNCONFIRMED, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
        assertNull(o.lastApplyStatus)
    }

    @Test
    fun matchedDuplicate_staleApplyNeverTrusted() {
        val f = loadFixture("notify-payment-matched-duplicate-stale-apply")
        assertTrue(f.bodyJson!!.getBoolean("duplicate"))
        assertEquals("ok", f.bodyJson.getJSONObject("match").optString("apply"))
        val o = LegacyResponseClassifier.classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(ResponseClassification.MATCHED_UNCONFIRMED, o.classification)
        assertNull(o.lastApplyStatus)
    }

    @Test
    fun ambiguous_chainStops_reviewFlag() {
        val o = outcomeOf("notify-payment-ambiguous")
        assertEquals(ResponseClassification.AMBIGUOUS, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
        assertTrue(o.reviewFlag)
    }

    @Test
    fun ambiguousDuplicate_chainStops() {
        val o = outcomeOf("notify-payment-ambiguous-duplicate")
        assertEquals(ResponseClassification.AMBIGUOUS, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
    }

    @Test
    fun logged_advancesChain() {
        val o = outcomeOf("notify-payment-logged")
        assertEquals(ResponseClassification.LOGGED, o.classification)
        assertEquals(ChainAction.ADVANCE, o.chain)
    }

    @Test
    fun duplicateLogged_advancesOnFirstAttemptSemantics() {
        // First attempt: 2xx `logged` advances. The §5.5 row 9 rule
        // (a re-POST after an UNKNOWN hop returning `logged` stays
        // UNKNOWN) is covered by the re-POST cases below.
        val o = outcomeOf("notify-payment-duplicate-logged")
        assertEquals(ResponseClassification.LOGGED, o.classification)
        assertEquals(ChainAction.ADVANCE, o.chain)
    }

    // --- T18 corrected (C1): heartbeat/test ---

    @Test
    fun t18_freshNotifyHeartbeat_matchNull_acks() {
        val f = loadFixture("notify-heartbeat-fresh")
        assertTrue(f.bodyJson!!.isNull("match"))
        val o = LegacyResponseClassifier.classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(ResponseClassification.ACK, o.classification)
    }

    @Test
    fun t18_catatHeartbeat_matchLogged_acks() {
        val f = loadFixture("catat-heartbeat")
        assertEquals("logged", f.bodyJson!!.getJSONObject("match").getString("status"))
        val o = LegacyResponseClassifier.classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(ResponseClassification.ACK, o.classification)
    }

    @Test
    fun t18_duplicateNotifyHeartbeat_matchLogged_acks() {
        val f = loadFixture("notify-heartbeat-duplicate")
        assertTrue(f.bodyJson!!.getBoolean("duplicate"))
        assertEquals("logged", f.bodyJson.getJSONObject("match").getString("status"))
        val o = LegacyResponseClassifier.classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(ResponseClassification.ACK, o.classification)
    }

    @Test
    fun t18_freshNotifyTest_matchNull_acks() {
        val o = outcomeOf("notify-test-fresh")
        assertEquals(ResponseClassification.ACK, o.classification)
    }

    @Test
    fun t18_catatTest_matchLogged_acks() {
        val o = outcomeOf("catat-test")
        assertEquals(ResponseClassification.ACK, o.classification)
    }

    @Test
    fun heartbeatWithoutOk_retries() {
        val o = outcomeOf("notify-heartbeat-ok-false")
        assertEquals(ResponseClassification.RETRY, o.classification)
        assertEquals(ChainAction.RETRY_BACKOFF, o.chain)
    }

    // --- Recorder (catat.php): always match.logged ---

    @Test
    fun catatPayment_logged_advances() {
        val f = loadFixture("catat-payment")
        assertEquals("in", f.bodyJson!!.getString("direction"))
        assertTrue(f.bodyJson.getBoolean("recorded"))
        val o = LegacyResponseClassifier.classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(ResponseClassification.LOGGED, o.classification)
        assertEquals(ChainAction.ADVANCE, o.chain)
    }

    @Test
    fun catatPaymentDuplicate_recordedFalse_stillLogged() {
        val f = loadFixture("catat-payment-out-duplicate")
        assertFalse(f.bodyJson!!.getBoolean("recorded"))
        assertEquals("out", f.bodyJson.getString("direction"))
        val o = LegacyResponseClassifier.classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(ResponseClassification.LOGGED, o.classification)
        assertEquals(ChainAction.ADVANCE, o.chain)
    }

    // --- Malformed 2xx, 429/408/5xx, 4xx, 3xx ---

    @Test
    fun malformed2xx_missingMatch_retriesSameRoute() {
        val o = outcomeOf("notify-payment-malformed-no-match")
        assertEquals(ResponseClassification.RETRY, o.classification)
        assertEquals(ChainAction.RETRY_SAME_ROUTE, o.chain)
    }

    @Test
    fun malformed2xx_notJson_retriesSameRoute() {
        val o = outcomeOf("notify-payment-malformed-not-json")
        assertEquals(ResponseClassification.RETRY, o.classification)
        assertEquals(ChainAction.RETRY_SAME_ROUTE, o.chain)
    }

    @Test
    fun http429_retries_honorsRetryAfter() {
        val o = outcomeOf("http-429-retry-after")
        assertEquals(ResponseClassification.RETRY, o.classification)
        assertEquals(ChainAction.RETRY_BACKOFF, o.chain)
        assertEquals(120L, o.retryAfterSeconds)
    }

    @Test
    fun http408_retries() {
        val o = outcomeOf("http-408")
        assertEquals(ResponseClassification.RETRY, o.classification)
        assertEquals(ChainAction.RETRY_BACKOFF, o.chain)
    }

    @Test
    fun http500_retriesWithBackoff() {
        val o = outcomeOf("http-500")
        assertEquals(ResponseClassification.RETRY, o.classification)
        assertEquals(ChainAction.RETRY_BACKOFF, o.chain)
    }

    @Test
    fun http401_blocked_authCategory_chainHalts() {
        val o = outcomeOf("http-401")
        assertEquals(ResponseClassification.BLOCKED, o.classification)
        assertEquals(ChainAction.HALT, o.chain)
        assertEquals("auth", o.blockedCategory)
    }

    @Test
    fun http403_blocked_authCategory() {
        val o = outcomeOf("http-403")
        assertEquals(ResponseClassification.BLOCKED, o.classification)
        assertEquals("auth", o.blockedCategory)
    }

    @Test
    fun http400_blocked_payloadCategory_chainHalts() {
        val o = outcomeOf("http-400")
        assertEquals(ResponseClassification.BLOCKED, o.classification)
        assertEquals(ChainAction.HALT, o.chain)
        assertEquals("payload", o.blockedCategory)
    }

    @Test
    fun http302_blocked_configCategory_locationSurfaced() {
        val o = outcomeOf("http-302")
        assertEquals(ResponseClassification.BLOCKED, o.classification)
        assertEquals(ChainAction.HALT, o.chain)
        assertEquals("config", o.blockedCategory)
        assertEquals("https://example.invalid/other", o.location)
    }

    // --- §5.5 rows 8–10: re-POST after an unknown hop ---

    private fun matchedBody(apply: String?): String {
        val applyJson = if (apply == null) "" else ""","apply":"$apply""""
        return """{"ok":true,"match":{"status":"matched"$applyJson}}"""
    }

    @Test
    fun repost_matchedApplyOk_settles_row8a() {
        val o = classifyDirect(200, matchedBody("ok"), repost = true)
        assertEquals(ResponseClassification.MATCHED_SETTLED, o.classification)
        assertEquals("ok", o.lastApplyStatus)
    }

    @Test
    fun repost_matchedApplySkip_staysUnconfirmed_row8b() {
        // Differs from the first attempt (NOT_APPLIED): on a re-POST
        // the response reflects the original attempt, so no apply
        // value other than `ok` is trusted.
        val o = classifyDirect(200, matchedBody("skip"), repost = true)
        assertEquals(ResponseClassification.MATCHED_UNCONFIRMED, o.classification)
        assertNull(o.lastApplyStatus)
        assertTrue(o.reviewFlag)
    }

    @Test
    fun repost_matchedApplyError_staysUnconfirmed_row8b() {
        val o = classifyDirect(200, matchedBody("error"), repost = true)
        assertEquals(ResponseClassification.MATCHED_UNCONFIRMED, o.classification)
    }

    @Test
    fun repost_matchedApplyAbsent_staysUnconfirmed_row8b() {
        val o = classifyDirect(200, matchedBody(null), repost = true)
        assertEquals(ResponseClassification.MATCHED_UNCONFIRMED, o.classification)
        assertNull(o.lastApplyStatus)
    }

    @Test
    fun repost_duplicateWithStaleApply_staysUnconfirmed_row8c() {
        val o = classifyDirect(
            200,
            """{"ok":true,"duplicate":true,"match":{"status":"matched","apply":"ok"}}""",
            repost = true
        )
        assertEquals(ResponseClassification.MATCHED_UNCONFIRMED, o.classification)
        assertNull(o.lastApplyStatus)
    }

    @Test
    fun repost_logged_staysUnknown_row9() {
        val o = classifyDirect(200, """{"ok":true,"match":{"status":"logged"}}""", repost = true)
        assertEquals(ResponseClassification.UNKNOWN_PENDING_RECONCILIATION, o.classification)
        assertEquals(ChainAction.HALT, o.chain)
        assertTrue(o.reviewFlag)
    }

    @Test
    fun repost_duplicateLogged_staysUnknown_row9() {
        val o = classifyDirect(
            200,
            """{"ok":true,"duplicate":true,"match":{"status":"logged"}}""",
            repost = true
        )
        assertEquals(ResponseClassification.UNKNOWN_PENDING_RECONCILIATION, o.classification)
    }

    @Test
    fun repost_malformed2xx_staysUnknown_row10() {
        val o = classifyDirect(200, "bukan json", repost = true)
        assertEquals(ResponseClassification.UNKNOWN_PENDING_RECONCILIATION, o.classification)
        val o2 = classifyDirect(200, """{"ok":true}""", repost = true)
        assertEquals(ResponseClassification.UNKNOWN_PENDING_RECONCILIATION, o2.classification)
    }

    @Test
    fun repost_4xx_staysUnknown_row10() {
        val o = classifyDirect(400, """{"ok":false}""", repost = true)
        assertEquals(ResponseClassification.UNKNOWN_PENDING_RECONCILIATION, o.classification)
        assertEquals(ChainAction.HALT, o.chain)
    }

    @Test
    fun repost_5xx_staysUnknown_row10() {
        val o = classifyDirect(500, "", repost = true)
        assertEquals(ResponseClassification.UNKNOWN_PENDING_RECONCILIATION, o.classification)
    }

    @Test
    fun repost_429_staysUnknown_row10() {
        val o = classifyDirect(429, "", repost = true)
        assertEquals(ResponseClassification.UNKNOWN_PENDING_RECONCILIATION, o.classification)
    }

    @Test
    fun repost_3xx_staysUnknown_row10() {
        val o = classifyDirect(
            302, "",
            headers = mapOf("Location" to "https://example.invalid/other"),
            repost = true
        )
        assertEquals(ResponseClassification.UNKNOWN_PENDING_RECONCILIATION, o.classification)
    }

    @Test
    fun repost_ambiguous_stillAmbiguous() {
        val o = classifyDirect(200, """{"ok":true,"match":{"status":"ambiguous"}}""", repost = true)
        assertEquals(ResponseClassification.AMBIGUOUS, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
    }

    @Test
    fun repost_heartbeat_ignoresFlag() {
        val ok = classifyDirect(200, """{"ok":true,"match":null}""", eventType = "heartbeat", repost = true)
        assertEquals(ResponseClassification.ACK, ok.classification)
        val noOk = classifyDirect(200, """{"ok":false}""", eventType = "heartbeat", repost = true)
        assertEquals(ResponseClassification.RETRY, noOk.classification)
        assertEquals(ChainAction.RETRY_BACKOFF, noOk.chain)
        val err = classifyDirect(500, "", eventType = "heartbeat", repost = true)
        assertEquals(ResponseClassification.RETRY, err.classification)
    }

    // --- Direct edge cases ---

    @Test
    fun retryAfter_invalidValue_yieldsNull() {
        val o = classifyDirect(429, "", headers = mapOf("Retry-After" to "soon"))
        assertEquals(ResponseClassification.RETRY, o.classification)
        assertNull(o.retryAfterSeconds)
    }

    @Test
    fun retryAfter_hugeValue_cappedAt7200() {
        val o = classifyDirect(429, "", headers = mapOf("retry-after" to "99999"))
        assertEquals(7200L, o.retryAfterSeconds)
    }

    @Test
    fun redirectWithoutLocation_locationNull() {
        val o = classifyDirect(301, "")
        assertEquals(ResponseClassification.BLOCKED, o.classification)
        assertEquals("config", o.blockedCategory)
        assertNull(o.location)
    }

    @Test
    fun unknownStatus_retriesWithBackoff() {
        assertEquals(
            ResponseClassification.RETRY,
            classifyDirect(100, "").classification
        )
        assertEquals(
            ChainAction.RETRY_BACKOFF,
            classifyDirect(600, "").chain
        )
    }

    @Test
    fun headerLookup_isCaseInsensitive() {
        val o = classifyDirect(429, "", headers = mapOf("RETRY-AFTER" to "30"))
        assertEquals(30L, o.retryAfterSeconds)
        val r = classifyDirect(
            307, "",
            headers = mapOf("LOCATION" to "https://example.invalid/x")
        )
        assertEquals("https://example.invalid/x", r.location)
    }
}
