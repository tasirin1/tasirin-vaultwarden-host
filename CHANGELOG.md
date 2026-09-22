# Changelog

Semua perubahan penting dicatat di sini. Format mengikuti
[Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/). Versi aplikasi
mengikuti tanggal build UTC (`yyyy.MM.dd`); release GitHub mengikuti versi
Vaultwarden (`v<versi>`). APK, binary, dan web-vault terbaru selalu ada di
[GitHub Releases](https://github.com/tasirin1/tasirin-vaultwarden-host/releases).

## [Belum rilis] — Tampilan utama, backup Telegram & efisiensi

### Ditambahkan
- **Layar awal sederhana**: hanya status server, tombol Start/Stop, log
  realtime, dan tombol Simpan .txt. Semua pengaturan (folder data, port,
  HTTPS, PIN, Telegram, pemeliharaan, sertifikat, export/import) pindah ke
  layar **Settings** lewat tombol **titik tiga (⋮)** — ramah remote TV
  (D-pad). Tombol buka-log layar penuh dihapus dari awal (log penuh tetap
  ada di Settings); brand diperkecil dan kotak info versi ala Settings
  ditampilkan di bawah brand.
- **Tampilan utama baru**: hero gradien brand (ikon + tagline), kartu Server
  dengan label aksi cepat & kotak info, tombol utama berisi, palet
  terang/gelap diselaraskan — ID dan navigasi D-pad tidak berubah.

### Diubah
- **Backup/restore terpadu**: duplikasi logika backup/restore
  dikonsolidasikan ke `TgBackup` (dipakai tombol UI & perintah bot).
- **Perintah `/log` hemat**: pakai `tailLog` tanpa salinan buffer penuh.
- **Efisiensi CPU/RAM/I-O**: kurangi alokasi dan I/O berlebih di semua modul.
- **Runner CI dikunci** ke `ubuntu-24.04` agar bebas notice migrasi.

### Diperbaiki
- **Warning linker tak lagi mengotori versi terpasang**: di STB kernel lama baris pertama output `binary --version` adalah `WARNING: linker: ... unsupported flags DT_FLAGS_1` (tidak fatal) sehingga versi terbaca sebagai teks warning (`versi: WARNING: linker...` di log Start) dan deteksi versi terpasang gagal (regex tak cocok). Baris noise kini disaring di `ServerService` + `Updater` (helper `isNoiseLinker` + test regresi) — log Start menampilkan versi asli; saringan log realtime yang sudah ada tak berubah.
- **HTTPS di STB kernel lama (`bad TLS ticketer: failed to get random bytes`)**: HTTP jalan tapi HTTPS selalu crash exit 1 — crate `getrandom` 0.2 (dipakai `ring` 0.17 ke ticketer TLS Rocket `rustls`) memanggil `syscall(SYS_getrandom)` mentah yang juga `EINVAL` di kernel 3.14 tanpa fallback (bukan `ENOSYS`), sehingga shim lama yang hanya mencegat `getrandom()` libc tidak berpengaruh di jalur TLS. Shim kini mencegat kedua simbol (`getrandom` + `syscall`) dan melayani keduanya dari `/dev/urandom`; uji interposisi CI mencakup kedua jalur. Pengguna cukup tekan **Cek Update** (shim baru terunduh otomatis + terverifikasi SHA-256) lalu Start lagi. App juga mengenali pesan ticketer sebagai masalah kernel lama (auto-restart dimatikan + saran, bukan salah sertifikat) + test regresi.
- **Shim getrandom selalu ditolak sebagai "tidak valid"**: batas ukuran minimum (`SHIM_MIN_BYTES` 4096 byte) lebih besar dari shim rilis yang asli (2712 byte, stripped) sehingga setiap unduhan yang checksum-nya cocok langsung dihapus dan Start gagal terus di STB kernel lama (mis. ZTE B860H) — batas kini 1024 byte + test regresi, dan pesan galat menyebut ukuran file agar mudah didiagnosis.
- **URL shim/binary diperbaiki (slash hilang = 404 terus)**: pembentukan URL asset versi kini memakai helper `binaryAssetUrl`/`shimAssetUrl` (`.../download/v<versi>/<asset>`) + test regresi; shim mendapat fallback `latest/download` seperti binary bila rilis versi belum memuat shim, dan saran koneksi kini mengenali frasa "belum tersedia" — sebelumnya STB selalu gagal pasang shim dengan pesan "belum tersedia di rilis v1.37.3" padahal asset sudah ada.
- **STB kernel lama kini jalan dengan Vaultwarden terbaru (shim getrandom)**:
  perangkat kernel <3.17 (mis. ZTE B860H Android 6) terdeteksi saat Start
  (`kernel 3.x | ... | channel legacy` di log); app memasang shim
  `libgetrandom-shim-armeabi-v7a.so` (terverifikasi SHA-256, via **Cek Update**
  / `/update` / otomatis saat Start) dan menjalankan binary dengan
  `LD_PRELOAD` — getrandom dilayani dari /dev/urandom (mekanisme teruji:
  flag bogus yang ditolak libc lolos dengan shim). Binary tak cocok tetap
  digagalkan cepat lewat smoke test `--version` (bukan restart buta); CI
  membangun + menguji interposisi + mempublish shim di setiap rilis (7 asset).
- **Crash kernel lama langsung dikenali**: panic `failed to generate random
  data` (binary Rust terbaru vs kernel STB Android 5/6, exit 101) kini
  menghentikan auto-restart seketika dengan saran binary legacy di log/status/
  Telegram — sebelumnya di-retry buta 5x lalu anti-loop. Warning linker
  `DT_FLAGS_1` yang tidak fatal ikut diredam dari log.
- **Resume HTTP 416 diperbaiki**: server menolak Range (file berubah/parsial
  lebih besar) kini memicu ulang dari nol otomatis di binary & web-vault —
  sebelumnya gagal terus dengan Range yang sama. Zip korup saat ekstrak
  (`invalid stored block lengths`) dilaporkan jujur + versi lama dipertahankan.
- **Deteksi DNS dibajak**: `github.com` yang resolve ke IP lokal/router
  (mis. `/192.168.100.1` + `ECONNREFUSED`) kini menampilkan saran portal
  login/DNS/hotspot yang spesifik, bukan sekadar timeout generik.
- **Perbaikan unduhan & CI**: checksum `.sha256` dicoba 2x dan tidak lagi
  menghapus file parsial bila gagal jaringan (bisa dilanjutkan); file `.tmp`
  dipertahankan antar-Start; pesan Telegram `/update` & `/webvault` memakai
  saran koneksi yang sama; workflow schedule kini benar-benar skip bila rilis
  sudah ada (`outputs.skip`) dan cache cargo dipakai ulang per versi.
- **Unduhan tahan putus**: binary & web-vault coba ulang 3x otomatis
  (sebelumnya binary langsung gagal sekali); galat `failed to connect to
  github.com` kini menampilkan saran aksi (cek internet/browser, tanggal & jam,
  hotspot/DNS, cara offline) di log & status; unduhan parsial tetap dilanjutkan
  via Range.
  **Pesan web-vault jujur**: unduhan yang belum selesai (3x timeout) kini
  melaporkan galat koneksi + saran dan mempertahankan file parsial untuk
  resume — sebelumnya salah tampil sebagai "checksum tidak cocok" dan parsial
  ikut dihapus sehingga unduhan ~35 MB mengulang dari nol.
- **Warning lint nol**: layout di-split ke `main_card_*.xml` (bebas
  TooManyViews) dan deprecation `javac` dibersihkan; derive PIN kini
  menangani checked exception agar kompilasi lolos.

## [Belum rilis] — Audit keamanan, bug & efisiensi

### Keamanan
- **Checksum fail-closed**: update binary/web-vault dibatalkan bila `.sha256`
  tidak ditemukan atau tidak cocok (sebelumnya dipasang tanpa verifikasi).
- **Binary manual terverifikasi**: SHA-256 bisa diisi di pengaturan; bila
  diisi harus cocok, bila kosong ada peringatan eksplisit di log.
- **PIN PBKDF2+salt** (format `PBKDF2$...`); hash lama otomatis dimigrasi
  saat login berhasil. Perintah bot berbahaya (`/stop`, `/update`,
  `/restore`) wajib diakhiri PIN bila PIN aktif.
- **Export config terenkripsi** (AES-GCM) bila password backup diisi; import
  bisa membaca `.json.enc`.
- **TLS end-entity**: cert baru `CA:FALSE` + EKU serverAuth (regenerasi
  otomatis via bump versi); pesan Telegram via POST terverifikasi;
  koneksi Telegram memakai trust anchor Android 5/6; batas koneksi &
  header di status web; `FileShareProvider` hanya melayani
  `tls/`/`backups`/internal; zip-slip restore file ditutup; backup
  checkpoint WAL dulu agar konsisten.

### Diperbaiki
- **Web-vault aman**: ekstrak ke folder sementara, versi lama baru diganti
  bila hasil valid (gagal ekstrak tidak lagi menghilangkan web UI).
- **Health check bocor**: callback dihentikan saat stop/destroy.
- **Detect versi**: timeout 15 detik di semua API, reader & proses ditutup.
- **Koneksi**: `disconnect()` dalam `finally` di semua unduhan/API.
- **Restore file** ikut memulihkan `tls/` + pengaturan seperti restore
  Telegram; **PIN** tidak mengunci saat pindah ke halaman Log (<60 detik).

### Efisiensi
- **Buffer tulis log** (flush tiap 16 KB, bukan per baris); **cache JSON
  status 10 detik**; info berat UI di worker thread + cache; throttle
  refresh log 500 ms; QR `setPixels` sekali; alarm bot inexact;
  **unduhan bisa dilanjutkan** (HTTP Range); cache enumerasi IP 5 detik;
  debounce jadwal bot saat mengetik token.

## [Belum rilis] — Perintah restore Telegram

### Ditambahkan
- **Perintah `/restore` Telegram**: `/restore` menampilkan info backup
  terakhir, `/restore YA` mengunduh + restore database (server dihentikan
  dulu, DB lama diamankan ke `db-backup-*-pre.sqlite3`, rollback bila hasil
  bukan SQLite valid). Identitas bot (token/chat/password/offset)
  dipertahankan agar bot tetap terhubung; logika restore dipindah ke
  `TgBackup.restoreFromZip()` agar dipakai tombol UI & bot.

### Diperbaiki
- **Validasi port**: Start ditolak bila port bukan 1-65535; prefs rusak
  otomatis jatuh ke `8088` (sebelumnya string mentah dikirim ke `ROCKET_PORT`
  lalu crash loop).
- **Cache update**: penanda versi binary/web-vault tidak lagi tertimpa string
  kosong saat API versi gagal (sebelumnya picu unduh ulang tiap Start).
- **Restart Telegram**: perintah `/restart` kini benar-benar start server
  walau sedang berhenti; offset polling disimpan per pesan (anti-spam).
- **Restore aman**: file mentah/zip wajib header SQLite, gagal → rollback
  ke backup `pre`; import pengaturan mempertahankan offset bot.
- **Status web**: endpoint `/api/*` butuh `?token=` bila Admin Token diisi.
- **Lainnya**: `humanBytes` dukung GB/TB, retensi backup tidak hapus export
  `app-config`, binary owner-executable.
- **Fallback unduh**: binary/web-vault pakai rilis terbaru repo bila URL versi
  spesifik 404 (jendela CI sedang buat ulang rilis).
- **Ronde 2**: `/update` Telegram tidak start-kan server yang berhenti,
  konfirmasi export config (sensitif), validasi import (marker + maks 512 KB
  + sanitasi port), hapus file pending hantu, auto-start aman Android 12+,
  regenerasi sertifikat kedaluwarsa, `concurrency` CI anti-race rilis.

## [2026-08-11] — Progress unduh, crash log viewer & status web kaya

### Ditambahkan
- **Progress unduh realtime**: chip status di layar utama menampilkan persen +
  ukuran saat mengunduh binary/web-vault (update manual, auto-update, atau
  Start pertama); polling UI dipercepat ke 500 ms selama unduhan.
- **Crash log viewer**: tombol **Crash** di halaman Log membuka dialog
  monospace berisi `crash-last.log` (bisa disalin); perintah Telegram
  **`/crashlog`** mengirim crash log terakhir (3500 karakter).
- **Rincian storage** di layar utama: ukuran DB, backup lokal (jumlah + total),
  web-vault, dan binary (di-cache 5 detik).
- **Status web diperkaya**: kartu baru Web Vault (versi + ukuran), DB (ukuran),
  dan Restart (riwayat); JSON `/api/status` kini menyertakan `dbHuman`,
  `wvHuman`, `binaryHuman`, `backupCount`, dan `restartHistory`.

### Diperbaiki
- **Lint bersih total (0 warning)**: semua string layout dipindahkan ke
  `strings.xml`, `setText` memakai resource (placeholder), `tgChat` diberi
  `inputType`, dan warning yang sengaja (trust-all TLS, wakelock, tombol TV,
  autofill) dinonaktifkan eksplisit dengan alasan di `app/build.gradle.kts`.

## [2026-08-11] — Koneksi mudah, anti-loop & lint bersih

### Ditambahkan
- **QR koneksi** (encoder QR mandiri, tanpa dependensi): tombol **QR** di layar
  utama menampilkan QR `http(s)://IP:port` untuk dipindai HP lain.
- **Riwayat restart** tampil di layar utama & Telegram `/status`.
- **Anti-loop restart**: 3× restart dalam 5 menit → auto-restart dimatikan
  (status + notifikasi Telegram).
- **Crash log**: ~100 baris log terakhir tersimpan ke `crash-last.log` (internal)
  saat crash/health gagal.
- **Backup Lokal** jadi `.zip` (DB + WAL/SHM) dengan retensi 10; restore
  menerima `.zip` maupun `.sqlite3` mentah.
- Perintah Telegram `/status` kini menampilkan ukuran DB, backup terakhir,
  dan riwayat restart.

### Diperbaiki
- **Lint bersih** (`abortOnError = true`): `Process.isAlive()` (API 26) diganti
  fallback `exitValue()` agar jalan di Android 5/6; `String.format` tanpa
  `Locale`; `getSystemService(Class)` (API 23); binary tidak lagi
  world-readable (`setReadable(ownerOnly=true)`).
- Unit test bertambah: decode QR via zxing (test-only), `extractTag`,
  `humanBytes`.

## [v1.37.1 — 2026-08-08] — Pematangan: update otomatis & panduan lengkap

### Ditambahkan
- **Restart otomatis setelah update** binary/web-vault (checkbox `auto_restart_update`).
- **Auto-update web vault** + perbandingan versi yang benar (tidak mengunduh
  ulang bila sudah terbaru — penanda `wv_from_version`).
- **Auto-update binary** dengan peringatan update di layar utama.
- **README jadi panduan lengkap** untuk pengguna & pengelola (manusia/AI):
  fitur, cara kerja, troubleshooting, struktur, arsitektur, aturan pengembangan.

### Diperbaiki
- Unduh binary tetap jalan saat API versi gagal (rate-limit/TLS).
- `startServer` dipindah ke worker thread (perbaikan
  `NetworkOnMainThreadException`).
- Cast `X509Certificate` untuk `getNotAfter` (compile error).
- Variabel `final` untuk lambda dialog update web-vault (compile error).

### Diubah
- APK tidak lagi membundel binary & web vault (~0,1 MB) — diunduh dari release
  saat Start pertama, dipakai ulang bila sudah ada.

## [2026-08-08] — Perbaikan CI & pematangan UI

### Ditambahkan
- Action CI dinaikkan ke Node 24 (checkout v5, setup-java v5, artifact v6/v7,
  cache v6, gradle v6) + retry curl saat resolve.
- Fokus ARM 32-bit murni: hapus flavor armv7, folder `src/armv7`, logika
  multi-ABI — APK & build lebih ramping.
- Desain ulang layar utama: minimal, chip status, tombol Start/Stop tunggal,
  panel Lanjutan collapsible, kartu & lebar konten untuk TV.
- Versi aplikasi mengikuti tanggal build (`versionName yyyy.MM.dd`,
  `versionCode yyyyMMdd`).

### Diperbaiki
- Panic Vaultwarden di Android — nonaktifkan DNS resolver hickory
  (`ndk-context`) + `extractBinary` otomatis ganti binary saat APK baru.
- Nama style tanpa titik (agar AAPT tidak mencari parent Tasirin).
- `highlightLog` yang hilang saat fitur log dipindah ke LogActivity.

### Diubah
- Log layar penuh (port dari download manager): tema terang/gelap + splash,
  fokus TV/D-pad, status web realtime (SSE).
- Profil rilis binary: strip + LTO + `opt-level=s`.

## [2026-08-05] — Remote Telegram, backup, TLS, dan ketahanan Android lama

### Ditambahkan
- **Remote kontrol Telegram bot**: perintah `/log /uptime /alive /update
  /webvault`, notifikasi update & storage, notifikasi privat di layar kunci.
- **Backup database ke Telegram** (manual + otomatis 24 jam via AlarmManager),
  enkripsi **AES-256-GCM**, restore dari Telegram, konfirmasi restore/revert.
- **HTTPS self-signed valid** (BasicConstraints CA:TRUE + KeyUsage) + tombol
  "Install Cert" langsung membuka installer CA Android + panduan.
- Health check `/alive`, kunci privasi layar, PIN + auto-lock, export/import
  config, batas ukuran log, kartu info jaringan, info versi web-vault di UI.

### Diperbaiki
- TLS GitHub di Android 5/6 — trust root CA tambahan (ISRG X1/Let's Encrypt,
  USERTrust ECC/RSA, DigiCert) via `HttpsCompat` + `AndroidCAStore`.
- Update web-vault & binary lebih tahan Android 5/6: TLS 1.2 eksplisit, cek
  storage, retry sekali, error detail, proteksi path ekstrak.
- Cegah loop start + default port diubah ke 8088.
- Sertifikat regenerasi saat IP berubah; cleanup backup; busy state; toggle
  password; peringatan storage; info ukuran DB.

### Diubah
- `sendMessage` async (cegah ANR), cache IP 3 detik, pause refresh UI,
  konstanta default terpusat (audit efisiensi).

## [2026-08-04] — Fondasi: Vaultwarden asli di Android, build CI penuh

### Ditambahkan
- Server **Vaultwarden asli** (binary Rust resmi) jalan di Android via
  `ProcessBuilder`; cek versi dari sumber resmi `dani-garcia/vaultwarden`.
- CI auto-rebuild tiap 6 jam saat Vaultwarden rilis versi baru.
- Update web-vault, auto-update check, battery exemption, backup/restore DB,
  admin token; build armv7 only (32-bit STB).
- `ROCKET_TLS` satu baris (format Rocket 0.5) agar HTTPS bisa jalan.
- Struktur X.509 diperbaiki (Name, AlgorithmIdentifier, Extensions) — cert
  self-signed valid & handshake TLS berhasil.
- `TlsCert` — bitString dipindah ke kelas `Der` + deklarasi `throws`
  (perbaikan compile APK).

### Diperbaiki
- Selalu pakai binary armeabi-v7a (32-bit) — APK hanya dibangun untuk ABI ini.
