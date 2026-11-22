package com.netter.paylist.privacy

import com.netter.paylist.migration.Inspection
import com.netter.paylist.migration.LegacyQueueInspector
import com.netter.paylist.migration.LegacyQueueItem
import com.netter.paylist.migration.LegacyQueueSnapshotSource
import org.json.JSONObject

/**
 * Span classes for the audit (§7.1, §7.7). The audit records classes
 * + count, never content.
 */
enum class SpanClass {
    /** OTP / verification code. */
    OTP,

    /** PIN. */
    PIN,

    /** Password / kata sandi. */
    PASSWORD,

    /** Auth token. */
    TOKEN,

    /** Bare unlabelled code: ambiguous, fail-closed. */
    AMBIGUOUS_CODE,
}

/** One classified span (offsets into the scanned text). */
data class ClassifiedSpan(
    val start: Int,
    val end: Int,
    val spanClass: SpanClass,
    /** True for class (a) CONFIRMED; false for class (b) AMBIGUOUS. */
    val confirmed: Boolean,
)

/** Result of scanning one title/text string. */
data class ScanResult(
    val spans: List<ClassifiedSpan>,
    /** The text with CONFIRMED spans replaced by [PrivacySpanScan.REDACTION]. */
    val redactedText: String,
    val confirmedCount: Int,
    val ambiguousCount: Int,
    /** True when any financial content (amount, labelled ref, masked account, direction word) is present. */
    val hasFinancialContent: Boolean,
)

/**
 * The class-(a)/(b)/(c) span scanner (design §7.1, four-class rule).
 *
 * THE shared implementation: Stage D `PrivacyGate` MUST reuse this
 * object — no divergent regex/policy (file plan §D, R29). The scan is
 * pure (no Android, no I/O) so it is unit-testable on the JVM.
 *
 * Rules, in order:
 * - FINANCIAL ranges are protected first: amounts (`Rp`/`IDR` +
 *   digits), labelled references (`No. Ref`, `ID Transaksi`, `Kode
 *   Transaksi`, ...), masked accounts (`****1234`), labelled referral
 *   codes. A digit run inside a protected range is never a span.
 * - A code span is a MAXIMAL digit run of 4–12 digits (a 20-digit
 *   token is not a span), a spaced digit group (`123 456`,
 *   `1234 5678`), or a mixed alphanumeric token (6–12 chars, letters
 *   + digits) adjacent to an auth cue.
 * - Each span binds to its LOCAL cue only (same clause, ≤40 chars):
 *   an auth cue promotes it to CONFIRMED (a); a financial cue keeps
 *   it FINANCIAL (c); no cue leaves it AMBIGUOUS (b). A financial cue
 *   elsewhere in the message does NOT promote a span, and an auth cue
 *   wins over a financial cue for the same span (§7.1(b)).
 * - Direction words are never secret spans.
 */
object PrivacySpanScan {
    const val REDACTION: String = "[REDACTED]"

    /** Max distance (chars) between a span and its local cue. */
    const val LOCAL_WINDOW: Int = 40

    private val authCues: List<Pair<Regex, SpanClass>> = listOf(
        Regex("(?i)\\bkode\\s+otp\\b") to SpanClass.OTP,
        Regex("(?i)\\b(one[- ]time\\s+password|verification\\s+code|auth\\s+code|security\\s+code)\\b") to SpanClass.OTP,
        Regex("(?i)\\b(kode\\s+verifikasi|kode\\s+autentikasi|kode\\s+otentikasi|kode\\s+keamanan|kode\\s+rahasia)\\b") to SpanClass.OTP,
        Regex("(?i)\\botp\\b") to SpanClass.OTP,
        Regex("(?i)\\bpin\\b") to SpanClass.PIN,
        Regex("(?i)\\b(kata\\s+sandi|password|kata\\s+laluan)\\b") to SpanClass.PASSWORD,
        // "token listrik" / "token PLN" / "token pulsa" are prepaid
        // utility/credit tokens, not auth tokens — never an auth cue.
        Regex("(?i)\\btoken\\b(?!\\s+(listrik|pln|pulsa))") to SpanClass.TOKEN,
    )

