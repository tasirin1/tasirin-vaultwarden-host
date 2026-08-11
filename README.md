# Tasirin Vaultwarden Host

[![Build](https://github.com/tasirin1/tasirin-vaultwarden-host/actions/workflows/build-apk.yml/badge.svg)](https://github.com/tasirin1/tasirin-vaultwarden-host/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-vaultwarden-host)](https://github.com/tasirin1/tasirin-vaultwarden-host/releases)

<p align="center"><b>&#127760; Bahasa: <a href="README.md">Indonesia</a> &middot; <a href="README.en.md">English</a> &middot; <a href="CHANGELOG.md">Changelog</a></b></p>

> **Untuk AI/agent yang mengelola repo ini: baca [AGENTS.md](AGENTS.md) dulu** —
> berisi struktur, arsitektur, aturan pengembangan, dan alur build/release.
> Agent wajib membacanya sebelum mengubah atau mengelola kode.

Menjalankan server **Vaultwarden** (Bitwarden-compatible, Rust) langsung di
Android, dibangun otomatis lewat **GitHub Actions**. Mendukung **Android 5.0
(API 21) ke atas** — termasuk STB/TV box 32-bit (ARM `armeabi-v7a`).

Repo ini **bukan cuma README**: dokumen ini panduan pemakaian & gambaran
teknis; panduan pengelolaan lengkap ada di
[AGENTS.md](AGENTS.md) dan riwayat perubahan di [CHANGELOG.md](CHANGELOG.md).

## Daftar isi

- [Fitur](#fitur)
- [Cara kerja](#cara-kerja)
- [Download & instal](#download--instal)
- [Pemakaian](#pemakaian)
- [Instal tanpa internet (offline)](#instal-tanpa-internet-offline)
- [Status web & log realtime](#status-web--log-realtime)
- [Remote via Telegram](#remote-via-telegram)
- [Data, backup & restart](#data-backup--restart)
- [HTTPS (self-signed)](#https-self-signed)
- [Update otomatis](#update-otomatis)
- [Troubleshooting](#troubleshooting)
- [Struktur repository](#struktur-repository)
- [Arsitektur ringkas](#arsitektur-ringkas)
- [Panduan pengelolaan repo (untuk manusia & AI)](#panduan-pengelolaan-repo-untuk-manusia--ai)
- [Lisensi](#lisensi)

## Fitur

- Server Vaultwarden asli (binary Rust resmi, bukan tiruan), versi mengikuti
  rilis resmi `dani-garcia/vaultwarden`.
- APK super kecil (~0,1 MB): binary & web vault **tidak dibundel**, diunduh
  dari GitHub Release saat Start pertama (diverifikasi SHA-256).
- Auto-update binary & web vault di jaringan rumah (WiFi/ethernet), plus
  restart otomatis setelah update — semua bisa diatur lewat checkbox.
- Status web ringan (JSON + log realtime via SSE) di port terpisah.
- Remote & backup via **Telegram bot** (command + backup AES-256-GCM).
- HTTPS self-signed, PIN kunci app, auto-start saat boot, restart otomatis
  saat crash, deteksi port bentrok, export/import config.
- UI ramah remote TV (navigasi D-pad) maupun layar sentuh HP.

## Cara kerja

1. **GitHub Actions** mengambil versi Vaultwarden terbaru dari rilis resmi,
   melakukan *cross-compile* untuk **`armeabi-v7a`** (ARM 32-bit, target
   Android API 21) — satu-satunya ABI yang didukung.
2. **APK + binary + web-vault.zip** di-publish ke **GitHub Release** repo ini.
   Binary server & web vault **tidak dibundel di dalam APK**; app mengunduhnya
   dari release saat Start pertama. DNS resolver kustom Vaultwarden
   (`hickory`/`ndk-context`) dinonaktifkan di Android karena butuh konteks JNI
   yang tidak tersedia di proses *standalone* (lihat [Panduan pengelolaan](#panduan-pengelolaan-repo-untuk-manusia--ai)).
3. Saat tombol **Start** ditekan, app menjalankan binary dengan `DATA_FOLDER`
   sesuai pilihan (default `/sdcard/vaultwarden`) — database SQLite & data
   lain tersimpan di sana, tidak hilang saat update app/restart device.

> `targetSdk 28` dipilih dengan sengaja: Android 10+ memblokir eksekusi binary
> dari app data untuk app dengan `targetSdk >= 29` (perilaku W^X). `targetSdk 28`
> juga mempertahankan akses storage lama (`/sdcard/...`) di Android 11+.

## Download & instal

- **GitHub Release** → `tasirin-vaultwarden-host-armeabi-v7a.apk`
  (ARM 32-bit — jalan di STB & HP lama; HP arm64 tetap bisa via compat mode).
- **Tab Actions** → run terbaru → artifact `tasirin-vaultwarden-host-apk-armeabi-v7a`.
- APK rilis ditandatangani **keystore Tasirin** (sama dengan Tasirin Download
  Manager) — tinggal install (aktifkan *install from unknown sources*).
- Versi APK = tanggal build (`2026.08.08`); versi release = versi Vaultwarden
  (`v1.37.x`).

## Pemakaian

1. Install APK, buka app, beri izin **Storage** bila diminta.
2. Isi **Folder data** (mis. `/sdcard/vaultwarden`) dan **Port** (default `8088`).
3. Tekan **Start**. Pertama kali binary diunduh otomatis (SHA-256, tersimpan di
   internal, tidak diunduh ulang). Bila web vault belum ada, pilih **Unduh &
   Start** di dialog. Status menampilkan server berjalan + URL.
4. Buka `http://127.0.0.1:8088` lewat tombol **Open Web UI** (browser HP), atau
   dari PC/laptop pakai `http://<IP-lokal-android>:8088`.
5. Klien: install app **Bitwarden** resmi → Settings → Server URL →
   `http://<IP-lokal-android>:8088`.

## Instal tanpa internet (offline)

APK tidak membundel binary & web vault supaya ukurannya kecil, jadi Start
pertama biasanya butuh internet. Bila perangkat tidak punya akses internet
(khususnya STB/TV), siapkan dua file ini **sekali** lewat perangkat lain:

1. Unduh dari halaman **Release** repo ini:
   - `vaultwarden-armeabi-v7a` (binary server, ~20 MB)
   - `web-vault.zip` (halaman web vault, ~36 MB)
2. Letakkan binary dengan nama persis: `/sdcard/vaultwarden/vaultwarden-armeabi-v7a`.
3. Ekstrak `web-vault.zip` sehingga muncul file
   `/sdcard/vaultwarden/web-vault/index.html` (isi zip diekstrak langsung ke
   folder `web-vault`).
4. Buka app, isi **Folder data** = `/sdcard/vaultwarden`, tekan **Start** —
   app memakai binary & web vault yang sudah ada **tanpa internet**.

Catatan: binary di `/sdcard` tidak bisa dieksekusi langsung (storage FAT),
karena itu app menyalinnya ke internal dulu saat Start — otomatis, tanpa unduh.
File lain (`web-vault`, database, config) dipakai langsung dari folder data.

## Status web & log realtime

- Saat server berjalan, app juga menjalankan **status web** di **port server + 1**
  (default `8088` → `8089`): `http://<IP-lokal-android>:8089`. Bila port itu
  dipakai, app otomatis mencari port bebas berikutnya (sampai +10).
- Halaman menampilkan status server (jalan/berhenti, versi app/binary/web-vault,
  port, HTTPS, uptime, RAM proses, sisa storage, folder data) plus **log
  realtime** (SSE) dengan pencarian & auto-scroll.
- Endpoint JSON: `GET /api/status`, log teks: `GET /api/log`, stream: `GET /api/events`.
- Di app: tombol **Status Web** membuka halaman itu; tombol **Buka** di panel
  Log membuka **Log Realtime layar penuh** (cari + highlight `GAGAL/ERROR`,
  hitungan baris, salin, bagikan, **Simpan .txt** ke `Download`).

## Remote via Telegram

Hubungkan **Bot token** + **Chat ID** di pengaturan, lalu kirim perintah ke bot:

| Perintah      | Fungsi                                              |
|---------------|-----------------------------------------------------|
| `/status`     | Status lengkap: versi, web vault, RAM, uptime, sisa |
| `/log`        | Potongan log terakhir (3500 karakter)               |
| `/uptime`     | Lama server berjalan                                |
| `/alive`      | Cek sehat via HTTP `/alive`                         |
| `/backup`     | Backup database sekarang (terenkripsi)              |
| `/update`     | Update binary + restart otomatis                    |
| `/webvault`   | Update web vault (restart manual via `/restart`)    |
| `/start` `/stop` `/restart` | Kontrol server                        |
| `/help`       | Daftar perintah                                     |

## Data, backup & restart

- Database: `DATA_FOLDER/db.sqlite3` (default `/sdcard/vaultwarden/`). Karena di
  storage eksternal, data tidak hilang saat update app/restart device.
- **Backup ke Telegram**: zip `db.sqlite3` (+ WAL) ber-timestamp, disimpan juga
  di `<data>/backups/` (maks 10, tertua otomatis dihapus). Opsional terenkripsi
  **AES-256-GCM** (password wajib sama saat restore). Bisa otomatis tiap 24 jam
  atau saat Start, dan menyertakan config + sertifikat (untuk pindah perangkat).
- **Restore**: dari backup Telegram atau file `.zip` lokal; server dihentikan
  otomatis saat restore.
- **Auto start saat boot** (foreground service + wake lock). **Restart otomatis
  saat crash**: jeda bertingkat 2→5→10→20→40 dtk (maks 5×; reset bila stabil >1
  menit). Health check `/alive` adaptif.
- Catatan: jika app di-**force-stop**, Android memblokir broadcast boot sampai
  app dibuka sekali lagi (swipe dari recents tidak memengaruhi service).

## HTTPS (self-signed)

- Centang **HTTPS (self-signed)** lalu Start. Sertifikat dibuat otomatis di
  `<DATA_FOLDER>/tls/` (`cert.pem` + `key.pem`, RSA 2048, berlaku 5 tahun,
  dibuat ulang otomatis saat IP berubah). Layar menampilkan sisa hari berlaku.
- Browser menampilkan peringatan self-signed; hilangkan dengan install
  `cert.pem` sebagai CA (Settings → Security → Install certificate).
- App **Bitwarden** resmi umumnya menolak self-signed — untuk klien non-web
  vault sebaiknya pakai HTTP di jaringan lokal yang tepercaya.

## Update otomatis

- **Cek versi diambil dari sumber resmi** (`dani-garcia/vaultwarden`), bukan
  repo ini. Notifikasi "Update tersedia" muncul otomatis saat app dibuka.
- Di layar utama muncul peringatan `⚠ Update vX tersedia — tekan Cek Update`
  (hilang otomatis setelah terpasang atau saat server berjalan).
- Checkbox di panel **Lanjutan → Pemeliharaan**:
  - *Auto-update binary* — pasang binary terbaru otomatis (jaringan rumah).
  - *Auto-update web vault* — ikutkan web vault mengikuti binary.
  - *Restart otomatis setelah update* — restart server sekali bila update
    terpasang saat server jalan.
- Tombol **Cek Update** memasang binary terbaru (dipakai saat Start berikutnya,
  tanpa install ulang APK). **Update Web Vault** mengunduh `web-vault.zip` dari
  release (isinya dari image web vault resmi) ke `<data>/web-vault`.
- Semua unduhan **diverifikasi SHA-256** terhadap file `.sha256` di release;
  bila tidak cocok, update dibatalkan (aman diulang).
- **Reset Binary** menghapus binary tersimpan — versi terbaru diunduh ulang
  otomatis saat Start berikutnya.

## Troubleshooting

**"Port 8088 sedang dipakai" saat Start**
- Ada aplikasi lain yang memakai port tersebut. Ganti **Port** di app, stop
  aplikasi lain, atau restart HP. Status web otomatis memakai port bebas
  (8089 → 8098) bila port defaultnya bentrok.

**Server start terus gagal / looping**
- Bisa ada proses `vaultwarden` lama yang nyangkut. Restart HP, lalu Start lagi
  (app juga membersihkan proses lama se-UID otomatis saat Start).

**Web UI tidak bisa dibuka dari perangkat lain**
- Pastikan status **Running**, perangkat lain di jaringan yang sama, dan URL
  memakai IP lokal (`http://<IP>:8088`). Bila memakai HTTPS, install sertifikat
  dulu (lihat seksi HTTPS).

**Peringatan sertifikat di browser**
- Normal untuk self-signed. Install `cert.pem` sebagai CA agar peringatan hilang.

**Auto start saat boot tidak jalan**
- Jika app pernah di-**force-stop**, Android memblokir broadcast boot sampai
  app dibuka sekali lagi. Buka app setelah reboot.

**Backup Telegram gagal**
- Cek **Bot token** & **Chat ID** (lihat seksi Remote via Telegram), sisa
  storage, dan koneksi. Backup terenkripsi wajib memakai password yang sama
  saat restore.

**Update web-vault gagal (checksum/unduhan)**
- Koneksi Android lama kadang putus-putus; coba lagi nanti. Unduhan diverifikasi
  SHA-256 dan dibatalkan bila tidak cocok — aman diulang.

---

# Panduan pengelolaan repo

Panduan pengelolaan lengkap (struktur, arsitektur, aturan pengembangan, alur
CI, secrets, troubleshooting, dan pemetaan fitur → file) sudah dipindah ke
**[AGENTS.md](AGENTS.md)** — baca itu dulu sebelum mengubah apa pun.

Ringkasan aturan paling penting:

1. **Build HANYA via GitHub Actions** — jangan build lokal.
2. **Jangan menaikkan `targetSdk` ≥ 29** (Android 10+ memblokir eksekusi
   binary dari app home; `targetSdk 28` dipilih dengan sengaja).
3. **Jangan menambah ABI lain** — repo ini `armeabi-v7a` saja (STB 32-bit).
4. **Jangan membundel binary/web-vault ke APK** — keduanya diunduh dari
   release agar APK tetap ~0,1 MB.
5. **Jaga kompatibilitas Android 5 (API 21)** — semua tugas jaringan di
   thread, UI ramah D-pad.
6. **Versi app mengikuti tanggal build** (`yyyy.MM.dd` / `yyyyMMdd`) — jangan
   diubah manual di `app/build.gradle.kts`.

## Lisensi

- Wrapper app: GPL-3.0 (lihat `LICENSE`).
- Vaultwarden: AGPL-3.0 (lihat `LICENSE.vaultwarden`; sumber:
  https://github.com/dani-garcia/vaultwarden).
