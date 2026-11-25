<?php
/**
 * Matcher bersama notifikasi QRIS v2New + re-match operator (perbaikan C2,
 * desain 08-c2-operator-rematch-design-v1.0.md).
 *
 * Diekstrak VERBATIM dari bayarQR/notify.php — rumus nominal TIDAK berubah:
 * nominal = ceil(dasar) + ceil(dasar * 0,7%) + kode_unik, dasar =
 * total_tagihan (atau hitung ulang komponen bila kosong).
 *
 * ATURAN KANDIDAT (C6, keputusan user 23 Nov 2026 21:13): kandidat = SEMUA
 * proforma UNPAID yang sudah berkode unik — disamakan dengan warganet
 * ("periksa semua tunggakan"). Sebelumnya hanya proforma TERBARU tiap
 * pelanggan (JOIN MAX(id)), sehingga bayar tunggakan lama tidak pernah
 * cocok. Konsekuensi yang disengaja: bayar tunggakan lama kini cocok dan
 * lunas; kembar nominal — termasuk dua tunggakan satu pelanggan — kini
 * ambiguous dan tidak ada yang lunas (sebelumnya yang terbaru yang menang).
 * Harness S5b-R9 menguji perilaku baru ini; S1-S4/S6 harus tetap hijau.
 *
 * Fungsi:
 *   qris_expected_nominal($dasar, $kode) — rumus nominal, satu tempat.
 *   qris_find_candidates($db, $amount)   — kandidat yang nominalnya cocok.
 *   qris_match_notification($db, $logId) — jalankan matcher atas satu baris
 *       log dan tulis transisi statusnya. Dipakai notify.php (insert baru)
 *       dan qris_rematch_log().
 *   qris_rematch_log($db, $logId, $oleh) — algoritma re-match per §5 desain:
 *       kunci baris (FOR UPDATE), cocokkan ulang, stempel audit, lunasi
 *       bila cocok lewat qris_apply_payment() yang sudah ada.
 *   qris_kode_birth_rematch($db, $proformaId, $kodeUnik) — hook kode-birth
 *       (§4c): cocokkan ulang baris `logged` yang nominalnya sama dengan
 *       nominal baru proforma ini.
 *   qris_ambiguous_alert($db, $logId, $candidates) — titik hook alert kasus
 *       ambigu (keputusan C1). NO-OP: channel alert diputuskan berjalan di
 *       sisi APLIKASI Android (Stage D); WA/email dari server DIPENDING.
 *       Hook dipertahankan sebagai titik integrasi bila WA/email nanti
 *       dibutuhkan.
 *   qris_maybe_mark_loser($db, $logId) — penanda loser C3 bentuk A: baris
 *       matched + belum diterapkan karena pelunasan kalah klaim atomik;
 *       menulis apply_note yang menyebut proforma + log pemenang.
 *
 * Keputusan C1/C3 (user 23 Nov 2026 21:08): kasus ambigu diputuskan operator
 * setelah pemeriksaan (harus terlihat jelas + terhitung di tampilan log);
 * loser dicocokkan manual operator lewat re-match — karena itu baris
 * matched + belum diterapkan JUGA bisa di-re-match (matcher melepaskan
 * kecocokan lama bila re-match tidak menemukan kandidat). Penandaan loser
 * bentuk B (baris `logged` yang nominalnya sama dengan pembayaran yang
 * sudah dilunasi) ditulis matcher pada hasil 0-hit. C6 DIPUTUSKAN user 23
 * Nov 2026 21:13 (samakan kedua sistem jadi "periksa semua tunggakan") —
 * lihat ATURAN KANDIDAT di atas.
 *
 * Batasan desain (review #6): tanpa kunci dedupe baru; tanpa fuzzy matching;
 * tanpa auto-sweep — re-match hanya dipicu operator (qris_rematch.php) atau
 * hook kode-birth, dan setiap re-match tercatat (rematch_count,
 * last_rematch_at, rematched_by).
 */

function qris_expected_nominal($dasar, $kode) {
    $dasarInt = (int)ceil((float)$dasar);
    return $dasarInt + (int)ceil($dasarInt * 0.007) + (int)$kode;
}

