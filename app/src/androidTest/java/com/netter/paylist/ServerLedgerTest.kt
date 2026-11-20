package com.netter.paylist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.delivery.TransportClient
import com.netter.paylist.security.SecretResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exactly-once business-effect tests (ChatGPT re-audit P1-2, R02):
 * the legacy [Sender] against a [LedgerTransport] whose server-side
 * ledger is file-backed and dedupes by `dedupe_key`.
 *
 * The three layers are asserted separately, as the review demands:
 * - queue retention: the item stays queued after an ACK loss;
 * - at-least-once transport: the POST goes out again on retry
 *   (request count 2 for one key);
 * - exactly-once business effect: the ledger records ONE effect
 *   for the key, however many times the POST was sent.
 *
 * The restart here is in-process (fresh Sender + Store + ledger
 * re-opened from its file); a REAL process restart between the
 * commit and the retry is R05, covered by ProcessDeathPhaseA/B.
 *
 * EXECUTION NOTE: these tests need a device or an emulator. They
 * COMPILE on laptopgrt2 (`assembleDebugAndroidTest`) but cannot
 * execute there — there is no device/emulator on that box. Do not
 * report them as "passing" from a compile-only gate.
 */
@RunWith(AndroidJUnit4::class)
class ServerLedgerTest {

    private lateinit var ctx: Context
    private lateinit var ledger: ServerLedger

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE).edit().clear().commit()
        ServerLedger.delete(ctx)
        ledger = ServerLedger.open(ctx)
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
    fun tearDown() {
        ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE).edit().clear().commit()
        ServerLedger.delete(ctx)
    }

    private fun newSender(transport: TransportClient): Sender =
        Sender(
            ctx, ModeGate.ALWAYS_LEGACY,
            ToggleSecrets(SecretResult.Present("s3cr3t")), transport,
        )

    @Test
    fun ackLossAfterCommit_retryDeliversExactlyOnce() {
        // R02: the server commits the payment, then the response is
        // lost (read timeout). The client retries; the server replays
        // the recorded outcome without a second effect.
        val transport = LedgerTransport(ledger, LedgerTransport.Behavior.CommitThenAckLoss)
        val sender = newSender(transport)
        val payload = paymentPayload("ledger-ackloss")
        val dedupeKey = payload.optString("dedupe_key")
        val item = chainQueueItem(payload, listOf("route-1", "route-2"))
        assertTrue(Store(ctx).saveQueueSync(listOf(item)))

        // Attempt 1: the POST reached the server (effect committed)
        // but the ACK never came back — the item stays queued.
        sender.flush()
        assertEquals(1, Store(ctx).queue().size)
        assertEquals(1, ledger.counts(dedupeKey).effects)
        assertEquals(1, ledger.counts(dedupeKey).requests)

        // Attempt 2: the retry POSTs again; the server replays the
        // recorded match — no second effect, the item delivers.
        sender.flush()
        assertTrue("the item must deliver on retry", Store(ctx).queue().isEmpty())
        val counts = ledger.counts(dedupeKey)
        assertEquals("exactly one business effect per dedupe key", 1, counts.effects)
        assertEquals("at-least-once transport: two POSTs for one key", 2, counts.requests)
    }

    @Test
    fun socketTimeoutAfterCommit_retryDeliversExactlyOnce() {
        // R02 (socket timeout variant): the socket times out on write
        // after the server committed — same expectation as ACK loss.
        val transport = LedgerTransport(ledger, LedgerTransport.Behavior.CommitThenSocketTimeout)
        val sender = newSender(transport)
        val payload = paymentPayload("ledger-timeout")
        val dedupeKey = payload.optString("dedupe_key")
        val item = chainQueueItem(payload, listOf("route-1", "route-2"))
        assertTrue(Store(ctx).saveQueueSync(listOf(item)))

        sender.flush()
        assertEquals(1, Store(ctx).queue().size)
        assertEquals(1, ledger.counts(dedupeKey).effects)

        sender.flush()
        assertTrue("the item must deliver on retry", Store(ctx).queue().isEmpty())
        val counts = ledger.counts(dedupeKey)
        assertEquals("exactly one business effect per dedupe key", 1, counts.effects)
        assertEquals("at-least-once transport: two POSTs for one key", 2, counts.requests)
    }

    @Test
    fun restartBetweenAttempts_retryDeliversExactlyOnce() {
        // R02 (restart): the client restarts between the lost ACK and
        // the retry — new Sender, new Store, ledger re-opened from
        // its file. The effect must still count exactly once.
        // (A real process restart is R05, covered by ProcessDeathPhaseA/B.)
        val transport = LedgerTransport(ledger, LedgerTransport.Behavior.CommitThenAckLoss)
        val sender = newSender(transport)
        val payload = paymentPayload("ledger-restart")
        val dedupeKey = payload.optString("dedupe_key")
        val item = chainQueueItem(payload, listOf("route-1", "route-2"))
        assertTrue(Store(ctx).saveQueueSync(listOf(item)))

        sender.flush()
        assertEquals(1, Store(ctx).queue().size)

        // Restart: fresh instances over the same durable files. The
        // behavior stays CommitThenAckLoss on purpose: the key is
        // already committed, so the replay path must answer matched
        // — the ACK loss is a one-time event, not a property of the key.
        val reopenedLedger = ServerLedger.open(ctx)
        val restartedSender = Sender(
            ctx, ModeGate.ALWAYS_LEGACY,
            ToggleSecrets(SecretResult.Present("s3cr3t")),
            LedgerTransport(reopenedLedger, LedgerTransport.Behavior.CommitThenAckLoss),
        )
        restartedSender.flush()
        assertTrue("the item must deliver after the restart", Store(ctx).queue().isEmpty())
        val counts = reopenedLedger.counts(dedupeKey)
        assertEquals("exactly one business effect per dedupe key", 1, counts.effects)
        assertEquals("at-least-once transport: two POSTs for one key", 2, counts.requests)
    }

    @Test
    fun distinctPayments_eachCommitTheirOwnEffect() {
        // Negative control: the ledger dedupes by key — two different
        // payments must commit two effects, not one.
        val transport = LedgerTransport(ledger, LedgerTransport.Behavior.CommitThenMatch)
        val sender = newSender(transport)
        val first = chainQueueItem(paymentPayload("ledger-distinct-a"), listOf("route-1", "route-2"))
        val second = chainQueueItem(paymentPayload("ledger-distinct-b"), listOf("route-1", "route-2"))
        assertTrue(Store(ctx).saveQueueSync(listOf(first, second)))

        sender.flush()
        assertTrue(Store(ctx).queue().isEmpty())
        assertEquals(2, ledger.totalEffects())
        assertEquals(2, ledger.totalRequests())
        assertEquals(
            1,
            ledger.counts(paymentPayload("ledger-distinct-a").optString("dedupe_key")).effects,
        )
        assertEquals(
            1,
            ledger.counts(paymentPayload("ledger-distinct-b").optString("dedupe_key")).effects,
        )
    }
}
