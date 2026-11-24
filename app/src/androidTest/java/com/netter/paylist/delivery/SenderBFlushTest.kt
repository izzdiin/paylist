package com.netter.paylist.delivery

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.ModeGate
import com.netter.paylist.PaylistApplication
import com.netter.paylist.Sender
import com.netter.paylist.collector.ConsentStore
import com.netter.paylist.collector.RawSnapshotStore
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.migration.CutoverMode
import com.netter.paylist.security.SecretProvider
import com.netter.paylist.security.SecretResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage D D2: the legacy-flush B path (design §7.3A(d)/(e)) — the
 * Sender takes the raw snapshot at its per-hop claim step, re-checks
 * the raw opt-in, and re-reads the admission fence before the first
 * write.
 *
 * Instrumented (the Sender needs a real `Context`/`Store`); the
 * transport is fake, so no real POST ever leaves the device. The
 * seeded route URL is a dead loopback URL as defence in depth.
 *
 * EXECUTION NOTE: this test needs a device or an emulator. It
 * COMPILES on laptopgrt2 (`compileDebugAndroidTestKotlin`) but
 * cannot execute there — there is no device/emulator on that box.
 * It runs in CI on the emulator. Do not report it as "passing"
 * from a compile-only gate.
 */
@RunWith(AndroidJUnit4::class)
class SenderBFlushTest {

    companion object {
        const val PKG = "com.gojek.gopaymerchant"
        /** Dead loopback URL: connection refused immediately, never a real POST. */
        const val DEAD_URL = "http://127.0.0.1:9/notify-test.php"
        const val DEDUPE = "com.gojek.gopaymerchant|1760000000000|100800|b1"
        const val OTP_TEXT = "Kode OTP 482913. Rp100.800 diterima di GoPay kamu"
        const val REDACTED_TEXT = "Kode [REDACTED]. Rp100.800 diterima di GoPay kamu"
    }

    private data class PostedRequest(val url: String, val body: String)
    private data class AmbiguousCall(
        val qkey: String,
        val dedupeKey: String,
        val amount: Long?,
        val pkg: String?,
    )

    private class FakeTransport : TransportClient {
        val posted = mutableListOf<PostedRequest>()
        var script: (TransportRequest) -> TransportResult = {
            TransportResult.Http(200, emptyMap(), """{"ok":true,"match":{"status":"matched","apply":"ok"}}""")
        }
        /** Runs before the checkpoint callback (a revoke landing between gate and final read). */
        var beforeCheckpoint: (suspend () -> Unit)? = null

        override suspend fun post(
            request: TransportRequest,
            onSocketOpen: suspend () -> Boolean,
        ): TransportResult {
            beforeCheckpoint?.invoke()
            if (!onSocketOpen()) return TransportResult.CheckpointFailed
            posted += PostedRequest(request.url, request.body)
            return script(request)
        }
    }

