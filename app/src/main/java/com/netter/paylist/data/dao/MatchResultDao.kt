package com.netter.paylist.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.netter.paylist.data.entity.AdvisoryMatchStatus
import com.netter.paylist.data.entity.AttributionStatus
import com.netter.paylist.data.entity.MatchResultEntity
import com.netter.paylist.data.entity.MatchRuleId

/** Outcome of an atomic attribution attempt (design §3.1.4 case D). */
enum class AttributionOutcome {
    /** This candidate became the one `attributed` row for the order. */
    ATTRIBUTED,

    /** Verified same bank transaction: recorded as `duplicate_evidence` against the first attribution. */
    DUPLICATE_EVIDENCE,

    /** Unverified/distinct identity: `ambiguous` / `NEEDS_REVIEW`, never a second attribution. */
    AMBIGUOUS,
}

/**
 * DAO for [MatchResultEntity] (design §4.6, P3).
 *
 * Attribution insert in one atomic transaction: on a conflicting
 * existing attribution, the losing candidate is recorded as an audit
 * row — `duplicate_evidence` only where verified same-bank-transaction
 * evidence proves it, otherwise `ambiguous`/`NEEDS_REVIEW` (design
 * §3.1.4 case D; a unique-index conflict alone never proves duplicate
 * identity; do not rely on `INSERT OR IGNORE` alone).
 *
 * The transaction serialises concurrent attributions (Room runs
 * transactions on a single thread), so the check-then-act inside
 * [attribute] is safe: at most one `attributed` row per
 * (`merchant_id`, `order_number`) exists at any time, backstopped by
 * the unique index on `attributed_order_key`.
 */
@Dao
interface MatchResultDao {
    @Insert
    suspend fun insert(result: MatchResultEntity)

    @Update
    suspend fun update(result: MatchResultEntity)

    /**
     * Operator decision on a review row (D5): marks the row with a
     * new advisory status + review note. Returns the rows updated
     * (0 when the row is gone).
     */
    @Query(
        "UPDATE match_results SET advisory_match_status = :status, rule_id = :ruleId, " +
            "review_reason = :reviewReason WHERE match_id = :matchId",
    )
    suspend fun updateAdvisory(
        matchId: String,
        status: String,
        ruleId: String,
        reviewReason: String?,
    ): Int

    @Query("SELECT * FROM match_results WHERE match_id = :id")
    suspend fun getById(id: String): MatchResultEntity?

    @Query("SELECT * FROM match_results WHERE event_id = :eventId")
    suspend fun getByEventId(eventId: String): List<MatchResultEntity>

    @Query("SELECT * FROM match_results WHERE merchant_id = :merchantId AND order_number = :orderNumber")
    suspend fun getByOrder(merchantId: String, orderNumber: String): List<MatchResultEntity>

    /**
     * Rows needing operator review (Stage D review surface):
     * `ambiguous` + `manual_review`, newest first. Read-only in D3 —
     * the manual attribution pick is a D5 follow-up.
     */
    @Query(
        "SELECT * FROM match_results WHERE advisory_match_status IN ('ambiguous', 'manual_review') " +
            "ORDER BY created_at_ms DESC",
    )
    suspend fun getNeedingReview(): List<MatchResultEntity>

    @Query(
        "SELECT * FROM match_results WHERE merchant_id = :merchantId AND order_number = :orderNumber " +
            "AND attribution_status = 'attributed'",
    )
    suspend fun getAttributed(merchantId: String, orderNumber: String): MatchResultEntity?

