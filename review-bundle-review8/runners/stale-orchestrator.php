#!/usr/bin/env php
<?php
// stale-orchestrator.php — driver interleaving stale-binding yang
// DETERMINISTIK untuk run-review8.sh (review #8, P1 stale match binding).
//
// Masalah yang dipecahkan: jendela lomba apply terletak di antara baca
// seleksi TANPA kunci dan SELECT ... FOR UPDATE di awal transaksi —
// jendela yang tidak bisa dipukul andal dengan sleep/timing. Skrip ini
// memakai kunci baris itu sendiri sebagai titik sinkronisasi:
//
//   1. Koneksi T mengunci baris log (BEGIN + SELECT ... FOR UPDATE).
//   2. Apply dijalankan lewat probe-apply.php (fungsi apply ASLI):
//      ia menyelesaikan baca seleksinya (tanpa kunci), lalu TERBLOKIR
//      pada kunci log — dibuktikan dengan polling
//      INFORMATION_SCHEMA.INNODB_LOCK_WAITS (transaksi apply menunggu
//      transaksi T), BUKAN dengan sleep.
//   3. Selagi apply terblokir, T mengubah binding baris log
//      (mode=bill: matched_id_* ke kandidat lain — persis kolom yang
//      ditulis transisi re-match; mode=nominal: total tagihan diubah
//      sehingga nominal harapan tidak lagi cocok; mode=status: status
//      pelanggan dibalik UNPAID -> ISOLIR — persis yang dilakukan cron
//      status saat masa_aktif habis; mode=lockwait: tidak mengubah
//      apa-apa, hanya menahan kunci melewati batas timeout apply untuk
//      memicu 1205), lalu COMMIT.
//   4. Apply melanjutkan dengan binding segar di bawah kunci — dan
//      harus MENOLAK (perbaikan review #8) atau terbukti melunasi
//      tagihan basi (kode pra-perbaikan). Untuk mode=status, apply
//      harus MELUNASI dengan poin dari status SEGAR (ISOLIR -> 5),
//      bukan dari status pra-kunci (UNPAID -> 10); kode pra-perbaikan
//      review #9 terbukti memberi 10.
//
// Mode=deadlock (review #9 item (b), run-review9b.sh) MEMAKSA deadlock
// 1213 secara deterministik — bukan mengandalkan nol 1213 yang
// teramati. Dua transaksi mengakuisisi baris yang sama dengan urutan
// berlawanan (siklus kunci):
//   apply:  log -> tagihan -> pelanggan (urutan seragam semua apply)
//   peer:   pelanggan -> tagihan (persis v2 task/servicechange.php:
//           UPDATE pelanggan dulu, lalu UPDATE proforma — inversi yang
//           sudah ada; untuk wg bentuk yang sama sebagai SQL khusus-tes,
//           karena servicechange wg hanya menyentuh pelanggan)
// Urutan orkestrasi (setiap langkah dibuktikan kunci baris, bukan
// sleep): T mengunci log; peer (deadlock-peer.php, proses terpisah)
// memegang kunci pelanggan (ready-file + trx_id); apply dijalankan
// dan TERBUKTI menunggu kunci log T; T commit; apply maju (log,
// tagihan) lalu TERBUKTI menunggu kunci pelanggan peer; baru peer
// meminta kunci tagihan -> siklus lengkap -> InnoDB mendeteksi
// deadlock dan mengembalikan 1213 ke korban. KOREKSI (temuan empiris
// 25 Nov 2026, kontrol pertama run-review9b): korban dipilih
// berdasarkan BOBOT (jumlah kunci yang dipegang), BUKAN jumlah baris
// yang diubah — peer servicechange asli (1 kunci pelanggan) LEBIH
// RINGAN dari apply (kunci log + tagihan), sehingga dalam tabrakan
// nyata PEER-lah yang jadi korban 1213 dan apply lunas normal
// (pembayaran menang — perilaku yang menguntungkan produksi).
// Untuk MEMAKSA apply menjadi korban, peer diberi bobot tambahan
// lewat deadlock-peer.php --extra-customers (mengunci baris pelanggan
// dummy tambahan sebelum go); urutan kunci peer tetap pelanggan dulu
// lalu tagihan. Lihat deadlock-peer.php.
// Peer lalu commit sebagai survivor; apply harus applied=false
// dengan rollback penuh; baris tetap matched dan apply ulang harus
// berhasil (recoverable). Bagian deadlock terakhir InnoDB
// (LATEST DETECTED DEADLOCK) ditangkap ke --deadlock-file sebagai
// bukti 1213 yang otoritatif — pesan apply sendiri tidak memuat kode
// 1213 (mysqli mode senyap: execute() gagal + warning di log worker,
// lalu fetch_assoc() atas bool melempar Error yang ditangkap apply).
//
// Urutan program apply menjamin baca seleksi sudah selesai saat kunci
// ditunggu (kunci adalah pernyataan pertama transaksi; baca seleksi
// mendahului begin_transaction), jadi deteksi "apply menunggu kunci"
// adalah bukti deterministik bahwa seleksi sudah terjadi SEBELUM switch.
//
// Kredensial DB dibaca dari file .cnf gaya MySQL (--cnf), tidak pernah
// lewat argv (agar tidak muncul di ps). Pakai .my-root.cnf: deteksi
// kunci butuh hak PROCESS (INNODB_TRX / INNODB_LOCK_WAITS atas koneksi
// lain), yang tidak dimiliki user contract. Keluar 0 bila orkestrasi
// berhasil (putusan PASS/FAIL ada di runner, dari isi tabel); keluar 2
// bila orkestrasi gagal (apply tidak pernah terblokir, curl gagal, dsb).
//
// Penggunaan (dipanggil run-review8.sh; jangan dipakai manual tanpa
// memahami stack scratch):
//   php stale-orchestrator.php --target v2|wg --cnf PATH --db-name DB
//     --log-id N --probe-url URL --mode bill|nominal|status|lockwait|deadlock
//     --resp-file PATH --code-file PATH
//     [--switch-bill M --switch-customer C]   (mode=bill)
//     [--bill-id B --switch-total T]          (mode=nominal)
//     [--switch-customer C --switch-status S] (mode=status)
//     [--hold-seconds S]                      (mode=lockwait)
//     [--probe-lock-wait-timeout N]           (lock_wait_timeout sesi apply)
//     [--switch-customer C --bill-id B --peer-script PATH
//      --peer-layanan L --peer-tagihan T --deadlock-file PATH
//      [--extra-customers C1,C2,...] --skip-invoice-lock] (mode=deadlock)

