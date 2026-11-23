package com.netter.paylist.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage D D2: the §7.3A raw-snapshot store (file plan §B).
 *
 * Capacity 50, TTL 2 h, LRU eviction, take-at-claim handoff,
 * return-to-map on claim failure, route drop + fence bump on
 * revocation. Pure (no Android, no I/O); runs on the JVM.
 */
class RawSnapshotStoreTest {

    private fun snapshot(
        dedupeKey: String = "key-1",
        routeId: String = "route-a",
        epoch: Long = 0,
        // Fresh capture: the store's real clock would treat the old
        // 1_000 ms default as TTL-expired on every take.
        capturedAtMs: Long = System.currentTimeMillis(),
    ) = RawSnapshotStore.RawSnapshot(
        dedupeKey = dedupeKey,
        routeId = routeId,
        consentEpoch = epoch,
        title = "Pembayaran berhasil",
        text = "Kode OTP 482913. Pembayaran Rp100.800 berhasil",
        capturedAtMs = capturedAtMs,
    )

    @Test
    fun putAndTake_takeRemoves_noSecondClaim() {
        val store = RawSnapshotStore()
        store.put(snapshot())
        assertTrue(store.contains("key-1", "route-a", 0))
        val taken = store.take("key-1", "route-a", 0)
        assertEquals("key-1", taken?.dedupeKey)
        assertNull("a second claim must find nothing", store.take("key-1", "route-a", 0))
        assertFalse(store.contains("key-1", "route-a", 0))
    }

    @Test
    fun takeWrongEpoch_returnsNull() {
        val store = RawSnapshotStore()
        store.put(snapshot(epoch = 3))
        assertNull(store.take("key-1", "route-a", 2))
        assertTrue("the snapshot stays for its own epoch", store.contains("key-1", "route-a", 3))
    }

    @Test
    fun takeWrongRoute_returnsNull() {
        val store = RawSnapshotStore()
        store.put(snapshot(routeId = "route-a"))
        assertNull(store.take("key-1", "route-b", 0))
    }

    @Test
    fun capacity_overflow_evictsLru() {
        val store = RawSnapshotStore(capacity = 3)
        store.put(snapshot(dedupeKey = "k1"))
        store.put(snapshot(dedupeKey = "k2"))
        store.put(snapshot(dedupeKey = "k3"))
        // Touch k1 (most recently used) — k2 becomes the LRU victim.
        store.peek("k1", "route-a", 0)
        val evicted = store.put(snapshot(dedupeKey = "k4"))
        assertEquals("k2", evicted?.dedupeKey)
        assertEquals(3, store.size())
        assertNull(store.take("k2", "route-a", 0))
        assertEquals("k1", store.take("k1", "route-a", 0)?.dedupeKey)
    }

    @Test
    fun ttlExpiry_takeReturnsNull_evictExpiredReturnsThem() {
        var now = 1_000L
        val store = RawSnapshotStore(clock = { now })
        store.put(snapshot(dedupeKey = "k1", capturedAtMs = 1_000L))
        store.put(snapshot(dedupeKey = "k2", capturedAtMs = 1_000L))
        now = 1_000L + RawSnapshotStore.DEFAULT_TTL_MS + 1
        assertNull("expired snapshot is dropped, never returned", store.take("k1", "route-a", 0))
        val expired = store.evictExpired(now)
        assertEquals(listOf("k2"), expired.map { it.dedupeKey })
        assertEquals(0, store.size())
    }

    @Test
    fun returnSnapshot_afterFailedClaim_isClaimableAgain() {
        val store = RawSnapshotStore()
        store.put(snapshot())
        val taken = store.take("key-1", "route-a", 0)!!
        store.returnSnapshot(taken)
        assertEquals("key-1", store.take("key-1", "route-a", 0)?.dedupeKey)
    }

    @Test
    fun dropRoute_dropsSnapshotsAndBumpsFence() {
        val store = RawSnapshotStore()
        store.put(snapshot(dedupeKey = "k1", routeId = "route-a"))
        store.put(snapshot(dedupeKey = "k2", routeId = "route-a"))
        store.put(snapshot(dedupeKey = "k3", routeId = "route-b"))
        val fenceBefore = store.fence("route-a")
        val dropped = store.dropRoute("route-a")
        assertEquals(listOf("k1", "k2"), dropped.map { it.dedupeKey }.sorted())
        assertEquals(fenceBefore + 1, store.fence("route-a"))
        assertEquals("the other route is untouched", 1, store.size())
        assertEquals(0L, store.fence("route-b"))
    }

    @Test
    fun fence_bumpIsVisibleToVolatileRead() {
        val store = RawSnapshotStore()
        assertEquals(0L, store.fence("route-a"))
        store.bumpFence("route-a")
        assertEquals(1L, store.fence("route-a"))
        store.bumpFence("route-a")
        assertEquals(2L, store.fence("route-a"))
    }
}
