package com.netter.paylist.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.AttemptState
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.data.entity.DeliveryAttemptEntity
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
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
import java.util.UUID

/**
 * The claim + lease rules against REAL Room (design §5.1, §5.2,
 * §7.5): the mode gate, the lease assignment, the lease-guarded
 * transitions, lease reclaim with the unresolved-attempt
 * qualification, and the consent join.
 *
 * EXECUTION NOTE: this test needs a device or an emulator. It
 * COMPILES on laptopgrt2 (`compileDebugAndroidTestKotlin`) but
 * cannot execute there — there is no device/emulator on that box.
 * Do not report it as "passing" from a compile-only gate.
 */
@RunWith(AndroidJUnit4::class)
class DeliveryLeaseTest {

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

    @Test
    fun claimReturnsEmptyWhileNotWorkerOnly() = runBlocking {
        seedDelivery("lease-a")
        // No mode row at all: the claim gates to WORKER_ONLY.
        assertTrue(db.deliveryDao().claimBatch(10, NOW, LEASE_MS).isEmpty())
        setMode("DRAINING")
        assertTrue(db.deliveryDao().claimBatch(10, NOW, LEASE_MS).isEmpty())
        setMode("WORKER_ONLY")
        assertEquals(1, db.deliveryDao().claimBatch(10, NOW, LEASE_MS).size)
    }

    @Test
    fun claimAssignsLeaseAndSetsInFlight() = runBlocking {
        setMode("WORKER_ONLY")
        seedDelivery("lease-b")
        seedDelivery("lease-c")
        val claimed = db.deliveryDao().claimBatch(10, NOW, LEASE_MS)
        assertEquals(2, claimed.size)
        for (delivery in claimed) {
            assertEquals(DeliveryState.IN_FLIGHT, delivery.state)
            assertNotNull(delivery.leaseOwner)
            assertEquals(NOW + LEASE_MS, delivery.leaseUntilMs)
            assertEquals(1, delivery.attempts)
        }
        // A second claim finds nothing: both rows are IN_FLIGHT.
        assertTrue(db.deliveryDao().claimBatch(10, NOW, LEASE_MS).isEmpty())
    }

    @Test
    fun leaseGuardedAckRequiresHeldLease() = runBlocking {
        setMode("WORKER_ONLY")
        seedDelivery("lease-d")
        val claimed = db.deliveryDao().claimBatch(10, NOW, LEASE_MS).first()
        // A stranger's lease token acks nothing.
        assertEquals(0, db.deliveryDao().ackIfLeaseHeld(claimed.deliveryId, "not-the-owner", NOW, "matched", "ok"))
        // The holder's token acks.
        assertEquals(1, db.deliveryDao().ackIfLeaseHeld(claimed.deliveryId, claimed.leaseOwner!!, NOW, "matched", "ok"))
        val acked = db.deliveryDao().getById(claimed.deliveryId)
        assertEquals(DeliveryState.ACKED, acked!!.state)
        assertEquals(NOW, acked.ackedAtMs)
    }

    @Test
    fun leaseGuardedTransitionFailsAfterExpiry() = runBlocking {
        setMode("WORKER_ONLY")
        seedDelivery("lease-e")
        val claimed = db.deliveryDao().claimBatch(10, NOW, LEASE_MS).first()
        // Past the lease: the holder's own token no longer writes.
        val afterExpiry = NOW + LEASE_MS + 1
        assertEquals(
            0,
            db.deliveryDao().ackIfLeaseHeld(claimed.deliveryId, claimed.leaseOwner!!, afterExpiry, "matched", "ok"),
        )
        assertEquals(DeliveryState.IN_FLIGHT, db.deliveryDao().getById(claimed.deliveryId)!!.state)
    }

