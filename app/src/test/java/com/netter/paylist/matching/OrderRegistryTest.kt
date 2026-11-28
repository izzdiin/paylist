package com.netter.paylist.matching

import com.netter.paylist.FakeOrderRegistryDao
import com.netter.paylist.FakeRegistryImportMetadataDao
import com.netter.paylist.FakeStore
import com.netter.paylist.data.entity.OrderRegistryEntity
import com.netter.paylist.data.entity.RegistryFreshness
import com.netter.paylist.data.entity.RegistrySnapshotStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage D D2b: the versioned order registry (design §3.1.4/§4.6,
 * F52).
 *
 * F52 — registry snapshot: invalid import rollback, per-merchant
 * atomic replacement, expired/unknown-freshness status, later
 * import re-evaluation only by explicit operator action,
 * history/version preserved, legacy delivery unaffected.
 */
class OrderRegistryTest {

    companion object {
        const val NOW = 1_794_960_000_000L
        const val MERCHANT_A = "merchant-a"
        const val MERCHANT_B = "merchant-b"
    }

    private class Harness {
        val store = FakeStore()
        val registryDao = FakeOrderRegistryDao(store)
        val metadataDao = FakeRegistryImportMetadataDao(store)
        var now = NOW
        val registry = OrderRegistry(registryDao, metadataDao, clock = { now })

        fun row(
            merchantId: String = MERCHANT_A,
            orderNumber: String,
            version: String = "v1",
            amount: Long = 100800L,
            currency: String = "IDR",
            direction: String = "in",
            status: String = "open",
        ) = OrderRegistryEntity(
            merchantId = merchantId,
            orderNumber = orderNumber,
            customerId = null,
            amount = amount,
            currency = currency,
            direction = direction,
            status = status,
            registryVersion = version,
            importedAtMs = now,
        )
    }

    @Test
    fun f52_validImport_lookupWorks() = runBlocking {
        val h = Harness()
        val result = h.registry.importSnapshot(
            MERCHANT_A, "v1", "operator",
            listOf(h.row(orderNumber = "INV-1"), h.row(orderNumber = "INV-2")),
        )
        assertTrue(result is OrderRegistry.ImportResult.Imported)
        assertEquals("INV-1", h.registry.lookup(MERCHANT_A, "INV-1")?.orderNumber)
        assertEquals("INV-2", h.registry.lookup(MERCHANT_A, "INV-2")?.orderNumber)
        assertNull(h.registry.lookup(MERCHANT_A, "INV-9"))
        assertEquals("v1", h.registry.activeSnapshot(MERCHANT_A)?.registryVersion)
        assertEquals(
            RegistrySnapshotStatus.ACTIVE,
            h.registry.activeSnapshot(MERCHANT_A)?.status,
        )
    }

    @Test
    fun f52_invalidImport_rollback_priorVersionIntact() = runBlocking {
        val h = Harness()
        h.registry.importSnapshot(
            MERCHANT_A, "v1", "operator", listOf(h.row(orderNumber = "INV-1")),
        )
        // Duplicate order number inside the snapshot.
        val duplicate = h.registry.importSnapshot(
            MERCHANT_A, "v2", "operator",
            listOf(h.row(orderNumber = "INV-2", version = "v2"), h.row(orderNumber = "INV-2", version = "v2")),
        )
        assertTrue(duplicate is OrderRegistry.ImportResult.Rejected)
        // Wrong merchant on a row.
        val wrongMerchant = h.registry.importSnapshot(
            MERCHANT_A, "v2", "operator",
            listOf(h.row(merchantId = MERCHANT_B, orderNumber = "INV-2", version = "v2")),
        )
        assertTrue(wrongMerchant is OrderRegistry.ImportResult.Rejected)
        // Negative amount.
        val negative = h.registry.importSnapshot(
            MERCHANT_A, "v2", "operator",
            listOf(h.row(orderNumber = "INV-2", version = "v2", amount = -1)),
        )
        assertTrue(negative is OrderRegistry.ImportResult.Rejected)
        // Bad direction.
        val badDirection = h.registry.importSnapshot(
            MERCHANT_A, "v2", "operator",
            listOf(h.row(orderNumber = "INV-2", version = "v2", direction = "sideways")),
        )
        assertTrue(badDirection is OrderRegistry.ImportResult.Rejected)

        // The prior version is intact: v1 rows + metadata untouched.
        assertEquals("v1", h.registry.activeSnapshot(MERCHANT_A)?.registryVersion)
        assertEquals("INV-1", h.registry.lookup(MERCHANT_A, "INV-1")?.orderNumber)
        assertNull(h.registry.lookup(MERCHANT_A, "INV-2"))
        assertEquals(1, h.registry.history(MERCHANT_A).size)
    }

