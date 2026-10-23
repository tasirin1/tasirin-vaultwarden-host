package com.tasirin.vaultwardenhost;

import java.io.File;
import java.io.IOException;

/** Kumpulan logika murni lintas modul (murni JVM agar bisa unit test).
 *  Dibuat untuk menampung duplikasi yang tersebar: cocok chat, redirect aman,
 *  batas unzip, dan kebersihan file temp. */
public final class Util {

    /** Batas total unzip web-vault (~35 MB zip -> maks 300 MB, anti zip-bomb). */
    public static final long BATAS_UNZIP_WEBVAULT = 300L * 1024 * 1024;
    /** Batas total unzip restore database + tls (anti zip-bomb di STB 1 GB). */
    public static final long BATAS_UNZIP_RESTORE = 200L * 1024 * 1024;
    /** Batas jumlah entri zip (anti zip-bomb entri kecil). */
    public static final int BATAS_JUMLAH_ENTRI = 20000;

    private Util() {
    }

    // Pool bersama tugas ringan UI (cek port, refresh berat, auto-check): hemat
    // thread dibanding new Thread per tugas di STB 1 GB. Antrean dibatasi 32 agar
    // spam tak menumpuk OOM; tugas baru DITOLAK saat penuh (bukan membuang tugas
    // lama) supaya pemanggil bisa melepas flag yang sudah diset sebelum submit.
    // Penolakan dicatat (bukan sunyi) agar tugas gugur bisa dilacak di log.
    private static final java.util.concurrent.RejectedExecutionHandler TOLAK_BERLOG =
            penanganAntreanPenuh("Util");