// Dasar nominal dari satu baris proforma, rumus yang sama dengan matcher
// (total_tagihan bila ada, bila tidak hitung ulang dari komponen).
function qris_base_from_proforma_row($row) {
    $base = (float)($row['total_tagihan'] ?? 0);
    if ($base <= 0) {
        $base = (float)$row['tagihan'] + (float)$row['addon'] + (float)$row['biaya_instalasi'] + (float)$row['pajak'] - (float)$row['diskon'];
    }
    return $base;
}

function qris_find_candidates(mysqli $db, $amount) {
    $amount = (int)$amount;
    // C6 (keputusan user 23 Nov 2026 21:13): kandidat = SEMUA proforma
    // UNPAID yang sudah berkode unik — disamakan dengan warganet
    // ("periksa semua tunggakan"). Sebelumnya hanya proforma TERBARU tiap
    // pelanggan (JOIN MAX(id) pada subquery), sehingga bayar tunggakan
    // lama tidak pernah cocok. Rumus nominal TIDAK berubah.
    $candSql = "SELECT p.id, p.id_pelanggan, p.tagihan, p.addon, p.biaya_instalasi,
                       p.pajak, p.diskon, p.total_tagihan, p.kode_unik
                FROM proforma p
                WHERE p.status = 'UNPAID' AND p.kode_unik IS NOT NULL";
    $hits = [];
    if ($res = $db->query($candSql)) {
        while ($row = $res->fetch_assoc()) {
            if (qris_expected_nominal(qris_base_from_proforma_row($row), (int)$row['kode_unik']) === $amount) {
                $hits[] = $row;
            }
        }
        $res->free();
    }
    return $hits;
}

