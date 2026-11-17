package com.netter.paylist

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Satu rute tujuan: notifikasi dari paket sumber tertentu diteruskan ke satu
 * endpoint billing. Urutan rute di daftar = urutan prioritas untuk paket yang
 * dipakai bersama (mis. satu aplikasi merchant untuk dua sistem billing):
 * endpoint pertama yang menjawab "matched" adalah pemilik pembayaran itu.
 */
data class Route(
    val id: String,
    val name: String,
    val packages: List<String>,
    val url: String,
    val secret: String,
    val keywords: List<String>,
    val enabled: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("packages", JSONArray(packages))
        put("url", url)
        put("secret", secret)
        put("keywords", JSONArray(keywords))
        put("enabled", enabled)
    }

    companion object {
        val DEFAULT_KEYWORDS =
            listOf("qris", "pembayaran", "berhasil", "diterima", "masuk", "transfer")

        fun fromJson(o: JSONObject): Route = Route(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            name = o.optString("name"),
            packages = jsonStrings(o.optJSONArray("packages")),
            url = o.optString("url"),
            secret = o.optString("secret"),
            keywords = jsonStrings(o.optJSONArray("keywords")).ifEmpty { DEFAULT_KEYWORDS },
            enabled = o.optBoolean("enabled", true)
        )

        fun jsonStrings(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
        }
    }
}

/** Satu entri log lokal terstruktur. pkg kosong = event rute/aplikasi. */
data class LogEntry(val t: Long, val pkg: String, val text: String, val n: Int = 1)

