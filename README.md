# Vaultwarden Host for Android

Menjalankan server **Vaultwarden** (Bitwarden-compatible, Rust) langsung di Android,
dibangun otomatis lewat **GitHub Actions**. Mendukung **Android 5.0 (API 21) ke atas**.

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

- **Tab Actions** → run terbaru → artifact **`vaultwarden-android-apk`**
  (paling bawah halaman run).
- **GitHub Release** → `vaultwarden-android.apk` (plus binary per-ABI untuk
  keperluan update di dalam app).
- APK ditandatangani debug key — tinggal install (aktifkan
  *install from unknown sources*).

## Pemakaian

1. Install APK, buka app **Vaultwarden Host**, beri izin **Storage** bila diminta.
2. Isi **Folder data** (mis. `/sdcard/vaultwarden`) dan **Port** (default `8080`).
3. Tekan **Start**. Status menunjukkan server berjalan + URL jaringan.
4. Buka `http://127.0.0.1:8080` lewat tombol **Open Web UI** (browser HP),
   atau dari PC/laptop pakai `http://<IP-lokal-android>:8080`.
5. Klien: install app **Bitwarden** resmi → Settings → Server URL →
   `http://<IP-lokal-android>:8080`.

## Data & restart

- Database tersimpan di `DATA_FOLDER` (default `/sdcard/vaultwarden/db.sqlite3`).
  Karena di storage eksternal, data **tidak hilang saat restart device**,
  bahkan saat app di-uninstall. Backup cukup dengan menyalin folder itu.
- Centang **Auto start saat boot** agar server otomatis menyala setelah device
  restart. Server berjalan sebagai *foreground service* (ada notifikasi), lebih
  tahan dibunuh sistem, plus wake lock saat aktif.
- Catatan: jika app di-**force-stop**, Android memblokir broadcast boot sampai
  app dibuka sekali lagi. Swipe dari recents tidak memengaruhi service.

## Update server otomatis

- Setiap build, workflow memakai **Vaultwarden rilis terbaru** dan mem-publish
  release baru (tag `v<versi>`).
- Di app: tekan **Cek Update** — jika ada versi lebih baru, binary diunduh
  sesuai ABI perangkat dan langsung dipakai saat Start berikutnya
  (**tanpa install ulang APK**).
- **Revert Bawaan** mengembalikan ke binary yang dibundel di dalam APK.

## Build ulang manual

- **Actions → Build Vaultwarden Android APK → Run workflow**
  (atau otomatis tiap push ke `main`).

## Lisensi

- Wrapper app: GPL-3.0 (lihat `LICENSE`).
- Vaultwarden: AGPL-3.0 (lihat `LICENSE.vaultwarden`; sumber:
  https://github.com/dani-garcia/vaultwarden).
