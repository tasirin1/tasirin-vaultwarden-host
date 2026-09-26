# Changelog

## [Belum rilis] — Binary manual aman: uji di tmp + log saat dilewati

### Perbaikan
- Binary manual diuji smoke test di file sementara dulu; cache yang sedang
  jalan tak lagi tertimpa file korup (penting untuk STB offline).
- Bila cache update aktif, file manual yang dilewati kini dicatat di log
  (sebelumnya diabaikan diam-diam).

## [Belum rilis] — Masking log konsisten, UTCTime UTC, KDF bersih

### Perbaikan
- Masking token kini konsisten di semua jalur keluar: file crash ditulis
  tersamarkan (menutup `/crashlog` + dialog crash) dan `/log` Telegram
  disamarkan sebelum dipotong.
- Sertifikat: `UTCTime` memakai zona UTC (sebelumnya zona perangkat
  berlabel `Z`).
- KDF backup: salinan char password dinolkan seusai derive (seperti PIN).

## [Belum rilis] — Kunci PIN anti-Back, gate log, impor angka aman

### Perbaikan
- Kunci PIN: dialog tak lagi bisa ditutup tombol Back/sentuh-luar
  (`setCancelable(false)`); sebelumnya Back melewatkan kunci sepenuhnya.
  Berlaku di layar utama dan Settings; keluar hanya via PIN atau "Keluar".
- Log layar penuh: gate PIN di `onResume` — sesi melewati grace 60 detik
  dikembalikan ke layar utama (yang meminta PIN).
- Impor config: nilai angka JSON (`port: 8088`) dikoersi ke String saat
  impor; prefs lama yang telanjur bukan-String disembuhkan tiap startup
  (`healkanStringPrefs`) agar `getString` tak `ClassCastException` tiap
  buka Settings/Start.

## [Belum rilis] — Audit keamanan & jam: 8 perbaikan

### Perbaikan
- Status web: batas 10 menit + heartbeat SSE pakai jam monotonik
  (`elapsedRealtime`); jam STB mundur/maju tak lagi memperpanjang SSE
  berjam-jam atau mematikannya prematur.
- Status web: slot koneksi direservasi atomik di `acceptLoop` (tutup race
  cek-lalu-tambah); `handle()` tak lagi menghitung sendiri.
- Unduhan: redirect protokol-relatif (`//host/...`) hanya boleh se-host
  dengan asal; lintas host ditolak.
- Bot Telegram: auth tombol inline satu pintu via `cocokChat` (ruang atau
  pengirim); banding String mentah yang redundan dibuang.
- PIN: salinan char kerja PBKDF2 dinolkan di `finally` (tak mengendap di heap).
- Server: folder data divalidasi ulang tepat sebelum `exec` (tutup jendela
  TOCTOU symlink selama unduh binary/web-vault).
- Trust anchor: checksum `.sha256` pendamping dicek bila diterbitkan di rilis
  (fail-open: tanpa sidecar tetap mengandalkan TLS seperti sebelumnya).
- Backup terjadwal: jam mundur tak lagi melewatkan backup (guard + dedup
  harian yang memutuskan).

## [Belum rilis] — README ditulis ulang total (lebih ringkas)

### Dokumentasi
- `README.md` / `README.en.md` dipangkas (~370 → ~50 baris): cara pakai, offline, fitur, Telegram, backup/HTTPS,
  error umum, dan info pengembang — tanpa duplikasi isi `AGENTS.md`.

## [Belum rilis] — Audit penuh: swap web-vault, restart yatim, jam monotonik, TIME_WAIT, dialog PIN

### Perbaikan
- Web-vault: bila rename ke `.bak` gagal, update dibatalkan (versi lama
  dipertahankan), bukan malah menghapus versi lama.
- Health terminal: status web dihentikan + penanda jalan dibersihkan seperti
  `stopServer` (tak lagi melayani info basi); restart tertunda dibatalkan.
- Restart: runnable tertunda jadi field agar `STOP`/`onDestroy` bisa
  membatalkannya (tak lagi start di service yang sudah mati).
- Restart loop: deteksi memakai jam monotonik (lompatan jam tak lagi memicu
  "restart berulang" palsu atau menyembunyikan loop asli).
- Port: `isPortBusy` pakai `REUSEADDR` agar `TIME_WAIT` sisa tak dituduh
  "port dipakai"; listener aktif tetap terdeteksi via `EADDRINUSE`.
- PIN: dialog tak lagi menumpuk bila `onResume` beruntun (guard tampil).
- Status web: `stop()` me-reset `listeningPort` (tak ada `ctrlPort` basi).
- Cek versi: gagal (rate-limit/offline) di-cache 60 dtk agar tak hantam API
  tiap Start; `pin_kunci_elapsed` masuk daftar rahasia export.
- Start: status web yatim dibersihkan bila gagal setelah sempat start.

## [Belum rilis] — Audit bug: uji asap binary, jam mundur bot, resume 206, deadline header, encrypt parsial

### Perbaikan
- Binary: uji jalan `--version` di file `.tmp` sebelum tukar; unduhan rusak
  tak lagi menggantikan binary bagus + dilapor "terpasang" (pemicu
  auto-restart tak lagi menendang server ke binary rusak).
- ELF: cek diperketat (magic + `e_machine` ARM), bukan magic saja.
- Bot: jam mundur tak lagi menolak perintah/tombol membabi buta (jepit maks
  + tetap proses); peringatan dibatasi 1x/jam via jam monotonik; maks
  wall-clock persisten lintas restart.
- Unduh: respons 206 dicek `Content-Range` cocok dengan bytes lanjutan; bila
  menyimpang, ulang dari nol sebelum kuota terbuang.
- Status web: baca header dibatasi deadline total 20 detik sejak accept
  (slowloris tak menghabiskan pool hingga status legit balas 503).
