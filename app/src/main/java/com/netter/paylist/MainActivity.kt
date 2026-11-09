package com.netter.paylist

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Layar utama Paylist: kartu status (perangkat, antrean, kegagalan terakhir),
 * daftar rute berbentuk kartu (urutan = prioritas), tambah rute, pintas Akses
 * Notifikasi, dan log dua bagian (paket terdaftar vs belum terdaftar).
 * Semua tampilan dibangun dari kode
 * (tanpa layout XML). Daftar dibangun ulang tiap onResume supaya hasil edit
 * langsung terlihat.
 */
class MainActivity : Activity() {
    companion object {
        private val TEAL = Color.rgb(0, 131, 116)      // brand #008374
        private val TEAL_DARK = Color.rgb(0, 105, 92)
        private val DARK = Color.rgb(23, 42, 40)       // brand #172a28
        private val GRAY = Color.rgb(91, 107, 104)
        private val BG = Color.rgb(244, 246, 245)
        private val LINE = Color.rgb(214, 224, 221)
        private val RED = Color.rgb(185, 28, 28)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cardLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), LINE)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, dp(12))
        layoutParams = lp
    }

    private fun primaryButton(text: String): Button = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(TEAL)
            cornerRadius = dp(10).toFloat()
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(6), 0, 0)
        layoutParams = lp
    }

    private fun ghostButton(text: String): Button = Button(this).apply {
        this.text = text
        setTextColor(TEAL_DARK)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), TEAL)
        }
    }

    private fun smallButton(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(TEAL_DARK)
        background = GradientDrawable().apply {
            setColor(Color.rgb(232, 242, 240))
            cornerRadius = dp(8).toFloat()
        }
        val lp = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        lp.setMargins(dp(3), dp(8), dp(3), 0)
        layoutParams = lp
    }

    /** Satu kartu log: judul + jumlah entri, isi 40 entri terakhir. */
    private fun logCardView(title: String, entries: List<LogEntry>): LinearLayout {
        val card = cardLayout()
        card.addView(TextView(this).apply {
            text = "$title (${entries.size})"
            textSize = 12f
            setTextColor(TEAL)
            setTypeface(typeface, Typeface.BOLD)
        })
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val body = if (entries.isEmpty()) "(belum ada)"
        else entries.takeLast(40).joinToString("\n") { e ->
            val jam = if (e.t > 0) fmt.format(java.util.Date(e.t)) + " " else ""
            val kali = if (e.n > 1) " (×${e.n})" else ""
            "$jam${e.text}$kali"
        }
        card.addView(TextView(this).apply {
            text = body
            textSize = 12f
            setTextColor(DARK)
            setPadding(0, dp(6), 0, 0)
        })
        return card
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store(this).seedIfFirstRun()
        HeartbeatReceiver.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        buildUi()
    }

    private fun buildUi() {
        val store = Store(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(18), dp(16), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "Paylist"
            textSize = 26f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Payment Listener — meneruskan notifikasi pembayaran ke endpoint billing"
            textSize = 13f
            setTextColor(GRAY)
            setPadding(0, dp(2), 0, dp(14))
        })

        // --- Kartu status ---
        val statusCard = cardLayout()
        statusCard.addView(TextView(this).apply {
            text = "STATUS"
            textSize = 12f
            setTextColor(TEAL)
            setTypeface(typeface, Typeface.BOLD)
        })
        val deviceField = EditText(this).apply {
            hint = "Label perangkat (mis. g57)"
            setText(store.deviceLabel())
            textSize = 15f
        }
        statusCard.addView(deviceField)
        val saveDeviceBtn = ghostButton("Simpan Label Perangkat")
        saveDeviceBtn.setOnClickListener {
            store.setDeviceLabel(deviceField.text.toString())
            store.logLocal("label perangkat disimpan")
            buildUi()
        }
        statusCard.addView(saveDeviceBtn)
        val qSize = store.queue().size
        statusCard.addView(TextView(this).apply {
            text = if (qSize == 0) "Antrean pengiriman: bersih"
            else "Antrean pengiriman: $qSize item tertahan (dicoba lagi berkala)"
            textSize = 14f
            setTextColor(if (qSize == 0) DARK else RED)
            setPadding(0, dp(10), 0, 0)
        })
        val failure = store.lastFailure()
        if (failure.isNotEmpty()) {
            statusCard.addView(TextView(this).apply {
                text = "Kegagalan terakhir: $failure"
                textSize = 13f
                setTextColor(RED)
                setPadding(0, dp(4), 0, 0)
            })
        }
        val rescan = store.lastRescan()
        if (rescan.isNotEmpty()) {
            statusCard.addView(TextView(this).apply {
                text = "Pindai ulang terakhir: $rescan"
                textSize = 13f
                setTextColor(GRAY)
                setPadding(0, dp(4), 0, 0)
            })
        }
        val rescanBtn = ghostButton("Pindai Ulang Notifikasi Aktif")
        rescanBtn.setOnClickListener {
            // Minta listener membaca ulang notifikasi yang masih aktif di
            // shade. Bila listener belum tersambung, requestRebind memicu
            // penyambungan — dan penyambungan selalu diikuti pindai otomatis.
            sendBroadcast(
                Intent(PaylistListenerService.ACTION_RESCAN).setPackage(packageName)
            )
            NotificationListenerService.requestRebind(
                ComponentName(this@MainActivity, PaylistListenerService::class.java)
            )
            store.logLocal("pindai ulang notifikasi aktif diminta dari layar utama")
            Handler(Looper.getMainLooper()).postDelayed({ buildUi() }, 2500)
        }
        statusCard.addView(rescanBtn)
        val notifBtn = primaryButton("Buka Pengaturan Akses Notifikasi")
        notifBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        statusCard.addView(notifBtn)
        root.addView(statusCard)

        // --- Daftar rute ---
        root.addView(TextView(this).apply {
            text = "RUTE (atas = prioritas pertama)"
            textSize = 12f
            setTextColor(TEAL)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        })
        val routes = store.routes()
        routes.forEachIndexed { i, r ->
            val card = cardLayout()
            val headRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headRow.addView(TextView(this).apply {
                text = "${i + 1}. ${r.name}"
                textSize = 16f
                setTextColor(DARK)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            headRow.addView(TextView(this).apply {
                text = if (r.enabled) "AKTIF" else "NONAKTIF"
                textSize = 11f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    setColor(if (r.enabled) TEAL else GRAY)
                    cornerRadius = dp(9).toFloat()
                }
            })
            card.addView(headRow)
            card.addView(TextView(this).apply {
                text = "paket: ${r.packages.joinToString(", ")}\nurl: ${r.url}"
                textSize = 13f
                setTextColor(GRAY)
                setPadding(0, dp(4), 0, 0)
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val editBtn = smallButton("Edit")
            editBtn.setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, RouteEditActivity::class.java)
                        .putExtra("route_id", r.id)
                )
            }
            row.addView(editBtn)
            val upBtn = smallButton("▲ Naik")
            upBtn.setOnClickListener { store.moveRoute(r.id, -1); buildUi() }
            row.addView(upBtn)
            val downBtn = smallButton("▼ Turun")
            downBtn.setOnClickListener { store.moveRoute(r.id, +1); buildUi() }
            row.addView(downBtn)
            val testBtn = smallButton("Kirim Tes")
            testBtn.setOnClickListener {
                val payload = JSONObject().apply {
                    put("type", "test")
                    put("device", store.deviceLabel())
                    put("pkg", "test")
                    put("dedupe_key", "test-${r.id}-${System.currentTimeMillis()}")
                    put("title", "Tes")
                    put("text", "tes endpoint dari Paylist")
                }
                Sender(this@MainActivity).enqueueToRoute(payload, r.id)
                store.logLocal("tes diantrekan ke rute ${r.name}; hasil kirimnya tercatat di log ini")
                buildUi()
            }
            row.addView(testBtn)
            card.addView(row)
            root.addView(card)
        }

        val addBtn = primaryButton("+ Tambah Rute")
        addBtn.setOnClickListener {
            startActivity(Intent(this, RouteEditActivity::class.java))
        }
        root.addView(addBtn)

        // --- Log dua bagian (v2.3) ---
        // Pembagian dinilai dari daftar paket rute SAAT INI: entri berpaket
        // yang paketnya ada di rute mana pun (plus entri tanpa paket =
        // event rute/aplikasi) masuk bagian terdaftar; entri berpaket lain
        // masuk bagian belum terdaftar.
        val registeredPkgs = store.routes().flatMap { it.packages }.toSet()
        val allEntries = store.logEntries()
        val regEntries = allEntries.filter {
            it.pkg.isEmpty() || it.pkg in registeredPkgs
        }
        val unregEntries = allEntries.filter {
            it.pkg.isNotEmpty() && it.pkg !in registeredPkgs
        }
        root.addView(logCardView("LOG PAKET TERDAFTAR", regEntries))
        root.addView(logCardView("LOG PAKET BELUM TERDAFTAR", unregEntries))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        })
    }
}

/** Heartbeat tiap 15 menit ke SEMUA rute aktif, lalu jadwalkan berikutnya. */
class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            // Setelah reboot: cukup jadwalkan ulang heartbeat + flush antrean.
            Thread { Sender(ctx).flush() }.start()
            schedule(ctx)
            return
        }
        val store = Store(ctx)
        Thread {
            val sender = Sender(ctx)
            store.routes().filter { it.enabled && it.url.isNotEmpty() && it.secret.isNotEmpty() }
                .forEach { r ->
                    val payload = JSONObject().apply {
                        put("type", "heartbeat")
                        put("device", store.deviceLabel())
                        put("pkg", "")
                        put(
                            "dedupe_key",
                            "hb-${r.id}-${System.currentTimeMillis() / 900000}"
                        )
                        put("title", "")
                        put("text", "")
                    }
                    sender.enqueueToRoute(payload, r.id)
                }
        }.start()
        schedule(ctx)
    }

    companion object {
        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, HeartbeatReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val at = System.currentTimeMillis() + 15 * 60 * 1000
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e: Exception) {
                // Android 12+: izin alarm eksak bisa belum diberikan; pakai inexact.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }
}