// Menjalankan matcher atas satu baris log dan menulis transisi statusnya.
// Mengembalikan ['status' => matched|ambiguous|logged|not_found,
// 'candidates' => n, ...]. Untuk 'matched', 'transitioned' menandakan
// UPDATE berpenjaga benar-benar mengubah baris (false = aktor lain sudah
// mengubahnya lebih dulu). Fungsi ini TIDAK melunasi — pelunasan adalah
// urusan pemanggil (notify.php / qris_rematch_log).
function qris_match_notification(mysqli $db, $logId) {
    $logId = (int)$logId;
    $sel = $db->prepare("SELECT id, amount, match_status, applied_at FROM qris_notif_log WHERE id = ?");
    $sel->bind_param('i', $logId);
    $sel->execute();
    $log = $sel->get_result()->fetch_assoc();
    $sel->close();
    if (!$log) {
        return ['status' => 'not_found', 'candidates' => 0];
    }
    $amount = $log['amount'];
    if ($amount === null || (int)$amount <= 0) {
        return ['status' => 'logged', 'candidates' => 0, 'note' => 'tanpa nominal'];
    }
    $hits = qris_find_candidates($db, (int)$amount);
    if (count($hits) === 1) {
        $mid = (int)$hits[0]['id_pelanggan'];
        $mpf = (int)$hits[0]['id'];
        // Penjaga status: hanya baris yang masih logged/ambiguous/matched
        // dan belum diterapkan yang boleh bertransisi. 'matched' ikut
        // dijaga agar loser C3 (matched + belum diterapkan) bisa
        // dicocokkan ulang ke kandidat lain. Untuk insert baru (logged,
        // applied_at NULL) penjaga ini selalu lolos — perilaku sama persis
        // dengan UPDATE berdasarkan dedupe_key sebelumnya.
        $upd = $db->prepare("UPDATE qris_notif_log SET match_status = 'matched', matched_id_pelanggan = ?, matched_id_proforma = ? WHERE id = ? AND match_status IN ('logged','ambiguous','matched') AND applied_at IS NULL");
        $upd->bind_param('iii', $mid, $mpf, $logId);
        $upd->execute();
        $changed = $upd->affected_rows;
        $upd->close();
        return ['status' => 'matched', 'candidates' => 1, 'id_pelanggan' => $mid, 'proforma' => $mpf, 'transitioned' => $changed === 1];
    }
    if (count($hits) > 1) {
        // C1: kasus ambigu harus terlihat jelas — catat jumlah kandidat di
        // apply_note dan panggil titik hook alert (no-op sampai channel
        // diputuskan user). Hook hanya dipanggil bila benar-benar ada
        // transisi/perubahan (baris ambiguous yang di-re-match dengan
        // kandidat sama tidak memicu alert baru).
        $note = 'ambiguous: ' . count($hits) . ' kandidat — perlu keputusan operator';
        $upd = $db->prepare("UPDATE qris_notif_log SET match_status = 'ambiguous', apply_note = ? WHERE id = ? AND match_status IN ('logged','ambiguous','matched') AND applied_at IS NULL");
        $upd->bind_param('si', $note, $logId);
        $upd->execute();
        $changed = $upd->affected_rows;
        $upd->close();
        if ($changed === 1) {
            try { qris_ambiguous_alert($db, $logId, $hits); } catch (Throwable $e) { /* hook tidak boleh merusak matcher */ }
        }
        return ['status' => 'ambiguous', 'candidates' => count($hits)];
    }
    // 0 kandidat UNPAID. C3 bentuk B: periksa apakah nominal ini sama
    // dengan pembayaran yang sudah dilunasi — kalah berebut / bayar ganda.
    // Lookup berindeks pada amount, dibatasi 5 baris terbaru; pemenang
    // dicatat di apply_note agar operator langsung tahu ke mana nominal
    // ini pergi. Bila tidak ada pemenang, baris dibiarkan `logged` polos
    // (kasus C2: kode belum lahir / nominal tidak dikenal).
    $loserNoted = false;
    $w = $db->prepare("SELECT id, matched_id_proforma FROM qris_notif_log WHERE amount = ? AND match_status = 'matched' AND applied_at IS NOT NULL ORDER BY applied_at DESC LIMIT 5");
    $w->bind_param('i', $amount);
    $w->execute();
    $wres = $w->get_result();
    $winner = null;
    while ($wr = $wres->fetch_assoc()) {
        if ($wr['matched_id_proforma'] !== null) { $winner = $wr; break; }
    }
    $w->close();
    if ($winner) {
        $note = 'nominal sama dengan pembayaran log #' . (int)$winner['id'] . ' yang melunasi proforma ' . (int)$winner['matched_id_proforma'] . ' — kemungkinan bayar ganda / kalah berebut; periksa manual';
        $upd = $db->prepare("UPDATE qris_notif_log SET apply_note = ? WHERE id = ? AND match_status IN ('logged','ambiguous','matched') AND applied_at IS NULL");
        $upd->bind_param('si', $note, $logId);
        $upd->execute();
        $loserNoted = $upd->affected_rows === 1;
        $upd->close();
    }
    return ['status' => 'logged', 'candidates' => 0, 'loser_noted' => $loserNoted];
}

// Titik hook alert kasus ambigu (keputusan C1, user 23 Nov 2026 21:08 +
// 21:13): channel alert DIPUTUSKAN berjalan di sisi APLIKASI Android
// (getar/notifikasi/dering HP + lencana jumlah kasus ambigu — tugas agen
// Stage D, membaca status ambiguous dari respons notify). WA/email dari
// server DIPENDING untuk saat ini (nanti dibutuhkan) — JANGAN kirim alert
// WA/email dari sini. Fungsi ini sengaja NO-OP dan dipertahankan sebagai
// titik integrasi bila alert server nanti dibutuhkan. Dipanggil matcher
// hanya pada transisi BARU ke ambiguous (insert baru maupun re-match).
// Catatan implementasi bila nanti diaktifkan: fungsi ini dipanggil dari
// DALAM transaksi matcher — antrekan pengiriman / kirim sesudah commit,
// jangan blokir transaksi dengan HTTP keluar. $candidates = baris kandidat
// (id proforma + id_pelanggan) untuk isi alert nanti.
function qris_ambiguous_alert(mysqli $db, $logId, array $candidates) {
    // Hook point — channel alert = aplikasi Android (Stage D); WA/email server DIPENDING.
}

