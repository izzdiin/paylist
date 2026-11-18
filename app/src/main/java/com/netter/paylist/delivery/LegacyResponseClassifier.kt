package com.netter.paylist.delivery

import org.json.JSONObject

/**
 * The §5.4 response-classification taxonomy, production.
 *
 * Pure function over (HTTP status, response headers, response body,
 * event type, re-POST flag) — no I/O, no state. The Stage A fixture
 * test (`LegacyContractFixturesTest`) pins the same taxonomy with a
 * test-local oracle over recorded response shapes; this object must
 * keep every one of those fixtures green.
 *
 * Chain semantics (§5.4/§5.5):
 * - matched/ambiguous: STOP — the chain never continues past a
 *   matched or ambiguous hop, whatever the apply outcome.
 * - logged: ADVANCE to the next route (ACK if this was the last).
 * - nonretryable 4xx / any 3xx: HALT + BLOCKED (visible, operator
 *   action required). 3xx is never followed (C9).
 * - network/408/429/5xx/malformed 2xx: RETRY — same route for a
 *   malformed payment 2xx, backoff otherwise.
 *
 * The one hard rule: `last_apply_status` is NEVER assumed `'ok'`.
 * A duplicate response carries no apply information at all, and a
 * stale echoed `apply` value is never trusted (§5.4).
 *
 * Re-POST after an unknown hop (§5.5 rows 8–10, `isRepostAfterUnknown`):
 * - 8a: matched + apply=ok (non-duplicate) is the ONLY in-band way
 *   an unknown hop clears as settled → MATCHED_SETTLED.
 * - 8b: matched + apply≠ok/absent → stays MATCHED_UNCONFIRMED. Note
 *   `apply=skip` on a re-POST is UNCONFIRMED, not NOT_APPLIED: the
 *   response reflects the original attempt, whose outcome was never
 *   observed, so no apply value on it is trusted.
 * - 8c: duplicate=true → 8b, even with a stale echoed `apply`.
 * - 9: `logged` → stays UNKNOWN_PENDING_RECONCILIATION: a duplicate
 *   `logged` is consistent with both "first attempt never committed"
 *   and "first attempt matched, duplicate now logged" — it proves
 *   nothing about the first attempt.
 * - 10: 4xx/5xx/timeout/malformed-2xx on the re-POST → stays UNKNOWN:
 *   a re-POST failure proves nothing about the first attempt.
 *
 * Heartbeat/test ignore the re-POST flag: heartbeat outcomes are
 * never settlement (the server dedupes by 15-minute bucket), so a
 * heartbeat re-POST is always safe to retry or ACK on ok=true.
 */
enum class ResponseClassification {
    MATCHED_SETTLED,
    MATCHED_NOT_APPLIED,
    MATCHED_UNCONFIRMED,
    AMBIGUOUS,
    LOGGED,
    ACK,
    RETRY,
    BLOCKED,
    /** §5.5 rows 9–10: the hop stays unknown; reconciliation only. */
    UNKNOWN_PENDING_RECONCILIATION,
}

enum class ChainAction {
    STOP,
    ADVANCE,
    HALT,
    RETRY_SAME_ROUTE,
    RETRY_BACKOFF,
}

data class ClassificationOutcome(
    val classification: ResponseClassification,
    val chain: ChainAction,
    val lastApplyStatus: String? = null,
    /** "auth" (401/403) | "payload" (other 4xx) | "config" (3xx). */
    val blockedCategory: String? = null,
    /** Parsed `Retry-After` (seconds), clamped to [0, 7200]; null if absent/invalid. */
    val retryAfterSeconds: Long? = null,
    /** Surfaced `Location` header on a 3xx; null if absent. */
    val location: String? = null,
    val reviewFlag: Boolean = false,
)

object LegacyResponseClassifier {

    private val UNKNOWN_HELD = ClassificationOutcome(
        ResponseClassification.UNKNOWN_PENDING_RECONCILIATION, ChainAction.HALT,
        reviewFlag = true
    )

