package com.netter.paylist

import android.content.Context
import com.netter.paylist.delivery.HttpTransportClient
import com.netter.paylist.delivery.LegacyResponseClassifier
import com.netter.paylist.delivery.ResponseClassification
import com.netter.paylist.delivery.TransportClient
import com.netter.paylist.delivery.TransportRequest
import com.netter.paylist.delivery.TransportResult
import com.netter.paylist.security.SecretProvider
import com.netter.paylist.security.SecretResult
import com.netter.paylist.security.SecretVault
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.concurrent.withLock

/**
 * The app's single legacy sender (mode-gated, vault-backed). Falls
 * back to an unfenced sender only when the Application is not
 * [PaylistApplication] (never in production).
 */
fun Context.appSender(): Sender =
    (applicationContext as? PaylistApplication)?.sender
        ?: Sender(this, ModeGate.ALWAYS_LEGACY)

/**
 * Mesin pengiriman antrean dengan rute.
 *
 * Dua macam item antrean:
 * - "single": payload untuk SATU rute (heartbeat, tes). Selesai begitu rute
 *   itu menjawab apa pun (2xx atau 4xx); gagal jaringan/5xx berarti antre lagi.
 * - "chain": payload pembayaran untuk RANTAI rute berurutan mengikuti
 *   prioritas. Rute yang menjawab match.status = 'matched' (atau 'ambiguous')
 *   adalah pemilik pembayaran: rantai berhenti. Rute yang menjawab 'logged'
 *   (tidak cocok): lanjut ke rute berikutnya. Gagal jaringan/5xx di tengah
 *   rantai: posisi disimpan di item, dicoba lagi nanti DARI rute yang sama.
 *
 * Pelajaran insiden 5-6 Nov 2026 (antrean macet total karena satu rute mati):
 * flush TIDAK BOLEH berhenti di item pertama yang gagal. Satu item yang
 * tertahan hanya menahan dirinya sendiri; item lain tetap diproses. Item
 * heartbeat yang sudah kedaluwarsa (lebih dari 2 jendela 15 menit) dibuang
 * tanpa dikirim, karena heartbeat lama tidak berguna. Setiap kegagalan
 * dicatat ke log lokal DENGAN ALASANNYA (kode HTTP / jenis exception) dan
 * diringkas di Store.lastFailure supaya terlihat di layar utama.
 *
 * Header secret tetap X-QRIS-Secret: <redacted>
 * yang sudah berjalan (notify.php), bukan nama app.
 *
 * Stage C (design §8, §5.4, §5.5):
 * - The queue lock is the shared fair [SenderLock]: the cutover
 *   coordinator takes the same lock (timed, 45 s) before committing
 *   `DRAINING`, so no flush is running at the commit. Quiescence for
 *   the legacy path comes from the lock plus the mode flag — the
 *   legacy flush writes NO attempt rows (legacy items have no Room
 *   delivery to attach them to; the attempt protocol is the worker's
 *   path, and the queue file + `dedupe_key` idempotency cover the
 *   crash windows instead, T22).
 * - Every POST start requires the lock plus a `LEGACY_ONLY` check
 *   performed under the lock ([modeGate]). Past the `DRAINING`
 *   commit, no POST can start.
 * - POSTs go through [TransportClient] with [LegacyResponseClassifier]
 *   semantics shared with the worker: redirects never followed (C9),
 *   malformed 2xx retries the same hop, 4xx halts the chain (§5.5
 *   row 3 — the item stays queued at the same hop instead of
 *   advancing, so no payment is lost to a rejected hop), unknown
 *   hops keep the item queued at the same hop.
 * - Known legacy limitations (the Room worker handles these properly
 *   post-cutover): a matched-but-unconfirmed hop (apply error/absent)
 *   removes the item with a log line (no HELD state exists here);
 *   a 4xx keeps the item queued and re-posts it on every flush until
 *   the operator fixes the route.
 */
