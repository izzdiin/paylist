package com.netter.paylist.collector

import com.netter.paylist.Route
import com.netter.paylist.SenderLock
import com.netter.paylist.Store
import com.netter.paylist.core.model.Delivery
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.core.model.DeliveryStateMachine
import com.netter.paylist.core.model.RouteStamp
import com.netter.paylist.data.RouteStampMap
import com.netter.paylist.data.TransactionRunner
import com.netter.paylist.data.dao.CollectorStateDao
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.data.dao.EventDao
import com.netter.paylist.data.dao.MatchResultDao
import com.netter.paylist.data.dao.AttributionOutcome
import com.netter.paylist.data.entity.AdvisoryMatchStatus
import com.netter.paylist.data.entity.AttributionStatus
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.data.entity.MatchResultEntity
import com.netter.paylist.data.toEntity
import com.netter.paylist.migration.QuarantineRecord
import com.netter.paylist.migration.QuarantineRecordStore
import com.netter.paylist.parser.Direction
import com.netter.paylist.privacy.LegacyQueuePrivacyGate
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.locks.Lock
import kotlin.concurrent.withLock

/**
 * Outcome of a legacy-queue sweep for one `dedupe_key` (§7.6 step
 * (b)). `DISPATCHED` / `UNKNOWN` need the R13/R14 promotion receipts
 * (not landed in Stage C) — the production sweep returns only
 * `REMOVED` / `ABSENT` until receipts exist; the pick protocol
 * implements the full branching so the receipt work plugs in
 * without changing the review flow.
 */
enum class SweepOutcome {
    /** An undispatched copy was found and removed. */
    REMOVED,

    /** No copy exists in the legacy queue. */
    ABSENT,

    /** A copy was already dispatched (receipt `RESOLVED`/dispatched). */
    DISPATCHED,

    /** A copy existed but its dispatch state is unknown. */
    UNKNOWN,
}

/**
 * The legacy-queue side of the §7.6 pick protocol: find/sweep a
 * prior copy by `dedupe_key`. The production implementation holds
 * [SenderLock] across scan + remove, so the flush cannot dispatch
 * the copy mid-sweep.
 */
interface LegacySweep {
    /** True when an undispatched legacy copy with this key exists. */
    fun exists(dedupeKey: String): Boolean

    /** Removes an undispatched legacy copy; reports the outcome. */
    fun sweep(dedupeKey: String): SweepOutcome
}

/** Current route set, re-read at pick time (§7.6(a)). */
interface ReviewRouteSource {
    fun routeById(routeId: String): Route?
    fun deviceLabel(): String
}

/**
 * The legacy queue as the quarantine workflow sees it. `find`
 * returns the RAW queue item (in-memory only — raw text is never
 * logged or persisted except through the PrivacyGate's redacted
 * output). `remove` deletes the item (the discard path's raw
 * deletion, §7.7).
 */
interface QuarantineQueue {
    fun findByDedupeKey(dedupeKey: String): JSONObject?
    fun removeByQkey(qkey: String): Boolean
}

/** [LegacySweep] over the SharedPreferences legacy queue. */
class StoreLegacySweep(
    private val store: Store,
    private val lock: Lock = SenderLock.lock,
) : LegacySweep {
    override fun exists(dedupeKey: String): Boolean = lock.withLock {
        store.queue().any { it.optJSONObject("payload")?.optString("dedupe_key") == dedupeKey }
    }

    override fun sweep(dedupeKey: String): SweepOutcome = lock.withLock {
        val queue = store.queue()
        val index = queue.indexOfFirst {
            it.optJSONObject("payload")?.optString("dedupe_key") == dedupeKey
        }
        if (index < 0) return@withLock SweepOutcome.ABSENT
        val remaining = queue.toMutableList().also { it.removeAt(index) }
        // Stage D boundary: without R13/R14 receipts an item found
        // in the queue is undispatched by construction (the flush
        // removes dispatched items) — REMOVED. DISPATCHED/UNKNOWN
        // stay unreachable until receipts land.
        if (!store.saveQueueSync(remaining)) return@withLock SweepOutcome.UNKNOWN
        SweepOutcome.REMOVED
    }
}

/** [ReviewRouteSource] over the [Store]. */
class StoreReviewRoutes(private val store: Store) : ReviewRouteSource {
    override fun routeById(routeId: String): Route? = store.routeById(routeId)
    override fun deviceLabel(): String = store.deviceLabel()
}

/** [QuarantineQueue] over the SharedPreferences legacy queue. */
class StoreQuarantineQueue(
    private val store: Store,
    private val lock: Lock = SenderLock.lock,
) : QuarantineQueue {
    override fun findByDedupeKey(dedupeKey: String): JSONObject? = lock.withLock {
        store.queue().firstOrNull {
            it.optJSONObject("payload")?.optString("dedupe_key") == dedupeKey
        }
    }

    override fun removeByQkey(qkey: String): Boolean = lock.withLock {
        val queue = store.queue()
        val remaining = queue.filter { it.optString("qkey") != qkey }
        if (remaining.size == queue.size) return@withLock false
        store.saveQueueSync(remaining)
    }
}

/** One currency token as shown on the review surface (value + position, never raw text). */
data class ReviewToken(val value: Long, val start: Int, val end: Int)

/**
 * One item on the operator review surface. Summaries show
 * amount-if-reliable, source, time, reason only — never OTP/PIN/auth
 * token, never raw spans, never `dedupe_key`/`content_sha256` internals
 * (LH-03: the surface shows the token list + content hash only where
 * the design names them for the amount pick).
 */
sealed interface ReviewItem {
    val sortMs: Long

    /** A §7.6 amount question: ≥2 differing currency tokens. */
    data class AmountQuestion(
        val deliveryId: String,
        val eventId: String,
        val sourcePackage: String?,
        val occurredAtMs: Long,
        val firstTokenAmount: Long,
        val tokens: List<ReviewToken>,
        val routeIds: List<String>,
        override val sortMs: Long,
    ) : ReviewItem

