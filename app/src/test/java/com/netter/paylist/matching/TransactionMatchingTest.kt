package com.netter.paylist.matching

import com.netter.paylist.FakeClassificationDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeMatchResultDao
import com.netter.paylist.FakeOrderRegistryDao
import com.netter.paylist.FakeRegistryImportMetadataDao
import com.netter.paylist.FakeStore
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.dao.AttributionOutcome
import com.netter.paylist.data.entity.AdvisoryMatchStatus
import com.netter.paylist.data.entity.AttributionStatus
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.data.entity.MatchResultEntity
import com.netter.paylist.data.entity.MatchRuleId
import com.netter.paylist.data.entity.OrderRegistryEntity
import com.netter.paylist.matching.TransactionClassifier.CategoryRule
import com.netter.paylist.matching.TransactionMatcher.MatchInput
import com.netter.paylist.matching.TransactionMatcher.MerchantOrderPattern
import com.netter.paylist.parser.Direction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Stage D D2b: advisory Transaction Matching + Classification
 * (design §3.1, F46–F48, F51).
 *
 * F46 — order-number match, cross-account uniqueness. F47 —
 * classification independence. F48 — no settlement on reference.
 * F51 — the P3 identity decision table (§3.1.4 cases A–E).
 *
 * Case A (same `dedupe_key` + same content hash ⇒ notification
 * duplicate, no second event) is enforced by the capture layer
 * before the matcher runs — covered by `CaptureCoordinatorTest`
 * (rescan duplicate) and `CaptureStagingTest` (pre/post-import
 * hash comparison incl. restart). This file pins the matcher-side
 * consequence: matching the same event twice never yields a
 * second attribution.
 */
class TransactionMatchingTest {

    companion object {
        const val NOW = 1_794_960_000_000L
        const val MERCHANT_A = "merchant-a"
        const val MERCHANT_B = "merchant-b"
    }

    private class Harness {
        val store = FakeStore()
        val eventDao = FakeEventDao(store)
        val deliveryDao = FakeDeliveryDao(store)
        val matchDao = FakeMatchResultDao(store)
        val classificationDao = FakeClassificationDao(store)
        val registryDao = FakeOrderRegistryDao(store)
        val metadataDao = FakeRegistryImportMetadataDao(store)
        var now = NOW
        val registry = OrderRegistry(registryDao, metadataDao, clock = { now })

        val patterns = listOf(
            MerchantOrderPattern(MERCHANT_A, Regex("INV-\\d+")),
            MerchantOrderPattern(MERCHANT_B, Regex("ORD-\\d+")),
        )
        val matcher = TransactionMatcher(matchDao, registry, patterns, clock = { now })
        val classifier = TransactionClassifier(
            classificationDao,
            rules = listOf(
                CategoryRule("donation", listOf("donasi", "sedekah", "infaq"), priority = 1, ruleId = "cat-donation"),
                CategoryRule("bill", listOf("tagihan", "listrik", "internet"), priority = 2, ruleId = "cat-bill"),
            ),
            clock = { now },
        )

        suspend fun importOrders(
            merchantId: String = MERCHANT_A,
            version: String = "v1",
            orders: List<Triple<String, Long, String>>,
        ) {
            val rows = orders.map { (number, amount, direction) ->
                OrderRegistryEntity(
                    merchantId = merchantId,
                    orderNumber = number,
                    customerId = null,
                    amount = amount,
                    currency = "IDR",
                    direction = direction,
                    status = "open",
                    registryVersion = version,
                    importedAtMs = now,
                )
            }
            val result = registry.importSnapshot(merchantId, version, "operator", rows)
            check(result is OrderRegistry.ImportResult.Imported) { "import failed: $result" }
        }

        fun input(
            eventId: String = UUID.randomUUID().toString(),
            orderNumber: String? = "INV-100",
            bankReference: String? = "BANKREF-1",
            amount: Long = 100800L,
            currency: String = "IDR",
            direction: Direction = Direction.IN,
            sourceAccountMasked: String? = "****1234",
            text: String? = null,
        ) = MatchInput(
            eventId = eventId,
            orderNumber = orderNumber,
            bankReference = bankReference,
            amount = amount,
            currency = currency,
            direction = direction,
            sourceAccountMasked = sourceAccountMasked,
            text = text,
        )