    private val financialLabels: List<Regex> = listOf(
        Regex("(?i)\\b(no\\.?\\s*ref|nomor\\s+referensi|id\\s+transaksi|kode\\s+transaksi|no\\.?\\s*transaksi|kode\\s+bayar|kode\\s+pembayaran|nomor\\s+pesanan|order\\s+id|invoice)\\b"),
        Regex("(?i)\\bkode\\s+referral\\b"),
    )

    private val amountPattern: Regex =
        Regex("(?i)\\b(rp|idr)\\s*[\\d][\\d.,]*")

    private val maskedAccountPattern: Regex =
        Regex("[*xX]{2,}\\d{4}")

    private val directionWords: Regex =
        Regex("(?i)\\b(masuk|keluar|transfer|diterima|dikirim|pembayaran|berhasil|sukses|gagal|debet|kredit|tarik|setor|bayar|terbayar|dipotong|bertambah|berkurang)\\b")

    private val spacedGroupPattern: Regex =
        Regex("\\b\\d{3} \\d{3}\\b|\\b\\d{4} \\d{4}\\b")

    // Bounded alphanumeric token (6–12 chars). The letter+digit mix
    // is checked in code (findCodeSpans): a regex lookahead cannot
    // express "contains a letter AND a digit within this token" — an
    // unanchored `.*` lookahead scans the rest of the string and
    // matches plain words like "adalah" whenever digits appear later.
    private val alnumTokenPattern: Regex =
        Regex("\\b[A-Za-z0-9]{6,12}\\b")

    private val segmentDelimiters: Regex = Regex("[.!?;\\n]")

    fun scan(text: String): ScanResult {
        if (text.isEmpty()) {
            return ScanResult(emptyList(), text, 0, 0, false)
        }
        val financialRanges = findFinancialRanges(text)
        val hasFinancial = financialRanges.isNotEmpty() ||
            directionWords.containsMatchIn(text) ||
            financialLabels.any { it.containsMatchIn(text) }
        val codeSpans = findCodeSpans(text, financialRanges)
        val classified = codeSpans.mapNotNull { (start, end) ->
            classifySpan(text, start, end)
        }
        var redacted = text
        // Redact from the end backwards so earlier offsets stay valid.
        for (span in classified.filter { it.confirmed }.sortedByDescending { it.start }) {
            redacted = redacted.substring(0, span.start) + REDACTION + redacted.substring(span.end)
        }
        return ScanResult(
            spans = classified,
            redactedText = redacted,
            confirmedCount = classified.count { it.confirmed },
            ambiguousCount = classified.count { !it.confirmed },
            hasFinancialContent = hasFinancial,
        )
    }

