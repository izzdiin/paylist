package com.netter.paylist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.migration.LegacyQkey
import com.netter.paylist.security.SecretResult
import com.netter.paylist.security.SecretVault
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end queue-retention tests (ChatGPT Stage C audit, finding 1):
 * the legacy [Sender] against the REAL [Store] queue, with a fake
 * transport (no billing server exists in CI) and either a toggleable
 * [SecretProvider] or the REAL [SecretVault] (Android Keystore on the
 * emulator).
 *
 * This is what the PR #4 source-guard test could not prove: a payment
 * item whose secret is unreadable stays in the queue at the same hop,
 * no POST is attempted, and the SAME item delivers normally once the
 * secret is readable again — no dequeue, no skip, no duplicate.
 *
 * P1-3 (ChatGPT re-audit, R01) extends the same guarantee to a
 * MISSING secret: no skip past the hop, no silent dequeue — the
 * item stays queued at the same hop with a visible error until the
 * operator enters the secret.
 *
 * EXECUTION NOTE: these tests need a device or an emulator. They
 * COMPILE on laptopgrt2 (`assembleDebugAndroidTest`) but cannot
 * execute there — there is no device/emulator on that box. Do not
 * report them as "passing" from a compile-only gate.
 */
@RunWith(AndroidJUnit4::class)
class SenderRetentionTest {

    private lateinit var ctx: Context

    @Before
    fun clearStoresAndSeedRoutes() {
        ctx = ApplicationProvider.getApplicationContext()
        clearPrefs()
        Store(ctx).saveRoutes(
            listOf(
                Route(
                    "route-1", "v2New", listOf("com.gojek.gopaymerchant"),
                    "https://example.invalid/notify-1", Route.DEFAULT_KEYWORDS, true,
                ),
                Route(
                    "route-2", "warganet", listOf("com.gojek.gopaymerchant"),
                    "https://example.invalid/notify-2", Route.DEFAULT_KEYWORDS, true,
                ),
            ),
        )
    }

    @After
    fun clearAfter() {
        clearPrefs()
    }

