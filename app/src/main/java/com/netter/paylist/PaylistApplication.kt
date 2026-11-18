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
import com.netter.paylist.migration.CutoverMode
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
        const val CAPTURE_TO_ROOM_READY: Boolean = false
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