function fail($msg, $code = 2) {
    fwrite(STDERR, "stale-orchestrator: $msg\n");
    echo json_encode(['ok' => false, 'error' => $msg]) . "\n";
    exit($code);
}

$opts = getopt('', [
    'target:', 'cnf:', 'db-name:', 'log-id:', 'probe-url:', 'mode:',
    'resp-file:', 'code-file:', 'switch-bill:', 'switch-customer:',
    'switch-status:', 'bill-id:', 'switch-total:', 'hold-seconds:',
    'probe-lock-wait-timeout:', 'peer-script:', 'peer-layanan:',
    'peer-tagihan:', 'deadlock-file:', 'extra-customers:', 'skip-invoice-lock',
]);
$target = $opts['target'] ?? '';
if (!in_array($target, ['v2', 'wg'], true)) { fail('target harus v2|wg'); }
$mode = $opts['mode'] ?? '';
if (!in_array($mode, ['bill', 'nominal', 'status', 'lockwait', 'deadlock'], true)) { fail('mode harus bill|nominal|status|lockwait|deadlock'); }
$logId = (int)($opts['log-id'] ?? 0);
if ($logId <= 0) { fail('log-id tidak valid'); }
$cnf = $opts['cnf'] ?? '';
$dbName = $opts['db-name'] ?? '';
$probeUrl = $opts['probe-url'] ?? '';
$respFile = $opts['resp-file'] ?? '';
$codeFile = $opts['code-file'] ?? '';
if ($cnf === '' || !is_readable($cnf)) { fail('cnf tidak bisa dibaca'); }
if ($dbName === '' || $probeUrl === '' || $respFile === '' || $codeFile === '') {
    fail('argumen wajib hilang (db-name/probe-url/resp-file/code-file)');
}
if ($mode === 'bill' && (empty($opts['switch-bill']) || !isset($opts['switch-customer']))) {
    fail('mode=bill butuh switch-bill + switch-customer');
}
if ($mode === 'nominal' && (empty($opts['bill-id']) || !isset($opts['switch-total']))) {
    fail('mode=nominal butuh bill-id + switch-total');
}
if ($mode === 'status' && (empty($opts['switch-customer']) || empty($opts['switch-status']))) {
    fail('mode=status butuh switch-customer + switch-status');
}
if ($mode === 'deadlock' && (empty($opts['switch-customer']) || empty($opts['bill-id'])
    || empty($opts['peer-script']) || !is_readable($opts['peer-script'])
    || empty($opts['peer-layanan']) || !isset($opts['peer-tagihan']) || empty($opts['deadlock-file']))) {
    fail('mode=deadlock butuh switch-customer + bill-id + peer-script + peer-layanan + peer-tagihan + deadlock-file');
}
$holdSeconds = (int)($opts['hold-seconds'] ?? 5);
$probeLwt = (int)($opts['probe-lock-wait-timeout'] ?? 0);

