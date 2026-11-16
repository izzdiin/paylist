# Paylist — Payment Listener (Android)

Aplikasi Android (`com.netter.paylist`) yang menangkap notifikasi pembayaran
dari aplikasi merchant/banking di HP, lalu meneruskannya ke endpoint billing
melalui sistem **rute**: setiap rute = daftar paket sumber + kata kunci +
satu URL endpoint + secret (`X-QRIS-Secret`). Urutan rute adalah prioritas;
endpoint pertama yang menjawab `matched`/`ambiguous` adalah pemilik
pembayaran itu, jawaban `logged` meneruskan rantai ke rute berikutnya.
Endpoint pencatat (mis. myBCA) selalu menjawab `logged` sehingga satu
notifikasi bisa tercatat di dua sistem sekaligus.

Komponen (v2.3):

- `PaylistListenerService` — NotificationListenerService: saring notifikasi
  per paket rute aktif + kata kunci per rute, parser nominal umum
  (pola `Rp` dan `IDR`), pindai ulang notifikasi aktif di shade (< 24 jam).
  Paket yang belum terdaftar hanya dicatat namanya di log lokal (isinya
  tidak dibaca), untuk menemukan nama paket aplikasi baru.
- `Sender` — antrean kirim tangguh: satu item gagal tidak menghentikan
  item lain, heartbeat kedaluwarsa (> 30 menit) dibuang, setiap kegagalan
  dicatat dengan alasannya.
- `HeartbeatReceiver` — heartbeat tiap 15 menit ke semua rute aktif.
- `Store` — konfigurasi + antrean + log terstruktur di SharedPreferences
  (tanpa dependensi eksternal; hanya framework Android).

## v1.1 (Phase 1.3 — in progress)

v1.1 mengganti antrean SharedPreferences + sender langsung dengan Room
persistent outbox + WorkManager delivery, dengan kontrak wire legacy
(field payload, `X-QRIS-Secret`, `dedupe_key`, `matched`/`ambiguous`/
`logged`) tidak berubah. Desain normatif: `docs/phase1/` (frozen — jangan
diubah). Ringkasan arsitektur: `docs/architecture.md`. Matriks tes:
`docs/test-plan.md`.

Tahapan: A Foundation (build, wrapper, ikon, dependensi, stub model/
parser, fixtures kontrak) → B Room → C Delivery → D Collector/security/
UI → E Verification. Gate A→B: T01 (cold build) + contract fixtures.

## Build

Butuh JDK 17, Android SDK (platform 34, build-tools 34.0.0), Gradle 8.9:

```sh
export JAVA_HOME=~/jdk17 ANDROID_HOME=~/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew assembleDebug --no-daemon --console=plain \
  -Dorg.gradle.jvmargs="-Xmx1024m" -Dkotlin.daemon.jvm.options="-Xmx512m"
```

Flag heap itu wajib di laptopgrt (RAM 2 GB). `build-on-laptopgrt.sh`
menyiapkan toolchain di `$HOME` lalu membangun dari
`~/paylist-build/paylist` (salinan v2.3, bukan repo ini). APK debug hasil
build bertanda tangan debug standar dan bisa dipasang menimpa versi
sebelumnya tanpa menghapus konfigurasi rute di HP.

Dependensi (desain §11, C17): Room 2.7.0 + KSP2 (prefix versi KSP harus
sama dengan Kotlin 2.0.20), WorkManager 2.9.0, Kotlin coroutines 1.8.1.
Fallback yang didokumentasikan, hanya bila KSP2 gagal: Room 2.6.1 +
`ksp.useKSP2=false`.

### Binary assets

Dua kelompok berkas biner tidak bisa lewat jalur push teks yang tersedia
(GitHub MCP hanya menerima UTF-8), jadi harus diunggah ke branch lewat
GitHub web UI (atau lewat git push dari mesin yang punya auth GitHub):

1. `gradle/wrapper/gradle-wrapper.jar` — dibuat lokal oleh
   `gradle wrapper --gradle-version 8.9`.
2. 10 ikon launcher `app/src/main/res/mipmap-*/ic_launcher*.png` —
   dipulihkan dari pohon build v2.3 pengguna
   (`~/paylist-build/paylist/app/src/main/res/` di laptopgrt).

Tanpa ikon, `assembleDebug` gagal (manifest merujuk
`@mipmap/ic_launcher` / `@mipmap/ic_launcher_round`). CI memakai
`gradle/actions/setup-gradle` (bukan `./gradlew`) sampai wrapper JAR ada
di branch — lihat `.github/workflows/android-ci.yml`.

md5 ikon (per density, `ic_launcher.png` dan `ic_launcher_round.png`
identik byte-per-byte — quirk v2.3 yang sudah ada sebelumnya,
dipulihkan apa adanya):

| Density | md5 |
|---|---|
| mdpi | `ef0e1a6aad59eea045b2c5250e9d1b70` |
| hdpi | `4f0d35895e8341733346fff6c8cfe8a4` |
| xhdpi | `5cbb2d7822aad9ca2bdd94d04a7c7471` |
| xxhdpi | `9ab48aa6e04794202b4446439caaf0ff` |
| xxxhdpi | `7a62288d2df72d249ececcc57608d834` |

