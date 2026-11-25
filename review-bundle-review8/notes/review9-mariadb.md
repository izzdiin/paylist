# Review #9 item (c) — Contract suites on REAL MariaDB 10.5 and 10.11 — NOTES

Tindak lanjut review #9 ChatGPT (review 6049617576, PR #4), item (c) saja:
"run contract on actual MariaDB 10.5/10.11 versions rather than infer
equivalence from 11.8." Item (a), (b), (d)–(f) TIDAK disentuh. Tidak ada
perubahan kode apply, tidak ada perubahan asersi tes, tidak ada perubahan
default `SCRATCH_DIR`, tidak ada deploy, tidak ada posting GitHub.
Produksi TIDAK disentuh.

**Status: SELESAI — 819/819 asersi hijau di MariaDB 10.5; 817/819 di
MariaDB 10.11 pada run awal (2 FAIL, keduanya asersi teks diagnostik yang sama —
lihat §6.6; perilaku deadlock yang dibuktikan identik di ketiga versi).
TINDAK LANJUT 25 Nov 2026 11:42–11:44 WIB: asersi `(1)` dilonggarkan ke
`WE ROLL BACK TRANSACTION \([0-9]+\)` (satu-satunya perubahan, lihat §6.7);
rerun review9b hijau 127/127 di KETIGA versi → 819/819 di semua versi.**

| Suite | 11.8.6 baseline | MariaDB 10.5.29 | MariaDB 10.11.19 |
|---|---|---|---|
| contract | 305/305 | 305/305 | 305/305 |
| review7 | 202/202 | 202/202 | 202/202 |
| review8 | 121/121 | 121/121 | 121/121 |
| birth | 19/19 | 19/19 | 19/19 |
| review9a | 45/45 | 45/45 | 45/45 |
| review9b | 127/127 | 127/127 | **127/127** (125/127 sebelum pelonggaran, §6.6) |
| **Total** | **819/819** | **819/819** | **819/819** |

## 1. Cara kerja (mengapa bukan setup-scratch.sh asli)

Satu-satunya pemanggil teardown/setup di semua runner adalah S3 di
`run-contract.sh` (restart seluruh stack lalu re-POST kunci yang sama).
Setup asli hanya bisa menjalankan mariadbd lokal (binari 11.8 di host) —
tidak ada jalan untuk menjalankan server 10.5/10.11 tanpa container.
Karena itu dibuat SALINAN harness di `/home/warganet/c2-harness-mariadb/`
dengan patch HANYA pada 3 berkas lifecycle/portabilitas; semua berkas
lain (runner, skema, migrasi, orkestrator, peer, probe) byte-identik
dengan aslinya (`diff -rq` hanya menemukan 3 berkas itu; sha harness
asli diverifikasi sama sebelum dan sesudah):

| Berkas (copy) | Patch |
|---|---|
| `setup-scratch.sh` | Bila `MYSQL_DOCKER_CONTAINER` diset: tulis `.cnf` TCP (host+port, `ssl=0`) bukan socket; `docker start` + ping-wait ganti start mariadbd lokal; lewati init datadir + first-time-init (DB diprovision terpisah). Jalur lokal asli utuh di cabang else. |
| `teardown-scratch.sh` | Bila `MYSQL_DOCKER_CONTAINER` diset: `docker stop` ganti `mysqladmin shutdown`, sehingga S3 me-restart container aslinya. Jalur lokal asli utuh di cabang else. |
| `run-review9b.sh` | `worker_log_of()` diturunkan dari `V2_HTTP_BASE`/`WG_HTTP_BASE` (dulu hardcode 8081/8091). Asersi sama (nol deadlock baru); tanpa patch, cek itu membaca file yang tak ada dan lolos vakum (0 -> 0). |

Runner dijalankan dengan env override saja (tanpa ubah default):
`SCRATCH_DIR=/home/warganet/paylist-contract-m105|m1011`,
`MYSQL_PORT=3308|3309`, `MYSQL_DOCKER_CONTAINER=mariadb-105|mariadb-1011`,
`V2_HTTP_BASE=8180|8280`, `WG_HTTP_BASE=8190|8290`.
`run-birth.sh` dipakai yang ASLI (`/home/warganet/birth-fix/run-birth.sh`,
`--pages fixed`) — ia tidak memanggil setup, jadi env override cukup.
Runner: `/home/warganet/review9-mariadb-patched/run-all-version.sh`
(urutan: contract, review7, review8, birth, review9a, review9b).

