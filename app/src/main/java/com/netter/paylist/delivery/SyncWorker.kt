package com.netter.paylist.delivery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager shell around [DeliveryProcessor] (design §5.2, §6).
 * All delivery logic lives in the processor; this class only maps
 * worker lifecycle to processor calls.
 *
 * Periodic runs carry `reconcile=true` ([SyncScheduler]): retention
 * cleanup runs once per periodic run, never on immediate runs.
 * When due items remain after a pass, the worker self-reschedules
 * through [onWorkRemaining] — the KEEP-trap fix (§6): with
 * `ExistingWorkPolicy.KEEP`, a request arriving while a worker runs
 * is ignored, so the worker itself must re-request at the end.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val processor: DeliveryProcessor,
    private val onWorkRemaining: () -> Unit = {},
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            if (inputData.getBoolean(SyncScheduler.KEY_RECONCILE, false)) {
                processor.runRetentionCleanup()
            }
            val remaining = processor.processDueWork(isStopped = { isStopped })
            if (remaining) onWorkRemaining()
            Result.success()
        } catch (e: Exception) {
            // Catastrophic failure (e.g. the database is unavailable):
            // WorkManager retries under the request's backoff policy.
            Result.retry()
        }
    }
}