    private fun findFinancialRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (pattern in listOf(amountPattern, maskedAccountPattern)) {
            pattern.findAll(text).forEach { ranges.add(it.range) }
        }
        // Labelled reference: the label + the following value token.
        for (label in financialLabels) {
            label.findAll(text).forEach { match ->
                val after = text.substring(match.range.last + 1)
                val value = Regex("^\\s*[:#\\-]?\\s*\\S+").find(after)
                val end = if (value != null) {
                    match.range.last + 1 + value.range.last
                } else {
                    match.range.last
                }
                ranges.add(match.range.first..end)
            }
        }
        return ranges
    }

    private fun findCodeSpans(text: String, financialRanges: List<IntRange>): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        // Maximal digit runs of 4–12 digits.
        Regex("\\d+").findAll(text).forEach { match ->
            val len = match.value.length
            if (len in 4..12) {
                val range = match.range.first..match.range.last
                if (financialRanges.none { it.first <= range.last && range.first <= it.last }) {
                    spans.add(match.range.first to match.range.last + 1)
                }
            }
        }
        // Spaced digit groups (123 456, 1234 5678).
        spacedGroupPattern.findAll(text).forEach { match ->
            val range = match.range.first..match.range.last
            if (financialRanges.none { it.first <= range.last && range.first <= it.last }) {
                spans.add(match.range.first to match.range.last + 1)
            }
        }
        // Mixed alphanumeric tokens: only spans when an auth cue is local
        // (checked in classifySpan); collect candidates here. A token
        // must mix letters and digits — a pure word ("adalah") or a
        // pure digit run is not an alphanumeric code.
        alnumTokenPattern.findAll(text).forEach { match ->
            val token = match.value
            if (!token.any { it.isLetter() } || !token.any { it.isDigit() }) return@forEach
            val range = match.range.first..match.range.last
            if (financialRanges.none { it.first <= range.last && range.first <= it.last }) {
                // Only a candidate: classification decides by local cue.
                spans.add(match.range.first to match.range.last + 1)
            }
        }
        return spans.sortedBy { it.first }.distinct()
    }

    private enum class LocalCue { AUTH, FINANCIAL, NONE }

    private fun classifySpan(text: String, start: Int, end: Int): ClassifiedSpan? {
        val window = localWindow(text, start, end)
        val authClass = authCues.firstOrNull { (cue, _) -> cue.containsMatchIn(window) }?.second
        if (authClass != null) {
            return ClassifiedSpan(start, end, authClass, confirmed = true)
        }
        if (financialLabels.any { it.containsMatchIn(window) } ||
            amountPattern.containsMatchIn(window)
        ) {
            // A financial cue local to the span: class (c), preserved.
            return null
        }
        val token = text.substring(start, end)
        // A mixed alphanumeric token without an auth cue is not a span
        // at all (too weak to quarantine on); bare digits are AMBIGUOUS.
        if (token.any { it.isLetter() }) return null
        return ClassifiedSpan(start, end, SpanClass.AMBIGUOUS_CODE, confirmed = false)
    }

    /**
     * The local window for a span: up to [LOCAL_WINDOW] chars each way,
     * bounded by segment delimiters (`.`, `!`, `?`, `;`, newline) so a
     * cue in another clause never binds to this span (§7.1(b)).
     */
    private fun localWindow(text: String, start: Int, end: Int): String {
        var left = (start - LOCAL_WINDOW).coerceAtLeast(0)
        for (i in (start - 1) downTo left) {
            if (segmentDelimiters.matches(text[i].toString())) {
                left = i + 1
                break
            }
        }
        var right = (end + LOCAL_WINDOW).coerceAtMost(text.length)
        for (i in end until right) {
            if (segmentDelimiters.matches(text[i].toString())) {
                right = i
                break
            }
        }
        return text.substring(left, right)
    }
}

/** Per-item gate verdict (§7.7, F50). */
sealed interface GateVerdict {
    /** Clean: no sensitive span; payload bytes pass through unchanged. */
    data object Clean : GateVerdict

    /** Confirmed spans redacted span-level; the audit is classes + count, never content. */
    data class Redacted(
        val redactedTitle: String,
        val redactedText: String,
        val spanCount: Int,
        val spanClasses: Set<SpanClass>,
    ) : GateVerdict

    /** Uncertain (fail-closed): never POSTed, imported, or exported. */
    data class Quarantined(
        val reason: String,
        val spanClasses: Set<SpanClass>,
    ) : GateVerdict
}

/** Result of one [LegacyQueuePrivacyGate.reprocess] run. */
data class ReprocessReport(
    val inspected: Int,
    val clean: Int,
    val redacted: Int,
    val quarantined: Int,
    val alreadyProcessed: Int,
    val corrupt: Int,
    /** False when the durable write failed: the caller must not proceed as if reprocessed. */
    val writeOk: Boolean,
)

