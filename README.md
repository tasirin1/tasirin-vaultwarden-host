# Tasirin Vaultwarden Host

Menjalankan server **Vaultwarden** (Bitwarden-compatible, Rust) langsung di Android,
dibangun otomatis lewat **GitHub Actions** (repo `tasirin1/tasirin-vaultwarden-host`).
Mendukung **Android 5.0 (API 21) ke atas**.

## Cara kerja

1. GitHub Actions mengambil **versi Vaultwarden terbaru** dari rilis resminya,
   melakukan *cross-compile* untuk **`armeabi-v7a`** (ARM 32-bit, target
   Android API 21) — satu-satunya ABI yang didukung.
2. **APK + binary + web-vault.zip** di-publish ke **GitHub Release** repo ini.
   Binary server & web vault **TIDAK lagi dibundel di dalam APK** (APK jauh
   lebih kecil) — app mengunduhnya dari release saat pertama kali Start.
   (Catatan: DNS resolver kustom Vaultwarden `hickory` dinonaktifkan di
   Android karena butuh konteks JNI yang tidak tersedia di proses
   *standalone*; diganti DNS sistem agar ikon vault & request keluar tetap
   jalan.)
3. Saat tombol **Start** ditekan, app menjalankan binary dengan `DATA_FOLDER`
   sesuai pilihan kamu (default `/sdcard/vaultwarden`) — database SQLite &
   data lain tersimpan di sana.

> `targetSdk 28` dipilih dengan sengaja: Android 10+ hanya memblokir eksekusi
> binary dari app data untuk app dengan `targetSdk >= 29`. `targetSdk 28` juga
> mempertahankan akses storage lama (`/sdcard/...`) di Android 11+.

## Download APK

- **GitHub Release** → `tasirin-vaultwarden-host-armeabi-v7a.apk`
  (ARM 32-bit — jalan di STB & HP lama; HP arm64 tetap bisa via compat mode).
- **Tab Actions** → run terbaru → artifact `tasirin-vaultwarden-host-apk-armeabi-v7a`.
- APK rilis ditandatangani **keystore Tasirin** (sama dengan Tasirin Download
  Manager) — tinggal install (aktifkan *install from unknown sources*).
- Ukuran APK **sangat kecil (~2–3 MB)**: binary server & web vault TIDAK
  dibundel ke dalam APK. Keduanya diunduh dari GitHub Release saat
  **Start pertama** (binary ~20 MB otomatis; web vault ~35 MB lewat dialog
  "Unduh & Start" atau kapan saja lewat tombol **Update Web Vault**).

## Pemakaian

1. Install APK, buka app **Tasirin Vaultwarden Host**, beri izin **Storage** bila diminta.
2. Isi **Folder data** (mis. `/sdcard/vaultwarden`) dan **Port** (default `8088`).
3. Tekan **Start**. Pertama kali, binary server diunduh otomatis dari
   GitHub Release (diverifikasi SHA-256, tersimpan di internal — tidak
   diunduh ulang pada Start berikutnya). Bila web vault belum ada, pilih
   **Unduh & Start** di dialog. Status menunjukkan server berjalan + URL.
4. Buka `http://127.0.0.1:8088` lewat tombol **Open Web UI** (browser HP),
   atau dari PC/laptop pakai `http://<IP-lokal-android>:8088`.
5. Klien: install app **Bitwarden** resmi → Settings → Server URL →
   `http://<IP-lokal-android>:8088`.

## Status web & log realtime

- Saat server berjalan, app juga menjalankan **status web** di **port server + 1**
  (default `8088` -> `8089`): `http://<IP-lokal-android>:8089`.
- Halaman itu menampilkan status server (jalan/berhenti, versi app & binary, port,
  HTTPS, uptime, sisa storage, folder data) plus **log realtime** (SSE) dengan
  pencarian & auto-scroll. Buka dari HP/PC di jaringan yang sama.
- Di layar utama ada tombol **Status Web** untuk langsung membuka halaman itu
  di browser (tersedia setelah server Start).
- Di app: tombol **Buka** di panel Log membuka **Log Realtime layar penuh** —
  cari + highlight `GAGAL/ERROR`, auto-scroll (default mati), hitungan baris,
  salin, bagikan, dan **Simpan .txt** ke folder `Download`.

## Data & restart

- Database tersimpan di `DATA_FOLDER` (default `/sdcard/vaultwarden/db.sqlite3`).
  Karena di storage eksternal, data **tidak hilang saat restart device**,
  bahkan saat app di-uninstall. Backup cukup dengan menyalin folder itu.
- Centang **Auto start saat boot** agar server otomatis menyala setelah device
  restart. Server berjalan sebagai *foreground service* (ada notifikasi), lebih
  tahan dibunuh sistem, plus wake lock saat aktif.
- **Restart otomatis saat crash**: jika proses server mati mendadak, app
  menyalakan ulang dengan jeda bertingkat (2 → 5 → 10 → 20 → 40 dtk, maks
  5 percobaan; penghitung di-reset bila server stabil > 1 menit).