    /**
     * Classify one HTTP exchange.
     *
     * @param httpStatus the response status code.
     * @param headers response headers (keys matched case-insensitively).
     * @param bodyText the response body as text.
     * @param eventType "payment" | "heartbeat" | "test".
     * @param isRepostAfterUnknown true when this POST re-attempts a hop
     *   whose first attempt outcome is unknown (§5.5 rows 8–10). Ignored
     *   for heartbeat/test (never settlement).
     */
    fun classify(
        httpStatus: Int,
        headers: Map<String, String>,
        bodyText: String,
        eventType: String,
        isRepostAfterUnknown: Boolean = false,
    ): ClassificationOutcome {
        if (httpStatus in 200..299) {
            val body = try {
                JSONObject(bodyText)
            } catch (e: Exception) {
                // Malformed 2xx: a re-POST failure proves nothing about
                // the first attempt (§5.5 row 10 logic); a first attempt
                // retries the same route (§5.4). Heartbeat/test has no
                // ok=true, so it is a failed attempt.
                return if (eventType == "payment") {
                    if (isRepostAfterUnknown) {
                        UNKNOWN_HELD
                    } else {
                        ClassificationOutcome(
                            ResponseClassification.RETRY, ChainAction.RETRY_SAME_ROUTE
                        )
                    }
                } else {
                    ClassificationOutcome(
                        ResponseClassification.RETRY, ChainAction.RETRY_BACKOFF
                    )
                }
            }
            if (eventType != "payment") {
                // C1: ACK on ok=true whatever `match` contains.
                return if (body.optBoolean("ok", false)) {
                    ClassificationOutcome(ResponseClassification.ACK, ChainAction.STOP)
                } else {
                    ClassificationOutcome(
                        ResponseClassification.RETRY, ChainAction.RETRY_BACKOFF
                    )
                }
            }
            val match = body.optJSONObject("match")
                ?: return if (isRepostAfterUnknown) {
                    UNKNOWN_HELD
                } else {
                    ClassificationOutcome(
                        ResponseClassification.RETRY, ChainAction.RETRY_SAME_ROUTE
                    )
                }
            val duplicate = body.optBoolean("duplicate", false)
            return when (val status = match.optString("status", "")) {
                "matched" -> {
                    if (duplicate) {
                        // Apply is definitionally absent on a duplicate;
                        // a stale echoed value is never trusted (§5.4, 8c).
                        ClassificationOutcome(
                            ResponseClassification.MATCHED_UNCONFIRMED, ChainAction.STOP,
                            lastApplyStatus = null, reviewFlag = true
                        )
                    } else if (isRepostAfterUnknown) {
                        // Rows 8a/8b: only apply=ok clears an unknown hop.
                        if (match.optString("apply", "") == "ok") {
                            ClassificationOutcome(
                                ResponseClassification.MATCHED_SETTLED, ChainAction.STOP,
                                lastApplyStatus = "ok"
                            )
                        } else {
                            ClassificationOutcome(
                                ResponseClassification.MATCHED_UNCONFIRMED, ChainAction.STOP,
                                lastApplyStatus = null, reviewFlag = true
                            )
                        }
                    } else when (val apply = match.optString("apply", "")) {
                        "ok" -> ClassificationOutcome(
                            ResponseClassification.MATCHED_SETTLED, ChainAction.STOP,
                            lastApplyStatus = "ok"
                        )
                        "skip" -> ClassificationOutcome(
                            ResponseClassification.MATCHED_NOT_APPLIED, ChainAction.STOP,
                            lastApplyStatus = "skip", reviewFlag = true
                        )
                        "error" -> ClassificationOutcome(
                            ResponseClassification.MATCHED_UNCONFIRMED, ChainAction.STOP,
                            lastApplyStatus = "error", reviewFlag = true
                        )
                        else -> ClassificationOutcome(
                            ResponseClassification.MATCHED_UNCONFIRMED, ChainAction.STOP,
                            lastApplyStatus = null, reviewFlag = true
                        )
                    }
                }
                "ambiguous" -> ClassificationOutcome(
                    ResponseClassification.AMBIGUOUS, ChainAction.STOP, reviewFlag = true
                )
                "logged" -> if (isRepostAfterUnknown) {
                    // Row 9: a re-POST `logged` proves nothing about the
                    // first attempt — the hop stays unknown.
                    UNKNOWN_HELD
                } else {
                    ClassificationOutcome(
                        ResponseClassification.LOGGED, ChainAction.ADVANCE
                    )
                }
                else -> if (isRepostAfterUnknown) {
                    UNKNOWN_HELD
                } else {
                    ClassificationOutcome(
                        ResponseClassification.RETRY, ChainAction.RETRY_SAME_ROUTE
                    )
                }
            }
        }
        // Non-2xx on a re-POST proves nothing about the first attempt
        // (§5.5 row 10): the hop stays unknown, never retried past it.
        if (isRepostAfterUnknown && eventType == "payment") return UNKNOWN_HELD
        if (httpStatus == 408 || httpStatus == 429) {
            val retryAfter = headers.entries
                .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                ?.value?.toLongOrNull()?.coerceIn(0, 7200)
            return ClassificationOutcome(
                ResponseClassification.RETRY, ChainAction.RETRY_BACKOFF,
                retryAfterSeconds = retryAfter
            )
        }
        if (httpStatus in 500..599) {
            return ClassificationOutcome(
                ResponseClassification.RETRY, ChainAction.RETRY_BACKOFF
            )
        }
        if (httpStatus in 400..499) {
            val category = if (httpStatus == 401 || httpStatus == 403) "auth" else "payload"
            return ClassificationOutcome(
                ResponseClassification.BLOCKED, ChainAction.HALT, blockedCategory = category
            )
        }
        if (httpStatus in 300..399) {
            // C9: 3xx is a route-configuration defect, never followed.
            val location = headers.entries
                .firstOrNull { it.key.equals("Location", ignoreCase = true) }?.value
            return ClassificationOutcome(
                ResponseClassification.BLOCKED, ChainAction.HALT,
                blockedCategory = "config", location = location
            )
        }
        return ClassificationOutcome(ResponseClassification.RETRY, ChainAction.RETRY_BACKOFF)
    }
}