    /**
     * Atomically attempts to attribute [candidate] to its order.
     *
     * - No existing attribution for the order → the candidate is
     *   inserted as `attributed` (with its `attributed_order_key`
     *   set) and [AttributionOutcome.ATTRIBUTED] is returned.
     * - An attribution already exists → the candidate is inserted as
     *   an audit row: `duplicate_evidence` linked to the first
     *   attribution when [verifiedSameBankTransaction] is true,
     *   otherwise `ambiguous` with `attribution_status = none`
     *   (operator `NEEDS_REVIEW`). Never a second attribution.
     *
     * The caller (Stage D `TransactionMatcher`) supplies the
     * verified-same-bank-transaction evidence; this DAO never infers
     * it from the conflict itself.
     */
    @Transaction
    suspend fun attribute(
        candidate: MatchResultEntity,
        verifiedSameBankTransaction: Boolean,
    ): AttributionOutcome {
        val merchantId = candidate.merchantId
        val orderNumber = candidate.orderNumber
        require(!merchantId.isNullOrBlank() && !orderNumber.isNullOrBlank()) {
            "attribution requires merchant_id and order_number"
        }
        val existing = getAttributed(merchantId, orderNumber)
        if (existing == null) {
            insert(
                candidate.copy(
                    attributionStatus = AttributionStatus.ATTRIBUTED,
                    attributedOrderKey = "$merchantId|$orderNumber",
                ),
            )
            return AttributionOutcome.ATTRIBUTED
        }
        if (verifiedSameBankTransaction) {
            insert(
                candidate.copy(
                    advisoryMatchStatus = AdvisoryMatchStatus.MATCHED,
                    attributionStatus = AttributionStatus.DUPLICATE_EVIDENCE,
                    attributedToMatchId = existing.matchId,
                    attributedOrderKey = null,
                    ruleId = MatchRuleId.DUPLICATE_EVIDENCE,
                ),
            )
            return AttributionOutcome.DUPLICATE_EVIDENCE
        }
        insert(
            candidate.copy(
                advisoryMatchStatus = AdvisoryMatchStatus.AMBIGUOUS,
                attributionStatus = AttributionStatus.NONE,
                attributedToMatchId = null,
                attributedOrderKey = null,
                // The conflict branch of this transaction is the
                // concurrent-loser case (D): the matcher handles
                // the pre-existing-attribution case (C) directly,
                // so reaching here means the attribution appeared
                // between the matcher's pre-read and this call.
                ruleId = MatchRuleId.CONCURRENT_LOSER,
                reviewReason = "order already attributed; identity unverified (case C/D)",
            ),
        )
        return AttributionOutcome.AMBIGUOUS
    }

    /**
     * Operator attribution of an EXISTING review row (Stage D D5).
     * Same atomic decision table as [attribute], but UPDATES the
     * stored row instead of inserting — the row is already in the
     * table (ambiguous/manual_review), so an insert would conflict
     * on the primary key. The operator examined the evidence, so
     * the caller passes verified=true; the unverified branch is
     * kept for symmetry with [attribute].
     */
    @Transaction
    suspend fun attributeExisting(
        candidate: MatchResultEntity,
        verifiedSameBankTransaction: Boolean,
        reviewNote: String,
    ): AttributionOutcome {
        val merchantId = candidate.merchantId
        val orderNumber = candidate.orderNumber
        require(!merchantId.isNullOrBlank() && !orderNumber.isNullOrBlank()) {
            "attribution requires merchant_id and order_number"
        }
        val existing = getAttributed(merchantId, orderNumber)
        if (existing == null) {
            update(
                candidate.copy(
                    advisoryMatchStatus = AdvisoryMatchStatus.MATCHED,
                    attributionStatus = AttributionStatus.ATTRIBUTED,
                    attributedOrderKey = "$merchantId|$orderNumber",
                    reviewReason = reviewNote,
                ),
            )
            return AttributionOutcome.ATTRIBUTED
        }
        if (verifiedSameBankTransaction) {
            update(
                candidate.copy(
                    advisoryMatchStatus = AdvisoryMatchStatus.MATCHED,
                    attributionStatus = AttributionStatus.DUPLICATE_EVIDENCE,
                    attributedToMatchId = existing.matchId,
                    attributedOrderKey = null,
                    ruleId = MatchRuleId.DUPLICATE_EVIDENCE,
                    reviewReason = reviewNote,
                ),
            )
            return AttributionOutcome.DUPLICATE_EVIDENCE
        }
        update(
            candidate.copy(
                advisoryMatchStatus = AdvisoryMatchStatus.AMBIGUOUS,
                attributionStatus = AttributionStatus.NONE,
                attributedToMatchId = null,
                attributedOrderKey = null,
                ruleId = MatchRuleId.CONCURRENT_LOSER,
                reviewReason = reviewNote,
            ),
        )
        return AttributionOutcome.AMBIGUOUS
    }
}