    private lateinit var app: PaylistApplication
    private lateinit var snapshots: RawSnapshotStore
    private lateinit var consentStore: ConsentStore
    private lateinit var transport: FakeTransport
    private lateinit var sender: Sender
    private lateinit var routeId: String

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        runCatching { app.db.clearAllTables() }
            .onFailure {
                Thread.sleep(500)
                app.db.clearAllTables()
            }
        app.store.seedIfFirstRun()
        val routes = app.store.routes()
        val gopay = routes.first { it.packages.contains(PKG) }
        routeId = gopay.id
        app.store.upsertRoute(gopay.copy(url = DEAD_URL))
        app.store.saveQueueSync(emptyList())
        app.db.collectorStateDao().put(
            CollectorStateEntity(
                CollectorStateKeys.CUTOVER_STATE,
                JSONObject().apply { put("mode", CutoverMode.LEGACY_ONLY.name) }.toString(),
                System.currentTimeMillis(),
            )
        )
        snapshots = RawSnapshotStore()
        consentStore = ConsentStore(app.db.collectorStateDao())
        transport = FakeTransport()
        sender = Sender(
            app,
            ModeGate.ALWAYS_LEGACY,
            secrets = object : SecretProvider {
                override fun secretFor(routeId: String): SecretResult =
                    SecretResult.Present("s1")
            },
            transport = transport,
            rawSnapshotStore = snapshots,
            consentStore = consentStore,
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            runCatching { app.db.clearAllTables() }
            app.store.saveQueueSync(emptyList())
        }
    }

    private suspend fun grantB(expiresInMs: Long = 3_600_000L) {
        consentStore.grant(routeId)
        consentStore.setRawOptIn(routeId, true, System.currentTimeMillis() + expiresInMs)
    }

    private fun bPayload(): JSONObject = JSONObject().apply {
        put("type", "payment")
        put("device", "Moto g57")
        put("pkg", PKG)
        put("amount", 100800)
        put("title", "GoPay")
        put("text", REDACTED_TEXT)
        put("notif_time", "2026-11-18T00:00:00Z")
        put("dedupe_key", DEDUPE)
        put("direction", "in")
        put("redacted", true)
        put("raw_ephemeral", true)
    }

    private fun enqueueBItem() {
        val item = JSONObject().apply {
            put("kind", "chain")
            put("route_ids", JSONArray(listOf(routeId)))
            put("next", 0)
            put("payload", bPayload())
            put("qkey", "$DEDUPE|chain|$routeId")
        }
        assertTrue(app.store.saveQueueSync(listOf(item)))
    }

    private suspend fun putSnapshot() {
        snapshots.put(
            RawSnapshotStore.RawSnapshot(
                dedupeKey = DEDUPE,
                routeId = routeId,
                consentEpoch = consentStore.epoch(routeId),
                title = "GoPay",
                text = OTP_TEXT,
                capturedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    @Test
    fun flush_postsRaw_whenSnapshotPresent() = runBlocking {
        grantB()
        enqueueBItem()
        putSnapshot()

        sender.flush()

        assertEquals(1, transport.posted.size)
        val body = JSONObject(transport.posted[0].body)
        assertTrue(body.getString("text").contains("482913"))
        // Matched: the item is done and leaves the queue.
        assertTrue(app.store.queue().isEmpty())
    }

    @Test
    fun flush_postsStored_whenSnapshotLost() = runBlocking {
        grantB()
        enqueueBItem()
        // No snapshot (lost before the flush): the stored redacted
        // payload is the legacy-path A′.

        sender.flush()

        assertEquals(1, transport.posted.size)
        val body = JSONObject(transport.posted[0].body)
        assertFalse(body.getString("text").contains("482913"))
        assertTrue(body.getString("text").contains("[REDACTED]"))
        assertTrue(app.store.queue().isEmpty())
    }

    @Test
    fun flush_holds_whenOptInExpired() = runBlocking {
        grantB()
        enqueueBItem()
        putSnapshot()
        // Expire the opt-in: revoke the opt-in flag (a clock-expiry
        // equivalent — the record no longer validates).
        consentStore.setRawOptIn(routeId, false, null)

        sender.flush()

        assertEquals(0, transport.posted.size)
        val remaining = app.store.queue()
        assertEquals(1, remaining.size)
        assertTrue(remaining[0].optBoolean("raw_blocked", false))
        assertEquals("consent_expired", remaining[0].optString("last_error"))
    }

    @Test
    fun flush_fenceAbort_holdsItem_zeroBytes() = runBlocking {
        grantB()
        enqueueBItem()
        putSnapshot()
        // The revoke lands after the take but before the final
        // admission read: the checkpoint observes the bumped fence.
        transport.beforeCheckpoint = {
            consentStore.revoke(routeId)
            snapshots.dropRoute(routeId)
        }

        sender.flush()

        assertEquals(0, transport.posted.size)
        val remaining = app.store.queue()
        assertEquals(1, remaining.size)
        assertTrue(remaining[0].optBoolean("raw_blocked", false))
        assertEquals("route_revoked", remaining[0].optString("last_error"))
    }

    /**
     * C1 (legacy path): a server answer `match.status ==
     * 'ambiguous'` fires the sender's `onAmbiguous` hook with the
     * queue key, dedupe key, amount and package — the app-level
     * hook records the case and fires the alert.
     */
    @Test
    fun flush_ambiguousResponse_firesOnAmbiguous() = runBlocking {
        val fired = mutableListOf<AmbiguousCall>()
        val ambSender = Sender(
            app,
            ModeGate.ALWAYS_LEGACY,
            secrets = object : SecretProvider {
                override fun secretFor(routeId: String): SecretResult =
                    SecretResult.Present("s1")
            },
            transport = transport,
            rawSnapshotStore = snapshots,
            consentStore = consentStore,
            onAmbiguous = { qkey, dedupeKey, amount, pkg ->
                fired += AmbiguousCall(qkey, dedupeKey, amount, pkg)
            },
        )
        transport.script = {
            TransportResult.Http(200, emptyMap(), """{"ok":true,"match":{"status":"ambiguous"}}""")
        }
        grantB()
        enqueueBItem()

        ambSender.flush()

        assertEquals(1, fired.size)
        val (qkey, dedupeKey, amount, pkg) = fired[0]
        assertEquals("$DEDUPE|chain|$routeId", qkey)
        assertEquals(DEDUPE, dedupeKey)
        assertEquals(100800L, amount)
        assertEquals(PKG, pkg)
        // The ambiguous hop stops the chain (item gone, not retried).
        assertTrue(app.store.queueSnapshot().isEmpty())
    }

    @Test
    fun clearRawBlocked_resumesWithStoredPayload() = runBlocking {        grantB()
        enqueueBItem()
        putSnapshot()
        consentStore.setRawOptIn(routeId, false, null)
        sender.flush()
        assertEquals(0, transport.posted.size)
        assertEquals(1, app.store.queue().size)

        // The operator clears the hold: the item resumes with the
        // stored (redacted) payload and never posts raw again.
        assertTrue(sender.clearRawBlocked("$DEDUPE|chain|$routeId"))
        // clearRawBlocked starts a flush on a background thread;
        // drive a synchronous flush here for determinism.
        sender.flush()

        assertEquals(1, transport.posted.size)
        val body = JSONObject(transport.posted[0].body)
        assertFalse(body.getString("text").contains("482913"))
        assertTrue(app.store.queue().isEmpty())
    }
}
