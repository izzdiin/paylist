package com.netter.paylist

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.netter.paylist.collector.CaptureCoordinator
import com.netter.paylist.collector.NotificationNormalizer

/**
 * Tangkap notifikasi pembayaran dari aplikasi APA PUN yang terdaftar sebagai
 * paket sumber di salah satu rute (GoPay Merchant hari ini; OVO, Shopee,
 * mobile banking, dll. tinggal ditambahkan sebagai rute/paket baru).
 *
 * Stage D routing (design §8.3 C21, file plan §A): the callback is
 * routing only — it reads the notification fields synchronously,
 * builds an in-memory snapshot, hands it to the coordinator's
 * capture executor, and returns immediately. It never acquires the
 * cutover lock, never touches a store, and never calls
 * `Sender.enqueueChain` directly. Keyword matching, amount parsing,
 * PrivacyGate classification, staging and the mode-gated enqueue
 * all run on the capture executor thread ([CaptureCoordinator]).
 *
 * Pindai ulang (v2.2): notifikasi yang masih aktif di shade dapat dibaca
 * ulang dan dimasukkan ke jalur yang sama — otomatis tiap listener
 * tersambung, dan manual lewat tombol di layar utama (broadcast ACTION_RESCAN).
 * Aman diulang: dedupe_key memakai postTime asli notifikasi, jadi server
 * menolak salinan yang sudah pernah diterima, dan rescan mendupe di Room
 * pada `dedupe_key` yang sama (design §6).
 */
/** Open for the instrumented test subclass (`TestableListenerService`). */
open class PaylistListenerService : NotificationListenerService() {

    companion object {
        const val ACTION_RESCAN = "com.netter.paylist.ACTION_RESCAN"
        private const val RESCAN_WINDOW_MS = 24L * 3600L * 1000L
    }

    private var rescanReceiver: BroadcastReceiver? = null

    /** The capture coordinator, from the Application wiring. */
    internal fun captureCoordinator(): CaptureCoordinator? =
        (applicationContext as? PaylistApplication)?.captureCoordinator

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
     * Hasilnya diringkas di Store.lastRescan + log lokal. Setiap notifikasi
     * diserahkan ke capture executor yang sama dengan jalur live (design
     * §8.3 (9)), jadi kunci C3 konsisten di kedua jalur.
     */
    fun rescanActive(sumber: String) {
        val store = Store(this)
        val coordinator = captureCoordinator()
        if (coordinator == null) {
            store.setLastRescan("gagal: capture coordinator tidak tersedia")
            store.logLocal("pindai ulang ($sumber): gagal, capture coordinator tidak tersedia")
            return
        }
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
        var diserahkan = 0
        for (sbn in actives) {
            val pkg = sbn.packageName ?: continue
            if (pkg !in enabledPkgs) continue
            if (sbn.postTime < batas) continue
            if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            ditemukan++
            // Only a QUEUED receipt counts as handed off: an
            // overflow-dropped rescan re-submission is still in the
            // shade and will be picked up by the next rescan, but it
            // was not received this time (its durable overflow
            // record is the evidence).
            if (coordinator.capture(
                    NotificationNormalizer.from(sbn, System.currentTimeMillis())
                ) == CaptureCoordinator.CaptureReceipt.QUEUED
            ) {
                diserahkan++
            }
        }
        val ringkas = "$ditemukan notifikasi rute aktif (<24 jam), $diserahkan diserahkan ke capture"
        store.setLastRescan(ringkas)
        store.logLocal("pindai ulang ($sumber): $ringkas")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Group summaries are never captured (live path too, §8.3 (1′)).
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.packageName == null) return
        val coordinator = captureCoordinator()
        if (coordinator == null) {
            // Never in production (the manifest declares
            // PaylistApplication); without the coordinator there is
            // no Room database to capture into. Loud, not silent.
            Store(this).logLocal("capture gagal: coordinator tidak tersedia (${sbn.packageName})")
            return
        }
        // The receipt is the handoff outcome, not an acceptance ack:
        // QUEUED means RECEIVED (in the queue); ACCEPTED means the
        // staging write completed on the executor thread. An
        // overflow drop is loud here and durably recorded by the
        // coordinator (OVERFLOW_DROPPED capture-log row); the
        // notification is recoverable via rescan while still posted.
        val receipt = coordinator.capture(
            NotificationNormalizer.from(sbn, System.currentTimeMillis())
        )
        if (receipt == CaptureCoordinator.CaptureReceipt.DROPPED_OVERFLOW) {
            Store(this).logLocal(
                "capture antrean penuh, notifikasi terbuang sebelum staging " +
                    "(${sbn.packageName} @${sbn.postTime}); pindai ulang selagi masih aktif",
            )
        }
    }
}
