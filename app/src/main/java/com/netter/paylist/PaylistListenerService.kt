package com.netter.paylist

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Tangkap notifikasi pembayaran dari aplikasi APA PUN yang terdaftar sebagai
 * paket sumber di salah satu rute (GoPay Merchant hari ini; OVO, Shopee,
 * mobile banking, dll. tinggal ditambahkan sebagai rute/paket baru).
 *
 * Privasi: isi notifikasi hanya dibaca untuk paket yang terdaftar di rute
 * aktif DAN lolos kata kunci rute itu. Paket lain hanya dicatat NAMANYA di
 * log lokal (untuk menemukan nama paket aplikasi baru), isinya tidak pernah
 * dibaca untuk dikirim.
 *
 * Parser nominal umum: pola "Rp" + angka (pemisah ribuan titik/koma/spasi),
 * atau pola "IDR" + angka ala bank (format Inggris "36,000.00" maupun format
 * Indonesia "1.234.567,00" — kelompok 2 digit terakhir sesudah pemisah
 * terakhir dianggap desimal/sen dan dibuang). Bila dua-duanya ada, yang
 * muncul lebih dulu di teks yang dipakai. Format asli tiap aplikasi dipelajari
 * dari log lokal melalui entri "target-lain" / "target-tanpa-nominal" sebelum
 * kata kuncinya disesuaikan.
 *
 * Pindai ulang (v2.2): notifikasi yang masih aktif di shade dapat dibaca
 * ulang dan dimasukkan ke jalur yang sama — otomatis tiap listener
 * tersambung, dan manual lewat tombol di layar utama (broadcast ACTION_RESCAN).
 * Aman diulang: dedupe_key memakai postTime asli notifikasi, jadi server
 * menolak salinan yang sudah pernah diterima.
 */
class PaylistListenerService : NotificationListenerService() {

    companion object {
        const val ACTION_RESCAN = "com.netter.paylist.ACTION_RESCAN"
        private const val RESCAN_WINDOW_MS = 24L * 3600L * 1000L
    }

    private val amountPattern =
        Pattern.compile("Rp\\s?([0-9]{1,3}(?:[.,\\s][0-9]{3})+|[0-9]+)")
    private val idrPattern =
        Pattern.compile(
            "IDR\\s?([0-9]{1,3}(?:[.,][0-9]{3})+(?:[.,][0-9]{2})?|[0-9]+(?:[.,][0-9]{2})?)",
            Pattern.CASE_INSENSITIVE
        )

