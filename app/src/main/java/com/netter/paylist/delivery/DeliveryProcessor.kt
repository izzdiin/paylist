package com.netter.paylist.delivery

import com.netter.paylist.Route
import com.netter.paylist.collector.ConsentStore
import com.netter.paylist.collector.RawSnapshotStore
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.core.model.RouteStamp
import com.netter.paylist.data.TransactionRunner
import com.netter.paylist.data.dao.AttemptDao
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.data.dao.EventDao
import com.netter.paylist.data.entity.AttemptState
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.data.entity.DeliveryAttemptEntity
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.data.entity.HopOutcome
import com.netter.paylist.data.RouteStampMap
import com.netter.paylist.parser.Direction
import com.netter.paylist.parser.DirectionHeuristic
import com.netter.paylist.privacy.FallbackGate
import com.netter.paylist.security.SecretProvider
import com.netter.paylist.security.SecretResult
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * The SyncWorker's delivery logic, free of Android/WorkManager
 * plumbing (design §5, §5.1, §5.2, §5.5, §6, §7.5, §8.5).
 * [SyncWorker] is a thin shell around this class; everything
 * testable lives here and is covered by `SyncWorkerTest` with fake
 * DAOs, a fake transport and a fake clock.
 *
 * One pass ([processDueWork]): reclaim expired leases, claim a
 * bounded batch (mode gate + consent join + 5-minute lease, one
 * transaction), process each delivery through its route chain, and
 * report whether due items remain (the worker self-reschedules —
 * the KEEP-trap fix, §6).
 *
 * Per hop, in order (§5.5): route/secret gates (NO_POST skip) →
 * stamp re-check (BLOCKED_CONSENT/BLOCKED_CONFIG halt) → unresolved
 * marker check (never a second socket for a hop, §8.5) → admission
 * marker (`ATTEMPT_STARTED`) → claim for socket open
 * (`SOCKET_OPENING`) → transport POST (`connect()` → `SOCKET_OPEN`
 * checkpoint → first write) → completion checkpoint (attempt row +
 * hop checkpoint + delivery transition, one transaction).
 *
 * Unknown-hop semantics (§5.5 rows 4/8–10, T08): a first attempt
 * whose outcome is unknown — 5xx, timeout, malformed 2xx, any
 * `RETRY` classification — records hop outcome
 * `UNKNOWN_PENDING_RECONCILIATION` and RETRY_WAITs with backoff; the
 * next attempt for that hop is a RE-POST (rows 8–10: only
 * matched+apply=ok settles it, anything else stays unknown and the
 * delivery holds for reconciliation). Whether an attempt is a
 * re-POST is read from the delivery's hop history (a prior UNKNOWN
 * or MATCHED_UNCONFIRMED outcome for the hop), never from an
 * unresolved marker: an unresolved marker means the previous
 * attempt is still live or orphaned, and the worker never posts
 * past it — startup recovery resolves the marker first.
 *
 * Lease rule (§5.1): every delivery transition is conditional on
 * the lease still being held and unexpired. If the lease is lost
 * mid-flight, the attempt row is still completed (it is the audit
 * trail, and an unresolved marker would block lease reclaim
 * forever), but the delivery state is left untouched — the reclaim
 * returns it to PENDING and the hop is re-POSTed under the same
 * `dedupe_key` (server-side idempotency, no double settlement).
 */
