# Panduan pengelolaan repo (untuk AI)

> ⚠ **ATURAN NO. 1 — DILARANG KERAS BUILD DI LOKAL.**
> AI yang mengelola repo ini **tidak boleh** menjalankan `./gradlew`,
> `gradle`, `apktool`, `javac`/`kotlinc`, emulator, `adb install`, atau
> perintah build/test/lint Android apa pun di mesin lokal — tanpa kecuali,
> bahkan "cuma cek sebentar" atau "cuma unit test". Build lokal butuh
> SDK/NDK/Rust/toolchain khusus dan hasilnya **tidak mewakili release**.
> Satu-satunya cara build, test, lint, dan rilis: **push ke `main`**,
> lalu workflow GitHub Actions yang mengerjakan semuanya.
> Verifikasi lokal yang diizinkan hanya yang tanpa toolchain Android:
> membaca kode, `grep`/`rg`, `git diff`/`git log`, dan parse XML polos.
> Pelanggaran aturan ini = pengerjaan dianggap gagal.

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
├── shim/getrandom_shim.c             # shim getrandom LD_PRELOAD untuk STB kernel lama
├── app/build.gradle.kts              # Kotlin DSL: targetSdk 28, minSdk 21, versionCode yyyyMMddHH, R8, signing via -P
├── app/proguard-rules.pro            # Aturan R8 minimal (Java murni, tanpa refleksi sendiri)
├── app/src/main/
│   ├── AndroidManifest.xml           # permission, activity/service/receiver, TV (touchscreen opsional)
│   ├── assets/certs/github-chain.pem # trust anchor TLS GitHub untuk Android 5/6 (TLS lama)
│   ├── res/layout/ (+layout-land)      # activity_main.xml (ringkas, portrait + landscape dua kolom) + activity_settings.xml + main_card_*.xml
│   ├── res/drawable/                 # bg_hero, bg_btn_*, bg_info_box, chip status, item_focus_bg
│   ├── res/values/ (+night, sw600dp) # warna (sinkron terang/gelap), gaya, string, dimensi
│   └── java/com/tasirin/vaultwardenhost/
│       ├── MainActivity.java         # layar awal ringkas: brand kecil, info versi, status, Start/Stop, log realtime, titik tiga
│       ├── SettingsActivity.java     # semua pengaturan (folder, port, PIN, Telegram, pemeliharaan), dibuka via titik tiga
│       ├── ServerService.java        # inti: start/stop proses, health+restart, log, TLS
│       ├── Updater.java              # cek versi GitHub, unduh binary/web-vault + SHA-256 (kuncian wajib x.y.z)
│       ├── TgBot.java / TgBackup.java / TgBotReceiver.java  # remote & backup Telegram
│       ├── AutoUpdate.java           # cek update berkala + notifikasi versi baru
│       ├── KernelCompat.java         # deteksi kernel lama + pasang shim getrandom via LD_PRELOAD
│       ├── PinCrypto.java / PinGate.java  # PIN PBKDF2+salt (format PBKDF2$...) + lockout brute-force
│       ├── TlsCert.java / HttpsCompat.java                  # sertifikat self-signed + trust anchor Android 5/6
│       ├── LogActivity.java / LogExport.java  # log realtime layar penuh (cari/simpan/bagikan)
│       ├── StoragePerm.java          # izin storage / All files access
│       ├── BootReceiver.java / AlarmReceiver.java           # auto-start boot & jadwal backup
│       └── FileShareProvider.java    # content provider (install cert / restore file)
├── app/src/test/                     # 12 kelas test JVM (junit4): Updater, ServerService,
                                      # TgBot, TgBackup, PinCrypto, KernelCompat, Util,
                                      # TlsCert, StoragePerm, LogActivity, FileShareProvider,
                                      # SettingsActivity — jalan di CI
