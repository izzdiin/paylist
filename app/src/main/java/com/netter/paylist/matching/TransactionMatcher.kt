package com.netter.paylist.matching

import com.netter.paylist.data.dao.AttributionOutcome
import com.netter.paylist.data.dao.MatchResultDao
import com.netter.paylist.data.entity.AdvisoryMatchStatus
import com.netter.paylist.data.entity.AttributionStatus
import com.netter.paylist.data.entity.MatchResultEntity
import com.netter.paylist.data.entity.MatchRuleId
import com.netter.paylist.parser.Direction
import org.json.JSONObject
import java.util.UUID

/**
 * Advisory Transaction Matching only (design §3.1.2/§3.1.4, P3 —
 * owner-decided 2026-11-15).
 *
 * Answers *which order does this bank transaction belong to?*
 * Input: `order_number` + `bank_reference` + amount + currency +
 * direction + source account. Output: `advisory_match.status`
 * (`matched`/`ambiguous`/`unmatched`/`manual_review`) with
 * confidence, the `rule_id` that fired, and an audit row.
 *
 * Advisory only (§3.1.1, §3.1.6): a `matched` result never
 * settles anything, never marks an invoice paid, never triggers a
 * `notify.php` apply, never produces a `MATCHED_SETTLED` claim,
 * and never writes `deliveries.last_match_status`. This class
 * touches only `match_results` (via [MatchResultDao]) and the
 * [OrderRegistry]; it has no delivery, transport, or settlement
 * dependency by construction.
 *
 * Merchant resolution: `merchant_id` is never derived from a
 * notification span and never inferred from free text (§3.1.4).
 * The merchant comes from the registered order-number shape
 * ([MerchantOrderPattern]): the order number is matched against
 * the registered shapes; zero matches ⇒ `manual_review`, more
 * than one ⇒ `manual_review` (cross-merchant collision, out of
 * scope for the matcher).
 *
 * Decision table (§3.1.4, R29 — applied in this order):
 * - (A) Same `dedupe_key` + same content hash: notification
 *   duplicate — handled by the capture layer before this matcher
 *   ever runs (see `CaptureCoordinatorTest`); never reaches here.
 * - (B) Verified same bank transaction (confirmed
 *   `bank_reference` + merchant scope matching the first
 *   attribution's evidence) ⇒ `duplicate_evidence` against the
 *   first attribution; never a second attribution.
 * - (C) Different/unknown bank identity claiming an
 *   already-attributed order ⇒ `ambiguous`/`NEEDS_REVIEW`.
 * - (D) Two concurrent claims for an unattributed order: the DB
 *   constraint chooses one `attributed`; the loser is `ambiguous`
 *   unless verified same-bank evidence proves `duplicate_evidence`.
 *   A unique-index conflict alone never proves duplicate identity.
 * - (E) Amount/currency/direction mismatch ⇒ `manual_review`,
 *   always, regardless of apparent reference reuse.
 */
