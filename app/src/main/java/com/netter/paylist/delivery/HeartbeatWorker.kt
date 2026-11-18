package com.netter.paylist.delivery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager shell around [HeartbeatProcessor] (design §6, C18).
 * Creates due heartbeat events + deliveries, then requests a sync
 * run when anything was created. Best-effort: a failed run simply
 * means the next periodic run (15 min) creates the next bucket's
 * heartbeat; nothing is retried in-band.
 */
class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters,
    private val processor: HeartbeatProcessor,
    private val onHeartbeatsCreated: () -> Unit = {},
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val created = processor.createDueHeartbeats()
            if (created > 0) onHeartbeatsCreated()
            Result.success()
        } catch (e: Exception) {
            Result.success() // best-effort; the next periodic run retries
        }
    }
}
