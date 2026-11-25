# P1 points race (review #9 item a) — catatan kerja (25 Nov 2026)

Tindak lanjut review #9 ChatGPT (review 6049617576, PR #4), item (a)
saja. Item (b)–(f) TIDAK disentuh. Tidak ada posting ke GitHub. Tidak
ada merge. Force-match TIDAK diimplementasikan. Produksi TIDAK
disentuh (sha terverifikasi sebelum + sesudah).

## 1. Celah

Di jalur apply C2, status pelanggan dibaca TANPA kunci sebelum
transaksi dan dipakai untuk memberi poin (UNPAID->10, ISOLIR->5,
SUSPEND->1 — poin mengikuti status SEBELUM pembayaran). Perubahan
status di tengah apply (mis. cron membalik UNPAID -> ISOLIR saat
masa_aktif habis) membuat apply memberi poin dari status BASI.
Ini celah yang dicatat sebagai "di luar cakupan P1" di NOTES review #8
§9 ("poin memakai status lama (selisih poin bila status berubah di
antara seleksi dan kunci — bukan salah arah uang)").

Bukti celah (kontrol negatif, §5): apply memberi poin 10 padahal status
sudah ISOLIR saat pelunasan (seharusnya 5).

## 2. Perbaikan (2 situs apply; top-up TIDAK berubah)

Prinsip: kunci baris pelanggan dan baca ulang status di bawah kuncinya
sebelum poin diturunkan. Poin mengikuti status pada saat pelunasan
(di bawah kunci, sebelum UPDATE mengubahnya ke PAID).

- **v2New `qris_apply_payment`** (`bayarQR/qris_apply.php`, 474 -> 522
  baris): sesudah penolakan binding basi + adopsi nilai segar proforma,
  kunci baris pelanggan (`SELECT status_pembayaran, masa_aktif ...
  FOR UPDATE`), baca ulang status -> `$statusLama` dan masa_aktif ->
  hitung ulang `$masaAktifBaru`/`$tanggalExpired` dari masa segar
  (agar jatuhTempo konsisten dengan masa_aktif sesudah UPDATE relatif
  +30; sebelumnya jatuhTempo diturunkan dari masa pra-kunci).
  Penugasan pra-kunci `$statusLama`/`$masaAktifLama` dan perhitungan
  `$masaAktifBaru`/`$tanggalExpired` pra-kunci DIHAPUS (tidak ada lagi
  nilai basi yang bisa terpakai). Status PAID tidak ditolak: pelanggan
  bisa sah melunasi proforma kedua selagi statusnya sudah PAID dari
  proforma pertama — `beriPoinPembayaran` mengembalikan 0 untuk PAID.
  Keputusan kick MikroTik (ISOLIR/SUSPEND) kini memakai status segar.
- **Warganet `paylist_apply_payment`** (`paylist/paylist_apply.php`,
  486 -> 521 baris): sesudah adopsi nilai segar payment, kunci baris
  pelanggan (`SELECT status_pembayaran ... FOR UPDATE`) lalu lookup
  ulang poin dari `poin_status` dengan status segar. Lookup poin
  pra-kunci DIHAPUS. Status PAID -> tanpa baris poin_status -> poin 0.
- **Warganet `paylist_apply_topup`: TIDAK berubah.** Diverifikasi:
  tidak ada lookup poin / UPDATE poin / sentuhan tabel pelanggan di
  cabang top-up (grep `poin` = nol; `pelanggan` hanya nama kolom
  `id_pelanggan` pada baris payment = no HP mitra). Top-up tidak
  memberi poin.

Tanpa perubahan skema (tanpa migrasi). Revalidasi binding basi
review #8 tetap utuh (tidak ada barisnya yang diubah).

### Urutan kunci (diverifikasi dari kode)

