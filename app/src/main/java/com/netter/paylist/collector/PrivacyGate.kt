package com.netter.paylist.collector

import com.netter.paylist.privacy.ClassifiedSpan
import com.netter.paylist.privacy.PrivacySpanScan
import com.netter.paylist.security.SensitiveDataRedactor

/**
 * The Stage D PrivacyGate (design §7.1 C4 four-class rule, file
 * plan §B).
 *
 * Runs before ANY durable write, on both the live and rescan
 * paths. A confirmed secret must never appear in a stored raw
 * payload, `legacy_payload_json`, local logs, diagnostics, or an
 * extracted `reference`.
 *
 * - (a) CONFIRMED — OTP/PIN/password/auth token: never persisted
 *   raw. A separable span → `[REDACTED]`, the rest preserved. A
 *   pure-auth event (no financial record) → the whole event
 *   dropped ([Decision.DROP_PURE_AUTH]), counter + metadata-only
 *   capture-log row only (addendum 06, LH-D2).
 * - (b) AMBIGUOUS — credential-like spans with no confirming local
 *   context: redacted even if an amount appears elsewhere; a
 *   financial cue elsewhere does NOT promote them.
 * - (c) FINANCIAL — amount, labelled reference, masked account,
 *   direction cue, names: preserved (the scanner protects these
 *   ranges before classifying spans).
 * - (d) Legacy exception — never covers persistence of class (a).
 *
 * OTP is never a reference: no parser may treat a redacted span
 * as a reference, and no reference is ever extracted from a
 * class-(a) span.
 *
 * The scanner is the shared [PrivacySpanScan] (no divergent
 * regex/policy — file plan §D, R29). This class is pure (no
 * Android, no I/O) except for the B-eligibility check, which
 * reads the consent record through [ConsentStore].
 *
 * Staging re-scan gate (§8.3 R11, defence in depth): after
 * redaction the merged text is re-scanned; a surviving
 * class-(a) span means redaction missed something — the text is
 * dropped entirely and only structured fields are staged
 * ([GateResult.inseparable] = true).
 */
class PrivacyGate(
    private val consentStore: ConsentStore? = null,
) {

    /** The gate's verdict for one snapshot. */
    enum class Decision {
        /** Proceed with the redacted fields. */
        ALLOW,

        /** Pure-auth event: drop, counter + metadata-only row only. */
        DROP_PURE_AUTH,

        /** No amount and no auth content: the caller's NO_AMOUNT path. */
        DROP_NO_AMOUNT,
    }

    data class GateResult(
        val decision: Decision,
        /** Redacted title ("" when the text was dropped). */
        val redactedTitle: String,
        /** Redacted text (merged text/bigText; "" when dropped). */
        val redactedText: String,
        /** True when any span was redacted. */
        val redacted: Boolean,
        /**
         * True when the financial record and a class-(a) span could
         * not be safely separated (the re-scan gate dropped the
         * text); only structured fields survive (§7.2).
         */
        val inseparable: Boolean,
        /** True when a CONFIRMED (class-(a)) span was present. */
        val hasConfirmedSecret: Boolean,
        /** All classified spans across the three fields (audit counts only). */
        val spans: List<ClassifiedSpan>,
    )

    /**
     * Gates one snapshot. [amount] is the parsed amount, or null
     * when no amount parsed (the caller parses before gating — the
     * amount is needed to tell a pure-auth event from a
     * no-amount financial notification).
     */
    fun gate(snapshot: NotificationSnapshot, amount: Long?): GateResult {
        val titleScan = PrivacySpanScan.scan(snapshot.title)
        val textScan = PrivacySpanScan.scan(snapshot.text)
        val bigScan = PrivacySpanScan.scan(snapshot.bigText)
        val allSpans = titleScan.spans + textScan.spans + bigScan.spans
        val hasConfirmed = allSpans.any { it.confirmed }

        if (amount == null) {
            // No financial record. A confirmed auth span makes this
            // a pure-auth event (§7.1(a), §7.3A(j)): dropped at
            // capture, never B-eligible on any route.
            if (hasConfirmed) {
                return GateResult(
                    decision = Decision.DROP_PURE_AUTH,
                    redactedTitle = "",
                    redactedText = "",
                    redacted = false,
                    inseparable = false,
                    hasConfirmedSecret = true,
                    spans = allSpans,
                )
            }
            return GateResult(
                decision = Decision.DROP_NO_AMOUNT,
                redactedTitle = "",
                redactedText = "",
                redacted = false,
                inseparable = false,
                hasConfirmedSecret = false,
                spans = allSpans,
            )
        }

        val title = SensitiveDataRedactor.redact(snapshot.title, titleScan)
        val text = SensitiveDataRedactor.redact(snapshot.text, textScan)
        val big = SensitiveDataRedactor.redact(snapshot.bigText, bigScan)
        // Re-scan gate: a surviving class-(a) span means redaction
        // missed something — drop the text, keep structured fields.
        val rescan = PrivacySpanScan.scan("$title $text $big")
        if (rescan.spans.any { it.confirmed }) {
            return GateResult(
                decision = Decision.ALLOW,
                redactedTitle = "",
                redactedText = "",
                redacted = true,
                inseparable = true,
                hasConfirmedSecret = true,
                spans = allSpans,
            )
        }
        val mergedText = text.ifEmpty { big }
        return GateResult(
            decision = Decision.ALLOW,
            redactedTitle = title,
            redactedText = mergedText,
            redacted = allSpans.isNotEmpty(),
            inseparable = false,
            hasConfirmedSecret = hasConfirmed,
            spans = allSpans,
        )
    }

    /**
     * B-eligibility for one route (§7.3A(j), §7.3 scope rule (a)):
     * a mixed financial+OTP event (amount parsed AND a confirmed
     * secret span) on a route with a valid, unexpired, unrevoked
     * P6 opt-in at capture time. Pure-auth events are never
     * B-eligible. Without a consent store, no route is B-eligible
     * (fail-closed).
     */
    suspend fun isBEligible(
        routeId: String,
        snapshot: NotificationSnapshot,
        amount: Long?,
        nowMs: Long,
    ): Boolean {
        if (amount == null) return false
        val store = consentStore ?: return false
        val gate = gate(snapshot, amount)
        if (!gate.hasConfirmedSecret) return false
        return store.isRawOptInValid(routeId, nowMs)
    }
}
