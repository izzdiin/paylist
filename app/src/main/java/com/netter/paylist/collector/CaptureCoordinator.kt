package com.netter.paylist.collector

import com.netter.paylist.Route
import com.netter.paylist.core.model.CanonicalEvent
import com.netter.paylist.core.model.Delivery
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.core.model.EventData
import com.netter.paylist.core.model.EventMetadata
import com.netter.paylist.core.model.EventSchema
import com.netter.paylist.core.model.EventSource
import com.netter.paylist.core.model.RouteStamp
import com.netter.paylist.data.EventInsertOutcome
import com.netter.paylist.data.EventRepository
import com.netter.paylist.data.dao.CaptureLogDao
import com.netter.paylist.data.entity.CaptureLogEntity
import com.netter.paylist.data.entity.CaptureOutcome
import com.netter.paylist.data.entity.CaptureStagingEntity
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.migration.LegacyEnqueue
import com.netter.paylist.migration.LegacyQkey
import com.netter.paylist.parser.ParserInput
import com.netter.paylist.parser.ParserRegistry
import com.netter.paylist.parser.PaymentParser
import com.netter.paylist.security.SensitiveDataRedactor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Where the capture log / local log lines go (production: `Store`). */
fun interface CaptureLogger {
    fun logEvent(pkg: String, text: String)
}

/** Extended logger: per-package events plus app-level lines. */
interface CaptureLogSink : CaptureLogger {
    fun logLocal(text: String)
}

/** Durable capture counters (design §8.3 "Measured, not assumed"). */
data class CaptureCounters(
    val accepted: Long = 0,
    val staged: Long = 0,
    val enqueued: Long = 0,
    val parked: Long = 0,
    val droppedOverflow: Long = 0,
    val droppedUnclassified: Long = 0,
    val identityCollision: Long = 0,
    val duplicates: Long = 0,
    val roomCapturesDuringDraining: Long = 0,
    val unlistedPackage: Long = 0,
    val noKeyword: Long = 0,
    val noAmount: Long = 0,
    val sweptOrphaned: Long = 0,
    /** Staging rows held on `payload_sha256` mismatch (F18), never dispatched. */
    val stagingMismatch: Long = 0,
    /** Pure-auth notifications dropped at capture (§7.1(a), §7.3A(j)). */
    val pureAuth: Long = 0,
    /** Raw snapshots evicted from the store (overflow; §7.3B at claim). */
    val rawSnapshotsEvicted: Long = 0,
    /** Capture-log inserts that failed (LH-04(d) — surfaced, never silent). */
    val captureLogFailures: Long = 0,
)

/**
 * The capture executor owner (design §8.3 C21, Stage D).
 *
 * The listener callback never acquires a lock and never touches a
 * store: it builds a [NotificationSnapshot] and hands it to
 * [capture], which offers it to a dedicated single-thread executor
 * and returns immediately. On the executor thread, in order:
 *
 * 1. Route/keyword filtering (v2.3 rules, [SourcePolicy]).
 * 2. Amount parsing ([PaymentParser] via [ParserRegistry]).
 * 3. PrivacyGate classification in memory; the post-gate snapshot
 *    is staged ([EventRepository.stageCapture] — event + staging
 *    rows in one Room transaction). No raw secret ever reaches
 *    disk: the staging writer re-runs the class-(a) span scan
 *    before writing (staging gate, §8.3 R11).
 * 4. The mode is read and the snapshot is enqueued to exactly one
 *    store: the legacy queue while `LEGACY_ONLY` (synchronous
 *    `saveQueueSync` + readback, then the staging row is deleted),
 *    the Room outbox once `DRAINING` is committed (outbox insert +
 *    staging delete in one Room transaction).
 *
 * A failed enqueue never drops the capture: the staging row stays
 * and [sweepOrphanedStaging] re-promotes it through the same
 * mode gate (idempotent by `dedupe_key` / `legacy_qkey`).
 *
 * Loss window (design §8.3, stated honestly): everything before
 * the staging write — the callback-to-executor handoff — can be
 * lost on process death and is directly unmeasurable; durable
 * staging narrows the window to "until the staging write
 * completes". Unrecovered-before-enqueue loss has no counter by
 * construction.
 *
 * Lock order (design §8.3 R12–R14): this path acquires
 * `Sender.LOCK` only (inside the legacy enqueue), never the
 * Cutover lock; Room transactions are leaves. No lock is held
 * across a network call here.
 */
