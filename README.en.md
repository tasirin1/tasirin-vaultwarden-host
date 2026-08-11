# Tasirin Vaultwarden Host (Android)

[![Build](https://github.com/tasirin1/tasirin-vaultwarden-host/actions/workflows/build-apk.yml/badge.svg)](https://github.com/tasirin1/tasirin-vaultwarden-host/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-vaultwarden-host)](https://github.com/tasirin1/tasirin-vaultwarden-host/releases)

<p align="center"><b>&#127760; Language: <a href="README.md">Indonesia</a> &middot; <a href="README.en.md">English</a> &middot; <a href="CHANGELOG.md">Changelog</a></b></p>

Run a real **Vaultwarden** (Bitwarden-compatible, Rust) server directly on
Android — built automatically via **GitHub Actions**. Supports **Android 5.0
(API 21) and up**, including 32-bit ARM STB/TV boxes (`armeabi-v7a`).

> **For AI agents maintaining this repo: read [AGENTS.md](AGENTS.md) first** —
> it contains the structure, architecture, development rules, and build/release
> flow. Agents must read it before changing or maintaining the code.

## Features

- Real Vaultwarden server (official Rust binary), version follows upstream
  `dani-garcia/vaultwarden` releases.
- Tiny APK (~0.1 MB): the binary & web vault are **not bundled** — downloaded
  from the GitHub Release on first Start (SHA-256 verified).
- Auto-update binary & web vault on your home network, with auto-restart after
  update (all optional).
- Lightweight status web (JSON + realtime SSE logs) on a separate port — now
  also shows **web vault version + size, DB size, and restart history**.
- Remote control & backup via **Telegram bot** (commands + AES-256-GCM backup),
  including `/crashlog` to send the last crash log.
- **Connection QR**: one tap shows a scannable QR for `http(s)://IP:port`.
- **Crash log** saved automatically — view it via the **Crash** button on the
  full-screen log page (dialog + copy) or send it to Telegram with `/crashlog`.
- **Realtime download progress** in the status chip: percent + size while
  downloading the binary/web vault (update or first Start).
- **Storage breakdown** on the main screen: DB, local backups (count + total),
  web vault, and binary sizes.
- Self-signed HTTPS, PIN lock, boot auto-start, crash auto-restart (with
  **anti-loop**: stops after 3 restarts in 5 minutes), port conflict detection,
  config export/import.
- TV-remote (D-pad) and touch-friendly UI.

## How it works

1. **GitHub Actions** fetches the latest Vaultwarden release and cross-compiles
   it for **`armeabi-v7a`** (32-bit ARM, Android API 21) — the only ABI.
2. **APK + binary + web-vault.zip** are published to this repo's **GitHub
   Release**. The app downloads them on first Start; existing files are reused.
3. Pressing **Start** launches the binary with your chosen `DATA_FOLDER`
   (default `/sdcard/vaultwarden`) — SQLite database and data stay there.

> `targetSdk 28` is intentional: Android 10+ blocks executing binaries from
> app storage for apps with `targetSdk >= 29` (W^X behavior).

## Download & install

1. Grab the latest APK from
   [GitHub Releases](https://github.com/tasirin1/tasirin-vaultwarden-host/releases)
   (`tasirin-vaultwarden-host-armeabi-v7a.apk`).
2. Install it (allow "install unknown apps" if asked).
3. Open the app — it downloads the Vaultwarden binary + web vault on first
   Start (needs internet on the first run; also supports offline setup).

## Usage

- Set the data folder, port (default `8088`), HTTPS on/off, and admin token in
  the main screen.
- Press **Start**; the status web is at `http(s)://<device-ip>:<port+1>`.
- Pair clients (bitwarden mobile/desktop) to `https://<device-ip>:<port>` and
  accept the self-signed certificate.
- From another phone, tap **QR** and scan to open the URL instantly.
- **Telegram remote**: set bot token + chat id to use `/log /uptime /alive
  /status /crashlog /update /webvault`, backups, and notifications. `/status`
  shows DB size, last backup time, and restart history.
- **Offline**: copy `vaultwarden-armeabi-v7a` + `web-vault.zip` from the
  Release into `/sdcard/vaultwarden/bin` and `/sdcard/vaultwarden/web-vault`
  (see [README.md](README.md) for details).

## Update & troubleshooting

- Updates are checked from the official Vaultwarden repo; binary & web vault
  update in-app over your network, with SHA-256 verification and **realtime
  progress** (percent + size) in the status chip.
- The status web page now also shows web vault version + size, DB size, backup
  count, and restart history; the full-screen log page has a **Crash** button
  to open the last crash log.
- Common issues (TLS on old Android, Telegram backup, web-vault update
  failures, DNS icon panics) are covered in the
  [Indonesian README](README.md#troubleshooting).

## Repository

- Management guide for humans & AI: **[AGENTS.md](AGENTS.md)**.
- Change history: **[CHANGELOG.md](CHANGELOG.md)**.
- CI: 3 jobs (`resolve` → `build-binary` → `build-apk`), auto-rebuild every 6 h
  when Vaultwarden releases a new version.

## License

- App wrapper: GPL-3.0 (see `LICENSE`).
- Vaultwarden: AGPL-3.0 (see `LICENSE.vaultwarden`; source:
  https://github.com/dani-garcia/vaultwarden).