Skrip kerja (salinan lokal di `~/workspace/c2-impl/review9-mariadb/`):
`setup-harness-copy.sh`, `provision-version.sh`, `compare-schema.sh`,
`fix-contract-user.sh`, `run-all-version.sh`, `patched/` (3 berkas patch).

## 2. Server yang diuji (bukti versi)

| | MariaDB 10.5 | MariaDB 10.11 | Baseline |
|---|---|---|---|
| Image | `mariadb:10.5`, digest `sha256:a530aeeefd82f4fa5150f391b6c75462140904780338766f6b03acecb1cca3ce` | `mariadb:10.11`, digest `sha256:e5fe75b2b9de0eb9f6f9a4d9c97272fb3a7480e389ccd487a786bab2a12cc0d7` | lokal 11.8.6 |
| `SELECT VERSION()` | `10.5.29-MariaDB-ubu2004` | `10.11.19-MariaDB-ubu2204` | `11.8.6-MariaDB-0+deb13u1 from Debian` |
| Container | `mariadb-105` (id `d9df06d510b6…`), port `127.0.0.1:3308->3306`, datadir `/home/warganet/mariadb-10.5-data` | `mariadb-1011`, port `127.0.0.1:3309->3306`, datadir `/home/warganet/mariadb-10.11-data` | — |
| Opsi server | `--innodb-buffer-pool-size=64M --max-connections=32` (sama dengan scratch lokal) | sama | sama |

## 3. Skema: identik dengan 11.8.6

Perbandingan via INFORMATION_SCHEMA (kolom + indeks + engine) antara
tiap instance versi dan scratch 11.8.6: **identik**, kecuali dua tabel
`midtrans` dan `qris_statis` yang hanya ada di 11.8.6 — keduanya BUKAN
bagian skema, melainkan fixture yang dibuat `run-birth.sh` sendiri
(`CREATE TABLE IF NOT EXISTS` + baris dummy) saat suite birth jalan;
saat birth jalan di 10.5/10.11, tabel itu dibuat di sana juga.
Kolom audit C2 terverifikasi di kedua versi: `rematch_count`,
`last_rematch_at`, `rematched_by` di `qris_notif_log` (v2) dan
`paylist_log` (wg), plus `idx_amount` di keduanya.
File diff: `<scratch-dir>/schema-compare/schema.diff`.

## 4. Hasil per versi

### 4.1 MariaDB 10.5.29 — 819/819, 25 Nov 2026 11:21–11:23 WIB

Log (asli di `/home/warganet/paylist-contract-m105/results/`, salinan
berprefix di `/home/warganet/paylist-contract-c2/results/mariadb105-*`):

| Suite | Hasil | Log |
|---|---|---|
| contract | PASS=305 FAIL=0 | `run-20261125-112113.log` |
| review7 | PASS=202 FAIL=0 | `run-review7-20261125-112156.log` |
| review8 | PASS=121 FAIL=0 | `run-review8-20261125-112223.log` |
| birth | PASS=19 FAIL=0 | `run-birth-20261125-112242.log` |
| review9a | PASS=45 FAIL=0 | `run-review9a-20261125-112250.log` |
| review9b | PASS=127 FAIL=0 | `run-review9b-20261125-112253.log` |

S3 restart durability PASS di kedua target — dan restart itu NYATA:
container `mariadb-105` tercatat `StartedAt 2026-11-25T04:21:40Z`
(11:21 WIB, di tengah run contract), hasil `docker stop` + `docker
start` dari teardown/setup copy.

### 4.2 MariaDB 10.11.19 — 817/819, 25 Nov 2026 11:26–11:28 WIB

Log (asli di `/home/warganet/paylist-contract-m1011/results/`, salinan
berprefix di `/home/warganet/paylist-contract-c2/results/mariadb1011-*`):