└── gradle wrapper                    # HANYA dipakai CI; AI dilarang menjalankannya (lihat Aturan No. 1)
```

## Arsitektur ringkas

- **MainActivity** (layar awal) hanya membaca pengaturan dari
  `SharedPreferences` (`vw_prefs`), menampilkan status + log realtime, dan
  memanggil `ServerService.start/stop`. Tidak ada input pengaturan di sini.
- **SettingsActivity** (titik tiga ⋮ → Settings) pemilik semua input
  pengaturan: menyimpan ke `vw_prefs` saat diubah. Status buka PIN dibagi
  dengan MainActivity (`pinBaruSajaDibuka`/`catatPinDibuka`) agar tidak
  diminta dua kali.
- **ServerService.startServer()** (di worker thread):
  1. `ensureBinary()` → `resolveBinary()`: pakai (a) binary update terbaru di
     cache internal (`KEY_UPDATE_VERSION`), (b) binary user di folder data
     (disalin ke internal), (c) unduh dari release, (d) bundled fallback.
  2. `ProcessBuilder` dengan env `DATA_FOLDER`, `ROCKET_ADDRESS/PORT/WORKERS`,
     `ADMIN_TOKEN`, `WEB_VAULT_ENABLED/FOLDER`, `ROCKET_TLS`, `RUST_LOG`, `DOMAIN`.
  3. `healthTick` → `checkHealthOnce()` (GET `/alive`); restart backoff
     `{2,5,10,20,40}s` maks 5×.
- **Updater**: `latestVersion()` (cache 15 mnt, fallback saat rate-limit/TLS),
  `tryUpdate()`/`downloadBinary()` (verifikasi SHA-256), `updateWebVault()`
  (butuh ≥150 MB sisa storage, penanda `KEY_WV_FROM` agar tidak unduh ulang).
  Semua jalur unduh memakai versi target (`versiTargetBinary()`/
  `versiTargetWebVault()` = kuncian `bin_pin_version`/`wv_pin_version` bila
  diisi, else terbaru); varian eksplisit `tryUpdateVersi()`/
  `downloadBinaryVersi()`/`updateWebVaultVersi()` selalu pasang versi
  diminta termasuk downgrade, dan versi terkunci tak fallback diam-diam
  ke latest bila asset-nya 404.
- **Kunci SharedPreferences** (`vw_prefs`): `data_dir`, `port`, `https` (selalu true, HTTPS-only),
  `mode_sederhana` (default aktif, UI lokal ikut diimpor), `wizard_selesai` (UI lokal, tak diimpor),
  `admin_token`, `auto_start`, `update_version`, `auto_update_binary`,
  `auto_update_webvault`, `auto_restart_update`, `tg_token`, `tg_chat`,
  `tg_auto` (jadwal + saat Start, selalu full), `tg_pass`, `pin_hash`, `pin_on`,
  `wv_from_version`, `tg_notified_version`, `advanced_open`,
  `bin_patch_rev` (revisi patch binary; paksa unduh ulang bila CI perbaiki
  binary tanpa ganti versi Vaultwarden),
  `bin_pin_version`/`wv_pin_version` (kuncian versi binary/web-vault pilihan
  user; kosong = ikuti terbaru; ikut export/import config).

## Aturan pengembangan

1. **DILARANG build/test/lint di lokal — SELALU via GitHub Actions.**
   Larangan mencakup `./gradlew` (perintah apa pun: `assemble*`, `lint*`,
   `test*`), `apktool`, `adb`, emulator, dan instalasi SDK/NDK di mesin
   kerja. Alur wajib: edit → `git commit` → `git push origin main`
   (lihat "Verifikasi setelah build"). Jangan memantau build dengan
   `gh run watch` kecuali pengguna eksplisit menyuruh memantau.
   Tidak ada pengecualian.
2. **Bahasa**: kode, komentar, pesan UI, dan commit memakai **Bahasa Indonesia**.
3. **Gaya commit**: `feat:` / `fix:` / `docs:` / `chore:` / `perf:` + deskripsi
   singkat (contoh di `git log`). Satu commit satu tujuan logis. Setiap push
   ke `main` yang menyentuh kode memicu full rebuild (~15 menit), jadi gabungkan
   perubahan kecil dalam satu commit (commit dokumen `*.md` saja dilewati CI).
4. **Jangan menaikkan `targetSdk` ≥ 29** tanpa solusi eksekusi binary:
   Android 10+ memblokir `execve` dari app home untuk targetSdk ≥ 29 (W^X).
5. **Jangan menambah ABI lain** — repo ini sengaja `armeabi-v7a` saja
   (STB 32-bit). Menambah arm64 membuat APK/binary membengkak dan menambah
   waktu build.
6. **Jangan membundel binary/web-vault ke APK, dan jangan commit
   binary/APK/shim/zip ke repo** — inti desain: APK tetap kecil (~0,1 MB);
   binary & web-vault diunduh dari release dan diverifikasi SHA-256.
7. **Jaga kompatibilitas Android 5 (API 21)**: hindari API ≥ 21 tanpa fallback,
   jangan `NetworkOnMainThreadException` (semua tugas jaringan di thread),
   UI harus bisa dinavigasi **D-pad** (setiap kontrol berfokus:
   `nextFocusUp/Down`, background `@drawable/item_focus_bg`).
8. **UI 2 mode**: layar sentuh HP dan remote TV — ukuran tombol cukup besar,
   tidak memakai gesture yang butuh sentuhan presisi. Layar awal
   (`MainActivity`) wajib tetap ringkas (status, Start/Stop, log, simpan
   .txt, titik tiga); kontrol baru selalu masuk `SettingsActivity`.
9. **Versi app jangan diubah manual** — `app/build.gradle.kts` memakai waktu
   build UTC (`versionName yyyy.MM.dd`, `versionCode` menit-epoch UTC);
   konsisten dengan CI. Menit-epoch monotonik dan unik per menit agar dua push
   dalam jam yang sama tetap beda versionCode (sideload versi sama diabaikan).
10. **Format path vektor harus `0.x`** — tulis `0.9`/`-0.9`, bukan `.9`/`-.9`
    (lint `InvalidVectorPath` menggagalkan build + crash di sebagian HP).
11. **Jangan mengubah workflow CI atau asset release manual** — rilis hanya
    lewat workflow; jangan edit asset release lewat web/UI manual.
12. **Setiap selesai perbaikan langsung commit + push ke `main`** — jangan
    menunda push, jangan menunggu perintah, jangan menumpuk perubahan di
    working tree. Alur wajib tiap selesai satu tujuan logis: `git add` →
    `git commit` → `git push origin main` (lihat aturan #1 dan
    "Verifikasi setelah build"). Pengecualian hanya bila pengguna eksplisit
    meminta menahan push.
13. **Jangan pantau build kecuali disuruh** — setelah `push`, JANGAN
    menjalankan `gh run watch`, `gh run view`, atau `gh release view`
    kecuali pengguna eksplisit menyuruh memantau/memverifikasi. Push saja,
    lalu selesai dan laporkan. Ini menghemat waktu polling CI (~15 menit)
    dan token. Pantau hanya bila kalimat pengguna memuat kata pantau/
    monitor/cek build/tunggu/verifikasi rilis.

## Alur build & rilis (CI, build-apk.yml)

Pipeline 4 job. Pemicu: `push` ke `main` (build + terbitkan ulang rilis;
commit dokumen `*.md`/`.gitignore` saja dilewati via `paths-ignore`),
`schedule` tiap 6 jam (cek versi upstream; skip bila rilis untuk tag
tersebut sudah ada), dan `workflow_dispatch` (manual). `concurrency:
vw-release` mencegah dua run berebut rilis yang sama; cache cargo dipakai
ulang antar run:

1. **resolve** — ambil `tag` release terbaru `dani-garcia/vaultwarden`.
2. **build-binary** — clone source Vaultwarden, terapkan **patch DNS Android**
   (nonaktifkan `hickory`/`ndk-context` di `vaultwarden/src/http_client.rs` —
   anchor `impl CustomDnsResolver { fn new()`), cross-compile `armeabi-v7a`
   (NDK 25, target `armv7-linux-androideabi`), strip, upload artifact.
3. **build-shim** — kompilasi `shim/getrandom_shim.c` (NDK, API 21, armv7,
   detik) + uji interposisi `LD_PRELOAD` di host, publish asset
   `libgetrandom-shim-armeabi-v7a.so` + `.sha256` di rilis yang sama.
   Shim ini dipakai app (via `LD_PRELOAD`) agar binary terbaru tetap jalan
   di kernel STB lama (getrandom/EINVAL).
4. **build-apk** — unduh binary + shim, ambil **web-vault dari Docker digest resmi**
   (`vaultwarden/web-vault@sha256:...` dari `docker/DockerSettings.yaml`),
   tulis `app/src/main/assets/vw_version.txt`, `assembleDebug` +
   `lintDebug` + `testDebugUnitTest` + `assembleRelease` (signed bila secrets
   ada), cek ukuran APK, publish release, upload artifact APK.

Release GitHub bernama `v<versi-vaultwarden>` berisi 7 asset: APK signed,
`vaultwarden-armeabi-v7a` + `.sha256`, `libgetrandom-shim-armeabi-v7a.so` +
`.sha256` (shim `LD_PRELOAD` untuk STB kernel lama, dibangun dari `shim/`),
`web-vault.zip` + `.sha256`.
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

- **Build lokal dilarang total** — bila tergoda menjalankan `./gradlew`
  "hanya untuk memastikan", jangan. Kepastiannya didapat dari `gh run watch`
  setelah push. Lihat Aturan No. 1.
- **`targetSdk 28` bukan kelalaian** — Android 10+ memblokir eksekusi binary
  dari app home untuk targetSdk ≥ 29 (perilaku W^X). Lihat aturan #4.
- **APK tidak memuat binary server** — `assets/bin/` diisi CI saat build;
  di repo hanya `assets/certs/` dan (saat CI) `vw_version.txt`. Jangan commit
  binary (puluhan MB) ke repo.
- **Layar awal vs Settings** — `MainActivity` (ringkas) hanya membaca prefs;
  semua input pengaturan ada di `SettingsActivity`. Jangan menambah kontrol
  ke layar awal.
- **Patch Android (`http_client.rs`)** rapuh terhadap perubahan upstream —
  workflow menambal DNS (`CustomDnsResolver::new()` → DNS sistem, tanpa
  `ndk-context`/JavaVM) dan TLS verifier (reqwest memakai
  `rustls-platform-verifier` yang wajib init dari JavaVM; binary standalone
  diganti verifier webpki + bundel root Mozilla via `tls_certs_only`). Saat
  Vaultwarden mengubah file ini, workflow gagal di langkah patch (anchor
  `impl CustomDnsResolver { fn new()` / rantai `Client::builder()`): sesuaikan
  polanya; bila `ndk-context`/platform-verifier sudah tidak dipakai, patch
  terkait bisa dihapus.
- **Unit test**: 12 kelas (`Updater`, `ServerService`, `TgBot`, `TgBackup`,
  `PinCrypto`, `KernelCompat`, `Util`, `TlsCert`, `StoragePerm`, `LogActivity`,
  `FileShareProvider`, `SettingsActivity`) menguji logika murni; tambahkan
  test untuk logika murni baru (versi, path, parse, crypto) — jangan test
  yang butuh Android runtime/network. Test hanya jalan di CI, bukan lokal.

## Pemetaan fitur → file

- **Pengaturan baru (checkbox/input)** → kartu esensial (Folder, Port, Admin Token, PIN, cadangan cepat)
  ada di `main_card_server.xml` (selalu tampil, termasuk mode sederhana); sisanya di
  `main_card_*.xml` yang sesuai dalam panel Lanjutan
  (`activity_settings.xml` hanya kerangka + hero; lengkapi `nextFocusUp/Down`) +
  `SettingsActivity.java` (field, `setChecked`, listener, simpan ke prefs) +
  konstanta `KEY_*` di `ServerService.java`. Layar awal (`MainActivity.java` +
  `activity_main.xml` + `layout-land`) tetap ringkas: brand kecil, info
  versi, banner status full-width, Start full-width sticky, log realtime,
  simpan .txt, titik tiga (tanpa tombol buka-log). Kedua orientasi wajib
  mendefinisikan ID yang sama persis agar `MainActivity` tak berubah.
- **Percantik tampilan (tanpa logika)** → `colors.xml` (+ `values-night`,
  wajib sinkron) + `styles.xml` + `drawable/bg_*` + string baru di
  `strings.xml`; jangan ubah ID/`nextFocusUp/Down` di layout.
- **Perilaku server (start/stop/env/health)** → `ServerService.java`.
- **Update/unduhan (versi, URL, checksum)** → `Updater.java` (URL asset di-host
  repo ini; versi diambil dari `dani-garcia/vaultwarden`; STB kernel lama
  otomatis memakai shim getrandom via `KernelCompat.java` + `LD_PRELOAD`).
- **Telegram bot/backup** → `TgBot.java`, `TgBackup.java`.
- **UI log** → `LogActivity.java` + `activity_log.xml`.

## Verifikasi setelah build

```bash
gh run view <run-id> --json status,conclusion
gh release view v<versi> --json assets -q '.assets[].name'
```

Jangan menjalankan `gh run watch` kecuali pengguna eksplisit menyuruh
memantau (aturan #13: push-andai saja, hemat token/polling).
Pastikan conclusion `success` dan release punya 7 asset. Ini satu-satunya
cara verifikasi yang sah (tidak ada verifikasi lokal). Verifikasi **favicon
vault** manual di perangkat: buka web vault → Vault → item ber-URL → cek log
tidak ada panic (`android context was not initialized` /
  `Expect rustls-platform-verifier to be initialized`) / `500` pada
`/icons/...`. Bila muncul, patch DNS di workflow perlu disesuaikan.
