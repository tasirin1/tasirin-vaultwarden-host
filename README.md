# Tasirin Vaultwarden Host

Menjalankan server **Vaultwarden** (Bitwarden-compatible, Rust) langsung di Android,
dibangun otomatis lewat **GitHub Actions** (repo `tasirin1/tasirin-vaultwarden-host`).
Mendukung **Android 5.0 (API 21) ke atas**.

## Cara kerja

1. GitHub Actions mengambil **versi Vaultwarden terbaru** dari rilis resminya,
   melakukan *cross-compile* untuk `arm64-v8a`, `armeabi-v7a`, dan `x86_64`
   (target Android, API 21).
2. Binary di-*bundle* ke dalam APK, lalu **APK + binary** di-publish ke
   **GitHub Release** repo ini.
3. Saat tombol **Start** ditekan, app menjalankan binary dengan `DATA_FOLDER`
   sesuai pilihan kamu (default `/sdcard/vaultwarden`) — database SQLite &
   data lain tersimpan di sana.

> `targetSdk 28` dipilih dengan sengaja: Android 10+ hanya memblokir eksekusi
> binary dari app data untuk app dengan `targetSdk >= 29`. `targetSdk 28` juga
> mempertahankan akses storage lama (`/sdcard/...`) di Android 11+.

## Download APK

- **GitHub Release** → pilih APK sesuai arsitektur HP:
  - `tasirin-vaultwarden-host-armeabi-v7a.apk` — 32-bit ARM (STB / HP lama).
- **Tab Actions** → run terbaru → artifact `tasirin-vaultwarden-host-apk-armeabi-v7a`.
- Cek arsitektur HP: app **CPU-Z**, atau terminal `adb shell getprop ro.product.cpu.abi`.
- APK rilis ditandatangani **keystore Tasirin** (sama dengan Tasirin Download
  Manager) — tinggal install (aktifkan *install from unknown sources*).

## Pemakaian

1. Install APK, buka app **Tasirin Vaultwarden Host**, beri izin **Storage** bila diminta.
2. Isi **Folder data** (mis. `/sdcard/vaultwarden`) dan **Port** (default `8080`).
3. Tekan **Start**. Status menunjukkan server berjalan + URL jaringan.
4. Buka `http://127.0.0.1:8088` lewat tombol **Open Web UI** (browser HP),
   atau dari PC/laptop pakai `http://<IP-lokal-android>:8088`.
5. Klien: install app **Bitwarden** resmi → Settings → Server URL →
   `http://<IP-lokal-android>:8088`.

## Data & restart

- Database tersimpan di `DATA_FOLDER` (default `/sdcard/vaultwarden/db.sqlite3`).
  Karena di storage eksternal, data **tidak hilang saat restart device**,
  bahkan saat app di-uninstall. Backup cukup dengan menyalin folder itu.
- Centang **Auto start saat boot** agar server otomatis menyala setelah device
  restart. Server berjalan sebagai *foreground service* (ada notifikasi), lebih
  tahan dibunuh sistem, plus wake lock saat aktif.
- **Restart otomatis saat crash**: jika proses server mati mendadak, app
  menyalakan ulang dengan jeda bertingkat (2 → 5 → 10 → 20 → 40 dtk, maks
  5 percobaan; penghitung di-reset bila server stabil > 1 menit).
- Catatan: jika app di-**force-stop**, Android memblokir broadcast boot sampai
  app dibuka sekali lagi. Swipe dari recents tidak memengaruhi service.

## HTTPS (self-signed)

- Centang opsi **HTTPS (self-signed)** di app lalu Start.
- Sertifikat dibuat otomatis di `<DATA_FOLDER>/tls/` (`cert.pem` + `key.pem`).
- Browser tetap menampilkan peringatan *self-signed*; untuk menghilangkannya:
  *Settings → Security → Install certificate → CA certificate* lalu pilih
  `cert.pem`.
- Aplikasi **Bitwarden** resmi umumnya menolak sertifikat self-signed —
  untuk klien selain web vault, sebaiknya tetap pakai HTTP di jaringan lokal
  yang tepercaya.

## Sumber & update otomatis

- **Cek versi diambil dari sumber resmi** (`dani-garcia/vaultwarden`), bukan
  repo ini. Notifikasi "Update tersedia" muncul otomatis saat app dibuka.
- Workflow dijadwalkan **tiap 6 jam**; bila Vaultwarden rilis versi baru,
  repo ini otomatis di-build ulang (skip bila versi sama).
- Binary Android & `web-vault.zip` **di-host di repo ini** karena rilis resmi
  Vaultwarden tidak menyediakan asset biner (Docker-only) — kontennya tetap
  diambil dari sumber resmi: source `dani-garcia/vaultwarden` untuk binary,
  dan image `vaultwarden/web-vault` (digest resmi) untuk web vault.
- Di app: tekan **Cek Update** — jika ada versi lebih baru, binary diunduh
  sesuai ABI perangkat dan langsung dipakai saat Start berikutnya
  (**tanpa install ulang APK**).
- **Update Web Vault** mengunduh `web-vault.zip` dari repo ini (isinya dari
  image web vault resmi) dan mengekstraknya ke `<data>/web-vault`.
- **Revert Bawaan** mengembalikan ke binary yang dibundel di dalam APK.

## Backup cloud (Telegram)

- Di app: isi **Bot token** dan **Chat ID**, lalu tekan **Backup ke Telegram**.
- Backup berupa zip `db.sqlite3` (+ file WAL) dengan timestamp, disimpan juga
  di `<data>/backups/` (maksimal 10 file, tertua otomatis dihapus).
- **Restore dari Telegram**: ambil backup terakhir yang pernah terkirim,
  server dihentikan otomatis, database dipulihkan.
- **Enkripsi**: isi *Password enkripsi backup* untuk mengenkripsi (AES-256-GCM)
  backup sebelum dikirim — wajib diisi sama saat restore.
- Centang **Backup otomatis tiap 24 jam** untuk kirim otomatis (via AlarmManager,
  jalan walau app tidak dibuka; terjadwal ulang setelah reboot).
- **PIN kunci app** (opsional): kunci app dengan PIN 4-6 digit saat dibuka.
- Cara buat bot: chat `@BotFather` → `/newbot` → ikuti langkah → dapat token.
- Chat ID: kirim pesan ke bot-mu dulu, lalu buka
  `https://api.telegram.org/bot<TOKEN>/getUpdates` di browser dan ambil angka
  `"chat":{"id":...}` (bisa juga pakai `@userinfobot`).

## Build ulang manual

- **Actions → Build Tasirin Vaultwarden Host APK → Run workflow**
  (atau otomatis tiap push ke `main`, atau tiap 6 jam).

## Lisensi

- Wrapper app: GPL-3.0 (lihat `LICENSE`).
- Vaultwarden: AGPL-3.0 (lihat `LICENSE.vaultwarden`; sumber:
  https://github.com/dani-garcia/vaultwarden).
