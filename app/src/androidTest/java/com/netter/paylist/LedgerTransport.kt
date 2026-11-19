package com.netter.paylist

import android.content.Context
import android.os.Process
import com.netter.paylist.delivery.TransportClient
import com.netter.paylist.delivery.TransportRequest
import com.netter.paylist.delivery.TransportResult
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * A fake billing server's durable ledger: one business effect per
 * `dedupe_key`, however many times the POST is sent. The ledger is a
 * JSON file in the app's files dir, written + fsynced on every
 * commit — so it survives a process kill immediately after the
 * commit (R05 kills right after the commit; a plain in-memory map
 * would die with the process and prove nothing).
 *
 * The ledger records two tallies per key, kept deliberately apart:
 * - `effects`: business effects applied (a payment settled on the
 *   server). At most one per key — the server dedupes by key.
 * - `requests`: HTTP POSTs received for the key. At-least-once:
 *   retries re-POST the same key.
 *
 * Exactly-once business effect + at-least-once transport is the
 * property the P1-2 tests pin: `effects == 1` while `requests >= 1`.
 */
class ServerLedger(private val file: File) {

    /** Per-key tallies: business effects applied vs HTTP requests received. */
    data class Counts(val effects: Int, val requests: Int)

    private val lock = Any()
    private val effects = mutableMapOf<String, Int>()
    private val requests = mutableMapOf<String, Int>()

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val e = json.optJSONObject("effects")
            if (e != null) {
                val keys = e.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    effects[key] = e.optInt(key)
                }
            }
            val r = json.optJSONObject("requests")
            if (r != null) {
                val keys = r.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    requests[key] = r.optInt(key)
                }
            }
        } catch (_: Exception) {
            // A torn ledger file must fail loudly at the next
            // assertion, not silently reset: keep the maps empty and
            // let the test's effect assertions catch the discrepancy.
            effects.clear()
            requests.clear()
        }
    }

    private fun save() {
        val eObj = JSONObject()
        for ((k, v) in effects) eObj.put(k, v)
        val rObj = JSONObject()
        for ((k, v) in requests) rObj.put(k, v)
        val json = JSONObject().apply {
            put("effects", eObj)
            put("requests", rObj)
        }
        FileOutputStream(file).use { out ->
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /**
     * Records one POST for [dedupeKey]. The FIRST post for a key
     * commits the business effect; a replay (same key again) is
     * recorded as a request only — the server dedupes by key and
     * never applies the same payment twice.
     *
     * @return true when this post committed a NEW effect, false when
     *   it was a replay of an already-committed key.
     */
    fun recordPost(dedupeKey: String): Boolean = synchronized(lock) {
        requests[dedupeKey] = (requests[dedupeKey] ?: 0) + 1
        val isNew = !effects.containsKey(dedupeKey)
        if (isNew) effects[dedupeKey] = 1
        save()
        isNew
    }

    fun counts(dedupeKey: String): Counts = synchronized(lock) {
        Counts(effects[dedupeKey] ?: 0, requests[dedupeKey] ?: 0)
    }

    fun totalEffects(): Int = synchronized(lock) { effects.values.sum() }
    fun totalRequests(): Int = synchronized(lock) { requests.values.sum() }

    companion object {
        const val FILE_NAME: String = "test-server-ledger.json"

        fun open(context: Context): ServerLedger =
            ServerLedger(File(context.filesDir, FILE_NAME))

        fun delete(context: Context) {
            File(context.filesDir, FILE_NAME).delete()
        }
    }
}

/**
 * A [TransportClient] backed by a [ServerLedger]: the fake server
 * commits the business effect for a `dedupe_key` on the FIRST post
 * and replays the same success response — without a second effect —
 * on every retry.
 *
 * The scripted [behavior] applies to the FIRST post for each key
 * only: a real server answers a retried key normally — an ACK loss
 * is a one-time network event, not a property of the key.
 * - [Behavior.CommitThenMatch]: commit, answer matched (the happy path).
 * - [Behavior.CommitThenAckLoss]: commit, then the response is lost
 *   (`read_timeout`) — the client must retry and the server must
 *   replay without a second effect (R02).
 * - [Behavior.CommitThenSocketTimeout]: commit, then the socket
 *   times out on write (`write_timeout`) — same expectation (R02).
 * - [Behavior.CommitThenKill]: commit, then kill this process
 *   immediately — the client never sees any response and persists
 *   nothing (R05; used by ProcessDeathPhaseA only).
 */
class LedgerTransport(
    private val ledger: ServerLedger,
    var behavior: Behavior = Behavior.CommitThenMatch,
) : TransportClient {

    enum class Behavior {
        CommitThenMatch,
        CommitThenAckLoss,
        CommitThenSocketTimeout,
        CommitThenKill,
    }

    override suspend fun post(
        request: TransportRequest,
        onSocketOpen: suspend () -> Boolean,
    ): TransportResult {
        // Mirror the real client ordering: the SOCKET_OPEN checkpoint
        // runs before the first request byte (here: before the commit).
        if (!onSocketOpen()) return TransportResult.CheckpointFailed
        val dedupeKey = try {
            JSONObject(request.body).optString("dedupe_key")
        } catch (e: Exception) {
            ""
        }
        val isFirstPost = ledger.recordPost(dedupeKey)
        if (!isFirstPost) {
            // Idempotent replay: the key already committed — answer
            // the recorded outcome, never a second effect.
            return FakeTransport.matchedResponse()
        }
        return when (behavior) {
            Behavior.CommitThenMatch -> FakeTransport.matchedResponse()
            Behavior.CommitThenAckLoss -> TransportResult.NetworkError("read_timeout")
            Behavior.CommitThenSocketTimeout -> TransportResult.NetworkError("write_timeout")
            Behavior.CommitThenKill -> {
                // The commit above is fsynced; now die before the
                // client sees anything. The flush never completes,
                // the queue is never re-saved — exactly the R05 window.
                Process.killProcess(Process.myPid())
                // Unreachable; keeps the compiler (and the reader) honest.
                TransportResult.NetworkError("io")
            }
        }
    }
}
