package com.netter.paylist.delivery

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Legacy endpoint contract fixtures (Stage A gate: T01 + contract
 * fixtures; design §5.4 taxonomy, §10.3 G2, T18 corrected per C1).
 *
 * The fixtures under `src/test/resources/fixtures/legacy/` are recorded
 * response shapes copied from the real PHP sources
 * (`warganet/paylist/notify.php`, `warganet/paylist/catat.php`):
 * success envelopes, `match` blocks, `apply` values, duplicate shapes
 * (stored status, no `apply` field), and error bodies. Fixtures marked
 * with a `note` are defensive/synthetic shapes the design pins even
 * though the current PHP never emits them. No test here touches a live
 * server — every exchange is a local file.
 *
 * The classifier in this file is a TEST-LOCAL pin of the §5.4 taxonomy:
 * it exists so the fixtures assert behaviour, not just their own bytes.
 * Stage C owns the production `LegacyResponseClassifier`; it must keep
 * these fixtures green and will replace this helper.
 *
 * Naming: `ResponseContractTest` (F34/F39) is a Stage C file; this
 * Stage A file is deliberately named differently to avoid a collision.
 */
class LegacyContractFixturesTest {

    // --- Test-local §5.4 classifier (Stage C replaces it) ---

    private enum class Classification {
        MATCHED_SETTLED,
        MATCHED_NOT_APPLIED,
        MATCHED_UNCONFIRMED,
        AMBIGUOUS,
        LOGGED,
        ACK,
        RETRY,
        BLOCKED,
    }

    private enum class ChainAction {
        STOP, ADVANCE, HALT, RETRY_SAME_ROUTE, RETRY_BACKOFF,
    }

    private data class ContractOutcome(
        val classification: Classification,
        val chain: ChainAction,
        val lastApplyStatus: String? = null,
        /** "auth" (401/403) | "payload" (other 4xx) | "config" (3xx). */
        val blockedCategory: String? = null,
        val retryAfterSeconds: Long? = null,
        val location: String? = null,
        val reviewFlag: Boolean = false,
    )

    /**
     * The §5.4 response-classification table, test-local.
     * `eventType` is "payment" | "heartbeat" | "test".
     */
    private fun classify(
        httpStatus: Int,
        headers: Map<String, String>,
        bodyText: String,
        eventType: String,
    ): ContractOutcome {
        if (httpStatus in 200..299) {
            val body = try {
                JSONObject(bodyText)
            } catch (e: Exception) {
                // Malformed 2xx: payment retries the same route (§5.4);
                // heartbeat/test has no ok=true, so it is a failed attempt.
                return if (eventType == "payment") {
                    ContractOutcome(Classification.RETRY, ChainAction.RETRY_SAME_ROUTE)
                } else {
                    ContractOutcome(Classification.RETRY, ChainAction.RETRY_BACKOFF)
                }
            }
            if (eventType != "payment") {
                // C1: ACK on ok=true whatever `match` contains.
                return if (body.optBoolean("ok", false)) {
                    ContractOutcome(Classification.ACK, ChainAction.STOP)
                } else {
                    ContractOutcome(Classification.RETRY, ChainAction.RETRY_BACKOFF)
                }
            }
            val match = body.optJSONObject("match")
                ?: return ContractOutcome(Classification.RETRY, ChainAction.RETRY_SAME_ROUTE)
            val duplicate = body.optBoolean("duplicate", false)
            return when (val status = match.optString("status", "")) {
                "matched" -> {
                    if (duplicate) {
                        // Apply is definitionally absent on a duplicate;
                        // a stale echoed value is never trusted (§5.4).
                        ContractOutcome(
                            Classification.MATCHED_UNCONFIRMED, ChainAction.STOP,
                            lastApplyStatus = null, reviewFlag = true
                        )
                    } else when (val apply = match.optString("apply", "")) {
                        "ok" -> ContractOutcome(
                            Classification.MATCHED_SETTLED, ChainAction.STOP,
                            lastApplyStatus = "ok"
                        )
                        "skip" -> ContractOutcome(
                            Classification.MATCHED_NOT_APPLIED, ChainAction.STOP,
                            lastApplyStatus = "skip", reviewFlag = true
                        )
                        "error" -> ContractOutcome(
                            Classification.MATCHED_UNCONFIRMED, ChainAction.STOP,
                            lastApplyStatus = "error", reviewFlag = true
                        )
                        else -> ContractOutcome(
                            Classification.MATCHED_UNCONFIRMED, ChainAction.STOP,
                            lastApplyStatus = null, reviewFlag = true
                        )
                    }
                }
                "ambiguous" -> ContractOutcome(
                    Classification.AMBIGUOUS, ChainAction.STOP, reviewFlag = true
                )
                "logged" -> ContractOutcome(Classification.LOGGED, ChainAction.ADVANCE)
                else -> ContractOutcome(Classification.RETRY, ChainAction.RETRY_SAME_ROUTE)
            }
        }
        if (httpStatus == 408 || httpStatus == 429) {
            val retryAfter = headers.entries
                .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                ?.value?.toLongOrNull()?.coerceIn(0, 7200)
            return ContractOutcome(
                Classification.RETRY, ChainAction.RETRY_BACKOFF,
                retryAfterSeconds = retryAfter
            )
        }
        if (httpStatus in 500..599) {
            return ContractOutcome(Classification.RETRY, ChainAction.RETRY_BACKOFF)
        }
        if (httpStatus in 400..499) {
            val category = if (httpStatus == 401 || httpStatus == 403) "auth" else "payload"
            return ContractOutcome(
                Classification.BLOCKED, ChainAction.HALT, blockedCategory = category
            )
        }
        if (httpStatus in 300..399) {
            // C9: 3xx is a route-configuration defect, never followed.
            val location = headers.entries
                .firstOrNull { it.key.equals("Location", ignoreCase = true) }?.value
            return ContractOutcome(
                Classification.BLOCKED, ChainAction.HALT,
                blockedCategory = "config", location = location
            )
        }
        return ContractOutcome(Classification.RETRY, ChainAction.RETRY_BACKOFF)
    }