- Backup: TTL cache ukuran folder pakai jam monotonik; `encryptFile` hapus
  output parsial saat gagal seperti `decryptFile`.

Semua perubahan penting dicatat di sini. Format mengikuti
[Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/). Versi aplikasi
mengikuti tanggal build UTC (`yyyy.MM.dd`); release GitHub mengikuti versi
Vaultwarden (`v<versi>`). APK, binary, dan web-vault terbaru selalu ada di
[GitHub Releases](https://github.com/tasirin1/tasirin-vaultwarden-host/releases).

## [Belum rilis] — Audit bug: WAL restore, antre pesan, anchor TLS, versi, swap, checkpoint

### Perbaikan
- Restore Telegram: buang `-wal`/`-shm` basi bila zip hanya berisi
  `db.sqlite3` agar backup bagus tak ditolak "korup".
- Pesan Telegram: antrean dibatasi 20 + buang tertua agar outage lama tak
  menumpuk OOM dan tak mengirim status basi berjam-jam kemudian.
- TLS: anchor dukung override (`filesDir/certs/github-chain.pem`, validasi
  PEM) + segar otomatis 1x sehari dari rilis (gagal diam sebelum asset ada).
- Update: banding versi semver terurut (tak lagi downgrade bila binary lebih
  baru) + marker mesin `[bin-updated]` gantikan `startsWith` di UI/bot.
- Web-vault: batalkan tanpa hapus bila rename `.bak` gagal (versi lama utuh).
- Backup: checkpoint WAL pakai mode tulis (sebelumnya READONLY selalu gagal
  diam); kunci sesaat (`SIBUK`) dicoba ulang 3x, bukan divonis korup.
- Restore lokal: nama DB diketatkan ke 3 file resmi (`db.sqlite3-evil`
  tak lagi ditulis sebagai sampah).
- Alarm: siaran ubah jam/zona tak memicu backup ulang bila backup <1 jam lalu.

## [Belum rilis] — Audit bug: flag bot macet, lockout jam miring, grace PIN, symlink, redirect //

### Perbaikan
- Bot: antrean pool pakai `AbortPolicy` + `runBeratDenganKunci` melepas kunci
  bila submit ditolak agar perintah berat berikut tak ditolak selamanya.
- PIN: kunci elapsed pendamping wall-clock (`pin_kunci_elapsed`) — reset jam
  tak lagi membuka lockout, jam STB rusak tetap mengunci 5 menit normal, dan
  reboot tak mengunci permanen (sisa di atas durasi dianggap basi).
- PIN: status buka disebar dua arah (Main <-> Settings) agar grace 60 detik
  benar-benar tak meminta dua kali.
- Folder data: validasi path kanonis menutup celah symlink ke area sistem.
- Log: siram buffer sebelum ganti file log folder baru; export menyamarkan
  token sekali saja di `LogExport`.
- Redirect: `//host/path` kini warisi skema https + ganti host (test
  `redirectProtokolRelatifTetapHttps` kini lolos).
- TLS: `daysLeft` bedakan belum-valid (-2, jam miring) dari kedaluwarsa agar
  log memberi petunjuk betulkan tanggal & jam.
- Unduh: kunci per-file pakai string ter-intern tanpa map yang tumbuh.

## [Belum rilis] — Audit bug: PIN reboot, port, masker token, rebind, callback, kuota, TLS

### Perbaikan
- PIN: lockout brute-force pakai wall-clock agar reboot tak mereset hitungan;
  nilai lama era elapsed dianggap kedaluwarsa agar tak mengunci permanen.
- Port: nilai rusak (huruf/kosong/di luar 1-65535) jatuh ke default agar health
  check tak membangun URL invalid lalu restart beruntun.
- Log: perbaiki escape regex pola penyamaran (escape tunggal `\d`/`\.`/`\S`
  tidak valid di Java hingga file tak bisa dikompilasi) + samarkan token bot
  mentah tanpa awalan "bot" agar tak bocor via
  bagi/salin/simpan/SSE; jam biasa tak ikut tersamarkan.
- Status web: rebind segera saat service start bila mode bind kedaluwarsa
  (token diubah saat jalan), tak menunggu health tick.
- Bot: tombol inline tanpa message ditolak (fail-closed) agar tak bisa
  di-replay selamanya.
- Provider: kunci privat yang dibungkus zip/enc/txt tetap ditolak, file biasa
  seperti monkey.zip/monkey.pem tetap lolos.
- Auto-update: VPN/WiMAX di Android 5/6 dianggap non-kuota seperti WiFi.
- TLS: hormati masa berlaku sertifikat (jam STB miring picu regenerasi).
- Redirect: protokol-relatif `//` eksplisit mewarisi https.

## [Belum rilis] — Audit bug: start basi, IPv6 LAN, PIN restore, provider, embedded-IPv4

### Perbaikan
- Start gagal: penanda jalan (`runningDataDir/Port/Https/AdminToken`)
  ikut dibersihkan agar status web/health tak menunjuk port/folder basi.
- TLS: kumpulkan IPv6 LAN (tanpa zona, tanpa link-local) + SAN `::1`
  agar dukungan IPv6 benar jalan (sebelumnya dead code IPv4 saja).
- Restore: status PIN selalu ikut perangkat, zip tak tepercaya tak bisa
  mematikan PIN (anti downgrade pengaman).
- Provider: pola kunci privat presisi (`ca-key.pem`, `key.pem`, `*.key`)
  agar `monkey.zip` tak ikut ditolak.
- TLS: dukung IPv6 embedded-IPv4 (`::ffff:192.168.1.1`) di SAN.
- Test: `pinOnHasilRestore`, `kunciPrivat`, IPv6 embedded.

## [Belum rilis] — Audit bug: datadir, restore, TLS IPv6, log, status

### Perbaikan
- Folder data: tolak traversal `..`, slash ganda, dan area sistem
  (`/system`, `/vendor`, `/proc`, `/sys`, `/dev`, `/data` kecuali
  `/data/data`) agar salah ketik tak menulis ke lokasi berbahaya.
- Start: pulihkan sisa swap web-vault terpotong crash (`.bak` yatim
  dikembalikan bila folder aktif hilang, dibuang bila aktif sehat).
- Restore lokal: baca 2 byte magic dengan loop (anti salah kira ZIP),
  hitung semua entri zip sejak awal agar batas anti zip-bomb tak lolos.
- TLS: SAN sertifikat dukung IPv6 (termasuk `::` dan `[::1]`) agar HTTPS
  LAN IPv6 lolos verifikasi hostname.
- Log: pola samaran dikompilasi sekali (hemat CPU polling tiap detik).
- HTTPS: kegagalan trust tambahan dicatat ke logcat (tidak lagi bungkam).
- Status web: path tak dikenal balas 404 (halaman status tetap di `/`).
- Test: dataDir traversal/sistem + parsing IPv6.

## [Belum rilis] — Bersih: 3 warning lint CI (jelas)

### Perbaikan
- Suppress ApplySharedPref di PinGate + TgBot (commit() sinkron disengaja:
  anti brute-force & anti replay perintah bot).
- Disable check ScopedStorage (All-files access disengaja; rilis via GitHub).

## [Belum rilis] — Bersih: 3 warning anotasi CI

### Perbaikan
- Suppress deprecation `defaultDataDir()` (satu jalur API 21-32).
- Hapus flag deprecated `android.nonTransitiveRClass` (default AGP 8+).
- Notifikasi update: SuppressLint MissingPermission (targetSdk 28 exempt) +
  try/catch SecurityException untuk forward-compat.

## [Belum rilis] — Audit bug: provider kunci, alarm, offset, unduh, TLS, port

### Perbaikan
- Provider: tolak kunci privat (*key*.pem, *.key) agar tak terbagi via URI.
- Alarm: jadwal ulang backup dulu di tiap pemicu agar alarm exact sekali-tembak tak putus.
- Bot: offset getUpdates ditulis commit() sinkron agar perintah tak replay.
- Unduh: kunci per-file ConcurrentHashMap (ganti WeakHashMap) + panjang konten long.
- HTTPS: negosiasi TLS umum (1.2+1.3) agar cepat di HP baru, tetap jalan di Android 5/6.
- Port: SO_REUSEADDR agar TIME_WAIT tak dikira sibuk; jadwal bot tanpa cancel-buta.
- Test: FileShareProviderTest + panjangKonten di UpdaterTest.

## [Belum rilis] — Audit bug: restore lokal, health-spam, update, backup, provider

### Perbaikan
- Restore lokal: hentikan server dulu, checkpoint WAL + buang -wal/-shm basi,
  rollback salinan pengaman juga saat gagal di tengah tulis.
- Health: tick dimatikan setelah stop terminal agar tak spam Telegram/log.
- Update: `--version` dibatasi 10 detik; respons HTTP kecil dibatasi 1 MB.
- Web-vault: tukar via `.bak` agar versi lama selamat bila gagal pasang.
- Backup lokal: tolak DB korup + tulis via tmp+rename; unduhan restore
  dibatasi 200 MB.
- Provider: `.enc` boleh dibagikan agar export terenkripsi tidak rusak.
- Health TLS loopback: gagal tertutup bila CA hilang (tanpa trust-all).

## [Belum rilis] — Audit bug: restore DB, smoke test binary, PIN, shim, CI

### Perbaikan
- Restore Telegram: batalkan bila server tak mau berhenti (cegah timpa SQLite
  hidup), checkpoint WAL sebelum salinan pengaman, dan singkirkan -wal/-shm
  basi saat rollback.
- Binary: tolak ELF bukan ARM (cek e_machine), smoke test `--version` kini
  menggugurkan (cache diunduh ulang, hasil unduh rusak dibuang).
- PIN: grace buka-kunci simetris Main ↔ Settings; counter gagal ditulis
  sinkron agar tak hilang bila app dibunuh.
- Shim getrandom: buka-ulang EBADF dikunci + cegah double-close antar thread.
- CI: tolak tag `null`/kosong dari `jq` agar gagal dengan pesan jelas.

## [Belum rilis] — Audit bug: kunci PIN, rollback Telegram, export, marker, token URL

### Perbaikan
- Kunci PIN: nilai wall-clock basi dianggap kedaluwarsa, cegah kunci permanen
  pasca-migrasi ke jam monoton.
- Telegram: perintah terbuang karena jam mundur kini dibalas notifikasi
  diagnostik sekali per batch, tak lagi hilang diam-diam.
- Export config: plaintext sementara pindah ke cache internal.
- Web-vault: penanda mesin `[wv-updated]` + satu pintu deteksi via
  `TgBot.webVaultBerubah` (AutoUpdate ikut).
- Status web: `no-referrer` + kupas `?token=` dari address bar via
  `history.replaceState`.

## [Belum rilis] — Audit bug: masker export, pool status, SSL, jam, unduh, dekrip, PIN

### Perbaikan
- Export log kini dimasker seperti bagi/salin, token tak bocor ke Download publik.
- Status web: pool koneksi berbatas + tolak cepat 503, antrean serbuan LAN tak OOM.
- Health TLS: factory volatile + double-checked locking, cap CA pakai hash isi.
- Telegram: jam mundur drastis ditolak fail-closed; grace PIN + kunci brute-force
  pakai jam monoton agar mundur/maju jam tak membuka kunci.
- Unduhan: kunci per-file agar binary/shim/web-vault tak saling blokir.
- Restore: fallback PBKDF2-SHA1 hanya untuk galat autentikasi, hemat CPU.

## [Belum rilis] — Audit kode: akses storage 11+, latch bot, migrasi port

### Perbaikan
- Storage Android 11+: akses dinilai dari All files access saja (izin runtime
  biasa tak lagi dianggap cukup), cegah Start gagal berulang.
- Bot Telegram: wake lock best-effort agar kunci tugas berat tak macet selamanya;
  tombol inline bertanggal masa depan tak wajar kini ditolak.
- Migrasi port 8080→8088 sekali jalan pakai flag (port 8080 pilihan user aman).
- Hasil izin runtime dibaca per nama izin; Start pakai folder efektif;
  redirect rusak bertahan di URL terakhir yang baik.

## [Belum rilis] — Izin penyimpanan semua Android

### Perbaikan
- Izin storage berlaku di semua Android: hapus `maxSdkVersion` di manifest,
  tambah `READ_EXTERNAL_STORAGE` + `MANAGE_EXTERNAL_STORAGE` +
  `requestLegacyExternalStorage`, dan alur izin runtime terpusat (`StoragePerm`).
  Android 11+ diarahkan ke All files access agar `/sdcard/vaultwarden` writable;
  Start dibatalkan dengan pesan jelas bila izin belum diberikan.

## [Belum rilis] — Audit ulang: plaintext restore, masker log, tabrakan backup, jam NTP

### Perbaikan
- Restore Telegram: dekrip sesudah user tekan Ya (plaintext tak mengendap
  di cache bila batal).
- Masker log: pola token/chat_id samarkan sampai spasi/`&` (kurung siku tak
  lagi membocorkan sisa token via bagi/clipboard).
- Nama backup unik: stempel milidetik + kunci + suffix `-1`/`-2` agar dua
  backup se-detik (bot vs UI, create vs pre-backup) tak saling menimpa.
- Verifikasi zip selaras SQLite: tolak db <512 byte (termasuk entri ukuran
  tak diketahui) sebelum diunggah ke Telegram.
- Sertifikat hemat: daftar IP diurutkan agar `ips.txt` stabil dan leaf tak
  diregenerasi sia-sia tiap Start.
- PIN ringan: hash basi dibatalkan tiap ketikan (termasuk saat <4 digit).
- Hint restart jujur: bandingkan nilai trim vs running (spasi tepi tak picu
  peringatan palsu).
- Timeout tahan NTP: deadline stop/wait + tunggu DB pakai `elapsedRealtime`.
- Status web: param `?token` tak peka huruf (selaras skema Bearer).
- Checksum tahan BOM: kupas `\uFEFF` sebelum validasi 64 hex.

## [Belum rilis] — Audit bug 3: unduh campur, tombol Telegram, auth web, zip TLS

### Perbaikan
- Unduh tak campur: `unduhKeTmp()` hapus `tmp` + mulai dari nol setiap ganti
  URL (fallback latest ↔ URL asli), cegah file campuran 2 asset + SHA-256 gagal.
- Tombol inline awet: `tanganiCallback()` tak lagi pakai `message.date` untuk
  cek basi (sebelumnya semua tombol mati setelah 5 menit); cek basi tetap
  untuk pesan ketik, tanpa tanggal kini basi (fail-closed).
- Tombol + PIN: perintah berbahaya via tombol diberi tahu agar ketik manual
  dengan PIN bila PIN aktif (sebelumnya selalu ditolak diam-diam).
- Status web ketat: semua path wajib token bila admin token diset (sebelumnya
  hanya `/api/` dan `/`).
- Restore aman: hanya `app-config.json`, `db.sqlite3*` (3 file), dan 4 file
  `tls/` resmi (`ca.pem`, `cert.pem`, `key.pem`, `ca-key.pem`); `tls/evil.sh`
  ditolak. Ekstrak web-vault tolak entri `.`/`./`.
- Enkripsi tak NPE: `Cipher.update()` null-safe sebelum `write` (GCM buffering).
- Cleartext terkunci: `usesCleartextTraffic=false` + `network_security_config`
  hanya izinkan cleartext ke `127.0.0.1`/`localhost`.
- Folder data ikut perangkat: bawaan pakai `Environment` + fallback
  `/sdcard/vaultwarden`; PIN legasi tak peka huruf; stempel export milidetik.

## [Belum rilis] — Audit bug 2: biner parsial, zip parsial, balap bot

### Perbaikan
- Biner manual korup ditolak: `copyBinary()` tulis ke `.tmp` + `sync` + rename,
  parsial dihapus (sebelumnya lolos cek ukuran+magic ELF).
- Backup jujur: `createBackupZip()` tulis `.tmp` + rename agar zip parsial tak
  mengusir backup bagus via retensi.
- Bot tak balapan: tugas berat (`/backup`, `/restore`, `/update`, `/webvault`)
  saling-menunggu via `TUGAS_BERAT` (sebelumnya 3 thread bebas tumpang tindih).
- Export config aman: plaintext sementara selalu dihapus via `finally` walau
  enkripsi gagal (sebelumnya tertinggal di `/sdcard` publik).
- Status web ketat: header >64 baris tanpa tuntas ditolak `431` (sebelumnya
  diproses seolah lengkap).
- Log anti-bocor: `samarkanLog()` juga mask kredensial format JSON
  (`"tg_token": "..."`).

## [Belum rilis] — Audit bug: NPE prefs, auth, TLS, backup

### Perbaikan
- Anti-crash NPE: baca token/chat prefs lewat `Util.amanTrim()` (null-safe) di
  TgBackup, TgBot, BootReceiver, dan AlarmReceiver.
- Status web fail-closed: `checkToken()` menolak akses bila prefs tak terbaca
  (sebelumnya terbuka tanpa auth); guard query null.
- Health-check loopback segar setelah regenerasi CA: cache `SSLSocketFactory`
  gugur bila `tls/ca.pem` berubah (mtime+ukuran), cegah stop palsu sehabis ganti IP.
- Backup jujur: `PRAGMA quick_check` sebelum unggah (tolak DB robek tersalin
  saat server menulis); file verifikasi enkripsi pindah ke cache internal
  (bukan `/sdcard` publik).
- Alarm Android 12+: cek `canScheduleExactAlarms()` + izin
  `SCHEDULE_EXACT_ALARM`, fallback inexact bila ditolak.
- PIN `unlocked`/`pauseStamp` jadi `volatile` (visibilitas antar-activity).
- Sertifikat tahan 2050+: waktu DER pakai GeneralizedTime bila tahun >= 2050.

## [Belum rilis] — Perintah Telegram /ca kirim CA

### Ditambahkan
- Perintah bot **`/ca`**: mengirim CA HTTPS aktif (`ca.pem` publik, tanpa
  kunci privat, tanpa PIN seperti `/status`) ke chat Telegram resmi — teruskan
  file-nya ke HP lain lalu install sebagai CA certificate. Menu `/` (revisi 3)
  + tombol inline + `/help` diperbarui; cakupan unit test ikut (14 perintah).

## [Belum rilis] — Tombol Bagikan CA ke HP lain

### Ditambahkan
- Tombol **Bagikan CA (ke HP lain)** di Settings → Sertifikat: mengirim CA
  aktif (`ca.pem` saja, tanpa kunci privat) via Bluetooth/WhatsApp/Telegram.
  Di HP tujuan simpan lalu install sebagai CA certificate.

## [Belum rilis] — Perbaiki Install Cert menunjuk CA lama

### Perbaikan
- Tombol **Install Cert** dan info masa berlaku cert kini membaca CA/cert aktif
  di internal (`getFilesDir/tls`), bukan arsip `/sdcard` — bila **dulu bisa
  login lalu gagal** setelah update (regenerasi CA versi 5→6) atau migrasi TLS
  internal, hapus CA lama di HP lalu install ulang CA baru via tombol tersebut.
- Hint sertifikat dan README diperjelas: lokasi CA aktif, kapan wajib install
  ulang, dan aplikasi Bitwarden resmi yang menolak self-signed (pakai HTTP
  di LAN tepercaya atau web-vault browser).

## [Belum rilis] — Health check toleran DB lambat

### Perbaikan
- Health check tidak lagi membunuh server sehat: `/alive` butuh DB sehingga di STB lambat bisa timeout/500 sementara `/api/config` tetap 200 — kini sehat bila salah satu 200, timeout 8 dtk, log rinci kode+alasan, dan 3x gagal tapi TCP masih tersambung tidak dihentikan.
- Health check pakai port/skema yang sedang berjalan (bukan prefs yang mungkin sudah diubah) dan pin CA internal dulu agar sesuai cert aktif.

## [Belum rilis] — Audit efisiensi, bug, keamanan, sampah

### Keamanan
- Binary manual di folder data kini DITOLAK bila SHA-256 belum diisi (sebelumnya jalan tanpa verifikasi).
- Unduhan GitHub hanya ikuti redirect https (cegah downgrade http); header Range dipertahankan saat resume.
- Restore ZIP dan web-vault dibatasi total ukuran + jumlah entri (anti zip-bomb); hapus file restore hanya bila temp internal.
- Counter brute-force PIN (`pin_gagal`/`pin_kunci_sampai`) tidak ikut backup dan tidak bisa direset via impor.
- PIN lama otomatis migrasi ke PBKDF2 saat verifikasi sukses di bot (seperti UI).
- Log bagi/salin samarkan `token=`, `chat_id`, dan token bot; status web kirim `no-store` + `nosniff`.
- Peringatan bila kunci TLS di storage publik (FAT, chmod tak berlaku); health-check cari CA internal juga.
- Bot dukung chat `@username` selain ID numerik; pesan masa depan (jam STB lambat) tidak dibuang.

### Perbaikan
- Satu penulis log terpusat `ServerService.catatLog()` (stempel + trim + versi); pratinjau log awal dibatasi ekor 30 KB.
- Halaman status `/` wajib token bila admin token diset; SSE pakai `no-store`.
- Kunci TLS dimigrasi ke internal (tak lagi di `/sdcard`); backup boot tertunda dijalankan susulan.
- Semua POST Telegram tolak redirect; unduh file Telegram hanya ikuti redirect https.
- `effectivePort()` murni tanpa tulis disk; migrasi `8080` sekali saat service dibuat.
- Refresh log pakai versi monotonik (anti balapan trim+append); `FileShareProvider.query()` kembalikan cursor nama/ukuran.
- Shim `getrandom` pakai ulang fd `/dev/urandom` (hemat open/close) + pulih bila EBADF.
- Utilitas murni baru `Util` + `UtilTest` (cocok chat, pesan segar, redirect aman, batas unzip, hapus aman).

## [Belum rilis] — Audit keamanan & keandalan

### Keamanan
- Health-check HTTPS loopback pin CA `tls/ca.pem` bila ada; fallback trust-all hanya untuk `127.0.0.1`/`localhost` + verifier host ketat (`ServerService.loopbackSslFactory`).
- Status web dukung `Authorization: Bearer <admin-token>` selain `?token=`; JSON tambah `protected:true/false`; cache status direset tiap start/stop agar tak basi.
- Perintah Telegram sensitif (`/start`, `/restart`, `/backup`, `/log`, `/crashlog`, `/webvault`) kini wajib PIN bila PIN aktif, seperti `/stop`/`/update`/`/restore`.
- `FileShareProvider` hanya bagikan `.pem/.crt/.cer/.zip/.json/.txt` di `tls/` & `backups/`; DB mentah tetap ditolak.
- `WRITE_EXTERNAL_STORAGE` dibatasi `maxSdkVersion=28`.

### Perbaikan
- `LogActivity` ekspor log pakai try-with-resources (tutup stream aman).
- `TgBackup.applyPrefsFromJson` tanpa `clear()` agar backup rusak tak menghapus seluruh pengaturan.

## [Belum rilis] — Tampilan utama, backup Telegram & efisiensi

### Ditambahkan
- **Menu perintah Telegram otomatis**: app mendaftarkan menu bot (tombol `/`)
  via `setMyCommands` setiap token disimpan (`TgBot.refreshMenuAsync`, sekali
  per token, best-effort di thread latar) — tidak perlu setting manual di
  @BotFather. Payload 13 perintah dibuat manual (`menuPayload`) agar bisa
  di-unit-test JVM; `README.md`/`README.en.md` diperbarui. `README.en.md`
  disinkron ulang mengikuti struktur Indonesia + CI 4 job.
- **`/webvault` restart otomatis**: perintah bot restart server sendiri bila
  web vault benar-benar berubah dan server sedang jalan (seperti `/update`);
  tombol Update Web Vault di pemeliharaan ikut restart otomatis, dan restart
  hanya terjadi bila file berubah (`webVaultBerubah`, ter-unit-test). Menu
  bot `webvault` menjadi `Update web vault + restart` (revisi menu 2,
  token lama daftar ulang sekali).
- **Bot responsif + tombol inline**: polling Telegram 60 dtk → 20 dtk
  (long-poll 15 dtk, trigger pertama 10 dtk); `/help` mengirim keyboard
  inline 13 perintah — ketuk tanpa mengetik (`callback_query` hanya dari
  chat resmi, basi >5 mnt diabaikan, `answerCallbackQuery` best-effort).
  Perintah berbahaya via tombol tetap wajib PIN.
- **Backup terverifikasi otomatis**: `backupNow` memeriksa zip sebelum
  diunggah (`verifikasiZip`: wajib ada `db.sqlite3` ber-header SQLite) dan
  uji-buka round-trip hasil enkripsi dengan passwordnya; backup korup
  dibatalkan sebelum dikirim ke Telegram. Ter-unit-test JVM.
- **Domain lokal**: kolom opsional **Domain lokal** di kartu Server
  (mis. `vault.lan`, boleh `host:port`/URL) — dipakai sebagai `DOMAIN`
  Vaultwarden, URL jaringan, dan tombol Salin URL; sertifikat HTTPS ikut
  memuatnya sebagai SAN DNS (regenerasi otomatis saat domain/IP berubah,
  versi cert naik ke 6). Kosong = perilaku lama (IP LAN otomatis).
- **Layar awal sederhana**: hanya status server, tombol Start/Stop, log
  realtime, dan tombol Simpan .txt. Semua pengaturan (folder data, port,
  HTTPS, PIN, Telegram, pemeliharaan, sertifikat, export/import) pindah ke
  layar **Settings** lewat tombol **titik tiga (⋮)** — ramah remote TV
  (D-pad). Tombol buka-log layar penuh dihapus dari awal (log penuh tetap
  ada di Settings); brand diperkecil dan kotak info versi ala Settings
  ditampilkan di bawah brand.
- **Poles layar awal**: brand tanpa tagline, kartu info gabungan (versi +
  URL ketuk-untuk-salinan + uptime), tombol Start/Stop sticky di footer,
  pratinjau log bisa diciutkan/bentangkan, peringatan update menjadi tombol
  lompat ke Settings, dan status kosong log yang membantu.
- **Status pindah + landscape optimal**: status server menjadi banner
  full-width di kartu server (tidak lagi di samping Start; footer hanya
  tombol Start full-width), dan varian `layout-land` dua kolom
  (info+server | log penuh) untuk TV/STB horizontal.
- **Tampilan utama baru**: hero gradien brand (ikon + tagline), kartu Server
  dengan label aksi cepat & kotak info, tombol utama berisi, palet
  terang/gelap diselaraskan — ID dan navigasi D-pad tidak berubah.

### Diubah
- **Backup/restore terpadu**: duplikasi logika backup/restore
  dikonsolidasikan ke `TgBackup` (dipakai tombol UI & perintah bot).
- **Perintah `/log` hemat**: pakai `tailLog` tanpa salinan buffer penuh.
- **Efisiensi CPU/RAM/I-O**: kurangi alokasi dan I/O berlebih di semua modul.
- **Runner CI dikunci** ke `ubuntu-24.04` agar bebas notice migrasi.
- **Backup otomatis saat tanggal berganti**: jadwal Telegram tidak lagi
  tiap 24 jam dari backup terakhir, melainkan tepat tengah malam (00:01)
  setiap hari via alarm + siaran ganti tanggal/jam/zona; bila device mati
  saat tengah malam, backup dikejar saat boot/Start berikutnya. Backup
  terjadwal ganda di hari yang sama dilewati otomatis. Label toggle,
  README, dan test (`sudahGantiHari`/`nextMidnight`) ikut diperbarui.
- **Settings dipangkas**: tombol **Sertifikat** (cuma dialog bantuan) dan
  **Status Web** dihapus — panduan sertifikat jadi hint di bawah
  **Install Cert**; dua toggle backup Telegram digabung jadi satu
  **Backup otomatis tiap 24 jam & saat Start** (migrasi pref lama otomatis);
  backup Telegram selalu menyertakan pengaturan + sertifikat (checkbox dihapus).
- **QR koneksi dihapus** untuk menghemat ukuran APK: encoder mandiri
  (`QrEncoder`, tanpa dependensi) + dialog + tombol + unit test + dependensi
  zxing (scope test) dibuang; URL dibagikan lewat **Salin URL**.
- **Backup tak lagi membawa kredensial**: `admin_token`, `tg_token`,
  `tg_chat`, `tg_pass`, `pin_hash` dikecualikan dari JSON backup/export
  (tidak mampir ke cloud Telegram / file polos); restore/import
  mempertahankan nilai perangkat. Dekripsi gagal tak lagi menyisakan
  plaintext parsial; KDF backup baru SHA256 (fallback baca SHA1 lama).
- **Telegram**: perintah basi (>5 mnt) diabaikan, long-poll 50 dtk + guard
- **Landscape dirapikan**: margin tepi layar lebar (TV/tablet) turun dari
  200dp ke 48dp (`values-sw600dp-land`, portrait tak berubah) sehingga dua
  kolom tak lagi terjepit; footer Start dibungkus wadah `card_bg` seperti
  portrait; padding kartu 16dp ke 12dp agar hemat tinggi layar; D-pad bisa
  pindah kiri/kanan antar-kolom (info/server <-> log). ID kedua orientasi
  tetap sama persis.
  anti tumpang-tindih; banding admin token constant-time; peringatan bila
  Admin Token kosong (status/log LAN terbuka).

- **UI tak lagi macet saat PIN/log/backup**: hash & verifikasi PIN
  (PBKDF2 120rb iterasi) pindah ke worker thread — sebelumnya tiap ketikan
  PIN di Settings memblokir UI; pratinjau log layar awal hanya menempel
  selisih baris baru (delta) alih-alih salin + `setText` seluruh buffer
  300 KB tiap 500 ms; `sleep(5000)` tebakan sebelum backup-otomatis-saat-Start
  (duplikat di 2 layar) diganti `backupTungguDb` yang poll kesiapan DB tiap
  500 ms (maks 30 dtk, helper murni `dbSiap` + test).

### Dihapus
- **Domain lokal kustom**: kolom **Domain lokal** dihapus dari Settings
  beserta logika `DOMAIN`/SAN DNS-nya — nama seperti `vault.lan` butuh DNS
  sendiri di jaringan (router/AdGuard) sehingga di kebanyakan STB/HP tidak
  pernah bisa dibuka dan hanya membingungkan. Akses selalu memakai IP LAN;
  sertifikat HTTPS tetap dibuat ulang otomatis saat IP berubah (CA tetap).

### Diperbaiki
- **Patch DNS hilang tertimpa patch TLS (favicon tetap 500, `ndk-context`)**:
  skrip CI menulis patch DNS ke file lalu patch TLS menimpa dari variabel basi
  sehingga binary rilis berisi patch TLS tapi kehilangan patch DNS — ikon
  panic `android context was not initialized` + `LazyLock ... poisoned`.
  Skrip kini menampung kedua patch di satu variabel dan verifikasi keduanya
  ada di file hasil. Revisi patch binary naik ke 3 agar cache basi terunduh
  ulang sekali via Start/Cek Update.
- **Binary lama tak pernah refresh padahal versi sama (favicon tetap 500)**:
  patch TLS favicon CI memperbaiki binary tanpa ganti versi Vaultwarden,
  tapi `Cek Update`/`Start` menganggap `1.37.3` yang ter-cache sudah terbaru
  sehingga binary pra-patch dipakai selamanya. Kini ada revisi patch binary
  (`bin_patch_rev=2`): Start melewati cache basi dan mengunduh ulang sekali,
  `Cek Update` pun memaksa unduh walau versi sama. Pengguna cukup tekan
  **Start** (atau **Cek Update**) setelah update APK.
- **Ikon website (favicon) selalu 500**: reqwest memakai
  `rustls-platform-verifier` yang wajib init dari JavaVM — binary standalone
  panic di tiap HTTPS keluar (`Expect ... to be initialized`). Patch CI
  (`http_client.rs`, teruji polanya) kini memakai verifier webpki + bundel
  root Mozilla di Android; DNS patch dipertahankan. Diterima lewat
  **Cek Update** (ikut binary, tanpa install ulang APK).
- **Restore database dari .zip lokal selalu gagal**: deteksi magic `PK`
  memakan 2 byte pertama stream sehingga `ZipInputStream` membaca header
  rusak (semua zip valid ditolak walau berisi `db.sqlite3`); stream kini
  dibungkus `PushbackInputStream` agar byte dikembalikan utuh. Nama entri
  juga dinormalisasi (`TgBackup.normalisasiEntriZip` + test) sehingga zip
  manual berisi folder pembungkus (`vaultwarden/db.sqlite3`) tetap terbaca;
  entri licik (`..`/drive/absolut) tetap ditolak.
- **Sertifikat bisa dipasang di HP lain**: skema TLS baru CA lokal
  (`tls/ca.pem`, CA:TRUE, 10 tahun) + sertifikat server (`cert.pem`, SAN IP,
  ditandatangani CA). Yang dipasang cukup `ca.pem` sebagai CA certificate
  (tanpa private key, diterima installer Android baru); CA stabil saat IP
  berubah sehingga tak perlu install ulang. Tombol Install Cert membagikan
  `ca.pem`, backup lengkap ikut membawa `ca.pem`/`ca-key.pem`.
- **Restore/import tak lagi membangkitkan kredensial lama**: `applyPrefsFromJson`
  menghapus `admin_token`/`pin_hash` impor bila perangkat tak punya nilai
  (backup lama yang masih menyimpan secrets tak lagi bocor saat restore),
  dan `pin_on` dipaksa mati bila hash kosong agar tak fail-open
  (helper murni `pinAktif` + test). Teks dialog Export yang masih menyebut
  file berisi token/PIN/password diluruskan (kredensial memang tak ikut export).
- **Banding token status web toleran spasi salinan**: helper murni
  `tokenCocok` (trim kedua sisi + constant-time + test regresi).
- **Warning linker tak lagi mengotori versi terpasang**: di STB kernel lama baris pertama output `binary --version` adalah `WARNING: linker: ... unsupported flags DT_FLAGS_1` (tidak fatal) sehingga versi terbaca sebagai teks warning (`versi: WARNING: linker...` di log Start) dan deteksi versi terpasang gagal (regex tak cocok). Baris noise kini disaring di `ServerService` + `Updater` (helper `isNoiseLinker` + test regresi) — log Start menampilkan versi asli; saringan log realtime yang sudah ada tak berubah.
- **HTTPS di STB kernel lama (`bad TLS ticketer: failed to get random bytes`)**: HTTP jalan tapi HTTPS selalu crash exit 1 — crate `getrandom` 0.2 (dipakai `ring` 0.17 ke ticketer TLS Rocket `rustls`) memanggil `syscall(SYS_getrandom)` mentah yang juga `EINVAL` di kernel 3.14 tanpa fallback (bukan `ENOSYS`), sehingga shim lama yang hanya mencegat `getrandom()` libc tidak berpengaruh di jalur TLS. Shim kini mencegat kedua simbol (`getrandom` + `syscall`) dan melayani keduanya dari `/dev/urandom`; uji interposisi CI mencakup kedua jalur. Pengguna cukup tekan **Cek Update** (shim baru terunduh otomatis + terverifikasi SHA-256) lalu Start lagi. App juga mengenali pesan ticketer sebagai masalah kernel lama (auto-restart dimatikan + saran, bukan salah sertifikat) + test regresi.
- **Shim getrandom selalu ditolak sebagai "tidak valid"**: batas ukuran minimum (`SHIM_MIN_BYTES` 4096 byte) lebih besar dari shim rilis yang asli (2712 byte, stripped) sehingga setiap unduhan yang checksum-nya cocok langsung dihapus dan Start gagal terus di STB kernel lama (mis. ZTE B860H) — batas kini 1024 byte + test regresi, dan pesan galat menyebut ukuran file agar mudah didiagnosis.
- **Crash exit 9 tepat setelah "Rocket has launched" di STB 1 GB (ZTE B860H)**: bukan shim getrandom (tanpa panic, exit 101/1) melainkan SIGKILL dari sistem (LowMemoryKiller/RAM penuh). Server kini hemat RAM (`ROCKET_WORKERS` 2→1, `DATABASE_MAX_CONNS=2`) dan crash exit 9 menampilkan saran spesifik (tutup aplikasi lain/reboot STB) di log, status, dan Telegram (`saranCrash` + test).
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
- **Zip-slip restore Telegram ditutup**: `restoreFromZip` (dipakai `/restore`
  & tombol Restore Telegram) hanya mengecek `startsWith(basePath)` tanpa
  separator sehingga entri `../vaultwarden-evil/...` lolos dan tertulis di
  luar folder data. Kini memakai allowlist `normalisasiEntriZip` yang sama
  dengan restore UI lokal (hanya `db.sqlite3*`, `tls/*`, `app-config.json`)
  + cek canonical pakai separator; cek lokal ikut dikeraskan.
- **Batas baca anti zip-bomb**: `app-config.json` dari zip tak tepercaya
  dibaca maksimal 1 MB (`bacaTerbatas`) agar zip bomb kecil tak OOM-kan
  STB 1 GB.
- **`stop`/`restart` aman dari background**: keduanya kini memakai
  `startForegroundService` di Android 8+ seperti `start`/`backupNow`
  (sebelumnya `startService` mentah bisa `IllegalStateException` dan
  `/stop` lapor gagal palsu).
- **Tugas berat tak tumpang tindih**: `runBusy` dikunci `AtomicBoolean`
  (tugas kedua ditolak dengan toast) dan `uiBusy` kini `volatile`.
- **Centang PIN tak macetkan UI**: `flushPinHash` (PBKDF2 120rb iterasi,
  s.d. 5 dtk di STB lambat) tak lagi dipanggil di UI thread — mengaktifkan
  PIN saat hash masih antre kini diminta coba lagi sebentar; mematikan PIN
  langsung tersimpan.
- **Duplikasi dihapus**: `autoUpdateCheck` (±100 baris di Main & Settings)
  pindah ke satu helper `AutoUpdate` (termasuk `tanpaKuota` + notifikasi);
  blok retry + salin-hash unduhan binary/shim (±80 baris) gabung ke helper
  `bolehCobaLagiUnduh`/`tundaCobaLagiUnduh`/`salinSambilHash` + test.
- **Start lebih cepat**: `detectBinaryVersion` hanya baca 2 baris versi lalu
  destroy (tanpa tampung 8 KB + tunggu 15 dtk bila binary aneh).
- **Kecil-kecil**: `fetchChecksum` pakai try-with-resources (tak bocor soket),
  `extractFileId` via `JSONObject` (fallback manual), semua tulis file teks
  UTF-8 eksplisit (tak lagi `FileWriter` charset bawaan), wakelock polling
  bot 60 dtk → 30 dtk (long-poll kini 15 dtk).

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
- **Ronde audit hemat 9.481 baris**: satu helper unduh retry terklasifikasi
  (404 tak di-retry) dipakai binary/shim/web-vault; pool tetap status web;
  `folderBytesCached` kunci absolute-path; versi bundled di-cache per proses
  + per layar; ekstrak web-vault ke `web-vault.new` lalu rename-swap dengan
  cek zip-slip leksikal (tanpa canonical per entri); `tailLog`/SSE tanpa
  split/concat per baris; `collectIps` tak-berubah; `humanBytes` manual;
  chat-id Telegram di-parse sekali (ter-unit-test `amanEntriZip`,
  `rentangKosong`).

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
