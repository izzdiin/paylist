package com.netter.paylist

import android.app.Application
import androidx.work.Configuration
import com.netter.paylist.data.PaylistDatabase
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.delivery.StoreRouteSource
import com.netter.paylist.delivery.SyncScheduler
import com.netter.paylist.delivery.SyncWorkerFactory
import com.netter.paylist.migration.CutoverCommit
import com.netter.paylist.migration.CutoverCoordinator
import com.netter.paylist.migration.CutoverFlushGate
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.migration.FlushResult
import com.netter.paylist.migration.LegacyQueueMigration
import com.netter.paylist.migration.LegacyQueueSnapshotSource
import com.netter.paylist.migration.QuarantinedItemInfo
import com.netter.paylist.privacy.GateBackedInspector
import com.netter.paylist.privacy.CutoverPrivacyPreflight
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
        const val CAPTURE_TO_ROOM_READY: Boolean = false

        /**
         * The production mode gate over [coordinator]: the legacy
         * sender may start a POST only while the coordinator's mode
         * is `LEGACY_ONLY`, read fresh on every check. Fail-closed:
         * any read failure denies the POST (a post during `DRAINING`
         * would race the drain); the next flush retries the read.
         *
         * The R06 contention tests build their gate with this same
         * factory over their file-backed coordinator, so the gate
         * under test is the production gate, not a copy of it.
         */
        fun productionModeGate(coordinator: CutoverCoordinator): ModeGate =
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

    /** This process's boot identity (fresh UUID per process start). */
    val bootId: String = UUID.randomUUID().toString()

    val db: PaylistDatabase by lazy { PaylistDatabase.create(this) }

    val store: Store by lazy { Store(this) }

    val modeGate: ModeGate by lazy { productionModeGate(coordinator) }

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
        Sender(this, modeGate, SecretVault(this))
    }

    override fun onCreate() {
        super.onCreate()
        Thread { startup() }.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(
                SyncWorkerFactory(db, StoreRouteSource(store), SecretVault(this))
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
            SyncScheduler.schedulePeriodic(this)
            SyncScheduler.scheduleHeartbeat(this)
        } catch (e: Exception) {
            store.logLocal("jadwal WorkManager gagal: ${e.message?.take(120)}")
        }
    }

    /**
     * Runs the cutover activation (§8.2) on the CALLER's thread —
     * call from a background thread, never the main thread. Reports
     * progress through [progress] (called on the same thread).
     * Returns the final user-facing message (Indonesian).
     *
     * Steps: request the drain → acquire the sender lock (45 s, fail
     * closed) → durable flush + verify (R07: content/identity readback;
     * a same-count mutation aborts before `DRAINING`) → commit `DRAINING` →
     * drain (import + sweep) → commit `WORKER_ONLY` → request a sync →
     * re-drain leftovers (R08).
     * On any failure after the drain request, the cutover is aborted
     * back to `LEGACY_ONLY` so the legacy sender resumes.
     *
     * R08: captures arriving after the drain snapshot — or after
     * `commitDraining`, unmarked by the preflight — land in the
     * legacy queue while the sender is gated off (Stage C capture
     * still enqueues there; the Stage D capture rewire sends them
     * to Room instead). They are NOT left for manual handling: after
     * the `WORKER_ONLY` commit, [redrainLeftoversAfterCommit]
     * re-runs the privacy preflight and re-drains whatever is left,
     * under the sender lock, so no capture can land unmarked
     * mid-redrain. In Stage D no capture can land in the legacy
     * queue after the re-drain at all (capture targets Room), so
     * the re-drain closes the window for good.
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
        val queueSource = object : LegacyQueueSnapshotSource {
            override fun queueSnapshot(): List<JSONObject> = store.queueSnapshot()
            override fun saveQueueSync(items: List<JSONObject>): Boolean =
                store.saveQueueSync(items)
        }
        try {
            // P9 reprocessing must be durable before DRAINING. The sender
            // lock prevents concurrent legacy queue mutation.
            val privacy = CutoverPrivacyPreflight().run(queueSource, System.currentTimeMillis())
            if (!privacy.ok) {
                return abortAndReturn(
                    "Cutover dibatalkan: pemeriksaan privasi antrean gagal (${privacy.reason})."
                )
            }
            // Durable flush: commit the current queue and verify the
            // readback before any state change (fail closed). R07: the
            // readback is content/identity verification (qkey set, next
            // hop, payload, routes, and every other parsed field — see
            // QueueFlushVerifier), not a size check, so a same-count
            // mutation aborts here, before commitDraining. The gate
            // aborts the coordinator itself; this branch only reports.
            val snapshot = queueSource.queueSnapshot()
            when (val flushed = runBlocking {
                CutoverFlushGate.flushOrAbort(
                    coordinator, queueSource, snapshot, System.currentTimeMillis()
                )
            }) {
                is FlushResult.Verified -> Unit
                is FlushResult.Failed -> {
                    store.logLocal("cutover dibatalkan: verifikasi antrean gagal (${flushed.reason})")
                    return "Cutover dibatalkan: verifikasi antrean tidak cocok (${flushed.reason})."
                }
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
        logQuarantinedItems(drained.quarantinedItems)
        if (leftover > 0) {
            // R08: captures that landed after the drain snapshot (or
            // after commitDraining, unmarked by the preflight) would
            // strand in the legacy queue — the sender is gated off in
            // WORKER_ONLY and the worker only claims Room rows. Re-drain
            // them instead of leaving them for manual handling.
            store.logLocal(redrainLeftoversAfterCommit(queueSource))
        }
        return summary
    }

    /**
     * Logs one line per quarantined item (P2): the aggregate count
     * in the summary cannot be acted on — the operator needs the
     * `dedupe_key` + reason per item. Capped at 10 lines; the
     * durable per-item records in Room hold the full set.
     */
    private fun logQuarantinedItems(items: List<QuarantinedItemInfo>) {
        if (items.isEmpty()) return
        val shown = items.take(10)
        for (item in shown) {
            store.logLocal("cutover karantina: ${item.dedupeKey} (${item.reason})")
        }
        if (items.size > shown.size) {
            store.logLocal("cutover karantina: dan ${items.size - shown.size} item lain")
        }
    }

    /**
     * R08: re-drains legacy-queue leftovers after the `WORKER_ONLY`
     * commit — captures that landed after the drain snapshot (or
     * after `commitDraining`, unmarked by the preflight). Runs the
     * privacy preflight first (the fail-closed inspector would
     * quarantine never-preflighted items), then the coordinator's
     * [CutoverCoordinator.redrainLeftovers], all under the sender
     * lock so no capture can land unmarked mid-redrain. On success
     * with imported items, requests another sync so the worker
     * picks them up promptly. Returns a log line describing the
     * outcome; never throws (a failure leaves the leftovers visible
     * in the queue + `lastFailure`, exactly as before R08).
     */
    private fun redrainLeftoversAfterCommit(queueSource: LegacyQueueSnapshotSource): String {
        val locked = try {
            SenderLock.lock.tryLock(45, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!locked) {
            val msg = "cutover redrain dibatalkan: antrean sibuk; " +
                "${store.queueSnapshot().size} item tersisa di antrean legacy"
            store.setLastFailure(msg)
            return "PERHATIAN $msg"
        }
        try {
            val now = System.currentTimeMillis()
            val preflight = CutoverPrivacyPreflight().run(queueSource, now)
            if (!preflight.ok) {
                val msg = "cutover redrain dibatalkan: pemeriksaan privasi gagal " +
                    "(${preflight.reason}); item tersisa di antrean legacy"
                store.setLastFailure(msg)
                return "PERHATIAN $msg"
            }
            val redrained = runBlocking { coordinator.redrainLeftovers(GateBackedInspector, now) }
            if (!redrained.ok) {
                val msg = "cutover redrain gagal: ${redrained.error ?: "tidak diketahui"}; " +
                    "item tersisa di antrean legacy"
                store.setLastFailure(msg)
                return "PERHATIAN $msg"
            }
            if (redrained.imported > 0) SyncScheduler.requestSync(this)
            logQuarantinedItems(redrained.quarantinedItems)
            val remaining = store.queueSnapshot().size
            return "cutover redrain: ${redrained.imported} item tersisa dimigrasikan " +
                "(${redrained.removed} dihapus dari antrean, " +
                "${redrained.quarantined} dikarantina); $remaining item masih di antrean " +
                "(karantina/rusak/kedaluwarsa)"
        } finally {
            SenderLock.lock.unlock()
        }
    }
}
