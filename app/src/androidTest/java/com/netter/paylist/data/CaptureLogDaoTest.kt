package com.netter.paylist.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.CaptureLogEntity
import com.netter.paylist.data.entity.CaptureOutcome
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The `capture_log` DAO against REAL Room (addendum 06, LH-04;
 * Stage D D4): insert/read, the history filter SQL (LH-02), the
 * 90-day unlinked cleanup (LH-06), and the FK SET NULL behaviour
 * on event deletion.
 *
 * EXECUTION NOTE: this test needs a device or an emulator. It
 * COMPILES on laptopgrt2 (`compileDebugAndroidTestKotlin`) but
 * cannot execute there — there is no device/emulator on that box.
 * Do not report it as "passing" from a compile-only gate.
 */
@RunWith(AndroidJUnit4::class)
class CaptureLogDaoTest {

    companion object {
        const val PKG = "com.gojek.gopaymerchant"
        const val NOW = 1_794_960_000_000L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }

    private lateinit var db: PaylistDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PaylistDatabase::class.java).build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun row(
        outcome: String,
        eventId: String? = null,
        amountMinor: Long? = null,
        postTimeMs: Long = NOW,
        capturedAtMs: Long = NOW,
        sourcePackage: String = PKG,
        titleSnippet: String? = null,
    ) = CaptureLogEntity(
        captureId = UUID.randomUUID().toString(),
        sourcePackage = sourcePackage,
        postTimeMs = postTimeMs,
        capturedAtMs = capturedAtMs,
        outcome = outcome,
        titleSnippet = titleSnippet,
        amountMinor = amountMinor,
        eventId = eventId,
        contentSha256 = null,
    )

    private suspend fun event(dedupeKey: String): EventEntity {
        val event = EventEntity(
            eventId = UUID.randomUUID().toString(),
            sourceType = "android_notification",
            sourceEventId = dedupeKey,
            deviceId = "Moto g57",
            eventType = "payment.notification",
            category = "payment",
            sourcePackage = PKG,
            occurredAtMs = NOW,
            capturedAtMs = NOW,
            dataJson = """{"amount":100800,"currency":"IDR"}""",
            metadataJson = "{}",
            legacyPayloadJson = null,
            privacyMode = "structured",
            retentionUntilMs = null,
            dedupeKey = dedupeKey,
            contentSha256 = null,
            exportRef = UUID.randomUUID().toString(),
            sourceAccountKey = null,
        )
        db.eventDao().insert(event)
        return event
    }

    private suspend fun delivery(
        event: EventEntity,
        state: DeliveryState = DeliveryState.PENDING,
        lastMatchStatus: String? = null,
    ): DeliveryEntity {
        val delivery = DeliveryEntity(
            deliveryId = UUID.randomUUID().toString(),
            eventId = event.eventId,
            kind = DeliveryKind.LEGACY_CHAIN,
            state = state,
            legacyQkey = null,
            routeIdsJson = JSONArray(listOf("r1")).toString(),
            nextRouteIndex = 0,
            attempts = 0,
            nextRetryAtMs = null,
            leaseOwner = null,
            leaseUntilMs = null,
            createdAtMs = NOW,
            updatedAtMs = NOW,
            routeStampsJson = "{}",
            lastMatchStatus = lastMatchStatus,
        )
        db.deliveryDao().insert(delivery)
        return delivery
    }

    @Test
    fun insertAndGet_roundTrips() = runBlocking {
        val inserted = row(CaptureOutcome.CAPTURED, amountMinor = 100800L, titleSnippet = "GoPay")
        db.captureLogDao().insert(inserted)

        val read = db.captureLogDao().getById(inserted.captureId)
        assertNotNull(read)
        assertEquals(CaptureOutcome.CAPTURED, read!!.outcome)
        assertEquals(100800L, read.amountMinor)
        assertEquals("GoPay", read.titleSnippet)
        assertEquals(1, db.captureLogDao().count())
    }

    @Test
    fun listHistory_filtersAndPaging() = runBlocking {
        val matched = event("k|1")
        delivery(matched, DeliveryState.ACKED, lastMatchStatus = "matched")
        db.captureLogDao().insert(
            row(CaptureOutcome.CAPTURED, eventId = matched.eventId, amountMinor = 170286L, postTimeMs = NOW),
        )
        val logged = event("k|2")
        delivery(logged, DeliveryState.ACKED, lastMatchStatus = "logged")
        db.captureLogDao().insert(
            row(CaptureOutcome.CAPTURED, eventId = logged.eventId, amountMinor = 170286L, postTimeMs = NOW + 1),
        )
        db.captureLogDao().insert(
            row(CaptureOutcome.NO_KEYWORD, postTimeMs = NOW + 2),
        )

        val dao = db.captureLogDao()
        // Amount filter (LH-T02 shape, against real SQL).
        assertEquals(
            2,
            dao.listHistory(null, null, 170286L, 170286L, null, null, null, null, null, 50, 0, null).size,
        )
        // Match-status filter.
        assertEquals(
            1,
            dao.listHistory(null, null, null, null, null, null, null, null, null, 50, 0, "matched").size,
        )
        // Outcome filter.
        assertEquals(
            1,
            dao.listHistory(null, null, null, null, null, CaptureOutcome.NO_KEYWORD, null, null, null, 50, 0, null).size,
        )
        // Delivery-state filter excludes unlinked rows.
        assertEquals(
            2,
            dao.listHistory(null, null, null, null, null, null, DeliveryState.ACKED, null, null, 50, 0, null).size,
        )
        // Route filter.
        assertEquals(
            2,
            dao.listHistory(null, null, null, null, null, null, null, "r1", null, 50, 0, null).size,
        )
        assertEquals(
            0,
            dao.listHistory(null, null, null, null, null, null, null, "rX", null, 50, 0, null).size,
        )
        // Query: exact amount.
        assertEquals(
            2,
            dao.listHistory(null, null, null, null, null, null, null, null, "170286", 50, 0, null).size,
        )
        // Paging: newest first, one per page.
        val page1 = dao.listHistory(null, null, null, null, null, null, null, null, null, 1, 0, null)
        val page2 = dao.listHistory(null, null, null, null, null, null, null, null, null, 1, 1, null)
        assertEquals(CaptureOutcome.NO_KEYWORD, page1[0].outcome)
        assertEquals(CaptureOutcome.CAPTURED, page2[0].outcome)
        assertEquals(3, dao.countHistory(null, null, null, null, null, null, null, null, null, null))
        assertEquals(listOf(PKG), dao.distinctPackages())
    }

    @Test
    fun deleteUnlinkedOlderThan_keepsLinkedRows() = runBlocking {
        val old = NOW - 91 * DAY_MS
        db.captureLogDao().insert(row(CaptureOutcome.NO_KEYWORD, capturedAtMs = old, postTimeMs = old))
        val linked = event("k|old")
        delivery(linked, DeliveryState.ACKED)
        val linkedRow = row(
            CaptureOutcome.CAPTURED, eventId = linked.eventId,
            amountMinor = 100800L, capturedAtMs = old, postTimeMs = old,
        )
        db.captureLogDao().insert(linkedRow)

        val deleted = db.captureLogDao().deleteUnlinkedOlderThan(NOW - 90 * DAY_MS)

        assertEquals(1, deleted)
        assertNotNull(db.captureLogDao().getById(linkedRow.captureId))
    }

    @Test
    fun deleteEvent_setNullOnLinkedRows() = runBlocking {
        val event = event("k|del")
        val linkedRow = row(CaptureOutcome.CAPTURED, eventId = event.eventId, amountMinor = 100800L)
        db.captureLogDao().insert(linkedRow)

        db.eventDao().deleteById(event.eventId)

        val after = db.captureLogDao().getById(linkedRow.captureId)
        assertNotNull("the diagnostic row survives the event", after)
        assertNull(after!!.eventId)
    }

    @Test
    fun countCollisionsFor_matchesSameObservation() = runBlocking {
        db.captureLogDao().insert(
            row(CaptureOutcome.CAPTURED, amountMinor = 100800L, postTimeMs = NOW),
        )
        db.captureLogDao().insert(
            row(CaptureOutcome.COLLISION, amountMinor = 100800L, postTimeMs = NOW),
        )
        db.captureLogDao().insert(
            row(CaptureOutcome.COLLISION, amountMinor = 50000L, postTimeMs = NOW),
        )

        assertEquals(1, db.captureLogDao().countCollisionsFor(PKG, NOW, 100800L))
        assertEquals(0, db.captureLogDao().countCollisionsFor(PKG, NOW, 999L))
    }
}
