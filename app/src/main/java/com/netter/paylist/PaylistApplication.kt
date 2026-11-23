package com.netter.paylist

import android.app.Application
import androidx.work.Configuration
import com.netter.paylist.collector.AmountReview
import com.netter.paylist.collector.CaptureCoordinator
import com.netter.paylist.collector.CaptureLogSink
import com.netter.paylist.collector.ConsentStore
import com.netter.paylist.collector.PrivacyGate
import com.netter.paylist.collector.RawSnapshotStore
import com.netter.paylist.collector.RouteLifecycle
import com.netter.paylist.collector.StoreLegacySweep
import com.netter.paylist.collector.StoreQuarantineQueue
import com.netter.paylist.collector.StoreReviewRoutes
import com.netter.paylist.collector.StoreRouteQueueStore
import com.netter.paylist.export.ReconciliationExport
import com.netter.paylist.export.ReconciliationExportRunner
import com.netter.paylist.export.StoreConsentSnapshotSource
import com.netter.paylist.data.EventRepository
import com.netter.paylist.data.PaylistDatabase
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.delivery.ReviewNotifier
import com.netter.paylist.delivery.StoreRouteSource
import com.netter.paylist.delivery.SyncScheduler
import com.netter.paylist.delivery.SyncWorkerFactory
import com.netter.paylist.migration.CutoverCommit
import com.netter.paylist.migration.CutoverCoordinator
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.history.HistoryRepository
import com.netter.paylist.migration.LegacyEnqueue
import com.netter.paylist.migration.LegacyQkey
import com.netter.paylist.migration.LegacyQueueMigration
import com.netter.paylist.migration.LegacyQueueSnapshotSource
import com.netter.paylist.privacy.GateBackedInspector
import com.netter.paylist.security.SecretVault
import com.netter.paylist.security.SharedPreferencesKeyValueStore
import com.netter.paylist.migration.SecretMigration
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Application wiring for v1.1 (Stage C): the Room database, the
 * cutover coordinator, the legacy sender (mode-gated), the
 * WorkManager factory, and the one-time cutover activation.
 *
 * Startup (background thread, never blocking `onCreate`):
 * 1. Write this process's boot identity (`boot_id` doc) — the
 *    coordinator needs it to prove quiescence.
 * 2. Recover orphaned attempts from dead processes (F31).
 * 3. Run the one-time secret migration (plaintext → Keystore, F44;
 *    idempotent, no-op on later launches).
 * 4. Schedule periodic sync + heartbeat work.
 *
 * The `neverSentGatePassed` flag is true: F40 (E1/E2/E3/H/I) passed
 * on the actual stack in Stage C, so orphaned pre-socket attempts
 * may classify `NEVER_SENT` (§8.5 resolution 3).
 */
class PaylistApplication : Application(), Configuration.Provider {

    companion object {
        /**
         * Stage D flips this to true when it wires capture to Room.
         * Until then [runCutover] refuses: activating the worker
         * while capture still targets the legacy queue would stop
         * new payments from delivering at all.
         */
        const val CAPTURE_TO_ROOM_READY: Boolean = true
    }

    /** This process's boot identity (fresh UUID per process start). */
    val bootId: String = UUID.randomUUID().toString()

    val db: PaylistDatabase by lazy { PaylistDatabase.create(this) }

    val store: Store by lazy { Store(this) }

    val modeGate: ModeGate by lazy {
        ModeGate {
            try {
                runBlocking { coordinator.getMode() } == CutoverMode.LEGACY_ONLY
            } catch (e: Exception) {
                // Fail closed: without a mode read, the legacy sender
                // must not post (a post during DRAINING would race
                // the drain). The next flush retries the read.
                false
            }
        }
    }

    val coordinator: CutoverCoordinator by lazy {
        val transactions = db.transactionRunner()
        val migration = LegacyQueueMigration(
            db.eventDao(),
            db.deliveryDao(),
            db.attemptDao(),
            db.promotionReceiptDao(),
            transactions,
        )
        val queue = object : LegacyQueueSnapshotSource {
            override fun queueSnapshot(): List<JSONObject> = store.queueSnapshot()
            override fun saveQueueSync(items: List<JSONObject>): Boolean =
                store.saveQueueSync(items)
        }
        CutoverCoordinator(
            db.collectorStateDao(),
            db.deliveryDao(),
            db.attemptDao(),
            transactions,
            migration,
            queue,
            SenderLock.lock,
            neverSentGatePassed = true,
        )
    }

