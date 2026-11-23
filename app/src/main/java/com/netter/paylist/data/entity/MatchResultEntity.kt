package com.netter.paylist.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Advisory match statuses (design §3.1.1, the `advisory_match.status`
 * namespace — never conflated with the legacy wire `match.status` or
 * the §5.4 response-classification taxonomy).
 */
object AdvisoryMatchStatus {
    const val MATCHED: String = "matched"
    const val AMBIGUOUS: String = "ambiguous"
    const val UNMATCHED: String = "unmatched"
    const val MANUAL_REVIEW: String = "manual_review"
}

/** Attribution statuses (design §4.6). */
object AttributionStatus {
    const val ATTRIBUTED: String = "attributed"
    const val DUPLICATE_EVIDENCE: String = "duplicate_evidence"
    const val NONE: String = "none"
}

/**
 * Match rule ids (design §3.1.4 decision table + §3.1.2): the
 * rule that fired, recorded on every `match_results` row so a
 * reviewer can replay the decision. Shared by the DAO's atomic
 * attribution (which sets the conflict-branch rules) and the
 * matcher.
 */
object MatchRuleId {
    /** Full agreement: order + amount + currency + direction. */
    const val MATCH: String = "P3-MATCH"

    /** Case B: verified same bank transaction ⇒ duplicate evidence. */
    const val DUPLICATE_EVIDENCE: String = "P3-B"

    /** Case C: already attributed, identity unverified ⇒ ambiguous. */
    const val AMBIGUOUS: String = "P3-C"

    /** Case D: concurrent loser ⇒ ambiguous. */
    const val CONCURRENT_LOSER: String = "P3-D"

    /** Case E: amount/currency/direction mismatch ⇒ manual review. */
    const val MISMATCH: String = "P3-E"

    /** No order number extracted. */
    const val NO_ORDER: String = "P3-NO-ORDER"

    /** No registered merchant shape matches. */
    const val NO_MERCHANT: String = "P3-NO-MERCHANT"

    /** Cross-merchant shape collision. */
    const val MULTI_MERCHANT: String = "P3-MULTI-MERCHANT"

    /** Order absent from the imported registry. */
    const val UNMATCHED: String = "P3-UNMATCHED"

    /** Registry snapshot missing, expired, or unverified. */
    const val REGISTRY_UNUSABLE: String = "P3-REGISTRY-UNUSABLE"
}

/**
 * The `match_results` table (design §4.6, P3): advisory transaction
 * matching results. Advisory only: a `matched` result never settles
 * anything, never marks an invoice paid, never triggers a
 * `notify.php` apply, and never produces a `MATCHED_SETTLED` claim
 * (§3.1.1, §3.1.6). Advisory results never write to
 * `deliveries.last_match_status`.
 *
 * One-attribution-per-order rule (§3.1.4, §4.6): at most one
 * `attributed` row per (`merchant_id`, `order_number`). The design
 * specifies a unique partial index
 * (`WHERE attribution_status = 'attributed'`); Room cannot declare
 * a partial index, so the constraint is enforced with the derived
 * column [attributedOrderKey] — set to
 * `merchant_id + "|" + order_number` exactly when (and only when)
 * `attribution_status` is `attributed`, NULL otherwise — plus a
 * plain unique index on it (SQLite treats NULLs as distinct, so
 * non-attributed rows never collide). The atomic attribution
 * transaction in `MatchResultDao` records the losing candidate as
 * an audit row; a unique-index conflict alone never proves
 * duplicate-bank-transaction identity (§3.1.4 case D).
 *
 * `source_account_masked` (masked to last 4) is the ONLY stored form
 * of the source account; the full verified identity is never
 * persisted (R35).
 */
@Entity(
    tableName = "match_results",
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["event_id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["event_id"]),
        Index(value = ["merchant_id", "order_number"]),
        Index(value = ["attributed_order_key"], unique = true),
    ],
)
data class MatchResultEntity(
    /** Primary key, UUID. */
    @PrimaryKey
    @ColumnInfo(name = "match_id")
    val matchId: String,

    /** FK -> events, ON DELETE CASCADE. */
    @ColumnInfo(name = "event_id")
    val eventId: String,

    /** Canonical merchant identity, or null when unmatched. */
    @ColumnInfo(name = "merchant_id")
    val merchantId: String?,

    /** Merchant-specified order number, or null when unmatched. */
    @ColumnInfo(name = "order_number")
    val orderNumber: String?,

    /** Bank-generated transaction reference, or null. */
    @ColumnInfo(name = "bank_reference")
    val bankReference: String?,

    /** Source account masked to last 4 — the only stored form (R35). */
    @ColumnInfo(name = "source_account_masked")
    val sourceAccountMasked: String?,

    /** `matched` / `ambiguous` / `unmatched` / `manual_review`. */
    @ColumnInfo(name = "advisory_match_status")
    val advisoryMatchStatus: String,

    /** Match confidence, 0.0–1.0. */
    @ColumnInfo(name = "confidence")
    val confidence: Double,

    /** The rule id that fired. */
    @ColumnInfo(name = "rule_id")
    val ruleId: String,

    /** Registry version the attribution used (audit trail, §3.1.9). */
    @ColumnInfo(name = "registry_version")
    val registryVersion: String,

    /** `attributed` / `duplicate_evidence` / `none`. */
    @ColumnInfo(name = "attribution_status")
    val attributionStatus: String,

    /** For `duplicate_evidence`: the `match_id` of the first attribution. */
    @ColumnInfo(name = "attributed_to_match_id")
    val attributedToMatchId: String? = null,

    /** Full input set as JSON, so a reviewer can replay the decision. */
    @ColumnInfo(name = "audit_json")
    val auditJson: String,

    /** Verified bank reference + source/merchant identity, when supported (§4.6, R29). */
    @ColumnInfo(name = "evidence_identity")
    val evidenceIdentity: String? = null,

    /** Review reason, when the result needs operator review (§4.6, R29). */
    @ColumnInfo(name = "review_reason")
    val reviewReason: String? = null,

    /** The `match_id` this row supersedes, for operator corrections (§4.6, R29). */
    @ColumnInfo(name = "supersedes_match_id")
    val supersedesMatchId: String? = null,

    /**
     * Derived one-attribution key: `merchant_id|order_number` exactly
     * when `attribution_status` is `attributed`, NULL otherwise.
     * Unique-indexed (see the class KDoc).
     */
    @ColumnInfo(name = "attributed_order_key")
    val attributedOrderKey: String? = null,

    /** Row creation time (epoch ms). */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)
