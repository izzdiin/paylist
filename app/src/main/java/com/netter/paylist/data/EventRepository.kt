package com.netter.paylist.data

import com.netter.paylist.Route
import com.netter.paylist.core.model.CanonicalEvent
import com.netter.paylist.core.model.Delivery
import com.netter.paylist.core.model.EventData
import com.netter.paylist.core.model.EventMetadata
import com.netter.paylist.core.model.EventSource
import com.netter.paylist.core.model.RouteStamp
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.data.dao.EventDao
import com.netter.paylist.data.entity.CaptureStagingDao
import com.netter.paylist.data.entity.CaptureStagingEntity
import com.netter.paylist.data.entity.ClassificationDao
import com.netter.paylist.data.entity.ClassificationEntity
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import org.json.JSONArray
import org.json.JSONObject

/** What happened when an event insert met an existing `dedupe_key`. */
enum class EventInsertOutcome {
    /** The event row was newly inserted. */
    INSERTED,

    /** Same `dedupe_key`, same `content_sha256`: an expected duplicate. */
    DUPLICATE,

    /**
     * Same `dedupe_key`, different `content_sha256`: a genuine
     * collision/update — the first event is kept, no delivery is
     * created for the second, and the caller counts
     * `identity_collision` in diagnostics (design §3, R11).
     */
    COLLISION,
}

/** Result of [EventRepository.createEventWithDelivery]. */
data class CreateEventResult(
    val eventOutcome: EventInsertOutcome,
    /** True when the delivery row was newly inserted. */
    val deliveryInserted: Boolean,
    /** The stored event id (the new row's, or the existing row's on duplicate). */
    val eventId: String,
)

/** Result of [EventRepository.promoteStagingToRoom]. */
data class PromoteResult(
    /** True when the delivery row was newly inserted (false = already present, idempotent). */
    val deliveryInserted: Boolean,
    /** True when the staging row was deleted. */
    val stagingDeleted: Boolean,
)

/**
 * One-transaction event + delivery creation, and the staging fan-out
 * (design §4.5, §8.3 C22(j)).
 *
 * Atomicity (design §4.5): event and delivery creation in one Room
 * transaction; claim/lease in one transaction; each ACK state
 * transition in a transaction. Do not hold DB transactions during
 * network I/O. If storage fails, the failure is exposed explicitly;
 * the repository never claims the event was safely queued.
 *
 * The repository works over DAO interfaces plus a [TransactionRunner],
 * so unit tests drive it with in-memory fakes and production wires
 * it to the real Room database.
 */