| Suite | Hasil | Log |
|---|---|---|
| contract | PASS=305 FAIL=0 | `run-20261125-112627.log` |
| review7 | PASS=202 FAIL=0 | `run-review7-20261125-112709.log` |
| review8 | PASS=121 FAIL=0 | `run-review8-20261125-112736.log` |
| birth | PASS=19 FAIL=0 | `run-birth-20261125-112755.log` |
| review9a | PASS=45 FAIL=0 | `run-review9a-20261125-112802.log` |
| review9b | **PASS=125 FAIL=2** | `run-review9b-20261125-112806.log` (ulangi: `run-review9b-20261125-113117.log`, juga 125/127) |

S3 restart durability PASS di kedua target; container `mariadb-1011`
tercatat `StartedAt 2026-11-25T04:26:53Z` (11:26 WIB, di tengah run
contract) — restart nyata via `docker stop` + `docker start`.

Kedua FAIL adalah asersi yang sama di kedua target (v2 + wg), lihat
§6.6. Semua asersi perilaku deadlock lainnya (korban 1213, rollback
penuh, peer committed, re-apply berhasil) PASS di 10.11.

## 5. Pemeriksaan 1020/1205/1213 (tak terduga vs dipaksa)

| Suite | 10.5 | 10.11 | 11.8.6 | Keterangan |
|---|---|---|---|---|
| contract | 0 | 0 | 0 | — |
| review7 | 0 | 0 | 0 | — |
| review8 | 8 (semua 1205) | 8 (semua 1205) | 8 (semua 1205) | 1205 yang DIPAKSA skenario F8-lockwait (v2+wg); teks error identik di ketiga versi: "Lock wait timeout exceeded; try restarting transaction" |
| birth | 0 | 0 | 0 | — |
| review9a | 0 | 0 | 0 | — |
| review9b | 14 (1213) | 14 (1213) | 14 (1213) | 1213 yang DIPAKSA skenario F9D-deadlock; jumlah kemunculan sama persis |

Nol 1020 di semua log. Nol 1213 di luar skenario deadlock yang dipaksa.
(Catatan: angka 0 review8 di NOTES item (b) memakai pola `1213|1020`
tanpa 1205; dengan pola `1020|1205|1213` baseline 11.8.6 juga 8.)

## 6. Perbedaan perilaku antar versi yang ditemukan

### 6.1 Nama variabel isolasi: `tx_isolation` vs `transaction_isolation`
- 10.5.29: hanya `tx_isolation` (REPEATABLE-READ).
- 10.11.19: hanya `tx_isolation` (REPEATABLE-READ).
- 11.8.6: `transaction_isolation` DAN `tx_isolation` (keduanya
  REPEATABLE-READ).
- Dampak: NOL. Tidak ada kode endpoint/harness yang membaca atau
  menyetel variabel ini (grep seluruh endpoint-src + runner + probe:
  nol); ketiga server default REPEATABLE-READ.

### 6.2 `innodb_snapshot_isolation`
- 10.5.29: variabel tidak ada.
- 10.11.19: ada, default OFF.
- 11.8.6: ada, default ON.
- Dampak: NOL yang teramati. Seluruh suite — termasuk skenario
  konkurensi, lock-wait, dan deadlock paksa — hijau identik di ketiga
  nilai (absen / OFF / ON).

### 6.3 Klien 11.8 menuntut TLS; server 10.5/10.11 docker tanpa SSL
- Gejala: `mariadb-admin`/`mariadb` 11.8 dari host ke container 10.5
  gagal: `TLS/SSL error: SSL is required, but the server does not
  support it` (image docker tidak membawa sertifikat SSL).
- Perbaikan (harness-only): `ssl=0` di `[client]` pada `.cnf` TCP
  (di `provision-version.sh` dan setup copy). Ini BUKAN perbedaan
  perilaku server — hanya default klien 11.8 vs server tanpa SSL.
  Sisi PHP (mysqli di worker/orkestrator/peer) tidak pernah meminta
  SSL, jadi tidak terdampak.

