# Review #9 item (b) — Forced-deadlock test (ER 1213) — NOTES

**Tugas:** item (b) review #9 (PR #4, review 6049617576): "Zero observed 1213 is not a
forced-deadlock test; demonstrate rollback and recoverable state." Hanya item (b);
item (c)–(f) TIDAK disentuh; kode apply dari item (a) TIDAK diubah.

**Status: SELESAI — suite review9b hijau penuh 127/127** (run
`run-review9b-20261125-105541.log`, 25 Nov 2026 10:55 WIB). Full suite lain hijau
(lihat tabel di bawah).

## 1. Desain skenario (deterministik, tanpa sleep)

Dua transaksi merebut baris yang sama dengan urutan kunci BERLAWANAN:

| | Transaksi 1 — apply (korban) | Transaksi 2 — peer (survivor) |
|---|---|---|
| Urutan kunci | log → tagihan → pelanggan | pelanggan → tagihan |
| Bentuk | fungsi apply ASLI via probe-apply.php (`qris_apply_payment` / `paylist_apply_payment`) | SQL khusus-tes meniru `task/servicechange.php` v2 (UPDATE pelanggan dulu, lalu UPDATE proforma); untuk wg bentuk sama karena servicechange wg hanya menyentuh pelanggan |
| Proses | worker PHP (port 8081 v2 / 8091 wg) | `deadlock-peer.php` (proses terpisah) |

Orkestrasi (`stale-orchestrator.php`, `mode=deadlock`) — sinkronisasi lewat kunci
baris + polling `INNODB_LOCK_WAITS`, BUKAN timing/sleep:
1. T0 mengunci baris log; peer memegang kunci pelanggan (ready-file + trx_id).
2. Apply dijalankan via probe dan TERBUKTI menunggu kunci log T0 (`blocked_detected`).
3. T0 commit; apply maju (log, tagihan) lalu TERBUKTI menunggu kunci pelanggan peer
   (`apply_waited_on_peer`).
4. Go-file dilepas; peer meminta kunci tagihan → siklus lengkap → InnoDB mendeteksi
   deadlock → korban mendapat 1213.

Skenario per target (v2 + wg), 6 skenario total:
- `f9d_probe_sanity` — kontrol positif: apply langsung tanpa gangguan → lunas normal,
  poin 10 (UNPAID).
- `f9d_lockwait_control` — kontrol negatif: peer memegang kunci pelanggan tapi TIDAK
  meminta kunci tagihan → apply hanya menunggu, lalu lunas normal, TANPA deadlock
  (membuktikan deadlock berasal dari langkah kunci tagihan kedua, bukan kunci
  pelanggan saja).
- `f9d_deadlock` — apply korban 1213 → `applied=false`, rollback penuh, peer
  survivor commit, lalu apply ulang atas log yang sama BERHASIL (recoverable).

## 2. Berkas

Baru / diubah (salinan lokal di `~/workspace/c2-impl/review9-deadlock/new/`):
- `deadlock-peer.php` — peer test-only (mode deadlock + `--skip-invoice-lock` untuk
  kontrol + `--extra-customers` untuk bobot; lihat §3.2).
- `run-review9b.sh` — runner suite baru (konvensi runner lain; log ke
  `/home/warganet/paylist-contract-c2/results/`).
- `stale-orchestrator.php` — cabang baru `mode=deadlock` (di `~/workspace/c2-impl/harness/`).

Deploy ke laptopgrt2 (`/home/warganet/c2-harness/tests/server-contract/`):
25 Nov 2026 10:10 WIB (backup `stale-orchestrator.php.bak-review9-deadlock`),
10:40 WIB (perbaikan anomali §3.1), 10:44 WIB (bobot peer), 10:54 WIB (perbaikan
asersi §3.3–3.4). `php -l` bersih untuk kedua berkas PHP; `bash -n` OK untuk runner.
TIDAK ada deploy ke deV2/produksi; TIDAK ada perubahan pada `servicechange.php`
atau kode apply; TIDAK ada aktivitas GitHub.

## 3. Temuan empiris

