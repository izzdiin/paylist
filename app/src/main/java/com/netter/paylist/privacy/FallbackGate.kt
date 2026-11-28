package com.netter.paylist.privacy

import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.parser.Direction

/**
 * The guarded best-effort fallback gate (design §7.3B, owner
 * decision 2026-11-15; file plan §D).
 *
 * When a B delivery's raw snapshot is lost — process restart,
 * in-memory map eviction, TTL expiry, or cross-process retry —
 * an automatic A′ structured fallback is authorised ONLY if ALL
 * six criteria hold, evaluated at claim time over the structured
 * fields + reconciliation state. Each criterion's outcome is
 * recorded on the attempt row by the caller.
 *
 * 1. Amount reliable — reliably parsed and validated; an
 *    amount-ambiguous event fails until the operator picks.
 * 2. Direction reliable — reliably determined; never inferred
 *    from ambiguous text. No direction cue ⇒ fails.
 * 3. Identity reliable — the original stable `dedupe_key` plus
 *    whatever verified identifiers are actually present; nothing
 *    fabricated. A missing/unverified identifier the endpoint
 *    requires ⇒ fails.
 * 4. Endpoint accepts the fallback — for `notify.php` the
 *    structured fields under the same `dedupe_key`; for a
 *    recorder route (`catat.php`) the §7.2 constructed text. A
 *    recorder route additionally requires F43 B14 to pass against
 *    the actual recorder semantics — before B14 passes, automatic
 *    A′ fallback on a recorder route is NOT permitted. The B14
 *    flag defaults FALSE in production wiring (fail-closed); it
 *    is set true only when B14 is confirmed against live
 *    `catat.php` semantics.
 * 5. Prior outcome reconciled — the full Resolution 4 test (§9.2):
 *    (a) writer coverage COVERED, (b) no UNKNOWN hop for the
 *    `dedupe_key`, (c) system/endpoint binding matches, (d) the
 *    sweep records the decision durably. A bare `found:false`
 *    NEVER authorises a fallback on its own. No prior POST at all
 *    ⇒ nothing to reconcile ⇒ passes.
 * 6. Consent and privacy re-checked — the opt-in is still valid,
 *    unexpired, unrevoked; the fallback sends structured fields
 *    only, never raw.
 *
 * Hold rule: ANY criterion ambiguous, unverified, incompatible,
 * or reconciliation inconclusive ⇒ the delivery holds, never
 * silently dispatched. Precedence (§7.3B/B13): amount-ambiguous
 * + raw-lost ⇒ NEEDS_REVIEW first (the pick re-runs this
 * evaluation with the chosen amount); consent failure ⇒ BLOCKED;
 * any other failure ⇒ RAW_UNAVAILABLE.
 *
 * Pure/deterministic: no Android, no I/O. The caller supplies
 * the reconciliation evidence (from the §9 status query) and
 * records the per-criterion outcome.
 */
object FallbackGate {

    /** Target endpoint kind (criterion 4). */
    enum class EndpointKind {
        /** `notify.php` — the payment matcher reads `amount` only. */
        NOTIFY,

        /** `catat.php` — recorder; needs the §7.2 constructed text + B14. */
        RECORDER,
    }

    /** §9 status-query answer for the `dedupe_key`. */
    enum class QueryResult {
        /** The server has a row for the key (§9 resolves the delivery). */
        FOUND_TRUE,

        /** The server has no row — authoritative only with all four conditions. */
        FOUND_FALSE,

        /** `in_flight:true`, 401/5xx/timeout, or no P12 coverage for the route. */
        INCONCLUSIVE,
    }

    /** Per-system writer coverage state (§9.2 Resolution 4 (a)). */
    enum class WriterCoverage {
        /** Migration applied, named locks on ALL writers, status endpoints deployed. */
        COVERED,

        /** Anything less (e.g. an undeployed lock) — advisory only. */
        ADVISORY,
    }