// Penanda loser C3 bentuk A: baris matched + belum diterapkan karena
// pelunasan kalah klaim atomik (proforma sudah PAID oleh notifikasi lain).
// Dipanggil pemanggil apply (notify.php dan qris_rematch_log) SESUDAH
// apply mengembalikan applied=false. Menulis apply_note yang menyebut
// proforma pemenang + log pemenang — hanya bila baris ini memang matched +
// belum diterapkan DAN proformanya kini PAID; bila tidak, diam.
// Best-effort: tidak pernah melempar.
function qris_maybe_mark_loser(mysqli $db, $logId) {
    $logId = (int)$logId;
    try {
        $sel = $db->prepare("SELECT id, match_status, matched_id_proforma, applied_at, apply_note FROM qris_notif_log WHERE id = ?");
        $sel->bind_param('i', $logId);
        $sel->execute();
        $log = $sel->get_result()->fetch_assoc();
        $sel->close();
        if (!$log || $log['match_status'] !== 'matched' || $log['applied_at'] !== null || $log['matched_id_proforma'] === null) {
            return false;
        }
        // Jangan timpa catatan loser yang sudah ada (idempotent).
        if ($log['apply_note'] !== null && strpos($log['apply_note'], 'kalah berebut') === 0) {
            return false;
        }
        $pf = (int)$log['matched_id_proforma'];
        $p = $db->prepare("SELECT status FROM proforma WHERE id = ?");
        $p->bind_param('i', $pf);
        $p->execute();
        $prof = $p->get_result()->fetch_assoc();
        $p->close();
        if (!$prof || $prof['status'] !== 'PAID') {
            return false;
        }
        // Pemenang = log pertama yang menerapkan proforma ini.
        $w = $db->prepare("SELECT id FROM qris_notif_log WHERE matched_id_proforma = ? AND applied_at IS NOT NULL ORDER BY applied_at ASC LIMIT 1");
        $w->bind_param('i', $pf);
        $w->execute();
        $winner = $w->get_result()->fetch_assoc();
        $w->close();
        if ($winner) {
            $note = 'kalah berebut: proforma ' . $pf . ' sudah dilunasi notifikasi log #' . (int)$winner['id'] . ' — cocokkan ulang manual bila pembayaran ini untuk tagihan lain';
        } else {
            $note = 'kalah berebut: proforma ' . $pf . ' sudah PAID (dilunasi di luar log ini) — cocokkan ulang manual bila pembayaran ini untuk tagihan lain';
        }
        $upd = $db->prepare("UPDATE qris_notif_log SET apply_note = ? WHERE id = ? AND match_status = 'matched' AND applied_at IS NULL");
        $upd->bind_param('si', $note, $logId);
        $upd->execute();
        $upd->close();
        return true;
    } catch (Throwable $e) {
        error_log('C3 mark-loser gagal (log ' . $logId . '): ' . $e->getMessage());
        return false;
    }
}