    /** The single legacy sender (mode-gated, vault-backed secrets). */
    val sender: Sender by lazy {
        Sender(
            this, modeGate, SecretVault(this),
            rawSnapshotStore = rawSnapshotStore,
            consentStore = consentStore,
            onAmbiguous = onLegacyAmbiguous,
        )
    }

    /** The capture coordinator (Stage D, design §8.3): listener → Room/legacy. */
    val captureCoordinator: CaptureCoordinator by lazy {
        val transactions = db.transactionRunner()
        val repository = EventRepository(
            db.eventDao(),
            db.deliveryDao(),
            db.captureStagingDao(),
            db.classificationDao(),
            transactions,
        )
        val legacyEnqueue = LegacyEnqueue { payload, kind, routeIds ->
            if (kind != LegacyQkey.KIND_CHAIN) return@LegacyEnqueue false
            sender.enqueueChainSync(payload, routeIds)
        }
        val logSink = object : CaptureLogSink {
            override fun logEvent(pkg: String, text: String) = store.logEvent(pkg, text)
            override fun logLocal(text: String) = store.logLocal(text)
        }
        CaptureCoordinator(
            repository = repository,
            modeReader = { coordinator.getMode() },
            legacyEnqueue = legacyEnqueue,
            routesProvider = { store.routes() },
            deviceIdProvider = { store.deviceLabel() },
            logger = logSink,
            onRoomEnqueued = { SyncScheduler.requestSync(this) },
            privacyGate = privacyGate,
            rawSnapshotStore = rawSnapshotStore,
            consentStore = consentStore,
            captureLogDao = db.captureLogDao(),
        )
    }

