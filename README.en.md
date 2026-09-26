# Tasirin Vaultwarden Host (Android)

[![Build](https://github.com/tasirin1/tasirin-vaultwarden-host/actions/workflows/build-apk.yml/badge.svg)](https://github.com/tasirin1/tasirin-vaultwarden-host/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-vaultwarden-host/releases)](https://github.com/tasirin1/tasirin-vaultwarden-host/releases)

<p align="center"><b>&#127760; Language: <a href="README.md">Indonesia</a> &middot; <a href="README.en.md">English</a> &middot; <a href="CHANGELOG.md">Changelog</a></b></p>

A **Vaultwarden** (Bitwarden-compatible) server on Android — for STB/TV boxes and old phones. **Android 5.0+**, 32-bit ARM (`armeabi-v7a`). ~0.1 MB APK; binary & web vault auto-downloaded and SHA-256 verified.

## Usage

1. Download the APK from [Releases](https://github.com/tasirin1/tasirin-vaultwarden-host/releases), install it.
2. Open the app → **&#8942; → Settings**: set **Data folder** and **Port**.
3. Press **Start** (first download is automatic, with progress).
4. Open `http://<PHONE-IP>:<port>` in a browser / Bitwarden app (Server URL).

All settings live in **Settings**; home is just status, Start/Stop, and log.

## Offline

Download from another phone, put in the data folder, press **Start**:

- `vaultwarden-armeabi-v7a` → data folder
- `libgetrandom-shim-armeabi-v7a.so` → same folder (for old STBs)
- `web-vault.zip` → extract to `web-vault/` (must contain `index.html`)

## Features

Auto-update of binary & web vault, status web on port+1, encrypted backups (local + Telegram), self-signed HTTPS, PIN, boot auto-start, auto-restart, TV-remote friendly.

**Telegram** (set Bot token + Chat ID in Settings): `/status` `/log` `/backup` `/restore` `/update` `/start` `/stop` `/restart` `/ca` `/crashlog` `/help`. With PIN: `/stop 123456`.

**Backup:** database at `<data-folder>/db.sqlite3`. Local backup in Settings → Maintenance; Telegram backup via `/backup`; restore via `/restore` or a `.zip`/`.sqlite3` file.

**HTTPS:** check HTTPS → Start → install `ca.pem` (Share CA button / `/ca`). For the Bitwarden app, use HTTP on the local network.

## Troubleshooting

- **Port in use?** Change Port in Settings.
- **`failed to generate random data` / HTTPS error?** Old STB kernel — press **Check Update**, Start again.
- **Web UI unreachable?** Same WiFi required, use the local IP.
- **Download fails?** Check STB internet & clock, Start again (auto-resumes).

## Developers & license

See [AGENTS.md](AGENTS.md). Build only via GitHub Actions (push to `main`); `targetSdk 28` is intentional; binary never bundled in the APK.

App: GPL-3.0 (`LICENSE`). Vaultwarden: AGPL-3.0 (`LICENSE.vaultwarden`).