// Re-match satu baris log (§5 desain). Mengembalikan
// ['ok', 'log_id', 'before', 'after', 'applied', 'note', ...] plus
// 'applied_at'/'apply_note' segar sesudah pelunasan (untuk tampilan admin).
function qris_rematch_log(mysqli $db, $logId, $oleh) {
    $logId = (int)$logId;
    $oleh = mb_substr((string)$oleh, 0, 64);
    $out = ['ok' => false, 'log_id' => $logId, 'before' => null, 'after' => null, 'applied' => false, 'note' => ''];

    $match = null;
    $after = null;
    $db->begin_transaction();
    try {
        $sel = $db->prepare("SELECT id, amount, match_status, matched_id_pelanggan, matched_id_proforma, applied_at FROM qris_notif_log WHERE id = ? FOR UPDATE");
        $sel->bind_param('i', $logId);
        $sel->execute();
        $log = $sel->get_result()->fetch_assoc();
        $sel->close();
        if (!$log) {
            $db->rollback();
            $out['note'] = 'log tidak ditemukan';
            return $out;
        }
        $out['before'] = $log['match_status'];
        // Eligibilitas C2+C3: baris yang sudah diterapkan tidak bisa
        // di-re-match; baris matched + BELUM diterapkan BISA (loser C3 —
        // dicocokkan ulang ke tagihannya sendiri, atau dilepas bila tidak
        // ada kandidat). Baris dengan status di luar ketiganya ditolak.
        if ($log['applied_at'] !== null) {
            $db->rollback();
            $out['after'] = $log['match_status'];
            $out['note'] = 'sudah diterapkan — tidak ada yang dicocokkan ulang';
            return $out;
        }
        if (!in_array($log['match_status'], ['logged', 'ambiguous', 'matched'], true)) {
            $db->rollback();
            $out['after'] = $log['match_status'];
            $out['note'] = 'status ' . $log['match_status'] . ' tidak bisa dicocokkan ulang';
            return $out;
        }
        // Matcher bersama (transisi status ditulis di sini, masih di dalam
        // transaksi yang sama; baris terkunci FOR UPDATE sehingga dua
        // operator yang bersamaan tidak bisa meloloskan dua transisi).
        $match = qris_match_notification($db, $logId);
        $after = $match['status'];
        // C3: re-match atas loser matched yang tidak menemukan kandidat
        // melepaskan kecocokan lamanya (proforma lama sudah PAID / tidak
        // cocok lagi) agar baris kembali `logged` dan terlihat di filter
        // "Belum cocok". Catatan matcher (bila ada penanda loser) dipertahankan;
        // bila matcher tidak mencatat apa-apa, tulis catatan lepas generik.
        if ($after === 'logged' && $log['match_status'] === 'matched') {
            if (!empty($match['loser_noted'])) {
                $rel = $db->prepare("UPDATE qris_notif_log SET match_status = 'logged', matched_id_pelanggan = NULL, matched_id_proforma = NULL WHERE id = ?");
                $rel->bind_param('i', $logId);
            } else {
                $relNote = 'cocok ulang: tidak ada kandidat — kecocokan lama dilepas';
                $rel = $db->prepare("UPDATE qris_notif_log SET match_status = 'logged', matched_id_pelanggan = NULL, matched_id_proforma = NULL, apply_note = ? WHERE id = ?");
                $rel->bind_param('si', $relNote, $logId);
            }
            $rel->execute();
            $rel->close();
        }
        // Stempel audit setiap percobaan re-match, apa pun hasilnya.
        // apply_note TIDAK disentuh di sini — catatan hasil matcher
        // (ambiguous / loser) sudah ditulis qris_match_notification.
        $st = $db->prepare("UPDATE qris_notif_log SET rematch_count = rematch_count + 1, last_rematch_at = NOW(), rematched_by = ? WHERE id = ?");
        $st->bind_param('si', $oleh, $logId);
        $st->execute();
        $st->close();
        $db->commit();
    } catch (Throwable $e) {
        try { $db->rollback(); } catch (Throwable $e2) { /* abaikan */ }
        $out['note'] = 'gagal: ' . $e->getMessage();
        return $out;
    }

    $out['after'] = $after;
    $out['candidates'] = (int)($match['candidates'] ?? 0);
    if (isset($match['id_pelanggan'])) { $out['id_pelanggan'] = $match['id_pelanggan']; }
    if (isset($match['proforma'])) { $out['proforma'] = $match['proforma']; }
    if ($after === 'matched' && !empty($match['transitioned'])) {
        // Pelunasan lewat fungsi yang sama dengan jalur notify, SESUDAH
        // commit (fungsi itu membuka transaksinya sendiri). Klaim atomik
        // di dalamnya mengakui paling banyak satu efek bisnis; bila kalah
        // lomba dengan jalur lain, baris tetap matched + belum diterapkan
        // (terlihat operator, tidak pernah hilang diam-diam).
        try {
            require_once __DIR__ . '/qris_apply.php';
            $applyRes = qris_apply_payment($db, $logId);
            $out['applied'] = !empty($applyRes['applied']);
            if ($out['applied']) {
                $out['note'] = 'cocok + dilunasi';
            } else {
                $langkah = isset($applyRes['steps']) && is_array($applyRes['steps']) ? implode('; ', $applyRes['steps']) : '';
                $out['note'] = 'cocok, pelunasan dilewati: ' . mb_substr($langkah, 0, 200);
            }
        } catch (Throwable $e) {
            $out['note'] = 'cocok, pelunasan error: ' . $e->getMessage();
        }
    } elseif ($after === 'matched') {
        $out['note'] = 'transisi kalah oleh aktor lain — baca ulang, berhenti';
    } elseif ($after === 'ambiguous') {
        $out['note'] = 're-match: ' . (int)$match['candidates'] . ' kandidat (ambiguous)';
    } else {
        $out['note'] = 'tetap logged — tidak ada kandidat yang cocok';
    }
    // C3 bentuk A: bila pelunasan gagal karena kalah klaim (proforma sudah
    // PAID oleh notifikasi lain), tandai kekalahan di baris ini sebelum
    // baca ulang — agar respons memuat catatan loser yang segar.
    if ($after === 'matched' && !$out['applied']) {
        try { qris_maybe_mark_loser($db, $logId); } catch (Throwable $e) { /* best-effort */ }
    }
    // Baca ulang keadaan akhir baris untuk tampilan admin.
    try {
        $sel2 = $db->prepare("SELECT match_status, applied_at, apply_note, rematch_count, last_rematch_at, rematched_by FROM qris_notif_log WHERE id = ?");
        $sel2->bind_param('i', $logId);
        $sel2->execute();
        $akhir = $sel2->get_result()->fetch_assoc();
        $sel2->close();
        if ($akhir) {
            $out['after'] = $akhir['match_status'];
            $out['applied_at'] = $akhir['applied_at'];
            $out['apply_note'] = $akhir['apply_note'];
            $out['rematch_count'] = (int)$akhir['rematch_count'];
            $out['last_rematch_at'] = $akhir['last_rematch_at'];
            $out['rematched_by'] = $akhir['rematched_by'];
        }
    } catch (Throwable $e) { /* baca ulang gagal tidak membatalkan hasil */ }
    $out['ok'] = true;
    return $out;
}

