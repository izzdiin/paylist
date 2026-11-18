package com.netter.paylist.delivery

import com.netter.paylist.FakeCollectorStateDao
import com.netter.paylist.FakeDeliveryDao
import com.netter.paylist.FakeEventDao
import com.netter.paylist.FakeStore
import com.netter.paylist.FakeTransactionRunner
import com.netter.paylist.Route
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.security.SecretProvider
import com.netter.paylist.security.SecretResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `HeartbeatProcessor` behaviour (the HeartbeatWorker's logic) with
 * fake DAOs and a fake clock. The thin `HeartbeatWorker` shell
 * itself cannot be instantiated in JVM unit tests without
 * Robolectric (see `SyncWorkerTest`).
 */
class HeartbeatWorkerTest {

    private class FakeRouteSource(var routesList: List<Route>) : RouteSource {
        override fun routes(): List<Route> = routesList
        override fun routeById(id: String): Route? = routesList.firstOrNull { it.id == id }
        override fun deviceLabel(): String = "Moto g57"
    }

    private class FakeSecrets(private val map: Map<String, SecretResult>) : SecretProvider {
        override fun secretFor(routeId: String): SecretResult =
            map[routeId] ?: SecretResult.Missing
    }

    private class Harness {
        val store = FakeStore()
        val eventDao = FakeEventDao(store)
        val deliveryDao = FakeDeliveryDao(store)
        val collectorDao = FakeCollectorStateDao(store)
        val transactions = FakeTransactionRunner(store)
        val routes = FakeRouteSource(emptyList())
        var secretsMap: Map<String, SecretResult> = emptyMap()
        var now = 1_794_960_000_000L // 2026-11-18T00:00:00Z

        val processor: HeartbeatProcessor
            get() = HeartbeatProcessor(
                deliveryDao, eventDao, transactions,
                routes, FakeSecrets(secretsMap), clock = { now }
            )

        suspend fun setMode(mode: String) {
            collectorDao.put(
                CollectorStateEntity(
                    CollectorStateKeys.CUTOVER_STATE,
                    """{"mode":"$mode"}""",
                    now
                )
            )
        }

        fun route(id: String, enabled: Boolean = true, url: String = "https://$id.example.invalid/notify.php") =
            Route(id, "Rute $id", listOf("com.gojek.gopay"), url, listOf("gopay"), enabled)
    }

    @Test
    fun createsOneHeartbeatPerEnabledRouteWithSecret() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        h.routes.routesList = listOf(h.route("r1"), h.route("r2"))
        h.secretsMap = mapOf(
            "r1" to SecretResult.Present("s1"),
            "r2" to SecretResult.Present("s2")
        )

        val created = h.processor.createDueHeartbeats()

        assertEquals(2, created)
        assertEquals(2, h.eventDao.count())
        val deliveries = h.deliveryDao.getByState(DeliveryState.PENDING)
        assertEquals(2, deliveries.size)
        for (delivery in deliveries) {
            assertEquals(DeliveryKind.LEGACY_CHAIN, delivery.kind)
            assertEquals(1, JSONArray(delivery.routeIdsJson).length())
        }
        // Bucket keys: 1794960000000 / 900000 = 1994400.
        val keys = h.store.events.values.map { it.dedupeKey }.sorted()
        assertEquals(listOf("hb-r1-1994400", "hb-r2-1994400"), keys)
        for (event in h.store.events.values) {
            assertEquals("heartbeat", event.category)
            assertEquals("Moto g57", event.deviceId)
        }
    }

    @Test
    fun skipsRoutesWithoutSecretDisabledOrUrlLess() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        h.routes.routesList = listOf(
            h.route("ok"),
            h.route("no-secret"),
            h.route("disabled", enabled = false),
            h.route("no-url", url = "")
        )
        h.secretsMap = mapOf(
            "ok" to SecretResult.Present("s"),
            "disabled" to SecretResult.Present("s"),
            "no-url" to SecretResult.Present("s")
        )

        val created = h.processor.createDueHeartbeats()

        assertEquals(1, created)
        assertEquals("hb-ok-1994400", h.store.events.values.single().dedupeKey)
    }

    @Test
    fun secondPassInSameBucketIsNoop() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        h.routes.routesList = listOf(h.route("r1"))
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))

        assertEquals(1, h.processor.createDueHeartbeats())
        assertEquals(0, h.processor.createDueHeartbeats())
        assertEquals(1, h.eventDao.count())
        assertEquals(1, h.deliveryDao.getByState(DeliveryState.PENDING).size)
    }

    @Test
    fun nextBucketCreatesNewHeartbeat() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        h.routes.routesList = listOf(h.route("r1"))
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))

        assertEquals(1, h.processor.createDueHeartbeats())
        h.now += 15 * 60 * 1000L // next bucket
        assertEquals(1, h.processor.createDueHeartbeats())
        assertEquals(2, h.eventDao.count())
        val keys = h.store.events.values.map { it.dedupeKey }.sorted()
        assertEquals(listOf("hb-r1-1994400", "hb-r1-1994401"), keys)
    }

    @Test
    fun modeNotWorkerOnlyCreatesNothing() = runBlocking {
        val h = Harness()
        h.setMode("LEGACY_ONLY")
        h.routes.routesList = listOf(h.route("r1"))
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))

        assertEquals(0, h.processor.createDueHeartbeats())
        assertEquals(0, h.eventDao.count())
        assertTrue(h.deliveryDao.getByState(DeliveryState.PENDING).isEmpty())
    }

    @Test
    fun deliveryIsSingleRouteChain() = runBlocking {
        val h = Harness()
        h.setMode("WORKER_ONLY")
        h.routes.routesList = listOf(h.route("r1"))
        h.secretsMap = mapOf("r1" to SecretResult.Present("s1"))

        h.processor.createDueHeartbeats()

        val delivery = h.deliveryDao.getByState(DeliveryState.PENDING).single()
        val routeIds = JSONArray(delivery.routeIdsJson)
        assertEquals(1, routeIds.length())
        assertEquals("r1", routeIds.getString(0))
        assertEquals(0, delivery.nextRouteIndex)
    }
}