    @Test
    fun expiredLeaseReclaimedOnlyWithoutUnresolvedAttempt() = runBlocking {
        setMode("WORKER_ONLY")
        val freeId = seedDelivery("lease-f")
        val heldId = seedDelivery("lease-g")
        db.deliveryDao().claimBatch(10, NOW, LEASE_MS)
        // An unresolved attempt marker holds the second delivery.
        db.attemptDao().insert(
            DeliveryAttemptEntity(
                attemptId = UUID.randomUUID().toString(),
                deliveryId = heldId,
                routeId = "route-1",
                attemptNo = 1,
                startedAtMs = NOW,
                state = AttemptState.ATTEMPT_STARTED,
                hopIndex = 0,
                dedupeKey = "dedupe-lease-g",
                ownerBootId = "boot-1",
            ),
        )
        val afterExpiry = NOW + LEASE_MS + 1
        assertEquals(1, db.deliveryDao().reclaimExpiredLeases(afterExpiry))
        assertEquals(DeliveryState.PENDING, db.deliveryDao().getById(freeId)!!.state)
        assertEquals(DeliveryState.IN_FLIGHT, db.deliveryDao().getById(heldId)!!.state)
    }

    @Test
    fun revokedRouteNotClaimed() = runBlocking {
        setMode("WORKER_ONLY")
        // The delivery's stamp says consent epoch 1; the live record revokes the route.
        val deliveryId = seedDelivery(
            "lease-h",
            routeStampsJson = JSONObject().apply {
                put(
                    "route-1",
                    JSONObject().apply {
                        put("consent_epoch", 1)
                        put("config_epoch", 1)
                        put("url_sha256", "abc")
                    },
                )
            }.toString(),
        )
        db.collectorStateDao().put(
            CollectorStateEntity(
                CollectorStateKeys.ROUTE_CONSENTS,
                JSONObject().apply {
                    put(
                        "routes",
                        JSONObject().apply {
                            put(
                                "route-1",
                                JSONObject().apply {
                                    put("granted", false)
                                    put("epoch", 1)
                                },
                            )
                        },
                    )
                }.toString(),
                NOW,
            ),
        )
        assertTrue(db.deliveryDao().claimBatch(10, NOW, LEASE_MS).isEmpty())
        assertEquals(DeliveryState.PENDING, db.deliveryDao().getById(deliveryId)!!.state)
    }

    @Test
    fun legacyImportWithoutStampsPassesConsentCheck() = runBlocking {
        setMode("WORKER_ONLY")
        // A legacy import has no route stamps: no revocation evidence, so it claims.
        seedDelivery("lease-i", routeStampsJson = "{}")
        assertEquals(1, db.deliveryDao().claimBatch(10, NOW, LEASE_MS).size)
    }

    companion object {
        const val NOW: Long = 1_795_000_000_000L

        /** Lease length, design §5.1: 5 min, no renewal. */
        const val LEASE_MS: Long = 300_000L
    }

    private suspend fun setMode(mode: String) {
        db.collectorStateDao().put(
            CollectorStateEntity(
                CollectorStateKeys.CUTOVER_STATE,
                JSONObject().put("mode", mode).put("blocked", false).toString(),
                NOW,
            ),
        )
    }

    private suspend fun seedDelivery(tag: String, routeStampsJson: String = "{}"): String {
        val dedupeKey = "dedupe-$tag"
        val event = EventEntity(
            eventId = UUID.randomUUID().toString(),
            schemaVersion = 1,
            sourceType = "android_notification",
            sourceEventId = dedupeKey,
            deviceId = "moto-g57",
            eventType = "payment.notification",
            category = "payment",
            sourcePackage = "com.gojek.gopaymerchant",
            occurredAtMs = NOW,
            capturedAtMs = NOW,
            dataJson = "{}",
            metadataJson = "{}",
            legacyPayloadJson = null,
            privacyMode = "structured",
            retentionUntilMs = null,
            dedupeKey = dedupeKey,
            contentSha256 = "deadbeef",
            exportRef = UUID.randomUUID().toString(),
            sourceAccountKey = null,
        )
        db.eventDao().insert(event)
        val delivery = DeliveryEntity(
            deliveryId = UUID.randomUUID().toString(),
            eventId = event.eventId,
            kind = DeliveryKind.LEGACY_CHAIN,
            state = DeliveryState.PENDING,
            legacyQkey = null,
            routeIdsJson = JSONArray(listOf("route-1")).toString(),
            createdAtMs = NOW,
            updatedAtMs = NOW,
            routeStampsJson = routeStampsJson,
        )
        db.deliveryDao().insert(delivery)
        return delivery.deliveryId
    }
}