    /** A §7.3B raw-loss hold: the operator authorises A′ or discards. */
    data class RawLost(
        val deliveryId: String,
        val eventId: String,
        val sourcePackage: String?,
        val occurredAtMs: Long,
        val amount: Long?,
        val lastErrorCode: String?,
        val hardStopReasons: List<String>,
        override val sortMs: Long,
    ) : ReviewItem

    /** A §7.7 quarantined item awaiting approve/discard. */
    data class Quarantined(
        val record: QuarantineRecord,
        override val sortMs: Long,
    ) : ReviewItem

    /**
     * A P3 advisory match needing operator review (read-only in D3:
     * the manual attribution pick — pick order / correct number /
     * discard attribution — is a D5 follow-up; the row is shown so
     * the operator sees it, never auto-attributed).
     */
    data class MatchQuestion(
        val row: MatchResultEntity,
        override val sortMs: Long,
    ) : ReviewItem
}

/** Outcome of an amount pick (§7.6 ordered protocol). */
sealed interface PickOutcome {
    /** The picked delivery was released to PENDING. */
    data class Released(val deliveryId: String, val newDedupeKey: String) : PickOutcome

    /** The pick is held: a prior copy's dispatch state is unknown. */
    data class Held(val deliveryId: String, val reason: String) : PickOutcome

    /** The pick is blocked: a prior copy was already dispatched. */
    data class Blocked(val deliveryId: String, val reason: String) : PickOutcome

    /** The pick was refused (no state changed). */
    data class Refused(val reason: String) : PickOutcome
}

/** Outcome of a discard (amount or raw-lost). */
sealed interface DiscardOutcome {
    /** Discarded; `legacyCopyExisted` warns the operator a legacy copy may still dispatch. */
    data class Discarded(val legacyCopyExisted: Boolean) : DiscardOutcome
    data class Refused(val reason: String) : DiscardOutcome
}

/** Outcome of a RAW_UNAVAILABLE authorise-A′ decision. */
sealed interface AuthorizeOutcome {
    /** Authorised: the delivery is PENDING with the A′ fallback. */
    data object Authorized : AuthorizeOutcome
    data class Refused(val reason: String) : AuthorizeOutcome
}

/** Outcome of a quarantine approve/discard decision. */
sealed interface QuarantineOutcome {
    /** Approved; `imported` tells whether a delivery was created. */
    data class Approved(val imported: Boolean) : QuarantineOutcome
    data object Discarded : QuarantineOutcome
    data class NotFound(val dedupeKey: String) : QuarantineOutcome
    data class AlreadyResolved(val resolution: String) : QuarantineOutcome
    data class Refused(val reason: String) : QuarantineOutcome
}

/** Outcome of the D5 manual P3 attribution pick. */
sealed interface MatchResolveOutcome {
    /** The row became the one `attributed` row for its order. */
    data class Attributed(val matchId: String) : MatchResolveOutcome

    /** An attribution already exists; this row is verified duplicate evidence. */
    data class DuplicateEvidence(val matchId: String) : MatchResolveOutcome

    /** Still ambiguous after the pick (unverified branch — defensive only). */
    data class StillAmbiguous(val matchId: String) : MatchResolveOutcome

    /** The operator marked the row as not this transaction. */
    data class MarkedUnmatched(val matchId: String) : MatchResolveOutcome

    data class Refused(val reason: String) : MatchResolveOutcome
}

/**
 * The operator review surface (§7.6, §7.3B, §7.7; file plan §B):
 * the `NEEDS_REVIEW` amount queue with the ordered idempotent pick
 * protocol, the `RAW_UNAVAILABLE` authorise-A′/discard queue, and the
 * quarantine approve/discard workflow (re-review #6 blocker C).
 *
 * Every decision is audited (operator identity + timestamp) as a
 * `review_audit:` collector_state document, written in the same Room
 * transaction as the state transition it records. Audit documents
 * are append-only and never deleted by retention.
 *
 * Disclosure (shown by the UI, §7.6(d)): "Review holds forwarding —
 * this payment is not sent until you pick an amount or discard it."
 * The surface also warns that the bill may already have been settled
 * through another channel while waiting — check the server log
 * before confirming.
 */
