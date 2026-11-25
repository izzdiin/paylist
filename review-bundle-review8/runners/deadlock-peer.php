#!/usr/bin/env php
<?php
// deadlock-peer.php — transaksi "servicechange" untuk run-review9b.sh
// (review #9 item (b): forced-deadlock test, 1213).
//
// Meniru persis urutan kunci v2 task/servicechange.php (deV2, dibaca
// read-only 25 Nov 2026): DALAM satu transaksi, UPDATE pelanggan dulu
// (mengunci baris pelanggan + mengubahnya) lalu UPDATE proforma
// (mengunci baris tagihan) — kebalikan dari urutan kunci apply
// (log -> tagihan -> pelanggan). Warganet tidak punya inversi ini
// (servicechange wg hanya menyentuh pelanggan), jadi untuk wg peer ini
// memakai bentuk yang sama sebagai SQL khusus-tes: UPDATE pelanggan
// lalu UPDATE payment (PK).
//
// Peer ini HANYA test-only SQL: tidak memanggil servicechange.php
// (butuh sesi admin + efek WA Fonnte) dan tidak mengubah kode apply
// atau servicechange. Nilai tagihan yang ditulis peer SAMA dengan nilai
// saat ini (dibaca runner, diteruskan sebagai argumen) agar nominal
// harapan apply tidak berubah dan apply ulang sesudah deadlock tetap
// bisa lunas; yang berubah hanya layanan (efek survivor yang terlihat).
//
// Koordinasi berkas (deterministik, tanpa sleep sebagai sinkronisasi):
//   1. BEGIN; UPDATE pelanggan (pegang kunci pelanggan, 1 baris berubah)
//      -> tulis ready-file {trx_id}.
//   2. Tunggu go-file (poll 100 ms, maks 30 dtk). Tanpa go-file:
//      rollback + keluar (tidak ada efek parsial).
//   3. UPDATE tagihan (terblokir pada kunci apply -> deadlock ->
//      korban 1213) -> bila berhasil, COMMIT.
//   --skip-invoice-lock: kontrol negatif — lewati langkah 3, langsung
//      COMMIT (membuktikan kunci pelanggan saja TIDAK menimbulkan
//      deadlock; apply harus lunas normal).
//
// Hasil ditulis ke result-file sebagai JSON:
//   {outcome: 'committed' | 'deadlock-victim' | 'error' | 'go-timeout',
//    errno, message, trx_id, ...}
// Keluar 0 bila result-file berhasil ditulis (putusan PASS/FAIL ada di
// runner, dari isi tabel + file ini); keluar 2 bila gagal total
// (tidak bisa konek / argumen hilang).
//
// Penggunaan (dipanggil stale-orchestrator.php mode=deadlock):
//   php deadlock-peer.php --target v2|wg --cnf PATH --db-name DB
//     --customer C --bill-id B --layanan L --tagihan T
//     --ready-file PATH --go-file PATH --result-file PATH
//     [--extra-customers C1,C2,...] [--skip-invoice-lock]

function peer_fail($msg, $code = 2) {
    fwrite(STDERR, "deadlock-peer: $msg\n");
    exit($code);
}

$opts = getopt('', [
    'target:', 'cnf:', 'db-name:', 'customer:', 'bill-id:',
    'layanan:', 'tagihan:', 'ready-file:', 'go-file:', 'result-file:',
    'extra-customers:', 'skip-invoice-lock',
]);
$target = $opts['target'] ?? '';
if (!in_array($target, ['v2', 'wg'], true)) { peer_fail('target harus v2|wg'); }
$cnf = $opts['cnf'] ?? '';
$dbName = $opts['db-name'] ?? '';
$customer = $opts['customer'] ?? '';
$billId = (int)($opts['bill-id'] ?? 0);
$layanan = $opts['layanan'] ?? '';
$tagihan = $opts['tagihan'] ?? '';
$readyFile = $opts['ready-file'] ?? '';
$goFile = $opts['go-file'] ?? '';
$resultFile = $opts['result-file'] ?? '';
$skipInvoice = isset($opts['skip-invoice-lock']);
if ($cnf === '' || !is_readable($cnf)) { peer_fail('cnf tidak bisa dibaca'); }
if ($dbName === '' || $customer === '' || $billId <= 0 || $layanan === ''
    || $tagihan === '' || $readyFile === '' || $goFile === '' || $resultFile === '') {
    peer_fail('argumen wajib hilang');
}
if ($target === 'v2') {
    $billTable = 'proforma';
} else {
    $billTable = 'payment';
}

