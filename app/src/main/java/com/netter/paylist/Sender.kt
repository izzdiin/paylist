package com.netter.paylist

import android.content.Context
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

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
 * Header secret tetap X-QRIS-Secret: nama header adalah kontrak endpoint
 * yang sudah berjalan (notify.php), bukan nama app.
 */
class Sender(private val ctx: Context) {
    companion object {
        // Antrean dibaca-tulis dari beberapa thread (listener, heartbeat, UI):
        // kunci statis supaya tidak ada event yang hilang karena balapan.
        private val LOCK = Any()
    }

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

    private fun enqueueItem(item: JSONObject, qkey: String) {
        synchronized(LOCK) {
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
        synchronized(LOCK) { flushLocked() }
    }

    private fun flushLocked() {
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
                if (route == null || !route.enabled ||
                    route.url.isEmpty() || route.secret.isEmpty()
                ) {
                    next++
                    continue
                }
                val res = post(route, payload)
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
        if (route.url.isEmpty() || route.secret.isEmpty()) return true
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
        val res = post(route, payload)
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
     * POST satu payload ke satu rute.
     * - 2xx: Outcome(matched dari blok "match"; 'matched'/'ambiguous' = diklaim).
     * - 4xx: tidak dicoba lagi (retryable=false) dengan alasan HTTP-nya.
     * - 5xx / gagal jaringan: retryable=true dengan alasan (kode / exception).
     */
    private fun post(route: Route, payload: JSONObject): Outcome {
        return try {
            val conn = URL(route.url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-QRIS-Secret", route.secret)
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { w ->
                w.write(payload.toString())
            }
            val code = conn.responseCode
            val body = try {
                val stream =
                    if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.readText().orEmpty()
            } catch (e: Exception) {
                ""
            }
            conn.disconnect()
            when {
                code in 200..299 -> {
                    // Kontrak endpoint notify.php: match.status salah satu dari
                    // 'matched' | 'ambiguous' | 'logged'. 'matched' berarti
                    // pembayaran diklaim rute ini; 'ambiguous' juga menghentikan
                    // rantai (pembayaran parkir di log rute ini untuk ditinjau
                    // manual — meneruskannya bisa melunasi dua sistem sekaligus).
                    // 'logged' / tanpa blok match = bukan milik rute ini.
                    val m = try {
                        JSONObject(body).optJSONObject("match")
                    } catch (e: Exception) {
                        null
                    }
                    val st = m?.optString("status").orEmpty()
                    Outcome(st == "matched" || st == "ambiguous", false, null)
                }
                code in 400..499 -> Outcome(false, false, "ditolak server (HTTP $code)")
                else -> Outcome(false, true, "server menjawab HTTP $code")
            }
        } catch (e: Exception) {
            val msg = e.message?.take(90)?.replace('\n', ' ').orEmpty()
            Outcome(false, true, "${e.javaClass.simpleName}${if (msg.isNotEmpty()) ": $msg" else ""}")
        }
    }
}