        fun candidate(
            eventId: String,
            bankReference: String?,
        ) = MatchResultEntity(
            matchId = UUID.randomUUID().toString(),
            eventId = eventId,
            merchantId = MERCHANT_A,
            orderNumber = "INV-100",
            bankReference = bankReference,
            sourceAccountMasked = "****1234",
            advisoryMatchStatus = AdvisoryMatchStatus.MATCHED,
            confidence = 0.9,
            ruleId = MatchRuleId.MATCH,
            registryVersion = "v1",
            attributionStatus = AttributionStatus.NONE,
            auditJson = "{}",
            evidenceIdentity = bankReference?.let { "bankref:$it|merchant:$MERCHANT_A" },
            reviewReason = null,
            createdAtMs = now,
        )

        suspend fun event(eventId: String = UUID.randomUUID().toString()): EventEntity {            val event = EventEntity(
                eventId = eventId,
                sourceType = "android_notification",
                sourceEventId = "src-$eventId",
                deviceId = "Moto g57",
                eventType = "payment.notification",
                category = "payment",
                sourcePackage = "com.gojek.gopay",
                occurredAtMs = now,
                capturedAtMs = now,
                dataJson = """{"amount":100800,"currency":"IDR"}""",
                metadataJson = """{"parser_version":"payment-v1"}""",
                legacyPayloadJson = null,
                privacyMode = "structured",
                retentionUntilMs = null,
                dedupeKey = "dedupe-$eventId",
                contentSha256 = null,
                exportRef = UUID.randomUUID().toString(),
                sourceAccountKey = null,
            )
            eventDao.insert(event)
            return event
        }

        suspend fun delivery(event: EventEntity): DeliveryEntity {
            val delivery = DeliveryEntity(
                deliveryId = UUID.randomUUID().toString(),
                eventId = event.eventId,
                kind = DeliveryKind.LEGACY_CHAIN,
                state = DeliveryState.PENDING,
                legacyQkey = null,
                routeIdsJson = "[]",
                nextRouteIndex = 0,
                attempts = 0,
                nextRetryAtMs = null,
                leaseOwner = null,
                leaseUntilMs = null,
                createdAtMs = now,
                updatedAtMs = now,
            )
            deliveryDao.insert(delivery)
            return delivery
        }
    }

    // --- F46: order-number match, cross-account uniqueness ---

    @Test
    fun f46a_sameBankTransaction_twoAccounts_oneAttributed_oneDuplicateEvidence() = runBlocking {
        val h = Harness()
        h.importOrders(orders = listOf(Triple("INV-100", 100800L, "in")))
        // First account attributes.
        val first = h.matcher.match(
            h.input(bankReference = "BANKREF-1", sourceAccountMasked = "****1234"),
        )
        assertEquals(AdvisoryMatchStatus.MATCHED, first.advisoryMatchStatus)
        assertEquals(AttributionStatus.ATTRIBUTED, first.attributionStatus)
        // Second account, verified same bank transaction (same
        // confirmed bank_reference + merchant scope).
        val second = h.matcher.match(
            h.input(bankReference = "BANKREF-1", sourceAccountMasked = "****5678"),
        )
        assertEquals(AdvisoryMatchStatus.MATCHED, second.advisoryMatchStatus)
        assertEquals(AttributionStatus.DUPLICATE_EVIDENCE, second.attributionStatus)
        assertEquals(MatchRuleId.DUPLICATE_EVIDENCE, second.ruleId)
        // Exactly one attributed row; the duplicate links to it with its own audit row.
        val rows = h.matchDao.getByOrder(MERCHANT_A, "INV-100")
        assertEquals(1, rows.count { it.attributionStatus == AttributionStatus.ATTRIBUTED })
        val dup = rows.first { it.attributionStatus == AttributionStatus.DUPLICATE_EVIDENCE }
        assertEquals(first.matchId, dup.attributedToMatchId)
        assertTrue(dup.auditJson.contains("****5678"))
    }