## Tests

Tes unit (JVM, tanpa perangkat; tidak pernah menyentuh server live):

```sh
./gradlew testDebugUnitTest --no-daemon --console=plain \
  -Dorg.gradle.jvmargs="-Xmx1024m" -Dkotlin.daemon.jvm.options="-Xmx512m"
```

- `parser/PaymentParserTest` — regresi parser v2.3 (`parseAmount`):
  pola Rp/IDR, aturan token-pertama, quirk yang sudah ada.
- `delivery/LegacyContractFixturesTest` — fixtures kontrak endpoint
  legacy (bentuk respons `notify.php`/`catat.php` yang direkam dari
  sumber PHP asli) atas taksonomi §5.4, termasuk 3 kasus T18 yang
  dikoreksi. Fixtures di `app/src/test/resources/fixtures/legacy/`.
- `core/model/DeliveryStateTest` — state machine delivery §5.

Hasil: `app/build/test-results/testDebugUnitTest/*.xml`.
Tes instrumented (`src/androidTest/`) butuh perangkat/emulator (Stage B+).

## Migrasi (v1.1)

- Mode cutover (`LEGACY_ONLY` → `DRAINING` → `WORKER_ONLY`) hidup di Room
  `collector_state` — tidak ada nilai mode di SharedPreferences.
- Impor antrean legacy bersifat idempotent per `qkey`, diverifikasi
  hitung + checksum sebelum `WORKER_ONLY` di-commit; antrean legacy
  hanya dibersihkan per entri yang terverifikasi terimpor/terselesaikan
  (entri karantina dipertahankan). Cutover bersifat monoton: sekali
  `DRAINING` di-commit, mode tidak pernah kembali ke `LEGACY_ONLY`
  secara otomatis.
- Secret rute dimigrasikan sekali saat peluncuran v1.1 pertama dari
  SharedPreferences plaintext ke Android Keystore (`SecretVault`); tidak
  ada fallback plaintext. Rute yang secret-nya hilang setelah restore
  ditandai `DISABLED_MISSING_SECRET` dan tidak pernah mengirim.
- Backup (keputusan owner, P10 option C): database Room dan antrean
  DIKECUALIKAN dari backup/transfer perangkat. Perangkat yang diganti
  atau di-reset sebelum pembayaran tertunda direkonsiliasi akan
  kehilangan catatan tertunda itu dari perangkat; yang tersisa hanya log
  server dari rute yang sudah dicapai — bila ada.

## Diagnostik

- Kartu status di layar utama: jumlah antrean, kegagalan terakhir,
  pindai ulang terakhir, status listener.
- Log lokal terstruktur di layar utama, dibagi "Log Paket Terdaftar" dan
  "Log Paket Belum Terdaftar" (dinilai dari daftar paket rute saat ini).
- Heartbeat: event heartbeat tiap 15 menit per rute aktif; server
  memantau ACK heartbeat terakhir. Heartbeat kedaluwarsa (> 30 menit /
  2 bucket) dibuang ke `EXPIRED` — tidak pernah dihitung sebagai ACK.
- v1.1 menambahkan metrik diagnostik Room (capture/persist/delivery per
  status, `HELD` per rute, `last_apply_status`); diagnostik tidak pernah
  mencatat `qkey`/`dedupe_key`/`content_sha256`, URL lengkap, secret,
  atau teks mentah.

## Known Android limits

- Android tidak menjamin setiap notifikasi tertangkap. Room melindungi
  event **setelah** transaksi penyimpanannya berhasil, bukan notifikasi
  yang tidak pernah diterima listener.
- Temuan audit P0 (celah tangkap Doze, 8,5 jam, 4 Nov 2026) TIDAK
  diselesaikan oleh desain v1.1 — jangan klaim sebaliknya. WorkManager
  adalah best-effort; `force-stop`, pembatasan OEM, dan notifikasi yang
  sudah di-dismiss tidak bisa diperbaiki WorkManager saja.
- Tidak ada foreground service di Phase 1 sampai ada bukti tes perangkat
  dan keputusan terpisah.
- Pindai ulang hanya membaca notifikasi yang masih aktif (< 24 jam),
  saat listener tersambung atau atas permintaan manual.

## Fitur per versi

- **2.3** — Form rute: tombol "Pilih dari Aplikasi Terpasang" (daftar semua
  aplikasi di HP: label + nama paket, bisa dicari) di samping field paket
  sumber yang tetap bisa diketik manual. Log layar utama jadi dua bagian:
  "Log Paket Terdaftar" dan "Log Paket Belum Terdaftar", dinilai dari
  daftar paket rute saat ini; log lama termigrasi otomatis.
- **2.2** — Parser nominal pola IDR + pindai ulang notifikasi aktif
  (otomatis saat listener tersambung, manual dari layar utama).
- **2.1** — Antrean anti-macet (satu rute mati tidak memblokir yang lain),
  kartu status di layar utama (antrean, kegagalan terakhir).

## Catatan repo

- Secret endpoint dan konfigurasi rute tersimpan di HP (SharedPreferences
  aplikasi), tidak pernah ada di repo ini.
- `docs/phase1/` adalah dokumen desain beku (D-final approved) — jangan
  diubah selama implementasi.