### 3.1 Anomali INNODB_TRX sebelum spawn peer (10:11 WIB)
Run pertama gagal di orkestrasi: peer ready tapi `trx_id=0`. Penyebab: lookup
`INNODB_TRX` milik orkestrator sendiri SEBELUM spawn peer berkorelasi 4/4 dengan
lookup `INNODB_TRX` peer yang mengembalikan 0 baris (A/B test 03:36 UTC: dengan
query itu FAIL 2/2; tanpa itu PASS 2/2). Mekanisme tidak terjelaskan (query I_S
read-only). Perbaikan: lookup trx milik sendiri digate `mode !== 'deadlock'`;
cabang deadlock me-query ulang trx_id SETELAH peer ready. Didokumentasikan di
komentar kode.

### 3.2 Korban dipilih berdasarkan BOBOT kunci, bukan baris diubah (10:40 WIB)
Run `run-review9b-20261125-104023` (PASS=82 FAIL=33): deadlock TERPAKSA dengan
sukses, tapi korbannya PEER (peer `outcome='deadlock-victim'`, errno 1213; apply
`applied=true`, lunas normal). Bagian InnoDB: peer "4 lock struct(s), 2 row
lock(s), undo log entries 1"; apply "6 lock struct(s), 3 row lock(s)". InnoDB
memilih transaksi yang LEBIH RINGAN sebagai korban.
**Implikasi produksi (baik):** dalam tabrakan nyata, servicechange asli (1 kunci
pelanggan) lebih ringan dari apply (kunci log + tagihan) → servicechange-lah yang
jadi korban 1213 dan pembayaran menang.
**Perbaikan tes:** peer diberi bobot tambahan `--extra-customers` (4 pelanggan
dummy 900002–900005 ikut di-UPDATE sebelum go: peer jadi "7 row lock(s), undo
log entries 5" vs apply 3 row lock) — urutan kunci peer tetap pelanggan dulu
lalu tagihan. Setelah itu apply menjadi korban di kedua target (run hijau).

### 3.3 "(N)" di "WE ROLL BACK TRANSACTION (N)" — KOREKSI: bukan jumlah, bukan indeks korban
**Klaim awal di bagian ini SALAH dan dikoreksi 25 Nov 2026 11:42 WIB
(tindak lanjut item (c); lihat NOTES item (c) §6.6–6.7).** Run
`run-review9b-20261125-104433` (PASS=121 FAIL=4) menunjukkan `***
WE ROLL BACK TRANSACTION (1)` sementara korban yang TERBUKTI adalah apply —
transaksi (2) di daftar (`(1)` = peer, yang committed; `(2)` = apply, yang
di-rollback). Dari run itu saja disimpulkan angka itu = jumlah transaksi
yang di-rollback (1), BUKAN indeks korban — kesimpulan itu TERBUKTI SALAH
di MariaDB 10.11.19: untuk deadlock yang SAMA persis (peer 7 row lock
terdaftar (1), apply 3 row lock terdaftar (2), korban TERBUKTI apply di
ketiga versi), 10.11 menulis `(2)` padahal hanya SATU transaksi yang
di-rollback (peer TERBUKTI committed). Jadi `(N)` TIDAK konsisten
memetakan ke korban (11.8.6 menulis `(1)` untuk korban `(2)`; 10.11
menulis `(2)` untuk korban `(2)`) dan TIDAK konsisten memetakan ke jumlah
yang di-rollback (10.11 me-rollback satu transaksi tapi menulis `(2)`).
Angka ini artefak diagnostik yang pemetaannya berubah antar versi
(cf. MDEV-37845, 10.6.19). Asersi diperbaiki 25 Nov 11:42–11:44 WIB:
pola dilonggarkan ke `WE ROLL BACK TRANSACTION \([0-9]+\)` (satu-satunya
perubahan, di `run-review9b.sh`); rerun review9b hijau 127/127 di KETIGA
versi — 11.8.6 `run-review9b-20261125-114407.log`, 10.5.29
`mariadb105-run-review9b-20261125-114343.log`, 10.11.19
`mariadb1011-run-review9b-20261125-114358.log`. Korban tetap
diidentifikasi dari teks apply sendiri + peer committed + cek rollback
baris-per-baris + re-apply yang berhasil, bukan nomor baris InnoDB.

### 3.4 mysqli melempar exception secara default di PHP 8.4.27 (bukan mode senyap)
Asumsi awal (mode senyap → warning PHP ke log worker) SALAH. Fakta: tidak ada
`mysqli_report` dan tidak ada `set_error_handler` di jalur apply mana pun
(grep seluruh docroot scratch + endpoint-src); worker `php -S` polos (hanya
`-d session.save_path`). Tes CLI mandiri 25 Nov 2026 membuktikan: `query()` dan
`prepare()` pada PHP 8.4.27 ini MELEMPAR `mysqli_sql_exception` tanpa
`mysqli_report` apa pun. Akibatnya: `execute()` pada kunci pelanggan yang
deadlock melempar `mysqli_sql_exception('Deadlock found when trying to get
lock; try restarting transaction')`, catch apply mencatatnya, rollback terjadi,
dan TIDAK ada warning PHP ke log worker (jumlah "Deadlock found" di log worker
tetap 0 — dicatat sebagai note, bukan asersi). Pesan 1213 justru sampai ke
respons apply sendiri — bukti yang lebih langsung dari log worker.

### 3.5 Format desimal tagihan
`tagihan` di DB bertipe desimal: nilai aktual `100000.00`, bukan `100000`.
Asersi diperbaiki dengan `CAST(tagihan AS SIGNED)`.

## 4. Hasil — suite review9b (run hijau 20261125-105541)

Log: `/home/warganet/paylist-contract-c2/results/run-review9b-20261125-105541.log`
— **PASS=127 FAIL=0**, `RESULT: ALL SCENARIOS BEHAVED AS SPECIFIED`, RUNNER-RC=0.
Actor responses: `/home/warganet/paylist-contract-c2/results/run-review9b-20261125-105541/`.

| Skenario | Hasil |
|---|---|
| F9D-probe-sanity [v2] | PASS |
| F9D-control [v2] | PASS (apply menunggu, lunas; nol 1213 baru) |
| F9D-deadlock [v2] | PASS (korban 1213 + rollback penuh + survivor commit + re-apply berhasil) |
| F9D-probe-sanity [wg] | PASS |
| F9D-control [wg] | PASS |
| F9D-deadlock [wg] | PASS |

## 5. Bukti 1213 + rollback + recoverability (nilai teramati, run hijau)

### (i) Korban mendapat 1213
- v2 apply: `applied=false`, steps `['GAGAL transaksi, rollback: Deadlock found
  when trying to get lock; try restarting transaction']` (log_id 726).
- wg apply: `applied=false`, note `'gagal: Deadlock found when trying to get lock;
  try restarting transaction'` (log_id 705).
- InnoDB `LATEST DETECTED DEADLOCK` (v2 10:44:38, wg 10:44:46 pada run 104433;
  run hijau menangkap bagian setara): transaksi (1) peer menunggu kunci tagihan
  (`UPDATE proforma/payment SET layanan`), transaksi (2) apply menunggu kunci
  pelanggan (`SELECT ... FROM pelanggan WHERE id_pelanggan = ? FOR UPDATE`),
  `WE ROLL BACK TRANSACTION (1)` (teks harfiah pada run 11.8.6 ini; angka
  `(N)` tidak ditafsir sebagai jumlah maupun indeks korban — lihat §3.3).
- Peer survivor: `outcome='committed'`, `errno=0`, `extra_updated=4`,
  `bill_updated=true` (kedua target).

### (ii) Korban rollback PENUH (state pasca-deadlock, sebelum re-apply)
Nilai teramati identik di v2 dan wg (customer 900001, bill v2 910001 / wg 920001,
log v2 726 / wg 705):
- tagihan tetap UNPAID; `pemasukan` 0 baris.
- pelanggan: `status_pembayaran=UNPAID`, `masa_aktif=0`, `poin=0`.
- log: `match_status=matched`, `matched_bill` tetap (910001 / 920001),
  `applied_at` NULL, `apply_note` NULL (tidak ada catatan gagal yang ditulis).
- wg: `report` 0 baris (tidak ada WA terkirim oleh korban).
- Efek survivor HANYA dari peer: pelanggan + tagihan `layanan='Test 20Mbps'`
  (dari `Test 10Mbps`), `tagihan` tetap 100000; 4 dummy `layanan='Test 20Mbps'`
  dengan status/masa/poin utuh (UNPAID/0/0).

### (iii) Recoverable — apply ulang atas log yang sama BERHASIL
- v2 re-apply: `{"ok":true,"apply":{"applied":true,"note":"lunas via Paylist
  (proforma 910001, idtrx PRO-TEST-910001)"}}` → tagihan PAID, pemasukan 1,
  `applied_at` terisi, pelanggan PAID / masa 30 / poin 10.
- wg re-apply: `{"ok":true,"apply":{"applied":true,"note":"lunas via Paylist
  (payment 920001, idtrx TRX-TEST-920001)"}}` → payment PAID, pemasukan 1,
  pelanggan PAID / masa 30 / poin 10, `report` 1 baris.

## 6. Full suite (sesudah review9b hijau)

| Suite | Perintah | Hasil | Log |
|---|---|---|---|
| contract (305) | `SCRATCH_DIR=/home/warganet/paylist-contract-c2 ./run-contract.sh` | PASS=305 FAIL=0 | `run-20261125-105719.log` |
| review7 (202) | `./run-review7.sh` | PASS=202 FAIL=0 | `run-review7-20261125-105835.log` |
| review8 (121) | `./run-review8.sh` | PASS=121 FAIL=0 | `run-review8-20261125-105917.log` |
| birth (19) | `/home/warganet/birth-fix/run-birth.sh` | PASS=19 FAIL=0 | `run-birth-20261125-110456.log` |
| review9a (45) | `./run-review9a.sh` | PASS=45 FAIL=0 | `run-review9a-20261125-105944.log` |
| review9b (127) | `./run-review9b.sh` | PASS=127 FAIL=0 | `run-review9b-20261125-105951.log` (juga 105541, 127/127) |

Semua log di `/home/warganet/paylist-contract-c2/results/`. **Total: 819 asersi,
0 gagal.** Catatan: runner birth TIDAK ada di `tests/server-contract/` (di
situlah `./run-birth.sh` gagal "No such file"); runner birth yang benar ada di
`/home/warganet/birth-fix/run-birth.sh` (men-deploy ulang halaman fixed-nya
sendiri ke scratch lalu menguji; PASS=19 FAIL=0 pada 11:04 WIB). Default
`SCRATCH_DIR` run-contract.sh masih menunjuk instance mati
`/home/warganet/paylist-contract` — dijalankan dengan override; default TIDAK
diubah, di luar cakupan.

Pemeriksaan 1020/1213 tak terduga: **0** di kelima log suite lain
(contract, review7, review8, birth, review9a — masing-masing grep
`1213|1020` = 0). Log review9b mengandung 14 kemunculan `1213|1020` — semuanya
yang dipaksa dan diasersikan di skenario deadlock (satu-satunya baris "FAIL"
di log itu adalah ringkasan `PASS=127 FAIL=0`).

## 7. Yang TIDAK disentuh / tidak terverifikasi
- TIDAK disentuh: file/kode produksi (stbjkt v2New, laptopgrt warganet), deV2,
  `servicechange.php`, kode apply, item review #9 (c)–(f), default `SCRATCH_DIR`.
- Tidak ada posting/komentar/push GitHub, tidak ada merge.
- Mekanisme anomali §3.1 (INNODB_TRX) tetap tidak terjelaskan — hanya korelasi
  A/B 4/4 yang didokumentasikan; perbaikan bekerja dan suite hijau.
- Perilaku mysqli §3.4 dibuktikan empiris pada PHP 8.4.27 di box ini; apakah
  itu default PHP 8.4 secara umum atau spesifik build Debian tidak diselidiki
  lebih jauh — tidak memengaruhi asersi.
