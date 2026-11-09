# Paylist — Payment Listener (Android)

Aplikasi Android (`com.netter.paylist`) yang menangkap notifikasi pembayaran
dari aplikasi merchant/banking di HP, lalu meneruskannya ke endpoint billing
melalui sistem **rute**: setiap rute = daftar paket sumber + kata kunci +
satu URL endpoint + secret (`X-QRIS-Secret`). Urutan rute adalah prioritas;
endpoint pertama yang menjawab `matched`/`ambiguous` adalah pemilik
pembayaran itu, jawaban `logged` meneruskan rantai ke rute berikutnya.
Endpoint pencatat (mis. myBCA) selalu menjawab `logged` sehingga satu
notifikasi bisa tercatat di dua sistem sekaligus.

Komponen:

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

## Build

Butuh JDK 17, Android SDK (platform 34, build-tools 34.0.0), Gradle 8.9:

```
gradle assembleDebug --no-daemon
```

atau jalankan `build-on-laptopgrt.sh` yang menyiapkan toolchain di `$HOME`
lalu membangun dari `~/paylist-build/paylist`. APK debug hasil build
bertanda tangan debug standar dan bisa dipasang menimpa versi sebelumnya
tanpa menghapus konfigurasi rute di HP.

## Catatan repo

- Berkas ikon launcher (`app/src/main/res/mipmap-*/ic_launcher*.png`,
  biner) tidak ikut terunggah karena jalur unggah yang dipakai hanya
  menerima berkas teks. Salin folder `app/src/main/res/` dari proyek lokal
  untuk build yang utuh; `AndroidManifest.xml` merujuk ikon tersebut.
- Secret endpoint dan konfigurasi rute tersimpan di HP (SharedPreferences
  aplikasi), tidak pernah ada di repo ini.
