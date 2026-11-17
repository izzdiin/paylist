package com.netter.paylist.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.migration.Inspection
import com.netter.paylist.migration.LegacyQueueInspector
import com.netter.paylist.migration.LegacyQueueMigration
import com.netter.paylist.migration.LegacyQkey
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The legacy-queue import against REAL Room (design §8.2, B→C gate
 * alongside T09/T10/T20): the schema exists, the import writes
 * events + deliveries, re-import is idempotent, and quarantined
 * items are never imported.
 *
 * EXECUTION NOTE: this test needs a device or an emulator. It
 * COMPILES on laptopgrt2 (`compileDebugAndroidTestKotlin`) but
 * cannot execute there — there is no device/emulator on that box.
 * Do not report it as "passing" from a compile-only gate.
 */
@RunWith(AndroidJUnit4::class)
class OutboxMigrationTest {

    private lateinit var db: PaylistDatabase
    private lateinit var migration: LegacyQueueMigration

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PaylistDatabase::class.java).build()
        migration = LegacyQueueMigration(
            db.eventDao(),
            db.deliveryDao(),
            db.attemptDao(),
            db.promotionReceiptDao(),
            db.transactionRunner(),
        )
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun schemaHasAllElevenTables() {
        val cursor = db.openHelper.readableDatabase.query(
            SimpleSQLiteQuery("SELECT name FROM sqlite_master WHERE type = 'table'"),
        )
        val tables = mutableSetOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                tables.add(it.getString(0))
            }
        }
        val expected = setOf(
            "events",
            "deliveries",
            "delivery_attempts",
            "collector_state",
            "capture_staging",
            "promotion_receipts",
            "order_registry",
            "match_results",
            "classification_results",
            "registry_imports",
            "export_audit",
        )
        assertTrue("missing tables: ${expected - tables}", tables.containsAll(expected))
    }

    @Test
    fun importWritesEventsAndDeliveries() = runBlocking {
        val items = listOf(queueItem("outbox-a"), queueItem("outbox-b"))
        val snapshot = migration.snapshot(items, NOW)
        val partitioned = migration.partition(snapshot, inspectorFor())
        val report = migration.import(partitioned, NOW)
        assertEquals(2, report.eventsImported)
        assertEquals(2, report.deliveriesImported)
        assertEquals(0, report.duplicatesSkipped)
        assertEquals(2, db.eventDao().count())
        for (item in items) {
            val delivery = db.deliveryDao().getByLegacyQkey(item.optString("qkey"))
            assertNotNull(delivery)
            assertEquals(DeliveryState.PENDING, delivery!!.state)
            assertNotNull(db.eventDao().getById(delivery.eventId))
        }
    }

    @Test
    fun reimportIsIdempotent() = runBlocking {
        val items = listOf(queueItem("outbox-c"), queueItem("outbox-d"))
        val snapshot = migration.snapshot(items, NOW)
        val partitioned = migration.partition(snapshot, inspectorFor())
        migration.import(partitioned, NOW)
        val second = migration.import(partitioned, NOW)
        assertEquals(0, second.eventsImported)
        assertEquals(0, second.deliveriesImported)
        assertEquals(2, second.duplicatesSkipped)
        assertEquals(2, db.eventDao().count())
        // The first export_ref is preserved (F65).
        val firstExportRefs = items.map { item ->
            db.deliveryDao().getByLegacyQkey(item.optString("qkey"))!!
                .let { db.eventDao().getById(it.eventId)!!.exportRef }
        }
        migration.import(partitioned, NOW)
        val afterThird = items.map { item ->
            db.deliveryDao().getByLegacyQkey(item.optString("qkey"))!!
                .let { db.eventDao().getById(it.eventId)!!.exportRef }
        }
        assertEquals(firstExportRefs, afterThird)
    }

    @Test
    fun quarantinedItemsAreNeverImported() = runBlocking {
        val clean = queueItem("outbox-e")
        val quarantined = queueItem("outbox-f")
        val snapshot = migration.snapshot(listOf(clean, quarantined), NOW)
        val partitioned = migration.partition(
            snapshot,
            inspectorFor(setOf(quarantined.optString("qkey"))),
        )
        val report = migration.import(partitioned, NOW)
        assertEquals(1, report.eventsImported)
        assertEquals(1, report.quarantinedExcluded)
        assertNull(db.deliveryDao().getByLegacyQkey(quarantined.optString("qkey")))
        assertEquals(1, db.eventDao().count())
    }

    @Test
    fun expiredHeartbeatIsSkippedNotMigrated() = runBlocking {
        val currentBucket = NOW / 900_000L
        val expired = JSONObject().apply {
            put("qkey", LegacyQkey.build("heartbeat-${currentBucket - 3}", "chain", listOf("route-1")))
            put("kind", "chain")
            put("route_ids", JSONArray(listOf("route-1")))
            put("next", 0)
            put("attempts", 0)
            put(
                "payload",
                JSONObject().apply {
                    put("type", "heartbeat")
                    put("device", "moto-g57")
                    put("dedupe_key", "heartbeat-${currentBucket - 3}")
                },
            )
        }
        val snapshot = migration.snapshot(listOf(expired), NOW)
        assertEquals(1, snapshot.expiredSkippedQkeys.size)
        assertTrue(snapshot.items.isEmpty())
    }

    companion object {
        const val NOW: Long = 1_795_000_000_000L
    }

    private fun inspectorFor(quarantinedQkeys: Set<String> = emptySet()): LegacyQueueInspector =
        LegacyQueueInspector { item ->
            if (item.qkey in quarantinedQkeys) Inspection.QUARANTINED else Inspection.IMPORTABLE
        }

    private fun queueItem(tag: String): JSONObject {
        val dedupeKey = "com.gojek.gopaymerchant|1795000000000|100800|$tag"
        val qkey = LegacyQkey.build(dedupeKey, "chain", listOf("route-1", "route-2"))
        return JSONObject().apply {
            put("qkey", qkey)
            put("kind", "chain")
            put("route_ids", JSONArray(listOf("route-1", "route-2")))
            put("next", 0)
            put("attempts", 0)
            put(
                "payload",
                JSONObject().apply {
                    put("type", "payment")
                    put("device", "moto-g57")
                    put("pkg", "com.gojek.gopaymerchant")
                    put("amount", 100_800L)
                    put("title", "GoPay")
                    put("text", "Pembayaran Rp100.800 diterima")
                    put("notif_time", "2026-11-18T03:00:00Z")
                    put("dedupe_key", dedupeKey)
                },
            )
        }
    }
}