/**
 * The P9 per-item privacy gate for the pre-v1.1 legacy queue (design
 * §7.7, R29/R30; file plan §D). The Stage C prerequisite: this gate
 * MUST run before any post-upgrade legacy POST and before every Room
 * import, and F50/F54 must PASS before any Stage C legacy
 * import/cutover activation.
 *
 * Per item (no bulk action of any kind — owner decision 2026-11-15):
 * - CLEAN: the payload passes through byte-identical; an inspection
 *   marker is persisted without changing the payload bytes.
 * - CONFIRMED spans (class (a)): the spans are replaced span-level
 *   with `[REDACTED]` and the redacted payload is written back
 *   durably via `saveQueueSync` (`commit()` + read-back); the marker
 *   records the span count + classes (never content) and the
 *   timestamp. `dedupe_key`, amount, direction, route order, `next`,
 *   `hop_outcomes` and the server-effect audit are preserved.
 * - UNCERTAIN (class (b) ambiguous spans, or a pure-auth item with no
 *   financial record): the item is quarantined (`privacy_quarantine:
 *   true` + reason), never POSTed, never imported, never exported.
 *   The raw text stays in the legacy holding pen until the operator
 *   approves the redacted form or discards it (Stage D review
 *   surface); the at-rest residue is disclosed, not claimed away.
 *
 * Markers live on the queue item (top level), NEVER inside the
 * payload: marker fields must not leak into the wire POST body.
 * The gate never deletes a queue item and never touches any item
 * other than the one it inspected.
 *
 * Quarantine discard (Stage D): discarding a quarantined item deletes
 * the raw payload but MUST retain a financial-event audit record in
 * Room (amount, `dedupe_key`, package, timestamps, span classes,
 * decision, operator, reason — never raw spans). No discard path
 * exists in Stage C, so no audit is owed yet; the review surface in
 * Stage D owns it.
 */
class LegacyQueuePrivacyGate {
    companion object {
        /** Set true when the item has been through this gate. */
        const val KEY_REPROCESSED: String = "privacy_reprocessed"

        /** Number of redacted spans (0 for a clean item). */
        const val KEY_REDACTED_SPANS: String = "privacy_redacted_spans"

        /** Epoch ms of the inspection. */
        const val KEY_REPROCESSED_AT_MS: String = "privacy_reprocessed_at_ms"

        /** Set true when the item is quarantined. */
        const val KEY_QUARANTINE: String = "privacy_quarantine"

        /** Quarantine reason (a stable code, never content). */
        const val KEY_QUARANTINE_REASON: String = "privacy_quarantine_reason"

        /** Span classes found, comma-joined (audit, never content). */
        const val KEY_SPAN_CLASSES: String = "privacy_span_classes"

        const val REASON_AMBIGUOUS: String = "ambiguous_span"
        const val REASON_PURE_AUTH: String = "pure_auth_no_financial_record"
    }

    /**
     * Inspects one raw queue item (pure decision, no I/O). The caller
     * persists the verdict via [reprocess] (or a single-item
     * `saveQueueSync` on the sender path).
     */
    fun inspect(item: JSONObject, nowMs: Long): GateVerdict {
        val payload = item.optJSONObject("payload") ?: return GateVerdict.Quarantined(
            REASON_AMBIGUOUS,
            emptySet(),
        )
        val title = payload.optString("title")
        val text = payload.optString("text")
        val titleScan = PrivacySpanScan.scan(title)
        val textScan = PrivacySpanScan.scan(text)
        val hasStructuredAmount = payload.has("amount")
        val hasFinancial = hasStructuredAmount ||
            titleScan.hasFinancialContent || textScan.hasFinancialContent
        val confirmed = (titleScan.spans + textScan.spans).filter { it.confirmed }
        val ambiguous = (titleScan.spans + textScan.spans).filter { !it.confirmed }
        val classes = (titleScan.spans + textScan.spans).map { it.spanClass }.toSet()
        return when {
            ambiguous.isNotEmpty() -> GateVerdict.Quarantined(REASON_AMBIGUOUS, classes)
            confirmed.isNotEmpty() && !hasFinancial ->
                GateVerdict.Quarantined(REASON_PURE_AUTH, classes)
            confirmed.isNotEmpty() -> GateVerdict.Redacted(
                redactedTitle = titleScan.redactedText,
                redactedText = textScan.redactedText,
                spanCount = confirmed.size,
                spanClasses = classes,
            )
            else -> GateVerdict.Clean
        }
    }

