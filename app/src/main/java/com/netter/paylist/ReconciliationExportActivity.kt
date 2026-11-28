package com.netter.paylist

import android.app.Activity
import android.app.KeyguardManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.netter.paylist.data.entity.ExportAuth
import com.netter.paylist.export.ExportFilter
import com.netter.paylist.export.ExportRequest
import com.netter.paylist.export.ExportResult
import com.netter.paylist.export.ReconciliationExport
import com.netter.paylist.export.SourceAccountEntry
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The reconciliation export screen (§7.7A, Q1 REQUIRED) — the ONLY
 * export path in the app (F55). Order of operations: filters →
 * preflight count → confirmation dialog → SAF picker → device
 * auth (identifiers only) → snapshot → streaming write.
 *
 * Identifiers (`bank_reference`, `order_number`, `source_account`)
 * are opt-in per export, default OFF, never persisted, and gated
 * on device credentials; without a secure lock the option is
 * disabled with an explanation. Cancellations before the run
 * starts (picker / device auth) write no audit row — nothing was
 * exported; cancellations during the run are audited CANCELLED.
 */
class ReconciliationExportActivity : Activity() {

    companion object {
        private val TEAL = Color.rgb(0, 131, 116)
        private val DARK = Color.rgb(23, 42, 40)
        private val GRAY = Color.rgb(91, 107, 104)
        private val BG = Color.rgb(244, 246, 245)
        private val LINE = Color.rgb(214, 224, 221)
        private val RED = Color.rgb(185, 28, 28)
        private val AMBER_BG = Color.rgb(255, 243, 205)

        private const val REQUEST_CREATE_DOCUMENT = 2001
        private const val REQUEST_DEVICE_AUTH = 2002

        private val DATE_PRESETS = listOf("Semua tanggal", "7 hari terakhir", "30 hari terakhir", "90 hari terakhir")
        private val DIRECTIONS = listOf("Semua arah", "IN", "OUT", "UNKNOWN")
        private val ADVISORY = listOf(
            "Semua status", "matched", "ambiguous", "manual_review",
            "unmatched", "duplicate_evidence",
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var dateSpinner: Spinner
    private lateinit var directionSpinner: Spinner
    private lateinit var advisorySpinner: Spinner
    private lateinit var accountSpinner: Spinner
    private lateinit var identifiersBox: CheckBox
    private lateinit var preflightText: TextView
    private lateinit var progressText: TextView
    private lateinit var exportBtn: Button
    private lateinit var cancelBtn: Button

    private var accounts: List<SourceAccountEntry> = emptyList()
    private var preflight: com.netter.paylist.export.FrozenExport? = null
    private var pendingUri: Uri? = null
    private var pendingAuth: String = ExportAuth.NONE
    private val cancelled = AtomicBoolean(false)

    private fun app(): PaylistApplication = application as PaylistApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(18), dp(16), dp(20))
        }
        layout.addView(TextView(this).apply {
            text = "Ekspor Rekonsiliasi"
            textSize = 22f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })
        layout.addView(TextView(this).apply {
            text = "Satu-satunya jalur ekspor data. File CSV berisi ringkasan " +
                "rekonsiliasi — tidak pernah berisi teks notifikasi mentah, " +
                "kode OTP/PIN, atau pengenal internal."
            textSize = 13f
            setTextColor(GRAY)
            setPadding(0, 0, 0, dp(10))
        })
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(TEAL)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(3))
        }
        fun spinner(items: List<String>) = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ReconciliationExportActivity,
                android.R.layout.simple_spinner_item, items,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        layout.addView(label("RENTANG TANGGAL"))
        dateSpinner = spinner(DATE_PRESETS)
        layout.addView(dateSpinner)
        layout.addView(label("ARAH"))
        directionSpinner = spinner(DIRECTIONS)
        layout.addView(directionSpinner)
        layout.addView(label("STATUS ADVISORY"))
        advisorySpinner = spinner(ADVISORY)
        layout.addView(advisorySpinner)
        layout.addView(label("AKUN SUMBER"))
        accountSpinner = spinner(listOf("Memuat…"))
        layout.addView(accountSpinner)

        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val deviceSecure = keyguard.isDeviceSecure
        identifiersBox = CheckBox(this).apply {
            text = "Sertakan pengenal (referensi bank, nomor pesanan, akun sumber)"
            isChecked = false
            isEnabled = deviceSecure
            setPadding(0, dp(12), 0, dp(2))
        }
        layout.addView(identifiersBox)
        layout.addView(TextView(this).apply {
            text = if (deviceSecure) {
                "Pengenal memerlukan autentikasi perangkat (PIN/pola/sidik jari) " +
                    "sebelum ekspor dimulai. Pilihan ini tidak disimpan."
            } else {
                "Pengenal tidak tersedia: perangkat ini tidak memakai kunci " +
                    "layar yang aman. Aktifkan PIN/pola/sidik jari untuk " +
                    "mengekspor pengenal."
            }
            textSize = 13f
            setTextColor(GRAY)
            setPadding(dp(4), 0, dp(4), dp(6))
        })

        preflightText = TextView(this).apply {
            text = "Belum ada pratinjau."
            textSize = 13f
            setTextColor(DARK)
            setPadding(0, dp(8), 0, dp(4))
        }
        layout.addView(preflightText)

        val previewBtn = Button(this).apply {
            text = "Pratinjau Jumlah Baris"
            setTextColor(TEAL)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), TEAL)
            }
        }
        previewBtn.setOnClickListener { runPreflight() }
        layout.addView(previewBtn)

        exportBtn = Button(this).apply {
            text = "Ekspor CSV"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(TEAL)
                cornerRadius = dp(10).toFloat()
            }
        }
        exportBtn.setOnClickListener { confirmAndPick() }
        layout.addView(exportBtn)

        progressText = TextView(this).apply {
            textSize = 13f
            setTextColor(GRAY)
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        layout.addView(progressText)
        cancelBtn = Button(this).apply {
            text = "Batalkan Ekspor"
            setTextColor(RED)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), RED)
            }
            visibility = View.GONE
        }
        cancelBtn.setOnClickListener {
            cancelled.set(true)
            Toast.makeText(this, "Membatalkan…", Toast.LENGTH_SHORT).show()
        }
        layout.addView(cancelBtn)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(layout)
        })
        loadAccounts()
    }

    private fun loadAccounts() {
        Thread {
            val entries = try {
                runBlocking { app().reconciliationExport.listSourceAccounts(null, null) }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                accounts = entries
                val items = mutableListOf("Semua akun", "Akun tidak terverifikasi saja")
                items += entries.map { it.label }
                accountSpinner.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_item, items,
                ).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
            }
        }.start()
    }

    private fun currentFilter(): ExportFilter {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L
        val fromMs = when (dateSpinner.selectedItemPosition) {
            1 -> now - 7 * dayMs
            2 -> now - 30 * dayMs
            3 -> now - 90 * dayMs
            else -> null
        }
        val direction = when (directionSpinner.selectedItemPosition) {
            0 -> null
            else -> DIRECTIONS[directionSpinner.selectedItemPosition]
        }
        val advisory = when (advisorySpinner.selectedItemPosition) {
            0 -> null
            else -> ADVISORY[advisorySpinner.selectedItemPosition]
        }
        val accountPos = accountSpinner.selectedItemPosition
        val unattributedOnly = accountPos == 1
        val accountKey = if (accountPos >= 2) accounts.getOrNull(accountPos - 2)?.key else null
        return ExportFilter(
            fromMs = fromMs,
            toMs = null,
            sourceAccountKey = accountKey,
            unattributedOnly = unattributedOnly,
            direction = direction,
            advisoryStatus = advisory,
        )
    }

    private fun runPreflight() {
        val filter = currentFilter()
        val includeIdentifiers = identifiersBox.isChecked
        preflightText.text = "Menghitung baris…"
        Thread {
            val frozen = try {
                runBlocking { app().reconciliationExport.build(filter, includeIdentifiers) }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                preflight = frozen
                preflightText.text = if (frozen == null) {
                    "Gagal menghitung baris."
                } else {
                    buildString {
                        append("Filter: ${filter.describe()}\n")
                        append("Baris: ${frozen.rows.size} · Dikecualikan: ${frozen.excludedCount}\n")
                        append("Pengenal: ${if (includeIdentifiers) "disertakan" else "tidak disertakan"}")
                        if (frozen.hasPartialChain) {
                            append("\n${ReconciliationExport.PARTIAL_CHAIN_WARNING}")
                        }
                    }
                }
            }
        }.start()
    }

    private fun confirmAndPick() {
        val filter = currentFilter()
        val includeIdentifiers = identifiersBox.isChecked
        val frozen = preflight
        val summary = if (frozen == null) {
            "Pratinjau belum dijalankan — jumlah baris dihitung ulang saat ekspor."
        } else {
            "Baris: ${frozen.rows.size} · Dikecualikan: ${frozen.excludedCount}" +
                if (frozen.hasPartialChain) "\n${ReconciliationExport.PARTIAL_CHAIN_WARNING}" else ""
        }
        AlertDialog.Builder(this)
            .setTitle("Ekspor CSV?")
            .setMessage(
                "Filter: ${filter.describe()}\n" +
                    "Pengenal: ${if (includeIdentifiers) "disertakan (butuh autentikasi perangkat)" else "tidak disertakan"}\n" +
                    summary,
            )
            .setPositiveButton("Pilih Lokasi") { _, _ -> pickDocument() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun pickDocument() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(
                Intent.EXTRA_TITLE,
                ReconciliationExport.suggestedFileName(System.currentTimeMillis()),
            )
        }
        startActivityForResult(intent, REQUEST_CREATE_DOCUMENT)
    }

    @Deprecated("startActivityForResult is the available API without androidx.activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CREATE_DOCUMENT -> {
                if (resultCode != RESULT_OK || data?.data == null) return
                val uri = data.data!!
                if (identifiersBox.isChecked) {
                    pendingUri = uri
                    val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    val authIntent = keyguard.createConfirmDeviceCredentialIntent(
                        "Konfirmasi identitas",
                        "Autentikasi perangkat diperlukan untuk mengekspor pengenal.",
                    )
                    if (authIntent == null) {
                        Toast.makeText(
                            this,
                            "Autentikasi perangkat tidak tersedia — ekspor dibatalkan.",
                            Toast.LENGTH_LONG,
                        ).show()
                        pendingUri = null
                        return
                    }
                    startActivityForResult(authIntent, REQUEST_DEVICE_AUTH)
                } else {
                    pendingAuth = ExportAuth.NONE
                    startExport(uri)
                }
            }
            REQUEST_DEVICE_AUTH -> {
                val uri = pendingUri
                pendingUri = null
                if (resultCode != RESULT_OK || uri == null) {
                    Toast.makeText(this, "Autentikasi dibatalkan — ekspor dibatalkan.", Toast.LENGTH_SHORT).show()
                    return
                }
                pendingAuth = ExportAuth.DEVICE_CREDENTIAL
                startExport(uri)
            }
        }
    }

    private fun startExport(uri: Uri) {
        val filter = currentFilter()
        val includeIdentifiers = identifiersBox.isChecked
        val auth = pendingAuth
        cancelled.set(false)
        exportBtn.isEnabled = false
        progressText.visibility = View.VISIBLE
        progressText.text = "Menulis…"
        cancelBtn.visibility = View.VISIBLE
        Thread {
            val result = try {
                runBlocking {
                    app().exportRunner.run(
                        ExportRequest(
                            filter = filter,
                            includeIdentifiers = includeIdentifiers,
                            auth = auth,
                            actor = Store(this@ReconciliationExportActivity).deviceLabel(),
                            fileName = uri.lastPathSegment ?: "paylist-reconciliation.csv",
                            openStream = {
                                contentResolver.openOutputStream(uri)
                                    ?: throw IllegalStateException("tidak dapat membuka tujuan")
                            },
                            deletePartial = {
                                try {
                                    DocumentsContract.deleteDocument(contentResolver, uri)
                                } catch (e: Exception) {
                                    false
                                }
                            },
                            isCancelled = { cancelled.get() },
                            onRowWritten = { written ->
                                if (written % 10 == 0) {
                                    runOnUiThread { progressText.text = "Menulis… $written baris" }
                                }
                            },
                        ),
                    )
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                exportBtn.isEnabled = true
                cancelBtn.visibility = View.GONE
                progressText.visibility = View.GONE
                showResult(result)
            }
        }.start()
    }

    private fun showResult(result: ExportResult?) {
        val message = when (result) {
            is ExportResult.Completed -> buildString {
                append("Ekspor selesai: ${result.rowsWritten} baris ditulis, " +
                    "${result.excludedCount} dikecualikan.")
                if (result.hasPartialChain) {
                    append("\n${ReconciliationExport.PARTIAL_CHAIN_WARNING}")
                }
            }
            is ExportResult.Failed -> "Ekspor gagal setelah ${result.rowsWritten} baris: " +
                result.reason +
                if (result.cleanupAttempted) {
                    if (result.cleanupSucceeded) " File parsial dihapus."
                    else " File parsial TIDAK dapat dihapus — hapus manual bila perlu."
                } else ""
            is ExportResult.Cancelled -> "Ekspor dibatalkan setelah ${result.rowsWritten} baris ditulis." +
                if (result.cleanupAttempted && !result.cleanupSucceeded) {
                    " File parsial TIDAK dapat dihapus — hapus manual bila perlu."
                } else ""
            null -> "Ekspor gagal sebelum dimulai."
        }
        AlertDialog.Builder(this)
            .setTitle("Hasil Ekspor")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
