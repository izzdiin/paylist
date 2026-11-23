package com.netter.paylist.privacy

import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.parser.Direction
import com.netter.paylist.privacy.FallbackGate.EndpointKind
import com.netter.paylist.privacy.FallbackGate.FallbackInput
import com.netter.paylist.privacy.FallbackGate.QueryResult
import com.netter.paylist.privacy.FallbackGate.ReconciliationEvidence
import com.netter.paylist.privacy.FallbackGate.WriterCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage D D2: the §7.3B guarded-fallback criteria (design §10.2,
 * F43 B12–B19 + B21).
 *
 * Pure/deterministic: no Android, no I/O. B17/B18 (notification
 * cases) are owned by `ReviewNotifierTest` (D5); B14's recorder
 * direction-heuristic fixture lives in `LegacyPayloadMapperTest`
 * (the constructed text must classify to the intended direction);
 * this file owns the criterion table + the B21 adversarial
 * negatives.
 */
class FallbackGateTest {

    private fun allPassingInput() = FallbackInput(
        amountReliable = true,
        direction = Direction.OUT,
        identityReliable = true,
        endpointKind = EndpointKind.NOTIFY,
        recorderGatePassed = false,
        reconciliation = ReconciliationEvidence(noPriorPost = true),
        consentValid = true,
    )

    private fun authoritativeFoundFalse() = ReconciliationEvidence(
        noPriorPost = false,
        queryResult = QueryResult.FOUND_FALSE,
        writerCoverage = WriterCoverage.COVERED,
        unknownHopExists = false,
        bindingMatches = true,
        durableRecord = true,
    )

    @Test
    fun allCriteriaMet_authorised() {
        val decision = FallbackGate.evaluate(allPassingInput())
        assertTrue(decision.authorized)
        assertTrue(decision.failedCriteria.isEmpty())
        assertEquals(null, decision.holdState)
    }

    // --- B12: one case per criterion ---