// --- tabel/kolom per target ---
if ($target === 'v2') {
    $logTable = 'qris_notif_log';
    $billTable = 'proforma';
    $billCol = 'matched_id_proforma';
    $totalCol = 'total_tagihan';
} else {
    $logTable = 'paylist_log';
    $billTable = 'payment';
    $billCol = 'matched_id_payment';
    $totalCol = 'tagihan';
}

// --- kredensial dari file .cnf gaya MySQL ([client]) ---
$cfg = ['user' => null, 'password' => null, 'socket' => null, 'host' => null, 'port' => null];
$inClient = false;
foreach (file($cnf, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $line) {
    $line = trim($line);
    if ($line === '' || $line[0] === '#' || $line[0] === ';') { continue; }
    if ($line[0] === '[') { $inClient = ($line === '[client]'); continue; }
    if (!$inClient || strpos($line, '=') === false) { continue; }
    list($k, $v) = explode('=', $line, 2);
    $k = trim($k);
    if (array_key_exists($k, $cfg)) { $cfg[$k] = trim($v, " \t\"'"); }
}
if ($cfg['user'] === null) { fail('cnf tidak berisi user'); }

$curlPid = null;
$peerPid = null;
try {
    if (!empty($cfg['socket'])) {
        $db = new mysqli('localhost', $cfg['user'], (string)$cfg['password'], $dbName, null, $cfg['socket']);
    } else {
        $db = new mysqli($cfg['host'] ?: '127.0.0.1', $cfg['user'], (string)$cfg['password'], $dbName, (int)($cfg['port'] ?: 3306));
    }
    $db->set_charset('utf8mb4');

    // 1. Kunci baris log.
    $db->begin_transaction();
    $st = $db->prepare("SELECT id FROM $logTable WHERE id = ? FOR UPDATE");
    $st->bind_param('i', $logId);
    $st->execute();
    $row = $st->get_result()->fetch_assoc();
    $st->close();
    if (!$row) { $db->rollback(); fail("log $logId tidak ditemukan"); }
    // trx_id sendiri — HANYA untuk mode selain deadlock (temuan
    // review #9b, A/B 4/4 25 Nov 2026): membaca INNODB_TRX di sini
    // (sebelum peer di-spawn) berkorelasi sempurna dengan lookup trx
    // peer yang mengembalikan 0 baris — transaksi peer (in_tx=1,
    // UPDATE 1 baris) tidak terlihat di INNODB_TRX, sehingga
    // orkestrator gagal di tahap ready. Mekanisme pastinya belum
    // terjelaskan (bacaan I_S read-only seharusnya tidak memengaruhi
    // koneksi lain); cabang deadlock mengambil trx_id-nya SENDIRI
    // sesudah peer siap (lookup peer sudah selesai saat itu) dan
    // memakai fallback PROCESSLIST bila lookup itu mengembalikan 0.
    $trxId = 0;
    if ($mode !== 'deadlock') {
        $trxRow = $db->query("SELECT trx_id FROM INFORMATION_SCHEMA.INNODB_TRX WHERE trx_mysql_thread_id = CONNECTION_ID()")->fetch_assoc();
        $trxId = $trxRow ? (int)$trxRow['trx_id'] : 0;
    }

    // ===== mode=deadlock: cabang tersendiri (alur berbeda dari mode lain) =====
    if ($mode === 'deadlock') {
        $custDl = (string)$opts['switch-customer'];
        $billIdDl = (int)$opts['bill-id'];
        $skipInvoice = isset($opts['skip-invoice-lock']);
        $baseDir = dirname($respFile);
        $tag = preg_replace('/\.apply\.json$/', '', basename($respFile));
        $readyFile = $baseDir . '/' . $tag . '.peer-ready.json';
        $goFile = $baseDir . '/' . $tag . '.peer-go';
        $resultFile = $baseDir . '/' . $tag . '.peer-result.json';
        $deadlockFile = (string)$opts['deadlock-file'];
        $peerOut = $baseDir . '/' . $tag . '.peer-stdout';
        $peerErr = $baseDir . '/' . $tag . '.peer-stderr';
        foreach ([$readyFile, $goFile, $resultFile] as $f) {
            if (file_exists($f)) { unlink($f); }
        }
        $killDl = function () use (&$curlPid, &$peerPid) {
            if ($curlPid && file_exists("/proc/$curlPid")) { shell_exec('kill ' . (int)$curlPid . ' 2>/dev/null'); }
            if ($peerPid && file_exists("/proc/$peerPid")) { shell_exec('kill ' . (int)$peerPid . ' 2>/dev/null'); }
        };

        // 1b. Peer memegang kunci pelanggan (UPDATE pelanggan) dan
        //     menulis ready-file berisi trx_id-nya. Peer TIDAK menyentuh
        //     tagihan sebelum go-file — jadi tidak ada siklus sebelum
        //     apply TERBUKTI menunggu kunci pelanggan peer.
        $peerCmd = 'php ' . escapeshellarg((string)$opts['peer-script'])
            . ' --target ' . escapeshellarg($target)
            . ' --cnf ' . escapeshellarg($cnf)
            . ' --db-name ' . escapeshellarg($dbName)
            . ' --customer ' . escapeshellarg($custDl)
            . ' --bill-id ' . $billIdDl
            . ' --layanan ' . escapeshellarg((string)$opts['peer-layanan'])
            . ' --tagihan ' . escapeshellarg((string)$opts['peer-tagihan'])
            . (!empty($opts['extra-customers']) ? ' --extra-customers ' . escapeshellarg((string)$opts['extra-customers']) : '')
            . ' --ready-file ' . escapeshellarg($readyFile)
            . ' --go-file ' . escapeshellarg($goFile)
            . ' --result-file ' . escapeshellarg($resultFile)
            . ($skipInvoice ? ' --skip-invoice-lock' : '')
            . ' > ' . escapeshellarg($peerOut) . ' 2> ' . escapeshellarg($peerErr)
            . ' & echo $!';
        $peerPid = (int)trim((string)shell_exec($peerCmd));
        if ($peerPid <= 0) { $db->rollback(); fail('gagal menjalankan deadlock-peer'); }
        $peerTrxId = 0;
        $deadline = microtime(true) + 15.0;
        while (microtime(true) < $deadline) {
            if (is_readable($readyFile)) {
                $rj = json_decode((string)file_get_contents($readyFile), true);
                if (is_array($rj) && !empty($rj['ready'])) { $peerTrxId = (int)($rj['trx_id'] ?? 0); break; }
            }
            if (!file_exists("/proc/$peerPid") && !is_readable($resultFile)) { break; }
            usleep(100000);
        }
        if ($peerTrxId <= 0) {
            $db->rollback();
            $killDl();
            $pr = is_readable($resultFile) ? (string)file_get_contents($resultFile) : '';
            $pe = is_readable($peerErr) ? (string)file_get_contents($peerErr) : '';
            fail('deadlock-peer tidak pernah siap (tidak memegang kunci pelanggan): result=' . substr($pr, 0, 200) . ' stderr=' . substr($pe, 0, 300));
        }

        // trx_id sendiri — baru dibaca DI SINI (sesudah peer siap),
        // bukan sebelum spawn peer (lihat catatan di atas: bacaan
        // INNODB_TRX pra-spawn berkorelasi dengan lookup peer yang
        // gagal). Dipakai langkah 3 untuk membuktikan apply menunggu
        // kunci log T lewat INNODB_LOCK_WAITS; bila 0, fallback
        // PROCESSLIST di bawah yang bekerja.
        $trxRow = $db->query("SELECT trx_id FROM INFORMATION_SCHEMA.INNODB_TRX WHERE trx_mysql_thread_id = CONNECTION_ID()")->fetch_assoc();
        $trxId = $trxRow ? (int)$trxRow['trx_id'] : 0;

        // 2. Jalankan apply di latar belakang (probe-apply.php).
        $post = 'log_id=' . $logId;
        if ($probeLwt > 0) { $post .= '&lock_wait_timeout=' . $probeLwt; }
        $cmd = 'curl -s -m 90 -o ' . escapeshellarg($respFile)
            . ' -w %{http_code} -X POST ' . escapeshellarg($probeUrl)
            . ' -d ' . escapeshellarg($post)
            . ' > ' . escapeshellarg($codeFile) . ' 2>/dev/null & echo $!';
        $curlPid = (int)trim((string)shell_exec($cmd));
        if ($curlPid <= 0) { $db->rollback(); $killDl(); fail('gagal menjalankan curl latar belakang'); }

        // 3. Tunggu sampai apply TERBUKTI menunggu kunci log kita.
        $blocked = false;
        $deadline = microtime(true) + 15.0;
        while (microtime(true) < $deadline) {
            if ($trxId > 0) {
                $w = $db->query("SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.INNODB_LOCK_WAITS WHERE blocking_trx_id = $trxId");
                if ($w && (int)$w->fetch_assoc()['n'] >= 1) { $blocked = true; break; }
            }
            $p = $db->query("SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.PROCESSLIST WHERE INFO LIKE '%FOR UPDATE%' AND ID <> CONNECTION_ID() AND COMMAND <> 'Sleep'");
            if ($p && (int)$p->fetch_assoc()['n'] >= 1) { $blocked = true; break; }
            if (!file_exists("/proc/$curlPid")) { break; }
            usleep(100000);
        }
        if (!$blocked) {
            $db->rollback();
            $killDl();
            $resp = is_readable($respFile) ? file_get_contents($respFile) : '(tidak ada respons)';
            fail('apply tidak pernah terblokir pada kunci log (probe mungkin error): ' . substr((string)$resp, 0, 300));
        }

        // 4. Lepas kunci log; apply maju (log, lalu tagihan) dan
        //    berhenti pada kunci pelanggan yang dipegang peer.
        $db->commit();

        // 5. Tunggu sampai apply TERBUKTI menunggu kunci pelanggan peer.
        //    Baru sesudah ini siklus boleh ditutup — deterministik.
        $waitedOnPeer = false;
        $applyTrxId = 0;
        $deadline = microtime(true) + 15.0;
        while (microtime(true) < $deadline) {
            $w = $db->query("SELECT requesting_trx_id FROM INFORMATION_SCHEMA.INNODB_LOCK_WAITS WHERE blocking_trx_id = $peerTrxId LIMIT 1");
            if ($w) {
                $wr = $w->fetch_assoc();
                if ($wr) { $waitedOnPeer = true; $applyTrxId = (int)$wr['requesting_trx_id']; break; }
            }
            if (!file_exists("/proc/$curlPid")) { break; }
            usleep(100000);
        }
        if (!$waitedOnPeer) {
            $killDl();
            $resp = is_readable($respFile) ? file_get_contents($respFile) : '(tidak ada respons)';
            fail('apply tidak pernah menunggu kunci pelanggan peer: ' . substr((string)$resp, 0, 300));
        }

        // 6. Go: peer meminta kunci tagihan -> siklus lengkap ->
        //    InnoDB mendeteksi deadlock (seketika, tanpa timeout).
        file_put_contents($goFile, "go\n");

        // 7. Tunggu probe + peer selesai (maks 100 dtk).
        $deadline = time() + 100;
        while (file_exists("/proc/$curlPid") && time() < $deadline) { usleep(100000); }
        if (file_exists("/proc/$curlPid")) {
            $killDl();
            fail('probe-apply tidak selesai dalam 100 dtk sesudah go');
        }
        $deadline = microtime(true) + 30.0;
        while (!is_readable($resultFile) && microtime(true) < $deadline) { usleep(100000); }
        if (!is_readable($resultFile)) {
            $killDl();
            fail('deadlock-peer tidak menulis result-file');
        }

        // 8. Tangkap LATEST DETECTED DEADLOCK (bukti 1213 otoritatif)
        //    sebelum transaksi lain sempat menimpanya. Scratch idle,
        //    jadi bagian ini adalah deadlock kita.
        $dlText = '(tidak ada bagian LATEST DETECTED DEADLOCK)';
        $st = $db->query("SHOW ENGINE INNODB STATUS");
        if ($st) {
            $row = $st->fetch_assoc();
            $full = (string)($row['Status'] ?? '');
            $start = strpos($full, 'LATEST DETECTED DEADLOCK');
            if ($start !== false) {
                $end = strpos($full, "\nTRANSACTIONS", $start);
                $dlText = ($end !== false) ? substr($full, $start, $end - $start) : substr($full, $start);
            }
        }
        file_put_contents($deadlockFile, $dlText . "\n");
        $db->close();

        $httpCode = is_readable($codeFile) ? trim((string)file_get_contents($codeFile)) : '';
        $body = is_readable($respFile) ? (string)file_get_contents($respFile) : '';
        $decoded = json_decode($body, true);
        $peerRes = json_decode((string)file_get_contents($resultFile), true);
        echo json_encode([
            'ok' => true,
            'blocked_detected' => true,
            'apply_waited_on_peer' => $waitedOnPeer,
            'apply_trx_id' => $applyTrxId,
            'peer_trx_id' => $peerTrxId,
            'http_code' => $httpCode,
            'switch' => ['mode' => 'deadlock', 'skip_invoice_lock' => $skipInvoice],
            'peer' => $peerRes,
            'apply' => ($decoded['apply'] ?? null),
            'deadlock_file' => $deadlockFile,
            'raw' => substr($body, 0, 2000),
        ]) . "\n";
        exit(0);
    }

    // 2. Jalankan apply di latar belakang (probe-apply.php).
    $post = 'log_id=' . $logId;
    if ($probeLwt > 0) { $post .= '&lock_wait_timeout=' . $probeLwt; }
    $cmd = 'curl -s -m 90 -o ' . escapeshellarg($respFile)
        . ' -w %{http_code} -X POST ' . escapeshellarg($probeUrl)
        . ' -d ' . escapeshellarg($post)
        . ' > ' . escapeshellarg($codeFile) . ' 2>/dev/null & echo $!';
    $curlPid = (int)trim((string)shell_exec($cmd));
    if ($curlPid <= 0) { $db->rollback(); fail('gagal menjalankan curl latar belakang'); }

    // 3. Tunggu sampai apply TERBUKTI menunggu kunci kita (maks 15 dtk).
    //    Deteksi utama: INNODB_LOCK_WAITS; cadangan: PROCESSLIST.
    $blocked = false;
    $deadline = microtime(true) + 15.0;
    while (microtime(true) < $deadline) {
        if ($trxId > 0) {
            $w = $db->query("SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.INNODB_LOCK_WAITS WHERE blocking_trx_id = $trxId");
            if ($w && (int)$w->fetch_assoc()['n'] >= 1) { $blocked = true; break; }
        }
        $p = $db->query("SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.PROCESSLIST WHERE INFO LIKE '%FOR UPDATE%' AND ID <> CONNECTION_ID() AND COMMAND <> 'Sleep'");
        if ($p && (int)$p->fetch_assoc()['n'] >= 1) { $blocked = true; break; }
        // Bila curl sudah selesai tanpa pernah terblokir, probe pasti
        // error cepat (bukan menunggu kunci) — jangan tunggu 15 dtk.
        if (!file_exists("/proc/$curlPid")) { break; }
        usleep(100000);
    }
    if (!$blocked) {
        $db->rollback();
        if (file_exists("/proc/$curlPid")) { shell_exec('kill ' . (int)$curlPid . ' 2>/dev/null'); }
        $resp = is_readable($respFile) ? file_get_contents($respFile) : '(tidak ada respons)';
        fail('apply tidak pernah terblokir pada kunci log (probe mungkin error): ' . substr((string)$resp, 0, 300));
    }

    // 4. Switch selagi apply terblokir.
    $switch = ['mode' => $mode];
    if ($mode === 'bill') {
        $sb = (int)$opts['switch-bill'];
        $sc = (string)$opts['switch-customer'];
        // Kolom yang sama dengan yang ditulis transisi re-match
        // (qris_match_notification / paylist_match_notification):
        // status tetap 'matched', binding pindah ke kandidat lain.
        $up = $db->prepare("UPDATE $logTable SET matched_id_pelanggan = ?, $billCol = ? WHERE id = ?");
        $up->bind_param('sii', $sc, $sb, $logId);
        $up->execute();
        $up->close();
        $switch['to_bill'] = $sb;
        $switch['to_customer'] = $sc;
    } elseif ($mode === 'nominal') {
        $bb = (int)$opts['bill-id'];
        $nt = (float)$opts['switch-total'];
        $up = $db->prepare("UPDATE $billTable SET $totalCol = ? WHERE id = ?");
        $up->bind_param('di', $nt, $bb);
        $up->execute();
        $up->close();
        $switch['bill'] = $bb;
        $switch['new_total'] = $nt;
    } elseif ($mode === 'status') {
        $sc = (string)$opts['switch-customer'];
        $ss = (string)$opts['switch-status'];
        // Pembalikan status pelanggan di tengah apply — persis yang
        // dilakukan cron status (v2 cron/cronetter.php, wg
        // cron/status.php: UPDATE pelanggan SET status_pembayaran =
        // 'ISOLIR' ...) saat masa_aktif habis. UPDATE berjalan di dalam
        // transaksi T yang memegang kunci log: apply yang terblokir pada
        // kunci log belum bisa memperoleh kunci pelanggan, jadi
        // perubahan ini mendarat SESUDAH baca pra-kunci apply (selesai
        // sebelum apply terblokir — urutan program) dan SEBELUM baca
        // status di bawah kunci pelanggan (perbaikan review #9).
        // Bind string untuk id_pelanggan: kolomnya INT di v2 dan
        // VARCHAR di wg; MySQL mengoersi string numerik untuk INT.
        $up = $db->prepare("UPDATE pelanggan SET status_pembayaran = ? WHERE id_pelanggan = ?");
        $up->bind_param('ss', $ss, $sc);
        $up->execute();
        $up->close();
        $switch['customer'] = $sc;
        $switch['new_status'] = $ss;
    } else { // lockwait: tahan kunci melewati timeout apply
        sleep($holdSeconds);
        $switch['held_seconds'] = $holdSeconds;
    }

    // 5. Lepas kunci; apply melanjutkan.
    $db->commit();
    $db->close();

    // 6. Tunggu curl selesai (maks 100 dtk).
    $deadline = time() + 100;
    while (file_exists("/proc/$curlPid") && time() < $deadline) { usleep(100000); }
    if (file_exists("/proc/$curlPid")) {
        shell_exec('kill ' . (int)$curlPid . ' 2>/dev/null');
        fail('probe-apply tidak selesai dalam 100 dtk sesudah kunci dilepas');
    }
} catch (Throwable $e) {
    if (isset($db) && $db instanceof mysqli) {
        try { $db->rollback(); } catch (Throwable $e2) { /* abaikan */ }
    }
    if ($curlPid && file_exists("/proc/$curlPid")) {
        shell_exec('kill ' . (int)$curlPid . ' 2>/dev/null');
    }
    if ($peerPid && file_exists("/proc/$peerPid")) {
        shell_exec('kill ' . (int)$peerPid . ' 2>/dev/null');
    }
    fail('exception: ' . $e->getMessage());
}

$httpCode = is_readable($codeFile) ? trim((string)file_get_contents($codeFile)) : '';
$body = is_readable($respFile) ? (string)file_get_contents($respFile) : '';
$decoded = json_decode($body, true);
echo json_encode([
    'ok' => true,
    'blocked_detected' => true,
    'http_code' => $httpCode,
    'switch' => $switch,
    'apply' => ($decoded['apply'] ?? null),
    'raw' => substr($body, 0, 2000),
]) . "\n";
exit(0);