class CaptureCoordinator(
    private val repository: EventRepository,
    private val modeReader: suspend () -> CutoverMode,
    private val legacyEnqueue: LegacyEnqueue,
    private val routesProvider: () -> List<Route>,
    private val deviceIdProvider: () -> String,
    private val logger: CaptureLogSink,
    private val parser: ParserRegistry = ParserRegistry(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Called after a Room enqueue (production: request a sync run). */
    private val onRoomEnqueued: () -> Unit = {},
    /** Stage D D2: the PrivacyGate (C4 four-class rule + staging re-scan gate). */
    private val privacyGate: PrivacyGate = PrivacyGate(),
    /** Stage D D2: the §7.3A raw-snapshot store (null = B disabled, A′ only). */
    private val rawSnapshotStore: RawSnapshotStore? = null,
    /** Stage D D2: per-route consent records (null = no consent evidence). */
    private val consentStore: ConsentStore? = null,
    /**
     * Stage D D4: the durable capture log (LH-04). Null disables
     * capture-log writes (tests that predate D4); production always
     * wires the real DAO. A failed insert is counted + logged, never
     * silent (LH-04(d)) — the capture itself continues.
     */
    private val captureLogDao: CaptureLogDao? = null,
) {
    companion object {
        /** Executor work-queue capacity (design §8.3 (6′), proposed value). */
        const val EXECUTOR_CAPACITY: Int = 500

        /** Periodic staging-sweep interval (matches the 15-minute periodic work). */
        const val SWEEP_INTERVAL_MINUTES: Long = 15

        /** The legacy `dedupe_key` formula (v2.3, C3 — unchanged). */
        fun legacyDedupeKey(pkg: String, postTimeMs: Long, amount: Long, combined: String): String =
            "$pkg|$postTimeMs|$amount|${combined.take(40).hashCode()}"

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }

    private val accepted = AtomicLong()
    private val staged = AtomicLong()
    private val enqueued = AtomicLong()
    private val parked = AtomicLong()
    private val droppedOverflow = AtomicLong()
    private val droppedUnclassified = AtomicLong()
    private val identityCollision = AtomicLong()
    private val duplicates = AtomicLong()
    private val roomCapturesDuringDraining = AtomicLong()
    private val unlistedPackage = AtomicLong()
    private val noKeyword = AtomicLong()
    private val noAmount = AtomicLong()
    private val sweptOrphaned = AtomicLong()
    private val stagingMismatch = AtomicLong()
    private val pureAuth = AtomicLong()
    private val rawSnapshotsEvicted = AtomicLong()
    private val captureLogFailures = AtomicLong()

    private fun daemonFactory(name: String): ThreadFactory = ThreadFactory { r ->
        Thread(r, name).apply { isDaemon = true }
    }

    /**
     * Single-thread capture executor with a bounded queue. Overflow
     * policy (§8.3 (6′)): drop the OLDEST entry, count
     * `dropped_overflow`, never block the callback thread, never
     * grow unboundedly.
     */
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(EXECUTOR_CAPACITY),
        daemonFactory("paylist-capture"),
        RejectedExecutionHandler { runnable, exec ->
            // Drop the oldest queued snapshot to make room.
            if (exec.queue.poll() != null) droppedOverflow.incrementAndGet()
            try {
                exec.execute(runnable)
            } catch (_: Exception) {
                // Extreme race (queue refilled between poll and
                // execute): drop the NEW snapshot rather than block
                // the callback thread.
                droppedOverflow.incrementAndGet()
            }
        },
    )

    /** Timer for the periodic staging sweep (submits onto [executor]). */
    private val sweepTimer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(daemonFactory("paylist-sweep-timer"))

    /** Current counter values (for diagnostics and tests). */
    fun counters(): CaptureCounters = CaptureCounters(
        accepted = accepted.get(),
        staged = staged.get(),
        enqueued = enqueued.get(),
        parked = parked.get(),
        droppedOverflow = droppedOverflow.get(),
        droppedUnclassified = droppedUnclassified.get(),
        identityCollision = identityCollision.get(),
        duplicates = duplicates.get(),
        roomCapturesDuringDraining = roomCapturesDuringDraining.get(),
        unlistedPackage = unlistedPackage.get(),
        noKeyword = noKeyword.get(),
        noAmount = noAmount.get(),
        sweptOrphaned = sweptOrphaned.get(),
        stagingMismatch = stagingMismatch.get(),
        pureAuth = pureAuth.get(),
        rawSnapshotsEvicted = rawSnapshotsEvicted.get(),
        captureLogFailures = captureLogFailures.get(),
    )

    /**
     * Hands a snapshot to the capture executor. Non-blocking: never
     * acquires a lock, never touches a store, never blocks the
     * callback thread (§8.3 (1′)).
     */
    fun capture(snapshot: NotificationSnapshot) {
        accepted.incrementAndGet()
        executor.execute { runBlocking { processSnapshot(snapshot) } }
    }

    /**
     * Runs one sweep pass on the executor thread (for tests and for
     * the periodic timer). Blocks until the pass finishes.
     */
    fun sweepNow() {
        val future = executor.submit<Unit> { runBlocking { sweepOrphanedStaging() } }
        future.get()
    }

    /** Starts the periodic staging sweep (every [SWEEP_INTERVAL_MINUTES]). */
    fun startPeriodicSweep() {
        sweepTimer.scheduleAtFixedRate(
            { executor.execute { runBlocking { sweepOrphanedStaging() } } },
            SWEEP_INTERVAL_MINUTES, SWEEP_INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
    }

    /** Stops the executor and the sweep timer (tests / shutdown). */
    fun close() {
        sweepTimer.shutdownNow()
        executor.shutdownNow()
    }

    // --- Capture path (executor thread) ---

    internal suspend fun processSnapshot(snapshot: NotificationSnapshot) {
        if (snapshot.isGroupSummary) return
        val pkg = snapshot.packageName
        val routes = SourcePolicy.routesForPackage(routesProvider(), pkg)
        if (routes.isEmpty()) {
            unlistedPackage.incrementAndGet()
            logger.logEvent(pkg, "paket-terlihat: $pkg (belum di daftar rute)")
            // LH-04(b): package + time + outcome only — no text, no amount.
            writeCaptureLog(
                snapshot, CaptureOutcome.UNLISTED_PACKAGE,
                titleSnippet = null, amountMinor = null,
                eventId = null, contentSha256 = null,
            )
            return
        }
        val matched = SourcePolicy.keywordMatchedRoutes(routes, snapshot.combined)
        if (matched.isEmpty()) {
            noKeyword.incrementAndGet()
            logger.logEvent(pkg, "target-lain: $pkg - ${snapshot.title.take(40)}")
            // LH-04(a): the gate never ran here, so the snippet is
            // scanned directly (confirmed + ambiguous spans redacted).
            writeCaptureLog(
                snapshot, CaptureOutcome.NO_KEYWORD,
                titleSnippet = snippet(snapshot.title),
                amountMinor = null, eventId = null, contentSha256 = null,
            )
            return
        }
        val input = ParserInput(pkg, snapshot.title, snapshot.text, snapshot.bigText, snapshot.postTimeMs)
        val parsed = parser.parse(input)
        val now = clock()
        if (parsed == null) {
            // No parseable amount: PrivacyGate distinguishes a
            // pure-auth notification (confirmed auth span, no
            // financial record — dropped, §7.1(a)) from a plain
            // no-amount notification.
            val gate = privacyGate.gate(snapshot, null)
            if (gate.decision == PrivacyGate.Decision.DROP_PURE_AUTH) {
                pureAuth.incrementAndGet()
                logger.logEvent(pkg, "pure-auth: $pkg (kode autentikasi, tanpa nominal)")
                // LH-07(b) + LH-D2 (accepted): metadata-only row —
                // package, time, outcome. No snippet, no amount, no
                // hash: a hash of an OTP notification would still be
                // a hash of secret content.
                writeCaptureLog(
                    snapshot, CaptureOutcome.PURE_AUTH,
                    titleSnippet = null, amountMinor = null,
                    eventId = null, contentSha256 = null,
                )
            } else {
                noAmount.incrementAndGet()
                logger.logEvent(pkg, "target-tanpa-nominal: $pkg - ${snapshot.title.take(40)}")
                writeCaptureLog(
                    snapshot, CaptureOutcome.NO_AMOUNT,
                    titleSnippet = gate.redactedTitle.take(40),
                    amountMinor = null, eventId = null, contentSha256 = null,
                )
            }
            return
        }
        val amount = parsed.amount ?: return
        // C3: identity from PRE-redaction transient input, identically
        // on the live and rescan paths; never recomputed from storage.
        val dedupeKey = legacyDedupeKey(pkg, snapshot.postTimeMs, amount, snapshot.combined)
        val contentSha = sha256Hex(snapshot.combined)
        // PrivacyGate (§7.1 C4 + §8.3 R11 re-scan gate): redaction
        // before ANY durable write. The gate result's redacted
        // fields are the only text that ever reaches storage.
        val gate = privacyGate.gate(snapshot, amount)
        val routeIds = matched.map { it.id }
        // §7.3A: the B-eligible routes for this capture (mixed
        // financial+OTP event + valid P6 opt-in). Computed before
        // the payload so the legacy queue item carries the
        // `raw_ephemeral` flag the flush needs to take the snapshot
        // at its per-hop claim step (§7.3A(d)).
        val bRoutes = if (rawSnapshotStore != null && gate.hasConfirmedSecret) {
            matched.filter { privacyGate.isBEligible(it.id, snapshot, amount, now) }
        } else {
            emptyList()
        }
        val payload = buildPayload(
            snapshot = snapshot,
            amount = amount,
            dedupeKey = dedupeKey,
            redactedTitle = gate.redactedTitle,
            redactedText = gate.redactedText,
            amountAmbiguous = parsed.amountAmbiguous,
            tokens = parsed.tokens,
            redacted = gate.redacted,
            inseparable = gate.inseparable,
            rawEphemeral = bRoutes.isNotEmpty(),
        )
        val event = CanonicalEvent(
            eventId = UUID.randomUUID().toString(),
            sourceEventId = "$pkg|${snapshot.postTimeMs}",
            type = EventSchema.TYPE_PAYMENT_NOTIFICATION,
            category = EventSchema.CATEGORY_PAYMENT,
            source = EventSource(
                EventSchema.SOURCE_TYPE_ANDROID_NOTIFICATION,
                deviceIdProvider(),
                pkg,
            ),
            occurredAtMs = snapshot.postTimeMs,
            capturedAtMs = now,
            data = EventData(amount, parsed.currency),
            metadata = EventMetadata(PaymentParser.PARSER_VERSION, "structured"),
            dedupeKey = dedupeKey,
            contentSha256 = contentSha,
            exportRef = UUID.randomUUID().toString(),
            sourceAccountKey = null,
            legacyPayloadJson = payload.toString(),
            retentionUntilMs = null,
        )
        val qkey = LegacyQkey.build(dedupeKey, LegacyQkey.KIND_CHAIN, routeIds)
        // Per-route stamps (§7.5): the consent epoch current at
        // capture + the URL hash. Config epoch has no live source
        // in Stage D (route config lives in SharedPreferences) —
        // stamped 0 until the D5 route editor tracks it.
        val stamps = matched.associate { route ->
            val epoch = consentStore?.epoch(route.id) ?: -1L
            route.id to RouteStamp(
                consentEpoch = epoch,
                configEpoch = 0,
                urlSha256 = sha256Hex(route.url),
            )
        }
        // §7.3A: retain a raw snapshot per B-eligible route (mixed
        // financial+OTP event + valid P6 opt-in at capture). The
        // snapshot is in-memory only, keyed by (dedupe_key,
        // route_id, consent epoch). An evicted snapshot is counted
        // and logged; its delivery is evaluated by the §7.3B gate
        // at claim time (the eviction itself never dispatches).
        var rawEphemeral = false
        val store = rawSnapshotStore
        if (store != null && gate.hasConfirmedSecret) {
            for (route in bRoutes) {
                val epoch = stamps[route.id]?.consentEpoch ?: -1L
                val evicted = store.put(
                    RawSnapshotStore.RawSnapshot(
                        dedupeKey = dedupeKey,
                        routeId = route.id,
                        consentEpoch = epoch,
                        title = snapshot.title.take(RawSnapshotStore.MAX_TEXT_CHARS),
                        text = snapshot.text.ifEmpty { snapshot.bigText }
                            .take(RawSnapshotStore.MAX_TEXT_CHARS),
                        capturedAtMs = now,
                    ),
                )
                if (evicted != null) {
                    rawSnapshotsEvicted.incrementAndGet()
                    logger.logLocal("snapshot raw diusir (kapasitas): ${evicted.dedupeKey.take(24)}…")
                }
                rawEphemeral = true
            }
        }
        val stagingRow = CaptureStagingEntity(
            legacyQkey = qkey,
            dedupeKey = dedupeKey,
            contentSha256 = contentSha,
            payloadSha256 = sha256Hex(payload.toString()),
            // The staging row carries one epoch: the first matched
            // route's (per-route epochs live on the delivery's
            // routeStampsJson; the sweep's gate reads those).
            consentEpoch = stamps.values.firstOrNull()?.consentEpoch ?: -1L,
            configEpoch = 0,
            createdAtMs = now,
        )
        val deliveryState =
            if (parsed.amountAmbiguous) DeliveryState.NEEDS_REVIEW else DeliveryState.PENDING
        val delivery = Delivery(
            deliveryId = UUID.randomUUID().toString(),
            eventId = event.eventId,
            kind = DeliveryKind.LEGACY_CHAIN,
            state = deliveryState,
            rawEphemeral = rawEphemeral,
            legacyQkey = qkey,
            routeIds = routeIds,
            createdAtMs = now,
            updatedAtMs = now,
            routeStamps = stamps,
        )
        val stagedResult = try {
            repository.stageCapture(event, listOf(stagingRow))
        } catch (e: Exception) {
            // Staging write failed: the capture is parked in memory
            // only if the caller retries; here the snapshot is lost
            // to this attempt but the notification may still be
            // recovered by rescan while active. Surfaced, never silent.
            parked.incrementAndGet()
            logger.logLocal("capture gagal staging ($pkg): ${e.message?.take(120)}")
            return
        }
        staged.incrementAndGet()
        when (stagedResult.eventOutcome) {
            EventInsertOutcome.COLLISION -> {
                // Same dedupe_key, different content: the first event
                // is kept, no delivery is created (§3 R11).
                identityCollision.incrementAndGet()
                logger.logLocal("capture collision (dedupe sama, isi beda): $pkg @${snapshot.postTimeMs}")
                // LH-04(c): unlinked row (LH-06) — the colliding
                // observation is not the kept event.
                writeCaptureLog(
                    snapshot, CaptureOutcome.COLLISION,
                    titleSnippet = gate.redactedTitle.take(40),
                    amountMinor = amount, eventId = null, contentSha256 = contentSha,
                )
                return
            }
            EventInsertOutcome.DUPLICATE -> {
                // Rescan cross-store rule (§6): an existing
                // PENDING/IN_FLIGHT delivery for this dedupe_key
                // (found via the event) means the capture is already
                // durable — drop the staging row, no second delivery.
                // (Checked by event, not by qkey: the route set may
                // have changed between the live capture and a rescan,
                // and one event maps to one chain delivery, §4.2.)
                duplicates.incrementAndGet()
                val existingEvent = repository.getEventByDedupeKey(dedupeKey)
                val existingDeliveries = existingEvent?.let {
                    repository.getDeliveriesByEventId(it.eventId)
                }.orEmpty()
                if (existingDeliveries.any {
                        it.state == DeliveryState.PENDING || it.state == DeliveryState.IN_FLIGHT
                    }
                ) {
                    repository.deleteStagingRow(qkey)
                    writeCaptureLog(
                        snapshot, CaptureOutcome.DUPLICATE,
                        titleSnippet = gate.redactedTitle.take(40),
                        amountMinor = amount, eventId = null, contentSha256 = contentSha,
                    )
                    return
                }
                if (repository.getDeliveryByQkey(qkey) != null) {
                    repository.deleteStagingRow(qkey)
                    writeCaptureLog(
                        snapshot, CaptureOutcome.DUPLICATE,
                        titleSnippet = gate.redactedTitle.take(40),
                        amountMinor = amount, eventId = null, contentSha256 = contentSha,
                    )
                    return
                }
                // Event exists but no live delivery (a partial
                // earlier capture, or a terminal delivery): fall
                // through and promote.
            }
            EventInsertOutcome.INSERTED -> Unit
        }
        // The insert outcome, recorded durably (LH-04(c)): INSERTED
        // links to the new event; the DUPLICATE fall-through (a
        // partial earlier capture now promoted) stays unlinked.
        writeCaptureLog(
            snapshot,
            if (stagedResult.eventOutcome == EventInsertOutcome.INSERTED) {
                CaptureOutcome.CAPTURED
            } else {
                CaptureOutcome.DUPLICATE
            },
            titleSnippet = gate.redactedTitle.take(40),
            amountMinor = amount,
            eventId = if (stagedResult.eventOutcome == EventInsertOutcome.INSERTED) {
                event.eventId
            } else {
                null
            },
            contentSha256 = contentSha,
        )
        enqueueStaged(stagingRow, delivery, payload, snapshot, routeIds)
    }

    /**
     * One durable capture-log row (LH-04), on the capture executor
     * thread — a single-row insert, never holding a DB transaction
     * during network I/O (LH-04(d)). A failed insert is counted and
     * logged explicitly, never silently skipped; the capture itself
     * continues (the event row is already durable).
     */
    private suspend fun writeCaptureLog(
        snapshot: NotificationSnapshot,
        outcome: String,
        titleSnippet: String?,
        amountMinor: Long?,
        eventId: String?,
        contentSha256: String?,
    ) {
        val dao = captureLogDao ?: return
        try {
            dao.insert(
                CaptureLogEntity(
                    captureId = UUID.randomUUID().toString(),
                    sourcePackage = snapshot.packageName,
                    postTimeMs = snapshot.postTimeMs,
                    capturedAtMs = clock(),
                    outcome = outcome,
                    titleSnippet = titleSnippet?.take(40),
                    amountMinor = amountMinor,
                    eventId = eventId,
                    contentSha256 = contentSha256,
                ),
            )
        } catch (e: Exception) {
            captureLogFailures.incrementAndGet()
            logger.logLocal(
                "capture-log gagal ditulis ($outcome ${snapshot.packageName}): " +
                    e.message?.take(120),
            )
        }
    }

    /** A PrivacyGate-scanned title snippet (≤40 chars, LH-04). */
    private fun snippet(title: String): String =
        SensitiveDataRedactor.redact(title).take(40)

    /**
     * Mode-gated enqueue of one staged capture (§8.3 (2′)/(4)):
     * exactly one store, decided by the mode read on this thread.
     * Returns true when the capture is durable in its target store
     * (and the staging row deleted).
     */
    private suspend fun enqueueStaged(
        stagingRow: CaptureStagingEntity,
        delivery: Delivery,
        payload: JSONObject,
        snapshot: NotificationSnapshot,
        routeIds: List<String>,
    ): Boolean {
        val mode = modeReader()
        // §7.6 is absolute: an amount-ambiguous event is NEVER
        // enqueued to the legacy sender (the first-token amount may
        // be a balance, and the legacy sender cannot hold it for
        // review). It promotes to Room as NEEDS_REVIEW in EVERY
        // mode — in LEGACY_ONLY the delivery is inert (the worker
        // claims only in WORKER_ONLY) and the pick releases it to
        // PENDING; it dispatches after cutover. The review surface
        // discloses the wait in LEGACY_ONLY mode.
        if (delivery.state == DeliveryState.NEEDS_REVIEW) {
            return promoteToRoom(stagingRow, delivery, payload, snapshot, routeIds, mode)
        }
        return when (mode) {
            CutoverMode.LEGACY_ONLY -> {
                val ok = legacyEnqueue.enqueue(payload, LegacyQkey.KIND_CHAIN, routeIds)
                if (!ok) {
                    parked.incrementAndGet()
                    logger.logLocal(
                        "capture parkir (gagal antre legacy): ${snapshot.packageName} " +
                            "@${snapshot.postTimeMs}; dicoba lagi oleh sweep",
                    )
                    return false
                }
                repository.deleteStagingRow(stagingRow.legacyQkey)
                enqueued.incrementAndGet()
                logger.logEvent(
                    snapshot.packageName,
                    "payment: Rp${payload.optLong("amount")} dari ${snapshot.packageName} -> " +
                        routeIds.joinToString(" > "),
                )
                true
            }
            CutoverMode.DRAINING, CutoverMode.WORKER_ONLY ->
                promoteToRoom(stagingRow, delivery, payload, snapshot, routeIds, mode)
        }
    }

    /** Promotes one staged capture to a Room delivery (all modes for NEEDS_REVIEW). */
    private suspend fun promoteToRoom(
        stagingRow: CaptureStagingEntity,
        delivery: Delivery,
        payload: JSONObject,
        snapshot: NotificationSnapshot,
        routeIds: List<String>,
        mode: CutoverMode,
    ): Boolean {
        val result = repository.promoteStagingToRoom(stagingRow, delivery)
        if (!result.deliveryInserted && repository.getDeliveryByQkey(stagingRow.legacyQkey) == null) {
            // Insert failed AND no delivery exists: the staging row
            // is retained for the sweep (§8.3 (7′)).
            parked.incrementAndGet()
            logger.logLocal(
                "capture parkir (gagal promote Room): ${snapshot.packageName} " +
                    "@${snapshot.postTimeMs}; dicoba lagi oleh sweep",
            )
            return false
        }
        if (mode == CutoverMode.DRAINING) roomCapturesDuringDraining.incrementAndGet()
        enqueued.incrementAndGet()
        logger.logEvent(
            snapshot.packageName,
            "payment: Rp${payload.optLong("amount")} dari ${snapshot.packageName} -> " +
                routeIds.joinToString(" > ") + " (Room)",
        )
        if (mode == CutoverMode.WORKER_ONLY) onRoomEnqueued()
        return true
    }

    // --- Staging sweep (executor thread) ---

    /**
     * Re-promotes orphaned staging rows through the same lock/mode
     * gate (§8.3 (2′)): e.g. after process death between staging
     * and enqueue. Idempotent by `dedupe_key` / `legacy_qkey`:
     * a row whose delivery already exists is deleted without a
     * second enqueue; a legacy re-enqueue dedupes by `qkey`.
     */
    suspend fun sweepOrphanedStaging() {
        val rows = repository.getAllStagingRows()
        for (row in rows) {
            try {
                sweepOne(row)
            } catch (e: Exception) {
                logger.logLocal("sweep staging gagal (${row.legacyQkey}): ${e.message?.take(120)}")
            }
        }
    }

    private suspend fun sweepOne(row: CaptureStagingEntity) {
        val existing = repository.getDeliveryByQkey(row.legacyQkey)
        if (existing != null) {
            // Already promoted (crash between enqueue and staging
            // delete): delete the orphan row, no second delivery.
            repository.deleteStagingRow(row.legacyQkey)
            sweptOrphaned.incrementAndGet()
            return
        }
        val event = repository.getEventByDedupeKey(row.dedupeKey)
        if (event == null) {
            // No event row: the staging row cannot be promoted
            // (nothing to deliver). Delete it — counted, visible.
            repository.deleteStagingRow(row.legacyQkey)
            sweptOrphaned.incrementAndGet()
            logger.logLocal("staging tanpa event dihapus: ${row.legacyQkey}")
            return
        }
        val parsed = LegacyQkey.parse(row.legacyQkey) ?: run {
            repository.deleteStagingRow(row.legacyQkey)
            sweptOrphaned.incrementAndGet()
            logger.logLocal("staging qkey rusak dihapus: ${row.legacyQkey}")
            return
        }
        // F18: a payload_sha256 mismatch means the staged payload no
        // longer matches the event row — hold the row (`staging_
        // mismatch`), never dispatch it. The row stays for operator
        // inspection; the sweep does not delete it.
        val currentPayloadJson = event.legacyPayloadJson ?: ""
        if (row.payloadSha256 != null &&
            sha256Hex(currentPayloadJson) != row.payloadSha256
        ) {
            stagingMismatch.incrementAndGet()
            logger.logLocal("staging_mismatch, tidak dipromosikan: ${row.legacyQkey}")
            return
        }
        val payload = try {
            JSONObject(event.legacyPayloadJson ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        val ambiguous = payload.optBoolean("amount_ambiguous", false)
        val now = clock()
        val delivery = Delivery(
            deliveryId = UUID.randomUUID().toString(),
            eventId = event.eventId,
            kind = DeliveryKind.LEGACY_CHAIN,
            state = if (ambiguous) DeliveryState.NEEDS_REVIEW else DeliveryState.PENDING,
            // B4/B19: the snapshot is gone after a process restart,
            // but the delivery is still a B delivery — the worker
            // runs the §7.3B evaluation at claim time instead of
            // silently posting the redacted payload.
            rawEphemeral = payload.optBoolean("raw_ephemeral", false),
            legacyQkey = row.legacyQkey,
            routeIds = parsed.routeIds,
            createdAtMs = now,
            updatedAtMs = now,
        )
        val snapshot = NotificationSnapshot(
            key = "",
            packageName = event.sourcePackage ?: "",
            postTimeMs = event.occurredAtMs,
            flags = 0,
            title = "",
            text = "",
            bigText = "",
            capturedAtMs = now,
        )
        if (enqueueStaged(row, delivery, payload, snapshot, parsed.routeIds)) {
            sweptOrphaned.incrementAndGet()
        }
    }

    // --- Helpers ---

    /**
     * Staging re-scan gate (§8.3 R11, defence in depth) now lives
     * in [PrivacyGate.gate]: redact every classified span
     * (confirmed AND ambiguous — §7.1(b) redacts ambiguous spans
     * too), then re-scan the redacted text; if a class-(a) span
     * survives, the text is dropped and only structured fields
     * are staged. This coordinator calls the gate; it never
     * re-implements the scan.
     */

    /** The legacy wire payload (v2.3 shape), built from REDACTED text only. */
    private fun buildPayload(
        snapshot: NotificationSnapshot,
        amount: Long,
        dedupeKey: String,
        redactedTitle: String,
        redactedText: String,
        amountAmbiguous: Boolean,
        tokens: List<com.netter.paylist.parser.AmountToken>,
        redacted: Boolean,
        inseparable: Boolean,
        rawEphemeral: Boolean,
    ): JSONObject = JSONObject().apply {
        put("type", "payment")
        put("device", deviceIdProvider())
        put("pkg", snapshot.packageName)
        put("amount", amount)
        put("title", redactedTitle.take(500))
        put("text", redactedText.take(500))
        put("notif_time", java.time.Instant.ofEpochMilli(snapshot.postTimeMs).toString())
        put("dedupe_key", dedupeKey)
        // §7.3A: the flush takes the raw snapshot at its per-hop
        // claim step only for B items; the flag survives the
        // staging sweep so a post-restart promotion still runs
        // the §7.3B evaluation (B4/B19) instead of silently A′.
        if (rawEphemeral) put("raw_ephemeral", true)
        // Direction, computed in memory from the transient raw text
        // with the §7.2 two-list heuristic (§7.1 confidence matrix).
        // Advisory only (PHP ignores extra fields); the claim-time
        // §7.3B gate reads it for criterion 2, and the A′
        // constructed text uses it for recorder routes.
        put(
            "direction",
            com.netter.paylist.parser.DirectionHeuristic.classify(snapshot.combined)
                .name.lowercase(),
        )
        if (redacted) put("redacted", true)
        if (inseparable) put("inseparable", true)
        if (amountAmbiguous) {
            put("amount_ambiguous", true)
            val arr = JSONArray()
            for (token in tokens) {
                arr.put(JSONObject().apply {
                    put("value", token.value)
                    put("start", token.start)
                    put("end", token.end)
                })
            }
            put("amount_tokens", arr)
        }
    }
}
