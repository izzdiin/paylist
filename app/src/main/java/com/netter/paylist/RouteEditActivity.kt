package com.netter.paylist

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.netter.paylist.collector.ConsentStore
import com.netter.paylist.security.EndpointValidation
import com.netter.paylist.security.EndpointValidator
import com.netter.paylist.security.SecretVault
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Form tambah/edit satu rute. Secret tidak pernah ditampilkan kembali:
 * field selalu dibuka kosong; menyimpan dengan secret kosong berarti
 * secret lama dipertahankan (sama seperti perilaku app sebelumnya).
 * Secret tersimpan di Keystore (SecretVault), bukan di data rute.
 * URL endpoint divalidasi (HTTPS-only; HTTP butuh konfirmasi eksplisit).
 * Tampilan mengikuti layar utama: terang, kartu, aksen teal brand.
 *
 * D5 (F15/P6): form ini adalah permukaan onboarding per rute —
 * persetujuan penangkapan (eksplisit, per rute) dan opt-in P6
 * penerusan teks mentah (default OFF, berlaku 90 hari) dicatat di
 * [ConsentStore]. Menyimpan hanya menulis bila ada perubahan:
 * grant()/setRawOptIn() menaikkan epoch, dan kenaikan epoch yang
 * tidak perlu akan memblokir pengiriman yang sedang berjalan.
 */