    @Test
    fun f46a_unverifiedIdentity_ambiguous_neverDuplicateEvidence() = runBlocking {
        val h = Harness()
        h.importOrders(orders = listOf(Triple("INV-100", 100800L, "in")))
        h.matcher.match(h.input(bankReference = "BANKREF-1"))
        // Different bank reference: distinct or indeterminate evidence (case C).
        val second = h.matcher.match(h.input(bankReference = "BANKREF-2"))
        assertEquals(AdvisoryMatchStatus.AMBIGUOUS, second.advisoryMatchStatus)
        assertEquals(AttributionStatus.NONE, second.attributionStatus)
        assertEquals(MatchRuleId.AMBIGUOUS, second.ruleId)
        // Missing bank reference: cannot verify sameness either.
        val third = h.matcher.match(h.input(bankReference = null))
        assertEquals(AdvisoryMatchStatus.AMBIGUOUS, third.advisoryMatchStatus)
        val rows = h.matchDao.getByOrder(MERCHANT_A, "INV-100")
        assertEquals(1, rows.count { it.attributionStatus == AttributionStatus.ATTRIBUTED })
        assertEquals(0, rows.count { it.attributionStatus == AttributionStatus.DUPLICATE_EVIDENCE })
    }

    @Test
    fun f46b_sameOrderNumber_distinctMerchants_independent() = runBlocking {
        val h = Harness()
        h.importOrders(merchantId = MERCHANT_A, orders = listOf(Triple("INV-100", 100800L, "in")))
        // Merchant B's shape is ORD-*, so its order number differs;
        // the independence check is per (merchant, order): the same
        // numeric tail under a different merchant+shape attributes
        // independently.
        h.importOrders(merchantId = MERCHANT_B, orders = listOf(Triple("ORD-100", 100800L, "in")))
        val a = h.matcher.match(h.input(orderNumber = "INV-100"))
        val b = h.matcher.match(h.input(orderNumber = "ORD-100"))
        assertEquals(AttributionStatus.ATTRIBUTED, a.attributionStatus)
        assertEquals(AttributionStatus.ATTRIBUTED, b.attributionStatus)
        assertEquals(MERCHANT_A, a.merchantId)
        assertEquals(MERCHANT_B, b.merchantId)
    }

    @Test
    fun f46c_duplicateOrderNumber_differentAmounts_manualReview() = runBlocking {
        val h = Harness()
        h.importOrders(orders = listOf(Triple("INV-100", 100800L, "in")))
        val first = h.matcher.match(h.input(amount = 100800L))
        assertEquals(AttributionStatus.ATTRIBUTED, first.attributionStatus)
        // A second event claiming the same order with a different
        // amount: mismatch against the registered order (case E) —
        // operator review, never a second attribution.
        val second = h.matcher.match(h.input(amount = 50000L, bankReference = "BANKREF-9"))
        assertEquals(AdvisoryMatchStatus.MANUAL_REVIEW, second.advisoryMatchStatus)
        assertEquals(MatchRuleId.MISMATCH, second.ruleId)
        val rows = h.matchDao.getByOrder(MERCHANT_A, "INV-100")
        assertEquals(1, rows.count { it.attributionStatus == AttributionStatus.ATTRIBUTED })
    }

    @Test
    fun f46d_mismatch_manualReview_neverAutoAttribution() = runBlocking {
        val h = Harness()
        h.importOrders(orders = listOf(Triple("INV-100", 100800L, "in")))
        // Amount mismatch.
        val amountMismatch = h.matcher.match(h.input(amount = 1L))
        assertEquals(AdvisoryMatchStatus.MANUAL_REVIEW, amountMismatch.advisoryMatchStatus)
        // Currency mismatch.
        val currencyMismatch = h.matcher.match(h.input(currency = "USD"))
        assertEquals(AdvisoryMatchStatus.MANUAL_REVIEW, currencyMismatch.advisoryMatchStatus)
        // Direction mismatch.
        val directionMismatch = h.matcher.match(h.input(direction = Direction.OUT))
        assertEquals(AdvisoryMatchStatus.MANUAL_REVIEW, directionMismatch.advisoryMatchStatus)
        // Unknown direction cannot agree.
        val unknownDirection = h.matcher.match(h.input(direction = Direction.UNKNOWN))
        assertEquals(AdvisoryMatchStatus.MANUAL_REVIEW, unknownDirection.advisoryMatchStatus)
        assertEquals(0, h.matchDao.getByOrder(MERCHANT_A, "INV-100")
            .count { it.attributionStatus == AttributionStatus.ATTRIBUTED })
    }