class DeliveryProcessor(
    private val deliveryDao: DeliveryDao,
    private val eventDao: EventDao,
    private val attemptDao: AttemptDao,
    private val transactions: TransactionRunner,
    private val routes: RouteSource,
    private val secrets: SecretProvider,
    private val transport: TransportClient,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: () -> Double = { Math.random() },
    /** Stage D §7.3A: the raw-snapshot store (null = B disabled, A′ only). */
    private val rawSnapshotStore: RawSnapshotStore? = null,
    /** Stage D §7.5/§7.3: per-route consent records (null = B unverifiable). */
    private val consentStore: ConsentStore? = null,
    /**
     * F43 B14: automatic A′ fallback on a recorder route requires
     * this to be true. Defaults FALSE in production wiring
     * (fail-closed) — set true only when B14 is confirmed against
     * live `catat.php` semantics.
     */
    private val recorderGatePassed: Boolean = false,
    /** Called when an authorised A′ fallback is dispatched (per-route counter). */
    private val onFallbackDispatched: (routeId: String) -> Unit = {},
) {
    companion object {
        /** Due deliveries per worker invocation (§5.4). */
        const val BATCH_SIZE = 20

        /** Claim lease: 5 minutes, no renewal (§5.1). */
        const val LEASE_MS = 5 * 60 * 1000L

        /** Heartbeat/test TTL: 30 minutes / 2 dedupe buckets (§4.2 C6). */
        const val HEARTBEAT_TTL_MS = 30 * 60 * 1000L

        /** Heartbeat/test retention after terminal state (§4.2 C11). */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * Retry-exhaustion policy (Stage C; the design pins the rule
         * but not the number): after this many failed claims a
         * retryable delivery becomes BLOCKED for operator attention
         * instead of retrying silently forever. 10 claims ≈ 8 hours
         * of backoff (1m → 2h cap).
         */
        const val MAX_ATTEMPTS_BEFORE_BLOCKED = 10

        /** Attempt rows kept per delivery (§4.2 C11). */
        const val MAX_ATTEMPTS_PER_DELIVERY = 50

        /** Network errors that prove the request never left (NO_POST). */
        private val NO_POST_ERROR_CODES = setOf(
            "dns", "connect_timeout", "connect_refused", "ssl", "io_connect"
        )
    }

    /**
     * One worker pass. Returns true when due items remain after this
     * pass (the caller self-reschedules).
     */
    suspend fun processDueWork(isStopped: () -> Boolean = { false }): Boolean {
        // Nothing is claimable outside WORKER_ONLY (claimBatch gates
        // on the mode); report no remaining work so the worker does
        // not self-reschedule in a loop before the cutover.
        val mode = deliveryDao.readCollectorValue(CollectorStateKeys.CUTOVER_STATE)
            ?.let { DeliveryDao.parseMode(it) }
        if (mode != "WORKER_ONLY") return false
        val now = clock()
        deliveryDao.reclaimExpiredLeases(now)
        val claimed = deliveryDao.claimBatch(BATCH_SIZE, now, LEASE_MS)
        for (delivery in claimed) {
            if (isStopped()) break
            try {
                processDelivery(delivery)
            } catch (e: CancellationException) {
                // Structured concurrency: cancellation is not a
                // delivery failure. The attempt row stays unresolved
                // (honest: the outcome is unknown) and orphan
                // recovery classifies it; the lease reclaim returns
                // the delivery to PENDING.
                throw e
            } catch (e: Exception) {
                // Fairness (§5.4): one failing item must not block others.
                try {
                    val leaseOwner = delivery.leaseOwner ?: continue
                    val at = clock()
                    deliveryDao.retryWaitIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, at,
                        at + backoffDelayMs(delivery.attempts),
                        "worker_error",
                        (e.message ?: e.javaClass.simpleName).take(120),
                        delivery.nextRouteIndex, delivery.hopOutcomesJson,
                    )
                } catch (_: Exception) {
                    // Lease lost; reclaim handles it.
                }
            } finally {
                try {
                    attemptDao.pruneToLatest(delivery.deliveryId, MAX_ATTEMPTS_PER_DELIVERY)
                } catch (_: Exception) {
                }
            }
        }
        return deliveryDao.countDue(clock()) > 0
    }

    /** Retention cleanup (periodic reconciliation only, §4.2 C11). */
    suspend fun runRetentionCleanup(): Int =
        eventDao.deleteExpiredHeartbeatTest(clock() - RETENTION_MS)

    // --- Per-delivery processing ---

    private suspend fun processDelivery(delivery: DeliveryEntity) {
        val now = clock()
        val leaseOwner = delivery.leaseOwner ?: return
        val event = eventDao.getById(delivery.eventId)
        if (event == null) {
            deliveryDao.blockIfLeaseHeld(
                delivery.deliveryId, leaseOwner, now,
                "event_missing", "event pengiriman tidak ditemukan"
            )
            return
        }
        // Heartbeat/test TTL (§4.2 C6): a stale heartbeat/test
        // expires instead of posting. The machine has no IN_FLIGHT
        // -> EXPIRED edge, so the worker routes through RETRY_WAIT
        // (both edges exist); the error code records the real reason.
        if ((event.category == "heartbeat" || event.category == "test") &&
            now - event.occurredAtMs > HEARTBEAT_TTL_MS
        ) {
            val moved = deliveryDao.retryWaitIfLeaseHeld(
                delivery.deliveryId, leaseOwner, now, now,
                "heartbeat_ttl_expired",
                "heartbeat/test kedaluwarsa (lebih dari 30 menit)",
                delivery.nextRouteIndex, delivery.hopOutcomesJson,
            )
            if (moved == 1) deliveryDao.markExpired(delivery.deliveryId, now)
            return
        }
        val routeIds = parseRouteIds(delivery.routeIdsJson)
        if (routeIds == null) {
            deliveryDao.blockIfLeaseHeld(
                delivery.deliveryId, leaseOwner, now,
                "corrupt_route_ids", "daftar rute pengiriman rusak"
            )
            return
        }
        if (routeIds.isEmpty()) {
            deliveryDao.blockIfLeaseHeld(
                delivery.deliveryId, leaseOwner, now,
                "no_routes", "pengiriman tanpa rute"
            )
            return
        }
        var nextIndex = delivery.nextRouteIndex
        val outcomes = HopOutcome.fromJsonArray(delivery.hopOutcomesJson).toMutableList()
        val stamps = RouteStampMap.fromJsonString(delivery.routeStampsJson)
        val bootId = readBootId()
        while (nextIndex < routeIds.size) {
            val routeId = routeIds[nextIndex]
            val route = routes.routeById(routeId)
            // §5.5 row 6: no route / disabled / empty URL -> NO_POST, advance.
            if (route == null || !route.enabled || route.url.isEmpty()) {
                outcomes += HopOutcome(routeId, "NO_POST", null, null, null, clock())
                nextIndex++
                if (!checkpoint(delivery, leaseOwner, nextIndex, outcomes)) return
                continue
            }
            // §5.5 row 7: stamp re-check before every hop.
            val consentsJson = deliveryDao.readCollectorValue(CollectorStateKeys.ROUTE_CONSENTS)
            when (val stampCheck = checkStamp(route, stamps[routeId], consentsJson)) {
                is StampCheck.Blocked -> {
                    outcomes += HopOutcome(routeId, stampCheck.outcome, null, null, null, clock())
                    val at = clock()
                    transactions.run {
                        deliveryDao.checkpointHopIfLeaseHeld(
                            delivery.deliveryId, leaseOwner, at,
                            nextIndex, HopOutcome.toJsonArray(outcomes)
                        )
                        deliveryDao.blockIfLeaseHeld(
                            delivery.deliveryId, leaseOwner, at,
                            stampCheck.errorCode, stampCheck.summary
                        )
                    }
                    return
                }
                is StampCheck.Ok -> { /* proceed */ }
            }
            // §5.5 row 6 (secret): no secret -> NO_POST, advance. An
            // UNREADABLE secret is different: the route HAS a secret
            // we cannot read — BLOCKED (§7.8), never silently skipped.
            val secret = when (val s = secrets.secretFor(routeId)) {
                is SecretResult.Present -> s.value
                is SecretResult.Missing -> {
                    outcomes += HopOutcome(routeId, "NO_POST", null, null, null, clock())
                    nextIndex++
                    if (!checkpoint(delivery, leaseOwner, nextIndex, outcomes)) return
                    continue
                }
                is SecretResult.Unavailable -> {
                    outcomes += HopOutcome(routeId, "BLOCKED_SECRET", null, null, null, clock())
                    val at = clock()
                    transactions.run {
                        deliveryDao.checkpointHopIfLeaseHeld(
                            delivery.deliveryId, leaseOwner, at,
                            nextIndex, HopOutcome.toJsonArray(outcomes)
                        )
                        deliveryDao.blockIfLeaseHeld(
                            delivery.deliveryId, leaseOwner, at,
                            "secret_unavailable",
                            "secret rute ${route.name} tidak bisa dibaca (${s.reason}); masukkan ulang secret"
                        )
                    }
                    return
                }
            }
            // The claim body is resolved below, after the unresolved
            // marker check: a B snapshot must only be taken when
            // this hop will actually be attempted (§7.3A(d)).
            // An unresolved marker for this hop means a previous
            // attempt is still live or orphaned: never open a second
            // socket for the hop (§8.5). Stop this delivery; startup
            // recovery resolves the marker, and the lease reclaim
            // returns the delivery to PENDING afterwards.
            if (attemptDao.getUnresolvedForHop(delivery.deliveryId, nextIndex).isNotEmpty()) return
            // Re-POST (rows 8–10) when the hop's previous outcome is
            // unknown — read from the hop history, not the marker.
            val isRepost = outcomes.any {
                it.routeId == routeId &&
                    (it.outcome == "UNKNOWN_PENDING_RECONCILIATION" || it.outcome == "MATCHED_UNCONFIRMED")
            }
            // §7.3A/§7.3B: resolve the claim body. A B delivery
            // posts raw from the in-memory snapshot; on raw loss
            // the FallbackGate decides A′ vs hold. A non-B delivery
            // posts the stored payload.
            val claim = try {
                resolveClaimBody(
                    delivery, event, route, routeId, stamps[routeId], outcomes, isRepost,
                )
            } catch (e: Exception) {
                null
            }
            if (claim == null) {
                val at = clock()
                transactions.run {
                    deliveryDao.checkpointHopIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, at,
                        nextIndex, HopOutcome.toJsonArray(outcomes)
                    )
                    deliveryDao.blockIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, at,
                        "missing_payload", "payload pengiriman tidak tersedia atau rusak"
                    )
                }
                return
            }
            if (claim is ClaimBody.Hold) {
                val at = clock()
                transactions.run {
                    deliveryDao.checkpointHopIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, at,
                        nextIndex, HopOutcome.toJsonArray(outcomes)
                    )
                    deliveryDao.holdIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, at,
                        claim.state, claim.errorCode, claim.summary,
                        claim.rawLost, nextIndex, HopOutcome.toJsonArray(outcomes),
                    )
                }
                return
            }
            val body = claim as ClaimBody.Body
            val payload = body.payload
            val fenceGeneration = body.fenceGeneration
            val snapshotTaken = body.snapshotTaken
            if (body.isFallback) {
                // The raw snapshot is gone; flag the delivery so
                // the operator knows the posted text is
                // constructed (§7.3B). A lost lease stops here —
                // the reclaim returns the delivery to PENDING.
                if (deliveryDao.markRawLostIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, clock(),
                    ) != 1
                ) return
            }
            // Admission (§8.5): the marker is written before
            // `openConnection()` — the only durable truth that this
            // hop was entered.
            val attemptNo = (attemptDao.getByDeliveryId(delivery.deliveryId).maxOfOrNull { it.attemptNo } ?: 0) + 1
            val attemptId = UUID.randomUUID().toString()
            val startedAt = clock()
            val stamp = stamps[routeId]
            attemptDao.insert(
                DeliveryAttemptEntity(
                    attemptId = attemptId,
                    deliveryId = delivery.deliveryId,
                    routeId = routeId,
                    attemptNo = attemptNo,
                    startedAtMs = startedAt,
                    state = AttemptState.ATTEMPT_STARTED,
                    hopIndex = nextIndex,
                    consentEpoch = parseConsentRecord(consentsJson, routeId)?.epoch,
                    configEpoch = stamp?.configEpoch,
                    urlSha256 = sha256Hex(route.url),
                    dedupeKey = event.dedupeKey,
                    ownerBootId = bootId,
                    // §7.3B: an authorised fallback dispatch is
                    // flagged raw_lost on the attempt row.
                    rawLost = body.isFallback,
                    // §7.3A(e)/B20: the admission stamp — the fence
                    // generation recorded at the pre-send gate.
                    fenceGeneration = fenceGeneration,
                )
            )
            // Claim for socket open. If the row was abandoned
            // concurrently, stop: the coordinator owns it now. A
            // taken snapshot is returned to the map — the claim
            // transaction failed after the take (§7.3A(d)).
            if (attemptDao.claimForSocketOpen(attemptId) != 1) {
                snapshotTaken?.let { rawSnapshotStore?.returnSnapshot(it) }
                return
            }
            // §7.3A(e) admission fence: the generation recorded at
            // the pre-send gate is re-read immediately before the
            // first write, as the LAST step of the checkpoint
            // callback — a plain volatile read, no lock held, no
            // I/O, no suspension point between the read and the
            // write. A mismatch aborts before any byte is written.
            var fenceAborted = false
            val result = transport.post(
                TransportRequest(url = route.url, authSecret = secret, body = payload.toString())
            ) {
                // SOCKET_OPEN checkpoint: the attempt row moves to
                // SOCKET_OPEN only; the hop checkpoint lands after the
                // POST returns (§5.5). A false return aborts the POST
                // before the first byte is written.
                if (attemptDao.markSocketOpen(attemptId) != 1) return@post false
                val fence = fenceGeneration
                if (fence != null && rawSnapshotStore?.fence(routeId) != fence) {
                    fenceAborted = true
                    return@post false
                }
                true
            }
            val finishedAt = clock()
            val durationMs = finishedAt - startedAt
            if (body.isFallback && result !is TransportResult.CheckpointFailed &&
                !(result is TransportResult.NetworkError && result.errorCode in NO_POST_ERROR_CODES)
            ) {
                // The fallback was actually dispatched (bytes
                // written or attempted) — count it per route
                // (§7.3B visibility, `raw_lost_fallback_total`).
                onFallbackDispatched(routeId)
            }
            when (result) {
                is TransportResult.CheckpointFailed -> {
                    if (fenceAborted) {
                        // Revocation observed at the final admission
                        // read (B20 case (a)): zero bytes written,
                        // snapshot dropped unsent, delivery BLOCKED
                        // — no further raw POST follows.
                        transactions.run {
                            deliveryDao.checkpointHopIfLeaseHeld(
                                delivery.deliveryId, leaseOwner, finishedAt,
                                nextIndex, HopOutcome.toJsonArray(outcomes)
                            )
                            deliveryDao.blockIfLeaseHeld(
                                delivery.deliveryId, leaseOwner, finishedAt,
                                "route_revoked",
                                "persetujuan dicabut sebelum pengiriman raw; tidak ada byte terkirim",
                            )
                        }
                        attemptDao.markCompleted(
                            attemptId, finishedAt, null, "CHECKPOINT_FAILED",
                            "fence_revoked", durationMs, "fence_revoked",
                            HopOutcome.toJsonArray(outcomes)
                        )
                        return
                    }
                    // The attempt was abandoned (or storage failed)
                    // after admission. Leave the delivery IN_FLIGHT:
                    // the lease reclaim returns it to PENDING. The
                    // attempt row is still completed so it never
                    // blocks reclaim as an orphan. A taken snapshot
                    // is returned — the POST never started.
                    snapshotTaken?.let { rawSnapshotStore?.returnSnapshot(it) }
                    attemptDao.markCompleted(
                        attemptId, finishedAt, null, "CHECKPOINT_FAILED",
                        "checkpoint_failed", durationMs, "checkpoint_failed",
                        HopOutcome.toJsonArray(outcomes)
                    )
                    return
                }
                is TransportResult.NetworkError -> {
                    if (result.errorCode in NO_POST_ERROR_CODES) {
                        // §5.5 row 5: provably not sent — retry the same hop.
                        outcomes += HopOutcome(routeId, "NO_POST", null, null, null, finishedAt)
                        completeAndRetry(
                            delivery, leaseOwner, attemptId, finishedAt,
                            "NO_POST", result.errorCode, durationMs,
                            nextIndex, outcomes
                        )
                    } else {
                        // §5.5 row 4: the request may have been sent —
                        // the hop is UNKNOWN. First attempt: RETRY_WAIT
                        // (T08) and the next attempt is a re-POST
                        // (rows 8–10). Re-POST failure (row 10): a
                        // failed re-POST proves nothing about the first
                        // attempt — the delivery holds.
                        outcomes += HopOutcome(
                            routeId, "UNKNOWN_PENDING_RECONCILIATION",
                            null, null, null, finishedAt
                        )
                        if (isRepost) {
                            completeAndHold(
                                delivery, leaseOwner, attemptId, finishedAt,
                                "UNKNOWN_PENDING_RECONCILIATION", result.errorCode,
                                durationMs, nextIndex, outcomes
                            )
                        } else {
                            completeAndRetry(
                                delivery, leaseOwner, attemptId, finishedAt,
                                "UNKNOWN_PENDING_RECONCILIATION", result.errorCode,
                                durationMs, nextIndex, outcomes
                            )
                        }
                    }
                    return
                }
                is TransportResult.Http -> {
                    val outcome = LegacyResponseClassifier.classify(
                        result.status, result.headers, result.body,
                        event.category, isRepost
                    )
                    val advance = handleClassified(
                        delivery, leaseOwner, route, routeId, nextIndex,
                        outcomes, attemptId, finishedAt, durationMs,
                        result, outcome, nextIndex >= routeIds.size - 1,
                        event.category
                    )
                    if (!advance) return
                    nextIndex++
                }
            }
        }
        // Chain exhausted with no terminal outcome: every hop was
        // NO_POST, so nothing was sent anywhere. BLOCKED, not ACKED —
        // a payment that reached no route must stay visible until an
        // operator fixes the routes (or discards the delivery).
        deliveryDao.blockIfLeaseHeld(
            delivery.deliveryId, leaseOwner, clock(),
            "all_routes_skipped", "semua rute dilewati (dihapus/dinonaktifkan/tanpa secret)"
        )
    }

    /**
     * Handles one classified HTTP response. Returns true when the
     * chain advances to the next hop (LOGGED, not the last route);
     * every other outcome is terminal for this pass.
     */
    private suspend fun handleClassified(
        delivery: DeliveryEntity,
        leaseOwner: String,
        route: Route,
        routeId: String,
        nextIndex: Int,
        outcomes: MutableList<HopOutcome>,
        attemptId: String,
        finishedAt: Long,
        durationMs: Long,
        result: TransportResult.Http,
        outcome: ClassificationOutcome,
        isLastRoute: Boolean,
        eventCategory: String,
    ): Boolean {
        when (outcome.classification) {
            ResponseClassification.MATCHED_SETTLED -> {
                outcomes += HopOutcome(routeId, "MATCHED_SETTLED", result.status, "matched", "ok", finishedAt)
                transactions.run {
                    attemptDao.markCompleted(
                        attemptId, finishedAt, result.status, "MATCHED_SETTLED",
                        null, durationMs, "matched/ok", HopOutcome.toJsonArray(outcomes)
                    )
                    deliveryDao.checkpointHopIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, finishedAt,
                        nextIndex, HopOutcome.toJsonArray(outcomes)
                    )
                    deliveryDao.ackIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, finishedAt, "matched", "ok"
                    )
                }
                return false
            }
            ResponseClassification.MATCHED_NOT_APPLIED -> {
                outcomes += HopOutcome(routeId, "MATCHED_NOT_APPLIED", result.status, "matched", "skip", finishedAt)
                transactions.run {
                    attemptDao.markCompleted(
                        attemptId, finishedAt, result.status, "MATCHED_NOT_APPLIED",
                        null, durationMs, "matched/skip", HopOutcome.toJsonArray(outcomes)
                    )
                    deliveryDao.checkpointHopIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, finishedAt,
                        nextIndex, HopOutcome.toJsonArray(outcomes)
                    )
                    // last_apply_status='skip' IS the review flag (§5.4).
                    deliveryDao.ackIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, finishedAt, "matched", "skip"
                    )
                }
                return false
            }
            ResponseClassification.MATCHED_UNCONFIRMED -> {
                outcomes += HopOutcome(routeId, "MATCHED_UNCONFIRMED", result.status, "matched", outcome.lastApplyStatus, finishedAt)
                completeAndHold(
                    delivery, leaseOwner, attemptId, finishedAt,
                    "MATCHED_UNCONFIRMED", "matched_unconfirmed", durationMs,
                    nextIndex, outcomes, result.status
                )
                return false
            }
            ResponseClassification.AMBIGUOUS -> {
                outcomes += HopOutcome(routeId, "AMBIGUOUS", result.status, "ambiguous", null, finishedAt)
                transactions.run {
                    attemptDao.markCompleted(
                        attemptId, finishedAt, result.status, "AMBIGUOUS",
                        null, durationMs, "ambiguous", HopOutcome.toJsonArray(outcomes)
                    )
                    deliveryDao.checkpointHopIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, finishedAt,
                        nextIndex, HopOutcome.toJsonArray(outcomes)
                    )
                    deliveryDao.ackIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, finishedAt, "ambiguous", null
                    )
                }
                return false
            }
            ResponseClassification.LOGGED -> {
                outcomes += HopOutcome(routeId, "LOGGED", result.status, "logged", null, finishedAt)
                transactions.run {
                    attemptDao.markCompleted(
                        attemptId, finishedAt, result.status, "LOGGED",
                        null, durationMs, "logged", HopOutcome.toJsonArray(outcomes)
                    )
                    deliveryDao.checkpointHopIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, finishedAt,
                        nextIndex + 1, HopOutcome.toJsonArray(outcomes)
                    )
                    if (isLastRoute) {
                        // Logged at the last route: ACKED with
                        // last_match_status='logged' — NOT settled (§5).
                        deliveryDao.ackIfLeaseHeld(
                            delivery.deliveryId, leaseOwner, finishedAt, "logged", null
                        )
                    }
                }
                return !isLastRoute
            }
            ResponseClassification.ACK -> {
                outcomes += HopOutcome(routeId, "ACK", result.status, outcome.matchStatus, null, finishedAt)
                transactions.run {
                    attemptDao.markCompleted(
                        attemptId, finishedAt, result.status, "ACK",
                        null, durationMs, "ack", HopOutcome.toJsonArray(outcomes)
                    )
                    deliveryDao.checkpointHopIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, finishedAt,
                        nextIndex, HopOutcome.toJsonArray(outcomes)
                    )
                    deliveryDao.ackIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, finishedAt, outcome.matchStatus, null
                    )
                }
                return false
            }
            ResponseClassification.RETRY -> {
                // §5.5 row 4: any RETRY response (5xx, 408, 429,
                // malformed 2xx) means the request reached the server
                // — the hop outcome is UNKNOWN, the delivery
                // RETRY_WAITs (T08), and the next attempt is a re-POST
                // (rows 8–10). Heartbeat/test never settle, so they
                // keep the plain RETRY outcome (the classifier ignores
                // the re-POST flag for them anyway).
                val hopOutcome = if (eventCategory == "payment") {
                    "UNKNOWN_PENDING_RECONCILIATION"
                } else {
                    "RETRY"
                }
                outcomes += HopOutcome(routeId, hopOutcome, result.status, outcome.matchStatus, null, finishedAt)
                if (delivery.attempts >= MAX_ATTEMPTS_BEFORE_BLOCKED) {
                    transactions.run {
                        attemptDao.markCompleted(
                            attemptId, finishedAt, result.status, hopOutcome,
                            "retry_exhausted", durationMs, "HTTP ${result.status}",
                            HopOutcome.toJsonArray(outcomes)
                        )
                        deliveryDao.checkpointHopIfLeaseHeld(
                            delivery.deliveryId, leaseOwner, finishedAt,
                            nextIndex, HopOutcome.toJsonArray(outcomes)
                        )
                        deliveryDao.blockIfLeaseHeld(
                            delivery.deliveryId, leaseOwner, finishedAt,
                            "retry_exhausted",
                            "pengiriman gagal ${delivery.attempts} kali; perlu tindakan operator"
                        )
                    }
                } else {
                    val delayMs = outcome.retryAfterSeconds?.let { it * 1000 }
                        ?: backoffDelayMs(delivery.attempts)
                    transactions.run {
                        attemptDao.markCompleted(
                            attemptId, finishedAt, result.status, hopOutcome,
                            "http_${result.status}", durationMs, "HTTP ${result.status}",
                            HopOutcome.toJsonArray(outcomes)
                        )
                        deliveryDao.retryWaitIfLeaseHeld(
                            delivery.deliveryId, leaseOwner, finishedAt,
                            finishedAt + delayMs, "http_${result.status}",
                            "HTTP ${result.status}", nextIndex,
                            HopOutcome.toJsonArray(outcomes)
                        )
                    }
                }
                return false
            }
            ResponseClassification.BLOCKED -> {
                val hopOutcome = when (outcome.blockedCategory) {
                    "auth" -> "BLOCKED_CONSENT"
                    "config" -> "BLOCKED_CONFIG"
                    else -> "REJECTED-4xx"
                }
                outcomes += HopOutcome(routeId, hopOutcome, result.status, outcome.matchStatus, null, finishedAt)
                val summary = buildString {
                    append("ditolak server (HTTP ${result.status})")
                    if (outcome.location != null) append("; redirect ke ${outcome.location}")
                }
                transactions.run {
                    attemptDao.markCompleted(
                        attemptId, finishedAt, result.status, hopOutcome,
                        "http_${result.status}", durationMs, "HTTP ${result.status}",
                        HopOutcome.toJsonArray(outcomes)
                    )
                    deliveryDao.checkpointHopIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, finishedAt,
                        nextIndex, HopOutcome.toJsonArray(outcomes)
                    )
                    deliveryDao.blockIfLeaseHeld(
                        delivery.deliveryId, leaseOwner, finishedAt,
                        "http_${result.status}", summary
                    )
                }
                return false
            }
            ResponseClassification.UNKNOWN_PENDING_RECONCILIATION -> {
                outcomes += HopOutcome(
                    routeId, "UNKNOWN_PENDING_RECONCILIATION",
                    result.status, outcome.matchStatus, null, finishedAt
                )
                completeAndHold(
                    delivery, leaseOwner, attemptId, finishedAt,
                    "UNKNOWN_PENDING_RECONCILIATION", "unknown_hop", durationMs,
                    nextIndex, outcomes, result.status
                )
                return false
            }
        }
    }

    private suspend fun completeAndRetry(
        delivery: DeliveryEntity,
        leaseOwner: String,
        attemptId: String,
        finishedAt: Long,
        resultClass: String,
        errorCode: String,
        durationMs: Long,
        nextIndex: Int,
        outcomes: List<HopOutcome>,
    ) {
        if (delivery.attempts >= MAX_ATTEMPTS_BEFORE_BLOCKED) {
            transactions.run {
                attemptDao.markCompleted(
                    attemptId, finishedAt, null, resultClass,
                    "retry_exhausted", durationMs, errorCode,
                    HopOutcome.toJsonArray(outcomes)
                )
                deliveryDao.checkpointHopIfLeaseHeld(
                    delivery.deliveryId, leaseOwner, finishedAt,
                    nextIndex, HopOutcome.toJsonArray(outcomes)
                )
                deliveryDao.blockIfLeaseHeld(
                    delivery.deliveryId, leaseOwner, finishedAt,
                    "retry_exhausted",
                    "pengiriman gagal ${delivery.attempts} kali; perlu tindakan operator"
                )
            }
        } else {
            transactions.run {
                attemptDao.markCompleted(
                    attemptId, finishedAt, null, resultClass,
                    errorCode, durationMs, errorCode,
                    HopOutcome.toJsonArray(outcomes)
                )
                deliveryDao.retryWaitIfLeaseHeld(
                    delivery.deliveryId, leaseOwner, finishedAt,
                    finishedAt + backoffDelayMs(delivery.attempts),
                    errorCode, errorCode, nextIndex,
                    HopOutcome.toJsonArray(outcomes)
                )
            }
        }
    }

    private suspend fun completeAndHold(
        delivery: DeliveryEntity,
        leaseOwner: String,
        attemptId: String,
        finishedAt: Long,
        resultClass: String,
        errorCode: String,
        durationMs: Long,
        nextIndex: Int,
        outcomes: List<HopOutcome>,
        httpStatus: Int? = null,
    ) {
        transactions.run {
            attemptDao.markCompleted(
                attemptId, finishedAt, httpStatus, resultClass,
                errorCode, durationMs, errorCode,
                HopOutcome.toJsonArray(outcomes)
            )
            deliveryDao.holdForReconciliationIfLeaseHeld(
                delivery.deliveryId, leaseOwner, finishedAt,
                errorCode, "hasil hop tidak diketahui; menunggu rekonsiliasi",
                nextIndex, HopOutcome.toJsonArray(outcomes)
            )
        }
    }

    /** Hop checkpoint outside a POST (NO_POST skips). False when the lease is lost. */
    private suspend fun checkpoint(
        delivery: DeliveryEntity,
        leaseOwner: String,
        nextIndex: Int,
        outcomes: List<HopOutcome>,
    ): Boolean {
        val at = clock()
        return deliveryDao.checkpointHopIfLeaseHeld(
            delivery.deliveryId, leaseOwner, at,
            nextIndex, HopOutcome.toJsonArray(outcomes)
        ) == 1
    }

    // --- Payload ---

    /**
     * The wire payload for one hop. Imported items go out verbatim;
     * heartbeat/test are built from structured fields. A payment
     * without a stored payload cannot be dispatched (Stage C
     * boundary: the Stage D collector always provides a payload).
     */
    private fun buildPayload(event: EventEntity, routeId: String): JSONObject? {
        val legacy = event.legacyPayloadJson
        if (legacy != null) {
            return try {
                LegacyPayloadMapper.mapImported(legacy)
            } catch (e: Exception) {
                null
            }
        }
        return when (event.category) {
            "heartbeat" -> LegacyPayloadMapper.mapHeartbeat(
                routes.deviceLabel(), routeId, event.occurredAtMs
            )
            "test" -> LegacyPayloadMapper.mapTest(
                routes.deviceLabel(), routeId, event.occurredAtMs
            )
            else -> null
        }
    }

    // --- Claim body (§7.3A/§7.3B) ---

    /**
     * The resolved claim body for one hop: either a POST body or a
     * hold decision. A B delivery posts raw from the in-memory
     * snapshot; on raw loss the FallbackGate decides A′ vs hold.
     */
    private sealed interface ClaimBody {
        /** The POST body for this hop, resolved. */
        data class Body(
            val payload: JSONObject,
            /** The taken raw snapshot, if any — returned to the store when the POST never starts. */
            val snapshotTaken: RawSnapshotStore.RawSnapshot?,
            /** The fence generation recorded at the pre-send gate (B deliveries only). */
            val fenceGeneration: Long?,
            /** True when the body is an authorised A′ fallback (raw lost). */
            val isFallback: Boolean,
        ) : ClaimBody

        /** Hold the delivery instead of dispatching. */
        data class Hold(
            val state: DeliveryState,
            val errorCode: String,
            val summary: String,
            val rawLost: Boolean,
        ) : ClaimBody
    }

    /**
     * Resolves the claim body for one hop (§7.3A/§7.3B).
     *
     * Non-B deliveries post the stored payload. A B delivery
     * re-checks the raw opt-in, then takes the snapshot
     * (take-don't-copy); on raw loss the §7.3B FallbackGate
     * decides between an authorised A′ dispatch and a hold.
     *
     * Stage D boundary: the §9 status query does not exist yet
     * (D5/D6 reconciliation), so criterion 5 can only pass when
     * no POST was ever attempted for the `dedupe_key` — a
     * fallback re-POST after any prior POST holds
     * `RAW_UNAVAILABLE` until §9 reconciliation is wired.
     */
    private suspend fun resolveClaimBody(
        delivery: DeliveryEntity,
        event: EventEntity,
        route: Route,
        routeId: String,
        stamp: RouteStamp?,
        outcomes: List<HopOutcome>,
        isRepost: Boolean,
    ): ClaimBody? {
        val stored = applyPickedPayloadOverride(
            delivery.deliveryId, buildPayload(event, routeId),
        )
        // Non-B deliveries and legacy imports post the stored payload.
        if (!delivery.rawEphemeral) {
            return stored?.let { ClaimBody.Body(it, null, null, false) }
        }
        val store = rawSnapshotStore
        val consent = consentStore
        val now = clock()
        // Pre-send consent re-check (§7.3): the raw opt-in must be
        // valid NOW, not just at capture. Without a consent store
        // the re-check cannot run — fail closed (B5b/B16).
        val consentValid = consent?.isRawOptInValid(routeId, now) == true
        if (!consentValid) {
            return ClaimBody.Hold(
                DeliveryState.BLOCKED, "consent_expired",
                "persetujuan raw rute ${route.name} tidak berlaku saat pengiriman; raw tidak dikirim",
                rawLost = false,
            )
        }
        // Take the snapshot (take-don't-copy): at most one claim
        // sees the raw text. The epoch is the capture-time stamp —
        // a revoked-and-regranted route has a new epoch and the old
        // snapshot is unclaimable.
        val epoch = stamp?.consentEpoch ?: -1L
        val snapshot = store?.take(event.dedupeKey, routeId, epoch)
        if (snapshot != null) {
            val fence = store.fence(routeId)
            val raw = buildRawBody(stored, snapshot) ?: return null
            return ClaimBody.Body(
                payload = raw,
                snapshotTaken = snapshot,
                fenceGeneration = fence,
                isFallback = false,
            )
        }
        // Raw lost (evicted / TTL / process restart / cross-process
        // retry): §7.3B guarded fallback evaluation.
        return evaluateFallback(
            delivery, event, route, routeId, stored, outcomes, isRepost, now,
        )
    }

    /**
     * §7.3B guarded fallback evaluation. The caller supplies the
     * reconciliation evidence it can observe; the §9 status query
     * is not wired in Stage D, so a prior POST can never be
     * reconciled here (fail-closed).
     */
    private suspend fun evaluateFallback(
        delivery: DeliveryEntity,
        event: EventEntity,
        route: Route,
        routeId: String,
        stored: JSONObject?,
        outcomes: List<HopOutcome>,
        isRepost: Boolean,
        now: Long,
    ): ClaimBody {
        val amountMinor = stored?.optLong("amount", -1L)?.takeIf { it >= 0 }
        val amountAmbiguous = stored?.optBoolean("amount_ambiguous", false) == true
        val amountReliable = amountMinor != null && !amountAmbiguous
        val direction = storedDirection(stored)
        val kind = endpointKind(route)
        val noPriorPost =
            !isRepost && outcomes.isEmpty() &&
                attemptDao.getByDeliveryId(delivery.deliveryId).isEmpty()
        val reconciliation = FallbackGate.ReconciliationEvidence(
            noPriorPost = noPriorPost,
            // §9 query not wired in Stage D: no authoritative
            // answer exists, so a prior POST never reconciles.
            queryResult = null,
            writerCoverage = null,
            unknownHopExists = outcomes.any {
                it.outcome == "UNKNOWN_PENDING_RECONCILIATION"
            },
            bindingMatches = false,
            durableRecord = false,
        )
        val consentValid = consentStore?.isRawOptInValid(routeId, now) == true
        val input = FallbackGate.FallbackInput(
            amountReliable = amountReliable,
            direction = direction,
            // Criterion 3: the original stable dedupe_key is the
            // identity; nothing fabricated. Verified identifiers
            // from the D2b identity parser are advisory-only and
            // do not gate the fallback.
            identityReliable = event.dedupeKey.isNotBlank(),
            endpointKind = kind,
            recorderGatePassed = recorderGatePassed,
            reconciliation = reconciliation,
            consentValid = consentValid,
        )
        val decision = FallbackGate.evaluate(input)
        if (!decision.authorized) {
            return ClaimBody.Hold(
                state = decision.holdState ?: DeliveryState.RAW_UNAVAILABLE,
                errorCode = "fallback_refused",
                summary = "fallback A′ tidak diizinkan (kriteria gagal: " +
                    "${decision.failedCriteria.joinToString(", ")})",
                rawLost = true,
            )
        }
        // Authorised: build the A′ body from structured fields only.
        val fallback = buildFallbackBody(stored, kind, amountMinor ?: 0L, direction)
            ?: return ClaimBody.Hold(
                state = DeliveryState.RAW_UNAVAILABLE,
                errorCode = "fallback_build_failed",
                summary = "teks fallback tidak dapat dibangun dari field terstruktur",
                rawLost = true,
            )
        return ClaimBody.Body(fallback, null, null, isFallback = true)
    }

    /**
     * Applies a §7.6 picked-payload override (Stage D review): the
     * event row is immutable, so a picked delivery carries its
     * chosen amount + recomputed `dedupe_key` in a
     * `picked_payload:<delivery_id>` document. The override is
     * applied before the B/non-B branching, so the chosen amount
     * flows into the raw body, the fallback body, and the §7.3B
     * evaluation (the §7.6 precedence rule: the pick re-runs the
     * fallback evaluation with the chosen amount). A missing or
     * malformed override leaves the stored payload untouched
     * (fail-closed: the stored first-token amount is never silently
     * replaced by a partial override).
     */
    private suspend fun applyPickedPayloadOverride(
        deliveryId: String,
        stored: JSONObject?,
    ): JSONObject? {
        if (stored == null) return null
        val raw = deliveryDao.readCollectorValue(
            CollectorStateKeys.PICKED_PAYLOAD_PREFIX + deliveryId,
        ) ?: return stored
        val override = runCatching { JSONObject(raw) }.getOrNull() ?: return stored
        if (!override.has("amount") || !override.has("dedupe_key")) return stored
        stored.put("amount", override.optLong("amount"))
        stored.put("dedupe_key", override.optString("dedupe_key"))
        stored.put("amount_ambiguous", false)
        stored.remove("amount_tokens")
        return stored
    }

    /** The raw POST body: the stored payload with the raw title/text restored. */
    private fun buildRawBody(
        stored: JSONObject?,
        snapshot: RawSnapshotStore.RawSnapshot,
    ): JSONObject? {
        val base = stored ?: return null
        val raw = JSONObject(base.toString())
        raw.put("title", snapshot.title.take(500))
        raw.put("text", snapshot.text.take(500))
        // The redaction flags describe the stored text, not the raw body.
        raw.remove("redacted")
        raw.remove("inseparable")
        return raw
    }

    /**
     * The A′ fallback body (§7.2/§7.3B). NOTIFY routes post the
     * stored structured fields (the matcher reads `amount` only);
     * recorder routes post the §7.2 constructed text. Returns null
     * when the constructed text needs operator review (a token was
     * sanitised) — the gate authorised the fallback, but the text
     * itself is not safe to send as constructed.
     */
    private fun buildFallbackBody(
        stored: JSONObject?,
        kind: FallbackGate.EndpointKind,
        amountMinor: Long,
        direction: Direction?,
    ): JSONObject? {
        val base = stored ?: return null
        if (kind != FallbackGate.EndpointKind.RECORDER) return base
        // Masked account / reference come from the D2b identity
        // parser (advisory-only); no confirmed patterns exist yet,
        // so both are omitted — never fabricated.
        val built = LegacyPayloadMapper.buildConstructedText(
            direction ?: Direction.UNKNOWN, amountMinor, null, null,
        )
        if (built.needsReview) return null
        val fallback = JSONObject(base.toString())
        fallback.put("text", built.text)
        return fallback
    }

    /** The capture-time direction stored in the payload (lowercase name), or null. */
    private fun storedDirection(stored: JSONObject?): Direction? {
        val raw = stored?.optString("direction")?.takeIf { it.isNotBlank() } ?: return null
        return try {
            Direction.valueOf(raw.uppercase())
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Endpoint kind from the route URL (criterion 4). */
    private fun endpointKind(route: Route): FallbackGate.EndpointKind {
        return if (route.url.contains("catat.php")) {
            FallbackGate.EndpointKind.RECORDER
        } else {
            FallbackGate.EndpointKind.NOTIFY
        }
    }

    // --- Stamp check (§5.5 row 7, §7.5) ---

    private sealed interface StampCheck {
        data object Ok : StampCheck
        data class Blocked(
            val outcome: String,
            val errorCode: String,
            val summary: String,
        ) : StampCheck
    }

    private data class ConsentRecord(val granted: Boolean, val epoch: Long)

    private fun parseConsentRecord(consentsJson: String?, routeId: String): ConsentRecord? {
        if (consentsJson == null) return null
        return try {
            val routesObj = JSONObject(consentsJson).optJSONObject("routes") ?: return null
            val record = routesObj.optJSONObject(routeId) ?: return null
            ConsentRecord(
                granted = record.optBoolean("granted", true),
                epoch = record.optLong("epoch", -1),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun checkStamp(
        route: Route,
        stamp: RouteStamp?,
        consentsJson: String?,
    ): StampCheck {
        if (stamp == null) return StampCheck.Ok // legacy import: no stamp to check
        val record = parseConsentRecord(consentsJson, route.id)
        if (record != null) {
            if (!record.granted) {
                return StampCheck.Blocked(
                    "BLOCKED_CONSENT", "consent_revoked",
                    "persetujuan rute ${route.name} dicabut"
                )
            }
            if (record.epoch >= 0 && record.epoch != stamp.consentEpoch) {
                return StampCheck.Blocked(
                    "BLOCKED_CONSENT", "consent_epoch_mismatch",
                    "persetujuan rute ${route.name} berubah sejak pengambilan"
                )
            }
        }
        if (stamp.urlSha256.isNotEmpty() && sha256Hex(route.url) != stamp.urlSha256) {
            return StampCheck.Blocked(
                "BLOCKED_CONFIG", "url_changed",
                "URL rute ${route.name} berubah sejak pengambilan"
            )
        }
        // config_epoch has no live source in Stage C (route config
        // has no version yet): a URL change is the config signal.
        // Stage D adds a config-epoch source and extends this check.
        return StampCheck.Ok
    }

    // --- Helpers ---

    private suspend fun readBootId(): String {
        val doc = deliveryDao.readCollectorValue(CollectorStateKeys.BOOT_ID)
        return try {
            doc?.let { JSONObject(it).optString("boot_id") }?.ifEmpty { null }
        } catch (e: Exception) {
            null
        } ?: "unknown"
    }

    private fun parseRouteIds(json: String): List<String>? {
        return try {
            Route.jsonStrings(JSONArray(json))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Exponential backoff (§5.4): start 1 min, multiply 2, cap 2 h,
     * jitter ±25%. `attempts` is the delivery's claim count (already
     * incremented by this claim).
     */
    internal fun backoffDelayMs(attempts: Int): Long {
        val exponent = (attempts - 1).coerceIn(0, 7)
        val raw = 60_000L * (1L shl exponent)
        val capped = minOf(raw, 2 * 60 * 60 * 1000L)
        val jitter = 1.0 + (random() * 0.5 - 0.25)
        return (capped * jitter).toLong().coerceAtLeast(0)
    }

    internal fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
