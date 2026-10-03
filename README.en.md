# Tasirin Vaultwarden Host (Android)

[![Build APK](https://github.com/tasirin1/tasirin-vaultwarden-host/actions/workflows/build-apk.yml/badge.svg)](https://github.com/tasirin1/tasirin-vaultwarden-host/actions)
[![Build Binary](https://github.com/tasirin1/tasirin-vaultwarden-host/actions/workflows/build-binary.yml/badge.svg)](https://github.com/tasirin1/tasirin-vaultwarden-host/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-vaultwarden-host/releases)](https://github.com/tasirin1/tasirin-vaultwarden-host/releases)

<p align="center"><b>&#127760; Language: <a href="README.md">Indonesia</a> &middot; <a href="README.en.md">English</a> &middot; <a href="CHANGELOG.md">Changelog</a></b></p>

A **Vaultwarden** (Bitwarden-compatible) server on Android — for STB/TV boxes and old phones. **Android 5.0+**, 32-bit ARM (`armeabi-v7a`). ~0.1 MB APK; binary & web vault auto-downloaded and SHA-256 verified.

## Usage

1. Download the APK from [Releases](https://github.com/tasirin1/tasirin-vaultwarden-host/releases), install it.
2. Open the app → **&#8942; → Settings**: follow the 3-step wizard (Folder → Port → Start). Simple mode shows only the essentials.
3. Press **Start** (first download is automatic, with progress).
4. Open `https://<PHONE-IP>:<port>` in a browser / Bitwarden app (Server URL).

All settings live in **Settings**; home is just status, Start/Stop, and log.

## Offline

Download from another phone, put in the data folder, press **Start**:

- `vaultwarden-armeabi-v7a` → data folder
- `libgetrandom-shim-armeabi-v7a.so` → same folder (for old STBs)
- `web-vault.zip` → extract to `web-vault/` (must contain `index.html`)

## Features

Auto-update of binary & web vault, encrypted backups (local + Telegram), self-signed HTTPS, PIN, boot auto-start, auto-restart, TV-remote friendly.

Single vertical column layout (portrait and landscape share the same order): links card on top, realtime log in the middle, server status below (log font scales with screen size) and every button works with a TV remote D-pad.

**Telegram** (set Bot token + Chat ID in Settings): `/status` `/log` `/uptime` `/alive` `/backup` `/restore` `/ca` `/cabackup` `/careset` `/crashlog` `/versi` `/update` `/webvault` `/restart` `/start` `/stop` `/help`. With PIN, state-changing commands (`/start` `/stop` `/restart` `/backup` `/update` `/webvault` `/restore` `/careset`) plus `/log` and `/crashlog` (they carry the data-folder path, port, binary version, and LAN URL) must end with the PIN (e.g. `/stop 123456`); other read commands (`/status` `/uptime` `/alive` `/ca` `/versi` `/help`) only need chat auth. PIN command messages are auto-deleted from the chat when the bot has delete permission; delete capability is probed once on `/help` and the owner is warned once if the bot cannot delete.

**Version pinning (not forced to latest):** Settings → Maintenance → "Versi binary" / "Versi web vault" buttons to pick an older version (downgrade allowed) or back to "Terbaru (otomatis)". The pinned version is honored by auto-update/Start (never silently upgraded). Via bot: `/versi` (show versions + pins), `/update 1.32.0` / `/webvault 1.32.0` (pin + install), `/update terbaru` (unpin).

**Backup:** database at `<data-folder>/db.sqlite3`. Local backup in Settings → Maintenance; Telegram backup via `/backup`; restore via `/restore` or a `.zip`/`.sqlite3` file.

**HTTPS (mandatory, always on):** press Start (the server only serves HTTPS) → install `ca.pem` on each phone (Share CA button / `/ca`, or Backup CA to Storage `/cabackup` without Telegram). If it worked before then fails: Reset Certificate (`/careset`, new CA) then reinstall the CA on every phone.

## Troubleshooting

- **Port in use?** Change Port in Settings.
- **`failed to generate random data` / HTTPS error?** Old STB kernel — press **Check Update**, Start again.
- **Web UI unreachable?** Same WiFi required, use the local IP.
- **Download fails?** Check STB internet & clock, Start again (auto-resumes).
- **Telegram backup fails with `Certificate not valid until`?** STB clock reset to 2015
  (power loss / no RTC). Enable Automatic date & time in STB Settings
  (needs internet), or set it manually to today, then retry backup.

## Developers & license

See [AGENTS.md](AGENTS.md). Build only via GitHub Actions (push to `main`, split: binary vs APK); `targetSdk 28` is intentional; binary never bundled in the APK.

App: GPL-3.0 (`LICENSE`). Vaultwarden: AGPL-3.0 (`LICENSE.vaultwarden`).
