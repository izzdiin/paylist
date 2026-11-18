package com.netter.paylist.delivery

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * `SyncScheduler` request shapes and policies. The builders are
 * pure functions (no `WorkManager` instance needed), so these
 * assertions run in JVM unit tests.
 *
 * The KEEP trap (§6) is pinned in two halves: this file asserts the
 * immediate-sync policy IS `KEEP` (never cancel a running worker),
 * and `SyncWorkerTest` asserts the other half — the worker
 * self-reschedules when due items remain (`processDueWork` returns
 * true). Together, a request arriving mid-run is never lost.
 */
class SyncSchedulerTest {

    @Test
    fun syncRequest_requiresNetworkAndExponentialBackoff() {
        val request = SyncScheduler.buildSyncRequest()
        val spec = request.workSpec
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(
            TimeUnit.MINUTES.toMillis(SyncScheduler.BACKOFF_MINUTES),
            spec.backoffDelayDuration
        )
        assertFalse(spec.isPeriodic)
        assertEquals(SyncWorker::class.java.name, spec.workerClassName)
    }

    @Test
    fun periodicRequest_is15MinWithReconcileFlag() {
        val request = SyncScheduler.buildPeriodicRequest()
        val spec = request.workSpec
        assertTrue(spec.isPeriodic)
        assertEquals(TimeUnit.MINUTES.toMillis(15), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertTrue(spec.input.getBoolean(SyncScheduler.KEY_RECONCILE, false))
        assertEquals(SyncWorker::class.java.name, spec.workerClassName)
    }

    @Test
    fun heartbeatRequest_is15MinWithoutReconcileFlag() {
        val request = SyncScheduler.buildHeartbeatRequest()
        val spec = request.workSpec
        assertTrue(spec.isPeriodic)
        assertEquals(TimeUnit.MINUTES.toMillis(15), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertFalse(spec.input.getBoolean(SyncScheduler.KEY_RECONCILE, false))
        assertEquals(HeartbeatWorker::class.java.name, spec.workerClassName)
    }

    @Test
    fun syncPolicy_isKeep() {
        // KEEP, never REPLACE: a sync request must not cancel a
        // worker mid-flight. The worker self-reschedules instead.
        assertEquals(ExistingWorkPolicy.KEEP, SyncScheduler.SYNC_EXISTING_POLICY)
    }

    @Test
    fun workNames_areDistinct() {
        assertNotEquals(SyncScheduler.SYNC_WORK_NAME, SyncScheduler.PERIODIC_WORK_NAME)
        assertNotEquals(SyncScheduler.SYNC_WORK_NAME, SyncScheduler.HEARTBEAT_WORK_NAME)
        assertNotEquals(SyncScheduler.PERIODIC_WORK_NAME, SyncScheduler.HEARTBEAT_WORK_NAME)
    }
}
