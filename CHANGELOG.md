# Changelog

## [Belum rilis] — Field restore Telegram tertunda menjadi volatile (Audit 857)
- Sisa Audit 852: pengecualian sapuan TTL membaca `restoreTgTertundaFile` dari thread latar, tetapi field itu tidak `volatile` — tidak seperti kedua field tertunda saudaranya yang dibaca sapuan yang sama (`exportPlainTertunda`, `imporTertundaFile`, keduanya volatile memang untuk jalur ini sejak Audit 595). Tanpa volatile, bacaan basi `null` di thread sapuan bisa menghapus berkas yang dialognya masih tertunda. Kedua field restore Telegram kini volatile. (Catatan verifikasi: `exportPlainPada` yang disebut sekalian ternyata sudah volatile, tak diubah.)


## [Belum rilis] — Kontrak gerbang memeriksa rujukan steps per-job, bukan se-berkas (Audit 856)
- Sisa Audit 836: id langkah yang dianggap "terdefinisi" oleh gerbang adalah gabungan semua job di berkas workflow, padahal konteks `steps.*` di GitHub Actions hanya berlaku di dalam job yang sama — rujukan lintas job (menghasilkan string kosong di runtime) lolos. Pemeriksaan kini per-job: id dikumpulkan dari langkah job itu sendiri dan rujukan dicari hanya di dalam job yang sama. Skenario uji-diri baru (job tambahan merujuk `steps.ambil` milik job resolve) menguncinya.


## [Belum rilis] — Gerbang memvalidasi nama keluaran yang ditulis produsen resolve-vw (Audit 855)
- Sisa Audit 840: kontrak resolve-vw hanya memeriksa sisi rujukan (ekspresi `value:` di `outputs` action dan rujukan workflow), sedangkan baris tulis produsen di skrip action (`echo "tag_mentah=…" >> $GITHUB_OUTPUT`) tak pernah dibaca — salah ketik nama di sana membuat keluaran kosong di runtime dengan gerbang hijau. Kini gerbang mengumpulkan semua nama yang ditulis ke `$GITHUB_OUTPUT` di action.yml dan menggagalkan setiap nama yang dirujuk `outputs` tetapi tak pernah ditulis. Skenario uji-diri baru menguncinya.


## [Belum rilis] — Cek Gradle gerbang kini sadar komentar & nilai efektif (Audit 854)
- Sisa Audit 815/835: `cek_gradle_akar` membaca teks mentah — baris `repositoriesMode` yang dikomentari memenuhi cek substring, komentar di antara `repositories` dan `{` menyembunyikan blok berisi repositori asing, dan baris `android.useAndroidX=true` duplikat (last-wins) lolos karena regex menemukan baris `false` di mana pun. Kini kedua berkas Gradle diperiksa sesudah komentar dikupas dengan pemindai sadar-string (`kupas_komentar_kotlin`), dan `useAndroidX` dinilai dari nilai efektif terakhir di `gradle.properties`. Tiga skenario uji-diri baru (mode dikomentari, repo asing terselip komentar, duplikat last-wins) menguncinya.


## [Belum rilis] — Penjaga dialog-sedang-tampil untuk konfirmasi restore Telegram (Audit 853)
- Konfirmasi restore Telegram tak punya penjaga tampil seperti dialog impor (Audit 597): siklus pause→resume saat dialog masih terbuka (layar PIN, dialog izin, pindah aplikasi lalu kembali) membuat `onResume` menampilkan dialog kedua yang menumpuk dan berbagi berkas serta field tertunda yang sama — menekan "Tidak" di satu dialog bisa menghapus berkas yang sedang dibaca restore dari dialog lain. Kini `tanyaRestoreTelegram` menolak tampil ganda lewat penanda `dialogRestoreTgTampil` yang dibersihkan di kedua tombol.


## [Belum rilis] — Berkas restore Telegram tertunda dikecualikan dari sapuan TTL cache (Audit 852)
- Sapuan cache `bersihkanExportPlainCache` menghapus semua `vwtg-restore-*.zip` yang berumur lebih dari 1 jam tanpa kecuali — termasuk berkas yang konfirmasi restore-nya masih tertunda, padahal sesudah Audit 808 keadaan tertunda itu disimpan di Bundle dan sah hidup melewati 1 jam. Pengguna yang kembali sesudahnya mendapati tertundanya dibersihkan diam-diam atau restore gagal "berkas hilang". Berkas tertunda kini dikecualikan dari sapuan, sejalan dengan pengecualian berkas impor tertunda yang sudah ada.


## [Belum rilis] — Sinkron TLS ke salinan internal menjadi atomik dan dilaporkan (Audit 851)
- Sisa Audit 807: `sinkronTlsKeInternal` menyalin berkas TLS satu per satu langsung ke folder internal dan menelan semua kegagalan, sehingga folder internal bisa tertinggal campuran (CA baru, sertifikat lama/hilang) tanpa catatan sementara restore dilaporkan sukses penuh. Kini salinan dibangun di folder staging lalu ditukar dengan pola sisihkan-rename + cadangan (gagal tukar = folder lama kembali), fungsi mengembalikan keberhasilan, kegagalan dicatat ke log, dan kedua pemanggil (restore Telegram dan restore lokal Settings) menambahkan peringatan yang terlihat pengguna.


## [Belum rilis] — doRestore Telegram mengambil kunci tugas data sebelum mengunduh (Audit 850)
- `TgBot.doRestore` dulu mengunduh dan mendekripsi backup dulu, baru mengambil `kunciRestore()` — bila tugas data lain sedang berjalan, kerja itu sia-sia lalu dibuang; dan penanda backup terakhir dibaca saat unduh dimulai, sehingga berkas yang dipulihkan bisa lebih baru daripada yang disebut di prompt konfirmasi. Kunci kini diambil paling awal (gagal cepat dengan pesan yang sama) dan unduh + dekripsi + restore berjalan seluruhnya di dalam kunci.


## [Belum rilis] — PinActivity membaca hash PIN di dalam kunci bersama (Audit 849)
- Jalur UI membaca hash PIN tersimpan tepat *sebelum* masuk `PinGate.KUNCI_PERCOBAAN`, lalu memverifikasi terhadap bacaan itu di dalam kunci — bila PIN diganti jalur lain pada jeda sempit itu, PIN lama masih bisa diterima satu kali. Hash kini dibaca di dalam kunci, menyelaraskan jalur UI dengan jalur bot yang sudah lebih dulu kebal pola ini (Audit 809); nilai yang sama tetap dipakai cek tulis-balik upgrade sesudahnya.


## [Belum rilis] — Verifikasi berkas berbagi FileShareProvider menjadi satu pintu (Audit 848)
- Rantai verifikasi yang sama (jalur kanonik, cek akar bagi, kanonik ulang + kesetaraan, cek symlink induk, `isFile`) dulu ditulis ulang di `query`, `getType`, dan `openFile`, sehingga satu aksi bagikan membayarnya tiga kali dan ketiganya bisa menyimpang sendiri-sendiri. Kini ketiganya memakai helper bersama `berkasBagiTerverifikasi`; hasil sengaja tetap tak di-cache (tiap panggilan memverifikasi segar, fail-closed) dan pemeriksaan jalur fd sesudah buka di `openFile` dipertahankan.


## [Belum rilis] — killStaleVaultwarden berhenti memindai /proc berulang per kandidat (Audit 847)
- Di dalam loop kandidat, PID anak dihitung ulang lewat `runningChildPid()` sampai dua kali per kandidat; di pra-API 26 (perangkat sasaran utama) itu adalah pemindaian seluruh `/proc`, jadi Start membayar biaya kuadratik terhadap jumlah proses satu-UID. Kini PID anak disegarkan paling banyak sekali selama loop dan dipakai ulang untuk kedua pemeriksaan; nilai sesaat yang basi selalu berarah aman (melewatkan bunuh, bukan membunuh anak yang hidup).


## [Belum rilis] — Klasifikasi crash menunggu pembaca output sampai 2 detik (Audit 846)
- `watchProcess` menggolongkan crash (panic shim / port direbut / biasa) dari ekor log, tetapi `tungguPembacaOutput` hanya memberi thread pembaca 500 ms untuk mengejar — saat banjir log, baris fatal sering belum terpompa sehingga crash port-direbut/panic digolongkan biasa dan server di-restart sia-sia beberapa kali sebelum berhenti. Batas tunggu kini 2 detik; pembaca berhenti sendiri saat aliran proses tutup, jadi waktu ekstra hanya terbayar bila pembaca memang tertinggal.


## [Belum rilis] — Entri penunggu proses dibuang juga pada jalur interupsi (Audit 845)
- Sisa Audit 818: pembuangan entri `PENUNGGU_PROSES` di `waitForOrKill` hanya terjadi pada lanjutan normal sesudah `join`; pemanggil yang terinterupsi saat menunggu menyisakan entri sampai penghentian berikutnya untuk proses yang sama. Pembuangan kini di blok `finally` (hanya bila proses sudah terkonfirmasi mati, dengan cek identitas yang sama), sehingga jalur interupsi membersihkan juga tanpa mengubah perilaku tunggu.


## [Belum rilis] — Pintasan sidik CA health-check dibatasi umurnya (Audit 844)
- Sisa Audit 805: `capCaAktif` memercayai sidik cache selamanya bila `mtime+panjang` berkas CA sama, dan tiap pemeriksaan menyegarkan cap waktunya — dua CA berbeda yang kebetulan sama panjang dan sama mtime terbaca (resolusi kasar, mis. FAT) membuat health-check mem-pin CA lama selamanya. Kini pintasan stat hanya dipercaya sampai `CAP_CA_BACA_ISI_MAKS_MS` (10 menit) sejak isi benar-benar dibaca; sesudah itu isi berkas dibaca dan di-hash ulang walau stat sama. Keputusan itu fungsi murni `perluBacaIsiCa` dengan uji JVM.


## [Belum rilis] — Hook pre-push memeriksa rentang per-ref sampai sha lokalnya (Audit 843)
- Sisa Audit 816: template hook di `tools/pasang-hook.sh` hanya memakai ref pertama dan membentuk rentang `sha-remote..HEAD`, sehingga push multi-ref tak memeriksa ref lain dan push yang tip-nya bukan HEAD memeriksa isi yang salah. Kini hook mengumpulkan rentang `sha-remote..sha-lokal` untuk SETIAP baris stdin pre-push dan menjalankan gerbang untuk masing-masing; baris ber-sha-remote nol (branch baru) tetap jatuh ke mode lokal seperti sebelumnya. Terbukti di workspace buang: rentang bersih lolos, rentang dua commit yang memuat zip terlarang gagal dan menyebut berkasnya.


## [Belum rilis] — Dokumentasi gerbang diluruskan sesudah Audit 830/831 (Audit 842)
- `AGENTS.md` dan nama langkah di tiga workflow masih menyebut cek-cepat memeriksa "ID land" — cek layout/ID sudah pindah satu pintu ke verifikasi-patch (Audit 831) — dan tak mencantumkan `tools/uji-cek-cepat.py` sama sekali. Keterangan cek-cepat kini sesuai isinya (vektor 0.x, README, pola rahasia), uji-cek-cepat tercantum di struktur repo dan daftar gerbang CI, dan nama langkah workflow diselaraskan.


## [Belum rilis] — Uji cek-cepat kini mengunci cabang token terbelah (Audit 841)
- Sisa Audit 830: satu-satunya skenario rahasia di `tools/uji-cek-cepat.py` menanam token satu baris yang sudah tertangkap `git grep` lebih dulu, jadi cabang bungkus (`tr -d` menggabungkan baris) di cek-cepat tak pernah terbukti hidup. Skenario baru menanam token Telegram yang sama terbelah dua baris dan mengasersi cek-cepat gagal karenanya; docstring skrip diperbarui menyebut cabang itu.


## [Belum rilis] — Gerbang memvalidasi id langkah internal action resolve-vw (Audit 840)
- Sisa Audit 814: mengganti id langkah internal di `.github/actions/resolve-vw/action.yml` (`vw` → nama lain) lolos gerbang karena ekspresi `value:` pada `outputs` action tak pernah diperiksa — kedua keluaran action akan kosong di runtime. Kini gerbang mengumpulkan id langkah di `runs.steps` action dan menggagalkan setiap rujukan `steps.<id>.outputs.*` di nilai keluaran yang id-nya tak terdefinisi. Skenario uji-diri baru menguncinya.


## [Belum rilis] — Enumerasi CA sistem yang terpotong tak lagi tercache permanen (Audit 839)
- Sisa Audit 811: ambang `HttpsCompat.trustSistemCukup` hanya `> 0`, sehingga enumerasi `AndroidCAStore` yang terpotong di tengah (1–2 CA berhasil, sisanya gagal senyap) tetap dianggap berhasil dan factory miskin-CA itu tercache seumur proses — unduhan GitHub jalan tetapi host lain (Telegram) gagal TLS terus. Ambang kini berupa lantai `LANTAI_CA_SISTEM` = 50 (store Android utuh berisi jauh di atas 100 CA); di bawah lantai pembangunan melempar dan diproses mekanisme jeda-bangun-ulang/fallback bawaan seperti enumerasi nol. Uji JVM diperbarui ke batas baru.


## [Belum rilis] — Hasil baca sertifikat yang gagal tidak lagi dimemoisasi (Audit 838)
- `TlsCert.sisaMsSekali` menyimpan hasil `bacaMasa` ke memo 60 detik tanpa membedakan sukses/gagal, sehingga satu kegagalan baca sesaat (I/O tersendat, berkas sedang ditukar) membuat sertifikat sehat dinilai "tak terbaca" selama semenit — retry 100 ms pun membaca memo yang sama — dan Start dalam jendela itu bisa mengganti CA yang sehat. Kini hanya bacaan sukses yang dimemoisasi; kegagalan selalu dibaca ulang dari berkas. Uji JVM baru menguncinya dengan berkas berstat identik (panjang + mtime sama) yang isinya berubah dari sampah menjadi sertifikat sah.


## [Belum rilis] — Fsync isi web-vault menjadi best-effort seperti situs sync lain (Audit 837)
- Fsync per berkas saat ekstrak web-vault (Audit 828) adalah satu-satunya `getFD().sync()` isi yang tidak dibungkus penelan galat: `SyncFailedException` dari filesystem tujuan (mis. folder data di SD/FUSE) merambat ke cabang umum, staging dibuang, dan seluruh update web-vault gagal padahal isi sudah tertulis utuh — dan selalu gagal lagi di titik yang sama. Kini fsync itu best-effort (`catch (Exception ignored)`) seperti semua situs sync isi lain di repo ini; kegagalan tulis yang sebenarnya tetap tertangkap pemeriksaan ukuran/batas unzip sesudahnya.


## [Belum rilis] — Gerbang menangkap id langkah workflow yang diganti tanpa menyelaraskan rujukan (Audit 836)
- Sisa Audit 814: mengganti `id:` langkah pemakai resolve-vw di workflow (rujukannya tetap) lolos gerbang karena kontrak hanya memvalidasi rujukan untuk id yang masih cocok. Kini gerbang juga mengumpulkan semua id langkah yang terdefinisi di tiap workflow pemakai dan menggagalkan setiap rujukan `steps.<id>.outputs.*` yang id-nya tak terdefinisi di berkas itu. Skenario uji-diri baru (id `ambil` diganti) menguncinya.


## [Belum rilis] — Gerbang memaku versi AGP persis & menolak repositori asing (Audit 835)
- Sisa Audit 815: regex gerbang menerima versi AGP terpaku apa pun dan keberadaan repositori asing di settings lolos. Kini `tools/verifikasi-patch.py` memaku versi persis lewat konstanta `VERSI_AGP_TERPAKU` (mengganti versi AGP wajib menyelaraskan konstanta itu pada commit yang sama) dan memvalidasi isi setiap blok `repositories` di `settings.gradle.kts` terhadap daftar izin `google()`/`mavenCentral()`/`gradlePluginPortal()`. Dua skenario uji-diri baru menguncinya.


## [Belum rilis] — Baris CODEOWNERS yang tak terjangkau dihapus (Audit 834)
- Baris pertama `.github/CODEOWNERS` (`* @tasirin1`) sudah mencakup semua berkas, sehingga baris `.github/ @tasirin1` tidak pernah menjadi penentu kepemilikan (pemiliknya pun sama). Baris mati itu dihapus agar cakupan kepemilikan tidak membingungkan pembaca; perilaku peninjauan tidak berubah.


## [Belum rilis] — Tiga keterangan basi AGENTS.md tentang workflow diluruskan (Audit 833)
- AGENTS.md adalah hukum kerja repo ini, tetapi tiga keterangannya tak lagi cocok dengan workflow: (1) `assembleRelease` ditulis "(signed bila secrets ada)", padahal build gagal keras bila satu saja dari 4 secret signing kosong — tidak ada jalur APK unsigned di `main`; (2) web-vault ditulis "hanya bila belum ada di rilis", padahal selalu diunduh ulang dan diverifikasi digest-nya, dan baru dibangun ulang dari Docker bila zip rilis korup/beda digest; (3) idempotensi unggahan ditulis "(hapus duplikat per ID + coba ulang)", padahal mekanismenya `--clobber` dengan coba ulang tersaring galat sementara. Sekalian diluruskan: keterangan pemicu gerbang di `build-apk.yml` yang masih menyebut pengecualian dokumen, menyusul perubahan daftar-putih Audit 817.


## [Belum rilis] — Retry unggah APK kini menyaring galat permanen, sama seperti build-binary (Audit 832)
- Langkah publish `build-apk.yml` sebelumnya mengulang semua kegagalan `gh release upload` sampai 6 kali dengan jeda 15 detik — termasuk galat permanen (401/403 otorisasi, validasi) yang pasti gagal lagi, membuang ±75 detik + waktu `gh` per kejadian. Logika unggahnya kini disamakan persis dengan fungsi `unggah_asset` di `build-binary.yml`: log galat ditangkap, hanya pola sementara (`already exists|validation failed|502|503|504|timeout|EOF|connection reset`) yang diulang, galat lain langsung gagal.


## [Belum rilis] — Cek layout dan berkas biner/nama kini satu pintu di verifikasi-patch (Audit 831)
- Paritas ID layout + layout-land yatim + layout wajib dan deteksi berkas biner/nama terlarang sebelumnya diimplementasikan dua kali — di `verifikasi-patch.py` dan di `scripts/cek-cepat.sh` — padahal keduanya selalu berjalan bersamaan (hook dan CI), dan kedua salinan sudah mulai drift (ekstraktor ID cek-cepat tak setara antarjalur rg/grep-nya sendiri; daftar ekstensi terlarang berbeda bentuk). Kini kepemilikannya satu pintu: `verifikasi-patch.py` — yang memang mem-parse XML dan memeriksa magic berkas — memiliki cek layout dan berkas (daftar nama terlarangnya disamakan dengan cakupan lama cek-cepat: tambah `.zip/.p12/.pfx/.so/.a`, `.env`, `vw_version.txt`, komponen `keystore`, dan nama binary/shim), sedangkan cek-cepat mempertahankan miliknya sendiri (pola rahasia termasuk token terbelah, vektor tanpa nol depan, struktur README). Skenario uji-diri berpindah mengikuti kepemilikan: layout-land yatim dan biner menyamar kini skenario `uji-verifikasi.py`, dan `uji-cek-cepat.py` mengunci cabang sisanya.


## [Belum rilis] — cek-cepat kini punya uji-diri untuk cabang deteksinya (Audit 830)
- `scripts/cek-cepat.sh` sebelumnya tanpa uji-diri sama sekali: di CI ia hanya pernah berjalan pada pohon bersih, jadi cabang deteksinya (berkas terlarang, biner menyamar, layout-land yatim, pola rahasia, vektor tanpa nol depan) tidak pernah terpicu dan regresi pola/regex bisa mengubahnya menjadi cap lolos exit-0 secara diam-diam. Skrip baru `tools/uji-cek-cepat.py` menanam pelanggaran pada salinan buang dan mengasersi kelima cabang itu tertangkap (plus kontrol pohon bersih), dan dijalankan sebagai langkah CI di `cek-wajib.yml` dan job verifikasi `build-apk.yml` — berkasnya juga masuk daftar-putih pemicu workflow itu.


## [Belum rilis] — Jalur eksplisit yang tak ada kini menggagalkan gerbang, bukan dibuang diam-diam (Audit 829)
- Mode jalur eksplisit `verifikasi-patch.py` sebelumnya menyaring argumen dengan `os.path.exists` tanpa sisa: satu nama berkas yang salah ketik atau sudah dihapus menghasilkan `Hasil: LOLOS (0 berkas diperiksa)` dengan kode keluar 0 — kepastian palsu bagi maintainer yang justru ingin memeriksa berkas yang baru dihapus/di-rename. Kini setiap jalur yang diminta tetapi tak ada dilaporkan sebagai kegagalan yang menyebut jalurnya (cek penghapusan tetap berjalan lewat mode diff/rentang seperti sebelumnya). Dikunci sebagai skenario baru di `tools/uji-verifikasi.py`.


## [Belum rilis] — Isi berkas web-vault kini ikut di-fsync sebelum swap (Audit 828)
- Pemasangan web-vault sebelumnya hanya mem-fsync direktori staging (dan data folder) sebelum direktori ditukar masuk, tetapi isi tiap berkas hasil unzip tidak pernah di-fsync — menyimpang dari jalur binary/penanda versi di repo ini yang selalu mem-fsync isi sebelum rename. Mati listrik tepat sesudah swap bisa menyisakan entri direktori yang awet dengan isi berkas kosong/robek, dan pemeriksaan runtime hanya memastikan `index.html` ada dan tak kosong. Kini setiap berkas hasil unzip di-`getFD().sync()` sebelum ditutup.


## [Belum rilis] — syncDirAman di Updater disatukan ke TlsCert.fsyncDirektori (Audit 827)
- `Updater.syncDirAman` adalah salinan kedua persis dari `TlsCert.fsyncDirektori` (buka direktori + `Os.fsync` + tutup, semua galat ditelan) — melanggar prinsip satu pintu repo, dan pola pintunya sudah dipakai lintas kelas (`ServerService.writeText` memanggil `TlsCert.fsyncDirektori` langsung). Tiga situs panggil di Updater kini memakai pintu yang sama dan salinannya dihapus (namanya dicatat di `tools/bekas-hapusan.txt` agar tak dipakai ulang). Perilaku tidak berubah.


## [Belum rilis] — sidikBerkas di Updater kini menumpang sha256Hex (Audit 826)
- `Updater` sebelumnya memuat dua implementasi hash berkas yang sama: `sidikBerkas` (buffer 8 KB, galat menjadi `""`) dan `sha256Hex` (buffer 64 KB, galat menjadi `null`) — perbaikan pada satu salinan mudah tidak menjalar ke salinan lain, dan perbedaan sentinelnya mengundang salah pakai. `sidikBerkas` kini delegasi tipis ke `sha256Hex` dengan sentinel `""`-nya dipertahankan persis untuk pemanggil penanda zip, jadi perilakunya tidak berubah; satu-satunya implementasi hash berkas di Updater tinggal `sha256Hex`. (`SettingsActivity.sidikBerkas` sengaja tidak disentuh: semantik cadangannya berbeda — sidik banding impor dengan penanda `baca-gagal-…`.) Dikunci uji JVM baru.


## [Belum rilis] — Field Chat ID Telegram kini saveEnabled=false seperti field kredensial lain (Audit 825)
- Semua field rahasia di kartu Telegram/Keamanan sebelumnya dilindungi `android:saveEnabled="false"` (token bot, kata sandi backup, token admin, PIN) kecuali field Chat ID (`@+id/tgChat`) — padahal chat ID diperlakukan sebagai kredensial di tempat lain (penyamaran log punya pola khusus untuknya). Isi field itu kini tidak lagi ikut tersimpan ke Bundle hierarki view saat activity disimpan sistem, menyamakan hardening-nya dengan field tetangganya.


## [Belum rilis] — Teks log layar-penuh kini memakai dimen adaptif TV (Audit 824)
- `activity_log.xml` sebelumnya meng-hardcode `textSize="11sp"` untuk teks log, mengabaikan dimen `log_text_size` yang memang dibuat berjenjang untuk layar besar (11sp bawaan, 13sp di sw600dp, 14sp di sw480dp-land, 15sp di sw600dp-land) dan sudah dipakai benar oleh log di layar utama. Layar log layar-penuh — layar yang paling untuk dibaca dari jauh di TV/STB — kini memakai `@dimen/log_text_size` yang sama, jadi di perangkat lebar teksnya ikut membesar seperti di beranda.


## [Belum rilis] — Dua teks UI hardcode dipindah ke resource (Audit 823)
- Toast hasil pasang versi terpilih sebelumnya menempelkan literal Inggris `" Press Start to use it."`, padahal resource-nya sudah ada dan dipakai benar di jalur update lain pada berkas yang sama (`R.string.update_press_start`, terjemahan Indonesia "Tekan Start untuk memakainya."). Sebaliknya toast restore menyisipkan literal Indonesia `"checkpoint WAL gagal"` sebagai argumen string berformat yang sudah terlokalisasi, sehingga pengguna locale Inggris menerima pesan campur. Keduanya kini lewat resource: jalur pertama memakai `update_press_start` yang sudah ada, jalur kedua memakai string baru `restore_checkpoint_gagal` di `values` dan `values-in`.


## [Belum rilis] — Offset semua callback tombol kini di-commit sebelum eksekusi (Audit 822)
- Pra-`commit()` offset sebelum eksekusi sebelumnya hanya berlaku untuk callback `careset`/`restore` (dan alur PIN pada pesan teks). Callback berat lain — `backup`, `update`, `restart`, `stop` saat PIN mati — hanya tercakup `apply()` di akhir putaran poll: bila proses mati (listrik STB padam) sesudah aksi tereksekusi tetapi sebelum antrean tulis disk `apply()` ter-flush, Telegram mengirim update yang sama pada poll berikutnya dan aksi berat itu tereksekusi ulang (backup/unggah ganda, restart ganda). Kini offset setiap callback tombol di-`commit()` sebelum `tanganiCallback`, menyamakan semantik at-most-once yang sudah diterima untuk `careset`/`restore`; tombol baca-saja (status/log) ikut pola yang sama agar aturannya seragam — pengguna cukup menekan lagi bila proses mati di tengah.


## [Belum rilis] — Backup Telegram tak lagi membayar quick_check penuh di DB live (Audit 821)
- Awal `createBackupZip` versi Telegram sebelumnya menjalankan `cekIntegritasDbUlang` atas database live: satu `quick_check` penuh ekstra per backup di STB kelas rendah, padahal salinan yang benar-benar dikirim sudah diperiksa tiga lapis sesudahnya — kestabilan + integritas snapshot di `snapshotDbKonsisten` (dengan coba-ulang sibuknya sendiri yang lebih sabar), verifikasi zip, dan verifikasi isi DB di dalam zip. Pemeriksaan live itu tidak menambah jaminan atas artefak terkirim (DB live bisa berubah sedetik sesudahnya) dan justru bisa menggagalkan backup lebih awal karena kesibukan sesaat. Bloknya dihapus dari jalur Telegram; `cekIntegritasDbUlang` tetap dipakai backup lokal di Settings yang memang menyalin DB live tanpa tahap snapshot.


## [Belum rilis] — Kirim CA kini terisolasi per-chat dan memakai ulang file_id (Audit 820)
- `kirimCa` sebelumnya mengunggah CA dengan satu loop tanpa isolasi: satu chat rusak (bot diblokir/chat berubah) menggagalkan seluruh perintah `/ca`, chat sesudahnya tak pernah dicoba, dan pengguna menerima "Kirim CA gagal" padahal chat sebelumnya sudah menerima CA — ditambah unggahan multipart penuh berulang ke tiap chat. Jalurnya kini meniru jalur backup (Audit 793/795): kegagalan diisolasi per-chat dan dilaporkan sebagai sukses sebagian di pesan hasil, kegagalan di semua chat barulah galat, dan chat lanjutan menerima CA yang sama lewat `file_id` hasil unggahan pertama (jatuh kembali ke unggahan penuh per-chat bila by-id gagal, dengan jejak di log).


## [Belum rilis] — Shim: fd nonblocking kini di-cache seperti jalur blocking (Audit 819)
- `isi_dari_nonblok` sebelumnya membuka dan menutup `/dev/urandom`/`/dev/random` pada setiap panggilan `getrandom` berbendera `GRND_NONBLOCK`/`GRND_INSECURE` (jalur yang dipakai runtime Rust/TLS untuk handshake dan nonce), membayar `open`+`close` + lookup path per panggilan di kernel STB lama. Jalur nonblocking kini meng-cache fd per sumber dengan mutex per sumber — pola yang sama persis dengan jalur blocking — termasuk pemulihan EBADF terbatas (maks 3 kali per panggilan) yang kini diperlukan karena fd berumur panjang. Semantik hasil tidak berubah: EAGAIN sesudah terisi sebagian tetap mengembalikan jumlah yang terisi, dan galat lain diteruskan apa adanya.


## [Belum rilis] — Entri penunggu proses kini dibuang sesudah proses mati (Audit 818)
- Pada jalur pra-API 26, `waitForOrKill` mendaftarkan tiap `Process` ke `WeakHashMap` `PENUNGGU_PROSES`, tetapi nilainya memegang thread penunggu yang menangkap proses itu sendiri — rantai map → nilai → thread → kunci membuat entri tak pernah ter-expunge, jadi peta (dan objek Thread mati) tumbuh monoton seumur proses aplikasi di STB lama yang jarang reboot. Entri kini dibuang segera sesudah proses terkonfirmasi mati (dengan pemeriksaan identitas di dalam kunci agar entri pengganti tak ikut terhapus); proses yang masih hidup tetap berbagi satu penunggu seperti sebelumnya. Dikunci uji JVM: jumlah penunggu kembali ke awal sesudah proses mati.


## [Belum rilis] — Pemicu build-apk kini daftar-putih: perubahan tooling murni tak lagi membangun + menerbitkan ulang APK (Audit 817)
- `build-apk.yml` sebelumnya memakai daftar-hitam `paths-ignore` (hanya `*.md`, `.gitignore`, `shim/**`, `build-binary.yml`), sehingga workflow jalan bila ada satu saja berkas lain yang berubah — termasuk perubahan yang sama sekali tidak menyentuh APK: `9591007` (hanya `.github/labeler.yml`), `e7a7f1b` (hanya `codeql.yml` + changelog), dan `95e8182` (action `resolve-vw` + `build-binary.yml`) semuanya membayar build penuh ±4 menit, penandatanganan, dan penggantian aset APK di rilis yang sama — membantah alasan anti-churn `versionCode` yang ditulis AGENTS.md sendiri. Pemicu kini daftar-putih `paths:` (meniru `build-binary.yml`): `app/**`, berkas Gradle akar + wrapper, ketiga berkas gerbang yang dipakai job verifikasi workflow ini, workflow ini sendiri, dan `resolve-vw` (keluarannya memang masuk APK sebagai `vw_version.txt`). PR di luar daftar tetap aman: check wajib "Cek patch yang berubah" dilaporkan `cek-wajib.yml` yang berjalan untuk semua PR (Audit 638). Deskripsi di AGENTS.md diselaraskan.


## [Belum rilis] — Mode lokal gerbang tak lagi menyempit ke satu commit; hook memakai rentang push yang sebenarnya (Audit 816)
- Mode lokal `verifikasi-patch.py` sebelumnya memakai diff `HEAD~1...HEAD` bila branch tak punya upstream: pada push pertama berisi banyak commit, cek berbasis diff (metode terlarang `bekas-hapusan`, locale baru, metode hilang) hanya melihat commit terakhir. Cadangannya kini berlapis seperti fungsi daftar hapus di berkas yang sama: `@{upstream}` → `origin/main` → barulah `HEAD~1`. Selain itu hook pre-push yang dipasang `pasang-hook.sh` sebelumnya membuang seluruh masukan pre-push (baris ref di stdin memuat SHA remote); hook kini membaca SHA remote pertama yang bukan nol dan menjalankan gerbang dengan `--rentang <sha-remote>..HEAD` — persis rentang yang akan di-push — dan jatuh ke mode lokal hanya untuk branch baru. Terbukti di workspace buang: hook terpasang, `--self-test` lolos, dan pemanggilan dengan baris ref simulasi menjalankan gerbang rentang dengan benar.


## [Belum rilis] — Isi berkas Gradle akar kini dijaga gerbang (Audit 815)
- Satu-satunya berkas Gradle yang dibaca isinya oleh gerbang sebelumnya adalah `app/build.gradle.kts`. Tiga invariant akar bisa berubah tanpa sinyal apa pun (terbukti pada salinan buang, semuanya LOLOS): `settings.gradle.kts` dilonggarkan dari `FAIL_ON_PROJECT_REPOS` ke `PREFER_PROJECT` (proyek bisa menimpa repositori yang dipaku settings — pelemahan rantai pasok), `gradle.properties` diubah ke `android.useAndroidX=true`, dan versi plugin AGP di `build.gradle.kts` akar diganti sembarangan. Pemeriksaan baru `cek_gradle_akar` memaku ketiganya: plugin `com.android.application` wajib berversi terpaku + `apply false`, `repositoriesMode` wajib `FAIL_ON_PROJECT_REPOS` dengan `google()`+`mavenCentral()` tetap terdaftar, dan `android.useAndroidX` wajib `false`; berkas yang hilang dilaporkan dengan pesan sesuai fungsinya, bukan lagi efek samping glob labeler. Dikunci sebagai skenario baru di `tools/uji-verifikasi.py`.


## [Belum rilis] — Kontrak keluaran resolve-vw kini divalidasi gerbang (Audit 814)
- Nama keluaran action komposit `resolve-vw` (`tag`, `tag_mentah`) sebelumnya tidak diperiksa silang dengan rujukan `steps.ambil.outputs.*` di kedua workflow: mengganti nama satu keluaran di `action.yml` saja membuat gerbang tetap LOLOS (terbukti pada salinan buang), dan kegagalannya baru muncul jauh di hilir — `build-binary` gagal di langkah ambil sumber sesudah memasang rustup + NDK, atau `build-apk` menulis `vw_version.txt` kosong. Gerbang kini mem-parse keluaran yang dideklarasikan action dan semua rujukan `steps.<id>.outputs.<nama>` dari langkah pemakai action di `build-apk.yml`/`build-binary.yml`, dan gagal bila ada rujukan tanpa deklarasi. Dikunci sebagai skenario baru di `tools/uji-verifikasi.py` (rename `tag_mentah` harus membuat gerbang gagal).


## [Belum rilis] — Keberadaan workflow & berkas CI wajib kini dijaga satu per satu oleh gerbang (Audit 813)
- `cek_workflow_yaml` sebelumnya hanya memvalidasi berkas yang masih ada di `.github/workflows/`; menghapus satu workflow (mis. `gitleaks.yml` atau `cek-wajib.yml` karena konflik merge) membuat seluruh automasi itu mati tanpa satu pun sinyal — terbukti pada salinan buang, penghapusan `gitleaks.yml` tetap `LOLOS (164 berkas)`. Folder `.github/actions/` pun hanya tertangkap tak langsung lewat efek samping pola glob labeler. Gerbang kini memuat daftar `WORKFLOW_WAJIB` (9 workflow) dan `BERKAS_CI_WAJIB` (`resolve-vw/action.yml`) yang keberadaannya diperiksa satu per satu dengan pesan yang menyebut konsekuensinya; menghapus workflow secara sadar kini harus mengubah daftar itu di commit yang sama. Dikunci sebagai skenario baru di `tools/uji-verifikasi.py`.


## [Belum rilis] — Nama berkas non-ASCII tak lagi lolos kedua gerbang (Audit 812)
- `git diff --name-only`/`ls-files` secara bawaan mengutip nama berkas non-ASCII menjadi bentuk oktal bertanda kutip (`"data-\303\274.zip"`), sehingga path itu tidak ada di disk dan dilewati diam-diam oleh cek ekstensi terlarang, magic biner, dan ukuran di `verifikasi-patch.py`, dan pola akhiran di `cek-cepat.sh` tidak cocok karena baris berakhiran `.zip"`. Satu berkas `.zip`/biner bernama non-ASCII dengan isi terlarang bisa lolos kedua gerbang CI (mode `--rentang`). Kini semua panggilan git di gerbang berjalan dengan `core.quotepath=false` (disisipkan terpusat di penolong `jalan()`), dan panggilan git pencatat nama di `cek-cepat.sh` memakai bendera yang sama. Terbukti dua arah pada salinan buang (zip ASCII dan non-ASCII sama-sama tertangkap) dan dikunci permanen sebagai skenario baru di `tools/uji-verifikasi.py`.


## [Belum rilis] — Trust store terdegradasi tak lagi disimpan permanen oleh HttpsCompat (Audit 811)
- `bangunFactory` sebelumnya menelan semua kegagalan pemuatan `AndroidCAStore` dan tetap menyimpan factory hasilnya ke cache permanen, tanpa memeriksa berapa sertifikat sistem yang benar-benar masuk. Bila enumerasi CA sistem diam-diam menghasilkan nol sertifikat pada perangkat lama, factory yang dipercaya semua unduhan dan jalur Telegram hanya berisi rantai GitHub seumur proses: koneksi ke host lain (mis. api.telegram.org) gagal TLS terus sampai aplikasi mati, tanpa pernah dicoba bangun ulang. Kini jumlah CA sistem yang terpasang dihitung; bila nol, pembangunan dinyatakan gagal (keputusan murni `trustSistemCukup`, dikunci uji JVM) sehingga mekanisme jeda-dan-coba-lagi yang sudah ada yang berlaku, bukan cache permanen yang rusak.


## [Belum rilis] — Reset Sertifikat kini ikut menghapus folder TLS tercatat dan kuncinya (Audit 810)
- Penanda folder TLS aktif sesi (`tls_dir`) sebelumnya tidak pernah dibersihkan di mana pun, dan `resetSertifikat` hanya menghapus folder internal + folder data saat ini. Bila folder data pernah diganti sesudah Start, folder TLS sesi terakhir (mis. folder data lama dari sesi fallback) tak tersentuh reset dan penandanya tetap ada: `/ca` atau backup CA sebelum Start berikutnya lalu mengirim CA pra-reset yang konon sudah dibatalkan. Reset kini juga mereset folder yang tercatat pada penanda itu dan menghapus penandanya, sehingga sesudah reset jalur kirim kembali ke keadaan "CA belum ada" sampai Start membuat CA baru. Penanda sengaja TIDAK dihapus saat Stop atau saat folder data diganti — sampai Start berikutnya, folder tercatat memang masih folder CA yang benar-benar dipakai sesi terakhir.


## [Belum rilis] — Tulis-balik upgrade hash PIN kini berkunci bersama dengan ganti PIN (Audit 809)
- Tulis-balik hash sesudah verifikasi PIN berhasil (jalur bot di `TgBot` dan layar kunci di `PinActivity`) sebelumnya berpola cek-lalu-tulis tanpa kunci: nilai dibaca, hash baru dihitung (PBKDF2 30.000 iterasi), lalu ditulis — sementara penulisan ganti/aktivasi PIN di Settings memakai kunci lokalnya sendiri atau tanpa kunci, dan migrasi dini `PinGate.kuatkanHashDini` memakai kunci ketiga (`PinGate.class`). Bila ganti PIN selesai di celah itu, hash PIN lama hasil upgrade menimpa hash PIN baru: pengguna mengira PIN sudah berganti padahal PIN lama yang tetap berlaku. Kini semua penulisan hash PIN dari jalur-jalur itu memegang kunci bersama `PinGate.KUNCI_PERCOBAAN` (urutan kunci Settings tetap lokal → bersama, tak ada urutan terbalik di mana pun), hash baru dihitung di luar kunci agar kunci tak tertahan lama, dan nilai tersimpan diperiksa ulang di dalam kunci sebelum ditulis. Penulisan dari restore konfigurasi tidak berubah (operasi eksklusif berpenanda restore).


## [Belum rilis] — Rotasi tak lagi membatalkan konfirmasi restore Telegram (Audit 808)
- `confirm()` di Settings menjalankan callback batal untuk semua dismiss yang bukan pilihan tombol, termasuk dismiss karena rotasi/perubahan konfigurasi (dialog ditutup `DialogAman` dari `onDestroy`). Untuk restore dari Telegram, callback batal itu menghapus berkas backup yang sudah selesai diunduh: operasi hilang tanpa jejak dan pengguna harus mengunduh ulang. Dua perbaikan: listener dismiss kini mengabaikan dismiss saat `isChangingConfigurations()` — meniru pola dialog kata sandi impor (Audit 592) — dan konfirmasi restore Telegram kini dicatat sebagai tertunda (`restore_tg_path`/`restore_tg_nama` di `savedInstanceState`) lalu ditampilkan lagi oleh instance baru, juga meniru jalur impor. Pembatalan sungguhan tetap menghapus berkas temp seperti sebelumnya.


## [Belum rilis] — Sinkron TLS sesudah restore kini hanya berjalan bila TLS backup benar-benar terpasang (Audit 807)
- `sinkronTlsKeInternal` sebelumnya dipanggil tanpa syarat di akhir kedua jalur restore (bot dan lokal). Padahal folder `tls` di folder data secara desain masih menyimpan sisa publik `ca.pem`/`cert.pem` setelah migrasi ke internal, jadi bila TLS dari backup ditolak, tidak ada, atau gagal dipasang, sinkron itu tetap menimpa set internal yang aktif dengan set folder data yang basi — sementara kunci internal tetap baru. Pada Start berikutnya pasangan yang campur itu terdeteksi tidak cocok dan CA dibuat ulang: semua HP klien harus memasang ulang CA, padahal pesan restore menyatakan TLS perangkat dipertahankan. Sinkron kini digerbang keputusan murni baru `perluSinkronTlsSesudahRestore` (ada staging + siap pasang + pasang berhasil) di kedua jalur; keputusan itu dikunci uji JVM.


## [Belum rilis] — Backup kini mengemas set TLS dari folder yang benar-benar aktif (Audit 806)
- `createBackupZip` sebelumnya mengambil `ca.pem`/`cert.pem` (dan kunci bila terenkripsi) dari folder yang dipilih `tlsAktifUntukBackup` berdasar keberadaan — internal selalu menang bila `ca.pem`-nya ada — sisa lubang Audit 804 di jalur backup. Pada sesi darurat yang sama (server berjalan memakai TLS folder data), backup justru mengemas set internal yang tidak aktif; karena set itu konsisten dengan dirinya sendiri, pemeriksaan pasangan saat restore lolos dan CA yang salah terpasang diam-diam. Pemilihan folder kini memakai keputusan murni baru `tlsAktifUntukBackup(tercatat, internal, data)`: folder TLS aktif sesi yang tercatat (`tls_dir`) didahulukan, pemilihan berdasar keberadaan hanya menjadi cadangan. Perilaku sesi normal tidak berubah; keputusan itu dikunci uji JVM.


## [Belum rilis] — Health check kini mem-pin CA dari folder TLS yang benar-benar aktif (Audit 805)
- `capCaAktif` dan `cobaPinnedCa` di `ServerService` sebelumnya memilih CA berdasar keberadaan (internal selalu menang bila ada) — sisa lubang dari perbaikan Audit 804 yang hanya menjangkau jalur kirim/backup CA. Pada sesi darurat yang sama (internal gagal menyiapkan TLS sehingga server berjalan memakai TLS lama di folder data), health check bisa mem-pin CA internal yang basi: handshake `/alive` gagal terus, server yang sehat di-restart berulang sampai episodenya diakhiri. Kedua situs kini memilih CA lewat penolong baru `caUntukHealthCheck` yang memakai keputusan murni yang sama dengan jalur kirim (`TgBackup.pilihCaKirim`): catatan folder TLS aktif sesi (`tls_dir`) didahulukan, pemilihan berdasar keberadaan hanya menjadi cadangan. Perilaku sesi normal tidak berubah; penolong itu dikunci uji JVM untuk konteks null.


## [Belum rilis] — Kirim dan backup CA kini mengikuti folder TLS yang benar-benar aktif (Audit 804)
- Perintah /ca dan backup CA ke storage sebelumnya memilih berkas CA murni berdasar keberadaan: berkas internal selalu menang bila ada. Pada sesi darurat yang langka — penyimpanan internal gagal total saat Start sehingga server berjalan memakai TLS lama di folder data apa adanya — pilihan itu bisa mengirim atau membackup CA yang sedang tidak dipakai server, dan HP lain yang memasangnya akan gagal memercayai server. Folder TLS yang benar-benar dipakai kini dicatat saat Start (`tls_dir`, perangkat-lokal dan tidak ikut diekspor dalam config), dan kedua jalur memilih CA lewat keputusan murni baru `pilihCaKirim`: catatan sesi didahulukan, pemilihan berdasar keberadaan hanya menjadi cadangan. Perilaku sesi normal tidak berubah; keputusan itu dikunci uji JVM.

## [Belum rilis] — Rantai rilis kini toleran tag upstream berawalan v (Audit 803)
- `resolve-vw` sebelumnya menolak tag yang tidak persis berbentuk `x.y.z`, padahal seluruh sisi aplikasi menormalkan satu awalan `v`/`V`: bila upstream `dani-garcia/vaultwarden` suatu hari beralih ke tag bergaya `v1.33.0`, kedua workflow akan berhenti keras di langkah resolve dan tidak ada rilis baru sama sekali. Action itu kini menormalkan tag (satu awalan `v`/`V` dibuang sebelum validasi, meniru `normalisasiPinVersi` di aplikasi) dan mengeluarkan bentuk ternormalisasi untuk penamaan rilis dan `vw_version.txt`, plus keluaran `tag_mentah` berisi tag persis upstream untuk dua pemakai yang memang butuh ref asli (clone sumber dan URL mentah DockerSettings di `build-binary.yml`). Untuk tag polos hari ini tidak ada perubahan perilaku.

## [Belum rilis] — Penanda versi dan daftar IP kini ditulis atomik (Audit 802)
- `writeText` sebelumnya menulis `bin/version.txt` dan `tls/ips.txt` langsung di tempat (truncate) — satu-satunya penulis penanda yang tidak memakai pola atomik repo. Kini penulisannya lewat berkas staging di folder yang sama, fsync, lalu ditukar masuk dengan rename (meniru `TlsCert.writeVersion` dan `Updater.writeVersionTag`): mati listrik di tengah penulisan tak lagi meninggalkan berkas robek yang memicu refresh binary atau regenerasi sertifikat daun ekstra pada start berikutnya.

## [Belum rilis] — Blok rollback pasang versi disatukan (Audit 798)
- `pasangVersiDipilih` sebelumnya menjalankan dua blok sibuk yang hampir sama dengan blok rollback pin terduplikasi verbatim di kedua catch-nya — dan salinan cabang web-vault memuat lengan kondisi yang tak pernah bisa hidup. Kini satu blok sibuk bersama dengan rollback yang ditulis satu kali; perilaku sukses dan gagal kedua jalur tidak berubah.

## [Belum rilis] — Label pin Settings disegarkan setiap kali halaman dibuka kembali (Audit 797)
- Pin versi yang diganti atau dilepas dari bot Telegram selagi Settings berada di latar sebelumnya tampil basi pada tombol versi sampai activity dibuat ulang. `onResume` kini menyegarkan label pin, jadi yang tampil selalu pin yang benar-benar aktif.

## [Belum rilis] — Kegagalan memuat daftar versi kini terlihat oleh pemilik (Audit 796)
- `daftarVersiTersedia` sebelumnya menelan semua kegagalan dan mengembalikan daftar kosong, sehingga dialog pemilih versi saat offline tampil seolah repo hanya punya versi terbaru — toast peringatan yang memang disiapkan untuk keadaan itu tak pernah terpicu. Fungsi itu kini mengembalikan null bila muatan gagal (non-200, galat jaringan, atau badan tak terbaca), dan dialog menampilkan peringatan itu sambil tetap membuka pilihan Terbaru, pin aktif, dan isi manual.

## [Belum rilis] — Kegagalan kirim backup by-id kini tercatat di log (Audit 795)
- Bila pengiriman ulang backup lewat `file_id` gagal dan jalur jatuh kembali ke unggahan penuh, kegagalannya kini ditulis ke log aplikasi beserta tujuannya — sebelumnya catch-nya menelan galat tanpa jejak, sehingga kemunduran jalur cepat (mis. `file_id` mulai ditolak) tak akan pernah terlihat penyebabnya.

## [Belum rilis] — Keputusan pin prerelease tak lagi menilai beta sama dengan stabil (Audit 794)
- Perbandingan versi umum memang sengaja buta sufiks, tetapi situs keputusan pin memakainya untuk menyimpulkan "sudah versi pilihan/terpasang": pin `1.37.3-beta` dinyatakan terpenuhi oleh binary `1.37.3` stabil dan bahkan dicatat sebagai versi terpasang, sementara jalur start menilai pin yang sama secara ketat dan menolaknya. Klaim kesetaraan di situs-situs keputusan itu (lewati unduh, update web-vault terkunci, penanda fallback, pencatatan AutoUpdate) kini memakai kesetaraan ketat sufiks `versiSamaPersis` — pin prerelease mengalir ke percobaan unduh dan gagal secara jujur dengan petunjuk versi stabil, sama seperti penilaian jalur start.

## [Belum rilis] — Backup ke banyak chat kini dikirim ulang lewat file_id (Audit 793)
- Sesudah unggahan penuh pertama berhasil, chat tujuan berikutnya menerima berkas yang sama lewat `file_id` Telegram — tidak lagi mengunggah ulang byte-per-byte hingga 48 MB per chat. Bila kirim by-id gagal untuk satu chat, chat itu jatuh kembali ke unggahan penuh seperti sebelumnya; pencatatan file_id untuk restore tidak berubah. Keputusan file_id berikutnya dan enkode parameter kirim by-id dikunci uji JVM.

## [Belum rilis] — Jatah percobaan PIN kini atomik lintas jalur bot dan UI (Audit 791)
- Pola lama di kedua pintu PIN adalah periksa sisa kunci, verifikasi, lalu catat sebagai tiga langkah terpisah: saat penghitung tepat di bawah ambang, satu percobaan bot dan satu percobaan UI bisa sama-sama lolos pemeriksaan dan sama-sama memverifikasi sebelum satu pun mencatat, memberi sekitar satu tebakan ekstra per jendela kunci. Verifikasi dan pencatatan kini berjalan di dalam kunci bersama `PinGate.KUNCI_PERCOBAAN` dengan pemeriksaan ulang otoritatif di dalamnya — jalur yang kalah balapan dibatalkan tanpa verifikasi dan menerima pesan terkunci, di bot maupun di layar kunci.

## [Belum rilis] — CodeQL kini ikut memindai Python (tools/) (Audit 789)
- Workflow CodeQL sebelumnya hanya menganalisis `java-kotlin` dan `cpp`, sehingga dua berkas Python yang menjadi gerbang verifikasi repo sendiri (`tools/verifikasi-patch.py`, `tools/uji-verifikasi.py`) buta total dari analisis keamanan. Job baru `analyze-python` (build-mode none, pola yang sama dengan job cpp) menutup celah itu.

## [Belum rilis] — Update web-vault otomatis tak lagi bisa mundur versi (Audit 788)
- Gerbang pemicu update web-vault otomatis sebelumnya hanya menguji kesetaraan versi: bila tag rilis terbaru upstream mundur (rilis dicabut/diganti), bundel web akan diunduh ulang dari rilis yang lebih lama dan tertukar masuk diam-diam, sementara binary menolak bergerak berkat penjaga perbandingan versinya. Keputusan pemicu kini murni dan teruji (`webVaultOtomatisPerluUpdate`): tanpa kuncian versi, otomatis hanya maju — penanda yang sama atau lebih baru dari target tidak memicu unduh. Bila user mengunci versi web-vault (pin), target adalah pilihan eksplisit dan selisih apa pun ke arah pin tetap dikejar seperti sebelumnya; jalur manual tidak berubah.

## [Belum rilis] — Uji: kunci pemilik offset bot kini diasersikan tidak ikut dieksport (Audit 786)
- Kunci `tg_bot_offset_id` dari Audit 779 memang terdaftar di `TAK_DIEKSPOR_KEYS`, tetapi uji keanggotaan satu-per-satu di `TgBackupTest` belum memuatnya — menghapus pendaftarannya tidak akan membuat uji mana pun merah. Asersi untuk kunci itu kini berdampingan dengan kunci saudaranya (`KEY_TG_MENU_FP`).

## [Belum rilis] — Uji: akar path /mnt dan /sdcard kini terisolasi pada uji gerbang dan peredaksi log (Audit 785)
- Uji path absolut sebelumnya hanya mengisolasi `/storage` dan `/data` di sisi peredaksi, dan memakai sampel gabungan `/mnt/sdcard/cadangan` di sisi gerbang — kedua akar itu saling menutupi, jadi menghapus salah satunya dari detektor atau pola peredaksi tidak akan membuat uji mana pun merah. Sampel terisolasi untuk `/mnt` dan `/sdcard` kini diasersikan di kedua sisi (gerbang `butuhSamaran` dan keluaran `samarkanLog`).

## [Belum rilis] — Gerbang: perilaku cabang langka kini terkunci uji-diri permanen (Audit 782)
- Perilaku gerbang dari putaran-putaran sebelumnya — fail-keras tanpa PyYAML, sumber lokal daftar hapus pada mode lokal dan mode `--semua <jalur>`, serta cabang kedua `strings.xml` kosong = gagal — hanya pernah terbukti oleh fixture audit sesaat; CI selalu memakai `--rentang` dengan PyYAML terpasang, jadi regresinya tidak akan membuat lari mana pun merah. Skrip baru `tools/uji-verifikasi.py` menjalankan kelima skenario itu pada salinan buang pohon kerja (kontrol bersih, PyYAML diblokir, hapusan staged pada dua mode, kedua strings dikosongkan) dan kini menjadi langkah tetap job verifikasi di `cek-wajib.yml` dan `build-apk.yml`. Kontrol negatif terverifikasi: mengembalikan fail-open PyYAML membuat skrip ini gagal tepat pada skenario itu. Fixture snapshot membawa dua commit (satu kosong) karena gerbang mode tanpa `--rentang` memakai diff cadangan `HEAD~1...HEAD` dan repo berkomit tunggal membuatnya gagal keras — temuan dari run CI pertama langkah ini, yang merah tepat pada skenario kontrol sampai riwayat minimum itu ditambahkan.

## [Belum rilis] — Uji: daftar hapus resetTls kini terkunci penuh 19 nama (Audit 781)
- Fixture uji `resetTls` sebelumnya hanya menciptakan 11 dari 19 nama berkas yang dihapus fungsi itu; 8 sisanya — varian `.baru` dan `.cad` dari keempat berkas kunci/sertifikat — tidak teruji, jadi membuangnya dari daftar akan tetap hijau di CI padahal sisa `.cad` memuat cadangan kunci privat. Uji baru `resetTls_hapusVarianBaruDanCad` menciptakan kedelapan nama itu dan menegaskan hitungan hapus 8 lalu 0.

## [Belum rilis] — Uji: penjaga surrogate potongBarisLog kini dikunci uji khusus (Audit 780)
- Perilaku Audit 778 benar tetapi tak teruji: satu-satunya uji `potongBarisLog` hanya memakai huruf 'x', jadi cabang `isHighSurrogate`-nya tak pernah dieksekusi dan penghapusannya akan tetap hijau di CI. Uji baru `potongBarisLog_takBelahSurrogate` mencerminkan pola uji kembarannya (`potongPesanGalat_takBelahSurrogate`): pasangan surrogate yang mengangkangi batas 8192 dibuang utuh (inti 8191 char, tak berakhir surrogate), dan pasangan yang utuh di dalam batas dipertahankan (inti 8192 char berakhir low surrogate).

## [Belum rilis] — Gerbang: sumber penghapusan lokal tak lagi dihitung dua kali (Audit 777)
- Bentuk union Audit 775 memanggil `berkas_dihapus("")` dua kali persis pada mode yang paling sering dipakai (lokal murni dan jalur posisional, termasuk hook pre-push): 6 panggilan `git diff --name-status` untuk 3 sumber yang sama. Kini bila `rentang_cek` kosong, sumber lokal dipanggil sekali saja; cabang union hanya berjalan bila benar-benar ada rentang cadangan. Terbukti dengan shim git pencatat panggilan: hitungan `name-status` turun 6 → 3 dan laporan penghapusan tetap tepat satu kali.

## [Belum rilis] — Cap waktu baris log kini diformat di dalam kunci buffer (Audit 776)
- `tulisBarisLog` dulu memformat cap waktu sebelum masuk `synchronized (logBuffer)`, jadi dua thread yang menulis bersamaan bisa tercatat di buffer dengan urutan cap terbalik (baris ber-cap lebih baru muncul lebih dulu) — membingungkan saat membaca urutan kejadian start/crash yang berselisih milidetik. Cap kini diformat di dalam kunci yang sama dengan penambahan barisnya, sehingga urutan buffer selalu searah dengan cap-nya. (Urutan penulisan berkas log adalah langkah terpisah dan tidak diubah.)

## [Belum rilis] — Pemotongan baris log tak lagi membelah pasangan surrogate (Audit 778)
- `potongBarisLog` memotong baris >8192 karakter di indeks tetap tanpa penjaga: karakter non-BMP yang pasangan surrogate-nya tepat mengangkangi batas menghasilkan surrogate tak berpasangan (glyph rusak) di ujung baris. Kini mengikuti pola `potongPesanGalat` di berkas yang sama: bila char terakhir hasil potongan adalah high surrogate, ia ikut dibuang sebelum penanda `[baris dipotong]` ditambahkan.

## [Belum rilis] — Bot Telegram: offset getUpdates kini direset saat token berganti ke bot lain (Audit 779)
- `update_id` Telegram adalah urutan per-bot, tetapi offset tersimpan tidak pernah direset: mengganti token ke bot BARU (bukan token baru bot yang sama) membuat polling memakai offset warisan bot lama, sehingga `getUpdates` mengembalikan hasil kosong selamanya — semua perintah bot diabaikan diam-diam (poll tetap 200, tanpa galat) sementara backup keluar tetap terkirim, dan tak ada jalan pulih di UI. Kini identitas pemilik offset dicatat di pref perangkat-lokal `tg_bot_offset_id` (tidak diekspor, dilestarikan saat restore config bersama offsetnya); saat polling melihat identitas token berbeda terbukti dari catatan, offset direset ke 0 dengan `commit()`. Token baru untuk bot yang sama mempertahankan offset seperti seharusnya. Keputusan resetnya fungsi murni `offsetPerluReset` dan teruji matriks.

## [Belum rilis] — Gerbang: cakupan penghapusan ter-staged dipulihkan untuk kombinasi --semua + jalur (Audit 775)
- Melepas tambahan staged ganda pada Audit 771 menyisakan satu lubang sempit: pada pemanggilan `--semua <jalur>` dengan diff jalur kosong, `rentang_cek` terisi rentang cadangan `HEAD~1...HEAD` sehingga `berkas_dihapus` bercabang rentang dan penghapusan ter-staged tak terlihat. Daftar hapus kini dibangun sebagai union ter-dedup (set) antara sumber rentang dan sumber lokal bila `--rentang` tidak diberikan eksplisit — mode lokal tetap tepat satu laporan (771 utuh), kombinasi `--semua`+jalur kembali menangkap (terbukti fixture tiga mode), dan mode CI `--rentang` tetap murni rentang.

## [Belum rilis] — Gerbang redaksi log kini juga mengenal bentuk token bot mentah (Audit 774)
- Kelas cacat yang sama dengan Audit 769, satu cabang di sebelahnya: `samarkanLog` menyamarkan bentuk token bot mentah (`123456:AA…`) tanpa syarat, tetapi gerbang `butuhSamaran` tidak memanggil detektornya — potongan log yang isinya hanya token polos tanpa kata konteks/IP/email/path akan lolos mentah ke tampilan, bagikan, dan ekor log bot. Gerbang kini memanggil `adaBentukTokenMentah`, menutup cabang terakhir yang tak tercermin menurut perbandingan menyeluruh gerbang↔peredaksi; uji gerbang memakai contoh tanpa-konteks yang sama dengan uji peredaksinya.

## [Belum rilis] — Layar Log: redaksi teks-penuh tak lagi dihitung pada mode delta (Audit 773)
- Refresh layar Log dulu selalu menjalankan `samarkanLog` atas seluruh teks tampil (sampai 100 ribu karakter, beberapa lintasan) walau pada mode delta — kasus normal saat layar terbuka — hasilnya dibuang dan yang dipakai hanya redaksi deltan-ya; sesudah Audit 769 gerbangnya nyaris selalu terbuka karena jendela tampil hampir selalu memuat baris ber-path, jadi pemborosan itu terjadi pada setiap tick berkonten baru di STB kelas rendah. Kini redaksi teks-penuh hanya dihitung pada mode non-delta, persis seperti jalur log beranda.

## [Belum rilis] — Gerbang: guard kedua-kosong diluruskan, plurals yang sah kosong tak lagi digagalkan (Audit 772)
- Guard Audit 765 menyamaratakan string dan plurals, padahal keduanya berbeda: string kedua-kosong pasti berarti berkas hilang/rusak, tetapi plurals kedua-kosong bisa jadi keadaan sah (aplikasi tanpa `<plurals>` sama sekali tersinkron sempurna) — guard itu akan menggagalkan gerbang secara permanen dengan pesan menyesatkan bila plurals terakhir suatu hari dihapus dari kedua locale. Cabang plurals kini kembali ke perbandingan bersyarat; berkas hilang tetap tertangkap cabang string karena berkas yang sama memuat string (terbukti fixture: kedua `strings.xml` dipindahkan → GAGAL dari cabang string, tepat sekali).

## [Belum rilis] — Gerbang: penghapusan ter-staged tak lagi dilaporkan dua kali (Audit 771)
- Sejak Audit 763, `berkas_dihapus` mode lokal sudah mengunion diff staged ke dalam daftarnya, tetapi `utama()` masih menambahkan penghapusan staged sekali lagi secara terpisah — warisan dari sebelum 763 — sehingga satu berkas ter-staged yang dihapus menghasilkan dua baris GAGAL yang sama. Tambahan ganda itu kini dilepas; terbukti dengan fixture `git rm` berkas yang masih dirujuk: GAGAL tetap muncul, tepat satu kali.

## [Belum rilis] — Gerbang: pesan PyYAML absen kini dilaporkan sekali, bukan dua kali (Audit 770)
- Pintu impor `_impor_yaml` dari Audit 758 dipanggil beberapa fungsi cek untuk akar yang sama, jadi lingkungan tanpa PyYAML menghasilkan dua baris GAGAL identik ("GAGAL (2)" untuk satu penyebab) yang mengaburkan laporan. Kini pesan itu ditambahkan hanya bila belum ada di daftar gagal — satu akar, satu laporan. Vonis fail-kerasnya tidak berubah.

## [Belum rilis] — Gerbang redaksi log kini mengenal path absolut (Audit 769)
- `samarkanLog` selalu menyamarkan path absolut menjadi `[path]` (maksud Audit 601), tetapi gerbang `butuhSamaran` yang memutuskan apakah redaksi perlu jalan sama sekali tidak mengenal path — potongan log yang isinya hanya baris ber-path (mis. baris `TLS cert: /data/…` saat server start) tampil mentah di log beranda dan ikut mentah saat dibagikan/disalin/dikirim bot. Gerbang kini ikut membuka bila ada path absolut, dan uji baru mengunci kasus path-saja.

## [Belum rilis] — resetTls kini fsync direktori sesudah penghapusan (Audit 768)
- `TlsCert.resetTls` adalah satu-satunya pengubah folder tls yang tidak menutup dengan fsync direktori (jalur lain — `writeVersion`, tukar pasangan, pemulihan tukar — semuanya fsync). Bila STB mati listrik segera sesudah bot melaporkan reset berhasil, penghapusan sampai 20 berkas itu bisa batal diam-diam: CA lama bangkit lagi dan tetap berlaku pada Start berikutnya, tanpa penanda. Kini sesudah ada berkas yang terhapus, `resetTls` memanggil `fsyncDirektori` seperti mutator lainnya.

## [Belum rilis] — Perbaikan Audit 759 diluruskan: identitas restartTunda dipisah lagi dari startTunda (Audit 767)
- Audit putaran-30 menemukan premis penggabungan Audit 759 keliru di satu situs: situs penundaan kunci-data memang selalu membatalkan `startTunda` SAJA, dan karena `batalkanTunda` menghapus semua posting Runnable yang sama, Runnable gabungan membuat pembatalan itu ikut membunuh posting backoff-crash yang sedang menunggu (redaman crash-loop melemah; start otomatis bisa gugur). Kini kedua Runnable kembali beridentitas Handler terpisah persis seperti pra-penggabungan (semua situs batal/posting cocok dengan keadaan sebelum Audit 759), tetapi badannya tetap SATU — `jalankanStartTunda(Runnable diri)` — jadi duplikasi logika tidak kembali. Nama `restartTunda` dikeluarkan lagi dari `tools/bekas-hapusan.txt`. Keputusan badannya sendiri diesktrak menjadi fungsi murni `putusanStartTunda` (0 diam / 1 posting ulang / 2 mulai) dan dikunci uji matriks di `ServerServiceTest`.

## [Belum rilis] — Build APK: langkah salin APK bernama rilis yang ganda dihapus (Audit 766)
- Langkah "Siapkan salinan APK bernama rilis" melakukan `find` + `cp` yang identik dengan awal langkah "Publish APK ke rilis" tepat sesudahnya; salinannya tertimpa sebelum `upload-artifact` membacanya, jadi langkah itu tak punya konsumen sendiri pada run main. Pemeriksaan "APK release tidak ada" tidak hilang — langkah publish memuat uji yang sama persis sebagai baris pertamanya. Satu-satunya penyalinan kini di langkah publish.

## [Belum rilis] — Gerbang: cek terjemahan kini gagal bila kedua himpunan string kosong (Audit 765)
- `cek_sinkron_terjemah` dulu membungkus perbandingannya dengan `if inggris or indonesia:` — pola "dua himpunan kosong dianggap cocok" yang sudah berulang kali terbukti fail-open di repo ini: bila kedua `strings.xml` hilang/berpindah bersamaan, cek tak membandingkan apa pun dan gerbang tetap LOLOS. Kini keadaan kedua-kosong (string maupun plurals) adalah GAGAL eksplisit yang menyatakan ceknya tak bisa berjalan. Terbukti dengan fixture kedua strings dipindahkan (GAGAL dengan pesan itu) vs pohon utuh (LOLOS).

## [Belum rilis] — Gerbang: paritas layout kini dua arah, berkas layout-land yatim menggagalkan (Audit 764)
- Lingkar paritas di `verifikasi-patch.py` dan `scripts/cek-cepat.sh` dulu hanya mengiterasi isi `layout/`; berkas yang hanya ada di `layout-land/` (varian tertinggal sesudah layout-nya dihapus, atau berkas land baru tanpa pasangan) tak pernah dibandingkan dan lolos kedua gerbang. Kini arah sebaliknya diperiksa juga: berkas land tanpa pasangan portrait = GAGAL di kedua gerbang. Terbukti dengan fixture berkas `yatim.xml` (kedua gerbang GAGAL) vs pohon bersih (LOLOS).

## [Belum rilis] — Gerbang: mode lokal kini melihat penghapusan berkas yang sudah ter-commit (Audit 763)
- `berkas_dihapus` tanpa rentang dulu hanya membaca diff working-tree, jadi berkas Java yang dihapus dalam commit lokal (belum push) tak terlihat oleh `cek_berkas_java_dihapus` di lokal — kelas yang masih menyebutnya lolos gerbang dan baru merah di CI. Kini sumbernya gabungan diff working-tree + staged + rentang commit lokal terhadap `@{upstream}` (cadangan `origin/main`). Terbukti dengan fixture: menghapus `DialogAman.java` dalam commit lokal kini menggagalkan gerbang lokal dengan pesan yang tepat; pohon bersih tetap LOLOS.

## [Belum rilis] — Build Binary: peringatan "strip tidak mengecilkan" yang selalu menyala dihapus (Audit 762)
- Langkah cross-compile mengekspor `CARGO_PROFILE_RELEASE_STRIP=true`, jadi binary sudah ter-strip oleh Cargo sebelum `llvm-strip` manual berjalan — ukuran yang tidak mengecil sesudahnya adalah keadaan NORMAL, bukan kerusakan toolchain. Peringatan berbasis perbandingan ukuran dari Audit 752 karena itu menyala di setiap build sehat dan melatih semua orang mengabaikan anotasi workflow. Kegagalan strip sungguhan tetap terlihat dari peringatan cabangnya sendiri (`llvm-strip gagal … build tetap lanjut`), dan baris informasi ukuran sebelum/sesudah dipertahankan.

## [Belum rilis] — TgBackup: pola redaksi galat dikompilasi sekali, bukan per panggilan (Audit 761)
- `redaksiDetailGalat` memakai tiga `String.replaceAll` sehingga setiap pencatatan kegagalan Telegram mengompilasi 3 pola regex baru dan memindai badan respons (bisa sampai 1 MB) tiga kali — berulang terus selama gangguan jaringan/bot. Ketiga pola kini `static final Pattern` (`POLA_TOKEN_GALAT`/`POLA_URL_GALAT`/`POLA_SPASI_GALAT`, meniru pola `POLA_*` di TgBot), perilaku redaksinya identik dan tetap dikunci uji `redaksiDetailGalatBuangTokenDanRapikanBaris`.

## [Belum rilis] — TlsCert: resetTls kini ikut menghapus varian .rusak (Audit 760)
- `pulihkanTukarTersisa` menyisihkan pasangan tak-cocok sebagai `<nama>.rusak` (termasuk kunci privat CA `ca-key.pem.rusak`); jalur normal dan rollback memang mengonsumsinya kembali, tetapi proses yang mati di tengah pemulihan meninggalkannya permanen — dan daftar hapus `resetTls` hanya memuat varian `.baru`/`.cad`, jadi kunci privat sisa itu selamat dari reset TLS eksplisit yang user kira tuntas. Daftar kini memuat keempat varian `.rusak` (CA + leaf), dikunci uji `resetTls_hapusVarianRusak`.

## [Belum rilis] — ServerService: restartTunda digabung ke startTunda (Audit 759)
- Sejak perbaikan #501 kedua Runnable itu berbadan identik kata per kata dan selalu dibatalkan berpasangan di semua situs — satu perilaku yang menyamar sebagai dua, berisiko divergensi diam-diam bila suatu hari hanya satu badan yang diedit. Kini tinggal satu Runnable `startTunda` untuk tunda-start kunci-data maupun backoff crash; `scheduleRestartTerkunci` mempostingnya dengan jeda backoff seperti sebelumnya, dan nama `restartTunda` dicatat di `tools/bekas-hapusan.txt`. Semantik tidak berubah: tiap posting tetap didahului pembatalan yang sama.

## [Belum rilis] — Gerbang: PyYAML absen kini menggagalkan verifikasi, bukan dilewati diam-diam (Audit 758)
- `cek_workflow_yaml`, `cek_yaml_github_lain`, dan pembaca `_muat_yaml_github` dulu menangkap kegagalan `import yaml` lalu hanya memberi peringatan (atau diam) dan `return` — seluruh validasi YAML workflow/dependabot/labeler bisa mati tanpa suara sementara gerbang tetap LOLOS, dan tak ada workflow pemanggil yang memasang pustaka itu. Kini ketiganya memakai satu pintu `_impor_yaml` yang **gagal keras** dengan pesan berisi jalan keluarnya (`pip install pyyaml`): gerbang yang tidak bisa memeriksa harus mengaku, bukan lolos buta. Terbukti dua arah dengan fixture modul `yaml` yang sengaja diblokir (GAGAL) vs normal (LOLOS).

## [Belum rilis] — Lint: banner TV pindah ke drawable-nodpi
- Peringatan `IconLocation`: `tv_banner.png` adalah karya seni bebas-kepadatan (banner TV 640×360 yang dirujuk manifest), jadi rumahnya yang benar adalah `drawable-nodpi`, bukan folder `drawable` tanpa kualifikasi kepadatan. Nama resource tidak berubah, jadi acuan manifest tetap sama dan tampilannya identik.

## [Belum rilis] — Lint: rentang heksadesimal pada pesan kesalahan SHA memakai en dash
- Peringatan `TypographyDashes`: rentang `0-9` pada string `bin_sha_error` (kedua locale) adalah rentang nilai, jadi tanda pisahnya yang benar secara tipografi adalah en dash `0–9`. Teks tampil berubah sesempit itu saja.

## [Belum rilis] — Lint: 8 peringatan ApplySharedPref ditandai disengaja (commit penanda daya-tahan)
- Lint menyarankan `apply()` untuk delapan `commit()` — semuanya penanda yang memang WAJIB tahan mati proses saat itu juga: penanda pasca-swap dan pin binary/web-vault (`Updater`, dari Audit 730) serta penanda restore-berjalan (`TgBackup`, sinyal keselamatan bila proses mati di tengah restore). Menggantinya ke `apply()` akan membatalkan maksud perbaikannya, jadi keempat metode penampungnya kini memakai `@SuppressLint("ApplySharedPref")` — pola yang sama yang sudah dipakai pembuatan rahasia alarm. Perilaku tidak berubah sama sekali; peringatan hilang karena keputusannya kini tercatat di kode.

## [Belum rilis] — Gerbang: metode di berkas uji tak lagi memicu peringatan "metode baru tanpa uji"
- Pemeriksaan `cek_uji_baru` di `tools/verifikasi-patch.py` memindai semua berkas Java yang berubah termasuk berkas uji, lalu mencari nama metodenya sebagai acuan `Kelas.nama()` atau di berkas `<Kelas>Test.java` — untuk metode yang dideklarasikan di dalam berkas uji sendiri (kelasnya `UtilTest`, berkas pasangannya "UtilTestTest.java" yang tak pernah ada) pencarian itu mustahil berhasil, jadi setiap penambahan metode uji memicu peringatan palsu (terakhir: uji `jalankanCek` dari Audit 757). Pemeriksaan kini melewati berkas di `src/test/` — aturan itu menjaga logika produksi, dan berkas uji adalah ujinya sendiri. Terverifikasi di salinan buang: metode uji baru kini senyap, metode produksi baru tanpa uji tetap diperingatkan.

## [Belum rilis] — Audit 757: Util.jalankanCek kini teruji seperti pembungkus pool lainnya
- `jalankanCek` (pool khusus `BG_CEK` dari Audit 716, satu-satunya jalur penyerahan cek update kedua activity) adalah satu-satunya pembungkus pool tanpa satu pun acuan uji, sementara ketiga saudaranya teruji termasuk penolakan saat antrean penuh. Uji baru mengunci kontrak yang sama untuknya: null ditolak, tugas diterima dan berjalan, dan penyerahan ditolak sesudah thread tunggalnya tertahan dan antrean 4 terisi.

## [Belum rilis] — Audit 756: hitungBaris disatukan ke salinan LogActivity yang teruji
- Logika hitung baris hidup sebagai dua implementasi identik di dua activity; salinan `LogActivity` terkunci uji, sedangkan salinan `MainActivity` bersifat private dan tak teruji — padahal penghitung baris beranda inkremental Audit 734 bertumpu padanya. Badan salinan MainActivity kini mendelegasikan ke salinan bersama `LogActivity`, jadi satu implementasi yang teruji melayani kedua layar dan penyimpangan antar-salinan tak lagi mungkin.

## [Belum rilis] — Audit 755: varian 3-arg zipSudahGagal/catatZipGagal (jalur produksi Audit 732) kini teruji
- Sejak Audit 732 jalur produksi web-vault memanggil varian 3-arg dengan digest rilis siap-pakai, tetapi uji hanya menyentuh varian 2-arg yang selalu jatuh ke hash berkas — regresi khusus jalur 3-arg (penanda ditulis dari satu sumber, dibandingkan dari sumber lain) tak akan tertangkap. Uji baru mengunci varian 3-arg: digest cocok, digest beda, huruf besar (mengunci pula predikat tak peka huruf Audit 748), dan digest kosong/null yang jatuh kembali ke sidik berkas.

## [Belum rilis] — Audit 754: uji catatLog kini benar-benar membuktikan jalur null adalah no-op
- Asertsi kedua `catatLogNaikkanVersi` membandingkan ke baseline *sebelum* panggilan pertama, jadi ia lulus murni berkat panggilan pertama dan tidak membuktikan apa pun tentang `catatLog(null)` — perilaku produksi yang sebenarnya (null kembali dini tanpa menaikkan versi) tak terkunci, dan regresinya tak akan tertangkap. Uji kini menangkap versi di antara dua panggilan dan mengasertikan null tidak mengubahnya.

## [Belum rilis] — Audit 752: kegagalan strip binary kini terlihat sebagai peringatan berukuran
- Kedua `llvm-strip ... || true` di `build-binary.yml` menelan kegagalan tanpa jejak: binary/shim tanpa strip bisa terbit diam-diam beberapa kali lebih besar (tak ada cek ukuran maksimum untuk binary, berbeda dengan web-vault dan APK). Strip kini mencatat ukuran sebelum/sesudah dan memunculkan `::warning::` bila strip gagal atau tidak mengecilkan berkas — build tetap tidak digugurkan oleh strip (keputusan lamanya dipertahankan), tetapi kegagalannya tak lagi tak terlihat.

## [Belum rilis] — Audit 751: label audit otomatis hanya untuk issue berpenulis pihak repo
- `label-audit.yml` memasang label `audit` pada issue mana pun yang badannya memuat penanda ronde audit, dan label itu membuat issue kebal dari penutupan `stale` — pembuka issue eksternal cukup menyalin satu baris penanda untuk mendapat kekebalan itu. Label kini hanya dipasang bila asosiasi penulisnya OWNER/MEMBER/COLLABORATOR; jalur pencabutan label tak berubah. Dampaknya memang hanya kebersihan antrean, tetapi celah efek-samping dua workflow ini kini tertutup.

## [Belum rilis] — Audit 750: checkout gitleaks kini tanpa kredensial tersimpan
- `gitleaks.yml` adalah satu-satunya checkout di repo yang belum memakai `persist-credentials: false` — token workflow tertinggal di `.git/config` lokal selama job yang menjalankan action pihak ketiga. Kini diselaraskan dengan semua checkout lain (pola yang sama pernah diterapkan untuk CodeQL); `fetch-depth: 0` yang dibutuhkan pemindaian riwayat penuh tetap dipertahankan.

## [Belum rilis] — Audit 749: penolakan tugas bangun log kini ikut me-reset stempel throttle pencarian
- Sisa Audit 718: stempel throttle bangun-ulang pencarian dicatat saat tugas dikirim, tetapi bila pool latar menolak tugas itu, hanya kunci konten dan gerbang single-flight yang direset — stempelnya tertinggal, sehingga bangun-ulang kueri yang sama tertahan sampai sisa jendela 4,5 detik habis. Blok penolakan kini me-reset kedua stempel juga, jadi tik berikutnya (±1,5 detik) langsung membangun tampilan cari yang benar.

## [Belum rilis] — Audit 748: perbandingan penanda zip gagal memakai predikat digest tak peka huruf
- Penanda zip gagal dibandingkan dengan `String.equals` yang peka huruf besar/kecil, sedangkan seluruh verifikasi digest lain di berkas ini memakai `expectedHexEquals` yang tak peka huruf — penanda dari sumber berhuruf berbeda (mis. `.sha256` rilis berhuruf besar melawan sidik berkas heks kecil) tidak akan dikenali sebagai zip yang sama. Perbandingan penanda kini memakai predikat yang sama; dampak praktisnya kecil (`.sha256` GitHub selalu huruf kecil dan penanda sembuh sendiri), ini pemadanan konsistensi.

## [Belum rilis] — Audit 747: stempel percobaan getMe ditulis saat penyerahan tugas, bukan di dalam tugas
- Sisa perlombaan yang ditangkap self-review putaran-28 atas Audit 724: stempel percobaan `getMe` baru ditulis sesudah tugas latar benar-benar berjalan, jadi selama tugas pertama masih antre di pool, poll berikutnya tetap lolos cek depan dan menyerahkan tugas kedua yang bisa menembak `getMe` lagi. Stempel kini ditulis di cek depan tepat sebelum penyerahan — poll berikutnya selama jendela percobaan langsung tertahan, dan pemeriksaan di dalam tugas tetap berperan sebagai pagar kedua. Dampaknya memang hanya satu-dua panggilan baca-saja duplikat, tetapi gerbangnya kini benar-benar tertutup untuk pemanggil berurutan.

## [Belum rilis] — Audit 746: single-flight pengambilan versi dipulihkan tanpa mengembalikan fetch ke dalam kunci
- Efek samping Audit 715 yang ditangkap self-review putaran-28: mengeluarkan fetch dari `KUNCI_VERSI` sekaligus menghilangkan single-flight — dua pemanggil bersamaan saat cache basi (cek AutoUpdate + perintah bot) sama-sama menembak API versi yang sama. Kini ada gerbang pengambil tunggal di luar kunci data: pemanggil kedua menunggu (maks 25 detik, sesuai anggaran timeout fetch 10+10 detik) lalu membaca hasil yang disimpan pengambil pertama, berhasil maupun gagal. Fetch tetap di luar `KUNCI_VERSI`, jadi jalur UI tetap tidak pernah menunggu jaringan — yang menunggu hanyalah sesama pemanggil latar.

## [Belum rilis] — Audit 745: penjadwalan restart-crash kini ikut membatalkan coba-lagi start
- Sisa pemisahan Audit 722: `startTunda` (coba-lagi start tertunda kunci data, siklus 5 detik) tidak dibatalkan saat `scheduleRestartTerkunci` menjadwalkan restart-crash ber-backoff, sehingga server bisa menyala mendahului backoff bila kunci data lepas lebih dulu. Penjadwalan restart kini membatalkan keduanya — coba-lagi start kehilangan maknanya begitu restart-crash mengambil alih. Dampaknya memang hanya ketepatan waktu (penjaga run + CAS starting menahan start liar), tetapi urutan yang dimaksud kini yang benar-benar terjadi.

## [Belum rilis] — Audit 719 (koreksi): jam throttle flush log diganti ke System.currentTimeMillis
- Koreksi atas entri Audit 719 di bawah: throttle flush memakai `SystemClock.elapsedRealtime()` di dalam `catatLog` — di lingkungan uji JVM, `SystemClock` adalah stub Android yang melempar RuntimeException, sehingga `catatLog` (yang sebelumnya murni Java/berkas dan aman dipanggil uji) mulai melempar dan tiga uji CI gagal (`catatLogNaikkanVersi` + dua uji `tundaBilaBootBaru` yang jalurnya mencatat log). Jam throttle kini memakai `System.currentTimeMillis()` (murni Java): lonjakan jam dinding hanya bisa membuat flush sedikit lebih sering atau lebih jarang sesaat — tak berpengaruh pada keamanan throttle. Perilaku produksi tidak berubah.

## [Belum rilis] — Audit 744: asersi waktu uji waitForOrKill diperketat dari 10 detik ke 3 detik
- Uji timeout 300 ms untuk proses macet hanya mengasertikan selesai di bawah 10 detik (kelonggaran 33×), sehingga regresi kelambatan sampai ±9 detik akan tetap hijau. Batas diperketat menjadi 3 detik — tetap 10× anggaran nominal, cukup longgar untuk CI yang sibuk, tetapi sekarang benar-benar menangkap regresi orde detik.

## [Belum rilis] — Audit 743: gerbang izin ekspor log jalur API lama tidak lagi fail-open saat pemeriksa melempar
- Cabang API <29 di `LogExport` membungkus pemeriksaan izin dalam try/catch yang menelan pengecualian lalu *melanjutkan menulis* — secara struktur fail-open, berlawanan arah dengan cabang `false`-nya, walau dampak praktisnya mendekati nol (pemeriksa menelan pengecualian internalnya sendiri dan tulisan tanpa izin tetap gagal di stream). Pengecualian kini diperlakukan sama dengan tidak-berizin: ekspor dibatalkan. Bentuk penanganannya yang dibuat benar, bukan perilaku yang berubah.

## [Belum rilis] — Audit 742: Util.batalkanTunda kini lewat pintu handlerUtama() seperti postTunda/postUtama
- `batalkanTunda` sebelumnya membaca field `HANDLER_UTAMA` mentah, satu-satunya dari tiga operasi handler yang tidak lewat pintu `handlerUtama()` (lazy-init + cek looper). Aman hari ini, tetapi rapuh: bila suatu hari handler dibuat ulang lewat pintu itu, pembatalan bisa diam-diam membidik handler yang salah dan tugas tertunda tetap menembak. Kini ketiganya konsisten lewat pintu yang sama; perilaku tanpa looper tidak berubah (tetap mengembalikan false dengan aman, sesuai uji yang menguncinya).

## [Belum rilis] — Audit 741: overload 6-arg throttleBootMenahan yang hanya dipakai uji dihapus
- Varian 6-arg `throttleBootMenahan` hanya mendelegasikan ke varian 7-arg dengan hitungan boot 0,0 dan hanya dipanggil oleh uji — produksi selalu memakai varian 7-arg. Overload dihapus; tujuh panggilan uji dialihkan ke varian 7-arg dengan hitungan 0,0 (semantik identik: cabang hitungan hanya berjalan bila kedua hitungan > 0). Nama metode tidak masuk daftar hapusan karena nama yang sama masih terdefinisi oleh varian yang hidup.

## [Belum rilis] — Audit 740: parameter mati diff_biasa di gerbang verifikasi dihapus
- `berkas_dihapus(rentang, diff_biasa)` di `tools/verifikasi-patch.py` menerima parameter kedua yang tidak pernah dipakai di badan fungsi (sisa refactor). Parameter dan argumen di satu-satunya pemanggil dihapus — perilaku gerbang tidak berubah.

## [Belum rilis] — Audit 739: deteksi deklarasi gerbang kini juga membaca deklarasi yang dibungkus tiga baris
- `nama_terdeklarasi` di `tools/verifikasi-patch.py` hanya mencoba baris tunggal dan gabungan dua baris berurutan, sehingga deklarasi metode yang dibungkus tiga baris (tanda tangan panjang) tidak terdeteksi sebagai terdefinisi — `masih_didefinisikan` bisa false-negatif dan gerbang daftar-hapusan/acuan berpotensi menolak push yang sah begitu ada deklarasi seperti itu. Gabungan tiga baris kini dicoba juga. Belum pernah terjadi di HEAD (gaya repo ≤2 baris); ini menutup kerapuhan laten sebelum ada yang menabraknya.

## [Belum rilis] — Audit 738: gerbang verifikasi kini meng-cache hasil kupas_java (lari penuh ±27% lebih cepat)
- `tools/verifikasi-patch.py` mengupas korpus yang sama berulang kali tanpa cache: fungsi murni `kupas_java` terpanggil ±35 ribu kali per lari penuh (terukur cProfile), sebagian besar atas baris-baris pendek yang sama persis (`}`, baris kosong, pola deklarasi) dan atas isi berkas utuh yang sama dari beberapa pemeriksaan berbeda. Hasil kupas kini di-memo per isi masukan dalam satu proses (dibersihkan berkala agar memori terbatas) — keluaran setiap pemeriksaan identik, hanya tidak dihitung ulang. Terukur lokal: lari `--semua` turun dari ±6,7 detik menjadi ±4,9 detik, hasil tetap LOLOS atas 164 berkas.

## [Belum rilis] — Audit 736: ganti PIN yang terlewat karena aktivasi berjalan kini memberi tahu pengguna
- Bila pengguna menyetujui dialog ganti PIN tepat saat tugas aktivasi PIN masih berjalan, penulisan hash baru memang harus dilewati (aktivasi akan menimpanya dengan hash PIN lama) — tetapi sebelumnya dilewati *diam-diam*: dialog tertutup seolah berhasil dan PIN tersimpan tetap yang lama, baru ketahuan saat PIN "baru" ditolak di layar kunci. Jalur konfirmasi pengguna kini menampilkan toast bahwa PIN tidak berubah karena aktivasi masih diproses dan meminta mencoba lagi sebentar. Urutan pengamanannya tidak berubah; yang berubah hanya kegagalan senyapnya.

## [Belum rilis] — Audit 735: dua penjaga saveAndStart Settings kini juga berlaku di MainActivity
- Blok `saveAndStart` disalin di kedua activity dan sudah menyimpang: versi Settings menolak Start saat tugas backup/restore berjalan dan menormalkan port privileged sebelum cek port, versi MainActivity tidak punya keduanya — menekan Start dari beranda di tengah restore bisa menghidupkan server di atas data yang sedang ditulis ulang, dan port tersimpan di bawah 1024 (mis. diatur lewat bot) langsung masuk jalur sibuk/root alih-alih dinormalkan. Kedua penjaga kini diterapkan juga di MainActivity (memakai helper bersama yang sama). Penyatuan penuh kedua salinan sengaja tidak dilakukan — sisanya adalah perbedaan sah antar-layar (input pengguna vs prefs tersimpan); yang disamakan adalah penjaga keamanannya.

## [Belum rilis] — Audit 734: jumlah baris log beranda kini dihitung inkremental, bukan dipindai penuh tiap detik
- `refreshHomeLog` memindai SELURUH teks tampilan (25–40 ribu karakter, karakter demi karakter) pada setiap tik 1 detik hanya untuk menghitung baris, begitu tampilan melewati 25 ribu karakter — di bawah banjir log itu terjadi hampir setiap tik di thread UI. Jumlah baris kini dipelihara sebagai penghitung: bertambah dari delta yang ditempel, dihitung ulang hanya atas teks sisa pada tik yang memang memangkas karakter (jauh lebih jarang), dan diturunkan persis sejumlah baris yang dibuang pada pangkas baris. Batas 40 ribu karakter / 150 baris dan hasil tampilannya tidak berubah.

## [Belum rilis] — Audit 732: penanda zip web-vault gagal tidak lagi meng-hash ulang seluruh zip
- `zipSudahGagal` dan `catatZipGagal` sama-sama membaca + meng-hash seluruh zip web-vault (puluhan MB) untuk mendapatkan sidiknya, padahal pada alur pemasangan zip itu baru saja diunduh dan digest-nya sudah dibuktikan sama dengan `.sha256` rilis (`expectedSha`). Kedua fungsi kini menerima sidik yang sudah diketahui itu dan melewatkan pembacaan ulang; isi penanda tidak berubah (tetap hex SHA-256 zip yang sama, jadi penanda lama tetap terbaca). Varian tanpa sidik tetap ada untuk pemanggil yang tidak memegang digest.

## [Belum rilis] — Audit 731: dua overload mati di Updater dihapus
- `downloadBinary(Context, File)` (tanpa pemanggil eksternal — produksi memakai varian `CekBatal`) dan `salinSambilHash` 5-arg (jalur produksi memakai varian 6-arg) dihapus. Keduanya murni permukaan mati yang menyesatkan pembaca jalur unduh. Nama metodenya tidak masuk `tools/bekas-hapusan.txt` karena nama yang sama masih terdefinisi oleh varian yang hidup (pengecualian aturan #15).

## [Belum rilis] — Audit 730: penanda versi/pin sesudah swap kini ditulis sinkron (commit)
- Penanda yang ditulis tepat sesudah pemasangan binary/web-vault — versi terpasang, pin SHA binary, dan cap web-vault (`KEY_WV_FROM`/`KEY_WV_SHA`/`KEY_WV_FALLBACK_FOR`) — sebelumnya memakai `apply()`, yang memperbarui memori seketika tetapi menulis disk secara asinkron: mati listrik di jendela sub-detik itu meninggalkan binary/WV BARU dengan penanda LAMA, yang terbaca sebagai "binary tidak utuh / WV basi" dan memicu unduh ulang penuh. Penanda-penanda itu kini memakai `commit()` (sinkron) — jendela campuran menyempit dari "sampai flush asinkron sempat jalan" menjadi hanya celah antar-operasi itu sendiri, dengan biaya satu tulis disk kecil sekali per update.

## [Belum rilis] — Audit 729: ekstrak web-vault tidak lagi fsync per berkas
- Ekstraksi web-vault memanggil `getFD().sync()` untuk setiap entri berkas — zip berisi ratusan berkas kecil dan di eMMC STB murah satu fsync bisa 10–100 ms, jadi ekstrak membayar detik sampai puluhan detik hanya untuk sinkronisasi. Fsync itu tidak membeli deteksi apa pun: keutuhan muatan sudah dibuktikan SHA-256 zip sebelum ekstrak, pemasangan bersifat atomik lewat rename direktori, dan kriteria siap hanya memeriksa `index.html`. Sync per berkas dihapus; sinkronisasi direktori (`syncDirAman`) yang menjaga rename tetap ada.

## [Belum rilis] — Audit 728: pembuatan rahasia alarm pertama kini diserialkan dengan kunci
- `rahasiaAlarm` melakukan baca-prefs → buat acak → commit tanpa sinkronisasi, padahal pemanggilnya datang dari banyak thread. Pada proses pertama sekali (rahasia belum tersimpan), dua pemanggil berbarengan bisa membuat dua rahasia berbeda dan keduanya commit — PendingIntent yang keburu dibangun dapat membawa rahasia yang kalah, sehingga satu alarm pertama ditolak "rahasia tak cocok". Seluruh urutan baca-buat-simpan kini berjalan di dalam satu kunci statis; jalur yang sudah punya rahasia hanya membayar satu monitor singkat.

## [Belum rilis] — Audit 727: blok kembar penghapusan pesan ber-PIN di pollOnce disatukan jadi satu helper
- Jalur jam-mundur dan jalur pesan-basi di `pollOnce` menjalankan urutan yang sama persis (±25 baris, hanya beda nama variabel): ambil pending bersyarat, nilai `pinBasiPerluHapus`, hapus pesan perintah, peringatkan bila gagal. Ini logika keamanan (penghapusan pesan berisi PIN), sehingga duplikasinya berisiko: pengetatan kelak harus diterapkan dua kali dan satu jalur bisa tertinggal. Urutan itu kini hidup di satu helper `hapusBilaPinBasi`; kedua jalur tinggal memanggilnya dengan teks/id pesannya masing-masing. Perilaku per jalur tidak berubah.

## [Belum rilis] — Audit 726: tiga salinan blok "tandai backup tertunda" kini memakai helper yang sudah ada
- `AlarmReceiver` menandai backup tertunda dengan blok try/commit yang ditulis ulang di tiga tempat, padahal helper `tandaiBackupTertunda` untuk persis itu sudah ada di berkas yang sama (dan dipakai jalur keempat). Ketiga salinan kini memanggil helper-nya — perilaku identik (commit sinkron, pengecualian ditelan), dan cara menandai kini hidup di satu tempat sehingga tak bisa menyimpang diam-diam di sebagian jalur.

## [Belum rilis] — Audit 724: poll bot tidak lagi menelurkan tugas executor hanya untuk menemukan nama bot sudah tersimpan
- `pollOnce` (tiap 20 detik, selamanya) memanggil `isiNamaBotBilaKosong`, yang tanpa syarat menyerahkan satu tugas ke pool latar; pemeriksaan "nama bot sudah tersimpan?" dan "token ini baru saja dicoba?" baru dilakukan di dalam tugas itu — ±130 ribu tugas sia-sia per bulan pada keadaan stabil. Kedua pemeriksaan murah itu (baca prefs dalam memori + stempel percobaan volatile) kini dilakukan lebih dulu di thread poll; tugas latar hanya diserahkan bila getMe benar-benar perlu dicoba. Pemeriksaan yang sama tetap ada di dalam tugas sebagai pengaman balapan.

## [Belum rilis] — Audit 723: penyegaran IP latar kini berpagar satu-sedang-berjalan
- `jadwalSegarkanIp` memasang satu tugas latar baru pada setiap panggilan `localIp()` selama cache basi — pemanggilnya termasuk tik UI 1 detik di kedua activity — tanpa pagar bahwa penyegaran sebelumnya masih berjalan. Tugas-tugas ekstra memang keluar murah di cek kesegaran, tetapi antreannya sia-sia. Kini ada pagar CAS: hanya satu penyegaran berjalan pada satu waktu, panggilan berikutnya selama penyegaran berjalan tidak memasang apa pun, dan pagar dilepas saat tugas selesai atau gagal masuk antrean.

## [Belum rilis] — Audit 722: jadwal restart-crash dan coba-lagi start kini memakai Runnable terpisah
- Satu `Runnable restartTunda` sebelumnya dipakai dua tujuan: jadwal restart sesudah crash (backoff) dan coba-lagi start yang tertunda kunci data (tiap 5 detik). Karena keduanya membatalkan + memasang Runnable yang sama, jadwal yang datang belakangan menimpa tujuan yang lain — restart bisa menembak lebih awal dari backoff-nya atau coba-lagi start bergeser mengikuti tunda crash. Coba-lagi start kini memakai `startTunda` sendiri dengan badan dan gerbang yang sama persis, dan ketiga jalur berhenti kini membatalkan keduanya. Perilaku tiap jalur tidak berubah; keduanya tidak bisa lagi saling membatalkan.

## [Belum rilis] — Audit 721: folder data kanonis hanya ditulis ke prefs bila benar-benar berubah
- `siapkanFolderData` menulis ulang `KEY_DATA_DIR` pada setiap start server walau nilai kanonisnya identik dengan yang tersimpan — setiap `apply()` menjadwalkan penulisan ulang seluruh berkas XML preferensi, termasuk pada tiap siklus mulai-ulang otomatis. Penulisan kini hanya terjadi bila nilai kanonis berbeda dari yang tersimpan (pola tulis-bila-beda yang sudah dipakai di tempat lain); validasi keamanan foldernya tidak berubah.

## [Belum rilis] — Audit 719: flush berkas log dari catatLog kini dibatasi sekali per detik
- `catatLog` (log konteks statis, ±45 situs panggil) memaksa flush disk pada SETIAP baris — buka stream append + tulis + tutup + stat panjang — menggugurkan penampungan 16 KB yang dipakai jalur log proses server. Saat semburan log (boot/restore/backup) itu I/O sinkron per baris di thread pemanggil, termasuk thread utama receiver. Flush paksa kini dibatasi maksimal sekali per 1000 ms; baris di antaranya tetap masuk penampungan dan ter-flush oleh ambang 16 KB atau flush berikutnya. Jendela kehilangan bila proses mati mendadak menyempit ke paling buruk ±1 detik baris konteks statis — jalur log server sendiri tidak berubah.

## [Belum rilis] — Audit 718: pembangunan ulang tampilan log saat mencari kini dibatasi lajunya
- Begitu kolom cari terisi, jalur delta murah LogActivity mati (delta menuntut kueri kosong), sehingga setiap perubahan log memicu pembangunan ulang penuh: salin ekor 100 KB, pindai redaksi, spannable sorotan 100 KB, dan layout ulang seluruh TextView di thread UI — berulang tiap tik 1,5 detik selama log mengalir. Pembangunan ulang untuk kueri yang sama kini dibatasi maksimal sekali per ±4,5 detik (kunci konten tidak dikonsumsi saat ditunda, jadi tik berikutnya menilai lagi); mengetik kueri baru tetap membangun seketika, dan jalur tanpa pencarian tidak berubah sama sekali.

## [Belum rilis] — Audit 717: digest unduhan kini dipakai ulang — binary tidak lagi di-hash tiga kali per pemasangan
- `unduhKeTmp` sudah menghitung digest SHA-256 streaming atas seluruh berkas dan mencocokkannya ke `.sha256` rilis di dalam loop unduh, tetapi nilai kembaliannya dibuang oleh semua pemanggil: `pasangHasilUnduh` membaca ulang seluruh tmp untuk hash yang sama, dan penulisan pin sesudah pasang membaca ulang seluruh berkas hasil sekali lagi. Untuk binary puluhan MB di STB lambat itu dua lintasan baca+hash penuh yang murni pengulangan, berjalan sambil memegang kunci pasang. Digest unduhan kini ditangkap dan diteruskan ke varian `pasangHasilUnduh` ber-digest (varian lama tetap ada untuk pemanggil tanpa digest, termasuk uji), dan pin ditulis dari `expectedSha` yang baru saja dibuktikan cocok oleh pemasangan itu sendiri. Verifikasi pin pada setiap start server (`binaryUtuh`) sengaja TIDAK diubah — itu pemeriksaan integritas anti-utak-atik antar-start, bukan pengulangan.

## [Belum rilis] — Audit 716: cek update kini berjalan di pool khususnya sendiri, bukan pool latar bersama
- Sisa Audit 704/706 yang terverifikasi: cek kedua menunggu `KUNCI_CEK` tanpa batas *di dalam* pool `BG_LAMA` yang hanya berisi 2 thread dan dipakai bersama ServerService/TgBackup — saat cek pertama mengunduh binary/web-vault di koneksi menetes (belasan menit), satu thread menunggu dan sisa kolam tinggal satu; antrean penuh mulai menolak tugas latar lain. Cek update kini diserahkan ke pool khusus satu thread (`Util.jalankanCek`, antrean kecil): cek kedua mengantre di kolamnya sendiri dan tetap berjalan sesudah cek pertama dengan penanda segar (semantik Audit 706 utuh), sementara `BG_LAMA` tidak pernah lagi tertahan oleh penantian cek.

## [Belum rilis] — Audit 715: pengambilan versi terbaru tidak lagi memegang kunci selama fetch HTTP
- `latestVersion()` menjalankan seluruh fetch (sambung + baca badan, timeout 10+10 detik) di dalam `synchronized (KUNCI_VERSI)`, dan pembaca versi bawaan dari thread UI (`onCreate` kedua activity) masuk monitor yang sama — membuka aplikasi tepat saat cek latar mengambil versi bisa membekukan UI selama sisa fetch. Fetch kini berjalan di luar kunci: kunci hanya menjaga baca/tulis cache dan stempel gagal, dengan semantik yang sama (cache segar dan jeda-gagal diperiksa sebelum fetch, hasil dan stempel disimpan sesudahnya). Dua fetch yang kebetulan berbarengan sama-sama mengambil JSON kecil yang sama dan penulis terakhir menang — tidak ada keadaan yang bisa rusak.

## [Belum rilis] — Audit 583: aplikasi kini muncul di layar utama Android TV murni (keputusan owner 2026-10-29)
- Aplikasi tidak mempunyai kategori `LEANBACK_LAUNCHER` sama sekali, sehingga di perangkat Android TV murni (launcher Leanback/Google TV, mis. Mi TV Stick) aplikasi tidak muncul di layar utama — hanya bisa dibuka lewat Pengaturan → Aplikasi. Kini `MainActivity` mendaftarkan kategori Leanback di samping `LAUNCHER` biasa, `application` membawa `android:banner` (banner TV baru `tv_banner`, 640×360, gradasi dan perisai mengikuti ikon), dan `uses-feature android.software.leanback` dinyatakan `required="false"` — perangkat non-TV dan STB AOSP tidak terpengaruh sama sekali (fitur dinyatakan tidak wajib, kategori tambahan diabaikan launcher biasa, dan syarat layar sentuh memang sudah `required="false"`).

## [Belum rilis] — Audit 714: empat kolom tersimpan-otomatis kini dibatasi panjangnya
- Kolom token bot, chat id, sandi backup, dan SHA binary tidak punya batas panjang di layout maupun jalur simpan: tempelan keliru berukuran ratusan KB (isi berkas/log di UI remote TV) tersimpan apa adanya ke SharedPreferences — yang dimuat utuh ke memori dan ditulis ulang pada tiap `apply()` di semua layar — dan token raksasa itu ikut disematkan ke URL API Telegram sampai bot mati tanpa penjelasan. Keempat kolom kini ber-`maxLength` lapang di atas kebutuhan sahnya (token 128, daftar chat 512, sandi 256, SHA 128; pola Audit 685), sehingga tempelan patologis terpotong di pintu masuk.

## [Belum rilis] — Audit 713: alarm tunda-boot yatim kini dibatalkan saat auto-backup dimatikan, dan cabangnya memeriksa flag fitur
- One-shot tunda-boot tidak pernah dibatalkan di mana pun: mematikan auto-backup hanya membatalkan rantai harian, sehingga alarm tunda yang terpasang saat boot tetap menembak — dan cabang TUNDA di receiver tidak memeriksa `KEY_TG_AUTO`, sehingga peringatan Telegram "jam STB salah, backup manual" terkirim untuk fitur yang baru saja dimatikan. Jalur nonaktif `schedule` kini membatalkan PendingIntent tunda-boot juga, dan cabang TUNDA pulang diam-diam bila fitur mati, sejajar dengan semua jalur saudaranya.

## [Belum rilis] — Audit 712: kegagalan auto-start boot karena izin dicabut kini diperingatkan lewat Telegram
- Auto-start boot memanggil service tanpa gerbang izin: bila izin penyimpanan dicabut atau di-reset sistem sesudah sebelumnya diberikan, start gugur di cek tulis service dan satu-satunya jejaknya adalah log di dalam aplikasi — pemilik STB headless baru sadar saat vault tak bisa dibuka. Jalur boot kini memeriksa dulu: folder data butuh izin eksternal tetapi akses tidak ada → catatan log eksplisit + peringatan Telegram (pola pesan boot yang sama dengan peringatan restore terputus), sementara start tetap dicoba seperti biasa agar penilaian service menjadi kata akhir.

## [Belum rilis] — Audit 711: notifikasi update yang tertahan izin notifikasi kini dicatat, tidak lagi senyap
- Di Android 13+, `tampilkanNotifikasi` memeriksa `POST_NOTIFICATIONS` lalu pulang `false` tanpa catatan apa pun — dan izin itu tidak pernah diminta di mana pun di aplikasi — sehingga pada perangkat/OEM yang memperlakukan aplikasi target lama sebagai belum berizin, notifikasi "update tersedia" mati diam-diam tanpa jejak untuk diagnosis. Penahanan kini menulis satu baris log yang menyebut izinnya. (Permintaan izin runtime-nya sendiri adalah keputusan UX terpisah: konteks pemeriksanya adalah cek latar tanpa activity.)

## [Belum rilis] — Audit 710: penolakan izin permanen kini dibedakan dan dinyatakan terus terang
- Seluruh repo tak pernah memeriksa `shouldShowRequestPermissionRationale`: penolakan permanen ("jangan tanya lagi") menerima toast yang sama dengan penolakan biasa, padahal dialog sistem tak akan pernah muncul lagi dan siklus Start berulang terlihat seperti tombol rusak. `tanganiPenolakan` kini menilai kepermanenan lewat predikat murni teruji `penolakanPermanen` (rationale mati + izin belum diberikan, di konteks sesudah penolakan nyata) dan memakai pesan khusus yang menyatakan dialog tidak akan muncul lagi serta satu-satunya jalan lewat Pengaturan, dengan catatan log yang sejalan.

## [Belum rilis] — Audit 709: prompt izin penyimpanan di awal kini hanya bila folder data memang eksternal
- Kedua activity meminta izin penyimpanan tanpa syarat di `onCreate` — dialog muncul pada setiap cold start selama izin belum diberikan, termasuk bagi pengguna ber-folder internal yang tidak butuh izin sama sekali, mengajari penolakan refleks dan menembakkan permintaan senyap selamanya sesudah "jangan tanya lagi". `mintaIzinBilaPerlu` kini punya varian ber-folder yang pulang tanpa meminta bila `butuhIzinEksternal` false, dan kedua situs awal memakainya dengan folder data tersimpan; gerbang Start (`siapStart`) tetap menjadi penegak utama saat izin benar-benar dibutuhkan.

## [Belum rilis] — Audit 708: URL dan catatannya kini dihitung dari satu bacaan IP yang sama
- `localUrlDenganCatatan` (Audit 698) memanggil `localIp()` dua kali — sekali di dalam `localUrl`, sekali untuk catatan — dan `localIp()` berpola stale-while-revalidate: cache yang kedaluwarsa tepat di antara keduanya bisa membuat catatan transisi/loopback dihitung dari IP yang berbeda dari host yang tampil di URL selama satu siklus. Host kini dibaca sekali dan diteruskan ke pembangun URL maupun penilai catatan lewat helper bersama `localUrlDariHost`.

## [Belum rilis] — Audit 707: timeout baca badan hapus pesan dikencangkan ulang ke sisa tenggat sesudah header
- Sisa batas Audit 694 (self-review putaran-26): tenggat total diperiksa di antara potongan, tetapi satu `read()` yang menggantung di fase badan masih bisa menambah sampai `tBaca` awal (≤30 dtk) melewati cap, karena timeout soket dihitung dari sisa anggaran di awal percobaan — sementara fase header (`getResponseCode`) juga mengonsumsi jendela yang sama. Sesudah kode respons diterima, timeout baca kini dikencangkan ulang ke sisa tenggat yang sebenarnya sebelum badan dibaca, sehingga satu pembacaan terakhir yang menggantung terpotong di tenggat total, bukan di jendela awal percobaan.

## [Belum rilis] — Audit 706: serialisasi cek update kini mengantre, bukan melewatkan
- Sisi lain Audit 704 (self-review putaran-26): pagar CAS membuat cek yang datang kedua *dilewati seluruhnya* — termasuk, untuk cek lengkap Settings, blok web-vault dan satu-satunya pemicu dialog tawarannya pada kunjungan itu. Pagar kini berupa kunci yang mengantre (kedua cek berjalan di pool latar, bukan thread UI): cek kedua menunggu cek pertama selesai lalu berjalan dengan penanda yang sudah diperbarui, sehingga pengumuman tetap tidak ganda (penanda versi dibaca sesudah cek pertama menulisnya) dan tidak ada pemicu yang tertelan. Harga yang dibayar hanya pengulangan pemeriksaan versi yang murah karena cache 15 menitnya.

## [Belum rilis] — Audit 705: penanda jaringan-mati kini ikut direset bersama penanda IP lain
- Penanda `ipMatiDiperingatkan` (Audit 697) tertinggal dari kedua titik reset sesi yang mereset kelima penanda IP lainnya: satu-satunya resetnya hanya terjangkau lewat IP non-loopback yang berbeda, sehingga sesudah pulih dengan IP yang sama, episode jaringan mati berikutnya dalam proses yang sama tidak lagi dicatat — mengaburkan diagnosis. Penanda kini direset di `resetPenandaJalan` dan di penyiapan sesi `jalankanProses`, sejajar dengan saudara-saudaranya; catatan satu-kali-per-episode kembali berlaku per sesi server.

## [Belum rilis] — Audit 704: cek update kini terserialkan antar-instance
- MainActivity dan SettingsActivity sama-sama menjalankan `AutoUpdate.cek` dari `onCreate` di pool latar yang sama, dan penanda tawarannya berpola baca-periksa-tulis tanpa serialisasi: membuka Settings sekejap sesudah layar utama (saat cek pertama masih menunggu jaringan) membuat kedua cek mengumumkan versi yang sama dua kali — toast ganda, dan berpotensi notifikasi + pesan Telegram ganda. `cek` kini berpagar CAS statis: cek kedua yang datang saat satu cek berjalan dilewati dengan catatan log (cek yang berjalan mencakup tanah yang sama), dan pagar dilepas di `finally`.

## [Belum rilis] — Audit 703: penanda tawaran web-vault kini ditulis hanya bila dialog benar-benar tampil
- Penanda `wv_notified_<versi>` ditulis *sebelum* `DialogAman.tampil`, dan `tampil` menelan kegagalan `show()` (activity bertransisi, BadToken) — dialog yang tidak pernah dilihat pengguna mengubur tawaran untuk versi server itu selamanya. Penanda kini ditulis sesudah pemanggilan, hanya bila dialog terbukti `isShowing()`; kegagalan tampil membiarkan tawaran dicoba lagi pada kesempatan berikutnya.

## [Belum rilis] — Audit 702: auto-restart sesudah update kini berlaku juga untuk instalasi dari layar utama
- Instalasi binary otomatis berjalan di kedua mode cek, tetapi penilaian auto-restart-nya terkurung di mode lengkap (Settings) dan `restartServer()` MainActivity adalah no-op: instalasi yang kebetulan terpicu dari layar utama tidak pernah menindaklanjuti preferensi `auto_restart_update`, dan cek berikutnya tak menebusnya karena versi sudah tercatat sama. Blok restart kini dinilai di luar gerbang mode (sesudah blok web-vault yang memang khusus mode lengkap), dan aksi restart MainActivity benar-benar menjalankan `ServerService.restart` seperti Settings.

## [Belum rilis] — Audit 701: cek update tak lagi memercayai versi memori saat server berhenti
- `ServerService.binaryVersion` tidak dibersihkan saat server berhenti (sudah didokumentasikan Audit 648 di jalur saudara), tetapi `AutoUpdate.cek` membacanya tanpa gerbang: bila binary di disk diganti selagi server berhenti (pasang versi pilihan/downgrade), cek menyimpulkan dari versi basi di memori — dan pada cabang "sudah sama" ia **menulis `KEY_UPDATE_VERSION = latest` yang salah secara persisten** dan membatalkan tawaran/notifikasi, sehingga tawaran sah tertelan sampai Start berikutnya. Versi berjalan kini hanya dipercaya saat server memang berjalan (pola yang sama dengan `Updater`); saat berhenti, cek bersandar pada versi tercatat/bawaan.

## [Belum rilis] — Audit 700: entri zip web-vault yang dilewati kini tercatat
- Ekstraksi web-vault melewati entri yang ditolak filter nama/kanonis tanpa log dan tanpa hitungan, sementara kriteria "siap pasang" hanya memeriksa `index.html`: bila arsip resmi suatu versi memuat nama berkas yang ditolak filter, pemasangan dinyatakan sukses dan aset itu 404 di UI vault tanpa satu baris pun catatan untuk mendiagnosisnya. Entri yang terlewat kini dihitung dari ketiga cabang lewati dan, bila ada, dicatat satu baris log dengan jumlahnya sesudah ekstraksi. Filter dan kriteria pasangnya tidak berubah — yang ditambah hanya jejaknya.

## [Belum rilis] — Audit 699: penanda staging web-vault kini didaftarkan sebelum foldernya dibuat
- Urutan pembuatan staging adalah buat folder dulu, baru daftarkan penanda aktif — di celah satu pernyataan itu `cleanupTempFiles` (dipanggil dari jalur Start, di bawah kunci web-vault yang tidak dipegang ekstraksi) bisa membaca folder baru sebagai sisa staging mati dan menghapusnya, membuat ekstraksi menulis ke folder yang hilang dan update gagal. Penanda kini didaftarkan sebelum folder dibuat (nama staging memang sudah unik sejak dialokasikan), sehingga tidak ada celah di mana folder staging hidup tampak yatim bagi penyapu.

## [Belum rilis] — Audit 698: label URL pasif kini menandai keadaan loopback dan transisi IP
- Tampilan URL di layar utama, Settings, dan status bot selalu memakai IP terbaru tanpa konteks: selama jendela stabilisasi perubahan IP, alamat yang ditampilkan belum dilayani sertifikat yang benar (DOMAIN masih IP lama), dan saat jaringan mati yang tampil adalah `127.0.0.1` kepada pengguna jarak jauh — kedua permukaan menyesatkan walau jalur *salin* URL sudah jujur. Ketiga permukaan pasif kini memakai `localUrlDenganCatatan`: helper murni teruji `catatanUrl` menambahkan "(loopback - IP LAN tidak terdeteksi)" atau "(IP baru - server masih memakai IP lama sampai restart)" sesuai keadaan, dan kosong bila keadaan normal.

## [Belum rilis] — Audit 696: catch pemulih Settings hanya membersihkan penanda restore milik percobaannya sendiri
- Sisi lain Audit 679 (self-review putaran-25): catch pemulih di restore lokal membungkus seluruh metode, termasuk fase sebelum penanda percobaan ini dipasang — kegagalan fase ekstraksi pada percobaan baru bisa menghapus penanda lama milik restore terputus sebelumnya, sehingga peringatan boot tentang keadaan hibrida tak pernah tampil. Pembersihan penanda di catch kini digerbang penanda lokal `tandaRestoreSendiri` yang hanya menyala sesudah `tandaiRestoreMulai` percobaan ini sendiri berjalan; penanda warisan percobaan lain tidak disentuh jalur ini.

## [Belum rilis] — Audit 695: kedaluwarsa sesi PIN tertunda kini juga dinilai dengan waktu hidup
- Pasangan Audit 683 yang tertinggal (self-review putaran-25): kesegaran pesan sudah dinilai dengan waktu saat pesan diproses, tetapi kedaluwarsa sesi PIN tertunda (`ambilPending`) masih memakai potret waktu awal batch — balasan PIN bisa diterima melampaui batas 5 menit sejak pending dibuat sebesar durasi pemrosesan item-item sebelumnya dalam batch yang sama. Penilaian kini memakai waktu hidup yang sama, konsisten dengan `pesanSegar`.

## [Belum rilis] — Audit 694: tenggat hapus pesan kini total, bukan per-pembacaan
- Sisa teoritis dari Audit 683 (self-review putaran-25): `setReadTimeout` membatasi jeda *antar* data, bukan total — respons yang menetes (drip-feed) memperbarui jendela baca pada tiap potongan, sehingga satu percobaan hapus secara teoretis bisa jauh melewati cap 60 detik. Badan respons `deleteMessage` kini dibaca pembaca khusus yang memeriksa tenggat total operasi di antara potongan dan melempar bila lewat (respons sah hanya `{"ok":true,...}` belasan byte; pembaca berhenti di 64 KB). Cap 60 detik sekarang tenggat total yang sebenarnya untuk jalur ini.

## [Belum rilis] — Audit 693: commit offset dini dipersempit ke callback yang benar-benar tak idempoten
- Penimbangan ulang trade-off Audit 682 (self-review putaran-25): commit-sebelum-eksekusi untuk *semua* callback membuat setiap ketukan bersifat at-most-once — ketukan Stop/Backup/dll. bisa hilang diam-diam bila proses mati tepat sesudah commit. Padahal yang benar-benar rusak oleh eksekusi ganda hanyalah aksi yang memutar keadaan: `/careset` (CA baru membuat CA yang baru dipasang basi) dan `/restore` (menerapkan ulang backup). Commit dini kini hanya untuk dua perintah itu; callback lain (berhenti, mulai, restart, backup, update, navigasi, tampilan) kembali ke semantik at-least-once lewat penyimpanan offset akhir batch — pengiriman ulang untuk mereka berakhir pada keadaan yang sama atau tertahan pagar tugas berat yang sudah ada.

## [Belum rilis] — Audit 697: jaringan mati total tidak lagi dibaca sebagai perubahan IP
- `lanHost()` mengembalikan `127.0.0.1` bila semua antarmuka turun, dan mekanisme perubahan IP tidak punya pengecualian untuk nilai itu: kepadaman jaringan yang cukup lama (router reboot) dibaca sebagai "IP berubah → 127.0.0.1", server di-restart ke mode loopback dengan pesan Telegram yang menyesatkan, lalu di-restart sekali lagi saat jaringan pulih walau IP-nya sama persis — dua restart dan satu jendela sertifikat tanpa SAN LAN untuk satu kepadaman. Keadaan loopback kini diperlakukan sebagai jaringan mati, bukan perubahan: kandidat perubahan dibersihkan, tidak ada restart dan tidak ada pengumuman "IP berubah", hanya satu catatan log jujur "jaringan tidak terdeteksi — menunggu pulih". Predikat murni `restartIpBolehDiminta` juga menolak loopback sebagai lapis kedua (terkunci uji).

## [Belum rilis] — Audit 692: gagal meluncurkan layar PIN kini gagal-tertutup
- Bila `startActivityForResult(PinActivity)` melempar, kedua activity dulu hanya menyetel ulang penanda dan berjalan terus: refresh memang terhalang, tetapi kontrol dan menu tetap bisa dipakai tanpa gerbang pada sesi itu — arah gagal yang terbuka. Kini kegagalan peluncuran menutup activity (`finish()`): gerbang yang tidak bisa ditegakkan tidak digantikan dengan sesi tanpa gerbang. Pemicunya sangat langka (komponen internal nonaktif/keadaan sistem rusak); pengguna cukup membuka ulang aplikasi.

## [Belum rilis] — Audit 691: konten disegel sejak onCreate bila layar PIN diperlukan
- UI dibangun penuh di `onCreate` sementara layar PIN baru diluncurkan dari `onResume`, sehingga pada cold start di perangkat lambat satu-dua frame konten (status/log) bisa tergambar sebelum layar kunci menutupinya. MainActivity dan SettingsActivity kini menyegel konten (`INVISIBLE` pada akar tampilan) sejak `onCreate` bila gerbang menyatakan layar PIN diperlukan, dan segel dibuka oleh tik refresh pertama yang lolos gerbang — kilasan tertutup secara struktural, bukan hanya dikurangi tema splash. Batal di layar PIN tetap menutup activity seperti sebelumnya, jadi segel tidak pernah meninggalkan layar kosong yang menggantung.

## [Belum rilis] — Audit 690: Settings dan Log tak lagi meninggalkan thumbnail di Recents
- MainActivity dan PinActivity sudah `excludeFromRecents`, tetapi SettingsActivity dan LogActivity belum: snapshot Recents LogActivity memuat isi log penuh keadaan terakhir — konten yang aplikasi sendiri perlakukan sensitif (ada penyamaran untuk log yang dibagikan) — dan bisa dibaca dari layar Recents tanpa melewati gerbang PIN, walau membuka activity-nya tergerbang. Kedua activity kini `excludeFromRecents="true"` seperti saudara-saudaranya. `FLAG_SECURE` sengaja tidak ditambahkan agar tangkapan layar yang disengaja pemilik tetap bisa.

## [Belum rilis] — Audit 688: LogActivity kini memeriksa ulang gerbang PIN di setiap tik
- Gerbang LogActivity hanya diperiksa di `onResume`; tik refresh 1,5 detiknya mengalirkan log terbaru tanpa batas sesudah sekali buka — berbeda dari Main/Settings dan membuat satu grace sesaat membuka aliran log selamanya selama layar terbuka. Pemeriksaan gerbang kini diekstrak ke satu helper yang dipakai `onResume` dan setiap tik: begitu grace berakhir atau PIN diaktifkan, tik berikutnya menutup activity (gagal-tertutup, sama seperti jalur masuknya).

## [Belum rilis] — Audit 687: grace PIN kedaluwarsa di foreground kini benar-benar mengunci ulang
- Sesudah dibuka, grace 30 detik berakhir walaupun activity masih di foreground — tetapi tidak ada yang menindaklanjutinya sampai `onResume` berikutnya: loop refresh berhenti total (status/log membeku seperti aplikasi macet) sementara tombol dan field tetap berfungsi penuh tanpa PIN. Itu kunci setengah-terapan: membeku di tampilan, terbuka di kontrol. Tik refresh di MainActivity dan SettingsActivity kini memanggil peluncur layar PIN yang sama dengan jalur `onResume` saat gerbang mulai menahan konten (peluncurnya berpagar `pinBukaJalan` sehingga tidak berulang): layar terkunci sungguhan, dan membatalkan di layar PIN menutup activity seperti jalur masuk yang sudah ada.

## [Belum rilis] — Audit 686: tenggat pembersihan clipboard kini memakai jam dinding, bukan elapsedRealtime
- Penanda kedaluwarsa clipboard disimpan persisten di preferensi tetapi nilainya berasal dari `elapsedRealtime`, yang ter-reset tiap boot: menyalin nilai sensitif saat uptime sudah berhari-hari lalu perangkat reboot membuat sisa tenggat dihitung berhari-hari, dan pembersihan "30 detik" tidak berlaku pada jalur lintas-reboot itu. Penanda kini memakai jam dinding (`currentTimeMillis`) saat ditulis dan dibaca, sehingga tenggat tetap benar sesudah reboot; risiko lompatan jam dinding sebatas membersihkan lebih awal/akhir sesaat, dan pembersih tetap memverifikasi hash isi sebelum menghapus sehingga tak menyentuh clipboard milik lain. Penanda lama berbasis uptime yang masih tersimpan akan terbaca sudah lewat tenggat (jam dinding jauh lebih besar) dan dibersihkan pada pemeriksaan pertama — arah yang aman.

## [Belum rilis] — Audit 685: kueri pencarian log dibatasi 64 karakter
- Kolom cari log tidak membatasi panjang kueri, sementara biaya penyorotan hasil sebanding teks-tampil (maks 100 ribu karakter) × panjang kueri per putaran: menempel kueri ribuan karakter membuat satu putaran sorot berjalan sangat lama dan, selama `redaksiJalan` terpasang, semua refresh log berikutnya tertahan — layar log membeku menampilkan data basi tanpa penjelasan. Kueri kini dibatasi 64 karakter di layout (`maxLength`, berlaku juga untuk tempelan) dan dijepit lagi di kode saat dibaca, sebagai lapis kedua.

## [Belum rilis] — Audit 684: satu baris log kini dibatasi 8192 karakter
- Semua batas log yang ada menjaga *total* (buffer 300 ribu karakter, berkas 2 MB), bukan *satu baris*: satu baris raksasa dari stdout Vaultwarden (jejak panic membengkak atau muatan tak tepercaya yang tercatat utuh) teralokasi utuh berkali-kali dalam perjalanan readLine → buffer → berkas → ekor crash — lonjakan memori yang bisa meng-OOM-kan aplikasi host di STB justru saat server bermasalah. Kedua pintu masuk log (`appendLog` dan `catatLog`) kini memotong baris di 8192 karakter dengan penanda "…[baris dipotong]" lewat helper murni teruji `potongBarisLog`; kepala baris (konteks pesan) dipertahankan.

## [Belum rilis] — Audit 683: hapus pesan menghormati cap 60 detiknya di dalam percobaan; kesegaran dinilai per pesan
- Dua tepi jalur poll: (1) `hapusPesanPerintah` memeriksa cap `BATAS_HAPUS_PESAN_MS` hanya *di antara* percobaan, sementara satu percobaan bisa membakar ±45 detik (sambung 15 + baca 30) — satu pesan bisa menyita ±90 detik thread poll tunggal dan batch berisi beberapa pesan ber-PIN berseri menjadi hitungan menit. Timeout tiap percobaan kini dijepit ke sisa anggaran (sambung maks setengah sisa, baca maks sisanya; sisa < 2 detik = berhenti), sehingga cap 60 detik berlaku di dalam percobaan juga. (2) Kesegaran pesan (`pesanSegar`) dinilai dari potret waktu awal batch, sehingga pesan di urutan akhir bisa lolos batas basi 5 menit karena item sebelumnya membakar waktu; penilaian kini memakai waktu saat pesan itu benar-benar diproses.

## [Belum rilis] — Audit 682: callback tombol kini commit offset sebelum eksekusi, seperti perintah teks ber-PIN
- Perintah teks berbahaya sudah lama dilindungi anti-putar-ulang (offset di-commit *sebelum* eksekusi), tetapi callback tombol — jalur UI utama untuk aksi yang sama (`/careset`, `/backup`, `/update`, `/stop`, `/restart`) — offset-nya baru disimpan di akhir seluruh batch. Proses yang mati di tengah batch membuat Telegram mengirim ulang callback yang sama dan aksinya berjalan dua kali; pada `/careset` itu berarti CA hasil reset pertama langsung basi oleh reset kedua dan HTTPS rusak sampai CA ketiga dipasang. Offset kini di-commit sebelum `tanganiCallback` untuk semua callback: aksi berbahaya tak bisa diputar ulang oleh kematian proses, dan harga untuk callback navigasi biasa hanyalah satu ketukan yang mungkin perlu diulang bila proses mati tepat di antaranya.

## [Belum rilis] — Audit 681: unlock kunci pasang global kini tak bisa terlewati finally
- Di kedua pintu kunci pasang global, `unlock()` berada sesudah `selesaiUnduhan()` dalam `finally` yang sama: bila `selesaiUnduhan()` suatu hari melempar (saat ini ia murni operasi atomik + volatile sehingga belum bisa — kerapuhan struktural temuan self-review), satu hold bocor dan semua pemasangan berikutnya mengantre selamanya. Urutannya kini finally bersarang sehingga `unlock()` adalah tindakan terakhir yang dijamin berjalan apa pun yang terjadi di pembukuan unduhan.

## [Belum rilis] — Audit 680: pagar cek port kini dilepas juga bila tugas latarnya melempar
- Pagar `startTertundaPort` (Audit 666) hanya dilepas di penyelesaian `ui.post` dan di cabang penolakan antrean. Badan tugas latarnya (`bereskanServerYatim` + `isPortBusy` + `portButuhRoot`) tidak berpagar pengecualian: satu lemparan di sana membuat penyelesaian tak pernah berjalan, pagar terkunci permanen (di MainActivity `uiBusy` statis ikut terkunci), dan ketukan Start berikutnya mati diam-diam tanpa umpan balik sampai activity dibuat ulang. Kedua activity kini membungkus badan tugas itu dengan penangkap yang memasang penyelesaian pelepas-pagar + catatan log "cek port gagal, Start dibatalkan" — kegagalan diperlakukan sebagai batal yang jujur, bukan kunci yang tertinggal.

## [Belum rilis] — Audit 679: penanda restore dibersihkan pada jalur gagal-bersih
- Penanda persisten Audit 661 hanya dibersihkan di baris sukses, sehingga kegagalan yang *bersih* — staging DB korup sehingga penukaran tidak pernah terjadi dan DB lama dipertahankan, atau rollback Settings yang berhasil mengembalikan DB lama — tetap meninggalkan penanda. Boot berikutnya lalu memunculkan peringatan "restore tidak selesai, database mungkin sudah baru, restore ulang" yang keliru untuk keadaan itu. Penanda kini dibersihkan tepat di cabang-cabang yang membuktikan keadaan lama utuh (`galatDb` di kedua jalur, dan sesudah `pulihkanDbLama` berhasil); jalur pengecualian di tengah penukaran — keadaan yang memang tidak pasti — tetap membiarkannya, sesuai maksud penanda itu.

## [Belum rilis] — Audit 678: restore lokal Settings kini memvalidasi config seperti jalur bot
- Pasangan Audit 663 yang tertinggal (self-review putaran-24): perbaikan validasi config putaran lalu hanya menambal jalur restore bot. Jalur restore zip lokal di Settings tetap menerapkan `prefs` tanpa memeriksa penanda `app` dan tanpa memeriksa `prefs == null`, lalu mencatat "Pengaturan dari backup ikut diterapkan" apa pun yang terjadi — padahal `applyPrefsFromJson` kembali diam-diam untuk argumen null. Jalur lokal kini memeriksa penanda aplikasi dan keberadaan objek `prefs` dengan pesan peringatan yang sama terus terangnya; klaim diterapkan hanya muncul bila pengaturan benar-benar diterapkan, dan penjadwalan ulang backup/bot hanya berjalan pada jalur yang sah itu.

## [Belum rilis] — Audit 677: bukti panic tidak lagi terhapus oleh probe rescue yang gagal
- Regresi dari perbaikan Audit 653 (self-review putaran-24): probe paksa-shim menimpa `lastVersionOutput`, dan jalur gagalnya menyetelnya kosong — bila probe dengan-shim gagal tanpa teks panic, pemeriksaan panic terakhir di `siapkanShim` membaca output baru itu, tak menemukan panic, dan Start lanjut **tanpa shim dan tanpa blok panic yang jujur**, padahal cabang rescue dimasuki justru karena bukti panic tanpa-shim sudah ada. Bukti sebelum rescue kini disimpan dan dipulihkan sesudah probe gagal, sehingga penilaian terakhir kembali ke bukti yang benar: panic tanpa shim tetap menghentikan start secara jujur bila shim tak terbukti menolong.

## [Belum rilis] — Perbaikan build: deklarasi KEY_RESTORE_TANDA dipindah sebelum pemakainya
- Run `3ba2190` merah di kompilasi: daftar `TAK_DIEKSPOR_KEYS` (Audit 659) merujuk konstanta `KEY_RESTORE_TANDA` yang deklarasinya berada jauh di bawah inisialisasi statis tersebut — Java menolaknya sebagai *illegal forward reference*. Deklarasi konstanta dipindah ke blok konstanta di kepala `TgBackup`, sebelum semua pemakainya; tidak ada perubahan perilaku.

## [Belum rilis] — Audit 676: kerja boot tak lagi jatuh sinkron ke thread broadcast saat antrean penuh
- Jalur cadangan BootReceiver menjalankan seluruh kerja boot secara sinkron di thread penerima broadcast bila pool latar menolak tugas — mengonsumsi jendela broadcast (~10 detik, `goAsync()` tidak menolong bila kerja justru berjalan di `onReceive`) dan berisiko ANR/pemutusan proses di tengah kerja pada STB lambat. Cadangannya kini berupa thread khusus (pola yang sama dengan cadangan di TgBotReceiver): kerja tetap selesai di luar thread broadcast; hanya bila pembuatan thread pun gagal, penanganan boot dilewati dengan log terus terang dan `PendingResult` ditutup rapi — bukan dijalankan paksa di thread broadcast.

## [Belum rilis] — Audit 675: langkah boot kini terisolasi — satu gagal tidak membatalkan langkah lain
- Seluruh pekerjaan BootReceiver berjalan dalam satu `try` besar ber-`catch` generik: satu langkah awal yang melempar (mis. migrasi preferensi pada bentuk data tak terduga) membatalkan auto-start server pada boot itu — padahal `auto_start` aktif dan tidak ada penanda tertunda untuk kasus ini — dan kegagalan jadwal backup membatalkan jadwal bot; satu-satunya jejak adalah satu baris log tanpa konteks langkah. Kini tiap kelompok langkah berpagar sendiri dengan log berkonteks: persiapan (cek penanda restore + heal/migrasi preferensi), jadwal backup, dan jadwal bot; auto-start sudah terisolasi sejak sebelumnya. Kegagalan satu langkah tidak lagi membatalkan langkah boot lainnya.

## [Belum rilis] — Audit 674: bolehHapusFile tidak lagi mengizinkan direktori akarnya sendiri
- Penjaga "hanya boleh hapus di dalam cacheDir/filesDir" juga menyetujui target yang persis sama dengan direktori akar itu sendiri (cabang `equals`), sehingga pemanggil yang meneruskan jalur hasil gabungan bisa menghapus `cacheDir`/`filesDir` utuh dan tetap lolos pemeriksaan. Pemanggil satu-satunya saat ini meneruskan berkas zip tertentu sehingga belum ada jalur aktif; cabang `equals` kini dicabut — yang boleh dihapus hanya isi **di dalam** akar, bukan akarnya — dan terkunci uji. Perlindungan symlink-ke-luar (jalur kanonis) tidak berubah.

## [Belum rilis] — Audit 673: tambahUkuranUnzip kini menjaga luapan long di dalam fungsi
- Penjaga akuntansi ukuran unzip menjumlahkan `total + tambah` tanpa cek luapan: hasil yang membungkus melewati `Long.MAX_VALUE` menjadi negatif, lolos dari pemeriksaan batas, dan mengembalikan total negatif — pemeriksaan berikutnya mulai dari angka rusak. Semua pemanggil saat ini meneruskan byte yang benar-benar terbaca sehingga luapan belum terjangkau, tetapi invariant itu dipegang pemanggil, bukan fungsinya. Penjumlahan kini dijaga eksplisit: luapan diperlakukan sebagai pelampauan batas (lempar `IOException`), terkunci uji di tepi `Long.MAX_VALUE`.

## [Belum rilis] — Audit 672: mdSha256 kini mengembalikan digest baru — tak ada lagi keadaan bersama per thread
- `mdSha256()` sebelumnya membagikan satu `MessageDigest` per thread lewat ThreadLocal dan me-*reset* pada setiap pengambilan. Itu rapuh terhadap pemakaian bersarang: kode yang sedang mengakumulasi hash streaming (unduhan, sidik berkas) akan kehilangan seluruh akumulasinya bila fungsi lain di thread yang sama mengambil digest di tengah — checksum akhir salah dan unduhan sah ditolak. Semua pemanggil saat ini terverifikasi tidak bersarang, tetapi invariant-nya dipegang disiplin pemanggil, bukan API-nya. Kini setiap pengambilan mengembalikan instance baru: keadaan bersama hilang, pemakaian bersarang aman menurut konstruksi, dan harga yang dibayar hanya pencarian provider per pengambilan (murah, dan pengambilan terjadi per berkas/unduhan — bukan per byte).

## [Belum rilis] — Audit 671: cocokAscii kini menjaga null seperti helper Util lainnya
- `cocokAscii` publik memanggil `hay.length()` langsung — satu-satunya helper teks di `Util.java` yang melempar `NullPointerException` untuk masukan null, menyimpang dari pola berkasnya sendiri (`heks`, `rendahAscii`, `mengandungAbaikanHuruf`, dll. semuanya menjaga null). Pemanggil internal satu-satunya memang sudah menjaga lebih dulu sehingga belum ada jalur aktif, tetapi pemanggil baru yang memakainya langsung dengan teks yang bisa null (preferensi/intent) akan crash di helper yang dikira seaman tetangganya. Kini null di kedua argumen menghasilkan `false`, terkunci uji.

## [Belum rilis] — Audit 670: kegagalan memulai thread watchdog kini tercatat
- `pasangWatchdogProses` menelan kegagalan `Thread.start()` (kehabisan thread/memori di STB) dalam `catch (Exception ignored)` dan mengembalikan thread seolah sehat; kedua pemanggil probe versi tidak memeriksa `isAlive()`, sehingga proses `--version` yang menggantung tanpa output bisa menunggu tanpa batas tanpa ada yang tahu watchdog-nya tidak pernah ada. Kegagalan itu kini ditulis ke log (`Log.w`, pola yang sama dengan peringatan antrean penuh di berkas yang sama) — kegagalan langka ini tidak lagi lenyap tanpa jejak.

## [Belum rilis] — Audit 669: watchdog proses menjepit timeout negatif ke nol
- Badan thread `pasangWatchdogProses` hanya menangkap `InterruptedException`, padahal `Thread.sleep` melempar `IllegalArgumentException` untuk timeout negatif — watchdog mati diam-diam tanpa pernah membunuh prosesnya. Kedua pemanggil saat ini memakai konstanta 10 detik sehingga celahnya laten, tetapi pemanggil yang menghitung timeout dinamis (sisa waktu = batas − berlalu) bisa menghasilkan nilai negatif tepat di jalur lambat. Timeout kini dijepit `Math.max(0, ...)` — semantik yang benar: tenggat yang sudah lewat berarti bunuh sekarang juga, bukan jangan pernah.

## [Belum rilis] — Audit 668: confirm() kini memverifikasi dialog benar-benar tampil
- Pola pengerasan Audit 603 (cek `isShowing()` sesudah `DialogAman.tampil`) sudah diterapkan di dialog impor tetapi belum di `confirm()`: bila `show()` gagal dalam balapan sempit antara cek `isFinishing()` dan tampil, dialog tak pernah muncul, `OnDismissListener` tak pernah terpanggil, dan callback batal tak pernah jalan — ketukan user hilang senyap. `confirm()` kini memperlakukan gagal-tampil sebagai batal (callback batal dijalankan), sama seperti pola yang sudah terbukti di dialog impor. Seluruh pemanggil saat ini memakai bentuk tanpa callback batal dan jalur matikan PIN memang gagal-aman, jadi ini menutup celah bagi pemanggil sekarang dan yang akan datang.

## [Belum rilis] — Audit 667: pagar pemindaian info berat Settings kini statis lintas rotasi
- `heavyRunning` adalah satu-satunya pagar tugas Settings yang masih per-instance, menyimpang dari pola `tugasBerjalan` yang sengaja statis (Audit 591: rotasi menghancurkan activity tetapi thread tugasnya terus berjalan). Rotasi di tengah pemindaian info berat (isi DB, storage, web-vault, sertifikat — I/O berkas + parse sertifikat) melahirkan instance baru berpagar kosong yang meluncurkan pemindaian kedua bersamaan di pool yang sama: kerja I/O ganda sesaat di STB lemah tiap rotasi. Pagarnya kini statis, sejalan dengan pola yang sudah terbukti.

## [Belum rilis] — Audit 666: cek port saveAndStart kini berpagar masuk di kedua activity
- `startTertundaPort` selama ini hanya *ditulis*, tidak pernah *dibaca* sebagai pagar: ketuk-ganda Start (remote D-pad Enter beruntun di STB) menjalankan dua tugas `bereskanServerYatim()` + `isPortBusy()` bersamaan, dan kedua penyelesaian bisa sama-sama memanggil `lanjutStart` — dialog bertumpuk dan `ServerService.start` terpanggil dua kali (di Settings tombol Start bahkan tidak ikut dinonaktifkan `setBusy`). Kini `saveAndStart` di MainActivity dan SettingsActivity menolak masuk bila cek port instance itu masih berjalan. Jalur rotasi tidak dirugikan: instance baru mewarisi keadaan tertunda lewat `startTertundaDariPutaran` (bukan flag-nya sendiri), dan penyelesaian instance lama tetap berhenti di cek `isDestroyed()` sebelum dialog/start apa pun.

## [Belum rilis] — Audit 665: staging TLS sisa restore terbunuh kini disapu di awal restore berikutnya
- Awal kedua jalur restore hanya menyapu staging DB; staging TLS (`tls/*.restore-tmp`) hanya dibersihkan di cabang gagal atau sesudah staging baru dibuat. Restore yang terbunuh di tengah penyaluran TLS (listrik padam) meninggalkan berkas-berkas itu di folder data selamanya — restore berikutnya yang backup-nya tanpa entri TLS tidak pernah membersihkannya. Kedua jalur kini juga memanggil `hapusStagingTls` di awal, sepasang dengan sapuan staging DB yang sudah ada.

## [Belum rilis] — Audit 664: entri -wal/-shm di dalam zip backup kini diperingatkan, bukan dibuang diam-diam
- Entri `db.sqlite3-wal`/`-shm` dari zip memang diterima ke staging, tetapi `pasangDbRestoreAtomik` hanya memasang berkas utama dan staging WAL/SHM dibuang tanpa catatan. Untuk backup buatan aplikasi sendiri itu aman (pembuatan backup me-checkpoint DB lebih dulu), tetapi zip dari sumber/versi lain bisa memuat transaksi ter-commit yang hanya ada di WAL — restore lama melapor sukses sementara transaksi itu hilang. Jalur restore bot kini mendeteksi entri tersebut dan menyebut terus terang di pesan hasil bahwa entri -wal/-shm diabaikan dan transaksi yang belum ter-checkpoint tidak ikut dipulihkan.

## [Belum rilis] — Audit 662: WAL live DB pengecualian-checkpoint kini ikut diselamatkan ke pre-backup
- Pre-backup restore hanya menyalin berkas utama DB. Untuk DB sehat itu cukup (checkpoint `TRUNCATE` mengosongkan WAL lebih dulu, dan kegagalan checkpoint membatalkan restore sejak Audit 579), tetapi DB yang dikecualikan dari kewajiban checkpoint (Audit 587 — justru DB rusak yang hendak dipulihkan) melewati blok itu tanpa checkpoint, lalu `-wal`/`-shm` live-nya dihapus sebelum ekstraksi: bila restore kemudian gagal, WAL asli hilang dan pre-backup tidak memuatnya, sehingga DB lama lebih sulit dipulihkan daripada sebelum percobaan. Kedua jalur restore (bot dan lokal) kini menyalin pendamping `-wal`/`-shm` di samping pre-backup khusus untuk kasus tanpa checkpoint itu, lewat helper bersama `salinPendampingWal`.

## [Belum rilis] — Audit 660: update_version diselaraskan ke binary yang benar-benar terpasang saat Start
- `update_version` hasil restore tidak pernah dikoreksi: jalur cepat cache hanya memeriksa nilainya tidak kosong, sehingga klaim versi dari backup (bisa lebih baru dari binary fisik perangkat tujuan) dibaca cek update berkala sebagai versi berjalan dan dapat menekan tawaran unduhan yang sebenarnya dibutuhkan. Kini, sesudah uji `--version` di jalur cepat berhasil, versi terdeteksi dari binary dibandingkan dengan `update_version` tercatat dan ditulis balik bila berbeda — satu-satunya sumber kebenaran adalah binary yang benar-benar jalan.

## [Belum rilis] — Audit 659: pin dan sidik perangkat tidak lagi ikut diekspor ke backup
- `bin_sha_pin`, `shim_sha256`, dan `bin_patch_rev` selama ini ikut tertulis ke `app-config.json` di dalam backup tetapi dibuang diam-diam saat impor (tidak ada di daftar putih impor) — data mati yang memberi kesan keliru bahwa pin ikut berpindah perangkat. Ketiganya adalah keadaan perangkat lokal (sidik berkas yang terpasang di perangkat ini), sekelas `bin_manual_fp` yang memang sudah dikecualikan; kini ketiganya masuk `TAK_DIEKSPOR_KEYS`. Penanda restore persisten baru (Audit 661) ikut dikecualikan dengan alasan yang sama. Perilaku restore tidak berubah: pin memang tidak pernah diterapkan dari backup, dan sejak Audit 652 pin yang basi dibersihkan secara eksplisit saat era binary berganti.

## [Belum rilis] — Audit 658: aarch32 diakui sebagai arsitektur ARM yang didukung
- Catatan self-review putaran-22: predikat `arsitekturTakDidukung` (Audit 645) hanya menerima awalan `arm`, `aarch64`, atau kandungan `arm64` — nilai `os.arch` "aarch32" (nama resmi ARM 32-bit di sebagian runtime) jatuh ke kelompok tak didukung, sehingga pesan uji asap menyalahkan arsitektur pada perangkat yang sebenarnya sah. `aarch32` kini diakui eksplisit dan terkunci uji.

## [Belum rilis] — Audit 657: nama check ganda dari dua workflow diterima sebagai residu terdokumentasi (issue ditutup-alasan)
- Sejak Audit 646, PR berkas kode menerima check "Cek patch yang berubah" dari dua workflow (`build-apk.yml` dan `cek-wajib.yml`) dan GitHub menilai run paling baru untuk nama yang sama. Jalur auto-merge tetap aman (ia juga menunggu kesimpulan run Build APK per head SHA secara terpisah), kedua job menjalankan langkah identik, dan pada merge manual kedua hasil terlihat berdampingan untuk dinilai owner. Mitigasi alternatif (cek-wajib menyimpulkan tanpa memverifikasi bila run lain ada) justru menciptakan green tanpa verifikasi. Residu ini diterima dan dicatat; issue #413 ditutup dengan alasan lengkap di GitHub.

## [Belum rilis] — Audit 656: antrean kunci pasang global kini memasang status tunggu
- Self-review putaran-22 memastikan kunci global Audit 649 bebas deadlock (satu-satunya jalur bersarang berjalan di thread yang sama dan `synchronized` bersifat reentrant), tetapi Start yang membutuhkan unduhan bisa mengantre belasan menit di belakang unduhan kanal lain tanpa penjelasan apa pun. Kunci diganti `ReentrantLock` (tetap reentrant per thread, semantik serialisasi sama); pemanggil yang tidak langsung memperoleh kunci memasang status "Menunggu unduhan/pemasangan lain selesai..." yang terlihat di UI sampai gilirannya tiba dan status unduhannya sendiri mengambil alih.

## [Belum rilis] — Audit 655: probe --version menangkap bukti panic yang datang sesudah baris kedua
- Probe versi berhenti membaca sesudah 2 baris non-derau, sehingga jejak panic getrandom yang tercetak sesudah dua baris banner normal tidak pernah masuk `lastVersionOutput` — bukti yang menjadi dasar rescue Audit 644/653 dan jalur panic bisa lolos palsu. Probe kini terus membaca baris yang **sudah tersedia** (`BufferedReader.ready()`, tanpa menunggu) sampai maks 40 baris: ledakan output panic tertangkap utuh, sedangkan proses sehat yang langsung keluar tidak ditahan lebih lama dari sebelumnya.

## [Belum rilis] — Audit 663: restore bot memvalidasi config; klaim "lengkap" kini hanya bila pengaturan benar-benar diterapkan
- Jalur restore Telegram/bot menerapkan `app-config.json` tanpa memeriksa penanda aplikasi — tidak seperti jalur impor config di Settings yang menolak config asing. JSON dari aplikasi lain, JSON acak berkunci `prefs`, atau objek tanpa `prefs` sama sekali tetap membuat DB direstorasi sambil balasan bot mengklaim "lengkap, termasuk pengaturan", padahal `applyPrefsFromJson` kembali diam-diam untuk argumen null dan tidak ada pengaturan yang berpindah; config yang gagal diparse pun lenyap tanpa peringatan. Kini jalur bot memeriksa penanda `app` dan keberadaan objek `prefs` sebelum menerapkan, dan setiap kegagalan (penanda salah, prefs tidak ada, config tidak terbaca) disebut terus terang di pesan hasil; label "lengkap" hanya muncul bila pengaturan benar-benar diterapkan.

## [Belum rilis] — Audit 661: penanda persisten restore — mati listrik di tengah restore kini terdeteksi saat boot
- Satu-satunya penanda restore sebelumnya adalah `AtomicBoolean` di memori; proses yang mati sesudah DB ditukar tetapi sebelum pengaturan diterapkan (listrik padam di STB tanpa UPS adalah skenario realistis) meninggalkan DB baru + TLS campuran + pengaturan lama berjalan bersama tanpa pesan apa pun, dan cadangan pra-restore tak pernah ditawarkan kembali. Kini kedua jalur restore (Telegram dan lokal) menulis penanda persisten `restore_tanda_ms` (commit sinkron) tepat sebelum penukaran DB dan membersihkannya sesudah ekor restore selesai; jalur gagal sengaja membiarkannya. BootReceiver memeriksa penanda itu paling awal: bila tersisa, log + pesan Telegram memperingatkan bahwa restore terakhir terputus dan database/pengaturan bisa tidak serasi, lalu penanda dibersihkan agar peringatan hanya muncul sekali.

## [Belum rilis] — Audit 654: bukti backport kini dari probe yang dijamin tanpa shim
- Pasangan Audit 653 dari arah sebaliknya: kesimpulan jalur backport (Audit 646, "binary lolos tanpa shim sehingga kernel membawa backport getrandom") diambil dari probe otomatis yang memutuskan `LD_PRELOAD` berdasar berkas shim di disk — bukan berdasar `sesi.shim`. Bila pemasangan ulang shim gagal di tengah (jaringan putus, hash rilis tak cocok) sementara berkas shim lama masih utuh di disk, probe lama lolos **dengan** shim, log mengklaim sebaliknya, dan start nyata — yang hanya memakai `sesi.shim` (null) — berjalan tanpa shim lalu panic getrandom saat runtime. Cabang backport kini memakai mode probe tanpa-shim secara eksplisit, jadi kelolosannya benar-benar membuktikan binary aman tanpa `LD_PRELOAD`.

## [Belum rilis] — Audit 653: probe rescue panic kini benar-benar memakai shim
- Self-review putaran-22 membuktikan klaim Audit 644 tidak terbukti oleh kode: `detectBinaryVersion` hanya memasang `LD_PRELOAD` bila klasifikasi perangkat legacy, sedangkan cabang rescue bukti panic justru berjalan pada perangkat non-legacy — probe penyelamat mengulang persis probe yang menghasilkan bukti panic, tanpa shim. Probe kini punya mode eksplisit: otomatis (perilaku lama), paksa-shim, dan tanpa-shim. Cabang rescue memakai mode paksa-shim, sehingga "uji asap lolos bersamanya" kini benar-benar berarti binary diuji dengan shim yang akan dipakai start nyata.

## [Belum rilis] — Audit 652: pin binary dipelihara saat pasang manual dan restore
- Dua jalur mengganti era binary tanpa menyentuh pin `bin_sha_pin`: pemasangan binary manual dari folder data menulis sidik manual dan versi tetapi membiarkan pin unduhan lama (Start berikutnya selalu gagal `binaryUtuh`, jalur cepat mati permanen, log menuduh kerusakan yang tidak ada), dan restore/impor menulis `bin_sha`/`update_version`/pin versi dari backup sambil membiarkan pin + sidik manual perangkat lama (keadaan campuran dua era). Kini jalur manual menulis pin dari SHA-256 berkas yang benar-benar terpasang, dan `applyPrefsFromJson` membersihkan `bin_sha_pin` + `bin_manual_fp` setiap kali kunci era binary ikut ditulis dari backup — pin dibangun ulang secara jujur lewat pin-on-first-use atau pemasangan manual berikutnya, bukan diwarisi dari era yang salah.

## [Belum rilis] — Audit 651: pin SHA-256 binary kini menggerbang semua cabang penerimaan cache
- Self-review putaran-22 menemukan pin `bin_sha_pin` (Audit 647) hanya diperiksa di cabang cache update; cabang cache internal (`version.txt` cocok + uji `--version` + pin versi) mengembalikan binary yang sama tanpa cek SHA sama sekali, sehingga binary rusak yang lolos uji asap tetap dipakai dan unduh ulang tak pernah dicoba. Ketiga cabang penerimaan cache kini mensyaratkan `binaryUtuh` yang sama: cabang cache internal, cabang tunda-unduh saat jeda percobaan ulang, dan cabang darurat sesudah unduh gagal (cabang darurat kini juga mencatat dengan jujur bila cache ditolak karena SHA, bukan diam-diam memakainya). Pin kosong tetap berarti belum ada catatan (perilaku pin-on-first-use tak berubah).

## [Belum rilis] — Perbaikan build: uji pesanUjiAsapGagal diselaraskan ke tanda tangan berarsitektur
- Run `70ff9f9` merah pada dua uji `pesanUjiAsapGagal`: sejak Audit 645, overload dua-argumen mendelegasikan dengan `os.arch` proses — di JVM CI nilainya `x86_64`, sehingga pesan yang keluar adalah diagnosis arsitektur (yang memang menyebut shim) dan asersi lama gugur; satu uji bahkan lulus semu karena kata "shim" ada di teks arsitektur. Uji kini memakai overload tiga-argumen dengan arsitektur eksplisit (`armv7l` untuk jalur kernel, `x86_64` untuk kasus diagnosis barunya). Perilaku produksi tidak berubah.

## [Belum rilis] — Audit 649: unduh dan pasang binary/shim kini diserialkan global
- Dua pemicu update bersamaan dari kanal berbeda (bot, Settings, AutoUpdate, Start) dengan target versi berbeda sebelumnya hanya diserialkan pada momen swap berkas; penulisan metadata (version.txt, versi tercatat, status fallback, reset versi proses) terjadi sesudahnya di luar kunci, jadi hasil akhir ditentukan thread yang selesai terakhir — bukan target yang seharusnya menang. Seluruh operasi unduh+pasang pada pintu publik `downloadBinaryVersi` dan `downloadShim` kini berjalan di bawah satu kunci global Updater; kunci yang sama menutup celah sapuan tmp basi terhadap pemasangan yang masih tertunda.

## [Belum rilis] — Audit 650: entri TMP_UNDUH_AKTIF kini dilepas lewat finally
- `unduhKeTmp` melepas penanda tmp-aktif hanya di jalur sukses dan penangkap `Exception`; sebuah `Error` (mis. `OutOfMemoryError`) yang lolos dari lingkar unduh meninggalkan entri basi, dan karena sapuan tmp selalu melewati path yang tercatat aktif, berkas yatim itu tak pernah tersapu selama sisa umur proses. Lingkar unduh kini dibungkus try-finally yang selalu melepas penandanya.

## [Belum rilis] — Audit 648: keputusan update tak lagi memakai versi proses yang sudah berhenti
- `tryUpdateVersi` bisa menyimpulkan "sudah versi terbaru" dari `ServerService.binaryVersion` — nilai yang tidak dibersihkan saat server berhenti dan bertahan selama proses aplikasi hidup. Bila binary di disk diganti/diturunkan lewat restore sesudah Stop, Cek Update tetap menyatakan terbaru tanpa mem-probe berkas. Nilai proses kini hanya dipercaya selagi server benar-benar berjalan; sesudah Stop keputusan berdasar probe berkas di disk.

## [Belum rilis] — Audit 646: shim gagal terpasang tak lagi membatalkan Start bila binary terbukti jalan tanpanya
- Klasifikasi legacy menggerbang Start secara keras: shim gagal terpasang (mis. pemasangan pertama offline) = Start batal, padahal sebagian kernel 3.x vendor membawa backport getrandom sehingga binary sebenarnya tidak butuh shim sama sekali — dan deteksi versi tak bisa melihat backport itu. Kini kegagalan pasang shim dibuktikan dulu lewat uji asap `--version` tanpa shim: lolos → Start lanjut tanpa `LD_PRELOAD` dengan catatan log; gagal → batal seperti semula. Perangkat legacy murni tanpa backport tidak berubah perilakunya (uji asapnya memang gagal tanpa shim).

## [Belum rilis] — Audit 645: kegagalan uji asap di perangkat non-ARM kini didiagnosis jujur
- Perangkat di luar target (x86/x86_64 murni, arm64 tanpa compat 32-bit) sebelumnya gagal uji `--version` dengan pesan generik yang menyalahkan berkas dan mendorong Cek Update berulang, padahal binary ARM memang tidak bisa berjalan di sana. `KernelCompat` kini punya predikat murni `arsitekturTakDidukung` (terkunci uji), dan pesan uji asap gagal menyebut arsitekturnya secara eksplisit: bukan ARM, mengunduh ulang tidak akan membantu. Tidak ada ABI baru yang ditambah — ini murni diagnosis, sesuai aturan repo.

## [Belum rilis] — Audit 644: bukti panic getrandom kini mengalahkan klasifikasi kernel
- `os.version` yang tak terparse dianggap "modern" (keputusan disengaja, terkunci uji), sehingga pada perangkat berkernel lama berformat aneh shim tak pernah dipasang — dan pesan pemulihannya menyuruh menekan Cek Update yang tergerbang klasifikasi yang sama, membentuk lingkaran yang tak bisa sembuh. Kini bila probe `--version` menunjukkan panic getrandom (bukti nyata perangkat butuh shim), `siapkanShim` mencoba memasang shim apa pun klasifikasinya dan mengulang uji asap: lolos → Start lanjut dengan `LD_PRELOAD`; gagal → jalur saran lama seperti sebelumnya. Klasifikasi tak-dikenal=modern sendiri tidak diubah.

## [Belum rilis] — Audit 643: langkah APK bertanda tangan rilis kini digerbang ref main
- Langkah build signed, verifikasi APK, cek ukuran, salinan bernama rilis, dan upload artifact di `build-apk.yml` sebelumnya hanya digerbang `event != pull_request`, jadi `workflow_dispatch` dari branch mana pun menghasilkan APK bertanda tangan keystore rilis asli sebagai artifact. Kelimanya kini juga mensyaratkan `github.ref == 'refs/heads/main'`, sejajar dengan langkah publish yang sudah lebih ketat.

## [Belum rilis] — Audit 642: CodeQL kini juga menganalisis shim C (cpp, build-mode none)
- Satu-satunya kode natif repo — shim getrandom yang di-`LD_PRELOAD` ke proses server dan mengintersep keacakan — sebelumnya tak dianalisis SAST mana pun. `codeql.yml` kini punya job `Analyze (cpp)` dengan ekstraksi tanpa build untuk `shim/getrandom_shim.c`, dan `shim/**` dicabut dari paths-ignore CodeQL agar perubahan shim benar-benar memicu analisis (jadwal mingguan tetap menjadi jaring pengaman).

## [Belum rilis] — Audit 641: jendela penemuan run auto-merge dibedakan dari PR tersaring path
- Langkah tunggu Build APK sebelumnya mencampur dua keadaan: run yang memang tak ada (PR tersaring path) dan run yang belum terdaftar karena GitHub lambat — keduanya dibaca sebagai "tersaring" dan auto-merge lanjut berbekal gerbang patch. Kini deteksi berkas PR juga menghitung apakah PR itu SEHARUSNYA memicu Build APK (ada berkas di luar daftar paths-ignore build-apk): jendela penemuan diperpanjang jadi 3 menit, dan bila run tak muncul padahal seharusnya ada, auto-merge DIBATALKAN (bisa dipulihkan lewat label) alih-alih dilanjutkan buta.

## [Belum rilis] — Audit 640: label security dibuat di repo agar pengecualian stale-nya hidup
- Daftar `exempt-issue-labels` di `stale.yml` memuat `security`, tetapi label itu tidak terdaftar di repo sehingga tidak bisa dipasang ke issue mana pun dan pengecualiannya mati diam-diam. Label `security` kini dibuat di pengaturan repo (perbaikan sisi repo, tanpa perubahan berkas) dan issue-nya ditutup dengan bukti daftar label.

## [Belum rilis] — Audit 639: major Gradle Wrapper kini diabaikan Dependabot sesuai keputusan repo
- Keputusan tercatat (PR wrapper 9.8.2 ditutup karena Gradle 9 tak berpasangan dengan AGP 8.x) belum terkodifikasi, jadi Dependabot akan terus membuka PR major wrapper yang harus ditriage manual dan berisiko ter-merge tak sengaja. `dependabot.yml` kini mengabaikan update major `gradle-wrapper`, sejajar dengan perlakuan major AGP.

## [Belum rilis] — Audit 638: PR khusus dokumen kini tetap mendapat check wajib
- Proteksi branch mewajibkan check "Cek patch yang berubah", tetapi `build-apk.yml` mengabaikan `*.md`/`.gitignore` pada pull_request, jadi PR yang hanya mengubah dokumen tak pernah menghasilkan check itu dan merge normalnya buntu selamanya. Workflow ringan baru `cek-wajib.yml` berjalan untuk semua PR tanpa saringan path dan melaporkan check bernama sama dengan langkah yang sama persis seperti job verifikasi build-apk (gerbang patch atas rentang PR + cek-cepat).

## [Belum rilis] — Audit 636: bendera gerbang killStale kini dinilai ulang per kandidat
- Bendera `anakHidupTakTeridentifikasi` sebelumnya dihitung sekali sebelum putaran pemindaian: bila anak yang berjalan mati di tengah sapuan, sisa kandidat putaran itu tetap diperlakukan seolah anak masih hidup. Bendera kini dinilai ulang tepat di titik gerbang untuk tiap kandidat (pemeriksaannya murah — sekadar cek keadaan proses), menutup jendela potret-sesaat itu tanpa mengubah semantik predikatnya.

## [Belum rilis] — Audit 635: batas rentang kurawal gerbang dinaikkan 64 → 512
- Batas anti-ledakan pada ekspansi rentang gerbang (64 nilai) menolak pola rentang sah yang besar seperti `img{1..100}.png` dengan pesan "tak mencocokkan berkas" yang menyesatkan. Batasnya dinaikkan ke 512 — jauh di atas rentang wajar mana pun untuk labeler, tetapi tetap membatasi biaya glob — dan dibuktikan dengan kasus satuan: rentang 100 nilai kini diekspansi, rentang 600 tetap literal.

## [Belum rilis] — Audit 634: probe versi kini memakai shimUtuh, konsisten dengan jalur start
- Audit 629 menaikkan probe dari `exists()` ke `shimValid`, tetapi shim rusak-di-tengah (lolos header, gagal hash tercatat Audit 628) tetap di-`LD_PRELOAD` oleh kedua probe `--version`, menggagalkan probe dan bisa memicu unduh ulang binary yang tak perlu. Kedua probe kini membaca hash tercatat dan memakai `shimUtuh`, persis gerbang jalur start.

## [Belum rilis] — Audit 633: pin hash shim pertama diverifikasi ke hash rilis, bukan pin buta
- Self-review menemukan sisa lubang Audit 628: pin-on-first-use menyimpan hash berkas SAAT INI, jadi shim yang sudah rusak sebelum pencatatan ada ikut diberkati dan verifikasi ulang selamanya "cocok". Kini pada pin pertama, `ensureShimFile` mengambil hash resmi shim dari rilis (mekanisme `shaDariRilis` yang sama dengan jalur unduh): berkas terpasang wajib cocok dengannya, tak cocok → unduh ulang terverifikasi. Bila hash rilis tak terjangkau (offline), perilaku lama berlaku (pin berkas saat ini) agar Start tidak terkunci jaringan — sisa risiko itu kini terdokumentasi, bukan diam-diam.

## [Belum rilis] — Audit 632: auto-merge memeriksa ulang head PR sesaat sebelum diaktifkan
- Self-review menemukan celah pada gerbang Build APK Audit 624: validasi mengikat SHA saat event dibuat, tetapi `gh pr merge --auto` menggabungkan head TERKINI begitu check wajib hijau — commit B yang didorong di tengah masa tunggu bisa ter-merge hanya berbekal gerbang patch-nya. Kini ada langkah pemeriksa head tepat sebelum pengaktifan: head PR dibaca ulang dan dibandingkan dengan SHA yang Build APK-nya ditunggu; berbeda = auto-merge dibatalkan dan run untuk head baru yang menilai ulang.

## [Belum rilis] — Audit 647: binary terpasang kini diverifikasi ulang SHA-256 (pin seperti shim)
- Cermin Audit 628 untuk berkas yang lebih besar: SHA-256 binary hanya diperiksa pada berkas sementara sebelum pasang; sesudah terpasang, pemeriksaan cache Start hanya ukuran + magic ELF 20 byte + uji `--version`, jadi kerusakan flash di tengah binary yang tak menyentuh jalur `--version` lolos selamanya dan update bisa dilewati karena versi dianggap benar. Kini hash binary dicatat ke prefs (`bin_sha_pin`) sesudah pemasangan terverifikasi, dan jalur cache Start memeriksa ulang lewat predikat `binaryUtuh`: tak cocok → log jelas dan binary diunduh ulang. Pasangan lama tanpa pin di-pin pada penerimaan cache pertama (pola yang sama dengan shim). Predikat dikunci uji.

## [Belum rilis] — Audit 637: PR yang menyentuh workflow binary/shim dikeluarkan dari auto-merge
- Perbaikan Audit 624 membuat auto-merge menunggu Build APK, tetapi PR yang isinya hanya `build-binary.yml` (mis. bump `actions/cache` yang hanya dipakai workflow itu) tidak menghasilkan run Build APK sama sekali — check wajibnya justru datang dari job verifikasi workflow binary, jadi PR bisa ter-merge berbekal gerbang patch sementara build binary 15–120 menit yang mengujinya tak pernah ditunggu (dan memang tak bisa ditunggu dalam batas job). PR Dependabot yang menyentuh `.github/workflows/build-binary.yml` atau `shim/**` kini diperlakukan seperti PR dependensi Gradle: auto-merge dimatikan dan diberi komentar panduan merge manual sesudah run Build Binary hijau.

## [Belum rilis] — Audit 631: classifier kunci privat juga mengupas ekstensi luar .json/.pem/.crt/.cer/.der
- `kunciPrivat` di FileShareProvider hanya mengupas daftar pembungkus tetap (.zip/.enc/dll.) sebelum menguji inti nama, jadi nama kunci berakhiran ganda di luar daftar itu — `ca-key.json`, `key.json`, `ca-key.crt`, `server.key.json` — lolos sebagai boleh-dibagikan (laten: belum ada penulis nama seperti itu). Lingkar kupas kini juga melepas ekstensi luar .json/.pem/.crt/.cer/.der berulang kali sebelum inti diuji; sertifikat murni (`ca.pem`, `cert.pem`, `cert.json`) terverifikasi tetap boleh dibagikan lewat uji yang diperluas di commit yang sama.

## [Belum rilis] — Audit 630: mutex shim dipisah per sumber agar baca /dev/random tak mengunci urandom
- `isi_dari` di shim getrandom memakai satu mutex yang sama untuk kedua sumber dan menahannya selama seluruh baca: satu panggilan `GRND_RANDOM` yang memblokir di `/dev/random` (STB miskin entropi) menahan semua panggilan urandom biasa (token sesi, kunci TLS) di belakangnya, padahal syscall asli membiarkan keduanya independen. Kini tiap sumber punya mutex sendiri (`kunci_urandom`/`kunci_random`) yang dipilih per panggilan; perilaku satu sumber tak berubah. Perubahan shim ini memicu workflow build-binary sesuai desain repo.

## [Belum rilis] — Audit 629: probe versi hanya memasang LD_PRELOAD untuk shim yang valid
- Dua jalur probe `--version` (`Updater.detectVersion`, `ServerService.detectBinaryVersion`) memasang `LD_PRELOAD` hanya berdasar `shim.exists()`: sisa shim tak valid (potongan unduhan lama di bawah ukuran minimum / bukan ELF ARM) ikut dimuat, linker menggagalkan probe, versi binary terbaca tak dikenal, dan jalur update bisa mengunduh ulang binary besar yang tak perlu. Keduanya kini memeriksa `shimValid` seperti jalur start utama.

## [Belum rilis] — Audit 627: template GitHub kini tercakup label ci
- Pola labeler belum menjangkau `.github/ISSUE_TEMPLATE/**` dan `.github/PULL_REQUEST_TEMPLATE.md`, jadi PR yang hanya mengubah template tak mendapat label apa pun. Keduanya kini masuk label `ci`.

## [Belum rilis] — Audit 626: syarat pemicu auto-merge kini memeriksa penulis PR, bukan aktor event
- Job auto-merge digerbang `github.actor == 'dependabot[bot]'`, padahal pada event `labeled`/`ready_for_review` aktornya adalah pihak yang memberi label/menandai siap — jadi label dari maintainer tak pernah menjalankan job dan jalur pemulihan berbasis label mati diam-diam. Syaratnya kini memeriksa penulis PR (`github.event.pull_request.user.login`), yang memang yang dimaksud untuk semua jenis event pemicu.

## [Belum rilis] — Audit 625: penanda label audit kini berjangkar format baku dan label bisa dicabut
- `label-audit.yml` sebelumnya memasang label `audit` (yang kebal stale tanpa batas waktu) hanya dari substring bebas "Ronde audit" di mana pun di badan issue — satu kutipan kalimat bisa memberi kekebalan selamanya, dan label tak pernah dicabut. Kini penandanya berjangkar pada format baku laporan audit (baris diawali `**Ronde audit:** putaran-N`), dan pada suntingan yang menghilangkan penanda, label dicabut otomatis.

## [Belum rilis] — Audit 623: ekspansi kurawal gerbang disamakan dengan aturan brace-expansion
- Self-review menemukan dua penyimpangan tersisa dari minimatch: grup unsur tunggal tanpa koma (`*.{md}`) diekspansi gerbang menjadi `*.md` sehingga pola mati LOLOS, dan rentang sah (`img{1..2}.png`) tidak diekspansi sehingga ditolak palsu. Kini grup hanya diekspansi bila berisi daftar koma (terluar) atau rentang `..` (bilangan/huruf, berarah, berlangkah, ber-padding nol, dibatasi 64 nilai); grup lain dibiarkan literal dan ekspansi berlanjut pada sisa pola. Dibuktikan 12 kasus satuan (termasuk sarang, literal campuran, kurung tak ditutup) dan fixture gerbang dua arah.

## [Belum rilis] — Audit 628: integritas shim terpasang diverifikasi ulang lewat SHA-256 tercatat
- `ensureShimFile` sebelumnya menerima shim terpasang hanya dari `shimValid` (ukuran + magic ELF + arsitektur): berkas yang rusak di TENGAH (header utuh) lolos selamanya, `LD_PRELOAD` gagal, start mati dengan pesan yang menuduh binary, dan saran "tekan Cek Update" mengembalikan berkas rusak yang sama — satu-satunya pemulihan adalah hapus data aplikasi. Kini hash SHA-256 shim dicatat ke prefs saat pemasangan (sesudah verifikasi unduhan yang sudah ada), dan `ensureShimFile` memeriksa ulang lewat predikat `shimUtuh`: hash tak cocok → unduh ulang. Pasangan lama tanpa catatan di-pin pada validasi pertama (percaya-sesudah-cek), jadi kerusakan berikutnya tetap tertangkap. Predikatnya dikunci uji termasuk kasus berkas pendek dan hash berkas lain.

## [Belum rilis] — Audit 624: auto-merge Dependabot kini menunggu hasil Build APK, bukan hanya gerbang patch
- Langkah "Auto-merge setelah CI hijau" sebelumnya menipu namanya: `--auto` hanya menunggu check wajib branch, dan satu-satunya check wajib adalah `Cek patch yang berubah` (gerbang patch tanpa compile/lint/uji) — bump major grup GitHub Actions yang mematahkan build bisa ter-merge otomatis. Workflow auto-merge kini menunggu run Build APK untuk head SHA PR itu: bila ada, auto-merge hanya diaktifkan sesudah run itu `success` (gagal/batal/timeout menunggu = dibatalkan); bila tak ada run (PR tersaring path sehingga build memang tak berjalan), jalur lama berlaku. Batas waktu job dinaikkan 15 → 25 menit untuk memberi ruang tunggu itu.

## [Belum rilis] — Audit 622: gerbang killStale dikoreksi — anak basi sesudah Stop kembali dibersihkan
- Self-review menemukan regresi pada perbaikan Audit 612: `lewatiBunuhBasi` memakai `childPid < 0` sebagai tanda ambiguitas, padahal nilai itu juga muncul pada keadaan normal sesudah Stop (tak ada proses terlacak sama sekali) — akibatnya anak basi berinduk aplikasi yang lolos dari Stop tak pernah dibunuh dan Start gugur "port dipakai proses lain" selamanya. Gerbang kini digerakkan keadaan yang sebenarnya dimaksud: hanya bila ada proses terlacak yang BENAR-BENAR hidup (`isProcessAlive()`) tetapi PID-nya tak teridentifikasi, kandidat ber-PPID aplikasi dilewati. Tanpa proses hidup, semua kandidat memang basi dan dibersihkan seperti sebelum Audit 612. Tanda tangan predikat berubah dan uji diperbarui di commit yang sama, termasuk kasus regresinya.

## [Belum rilis] — Perbaikan build: Base64 TlsCert kini punya cadangan java.util agar teruji JVM
- Tiga uji pasangan asli Audit 607 gagal di CI: `TlsCert.writePem` dan pembaca kunci privat memakai `android.util.Base64`, yang di uji JVM hanya stub pelempar pengecualian, sehingga `buatCa` selalu mengembalikan false di sana (di perangkat Android API 21+ panggilan itu normal). Kedua situs kini lewat helper `base64TanpaBaris`/`base64Dekode` yang mencoba `android.util.Base64` lebih dulu — perilaku di perangkat tidak berubah — dan jatuh ke `java.util.Base64` bila yang pertama melempar (lingkungan JVM). Cadangan itu dijangkau lewat refleksi, bukan acuan langsung: lint `NewApi` (dipasang sebagai error) menolak acuan `java.util.Base64` pada minSdk 21, dan refleksi sekaligus memastikan kelasnya tak pernah dimuat di API 21–25 tempat ia memang tak ada. Perilaku produksi identik; jalur kriptografi TlsCert kini benar-benar terjangkau uji.

## [Belum rilis] — Audit 621: istilah wizard di README diluruskan ke tampilan sebenarnya
- Ketiga README menyuruh user "mengikuti wizard 3 langkah", menyiratkan UI berpindah halaman per langkah — padahal yang ada satu layar Settings dengan isian berurutan (istilah "wizard" hanya hidup sebagai pref internal `wizard_selesai`). Kalimatnya kini menggambarkan kenyataannya: isi 3 hal berurutan (Folder → Port → Start), tanpa menjanjikan tampilan langkah-berlangkah.

## [Belum rilis] — Audit 620: bullet pemecahan masalah login kini ada di ketiga README
- Petunjuk "**Aplikasi Bitwarden tak bisa login?** Baca log realtime baris `[login]`" hanya ada di README Indonesia; pembaca Inggris dan Rusia kehilangan petunjuk diagnostik yang sama (fiturnya nyata — baris `[login]` ditulis ServerService). Bullet padanannya kini ditambahkan ke README Inggris dan Rusia pada posisi yang sama di daftar pemecahan masalah, memulihkan paritas tiga bahasa.

## [Belum rilis] — Audit 619: angka ukuran APK disegarkan ke rilis terakhir
- AGENTS.md masih menulis "rilis terakhir 247 KB" dan ketiga README "~0,25 MB", padahal rilis terakhir (v1.37.4, terverifikasi saat audit) berisi APK 284.936 byte (±285 KB / 0,28 MB) — angka presisi di dokumen sudah terlampaui ±15%. Kini AGENTS.md menulis 285 KB dan ketiga README ~0,28 MB sesuai aset rilis aktual.

## [Belum rilis] — Audit 618: AGENTS.md tak lagi menyebut simpan .txt di layar awal
- Aturan 8 dan pemetaan fitur di AGENTS.md mencantumkan "simpan .txt" sebagai isi layar awal, padahal MainActivity tak punya penangan simpan log sama sekali (menu titik tiganya hanya Open Settings + About) — simpan .txt hidup di layar log penuh LogActivity. Deskripsi layar awal kini cocok dengan kode, dan pemetaan fitur menunjuk tempat simpan .txt yang sebenarnya agar pengelola tak mengira ada regresi atau menambahkannya kembali ke beranda yang sengaja ringkas.

## [Belum rilis] — Audit 617: nama panel menu /help di README EN/RU disamakan dengan tombol bot
- README Inggris/Rusia menulis panel menu sebagai "(Status, Control, Backup, Update, Certificate, Info)", padahal tombol bot selalu berbahasa Indonesia: "Kontrol" dan "Sertifikat" yang tampil, "Control"/"Certificate" tidak pernah muncul. Kini ketiga README mengutip nama tombol yang sama persis.

## [Belum rilis] — Audit 616: README Inggris/Rusia kini mengutip label UI Inggris yang sebenarnya
- README Inggris dan Rusia mengutip label tombol "Versi binary" / "Versi web vault" / "Terbaru (otomatis)" — label Bahasa Indonesia yang hanya tampil pada UI Indonesia. Pembaca keduanya kini diberi label yang benar-benar mereka lihat: "Binary version" / "Web vault version" / "Latest (automatic)". Kutipan kata kunci bot `/update terbaru` memang literal di kode dan tidak diubah.

## [Belum rilis] — Audit 615: nama seksi di README disamakan dengan judul kartu di UI
- Ketiga README mengarahkan kunci versi dan backup lokal ke "Settings → Maintenance/Pemeliharaan", padahal judul kartu yang benar-benar tampil adalah "Updates & Certificate" (Inggris) / "Pembaruan & Sertifikat" (Indonesia). Arahannya benar tempat, salah nama — kini disamakan persis dengan string UI di ketiga bahasa (README Rusia memakai nama Inggris karena aplikasi tak punya sumber daya Rusia).

## [Belum rilis] — Audit 612: killStaleVaultwarden gagal-aman saat identitas anak tak bisa dipastikan
- Di API 21–25 tak ada `Process.pid()`, jadi pengecualian proses berjalan bergantung pada scan /proc yang hanya berhasil bila ada tepat satu anak server. Bila dua anak sempat hidup bersamaan, scan gagal, `childPid` menjadi -1, dan anak yang sah bisa ikut dibunuh di tengah urutan start-nya sendiri (DUGAAN jendela sempit). Kini kandidat berinduk ke aplikasi ini (PPID = PID aplikasi) dilewati bila identitas anak yang berjalan tak teridentifikasi — yatim sejati berinduk lain tetap dibersihkan. Keputusan itu predikat murni `lewatiBunuhBasi` dan dikunci uji.

## [Belum rilis] — Audit 613: klasifikasi link-local IPv6 memakai isLinkLocalAddress
- `collectIps` menyaring alamat link-local hanya dengan awalan literal `fe80:`/`FE80:`, padahal blok link-local adalah fe80::/10 (fe80:–febf:): alamat seperti `fe90::…` lolos sebagai alamat routable, masuk daftar SAN sertifikat TLS dan bisa dipilih `lanHost()` sebagai alamat yang ditampilkan. Kini memakai `InetAddress.isLinkLocalAddress()` yang memang menguji blok /10 itu.

## [Belum rilis] — Audit 611: readProcCmdline membaca sampai habis, bukan sekali baca
- `readProcCmdline` membaca cmdline proses dengan satu kali `read()` ke buffer 1024 byte: kontrak `InputStream` tak menjamin buffer terisi penuh, dan cmdline yang lebih panjang terpotong diam-diam — bagian yang hilang pertama justru pengecualian `--version` di `cmdlineServer` (letaknya paling akhir), sehingga proses smoke-test bisa lolos sebagai server sungguhan. Kini dibaca berulang sampai habis dengan plafon 8192 byte.

## [Belum rilis] — Audit 610: predikat pengecualian sapuan impor diekstrak dan dikunci uji
- Pengecualian Audit 595 (berkas impor tertunda tak boleh disapu) sebelumnya inline di metode privat `sapuSisaImpor` sehingga tak terjangkau uji — refactor sapuan berikutnya bisa menghilangkannya lagi tanpa sinyal, persis pola yang melahirkan temuan Sedang itu. Logikanya kini predikat murni statis `berkasImporBolehDisapu(nama, path, pathTertunda)` yang dipakai pemanggil yang sama, dikunci uji: path tertunda persis → tahan; berkas impor lain → sapu; tanpa penanda → sapu; nama bukan impor/null → jangan sentuh.

## [Belum rilis] — Audit 609: keputusan penanda pengumuman update diekstrak dan dikunci uji
- Aturan Audit 599 (penanda `tg_notified_version` dicatat sesudah pengumuman dicoba, tidak digerbang keberhasilan notifikasi sistem) sebelumnya hidup inline di `AutoUpdate.cek()` sehingga tak terjangkau uji — mengembalikan gerbang lama akan menghijaukan seluruh suite sambil menghidupkan lagi spam Telegram. Keputusan itu kini predikat murni `catatPenandaPengumuman(tercatat, versi, pengumumanDicoba)` yang tanda tangannya sengaja tidak menerima hasil notifikasi sistem, dikunci uji tabel keputusan (versi baru + sudah dicoba → catat; versi sama → tidak; belum dicoba → tidak).

## [Belum rilis] — Audit 608: cabang fallback salinCaKeStorage kini teruji
- Satu-satunya uji lama menulis ke folder yang masih kosong sehingga `renameTo` langsung berhasil dan seluruh blok fallback Audit 541 (nama tmp unik, geser tujuan lama ke `.lama-`, pulih bila gagal, fsync direktori) tak pernah berjalan. Uji baru menduduki path tujuan dengan folder berisi berkas — rename berkas ke folder pasti gagal — dan membuktikan jalur geser memasang salinan baru sambil mempertahankan geseran `.lama-*`. Sub-cabang pulih-bila-gagal kedua tak dapat diinduksi di JVM (rename lanjutan ke path yang baru dikosongkan tak bisa gagal selektif), dicatat apa adanya.

## [Belum rilis] — Audit 607: jalur pemulihan pasangan asli pulihkanTukarTersisa kini teruji
- Ketiga uji lama `pulihkanTukarTersisa` memakai berkas teks sehingga `pasanganCertKeyCocok` selalu false — seluruh jalur yang digerbang kecocokan kriptografis tak pernah berjalan. Ditambah tiga uji berpasangan asli (dibuat generator produksi `buatCa`, yang visibilitasnya dibuka setingkat ke paket mengikuti pola helper teruji lain): pasangan `.baru` terpasang, kunci `.baru` dipasang untuk sertifikat resmi, dan pasangan resmi tak cocok diperbaiki dari pasangan `.cad`. Dua sub-cabang gagal-rename di tengah (gulung-balik sertifikat dan fallback keyCad sesudah rename kunci gagal) tidak dapat diinduksi pada sistem berkas JVM — rename berkas ke path saudara yang belum ada dalam folder yang sama tak bisa gagal selektif — jadi keduanya tetap hanya dijaga kodenya, dicatat apa adanya.

## [Belum rilis] — Audit 606: uji nextMidnight kini mengunci zona DST transisi tengah malam
- Uji DST yang ada (America/New_York, transisi 02:00) lolos identik di algoritma lama maupun baru, jadi iterasi titik-tetap Audit 590 sebenarnya tak terkunci: refactor yang mengembalikan koreksi satu lintasan akan menghijaukan seluruh suite. Ditambah uji `nextMidnight_dstTransisiTengahMalam` untuk Asia/Beirut dan America/Santiago — dua zona yang transisinya tepat tengah malam — dengan nilai epoch yang dibuktikan porting 1:1 (algoritma lama menghasilkan nilai berbeda pada kedua kasus).

## [Belum rilis] — Audit 605: gerbang labeler mendukung kurung kurawal bersarang
- Lanjutan Audit 602: ekspansi kurawal gerbang mengambil grup sampai kurung tutup pertama, sehingga pola bersarang yang sah di minimatch seperti `*.{md,{txt,rst}}` ditolak palsu. Kini tutup grup dicari dengan menghitung kedalaman dan isi dipecah hanya pada koma terluar. Dibuktikan fixture: pola bersarang sah LOLOS, pola bersarang hantu tetap GAGAL, pohon bersih LOLOS.

## [Belum rilis] — Audit 604: pasang manual kini ikut membatalkan notifikasi update
- Sisa Audit 600 yang ditemukan self-review: notifikasi "update tersedia" (ID 2) hanya dibatalkan pada `AutoUpdate.cek()` berikutnya, padahal jalur pasang paling lazim justru manual — tombol Update dan pilih versi di Settings serta perintah `/update` bot. Ketiga jalur itu kini memanggil `batalkanNotifikasiUpdate` pada titik yang sama dengan pengosongan `pendingVersion` (saat binary benar-benar berubah), jadi notifikasi basi tak lagi tergantung selama sisa sesi Settings.

## [Belum rilis] — Audit 603: penjaga dialog impor tak lagi macet bila dialog gagal tampil
- Self-review menemukan celah penjaga Audit 597: `DialogAman.tampil` menelan kegagalan tampil (activity selesai atau `show()` melempar) tanpa dismiss maupun pengecualian, sehingga `dialogImporTampil` bisa macet `true` dan impor config terenkripsi mati total sampai Settings dibuka ulang. Kini sesudah `tampil(...)` hasilnya diperiksa: bila dialog ternyata tak tampil (`!isShowing()`), penjaga dilepas dan berkas + penanda tertunda dibiarkan agar `onResume` berikutnya menampilkan dialog lagi.

## [Belum rilis] — Audit 602: gerbang labeler memahami kurung kurawal minimatch
- `cek_labeler_glob` memakai glob Python yang tak mendukung ekspansi kurung kurawal, sementara minimatch (actions/labeler) mendukungnya — pola sah seperti `*.{md,txt}` ditolak palsu sebagai "tak mencocokkan satu pun berkas" (terbukti fixture saat self-review). Kini tiap pola diekspansi grup `{a,b}`-nya lebih dulu (rekursif, tanpa sarang) dan cukup satu varian yang cocok agar pola lolos. Dibuktikan fixture: pohon bersih LOLOS, `*.{md,txt}` LOLOS, `*.{xyz,abc}` tetap GAGAL.

## [Belum rilis] — Audit 601: path absolut disamarkan pada log yang keluar
- Gerbang samaran log menutup token/IP/email/domain/perangkat, tetapi tidak path berkas: log yang dibagikan/disimpan/dikirim bot membocorkan lokasi folder data kustom user, nama berkas backup/ekspor beserta stempelnya, dan path cache internal ke penerima log — metadata tata-letak yang user kira sudah "disamarkan".
- Kini `samarkanLog` mengganti path absolut berakar `/storage`, `/sdcard`, `/data`, dan `/mnt` dengan `[path]` (pola `POLA_PATH_ABSOLUT` dengan penjaga cepat `adaPathAbsolut`, mengikuti gaya pola yang ada). URL biasa tak tersentuh. Dikunci uji JVM baru.

## [Belum rilis] — Audit 600: notifikasi update dibatalkan saat tak ada lagi update tertunda
- Notifikasi "update tersedia" (ID 2) hanya hilang bila diketuk (autoCancel); tidak ada satu pun `NotificationManager.cancel` di basis kode. Update yang terpasang lewat bot/Settings meninggalkan notifikasi "vX available" tergantung tanpa batas untuk versi yang sudah terpasang — menyesatkan dan bisa memicu unduh ulang.
- Kini helper `batalkanNotifikasiUpdate` dipanggil di ketiga titik `cek()` yang mengosongkan keadaan tertunda (sudah versi terbaru, versi lebih baru dari rilis, dan sesudah auto-update terpasang), jadi pemeriksaan berkala berikutnya membersihkan laci begitu update tak lagi tertunda.

## [Belum rilis] — Audit 599: penanda pengumuman update dicatat sesudah dicoba, bukan digerbang notifikasi
- Blok pengumuman versi baru di `AutoUpdate.cek` mengirim pesan Telegram tanpa syarat, tetapi penanda dedup `tg_notified_version` hanya ditulis bila notifikasi sistem berhasil tampil. Bila user mematikan notifikasi aplikasi, penanda tak pernah tercatat dan pesan Telegram yang sama terkirim ulang pada setiap pembukaan aplikasi/Settings sampai update dipasang. Kini penanda dicatat sesudah pengumuman dicoba di kedua kanal; kanal dalam-aplikasi (`kabariTersedia`) tetap punya dedupnya sendiri.

## [Belum rilis] — Audit 598: jadwal bot menyiram tulis tertunda sebelum membaca prefs
- Efek samping Audit 593: siraman debounce bersama digeser oleh ketikan di field mana pun, sementara jadwal bot berjalan tetap 1000 ms sesudah ketikan token/chat dan membaca kredensial dari prefs untuk memutuskan pasang/batal alarm. Bila user lanjut mengetik password backup/SHA dalam jendela itu, siraman terdorong melewati jadwal — `TgBot.schedule()` menilai prefs lama (kosong untuk kredensial pertama kali) dan membatalkan alarm; tak ada yang memasangnya lagi, bot berhenti polling diam-diam.
- Kini runnable jadwal bot menyiram tulis tertunda lebih dulu sebelum memanggil `TgBot.schedule()`, jadi keputusan jadwal selalu membaca kredensial terbaru apa pun urutan ketikannya.

## [Belum rilis] — Audit 597: dialog password impor tak lagi tampil ganda pada jeda/resume biasa
- Blok tampil-lagi di onResume (Audit 592) berjalan pada setiap resume selama penanda tertunda terpasang — termasuk jeda biasa tanpa rotasi (tombol Home, kembali dari layar PIN) saat dialog pertama masih terbuka. Hasilnya dua dialog tertumpuk atas berkas temp yang sama; dialog bawah yang kemudian diisi password benar gagal berkas-hilang dan menaikkan penghitung gagal impor.
- Kini `tanyaPasswordImpor` menolak menampilkan dialog kedua selama satu dialog impor masih tampil (penjaga `dialogImporTampil`, dipasang tepat sebelum tampil dan dilepas pada dismiss); jalur gagal-bangun dialog juga membersihkan penanda tertunda agar tak ada tampil-lagi hantu.

## [Belum rilis] — Audit 596: reloadSettingsFromPrefs menyegarkan dasar simpan
- Jalur reload sesudah restore/impor lokal menulis ulang input folder/port/admin dari prefs, tetapi dasar simpan Audit 588 hanya ditangkap di `muatPreferensi`. Dasar yang basi membuat onPause berikutnya memperlakukan teks hasil reload sebagai suntingan user — kelas bug input basi #344 masuk lagi lewat reload: bila restore bot mendarat di antara reload dan onPause, nilai reload ditulis menang. Kini ketiga dasar disegarkan tepat sesudah input dimuat ulang, sama seperti di muatPreferensi.

## [Belum rilis] — Audit 595: sapuan cache tak lagi menghapus berkas impor yang dialognya tertunda
- Perbaikan Audit 592 dikalahkan sapuan yang sudah ada: `sapuCacheLatar` memanggil `sapuSisaImpor` di onCreate/onResume instance baru, dan sapuan itu menghapus semua berkas `vwcfg-import*` tanpa syarat — berkas yang dipertahankan untuk dialog lintas rotasi pasti tersapu. Bila sapuan menang lebih dulu, dialog tak pernah kembali; bila menang sesudahnya, berkas hilang di bawah dialog yang terbuka dan password yang benar tercatat sebagai percobaan gagal, sampai berkas backup ditolak permanen pada hitungan ke-5.
- Kini `sapuSisaImpor` mengecualikan berkas impor tertunda (penanda dibuat volatile karena dibaca thread sapuan latar), dan blok tampil-lagi di onResume sengaja tidak membersihkan penanda sebelum dialog memasang penandanya sendiri, menutup celah balapan di antaranya.

## [Belum rilis] — Audit 594: cek glob labeler kini mewajibkan pola sungguh mencocokkan berkas
- `cek_labeler_glob` (Audit 585) hanya mengumpulkan pola yang mengandung wildcard dan hanya memeriksa keberadaan awalan statisnya. Dua kelas pola mati lolos tanpa sinyal (terbukti fixture saat self-review): pola literal tanpa wildcard yang menunjuk berkas tak ada, dan pola yang awalannya ada tetapi cocok ke 0 berkas (mis. `shim/*.py` padahal shim hanya berisi .c).
- Kini pengumpul mengambil semua string pola dan tiap pola wajib mencocokkan minimal satu path lewat glob rekursif. Dibuktikan fixture dua arah di salinan buang: pohon bersih LOLOS; `BERKAS-HANTU.md`, `shim/*.py`, dan regresi lama `app/src/mian/**` sama-sama GAGAL menyebut polanya.

## [Belum rilis] — Audit 593: tulis debounce field Telegram kini satu transaksi, bukan per-field
- `SimpleTextWatcher` dulu menulis tiap field (token bot, chat ID, password backup, SHA) sebagai `apply()` terpisah per field dengan debounce 400 ms. Kematian proses mendadak tanpa lifecycle (force-stop, OOM, daya dicabut — lazim di STB) di antara dua apply meninggalkan prefs campuran lama/baru, mis. token baru berpasangan chat ID lama, dan bot berjalan dengan kredensial tak konsisten.
- Kini semua watcher memasukkan nilai ke satu keranjang bersama dan satu siraman menulis seluruhnya dalam SATU `edit().apply()`: konfigurasi tak pernah campuran. Jendela kehilangan ≤400 ms sesudah ketikan terakhir tetap ada pada kematian mendadak, tetapi kini konsisten utuh — semua field mundur bersama ke keadaan siraman sebelumnya, bukan sebagian. Siraman di onPause/onDestroy tak berubah.

## [Belum rilis] — Audit 592: rotasi tak lagi membatalkan dialog password impor secara diam-diam
- Dialog password impor ditutup saat onDestroy dan `OnDismissListener`-nya menghapus berkas enkrip temp sebagai perilaku anti-sisa — pada rotasi itu berarti dialog lenyap, temp terhapus, dan impor berakhir tanpa satu pun pesan; pengguna harus mengulang dari pemilih berkas.
- Kini keadaan impor tertunda (path temp + cap + sidik berkas) disimpan lewat `onSaveInstanceState` mengikuti pola ekspor-tertunda yang sudah ada; listener dismiss membedakan dismiss karena perubahan konfigurasi (temp dipertahankan, penanda tetap) dari pembatalan sungguhan (temp dihapus, penanda dibersihkan); dan `onResume` instance baru menampilkan lagi dialognya bila berkas temp masih ada.

## [Belum rilis] — Audit 591: keadaan sibuk Settings bertahan lintas rotasi
- `tugasBerjalan` dan `uiBusy` dulu per-instance activity. Rotasi menghancurkan activity tetapi thread tugasnya (pool bersama) terus berjalan memegang penanda instance lama: instance baru lahir "tidak sibuk" — tombol update/revert/backup/restore aktif lagi dan bilah progres unduhan tak ditampilkan, padahal tugas masih berjalan (operasi datanya sendiri tetap aman oleh kunci global, jadi yang hilang murni keadaan UI dan umpan balik penyelesaian dibuang penjaga isDestroyed).
- Kini `tugasBerjalan` statis se-proses, dan `refreshFromService` menyinkronkan `uiBusy` dari penanda statis itu pada tiap siklus segar: instance baru langsung tampil sibuk, dan penyelesaian tugas lama tercermin tanpa bergantung pada setBusy instance yang sudah mati.

## [Belum rilis] — Audit 590: nextMidnight iterasi koreksi offset sampai stabil (DST tepat tengah malam)
- `nextMidnight` hanya mengoreksi offset satu lintasan. Di zona yang transisi DST-nya tepat pukul 00:00 (America/Santiago, Asia/Beirut), pada hari spring-forward kandidat jatuh pukul 23:01 di hari lokal yang sama — backup harian jalan 1–2 jam lebih awal pada satu hari per tahun di zona itu (zona Indonesia tanpa DST tak terpengaruh).
- Kini koreksi diiterasi sampai titik tetap (maks 4 lintasan). Nilai harapan dibuktikan sebelum patch dengan porting Python 1:1 atas 5.840 hari-zona (8 zona × 2025–2026): hanya 4 kasus anomali yang berubah — masing-masing menjadi 01:01 lokal sesudah tengah malam yang dilompati — seluruh kasus lain identik karakter, dan hasil tidak pernah waktu lampau.

## [Belum rilis] — Audit 589: jeda minimum 30 detik antar perintah berat dan antar /restart bot
- Satu-satunya pembatas perintah berat (`/backup`, `/restore`, `/ca`, `/update`, `/webvault`) dulu adalah kunci cegah-bersamaan: begitu tugas selesai, perintah berikutnya langsung jalan penuh lagi — zip + unggah berulang menguras disk/baterai/kuota, dan `/update` berurutan mengunduh ulang. `/restart` manual bahkan lepas dari backoff restart-otomatis, jadi deretannya membuat proses Vaultwarden mati-nyala berulang.
- Kini `runBeratDenganKunci` menolak perintah berat selama 30 detik sesudah tugas berat sebelumnya berakhir (stempel elapsed, `tugasBeratBerakhirMs`), dan `/restart` di `perintahKontrol` punya stempel jeda yang sama. Keputusan jeda adalah fungsi murni `dalamJedaBerat` yang gagal-terbuka saat jam elapsed mundur agar perintah tak pernah terkunci selamanya; dikunci uji JVM baru termasuk batas 29.999/30.000 ms.

## [Belum rilis] — Audit 588: simpan onPause tak lagi menimpa prefs eksternal dengan input basi
- Input Settings (folder data, port, token admin) hanya dimuat di onCreate dan sesudah restore/impor lokal — tidak di onResume. Restore lewat bot mengubah prefs (port ternormalisasi) di belakang activity yang sedang terbuka; penjaga `restoreJalan()` hanya menahan penulisan selama restore berjalan, jadi onPause berikutnya menulis nilai tampilan lama sebagai pemenang lewat `tulisBilaBeda`. Port hasil restore hilang diam-diam dan server berikutnya start di port lama.
- Kini `simpanPengaturanValid` hanya menulis field yang benar-benar diubah user sejak nilai itu dimuat/disimpan terakhir: dasar per-field ditangkap di akhir `muatPreferensi` dan diperbarui sesudah tiap penyimpanan, dengan predikat murni `berubahDariDasar` (dasar null = perilaku lama). Field tak tersentuh membiarkan perubahan eksternal menang; suntingan user yang disengaja tetap tersimpan seperti sebelumnya. Predikat dikunci uji JVM baru.

## [Belum rilis] — Audit 587: gerbang checkpoint tak lagi memblokir restore untuk DB yang tak bisa dibuka
- Efek samping Audit 579: `checkpointWal` menjadi gerbang keras di kedua jalur restore, tetapi ia membuka DB dengan OPEN_READWRITE — DB live yang korup/rusak header membuatnya melempar pengecualian yang tertelan menjadi `false`, sehingga restore BATAL dengan pesan "checkpoint WAL gagal" justru untuk keadaan yang paling membutuhkan restore. Jalan keliling satu-satunya menghapus db.sqlite3 manual.
- Kini kedua jalur memakai predikat murni baru `checkpointWajib(dbFile)` (= `isSqliteFile`): gerbang hanya berlaku bila DB live memang berkas SQLite yang bisa dibuka. DB hilang/bukan SQLite tak punya WAL yang dapat diselamatkan (pemulihan WAL membutuhkan berkas utama yang terbuka), jadi restore berjalan; pre-backup tetap menyalin byte apa adanya. Untuk DB sehat yang checkpoint-nya gagal, perlindungan Audit 579 utuh. Predikat dikunci uji JVM baru.

## [Belum rilis] — Perbaikan build: dua uji pasangDbDariSalinan/pulihkanDbLama diselaraskan dengan penjaga integritas Audit 578
- Penjaga baru di `pasangDbDariSalinan` (Audit 578) mengubah kontrak yang dikunci dua uji JVM: fixture salinan teks polos "DB-LAMA" kini memang harus ditolak, tetapi uji lama mengharapkannya terpasang — `testDebugUnitTest` gagal di CI (run 40754364765) untuk `pasangDbDariSalinanGantiDbDanBuangWalShm` dan `pulihkanDbLamaPakaiSalinanAtauBuangDb`. Jalur sukses penjaga memerlukan SQLite Android asli sehingga tak bisa diuji di JVM — preseden yang sama seperti `pasangDbRestoreAtomik` yang juga hanya diuji jalur tolaknya.
- Kedua uji ditulis ulang mengunci kontrak baru: salinan bukan SQLite ditolak (IOException) dan DB live terbukti tak tersentuh; cabang tanpa salinan tetap membuang DB. Perilaku produksi tidak berubah.

## [Belum rilis] — Audit 586: gerbang kini menjaga daftar izin wajib manifest dari penghapusan
- Gerbang dulu asimetris: izin baru diperingatkan (`cek_izin_baru`), tetapi izin yang dihapus dari manifest tak terlihat cek mana pun — penghapusan tak sengaja (mis. konflik merge) bisa mematikan fitur diam-diam dengan CI hijau, termasuk regresi persis perbaikan Audit 562 (POST_NOTIFICATIONS) atau INTERNET.
- Cek baru `cek_izin_wajib` dengan baseline `IZIN_WAJIB` (11 izin yang kini dideklarasikan): manifest yang kehilangan satu pun GAGAL dengan nama izinnya, dan pesannya menjelaskan jalur pengecualian yang disengaja (ubah baselinenya di commit yang sama). Terbukti fixture dua arah (bersih LOLOS; hapus POST_NOTIFICATIONS GAGAL; hapus INTERNET juga GAGAL menyebut keduanya).

## [Belum rilis] — Audit 585: gerbang kini memeriksa keberadaan path pola labeler
- Pola glob di `labeler.yml` yang salah ketik (mis. `app/src/mian/**`) tetap YAML valid dan dulu lolos gerbang — label berhenti menempel pada PR tanpa sinyal apa pun (regresi sekelas yang baru diperbaiki Audit 564 bisa kambuh diam-diam).
- `cek_labeler_glob` baru: awalan statis tiap pola (bagian sebelum wildcard pertama) wajib ada sebagai berkas/folder nyata, jika tidak gerbang GAGAL menyebut polanya. Terbukti fixture dua arah di salinan buang (bersih LOLOS; pola `mian` GAGAL dengan pesan tepat).

## [Belum rilis] — Audit 584: gerbang kini memvalidasi YAML .github di luar workflows/ juga
- `cek_workflow_yaml` hanya memindai `.github/workflows/`; `dependabot.yml`, `labeler.yml`, dan `actions/*/action.yml` tak pernah divalidasi — YAML rusak di dependabot menghentikan Dependabot total tanpa satu pun CI merah, dan salah ketik `package-ecosystem` pun lolos.
- Cek baru `cek_yaml_github_lain`: ketiga berkas itu wajib ada, ter-parse sebagai peta YAML; entri dependabot wajib punya ekosistem yang dikenal dan `directory`; action wajib punya kunci `runs`. Terbukti fixture dua arah di salinan buang (bersih LOLOS; ekosistem "gradel" GAGAL dengan pesan tepat; YAML rusak GAGAL).

## [Belum rilis] — Audit 582: area gulir dialog Tentang kini bisa difokuskan remote TV
- `about_scroll` adalah satu-satunya ScrollView di aplikasi tanpa `focusable` dan tanpa latar cincin fokus, padahal tingginya dibatasi 40% layar sehingga baris-baris Tentang bawah hanya bisa dibaca dengan menggulir — sesuatu yang mustahil bagi pengguna remote/STB karena isinya murni TextView tak-focusable. Kini mengikuti pola dua ScrollView lain (`focusable` + `focusableInTouchMode` + latar `item_focus_bg`), jadi D-pad bisa menggulirnya dan cincin fokus terlihat.

## [Belum rilis] — Audit 581: sisa frasa 'mode sederhana/panel Lanjutan' di Pemetaan fitur AGENTS.md dibersihkan
- Melanjutkan Audit 567: pembersihan putaran-16 memburu nama kunci prefs, tetapi frasa prosa "termasuk mode sederhana" dan "dalam panel Lanjutan" di bagian Pemetaan fitur AGENTS.md lolos. Keduanya merujuk fitur yang tidak ada — Settings memakai seksi buka-tutup `sec_buka_*`. Uraian kini menyebut seksi buka-tutup itu secara akurat.

## [Belum rilis] — Audit 580: CustomX509TrustManager naik ke severity error lint
- Melanjutkan Audit 563: cek itu sudah dihidupkan lagi dari daftar `disable`, tetapi severity bawaannya Warning sehingga `abortOnError` tak pernah menghentikan build karenanya — jaring pengaman TLS hanya berupa warning terkubur di laporan. Kini cek tersebut masuk daftar `error` lint di `app/build.gradle.kts`: implementasi TrustManager kustom apa pun yang muncul di masa depan menggagalkan `lintDebug`. Tak ada implementasi kustom saat ini, jadi tak ada temuan baru yang diharapkan.

## [Belum rilis] — Audit 579: checkpoint WAL gagal kini membatalkan restore, bukan diteruskan diam-diam
- Kedua jalur restore (bot di `TgBackup`, zip di Settings) memanggil `checkpointWal(dbFile)` tanpa memeriksa hasil boolean-nya, lalu menyalin berkas utama saja sebagai pre-backup dan menghapus -wal/-shm live tanpa syarat. Bila checkpoint tak tuntas (busy, atau galat I/O yang ditelan), transaksi ter-commit terbaru masih hanya ada di -wal itu — pre-backup kehilangannya dan restore yang kemudian gagal mengembalikan DB tanpa transaksi terakhir, permanen.
- Kini hasil checkpoint diperiksa: jalur bot melempar IOException pembatal sebelum apa pun disentuh; jalur Settings membatalkan dengan pesan + return langsung (bukan throw, agar catch pemulih tak berjalan atas DB yang belum disentuh). Sesudah checkpoint sukses (WAL sudah tertuang ke berkas utama), penghapusan -wal/-shm tetap aman seperti sebelumnya.

## [Belum rilis] — Audit 578: pre-backup separuh tak lagi bisa terpasang menggantikan DB live yang utuh
- Lubang berantai di jalur pulih restore Settings: `copyFile` meninggalkan berkas tujuan parsial bila IOException meledak di tengah loop (cek panjang hanya berjalan sesudah loop selesai normal), dan `pasangDbDariSalinan` memasang salinan pengaman tanpa cek integritas apa pun — jadi restore yang gagal karena disk penuh justru memasang pre-backup terpotong sebagai `db.sqlite3`, merusak vault yang sebenarnya belum tersentuh.
- Tiga lapis perbaikan: (1) `copyFile` kini menghapus tujuan dan melempar ulang bila penyalinan gagal di tengah — sisa parsial tak pernah tertinggal untuk pemanggil mana pun (tujuan memang sudah terpotong sejak aliran dibuka, jadi tak ada isi lama yang dikorbankan); (2) `pasangDbDariSalinan` mewajibkan salinan lolos `isSqliteFile` + `cekIntegritasDb` — penjaga yang sama seperti jalur pasang DB restore biasa — sebelum rename; (3) `pulihkanDbLama` tak lagi menghapus DB live bila pre-backup sempat dicoba dibuat tetapi berkasnya tiada (tanda kegagalan terjadi sebelum DB disentuh).

## [Belum rilis] — Audit 577: uji keunikan stempelUnik diperkuat ke perbandingan berpasangan
- Asersi lama hanya gagal bila dua pasangan panggilan berurutan sama-sama identik, sehingga duplikat berpasangan sesekali — persis regresi yang membahayakan nama berkas backup — bisa lolos. Kini tiga stempel berurutan dibandingkan berpasangan (s1≠s2, s2≠s3, s1≠s3); sufiks acak 48-bit `SecureRandom` membuat ketiganya berbeda secara praktis selalu, jadi asersi ini stabil sekaligus jauh lebih kuat.

## [Belum rilis] — Audit 576: asersi ganda identik di KernelCompatTest dihapus
- Uji `legacyMemoKonsistenDenganMurni` menulis `assertEquals` yang sama persis dua kali berurutan — salinan yang tak menambah cakupan dan memberi rasa cakupan semu. Satu baris dihapus; komentar menegaskan sifat uji ini memang konsistensi memo terhadap jalur murni, sementara nilai eksplisit fungsi murninya dikunci uji-uji bernilai tetap di berkas yang sama.

## [Belum rilis] — Audit 575: duplikasi asersi humanBytes antar-uji dihapus
- Uji `satuDesimal_formatSamaSepertiDulu` mengulang persis tiga asersi `humanBytes` (1536, 2 MB, 1 GB) dari `humanBytes_skalaBenar` — perubahan perilaku yang sah harus disunting di dua tempat dan mudah basi sebelah. Tiga asersi duplikat itu dihapus; perilaku `humanBytes` tetap dikunci satu-satunya di uji namanya, dan uji `satuDesimal` mempertahankan asersi fungsi namanya sendiri. (Keluarga lain yang disebut audit — `normalisasiEntriZip` nama-telanjang vs berprefix folder, dan pengulangan tematik `namaBolehDibagikan`/`normVersion` — terverifikasi bukan duplikat nilai: masukan atau sudut regresinya berbeda, jadi dibiarkan.)

## [Belum rilis] — Audit 574: uji jam-mundur TgBot kini menegaskan hasil catatMundurDanBolehIngatkan
- Uji `mundurTakMenurunkanTandaAir` memanggil `catatMundurDanBolehIngatkan` tanpa menegaskan nilai kembali — cabang "boleh ingatkan saat jam mundur" (fungsi metode itu sendiri) bisa regresi diam-diam; yang terkunci hanya tanda air, yang sudah dicakup uji tetangga. Panggilan kini dibungkus `assertTrue`: pada jam mundur sesudah tanda air tinggi, peringatan memang harus boleh terbit (di uji JVM jalur ini lewat cabang fallback jam-elapsed yang juga mengembalikan true).

## [Belum rilis] — Audit 573: uji copyFile kini membandingkan isi byte, bukan hanya panjang
- Uji `copyFile_menyalinUtuhDenganPanjangSama` hanya menegaskan panjang asal = panjang salinan, padahal namanya mengklaim keutuhan: regresi yang menulis byte korup/nol berpanjang sama akan tetap hijau. Kini isi salinan dibandingkan byte-per-byte (`assertArrayEquals`) terhadap isi asal 70.000 byte acak, selaras uji `salinTls` di berkas yang sama.

## [Belum rilis] — Audit 572: dedupe folderBytesWalk kini memakai path canonical; uji tak lagi mengunci angka inflasi 3100
- `TgBackup.folderBytesWalk` men-dedupe kunjungan folder memakai `getAbsolutePath()` (variabelnya bahkan bernama `kanon`), sehingga symlink melingkar menghasilkan path tekstual berbeda di tiap level dan tak terdeteksi — berkas yang sama dihitung ulang sampai cap kedalaman 32 (satu berkas 100 byte terhitung 3100), dan uji justru mengunci angka inflasi itu sebagai nilai harapan.
- Dedupe kini memakai `getCanonicalPath()`: symlink loop terdeteksi sebagai folder yang sama dan dilewati. Uji diperbarui di commit yang sama menjadi 100 dengan komentar derivasi. Dampak praktis: taksiran ukuran folder (untuk pra-cek ruang backup/restore) tak lagi membengkak pada folder bersymlink-loop.

## [Belum rilis] — Audit 571: asersi uji potongEkorBaris diketatkan ke nilai eksak
- Uji `potongEkor_jumboMulaiBatasBaris` memakai disjungsi longgar (`startsWith("baru") || tak ada newline || contains("baru")`) yang juga lolos untuk keluaran salah seperti potongan tengah-baris — persis regresi yang dicegah metode itu. Nilai harapan eksaknya ("baru2" untuk masukan uji, dibuktikan porting Python 1:1 dari implementasi) kini dikunci dengan `assertEquals`, selaras uji-uji saudara di berkas yang sama.

## [Belum rilis] — Audit 570: pohon struktur AGENTS.md diselaraskan dengan isi repo nyata
- Pohon "Struktur repository" di AGENTS.md tertinggal: hanya memuat 2 dari 8 workflow (codeql/gitleaks/labeler/label-audit/stale/auto-merge dan action komposit resolve-vw tak tercantum), pohon java memuat 21 dari 23 kelas (AboutDialog dan DialogAman hilang), dan daftar varian `res/values` menghilangkan `values-in` (folder terjemahan yang wajib sinkron) serta `values-sw600dp`.
- Pohon kini menyebut seluruh workflow dan action komposit, kedua kelas yang hilang, dan varian values yang lengkap — peta pertama pengelola/agen kembali bisa dipercaya.

## [Belum rilis] — Audit 569: klaim assembleDebug di uraian build-apk AGENTS.md dikoreksi
- AGENTS.md menyebut job build-apk menjalankan `assembleDebug`, padahal workflow hanya memanggil Gradle dua kali: `lintDebug + testDebugUnitTest` lalu `assembleRelease` — APK debug tidak pernah di-assemble di CI. Uraian dikoreksi agar diagnosis build tidak berangkat dari cakupan CI yang salah.

## [Belum rilis] — Audit 568: sumber "(d) bundled fallback" yang tak ada dihapus dari uraian ensureBinary di AGENTS.md
- Uraian `ensureBinary()` di AGENTS.md menyebut empat sumber binary termasuk "(d) bundled fallback", padahal implementasinya hanya punya tiga (cache internal, binary user folder data, unduh dari release) dan tak ada jalur aset untuk binary — satu-satunya aset yang dibuka aplikasi adalah chain sertifikat GitHub dan `vw_version.txt`. Uraian kini sesuai kode dan menegaskan binary tak pernah dibundel di APK.

## [Belum rilis] — Audit 567: kunci prefs hantu dan klaim mode sederhana dihapus dari AGENTS.md dan README
- Daftar kunci SharedPreferences di AGENTS.md memuat dua kunci yang tidak ada di kode mana pun — `mode_sederhana` dan `advanced_open` (grep seluruh repo: 0 hasil) — sementara kunci keadaan seksi Settings yang nyata (`sec_buka_server/keamanan/rawat/telegram/log`) tak tercantum. Daftar itu rujukan pekerjaan ekspor/impor config dan daftar-tolak rahasia, jadi rujukannya kini diselaraskan: dua kunci hantu dihapus, keluarga `sec_buka_*` ditambahkan.
- Kalimat "mode sederhana" di README (Inggris/Indonesia/Rusia) dihapus — fitur itu tidak pernah ada; wizard 3 langkah memang satu-satunya alur Settings.

## [Belum rilis] — Audit 566: checkout CodeQL kini persist-credentials: false, konsisten dengan workflow build
- Semua checkout di `build-apk.yml`/`build-binary.yml` memakai `persist-credentials: false`, tetapi checkout CodeQL tidak — padahal job `analyze` memegang `security-events: write` dan Autobuild mengeksekusi build Gradle dari tree hasil checkout, termasuk untuk PR. Tanpa pengaturan itu token job tersimpan di `.git/config` lokal selama job dan dapat dibaca kode build di runner yang sama.
- Checkout CodeQL kini memakai `persist-credentials: false` seperti pola repo. Analisis CodeQL memakai akses API-nya sendiri, bukan kredensial git lokal, jadi perilaku analisis tak berubah.

## [Belum rilis] — Audit 565: pola grup dependabot gradle-tools kini cocok dengan nama AGP nyata
- Grup `gradle-tools` memakai pola `com.android.tools*`, padahal tak ada dependensi bernama itu di repo; Dependabot menamai AGP `com.android.application` (terbukti dari entri `ignore` di berkas yang sama yang bekerja atas nama itu). Grup itu tak akan pernah mengelompokkan apa pun dan update AGP selalu datang tak tergrup — konfigurasi mati yang menyesatkan.
- Pola grup diganti menjadi `com.android.application`, sesuai nama nyata plugin di `build.gradle.kts` akar. Entri `ignore` untuk major AGP tidak berubah.

## [Belum rilis] — Audit 564: labeler kini melabeli .github/actions/** sebagai ci
- Action komposit `.github/actions/resolve-vw` dipakai kedua workflow build sebagai penentu versi, tetapi pola label `ci` di `.github/labeler.yml` tak menjangkaunya (juga bukan `app`/`docs`) — PR yang hanya mengubah action kritis itu tak menerima label apa pun dan tak terlihat saat PR disaring per label.
- Pola `.github/actions/**` ditambahkan ke label `ci`.

## [Belum rilis] — Audit 563: lint CustomX509TrustManager dihidupkan lagi — jaring pengaman verifikasi TLS pulih
- Cek lint `CustomX509TrustManager` tadinya dimatikan global di `app/build.gradle.kts`, padahal tak ada satu pun implementasi `X509TrustManager` kustom di kode — kedua situs TLS memakai `TrustManagerFactory` platform dengan KeyStore CA ter-pin. Yang hilang murni jaring pengamannya: TrustManager baru yang menerima semua sertifikat tak akan tertandai lint walau `abortOnError=true`.
- Entri itu dihapus dari daftar `disable`; lint kini akan menandai implementasi TrustManager kustom apa pun yang muncul di masa depan. Karena tak ada implementasi kustom saat ini, tak ada temuan lint baru yang diharapkan.

## [Belum rilis] — Audit 562: POST_NOTIFICATIONS kini dideklarasikan — notifikasi update bisa tampil lagi di Android 13+
- `AutoUpdate.tampilkanNotifikasi` memeriksa `POST_NOTIFICATIONS` pada SDK >= 33 dan membatalkan notifikasi bila tak granted, tetapi izin itu tidak pernah dideklarasikan di `AndroidManifest.xml` — izin tak terdeklarasi tak bisa pernah GRANTED, jadi notifikasi "update tersedia" dari cek berkala tak pernah tampil di Android 13+, diam-diam tanpa log.
- Izin kini dideklarasikan di manifest. Untuk targetSdk 28, perangkat Android 13+ memberi izin notifikasi secara bawaan pada aplikasi legasi begitu izinnya terdeklarasi; perangkat lama mengabaikan deklarasi izin yang tak dikenal. Jalur notifikasi foreground ServerService tidak berubah.

## [Belum rilis] — Audit 561: dedupe penjaga jejak uji kini per (berkas, metode), bukan nama saja
- #312 membuat pencocokan jejak uji sadar kelas, tetapi dedupe `sudah` di `cek_uji_baru` dan `cek_uji_ubah` masih berbasis nama global: metode kedua bernama sama di kelas lain dalam diff yang sama tak pernah diperiksa jejaknya — sisa fail-open sempit di cek yang baru diperkuat.
- Kunci dedupe kini `(path, nama)` di kedua cek, selaras dengan kesadaran kelasnya. Terbukti fixture: metode baru `cocokChat` ditambahkan ke TgBot dan ServerService dalam satu diff → dua peringatan terbit (sebelumnya hanya satu).

## [Belum rilis] — Audit 560: validasi YAML workflow kini memeriksa struktur on/jobs, bukan hanya sintaks
- `cek_workflow_yaml` (dari #309) hanya menangkap galat parse: berkas workflow yang terpotong kosong atau kehilangan kunci `on:`/`jobs:` tetap LOLOS, lalu GitHub menonaktifkannya diam-diam sesudah merge — automasi mati tanpa ada yang tahu, persis kelas kegagalan yang hendak dicegah #309.
- Hasil parse kini wajib berupa peta level-atas yang memuat kunci `on` (atau kunci boolean True khas YAML 1.1 untuk `on:`) dan `jobs`; selain itu GAGAL dengan nama berkasnya. Terbukti fixture dua arah: `stale.yml` dikosongkan → GAGAL menyebut berkasnya; kedelapan workflow asli → LOLOS.

## [Belum rilis] — Audit 559: jalan() gerbang kini decode UTF-8 toleran, tak lagi GAGAL palsu pada berkas teks non-UTF-8
- `jalan()` menjalankan git dengan `text=True` tanpa encoding/errors: byte tak valid UTF-8 di output `git diff` (berkas teks Latin-1 yang lolos klasifikasi teks git) atau locale non-UTF-8 (LC_ALL=C pada hook) melempar UnicodeDecodeError yang tertelan menjadi None — seluruh cek berbasis diff batal dan gerbang GAGAL dengan pesan yang menyebut penyebab yang salah (riwayat/safe.directory/timeout).
- Decode kini eksplisit `encoding="utf-8", errors="replace"`: byte asing diganti karakter pengganti, cek diff tetap berjalan. Terbukti fixture dua arah: berkas teks Latin-1 yang diubah — sebelum perbaikan keluar GAGAL "git diff gagal…", sesudahnya pesan itu hilang dan cek diff berjalan normal (satu-satunya GAGAL tersisa adalah penolakan format biner tak dikenal yang memang benar untuk berkas itu).

## [Belum rilis] — Audit 558: semua job workflow kini punya timeout-minutes eksplisit
- Hanya CodeQL yang membatasi job-nya (60 menit); job lain mewarisi batas default platform 360 menit. Build binary atau panggilan `gh` yang menggantung dapat membakar jam runner berjam-jam dan — karena grup concurrency repo memakai cancel-in-progress: false — menahan run berikutnya di belakang job yang sebenarnya sudah mati.
- timeout-minutes kini terpasang di semua job: verifikasi/resolve 15/10, build-apk 90, build-binary 120, publish-binary 30, gitleaks/stale/auto-merge 15, labeler/label-audit 5 (CodeQL tetap 60). Seluruh YAML terverifikasi parse sesudah perubahan (dan kini juga dijaga gerbang Audit 553).

## [Belum rilis] — Audit 557: label audit untuk pengecualian stale kini terpasang otomatis
- Pengecualian stale bagi antrean audit (#245) bekerja hanya karena label `audit` dipasang manual sekali itu; tak ada workflow/template yang memasangnya. Issue audit putaran berikutnya yang lupa dilabeli akan ditandai stale di hari ke-45 dan ditutup otomatis di hari ke-59 — antrean kerja hilang tanpa keputusan.
- Workflow baru `label-audit.yml` berjalan pada issue dibuka/diedit: badan yang memuat penanda baku "Ronde audit" otomatis mendapat label `audit` (badan diteruskan lewat env, bukan interpolasi run, agar aman dari injeksi). Issue non-audit tak disentuh.

## [Belum rilis] — Audit 556: penjaga jejak uji kini sadar kelas, bukan sekadar nama metode
- `disebut_di_uji` hanya mencocokkan nama metode + `(` di berkas uji mana pun: metode murni baru bernama umum yang kebetulan sama dengan panggilan milik kelas lain di uji (mis. `cocokChat(` di UtilTest) lolos tanpa peringatan sama sekali, padahal klaim AGENTS.md "uji logika murni baru" dijaga alat ini.
- Untuk metode baru, jejak kini wajib berupa panggilan terkualifikasi `Kelas.metode(` di uji mana pun, atau penyebutan nama di berkas uji kelas itu sendiri (`<Kelas>Test.java`). Terbukti fixture: metode baru `TgBot.cocokChat` (nama bertabrakan dengan uji Util) kini memicu peringatan; metode #310 yang memang diuji terkualifikasi tetap bersih. Cek metode-yang-diubah tetap berbasis nama (header hunk tak membawa nama kelas).

## [Belum rilis] — Audit 555: dua uji tanpa asersi terakhir kini memeriksa postcondition nyata
- Sisa pola yang pernah diperbaiki di #174: `cleanupOldBackups_nolAman` dan `peringatkanHapusPinAbaikanContextNull` hanya membuktikan "tidak crash untuk null" tanpa satu pun asersi — regresi yang membuat cleanup menciptakan folder atau menghapus isi pada masukan nol akan tetap hijau.
- Uji cleanup kini menegaskan folder tak-ada tetap tak ada sesudah panggilan dan folder kosong tetap ada serta tetap kosong. Uji peringatan menegaskan invarian fail-closed `sudahUjiHapus(null)` tetap true sebelum dan sesudah kedua cabang panggilan null — keadaan "sudah diuji" tak bisa tercipta dari konteks null.

## [Belum rilis] — Audit 554: empat kelas utama terakhir kini punya uji JVM
- `AboutDialog`, `DialogAman`, `LogExport`, dan `TgBotReceiver` adalah empat kelas utama tanpa satu pun uji (0 dari 23), sehingga regresi di pengelolaan dialog, ekspor log, dan receiver bot hanya tertangkap uji perangkat manual. Sebagian besar logikanya terikat Android, jadi yang diuji adalah jahitan murninya — dua di antaranya dibuka persis untuk itu tanpa mengubah perilaku:
- `AboutDialog.rangkaianVersi` diekstrak kata-demi-kata dari `tampil` (komposisi baris versi: "?" bila kosong, sufiks binary/vault hanya bila ada) dan diuji 6 kombinasi. `LogExport.namaLog` dibuka paket dan diuji formatnya (awalan + stempel + 12 hex acak + .txt, dua panggilan berbeda). `TgBotReceiver.konfigBotSiap` diekstrak dari `adaKonfig` (token+chat wajib terisi sesudah trim) dan diuji 8 kombinasi termasuk null/spasi. `DialogAman` diuji keamanan-null jalur tampil/tutup. Jumlah kelas uji di AGENTS.md diperbarui 19 → 23.

## [Belum rilis] — Audit 553: YAML semua workflow kini divalidasi gerbang wajib di setiap PR
- Workflow automasi (auto-merge/stale/labeler) berjalan lewat `pull_request_target`/jadwal, sehingga PR yang mengubah workflow itu sendiri tak pernah menjalankannya — dan tak ada validasi YAML workflow di gerbang mana pun: YAML rusak atau perubahan pemicu yang keliru baru ketahuan sesudah merge, dari automasi yang diam-diam mati. (Semantik `pull_request_target` memakai versi dasar memang disengaja demi keamanan dan tidak diubah.)
- `verifikasi-patch.py` — konteks wajib proteksi branch yang berjalan di setiap PR — kini mem-parse YAML seluruh berkas `.github/workflows/*.yml|yaml` (`cek_workflow_yaml`): parse gagal = GAGAL dengan nama berkas dan pesan parsernya; PyYAML yang absen di suatu lingkungan hanya menurunkan cek ini jadi peringatan agar gerbang tak merah palsu. Terbukti fixture dua arah: `stale.yml` dirusak sintaksnya → GAGAL menyebut berkasnya; pohon asli → LOLOS.

## [Belum rilis] — Audit 552: penjaga bekas-hapusan kini memindai pemanggil di seluruh pohon, bukan hanya diff
- `cek_panggil_bekas_hapusan` hanya memeriksa baris tambah pada rentang diff yang sedang diperiksa, padahal `tools/bekas-hapusan.txt` sendiri menjanjikan daftar itu "menjaga push-push berikut": pemanggil metode terhapus yang masuk lewat merge/cherry-pick, atau yang lolos di satu push tak terkait, tak akan pernah ditandai lagi dan baru ketahuan dari kompilasi CI merah (untuk kode uji: hanya jalur PR).
- `cek_daftar_hapusan` — yang sudah memindai seluruh pohon untuk definisi — kini juga memindai pemanggil (`nama(` / `::nama`) di semua berkas Java pohon atas teks terkupas komentar/string-nya, dan menggagalkan gerbang dengan nama berkasnya. Terbukti fixture dua arah di salinan buang: pemanggil `batalTunda()` yang di-commit dua langkah sebelum pemeriksaan tetap tertangkap GAGAL walau diff terakhir tak terkait; pohon asli tetap LOLOS tanpa positif palsu.

## [Belum rilis] — Audit 551: kolom SHA-256 binary kini punya validasi inline seperti kolom lain
- Kolom `binSha` hanya punya penyimpan per-ketukan tanpa pemeriksaan dan tanpa baris galat pendamping — salah tempel (spasi di tengah, karakter non-hex, terpotong) tersimpan diam-diam dan baru terasa saat Start menolak binary manual dengan pesan umum "SHA-256 tidak cocok", menyalahkan berkasnya padahal kolomnya yang rusak. Jalur impor config sudah lama memvalidasi format ini (`TgBackup.shaHexValid`).
- Kolom kini diperiksa `galatBinSha` (kosong sah; terisi wajib tepat 64 hex menurut `shaHexValid` yang sama) sebagai bagian `validasiInline`, dengan baris galat `binShaError` baru di kartu Telegram dan string `bin_sha_error` (Inggris + Indonesia). Mengikuti pola token/chat: galat ditampilkan tanpa memblokir tombol Start. Uji JVM baru mengunci keenam kasus (null/kosong/hex-64 sah/pendek/spasi-di-tengah/non-hex).

## [Belum rilis] — Audit 550: jalur delta LogActivity tak lagi menghitung highlight penuh untuk dibuang
- Sisa #268: penyegar log menghitung `highlightLog` atas SELURUH teks tampil (sampai 100.000 karakter) tanpa syarat di setiap tick 1,5 detik, padahal pada kasus paling umum (log bertambah, tanpa pencarian) jalur delta hanya memakai sorotan delta — hasil highlight penuh itu dibuang. Setengah manfaat jalur delta hilang: CPU/baterai STB terpakai untuk Spannable yang tak pernah tampil.
- Kelayakan delta kini ditentukan lebih dulu dan highlight penuh hanya dihitung bila delta tak bisa dipakai; jalur delta murni bekerja sebatas ukuran delta seperti maksud perbaikan asalnya.

## [Belum rilis] — Audit 549: fokus naik dari OK dialog About kembali ke Check update, bukan GitHub
- Kedua tombol baris atas dialog About (GitHub dan Check update) sama-sama menunjuk turun ke tombol OK, tetapi OK menunjuk naik hanya ke GitHub: pengguna yang turun dari Check update lalu menekan Atas mendarat di GitHub — arah pulang tak konsisten.
- `btn_about_ok` kini `nextFocusUp=@id/btn_about_update` (tombol aksi utama baris atas); GitHub tetap satu langkah Kiri dari Check update, jadi seluruh dialog terjelajahi dua arah tanpa lompatan mengejutkan.

## [Belum rilis] — Audit 548: fokus turun dari Revert kini mampir ke Cert info sebelum Install cert
- Rantai fokus kartu Rawat timpang satu arah: naik dari `installCert` mampir ke `certInfoToggle` dan `certInfoToggle` sendiri menunjuk turun ke `installCert`/naik ke `revert`, tetapi `revert` menunjuk `nextFocusDown` langsung ke `installCert` — pengguna D-pad yang menelusuri turun tak pernah mendarat di tombol info sertifikat yang secara visual berada di antara keduanya.
- `revert` kini `nextFocusDown=@id/certInfoToggle`, menutup rantai dua arah yang konsisten (revert → certInfoToggle → installCert, dan sebaliknya).

## [Belum rilis] — Audit 547: teks galat di atas sorotan pencarian log kini terbaca di kedua mode
- Karakter tersorot pada baris galat memadukan warna teks `log_error` dengan latar `search_highlight`: siang #C62828 di atas #FFE082 = 4,35:1 dan malam #F26A62 di atas #7A5A00 = 2,13:1 (hitung WCAG) — di bawah ambang 4,5:1, justru pada baris yang paling sering dicari pengguna. Pasangan normalnya sehat, jadi yang digeser hanya warna galatnya.
- `log_error` siang dipergelap ke #B71C1C (di atas sorotan 5,08:1; di atas putih 6,57:1) dan malam diterangkan ke #FFD2C8 (di atas sorotan 4,65:1; di atas latar gelap aplikasi >12:1). Pemakai lain warna yang sama (galat PIN, pewarnaan log MainActivity) ikut membaik atau tetap jauh di atas ambang di kedua mode; tidak ada perubahan kode.

## [Belum rilis] — Audit 546: penjaga WakeLock tugas berat kini memasang ulang lock yang sudah kedaluwarsa
- Penjaga perpanjang WakeLock tugas berat (tik tiap 10 menit) hanya memperpanjang bila `isHeld()` masih true. Bila satu tik terlambat melewati momen kedaluwarsa lock 20 menit — persis saat perangkat mulai tidur — tik berikutnya melihat lock sudah lepas, tak mengambil ulang, tetapi tetap menjadwalkan diri sampai batas 6 jam: sisa tugas berat (unduh/restore/update di jaringan lambat) berjalan tanpa WakeLock dan bisa membeku oleh doze.
- Tik penjaga kini memanggil `acquire(timeout)` tanpa syarat selama tugas belum selesai dan batas 6 jam belum lewat: pada lock yang masih terpegang itu memperbarui kedaluwarsanya, pada lock yang sudah lepas itu memasangnya kembali.

## [Belum rilis] — Audit 545: ekspor config tak lagi meloloskan nama backup terakhir dan penanda storage-rendah
- Residual #265: daftar tak-diekspor menutup kunci dinamis satu per satu, tetapi dua kunci statis perangkat-spesifik tertinggal: `tg_last_name` (nama berkas backup terakhir perangkat ini — keadaan lokal yang salah bila diterapkan di perangkat lain dan membocorkan nama berkas lokal ke penerima ekspor) dan `tg_low_storage_notified` (penanda sudah-diperingatkan; ikut ter-restore membuat peringatan storage rendah tak pernah muncul di perangkat baru).
- Keduanya kini masuk `TAK_DIEKSPOR_KEYS` lewat konstantanya (`KEY_TG_LAST_NAME`, `KEY_TG_LOW_STORAGE`), dan uji `secretTakIkutBackup` diperluas mengunci keduanya.

## [Belum rilis] — Audit 544: restore Settings tak lagi melapor gagal total bila hanya pemasangan TLS yang gagal
- Residual #261 pada jalur kembarannya: di `SettingsActivity`, kegagalan `pasangTlsRestore` (sesudah DB baru SAH terpasang atomik) memunculkan toast "Restore failed" dan `return` — prefs dari backup tak pernah diterapkan, sinkronisasi TLS internal terlewat, dan pengguna menyangka seluruh restore gagal lalu berpotensi mengulang restore. Jalur Telegram untuk keadaan yang sama sudah memperlakukannya sebagai peringatan sejak Audit 506.
- Kegagalan itu kini menjadi peringatan yang melanjutkan alur (semantik sama persis dengan jalur Telegram): TLS lama dikembalikan, prefs tetap diterapkan, dan toast penutup memakai string baru `restore_selesai_tls_gagal` (Inggris + Indonesia) yang menyatakan DB berhasil direstore tetapi sertifikat backup gagal dipasang — bukan lagi `restore_gagal`.

## [Belum rilis] — Audit 543: penanda zip gagal kini di-fsync dan bersidik SHA-256
- Residual #255: `catatZipGagal` menutup FileOutputStream tanpa `getFD().sync()`, sehingga penanda pencegah unzip-ulang zip rusak bisa hilang saat mati listrik (bertahan hanya di cache OS) dan percobaan berikutnya meng-unzip ulang zip 35 MB yang sama sampai gagal lagi. Sidiknya juga lemah: `length + ':' + lastModified` bisa cocok untuk zip berbeda yang panjang dan mtime-nya sama.
- `catatZipGagal` kini flush + fsync sebelum tutup. `Updater.sidikBerkas` kini SHA-256 heksadesimal atas isi (pola yang sama dengan `SettingsActivity.sidikBerkas`), gagal-baca menghasilkan sidik kosong yang aman (penanda tak cocok → ekstrak dicoba lagi, bukan dilewati). Uji diperluas: isi berbeda dengan panjang DAN mtime dipaksa sama kini terbukti tak cocok lagi.

## [Belum rilis] — Audit 542: pemulihan pertukaran TLS memeriksa hasil rename dan menggulung balik pasangan setengah terpasang
- `pulihkanTukarTersisa` mengabaikan hasil beberapa `renameTo`: pada cabang pemasangan pasangan `.baru`, cert bisa terpasang sementara rename key gagal dibiarkan — menyisakan keadaan setengah yang cabang berikutnya bisa lengkapi dengan key `.cad` lama (pasangan campuran), tanpa satu pun langkah menyadarinya.
- Cabang itu kini menggulung balik cert ke nama `.baru` bila rename key gagal, sehingga keadaan kembali persis seperti sebelum pemulihan dan bisa dicoba lagi utuh. Cabang key juga hanya jatuh ke `.cad` bila rename `.baru` gagal DAN `.cad` terbukti cocok dengan cert yang terpasang. Direktori di-fsync di akhir pemulihan (pola Audit 540).

## [Belum rilis] — Audit 541: fallback salinCaKeStorage tak lagi menghapus tujuan sebelum pengganti terpasang
- Residual #258: jalur utama `salinCaKeStorage` sudah atomik (tulis .tmp + fsync + rename), tetapi fallback-nya menghapus berkas tujuan dulu baru rename kedua — jendela crash di antaranya menghilangkan salinan CA yang sebelumnya baik. Nama berkas sementara juga tetap (`tujuan + ".tmp"`), jadi dua ekspor bersamaan bisa saling menulis berkas yang sama.
- Nama sementara kini unik per panggilan (akhiran nanoTime heksadesimal). Fallback tak lagi delete-dulu: tujuan lama digeser ke nama `.lama-<unik>`, pengganti dipasang, dan hanya sesudah itu cadangan lama dihapus (kegagalan memasang memulihkan cadangan ke nama tujuan). Direktori tujuan di-fsync sesudah pemasangan, seperti pola Audit 540.

## [Belum rilis] — Audit 540: pertukaran pasangan TLS dan version.txt kini men-fsync direktori sesudah rename
- `tukarPasanganAtomik` dan `writeVersion` men-fsync isi berkas sebelum rename, tetapi tak pernah men-fsync direktori `tls/` sesudahnya. Di Android/data=ext4/f2fs, rename yang belum ter-fsync direktorinya bisa terbalik oleh mati listrik tepat sesudah pertukaran: pasangan cert/key campuran atau `version.txt` basi kembali, dan jalur pemulihan `.cad` sudah terlanjur dihapus pada keberhasilan semu itu.
- Helper `TlsCert.fsyncDirektori` (pola `android.system.Os.open/fsync` yang sama dengan yang sudah dipakai pemulihan DB di ServerService, best-effort dalam try/catch) kini dipanggil di akhir keberhasilan `tukarPasanganAtomik` dan sesudah rename `writeVersion` (termasuk jalur fallback-nya).

## [Belum rilis] — Audit 539: penghitung start tertunda kini per-episode, bukan warisan antar-episode
- `startTertundaData` statik hanya di-reset saat kunci data berhasil didapat atau batas 60 percobaan tercapai. Bila pengguna menekan Stop di tengah penundaan (restore/reset masih berjalan), hitungan berhenti di nilai tengah dan tak dibersihkan — episode tertunda berikutnya mewarisi sisa itu, sehingga jatah ±5 menitnya menyusut dan start bisa menyerah ("tekan Start lagi") jauh lebih awal pada restore panjang berikutnya.
- Penghitung kini di-reset di ketiga titik batas episode: handler ACTION_START dan jalur sticky intent-null (episode baru dimulai → jatah penuh) serta handler ACTION_STOP (episode diakhiri paksa → sisa hitungan dibuang). Jalur kelanjutan episode yang sama (restartTunda dan start susulan) tidak menyentuhnya.

## [Belum rilis] — Audit 538: catatan log dari receiver kini sampai ke berkas log dan langsung di-flush
- Dua jalur log tak simetris: `appendLog` (milik service) menulis buffer memori + buffer berkas (flush pada 16 KB), tetapi `catatLog` — jalur yang dipakai BootReceiver, AlarmReceiver, dan pembersih proses yatim — hanya menulis buffer memori, sehingga catatan boot/alarm (termasuk penolakan throttle boot) tak pernah ada di `vaultwarden.log` sama sekali. Dan flush berkas yang bertumpu pada ambang 16 KB + onDestroy membuat ekor log hilang bila STB mati listrik/dibunuh paksa sebelum ambang tercapai.
- Penulisan berkas disatukan ke helper `tulisKeBerkasLog` yang dipakai kedua jalur; `catatLog` kini juga mengantre ke berkas dan langsung mem-flush (kejadian boot/alarm jarang, biaya tulisnya kecil) — catatan itu kini persisten begitu dibuat, sementara aliran log server yang deras tetap di-batch pada ambang 16 KB seperti sebelumnya.

## [Belum rilis] — Audit 537: notifikasi dan localUrl menampilkan port yang benar-benar didengarkan server
- Teks notifikasi (`bangunNotifikasi` lewat `currentPort()`) dan `localUrl()` membaca port dari prefs, berbeda dari `pingRinci`/`portLoopback` yang mendahulukan `runningPort`. Server yang sudah berjalan tetap di port lama sampai restart, tetapi notifikasi "Server aktif di port ..." dan URL bagikan langsung berubah mengikuti suntingan Settings — pengguna mengira server pindah port atau mati.
- Jalur tampil kini mendahulukan `runningPort` bila server sudah terikat (helper `portTampil()` untuk notifikasi; `localUrl` meniru pilihan `pingRinci` persis), dan jatuh ke port prefs hanya bila belum ada port berjalan. `currentPort()` sendiri SENGAJA tidak diubah: ia juga mengisi `sesi.port` saat persiapan start, yang memang harus port target dari prefs.

## [Belum rilis] — Audit 536: onDestroy kini membatalkan episode start yang sedang disiapkan
- `onDestroy` hanya membatalkan healthTick/restartTunda, membunuh proses yang sudah lahir, dan melepas wakelock — tidak menyentuh penanda episode start, padahal jalur ACTION_STOP lengkap (`batalStart`, `nomorStop`, penanda susulan). Pekerja `startServerAsync` berjalan di pool proses-aplikasi, bukan milik instance: service yang dihancurkan di tengah persiapan (unduh binary pertama bisa bermenit-menit) membiarkan pekerja itu tetap melahirkan proses vaultwarden sesudah service hancur — proses yatim tanpa foreground/health.
- `onDestroy` kini memasang pembatalan yang sama seperti ACTION_STOP (`healthActive=false`, `batalStart=true`, `nomorStop` naik, penanda start susulan dibersihkan) sebelum membunuh proses. Niat jalan tersimpan SENGAJA tidak diubah — penghancuran oleh sistem bukan perintah berhenti dari pengguna, dan kedua jalur start berikutnya (ACTION_START maupun sticky intent-null) sudah mengatur ulang `batalStart=false` di awalnya.

## [Belum rilis] — Audit 535: auto-merge Dependabot kini memverifikasi cek wajibnya sendiri dan berjalan serial per PR
- Auto-merge PR Dependabot non-Gradle hanya dijaga satu konteks proteksi branch (`Cek patch yang berubah`, terverifikasi lewat API): bila konteks itu suatu saat ter-rename/terhapus dari proteksi, atau job verifikasi ter-skip, PR bisa menyatu tanpa gerbang hijau apa pun — workflow sendiri tak pernah memastikan cek itu benar-benar ada. Workflow juga tak punya `concurrency`, sehingga event opened/synchronize/labeled yang beruntun menjalankan instance yang balapan mengaktifkan auto-merge yang sama.
- Job kini serial per PR (`concurrency: auto-merge-pr-<nomor>`, `cancel-in-progress: false`), dan sebelum mengaktifkan auto-merge ada langkah penegas: menunggu sampai cek `Cek patch yang berubah` benar-benar muncul di daftar cek PR ini (8 × 15 detik untuk memberi waktu workflow verifikasi mulai) — bila tak muncul, langkah gagal dan auto-merge dibatalkan dengan pesan eksplisit, bukan menyatu tanpa gerbang. Berkas lolos parse YAML; struktur langkah terverifikasi dari hasil parse.

## [Belum rilis] — Audit 534: Gitleaks kini ikut berjalan untuk perubahan dokumen dan shim
- `paths-ignore` di `gitleaks.yml` memuat `'*.md'` dan `'shim/**'` (warisan penyaring dokumen Audit 517 yang tepat untuk build/CodeQL, tetapi salah untuk pemindai rahasia): push yang hanya mengubah README/CHANGELOG/AGENTS.md tidak memicu pemindaian rahasia sama sekali, dan perubahan shim hanya diperiksa pola rahasia sempit cek-cepat, bukan kamus Gitleaks. Token yang ter-tempel ke dokumentasi (template issue repo sendiri meminta lampiran log) bisa masuk main tanpa satu pun pemindai berjalan.
- Kedua entri itu dihapus dari `paths-ignore` push dan pull_request di `gitleaks.yml` (tersisa `.gitignore` dan workflow binary berat); workflow build dan CodeQL tidak diubah — penyaring dokumennya tetap hemat seperti keputusan Audit 517. Berkas lolos parse YAML dan daftar trigger terverifikasi dari hasil parse.

## [Belum rilis] — Audit 533: cek-cepat kini gagal tertutup bila git tak bisa dipakai
- Seluruh cek berbasis git di `cek-cepat.sh` (daftar berkas terlarang, sapuan biner, `git grep` pola rahasia) menelan kegagalan git dengan `|| true`/daftar kosong, sehingga di ekspor sumber tanpa `.git` (atau repo rusak/safe.directory) skrip keluar 0 walau tak satu pun cek itu berjalan — dibuktikan sesi audit: ekspor + token `ghp_` + biner ELF tetap "LOLOS".
- Skrip kini memeriksa kesehatan git di awal (`git rev-parse --is-inside-work-tree` + `git ls-files`); bila gagal, ia mencatat GAGAL eksplisit bahwa hasil tak bisa dipercaya dan keluar 1, sementara cek non-git tetap berjalan seperti biasa. Terbukti fixture dua arah: ekspor `git archive` tanpa `.git` berisi token + biner → exit 1 dengan pesan itu; pohon normal → tetap LOLOS.

## [Belum rilis] — Audit 532: pengecualian format pasif gerbang kini terikat ekstensi dan bebas bungkus biner
- Sisa #244: kedua gerbang mengecualikan berkas dari larangan biner hanya berdasar 12 byte header pertama (PNG/JPEG/GIF/WebP/PDF/ICO) tanpa mengikat ekstensi dan tanpa melihat isi — biner poliglot (header `%PDF-1.7` + ELF utuh) lolos sebagai "dokumen pasif" di `verifikasi-patch.py` maupun `cek-cepat.sh`, melemahkan pagar biner diam-diam (aturan repo melarang biner tak dikenal masuk repo).
- `jenis_pasif` kini hanya mengakui format pasif bila ekstensi berkas cocok dengan formatnya (`.png`, `.jpg`/`.jpeg`, `.gif`, `.webp`, `.pdf`, `.ico`) DAN seluruh isi bebas tanda bungkus executable (ELF, ZIP/PK, DEX, Mach-O); `cek-cepat.sh` menerapkan ikatan yang sama (pemindaian hexdump untuk tanda yang sama). Terbukti fixture dua arah di salinan buang: poliglot `.bin` dan `.pdf` → kedua gerbang GAGAL; header acak → GAGAL; PNG sah bernama `.png` → kedua gerbang LOLOS (158 berkas).

## [Belum rilis] — Audit 531: penjaga ABI di gerbang tak lagi kalah oleh komentar umpan
- Lubang sejenis #229 yang belum tertutup: `himpun_abi` mengurai teks mentah `app/build.gradle.kts`, sehingga baris `abiFilters` asli yang dinonaktifkan dengan `//` tetap terhitung terpasang — AGP tanpa `abiFilters` akan mengemas semua ABI, melanggar aturan armeabi-v7a saja tanpa gerbang bersuara. Pengupas komentar yang sudah ada (`kupas_java`) tak bisa dipakai di sini karena ia juga membuang isi string, padahal nama ABI justru hidup di dalam string.
- Pengupas baru `kupas_komentar_saja` membuang komentar `//` dan `/* */` sambil menyalin literal string/char/raw-string apa adanya (sadar escape dan `//` di dalam URL string); `himpun_abi` dan cek keberadaan `abiFilters` kini bekerja di atas teks bersih itu. Terbukti unit langsung (bersih → {armeabi-v7a}; dikomentari/blok → kosong; string URL berisi `//` tetap aman) dan fixture dua arah di salinan buang: abiFilters dikomentari → GAGAL, pohon bersih → LOLOS.

## [Belum rilis] — Audit 530: paritas resource di gerbang tak lagi kalah oleh komentar XML
- `cek_paritas_resource` membaca nama warna, quantity plurals, dan ID layout dari teks mentah (`baca()`), sehingga definisi yang sudah dihapus tetapi disisakan sebagai komentar `<!-- ... -->` tetap terhitung ada: warna malam yang hilang nyata lolos paritas dan Android jatuh diam-diam ke warna siang di mode gelap.
- Ekstraksi ketiganya kini lewat `teks_xml_bersih` (komentar XML dibuang lebih dulu). Terbukti fixture dua arah di salinan buang dengan mutasi ter-assert (parse XML membuktikan 0 definisi `primary` nyata): warna malam dikomentari → GAGAL "colors.xml siang berlebih vs night: primary"; pohon bersih → LOLOS 157 berkas.

## [Belum rilis] — Audit 529: isian latar fokus D-pad tak lagi menenggelamkan kontras teks di mode siang
- `item_focus_bg` memberi isian oranye transparan pada keadaan fokus (`#66FF6F00`, alfa 0,40) dan tekan (`#33F57C00`, alfa 0,20). Di atas latar terang, campuran itu menjatuhkan kontras teks yang justru sedang dipilih: `netInfo` (text_secondary #666D7B, 11sp) di atas campuran fokus di info_bg hanya 3,11:1 dan saat tekan 3,84:1 (hitung WCAG) — di bawah ambang 4,5:1, padahal pasangan dasarnya sendiri lolos 4,58:1. Perhitungan campuran juga menunjukkan alfa oranye sekecil apa pun tetap di bawah ambang, karena pasangan dasar memang pas-pasan — jadi mengecilkan alfa bukan perbaikan.
- Isian kedua keadaan kini transparan penuh; penanda fokus/tekan adalah garis tepi aksen 3dp yang sudah ada di selector yang sama (tetap jelas terlihat di TV). Kontras teks pada keadaan fokus/tekan kembali persis ke kontras pasangan dasar yang sudah lolos (netInfo 4,58:1; pemakai lain lebih tinggi), dan mode malam tidak berubah perilakunya selain isian yang hilang.

## [Belum rilis] — Audit 528: retry hapus pesan ber-PIN mengklasifikasi galat dan berbatas waktu total
- Residual #239 × #260: `hapusPesanPerintah` menandai SEMUA eksepsi sebagai "sesaat" (`catch (Exception) { sesaat = true; }`), sehingga galat permanen — penolakan redirect Telegram dan kegagalan TLS (mis. jam STB salah) yang tak akan berubah dalam 1–2 detik — ikut dicoba 3×45 detik. Beberapa pesan ber-PIN dalam satu poll (maks 10 update) dengan mudah melewati WakeLock poll 10 menit, dan poll membeku di tengah dengan offset belum tersimpan.
- Klasifikasi baru `galatHapusSesaat`: hanya galat jaringan yang dicoba lagi (timeout, connect, DNS, socket, IOException umum); `SSLException`, penolakan redirect, eksepsi non-IO, dan null berhenti pada percobaan pertama. Selain itu satu pemanggilan kini berbatas waktu total 60 detik (`BATAS_HAPUS_PESAN_MS`) sebelum menyerah — percobaan pertama selalu berjalan penuh, percobaan berikutnya tak dimulai sesudah batas lewat. Uji JVM baru mengunci kedelapan kasus klasifikasi.

## [Belum rilis] — Audit 527: zip unduhan /restore via bot kini ikut disapu dari cache
- Pola #238 pada nama berkas lain: zip hasil unduhan `TgBot.doRestore` (`vwtg-restore-bot-*.zip` di cacheDir) hanya dihapus di blok `finally` jalur itu. Bila proses mati di antara unduhan selesai dan ekstrak selesai (jendela termasuk penghentian server + verifikasi DB), salinan backup — untuk backup tanpa sandi: zip PLAIN berisi `db.sqlite3` + `app-config.json` + sertifikat — tertinggal tanpa batas waktu bagi pemilik yang hanya memakai bot, karena `sapuZipPlainCacheBasi` hanya mengenal awalan `backup-telegram-` dan satu-satunya penyapu pola `vwtg-restore-` ada di UI Settings.
- Awalan `vwtg-restore-bot-` kini menjadi calon sapuan `sapuZipPlainCacheBasi` (yang berjalan di awal setiap backup), dengan syarat umur yang sama (`layakSapuBasi`). Nama `vwtg-restore-*` milik alur Settings SENGAJA tidak ikut — penyapunya sendiri di Settings memakai TTL 1 jam, dan uji yang diperluas mengunci pembedaan ini (bot basi terhapus, bot segar + Settings basi-di-bawah-TTL-nya bertahan).

## [Belum rilis] — Audit 526: restore kini memvalidasi isi dan kecocokan pasangan TLS, bukan hanya nama entri
- Residual #262: `tlsRestoreLengkap` hanya memeriksa keempat nama entri TLS ada di daftar zip. Entri bernama benar tetapi kosong (0 byte — berkas staging tetap tercipta saat ekstrak) atau cert/key dari pasangan berbeda tetap dinyatakan "lengkap" dan menimpa TLS hidup lewat `pasangTlsRestore`; restore dilaporkan berhasil sementara server gagal melayani TLS.
- Validator baru `TgBackup.tlsRestoreSiapPasang` dipakai kedua jalur restore (Telegram dan Settings): selain kelengkapan nama, setiap berkas staging wajib ada dan berukuran > 0, pasangan CA (`ca.pem`/`ca-key.pem`) dan pasangan leaf (`cert.pem`/`key.pem`) wajib cocok menurut `TlsCert.pasanganCertKeyCocok`, dan leaf wajib ditandatangani CA dari backup yang sama (`TlsCert.ditandatanganiCa`). Gagal salah satu → TLS perangkat dipertahankan dan pengguna diperingatkan (teks peringatan kedua jalur diselaraskan), persis perilaku jalur tak-lengkap sebelumnya. Uji JVM baru mengunci penolakan untuk staging hilang/kosong/isi sampah/daftar tak lengkap.

## [Belum rilis] — Audit 525: gerbang kesehatan web-vault di ServerService memakai cek payload yang sama dengan Updater
- Residu #235: perbaikan Audit 479 (`Updater.wvPayloadAda` — `index.html` wajib berkas dan berukuran > 0) hanya diterapkan di Updater. Tiga titik di ServerService masih memakai `exists()` polos: pengaktifan `WEB_VAULT_ENABLED` saat start, penilaian `aktifSehat` pada pemulihan `web-vault.bak`, dan `extractWebVault()`. `index.html` 0 byte (sisa ekstrak terpotong) dianggap sehat: server menyalakan web-vault kosong, dan cadangan `.bak` yang baik dibuang karena yang aktif dianggap sehat.
- Ketiga titik kini memakai `Updater.wvPayloadAda(...)` yang sama — satu definisi "payload sehat" untuk seluruh app; jalur `.bak` akan memulihkan cadangan bila indeks aktif kosong, dan start tak lagi mengaktifkan web-vault tanpa isi.

## [Belum rilis] — Audit 524: cache cipher PabrikTls12 kini divalidasi per soket seperti jalur protokol
- Sisa #254: jalur protokol di `PabrikTls12.nyalakan` sudah divalidasi per soket (irisan `protokolCadangan` bila cache tak sepenuhnya didukung), tetapi jalur cipher memakai `cipherCache` global mentah-mentah. Soket berikutnya dengan dukungan cipher berbeda membuat `setEnabledCipherSuites` melempar, eksepsi hanya dicatat, dan soket berjalan dengan cipher bawaan tanpa penyaringan `saringCipher` — filter cipher kuat hilang diam-diam untuk koneksi itu.
- Daftar dukungan cipher soket kini selalu dibaca; cache yang tak sepenuhnya didukung dipotong ke irisan yang didukung soket ini (`cipherCadangan` baru, semantik sama dengan `protokolCadangan`), dan bila pemasangan tetap melempar, dicoba sekali lagi dengan irisan itu sebelum menyerah ke bawaan soket — persis pola jalur protokol. Uji JVM baru mengunci semantik irisan (urutan mengikuti pilihan, kosong untuk asing/null) dengan nilai harapan terverifikasi porting Python 1:1.

## [Belum rilis] — Audit 523: restart karena IP berubah tak lagi hilang permanen bila worker melewatkannya
- `peringatkanIpBerubah` menandai `ipRestartDiminta` begitu intent restart terkirim (`restart()` = true), dan syarat `restartIpBolehDiminta` menolak meminta lagi untuk IP yang sama. Bila worker `ACTION_RESTART` ternyata melewatkan restart itu (persiapan start lain sedang berjalan, `restartDilewati`), penanda tak pernah dibersihkan: DOMAIN server menunjuk IP lama selamanya selama proses hidup, walau server sehat. Jalur antrean-penuh di handler yang sama sudah membersihkan penanda, jalur dilewati tidak.
- Worker kini membersihkan `ipRestartDiminta` pada cabang `restartDilewati` (kedua sub-kasus), mengikuti pola jalur antrean-penuh: pemantau kesehatan akan mengakumulasi ulang kandidat IP yang stabil dan meminta restart lagi sesudah start yang berjalan selesai. Log cabang start-berjalan menyebut pembersihan ini agar jejaknya jelas.

## [Belum rilis] — Audit 522: shim getrandom kini menghormati GRND_NONBLOCK dan GRND_INSECURE
- Sisa #249: `getrandom_bendera` memvalidasi flags tak dikenal (Audit 493) tetapi tak pernah membaca bit `GRND_NONBLOCK`/`GRND_INSECURE` — panggilan yang menurut kontrak Linux wajib kembali `-1/EAGAIN` bila sumber akan memblokir justru dilayani `read()` yang memblokir di `/dev/random`, sambil memegang mutex global shim, sehingga satu panggilan "non-blocking" di STB kernel lama berentropi lambat bisa membekukan semua panggilan getrandom lain di proses vaultwarden.
- Panggilan ber-flag `GRND_NONBLOCK` atau `GRND_INSECURE` kini dilayani jalur terpisah `isi_dari_nonblok`: fd sementara `O_NONBLOCK` per panggilan (tanpa cache fd bersama, tanpa mutex), `EAGAIN` diteruskan apa adanya (atau jumlah byte parsial yang sudah terbaca), dan `GRND_INSECURE` mengambil dari `/dev/urandom` walau disertai `GRND_RANDOM`, sesuai semantik kernel. Jalur memblokir lama tak berubah untuk flags 0/`GRND_RANDOM` murni. Uji interposisi host menambah tiga kasus (NONBLOCK penuh, RANDOM|NONBLOCK penuh-atau-EAGAIN, INSECURE penuh); bukti lokal hanya `gcc -fsyntax-only -Wall` bersih — uji interposisi penuh milik CI build-binary.

## [Belum rilis] — Audit 521: throttle boot memakai nomor boot sistem dan stempel tak lagi bergeser saat ditahan
- Sisa #228: dua boot berbeda yang berdekatan (restart manual cepat sesudah boot) masih bisa tertelan throttle 60 detik karena heuristik elapsed/wall tak bisa membedakannya dari broadcast duplikat pada boot yang sama — auto-start server beserta penjadwalan backup dan bot untuk boot itu hilang. Selain itu stempel throttle ditimpa tanpa syarat walau broadcast ditahan, sehingga jendelanya bergeser terus (pola yang di jalur alarm sudah diperbaiki Audit 475).
- `BootReceiver` kini membaca nomor boot sistem (`Settings.Global.BOOT_COUNT`, API 24+; di bawah itu 0 dan heuristik lama tetap dipakai): nomor berbeda = boot berbeda dan tak pernah ditahan, nomor sama = throttle hanya berdasar selisih elapsed. Nomor boot disimpan bersama stempel lama (kunci prefs baru `boot_throttle_count`, masuk daftar tak-diekspor), dan stempel kini hanya ditulis bila broadcast diterima — broadcast yang ditahan tak lagi menggeser jendela. Uji baru membuktikan kasus issue persis (count berbeda dilepas walau wall dekat; count sama tetap ditahan) dengan nilai harapan yang diverifikasi porting Python 1:1.

## [Belum rilis] — Warning compileSdk dimatikan lewat daftar disable lint (GradleDependency)
- Sesudah pembersihan warning sebelumnya, anotasi build tersisa 2 warning `GradleDependency` ("compileSdkVersion 34 tersedia 36", sekali per varian) + 1 notice "Gradle out of date" dari langkah pasca `setup-gradle` GitHub (bukan dari lint/kode repo).
- `GradleDependency` ditambahkan ke daftar `disable` lint di `app/build.gradle.kts`: compileSdk 34 adalah pin yang disengaja (CI memasang platform 34, targetSdk 28 by design) dan kesegaran versi dependensi/wrapper sudah dipantau Dependabot, jadi pemberitahuan lint-nya hanya derau berulang. Mengikuti preseden daftar yang sama yang sudah mematikan `OldTargetApi`/`ExpiredTargetSdkVersion` untuk pin SDK yang sama.
- Notice "Gradle out of date" TIDAK bisa dimatikan dari repo: ia diterbitkan action `gradle/actions/setup-gradle` sendiri sesudah build, membandingkan Gradle 8.14.6 dengan seri 9 — hilang hanya bila pindah ke Gradle 9 (tidak kompatibel AGP 8.x) atau membuang action itu (kehilangan caching-nya). Dibiarkan sebagai catatan informational.

## [Belum rilis] — Pembersihan warning lint build (ApplySharedPref + wrapper Gradle)
- Build pasca-merge AGP 8.13.2 menyisakan 4 warning lint unik: 2× `ApplySharedPref` di `AlarmReceiver.onReceive` dan 2 pemberitahuan versi (Gradle 8.14.3 → 8.14.6 tersedia; compileSdk 34 → 36 tersedia).
- Dua `commit()` di `onReceive` SENGAJA sinkron: keduanya menulis tanda `KEY_BACKUP_TERTUNDA` pada jalur gugur alarm/boot, di mana proses bisa mati sebelum tulis asinkron `apply()` sempat turun ke disk — menggantinya akan membuka peluang tanda backup tertunda hilang diam-diam. Metode diberi `@SuppressLint("ApplySharedPref")`, mengikuti pola dua metode lain di berkas yang sama yang sudah lebih dulu ditandai persis untuk alasan ini.
- Wrapper Gradle dinaikkan 8.14.3 → 8.14.6 (patch terakhir seri 8.14; checksum SHA-256 resmi dari `services.gradle.org`) sehingga dua pemberitahuan versi Gradle hilang. Pemberitahuan compileSdk 34 → 36 DIBIARKAN: compileSdk 34 adalah pilihan repo saat ini (CI memasang platform 34; targetSdk tetap 28 by design) — menaikkannya keputusan SDK tersendiri, bukan pembersihan warning.

## [Belum rilis] — Build merah (jalur PR): import StandardCharsets hilang di UpdaterTest
- Jalur PR workflow build menjalankan kompilasi uji unit (`compileDebugUnitTestJavaWithJavac`), sedangkan jalur push tidak — jadi kesalahan ini bersembunyi selama hanya build push yang terlihat: dua uji zip di `UpdaterTest` memakai `StandardCharsets.UTF_8` tanpa `import java.nio.charset.StandardCharsets`, simbol tidak ditemukan dan kompilasi uji gagal. Terungkap oleh gerbang PR #225 sesudah perbaikan kompilasi `HttpsCompat`.
- Import yang hilang ditambahkan; tidak ada perubahan perilaku uji.

## [Belum rilis] — Build merah: pernyataan tak terjangkau di socketFactory HttpsCompat
- Build `main` gagal compile sejak bentuk throttle bangun trust masuk: di `HttpsCompat.socketFactory`, semua jalur di dalam blok `synchronized` berakhir `return`/`throw` (cek cache, lempar `JedaBangunTrust`, `return bangunFactory(ctx)`, atau rethrow dari catch), sehingga `return cached;` sesudah blok menjadi pernyataan tak terjangkau — kesalahan keras javac (`unreachable statement`) yang menggagalkan `compileDebugJavaWithJavac` di AGP lama maupun baru. Ditemukan saat mengerjakan PR Dependabot AGP #225 (gerbang PR menangkapnya; build push `main` pun sudah merah).
- Baris mati itu dihapus; perilaku tidak berubah (kode tak terjangkau memang tidak pernah berjalan — `bangunFactory` sendiri yang mengisi `cached` dan mengembalikan hasilnya).

## [Belum rilis] — Audit 520: uji capDepanStabil diisolasi dari peta statis bersama + membersihkan temp-nya
- Dua uji `capDepanStabil` berbagi peta statis `CAP_DEPAN_TERLIHAT` (batas 128 entri) tanpa reset: hasil uji bergantung urutan jalan (JUnit tidak menjamin urutan), dan uji pertama bisa mengisi peta sehingga jalur sapu/clear uji kedua terpicu lebih awal dari yang dimaksudkan. Kedua uji juga meninggalkan direktori temp beserta berkasnya.
- `TgBackup.resetCapDepanUntukUji()` ditambahkan dan dipanggil di awal kedua uji; keduanya kini menghapus berkas + direktori temp-nya di blok `finally`, mengikuti pola uji tetangga.

## [Belum rilis] — Audit 519: labeler kini mencakup berkas build akar dan proguard
- Label `app`/`ci` di `.github/labeler.yml` tidak mencakup berkas build akar — PR yang hanya mengubah `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, atau `app/proguard-rules.pro` tidak mendapat label apa pun, padahal berkas-berkas itu mengubah build sekuat `app/build.gradle.kts` yang sudah terlabel.
- Keempat pola ditambahkan ke label `app` (format v5 `changed-files` hasil Audit 487 dipertahankan; berkas lolos parse YAML dan keempat berkasnya terverifikasi ada di repo).

## [Belum rilis] — Audit 518: cek sinkron README membandingkan struktur judul, bukan hanya jumlah ##
- Pemeriksaan sinkron tiga README di `scripts/cek-cepat.sh` hanya menghitung judul `^## ` — subjudul `###` yang ditambah/diubah di satu bahasa dan perubahan isi/tingkat judul lain lolos tanpa jejak, padahal AGENTS.md mewajibkan ketiga README sinkron.
- Pemeriksaan kini membandingkan urutan tingkat seluruh judul (`#`–`######`) antar ketiga berkas (judul memang diterjemahkan, jadi yang dibandingkan strukturnya). Terbukti fixture dua arah di salinan terpisah: tambah `###` di satu README → GAGAL, tambah `##` → GAGAL, pohon bersih → LOLOS.

## [Belum rilis] — Audit 517: CodeQL & Gitleaks kini menyaring dokumen dan punya concurrency
- `codeql.yml` (timeout 60 menit) dan `gitleaks.yml` berjalan pada SETIAP push/PR termasuk commit dokumen murni, dan tanpa `concurrency` — selama maraton berisi puluhan push beruntun, run analisis menumpuk dan membakar menit Actions untuk perubahan yang tidak menyentuh kode.
- Keduanya kini memakai `paths-ignore` dokumen yang sama dengan `build-apk.yml` (`*.md`, `.gitignore`, `shim/**`, workflow binary) pada `push` dan `pull_request`, plus grup `concurrency` per-ref (`codeql-…`/`gitleaks-…`, `cancel-in-progress: false` mengikuti pola build). Jadwal mingguan CodeQL tidak disaring.

## [Belum rilis] — Audit 516: workflow automasi baru dipin ke SHA penuh seperti workflow build
- `codeql.yml`, `gitleaks.yml`, `stale.yml`, dan `labeler.yml` (paket automasi sesi #105) memakai tag mengambang (`@v7`, `@v4`, `@v3`, `@v11`) sementara `build-apk.yml`/`build-binary.yml` memin semua action ke SHA commit berkomentar versi — tag mengambang bisa digeser pemilik repo action (atau akun yang dibajak) dan mengubah kode yang berjalan dengan token repo ini tanpa satu pun perubahan terlihat.
- Sepuluh rujukan `uses:` kini dipin ke SHA commit persis dari tag resminya masing-masing (diresolusi lewat API GitHub, tag anotasi CodeQL didereferensikan ke commit-nya), dengan komentar versi mengikuti pola workflow lama; keempat berkas lolos parse YAML.

## [Belum rilis] — Audit 515: dialog Tentang membaca minSdk dinamis, bukan literal 21
- Kaki dialog Tentang menulis minSdk sebagai literal `21` sementara targetSdk dan versi build di sebelahnya dibaca dinamis — bila `minSdk` di `app/build.gradle.kts` suatu hari berubah, teks Tentang tetap mengklaim 21 dan menyesatkan laporan bug/dukungan.
- minSdk kini dibaca dari `ApplicationInfo.minSdkVersion` (bidang API 24+; di perangkat lebih lama dipakai nilai cadangan karena aplikasi yang terpasang di sana memang hanya bisa berarti minSdk ≤ API perangkat), dalam satu panggilan `getApplicationInfo` yang sama dengan targetSdk.

## [Belum rilis] — Audit 514: entri DialogAman dilepas saat dialog tertutup, bukan menunggu dialog berikutnya
- Daftar statis `DialogAman` menahan referensi kuat ke dialog (dan lewat dialog, konteks Activity-nya) sesudah dialog ditutup; entri baru dibersihkan hanya saat dialog lain tampil atau `tutup(activity)` dipanggil — sesi Settings panjang yang membuka banyak dialog menumpuk semuanya beserta konteksnya.
- Sesudah `show()`, kini dipasang pendengar lepas-jendela pada decor view: saat jendela dialog terlepas (dismiss/cancel/aktivitas selesai), entrinya menghapus diri dari daftar. Slot `OnDismissListener` sengaja tidak dipakai karena dua dialog Settings memasang pendengarnya sendiri sebelum `tampil`.

## [Belum rilis] — Audit 513: potongEkorBaris tak lagi melanggar batasnya sendiri + hitungan baris jalur terpotong lurus
- `potongEkorBaris` punya cabang khusus: bila newline PERTAMA seluruh teks jatuh di dalam jendela ekor, fungsi mengembalikan seluruh teks dari awal (terbukti dengan porting Python: teks 156 karakter lolos untuk batas 100) — batas `BATAS_TAMPIL_LOG` dilanggar persis pada log berbaris pertama raksasa. Cabang itu dihapus; ekor selalu dipotong pada batas baris pertama sesudah awal jendela atau potongan keras.
- Hitungan baris hanya menghitung `\n`, sedangkan jalur terpotong membuang newline penutup — jumlah "baris" yang ditampilkan kurang 1 dari yang terlihat. `hitungBaris` (dipakai juga jalur delta Audit 512 dan menggantikan loop hitung inline) kini menghitung baris terakhir tanpa newline penutup sebagai satu baris. Satu ekspektasi uji lama yang mengunci pelanggaran batas (`"aa\nbb\n"` maks 4 → `"aa\nbb"`) diperbarui ke `"bb"` di commit yang sama, plus uji regresi baris-pertama-raksasa dan uji `hitungBaris`.

## [Belum rilis] — Audit 512: LogActivity menempel delta log, bukan membangun ulang seluruh teks tiap baris baru
- Setiap baris log baru memicu pembangunan ulang total di layar log penuh: substring ekor ±100 rb karakter + hitung baris + samarkan seluruh teks + sorot seluruh teks + `setText` penuh, tiap tick 1,5 detik selama log mengalir — pola yang di layar utama sudah lama diganti tempel delta (`MainActivity.refreshHomeLog`), tetapi di sini tertinggal.
- Bila tidak ada kata pencarian dan teks tampil sebelumnya berakhir baris utuh serta teks baru adalah lanjutannya, kini hanya delta yang disamarkan, disorot, dan ditempel (`append`), dengan hitungan baris diakumulasi (helper murni `hitungBaris`). Keadaan lain (pencarian aktif, buffer menyusut/bergeser, baris terakhir belum utuh) tetap jalur bangun-ulang penuh seperti sebelumnya.

## [Belum rilis] — Audit 511: kontras hint siang saat input fokus dinaikkan ke 4,56:1
- `text_hint` siang `#68707C` tampil 4,41:1 (hitung WCAG) saat input berfokus karena latarnya berganti ke `info_bg` `#EDF1F5` lewat `bg_input_tv` — tepat pada momen pengguna membaca petunjuk isian di TV/HP terang.
- Warna digelapkan sedikit ke `#646E7A` = 4,56:1 di atas latar fokus dan 5,18:1 di atas kartu putih (dari 5,0:1); konteks terang lain ikut membaik, mode malam memakai nilainya sendiri dan tidak berubah.

## [Belum rilis] — Audit 510: kontras teks galat log mode malam dinaikkan ke 4,77:1
- `log_error` mode malam `#F0554D` di atas `info_bg` `#1E2A44` hanya 4,16:1 (hitung WCAG) untuk teks log 11sp — di bawah ambang 4,5:1 teks kecil, dan merah galat adalah teks paling penting dibaca sekilas di log malam.
- Warna diterangkan sekeluarga ke `#F26A62` = 4,77:1 (terverifikasi hitung WCAG sebelum/sesudah). `status_off` yang berbagi nilai lama tidak diubah — konteks pakainya berbeda dan di luar temuan ini.

## [Belum rilis] — Audit 509: kunci keadaan lokal Telegram/throttle tidak lagi ikut ekspor config
- Ekspor config (`configJson`) masih meloloskan kunci keadaan khas-perangkat: `tg_last_file` (ID berkas backup terakhir — pola yang di LogActivity sendiri digolongkan rahasia), identitas bot ter-cache (`tg_bot_id`, `tg_bot_user`), hash menu (`tg_menu_hash`), dan stempel throttle (`alarm_throttle_elapsed`, `alarm_throttle_waktu_elapsed`, `boot_throttle_elapsed`, `boot_throttle_wall`). Terimpor ke perangkat lain, nilainya basi/asing dan bisa mengacaukan throttle serta penanda backup perangkat tujuan.
- Kedelapan kunci masuk `TAK_DIEKSPOR_KEYS`; uji `secretTakIkutBackup` diperluas untuk semuanya.

## [Belum rilis] — Audit 508: contoh galat update Telegram di log kini diredaksi
- Ringkasan "update dilewati" di `pollOnce` menulis contoh eksepsi mentah ke log; jalur galat lain (callback, status, unduh) sudah melewati `TgBackup.redaksiDetailGalat` yang menyamarkan token/URL/kredensial. Log bisa dibagikan lewat fitur ekspor, jadi pengecualian yang membawa URL bertoken pada jalur ini bisa ikut tersebar.
- Contoh galat kini diredaksi dengan helper yang sama sebelum dipotong 120 karakter dan ditulis ke log.

## [Belum rilis] — Audit 507: verifikasi PIN ASCII tidak lagi membayar PBKDF2 kedua yang identik
- Setiap verifikasi PIN yang gagal selalu menjalankan dua derivasi PBKDF2 penuh (primer + cadangan kompatibilitas). Cadangan itu hanya bisa berbeda untuk masukan non-ASCII (perbedaan konversi char→byte antar-provider); untuk masukan ASCII murni — selalu begitu pada format bungkus `PBKDF2W$` yang masukannya hex SHA-256 — hasil cadangan identik secara konstruksi, jadi derivasi kedua adalah biaya murni yang terasa di STB lambat setiap pengguna salah ketik PIN.
- Jalur cadangan kini dilewati bila masukan terbukti seluruhnya ASCII (helper murni `semuaAscii` + uji); PIN non-ASCII pada format `PBKDF2$` tetap mendapat cadangan kompatibilitas seperti sebelumnya.

## [Belum rilis] — Audit 506: tlsRestoreLengkap kini mensyaratkan dua pasangan lengkap dua arah
- `tlsRestoreLengkap` hanya memeriksa dua kunci privat; backup berisi kunci tanpa sertifikat pasangannya dinyatakan "lengkap", lalu `pasangTlsRestore` menimpa berkas hidup satu-satu dan meninggalkan campuran baru/lama yang tidak serasi (server gagal start TLS atau menyajikan sertifikat yang tidak cocok dengan kuncinya).
- Kelengkapan kini berarti keempat entri `ENTRI_TLS_RESTORE` ada (pasangan CA dan pasangan leaf, dua arah); pesan peringatan restore disesuaikan ("pasangan sertifikat + kunci privatnya tidak lengkap"), dan uji kelengkapan diperbarui namanya + ditambah kasus asimetris kunci-saja dan kurang-satu.

## [Belum rilis] — Audit 505: kegagalan pasang TLS saat restore tidak lagi dilaporkan sebagai gagal total
- Pada `restoreFromZipIsi`, database ditukar lebih dulu secara atomik; bila pemasangan sertifikat backup sesudahnya gagal, kode melempar pengecualian sehingga seluruh restore dilaporkan "gagal" — padahal DB sudah terganti (hanya TLS yang dikembalikan). Pemilik bisa mengira DB lama masih utuh dan mengulang restore yang sama.
- Kegagalan pasang TLS kini menjadi peringatan di pesan hasil: "Database SUDAH dipulihkan, tetapi sertifikat dari backup gagal dipasang (…) - TLS perangkat ini dikembalikan seperti sebelum restore." Rollback TLS-nya sendiri tidak berubah.

## [Belum rilis] — Audit 504: WakeLock poll Telegram dinaikkan dari 60 detik ke 10 menit
- `TgBotReceiver` mengambil WakeLock hanya 60 detik, padahal satu `pollOnce` menjalankan banyak panggilan API berurutan yang masing-masing boleh membaca sampai 30 detik — di perangkat tidur (STB), CPU bisa tidur di tengah poll begitu 60 detik lewat dan sisa perintah tertunda sampai perangkat bangun sendiri.
- Batas WakeLock kini 10 menit; lock tetap dilepas segera oleh `selesaikan` begitu poll selesai, jadi batas ini hanya plafon pengaman, bukan durasi tahan.

## [Belum rilis] — Audit 503: retry 429 Telegram kini menjangkau jalur unduh dan edit pesan
- Sikap tunggu-`retry_after` yang sudah ada di jalur kirim pesan dan unggah belum ada di tiga pemanggil API lain: `getFilePath` (429 = restore dari Telegram selalu gagal total), unduhan berkas backup di `downloadLastBackup` (429 di tengah jalan membuang seluruh unduhan), dan `ubahSinkron` (edit pesan status gagal seketika).
- Ketiganya kini mencoba sekali lagi sesudah tidur selama `jedaRetryAfterMs` dari badan galat 429 — pola yang sama persis dengan dua jalur lama; pemotongan jeda maksimum 5 detik tidak diubah.

## [Belum rilis] — Audit 502: salinCaKeStorage kini menulis lewat berkas sementara + rename
- Penyalinan cadangan CA menimpa langsung nama tujuan final: storage dicabut/penuh di tengah salinan menghancurkan cadangan lama bernama sama dan meninggalkan cadangan baru terpotong yang namanya tampak sah untuk dipasang/di-share.
- Salinan kini ditulis ke `<nama>.tmp` di folder yang sama, di-flush + fsync, lalu di-rename ke nama final (cadangan lama hanya terganti oleh salinan yang utuh); kegagalan membersihkan `.tmp` dan melempar IOException tanpa menyentuh cadangan lama.

## [Belum rilis] — Audit 501: kode mati overload privat isShareable(String) dihapus
- `FileShareProvider.isShareable(String)` satu-argumen tidak pernah dipanggil satu pun call-site (semua memakai varian dua-argumen yang menerima akar bagi eksplisit). Overload mati dihapus; namanya sengaja TIDAK dicatat ke `tools/bekas-hapusan.txt` karena nama yang sama masih terdefinisi sah sebagai metode dua-argumen (sesuai peringatan di kepala berkas itu).

## [Belum rilis] — Audit 500: latestVersion kini single-flight dan stempel TTL-nya dicap sesudah fetch
- `latestVersion` melepas kunci sebelum fetch jaringan: N thread bersamaan saat cache kedaluwarsa sama-sama menembak `api.github.com` (mengundang rate-limit 403 yang justru memperpanjang masa gagal), dan stempel cache/jeda-gagal dicap dengan waktu dari SEBELUM fetch sehingga TTL 15 menit dan jeda gagal 60 detik menyusut sebesar durasi fetch (timeout baca 10 detik ≈ 17% jeda).
- Seluruh keputusan + fetch kini berjalan di dalam `KUNCI_VERSI` (fetch memang terbatas timeout 10 detik dan pemanggilnya latar), dan `sLatestAt`/`sLatestGagalAt` dicap dengan `elapsedRealtime` segar pada saat selesai.

## [Belum rilis] — Audit 499: zip web-vault yang sudah terbukti gagal tidak lagi dibongkar ulang dari nol
- Patch #213 membuat tmpZip bertahan sesudah validasi payload gagal agar unduh ulang murah, tetapi hasil validasinya tidak diingat: setiap percobaan manual/Telegram berikutnya membongkar seluruh zip (orde 100+ MB tulis staging di STB) lalu gagal di titik yang sama persis.
- Kegagalan payload kini dicatat ke berkas penanda `<zip>.gagal` berisi sidik zip (panjang + mtime); percobaan berikutnya dengan zip yang sidiknya sama langsung gagal dengan pesan yang sama tanpa ekstrak, dan penanda dihapus bila zip berubah atau pemasangan berhasil. Uji sidik penanda ditambahkan.

## [Belum rilis] — Audit 498: PabrikTls12 tidak lagi melempar RuntimeException dan cache protokolnya tidak lagi meracuni soket lain
- Daftar protokol dicache global dari soket PERTAMA dan dipaksakan ke semua soket berikutnya (termasuk factory server), dan kegagalan `setEnabledProtocols` dibungkus `RuntimeException` yang menggagalkan pembuatan soket total — asimetris dengan kegagalan cipher di sebelahnya yang ditoleransi. Soket yang tidak mendukung protokol tercache (mis. hanya TLSv1.2 saat cache memuat TLSv1.3) mati total padahal protokol bawaannya akan berhasil.
- `nyalakan` kini memvalidasi cache terhadap dukungan soket saat itu (penolong murni `semuaProtokolDidukung`/`protokolCadangan`): cache yang tak didukung dipersempit ke irisan yang didukung soket ini, dan kegagalan set protokol jatuh ke irisan cadangan lalu ke bawaan soket dengan log — tidak pernah melempar lagi. Uji irisan + dukungan ditambahkan.

## [Belum rilis] — Audit 497: cap gagal bangun trust HttpsCompat kini ditulis di dalam kunci
- Cap `bangunGagalAt` untuk jeda 60 detik (Audit #212) ditulis pemanggil `apply` SESUDAH `socketFactory` keluar dari blok `synchronized`, jadi thread paralel yang sudah mengantre bisa masuk dan membangun ulang trust store mahal yang pasti gagal sebelum cap terlihat — jeda hanya efektif untuk panggilan sesudah cap tertulis.
- Pembangunan factory diekstrak ke `bangunFactory` dan cap kini ditulis di blok `synchronized` yang sama pada catch di `socketFactory`, sebelum kunci dilepas; thread berikutnya langsung melihat jeda dan melempar `JedaBangunTrust` tanpa membangun apa pun.

## [Belum rilis] — Audit 496: pemulihan sertifikat TlsCert tidak lagi asimetris terhadap .baru
- `pulihkanTukarTersisa` memulihkan kunci yang hilang dari `.baru` bila terbukti cocok, tetapi sertifikat yang hilang hanya boleh dipulihkan dari `.cad`: keadaan "pasangan .baru utuh dan saling cocok, resmi hilang, .cad tidak ada" dibuang seluruhnya dan CA/leaf dibuat ulang dari nol — lambat di STB dan memaksa semua klien memasang CA baru, padahal pasangan sah tersedia.
- Sertifikat kini juga dipulihkan dari `.baru` dengan syarat bukti yang sama persis: berpasangan dengan kunci resmi yang ada, atau kedua resmi hilang dan pasangan `.baru` terbukti cocok satu sama lain lebih dulu.

## [Belum rilis] — Audit 495: writeVersionTag binary kini atomik lewat .baru + fsync
- Penanda `bin/version.txt` ditulis langsung ke nama final tanpa fsync; crash/mati listrik di tengah tulis meninggalkan penanda korup/kosong sehingga binary yang sebenarnya cocok dianggap beda versi dan diunduh ulang sia-sia.
- Penulisan kini mengikuti pola `TlsCert.writeVersion`: tulis ke `version.txt.baru`, flush + `getFD().sync()`, lalu rename ke nama final (dengan jalur cadang hapus-lalu-rename), dan `.baru` dibersihkan bila gagal.

## [Belum rilis] — Audit 494: versi update hanya ditandai "sudah diberitahu" bila notifikasi benar-benar tampil
- `tampilkanNotifikasi` kembali diam-diam (izin POST_NOTIFICATIONS ditolak, pemeriksaan izin melempar, atau SecurityException) tanpa nilai balik, tetapi pemanggil tetap menulis `tg_notified_version` — untuk versi itu notifikasi sistem tidak pernah dicoba lagi walau izin kemudian diberikan.
- `tampilkanNotifikasi` kini mengembalikan boolean (hanya true bila `nm.notify` benar-benar dijalankan) dan penanda hanya ditulis bila true; pesan Telegram tetap dikirim pada percobaan yang sama seperti sebelumnya.

## [Belum rilis] — Audit 493: shim getrandom kini menghormati flags, bukan membuangnya
- Kedua pintu shim (`getrandom()` dan interposisi `syscall(SYS_getrandom)`) membuang argumen `flags` sepenuhnya: flags tak dikenal yang menurut kontrak Linux harus `EINVAL` diterima diam-diam, dan `GRND_RANDOM` dilayani dari `/dev/urandom` — dan uji host justru mengunci penyimpangan itu dengan menuntut sukses untuk flags `0xFFFF`.
- Shim kini memvalidasi flags lewat `getrandom_bendera` (gabungan yang dikenal hanya NONBLOCK/RANDOM/INSECURE; sisanya -1/EINVAL), melayani `GRND_RANDOM` dari `/dev/random` lewat jalur baca yang sama (`isi_dari` berparameter sumber, fd terpisah), dan jalur syscall membaca argumen ketiga untuk meneruskannya. Uji host dibalik: `0xFFFF` wajib EINVAL di kedua pintu, `GRND_RANDOM` wajib terisi 32 byte, flags 0 tetap jalan. Pembungkus `isi_urandom` yang tak lagi terpakai dihapus dan dicatat di `tools/bekas-hapusan.txt`. Bukti lokal sebatas `gcc -fsyntax-only` bersih (aturan repo: tanpa build/test); uji interposisi penuh berjalan di CI build-binary.

## [Belum rilis] — Audit 492: cache cap CA kini dibersihkan saat berkas CA hilang, tak lagi bergantian 0/hash lama
- Cabang "CA tidak ada" di `capCaAktif` memperbarui `capCaWaktu` tetapi membiarkan `capCaStat`/`capCaNilai` lama: panggilan pertama sesudah cache kedaluwarsa mengembalikan 0 (health-check gagal), panggilan berikutnya dalam 60 detik mengembalikan hash CA lama dari jalur cepat (health-check lolos) — kondisi berkas yang sama menghasilkan hasil bergantian dan bisa memicu hitungan gagal palsu atau menutupi CA yang hilang.
- Cabang itu kini mengosongkan pasangan cache (`capCaStat = MIN_VALUE`, `capCaNilai = 0`) sebelum memperbarui waktu, sehingga "CA hilang" adalah satu-satunya hasil sampai CA benar-benar ada lagi.

## [Belum rilis] — Audit 491: hasil pencarian PID anak dari /proc kini disimpan per objek Process
- Di API 21–25 (perangkat sasaran utama) `pidOf` selalu jatuh ke `pidAnakDariProc`, yang memindai ulang seluruh kandidat se-UID dan membaca cmdline+stat tiap kandidat pada SETIAP panggilan — cache 8 detik yang ada hanya meng-cache daftar PID, bukan hasil bacaannya. Pemanggilnya sering dan berulang (RSSI berkala, `bereskanServerYatim` per percobaan, dan satu panggilan per kandidat di lingkaran verifikasi terakhir).
- PID sebuah objek `Process` tidak pernah berubah seumur objek itu, jadi hasil positif kini disimpan di `WeakHashMap` per objek dan pemindaian hanya terjadi sekali (cek `exitValue` yang murah tetap lebih dulu, sehingga proses mati tidak pernah dilayani dari cache). Panggilan `pidOf` di lingkaran verifikasi terakhir `bereskanServerYatim` juga diangkat keluar lingkaran.

## [Belum rilis] — Audit 490: auto-merge kini dimatikan ulang bila PR berubah menyentuh dependensi Gradle
- Alur auto-merge hanya searah: PR yang dibuka sebagai non-Gradle mengaktifkan auto-merge, lalu push susulan (`synchronize`) yang menambahkan berkas Gradle hanya memicu komentar panduan — auto-merge yang sudah terlanjur aktif tidak pernah dimatikan dan tetap menggabungkan begitu check wajib hijau, melewati aturan merge manual dependensi Gradle.
- Langkah baru mematikan auto-merge (`gh pr merge --disable-auto-merge`, menoleransi keadaan belum aktif) setiap kali deteksi ulang menemukan berkas Gradle, sebelum komentar panduan dipasang. Dengan check wajib dari Audit 474, penggabungan juga selalu dinilai pada head terbaru.

## [Belum rilis] — Audit 489: Stale tidak lagi bisa menutup antrean issue audit
- `stale.yml` menandai issue non-PR sesudah 45 hari dan menutupnya 14 hari kemudian, sedangkan model kerja repo ini memang menyimpan temuan audit sebagai issue terbuka yang menunggu giliran maraton — antrean putaran-12 akan mulai tertutup otomatis sekitar Desember 2026 dan pekerjaan yang belum dikerjakan hilang tanpa keputusan siapa pun. Label `bug` (label template laporan bug) pun tidak termasuk pengecualian.
- `exempt-issue-labels` kini memuat `bug` dan label baru `audit`; label `audit` dibuat di repo dan dipasang ke seluruh issue antrean putaran-12 yang masih terbuka (32 issue) agar pengecualiannya nyata, dan menjadi penanda baku issue hasil audit berikutnya.

## [Belum rilis] — Audit 488: berkas biner format tak dikenal kini ditolak kedua gerbang pada ukuran berapa pun
- Cek biner `verifikasi-patch.py` hanya menolak bila berkas >1 MB dan bukan teks (atau ber-header eksekutabel yang dikenal), dan `cek-cepat.sh` hanya memeriksa pola nama — biner ≤1 MB berformat tak dikenal (payload terkompresi/terenkripsi, dump, blob acak) lolos keduanya. Terbukti fixture audit: `blob.dat` dan `gambar.png` palsu masing-masing 200.008 byte lolos dua gerbang.
- `verifikasi-patch.py` kini menolak setiap berkas bukan-teks yang headernya tak dikenal (penolong `jenis_pasif` mengenali PNG/JPEG/GIF/WebP/PDF/ICO sebagai format pasif yang sah, tetap dibatasi aturan >1 MB), dan `cek-cepat.sh` mendapat pindai padanannya (deteksi biner via `grep -I` + tanda tangan hex yang sama, wrapper jar tetap dikecualikan). Dibuktikan ulang di salinan buang: blob acak 5 KB dan 200 KB → GAGAL dua gerbang, PNG beneran → LOLOS, pohon bersih 157 berkas → LOLOS.

## [Belum rilis] — Audit 487: konfigurasi Labeler ditulis ulang ke format actions/labeler v5+
- `.github/labeler.yml` masih memakai format v4 (`- any: [...]`) sedangkan workflow memasang `actions/labeler@v7`; format config didesain ulang dan dinyatakan tidak kompatibel sejak v5.0.0, jadi tidak ada label yang pernah terpasang dan tidak ada galat yang terlihat — fitur mati total sejak dipasang pada paket pemerataan.
- Ketiga label (`app`/`ci`/`docs`) ditulis ulang ke skema saat ini (`changed-files` + `any-glob-to-any-file`) dengan daftar pola yang persis sama, dan strukturnya divalidasi sebagai YAML berskema baru sebelum commit.

## [Belum rilis] — Audit 486: rantai fokus dinamis beranda tidak lagi memutus jalur D-pad ke log
- Penyesuaian fokus saat tombol update muncul/sembunyi mengarahkan `netInfo.down` dan `startStop.up` langsung ke satu sama lain (atau ke tombol update), melewati `homeLogScroll` — membatalkan rantai statis Audit 463 pada pemakaian nyata pertama dan mengembalikan log beranda tak terjangkau D-pad persis seperti sebelum #463.
- Penyisipan tombol update kini dilakukan pada mata rantai yang benar: `homeLogScroll.down` menjadi updateBtn/startStop dan `startStop.up` menjadi updateBtn/homeLogScroll; `netInfo.down` tidak disentuh lagi. Atribut statis `updateBtn.nextFocusUp` di XML juga diluruskan dari `netInfo` ke `homeLogScroll` agar konsisten sebelum kode dinamis berjalan.

## [Belum rilis] — Audit 485: tombol Show/Hide token kini bisa menyembunyikan lagi
- `togglePassword` menebak keadaan dari uji bit `(inputType & TYPE_TEXT_VARIATION_PASSWORD) != 0`, tetapi `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` (0x90) ikut memuat bit 0x80 — sesudah sekali ditampilkan, keadaan selalu terbaca "tersembunyi", jadi token yang sudah dibuka di layar TV tidak bisa disembunyikan lagi dan label tombolnya pun berbohong.
- Keadaan kini ditentukan dari perbandingan persis masker variasi lewat penolong murni `SettingsActivity.variasiSandiTersembunyi` (0x80 dan 0xE0 = tersembunyi, 0x90 = tampil). Uji nilai mentah inputType ditambahkan untuk ketiga variasi.

## [Belum rilis] — Audit 484: ekspor config tidak lagi meloloskan Chat ID lewat kunci dinamis pending-aksi
- Daftar tolak ekspor (`SECRET_PREF_KEYS`/`TAK_DIEKSPOR_KEYS`) hanya memuat nama kunci statis, sedangkan kunci persetujuan aksi tertunda berbentuk dinamis `tg_pending_aksi_<chatId>` — isinya memuat Chat ID pemilik yang seharusnya rahasia (anggota `SECRET_PREF_KEYS` sebagai `tg_chat`) bersama stempel elapsed dan nama aksinya, dan semuanya lolos ke `app-config.json` di setiap backup, termasuk yang tanpa sandi.
- Keputusan ekspor kini diekstrak ke predikat murni `TgBackup.kunciDiekspor` yang juga menolak semua kunci berawalan `TgBot.KEY_TG_PENDING_PREFIX` (konstantanya kini package-private), sejajar awalan `wv_notified_` yang sudah ditolak sebelumnya. Uji predikat ditambahkan untuk kunci dinamis positif/negatif.

## [Belum rilis] — Audit 483: hapus pesan ber-PIN kini dicoba ulang untuk galat sesaat, dan kunci peringatannya bisa pulih
- `hapusPesanPerintah` menyerah pada percobaan pertama untuk semua jenis kegagalan: timeout/429/5xx sesaat — persis kondisi jaringan STB — meninggalkan pesan berisi PIN tetap di riwayat chat, pesan yang secara desain tidak boleh tertinggal. Selain itu kunci `KEY_TG_HAPUS_WARN` tak pernah direset walau kemampuan hapus berubah, sehingga peringatan hanya bisa tampil sekali seumur pemasangan.
- Penghapusan kini dicoba sampai 3 kali dengan jeda 1–2 detik hanya untuk galat sesaat (eksepsi jaringan, 429, 5xx); galat permanen Telegram (4xx lain, respons ok=false) tetap langsung berhenti. Keberhasilan penghapusan kini juga menghapus `KEY_TG_HAPUS_WARN`, jadi kegagalan di masa depan sesudah bot kehilangan hak hapus bisa memperingatkan lagi.

## [Belum rilis] — Audit 482: zip backup plain yang tertinggal di cache kini ikut disapu basi
- Pada backup terenkripsi, zip PLAIN (database + kunci privat TLS) dibuat di cache dan hanya dihapus di blok `finally` jalur normal; proses yang mati di antaranya meninggalkan salinan plaintext utuh permanen di `cacheDir` — pola sapuan berkas sementara yang ada tidak mengenal nama `backup-telegram-*` dan cleanup folder backups tidak menjangkau cache.
- Penolong baru `TgBackup.sapuZipPlainCacheBasi` menyapu `backup-telegram-*.zip`/`.zip.tmp` basi dari cache di awal setiap backup (ambang umur + logika cap masa depan yang sama, diekstrak ke `layakSapuBasi` dan dipakai bersama `sapuTmpPlainBasi`). Metode ini sengaja hanya dipanggil untuk folder cache — pola nama yang sama di folder backups adalah backup sah yang tidak boleh disapu. Uji perilaku ditambahkan, termasuk bahwa berkas `.zip.enc` tidak disentuh.

## [Belum rilis] — Audit 481: rollback .cad di pulihkanTukarTersisa tidak lagi buta
- Cabang pemulihan pasangan tak cocok menghapus pasangan resmi lalu mengganti namanya menjadi pasangan `.cad` tanpa memeriksa hasil `renameTo` dan tanpa membuktikan pasangan `.cad` itu sendiri cocok — `.cad` yang setengah tertulis bisa mengorbankan pasangan resmi dan meninggalkan direktori tanpa pasangan yang bisa dipakai (sisa patch Audit #211).
- Pasangan `.cad` kini wajib lolos `pasanganCertKeyCocok` lebih dulu sebelum resmi disentuh; penukaran dilakukan lewat nama sampingan `.rusak` dengan setiap rename diperiksa, dan kegagalan di tengah mengembalikan pasangan resmi serta pasangan `.cad` ke tempat semula persis. Uji baru mengunci cabang negatifnya: `.cad` yang tak terbukti cocok tidak menimpa resmi dan tidak terbuang.

## [Belum rilis] — Audit 480: ekstrak dan tukar web-vault kini difsync agar tahan mati listrik
- Jalur ekstrak/swap web-vault adalah satu-satunya jalur berkas penting tanpa fsync sama sekali (bandingkan `TlsCert.writeVersion` dan pemasangan binary yang berlapis sync), padahal perangkat target rawan mati listrik: berkas hasil ekstrak dan kedua rename swap bisa hilang/terpotong sesudah update dilaporkan sukses dan versi dicap.
- Tiap berkas hasil ekstrak kini di-`sync()` sebelum ditutup, direktori staging (termasuk subdirektori yang dibuat) disync sesudah ekstrak, dan folder data disync sesudah swap selesai — memakai penolong baru `Updater.syncDirAman` dengan pola `Os.open`+`fsync` yang sama seperti di ServerService, menelan galat agar fsync tak pernah menggagalkan update di filesystem yang tak mendukungnya.

## [Belum rilis] — Audit 479: validasi hasil ekstrak web-vault kini memakai syarat payload yang sama dengan cek kesiapan
- Gerbang sesudah ekstrak zip web-vault hanya memeriksa `index.html` `.exists()`, sedangkan cek kesiapan `wvPayloadAda` (dipakai jalur lain) menuntut berkas sungguhan berukuran > 0. Zip yang lolos dengan `index.html` 0 byte akan terpasang sebagai vault rusak, dicap versi + penanda terpasang, dan tak pernah dicoba lagi.
- Gerbang ekstrak kini memanggil `wvPayloadAda(newDir)` yang sama, sehingga definisi "payload sah" tunggal di semua jalur; pesan galatnya menyebut kemungkinan kosong. Predikatnya sendiri sudah terkunci uji (`wvPayloadAda_wajibIndexBerisi`).

## [Belum rilis] — Audit 478: jeda gagal unduh 6 jam kini memakai jam dinding agar bertahan lintas reboot
- Stempel `bin_dl_gagal_at`/`wv_dl_gagal_at` menyimpan `elapsedRealtime` secara persisten — pola yang sama yang dulu diperbaiki untuk stempel PIN (Audit 79) tetapi terulang pada salinan #213. Sesudah reboot, stempel elapsed boot lama yang besar membuat `bolehCobaUnduhLagi` langsung mengizinkan unduh ulang: jeda 6 jam runtuh tepat pada skenario kegagalan unduh yang dibarengi boot-loop.
- Keenam titik baca/tulis stempel (ServerService + AutoUpdate, binary + web-vault) kini memakai jam dinding `currentTimeMillis`; fungsi murni `bolehCobaUnduhLagi` tidak berubah semantik (ujinya tetap berlaku). Stempel elapsed warisan dari versi lama terbaca sebagai masa lampau jauh sehingga mengizinkan tepat satu percobaan lalu sembuh sendiri pada penulisan berikutnya. Komentar di definisi kunci menandai basis jamnya agar tidak terulang.

## [Belum rilis] — Audit 477: onDestroy kini menunggu proses server benar-benar mati lewat jalur stopper yang sama
- `onDestroy` hanya memanggil `p.destroy()` sekali lalu selesai, berbeda dengan `stopServer()` yang menunggu kematian proses di thread `vw-stop` (`matikanProsesTegas` + `lepasProses` + start tertunda). Service yang dimatikan lalu segera dibuat lagi bisa menabrak proses lama yang masih memegang port dan mengotori penilaian start berikutnya.
- `onDestroy` kini meniru pola `stopServer()` persis: sesudah `destroy()`, thread daemon `vw-stop` menjalankan `matikanProsesTegas(p, 5000)` lalu pembersihan yang sama (`lepasProses` terjaga identitas proses sehingga aman dari balapan dengan `watchProcess`, `hapusTandaStop`, `jalankanStartTertundaStop`) — tanpa memblokir thread utama.

## [Belum rilis] — Audit 476: cache sidik token admin tidak lagi menyimpan token plaintext
- Memo `sidikTokenAdmin` menyimpan token admin utuh di field statik `sidikTokenMasuk` seumur proses app dan tidak pernah dibersihkan (token kosong pun tidak membersihkannya; reset penanda jalan tidak menyentuhnya) — padahal fungsi itu ada justru untuk tidak memegang plaintext, dan aplikasi ini secara aktif meminimalkan jejak token (redaksi log, ekspor tanpa sandi).
- Memo kini dikunci oleh sidik cepat FNV-1a 64-bit + panjang token (`sidikCepat`), bukan tokennya: tabrakan praktis mustahil dan dampak teoretisnya terbatas pada deteksi perubahan token di UI. Plaintext tidak lagi disimpan di mana pun di jalur ini, token kosong membersihkan memo, dan `resetPenandaJalan` kini ikut membersihkannya. Uji referensi FNV-1a ditambahkan (nilai dibuktikan dengan porting Python 1:1).

## [Belum rilis] — Audit 475: throttle alarm dipisah per jalur, penolakan tidak lagi menggeser jendela, backup harian yang tertelan ditandai tertunda
- `AlarmReceiver` memakai satu throttle global untuk semua aksi: broadcast perubahan jam (DATE/TIME/TIMEZONE_CHANGED) yang tidak menghasilkan backup ikut mengonsumsi jendela 60 detik alarm harian, stempel diperbarui juga saat ditolak sehingga rentetan broadcast bisa menunda tanpa batas (jendela geser), dan alarm harian yang tertelan hanya dipasang ulang ke tengah malam berikutnya — backup hari itu hilang diam-diam tanpa tanda tertunda.
- Throttle kini dua jalur terpisah (harian memakai kunci lama `alarm_throttle_elapsed`; siaran waktu memakai kunci baru `alarm_throttle_waktu_elapsed` + field statik sendiri), stempel hanya ditulis bila lolos (jendela tetap — semantik `bolehAlarmJalan` tidak berubah dan sudah terkunci uji), dan jalur harian yang tertelan kini menandai `KEY_BACKUP_TERTUNDA` serta mencatat log, sejajar jalur gugur lain di berkas yang sama.

## [Belum rilis] — Audit 474: gerbang repo kini berjalan pada pull request dan diwajibkan sebelum merge
- Kedua workflow build hanya memicu `push`/`workflow_dispatch`/`schedule`, sehingga untuk PR Dependabot — satu-satunya jalur PR rutin repo ini — job verifikasi, lint, dan unit test tidak pernah berjalan sebelum merge; klaim auto-merge "sesudah CI hijau" tidak ditegakkan berkas mana pun, dan branch `main` memang tidak terproteksi (terverifikasi API: "Branch not protected").
- `build-apk.yml` dan `build-binary.yml` kini juga memicu `pull_request` (saringan path sama seperti push; grup concurrency per-ref agar run PR tidak mengantre di belakang run push). Rentang verifikasi pada PR memakai `base.sha...head.sha` (kolom `before` kosong di event PR). Pada PR, langkah signing/verifikasi sertifikat/ukuran/salinan rilis/upload artifact di `build-apk.yml` dilewati (secrets memang tak tersedia untuk PR Dependabot) — yang berjalan gerbang + lint + unit test; publish binary memang sudah terjaga `refs/heads/main`. Proteksi branch `main` diaktifkan dengan check wajib `Cek patch yang berubah` (nama job verifikasi di kedua workflow), tanpa memaksa admin, agar auto-merge benar-benar menunggu gerbang hijau.

## [Belum rilis] — Audit 473: penjaga targetSdk/minSdk gerbang tidak lagi kalah oleh komentar umpan
- `nilai_target_sdk` dan `nilai_min_sdk` di `tools/verifikasi-patch.py` menjalankan regex di teks mentah `app/build.gradle.kts` dan mengambil kecocokan pertama, sehingga komentar `// minSdk = 21` / `// targetSdk = 28` di kepala berkas membayangi nilai asli di `defaultConfig` — patch Audit lama (#206/#207) hanya menutup kasus baris hilang. Terbukti fixture: komentar umpan + nilai asli 26/34 → LOLOS.
- Kedua fungsi kini mengupas komentar lebih dulu memakai `kupas_java` yang sudah ada (sadar string, jadi `//` di dalam string tidak ikut terbuang) sebelum mencari penetapan. Dibuktikan ulang di salinan buang: umpan `//` + nilai asli 26/34 → GAGAL, berkas asli → LOLOS, umpan blok `/* */` → GAGAL.

## [Belum rilis] — Audit 472: throttle boot tidak lagi menelan broadcast boot berikutnya
- Throttle boot membandingkan `elapsedRealtime` yang disimpan persisten lintas-boot lewat `bolehAlarmJalan`, yang hanya mengenali reboot bila elapsed mundur. Dua boot berbeda yang broadcast-nya sama-sama diterima pada fase elapsed mirip (puluhan detik sesudah boot) dengan selisih di bawah 60 detik dianggap duplikat dalam boot yang sama, dan penolakannya `return` tanpa log: auto-start server, jadwal backup, dan jadwal bot hilang diam-diam sampai app dibuka manual.
- Stempel throttle kini disimpan berpasangan (elapsed + jam dinding `boot_throttle_wall`) dan keputusan dipindah ke penolong murni `BootReceiver.throttleBootMenahan`: duplikat boot yang sama tetap ditahan, tetapi boot lain dikenali dari jam dinding yang maju jauh melampaui elapsed (atau mundur), dan elapsed yang mundur tetap dilepas. Penolakan kini juga dicatat ke log. Uji batas penolong baru ditambahkan (nilai harapan dibuktikan dengan porting Python 1:1).

## [Belum rilis] — Pemerataan sistem pengelolaan repo dengan repo Tasirin lain
- Repo ini sebelumnya hanya punya workflow build; kini ditambah paket automasi yang sama seperti tasirin-download-manager: Dependabot (gradle + github-actions, berkelompok, major AGP manual), CodeQL (analisis keamanan statis java-kotlin), Gitleaks (deteksi secret ter-commit), Stale (penanda + penutup issue/PR tak aktif), Labeler (label otomatis per path `app`/`ci`/`docs`, konfig `.github/labeler.yml`, label dibuat di repo), dan Auto-merge khusus PR Dependabot non-Gradle sesudah CI hijau (deteksi berkas gagal-tertutup; PR dependensi Gradle diarahkan ke merge manual).
- Ditambah pula `.github/CODEOWNERS`, template PR dan issue (bug report + feature request), `SECURITY.md` (kebijakan pelaporan kerentanan privat), `CONTRIBUTING.md`, dan `.editorconfig`. Workflow `build-apk.yml` dan `build-binary.yml` tidak diubah sama sekali.

## [Belum rilis] — Audit 471: cabang batas peta 128 capDepanStabil kini teruji
- Logika pembatas baru dari Audit 441 (#194) — saat peta penanda cap depan penuh (128), sapu entri yang berkasnya lenyap, dan `clear()` total bila tetap penuh — tidak pernah dijalankan uji mana pun; uji yang ada hanya membuktikan semantik dua-penampakan lama dengan satu entri. (Separuh temuan ini, pilihan TTL `collectIps` dari #192, sudah ditutup Audit 470 lewat `collectCacheSegar` + ujinya.)
- `capDepanStabil` kini package-private agar bisa diuji langsung, dan dua uji baru mengunci kedua cabang pembatas persis pada perilakunya yang membedakan: entri berkas yang masih ada selamat dari sapuan selektif (penampakan kedua tetap "stabil"), sedangkan sesudah `clear()` total berkas lama dihitung sebagai penampakan pertama lagi. Perilaku produksi tidak berubah.

## [Belum rilis] — Audit 470: cache awal collectIps tidak lagi dipercaya sebagai hasil enumerasi segar
- Patch Audit 438 (#192) membuang syarat cache tak kosong dari `collectIps`, sehingga keadaan awal "belum pernah enumerasi" (`collectCacheTime = 0`, daftar kosong) tak bisa dibedakan dari hasil kosong yang baru di-cache: panggilan pertama pada 10 detik pertama sejak boot mengembalikan daftar kosong tanpa pernah enumerasi, dan sertifikat TLS yang disiapkan pada jendela itu hanya berisi SAN loopback sampai sidik IP berubah.
- Keputusan segar kini diekstrak ke penolong murni `collectCacheSegar` (sejajar `ipCacheSegar` di sebelahnya): stempel 0 tidak pernah dianggap segar, TTL tetap 10 detik untuk hasil kosong dan 60 detik untuk hasil terisi, dan jam yang mundur membatalkan kesegaran. Uji batas TTL + stempel awal ditambahkan (sekaligus menutup separuh celah uji Audit 471 untuk pilihan TTL ini).

## [Belum rilis] — Audit 469: pasang-hook.sh kini bekerja di git worktree
- Skrip mengeras-tertulis `HOOK=".git/hooks/pre-push"` dan mensyaratkan `[ -d .git ]`; di worktree `.git` adalah berkas penunjuk gitdir dan hook tinggal di gitdir bersama, sehingga skrip menolak dengan pesan "folder .git tidak ada" yang menyesatkan dan pengembang worktree kehilangan kait pre-push lokal.
- Folder hook kini dicari lewat `git rev-parse --git-path hooks` (benar untuk clone biasa maupun worktree) dan dibuat bila belum ada; di luar repo pesan gagalnya menyebut penyebab sebenarnya. Isi hook dan bukti eksekusi `--self-test` tidak berubah. Dibuktikan di salinan buang: clone biasa terpasang + self-test lolos, worktree terpasang ke gitdir bersama, di luar repo gagal dengan pesan baru.

## [Belum rilis] — Audit 468: penjaga bekas-hapusan kini tahan panggilan yang dipisah baris kosong
- Pencocokan lintas baris `cek_panggil_bekas_hapusan` menggabungkan baris tambah saat ini dengan satu baris tambah sebelumnya, tetapi baris kosong ikut menimpa penanda "sebelumnya" — panggilan metode terhapus yang ditulis nama dan kurungnya dipisah satu baris kosong (Java sah) memutus rantai gabungan dan lolos, padahal aturan repo No. 15 bertumpu pada penjaga ini.
- Baris kosong kini tidak lagi menimpa penanda gabungan, sehingga pasangan nama+kurung tetap diperiksa melintasi baris kosong. Dibuktikan di salinan buang dengan commit uji: panggilan normal → GAGAL (utuh), varian terpisah baris kosong → GAGAL (sebelumnya LOLOS), kode sah terpisah baris tanpa nama terhapus → LOLOS.

## [Belum rilis] — Audit 467: cek-cepat kini menggagalkan README yang hilang, bukan menyamakan hitungan nol
- Cek sinkron README membandingkan jumlah judul `## ` antar ketiga berkas, dan `hitung_judul` mengembalikan 0 baik untuk berkas tak ada maupun berkas tanpa judul — menghapus KETIGA README sekaligus menghasilkan 0 == 0 == 0 yang dianggap sinkron (fail-open dua himpunan kosong, pola yang sudah diperbaiki untuk colors/layout di gerbang patch tetapi tertinggal di sini). `verifikasi-patch.py` memang tidak memeriksa README sama sekali.
- Keberadaan ketiga berkas kini diperiksa eksplisit lebih dulu; hilang satu pun digagalkan dengan pesan `berkas wajib hilang`. Dibuktikan di salinan buang: ketiganya dipindah keluar → GAGAL, satu dipindah → GAGAL, utuh → LOLOS.

## [Belum rilis] — Audit 466: pindai rahasia dan nama terlarang cek-cepat kini mencakup berkas belum terlacak
- Ketiga sumber daftar berkas `cek-cepat.sh` hanya melihat berkas terlacak git (`git grep`, `git ls-files`), sehingga token/kunci privat di berkas BARU yang belum pernah `git add` lolos pemeriksaan manual terakhir pengembang — bertentangan dengan klaim Audit 403 bahwa pindai meliputi worktree. Berkas terlarang bernama sensitif (`.env`, keystore) yang belum terlacak juga lolos cek nama.
- Daftar berkas kini ditambah `git ls-files --others --exclude-standard` (berkas ter-`gitignore` tetap dikecualikan karena memang tak akan ter-commit): lingkaran pindai token terbungkus dan cek nama terlarang mencakupnya, dan berkas belum terlacak juga dipindai utuh terhadap pola rahasia lengkap (termasuk kepala PEM). Dibuktikan di salinan buang: token di berkas tak-terlacak → GAGAL, PEM di berkas tak-terlacak → GAGAL, berkas baru bersih → LOLOS, token ter-stage (kontrol) → GAGAL.

## [Belum rilis] — Audit 465: penjaga ABI mengenal bentuk arrayOf/mutableSetOf dan menggagalkan blok yang hilang
- `himpun_abi` hanya mengenal token pengisi `listOf`/`setOf`/`addAll`/`add` (Audit 423); bentuk Kotlin yang sama sahnya — `arrayOf`, `mutableSetOf`, dst. — menghentikan pemindaian sebelum string dibaca, dan menghapus seluruh blok `abiFilters` menghasilkan himpunan kosong yang dianggap patuh. Aturan ABI tunggal (aturan repo No. 5) bisa dilanggar tanpa alarm.
- Himpunan token pengisi diperluas ke pabrik koleksi Kotlin yang lazim (`arrayOf`, `mutableListOf`, `mutableSetOf`, `arrayListOf`, `hashSetOf`, `linkedSetOf`), dan `cek_aturan_repo` kini menggagalkan bila `abiFilters` tak ditemukan atau tak terbaca sama sekali. Dibuktikan di salinan buang: `arrayOf`/`mutableSetOf` berisi arm64 → GAGAL, blok dihapus → GAGAL, bentuk `listOf` arm64 → GAGAL (utuh), berkas saat ini → LOLOS.

## [Belum rilis] — Audit 464: kontras text_hint mode siang dinaikkan melewati AA
- `text_hint` siang `#9AA0A6` — warna hint semua kolom isian bergaya `TasirinInput` (folder data, port, token, sandi backup, PIN) — hanya 2,64:1 di atas kartu putih dan 2,40:1 di latar jendela, jauh di bawah patokan AA 4,5:1 yang dipakai proyek ini untuk teks kecil (Audit 444), dan di TV kolom-kolom itu dibaca dari jauh dengan hint sebagai satu-satunya petunjuk isinya.
- Warna digelapkan ke `#68707C` (5,00:1 di atas kartu putih, 4,54:1 di latar jendela — dihitung dengan rumus luminansi relatif WCAG), tetap keluarga abu-biru yang sama dan sedikit lebih terang dari `text_secondary`. Mode malam tidak disentuh (sudah 5,39:1).

## [Belum rilis] — Audit 463: log realtime di layar awal kini bisa digulir dengan D-pad
- `homeLogScroll` di `activity_main.xml` tidak fokusable dan tidak masuk rantai `nextFocus*` mana pun (`netInfo` melompat langsung ke `startStop`), sehingga pengguna TV/STB tidak pernah bisa menggulir mundur log beranda (viewport 360dp, isi sampai 150 baris) — berbeda dengan layar log penuh yang sudah benar.
- `homeLogScroll` kini `focusable` + `focusableInTouchMode` (pola `activity_log.xml`) dan disisipkan ke rantai vertikal: `netInfo` ↓ `homeLogScroll` ↓ `startStop`, arah atas sebaliknya.

## [Belum rilis] — Audit 462: alarm TUNDA yang rahasianya tak cocok kini menandai backup tertunda
- Jalur rahasia-tak-cocok di `AlarmReceiver` hanya menyembuhkan aksi HARIAN (pasang ulang); aksi TUNDA langsung kembali tanpa penanda dan tanpa log. Keadaan itu tercapai bila rahasia alarm gagal tersimpan persisten (commit prefs gagal) lalu proses mati: rahasia dibangkitkan baru, alarm tunda terjadwal tak cocok, dan backup susulan boot hari itu hilang tanpa jejak.
- Cabang TUNDA kini menandai `KEY_BACKUP_TERTUNDA` (pola yang sama dengan jalur gagal `mulaiBackup` dan `BootReceiver`) dan mencatat kegugurannya di log, sehingga backup susulan tetap terjadi pada kesempatan berikutnya.

## [Belum rilis] — Audit 461: sidik menu bot dan kunci keadaan lokal lain kini ikut dikecualikan dari ekspor config
- Daftar pengecualian ekspor `configJson` tertinggal lima kunci keadaan lokal: `tg_menu_fp` (sidik SHA-256 token bot + revisi menu — turunan kredensial yang tak pernah disebut di `TgBackup`), `impor_gagal_peta` (Audit 409 mengklaim kunci ini tak diekspor, faktanya hanya impornya yang terblokir whitelist), serta `bin_fallback_for`/`bin_fallback_at`/`cek_gangguan_at` sementara padanan web-vault-nya sudah dikecualikan. Tak satu pun dibutuhkan restore (restore hanya membaca whitelist `BOLEH_IMPOR`).
- Kelimanya kini masuk `TAK_DIEKSPOR_KEYS` lewat konstanta pemiliknya masing-masing (`TgBot`, `SettingsActivity`, `Updater`), dan uji `secretTakIkutBackup` diperluas menutup kelimanya.

## [Belum rilis] — Audit 460: kegagalan update web-vault otomatis kini diberi jeda gagal seperti jalur binary
- Zip web-vault yang sudah lolos verifikasi SHA-256 dibuang sebelum validasi isi selesai, dan cabang web-vault `AutoUpdate.cek` tidak punya stempel gagal: bila aset rilisnya sendiri rusak (hash cocok tetapi `index.html` tak ada / zip tak terurai), setiap cek berkala mengunduh ulang ±35 MB yang identik dan gagal dengan cara yang sama, tanpa jeda — jalur binary sudah lama punya perlindungan ini (`KEY_BIN_DL_GAGAL_AT`), jalur web-vault tertinggal.
- Cabang web-vault kini memakai kunci baru `KEY_WV_DL_GAGAL_AT` dengan penjaga yang sama (`ServerService.bolehCobaUnduhLagi`, jeda 6 jam): kegagalan dicap, percobaan berikutnya dilewati sampai jeda lewat, dan cap dihapus pada keberhasilan. Kunci itu juga masuk `TAK_DIEKSPOR_KEYS` sejajar padanan binary-nya. Di `Updater`, penghapusan `tmpZip` dipindah ke sesudah cek `index.html` lolos agar artefak terverifikasi tidak dihancurkan sebelum validasi isi selesai (cabang zip rusak tetap membuangnya seperti semula).

## [Belum rilis] — Audit 459: kegagalan membangun trust store HttpsCompat kini diberi jeda, bukan diulang tiap koneksi
- `socketFactory()` hanya mengisi cache bila pembangunan trust store berhasil; satu kegagalan permanen untuk proses itu (aset rantai tak terurai, `AndroidCAStore` melempar di STB tertentu) membuat SETIAP koneksi berikutnya mengulang penyalinan seluruh CA sistem + parse aset yang pasti gagal, baru jatuh ke trust bawaan — biaya CPU/latensi tanpa batas di perangkat lambat (Audit 328 baru menutup sisi log-nya).
- Kegagalan kini dicap waktunya (`bangunGagalAt`) dan pembangunan ulang ditahan 60 detik lewat penolong murni teruji `bolehBangunTrust` (jam mundur tetap membolehkan, sejajar pola penjaga lain di repo); selama jeda, `apply` langsung memakai trust bawaan tanpa mencatat ulang. Cap dibersihkan pada pembangunan pertama yang berhasil.

## [Belum rilis] — Audit 458: sisa tukar pasangan sertifikat (.cad) kini dipulihkan saat ensure
- `tukarPasanganAtomik` menukar pasangan sertifikat lewat rangkaian rename (resmi → `.cad`, `.baru` → resmi); mati listrik di antara langkahnya meninggalkan sertifikat/kunci hilang atau tak berpasangan, dan tidak ada pemulihan saat start — `caOk` menilai pasangan rusak lalu `ensure` membuat CA sama sekali baru, memaksa semua klien memasang CA baru padahal pasangan lama utuh di berkas `.cad`.
- Penolong baru `pulihkanTukarTersisa` berjalan di awal `ensure` untuk pasangan CA dan leaf: berkas resmi yang hilang dikembalikan dari `.cad`; kunci yang hilang dilengkapi dari `.baru` hanya bila `pasanganCertKeyCocok` membuktikan ia pasangan sertifikat resmi (swap diteruskan), selain itu dari `.cad` (swap dibatalkan); pasangan resmi yang tak cocok dengan cadangan lengkap tersedia dikembalikan utuh ke pasangan lama; dan sisa `.cad`/`.baru` dibersihkan bila pasangan resmi terbukti cocok. Uji JVM menutup jalur pengembalian cadangan dan keamanan null (pencocokan pasangan memakai `pasanganCertKeyCocok` yang sudah teruji gagal-tertutup).

## [Belum rilis] — Audit 457: waitForOrKill pra-API 26 kini memakai satu thread penunggu per proses
- Jalur pra-API 26 `waitForOrKill` membuat thread daemon `vw-wait` baru pada setiap panggilan dan meninggalkannya terblokir di `waitFor()` setiap kali batas tunggu terlampaui; `matikanProsesTegas` saja memanggilnya dua kali per upaya, sehingga proses bandel di STB lama menumpuk thread + stack selama ia hidup.
- Penunggu kini disimpan per proses (`PENUNGGU_PROSES`, `WeakHashMap` tersinkronisasi): panggilan berikutnya untuk proses yang sama bergabung ke thread yang sudah ada, jadi jumlah thread penunggu dibatasi satu per proses. Semantik nilai kembali tidak berubah; uji baru membuktikan panggilan kedua tak menambah thread.

## [Belum rilis] — Audit 456: syarat IP stabil kini menuntut sampel jaringan kedua yang sungguhan
- `peringatkanIpBerubah` menghitung dua tick berurutan sebagai bukti IP baru stabil, tetapi kedua tick itu membaca `collectIps()` yang sama dari cache 60 detik — selama fase health cepat (interval 30 detik) satu enumerasi sesaat langsung dianggap stabil dan memicu restart yang belum tentu perlu.
- Kandidat IP kini dicatat waktu pertama terlihatnya (`ipBaruSejak`) dan restart hanya diminta bila kandidat bertahan >= 60 detik (satu umur cache penuh), sehingga bacaan penentu selalu berasal dari enumerasi jaringan yang berbeda. Keputusan tetap di penolong murni `restartIpBolehDiminta` (tanda tangan diperluas dengan sejak/kini) dan ujinya diperbarui + ditambah kasus batas umur cache.

## [Belum rilis] — Audit 455: penolong berkas_teks memindai seluruh berkas, bukan cuplikan 64 KB pertama
- `berkas_teks` (pengecualian teks untuk batas 1 MB, Audit 445) hanya membaca 64 KB pertama: berkas poliglot 1,3 MB berawalan teks dengan ekor biner dinilai teks dan lolos batas ukuran, membuka jalan memasukkan blob biner besar ke repo selama ekstensinya netral.
- Berkas kini dipindai per potongan sampai habis (cek byte NUL di semua potongan; dekode UTF-8 inkremental agar karakter multibita di batas potongan tidak salah nilai). Dibuktikan di salinan buang: poliglot 1,3 MB → GAGAL, teks murni 1,3 MB → tetap LOLOS (pengecualian Audit 445 utuh), biner murni 1,2 MB → GAGAL.

## [Belum rilis] — Audit 454: minSdk kini ikut dijaga gerbang (wajib tetap 21)
- `cek_aturan_repo` hanya memeriksa `targetSdk` dan ABI; `minSdk` tidak disebut sama sekali di gerbang, sehingga menaikkannya (mis. ke 26) lolos tanpa alarm — padahal perangkat sasaran utama repo ini adalah STB Android 5 yang langsung tidak bisa memasang APK, dan seluruh mesin kompatibilitas (shim getrandom, `KernelCompat`, `HttpsCompat`) ada untuk mereka.
- Penolong baru `nilai_min_sdk` membaca nilai dari `app/build.gradle.kts`; hilang, tak terparse, atau != 21 kini digagalkan. Dibuktikan di salinan buang: 26 → GAGAL, baris dihapus → GAGAL, 21 → LOLOS.

## [Belum rilis] — Audit 453: gerbang menggagalkan targetSdk yang hilang, bukan hanya yang >= 29
- `cek_aturan_repo` hanya menggagalkan `targetSdk` tak terparse (-1) atau >= 29; baris yang dihapus total menghasilkan `None` dari `nilai_target_sdk` dan lolos diam-diam — padahal tanpa baris itu AGP memakai compileSdk (34) sebagai target dan `execve` binary terblokir W^X di Android 10+, persis yang dijaga aturan repo No. 4.
- Nilai `None` kini digagalkan dengan pesan eksplisit. Dibuktikan di salinan buang: baris dihapus → GAGAL, nilai 28 → LOLOS, nilai 29 → GAGAL (perilaku lama utuh).

## [Belum rilis] — Audit 452: rantai fokus D-pad antar-kartu Settings disambung ulang mengikuti seksi terlipat
- Kontrol batas antar-kartu (`port`→`adminToken`, `pinEnabled`→`autoStart`, `restoreDb`→`tgToken`, `restoreTg`→`logOpen`, dan arah sebaliknya) statis dari XML dan menunjuk kontrol di seksi tetangga; bila seksi itu dilipat (badannya GONE), target fokus tidak tampil dan navigasi remote TV berhenti/melompat — pola yang sama dengan yang diperbaiki Audit 180 untuk satu titik, sedangkan `perbaikiRantaiFokus` selama ini hanya menyambung ulang fokus header.
- `perbaikiRantaiFokus` kini juga menyambung ulang keempat batas itu lewat penolong baru `aturFokusBatas`: target diarahkan ke isi seksi tetangga bila terbuka, atau ke headernya bila terlipat, untuk kedua arah (bawah dan atas). Nilai statis XML tidak berubah (tetap benar untuk keadaan semua seksi terbuka).

## [Belum rilis] — Audit 451: cabang tunggu-boot AlarmReceiver tidak lagi bisa mematikan rantai alarm harian
- Alarm yang jatuh pada 5 menit pertama sejak boot memanggil `jadwalTundaBoot` tanpa memeriksa hasil dan menelan eksepsi baca prefs; bila penjadwalan tunda itu gagal (AlarmManager null / izin exact ditolak), tidak ada alarm tunda, tidak ada alarm harian berikutnya, tidak ada log — backup harian berhenti diam-diam, tertinggal dari pola perbaikan Audit 405/428.
- Hasil penjadwalan kini diperiksa: bila gagal (atau prefs tak terbaca), rantai harian dipasang ulang lewat `jadwalUlangBilaOtomatis`, backup ditandai tertunda (`KEY_BACKUP_TERTUNDA`, pola yang sama dengan jalur gagal `mulaiBackup` dan `BootReceiver`), dan kegagalannya dicatat di log.

## [Belum rilis] — Audit 450: hash clipboard tidak lagi ikut diekspor ke config backup
- `configJson` mengekspor seluruh prefs kecuali dua daftar pengecualian, dan `clip_hash` — SHA-256 tanpa garam dari isi clipboard, termasuk token admin/bot yang disalin lewat tombol salin — tidak ada di daftar mana pun, sehingga ikut ke `app-config.json` dalam zip backup dan menjadi bahan tebak-tebakan offline. Jalur impor justru sudah memblokir kunci ini; ekspornya yang tertinggal.
- `LogActivity.KEY_CLIP_HASH` kini masuk `SECRET_PREF_KEYS` (sejajar `pin_hash` dan rahasia alarm) dan pasangan kedaluwarsanya `KEY_CLIP_KEDALUWARSA` masuk `TAK_DIEKSPOR_KEYS`; uji `secretTakIkutBackup` diperluas menutup keduanya.

## [Belum rilis] — Audit 449: penanda IP tidak lagi dimajukan sebelum restart terbukti berjalan
- `peringatkanIpBerubah()` memajukan `runningLanHost` ke IP baru sebelum `restart(...)` dipastikan berhasil; bila permintaan restart gagal atau intent RESTART dibuang karena antrean tugas penuh, tick berikutnya menganggap IP sudah ditangani dan DOMAIN menunjuk IP lama selamanya.
- Penanda kini hanya dicatat sebagai `ipRestartDiminta` sesudah `restart(...)` diterima; sesi start yang benar-benar berjalan tetap yang menulis `runningLanHost` dari host segar. Cabang antrean-penuh membersihkan penanda itu agar health mencoba lagi, dan keputusan meminta restart diekstrak ke penolong murni `restartIpBolehDiminta` yang teruji.

## [Belum rilis] — Audit 448: komentar grup untuk nama bekas-hapusan dari Audit 421 ditambahkan
- Delapan nama tes yang dicatat pada Audit 421 (#175) masuk `tools/bekas-hapusan.txt` tanpa komentar grup `# Audit NNN:` seperti konvensi berkas itu, sehingga asal-usulnya sulit ditelusuri sesi berikutnya.
- Komentar grup `# Audit 421: tes digabung/dihapus pada #175.` kini memayungi kedelapan nama itu; isi daftar tidak berubah.

## [Belum rilis] — Audit 447: inti job resolve disatukan menjadi action komposit bersama
- Inti job `resolve` (ambil + validasi tag rilis Vaultwarden terbaru, termasuk jalur cadangan gh→curl) tertulis identik di `build-apk.yml` dan `build-binary.yml`, sehingga perbaikan pola validasi tag harus dilakukan dua kali dan mudah tertinggal di satu sisi.
- Inti itu kini menjadi action komposit `.github/actions/resolve-vw` yang dipakai kedua workflow (kedua job memasang checkout lebih dulu karena action lokal membutuhkannya). Blok cek kelengkapan aset khusus binary tetap menjadi langkah tersendiri di job-nya dan membaca tag dari keluaran action. Skrip yang dipindah terbukti identik byte-per-byte dengan aslinya, blok aset terbukti verbatim, ketiga berkas ter-parse sebagai YAML valid, dan kedua skrip lolos `bash -n`.

## [Belum rilis] — Audit 446: verifikasi pasca-unggah APK kini mencocokkan digest SHA-256 juga
- Sesudah unggah, APK hanya dicocokkan ukurannya dengan aset rilis (3 kali coba), sedangkan aset binary mendapat verifikasi tiga lapis (ukuran + digest SHA-256 API + isi berkas `.sha256`). Padahal APK bertanda tangan adalah artefak yang dipasang pengguna; unggahan rusak berukuran sama akan lolos tanpa alarm.
- Langkah rilis `build-apk.yml` kini juga membandingkan digest SHA-256 aset di rilis dengan hash lokal dan menggagalkan rilis bila digest tak tersedia atau berbeda — pola yang sama dengan `build-binary.yml`. Sintaks shell terverifikasi `bash -n` dan YAML ter-parse bersih.

## [Belum rilis] — Audit 445: batas berkas 1 MB gerbang kini mengecualikan berkas teks
- Batas ukuran gerbang berlaku untuk semua berkas kecuali `gradle-wrapper.jar`, termasuk teks polos — padahal `CHANGELOG.md` (500 KB, wajib diedit di setiap commit perbaikan) sedang menuju ambang itu dan suatu hari akan menggagalkan semua push perbaikan dengan pesan yang menyalahkan binary/web-vault.
- Berkas yang terbaca sebagai teks (cuplikan 64 KB tanpa byte NUL dan ter-decode UTF-8, penolong `berkas_teks`) kini dikecualikan dari batas ukuran; berkas biner tak dikenal tetap tertahan. Dibuktikan di salinan buang: `.md` 1,29 MB LOLOS, blob biner 1,2 MB tetap GAGAL.

## [Belum rilis] — Audit 444: kontras text_secondary mode siang dinaikkan melewati AA teks kecil
- `text_secondary` siang `#6B7280` di atas `window_bg` `#F2F4F8` hanya 4,39:1 — sedikit di bawah ambang WCAG AA 4,5:1 untuk teks kecil, dan warna ini dipakai label/petunjuk kecil yang duduk langsung di latar jendela.
- Warna digelapkan satu langkah menjadi `#666D7B` (4,72:1 di latar jendela, 5,20:1 di atas kartu putih), tetap keluarga abu-biru yang sama. Mode malam tidak disentuh (sudah 10,84:1).

## [Belum rilis] — Audit 443: blok throttle dan jadwal-otomatis AlarmReceiver disatukan
- Blok throttle 60 detik di `onReceive` tertulis dua kali hampir identik (jalur siaran waktu dan jalur utama), dan pola cek `KEY_TG_AUTO` → `schedule` tersebar di banyak salinan — pola yang terbukti membuat perbaikan rantai alarm (Audit #159, #181) mudah tertinggal di satu salinan.
- Blok throttle diekstrak menjadi `lolosThrottleAlarm` (stempel throttle tetap ditulis pada kedua jalur, sama seperti sebelumnya) dan salinan jadwal-otomatis di jalur tunda-boot kini memanggil `jadwalUlangBilaOtomatis` yang sudah ada. Inventaris pernyataan sebelum/sesudah cocok persis dengan dedupe yang dimaksud; perilaku tidak berubah.

## [Belum rilis] — Audit 442: alur ketik PIN Settings kini memakai pool PIN bersama Util
- SettingsActivity memelihara executor satu-thread kedua (`pinExec`) khusus untuk hash PIN alur mengetik/mengganti, di samping `Util.jalankanPin` yang dipakai alur mengaktifkan — dua antrean untuk pekerjaan yang sama, melanggar pola satu pintu `Util` (AGENTS.md) dan membuat tugas hash dua alur tidak terserialisasi satu sama lain.
- `Util` kini menyediakan `kirimPin` (versi `submit` dari pintu yang sama, mengembalikan `Future` atau null bila antrean menolak); `tulisHashPin` memakainya dan `pinExec` dihapus. Pembaca `pinPending` memang sudah aman-null, dan penjaga `pinSeq` tetap membuang hasil basi.

## [Belum rilis] — Audit 441: peta CAP_DEPAN_TERLIHAT kini dibatasi dan disapu dari berkas yang lenyap
- Peta penanda stempel masa depan (Audit untuk #125/#160) hanya menghapus entri bila berkas yang sama terlihat lagi dengan stempel identik; berkas yang lenyap lewat jalur lain meninggalkan entri permanen selama proses hidup, tanpa batas ukuran — kebocoran memori lambat justru di perangkat yang jamnya sering melompat.
- Saat peta mencapai 128 entri, entri yang berkasnya sudah tidak ada dibuang; bila masih penuh sesudahnya peta dikosongkan (stempel dapat diamati ulang — berkas berstempel masa depan paling lambat terhapus satu siklus sapuan berikutnya, semantik dua-sapuan tidak berubah).

## [Belum rilis] — Audit 440: akar berbagi FileShareProvider dihitung sekali per operasi
- Setiap panggilan `isShareable()` mengulang kanonikalisasi `getFilesDir()`/`getCacheDir()` ke filesystem dan membaca SharedPreferences, dan setiap operasi provider (query/getType/openFile) memanggilnya dua kali penuh untuk cek awal + cek ulang anti-TOCTOU.
- Akar berbagi kini dibungkus snapshot `AkarBagi` yang dihitung sekali per operasi dan diteruskan ke kedua pemeriksaan; folder data divalidasi malas (`dataBagi`, ter-cache di snapshot) hanya bila jalurnya dibutuhkan, sama seperti sebelumnya. Semantik cek ganda anti-TOCTOU tidak berubah — yang dihemat hanya penurunan akarnya.

## [Belum rilis] — Audit 439: collectIps kini meng-cache hasil kosong dengan TTL pendek
- Syarat cache lama mengharuskan hasil tidak kosong, sehingga di perangkat tanpa LAN setiap panggilan `lanHost()`/`localIp()` (termasuk dari health tick berkala) meng-enumerasi ulang semua antarmuka jaringan di bawah kunci, tanpa jeda, sepanjang server jalan.
- Hasil kosong kini ikut ter-cache dengan TTL 10 detik (hasil berisi tetap 60 detik): enumerasi berulang terputus, dan keterlambatan mengenali LAN yang baru tersambung terbatas 10 detik.

## [Belum rilis] — Audit 438: sisa plumbing dns fitur domain lokal yang sudah dihapus dibersihkan
- Fitur domain lokal kustom sudah dihapus (akses selalu IP LAN), tetapi daftar `dns` tetap dibuat kosong di `prepareTls`, diteruskan lewat `ensureCertWithIps` → `TlsCert.ensure` → `buatLeaf` → `extensionsBlock`, dan ikut dihitung dalam sidik perubahan sertifikat — kode mati yang menyesatkan seolah SAN DNS masih didukung.
- Seluruh rantai parameter `dns` dibuang; sidik `ips.txt` kini hanya daftar IP (sekali sesudah pembaruan, sidik lama terbaca berbeda sehingga leaf dievaluasi ulang satu kali — CA tetap, sama seperti saat IP berubah). Validator yatim `namaDnsValid` beserta dua pola regex dan dua tesnya ikut dihapus dan namanya dicatat di `tools/bekas-hapusan.txt`; SAN `localhost` yang tertanam tetap ada.

## [Belum rilis] — Audit 437: anotasi SuppressLint mati di PinGate.sisaKunciMs dihapus
- `sisaKunciMs` hanya memanggil `.apply()`, sehingga `@SuppressLint("ApplySharedPref")` di atasnya tidak menekan apa pun dan menyesatkan pembaca seolah ada `commit()` yang disengaja di sana. Anotasi yang benar-benar dibutuhkan hanya yang di `catatHasil` (memang memakai `commit()`), dan itu tidak disentuh.
- Anotasi mati dihapus; tidak ada perubahan perilaku.

## [Belum rilis] — Audit 436: konstanta mati MainActivity.DEFAULT_PORT dihapus
- Alias `DEFAULT_PORT` di MainActivity tidak dirujuk satu kali pun — semua logika port memakai `ServerService.DEFAULT_PORT` langsung (salinan di SettingsActivity memang terpakai dan tidak disentuh). Konstanta mati menyesatkan pembaca seolah MainActivity punya bawaan port sendiri.
- Konstanta dihapus; tidak ada perubahan perilaku.

## [Belum rilis] — Audit 435: metode mati fetchChecksum dihapus
- Pembungkus `fetchChecksum` atas `fetchChecksumRinci` tidak punya satu pun pemanggil di aplikasi maupun tes — yang hidup hanya varian rincinya (4 situs). Kode mati di berkas keamanan-kritis menyesatkan pembaca seolah ada jalur ambil-checksum kedua.
- Metode dihapus dan namanya dicatat di `tools/bekas-hapusan.txt` agar tidak dipakai lagi.

## [Belum rilis] — Audit 434: helper hanya-untuk-tes ukuranIsiZip dihapus
- `ukuranIsiZip` adalah pembungkus satu baris atas `pindaiZipRestore(zip).ukuran` tanpa satu pun pemanggil produksi — persis pola kode mati yang pernah dibersihkan repo ini (helper produksi yang hanya hidup untuk tesnya).
- Helper dihapus; tesnya kini memanggil `pindaiZipRestore(...).ukuran` langsung dan berganti nama menjadi `pindaiZipRestore_menjumlahkanUkuranEntri` (kasus yang diuji tidak berubah: total 1250 dan -1 untuk berkas tak ada). Kedua nama lama dicatat di `tools/bekas-hapusan.txt` agar tidak dipakai lagi.

## [Belum rilis] — Audit 433: cek duplikat definisi kini mengupas komentar XML
- `cek_duplikat_definisi` membaca berkas values mentah, sehingga definisi lama yang disimpan di dalam komentar XML ikut terhitung dan memicu GAGAL palsu "Definisi ganda" — padahal AAPT mengabaikan komentar. Fungsi tetangganya (`himpun_definisi`, `cek_sinkron_terjemah`) sudah memakai `kupas_komentar_xml` sejak Audit untuk #148/#153; cek ini tertinggal saat ditulis pada Audit untuk #168.
- Pemindaian kini melewati `kupas_komentar_xml` lebih dulu. Dibuktikan di salinan buang: duplikat di dalam komentar kini LOLOS, duplikat nyata tetap GAGAL.

## [Belum rilis] — Audit 432: penjaga bekas-hapusan kini mengenali rujukan metode ::
- Pola `cek_panggil_bekas_hapusan` hanya mencari panggilan `nama(`, sehingga nama metode terhapus yang dipakai ulang sebagai rujukan metode (`this::namaTerhapus`) lolos — celah yang sama yang ditutup `pemanggil()` pada Audit untuk #147 untuk cek tetangganya.
- Pola kini sama dengan `pemanggil()`: `\bnama\s*\(|::\s*nama\b`, termasuk lewat jalur baris gabungan untuk panggilan pisah baris. Dibuktikan di salinan buang: `this::sendMessageKb` kini GAGAL, rujukan ke nama yang tidak terlarang tetap LOLOS.

## [Belum rilis] — Audit 431: pola PEM cek-cepat menoleransi indentasi di awal baris
- Jangkar `^` pada pola kunci privat (dipindah ke awal baris oleh Audit untuk #141) membuat header PEM berindentasi — bentuk umum saat kunci ditempel sebagai blok kode di catatan/dokumentasi — lolos dari satu-satunya gerbang isi-rahasia.
- Pola kini `^[[:space:]]*-----BEGIN ...` sehingga spasi/tab di awal baris tetap tertangkap. Dibuktikan di salinan buang: PEM berindentasi kini GAGAL, PEM kolom 0 tetap GAGAL, dan pohon asli tetap LOLOS (literal header di tengah baris kode `TlsCert` tidak ikut tertangkap).

## [Belum rilis] — Audit 430: findChildPid kini mensyaratkan kepemilikan dan kecocokan tunggal
- Pencari PID anak untuk tampilan RSS mengembalikan proses pertama se-UID yang cmdline-nya cocok — tanpa cek induk dan tanpa cek tunggal — sehingga bila anak asli tak terdeteksi, angka RSS yang tampil di Settings/pesan bot bisa milik proses vaultwarden yatim sisa crash.
- `findChildPid` kini mencerminkan disiplin `pidAnakDariProc` (Audit untuk #120): kandidat wajib ber-cmdline server DAN ber-PPID sama dengan PID aplikasi, dan hasil hanya diakui bila kecocokannya tunggal (`pidDariKecocokan`); selain itu -1 (RSS tidak ditampilkan) daripada angka milik proses lain.

## [Belum rilis] — Audit 429: siapPakaiTanpaTulis memakai kaLayakDipakai yang sama dengan ensure
- Jalur fallback baca-saja menilai kelayakan CA dengan `caSisaCukup` yang ketat, sedangkan `ensure()` memakai `caLayakDipakai` yang mempertahankan CA saat jam STB mundur (sisa `-2`, notBefore belum tercapai). Dua fungsi menjawab pertanyaan yang sama dengan jawaban berlawanan untuk keadaan jam yang sama: fallback ditolak dan Start gagal total, padahal sertifikat yang sama diterima jalur normal.
- Sisi CA `siapPakaiTanpaTulis` kini memakai `caLayakDipakai` (semantiknya sudah terkunci uji `TlsCertTest.caLayakDipakaiPertahankanCaSaatJamMundur`); sisi leaf tetap ketat, sama seperti `ensure()` yang membuat ulang leaf pada keadaan itu.

## [Belum rilis] — Audit 428: rantai alarm harian dipasang ulang juga pada jalur eksepsi mulaiBackup
- Pemasangan ulang alarm harian di `mulaiBackup()` berada di dalam blok `try` yang `catch`-nya langsung pulang — bila membaca prefs melempar sesaat (prefs rusak, kasus yang memang ditangani `healkanStringPrefs` di STB), alarm satu-tembak tidak terpasang lagi dan backup harian berhenti diam-diam sampai pemicu lain. Jalur pulang-dini lain sudah ditambal Audit untuk #159; jalur eksepsi ini belum.
- Blok `catch` kini memanggil `jadwalUlangBilaOtomatis(context)` — pembantu yang sama dari #159, yang membaca ulang prefs dengan penjaganya sendiri — sebelum pulang, sehingga rantai tetap hidup bila kegagalannya sesaat dan tetap diam bila backup otomatis memang mati.

## [Belum rilis] — Audit 427: SettingsActivity kini menahan konten di balik gerbang PIN seperti MainActivity
- Penahan konten Audit untuk #164 hanya dipasang di MainActivity: `onResume` Settings memposting loop refresh lebih dulu dan memanggil `maybeShowPinLock()` paling akhir, dan `refreshFromService`-nya tidak pernah memeriksa keadaan PIN — selama layar PIN tampil/bertransisi, isi Settings (URL server, status, petunjuk update) ter-render penuh.
- `maybeShowPinLock()` kini dipanggil sebelum loop refresh diposting, dan `refreshFromService` pulang dini selama PIN menahan konten (helper `pinMenahanKonten` setempat: transisi buka-PIN berjalan, atau `PinGate.perlukanLayarPin` benar) — pola yang sama persis dengan MainActivity.

## [Belum rilis] — Audit 426: cermin pinMenahanKonten selaras dengan PinGate pada keadaan hash hilang
- Cermin keputusan penahan konten di MainActivity mensyaratkan hash PIN ada, sehingga pada keadaan `KUNCI_RUSAK` (PIN aktif, hash hilang/rusak) konten tetap ter-refresh di belakang layar PIN — padahal `PinGate.wajibLayarPin` mewajibkan layar PIN pada keadaan itu juga. Tes Audit untuk #164 ikut mengunci penyimpangan ini.
- Parameter `hashAda` dibuang dari `pinMenahanKonten`: konten tertahan tepat saat PIN aktif dan di luar masa tenggang, sama seperti keputusan `PinGate` (BUKA maupun RUSAK). Tes diperbarui di commit yang sama — kasus hash-hilang kini tercakup oleh bentuk tiga-argumen dan menegaskan konten tertahan.

## [Belum rilis] — Audit 425: callback batal confirm() tidak lagi berjalan dua kali
- Handler tombol Batal menjalankan callback `batal` tetapi tidak menandai `dipilih[0]`; dialog selalu men-dismiss sesudah klik, sehingga `OnDismissListener` (tambahan Audit untuk #136) mengira dialog tertutup tanpa pilihan dan menjalankan `batal` sekali lagi. Dampaknya tertutup selama ini hanya karena satu-satunya callback berkabel bersifat idempoten (menghapus berkas tmp).
- Tombol Batal kini menandai `dipilih[0] = true` sebelum menjalankan callback, sehingga jalur dismiss hanya menanggung penutupan tanpa pilihan (rotasi, sentuh luar, tombol Back) seperti maksud semula.

## [Belum rilis] — Audit 424: pesan ber-PIN kini dihapus juga pada jalur penolakan jam-mundur
- Bila jam dinding STB mundur melewati toleransi, loop poll Telegram menolak pesan tanpa memprosesnya (demi keamanan), tetapi juga tanpa menghapusnya — pesan berisi PIN (perintah ber-PIN atau balasan PIN) menetap di riwayat chat selamanya, kelas kebocoran yang sama dengan yang ditutup Audit untuk jalur pesan basi.
- Cabang jam-mundur kini mencoba penghapusan yang sama dengan jalur basi sebelum `continue`: predikat `pinBasiPerluHapus` yang sama (teks tebakan PIN, atau balasan saat sesi PIN tertunda), pemanggil `hapusPesanPerintah` yang sama, dan pagar `pinPerangkatAktif` yang sama. Pesan tetap tidak diproses.

## [Belum rilis] — Audit 423: gerbang ABI kini mengenali bentuk listOf/addAll/setOf
- Pengumpul ABI gerbang hanya membaca string tepat sesudah `abiFilters`/`+=`, sehingga bentuk idiomatik Kotlin `abiFilters += listOf(...)`, `abiFilters.addAll(listOf(...))`, dan `abiFilters = setOf(...)` yang memuat `arm64-v8a` lolos dari aturan repo ABI tunggal (armeabi-v7a saja) — sisa celah Audit untuk #143.
- `himpun_abi` kini melewati token pengisi `listOf`/`setOf`/`addAll`/`add` serta tanda `.`/`(`/`)` saat memindai sesudah `abiFilters`. Dibuktikan di salinan buang: ketiga bentuk berisi arm64 kini GAGAL, bentuk kelanjutan polos tetap GAGAL, berkas asli tetap LOLOS.

## [Belum rilis] — Audit 422: bersihkan tiga warning yang tertinggal dari maraton putaran 7–9
- Tiga warning non-fatal tertinggal dari perbaikan maraton: lint `ApplySharedPref` untuk `commit()` penghitung gagal impor (Audit 409) yang belum beranotasi penekan seperti semua situs `commit()` lain; peringatan gerbang "pola boros" untuk `Calendar.getInstance(` di `nextMidnight` (Audit 407) dan `new Thread(` di penjaga wakelock (Audit 408).
- `simpanGagalImporKeduanya` kini ber-`@SuppressLint("ApplySharedPref")` (commit tetap disengaja); `nextMidnight` dihitung manual dua lintasan — offset hari target dikoreksi sekali bila berbeda, hasil DST spring-forward dan fall-back identik dengan versi Calendar (dibuktikan porting Python); penjaga wakelock kini rantai `Util.postTunda` yang menjadwalkan dirinya sendiri, tanpa thread khusus dan tanpa menahan thread pool.

## [Belum rilis] — Audit 421: tes duplikat dan trivial digabung/dihapus tanpa kehilangan kasus
- Beberapa tes mengasersi ulang hal yang sama atau tak membuktikan perilaku: dua tes normalisasi port yang tumpang tindih, dua tes samaran log yang tercakup penuh oleh tes gabungannya, tiga tes `namaBolehDibagikan` yang tercerai, dan satu tes refleksi Updater yang hanya memeriksa nama + jumlah parameter metode (temuan audit putaran-9, #175).
- Pasangan port digabung (semua masukan unik dipertahankan; asersi `portTampakAngka` pindah ke tesnya sendiri), dua tes log yang tercakup dihapus, tiga tes nama berkas digabung jadi satu, dan tes refleksi trivial beserta penolongnya dihapus — keberadaan overload memang dijamin kompilasi karena dipanggil kode produksi. Nama metode tes yang dihapus dicatat di `tools/bekas-hapusan.txt`.

## [Belum rilis] — Audit 420: empat tes tanpa asersi kini memeriksa hasil yang teramati
- Empat tes hanya memanggil fungsi penelan-eksepsi tanpa satu pun asersi (`batalkanTundaAmanTanpaLooper`, `kirimKeSatuChatAbaikanMasukanTakSah`, `editMessageKb_abaikanMasukanTakSah`, `matikanProsesTegas_prosesMatiAtauNullAman`) — lulus selama JVM tak crash dan memberi kesan cakupan palsu (temuan audit putaran-9, #174).
- Fungsi terkait kini melaporkan hasil: `Util.postTunda`/`batalkanTunda` dan pengiriman Telegram (`sendMessageKe`/`sendPentingKe`/`sendMessageKbKe`/`editMessageKb`) mengembalikan boolean diterima/ditolak (pemanggil lama tak berubah, nilai boleh diabaikan), tes meng-assert penolakan masukan tak sah, dan tes proses mati menghitung panggilan `destroy()` (tepat 1x, tanpa paksaan).

## [Belum rilis] — Audit 419: dua tes tak lagi bisa lulus tanpa menjalankan asersi intinya
- `folderBytesWalk_symlinkMelingkarTakMeledak` mengembalikan "lulus" diam-diam bila pembuatan symlink gagal dan memeriksa total dengan rentang longgar 100–100.000; `tautanSimbol_bedakanLinkDanBiasa` menaruh asersi positifnya di dalam try yang catch-nya kosong — keduanya bisa hijau tanpa pernah memeriksa hal yang dimaksud (temuan audit putaran-9, #173).
- Kegagalan membuat symlink kini menjadi skip yang jujur lewat `Assume`, asersi berjalan di luar try/catch, dan total walk symlink melingkar dijepit tepat 3100 byte (31 kunjungan berkas 100 byte sampai batas kedalaman 32, dibuktikan dengan porting 1:1 ke Python).

## [Belum rilis] — Audit 418: pola berkas terlarang cek-cepat kini tak peka huruf besar/kecil
- Pola berkas terlarang di `cek-cepat.sh` dicocokkan peka huruf: `app.APK`, `SECRET.JKS`, dan `.ENV` lolos dari gerbang larangan binary/secret padahal isinya sama terlarangnya (temuan audit putaran-9, #172).
- Pencocokan kini `grep -iE`; daftar pola dan pesan kegagalannya tidak berubah. (Sisi verifikasi-patch sudah menurunkan huruf lebih dulu, jadi tak berubah.)

## [Belum rilis] — Audit 417: namespace kosong kini divalidasi eksplisit oleh cek manifest gerbang
- Bila `namespace` di `app/build.gradle.kts` tak terbaca (format berubah), `namespace_aplikasi()` mengembalikan string kosong dan `cek_manifest` menyusun FQCN dari awalan kosong — hasilnya banjir kegagalan "komponen tanpa kelas" yang menyesatkan, menunjuk sebab yang salah (temuan audit putaran-9, #171).
- Namespace kosong kini menghasilkan satu kegagalan yang jelas menyebut sebabnya dan cek komponen dilewati, bukan puluhan tuduhan komponen palsu.

## [Belum rilis] — Audit 416: cek import mati gerbang kini mengenali acuan Javadoc `{@see}`
- `cek_import_mati` mengecualikan import yang dirujuk `{@link}`/`{@linkplain}`/`{@code}`, tetapi tidak `{@see}` — import yang hanya dirujuk tag `{@see}` ditandai mati (positif palsu) dan tekanan menghapusnya bisa memutus Javadoc (temuan audit putaran-9, #170).
- Pola acuan Javadoc kini mencakup `see` dengan bentuk target yang sama (nama kelas, boleh berkualifikasi dan ber-`#anggota`); bentuk `{@see "..."}` dan `{@see <a ...>}` tetap tidak tertangkap karena token pertamanya bukan nama kelas.

## [Belum rilis] — Audit 415: deteksi berkas biner gerbang kini mengenali magic gzip
- `jenis_biner` di verifikasi-patch mengenali ELF/PE/ZIP/Mach-O/DEX dari byte magic, tetapi bukan gzip — berkas `.gz` berisi biner (mis. binary server terkompres) yang ter-commit akan lolos gerbang larangan biner (temuan audit putaran-9, #169).
- Magic gzip (`1f 8b`) kini dikenali dan dilaporkan sebagai berkas biner seperti jenis lainnya.

## [Belum rilis] — Audit 414: cek definisi ganda gerbang kini per folder values, bukan per berkas
- `cek_duplikat_definisi` mereset penghitungnya tiap berkas, jadi definisi yang sama (mis. `app_name` kedua) di dua berkas berbeda dalam folder `values/` yang sama lolos gerbang lokal dan baru merah di AAPT CI (temuan audit putaran-9, #168; terbukti eksekusi ulang saat audit).
- Penghitung kini dikelompokkan per folder values — persis satuan konflik AAPT; definisi lintas folder (mis. `values/` vs `values-night/`) tetap sah sebagai timpaan dan tidak dilaporkan.

## [Belum rilis] — Audit 413: hash PIN yang lebih kuat dari standar tidak lagi diturunkan saat "upgrade"
- `perluUpgradeHash` memakai `iter != iterasiStandar()`: di perangkat tanpa pabrik PBKDF2 (standar legasi 12.000), hash sah 30.000 iterasi dari perangkat lain ditandai perlu upgrade lalu ditulis ulang pada 12.000 — penurunan kekuatan hash secara diam-diam (temuan audit putaran-9, #167).
- Keputusan kini `iter < iterasiStandar()`: hanya hash yang lebih lemah dari standar perangkat yang dinaikkan; yang lebih kuat dipertahankan (verifikasi memang memakai jumlah iterasi tersimpan). Uji `normalisasiIterasiLama` yang mengunci perilaku lama diperbarui di commit yang sama.

## [Belum rilis] — Audit 412: start tertunda kunci data kini membatalkan jadwal restartTunda sebelumnya
- Jalur penundaan start saat kunci data sibuk menjadwalkan `restartTunda` dengan `posUtama` tanpa `batalUtama` lebih dulu — satu-satunya situs penjadwalan yang tidak berpasangan; `postTunda` tidak mendedupe, jadi penundaan beruntun menumpuk callback yang sama dan rantai restart bisa bercabang (temuan audit putaran-9, #166).
- `batalUtama(restartTunda)` kini dipanggil tepat sebelum penjadwalan itu, menyamai pola di `scheduleRestartTerkunci` dan situs lainnya.

## [Belum rilis] — Audit 411: pemindaian pola petunjuk login kini ikut di-throttle, bukan hanya pancarannya
- Cap throttle petunjuk login hanya maju saat sebuah petunjuk benar-benar terpancar; selama tak ada baris yang cocok, jendela selalu terbuka sehingga SETIAP baris log server membayar pindaian pola penuh (`minatLogin` + ~35 pola) tanpa henti di thread pembaca keluaran proses (temuan audit putaran-9, #165).
- Pemindaian kini punya throttle sendiri (`LOGIN_PINDAI_JEDA_MS` 10 detik, `bolehPindaiLogin`): cap dicatat saat pindaian berjalan, jadi biaya pindaian terbatas ±6x/menit; jeda pancaran antar-petunjuk tetap 60 detik seperti sebelumnya.

## [Belum rilis] — Audit 410: konten layar awal tidak lagi dirender sebelum layar PIN menutupinya
- `onResume` MainActivity menjadwalkan `refreshLoop` lebih dulu dan memanggil `maybeShowPinLock()` terakhir; refresh yang terposting bisa mengisi status dan log ke layar yang sudah terlihat sesaat sebelum PinActivity menutupinya — kilasan isi (termasuk baris log server) bocor di balik kunci PIN (temuan audit putaran-9, #164).
- Urutan dibalik (layar PIN dipanggil pertama, refresh hanya diposting bila PIN tidak sedang dibuka) dan `refreshFromService` kini berhenti lebih awal selama PIN menahan konten (keputusan murni `pinMenahanKonten`, cermin bebas efek samping dari `PinGate.perlukanLayarPin`); sesudah PIN dibuka, `onResume` menjadwalkan refresh seperti biasa.

## [Belum rilis] — Audit 409: penghitung gagal password impor konfigurasi kini tersimpan persisten
- Blokir 5x salah password impor konfigurasi dicatat di peta statis dalam memori (`GAGAL_IMPOR_DIGEST`); memulai ulang aplikasi mengosongkannya sehingga percobaan bisa diulang tanpa batas dengan modal restart (temuan audit putaran-9, #163).
- Penghitung kini dikemas (`kemasGagalImpor`) dan disimpan ke preferensi dengan `commit()` pada setiap perubahan, lalu dipulihkan (`pulihkanGagalImpor`, toleran terhadap isi rusak) setiap alur impor memeriksanya — pola yang sama seperti penghitung gagal PIN di `PinGate`; kunci ini tidak ikut ekspor/impor konfigurasi sehingga blokir tak bisa direset lewat impor.

## [Belum rilis] — Audit 408: wakelock tugas berat Telegram kini diperpanjang selama tugas berjalan
- Wakelock tugas berat (update, restore, backup) dipasang dengan batas tunggal 20 menit; unduhan besar di koneksi lambat yang melewatinya kehilangan wakelock di tengah jalan dan perangkat bisa tertidur sehingga tugas macet/gagal tanpa jejak jelas (temuan audit putaran-9, #162).
- Kini ada thread penjaga `vw-jaga-kunci` yang memperpanjang wakelock tiap 10 menit selama tugas belum selesai (keputusan murni `kunciMasihDijaga`), dengan batas total penjagaan 6 jam agar tugas yang benar-benar menggantung tidak menguras baterai tanpa batas; pelepasan saat tugas selesai tidak berubah.

## [Belum rilis] — Audit 407: nextMidnight kini dihitung dengan Calendar yang menghormati DST hari target
- `nextMidnight` memakai offset zona pada INSTAN acuan untuk menghitung tengah malam berikutnya; pada malam pergantian DST offset hari target berbeda dan alarm backup meleset ±1 jam (diuji dengan angka: zona America/New_York menjelang spring-forward meleset tepat 3600 detik; Indonesia tak ber-DST jadi tak terpengaruh) (temuan audit putaran-9, #161).
- Perhitungan kini memakai `Calendar` (tengah malam lokal hari berikutnya menurut aturan zona pada hari itu) + 60 detik seperti sebelumnya; nilai untuk zona tanpa DST tidak berubah.

## [Belum rilis] — Audit 406: sapuan .tmp folder backups kini mengenali cap masa depan
- `cleanupOldBackups` hanya menghapus `.tmp` bila `sekarang - lastModified > TMP_BASI_MS` — berkas bercap masa depan (jam perangkat melompat saat penulisan terpotong) bernilai negatif dan tak pernah tersapu, pasangan tak lengkap dari perbaikan cap depan #103 untuk plaintext (temuan audit putaran-9, #160).
- Cabang cap depan ditambahkan dengan syarat kestabilan yang sama seperti Audit 387 (`capDepanStabil`: cap tak berubah pada dua sapuan berurutan) agar berkas yang sedang ditulis alur lain tidak ikut terhapus.

## [Belum rilis] — Audit 405: rantai alarm backup harian kini dipasang ulang di jalur keluar awal
- Alarm harian adalah alarm exact sekali tembak yang dipasang ulang oleh `mulaiBackup`; dua jalur keluar awal di `AlarmReceiver` tidak pernah mencapainya — rahasia alarm tak cocok dan throttle 60 detik — sehingga satu kejadian saja mematikan rantai: backup harian berhenti selamanya tanpa log maupun pesan (temuan audit putaran-9, #159).
- Kedua jalur itu kini memasang ulang alarm hari berikutnya lebih dulu (`jadwalUlangBilaOtomatis`, hanya untuk aksi harian dan hanya bila backup otomatis aktif) sebelum kembali; isi backup dan throttle-nya sendiri tak berubah.

## [Belum rilis] — Audit 404: semua action workflow dipaku ke SHA dan rustup dipasang dari biner terpaku checksum
- Tak satu pun `uses:` di kedua workflow dipaku ke commit (tag mayor bisa digeser pemilik repo action), dan rustup dipasang lewat `curl https://sh.rustup.rs | sh` tanpa verifikasi — kompromi pihak ketiga berarti eksekusi kode asing di job pembangun biner/APK rilis (temuan audit putaran-8, #142; catatan pengerasan).
- Semua `uses:` kini menunjuk SHA commit persis (versi lama dipertahankan sebagai komentar), dan rustup dipasang dari `rustup-init` 1.28.2 resmi yang diverifikasi SHA-256 sebelum dijalankan — pola yang sama seperti bundel CA di berkas yang sama.

## [Belum rilis] — Audit 403: pindai rahasia cek-cepat kini meliputi worktree dan token terbungkus baris
- Pindai rahasia hanya membaca indeks (`git grep --cached`): menjalankan cek-cepat manual sebelum `git add` melaporkan LOLOS atas token yang hidup di berkas kerja; dan pemindaian per baris melewatkan token yang terbungkus ke dua baris (temuan audit putaran-8, #141).
- Pindai per baris kini menggabungkan indeks DAN worktree, ditambah lintasan kedua yang menggabungkan baris tiap berkas terlacak sebelum mencocokkan pola token — terbukti di salinan buang: token belum ter-stage dan token `ghp_` terbelah dua baris kini GAGAL, pohon bersih tetap LOLOS.

## [Belum rilis] — Audit 402: pola kunci privat cek-cepat tak lagi berjangkar akhir baris
- Alternatif PEM pada `POLA_RAHASIA` diakhiri `$`: baris `-----BEGIN ... PRIVATE KEY-----` yang diikuti spasi ekor atau berujung CRLF tidak cocok dan lolos dari satu-satunya gerbang rahasia lokal (temuan audit putaran-8, #140).
- Jangkar akhir diganti jangkar AWAL baris (`^`): kop PEM sungguhan selalu mulai di kolom pertama, sedangkan literal kop di dalam kode (pengurai PEM `TlsCert`) tidak — terbukti di salinan buang: varian spasi ekor dan CRLF kini GAGAL, pohon bersih (termasuk literal kode itu) tetap LOLOS.

## [Belum rilis] — Audit 401: penjaga bekas-hapusan kini memeriksa panggilan terbelah baris dan keabsahan daftarnya
- Dua lubang pada penjaga nama metode terhapus: (1) `cek_panggil_bekas_hapusan` memindai baris tambah satu per satu, jadi panggilan yang dibelah dua baris (`nama` di akhir baris, `(` di baris berikut) lolos; (2) daftar `tools/bekas-hapusan.txt` tak pernah divalidasi ke pohon — nama yang terdefinisi lagi diam-diam tetap terdaftar dan menyesatkan penjagaan (temuan audit putaran-8, #139).
- Pemindaian kini juga menguji gabungan tiap baris dengan baris sebelumnya dalam berkas yang sama, dan `cek_daftar_hapusan` menolak nama terdaftar yang masih terdefinisi di pohon. Terbukti di salinan buang: panggilan terbelah dan nama basi kini GAGAL, pohon bersih tetap LOLOS.

## [Belum rilis] — Audit 400: pengisian nama bot getMe tak lagi menahan antrean perintah
- `pollOnce` memanggil `isiNamaBotBilaKosong` secara sinkron SEBELUM `getUpdates`; panggilan `getMe`-nya bisa menggantung sampai ±50 detik (timeout sambung + baca) di jaringan mati — selama itu benang polling tunggal tertahan dan perintah pengguna tak diproses (temuan audit putaran-8, #138).
- Pengisian nama kini dikirim ke latar (`Util.jalankanBg`) dengan konteks aplikasi; polling langsung lanjut ke `getUpdates`. Nama bot hanya pelengkap tampilan/penyebutan, jadi keterlambatan latar tak mengubah perilaku perintah.

## [Belum rilis] — Audit 399: penanda hapus ekspor config polos kini selamat dari rotasi
- Berkas ekspor config polos (`app-config-*.json` — token bot, sandi cadangan, token admin teks terang) dijadwalkan terhapus 2 menit sesudah dibuat, tetapi penandanya field instance `exportPlainTertunda`: rotasi saat lembar berbagi tampil membuang penanda itu dan berkas hanya bergantung pada sapuan umur dari lifecycle Settings — bisa bertahan jauh melampaui TTL (temuan audit putaran-8, #137).
- Jalur + cap waktu penanda kini disimpan ke Bundle (`onSaveInstanceState`) dan dipulihkan di `onCreate`, sehingga instance sesudah rotasi meneruskan jadwal hapus yang sama (cap `elapsedRealtime` tetap sah dalam boot yang sama).

## [Belum rilis] — Audit 398: dialog konfirmasi kini menjalankan callback batal saat tertutup tanpa pilihan
- Penghapusan berkas unduhan restore Telegram (`vwtg-restore-*.zip`, isi database vault) hanya digantungkan pada tombol Batal dialog konfirmasi; rotasi menutup dialog lewat `dismiss()` yang tak memanggil listener tombol — berkas tertinggal di cache sampai sapuan TTL 1 jam yang sendirinya hanya terpicu dari Settings (temuan audit putaran-8, #136).
- Pembantu `confirm(...)` kini memasang `setOnDismissListener`: callback batal berjalan juga saat dialog tertutup tanpa pilihan (rotasi/back), dengan penanda pilihan agar jalur "Ya" yang sudah berjalan tidak ikut dibatalkan — pola yang sama seperti dialog sandi impor di berkas yang sama.

## [Belum rilis] — Audit 397: tekanan Start di Settings kini diteruskan sesudah rotasi di tengah cek port
- Kelanjutan `saveAndStart` Settings (dialog port sibuk/web-vault/`ServerService.start`) digantungkan ke `ui.post` instance yang berjalan; rotasi di tengah cek port menghancurkan instance itu dan kelanjutannya gugur diam-diam — server tak jadi menyala tanpa pesan apa pun (temuan audit putaran-8, #135; pola yang sama sudah diperbaiki untuk MainActivity pada #89).
- Penanda `startTertundaPort` yang sama kini diterapkan: disimpan ke Bundle lewat `onSaveInstanceState`, dipulihkan sebagai `startTertundaDariPutaran`, dan `onResume` instance baru menjalankan ulang `saveAndStart()` — cek port diulang dari keadaan tersimpan dan alur Start selesai seperti tanpa rotasi.

## [Belum rilis] — Audit 396: pengumpulan IP kini memeringkat antarmuka — LAN di atas seluler dan VPN
- `collectIps` menghimpun alamat dari SEMUA antarmuka up (VPN/seluler ikut) lalu mengurutkan leksikografis, sehingga pilihan pertama `lanHost` bisa jatuh ke alamat VPN/seluler yang tak terjangkau klien LAN; naik-turunnya VPN terbaca sebagai "IP berubah" dan memicu regenerasi sertifikat/restart yang tak perlu (temuan audit putaran-8, #134).
- Antarmuka kini diurutkan menurut peringkat (`peringkatAntarmuka`: wlan/eth/en 0, lain-lain 1, seluler rmnet/ccmni/pdp 2, VPN tun/tap/ppp/wg/ipsec 3) sebelum alamatnya dihimpun — pilihan utama dan deteksi perubahan IP stabil di alamat LAN; alamat IPv4 link-local `169.254.` tak lagi dihimpun. Daftar lengkap tetap dipakai untuk SAN sertifikat.

## [Belum rilis] — Audit 395: bit eksekusi scripts/cek-cepat.sh dipulihkan
- Mode berkas `scripts/cek-cepat.sh` di git turun menjadi `100644` (dari `100755`) sejak commit penghapusan komentar massal — clone segar di mesin normal tak bisa menjalankan `./scripts/cek-cepat.sh` langsung dan gerbang lokal terasa "rusak" padahal isinya sehat (temuan audit putaran-7, #133).
- Mode dikembalikan ke `100755` lewat `git update-index --chmod=+x`; isi skrip tidak berubah sehuruf pun.

## [Belum rilis] — Audit 394: urutan keadaan bg_input_tv diselaraskan — pressed sebelum focused
- `bg_input_tv.xml` adalah satu-satunya selector yang tertinggal dari penataan ulang #111: item `state_focused` mendahului `state_pressed`, dan karena selector mengambil kecocokan pertama, input yang sedang fokus sekaligus ditekan (khas navigasi D-pad TV) menampilkan gaya fokus, bukan gaya tekan (temuan audit putaran-7, #132).
- Kedua item ditukar urutannya persis seperti selector lain; bentuk dan warna tiap keadaan tidak berubah sama sekali.

## [Belum rilis] — Audit 393: ikon Play/Stop kini berwujud selector agar terlihat saat pressed di mode malam
- Warna isi vektor ikon bersifat statis: di mode malam ikon memakai `btn_primary_text` gelap `#1A1A1A`, tetapi latar pressed tombol utama/Stop juga gelap (`#0D47A1`/`#7A1A17`) — ikon lenyap selama tombol ditahan (2,02:1 dan 1,65:1), peninggalan perbaikan #94 yang memindahkan masalah dari keadaan default ke pressed (temuan audit putaran-7, #130).
- Ikon kini dibungkus selector (`ic_play_state`/`ic_stop_state`): keadaan pressed memakai varian ikon putih (≥7:1 di kedua latar pressed), keadaan lain tetap ikon asal; kedua pemakai (layout Settings dan penukar ikon di `SettingsActivity`) diarahkan ke selector.

## [Belum rilis] — Audit 392: teks tombol biasa kini punya warna pressed yang kontras
- Selector teks tombol biasa (`btn_text_state`) tak punya item `state_pressed`: saat ditekan latar menggelap ke `primary_container_pressed` tetapi teks tetap `primary` — kontras siang jatuh ke 3,99:1 selama penekanan (pasangan tak lengkap perbaikan tombol utama #93; temuan audit putaran-7, #131).
- Item pressed ditambahkan memakai warna baru `btn_text_pressed` — siang `#0D47A1` (5,99:1 di latar pressed), malam `#90CAF9` (5,19:1, sama seperti perilaku malam yang sudah aman).

## [Belum rilis] — Audit 391: restore Settings kini sejalan bot saat pemasangan TLS/prefs gagal
- Dua jalur restore bereaksi beda atas kegagalan yang sama: di bot, gagal pasang TLS mengembalikan TLS lama tetapi DB baru tetap terpasang (restore dilaporkan gagal), dan gagal terapkan prefs hanya jadi peringatan; di Settings kedua kegagalan itu jatuh ke catch luar yang mengembalikan DB LAMA — zip yang sama, hasil akhir berbeda (temuan audit putaran-7, #129).
- Jalur Settings kini mencerminkan semantik bot: `pasangTlsRestore` dibungkus lokal (gagal → staging dibersihkan, TLS lama dikembalikan, dilaporkan gagal, DB baru dipertahankan) dan `applyPrefsFromJson` dibungkus lokal (gagal → peringatan di log, restore tetap selesai).

## [Belum rilis] — Audit 390: pesan gagal perintah sertifikat bot kini lewat redaksi galat
- Tiga jalur sertifikat bot (`/careset`, `/ca`, `/cabackup`) mengirim `e.getMessage()` mentah ke chat; pesan pengecualian dari operasi berkas/API bisa memuat URL ber-token bot atau detail internal — jalur restore di berkas yang sama sudah lama membersihkannya lebih dulu (temuan audit putaran-7, #128).
- Ketiganya kini melewati `TgBackup.redaksiDetailGalat` yang sama (token dan URL disamarkan, pesan dipangkas) sebelum dibalas ke chat.

## [Belum rilis] — Audit 389: pemulihan .bak kini menjangkau folder backups
- `gantiAtomik` juga dipakai menulis backup di folder `backups/`, tetapi `pulihkanBakTersisa` hanya menyapu folder data dan `tls/` — swap backup yang terpotong meninggalkan backup bagus hanya sebagai `.bak` tanpa pernah dipulihkan, dan rotasi bisa menganggapnya hilang (temuan audit putaran-7, #127).
- Sapuan kini menjangkau `backups/` dengan predikat nama yang sama dengan pengenal berkas backup (`TgBackup.isFileBackup`: awalan `backup-telegram-`/`db-backup-`), lewat kelebihan-beban predikat pada penyapu yang sama — batasan nama sah Audit 386 tetap berlaku di semua folder.

## [Belum rilis] — Audit 388: parser checksum bernama kini menolak kandidat bercabang
- Jalur bernama `pindaiHexDariBaris` mengembalikan hex PERTAMA dari baris yang menyebut nama asset — dua baris untuk asset yang sama dengan hash berbeda (manifes rusak/tercampur) diterima begitu saja dari urutan baris, padahal jalur tanpa nama sejak perbaikan #100 menolak kandidat lebih dari satu (temuan audit putaran-7, #126).
- Jalur bernama kini menghimpun kandidatnya: dua hex berbeda untuk nama yang sama → `null` (checksum dianggap tak terbit, unduhan dibatalkan) — baris sepakat dengan hex sama tetap diterima.

## [Belum rilis] — Audit 387: sapuan plaintext bercap masa depan kini menuntut cap yang stabil dua sapuan
- Cabang cap-masa-depan dari perbaikan #103 menghapus berkas plaintext sementara hanya dari cap waktunya; daftar pengecualian hanya memuat berkas milik pemanggil sapuan itu sendiri — berkas yang sedang ditulis alur lain (dekripsi/restore berjalan bersamaan, jam perangkat melompat) bisa terhapus di tengah jalan (temuan audit putaran-7, #125).
- Berkas bercap masa depan kini hanya dihapus bila capnya terlihat TIDAK BERUBAH pada dua sapuan berurutan dalam proses yang sama (`capDepanStabil`): berkas yang sedang aktif ditulis capnya terus bergerak dan lolos terus, sedangkan sisa yatim bercap depan tetap terhapus — satu sapuan lebih lambat dari sebelumnya.

## [Belum rilis] — Audit 386: sapuan .bak pemulihan kini hanya menyentuh berkas kelola aplikasi dan tercatat di log
- `pulihkanBakDiFolder` memproses SEMUA `*.bak` di folder data dan `tls/`: yang berkas asalnya ada dihapus diam-diam, yang tidak ada diganti namanya menjadi berkas asal — berkas `.bak` manual milik pengguna (mis. salinan `db.sqlite3.bak` buatannya sendiri) bisa lenyap atau terpindah tanpa jejak (temuan audit putaran-7, #124).
- Sapuan kini dibatasi daftar nama yang benar-benar ditukar atomik aplikasi (di folder data: `db.sqlite3` + `-wal`/`-shm`; di `tls/`: `ca.pem`, `cert.pem`, `key.pem`, `ca-key.pem` — selaras daftar sah restore), dan penghapusan sisa swap kini dicatat di log seperti pemulihannya. Nama di luar daftar tidak disentuh sama sekali.

## [Belum rilis] — Audit 385: localIp dingin kini menyelesaikan alamat LAN secara sinkron sekali
- Pada cache yang belum pernah terisi, `localIp()` menjadwalkan penyegaran latar lalu langsung mengembalikan `127.0.0.1` — tampilan pertama URL akses (layar utama, Pengaturan, pesan bot) menunjukkan alamat loopback yang tak bisa dipakai perangkat lain sampai penyegaran latar keburu selesai dan layar digambar ulang (temuan audit putaran-7, #123).
- Jalur dingin kini menyelesaikan `lanHost()` secara sinkron sekali dan mengisi cache (enumerasi antarmuka adalah operasi lokal, bukan I/O jaringan); jalur basi tetap melayani nilai lama + penyegaran latar seperti sebelumnya, dan `127.0.0.1` hanya tersisa sebagai hasil sah `lanHost()` bila memang tak ada antarmuka LAN.

## [Belum rilis] — Audit 384: pidOf kini jatuh ke pemindaian /proc bila invoke pid() gagal
- `pidOf` hanya memakai cadangan /proc bila metode `Process.pid()` tidak ada; bila metodenya ada tetapi `invoke`-nya gagal (akses refleksi ditolak), hasilnya permanen -1 — sapuan server yatim dan kawanannya kehilangan PID anak untuk Proses yang sebenarnya hidup (temuan audit putaran-7, #122).
- Blok tangkap `pidOf` kini mengembalikan `pidAnakDariProc(p)` sebagai cadangan terakhir sebelum menyerah, sejalan jalur metode-tidak-ada.

## [Belum rilis] — Audit 383: keputusan symlink induk FileShareProvider kini bisa diuji di JVM
- Satu-satunya tes `adaSymlinkInduk` berjalan lewat cabang pintas unit-test JVM yang selalu menjawab `false`; logika perangkat sungguhan (lstat tiap leluhur + uji bit symlink) tak pernah teruji — regresi di pemeriksaan keamanan berbagi berkas ini tak akan ketahuan tes (temuan audit putaran-9, #158).
- Keputusan mode dipisah menjadi jahitan murni yang bisa disuntik: `modeAdalahLink` (bit `S_IFMT`/`S_IFLNK`) dan `adaSymlinkDariMode` (telusur leluhur lewat penyedia mode `ModeInduk`); jalur perangkat kini memasok mode dari lstat refleksi ke jahitan yang sama, pemeriksaan kanonis sesudahnya tak berubah. Tes baru mengunci bit link, leluhur link terdeteksi, dan mode tak diketahui yang diabaikan.

## [Belum rilis] — Audit 382: tes prioritas newline pecahPesan kini membedakan perilakunya
- Tes `pecahPesan_utamakanNewlineDanGabungUtuh` hanya menegaskan potongan ≤ batas+16 dan gabungan utuh — lolos juga untuk pemotong keras per-batas yang mengabaikan newline, padahal prioritas newline-lah perilaku yang hendak dikunci (temuan audit putaran-9, #157).
- Tes kini menegaskan daftar potongan persis (`baris1\n`, `baris2\n`, `baris3` untuk batas 10, diturunkan dari porting Python fungsi yang sama) dan tiap potongan ≤ batas tanpa kelonggaran grapheme untuk masukan ini.

## [Belum rilis] — Audit 381: konstanta produksi yang diuji kini dijepit nilai literal di tes
- Sejumlah tes menurunkan nilai harapan dari konstanta produksi itu sendiri (`KUNCI_MS`, `DEFAULT_PORT`, `LEAF_MAX_MS`/`BATAS_REGEN_MS`, `KEEP_PRE_RESTORE`, `BATAS_ENTRI_CHAT`): mengubah konstantanya diam-diam mengubah pula "harapannya", jadi tes tetap hijau dan pergeseran nilai yang disengaja maupun tak disengaja tak pernah ketahuan (temuan audit putaran-9, #156).
- Tiap berkas tes terkait kini punya tes penjepit yang menegaskan konstanta sama dengan literal yang dimaksud (300000 ms, "8088", 397 dan 30 hari dalam ms, 2, 32) — perubahan konstanta mulai sekarang menggagalkan tes dengan sengaja dan terlihat.

## [Belum rilis] — Audit 380: tes memo kernel legacy tak lagi tautologi
- `KernelCompatTest.legacyMemoKonsistenDenganMurni` membandingkan `legacyPerangkat()` dengan dirinya sendiri — selalu hijau walau memo menyimpang dari versi kernel perangkat, jadi regresi di jalur memo lolos tanpa jejak (temuan audit putaran-9, #155).
- Nilai harapan kini diturunkan secara independen dari `System.getProperty("os.version")` lewat `isLegacyKernel` (bukan lewat `kernelSekarang()` yang sedang diuji), lalu dibandingkan ke `legacyPerangkat()` dua kali untuk kestabilan memo.

## [Belum rilis] — Audit 379: cek locale gerbang tak lagi terkecoh spasi sebelum kurung
- Pola `cek_locale_baru` menuntut teks persis `.toLowerCase()`; bentuk sah Java `"ABC".toLowerCase ();` (spasi sebelum kurung) atau `( )` lolos dari larangan toLower/UpperCase tanpa Locale — gerbang hijau untuk kode yang justru hendak dicegahnya (temuan audit putaran-9, #154).
- Pola kini menoleransi spasi di sekitar kurung (`\s*\(\s*\)`) sambil tetap membiarkan varian berargumen `Locale` lewat; terbukti di salinan buang: bentuk berspasi kini GAGAL, bentuk `toLowerCase(Locale.US)` tetap LOLOS.

## [Belum rilis] — Audit 378: sinkron terjemahan kini memeriksa semua berkas values, bukan hanya strings.xml
- `cek_sinkron_terjemah` hanya membaca `values/strings.xml` dan `values-in/strings.xml`; string Inggris baru di berkas values lain (mis. berkas fitur tersendiri) tanpa padanan Indonesia sama sekali lolos — UI diam-diam jatuh ke Inggris untuk string itu (temuan audit putaran-9, #153).
- Nama string dan plurals kini dihimpun dari semua berkas `.xml` di folder `values/` dan `values-in/` (dengan komentar XML dikupas, sejalan Audit 373); terbukti di salinan buang: string Inggris yatim di berkas terpisah kini GAGAL, keadaan normal tetap LOLOS. Sekalian diperketat: pola definisi longgar Audit 374 kini memakai penengok batas tag `(?=[\s/>])` agar `<string>` tidak lagi tertukar dengan `<string-array>` (nama array `about_icons` sempat tertuduh sebagai string yang hilang).

## [Belum rilis] — Audit 377: cek_manifest kini memeriksa semua nama komponen dan targetActivity alias
- `cek_manifest` melewati (`continue`) setiap nama kelas penuh yang tidak diawali namespace aplikasi, dan tidak pernah melihat `activity-alias` — manifest boleh menunjuk kelas hantu di luar paket atau target alias yang tidak ada dan gerbang tetap LOLOS (temuan audit putaran-9, #152).
- Semua nama komponen kini diselesaikan ke jalur sumbernya (relatif, nama penuh, atau tanpa titik) dan wajib ada berkasnya; `activity-alias` diperiksa lewat `android:targetActivity`-nya. Terbukti di salinan buang: kelas asing hantu dan alias hantu kini GAGAL, manifest normal tetap LOLOS.

## [Belum rilis] — Audit 376: mode jalur verifikasi-patch kini membandingkan ke HEAD, bukan ke index
- Mode jalur (`verifikasi-patch.py <path>`) hanya menjalankan `git diff -- <path>` (worktree vs index): baris bermasalah terdeteksi sebelum `git add` tetapi LOLOS sesudahnya — pemeriksaan lokal memberi rasa aman palsu tepat pada keadaan paling umum, sesudah staging (temuan audit putaran-9, #151).
- Diff mode jalur kini `git diff HEAD -- <path>` sehingga perubahan staged dan belum staged sama-sama terlihat; terbukti di salinan buang: baris `toLowerCase()` yang sudah ter-stage kini tetap GAGAL, berkas bersih tetap LOLOS.

## [Belum rilis] — Audit 375: pendeteksi deklarasi metode di gerbang kini membaca signature yang dibungkus dua baris
- `nama_terdeklarasi` hanya mencocokkan deklarasi per baris; metode yang signature-nya dibungkus (nama dan kurung buka terpisah baris) tidak terdaftar sebagai masih terdeklarasi — memformat ulang signature menjadi dua baris dituduh "metode dihapus tapi masih dipanggil" oleh `cek_metode_hilang` (temuan audit putaran-9, #150).
- Pemindaian kini juga mencoba gabungan tiap baris dengan baris berikutnya sebelum mencocokkan pola deklarasi; terbukti di salinan buang: format ulang signature menjadi terbungkus kini LOLOS, penghapusan sungguhan tetap GAGAL.

## [Belum rilis] — Audit 374: pola definisi resource di gerbang tak lagi mensyaratkan atribut name pertama
- Delapan pola regex definisi resource (himpun definisi, duplikat, definisi baru, sinkron terjemahan, paritas) mensyaratkan `name` sebagai atribut pertama; `<string translatable="false" name="...">` yang sah tak terbaca sebagai definisi sehingga layout yang merujuknya dituduh "acuan yatim" — GAGAL palsu yang mengganggu push sah (temuan audit putaran-9, #149).
- Semua pola kini mencocokkan `name=` di posisi atribut mana pun dalam tag (`\b[^>]*?\bname=`); terbukti di salinan buang: string sah ber-atribut lain lebih dulu kini LOLOS, string hilang sungguhan tetap GAGAL.

## [Belum rilis] — Audit 373: pengumpulan definisi resource di gerbang kini mengupas komentar XML
- `himpun_definisi` menjalankan regex atas teks mentah sehingga `<string>` contoh di dalam komentar `<!-- ... -->` terhitung sebagai definisi sungguhan: layout yang merujuk string yang sebenarnya tidak ada bisa LOLOS dari `cek_acuan_yatim` (dan acuan di dalam komentar ikut terhitung sebagai pemakaian) — gerbang hijau padahal AAPT akan gagal (temuan audit putaran-9, #148).
- Isi berkas XML kini dikupas komentarnya lebih dulu (`kupas_komentar_xml`) sebelum definisi dan acuan dihimpun — terbukti di salinan buang: definisi hantu di kedua locale kini GAGAL sebagai acuan yatim, keadaan normal tetap LOLOS.

## [Belum rilis] — Audit 372: pencarian pemanggil di gerbang kini mengenal method-reference ::
- `pemanggil()` di `verifikasi-patch.py` hanya mengenal bentuk pemanggilan `nama(...)` sehingga metode yang dihapus tetapi masih dirujuk sebagai method-reference (`Foo::metode`) tak terlihat — `cek_metode_hilang` menjawab LOLOS padahal kompilasi pasti gagal (temuan audit putaran-9, #147).
- Pola pencarian kini juga mencocokkan bentuk `::nama`; terbukti di salinan buang: metode terhapus yang tersisa sebagai method-reference kini GAGAL, penghapusan bersih tetap LOLOS.

## [Belum rilis] — Audit 371: verifikasi PIN dan dekripsi backup kini mencoba kedua pengkodean PBKDF2
- Jalur pabrik `PBEKeySpec` mengkodekan kata sandi per byte-rendah tiap karakter (PKCS #5) sedangkan jalur manual memakai UTF-8 penuh; untuk sandi non-ASCII hasil turunannya beda dan format `PBKDF2$` maupun header `VWB3` tidak mencatat jalur mana yang dipakai — backup/PIN non-ASCII yang dibuat di perangkat tanpa pabrik tak bisa dibuka di perangkat berpabrik, dan sebaliknya (temuan audit putaran-9, #146).
- `verifikasiPbkdf2` kini mencoba derivasi cadangan (`PinCrypto.deriveCadangan`: pengkodean lawan dari jalur utama perangkat itu, dihitung manual sehingga selalu tersedia) bila derivasi utama tak cocok — hash yang cocok lewat jalur mana pun diterima dan penulisan ulang hash sesudah login menyembuhkan catatannya ke jalur utama. Dekripsi backup memakai pola sama: kegagalan tag GCM pada kunci utama dicoba sekali lagi dengan kunci cadangan sebelum menyerah. Vektor referensi independen (termasuk sandi non-ASCII kedua pengkodean) dikunci di `PinCryptoTest`.

## [Belum rilis] — Audit 370: perintah restart tak lagi membatalkan Stop yang sedang menahan persiapan start
- Handler `ACTION_RESTART` menghapus `batalStart` tanpa syarat saat intent diterima — membalap pemeriksaan batal di akhir persiapan start: Start lalu Stop (persiapan tertahan) lalu sebuah restart yang tiba bisa membuat proses tetap lahir walau user sudah Stop; dan cabang "Restart dilewati" dapat `stopSelf` di tengah persiapan yang masih berjalan sehingga proses anak lahir dari service yang sudah hancur (temuan audit putaran-9, #145).
- `batalStart` kini tak disentuh handler restart. Keputusan lewati disatukan di `restartDilewati` (teruji): bila persiapan start sedang berjalan, restart dilewati tanpa menurunkan foreground/`stopSelf` — alur start sendiri yang menentukan hasil akhirnya; cabang antrean penuh memakai penjaga yang sama.

## [Belum rilis] — Audit 369: pesan PIN basi kini ikut dihapus dari riwayat chat, bukan dilewati selamanya
- Pemeriksaan kesegaran pesan di `pollOnce` membuang pesan berumur >5 menit dengan `continue` sebelum blok penghapusan PIN (#101) tercapai: PIN yang dikirim user saat bot sedang mati/offline tertinggal permanen di riwayat chat Telegram dan tak pernah dikunjungi lagi karena offset sudah maju (temuan audit putaran-7, #121).
- Cabang basi kini memeriksa bentuk pesannya lebih dulu (`pinBasiPerluHapus`, teruji): perintah berargumen PIN dan balasan PIN untuk sesi pending (aktif maupun kedaluwarsa) dihapus lewat jalur hapus yang sama sebelum pesan dilewati; pesan basi biasa tetap dilewati tanpa disentuh, dan tidak ada perintah basi yang dijalankan.

## [Belum rilis] — Audit 368: pencarian PID anak lewat /proc tak lagi menebak identitas di antara dua proses server
- `pidAnakDariProc` mengembalikan proses server pertama yang cmdline-nya cocok dan induknya aplikasi ini, tanpa memastikan ia proses yang sedang dilacak; dengan dua proses server seinduk (sisa tumpang-tindih restart), PID yang dikembalikan bisa milik saudara yang salah — dan `bereskanServerYatim` mempercayainya sebagai pengecualian sehingga server yang hidup justru bisa dibunuh sebagai "yatim" (temuan audit putaran-7, #120).
- Pencarian kini mengumpulkan semua kecocokan dan hanya mengakui PID bila tepat satu yang cocok (`pidDariKecocokan`, teruji); lebih dari satu berarti identitas tak pasti dan hasilnya -1. `bereskanServerYatim` juga berhenti fail-closed (kembali false) bila server terlacak masih hidup tetapi PID-nya tak bisa dipastikan, dan memakai satu nilai PID anak yang konsisten selama satu percobaan sapuan.

## [Belum rilis] — Audit 367: tes SHA-256 kini punya vektor emas independen dan cakupan berkas besar
- Tes pemasangan hasil unduhan memasok checksum dari `Updater.sha256Hex` itu sendiri dan datanya hanya 1–4 byte, padahal produksi membaca per blok 64 KB — bug hash sistematis (hanya blok pertama ter-hash, hex terpotong) tak akan pernah memerahkan tes, padahal checksum ini gerbang keamanan biner/web vault berukuran MB (temuan audit putaran-9, #144).
- `UpdaterTest` kini mengunci vektor emas yang dihitung implementasi independen (keluarga vektor uji SHA-256 yang diterbitkan + pola 200.000 byte): berkas kosong, `abc`, 1.000.000 huruf `a`, dan pola biner 200 KB; plus jalur `pasangHasilUnduh` untuk berkas besar memakai checksum emas tertulis — cocok terpasang, beda satu digit hex ditolak.

## [Belum rilis] — Audit 366: gerbang aturan repo kini membaca ekspresi targetSdk dan ABI baris lanjutan
- `cek_aturan_repo` membaca angka pertama sesudah `targetSdk =` sehingga `targetSdk = 28 + 1` (efektif 29, melanggar aturan repo) terbaca 28, dan daftar ABI hanya dipindai dari baris yang memuat kata `abiFilters` sehingga ABI asing di baris lanjutan lolos — dua aturan repo paling keras bisa dilewati gerbangnya sendiri tanpa sengaja (temuan audit putaran-9, #143).
- Nilai `targetSdk` kini dievaluasi sebagai literal/ekspresi aritmetika sederhana (fail-closed: bentuk yang tak bisa ditentukan ditolak), dan ABI dihimpun dengan pemindai yang mengikuti daftar string melewati baris lanjutan dan koma — kedua bentuk lolos di atas kini GAGAL, keadaan normal tetap LOLOS (terbukti eksekusi ulang di salinan buang).

## [Belum rilis] — Audit 365: pemeriksaan fd pasca-buka FileShareProvider kini fail-closed
- Baris pertahanan terakhir anti-TOCTOU di `openFile` — membandingkan readlink `/proc/self/fd/N` berkas yang benar-benar terbuka dengan jalur kanonis yang lolos semua pemeriksaan — terhubung sebagai `if (jalurFd != null && !jalurFdCocok(...))`: bila `Os.readlink` sendiri gagal (null), penolakan dilewati diam-diam dan berkas tetap dilayani. Sisa dari perbaikan Audit 326 (#78); lapisan terakhir ini justru fail-open persis pada kegagalan yang seharusnya membuatnya menolak.
- Kondisinya kini cukup `!jalurFdCocok(jalurFd, canonUlang)` — `jalurFdCocok` memang sudah mengembalikan false untuk null (dan perilaku itu teruji), jadi readlink yang gagal kini menolak akses seperti ketidakcocokan biasa: tak bisa diverifikasi, tak dilayani.

## [Belum rilis] — Audit 364: sisa .bak swap DB/TLS kini dipulihkan saat start, bukan hanya binary
- `gantiAtomik` memulihkan `.bak` hanya di dalam proses yang sama; bila proses mati (listrik padam/baterai habis) di antara kedua rename, berkas tujuan hilang dan `.bak`-nya yatim. Satu-satunya pemulihan saat start hanya ada untuk binary (`ensureBinary`), padahal pemakai `gantiAtomik` lain memasang berkas yang jauh lebih penting saat restore: `db.sqlite3` (#69) dan berkas TLS (#70). Jendela milidetik di tengah restore yang terpotong daya membuat start berikutnya menciptakan database vault baru yang kosong — vault tampak hilang total padahal data utuh di `db.sqlite3.bak` dan hanya terselamatkan lewat rename manual.
- `pulihkanBakTersisa` kini berjalan di `pilihBiner` sebelum server dinyalakan: untuk folder data dan subfolder `tls/`, setiap `X.bak` yang berkas `X`-nya hilang diganti namanya kembali menjadi `X` (dengan catatan log), dan `X.bak` yang berkasnya sudah ada (swap selesai, penghapusan `.bak` yang terpotong) dibuang — pola yang sama dengan pemulihan binary.

## [Belum rilis] — Audit 363: pekerja verifikasi PIN selalu memulihkan UI lewat try/finally
- Tubuh pekerja di `PinActivity.cobaBuka()` (verify → `catatHasil` → buka grace → upgrade hash → `ui.post`) tidak punya try/finally penyeluruh: `sedangPeriksa` dan pengaktifan lagi tombol Buka hanya dipulihkan di dalam `ui.post` itu. Satu lemparan tak terduga di tengah — mis. `catatHasil` gagal keras di penyimpanan rusak, atau `verify` melempar pada hash tersimpan yang korup — membuat `ui.post` tak pernah terpasang: tulisan "Memeriksa…" menetap dan tombol Buka mati sampai activity dibuat ulang.
- Seluruh badan pekerja kini dibungkus try/finally dengan callback UI di blok finally (isi callback tidak berubah): apa pun yang gagal di tengah, keadaan UI selalu dipulihkan dan pengguna menerima hasil gagal biasa, bukan layar terkunci.

## [Belum rilis] — Audit 362: tombol Check Update di About dari MainActivity kini menutup dialog lebih dulu
- Di `AboutDialog`, cabang Settings men-`dismiss()` dialog sebelum bertindak, tetapi cabang selain Settings (dari MainActivity) langsung `startActivity(SettingsActivity)` tanpa menutup dialog: sesudah selesai di Settings dan menekan Back, dialog About lama masih terbuka menunggu ditutup manual. Sisa inkonsistensi di sekitar perbaikan Audit 322 (#74).
- Cabang non-Settings kini men-`dismiss()` lebih dulu, sama seperti cabang Settings.

## [Belum rilis] — Audit 361: DialogAman.tampil menelan kegagalan show dan membersihkan registernya
- `DialogAman.tampil()` memanggil `d.show()` telanjang sesudah cek hidup dan sesudah mendaftarkan dialog ke `DAFTAR` — berbeda dengan `tutupDiam` di berkas yang sama yang memakai try-catch. Bila token jendela keburu tidak valid saat `show()` dipanggil dari jalur asinkron (callback `ui.post` tepat ketika activity dihancurkan), `BadTokenException` lolos ke pemanggil sebagai crash dan entri dialog yang tak pernah tampil tertinggal di register.
- `show()` kini dibungkus try-catch sesuai filosofi kelas ini (gagal tampil = dialog dilewati diam-diam); pada kegagalan, entri yang baru didaftarkan itu juga dikeluarkan dari `DAFTAR` agar register tidak menampung dialog hantu.

## [Belum rilis] — Audit 360: localIp tak lagi menghitung antarmuka jaringan di utas pemanggil saat cache dingin
- `ServerService.localIp()` sudah benar untuk cache basi (jadwalkan penyegaran di latar, kembalikan nilai lama), tetapi pada cache DINGIN (proses baru) ia memanggil `lanHost()` — enumerasi `NetworkInterface` — sinkron di utas pemanggil. Pemanggilnya di utas UI: tick refresh MainActivity dan klik salin URL, jadi buka pertama aplikasi bisa tersendat menunggu enumerasi selesai.
- Blok penyegaran latar diekstrak ke `jadwalSegarkanIp()` dan kini dipakai kedua jalur: cache dingin menjadwalkan penyegaran yang sama lalu mengembalikan `127.0.0.1` — nilai yang sama dengan hasil `lanHost()` saat tak ada jaringan — dan URL tampil terkoreksi sendiri pada tick berikutnya begitu cache terisi (hitungan detik).

## [Belum rilis] — Audit 359: arah fokus D-pad layar PIN landscape kini mengikuti posisi tombol sebenarnya
- Di `layout-land/activity_pin.xml` kedua tombol tersusun menyamping (Buka kiri, Keluar kanan), tetapi atribut fokusnya tersalin utuh dari portrait tempat tombol bertumpuk vertikal: `pinBuka` punya `nextFocusDown=@id/pinKeluar` dan `pinKeluar` punya `nextFocusUp=@id/pinBuka` — di perangkat landscape/TV menekan Bawah pada "Buka" melompat menyamping ke "Keluar", dan menekan Atas pada "Keluar" melompat ke "Buka".
- Landscape kini memakai arah sebenarnya: `pinBuka` `nextFocusRight=@id/pinKeluar`, `pinKeluar` `nextFocusLeft=@id/pinBuka` + `nextFocusUp=@id/pinInput` (kolom PIN membentang di atas kedua tombol). Portrait tidak berubah; paritas ID layout-land tetap utuh.

## [Belum rilis] — Audit 358: state pressed tak lagi tertutup state focused di selector tombol
- Kelima selector latar tombol (`bg_btn_primary`, `bg_btn_stop`, `bg_btn_tv`, `bg_chip`, `item_focus_bg`) menulis item `state_focused` sebelum `state_pressed`; selector Android mengambil kecocokan pertama dan gaya tombol mematikan animator state, jadi di TV/STB menahan OK pada tombol yang sedang fokus tidak mengubah tampilan sama sekali sampai aksi sibuk berjalan — tidak ada umpan balik bahwa tekanan diterima.
- Blok item `state_pressed` kini mendahului `state_focused` di kelima berkas (isi tiap item tidak berubah, hanya urutannya); melengkapi Audit 341 yang membetulkan warna teks saat ditekan. Keadaan nonaktif tetap paling dulu sehingga tombol mati tidak menunjukkan gaya tekan/fokus.

## [Belum rilis] — Audit 357: petunjuk "update tersedia" tak lagi oranye pudar di mode siang
- Baris `updateHint` di kartu Server memakai `@color/accent` (#F57C00) untuk teks 11sp di atas kartu putih — kontrasnya hanya 2,70:1 sehingga mudah terlewat, padahal baris itu satu-satunya petunjuk di kartu bahwa ada update (di malam pasangan yang sama aman, 9,52:1, karena aksen malam #FFB74D di atas kartu gelap). `accent` dipakai di 19 tempat lain sehingga tidak bisa digelapkan global.
- Warna khusus baru `update_hint`: siang #BF5700 (4,59:1 di atas putih, sejajar pilihan `chip_busy` di Audit 343), malam tetap #FFB74D seperti aksen malam — tampilan malam tidak berubah sama sekali.

## [Belum rilis] — Audit 356: rebase lockout PIN tak lagi menulis disk sinkron di UI thread
- `PinActivity.cobaBuka()` memanggil `PinGate.sisaKunciMs(...)` dari utas UI, dan cabang rebase pasca-reboot (#68) di dalamnya menulis ulang kedua stempel kunci dengan `commit()` sinkron: tepat pada skenario 5× gagal + reboot + buka aplikasi, UI membeku sesaat sebelum pesan lockout tampil. PBKDF2-nya sendiri sudah benar di utas pekerja, dan jalur tulis `catatHasil` dari UI sudah dialihkan async — jalur baca rebase ini yang tertinggal.
- Penulisan rebase kini `apply()`: nilai baru terbaca seketika di proses yang sama (memori SharedPreferences diperbarui sinkron), dan bila proses mati sebelum penulisan asinkron sempat tuntas, stempel lama yang basi membuat cabang rebase menghitung ulang hasil yang sama pada buka berikutnya — sembuh sendiri tanpa mengubah semantik kunci. Pencatatan kegagalan di `catatHasil` tetap `commit()` karena penghitung keamanan itu wajib awet sebelum kembali.

## [Belum rilis] — Audit 355: Back dan Keluar di layar PIN tak lagi membatalkan verifikasi yang sedang berjalan
- Selama verifikasi PBKDF2 berjalan, `cobaBuka()` hanya menonaktifkan tombol Buka; Back (`onBackPressed`) dan tombol Keluar tetap memanggil `batalKeluar()` yang langsung `RESULT_CANCELED` + `finish()` — dan activity pemanggil ikut `finish()` — padahal pekerja tetap menyelesaikan verifikasi dan membuka grace. Pengguna yang refleks menekan Back saat layar terasa lambat di perangkat tua justru menutup aplikasi tepat ketika PIN-nya terbukti benar.
- `batalKeluar()` kini kembali lebih dulu selama `sedangPeriksa` berjalan (satu titik untuk kedua pemanggilnya: tombol Keluar dan `onBackPressed`); verifikasi selalu berumur detik dan hasilnya — sukses yang menutup layar dengan `RESULT_OK`, atau pesan gagal — tetap sampai ke pengguna.

## [Belum rilis] — Audit 354: LogActivity.toast kini berpagar siklus hidup seperti activity lain
- `LogActivity.toast()` memasang `Toast` lewat `ui.post` polos tanpa memeriksa keadaan activity — pola yang sama yang diperbaiki di SettingsActivity pada Audit 330 (#82), di berkas yang saat itu tidak ikut diperbaiki. try/catch memang mencegah crash `BadTokenException`, tetapi toast tetap dicoba tampil dengan token activity yang sudah mati dan perilakunya tidak konsisten dengan kelas lain (`MainActivity.uiHidup()`).
- Runnable toast kini kembali lebih dulu bila `isFinishing() || isDestroyed()`, menutup semua pemanggil di berkas ini sekaligus.

## [Belum rilis] — Audit 353: penanda tawaran update hanya ditulis bila toast benar-benar bisa tampil
- `kabariTersedia` di MainActivity (dan pola identiknya di SettingsActivity, berbagi kunci penanda yang sama) menulis penanda `KEY_TAWARAN_UPDATE` lebih dulu, baru memanggil `toast()` yang berpagar siklus hidup: bila activity sedang mati saat cek latar selesai, toast dibuang diam-diam tetapi versinya sudah ditandai pernah ditawarkan — toast "Update tersedia" untuk versi itu hilang permanen (tombol update punya jalur terpisah sehingga dampaknya terbatas pada toast).
- Penanda kini hanya ditulis sesudah pemeriksaan `uiHidup()` (MainActivity) / `isFinishing() || isDestroyed()` (SettingsActivity) lolos, tepat sebelum toast dipasang; activity yang mati melewatkan keduanya sehingga instance berikutnya yang hidup menawarkan lagi.

## [Belum rilis] — Audit 352: hasil pencarian log lama tak lagi tampil sesaat saat redaksi sedang berjalan
- Di `LogActivity.refreshLog`, permintaan kata kunci baru yang datang selagi redaksi lama berjalan ditolak `compareAndSet` SEBELUM `lastLogKey` diperbarui, sehingga cek anti-usang di callback tugas lama (`!kunci.equals(lastLogKey)`) menganggap hasil lama — tanpa saringan kata yang sedang tertulis — masih terbaru dan menampilkannya; baru terkoreksi pada tick berikutnya (sampai 1,5 detik).
- Penolakan CAS kini mengosongkan `lastLogKey` (idiom yang sama dengan jalur antrean-penuh di fungsi yang sama): callback lama pasti terbuang karena kuncinya tak lagi cocok, dan tick berikutnya menjalankan ulang permintaan terbaru begitu redaksi bebas — tanpa jebakan dedupe kunci yang membuat tampilan tersaring tak pernah muncul pada log yang diam.

## [Belum rilis] — Audit 351: FileShareProvider hanya membagikan berkas datar langsung di backups/ dan tls/
- Uji lokasi di `isShareable` hanya awalan jalur (`startsWith(data + "/backups/")`), jadi berkas bernama yang diizinkan di subfolder mana pun di bawah `backups/` ikut bisa dibagikan, padahal aplikasi hanya menulis berkas datar di sana — relevan terutama bila `data_dir` berada di storage bersama tempat pihak lain bisa menaruh berkas. Semua blok berbasis nama (db.sqlite3*, kunci privat, backup penuh, berkas sementara) memang tetap berlaku, tetapi batas lokasinya tidak seketat desainnya.
- Uji lokasi kini penolong murni `anakLangsung`: sisa jalur sesudah awalan folder tidak boleh mengandung pemisah lagi. Diterapkan ke `backups/` sesuai temuan dan ke `tls/` yang strukturnya identik (aplikasi juga hanya menulis berkas datar di sana). Diuji di `FileShareProviderTest`.

## [Belum rilis] — Audit 350: sapu plaintext sementara tetap bekerja sesudah jam perangkat dimundurkan
- `TgBackup.sapuTmpPlainBasi` menilai umur sisa berkas dekripsi (`vwtg-dec-*`/`vwtg-plain-*`, berisi zip DB plaintext) murni dari jam dinding: `kini - lastModified`, negatif dipatok 0, hapus hanya bila ≥ 10 menit. Bila jam STB dimundurkan, sisa dari proses yang mati tidak pernah menua dan tidak tersapu sampai jam dibetulkan — plaintext vault bertahan tanpa batas di direktori privat.
- Berkas yang stempel waktunya berada ≥ 10 menit di MASA DEPAN menurut jam sekarang kini ikut disapu: stempel seperti itu hanya mungkin lahir dari perubahan jam (mundur sesudah berkas dibuat, atau maju lalu mundur), dan berkas sementara plaintext tidak punya alasan sah berstempel masa depan. Operasi yang sedang berjalan tetap aman karena pemanggil mengecualikan berkasnya sendiri dan tugas data terserialisasi.

## [Belum rilis] — Audit 349: pesan berisi PIN salah di chat pribadi Telegram kini ikut dihapus
- Keputusan hapus pesan di `TgBot` mensyaratkan otorisasi untuk balasan sesi PIN dan perintah berargumen PIN, jadi PIN yang salah ketik — yang biasanya hanya beda satu digit dari PIN asli — tertinggal permanen di riwayat chat Telegram (cloud), padahal PIN basi dan PIN di grup selalu dihapus, dan teks balasan bot sendiri menyebut pesan PIN dihapus dari riwayat.
- Predikatnya kini fungsi murni `wajibHapusPesanPin`: pesan dihapus kapan pun ia memuat bahan PIN — balasan sesi yang terurai sebagai PIN (benar atau salah) atau perintah dengan argumen PIN (`pinTebakan(text) != null`) — selain jalur lama (basi, grup, terotorisasi). Balasan obrolan biasa dan perintah tanpa argumen PIN tetap disimpan. Diuji tabel kebenarannya di `TgBotTest`.

## [Belum rilis] — Audit 348: cadangan pemindaian checksum hanya menerima kandidat tunggal tanpa nama
- `pindaiHexDariBaris` di `Updater` punya lintasan cadangan tanpa syarat: bila tidak ada baris yang menyebut nama aset, hex 64 pertama dari baris mana pun diterima sebagai checksum aset itu. Selama berkas `.sha256` per-aset berisi satu baris bernama, ini tak terpakai — tetapi begitu format rilis berubah (berkas gabungan tanpa nama aset per baris), unduhan terikat ke hash aset lain: arah realistisnya semua update gagal checksum (fail-closed, aman tetapi update mati total sampai format diperbaiki), dan pengikatannya memang bukan lagi "hash milik aset ini".
- Lintasan cadangan kini hanya menerima bila tepat SATU baris di seluruh berkas memuat checksum yang sah (tak ada ambiguitas untuk dipilih — berkas per-aset memang diunduh dari URL bernama aset itu); dua kandidat tanpa nama mengembalikan null sehingga update ditunda sebagai "belum terbit", bukan terikat ke hash yang salah. Uji `pindaiHexDariBaris_utamakanNamaAsset` diperbarui mengikuti perilaku baru di commit yang sama.

## [Belum rilis] — Audit 347: sapu tmp basi tak lagi bisa menghapus tmp yang sedang diunduh
- `sapuTmpUnduhBasi` berjalan di awal tiap alur unduh tanpa kunci apa pun dan menilai umur tmp murni dari jam dinding (`currentTimeMillis - lastModified`, ambang 10 menit): sapu milik unduhan versi lain bisa menghapus tmp unduhan yang sedang berjalan — termasuk saat jam STB melompat maju lebih dari 10 menit di tengah unduhan (mis. sinkron NTP sesudah boot) sehingga tmp yang baru ditulis tampak basi. Unduhan korban gagal dengan aman (SHA-256 diverifikasi ulang saat pemasangan) tetapi harus diulang dari capaian terakhir.
- `unduhKeTmp` — corong tunggal ketiga alur (binary, shim, web-vault) — kini mendaftarkan path tmp-nya ke himpunan statik `TMP_UNDUH_AKTIF` selama seluruh percobaan termasuk jeda coba-lagi, dan `sapuTmpUnduhBasi` melewati path yang terdaftar. Sisa tmp dari proses yang mati tidak terdaftar (proses baru, himpunan kosong) sehingga tetap tersapu seperti sebelumnya.

## [Belum rilis] — Audit 346: tmp unduhan yang melebihi batas ukuran kini ikut dibuang
- `buangParsialRusak` di `Updater` hanya menghapus tmp unduhan gagal bila pesan kesalahannya memuat kata pemicu (parsial/kurang/terpotong/checksum/rusak/dll.), dan pesan `pesanMelebihiBatas` ("melebihi batas ukuran") tidak memuat satu pun — tmp sampai 150 MB tertinggal, dikecualikan dari sapu tmp basi karena bernama versi yang sedang dicari, dan ruangnya terus dihitung terpakai sehingga gerbang ruang 150 MB untuk update web-vault berikutnya bisa gagal terus sampai ada rilis lebih baru. Isi tmp yang melebihi batas memang tidak pernah bisa dilanjutkan (resume), jadi membuangnya adalah perlakuan yang benar seperti parsial rusak.
- Daftar pemicu kini memuat "melebihi batas".

## [Belum rilis] — Audit 345: paritas string values-in di verifikasi-patch tak lagi fail-open
- `cek_sinkron_terjemah` di `tools/verifikasi-patch.py` hanya membandingkan string Inggris↔Indonesia bila KEDUA sisi tak kosong (`if inggris and indonesia:`), sedangkan cabang plurals di fungsi yang sama sudah fail-closed (`or`). Mengosongkan seluruh `<string>` di `values-in/strings.xml` — atau menghapus berkasnya — membuat gerbang LOLOS dan aplikasi diam-diam jatuh ke UI Inggris penuh; tak ada jaring lain karena daftar error lint CI tidak memuat `MissingTranslation`. Kelas bug yang sama dengan Audit 303 (#45) pada paritas colors/layout.
- Syaratnya kini `if inggris or indonesia:` seperti plurals: satu sisi kosong/hilang langsung GAGAL dengan daftar string yang kurang. Terbukti eksekusi di salinan buang: berkas utuh LOLOS, string dikosongkan GAGAL, berkas dihapus GAGAL.

## [Belum rilis] — Audit 344: samaran log kini menutup email pengguna vault dan IP publik
- Penyamaran log digerbang `butuhSamaran()` (kata kunci rahasia atau substring IP privat) dan `samarkanLog()` tidak punya pola sama sekali untuk alamat email dan IP publik — padahal output proses Vaultwarden yang mengalir mentah ke buffer yang sama lazim memuat email pengguna vault pada login gagal dan IP klien pada baris request. Log yang diekspor ke Downloads, dibagikan, atau dikirim bot `/log` bisa membocorkan email pengguna vault (mis. anggota keluarga) dan IP publik mereka, utuh.
- `samarkanLog()` kini mengganti email menjadi `[email]` dan IPv4 publik (oktet tervalidasi 0–255) menjadi `[ip-publik]`, sesudah giliran IP privat agar alamat privat tetap `[ip-privat]`; `butuhSamaran()` membuka gerbang juga bila salah satunya ada (`adaEmail`/`adaIpPublik`). Semua pemanggil (ekspor, bagikan, crash log, delta UI, bot) memakai pasangan fungsi yang sama sehingga tertutup sekaligus. Diuji di `LogActivityTest`.

## [Belum rilis] — Audit 343: teks status putih terbaca lagi di banner Running (malam) dan Busy (dua mode)
- Teks status selalu putih, tetapi dua warna latarnya terlalu terang: `status_on` versi malam (#34A853) kontrasnya hanya 3,06:1 dan `chip_busy` (#E8710A, kedua mode) 3,09:1 — di bawah 4,5:1 untuk teks 13–14sp pada indikator status utama yang di TV/STB dibaca dari jauh (siang `status_on` #2E7D32 aman di 5,13:1).
- `status_on` malam disamakan dengan siang (#2E7D32, 5,13:1) dan `chip_busy` digelapkan ke #BF5700 (4,59:1) di kedua mode; keduanya juga dipakai titik status (`bg_dot_*`) yang ikut menggelap tanpa teks. Rasio dihitung dari luminansi relatif WCAG.

## [Belum rilis] — Audit 342: ikon Play/Stop mengikuti warna teks tombol, tak lagi pudar di mode malam
- Ikon `ic_play`/`ic_stop` pada tombol Start/Stop bawah di Settings selalu putih (`@color/white`), padahal di mode malam latar tombolnya biru muda `primary` (#90CAF9) dan teks di sebelahnya sengaja dibalik gelap — segitiga putih di atasnya nyaris menyatu (kontras sekitar 1,75:1; siang 5,75:1). Kedua ikon hanya dipakai pada tombol itu (layout + tukar runtime di `SettingsActivity`).
- `fillColor` kedua vektor kini `@color/btn_primary_text` sehingga ikon selalu sewarna label di sebelahnya di semua kombinasi: putih di biru siang dan di merah `status_off` siang, gelap di biru muda malam dan di merah muda `status_off` malam.

## [Belum rilis] — Audit 341: teks tombol Start/Stop tetap terbaca saat ditekan di mode malam
- Di mode malam teks tombol primer sengaja gelap (`btn_primary_text=#1A1A1A`) agar terbaca di atas biru muda `primary` (#90CAF9), tetapi latar keadaan ditekan pada `bg_btn_primary` (`primary_dark=#0D47A1`) dan `bg_btn_stop` (`btn_stop_pressed=#7A1A17`) gelap di kedua mode dan daftar warna teks tidak punya item `state_pressed`: selama tombol ditahan, tulisannya nyaris hilang (kontras sekitar 2,0:1 dan 1,65:1). Tombol yang sama berganti latar primer/stop saat runtime di MainActivity dan SettingsActivity, jadi satu daftar teks melayani keduanya.
- `btn_primary_text_state` kini punya item `state_pressed` berwarna putih — di atas kedua latar tekan itu kontrasnya 8,6:1 dan 10,6:1; mode siang tidak berubah karena teks tekannya memang sudah putih.

## [Belum rilis] — Audit 340: tekanan Buka dengan PIN kosong/pendek tak lagi dihitung tebakan gagal
- `PinActivity.cobaBuka()` mengirim masukan apa pun — termasuk kolom kosong — ke verifikasi PBKDF2, dan setiap hasil tak cocok menaikkan penghitung gagal `PinGate`: lima ketukan tak sengaja pada tombol Buka mengunci pengguna sah selama 5 menit tanpa satu pun tebakan PIN nyata. Jalur lain sudah konsisten menolaknya lebih dulu: pembuatan PIN mewajibkan minimal 4 karakter (`SettingsActivity`) dan bot Telegram menolak PIN < 4 sebelum verifikasi tanpa menghitung gagal.
- `cobaBuka()` kini menolak masukan kurang dari 4 karakter dengan pesan `R.string.pin_min` yang sama seperti di Settings, sebelum verifikasi dimulai dan tanpa menyentuh penghitung gagal maupun lockout.

## [Belum rilis] — Audit 339: hasil Salin/Bagikan log tak lagi hilang karena rotasi selama penyamaran
- Di `LogActivity`, penulisan clipboard (Salin) dan pembukaan lembar bagikan (Bagikan) sama-sama berada di dalam callback `ui.post` berpagar `isFinishing()/isDestroyed()`: rotasi selama penyamaran log besar di utas latar (buffer sampai 300.000 karakter) membuang hasil kerjanya sendiri tanpa indikasi gagal — berbeda dengan `exportLogTxt()` di berkas yang sama yang menulis berkas di utas latar sehingga hasilnya selamat.
- Salin kini menulis clipboard di utas pekerja (`salinBersihOtomatis` memang berbasis konteks aplikasi dan aman dari utas mana pun); callback UI tinggal melaporkan hasilnya. Bagikan kini menyimpan teks tersamar ke slot statik `bagikanTertunda` yang dikonsumsi tepat satu kali oleh instance mana pun yang hidup (`tampilBagikanTertunda()`, dipanggil dari callback pekerja dan dari `onResume` sesudah pagar PIN); slot dibersihkan bila activity ditutup dengan niat selesai (`isFinishing()`), sehingga tidak meledak sendiri pada kunjungan berikutnya.

## [Belum rilis] — Audit 338: backup otomatis sesudah unduh web-vault tak lagi hilang karena rotasi
- Pada jalur "Unduh & mulai", server dinyalakan dari utas pekerja dengan konteks aplikasi sehingga selamat dari rotasi, tetapi `maybeAutoBackup()` hanya dipanggil di dalam callback UI berpagar `isFinishing()/isDestroyed()`: rotasi selama unduhan (sekitar 35 MB) membuat backup Telegram otomatis untuk start itu terlewat tanpa catatan, dan pengguna tidak akan menekan Start lagi karena server sudah menyala.
- `maybeAutoBackup()` kini dipanggil di utas pekerja tepat sesudah `ServerService.start(...)` berhasil — jembatan UI-nya memang sudah berpagar dan aman dari utas mana pun (toast lewat `ui.post` + `uiHidup()`, catatan lewat `catatLog` bersama), dan `TgBackup.maybeAutoBackup` sendiri menolak jalan ganda (prefs `tg_auto`, jatah sekali sehari, `tugasDataJalan()`). Callback UI tinggal melaporkan hasil unduhan seperti sebelumnya.

## [Belum rilis] — Audit 337: tekanan Start yang terpotong rotasi saat cek port kini diteruskan instance baru
- Kelanjutan Start sesudah cek port latar (`lanjutStart()` dan dialog port) hanya hidup di callback `ui.post` berpagar `isFinishing()/isDestroyed()` di `saveAndStart()`, tanpa penanda tertunda yang disimpan: rotasi selama `bereskanServerYatim()`/`isPortBusy()` berjalan membuang perintah Start diam-diam — server tidak jadi menyala dan pengguna harus menebak untuk menekan Start lagi.
- `saveAndStart()` kini menandai `startTertundaPort` sebelum pekerjaan latar dimulai dan membersihkannya saat callback tiba atau antrean menolak; penanda itu disimpan lewat `onSaveInstanceState` (pola yang sama dengan `STATE_MULAI_ULANG_IZIN`), dan instance hasil buat-ulang yang memulihkannya menjalankan ulang `saveAndStart()` sekali dari `onResume`. Jeda/resume biasa pada instance yang sama tidak memicu pengulangan.

## [Belum rilis] — Audit 336: PinActivity memeriksa ulang grace selama tampil, rotasi tak lagi menahan PIN yang benar
- Perbaikan Audit 329 (#81) membuka grace di utas pekerja, tetapi `PinActivity` sendiri hanya memeriksa `dalamGraceBersama()` sekali di `onCreate`: instance hasil rotasi terlanjur melewati pemeriksaan itu selagi verifikasi PBKDF2 instance lama masih berjalan, dan tidak ada mekanisme yang menutupnya ketika grace kemudian terbuka — callback satu-satunya milik instance lama dan dibuang. Pengguna melihat formulir PIN utuh lagi seolah belum memasukkan PIN, dan menekan Back menutup activity pemanggil (`finish()` pada hasil selain `RESULT_OK`).
- Pemeriksaan awal kini diekstrak ke `lolosGrace()` (dipakai `onCreate` seperti sebelumnya), dan selama activity tampil sebuah pemeriksa ringan (`pemeriksaGrace`, tiap 500 ms, dimulai di `onResume` dan dihentikan di `onPause`) memanggilnya ulang: begitu grace terbuka — oleh pekerja instance mana pun — layar PIN menutup sendiri dengan `RESULT_OK`. Pemeriksaan berhenti begitu activity selesai/jeda, dan biaya per putaran hanya baca prefs + keadaan grace statik.

## [Belum rilis] — Audit 335: pidOf kini menemukan PID anak lewat /proc di API 21–25
- Perbaikan Audit 313 (#65) mengecualikan proses anak yang sedang jalan dari pembersihan yatim lewat `pidOf()`, tetapi `pidOf()` hanya memakai refleksi `Process.pid()` yang baru ada di API 26+; di API 21–25 (perangkat sasaran utama) ia selalu mengembalikan -1, sehingga `bereskanServerYatim` dan `killStaleVaultwarden` bisa membunuh server yang sedang jalan sebagai yatim: watcher menafsirkannya sebagai crash, jatah restart terbakar, dan vault bisa mati sampai intervensi manual.
- Bila metode `pid()` tak tersedia, `pidOf()` kini jatuh ke `pidAnakDariProc()`: proses yang masih hidup (`exitValue()` melempar `IllegalThreadStateException`) dicari di `/proc` sebagai proses server yang induk langsungnya adalah proses aplikasi ini (pengurai murni `ppidDariStat`, ppid == myPid) — yatim sejati yang induknya sudah mati tetap dibersihkan seperti sebelumnya. `ppidDariStat` diuji di `ServerServiceTest`.

## [Belum rilis] — Audit 334: pengingat uji di verifikasi-patch kini mengenali metode non-statis
- `cek_uji_baru` di `tools/verifikasi-patch.py` hanya mencocokkan deklarasi satu baris berpola `(public)? static`, jadi metode instan dan metode paket-privat baru — yang justru paling mungkin berisi logika murni — lolos dari pengingat "uji logika murni baru" tanpa bunyi apa pun.
- Pola kini menerima semua pengubah akses/`final`/`synchronized`/`default` dengan tipe pengembalian wajib berkarakter kata (bukan sekadar spasi indentasi); konstruktor disaring lewat nama kelas dari nama berkas + deklarasi `class`/`interface`/`enum` di diff yang sama, metode `@Override` (sebaris atau baris sebelumnya) dan deklarasi tanpa badan (`;`) dilewati, dan teks peringatan digeneralisasi menjadi "Metode baru". Terbukti dengan diff sintetis: metode instan/paket-privat/tipe multi-kata terdeteksi, konstruktor/`@Override`/panggilan metode tidak.
## [Belum rilis] — Audit 333: dispatch workflow dari cabang selain main tak lagi gagal di Upload artifact
- Langkah "Publish APK ke rilis" di `build-apk.yml` bersyarat `github.ref == 'refs/heads/main'`, dan salinan APK bernama rilis (`tasirin-vaultwarden-host-armeabi-v7a.apk`) hanya dibuat di langkah itu; langkah "Upload APK artifact" tanpa syarat dengan `if-no-files-found: error` lalu selalu gagal pada `workflow_dispatch` dari cabang lain — run merah padahal build-nya sendiri berhasil.
- Langkah baru tanpa syarat "Siapkan salinan APK bernama rilis" membuat salinan itu sebelum Publish (di `main`, Publish menimpanya dengan berkas yang sama persis, lalu alur rilis tak berubah); Upload artifact kini menemukan berkasnya di semua ref.

## [Belum rilis] — Audit 332: langkah Verify APK contents kini mewajibkan ketiga isi APK
- Langkah "Verify APK contents" di `build-apk.yml` memverifikasi isi APK dengan satu `grep -E 'vw_version|classes.dex|AndroidManifest'` — operator ATAU: langkah lolos selama salah satu dari tiga pola cocok, jadi APK yang hilang `vw_version.txt`-nya (versi server tak terbaca) tetap hijau dan terbit.
- Daftar isi kini ditangkap sekali dan tiap berkas wajib (`vw_version.txt`, `classes.dex`, `AndroidManifest.xml`) diperiksa sendiri dengan `grep -q`; yang hilang menggagalkan langkah dengan pesan `::error::` yang menyebut berkasnya.

## [Belum rilis] — Audit 331: tombol yang dinonaktifkan saat sibuk kini terlihat nonaktif
- Selector latar `bg_btn_tv`/`bg_btn_primary` (dan `bg_btn_stop`) tak punya keadaan `state_enabled="false"` dan warna teks gaya `TasirinBtn` konstan, jadi tujuh tombol Settings yang dimatikan `setBusy` (dan tombol Start/Stop MainActivity) tampak persis seperti tombol aktif selama operasi latar berjalan — pengguna bisa mengira aplikasi macet atau menekan berulang kali.
- Ketiga selector kini punya item pertama `state_enabled="false"` berlatar warna baru `btn_disabled_bg`, dan warna teks `TasirinBtn`/`TasirinBtnPrimary` diganti ColorStateList baru (`res/color/btn_text_state.xml`, `res/color/btn_primary_text_state.xml`) yang beralih ke `btn_disabled_text` saat nonaktif. Warna baru ditambahkan sinkron di `values` dan `values-night`.

## [Belum rilis] — Audit 330: toast SettingsActivity kini memakai pagar siklus hidup uiPost
- `SettingsActivity.toast()` memasang `Toast` lewat `ui.post` polos tanpa memeriksa keadaan activity, padahal kelas yang sama sudah punya `uiPost` berpagar `isFinishing()/isDestroyed()` dan `MainActivity.toast` memakai `uiHidup()`. Toast dari callback latar (cek auto-update dan auto-backup Telegram sama-sama meneruskan ke `toast()` ini) yang tiba sesudah activity ditutup bisa menempel pada window yang sudah mati.
- `toast()` kini meneruskan ke `uiPost` — satu baris, menutup semua pemanggil sekaligus; toast yang terlambat sesudah activity selesai dibuang diam-diam seperti callback UI lain di kelas ini.

## [Belum rilis] — Audit 329: rotasi saat verifikasi PIN tak lagi membuang hasil yang berhasil
- `PinGate.bukaKunciBersama()` hanya dipanggil di dalam `ui.post` sesudah pemeriksaan `isFinishing()/isDestroyed()`, jadi rotasi di tengah verifikasi PBKDF2 (yang memang lambat di perangkat tua) membuang hasil sukses: grace tak pernah dibuka dan PIN yang sudah benar harus diketik ulang, padahal `catatHasil` sudah mencatatnya.
- Grace kini dibuka di utas pekerja tepat sesudah `catatHasil` bila PIN cocok, sebelum callback UI dipasang; callback UI tinggal `setResult(RESULT_OK)` + `finish`. Bila activity lama hancur karena rotasi, instance barunya langsung lolos lewat pemeriksaan `dalamGraceBersama()` di `onCreate` seperti jalur normal.

## [Belum rilis] — Audit 328: kegagalan trust tambahan HttpsCompat kini memberi sinyal di log aplikasi
- Bila rantai aset `certs/github-chain.pem` gagal dimuat/diurai, `HttpsCompat.apply` hanya menulis satu baris `Log.w` lalu lanjut dengan factory bawaan — himpunan trust diam-diam berbeda dari desain (terutama di Android 5/6) dan gejala akhirnya kegagalan handshake generik yang sulit didiagnosis.
- Kegagalan kini dicatat lewat `catatGagalTrust`: tetap ada di logcat, dan kegagalan yang baru/berbeda juga ditulis sekali ke log aplikasi (`ServerService.catatLog`, terlihat di UI log dan ekspor log) lewat penolong murni `sinyalGagalTrust`; pesan yang sama berulang tidak membanjiri log. Keadaan gagal terakhir bisa dibaca lewat `gagalTrustTerakhir()`. Perilaku koneksi tak berubah (verifier bawaan tetap memvalidasi) — yang ditambah adalah sinyalnya. Diuji di `HttpsCompatTest`.

## [Belum rilis] — Audit 327: penanda fallback binary kini memakai jam dinding, bukan elapsedRealtime
- `KEY_BIN_FALLBACK_AT` disimpan persisten di SharedPreferences tetapi nilainya `elapsedRealtime` yang berumur sejak boot: sesudah reboot, begitu uptime boot baru masuk lagi ke rentang lama, fallback yang sudah kedaluwarsa dianggap segar kembali (bisa sehari kemudian) dan cek update memakai fallback basi.
- Stempel kini ditulis dan dibaca sebagai jam dinding (`System.currentTimeMillis`, parameter `fallbackBinaryBerlaku` diganti nama menjadi `kiniWall`; logikanya tak berubah dan tetap menolak jam yang mundur). Stempel elapsed warisan versi lama otomatis tak berlaku lagi karena selisihnya terhadap jam dinding jauh melebihi jendela 6 jam — migrasi fail-closed tanpa langkah khusus.

## [Belum rilis] — Audit 326: openFile memverifikasi descriptor yang benar-benar terbuka
- `FileShareProvider.openFile` memeriksa path kanonis + symlink induk lalu membuka ulang berdasarkan string path; penukar symlink yang menang balapan di antara pemeriksaan terakhir dan `open` bisa membuat descriptor menunjuk berkas yang tak pernah lolos `isShareable` (temuan ini berstatus dugaan di audit putaran-5 — jendelanya sempit dan butuh aplikasi jahat lokal).
- Sesudah descriptor terbuka, jalur nyatanya dibaca dari `/proc/self/fd/<fd>` (`jalurFdTerbuka`) dan wajib persis sama dengan path kanonis yang lolos pemeriksaan (`jalurFdCocok`, akhiran ` (deleted)` ditoleransi); beda → descriptor ditutup dan akses ditolak. Bila readlink tak tersedia, perilaku lama dipertahankan. Penolong murni `jalurFdCocok` diuji di `FileShareProviderTest`.

## [Belum rilis] — Audit 325: FileShareProvider tak lagi boleh membagikan berkas backup penuh
- Daftar boleh-bagi untuk folder data hanya menyaring nama/akhiran, jadi `backup-telegram-*.zip` dan `db-backup-*.zip` di folder `backups/` lolos — padahal isinya snapshot `db.sqlite3` + config utuh, dan `db.sqlite3` lepas justru ditolak eksplisit oleh provider yang sama. Kelonggaran laten: belum ada alur yang menerbitkan grant ke folder itu, tetapi satu grant keliru langsung membocorkan seluruh vault.
- Cabang folder-data `isShareable` kini menolak nama berkas cadangan penuh (`backup-*`/`db-backup-*`, tak peka huruf, lewat penolong murni `berkasBackupPenuh` yang diuji); ekspor yang sah tak berubah (`ca.pem`/`cert.pem` dari `tls/`, `app-config-*.json(.enc)` tetap boleh).

## [Belum rilis] — Audit 324: pasang-hook hanya melewati cadangan bila hook lama persis hook standar
- Uji kepemilikan hook memakai substring: hook asing apa pun yang kebetulan memuat baris `exec bash scripts/cek-cepat.sh` (mis. sebagai salah satu langkah tim) dianggap "hook kita" lalu ditimpa total tanpa cadangan — langkah tambahannya hilang diam-diam.
- Skrip kini menulis hook standar ke berkas sementara lebih dulu dan hanya melewati cadangan bila isi hook lama **persis sama** (`cmp -s`) dengan standar; isi lain apa pun dicadangkan ke `.bak` (kegagalan menyalin tetap membatalkan penimpaan). Teruji tiga skenario di salinan terpisah: tanpa hook, hook standar identik (tanpa cadangan), dan hook asing berpenanda (tercadangkan utuh).

## [Belum rilis] — Audit 323: gerbang paritas layout-land tak lagi buta per-berkas
- Kedua gerbang hanya membandingkan ID bila berkas `layout-land` ada, jadi menghapus satu-satunya varian landscape (`layout-land/activity_pin.xml`) lolos hijau penuh — layar PIN di TV/STB (selalu landscape) akan diam-diam jatuh ke layout portrait. Menghapus seluruh foldernya memang tertangkap, tetapi per-berkas tidak (pola yang sama dengan celah paritas warna dulu).
- `verifikasi-patch.py` dan `cek-cepat.sh` kini punya daftar berkas layout wajib berpasangan (`activity_pin.xml`): hilang di `layout/` atau `layout-land/` = gagal di kedua gerbang. Teruji di salinan terpisah: berkas dihapus → kedua gerbang GAGAL dengan pesan jelas; repo utuh tetap LOLOS.

## [Belum rilis] — Audit 322: tombol Cek update di About kini membuka seksi Perawatan dulu
- `fokuskanTombolUpdate` langsung memfokuskan + menggeser ke tombol update yang tinggal di dalam seksi Perawatan; bila seksi itu sedang tertutup (status persisten `sec_buka_rawat`), badannya `GONE` sehingga fokus gagal dan gesernya tak bermakna — dialog About tertutup tanpa hasil yang terlihat.
- Fungsi itu kini membuka seksi Perawatan lebih dulu (simpan preferensi + `terapkanSeksi` + `perbaikiRantaiFokus`, pola yang sama dengan `loncatKeSeksi`) sebelum meminta fokus dan menggeser layar.

## [Belum rilis] — Audit 321: uji asap binary kini di dalam kunci unduhan
- Kunci unduhan per-berkas hanya menaungi badan `unduhKeTmp`; sesudahnya `downloadBinaryInner` memeriksa berkas, mengeksekusinya sebagai uji asap (`detectVersion`), dan menghapusnya di luar kunci — padahal nama tmp per-versi sama antar-jalur (auto-update, Start, bot). Unduhan kedua untuk versi yang sama bisa me-reset/menghapus tmp tepat saat unduhan pertama mengeksekusinya, atau sebaliknya, sehingga update gagal acak (integritas tetap aman karena `pasangHasilUnduh` meng-hash ulang di dalam kunci).
- Seluruh rangkaian unduh → periksa → uji asap → pasang di `downloadBinaryInner` kini dibungkus `synchronized` pada kunci tmp yang sama (referensi terhitung `kunciUnduh`/`lepasKunciUnduh`; kunci bawaannya sendiri di `unduhKeTmp`/`pasangHasilUnduh` bersifat reentrant untuk utas yang sama). Transformasi dibuktikan setara dengan pembanding pernyataan: tidak ada pernyataan lama yang hilang/berubah selain dua deklarasi yang diangkat keluar blok.

## [Belum rilis] — Audit 320: unduhan binary/shim/web-vault kini berbatas ukuran
- Loop unduhan `salinSambilHash` menulis terus sampai stream berakhir: tanpa `Content-Length` tidak ada pemeriksaan sama sekali, dan dengan `Content-Length` kegagalan baru dinyatakan sesudah semua byte tertulis — aset salah/korup atau respons raksasa bisa memenuhi disk STB (yang sama dengan folder data Vaultwarden) sebelum SHA-256 sempat menolaknya.
- Kini ada batas per jenis unduhan (`batasUnduhUntuk`: binary 128 MB, shim 8 MB, web-vault 150 MB — jauh di atas ukuran aset sah). Panjang yang diketahui melebihi batas ditolak sebelum penulisan, dan penghitung berjalan diperiksa tiap potongan (`unduhanMelebihiBatas`) sehingga unduhan tanpa panjang pun berhenti tepat sesudah batas terlampaui; galatnya sengaja tidak memenuhi syarat coba-lagi. Diuji di `UpdaterTest`.

## [Belum rilis] — Audit 319: restore backup tanpa kunci privat tak lagi mencampur pasangan TLS dua perangkat
- Zip backup selalu memuat `tls/ca.pem` + `tls/cert.pem`, tetapi kunci privat (`ca-key.pem`/`key.pem`) hanya ikut pada backup terenkripsi. Restore di perangkat lain memasang sertifikat perangkat asal menimpa milik perangkat tujuan sementara kuncinya tertinggal: pasangan TLS tercampur, CA efektif berganti diam-diam, dan semua klien harus memasang CA baru tanpa peringatan apa pun (server memang pulih sendiri lewat validasi pasangan di `TlsCert`, tetapi sesudah berganti).
- Kedua jalur restore (Telegram `TgBackup` dan lokal `SettingsActivity`) kini hanya memasang TLS dari backup bila paketnya lengkap (`tlsRestoreLengkap`: kedua kunci privat ikut); paket tak lengkap dibuang dari staging, TLS perangkat tujuan dipertahankan, dan pemilik diperingatkan — lewat pesan hasil restore di Telegram dan log di Settings.

## [Belum rilis] — Audit 318: sisa penulisan non-atomik restore/enkripsi/dekripsi ditutup
- Ekstraksi restore (jalur Telegram di `TgBackup` dan jalur lokal di `SettingsActivity`) menulis entri TLS langsung ke berkas hidup tanpa fsync — listrik padam di tengah penulisan meninggalkan sertifikat/kunci separuh tertulis, dan rollback dari potret memori tak menolong untuk kerusakan di cakram. Enkripsi/dekripsi juga masih memakai pola hapus-dulu-baru-rename: ada jendela berkas tujuan hilang bila rename kedua gagal.
- Entri TLS kini ditulis ke staging `tls/*.restore-tmp` + fsync lebih dulu dan baru dipasang atomik per berkas lewat `ServerService.gantiAtomik` sesudah DB restore terpasang (`pasangTlsRestore`); semua jalur gagal membersihkan staging (`hapusStagingTls`) sehingga TLS hidup tak tersentuh restore yang gagal. `tulisTerenkripsi` dan `decryptToFile` kini juga memasang hasilnya lewat `gantiAtomik` (berkas lama dicadangkan dan dikembalikan bila pemasangan gagal), bukan hapus-dulu. Diuji: penamaan staging TLS di `TgBackupTest`.

## [Belum rilis] — Audit 317: pasang DB restore kini atomik, WAL lama aman sampai DB baru terpasang
- `pasangDbRestoreAtomik` menghapus WAL/SHM DB lama lebih dulu lalu memakai `renameTo` polos: bila rename gagal, DB lama diklaim "dipertahankan" padahal WAL-nya (yang bisa berisi transaksi terakhir) sudah dibuang, dan lemparan `IOException`-nya melewati pengembalian TLS di `restoreFromZipIsi` karena jalur itu hanya menangani nilai balik galat.
- Pemasangan kini memakai `ServerService.gantiAtomik` (DB lama dicadangkan ke `.bak`, staging di-fsync, rename, direktori di-fsync; gagal pasang mengembalikan DB lama utuh termasuk WAL-nya), WAL/SHM lama baru dibuang sesudah DB baru terpasang, dan `restoreFromZipIsi` menangkap lemparan dari pemasangan untuk membersihkan staging + mengembalikan TLS sebelum melempar ulang.

## [Belum rilis] — Audit 316: lockout PIN tak lagi runtuh oleh majukan jam + restart
- Lockout ditahan dua jam terpisah (dinding untuk restart, `elapsedRealtime` untuk majukan jam), tetapi stempel elapsed sisa boot lama selalu dibaca "sisa > 5 menit → tidak terkunci". Gabungan majukan jam dinding melewati batas kunci lalu restart membuat kedua sisi 0 bersamaan, jadi tebakan PIN baru langsung diterima — laju tebakan naik dari 5 per 5 menit menjadi ±5 per siklus restart.
- `PinGate.sisaKunciMs` kini mengenali stempel elapsed basi (sisa melebihi `KUNCI_MS` hanya mungkin dari boot lama, karena di boot yang sama sisa tak pernah melebihinya) lewat `PinCrypto.stempelElapsedBasi`, lalu memasang ulang kedua stempel secara konsisten untuk boot berjalan: sisa mengikuti jam dinding bila masih ada, dan kunci penuh 5 menit bila jam dinding sudah dimajukan (`sisaKunciSesudahReboot`) — fail-closed tetapi selalu berhingga, bukan kunci permanen. Diuji di `PinCryptoTest`.

## [Belum rilis] — Audit 315: Start dari Settings tak lagi terkunci proses yatim di port
- Perbaikan Audit 312 (#50) hanya memasang pola "bersihkan yatim sebelum cek port" di `MainActivity.saveAndStart`; jalur kembarnya `SettingsActivity.saveAndStart` langsung memanggil `isPortBusy`, jadi Start dari Settings ditolak dialog "Port sedang dipakai proses lain" selamanya bila ada proses vaultwarden yatim memegang port — padahal Start yang sama dari layar utama berhasil.
- `saveAndStart` Settings kini memanggil `ServerService.bereskanServerYatim()` lebih dulu di dalam tugas latar yang sama, persis pola MainActivity, sebelum `isPortBusy` diperiksa.

## [Belum rilis] — Audit 314: restore via Telegram kini membuat salinan pengaman DB lama
- `TgBackup.restoreFromZipIsi` (jalur `/restore` Telegram dan restore Telegram dari Settings) memasang DB hasil restore tanpa pernah menyalin DB yang berjalan, jadi sekali restore lolos, vault versi lama hilang permanen — padahal jalur restore lokal membuat `db-backup-…-pre.sqlite3` dan retensi `KEEP_PRE_RESTORE` memang disiapkan untuk berkas itu.
- Jalur Telegram kini meniru jalur lokal persis: sesudah server berhenti dan staging lama dibersihkan, DB lama di-checkpoint lalu disalin ke `backups/db-backup-<stempel>-pre.sqlite3`, retensi dibersihkan, dan nama salinan pengaman disebut dalam pesan hasil restore agar pemilik tahu berkas pemulihnya ada.

## [Belum rilis] — Audit 313: bereskanServerYatim tak lagi membunuh server yang sedang aktif
- `bereskanServerYatim` menganggap semua proses `vaultwarden-*` selain proses aplikasi sebagai yatim, termasuk anak yang sedang dilacak field `process` dan sedang melayani. Server sehat bisa kena SIGKILL mendadak (mis. server baru yang lahir di celah antar-percobaan pembersihan), tulisan DB terpotong, dan restore ikut batal.
- `pidYatimServer` kini juga mengecualikan PID anak yang berjalan, `bereskanServerYatim` mengambil PID anak segar dari `process` di tiap pindai dan memeriksa ulang tepat sebelum membunuh (meniru pengecualian ganda `killStaleVaultwarden`), dan cek akhir juga melewati anak yang berjalan. Uji `pidYatimServer` diperbarui untuk mengunci pengecualian ini.

## [Belum rilis] — Audit 311: startServer dan handleCommand dipecah per tanggung jawab
- `ServerService.startServer` (320 baris) mencampur pemilihan binary, shim, smoke test, pembersihan proses yatim, cek port, TLS, env proses, start, notifikasi, dan pemasangan thread monitor; salah urutan (mis. cek port sebelum membersihkan proses yatim) mudah terulang.
- Kini `startServer` hanya mengurutkan langkah melalui `SesiStart`: `siapkanFolderData`, `pilihBiner`, `siapkanShim`, `siapkanProsesDanPort`, `siapkanTls`, `bangunEnv`, `jalankanProses` (+ `umumkanJalan`). Seluruh langkah tetap di dalam satu `try` yang sama seperti sebelumnya, jadi penanganan galat start tidak berubah.
- `TgBot.handleCommand` (347 baris) kini dispatcher kecil: `perintahKontrol`, `perintahCadangan`, `perintahSertifikat`, `perintahUpdate` (+ `unduhBinerDariBot`, `unduhWebVaultDariBot`, `balasHasilUnduh`), `perintahInfo`, dan `/help`. Nilai balik "perintah berbahaya terotorisasi" dipertahankan per cabang (mis. `/status` tanpa PIN tetap `false`, `/restart` yang ditolak tetap `true`).

## [Belum rilis] — Audit 311: SettingsActivity dipecah jadi metode bernama
- `SettingsActivity.onCreate` (460 baris) dan `refreshFromService` (269 baris) mencampur banyak tanggung jawab dalam satu metode, sehingga perubahan kecil (mis. menambah pengaturan) mudah memicu efek samping dan sulit ditinjau.
- Dipecah **tanpa mengubah urutan/perilaku**: `muatTampilan`, `pasangAksi`, `muatPreferensi`, `pasangSeksi`, `pasangListener`, `mulaiRefresh` untuk `onCreate`; `tampilkanSibukUnduhan`, `perbaruiStatusServer`, `perbaruiPenandaUbah`, `perbaruiPetunjukUpdate`, `teksVersiPerangkat`, `perbaruiKartuInfo`, `perbaruiUrlLokal`, `perbaruiBaterai` untuk `refreshFromService`. Lima tombol yang sebelumnya lokal (`showAdminBtn`, `showTgBtn`, `showPassBtn`, `randomAdminBtn`, `copyLoopbackBtn`) menjadi field agar bisa dipakai lintas metode.

## [Belum rilis] — Audit 311: hapus sendMessageKb yang tak dipakai
- `TgBackup.sendMessageKb(ctx, text, markup)` publik tapi tak pernah dipanggil (yang dipakai `sendMessageKbKe` dengan chat id), sehingga pembaca kode bisa salah pilih varian dan pesan tak terkirim ke chat yang dituju.
- Metode dihapus; `kirimPesan` tetap dipakai `sendMessage`. Nama dicatat di `tools/bekas-hapusan.txt` agar tak diciptakan ulang.

## [Belum rilis] — Audit 311: verifikator mengenali kelas manifest sub-paket
- `cek_manifest` mengambil segmen pertama nama komponen setelah namespace, jadi `.ui.MainActivity` dicari sebagai `ui.java` dan komponen sah dilaporkan "Komponen manifest tanpa kelas" — push ke sub-paket baru pasti gagal.
- Nama relatif kini dipetakan utuh ke direktori (`ui/MainActivity.java`) untuk bentuk `.Nama` maupun nama absolut; pesan galat ikut menampilkan path relatif. Diuji dengan fixture: kelas sub-paket yang ada lolos, yang hilang tetap terdeteksi.

## [Belum rilis] — Audit 311: distribusi Gradle wrapper dipin SHA-256
- `gradle-wrapper.properties` hanya mengandalkan `distributionUrl` + `validateDistributionUrl`, sehingga zip Gradle yang dieksekusi di runner (memegang `GITHUB_TOKEN` dan secret keystore) tidak diperiksa integritasnya bila layanan distribusi/proxy CI disusupi.
- Ditambahkan `distributionSha256Sum=bd7110...f3531` untuk `gradle-8.14.3-bin.zip`, dicocokkan dari dua endpoint resmi Gradle (`distributions/...sha256` dan `versions/all`). Bila versi Gradle dinaikkan, hash wajib diperbarui bersamaan.

## [Belum rilis] — Audit 311: unggahan Telegram menghormati HTTP 429
- `uploadTelegram` (sendDocument untuk backup/CA) langsung mengubah HTTP 429 menjadi `IOException`, sedangkan `kirimSatuPesan` sudah membaca `retry_after` lalu menunggu. Saat backup manual + terjadwal + `/cabackup` berdekatan, Telegram membatasi laju dan backup hari itu tidak terkirim.
- Jalur unggah kini mencoba ulang sekali setelah `jedaRetryAfterMs` (log "[tg] Unggah kena limit Telegram ..."), lalu tetap melempar galat bila percobaan kedua masih 429 — tidak ada pengulangan tanpa batas.

## [Belum rilis] — Audit 311: bundel CA binary dipatok ke URL bertanggal
- `build-binary.yml` mengunduh `https://curl.se/ca/cacert.pem` yang isinya berganti tiap Mozilla memperbarui bundel, padahal hash-nya hard-code: begitu curl.se menerbitkan bundel baru, build binary (termasuk jadwal bulanan) merah sampai hash diperbarui manual, dan binary Vaultwarden baru tak terbit.
- URL diganti arsip bertanggal `https://curl.se/ca/cacert-2026-10-20.pem` (isinya tetap, hash `796f1389...` sudah diverifikasi lokal), jadi `sha256sum -c` tak lagi bergantung pada perubahan harian. Bila suatu saat bundel perlu diperbarui, pesan galat CI menunjuk `curl.se/docs/caextract.html` untuk memilih tanggal + hash baru.

## [Belum rilis] — Audit 311: unit test kebijakan retry unduhan benar-benar jalan
- `UpdaterTest.bolehCobaLagiUnduh_hanyaGalatJaringan()` tidak ber-`@Test`, jadi JUnit melewatinya diam-diam. Isinya mengunci kebijakan penting: jaringan/timeout/HTTP 500 boleh dicoba lagi, sedangkan HTTP 404 dan "build belum tersedia" tidak.
- Anotasi `@Test` ditambahkan (test kini ikut CI). Bila `bolehCobaLagiUnduh` berubah sehingga retry 404 atau berhenti retry timeout, test langsung merah.

## [Belum rilis] — Audit 311: chat @username benar-benar butuh PIN
- Saat chat bot diisi `@username`, UI/log memperingatkan "diperlakukan seperti grup (selalu butuh PIN)", tetapi `authDangerous` hanya menolak berdasarkan `idRuang < 0`. Dari chat pribadi (ID positif) dengan PIN app **nonaktif**, `/status`, `/log`, `/crashlog`, `/ca`, dan `/cabackup` lolos tanpa PIN karena `berbahaya == false` — username yang kedaluwarsa/diklaim ulang bisa membaca status, log, path, versi, dan URL.
- Aturan PIN-mati kini `wajibPinSaatPinMati(berbahaya, chatAnggapGrup)`: perintah baca ber-PIN juga ditolak bila konfigurasi chat memakai username/tak-terurai (fail-closed), bukan hanya saat pengirim benar-benar grup. Dengan PIN aktif perilakunya tetap (verifikasi PIN). README tiga bahasa menjelaskan konsekuensinya.

## [Belum rilis] — Audit 311: zip backup di-fsync & dipasang atomik
- `createBackupZip` menulis zip ke `.tmp` lalu langsung `renameTo`, tanpa `getFD().sync()`, dan menghapus berkas tujuan lebih dulu. Bila listrik putus tepat setelah backup "selesai", zip baru bisa kosong/terpotong sementara nama lamanya sudah hilang — tidak ada backup yang bisa direstore. Jalur `.enc` di berkas yang sama sudah benar.
- Zip kini ditutup dengan `finish()` + `flush()` + `fsync` sebelum dipasang lewat `ServerService.gantiAtomik` (rename atomik, berkas lama dicadangkan `.bak` dan dipulihkan bila rename gagal, direktori ikut di-fsync). Sisa `.tmp` tetap dibuang saat gagal.

## [Belum rilis] — Audit 311: trust anchor TLS hanya dari aset APK
- `Updater.segarkanTrustAnchor` mengunduh `github-chain.pem` dari rilis lalu memasangnya ke `files/certs/` (dibaca `HttpsCompat`) walau checksum `.sha256` gagal diambil; validasi isi hanya "minimal satu sertifikat bisa diparse", tanpa root yang diharapkan. Asset itu juga tak pernah diterbitkan kedua workflow, jadi jalur tersebut selalu 404 sekali sehari — kode mati yang tetap menambah permukaan serangan.
- Jalur refresh dihapus total (`segarkanTrustAnchor`, `validasiRantai`, pembacaan override di `HttpsCompat`, pemicu harian di `AutoUpdate`); trust anchor kini hanya dari `assets/certs/github-chain.pem` yang ikut APK, sehingga bisa diaudit per rilis dan tak bisa diganti diam-diam lewat jaringan. Nama metode yang dihapus dicatat di `tools/bekas-hapusan.txt`.

## [Belum rilis] — Audit 311: verifikasi PIN tak lagi mengantre di pool unduhan
- `PinActivity.cobaBuka` dan aktivasi PIN di `SettingsActivity` memakai `Util.jalankanLama` (pool `vw-bg-lama`, 2 thread) yang juga dipakai unduhan web-vault ≥150 MB, update binary, serta backup/restore. Saat dua tugas berat berjalan, PBKDF2 PIN mengantre bermenit-menit dan layar kunci menampilkan "Memeriksa PIN…" tanpa tombol aktif.
- Pool khusus 1 thread `vw-pin` + `Util.jalankanPin` kini dipakai kedua jalur; `BG_LAMA` tetap untuk tugas berat. Unit test baru menahan kedua thread `jalankanLama` lalu memastikan tugas PIN tetap selesai <2 detik.

## [Belum rilis] — Audit 311: tebakan PIN dari grup tak bisa mengunci pemilik
- `handleCommand` dan `authDangerous` dulu menghitung setiap tebakan PIN dari chat grup sebagai kegagalan **global** (`PinGate.catatHasil(ctx, false, ...)`), padahal perintah ber-PIN dari grup memang selalu ditolak. Anggota grup mana pun bisa mengirim `/stop 0000` lima kali lalu mengulang tiap 5 menit untuk menjaga pemilik tetap terkunci — DoS dari dalam grup.
- `hitungTebakanPinGrup` dihapus total (dicatat di `tools/bekas-hapusan.txt`); perintah ber-PIN dari grup tetap ditolak dan pesannya tetap dihapus, tetapi penghitung PIN pribadi tidak tersentuh. Pesan "dihitung sebagai percobaan gagal" ikut dihapus.

## [Belum rilis] — Audit 312: sertifikat APK rilis dipaku SHA-256
- Langkah *Verify APK contents* (`build-apk.yml`) dulu hanya memastikan `keytool` bisa membaca sertifikat, bukan bahwa sertifikat itu kunci rilis yang benar. Bila `KEYSTORE_BASE64`/`KEY_ALIAS` salah atau keystore dibuat ulang, build tetap hijau dan APK berkunci lain terbit — pengguna lama tak bisa update (harus uninstall, data hilang).
- Sidik jari SHA-256 sertifikat APK kini dibandingkan dengan konstanta `SIDIK_SERT` (diambil dari APK rilis `v1.37.4`, `C2:78:5A:...:13:D1`); beda/tak terbaca = build gagal sebelum unggah. Prosedur rotasi keystore dicatat di `AGENTS.md`.

## [Belum rilis] — Audit 312: Start tak lagi terkunci proses vaultwarden yatim
- `MainActivity.saveAndStart()` memeriksa `ServerService.isPortBusy()` sebelum service dipanggil, sedangkan pembersihan proses yatim (`killStaleVaultwarden()`) baru berjalan **di dalam** `ServerService.startServer()` — jadi bila proses `vaultwarden-*` sisa app yang dibunuh sistem masih memegang port, dialog "Port sedang dipakai proses lain" muncul terus dan server tak bisa dihidupkan dari UI sampai perangkat reboot atau port diganti. Jalur bot `/start` tidak terkena karena memanggil service langsung.
- Pre-check kini memanggil `ServerService.bereskanServerYatim()` (pindai `/proc` segar, verifikasi cmdline sebelum kill) lebih dulu, baru memeriksa port. Bila port masih dipakai proses lain yang bukan milik app, dialog lama tetap muncul — jadi pesan "dipakai proses lain" kini benar.

## [Belum rilis] — Audit 310: kode tanpa komentar (code only, hemat token)
- Seluruh komentar dan docstring dihapus dari berkas kode: 42 `*.java`, 68 `*.xml` (resource + manifest), 3 `*.kts`, 2 `*.sh`, 1 `*.py`, 2 `*.yml`, 2 `*.c`, 1 `*.pro`, 2 `*.properties` (80 berkas berubah, 5.128 baris penjelasan hilang). Dokumen (`*.md`, `LICENSE`), data (`tools/bekas-hapusan.txt`, `*.pem`), dan wrapper Gradle hasil-generate (`gradlew`, `gradlew.bat`, jar) sengaja tidak disentuh.
- Penghapusan dilakukan dengan pemindai sadar-string/heredoc, bukan regex buta: `//` di URL, `/*` di string, `#` di `${...}`/regex/`::error::`, komentar XML di atribut, dan komentar di dalam heredoc `run: |` tetap aman. Validasi tiap berkas sebelum ditulis: token C-like sama persis (Java/Kotlin/C), pohon XML identik, AST Python identik (docstring dibuang terkontrol), struktur YAML identik, properti sama, serta `bash -n`/`sh -n`/`py_compile`/`gcc -fsyntax-only` lolos.
- `tools/pasang-hook.sh`: penanda hook yang dulu berupa teks komentar diganti baris kode `exec bash scripts/cek-cepat.sh` agar pemeriksaan "hook ini milik kita" tetap bekerja setelah komentar dihapus.
- `tools/verifikasi-patch.py`: `cek_uji_baru` tidak lagi salah menuduh metode statis lama sebagai "baru" ketika komentar yang menempel di baris deklarasi ikut terhapus (bandingkan baris tambah vs baris hapus setelah komentar dikupas).
- Aturan repo diperbarui: berkas kode dipelihara tanpa komentar/docstring; penjelasan keputusan ditulis di commit, `CHANGELOG.md`, atau README.

## [Belum rilis] — Audit 309: 6 peringatan lint dihilangkan (tanpa suppress)
- `TypographyDashes` (`values`/`values-in` `port_angka`): "1-65535" memakai tanda hubung; kini en dash `1&#8211;65535` di kedua locale.
- `PluralsCandidate` `port_root_judul`: kalimat dibalik menjadi "Root access is required for port %1$d" (Indonesia: "Akses root diperlukan untuk port %1$d") sehingga angka port tak lagi diikuti kata benda — bukan angka yang dihitung, jadi plurals memang tidak tepat di sini.
- `PluralsCandidate` `galat_db_besar`: `%1$d bytes` diganti `%1$s` dan pemanggil mengirim `TgBackup.humanBytes(Util.BATAS_UNZIP_RESTORE)` → "200 MB" (lebih mudah dibaca daripada angka mentah).
- `PluralsCandidate` `info_tls`/`info_tls_exp`: kini benar-benar `plurals` dengan quantity `one`/`other` (Inggris: "1 day" vs "N days"; Indonesia hanya `other`, sesuai CLDR). `certInfoLineInner` memakai `getQuantityString`.
- `verifikasi-patch.py`: `cek_sinkron_terjemah` juga membandingkan nama `plurals` `values` vs `values-in` (dulu hanya `<string>`), agar plurals yang lupa diterjemahkan tak diam-diam jatuh ke teks Inggris.

## [Belum rilis] — Audit 308: build APK hijau lagi (lint i18n + compile unit test)
- Dua sebab build merah beruntun: (a) `compileDebugUnitTestJavaWithJavac` gagal karena `TgBackupTest` memakai `TgBackup.ENC_MAGIC` yang `private`; (b) `lintDebug` gagal 16 error setelah i18n (#1).
- Lint `StringFormatMatches` (8 lokasi, `MainActivity:434-443` + `SettingsActivity:1080-1088`): enam string port memakai `%1$s` padahal pemanggil memberi `int` (`portFix`/`portCekPort`). Specifier diganti `%1$d` di `values` **dan** `values-in` (paritas tetap). Lint `UnusedQuantity` (error, aturan #12): percobaan menambah quantity `one` pada `plurals pin_lockout` versi Indonesia justru **GAGAL** karena CLDR bahasa Indonesia hanya punya `other` — item `one` dibuang, dan kesimpulannya dicatat agar tak diulang.
- Uji `TgBackupTest` memakai literal `"VWB1"` (konstanta `ENC_MAGIC` memang privat).
- Langkah CI "Tampilkan laporan lint di log" dulu `cat app/build/reports/lint-results-debug.txt` yang **tidak pernah ada** di AGP 8 (teks laporan ada di `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/`), sehingga log hanya memuat "First failure". Kini kedua path dicoba dan 400 baris pertama dicetak.
- `verifikasi-patch.py`: `cek_paritas_resource` kini memeriksa quantity `plurals` `values-in` sesuai aturan CLDR (wajib ada `other`, quantity lain ditolak) — setara lint `MissingQuantity`/`UnusedQuantity` yang dipasang sebagai error, agar kesalahan begini tak lagi baru ketahuan merah di CI. Diuji dua arah: menambah `one` (GAGAL, sesuai lint), menghapus `other` (GAGAL), keadaan normal LOLOS.

## [Belum rilis] — Audit 307: backup .enc ditulis atomik & sisa terpotong tak mengusir backup sehat
- `tulisTerenkripsi` dulu menulis `.enc` final di tempat. Bila STB mati/listrik putus/`kill -9` di tengah enkripsi, folder `backups/` menyimpan `.enc` parsial yang **tidak bisa didekripsi**, tetapi `lastModified`-nya paling baru sehingga ia menempati kuota `KEEP_BACKUPS` dan mengusir backup lama yang masih sehat — kebalikan tujuan retensi.
- Enkripsi kini menulis ke `<nama>.enc.tmp` di folder yang sama, `flush` + `fsync`, lalu `rename` atomik (fallback hapus-lalu-rename); `finally` membuang sisa `.tmp`. `isFileBackup` menganggap nama ber-`.tmp` bukan backup sehingga sisa tulisan tak menempati kuota.
- `cleanupOldBackups` menyapu sisa terputus sebelum menghitung retensi: `.tmp` yang lebih tua dari `TMP_BASI_MS` (1 jam; enkripsi yang sedang berjalan aman) dibuang, dan `.enc` yang panjangnya di bawah `panjangMinimumEnc(magic)` (VWB3 52 byte, VWB1/VWB2 48 byte; magic tak dikenal memakai batas 48) pasti terpotong sehingga dibuang. Daftar berkas dibaca ulang setelah penyapuan agar yang terhapus tak menempati kuota. Unit test baru menutup enc terpotong/kosong, tmp basi vs baru, dan tidak ada `.tmp` tersisa setelah `encryptFile` sukses.

## [Belum rilis] — Audit 306: status sibuk MainActivity bertahan lintas rotasi
- `uiBusy` hanya field instance, padahal unduhan web-vault (~35 MB) sengaja berjalan di worker dengan application context sehingga tetap lanjut walau Activity dibuat ulang. Setelah rotasi, instance baru punya `uiBusy=false`: tombol Start aktif kembali dan indikator unduh hilang; Start yang ditekan di tengah unduhan membuat `extractWebVault()` tak menemukan `index.html` sehingga server menyala **tanpa web vault**, dan callback lama dibuang karena `isDestroyed()`.
- `uiBusy` kini `static volatile`; `onCreate` memulihkan penguncian tombol + status sibuk bila unduhan masih berjalan. Jalur selesai unduhan melepas kunci (`setBusy(false)`) **sebelum** cek `isDestroyed()`, karena dengan status global rotasi di tengah unduhan dulu akan meninggalkan "sibuk" terkunci selamanya (setBusy sendiri sudah aman untuk Activity mati).

## [Belum rilis] — Audit 305: permintaan simpan log bertahan saat Activity dibuat ulang
- `LogActivity.simpanUlangSetelahIzin` hanya field instance. Pada Android 6–9, menekan "Simpan" tanpa izin storage membuka dialog izin; bila Activity dibuat ulang saat dialog tampil (rotasi, "Don't keep activities", proses dibunuh), instance baru punya flag `false` sehingga callback "Izinkan" masuk ke instance baru dan **tidak** memanggil `exportLogTxt()`: tidak ada file tersimpan dan tidak ada pesan apa pun.
- Flag kini disimpan di `onSaveInstanceState` (`simpan_ulang_izin`) dan dipulihkan di `onCreate`, cermin `STATE_MULAI_ULANG_IZIN` di MainActivity.

## [Belum rilis] — Audit 304: AGENTS.md bebas rujukan metode/job yang tak ada
- `AGENTS.md` menyebut `pinBaruSajaDibuka` (metode itu tak ada; yang ada `catatPinDibuka` + `PinGate.dalamGraceBersama()`), rantai `ensureBinary()` → `resolveBinary()` (`resolveBinary` tak pernah ada), dan job `perlu` di `build-apk.yml` (job sebenarnya `verifikasi`, `resolve`, `build-apk`). Rujukan basi membuat sesi baru mencari kode yang tak ada lalu "memulihkan" logika yang sudah dipindah — atau menciptakan ulang nama yang sudah dibuang (aturan #15).
- Dokumen diperbarui ke nama nyata, ukuran APK disamakan (`~0,25 MB`, rilis terakhir 247 KB) termasuk komentar `build-apk.yml` yang masih menulis ~0,1 MB.
- `tools/verifikasi-patch.py` mendapat cek baru `cek_acuan_agents`: setiap nama metode ber-`()` di `AGENTS.md` wajib punya deklarasi di `app/src` (main + test), selain itu verifikasi GAGAL. Dibuktikan dengan rujukan `resolveBinary()` lama: verifikator menolaknya, setelah dokumen diperbaiki kembali LOLOS.

## [Belum rilis] — Audit 303: verifikator menolak berkas warna/layout wajib yang hilang
- `baca()` mengembalikan string kosong untuk berkas yang tak ada, sehingga paritas `values/colors.xml` vs `values-night/colors.xml` (dan `layout/` vs `layout-land/`) hanya dicek bila kedua himpunan tak kosong: menghapus `values-night/colors.xml` (mode gelap memakai warna terang) atau folder `layout-land/` (UI landscape rusak) berakhir **LOLOS** tanpa cek lain yang menangkapnya.
- `cek_paritas_resource` kini memeriksa keberadaan berkas/folder wajib lebih dulu dan menggagalkan verifikasi bila hilang; `colors.xml` yang ada tetapi tanpa `<color>` juga gagal (paritas tak bisa dicek). Diuji lokal dengan memindahkan `values-night/colors.xml` dan `layout-land/`: dua temuan muncul; setelah dipulihkan verifikasi LOLOS.

## [Belum rilis] — Audit 302: pasang-hook membuktikan eksekusi & mencadangkan hook lama
- `tools/pasang-hook.sh` dulu memakai `[ -x "$HOOK" ]` sebagai bukti "kait bisa dieksekusi". `test -x` hanya membaca **bit izin**, bukan opsi mount: di penyimpanan `noexec` (`/sdcard`, tempat repo ini berada) `chmod +x` tetap "berhasil" tetapi `execve` ditolak kernel, sehingga skrip mencetak sukses padahal git mengabaikan hook dan setiap push lolos tanpa verifikasi. Sekarang hook dijalankan sungguhan (`pre-push --self-test` yang langsung `exit 0`); bila ditolak kernel, skrip menampilkan peringatan noexec. Diuji di repo ini (`/storage/emulated/0`): deteksi berhasil (sebelumnya akan salah lapor sukses) dan di `/tmp` (exec normal) lapor sukses.
- Hook `pre-push` lama (bukan buatan skrip ini) kini dicadangkan ke `pre-push.bak` (atau `.bak.N` bila sudah ada) sebelum ditimpa; bila pencadangan gagal, hook tidak diubah. Skrip juga menolak berjalan di luar akar repo.

## [Belum rilis] — Audit 301: Stop tepat setelah exec tak lagi diumumkan "Server jalan"
- Setelah `pb.start()` dan `KUNCI_PROSET` dilepas, thread Start masih mereset health, memindah file log, lalu menulis status `Running (PID …)` dan mengirim Telegram "Server jalan". Stop yang masuk di jeda itu sudah mematikan proses, sehingga status "Menghentikan…/Stopped" tertimpa "Running" dan pemilik yang mengandalkan bot mengira server hidup (padahal proses sedang dibunuh).
- Helper murni baru `ServerService.bolehUmumkanJalan(prosesSama, running, prosesHidup, episodeTerbaru)`; pengumuman + status kini hanya ditulis bila proses yang lahir masih aktif, `running` masih true, dan `idEpisodaProses == startIdTerakhir` (dicek di dalam `KUNCI_PROSET`). Bila tidak, jalur itu mencatat "Stop sudah diminta - status dan pesan Server jalan dilewati". Reader/watcher tetap dijalankan agar output proses yang masih hidup terus dikuras (pipe penuh memblokir server) dan watcher menutup episode.

## [Belum rilis] — Audit 300: staging web-vault selalu dibersihkan lewat try/finally
- Ekstrak + tukar web-vault hanya menangani `ZipException`/`IOException`; `RuntimeException`/`Error` (mis. `OutOfMemoryError` saat ekstrak di STB 1 GB) melewati semua pembersih per-cabang sehingga folder `web-vault.new-<cap>` puluhan MB dan tanda `STAGING_AKTIF` tertinggal. Pembersih Start melewati nama bertanda aktif, jadi folder itu tak pernah disapu sampai proses mati — storage menyusut diam-diam dan update berikutnya bisa gagal karena "ruang kurang".
- Seluruh blok ekstrak + swap kini dibungkus `try/finally`: apa pun yang keluar (termasuk `Error`), tanda staging dilepas dan folder dibuang bila belum terpasang (`terpasang` baru true setelah `rename` ke `web-vault` berhasil). Hapus di `finally` dibungkus `catch (Throwable ignored)` agar tak menutupi galat asli.

## [Belum rilis] — Audit 299: tawaran update web-vault tak lagi lintas namespace versi
- `autoOfferWebVaultUpdate` memakai `webVaultFromVersion()` (penanda `KEY_WV_FROM`) **atau**, bila kosong, versi dari `web-vault/vw-version.json` milik web-vault Bitwarden (mis. `2024.6.2`). Nilai fallback itu lalu dibandingkan dengan `currentServerVersion()` (mis. `1.32.0`) sehingga selalu "beda": dialog "Web Vault update available" muncul sekali per versi server dan menawarkan unduhan ~35 MB yang tidak mengubah apa pun; baris info Setelan juga menampilkan "server version differs" permanen.
- Helper murni baru `Updater.wvBedaVersiServer(penanda, versiServer)` hanya membandingkan penanda Vaultwarden dengan versi server (pakai `versiCocok`, toleran `v`/segmen hilang) dan bernilai false bila penanda atau versi server tak diketahui. Tawaran update dan peringatan di baris info kini memakainya; `vw-version.json` tetap boleh ditampilkan sebagai versi terpasang, tetapi tak pernah dibandingkan dengan versi server. Unit test mencakup penanda kosong/spasi, versi server kosong, dan `1.32` vs `1.32.0`.

## [Belum rilis] — Audit 298: /ca & /cabackup ikut kunci tugas berat (balapan dengan /careset)
- `/careset` mengambil `TUGAS_BERAT` agar tidak berjalan bersamaan dengan backup/restore, tetapi `/ca` dan `/cabackup` memakai `runWithWakeLock` polos. Akibatnya `/ca` yang dikirim beruntun setelah `/careset` bisa membaca `ca.pem` lama yang sedang dihapus/diganti, sehingga pengguna memasang CA basi (HTTPS gagal) atau menerima pesan galat membingungkan.
- Keduanya kini memakai `runBeratDenganKunci` seperti `/careset`, jadi salinan CA menunggu operasi TLS/backup lain selesai dan ditolak halus ("Tugas lain masih berjalan") bila ada tugas berat berjalan.

## [Belum rilis] — Audit 297: version.txt TLS ditulis atomik (CA tak dibuat ulang saat listrik mati)
- `TlsCert.writeVersion` dulu menulis langsung ke `version.txt` (truncate lalu isi). Bila STB mati/IO gagal di tengah, berkas kosong/terpotong; `certVersionOk()` jadi false, CA dianggap rusak, lalu CA baru dibuat dan **semua HP wajib memasang CA baru**. fsync di komentar lama tidak menolong berkas yang sudah ter-truncate.
- Kini pola sama dengan `writePem`/`tukarPasanganAtomik`: tulis `version.txt.baru` → `flush` → `fsync` → `rename` atomik menimpa `version.txt` (fallback hapus-lalu-rename bila FS menolak menimpa, lalu `IOException`). `resetTls` juga menyapu sisa `version.txt.baru` agar reset bersih; unit test baru memeriksa sisa itu terhapus.

## [Belum rilis] — Audit 296: Verifikator menolak biner/berkas besar di semua lokasi
- Cek "berkas >1 MB" dulu hanya berlaku untuk `app/src/main/`, sehingga `web-vault.zip` 36 MB, binary ELF tanpa ekstensi, atau arsip apa pun di root/`tools/`/`app/src/test/` lolos gate. Daftar ekstensi terlarang juga terbatas `.apk/.aab/.dex/.jks/.keystore`.
- `cek_aturan_repo` kini memeriksa magic berkas (ELF, PE/EXE, ZIP/JAR/APK, Mach-O/class, DEX) di **semua** berkas berubah dan menegakkan batas 1 MB di semua path, dengan pengecualian eksplisit hanya `gradle/wrapper/gradle-wrapper.jar` (bagian repo, bukan aset app). Berkas yang dihapus (tak terbaca) tetap lolos agar revert tak diblokir.
- Diuji lokal dengan berkas percobaan: `web-vault.zip` (magic PK), ELF tanpa ekstensi, dan teks >1 MB di root semuanya ditolak dengan nama jenisnya; setelah dibersihkan verifikasi kembali LOLOS.

## [Belum rilis] — Audit 295: Teks UI layar Log, awal, dan Setelan pindah ke resource (i18n)
- Teks tampilan yang masih ditulis langsung di `LogActivity`, `MainActivity`, dan `SettingsActivity` dipindah ke `res/values/strings.xml` (Inggris) + `res/values-in/strings.xml` (Indonesia): toast crash-log, dialog "Crash Log", bagikan/salin/simpan log, dialog port (<1024/in use), tawaran web-vault, folder data tidak valid, baris versi `App … · Binary …`, pemberitahuan update, seluruh toast/dialog Setelan (CADB, backup/restore, ekspor/impor config, reset binary/sertifikat), label salin token/URL, pesan galat `IOException`, serta baris info panel (DB/penyimpanan/web-vault/RAM/uptime/sisa ruang/TLS/shim). Nilai Inggris tiap resource dibuktikan sama dengan literal lama: 52 literal UI tunggal diperiksa otomatis (parse XML vs teks asal, 50 identik; 2 beda yang disengaja — "not an export of this app" menghindari apostrof resource dan `\u2014` ditulis sebagai karakter em-dash), sedangkan kasus ber-argumen memakai `%1$s`/`%2$d` dengan urutan sama seperti konkatenasi lama.
- Sekalian memperbaiki teks Inggris yang mencampur bahasa Indonesia: toast URL loopback kini `… (loopback — LAN IP not detected)` dengan padanan Indonesia `… (loopback — IP LAN tak terdeteksi)`.
- Dialog port/web-vault memakai resource netral (`port_root_*`, `port_dipakai_*`, `wv_*`) dengan varian pesan "di atas"/"in Settings" agar layar awal dan Setelan tetap menyebut lokasi pengaturan yang benar. `readCapped` menerima pesan galat dari pemanggil karena metode statis tak bisa memanggil `getString`.

## [Belum rilis] — Audit 294: Rangkaian build APK merah (kelas uji kembar, `throws`, API 24)
- `compileDebugJavaWithJavac` gagal 3 error: `URLEncoder.encode` dipindah ke `kirimKeDaftar` (commit "encode-once") tanpa ikut membawa `throws Exception`/`UnsupportedEncodingException`, sehingga seluruh build APK sesudahnya merah. `kirimKeDaftar` kini mendeklarasikan `throws Exception` dan kedua pemanggilnya (`kirimSinkron`, `kirimSinkronKe`) memakai jalur `catch` + `logTgFailure` yang sudah ada — tidak ada catch duplikat.
- `compileDebugUnitTestJavaWithJavac` gagal karena kelas pembantu uji `ProsesPalsu` terdefinisi dua kali identik di `ServerServiceTest` (salinan kedua dihapus; salinan pertama tetap dipakai dua uji kunci proses/episode).
- `lintDebug` gagal 2 error `NewApi`: `ConcurrentHashMap#compute`/`#computeIfPresent` baru ada di API 24 sedangkan `minSdk` 21. Mutasi peta kunci unduhan (naik/turun `pemakai` + buang entri saat pemakai terakhir) kini dalam satu blok `synchronized (KUNCI_PETA)` — atomik, tanpa API 24; kunci per path tetap `KunciUnduh.mutex` sehingga unduhan tak saling tertahan.
- Gerbang verifikasi ikut diperbaiki agar tidak memberi false positive "metode dihapus tapi masih dipanggil" saat menghapus satu dari dua pembantu uji kembar (override `Process`): `masih_didefinisikan()` memindai `app/src` (main + test), sesuai aturan #15 "masih didefinisikan di berkas lain = pindah, bukan hapus".
- Uji `TgBackupTest.sapuTmpPlainBasi_hanyaBasiDanBukanAktif` memanggil varargs dengan `null` tanpa cast sehingga javac memperingatkan "non-varargs call ... inexact argument type"; kini `(String[]) null` eksplisit (maksudnya: tanpa pengecualian).

## [Belum rilis] — Audit 293: Stop menghentikan unduhan binary yang sedang berjalan
- `ACTION_STOP` hanya menyetel `batalStart`, yang baru dibaca sebelum `exec`; `Updater.unduhKeTmp` tak punya hook pembatalan. Start tanpa binary lalu Stop membuat ~20 MB tetap terunduh (menit-menitan di STB lambat) dan `starting` tetap true sampai selesai, sehingga restore/reset yang menunggu `stopAndWait` ditolak "server gagal berhenti" padahal tak ada server.
- Ditambahkan `nomorStop` (nomor monotonik, dinaikkan tiap Stop) + `Updater.CekBatal`: episode Start mencatat nomornya, lalu unduhan berhenti di antara percobaan **dan** di tengah salinan badan (per 64 KB). Memakai nomor, bukan flag yang direset Start berikutnya, agar Start baru tidak menghidupkan lagi unduhan episode lama.
- Pesan pembatalan (`TANDA_DIBATALKAN`) sengaja tak memuat kata pemicu ulang/hapus parsial, jadi unduhan tidak diulang, byte parsial tetap bisa dilanjutkan via Range, dan `ensureBinary` tidak mencatat `KEY_BIN_DL_GAGAL_AT`/saran koneksi untuk keputusan user ini. Uji `UpdaterTest.pesanDibatalkan_tidakMemicuUlangAtauHapusParsial`, `dibatalkan_amanUntukCallbackNullDanRusak`, `overloadPembatalan_tersedia`.

## [Belum rilis] — Audit 292: Restore membatalkan Start yang masih tertunda
- Kedua pemanggil restore (`SettingsActivity.restoreDatabaseIsi`, `TgBackup.restoreFromZipIsi`) hanya memanggil `ServerService.stopAndWait()` bila `isProcessAlive()`. Saat episode Start masih menyiapkan binary (`process == null`, `starting == true`), guard itu false sehingga Stop tak pernah dikirim dan `batalStart` tak pernah dipasang: `KUNCI_DATA` menahan exec selama restore, lalu episode Start itu exec setelah restore selesai dengan `dataDir`/`port` yang dibaca **sebelum** restore.
- Guard `isProcessAlive()` dihapus di kedua jalur (hanya Stop nyata yang bisa membatalkan Start tertunda); restore tetap dibatalkan bila `stopAndWait()` mengembalikan false atau proses masih hidup. `stopAndWait()` sendiri sudah langsung `true` saat tak ada proses maupun Start tertunda, jadi tak ada intent Stop tambahan pada kasus biasa.

## [Belum rilis] — Audit 291: Rahasia alarm diperiksa sebelum kerja tunda-boot
- Jalur "tunda 5 menit setelah boot" di `AlarmReceiver.onReceive` membaca prefs dan memanggil `TgBackup.jadwalTundaBoot()` **sebelum** `rahasiaAlarmCocok`, padahal receiver `exported="true"`. Selama 5 menit pertama setelah boot, app lain bisa mengirim explicit intent `ALARM_HARIAN`/`ALARM_TUNDA` berulang untuk memaksa parse prefs + penjadwalan alarm di main thread (risiko ANR di STB 1 GB).
- Pemeriksaan rahasia dipindah ke awal `onReceive` untuk semua action milik app (siaran sistem tanggal/jam/zona tetap dilayani dengan throttle 60 dtk); dua pemeriksaan rahasia yang jadi duplikat dihapus.

## [Belum rilis] — Audit 290: Editan folder/port/token tak lagi dibuang saat tugas berjalan
- `SettingsActivity.onPause` memanggil `simpanPengaturanValid()`, tetapi fungsinya langsung `return` bila `tugasBerjalan` **atau** `TgBackup.tugasDataJalan()`; selama update binary/web-vault (menit) atau backup, perubahan folder/port/admin token dibuang diam-diam dan tak pernah disimpan sesudah tugas selesai.
- Penahan tulis kini hanya **restore** (`TgBackup.restoreJalan()`, penanda baru khusus `kunciRestore`/`lepasRestore`) karena hanya restore yang menimpa prefs lewat `app-config.json`. Update & backup tak menyentuh ketiga kunci itu, jadi editan user langsung tersimpan saat layar ditinggalkan. Uji `TgBackupTest.restoreJalanHanyaSaatKunciRestore`.

## [Belum rilis] — Audit 289: PIN rusak tetap menutup aplikasi (fail-closed)
- Bila `pin_on=true` tetapi `pin_hash` hilang/rusak, `PinGate.perlukanLayarPin()` menulis `pin_on=false` lalu mengembalikan `false`: aplikasi terbuka penuh (Settings, admin token, log, backup) **dan** proteksinya mati permanen, hanya meninggalkan satu baris log. `PinActivity` sendiri sudah fail-closed, jadi dua jalur ini saling bertentangan.
- Keputusan wajib-layar dipusatkan di `wajibLayarPin()` (murni + uji): `KUNCI_RUSAK` kini ikut wajib layar PIN, prefs tak lagi diubah. Karena hash tak ada, `PinActivity` menolak (RESULT_CANCELED) dan pemanggil finish — akses tetap terkunci sampai PIN disiapkan ulang, dan pesan log menyebut tindakan yang perlu dilakukan.

## [Belum rilis] — Audit 288: Restore bot tak lagi menuntut ruang 2× untuk salinan mati
- Jalur restore bot/UI (`TgBackup.restoreFromZipIsi`) membuat `backups/db-backup-*-pre.sqlite3` lalu tak pernah memakainya; cek ruang ikut menghitung salinan itu (DB 200 MB + isi zip 40 MB menuntut ~245 MB) sehingga restore ditolak di perangkat yang sebenarnya cukup. Dokumentasi menjanjikan rollback yang tak ada.
- Salinan pengaman itu dihapus: staging + `rename` atomik sudah menjaga DB lama sampai backup baru lolos `quick_check`, jadi cek ruang cukup `isi zip + 5 MB`. Bila penerapan `app-config.json` gagal setelah DB terpasang, restore dilaporkan **berhasil dengan peringatan** (bukan "gagal" tanpa pemulihan). Javadoc `restoreFromZip` + komentar `SettingsActivity.restoreFromZip` disamakan dengan perilaku.

## [Belum rilis] — Audit 287: Username bot diikat ke ID bot pemilik token
- `KEY_TG_BOT_USER` hanya diisi sekali lewat `isiNamaBotBilaKosong()`, jadi setelah token bot diganti username lama tetap dipakai memvalidasi suffix `@namabot`: perintah ke bot baru (`/status@botbaru`) dibuang diam-diam sementara perintah ke bot lama tetap lolos gerbang grup.
- Username kini disimpan bersama `KEY_TG_BOT_ID` (angka sebelum `:` pada token, `idBotDariToken` murni + uji). `namaBotTersimpan()` mengembalikan "" bila ID token sekarang berbeda, sehingga getMe berikutnya mengisi ulang dan suffix bot lama langsung tak berlaku — termasuk untuk token yang diimpor dari config.

## [Belum rilis] — Audit 286: "Belum terbit" tidak lagi dipakai untuk gangguan jaringan
- `fetchChecksum()` mengembalikan `null` untuk 404 **dan** untuk galat jaringan, lalu pemanggil probe `.sha256` menyimpulkan "Build Android v<versi> belum tersedia / web vault belum tersedia" padahal asetnya sudah terbit dan GitHub/STB-nya yang tak terjangkau. Cek yang sama juga diulang tiap aplikasi dibuka (2 request × timeout).
- `fetchChecksumRinci()` kini membedakan `BELUM_TERBIT` (404/format asing) dari `GANGGUAN` (timeout/5xx/rate-limit). Pesan probe binary & web-vault, serta pesan `shaDariRilis`, menyebut sebab yang benar. Setelah gangguan, waktu kegagalan disimpan (`KEY_CEK_GANGGUAN_AT`) dan probe ditahan `JEDA_CEK_GANGGUAN_MS` (15 menit, `dalamJedaCek` murni + uji; jam mundur tidak menahan selamanya). Cek sidik web-vault yang terganggu tidak lagi memicu unduh ulang ~35 MB.

## [Belum rilis] — Audit 285: Badan unduhan terpotong & checksum mismatch diulang, bukan dibuang
- `salinSambilHash()` menghitung digest dari berapa pun byte yang masuk. Proksi/CDN yang menutup koneksi "rapi" di tengah badan membuat digest parsial diterima sebagai hasil penuh, lalu verifikasi SHA-256 (di luar loop) langsung membatalkan unduhan dan menghapus 12 MB yang sudah masuk — pengguna harus menekan ulang manual.
- Badan kini dibandingkan dengan Content-Length (`badanTerpotong`, murni + uji). Bila berhenti lebih awal, unduhan dianggap "terputus" (boleh dicoba ulang) dan berkas parsial **dipertahankan** karena byte-nya masih berurutan benar, sehingga percobaan berikut melanjutkan via Range alih-alih mengulang dari nol.
- Verifikasi checksum dipindah **ke dalam** loop unduh (`Updater.ShaTarget`, `shaDariRilis`): digest yang tak cocok kini dihapus + diunduh ulang (maks 2× agar aset yang memang korup tidak menghabiskan kuota 4×), tidak lagi mati permanen. Gagal mengambil `.sha256` tetap tidak menghapus parsial.

## [Belum rilis] — Audit 284: Unduhan paralel versi sama tak lagi gagal "checksum tidak cocok"
- Dua unduhan versi **sama** memakai nama `tmp` identik. Pihak pertama me-rename `tmp` → `out` (setelah uji asapnya) sebelum pihak kedua memanggil `pasangHasilUnduh()`; `sha256Hex(tmp)` menjadi null → `expectedHexEquals` false → "Checksum SHA-256 tidak cocok" padahal byte di tujuan sehat, lalu `tmp.delete()` ikut menimpa hasil pihak lain. Di `SettingsActivity.pasangVersiDipilih` pin dikembalikan ke versi lama dan `KEY_BIN_DL_GAGAL_AT` dipasang sehingga AutoUpdate berhenti mencoba 6 jam.
- `pasangHasilUnduh()` kini menerima `tmp` yang hilang sebagai sukses bila `out` sudah cocok checksum (hasil unduhan paralel), dan memberi galat eksplisit "berkas sementara hilang" bila tujuan juga tak cocok. Verifikasi byte yang benar-benar dipasang tetap wajib.

## [Belum rilis] — Audit 283: Hitungan health direset saat `/api/config` sehat
- Cabang `aliveRusakTapiConfigSehat` (di bawah ambang `BATAS_CONFIG_SAJA_BERUNTUN`) hanya menaikkan `configSajaBeruntun`; `healthFails`/`healthTcpLolos` dari timeout lama dibiarkan menumpuk. Akibatnya ambang "3x beruntun" bukan benar-benar beruntun: dua timeout berjam-jam lalu + satu timeout baru langsung memicu restart otomatis, server sehat dibunuh, dan jatah `restartAttempt` terpakai.
- Server yang menjawab `/api/config` 200 kini mereset kedua counter itu (tick ini bukan kegagalan `/alive` biasa); kerusakan DB yang beruntun tetap dilacak `configSajaBeruntun` sampai ambangnya sendiri.

## [Belum rilis] — Audit 282: Efek stop (log/notifikasi/wakelock) di luar KUNCI_PROSET
- `lepasProses()` menjalankan `efek.run()` **di dalam** `synchronized (KUNCI_PROSET)`. Efek pembersihan stop menulis berkas log ke folder data (bisa `/sdcard` FAT) dan memanggil binder `NotificationManager.notify` serta `WakeLock.release`, sementara main thread (`onStartCommand` → `stopServer`) membutuhkan kunci yang sama — UI bisa beku sampai ANR pada STB lambat/log banyak.
- `lepasProses()` kini hanya mengubah state proses di dalam kunci lalu menjalankan efek di luarnya. Efek standar dipusatkan di `efekProsesBerhenti()`, dengan `setStatusBilaBerhenti()` (cek `running`/`process`/`starting` singkat di kunci, notifikasi di luar) dan `releaseWakeLockBilaBerhenti()` (cek ulang di dalam `KUNCI_WAKELOCK`, kunci yang sama dengan `acquireWakeLock`) agar Start baru tidak kehilangan status/wakelock-nya.

## [Belum rilis] — Audit 281: Anggaran tunggu stop menutup worst-case stopper
- `stopAndWait()` dipanggil dengan `timeoutMs = 8000` dari restore Settings dan Telegram, padahal worst-case stopper adalah `destroy` + tunggu 5 dtk, lalu `destroyForcibly` + tunggu 3 dtk (= 8 dtk) **belum** termasuk jeda start thread `vw-stop` dan polling 500 ms. Pada STB lambat yang butuh jalur paksa, restore dibatalkan dengan peringatan "DB bisa korup" padahal server hanya butuh sedikit lebih lama untuk mati — pengguna disuruh mengulang dan masalah berulang.
- Konstanta `ServerService.ANGGARAN_STOP_MIN_MS` (15 dtk) dipakai kedua pemanggil, dan `stopAndWait()` menaikkan anggaran apa pun yang di bawah minimum itu sebagai pertahanan untuk pemanggil baru. Perilaku "false = DB belum bebas" tetap fail-closed.

## [Belum rilis] — Audit 280: PIN yang diketik di grup dihapus dan dihitung gagal
- Dua gerbang grup (`handleCommand` untuk perintah berbahaya dan `authDangerous` untuk perintah ber-PIN) menolak dengan `return` **sebelum** PIN diurai. Blok penghapusan pesan di poll loop mensyaratkan `terotorisasi`, sehingga PIN asli yang salah ketik di grup **tetap tersimpan di riwayat grup** — bisa dibaca anggota lain dan dipakai di chat pribadi untuk `/stop`, `/restore`, `/careset`. `PinGate.catatHasil` juga tak pernah dipanggil, jadi tebakan PIN dari grup tidak pernah mengaktifkan lockout 5×5 menit (tebakan tanpa batas).
- Pembantu murni `pinTebakan()`/`pinTebakanArgumen()` (memakai `pisahkanPin` yang sudah menyaring versi/nama file) mengenali tebakan PIN ≥ 4 karakter. Jalur tolak grup kini menghitungnya sebagai percobaan gagal (tanpa verifikasi agar tak jadi oracle benar/salah; hanya saat PIN aktif dan belum terkunci, agar spam tanpa PIN tak bisa mengunci pemilik). Poll loop menghapus pesan grup yang membawa tebakan PIN walau perintahnya ditolak, dan balasan grup menyebut bahwa tebakan dihitung gagal.

## [Belum rilis] — Audit 279: Publish rilis hanya dari commit `main`
- `workflow_dispatch` bisa dijalankan pada branch/tag mana pun dan tak ada pemeriksaan `github.ref` sebelum menerbitkan. Kedua workflow memakai nama rilis `v<tag upstream terbaru>` dengan `--clobber` (dan binary menandainya `--latest`), sehingga run dari branch percobaan bisa menimpa aset rilis resmi yang diverifikasi SHA-256 dan diunduh semua pengguna — setara mengedit aset rilis manual (aturan repo #11), tanpa cara bagi pengguna membedakannya.
- Langkah `Publish APK ke rilis` kini `if: github.ref == 'refs/heads/main'`, dan job `publish-binary` mensyaratkan hal yang sama di samping cek `skip`. Build di branch lain tetap berjalan sebagai artifact untuk pengujian, publish dilewati. Push ke `main` dan cron bulanan (ref = default branch) tidak terpengaruh.

## [Belum rilis] — Audit 278: Backup Telegram memakai snapshot DB yang konsisten
- `PRAGMA wal_checkpoint(TRUNCATE)` hanya best-effort: status SIBUK/`busy=1` dan galatnya ditelan `catch (Exception ignored)`. Setelah itu `db.sqlite3`, `-wal`, dan `-shm` disalin berurutan tanpa pemeriksaan apa pun, sementara server (proses lain) terus menulis — db dari T1, WAL dari T2, SHM dari T3. Verifikasi hanya mengekstrak `db.sqlite3`, sehingga zip dengan WAL robek/tak cocok tetap dilaporkan sukses dan baru ketahuan saat `/restore` (saat DB asli sudah ditimpa).
- `checkpointWal()` kini mengembalikan `busy=0` yang sebenarnya. Backup membangun snapshot di folder cache: checkpoint → salin db+WAL+SHM → tolak bila sumber berubah selama penyalinan (mtime/ukuran) → buka salinan dengan WAL-nya dan jalankan `quick_check` (mode READWRITE agar SQLite boleh memulihkan WAL). Tiga percobaan, lalu backup **dibatalkan lantang** alih-alih mengunggah arsip yang mungkin tidak konsisten. Zip dibangun dari snapshot itu, dan `verifikasiIsiDbZip()` kini mengekstrak db+WAL+SHM bersama lalu memeriksa gabungannya. Sisa folder `vwsnap-*` dari proses yang dibunuh disapu.

## [Belum rilis] — Audit 277: Balasan perintah Telegram hanya ke chat pengirim
- Seluruh balasan bot memakai `TgBackup.sendMessage()` yang mengulang **semua** chat di `KEY_TG_CHAT`. `authDangerous` sudah menolak perintah ber-PIN dari grup, tetapi **hasil** perintah yang dijalankan dari chat pribadi tetap disiarkan: grup ikut menerima path folder data, ukuran DB, URL LAN/port (`/status`), potongan log/crash (`/log`, `/crashlog`), serta nama & tanggal berkas backup (`/backup`, `/restore`).
- Balasan kini lewat satu pintu `TgBackup.sendMessageKe`/`sendMessageKbKe`/`sendPentingKe` yang mengirim ke **satu** chat (pengirim) lewat `kirimKeDaftar()` yang sama; siaran tetap dipakai untuk notifikasi sistem (backup terjadwal, storage, restart berulang, peringatan jam mundur). Fallback `editMessageKb` yang gagal mengedit juga tidak lagi menyiarkan menu ke semua chat. Tujuan balasan divalidasi `tujuanBalasan()` (murni, diuji) sehingga ID aneh tak masuk URL API.

## [Belum rilis] — Audit 276: Kegagalan `git diff` menggagalkan verifikasi, bukan lolos diam-diam
- `jalan()` mengembalikan `None` untuk exit≠0/timeout, tetapi seluruh pemanggil diff menulis `jalan(...) or ""`. Bila `git diff` gagal (riwayat tak berhubungan setelah force-push/re-init, `safe.directory`, atau timeout 60 dtk di runner lambat), diff menjadi string kosong dan **sepuluh cek berbasis diff** — termasuk `cek_panggil_bekas_hapusan` (aturan #15), `cek_metode_hilang`, `cek_locale_baru`, `cek_pola_berisiko`, `cek_izin_baru`, `cek_definisi_baru` — dilewati, lalu skrip mencetak `Hasil: LOLOS`.
- Pembantu baru `jalan_diff()` memisahkan "diff kosong" dari "git gagal"; kegagalan dicatat dan membuat hasil akhir GAGAL dengan pesan eksplisit, sehingga gerbang CI tak bisa hijau tanpa bahan. Berlaku untuk jalur `--rentang`, jalur lokal (hulu/HEAD~1 + diff kerja + staged), `--semua`, dan daftar berkas eksplisit.

## [Belum rilis] — Audit 275: Verifikator tak lagi mengira pemanggilan konstruktor sebagai definisi
- `nama_metode_hilang()` menilai baris yang dihapus sebagai definisi metode bila baris itu diakhiri `{` (heuristik untuk definisi yang kurawalnya di baris berikut). Panggilan konstruktor yang dipecah ke beberapa baris — mis. `new FileInputStream(new File(...))) {` di dalam try-with-resources — ikut terbaca sebagai definisi `FileInputStream()`, sehingga penghapusan sah (`tulisTagShim`/`bacaTagShim`) digagalkan gerbang dengan pesan "Metode FileInputStream() dihapus tapi masih dipanggil".
- Baris yang memuat `new Nama(` kini dilewati: itu pemanggilan konstruktor, bukan definisi. Cek metode hilang tetap menangkap definisi sungguhan (yang tak pernah didahului `new`).

## [Belum rilis] — Audit 274: Shim getrandom dipakai ulang tanpa menunggu APK baru
- `ensureShimFile()` hanya memakai ulang shim bila `appVersionName(ctx)` (tanggal build APK) sama dengan isi `shim-tag.txt`. Setiap APK baru membuat penanda itu basi, sehingga Start di STB kernel lama **wajib** mengunduh ulang shim; tanpa internet Start dibatalkan ("Shim getrandom belum tersedia") padahal `libgetrandom-shim-armeabi-v7a.so` di disk masih sah, dan tiap Start menunggu timeout jaringan lebih dulu.
- Reuse kini murni berdasarkan validitas berkas (`shimValid`: ELF + ukuran wajar) — shim bergantung pada ABI/syscall kernel, bukan versi APK. Penanda `shim-tag.txt` beserta penulis/pembacanya (`tulisTagShim`/`bacaTagShim`) dihapus dan namanya dicatat di `tools/bekas-hapusan.txt`.

## [Belum rilis] — Audit 273: Referensi proses Start ditangkap di bagian kritis (NPE `vw-watch`)
- `startServer()` membaca `final Process p = process;` **di luar** `KUNCI_PROSET`, sesudah `TgBackup.sendMessage()` (jaringan) dan `notifyLowStorage()`. Stop yang berjalan di jendela itu men-null-kan `process`, sehingga `p` menjadi null dan `watchProcess(null)` melempar `NullPointerException` di thread `vw-watch` — NPE tanpa handler mematikan seluruh proses aplikasi dan server Rust tertinggal yatim (port tetap terpakai).
- Referensi proses kini ditangkap di dalam bagian kritis yang sama dengan `pb.start()`, lalu dipakai untuk status PID, pembaca output, dan watcher. Stop yang datang setelahnya tetap membersihkan state lewat `lepasProses()` (guard `process == p`), jadi perilaku stop/restart tidak berubah.

## [Belum rilis] — Audit 272: Verifikator patch tahan XML hilang/tak terbaca
- `cek_xml` hanya menangkap `ET.ParseError`, padahal `ET.parse` juga melempar `OSError` (berkas hilang, izin, checkout tak lengkap). Satu manifest hilang membuat `verifikasi-patch.py` berhenti dengan traceback: temuan lain tak pernah dilaporkan dan exit code bukan `1` yang jelas. `himpun_definisi` pun memanggil `os.listdir(res)` tanpa penjagaan sehingga folder `res/` yang hilang mematikan skrip sebelum sempat menulis temuan.
- Parse XML kini lewat satu helper `parse_xml_aman` yang membedakan "XML rusak" (ParseError) dari "tak terbaca" (OSError), `cek_manifest` ikut menangkap OSError, `himpun_definisi` melewati `listdir` bila folder tak ada, dan `cek_xml` melaporkan folder `res/` yang hilang secara eksplisit (sebelumnya `os.walk` diam-diam memeriksa nol berkas lalu lolos). Parameter mati `basis_desc` pada `cek_xml` dihapus.

## [Belum rilis] — Audit 271: Web-vault dipakai ulang hanya bila digest-nya cocok
- Job `publish-binary` memakai ulang `web-vault.zip` yang sudah ada di rilis bila lolos `unzip -t` saja (uji integritas arsip). Akibatnya zip yang dibangun dari digest Docker **berbeda** untuk tag yang sama tetap diterima dan diunggah ulang: isi web-vault bisa tidak sinkron dengan binary yang dirilis, dan `web-vault.zip.sha256` ikut diperbarui sehingga aplikasi mengunduh campuran versi tanpa peringatan.
- Digest `vault_image_digest` untuk tag tersebut kini dibaca lebih dulu, lalu pemakaian ulang mensyaratkan arsip utuh **dan** penanda `.vaultwarden-web-digest` di dalam zip sama dengan digest itu. Zip lama tanpa penanda otomatis dibangun ulang sekali dari Docker; saat membangun, penanda ditulis ke folder sebelum di-zip. Galat jaringan saat membaca digest menggagalkan langkah secara lantang, bukan diam-diam memakai zip yang mungkin beda versi.

## [Belum rilis] — Audit 270: Build binary memakai `Cargo.lock` upstream apa adanya
- `cargo build --release` di `build-binary.yml` berjalan tanpa `--locked`, padahal repo Vaultwarden menyertakan `Cargo.lock` (workflow sendiri membacanya untuk cek `ndk-context`). Tanpa `--locked`, cargo bebas memperbarui resolusi dependensi ke versi semver-terbaru: build jadi tak reprodusibel antar-run, dan crate baru bisa menuntut toolchain lebih baru daripada `rust-toolchain.toml` tag tersebut — kegagalan yang muncul jauh di tengah kompilasi ~15 menit.
- Perintah kini memakai `--locked` sehingga build gagal lantang bila `Cargo.lock` upstream tidak konsisten, bukan diam-diam memakai dependensi lain.

## [Belum rilis] — Audit 269: Galat sesaat `gh release view` tak lagi memicu rebuild binary
- Langkah `resolve` di `build-binary.yml` membaca daftar aset dengan `gh release view … 2>/dev/null || true`. Bila panggilan itu gagal sesaat (502, rate-limit, timeout), `DAFTAR` kosong dan loop menganggap keenam aset non-APK **belum lengkap** — cron bulanan lalu menjalankan build Rust ~15 menit plus publish padahal asetnya sudah ada.
- Daftar aset kini dibaca dengan coba-ulang 4× (jeda 5 dtk) dan stderr diperiksa: pesan "not found"/"HTTP 404" tetap berarti rilis belum ada (build memang perlu jalan), sedangkan galat lain menggagalkan job `resolve` dengan `::error::` eksplisit — lebih baik merah sekali daripada membangun ulang tanpa alasan. Cek kelengkapan aset sendiri tak berubah.

## [Belum rilis] — Audit 268: Javadoc bertumpuk/yatim dirapikan
- 15 lokasi memuat dua blok `/** … */` berurutan untuk satu anggota (sisa pemindahan kode). Blok pertama menempel ke blok kedua sehingga dokumentasi pertama tak terbaca alat dan menyiratkan dua penjelasan berbeda untuk satu metode/field. Sebagian benar-benar yatim: dokumentasi metode yang sudah pindah menempel pada anggota lain (mis. "Hapus backup terlama…" di atas `isFileBackup`, "Terapkan prefs dari JSON…" di atas `KUNCI_STRING`, "Satu sumber kebenaran validitas entri chat…" di atas `bandingDigit`), sehingga pembaca mengira doc itu milik anggota yang salah.
- Tiap lokasi diselesaikan sesuai isinya: blok untuk anggota yang sama digabung (`healthBerjalan`, `mulaiService`, `panduanLoginBitwarden`, `sysInfoLine`, `certInfoLine`, `salinBersihOtomatis`, `freeBytes(Context, …)`), blok yatim dipindahkan ke metode yang benar-benar didokumentasikan (`cleanupOldBackups`, `applyPrefsFromJson`, `backupOtomatis`, `chatTunggalValid`, `bersihkanPin`, `validasiInline`), dan blok tanpa target dibuang (doc "Tanggal via cache terpusat Util" di `LogExport`, "Teks ringkasan per kartu" di `SettingsActivity`). Satu javadoc `capCaStat` yang terpisah baris kosong dari field-nya ditempelkan kembali.

## [Belum rilis] — Audit 267: Pesan restore tak lagi mengaku datang dari Telegram
- `TgBackup.restoreFromZipIsi()` selalu mengembalikan "Database direstore dari Telegram … Tekan /start untuk menjalankan.", padahal fungsi yang sama dipakai tombol Restore di Pengaturan untuk file zip **lokal** (hasilnya masuk log UI). Pengguna yang memulihkan dari file lokal melihat sumber yang salah, dan petunjuk "/start" (perintah bot) tak berlaku di jalur UI.
- Pesan kini netral: "Database dipulihkan … Ukuran DB: N bytes."; petunjuk lanjutan jadi tanggung jawab pemanggil — bot `/restore` menambahkan " Tekan /start untuk menjalankan." sendiri, sementara UI sudah memberi toast "Restore done. Press Start to run it.". Komentar/javadoc yang menyebut "zip backup Telegram" diselaraskan karena jalur ini melayani dua sumber.

## [Belum rilis] — Audit 266: Kerja boot pindah dari main thread ke worker
- `BootReceiver.onReceive` mengerjakan semuanya di main thread: `healkanStringPrefs()` + `migrateAutoPref()` masing-masing memanggil `getAll()` (parse XML prefs penuh), penulisan heal, `Environment.getExternalStorageDirectory()` + `File.exists()` untuk `db.sqlite3`, lalu penjadwalan alarm/bot. Pada STB lambat dengan prefs besar, siaran boot bisa menahan UI sampai batas ~10 detik receiver terlampaui.
- Validasi action (murah) tetap di main; sisa kerja kini diserahkan lewat `goAsync()` + `Util.jalankanBg`, dengan `PendingResult.finish()` di `finally` supaya siaran selalu ditutup. Bila antrean worker penuh (langka saat boot), kerja dijalankan inline agar auto-start tak hilang. Keputusan throttle — baca/tulis penanda yang dulu otomatis berurutan karena main thread — dibungkus `KUNCI_BOOT` agar dua siaran beruntun tak sama-sama lolos. Galat tak terduga dicatat kelasnya, tidak lagi mematikan proses saat boot.

## [Belum rilis] — Audit 265: Pratinjau log tak lagi memotong baris raksasa di tengah
- `refreshHomeLog()` memotong delta tepat di `BATAS_DELTA_TICK` bila tak ada `\n` dalam 8192 karakter pertama ("potong paksa"). Penyamaran bekerja per baris, sehingga potongan baris panjang tampil separuh di satu tick dan lanjutannya di tick berikut — di layar keduanya tersambung lagi, jadi token/rahasia yang seharusnya tersamar justru terbaca utuh (hanya terpecah antar-tick). Titik potong paksa juga bisa membelah pasangan surrogate.
- Batas tampil kini dihitung di dalam kunci `logBuffer` lewat dua pembantu murni: `ujungBarisUtuh()` (potong sesudah `\n` terakhir di jendela tick, `n` bila sisa buffer muat, `-1` bila baris pertama lebih panjang dari jendela) dan `awalBarisSetelah()` (lompat ke baris utuh berikutnya). Baris yang lebih panjang dari jendela dilewati utuh — bukan dipotong — dan tick berikutnya melanjutkan dari baris baru; lompat-ke-ekor (buffer dipangkas/log meledak) juga selalu mulai di awal baris. Blok potong paksa pasca-lock beserta cek surrogate-nya dihapus karena tak lagi terpakai. Uji JVM `pratinjauLogTakMemotongBaris` menutup kasus baris 9000 karakter.

## [Belum rilis] — Audit 264: Syarat tulis hash PIN tak lagi selalu benar
- `tulisHashPin()` memeriksa `seq == pinSeq && h.equals(pinHashSiap)` **sesudah** `seq` dicek di atasnya dan `pinHashSiap = h` baru saja ditetapkan — kedua klausa tak pernah bisa salah, jadi yang benar-benar menyaring hanya `!pinAktifJalan && pinAktif`. Field `pinHashSiap` sendiri hanya ditulis (4 tempat) dan tak pernah dibaca secara bermakna, sehingga menyiratkan adanya cache "hash settle" yang sebenarnya tidak dipakai.
- Field itu dihapus beserta seluruh penulisannya; gerbang settle tinggal nomor urut (`pinSeq`) + debounce 800 ms, dan komentar jalur ketik/aktivasi disesuaikan agar tak lagi menyebut cache yang tak ada. Perilaku tulis hash tidak berubah (dulu pun selalu lolos syarat itu).

## [Belum rilis] — Audit 263: Sapu cache tak lagi memblokir UI saat activity dibuka
- `onCreate` memanggil `bersihkanExportPlainCache()` dan `onResume` memanggil `sapuExportPlainBasi()` + `sapuSisaImpor()` langsung di UI thread. Ketiganya melakukan `getCacheDir().listFiles()` lalu `lastModified()`/`delete()` per entri; pada STB yang lama dipakai (cache ribuan berkas, storage lambat) ini menahan cold start/`onResume` sampai enumerasi selesai — persis kelas ANR yang dihindari di jalur lain.
- Keduanya kini dijalankan lewat satu pintu `sapuCacheLatar()` di pool ringan (`Util.jalankanBg`), dengan gerbang `sapuCacheJalan` agar `onCreate`+`onResume` beruntun tak menggandakan pekerjaan; antrean penuh melepas gerbang agar percobaan berikutnya tetap jalan. Sapu tetap best-effort dan `exportPlainTertunda` hanya dikosongkan bila masih menunjuk file yang sama (sapu kini bisa berjalan bersamaan dengan UI yang memasang pending baru).

## [Belum rilis] — Audit 262: Teks Indonesia tak lagi menyelip di toast berbahasa Inggris
- Tiga potongan teks Indonesia dicetak ke toast UI yang selebihnya berbahasa Inggris: `" Shim getrandom siap."`, `" Shim gagal: …"` (jalur cek update) dan `" Tekan Start untuk memakai."` (cek update + pasang versi pilihan). Pengguna locale Inggris melihat kalimat campuran, dan teks itu tak bisa diterjemahkan.
- Kini toast memakai Inggris (`" Shim getrandom ready."`, `" Shim failed: …"`, `" Press Start to use it."`), sedangkan log diagnostik tetap Indonesia lewat string terpisah (`imbuhShimLog`), sesuai aturan bahasa repo (UI Inggris, log/bot Indonesia).

## [Belum rilis] — Audit 261: Kerja berat unduh/restore memakai context aplikasi
- Tiga jalur kerja menit-lama di `SettingsActivity` menyerahkan **Activity** ke helper: `Updater.updateWebVault(this)` (unduh web-vault ~35 MB), `Updater.tryUpdate(this)` (unduh binary ~15 MB), dan `TgBackup.restoreFromZip(this, zip)`/`downloadLastBackup(SettingsActivity.this, …)` (unduh + restore backup). Bila pengguna menekan Back saat proses berjalan, helper tetap memegang Activity beserta seluruh view/window sampai selesai — pola yang sengaja dihindari MainActivity (komentar "pakai app context di worker agar tekan back saat unduh 35 MB tak menahan Activity yang sudah destroy").
- Kini keempat jalur memakai `getApplicationContext()` (selaras MainActivity); pembaruan UI sesudahnya tetap lewat gerbang `isFinishing()`/`isDestroyed()` yang sudah ada. Aktivitas hanya dipakai untuk menyentuh view, bukan untuk menahan operasi panjang.

## [Belum rilis] — Audit 260: Satu sumber grace PIN untuk layar log
- `LogActivity.logBolehTampil()` menerima **dua** parameter grace (Main + Settings), padahal kedua pemanggilnya — `MainActivity.pinBaruSajaDibuka()` dan `SettingsActivity.pinBaruSajaDibuka()` — adalah wrapper identik atas `PinGate.dalamGraceBersama()`. Nilainya selalu sama, sehingga cabang `graceMain || graceSettings` tak pernah bisa berbeda dan menyiratkan adanya dua sesi PIN yang sebenarnya tidak ada.
- Parameter diciutkan jadi satu `grace`, dan pemanggil memakai `PinGate.dalamGraceBersama()` langsung; dua wrapper identik dihapus (dicatat di `tools/bekas-hapusan.txt`) agar tak ada jalan kedua yang bisa menyimpang dari satu sumber grace. Uji JVM disesuaikan.

## [Belum rilis] — Audit 259: Permintaan Start tertahan izin selamat dari Activity dibuat ulang
- `mulaiUlangSetelahIzin` (Start ditahan menunggu izin storage, lalu dilanjutkan di `onRequestPermissionsResult`) hanya hidup di field instance. Dialog izin sistem bisa membuat Activity dibuat ulang (rotasi layar, tema/ukuran berubah, proses app dibunuh saat dialog tampil); instance baru memulai flag `false`, sehingga izin yang akhirnya diberikan **tidak** melanjutkan Start dan pengguna harus menekan Start lagi tanpa tahu sebabnya.
- `MainActivity` dan `SettingsActivity` kini menyimpan flag itu di instance state (`STATE_MULAI_ULANG_IZIN` lewat `onSaveInstanceState`) dan memulihkannya di `onCreate(savedInstanceState)` sebelum hasil izin kembali. Flag tetap direset di `onRequestPermissionsResult`/`saveAndStart()` agar penolakan atau kegagalan tak memicu Start liar.

## [Belum rilis] — Audit 258: Runnable dari worker tak lagi menyentuh Activity yang sudah mati
- Callback worker (`AutoUpdate.Aksi`, `TgBackup.BackupStartUi`) memposting pekerjaan ke Handler utama, tetapi runnable-nya langsung menulis `startStopBtn`/`statusView`, menjalankan `refreshFromService()`, atau menampilkan `Toast` tanpa memeriksa `isFinishing()`/`isDestroyed()`. `onDestroy` hanya membersihkan callback yang **sudah** antre — post baru sesudahnya tetap dieksekusi, sehingga Activity yang sudah mati masih disentuh (potensi crash/leak context).
- Kini ada satu gerbang `uiHidup()` yang dipakai di dalam runnable `setBusy()`, `refreshFromService()`, `toast()`, dan `jalankanUi()`; pekerjaan UI dari worker berhenti begitu Activity sedang selesai/dihancurkan.

## [Belum rilis] — Audit 257: Walk symlink induk di FileShareProvider benar-benar jalan
- `adaSymlinkInduk()` memakai "jalur cepat": bandingkan `getCanonicalPath()` dengan `getAbsolutePath()`. Semua pemanggil sudah memberi path **kanonis** (`getCanonicalPath()`), dan untuk path kanonis kedua nilai itu selalu sama — sehingga fungsi selalu melaporkan "tak ada symlink" dan walk `lstat` per segmen (yang dimaksudkan menutup TOCTOU tukar-symlink) tak pernah dieksekusi. Komentar proteksinya menyesatkan; satu-satunya lapis nyata tinggal cek kanonis ulang sebelum open.
- Jalur cepat yang tautologis dihapus; walk `lstat` + cek kanonis-vs-absolut per segmen kini selalu dijalankan di perangkat (unit test tetap pulang lewat `diJvmUnitTest()`). Biayanya beberapa `lstat` per open pada path app yang pendek.

## [Belum rilis] — Audit 256: Cipher ChaCha20 tak lagi dibuang filter "cipher kuat"
- `cipherKuat()` hanya menerima suite yang memuat `ECDHE`, `AES_GCM`, atau `AES_256`, padahal komentarnya menyatakan hanya primitif lemah (RC4/3DES/DES/MD5/NULL/anon/EXPORT) yang gugur. Suite **ChaCha20** — `TLS_CHACHA20_POLY1305_SHA256` (TLS 1.3) dan `ECDHE-…-CHACHA20-POLY1305` — adalah primitif kuat tetapi tak memuat tiga penanda itu, sehingga dibuang dari daftar cipher yang dipasang.
- Kini `CHACHA20` ikut diterima (setelah filter primitif lemah), sesuai maksud komentar; uji JVM menambahkan dua suite ChaCha20 dan memastikan 3DES/RC4 tetap ditolak.

## [Belum rilis] — Audit 255: Komentar penanda notifikasi versi tak lagi menjanjikan pengiriman
- Komentar pada penulisan `tg_notified_version` mengklaim penanda ditulis "sesudah kabar dikirim" dan versi akan dikabari lagi bila pengiriman gagal. Kenyataannya `TgBackup.sendMessage()` hanya **memasukkan pesan ke antrean** worker: ia kembali sebelum jaringan disentuh, sehingga kegagalan kirim Telegram tidak memicu percobaan ulang — hanya tercatat di log.
- Komentar kini menyatakan semantik sebenarnya: penanda mencegah spam kabar tiap cek; notifikasi sistem tetap sinkron (gagal = penanda batal, dicoba lagi), sedangkan pengiriman Telegram asinkron dan tidak dijamin. Perilaku kode tidak berubah.

## [Belum rilis] — Audit 254: Plaintext restore dari bot Telegram ber-nama unik dan disapu
- `doRestore()` (jalur `/restore` bot) memakai nama cache **tetap**: `vwtg-restore-bot.zip` untuk arsip unduhan dan `vwtg-restore-bot-dec.zip` untuk hasil dekripsi. Dua operasi restore (dua perintah `/restore`, atau bersamaan dengan restore dari Pengaturan) bisa berebut/menimpa file yang sama, dan bila proses mati sebelum blok `finally` sempat jalan, salinan **plaintext** database tertinggal di cache dengan nama yang akan dipakai ulang.
- Kini arsip unduhan ber-nama unik (`vwtg-restore-bot-<stempel>.zip`) dan hasil dekripsi memakai `TgBackup.berkasPlainSementara()` (`vwtg-plain-…`, dilarang dibagikan `FileShareProvider`) plus `sapuTmpPlainBasi()` di awal operasi agar sisa proses yang mati tak mengendap selamanya.

## [Belum rilis] — Audit 253: Galat callback tombol Telegram tak lagi ditelan
- `tanganiCallback()` membungkus seluruh pemrosesan tombol dengan `catch (Exception ignored)`. Galat setelah spinner dijawab (mis. perintah melempar, data tombol rusak) membuat tombol seolah mati tanpa satu baris pun di log, sehingga pengguna melapor "tombol tak berfungsi" tanpa bukti untuk didiagnosis.
- Kini galat dicatat lewat `ServerService.catatLog` dengan detail yang diredaksi (`TgBackup.redaksiDetailGalat`: token diganti `<token>`, spasi dirapatkan, dipotong 200 char) agar log/ekspor log tak membocorkan token bot.

## [Belum rilis] — Audit 252: Hitungan health lama tak lagi ikut ke server baru
- Saat Start, hanya `healthFails` yang direset; `healthTcpLolos` dan `configSajaBeruntun` (keduanya statis, selamat dari recreate) terbawa dari episode sebelumnya. Bila run lama berakhir dengan 2 lolos-TCP, server baru bisa langsung divonis "gantung" pada kegagalan health pertama (2 sisa + 1 baru = ambang 3) dan dimatikan; sisa `configSajaBeruntun` mempercepat vonis "DB rusak" dengan cara yang sama.
- Kini ketiga hitungan direset bersama saat Start, karena proses baru selalu memulai episode health dari nol. `restartAttempt` tetap tidak direset (batas 5x crash beruntun sengaja lintas start).

## [Belum rilis] — Audit 251: Cabang selalu-benar dan path ganda di `ensureBinary`/`ensureShim`
- `ensureShim()` menulis `e instanceof Exception ? (Exception) e : null` padahal `e` sudah bertipe `Exception` (catch parameter): kondisinya selalu benar dan cabang `null` mustahil, menyamarkan maksud sekaligus menambah satu percabangan yang tak pernah diuji.
- Jalur "binary manual dilewati" membuat ulang `new File(dataDir, "vaultwarden-" + ABI)` padahal `userBin` dengan path yang sama sudah dihitung di atas; dua sumber path bisa menyimpang bila pola nama berubah. Kini memakai `userBin` yang sama.

## [Belum rilis] — Audit 250: Javadoc urutan pemilihan binary disamakan dengan kode
- Javadoc `ensureBinary()` menyebut prioritas manual → cache internal → unduh, padahal kode memakai cache hasil tombol Perbarui lebih dulu (agar Start tak memakai binary lama selamanya), baru manual, cache internal, lalu unduh. Dokumentasi yang salah arah membuat perbaikan berikutnya berisiko "membetulkan" urutan yang justru sudah benar.
- Javadoc kini memuat empat jalur sesuai urutan kode beserta alasan cache update didahulukan dan syarat dilewatinya (revisi patch berubah / file manual ditukar).

## [Belum rilis] — Audit 249: Baris log folder lama tak lagi masuk file folder baru
- Saat Start memakai folder data baru, `flushLogFile()` (kunci `LOG_FILE_LOCK`) dan penggantian `logFile` adalah dua langkah terpisah. `appendLog()` mengecek `logFile != null` di luar kunci, sehingga baris yang masuk di jeda itu tetap di buffer dan tertulis ke file folder **baru** — log folder lama tecampur, dan baris tersebut hilang dari file lama yang seharusnya.
- Kini ada satu pintu `pindahLogFile()`: siram buffer dan tukar `logFile` dalam satu bagian kritis, dan `appendLog()` memeriksa `logFile` di dalam kunci yang sama. Baris yang masuk sebelum penukaran pasti tertulis ke file lama, sesudahnya ke file baru. Uji JVM memastikan buffer lama disiram ke file lama dan tujuan baru terpasang.

## [Belum rilis] — Audit 248: Pembersihan proses tak lagi menghapus proses Start baru
- Pembersihan state proses (`if (process == p) process = null;` + `running = false` + reset penanda + wake lock/status) dilakukan **di luar** `KUNCI_PROSET`, padahal fase exec Start memakai kunci yang sama. Bila Start baru berhasil `pb.start()` di jeda antara cek dan tulis, pembersihan milik proses lama menghapus referensi proses baru: UI menampilkan "Stopped" padahal server baru hidup, wake lock-nya dilepas, dan health/tautan menunjuk penanda kosong. Jalur terdampak: stopper `stopServer()`, `watchProcess()` (dua cabang), health 3x gagal, dan restart dari Telegram.
- Kini semua jalur memakai satu pintu `lepasProses(p, efek)`: cek identitas, pelepasan `process`/`running`, reset penanda, dan efek lanjutan (wake lock/log/status) dijalankan dalam satu bagian kritis `KUNCI_PROSET`. Baca-dan-tandai proses pada jalur restart/health juga dipindah ke dalam kunci. Uji JVM (`ProsesPalsu`) membuktikan pembersihan proses lama tak menyentuh referensi proses baru.

## [Belum rilis] — Audit 247: Proses `--version` dimatikan tegas, bukan hanya destroy
- `Updater.detectVersion()` membersihkan proses di `finally` hanya dengan `p.destroy()` (SIGTERM) tanpa `destroyForcibly()`, padahal komentarnya menjanjikan "dibunuh di SEMUA jalur". Binary yang mengabaikan SIGTERM (atau macet di linker) tetap hidup dan menahan thread/pipa sampai proses app mati.
- Kini pembersihan memakai satu pintu `ServerService.matikanProsesTegas(p, 2000)`: `destroy` → tunggu 2 dtk → `destroyForcibly` (pra-Oreo `destroy`) → tunggu 3 dtk, sama seperti jalur stop/restart server.

## [Belum rilis] — Audit 246: Koneksi unduh yang gagal di tengah jalur tak lagi bocor
- `Updater.bukaIkutiRedirect()` membuka `HttpURLConnection` per percobaan redirect, tetapi hanya menutupnya pada cabang redirect/return. Exception di `HttpsCompat.apply()`, `getResponseCode()`, atau validasi redirect meninggalkan koneksi (socket) menggantung sampai GC — berulang tiap percobaan unduh yang gagal TLS/timeout.
- Kini seluruh badan percobaan dibungkus `try/catch` yang memanggil `disconnect()` sebelum melempar ulang; koneksi yang diserahkan ke pemanggil tetap ditutup pemanggil seperti sebelumnya.

## [Belum rilis] — Audit 245: Pipa SIGPIPE tak lagi menggagalkan workflow acak
- GitHub Actions menjalankan `bash -eo pipefail`. Setiap pipa yang ujungnya keluar lebih dulu (`grep -q`, `head -n 1`) membuat proses hulu kena SIGPIPE (141); dengan `pipefail` status itu menggagalkan langkah. Di `build-binary.yml`, cek `index.html` bisa melaporkan "web-vault.zip tidak berisi index.html" padahal ada (rilis gagal), ekstraksi digest Docker bisa gagal acak, dan pemeriksaan kelengkapan aset bisa salah menyimpulkan "belum lengkap".
- Kini `grep -q` diganti `grep ... >/dev/null` (grep tanpa `-q` membaca seluruh aliran sehingga hulu tidak diputus), dan `head -n 1` diganti `sed -nE '1{...;p;}'` yang juga membaca seluruh aliran. `find | head -1` di `build-apk.yml` diganti `find -print -quit` (tanpa pipa). Perilaku hasil sama, tanpa status 141 acak.

## [Belum rilis] — Audit 244: Daftar bekas-hapusan gagal-terbuka ditutup
- `muat_bekas_hapusan()` menelan `OSError` lalu kembali ke `{"batalTunda"}`. Bila `tools/bekas-hapusan.txt` hilang/tak terbaca (mis. salah path saat skrip dipanggil dari salinan repo), penjagaan regresi hapusan praktis mati: semua nama lain bisa dipakai ulang tanpa satu pun peringatan.
- Kini kegagalan baca mengembalikan `None`, dan `cek_daftar_hapusan()` (dipanggil di setiap mode, termasuk `--semua`) menggagalkan verifikasi dengan pesan jelas; daftar kosong juga digagalkan karena berarti penjagaan tak berisi. Cek pemanggil/penghapusan memakai konstanta yang sama dan tak lagi membaca ulang berkas.

## [Belum rilis] — Audit 243: `kupas_java` mengenal text block Java
- `kupas_java()` (dipakai cek kurung, import mati, locale, dan pola boros) hanya mengenal string satu-baris. Pada text block (`"""…"""`) kutip kedua menutup string kosong lalu kutip ketiga membuka string liar yang menelan kode sesudahnya, sehingga cek kurung/import bisa meleset (false positive maupun temuan yang lolos).
- Kini `"""…"""` dikupas utuh sebagai satu literal, termasuk escape `\` di dalamnya; kode sesudah text block tetap diperiksa. Diuji lokal: isi text block berisi `{`, `//`, dan kutip tidak memengaruhi keseimbangan kurawal maupun deteksi metode setelahnya.

## [Belum rilis] — Audit 242: Import yang dipakai {@link} tak lagi disebut mati
- `cek_import_mati()` mengupas komentar lewat `kupas_java()` sebelum menghitung pemakaian, sehingga import yang hanya dirujuk dokumentasi `{@link Kelas}` / `{@linkplain Kelas#metode()}` dianggap mati. Penulis lalu terpaksa menghapus import **atau** tautan javadoc-nya agar push lolos.
- Kini acuan `{@link}`/`{@linkplain}`/`{@linkcode}` dikumpulkan dari sumber mentah dan dihitung sebagai pemakaian sah; import yang benar-benar hanya disebut di komentar biasa/`{@code}` tetap ditolak. Diuji lokal dengan sumber sintetis (List/Map lewat link lolos, File hanya di `{@code}` tetap tertangkap).

## [Belum rilis] — Audit 241: Verifier tak lagi menuduh komentar/string soal Locale
- `cek_locale_baru()` mencocokkan pola `.toLowerCase()`/`.toUpperCase()` pada baris tambah **mentah**, termasuk baris komentar dan isi string. Akibatnya commit yang hanya menulis dokumentasi (mis. "dulu memakai `.toLowerCase()` tanpa Locale") digagalkan gerbang Verifikasi Patch, dan penulis terdorong menghapus penjelasan yang berguna.
- Kini tiap baris tambah dikupas dulu dengan `kupas_java()` (komentar + literal dibuang) dan baris lanjutan komentar blok (diawali `*`) dilewati; hanya pemanggilan sungguhan tanpa `Locale` yang gagal. Diuji lokal dengan diff sintetis: komentar/string diabaikan, `toLowerCase()` dan `toUpperCase()` asli tetap tertangkap, `toLowerCase(Locale.US)` lolos.

## [Belum rilis] — Audit 240: Kunci privat TLS tak lagi dibuat di storage publik
- Saat `TlsCert.ensure()` di folder internal gagal, `prepareTls()` jatuh ke `data_dir/tls` — yang pada perangkat umumnya `/sdcard/vaultwarden/tls`. `ensure()` lalu membuat CA/leaf baru di sana, sehingga `ca-key.pem`/`key.pem` tertulis di FAT yang tak mengenal chmod dan bisa disalin app lain berizin storage (log lama hanya memberi peringatan setelah kunci sudah tertulis).
- Kini fallback ke folder di storage publik hanya memakai material lama yang sudah lengkap dan valid (`TlsCert.siapPakaiTanpaTulis()`: pasangan cert/key cocok, leaf ditandatangani CA, belum kedaluwarsa) **tanpa menulis apa pun**. Bila belum ada material valid, TLS gagal dengan log yang menyebut penyebabnya (ruang/izin internal) — server tak start dengan kunci bocor. Folder data di internal tetap boleh ditulis seperti biasa. Gerbang `diStoragePublik()`/`bolehFallbackTls()` murni + uji JVM.

## [Belum rilis] — Audit 239: Cert/key/CA diverifikasi berpasangan sebelum dipakai ulang
- `ensure()` memakai ulang `cert.pem`/`key.pem` hanya berdasarkan keberadaan, ukuran, dan sisa masa berlaku; `caOk()` juga begitu untuk `ca.pem`/`ca-key.pem`. Padahal backup tanpa password hanya membawa `ca.pem` + `cert.pem` (kunci privat sengaja tak ikut), sehingga restore bisa meninggalkan leaf lama dengan kunci baru atau CA baru dengan kunci lama. Rocket lalu gagal TLS dengan log yang menunjuk storage/jam (salah arah), dan server tak pernah start.
- Kini sebelum dipakai ulang: `pasanganCertKeyCocok()` memastikan modulus kunci privat sama dengan kunci publik sertifikat (lewat gerbang murni `kunciCocokCert()` + uji JVM), dan `ditandatanganiCa()` memastikan leaf benar-benar ditandatangani `ca.pem`. Bila tak cocok, leaf/CA diregen otomatis dengan log sebab yang tepat.

## [Belum rilis] — Audit 238: Galat satu chat tak menghentikan chat lain + hormati 429
- `kirimSinkron()` membungkus kedua loop (chat × potongan) dalam satu `try`: exception saat mengirim ke chat pertama melewati seluruh chat berikutnya, padahal jalur upload sengaja "coba semua chat" — sebagian penerima tak dapat kabar tanpa jejak.
- Kini tiap potongan dikirim lewat `kirimSatuPesan()` dengan `try/catch` sendiri (galat dicatat, chat berikutnya tetap dicoba), dan balasan **429** diulang sekali setelah jeda `parameters.retry_after` lewat gerbang murni `jedaRetryAfterMs()` (default 1 dtk, dibatasi 1-5 dtk agar antrean pesan STB tak tertahan lama; + uji JVM).

## [Belum rilis] — Audit 237: Detail galat Telegram diredaksi sebelum masuk log/pesan
- `TgBackup.logTgFailure()` mencatat body respons atau `e.getMessage()` mentah (200 char) ke log app. Body dari proxy/captive portal bisa memuat URL `…/bot<token>/sendMessage`, dan log bisa diekspor atau dikirim lewat `/log` — token bot bocor. `TgBot` sendiri sudah melarang ini (hanya kelas exception / `description`).
- Kini satu pintu `redaksiDetailGalat()` (murni + uji JVM) mengganti token pola `<angka>:<rahasia>` dengan `<token>`, merapatkan spasi/baris, dan memotong 200 char; dipakai `logTgFailure()` dan `pesanGalatBackup()` (pesan galat backup ikut dikirim ke chat).

## [Belum rilis] — Audit 236: Sisa plaintext verifikasi backup tak lagi mengendap di cache
- `verifikasi-isi-db.sqlite3` (salinan DB vault) dan `verifikasi-tmp.zip` (hasil dekripsi berisi `key.pem`/`ca-key.pem`) memakai nama **tetap** di cache dan hanya dihapus di `finally`. Proses yang dibunuh/power-loss di tengah backup meninggalkan plaintext itu selamanya, dan dua backup berbeda bisa berebut satu file.
- Kini keduanya (termasuk jalur backup lokal di Settings) memakai nama unik `vwtg-plain-<label>-<acak>` lewat `berkasPlainSementara()`; awalan `vwtg-` membuatnya otomatis dilarang dibagikan `FileShareProvider`. Penyapu `sapuTmpPlainBasi()` (pengganti `sapuDecTmpBasi`, kini juga menyapu `vwtg-plain-*`) dipanggil sebelum file baru dibuat, dan sapu cache `SettingsActivity` (onCreate/onResume/onDestroy) ikut membersihkan sisa `vwtg-plain-*` — keduanya berambang umur 10 menit agar operasi yang sedang berjalan tak terganggu.

## [Belum rilis] — Audit 235: Worker aktivasi PIN tak lagi menyalakan PIN yang sudah dibatalkan
- Worker hash aktivasi PIN selalu menulis `pin_on=true` setelah PBKDF2 selesai, tanpa memeriksa apakah permintaannya masih berlaku. Bila centang dimatikan (konfirmasi "Ya") atau activity ditutup (rotasi/Back) selama hash berjalan, PIN hidup kembali di belakang user: prefs `pin_on=true` sementara centang di layar tampil mati.
- Kini tiap permintaan aktivasi memakai nomor urut `epAktifPin`; pembatalan (centang mati, `onDestroy`) menaikkannya di dalam kunci `kunciAktifPin`, dan worker hanya menulis hash + `pin_on` bila `aktivasiPinMasihBerlaku()` (murni + uji JVM) masih benar. Worker basi tak menampilkan "PIN is active" dan mengembalikan centang ke mati.

## [Belum rilis] — Audit 234: Field rahasia tak lagi disimpan framework ke instance state
- `adminToken`, `pinInput`, `tgToken`, `backupPass`, dan field PIN di layar kunci (`activity_pin.xml`) disimpan otomatis Android ke `Bundle` instance state dan dipulihkan **setelah** `onCreate` membersihkan/mengisi field. Akibatnya rahasia ikut ke state yang bisa ditulis ke disk, dan pemulihan `pinInput` memicu `TextWatcher` → dialog "ganti PIN?" muncul sendiri; menekan OK menulis hash yang tak pernah dikonfirmasi user.
- Kelima field kini memakai `android:saveEnabled="false"` sehingga rahasia tidak ikut tersimpan/dipulihkan; nilainya selalu dibaca ulang dari prefs (atau dikosongkan untuk PIN).

## [Belum rilis] — Audit 233: Dialog konfirmasi PIN tak lagi muncul dari onDestroy
- `onDestroy` sengaja menjalankan debounce `pinTunda` yang masih tertunda agar ketikan PIN tidak hilang saat Back. Runnable itu juga bisa menampilkan `AlertDialog` konfirmasi "Ganti PIN?". Saat rotasi `isFinishing()`/`isDestroyed()` masih false, sehingga guard lama lolos: dialog menempel ke window activity yang sedang dibongkar (`WindowLeaked`) dan konfirmasinya tak pernah bisa diklik.
- Kini flag `pinDialogBoleh` dimatikan sejak awal `onDestroy`; jalur tulis hash tetap jalan (aman), jalur dialog dilewati lewat gerbang murni `bolehDialogPin()` (+ uji JVM). PIN lama tetap berlaku dan kandidat yang tak dikonfirmasi dibuang.

## [Belum rilis] — Audit 232: Start kedua tidak lagi menggantung tanpa proses
- Start yang datang saat episode Start pertama masih berjalan (unduh binary / restore memegang `KUNCI_DATA`) dulu hanya dicatat "Start masih berjalan, dilewati". Karena `startIdTerakhir` sudah naik, episode pertama tidak boleh lagi menutup episode (guard `intentTerbaru`), sedangkan episode baru tak punya thread: `healthActive`, niat START_STICKY, dan notifikasi foreground menggantung tanpa proses dan tanpa restart.
- Kini Start kedua menandai `startSusulanDiminta`; di akhir episode berjalan (setelah `starting` dilepas) Start susulan dijalankan lewat gerbang murni `perluStartSusulan()` — hanya bila episode tidak ditunda, server belum hidup, dan niat jalan masih tersimpan. Stop membatalkan permintaan susulan ini.

## [Belum rilis] — Audit 231: Start saat Stop berjalan tidak lagi hilang
- Menekan Start di jendela saat Stop masih mematikan proses lama (~5-8 dtk) dulu diabaikan total: guard `process == null || !alive(process)` melihat proses yang sedang dibunuh masih "hidup", sehingga `startServerAsync()` tak pernah dipanggil padahal niat jalan, `autoRestart`, `healthActive`, dan foreground sudah disetel. Server tetap mati sampai user menekan Start lagi (status "Port ... dipakai proses lain" menyesatkan).
- Kini Start di jendela itu menandai `mulaiSetelahProsesMati`; thread stopper `vw-stop`/watcher memanggil satu pintu `jalankanStartTertundaStop()` tepat setelah proses benar-benar mati (flag direset lebih dulu, Start hanya dijalankan bila tak ada proses hidup dan niat jalan masih tersimpan). Perintah Stop membatalkan penantian ini agar server tak menyala setelah user menghentikannya.

## [Belum rilis] — Audit 230: Trust store tidak lagi mempercayai CA pengguna
- `HttpsCompat.socketFactory()` menyalin **semua** alias `AndroidCAStore` ke trust store kustomnya, termasuk alias berawalan `user:` (CA yang dipasang pengguna). Sejak `targetSdk 24` platform sengaja tidak mempercayai CA pengguna; menyalinnya kembali membuat CA "Wi-Fi kantor"/MITM dipercaya untuk unduhan binary + `.sha256` (kanal sama, jadi checksum palsu ikut lolos) dan upload backup ke Telegram — pintu eksekusi kode di sandbox app.
- Kini hanya anchor sistem (`aliasCaSistem()`, murni + diuji) yang disalin; alias `user:` dilewati. Anchor tambahan dari aset `github-chain.pem`/override tetap berlaku.

## [Belum rilis] — Audit 229: Perbaiki 10 galat kompilasi APK (tabrakan `KUNCI_GRACE`, `idAwal` hilang)
- `PinGate` mendeklarasikan dua anggota bernama `KUNCI_GRACE`: objek kunci `private static final Object KUNCI_GRACE` (untuk `synchronized` cek grace) dan konstanta hasil `static final int KUNCI_GRACE = 1`. javac menolak kelas itu: `variable KUNCI_GRACE is already defined` plus `unexpected type` di ketiga `synchronized (KUNCI_GRACE)`. Objek kunci kini bernama `KUNCI_BERSAMA` (konstanta hasil dan `PinGateTest` tidak berubah) dan dua blok javadoc `PIN_GRACE_MS` yang menumpuk digabung.
- `ServerService.startServer()` memakai `idAwal` pada guard "jangan timpa status perintah Start yang lebih baru", padahal `idAwal` hanya ada di `startServerAsync()` (`cannot find symbol`). `startServer()` kini menangkap episodenya sendiri di awal (`final int idEpisoda = startIdTerakhir`) dan guard memakai nilai itu, sehingga semantik lama terjaga tanpa parameter baru.
- Dampak: build APK kembali bisa dikompilasi (10 error → 0). Tidak ada perubahan perilaku; kelas ini hanya berubah nama variabel/field internal.

## [Belum rilis] — Audit 228: Spasi berlebih di blok skrip `build-binary.yml`
- Blok `run:` patch TLS diakhiri tiga baris kosong (dua di antaranya berisi spasi) sebelum langkah berikutnya. Diringkas jadi satu baris kosong; tidak ada perubahan perintah. YAML divalidasi ulang (`yaml.safe_load`) dan blok skrip lolos `bash -n`.

## [Belum rilis] — Audit 227: Galat cek binary di `ensureBinary` tidak lagi ditelan
- Tiga `catch (Exception ignored)` di `ServerService.ensureBinary()` menelan sebab kegagalan cek binary cache (jalur update, cache internal, dan throttle unduhan). Saat pengguna melaporkan "binary cache rusak/unduh berulang", log hanya menyebut hasil akhir tanpa alasan (izin, `ETXTBSY`, prefs korup).
- Ketiganya kini mencatat `Exception.toString()` + langkah berikutnya ("lanjut jalur lain" / "tetap coba unduh") ke log aplikasi. Tidak ada perubahan alur: kegagalan tetap jatuh ke jalur cadangan seperti sebelumnya.

## [Belum rilis] — Audit 226: Batas potret TLS dipisah dari batas config zip
- `potretTls()` membaca cert/key/ca memakai `readAllBytes()` yang batasnya bernama `BATAS_CONFIG_JSON` — nama konstanta menyesatkan dan menyiratkan batas yang sama untuk dua konteks berbeda; bila suatu saat rantai sertifikat wajar melebihi 1 MB, potret TLS gagal senyap (`catch` diabaikan) dan rollback restore kehilangan berkas itu.
- Kini ada `BATAS_TLS_POTRET` eksplisit (1 MB, tetap jaring pengaman) dan `potretTls()` memakai `bacaTerbatas(in, BATAS_TLS_POTRET)`; `readAllBytes()` didokumentasikan sebagai pembaca `app-config.json` saja (nama dipertahankan karena pemindai CI memakai pola nama itu dan `java.nio.file.Files.readAllBytes` dipakai di test).

## [Belum rilis] — Audit 225: Kerapian javadoc & spasi `ServerService`
- Javadoc "Pola statis..." terpisah dari field `POLA_SUFIKS_VERSI` oleh baris kosong (dianggap yatim), dan `configSajaBeruntun` punya dua blok javadoc berurutan (blok kedua menimpa keterangan pertama di sebagian alat baca). Keduanya digabung/ditempel ke deklarasi yang dimaksud.
- Empat baris kosong berurutan sebelum `getPid()` diringkas jadi satu. Tidak ada perubahan perilaku.

## [Belum rilis] — Audit 224: Salin log memakai satu pengambilan ClipboardManager
- `LogActivity.salinLogClipboard()` mengambil `ClipboardManager` + memeriksa null, lalu memanggil `salinClipboardBersihOtomatis()` yang mengambil service yang sama sekali lagi. Pemeriksaan ganda itu juga membuat toast "Log copied" tetap muncul saat `setPrimaryClip` gagal.
- `salinClipboardBersihOtomatis()` kini mengembalikan hasil `salinBersihOtomatis()`; pemanggil cukup memakai nilai itu (gagal → "Clipboard unavailable.", sukses → "Log copied to clipboard."). Satu pengambilan service, satu sumber hasil.

## [Belum rilis] — Audit 223: Restore zip membaca arsip satu lintasan sebelum ekstraksi
- `restoreFromZipIsi()` membuka zip **dua kali** sebelum ekstraksi: pass `ZipInputStream` untuk mencari `app-config.json`, lalu `ukuranIsiZip()` (`ZipFile`) untuk menghitung total isi (cek ruang). Pada STB dengan storage lambat, restore jadi lebih lambat dari perlu.
- Keduanya digabung menjadi `pindaiZipRestore()` yang mengembalikan `PindaiZip{config, ukuran}` dalam satu lintasan `ZipFile` (batas `BATAS_CONFIG_JSON` tetap berlaku; entri berukuran tak diketahui dilewati seperti sebelumnya; ukuran yang melewati `BATAS_UNZIP_RESTORE` langsung dipotong ke batas+1). `ukuranIsiZip()` kini pembungkus tipis agar pemanggil/uji lama tetap sah (javadoc-nya menempel ke metode, tidak menumpuk dua blok). Uji JVM baru memastikan config dan ukuran terbaca dari satu panggilan; ekstraksi tetap lintasan tersendiri karena menulis berkas.

## [Belum rilis] — Audit 222: Cabang normalisasi port mati di `startServer` dibuang
- `ServerService.startServer()` mengambil `port = currentPort()` lalu mem-parse dan menyembuhkan port `<1024`/`>65535` sendiri. Padahal `currentPort() → effectivePort() → normalisasiPort()` sudah menjamin 1024–65535, sehingga `parseInt` tak pernah gagal dan cabang penyembuhan itu tak terjangkau (termasuk tulis ulang prefs di dalamnya); `port.trim()` kedua juga alokasi sia-sia.
- Kini parse langsung sekali dengan komentar sumber kebenaran tunggal. Perilaku pengguna tidak berubah: port tak valid tetap jatuh ke default di `normalisasiPort()`.

## [Belum rilis] — Audit 221: Kode mati & `try/catch` mubazir di jalur update binary
- `Updater.downloadBinaryInner` menyimpan `asap` ke `installed` lalu memakai cabang `!fallback ? latest : null` untuk `KEY_UPDATE_VERSION` — padahal `detectVersion()` yang `null` sudah `throw` beberapa baris sebelumnya, jadi `installed` selalu non-null, cabang itu tak terjangkau, dan `if (installed != null)` selalu benar (pesan fallback di akhir juga mati). Semua disederhanakan ke `asap` langsung; nilai yang disimpan tetap sama.
- Pemeriksaan jumlah entri zip web-vault dibungkus `try { ... } catch (IOException e) { throw e; }` — catch hanya melempar ulang. Kini dipanggil langsung (batas ukuran byte tetap diakumulasi di loop tulis).

## [Belum rilis] — Audit 220: Pratinjau log layar awal mengejar ekor, bukan pangkal
- `MainActivity.refreshHomeLog()` mereset `lastLogLen = 0` + mengosongkan layar setiap buffer log dipangkas (300 KB), lalu menampilkan potongan **tertua** dari buffer sisa. Bila laju log melebihi `BATAS_DELTA_TICK` (8 KB) per tick, offset tak pernah sampai ke ekor sehingga layar terus menampilkan log lama dan melompat tiap trim. Komentar "tiap 500 ms" juga usang (loop nyata 1–2 dtk).
- Kini saat buffer dipangkas **atau** tertinggal lebih dari `BATAS_LOMPATAN` (4× jendela tick), offset dilompatkan ke ±8 KB terakhir pada batas baris utuh (pindai `charAt` manual, tanpa alokasi) lalu layar diisi ulang — pengguna selalu melihat baris terbaru, dan penyamaran rahasia tetap bekerja per baris (tidak ada token terpotong separuh).

## [Belum rilis] — Audit 219: Start dilanjutkan otomatis setelah izin storage diberikan
- `MainActivity.saveAndStart()` dan `SettingsActivity.saveAndStart()` memanggil `StoragePerm.siapStart()` yang memunculkan dialog izin lalu mengembalikan `false`; `onRequestPermissionsResult` hanya menampilkan toast saat izin **ditolak**. Akibatnya, pada instal pertama (atau setelah izin dicabut) pengguna menekan Start, memberi izin, tetapi server tetap tidak jalan sampai ia menekan Start untuk kedua kalinya.
- Kedua activity kini mengingat bahwa Start tertahan menunggu izin (`mulaiUlangSetelahIzin`, direset di awal `saveAndStart`). Bila callback mengembalikan izin diberikan, `saveAndStart()` dipanggil ulang sekali (dengan guard `isFinishing`/`isDestroyed`); penolakan tetap memakai pesan lama dan tak pernah memicu Start liar.

## [Belum rilis] — Audit 218: Aset web-vault korup tak lagi memacetkan rilis binary
- Jalur "pakai ulang" di `build-binary.yml` hanya memeriksa **keberadaan** `web-vault.zip` di rilis lalu mengunduhnya; `unzip -t` baru dijalankan sesudahnya. Bila aset itu sudah korup/terpotong, setiap run binary berikutnya memakai ulang aset rusak, gagal di `unzip -t`, dan `exit 1` — permanen sampai aset dihapus manual (padahal aturan repo melarang edit aset rilis manual).
- Kini zip lama diunduh dan diuji utuh **sebelum** diputuskan dipakai ulang. Bila unduhan atau `unzip -t` gagal, aset lokal dibuang dan web-vault dibangun ulang dari digest Docker resmi (dengan `::warning::` di log); pemeriksaan isi final (`unzip -t`, `index.html`, ukuran minimum) tetap berlaku untuk hasil pakai-ulang maupun hasil build baru.

## [Belum rilis] — Audit 217: WakeLock dilepas saat service dihancurkan
- `ServerService.onDestroy()` memanggil `p.destroy()` lalu langsung `isProcessAlive()`. Karena `destroy()` asinkron, `exitValue()` masih melempar sesaat sehingga proses dianggap hidup dan `releaseWakeLock()` terlewat: `PARTIAL_WAKE_LOCK` (timeout 12 jam) tetap dipegang padahal binary sudah dibunuh — deep-sleep hilang dan baterai terkuras bila service dihancurkan sistem tanpa recreate (tekanan memori, update app, START_STICKY).
- Jalur ini memang membunuh proses, jadi kunci tak berguna lagi: `releaseWakeLock()` kini dipanggil tanpa syarat (idempoten) sesudah `destroy()`. Recreate START_STICKY tetap memasang ulang kunci lewat `acquireWakeLock()` saat server dijalankan lagi.

## [Belum rilis] — Audit 216: Toast izin saat simpan log tidak lagi menumpuk dialog
- `LogActivity.exportLogTxt` memanggil `StoragePerm.mintaIzinBilaPerlu` lalu **selalu** menampilkan toast `izin_storage_belum` — teksnya "Storage permission not granted yet — Start cancelled", padahal aksinya menyimpan log dan dialog izin baru saja dimunculkan (dua pesan bertumpuk, persis pola yang sudah diperbaiki di `StoragePerm.siapStart`). Bila permintaan izin gagal ditampilkan, flag `simpanUlangSetelahIzin` juga tertinggal `true` sehingga penyimpanan bisa jalan di luar konteks izin.
- Kini toast hanya muncul bila `mintaIzinBilaPerlu` mengembalikan `false` (dialog tak bisa ditampilkan); flag direset di jalur itu, dan pesan memakai string baru `izin_simpan_belum` yang khusus konteks simpan (Inggris + Indonesia).

## [Belum rilis] — Audit 215: Aturan kunci PIN dipusatkan di PinGate
- `MainActivity.maybeShowPinLock()` dan `SettingsActivity.maybeShowPinLock()` menyalin ~30 baris aturan yang sama (PIN mati → buang grace, grace aktif → lewati, hash hilang → matikan PIN + catat, selain itu buka layar PIN). Dua salinan itu bisa menyimpang tanpa ketahuan; salah satunya meleset berarti satu layar fail-open (terbuka tanpa PIN).
- Keputusan murni `PinGate.putusKunci(pinOn, dalamGrace, pinHash)` (empat hasil: `KUNCI_MATI`/`KUNCI_GRACE`/`KUNCI_RUSAK`/`KUNCI_BUKA`) + pembungkus `PinGate.perlukanLayarPin(Context)` yang menjalankan efek sampingnya kini menjadi satu-satunya sumber aturan. Kedua activity tinggal membuka `PinActivity` + guard anti-tumpuk. Uji JVM baru `PinGateTest` (4 kasus) menutup seluruh kombinasi; `AGENTS.md` mencatat 18 kelas test.

## [Belum rilis] — Audit 214: AlarmReceiver memvalidasi action sebelum menunda boot
- `AlarmReceiver.onReceive` dulu menjalankan cabang tunda-boot (baca prefs + pasang alarm tunda) **sebelum** memeriksa action. Selama 5 menit pertama setelah boot, intent apa pun yang berhasil mencapai receiver (mis. explicit intent dengan action sembarang, termasuk spoof dari app lain) tetap menulis prefs dan menjadwalkan alarm tunda, padahal action itu tak akan pernah diproses oleh cabang mana pun di bawahnya.
- Kini `intent` null dan action yang bukan milik app (`ACTION_HARIAN`/`ACTION_TUNDA`) maupun siaran waktu sistem ditolak paling awal. Penolakan action ganda di jalur alarm umum dihapus karena sudah dijamin oleh validasi awal (komentar disesuaikan).

## [Belum rilis] — Audit 213: Polling bot tak pernah dijalankan di main thread
- `TgBotReceiver.onReceive` memakai `tugas.run()` sebagai fallback terakhir saat pool bot penuh **dan** pembuatan thread baru gagal. Itu menjalankan `TgBot.pollOnce()` (long-poll Telegram 15–35 detik + I/O prefs) langsung di main thread dari dalam `onReceive`: UI beku, ANR, lalu proses/receiver dibunuh sistem — justru saat perangkat sedang kekurangan thread.
- Fallback kini hanya menutup `PendingResult` + melepas wakelock (satu pintu `selesaikan`) dan mencatat alasannya; alarm polling 20 detik berikutnya memicu ulang saat thread tersedia. Tidak ada jalur yang memblokir main thread.

## [Belum rilis] — Audit 212: Galat factory PBKDF2 transien tak lagi menurunkan hash permanen
- `PinCrypto.pabrikTersedia()` mengingat `FALSE` untuk **semua** exception `SecretKeyFactory.getInstance()`, dan `derive()` menulis `pabrikAda = FALSE` pada exception apa pun dari `generateSecret()`. Satu gangguan sesaat (provider sibuk, alokasi gagal) membuat proses hidup menganggap perangkat tak punya PBKDF2-SHA256 selamanya: hash baru ditulis dengan iterasi legasi 12rb dan setiap buka PIN memakai jalur manual yang 2–3× lebih lambat, padahal factory perangkat sehat.
- Kini hanya `NoSuchAlgorithmException` (algoritma memang tak tersedia) yang diingat permanen; galat transien tidak di-cache sehingga deteksi diulang pada panggilan berikutnya. Untuk panggilan yang gagal, hasil manual tetap dipakai (bit-identik dengan factory), jadi verifikasi tidak berubah. Klasifikasi murni `pabrikHilangPermanen(Throwable)` + uji JVM.

## [Belum rilis] — Audit 211: Tombol Stop di Pengaturan, toast jadwal palsu, hint & antrean log
- Tombol Start/Stop di Pengaturan selalu memakai latar biru `TasirinBtnPrimary` walau teksnya "Stop" (ikon berhenti di atas tombol aksi biasa), sedangkan layar awal sudah memakai `bg_btn_stop` merah saat berjalan. Tombol bawah kini ikut berganti latar (`bg_btn_stop`/`bg_btn_primary`) selagi teks/ikonnya berganti.
- Listener `tgAutoCheck` menampilkan toast "Automatic backup enabled/disabled" juga saat `setChecked` programatik (impor config/restore) mengubah nilai — pengguna diberi tahu perubahan yang tak ia lakukan. Listener kini membandingkan nilai prefs lama; jadwal tetap dipasang ulang (idempoten), toast hanya untuk perubahan sungguhan.
- Hint versi manual masih hardcode bahasa Indonesia di kode (`"mis. 1.32.0 (kosongkan = terbaru)"`) sehingga tampil Indonesia di app berbahasa Inggris. Dipindah ke `strings.xml` (`versi_manual_hint`, default Inggris + `values-in`).
- `LogActivity` mengabaikan hasil `Util.jalankanBg` di empat jalur (salin, bagikan, simpan .txt, dialog crash log): saat antrean tugas penuh UI sudah menampilkan "Preparing/Saving…" padahal tak ada tugas berjalan. Keempat jalur kini memberi tahu "Task queue is full, try again shortly.".

## [Belum rilis] — Audit 210: Klaim ukuran APK & komentar usang diselaraskan
- README (Inggris/Indonesia/Rusia) dan `AGENTS.md` masih menyebut "APK ~0,1 MB", padahal aset rilis terkini 238.952–239.093 byte (~0,23 MB). Ketiganya kini "~0,25 MB" (angka dibulatkan ke atas agar tak menjanjikan ukuran yang mustahil).
- `AndroidManifest.xml` menjelaskan rotasi memuat `layout-land/activity_main.xml`, padahal layout-land hanya berisi `activity_pin.xml` (MainActivity sengaja satu layout untuk dua orientasi). Komentar diperbaiki agar alasan tanpa `configChanges` tetap akurat.
- `build-binary.yml` menyebut resolve "skip bila rilis tag itu sudah ada" — kenyataannya skip hanya bila **keenam** aset non-APK lengkap (tag bisa dibuat workflow APK lebih dulu). Komentar unggahan juga masih menyebut "hapus duplikat per ID", padahal jalur sekarang memakai `gh release upload --clobber`. Keduanya diselaraskan dengan kode.
- `tools/pasang-hook.sh` menyebut "workflow Verifikasi Patch" yang tak ada; verifikasi adalah job di kedua workflow CI. Teks cadangan diperbaiki.

## [Belum rilis] — Audit 209: Salinan log di UI thread dibatasi, VmRSS di-memo, hash ganda didokumentasikan
- `MainActivity.refreshHomeLog()` menyalin `logBuffer.substring(lastLogLen, n)` **utuh** di UI thread sebelum memotongnya ke `BATAS_DELTA_TICK` (8 KB). Saat log meledak (buffer sampai 300 KB dalam satu tick) UI thread membayar salinan ratusan KB hanya untuk membuang 99%-nya, tiap 500 ms. Salinan kini dibatasi `lastLogLen + BATAS_DELTA_TICK + 2` karakter di dalam lock; pemotongan batas baris tetap memakai karakter di sekitar batas sehingga perilaku (baris utuh, surrogate aman, offset mentah) tak berubah.
- `ServerService.processRssKb()` membaca `/proc/<pid>/status` setiap panggilan. Kini hasil VmRSS di-memo per PID selama 5 detik (selaras jeda tampilan status), sehingga worker Pengaturan dan perintah `/status` bot yang berdekatan berbagi satu pembacaan. Komentar lama "dipanggil UI tiap 5 dtk" juga diperbaiki: pemanggil beratnya worker (UI hanya membaca cache).
- Hash SHA-256 ganda pada pemasangan binary (dihitung saat unduh, lalu dihitung ulang di `pasangHasilUnduh`) tetap dipertahankan dan alasannya didokumentasikan: antara kedua titik file tmp di-chmod dan dieksekusi uji asap, jadi gerbang rename harus membuktikan byte yang benar-benar dipasang. Satu pass 20 MB lebih murah daripada memasang binary yang berubah di tengah jalan.

## [Belum rilis] — Audit 208: Sisa kode mati (field, konstanta, overload, cabang) dibuang
- `ServerService.lastStartTime` hanya ditulis (`startServer`) dan tak pernah dibaca — uptime/health memakai `lastStartElapsed` (monotonik). Field + penulisannya dihapus.
- `SettingsActivity.kuncianCekAt` (dengan komentar "throttle baca prefs kuncian") tak pernah dipakai; dihapus.
- `Updater.KEY_WV_FALLBACK_AT` hanya ditulis/dihapus, tak pernah dibaca: keputusan fallback web-vault sudah berbasis isi (`fallbackTercatatUntuk` + probe `.sha256`), bukan timer. Konstanta + tulis/hapusnya dibuang; `KEY_WV_FALLBACK_FOR` tetap (dibaca).
- Overload `Updater.pindaiHexDariBaris(List)` hanya dipakai unit test (produksi memakai varian 2-argumen dengan nama asset); overload dihapus dan uji memanggil varian 2-argumen (`null` = tanpa preferensi nama). Nama metode masih dipakai, jadi tak masuk `bekas-hapusan.txt`.
- `bg_chip.xml` punya `<item android:state_selected="true">`, tetapi tak ada satu pun view chip yang memanggil `setSelected`/memakai state itu; item + komentar "terpilih primary solid" dihapus.
- `ServerService.startForegroundCompat()` memanggil `createChannel()` lagi padahal `onCreate()` (selalu jalan lebih dulu untuk instance service) sudah membuatnya; panggilan ganda dibuang.
- `pingRinci()` menginisialisasi `HasilCoba config = new HasilCoba(-1, "")` yang langsung ditimpa di kedua cabang; kini dideklarasikan tanpa nilai awal.
- `MainActivity.saveAndStart()` memeriksa `portNum < 1024 || > 65535` padahal `effectivePort()` menjamin hasil `normalisasiPort()` (1024-65535); cabang + `try/catch` yang tak terjangkau dihapus.

## [Belum rilis] — Audit 207: Uji shim getrandom juga menguji penerusan `syscall()` lain
- Shim mencegat **seluruh** simbol `syscall()` (bukan hanya nomor `SYS_getrandom`): nomor lain diteruskan ke `dlsym(RTLD_NEXT, "syscall")`. Uji host `shim/test_interpose.c` hanya memanggil `getrandom()` dan `syscall(SYS_getrandom)`, sehingga regresi jalur penerusan (dlsym gagal, `va_arg` kacau, deadlock reentransi constructor) lolos CI dan baru muncul di perangkat sebagai kegagalan syscall tak terkait (I/O, close, tulis) saat shim dipasang.
- Uji kini menambah tiga penerusan nyata: `SYS_getpid` harus sama dengan `getpid()`, `SYS_close(-1)` harus mengembalikan `-1` + `EBADF` (errno dari kernel, bukan `ENOSYS` shim), dan `SYS_write` 3-argumen harus menulis tepat sejumlah byte. Semua argumen varargs diisi eksplisit agar hook yang membaca enam argumen tak menyentuh nilai tak terdefinisi.

## [Belum rilis] — Audit 205: Loop coba-ulang verifikasi aset rilis tak lagi dimatikan `set -e`
- Langkah "Siapkan & publish 6 asset non-APK" memakai `set -e`, lalu loop verifikasi 3x mengambil data dengan `DAFTAR=$(gh release view ...)` dan `JAUH=$(gh release view ...)`. Bila GitHub menjawab 502/rate-limit/timeout sesaat setelah unggah (justru kondisi yang ingin dicoba ulang), assignment yang gagal langsung menghentikan langkah; `sleep 10` + percobaan berikutnya **tak pernah berjalan** dan rilis yang asetnya mungkin sudah lengkap tetap dinyatakan gagal (atau sebaliknya, `--latest` tak dipasang).
- Kedua pengambilan data kini memakai `|| true` (ditambah `stat` lokal dengan fallback), sehingga kegagalan sesaat hanya menandai `OK=false` dan loop benar-benar mengulang. Perilaku `set -e` diverifikasi lokal dengan `bash -ec`: `X=$(false)` keluar, `X=$(false || true)` lanjut.

## [Belum rilis] — Audit 206: Isi web-vault diverifikasi dan payload rilis dicek lewat digest server
- Verifikasi pasca-unggah membandingkan `web-vault.zip.sha256` di rilis dengan sidecar lokal — keduanya dibuat CI sendiri, jadi sirkular: payload yang terpotong/diam-diam memakai aset lama tetap lolos selama sidecar ikut terunggah. Isi `web-vault.zip` sendiri tak pernah diperiksa, termasuk saat zip dipakai ulang dari rilis lama.
- Sebelum unggah, `web-vault.zip` (baru maupun hasil pakai ulang) wajib lolos `unzip -t`, memuat `index.html` di akar (syarat `Updater` — tanpa itu web UI 404), dan berukuran ≥1 MB. Payload rilis kini juga dibandingkan dengan **digest SHA-256 yang dihitung server GitHub** (`gh api ... .assets[].digest`) terhadap `sha256sum` berkas lokal, sehingga bukti kecocokan tak berasal dari sidecar buatan sendiri. Ukuran dan isi sidecar tetap dicek.

## [Belum rilis] — Audit 204: `verifikasi-patch.py --semua` memeriksa seluruh repo dan cek diff
- `--semua` mengisi daftar berkas dengan `jalan_repo("app")`, yang default-nya hanya `*.java`. Resource `res/**`, `AndroidManifest.xml`, `app/build.gradle.kts`, `shim/`, `.github/workflows/`, `scripts/`, dan `tools/` tak pernah masuk daftar sehingga cek aturan repo (berkas terlarang/binary >1 MB, termasuk seluruh isi `app/src/main`) dan hitungan "berkas diperiksa" buta terhadap perubahan non-Java. `jalan_repo()` kini menerima `ext=None` (semua berkas) dan `--semua` memakainya: 39 → 134 berkas pada repo ini.
- Di repo bersih, `--semua` juga tak punya `diff` sehingga delapan cek berbasis diff (metode hilang, hapusan belum tercatat, uji baru/berubah, pola berisiko/boros, izin/definisi baru, `diff --check`) dilewati diam-diam sementara hasilnya "LOLOS". Bila tak ada diff kerja, `--semua` kini memakai `HEAD~1...HEAD` (rentang cadangan yang sudah dipakai jalur CI) dan mencetak info sumbernya; bila repo belum punya induk, info menyatakan cek diff dilewati.

## [Belum rilis] — Audit 203: Gerbang ruang backup memakai ukuran DB, bukan ambang tetap 10 MB
- Sebelum backup, `TgBackup.backupNowIsi` hanya menolak bila sisa ruang < 10 MB, padahal zip yang dibangun memuat **salinan utuh** `db.sqlite3` (+WAL/SHM), dan jalur terenkripsi menahan zip plaintext **plus** salinan hasil dekripsi untuk verifikasi di cache internal. DB 200 MB di perangkat dengan sisa 150 MB lolos gerbang, lalu gagal di tengah `createBackupZip`/verifikasi dengan pesan ENOSPC membingungkan dan sisa file `.tmp`; pada STB kecil storage bisa penuh sampai mengganggu server.
- Kebutuhan ruang kini diproyeksikan dari ukuran isi DB: folder data butuh `isi DB + 5 MB` (hasil akhir `.zip`/`.enc`), cache internal jalur terenkripsi butuh `2 × isi DB + 5 MB` (zip plaintext + salinan verifikasi), dengan lantai 10 MB untuk DB kecil. Pesan galat menyebut kebutuhan dan sisa ruang, bukan hanya sisa. Logika murni `ruangBackupDibutuhkan()` + `panjangBerkas()` diuji.
- `folderCekRuangBackup()` tidak berubah: volume yang diperiksa tetap sama (cache hanya saat terenkripsi, folder data selalu), jadi tak ada volume baru yang bisa menggagalkan backup lama secara mendadak.

## [Belum rilis] — Audit 202: Balasan sesi PIN bot dibedakan dari obrolan, PIN basi dihapus
- Selama sesi PIN tombol terbuka (5 menit), `TgBot` meneruskan **setiap** teks non-perintah sebagai PIN ke `handlePinPending` → `authDangerous` → `PinGate.catatHasil(false)` bila tak cocok. Obrolan biasa ("halo", "tolong bantu") karena itu dihitung sebagai PIN salah, dan lima pesan obrolan mengunci PIN aplikasi 5 menit — `PinGate` dipakai bersama layar kunci Main/Settings, jadi pengguna ikut terkunci dari app. Sesi juga langsung ditutup sehingga aksi yang tadi dipilih hilang.
- Kini `pinDariBalasan()` (murni + uji) memisahkan percobaan PIN dari obrolan: bentuk eksplisit `PIN:...` selalu diterima (PIN boleh ber-spasi/huruf murni), sedangkan tanpa penanda hanya satu token tanpa spasi minimal 4 karakter yang berdigit. Kata huruf murni/obrolan banyak kata tidak diverifikasi, tidak menambah hitungan lockout, dan **tidak** menutup sesi; bot mengirim petunjuk bentuk `PIN:...` agar PIN tanpa digit tak gagal diam. PIN implisit `ab12`/`123456` tetap seperti sebelumnya.
- Sesi yang sudah lewat 5 menit dulu dikembalikan `ambilPending` sebagai "tak ada sesi" (`""`), sehingga pesan PIN yang datang terlambat tidak dieksekusi **dan tidak dihapus** — PIN asli menempel selamanya di riwayat cloud. `ambilPending` kini mengembalikan `AksiPending` yang membedakan "tak ada sesi" dari "sesi basi"; balasan non-perintah pertama setelah sesi basi dihapus dari chat (best-effort, satu pintu `hapusPesanPerintah`) dan pemilik diberi tahu bahwa aksinya tidak dijalankan. Balasan itu tak bisa dibedakan dari PIN yang terlambat, jadi penghapusan berlaku untuk pesan apa pun — tetapi hanya sekali karena sesi langsung dibersihkan. Isi sesi yang rusak/asing tidak ditandai basi agar pesan pengguna tak ikut terhapus.

## [Belum rilis] — Audit 201: Bilah judul mode malam terbaca (kontras 1,75:1 → ±11:1)
- Mode malam memakai `primary` = `#90CAF9` (biru muda) sebagai latar bilah judul, sedangkan judul, subversi (`TasirinToolbarTitle`/`Subtitle`), ikon titik-tiga, panah kembali, dan ikon Stop semuanya putih. Rasio kontras putih di atas `#90CAF9` hanya ±1,75:1 (WCAG AA butuh 4,5:1), jadi header praktis tak terbaca — dan TV/STB umumnya memakai mode malam. Splash juga ikut menyala terang di tema gelap.
- Ditambah warna `toolbar_bg` yang khusus untuk bilah judul: `#1565C0` di mode siang (sama seperti sebelumnya) dan `#1E3A5F` di mode malam (kontras putih ±11:1, sewarna `primary_container`). `activity_main.xml`, `activity_settings.xml`, dan `bg_splash.xml` memakai warna baru ini; `primary` tetap dipakai tombol/aksen sehingga teks tombol malam yang gelap (`btn_primary_text` = `#1A1A1A`) tetap kontras.
- Komentar gaya toolbar diperbarui agar tak lagi menyebut "putih di atas primary".

## [Belum rilis] — Audit 200: Notifikasi foreground mengikuti status server
- Teks notifikasi dihitung sekali di `startForegroundCompat()`. Pada Start normal, `running` masih `false` saat service dinaikkan (start berjalan asinkron), jadi notifikasi berbunyi "Menjalankan tugas latar..." **sepanjang** server melayani; sebaliknya setelah server mati tanpa `startForegroundCompat()` baru, notifikasi tetap "Server aktif di port ...". Di STB/TV yang hanya menampilkan notifikasi, status ini praktis satu-satunya indikator.
- Notifikasi kini dibangun oleh satu pintu `bangunNotifikasi()` dan disegarkan dari `setStatus()` lewat `perbaruiNotifikasi()` (hanya bila service memang sedang foreground — `notifForeground`, agar tak memposting notifikasi biasa yang menempel setelah service turun). Isinya: "Server aktif di port N" saat proses hidup, atau baris pertama status yang dirapikan dan dibatasi 80 karakter saat belum (logika murni `teksNotifikasi()` + uji).
- Semua `stopForeground` dipusatkan di `turunkanForeground()` yang membersihkan penanda tersebut; `onDestroy` juga membersihkannya agar thread yang masih hidup tak memposting notifikasi setelah service mati. Try/catch ganda di pemanggil ikut dihapus (helper sudah menelan galat).

## [Belum rilis] — Audit 199: Transisi Start↔Stop pada exec proses dijadikan satu bagian kritis
- Fase "cek `batalStart` lalu `pb.start()` + `process = ...` + `running = true` + `acquireWakeLock()`" berjalan tanpa kunci, sementara `stopServer()` memutuskan berdasarkan `process` dengan kunci yang sama-sama tidak ada. Dua hasil buruk: (a) Stop yang masuk setelah cek `batalStart` tetapi sebelum `pb.start()` tetap membiarkan server lahir — status sudah "Stopped" dan niat jalan sudah dicabut, lalu cleanup Stop memanggil `stopSelf`/`onDestroy` yang membunuh proses baru; (b) bila Stop melihat `process` yang baru di-assign, stopper menandai proses "stop sengaja" dan membersihkan `process/running`, lalu thread start menimpa `prosesStopDisengaja = null` + `running = true`. Proses yang mati tak lagi dianggap stop sengaja (status "Stopped (exit code 143)" + pesan crash palsu), dan `running=true` bisa tertinggal tanpa proses (UI "Running", tombol jadi Stop, wakelock dipegang) karena health tick sudah dibatalkan Stop.
- Ditambah `KUNCI_PROSET`: cek-batal-vs-luncur beserta pemasangan `process`/`prosesStopDisengaja`/`running`/wakelock/`idEpisodaProses` kini satu bagian kritis, dan `stopServer()` mengambil keputusan "proses ada?" + `tandaiStopDisengaja()` + `batalStart` di bagian kritis yang sama. Urutan jadi pasti: Start lebih dulu → Stop melihat proses hidup dan mematikannya; atau Stop lebih dulu → Start melihat `batalStart` dan tak meluncurkan apa pun. Pesan "Start dibatalkan" juga tak lagi menimpa status "Running" bila Start baru datang sesudah Stop (dijaga `idAwal == startIdTerakhir`).
- Penantian proses (hingga ~8 dtk) tetap di thread stopper, di luar kunci, jadi Stop tidak memblokir UI lebih lama dari sebelumnya.

## [Belum rilis] — Audit 198: Episode Start yang melahirkan proses diserahkan ke watcher
- `startServerAsync` menilai `startGagalPermanen(...)` di `finally` memakai keadaan saat itu (`running`/`process`). Bila proses crash dalam milidetik — port bentrok, DB terkunci, panic — watcher sudah men-null-kan `process`, mengeset `running=false`, lalu **menjadwalkan** `restartTunda`; thread start yang masih menyelesaikan kirim Telegram lalu melihat "tak ada proses, tak running" dan memanggil `healthActive=false` + `simpanNiatJalan(false)` + `stopForeground` + `stopSelfResult`. Akibatnya restart yang baru dijadwalkan tak pernah jalan (`restartTunda` mensyaratkan `healthActive`), padahal status dan Telegram sudah menjanjikan "restart otomatis"; pengguna harus menekan Start manual. `restartTunda` juga bisa dibuang diam-diam oleh guard `starting` di `startServerAsync` ("Start masih berjalan, dilewati") saat thread start masih sibuk.
- Kepemilikan episode kini eksplisit: `idEpisodaProses` mencatat startId yang melahirkan proses. `startGagalPermanen()` menerima `prosesPernahLahir` dan **tidak** mengakhiri episode yang prosesnya sempat lahir — watcher yang memutuskan restart atau menutup episode. `restartTunda` menunda 2 dtk lalu mencoba lagi bila `starting` masih benar, bukan membuang restart.
- Jalur menyerah dipusatkan di `akhiriEpisodeGagal()` (satu pintu): watcher kernel tak cocok, port direbut, restart habis 5x, dan loop restart ≥3x/5mnt kini benar-benar mematikan health tick, mencabut niat `START_STICKY`, melepas wakelock, membersihkan penanda jalan, dan menurunkan foreground bila tak ada backup terjadwal. Dulu jalur-jalur itu hanya mengirim pesan lalu berhenti, meninggalkan service foreground tanpa pekerjaan dan `KEY_SERVER_DIMINTA=true` sehingga sistem menghidupkan ulang percobaan yang sudah dinyatakan gagal. Pembersihan dipanggil di luar kunci `RESTART_TIMES`/`KUNCI_RESTART` agar tak menahan UI (`restartSummary`) saat menulis prefs/stopForeground.
- Uji `startGagalPermanenHanyaSaatTakAdaProsesDanTakDitunda` diperluas: episode yang prosesnya sempat lahir tak boleh diakhiri thread start.

## [Belum rilis] — Audit 197: Pilihan "Latest (automatic)" web-vault benar-benar memasang versi terbaru
- `Updater.updateWebVaultVersi(ctx, null)` — dipanggil tombol "Latest (automatic)" di Settings dan `/webvault terbaru` di bot — meneruskan `null` ke `updateWebVaultInner`, yang memperlakukan `null` sebagai "versi tak diketahui". Bila web-vault sudah terpasang, tiga guard awal langsung `return` dengan pesan "cek versi terbaru gagal (koneksi/rate-limit)" **tanpa pernah bertanya ke GitHub**, sehingga pilihan "terbaru" tak pernah memperbarui apa pun dan menuduh jaringan rusak. Jalur binary tidak kena karena `tryUpdateVersi` sudah memanggil `latestVersion()` saat diminta `null`.
- `updateWebVaultInner` kini meresolusi `diminta == null` lewat `latestVersion(ctx)` lebih dulu (cache + throttle 15 menit membuat panggilan kedua dari `updateWebVault()` murah), lalu membandingkan penanda terpasang dengan versi hasil resolusi. Pesan "cek versi gagal" kini hanya muncul bila pengecekan memang dicoba dan gagal. Nama berkas `.tmp` juga memakai versi hasil resolusi agar unduhan "terbaru" tak bercampur dengan sisa `.tmp` versi lain.
- Pin versi tidak dibaca di dalam fungsi ini: pemanggil yang menginginkan versi terkunci tetap mengirim versinya eksplisit, jadi unduhan terpin tidak berubah menjadi "terbaru".

## [Belum rilis] — Audit 196: Cek "metode dihapus tapi masih dipanggil" benar-benar menyala
- `tools/verifikasi-patch.py` memakai `masih_didefinisikan()` dengan pola `\bnama\s*\(` ke seluruh `app/src/main/java`. Pola itu juga cocok pada baris **pemanggilan**, jadi satu-satunya keadaan yang ingin dilaporkan (metode sudah dihapus tetapi pemanggil produksi masih ada) selalu menghasilkan `True` → `continue` → tidak pernah dilaporkan. Gerbang CI/pre-push hijau palsu padahal pohon Java tak bisa dikompilasi; workflow binary tidak mengompilasi Java sehingga rilis non-APK bisa terbit dari pohon yang APK-nya gagal build.
- Kini hanya **deklarasi** yang dihitung: `nama_metode_dideklarasikan()` mewajibkan tipe kembalian sebelum nama (`[modifier] TIPE<spasi>NAMA(`), menolak baris berawalan kata kunci (`return`, `new`, `if`, ...), dan membuang komentar lebih dulu. Pemanggilan seperti `Util.foo(1)` atau `foo(1)` tak lagi dianggap definisi, sedangkan overload yang benar-benar masih ada di berkas lain tetap terdeteksi (dipakai juga oleh `cek_hapusan_belum_tercatat` agar metode yang pindah berkas tidak salah dilaporkan).
- Pemindaian deklarasi di-cache per berkas (`nama_terdeklarasi()`): sebelumnya tiap nama yang dihapus memindai ulang seluruh berkas (`O(nama × berkas)`), sekarang tiap berkas dipindai sekali per jalan.
- Reproduksi sebelum perbaikan: `masih_didefinisikan('foo') = True` → `cek_metode_hilang` menghasilkan `gagal=[]` walau pemanggil produksi masih ada. Sesudah perbaikan: `gagal` memuat lokasi pemanggil; metode yang dipindah ke berkas lain tetap lolos. `--semua` dan `scripts/cek-cepat.sh` tetap hijau.

## [Belum rilis] — Audit 195: Proses `--version` benar-benar mati lewat satu pintu
- `ServerService.detectBinaryVersion` mematikan proses `--version` dengan blok `destroy()` + `waitForOrKill(2000)` buatan sendiri, sedangkan `finally` hanya memanggil `destroy()`. Bila binary mengabaikan SIGTERM, proses tetap hidup sambil memegang berkas binary-nya (penulisan/penggantian binary berikutnya bisa gagal `ETXTBSY`) dan memperpanjang jejak proses yatim pada STB ber-RAM kecil.
- Kedua jalur kini memakai `matikanProsesTegas()` (destroy → tunggu → `destroyForcibly` + tunggu 3 dtk), satu pintu yang sudah dipakai jalur stop/restart/bersih.

## [Belum rilis] — Audit 194: Parameter `https`/`scheme` yang selalu sama dibuang dari jalur start & health
- Aplikasi ini HTTPS-only (`KEY_HTTPS` selalu `true`, sertifikat wajib dibuat), tetapi `pingRinci`/jalur Start masih membawa variabel `boolean https = true` + `String scheme = "https"` dan meneruskannya ke `cobaKode(...)`, `panduanLoginBitwarden(...)`, serta `DOMAIN`. Nilai yang tak pernah bisa berubah menyamarkan asumsi "selalu TLS": pembaca kode bisa mengira ada jalur HTTP yang tetap bekerja padahal `cobaKode` tanpa TLS khusus akan gagal ke server TLS.
- Parameter itu dihapus: `cobaKode()` kini membuka `HttpsURLConnection` langsung, `panduanLoginBitwarden()` tak lagi menerima skema (isinya memang selalu `https://`), dan `DOMAIN` ditulis `https://…`. Uji `panduanLoginMemuatUrlDanAkun` disesuaikan (dulu menguji bahwa "http" menghasilkan URL https — bukti parameternya mati) dan kini juga menguji port/IP kosong.
- Penanda `ServerService.runningHttps` dihapus karena selalu bernilai sama dengan "proses hidup"; `TgBackup.resetSertifikat` cukup memeriksa `isProcessAlive()`. Komentar `resetPenandaJalan` disesuaikan (7 penanda).

## [Belum rilis] — Audit 193: Satu pemindai `tag_name` untuk rilis tunggal & daftar rilis
- `Updater.extractTag` (versi rilis terbaru) dan `Updater.parseDaftarTag` (daftar versi pilihan) masing-masing menyimpan salinan pemindai JSON struktural ~70 baris. Dua salinan itu wajib diubah bersamaan; perbaikan di satu sisi (mis. body yang memuat `"tag_name"` di dalam teks) tak ikut ke sisi lain sehingga daftar versi dan versi terbaru bisa dibaca berbeda dari respons GitHub yang sama.
- Keduanya kini memakai satu pemindai `pindaiTag()`; `extractTag` mengambil hasil pertama. Efek samping yang disengaja: bila `tag_name` berisi nilai non-string, pemindai melewatinya dan memakai tag sah berikutnya (dulu `extractTag` langsung menyerah dengan `null`) — perilaku ini kini sama dengan `parseDaftarTag` dan diuji di `pindaiTag_satuPintuUntukRilisTunggalDanDaftar`.

## [Belum rilis] — Audit 192: AGENTS.md tak lagi merujuk workflow yang tak ada
- Bagian alur rilis menyebut **`verifikasi-patch.yml`** sebagai workflow gerbang tersendiri, padahal berkas itu tidak pernah ada: verifikasi adalah job `verifikasi` di dalam `build-apk.yml` dan `build-binary.yml`. Panduan yang salah membuat agen/pengguna mencari berkas hantu dan menyimpulkan CI tak berjalan.
- Bagian itu diganti "Gerbang bersama: job `verifikasi`" (pemicu di kedua workflow, isi dua langkah, annotations), dan klaim "GAGAL = push ditolak" dikoreksi: push tetap masuk `main`, hanya job build di run tersebut yang tidak dijalankan sehingga perlu commit susulan/revert.

## [Belum rilis] — Audit 191: Shim getrandom membaca /dev/urandom di dalam kunci + uji tak flaky
- `isi_urandom()` membaca `fd_urandom` di luar `kunci_urandom`, sementara jalur `EBADF` menutup dan membuka ulang fd itu di dalam kunci. Thread lain bisa memakai fd yang sudah ditutup, atau — bila nomornya sudah dipakai ulang proses lain — menyalin byte dari berkas/socket yang salah sebagai "acak" untuk kunci TLS/token. Read kini berlangsung di dalam kunci yang sama (`/dev/urandom` tak pernah memblokir sehingga serialisasi murah).
- `shim/test_interpose.c` membandingkan checksum 32 byte (ruang nilai ~8 ribu) untuk memastikan dua jalur berbeda, sehingga tabrakan sesekali menggagalkan CI tanpa bug nyata. Perbandingan kini `memcmp` isi buffer penuh; ditambah deklarasi eksplisit `getrandom()` agar uji host tetap bisa dikompilasi di header bionic lama.

## [Belum rilis] — Audit 190: Cek cepat memindai isi berkas untuk pola rahasia
- `scripts/cek-cepat.sh` hanya memeriksa NAMA berkas (keystore/.env/zip/apk), sehingga token cloud atau private key yang ditempel ke kode/dokumen tetap lolos gerbang pra-push.
- Ditambah pemindaian isi index (`git grep --cached -I`) untuk pola berisiko tinggi: header private key PEM, token GitHub (`ghp_`/`gho_`/`ghu_`/`ghs_`/`ghr_`, `github_pat_`), access key AWS, token Slack, dan token bot Telegram. Pola dibuat spesifik (header PEM harus di akhir baris) agar kode yang menulis header, seperti `TlsCert`, tidak memicu positif palsu.

## [Belum rilis] — Audit 189: Verifikator CI tak lagi fail-open pada rentang kosong/tak valid
- `cek_pola_berisiko()` menjalankan `git diff --check` tanpa rentang; di checkout CI yang bersih perintah itu tak memeriksa apa pun, sehingga spasi berlebih/penanda konflik pada commit yang di-push lolos. Kini `--check` memakai rentang yang sama dengan diff.
- Saat `--rentang` kosong/tak valid (workflow_dispatch, schedule, force-push, SHA asing) verifikator dulu melewati seluruh cek berbasis diff lalu tetap mencetak LOLOS. Kini ia jatuh ke `HEAD~1...HEAD` (atau cek keadaan penuh bila repo satu commit), dan `berkas_dihapus()` ikut memakai rentang efektif itu.

## [Belum rilis] — Audit 188: Padding gaya tombol/input memakai start/end (RTL)
- `styles.xml` memakai `android:paddingLeft`/`paddingRight` untuk `TasirinBtn`, `TasirinBtnSmall`, dan `TasirinInput`, padahal manifest menyalakan `android:supportsRtl="true"`. Di locale RTL padding fisik tak ikut dicerminkan sehingga teks menempel ke tepi yang salah.
- Ketiganya diganti `paddingStart`/`paddingEnd` (API 17+, minSdk 21) sehingga padding mengikuti arah baca.

## [Belum rilis] — Audit 187: Tinggi daftar baris dialog Tentang benar-benar dibatasi
- `dialog_about.xml` memakai `android:maxHeight` pada `ScrollView` — atribut itu bukan milik `ScrollView` sehingga diabaikan, dan `layout_height="0dp"` + `layout_weight="1"` di dalam root `wrap_content` membuat daftar baris runtuh ke 0 atau mendorong tombol Tutup keluar layar pada TV 480p.
- ScrollView kini `wrap_content` tanpa `maxHeight`; `AboutDialog` mengukur tinggi isi lalu membatasinya ke 40% tinggi layar hanya bila isinya lebih panjang, sehingga baris tetap terbaca, bisa digulir, dan tombol Tutup selalu terjangkau.

## [Belum rilis] — Audit 186: Varian layout landscape layar awal yang duplikat dihapus
- `res/layout-land/activity_main.xml` adalah salinan penuh `res/layout/activity_main.xml` dengan satu-satunya beda `paddingTop` 8dp vs 12dp. Dua berkas besar dengan ID yang wajib sama persis berarti setiap perubahan layar awal harus disalin manual; lupa satu sisi = crash `findViewById`/ID hilang hanya di satu orientasi.
- Varian landscape dihapus (portrait dipakai di kedua orientasi, selisih 4dp tak sepadan dengan risiko drift) dan komentar layout + `AGENTS.md` disesuaikan.

## [Belum rilis] — Audit 185: Satu dialog Tentang untuk kedua layar
- `MainActivity` dan `SettingsActivity` punya dua dialog "Tentang" berbeda: yang di Settings berupa HTML ad-hoc dengan isi lain (panduan pakai, tanpa baris ikon/footer) sehingga info versi, tombol GitHub, dan tombol Cek Update tak pernah sinkron dan perbaikan dialog utama tak sampai ke sana.
- Dialog dipindah ke satu implementasi `AboutDialog.tampil()` yang dipakai kedua layar. Saat dibuka dari Settings, tombol Cek Update menutup dialog dan memfokuskan tombol Update di kartu (`fokuskanTombolUpdate()`) alih-alih membuka instance Settings kedua. Import yang tak terpakai di kedua activity ikut dibuang.

## [Belum rilis] — Audit 184: State diagnostik PIN yang tak pernah dibaca dihapus
- `MainActivity.pauseStamp`/`SettingsActivity.pauseStamp` ditulis di `onCreate`, `onPause`, dan `catatPinDibuka` tetapi tak pernah dibaca satu kali pun; `kapanDibuka()` juga tak pernah dipanggil, dan `PinGate.kapanBukaBersama()` hanya hidup untuk `kapanDibuka()`. Jangkar grace yang sebenarnya adalah `PinGate.dalamGraceBersama()`.
- Ketiganya dihapus (termasuk komentar onPause yang menggantung) dan namanya dicatat di `tools/bekas-hapusan.txt` agar tak diciptakan ulang.

## [Belum rilis] — Audit 183: Helper produksi yang hanya dipakai unit test dihapus
- Lima fungsi tidak pernah dipanggil kode produksi dan hanya hidup karena unit test: `MainActivity.muatKata`/`indeksTakPeka` (produksi memakai varian `…Rentang`), `TlsCert.daftarDns` (fitur domain lokal sudah dihapus; SAN DNS kini selalu kosong), `FileShareProvider.adaStubAndroid` (deteksi stub tak dipakai jalur mana pun), dan `SettingsActivity.perluWizard` (wizard sudah tidak ada). Kode mati semacam ini lolos dari lint resource dan menyamarkan perilaku yang sebenarnya.
- Semua dihapus bersama uji khususnya; uji `MainActivity` dialihkan ke varian rentang. Nama dicatat di `tools/bekas-hapusan.txt` agar tak diciptakan ulang.

## [Belum rilis] — Audit 182: Kode mati impor config + arsip restore Telegram tak tersapu
- `TgBackup.applyPrefsFromJson` diakhiri cabang "tebak tipe" (`String`/`Boolean`/`Integer`/`Long`/`Double`) yang tak pernah dieksekusi: setiap kunci `BOLEH_IMPOR` sudah tercakup `KUNCI_STRING`/`KUNCI_BOOLEAN`/`KUNCI_INT`/`KUNCI_LONG` dan semua cabang di atas memakai `continue`. Kode mati itu menyamarkan kunci baru yang lupa didaftarkan; cabang dihapus dan diganti komentar kewajiban mendaftarkan kunci baru di set tipe.
- Arsip `vwtg-restore-*.zip` di cache hanya dihapus pada jalur sukses/gagal, bukan saat dialog konfirmasi dibatalkan atau proses mati di tengah. `confirm()` kini punya varian dengan aksi batal (dipakai untuk menghapus arsip), dan `bersihkanExportPlainCache()` menyapu sisa `vwtg-restore-*.zip`/`-dec.zip` yang lebih tua dari 1 jam saat Settings dibuka.

## [Belum rilis] — Audit 181: Monitor kelas ServerService tak lagi dipakai untuk I/O SSL
- Cache SSL (`loopbackSslFactory` + `capCaAktif`) memakai `synchronized (ServerService.class)` — monitor yang sama dengan perpanjangan wakelock dan lookup refleksi pid. Di dalam kunci itu `ca.pem` dibaca dan di-hash penuh lalu `SSLContext` diinisialisasi; health tick yang sedang menyegarkan cache bisa menahan `jagaWakeLock()`/`acquireWakeLock()` beberapa saat, tepat pada jalur yang menjaga server tetap hidup saat Doze.
- Tiga tanggung jawab kini punya kunci sendiri (`KUNCI_SSL`, `KUNCI_WAKELOCK`, `KUNCI_PID`), dan reset cache SSL di `prepareTls` dilakukan di dalam `KUNCI_SSL` agar pembaca tak menyimpan factory CA lama sesudah reset.

## [Belum rilis] — Audit 180: Rantai fokus D-pad tak menunjuk tombol baterai yang tersembunyi
- `main_card_maintenance.xml` mengarahkan `nextFocusDown` (autoStart) dan `nextFocusUp` (autoUpdate) ke `batteryBtn`, padahal `batteryRow` berisi tombol itu default `gone` dan hanya tampil bila optimasi baterai belum diizinkan. Selama tersembunyi, navigasi remote TV berhenti/melompat di rantai fokus.
- Rantai fokus kini disambung ulang setiap status baris berubah: baris tampil → ke `batteryBtn`; tersembunyi → autoStart langsung ke autoUpdate dan sebaliknya.

## [Belum rilis] — Audit 179: Penanda "sudah dikabari" ditulis setelah kabar terkirim
- `AutoUpdate` menulis `tg_notified_version` sebelum menampilkan notifikasi sistem dan mengirim pesan Telegram. Bila notifikasi melempar atau proses mati di antara penulisan dan pengiriman, versi itu ditandai sudah dikabari padahal pengguna tak pernah menerima kabar — update berikutnya tak pernah diberitahukan untuk versi tersebut.
- Penanda kini ditulis paling akhir, sehingga kegagalan membuat versi dikabari lagi pada cek berikutnya (paling buruk kabar ganda, bukan hilang).

## [Belum rilis] — Audit 178: Rahasia alarm yang gagal disimpan tak membuat backup hilang
- `TgBackup.rahasiaAlarm` membuat rahasia anti-spoof lalu mengabaikan hasil `commit()`. Bila penyimpanan gagal (storage penuh), PendingIntent tunda-boot tetap dijadwalkan dengan rahasia yang tak pernah awet; saat alarm menyala, prefs kosong → rahasia baru dibuat → verifikasi gagal → backup susulan boot dilewati diam-diam.
- Rahasia yang dipakai kini dicatat di sesi proses lebih dulu (sumber kebenaran yang sama untuk penjadwalan dan verifikasi), kegagalan simpan dilaporkan ke log, dan pemilihan nilai dipisah ke `pilihRahasiaAlarm()` (murni) dengan uji `TgBackupTest.rahasiaAlarm_pakaiSesiProsesBilaTersimpanGagal`.

## [Belum rilis] — Audit 177: Folder data cadangan tak lagi terkunci di cache
- `dataDirBawaanSegar()` didokumentasikan "dihitung ulang tiap dipanggil (anti basi)", tetapi `defaultDataDir()` meng-cache hasil apa pun — termasuk nilai cadangan `/sdcard/vaultwarden` saat storage belum ter-mount. Proses yang memanggil lebih awal (mis. receiver boot) memakai path cadangan itu selamanya, persis masalah yang ingin dihindari dengan menambahkan fungsi "segar".
- Cache kini hanya menyimpan path yang benar-benar berhasil di-resolve; nilai cadangan dikembalikan tanpa di-cache sehingga pemanggilan sesudah storage ter-mount mendapat path sebenarnya. Uji JVM baru `ServerServiceTest.dataDirBawaanSegarTakKosongDanStabil`.

## [Belum rilis] — Audit 176: Dialog/pembaruan UI Settings tak jalan di Activity mati
- Worker `SettingsActivity` (unduh backup Telegram, restore, impor/ekspor config, pasang versi) memposting dialog dan pembaruan view tanpa cek siklus hidup. `onDestroy` memang membuang antrean, tetapi callback yang masuk sesudahnya tetap dieksekusi: `confirm()`/`startActivityForResult` di Activity selesai memicu `BadTokenException` (crash) atau jendela hantu, dan arsip unduhan bisa tertinggal di cache.
- Ditambah satu pintu `uiPost()` (gerbang `isFinishing()/isDestroyed()`) untuk semua posting yang menyentuh view/dialog, plus gerbang yang sama di awal `refreshFromService()`. Efek data (jadwal backup/bot, flag wizard) pada impor/restore dipindah ke luar gerbang agar tetap dijalankan walau Activity sudah mati; dialog restore dari Telegram menghapus arsip unduhan bila Activity keburu tutup.

## [Belum rilis] — Audit 175: Cek ruang backup mengukur volume yang benar
- `backupKeTelegram` hanya memeriksa sisa ruang folder data, padahal zip plaintext dibangun di cache internal app saat backup dienkripsi. Storage internal yang penuh tak terdeteksi lebih awal: backup baru gagal di tengah pembuatan zip dengan pesan membingungkan, walau volume data masih lega.
- Password dibaca lebih awal (tetap sekali) untuk menentukan apakah cache ikut dipakai, lalu cek ruang dijalankan untuk setiap volume yang benar-benar ditulis (cache bila terenkripsi + folder data), dengan path penyebab di pesan galat. Uji JVM baru `TgBackupTest.folderCekRuangBackupIkutCacheSaatTerenkripsi`.

## [Belum rilis] — Audit 174: Galat upload Telegram diringkas sebelum masuk chat
- `TgBackup.uploadTelegram` melempar `IOException("Telegram HTTP <kode>: <body>")` dengan potongan body mentah (200 karakter) dari respons Telegram. Pesan itu mengalir lewat `gabungGagalChat` → `pesanGalatBackup` → dikirim ke chat dan log, sehingga body panjang/HTML tampil mentah di percakapan.
- Kini memakai satu pintu `TgBot.ringkasGalatTelegram` (hanya `error_code` + `description`, maks 160 karakter) yang sudah dipakai jalur polling.

## [Belum rilis] — Audit 173: Gerbang TTL cache anchor TLS hidup saat override tak ada
- `HttpsCompat.capOverride` memakai `capStat != Long.MIN_VALUE` sebagai syarat gerbang TTL, padahal `capStat` hanya diisi bila berkas anchor override ada. Di kondisi normal (override tak ada, anchor dari aset) gerbang itu tak pernah lolos sehingga tiap koneksi TLS (poll bot 20 dtk, health 30 dtk, unduhan) tetap membayar kunci + `getFilesDir` + `isFile` di STB lama.
- Syarat gerbang kini `capWaktu != 0` (diisi semua jalur, termasuk jalur "override tak ada"), jadi stempel 60 dtk benar-benar bekerja.

## [Belum rilis] — Audit 172: URL lokal tak mengenumerasi interface di UI thread
- `ServerService.localIp()` mengembalikan nilai cache hanya bila masih segar (<60 dtk); tepat saat TTL habis, pemanggil UI (`MainActivity` kartu info + tombol salin, `SettingsActivity`) yang membayar enumerasi `NetworkInterface` + `isUp()` per kartu, sehingga layar terasa patah di STB lambat.
- Cache basi kini dipakai lebih dulu (IP LAN praktis statis, hanya untuk tampilan URL) dan penyegaran dijalankan di worker (`Util.jalankanBg`). Jalur pertama (cache kosong) tetap sinkron agar URL tak menampilkan alamat palsu.

## [Belum rilis] — Audit 171: Salinan buffer log tak lagi diambil di UI thread
- `LogActivity` menyalin isi `ServerService.logBuffer` di UI thread (ekor 100 KB tiap tick, dan `toString()` penuh 300 KB untuk salin/bagi/simpan). Lock yang sama dipegang `pumpOutput`/`catatLog` server, sehingga tiap tick menahan penulisan log server dan membuat layar terasa berat di STB 1 GB.
- Keempat jalur (`refreshLog`, `copyLog`, `shareLog`, `exportLogTxt`) kini menyalin buffer di worker; UI thread hanya menerima hasil yang sudah diolah.

## [Belum rilis] — Audit 170: Sertifikat TLS dibuat ulang saat CA berganti & tahan mati listrik
- Setelah CA dibuat ulang, `TlsCert.ensure` mengandalkan `delete()` leaf lama yang hasilnya diabaikan. Bila hapus gagal (berkas terkunci), leaf bertanda tangan CA lama tetap lolos cek "umur cukup" dan dipakai lagi, sehingga semua klien yang memasang CA baru ditolak TLS. Regenerasi kini ditentukan flag `caBaru`, bukan keberhasilan hapus.
- `writePem`/`writeVersion` belum `fsync`: rename atomik hanya mengawetkan nama, isi bisa nol/terpotong bila STB mati tepat sesudah tukar — CA terbaca rusak lalu dibuat ulang (semua HP wajib install CA baru). Keduanya kini fsync sebelum tutup (best-effort, tak menggagalkan start).

## [Belum rilis] — Audit 169: Pemasangan hasil unduhan diserialkan per tujuan
- Kunci unduhan hanya per berkas `.tmp`, sedangkan `gantiAtomik(tmp, out)` menulis ke tujuan bersama. Dua unduhan versi berbeda (mis. auto-update + `/update` dari bot) memakai tmp berbeda tetapi tujuan sama, sehingga keduanya bisa masuk `gantiAtomik` bersamaan: `.bak` saling dihapus/ditimpa dan salah satunya gagal dengan "Gagal mencadangkan binary lama" padahal unduhannya sehat.
- Jalur binary dan shim kini memakai `pasangHasilUnduh()` yang memegang kunci tujuan lalu kunci tmp (urutan tetap, tanpa siklus dengan jalur transfer) sebelum verifikasi SHA-256 dan rename. Uji JVM baru `UpdaterTest.pasangHasilUnduh_verifikasiShaLaluGantiAtomik`.

## [Belum rilis] — Audit 168: Episode Start basi tak menghapus niat & notifikasi perintah baru
- `startServerAsync` menyimpan `idAwal` tetapi blok pembersihan akhirnya selalu menjalankan `simpanNiatJalan(false)` + `stopForeground(true)` walau sudah ada perintah Start/Stop yang lebih baru. Akibatnya Start yang baru ditekan (sementara episode lama masih mengunduh lalu gagal) kehilangan niat tersimpan (server tak pulih setelah app dibunuh LMK) dan notifikasinya dicabut.
- `startGagalPermanen` kini menerima `intentTerbaru` (`idAwal == startIdTerakhir`); episode basi hanya melepas guard `starting` dan membiarkan perintah terbaru mengurus health/notifikasi/niat. Uji `ServerServiceTest.startGagalPermanenHanyaSaatTakAdaProsesDanTakDitunda` diperluas.

## [Belum rilis] — Audit 167: Proses yang lahir tak ditinggal yatim saat bookkeeping gagal
- Bila sesuatu melempar setelah `pb.start()` (mis. kirim notifikasi Telegram), blok `catch` hanya mengosongkan `process` tanpa mematikan proses: server tetap hidup memegang port + database tanpa reader/watcher, dan Start berikutnya dilaporkan "port sedang dipakai proses lain".
- Blok `catch` kini mematikan proses yang sudah lahir (process di-null-kan lebih dulu agar watcher tak mengira crash lalu auto-restart). Urutan destroy → tunggu → paksa mati yang sebelumnya disalin di tiga jalur (restart bot, `stopServer`, health ganti) disatukan ke `matikanProsesTegas()`; uji JVM baru `ServerServiceTest.matikanProsesTegas_*`.

## [Belum rilis] — Audit 166: Kunci data tak dipegang selama unduh binary
- `startServerAsync` memegang `KUNCI_DATA` sepanjang `startServer()`, termasuk unduh binary (~20 MB) dan uji `--version` yang bisa berjalan menit-menitan di koneksi STB lambat. Restore/reset (UI maupun bot) yang datang di tengah unduhan menunggu tanpa penjelasan, dan permintaan Start berikutnya ditolak "Start masih berjalan".
- Kunci kini dilepas sebelum `ensureBinary()`/`ensureShim()` (fase yang tidak menulis folder data) dan diambil kembali lewat `tungguKunciDataStart()` sebelum TLS/web-vault/exec: operasi data yang masih berjalan ditunggu maks 5 menit sambil menghormati Stop, lalu Start dibatalkan dengan pesan jelas. Pelepasan di `finally` memakai `lepasKunciData()` (dulu `unlock()` langsung bisa melempar `IllegalMonitorStateException` bila kunci sudah dilepas di tengah, sehingga `starting.set(false)` terlewati dan Start berikutnya terkunci selamanya).

## [Belum rilis] — Audit 165: Token CI minimal & tak tertinggal di .git/config
- Kedua workflow memberi `contents: write` ke seluruh job, termasuk langkah lint/unit-test yang menjalankan toolchain pihak ketiga dan job build binary. Kini level workflow hanya `contents: read`; `contents: write` dipersempit ke job yang benar-benar menerbitkan aset (`build-apk`, `publish-binary`).
- Semua `actions/checkout` memakai `persist-credentials: false` sehingga token tak tersimpan di `.git/config` untuk langkah berikutnya (tak ada langkah yang butuh push).

## [Belum rilis] — Audit 164: Cron binary tak lagi skip versi yang hanya punya APK
- `build-binary.yml` melewati jadwal bulanan hanya bila tag rilis sudah ada. Tag bisa dibuat lebih dulu oleh workflow APK (hanya berisi APK), sehingga binary + web-vault untuk versi itu tak pernah dibangun: app yang memakai fallback `/releases/latest/download/...` dan update web-vault menerima 404 permanen.
- Skip cron kini menuntut keenam aset non-APK benar-benar ada; output `ada` yang tak dipakai dihapus.
- Unggahan aset memakai `gh release upload --clobber` (satu perintah, tanpa hapus-per-ID + tidur 5 dtk) dengan coba ulang khusus gangguan sementara, dan verifikasi akhir membandingkan ukuran tiap payload serta isi `.sha256` di rilis dengan berkas lokal — unggahan terpotong tak bisa lolos lalu ditandai latest.

## [Belum rilis] — Audit 163: Rilis "latest" hanya setelah 6 aset non-APK lengkap
- Kedua workflow membuat rilis dengan penanda `latest` otomatis, sehingga rilis yang baru berisi APK (workflow APK jalan ~4 menit, workflow binary ~15 menit/bulanan) menjadi tujuan `/releases/latest/download/...` — jalur yang dipakai app untuk fallback unduhan binary. Selama jendela itu (bisa sampai sebulan bila workflow binary belum jalan) instalasi baru menerima 404.
- `build-apk.yml` dan `build-binary.yml` kini membuat rilis dengan `--latest=false`; `build-binary.yml` menandai `--latest` hanya setelah verifikasi 6 aset non-APK lolos. Rilis setengah jadi tak pernah jadi tujuan fallback.

## [Belum rilis] — Audit 162: APK rilis wajib benar-benar bertanda tangan
- `build-apk.yml` dulu hanya bergantung pada `KEYSTORE_BASE64`; bila salah satu dari tiga secret lain kosong, AGP menghasilkan `app-release-unsigned.apk` yang tetap cocok pola `*release*.apk` lalu terunggah `--clobber` menimpa APK signed.
- Langkah signing kini memeriksa keempat nilai, menolak berkas bernama `*unsigned*`, dan memverifikasi sertifikat dengan `keytool -printcert -jarfile` sebelum unggah. Password tak lagi dikirim sebagai `-P` (Gradle membaca env; `argv` tak menyimpan rahasia), dan `KEYSTORE_BASE64` dipindah ke level langkah agar langkah lint/unit-test tak melihatnya.

## [Belum rilis] — Audit 161: Fallback web-vault hormati kuncian versi & tak unduh ulang
- Jalur fallback web-vault tak punya penjaga kuncian: memilih versi X dalam 6 jam setelah fallback membuat app menjawab "X sudah dipasang" padahal payload-nya rilis lain. Kini `adaKuncianWebVault()` wajib kosong dulu, jadi pin selalu dipenuhi atau gagal lantang.
- Timer 6 jam diganti PROBE `v<versi>/web-vault.zip.sha256` (ratusan byte): 404 = belum terbit (lewati, tak unduh 35 MB), 200 = aset ada (penanda dibuang, versi target diunduh). `fallbackBaruSaja` beserta dua ujinya dihapus (`tools/bekas-hapusan.txt`).

## [Belum rilis] — Audit 160: Fallback binary tak lagi mengunduh ulang payload yang sama
- Jalur fallback `Updater.tryUpdate` dulu memakai timer 6 jam: setelah jeda habis, `releases/latest/download/...` diunduh ulang (~20 MB) padahal isinya byte yang sama dengan yang sudah terpasang, berulang tiap 6 jam selama aset versi target belum terbit.
- Kini keputusan berbasis isi: bila penanda fallback masih menunjuk versi target (`fallbackTercatatUntuk`, tanpa batas umur), app hanya PROBE `vaultwarden-armeabi-v7a.sha256` versi target (~ratusan byte). 404 = belum terbit, lewati; 200 = aset sudah ada, penanda dibuang lalu versi target diunduh. Uji JVM baru `UpdaterTest.fallbackTercatatUntuk_tanpaBatasUmur`.

## [Belum rilis] — Audit 159: Resume unduhan bertahan melewati redirect CDN GitHub
- `Updater.bukaIkutiRedirect` tak lagi menolkan offset `Range` saat redirect lintas host. Rilis GitHub selalu 302 dari `github.com` ke `release-assets.githubusercontent.com` untuk aset yang sama, jadi penolakan itu membuat header Range hilang, CDN membalas 200, dan parsial 20-35 MB dihapus lalu diunduh ulang tiap percobaan (di koneksi yang putus sebelum 20 MB, update tak pernah selesai).
- Keutuhan tetap dijaga `rangeCocok()` (awal `Content-Range` wajib sama dengan offset) + verifikasi SHA-256 pemanggil. Helper `hostBerubah` beserta ujinya dihapus karena tak lagi dipakai (`tools/bekas-hapusan.txt`).

## [Belum rilis] — Audit 158: Cek aset rilis binary tak lagi memakai repo hardcoded
- `.github/workflows/build-binary.yml` memakai `repos/$GITHUB_REPOSITORY/releases/...` alih-alih `tasirin1/tasirin-vaultwarden-host`. Dulu repo fork/rename melihat `EXISTING` kosong sehingga penanda "aset versi ini sudah ada" meleset dan build binary ~15 menit tetap jalan tiap jadwal bulanan.

## [Belum rilis] — Audit 157: Cek ELF binary satu pintu (Android 5+)
- `ServerService.isValidBinary` kini mendelegasikan ke `Updater.isElf` (ukuran wajar + magic ELF + `e_machine` ARM 32-bit, termasuk varian `EI_DATA` big-endian). Dulu blok ELF kembar di ServerService mengabaikan endianness.
- `HttpsCompat.capOverride` membuang cek `stat == capStat && cap == capNilai` yang tak pernah bisa benar: stat yang cocok sudah pulang lewat gerbang cepat `capNilai != 0`.

## [Belum rilis] — Audit 156: Wake lock tugas berat bot cukup untuk unduhan lambat (Android 5+)
- `TgBot.WAKELOCK_TUGAS_BERAT_MS` naik dari 3 ke 20 menit. `/update` (~20 MB) dan `/webvault` (~35 MB) di STB ber-kuota lambat butuh >9 menit; dengan batas lama CPU tidur di tengah transfer lalu unduhan gagal.
- Batas tetap ada sebagai pengaman thread macet (kunci dilepas di `finally`). Uji JVM baru `TgBotTest.wakelockTugasBeratCukupUntukUnduhanLambat`.

## [Belum rilis] — Audit 155: Mengganti PIN aktif wajib konfirmasi (Android 5+)
- `SettingsActivity.perluKonfirmasiGantiPin(pinAktif, adaHash)` + dialog "Ganti PIN?" sebelum hash ditimpa. Dulu mengetik di field PIN saat PIN aktif langsung mengganti hash, sehingga salah ketik/salah tempel satu kali mengunci app di luar.
- Batal mengosongkan field (isi yang tak tersimpan tak boleh tampak seperti PIN baru), dan centang aktivasi mereset kandidat agar edit berikutnya ditanya lagi. Uji JVM baru `SettingsActivityTest.gantiPinButuhKonfirmasiHanyaSaatPinAktifDanHashAda`; `adaHashPinTersimpan` gagal-tertutup saat prefs tak terbaca.
- Perbaikan build: dialog memakai `SettingsActivity.this` — di dalam lambda `TextWatcher`, `this` menunjuk anonim TextWatcher (bukan Activity) sehingga `AlertDialog.Builder(Context)` gagal dikompilasi.

## [Belum rilis] — Audit 154: `getType` provider tolak symlink induk seperti `query()` (Android 5+)
- `FileShareProvider.getType` menghitung kanonis ulang lalu menolak symlink induk (`adaSymlinkInduk`), sama seperti `query()`/`openFile()`. Dulu tukar symlink di jeda cek-vs-jawab bisa membocorkan tipe file di luar area share.
- Javadoc `adaSymlinkInduk` yang tertinggal di atas metode lain dipindah ke metode yang benar; klausa `vwtg-restore` yang sudah tercakup awalan `vwtg-` dibuang.

## [Belum rilis] — Audit 153: Mekanisme lipat kartu Settings yang selalu GONE dibuang
- Tiga blok ringkasan + tombol "Ubah" (`sec*`/`rawat*`/`tg*`) dihapus beserta id `secDetail`/`rawatDetail`/`tgDetail`, karena barisnya selalu `GONE` dan tak pernah dimunculkan kode — `nextFocusUp/Down` di kartu lain bahkan menunjuk view tak terlihat.
- `aturDetail`, `aturRingkasan`, `tampilkanDetailPenuh`, `teksKuncian`, dan `refreshRingkasan` dihapus (`tools/bekas-hapusan.txt`); sisanya menjadi `perbaruiStatusIsian` (hanya label "terisi/belum diisi"). `ic_edit.xml` serta string `ubah`/`ringkas_*` di kedua locale dihapus agar lint `UnusedResources` tetap bersih.

## [Belum rilis] — Audit 152: Parameter dan cabang mati di jalur login/start dibuang
- `ServerService.saranLoginUntukBaris(String)` tak lagi menerima `httpsAktif`: server ini selalu HTTPS sehingga flag itu tak pernah dibaca (9 pemanggilan uji disesuaikan).
- Cabang "port butuh root" di `startServer` dibuang karena tak terjangkau — port sudah dinormalkan ke 1024..65535 dan `isPortBusy()` true berarti bind gagal `EADDRINUSE`, bukan `EACCES`.

## [Belum rilis] — Audit 151: Baris dialog Tentang ikut bahasa Indonesia
- `values-in/arrays.xml` baru memuat `about_rows`, sehingga dialog Tentang di perangkat berbahasa Indonesia tak lagi separuh Inggris.

## [Belum rilis] — Audit 150: Deklarasi `MANAGE_EXTERNAL_STORAGE` dibuang
- Izin itu hanya bisa diberikan lewat layar pengaturan sistem dan tak pernah diminta app (targetSdk 28 masih model legacy), jadi deklarasinya hanya menambah permukaan izin. `StoragePerm.cukupAkses` tetap menerima bila perangkat sudah memberikannya.

## [Belum rilis] — Audit 149: Import mati dibuang, verifikator menutup celah nama-penuh
- `HttpsCompat`/`ServerService` membuang import `SecureRandom` dan `Date` yang tak dipakai.
- `tools/verifikasi-patch.py` memakai `(?<![\w.])nama\b`, sehingga pemakaian bernama-penuh (`java.security.SecureRandom`) tak lagi dianggap bukti import itu dipakai.

## [Belum rilis] — Audit 148: Cabang `/host` redundan di `Util` dibuang
- Normalisasi URL cukup satu jalur; helper `potongHost` dan cabang `/host` yang hasilnya identik dihapus (`tools/bekas-hapusan.txt`).

## [Belum rilis] — Audit 147: Tiga overload mati di `Updater` dibuang
- Overload yang tak pernah dipanggil dihapus dan namanya dicatat di `tools/bekas-hapusan.txt` agar tak diciptakan ulang.

## [Belum rilis] — Audit 146: Ekor log crash sempat hilang sebelum saran dibuat (Android 5+)
- `ServerService` menyimpan thread pembaca output (`pembacaOutput`) dan menunggunya maks 500 ms (`tungguPembacaOutput`) setelah proses mati, sebelum `tailLog` dibaca. Dulu baris panic Rust terakhir (sering baris pamungkas) belum masuk buffer sehingga saran crash kehilangan pesan aslinya.

## [Belum rilis] — Audit 145: Stempel backup harian tak dimajukan bila sebagian chat gagal (Android 5+)
- `KEY_TG_LAST` hanya ditulis bila semua chat tujuan menerima backup; `KEY_TG_LAST_NAME` tetap dicatat agar `/restore` dan info ukuran memakai berkas yang benar. Dulu satu chat gagal membuat cek "sudah backup hari ini" menganggap selesai dan chat itu tak pernah menerima backup hari tersebut.

## [Belum rilis] — Audit 144: Koneksi unduh Telegram ditutup di semua jalur galat (Android 5+)
- `TgBackup.bukaFileTelegram` memakai `try/finally` + penanda `serah`: koneksi yang tidak diserahkan ke pemanggil selalu `disconnect()`, termasuk saat `getResponseCode()` melempar `SocketTimeoutException` (dulu socket + buffer tertinggal tiap percobaan).
- `catch (Throwable)` di jalur cek DB diganti `catch (Exception)` agar `Error` (OOM dan sejenisnya) tak ditelan lalu backup tetap dijalankan.

## [Belum rilis] — Audit 143: Sidik isi web-vault mencegah unduh ulang 35 MB (Android 5+)
- `Updater.KEY_WV_SHA` menyimpan SHA-256 zip web-vault yang terpasang. Saat penanda versi kosong (cek versi terbaru gagal ketika memasang), kecocokan dibuktikan lewat berkas `.sha256` target yang kecil, bukan mengunduh ulang ~35 MB; cap versi ikut dipasang bila cocok.

## [Belum rilis] — Audit 142: File `.tmp` unduhan dipisah per versi (Android 5+)
- `Updater.namaTmpUnduh` menamai file sementara dengan versi target. Dulu nama `.tmp` bersama membuat sisa unduhan versi lama dilanjutkan (HTTP Range) oleh versi baru sehingga byte dua asset tercampur, checksum gagal, dan kuota terbuang.
- Sisa `.tmp` versi lain yang tak tersentuh >10 menit disapu (`sapuTmpUnduhBasi`) agar tiap ganti versi tak menyisakan 20–35 MB.

## [Belum rilis] — Audit 141: Toast izin storage tak lagi menumpuk dialog izin (Android 5+)
- `StoragePerm` hanya menampilkan toast "izin belum diberikan" bila dialog izin tidak baru dimunculkan. Dulu user melihat toast dan dialog sekaligus; penolakan tetap dilaporkan lewat `onRequestPermissionsResult`.

## [Belum rilis] — Audit 140: Penanda jalan dibersihkan juga saat proses server mati sendiri (Android 5+)
- `ServerService.watchProcess` memanggil `resetPenandaJalan()` saat proses keluar sendiri. Dulu hanya jalur Stop/gagal-start yang membersihkan, sehingga status "Stopped" setelah crash masih menyimpan port + sidik token server yang sudah mati.

## [Belum rilis] — Audit 139: Gerbang PIN layar log gagal-tertutup (Android 5+)
- `LogActivity.logBolehTampil(pinOn, graceMain, graceSettings)` dipisah sebagai predikat murni; galat membaca prefs kini berarti log TIDAK boleh tampil. Dulu blok `catch` kosong membuat log penuh terbuka saat prefs tak terbaca.

## [Belum rilis] — Audit 138: `QUICKBOOT_POWERON` non-protected tak lagi memicu auto-start (Android 5+)
- `BootReceiver` hanya menerima `BOOT_COMPLETED` dan `MY_PACKAGE_REPLACED` (keduanya protected). Action vendor `QUICKBOOT_POWERON` bukan protected, sehingga app lain bisa mengirim intent eksplisit ke receiver `exported` ini dan memicu auto-start/jadwal backup tanpa reboot.

## [Belum rilis] — Audit 137: Rotasi activity memuat layout landscape (Android 5+)
- `android:configChanges` dibuang dari keempat activity tanpa `onConfigurationChanged`. Dulu orientasi berubah tapi layout `layout-land` serta dimensi `values-sw480dp-land`/`values-sw600dp-land` tak pernah dimuat setelah rotasi (portrait diregangkan).
- State tetap aman: `SettingsActivity` menyiram tulis tertunda + menyimpan setelan valid di `onPause`, dan backup/restore dikunci statis di `TgBackup`.

## [Belum rilis] — Audit 136: Checksum 403 dicoba ulang, unduhan 416 lengkap tak diulang (Android 5+)
- `Updater.kodeSementara` menerima 403: GitHub membalas 403 (bukan 429) saat rate-limit, dan tanpa ulang checksum dianggap "tidak ada" padahal asetnya ada. Pesan galat checksum kini menyebut kemungkinan jaringan/rate-limit.
- `totalDariContentRange` membaca `Content-Range: bytes */N`; respons 416 dengan total == `resumeFrom` berarti file sementara sudah lengkap, jadi di-hash lokal alih-alih mengunduh penuh lagi.

## [Belum rilis] — Audit 135: Start ditahan selama backup/restore berjalan (Android 5+)
- `SettingsActivity.startDitahanSaatTugasData` menggerbangi `saveAndStart`. Tanpa gerbang ini Start menulis folder/port/token dari form yang mungkin masih berisi nilai pra-restore ke prefs yang sedang ditimpa hasil restore, lalu menyalakan server.

## [Belum rilis] — Audit 134: Backup DB manual membedakan database sibuk dari korup (Android 5+)
- `TgBackup.cekIntegritasDbUlang` mengulang cek integritas maks 2× selang 1 detik saat DB terkunci sesaat (server sedang menulis). Interrupt ditandai `SIBUK` agar tak dilaporkan sebagai DB rusak; pesan ke user menjadi "coba lagi", bukan "korup".

## [Belum rilis] — Audit 133: Binary manual tak disalin ulang tiap Start (Android 5+)
- `ServerService.sidikManualFile` (ukuran + mtime) + `KEY_BIN_MANUAL_FP` membuat cache internal dipakai ulang selama file di folder data belum ditukar. Dulu tiap Start menyalin 20 MB + hash + smoke test lagi hanya karena jalur cache dilewati revisi patch.
- Sidik diambil sebelum salin; bila file ditukar di tengah salin, sidik tersimpan tak cocok sehingga Start berikutnya memvalidasi ulang. Kunci bersifat perangkat (`TAK_DIEKSPOR_KEYS`).

## [Belum rilis] — Audit 132: Start yang gagal permanen menghentikan watchdog dan notifikasi basi (Android 5+)
- `ServerService.startIdTerakhir` memastikan `stopSelfResult` hanya dipakai bila tak ada intent lebih baru menunggu. Watchdog health dan notifikasi dihentikan saat start gagal permanen, sehingga tak ada lagi restart/notifikasi untuk server yang tak pernah menyala.

## [Belum rilis] — Audit 131: Restart dan backup terjadwal tetap ditangani saat antrean pool penuh (Android 5+)
- `Util.jalankanLama` mengembalikan status terima; `ServerService` dan jalur backup memeriksanya lalu menurunkan foreground / memberi pesan bila pool menolak tugas. Dulu tugas ditolak diam-diam sehingga restart dan backup terjadwal hilang tanpa jejak di status.

## [Belum rilis] — Audit 130: Unduh binary mulai dari URL fallback yang penandanya masih berlaku (Android 5+)
- `Updater.urlAwalUnduhBinary` memilih URL awal unduh; checksum wajib diambil dari URL yang sama dengan sumber byte. Dulu unduhan selalu dimulai dari `assetUrl` walau `urlAkhir` sudah fallback, sehingga penanda fallback tak pernah terpakai (404 berulang -> "belum tersedia") dan checksum bisa berasal dari URL berbeda. Uji JVM baru `UpdaterTest.urlAwalUnduhBinary_pakaiFallbackSaatPenandaBerlaku`.

## [Belum rilis] — Audit 129: Restore DB lewat staging + swap atomik (Android 5+)
- `TgBackup.pasangDbRestoreAtomik` menerima DB hasil ekstrak di `db.sqlite3.restore-tmp` (+ `-wal`/`-shm` mengikuti penamaan SQLite), menyatukan WAL staging lewat checkpoint, memvalidasi header + `quick_check`, lalu `rename(2)` ke `db.sqlite3` — satu langkah yang mengganti file lama. `SettingsActivity.restoreDatabaseIsi` dan `TgBackup.restoreFromZipIsi` menulis entri DB ke staging, bukan ke DB hidup. Dulu `db.sqlite3` ditimpa langsung saat ekstraksi, sehingga app dibunuh/kehabisan storage di tengah restore meninggalkan DB parsial; rollback dari `backups/*-pre.sqlite3` hanya jalan bila proses masih hidup.
- `TgBackup.pulihkanDbLama`/`pasangDbDariSalinan` memakai staging + rename juga, jadi rollback yang dibunuh di tengah salin tak meninggalkan DB parsial. Sisa staging dibersihkan sebelum & sesudah restore; entri `db.sqlite3/` sebagai direktori ditolak. Uji JVM baru: `namaStagingRestoreIkutPenamaanWalSqlite`, `hapusStagingRestoreBersihkanSemuaSisa`, `pasangDbRestoreAtomikTolakSampahTanpaSentuhDbLama`, `pasangDbDariSalinanGantiDbDanBuangWalShm`, `pulihkanDbLamaPakaiSalinanAtauBuangDb`, `hapusWalShmBuangWalDanShmLama`.

## [Belum rilis] — Audit 128: Restore DB membersihkan proses vaultwarden yatim (Android 5+)
- `ServerService.bereskanServerYatim` memindai `/proc` segar (cache 8 detik diabaikan), membunuh proses `.../bin/vaultwarden-*` se-UID yang bukan proses app (verifikasi ulang cmdline sebelum kill, anti PID-recycle), lalu memastikan tak ada yang bertahan. `SettingsActivity.restoreDatabaseIsi` dan `TgBackup.restoreFromZipIsi` memanggilnya sesudah `isProcessAlive`. Dulu anak server yang selamat saat app dibunuh sistem/update jadi yatim (tak terlihat `Process` statis) dan masih memegang `db.sqlite3`, sehingga restore bisa menimpa DB yang sedang dipakai.
- Bila proses yatim tak bisa dibunuh, restore dibatalkan dengan pesan jelas (DB lama tetap utuh). Uji JVM baru `ServerServiceTest.pidYatimServer_lewatiProsesSendiriDanSmokeTest`.

## [Belum rilis] — Audit 127: Kunci PIN wall-clock tak lagi bisa permanen (Android 5+)
- `PinCrypto.sisaKunciMs` selalu memangkas sisa wall-clock ke `KUNCI_MS`. Dulu selisih dikembalikan apa adanya saat jam perangkat wajar, sehingga lima PIN salah ketika jam diset ke masa depan (atau 1970) lalu jam dibetulkan membuat PIN terkunci bertahun-tahun — pemulihan hanya dengan menghapus data app. Pangkas ini juga menutup selisih raksasa saat jam sane dimundurkan; perpanjangan tiap percobaan gagal tetap lewat `kunciBerikutnyaMs`.
- Uji JVM baru `PinCryptoTest.stempelMasaDepanDipangkasSatuKunci`; `kunciJam1970TetapMengunci`, `kunciWallBasiDianggapKedaluwarsa`, dan `jamMundurTetapKunci` tetap hijau.

## [Belum rilis] — Audit 126: Sertifikat TLS belum-valid diregen, server bisa start lagi (Android 5+)
- `TlsCert.caOk`/`leafCukup` tak lagi menerima sisa `-2` (cert belum valid karena jam mundur > 1 hari). Dulu cert dipakai ulang sementara `ServerService.startServer` fail-closed pada `certDays == -2`, sehingga setelah jam pernah diset ke depan lalu dibetulkan server mustahil start sampai jam melewati `notBefore`. Regenerasi memakai jam sekarang (`notBefore = kini - 1 hari`) membuat cert valid lagi — sekali jalan, bukan tiap Start.
- `caSisaCukup` dipisah sebagai predikat murni; log menyebut sebab regen (jam mundur), bukan tampak seperti CA hilang. Uji JVM: `caSisaCukupTolakJamMundurAgarRegen` + `leafCukupTerimaSisaPanjangTolakJamMundur`.

## [Belum rilis] — Audit 125: Flag auto-start susulan tak lagi membuat server menyala sendiri (Android 5+)
- `MainActivity`: `KEY_START_TERTUNDA` dihapus begitu `ServerService.start()` mengembalikan `true` (intent diterima sistem), bukan menunggu `ServerService.running` yang baru bernilai `true` setelah start asinkron selesai. Dulu flag hampir tak pernah terhapus, sehingga setiap app dibuka setelah user menekan Stop server menyala lagi lewat jalur "Auto-start susulan".

## [Belum rilis] — Audit 124: Sidik menu bot tersimpan untuk respons JSON ber-spasi
- `TgBot.refreshMenuAsync` memakai `responsOk()` (toleran `"ok": true` ber-spasi) alih-alih `contains("\"ok\":true")`. Dulu respons sah dengan spasi dianggap gagal sehingga `KEY_TG_MENU_FP` tak pernah tersimpan dan `setMyCommands` dikirim ulang pada setiap Start/boot/perubahan setelan.

## [Belum rilis] — Audit 123: Backup otomatis dilewati cepat saat tugas data lain berjalan (Android 5+)
- `TgBackup.maybeAutoBackup` mengecek `tugasDataJalan()` sebelum masuk pool: bila backup/restore lain sedang berjalan, backup otomatis dicatat "dilewati" dan keluar, bukan menunggu DB sampai 30 detik lalu melaporkan "Backup gagal: tugas backup/restore lain sedang berjalan. <saran koneksi>" — pesan yang membuat user mengira backup harian gagal/putus jaringan, sekaligus menahan satu slot `BG_LAMA` (hanya 2 thread) di STB 1 GB.

## [Belum rilis] — Audit 122: Retensi backup hanya menyapu setelah zip terverifikasi (Android 5+)
- `TgBackup.createBackupZip` tak lagi memanggil `cleanupOldBackups`; pemanggil menyapu setelah zip lolos `verifikasiZip` + `verifikasiIsiDbZip` (jalur tanpa password) atau setelah `.enc` jadi (jalur terenkripsi). Dulu retensi jalan sebelum verifikasi, sehingga percobaan backup yang gagal (DB robek saat server menulis) tetap mengusir satu backup lama yang sah — riwayat menipis justru ketika backup sedang bermasalah.

## [Belum rilis] — Audit 121: `.enc` gagal verifikasi tak lagi menumpuk di folder backup (Android 5+)
- `TgBackup.backupNowIsi`: berkas `.enc` yang sudah ditulis tetapi gagal round-trip (`decryptFile`/`verifikasiZip`) kini dihapus di blok `finally` lewat penanda `sah`. Dulu berkas itu tertinggal di `<folder data>/backups`, ikut dihitung retensi `KEEP_BACKUPS`, dan bisa mengusir backup lama yang sah — bahkan muncul di daftar restore lalu baru gagal saat dipakai.

## [Belum rilis] — Audit 120: Start saat restore/reset berjalan tak lagi hilang diam-diam (Android 5+)
- `ServerService.startServerAsync`: bila `KUNCI_DATA` sedang dipegang restore/reset, Start tidak lagi dibuang. Permintaan diulang tiap 5 detik lewat `restartTunda` (maks 60 kali/5 menit) lalu dihentikan dengan pesan jelas "tekan Start setelah selesai". Stop tetap membatalkan percobaan ulang (`batalUtama(restartTunda)` + `autoRestart=false`), dan hitungan di-reset begitu Start berhasil memegang kunci.
- Dulu `/start` dari bot atau tombol Start saat restore hanya menulis "Start ditunda" tanpa ada yang mencoba lagi, sehingga server tetap mati sampai user menekan Start kedua (padahal `KEY_SERVER_DIMINTA` sudah tersimpan).

## [Belum rilis] — Audit 119: Jam STB yang melompat ke masa depan tak lagi membisukan bot selamanya (Android 5+)
- `TgBot.catatWall` hanya memajukan tanda air anti-rollback bila lompatannya wajar (`wallBolehMaju`): nilai era 1970 (RTC belum diset) ditolak dan lompatan > 30 hari dianggap RTC salah. Dulu satu lompatan jam ke 2030 langsung dicatat + disimpan ke `tg_wall_maks`, sehingga setelah NTP membetulkan jam setiap perintah/tombol dibuang `jamMundur` sampai tahun 2030 — remote Telegram mati total tanpa jalan pulih in-app.
- `muatWallMaks` membuang tanda air racun yang jauh di masa depan (pemulihan bagi perangkat yang sudah terlanjur menyimpannya), dan `pollOnce` me-rebase tanda air serupa yang terlanjur ada di memori sebelum cek mundur. Proteksi anti-rollback untuk selisih wajar (menit/jam) tetap berjalan; pesan basi tetap ditolak `Util.pesanSegar` dan tombol oleh `tombolKedaluwarsa`.
- Uji JVM baru `TgBotTest.wallBolehMaju_tolakJam1970DanLompatanRaksasa`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 118: Penolakan Telegram tak lagi membungkam polling tanpa jejak (Android 5+)
- `TgBot.httpPostForm` kini membaca body galat (bukan membuangnya) lalu mencatat ringkasannya sekali per jenis: 401 token dicabut, 409 bot dipakai instans lain, dan 429 kena limit dulu membuat bot berhenti menjawab tanpa satu pun petunjuk di log. Galat jaringan juga dicatat per kelas exception.
- `TgBot.ringkasGalatTelegram` (murni, tanpa `org.json`) mengambil `error_code` + `description` saja; body mentah tak pernah dicatat karena galat proxy/HTML bisa memuat URL `bot<token>` sehingga token bocor ke log dan Telegram `/log`. Deskripsi dipangkas 160 karakter.
- Uji JVM baru `TgBotTest.ringkasGalatTelegram_kodeDanDeskripsi` (401/409, body non-JSON, tanpa body, deskripsi panjang). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 117: Lockout PIN tetap berlaku saat jam perangkat masih 1970 (Android 5+)
- `PinCrypto.sisaKunciMs`: stempel kunci kecil (ditulis saat jam 1970, lazim di STB tanpa RTC/NTP) tak lagi dianggap sisa era `elapsedRealtime` lalu langsung diloloskan. Selisih `terkunciSampai - sekarang` tetap dipakai dan dibatasi `KUNCI_MS`, sehingga lima PIN salah di perangkat berjam 1970 benar-benar terkunci 5 menit (dulu brute-force tanpa jeda). Jam wajar yang dimundurkan ke 1970 tetap fail-closed (selisih raksasa → kunci penuh), dan stempel era-elapsed pada jam wajar tetap kedaluwarsa lewat cek `sekarang >= terkunciSampai`.
- Uji JVM baru `PinCryptoTest.kunciJam1970TetapMengunci` (kunci penuh, sisa menyusut, lalu nol tepat saat habis). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 116: Ringkasan pengaturan tak lagi disentuh dari worker thread (Android 5+)
- `SettingsActivity.refreshRingkasan` kini mem-posting dirinya ke UI thread bila dipanggil dari thread lain. Dulu dua blok `catch` di dalam `runBusy` (gagal pasang binary / web-vault versi pilihan) memanggilnya langsung dari worker, sehingga `isChecked()`/`setText()` dari thread non-UI melempar `CalledFromWrongThreadException` — app crash tepat saat user butuh pesan galat. Guard memakai `Looper.getMainLooper()` + null-check (aman bila Looper belum siap). Tanpa test baru (butuh Activity/Looper Android); verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 115: Zip backup berisi kunci privat tak lagi mampir ke storage publik (Android 5+)
- `TgBackup.createBackupZip` menerima folder zip sementara: saat backup akan dienkripsi, zip plaintext (DB + `tls/ca-key.pem` + `tls/key.pem`) dibangun di cache privat app, bukan di `<folder data>/backups` yang bisa dibaca app lain. Dulu zip berisi kunci privat sempat tertulis plaintext di /sdcard sebelum dienkripsi — dan bila enkripsi/verifikasi gagal, `zip.delete()` tak pernah jalan sehingga plaintext bertahan di sana.
- Hasil `.enc` tetap disimpan di `<folder data>/backups` (retensi 10) dengan nama unik terhadap isi folder; `cleanupOldBackups` dipanggil setelah `.enc` jadi karena zip sementara tak menjalankan retensi. `zip.delete()` + `tmpDec.delete()` kini di `finally` agar plaintext hilang juga saat gagal. Tanpa test baru (butuh Context + storage Android); verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 114: Restart karena IP LAN berubah benar-benar jalan (Android 5+)
- `ServerService.peringatkanIpBerubah`: restart memakai jalur kanonik `restart()` (`ACTION_RESTART`) yang menghentikan proses lama lebih dulu, bukan `scheduleRestart()` yang hanya menunda start saat proses sudah mati. Dulu `restartTunda` melewati proses yang masih hidup sehingga tidak ada restart sama sekali, padahal log + Telegram menjanjikannya dan status sempat berbunyi "Server crash - restart dalam 2 dtk". `runningLanHost` langsung diperbarui ke IP baru agar tick berikutnya tak memicu restart kedua selagi proses lama berhenti; bila permintaan restart ditolak sistem, log menyarankan Stop→Start.
- Log `ACTION_RESTART` jadi "Restart server diminta." (dulu "via Telegram") karena jalur ini juga dipakai peringatan IP. Tanpa test baru (butuh proses/service Android); verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 113: Stop tak lagi menggantung karena balapan watcher (Android 5+)
- `ServerService.watchProcess`: cabang "stop disengaja" kini menyelesaikan seluruh pembersihan (`process`, `running`, penanda port/folder, wakelock, flush log, status "Stopped"), bukan hanya men-null-kan `process`. Watcher dan thread `vw-stop` sama-sama menunggu proses mati; dulu bila watcher menang, guard `process == p` di `finally` stopper ikut gagal sehingga `running` tetap `true` dan status "Menghentikan..." menggantung (UI/guard restore salah menilai server masih hidup). Kedua jalur tetap memakai guard `process == p` agar Start baru tak ikut dibersihkan. Tanpa test baru (butuh proses/thread Android); verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Fitur: kontrol Telegram berbasis tombol berjenjang (Android 5+)
- `TgBot`: `/help` kini membuka menu utama berjenjang (Status, Kontrol, Backup, Update, Sertifikat, Info) dengan tombol navigasi `m:<panel>` dan tombol Kembali. Panel diedit di tempat lewat `editMessageText` agar riwayat chat tidak menumpuk; keyboard lama dengan callback `/perintah` tetap dikenali.
- Tombol perintah yang dijaga PIN (`/start /stop /restart /backup /restore /update /webvault /careset /status /log /crashlog /ca /cabackup`) kini meminta PIN lewat balasan chat selama 5 menit. Aksi pending disimpan per chat, fail-closed saat jam mundur, dan selalu dihapus sebelum eksekusi agar PIN salah tidak menambah lockout berulang. Grup tetap ditolak dan PIN yang lolos tetap dihapus dari riwayat.
- `perintahPerluPin`, `pendingMasihBerlaku`, `rakitKeyboard`, dan `panelMenuValid` diuji JVM; `TgBackup.editMessageKb` butuh `Context` Android sehingga tidak diuji JVM. `pesanPinWajibHapus` kini juga menghapus `/ca`/`/cabackup` ber-PIN (dulu PIN tertinggal di riwayat). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 112: judul dialog restore tak lagi campur bahasa (Android 5+)
- `SettingsActivity`: judul konfirmasi `"Restore dari Telegram"` diganti `"Restore from Telegram"` (isi pesannya sudah Inggris). Ini satu-satunya teks UI yang masih Indonesia; log diagnostik dan pesan bot Telegram tetap Indonesia sesuai aturan bahasa repo. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 111: deteksi grup bot per chat, bukan seluruh config (Android 5+)
- `TgBot`: `authDangerous` kini menerima ID chat (`idRuang`) dan menolak perintah ber-PIN hanya bila chat pengirim benar-benar grup (ID negatif). Sebelumnya pemeriksaan memakai `chatResmiAdalahGrup(ctx)` yang menilai seluruh daftar chat, sehingga config campuran (chat pribadi + grup) menolak perintah ber-PIN dari chat pribadi juga. Parameter `pengirimId` di `handleCommand` diganti `idRuang` agar sesuai isinya. Perintah berbahaya dari grup tetap ditolak lebih awal (tanpa verifikasi PIN). Tanpa test baru (butuh Context Android); helper murni `perintahBerbahaya` tetap teruji. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 110: restore cek ruang sebelum menimpa database (Android 5+)
- `TgBackup.restoreFromZipIsi`: sebelum server dihentikan, total isi zip (tak terkompresi) dihitung dan dibandingkan dengan ruang bebas folder data — termasuk salinan pengaman DB lama + 5 MB headroom. Restore ditolak lebih awal bila isi zip melebihi `BATAS_UNZIP_RESTORE` atau storage kurang, sehingga tak ada rollback di tengah ekstraksi dengan pesan membingungkan. Helper murni `ukuranIsiZip` + test `ukuranIsiZip_menjumlahkanEntri`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 109: bundel CA CI terpin & cache cargo tak lagi mati (CI)
- `.github/workflows/build-binary.yml`: `cacert.pem` dari curl.se kini diverifikasi SHA-256 sebelum dipakai `include_str!` (dulu isi yang berubah/diretas bisa ikut masuk binary). Bila curl.se merilis bundel baru, langkah ini gagal sengaja dengan petunjuk memperbarui hash.
- Cache cargo: `restore-keys` diganti prefiks lebar `vw-cargo-armv7-` (dulu `vw-cargo-armv7-<versi>-` tak pernah cocok dengan key `vw-cargo-armv7-<versi>` sehingga cache lama tak terpakai). Verifikasi via `verifikasi-patch` + `cek-cepat`, build penuh di CI.

## [Belum rilis] — Audit 108: gerbang patch tak lagi buta di CI & hasil build (CI)
- `scripts/cek-cepat.sh`: pemeriksaan berkas terlarang kini melihat yang staged **dan** semua berkas tracked, jadi gate tetap bekerja di runner (dulu `git diff --cached` kosong karena checkout bersih). Pola diperluas ke `.aab`/`.so`/`.a`/`.dex`, `assets/bin/`, `vw_version.txt`, dan `.env.*`.
- `.gitignore`: mengabaikan `app/src/main/assets/bin/` dan `app/src/main/assets/vw_version.txt` yang diisi CI.
- `tools/verifikasi-patch.py`: `jalan_repo` melewati `build`/`.gradle` (dulu `--semua` lokal memindai hasil build dan memunculkan temuan palsu), cek ABI hanya membaca nilai `abiFilters` (bukan semua string di build.gradle.kts), dan cek metode hilang/pemanggil mengabaikan komentar/Javadoc agar `{@link nama()}` tak dianggap kode hidup. Verifikasi via `verifikasi-patch --semua` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 107: build APK tak lagi rawan kehilangan aset rilis (CI)
- `.github/workflows/build-apk.yml`: job `perlu` yang selalu menjawab "yes" dihapus, `assembleDebug` yang hasilnya tak dipakai dihapus (lint + unit test + `assembleRelease` cukup), dan `cancel-in-progress` dimatikan agar push baru tak memotong run di tengah publish. Unggahan APK kini memakai `gh release upload --clobber` dengan coba ulang (tanpa hapus manual + jeda 5 dtk) dan diverifikasi lewat ukuran aset di rilis, bukan sekadar namanya. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 106: PIN bot tak lagi diterima dari grup (Android 5+)
- `TgBot.authDangerous`: perintah yang dijaga PIN (start/stop/restart/backup/restore/update/webvault/careset plus status/log/crashlog/ca/cabackup) kini ditolak bila chat resmi berupa grup atau username, dengan pesan agar dikirim dari chat pribadi. Sebelumnya PIN yang diketik di grup terbaca semua anggota (hapus pesan hanya best-effort) dan anggota mana pun bisa memicu aksi atau mengirim PIN salah sampai `PinGate` bersama mengunci pemilik app. Grup tetap menerima notifikasi/backup dan perintah tanpa PIN (`/help`, `/alive`, `/uptime`, `/versi`). README (EN/ID/RU) diperbarui. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 105: reset sertifikat tak lagi balapan dengan Start (Android 5+)
- `TgBackup.resetSertifikat` kini memegang `ServerService.KUNCI_DATA` (kunci yang sama dengan Start/restore) dan memeriksa ulang status server di dalam kunci; `TlsCert.resetTls` ikut mengunci `KUNCI_ENSURE` yang dipakai `TlsCert.ensure`. Sebelumnya reset dari tombol Settings atau perintah bot `/careset` bisa menghapus CA/leaf tepat saat Start sedang memasang pasangan baru, sehingga start gagal atau pesan "tekan Start" menyesatkan. Pesan Start yang ditunda disesuaikan menjadi "restore/reset sedang berjalan". Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 104: admin token lemah memblokir Start & setelan tersimpan tanpa Start (Android 5+)
- `SettingsActivity`: `inputStartValid` kini juga mensyaratkan admin token valid (≥8 karakter), dan `saveAndStart` memeriksanya lagi secara defensif agar jalur programatik tak menyimpan token lemah.
- Folder, port, dan admin token yang valid disimpan saat layar ditinggalkan (`onPause` → `simpanPengaturanValid`), bukan hanya ketika Start ditekan; nilai setengah jadi dilewati, dan penyimpanan ditunda saat backup/restore berjalan (`tugasBerjalan`/`TgBackup.tugasDataJalan`) agar tak menimpa prefs hasil restore. Ditambah `TgBackup.tugasDataJalan()` + test `tugasDataJalan_mengikutiKunciRestore`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 103: penolakan antrean tugas tak lagi menggantung UI (Android 5+)
- `SettingsActivity`: hasil `Util.jalankanBg` kini diperiksa di tiga jalur. Cek port sebelum Start memberi pesan bila antrean penuh (dulu tombol Start diam saja); refresh info berat melepas `heavyRunning` bila tugas ditolak (dulu flag tertahan `true` sehingga info DB/storage tak pernah disegarkan lagi); dan tombol "Try import" diaktifkan kembali + pesan bila dekrip ditolak (dulu tombol mati permanen). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 102: hash PIN yang masih debounce tak hilang saat Back (Android 5+)
- `SettingsActivity`: rundown debounce PIN kini menandai dirinya selesai (`pinTunda = null`) dan dibaca status centangnya di UI thread sebelum dikirim ke worker (dulu worker memanggil `pinEnabledCheck.isChecked()` dari thread lain). `onDestroy` menjalankan debounce yang masih tertunda sebelum antrean UI dibuang, sehingga PIN yang baru diketik lalu langsung Back tetap di-hash dan tersimpan. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 101: dialog tak dibuat saat activity sudah selesai (Android 5+)
- `SettingsActivity.tampilDialogVersi` dan `confirm` kini berhenti bila `isFinishing() || isDestroyed()`. `tampilDialogVersi` dipanggil lewat `ui.post` dari worker daftar versi, sehingga hasil yang datang setelah activity ditutup dulu memicu `WindowManager$BadTokenException`. Dialog password impor juga ikut memeriksa `isDestroyed()`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 100: tombol Stop tak lagi terkunci input tak valid (Android 5+)
- `SettingsActivity`: status enabled tombol bawah dihitung ulang tiap tick lewat `perbaruiEnabledStartStop()` — saat server berjalan (tombol = Stop) tombol selalu aktif, saat berhenti (tombol = Start) hanya aktif bila folder/port valid. Sebelumnya `setEnabled(!blokirStart)` mengunci Stop juga, sehingga server yang jalan dengan port/folder salah tidak bisa dihentikan dari layar Pengaturan. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 99: fallback binary tak lagi diunduh 20 MB tiap cek (Android 5+)
- `Updater`: fallback ke rilis terbaru (saat asset versi target belum terbit) kini dicatat di `bin_fallback_for`/`bin_fallback_at` dan berlaku 6 jam, sama seperti web-vault. `tryUpdateVersi` melewati percobaan unduh selama penanda berlaku, dan `downloadBinaryInner` langsung memakai URL rilis terbaru (bukan mencoba asset 404 dulu). Sebelumnya setiap cek update mengulang 404 lalu mengunduh ulang ~20 MB karena penanda hanya hidup per panggilan. Ditambah test `fallbackBinaryBerlaku_*` + `binaryFallbackUrl_*`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 98: stopAndWait menunggu Start yang belum melahirkan proses (Android 5+)
- `ServerService.stopAndWait` kini juga menunggu `starting` selesai, bukan hanya `process` mati. Sebelumnya Start yang masih mengunduh/menyiapkan binary membuat `process == null` sehingga fungsi langsung mengembalikan `true`; pemanggil restore lalu menimpa DB dan task Start bisa mengeksekusi binary di tengah restore. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 97: panic getrandom di baris kedua ikut terbaca (Android 5+)
- `ServerService.detectBinaryVersion`: `lastVersionOutput` kini menyimpan semua baris yang dibaca (maks 2 baris non-noise), bukan hanya baris versi. Sebelumnya panic kernel lama (`thread 'main' panicked ... getrandom ...`) yang dicetak di baris kedua terbuang, sehingga `isKernelRandomPanic` tak menyala dan server tetap dicoba start tanpa shim — gagal berulang dengan pesan tidak jelas. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 96: folder di bawah path symlink tak dikira symlink (Android 5+)
- `Updater.tautanSimbol`: kini hanya menilai komponen terakhir sebagai tautan dengan membandingkan kanonis induk + nama terhadap kanonis file. Sebelumnya `getCanonicalPath()` dibandingkan dengan path absolut sehingga folder biasa di bawah induk symlink (mis. `/sdcard` → `/storage/emulated/0`) dianggap tautan; `deleteRecursive` lalu berhenti di satu entri dan sisa folder web-vault/binary menumpuk. Ditambah test induk symlink di `tautanSimbol_bedakanLinkDanBiasa`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 95: tag upstream tak lagi diinterpolasi ke shell CI (Android 5+)
- `.github/workflows/build-apk.yml` + `build-binary.yml`: tag rilis Vaultwarden divalidasi berbentuk versi (`1.34.3`, boleh sufiks `-beta.1`/`+build`) di job `resolve`, lalu diteruskan lewat `env: VW` dan dipakai sebagai `"$VW"` di `run:`. Sebelumnya `${{ needs.resolve.outputs.vw }}` disubstitusi GitHub langsung ke skrip shell; tag berisi `$(...)`/backtick dari upstream yang disusupi bisa dieksekusi di runner ber-`contents: write`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 94: pool tugas benar-benar memakai worker paralel (Android 5+)
- `Util`: `BG_RINGAN` (3) dan `BG_LAMA` (2) kini dibuat lewat satu pintu `buatPool` dengan `core = max` + `allowCoreThreadTimeOut(60 dtk)`. Sebelumnya `core=0` membuat `ThreadPoolExecutor` hanya memakai **satu** worker sampai antrean penuh (32/16), sehingga verifikasi/hash PIN dan Start ikut mengantre di belakang unduhan binary/web-vault 20–35 MB — layar kunci bisa berhenti di “Memeriksa…” selama unduhan. Thread idle tetap dibuang agar RAM STB hemat. Ditambah unit test `jalankanLamaBenarBenarParalel`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 93: update tak memasang isi yang tak terverifikasi (Android 5+)
- `Updater`: sebelum `gantiAtomik`, binary dan shim di-hash ulang di dalam kunci unduh yang sama dengan transfer; bila file tmp sempat ditimpa update lain, pemasangan dibatalkan (dulu checksum diverifikasi pada hasil transfer lalu rename di luar kunci, sehingga isi lain bisa terpasang). Update web-vault kini diserialkan penuh (`KUNCI_UPDATE_WV`) karena zip tmp dipakai bersama antar-panggilan. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 92: Start tak menyalakan server di tengah restore (Android 5+)
- `ServerService` menambah kunci data `kunciData()/lepasKunciData()`. `startServerAsync` memakai `tryLock` dan menunda Start dengan pesan jelas bila restore sedang berjalan; restore UI (`SettingsActivity.restoreDatabase`) dan restore zip (`TgBackup.restoreFromZip`) memegang kunci sampai ekstraksi selesai. Sebelumnya bot `/restore` berjalan di pool berbeda dari Start UI sehingga server bisa menyala saat DB setengah ditimpa. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 91: backup selesai tak mematikan service saat Start berjalan (Android 5+)
- `ServerService` (ACTION_TG_BACKUP): `stopSelf()` di `finally` kini memakai `stopSelf(startId)` dan dilewati bila `starting` masih true. Sebelumnya backup yang selesai saat Start masih mengantre/mengunduh binary (`process` belum lahir) mematikan service di tengah start, lalu `onDestroy` membunuh proses yang baru dibuat. `stopSelf(startId)` juga membuat intent Start yang lebih baru otomatis membatalkan stop. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 90: service mati tak memicu server yatim (Android 5+)
- `ServerService.onDestroy`: proses kini ditandai `tandaiStopDisengaja` sebelum `destroy`, sehingga `watchProcess` tidak lagi menganggap kematian service sebagai crash lalu menjadwalkan restart. Sebelumnya binary bisa hidup kembali tanpa service/notifikasi (proses yatim) setelah service dihancurkan sistem; pemulihan tetap terjadi lewat jalur intent null `START_STICKY` bila user memang meminta server jalan. Penanda juga dibersihkan saat watcher melihat proses berhenti. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 89: restore tak membuang WAL sah (Android 5+)
- `TgBackup` restore: WAL dan SHM kini dihapus per berkas sesuai isi zip. Sebelumnya kondisi `!adaWal || !adaShm` menghapus keduanya, sehingga zip berisi `db.sqlite3-wal` tanpa `-shm` (umum setelah crash/force-stop) kehilangan transaksi yang sudah commit tapi belum checkpoint — `quick_check` tetap lolos sehingga kerusakan tak terdeteksi. Ditambah unit test `hapusWalShmTerpisahPertahankanWalSah`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 88: password backup dipakai satu snapshot (Android 5+)
- `TgBackup`: password Telegram dibaca sekali di awal backup dan dipakai untuk menentukan ikut/tidaknya kunci privat TLS, enkripsi zip, serta teks peringatan. Sebelumnya password dibaca ulang setelah zip dibangun, sehingga bila user mengosongkannya di tengah backup zip plaintext berisi kunci privat tetap terkirim tanpa peringatan; kini hasil backup selalu konsisten dengan password saat backup dimulai. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 87: mengetik PIN pendek tak mematikan proteksi (Android 5+)
- `SettingsActivity`: cabang `TextWatcher` PIN <4 karakter tidak lagi menghapus `pin_hash` dan mematikan `pin_on` tanpa konfirmasi. Sebelumnya mengetik karakter pertama saat PIN aktif langsung mematikan proteksi (fail-open) dan PIN baru (>=4) tak pernah tersimpan karena centang sudah dilepas; kini PIN hanya dimatikan lewat centang yang berkonfirmasi. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 86: teks UI PIN yang hardcode Indonesia jadi resource (Android 5+)
- `SettingsActivity`: dua toast aktivasi PIN yang masih hardcode `"PIN gagal diproses, coba lagi."` diganti `R.string.pin_proses_gagal` (default Inggris; padanan Indonesia di `values-in`). Sebelumnya teks Indonesia muncul di perangkat berbahasa apa pun dan tak bisa diterjemahkan, melanggar aturan bahasa default (teks UI Inggris, log/Telegram Indonesia). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 85: satu pintu pembersihan proses smoke test di Updater (Android 5+)
- `Updater.detectVersion`: tiga blok `try { p.destroy(); }` yang identik diganti satu blok `finally` yang selalu menghentikan watchdog dan membunuh proses; jalur `InterruptedException` yang dulu sempat melewatkan `destroy` kini ikut bersih. Hasil (versi atau null) tidak berubah. Tanpa test baru (butuh eksekusi proses). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 84: penanda versi web-vault tak campur namespace (Android 5+)
- `Updater.versiWvUntukCap`: `KEY_WV_FROM` kini hanya diisi tag Vaultwarden yang benar-benar diunduh; jalur `latest == null` (redirect `WV_UPDATE_URL`) tidak lagi menulis versi dari `vw-version.json`. File itu berisi versi web-vault Bitwarden (mis. `2026.x`) yang namespace-nya berbeda dari tag Vaultwarden, sehingga perbandingan "sudah versi terbaru" di `updateWebVaultInner` selalu meleset dan UI menampilkan versi yang membingungkan. Pemeriksaan `latest == null` sudah memakai keberadaan payload (`wvPayloadAda`), jadi tak ada unduhan ulang tambahan; tampilan versi tetap memakai fallback `readWvVersion` di Main/Settings/TgBot.
- Test `capWvHanyaTagVaultwarden` menggantikan `capWvRedirectDariJson`; nama test lama dicatat di `tools/bekas-hapusan.txt`. Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 83: buang kode mati (overload & konstanta tak terpakai) (Android 5+)
- `TlsCert.extensionsBlock(List<String>)` (overload satu argumen) dihapus; satu-satunya pemanggil memakai varian dua argumen (ips, dns).
- `TgBackup.deriveKey(String, byte[])` dan `deriveKey(String, byte[], boolean)` dihapus; semua jalur memakai varian 4 argumen (SHA256/SHA1 + iterasi eksplisit dari header VWB3).
- `TgBackup.TG_INTERVAL_MS` dihapus (tidak pernah dirujuk; jadwal backup memakai alarm dan `sudahGantiHari`).
- Nama `deriveKey`/`extensionsBlock` tidak dimasukkan `tools/bekas-hapusan.txt` karena masih didefinisikan sebagai overload (pindah, bukan hapus total). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

## [Belum rilis] — Audit 82: memo versi binary bebas balapan antar-thread (Android 5+)
- `Updater.parseBinaryVersion`: memo dua field volatile (`memoBinMasuk`/`memoBinHasil`) diganti satu objek immutable `MemoVersi` yang ditulis atomis. Dulu thread UI (tick tiap detik) dan worker bisa menulis/membaca silang: field `masuk` sudah berganti tetapi `hasil` masih milik input lama, sehingga versi yang dikembalikan salah. Hasil ini dipakai membandingkan kuncian versi (`cacheSesuaiPin`) dan menampilkan versi, jadi salah baca bisa menolak/menerima binary yang keliru.
- Test regresi `parseBinaryVersion_memoAmanAntarThread` (4 thread, input bergantian). Verifikasi via `verifikasi-patch` + `cek-cepat`, build/test penuh di CI.

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