    /**
     * The Stage D D4 history read model (addendum 06, LH-01–LH-03):
     * capture log + linked events/deliveries/attempts, in-app
     * viewing only — no export capability (LH-07(c), F55).
     */
    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(
            captureLogDao = db.captureLogDao(),
            eventDao = db.eventDao(),
            deliveryDao = db.deliveryDao(),
            attemptDao = db.attemptDao(),
            collectorStateDao = db.collectorStateDao(),
        )
    }

    /**
     * C1 ambiguous-case alert (user decision 2026-11-23): the Room
     * worker path fires with a delivery id; the amount/package are
     * resolved via the history repository on a fresh thread (the
     * worker thread never blocks on the resolve). When the resolve
     * fails, the alert still fires with generic text.
     */
    private val onRoomAmbiguous: (deliveryId: String) -> Unit = { deliveryId ->
        Thread {
            val info = try {
                runBlocking { historyRepository.ambiguousCaseInfo(deliveryId) }
            } catch (e: Exception) {
                null
            }
            ReviewNotifier.notifyAmbiguous(this, deliveryId, info?.first, info?.second)
        }.start()
    }

    /**
     * C1 ambiguous-case alert, legacy sender path: the case is
     * recorded durably (collector doc, for the badge) and the
     * alert fires with the payload's amount/package.
     */
    private val onLegacyAmbiguous: (qkey: String, dedupeKey: String, amountMinor: Long?, sourcePackage: String?) -> Unit =
        { qkey, dedupeKey, amount, pkg ->
            try {
                runBlocking {
                    historyRepository.recordLegacyAmbiguous(qkey, dedupeKey, amount, pkg)
                }
            } catch (e: Exception) {
                store.logLocal("gagal mencatat kasus ambigu legacy: ${e.message?.take(120)}")
            }
            ReviewNotifier.notifyAmbiguous(this, "legacy:$qkey", amount, pkg)
        }

    /** Per-route consent records (§7.5 C22(l), §7.3 P6 opt-in). */
    val consentStore: ConsentStore by lazy {
        ConsentStore(db.collectorStateDao())
    }

    /** The Stage D PrivacyGate, bound to the consent store (B-eligibility). */
    val privacyGate: PrivacyGate by lazy {
        PrivacyGate(consentStore)
    }

    /**
     * The single §7.3A raw-snapshot store, shared by the capture
     * executor (put) and the claim paths (take). One instance for
     * the whole process.
     */
    val rawSnapshotStore: RawSnapshotStore by lazy {
        RawSnapshotStore()
    }

    /**
     * The Stage D operator review surface (§7.6/§7.3B/§7.7):
     * amount picks, RAW_UNAVAILABLE authorise/discard, quarantine
     * approve/discard. The coordinator is the quarantine record
     * store; the legacy sweep/queue adapters hold SenderLock.
     * `recorderGatePassed` stays false until B14 passes against
     * live `catat.php` semantics (fail-closed).
     */
    val amountReview: AmountReview by lazy {
        AmountReview(
            deliveryDao = db.deliveryDao(),
            eventDao = db.eventDao(),
            collectorStateDao = db.collectorStateDao(),
            matchResultDao = db.matchResultDao(),
            transactions = db.transactionRunner(),
            quarantineStore = coordinator,
            legacySweep = StoreLegacySweep(store),
            quarantineQueue = StoreQuarantineQueue(store),
            routes = StoreReviewRoutes(store),
            consentStore = consentStore,
            privacyGate = privacyGate,
            recorderGatePassed = false,
        )
    }

    /**
     * The Stage D route lifecycle (§7.5/F21): revoke/delete/
     * disable/enable + the BLOCKED sweep and explicit reactivation.
     */
    val routeLifecycle: RouteLifecycle by lazy {
        RouteLifecycle(
            deliveryDao = db.deliveryDao(),
            transactions = db.transactionRunner(),
            consentStore = consentStore,
            routes = StoreRouteSource(store),
            queueStore = StoreRouteQueueStore(store),
            rawSnapshotStore = rawSnapshotStore,
        )
    }

    /**
     * The §7.7A reconciliation export (Q1 REQUIRED) — the only
     * export path in the app (F55). Quarantined dedupe keys come
     * from the coordinator's quarantine records (unresolved only).
     */
    val reconciliationExport: ReconciliationExport by lazy {
        val routeSource = StoreRouteSource(store)
        ReconciliationExport(
            eventDao = db.eventDao(),
            deliveryDao = db.deliveryDao(),
            matchResultDao = db.matchResultDao(),
            classificationDao = db.classificationDao(),
            routes = routeSource,
            consentSource = StoreConsentSnapshotSource(consentStore, routeSource),
            quarantinedDedupeKeys = {
                runBlocking {
                    coordinator.listAll()
                        .filter { it.resolution == null }
                        .map { it.dedupeKey }
                        .toSet()
                }
            },
        )
    }

    /** Streams a frozen export to SAF with per-row revalidation + audit. */
    val exportRunner: ReconciliationExportRunner by lazy {
        ReconciliationExportRunner(reconciliationExport, db.exportAuditDao())
    }

    /** Read-only held-count aggregates for the Diagnostics screen (D5). */
    val diagnosticsRepository: DiagnosticsRepository by lazy {
        DiagnosticsRepository(
            deliveryDao = db.deliveryDao(),
            queueSnapshot = { store.queue() },
            ambiguousCount = { historyRepository.ambiguousCount() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        Thread { startup() }.start()
    }

    /**
     * Per-route `raw_lost_fallback_total` counters (§7.3B
     * visibility): how many authorised A′ fallbacks were actually
     * dispatched per route in this process. Surfaced in
     * diagnostics (D5); the durable per-delivery record is the
     * `raw_lost` flag on the delivery + attempt rows.
     */
    val rawLostFallbackTotals: MutableMap<String, java.util.concurrent.atomic.AtomicLong> =
        java.util.concurrent.ConcurrentHashMap()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(
                SyncWorkerFactory(
                    db, StoreRouteSource(store), SecretVault(this),
                    rawSnapshotStore = rawSnapshotStore,
                    consentStore = consentStore,
                    // F43 B14: fail-closed — automatic A′ fallback
                    // on recorder routes is NOT permitted until B14
                    // passes against live catat.php semantics.
                    recorderGatePassed = false,
                    onFallbackDispatched = { routeId ->
                        rawLostFallbackTotals
                            .computeIfAbsent(routeId) {
                                java.util.concurrent.atomic.AtomicLong()
                            }
                            .incrementAndGet()
                        store.logLocal("fallback A′ terkirim (raw hilang) rute $routeId")
                    },
                    onAmbiguous = onRoomAmbiguous,
                )
            )
            .build()

    /**
     * Startup sequence (background thread). Each step is best-effort
     * except the boot-doc write: a failure is logged locally and the
     * next launch retries; nothing here throws to the caller.
     */
    private fun startup() {
        try {
            val now = System.currentTimeMillis()
            runBlocking {
                db.collectorStateDao().put(
                    CollectorStateEntity(
                        CollectorStateKeys.BOOT_ID,
                        JSONObject().apply {
                            put("boot_id", bootId)
                            put("boot_started_at_ms", now)
                        }.toString(),
                        now,
                    )
                )
            }
            val report = runBlocking {
                coordinator.recoverOrphanedAttempts(bootId, System.currentTimeMillis())
            }
            if (report.orphanCount > 0 || report.abandonedStale > 0) {
                store.logLocal(
                    "pemulihan awal: ${report.abandonedStale} dibatalkan, " +
                        "${report.neverSent} tidak pernah terkirim, " +
                        "${report.unknownHeld} ditahan untuk rekonsiliasi"
                )
            }
        } catch (e: Exception) {
            store.logLocal("pemulihan awal gagal: ${e.message?.take(120)}")
        }
        try {
            val routesStore = SharedPreferencesKeyValueStore(
                getSharedPreferences("paylist", MODE_PRIVATE)
            )
            val markerStore = SharedPreferencesKeyValueStore(
                getSharedPreferences("paylist_migration", MODE_PRIVATE)
            )
            val report = SecretMigration(routesStore, markerStore, SecretVault(this)).migrate()
            val migrated = report.results.count { it is SecretMigration.RouteOutcome.Migrated }
            val blocked = report.results.count { it is SecretMigration.RouteOutcome.BlockedMissingSecret }
            if (migrated > 0 || blocked > 0) {
                store.logLocal("migrasi secret: $migrated rute dipindah ke Keystore, $blocked rute tanpa secret")
            }
        } catch (e: Exception) {
            store.logLocal("migrasi secret gagal: ${e.message?.take(120)}")
        }
        try {
            // Stage D D5: one-time upgrade migration (§7.5) — routes
            // that already exist from v2.3 carry their working
            // consent forward (the operator already runs the
            // listener; the upgrade must not silently stop billing
            // capture). Guarded by the collector marker doc; never
            // re-grants a revoked route. Fresh installs get no
            // grant until the operator completes consent in
            // RouteEditActivity.
            val marker = runBlocking {
                db.collectorStateDao().get(RouteLifecycle.UPGRADE_MIGRATION_KEY)
            }
            if (marker == null) {
                val granted = runBlocking {
                    routeLifecycle.migrateUpgradeConsent(store.routes().map { it.id })
                }
                runBlocking {
                    db.collectorStateDao().put(
                        CollectorStateEntity(
                            RouteLifecycle.UPGRADE_MIGRATION_KEY,
                            JSONObject().apply {
                                put("granted", granted)
                                put("at_ms", System.currentTimeMillis())
                            }.toString(),
                            System.currentTimeMillis(),
                        )
                    )
                }
                if (granted > 0) {
                    store.logLocal("migrasi persetujuan: $granted rute lama diaktifkan")
                }
            }
        } catch (e: Exception) {
            store.logLocal("migrasi persetujuan gagal: ${e.message?.take(120)}")
        }
        try {
            SyncScheduler.schedulePeriodic(this)
            SyncScheduler.scheduleHeartbeat(this)
        } catch (e: Exception) {
            store.logLocal("jadwal WorkManager gagal: ${e.message?.take(120)}")
        }
        try {
            // Stage D: re-promote staging rows orphaned by a process
            // death between staging and enqueue, then sweep
            // periodically on the capture executor.
            runBlocking { captureCoordinator.sweepOrphanedStaging() }
            captureCoordinator.startPeriodicSweep()
        } catch (e: Exception) {
            store.logLocal("sweep staging gagal: ${e.message?.take(120)}")
        }
        try {
            // Stage D D3: recover amount picks interrupted by a
            // crash between the Room hold and the legacy sweep —
            // re-sweep and release/block each HELD delivery.
            val (released, blocked, held) =
                runBlocking { amountReview.recoverHeldPicks() }
            if (released > 0 || blocked > 0 || held > 0) {
                store.logLocal(
                    "pemulihan pick review: $released dilepas, $blocked diblokir, $held masih ditahan"
                )
            }
        } catch (e: Exception) {
            store.logLocal("pemulihan pick review gagal: ${e.message?.take(120)}")
        }
    }

    /**
     * Runs the cutover activation (§8.2) on the CALLER's thread —
     * call from a background thread, never the main thread. Reports
     * progress through [progress] (called on the same thread).
     * Returns the final user-facing message (Indonesian).
     *
     * Steps: request the drain → acquire the sender lock (45 s, fail
     * closed) → durable flush + verify → commit `DRAINING` →
     * drain (import + sweep) → commit `WORKER_ONLY` → request a sync.
     * On any failure after the drain request, the cutover is aborted
     * back to `LEGACY_ONLY` so the legacy sender resumes.
     *
     * Known Stage C limitation: captures arriving between the drain
     * snapshot and the `WORKER_ONLY` commit land in the legacy queue
     * after the snapshot (Stage C capture still enqueues there; the
     * Stage D capture rewire sends them to Room instead). The window
     * is seconds; a non-empty queue afterwards is logged loudly.
     */
    fun runCutover(progress: (String) -> Unit): String {
        if (!CAPTURE_TO_ROOM_READY) {
            return "Cutover menunggu Stage D: jalur capture ke Room belum ada. " +
                "Mengaktifkan Worker sekarang akan menghentikan pengiriman pembayaran baru."
        }
        val now = System.currentTimeMillis()
        progress("Meminta drain…")
        when (val requested = runBlocking { coordinator.requestDrain(now) }) {
            is CutoverCommit.Rejected -> return "Cutover ditolak: ${requested.reason}"
            is CutoverCommit.Success -> Unit
        }
        fun abortAndReturn(message: String): String {
            runBlocking { coordinator.abortCutover(System.currentTimeMillis()) }
            return message
        }
        progress("Menunggu antrean tenang (maks 45 detik)…")
        val locked = try {
            SenderLock.lock.tryLock(45, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!locked) {
            return abortAndReturn(
                "Cutover dibatalkan: antrean sibuk (pengiriman sedang berjalan). " +
                    "Coba lagi beberapa menit lagi."
            )
        }
        try {
            // Durable flush: commit the current queue and verify the
            // readback before any state change (fail closed).
            val snapshot = store.queueSnapshot()
            if (!store.saveQueueSync(snapshot)) {
                return abortAndReturn("Cutover dibatalkan: antrean gagal ditulis sinkron.")
            }
            if (store.queueSnapshot().size != snapshot.size) {
                return abortAndReturn("Cutover dibatalkan: verifikasi antrean tidak cocok.")
            }
            progress("Mengunci antrean, masuk mode DRAINING…")
            when (val draining = runBlocking { coordinator.commitDraining(now) }) {
                is CutoverCommit.Rejected ->
                    return abortAndReturn("Cutover dibatalkan: ${draining.reason}")
                is CutoverCommit.Success -> Unit
            }
        } finally {
            SenderLock.lock.unlock()
        }
        progress("Memigrasikan antrean ke Room…")
        val drained = runBlocking { coordinator.drain(GateBackedInspector, now) }
        if (!drained.ok) {
            return "Drain gagal: ${drained.error ?: "tidak diketahui"}. " +
                "Mode masih DRAINING; ulangi cutover atau batalkan dari layar utama."
        }
        progress("Mengaktifkan pengiriman baru (Worker)…")
        when (val committed = runBlocking { coordinator.commitWorkerOnly(now) }) {
            is CutoverCommit.Rejected ->
                return "Aktivasi Worker ditolak: ${committed.reason}. Mode masih DRAINING."
            is CutoverCommit.Success -> Unit
        }
        SyncScheduler.requestSync(this)
        val leftover = store.queueSnapshot().size
        val summary = "Pengiriman baru aktif: ${drained.imported} item dimigrasikan, " +
            "${drained.quarantined} dikarantina (privasi), ${drained.corrupt} rusak dilewati."
        store.logLocal("cutover selesai: $summary")
        if (leftover > 0) {
            // Captures that landed after the drain snapshot (see the
            // KDoc): visible, never silent.
            store.logLocal(
                "PERHATIAN cutover: $leftover item masih di antrean legacy " +
                    "setelah aktivasi (tiba saat drain); perlu penanganan manual"
            )
            store.setLastFailure("cutover: $leftover item tersisa di antrean legacy")
        }
        return summary
    }
}