    @Test
    fun f52_perMerchantAtomicReplacement_otherMerchantUntouched() = runBlocking {
        val h = Harness()
        h.registry.importSnapshot(
            MERCHANT_A, "v1", "operator", listOf(h.row(orderNumber = "INV-1")),
        )
        h.registry.importSnapshot(
            MERCHANT_B, "v1", "operator",
            listOf(h.row(merchantId = MERCHANT_B, orderNumber = "INV-1")),
        )
        // Replace merchant A's snapshot: INV-1 is gone, INV-2 is in.
        h.now += 1_000L
        val result = h.registry.importSnapshot(
            MERCHANT_A, "v2", "operator", listOf(h.row(orderNumber = "INV-2", version = "v2")),
        )
        assertTrue(result is OrderRegistry.ImportResult.Imported)
        assertNull(h.registry.lookup(MERCHANT_A, "INV-1"))
        assertEquals("INV-2", h.registry.lookup(MERCHANT_A, "INV-2")?.orderNumber)
        // Merchant B is untouched (same order number, independent).
        assertEquals("INV-1", h.registry.lookup(MERCHANT_B, "INV-1")?.orderNumber)
        assertEquals("v1", h.registry.activeSnapshot(MERCHANT_B)?.registryVersion)
        // History preserved: v1 superseded, v2 active.
        val history = h.registry.history(MERCHANT_A)
        assertEquals(2, history.size)
        assertEquals("v2", history[0].registryVersion)
        assertEquals(RegistrySnapshotStatus.ACTIVE, history[0].status)
        assertEquals("v1", history[1].registryVersion)
        assertEquals(RegistrySnapshotStatus.SUPERSEDED, history[1].status)
    }

    @Test
    fun f52_expiredSnapshot_unusable() = runBlocking {
        val h = Harness()
        h.registry.importSnapshot(
            MERCHANT_A, "v1", "operator",
            listOf(h.row(orderNumber = "INV-1")),
            validUntilMs = NOW + 3_600_000L,
        )
        assertTrue(h.registry.isSnapshotUsable(MERCHANT_A))
        assertNotNull(h.registry.lookup(MERCHANT_A, "INV-1"))
        // Past the expiry: unusable, lookup returns null (held for review).
        h.now = NOW + 3_600_000L
        assertFalse(h.registry.isSnapshotUsable(MERCHANT_A))
        assertNull(h.registry.lookup(MERCHANT_A, "INV-1"))
    }

    @Test
    fun f52_unknownFreshness_unusable() = runBlocking {
        val h = Harness()
        h.registry.importSnapshot(
            MERCHANT_A, "v1", "operator",
            listOf(h.row(orderNumber = "INV-1")),
            freshness = RegistryFreshness.UNVERIFIED,
        )
        assertFalse(h.registry.isSnapshotUsable(MERCHANT_A))
        assertNull(h.registry.lookup(MERCHANT_A, "INV-1"))
        // The metadata still records the import (auditable).
        assertEquals("v1", h.registry.activeSnapshot(MERCHANT_A)?.registryVersion)
    }

    @Test
    fun f52_snapshotHash_deterministic() = runBlocking {
        val h = Harness()
        val rows = listOf(h.row(orderNumber = "INV-2"), h.row(orderNumber = "INV-1"))
        val hash1 = h.registry.snapshotHash(rows)
        val hash2 = h.registry.snapshotHash(rows.reversed())
        assertEquals("row order must not change the hash", hash1, hash2)
        val different = h.registry.snapshotHash(
            listOf(h.row(orderNumber = "INV-1", amount = 999L)),
        )
        assertTrue(hash1 != different)
        assertEquals(64, hash1.length)
    }

    @Test
    fun f52_laterImport_doesNotReevaluate_matchResultsUntouched() = runBlocking {
        // A later import never silently re-evaluates historical
        // attributions: this test pins the negative — the import
        // path touches only the registry tables, never
        // `match_results`. (Re-evaluation is an explicit operator
        // action in the D3 review surface.)
        val h = Harness()
        h.registry.importSnapshot(
            MERCHANT_A, "v1", "operator", listOf(h.row(orderNumber = "INV-1")),
        )
        val before = h.store.matchResults.toMap()
        h.registry.importSnapshot(
            MERCHANT_A, "v2", "operator", listOf(h.row(orderNumber = "INV-2", version = "v2")),
        )
        assertEquals(before, h.store.matchResults.toMap())
    }
}