class Sender(
    private val ctx: Context,
    private val modeGate: ModeGate,
    private val secrets: SecretProvider = SecretVault(ctx),
    private val transport: TransportClient = HttpTransportClient(),
    /** Stage D §7.3A: the raw-snapshot store (null = B disabled, stored payload only). */
    private val rawSnapshotStore: com.netter.paylist.collector.RawSnapshotStore? = null,
    /** Stage D §7.3: per-route consent records (null = B unverifiable). */
    private val consentStore: com.netter.paylist.collector.ConsentStore? = null,
) {

    fun enqueueToRoute(payload: JSONObject, routeId: String) {
        val item = JSONObject().apply {
            put("kind", "single")
            put("route_id", routeId)
            put("payload", payload)
        }
        enqueueItem(item, payload.optString("dedupe_key") + "|single|" + routeId)
    }

    fun enqueueChain(payload: JSONObject, routeIds: List<String>) {
        val item = JSONObject().apply {
            put("kind", "chain")
            put("route_ids", org.json.JSONArray(routeIds))
            put("next", 0)
            put("payload", payload)
        }
        enqueueItem(item, payload.optString("dedupe_key") + "|chain|" + routeIds.joinToString(","))
    }

    /**
     * Synchronous chain enqueue (Stage D capture path, design §8.3
     * (2′)): the item is added under the fair lock with a timed
     * acquire (5 s — the commit hold contains no network and no
     * import) and written via `saveQueueSync` (`commit()` +
     * readback). Returns true only when the item is durably in the
     * queue — either newly written (commit + readback agree) or
     * already present (idempotent re-enqueue by `qkey`).
     *
     * A false return means the caller must NOT delete its staging
     * row: the staging sweep retries the enqueue later. A flush is
     * started (background thread) only on success.
     */
    fun enqueueChainSync(payload: JSONObject, routeIds: List<String>): Boolean {
        val qkey = payload.optString("dedupe_key") + "|chain|" + routeIds.joinToString(",")
        val locked = try {
            SenderLock.lock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        if (!locked) return false
        val written = try {
            val store = Store(ctx)
            val q = store.queue()
            if (q.none { it.optString("qkey") == qkey }) {
                val item = JSONObject().apply {
                    put("kind", "chain")
                    put("route_ids", org.json.JSONArray(routeIds))
                    put("next", 0)
                    put("payload", payload)
                    put("qkey", qkey)
                }
                q.add(item)
                if (!store.saveQueueSync(q)) return false
                // Readback: the item must be present after the commit.
                if (store.queue().none { it.optString("qkey") == qkey }) return false
            }
            true
        } finally {
            SenderLock.lock.unlock()
        }
        if (written) Thread { flush() }.start()
        return written
    }

    private fun enqueueItem(item: JSONObject, qkey: String) {
        SenderLock.lock.withLock {
            val store = Store(ctx)
            val q = store.queue()
            if (q.none { it.optString("qkey") == qkey }) {
                item.put("qkey", qkey)
                q.add(item)
                store.saveQueue(q)
            }
        }
        // Jaringan tidak boleh di thread utama: flush selalu di thread latar.
        Thread { flush() }.start()
    }

    fun flush() {
        SenderLock.lock.withLock { flushLocked() }
    }

    private fun flushLocked() {
        // Mode gate at flush entry (defence in depth; the per-POST
        // check inside processItem is the normative one).
        if (!modeGate.isLegacyOnly()) return
        val store = Store(ctx)
        // Proses SEMUA item; yang belum selesai tetap di antrean dengan
        // urutan asal. Kegagalan satu item tidak menghentikan item lain.
        val remaining = mutableListOf<JSONObject>()
        for (item in store.queue()) {
            if (!processItem(store, item)) remaining.add(item)
        }
        store.saveQueue(remaining)
    }

    /** Label singkat payload untuk log: "heartbeat" / "tes" / "pembayaran RpX". */
    private fun describe(payload: JSONObject): String {
        return when (payload.optString("type")) {
            "heartbeat" -> "heartbeat"
            "test" -> "tes"
            "payment" -> "pembayaran Rp${payload.optLong("amount")}"
            else -> payload.optString("type", "event")
        }
    }

    /** Catat kegagalan pada item + log lokal (dibatasi supaya log tidak banjir). */
    private fun markFail(store: Store, item: JSONObject, what: String, routeName: String, error: String) {
        val attempts = item.optInt("attempts", 0) + 1
        val prevError = item.optString("last_error")
        item.put("attempts", attempts)
        item.put("last_error", error)
        item.put("last_try", System.currentTimeMillis())
        store.setLastFailure("$what ke rute $routeName: $error")
        if (attempts <= 2 || prevError != error) {
            store.logLocal("gagal: $what ke rute $routeName: $error (percobaan $attempts, dicoba lagi nanti)")
        }
    }

    /** @return true bila item selesai dan boleh dihapus dari antrean. */
    private fun processItem(store: Store, item: JSONObject): Boolean {
        val payload = item.getJSONObject("payload")
        if (item.optString("kind") == "chain") {
            val ids = Route.jsonStrings(item.optJSONArray("route_ids"))
            var next = item.optInt("next", 0)
            while (next < ids.size) {
                val route = store.routeById(ids[next])
                if (route == null || !route.enabled || route.url.isEmpty()) {
                    next++
                    continue
                }
                // Stage C: the secret comes from the vault, never from
                // the route record. No secret (or an unreadable one)
                // means this hop cannot be attempted: skip it (NO_POST,
                // §5.5 row 6) and record the reason visibly.
                val secret = when (val s = secrets.secretFor(route.id)) {
                    is SecretResult.Present -> s.value
                    is SecretResult.Missing -> {
                        next++
                        continue
                    }
                    is SecretResult.Unavailable -> {
                        store.setLastFailure(
                            "secret rute ${route.name} tidak bisa dibaca (${s.reason}); masukkan ulang secret",
                        )
                        next++
                        continue
                    }
                }
                // Normative mode check before every POST (§8): the
                // mode cannot change mid-flush (the coordinator needs
                // this lock first), so this is defence in depth.
                if (!modeGate.isLegacyOnly()) {
                    item.put("next", next)
                    return false
                }
                // §7.3A(d): the legacy flush takes the raw snapshot
                // at its per-hop claim step for B items. A null
                // resolution means the item is HELD — never POSTed
                // (raw or redacted) until the operator clears it.
                val hop = resolveHopBody(item, payload, route, store)
                if (hop == null) {
                    item.put("next", next)
                    return false
                }
                val res = post(route, hop.payload, secret) { hop.checkpoint() }
                if (hop.fenceAborted) {
                    // B3: revoke observed at the final admission
                    // read — raw dropped unsent, the item is held
                    // at this hop, no further raw POST follows.
                    holdRawBlocked(item, store, route, "route_revoked")
                    item.put("next", next)
                    return false
                }
                if (res.retryable) {
                    // Simpan posisi; item tertahan, item LAIN tetap diproses.
                    item.put("next", next)
                    markFail(store, item, describe(payload), route.name, res.error ?: "gagal jaringan")
                    return false
                }
                if (res.error != null) {
                    store.logLocal("rute ${route.name}: ${res.error} (untuk ${describe(payload)})")
                }
                if (res.matched) {
                    if (item.optInt("attempts", 0) > 0) {
                        store.logLocal("${describe(payload)} akhirnya terkirim ke rute ${route.name}")
                    }
                    return true // diklaim rute ini: rantai selesai
                }
                next++ // tidak cocok di rute ini: lanjut prioritas berikutnya
            }
            return true // semua rute menjawab tidak cocok: selesai
        }
        // Item "single" (heartbeat / tes untuk satu rute).
        val route = store.routeById(item.optString("route_id")) ?: return true
        if (!route.enabled) return true // rute dimatikan: item tidak relevan lagi
        if (route.url.isEmpty()) return true
        // Stage C: the secret comes from the vault (see the chain path above).
        val secret = when (val s = secrets.secretFor(route.id)) {
            is SecretResult.Present -> s.value
            is SecretResult.Missing -> return true // tanpa secret: item tidak bisa dikirim
            is SecretResult.Unavailable -> {
                store.setLastFailure(
                    "secret rute ${route.name} tidak bisa dibaca (${s.reason}); masukkan ulang secret",
                )
                return true
            }
        }
        // Heartbeat kedaluwarsa: jendela dedupe-nya sudah lewat > 2 jendela
        // (30 menit) -> buang, heartbeat lama tidak berguna.
        if (payload.optString("type") == "heartbeat") {
            val bucket = payload.optString("dedupe_key")
                .substringAfterLast('-').toLongOrNull()
            val nowBucket = System.currentTimeMillis() / 900000
            if (bucket != null && nowBucket - bucket >= 2) {
                store.logLocal("heartbeat kedaluwarsa untuk rute ${route.name} dibuang")
                return true
            }
        }
        if (!modeGate.isLegacyOnly()) return false
        val res = post(route, payload, secret)
        if (res.retryable) {
            markFail(store, item, describe(payload), route.name, res.error ?: "gagal jaringan")
            return false
        }
        if (res.error != null) {
            store.logLocal("rute ${route.name}: ${res.error} (untuk ${describe(payload)})")
        } else if (payload.optString("type") == "test") {
            store.logLocal("tes ke rute ${route.name} terkirim OK")
            store.clearLastFailure()
        }
        return true
    }

    private class Outcome(val matched: Boolean, val retryable: Boolean, val error: String?)

    /**
     * The resolved body for one legacy hop (§7.3A/§7.3B). Either
     * the stored (redacted) payload or the raw payload built from
     * a taken snapshot, plus the admission fence for the final
     * re-read before the first write.
     */
    private class HopResolution(
        val payload: JSONObject,
        private val fenceGeneration: Long?,
        private val routeId: String,
        private val rawSnapshotStore: com.netter.paylist.collector.RawSnapshotStore?,
    ) {
        /** True when the fence check observed a revocation. */
        @Volatile
        var fenceAborted: Boolean = false
            private set

        /**
         * §7.3A(e): the generation recorded at the pre-send gate
         * is re-read immediately before the first write, as the
         * LAST step of the checkpoint callback — a plain volatile
         * read, no lock held, no I/O, no suspension point between
         * the read and the write. A mismatch aborts before any
         * byte is written.
         */
        fun checkpoint(): Boolean {
            val fence = fenceGeneration ?: return true
            if (rawSnapshotStore?.fence(routeId) != fence) {
                fenceAborted = true
                return false
            }
            return true
        }
    }

    /**
     * Resolves the body for one legacy chain hop (§7.3A(d)).
     * Returns null when the item must be HELD (never POSTed):
     * the raw opt-in is invalid at claim time (B16), or the item
     * was already held by an earlier fence abort (B3).
     *
     * Non-B items post the stored payload unchanged. A B item
     * whose snapshot is lost posts the stored redacted payload —
     * the legacy-path A′; the full §7.3B gate runs on the Room
     * worker only (Stage D boundary: the legacy queue carries no
     * reconciliation evidence and no attempt rows).
     */
    private fun resolveHopBody(
        item: JSONObject,
        payload: JSONObject,
        route: Route,
        store: Store,
    ): HopResolution? {
        if (!payload.optBoolean("raw_ephemeral", false)) {
            return HopResolution(payload, null, route.id, rawSnapshotStore)
        }
        // A held item stays held until the operator clears it (D3/D5).
        if (item.optBoolean("raw_blocked", false)) return null
        val consent = consentStore
        val snapshots = rawSnapshotStore
        if (consent == null || snapshots == null) {
            // B unverifiable without the stores: post the stored
            // (redacted) payload — no raw is ever transmitted.
            return HopResolution(payload, null, route.id, rawSnapshotStore)
        }
        // Pre-send consent re-check (§7.3): the raw opt-in must be
        // valid NOW, not just at capture. The legacy flush runs on
        // its own thread (not a coroutine), so the suspend consent
        // reads run under runBlocking — the same pattern `post()`
        // already uses for the suspend transport call.
        val (optInValid, currentEpoch) = runBlocking {
            consent.isRawOptInValid(route.id, System.currentTimeMillis()) to consent.epoch(route.id)
        }
        if (!optInValid) {
            holdRawBlocked(item, store, route, "consent_expired")
            return null
        }
        // Take the snapshot (take-don't-copy) with the CURRENT
        // epoch: a revoked-and-regranted route has a new epoch and
        // the old snapshot is unclaimable.
        val snapshot = snapshots.take(
            payload.optString("dedupe_key"), route.id, currentEpoch,
        ) ?: return HopResolution(payload, null, route.id, rawSnapshotStore)
        val raw = JSONObject(payload.toString())
        raw.put("title", snapshot.title.take(500))
        raw.put("text", snapshot.text.take(500))
        raw.remove("redacted")
        raw.remove("inseparable")
        return HopResolution(raw, snapshots.fence(route.id), route.id, rawSnapshotStore)
    }

    /** Holds a B item at its current hop: never POSTed until the operator clears it. */
    private fun holdRawBlocked(item: JSONObject, store: Store, route: Route, reason: String) {
        item.put("raw_blocked", true)
        item.put("last_error", reason)
        store.setLastFailure(
            "pengiriman raw ke rute ${route.name} ditahan ($reason); perlu keputusan operator",
        )
        store.logLocal("item ditahan (raw_blocked, $reason) rute ${route.name}")
    }

    /**
     * Clears the `raw_blocked` hold on one legacy queue item
     * (operator action, D3/D5 review surface). The item resumes
     * flushing with the stored (redacted) payload — the B flag is
     * cleared too, so a held item never posts raw again.
     */
    fun clearRawBlocked(qkey: String): Boolean {
        var cleared = false
        SenderLock.lock.withLock {
            val store = Store(ctx)
            val q = store.queue()
            for (item in q) {
                if (item.optString("qkey") == qkey && item.optBoolean("raw_blocked", false)) {
                    item.remove("raw_blocked")
                    item.optJSONObject("payload")?.remove("raw_ephemeral")
                    cleared = true
                }
            }
            if (cleared) store.saveQueue(q)
        }
        if (cleared) Thread { flush() }.start()
        return cleared
    }

    /**
     * POST satu payload ke satu rute via [TransportClient], classified
     * with the shared §5.4 taxonomy ([LegacyResponseClassifier]).
     * The checkpoint callback is trivially true for non-raw hops;
     * for raw hops it re-reads the §7.3A(e) admission fence.
     */
    private fun post(
        route: Route,
        payload: JSONObject,
        secret: String,
        onCheckpoint: (suspend () -> Boolean)? = null,
    ): Outcome {
        val eventType = payload.optString("type").ifEmpty { "payment" }
        val result = try {
            runBlocking {
                transport.post(
                    TransportRequest(route.url, secret, payload.toString())
                ) { onCheckpoint?.invoke() ?: true }
            }
        } catch (e: Exception) {
            val msg = e.message?.take(90)?.replace('\n', ' ').orEmpty()
            return Outcome(false, true, "${e.javaClass.simpleName}${if (msg.isNotEmpty()) ": $msg" else ""}")
        }
        return when (result) {
            is TransportResult.Http -> {
                val outcome = LegacyResponseClassifier.classify(
                    result.status, result.headers, result.body, eventType
                )
                when (outcome.classification) {
                    ResponseClassification.MATCHED_SETTLED,
                    ResponseClassification.MATCHED_NOT_APPLIED,
                    ResponseClassification.MATCHED_UNCONFIRMED,
                    ResponseClassification.AMBIGUOUS,
                    -> {
                        // Chain stops; the item is done. An unconfirmed
                        // match is logged (no HELD state exists here).
                        val error = if (outcome.classification == ResponseClassification.MATCHED_UNCONFIRMED) {
                            "hasil apply belum terkonfirmasi; periksa log server"
                        } else {
                            null
                        }
                        Outcome(true, false, error)
                    }
                    ResponseClassification.LOGGED,
                    ResponseClassification.ACK,
                    -> Outcome(false, false, null)
                    ResponseClassification.RETRY ->
                        Outcome(false, true, "HTTP ${result.status}")
                    ResponseClassification.BLOCKED -> {
                        // §5.5 row 3: halt — the item stays queued at
                        // this hop (never advances, never lost).
                        val location = outcome.location?.let { "; redirect ke $it" }.orEmpty()
                        Outcome(false, true, "ditolak server (HTTP ${result.status})$location")
                    }
                    ResponseClassification.UNKNOWN_PENDING_RECONCILIATION ->
                        Outcome(false, true, "hasil tidak diketahui (HTTP ${result.status})")
                }
            }
            is TransportResult.NetworkError ->
                Outcome(false, true, result.errorCode)
            is TransportResult.CheckpointFailed ->
                Outcome(false, true, "checkpoint gagal")
        }
    }
}
