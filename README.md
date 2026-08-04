# Vaultwarden Host for Android

Menjalankan server **Vaultwarden** (Bitwarden-compatible, Rust) langsung di Android,
dibangun otomatis lewat **GitHub Actions**. Mendukung **Android 5.0 (API 21) ke atas**.

## Cara kerja

1. GitHub Actions melakukan *cross-compile* binary `vaultwarden` untuk
   `arm64-v8a`, `armeabi-v7a`, dan `x86_64` (target Android, API 21).
2. Binary di-*bundle* ke dalam APK sebagai asset.
3. Saat tombol **Start** ditekan, app mengekstrak binary ke app data, lalu
   menjalankannya dengan `DATA_FOLDER` sesuai pilihan kamu (default
   `/sdcard/vaultwarden`), jadi database SQLite & data lain tersimpan di sana.

> `targetSdk 28` dipilih dengan sengaja: Android 10+ hanya memblokir eksekusi
> binary dari app data untuk app dengan `targetSdk >= 29`. `targetSdk 28` juga
> mempertahankan akses storage lama (`/sdcard/...`) di Android 11+.

## Build & download APK

- Build otomatis berjalan saat ada push ke `main`, atau manual via
  **Actions → Build Vaultwarden Android APK → Run workflow**.
- APK di-upload sebagai artifact **`vaultwarden-android-apk`**
  (Actions → run terbaru → paling bawah halaman).
- APK ditandatangani dengan debug key, tinggal install (aktifkan
  *install from unknown sources*).

## Pemakaian

1. Install APK, buka app **Vaultwarden Host**.
2. Beri izin **Storage** bila diminta.
3. Isi **Folder data** (mis. `/sdcard/vaultwarden`) dan **Port** (default `8080`).
4. Tekan **Start**. Status akan menunjukkan server berjalan.
5. Buka `http://127.0.0.1:8080` lewat tombol **Open Web UI**, atau dari
   perangkat lain pakai `http://<IP-lokal-android>:8080`.
6. Untuk klien: install app **Bitwarden** resmi → Settings → Server URL →
   isi `http://<IP-lokal-android>:8080`.
7. Database & attachment tersimpan di folder data — backup folder itu saja.

## Catatan

- **Web vault tidak disertakan** dalam build ini (biar binary tetap ramping).
  Gunakan app Bitwarden / browser dengan `WEB_VAULT_ENABLED=false`.
- Server berhenti jika app ditutup paksa / di-*swipe*. Jangan force-stop
  jika ingin server tetap jalan; app memakai wake lock saat server aktif.
- Port di bawah 1024 butuh root — gunakan `8080` atau lainnya.
- Untuk akses dari luar jaringan: gunakan port forwarding router atau
  reverse tunnel (mis. `cloudflared`).

## Lisensi

- Wrapper app: GPL-3.0 (lihat `LICENSE`).
- Vaultwarden: AGPL-3.0 (lihat `LICENSE.vaultwarden`; sumber:
  https://github.com/dani-garcia/vaultwarden).