class EventRepository(
    private val eventDao: EventDao,
    private val deliveryDao: DeliveryDao,
    private val captureStagingDao: CaptureStagingDao,
    private val classificationDao: ClassificationDao,
    private val transactions: TransactionRunner,
) {

    /**
     * Creates an event and its delivery in one transaction.
     *
     * On a `dedupe_key` conflict: same hash = duplicate (the delivery
     * insert still proceeds — a second delivery for the same event is
     * permitted only with a different `legacy_qkey`, §3 R11);
     * different hash = collision (first event kept, no delivery
     * created, [EventInsertOutcome.COLLISION]).
     */
    suspend fun createEventWithDelivery(event: CanonicalEvent, delivery: Delivery): CreateEventResult {
        return transactions.run {
            val inserted = eventDao.insert(event.toEntity())
            if (inserted != 0L) {
                val deliveryInserted = deliveryDao.insert(delivery.toEntity()) != 0L
                CreateEventResult(EventInsertOutcome.INSERTED, deliveryInserted, event.eventId)
            } else {
                val existing = eventDao.getByDedupeKey(event.dedupeKey)
                val outcome = when {
                    existing == null -> EventInsertOutcome.DUPLICATE
                    existing.contentSha256 == event.contentSha256 -> EventInsertOutcome.DUPLICATE
                    else -> EventInsertOutcome.COLLISION
                }
                if (outcome == EventInsertOutcome.COLLISION) {
                    CreateEventResult(outcome, false, existing?.eventId ?: event.eventId)
                } else {
                    val deliveryInserted = deliveryDao.insert(delivery.toEntity()) != 0L
                    CreateEventResult(outcome, deliveryInserted, existing?.eventId ?: event.eventId)
                }
            }
        }
    }

    /**
     * Staging fan-out (C22(j)): one notification snapshot fans out to
     * staging rows exactly once, at capture, in a single Room
     * transaction — the event row, the classification record (when
     * the Stage D classifier supplied one), and one staging row per
     * delivery (each keyed by its own `legacy_qkey`). Either all rows
     * land or none; a failed transaction means the event is not
     * durably captured.
     */
    suspend fun stageCapture(
        event: CanonicalEvent,
        stagingRows: List<CaptureStagingEntity>,
        classification: ClassificationEntity? = null,
    ): CreateEventResult {
        return transactions.run {
            val inserted = eventDao.insert(event.toEntity())
            val outcome = if (inserted != 0L) {
                EventInsertOutcome.INSERTED
            } else {
                val existing = eventDao.getByDedupeKey(event.dedupeKey)
                when {
                    existing == null -> EventInsertOutcome.DUPLICATE
                    existing.contentSha256 == event.contentSha256 -> EventInsertOutcome.DUPLICATE
                    else -> EventInsertOutcome.COLLISION
                }
            }
            if (outcome != EventInsertOutcome.COLLISION) {
                if (classification != null) classificationDao.insert(classification)
                for (row in stagingRows) captureStagingDao.insert(row)
            }
            val existingId = eventDao.getByDedupeKey(event.dedupeKey)?.eventId ?: event.eventId
            CreateEventResult(outcome, false, existingId)
        }
    }

    /**
     * Room-path promotion (§4.5): outbox insert + staging delete in
     * one Room transaction. Idempotent: when the delivery already
     * exists (a re-promotion after a crash), the staging row is still
     * deleted — the promotion's effect is already durable in the
     * target store.
     */
    suspend fun promoteStagingToRoom(
        stagingRow: CaptureStagingEntity,
        delivery: Delivery,
    ): PromoteResult {
        return transactions.run {
            val deliveryInserted = deliveryDao.insert(delivery.toEntity()) != 0L
            val stagingDeleted = captureStagingDao.deleteByLegacyQkey(stagingRow.legacyQkey) == 1
            PromoteResult(deliveryInserted, stagingDeleted)
        }
    }

    suspend fun getEventByDedupeKey(dedupeKey: String): EventEntity? =
        eventDao.getByDedupeKey(dedupeKey)

    suspend fun getEventById(eventId: String): EventEntity? = eventDao.getById(eventId)

    suspend fun getDeliveryByQkey(qkey: String): DeliveryEntity? =
        deliveryDao.getByLegacyQkey(qkey)

    suspend fun getStagingRows(dedupeKey: String): List<CaptureStagingEntity> =
        captureStagingDao.getByDedupeKey(dedupeKey)

    suspend fun getAllStagingRows(): List<CaptureStagingEntity> = captureStagingDao.getAll()
}

// --- Mapping: canonical model <-> Room entities (design §3/§4) ---

/** Canonical event -> Room entity. `data`/`metadata` become validated JSON. */
fun CanonicalEvent.toEntity(): EventEntity = EventEntity(
    eventId = eventId,
    schemaVersion = schemaVersion,
    sourceType = source.type,
    sourceEventId = sourceEventId,
    deviceId = source.deviceId,
    eventType = type,
    category = category,
    sourcePackage = source.packageName,
    occurredAtMs = occurredAtMs,
    capturedAtMs = capturedAtMs,
    dataJson = data.toJsonString(),
    metadataJson = metadata.toJsonString(),
    legacyPayloadJson = legacyPayloadJson,
    privacyMode = metadata.privacyMode,
    retentionUntilMs = retentionUntilMs,
    dedupeKey = dedupeKey,
    contentSha256 = contentSha256,
    exportRef = exportRef,
    sourceAccountKey = sourceAccountKey,
)

/** Room entity -> canonical event. */
fun EventEntity.toCanonical(): CanonicalEvent = CanonicalEvent(
    eventId = eventId,
    schemaVersion = schemaVersion,
    sourceEventId = sourceEventId,
    type = eventType,
    category = category,
    source = EventSource(sourceType, deviceId, sourcePackage),
    occurredAtMs = occurredAtMs,
    capturedAtMs = capturedAtMs,
    data = eventDataFromJson(dataJson),
    metadata = eventMetadataFromJson(metadataJson),
    dedupeKey = dedupeKey,
    // The domain type is non-null (Stage A model); an empty string
    // means "no verified hash" (pre-v1.1 imports, NULL in Room).
    contentSha256 = contentSha256 ?: "",
    exportRef = exportRef,
    sourceAccountKey = sourceAccountKey,
    legacyPayloadJson = legacyPayloadJson,
    retentionUntilMs = retentionUntilMs,
)

