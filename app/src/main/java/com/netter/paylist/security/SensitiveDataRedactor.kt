package com.netter.paylist.security

import com.netter.paylist.privacy.ClassifiedSpan
import com.netter.paylist.privacy.PrivacySpanScan
import com.netter.paylist.privacy.ScanResult

/**
 * Sensitive-content redaction (design §7.1, file plan §D).
 *
 * Pure functions over text: no Android, no I/O, unit-testable on
 * the JVM. The scanner is the shared [PrivacySpanScan] (no
 * divergent regex/policy — file plan §D, R29); this object only
 * applies its verdicts.
 *
 * Redaction rule (§7.1(a)/(b)): CONFIRMED spans are always
 * redacted; AMBIGUOUS spans are redacted too — an unlabelled bare
 * span stays ambiguous and is redacted even if an amount appears
 * elsewhere. Financial ranges are protected by the scanner itself
 * (amounts, labelled references, masked accounts, direction words
 * are never spans).
 */
object SensitiveDataRedactor {

    /** The replacement token (shared with the scanner). */
    const val REDACTION: String = PrivacySpanScan.REDACTION

    /**
     * Replaces every classified span (confirmed + ambiguous) in
     * [text] with `[REDACTED]`. Spans are applied last-first so
     * offsets stay valid.
     */
    fun redact(text: String, scan: ScanResult): String =
        redactSpans(text, scan.spans)

    /** Scans [text] and redacts every classified span. */
    fun redact(text: String): String {
        val scan = PrivacySpanScan.scan(text)
        return redactSpans(text, scan.spans)
    }

    /** Replaces the given spans (offsets into [text]) with `[REDACTED]`. */
    fun redactSpans(text: String, spans: List<ClassifiedSpan>): String {
        if (spans.isEmpty()) return text
        var out = text
        for (span in spans.sortedByDescending { it.start }) {
            if (span.start < 0 || span.end > out.length || span.start >= span.end) continue
            out = out.substring(0, span.start) + REDACTION + out.substring(span.end)
        }
        return out
    }

    /** True when the scan found any CONFIRMED (class-(a)) span. */
    fun hasConfirmedSecret(scan: ScanResult): Boolean = scan.confirmedCount > 0
}