// Hook kode-birth (§4c): dipanggil dari member/payment.php TEPAT sesudah
// kode_unik berhasil diklaim untuk proforma ini (dan hanya bila klaim itu
// terjadi di request ini). Mencari baris `logged` yang nominalnya sama
// dengan nominal baru proforma (satu lookup berindeks idx_amount; kasus
// umum tidak ada baris menunggu) lalu me-re-match tiap baris itu dengan
// rematched_by = 'kode-birth'. Mengembalikan ringkasan; tidak pernah
// melempar — pemanggil membungkusnya try/catch agar render QR tak terganggu.
function qris_kode_birth_rematch(mysqli $db, $proformaId, $kodeUnik) {
    $proformaId = (int)$proformaId;
    $kodeUnik = (int)$kodeUnik;
    $out = ['proforma' => $proformaId, 'kode_unik' => $kodeUnik, 'expected' => null, 'checked' => 0, 'applied' => 0];
    $sel = $db->prepare("SELECT id, tagihan, addon, biaya_instalasi, pajak, diskon, total_tagihan FROM proforma WHERE id = ?");
    $sel->bind_param('i', $proformaId);
    $sel->execute();
    $prof = $sel->get_result()->fetch_assoc();
    $sel->close();
    if (!$prof) { return $out; }
    $expected = qris_expected_nominal(qris_base_from_proforma_row($prof), $kodeUnik);
    $out['expected'] = $expected;
    $q = $db->prepare("SELECT id FROM qris_notif_log WHERE amount = ? AND match_status = 'logged' AND applied_at IS NULL ORDER BY id");
    $q->bind_param('i', $expected);
    $q->execute();
    $res = $q->get_result();
    $ids = [];
    while ($r = $res->fetch_assoc()) { $ids[] = (int)$r['id']; }
    $q->close();
    foreach ($ids as $id) {
        $out['checked']++;
        try {
            $rm = qris_rematch_log($db, $id, 'kode-birth');
            if (!empty($rm['applied'])) { $out['applied']++; }
        } catch (Throwable $e) {
            error_log('C2 kode-birth rematch gagal (log ' . $id . '): ' . $e->getMessage());
        }
    }
    return $out;
}