/** Delivery -> Room entity. Hop outcomes start empty; checkpoints fill them. */
fun Delivery.toEntity(): DeliveryEntity = DeliveryEntity(
    deliveryId = deliveryId,
    eventId = eventId,
    kind = kind,
    state = state,
    rawEphemeral = rawEphemeral,
    rawLost = rawLost,
    legacyQkey = legacyQkey,
    routeIdsJson = JSONArray(routeIds).toString(),
    nextRouteIndex = nextRouteIndex,
    attempts = attempts,
    nextRetryAtMs = nextRetryAtMs,
    leaseOwner = leaseOwner,
    leaseUntilMs = leaseUntilMs,
    lastErrorCode = lastErrorCode,
    lastErrorSummary = lastErrorSummary,
    lastMatchStatus = lastMatchStatus,
    lastApplyStatus = lastApplyStatus,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    ackedAtMs = ackedAtMs,
    terminalAtMs = terminalAtMs,
    routeStampsJson = routeStamps.toJsonString(),
    hopOutcomesJson = "[]",
)

/** Room entity -> delivery. */
fun DeliveryEntity.toDelivery(): Delivery = Delivery(
    deliveryId = deliveryId,
    eventId = eventId,
    kind = kind,
    state = state,
    rawEphemeral = rawEphemeral,
    rawLost = rawLost,
    legacyQkey = legacyQkey,
    routeIds = Route.jsonStrings(JSONArray(routeIdsJson)),
    nextRouteIndex = nextRouteIndex,
    attempts = attempts,
    nextRetryAtMs = nextRetryAtMs,
    leaseOwner = leaseOwner,
    leaseUntilMs = leaseUntilMs,
    lastErrorCode = lastErrorCode,
    lastErrorSummary = lastErrorSummary,
    lastMatchStatus = lastMatchStatus,
    lastApplyStatus = lastApplyStatus,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    ackedAtMs = ackedAtMs,
    terminalAtMs = terminalAtMs,
    routeStamps = RouteStampMap.fromJsonString(routeStampsJson),
)

private fun EventData.toJsonString(): String = JSONObject().apply {
    if (amount != null) put("amount", amount)
    if (currency != null) put("currency", currency)
}.toString()

private fun eventDataFromJson(json: String): EventData {
    return try {
        val o = JSONObject(json)
        EventData(
            amount = if (o.has("amount")) o.optLong("amount") else null,
            currency = o.optString("currency").ifEmpty { null },
        )
    } catch (e: Exception) {
        EventData(null, null)
    }
}

private fun EventMetadata.toJsonString(): String = JSONObject().apply {
    put("parser_version", parserVersion)
    put("privacy_mode", privacyMode)
}.toString()

private fun eventMetadataFromJson(json: String): EventMetadata {
    return try {
        val o = JSONObject(json)
        EventMetadata(
            parserVersion = o.optString("parser_version"),
            privacyMode = o.optString("privacy_mode"),
        )
    } catch (e: Exception) {
        EventMetadata("", "")
    }
}

private fun Map<String, RouteStamp>.toJsonString(): String = JSONObject().apply {
    for ((routeId, stamp) in this@toJsonString) {
        put(routeId, JSONObject().apply {
            put("consent_epoch", stamp.consentEpoch)
            put("config_epoch", stamp.configEpoch)
            put("url_sha256", stamp.urlSha256)
        })
    }
}.toString()

private object RouteStampMap {
    fun fromJsonString(json: String): Map<String, RouteStamp> {
        return try {
            val o = JSONObject(json)
            val out = mutableMapOf<String, RouteStamp>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val routeId = keys.next()
                val s = o.optJSONObject(routeId) ?: continue
                out[routeId] = RouteStamp(
                    consentEpoch = s.optLong("consent_epoch"),
                    configEpoch = s.optLong("config_epoch"),
                    urlSha256 = s.optString("url_sha256"),
                )
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
