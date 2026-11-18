package com.netter.paylist.delivery

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * WorkManager scheduling for delivery (design §6).
 *
 * - Immediate sync: unique OneTimeWork with a network constraint,
 *   `ExistingWorkPolicy.KEEP` — never cancel a running worker.
 *   The KEEP trap (a request arriving while a worker runs is
 *   ignored, so newly due work after the run is suppressed) is
 *   fixed by the worker itself: [SyncWorker] re-requests a run at
 *   the end of its pass when due items remain.
 * - Periodic sync: unique periodic work (15 min minimum) carrying
 *   `reconcile=true` — retention cleanup runs once per periodic
 *   run, never on immediate runs.
 * - Periodic heartbeat: unique periodic work (15 min) for
 *   [HeartbeatWorker]; best-effort wake-up, not guaranteed.
 *
 * The request builders are separate pure functions so
 * `SyncSchedulerTest` can pin the constraints, backoff, input data
 * and policies without a `WorkManager` instance (unavailable in
 * JVM unit tests without Robolectric).
 */
object SyncScheduler {
    const val SYNC_WORK_NAME = "paylist-sync"
    const val PERIODIC_WORK_NAME = "paylist-periodic-sync"
    const val HEARTBEAT_WORK_NAME = "paylist-heartbeat"

    /** Input-data key: this run performs periodic reconciliation. */
    const val KEY_RECONCILE = "reconcile"

    /** Immediate sync never cancels a running worker (see KDoc). */
    val SYNC_EXISTING_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

    /** Periodic work keeps the existing schedule when re-requested. */
    val PERIODIC_EXISTING_POLICY: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP

    /** Backoff for a catastrophically failed sync run (WorkManager-level). */
    const val BACKOFF_MINUTES = 1L

    fun networkConstraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun buildSyncRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

    fun buildPeriodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .setInputData(workDataOf(KEY_RECONCILE to true))
            .build()

    fun buildHeartbeatRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .build()

    /** Request an immediate sync run (unique, KEEP). */
    fun requestSync(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_WORK_NAME, SYNC_EXISTING_POLICY, buildSyncRequest()
        )
    }

    /** Schedule periodic reconciliation + sync (unique, KEEP). */
    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME, PERIODIC_EXISTING_POLICY, buildPeriodicRequest()
        )
    }

    /** Schedule the periodic heartbeat (unique, KEEP). */
    fun scheduleHeartbeat(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HEARTBEAT_WORK_NAME, PERIODIC_EXISTING_POLICY, buildHeartbeatRequest()
        )
    }

    /** Cancel all delivery work (used on operator disable / uninstall paths). */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(SYNC_WORK_NAME)
        wm.cancelUniqueWork(PERIODIC_WORK_NAME)
        wm.cancelUniqueWork(HEARTBEAT_WORK_NAME)
    }
}
