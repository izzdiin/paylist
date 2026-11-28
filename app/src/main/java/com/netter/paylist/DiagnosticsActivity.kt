package com.netter.paylist

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.netter.paylist.collector.AmountReview
import com.netter.paylist.collector.AuthorizeOutcome
import com.netter.paylist.collector.DiscardOutcome
import com.netter.paylist.collector.MatchResolveOutcome
import com.netter.paylist.collector.PickOutcome
import com.netter.paylist.collector.QuarantineOutcome
import com.netter.paylist.collector.ReviewItem
import com.netter.paylist.migration.CutoverMode
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal operator review surface (Stage D D3): the NEEDS_REVIEW
 * amount queue (pick/discard), the RAW_UNAVAILABLE queue
 * (authorise-A′/discard), the quarantine queue (approve/discard),
 * and the P3 match queue (read-only in D3). D5 expands this screen
 * (status cards, reconciliation export, diagnostics repository);
 * the MainActivity entry point lands with D5 — until then this
 * activity is launched explicitly.
 *
 * Summaries show amount-if-reliable, source, time, reason only —
 * never OTP/PIN/auth token, never raw spans.
 */
class DiagnosticsActivity : Activity() {

    companion object {
        private val TEAL = Color.rgb(0, 131, 116)      // brand #008374
        private val DARK = Color.rgb(23, 42, 40)       // brand #172a28
        private val GRAY = Color.rgb(91, 107, 104)
        private val BG = Color.rgb(244, 246, 245)
        private val LINE = Color.rgb(214, 224, 221)
        private val RED = Color.rgb(185, 28, 28)
        private val AMBER_BG = Color.rgb(255, 243, 205)
    }

    private val timeFormat = SimpleDateFormat("d MMM yyyy HH:mm", Locale("id", "ID"))

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

    private fun operator(): String = Store(this).deviceLabel()