    @Test
    fun f46e_concurrentAttribution_exactlyOneAttributed() = runBlocking {
        val h = Harness()
        h.importOrders(orders = listOf(Triple("INV-100", 100800L, "in")))
        // Two concurrent claims for the same unattributed order:
        // the atomic transaction chooses one; the loser is ambiguous.
        val first = async(Dispatchers.Default) {
            h.matcher.match(h.input(bankReference = "BANKREF-1"))
        }
        val second = async(Dispatchers.Default) {
            h.matcher.match(h.input(bankReference = "BANKREF-2"))
        }
        val outcomes = listOf(first.await(), second.await())
        assertEquals(1, outcomes.count { it.attributionStatus == AttributionStatus.ATTRIBUTED })
        assertEquals(1, outcomes.count { it.advisoryMatchStatus == AdvisoryMatchStatus.AMBIGUOUS })
        val loser = outcomes.first { it.advisoryMatchStatus == AdvisoryMatchStatus.AMBIGUOUS }
        // Which rule the loser gets depends on interleaving: P3-D
        // when its pre-read ran before the winner's attribute
        // committed (DAO conflict branch), P3-C when the pre-read
        // saw the attribution already in place. Both are correct:
        // ambiguous, never a second attribution. The P3-D branch
        // itself is pinned deterministically below.
        assertTrue(
            "loser rule must be P3-C or P3-D, was ${loser.ruleId}",
            loser.ruleId == MatchRuleId.CONCURRENT_LOSER || loser.ruleId == MatchRuleId.AMBIGUOUS,
        )
        val rows = h.matchDao.getByOrder(MERCHANT_A, "INV-100")
        assertEquals(1, rows.count { it.attributionStatus == AttributionStatus.ATTRIBUTED })
    }

    @Test
    fun f46e_daoConflictBranch_concurrentLoserRule() = runBlocking {
        // Deterministic P3-D: two sequential attribute() calls —
        // the second reaches the DAO conflict branch (its caller
        // pre-read "no attribution", the row appeared between the
        // pre-read and the call).
        val h = Harness()
        val first = h.matchDao.attribute(
            h.candidate(eventId = "evt-1", bankReference = "BANKREF-1"),
            verifiedSameBankTransaction = false,
        )
        assertEquals(AttributionOutcome.ATTRIBUTED, first)
        val second = h.matchDao.attribute(
            h.candidate(eventId = "evt-2", bankReference = "BANKREF-2"),
            verifiedSameBankTransaction = false,
        )
        assertEquals(AttributionOutcome.AMBIGUOUS, second)
        val rows = h.matchDao.getByOrder(MERCHANT_A, "INV-100")
        assertEquals(1, rows.count { it.attributionStatus == AttributionStatus.ATTRIBUTED })
        val loser = rows.first { it.attributionStatus == AttributionStatus.NONE }
        assertEquals(AdvisoryMatchStatus.AMBIGUOUS, loser.advisoryMatchStatus)
        assertEquals(MatchRuleId.CONCURRENT_LOSER, loser.ruleId)
    }

    @Test
    fun f46f_orderAbsentFromRegistry_unmatched() = runBlocking {
        val h = Harness()
        h.importOrders(orders = listOf(Triple("INV-100", 100800L, "in")))
        val outcome = h.matcher.match(h.input(orderNumber = "INV-999"))
        assertEquals(AdvisoryMatchStatus.UNMATCHED, outcome.advisoryMatchStatus)
        assertEquals(AttributionStatus.NONE, outcome.attributionStatus)
        assertEquals(MatchRuleId.UNMATCHED, outcome.ruleId)
        assertEquals(0.0, outcome.confidence, 0.0)
    }