class TransactionMatcher(
    private val matchDao: MatchResultDao,
    private val registry: OrderRegistry,
    private val merchantPatterns: List<MerchantOrderPattern> = emptyList(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * A merchant-registered order-number shape (§3.1.4, P3(a)):
     * explicit merchant configuration, registered manually by the
     * operator — no auto-learning. [shape] matches the order
     * number itself (e.g. `INV-\d+`); it is used both to find an
     * order number in text and to resolve the merchant from a
     * parsed order number.
     */
    data class MerchantOrderPattern(
        val merchantId: String,
        val shape: Regex,
    )

    /** The matcher's input: the parsed identity + the event's structured fields. */
    data class MatchInput(
        val eventId: String,
        /** Labelled order number from the identity parser, or null. */
        val orderNumber: String?,
        /** Confirmed bank reference, or null. */
        val bankReference: String?,
        val amount: Long,
        val currency: String,
        val direction: Direction,
        val sourceAccountMasked: String?,
        /** The notification text, for shape-scanning when the parser found no order number. */
        val text: String? = null,
    )

    /** The matcher's verdict (also persisted as a `match_results` row). */
    data class MatchOutcome(
        val advisoryMatchStatus: String,
        val attributionStatus: String,
        val confidence: Double,
        val ruleId: String,
        val merchantId: String?,
        val orderNumber: String?,
        val matchId: String,
        val registryVersion: String,
        val reviewReason: String?,
    )

    companion object {
        /**
         * v1.1 fixed confidence scale (documented, not calibrated):
         * a full agreement match is 0.9 — the payer is unconfirmed
         * by the registry, so confidence is capped below 1.0
         * (§3.1.5); indeterminate outcomes are 0.5; no match is 0.0.
         */
        const val CONFIDENCE_MATCHED: Double = 0.9
        const val CONFIDENCE_INDETERMINATE: Double = 0.5
        const val CONFIDENCE_UNMATCHED: Double = 0.0
    }

    /**
     * Runs the advisory match for one event and persists the
     * result row. Never settles, never applies, never posts.
     */
    suspend fun match(input: MatchInput): MatchOutcome {
        val now = clock()
        val orderNumber = input.orderNumber ?: findOrderNumberByShape(input.text)
        if (orderNumber == null) {
            return record(
                input, orderNumber = null, merchantId = null,
                status = AdvisoryMatchStatus.MANUAL_REVIEW,
                attribution = AttributionStatus.NONE,
                confidence = CONFIDENCE_INDETERMINATE,
                ruleId = MatchRuleId.NO_ORDER,
                registryVersion = "none",
                reviewReason = "no order_number extracted (absent reference)",
                now = now,
            )
        }
        // Resolve the merchant from the registered shapes.
        val merchants = merchantPatterns.filter { it.shape.matches(orderNumber) }
        if (merchants.isEmpty()) {
            return record(
                input, orderNumber = orderNumber, merchantId = null,
                status = AdvisoryMatchStatus.MANUAL_REVIEW,
                attribution = AttributionStatus.NONE,
                confidence = CONFIDENCE_INDETERMINATE,
                ruleId = MatchRuleId.NO_MERCHANT,
                registryVersion = "none",
                reviewReason = "no registered merchant shape matches the order number",
                now = now,
            )
        }
        if (merchants.size > 1) {
            return record(
                input, orderNumber = orderNumber, merchantId = null,
                status = AdvisoryMatchStatus.MANUAL_REVIEW,
                attribution = AttributionStatus.NONE,
                confidence = CONFIDENCE_INDETERMINATE,
                ruleId = MatchRuleId.MULTI_MERCHANT,
                registryVersion = "none",
                reviewReason = "cross-merchant collision: ${merchants.size} shapes match",
                now = now,
            )
        }
        val merchantId = merchants[0].merchantId
        // The registry must be usable (ACTIVE + VERIFIED + unexpired)
        // before any attribution; otherwise the event is held.
        if (!registry.isSnapshotUsable(merchantId)) {
            return record(
                input, orderNumber = orderNumber, merchantId = merchantId,
                status = AdvisoryMatchStatus.MANUAL_REVIEW,
                attribution = AttributionStatus.NONE,
                confidence = CONFIDENCE_INDETERMINATE,
                ruleId = MatchRuleId.REGISTRY_UNUSABLE,
                registryVersion = registry.activeSnapshot(merchantId)?.registryVersion ?: "none",
                reviewReason = "registry snapshot missing, expired, or unverified",
                now = now,
            )
        }
        val order = registry.lookup(merchantId, orderNumber)
        if (order == null) {
            return record(
                input, orderNumber = orderNumber, merchantId = merchantId,
                status = AdvisoryMatchStatus.UNMATCHED,
                attribution = AttributionStatus.NONE,
                confidence = CONFIDENCE_UNMATCHED,
                ruleId = MatchRuleId.UNMATCHED,
                registryVersion = registry.activeSnapshot(merchantId)?.registryVersion ?: "none",
                reviewReason = "order absent from the imported registry",
                now = now,
            )
        }
        // (E) Amount/currency/direction must agree with the
        // registered order; any mismatch ⇒ manual_review, never
        // auto-attribution. An unknown direction cannot agree.
        val directionAgrees = input.direction != Direction.UNKNOWN &&
            input.direction.name.lowercase() == order.direction
        if (input.amount != order.amount ||
            !input.currency.equals(order.currency, ignoreCase = true) ||
            !directionAgrees
        ) {
            return record(
                input, orderNumber = orderNumber, merchantId = merchantId,
                status = AdvisoryMatchStatus.MANUAL_REVIEW,
                attribution = AttributionStatus.NONE,
                confidence = CONFIDENCE_INDETERMINATE,
                ruleId = MatchRuleId.MISMATCH,
                registryVersion = order.registryVersion,
                reviewReason = "amount/currency/direction mismatch against the registered order",
                now = now,
            )
        }
        // Agreement: attempt the atomic attribution (cases B/C/D).
        // Verified same-bank-transaction evidence (case B): the
        // confirmed bank_reference + merchant scope match the first
        // attribution's evidence identity. A unique-index conflict
        // alone never proves it.
        val evidenceIdentity = evidenceIdentity(input.bankReference, merchantId)
        val existing = matchDao.getAttributed(merchantId, orderNumber)
        if (existing != null) {
            val verifiedSameBankTransaction = evidenceIdentity != null &&
                existing.evidenceIdentity == evidenceIdentity
            if (!verifiedSameBankTransaction) {
                // Case C: the order is already attributed and the
                // identity is unverified — recorded directly (no
                // race: the existing attribution is immutable, so
                // the DAO's conflict branch cannot apply here).
                return record(
                    input, orderNumber = orderNumber, merchantId = merchantId,
                    status = AdvisoryMatchStatus.AMBIGUOUS,
                    attribution = AttributionStatus.NONE,
                    confidence = CONFIDENCE_INDETERMINATE,
                    ruleId = MatchRuleId.AMBIGUOUS,
                    registryVersion = order.registryVersion,
                    reviewReason = "order already attributed; identity unverified (case C)",
                    now = now,
                )
            }
            // Case B: verified same bank transaction — the DAO
            // records the duplicate evidence against the first
            // attribution (never a second attribution).
            val candidate = candidateRow(
                input, merchantId, orderNumber, order.registryVersion,
                evidenceIdentity, now,
            )
            val outcome = matchDao.attribute(candidate, true)
            check(outcome == AttributionOutcome.DUPLICATE_EVIDENCE) {
                "case B attribution returned $outcome"
            }
            return MatchOutcome(
                advisoryMatchStatus = AdvisoryMatchStatus.MATCHED,
                attributionStatus = AttributionStatus.DUPLICATE_EVIDENCE,
                confidence = CONFIDENCE_MATCHED,
                ruleId = MatchRuleId.DUPLICATE_EVIDENCE,
                merchantId = merchantId,
                orderNumber = orderNumber,
                matchId = candidate.matchId,
                registryVersion = order.registryVersion,
                reviewReason = null,
            )
        }
        // No existing attribution: the atomic attempt attributes
        // this candidate — or, on a concurrent claim landing first,
        // records it as the case-D loser (ambiguous).
        val candidate = candidateRow(
            input, merchantId, orderNumber, order.registryVersion,
            evidenceIdentity, now,
        )
        return when (matchDao.attribute(candidate, false)) {
            AttributionOutcome.ATTRIBUTED -> MatchOutcome(
                advisoryMatchStatus = AdvisoryMatchStatus.MATCHED,
                attributionStatus = AttributionStatus.ATTRIBUTED,
                confidence = CONFIDENCE_MATCHED,
                ruleId = MatchRuleId.MATCH,
                merchantId = merchantId,
                orderNumber = orderNumber,
                matchId = candidate.matchId,
                registryVersion = order.registryVersion,
                reviewReason = null,
            )
            AttributionOutcome.DUPLICATE_EVIDENCE -> MatchOutcome(
                // Unreachable: verified=false never yields duplicate
                // evidence. Defensive — the DAO decides, not this branch.
                advisoryMatchStatus = AdvisoryMatchStatus.MATCHED,
                attributionStatus = AttributionStatus.DUPLICATE_EVIDENCE,
                confidence = CONFIDENCE_MATCHED,
                ruleId = MatchRuleId.DUPLICATE_EVIDENCE,
                merchantId = merchantId,
                orderNumber = orderNumber,
                matchId = candidate.matchId,
                registryVersion = order.registryVersion,
                reviewReason = null,
            )
            AttributionOutcome.AMBIGUOUS -> MatchOutcome(
                advisoryMatchStatus = AdvisoryMatchStatus.AMBIGUOUS,
                attributionStatus = AttributionStatus.NONE,
                confidence = CONFIDENCE_INDETERMINATE,
                ruleId = MatchRuleId.CONCURRENT_LOSER,
                merchantId = merchantId,
                orderNumber = orderNumber,
                matchId = candidate.matchId,
                registryVersion = order.registryVersion,
                reviewReason = "order already attributed; identity unverified (case C/D)",
            )
        }
    }

    /** Builds the attribution candidate row (advisory matched, unaudited until the DAO decides). */
    private fun candidateRow(
        input: MatchInput,
        merchantId: String,
        orderNumber: String,
        registryVersion: String,
        evidenceIdentity: String?,
        now: Long,
    ): MatchResultEntity = MatchResultEntity(
        matchId = UUID.randomUUID().toString(),
        eventId = input.eventId,
        merchantId = merchantId,
        orderNumber = orderNumber,
        bankReference = input.bankReference,
        sourceAccountMasked = input.sourceAccountMasked,
        advisoryMatchStatus = AdvisoryMatchStatus.MATCHED,
        confidence = CONFIDENCE_MATCHED,
        ruleId = MatchRuleId.MATCH,
        registryVersion = registryVersion,
        attributionStatus = AttributionStatus.NONE,
        auditJson = auditJson(input, merchantId, orderNumber, registryVersion, MatchRuleId.MATCH, now),
        evidenceIdentity = evidenceIdentity,
        reviewReason = null,
        createdAtMs = now,
    )

    /** Finds an order number in [text] by merchant shape (first pattern that finds one). */
    private fun findOrderNumberByShape(text: String?): String? {
        if (text == null) return null
        for (pattern in merchantPatterns) {
            val found = pattern.shape.find(text)?.value
            if (found != null) return found
        }
        return null
    }

    /**
     * The verified same-bank-transaction identity (case B): the
     * confirmed bank reference together with its merchant scope.
     * Null when no confirmed bank reference exists — an absent
     * reference can never verify sameness.
     */
    private fun evidenceIdentity(bankReference: String?, merchantId: String): String? {
        if (bankReference.isNullOrBlank()) return null
        return "bankref:$bankReference|merchant:$merchantId"
    }

    /** Persists a non-attribution result row and returns the outcome. */
    private suspend fun record(
        input: MatchInput,
        orderNumber: String?,
        merchantId: String?,
        status: String,
        attribution: String,
        confidence: Double,
        ruleId: String,
        registryVersion: String,
        reviewReason: String?,
        now: Long,
    ): MatchOutcome {
        val row = MatchResultEntity(
            matchId = UUID.randomUUID().toString(),
            eventId = input.eventId,
            merchantId = merchantId,
            orderNumber = orderNumber,
            bankReference = input.bankReference,
            sourceAccountMasked = input.sourceAccountMasked,
            advisoryMatchStatus = status,
            confidence = confidence,
            ruleId = ruleId,
            registryVersion = registryVersion,
            attributionStatus = attribution,
            auditJson = auditJson(input, merchantId, orderNumber, registryVersion, ruleId, now),
            evidenceIdentity = if (merchantId != null) {
                evidenceIdentity(input.bankReference, merchantId)
            } else {
                null
            },
            reviewReason = reviewReason,
            createdAtMs = now,
        )
        matchDao.insert(row)
        return MatchOutcome(
            advisoryMatchStatus = status,
            attributionStatus = attribution,
            confidence = confidence,
            ruleId = ruleId,
            merchantId = merchantId,
            orderNumber = orderNumber,
            matchId = row.matchId,
            registryVersion = registryVersion,
            reviewReason = reviewReason,
        )
    }

    /**
     * The audit row: the full input set, so a later reviewer can
     * replay the decision. Structured fields only — never raw
     * text, never an OTP/PIN/auth token, never a full account
     * number (only the masked form).
     */
    private fun auditJson(
        input: MatchInput,
        merchantId: String?,
        orderNumber: String?,
        registryVersion: String,
        ruleId: String,
        now: Long,
    ): String = JSONObject().apply {
        put("event_id", input.eventId)
        put("order_number", orderNumber)
        put("bank_reference", input.bankReference)
        put("amount", input.amount)
        put("currency", input.currency)
        put("direction", input.direction.name.lowercase())
        put("source_account_masked", input.sourceAccountMasked)
        put("merchant_id", merchantId)
        put("registry_version", registryVersion)
        put("rule_id", ruleId)
        put("decided_at_ms", now)
    }.toString()
}