    /** Penanganan antrean penuh bersama: catat lalu TOLAK tugas baru dengan
     *  RejectedExecutionException. Satu pintu untuk Util/TgBot/PinGate.
     *  Dulu tugas tertua dibuang diam-diam: bila tugas itu memegang flag
     *  (health/start/runBusy/PIN), flag tak pernah dilepas dan fitur terkunci
     *  selamanya. Tanpa execute() ulang di dalam handler (anti-rekursi). */
    public static java.util.concurrent.RejectedExecutionHandler penanganAntreanPenuh(
            final String tag) {
        final String t = tag == null ? "Util" : tag;
        return new java.util.concurrent.RejectedExecutionHandler() {
            @Override public void rejectedExecution(Runnable r,
                    java.util.concurrent.ThreadPoolExecutor e) {
                try {
                    android.util.Log.w(t, "Antrean penuh, tugas baru ditolak");
                } catch (Exception ignored) {
                }
                throw new java.util.concurrent.RejectedExecutionException(
                        t + ": antrean penuh");
            }
        };
    }
    /** Buat pool berukuran tetap dengan antrean terbatas + thread daemon.
     *  core = max (dulu core 0 membuat ThreadPoolExecutor hanya memakai SATU
     *  worker sampai antrean penuh, sehingga verifikasi PIN/Start ikut antre di
     *  belakang unduhan 20 MB). allowCoreThreadTimeOut membuang thread idle
     *  setelah 60 dtk agar RAM STB 1 GB tetap hemat. */
    private static java.util.concurrent.ExecutorService buatPool(
            int ukuran, int antrean, final String nama) {
        java.util.concurrent.ThreadPoolExecutor p =
                new java.util.concurrent.ThreadPoolExecutor(ukuran, ukuran, 60L,
                        java.util.concurrent.TimeUnit.SECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<Runnable>(antrean),
                        r -> {
                            Thread t = new Thread(r, nama);
                            t.setDaemon(true);
                            return t;
                        }, TOLAK_BERLOG);
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    private static final java.util.concurrent.ExecutorService BG_RINGAN =
            buatPool(3, 32, "vw-ui-bg");

    // Pool tugas lama (unduh binary/web-vault, auto-update, hash PIN): tugas
    // menit-lama tak boleh memonopoli pool ringan hingga cek port terbuang.
    private static final java.util.concurrent.ExecutorService BG_LAMA =
            buatPool(2, 16, "vw-bg-lama");

    /** Jalankan tugas lama (menit-lama) di pool sendiri; false bila antrean
     *  penuh (tugas TIDAK dijalankan) agar pemanggil melepas flag/kuncinya. */
    public static boolean jalankanLama(Runnable r) {
        if (r == null) {
            return false;
        }
        try {
            BG_LAMA.execute(r);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Digest SHA-256 per thread: getInstance lookup provider tiap panggil boros. */
    private static final ThreadLocal<java.security.MessageDigest> MD_SHA256 =
            new ThreadLocal<java.security.MessageDigest>() {
            };

    /** Ambil digest SHA-256 milik thread ini (digest() me-reset sehingga aman
     *  pakai ulang); lempar bila provider tak ada agar pemanggil fallback.
     *  Murni JVM agar bisa unit test. */
    public static java.security.MessageDigest mdSha256() throws Exception {
        java.security.MessageDigest m = MD_SHA256.get();
        if (m == null) {
            m = java.security.MessageDigest.getInstance("SHA-256");
            MD_SHA256.set(m);
        } else {
            // Bersihkan sisa update() yang gagal digest (exception di tengah):
            // tanpa ini hash berikut di thread sama tercemar state basi.
            try {
                m.reset();
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    /** Digit heksa kecil bersama (tanpa Locale): satu tabel dipakai LogExport,
     *  TgBackup, TgBot, PinCrypto agar tak triplikat di tiap berkas. Murni. */
    static final char[] HEKS = "0123456789abcdef".toCharArray();

    /** Tulis 12 digit heksa (48-bit) manual tanpa String.format.
     *  Satu implementasi dipakai LogExport + TgBackup. Murni. */
    static String hex12(long v) {
        char[] o = new char[12];
        for (int i = 11; i >= 0; i--) {
            o[i] = HEKS[(int) (v & 15)];
            v >>>= 4;
        }
        return new String(o);
    }

    /** Byte -> heks kecil satu pintu (tanpa String.format/append per nibble).
     *  Dipakai sidikClip, sidikBerkas, rahasiaAlarm, Updater.toHex agar
     *  tabel + loop tak triplikat di tiap berkas. Murni. */
    static String heks(byte[] data) {
        if (data == null) {
            return "";
        }
        char[] o = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            o[i * 2] = HEKS[v >>> 4];
            o[i * 2 + 1] = HEKS[v & 15];
        }
        return new String(o);
    }

    /** Rendahkan ASCII A-Z tanpa Locale (tanpa alokasi Locale tiap panggil).
     *  Untuk host/hex/username/versi yang murni ASCII; bukan teks lokal. Murni. */
    public static String rendahAscii(String s) {
        if (s == null) {
            return "";
        }
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                char[] o = s.toCharArray();
                o[i] = (char) (c + 32);
                for (int j = i + 1; j < n; j++) {
                    char d = o[j];
                    if (d >= 'A' && d <= 'Z') {
                        o[j] = (char) (d + 32);
                    }
                }
                return new String(o);
            }
        }
        return s;
    }

    /** True bila s numerik 4+ digit (pengganti regex "\d{4,}"). Murni. */
    static boolean numerik4Plus(String s) {
        if (s == null || s.length() < 4) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** True bila kandidat PIN implisit valid: min 4 char tanpa spasi dan
     *  berdigit ATAU bersimbol (pengganti 3 regex PIN). Murni. */
    static boolean pinKataValid(String s) {
        if (s == null || s.length() < 4) {
            return false;
        }
        boolean digit = false;
        boolean simbol = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ') {
                return false;
            }
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                simbol = true;
            }
        }
        return digit || simbol;
    }

    /** Cache formatter tanggal per pola (satu pintu): ganti 4 ThreadLocal
     *  pola sama yang tersebar (LogExport/TgBackup/TgBot) agar thread tak
     *  memegang Calendar+TimeZone ganda di STB 1 GB. LOG_TS ServerService
     *  sengaja tetap langsung (jalur log terpanas, tanpa lookup map). Murni. */
    private static final java.util.concurrent.ConcurrentHashMap<String, ThreadLocal<java.text.SimpleDateFormat>> FMT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, ThreadLocal<java.text.SimpleDateFormat>>();

    /** Format tanggal pola Locale.US ("" bila gagal). Murni. */
    public static String formatTanggal(String pola, java.util.Date t) {
        if (pola == null || t == null) {
            return "";
        }
        try {
            ThreadLocal<java.text.SimpleDateFormat> tl = FMT_CACHE.get(pola);
            if (tl == null) {
                final String pe = pola;
                ThreadLocal<java.text.SimpleDateFormat> buat =
                        new ThreadLocal<java.text.SimpleDateFormat>() {
                    @Override protected java.text.SimpleDateFormat initialValue() {
                        return new java.text.SimpleDateFormat(pe, java.util.Locale.US);
                    }
                };
                ThreadLocal<java.text.SimpleDateFormat> menang =
                        FMT_CACHE.putIfAbsent(pola, buat);
                tl = menang != null ? menang : buat;
            }
            return tl.get().format(t);
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Format tanggal STB locale Indonesia per thread ("d MMM yyyy HH:mm"):
     *  satu pintu pengganti ThreadLocal kembar identik di Updater + TgBackup
     *  (tiap ThreadLocal memegang Calendar+TimeZone+Locale sendiri per thread).
     *  Locale Indonesia disengaja (nama bulan "Mei"/"Agu"), bukan Locale.US. */
    private static final ThreadLocal<java.text.SimpleDateFormat> FMT_STB_ID =
            new ThreadLocal<java.text.SimpleDateFormat>() {
                @Override protected java.text.SimpleDateFormat initialValue() {
                    return new java.text.SimpleDateFormat("d MMM yyyy HH:mm",
                            new java.util.Locale("id", "ID"));
                }
            };

    /** Tanggal STB terbaca ("d MMM yyyy HH:mm", fallback millis bila gagal). */
    public static String tanggalStb(long kiniMs) {
        try {
            return FMT_STB_ID.get().format(new java.util.Date(kiniMs));
        } catch (Exception ignored) {
            return String.valueOf(kiniMs);
        }
    }

    /** Format waktu kini pola Locale.US ("" bila gagal). Murni. */
    public static String formatTanggalSekarang(String pola) {
        try {
            return formatTanggal(pola, new java.util.Date());
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Handler utama bersama: hemat alokasi Handler per operasi clipboard/timer. */
    private static volatile android.os.Handler HANDLER_UTAMA = null;
    /** Kunci pembuatan handler: tanpa ini dua thread bersamaan membuat dua
     *  Handler lalu satu bocor (volatile check-then-act tak atomik). */
    private static final Object KUNCI_HANDLER = new Object();

    /** Handler utama bersama satu pintu (ganti blok ambil-handler ganda
     *  di postTunda/postUtama yang rawan drift). */
    private static android.os.Handler handlerUtama() {
        try {
            android.os.Looper looper = android.os.Looper.getMainLooper();
            if (looper == null) {
                return null;
            }
            android.os.Handler h = HANDLER_UTAMA;
            if (h == null || h.getLooper() != looper) {
                synchronized (KUNCI_HANDLER) {
                    h = HANDLER_UTAMA;
                    if (h == null || h.getLooper() != looper) {
                        h = new android.os.Handler(looper);
                        HANDLER_UTAMA = h;
                    }
                }
            }
            return h;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Tunda runnable di looper utama (best-effort, abaikan bila tak ada). */
    public static void postTunda(Runnable r, long tundaMs) {
        if (r == null) {
            return;
        }
        try {
            android.os.Handler h = handlerUtama();
            if (h == null) {
                return;
            }
            h.postDelayed(r, tundaMs);
        } catch (Exception ignored) {
        }
    }

    /** Kirim ke looper utama tanpa tunda (hemat alokasi Handler per panggil). */
    public static void postUtama(Runnable r) {
        if (r == null) {
            return;
        }
        try {
            android.os.Handler h = handlerUtama();
            if (h == null) {
                return;
            }
            h.post(r);
        } catch (Exception ignored) {
        }
    }

    /** Batalkan runnable tertunda di looper utama (best-effort, abaikan bila tak ada).
     *  Satu pintu pasangan postTunda: ServerService dulunya punya salinan
     *  batalUtama+handler sendiri (duplikat + balapan double-create tanpa kunci).
     *  Nama batalkanTunda (bukan batalTunda): nama pendeknya terlarang di
     *  tools/bekas-hapusan.txt (metode mati yang dibuang audit-38). */
    public static void batalkanTunda(Runnable r) {
        if (r == null) {
            return;
        }
        try {
            // Tanpa membuat handler: belum pernah post = tak ada yang dibatalkan.
            android.os.Handler h = HANDLER_UTAMA;
            if (h == null) {
                return;
            }
            h.removeCallbacks(r);
        } catch (Exception ignored) {
        }
    }

    /** Nyalakan watchdog proses --version (satu pintu ServerService + Updater).
     *  readLine() pemanggil bisa memblokir selamanya bila binary macet tanpa
     *  output; destroy terjadwal menutup pipa sehingga baca balik EOF.
     *  Selesai lebih awal = interrupt oleh pemanggil (tanpa destroy).
     *  AtomicBoolean (bukan boolean[]): tulis pemanggil wajib terlihat thread
     *  watchdog tanpa synchronized. Murni JVM agar bisa unit test. */
    public static Thread pasangWatchdogProses(final Process proc,
            final java.util.concurrent.atomic.AtomicBoolean selesai,
            final long timeoutMs, final String nama) {
        Thread w = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(timeoutMs);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    if (selesai != null && !selesai.get() && proc != null) {
                        proc.destroy();
                    }
                } catch (Exception ignored) {
                }
            }
        }, nama == null || nama.isEmpty() ? "vw-watchdog" : nama);
        try {
            w.setDaemon(true);
            w.start();
        } catch (Exception ignored) {
        }
        return w;
    }

    /** Pecah hash PIN "A$B$C$D" tanpa regex (split kompilasi Pattern tiap panggil di API 21). */
    public static String[] pecahPin(String s) {
        if (s == null) {
            return null;
        }
        int a = s.indexOf('$');
        if (a < 0) {
            return null;
        }
        int b = s.indexOf('$', a + 1);
        if (b < 0) {
            return null;
        }
        int c = s.indexOf('$', b + 1);
        if (c < 0 || s.indexOf('$', c + 1) >= 0) {
            return null;
        }
        String[] out = new String[4];
        out[0] = s.substring(0, a);
        out[1] = s.substring(a + 1, b);
        out[2] = s.substring(b + 1, c);
        out[3] = s.substring(c + 1);
        return out;
    }

    /** Contains abaikan-huruf tanpa alokasi toLowerCase (hemat GC log deras STB). */
    public static boolean mengandungAbaikanHuruf(String hay, String needle) {
        if (hay == null || needle == null || needle.isEmpty() || hay.length() < needle.length()) {
            return false;
        }
        // Jalur cepat ASCII (token/bot/admin/... semua ASCII): banding (c|32)
        // tanpa lipatan case Unicode regionMatches yang mahal di ART lama.
        boolean ascii = true;
        for (int k = 0; k < needle.length(); k++) {
            if (needle.charAt(k) >= 128) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return cocokAscii(hay, needle);
        }
        int n = hay.length() - needle.length();
        for (int i = 0; i <= n; i++) {
            if (hay.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }

    /** Pencocokan case-insensitive khusus ASCII (huruf A-Z saja dilipat).
     *  Murni agar bisa unit test. */
    public static boolean cocokAscii(String hay, String needle) {
        int n = hay.length() - needle.length();
        for (int i = 0; i <= n; i++) {
            int j = 0;
            while (j < needle.length() && samaHurufAscii(hay.charAt(i + j), needle.charAt(j))) {
                j++;
            }
            if (j >= needle.length()) {
                return true;
            }
        }
        return false;
    }

    /** Sama-huruf ASCII: sama persis, atau kedua huruf yang beda case saja. */
    static boolean samaHurufAscii(char a, char b) {
        if (a == b) {
            return true;
        }
        if (a >= 'A' && a <= 'Z') {
            a = (char) (a + 32);
        }
        if (b >= 'A' && b <= 'Z') {
            b = (char) (b + 32);
        }
        return a == b;
    }

    /** Jalankan tugas ringan di pool bersama; false bila antrean penuh (tugas
     *  TIDAK dijalankan) agar pemanggil melepas flag yang sudah diset. */
    public static boolean jalankanBg(Runnable r) {
        if (r == null) {
            return false;
        }
        try {
            BG_RINGAN.execute(r);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** True bila s menyerupai integer desimal ([+-]?digit, min 1 digit):
     *  parseLong di bawah tak melempar untuk panjang wajar; digit raksasa
     *  (overflow) tetap ditangkap catch pemanggil. Murni. */
    static boolean angkaBulat(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int i = 0;
        char depan = s.charAt(0);
        if (depan == '+' || depan == '-') {
            i = 1;
        }
        if (i >= s.length()) {
            return false;
        }
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** True bila s hanya digit ASCII (tanpa kompilasi regex matches). */
    static boolean semuaDigit(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Potong host sampai / ? # tanpa split regex (tanpa kompilasi). */
    static String potongHost(String h) {
        int n = h.length();
        for (int i = 0; i < n; i++) {
            char c = h.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                return h.substring(0, i);
            }
        }
        return h;
    }

    /** Trim aman untuk hasil prefs yang bisa null (tanpa NPE). Murni. */
    public static String amanTrim(String s) {
        return s == null ? "" : s.trim();
    }

    /** True bila pesan Telegram berasal dari chat resmi.
      *  Dukung ID numerik ("123456") dan username ("@nama" / "nama",
      *  tanpa peka huruf). Murni agar bisa unit test.
      *  Catatan keamanan: username (@nama) tidak aman sebagai identitas —
      *  username bisa dilepas lalu diklaim orang lain, beda dengan ID numerik
      *  yang permanen. Demi kompatibilitas lama, username tetap dicocokkan,
      *  tapi pemakaian username dicatat sebagai peringatan sekali per proses
      *  dan pemilik dianjurkan memakai ID numerik di pengaturan bot. */
    private static volatile boolean peringatanUsernameSudah = false;

    public static boolean cocokChat(String config, long id, String username) {
        if (config == null) {
            return false;
        }
        String c = config.trim();
        if (c.isEmpty()) {
            return false;
        }
        // ID tempel dari nomor telepon ("+628...") disamakan ke ID numerik
        // Telegram agar bot tak diam tanpa pesan galat.
        if (c.startsWith("+") && c.length() > 1) {
            c = c.substring(1).trim();
            if (c.isEmpty()) {
                return false;
            }
        }
        // Nol-depan ("0123") bukan format ID Telegram: tolak agar tak
        // cocok longgar dengan ID 123 (fail-closed ke tidak-cocok).
        if (c.length() > 1 && c.startsWith("0")) {
            return false;
        }
        // Saring digit dulu: config username tak melempar NumberFormatException
        // + isi stack trace tiap pesan masuk di ART lama. Digit raksasa
        // (overflow) tetap jatuh ke cocok username selaras perilaku lama.
        if (angkaBulat(c)) {
            try {
                return id == Long.parseLong(c);
            } catch (NumberFormatException ignored) {
            }
        }
        String norm = c.startsWith("@") ? c.substring(1) : c;
        if (norm.isEmpty() || username == null) {
            return false;
        }
        // Username murni angka (mis. "@12345") diperlakukan sebagai ID agar
        // tak bisa diklaim lewat username; config numerik wajib cocok ID.
        if (semuaDigit(norm)) {
            // 20+ digit pasti overflow (selaras catch -> false di bawah)
            // sehingga tak membayar eksepsi tiap pesan masuk.
            if (norm.length() > 19) {
                return false;
            }
            try {
                if (norm.length() > 1 && norm.startsWith("0")) {
                    return false;
                }
                return id == Long.parseLong(norm);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        // Peringatan sekali: config masih memakai username (bukan ID numerik).
        // Username tetap dicocokkan agar kompatibel, tapi ID numerik dianjurkan.
        if (!peringatanUsernameSudah) {
            peringatanUsernameSudah = true;
            try {
                android.util.Log.w("Util", "[tg] Chat bot memakai username (@"
                        + norm + "), bukan ID numerik. Username bisa diklaim"
                        + " ulang orang lain — ganti ke ID numerik di pengaturan.");
            } catch (Throwable ignored) {
            }
        }
        // Kupas satu @ depan manual: replaceFirst mengkompilasi regex tiap
        // pesan masuk di API 21 (aturan repo: tanpa regex per panggil).
        String u = username.trim();
        if (u.startsWith("@") && u.length() > 1) {
            u = u.substring(1);
        }
        return norm.equalsIgnoreCase(u);
    }

    /** Batas jumlah chat tujuan Telegram (hemat bandwidth/CPU STB). Murni. */
    public static final int MAKS_CHAT_TUJUAN = 5;
    /** Batas keras entri parser daftar chat (anti-DoS config impor/edit manual
     *  raksasa: contains() kuadrat + kirim per chat tiap tick). Validasi resmi
     *  tetap MAKS_CHAT_TUJUAN; auth/kirim konsisten karena satu parser. Murni. */
    public static final int BATAS_ENTRI_CHAT = 32;

    /** Pecah config chat jadi daftar tujuan (pisah koma/titik-koma/spasi/baris).
     *  Urutan dipertahankan, duplikat dibuang, kosong diabaikan. Tanpa regex
     *  (aturan repo: tanpa kompilasi pola per panggil). Murni agar bisa unit test. */
    public static java.util.List<String> pecahDaftarChat(String config) {
        java.util.List<String> hasil = new java.util.ArrayList<String>();
        if (config == null) {
            return hasil;
        }
        // Set pendamping: contains() di daftar adalah pindai O(n) sehingga
        // dedup murni-kuadrat; set menjaga O(1) dengan urutan tetap di daftar.
        java.util.Set<String> terlihat = new java.util.HashSet<String>();
        int n = config.length();
        int awal = -1;
        for (int i = 0; i <= n; i++) {
            char c = i < n ? config.charAt(i) : ',';
            boolean pisah = c == ',' || c == ';' || c == '\n' || c == '\r'
                    || c == '\t' || c == ' ';
            if (pisah) {
                if (awal >= 0) {
                    String potong = config.substring(awal, i).trim();
                    if (!potong.isEmpty() && terlihat.add(potong)) {
                        hasil.add(potong);
                        if (hasil.size() >= BATAS_ENTRI_CHAT) {
                            break;
                        }
                    }
                    awal = -1;
                }
            } else if (awal < 0) {
                awal = i;
            }
        }
        return hasil;
    }

    /** True bila pengirim cocok dengan salah satu chat di daftar config.
     *  Config satu ID lama tetap jalan (daftar satu entri). Murni. */
    public static boolean cocokChatBanyak(String config, long id, String username) {
        java.util.List<String> daftar = pecahDaftarChat(config);
        for (int i = 0; i < daftar.size(); i++) {
            if (cocokChat(daftar.get(i), id, username)) {
                return true;
            }
        }
        return false;
    }

    /** Satu sumber kebenaran validitas satu entri chat: ID numerik (awalan
     *  + opsional ala tempel nomor telepon, - untuk grup) atau username
     *  (aturan Telegram: huruf-digit-garisbawah, min 5, @ opsional).
     *  Selaras cocokChat: valid selalu berarti bisa cocok (nol-depan dan
     *  digit-murni ber-@ ditolak), plus tanpa whitespace/kontrol agar aman
     *  dipakai di header multipart. Dipakai UI, impor, dan upload agar satu
     *  suara. Tanpa regex. Murni agar bisa unit test. */
    /** Banding dua string digit sama panjang: -1/0/1. Tanpa alokasi. Murni. */
    static int bandingDigit(String a, String b) {
        for (int i = 0; i < a.length() && i < b.length(); i++) {
            char x = a.charAt(i);
            char y = b.charAt(i);
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        if (a.length() == b.length()) {
            return 0;
        }
        return a.length() < b.length() ? -1 : 1;
    }

    public static boolean chatTunggalValid(String entri) {
        if (entri == null) {
            return false;
        }
        String t = entri.trim();
        if (t.isEmpty() || t.length() > 64) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c <= ' ' || c == 127) {
                return false;
            }
        }
        String isi = t;
        if (isi.startsWith("+") && isi.length() > 1) {
            isi = isi.substring(1);
        }
        if (isi.isEmpty()) {
            return false;
        }
        // Numerik dulu (cocokChat juga menyaring digit sebelum parse agar
        // config username tak melempar di ART lama).
        boolean negatif = isi.startsWith("-");
        String angka = negatif ? isi.substring(1) : isi;
        if (!angka.isEmpty() && semuaDigit(angka)) {
            if (angka.length() > 19) {
                return false;
            }
            if (angka.length() > 1 && angka.startsWith("0")) {
                return false;
            }
            // 19 digit bisa lewat Long.MAX (9,2e18): cocokChat pasti gagal
            // parse lalu jatuh ke banding username (tak cocok). Tolak di sini
            // agar valid selalu berarti bisa cocok. Banding leksikografis
            // manual (sama panjang) tanpa alokasi/eksepsi. Murni.
            if (angka.length() == 19) {
                String batas = negatif ? "9223372036854775808"
                        : "9223372036854775807";
                if (bandingDigit(angka, batas) > 0) {
                    return false;
                }
            }
            return true;
        }
        String nama = isi.startsWith("@") ? isi.substring(1) : isi;
        if (nama.isEmpty() || semuaDigit(nama) || nama.length() < 5) {
            return false;
        }
        for (int i = 0; i < nama.length(); i++) {
            char c = nama.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** True bila salah satu chat di daftar wajib diperlakukan seperti grup.
     *  Fail-closed: satu entri grup/username mengetatkan semua (butuh PIN).
     *  Murni agar bisa unit test. */
    public static boolean daftarPerluAnggapGrup(String config) {
        java.util.List<String> daftar = pecahDaftarChat(config);
        for (int i = 0; i < daftar.size(); i++) {
            if (chatPerluAnggapGrup(daftar.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** True bila config chat menunjuk grup/supergrup (ID numerik negatif).
     *  Username (@grup) tak bisa dipastikan pemiliknya sehingga dianggap
     *  bukan grup. Murni agar bisa unit test. */
    public static boolean chatAdalahGrup(String config) {
        if (config == null) {
            return false;
        }
        String c = config.trim();
        if (c.isEmpty() || c.startsWith("@")) {
            return false;
        }
        if (c.startsWith("+") && c.length() > 1) {
            c = c.substring(1).trim();
        }
        // Tanpa eksepsi untuk config username (tiap perintah bot di ART lama).
        if (!angkaBulat(c)) {
            return false;
        }
        try {
            return Long.parseLong(c) < 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /** True bila chat wajib diperlakukan seperti grup (selalu butuh PIN).
     *  ID negatif = grup pasti; config username/tak-terurai (mis. "@grup")
     *  tak bisa dipastikan chat pribadi sehingga fail-closed dianggap grup —
     *  kalau tidak, bot yang dipasang di grup via username diperlakukan
     *  sebagai chat pribadi dan semua anggota bisa memerintah. Kosong/null =
     *  bot belum dikonfigurasi (bukan grup). Murni agar bisa unit test. */
    public static boolean chatPerluAnggapGrup(String config) {
        if (chatAdalahGrup(config)) {
            return true;
        }
        if (config == null) {
            return false;
        }
        String c = config.trim();
        if (c.isEmpty()) {
            return false;
        }
        if (c.startsWith("+") && c.length() > 1) {
            c = c.substring(1).trim();
            if (c.isEmpty()) {
                return false;
            }
        }
        String norm = c.startsWith("@") ? c.substring(1) : c;
        if (norm.isEmpty()) {
            return false;
        }
        // Tanpa eksepsi untuk username; non-angka = anggap grup (fail-closed).
        if (!angkaBulat(norm)) {
            return true;
        }
        try {
            return Long.parseLong(norm) < 0;
        } catch (NumberFormatException ignored) {
            // Digit raksasa (overflow): pemilik tak pasti, anggap grup.
            return true;
        }
    }

    /** True bila pesan masih segar (tidak basi). Pesan sedikit di masa depan
     *  (jam STB lambat) tetap diterima; masa depan jauh ditolak seperti tombol
     *  inline (toleransi selebar jendela basi) agar antrean Telegram bertanggal
     *  menyimpang tak bisa mengeksekusi perintah kapan pun. Murni. */
    public static boolean pesanSegar(long dateMs, long sekarang, long basiMs) {
        // Tanpa tanggal = basi (fail-closed): perintah berbahaya basi
        // (mis. /stop tertunda) tak boleh lolos karena field date hilang.
        if (dateMs <= 0) {
            return false;
        }
        if (dateMs > sekarang + Math.max(0, basiMs)) {
            return false;
        }
        if (dateMs > sekarang) {
            return true;
        }
        return sekarang - dateMs <= basiMs;
    }

    /** True bila redirect boleh diikuti: hanya skema https (atau path relatif).
     *  Cegah downgrade https -> http saat unduh binary/checksum. Murni. */
    public static boolean bolehIkutiRedirect(String asal, String lokasi) {
        if (lokasi == null || lokasi.isEmpty()) {
            return false;
        }
        String l = lokasi.trim();
        if (l.startsWith("//")) {
            // Protokol-relatif ganti host: hanya boleh se-host dengan asal
            // (mis. CDN), bukan host sembarang. Mutlak https tetap dicek
            // di sambungRedirect via warisan skema dasar.
            return hostSama(asal, l.substring(2));
        }
        if (l.startsWith("/")) {
            return true;
        }
        int kol = l.indexOf(':');
        if (kol < 0) {
            return true;
        }
        return "https".equals(rendahAscii(l.substring(0, kol)));
    }

    /** True bila host termasuk rilis GitHub (unduhan binary/web-vault).
     *  Redirect asset github.com hanya ke objects.githubusercontent.com /
     *  release-assets.githubusercontent.com; wildcard *.githubusercontent.com
     *  ditolak karena konten user (gist/raw) bisa menyajikan binary + sha256
     *  palsu yang cocok bila DNS/MITM. Murni agar bisa unit test. */
    public static boolean hostGithubAman(String host) {
        if (host == null) {
            return false;
        }
        // Kupas ":port" dulu seperti hostTelegramAman: cabang "//host"
        // memberi otoritas mentah sehingga redirect sah berport eksplisit
        // ("//github.com:443/...") tertolak tanpa ini.
        String kupas = kupasHostPort(host.trim());
        if (kupas == null || kupas.isEmpty()) {
            return false;
        }
        String h = rendahAscii(normalisasiHost(kupas));
        if (h.isEmpty()) {
            return false;
        }
        if (h.equals("github.com") || h.equals("api.github.com")
                || h.equals("codeload.github.com")
                || h.equals("objects.githubusercontent.com")
                || h.equals("release-assets.githubusercontent.com")) {
            return true;
        }
        // CDN produksi lama (github-production-release-asset-*.s3 / *.githubusercontent.com):
        // wildcard umum *.githubusercontent.com tetap ditolak (konten user bisa
        // menyajikan binary + sha256 palsu), hanya prefix produksi resmi yang lolos.
        if (h.startsWith("github-production-release-asset-")
                && h.endsWith(".githubusercontent.com")) {
            return true;
        }
        return false;
    }

    /** True bila host milik Telegram (unduhan file bot).
     *  URL unduh file membawa token bot di path sehingga host sembarang wajib
     *  ditolak: 302 nakal (MITM/CDN jahat) bisa membocorkan token. Keluarga
     *  telegram.org + cdn-telegram.org mencakup API dan CDN resmi. Murni. */
    public static boolean hostTelegramAman(String host) {
        if (host == null) {
            return false;
        }
        String kupas = kupasHostPort(host.trim());
        if (kupas == null || kupas.isEmpty()) {
            return false;
        }
        String h = rendahAscii(normalisasiHost(kupas));
        if (h.isEmpty()) {
            return false;
        }
        if (h.equals("telegram.org") || h.endsWith(".telegram.org")) {
            return true;
        }
        return h.equals("cdn-telegram.org") || h.endsWith(".cdn-telegram.org");
    }

    /** True bila redirect unduhan file Telegram boleh diikuti: https + host
     *  Telegram (lihat hostTelegramAman). Dipakai bukaFileTelegram agar token
     *  di path URL tak bocor ke host asing via 302. Murni agar bisa unit test. */
    public static boolean bolehIkutiRedirectTelegram(String asal, String lokasi) {
        return bolehIkutiRedirectHost(asal, lokasi, true);
    }

    /** True bila redirect unduhan GitHub boleh diikuti: https + host GitHub.
     *  Dipakai Updater (binary/web-vault/shim).
     *  Murni agar bisa unit test. */
    public static boolean bolehIkutiRedirectGithub(String asal, String lokasi) {
        return bolehIkutiRedirectHost(asal, lokasi, false);
    }

    /** Inti tunggal validasi redirect host (Telegram bila telegram=true,
     *  else GitHub): satu parse + satu lower skema, bukan ganda. Murni. */
    private static boolean bolehIkutiRedirectHost(String asal, String lokasi, boolean telegram) {
        if (!bolehIkutiRedirect(asal, lokasi)) {
            return false;
        }
        if (lokasi == null) {
            return false;
        }
        String l = lokasi.trim();
        if (l.startsWith("//")) {
            String host = potongHost(l.substring(2));
            return telegram ? hostTelegramAman(host) : hostGithubAman(host);
        }
        if (l.startsWith("/")) {
            return true;
        }
        int kol = l.indexOf(':');
        try {
            if (kol < 0) {
                String gabung = sambungRedirect(asal, l);
                String host = new java.net.URL(gabung).getHost();
                return telegram ? hostTelegramAman(host) : hostGithubAman(host);
            }
            if (!"https".equals(rendahAscii(l.substring(0, kol)))) {
                return false;
            }
            String host = new java.net.URL(l).getHost();
            return telegram ? hostTelegramAman(host) : hostGithubAman(host);
        } catch (Exception e) {
            return false;
        }
    }

    /** True bila sisa lokasi "//host/..." menunjuk host yang sama dengan asal.
     *  Murni agar bisa unit test. */
    static boolean hostSama(String asal, String sisa) {
        if (asal == null || sisa == null || sisa.isEmpty()) {
            return false;
        }
        try {
            String hostAsal = new java.net.URL(asal.trim()).getHost();
            if (hostAsal == null || hostAsal.isEmpty()) {
                return false;
            }
            int ujung = sisa.length();
            for (int i = 0; i < sisa.length(); i++) {
                char c = sisa.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    ujung = i;
                    break;
                }
            }
            String host = kupasHostPort(sisa.substring(0, ujung));
            return host != null && !host.isEmpty()
                    && host.equalsIgnoreCase(normalisasiHost(hostAsal));
        } catch (Exception e) {
            return false;
        }
    }

    /** Kupas "host", "host:port", "[v6]", "[v6]:port", atau literal IPv6 tanpa
     *  kurung-siku menjadi nama host (tanpa port/kurung). Null bila tak jelas.
     *  Potong-di-':'-pertama merusak IPv6 (isinya banyak ':') sehingga redirect
     *  se-host "[2001:db8::1]" dulu ditolak walau sah. Murni. */
    static String kupasHostPort(String hostPort) {
        if (hostPort == null || hostPort.isEmpty()) {
            return null;
        }
        if (hostPort.charAt(0) == '[') {
            int tutup = hostPort.indexOf(']');
            if (tutup < 0) {
                return null;
            }
            String ekor = hostPort.substring(tutup + 1);
            if (!ekor.isEmpty() && !(ekor.charAt(0) == ':' && semuaDigit(ekor.substring(1)))) {
                return null;
            }
            String dalam = hostPort.substring(1, tutup);
            return dalam.isEmpty() ? null : dalam;
        }
        int titikDua = 0;
        for (int i = 0; i < hostPort.length(); i++) {
            if (hostPort.charAt(i) == ':') {
                titikDua++;
            }
        }
        if (titikDua > 1) {
            return hostPort;
        }
        if (titikDua == 1) {
            int i = hostPort.indexOf(':');
            String h = hostPort.substring(0, i);
            String port = hostPort.substring(i + 1);
            // Port wajib angka: "host:abc" bukan otoritas valid sehingga
            // null (ditolak) alih-alih terkupas jadi host telanjang.
            if (h.isEmpty() || port.isEmpty() || !semuaDigit(port)) {
                return null;
            }
            return h;
        }
        return hostPort;
    }

    /** Samakan bentuk host: buang kurung-siku IPv6 ("[::1]" -> "::1").
     *  getHost() tak pernah membawa port sehingga replaceFirst hapus-port
     *  yang lama mati (malah menggerogoti IPv6). Murni. */
    static String normalisasiHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim();
        if (h.length() >= 2 && h.charAt(0) == '['
                && h.charAt(h.length() - 1) == ']') {
            return h.substring(1, h.length() - 1);
        }
        return h;
    }

    /** Selesaikan URL redirect relatif terhadap URL dasar. Murni. */
    public static String sambungRedirect(String dasar, String lokasi) {
        if (lokasi == null) {
            return dasar;
        }
        String l = lokasi.trim();
        if (l.regionMatches(true, 0, "https://", 0, 8)) {
            return l;
        }
        // Fail-closed: absolut http:// tak pernah diteruskan (downgrade
        // cleartext). Pemanggil memvalidasi via bolehIkutiRedirect* dulu,
        // tapi pemanggil langsung yang lupa validasi tetap aman: tahan di
        // URL dasar agar loop redirect berakhir "Terlalu banyak redirect".
        if (l.regionMatches(true, 0, "http://", 0, 7)) {
            return dasar;
        }
        if (l.startsWith("//")) {
            // Protokol-relatif: warisi skema dasar (tetap https), ganti host.
            try {
                java.net.URL u = new java.net.URL(dasar);
                return u.getProtocol() + ":" + l;
            } catch (Exception e) {
                return dasar;
            }
        }
        if (l.startsWith("/")) {
            try {
                java.net.URL u = new java.net.URL(dasar);
                return u.getProtocol() + "://" + u.getHost()
                        + (u.getPort() < 0 ? "" : ":" + u.getPort()) + l;
            } catch (Exception e) {
                // Dasar rusak: jangan kembalikan path relatif mentah (pemanggil
                // gagal "no protocol" membingungkan) — tahan di URL terakhir
                // yang baik agar loop redirect berakhir "Terlalu banyak redirect".
                return dasar;
            }
        }
        try {
            return new java.net.URL(new java.net.URL(dasar), l).toString();
        } catch (Exception e) {
            return dasar;
        }
    }

    /** True bila file temp aman dihapus: hanya di dalam cache/files internal.
     *  Cegah hapus file user di /sdcard saat restore gagal. Murni. */
    public static boolean bolehHapusFile(File cacheDir, File filesDir, File target) {
        if (target == null) {
            return false;
        }
        try {
            String kanon = target.getCanonicalPath();
            if (cacheDir != null) {
                String c = cacheDir.getCanonicalPath();
                if (kanon.equals(c) || kanon.startsWith(c + File.separator)) {
                    return true;
                }
            }
            if (filesDir != null) {
                String f = filesDir.getCanonicalPath();
                if (kanon.equals(f) || kanon.startsWith(f + File.separator)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** Akumulasi ukuran unzip; lempar IOException bila lewati batas/maks entri.
     *  Murni agar bisa unit test. */
    public static long tambahUkuranUnzip(long total, long tambah, long batas,
            int jumlah, int batasJumlah) throws IOException {
        if (jumlah > batasJumlah) {
            throw new IOException("Terlalu banyak entri zip (" + jumlah + ").");
        }
        long hasil = total + Math.max(0, tambah);
        if (hasil > batas) {
            throw new IOException("Ukuran unzip melebihi batas " + batas + " byte.");
        }
        return hasil;
    }
}
