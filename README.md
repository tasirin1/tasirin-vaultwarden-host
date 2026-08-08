# Tasirin Vaultwarden Host

[![Build](https://github.com/tasirin1/tasirin-vaultwarden-host/actions/workflows/build-apk.yml/badge.svg)](https://github.com/tasirin1/tasirin-vaultwarden-host/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-vaultwarden-host)](https://github.com/tasirin1/tasirin-vaultwarden-host/releases)

Menjalankan server **Vaultwarden** (Bitwarden-compatible, Rust) langsung di
Android, dibangun otomatis lewat **GitHub Actions**. Mendukung **Android 5.0
(API 21) ke atas** — termasuk STB/TV box 32-bit (ARM `armeabi-v7a`).

Repo ini **bukan cuma README**: dokumen ini juga panduan pengelolaan untuk
manusia maupun AI (lihat [Panduan pengelolaan repo](#panduan-pengelolaan-repo-untuk-manusia--ai)).

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

Bagian ini untuk **manusia maupun AI** yang ingin memahami, mengubah, atau
mengelola repository ini dengan benar.

## Struktur repository

```
.
├── .github/workflows/build-apk.yml   # CI: resolve versi → build binary → build APK → release
├── app/build.gradle                  # targetSdk 28, minSdk 21, signing via property, R8
├── app/src/main/
│   ├── AndroidManifest.xml           # permission, activity/service/receiver, TV (touchscreen opsional)
│   ├── assets/certs/github-chain.pem # trust anchor TLS GitHub untuk Android 5/6 (TLS lama)
│   └── java/com/tasirin/vaultwardenhost/
│       ├── MainActivity.java         # UI utama, pengaturan, auto-update check, PIN, export/import
│       ├── ServerService.java        # inti: start/stop proses, health+restart, log, TLS, ControlServer
│       ├── Updater.java              # cek versi GitHub, unduh binary/web-vault + SHA-256
│       ├── ControlServer.java        # status web ringan (JSON + log SSE) di port+1
│       ├── TgBot.java / TgBackup.java / TgBotReceiver.java  # remote & backup Telegram
│       ├── TlsCert.java / HttpsCompat.java                  # sertifikat self-signed
│       ├── LogActivity.java          # log realtime layar penuh (cari/simpan/bagikan)
│       ├── BootReceiver.java / AlarmReceiver.java           # auto-start boot & jadwal backup
│       └── FileShareProvider.java    # content provider (install cert / restore file)
└── gradle wrapper                    # build via ./gradlew (CI saja, lihat aturan)
```

## Arsitektur ringkas

- **MainActivity** menyimpan pengaturan ke `SharedPreferences` (`vw_prefs`),
  lalu memanggil `ServerService.start(this)`.
- **ServerService.startServer()** (di worker thread):
  1. `ensureBinary()` → `resolveBinary()`: pakai (a) binary update terbaru di
     cache internal (`KEY_UPDATE_VERSION`), (b) binary user di folder data
     (disalin ke internal), (c) unduh dari release, (d) bundled fallback.
  2. `ProcessBuilder` dengan env `DATA_FOLDER`, `ROCKET_ADDRESS/PORT/WORKERS`,
     `ADMIN_TOKEN`, `WEB_VAULT_ENABLED/FOLDER`, `ROCKET_TLS`, `RUST_LOG`, `DOMAIN`.
  3. `healthTick` → `checkHealthOnce()` (GET `/alive`); restart backoff
     `{2,5,10,20,40}s` maks 5×.
  4. `ControlServer` di `port+1` (auto fallback sampai +10).
- **Updater**: `latestVersion()` (cache 15 mnt, fallback saat rate-limit/TLS),
  `tryUpdate()`/`downloadBinary()` (verifikasi SHA-256), `updateWebVault()`
  (butuh ≥150 MB sisa storage, penanda `KEY_WV_FROM` agar tidak unduh ulang).
- **Kunci SharedPreferences** (`vw_prefs`): `data_dir`, `port`, `https`,
  `admin_token`, `auto_start`, `update_version`, `auto_update_binary`,
  `auto_update_webvault`, `auto_restart_update`, `tg_token`, `tg_chat`,
  `tg_auto`, `tg_backup_on_start`, `tg_pass`, `tg_full`, `pin_hash`, `pin_on`,
  `wv_from_version`, `tg_notified_version`, `advanced_open`.

## Aturan pengembangan

1. **Build HANYA via GitHub Actions** — jangan build lokal
   (`./gradlew`, apktool, dsb). Build lokal butuh SDK/NDK/Rust khusus dan
   hasilnya tidak mewakili release; semua perubahan dikirim sebagai commit +
   push, lalu workflow yang membangun.
2. **Bahasa**: kode, komentar, pesan UI, dan commit memakai **Bahasa Indonesia**.
3. **Gaya commit**: `feat:` / `fix:` / `docs:` / `chore:` + deskripsi singkat
   (contoh di `git log`). Satu commit satu tujuan logis; sertakan penjelasan
   di body bila perlu.
4. **Jangan menaikkan `targetSdk` ≥ 29** tanpa solusi eksekusi binary:
   Android 10+ memblokir `execve` dari app home untuk targetSdk ≥ 29 (W^X).
   Naik ke 33 butuh binary dipindah keluar `getFilesDir()` (mis. folder data).
5. **Jangan menambah ABI lain** — repo ini sengaja `armeabi-v7a` saja
   (STB 32-bit). Menambah arm64 membuat APK/binary membengkak dan menambah
   waktu build.
6. **Jangan membundel binary/web-vault ke APK** — inti desain: APK tetap
   kecil (~0,1 MB); keduanya diunduh dari release.
7. **Jaga kompatibilitas Android 5 (API 21)**: hindari API ≥ 21 tanpa fallback,
   jangan `NetworkOnMainThreadException` (semua tugas jaringan di thread),
   UI harus bisa dinavigasi **D-pad** (setiap kontrol berfokus:
   `nextFocusUp/Down`, background `@drawable/item_focus_bg`).
8. **UI 2 mode**: layar sentuh HP dan remote TV — ukuran tombol cukup besar,
   tidak memakai gesture yang butuh sentuhan presisi.

## Cara memicu build & release

- **Push ke `main`** → workflow `build-apk.yml` jalan otomatis.
- **Manual**: GitHub → Actions → *Build Tasirin Vaultwarden Host APK* →
  *Run workflow* (atau `gh workflow run build-apk.yml`).
- **Jadwal**: tiap 6 jam (`cron '0 */6 * * *'`); job `resolve` membandingkan
  versi terbaru Vaultwarden — bila release untuk versi itu sudah ada, seluruh
  pipeline di-skip.
- Hasil: release `v<versi-vaultwarden>` di-*refresh* (dihapus & dibuat ulang)
  berisi 5 asset: APK signed, `vaultwarden-armeabi-v7a` + `.sha256`,
  `web-vault.zip` + `.sha256`.
- **Jangan edit asset release secara manual** — selalu lewat workflow.

## Alur pipeline (build-apk.yml)

1. **resolve** — ambil `tag` release terbaru `dani-garcia/vaultwarden`.
2. **build-binary** — clone source Vaultwarden, terapkan **patch DNS Android**
   (nonaktifkan `hickory`/`ndk-context` di `vaultwarden/src/http_client.rs` —
   anchor `impl CustomDnsResolver { fn new()`), cross-compile
   `armeabi-v7a` (NDK 25, target `armv7-linux-androideabi`), strip, upload.
3. **build-apk** — unduh binary, ambil **web-vault dari Docker digest resmi**
   (`vaultwarden/web-vault@sha256:...` dari `docker/DockerSettings.yaml`),
   tulis `app/src/main/assets/vw_version.txt`, `assembleDebug` + `assembleRelease`
   (signed bila secrets ada), publish release, upload artifact APK.

## Secrets yang dibutuhkan (Settings → Secrets and variables → Actions)

| Secret               | Fungsi                              |
|----------------------|-------------------------------------|
| `KEYSTORE_BASE64`    | File `keystore.jks` di-encode base64 |
| `KEYSTORE_PASSWORD`  | Password keystore                   |
| `KEY_ALIAS`          | Alias kunci signing                 |
| `KEY_PASSWORD`       | Password kunci alias                |

**Jangan pernah commit keystore atau password** (`.gitignore` sudah mengecualikan
`keystore.jks`). Bila keystore hilang, APK rilis lama tidak bisa di-update
seamless (beda signature) — backup keystore di tempat aman.

## Menambah/mengubah fitur — file mana yang disentuh

- **Pengaturan baru (checkbox/input)** → `activity_main.xml` (layout, lengkapi
  `nextFocusUp/Down`) + `MainActivity.java` (field, `setChecked`, listener,
  simpan ke prefs) + konstanta `KEY_*` di `ServerService.java`.
- **Perilaku server (start/stop/env/health)** → `ServerService.java`.
- **Update/unduhan (versi, URL, checksum)** → `Updater.java` (URL asset di-host
  repo ini; versi diambil dari `dani-garcia/vaultwarden`).
- **Status web/API JSON** → `ControlServer.java`.
- **Telegram bot/backup** → `TgBot.java`, `TgBackup.java`.
- **UI log** → `LogActivity.java` + `activity_log.xml`.
- **Versi app** → jangan ubah manual; `app/build.gradle` memakai tanggal build
  UTC (`yyyy.MM.dd` / `yyyyMMdd`) — konsisten dengan CI.

## Verifikasi setelah build

```bash
gh run watch <run-id> --exit-status
gh run view <run-id> --json status,conclusion
gh release view v<versi> --json assets -q '.assets[].name'
```

Pastikan conclusion `success` dan release punya 5 asset. Verifikasi **favicon
vault** manual di perangkat: buka web vault → Vault → item ber-URL → cek log
tidak ada `panic 'android context was not initialized'` / `500` pada
`/icons/...`. Bila muncul, patch DNS di workflow perlu disesuaikan (anchor
`CustomDnsResolver::new` mungkin berubah di upstream).

## Build ulang manual (ringkas)

- **Actions → Build Tasirin Vaultwarden Host APK → Run workflow**
  (atau otomatis tiap push ke `main`, atau tiap 6 jam).

## Lisensi

- Wrapper app: GPL-3.0 (lihat `LICENSE`).
- Vaultwarden: AGPL-3.0 (lihat `LICENSE.vaultwarden`; sumber:
  https://github.com/dani-garcia/vaultwarden).
