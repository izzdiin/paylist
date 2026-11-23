package com.netter.paylist.matching

import com.netter.paylist.data.entity.ClassificationDao
import com.netter.paylist.data.entity.ClassificationEntity
import org.json.JSONObject
import java.util.UUID

/**
 * Advisory Transaction Classification only (design §3.1.2, P3 —
 * owner-decided 2026-11-15).
 *
 * Answers *what purpose does this transaction serve?* Input:
 * `purpose_text` + sender/recipient + configured category rules.
 * Output: `classification.category` with its own confidence,
 * `rule_id`, and audit row.
 *
 * Independent of the matcher (§3.1.2): this class reads no matcher
 * output and writes no matcher field — a transaction can be
 * `matched` to an order and still `UNCLASSIFIED`, or classified
 * `donation` with no order match. There is no FK between the two
 * result tables. Advisory only: classification never settles,
 * never applies, never writes `deliveries.last_match_status`.
 *
 * Rules are configurable categories with priority (config file in
 * the design; constructor-injected here — no rule-editing UI in
 * the MVP). The default rule set is empty: with no rules every
 * transaction is `UNCLASSIFIED`.
 */
class TransactionClassifier(
    private val classificationDao: ClassificationDao,
    private val rules: List<CategoryRule> = emptyList(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * One category rule: [keywords] (lowercased substrings)
     * matched against the purpose/sender/recipient text.
     * [priority] orders the rules — lower fires first.
     */
    data class CategoryRule(
        val category: String,
        val keywords: List<String>,
        val priority: Int,
        val ruleId: String,
    )

    /** The classifier's input: the parsed purpose + party fields. */
    data class ClassificationInput(
        val eventId: String,
        val purposeText: String?,
        val senderName: String?,
        val recipientName: String?,
    )

    /** The classification verdict (also persisted as a `classification_results` row). */
    data class ClassificationOutcome(
        val category: String,
        val confidence: Double,
        val ruleId: String,
        val classificationId: String,
    )

    companion object {
        /** Default category: no rule fired. */
        const val UNCLASSIFIED: String = "UNCLASSIFIED"

        /** Rule id recorded when no rule fires. */
        const val RULE_DEFAULT: String = "default"

        /**
         * v1.1 fixed confidence scale (documented, not calibrated):
         * a keyword-rule match is 0.8 (a keyword is evidence, not
         * proof); unclassified is 0.0 (no classification confidence).
         */
        const val CONFIDENCE_RULE_MATCH: Double = 0.8
        const val CONFIDENCE_UNCLASSIFIED: Double = 0.0
    }

    /**
     * Classifies one event and persists the result row. Reads no
     * matcher output; writes no matcher field.
     */
    suspend fun classify(input: ClassificationInput): ClassificationOutcome {
        val now = clock()
        val haystack = listOfNotNull(
            input.purposeText, input.senderName, input.recipientName,
        ).joinToString(" ").lowercase()
        val fired = rules.sortedBy { it.priority }.firstOrNull { rule ->
            rule.keywords.any { keyword -> haystack.contains(keyword.lowercase()) }
        }
        val (category, confidence, ruleId) = if (fired != null) {
            Triple(fired.category, CONFIDENCE_RULE_MATCH, fired.ruleId)
        } else {
            Triple(UNCLASSIFIED, CONFIDENCE_UNCLASSIFIED, RULE_DEFAULT)
        }
        val row = ClassificationEntity(
            classificationId = UUID.randomUUID().toString(),
            eventId = input.eventId,
            category = category,
            confidence = confidence,
            ruleId = ruleId,
            auditJson = auditJson(input, ruleId, now),
            createdAtMs = now,
        )
        classificationDao.insert(row)
        return ClassificationOutcome(category, confidence, ruleId, row.classificationId)
    }

    /**
     * The audit row: the full input set, so a later reviewer can
     * replay the decision. Structured fields only — never raw
     * notification text.
     */
    private fun auditJson(input: ClassificationInput, ruleId: String, now: Long): String =
        JSONObject().apply {
            put("event_id", input.eventId)
            put("purpose_text", input.purposeText)
            put("sender_name", input.senderName)
            put("recipient_name", input.recipientName)
            put("rule_id", ruleId)
            put("decided_at_ms", now)
        }.toString()
}