    /**
     * Reprocesses the whole legacy queue, one item at a time, writing
     * markers (and redacted payloads) back durably via `saveQueueSync`.
     * Already-processed items are skipped (idempotent re-run); items
     * without a parseable payload are counted corrupt and left alone.
     * Returns the report; when [ReprocessReport.writeOk] is false the
     * caller must NOT proceed as if the queue were reprocessed.
     */
    fun reprocess(source: LegacyQueueSnapshotSource, nowMs: Long): ReprocessReport {
        val queue = source.queueSnapshot()
        var clean = 0
        var redacted = 0
        var quarantined = 0
        var alreadyProcessed = 0
        var corrupt = 0
        var changed = false
        for (item in queue) {
            if (item.optBoolean(KEY_REPROCESSED, false) ||
                item.optBoolean(KEY_QUARANTINE, false)
            ) {
                alreadyProcessed++
                continue
            }
            if (item.optJSONObject("payload") == null) {
                corrupt++
                continue
            }
            when (val verdict = inspect(item, nowMs)) {
                is GateVerdict.Clean -> {
                    markReprocessed(item, spanCount = 0, classes = emptySet(), nowMs)
                    clean++
                    changed = true
                }
                is GateVerdict.Redacted -> {
                    val payload = item.getJSONObject("payload")
                    payload.put("title", verdict.redactedTitle)
                    payload.put("text", verdict.redactedText)
                    markReprocessed(item, verdict.spanCount, verdict.spanClasses, nowMs)
                    redacted++
                    changed = true
                }
                is GateVerdict.Quarantined -> {
                    item.put(KEY_QUARANTINE, true)
                    item.put(KEY_QUARANTINE_REASON, verdict.reason)
                    item.put(KEY_SPAN_CLASSES, verdict.spanClasses.joinToString(","))
                    item.put(KEY_REPROCESSED_AT_MS, nowMs)
                    quarantined++
                    changed = true
                }
            }
        }
        val writeOk = if (changed) source.saveQueueSync(queue) else true
        return ReprocessReport(
            inspected = clean + redacted + quarantined,
            clean = clean,
            redacted = redacted,
            quarantined = quarantined,
            alreadyProcessed = alreadyProcessed,
            corrupt = corrupt,
            writeOk = writeOk,
        )
    }

    private fun markReprocessed(
        item: JSONObject,
        spanCount: Int,
        classes: Set<SpanClass>,
        nowMs: Long,
    ) {
        item.put(KEY_REPROCESSED, true)
        item.put(KEY_REDACTED_SPANS, spanCount)
        item.put(KEY_SPAN_CLASSES, classes.joinToString(","))
        item.put(KEY_REPROCESSED_AT_MS, nowMs)
    }
}

/**
 * The production P9 inspector for the migration (F54): IMPORTABLE
 * only for inspected, non-quarantined items. Fail-closed: an item
 * that never went through the gate is QUARANTINED, never imported.
 */
object GateBackedInspector : LegacyQueueInspector {
    override fun inspect(item: LegacyQueueItem): Inspection =
        if (item.privacyQuarantined || !item.privacyReprocessed) {
            Inspection.QUARANTINED
        } else {
            Inspection.IMPORTABLE
        }
}

/**
 * Durable P9 gate: call under SenderLock before committing DRAINING —
 * and again before a post-commit re-drain (R08): leftovers that
 * arrived after the first preflight carry no markers, and the
 * fail-closed inspector would quarantine them instead of importing.
 */
class CutoverPrivacyPreflight(
    private val gate: LegacyQueuePrivacyGate = LegacyQueuePrivacyGate(),
) {
    data class Result(val ok: Boolean, val reason: String)

    fun run(source: LegacyQueueSnapshotSource, nowMs: Long): Result {
        val before = try {
            source.queueSnapshot().map { it.optString("qkey") }
        } catch (e: Exception) {
            return Result(false, "queue_read_failed")
        }
        val report = try {
            gate.reprocess(source, nowMs)
        } catch (e: Exception) {
            return Result(false, "inspection_exception")
        }
        if (!report.writeOk) return Result(false, "privacy_write_failed")
        if (report.corrupt > 0) return Result(false, "corrupt_queue_items")
        val after = try {
            source.queueSnapshot()
        } catch (e: Exception) {
            return Result(false, "privacy_readback_failed")
        }
        if (after.map { it.optString("qkey") } != before) {
            return Result(false, "queue_identity_changed")
        }
        if (after.any { !it.optBoolean(LegacyQueuePrivacyGate.KEY_REPROCESSED, false) &&
                !it.optBoolean(LegacyQueuePrivacyGate.KEY_QUARANTINE, false) }) {
            return Result(false, "privacy_marker_missing")
        }
        return Result(true, "verified")
    }
}