    /** The reconciliation evidence for criterion 5 (Resolution 4). */
    data class ReconciliationEvidence(
        /** True when no POST was ever attempted for this `dedupe_key`. */
        val noPriorPost: Boolean = false,
        val queryResult: QueryResult? = null,
        val writerCoverage: WriterCoverage? = null,
        /** True when an UNKNOWN hop exists for the key anywhere in the sweep. */
        val unknownHopExists: Boolean = false,
        /** True when `system_id` + `endpoint_kind` + destination binding match (§9.5). */
        val bindingMatches: Boolean = false,
        /** True when the sweep recorded the coverage state + decision durably. */
        val durableRecord: Boolean = false,
    )

    /** Inputs to the six-criterion evaluation. */
    data class FallbackInput(
        /** Criterion 1: amount reliably parsed and validated. */
        val amountReliable: Boolean,
        /** Criterion 2: direction reliably determined (null = unknown). */
        val direction: Direction?,
        /** Criterion 3: identity/dedupe reliable. */
        val identityReliable: Boolean,
        /** Criterion 4: target endpoint kind. */
        val endpointKind: EndpointKind,
        /**
         * Criterion 4 (recorder only): F43 B14 passed against the
         * actual recorder semantics. Defaults FALSE in production
         * wiring — automatic A′ fallback on a recorder route is
         * NOT permitted until B14 passes.
         */
        val recorderGatePassed: Boolean = false,
        /** Criterion 5: reconciliation evidence. */
        val reconciliation: ReconciliationEvidence,
        /** Criterion 6: opt-in still valid, unexpired, unrevoked. */
        val consentValid: Boolean,
    )

    /** The gate's verdict. */
    data class FallbackDecision(
        /** True only when ALL six criteria hold. */
        val authorized: Boolean,
        /** The failed criterion numbers (1–6), in order. Empty when authorised. */
        val failedCriteria: List<Int>,
        /**
         * The hold state when not authorised: NEEDS_REVIEW for an
         * amount failure (precedence), BLOCKED for a consent
         * failure, RAW_UNAVAILABLE otherwise. Null when authorised.
         */
        val holdState: DeliveryState?,
    )

    fun evaluate(input: FallbackInput): FallbackDecision {
        val failed = mutableListOf<Int>()

        // (1) Amount reliable.
        if (!input.amountReliable) failed += 1

        // (2) Direction reliable — never inferred; null/UNKNOWN fails.
        if (input.direction == null || input.direction == Direction.UNKNOWN) failed += 2

        // (3) Identity reliable.
        if (!input.identityReliable) failed += 3

        // (4) Endpoint accepts the fallback. A recorder route needs
        // the §7.2 constructed text AND B14 to have passed against
        // the actual recorder semantics.
        if (input.endpointKind == EndpointKind.RECORDER && !input.recorderGatePassed) {
            failed += 4
        }

        // (5) Prior outcome reconciled — the full Resolution 4 test.
        if (!criterion5Passes(input.reconciliation)) failed += 5

        // (6) Consent and privacy re-checked.
        if (!input.consentValid) failed += 6

        if (failed.isEmpty()) {
            return FallbackDecision(authorized = true, failedCriteria = emptyList(), holdState = null)
        }
        // Precedence: amount first (NEEDS_REVIEW), then consent
        // (BLOCKED), then everything else (RAW_UNAVAILABLE).
        val holdState = when {
            1 in failed -> DeliveryState.NEEDS_REVIEW
            6 in failed -> DeliveryState.BLOCKED
            else -> DeliveryState.RAW_UNAVAILABLE
        }
        return FallbackDecision(authorized = false, failedCriteria = failed, holdState = holdState)
    }

    /**
     * Criterion 5: no prior POST ⇒ passes (nothing to reconcile).
     * Otherwise an authoritative `found:false` requires ALL FOUR
     * Resolution 4 conditions; a bare `found:false` never
     * authorises a fallback. `found:true` resolves the delivery
     * per §9 — the caller does not evaluate the fallback for it,
     * so it fails here (defensive: never authorise on it).
     */
    private fun criterion5Passes(evidence: ReconciliationEvidence): Boolean {
        if (evidence.noPriorPost) return true
        if (evidence.queryResult != QueryResult.FOUND_FALSE) return false
        if (evidence.writerCoverage != WriterCoverage.COVERED) return false
        if (evidence.unknownHopExists) return false
        if (!evidence.bindingMatches) return false
        if (!evidence.durableRecord) return false
        return true
    }
}
