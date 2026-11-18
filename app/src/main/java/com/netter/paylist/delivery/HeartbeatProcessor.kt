package com.netter.paylist.delivery

import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.TransactionRunner
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.data.dao.EventDao
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.security.SecretProvider
import com.netter.paylist.security.SecretResult
import org.json.JSONArray
import java.util.UUID

/**
 * The HeartbeatWorker's logic, free of Android/WorkManager plumbing
 * (design §6, §4.2 C12).
 *
 * One pass ([createDueHeartbeats]): for every enabled route with a
 * stored secret, create one heartbeat event + one single-route
 * `LEGACY_CHAIN` delivery for the current 15-minute bucket. The
 * event insert is `INSERT OR IGNORE` on `dedupe_key`, so a second
 * pass in the same bucket is a no-op — the worker is safe to run
 * more often than the bucket (and safe alongside the AlarmManager
 * heartbeat during the C–D dual run, C18: the server dedupes by the
 * same bucket key).
 *
 * Heartbeats are created only in `WORKER_ONLY` mode: before the
 * cutover the AlarmManager heartbeat covers the signal, and Room
 * heartbeat deliveries created earlier would only pile up PENDING
 * and expire.
 */
class HeartbeatProcessor(
    private val deliveryDao: DeliveryDao,
    private val eventDao: EventDao,
    private val transactions: TransactionRunner,
    private val routes: RouteSource,
    private val secrets: SecretProvider,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** Heartbeat dedupe bucket length in ms (15 min, `Sender.kt`). */
        const val HEARTBEAT_BUCKET_MS = 900_000L
    }

    /** Creates due heartbeat events + deliveries. Returns the count created. */
    suspend fun createDueHeartbeats(): Int {
        val modeJson = deliveryDao.readCollectorValue(CollectorStateKeys.CUTOVER_STATE)
        val mode = modeJson?.let { DeliveryDao.parseMode(it) }
        if (mode != "WORKER_ONLY") return 0
        val now = clock()
        val bucket = now / HEARTBEAT_BUCKET_MS
        var created = 0
        for (route in routes.routes()) {
            if (!route.enabled || route.url.isEmpty()) continue
            if (secrets.secretFor(route.id) !is SecretResult.Present) continue
            val dedupeKey = "hb-${route.id}-$bucket"
            val eventId = UUID.randomUUID().toString()
            val event = EventEntity(
                eventId = eventId,
                sourceType = "heartbeat",
                sourceEventId = dedupeKey,
                deviceId = routes.deviceLabel(),
                eventType = "heartbeat.notification",
                category = "heartbeat",
                sourcePackage = null,
                occurredAtMs = now,
                capturedAtMs = now,
                dataJson = "{}",
                metadataJson = """{"parser_version":"heartbeat-v1","privacy_mode":"structured"}""",
                legacyPayloadJson = null,
                privacyMode = "structured",
                retentionUntilMs = null,
                dedupeKey = dedupeKey,
                contentSha256 = null,
                exportRef = UUID.randomUUID().toString(),
                sourceAccountKey = null,
            )
            val delivery = DeliveryEntity(
                deliveryId = UUID.randomUUID().toString(),
                eventId = eventId,
                kind = DeliveryKind.LEGACY_CHAIN,
                state = DeliveryState.PENDING,
                legacyQkey = null,
                routeIdsJson = JSONArray(listOf(route.id)).toString(),
                createdAtMs = now,
                updatedAtMs = now,
            )
            val inserted = transactions.run {
                val rowId = eventDao.insert(event)
                if (rowId == -1L) {
                    false // a heartbeat for this bucket already exists
                } else {
                    deliveryDao.insert(delivery)
                    true
                }
            }
            if (inserted) created++
        }
        return created
    }
}