/** Penyimpanan konfigurasi + antrean + log lokal, tanpa library eksternal. */
class Store(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE)

    fun deviceLabel(): String = sp.getString("device", "g57").orEmpty()
    fun setDeviceLabel(v: String) {
        sp.edit().putString("device", v.trim()).apply()
    }

    fun routes(): MutableList<Route> {
        val arr = JSONArray(sp.getString("routes", "[]").orEmpty())
        val out = mutableListOf<Route>()
        for (i in 0 until arr.length()) out.add(Route.fromJson(arr.getJSONObject(i)))
        return out
    }

    fun saveRoutes(routes: List<Route>) {
        val arr = JSONArray()
        routes.forEach { arr.put(it.toJson()) }
        sp.edit().putString("routes", arr.toString()).apply()
    }

    fun routeById(id: String): Route? = routes().firstOrNull { it.id == id }

    fun upsertRoute(r: Route) {
        val rs = routes()
        val idx = rs.indexOfFirst { it.id == r.id }
        if (idx >= 0) rs[idx] = r else rs.add(r)
        saveRoutes(rs)
    }

    fun deleteRoute(id: String) {
        saveRoutes(routes().filter { it.id != id })
    }

    /** Geser posisi rute (prioritas): dir = -1 naik, +1 turun. */
    fun moveRoute(id: String, dir: Int) {
        val rs = routes()
        val idx = rs.indexOfFirst { it.id == id }
        val j = idx + dir
        if (idx < 0 || j !in rs.indices) return
        val tmp = rs[idx]
        rs[idx] = rs[j]
        rs[j] = tmp
        saveRoutes(rs)
    }

    /**
     * Pertama kali jalan: siapkan draf rute v2New (URL + paket GoPay Merchant
     * sudah dikenal publik di sistem ini; secret sengaja kosong, diisi pemilik).
     * Paket aplikasi ini berbeda dari QRIS Listener lama, jadi konfigurasi app
     * lama tidak bisa dibaca dari sini dan memang tidak dipindahkan otomatis.
     */
    fun seedIfFirstRun() {
        if (sp.getBoolean("seeded", false)) return
        if (routes().isEmpty()) {
            upsertRoute(
                Route(
                    UUID.randomUUID().toString(),
                    "v2New (Netter)",
                    listOf("com.gojek.gopaymerchant"),
                    "https://bill.netterhome.net/bayarQR/notify.php",
                    "",
                    Route.DEFAULT_KEYWORDS,
                    true
                )
            )
        }
        sp.edit().putBoolean("seeded", true).apply()
    }

    /** Ringkasan kegagalan kirim terakhir, ditampilkan di kartu status. */
    fun setLastFailure(text: String) {
        val jam = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            .format(java.util.Date())
        sp.edit().putString("last_failure", "$jam — $text").apply()
    }

    fun lastFailure(): String = sp.getString("last_failure", "").orEmpty()

    /** Ringkasan pindai ulang notifikasi aktif terakhir, ditampilkan di kartu status. */
    fun setLastRescan(text: String) {
        val jam = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            .format(java.util.Date())
        sp.edit().putString("last_rescan", "$jam — $text").apply()
    }

    fun lastRescan(): String = sp.getString("last_rescan", "").orEmpty()

    fun clearLastFailure() {
        sp.edit().remove("last_failure").apply()
    }

    // --- Log lokal terstruktur (v2.3) ---
    // Entri dari listener membawa nama paket sumbernya; entri rute/aplikasi
    // pkg-nya kosong. Layar utama membagi tampilan menurut daftar paket rute
    // SAAT INI: paket yang ada di rute mana pun masuk bagian "terdaftar",
    // sisanya "belum terdaftar" — jadi riwayat paket yang belakangan
    // didaftarkan otomatis pindah bagian. Log lama format teks (kunci "log")
    // dimigrasikan sekali; nama paketnya dikenali dari pola baris listener.

    fun logEntries(): List<LogEntry> {
        migrateLegacyLogIfNeeded()
        val arr = JSONArray(sp.getString("log_entries", "[]").orEmpty())
        val out = mutableListOf<LogEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                LogEntry(
                    o.optLong("t"), o.optString("pkg"),
                    o.optString("text"), o.optInt("n", 1)
                )
            )
        }
        return out
    }

    private fun saveLogEntries(entries: List<LogEntry>) {
        val arr = JSONArray()
        entries.takeLast(150).forEach { e ->
            arr.put(JSONObject().apply {
                put("t", e.t)
                put("pkg", e.pkg)
                put("text", e.text)
                put("n", e.n)
            })
        }
        sp.edit().putString("log_entries", arr.toString()).apply()
    }

    /** Catat event sebuah paket sumber (dipakai listener). */
    fun logEvent(pkg: String, text: String) {
        val entries = logEntries().toMutableList()
        val last = entries.lastOrNull()
        // "paket-terlihat" dari paket yang sama berturut-turut diringkas
        // jadi satu entri berhitung, supaya log tidak banjir.
        if (pkg.isNotEmpty() && last != null && last.pkg == pkg &&
            last.text.startsWith("paket-terlihat:")
        ) {
            entries[entries.lastIndex] =
                LogEntry(System.currentTimeMillis(), pkg, last.text, last.n + 1)
        } else {
            entries.add(LogEntry(System.currentTimeMillis(), pkg, text))
        }
        saveLogEntries(entries)
    }

    /** Catat event rute/aplikasi (tanpa paket sumber). */
    fun logLocal(line: String) {
        val entries = logEntries().toMutableList()
        entries.add(LogEntry(System.currentTimeMillis(), "", line))
        saveLogEntries(entries)
    }

    fun logText(): String {
        val entries = logEntries()
        if (entries.isEmpty()) return "(belum ada event)"
        return entries.joinToString("\n") { it.text }
    }

    private fun migrateLegacyLogIfNeeded() {
        if (sp.getBoolean("log_migrated_v23", false)) return
        sp.edit().putBoolean("log_migrated_v23", true).apply()
        if (!sp.getString("log_entries", "").isNullOrEmpty()) return
        val legacy = sp.getString("log", "").orEmpty()
        if (legacy.isBlank()) return
        val pkgPatterns = listOf(
            Regex("^paket-terlihat: (\\S+)"),
            Regex("^target-lain: (\\S+)"),
            Regex("^target-tanpa-nominal: (\\S+)"),
            Regex("^payment: .* dari (\\S+) ->")
        )
        val entries = legacy.lines().filter { it.isNotBlank() }.map { line ->
            var pkg = ""
            for (p in pkgPatterns) {
                val m = p.find(line)
                if (m != null) {
                    pkg = m.groupValues[1]
                    break
                }
            }
            LogEntry(0, pkg, line)
        }
        saveLogEntries(entries)
    }

    fun queue(): MutableList<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val arr = JSONArray(sp.getString("queue", "[]").orEmpty())
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
        return out
    }

    fun saveQueue(items: List<JSONObject>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        sp.edit().putString("queue", arr.toString()).apply()
    }

    // --- Stage B (design §8.2, §8.6; file plan §A) ---
    // Migration read-only snapshot access + the synchronous queue write.
    // No mode value lives here: the authoritative mode store is Room
    // `collector_state` (R14); no code path reads mode from
    // SharedPreferences (asserted by the F27 static check).

    /**
     * Read-only snapshot of the legacy queue for the migration
     * ([com.netter.paylist.migration.LegacyQueueMigration]). The
     * returned list is a fresh copy; the migration must never mutate
     * the queue through it. The queue file itself is not modified,
     * not emptied, not moved by snapshotting (§8.2 step 2).
     */
    fun queueSnapshot(): List<JSONObject> = queue()

    /**
     * Synchronous queue write (design §8.2, §8.6): `commit()` plus a
     * readback of the queue key. Returns true only when `commit()`
     * returned true AND the readback agrees (same count, same ordered
     * `qkey` set).
     *
     * Readback, corrected (R19): `commit()` is a synchronous disk
     * write; the getter readback afterwards reads the in-memory cache
     * and confirms only that the in-memory view agrees — it is a
     * sanity check, not crash-proof persistence evidence, and no
     * sweep decision treats it as the latter. The crash-proof
     * evidence is the Room sweep intent, written before the queue is
     * touched. Recovery after a crash re-reads the queue from a fresh
     * process — a new `SharedPreferences` instance, the pre-crash
     * cache gone — so a post-restart readback IS a disk read.
     */
    fun saveQueueSync(items: List<JSONObject>): Boolean {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        val committed = sp.edit().putString("queue", arr.toString()).commit()
        if (!committed) return false
        val readBack = queue()
        if (readBack.size != items.size) return false
        for (i in items.indices) {
            if (readBack[i].optString("qkey") != items[i].optString("qkey")) return false
        }
        return true
    }

    /**
     * Removes ONE queue item by `qkey` (Stage C cutover step 6 only).
     * Clearing the legacy queue after `WORKER_ONLY` is permitted only
     * for individually verified imported/resolved entries (R29);
     * quarantined entries are retained and never removed by this
     * path. There is deliberately NO whole-queue delete in this class
     * (F54): a blanket queue delete must not exist in the tree.
     *
     * @return true when the item was present and the removal committed
     *   with a agreeing readback; false when the item was absent or
     *   the write failed.
     */
    fun clearQueueItem(qkey: String): Boolean {
        val q = queue()
        val idx = q.indexOfFirst { it.optString("qkey") == qkey }
        if (idx < 0) return false
        q.removeAt(idx)
        return saveQueueSync(q)
    }
}
