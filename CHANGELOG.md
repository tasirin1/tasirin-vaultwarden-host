# Changelog

## [Belum rilis] — Audit 81: hapus throttle smoke test yang tak pernah dipakai (Android 5+)
- `ServerService`: buang `smokeGagalAt`, `SMOKE_GAGAL_TTL_MS`, dan `smokeGagalBaruSaja()` beserta tiga penulisannya. Nilainya tak pernah dibaca jalur produksi (hanya unit test), sehingga komentar "anti unduh ulang tiap Start" menyesatkan: throttle unduhan yang benar-benar bekerja adalah `KEY_BIN_DL_GAGAL_AT` + `bolehCobaUnduhLagi` (persisten, dipakai `resolveBinary` dan `AutoUpdate`). Smoke test `--version` tetap dijalankan tiap Start karena itu gerbang terakhir sebelum binary dieksekusi.
- Test `smokeGagal_throttleGagalBaru` dihapus; kedua nama metode dicatat di `tools/bekas-hapusan.txt`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 80: kegagalan cek update tidak lagi sunyi (Android 5+)
- `AutoUpdate.cek`: catch terluar tidak lagi kosong — kegagalan (prefs korup, parsing versi, callback UI) dicatat sebagai `[app] Cek update gagal: ...` lewat `aksi.catat`, yang dibungkus try/catch agar kegagalan callback tak menular. Sebelumnya semua galat ditelan sehingga keluhan \"auto-update tak pernah jalan\" tidak meninggalkan jejak apa pun di log aplikasi.
- Bila `versiTargetBinary` mengembalikan null (offline/rate-limit), kini dicatat `[app] Cek update dilewati: versi terbaru tak terbaca (offline/rate-limit).` alih-alih return tanpa keterangan. Tanpa test baru (butuh Context/Android). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 79: verifikasi CI tetap jalankan cek diff saat dispatch/schedule
- `tools/verifikasi-patch.py`: rentang `--rentang` yang sisi kirinya kosong atau SHA nol (`workflow_dispatch`, `schedule`, push pertama) kini dinormalkan menjadi `<sha>~1...<sha>` lewat satu pintu `rentang_normal`, dipakai `daftar_berkas_berubah`, `berkas_dihapus`, dan perhitungan `diff` di `utama`. Sebelumnya `github.event.before` kosong menghasilkan `diff = ""` sehingga cek berbasis diff (metode hilang, hapusan belum tercatat, uji baru/berubah, pola berisiko/boros, izin/definisi baru, locale baru) dilewati diam-diam — run manual/schedule bisa lolos tanpa gerbang itu.
- Tanpa test baru (skrip Python tanpa harness uji); diverifikasi lokal dengan `--rentang "...HEAD"`, `HEAD~1...HEAD`, dan SHA nol (ketiganya memeriksa 2 berkas commit terakhir) plus `cek-cepat.sh`. Build/test penuh di CI.