    @Test
    fun f46_noOrderNumber_manualReview() = runBlocking {
        val h = Harness()
        h.importOrders(orders = listOf(Triple("INV-100", 100800L, "in")))
        val outcome = h.matcher.match(h.input(orderNumber = null, text = "Pembayaran Rp100.800"))
        assertEquals(AdvisoryMatchStatus.MANUAL_REVIEW, outcome.advisoryMatchStatus)
        assertEquals(MatchRuleId.NO_ORDER, outcome.ruleId)
    }

    @Test
    fun f46_orderNumberFoundByShape() = runBlocking {
        val h = Harness()
        h.importOrders(orders = listOf(Triple("INV-100", 100800L, "in")))
        // No labelled order number — the merchant shape finds it in the text.
        val outcome = h.matcher.match(
            h.input(orderNumber = null, text = "Transfer INV-100 Rp100.800"),
        )
        assertEquals(AdvisoryMatchStatus.MATCHED, outcome.advisoryMatchStatus)
        assertEquals(AttributionStatus.ATTRIBUTED, outcome.attributionStatus)
        assertEquals("INV-100", outcome.orderNumber)
    }

    // --- F47: classification independence ---

    @Test
    fun f47_matchedOrder_unclassifiedCategory() = runBlocking {
        val h = Harness()
        h.importOrders(orders = listOf(Triple("INV-100", 100800L, "in")))
        val event = h.event()
        val match = h.matcher.match(h.input(eventId = event.eventId))
        assertEquals(AttributionStatus.ATTRIBUTED, match.attributionStatus)
        val classification = h.classifier.classify(
            TransactionClassifier.ClassificationInput(
                eventId = event.eventId,
                purposeText = "Pembayaran tagihan bulanan",
                senderName = null,
                recipientName = null,
            ),
        )
        // "tagihan" fires the bill rule — but with a purpose that
        // matches no rule, the same matched order stays UNCLASSIFIED.
        assertEquals("bill", classification.category)
        val unclassified = h.classifier.classify(
            TransactionClassifier.ClassificationInput(
                eventId = event.eventId,
                purposeText = "Transfer biasa",
                senderName = null,
                recipientName = null,
            ),
        )
        assertEquals(TransactionClassifier.UNCLASSIFIED, unclassified.category)
        // Neither subsystem's output appears in the other's rows.
        val matchRow = h.matchDao.getById(match.matchId)!!
        assertFalse(matchRow.auditJson.contains("UNCLASSIFIED"))
        assertFalse(matchRow.auditJson.contains("bill"))
        val classRows = h.classificationDao.getByEventId(event.eventId)
        assertTrue(classRows.none { it.auditJson.contains("INV-100") })
        assertTrue(classRows.none { it.auditJson.contains("matched") })
    }

    @Test
    fun f47_classifiedDonation_noOrderMatch() = runBlocking {
        val h = Harness()
        val event = h.event()
        val match = h.matcher.match(h.input(eventId = event.eventId, orderNumber = null))
        assertEquals(AdvisoryMatchStatus.MANUAL_REVIEW, match.advisoryMatchStatus)
        val classification = h.classifier.classify(
            TransactionClassifier.ClassificationInput(
                eventId = event.eventId,
                purposeText = "Donasi pembangunan masjid",
                senderName = "Budi",
                recipientName = null,
            ),
        )
        assertEquals("donation", classification.category)
        assertEquals("cat-donation", classification.ruleId)
        assertEquals(0.8, classification.confidence, 0.0)
    }

    // --- F48: no settlement on reference ---

