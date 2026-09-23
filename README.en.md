# Tasirin Vaultwarden Host (Android)

[![Build](https://github.com/tasirin1/tasirin-vaultwarden-host/actions/workflows/build-apk.yml/badge.svg)](https://github.com/tasirin1/tasirin-vaultwarden-host/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-vaultwarden-host/releases)](https://github.com/tasirin1/tasirin-vaultwarden-host/releases)

<p align="center"><b>&#127760; Language: <a href="README.md">Indonesia</a> &middot; <a href="README.en.md">English</a> &middot; <a href="CHANGELOG.md">Changelog</a></b></p>

Run a real **Vaultwarden** (Bitwarden-compatible, Rust) server directly on
Android — built automatically via **GitHub Actions**. Supports **Android 5.0
(API 21) and up**, including 32-bit ARM STB/TV boxes (`armeabi-v7a`).

> **For AI agents maintaining this repo: read [AGENTS.md](AGENTS.md) first** —
> it contains the structure, architecture, development rules, and build/release
> flow. Agents must read it before changing or maintaining the code.

This document is the usage guide & technical overview; the full management
guide is in [AGENTS.md](AGENTS.md) and change history in [CHANGELOG.md](CHANGELOG.md).

## Contents

- [Features](#features)
- [How it works](#how-it-works)
- [Download & install](#download--install)
- [Usage](#usage)
- [Offline install](#offline-install)
- [Status web & realtime log](#status-web--realtime-log)
- [Remote via Telegram](#remote-via-telegram)
- [Data, backup & restart](#data-backup--restart)
- [HTTPS (self-signed)](#https-self-signed)
- [Auto update](#auto-update)
- [Troubleshooting](#troubleshooting)
- [Repository structure](#repository-structure)
- [License](#license)

## Features

- Real Vaultwarden server (official Rust binary), version follows upstream
  `dani-garcia/vaultwarden` releases.
- Tiny APK (~0.1 MB): binary & web vault are **not bundled**, downloaded
  from GitHub Release on first Start (SHA-256 verified).
- Auto-update binary & web vault on the home network (WiFi/ethernet), plus
  auto-restart after update — all toggleable via checkboxes.
- Lightweight status web (JSON + realtime log via SSE) on a separate port —
  shows **web vault version + size, DB size, restart history**.
- Remote & backup via **Telegram bot** (commands + AES-256-GCM backup),
  including `/crashlog` for the last crash log. Command menu (the `/` button)
  is registered automatically via `setMyCommands` whenever the bot token is
  saved — no manual @BotFather setup needed.
- **Crash log** saved automatically, viewable via the **Crash** button on the
  Log screen (dialog + copy) or sent to Telegram with `/crashlog`.
- **Realtime download progress** in the status chip: percent + size while
  downloading binary/web-vault (update or first Start).
- **Storage breakdown** on the home screen: DB size, local backups (count +
  total), web-vault, and binary sizes.
- Self-signed HTTPS, app-lock PIN, auto-start on boot, auto-restart on crash
  (with **anti-loop**: full stop after 3 restarts in 5 minutes), port-conflict
  detection, config export/import.
- TV-remote friendly UI (D-pad navigation) as well as phone touchscreens.

## How it works

1. **GitHub Actions** takes the latest Vaultwarden version from the official
   release and cross-compiles it for **`armeabi-v7a`** (32-bit ARM, Android
   API 21 target) — the only supported ABI.
2. **APK + binary + web-vault.zip** are published to this repo's **GitHub
   Release**. The binary & web vault are **not bundled inside the APK**; the
   app downloads them from the release on first Start. Vaultwarden's custom
   DNS resolver (`hickory`/`ndk-context`) is disabled on Android because it
   needs a JNI context unavailable in a *standalone* process.
3. Pressing **Start** launches the binary with your chosen `DATA_FOLDER`
   (default `/sdcard/vaultwarden`) — SQLite database & data stay there, so
   nothing is lost on app update/device reboot.

> `targetSdk 28` is intentional: Android 10+ blocks executing binaries from
> app storage for apps with `targetSdk >= 29` (W^X behavior). `targetSdk 28`
> also keeps the legacy storage access (`/sdcard/...`) on Android 11+.

## Download & install

- **GitHub Release** → `tasirin-vaultwarden-host-armeabi-v7a.apk`
  (32-bit ARM — runs on STBs & old phones; arm64 phones still work via compat).
- **Actions tab** → latest run → artifact `tasirin-vaultwarden-host-apk-armeabi-v7a`.
- Release APKs are signed with the **Tasirin keystore** — just install
  (enable *install from unknown sources* if asked).
- APK version = build date (`2026.08.08`); release version = Vaultwarden
  version (`v1.37.x`).

## Usage

The home screen is minimal: **server status**, **Start/Stop** button,
**realtime log**, and **Save .txt**. Everything else lives under
**three-dot (⋮) → Settings** at the top right.

1. Install the APK, open the app, grant **Storage** permission if asked.
2. Open **⋮ → Settings**, fill **Data folder** (e.g. `/sdcard/vaultwarden`)
   and **Port** (default `8088`). Access always uses the LAN IP.
3. Back on the home screen, press **Start**. On first run the binary
   downloads automatically (SHA-256, stored internally, not re-downloaded).
   If the web vault is missing, choose **Download & Start** in the dialog.
4. Open `http://127.0.0.1:8088` via **Open Web UI** in Settings (phone
   browser), or from a PC/laptop at `http://<android-local-IP>:8088`.
   From another phone: copy the URL from Settings (**Copy URL**) and open it
   in a browser.
5. Clients: install the official **Bitwarden** app → Settings → Server URL →
   `http://<android-local-IP>:8088`.

## Offline install

The APK does not bundle the binary & web vault (to stay small), so the first
Start normally needs internet. If the device has no internet access
(especially STB/TV), prepare these two files **once** from another device:

1. Download from this repo's **Release** page:
   - `vaultwarden-armeabi-v7a` (server binary, ~20 MB)
   - `libgetrandom-shim-armeabi-v7a.so` (required for old-kernel Android 5/6 STBs)
   - `web-vault.zip` (web vault pages, ~36 MB)
2. Place with exact names: binary at
   `/sdcard/vaultwarden/vaultwarden-armeabi-v7a` and (for old-kernel STBs)
   the shim at `/sdcard/vaultwarden/libgetrandom-shim-armeabi-v7a.so`.
3. Extract `web-vault.zip` so that
   `/sdcard/vaultwarden/web-vault/index.html` exists (zip contents extracted
   straight into the `web-vault` folder).
4. Open the app, set **Data folder** = `/sdcard/vaultwarden`, press **Start** —
   the app uses the existing binary & web vault **without internet**.

Note: binaries on `/sdcard` cannot be executed directly (FAT storage), so the
app copies them to internal storage on Start — automatically, no download.
Other files (`web-vault`, database, config) are used straight from the data
folder.

## Status web & realtime log

- While the server runs, the app also serves a **status web** on **server
  port + 1** (default `8088` → `8089`): `http://<android-local-IP>:8089`. If
  that port is taken, the app auto-probes the next free port (up to +10).
- The page shows server status (running/stopped, app/binary version, web
  vault version + size, DB size, backup count, port, HTTPS, uptime, process
  RAM, free storage, restart history, data folder) plus a **realtime log**
  (SSE) with search & auto-scroll.
- JSON endpoints: `GET /api/status` (incl. `dbHuman`, `wvHuman`,
  `binaryHuman`, `backupCount`, `restartHistory`), text log: `GET /api/log`,
  stream: `GET /api/events`.
- If an **Admin Token** is set, `/api/*` endpoints require
  `?token=<admin-token>` (auto-appended when opened locally) — others on the
  network cannot peek at the log without the token.

## Remote via Telegram

Set **Bot token** + **Chat ID** in settings, then send commands to the bot:

| Command       | Function                                                    |
|---------------|-------------------------------------------------------------|
| `/status`     | Full status: version, web vault, DB, last backup, RAM, uptime, free space, restart history |
| `/log`        | Last log snippet (3500 chars)                               |
| `/uptime`     | How long the server has run                                 |
| `/alive`      | Health check via HTTP `/alive`                              |
| `/backup`     | Back up the database now (encrypted)                        |
| `/restore`    | Restore last backup (`/restore YA` to confirm)              |
| `/ca`         | Send active HTTPS CA (`ca.pem`) to this chat — forward to another phone then install as CA |
| `/crashlog`   | Send last crash log (if any)                                |
| `/update`     | Update binary + auto restart                                |
| `/webvault`   | Update web vault + auto restart when running                |
| `/start` `/stop` `/restart` | Server control                            |
| `/help`       | Command list                                                |

When the **app PIN is enabled**, `/stop`, `/update`, and `/restore` must end
with the PIN (e.g. `/stop 123456`) so a stolen Telegram session cannot wreck
the server.

The command menu (the `/` button) is registered automatically via
`setMyCommands` every time the bot token is saved — no manual @BotFather
setup needed. If the menu does not appear yet, re-save the token or just
send `/help`.

Commands are polled roughly every ~20 seconds (15 s long-poll). Send `/help`
to get **inline buttons** — tap (Status, Backup, Restart, ...) instead of
typing. Dangerous commands via button still need the trailing PIN when the
app PIN is on (type manually, e.g. `/stop 123456`).

## Data, backup & restart

- Database: `DATA_FOLDER/db.sqlite3` (default `/sdcard/vaultwarden/`). On
  external storage, data survives app updates/device reboots.
- **Local backup** (button in Maintenance): timestamped zip of `db.sqlite3` +
  WAL/SHM into `<data>/backups/` (max 10, oldest auto-deleted).
  **Telegram backup**: same zip, also kept in `<data>/backups/`, optionally
  **AES-256-GCM** encrypted (same password required on restore), can run
  automatically on date change (00:01 midnight, even when the app is closed)
  or on Start when the day has changed, and includes config + certificates. Every
  backup is **auto-verified** before upload (must contain a valid-header
  `db.sqlite3`; encrypted backups are trial-decrypted with the password
  first) — corrupt backups are aborted, never sent.
- **Restore**: from a Telegram backup (in-app button or `/restore` +
  `/restore YA` confirmation), a local `.zip` file, or a raw `.sqlite3`
  (legacy backup); the server stops automatically during restore. Bot identity
  (token/chat/password) is preserved so the bot stays connected.
- **Security**: binary/web-vault updates abort when the SHA-256 checksum is
  missing/mismatched; manual binaries need their SHA-256 set in settings;
  config export is encrypted when a backup password is set; PIN uses
  PBKDF2+salt; TLS certificate is now end-entity (not CA).
- **Auto-start on boot** (foreground service + wake lock). **Auto-restart on
  crash**: staged backoff 2→5→10→20→40 s (max 5×; reset when stable >1 min).
  **Anti-loop**: 3× restarts in 5 min → auto-restart off, status & Telegram
  notified. **Restart history** (count + last time) shows on the home screen
  and `/status`. **Crash log** (last ~100 lines) is stored in
  `crash-last.log` (internal) on server crash/health failure. Adaptive `/alive` health check (fallback `/api/config` + TCP probe so a healthy server is not killed when DB is slow).
- Note: if the app is **force-stopped**, Android blocks the boot broadcast
  until the app is opened once more (swiping from recents does not affect the
  service).

## HTTPS (self-signed)

- Tick **HTTPS (self-signed)** then Start. The app creates a local CA
  (`tls/ca.pem`, 10 years) + server certificate (`tls/cert.pem` + `key.pem`,
  RSA 2048, 5 years, auto-regenerated when the IP changes).
- Browsers show a self-signed warning; silence it by installing `ca.pem`
  (not `cert.pem`) as a CA on other phones: copy `tls/ca.pem`, then
  Settings → Security → Install CA certificate (no private key). The active
  CA lives in the app internal storage (old data folder is only an archive).
  The CA is stable across IP changes, so install once — except after an
  update that regenerates the CA (e.g. cert version bump): if it **used to
  work then fails**, remove the old CA on the phone and reinstall the new one.
- **Transfer to another phone**: on the server phone tap **Share CA (Bagikan CA)**
  and send it via Bluetooth/WhatsApp/Telegram, or send the Telegram command
  **`/ca`** to the bot (file arrives in chat, forward it to the other phone);
  on the target phone save the file then install it as a CA certificate.
  **Install Cert** only installs on the server phone itself.
- The official **Bitwarden** app usually rejects self-signed certs — for
  non-web-vault clients prefer HTTP on a trusted local network.

## Auto update

- **Version checks come from the official source** (`dani-garcia/vaultwarden`),
  not this repo. An "Update available" notice appears automatically when the
  app opens.
- Checkboxes in **Settings → Maintenance**:
  - *Auto-update binary* — install the latest binary automatically (home network).
  - *Auto-update web vault* — keep the web vault following the binary.
  - *Auto-restart after update* — restart the server once when an update
    lands while running.
- **Check Update** installs the newest binary (used on next Start, no APK
  reinstall). **Update Web Vault** downloads `web-vault.zip` from the release
  into `<data>/web-vault`. While downloading, the status chip shows
  **realtime progress** (percent + size).
- All downloads are **SHA-256 verified** against the `.sha256` file in the
  release; mismatches abort the update (safe to retry).
- **Reset Binary** deletes the stored binary — the newest one re-downloads
  automatically on next Start.

## Troubleshooting

- **"Port 8088 in use" on Start** — another app uses the port. Change the
  **Port**, stop the other app, or reboot. The status web auto-uses a free
  port (8089 → 8098) on conflict.
- **Server keeps crashing / looping** — a stale `vaultwarden` process may be
  stuck. Reboot, then Start again (the app also cleans same-UID leftovers).
- **Instant crash: `failed to generate random data` (exit 101)** — the latest
  Vaultwarden binary needs a newer-kernel `getrandom()`; on Android 5/6 STB
  kernels (e.g. ZTE B860H, kernel 3.14.x) it panics (`errno=22`). The app
  auto-detects old kernels and uses the getrandom shim
  (`libgetrandom-shim-armeabi-v7a.so`, auto-downloaded + SHA-256 verified on
  Start/Check Update) via `LD_PRELOAD`. Press **Check Update** then Start.
- **Favicon 500 / TLS verifier panic** — press **Check Update** then Start so
  the fixed binary lands (no APK reinstall needed); the STB must be online.
- **Telegram backup fails** — check bot token & chat ID, free storage, and
  connection. Encrypted backups need the same password on restore.
- Full troubleshooting (TLS on old Android, hijacked DNS, GitHub timeouts
  with 3× retry + offline setup, HTTP 416 resume, corrupt zips) is covered in
  the [Indonesian README](README.md#troubleshooting).

## Repository structure

Management guide for humans & AI: **[AGENTS.md](AGENTS.md)**.
Change history: **[CHANGELOG.md](CHANGELOG.md)**.
CI: 4 jobs (`resolve` → `build-binary` → `build-shim` → `build-apk`),
auto-rebuild every 6 h when Vaultwarden releases a new version.

## License

- App wrapper: GPL-3.0 (see `LICENSE`).
- Vaultwarden: AGPL-3.0 (see `LICENSE.vaultwarden`; source:
  https://github.com/dani-garcia/vaultwarden).