    // --- Fixture loading ---

    private data class Fixture(
        val name: String,
        val endpoint: String,
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
        return Fixture(
            name = name,
            endpoint = json.getString("endpoint"),
            eventType = json.getString("event_type"),
            httpStatus = json.getInt("http_status"),
            headers = headers,
            bodyText = bodyText,
            bodyJson = bodyJson,
        )
    }

    private fun outcomeOf(name: String): ContractOutcome {
        val f = loadFixture(name)
        return classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
    }

    // --- §5.4 payment taxonomy (G2: fresh payment matched/logged/ambiguous) ---

    @Test
    fun matchedApplyOk_settled_chainStops() {
        val o = outcomeOf("notify-payment-matched-apply-ok")
        assertEquals(Classification.MATCHED_SETTLED, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
        assertEquals("ok", o.lastApplyStatus)
        assertFalse(o.reviewFlag)
    }

    @Test
    fun matchedApplySkip_notApplied_chainStops_reviewFlag() {
        val o = outcomeOf("notify-payment-matched-apply-skip")
        assertEquals(Classification.MATCHED_NOT_APPLIED, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
        assertEquals("skip", o.lastApplyStatus)
        assertTrue(o.reviewFlag)
    }

    @Test
    fun matchedApplyError_unconfirmed_chainStops() {
        val o = outcomeOf("notify-payment-matched-apply-error")
        assertEquals(Classification.MATCHED_UNCONFIRMED, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
        assertEquals("error", o.lastApplyStatus)
        assertTrue(o.reviewFlag)
    }

    @Test
    fun matchedApplyAbsent_unconfirmed_neverAssumesOk() {
        val o = outcomeOf("notify-payment-matched-apply-absent")
        assertEquals(Classification.MATCHED_UNCONFIRMED, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
        assertNull(o.lastApplyStatus)
        assertTrue(o.reviewFlag)
    }

    @Test
    fun matchedDuplicate_unconfirmed_noApplyField() {
        val f = loadFixture("notify-payment-matched-duplicate")
        // The duplicate response carries the stored status with NO apply.
        assertTrue(f.bodyJson!!.getBoolean("duplicate"))
        assertFalse(f.bodyJson.getJSONObject("match").has("apply"))
        val o = classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(Classification.MATCHED_UNCONFIRMED, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
        assertNull(o.lastApplyStatus)
    }

    @Test
    fun matchedDuplicate_staleApplyNeverTrusted() {
        val f = loadFixture("notify-payment-matched-duplicate-stale-apply")
        assertTrue(f.bodyJson!!.getBoolean("duplicate"))
        assertEquals("ok", f.bodyJson.getJSONObject("match").optString("apply"))
        val o = classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(Classification.MATCHED_UNCONFIRMED, o.classification)
        assertNull(o.lastApplyStatus)
    }

    @Test
    fun ambiguous_chainStops_reviewFlag() {
        val o = outcomeOf("notify-payment-ambiguous")
        assertEquals(Classification.AMBIGUOUS, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
        assertTrue(o.reviewFlag)
    }

    @Test
    fun ambiguousDuplicate_chainStops() {
        val o = outcomeOf("notify-payment-ambiguous-duplicate")
        assertEquals(Classification.AMBIGUOUS, o.classification)
        assertEquals(ChainAction.STOP, o.chain)
    }

    @Test
    fun logged_advancesChain() {
        val o = outcomeOf("notify-payment-logged")
        assertEquals(Classification.LOGGED, o.classification)
        assertEquals(ChainAction.ADVANCE, o.chain)
    }

    @Test
    fun duplicateLogged_advancesOnFirstAttemptSemantics() {
        // §5.4 row: 2xx `logged` advances. The §5.5 row 9 rule (a re-POST
        // after an UNKNOWN hop returning `logged` stays UNKNOWN) needs
        // attempt history and is owned by the Stage C classifier.
        val o = outcomeOf("notify-payment-duplicate-logged")
        assertEquals(Classification.LOGGED, o.classification)
        assertEquals(ChainAction.ADVANCE, o.chain)
    }

    // --- T18 corrected (C1): heartbeat/test, 3 cases ---

    @Test
    fun t18_freshNotifyHeartbeat_matchNull_acks() {
        val f = loadFixture("notify-heartbeat-fresh")
        assertTrue(f.bodyJson!!.isNull("match"))
        val o = classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(Classification.ACK, o.classification)
    }

    @Test
    fun t18_catatHeartbeat_matchLogged_acks() {
        val f = loadFixture("catat-heartbeat")
        assertEquals("logged", f.bodyJson!!.getJSONObject("match").getString("status"))
        val o = classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(Classification.ACK, o.classification)
    }

    @Test
    fun t18_duplicateNotifyHeartbeat_matchLogged_acks() {
        val f = loadFixture("notify-heartbeat-duplicate")
        assertTrue(f.bodyJson!!.getBoolean("duplicate"))
        assertEquals("logged", f.bodyJson.getJSONObject("match").getString("status"))
        val o = classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(Classification.ACK, o.classification)
    }

    @Test
    fun t18_freshNotifyTest_matchNull_acks() {
        val o = outcomeOf("notify-test-fresh")
        assertEquals(Classification.ACK, o.classification)
    }

    @Test
    fun t18_catatTest_matchLogged_acks() {
        val o = outcomeOf("catat-test")
        assertEquals(Classification.ACK, o.classification)
    }

    @Test
    fun heartbeatWithoutOk_retries() {
        val o = outcomeOf("notify-heartbeat-ok-false")
        assertEquals(Classification.RETRY, o.classification)
        assertEquals(ChainAction.RETRY_BACKOFF, o.chain)
    }

    // --- Recorder (catat.php): always match.logged ---

    @Test
    fun catatPayment_logged_advances() {
        val f = loadFixture("catat-payment")
        assertEquals("in", f.bodyJson!!.getString("direction"))
        assertTrue(f.bodyJson.getBoolean("recorded"))
        val o = classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(Classification.LOGGED, o.classification)
        assertEquals(ChainAction.ADVANCE, o.chain)
    }

    @Test
    fun catatPaymentDuplicate_recordedFalse_stillLogged() {
        val f = loadFixture("catat-payment-out-duplicate")
        assertFalse(f.bodyJson!!.getBoolean("recorded"))
        assertEquals("out", f.bodyJson.getString("direction"))
        val o = classify(f.httpStatus, f.headers, f.bodyText, f.eventType)
        assertEquals(Classification.LOGGED, o.classification)
        assertEquals(ChainAction.ADVANCE, o.chain)
    }

    // --- Malformed 2xx, 429/408/5xx, 4xx, 3xx (G2) ---

    @Test
    fun malformed2xx_missingMatch_retriesSameRoute() {
        val o = outcomeOf("notify-payment-malformed-no-match")
        assertEquals(Classification.RETRY, o.classification)
        assertEquals(ChainAction.RETRY_SAME_ROUTE, o.chain)
    }

    @Test
    fun malformed2xx_notJson_retriesSameRoute() {
        val o = outcomeOf("notify-payment-malformed-not-json")
        assertEquals(Classification.RETRY, o.classification)
        assertEquals(ChainAction.RETRY_SAME_ROUTE, o.chain)
    }

    @Test
    fun http429_retries_honorsRetryAfter() {
        val o = outcomeOf("http-429-retry-after")
        assertEquals(Classification.RETRY, o.classification)
        assertEquals(ChainAction.RETRY_BACKOFF, o.chain)
        assertEquals(120L, o.retryAfterSeconds)
    }

    @Test
    fun http408_retries() {
        val o = outcomeOf("http-408")
        assertEquals(Classification.RETRY, o.classification)
        assertEquals(ChainAction.RETRY_BACKOFF, o.chain)
    }

    @Test
    fun http500_retriesWithBackoff() {
        val o = outcomeOf("http-500")
        assertEquals(Classification.RETRY, o.classification)
        assertEquals(ChainAction.RETRY_BACKOFF, o.chain)
    }

    @Test
    fun http401_blocked_authCategory_chainHalts() {
        val o = outcomeOf("http-401")
        assertEquals(Classification.BLOCKED, o.classification)
        assertEquals(ChainAction.HALT, o.chain)
        assertEquals("auth", o.blockedCategory)
    }

    @Test
    fun http403_blocked_authCategory() {
        val o = outcomeOf("http-403")
        assertEquals(Classification.BLOCKED, o.classification)
        assertEquals("auth", o.blockedCategory)
    }

    @Test
    fun http400_blocked_payloadCategory_chainHalts() {
        val o = outcomeOf("http-400")
        assertEquals(Classification.BLOCKED, o.classification)
        assertEquals(ChainAction.HALT, o.chain)
        assertEquals("payload", o.blockedCategory)
    }

    @Test
    fun http302_blocked_configCategory_locationSurfaced() {
        val o = outcomeOf("http-302")
        assertEquals(Classification.BLOCKED, o.classification)
        assertEquals(ChainAction.HALT, o.chain)
        assertEquals("config", o.blockedCategory)
        assertEquals("https://example.invalid/other", o.location)
    }

    // --- Fixture-set integrity: every fixture file is covered ---

    @Test
    fun everyFixtureFileHasACase() {
        val expected = setOf(
            "catat-heartbeat.json",
            "catat-payment-out-duplicate.json",
            "catat-payment.json",
            "catat-test.json",
            "http-302.json",
            "http-400.json",
            "http-401.json",
            "http-403.json",
            "http-408.json",
            "http-429-retry-after.json",
            "http-500.json",
            "notify-heartbeat-duplicate.json",
            "notify-heartbeat-fresh.json",
            "notify-heartbeat-ok-false.json",
            "notify-payment-ambiguous-duplicate.json",
            "notify-payment-ambiguous.json",
            "notify-payment-duplicate-logged.json",
            "notify-payment-logged.json",
            "notify-payment-malformed-no-match.json",
            "notify-payment-malformed-not-json.json",
            "notify-payment-matched-apply-absent.json",
            "notify-payment-matched-apply-error.json",
            "notify-payment-matched-apply-ok.json",
            "notify-payment-matched-apply-skip.json",
            "notify-payment-matched-duplicate-stale-apply.json",
            "notify-payment-matched-duplicate.json",
            "notify-test-fresh.json",
        )
        val url = javaClass.classLoader.getResource("fixtures/legacy")
            ?: throw AssertionError("fixtures/legacy resource dir missing")
        val dir = File(url.toURI())
        val actual = dir.listFiles()?.map { it.name }?.toSet()
            ?: throw AssertionError("cannot list fixtures/legacy")
        assertEquals(expected, actual)
        // Every fixture parses and carries the exchange envelope.
        for (name in expected) {
            val f = loadFixture(name.removeSuffix(".json"))
            assertTrue(f.endpoint == "notify" || f.endpoint == "catat")
            assertTrue(f.eventType in setOf("payment", "heartbeat", "test"))
            assertTrue(f.httpStatus in 100..599)
        }
    }
}
