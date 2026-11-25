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
//      sehingga nominal harapan tidak lagi cocok; mode=lockwait: tidak
//      mengubah apa-apa, hanya menahan kunci melewati batas timeout
//      apply untuk memicu 1205), lalu COMMIT.
//   4. Apply melanjutkan dengan binding segar di bawah kunci — dan
//      harus MENOLAK (perbaikan review #8) atau terbukti melunasi
//      tagihan basi (kode pra-perbaikan).
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
//     --log-id N --probe-url URL --mode bill|nominal|lockwait
//     --resp-file PATH --code-file PATH
//     [--switch-bill M --switch-customer C]   (mode=bill)
//     [--bill-id B --switch-total T]          (mode=nominal)
//     [--hold-seconds S]                      (mode=lockwait)
//     [--probe-lock-wait-timeout N]           (lock_wait_timeout sesi apply)

function fail($msg, $code = 2) {
    fwrite(STDERR, "stale-orchestrator: $msg\n");
    echo json_encode(['ok' => false, 'error' => $msg]) . "\n";
    exit($code);
}

$opts = getopt('', [
    'target:', 'cnf:', 'db-name:', 'log-id:', 'probe-url:', 'mode:',
    'resp-file:', 'code-file:', 'switch-bill:', 'switch-customer:',
    'bill-id:', 'switch-total:', 'hold-seconds:',
    'probe-lock-wait-timeout:',
]);
$target = $opts['target'] ?? '';
if (!in_array($target, ['v2', 'wg'], true)) { fail('target harus v2|wg'); }
$mode = $opts['mode'] ?? '';
if (!in_array($mode, ['bill', 'nominal', 'lockwait'], true)) { fail('mode harus bill|nominal|lockwait'); }
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
