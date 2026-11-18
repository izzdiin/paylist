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