class RouteEditActivity : Activity() {
    companion object {
        private val TEAL = Color.rgb(0, 131, 116)
        private val DARK = Color.rgb(23, 42, 40)
        private val GRAY = Color.rgb(91, 107, 104)
        private val BG = Color.rgb(244, 246, 245)
        private val RED = Color.rgb(185, 28, 28)

        /** Masa berlaku opt-in P6 dari saat diberikan (90 hari). */
        private const val RAW_OPT_IN_VALIDITY_MS = 90L * 24L * 60L * 60L * 1000L
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        val routeId = intent.getStringExtra("route_id")
        val existing = routeId?.let { store.routeById(it) }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(18), dp(16), dp(20))
        }
        layout.addView(TextView(this).apply {
            text = if (existing == null) "Tambah Rute" else "Edit Rute"
            textSize = 22f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        })
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(TEAL)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(3))
        }
        fun field(hint: String, value: String = "") = EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 15f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.rgb(214, 224, 221))
            }
        }
        val nameField = field("mis. v2New (Netter)", existing?.name ?: "")
        val pkgField = field(
            "mis. com.gojek.gopaymerchant",
            existing?.packages?.joinToString(",") ?: ""
        )
        val urlField = field("https://...", existing?.url ?: "")
        val secretField = field(
            if (existing == null) "Secret endpoint"
            else "Kosongkan = tidak diubah"
        )
        val kwField = field(
            "pisah koma",
            (existing?.keywords ?: Route.DEFAULT_KEYWORDS).joinToString(",")
        )
        val enabledBox = CheckBox(this).apply {
            text = "Rute aktif"
            isChecked = existing?.enabled ?: true
            setPadding(0, dp(12), 0, dp(4))
        }

        // --- Persetujuan penangkapan + opt-in P6 (D5/F15) ---
        // Record dimuat di latar belakang; tombol Simpan diblokir
        // sampai record terbaca (rute baru: tidak ada record).
        val app = application as PaylistApplication
        var consentRecord: ConsentStore.RouteConsent? = null
        var consentLoaded = existing == null
        val consentStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(GRAY)
            setPadding(0, dp(2), 0, dp(6))
            text = if (existing == null) "Rute baru: belum ada persetujuan."
            else "Memuat status persetujuan…"
        }
        val consentBox = CheckBox(this).apply {
            text = "Saya menyetujui penangkapan notifikasi untuk rute ini"
            isChecked = false
            isEnabled = existing == null
            setPadding(0, dp(6), 0, dp(2))
        }
        val disclosureText = TextView(this).apply {
            text = "Pembayaran yang ditangkap direkam di perangkat ini. " +
                "Kontrol penghapusan tersedia di layar Riwayat. " +
                "Mencabut persetujuan menghentikan penangkapan baru dan " +
                "memblokir pengiriman yang tertunda; data yang sudah " +
                "terkirim tidak dapat ditarik kembali."
            textSize = 13f
            setTextColor(GRAY)
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        val rawBox = CheckBox(this).apply {
            text = "Teruskan teks notifikasi apa adanya (termasuk kode apa pun)"
            isChecked = false
            isEnabled = existing == null
            setPadding(0, dp(6), 0, dp(2))
        }
        val rawDisclosure = TextView(this).apply {
            text = "Bila dicentang, teks notifikasi diteruskan utuh ke rute " +
                "ini selama 90 hari. Default: teks disensor — kode diganti " +
                "[REDACTED] sebelum dikirim."
            textSize = 13f
            setTextColor(GRAY)
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        if (existing != null) {
            Thread {
                val record = runBlocking { app.consentStore.get(existing.id) }
                val rawValid = runBlocking {
                    app.consentStore.isRawOptInValid(existing.id, System.currentTimeMillis())
                }
                runOnUiThread {
                    consentRecord = record
                    consentLoaded = true
                    consentBox.isEnabled = true
                    rawBox.isEnabled = true
                    consentBox.isChecked = record?.granted ?: false
                    rawBox.isChecked = rawValid
                    consentStatus.text = when {
                        record == null -> "Belum ada persetujuan untuk rute ini."
                        record.granted -> "Persetujuan aktif (epoch ${record.epoch})." +
                            if (rawValid) " Opt-in teks mentah aktif." else ""
                        else -> "Persetujuan dicabut (epoch ${record.epoch}). " +
                            "Penangkapan berhenti; pengiriman tertunda diblokir."
                    }
                }
            }.start()
        }
        layout.addView(label("NAMA RUTE"))
        layout.addView(nameField)
        layout.addView(label("PAKET SUMBER (PISAH KOMA)"))
        layout.addView(pkgField)
        val pickBtn = Button(this).apply {
            text = "Pilih dari Aplikasi Terpasang"
            setTextColor(TEAL)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), TEAL)
            }
        }
        pickBtn.setOnClickListener { showAppPicker(pkgField) }
        layout.addView(pickBtn)
        layout.addView(label("URL ENDPOINT"))
        layout.addView(urlField)
        layout.addView(label("SECRET ENDPOINT"))
        layout.addView(secretField)
        layout.addView(label("KATA KUNCI (PISAH KOMA)"))
        layout.addView(kwField)
        layout.addView(enabledBox)
        layout.addView(label("PERSETUJUAN PENANGKAPAN"))
        layout.addView(consentStatus)
        layout.addView(consentBox)
        layout.addView(disclosureText)
        layout.addView(rawBox)
        layout.addView(rawDisclosure)

        val saveBtn = Button(this).apply {
            text = "Simpan Rute"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(TEAL)
                cornerRadius = dp(10).toFloat()
            }
        }
        fun saveRoute(url: String) {
            if (!consentLoaded) {
                Toast.makeText(this, "Tunggu status persetujuan termuat.", Toast.LENGTH_SHORT).show()
                return
            }
            // Baca state UI di thread utama sebelum pekerjaan latar.
            val wantConsent = consentBox.isChecked
            val wantRaw = rawBox.isChecked
            val typedSecret = secretField.text.toString().trim()
            val route = Route(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = nameField.text.toString().trim().ifEmpty { "Rute" },
                packages = pkgField.text.toString().split(",")
                    .map { it.trim() }.filter { it.isNotEmpty() },
                url = url,
                keywords = kwField.text.toString().split(",")
                    .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    .ifEmpty { Route.DEFAULT_KEYWORDS },
                enabled = enabledBox.isChecked
            )
            // Stage C: the secret lives in the Keystore vault, never in
            // the route record. An empty field keeps the stored secret.
            if (typedSecret.isNotEmpty()) {
                if (!SecretVault(this).putSecret(route.id, typedSecret)) {
                    Toast.makeText(this, "Secret gagal disimpan; rute tidak disimpan.", Toast.LENGTH_LONG).show()
                    return
                }
            }
            store.upsertRoute(route)
            store.logLocal("rute disimpan: ${route.name}")
            val prior = existing
            Thread {
                runBlocking {
                    val now = System.currentTimeMillis()
                    // Persetujuan: tulis hanya bila berubah (grant/
                    // revoke menaikkan epoch; kenaikan yang tidak
                    // perlu memblokir pengiriman berjalan).
                    if (wantConsent && consentRecord?.granted != true) {
                        app.consentStore.grant(route.id)
                    } else if (!wantConsent && consentRecord?.granted == true) {
                        app.routeLifecycle.revokeRoute(route.id)
                    }
                    // Opt-in P6: tulis hanya bila berubah; mencentang
                    // saat sudah kedaluwarsa memperbarui 90 hari.
                    val rawValidNow = app.consentStore.isRawOptInValid(route.id, now)
                    val rawRecord = app.consentStore.get(route.id)
                    if (wantRaw && !rawValidNow) {
                        app.consentStore.setRawOptIn(
                            route.id, true, now + RAW_OPT_IN_VALIDITY_MS
                        )
                    } else if (!wantRaw && rawRecord?.rawOptIn == true) {
                        app.consentStore.setRawOptIn(route.id, false, null)
                    }
                    // Efek perubahan rute (F21): URL berubah →
                    // pengiriman tertunda diblokir sampai disetujui
                    // ulang; dinonaktifkan → diblokir; diaktifkan
                    // kembali TIDAK menghidupkan otomatis.
                    if (prior != null) {
                        if (prior.url != route.url) {
                            app.routeLifecycle.urlChanged(route.id)
                        }
                        if (prior.enabled && !route.enabled) {
                            app.routeLifecycle.sweepRoute(
                                route.id,
                                com.netter.paylist.collector.RouteLifecycle.ERROR_ROUTE_DISABLED,
                                "rute dinonaktifkan",
                            )
                        }
                    }
                }
                runOnUiThread { finish() }
            }.start()
        }
        saveBtn.setOnClickListener {
            val url = urlField.text.toString().trim()
            when (val validation = EndpointValidator.validate(url)) {
                is EndpointValidation.Invalid -> {
                    Toast.makeText(this, "URL tidak valid: ${validation.reason}", Toast.LENGTH_LONG).show()
                }
                is EndpointValidation.LegacyHttp -> {
                    AlertDialog.Builder(this)
                        .setTitle("URL masih HTTP")
                        .setMessage("Rute ini memakai HTTP tanpa enkripsi. Secret akan dikirim tanpa TLS. Simpan sebagai rute legacy?")
                        .setPositiveButton("Simpan") { _, _ -> saveRoute(url) }
                        .setNegativeButton("Batal", null)
                        .show()
                }
                is EndpointValidation.Valid -> saveRoute(url)
            }
        }
        layout.addView(saveBtn)

        if (existing != null) {
            val reactivateBtn = Button(this).apply {
                text = "Aktifkan Ulang Pengiriman Tertunda"
                setTextColor(TEAL)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), TEAL)
                }
            }
            reactivateBtn.setOnClickListener {
                Thread {
                    val result = runBlocking {
                        app.routeLifecycle.reactivateRoute(existing.id)
                    }
                    runOnUiThread {
                        val msg = if (result.refusedReason == null) {
                            "Pengiriman diaktifkan ulang: ${result.reactivatedDeliveries} pengiriman, " +
                                "${result.releasedQueueItems} antrean legacy."
                        } else {
                            "Tidak dapat diaktifkan ulang: ${result.refusedReason}"
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            layout.addView(reactivateBtn)
            val delBtn = Button(this).apply {
                text = "Hapus Rute Ini"
                setTextColor(RED)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), RED)
                }
            }
            delBtn.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Hapus rute?")
                    .setMessage("Rute \"${existing.name}\" dihapus. Pengiriman " +
                        "tertunda ke rute ini diblokir dan tidak hidup lagi " +
                        "bila rute dibuat ulang. Secret ikut dihapus.")
                    .setPositiveButton("Hapus") { _, _ ->
                        Thread {
                            runBlocking {
                                app.routeLifecycle.deleteRoute(existing.id)
                            }
                            SecretVault(this).deleteSecret(existing.id)
                            store.logLocal("rute dihapus: ${existing.name}")
                            runOnUiThread { finish() }
                        }.start()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            layout.addView(delBtn)
        }
        val cancelBtn = Button(this).apply {
            text = "Batal"
            setTextColor(DARK)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.rgb(214, 224, 221))
            }
        }
        cancelBtn.setOnClickListener { finish() }
        layout.addView(cancelBtn)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(layout)
        })
    }

    /**
     * Pemilih paket sumber (v2.3): daftar SEMUA aplikasi terpasang di HP
     * (label aplikasi + nama paket) dengan kotak cari. Memilih satu aplikasi
     * menambahkan nama paketnya ke field paket sumber (yang tetap bisa
     * diketik manual dan bisa berisi beberapa paket pisah koma).
     */
    private fun showAppPicker(pkgField: EditText) {
        Toast.makeText(this, "Memuat daftar aplikasi...", Toast.LENGTH_SHORT).show()
        Thread {
            val apps = try {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .map { ai ->
                        val lbl = try {
                            ai.loadLabel(packageManager)?.toString()
                        } catch (e: Exception) {
                            null
                        }
                        (lbl?.takeIf { it.isNotEmpty() } ?: ai.packageName) to ai.packageName
                    }
                    .distinctBy { it.second }
                    .sortedBy { it.first.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (apps.isEmpty()) {
                    Toast.makeText(
                        this,
                        "Daftar aplikasi tidak terbaca; ketik nama paket manual.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                var filtered = apps
                val search = EditText(this).apply {
                    hint = "Cari nama aplikasi / paket..."
                    textSize = 15f
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
                val list = ListView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(400)
                    )
                }
                val adapter = object : BaseAdapter() {
                    override fun getCount() = filtered.size
                    override fun getItem(pos: Int) = filtered[pos]
                    override fun getItemId(pos: Int) = pos.toLong()
                    override fun getView(pos: Int, convert: View?, parent: ViewGroup?): View {
                        val v = LinearLayout(this@RouteEditActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                        }
                        v.addView(TextView(this@RouteEditActivity).apply {
                            text = filtered[pos].first
                            textSize = 15f
                            setTextColor(DARK)
                        })
                        v.addView(TextView(this@RouteEditActivity).apply {
                            text = filtered[pos].second
                            textSize = 12f
                            setTextColor(GRAY)
                        })
                        return v
                    }
                }
                list.adapter = adapter
                search.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        val q = s?.toString()?.trim()?.lowercase().orEmpty()
                        filtered = if (q.isEmpty()) apps else apps.filter {
                            it.first.lowercase().contains(q) ||
                                it.second.lowercase().contains(q)
                        }
                        adapter.notifyDataSetChanged()
                    }

                    override fun beforeTextChanged(
                        s: CharSequence?, a: Int, b: Int, c: Int
                    ) {
                    }

                    override fun onTextChanged(
                        s: CharSequence?, a: Int, b: Int, c: Int
                    ) {
                    }
                })
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(search)
                    addView(list)
                }
                val dialog = AlertDialog.Builder(this)
                    .setTitle("Pilih paket sumber (${apps.size} aplikasi)")
                    .setView(box)
                    .setNegativeButton("Tutup", null)
                    .create()
                list.onItemClickListener =
                    AdapterView.OnItemClickListener { _, _, pos, _ ->
                        val pkg = filtered[pos].second
                        val current = pkgField.text.toString().split(",")
                            .map { it.trim() }.filter { it.isNotEmpty() }
                        if (pkg !in current) {
                            pkgField.setText((current + pkg).joinToString(","))
                        }
                        Toast.makeText(
                            this, "Paket ditambahkan: $pkg", Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    }
                dialog.show()
            }
        }.start()
    }
}
