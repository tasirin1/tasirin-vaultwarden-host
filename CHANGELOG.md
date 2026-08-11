# Changelog

Semua perubahan penting dicatat di sini. Format mengikuti
[Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/). Versi aplikasi
mengikuti tanggal build UTC (`yyyy.MM.dd`); release GitHub mengikuti versi
Vaultwarden (`v<versi>`). APK, binary, dan web-vault terbaru selalu ada di
[GitHub Releases](https://github.com/tasirin1/tasirin-vaultwarden-host/releases).

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
