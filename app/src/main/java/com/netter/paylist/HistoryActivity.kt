package com.netter.paylist

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.entity.CaptureOutcome
import com.netter.paylist.history.HistoryDetail
import com.netter.paylist.history.HistoryFilter
import com.netter.paylist.history.HistoryItem
import com.netter.paylist.history.HistoryRepository
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Dedicated history screen (Stage D D4, LH-D3 Option A; addendum
 * 06, LH-01–LH-03, LH-07(e)): every observed notification with its
 * outcome, most recent first, no entry cap — paged 50 at a time.
 * Filters (date preset, amount range, outcome, package) and a
 * structured-field search (package substring or exact amount).
 *
 * Privacy (LH-03, LH-07): the detail view shows timestamps, amount,
 * package, delivery state, match/apply outcomes, hop outcomes and
 * hold reasons — never internal identifiers, and this screen has no
 * export capability (LH-07(c), F55; enforced by the LH-T08 static
 * check). Per-record and per-category local deletion only.
 */
class HistoryActivity : Activity() {

    companion object {
        private val TEAL = Color.rgb(0, 131, 116)      // brand #008374
        private val DARK = Color.rgb(23, 42, 40)       // brand #172a28
        private val GRAY = Color.rgb(91, 107, 104)
        private val BG = Color.rgb(244, 246, 245)
        private val LINE = Color.rgb(214, 224, 221)
        private val RED = Color.rgb(185, 28, 28)

        /** Intent extra: pre-set the server match-status filter (C1 deep-link). */
        const val EXTRA_MATCH_STATUS: String = "match_status"

        private val OUTCOMES = listOf(
            null to "Semua hasil",
            CaptureOutcome.CAPTURED to "Terekam",
            CaptureOutcome.NO_KEYWORD to "Tanpa kata kunci",
            CaptureOutcome.NO_AMOUNT to "Tanpa nominal",
            CaptureOutcome.DUPLICATE to "Duplikat",
            CaptureOutcome.COLLISION to "Bentrok identitas",
            CaptureOutcome.PURE_AUTH to "Autentikasi saja",
            CaptureOutcome.UNLISTED_PACKAGE to "Paket tak terdaftar",
        )

        private val DATE_PRESETS = listOf(
            "Semua tanggal", "Hari ini", "7 hari terakhir", "30 hari terakhir",
        )

        private val MATCH_STATUSES = listOf(
            null to "Semua status server",
            "matched" to "matched (cocok)",
            "logged" to "logged (tercatat, belum cocok)",
            "ambiguous" to "ambiguous (ambigu)",
        )

        private fun outcomeLabel(outcome: String): String =
            OUTCOMES.firstOrNull { it.first == outcome }?.second ?: outcome

        private fun stateLabel(state: DeliveryState?): String = when (state) {
            null -> "—"
            DeliveryState.PENDING -> "Menunggu kirim"
            DeliveryState.IN_FLIGHT -> "Sedang dikirim"
            DeliveryState.RETRY_WAIT -> "Menunggu ulang"
            DeliveryState.ACKED -> "Terkirim"
            DeliveryState.EXPIRED -> "Kedaluarsa"
            DeliveryState.NEEDS_REVIEW -> "Perlu review"
            DeliveryState.RAW_UNAVAILABLE -> "Teks hilang"
            DeliveryState.DISCARDED -> "Dibuang"
            DeliveryState.BLOCKED -> "Diblokir"
            DeliveryState.HELD_AWAITING_LEGACY_SWEEP -> "Ditahan (sapuan legacy)"
            DeliveryState.HELD_AWAITING_RECONCILIATION -> "Ditahan (rekonsiliasi)"
        }

        private fun packageLabel(pkg: String): String = when (pkg) {
            "com.gojek.gopaymerchant" -> "GoPay Merchant"
            "com.gojek.gopay" -> "GoPay"
            else -> pkg
        }
    }

    private val timeFormat = SimpleDateFormat("d MMM yyyy HH:mm", Locale("id", "ID"))

    private var queryText: String = ""
    private var outcomeFilter: String? = null
    private var packageFilter: String? = null
    private var matchStatusFilter: String? = null
    private var datePreset: Int = 0
    private var minAmount: Long? = null
    private var maxAmount: Long? = null