### 6.4 Koneksi via published port tiba dari IP gateway Docker
- Gejala: user `contract@127.0.0.1`/`localhost` ditolak —
  `Access denied for user 'contract'@'172.17.0.1'` — karena koneksi
  lewat port publish tiba di container dari IP gateway Docker, bukan
  127.0.0.1. Endpoint lalu jatuh ke placeholder hash -> smoke
  `unauthorized` (bukan 500; jalur fallback secret di notify.php).
- Perbaikan (harness-only): tambah akun `contract@'%'` (setara
  localhost-only di sini karena port hanya di-publish di 127.0.0.1).
  BUKAN perbedaan perilaku server.

### 6.5 Selain itu: TIDAK ADA perbedaan perilaku lain
Teks error 1205/1213 identik; perilaku lock-wait, deadlock (korban
dipilih berdasar bobot, rollback penuh, re-apply berhasil), dedupe,
re-match, dan poin identik; semua asersi perilaku hijau di ketiga versi.
Satu-satunya perbedaan yang mengubah hasil asersi adalah §6.6 (teks
diagnostik, bukan perilaku).

### 6.6 TEMUAN: `WE ROLL BACK TRANSACTION (N)` di 10.11 = (2), bukan (1)
Satu-satunya perbedaan antar versi yang mematahkan asersi — dan ini
perbedaan NYATA (bukan artefak harness), deterministik, dan sudah
direproduksi:

**Asersi yang gagal (2x, v2 + wg, hanya di 10.11):**
`F9D-deadlock [v2|wg] InnoDB rolled back exactly one transaction
(missing /WE ROLL BACK TRANSACTION \(1\)/ in f9d-d-*.innodb-deadlock.txt)`
— asersi dari review #9b §3.3 yang mengunci angka `(1)`.

**Fakta teramati (tiga versi, deadlock yang SAMA persis — peer berat
7 row lock vs apply 3 row lock, korban TERBUKTI apply di ketiganya):**

| Versi | Urutan listing | Korban terbukti | Baris penutup |
|---|---|---|---|
| 10.5.29 (format lama) | (1)=apply (3 lock, menunggu pelanggan); (2)=peer (menunggu proforma) | apply = (1) | `WE ROLL BACK TRANSACTION (1)` |
| 11.8.6 (format baru) | (1)=peer (7 lock, menunggu proforma); (2)=apply (3 lock, menunggu pelanggan) | apply = (2) | `WE ROLL BACK TRANSACTION (1)` |
| 10.11.19 (format baru) | (1)=peer (7 lock, menunggu proforma); (2)=apply (3 lock, menunggu pelanggan) | apply = (2) | `WE ROLL BACK TRANSACTION (2)` |

- Diff mentah file deadlock v2 11.8.6 vs 10.11: 236 baris di keduanya,
  struktur identik (jumlah lock, relasi tunggu, urutan listing sama);
  selisih hanya nilai spesifik-run (timestamp, trx id, thread id,
  space id, heksa record, heap 1120 vs 1128) DAN angka `(N)` itu.