// Kredensial dari file .cnf gaya MySQL ([client]) — sama seperti
// stale-orchestrator.php.
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
if ($cfg['user'] === null) { peer_fail('cnf tidak berisi user'); }

mysqli_report(MYSQLI_REPORT_ERROR | MYSQLI_REPORT_STRICT);

$result = ['outcome' => 'error', 'errno' => 0, 'message' => '', 'trx_id' => 0];
try {
    if (!empty($cfg['socket'])) {
        $db = new mysqli('localhost', $cfg['user'], (string)$cfg['password'], $dbName, null, $cfg['socket']);
    } else {
        $db = new mysqli($cfg['host'] ?: '127.0.0.1', $cfg['user'], (string)$cfg['password'], $dbName, (int)($cfg['port'] ?: 3306));
    }
    $db->set_charset('utf8mb4');

    // Bulan terbit proforma (v2: predikat servicechange memakai bulan
    // berjalan; baca dari baris target agar tidak bergantung pada
    // tanggal jalan tes).
    $billMonth = null;
    if ($target === 'v2') {
        $q = $db->prepare("SELECT DATE_FORMAT(tanggal_terbit, '%Y-%m') AS m FROM proforma WHERE id = ?");
        $q->bind_param('i', $billId);
        $q->execute();
        $r = $q->get_result()->fetch_assoc();
        $q->close();
        if (!$r) { throw new RuntimeException("proforma $billId tidak ditemukan"); }
        $billMonth = $r['m'];
    }

    // 1. Kunci pelanggan dulu (persis servicechange: UPDATE pelanggan).
    $db->begin_transaction();
    if ($target === 'v2') {
        $up = $db->prepare("UPDATE pelanggan SET layanan = ?, tagihan = ? WHERE id_pelanggan = ?");
        $up->bind_param('ssi', $layanan, $tagihan, $customer);
    } else {
        $up = $db->prepare("UPDATE pelanggan SET layanan = ?, tagihan = ? WHERE id_pelanggan = ?");
        $up->bind_param('sss', $layanan, $tagihan, $customer);
    }
    $up->execute();
    if ($up->affected_rows !== 1) {
        $up->close();
        throw new RuntimeException("UPDATE pelanggan $customer: affected_rows != 1");
    }
    $up->close();
    // Bobot korban (temuan review #9b, 25 Nov 2026): InnoDB memilih
    // korban deadlock berdasarkan BOBOT — jumlah kunci yang dipegang —
    // bukan jumlah baris yang diubah. Peer servicechange asli (1 baris
    // pelanggan) LEBIH RINGAN dari apply (log + tagihan = 2-3 kunci),
    // sehingga dalam tabrakan nyata PEER-lah yang jadi korban 1213
    // (apply lunas normal — pembayaran menang; perilaku ini
    // menguntungkan produksi dan dibuktikan kontrol awal). Untuk
    // MEMAKSA apply menjadi korban (kasus yang diminta review #9:
    // rollback apply + recoverable), peer sengaja dibuat LEBIH BERAT
    // dengan mengunci baris pelanggan dummy tambahan
    // (--extra-customers): tiap UPDATE menambah 1 kunci baris + 1
    // undo, sehingga bobot peer melampaui apply dan InnoDB memilih
    // apply sebagai korban. Urutan kunci peer tetap pelanggan dulu
    // lalu tagihan (inversi servicechange tidak berubah); hanya
    // bobotnya yang ditambah — didokumentasikan eksplisit, bukan
    // disembunyikan.
    $extraUpdated = 0;
    if (!empty($opts['extra-customers'])) {
        foreach (explode(',', (string)$opts['extra-customers']) as $xc) {
            $xc = trim($xc);
            if ($xc === '') { continue; }
            if ($target === 'v2') {
                $ux = $db->prepare("UPDATE pelanggan SET layanan = ?, tagihan = ? WHERE id_pelanggan = ?");
                $ux->bind_param('ssi', $layanan, $tagihan, $xc);
            } else {
                $ux = $db->prepare("UPDATE pelanggan SET layanan = ?, tagihan = ? WHERE id_pelanggan = ?");
                $ux->bind_param('sss', $layanan, $tagihan, $xc);
            }
            $ux->execute();
            if ($ux->affected_rows !== 1) {
                $ux->close();
                throw new RuntimeException("UPDATE pelanggan dummy $xc: affected_rows != 1");
            }
            $ux->close();
            $extraUpdated++;
        }
    }
    $result['extra_updated'] = $extraUpdated;
    $trxRow = $db->query("SELECT trx_id FROM INFORMATION_SCHEMA.INNODB_TRX WHERE trx_mysql_thread_id = CONNECTION_ID()")->fetch_assoc();
    $trxId = $trxRow ? (int)$trxRow['trx_id'] : 0;
    $result['trx_id'] = $trxId;
    file_put_contents($readyFile, json_encode(['ready' => true, 'trx_id' => $trxId]) . "\n");

    // 2. Tunggu go-file (apply sudah TERBUKTI menunggu kunci kita).
    $deadline = microtime(true) + 30.0;
    while (!file_exists($goFile)) {
        if (microtime(true) > $deadline) {
            $db->rollback();
            $result['outcome'] = 'go-timeout';
            $result['message'] = 'go-file tidak muncul dalam 30 dtk';
            file_put_contents($resultFile, json_encode($result) . "\n");
            exit(0);
        }
        usleep(100000);
    }

    // 3. Kunci tagihan kedua (persis servicechange untuk v2; PK untuk wg).
    if (!$skipInvoice) {
        if ($target === 'v2') {
            $up2 = $db->prepare("UPDATE proforma SET layanan = ?, tagihan = ? WHERE id_pelanggan = ? AND DATE_FORMAT(tanggal_terbit, '%Y-%m') = ?");
            $up2->bind_param('ssis', $layanan, $tagihan, $customer, $billMonth);
        } else {
            $up2 = $db->prepare("UPDATE payment SET layanan = ?, tagihan = ? WHERE id = ?");
            $up2->bind_param('ssi', $layanan, $tagihan, $billId);
        }
        $up2->execute();
        if ($up2->affected_rows !== 1) {
            $up2->close();
            throw new RuntimeException("UPDATE $billTable: affected_rows != 1");
        }
        $up2->close();
        $result['bill_updated'] = true;
    }

    $db->commit();
    $result['outcome'] = 'committed';
    $result['message'] = $skipInvoice ? 'committed tanpa kunci tagihan (kontrol)' : 'committed sesudah deadlock';
    file_put_contents($resultFile, json_encode($result) . "\n");
    exit(0);
} catch (mysqli_sql_exception $e) {
    if (isset($db) && $db instanceof mysqli) {
        try { $db->rollback(); } catch (Throwable $e2) { /* abaikan */ }
    }
    $result['outcome'] = ($e->getCode() === 1213) ? 'deadlock-victim' : 'error';
    $result['errno'] = $e->getCode();
    $result['message'] = $e->getMessage();
    file_put_contents($resultFile, json_encode($result) . "\n");
    exit(0);
} catch (Throwable $e) {
    if (isset($db) && $db instanceof mysqli) {
        try { $db->rollback(); } catch (Throwable $e2) { /* abaikan */ }
    }
    $result['outcome'] = 'error';
    $result['message'] = $e->getMessage();
    file_put_contents($resultFile, json_encode($result) . "\n");
    exit(0);
}