## [Belum rilis] — Audit 78: centang PIN tak desync setelah import / antrean penuh (Android 5+)
- `SettingsActivity.reloadSettingsFromPrefs`: `pinEnabledCheck.setChecked()` kini dibungkus `pinCentangProgram` seperti semua set programatik lain. Sebelumnya import config memicu listener: PIN mati memunculkan konfirmasi "matikan PIN" palsu, dan config ber-PIN dengan kolom PIN kosong dicentang lalu langsung dibatalkan listener sehingga centang tampil mati padahal `pin_on=true` (sampai Settings dibuka ulang).
- Aktivasi PIN lewat centang: bila `Util.jalankanLama` menolak tugas (antrean penuh), `pinAktifJalan` dilepas dan centang dikembalikan mati + pesan gagal. Sebelumnya guard itu bisa tertinggal true selamanya: centang menyala, `pin_on` tak pernah ditulis, dan toggle PIN berikutnya diabaikan ("aktivasi masih jalan"). Tanpa test baru (butuh lifecycle/prefs Android). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 77: versi terkunci tak lagi cek "terbaru" ke GitHub (Android 5+)
- `Updater.versiTargetBinary`/`versiTargetWebVault`: bila user mengunci versi (`bin_pin_version`/`wv_pin_version`), fungsi langsung mengembalikan kuncian tanpa memanggil `latestVersion`. Sebelumnya nilai terbaru selalu diambil lalu dibuang `pilihTarget`; pada STB offline/lambat itu menunda Start dan saat `latestVersion` gagal jalur unduh pin ikut menggantung, sekaligus memicu rate-limit GitHub percuma. Tanpa test baru (butuh Context/prefs; `pilihTarget` sudah diuji). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 76: Android 11+ tak lagi wajib All files access (Android 5+)
- `StoragePerm.cukupAkses`: pada API 30+ kini menerima izin tulis runtime ATAU All files access. targetSdk 28 tetap memakai model legacy di Android 11+ (scoped storage baru diwajibkan untuk target 30+), jadi `/sdcard/vaultwarden` sudah bisa ditulis dengan izin Storage biasa. Sebelumnya aplikasi menolak Start di Android 11+ walau izin tulis sudah diberikan, kecuali pengguna juga memberi All files access.
- `mintaIzinBilaPerlu`: Android 11+ ikut meminta izin tulis runtime (jalur sama dengan Android 6-10); dialog "buka pengaturan All files" + throttle 5 menit dihapus (`tampilDialogKelola`/`bukaPengaturanKelola`/`bolehTampilDialogKelola`, string `izin_storage_judul/pesan/buka/nanti`). `MANAGE_EXTERNAL_STORAGE` tetap dideklarasikan dan tetap diterima bila pengguna memberikannya manual.
- Test `cukupAkses_api30TerimaLegacyAtauKelola`; test dialog All-files dihapus. Nama metode yang dihapus dicatat di `tools/bekas-hapusan.txt`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 75: backup "saat Start" tak dijadwalkan saat start gagal (Android 5+)
- `SettingsActivity` (tombol Start): `maybeAutoBackup()` dipindah ke dalam alur start dan hanya dipanggil bila `ServerService.start()` diterima. Pada jalur "Download & Start" ia dulu dipanggil tepat setelah tugas unduh 35 MB dimasukkan antrean — sehingga backup otomatis bisa berjalan bersamaan dengan unduhan, dan tetap dijadwalkan walau unduh gagal (server tidak pernah start) sehingga lapor "Database belum siap 30 dtk setelah Start" atau membuat backup yang menandai `tg_last` padahal server tidak jalan. Dua jalur lain ikut dijaga dengan `if (ServerService.start(...))`. Tanpa test baru (butuh Service/Context Android). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 74: "Hapus log" benar-benar bersih (Android 5+)
- `ServerService.clearLog`: ikut mengosongkan buffer tulis file (`logFileBuf`) dan menghapus `vaultwarden.log.1`. Sebelumnya baris yang belum di-flush (<16 KB) ditulis lagi sesudah file dihapus sehingga log lama muncul kembali, dan file rotasi `.1` tertinggal — "Hapus log" terlihat tidak bekerja dan data diagnostik lama tetap ada di storage. Pengosongan buffer + hapus file kini di bawah `LOG_FILE_LOCK` agar tidak balapan dengan penulis log. Tanpa test baru (state statis + IO Android). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 73: checksum dicoba ulang saat GitHub gangguan sesaat (Android 5+)
- `Updater.fetchChecksum`: balasan non-200 kini dibedakan. 408/429/5xx dilempar agar jalur coba-ulang (2x, jeda 2 dtk) berjalan; 404/403 tetap langsung `null`. Sebelumnya semua non-200 langsung menyerah, sehingga gangguan sesaat/rate-limit GitHub membuat unduhan binary/web-vault yang sudah selesai dibatalkan dengan pesan "Checksum tidak ditemukan" (padahal file tmp parsial bisa dilanjutkan).
- `kodeSementara` (baru, murni) + test `kodeSementara_hanyaGangguanSesaat`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 72: tugas ditolak tegas, flag tak macet (Android 5+)
- `Util.penanganAntreanPenuh`: antrean penuh kini mencatat lalu MELEMPAR `RejectedExecutionException` untuk tugas baru; tugas lama tidak lagi dibuang diam-diam. Dulu handler mem-poll tugas tertua: bila yang terbuang memegang flag (health tick, start server, runBusy, PIN, redaksi log), flag tak pernah dilepas — watchdog health mati senyap, tombol Start/OK terkunci permanen, layar PIN macet di "Checking PIN…", log berhenti refresh.
- `Util.jalankanBg`/`jalankanLama` mengembalikan boolean (false = tugas tidak dijalankan); pemanggil yang menyetel flag sebelum submit melepasnya: health tick melepas `healthBerjalan` (tick berikut tetap memeriksa), `startServerAsync` melepas `starting` + pesan status, `SettingsActivity.runBusy` melepas `tugasBerjalan`/`setBusy`, `PinActivity` mengaktifkan tombol + `pin_sibuk`, `LogActivity` melepas `redaksiJalan` + `lastLogKey=null`, `MainActivity` melepas `setBusy` (cek port & unduh web-vault).
- `ServerService` ACTION_STOP: bila tugas penunggu proses ditolak, foreground/service tetap ditutup via `postTunda` 3 dtk; kedua jalur tidak mencabut foreground server baru bila user sudah Start lagi (`niatJalanTersimpan`).
- `TgBot`/`PinGate` memakai handler yang sama; `cobaJalankanBg` yang selama ini menangkap REE jadi benar-benar berfungsi, dan `CATAT_EXEC` PinGate best-effort tetap aman (sudah dibungkus try/catch).
- Test diperbarui: `penanganAntreanPenuh_tolakBaruTanpaBuangLama` (tugas lama tetap di antrean, tugas baru melempar) + `jalankanBgLamaLaporPenolakanDanMenerimaTugas`. Nama test lama dicatat di `tools/bekas-hapusan.txt`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 71: START_STICKY benar-benar memulihkan server (Android 5+)
- `ServerService`: niat user kini disimpan (`server_diminta` via `simpanNiatJalan`, commit sinkron) saat Start dan dihapus saat Stop. Intent `null` dari sistem (service dihidupkan ulang START_STICKY setelah proses dibunuh LMK) dulu langsung dijawab `START_NOT_STICKY` tanpa start apa pun, padahal flag statis (`running/healthActive`) sudah hilang — janji START_STICKY tak pernah ditepati dan server tak pulih otomatis.
- Jalur intent `null` sekarang memulai ulang hanya bila niat tersimpan true, memasang foreground/health, dan tetap menghormati proses yang masih hidup (`process == null || !alive`) agar recreate layanan tanpa kematian proses tidak men-spawn server kedua. Aksi tak dikenal (non-null) tetap diabaikan. Tanpa test baru (butuh lifecycle service Android). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 70: "web-vault sudah terbaru" tak lagi palsu (Android 5+)
- `Updater.updateWebVaultInner`: "terpasang" kini hanya bila `web-vault/index.html` benar-benar ada dan berisi (`wvPayloadAda`), bukan sekadar ada `vw-version.json`. Sebelumnya folder dengan penanda tapi `index.html` hilang (swap terputus/storage penuh) selalu dijawab "sudah versi terbaru" sehingga web UI 404 permanen dan tombol Update tak memperbaiki apa pun.
- Versi terpasang hanya dipercaya dari penanda tag Vaultwarden (`wv_from_version`). Tebakan dari versi binary dihapus: namespace-nya beda dan bukan bukti isi web-vault, sehingga justru menutup peluang self-heal saat penanda kosong. `webVaultReady` memakai pintu yang sama (index kosong = belum siap).
- Test baru `wvPayloadAda_wajibIndexBerisi` (penanda saja, index kosong, index berisi, null). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 69: dialog tawaran web-vault aman lifecycle (Android 5+)
- `SettingsActivity.autoOfferWebVaultUpdate`: dialog dipindah utuh ke dalam `ui.post` dengan penjaga `isFinishing()/isDestroyed()` (pola sama dengan dialog lain di kelas ini), dan penanda `wv_notified_<server>` baru ditulis setelah Activity terbukti hidup. Sebelumnya penanda ditulis di worker sebelum dialog tampil, lalu `show()` tanpa penjaga: keluar dari Settings saat cek update berjalan bisa memicu `BadTokenException` (app tertutup) dan tawaran update hilang permanen karena penanda sudah terlanjur true. Tanpa test baru (butuh lifecycle Android). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 68: backup UI serial dengan bot/restore (Android 5+)
- `SettingsActivity.backupDatabase` kini mengambil `TgBackup.kunciBackup()` (wrapper + `backupDatabaseIsi`, pola sama dengan restore UI), sehingga backup dari tombol dan backup terjadwal/`/backup` bot tak berjalan bersamaan. Sebelumnya keduanya berebut file temp `<cache>/verifikasi-isi-db.sqlite3` dan saling menghapus di `finally`: backup sah bisa dilaporkan "corrupt" lalu dibuang.
- Tanpa test baru (butuh Context/executor Android; perilaku sama dengan jalur restore yang sudah memakai kunci). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 67: rollback DB saat restore gagal di tengah (Android 5+)
- `TgBackup.restoreFromZip`: blok `catch` ekstraksi kini memulihkan `db.sqlite3` dari `preBackup` (dan membuang WAL/SHM sisa zip) sebelum melempar ulang, bukan hanya memulihkan TLS. Sebelumnya `IOException` di tengah ekstraksi (storage penuh/proses dibunuh) meninggalkan DB parsial sehingga server tak bisa start walau salinan pengaman tersedia; jalur "bukan SQLite"/"korup" sudah rollback, jalur exception belum.
- `pulihkanDbLama` (baru, satu pintu): dipakai ketiga jalur gagal restore; WAL/SHM kini juga dibuang pada jalur tanpa `preBackup` (sebelumnya bisa tertinggal menempel ke DB lain). Tanpa test baru (butuh Context/SQLite Android). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 66: anti-loop restart hidup lagi (Android 5+)
- `ServerService`: riwayat restart tidak lagi dibersihkan tiap start sukses. Start otomatis (`restartTunda` → `startServer`) juga lewat jalur itu, sehingga penjaga "≥3 restart/5 menit" (`recordRestart`) mustahil tercapai dan binary yang crash tiap beberapa detik di-restart 2 dtk selamanya (spam Telegram + crash log tiap siklus). Riwayat kini hanya dilupakan saat Start eksplisit user (`lupakanRiwayatRestart`, dipanggil di ACTION_START).
- `restartAttempt` tidak lagi di-reset tiap start; reset hanya saat uptime >60 dtk (`scheduleRestartTerkunci`) atau Start eksplisit, agar batas 5x backoff juga berfungsi untuk crash ber-uptime pendek.
- `recordRestart` membuang entri di luar jendela 5 menit dan `restartSummary` menghitung hanya jendela itu, agar status tak menampilkan "Restart: Nx" basi setelah server pulih lama. Tanpa test baru (wiring lifecycle/thread; logika murni `hitungRestartBaru` sudah diuji). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit TLS: 1 tinggi + 3 rendah (Android 5+)
- `ServerService.cobaPinnedCa` + `HttpsCompat.bungkusTls12` (baru): factory loopback health-check dibungkus penegak TLSv1.2+ satu pintu dengan `socketFactory()` — konteks "TLS" di API 21/22 default hanya TLSv1 sehingga handshake ke Rocket (min 1.2) gagal selamanya lalu restart beruntun sia-sia. Perilaku identik di API 23+.
- `HttpsCompat.cipherKuat` (baru, dipakai `saringCipher`): kecualikan eksplisit suite berprimitif lemah (RC4/DES/3DES/MD5/NULL/anon/EXPORT) — filter lama meloloskan ECDHE+3DES walau komentar mengklaim gugur.
- `TlsCert.grupHeksaValid` (baru, dipakai `ipv6`): tolak grup bertanda (`+ff`) yang diterima `parseInt` radix.
- `TlsCert` memo tanggal: TTL 60 dtk (`MEMO_MASA_TTL_MS`, selaras cap trust) agar tulis ulang berukuran sama dalam granularitas mtime FAT (2 dtk) tak menyajikan tanggal basi selamanya; aman JVM via `elapsedAman`.
- Test: `saringCipher` kini mencakup ECDHE+3DES, `bungkusTls12` null/wrap/delegasi, `ipv6` tolak `+`/`-` + `grupHeksaValid`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 65: cache prefs hint restart Settings (Android 5+)
- `SettingsActivity.refreshFromService`: blok deteksi "setting berubah" tak lagi baca `SharedPreferences` + parse port + `sidikTokenAdmin` tiap tick 1 dtk — kini cache tiap 5 tick selaras `MainActivity.tickCachePrefs`, dipaksa segar saat prefs ditulis lokal (saveAndStart/restore/import/sanitize), saat import `applyPrefsFromJson`, dan saat `onResume`. Hint restart + sorot label tetap akurat tanpa jeda. Perilaku identik; tanpa test baru (wiring UI thread). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 64: runBusy pindah ke pool lama (Android 5+)
- `SettingsActivity.runBusy`: 12 tugas berat (unduh binary/web-vault, backup/restore/unggah Telegram, generate RSA) pindah dari `Util.jalankanBg` (pool ringan 3-thread untuk cek port/refresh/redaksi log) ke `Util.jalankanLama` (pool BG_LAMA 2-thread untuk tugas menit-lama) — selaras dokumen pool di `Util` + preseden `MainActivity` yang sudah memakai `jalankanLama` untuk unduh web-vault. Sebelumnya unduhan 35 MB memonopoli thread pool ringan ber-shared proses sehingga cek port/refresh terbuang di STB 1 GB. Perilaku sama (CAS `tugasBerjalan` + `setBusy` tak berubah); tanpa test baru (routing executor butuh runtime Android). Verifikasi via `verifikasi-patch --semua` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 63: sapuan penuh nihil temuan (Android 5+)
- Sapuan penuh 21 berkas (24rb baris) dengan rg/fd/bat/eza tanpa toolchain: nol metode/field/import mati, tanpa `printStackTrace`/`System.out`/`System.gc`/`Timer`/`AsyncTask`/`Uri.fromFile`/`TODO` (satu-satunya hit = komentar `NetworkOnMainThreadException`), semua `HttpURLConnection` ber-timeout + `disconnect()` + try-with-resources di worker, semua `listFiles` ber-guard null (pemanggil `daftarBackup` null-safe), `Pattern`/`SimpleDateFormat`/`MessageDigest`/`SecureRandom`/heks terpusat + ber-cache, `SecureRandom` bersama (hemat seed kernel lama), `PendingIntent` IMMUTABLE + channel O+ + exact-alarm + `POST_NOTIFICATIONS` + `Html.fromHtml` berpenjaga versi, `checkSelfPermission`/`isExternalStorageManager`/`StorageManager` berpenjaga versi, alarm Doze memakai `setExactAndAllowWhileIdle` + fallback `setAndAllowWhileIdle`, tick UI ter-cache (versi/prefs/IP/URL/warna) + refresh tunggal, lapisan `rendahAscii`/`cocokAscii`/`mengandungAbaikanHuruf` + `cocokChat`/`pecahDaftarChat`/`bandingDigit` semua dipakai (bukan duplikat mati). Tanpa perubahan kode (tanpa churn) + tanpa test baru. Verifikasi via `verifikasi-patch --semua` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 62: sapuan penuh nihil temuan (Android 5+)
- Sapuan penuh semua area tanpa perubahan kode (tanpa churn): nol metode/field/import mati (pindai definisi-vs-pemakaian termasuk method-ref `::` + callback lifecycle + string refleksi; 3 kandidat = `onBind`/`onStartCommand`/`onBackPressed` milik framework), tanpa `printStackTrace`/`System.out`/`Timer`/`AsyncTask`/`System.gc`/`Uri.fromFile`/`TODO`, semua `HttpURLConnection` di worker, semua `listFiles` ber-guard null, `Pattern`/`SimpleDateFormat`/`MessageDigest`/`SecureRandom`/heks terpusat, pool berbatang + tolak tercatat, `PendingIntent` IMMUTABLE + channel O+ + exact-alarm + `POST_NOTIFICATIONS` berpenjaga versi, `commit()` sinkron semua beralasan daya-tahan, `collectIps`/`localIp`/URL/prefs/throttle ter-cache di semua tick UI, restore/unduh zip ber-allowlist + kanonis + batas anti-zip-bomb, PIN PBKDF2 ber-batas iterasi/ukuran + lockout ganda jam. Tanpa test baru (tanpa perubahan perilaku). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.
## [Belum rilis] — Audit 61: sapuan penuh, delegasi mati + klon sia-sia (Android 5+)
- `TgBot`: buang delegasi satu-baris `jalankanBg` tanpa nilai tambah (2 call site kini langsung ke `cobaJalankanBg`, perilaku identik termasuk redam `RejectedExecutionException`). Nama `jalankanBg` tetap terdefinisi di `Util` sehingga tanpa catat hapusan (seperti preseden audit-58/59).
- `StoragePerm.izinRuntime`: kembalikan konstanta `IZIN_KOSONG` bersama tanpa `.clone()` — array nol-elemen tak punya indeks yang bisa diubah pemanggil sehingga aman dibagi; hemat satu alokasi tiap cek izin di ART lama. Varian berisi (`IZIN_RW`) tetap diklon.
- Sapuan penuh semua area (temuan lain nihil, tanpa churn): tanpa import/field/metode mati lain, tanpa `printStackTrace`/`System.out`/`Timer`/`AsyncTask`/`System.gc`/`Uri.fromFile`/`TODO`, semua `HttpURLConnection` di worker (tanpa `NetworkOnMainThread`), `PendingIntent` IMMUTABLE + channel O+ + exact-alarm berpenjaga versi, pool berbatang + tolak tercatat, log delta + trim histeresis, unduh 64 KB + hash-sambil-salin + resume aman-redirect. Tanpa test baru (delegasi internal + returning-konstanta; perilaku identik). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.
## [Belum rilis] — Audit 60: sapuan penuh, cabang mati pumpOutput (Android 5+)
- `ServerService.pumpOutput`: buang cek throttle `bolehHintLogin` kedua + cabang `else` tak terjangkau — gerbang di atas sudah meloloskan (`hintLogin != null`), penulis satu-satunya di thread reader ini, `kini` sama. Perilaku identik (urutan append + stempel throttle sama persis).
- Sapuan penuh semua area (temuan lain nihil, tanpa churn): `daftarDns` sengaja dipertahankan (util teruji untuk pemakaian mendatang), sisa migrasi `KEY_TG_MENU_HASH` disengaja, `getAll()` prefs + `getPackageInfo` hanya jalur dingin/sembuh, kunci backup/restore satu flag, alarm/`PendingIntent`/notifikasi berpenjaga versi, tanpa `NetworkOnMainThread`/`Uri.fromFile`/`System.gc`/`printStackTrace`/`Timer`/`AsyncTask`/receiver bocor. Tanpa test baru (cabang mati tanpa perilaku; `pumpOutput` butuh runtime proses). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 59: sapuan penuh, 1 metode mati + 2 overload mati (Android 5+)
- `PinCrypto`: buang `hashLegasiValid` yang nol pemanggil (digantikan `hashLegasiValidTernormalisasi` di `bungkusLegasi`; nama dicatat di `tools/bekas-hapusan.txt` sesuai aturan #15 agar tak dipakai lagi).
- `TlsCert`: buang overload mati `ensure(dir, ips)` + `ensure(dir, ips, dns)` yang nol pemanggil (satu-satunya pemanggil memakai varian 4-arg `paksaLeaf`). Nama `ensure` tetap terdefinisi sehingga tanpa catat hapusan.
- `HttpsCompat.socketFactory`: gabung 2 baris komentar duplikat "Union (bukan ganti)" jadi satu (tanpa ubah logika).
- Sapuan penuh semua area (temuan lain nihil, tanpa churn): pindai definisi-vs-pemakaian semua metode (acuan method-ref `this::` + callback siklus hidup + refleksi `len` ikut dihitung), tanpa import/field mati, `getAll()` prefs hanya di jalur sembuh-korup, `getPackageInfo` hanya dialog/contoh sekali-tampil, pool BG berbatang + tolak tercatat, `PendingIntent` IMMUTABLE + channel O+ berpenjaga versi, tanpa `NetworkOnMainThread`/`Uri.fromFile`/`System.gc`/`printStackTrace`/`Timer`/`AsyncTask`. Tanpa test baru (metode mati tanpa acuan uji; perilaku lain identik). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 58: sapuan penuh, 2 overload mati + 1 onDestroy (Android 5+)
- `PinActivity.onDestroy` baru: cabut `ui.removeCallbacksAndMessages(null)` — tanpa ini runnable hasil PBKDF2 (`ui.post`) menahan activity yang sudah `finish()` sampai hash 30rb iterasi selesai (detik di STB lama). Perilaku sama; guard `isFinishing()/isDestroyed()` di callback tetap.
- `SettingsActivity`: buang overload 1-arg `batasiFileExportCache(String)` yang nol pemanggil (hanya varian 2-arg `(String, File[])` yang dipakai sapu cache export). Nama tetap terdefinisi sehingga tanpa catat hapusan.
- `TlsCert`: buang overload 4-arg `buatLeaf(caKey, cert, key, ips)` yang nol pemanggil (satu-satunya pemanggil memakai varian 5-arg dengan SAN DNS). Nama tetap terdefinisi sehingga tanpa catat hapusan.
- Sapuan penuh semua area (temuan lain nihil, tanpa churn): tanpa import/field mati, `Pattern` statis, `SimpleDateFormat`/`MessageDigest`/`SecureRandom`/heks bersama, pool BG berbatang + tolak tercatat, `commit()` sinkron semua beralasan daya-tahan, `PendingIntent` IMMUTABLE + channel O+ + `POST_NOTIFICATIONS`/`Html.fromHtml` berpenjaga versi, `isDestroyed` aman (API 17+ < minSdk 21), tanpa `NetworkOnMainThread`, tanpa `Uri.fromFile`, tanpa `System.gc`/`printStackTrace`. Tanpa test baru (overload mati tanpa acuan uji; `onDestroy` butuh runtime Android). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 57: sapuan penuh semua area (nihil temuan, Android 5+)
- Diverifikasi tanpa perubahan kode: alur Start (env, shim, smoke test, kill-stale, TLS, bind), health+restart backoff, unduh binary/shim/web-vault (resume, SHA-256, zip-slip, batas zip-bomb), backup/restore (WAL checkpoint, quick_check, enkripsi, allowlist), dispatch bot + polling, redaksi rahasia, tick Main/Settings/Log (memo versi, gerbang StringBuilder, cache IP/URL/prefs TTL), PBKDF2 + fallback manual, factory TLS + cap override, provider kanonis ganda, receiver anti-spoof + throttle, izin/storage berpenjaga versi. Tanpa import/metode/field mati, tanpa `String.format`/`split`/trim-case di jalur panas, tanpa I/O berat di UI thread, tanpa `System.gc`/`printStackTrace`/`available()`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh tetap di CI.

## [Belum rilis] — Audit 56: satu pintu magic SQLite (Android 5+)
- `TgBackup.MAGIC_SQLITE` baru (satu pintu): 3 situs cek header SQLite (`isSqliteFile`, cek header zip, validasi restore `SettingsActivity`) dulu `getBytes` per panggil — termasuk loop tunggu-DB 500 ms hingga 60x per backup otomatis. Kini konstanta `static final` sekali-encode (ASCII = byte identik dengan varian UTF-8 lama). Perilaku identik; tanpa test baru (byte magic dicakup `TgBackupTest` yang ada). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 55: gerbang versi Settings per tick (Android 5+)
- `SettingsActivity.refreshFromService`: string versi ("App … · Binary: …") kini dirakit hanya bila komponennya berubah (`lastVersiApp/Bin/Bundled` + `versiCache`, selaras gerbang `MainActivity`). Sebelumnya `StringBuilder` + concat dibangun tiap tick 1 dtk walau versi statis di antara Start/update — sampah GC sia-sia di UI thread STB lama. Perilaku/teks identik; tanpa test baru (tick activity butuh runtime Android). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 54: samakan banding versi Settings + tutup manual-close (Android 5+)
- `SettingsActivity.refreshFromService`: blok `pendingVersion` kini memakai `Updater.versiCocok` semantik + memo pasangan seperti `MainActivity` (dulu `equals` mentah + `parseBinaryVersion`/`normVersion` regex tiap tick 1 dtk). Perbaiki bug banner macet saat format beda tulisan ("v1.32.0" vs "1.32.0"/sufiks beta) sekaligus hemat alokasi Matcher di UI thread STB lama; baca `pendingVersion` volatile sekali per tick (`pendSet`) agar tak berubah tengah jalan.
- `TlsCert.bacaPrivateKey`: ganti tutup manual `finally { if (in != null) in.close(); }` dengan try-with-resources (hapus kode sampah + tutup FD dijamin bila `read` melempar di tengah; cegah kebocoran FD di STB RAM kecil).
- Sapuan penuh 21 berkas (temuan lain nihil, tanpa churn): tanpa import/metode/field mati, `Pattern` sudah statis, `SimpleDateFormat`/`MessageDigest`/`SecureRandom`/heks bersama, pool BG berbatang, `commit()` sinkron semua beralasan daya-tahan, `PendingIntent` IMMUTABLE + channel O+ + `POST_NOTIFICATIONS`/`Html.fromHtml` berpenjaga versi, tanpa `NetworkOnMainThread`, tanpa `Uri.fromFile`. Tanpa test baru (perilaku identik; tick activity butuh runtime Android). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 53: sapuan penuh, 3 sisa inefisiensi/junk dibuang (Android 6+)
- `SettingsActivity.bersihkanExportPlainCache`: satu enumerasi `getCacheDir().listFiles()` — varian baru `batasiFileExportCache(nama, File[])` memakai ulang hasil sapu (dulu enumerasi + stat direktori cache dua kali tiap onCreate/onResume/onDestroy). Varian 1-arg dipertahankan sebagai delegasi (tanpa hapus nama, tanpa catat hapusan).
- `TgBackup`: buang 2 cabang `else` mati tak terjangkau — fallback `am.set()` lawas di `jadwalTundaBoot` dan `schedule` harian yang mensyaratkan API <23 padahal cabang luar sudah menjamin API 23+ (`exact=true` hanya bila `>= M`). Perilaku sama persis di API 21-32 (API 21/22 tetap `set`/`setInexactRepeating` di cabang luar).
- `Util.pecahDaftarChat`: dedup `hasil.contains()` O(n²) diganti `HashSet` pendamping O(1) — urutan + batas anti-DoS tak berubah (diuji `UtilTest` yang ada).
- Sapuan penuh 21 berkas (temuan lain nihil, tanpa churn): regex sudah statis, `SimpleDateFormat` ThreadLocal, `MessageDigest`/`SecureRandom`/heks bersama, pool BG berbatang + tolak tercatat, `SharedPreferences` ber-cache proses (baca ulang murah), `collectIps` cache 60 dtk, sleep hanya di worker + guard UI-thread, `startForegroundService`/`NotificationChannel`/`FLAG_IMMUTABLE`/`canScheduleExactAlarms`/`POST_NOTIFICATIONS`/`Html.fromHtml`/`isIgnoringBatteryOptimizations` semua berpenjaga versi, tanpa `Uri.fromFile`, `commit()` sinkron semua beralasan daya-tahan. Tanpa test baru (perilaku identik; test lama mencakup). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 52: sapuan penuh semua area, satu enumerasi IP saat Start
- `ServerService.startServer`: panduan login memakai `hostSegar` (bukan `lanHost()` kedua) — satu enumerasi interface sesuai komentar, plus DOMAIN dan panduan tak bisa beda IP bila DHCP flap tepat di tengah Start. Perilaku sama bila IP stabil; tanpa test baru (butuh runtime Android).
- Sapuan penuh semua area (temuan lain nihil, tanpa churn): tak ada method/field/import mati (pindai privat + `cek_import_mati`), `sendMessage` async, health/bot/restore/export/PBKDF2 di worker, `toast` via `ui.post`, guard view-mati di semua callback PIN, `logBuffer` terkunci + delta berpagar versi, redaksi rahasia pre-pindai sekali-jalan di worker, cipher AES-GCM + PBKDF2 ber-batas iterasi/ukuran + `isEqual` konstan-waktu, zip allowlist + kanonis + batas anti-zip-bomb, unduh 64 KB + resume + hash-sambil-salin + lapor per-256 KB, `collectIps` cache 60 dtk, alarm exact + fallback `AllowWhileIdle` (Doze 6+), `commit()` sinkron semua beralasan daya-tahan, `SecureRandom`/heks/`MessageDigest` bersama, tanpa `String.format`/`split`/`toLowerCase` di jalur panas, `PendingIntent` IMMUTABLE + channel O+ + `POST_NOTIFICATIONS` berpenjaga. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 51: tunda boot via alarm, tanpa tidurkan pool (Android 6+)
- `TgBackup.tundaBilaBootBaru()` baru (satu pintu): ganti `tungguBootStabil()` yang menidurkan thread pool bersama hingga 5 menit tiap STB baru hidup (3 jalur: susulan boot `MainActivity`, terjadwal `ServerService.ACTION_TG_BACKUP`, saat-Start `backupTungguDb`). Pool BG lama (2 slot) + BG ringan (3 slot) di STB 1 GB ikut tertahan: unduh binary/hash PIN antre di belakang tidur itu; kini ketiga jalur menjadwalkan `jadwalTundaBoot` lalu pulang, `AlarmReceiver` menilai ulang + mengeksekusi saat alarm menyala. Perilaku waktu sama, tanpa thread tertahan.
- `tungguBootStabil` dihapus total (nama dicatat di `tools/bekas-hapusan.txt` sesuai aturan #15); varian waktu-suntik `tundaBilaBootBaruPada` murni+alarm agar bisa unit test JVM.
- Test: `TgBackupTest.tundaBilaBootBaru_lewatBilaUptimeLama` + `tundaBilaBootBaru_tundaBilaBootBaru` (deterministik tanpa runtime Android).
- Sapuan penuh semua area (temuan lain nihil, tanpa churn): `PendingIntent` IMMUTABLE berpenjaga M+, channel O+ berpenjaga, exact-alarm + fallback `AllowWhileIdle` (Doze Android 6+), `sendMessage` async (tanpa `NetworkOnMainThread`), health di pool (tanpa ANR), `listFiles` ber-guard null, cipher kosong = pakai bawaan (API 21), TLSv1.2 dipaksa di 5.x, `commit()` sinkron semua beralasan daya-tahan, `SecureRandom` bersama, heksa manual tanpa `String.format`, `logBuffer` terkunci, `waitForOrKill` pra-O via waiter, `FileShareProvider` kanonis ganda + tolak symlink, `LogExport` MediaStore Q berpenjaga 29. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 50: hapus delegasi mati + sapuan satu-pintu
- `ServerService.mulaiAksi()` dihapus: delegasi satu-baris tanpa nilai tambah ke `mulaiService()` (2 call site `stop`/`restart` kini langsung ke `mulaiService`, selaras `backupNow`); nama dicatat di `tools/bekas-hapusan.txt` sesuai aturan #15 agar tak dipakai lagi.
- Sapuan putaran empat (temuan lain nihil, tanpa churn): `bacaResponsBatas`/`bacaTerbatas` + `bukaPostTelegram` + `gantiAtomik` sudah satu-pintu lintas TgBot/Updater/ServerService; `tailLog` TgBot hanya menambah redaksi + batas di atas `ServerService.tailLog`; `alive()` via `exitValue` hanya di health 30 dtk/2 mnt + jalur event (bukan tick UI); `healkanStringPrefs` tak di poll; `tanpaKuota` ganda di `AutoUpdate.cek` disengaja (cek ulang segar sesudah unduh menit-lama — hoist justru bakar kuota bila pindah ke mobile data); wakelock ganda receiver + `runWithWakeLock` disengaja (jembatan handoff Doze).
- Tanpa test baru: hapus delegasi internal (butuh runtime Android); verifikasi via `verifikasi-patch` (LOLOS) + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 49: dedup jalur log + seleksi IP + guard view mati
- `ServerService.tulisBarisLog()` baru (satu pintu): ganti badan stempel+tempel 12-baris identik di `appendLog` (instans, + file-log) dan `catatLog` (statis). Perilaku sama persis.
- `ServerService.localIp()`: delegasikan seleksi IPv4-pertama ke `lanHost()` (dulu salinan logika identik di dua tempat); cache 60 dtk tak berubah.
- `PinActivity`: pindahkan guard `isFinishing/isDestroyed` ke puncak callback hasil-PIN agar cabang galat (`tampilGalat`) tak menyentuh view mati saat rotate/keluar tepat selama PBKDF2 berjalan.
- Sapuan putaran tiga (temuan lain nihil, tanpa churn): health/IP (peringatan sekali per IP + restart bila stabil 2 tick, TCP-lolos 3x anti positif-palsu), `latestVersion` (TTL 15 mnt + jeda-gagal 60 dtk), `schedule` harian (exact + fallback inexact + sapu alarm lawas), `pollOnce`/`handleCommand` (gerbang grup + PIN + offset at-most-once), `notifyLowStorage` (latch sekali), `segarkanTrustAnchor` (1x/hari), `healkanStringPrefs` (hanya di onCreate/Start/receiver harian — tak di poll 20 dtk), debounce tulis prefs + siram saat pause, `PinActivity` (PBKDF2 di pool lama + hash-upgrade TOCTOU-aman).
- Tanpa test baru: dedup pemanggilan + guard lifecycle (butuh runtime Android); verifikasi via `verifikasi-patch` (LOLOS) + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 48: satu pintu reset penanda + pabrik pool pesan
- `ServerService.resetPenandaJalan()` baru (satu pintu): ganti 4 blok reset 8-field identik (`runningDataDir/Port/Https/AdminToken/WvFrom/LanHost` + 3 pelacak IP) di jalur gagal-start, stopper, stop-tanpa-proses, dan healthFail. Tambah field baru dulu hanya di sebagian jalur = status basi (drift sudah terlihat: indentasi 2 baris melenceng di blok stopper); WakeLock/log/status tetap di pemanggil karena beda tiap jalur. Perilaku sama persis.
- `TgBackup.pabrikPesan()` + `tolakPesan()` baru (satu pintu lokal): ganti 2 blok konstruksi `ThreadPoolExecutor` + handler-tolak identik (`TG_MSG_EXEC`/`TG_PENTING_EXEC`, ~45 baris duplikat) — lajur prioritas tetap terpisah, teks log biasa/penting dipertahankan via parameter. Catatan verifikasi: peringatan `new Thread(` pada diff ini false-positive — berada di dalam `ThreadFactory` pool berbatang (maks 1 thread per pool, dipakai ulang), bukan `new Thread` per tugas.
- Sapuan putaran dua (temuan lain nihil, tanpa churn): `ensureBinary` (urutan cache→manual→unduh + TOCTOU aman), loop unduh 64 KB + resume + hash-sambil-salin, `pollOnce` (deduplikasi `POLLING` + offset at-most-once), restore lokal (magic PK/SQLite + batas 200 MB), `FileShareProvider` (kanonis ganda + tolak symlink), `TlsCert.ensure` (tolak UI thread + tukar atomik), `HttpsCompat` (union trust + paksa TLSv1.2 API 21/22), `PinCrypto` (PBKDF2 manual tanpa alokasi per iterasi), `refreshLog` (berpagar versi + coalesce + ekor saja di worker).
- Tanpa test baru: perubahan berupa dedup konstruksi/pemanggilan (butuh runtime Android); verifikasi via `verifikasi-patch` (LOLOS) + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 47: satukan rantai refresh ganda (hemat CPU/baterai Android 6+)
- `MainActivity` + `SettingsActivity`: `refreshFromService` memakai satu instans `refreshLoop` di semua titik `post`/`postDelayed` (dulu tiap `this::refreshFromService` adalah objek beda sehingga `removeCallbacks` tak bisa membatalkan titikan basi). Tiap suntikan "refresh sekarang" (`onResume`, `setBusy(false)`, `appendUiLog` throttle 500 ms) ikut menjadwalkan penerus abadi — rantai loop 1-2 dtk menumpuk permanen tiap buka Settings/Log dan transisi sibuk (baca prefs + binder + `setText` + delta log berlipat di STB 1 GB).
- Perilaku sama, kini satu rantai: `removeCallbacks(refreshLoop)` di awal `refreshFromService` meruntuhkan duplikat tiap tick; `onPause` membatalkan titikan (tanpa kerja latar saat tak terlihat, hemat Doze/baterai); `onResume` hapus-dahulu-lalu-post. Selaras pola `LogActivity.logTick` yang sudah benar.
- Tanpa test baru: logika di activity (butuh runtime Android); verifikasi via `verifikasi-patch` + `cek-cepat` lokal, build/test penuh di CI.

## [Belum rilis] — Audit 46: guard null retensi backup (Android 6+)
- `TgBackup.cleanupOldBackups`: tambah guard `backupDir == null` — selaras `namaFileDi`/`sapuExportEnkripLama` yang sudah berpenjaga; cegah NPE crash bila dipanggil dengan folder null di jalur backup/restore.
- `SettingsActivity.daftarBackup`: tambah guard `backupDir == null → null` — pemanggil (`dbInfoLine`/`storageInfoLine`) sudah menangani `null` (`files == null ? 0`), cegah NPE `getAbsolutePath` bila prefs folder korup.
- Test: `TgBackupTest.cleanupOldBackups_nolAman` (null + path tak ada, murni JVM).
- Sapuan penuh 21 berkas: tanpa import mati, tanpa method privat mati, `commit()` sinkron semua beralasan daya-tahan, `Pattern` statis, buffer 64 KB, `getColor`/`getPackageInfo` lawas disengaja satu jalur API 21-32, `PendingIntent` IMMUTABLE berpenjaga M+, channel O+ berpenjaga, exact-alarm fallback inexact — nihil temuan lain sehingga tanpa churn tambahan.

## [Belum rilis] — Verifikasi patch menulis annotations GitHub
- `tools/verifikasi-patch.py`: tiap temuan GAGAL/PERINGATAN juga dipancarkan sebagai perintah workflow `::error`/`::warning` bila jalan di CI (`GITHUB_ACTIONS=true`) — bug, kode sampah, dan celah keamanan langsung muncul sebagai annotation di tab Files; lokasi `berkas:baris` pertama dalam pesan dipakai agar anotasi menempel di baris kode yang tepat. Output lokal tak berubah.
- `scripts/cek-cepat.sh`: tiap GAGAL ikut ditulis sebagai `::error::` di CI via helper `gagal_msg` (lokal tetap teks biasa).

## [Belum rilis] — Audit 45: satu pintu watchdog/handler/throttle + hapus poll boros
- `Util.pasangWatchdogProses` baru (satu pintu): ganti blok watchdog `--version` identik di `ServerService.detectBinaryVersion` + `Updater.detectVersion`; `Util.batalkanTunda` baru: `ServerService` (`handlerBersama`/`handlerUtama`/`posUtama`/`batalUtama`) delegasi ke `Util.postTunda`/`batalkanTunda` — sekaligus perbaiki balapan double-create tanpa kunci di `handlerUtama` lama. (Nama panjang disengaja: `batalTunda` terlarang di `tools/bekas-hapusan.txt`.)
- `Updater.tungguAtauBunuh` dihapus: poll `exitValue()` tiap 100 ms melempar + isi stack trace tiap tick di ART lama; diganti `ServerService.waitForOrKill` (kini `static` package-visible; waiter+join pra-O, `waitFor(timeout)` di O+).
- `TgBackup.bacaStempelThrottle`/`simpanStempelThrottle` baru (satu pintu): ganti salinan baca/tulis throttle identik di `AlarmReceiver` + `BootReceiver` (perilaku sama persis, nama method receiver dipertahankan).
- Test: 4 test baru `UtilTest` (watchdog hancurkan saat macet / diam saat selesai / diam saat interupsi; `batalkanTunda` aman tanpa Looper) + 2 test `ServerServiceTest` (`waitForOrKill` mati-cepat/timeout); `tungguAtauBunuh` dicatat di `tools/bekas-hapusan.txt`.
- Sapuan bersih (temuan nihil): tanpa metode/field privat mati, vektor patuh `0.x`, warna night sinkron, paritas ID layout portrait-landscape, paritas string EN/ID, `disconnect()` semua jalur koneksi, interrupt selalu restore (kecuali watchdog yang memang exit), foreground tepat di semua action service, tanpa `Random`/`System.gc`/`available()`/`toLowerCase()` tanpa Locale.

## [Belum rilis] — Audit 44: sapuan dalam kripto/restore/restart (nihil temuan)
- Diverifikasi tanpa perubahan kode: enkripsi backup (salt+IV acak per berkas, GCM, batas iterasi KDF, password dinolkan, plaintext parsial dihapus saat gagal); ekstraksi restore (allowlist + kanonis ganda + batas zip-bomb + buang -wal/-shm basi); restart (backoff 2/5/10/20/40 dtk + rem loop 5 mnt monotonik); sanitasi versi bot (regex allowlist, tolak traversal URL); request code PendingIntent unik (0/3/101/...); validasi port inline selaras normalisasi; `tanpaKuota` (tolak VPN + metered, dual jalur API 21/23).
- Keputusan: tanpa diff kode — basis sudah bersih, perubahan tanpa temuan hanya menambah risiko regresi tanpa untung (prinsip audit-41).

## [Belum rilis] — APK otomatis tiap perubahan app (tak ikut rilis binary)
- `build-apk.yml`: kembalikan pemicu `push` (kode app berubah → APK otomatis dibangun); `workflow_run` tetap dihapus agar rilis binary bulanan tak ikut membangun APK (`versionCode` tak churn sia-sia). `workflow_dispatch` manual tetap ada.
- `AGENTS.md` + `README.md`/`README.id.md`/`README.ru.md`: dokumen selaras.

## [Belum rilis] — Audit 43: dedup webVaultReady + sapuan jalur panas
- `Updater.webVaultReady(ctx, dataDir)` baru (satu pintu): ganti salinan identik `webVaultReady` di `MainActivity` + `SettingsActivity` (4 call site). Bonus: null-guard + try/catch — aslinya `new File(null, ...)` = NPE bila prefs korup kosong.
- Sapuan bersih (temuan nihil): `pumpOutput` (throttle hint + filter noise), `highlightLog`/`refreshHomeLog` (batas kerja per tick + delta), loop unduh 64 KB + hapus `.tmp` di semua jalur gagal, `pollOnce` (snapshot jam/offset per poll), `PinActivity` (PBKDF2 di pool lama), `TlsCert.ensure` (tolak UI thread), `FileShareProvider` (kanonis ganda + tolak symlink), manifest `exported` tepat, pool `Util` berpenjaga anti-OOM.
- Tanpa test baru: helper butuh `Context.getFilesDir` (runtime Android); logika murni (null-guard) sebaris guard yang sudah dikunci pola sejenis.

## [Belum rilis] — Audit 42: I/O bind di UI thread + sapuan penuh 21 file
- `SettingsActivity` (jalur simpan & Start): `portButuhRoot()` (2x bind `ServerSocket`) jalan di dalam `ui.post` padahal komentarnya mewajibkan cek port di worker — pindah ke `Util.jalankanBg` dengan short-circuit (`portSibuk && ...`) agar port bebas tak membayar 2 bind tambahan dan UI tak ANR di tumpukan lambat. Perilaku dialog sama persis; `MainActivity` sudah benar (keduanya di worker).
- Sapuan bersih (temuan nihil, terverifikasi per berkas): tanpa API >21 tanpa guard (`NotificationChannel`, `setExactAndAllowWhileIdle`, `FLAG_IMMUTABLE`, `TLSv1.2`, `requestPermissions` semua berpenjaga; `java.nio` hanya `charset`; tanpa `java.nio.file`/`Optional`/`Stream`); receiver ringan (`backupNow`/`pollOnce` delegasi ke service/pool, `goAsync` + wakelock timeout); semua `Pattern` statis; buffer unduh 64 KB; `ThreadLocal` tanggal terpusat `Util` (2 pola konstan, tanpa bocor); `MessageDigest.isEqual` + batas iterasi DoS + lockout ganda wall/elapsed; `disconnect()`/`delete()` file sementara di semua jalur; tanpa `StringBuffer`/`System.out`/`printStackTrace`.
- Tanpa test baru: perbaikan berupa pemindahan thread (butuh runtime Android), tanpa logika murni baru.

## [Belum rilis] — Jadwal rilis bulanan + APK manual
- `build-binary.yml`: `schedule` tiap 12 jam → sebulan sekali tiap tanggal 1 (`0 0 1 * *`); tetap skip bila binary/web-vault untuk tag upstream sudah ada, jadi bangun hanya bila ada update Vaultwarden.
- `build-apk.yml`: hapus pemicu otomatis (`push` + `workflow_run`); APK hanya via `workflow_dispatch` manual. Job `perlu` disederhanakan jadi selalu bangun.
- `AGENTS.md` + `README.md`/`README.id.md`/`README.ru.md`: dokumen selaras (jadwal bulanan, APK manual).

## [Belum rilis] — Audit agresif 41: dokumen selaras + sapuan D-pad/batas aman
- `AGENTS.md`: klaim basi "15 kelas test" di dua tempat (struktur + aturan unit test) selaras jadi 17 (`HttpsCompat`, `MainActivity` hilang dari daftar); dokumen basi menyesatkan sesi AI berikut.
- Tanpa ubah kode aplikasi: sapuan 9 area berikut bersih sehingga sisipan диф hanya menambah risiko regresi tanpa untung.
- Sapuan bersih (temuan nihil, terverifikasi): rantai D-pad lengkap (77 kontrol interaktif di 13 layout punya `nextFocus`, paritas portrait-landscape utuh); `network_security_config` (loopback cleartext saja, WAN HTTPS-only); semua `charAt(0)`/`substring(indexOf)` berpenjaga (`isEmpty`/hitung-titik/cek-batas); `shorten` vs `potongPesanGalat` beda ujung (tak didedup); factory SSL loopback memo + cap (tanpa I/O per tick); `FMT_CACHE` 3 pola konstan; `bandingVersi` hanya jalur Start/update (dingin); tanpa `StringBuffer`/`+=` string di loop/`StringBuilder` panas tanpa kapasitas.

## [Belum rilis] — Audit agresif 40: wrapper delegasi + port tanpa eksepsi
- `SettingsActivity`/`MainActivity`: wrapper `readWvVersion`/`currentServerVersion` (delegasi satu baris + komentar ganda sisa refactor) dihapus; 7 situs panggil `Updater.*` langsung. Baris bersih -11.
- `ServerService.portLoopback`: saring `portTampakAngka` sebelum `parseInt` (selaras `normalisasiPort`) agar prefs korup tak melempar tiap health gagal di ART lama.
- Test: `portLoopback_tolakKorupTanpaLempar` (`ServerServiceTest`).
- Sapuan bersih (bukan temuan): `FMT_CACHE` terbatas 3 pola konstan (tanpa bocor); `reportDownload` throttle 256 KB; `debounce` jadwal bot 1 dtk; `disconnect`/tutup stream semua jalur koneksi; TTL + worker untuk semua info berat.

## [Belum rilis] — Audit agresif 39: metode mati + getMessage ganda terakhir
- `TlsCert.utcTime`: metode mati (nol pemanggil di app + test, diganti `waktu()` yang menangani GeneralizedTime >=2050) dihapus 12 baris; `tulisUtc` tetap dipakai `waktu()`.
- `TgBackup` (3 situs): `getMessage()` 2x tiap chat gagal unggah (loop per-chat) + 2x di dua verifikasi zip kini lokal `pesan`/`msgSebab` sekali.
- Tanpa test baru: hoist murni setara (pola sama audit-37/38 yang sudah dikunci test); `waktu()` tetap dikunci `TlsCertTest` yang ada.
- Sapuan bersih (bukan temuan): nol metode yatim di 21 file (pindai definisi-vs-panggilan), nol import yatim di semua file, tanpa `toLowerCase()`/`SimpleDateFormat` tanpa Locale (bug locale Turki nihil), `TimeZone.getDefault` hanya aritmetika offset, aturan ProGuard manifest-auto-keep + eksplisit selaras, koneksi `disconnect`/stream-tutup di semua jalur (redirect loop + health tick + checksum).

## [Belum rilis] — Audit agresif 38: salinan getMessage bot + jam ganda backup
- `TgBot` (/update + /webvault): pola `getMessage()` 3x tiap gagal unduh kini lokal `pesan` sekali — salinan kembar dari pola `ServerService` yang sudah dirapikan di audit-37 tapi lolos di jalur perintah bot.
- `TgBackup.pesanGalatBackup` + `backupNowIsi`: jam dibaca 2x (cek wajar vs bangun pesan) kini `kini` sekali; cek vs pesan satu instant yang sama.
- Test: `pesanGalatBackup_jamWajarPakaiIntiSekali` (`TgBackupTest`).
- Sapuan bersih (bukan temuan): kode request alarm 101/0/3 + `getActivity` terpisah (tanpa tabrakan `PendingIntent`); status lintas-thread `volatile` (`running`/`binaryVersion`/`downloadStatus`/`logVer` atomik); `downloadStatus` throttle 256 KB; paritas ID portrait-vs-landscape sama persis (13 + 4 ID); string 147/147 + warna day/night 32/32 sinkron; `delete(0,n)` trim log hanya saat lewat ambang (amortisasi aman); tanpa komentar kode mati; loop tunggu DB membaca ulang jam tiap 500 ms sesuai kebutuhan (bukan ganda sia-sia).

## [Belum rilis] — Audit agresif 37: parse tanpa alokasi + pesan galat sekali baca
- `KernelCompat.parseKernel`: satu pass tanpa alokasi (dulu `trim` + 4 `substring` + `indexOf` per Start); digit ASCII eksplisit (dulu `Character.isDigit` menerima digit Unicode yang pasti gagal `parseInt` lalu melempar `NumberFormatException` tiap Start); angka raksasa dijenuhkan (dulu melempar, hasil legacy sama = modern). Kesetaraan dibuktikan simulasi (termasuk NBSP/digit Arab/overflow) + test.
- `ServerService.ensureBinary/ensureShim`: `e.getMessage()` 3x/2x tiap gagal unduh/pasang kini lokal `msgBin`/`msgShim` sekali.
- `TgBackup.potretTls`: `finally` manual ganda per berkas jadi try-with-resources (D8 aman API 21); semantik tutup-abaikan sama, bytecode lebih ramping.
- Test: `parseKernelTanpaAlokasiSetara` (`KernelCompatTest`).
- Sapuan bersih (bukan temuan): rantai PEM berisi 6 sertifikat (kedaluwarsa 2031-2046, anchor aman); vektor tanpa path `.9` (lint `InvalidVectorPath` aman); `isDestroyed` butuh API 17 (minSdk 21 aman); `localUrl` di UI thread hanya baca prefs + IP cache 60 dtk (tanpa socket, tanpa `NetworkOnMainThread`); `showAboutDialog` IPC ganda hanya saat dialog dibuka; `heal` prefs + `folderBytes` + info storage semua TTL/cache + worker; tanpa `computeIfAbsent`/`String.join`/`Optional`/`stream` di scope app.

## [Belum rilis] — Audit agresif 36: syscall sekali + validasi tanpa alokasi tiap ketikan
- `ServerService.pumpOutput`: `elapsedRealtime()` dulu 2x per baris log deras (cek throttle + pakai) kini satu `kini` per iterasi; hemat syscall + tutup jendela TOCTOU throttle.
- `ServerService.startServer`: `port.trim()` 6x tiap gagal start kini `portRapi` sekali; pesan status/log/Telegram identik.
- `TgBackup.jadwalTundaBoot`: `elapsedRealtime()` ganda (`now + sisa(now)`) kini satu `kini`; trigger identik.
- `SettingsActivity.galatChat`: jalur cepat satu entri tanpa pemisah — tanpa `ArrayList` + `contains()` kuadrat tiap ketikan; setara parser daftar (dibuktikan simulasi + test).
- `SettingsActivity.validasiInline`: `setText`/`setVisibility` tiap ketikan dulu selalu memicu layout; kini `setTeksGalat` (cache tag) + `samakanVisibilitas` — layout hanya bila teks/visibilitas berubah. Aman API 21.
- Test: `galatChatSatuEntriTanpaAlokasi` (`SettingsActivityTest`).
- Sapuan bersih (bukan temuan): `ConcurrentHashMap.putIfAbsent` aman API 21 (override lawas, bukan default-method 24); `NotificationChannel`/`setExactAndAllowWhileIdle`/`canScheduleExactAlarms`/`FROM_HTML_MODE_LEGACY` semua guard versi; tanpa `java.time`/`stream`/`String.join`/`printStackTrace`/`System.out` di scope app; `try-with-resources` aman via D8; `FLAG_IMMUTABLE` konstanta inline + guard M.

## [Belum rilis] — Audit agresif 35: throttle getMe + dedup lower perintah
- `TgBot.isiNamaBotBilaKosong`: STB offline dulu membayar handshake TLS `getMe` gagal tiap 20 dtk poll; kini throttle 1 jam per token (`bolehCobaGetMe` murni: token ganti = coba langsung, elapsed mundur = coba lagi). Hemat baterai + CPU + log.
- `TgBot.kecilkanRentang`: 2 loop salin-lower ASCII identik (`namaPerintah` + `namaPerintahUntukBot`) jadi satu pintu batas-jepit; perilaku sama persis (terkunci test perintah yang ada).
- Test: `kecilkanRentang_lowerAsciiJepitBatas`, `bolehCobaGetMe_throttleSatuJamPerToken` (`TgBotTest`).
- Sapuan bersih (bukan temuan): `pollOnce` single-flight + snapshot jam/id per poll; `httpPostForm`/`bukaFileTelegram`/`bukaIkutiRedirect` semua berset timeout + redirect fail-closed; `verify`/`perluUpgradeHash` batas iterasi + tolak heks raksasa; `Boot/Alarm/TgBotReceiver` tanpa `getAll` di jalur panas; `LogExport` streaming tanpa salinan ganda; `StoragePerm` dialog throttle per proses.

## [Belum rilis] — Audit agresif 34: satu pintu penangan antrean penuh
- `Util.penanganAntreanPenuh(tag)`: 3 salinan identik handler buang-tertua (Util/TgBot/PinGate, ±25 baris tiapnya) jadi satu factory; semantik sama persis (buang tertua + jejak log + offer sekali anti-rekursi StackOverflow). Varian `TgBackup` (TG_MSG/TG_PENTING) sengaja tetap sendiri: membawa `catatLog` server + cek shutdown.
- Test: `penanganAntreanPenuh_buangTertuaTanpaLempar` (`UtilTest`) — antrean penuh tak melempar, tugas gugur tercatat.
- Sapuan bersih (bukan temuan): `TgBotReceiver` goAsync+wakelock+fallback berlapis; `Boot/AlarmReceiver` tanpa `getAll` di jalur panas; `AutoUpdate.cek`/`PinActivity`/`SettingsActivity.refreshRingkasan`+`teksKuncian` semua worker/cache/gerbang; `Toast`/`dialog` hanya dari UI; tanpa `MODE_WORLD`/`sendBroadcast`/`printStackTrace`/`String.format` di scope app; semua `openConnection` berset timeout; `getPackageInfo` sekali saat create.

## [Belum rilis] — Audit agresif 33: encode-once multi-chat + verifier statis + toast aman-thread
- `TgBackup.kirimSinkron`: `URLEncoder.encode` potongan pesan dulu jalan per kombinasi chat×potongan (teks 4KB ke 5 chat = 5x encode identik di CPU STB); kini encode sekali per chat/potongan/markup + rakit via `bangunParamPesan` satu pintu (builder pra-ukuran, tanpa regrow). Byte body identik.
- `ServerService.cobaKode`: lambda `HostnameVerifier` loopback dulu dialokasi tiap tick health (30 dtk/2 mnt); kini konstanta statis `VERIF_LOOPBACK` tanpa state.
- `LogActivity.toast`: langsung `Toast.makeText().show()` crash (`Looper.prepare`) bila kelak dipanggil dari worker; kini via `ui.post` selaras Main/Settings + null-aman.
- Test: `bangunParamPesan_tanpaKeyboardDanDenganKeyboard` (`TgBackupTest`).
- Sapuan bersih (bukan temuan): `reportDownload` hanya string di memori (tanpa tulis prefs per 256KB); `catatLog`/`appendLog` pakai `Date`+`SimpleDateFormat` per-thread tanpa alokasi; `appendUiLog` throttle 500 ms; `AutoUpdate.cek` selalu di worker; `PinActivity.verify` di pool; `collectIps`/versi/ringkasan/kuncian semua cache+gerbang; tanpa `printStackTrace`/`System.out`/`Random` non-sekaman; `String.format` nihil di scope app; semua `openConnection` berset timeout; semua receiver pakai intent eksplisit.

## [Belum rilis] — Audit agresif 32: kanonis ok-true + gerbang samaran tanpa pindai buta
- `TgBot`/`TgBackup`: 3 cek `"ok":true` mentah (`contains`) jadi satu pintu `TgBot.responsOk` (regex `POLA_OK_TRUE` toleran spasi `"ok": true`); perbaikan bug nyata — hapus/pesan dan kirim dokumen yang sah tapi ber-spasi dulu ditolak sebagai gagal.
- `TgBackup`: `DataOutputStream.writeBytes` (tersinkron per baris) untuk header multipart jadi `tulisAscii` (byte rendah, tanpa lock, byte identik — dikunci test); import mati dibuang.
- `LogActivity.samarkanLog`: gerbang JSON (`"..."`/`'...'`) yang dulu `indexOf` kutip buta (kutip ada di hampir tiap baris) kini gerbang topeng kredensial dulu; `indexOf(':')` yang selalu benar di log ber-stempel waktu jadi pra-cek bentuk `adaBentukTokenMentah` (tanpa mesin regex, jam `12:30`/port `8088` gugur murah); 4 `contains` IP (`4x` pindai penuh tiap tick) jadi `adaIpPrivat` sekali jalan (`10.`/`172.` menuntut digit seperti regex).
- `LogActivity.topengKunci`: deteksi `token=` dilonggarkan ke `token` agar gerbang JSON berbasis topeng tetap over-aproksimasi aman untuk kunci `"token": "..."` tanpa `=`; tanpa redaksi hilang (pra-cek dibuktikan tak lebih ketat dari regex via simulasi + test).
- `Util.handlerUtama`: double-checked locking (`KUNCI_HANDLER`) — dua thread bersamaan dulu bisa membuat dua Handler dan membocorkan satu.
- Test: `adaBentukTokenMentah`/`adaIpPrivat`/`samaranJsonTanpaEquals` (`LogActivityTest`), `tulisAscii_byteIdentikWriteBytes` (`TgBackupTest`), `responsOk_toleranSpasi` (`TgBotTest`).
- Sapuan bersih (bukan temuan): `waitFor(timeout)` sudah guard O+ dengan fallback waiter-join pra-Oreo; `Base64` pakai `android.util`; tanpa `String.join`/`computeIfAbsent`/`java.time`/`stream` di scope app (aman API 21); `PendingIntent` IMMUTABLE-guard semua; `Html.fromHtml`/`StatFs`/`AlarmManager.exact` semua guard versi; semua import terpakai.

## [Belum rilis] — Audit agresif 31: pra-alokasi buffer pembaca batas
- `TgBackup.bacaTerbatas` + `SettingsActivity.readCapped`: `ByteArrayOutputStream` pra-alokasi 8 KB (dulu 32 B bawaan mengganda + menyalin berulang untuk payload hingga 512 KB–1 MB); perilaku sama persis.
- Sapuan bersih: charset eksplisit di semua konversi byte↔string; loop unduh/hash 64 KB; `tailLog`/`shorten` tanpa salinan perantara; `refreshRingkasan`/`validasiInline` sudah gerbang-cache; `equalsIgnoreCase` sisa hanya di jalur dingin; tidak ada baca 1-byte.

## [Belum rilis] — Audit agresif 30: trim sekali + pulihkan interupsi
- `SettingsActivity.saveAndStart`: `port` sudah di-trim saat baca input — buang `trim()` kedua.
- `SettingsActivity.perluWizard`: trim tiap argumen sekali ke lokal (dulu 2x) + `dataDirBawaanSegar()` hanya dipanggil bila input tak kosong; semantik sama persis.
- `ServerService.watchProcess` + runnable backup `TgBackup`: pulihkan status interupsi (`interrupt()`) saat tangkap `InterruptedException` agar thread pool tak kehilangan sinyal interupsi. Watchdog `Updater` sengaja tak diubah (langsung `return`, sudah benar).

## [Belum rilis] — Perbaiki build: doFinal Mac 4-argumen
- `PinCrypto.pbkdf2Manual`: `Mac` tak punya `doFinal` 5-argumen (itu milik `Cipher`) sehingga build gagal; ganti ke `update` + `doFinal(output, offset)` yang ada sejak API 1 — tetap tanpa alokasi per iterasi, hasil bit-identik.

## [Belum rilis] — Audit agresif 29: IPv4 manual tanpa split + ketat titik-akhir
- `TlsCert.ipv4`: tulis ulang manual digit-only tanpa `split`/`parseInt` — hemat alokasi regex di STB lama sekaligus perbaiki bug `1.2.3.4.` lolos (split membuang ekor kosong) dan `+1.2.3.4` lolos (parseInt terima plus); hanya digit + tepat 3 titik, alokasi `byte[4]` hanya saat valid.
- Test `TlsCertTest`: tambah kasus titik-akhir/segmen-kosong/plus agar regresi terkunci.
- Sapuan bersih: sisa `split` (`namaDnsValid`/`daftarDns`/`ipv6`/`pindaiHexChecksum`) sudah pola statis + limit `-1` benar; `AutoUpdate` channel/notif sudah guard O/M/33; `PendingIntent` semua IMMUTABLE-guard; tanpa `java.nio/time/stream/String.join` di scope app (aman API 21).

## [Belum rilis] — Audit agresif 28: PBKDF2 tanpa alokasi, mkdirs sekali, poll snapshot
- `PinCrypto`: `pbkdf2Manual` pakai `doFinal` 4-argumen ping-pong — 12rb iterasi tak lagi membuat 12rb array 32B (~384KB sampah) tepat di STB tua API 21-25 yang memakai jalur ini. Hasil bit-identik (test `pbkdf2ManualSamadenganFactory` tetap acuan).
- `Updater`: ekstrak web-vault `mkdirs` sekali per direktori (`dirSiap`) — ribuan stat sia-sia di FAT /sdcard hilang; perilaku sama (dir segar per ekstrak).
- `TgBot`: `pollOnce` snapshot `kiniPoll` + `idRuang` sekali per poll — syscall jam ×10 dan lookup `optLong("id")` ×4 per pesan hilang; snapshot tunggal juga lebih konsisten untuk batas basi.
- Sapuan bersih (bukan temuan): timeout long-poll 8 dtk < read 35 dtk (tanpa busy-loop); `AlarmReceiver` delegasi ke service (tanpa `goAsync` pun aman); `cleanup`/`sapuExport` sudah worker; layout tanpa teks hardcode; izin manifest semua terpakai.

## [Belum rilis] — Audit agresif 27: topeng sekali-jalan, pindai manual, /proc cache, port worker, multi-chat tahan-gagal
- `LogActivity`: gerbang `samarkanLog` ~8 pindai `cocokAscii` penuh jadi satu `topengKunci()` sekali-jalan (bit per kunci, semantik substring sama persis).
- `ServerService`: `dataDirAman` tanpa `split` regex + tanpa `getBytes` per segmen (hitung byte UTF-8 manual, cacah dipakai ulang); `POLA_SLASH` mati dibuang.
- `ServerService`: pindai `/proc` di-cache 8 dtk (`findChildPid`+`killStale` satu pindai; basi aman via verifikasi ulang); buffer baca `status` 2KB bukan 8KB.
- `SettingsActivity`: cek `isPortBusy` (2x bind) pindah worker + dialog di UI; tick sibuk 500 ms jadi 1500 ms selaras Main.
- `TgBackup`: upload multi-chat coba semua chat (gagal per-chat dilaporkan, sukses parsial + `fileId` chat sukses terakhir; lempar hanya bila semua gagal); `cleanupOldBackups` stat hanya berkas backup; `TG_PENTING_EXEC` buang ber-log selaras `TG_MSG_EXEC`.
- `Updater`: `panjangKonten` sekali parse (buang fallback `getContentLength` ganda + overflow int).
- `SettingsActivity`: `sapuExportEnkripLama` tanpa peta boxing + catat gagal hapus.
- Koreksi audit: `cleanupOldBackups`/`sapuExport` ternyata sudah di worker (`runBusy`) — bukan ANR; yang dipindah hanya cek port. Helper `parsePortAman` ditolak: tiap situs punya fallback/toast beda, unifikasi berisiko drift.

## [Belum rilis] — Audit agresif 26: wakelock bot, alias array, symlink cepat, tulis log streaming
- `TgBotReceiver`: timeout wakelock 20 dtk jadi 60 dtk — poll terburuk ~50 dtk kedaluwarsa di tengah saat Doze sehingga CPU tidur sebelum poll selesai.
- `StoragePerm`: `izinRuntime()` kembalikan salinan (`clone`) — array bersama yang diekspos bisa diubah pemanggil dan meracuni panggilan berikut.
- `FileShareProvider`: `adaSymlinkInduk()` jalur cepat kanonis-vs-absolut dulu — cocok langsung false tanpa 256x lstat+canonical per segmen di ART lama; beda langsung true; walk lstat hanya saat I/O gagal.
- `LogExport`: kepala kecil + badan log ditulis streaming via `OutputStreamWriter` — tanpa salinan ganda String+byte 300 KB di heap STB.
- `ServerService`: `startForegroundCompat()` pastikan `createChannel()` dulu (anti crash notif tanpa channel di Android 8+); trim log maju ke awal baris berikut (pindai batas 4 KB) agar tak sisakan potongan baris.
- `Updater`: cek TOCTOU resume hanya panjang file — `lastModified` FAT bergranularitas 2 detik sehingga perubahan dalam jendela lolos.
- `Util`/`PinGate`: penolakan antrean penuh kini ber-log (`BUANG_BERLOG`) — tugas hilang tak lagi sunyi.
- `MainActivity`: cache URL jadi `volatile`; tick sibuk 500 ms jadi 1500 ms (separuh bangun CPU saat unduh).

## [Belum rilis] — Audit agresif 25: 5 validator ketikan trim sekali
- `SettingsActivity`: `galatPort`/`galatAdmin`/`galatTgToken`/`galatChat`/`galatFolder` trim 2x jadi lokal `isi` sekali — validator ini jalan tiap karakter ketikan (jalur input terpanas), perilaku sama persis.
- Sapuan angle baru (banding `==`, race ARM, charset, `disconnect`, leak static, duplikasi): nihil temuan — ronde 15–24 sudah menutupnya.

## [Belum rilis] — Audit agresif 24: trim ganda boot + sapuan nihil
- `BootReceiver`: `dataDir.trim()` 2x jadi lokal sekali (`dRapi`) — sisa terakhir keluarga trim-ganda (jalur boot, perilaku sama).
- Sapuan agresif ~15 keluarga pola (API desugar, `commit()`, IO 1-byte, eksepsi jalur panas, timeout, notifikasi, UUID/enum, kode terkomentari, `@Test`, caching RSS/uptime/IP, jadwal bot, pencarian log, receiver): nihil temuan baru — ronde 15–23 sudah menutupnya.

## [Belum rilis] — CI hijau: cabut ID lint tak dikenal + cek import pindah ke verifier
- `app/build.gradle.kts`: cabut `"UnusedImports"` dari set error lint — ID tak dikenal AGP 8.5.2 (warning CI "Unknown issue id"; lint AGP tak punya cek import Java, itu ranah compiler/IDE).
- `tools/verifikasi-path.py`: `cek_import_mati` baru (GAGAL bila nama import tak dipakai di kode; wildcard dilewati, komentar+string dikupas) — penjagaan setara tetap jalan di tiap push via SDK GitHub. Pohon kini bersih (nol import mati).
- Gradle 8.14.3 "out of date" (notice saja): sengaja tak di-upgrade — Gradle 9 butuh AGP ≥8.7 (rantai risiko), build tetap hijau.

## [Belum rilis] — Audit agresif 23: buang trim redundan + kunci tick + enumerasi ganda
- `SettingsActivity` tick: kunci status `statusText+"|"+...` tiap 500/1000 ms jadi literal `"on"`/`"off"` (ikut pola `MainActivity`, nol alokasi per tick).
- `ServerService`: buang `.trim()` redundan yang sumbernya selalu ternormalisasi — `portLoopback()`, `pingRinci()`, `localUrl()` (per health tick/10 dtk); `mentah.trim()` 3x jadi lokal sekali (`mRapi`); `lanHost()` 2x enumerasi interface saat Start jadi sekali (`hostSegar`).
- `Updater`: `baris.trim()` 2x jadi sekali (`bRapi`); `versiCocok` trim tiap arg sekali (dulu cek lalu `normVersion` trim lagi).
- Semua perilaku sama persis (fallback `-1`/default/`null` tak berubah); sapuan pola boros/thread/tick nihil temuan baru.

## [Belum rilis] — Verifikasi anti salah-fix: hapusan tetap + paritas + locale + uji-ubah
- `tools/bekas-hapusan.txt` (baru): daftar hapusan tetap (`batalTunda`, `isLegacyDevice`, `tokenPertama`) — baris baru yang memanggilnya = GAGAL di push berikut; hapusan baru yang belum tercatat = peringatan (menutup celah daftar manual yang basi + pelajaran grep BusyBox yang lapor "hilang" palsu).
- `tools/verifikasi-path.py`: `cek_uji_ubah` (metode yang tubuhnya diubah tapi tanpa jejak di `app/src/test` = peringatan), `cek_paritas_resource` (colors siang/night + ID layout/land = GAGAL, dulu manual), `toLower/UpperCase()` tanpa Locale naik dari peringatan jadi GAGAL (bug locale Turki), deteksi definisi terhapus tangkap package-private + kurawal baris-berikut.
- `scripts/cek-cepat.sh`: ekstrak ID portabel tanpa `rg` (mesin BusyBox); workflow verifikasi kini menjalankannya di CI (selama ini tak jalan di CI!); hook pre-push jalankan keduanya.

## [Belum rilis] — Audit agresif 22: buang token mati + label DNS tanpa salinan
- `TgBot.tokenPertama`: buang metode mati (0 pemanggil dari 574 metode tersapu; tanpa tes merujuk).
- `TlsCert.namaDnsValid`: pola label jadi `CASE_INSENSITIVE` sehingga buang `toLowerCase` per label (hemat satu salinan String per label tiap validasi; perilaku sama — tes `Vault.Lan`/`VAULT` tetap hijau).
- Sapuan mati: 169 field dicek nol mati; resource ID/paritas warna-string OK.

## [Belum rilis] — Audit agresif 21: heksa terpusat + tanpa eksepsi poll + seed dadu
- `Util.HEKS`/`hex12`: tabel + penulis heksa terpusat; duplikat identik di `LogExport`/`TgBackup` dibuang, `TgBot` ikut pakai tabel (+ uji `UtilTest`).
- `PinCrypto.hex`: `Character.forDigit` per nibble jadi tabel (hasil sama persis, hemat di verifikasi PIN).
- `TgBot.idChatResmi`: saring digit + cap 19 digit sebelum `parseLong` agar config username tak melempar tiap poll 20 dtk (ID grup minus tetap lolos).
- `SettingsActivity` dadu token: `new SecureRandom` tiap ketuk jadi `ACAK_DADU` sekali-per-proses.
- `ServerService.appendLog`/`catatLog`: rantai `append` tanpa String perantara per baris log.
- `ServerService.startServer`: `trim` token + path web-vault sekali (dulu dua kali).
- `MainActivity.readWvVersion`: buang wrapper 1-baris, panggil `Updater` langsung.
- Sapuan mati: 254 metode privat dicek, nol mati (85 sekali-pakai terverifikasi sah).

## [Belum rilis] — Audit agresif 20: hemat tick/health/hash + buang duplikat
- `MainActivity`/`SettingsActivity` tick: `trim()` 2x per nilai jadi lokal sekali (`dRapi`/`pRapi`/`rdRapi`/`rpRapi`/`raRapi`).
- `MainActivity.onCreate`: dua `getPackageInfo` jadi satu IPC PackageManager.
- `ServerService.pingRinci`: fallback `/api/config` dilewati bila `/alive` galat transport (-1, lapor "dilewati") — hemat satu handshake TLS di ARMv7 lambat.
- `HttpsCompat.capOverride`: hash cap per byte tanpa `Arrays.copyOf` per chunk.
- `TgBot.teksVersi`: tanpa panggil folder bawaan ganda (fallback sekali).
- `Updater.readBundledVersionLabel`: satu implementasi label versi; metode privat duplikat di `MainActivity`/`SettingsActivity` dibuang (+ import mati dibersihkan agar lint `UnusedImports` tetap hijau).
- `KernelCompat.isLegacyDevice`: buang wrapper (kanonis `isLegacyKernel`); `Updater` + tes ikut.
- `ServerService.DEFAULT_DATA_DIR`: `@Deprecated` (kode baru wajib `dataDirBawaanSegar()`).
- `TlsCert`: 2 TODO jam-miring jadi catatan perilaku (regen otomatis di Start berikut setelah jam pulih).
- `AlarmReceiver`: buang cek literal `TIME_SET` ganda (konstanta = string itu).

## [Belum rilis] — Radar lint buru-bug: 7 cek jadi error CI
- `app/build.gradle.kts`: `UnusedImports`, `ObsoleteSdkInt`, `DrawAllocation`, `HandlerLeak`, `StaticFieldLeak`, `Recycle`, `Wakelock` naik ke `error` — SDK GitHub (`lintDebug` tiap push) yang berburu bug/inefisiensi, bukan SDK lokal. Semua lolos audit manual sebelum dipromosi.
## [Belum rilis] — Audit agresif 19: buang import mati + sapuan sampah nihil
- `MainActivity.java`: buang `import android.os.Build` yang tak terpakai (satu-satunya kemunculan kata `Build`).
- Sapuan sampah: 12 kunci prefs dicurigai semua terpakai; import 21 berkas bersih; ~50 metode privat dicurigai semua terhubung (callback/listener/`::ref`/tes); resource yatim nihil (verifikasi-path).
## [Belum rilis] — Audit agresif 18: banding versi sekali + triase warning basi
- `AutoUpdate.cek`: `bandingVersi(real, latest)` 2x jadi lokal `bandingReal` sekali.
- Triase anotasi build: `TasirinBtnHero`/`overflow`/`btn_tv_*`/`hero_run_*`/`bg_hero_running`/`bg_btn_hero`, `%d minutes`, `quantities one (in)` semuanya basi (berkas tak ada lagi); `x86_64 ChromeOS` disengaja (armeabi-v7a saja).
## [Belum rilis] — Audit agresif 17: belah pesan jendela + trim sekali
- `TgBackup.pecahPesan`: `lastIndexOf` pindai mundur sampai indeks 0 per potongan (O(n^2)) jadi loop manual terbatas jendela `(i + batas/2, akhir)`; hasil sama (tes newline/gabung utuh tetap mencakup).
- `TgBot.pollOnce`: `chat.trim()` per callback jadi `chatResmi` yang sudah di-trim.
## [Belum rilis] — Audit agresif 16: Date pakai ulang + gerbang ringkasan
- `ServerService.catatLog`: `new Date()` per baris jadi `LOG_TGL` ThreadLocal pakai ulang (pola `appendLog`).
- `SettingsActivity.refreshRingkasan`: gerbang kunci gabungan murah (5 komponen tanpa `getString`) di atas; 9 `getString` + concat hanya bila berubah + badan pakai ulang komponen gerbang (tanpa trim/baca ulang).
- `SettingsActivity.teksKuncian`: memo dari pasangan pref mentah; `normalisasiPinVersi` (regex) hanya bila kuncian berubah.
## [Belum rilis] — Audit agresif 15: pesan penting + nama perintah + persen tanpa alokasi/eksepsi
- `TgBackup.pesanPenting`: `toLowerCase` salinan penuh + 7x `contains` jadi 7x `Util.mengandungAbaikanHuruf` (jalur ASCII tanpa alokasi).
- `TgBot.namaPerintah`: `text.trim()` + `toLowerCase` salinan penuh jadi pindai token manual (hanya token pendek di-lowercase), pola `namaPerintahUntukBot`.
- `Updater.persenUnduhan`: gerbang digit manual (1-3 char) sebelum `parseInt` agar status malformasi tak melempar tiap 500 ms di ART lama.
## [Belum rilis] — Perbaiki tes indeks log (build merah)
- `MainActivityTest.indeksTakPekaAscii`: ekspektasi posisi `ERROR` salah hitung (6, seharusnya 4) — satu-satunya dari 395 tes yang gagal di CI; logika aplikasi tak berubah.

## [Belum rilis] — Verifikasi path v2: 9 cek baru
- Kebenaran: komponen manifest tanpa kelas, berkas Java dihapus tapi kelas masih disebut, duplikat definisi seberkas, kurung Java tak seimbang (pemindai sekali-jalan, sadar URL-string), acuan generik semua tipe res (termasuk `layout-land` + `res/xml` + `plurals`).
- Efisiensi lama: baris baru berpola boros (`String.format`, `SimpleDateFormat`, `Calendar`, `split`/`matches`, `new Thread`, `toLowerCase()` tanpa Locale) jadi peringatan; izin manifest baru + definisi baru tak terpakai + pengingat CHANGELOG.
- Perbaikan bug checker: pindai hunk `.java` saja (tak menuduh diri sendiri), uji-baru yang tak pernah cocok, crash `set.append`, pemindaian berulang puluhan kali saat hapus seberkas.
- `pasang-hook.sh` lapor jujur bila FS `noexec` (kait git tak jalan, CI jadi cadangan).

## [Belum rilis] — Sistem auto-verifikasi path baru
- `tools/verifikasi-path.py`: cek murni-Python tanpa SDK — XML sehat, acuan `R.*`/`@*/` yatim (termasuk gaya titik `Theme.A` dan pengecualian `android.R`), `values-in` sinkron, aturan repo (targetSdk<29, armeabi-v7a saja, tanpa binary/keystore), metode dihapus tapi masih dipanggil + daftar bekas hapusan (`batalTunda`), metode statis baru tanpa uji, `diff --check`. LOLOS=0/GAGAL=1.
- `tools/pasang-hook.sh`: pasang kait `pre-push` agar tiap push otomatis terverifikasi (gagal = push batal sebelum sampai CI).
- `.github/workflows/verifikasi-path.yml`: workflow ringan baru (~1 mnt, tanpa SDK, tak mengubah workflow rilis) memeriksa rentang push; dokumen `*.md` dilewati.

## [Belum rilis] — Audit agresif 14: buang mati + gerbang login sekali-jalan
- `styles.xml`: hapus alias tak terpakai `AppButton`/`TvOutlinedButton`/`AppInput` (0 referensi) + namespace `tools` yatim.
- `Util.batalTunda`: hapus (0 pemanggil dari 559 static).
- `ServerService.saranLoginUntukBaris`: gerbang 16 pindaian jadi `minatLogin()` sekali-jalan dispatch huruf-pertama; frasa panjang tahap lanjut tak berubah + uji baru.
- `MainActivity.indeksTakPeka`: `Character.toLowerCase` Unicode per char jadi `Util.samaHurufAscii`; `tempelLogBerwarna` tanpa `substring` per baris (`muatKataRentang` + `append` rentang, `muatKata` jadi wrapper) + `MainActivityTest` baru.

## [Belum rilis] — Audit agresif 13: gerbang samaran + sorot sekali-jalan
- `LogActivity.butuhSamaran`: 23 pindaian per tick (8x `regionMatches` penuh + 15x `contains`) jadi satu loop dispatch huruf-pertama (`pindaiKunciRahasia` + `cocokKunciAt` ASCII); IP privat tetap `contains` native murah.
- `LogActivity.highlightLog`: pra-cek 3x full-text + pindai ulang per baris via `regionMatches` Unicode jadi satu walk ASCII per baris (galat + query sekaligus, span malas tanpa alokasi saat bersih); kueri non-ASCII lewat jalur lambat.
- `Util.mengandungAbaikanHuruf`: jalur cepat ASCII (`cocokAscii`/`samaHurufAscii` via `(c|32)`) tanpa lipatan case Unicode; fallback `regionMatches` hanya untuk needle non-ASCII + uji baru.
- `MainActivity`: `versiCocok` (regex `normVersion`) jadi memo pasangan + `StringBuilder` versi dan `durationText`/`getString` uptime digerbang komponen/detik sebelum dibangun (tanpa alokasi saat tak berubah).

## [Belum rilis] — Audit agresif 12: cap TLS + cocok chat tanpa eksepsi
- `HttpsCompat.capOverride`: gerbang TTL 60 dtk (pola `capCaAktif`) — tiap koneksi (poll 20 dtk + health) tanpa kunci kelas + stat file.
- `Util.cocokChat`/`chatAdalahGrup`/`chatPerluAnggapGrup`: saring `angkaBulat` + kupas `@` manual — tanpa `replaceFirst` regex dan `NumberFormatException` tiap pesan bot di ART lama; perilaku identik + uji baru.

## [Belum rilis] — Audit agresif 11: tanpa regex + tanpa eksepsi di tick
- `Updater.parseBinaryVersion`: memo input->hasil — Matcher regex per detik dari UI hilang (input hanya berganti tiap Start).
- `ServerService.normalisasiPort`: saring digit manual (`portTampakAngka`) — port salah ketik tak menghujani `NumberFormatException` + isi stack trace tiap tick UI/health di ART lama; output identik + uji baru.

## [Belum rilis] — Audit agresif 10: memo tanggal cert + ruang bebas
- `TlsCert`: parse X.509 + lookup provider tiap 30 dtk (tick Settings) jadi memo `[notBefore, notAfter]` bergerbang stat file — perilaku `checkValidity` (-2/-1/0) dipertahankan + uji `sisaDariMemo`.
- `TgBackup.freeBytes`: memo 30 dtk per path — tick info 5 dtk tak membayar 2x binder IPC `StorageManager` tiap kali.

## [Belum rilis] — Audit agresif 9: hemat alokasi tick UI tiap detik
- `SettingsActivity`: `localUrl()` tiap 1 dtk jadi cache 10 dtk (pola `MainActivity`) — tanpa lookup prefs + concat URL sia-sia.
- `MainActivity`: string status Berjalan/Berhenti di-cache + kunci boolean (tanpa `getString` + concat tiap 1-2 dtk di ART lama).

## [Belum rilis] — Audit agresif 8: cache IP/CA + thread PIN anti-bocor
- `ServerService`: TTL cache IP 3 dtk jadi 60 dtk + TTL enumerasi interface 5 dtk jadi 60 dtk — tick UI/health tak membayar ioctl tiap detik di ARMv7.
- `ServerService.capCaAktif`: gerbang TTL 60 dtk tanpa kunci/syscall + invalidasi eksplisit saat sertifikat regenerasi (health + ping Telegram hemat stat eMMC).
- `SettingsActivity.pinExec`: executor per-instance non-daemon (bocor 1 thread tiap rotasi) jadi bersama + daemon; tanpa shutdown agar hash antre tetap tersimpan.
- `PinGate.CATAT_EXEC` + `TlsCert` (zona UTC bersama, digit waktu manual tanpa `String.format`/`getTimeZone` per panggil).

## [Belum rilis] — Memo folder bawaan (hemat binder tiap tick UI)
- `ServerService.defaultDataDir()`: cache per proses — argumen default prefs yang dievaluasi tiap tick tak lagi membayar `getExternalStorageDirectory()`.

## [Belum rilis] — Memo kernel, tunggu mati tanpa eksepsi, heal alarm hemat
- `KernelCompat.legacyPerangkat()`: status legacy dihitung sekali per proses (9 situs Start/health/bot pakai memo).
- `ServerService.waitForOrKill()`: pra-Oreo pakai waiter `join()` tanpa poll `exitValue()` (tanpa hujan eksepsi di ART lama).
- `AlarmReceiver`: heal penuh pindah ke `mulaiBackup()`; alarm palsu/throttle/boot-dini tak membayar parse XML.

## [Belum rilis] — Hemat pindai /proc, hitung baris log, dan seed SSL di STB
- `ServerService`: TTL PID anak 30 dtk jadi 120 dtk + `processRssKb()` keluar cepat bila server mati; `readProcUid()` parse manual tanpa split.
- `ServerService`/`HttpsCompat`: satu `SecureRandom` bersama untuk init SSL (seed kernel lama bisa blokir).
- `PinCrypto.sha256()`: digest SHA-256 bersama (`Util.mdSha256()`).
- `MainActivity`: trim 150 baris log hanya dihitung bila teks >25 KB (bukan pindai penuh tiap tick).

## [Belum rilis] — Deteksi resource yatim otomatis di CI
- `app/build.gradle.kts`: `UnusedResources`/`UnusedIds`/`UnusedQuantity`/`MissingQuantity` naik jadi error lint — resource yatim gagal build tiap push.
- Hapus 9 `android:id` yatim (`heroBar`/`appTitle` ×2 orientasi, `logTitle`, 5 seksi Settings, `secRingkasanRow`) + aturan di `AGENTS.md`.

## [Belum rilis] — Audit agresif 7: TLS, health, regex, dan MIME hemat STB
- `HttpsCompat.capOverride`: cek stat dulu, hash isi hanya bila berubah (poll 20 dtk + health tak bayar baca file tiap koneksi).
- `TgBot`: long-poll `timeout=15` jadi `8` + wakelock receiver 60 dtk jadi 25 dtk (radio/CPU tak aktif 75% waktu saat idle).
- `ServerService.pingRinci`: gerbang TCP murah dulu; server mati tak membayar 2x handshake TLS tiap tick.
- `TgBackup.adaSegmenDotDot` + `TgBot.rapikanSpasi`/`namaPerintahUntukBot`: manual tanpa regex/split (ribuan entri zip + tiap pesan masuk).
- `FileShareProvider`: 6 cek MIME/nama via `regionMatches`/`equalsIgnoreCase` (tanpa 8x `toLowerCase`).
- `LogActivity.butuhSamaran`: abaikan-huruf sekali per kunci (separuh pindai 100 KB tiap tick worker).

## [Belum rilis] — Audit agresif 6: pool thread, parse tanpa regex, tick idle
- `ServerService`: health/start/stop/restart/backup terjadwal lewat pool bersama (`Util.jalankanBg/jalankanLama`); `appendLog` pakai ulang `Date` per thread; `saranLoginUntukBaris` tanpa `toLowerCase` (saring murah + `regionMatches`).
- `PinCrypto`: pecah hash `PBKDF2$` via `Util.pecahPin` manual (tanpa kompilasi regex tiap verifikasi).
- `TgBot`: offset akhir poll via `apply()` (hemat fsync tiap 20 dtk; perintah berbahaya tetap `commit()` sebelum eksekusi).
- `TlsCert`: `SecureRandom` bersama (hemat seed `/dev/random` di kernel lama).
- `MainActivity`: tick idle 2 dtk saat server mati; `LogActivity`: tick log 1,5 dtk.
- `Util`: tambah `postUtama`/`batalTunda`/`pecahPin`/`mengandungAbaikanHuruf` + uji unit.

## [Belum rilis] — Audit agresif 5: ringkasan ketikan dan pool verifikasi PIN
- `SettingsActivity.refreshRingkasan`: 5 teks ringkasan/status hanya `setText` bila berubah (sebelumnya tiap ketikan selalu layout ulang).
- `PinActivity`: verifikasi PIN (PBKDF2 CPU-bound) lewat pool tugas lama bersama, tanpa thread baru tiap ketukan; aman karena guard `sedangPeriksa` + lockout mencegah antrean menumpuk.

## [Belum rilis] — Audit agresif 4: tick Settings, stempel log tanpa kunci
- `SettingsActivity`: teks status/tombol/badge di-cache sekali di `onCreate` (tanpa `getString` tiap tick); `setText` tombol/badge/hint/status-sibuk hanya bila berubah (tanpa `getText().toString()`); `tandaiLabel` dilewati bila status kotor tak berubah (setText sama pun memicu layout).
- `ServerService`: stempel jam log (`LOG_TS`) jadi `ThreadLocal` — 4 titik `synchronized` di jalur panas catat/append log hilang.
- `TgBackup.pesanJamStbSalah`: format tanggal via `ThreadLocal` (tanpa `SimpleDateFormat` baru tiap panggil).

## [Belum rilis] — Audit agresif 3: poll bot, redaksi log di worker, cap tick
- `TgBotReceiver`: hapus heal prefs penuh tiap poll 20 dtk (parse ulang XML + iterasi semua kunci; baca `aman*` sembuh-sendiri, heal tetap di onCreate/start); polling lewat pool bot bersama + fallback thread bila penuh (jamin `goAsync`/wakelock selesai).
- `LogActivity.refreshLog`: redaksi 13 regex + sorot span pindah ke worker dengan coalesce (satu olahan dalam penerbangan, tick menyusul dilewati, hasil basi dibuang via kunci generasi).
- `MainActivity.refreshHomeLog`: delta per tick dibatasi 8 KB pada batas baris utuh (sisa mengalir tick berikut; urutan + baris utuh terjaga, surrogate aman).
- `Updater`: pindai checksum tanpa salinan lowercase per baris (`mengandungAbaikanHuruf` via `regionMatches`); tanggal STB via `ThreadLocal`.
- `TgBackup`: backup otomatis saat Start lewat pool tugas lama bersama (tanpa thread baru tiap Start).
- Sengaja tak diubah: heal di `AlarmReceiver`/`BootReceiver`/onCreate (jarang, proses segar), `sidikTokenAdmin` (sudah memoize), hint login (sudah digerbang throttle), thread backup panjang satu-shot sebelumnya kini ikut pool lama.

## [Belum rilis] — Audit agresif 2: tick UI, digest bersama, pool tugas lama
- `MainActivity`: rangkai versi per tick via `StringBuilder`; warna log di-cache; pewarnaan baris tanpa salinan lowercase/varargs (`muatKata`/`indeksTakPeka`); redaksi delta log hanya bila `butuhSamaran` lolos.
- `LogActivity`: gerbang `butuhSamaran` di semua jalur redaksi (tampil/bagi/salin/crash/export); timer clipboard via handler utama bersama; sidik clipboard via digest bersama.
- `Util`: digest SHA-256 per thread bersama (`mdSha256`) dipakai `ServerService`/`TgBot`/`Updater`/`SettingsActivity`/`LogActivity`; pool tugas lama (`jalankanLama`) untuk unduh/auto-update/hash PIN agar pool ringan tak macet; `postTunda` bersama.
- `ServerService`: sidik token di-memoize (hash ulang hanya bila token berubah); pindai hint login hanya bila throttle mengizinkan; timeout health loopback 8 dtk jadi 4 dtk.
- `PinCrypto` sengaja tak diubah: lookup factory/Mac ~mikrodetik, kerdil dibanding KDF 12–30rb iterasi; cache objek crypto bersama berisiko thread-safety.

## [Belum rilis] — Hemat CPU/RAM di STB lama (tanpa regex/thread/format berulang)
- `TgBot.lolosJson`: `String.format` per char diganti tulis escape unicode manual; `parseUsernameBot` tak lagi pindai regex DOTALL; `split`/`matches`/`replaceAll`/`replaceFirst` di path perintah diganti pola statis + helper manual (`tokenPertama`, `kupasAt`, `rapikanSpasi`); tanggal status via `ThreadLocal`.
- `TgBackup`: tanggal backup via `ThreadLocal`; `stempelUnik` hex manual; `sudahGantiHari`/`nextMidnight` aritmetika hari lokal tanpa `Calendar`; `rahasiaAlarm` cek hex manual + `SecureRandom` bersama; `folderBytesWalk` pakai absolute path (tanpa syscall kanonis); rangkai param kirim via `StringBuilder`.
- `LogExport`: tanggal via `ThreadLocal`; `SecureRandom` bersama (tak blokir seed tiap export); nama file hex manual.
- `ServerService`: sidik token pakai `MessageDigest` per thread; `split`/`matches` validasi path pakai pola statis; polling tunggu proses 200 ms jadi 500 ms.
- `Updater`/`TlsCert`/`Util`/`KernelCompat`: `matches`/`split` panas diganti pola statis atau loop manual (`hex64`, `tokenAman`, `semuaDigit`, `potongHost`, urai versi kernel tanpa split).
- `Util.jalankanBg`: pool bersama tugas ringan UI (maks 3 thread, antrean 32 buang-tertua); `MainActivity`/`SettingsActivity`/`LogActivity` tak lagi `new Thread` per tugas; rangkai teks refresh Settings via `StringBuilder`.
- Thread siklus-hidup (reader/watcher/stopper service, watchdog versi, backup, poll bot) tetap dedicated; `commit()` daya-tahan (PIN, alarm, boot) tak diubah.

## [Belum rilis] — Tombol log tak freeze UI di STB (redaksi + tulis di worker)
- `LogActivity.shareLog/copyLog`: 14 regex penyamaran di atas buffer 300 KB pindah ke worker; UI hanya menerima hasil (dengan cek activity hidup).
- `LogActivity.exportLogTxt`: salin buffer + tulis Download/MediaStore pindah ke worker; toast penanda disiapkan/disimpan.

## [Belum rilis] — Format backup VWB3: iterasi KDF ikut header, ringan di STB
- `TgBackup`: file backup terenkripsi baru memakai magic `VWB3` dengan iterasi KDF di header (4 byte big-endian): perangkat modern menulis 100rb (tak berubah), STB lama tanpa factory menulis 30rb — enkripsi/restore di STB ~3x lebih cepat.
- Dekripsi membaca iterasi dari header (batas 10rb–120rb ikut `PinCrypto`; di luar itu ditolak fail-closed anti-DoS) sehingga file STB terbaca di HP dan sebaliknya; `VWB1` (SHA1) dan `VWB2` tetap terbaca; `VWB2`/`VWB3` tak fallback SHA1.
- Test: file baru assert `VWB3` + roundtrip; file `VWB2`/`VWB1` rakitan manual (jalur fallback SHA1 kini teruji beneran); file gaya STB (VWB3 30rb) lintas perangkat; header iterasi raksasa ditolak tanpa sisa file; roundtrip `iterKeByte`/`byteKeIter`.

## [Belum rilis] — Backup terenkripsi jalan di STB lama (fallback KDF manual)
- `TgBackup.deriveKey`: tanpa factory PBKDF2-SHA256 di Android lama, backup/restore terenkripsi (KDF 100rb iterasi) melempar seperti PIN dulu — kini jatuh ke `PinCrypto.pbkdf2Manual` dengan iterasi tetap 100rb agar format file tak berubah; jalur SHA1 lama tak disentuh.
- Test `pbkdf2ManualIterasiBesarSamadenganFactory` mengunci kesetaraan manual vs factory pada 100rb iterasi.

## [Belum rilis] — Audit STB lama: hex manual + regex statis di path panas
- `ServerService.sidikTokenAdmin`: `String.format("%02x")` per byte (32x tiap refresh UI 500 ms di UI thread) diganti `Updater.toHex` manual — pola yang sudah dipakai `Updater` sendiri.
- `TgBot.sidikMenu`: pola `String.format` per byte yang sama diganti `Updater.toHex`.
- `SettingsActivity.galatChat`: `String.matches()` (kompilasi regex tiap ketikan) diganti tiga pola statis; perilaku validasi persis sama.
- Diaudit dan dinyatakan sehat: pola redaksi log statis, refresh UI delta + guard, buffer log 300 KB + ekor tampil, backup/zip streaming 64 KB, receiver hanya start service, backup jalan di service, PBKDF2 adaptif, debounce tulis prefs.

## [Belum rilis] — PIN ringan di Android lama: iterasi ikut kemampuan perangkat
- `PinCrypto`: standar iterasi kini dua tingkat — 30rb di perangkat modern, 12rb di STB lama tanpa factory PBKDF2-SHA256 (jalur manual ~2-3x lebih lambat per iterasi) — agar set/buka PIN tetap ~1 detik di CPU lemah. 12rb tetap di atas batas verifikasi 10rb dan hash antar-perangkat saling lolos.
- `PinCrypto`: hasil deteksi factory diingat (`pabrikAda`) agar `getInstance` yang gagal di STB tak diulang tiap buka PIN; `SecureRandom` dipakai bersama agar hemat seed.
- `PinCrypto.perluUpgradeHash()`: normalisasi ke standar perangkat masing-masing — hash HP 30k yang dibawa ke STB diturunkan sekali saat login sukses (buka berikutnya cepat), begitu pula sebaliknya.
- Test baru: `standarLegasiDibawahModernDiatasBatas` + `hashLegasiLolosVerifikasiAntarPerangkat`.

## [Belum rilis] — PIN bisa aktif di STB Android lama (fallback PBKDF2 manual)
- `PinCrypto`: `SecretKeyFactory` PBKDF2WithHmacSHA256 tak ada di stock Android API 21-25 sehingga hash PIN selalu melempar di STB ("PIN gagal diproses") sementara di HP bisa — kini jatuh ke `pbkdf2Manual()` via `Mac` HmacSHA256 yang ada di semua API.
- `pbkdf2Manual()`: PBKDF2-HMAC-SHA256 sesuai RFC 2898, hasil bit-identik dengan factory untuk PIN ASCII sehingga hash HP baru terverifikasi di STB dan sebaliknya; dikunci test `pbkdf2ManualSamadenganFactory`.

## [Belum rilis] — Centang PIN tahan CPU STB lambat
- `SettingsActivity`: aktivasi PIN (centang) pindah ke thread sendiri (bukan antrean `pinExec`) agar tak antre di belakang hash debounce di CPU lemah STB; ketikan/tekan remote ganda selama "Processing PIN" tak lagi menggugurkan hasil via nomor urut.
- `SettingsActivity`: checkbox + field tak lagi di-uncheck/disable selama hash (fokus D-pad STB tak hilang, tak terlihat "tidak bisa aktif"); toggle ganda saat aktivasi jalan diabaikan dan centang dikembalikan.
- `SettingsActivity`: hapus-PIN-pendek dan tulis-otomatis debounce dilewati selama aktivasi berjalan; `cancel(true)` diganti `cancel(false)` karena PBKDF2 tak bisa diinterupsi.

## [Belum rilis] — Teks lockout PIN ikut locale Indonesia
- `PinActivity`: pesan terkunci brute-force yang tadinya Inggris hardcoded kini memakai string `pin_lockout_one`/`pin_lockout_many` (EN + ID) via `teksSisaKunci(Context, long)`; fallback Inggris bila resource gagal dibaca.
- `teksSisaKunci(long)` dipertahankan persis (dikunci `PinActivityTest`) + helper murni baru `menitSisaKunci` dengan test ceiling-nya.

## [Belum rilis] — Audit agresif: centang PIN sinkron saat PIN dihapus ketikan
- `SettingsActivity` (watcher `pinInput`): ketikan <4 char menghapus hash + mematikan PIN tapi centang tetap menyala (menyesatkan sampai buka ulang) — kini centang ikut dimatikan programatis via guard `pinCentangProgram`.
- Audit seluruh area (21 file): receiver anti-spoof, PBKDF2 + lockout ganda, buffer log terkunci, zip-slip + allowlist + batas unzip, AES-GCM salt/IV acak, parser versi/IP/IPv6/TLS, referensi `R.*` + manifest — semua lolos, tak ada bug lain yang perlu perbaikan.

## [Belum rilis] — Settings persis TDM: bar atas, chip pil, ikon header
- `activity_settings`: bilah atas ramping diganti bar ala Toolbar TDM (primary solid setinggi action bar, panah kembali putih + judul putih); `settingsBack` jadi `ImageButton` 48dp.
- Lima chip navigasi (`navServer`..`navLog`) ke gaya pil TDM: `bg_chip` + `TasirinChip` 40dp teks 13sp tebal; warna `chip_bg`/`chip_pressed` adaptif siang-malam.
- Lima header seksi: tinggi 48dp jadi 40dp + ikon 18dp tint aksen seksi (server/kunci/perkakas/kirim/daftar) ala header TDM. Chevron teks + lipat-buka tak berubah.

## [Belum rilis] — Top bar + titik-tiga persis TDM, tombol seragam 48dp
- `activity_main` (portrait + land): hero gradien diganti bar judul ala Toolbar TDM — primary solid setinggi action bar, tetap di atas, judul 20sp + subversi `vX · build N` putih, tanpa ikon app. Rantai D-pad dan ID kedua orientasi tetap sama persis.
- Titik-tiga diganti `ImageButton` 48dp berikon vektor `ic_more` putih (jalur TDM) + `PopupMenu` di bawahnya; status hijau hero saat berjalan dihapus (indikator jalan tetap di banner status).
- `TasirinBtnPrimary`: tinggi 52dp jadi 48dp + teks 17sp jadi 16sp — semua gaya tombol kini se-TDM: satu tinggi 48dp, beda hanya isi/teks. Style `TasirinToolbarTitle`/`Subtitle` baru.

## [Belum rilis] — Tema seragam ikut Tasirin Download Manager
- Palet inti (`primary`/`accent`/`window_bg`/`text_*`/`status_*`) disamakan dengan TDM (`#1565C0`/`#F57C00`/`#F2F4F8`); token TDM (`line`, `text_hint`, `primary_container`) ditambah di siang + malam agar komponen bersama tampil sama.
- `themes.xml` (siang + malam): atribut `colorControlNormal`, `textColorPrimary/Secondary`, `colorBackground` ditambah ikut TDM.
- Tombol diseragamkan: `bg_btn_tv` jadi tonal M3 pil 20dp, `bg_btn_primary`/`bg_btn_stop` radius pil 20dp + fokus 2dp, `TasirinBtn` sekunder teks `primary`, `TasirinInput` fokus + hint ikut TDM.
- `styles.xml`: alias `AppButton`/`TvOutlinedButton`/`AppInput` agar layout bersama bisa dipakai di kedua aplikasi. Dukungan malam + APK kecil tanpa AppCompat tetap dipertahankan.

## [Belum rilis] — Popup titik tiga menempel + About khas TDM
- `MainActivity.showOverflowMenu`: `AlertDialog` tengah diganti `PopupMenu` berjangkar `overflowBtn` (`Gravity.END`) — menu Settings/Tentang kini muncul tepat di bawah tombol titik tiga seperti `Tasirin Download Manager`.
- `MainActivity.showAboutDialog`: dialog HTML datar diganti layout `dialog_about` khas TDM — ikon app, judul, versi app + binary + web-vault, 8 baris ikon-teks (developer Julius Rudi Tasirin), tombol Open GitHub + Check Update, tombol tutup, footer minSdk/targetSdk/build.
- Resource baru: `layout/dialog_about.xml` + `values/arrays.xml` (`about_icons`/`about_rows`), string `about_title`/`about_version`/`about_github`/`about_check_update`/`about_tech` (EN + ID).

## [Belum rilis] — PIN enteng di STB: iterasi 120rb jadi 30rb + antrean tak dibuang
- `PinCrypto`: hash PIN baru memakai 30rb iterasi (dulu 120rb) — sekali set/buka PIN di STB rampung ~1 detik. Brute-force tetap terkunci 5 menit tiap 5 gagal + salt acak, jadi keamanan praktis tak berubah.
- `PinCrypto`: hash lama 120rb tetap diterima sekali lalu dinormalisasi ke 30rb saat login sukses (`perluUpgradeHash`: `iter != standar`) — user lama tak terkunci dan buka PIN berikutnya jadi cepat.
- `SettingsActivity.onDestroy`: `pinExec.shutdownNow()` + `cancel()` diganti `shutdown()` — hash toggle yang antre tepat sebelum keluar Settings tetap tersimpan (sumber "centang balik uncheck tanpa pesan").


## [Belum rilis] — PIN ringan di STB: debounce hash + kunci UI saat proses
- `SettingsActivity`: hash PBKDF2 120rb tiap ketikan diganti debounce 800 ms — hash berat baru dikirim ke worker sesudah user berhenti mengetik (satu hash per jeda, bukan satu per ketikan yang menumpuk di CPU lemah STB).
- `SettingsActivity`: centang PIN membatalkan jadwal debounce + mengunci checkbox/field selama hash berjalan — ketuk/ketik ulang tak lagi menumpuk antrean PBKDF2 (sumber status "proses mulu").

## [Belum rilis] — Perbaikan audit: runBusy anti-diam, normalisasi update_version, sidik impor unik
- `SettingsActivity.runBusy`: tangkap `Throwable` + toast/log — task yang melempar tak terduga tak lagi membunuh thread diam-diam dengan UI kembali idle tanpa pesan.
- `TgBackup.applyPrefsFromJson`: `update_version` impor dinormalisasi (`normVersion`, sampah = kosong) selaras kuncian versi — penanda versi rusak tak lagi mengendap di prefs.
- `SettingsActivity.sidikBerkas`: fallback unik per path+waktu bila hash gagal — berkas gagal-baca tak lagi berbagi satu counter `""` sehingga tak saling memblokir impor.

## [Belum rilis] — Perbaikan audit: cap verifikasi DB, offset bot at-most-once, restart ikut IP baru
- `TgBackup.verifikasiIsiDbZip`: ekstraksi `db.sqlite3` kini dibatasi `BATAS_UNZIP_RESTORE` — zip backup jahat/korup dengan DB raksasa dibatalkan + tmp dibersihkan, bukan memenuhi disk/OOM STB 1 GB.
- `TgBot.pollOnce`: offset dikunci (`commit()`) SEBELUM eksekusi perintah sensitif (berbahaya/status/log/crashlog) — crash di tengah handle tak lagi memutar ulang `/restore`/`/backup` saat hidup lagi.
- `ServerService.peringatkanIpBerubah`: IP LAN baru yang stabil 2x health tick memicu `scheduleRestart()` agar `DOMAIN` ikut IP baru; flapping DHCP tak memicu loop (kandidat+hitung), peringatan Telegram sekali per IP tetap.
- `ServerService.onCreate`: guard recreate ditambah `running` eksplisit agar monitoring tak bangun tanpa server yang diminta jalan.
- `AlarmReceiver.simpanThrottle`: dokumentasikan alasan `apply()` (main thread, anti-ANR) — throttle hilang sesaat hanya berarti satu bangun ekstra.

## [Belum rilis] — Perbaikan audit: enctmp tak dibagikan, probe prefs, kunci clip sembuh-sendiri
- `FileShareProvider.berkasSementara`: `app-config-enctmp-*.json` (plaintext sementara export terenkripsi) tak lagi lolos sebagai shareable via pola `app-config-*`.
- `TgBackup.restoreFromZip`: probe `app-config.json` memakai `normalisasiEntriZip` seperti loop ekstraksi + restore UI — zip ber-folder pembungkus tetap menerapkan prefs.
- `TgBackup.KUNCI_STRING/KUNCI_LONG`: tambah `clip_hash`/`clip_kedaluwarsa` agar tipe korup sembuh-sendiri via `healkanStringPrefs`, bukan melempar tiap dibaca `LogActivity`.

## [Belum rilis] — Perbaikan audit: restart guard, TOCTOU binary manual, tmp restore Telegram
- `ServerService`: restart terjadwal tak lagi menyalakan server yang sudah di-Stop; alias tombol dihapus; komentar cap trust diluruskan.
- `ServerService.ensureBinary`: tutup TOCTOU binary manual — hash SHA-256 dihitung dari salinan internal sesudah disalin, bukan dari file di folder data yang bisa ditukar di tengah jalan.
- `TgBot.doRestore` / UI restore: file sementara restore Telegram dan UI dibuat unik per unduhan agar tak berebut; balapan dialog restore ditutup.

## [Belum rilis] — Perbaikan audit: baca prefs tahan-korup, hook shim, changelog
- `ServerService.ensureBinary`: `KEY_BIN_DL_GAGAL_AT` dibaca via `amanLong` (selaras `AutoUpdate`) — prefs korup bertipe String tak lagi jatuh ke 0 lalu mengunduh ulang tiap Start (bakar kuota) tanpa sembuh sendiri.
- `shim/getrandom_shim.c`: hook `syscall()` hanya membaca argumen yang dipakai (jalur getrandom cukup buf+len) + penerusan diselesaikan di constructor — jendela deadlock reentransi `pthread_once`/`dlsym` di dalam interceptor tertutup.
- `CHANGELOG.md`: entri audit sebelumnya yang tertinggal dilengkapi.

## [Belum rilis] — Perbaikan audit agresif: symlink fail-closed, korban dekrip, tmp unik, cap trust, overflow desimal
- `Updater.tautanSimbol`: fail-closed (path tak bisa dikanoniskan dianggap tautan) — `deleteRecursive` tak lagi merekursi ke target luar folder.
- `TgBackup.encryptFile`/`decryptFile`: gagal tak lagi menghapus file baik milik sebelumnya; tmp dekrip unik per panggilan (dekrip paralel tak berebut); `sapuDecTmpBasi` cek `isFile` + hapus tunggal.
- `HttpsCompat.capOverride`: cap selalu hash isi (bukan stat mtime+ukuran saja) — ganti isi se-msinar+seukuran tak lolos sebagai cache basi.
- `TgBackup.satuDesimal`: bagi-dulu anti-overflow + carry desimal 10; uji regresi baru (korban dekrip/enkrip, carry, nilai raksasa).

## [Belum rilis] — Perbaikan audit: health statis, wakelock ganda, rahasia alarm awet
- `ServerService`: `healthFails`, `configSajaBeruntun`, `healthTcpLolos`, `healthBerjalan` dijadikan statis — hitungan health tak lagi di-reset recreate sistem sehingga restart tertunda berkurang.
- `ServerService`: `wakeLock` dijadikan statis + akuisisi/pelepasan dikunci `ServerService.class` — recreate transien tak lagi memegang dua kunci 12 jam (bocor baterai).
- `TgBackup.rahasiaAlarm`: tulis rahasia memakai `commit()` sinkron — rahasia wajib awet sebelum alarm terjadwal memakainya agar backup tak ditolak lalu hilang.

## [Belum rilis] — Perbaikan audit: PIN whitespace, chat nol-depan, digest, start+backup
- `TgBot.pisahkanPin`: pemisah PIN kata-terakhir kini whitespace apa pun (tab/newline), bukan `lastIndexOf(' ')` — "YA<TAB>123456" dikenali + sisa dinormalisasi agar cocok `isRestoreConfirm`; uji baru `pisahkanPinPemisahWhitespaceApapun`.
- `Util.cocokChat`: config/username ber-nol-depan ("0123") ditolak fail-closed agar tak cocok longgar dengan ID 123; uji baru `cocokChatTolakNolDepan`.
- `UtilTest.cocokChatUsername`: anotasi `@Test` yang hilang dipasang kembali — uji pencocokan username selama ini mati tak pernah jalan.
- `MainActivity` (Download & Start): `ServerService.start` + `maybeAutoBackup` dipindah ke sesudah unduh di worker — backup tak lagi dilewati diam-diam saat tunggu-DB 30 dtk hangus selama unduh 35 MB.
- `build-binary.yml`: digest web-vault divalidasi ketat (`sha256:*`, baris pertama) agar gagal lantang saat format upstream berubah.

## [Belum rilis] — Perbaikan audit: receiver, argumen bot, checksum, throttle, resConfigs
- `TgBot.ambilArgumen` (baru, murni): argumen perintah dipisah pada whitespace apa pun (spasi/tab), bukan `indexOf(' ')` — PIN sesudah tab tak lagi ditolak misterius; dipakai `handleCommand`; uji baru `ambilArgumen_pisahWhitespaceApapun`.
- `TgBotReceiver`: lubang kompat "alarm pra-rahasia diizinkan sekali" ditutup — intent tanpa rahasia kini ditolak + alarm langsung dijadwalkan ulang ber-rahasia (migrasi otomatis tick berikut); galat prefs fail-closed.
- `Updater.pindaiHexChecksum`: belah `=` juga agar format checksum tanpa spasi (`SHA256(f)=<hex>`) dikenali, bukan abort; uji baru `pindaiChecksumDukungTanpaSpasi`.
- `AlarmReceiver`/`BootReceiver`: tolak-throttle ikut menggeser jendela (spam beruntun tak lolos tiap 60 dtk); sekali-lolos pasca-reboot tak berubah.
- `PinCrypto.perluUpgradeHash`: hardcode `120000` diganti konstanta `ITERATIONS` anti-drift.
- `app/build.gradle.kts`: buang `resConfigs "id"` yang mati (qualifier Indonesia = `in`).

## [Belum rilis] — Perbaikan audit: PIN Telegram di grup + clipboard
- `TgBot.hapusPesanPerintah`: guard `chatId <= 0` jadi `== 0` — pesan ber-PIN di grup (ID negatif) kini ikut dihapus dari riwayat, bukan dibiarkan nangkring untuk semua anggota.
- `Util.chatPerluAnggapGrup` (baru) dipakai `TgBot.chatResmiAdalahGrup` + peringatan grup: config chat username/tak-terurai fail-closed diperlakukan seperti grup (selalu butuh PIN) + log anjuran ganti ke ID numerik; `chatAdalahGrup` tak berubah. Uji baru `chatPerluAnggapGrupFailClosedUntukUsername`.
- `LogActivity.salinBersihOtomatis`: kembalikan boolean + guard Looper null/exception di dua titik Handler (salin + pasang-ulang timer) — salin yang berhasil tak lagi crash di proses awal; timer tetap best-effort via penanda prefs.
- `MainActivity.copyShownUrl`/`SettingsActivity.salinTeks`: toast sukses hanya bila salin benar masuk clipboard, gagal tampil peringatan (tanpa string baru).
- Throttle alarm/boot selepas reboot dinilai by-design tanpa patch: sekali-lolos pasca-reboot wajib ada agar backup sah pasca-boot tak terkunci permanen (elapsed reset), dan jalur alarm ber-rahasia + dedup harian menutup spoof berulang.

## [Belum rilis] — Perbaikan audit agresif: clipboard, kill basi, throttle, wall-clock
- `LogActivity.bersihkanBilaIsiKita`: finally memakai sidik asal (bukan baca ulang prefs) — timer basi tak lagi menghapus penanda salinan baru sehingga auto-hapus 30 dtk tetap jalan.
- `ServerService.killStaleVaultwarden`: verifikasi ulang PID + cmdline tepat sebelum bunuh — server fresh dari Start konkuren tak ikut terbunuh.
- `AlarmReceiver`/`BootReceiver`: penanda throttle disimpan di prefs (`alarm_throttle_elapsed`, `boot_throttle_elapsed`) agar tahan mati proses; uji baru `throttleTersimpanMenutupResetProses`.
- `TgBot.catatWall`/`muatWallMaks`: baca-ubah-tulis di balik `KUNCI_WALL` agar maksimum wall-clock tak hilang saat poll tumpang tindih.

## [Belum rilis] — Bahasa default Inggris + README Rusia
- `README.md` kini Inggris (default); Indonesia pindah ke `README.id.md`; baru `README.ru.md` (Rusia). Ketiganya sinkron fitur.
- Bahasa default aplikasi kini Inggris: `values/strings.xml` Inggris, Indonesia di `values-in/strings.xml`; teks UI hardcode di activity ikut Inggris (log diagnostik + pesan bot Telegram tetap Indonesia).
- Deskripsi repo GitHub diganti Inggris.

## [Belum rilis] — Halaman login PIN layar penuh
- `PinActivity` baru (pengganti popup dialog): brand + kolom PIN + tombol Buka/Keluar, ramah D-pad, portrait + landscape, `FLAG_SECURE`.
- `MainActivity`/`SettingsActivity`: kunci PIN membuka halaman login via `startActivityForResult`; batal/Back = keluar (finish) agar kunci tak bisa dilewat; grace + lockout + upgrade hash tetap via `PinGate`/`PinCrypto`.
- Uji baru: `PinActivityTest.teksSisaKunci` (ceiling menit lockout).

## [Belum rilis] — Perbaikan audit agresif: lock log, symlink, versi, sertifikat
- `LogActivity.shareLog/copyLog`: penyamaran token pindah ke luar `synchronized(logBuffer)` — belasan regex di atas 300 KB tak lagi menahan thread server dan memicu ANR.
- `FileShareProvider.adaSymlinkInduk`: fail-closed bila `lstat` gagal di perangkat (sebelumnya dianggap bersih); stub android.jar JVM (`adaStubAndroid`) dikecualikan agar unit test CI hijau.
- `Updater.normVersion`: tag aneh (`../../x`, slash, spasi) ditolak null agar tak ditempel mentah ke URL asset; pemanggil sudah null-aman.
- `TlsCert.sisaMs`: baca ulang sekali selang 100 ms bila -1 agar jendela tukar atomik tak dikira cert rusak.
- Uji baru: `normVersion_tolakTagAneh`, `symlinkIndukBersihDiJvm`.

## [Belum rilis] — Perbaikan audit agresif: restart, unduh, UI
- `ServerService.healthFail`: tunggu proses mati sinkron sebelum menjadwalkan restart (restart 2 dtk yang menyala saat proses lama masih sekarat membuat restart hilang diam-diam) + rem loop `recordRestart` di jalur health (sebelumnya restart jalan selamanya + spam Telegram tiap siklus bila DB rusak permanen).
- `ServerService`: rotasi log sebelum tulis (rename gagal tak lagi memangkas tulisan baru); cache PID RSS divalidasi ulang cmdline ketat; komentar `isPortBusy` diluruskan.
- `Updater`: instal web-vault via redirect mencap versi dari `vw-version.json` (tak lagi unduh ulang 35 MB tiap cek).
- `MainActivity`/`SettingsActivity`: toast update sekali per versi; "Unduh & Start" yang gagal tak auto-start; Settings pakai application context; flag start susulan dihapus hanya bila server benar-benar jalan.
- Uji baru: `hitungRestartBaru_jendelaLimaMenit`, `cmdlineServer_ketatBukanSmokeTest`, `capWvRedirectDariJson`, `tawarkanBaruSekaliPerVersi`.

## [Belum rilis] — Perbaikan: unduh binary berulang tiap cek
- `Updater.downloadBinaryInner`: fail-fast shim getrandom SEBELUM mengunduh binary ~20 MB di kernel lama (dulu binary diunduh dulu lalu gagal uji asap `--version` karena shim hilang, file tmp dibuang, cek berikut mengunduh lagi dari nol).
- `AutoUpdate.cek`: cooldown 6 jam berbagi cap `bin_dl_gagal_at` bila cache binary masih ada — sukses membersihkan cap, gagal mencatat cap, dan kegagalan tak lagi memicu notifikasi "tersedia" berulang (notifikasi sekali per versi tetap di jalur non-otomatis).
- `ServerService.bolehCobaUnduhLagi`: tahan reboot (cap lebih besar dari waktu kini dianggap basi agar unduhan tak tertahan 6 jam).
- `SettingsActivity` reset binary ikut membuang cap gagal agar percobaan berikut langsung jalan.
- Uji baru: `unduhBolehDicobaBilaJamMundur`, `AutoUpdateTest` (lewati bila baru gagal + cache ada, tetap coba bila tanpa cache/belum gagal/jeda lampau).

## [Belum rilis] — Perbaikan: unduh binary berulang tanpa reset
- `Updater.tryUpdateVersi`: nilai versi file (`versiFileBinary`) sebelum mengunduh ulang — binary yang sudah terunduh tapi belum dipakai (server jalan versi lama) tak lagi diunduh tiap cek; bila server masih jalan versi lama, pesan membawa `[bin-updated]` agar auto-restart tetap jalan. Versi eksplisit/kuncian dilewati bila file sudah persis (`unduhBolehDilewati`); downgrade tetap jalan bila file beda.
- `ServerService.ensureBinary`: binary manual dari folder data ikut mencatat `update_version` agar tak dianggap basi; unduhan perbaikan yang gagal di-throttle 6 jam bila cache valid masih ada (`bin_dl_gagal_at`, tak ikut export config) agar tiap Start tak membakar kuota.
- Uji baru: `unduhDilewatiBilaBerkasSudahTerbaru`, `unduhJalanBilaBerkasTertinggal`, `unduhPaksaDilewatiHanyaBilaBerkasCocok`, `unduhBolehDicobaBilaBelumPernahGagal`, `unduhDitundaBilaBaruGagal`.

## [Belum rilis] — Perbaikan audit: clipboard, PIN, port, susulan start
- `LogActivity`/`SettingsActivity`/`MainActivity`: clipboard satu pintu tahan mati proses (`salinBersihOtomatis` + sidik/kedaluwarsa di prefs, dipasang ulang saat activity dibuka) agar token tak mengendap bila app dibunuh dalam 30 dtk.
- `TgBot`/`MainActivity`/`SettingsActivity`: upgrade hash PIN baca-ulang sebelum tulis + verifikasi lawan hash segar agar PIN lama tak lolos/menimpa hash baru.
- `SettingsActivity`/`MainActivity`: salin URL selalu pakai port ternormalisasi; port privileged (<1024) disembuhkan ke default saat simpan agar prefs/UI/server sepakat.
- `MainActivity`: flag susulan auto-start hanya dihapus bila start sukses agar tak hangus sia-sia.

## [Belum rilis] — Perbaikan audit: port privileged
- `SettingsActivity.sanitizePortPref`: terima hanya 1024-65535 selaras `normalisasiPort` service dan pesan MainActivity; port privileged hasil impor dikembalikan ke bawaan dengan log jelas (plus buang catch mati).

## [Belum rilis] — Perbaikan audit: nama file emoji
- `TgBackup.sanitasiNamaFile`: titik potong 120 char digeser ke batas code-point agar nama ber-emoji tak terbelah jadi lone surrogate di header multipart; uji `sanitasiNamaFile_takBelahEmoji` baru.

## [Belum rilis] — Perbaikan build: impor assertEquals test log
- `LogActivityTest`: tambah `import static assertEquals` yang hilang sejak test `sisaClipMs` ditambahkan sehingga kompilasi unit test CI gagal (`cannot find symbol`).

## [Belum rilis] — Perbaikan audit: pin beta, PIN ganda, impor, komentar TTL
- `ServerService.cacheSesuaiPin`: sufiks prerelease wajib sama (`1.37.3-beta` beda dengan `1.37.3`) agar selaras jalur unduh yang menolak beta lantang; uji baru.
- `TgBot.pisahkanPin`: kemunculan `PIN:` terakhir yang dipakai agar tempelan `PIN:lama ... PIN:baru` memakai yang baru; uji baru.
- `SettingsActivity`: dialog password impor dibatasi 5x salah (selaras lockout PIN) lalu batal + hapus sisa file.
- `SettingsActivity`: betulkan komentar TTL sapu export plaintext 5 menit menjadi 2 menit (sesuai `EXPORT_PLAIN_TTL_MS`).

## [Belum rilis] — Perbaikan audit: leaf 397 hari, DNS huruf besar
- `TlsCert`: umur leaf baru 397 hari (bukan 825 hari) agar lolos batas 398 hari Chrome/Android modern; leaf warisan 825 hari otomatis diregen via `leafTerlaluLama`; uji `leafTerlaluLamaBatas397Hari` baru.
- `TlsCert.namaDnsValid`: terima huruf besar (case-insensitive) agar konsisten dengan `daftarDns` yang sudah lower-case; uji tambah `Vault.Lan`/`VAULT`.

## [Belum rilis] — Perbaikan audit: clipboard sidik, wrap PIN, alarm, resume, loopback
- `MainActivity`/`SettingsActivity`: banding bersih clipboard via sidik SHA-256 (`LogActivity.sidikClip`) agar lambda 30 dtk tak menahan plaintext di heap.
- `ServerService`/`SettingsActivity`: `PinGate.kuatkanHashDini` juga dari jalur service + settings agar pemakaian bot-only/settings-only tak menyisakan SHA-256 tanpa salt.
- `AlarmReceiver`/`TgBackup`: alarm milik app ditandai action (`ACTION_HARIAN`/`ACTION_TUNDA`, bare intent lawas tetap diterima transisi) + throttle 60 dtk di jalur umum agar spam explicit-intent tak membangunkan perangkat beruntun; uji `AlarmReceiverTest` baru.
- `Updater`: resume lanjut hanya bila temp `isFile`; direktori nyasar dihapus best-effort agar unduhan tak gagal permanen.
- `MainActivity`/`SettingsActivity`: salin URL loopback ditandai jelas agar tak dikira URL LAN.

## [Belum rilis] — Perbaikan audit: receiver, update, export, samarkan, izin
- `AndroidManifest AlarmReceiver`: `exported=true` agar `DATE_CHANGED`/`TIME_SET`/`TIMEZONE_CHANGED` sampai di Android 12+; alarm eksplisit tetap aman karena `mulaiBackup` cek auto + token/chat.
- `BootReceiver`: `MY_PACKAGE_REPLACED` tak lagi auto-start server; hanya BOOT yang boleh start, selepas update cukup jadwalkan ulang alarm/bot.
- `LogExport` legacy: cek kanonis `Download/nama` sesudah `createNewFile` agar symlink yang ditukar di jeda cek-vs-tulis menggagalkan tulis.
- `LogActivity`: tambah `POLA_TOKEN_JSON_SQ` untuk dump kutip tunggal; auto-bersih clipboard log 60 dtk menjadi 30 dtk.
- `SettingsActivity`: auto-bersih clipboard token 60 dtk menjadi 30 dtk.
- `StoragePerm.butuhIzinEksternal`: cocok prefix pakai separator agar `/sdcard2evil` tak diminta izin sia-sia; uji baru.

## [Belum rilis] — Perbaikan audit: titik-titik, PIN bot, TTL export, clipboard
- `ServerService.dataDirAman`: tolak segmen `.` (`/sdcard/./vaultwarden`) selain `..` agar normalisasi path tak bisa mengelabui cek; uji `dataDirAmanTolakTraversalDanSistem` ditambah.
- `TgBot.authDangerous`: PIN lebih pendek dari 4 karakter ditolak dini tanpa PBKDF2 120k agar spam bot tak DoS CPU STB dan tak membakar lockout sia-sia.
- `SettingsActivity`: TTL file export plaintext `app-config-*.json` 5 menit menjadi 2 menit agar jendela baca via URI grant lebih sempit.
- `MainActivity`: auto-bersih clipboard URL 60 dtk menjadi 30 dtk agar tak lama nangkring di riwayat clipboard.
- `MainActivity`/`SettingsActivity`: pembersih PIN kini `getText().clear()` dulu baru `setText("")` agar buffer `Editable` tak mengendap di hierarki view.

## [Belum rilis] — Perbaikan audit: /status wajib PIN, batas 20 MB restore, kunci data bersama
- `TgBot /status`: wajib PIN bila PIN aktif seperti `/log`/`crashlog` (memuat folder data, versi binary/web-vault, waktu backup, URL LAN); pesan `/status <PIN>` yang lolos ikut dihapus dari riwayat; teks `/help` + `README.md`/`README.en.md` diselaraskan.
- `TgBackup`: unduhan restore via Bot API dibatasi 20 MB (`bolehUnduhUlangTelegram`, dipakai `getFilePath`) — backup lebih besar ditolak lantang dengan arahan restore manual; pesan sukses backup >20 MB membawa peringatan yang sama. Uji baru: `bolehUnduhUlangTelegram_batas20MB`.
- `TgBackup`: satu kunci bersama backup + restore (`TUGAS_DATA_JALAN` via `kunciBackup`/`kunciRestore`) agar backup tak menangkap DB tengah-restore lintas UI/bot/jadwal. Uji baru: `kunciBackupDanRestoreSalingMengesampingkan`.
- `TgBot /help`: catatan bahwa pesan `/help` pertama bisa dihapus sebagai uji izin hapus.
- `MainActivity`: pesan port diselaraskan ke 1024-65535 seperti `normalisasiPort` service.

## [Belum rilis] — Perbaikan audit: /log wajib PIN, peringatan hapus gagal, cermin PIN dibuang
- `TgBot /log`: wajib PIN bila PIN aktif, sama seperti `/crashlog` (keduanya memuat path folder data, port, versi binary, URL LAN); pesan `/log <PIN>` yang lolos ikut dihapus dari riwayat (`pesanPinWajibHapus`); teks `/help` + `README.md`/`README.en.md` diselaraskan.
- `TgBot.hapusPesanPerintah`: kini kembalikan boolean (cek `ok:true` + `tolakRedirectTelegram`); gagal hapus tak lagi diam-diam — kemampuan hapus diuji sekali saat `/help` (prob hapus pesan itu sendiri) dan pemilik diperingatkan satu kali (`tg_hapus_warn`) agar PIN yang nangkring di cloud dihapus manual; lolos sekali ditandai (`tg_hapus_ok`). Kedua kunci tak ikut export config.
- `MainActivity`/`SettingsActivity`: field cermin `unlocked`/`unlockAt` dihapus total — satu-satunya sumber grace adalah `PinGate`; `pauseStamp` diagnostik dipertahankan.

## [Belum rilis] — Perbaikan audit: PIN memori, TLS 1.1, oracle callback, race upgrade hash
- `MainActivity`/`SettingsActivity`: isi PIN dibersihkan dari `EditText` saat hasil/dismiss agar tak mengendap di hierarki view; tulis upgrade hash baca ulang dulu agar ganti PIN konkuren tak tertimpa hash lama.
- `HttpsCompat`: hanya aktifkan `TLSv1.3`/`TLSv1.2` (buang `TLSv1.1` usang; GitHub wajib 1.2+).
- `TgBot.tanganiCallback`: selalu `jawabCallback` walau chat tak resmi agar spinner tak jadi oracle resmi/tidak.
## [Belum rilis] — Perbaikan audit: checksum multi-asset, leaf 825 hari, port dual-stack, exact alarm, export, symlink induk, DB plaintext
- `Updater.fetchChecksum`: cap 20 → 200 baris x 4 KB agar checksum multi-asset tak abort permanen; baris tanpa newline dipotong anti OOM STB 1 GB.
- `TlsCert`: leaf baru 825 hari (bukan 5 tahun) + `leafTerlaluLama` meregen leaf warisan kepanjangan agar klien modern tak tolak.
- `ServerService.isPortBusy`: cek `0.0.0.0` dan `::` (sibuk bila salah satu terpakai); peringatan DB plaintext di storage bersama (PIN hanya kunci UI/bot).
- `TgBackup.schedule`: log peringatan bila exact alarm ditolak agar backup tak meleset diam-diam.
- `SettingsActivity`: TTL plaintext 10 → 5 menit + retensi 10 export `.json.enc` terbaru agar `backups/` tak penuh.
- `FileShareProvider.openFile`: tolak bila rantai induk bersymlink (`Os.lstat` + `S_ISLNK`) untuk sempitkan TOCTOU tukar-symlink.
## [Belum rilis] — Bar unduh ganda fungsi loading
- `SettingsActivity`: `unduhBar` selain jadi progress download (determinate) kini jadi indikator loading berjalan (indeterminate) saat sibuk tanpa persen — total unduhan tak diketahui maupun kerja non-unduh (backup/restore). Chip status tetap menunjukkan teks aktivitas.

## [Belum rilis] — Perbaikan audit: backup otomatis, PIN versi, health DB rusak
- `TgBackup.backupOtomatis` baru: backup otomatis (jadwal/saat Start/susulan boot) wajib terenkripsi — tanpa password backup langsung ditolak fail-fast dengan arahan isi password, bukan mengunggah database vault plaintext ke cloud Telegram. Backup manual (tombol/`/backup`) tetap bisa tanpa enkripsi dengan peringatan. Uji baru: `backupOtomatisWajibPassword`.
- `TgBot.pisahkanPin`: kata terakhir bentuk versi (`1.32.0`/`v1.32.0`) bukan PIN — sebelumnya argumen versi di posisi akhir dimakan sebagai PIN salah dan membakar 1x lockout. Uji baru: `pisahkanPinVersiBukanPin`.
- `ServerService`: episode beruntun `/alive` 5xx + `/api/config` 200 (`aliveRusakTapiConfigSehat`, ambang 10x tick) kini dianggap gantung lalu restart — sebelumnya DB rusak tak pernah pulih sendiri karena selalu dinilai sehat. 500 sesaat (backup/migrasi) tetap aman. Uji baru: `configSajaSehatHanyaSaatAlive5xx`.

## [Belum rilis] — Perbaikan audit: recreate service, restart, TOCTOU provider, clipboard
- `ServerService.onDestroy`: tak lagi mengosongkan `autoRestart`/`healthActive` statis; `onCreate` memasang ulang health-check/wakelock/restart bila server masih diminta jalan agar recreate transien tanpa intent baru tak menghentikan monitoring diam-diam.
- `ServerService` jalur `ACTION_RESTART`: start susulan lewat konteks aplikasi + aksi `START` (instance hidup yang mengeksekusi), bukan `mainHandler` milik instance penerima yang bisa mati sebelum runnable jalan.
- `FileShareProvider.openFile`: cek ulang kanonis + `isShareable` tepat sebelum open agar symlink induk yang ditukar di jeda cek-vs-buka menggagalkan open (sempitkan jendela TOCTOU).
- `LogActivity`: penanda sidik + kedaluwarsa salinan clipboard di prefs; penghapus 60 dtk selamat dari mati proses (buka berikutnya memasang ulang sisa timer atau membersihkan sisa basi). Uji baru: `sidikClip_konsistenDanBeda`, `sidikClip_nullKosong`.

## [Belum rilis] — Perbaikan audit minor: pending export, predikat stale, leak activity, entri zip, boundary
- `LogExport`: URI pending MediaStore dilacak di luar `try` agar lempar di jeda insert-vs-tulis ikut dibersihkan (anti orphan tak terlihat di Download).
- `ServerService.bolehBunuhBasi`: predikat ketat `/bin/vaultwarden-` (bukan substring longgar) agar exec se-UID lain tak ikut terbunuh.
- `MainActivity`: unduh web-vault 35 MB pakai app context + guard `isFinishing`/`isDestroyed` agar tekan back tak menahan Activity.
- `Updater.amanEntriZip`: tolak karakter kontrol/NUL/DEL di nama entri zip.
- `TgBackup`: boundary multipart fallback UUID bila `SecureRandom` melempar di STB tua.

## [Belum rilis] — Perbaikan audit: saran pin beta, pecah pesan, allowlist restore, komentar TTL
- `Updater`: kuncian prerelease (`1.37.3-beta`) yang asset-nya 404 kini menyarankan stabil padanan (`saranStabilPrerelease`) — tetap gagal lantang tanpa fallback diam-diam, tapi user tahu pin-nya yang bermasalah.
- `TgBackup.pecahPesan`: utamakan belah di akhir baris + ekor grapheme (combining/ZWJ/variant) ikut potongan ini; gabungan potongan tetap sama dengan asli. Uji: belah newline + `ekorGrapheme`.
- `TgBackup.bolehTulisRestore`: satu pintu allowlist restore (3 DB + tls + 4 TLS) dipakai jalur Telegram & UI Settings (ganti blok `dbPart`/`tlsPart` duplikat); ada uji.
- `SettingsActivity`: komentar sapu export plaintext diluruskan (<10 menit sesuai `EXPORT_PLAIN_TTL_MS`, bukan 24 jam).
## [Belum rilis] — Perbaikan audit: grace PIN bersama, PIN bot, watchdog versi, provider, pending UI
- `PinGate`: grace buka PIN kini satu sumber (`bukaKunciBersama`/`dalamGraceBersama`/`kapanBukaBersama`/`kunciBersama`) — `MainActivity`/`SettingsActivity` tak lagi salin-silang dua statis yang bisa drift; tanpa panggil balik antar-activity.
- `TgBot.pisahkanPin`: kutip mengapit diambil tepat (sisa sesudah kutip tutup kembali jadi argumen, mis. `PIN:"kunci saya" extra`); tanpa kutip tetap menelan sisa baris agar PIN ber-spasi lama tak rusak. `handleCommand` null-safe.
- `TgBot.authDangerous`: pesan butuh PIN menyebut PIN huruf wajib bentuk `PIN:ab12` agar tak lockout sia-sia.
- `ServerService.detectBinaryVersion`: watchdog 10 detik ikut dihentikan di jalur gagal (tanpa ini tiap Start gagal menyisakan thread daemon); kegagalan smoke test dicatat (`smokeGagalAt` + `smokeGagalBaruSaja` 60 dtk, ada uji).
- `FileShareProvider.query`: kursor kini `setNotificationUri` agar observer tahu bila file berubah.
- `MainActivity`/`SettingsActivity`: tulis `pendingVersion` dari worker `AutoUpdate` di-post ke UI thread (field sudah `volatile`).
- Uji baru: `pisahkanPinKutipAwalanSisaKembali`, `smokeGagal_throttleGagalBaru`.
## [Belum rilis] — Perbaikan audit: URL asset tahan spasi-kosong
- `Updater.binaryAssetUrl`/`shimAssetUrl`: versi spasi-kosong (`"   "`, `" v "`) kini jatuh ke `latest/download` alih-alih URL `.../v/...` yang pasti 404.

## [Belum rilis] — Perbaikan audit: URL asset anti-v ganda + TLSv1.3
- `Updater.binaryAssetUrl`/`shimAssetUrl`: kupas awalan `v`/`V` defensif — input berawalan `v` (mis. `v1.37.3`) sebelumnya jadi `vv1.37.3` lalu 404 terus.
- `HttpsCompat.PabrikTls12`: ikut nyalakan `TLSv1.3` bila didukung perangkat — sebelumnya daftar putih hanya 1.1/1.2 sehingga HP baru dipaksa turun ke 1.2 walau komentar menyebut 1.3 tetap dirundingkan.
- Uji baru: `assetUrl_awalanVTakGanda`.

## [Belum rilis] — Perbaikan audit: kunci prefs tertunda + cek sinkron README
- `TgBackup.KUNCI_BOOLEAN`: tambah `ServerService.KEY_START_TERTUNDA` (`auto_start_tertunda`) — sebelumnya satu-satunya kunci Boolean yang tak ikut penyembuhan tipe massal `healkanStringPrefs`, sehingga prefs korup bertipe String untuk kunci itu tak disembuhkan sekali jalan.
- `scripts/cek-cepat.sh`: cek #4 jumlah `##` `README.md` vs `README.en.md` wajib sama agar docs Indonesia/Inggris tak hanyut diam-diam.

## [Belum rilis] — Hemat CI berat: shim gabung job binary, tanpa JDK, toolchain sekali
- `build-binary.yml`: job `build-shim` dilebur ke `build-binary` (NDK ~500 MB cukup diunduh sekali); langkah `Set up JDK 17` dibuang (Rust tak membutuhkannya); `dtolnay/rust-toolchain@stable` diganti rustup minimal tanpa toolchain sehingga yang diunduh cukup versi pinned Vaultwarden.
- Job `deteksi` dibuang: workflow ini jarang jalan (hanya saat `shim/`/workflow berubah atau versi upstream baru), selalu bangun binary + shim sekaligus agar alur sederhana.
- `publish-binary` disederhanakan (2 job kebutuhan, unduh artifact tanpa syarat).

## [Belum rilis] — Pisah workflow build binary dan APK (hemat waktu CI)
- `.github/workflows/build-binary.yml` (baru, berat ~15 mnt): `resolve` → `deteksi` → `build-binary` (Rust `armeabi-v7a` + patch DNS/TLS Android) → `build-shim` → `publish-binary` (6 asset non-APK + `.sha256`). Pemicu: `push` yang menyentuh workflow/shim saja, `schedule` 6 jam, manual. Push shim-only memakai ulang binary + web-vault dari rilis (tanpa Docker ulang).
- `.github/workflows/build-apk.yml` (ringan ~4 mnt): `resolve` → `perlu` → `build-apk` (Gradle + lint + unit test) → upload hanya asset APK. Pemicu: `push` kode aplikasi, manual, dan `workflow_run` setelah binary sukses (dilewati bila APK sudah ada agar `versionCode` tak churn).
- Perbaikan aplikasi kini tak membangun ulang binary Rust; perbaikan shim/patch tak membangun ulang APK sia-sia. Rilis tetap 7 asset (6 binary + 1 APK).

## [Belum rilis] — Perbaikan audit: kunci privat lama + kunci restore bersama
- `ServerService.prepareTls`: kunci privat lama (`ca-key.pem`/`key.pem`) di storage publik dihapus best-effort setelah internal terbukti jadi — sebelumnya mengendap di `/sdcard` (FAT) dan bisa dibaca app berizin storage. Sertifikat publik dibiarkan untuk fallback; fallback folder lama tak ikut terhapus.
- `TgBackup.kunciRestore`/`lepasRestore`: kunci restore bersama UI + bot — restore UI (`restoreDatabase`/`restoreFromZip`) dan bot (`doRestore`) yang jalan bersamaan kini ditolak halus, bukan mengekstrak interleave ke folder data yang sama.
- Uji baru: `kunciRestoreSalingMengesampingkan`.
- Catatan: salinan `tls/*` di folder data memang diisi ulang tiap restore Telegram (sumber sinkron ke internal) — pembersihan hanya untuk migrasi satu-kali.

## [Belum rilis] — Perbaikan audit: reset binary saat jalan, RNG token, toast ganda
- `SettingsActivity.revertToBundled`: ditolak bila server masih berjalan (`running`/`isProcessAlive`) — sebelumnya binary dicabut dari bawah proses hidup dan penanda versi ikut terhapus.
- `SettingsActivity.buatTokenAcak`: wajib `SecureRandom` (fail-fast `IllegalArgumentException` untuk `Random` biasa) — token admin tak bisa lahir dari RNG lemah.
- `SettingsActivity.exportConfig`: `return` setelah toast fallback bila chooser bagi tak ada — sebelumnya toast ganda (`File tersimpan` + `Konfigurasi diekspor`).
- Uji: `buatTokenAcak` pakai `SecureRandom` + uji baru `buatTokenAcakTolakRandomLemah`.

## [Belum rilis] — Perbaikan audit: batas restart statis, checksum terikat asset, decrypt bersih, zip bersarang ditolak, retry backup, grace PIN fail-closed, otoritas provider
- `ServerService`: `restartAttempt` + kunci restart dijadikan statis — recreate service oleh sistem tak lagi me-reset batas 5x restart.
- `Updater.fetchChecksum`: baris checksum diutamakan yang menyebut nama asset (`namaAssetDariUrl` + overload `pindaiHexDariBaris(baris, nama)`) — file `.sha256` multi-asset tak tertukar; fallback hex pertama untuk checksum mentah.
- `Updater`: `bolehCobaLagiUnduh`/`buangParsialRusak` kenali galat campuran-resume (`checksum`, `content-range`, `416`, `corrupt`, `terpotong`) — parsial campuran dibuang lalu unduh ulang dari nol.
- `TgBackup.decryptToFile`: hapus `out` parsial juga di jalur VWB2 sebelum lempar (lapis dalam; lapis luar `decryptFile` sudah menghapus).
- `TgBackup.normalisasiEntriZip`: kupas maksimal satu folder pembungkus jinak — sarang `a/b/db.sqlite3` ditolak, `vaultwarden/tls/cert.pem` tetap diterima.
- `TgBackup.backupNowIsi`: bangun+verifikasi zip diulang sekali dengan checkpoint WAL segar bila verifikasi pertama gagal (server tetap jalan saat backup).
- `MainActivity`/`SettingsActivity`: grace PIN fail-closed (`delta >= 0`) di `pinBaruSajaDibuka` dan `maybeShowPinLock` — jangkar basi/negatif bukan grace.
- `FileShareProvider.openFile`: tolak otoritas asing seperti `query()`/`getType()` sebelum menyentuh filesystem.
- Uji baru: `namaAssetDariUrl_kupasVersiDanSha256`, `pindaiHexDariBaris_utamakanNamaAsset`, `bolehCobaLagiUnduh_checksumCampuranDiulangDariNol`, `normalisasiEntriZip_tolakBungkusBersarang`.
- Catatan audit: klaim awal soal plaintext dekrip tertinggal dan alarm exact dikoreksi — `decryptFile` sudah menghapus di lapis luar dan `schedule()` sudah punya fallback inexact; yang ditambah hanya lapis pertahanan.

## [Belum rilis] — Perbaikan audit: hapus pesan /crashlog ber-PIN dari riwayat
- `TgBot`: pesan ketik `/crashlog <PIN>` yang lolos kini dihapus dari riwayat chat (predikat baru `pesanPinWajibHapus` = perintah berbahaya + crashlog) — sebelumnya PIN tertinggal di riwayat dan bisa dipakai ulang pengintip. `perintahBerbahaya` tak diubah (tombol inline `/crashlog` tetap berperilaku sama); upaya gagal tetap dipertahankan sebagai bukti brute-force.
- Uji baru: `pesanPinWajibHapus_crashlogIkutDihapus`.

## [Belum rilis] — Perbaikan audit: parse file_size, checksum multi-baris, escape JSON, PIN multi-baris
- `TgBackup.fileSizeDariRespons`: parse `file_size` via `JSONObject` (selaras `parseFilePathTelegram`) — spasi/baris baru di sekitar titik-dua kini terbaca sehingga pre-check storage tak dilewati diam-diam; fallback `indexOf` dipertahankan untuk respons terpotong.
- `Updater.fetchChecksum`: pindai semua baris checksum (cap 20 baris, helper murni `pindaiHexDariBaris`) — file `.sha256` multi-baris/komentar tak lagi gagal di baris pertama; mismatch tetap fail-closed di caller.
- `TgBot.lolosJson`: escape `U+2028`/`U+2029` dan surrogate yatim (`\\uXXXX`), pasangan surrogate valid (emoji) dibiarkan utuh — payload tak lagi invalid untuk teks log aneh.
- `TgBot.pisahkanPin`: regex eksplisit `PIN:` pakai flag DOTALL — argumen multi-baris tak terpotong di baris pertama.
- Uji baru: `fileSizeDariRespons` varian spasi, `lolosJson_amankanUnicodeKhususDanSurrogate`, `pisahkanPin_dukungMultiBaris`, `pindaiHexDariBaris_lewatiBarisSampah`.
- Catatan audit: tmp impor config (`vwcfg-import-*.bin`) tidak bocor — `SettingsActivity`/`MainActivity`/`LogActivity` memakai `configChanges` (rotasi tak menghancurkan activity), dialog punya `onDismiss` penyapu, dan `sapuSisaImpor` jalan tiap `onResume`; tidak diubah.

## [Belum rilis] — Perbaikan audit: WAL yatim lokal, tukar TLS atomik, saran shim
- `SettingsActivity.restoreDatabase`: zip tanpa `db.sqlite3` kini buang `-wal`/`-shm` asing yang telanjur tertulis (selaras `TgBackup.restoreFromZip`) — sebelumnya WAL asing menempel ke DB lama lalu korup saat Start.
- `TlsCert.ensure`: tukar pasangan cert+key lewat cadangan `.cad` (`tukarPasanganAtomik`) — gagal rename kedua tak lagi mencampur cert baru + key lama, dan key CA tak lagi hilang (yang memaksa regen CA + install ulang di semua HP).
- `KernelCompat.saranShimGagal`: buang anjuran taruh shim manual ke folder data (tak ada jalur kode yang membacanya) — shim diunduh otomatis via Cek Update.
- Uji baru: `tukarPasanganAtomik_pasangBaruBersihCadangan`.

## [Belum rilis] — Perbaikan audit: restore WAL yatim, salin pengaman, parse getFile, cap entri, folder samaran
- `TgBackup.restoreFromZip`: tolak tegas zip berisi `-wal`/`-shm` tanpa `db.sqlite3` (flag `adaDb`) — sebelumnya WAL asing menempel ke DB lama lalu lolos cek SQLite karena DB lama memang valid.
- `TgBackup.copyFile`: `fsync` + verifikasi panjang salinan — salinan pengaman pra-restore/rollback parsial (storage penuh) kini dibuang dan restore digagalkan, bukan dipakai rollback.
- `TgBackup.getFilePath`: parse `file_path` via `JSONObject` (`parseFilePathTelegram`) — spasi di sekitar titik-dua dan escape `\/` yang sah kini terbaca; `indexOf` manual gagal untuk keduanya.
- `TgBackup.verifikasiZip`/`verifikasiIsiDbZip`: tolak zip dengan entri melebihi `BATAS_JUMLAH_ENTRI` agar central directory raksasa tak OOM di STB 1 GB.
- `ServerService.dataDirAman`: tolak karakter kontrol/format tak terlihat (RTL override U+202E dkk., zero-width, BOM) agar nama folder tak menipu di UI.
- Uji baru: `copyFile_menyalinUtuhDenganPanjangSama`, `dataDirAmanTolakKontrolDanFormatTakTerlihat`.
- Catatan audit: binary manual tanpa SHA sudah ditolak (`SHA-256 belum diisi`) — bukan bug; tidak diubah.

## [Belum rilis] — Perbaikan audit: race health, antrean penting, guard null
- `ServerService`: `cobaKode` kini kembalikan `HasilCoba` (kode + galat lokal), field statis `aliveErrTerakhir` dihapus — panggilan konkuren health-tick dan `/status` bot tak lagi tukar pesan error.
- `TgBackup`: `kirimPesan` merutekan kabar kritis (gagal/terkunci/berhenti/crash/restore/korup/darurat, tanpa keyboard) ke antrean prioritas `TG_PENTING_EXEC` via `pesanPenting()` agar tak terbuang antrean biasa saat penuh; uji baru `pesanPenting_utamakanKabarKritis`.
- `PinGate.catatHasil`: guard `ctx == null` agar tak NPE bila dipanggil tanpa konteks.
- `FileShareProvider.query`: tolak file tak-terbaca (`canRead`) selaras `openFile`.
- `Updater` (binary + shim): `mkdirs` folder binary yang gagal kini lempar `IOException` ramah (cek storage/izin).

## [Belum rilis] — Perbaikan audit: bocor tmp impor config
- `SettingsActivity.tanyaPasswordImpor`: tambah `setOnDismissListener` penyapu `vwcfg-import-*.bin` — tombol Back menutup dialog tanpa lewat Batal sehingga berkas terenkripsi mengendap di cache (jalur sukses/Batal sudah menghapus; no-op ganda, aman).

## [Belum rilis] — Redam warning lint ApplySharedPref
- `AlarmReceiver`/`ServerService`/`MainActivity`: tambah `@SuppressLint("ApplySharedPref")` (ikut pola `BootReceiver`/`TgBot`/`PinGate`) — `commit()` sinkron di sana disengaja agar flag tak hilang bila STB mati/kill tepat sesudah tulis; `apply()` async justru mengembalikan bug yang sudah diperbaiki.

## [Belum rilis] — Perbaikan build: duplikat @Test di 3 file uji
- `ServerServiceTest`/`TgBotTest`/`UpdaterTest`: buang baris ganda `@Test` + signature (`migrasiPort_…`, `pisahkanPinKataBiasaTakDimakan`, `tautanSimbol_…`) yang menggagalkan kompilasi uji sejak 44c3562 (`';' expected`).

## [Belum rilis] — Perbaikan audit: race cache factory loopback
- `ServerService`: `capCaAktif` sinkron + `loopbackSslFactory` hitung ulang cap di dalam kunci (cermin `HttpsCompat` commit 44c3562) agar factory basi tak menimpa factory segar saat `tls/ca.pem` regenerasi tepat ketika health-tick dan ping UI jalan bersamaan (sebelumnya: health HTTPS gagal palsu → restart beruntun).

## [Belum rilis] — Bersihkan kode mati dan duplikat
- Hapus `ServerService.barisKosong`, `TlsCert.masaBelumTiba`, `TgBot.parseChatId` yang mati di produksi (hanya dipakai uji sendiri) beserta ujinya; `daysLeft`/`sisaMs`/`cocokChat` tetap menutup kebutuhan.
- Gabung `deleteRecursive` duplikat: salinan `ServerService` dihapus, 5 call-site memakai milik bersama `Updater.deleteRecursive`.

## [Belum rilis] — Perbaikan audit: stempel unik, flag susulan boot
- `TgBackup.stempelUnik`: acak 16-bit jadi 48-bit agar export/staging sejawat dalam ms sama tak saling menimpa (tabrakan lama 1/65536, termasuk staging unik web-vault); komen format `backupTimestamp` diluruskan (`-SSS`).
- `TgBackupTest`: regex format stempel menyesuaikan 12 hex.
- `MainActivity`: hapus flag `KEY_BACKUP_TERTUNDA` pakai `commit()` sinkron agar kill tepat sesudahnya tak memicu backup susulan ganda.

## [Belum rilis] — Perbaikan audit: nama log, resume CDN, fsync binary, race trust, grace PIN
- `LogExport`: nama file acak 48-bit + `createNewFile` atomik agar ketuk ganda dalam ms sama tak saling timpa/hapus (acak 16-bit lama tabrakan 1/65536 di jalur legacy).
- `AlarmReceiver`: flag `KEY_BACKUP_TERTUNDA` pakai `commit()` sinkron seperti `BootReceiver`/offset bot agar tak hilang bila STB mati tepat setelah alarm.
- `Updater`: redirect lintas host me-reset offset resume dari nol (`hostBerubah`, samakan port bawaan skema) agar byte dua asset tak campur lalu gagal SHA + buang kuota.
- `ServerService.gantiAtomik`: `fsync` isi file sebelum `renameTo` (best-effort via `getFD().sync`, API 1) agar binary tak nol/terpotong bila STB mati tepat sesudah pasang (FAT).
- `HttpsCompat`: `capOverride` sinkron + hitung ulang cap di dalam kunci `socketFactory` agar factory basi tak menimpa factory segar milik thread lain.
- `MainActivity`/`SettingsActivity`: buang status buka PIN basi saat PIN mati agar PIN yang diaktifkan lagi dalam grace lama tak dianggap sudah dibuka tanpa entri baru.
- `ServerService.potongPesanGalat`: `maks<=0` kembalikan `""` (bukan pesan penuh) agar kontrak potong tak jebol ke log/Telegram.
- `TgBot.pisahkanPin`: token `PIN:` telanjang bukan PIN agar salah ketik tak menambah hitungan lockout sia-sia.
- Uji baru: `potongPesanGalat_batasNolKosong`, `pisahkanPinTelanjangBukanPin`, `hostBerubah_deteksiLintasHost`.

## [Belum rilis] — Perbaikan audit: redirect berport, hash PIN legasi, password impor, oracle getType
- `Util.hostGithubAman`: kupas `:port` dulu (seperti `hostTelegramAman`) agar redirect sah berport eksplisit (`//github.com:443/...`) tak ditolak; `kupasHostPort` kini tolak port non-angka (`host:abc` = null, bukan host telanjang).
- `PinCrypto` + `PinGate.kuatkanHashDini`: hash legasi SHA-256 tanpa salt dibungkus-dini ke `PBKDF2W$120k` ber-salt tiap buka app (tanpa menunggu login) sehingga prefs bocor tak lagi memberi hash yang retak dalam detik; login sukses menormalkan ke format standar via `perluUpgradeHash`.
- `SettingsActivity`: impor config terenkripsi yang gagal dibuka password perangkat kini tanya password asal file (dialog) tanpa mengubah password perangkat; berkas password lama tetap bisa diimpor.
- `FileShareProvider.getType`: cek `isShareable` dulu (null bila tak boleh) seperti `query()` agar tak jadi oracle ekstensi path privat.
- `TgBot /crashlog`: komentar docs-drift sudah diluruskan di commit sebelumnya, tak ada perubahan.

## [Belum rilis] — Perbaikan audit: TLS 1.2, union trust anchor, folder web-vault, komen crashlog, rate-limit resolve
- `HttpsCompat`: paksa TLSv1.2 di API 21/22 (konteks `TLS` bawaan hanya mengaktifkan TLSv1 di sana sehingga HTTPS GitHub yang wajib >=1.2 gagal di Android 5.0/5.1); konteks `TLSv1.2` dulu dengan fallback `TLS`, plus pembungkus factory yang menyalakan TLSv1.2/1.1 di tiap soket.
- `HttpsCompat`: trust anchor override kini union (bawaan + berkas segar), bukan ganti; override valid tapi tak lengkap tak lagi memutus rantai root lama sampai refresh berikut.
- `Updater.updateWebVaultInner`: validasi `dataDir` via `dataDirAman` + `dataDirKanonisAman` seperti jalur restore/`startServer` agar prefs utak-atik tak mengarahkan unduhan ke folder arbitrer.
- `TgBot.perintahBerbahaya`: perbaiki komentar yang masih tulis `/crashlog` cukup auth chat — kini wajib PIN bila PIN aktif (diperiksa sendiri di `handleCommand`).
- `build-apk.yml` (job `resolve`): fallback `curl` membawa `GITHUB_TOKEN` (limit 5000/jam, bukan 60/jam anonim) agar tak gagal spurios saat cron + push berdekatan.

## [Belum rilis] — Perbaikan bug: clipboard log, iterasi PIN, checksum BSD, folder web-vault, VPN kuota
- `LogActivity.copyLog`: auto-hapus clipboard 60 dtk seperti jalur salin lain.
- `PinCrypto.verify`: tolak iterasi di atas 120k sebelum derive agar prefs utak-atik tak memaksa PBKDF2 raksasa (stall STB).
- `Updater.pindaiHexChecksum`: pindai semua token baris agar format BSD (`SHA256 (f) = hex`) tetap lolos verifikasi.
- `Updater.updateWebVaultInner`: gagal `mkdirs`/folder tak writable digagalkan eksplisit lebih awal.
- `AutoUpdate.tanpaKuota`: fail-closed saat VPN di API 21/22 agar auto-unduh tak lewat kuota seluler.
- Izin baterai: sudah terhubung (`requestBatteryExemption`), tak ada perubahan.

## [Belum rilis] — Perbaikan bug: wakelock, folder data, redirect GitHub, clipboard, crashlog, TTL export
- `ServerService.jagaWakeLock`: wakelock 12 jam disegarkan tiap `healthTick` agar server >12 jam tak kena Doze.
- `ServerService.dataDirAman`: `/data/data|user` hanya milik `com.tasirin.vaultwardenhost`; prefix `/mnt/media_rw|runtime*` dan `/storage/self` ditolak.
- `Util.hostGithubAman`: hapus wildcard `*.githubusercontent.com`, hanya host rilis resmi agar redirect nakal tak bisa sajikan binary+sha palsu.
- `SettingsActivity`/`MainActivity`/`LogActivity`: salin clipboard otomatis dibersihkan 60 dtk.
- `TgBot /crashlog`: wajib PIN bila PIN aktif; teks bantuan ikut diperbarui.
- `LogActivity.samarkanLog`: `DOMAIN=` ikut disamarkan.
- `SettingsActivity.EXPORT_PLAIN_TTL_MS`: 24 jam jadi 10 menit agar plaintext tak mengendap.
- `AGENTS.md`: aturan #13 — jangan pantau build (`gh run watch/view`) kecuali disuruh.

## [Belum rilis] — Perbaikan audit: log fallback port, PIN tanpa activity leak, TLS atomik, guard provider
- `ServerService.startServer`: bila port mentah (<1024/invalid) jatuh ke default, catat log eksplisit agar user paham port 80/443 tak bisa dipakai tanpa root (sebelumnya pindah diam-diam).
- `MainActivity`/`SettingsActivity`: verifikasi PIN worker memakai `applicationContext` yang ditangkap sebelum thread agar tak menahan Activity (bocor memori saat rotate/destroy).
- `TlsCert.ensure`: tulis CA/leaf ke file `.baru` dulu, ganti lama hanya bila pengganti jadi; gagal generate tak lagi menghapus CA/leaf bagus yang masih ada. `resetTls` ikut membersihkan sisa `.baru`.
- `FileShareProvider.isShareable`: tolak eksplisit bila `canon`/`getContext()` null agar tak NPE.

## [Belum rilis] — Perbaikan audit: port privileged, PIN anti-ANR, data dir segar, log bot, izin export, alokasi alarm
- `ServerService.normalisasiPort` + `SettingsActivity.galatPort` + validasi start: hanya 1024-65535 yang valid; prefs lama berisi 80/443 disembuhkan ke default saat Start (dulu lolos lalu FATAL tiap Start).
- `PinGate`: `catatHasil()` otomatis dialihkan ke worker (`catatHasilAsync`) bila dipanggil dari UI thread agar `commit()` sinkron tak ANR; jaminan awet-sebelum-kill dipertahankan.
- `ServerService.dataDirBawaanSegar()`: fallback folder data dihitung ulang tiap dipakai (`amankanDataDir`, `startServer`); konstanta `DEFAULT_DATA_DIR` yang di-cache saat class-load bisa basi bila storage belum mount.
- `TgBot.pollOnce`: update gagal parse tak lagi hilang diam-diam — dihitung dan dicatat satu baris ringkas per poll (contoh <=120 char) agar tak banjir log saat diserang.
- `LogExport.simpanKeDownload`: lapis kedua cek izin tulis pra-29 di dalam fungsi (pemanggil `LogActivity` sudah cek dulu) agar gagal izin tak dikira galat I/O.
- `TgBackup.REQ_HARIAN` + `TgBot.REQ_POLL`: alokasi requestCode alarm didokumentasikan terpusat (0/3/101); nilai historis dipertahankan agar alarm lama tetap bisa dibatalkan.
- Unit test: port privileged ditolak (`ServerServiceTest`, `SettingsActivityTest`).

## [Belum rilis] — Banding versi toleran format, versionCode menit-epoch, MIME share, samaran token pendek
- `Updater.normVersion`: `trim()` dulu sebelum kupas `v` dan kupas metadata `+build` (selaras `normalisasiPinVersi`); `fallbackBaruSaja`, cek web-vault (`known`/`marker`), dan notifikasi `tg_notified_version` (`AutoUpdate`) pakai `versiCocok()`/`bandingVersi()` — beda tulis `v1.32.0` vs `1.32.0`/spasi/`+build` tak lagi memicu unduh ulang 15–35 MB tiap Start.
- `app/build.gradle.kts`: `versionCode` menit-epoch UTC (muat `int` sampai tahun 6055); dua push dalam jam sama tetap beda kode sehingga sideload tak diabaikan PackageManager (skema `yyyyMMddHH` lama tabrakan dalam sejam).
- `FileShareProvider.tipeMime`: `getType()` kembalikan MIME sesuai ekstensi (`.json`, `.txt`, `.pem/.crt/.cer`, `.zip`); tak dikenal tetap `octet-stream` agar app penerima bisa preview.
- `LogActivity`: ambang samaran token diturunkan (`bot\d+:` 6+, token mentah/URL 10+→6+/10+ konsisten) agar token pendek/test ikut tersamar; jam `12:30`/port `8088` tetap aman (syarat 6–12 digit).
- `ServerService.prepareTls`: komentar menegaskan SAN DNS sengaja kosong (fitur domain lokal dihapus, butuh DNS sendiri); `TlsCert.namaDnsValid` tetap dipakai sanitasi SAN internal, `daftarDns` util teruji untuk mendatang; `AGENTS.md` hapus `domain_lokal` dari daftar key aktif agar docs sinkron.

## [Belum rilis] — Perbaikan audit: health leak, port IPv6, kuncian 2-bagian, upgrade hash lemah, versionCode per-jam, lint sempit
- `ServerService.healthTick`: berhenti repost saat `autoRestart` mati dan `scheduleRestart` gagal 5x mematikan `healthActive` — sebelumnya polling tiap 2 menit selamanya walau server sudah berhenti.
- `ServerService.isPortBusy`: hanya cek IPv4 (`ROCKET_ADDRESS=0.0.0.0`); cek `::` memberi false-positive saat pendengar IPv6-only memakai port yang sama.
- `Updater.normalisasiPinVersi`: wajib 3 bagian `x.y.z`; kuncian `1.32` kini ditolak (dulu lolos lalu 404 `v1.32`) sehingga pin lama bertahan.
- `PinCrypto.perluUpgradeHash`: hash lama maupun PBKDF2 di bawah 120k di-upgrade otomatis sesudah verifikasi sukses (`MainActivity`/`SettingsActivity`/`TgBot`); sebelumnya hash 10k lolos selamanya.
- `app/build.gradle.kts`: `versionCode` jadi `yyyyMMddHH` agar dua build sehari beda kode (sideload kode sama diabaikan PackageManager).
- `app/build.gradle.kts` lint: hapus supresi `TrustAllX509TrustManager` (tak ada trust-all di kode) dan `WakelockTimeout` (wakelock pakai timeout 12 jam); `CustomX509TrustManager` dipertahankan (anchor tambahan Android 5/6).
- `AGENTS.md` + `network_security_config.xml`: struktur file disinkronkan (`AutoUpdate`, `PinGate`, `LogExport`, `StoragePerm`); komentar cleartext diluruskan (HTTPS-only, loopback hanya probe lokal).

## [Belum rilis] — Perbaikan audit: PIN bot ber-spasi, samaran tg_pass, sidik admin, share sempit, KDF header, cache cap, potong surrogate, trim port, semver +build
- `TgBot.pisahkanPin`: format eksplisit `PIN:...` menelan sisa baris sebagai PIN (kutip mengapit dikupas) sehingga passphrase ber-spasi mis. `kunci saya 9` bisa dipakai via bot (`/stop PIN:"kunci saya 9"`); fallback kata-terakhir tetap untuk `/restore YA 123456`.
- `LogActivity`: `tg_pass` ber-spasi disamarkan sampai akhir baris (`POLA_SANDI_SPASI`), bukan sampai spasi pertama, agar sisa frasa tak bocor saat log dibagikan/disalin.
- `ServerService.runningAdminToken` kini sidik SHA-256 (`sidikTokenAdmin`), bukan plaintext statis; perbandingan hint restart di `MainActivity`/`SettingsActivity` memakai sidik.
- `FileShareProvider`: files/cache hanya membagikan `ca.pem`/`cert.pem`/`ca-cadangan-*.pem`/`app-config-*.json(.enc)` (`namaCacheBolehDibagikan`); allowlist lebar `*.json/*.txt/*.zip/*.enc` hanya untuk folder data `tls/` & `backups/`.
- `TgBackup`: file baru ditulis `VWB2` (SHA256 saja); password salah pada VWB2 langsung gagal tanpa fallback SHA1 ganda (hemat 2x PBKDF2 100k); fallback SHA1 hanya untuk arsip lama `VWB1` dan hanya saat galat autentikasi.
- `HttpsCompat.capOverride` + `ServerService.capCaAktif`: cap penuh dihitung ulang hanya bila stat (mtime+ukuran) berubah — polling bot tiap 20 dtk tak lagi baca+hash file tiap koneksi.
- `ServerService.potongPesanGalat`: potong 80 char di batas pasangan surrogate agar emoji tak jadi tofu di log/Telegram.
- `ServerService.isPortBusy`: komentar menegaskan cek pra-start bersifat saran (TOCTOU); penentu sah gagal bind Rocket + mitigasi `portDirebut` di watchProcess.
- `perluMigrasiPort` memakai `trim()` sehingga `" 8080"` tetap migrasi; `normalisasiPinVersi` menerima lalu mengupas metadata semver `+build` (`1.32.0+foo` → `1.32.0`) agar tak fallback diam-diam ke latest.
- Unit test: PIN ber-spasi, samaran sandi ber-spasi, sidik admin, whitelist cache, magic VWB2, cache cap, potong surrogate, trim migrasi, kupas +build.

## [Belum rilis] — Perbaikan audit: staging web-vault unik, normVersion huruf V
- Ekstrak web-vault kini ke staging unik per panggilan (`web-vault.new-<cap>`, bukan `web-vault.new` bersama): update bot + UI yang bersamaan tak lagi menimpa hasil ekstrak satu sama lain, dan `cleanupTempFiles` Start tak bisa membuang staging yang sedang diekstrak (komentar `KUNCI_WEBVAULT` selama ini mengklaim proteksi itu tapi ekstrak berjalan di luar kunci; hanya swap yang dikunci).
- `cleanupTempFiles` menyapu sisa staging yatim (`web-vault.new` lama + `web-vault.new-<cap>` unik) agar gagal/crash tak menumpuk folder 35 MB.
- `normVersion` turut mengupas huruf `V` besar (selaras `uraiVersi`/`normalisasiPinVersi`): tag `V1.x` tak lagi jadi URL `vV1.x` 404.
- Unit test: `normVersionKupasHurufVBesarJuga`, `stagingWebVaultUnikDanDikenaliSapu`.

## [Belum rilis] — Perbaikan audit: hapus rekursif symlink-safe, flag autoRestart volatile
- `ServerService.deleteRecursive` hapus link-nya saja bila symlink (selaras `Updater`): sapu `web-vault.new`/`.bak` tak lagi mengikuti link keluar folder data.
- `autoRestart` kini `volatile`: ditulis thread watch/health, dibaca UI thread (`restartTunda`) sehingga stop fatal tak dibaca basi.

## [Belum rilis] — Perbaikan audit: share plaintext async, ROCKET_TLS, stop jujur, resume, import, DER
- Share config plaintext tak lagi dihapus saat chooser kembali (Gmail/Drive membaca async setelah kembali); file dipertahankan 24 jam dan disapu basi di `onResume`/`onDestroy`.
- `dataDirAman` menolak koma/kurawal dan `ROCKET_TLS` memakai `kutipRocket` agar path folder tak merusak parse `{certs="...",key="..."}`.
- `stopServer` jujur: status `Menghentikan...` dan flag/DB baru dibersihkan setelah proses lama benar-benar mati (sebelumnya klaim `Stopped` lalu timpa DB selagi terkunci).
- Resume unduhan mengunci TOCTOU: panjang/mtime dicatat sebelum hash prefix dan dibatalkan lantang bila berubah di tengah.
- Import config memakai nama unik per import (`vwcfg-import-<cap>`) + sapu sisa agar import ganda tak berebut file.
- Encoder DER `len()` mendukung `0x84` untuk payload >16 MB (sebelumnya 3 byte selalu).
- Unit test: `dataDirAmanTolakKomaKurawalUntukRocketTls`, `kutipRocketEscapeBackslashDanKutip`, `panjangDerBesarPakaiAwalanBenar`.

## [Belum rilis] — Perbaikan audit: watchdog Updater, locale JSON, redirect Telegram, tulis wall-maks
- Watchdog `detectVersion` di `Updater` pakai `AtomicBoolean` (duplikat pola `ServerService` yang terlewat; elemen `boolean[]` bukan `volatile`).
- `lolosJson` format hex pakai `Locale.US` agar locale berdigit non-Latin tak merusak escape JSON.
- Unduhan file Telegram hanya ikuti redirect ke host Telegram (`hostTelegramAman` + `bolehIkutiRedirectTelegram`); URL unduh membawa token bot di path sehingga 302 ke host asing bisa membocorkannya.
- `simpanWallMaks` tulis prefs hanya bila nilai berubah (sebelumnya tiap poll 20 detik).
- Unit test: `redirectTelegramHanyaHostTelegram`, escape JSON di locale Arab.

## [Belum rilis] — Perbaikan audit: cleartext, samaran chat, PIN simbol, watchdog, retensi pre
- `usesCleartextTraffic` kini `false`: server HTTPS-only dan Telegram/GitHub selalu HTTPS sehingga cleartext tak dibutuhkan; flag `true` membuka cleartext WAN di API 23.
- `samarkanLog` turut menyamarkan `tg_token=`/`tg_chat=` mentah (`key=value` tanpa kutip tak ikut pola JSON/`chat_id`).
- `pisahkanPin` fallback menerima PIN bersimbol (`YA p@ss!9`, selaras input `textPassword` UI); kata huruf murni tetap argumen agar tak makan lockout.
- Watchdog `--version` pakai `AtomicBoolean` agar selesai Start terlihat thread watchdog (sebelumnya elemen `boolean[]` bukan `volatile`).
- `cleanupOldBackups` mempartisi salinan pra-restore (`*-pre.sqlite3`, maks 2 sendiri) agar tiap restore tak mengusir backup bagus dari kuota.
- `gantiAtomik` coba hapus `.bak` dua kali agar binary yatim 15 MB tak menumpuk bila hapus pertama gagal (FAT).
- Import config menormalisasi kuncian versi sampah jadi kosong (= terbaru) agar tak mengendap di prefs.
- Koreksi audit: unduhan resume korup sudah aman (`digestPrefix` gagal lantang berisi kata `parsial` lalu dibuang); share plaintext cache sudah diizinkan provider; port import sudah dinormalisasi — ketiganya tidak diubah.

## [Belum rilis] — Perbaikan audit: export plaintext, path panjang, crash-dialog, wakelock
- Export config plaintext dihapus begitu user kembali dari chooser (`startActivityForResult` + sapu di `onCreate`/`onDestroy`); sweep melewati file yang chooser-nya belum terbuka agar export ganda cepat aman.
- `dataDirAman` menolak path >512 char / segmen >255 char agar `/status` Telegram tak jebol 4096 char dan `mkdirs` tak gagal misterius; unit test `dataDirAmanTolakPathTerlaluPanjang`.
- Dialog crash-log menyamarkan ulang isi file saat tampil (pola baru berlaku untuk file lama).
- `acquireWakeLock` melepas kunci lama dulu agar referensi tak tertimpa dan bocor sampai timeout.

## [Belum rilis] — Perbaikan audit: PIN plaintext, wakelock bot, samaran chat_id, tulis PIN atomis
- `SettingsActivity` tak lagi menyimpan PIN mentah di field (`pinHashUntuk` dihapus; penanda settle cukup nomor urut) dan hash otomatis tiap ketikan hanya ditulis bila PIN aktif.
- `TgBotReceiver` cek token/chat dulu lalu `goAsync` dulu sebelum pegang wakelock agar `goAsync` yang melempar tak membocorkan wakelock 60 detik.
- `LogActivity.POLA_CHAT_ID` turut menyamarkan bentuk JSON (`"chat_id":123`) selain `chat_id=...`.
- Enable PIN menulis hash + flag on dalam satu `apply` atomis agar crash di antaranya tak meninggalkan hash basi.
- `Updater.unduhSatuPercobaan` melempar `Exception` (bukan `IOException`): `open()` TLS/proksi melempar checked umum sehingga refactor retry e79924a gagal kompilasi di CI.

## [Belum rilis] — Perbaikan audit: latch rollback, bukti PIN, retry lock, IP berubah
- `TgBot.catatMundurDanBolehIngatkan` tak lagi menjepit tanda air ke jam mundur agar rollback beruntun tak menurunkannya bertahap.
- Pesan perintah berbahaya yang PIN-nya gagal dipertahankan di riwayat sebagai bukti brute-force (`handleCommand` kembalikan status otorisasi; hapus hanya bila terotorisasi).
- Jeda retry unduh `Updater` pindah ke luar kunci per-file agar tak menahan pemanggil lain selama 3 detik.
- `ServerService` ingatkan sekali per IP baru bila IP LAN berubah setelah start (`DOMAIN` hanya dibaca saat start).
- Unit test: `mundurTakMenurunkanTandaAir`.
## [Belum rilis] — Perbaikan audit: PIN log, kuncian versi, restart race, boot jujur
- `LogActivity` cek PIN lewat `amanBoolean` agar prefs korup tak bypass kunci log.
- `Updater.kuncianBinary/WebVault` lewat `amanString` agar kuncian user tak hilang diam-diam saat prefs korup.
- `ServerService.restartAttempt` jadi `AtomicInteger` + `scheduleRestart` sinkron agar backoff restart tak balapan thread watch/health.
- `BootReceiver` catat jujur bila auto-start boot ditolak sistem (susulan tetap via `KEY_START_TERTUNDA` di `MainActivity`).
## [Belum rilis] — Perbaikan audit: prefs tahan korup, PIN fallback, CA regen dini
- Semua bacaan prefs (`TgBackup`, `TgBot`, `Updater`, `ServerService`) lewat `amanString/amanBoolean/amanInt/amanLong`; `aman*` kini null-safe agar `ClassCastException` tak crash service/bot.
- `pisahkanPin` fallback multi-kata wajib berdigit: `YA webvault` tak lagi dimakan sebagai PIN (selaras cabang kata-tunggal), `YA ab12`/`YA 123456` tetap PIN.
- `TlsCert.caOk` regen dini 30 hari seperti leaf agar CA tak kedaluwarsa mendadak.
- Web-vault versi tak dikenal + cek terbaru gagal kini hemat kuota (tak unduh 35 MB membabi-buta).
- Dialog All files access dibatasi 5 menit sekali agar tak muncul tiap buka app.
- Unit test: `pisahkanPin` multi-kata huruf vs berdigit.
## [Belum rilis] — Perbaikan audit: nama file unik, shim, CHANGELOG
- `TgBackup.stempelUnik()` (timestamp + 4 hex acak): nama `db-backup`, `app-config`, dan salinan CA tak saling timpa bila dibuat dalam milidetik yang sama.
- Pembersih export plaintext lama kini jalan di kedua cabang (terenkripsi/plaintext).
- Shim getrandom: buka-ulang `/dev/urandom` usai `EBADF` dibatasi 3x lalu gagal tertutup (sebelumnya berpotensi berputar selamanya).
- Unit test: format + keunikan `stempelUnik`.

## [Belum rilis] — Perbaikan 7 temuan audit (PIN bot, redirect, folder, notif, export, token, PIN alnum)
- `pisahkanPin` kata-tunggal hanya numerik 4+ digit: argumen salah ketik (mis. `webvault`) tak lagi dimakan sebagai PIN dan menambah lockout.
- Redirect unduhan binary/web-vault dibatasi host GitHub (`bolehIkutiRedirectGithub`); unduhan file Telegram tetap umum.
- `MainActivity.saveAndStart` validasi `dataDirAman` lebih awal dengan pesan jelas.
- Notifikasi foreground jujur saat tugas latar jalan tanpa server.
- Nama file export log tambah akhiran acak anti-timpa; komentar token bot diluruskan.
- UI PIN dukung huruf/angka seperti bot (`textPassword`, hint diperbarui).
- Unit test: `pisahkanPin` kata-tunggal huruf, `bolehIkutiRedirectGithub`.

## [Belum rilis] — Perbaikan audit: keyboard Telegram lolos JSON
- `keyboardPerintah` pakai `lolosJson` seperti `menuPayload` agar label perintah ber-tanda kutip tetap valid.
- Unit test: `keyboardPerintah_memuatSemuaPerintah`.

## [Belum rilis] — Perbaikan audit: race Stop/health, fail-closed PIN bot, trim surrogate
- Health-check yang terbang saat Stop ditekan tak lagi menghidupkan ulang server (`healthFail`/`restartTunda` hormati `healthActive`).
- `authDangerous` fail-closed: PIN aktif tanpa hash tetap minta PIN (pulih via buka Settings).
- `ACTION_TG_BACKUP` baca `tg_auto` tahan korup agar `stopSelf` tetap jalan; `effectivePort` jatuh ke default bila prefs korup.
- Export plaintext hapus `app-config-*.json` lama di cache agar tak menumpuk.
- Trim `logBuffer` (2 jalur via `pangkasBufferTerkunci`) dan log layar awal di batas code-point.

## [Belum rilis] — Perbaikan audit: PIN, surrogate, JSON menu
- Aktif PIN kini selalu hash ulang dari isi field saat itu di worker (satu tekan): centang tak pernah memakai hash basi/parsial, user tak terkunci permanen.
- `potongEkor`/`shorten` potong di batas code-point agar emoji tak terbelah jadi lone surrogate (Telegram 400/teks rusak).
- `menuPayload` lolos JSON (`lolosJson`) agar deskripsi perintah ber-tanda kutip tetap valid.
- Unit test: `potongEkor_takBelahSurrogate`, `lolosJson_amankanKutipDanKontrol`.

## [Belum rilis] — Pilih versi binary & web-vault (kunci versi, bisa downgrade)
- Settings → Pemeliharaan ada tombol "Versi binary" dan "Versi web vault": pilih dari daftar rilis repo, ketik manual (mis. 1.32.0), atau kembali ke "Terbaru (otomatis)".
- Versi pilihan tersimpan (`bin_pin_version`/`wv_pin_version`, ikut export/import config) dan dihormati auto-update, Cek Update, Start, dan tombol Update Web Vault — tak dinaikkan diam-diam ke terbaru.
- Versi eksplisit selalu dipasang termasuk downgrade; tanpa kuncian, asset 404 tetap fallback ke rilis terbaru seperti dulu (versi terkunci gagal lantang bila asset-nya tak ada).
- Bot Telegram: perintah baru `/versi` (baca, tanpa PIN), `/update [versi|terbaru]`, `/webvault [versi|terbaru]`; tombol inline "Versi" ditambahkan; `/help` diperbarui.
- Unit test: `normalisasiPinVersi`, `parseDaftarTag`, `pilihTarget`, `argumenVersiValid`.

## [Belum rilis] — Perbaikan audit menyeluruh (token, PIN, folder, hijack, konstanta)
- Log selalu samarkan token bot mentah (sebelumnya hanya dalam konteks Telegram).
- `pisahkanPin` fallback kata-terakhir hanya untuk PIN numerik 4+ digit: kata biasa tak dimakan, lockout tak bertambah sia-sia.
- Simpan folder data di Settings kini cek kanonis (symlink/`..` lolos string ditolak sebelum masuk prefs).
- Deteksi DNS-hijack meluas ke `127.x`, `100.64/10`, `fe80`/`fd00`/`::1`; locale tanggal STB `in` -> `id` (2 lokasi).
- Kunci `tg_backup_tertunda` disentralisasi ke `TgBackup.KEY_BACKUP_TERTUNDA` (Alarm/Boot/Main).
- `gantiAtomik` fsync direktori best-effort agar rename awet bila STB mati tepat sesudah pasang binary.
- Koreksi audit sebelumnya: TLS sudah backdate `notBefore -1 hari` dan port privileged sudah fail-fast sebelum exec (tidak diubah).

## [Belum rilis] — Perbaikan race /careset vs backup
- `/careset` kini jalan di bawah kunci tugas berat (serial dengan backup/restore):
  reset menghapus file tls satu per satu sehingga backup konkuren bisa menangkap setengah set.

## [Belum rilis] — Perbaikan 3 bug audit (fallback web-vault, symlink, callback)
- Web-vault via fallback kini catat penanda 6 jam: cek berikutnya tak lagi unduh ulang 35 MB tiap kali.
- `deleteRecursive` tak lagi mengikuti symlink direktori (hapus link-nya saja).
- Callback Telegram wajib dari ruang resmi; pengirim saja tak cukup.
- Unit test: `fallbackBaruSaja`, `tautanSimbol`.

## [Belum rilis] — Perbaikan 3 bug ringan audit (alarm bot, prefix hash, chat ID)
- Alarm polling bot kini dibatalkan bila token atau chat kosong (sebelumnya chat kosong tetap membangunkan perangkat tiap 20 detik).
- `digestPrefix` gagal lantang bila file parsial terpotong agar unduh ulang dari nol dengan pesan jelas (bukan gagal checksum menyesatkan).
- `uploadTelegram` memvalidasi chat ID sebelum tulis mentah ke multipart.
- Unit test: `chatIdAman`.

## [Belum rilis] — Perbaikan 4 bug audit (unduhan fallback, impor config, kunci privat, komentar TLS)
- Unduhan binary/shim/web-vault tak lagi balik ke URL asli setelah fallback: digest selalu sesuai checksum URL final (sebelumnya unduhan bagus bisa ditolak `Checksum SHA-256 tidak cocok`).
- Impor `app-config.json` kini normalisasi port ke default bila rusak dan mengabaikan `bin_sha` bukan 64 hex agar binary manual tak selalu ditolak.
- `kunciPrivat` cocok tepat (`ca-key.pem`/`key.pem`/`*.key`): file sah seperti `backup-ca-key-info.txt` tak ikut ditolak.
- Komentar `TlsCert.ensure` diluruskan (deteksi IP via `ips.txt` di `ServerService`); komentar `pisahkanPin` menegaskan fallback kata-terakhir by-design untuk `/restore YA <PIN>`.
- Unit test: `shaHexValid`, `namaBiasaBerisiCaKeyTakIkutDitolak`, `pisahkanPin` alur `YA 123456`.

## [Belum rilis] — Perbaikan 7 bug audit (stop race, atomik, health, kunci, CA, zip)
- Stop sengaja per-proses (bukan boolean global): Stop lalu Start cepat tak lagi salah menandai crash baru sebagai stop sengaja.
- Ganti binary/shim/trust-chain atomik via `.bak`: crash di tengah pasang tak lagi meninggalkan tanpa-binary.
- Health anti-livelock: /alive gagal tapi TCP hidup ditoleransi 3 siklus lalu dihentikan sebagai gantung (sebelumnya reset ke 2 selamanya).
- Kunci unduh per-file tanpa `String.intern` (map kunci) + swap web-vault dikunci bersama `cleanupTempFiles` agar Start tak membuang ekstrak 35 MB.
- CA jam miring (belum valid) dipakai terus agar tak regenerasi tiap Start yang memaksa install ulang ca.pem.
- Normalisasi zip cek segmen `..` (bukan substring) agar folder sah `my..folder` tetap direstore.
- Unit test: `adaSegmenDotDot`, `kunciUnduh` identitas, `gantiAtomik`.

## [Belum rilis] — Perbaikan temuan audit kode (putaran 11)
- `mulaiService` kini kembalikan boolean: penolakan start background tak lagi ditelan diam-diam; `AlarmReceiver` menandai susulan backup saat `backupNow` gagal (fallback `tg_backup_tertunda` sebelumnya dead code), dan pesan bot `/start`/`/stop`/`/restart` serta restart pasca-update jujur bila intent ditolak sistem.
- Susulan backup boot di `MainActivity` cek ulang `tg_auto`: tak mengunggah bila user sudah mematikan auto-backup (selaras `ACTION_TG_BACKUP`).

## [Belum rilis] — Perbaikan temuan audit kode (putaran 10)
- Import config menolak stream null dengan pesan jelas (sebelumnya NPE → toast `Gagal import config: null`); selaras dengan cek null `restoreDatabase`.
- Stop saat start masih persiapan (unduh binary) kini membatalkan start tepat sebelum exec: server tak lagi jalan sendiri walau Stop sudah ditekan.
- Auto-start yang ditolak sistem dari background (Android 12+) menandai `auto_start_tertunda` dan dijalankan susulan saat app dibuka (pola yang sama dengan susulan backup boot).
- Export config tak lagi memuat kunci milik perangkat (`data_dir`, offset/wall-clock bot, penanda notifikasi, flag susulan) agar import di perangkat lain tak membawa state basi.
- Dialog crash-log layar log penuh membaca file di worker thread agar tak ANR di storage STB lambat.

## [Belum rilis] — Perbaikan temuan audit kode
- Port privileged (<1024 tanpa root) tak lagi dilaporkan "sedang dipakai": `portButuhRoot` membedakan EACCES/permission-denied dengan pesan "butuh akses root, ganti ke >= 1024" (Start di service, Main, dan Settings; + unit test).
- Backup terjadwal memverifikasi ulang `tg_auto` di `ACTION_TG_BACKUP`: alarm basi/duplikat tak mengunggah setelah auto-backup dimatikan.
- `PinCrypto.verify` menolak hash beriterasi di bawah 10.000 (fail-closed; + unit test).
- `BootReceiver` kini `exported=true` agar `BOOT_COMPLETED` sampai di Android modern (ketiga action-nya protected broadcast, hanya sistem yang bisa kirim).

## [Belum rilis] — Layar utama lega: kartu log tanpa judul/tombol
- Kartu log realtime layar utama tanpa judul, tombol Ciutkan, tombol Simpan .txt, dan info jumlah baris; area teks naik 220dp → 280dp agar lega.
- Simpan .txt tetap ada di layar log penuh; rantai fokus D-pad dialihkan (info URL ↔ Start) mengikuti tombol update.

## [Belum rilis] — Smoke test gantung, izin simpan log, landscape TV
- Smoke test `--version` tak lagi menggantung: `ServerService.detectBinaryVersion` + `Updater.detectVersion` kini dipagari watchdog 10 detik (bunuh terjadwal menutup pipa agar `readLine()` balik EOF); thread Start/tombol berat tak nyangkut `Bekerja...` bila binary macet tanpa output.
- Simpan log layar penuh kini menawari izin ulang: bila izin storage belum ada (Android 6-9), tombol Simpan meminta izin dulu lalu menyimpan otomatis saat disetujui, bukan selalu `Gagal menyimpan log`; layar utama ikut dijaga sama.
- Layar awal satu kolom vertikal (portrait + landscape sama): kartu tautan di atas, log realtime di tengah, status server di bawah; font log ikut ukuran layar (`sw480dp-land`/`sw600dp-land`) agar muat banyak baris di TV.

## [Belum rilis] — Perbaikan bug tampilan (audit UI)
- Log layar penuh: sorot pencarian + baris galat memakai warna tema (`search_highlight`/`log_error` sinkron terang-gelap) agar terbaca di mode malam; isi log diberi kotak info + warna teks tema + padding seperti pratinjau Home.
- Rantai D-pad layar log: Kembali <-> Crash terhubung horizontal, paruh kanan baris tombol naik ke Crash, dan Auto-scroll tidak lagi menjebak fokus ke Cari.
- Baris 4 tombol layar log satu baris + elipsis agar tak wrap di layar sempit; pratinjau log Home memakai `nestedScrollingEnabled` agar gulir jari tak berebut scroll halaman.
- Dialog Tentang: warna link ikut aksen tema agar terbaca di mode malam.
- Tablet portrait (`sw600dp`): margin `200dp` -> `48dp` agar konten tak terjepit jadi kolom tipis.

## [Belum rilis] — Perbaikan hasil audit kode (putaran 9)
- Bot Telegram: tuntaskan putaran 7 — `/status`/`/log`/`/crashlog` cukup auth chat (gate PIN sisa + teks `/help` diselaraskan) agar tombol inline jalan saat PIN aktif.
- Status: riwayat restart dibersihkan saat server berhasil start agar tak tampil basi.
- Bot Telegram: potong ekor `/log`/`/crashlog` di batas baris (+ unit test).

## [Belum rilis] — Perbaikan hasil audit kode (putaran 8)
- Update versi: jeda gagal API berlaku juga saat cache basi ada (kembalikan cache basi, tiap Start saat offline tak menghantam API).
- Health check: flag `running` dimatikan tanpa syarat di cabang terminal agar tak macet true bila proses mati di jeda cek-vs-eksekusi.
- Log: pencarian di-debounce 300 ms agar tak salin buffer 300 KB per ketikan di UI thread.
- Berbagi file: `openFile` menolak mode tulis eksplisit (berbagi hanya baca).
- Validasi chat: terima `@username` selaras auth bot (ID numerik tetap utama).

## [Belum rilis] — Perbaikan hasil audit kode (putaran 7)
- Bot Telegram: perintah baca (`status`/`log`/`crashlog`/`uptime`/`alive`/`help`) tak lagi butuh PIN (cukup auth chat) agar tombol inline tetap jalan saat PIN aktif; perintah ubah keadaan (`start`/`stop`/`backup`/`restore`/`update`/`webvault`/`careset`) tetap wajib PIN.
- Bot Telegram: `/restore confirm` ikut diterima selain `YA`/`yes`/`konfirmasi`.
- Auth chat: config `@12345` (numerik) diperlakukan sebagai ID, bukan username, agar tak bisa diklaim lewat username.
- Cek port: `SO_REUSEADDR` agar Start langsung setelah Stop tak dikira sibuk (`TIME_WAIT`); bind `::` yang gagal karena perangkat tanpa IPv6 tak lagi memblokir Start.
- Folder data: traversal dicek per segmen (`..`) sehingga folder sah bernama `my..folder` tetap diterima.
- Antrean pesan Telegram tetap buang-tertua (anti-OOM) tapi kini dicatat ke log saat pesan gugur.

## [Belum rilis] — Sederhanakan desain Settings (13 usulan)
- Judul kartu tanpa nomor: Server, Keamanan, Pembaruan & Sertifikat, Telegram, Log; satu warna aksen primary (terang/gelap sinkron).
- Kartu Server ramping (status + Start + URL + folder/port); Admin Token + PIN pindah ke kartu Keamanan; cadangan cepat pindah ke Telegram.
- Kartu Pengaturan + Pemeliharaan digabung jadi Pembaruan & Sertifikat (sistem, pembaruan, sertifikat, konfigurasi, DB).
- Mode sederhana tampilkan ringkasan per kartu + tombol Ubah (tak lagi sembunyi total); ceklis mode pindah ke hero atas.
- Bilah bawah tunggal [Start/Stop] [Buka Web UI]; tombol Buka di kartu dihapus; Start dikunci saat folder/port invalid.
- Sertifikat: teks panjang jadi tombol Pelajari + ringkasan satu baris.
- Field sensitif standar: tombol Salin + status tersimpan/belum diisi; validasi inline token bot, chat ID, admin token.
- Wizard 3 dialog jadi 1 dialog (folder + port sekaligus); padding kartu 12dp; rantai D-pad diaudit ulang.

## [Belum rilis] — Desain ulang Settings (mode sederhana + wizard)
- Judul kartu bernomor langkah (1 Server, 2 Pengaturan, 3 Pemeliharaan, 4 Telegram, 5 Log) + aksen warna per kartu.
- Mode sederhana (bawaan aktif): hanya kartu Server berisi Folder, Port, Admin Token, PIN, dan cadangan cepat.
- Wizard 3 langkah (Folder → Port → Start) untuk instalasi baru; pengguna lama tak diganggu.
- Badge status CA/HTTPS inline, validasi inline Port/Folder, tombol salin URL jaringan + URL lokal.
- Titik merah pada label yang diubah tapi server belum restart; bilah Start/Stop menempel di bawah.
- Konfirmasi sebelum matikan PIN; tombol Acak untuk Admin Token; progress bar unduhan; kontras mode malam dinaikkan.

## [Belum rilis] — HTTPS-only (HTTP tak bisa dipakai)
- Server selalu TLS: saklar HTTPS di Settings dikunci aktif, `ROCKET_TLS` selalu dipasang, health check/`localUrl`/`DOMAIN` selalu `https://`.
- Start gagal lantang (fail-closed) bila sertifikat TLS gagal dibuat — tak lagi jatuh ke HTTP.
- Panduan `[login]` + pesan error TLS kini mengarah ke install CA (bukan matikan HTTPS); label bot `/alive` HTTPS.

## [Belum rilis] — Perbaikan hasil audit kode (putaran 6)
- Dekripsi backup: header magic dibaca sampai penuh (`readFully`) agar short-read tak mengira file terenkripsi sebagai plaintext.
- Loop isi manual (`readFully`, `isSqliteFile`, deteksi zip + header SQLite di restore lokal) berhenti pada kembalian 0 agar tak macet tanpa henti.
- Sisanya terverifikasi sudah benar di putaran 5: loop `bacaPrivateKey`/verifikasi DB (`!= -1`), race `stopDisengaja`, `killStaleVaultwarden` lewati `--version`, CI tolak APK debug sebagai rilis, `tanpaKuota()` false saat offline, `isEncrypted()` penuh, entri file `tls` dilewati, shim `#error` + `pthread_once`, digest web-vault pakai retry.

## [Belum rilis] — Perbaikan hasil audit kode (putaran 5)
- Sisa loop baca `> 0` dituntaskan (`bacaPrivateKey`, verifikasi header DB): `!= -1` di semua jalur.
- Race `stopDisengaja`: tanda stop lama dihapus saat proses baru lahir agar crash dini tak dikira stop sengaja.
- `killStaleVaultwarden` melewati smoke-test `--version` milik flow konkuren (+ unit test).
- CI gagal lantang bila APK release tak ada (tak lagi menerbitkan APK debug sebagai rilis); unduh digest web-vault pakai retry.
- `tanpaKuota()` false saat offline; `isEncrypted()` baca magic sampai penuh; entri file `tls` ditolak saat restore.
- Shim: arsitektur tak dikenal gagal saat kompilasi (`#error`); init penerusan `syscall` via `pthread_once`.

## [Belum rilis] — Perbaikan hasil audit kode (putaran 4)
- Loop baca stream (`unduh`, `backup/restore`, `salin`, `hash`, `cap TLS`) memakai `!= -1` bukan `> 0`: kembalian 0 tak lagi dianggap EOF sehingga file tak terpotong diam-diam.
- PIN fail-closed penuh: hitungan gagal tanpa stempel kunci (prefs korup/kunci dihapus) mengunci 5 menit, tak lagi membuka lockout (+ unit test).
- Penolakan binary manual (SHA tak cocok/belum diisi) kini tampil di status, bukan hanya di log.
- Refresh anchor TLS menandai hari untuk hasil negatif definitif (404/rantai tak valid/checksum beda) sesuai janji "maks 1x sehari".

## [Belum rilis] — Perbaikan hasil audit keamanan (putaran 2)
- Reset sertifikat ditolak saat server HTTPS jalan (Stop dulu): sebelumnya file CA dihapus di bawah server hidup lalu health check membunuhnya sebagai "tidak sehat".
- `file_path` Telegram divalidasi (tolak traversal/absolut) + cek storage dari `file_size` sebelum unduh backup.
- Cap cache TLS loopback hash seluruh isi (seperti `HttpsCompat`) agar factory tak basi setelah regenerasi cepat.
- CI melewati build untuk commit dokumen saja (`paths-ignore`: `*.md`, `.gitignore`).

## [Belum rilis] — Perbaikan hasil audit keamanan
- `/status` Telegram kini wajib PIN bila PIN aktif (seperti `/log`); tombol Status memberi tahu agar ketik manual saat PIN aktif.
- Pesan perintah ber-PIN dihapus otomatis dari chat (best-effort, butuh izin hapus) agar PIN tak menempel di riwayat.
- `FileShareProvider` membuka file kanonis hasil cek (tutup celah symlink cek-vs-buka).
- Bind gagal karena port direbut (`EADDRINUSE`) langsung berhenti + saran jelas, tanpa restart beruntun.
- Export plaintext tanpa password memang sudah ada dialog peringatan — tidak diubah.

## [Belum rilis] — Hapus status web port+1
- Status web `ControlServer` (port+1, JSON + log SSE) dihapus total beserta test dan seluruh rujukannya di `ServerService`, `MainActivity`, `SettingsActivity`, string, dan README; pemantauan tetap via log realtime di aplikasi + `/status`/`/log` Telegram sehingga tak ada port tambahan yang terbuka.

## [Belum rilis] — Perbaikan hasil audit kode (putaran 3)
- PIN fail-open ditutup: PIN pendek menghapus hash sekaligus mematikan PIN;
  kunci otomatis mematikan PIN yatim (tanpa hash) dengan catatan log.
- Restore dari Telegram memakai folder data tersanitasi seperti Start.
- Checksum `.sha256` wajib 64 digit heksadesimal (pesan galat lebih jelas).

## [Belum rilis] — Perbaikan hasil audit kode (putaran 2)
- Status web (`ControlServer`) kini menutup soket SSE/klien saat stop dan
  membersihkan hitungan per-IP agar instance baru tak mewarisi 503/429 basi.
- Folder privat kanonis `/data/user/0/...` diterima sebagai folder data sah
  (sebelumnya hanya `/data/data/...`).
- Service mengabaikan intent tanpa aksi START agar tak menyala sendiri.
- Alarm boot menunda jadwal susulan hanya bila backup otomatis aktif.
- Deteksi port tanpa `REUSEADDR` (konservatif, cegah crash-loop EADDRINUSE).
- Serial sertifikat dijamin non-nol; leaf diregen dini saat sisa < 30 hari.
- Cap trust anchor dihitung dari seluruh isi file tanpa overflow.

## [Belum rilis] — Perbaikan hasil audit kode
- Backup otomatis (catch-up boot, susulan, saat Start, tengah malam) menunggu
  5 menit setelah STB hidup agar sistem stabil sebelum beban backup+upload.
- Susulan boot diputuskan setelah 5 menit dengan jam stabil: jalan hanya bila
  sudah ganti hari; jam reset/mundur (mis. tahun 1999) tak memicu backup.

### Perbaikan
- Backup Telegram kini mengambil TLS dari folder internal aktif (bukan salinan
  basi di folder data); restore (bot + Settings) menyinkronkan `tls/*` ke
  internal agar identitas server benar-benar berganti.
- Health check dibatasi satu thread dalam penerbangan (cegah penumpukan saat
  server macet); cek port mencakup IPv6-only; hitung ukuran folder kebal
  symlink melingkar; komentar interval polling bot diluruskan (20 dtk).

## [Belum rilis] — Reset + backup sertifikat (Telegram & Settings)

### Fitur
- Perintah Telegram baru `/cabackup` (tanpa PIN, seperti `/ca`): salin ca.pem
  publik ke folder data (`ca-cadangan-<timestamp>.pem`) agar bisa diambil via
  file manager tanpa Telegram. `/careset` (wajib PIN bila aktif): hapus CA +
  leaf lama agar CA baru dibuat saat Start berikut; semua HP wajib install
  ulang CA baru sesudahnya.
- Settings → Pemeliharaan → Sertifikat: tombol baru Reset Sertifikat (CA baru,
  dialog konfirmasi) dan Backup CA ke Storage (salin ca.pem publik ke folder
  data). Hanya file publik yang disalin; kunci privat tak pernah ke storage.
- Menu bot, keyboard inline, dan teks `/help` diperbarui; `MENU_REV` naik agar
  daftar perintah baru terdaftar ulang ke Telegram.

### Perbaikan
- `/ca` kini mengirim file bernama unik `ca-cadangan-<timestamp>.pem` (sama
  seperti backup storage) agar tak tertukar dengan CA lama di riwayat chat
  setelah reset. Pesan balasan menyebut nama file terbaru dan menegaskan file
  lama tak berlaku lagi.


## [Belum rilis] — Log realtime jelaskan kenapa aplikasi Bitwarden gagal login

### Fitur
- Tiap start kini mencatat 3 baris panduan `[login]`: Server URL yang benar
  untuk aplikasi Bitwarden (satu WiFi, bukan port+1/bukan 127.0.0.1 dari HP
  lain), peringatan bila HTTPS aktif (aplikasi resmi menolak self-signed →
  matikan HTTPS, pakai HTTP), dan pengingat daftar akun dulu di web-vault
  (login app memakai email+password, bukan admin token).
- Output server dipindai saat jalan: kredensial salah / 401
  `/identity/connect/token`, butuh 2FA, dan gagal handshake TLS otomatis
  menambah satu baris saran `[login]` (throttle 60 dtk agar tak spam).

## [Belum rilis] — Start STB kernel lama gagal uji --version padahal binary bagus

### Perbaikan
- Start di STB kernel lama (mis. ZTE B860H 3.14.29) gagal dengan
  "File update tidak valid (gagal uji jalan --version)" padahal binary bagus:
  uji asap `--version` butuh shim `LD_PRELOAD` tapi shim baru diunduh sesudah
  binary (ayam-telur saat folder bin masih kosong). Kini shim dipastikan dulu
  sebelum uji asap di `Updater.downloadBinary`, di awal `ensureBinary`, serta
  sebelum `tryUpdate` di Settings dan `/update` Telegram. Pesan gagal uji di
  kernel lama tanpa shim kini menyebut shim + langkah (cek internet, Start lagi).

## [Belum rilis] — Upload tak tahan antrean, export ingatkan plaintext, konfirmasi restore sempit

### Perbaikan
- Koreksi audit lalu: upload Telegram tak pernah jalan di antrean pesan
  (teks 15/30 dtk mandiri) — yang benar: upload macet menahan slot pool BG +
  kunci tugas-berat. Timeout baca upload 180→60 dtk (jeda idle antar byte,
  upload lambat tetap lolos).
- Dialog export kini ingatkan eksplisit bila tanpa password backup file
  berupa plaintext di folder backups.
- Konfirmasi `/restore` dipersempit ke "ya"/"yes"/"konfirmasi" ("ok"/"y"/
  "lanjut" tak lagi memicu timpa database).
- Baca `AutoUpdate` tahan `ClassCastException` via `amanString`/`amanBoolean`
  baru (gagal satu siklus diam-diam bila prefs korup sebelum heal).
- Backup Telegram gagal karena jam STB reset (mis. `Certificate not valid until ... 2025
  (compared to ... 2015)`) kini lapor jelas: tanggal terbaca + suruh aktifkan
  Tanggal & waktu otomatis / atur manual. Guard jam (< 1 Jan 2024) gagal cepat
  sebelum TLS; semua jalur backup (jadwal, saat Start, tombol, `/backup`, susulan
  boot) pakai pesan ramah yang sama.

## [Belum rilis] — Batas SSE per-IP benar, migrasi anti-crash, receiver heal, start tahan Android 12+

### Perbaikan
- Batas SSE per-IP kini pakai host tanpa port sumber (sebelumnya
  `SocketAddress.toString()` memuat port ephemeral sehingga tiap koneksi
  terbaca beda IP dan batas tak pernah jalan).
- Migrasi prefs legacy tahan `ClassCastException` (baca via getAll+koersi,
  bungkus try/catch) dan jalan setelah heal; receiver (boot/alarm/bot)
  heal dulu di `onReceive` agar tak crash sebelum Activity/Service hidup.
- `start`/`stop`/`restart`/`backupNow` tahan penolakan background Android 12+
  (catat ke log, tak crash pemanggil; bot sudah memberi tahu user).
- Banding checksum SHA-256 constant-time (`MessageDigest.isEqual`).
- Deteksi web-vault berubah: marker + frasa legacy "Web vault updated"
  (kata "updated" bebas pensiun agar tak false-positive).

## [Belum rilis] — Heal int/long, Start tanpa ANR, log hemat baterai, SSE adil, TLS presisi, IPv6

### Perbaikan
- Heal prefs diperluas ke int/long (`pin_gagal`, `pin_kunci_*`,
  `tg_last_backup`, `tg_bot_offset`, ...); baca int/long kini tahan
  `ClassCastException` (disembuhkan sekali, lalu default) di PinGate, TgBot,
  receiver, dan restore.
- Cek port Start pindah ke worker thread (tak lagi bind di UI thread);
  service tetap cek ulang sebelum start.
- Polling log berhenti di `onPause` (tak sedot CPU/baterai saat Home),
  jalan lagi di `onResume`.
- SSE log: batas 1 koneksi per IP (429 bila lebih), umur maks 3 menit
  (EventSource reconnect otomatis), deteksi putus cepat, keepalive aktif.
- Regenerasi sertifikat memakai sisa milidetik: CA/leaf bersisa hitungan jam
  tak lagi dianggap kedaluwarsa (tak perlu install ulang CA).
- Cache trust HTTPS kini hash isi anchor (refresh se-detik ukuran sama tak
  pakai factory basi).
- Redirect se-host kini dukung IPv6 kurung-siku (`[2001:db8::1]`, `[::1]:port`).

## [Belum rilis] — Stop tunggu mati, PIN fail-closed, impor anti-crash, DOMAIN IPv6

### Perbaikan
- Stop/health/restart kini tunggu proses benar-benar mati sebelum null
  (`stopDisengaja`); `stopAndWait` tak lagi lolos palsu sehingga restore tak
  menimpa DB selagi proses lama hidup.
- PIN fail-closed saat jam mundur ke 1970 (kunci penuh, bukan bypass);
  PIN kosong Telegram tak lagi dihitung gagal (tak ada lockout sendiri).
- Impor config per-tipe (String/Boolean dikoersi, salah diabaikan) +
  heal Boolean; offset bot impor diabaikan agar zip jahat tak brick bot.
- DOMAIN/URL kini prefer IPv4 dan kurung IPv6 (`[::1]`); versi tahan sufiks
  (`1.37.3-beta` tak lagi dianggap tertua).


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