    private fun clearPrefs() {
        ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences(SecretVault.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun chainItemRetainedWhileSecretUnreadable_thenDeliveredAfterRecovery() {
        val secrets = ToggleSecrets(SecretResult.Unavailable("keystore locked"))
        val transport = FakeTransport()
        val sender = Sender(ctx, ModeGate.ALWAYS_LEGACY, secrets, transport)
        val payload = paymentPayload("retain-chain")
        val qkey = chainQkey("retain-chain", listOf("route-1", "route-2"))

        // The real enqueue path: enqueueChain starts a background flush.
        sender.enqueueChain(payload, listOf("route-1", "route-2"))
        // The background flush must have run (attempts recorded) before
        // asserting — attempts are persisted at the end of the flush, so
        // observing them also proves the flush thread has finished.
        waitUntil {
            Store(ctx).queue().any {
                it.optString("qkey") == qkey && it.optInt("attempts", 0) >= 1
            }
        }

        // Retained: the same item, the same hop, no POST attempted.
        val retained = Store(ctx).queue()
        assertEquals(1, retained.size)
        assertEquals(qkey, retained.single().optString("qkey"))
        assertEquals(0, retained.single().optInt("next"))
        assertTrue(
            "the failure reason must name the unreadable secret",
            retained.single().optString("last_error").contains("secret tidak bisa dibaca"),
        )
        assertTrue(
            "no POST may start while the secret is unreadable",
            transport.requests.isEmpty(),
        )

        // Recovery: the secret becomes readable; the SAME item delivers.
        secrets.result = SecretResult.Present("s3cr3t")
        sender.flush()
        assertTrue(
            "the retained item must deliver after recovery",
            Store(ctx).queue().isEmpty(),
        )
        assertEquals(1, transport.requests.size)
        assertEquals("https://example.invalid/notify-1", transport.requests.single().url)
        assertEquals("s3cr3t", transport.requests.single().authSecret)
        assertTrue(
            "the delivered body must carry the payment",
            transport.requests.single().body.contains(payload.optString("dedupe_key")),
        )
    }

    @Test
    fun singleItemRetainedWhileSecretUnreadable_thenDeliveredAfterRecovery() {
        val secrets = ToggleSecrets(SecretResult.Unavailable("keystore locked"))
        val transport = FakeTransport(responder = { FakeTransport.ackResponse() })
        val sender = Sender(ctx, ModeGate.ALWAYS_LEGACY, secrets, transport)
        val payload = JSONObject().apply {
            put("type", "test")
            put("device", "moto-g57")
            put("dedupe_key", "test|retain-single")
        }
        val qkey = LegacyQkey.build("test|retain-single", LegacyQkey.KIND_SINGLE, listOf("route-1"))
        val item = JSONObject().apply {
            put("qkey", qkey)
            put("kind", "single")
            put("route_id", "route-1")
            put("payload", payload)
        }
        assertTrue(Store(ctx).saveQueueSync(listOf(item)))

        sender.flush()
        val retained = Store(ctx).queue()
        assertEquals(1, retained.size)
        assertEquals(qkey, retained.single().optString("qkey"))
        assertEquals(1, retained.single().optInt("attempts"))
        assertTrue(
            "no POST may start while the secret is unreadable",
            transport.requests.isEmpty(),
        )

        secrets.result = SecretResult.Present("s3cr3t")
        sender.flush()
        assertTrue(
            "the retained item must deliver after recovery",
            Store(ctx).queue().isEmpty(),
        )
        assertEquals(1, transport.requests.size)
        assertEquals("https://example.invalid/notify-1", transport.requests.single().url)
        assertEquals("s3cr3t", transport.requests.single().authSecret)
    }

    @Test
    fun corruptVaultEntryRetainsItem_realKeystore_thenDeliveredAfterSecretReentry() {
        val vault = SecretVault(ctx)
        // A corrupt vault entry: the real vault reports Unavailable,
        // never a plaintext fallback.
        ctx.getSharedPreferences(SecretVault.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(SecretVault.keyFor("route-1"), "!!!not-base64!!!").commit()
        val unreadable = vault.secretFor("route-1")
        assertTrue(
            "a corrupt entry must surface as Unavailable, got $unreadable",
            unreadable is SecretResult.Unavailable,
        )

        val transport = FakeTransport()
        val sender = Sender(ctx, ModeGate.ALWAYS_LEGACY, vault, transport)
        val payload = paymentPayload("retain-vault")
        val item = chainQueueItem(payload, listOf("route-1", "route-2"))
        assertTrue(Store(ctx).saveQueueSync(listOf(item)))

        sender.flush()
        assertEquals(1, Store(ctx).queue().size)
        assertTrue(
            "no POST may start while the vault entry is corrupt",
            transport.requests.isEmpty(),
        )

        // Operator re-enters the secret: a real Keystore encrypt +
        // decrypt round-trip on the emulator.
        assertTrue(
            "putSecret must succeed on the emulator Keystore",
            vault.putSecret("route-1", "s3cr3t-real"),
        )
        val readable = vault.secretFor("route-1")
        assertTrue("the re-entered secret must read back Present", readable is SecretResult.Present)
        assertEquals("s3cr3t-real", (readable as SecretResult.Present).value)

        sender.flush()
        assertTrue(
            "the retained item must deliver after the secret is re-entered",
            Store(ctx).queue().isEmpty(),
        )
        assertEquals(1, transport.requests.size)
        assertEquals("s3cr3t-real", transport.requests.single().authSecret)
    }

    @Test
    fun corruptItemWithoutPayload_doesNotKillFlush_healthyItemStillDelivers() {
        val secrets = ToggleSecrets(SecretResult.Present("s3cr3t"))
        val transport = FakeTransport()
        val sender = Sender(ctx, ModeGate.ALWAYS_LEGACY, secrets, transport)
        // The corrupt item comes FIRST: before the fix, getJSONObject
        // threw on it and the healthy item behind it was never
        // attempted in that flush.
        val corrupt = JSONObject().apply {
            put("qkey", "corrupt|chain|route-1,route-2")
            put("kind", "chain")
            put("route_ids", JSONArray(listOf("route-1", "route-2")))
            put("next", 0)
            put("attempts", 0)
            // No "payload" key at all.
        }
        val healthy = chainQueueItem(
            paymentPayload("retain-corrupt-healthy"),
            listOf("route-1", "route-2"),
        )
        assertTrue(Store(ctx).saveQueueSync(listOf(corrupt, healthy)))

        sender.flush() // Must not throw.

        // The healthy item delivered; exactly one POST went out.
        assertEquals(1, transport.requests.size)
        assertTrue(
            "the delivered body must carry the healthy payment",
            transport.requests.single().body.contains("retain-corrupt-healthy"),
        )
        // The corrupt item stays queued (never silently deleted),
        // marked failed with a visible reason.
        val remaining = Store(ctx).queue()
        assertEquals(1, remaining.size)
        assertEquals("corrupt|chain|route-1,route-2", remaining.single().optString("qkey"))
        assertEquals(1, remaining.single().optInt("attempts"))
        assertTrue(
            "the failure reason must name the missing payload",
            remaining.single().optString("last_error").contains("payload"),
        )
    }

    @Test
    fun chainItemRetainedWhileSecretMissing_thenDeliveredAfterSecretEntered() {
        // P1-3 (R01): a missing secret retains the payment at its hop —
        // it must NOT skip past every hop and dequeue the payment.
        val secrets = ToggleSecrets(SecretResult.Missing)
        val transport = FakeTransport()
        val sender = Sender(ctx, ModeGate.ALWAYS_LEGACY, secrets, transport)
        val payload = paymentPayload("retain-missing-chain")
        val qkey = chainQkey("retain-missing-chain", listOf("route-1", "route-2"))
        val item = chainQueueItem(payload, listOf("route-1", "route-2"))
        assertTrue(Store(ctx).saveQueueSync(listOf(item)))

        sender.flush()
        val retained = Store(ctx).queue()
        assertEquals(1, retained.size)
        assertEquals(qkey, retained.single().optString("qkey"))
        assertEquals(0, retained.single().optInt("next"))
        assertTrue(
            "the failure reason must name the missing secret",
            retained.single().optString("last_error").contains("secret belum diatur"),
        )
        assertTrue(
            "no POST may start while the secret is missing",
            transport.requests.isEmpty(),
        )

        // The operator enters the secret: the SAME item delivers to route-1.
        secrets.result = SecretResult.Present("s3cr3t")
        sender.flush()
        assertTrue(
            "the retained item must deliver after the secret is entered",
            Store(ctx).queue().isEmpty(),
        )
        assertEquals(1, transport.requests.size)
        assertEquals("https://example.invalid/notify-1", transport.requests.single().url)
        assertEquals("s3cr3t", transport.requests.single().authSecret)
    }

    @Test
    fun chainDoesNotSkipMissingHopToNextRoute() {
        // P1-3 (R01, mixed): the first hop's secret is missing while
        // the second hop's is present — the chain must NOT skip to
        // route-2. The payment may belong to route-1; only the
        // operator entering the secret (or disabling the route)
        // moves it on.
        val secrets = MapSecrets(
            mutableMapOf(
                "route-1" to SecretResult.Missing,
                "route-2" to SecretResult.Present("s3cr3t-2"),
            ),
        )
        val transport = FakeTransport()
        val sender = Sender(ctx, ModeGate.ALWAYS_LEGACY, secrets, transport)
        val payload = paymentPayload("retain-missing-mixed")
        val item = chainQueueItem(payload, listOf("route-1", "route-2"))
        assertTrue(Store(ctx).saveQueueSync(listOf(item)))

        sender.flush()
        val retained = Store(ctx).queue()
        assertEquals(1, retained.size)
        assertEquals(0, retained.single().optInt("next"))
        assertTrue(
            "no POST may go to route-2 while route-1's secret is missing",
            transport.requests.isEmpty(),
        )

        // The operator enters route-1's secret: the item delivers to route-1.
        secrets.set("route-1", SecretResult.Present("s3cr3t-1"))
        sender.flush()
        assertTrue(
            "the retained item must deliver after the secret is entered",
            Store(ctx).queue().isEmpty(),
        )
        assertEquals(1, transport.requests.size)
        assertEquals("https://example.invalid/notify-1", transport.requests.single().url)
        assertEquals("s3cr3t-1", transport.requests.single().authSecret)
    }

    @Test
    fun singleItemRetainedWhileSecretMissing_thenDeliveredAfterSecretEntered() {
        // P1-3 (R01, single path): a missing secret must not silently
        // dequeue a test item either.
        val secrets = ToggleSecrets(SecretResult.Missing)
        val transport = FakeTransport(responder = { FakeTransport.ackResponse() })
        val sender = Sender(ctx, ModeGate.ALWAYS_LEGACY, secrets, transport)
        val payload = JSONObject().apply {
            put("type", "test")
            put("device", "moto-g57")
            put("dedupe_key", "test|retain-missing-single")
        }
        val qkey = LegacyQkey.build("test|retain-missing-single", LegacyQkey.KIND_SINGLE, listOf("route-1"))
        val item = JSONObject().apply {
            put("qkey", qkey)
            put("kind", "single")
            put("route_id", "route-1")
            put("payload", payload)
        }
        assertTrue(Store(ctx).saveQueueSync(listOf(item)))

        sender.flush()
        val retained = Store(ctx).queue()
        assertEquals(1, retained.size)
        assertEquals(qkey, retained.single().optString("qkey"))
        assertEquals(1, retained.single().optInt("attempts"))
        assertTrue(
            "the failure reason must name the missing secret",
            retained.single().optString("last_error").contains("secret belum diatur"),
        )
        assertTrue(
            "no POST may start while the secret is missing",
            transport.requests.isEmpty(),
        )

        secrets.result = SecretResult.Present("s3cr3t")
        sender.flush()
        assertTrue(
            "the retained item must deliver after the secret is entered",
            Store(ctx).queue().isEmpty(),
        )
        assertEquals(1, transport.requests.size)
        assertEquals("https://example.invalid/notify-1", transport.requests.single().url)
        assertEquals("s3cr3t", transport.requests.single().authSecret)
    }

    @Test
    fun expiredHeartbeatWithMissingSecret_isDroppedNotRetained() {
        // The heartbeat-expiry check runs BEFORE the secret check:
        // an expired heartbeat is useless whatever the secret state,
        // or dead heartbeats would pile up forever on secret-less
        // routes (one per 15-minute window).
        val secrets = ToggleSecrets(SecretResult.Missing)
        val transport = FakeTransport(responder = { FakeTransport.ackResponse() })
        val sender = Sender(ctx, ModeGate.ALWAYS_LEGACY, secrets, transport)
        val nowBucket = System.currentTimeMillis() / 900000
        val dedupeKey = "heartbeat-${nowBucket - 3}"
        val payload = JSONObject().apply {
            put("type", "heartbeat")
            put("device", "moto-g57")
            put("dedupe_key", dedupeKey)
        }
        val qkey = LegacyQkey.build(dedupeKey, LegacyQkey.KIND_SINGLE, listOf("route-1"))
        val item = JSONObject().apply {
            put("qkey", qkey)
            put("kind", "single")
            put("route_id", "route-1")
            put("payload", payload)
        }
        assertTrue(Store(ctx).saveQueueSync(listOf(item)))

        sender.flush()
        assertTrue(
            "an expired heartbeat must drop even with a missing secret",
            Store(ctx).queue().isEmpty(),
        )
        assertTrue(
            "no POST may start for an expired heartbeat",
            transport.requests.isEmpty(),
        )
    }

    /** Polls [condition] until true or the timeout lapses (then fails). */
    private fun waitUntil(timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("timed out waiting for condition", condition())
    }
}