class AmountReview(
    private val deliveryDao: DeliveryDao,
    private val eventDao: EventDao,
    private val collectorStateDao: CollectorStateDao,
    private val matchResultDao: MatchResultDao?,
    private val transactions: TransactionRunner,
    private val quarantineStore: QuarantineRecordStore,
    private val legacySweep: LegacySweep,
    private val quarantineQueue: QuarantineQueue,
    private val routes: ReviewRouteSource,
    private val consentStore: ConsentStore?,
    private val privacyGate: PrivacyGate = PrivacyGate(),
    /** B14 gate: automatic A′ on a recorder route needs a passing B14 (default false, fail-closed). */
    private val recorderGatePassed: Boolean = false,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    // --- Review queue ---

    /**
     * The full review queue, newest first: amount questions, raw-lost
     * holds, unresolved quarantine records, P3 matches needing
     * review. Quarantined items report as held/quarantined until
     * resolved — never delivered/resolved.
     */
    suspend fun listReviewQueue(): List<ReviewItem> {
        val items = mutableListOf<ReviewItem>()
        for (delivery in deliveryDao.getByState(DeliveryState.NEEDS_REVIEW)) {
            val event = eventDao.getById(delivery.eventId) ?: continue
            val payload = parsePayload(event.legacyPayloadJson)
            val tokens = payload?.optJSONArray("amount_tokens")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val t = arr.optJSONObject(i) ?: return@mapNotNull null
                    ReviewToken(t.optLong("value"), t.optInt("start"), t.optInt("end"))
                }
            }.orEmpty()
            items += ReviewItem.AmountQuestion(
                deliveryId = delivery.deliveryId,
                eventId = delivery.eventId,
                sourcePackage = event.sourcePackage,
                occurredAtMs = event.occurredAtMs,
                firstTokenAmount = payload?.optLong("amount", -1L) ?: -1L,
                tokens = tokens,
                routeIds = Route.jsonStrings(JSONArray(delivery.routeIdsJson)),
                sortMs = delivery.createdAtMs,
            )
        }
        for (delivery in deliveryDao.getByState(DeliveryState.RAW_UNAVAILABLE)) {
            val event = eventDao.getById(delivery.eventId) ?: continue
            val payload = parsePayload(event.legacyPayloadJson)
            items += ReviewItem.RawLost(
                deliveryId = delivery.deliveryId,
                eventId = delivery.eventId,
                sourcePackage = event.sourcePackage,
                occurredAtMs = event.occurredAtMs,
                amount = payload?.let { if (it.has("amount")) it.optLong("amount") else null },
                lastErrorCode = delivery.lastErrorCode,
                hardStopReasons = hardStopReasons(delivery, payload),
                sortMs = delivery.createdAtMs,
            )
        }
        for (record in quarantineStore.listAll()) {
            if (record.resolution == null) {
                items += ReviewItem.Quarantined(record, record.quarantinedAtMs)
            }
        }
        val matcher = matchResultDao
        if (matcher != null) {
            for (row in matcher.getNeedingReview()) {
                items += ReviewItem.MatchQuestion(row, row.createdAtMs)
            }
        }
        return items.sortedByDescending { it.sortMs }
    }

    /** Count of unacted review items (badge source for the review surface). */
    suspend fun reviewCount(): Int = listReviewQueue().size

    // --- Amount pick (§7.6 ordered idempotent protocol) ---

    /**
     * Picks the correct amount for a NEEDS_REVIEW delivery.
     *
     * (a) Room transaction — pick and hold: supersede the NEEDS_REVIEW
     * delivery (→ DISCARDED, named transition), create the picked
     * delivery HELD_AWAITING_LEGACY_SWEEP with the recomputed
     * `dedupe_key`, write the picked-payload override + pick audit.
     * (b) Legacy sweep under [SenderLock]: remove an undispatched
     * prior copy. (c) Room transaction — release (→ PENDING) or
     * block (prior_copy_dispatched) or hold (prior_copy_unknown).
     *
     * Pick-time re-validation (§7.6(a)): the route set + consent
     * epochs are re-read, not the capture-time values. A route
     * removed, disabled, URL-changed, or consent-revoked during
     * review blocks the pick until re-approved.
     *
     * Fail-closed: any OTHER non-terminal delivery for the same
     * event or original `dedupe_key` aborts the pick — the state
     * machine names no transition to cancel a live PENDING/HELD
     * delivery, and creating a second live copy risks a second
     * settlement.
     */
    suspend fun pickAmount(
        deliveryId: String,
        chosenAmountMinor: Long,
        operator: String,
        note: String? = null,
        nowMs: Long = clock(),
    ): PickOutcome {
        val delivery = deliveryDao.getById(deliveryId)
            ?: return PickOutcome.Refused("tidak ada delivery: $deliveryId")
        if (delivery.state != DeliveryState.NEEDS_REVIEW) {
            return PickOutcome.Refused("delivery berstatus ${delivery.state}, bukan NEEDS_REVIEW")
        }
        val event = eventDao.getById(delivery.eventId)
            ?: return PickOutcome.Refused("tidak ada event untuk delivery $deliveryId")
        val payload = parsePayload(event.legacyPayloadJson)
            ?: return PickOutcome.Refused("payload event rusak")
        val tokens = payload.optJSONArray("amount_tokens")?.let { arr ->
            (0 until arr.length()).map { arr.optJSONObject(it)?.optLong("value") ?: -1L }
        }.orEmpty()
        if (chosenAmountMinor !in tokens) {
            return PickOutcome.Refused(
                "jumlah yang dipilih (Rp$chosenAmountMinor) bukan salah satu token terurai",
            )
        }
        val originalDedupeKey = event.dedupeKey
        val newDedupeKey = recomputeDedupeKey(originalDedupeKey, chosenAmountMinor)
            ?: return PickOutcome.Refused("dedupe_key asal tidak dapat dihitung ulang")
        val routeIds = Route.jsonStrings(JSONArray(delivery.routeIdsJson))
        // Re-validate routes + consent at pick time.
        val currentStamps = mutableMapOf<String, RouteStamp>()
        for (routeId in routeIds) {
            val route = routes.routeById(routeId)
                ?: return PickOutcome.Refused("rute $routeId sudah tidak ada")
            if (!route.enabled) {
                return PickOutcome.Refused("rute ${route.name} sedang nonaktif")
            }
            val consent = consentStore?.get(routeId)
            if (consent == null || !consent.granted) {
                return PickOutcome.Refused("persetujuan rute ${route.name} dicabut / belum diberikan")
            }
            val captureStamp = RouteStampMap.fromJsonString(delivery.routeStampsJson)[routeId]
            val urlSha = CaptureCoordinator.sha256Hex(route.url)
            if (captureStamp != null && captureStamp.urlSha256 != urlSha) {
                return PickOutcome.Refused("URL rute ${route.name} berubah sejak capture")
            }
            currentStamps[routeId] = RouteStamp(
                consentEpoch = consent.epoch,
                configEpoch = 0,
                urlSha256 = urlSha,
            )
        }
        // No other live delivery for this event or key.
        val siblings = deliveryDao.getByEventId(delivery.eventId)
            .filter { it.deliveryId != deliveryId && !DeliveryStateMachine.isTerminal(it.state) }
        if (siblings.isNotEmpty()) {
            return PickOutcome.Refused(
                "masih ada delivery aktif lain untuk event ini (${siblings.first().state})",
            )
        }
        val pickedDeliveryId = UUID.randomUUID().toString()
        val pickedDelivery = Delivery(
            deliveryId = pickedDeliveryId,
            eventId = delivery.eventId,
            kind = delivery.kind,
            state = DeliveryState.HELD_AWAITING_LEGACY_SWEEP,
            // The §7.6 precedence rule: a B delivery keeps its
            // rawEphemeral flag — the worker re-runs the §7.3B
            // evaluation with the chosen amount at claim time (the
            // snapshot under the OLD key is unclaimable under the
            // new key, so the evaluation sees raw loss).
            rawEphemeral = delivery.rawEphemeral,
            // No legacy qkey: `legacy_qkey` is UNIQUE and the
            // superseded DISCARDED row keeps the original key (a
            // same-amount pick recomputes the SAME key, so reusing
            // it would conflict and the IGNORE insert would
            // silently drop the picked delivery). Picked deliveries
            // are Room-native; both keys live in the pick audit +
            // the picked-payload override document.
            legacyQkey = null,
            routeIds = routeIds,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            routeStamps = currentStamps,
        )
        // Step (a): pick + hold in one Room transaction. Order
        // matters: the picked delivery is inserted FIRST, then the
        // original is superseded — a failed insert aborts before
        // anything is written (a `return@run false` after a write
        // would NOT roll back; only an exception rolls back).
        // The supersede must affect exactly one row — a concurrent
        // discard between the pre-read and this transaction leaves
        // 0 rows and aborts the pick.
        val held = try {
            transactions.run {
                check(DeliveryStateMachine.canTransition(delivery.state, DeliveryState.DISCARDED))
                // IGNORE insert: -1 means the row was not created
                // (key conflict) — abort the pick, do not proceed
                // with a phantom HELD delivery.
                if (deliveryDao.insert(pickedDelivery.toEntity()) == -1L) {
                    return@run false
                }
                if (deliveryDao.setStateIfNotTerminal(deliveryId, DeliveryState.DISCARDED, nowMs) != 1) {
                    throw IllegalStateException("supersede affected 0 rows; rolling back pick")
                }
            collectorStateDao.put(
                CollectorStateEntity(
                    CollectorStateKeys.PICKED_PAYLOAD_PREFIX + pickedDeliveryId,
                    JSONObject().apply {
                        put("amount", chosenAmountMinor)
                        put("dedupe_key", newDedupeKey)
                    }.toString(),
                    nowMs,
                ),
            )
            writeAudit(
                "pick", pickedDeliveryId, nowMs,
                JSONObject().apply {
                    put("event_id", delivery.eventId)
                    put("original_delivery_id", deliveryId)
                    put("original_dedupe_key", originalDedupeKey)
                    put("chosen_amount", chosenAmountMinor)
                    put("new_dedupe_key", newDedupeKey)
                    put("route_ids", JSONArray(routeIds))
                    put("consent_epochs", JSONObject().apply {
                        for ((routeId, stamp) in currentStamps) put(routeId, stamp.consentEpoch)
                    })
                    put("operator", operator)
                    if (note != null) put("note", note)
                },
            )
            true
        }
        } catch (e: IllegalStateException) {
            // Concurrent change (or a failed supersede): the
            // transaction rolled back; nothing was picked.
            false
        }
        if (!held) {
            return PickOutcome.Refused("delivery berubah bersamaan (kemungkinan sudah di-discard)")
        }
        // Step (b): legacy sweep (holds SenderLock in production).
        val sweepOutcome = try {
            legacySweep.sweep(originalDedupeKey)
        } catch (e: Exception) {
            SweepOutcome.UNKNOWN
        }
        // Step (c): release, block, or hold.
        return finishPick(pickedDeliveryId, newDedupeKey, sweepOutcome, operator, nowMs)
    }

    private suspend fun finishPick(
        pickedDeliveryId: String,
        newDedupeKey: String,
        sweepOutcome: SweepOutcome,
        operator: String,
        nowMs: Long,
    ): PickOutcome {
        return when (sweepOutcome) {
            SweepOutcome.REMOVED, SweepOutcome.ABSENT -> {
                // Step (c): release + sweep record in ONE Room
                // transaction (crash between release and record
                // would leave the delivery PENDING with no sweep
                // record — recovery re-reads the state, not the
                // record, so the release itself is the commit).
                val released = transactions.run {
                    val current = deliveryDao.getById(pickedDeliveryId)
                    if (current == null ||
                        !DeliveryStateMachine.canTransition(current.state, DeliveryState.PENDING)
                    ) {
                        false
                    } else if (deliveryDao.setStateIfNotTerminal(
                            pickedDeliveryId, DeliveryState.PENDING, nowMs,
                        ) != 1
                    ) {
                        false
                    } else {
                        writeAudit(
                            "pick_release", pickedDeliveryId, nowMs,
                            JSONObject().apply {
                                put("sweep", sweepOutcome.name.lowercase())
                                put("operator", operator)
                            },
                        )
                        true
                    }
                }
                if (!released) return currentPickState(pickedDeliveryId, newDedupeKey)
                PickOutcome.Released(pickedDeliveryId, newDedupeKey)
            }
            SweepOutcome.DISPATCHED -> {
                transactions.run {
                    val current = deliveryDao.getById(pickedDeliveryId)
                    if (current != null &&
                        DeliveryStateMachine.canTransition(current.state, DeliveryState.BLOCKED)
                    ) {
                        deliveryDao.setStateIfNotTerminal(
                            pickedDeliveryId, DeliveryState.BLOCKED, nowMs,
                        )
                    }
                    writeAudit(
                        "pick_blocked", pickedDeliveryId, nowMs,
                        JSONObject().apply {
                            put("reason", "prior_copy_dispatched")
                            put("operator", operator)
                        },
                    )
                }
                PickOutcome.Blocked(
                    pickedDeliveryId,
                    "salinan lama sudah terkirim (prior_copy_dispatched) — rekonsiliasi dengan log server",
                )
            }
            SweepOutcome.UNKNOWN -> {
                transactions.run {
                    writeAudit(
                        "pick_held", pickedDeliveryId, nowMs,
                        JSONObject().apply {
                            put("reason", "prior_copy_unknown")
                            put("operator", operator)
                        },
                    )
                }
                PickOutcome.Held(
                    pickedDeliveryId,
                    "status salinan lama tidak diketahui (prior_copy_unknown) — ditahan sampai direkonsiliasi",
                )
            }
        }
    }

    private suspend fun currentPickState(deliveryId: String, newDedupeKey: String): PickOutcome {
        return when (deliveryDao.getById(deliveryId)?.state) {
            DeliveryState.PENDING -> PickOutcome.Released(deliveryId, newDedupeKey)
            DeliveryState.BLOCKED -> PickOutcome.Blocked(deliveryId, "delivery sudah BLOCKED")
            else -> PickOutcome.Held(deliveryId, "delivery berubah bersamaan; recovery akan menyelesaikan")
        }
    }

    /**
     * Crash recovery (§7.6): re-sweeps HELD_AWAITING_LEGACY_SWEEP
     * deliveries (a crash between pick steps (a)–(c) leaves the
     * picked delivery HELD with no release record). The sweep is
     * idempotent — a re-sweep after a REMOVED copy finds ABSENT and
     * releases. Returns (released, blocked, still held).
     */
    suspend fun recoverHeldPicks(nowMs: Long = clock()): Triple<Int, Int, Int> {
        var released = 0
        var blocked = 0
        var held = 0
        for (delivery in deliveryDao.getByState(DeliveryState.HELD_AWAITING_LEGACY_SWEEP)) {
            val event = eventDao.getById(delivery.eventId)
            if (event == null) {
                held++
                continue
            }
            val outcome = try {
                legacySweep.sweep(event.dedupeKey)
            } catch (e: Exception) {
                SweepOutcome.UNKNOWN
            }
            // The new key lives in the picked-payload override
            // document (the picked delivery carries no qkey).
            val overrideRaw = collectorStateDao.getValue(
                CollectorStateKeys.PICKED_PAYLOAD_PREFIX + delivery.deliveryId,
            )
            val newKey = overrideRaw?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optString("dedupe_key").orEmpty()
            when (finishPick(delivery.deliveryId, newKey, outcome, "recovery", nowMs)) {
                is PickOutcome.Released -> released++
                is PickOutcome.Blocked -> blocked++
                else -> held++
            }
        }
        return Triple(released, blocked, held)
    }

    // --- Amount discard (§7.6(c)) ---

    /**
     * Discards a NEEDS_REVIEW delivery: state → DISCARDED with an
     * audit record; the event row, token list, content hash, and
     * audit are retained. Discard never deletes. The outcome warns
     * when a legacy copy existed at discard time (discarding does
     * not cancel a prior legacy copy).
     */
    suspend fun discardAmount(
        deliveryId: String,
        operator: String,
        note: String? = null,
        nowMs: Long = clock(),
    ): DiscardOutcome {
        val delivery = deliveryDao.getById(deliveryId)
            ?: return DiscardOutcome.Refused("tidak ada delivery: $deliveryId")
        if (delivery.state != DeliveryState.NEEDS_REVIEW) {
            return DiscardOutcome.Refused("delivery berstatus ${delivery.state}, bukan NEEDS_REVIEW")
        }
        val event = eventDao.getById(delivery.eventId)
        val legacyCopyExisted = event != null && try {
            legacySweep.exists(event.dedupeKey)
        } catch (e: Exception) {
            false
        }
        val discarded = transactions.run {
            check(DeliveryStateMachine.canTransition(delivery.state, DeliveryState.DISCARDED))
            if (deliveryDao.setStateIfNotTerminal(deliveryId, DeliveryState.DISCARDED, nowMs) != 1) {
                return@run false
            }
            writeAudit(
                "discard", deliveryId, nowMs,
                JSONObject().apply {
                    put("event_id", delivery.eventId)
                    if (event != null) put("original_dedupe_key", event.dedupeKey)
                    put("legacy_copy_existed", legacyCopyExisted)
                    put("operator", operator)
                    if (note != null) put("note", note)
                },
            )
            true
        }
        if (!discarded) {
            return DiscardOutcome.Refused("delivery berubah bersamaan")
        }
        return DiscardOutcome.Discarded(legacyCopyExisted)
    }

    // --- RAW_UNAVAILABLE authorise-A′ / discard (§7.3B) ---

    /**
     * The §7.3B hard stops for one RAW_UNAVAILABLE delivery, as
     * user-facing reasons (empty = authorisable). No authorise-A′
     * action is offered for hard-stop cases — the UI hides the
     * button and shows the reasons instead.
     */
    suspend fun hardStopReasons(delivery: DeliveryEntity, payload: JSONObject?): List<String> {
        val reasons = mutableListOf<String>()
        val amountMinor = payload?.let { if (it.has("amount")) it.optLong("amount") else null }
        val amountAmbiguous = payload?.optBoolean("amount_ambiguous", false) == true
        if (amountMinor == null || amountAmbiguous) {
            reasons += "jumlah tidak dapat diandalkan (ambigu / tidak ada)"
        }
        if (storedDirection(payload) == Direction.UNKNOWN) {
            reasons += "arah transaksi tidak diketahui"
        }
        val routeIds = Route.jsonStrings(JSONArray(delivery.routeIdsJson))
        for (routeId in routeIds) {
            val route = routes.routeById(routeId) ?: continue
            if (route.url.contains("catat.php") && !recorderGatePassed) {
                reasons += "rute perekam ${route.name}: gerbang B14 belum lolos"
            }
            val consent = consentStore?.get(routeId)
            if (consent == null || !consent.granted) {
                reasons += "persetujuan rute ${route.name} dicabut / belum diberikan"
            }
        }
        if (consentStore == null) {
            reasons += "penyimpanan persetujuan tidak tersedia (fail-closed)"
        }
        return reasons
    }

    /**
     * Explicit operator authorisation of an A′ dispatch for a
     * RAW_UNAVAILABLE delivery (§7.3B scope rule (b)): the hard
     * stops are re-checked (amount, direction, endpoint, consent);
     * criterion 5 (prior POST reconciliation) is the operator's
     * explicit responsibility — the UI discloses "check the server
     * log before confirming" and the audit records
     * `operator_verified_no_prior_post`. A failed hard stop refuses
     * with no state change.
     */
    suspend fun authorizeFallback(
        deliveryId: String,
        operator: String,
        note: String? = null,
        nowMs: Long = clock(),
    ): AuthorizeOutcome {
        val delivery = deliveryDao.getById(deliveryId)
            ?: return AuthorizeOutcome.Refused("tidak ada delivery: $deliveryId")
        if (delivery.state != DeliveryState.RAW_UNAVAILABLE) {
            return AuthorizeOutcome.Refused(
                "delivery berstatus ${delivery.state}, bukan RAW_UNAVAILABLE",
            )
        }
        val event = eventDao.getById(delivery.eventId)
        val payload = parsePayload(event?.legacyPayloadJson)
        val hardStops = hardStopReasons(delivery, payload)
        if (hardStops.isNotEmpty()) {
            return AuthorizeOutcome.Refused(
                "hard stop §7.3B: " + hardStops.joinToString("; "),
            )
        }
        val authorized = transactions.run {
            check(DeliveryStateMachine.canTransition(delivery.state, DeliveryState.PENDING))
            if (deliveryDao.setStateIfNotTerminal(deliveryId, DeliveryState.PENDING, nowMs) != 1) {
                return@run false
            }
            writeAudit(
                "authorize_fallback", deliveryId, nowMs,
                JSONObject().apply {
                    put("event_id", delivery.eventId)
                    put("operator_verified_no_prior_post", true)
                    put("operator", operator)
                    if (note != null) put("note", note)
                },
            )
            true
        }
        if (!authorized) {
            return AuthorizeOutcome.Refused("delivery berubah bersamaan")
        }
        return AuthorizeOutcome.Authorized
    }

    /**
     * Discards a RAW_UNAVAILABLE delivery: local disposition only,
     * never proof of non-settlement. The audit retains the
     * unresolved server-effect evidence (amount, dedupe_key,
     * package, error); the event row is retained.
     */
    suspend fun discardRawLost(
        deliveryId: String,
        operator: String,
        note: String? = null,
        nowMs: Long = clock(),
    ): DiscardOutcome {
        val delivery = deliveryDao.getById(deliveryId)
            ?: return DiscardOutcome.Refused("tidak ada delivery: $deliveryId")
        if (delivery.state != DeliveryState.RAW_UNAVAILABLE) {
            return DiscardOutcome.Refused(
                "delivery berstatus ${delivery.state}, bukan RAW_UNAVAILABLE",
            )
        }
        val event = eventDao.getById(delivery.eventId)
        val payload = parsePayload(event?.legacyPayloadJson)
        val discarded = transactions.run {
            check(DeliveryStateMachine.canTransition(delivery.state, DeliveryState.DISCARDED))
            if (deliveryDao.setStateIfNotTerminal(deliveryId, DeliveryState.DISCARDED, nowMs) != 1) {
                return@run false
            }
            writeAudit(
                "discard_raw", deliveryId, nowMs,
                JSONObject().apply {
                    put("event_id", delivery.eventId)
                    if (event != null) {
                        put("original_dedupe_key", event.dedupeKey)
                        put("source_package", event.sourcePackage)
                    }
                    if (payload != null && payload.has("amount")) put("amount", payload.optLong("amount"))
                    put("last_error_code", delivery.lastErrorCode)
                    put("local_disposition_only", true)
                    put("operator", operator)
                    if (note != null) put("note", note)
                },
            )
            true
        }
        if (!discarded) {
            return DiscardOutcome.Refused("delivery berubah bersamaan")
        }
        return DiscardOutcome.Discarded(legacyCopyExisted = false)
    }

    // --- P3 manual attribution pick (D5) ---

    /**
     * Resolves a P3 review row by operator decision.
     *
     * - `attribute = true`: the operator examined the evidence and
     *   confirms the match, so the pick is verified same-bank
     *   evidence (`attributeExisting(verified = true)`). Without a
     *   merchant/order pair the pick is refused — attribution
     *   requires both.
     * - `attribute = false`: the row is marked `unmatched` with the
     *   operator's note; the event itself is untouched.
     *
     * Advisory only: the decision is recorded in `match_results`
     * + a review-audit document, never transmitted, never written
     * to `deliveries.last_match_status` (§3.1).
     */
    suspend fun resolveMatch(
        matchId: String,
        attribute: Boolean,
        operator: String,
        nowMs: Long = clock(),
    ): MatchResolveOutcome {
        val dao = matchResultDao
            ?: return MatchResolveOutcome.Refused("match store tidak tersedia")
        val row = dao.getById(matchId)
            ?: return MatchResolveOutcome.Refused("baris tidak ditemukan")
        if (row.advisoryMatchStatus != AdvisoryMatchStatus.AMBIGUOUS &&
            row.advisoryMatchStatus != AdvisoryMatchStatus.MANUAL_REVIEW
        ) {
            return MatchResolveOutcome.Refused(
                "baris berstatus ${row.advisoryMatchStatus}, bukan menunggu review",
            )
        }
        if (attribute) {
            if (row.merchantId.isNullOrBlank() || row.orderNumber.isNullOrBlank()) {
                return MatchResolveOutcome.Refused(
                    "tidak ada nomor pesanan — tidak dapat diatribusikan",
                )
            }
            if (row.attributionStatus == AttributionStatus.ATTRIBUTED) {
                return MatchResolveOutcome.Refused("sudah diatribusikan")
            }
            val note = "operator $operator: diatribusikan manual"
            val outcome = dao.attributeExisting(row, verifiedSameBankTransaction = true, note)
            transactions.run {
                writeAudit(
                    "match_attribute", matchId, nowMs,
                    JSONObject().apply {
                        put("event_id", row.eventId)
                        put("merchant_id", row.merchantId)
                        put("order_number", row.orderNumber)
                        put("outcome", outcome.name)
                        put("operator", operator)
                    },
                )
            }
            return when (outcome) {
                AttributionOutcome.ATTRIBUTED -> MatchResolveOutcome.Attributed(matchId)
                AttributionOutcome.DUPLICATE_EVIDENCE ->
                    MatchResolveOutcome.DuplicateEvidence(matchId)
                AttributionOutcome.AMBIGUOUS -> MatchResolveOutcome.StillAmbiguous(matchId)
            }
        }
        val updated = transactions.run {
            val rows = dao.updateAdvisory(
                matchId,
                AdvisoryMatchStatus.UNMATCHED,
                row.ruleId,
                "operator $operator: bukan transaksi ini",
            )
            if (rows != 1) return@run 0
            writeAudit(
                "match_unmatched", matchId, nowMs,
                JSONObject().apply {
                    put("event_id", row.eventId)
                    put("merchant_id", row.merchantId)
                    put("order_number", row.orderNumber)
                    put("operator", operator)
                },
            )
            rows
        }
        if (updated != 1) {
            return MatchResolveOutcome.Refused("baris berubah bersamaan")
        }
        return MatchResolveOutcome.MarkedUnmatched(matchId)
    }

    // --- Quarantine approve / discard (§7.7, blocker C) ---

    /**
     * Approves a quarantined item — only where the privacy rule
     * allows. `pure_auth_no_financial_record`: no financial record
     * exists, so approval resolves the record with no import and
     * deletes the raw queue item. `ambiguous_span` (or `unknown`):
     * the item is re-gated — the redacted form is imported as a
     * PENDING delivery and the raw queue item removed; if the
     * re-gate finds pure auth after all, the record resolves with
     * no import. The raw text is handled in-memory only, never
     * logged, never persisted except through the gate output.
     */
    suspend fun approveQuarantine(
        dedupeKey: String,
        operator: String,
        note: String? = null,
        nowMs: Long = clock(),
    ): QuarantineOutcome {
        val record = quarantineStore.get(dedupeKey)
            ?: return QuarantineOutcome.NotFound(dedupeKey)
        if (record.resolution != null) {
            return QuarantineOutcome.AlreadyResolved(record.resolution)
        }
        if (record.reason == LegacyQueuePrivacyGate.REASON_PURE_AUTH) {
            quarantineQueue.removeByQkey(record.qkey)
            quarantineStore.resolve(
                dedupeKey, QuarantineRecord.RESOLUTION_APPROVED, operator, note, nowMs,
            )
            transactions.run {
                writeAudit(
                    "quarantine_approve", dedupeKey, nowMs,
                    JSONObject().apply {
                        put("qkey", record.qkey)
                        put("reason", record.reason)
                        put("imported", false)
                        put("operator", operator)
                        if (note != null) put("note", note)
                    },
                )
            }
            return QuarantineOutcome.Approved(imported = false)
        }
        // ambiguous_span / unknown: re-gate the raw item, import the redacted form.
        val item = quarantineQueue.findByDedupeKey(dedupeKey)
            ?: return QuarantineOutcome.Refused(
                "item sudah tidak ada di antrean legacy — tidak dapat diimpor; discard bila yakin",
            )
        val payload = item.optJSONObject("payload")
            ?: return QuarantineOutcome.Refused("payload item rusak")
        val amount = if (payload.has("amount")) payload.optLong("amount") else null
        val snapshot = NotificationSnapshot(
            key = "",
            packageName = record.sourcePackage ?: "",
            postTimeMs = postTimeFromDedupe(dedupeKey) ?: nowMs,
            flags = 0,
            title = payload.optString("title"),
            text = payload.optString("text"),
            bigText = "",
            capturedAtMs = nowMs,
        )
        val gate = privacyGate.gate(snapshot, amount)
        when (gate.decision) {
            PrivacyGate.Decision.DROP_PURE_AUTH -> {
                quarantineQueue.removeByQkey(record.qkey)
                quarantineStore.resolve(
                    dedupeKey, QuarantineRecord.RESOLUTION_APPROVED, operator, note, nowMs,
                )
                transactions.run {
                    writeAudit(
                        "quarantine_approve", dedupeKey, nowMs,
                        JSONObject().apply {
                            put("qkey", record.qkey)
                            put("reason", record.reason)
                            put("imported", false)
                            put("regate", "pure_auth")
                            put("operator", operator)
                            if (note != null) put("note", note)
                        },
                    )
                }
                return QuarantineOutcome.Approved(imported = false)
            }
            PrivacyGate.Decision.DROP_NO_AMOUNT -> {
                return QuarantineOutcome.Refused(
                    "tidak ada jumlah yang dapat diandalkan — tidak dapat diimpor; discard bila yakin",
                )
            }
            PrivacyGate.Decision.ALLOW -> Unit
        }
        val imported = importRedacted(item, record, gate, amount ?: 0L, nowMs)
            ?: return QuarantineOutcome.Refused(
                "impor gagal (event bentrok / rute tidak valid) — record tetap belum diputuskan",
            )
        quarantineQueue.removeByQkey(record.qkey)
        quarantineStore.resolve(
            dedupeKey, QuarantineRecord.RESOLUTION_APPROVED, operator, note, nowMs,
        )
        transactions.run {
            writeAudit(
                "quarantine_approve", dedupeKey, nowMs,
                JSONObject().apply {
                    put("qkey", record.qkey)
                    put("reason", record.reason)
                    put("imported", true)
                    put("delivery_id", imported)
                    put("operator", operator)
                    if (note != null) put("note", note)
                },
            )
        }
        return QuarantineOutcome.Approved(imported = true)
    }

    /**
     * Imports the re-gated (redacted) form of a quarantined item as
     * a PENDING delivery. Returns the new delivery id, or null when
     * the import cannot proceed (event collision, invalid routes).
     * The event row carries the redacted payload; the content hash
     * is over the raw combined text (hash only, never displayed).
     */
    private suspend fun importRedacted(
        item: JSONObject,
        record: QuarantineRecord,
        gate: PrivacyGate.GateResult,
        amount: Long,
        nowMs: Long,
    ): String? {
        val payload = item.optJSONObject("payload") ?: return null
        val routeIds = when {
            item.has("route_ids") -> Route.jsonStrings(item.optJSONArray("route_ids"))
            item.optString("route_id").isNotEmpty() -> listOf(item.optString("route_id"))
            else -> return null
        }
        if (routeIds.isEmpty()) return null
        val stamps = mutableMapOf<String, RouteStamp>()
        for (routeId in routeIds) {
            val route = routes.routeById(routeId) ?: return null
            if (!route.enabled) return null
            val consent = consentStore?.get(routeId)
            if (consent == null || !consent.granted) return null
            stamps[routeId] = RouteStamp(
                consentEpoch = consent.epoch,
                configEpoch = 0,
                urlSha256 = CaptureCoordinator.sha256Hex(route.url),
            )
        }
        val rawCombined = payload.optString("title") + "\n" + payload.optString("text")
        val contentSha = CaptureCoordinator.sha256Hex(rawCombined)
        val existing = eventDao.getByDedupeKey(record.dedupeKey)
        if (existing != null && existing.contentSha256 != contentSha) {
            return null // Collision: same key, different content — operator reconciles.
        }
        val postTime = postTimeFromDedupe(record.dedupeKey) ?: nowMs
        val redactedPayload = JSONObject().apply {
            put("type", "payment")
            put("device", routes.deviceLabel())
            put("pkg", record.sourcePackage)
            put("amount", amount)
            put("title", gate.redactedTitle.take(500))
            put("text", gate.redactedText.take(500))
            put("notif_time", java.time.Instant.ofEpochMilli(postTime).toString())
            put("dedupe_key", record.dedupeKey)
            put("direction", storedDirection(payload).name.lowercase())
            if (gate.redacted) put("redacted", true)
            if (gate.inseparable) put("inseparable", true)
        }
        val eventId = existing?.eventId ?: UUID.randomUUID().toString()
        val deliveryId = UUID.randomUUID().toString()
        var inserted = false
        transactions.run {
            // A live delivery for this event already exists (e.g. a
            // concurrent import): no second delivery, no new event.
            if (existing != null) {
                val live = deliveryDao.getByEventId(eventId)
                    .any { !DeliveryStateMachine.isTerminal(it.state) }
                if (live) return@run
            }
            if (existing == null) {
                val row = eventDao.insert(
                    EventEntity(
                        eventId = eventId,
                        sourceType = "android_notification",
                        sourceEventId = record.dedupeKey,
                        deviceId = routes.deviceLabel(),
                        eventType = "payment.notification",
                        category = "payment",
                        sourcePackage = record.sourcePackage,
                        occurredAtMs = postTime,
                        capturedAtMs = nowMs,
                        dataJson = """{"amount":$amount,"currency":"IDR"}""",
                        metadataJson = """{"parser_version":"payment-v1","privacy_mode":"structured"}""",
                        legacyPayloadJson = redactedPayload.toString(),
                        privacyMode = "structured",
                        retentionUntilMs = null,
                        dedupeKey = record.dedupeKey,
                        contentSha256 = contentSha,
                        exportRef = UUID.randomUUID().toString(),
                        sourceAccountKey = null,
                    ),
                )
                if (row == -1L) return@run
            }
            val delivery = Delivery(
                deliveryId = deliveryId,
                eventId = eventId,
                kind = DeliveryKind.LEGACY_CHAIN,
                state = DeliveryState.PENDING,
                rawEphemeral = false,
                legacyQkey = record.qkey,
                routeIds = routeIds,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
                routeStamps = stamps,
            )
            inserted = deliveryDao.insert(delivery.toEntity()) != -1L
        }
        return if (inserted) deliveryId else null
    }

    /**
     * Discards a quarantined item (§7.7): deletes the raw payload
     * (the queue item) but retains the financial-event audit record
     * in Room — the resolved QuarantineRecord (amount, dedupe_key,
     * package, timestamps, span classes, decision, operator, reason;
     * never raw spans) plus the review-audit document. Local
     * disposition only, never proof of non-settlement.
     */
    suspend fun discardQuarantine(
        dedupeKey: String,
        operator: String,
        note: String? = null,
        nowMs: Long = clock(),
    ): QuarantineOutcome {
        val record = quarantineStore.get(dedupeKey)
            ?: return QuarantineOutcome.NotFound(dedupeKey)
        if (record.resolution != null) {
            return QuarantineOutcome.AlreadyResolved(record.resolution)
        }
        quarantineQueue.removeByQkey(record.qkey)
        quarantineStore.resolve(
            dedupeKey, QuarantineRecord.RESOLUTION_DISCARDED, operator, note, nowMs,
        )
        transactions.run {
            writeAudit(
                "quarantine_discard", dedupeKey, nowMs,
                JSONObject().apply {
                    put("qkey", record.qkey)
                    put("reason", record.reason)
                    put("span_classes", record.spanClasses)
                    if (record.amount != null) put("amount", record.amount)
                    put("source_package", record.sourcePackage)
                    put("local_disposition_only", true)
                    put("operator", operator)
                    if (note != null) put("note", note)
                },
            )
        }
        return QuarantineOutcome.Discarded
    }

    // --- Helpers ---

    private fun parsePayload(json: String?): JSONObject? {
        if (json.isNullOrEmpty()) return null
        return runCatching { JSONObject(json) }.getOrNull()
    }

    private fun storedDirection(payload: JSONObject?): Direction {
        return when (payload?.optString("direction")?.lowercase()) {
            "in" -> Direction.IN
            "out" -> Direction.OUT
            else -> Direction.UNKNOWN
        }
    }

    private fun postTimeFromDedupe(dedupeKey: String): Long? {
        val parts = dedupeKey.split("|")
        if (parts.size < 2) return null
        return parts[1].toLongOrNull()
    }

    /**
     * Appends one review-audit document. Must be called inside a
     * [TransactionRunner] block (atomic with the transition it
     * records). Keys are unique per decision (action + id + time +
     * random suffix); documents are append-only.
     */
    private suspend fun writeAudit(
        action: String,
        refId: String,
        nowMs: Long,
        details: JSONObject,
    ) {
        val key = CollectorStateKeys.REVIEW_AUDIT_PREFIX + action + ":" + refId + ":" +
            nowMs + ":" + UUID.randomUUID().toString().take(8)
        details.put("action", action)
        details.put("at_ms", nowMs)
        collectorStateDao.put(CollectorStateEntity(key, details.toString(), nowMs))
    }

    companion object {
        /**
         * Recomputes the `dedupe_key` for a picked amount (§7.6):
         * the v2.3 key is `pkg|postTime|amount|hash` — only the
         * amount segment changes, so the recomputation replaces
         * that segment of the ORIGINAL key (the combined text is
         * not stored durably, so the key cannot be rebuilt from
         * parts). Returns null when the original key does not
         * parse — the pick is refused rather than guessed.
         */
        fun recomputeDedupeKey(originalDedupeKey: String, chosenAmountMinor: Long): String? {
            val parts = originalDedupeKey.split("|")
            if (parts.size != 4) return null
            if (parts[1].toLongOrNull() == null) return null
            if (parts[2].toLongOrNull() == null) return null
            return listOf(parts[0], parts[1], chosenAmountMinor.toString(), parts[3])
                .joinToString("|")
        }
    }
}