    private var rescanReceiver: BroadcastReceiver? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        registerRescanReceiver()
        Thread { rescanActive("otomatis") }.start()
    }

    override fun onListenerDisconnected() {
        unregisterRescanReceiver()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        unregisterRescanReceiver()
        super.onDestroy()
    }

    private fun registerRescanReceiver() {
        if (rescanReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Thread { rescanActive("manual") }.start()
            }
        }
        val filter = IntentFilter(ACTION_RESCAN)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        rescanReceiver = receiver
    }

    private fun unregisterRescanReceiver() {
        val r = rescanReceiver ?: return
        try {
            unregisterReceiver(r)
        } catch (e: Exception) {
            // Receiver sudah terlepas: abaikan.
        }
        rescanReceiver = null
    }

    /**
     * Pindai ulang notifikasi yang MASIH aktif di shade: hanya yang paketnya
     * terdaftar di rute aktif, bukan ringkasan grup, dan umurnya < 24 jam.
     * Hasilnya diringkas di Store.lastRescan + log lokal.
     */
    fun rescanActive(sumber: String) {
        val store = Store(this)
        val actives = try {
            activeNotifications
        } catch (e: Exception) {
            null
        }
        if (actives == null) {
            store.setLastRescan("gagal membaca notifikasi aktif")
            store.logLocal("pindai ulang ($sumber): gagal membaca notifikasi aktif")
            return
        }
        val enabledPkgs = store.routes().filter { it.enabled }
            .flatMap { it.packages }.toSet()
        val batas = System.currentTimeMillis() - RESCAN_WINDOW_MS
        var ditemukan = 0
        var diantrekan = 0
        for (sbn in actives) {
            val pkg = sbn.packageName ?: continue
            if (pkg !in enabledPkgs) continue
            if (sbn.postTime < batas) continue
            if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            ditemukan++
            if (processNotification(sbn)) diantrekan++
        }
        val ringkas = "$ditemukan notifikasi rute aktif (<24 jam), $diantrekan diantrekan ulang"
        store.setLastRescan(ringkas)
        store.logLocal("pindai ulang ($sumber): $ringkas")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val store = Store(this)
        val routes = store.routes().filter { it.enabled && pkg in it.packages }
        if (routes.isEmpty()) {
            // Observasi paket: simpan nama paket saja, bukan isi notifikasi.
            store.logEvent(pkg, "paket-terlihat: $pkg (belum di daftar rute)")
            return
        }
        processNotification(sbn)
    }

    /**
     * Jalur proses bersama untuk notifikasi baru dan hasil pindai ulang:
     * kata kunci per rute -> nominal -> antrekan rantai.
     * @return true bila payload diantrekan ke Sender.
     */
    private fun processNotification(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName ?: return false
        val store = Store(this)
        val routes = store.routes().filter { it.enabled && pkg in it.packages }
        if (routes.isEmpty()) return false
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val combined = "$title $text $bigText"
        val lower = combined.lowercase()
        // Kata kunci dinilai per rute: rantai hanya berisi rute yang
        // kata kuncinya cocok dengan isi notifikasi ini.
        val matched = routes.filter { r -> r.keywords.any { it in lower } }
        if (matched.isEmpty()) {
            store.logEvent(pkg, "target-lain: $pkg - ${title.take(40)}")
            return false
        }
        val amount = parseAmount(combined)
        if (amount == null) {
            store.logEvent(pkg, "target-tanpa-nominal: $pkg - ${title.take(40)}")
            return false
        }

        val payload = JSONObject().apply {
            put("type", "payment")
            put("device", store.deviceLabel())
            put("pkg", pkg)
            put("amount", amount)
            put("title", title.take(500))
            put("text", (text.ifEmpty { bigText }).take(500))
            put("notif_time", java.time.Instant.ofEpochMilli(sbn.postTime).toString())
            put("dedupe_key", "$pkg|${sbn.postTime}|$amount|${combined.take(40).hashCode()}")
        }
        store.logEvent(
            pkg,
            "payment: Rp$amount dari $pkg -> " +
                matched.joinToString(" > ") { it.name }
        )
        Sender(this).enqueueChain(payload, matched.map { it.id })
        return true
    }

    /**
     * Ambil nominal pertama dari teks: pola "Rp" atau "IDR", mana yang muncul
     * lebih dulu. Pemisah ribuan (titik/spasi/koma) dibuang; untuk IDR,
     * kelompok 2 digit terakhir sesudah pemisah terakhir dianggap desimal
     * ("36,000.00" -> 36000; "1.234.567,00" -> 1234567; "36.000" -> 36000).
     */
    fun parseAmount(text: String): Long? {
        val rm = amountPattern.matcher(text)
        val rFound = rm.find()
        val im = idrPattern.matcher(text)
        val iFound = im.find()
        if (!rFound && !iFound) return null
        if (rFound && (!iFound || rm.start() <= im.start())) {
            val digits = rm.group(1).orEmpty().replace(Regex("[.,\\s]"), "")
            return digits.toLongOrNull()
        }
        return normalizeIdr(im.group(1).orEmpty())
    }

    /** Normalkan token nominal IDR (lihat parseAmount) menjadi rupiah bulat. */
    private fun normalizeIdr(token: String): Long? {
        var t = token.trim()
        if (t.isEmpty()) return null
        val lastSep = maxOf(t.lastIndexOf('.'), t.lastIndexOf(','))
        if (lastSep >= 0 && t.length - lastSep - 1 == 2) {
            t = t.substring(0, lastSep)
        }
        val digits = t.replace(Regex("[.,\\s]"), "")
        return digits.toLongOrNull()
    }
}
