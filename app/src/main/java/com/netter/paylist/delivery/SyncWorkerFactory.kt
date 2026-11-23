package com.netter.paylist.delivery

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.netter.paylist.data.PaylistDatabase
import com.netter.paylist.security.SecretProvider

/**
 * Builds [SyncWorker] and [HeartbeatWorker] with their real
 * dependencies (design §6). Installed by `PaylistApplication` via
 * `Configuration.Provider`; WorkManager instantiates workers
 * through this factory instead of reflection.
 */
class SyncWorkerFactory(
    private val db: PaylistDatabase,
    private val routes: RouteSource,
    private val secrets: SecretProvider,
    private val transport: TransportClient = HttpTransportClient(),
    /** Stage D §7.3A: the raw-snapshot store (null = B disabled, A′ only). */
    private val rawSnapshotStore: com.netter.paylist.collector.RawSnapshotStore? = null,
    /** Stage D §7.5/§7.3: per-route consent records (null = B unverifiable). */
    private val consentStore: com.netter.paylist.collector.ConsentStore? = null,
    /**
     * F43 B14: automatic A′ fallback on a recorder route requires
     * this to be true. Defaults FALSE in production wiring
     * (fail-closed) — set true only when B14 is confirmed against
     * live `catat.php` semantics.
     */
    private val recorderGatePassed: Boolean = false,
    /** Called when an authorised A′ fallback is dispatched (per-route counter). */
    private val onFallbackDispatched: (routeId: String) -> Unit = {},
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        val transactions = db.transactionRunner()
        return when (workerClassName) {
            SyncWorker::class.java.name -> SyncWorker(
                appContext,
                workerParameters,
                DeliveryProcessor(
                    deliveryDao = db.deliveryDao(),
                    eventDao = db.eventDao(),
                    attemptDao = db.attemptDao(),
                    transactions = transactions,
                    routes = routes,
                    secrets = secrets,
                    transport = transport,
                    rawSnapshotStore = rawSnapshotStore,
                    consentStore = consentStore,
                    recorderGatePassed = recorderGatePassed,
                    onFallbackDispatched = onFallbackDispatched,
                    captureLogDao = db.captureLogDao(),
                ),
                onWorkRemaining = { SyncScheduler.requestSync(appContext) },
            )
            HeartbeatWorker::class.java.name -> HeartbeatWorker(
                appContext,
                workerParameters,
                HeartbeatProcessor(
                    deliveryDao = db.deliveryDao(),
                    eventDao = db.eventDao(),
                    transactions = transactions,
                    routes = routes,
                    secrets = secrets,
                ),
                onHeartbeatsCreated = { SyncScheduler.requestSync(appContext) },
            )
            else -> null
        }
    }
}
