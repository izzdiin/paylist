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
import java.util.UUID

/**
 * Form tambah/edit satu rute. Secret tidak pernah ditampilkan kembali:
 * field selalu dibuka kosong; menyimpan dengan secret kosong berarti
 * secret lama dipertahankan (sama seperti perilaku app sebelumnya).
 * Tampilan mengikuti layar utama: terang, kartu, aksen teal brand.
 */
class RouteEditActivity : Activity() {
    companion object {
        private val TEAL = Color.rgb(0, 131, 116)
        private val DARK = Color.rgb(23, 42, 40)
        private val GRAY = Color.rgb(91, 107, 104)
        private val BG = Color.rgb(244, 246, 245)
        private val RED = Color.rgb(185, 28, 28)
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

        val saveBtn = Button(this).apply {
            text = "Simpan Rute"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(TEAL)
                cornerRadius = dp(10).toFloat()
            }
        }
        saveBtn.setOnClickListener {
            val typedSecret = secretField.text.toString().trim()
            val route = Route(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = nameField.text.toString().trim().ifEmpty { "Rute" },
                packages = pkgField.text.toString().split(",")
                    .map { it.trim() }.filter { it.isNotEmpty() },
                url = urlField.text.toString().trim(),
                secret = typedSecret.ifEmpty { existing?.secret ?: "" },
                keywords = kwField.text.toString().split(",")
                    .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    .ifEmpty { Route.DEFAULT_KEYWORDS },
                enabled = enabledBox.isChecked
            )
            store.upsertRoute(route)
            store.logLocal("rute disimpan: ${route.name}")
            finish()
        }
        layout.addView(saveBtn)

        if (existing != null) {
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
                store.deleteRoute(existing.id)
                store.logLocal("rute dihapus: ${existing.name}")
                finish()
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
