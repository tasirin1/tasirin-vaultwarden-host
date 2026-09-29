# Tasirin Vaultwarden Host

[![Build](https://github.com/tasirin1/tasirin-vaultwarden-host/actions/workflows/build-apk.yml/badge.svg)](https://github.com/tasirin1/tasirin-vaultwarden-host/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-vaultwarden-host)](https://github.com/tasirin1/tasirin-vaultwarden-host/releases)

<p align="center"><b>&#127760; Bahasa: <a href="README.md">Indonesia</a> &middot; <a href="README.en.md">English</a> &middot; <a href="CHANGELOG.md">Changelog</a></b></p>

Server **Vaultwarden** (kompatibel Bitwarden) di Android — buat STB/TV box dan HP lama. **Android 5.0+**, ARM 32-bit (`armeabi-v7a`). APK ~0,1 MB; binary & web vault diunduh otomatis dan dicek SHA-256.

## Cara pakai

1. Unduh APK di [Releases](https://github.com/tasirin1/tasirin-vaultwarden-host/releases), install.
2. Buka app → **&#8942; → Settings**: ikuti wizard 3 langkah (Folder → Port → Start). Mode sederhana hanya menampilkan yang penting.
3. Tekan **Start** (unduhan pertama otomatis, ada progress).
4. Buka `https://<IP-HP>:<port>` di browser / app Bitwarden (Server URL).

Semua pengaturan ada di **Settings**; layar utama cuma status, Start/Stop, dan log.

## Tanpa internet

Unduh dari HP lain, taruh di folder data, tekan **Start**:

- `vaultwarden-armeabi-v7a` → folder data
- `libgetrandom-shim-armeabi-v7a.so` → folder yang sama (buat STB lama)
- `web-vault.zip` → ekstrak ke `web-vault/` (harus ada `index.html`)

## Fitur

Auto-update binary & web vault, backup terenkripsi (lokal + Telegram), HTTPS self-signed, PIN, auto-start boot, restart otomatis, ramah remote TV.

**Telegram** (isi Bot token + Chat ID di Settings): `/status` `/log` `/backup` `/restore` `/update` `/start` `/stop` `/restart` `/ca` `/cabackup` `/careset` `/crashlog` `/help`. Kalau PIN aktif: `/status` `/log` `/stop` dkk wajib diakhiri PIN (mis. `/stop 123456`); pesan perintah ber-PIN dihapus otomatis dari chat bila bot punya izin hapus.

**Backup:** database di `<folder-data>/db.sqlite3`. Backup lokal di Settings → Pemeliharaan; backup Telegram via `/backup`; restore via `/restore` atau file `.zip`/`.sqlite3`.

**HTTPS (wajib, selalu aktif):** tekan Start (server hanya melayani HTTPS) → install `ca.pem` di tiap HP (tombol Bagikan CA / `/ca`, atau Backup CA ke Storage `/cabackup` bila tanpa Telegram). Bila dulu bisa lalu gagal: Reset Sertifikat (`/careset`, CA baru) lalu install ulang CA di semua HP.

## Kalau error

- **Port dipakai?** Ganti Port di Settings.
- **`failed to generate random data` / HTTPS error?** Kernel STB lama — tekan **Cek Update**, Start lagi.
- **Aplikasi Bitwarden tak bisa login?** Baca log realtime baris `[login]` (URL benar `https://`, CA sudah di-install di HP, daftar akun dulu di web-vault).
- **Web UI tak bisa dibuka?** Harus satu WiFi, pakai IP lokal.
- **Gagal unduh?** Cek internet & jam STB, Start lagi (otomatis dilanjutkan).
- **Backup Telegram GAGAL `Certificate not valid until`?** Jam STB reset ke 2015
  (STB mati total / tanpa RTC). Aktifkan Tanggal & waktu otomatis di Pengaturan
  STB (butuh internet), atau atur manual ke hari ini, lalu ulangi backup.

## Pengembang & lisensi

Lihat [AGENTS.md](AGENTS.md). Build hanya via GitHub Actions (push ke `main`); `targetSdk 28` disengaja; tanpa bundel binary ke APK.

App: GPL-3.0 (`LICENSE`). Vaultwarden: AGPL-3.0 (`LICENSE.vaultwarden`).