| Entrypoint | Kunci yang dipegang bersamaan | Urutan |
|---|---|---|
| apply v2 `qris_apply_payment` | log -> proforma -> pelanggan | log dulu, lalu tagihan, lalu pelanggan |
| apply wg `paylist_apply_payment` | log -> payment -> pelanggan -> mitra (bonus) | sama |
| apply wg `paylist_apply_topup` | log -> payment -> mitra | tidak berubah (tanpa pelanggan) |
| re-match `qris_rematch_log` / `paylist_rematch_log` | log saja | — |
| matcher notify | tidak ada (autocommit) | — |
| hook kode-birth | log saja (via re-match) | — |
| klaim kode-birth | tagihan saja (autocommit) | — |
| cron status (v2 `cron/cronetter.php`, wg `cron/status.php`) | pelanggan saja (autocommit per pernyataan) | — |
| finance/in.php, cust/in.php (v2); payment/status-handler.php (wg) | autocommit per pernyataan (proforma/payment -> pelanggan) | — |
| finance/delete_income.php (v2) | proforma saja dalam transaksi | — |
| task/extend.php (v2+wg), wa/autoreply.php (wg) | pelanggan saja dalam transaksi | — |
| regist/* (v2+wg) | pelanggan baru (INSERT) | — |

Satu-satunya inversi yang diketahui: **v2 `task/servicechange.php`**
mengunci pelanggan dulu lalu proforma dalam satu transaksi
(pelanggan -> proforma) — INVERTE apply. Inversi ini SUDAH ADA
sebelum perbaikan ini (apply review #8 sudah mengunci pelanggan lewat
UPDATE-nya sambil memegang kunci proforma); perbaikan ini TIDAK
mengubah urutan kunci, hanya memajukan akuisisi kunci pelanggan di
dalam transaksi yang sama. Tabrakan (operator mengubah layanan
pelanggan yang sama persis saat QRIS-nya dilunasi) berakhir 1213 ->
rollback -> applied=false, baris tetap matched + bisa dicocokkan
ulang (jalur yang sama dengan 1205, sudah ada). `servicechange.php`
TIDAK diubah (di luar cakupan item a). Warganet tidak punya inversi
ini (`task/servicechange.php` wg hanya menyentuh pelanggan).

## 3. Tes regresi deterministik (baru)

Runner baru `run-review9a.sh` + mode baru `status` di
`stale-orchestrator.php` (mode=bill/nominal/lockwait tidak berubah).
Orkestrator mengunci baris log, menjalankan apply lewat probe sampai
TERBUKTI menunggu kunci itu (poll `INNODB_LOCK_WAITS`), lalu
membalik status pelanggan UNPAID -> ISOLIR (UPDATE yang sama dengan
cron status) dan commit — lalu apply melanjutkan. Urutan program
menjamin baca pra-kunci apply (UNPAID) selesai SEBELUM switch dan
baca di bawah kunci pelanggan SESUDAH switch: deterministik, tanpa
timing/sleep.

Skenario (4 verdict, 45 asersi):

| Skenario | Target | Isi |
|---|---|---|
| f9_probe_sanity | v2+wg | Kontrol positif: apply langsung tanpa gangguan -> lunas normal, poin 10 (UNPAID) |
| f9_points_race | v2+wg | Status dibalik UNPAID -> ISOLIR di tengah apply -> apply MELUNASI dengan poin 5 (status segar), bukan 10 |

Hasil:

| Run | Hasil | Log |
|---|---|---|
| Kontrol negatif, kode review #8 (pra-perbaikan) | 42 PASS, **3 FAIL sesuai desain**: poin 10, bukan 5 (v2+wg); v2 menyebut "status lama UNPAID" | `results/run-review9a-20261125-092646.log` |
| Pasca-perbaikan, ronde 1 | **45 PASS, 0 FAIL** | `results/run-review9a-20261125-092948.log` |
| Pasca-perbaikan, ronde 2 (sesudah rebuild docroot oleh run-contract S3) | **45 PASS, 0 FAIL** | `results/run-review9a-20261125-094451.log` |

## 4. Regresi penuh pasca-perbaikan

| Suite | Hasil | Log |
|---|---|---|
| run-review9a.sh (baru) | **45 PASS, 0 FAIL** | `results/run-review9a-20261125-094451.log` |
| run-review8.sh | **121 PASS, 0 FAIL** | `results/run-review8-20261125-093250.log` |
| run-contract.sh | **305 PASS, 0 FAIL** | `results/run-20261125-094200.log` |
| run-review7.sh | **202 PASS, 0 FAIL** | `results/run-review7-20261125-093318.log` |
| run-birth.sh `--pages fixed` | **19 PASS, 0 FAIL** | `results/run-birth-20261125-093359.log` |

Total 692 asersi hijau. Urutan jalan: review9a -> review8 ->
contract (retry, lihat §6) -> review7 -> birth -> review9a (final).
Nol kemunculan 1020/1213/deadlock di log contract; nol fatal baru di
log worker PHP.

## 5. Deploy + verifikasi deV2 (25 Nov 09:46)

Hanya `bayarQR/qris_apply.php` (warganet tidak punya deV2; perbaikan
paylist_apply.php terverifikasi harness saja).

- Backup: `qris_apply.php.bak-review9-points` (versi review #8,
  25294 byte).
- Sha sebelum terverifikasi (`99a99e48…`, versi review #8); sha
  sesudah cocok (`aecf9db6…`). Owner www-data:www-data, chmod 644.
- `php -l` bersih di PHP 7.4.33 stbjkt (juga 8.4.27 laptopgrt2 dan
  8.3.6 laptopgrt untuk file wg — tanpa sintaks PHP 8).
- Jumlah baris: proforma 256 -> 256, qris_notif_log 1490 -> 1490
  (deploy tidak mengubah baris).
- Smoke: GET notify 405, GET qris_rematch 405, POST rematch tanpa
  sesi 401.
- Skrip deploy: `deploy-dev2-review9a.sh`.
- Uji fungsional live di deV2 TIDAK dilakukan (butuh sesi admin atau
  secret perangkat — tidak dipalsukan; sama seperti deploy apply
  review #7/#8 yang hanya sha + smoke). Bukti fungsional adalah
  harness (45 tes atas fungsi apply yang sama persis, sha
  terverifikasi).

## 6. Temuan harness di luar item (a) — TIDAK diubah, dicatat saja

`run-contract.sh` gagal dua kali di prakondisi ("scratch MariaDB not
reachable") BUKAN karena DB mati: default `SCRATCH_DIR`-nya masih
`/home/warganet/paylist-contract` (instance lama yang sudah mati),
sementara runner lain (review7/8/9a, birth) default ke
`/home/warganet/paylist-contract-c2` (instance hidup :3307). Jalan
dengan `SCRATCH_DIR=/home/warganet/paylist-contract-c2 ./run-contract.sh`
-> 305 PASS. Log 24 Nov membuktikan pemanggilan yang sama (log ada di
`-c2/results/`). Default usang ini tidak diperbaiki di sini (di luar
cakupan item a) — diserahkan ke parent/orchestrator.

## 7. Produksi TIDAK tersentuh (bukti)

- v2New produksi (`/usr/local/app/v2New/html/bayarQR/qris_apply.php`):
  sha `a35e11ae…` sebelum dan sesudah pekerjaan (cocok).
- Warganet produksi
  (`/usr/local/app/app-warganet/html/paylist/paylist_apply.php`):
  sha `dfebb8db…` sebelum dan sesudah pekerjaan (cocok).
- Tidak ada tulis ke path produksi mana pun; satu-satunya tulis di
  laptopgrt adalah file harness + probe /tmp (dihapus sesudah jalan);
  satu-satunya tulis di stbjkt adalah deV2 + stage /tmp.

## 8. File yang berubah (untuk regenerasi bundle)

Produksi (patch set):

| File | review8 -> review9a | Prod -> review9a |
|---|---|---|
| v2New `bayarQR/qris_apply.php` | +48 baris (474 -> 522) | lihat `diffs/v2-qris-apply.prod-to-review9a.diff` |
| warganet `paylist/paylist_apply.php` | +35 baris (486 -> 521) | lihat `diffs/wg-paylist-apply.prod-to-review9a.diff` |

Diff lengkap: `diffs/*.review8-to-review9a.diff` dan
`diffs/*.prod-to-review9a.diff`; file utuh: `fixed/` (baru) dan
`orig/` (versi review #8). Tanpa perubahan skema (tanpa migrasi DB).
Bundle review #8 (`review8-stale/diffs/`) kini tertinggal untuk kedua
file apply ini — regenerasi bundle menunggu parent/orchestrator (di
luar cakupan tugas ini).

Harness (bukan kode produksi):

| File | Perubahan |
|---|---|
| `tests/server-contract/stale-orchestrator.php` | + mode `status` (backup `.bak-review9-points` di samping file asli) |
| `tests/server-contract/run-review9a.sh` | baru (runner 4 skenario) |
| `endpoint-src/bayarQR/qris_apply.php` + `endpoint-src-wg/paylist/paylist_apply.php` | versi perbaikan (backup `.bak-review9-points` di samping file asli; juga disalin ke `html-v2`/`html-wg` — sha sama di keempat salinan) |

Salinan workspace harness yang sinkron: `~/workspace/c2-impl/harness/`
(stale-orchestrator.php, run-review9a.sh). Catatan kerja ini:
`~/workspace/c2-impl/review9-points/NOTES.md`.

## 9. Yang tersisa / batasan yang diketahui

- Deploy produksi (v2New + warganet) — butuh persetujuan owner +
  komentar reviewer (re-review #9 belum memberi lampu hijau
  produksi). Bundle diff terbaru ada di `diffs/`; tanpa migrasi DB.
- Addendum desain §13.12 (points race + urutan kunci log -> tagihan
  -> pelanggan + inversi servicechange) — di luar cakupan tugas ini;
  parent menyiapkan draf balasan review #9.
- Default `SCRATCH_DIR` usang di `run-contract.sh` (§6) — tidak
  diperbaiki di sini.
- Uji fungsional live di deV2 tidak dilakukan (tanpa sesi admin /
  secret perangkat); bukti fungsional adalah harness.