    @Test
    fun f48_advisoryMatchedAlone_noSettlementSignal() = runBlocking {
        val h = Harness()
        h.importOrders(orders = listOf(Triple("INV-100", 100800L, "in")))
        val event = h.event()
        val delivery = h.delivery(event)
        val before = h.deliveryDao.getById(delivery.deliveryId)!!
        val outcome = h.matcher.match(h.input(eventId = event.eventId))
        assertEquals(AdvisoryMatchStatus.MATCHED, outcome.advisoryMatchStatus)
        assertEquals(AttributionStatus.ATTRIBUTED, outcome.attributionStatus)
        // The delivery row is untouched: no state change, no
        // last_match_status write, no ack. Advisory `matched` never
        // becomes a legacy `match.status`.
        val after = h.deliveryDao.getById(delivery.deliveryId)!!
        assertEquals(before, after)
        assertNull(after.lastMatchStatus)
        assertNull(after.ackedAtMs)
        assertEquals(DeliveryState.PENDING, after.state)
        // The advisory status lives only in match_results.
        val row = h.matchDao.getById(outcome.matchId)!!
        assertEquals(AdvisoryMatchStatus.MATCHED, row.advisoryMatchStatus)
        // And no advisory field leaks into a wire payload: the
        // legacy payload for this event carries no advisory key.
        val payload = JSONObject(event.legacyPayloadJson ?: "{}")
        assertFalse(payload.has("advisory_match"))
        assertFalse(payload.has("advisory_match_status"))
    }

    // --- F51: the identity decision table ---

    @Test
    fun f51_caseA_sameEventMatchedTwice_neverSecondAttribution() = runBlocking {
        val h = Harness()
        h.importOrders(orders = listOf(Triple("INV-100", 100800L, "in")))
        val eventId = UUID.randomUUID().toString()
        val first = h.matcher.match(h.input(eventId = eventId, bankReference = "BANKREF-1"))
        assertEquals(AttributionStatus.ATTRIBUTED, first.attributionStatus)
        // Matching the same event again (same bank identity):
        // duplicate evidence, never a second attribution. (True
        // case-A dedupe — no second event at all — is the capture
        // layer's job; see CaptureCoordinatorTest.)
        val second = h.matcher.match(h.input(eventId = eventId, bankReference = "BANKREF-1"))
        assertEquals(AttributionStatus.DUPLICATE_EVIDENCE, second.attributionStatus)
        val rows = h.matchDao.getByOrder(MERCHANT_A, "INV-100")
        assertEquals(1, rows.count { it.attributionStatus == AttributionStatus.ATTRIBUTED })
    }

    @Test
    fun f51_auditImmutable_fullInputSetRecorded() = runBlocking {
        val h = Harness()
        h.importOrders(orders = listOf(Triple("INV-100", 100800L, "in")))
        val outcome = h.matcher.match(h.input())
        val row = h.matchDao.getById(outcome.matchId)!!
        val audit = JSONObject(row.auditJson)
        assertEquals("INV-100", audit.getString("order_number"))
        assertEquals("BANKREF-1", audit.getString("bank_reference"))
        assertEquals(100800, audit.getLong("amount"))
        assertEquals("IDR", audit.getString("currency"))
        assertEquals("in", audit.getString("direction"))
        assertEquals("****1234", audit.getString("source_account_masked"))
        assertEquals(MERCHANT_A, audit.getString("merchant_id"))
        assertEquals("v1", audit.getString("registry_version"))
        // A later decision for the same order never overwrites the first row.
        h.matcher.match(h.input(bankReference = "BANKREF-2"))
        val firstAgain = h.matchDao.getById(outcome.matchId)!!
        assertEquals(row, firstAgain)
        assertNotEquals(row.matchId, h.matchDao.getByOrder(MERCHANT_A, "INV-100")
            .first { it.advisoryMatchStatus == AdvisoryMatchStatus.AMBIGUOUS }.matchId)
    }

    @Test
    fun f51_registryUnusable_heldForReview() = runBlocking {
        val h = Harness()
        // No import at all: no usable snapshot ⇒ manual review, never matched.
        val outcome = h.matcher.match(h.input())
        assertEquals(AdvisoryMatchStatus.MANUAL_REVIEW, outcome.advisoryMatchStatus)
        assertEquals(MatchRuleId.REGISTRY_UNUSABLE, outcome.ruleId)
        assertEquals(0, h.matchDao.getByOrder(MERCHANT_A, "INV-100")
            .count { it.attributionStatus == AttributionStatus.ATTRIBUTED })
    }
}
