package com.netter.paylist.collector

import com.netter.paylist.FakeCollectorStateDao
import com.netter.paylist.FakeStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage D D2: consent records (design §7.5 C22(l), §7.3 P6 opt-in;
 * fixtures F23/F26 at the record level, F43 B20 fence protocol).
 *
 * The claim-join behaviour (epoch mismatch ⇒ BLOCKED) is owned by
 * `DeliveryLeaseTest` (androidTest, real Room); the fence race
 * against a live send is owned by the processor/sender claim-path
 * tests with a fake-transport latch. This file owns the record
 * layer: grants, revokes, epoch bumps, P6 opt-in validity, and
 * the fence protocol at the store level.
 */
class ConsentTest {

    companion object {
        const val ROUTE = "route-v2new"
        const val NOW = 1_760_000_000_000L
    }

    private lateinit var dao: FakeCollectorStateDao
    private lateinit var store: ConsentStore

    @Before
    fun setUp() {
        dao = FakeCollectorStateDao(FakeStore())
        store = ConsentStore(dao, clock = { NOW })
    }

    @Test
    fun noRecord_epochMinusOne_optInInvalid() = runBlocking {
        assertNull(store.get(ROUTE))
        assertEquals(-1L, store.epoch(ROUTE))
        assertFalse("no record ⇒ no raw forwarding, fail-closed", store.isRawOptInValid(ROUTE, NOW))
    }

    @Test
    fun grant_createsRecord_epochZero_noRawOptIn() = runBlocking {
        store.grant(ROUTE)
        val record = store.get(ROUTE)!!
        assertTrue(record.granted)
        assertEquals(0L, record.epoch)
        assertEquals(ConsentStore.DISCLOSURE_VERSION, record.disclosureVersion)
        assertFalse("grant alone never grants the P6 opt-in", record.rawOptIn)
        assertFalse(store.isRawOptInValid(ROUTE, NOW))
    }

    @Test
    fun revoke_bumpsEpoch_clearsOptIn() = runBlocking {
        store.grant(ROUTE)
        store.setRawOptIn(ROUTE, true, NOW + 3_600_000L)
        assertTrue(store.isRawOptInValid(ROUTE, NOW))
        val epochBefore = store.epoch(ROUTE)

        store.revoke(ROUTE)

        val record = store.get(ROUTE)!!
        assertFalse(record.granted)
        assertEquals(epochBefore + 1, record.epoch)
        assertFalse("revocation withdraws raw forwarding too", record.rawOptIn)
        assertFalse(store.isRawOptInValid(ROUTE, NOW))
    }

    @Test
    fun regrant_bumpsEpoch_keepsOptInCleared() = runBlocking {
        store.grant(ROUTE)
        store.setRawOptIn(ROUTE, true, NOW + 3_600_000L)
        store.revoke(ROUTE)
        store.grant(ROUTE)

        val record = store.get(ROUTE)!!
        assertTrue(record.granted)
        assertFalse("re-grant does not restore the raw opt-in", record.rawOptIn)
        assertFalse(store.isRawOptInValid(ROUTE, NOW))
    }

    @Test
    fun optIn_requiresExpiry() = runBlocking {
        store.grant(ROUTE)
        store.setRawOptIn(ROUTE, true, null)
        assertFalse(
            "an opt-in without an expiry is invalid (fail-closed)",
            store.isRawOptInValid(ROUTE, NOW),
        )
    }

    @Test
    fun optIn_expiredAtCapture_invalid() = runBlocking {
        store.grant(ROUTE)
        store.setRawOptIn(ROUTE, true, NOW - 1)
        assertFalse(store.isRawOptInValid(ROUTE, NOW))
    }

    @Test
    fun optIn_validBeforeExpiry_invalidAfter() = runBlocking {
        store.grant(ROUTE)
        store.setRawOptIn(ROUTE, true, NOW + 3_600_000L)
        assertTrue(store.isRawOptInValid(ROUTE, NOW))
        assertTrue(store.isRawOptInValid(ROUTE, NOW + 3_599_999L))
        assertFalse(store.isRawOptInValid(ROUTE, NOW + 3_600_000L))
    }

    @Test
    fun optInChange_bumpsEpoch() = runBlocking {
        store.grant(ROUTE)
        val epochBefore = store.epoch(ROUTE)
        store.setRawOptIn(ROUTE, true, NOW + 3_600_000L)
        assertEquals(epochBefore + 1, store.epoch(ROUTE))
    }

    @Test
    fun routes_areIndependent() = runBlocking {
        store.grant(ROUTE)
        store.setRawOptIn(ROUTE, true, NOW + 3_600_000L)
        store.grant("route-other")
        assertTrue(store.isRawOptInValid(ROUTE, NOW))
        assertFalse(store.isRawOptInValid("route-other", NOW))
        store.revoke(ROUTE)
        assertTrue("revoking one route leaves the other granted", store.get("route-other")!!.granted)
    }

    // --- F43 B20 fence protocol (store level) ---
    // The full race (fake transport latch between the final
    // admission read and the first write) is owned by the
    // claim-path tests; here the protocol primitive is pinned:
    // a revocation bumps the fence, so a claim that recorded the
    // old generation observes the change.

    @Test
    fun b20_fence_revokeBumps_generationChanges() {
        val snapshots = RawSnapshotStore()
        val recorded = snapshots.fence(ROUTE)
        snapshots.dropRoute(ROUTE)
        assertTrue(
            "a claim that recorded $recorded must observe the revoke",
            snapshots.fence(ROUTE) != recorded,
        )
    }

    @Test
    fun b20_fence_noRevoke_generationStable() {
        val snapshots = RawSnapshotStore()
        val recorded = snapshots.fence(ROUTE)
        assertEquals(recorded, snapshots.fence(ROUTE))
    }
}