- Jadi `(N)` TIDAK konsisten memetakan ke korban di semua versi (di
  11.8.6 `(1)` untuk korban `(2)`; di 10.11 `(2)` untuk korban `(2)`),
  dan TIDAK konsisten memetakan ke jumlah yang di-rollback (di 10.11
  hanya SATU transaksi yang di-rollback — peer TERBUKTI committed —
  tapi `(N)=(2)`). Interpretasi §3.3 review #9b ("`(N)` = jumlah")
  benar di 10.5/11.8.6 tapi SALAH di 10.11; interpretasi "indeks
  korban" benar di 10.5/10.11 tapi SALAH di 11.8.6. Angka ini artefak
  diagnostik yang pemetaannya berubah antar branch — didukung laporan
  publik yang sama: MDEV-37845 ("Deadlock log shows 'WE ROLL BACK
  TRANSACTION (2)' but the rolled-back trx (per application exception)
  is the other one", 10.6.19).
- **Perilaku TIDAK berbeda:** di 10.11, semua asersi perilaku skenario
  deadlock PASS — apply `applied=false` + 1213, peer `committed` +
  errno 0, tagihan tetap UNPAID, pemasukan 0, pelanggan UNPAID/masa
  0/poin 0, log tetap matched + `applied_at` NULL, efek survivor
  hanya dari peer, re-apply `applied=true` -> PAID/masa 30/poin 10.
  Yang gagal HANYA dua asersi teks `(1)` + dua verdict skenario yang
  mengikutinya (verdict = FAIL karena 1 asersi gagal, bukan karena
  perilaku).
- **Deterministik:** ulangi review9b di 10.11 -> lagi 125/127 dengan
  `(2)` di kedua target (`run-review9b-20261125-113117.log`); ulangi
  di 10.5 -> lagi 127/127 dengan `(1)` (`run-review9b-20261125-113146.log`).
- **Tidak diubah:** asersi TIDAK disunting untuk menyembunyikan ini
  (sesuai aturan tugas). Keputusan soal asersi `(1)` — longgarkan ke
  pola `WE ROLL BACK TRANSACTION \(\d+\)` + identifikasi korban dari
  teks apply/peer (seperti §3.3 dulu), atau kunci per versi — diserahkan
  ke parent/orchestrator + reviewer; ini BUKAN item (c), melainkan
  tindak lanjut review #9b.
  **Keputusan diambil + dilaksanakan 25 Nov 2026 11:42 WIB (tindak lanjut
  item (c), lihat §6.7): longgarkan ke `WE ROLL BACK TRANSACTION \([0-9]+\)`;
  rerun review9b 127/127 di ketiga versi.**

## 6.7 Tindak lanjut: pelonggaran asersi `(1)` → `[0-9]+` (25 Nov 2026, 11:42–11:44 WIB)

**Perubahan (satu asersi saja, di `run-review9b.sh`):**

Sebelum:
`assert_file_contains "F9D-deadlock [$t] InnoDB rolled back exactly one transaction" "$dlfile" "WE ROLL BACK TRANSACTION \(1\)"`

Sesudah:
`assert_file_contains "F9D-deadlock [$t] InnoDB rolled back a transaction" "$dlfile" "WE ROLL BACK TRANSACTION \([0-9]+\)"`

Komentar rasional ditulis di samping asersi (NOTE comment) dan di header
runner: nilai `(N)` adalah teks diagnostik yang bergantung versi dan TIDAK
mengidentifikasi korban maupun jumlah yang di-rollback — 10.5.29 dan
11.8.6 menulis `(1)` sementara 10.11.19 menulis `(2)` untuk deadlock yang
sama persis; 11.8.6 menulis `(1)` padahal korbannya transaksi (2), dan
10.11 me-rollback tepat satu transaksi (peer TERBUKTI committed) tapi
menulis `(2)`; cf. MDEV-37845 (10.6.19). Identitas korban dibuktikan
secara independen oleh respons 1213 apply + `applied=false`, commit peer,
cek rollback baris-per-baris, dan re-apply yang berhasil.

**Cakupan perubahan (terverifikasi):**
- Diterapkan di TIGA salinan yang sama: kanonikal
  `/home/warganet/c2-harness/tests/server-contract/run-review9b.sh`,
  salinan mariadb
  `/home/warganet/c2-harness-mariadb/tests/server-contract/run-review9b.sh`,
  dan salinan lokal `~/workspace/c2-impl/review9-deadlock/new/run-review9b.sh`
  (untuk regenerasi bundle nanti). `bash -n` bersih di ketiganya.
- Kanonikal byte-identik dengan salinan lokal (sesudah fix); kanonikal vs
  salinan mariadb berbeda HANYA pada patch `worker_log_of` (§1) — tidak ada
  asersi lain yang berubah.
- `grep -r 'WE ROLL BACK'` di kedua `tests/server-contract/` hanya mengenai
  `run-review9b.sh` — tidak ada suite lain yang memakai pola ini, jadi tidak
  ada suite lain yang terpengaruh.

**Rerun review9b sesudah fix (driver log:
`/home/warganet/rerun-review9b-fix.log`):**

| Versi | Log | Hasil | Baris `WE ROLL BACK` |
|---|---|---|---|
| 10.5.29 | `/home/warganet/paylist-contract-m105/results/run-review9b-20261125-114343.log` (salinan: `/home/warganet/paylist-contract-c2/results/mariadb105-run-review9b-20261125-114343.log`) | **PASS=127 FAIL=0**, rc=0 | `(1)` / `(1)` |
| 10.11.19 | `/home/warganet/paylist-contract-m1011/results/run-review9b-20261125-114358.log` (salinan: `/home/warganet/paylist-contract-c2/results/mariadb1011-run-review9b-20261125-114358.log`) | **PASS=127 FAIL=0**, rc=0 | `(2)` / `(2)` |
| 11.8.6 | `/home/warganet/paylist-contract-c2/results/run-review9b-20261125-114407.log` | **PASS=127 FAIL=0**, rc=0 | `(1)` / `(1)` |

- 10.11 tetap menulis `(2)` di kedua target (v2 + wg) — deterministik, sama
  seperti sebelum fix — jadi yang lolos sekarang memang pola yang
  dilonggarkan, bukan pass vakum: baris `PASS: F9D-deadlock [v2|wg] InnoDB
  rolled back a transaction (found /WE ROLL BACK TRANSACTION \([0-9]+\)/ ...)`
  tercatat di ketiga log.
- Semua 6 skenario (sanity/control/deadlock × v2/wg) PASS di ketiga versi;
  `RESULT: ALL SCENARIOS BEHAVED AS SPECIFIED` di ketiga log.
- Keadaan akhir: kedua container **STOPPED** lagi (sama seperti sebelum
  rerun — `mariadb-105`/`mariadb-1011` Exited (0), diverifikasi 11:44 WIB);
  stack 11.8.6 tetap hidup (worker 8080/8090 menjawab).

## 7. Keadaan akhir + cara mengulang

Keadaan akhir: kedua container **STOPPED** (sengaja — box 2 GB RAM;
membiarkan dua MariaDB idle membebani box). Datadir, scratch dir,
dan harness copy tetap ada. Stack 11.8.6 hidup TIDAK tersentuh
(worker 8080-8093 balas 405, mariadbd :3307 hidup — diverifikasi
sebelum, selama, dan sesudah kedua run versi).
Update 25 Nov 2026 11:44 WIB: keadaan yang sama diverifikasi ulang
sesudah rerun review9b pasca-fix §6.7 (kedua container di-start untuk
rerun lalu di-stop lagi via teardown; keduanya Exited (0); stack
11.8.6 tetap menjawab).

Mengulang satu versi (dari laptopgrt2, sebagai root):

```bash
# 10.5:
docker start mariadb-105
cd /home/warganet/c2-harness-mariadb/tests/server-contract
SCRATCH_DIR=/home/warganet/paylist-contract-m105 MYSQL_PORT=3308 \
  MYSQL_DOCKER_CONTAINER=mariadb-105 V2_HTTP_BASE=8180 WG_HTTP_BASE=8190 \
  ./setup-scratch.sh
# lalu jalankan runner yang sama dengan env di atas; birth dari
# /home/warganet/birth-fix/run-birth.sh --pages fixed
# 10.11: sama dengan m1011 / 3309 / mariadb-1011 / 8280 / 8290
# Berhenti: SCRATCH_DIR=... MYSQL_DOCKER_CONTAINER=... ./teardown-scratch.sh
```

## 8. Yang TIDAK disentuh / batasan

- TIDAK disentuh: kode apply, asersi tes, default `SCRATCH_DIR`,
  harness asli `/home/warganet/c2-harness/`, deV2, produksi
  (v2New `a35e11ae…`, warganet `dfebb8db…` — tidak diverifikasi ulang
  di sini karena tidak ada tulis ke sana sama sekali; hanya dibaca
  endpoint-src-nya oleh setup copy).
- Satu-satunya tulis di luar scratch versi: salinan log berprefix
  `mariadb105-*` / `mariadb1011-*` di
  `/home/warganet/paylist-contract-c2/results/` (log saja, bukan DB
  atau docroot 11.8.6).
- `innodb_snapshot_isolation` ON vs OFF tidak diselidiki lebih jauh
  dari hasil suite (tidak ada asersi yang membedakan).
- Mekanisme anomali INNODB_TRX review #9b §3.1 tetap tidak
  terjelaskan; suite review9b hijau di ketiga versi tanpa perubahan.
