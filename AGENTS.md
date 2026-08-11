# Panduan pengelolaan repo (untuk AI)

Baca file ini **SEBELUM** mengubah, memperbaiki, atau mengelola repository ini.
Panduan lengkap untuk pengguna ada di `README.md` (Indonesia) dan
`README.en.md` (Inggris) — jaga keduanya sinkron dengan fitur terbaru.
Riwayat perubahan dicatat di `CHANGELOG.md` (update manual per commit penting).

## Struktur repository

```
.
├── .github/workflows/build-apk.yml  # CI: resolve versi → build binary → build APK → release
├── AGENTS.md                         # Panduan pengelolaan ini
├── CHANGELOG.md                      # Riwayat perubahan per rilis (update manual)
├── app/build.gradle.kts              # Kotlin DSL: targetSdk 28, minSdk 21, R8, signing via -P
├── app/proguard-rules.pro            # Aturan R8 minimal (Java murni, tanpa refleksi sendiri)
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
├── app/src/test/                     # Unit test JVM (junit4) — jalan di CI
└── gradle wrapper                    # build via ./gradlew (CI saja untuk rilis)
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
3. **Gaya commit**: `feat:` / `fix:` / `docs:` / `chore:` / `perf:` + deskripsi
   singkat (contoh di `git log`). Satu commit satu tujuan logis.
4. **Jangan menaikkan `targetSdk` ≥ 29** tanpa solusi eksekusi binary:
   Android 10+ memblokir `execve` dari app home untuk targetSdk ≥ 29 (W^X).
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
9. **Versi app jangan diubah manual** — `app/build.gradle.kts` memakai tanggal
   build UTC (`yyyy.MM.dd` / `yyyyMMdd`); konsisten dengan CI.

## Alur build & rilis (CI, build-apk.yml)

Pipeline 3 job; semua di-skip bila release untuk versi Vaultwarden terbaru
sudah ada (job `resolve` membandingkan tag):

1. **resolve** — ambil `tag` release terbaru `dani-garcia/vaultwarden`.
2. **build-binary** — clone source Vaultwarden, terapkan **patch DNS Android**
   (nonaktifkan `hickory`/`ndk-context` di `vaultwarden/src/http_client.rs` —
   anchor `impl CustomDnsResolver { fn new()`), cross-compile `armeabi-v7a`
   (NDK 25, target `armv7-linux-androideabi`), strip, upload artifact.
3. **build-apk** — unduh binary, ambil **web-vault dari Docker digest resmi**
   (`vaultwarden/web-vault@sha256:...` dari `docker/DockerSettings.yaml`),
   tulis `app/src/main/assets/vw_version.txt`, `assembleDebug` +
   `lintDebug` + `testDebugUnitTest` + `assembleRelease` (signed bila secrets
   ada), cek ukuran APK, publish release, upload artifact APK.

Release GitHub bernama `v<versi-vaultwarden>` berisi 5 asset: APK signed,
`vaultwarden-armeabi-v7a` + `.sha256`, `web-vault.zip` + `.sha256`.
**Jangan edit asset release secara manual** — selalu lewat workflow.

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

## Yang sering membingungkan AI

- **`targetSdk 28` bukan kelalaian** — Android 10+ memblokir eksekusi binary
  dari app home untuk targetSdk ≥ 29 (perilaku W^X). Lihat aturan #4.
- **APK tidak memuat binary server** — `assets/bin/` diisi CI saat build;
  di repo hanya `assets/certs/` dan (saat CI) `vw_version.txt`. Jangan commit
  binary (puluhan MB) ke repo.
- **Patch DNS** rapuh terhadap perubahan upstream — saat Vaultwarden mengubah
  `http_client.rs`, workflow akan gagal di langkah patch. Periksa anchor
  `impl CustomDnsResolver { fn new()` dan sesuaikan polanya; bila `ndk-context`
  sudah tidak dipakai, patch bisa dihapus.
- **`ControlServer` bukan web vault** — itu status web ringan (JSON + SSE)
  di port `port+1`; web vault asli dilayani binary Vaultwarden di port utama.
- **Unit test**: `Updater.normVersion()` adalah fungsi murni yang diuji;
  tambahkan test untuk logika murni baru (versi, path, parse) — jangan test
  yang butuh Android runtime/network.

## Pemetaan fitur → file

- **Pengaturan baru (checkbox/input)** → `activity_main.xml` (layout, lengkapi
  `nextFocusUp/Down`) + `MainActivity.java` (field, `setChecked`, listener,
  simpan ke prefs) + konstanta `KEY_*` di `ServerService.java`.
- **Perilaku server (start/stop/env/health)** → `ServerService.java`.
- **Update/unduhan (versi, URL, checksum)** → `Updater.java` (URL asset di-host
  repo ini; versi diambil dari `dani-garcia/vaultwarden`).
- **Status web/API JSON** → `ControlServer.java`.
- **Telegram bot/backup** → `TgBot.java`, `TgBackup.java`.
- **UI log** → `LogActivity.java` + `activity_log.xml`.

## Verifikasi setelah build

```bash
gh run watch <run-id> --exit-status
gh run view <run-id> --json status,conclusion
gh release view v<versi> --json assets -q '.assets[].name'
```

Pastikan conclusion `success` dan release punya 5 asset. Verifikasi **favicon
vault** manual di perangkat: buka web vault → Vault → item ber-URL → cek log
tidak ada `panic 'android context was not initialized'` / `500` pada
`/icons/...`. Bila muncul, patch DNS di workflow perlu disesuaikan.