    private fun review(): AmountReview = (application as PaylistApplication).amountReview

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        buildUi()
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

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(DARK)
    }

    private fun small(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(GRAY)
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(18), dp(16), dp(20))
        }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(TextView(this).apply {
            text = "Review & Diagnostik"
            textSize = 26f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(small("Memuat antrean review…"))
        Thread {
            val app = application as PaylistApplication
            val mode = try {
                runBlocking { app.coordinator.getMode() }
            } catch (e: Exception) {
                null
            }
            val items = try {
                runBlocking { review().listReviewQueue() }
            } catch (e: Exception) {
                emptyList()
            }
            val held = try {
                runBlocking { app.diagnosticsRepository.heldCounts() }
            } catch (e: Exception) {
                null
            }
            val routeNames = Store(this@DiagnosticsActivity).routes()
                .associate { it.id to it.name }
            runOnUiThread { renderList(root, items, mode, held, routeNames) }
        }.start()
    }

    private fun renderList(
        root: LinearLayout,
        items: List<ReviewItem>,
        mode: CutoverMode?,
        held: DiagnosticsRepository.HeldCounts?,
        routeNames: Map<String, String>,
    ) {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = "Review & Diagnostik"
            textSize = 26f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
        })
        // Disclosure (§7.6(d)).
        val disclosure = cardLayout()
        disclosure.background = GradientDrawable().apply {
            setColor(AMBER_BG)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), LINE)
        }
        disclosure.addView(TextView(this).apply {
            text = "Review menahan pengiriman — pembayaran ini tidak dikirim " +
                "sampai Anda memilih jumlah atau membuangnya. Tagihan mungkin " +
                "sudah lunas lewat jalur lain selama menunggu — periksa log " +
                "server sebelum mengonfirmasi."
            textSize = 13f
            setTextColor(DARK)
        })
        if (mode == CutoverMode.LEGACY_ONLY) {
            disclosure.addView(TextView(this).apply {
                text = "Mode saat ini LEGACY_ONLY: pengiriman yang dipilih " +
                    "menunggu cutover ke mode Room."
                textSize = 13f
                setTextColor(DARK)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, 0)
            })
        }
        root.addView(disclosure)
        // D5 status card: HELD counts per state and per route.
        if (held != null) {
            val status = cardLayout()
            status.addView(TextView(this).apply {
                text = "Status pengiriman tertahan"
                textSize = 16f
                setTextColor(DARK)
                setTypeface(typeface, Typeface.BOLD)
            })
            val lines = mutableListOf<String>()
            lines += "Jumlah ambigu (NEEDS_REVIEW): ${held.needsReview}"
            lines += "Teks asli hilang (RAW_UNAVAILABLE): ${held.rawUnavailable}"
            lines += "Menunggu sapuan legacy: ${held.heldAwaitingLegacySweep}"
            lines += "Menunggu rekonsiliasi: ${held.heldAwaitingReconciliation}"
            lines += "Diblokir (persetujuan/konfigurasi): ${held.blocked}"
            lines += "Antrean legacy tertahan: ${held.legacyQueueHeld}"
            lines += "Kasus ambigu belum ditindaklanjuti: ${held.ambiguousUnreviewed}"
            for (line in lines) status.addView(small(line))
            if (held.perRoute.isNotEmpty()) {
                status.addView(TextView(this).apply {
                    text = "Per rute:"
                    textSize = 13f
                    setTextColor(DARK)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(8), 0, dp(2))
                })
                for ((routeId, count) in held.perRoute.entries.sortedByDescending { it.value }) {
                    val name = routeNames[routeId] ?: routeId
                    status.addView(small("$name: $count tertahan"))
                }
            }
            root.addView(status)
        }
        root.addView(TextView(this).apply {
            text = if (items.isEmpty()) {
                "Tidak ada yang perlu direview."
            } else {
                "${items.size} item menunggu keputusan operator."
            }
            textSize = 14f
            setTextColor(if (items.isEmpty()) GRAY else RED)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(12))
        })
        for (item in items) {
            when (item) {
                is ReviewItem.AmountQuestion -> root.addView(amountCard(item))
                is ReviewItem.RawLost -> root.addView(rawLostCard(item))
                is ReviewItem.Quarantined -> root.addView(quarantineCard(item))
                is ReviewItem.MatchQuestion -> root.addView(matchCard(item))
            }
        }
    }

    private fun amountCard(item: ReviewItem.AmountQuestion): LinearLayout {
        val card = cardLayout()
        card.addView(TextView(this).apply {
            text = "Jumlah ambigu — pilih yang benar"
            textSize = 16f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(small("${item.sourcePackage ?: "?"} · ${formatTime(item.occurredAtMs)}"))
        card.addView(label("Token terurai: " + item.tokens.joinToString(", ") { formatRp(it.value) }))
        card.addView(small("Token pertama (terpilih otomatis bila tidak direview): ${formatRp(item.firstTokenAmount)}"))
        for (token in item.tokens) {
            val btn = primaryButton("Pilih ${formatRp(token.value)}")
            btn.setOnClickListener {
                runReviewAction("Memilih ${formatRp(token.value)}…") {
                    when (val r = review().pickAmount(item.deliveryId, token.value, operator())) {
                        is PickOutcome.Released -> "Dipilih ${formatRp(token.value)} — pengiriman dilepas."
                        is PickOutcome.Held -> "Ditahan: ${r.reason}"
                        is PickOutcome.Blocked -> "Diblokir: ${r.reason}"
                        is PickOutcome.Refused -> "Ditolak: ${r.reason}"
                    }
                }
            }
            card.addView(btn)
        }
        val discard = ghostButton("Buang pembayaran ini")
        discard.setOnClickListener {
            confirm("Buang pembayaran ini?") {
                runReviewAction("Membuang…") {
                    when (val r = review().discardAmount(item.deliveryId, operator())) {
                        is DiscardOutcome.Discarded ->
                            if (r.legacyCopyExisted) {
                                "Dibuang. Perhatian: salinan legacy masih ada — mungkin tetap terkirim."
                            } else {
                                "Dibuang. Catatan peristiwa tetap tersimpan."
                            }
                        is DiscardOutcome.Refused -> "Ditolak: ${r.reason}"
                    }
                }
            }
        }
        card.addView(discard)
        return card
    }

    private fun rawLostCard(item: ReviewItem.RawLost): LinearLayout {
        val card = cardLayout()
        card.addView(TextView(this).apply {
            text = "Teks asli hilang — putuskan bentuk kirim"
            textSize = 16f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(small("${item.sourcePackage ?: "?"} · ${formatTime(item.occurredAtMs)}"))
        item.amount?.let { card.addView(label("Jumlah: ${formatRp(it)}")) }
        item.lastErrorCode?.let { card.addView(small("Alasan penahanan: $it")) }
        if (item.hardStopReasons.isEmpty()) {
            card.addView(small("Periksa log server sebelum mengonfirmasi: tidak ada POST sebelumnya untuk pembayaran ini."))
            val auth = primaryButton("Kirim bentuk ringkas (A′)")
            auth.setOnClickListener {
                runReviewAction("Mengirim…") {
                    when (val r = review().authorizeFallback(item.deliveryId, operator())) {
                        is AuthorizeOutcome.Authorized -> "Diizinkan — pengiriman bentuk ringkas dijadwalkan."
                        is AuthorizeOutcome.Refused -> "Ditolak: ${r.reason}"
                    }
                }
            }
            card.addView(auth)
        } else {
            card.addView(TextView(this).apply {
                text = "Tidak dapat diizinkan: " + item.hardStopReasons.joinToString("; ")
                textSize = 13f
                setTextColor(RED)
                setPadding(0, dp(6), 0, 0)
            })
        }
        val discard = ghostButton("Buang (hanya keputusan lokal — bukan bukti belum lunas)")
        discard.setOnClickListener {
            confirm("Buang pembayaran ini? Keputusan ini hanya lokal — bukan bukti pembayaran belum lunas.") {
                runReviewAction("Membuang…") {
                    when (val r = review().discardRawLost(item.deliveryId, operator())) {
                        is DiscardOutcome.Discarded -> "Dibuang. Bukti audit tetap tersimpan."
                        is DiscardOutcome.Refused -> "Ditolak: ${r.reason}"
                    }
                }
            }
        }
        card.addView(discard)
        return card
    }

    private fun quarantineCard(item: ReviewItem.Quarantined): LinearLayout {
        val card = cardLayout()
        val record = item.record
        card.addView(TextView(this).apply {
            text = "Karantina: ${record.reason}"
            textSize = 16f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(small("${record.sourcePackage ?: "?"} · ${formatTime(record.quarantinedAtMs)}"))
        record.amount?.let { card.addView(label("Jumlah: ${formatRp(it)}")) }
        record.spanClasses?.let { card.addView(small("Kelas span: $it")) }
        val approve = primaryButton("Setujui bentuk tersensor")
        approve.setOnClickListener {
            runReviewAction("Menyetujui…") {
                when (val r = review().approveQuarantine(record.dedupeKey, operator())) {
                    is QuarantineOutcome.Approved ->
                        if (r.imported) "Disetujui — bentuk tersensor diimpor sebagai pengiriman." else "Disetujui — tidak ada catatan keuangan untuk diimpor."
                    is QuarantineOutcome.NotFound -> "Record tidak ditemukan."
                    is QuarantineOutcome.AlreadyResolved -> "Sudah diputuskan: ${r.resolution}."
                    is QuarantineOutcome.Refused -> "Ditolak: ${r.reason}"
                    is QuarantineOutcome.Discarded -> "Sudah dibuang."
                }
            }
        }
        card.addView(approve)
        val discard = ghostButton("Buang (hapus teks asli; audit tetap tersimpan)")
        discard.setOnClickListener {
            confirm("Buang item karantina ini? Teks asli dihapus; catatan audit tetap tersimpan.") {
                runReviewAction("Membuang…") {
                    when (val r = review().discardQuarantine(record.dedupeKey, operator())) {
                        is QuarantineOutcome.Discarded -> "Dibuang. Catatan audit tetap tersimpan."
                        is QuarantineOutcome.NotFound -> "Record tidak ditemukan."
                        is QuarantineOutcome.AlreadyResolved -> "Sudah diputuskan: ${r.resolution}."
                        is QuarantineOutcome.Refused -> "Ditolak: ${r.reason}"
                        is QuarantineOutcome.Approved -> "Sudah disetujui."
                    }
                }
            }
        }
        card.addView(discard)
        return card
    }

    private fun matchCard(item: ReviewItem.MatchQuestion): LinearLayout {
        val card = cardLayout()
        val row = item.row
        card.addView(TextView(this).apply {
            text = "Kecocokan perlu review (P3)"
            textSize = 16f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(small("${formatTime(item.sortMs)} · ${row.ruleId}"))
        card.addView(label("Merchant: ${row.merchantId} · Order: ${row.orderNumber ?: "-"}"))
        card.addView(label("Status: ${row.advisoryMatchStatus} · Keyakinan: ${row.confidence}"))
        row.reviewReason?.let { card.addView(small(it)) }
        card.addView(small("Keputusan ini hanya advisory (dicatat lokal, tidak dikirim ke server)."))
        val attribute = primaryButton("Cocok — atributkan ke pesanan ini")
        attribute.setOnClickListener {
            confirm("Atribusikan transaksi ini ke pesanan ${row.orderNumber ?: "-"}?") {
                runReviewAction("Mengatribusikan…") {
                    when (val r = review().resolveMatch(row.matchId, attribute = true, operator())) {
                        is MatchResolveOutcome.Attributed -> "Diatribusikan ke pesanan ini."
                        is MatchResolveOutcome.DuplicateEvidence ->
                            "Pesanan sudah diatribusikan; transaksi ini dicatat sebagai bukti duplikat."
                        is MatchResolveOutcome.StillAmbiguous -> "Masih ambigu — lihat log audit."
                        is MatchResolveOutcome.MarkedUnmatched -> "Ditandai bukan transaksi ini."
                        is MatchResolveOutcome.Refused -> "Ditolak: ${r.reason}"
                    }
                }
            }
        }
        card.addView(attribute)
        val unmatched = ghostButton("Bukan transaksi ini")
        unmatched.setOnClickListener {
            confirm("Tandai sebagai bukan transaksi pesanan ini?") {
                runReviewAction("Menandai…") {
                    when (val r = review().resolveMatch(row.matchId, attribute = false, operator())) {
                        is MatchResolveOutcome.MarkedUnmatched -> "Ditandai bukan transaksi ini."
                        is MatchResolveOutcome.Refused -> "Ditolak: ${r.reason}"
                        else -> "Keputusan tidak terduga: $r"
                    }
                }
            }
        }
        card.addView(unmatched)
        return card
    }

    private fun confirm(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Ya") { _, _ -> onYes() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun runReviewAction(progress: String, action: suspend () -> String) {
        Toast.makeText(this, progress, Toast.LENGTH_SHORT).show()
        Thread {
            val result = try {
                runBlocking { action() }
            } catch (e: Exception) {
                "Gagal: ${e.message?.take(120)}"
            }
            runOnUiThread {
                Toast.makeText(this@DiagnosticsActivity, result, Toast.LENGTH_LONG).show()
                buildUi()
            }
        }.start()
    }
}