- Catatan: jika app di-**force-stop**, Android memblokir broadcast boot sampai
  app dibuka sekali lagi. Swipe dari recents tidak memengaruhi service.

## HTTPS (self-signed)

- Centang opsi **HTTPS (self-signed)** di app lalu Start.
- Sertifikat dibuat otomatis di `<DATA_FOLDER>/tls/` (`cert.pem` + `key.pem`).
- Browser tetap menampilkan peringatan *self-signed*; untuk menghilangkannya:
  *Settings → Security → Install certificate → CA certificate* lalu pilih
  `cert.pem`.
- Aplikasi **Bitwarden** resmi umumnya menolak sertifikat self-signed —
  untuk klien selain web vault, sebaiknya tetap pakai HTTP di jaringan lokal
  yang tepercaya.

## Sumber & update otomatis

- **Cek versi diambil dari sumber resmi** (`dani-garcia/vaultwarden`), bukan
  repo ini. Notifikasi "Update tersedia" muncul otomatis saat app dibuka.
- Workflow dijadwalkan **tiap 6 jam**; bila Vaultwarden rilis versi baru,
  repo ini otomatis di-build ulang (skip bila versi sama).
- Binary Android & `web-vault.zip` **di-host di repo ini** karena rilis resmi
  Vaultwarden tidak menyediakan asset biner (Docker-only) — kontennya tetap
  diambil dari sumber resmi: source `dani-garcia/vaultwarden` untuk binary,
  dan image `vaultwarden/web-vault` (digest resmi) untuk web vault.
- Binary **tidak dibundel di APK**: saat Start pertama app mengunduhnya dari
  release repo ini dan menyimpannya di internal (dipakai ulang sampai APK
  di-update). Bisa juga menaruh `vaultwarden-armeabi-v7a` manual di folder
  data (mis. `/sdcard/vaultwarden/`) — app akan memakainya tanpa unduh ulang
  (disalin ke internal dulu karena `/sdcard` tidak bisa dieksekusi langsung).
- Di app: tekan **Cek Update** — jika ada versi lebih baru, binary diunduh
  sesuai ABI perangkat dan langsung dipakai saat Start berikutnya
  (**tanpa install ulang APK**).
- Setiap unduhan (binary & web vault) **diverifikasi SHA-256** terhadap file
  `.sha256` di release sebelum dipasang; kalau checksum tidak cocok, update
  dibatalkan.
- **Update Web Vault** mengunduh `web-vault.zip` dari repo ini (isinya dari
  image web vault resmi) dan mengekstraknya ke `<data>/web-vault`.
- Saat versi web vault beda dari binary server, app otomatis menawarkan
  update web vault sekali per versi.
- **Reset Binary** menghapus binary tersimpan — versi terbaru diunduh ulang
  otomatis saat Start berikutnya.

## Backup cloud (Telegram)

- Di app: isi **Bot token** dan **Chat ID**, lalu tekan **Backup ke Telegram**.
- Backup berupa zip `db.sqlite3` (+ file WAL) dengan timestamp, disimpan juga
  di `<data>/backups/` (maksimal 10 file, tertua otomatis dihapus).
- **Restore dari Telegram**: ambil backup terakhir yang pernah terkirim,
  server dihentikan otomatis, database dipulihkan.
- **Enkripsi**: isi *Password enkripsi backup* untuk mengenkripsi (AES-256-GCM)
  backup sebelum dikirim — wajib diisi sama saat restore.
- Centang **Backup otomatis tiap 24 jam** untuk kirim otomatis (via AlarmManager,
  jalan walau app tidak dibuka; pakai alarm **exact** supaya tidak di-batch
  Doze, dijadwalkan ulang tepat 24 jam setelah backup selesai, dan otomatis
  terpasang lagi setelah reboot).
- Centang **Sertakan pengaturan & sertifikat** agar backup ikut mengirim
  `app-config.json` + `tls/cert.pem` & `tls/key.pem`. Restore lengkap otomatis
  memakai folder data & pengaturan dari backup (cocok untuk pindah perangkat).
- **PIN kunci app** (opsional): kunci app dengan PIN 4-6 digit saat dibuka.
- Cara buat bot: chat `@BotFather` → `/newbot` → ikuti langkah → dapat token.
- Chat ID: kirim pesan ke bot-mu dulu, lalu buka
  `https://api.telegram.org/bot<TOKEN>/getUpdates` di browser dan ambil angka
  `"chat":{"id":...}` (bisa juga pakai `@userinfobot`).

## Build ulang manual

- **Actions → Build Tasirin Vaultwarden Host APK → Run workflow**
  (atau otomatis tiap push ke `main`, atau tiap 6 jam).

## Lisensi

- Wrapper app: GPL-3.0 (lihat `LICENSE`).
- Vaultwarden: AGPL-3.0 (lihat `LICENSE.vaultwarden`; sumber:
  https://github.com/dani-garcia/vaultwarden).
