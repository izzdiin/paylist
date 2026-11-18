package com.netter.paylist.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the §5 delivery state machine encoded in [DeliveryStateMachine].
 * Guards that need runtime context (lease ownership, attempt markers,
 * event type, reconciliation evidence) are the caller's job; this test
 * pins the transition table itself.
 */
class DeliveryStateTest {

    // --- The §5 happy path ---

    @Test
    fun pending_claimsToInFlight() {
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.PENDING, DeliveryState.IN_FLIGHT))
    }

    @Test
    fun inFlight_acksOnValid2xx() {
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.IN_FLIGHT, DeliveryState.ACKED))
    }

    @Test
    fun inFlight_retriesOnTransientFailure() {
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.IN_FLIGHT, DeliveryState.RETRY_WAIT))
    }

    @Test
    fun inFlight_blocksOnNonretryable() {
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.IN_FLIGHT, DeliveryState.BLOCKED))
    }

    @Test
    fun retryWait_reclaimsToPending() {
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.RETRY_WAIT, DeliveryState.PENDING))
    }

    @Test
    fun retryWait_expiresHeartbeatTestOnly() {
        // The heartbeat/test-only guard is the caller's (§4.2, C6);
        // the table allows the transition.
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.RETRY_WAIT, DeliveryState.EXPIRED))
    }

    @Test
    fun blocked_returnsToPendingOnOperatorAction() {
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.BLOCKED, DeliveryState.PENDING))
    }

    // --- Held states (§7.3B, §7.6, §8.5/§8.6) ---

    @Test
    fun needsReview_pickGoesToLegacySweepHold() {
        assertTrue(
            DeliveryStateMachine.canTransition(
                DeliveryState.NEEDS_REVIEW,
                DeliveryState.HELD_AWAITING_LEGACY_SWEEP
            )
        )
    }

    @Test
    fun needsReview_discard() {
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.NEEDS_REVIEW, DeliveryState.DISCARDED))
    }

    @Test
    fun legacySweepHold_releasesToPending() {
        assertTrue(
            DeliveryStateMachine.canTransition(
                DeliveryState.HELD_AWAITING_LEGACY_SWEEP,
                DeliveryState.PENDING
            )
        )
    }

    @Test
    fun reconciliationHold_resolvesToPendingOrAcked() {
        assertTrue(
            DeliveryStateMachine.canTransition(
                DeliveryState.HELD_AWAITING_RECONCILIATION,
                DeliveryState.PENDING
            )
        )
        assertTrue(
            DeliveryStateMachine.canTransition(
                DeliveryState.HELD_AWAITING_RECONCILIATION,
                DeliveryState.ACKED
            )
        )
    }

    @Test
    fun rawUnavailable_operatorAuthorisesOrDiscards() {
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.RAW_UNAVAILABLE, DeliveryState.PENDING))
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.RAW_UNAVAILABLE, DeliveryState.DISCARDED))
    }

    @Test
    fun pending_holdsOnRawLossOrUnknownHop() {
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.PENDING, DeliveryState.RAW_UNAVAILABLE))
        assertTrue(
            DeliveryStateMachine.canTransition(
                DeliveryState.PENDING,
                DeliveryState.HELD_AWAITING_RECONCILIATION
            )
        )
    }

    // --- Terminal states: nothing leaves them ---

    @Test
    fun terminalStates_haveNoOutgoingTransition() {
        for (terminal in listOf(DeliveryState.ACKED, DeliveryState.EXPIRED, DeliveryState.DISCARDED)) {
            assertTrue(DeliveryStateMachine.isTerminal(terminal))
            for (to in DeliveryState.values()) {
                assertFalse(DeliveryStateMachine.canTransition(terminal, to))
            }
        }
    }

    // --- Forbidden shortcuts ---

    @Test
    fun noDirectPendingToAcked() {
        assertFalse(DeliveryStateMachine.canTransition(DeliveryState.PENDING, DeliveryState.ACKED))
    }

    @Test
    fun noDirectPendingToExpired() {
        // EXPIRED is reached from RETRY_WAIT only (§5).
        assertFalse(DeliveryStateMachine.canTransition(DeliveryState.PENDING, DeliveryState.EXPIRED))
    }

    @Test
    fun needsReview_neverGoesDirectlyToPending() {
        // A pick must pass the legacy sweep hold first (§7.6).
        assertFalse(DeliveryStateMachine.canTransition(DeliveryState.NEEDS_REVIEW, DeliveryState.PENDING))
    }

    @Test
    fun noSelfTransition() {
        for (state in DeliveryState.values()) {
            assertFalse(DeliveryStateMachine.canTransition(state, state))
        }
    }
}