    @Test
    fun b12_criterion1_amountUnreliable_fails_needsReview() {
        // Amount-ambiguous, no pick yet ⇒ NEEDS_REVIEW (amount-first
        // precedence, §7.3B/B13); the operator pick re-runs the
        // evaluation with the chosen amount.
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(amountReliable = false),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(1), decision.failedCriteria)
        assertEquals(DeliveryState.NEEDS_REVIEW, decision.holdState)
    }

    @Test
    fun b12_criterion2_directionUnknown_fails_rawUnavailable() {
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(direction = Direction.UNKNOWN),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(2), decision.failedCriteria)
        assertEquals(DeliveryState.RAW_UNAVAILABLE, decision.holdState)
    }

    @Test
    fun b12_criterion2_directionNull_fails() {
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(direction = null),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(2), decision.failedCriteria)
    }

    @Test
    fun b12_criterion3_identityInsufficient_fails() {
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(identityReliable = false),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(3), decision.failedCriteria)
        assertEquals(DeliveryState.RAW_UNAVAILABLE, decision.holdState)
    }

    @Test
    fun b12_criterion4_recorderWithoutB14_fails() {
        // Before B14 passes against the actual recorder semantics,
        // automatic A′ fallback on a recorder route is NOT
        // permitted — the delivery is held for review.
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(
                endpointKind = EndpointKind.RECORDER,
                recorderGatePassed = false,
            ),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(4), decision.failedCriteria)
        assertEquals(DeliveryState.RAW_UNAVAILABLE, decision.holdState)
    }

    @Test
    fun b14_recorderWithB14Passing_criterion4Met() {
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(
                endpointKind = EndpointKind.RECORDER,
                recorderGatePassed = true,
            ),
        )
        assertTrue(decision.authorized)
    }

    @Test
    fun b12_criterion5_priorUnknownInconclusive_fails_staysHeld() {
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(
                reconciliation = ReconciliationEvidence(
                    noPriorPost = false,
                    queryResult = QueryResult.INCONCLUSIVE,
                    writerCoverage = WriterCoverage.COVERED,
                    unknownHopExists = false,
                    bindingMatches = true,
                    durableRecord = true,
                ),
            ),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(5), decision.failedCriteria)
        assertEquals(DeliveryState.RAW_UNAVAILABLE, decision.holdState)
    }

    @Test
    fun b12_criterion5_authoritativeFoundFalse_authorises() {
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(reconciliation = authoritativeFoundFalse()),
        )
        assertTrue(decision.authorized)
    }

    @Test
    fun b12_criterion6_consentRevoked_fails_blocked() {
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(consentValid = false),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(6), decision.failedCriteria)
        assertEquals(DeliveryState.BLOCKED, decision.holdState)
    }

    // --- B13: ambiguous amount + raw lost ⇒ NEEDS_REVIEW takes precedence ---

    @Test
    fun b13_amountAndRawLost_needsReviewPrecedence() {
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(
                amountReliable = false,
                direction = Direction.UNKNOWN,
            ),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(1, 2), decision.failedCriteria)
        assertEquals(DeliveryState.NEEDS_REVIEW, decision.holdState)
    }

    // --- B15: dedupe — the fallback re-POSTs under the original dedupe_key ---
    // (Proven at the transport level: both endpoints dedupe by it,
    // verified §5.4; the gate never mints a new key. The gate's part
    // is that it takes no key input at all — asserted by construction.)

    // --- B16: consent change at fallback time ⇒ BLOCKED, no auto-dispatch ---

    @Test
    fun b16_consentChangeAtFallback_blocked() {
        // Same as criterion 6: revoke between raw loss and claim.
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(consentValid = false),
        )
        assertFalse(decision.authorized)
        assertEquals(DeliveryState.BLOCKED, decision.holdState)
    }

    // --- B21: authoritative-negative adversarial cases (Resolution 4) ---

    @Test
    fun b21a_foundFalseWithUnknownHop_fails() {
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(
                reconciliation = authoritativeFoundFalse().copy(unknownHopExists = true),
            ),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(5), decision.failedCriteria)
    }

    @Test
    fun b21b_foundFalseWithAdvisoryCoverage_fails() {
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(
                reconciliation = authoritativeFoundFalse()
                    .copy(writerCoverage = WriterCoverage.ADVISORY),
            ),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(5), decision.failedCriteria)
    }

    @Test
    fun b21c_foundFalseWithBindingMismatch_fails() {
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(
                reconciliation = authoritativeFoundFalse().copy(bindingMatches = false),
            ),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(5), decision.failedCriteria)
    }

    @Test
    fun b21d_foundFalseWithoutDurableRecord_fails() {
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(
                reconciliation = authoritativeFoundFalse().copy(durableRecord = false),
            ),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(5), decision.failedCriteria)
    }

    @Test
    fun b21_bareFoundFalse_neverAuthorises() {
        // A bare found:false with none of the four conditions.
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(
                reconciliation = ReconciliationEvidence(
                    noPriorPost = false,
                    queryResult = QueryResult.FOUND_FALSE,
                ),
            ),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(5), decision.failedCriteria)
    }

    @Test
    fun b21_foundTrue_neverAuthorisesFallback() {
        // found:true resolves the delivery per §9 — the fallback
        // gate never authorises on it (defensive: the caller
        // resolves via §9 instead).
        val decision = FallbackGate.evaluate(
            allPassingInput().copy(
                reconciliation = ReconciliationEvidence(
                    noPriorPost = false,
                    queryResult = QueryResult.FOUND_TRUE,
                    writerCoverage = WriterCoverage.COVERED,
                    unknownHopExists = false,
                    bindingMatches = true,
                    durableRecord = true,
                ),
            ),
        )
        assertFalse(decision.authorized)
        assertEquals(listOf(5), decision.failedCriteria)
    }
}