    private var loadedItems: MutableList<HistoryItem> = mutableListOf()
    private var totalCount: Int = 0
    private var packages: List<String> = emptyList()
    private var listContainer: LinearLayout? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun formatRp(amountMinor: Long): String {
        val s = amountMinor.toString()
        val out = StringBuilder()
        var count = 0
        for (i in s.length - 1 downTo 0) {
            out.append(s[i])
            count++
            if (count == 3 && i > 0) {
                out.append('.')
                count = 0
            }
        }
        return "Rp" + out.reverse().toString()
    }

    private fun formatTime(ms: Long): String = timeFormat.format(Date(ms))

    private fun repository(): HistoryRepository =
        (application as PaylistApplication).historyRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // C1 deep-link: the ambiguous-case notification opens the
        // history pre-filtered to ambiguous cases.
        intent.getStringExtra(EXTRA_MATCH_STATUS)?.let {
            if (MATCH_STATUSES.any { status -> status.first == it }) {
                matchStatusFilter = it
            }
        }
        showList()
    }

    private fun currentFilter(): HistoryFilter {
        val fromMs = when (datePreset) {
            1 -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            2 -> System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            3 -> System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            else -> null
        }
        return HistoryFilter(
            fromMs = fromMs,
            minAmount = minAmount,
            maxAmount = maxAmount,
            packageName = packageFilter,
            outcome = outcomeFilter,
            matchStatus = matchStatusFilter,
            query = queryText.takeIf { it.isNotBlank() },
        )
    }

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
            LinearLayout.LayoutParams.WRAP_CONTENT,
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
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
    }

    private fun ghostButton(text: String): Button = Button(this).apply {
        this.text = text
        setTextColor(RED)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), RED)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
    }

    private fun small(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(GRAY)
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(DARK)
    }

    private fun spinner(items: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(
                this@HistoryActivity,
                android.R.layout.simple_spinner_item,
                items,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selected)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    onSelect(position)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }

    // --- List view ---

    private fun showList() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(18), dp(16), dp(20))
        }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(TextView(this).apply {
            text = "Riwayat"
            textSize = 26f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(small("Memuat…"))
        // The package filter needs the distinct package list before
        // the controls are built.
        Thread {
            val pkgs = try {
                runBlocking { repository().distinctPackages() }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                packages = pkgs
                buildListUi(root)
            }
        }.start()
    }

    private fun buildListUi(root: LinearLayout) {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = "Riwayat"
            textSize = 26f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(small("Semua notifikasi yang diamati, terbaru dulu. Ketuk satu baris untuk detail."))

        // --- C1 badge: unacknowledged ambiguous cases ---
        val badgeView = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
            visibility = View.GONE
        }
        root.addView(badgeView)
        Thread {
            val count = try {
                runBlocking { repository().ambiguousCount() }
            } catch (e: Exception) {
                0
            }
            runOnUiThread {
                if (count > 0) {
                    badgeView.text = "$count kasus ambigu belum ditindaklanjuti — ketuk untuk melihat."
                    badgeView.setTextColor(RED)
                    badgeView.visibility = View.VISIBLE
                    badgeView.isClickable = true
                    badgeView.setOnClickListener {
                        matchStatusFilter = "ambiguous"
                        loadedItems.clear()
                        showList()
                    }
                }
            }
        }.start()

        // --- Filter controls ---
        val filterCard = cardLayout()
        filterCard.addView(TextView(this).apply {
            text = "FILTER & PENCARIAN"
            textSize = 12f
            setTextColor(TEAL)
            setTypeface(typeface, Typeface.BOLD)
        })
        val searchBox = EditText(this).apply {
            hint = "Cari paket / nominal (mis. 170286)"
            setText(queryText)
            textSize = 15f
        }
        filterCard.addView(searchBox)
        filterCard.addView(small("Hasil"))
        filterCard.addView(
            spinner(OUTCOMES.map { it.second }, OUTCOMES.indexOfFirst { it.first == outcomeFilter }.coerceAtLeast(0)) {
                outcomeFilter = OUTCOMES[it].first
            },
        )
        filterCard.addView(small("Status server (match)"))
        filterCard.addView(
            spinner(MATCH_STATUSES.map { it.second }, MATCH_STATUSES.indexOfFirst { it.first == matchStatusFilter }.coerceAtLeast(0)) {
                matchStatusFilter = MATCH_STATUSES[it].first
            },
        )
        filterCard.addView(small("Paket"))
        val packageItems = listOf("Semua paket") + packages.map { packageLabel(it) }
        filterCard.addView(
            spinner(
                packageItems,
                if (packageFilter == null) 0 else packages.indexOf(packageFilter) + 1,
            ) {
                packageFilter = if (it == 0) null else packages[it - 1]
            },
        )
        filterCard.addView(small("Tanggal"))
        filterCard.addView(spinner(DATE_PRESETS, datePreset) { datePreset = it })
        val minBox = EditText(this).apply {
            hint = "Nominal min (Rp)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(minAmount?.toString() ?: "")
            textSize = 15f
        }
        filterCard.addView(minBox)
        val maxBox = EditText(this).apply {
            hint = "Nominal maks (Rp)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(maxAmount?.toString() ?: "")
            textSize = 15f
        }
        filterCard.addView(maxBox)
        val applyBtn = primaryButton("Terapkan Filter")
        applyBtn.setOnClickListener {
            queryText = searchBox.text.toString().trim()
            minAmount = minBox.text.toString().filter { it.isDigit() }.toLongOrNull()
            maxAmount = maxBox.text.toString().filter { it.isDigit() }.toLongOrNull()
            loadedItems.clear()
            listContainer?.let { loadPage(it, append = false) }
        }
        filterCard.addView(applyBtn)
        root.addView(filterCard)

        // --- Per-category deletion (LH-07(e): unlinked rows only) ---
        val manageCard = cardLayout()
        manageCard.addView(TextView(this).apply {
            text = "KELOLA (HANYA BARIS TANPA PERISTIWA)"
            textSize = 12f
            setTextColor(TEAL)
            setTypeface(typeface, Typeface.BOLD)
        })
        manageCard.addView(small("Menghapus baris riwayat tanpa peristiwa untuk satu kategori hasil. Peristiwa pembayaran tidak pernah terhapus dari sini."))
        val deletable = OUTCOMES.filter { it.first != null && it.first != CaptureOutcome.CAPTURED }
        var deleteOutcome = deletable[0].first!!
        manageCard.addView(spinner(deletable.map { it.second }, 0) { deleteOutcome = deletable[it].first!! })
        val deleteCatBtn = ghostButton("Hapus Semua Baris Kategori Ini")
        deleteCatBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(
                    "Hapus semua baris \"${outcomeLabel(deleteOutcome)}\" tanpa peristiwa? " +
                        "Baris yang tertaut ke peristiwa tidak ikut terhapus.",
                )
                .setPositiveButton("Hapus") { _, _ ->
                    Thread {
                        val deleted = try {
                            runBlocking { repository().deleteUnlinkedByOutcome(deleteOutcome) }
                        } catch (e: Exception) {
                            -1
                        }
                        runOnUiThread {
                            Toast.makeText(
                                this@HistoryActivity,
                                if (deleted >= 0) "$deleted baris dihapus." else "Gagal menghapus.",
                                Toast.LENGTH_LONG,
                            ).show()
                            loadedItems.clear()
                            listContainer?.let { loadPage(it, append = false) }
                        }
                    }.start()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        manageCard.addView(deleteCatBtn)
        root.addView(manageCard)

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        this.listContainer = listContainer
        root.addView(listContainer)
        loadPage(listContainer, append = false)
    }

    private fun loadPage(container: LinearLayout, append: Boolean) {
        Thread {
            val repo = repository()
            val filter = currentFilter()
            val offset = if (append) loadedItems.size else 0
            val (items, total) = try {
                runBlocking {
                    Pair(
                        repo.listPage(filter, HistoryRepository.PAGE_SIZE, offset),
                        repo.count(filter),
                    )
                }
            } catch (e: Exception) {
                Pair(emptyList(), 0)
            }
            runOnUiThread {
                totalCount = total
                if (!append) loadedItems.clear()
                loadedItems.addAll(items)
                renderList(container)
            }
        }.start()
    }

    private fun renderList(container: LinearLayout) {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = if (totalCount == 0) {
                "Belum ada riwayat untuk filter ini."
            } else {
                "Menampilkan ${loadedItems.size} dari $totalCount baris."
            }
            textSize = 14f
            setTextColor(if (totalCount == 0) GRAY else DARK)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(12))
        })
        for (item in loadedItems) {
            val card = cardLayout()
            card.addView(TextView(this).apply {
                text = item.amountMinor?.let { formatRp(it) } ?: outcomeLabel(item.outcome)
                textSize = 16f
                setTextColor(DARK)
                setTypeface(typeface, Typeface.BOLD)
            })
            card.addView(small("${packageLabel(item.sourcePackage)} · ${formatTime(item.postTimeMs)}"))
            card.addView(small("Hasil: ${outcomeLabel(item.outcome)}"))
            item.titleSnippet?.let { card.addView(small(it)) }
            if (item.hasEvent) {
                card.addView(small("Pengiriman: ${stateLabel(item.deliveryState)}"))
                val business = listOfNotNull(
                    item.lastMatchStatus?.let { "match: $it" },
                    item.lastApplyStatus?.let { "apply: $it" },
                ).joinToString(" · ")
                if (business.isNotEmpty()) card.addView(small(business))
                if (item.lastMatchStatus == "ambiguous") {
                    card.addView(TextView(this).apply {
                        text = if (item.ambiguousReviewed) {
                            "Ambigu — sudah ditindaklanjuti"
                        } else {
                            "Ambigu — BELUM ditindaklanjuti"
                        }
                        textSize = 13f
                        setTextColor(if (item.ambiguousReviewed) GRAY else RED)
                        setTypeface(typeface, Typeface.BOLD)
                    })
                }
            }
            card.isClickable = true
            card.setOnClickListener { showDetail(item.captureId) }
            container.addView(card)
        }
        if (loadedItems.size < totalCount) {
            val moreBtn = primaryButton("Muat 50 Lagi")
            moreBtn.setOnClickListener {
                loadPage(container, append = true)
            }
            container.addView(moreBtn)
        }
    }

    // --- Detail view ---

    private fun showDetail(captureId: String) {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(18), dp(16), dp(20))
        }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(TextView(this).apply {
            text = "Detail"
            textSize = 26f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(small("Memuat…"))
        Thread {
            val detail = try {
                runBlocking { repository().getDetail(captureId) }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (detail == null) {
                    Toast.makeText(this@HistoryActivity, "Baris tidak ditemukan.", Toast.LENGTH_SHORT).show()
                    showList()
                } else {
                    renderDetail(root, detail)
                }
            }
        }.start()
    }

    private fun renderDetail(root: LinearLayout, detail: HistoryDetail) {
        root.removeAllViews()
        val item = detail.item
        root.addView(TextView(this).apply {
            text = "Detail"
            textSize = 26f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
        })
        val card = cardLayout()
        card.addView(TextView(this).apply {
            text = item.amountMinor?.let { formatRp(it) } ?: outcomeLabel(item.outcome)
            textSize = 20f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(label("Paket: ${packageLabel(item.sourcePackage)}"))
        card.addView(label("Hasil: ${outcomeLabel(item.outcome)}"))
        card.addView(label("Waktu notifikasi: ${formatTime(detail.occurredAtMs)}"))
        card.addView(label("Waktu diamati: ${formatTime(detail.capturedAtMs)}"))
        item.titleSnippet?.let { card.addView(label("Judul: $it")) }
        detail.currency?.let { card.addView(label("Mata uang: $it")) }
        detail.category?.let { card.addView(label("Kategori: $it")) }
        if (detail.identityCollision) {
            card.addView(TextView(this).apply {
                text = "Perhatian: bentrok identitas teramati untuk peristiwa ini (dedupe sama, isi beda — peristiwa pertama dipertahankan)."
                textSize = 13f
                setTextColor(RED)
                setPadding(0, dp(6), 0, 0)
            })
        }
        detail.holdReason?.let {
            card.addView(TextView(this).apply {
                text = it
                textSize = 13f
                setTextColor(RED)
                setPadding(0, dp(6), 0, 0)
            })
        }
        root.addView(card)

        for ((index, delivery) in detail.deliveries.withIndex()) {
            val dCard = cardLayout()
            dCard.addView(TextView(this).apply {
                text = "Pengiriman ${index + 1}: ${stateLabel(delivery.state)}"
                textSize = 16f
                setTextColor(DARK)
                setTypeface(typeface, Typeface.BOLD)
            })
            delivery.lastMatchStatus?.let { dCard.addView(label("match: $it")) }
            delivery.lastApplyStatus?.let { dCard.addView(label("apply: $it")) }
            delivery.lastErrorCode?.let { dCard.addView(label("Kode galat: $it")) }
            delivery.lastErrorSummary?.let { dCard.addView(small(it)) }
            delivery.terminalAtMs?.let { dCard.addView(small("Selesai: ${formatTime(it)}")) }
            if (delivery.hopOutcomes.isNotEmpty()) {
                dCard.addView(small("Jejak hop:"))
                for (hop in delivery.hopOutcomes) {
                    dCard.addView(
                        small(
                            "• ${hop.routeId}: ${hop.outcome}" +
                                (hop.httpCode?.let { " (HTTP $it)" } ?: "") +
                                (hop.matchStatus?.let { " match=$it" } ?: "") +
                                (hop.apply?.let { " apply=$it" } ?: "") +
                                " · ${formatTime(hop.attemptedAtMs)}",
                        ),
                    )
                }
            }
            root.addView(dCard)
        }
        if (detail.attempts.isNotEmpty()) {
            val aCard = cardLayout()
            aCard.addView(TextView(this).apply {
                text = "Percobaan (${detail.attempts.size})"
                textSize = 16f
                setTextColor(DARK)
                setTypeface(typeface, Typeface.BOLD)
            })
            for (attempt in detail.attempts) {
                aCard.addView(
                    small(
                        "#${attempt.attemptNo} ${attempt.routeId ?: "-"} · " +
                            "${formatTime(attempt.startedAtMs)} · " +
                            (attempt.httpStatus?.let { "HTTP $it · " } ?: "") +
                            (attempt.resultClass ?: "-") +
                            (attempt.errorCode?.let { " · $it" } ?: ""),
                    ),
                )
            }
            root.addView(aCard)
        }

        // --- C1: mark this ambiguous case handled ---
        if (item.lastMatchStatus == "ambiguous" && !detail.ambiguousReviewed) {
            val markBtn = primaryButton("Tandai Sudah Ditindaklanjuti")
            markBtn.setOnClickListener {
                Thread {
                    val ok = try {
                        runBlocking {
                            repository().markAmbiguousReviewed(
                                item.captureId,
                                Store(this@HistoryActivity).deviceLabel(),
                            )
                        }
                    } catch (e: Exception) {
                        false
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this@HistoryActivity,
                            if (ok) "Ditandai sudah ditindaklanjuti." else "Gagal menandai.",
                            Toast.LENGTH_SHORT,
                        ).show()
                        showList()
                    }
                }.start()
            }
            root.addView(markBtn)
        } else if (item.lastMatchStatus == "ambiguous") {
            root.addView(TextView(this).apply {
                text = "Kasus ambigu ini sudah ditandai ditindaklanjuti."
                textSize = 13f
                setTextColor(GRAY)
                setPadding(0, dp(6), 0, 0)
            })
        }

        // --- Local deletion (LH-07(e)) ---
        val deleteRowBtn = ghostButton("Hapus Baris Riwayat Ini")
        deleteRowBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Hapus baris riwayat ini dari perangkat? Peristiwa (bila ada) tidak ikut terhapus.")
                .setPositiveButton("Hapus") { _, _ ->
                    Thread {
                        val ok = try {
                            runBlocking { repository().deleteCaptureRow(item.captureId) }
                        } catch (e: Exception) {
                            false
                        }
                        runOnUiThread {
                            Toast.makeText(
                                this@HistoryActivity,
                                if (ok) "Baris dihapus." else "Gagal menghapus.",
                                Toast.LENGTH_SHORT,
                            ).show()
                            showList()
                        }
                    }.start()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        root.addView(deleteRowBtn)
        if (item.hasEvent) {
            val deleteEventBtn = ghostButton("Hapus Peristiwa Ini (termasuk pengiriman)")
            deleteEventBtn.setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage(
                        "Hapus peristiwa ini beserta pengiriman dan percobaannya dari perangkat? " +
                            "Baris riwayat tetap tersimpan tanpa tautan. " +
                            "Tindakan ini tidak dapat dibatalkan dan tidak menyentuh salinan server.",
                    )
                    .setPositiveButton("Hapus Peristiwa") { _, _ ->
                        Thread {
                            val ok = try {
                                runBlocking { repository().deleteEvent(item.captureId) }
                            } catch (e: Exception) {
                                false
                            }
                            runOnUiThread {
                                Toast.makeText(
                                    this@HistoryActivity,
                                    if (ok) "Peristiwa dihapus." else "Gagal menghapus.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                showList()
                            }
                        }.start()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            root.addView(deleteEventBtn)
        }
        val backBtn = primaryButton("Kembali ke Daftar")
        backBtn.setOnClickListener { showList() }
        root.addView(backBtn)
    }
}
