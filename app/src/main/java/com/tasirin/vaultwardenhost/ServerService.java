package com.tasirin.vaultwardenhost;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

public class ServerService extends Service {

    public static final String ACTION_START = "com.tasirin.vaultwardenhost.START";
    public static final String ACTION_STOP = "com.tasirin.vaultwardenhost.STOP";
    public static final String ACTION_RESTART = "com.tasirin.vaultwardenhost.RESTART";
    public static final String ACTION_TG_BACKUP = "com.tasirin.vaultwardenhost.TG_BACKUP";

    public static final String PREFS = "vw_prefs";
    /** Folder data bawaan: penyimpanan eksternal perangkat + vaultwarden.
     *  Dulunya hardcode "/sdcard/vaultwarden" yang tak ada di sebagian perangkat;
     *  kini ikut Environment dengan fallback lama agar tetap bisa start.
     *  Konstanta ini di-cache saat class-load (bisa basi bila storage belum
     *  mount); kode baru wajib memakai {@link #dataDirBawaanSegar()} untuk
     *  fallback agar selalu baca kondisi storage terkini. */
    public static final String DEFAULT_DATA_DIR = defaultDataDir();

    /** Folder data bawaan yang dihitung ulang tiap dipanggil (anti basi:
     *  bila class-load terjadi sebelum storage mount, konstanta di atas
     *  menunjuk fallback lama selamanya). Murni I/O ringan. */
    public static String dataDirBawaanSegar() {
        return defaultDataDir();
    }

    /** Cache folder bawaan per proses: argumen default amanString() dievaluasi
     *  duluan tiap panggil (termasuk tick UI), jadi binder getExternalStorage
     *  tak boleh dibayar tiap tick. Jalur storage tak berubah tanpa reboot. */
    private static volatile String cacheDirBawaan = null;

    // getExternalStorageDirectory lawas sengaja agar satu jalur kode untuk API 21-32.
    @SuppressWarnings("deprecation")
    private static String defaultDataDir() {
        String c = cacheDirBawaan;
        if (c != null) {
            return c;
        }
        String hasil = "/sdcard/vaultwarden";
        try {
            java.io.File ext = android.os.Environment.getExternalStorageDirectory();
            if (ext != null) {
                hasil = new java.io.File(ext, "vaultwarden").getAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        cacheDirBawaan = hasil;
        return hasil;
    }
    public static final String DEFAULT_PORT = "8088";
    /** Binary selalu 32-bit ARM (armeabi-v7a); HP arm64 tetap jalan via compat mode. */
    public static final String ABI = "armeabi-v7a";
    public static final String KEY_DATA_DIR = "data_dir";
    public static final String KEY_PORT = "port";
    public static final String KEY_AUTO_START = "auto_start";
    public static final String KEY_UPDATE_VERSION = "update_version";
    /** Selalu true (HTTPS-only: HTTP tak bisa dipakai). Dipertahankan agar
     *  config/backup lama tetap bisa dibaca. */
    public static final String KEY_HTTPS = "https";
    public static final String KEY_ADMIN_TOKEN = "admin_token";
    public static final String KEY_AUTO_UPDATE = "auto_update_binary";
    public static final String KEY_AUTO_UPDATE_WV = "auto_update_webvault";
    public static final String KEY_AUTO_RESTART_UPDATE = "auto_restart_update";
    /** SHA-256 (hex) binary manual di folder data; wajib diisi bila pakai binary sendiri. */
    public static final String KEY_BIN_SHA = "bin_sha";
    /** Revisi patch binary rilis (naikkan bila CI memperbaiki binary tanpa ganti versi). */
    public static final String KEY_BIN_PATCH = "bin_patch_rev";
    /** 3 = binary pasca-patch DNS+TLS favicon (tanpa ndk-context/platform-verifier). */
    public static final int BIN_PATCH_REV = 3;
    /** Versi binary yang dikunci user (mis. "1.32.0"); kosong = ikuti versi terbaru. */
    public static final String KEY_BIN_PILIH = "bin_pin_version";
    /** Versi web-vault yang dikunci user (mis. "1.32.0"); kosong = ikuti versi terbaru. */
    public static final String KEY_WV_PILIH = "wv_pin_version";
    /** Kapan unduh binary terakhir gagal (elapsedRealtime ms); 0 = belum pernah.
     *  Perangkat-lokal saja: jangan ikut export/import config. */
    public static final String KEY_BIN_DL_GAGAL_AT = "bin_dl_gagal_at";
    /** Jeda coba-ulang unduh perbaikan bila cache valid masih ada (hemat kuota). */
    static final long TUNDA_ULANG_UNDUH_MS = 6L * 60 * 60 * 1000;

    /** Throttle hint [login]: jeda antar hint agar brute-force tak membanjiri log (60 dtk). */
    private static volatile long loginHintTerakhirElapsed = 0;
    static final long LOGIN_HINT_JEDA_MS = 60 * 1000;

    /** True bila admin token aman dipasang ke env (tanpa spasi/kontrol/baris baru).
     *  Murni agar bisa unit test. */
    static boolean tokenAdminValid(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c <= ' ' || c == 127) {
                return false;
            }
        }
        return true;
    }

    /** True bila binary rilis tersimpan berasal dari patch lama dan wajib diunduh ulang. */
    static boolean perluRefreshPatch(String tersimpan) {
        return tersimpan == null || !tersimpan.equals(String.valueOf(BIN_PATCH_REV));
    }

    /** True bila unduh boleh dicoba lagi (belum pernah gagal / jeda terlampaui).
     *  Murni agar bisa unit test. */
    static boolean bolehCobaUnduhLagi(long gagalAt, long sekarang) {
        if (gagalAt <= 0) {
            return true;
        }
        if (gagalAt > sekarang) {
            // Jam mundur (reboot me-reset elapsedRealtime): cap basi,
            // izinkan coba lagi daripada menahan unduhan 6 jam.
            return true;
        }
        return sekarang - gagalAt >= TUNDA_ULANG_UNDUH_MS;
    }

    /** Sidik SHA-256 hex dari token admin ("" bila kosong/gagal; murni).
     *  Dipakai membandingkan token prefs vs saat start tanpa menyimpan plaintext.
     *  Hex manual via Updater.toHex: String.format per byte 32x per tick UI
     *  bikin jank di STB lama (dipanggil tiap refresh 500 ms). */
    /** Cache sidik token: refresh UI tiap detik tak perlu hash ulang bila
     *  token tak berubah. Kunci sendiri agar commit() PIN tak menahannya. */
    private static final Object KUNCI_SIDIK = new Object();
    private static String sidikTokenMasuk = null;
    private static String sidikTokenHasil = "";

    static String sidikTokenAdmin(String token) {
        String bersih = token == null ? "" : token.trim();
        if (bersih.isEmpty()) {
            return "";
        }
        synchronized (KUNCI_SIDIK) {
            if (bersih.equals(sidikTokenMasuk)) {
                return sidikTokenHasil;
            }
        }
        String hasil;
        try {
            byte[] h = Util.mdSha256().digest(bersih.getBytes(StandardCharsets.UTF_8));
            hasil = Updater.toHex(h);
        } catch (Exception e) {
            return "";
        }
        synchronized (KUNCI_SIDIK) {
            sidikTokenMasuk = bersih;
            sidikTokenHasil = hasil;
        }
        return hasil;
    }

    /** Potong pesan galat di batas code-point (murni). Belah pasangan surrogate
     *  menghasilkan lone surrogate (tofu di layar / 400 Telegram). */
    static String potongPesanGalat(String msg, int maks) {
        if (maks <= 0) {
            return "";
        }
        if (msg == null || msg.length() <= maks) {
            return msg == null ? "" : msg;
        }
        String potong = msg.substring(0, maks);
        if (Character.isHighSurrogate(potong.charAt(potong.length() - 1))) {
            potong = potong.substring(0, potong.length() - 1);
        }
        return potong;
    }

    /** Kuncian versi binary user ("" = ikuti terbaru). Murni prefs. */
    static String pinBinaryTersimpan(SharedPreferences sp) {
        try {
            String pin = Updater.normalisasiPinVersi(
                    sp == null ? null : TgBackup.amanString(sp, KEY_BIN_PILIH, ""));
            return pin == null ? "" : pin;
        } catch (Exception e) {
            return "";
        }
    }

    /** Pola statis: split/matches kompilasi regex tiap panggil di STB lama. */
    private static final java.util.regex.Pattern POLA_SLASH =
            java.util.regex.Pattern.compile("/");
    private static final java.util.regex.Pattern POLA_SPASI =
            java.util.regex.Pattern.compile("\\s+");

    /** True bila s hanya digit ASCII (tanpa kompilasi regex matches). */
    static boolean semuaDigit(String s) {
        return Util.semuaDigit(s);
    }

    private static final java.util.regex.Pattern POLA_SUFIKS_VERSI =
            java.util.regex.Pattern.compile("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?");

    /** True bila binary cache (hasil smoke test di binaryVersion) boleh dipakai:
     *  tanpa kuncian selalu boleh; bila dikunci wajib sama dengan kuncian
     *  termasuk sufiks prerelease ("1.37.3-beta" beda dengan "1.37.3"):
     *  bandingVersi hanya banding angka sehingga tanpa ini pin beta lolos
     *  diam-diam di cache stabil, padahal jalur unduh menolaknya lantang.
     *  Murni agar bisa unit test. */
    static boolean cacheSesuaiPin(String pin, String terdeteksi) {
        if (pin == null || pin.isEmpty()) {
            return true;
        }
        String real = Updater.parseBinaryVersion(terdeteksi);
        if (real == null || Updater.bandingVersi(real, pin) != 0) {
            return false;
        }
        return sufiksPrerelease(terdeteksi).equalsIgnoreCase(sufiksPrerelease(pin));
    }

    /** Sufiks prerelease versi ("-beta" dari "1.37.3-beta"); "" bila stabil
     *  atau tak terpola. Murni agar bisa unit test. */
    static String sufiksPrerelease(String v) {
        if (v == null) {
            return "";
        }
        java.util.regex.Matcher m = POLA_SUFIKS_VERSI.matcher(v.trim());
        if (!m.find() || m.group(1) == null) {
            return "";
        }
        return m.group(1);
    }

    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "vaultwarden_server";
    private static final int MAX_LOG_CHARS = 300_000;
    // Histeresis trim: pangkas hanya bila melampaui batas + 32 KB agar memmove
    // O(n) tidak terjadi terlalu sering saat log deras.
    private static final int LOG_TRIM_THRESHOLD = MAX_LOG_CHARS + 32 * 1024;
    private static final long MAX_LOG_FILE = 2L * 1024 * 1024;
    private static final long HEALTH_INTERVAL_MS = 2 * 60 * 1000;
    private static final long HEALTH_FAST_MS = 5 * 60 * 1000;
    private static final long HEALTH_FAST_INTERVAL_MS = 30 * 1000;
    /** Stempel jam log per thread: SimpleDateFormat tak thread-safe sehingga
     *  instance statis bersama butuh synchronized di tiap baris log (jalur
     *  panas thread pump output); ThreadLocal menghapus kunci itu. */
    private static final ThreadLocal<SimpleDateFormat> LOG_TS =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
                }
            };

    public static volatile boolean running = false;
    public static volatile String statusLine = "Stopped";
    public static volatile String binaryVersion = "";
    public static final StringBuilder logBuffer = new StringBuilder();
    /** Versi log naik tiap baris/trim/clear: kunci refresh UI tanpa adu panjang. */
    private static volatile long logVer = 0;

    /** Snapshot konfigurasi server yang sedang berjalan (untuk peringatan restart). */
    public static volatile String runningDataDir = "";
    public static volatile String runningPort = "";
    public static volatile boolean runningHttps = false;
    /** Sidik SHA-256 token admin saat server start (untuk hint restart).
     *  Sengaja bukan plaintext agar rahasia tak mengendap di heap statis;
     *  bandingkan via sidikTokenAdmin(). */
    public static volatile String runningAdminToken = "";
    /** Penanda versi web-vault saat server start (untuk hint restart di MainActivity). */
    public static volatile String runningWvFrom = "";
    /** IP LAN saat server start (untuk DOMAIN); dibandingkan tiap health check
     *  agar DHCP/WiFi yang berubah ketahuan tanpa restart membabi-buta. */
    public static volatile String runningLanHost = "";
    /** IP perubahan terakhir yang sudah diperingatkan (anti-spam Telegram). */
    private static volatile String ipBerubahDiperingatkan = "";
    /** Kandidat IP baru + hitungan tick beruntun: restart otomatis hanya bila
     *  IP baru stabil 2x health tick agar IP flapping DHCP tak memicu loop restart. */
    private static volatile String ipBaruKandidat = "";
    private static volatile int ipBaruHitung = 0;

    private static final long[] RESTART_DELAYS = {2000, 5000, 10000, 20000, 40000};
    // Anti-loop: berhenti total bila restart beruntun ≥3x dalam 5 menit.
    private static final long RESTART_WINDOW_MS = 5 * 60 * 1000L;
    private static final int RESTART_WINDOW_MAX = 3;
    private static final int RESTART_HISTORY_MAX = 10;
    private static final String CRASH_LOG_NAME = "crash-last.log";
    private static final List<Long> RESTART_TIMES = new ArrayList<>();
    private static final List<String> RESTART_REASONS = new ArrayList<>();

    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private static volatile Process process;
    /** Proses yang diminta berhenti sengaja (per-proses, bukan boolean global):
     *  watchProcess hanya mengabaikan exit milik proses ini agar Stop lalu Start cepat
     *  tak salah menandai crash proses baru sebagai stop sengaja. */
    private static volatile Process prosesStopDisengaja = null;
    /** Lolos TCP beruntun saat /alive gagal tapi port tersambung (anti livelock health).
     *  Statis agar selamat dari recreate instance (selaras restartAttempt/healthFails). */
    private static final java.util.concurrent.atomic.AtomicInteger healthTcpLolos =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** True bila Stop ditekan saat start masih persiapan (unduh binary):
     *  start dibatalkan tepat sebelum exec agar server tak jalan tanpa diminta. */
    private static volatile boolean batalStart = false;
    private static final java.util.concurrent.atomic.AtomicBoolean starting = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** Statis agar recreate transien tak memegang dua kunci 12 jam (bocor baterai). */
    private static PowerManager.WakeLock wakeLock;
    private static volatile File logFile;
    /** Ditulis thread watch/health, dibaca UI thread (restartTunda): volatile agar
     *  stop fatal tak dibaca basi lalu restart jalan tanpa diminta.
     *  Statis agar selamat dari recreate instance service oleh sistem
     *  (batas 5x di restartAttempt juga statis). */
    private static volatile boolean autoRestart = false;
    /** Hitungan restart beruntun: statis agar tetap berlaku bila sistem membuat
     *  ulang instance service (batas 5x tak bisa di-reset oleh recreate).
     *  AtomicInteger karena scheduleRestart dipanggil dari thread watch proses
     *  dan thread health/main bersamaan. */
    private static final java.util.concurrent.atomic.AtomicInteger restartAttempt =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** Kunci statis pendamping restartAttempt (sinkron instance tak
     *  melindungi antar instance hasil recreate). */
    private static final Object KUNCI_RESTART = new Object();
    private static volatile long lastStartTime = 0;
    /** Jangkar monotonik start (elapsedRealtime); wall-clock bisa mundur. */
    private static volatile long lastStartElapsed = 0;
    /** Statis agar hitungan gagal tak di-reset recreate sistem (selaras restartAttempt). */
    private static final java.util.concurrent.atomic.AtomicInteger healthFails = new java.util.concurrent.atomic.AtomicInteger(0);
    /** Episode beruntun "server melayani tapi DB rusak" (/alive 5xx + /api/config 200):
     *  pingRinci() menganggapnya sehat sehingga DB korup tak pernah restart.
     *  Dihitung terpisah dari healthFails agar 500 sesaat (backup/migrasi di
     *  STB lambat) tak langsung membunuh server. */
    /** Statis agar episode DB-rusak tak di-reset recreate (selaras healthFails). */
    private static final java.util.concurrent.atomic.AtomicInteger configSajaBeruntun =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** Batas episode config-saja sebelum dianggap gantung lalu restart: 10x tick
     *  (~5 mnt mode cepat / ~20 mnt mode normal) — jauh di atas 500 sesaat. */
    static final int BATAS_CONFIG_SAJA_BERUNTUN = 10;

    /** Statis seperti autoRestart: recreate instance tak boleh mematikan
     *  health-check diam-diam. */
    private static volatile boolean healthActive = false;
    /** Cek health yang sedang jalan: tiap tick hanya satu (timeout total
     *  worst-case 32 dtk > interval cepat 30 dtk sehingga thread bisa
     *  menumpuk bila server macet). */
    /** Statis agar dua instance hasil recreate tak cek health bersamaan. */
    private static final java.util.concurrent.atomic.AtomicBoolean healthBerjalan =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Runnable healthTick = new Runnable() {
        @Override
        public void run() {
            // Jangan repost bila service sudah berhenti atau auto-restart mati
            // (gagal 5x / Stop): cegah polling tiap 2 menit selamanya.
            if (!healthActive || !autoRestart) {
                return;
            }
            jagaWakeLock();
            // Adaptif: tiap 30 detik di 5 menit pertama (crash dini cepat
            // ketahuan), lalu tiap 2 menit setelah server stabil.
            long delay = HEALTH_INTERVAL_MS;
            long up = SystemClock.elapsedRealtime() - lastStartElapsed;
            if (lastStartElapsed > 0 && up < HEALTH_FAST_MS) {
                delay = HEALTH_FAST_INTERVAL_MS;
            }
            if (process == null || !alive(process) || !running) {
                // Proses mati ditangani restartTunda; tetap jadwalkan tick
                // berikut sekali saja (bukan sebelum cek agar tak ganda).
                mainHandler.postDelayed(this, delay);
                return;
            }
            if (!healthBerjalan.compareAndSet(false, true)) {
                mainHandler.postDelayed(this, delay);
                return;
            }
            mainHandler.postDelayed(this, delay);
            // Pool bersama (bukan new Thread per tick): hemat stack 1 MB tiap 30 dtk di STB.
            Util.jalankanBg(() -> {
                try {
                    checkHealthOnce();
                } finally {
                    healthBerjalan.set(false);
                }
            });
        }
    };

    public static boolean start(Context context) {
        return mulaiService(context, ACTION_START);
    }

    /** Start service tahan penolakan background Android 12+
     *  (ForegroundServiceStartNotAllowedException): catat ke log, jangan crash
     *  pemanggil (bot/receiver sudah memberi tahu user secara terpisah). */
    /** commit() di bawah disengaja (sinkron anti-hilang, lihat komentar) — bukan apply(). */
    @SuppressLint("ApplySharedPref")
    private static boolean mulaiService(Context context, String aksi) {
        try {
            Intent i = new Intent(context, ServerService.class).setAction(aksi);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
            return true;
        } catch (Exception e) {
            catatLog("[app] Gagal start service (" + aksi + "): " + e);
            if (ACTION_START.equals(aksi)) {
                // Android 12+ menolak start dari background: tandai agar
                // MainActivity menjalankan susulan saat dibuka berikutnya.
                // commit() sinkron (bukan apply()): flag daya-tahan wajib awet
                // di disk sebelum reboot/kill (lihat BootReceiver).
                try {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean(KEY_START_TERTUNDA, true).commit();
                } catch (Exception ignored) {
                }
            }
            return false;
        }
    }

    public static boolean stop(Context context) {
        return mulaiAksi(context, ACTION_STOP);
    }

    /** Restart proses server tanpa mematikan service (dipakai dari perintah bot). */
    public static boolean restart(Context context) {
        return mulaiAksi(context, ACTION_RESTART);
    }

    /** Flag susulan auto-start: start dari background ditolak sistem
     *  (Android 12+). MainActivity mengeksekusinya saat dibuka berikutnya. */
    public static final String KEY_START_TERTUNDA = "auto_start_tertunda";

    /** Kirim aksi ke service lewat foreground API di Android 8+ agar tidak
     *  IllegalStateException saat dipanggil dari background (bot/alarm).
     *  Return false bila sistem menolak (pemanggil wajib fallback/susulan,
     *  bukan mengandalkan try/catch: penolakan sudah ditangkap di dalam). */
    private static boolean mulaiAksi(Context context, String aksi) {
        return mulaiService(context, aksi);
    }

    /** Jalankan backup Telegram terjadwal (via AlarmReceiver).
     *  Return false bila sistem menolak start background (AlarmReceiver
     *  wajib menandai susulan agar backup tak hilang diam-diam). */
    public static boolean backupNow(Context context) {
        return mulaiService(context, ACTION_TG_BACKUP);
    }

    /** Apakah proses vaultwarden masih hidup (dipakai sebelum restore DB). */
    public static boolean isProcessAlive() {
        Process p = process;
        return alive(p);
    }

    private static boolean isStopDisengaja(Process p) {
        return p != null && p == prosesStopDisengaja;
    }

    private static void tandaiStopDisengaja(Process p) {
        if (p != null) {
            prosesStopDisengaja = p;
        }
    }

    private static void hapusTandaStop(Process p) {
        if (p != null && prosesStopDisengaja == p) {
            prosesStopDisengaja = null;
        }
    }

    /** Ganti file atomik via cadangan: tanpa jendela tanpa-binary bila crash di tengah. */
    static void gantiAtomik(File tmp, File out) throws java.io.IOException {
        if (tmp == null || out == null || out.getParentFile() == null) {
            throw new java.io.IOException("Path binary tidak valid.");
        }
        File bak = new File(out.getParentFile(), out.getName() + ".bak");
        try {
            if (bak.exists() && !bak.delete()) {
                bak.delete();
            }
        } catch (Exception ignored) {
        }
        if (out.exists() && !out.renameTo(bak)) {
            throw new java.io.IOException("Gagal mencadangkan binary lama.");
        }
        boolean ok = false;
        try {
            // fsync isi file dulu (best-effort): rename hanya mengawetkan nama
            // di direktori; tanpa ini isi binary bisa nol/terpotong bila STB
            // mati tepat sesudah pasang (FAT). getFD().sync() ada sejak API 1.
            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(tmp);
                try {
                    fis.getFD().sync();
                } finally {
                    try {
                        fis.close();
                    } catch (Exception ignored2) {
                    }
                }
            } catch (Exception ignored) {
            }
            if (!tmp.renameTo(out)) {
                throw new java.io.IOException("Gagal memasang binary.");
            }
            ok = true;
        } finally {
            if (!ok && bak.exists()) {
                try {
                    if (!out.exists()) {
                        bak.renameTo(out);
                    }
                } catch (Exception ignored) {
                }
            } else if (ok) {
                try {
                    if (bak.exists() && !bak.delete()) {
                        bak.delete();
                    }
                } catch (Exception ignored) {
                }
                // Best-effort: fsync direktori agar rename awet bila STB mati
                // tepat sesudah pasang binary (tanpa ini file bisa hilang di FAT).
                // Os.open ada sejak API 21 (minSdk repo ini) sehingga tanpa cek versi.
                try {
                    java.io.File dir = out.getParentFile();
                    if (dir != null) {
                        java.io.FileDescriptor fd = android.system.Os.open(
                                dir.getAbsolutePath(),
                                android.system.OsConstants.O_RDONLY, 0);
                        try {
                            android.system.Os.fsync(fd);
                        } finally {
                            try {
                                android.system.Os.close(fd);
                            } catch (Exception ignored2) {
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Process.isAlive() baru ada di API 26; fallback exitValue() untuk Android 5/6. */
    private static boolean alive(Process p) {
        if (p == null) {
            return false;
        }
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /** Kosongkan buffer log (in-memory) dan hapus file log di folder data. */
    public static void clearLog() {
        synchronized (logBuffer) {
            logBuffer.setLength(0);
            logVer++;
        }
        File f = logFile;
        if (f != null) {
            f.delete();
        }
    }

    /** Lama server sudah berjalan (ms, jam monotonik); 0 bila sedang berhenti. */
    public static long uptimeMs() {
        if (!running) {
            return 0;
        }
        long t = lastStartElapsed;
        return t == 0 ? 0 : Math.max(0, SystemClock.elapsedRealtime() - t);
    }

    /** Cek sehat sekali tanpa efek samping; true bila HTTPS 200 di /alive atau /api/config. */
    public static boolean pingAlive(Context ctx) {
        return pingRinci(ctx).sehat;
    }

    /** Hasil cek sehat rinci (untuk log diagnosa tanpa bocor token). */
    static final class HasilPing {
        final boolean sehat;
        final int aliveCode;
        final int configCode;
        final String rincian;
        HasilPing(boolean sehat, int aliveCode, int configCode, String rincian) {
            this.sehat = sehat;
            this.aliveCode = aliveCode;
            this.configCode = configCode;
            this.rincian = rincian;
        }
    }

    /** Sehat bila /alive atau /api/config balas 200 (murni agar bisa diuji).
     *  /alive butuh DB (DbConn) sehingga di STB lambat bisa timeout/500
     *  sementara /api/config tetap 200 — server seperti itu tetap sehat. */
    static boolean sehatDariKode(int aliveCode, int configCode) {
        return aliveCode == 200 || configCode == 200;
    }

    /** True bila server melayani tapi DB rusak: /alive galat server (5xx) sementara
     *  /api/config 200. Murni agar bisa unit test. */
    static boolean aliveRusakTapiConfigSehat(int aliveCode, int configCode) {
        return aliveCode >= 500 && aliveCode < 600 && configCode == 200;
    }

    /** Cek berurutan /alive lalu /api/config; sehat bila salah satu 200. */
    static HasilPing pingRinci(Context ctx) {
        boolean https = true;
        String port;
        try {
            if (!runningPort.isEmpty()) {
                port = runningPort;
            } else {
                SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                port = effectivePort(sp);
            }
        } catch (Exception e) {
            return new HasilPing(false, -1, -1, "baca prefs gagal");
        }
        String scheme = "https";
        String p = port == null ? "" : port.trim();
        // Gerbang TCP murah dulu: server mati tak membayar 2x handshake TLS
        // (2x4 dtk) tiap tick di CPU ARMv7 lambat; cukup 1x TCP ~3 dtk.
        try {
            int pn = Integer.parseInt(p);
            if (!tcpTersambung(pn)) {
                return new HasilPing(false, -1, -1, "port tertutup");
            }
        } catch (Exception ignored) {
        }
        HasilCoba alive = cobaKode(ctx, scheme, p, "/alive", https);
        HasilCoba config = new HasilCoba(-1, "");
        // /alive butuh DB; fallback ringan /api/config memastikan server
        // yang masih melayani tidak dibunuh sia-sia (kasus log: config 200).
        if (alive.kode != 200) {
            config = cobaKode(ctx, scheme, p, "/api/config", https);
        } else {
            config = new HasilCoba(-2, "");
        }
        boolean sehat = sehatDariKode(alive.kode, config.kode);
        String rincian;
        if (sehat) {
            rincian = alive.kode == 200 ? "alive 200" : "config 200 (alive " + ringkasKode(alive.kode, alive.galat) + ")";
        } else {
            rincian = "alive " + ringkasKode(alive.kode, alive.galat)
                    + ", config " + ringkasKode(config.kode, config.galat);
        }
        return new HasilPing(sehat, alive.kode, config.kode, rincian);
    }

    /** Hasil satu request GET loopback (kode + pesan galat lokal tanpa field
     *  statis bersama agar panggilan konkuren health/bot tak tukar pesan error). */
    static final class HasilCoba {
        final int kode;
        final String galat;
        HasilCoba(int kode, String galat) {
            this.kode = kode;
            this.galat = galat == null ? "" : galat;
        }
    }

    /** Satu request GET loopback; balas kode HTTP atau -1 bila gagal jaring/TLS. */
    private static HasilCoba cobaKode(Context ctx, String scheme, String port, String path, boolean https) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(
                    scheme + "://127.0.0.1:" + port + path).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            if (https) {
                HttpsURLConnection hc = (HttpsURLConnection) c;
                hc.setSSLSocketFactory(loopbackSslFactory(ctx));
                hc.setHostnameVerifier((host, session) ->
                        "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
            }
            return new HasilCoba(c.getResponseCode(), "");
        } catch (Exception e) {
            String m = e.getClass().getSimpleName();
            String msg = e.getMessage();
            msg = potongPesanGalat(msg == null ? "" : msg, 80);
            String galat = msg == null || msg.isEmpty() ? m : m + ": " + msg;
            return new HasilCoba(-1, galat);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** Ringkas kode + pesan error untuk log (tanpa token, tanpa stacktrace). Murni. */
    static String ringkasKode(int code, String err) {
        if (code > 0) {
            return String.valueOf(code);
        }
        if (code == -2) {
            return "dilewati";
        }
        return err == null || err.isEmpty() ? "tak tersambung" : "tak tersambung (" + err + ")";
    }

    /** TCP loopback ke port utama; true bila port masih menerima koneksi. */
    static boolean tcpTersambung(int portNum) {
        if (portNum < 1 || portNum > 65535) {
            return false;
        }
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new InetSocketAddress("127.0.0.1", portNum), 3000);
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Port loopback yang sedang dipakai (running dulu, fallback prefs). */
    int portLoopback() {
        try {
            String p = !runningPort.isEmpty() ? runningPort
                    : effectivePort(getSharedPreferences(PREFS, MODE_PRIVATE));
            return Integer.parseInt(p.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Stop server dan tunggu proses benar-benar mati.
     *  True bila proses sudah mati; false bila masih hidup (pemanggil wajib
     *  membatalkan operasi file DB agar SQLite tidak korup).
     *  @WorkerThread — jangan panggil dari UI thread (ada sleep di dalam). */
    public static boolean stopAndWait(Context context, long timeoutMs) {
        // Guard: sleep di UI thread = ANR; batalkan agar pemanggil sadar salah thread.
        // Aman di JVM unit test (stub Looper melempar): anggap bukan UI.
        try {
            android.os.Looper ui = android.os.Looper.getMainLooper();
            if (ui != null && android.os.Looper.myLooper() == ui) {
                catatLog("[server] stopAndWait di UI thread, batal");
                return false;
            }
        } catch (Exception ignored) {
        }
        if (!isProcessAlive()) {
            return true;
        }
        stop(context);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline && isProcessAlive()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isProcessAlive();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            TgBackup.healkanStringPrefs(this);
            migrasiPortSekali(getSharedPreferences(PREFS, MODE_PRIVATE));
        } catch (Exception ignored) {
        }
        createChannel();
        // Keraskan hash PIN legasi di rest (tanpa menunggu buka activity):
        // pemakaian bot-only/boot tak pernah menyentuh MainActivity sehingga
        // SHA-256 tanpa salt bertahan selamanya. Worker thread (PBKDF2 30k).
        try {
            final android.content.Context appPin = getApplicationContext();
            Util.jalankanBg(() -> PinGate.kuatkanHashDini(appPin));
        } catch (Exception ignored) {
        }
        // Recreate transien oleh sistem tanpa intent baru: pasang ulang
        // health-check/wakelock/restart bila server masih diminta jalan.
        // Tanpa ini monitoring berhenti diam-diam selagi binary masih hidup.
        try {
            if (running && autoRestart && healthActive) {
                // Wajib foreground seperti action lain: tanpa ini service hasil
                // recreate bekerja (health/wakelock) tanpa notifikasi dan bisa
                // dibunuh sistem sebagai background.
                try {
                    startForegroundCompat();
                } catch (Exception ignoredFg) {
                }
                if (isProcessAlive()) {
                    jagaWakeLock();
                    mainHandler.removeCallbacks(healthTick);
                    mainHandler.postDelayed(healthTick, HEALTH_FAST_INTERVAL_MS);
                } else if (running) {
                    scheduleRestart();
                    mainHandler.removeCallbacks(healthTick);
                    mainHandler.postDelayed(healthTick, HEALTH_FAST_INTERVAL_MS);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            // Foreground dulu seperti action lain: STOP datang via
            // startForegroundService sehingga wajib startForeground() dalam
            // ~5 dtk; stopper di bawah menunggu proses sampai ~9 dtk.
            try {
                startForegroundCompat();
            } catch (Exception ignoredFg) {
            }
            autoRestart = false;
            healthActive = false;
            mainHandler.removeCallbacks(healthTick);
            mainHandler.removeCallbacks(restartTunda);
            batalStart = true;
            stopServer();
            // stopper di stopServer() butuh hingga ~8 dtk (destroy + destroyForcibly):
            // stopForeground/stopSelf langsung di sini mematikan service sebelum
            // proses mati sehingga watcher bocor dan restore mengira DB bebas.
            // Tunda sampai proses benar-benar mati, maks ~9 dtk.
            final int stopId = startId;
            // Pool lama (bukan thread baru): tunggu ~9 dtk tanpa thread baru per Stop.
            Util.jalankanLama(() -> {
                try {
                    long tenggat = SystemClock.elapsedRealtime() + 9000;
                    while (SystemClock.elapsedRealtime() < tenggat && isProcessAlive()) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        stopForeground(true);
                    } catch (Exception ignored) {
                    }
                    try {
                        stopSelf(stopId);
                    } catch (Exception ignored) {
                    }
                }
            });
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            autoRestart = true;
            batalStart = false;
            startForegroundCompat();
            // Pool lama agar restart bot tak menambah thread saat Start jalan.
            Util.jalankanLama(() -> {
                appendLog("[app] Restart diminta via Telegram.");
                // Jangan menyalakan server yang sedang Stop: restart hanya sah
                // saat server berjalan. Tanpa guard ini /restart menyalakan
                // server yang sengaja di-Stop user (lihat TgBot "/restart").
                if (!running && !alive(process)) {
                    appendLog("[app] Restart dilewati: server tidak berjalan.");
                    return;
                }
                if (process != null) {
                    final Process p = process;
                    tandaiStopDisengaja(p);
                    running = false;
                    releaseWakeLock();
                    p.destroy();
                    try {
                        if (!waitForOrKill(p, 5000)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                p.destroyForcibly();
                            } else {
                                p.destroy();
                            }
                            try {
                                waitForOrKill(p, 3000);
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (process == p) {
                            process = null;
                        }
                        hapusTandaStop(p);
                    }
                }
                // Lewat konteks aplikasi + aksi START agar instance hidup yang
                // mengeksekusi, bukan runnable berkonteks instance mati ini.
                // start() statis dan aman-thread sehingga fallback langsung
                // dipakai bila Looper utama tak tersedia (praktis tak terjadi
                // di perangkat, tapi tanpa guard ini restart hilang + crash).
                final android.content.Context appCtx = getApplicationContext();
                try {
                    android.os.Looper ui = android.os.Looper.getMainLooper();
                    if (ui == null) {
                        if (autoRestart) {
                            ServerService.start(appCtx);
                        }
                    } else {
                        // Handler bersama (bukan new Handler per restart).
                        Util.postUtama(() -> {
                            if (autoRestart) {
                                ServerService.start(appCtx);
                            }
                        });
                    }
                } catch (Exception ignored) {
                    try {
                        if (autoRestart) {
                            ServerService.start(appCtx);
                        }
                    } catch (Exception ignored2) {
                    }
                }
            });
            return START_NOT_STICKY;
        }
        if (ACTION_TG_BACKUP.equals(action)) {
            startForegroundCompat();
            // Pool lama (backup menit-lama tak monopoli / tak bikin thread baru).
            Util.jalankanLama(() -> {
                try {
                    SharedPreferences cek = getSharedPreferences(PREFS, MODE_PRIVATE);
                    if (!TgBackup.amanBoolean(cek, TgBackup.KEY_TG_AUTO, false)) {
                        // Alarm basi/duplikat yang terlanjur terjadwal tak boleh
                        // mengunggah setelah user mematikan auto-backup.
                        appendLog("[tg] Backup terjadwal dilewati: auto-backup sudah dimatikan.");
                        return;
                    }
                    long last = TgBackup.amanLong(cek, TgBackup.KEY_TG_LAST, 0);
                    if (last > 0 && !TgBackup.sudahGantiHari(last, System.currentTimeMillis())) {
                        appendLog("[tg] Backup hari ini sudah ada - terjadwal dilewati.");
                    } else if (!TgBackup.bolehBackupOtomatis(
                            TgBackup.amanString(cek, TgBackup.KEY_TG_PASS, ""))) {
                        String tolak = TgBackup.pesanTolakPlainOtomatis();
                        appendLog("[tg] " + tolak);
                        TgBackup.sendMessage(this, "Backup otomatis GAGAL: " + tolak);
                    } else {
                        try {
                            TgBackup.tungguBootStabil();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        // Terjadwal = otomatis: wajib terenkripsi (fail-closed).
                        String msg = TgBackup.backupOtomatis(this);
                        appendLog("[tg] " + msg);
                        TgBackup.sendMessage(this, "Backup otomatis: " + msg);
                    }
                } catch (Exception e) {
                    String ramah = TgBackup.pesanGalatBackup(e);
                    appendLog("[tg] " + ramah + " (" + e + ")");
                    TgBackup.sendMessage(this, "Backup otomatis GAGAL: " + ramah);
                } finally {
                    // Jadwalkan ulang ke tengah malam berikutnya, selama masih aktif.
                    // Baca tahan korup: getBoolean mentah melempar di finally
                    // sehingga stopForeground/stopSelf di bawah tak jalan.
                    SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                    boolean autoAktif = TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false);
                    TgBackup.schedule(this, autoAktif);
                    // Service yang dimatikan di sini memaksa alarm berikutnya
                    // startForegroundService dari background (Android 12+ bisa
                    // ditolak). Pertahankan hidup bila server jalan atau
                    // auto-backup masih aktif; hanya berhenti total bila idle.
                    boolean serverJalan = process != null && alive(process);
                    if (!serverJalan && !autoAktif) {
                        stopForeground(true);
                        stopSelf();
                    } else if (!serverJalan) {
                        // Demote foreground di sini (stopForeground(false)) berisiko:
                        // service kehilangan notifikasi tapi tetap hidup tanpa alasan.
                        // Pilih stopSelf(): aman karena alarm berikutnya start ulang bila perlu.
                        try {
                            stopSelf();
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
            return START_NOT_STICKY;
        }
        // Aksi tak dikenal (termasuk intent null dari sistem) bukan perintah start:
        // abaikan agar service tak menyala sendiri tanpa persetujuan user.
        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }
        autoRestart = true;
        batalStart = false;
        healthActive = true;
        startForegroundCompat();
        if (process == null || !alive(process)) {
            startServerAsync();
        }
        mainHandler.removeCallbacks(healthTick);
        mainHandler.postDelayed(healthTick, HEALTH_FAST_INTERVAL_MS);
        // ACTION_START wajib STICKY agar sistem menghidupkan ulang service bila
        // dibunuh saat server diminta jalan; aksi lain tetap NOT_STICKY.
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Konstruktor Builder tanpa channel sengaja untuk pra-Oreo (API 21-25).
    @SuppressWarnings("deprecation")
    private void startForegroundCompat() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        boolean jalan = running || alive(process);
        Notification n = b.setContentTitle("Vaultwarden Host")
                .setContentText(jalan ? "Server aktif di port " + currentPort()
                        : "Menjalankan tugas latar...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        startForeground(NOTIF_ID, n);
    }

    /** True bila folder data aman dipakai: absolut, kanonis sederhana, di luar area sistem. Murni. */
    public static boolean dataDirAman(String d) {
        if (d == null) {
            return false;
        }
        String t = d.trim();
        if (t.isEmpty() || !t.startsWith("/")) {
            return false;
        }
        if (t.contains("\"") || t.contains("\n") || t.contains("\r") || t.contains("\0")) {
            return false;
        }
        // Karakter kontrol/format tak terlihat (RTL override U+202E dkk.)
        // membuat nama folder menipu di UI; tolak eksplisit.
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 || c == 0x7F || c == 0xFEFF
                    || (c >= 0x200B && c <= 0x200F)
                    || (c >= 0x202A && c <= 0x202E)
                    || (c >= 0x2066 && c <= 0x2069)) {
                return false;
            }
        }
        // Tolak traversal per segmen agar /sdcard/../data tak lolos, tapi
        // folder sah bernama "my..folder" tetap diterima.
        if (t.contains("//") || t.contains("\\")) {
            return false;
        }
        // Koma/kurawal merusak parse ROCKET_TLS ({certs="...",key="..."})
        // sehingga folder data yang memuatnya ditolak dengan pesan jelas.
        if (t.contains(",") || t.contains("{") || t.contains("}")) {
            return false;
        }
        // Batas panjang: path raksasa (ketikan/ekspor rusak) membuat mkdirs gagal
        // misterius dan pesan /status Telegram jebol >4096 char (gagal 400 diam-diam).
        if (t.length() > 512) {
            return false;
        }
        for (String segmen : POLA_SLASH.split(t)) {
            if (segmen.equals("..") || segmen.equals(".")) {
                return false;
            }
            // Segmen >255 byte tak bisa dibuat di ext4/f2fs (ENAMETOOLONG):
            // hitung byte UTF-8, bukan char (200 emoji = 800 byte lolos cek char).
            if (segmen.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255) {
                return false;
            }
        }
        // Kupas slash akhir agar /data/ tak lolos dari cek persis.
        String n = t;
        while (n.length() > 1 && n.endsWith("/")) {
            n = n.substring(0, n.length() - 1);
        }
        if (n.equals("/") || n.equals("/system") || n.equals("/data") || n.equals("/vendor")
                || n.equals("/proc") || n.equals("/sys") || n.equals("/dev")) {
            return false;
        }
        // Tolak seluruh isi area sistem; kecuali privat milik app
        // (/data/data dan /data/user yang merupakan path kanonis sama).
        if (n.startsWith("/system/") || n.startsWith("/vendor/")
                || n.startsWith("/proc/") || n.startsWith("/sys/")
                || n.startsWith("/dev/")) {
            return false;
        }
        if (n.equals("/data") || n.startsWith("/data/")) {
            String pkg = "com.tasirin.vaultwardenhost";
            boolean sendiri = n.equals("/data/data/" + pkg)
                    || n.startsWith("/data/data/" + pkg + "/");
            if (!sendiri && n.startsWith("/data/user/")) {
                String sisa = n.substring("/data/user/".length());
                int slash = sisa.indexOf('/');
                if (slash < 0) {
                    sendiri = sisa.equals(pkg);
                } else {
                    String seg0 = sisa.substring(0, slash);
                    String rest = sisa.substring(slash + 1);
                    if (seg0.equals(pkg)) {
                        sendiri = true;
                    } else if (semuaDigit(seg0)) {
                        sendiri = rest.equals(pkg) || rest.startsWith(pkg + "/");
                    }
                }
            }
            if (!sendiri) {
                return false;
            }
        }
        // Mount mentah tak boleh jadi folder data walau subfolder
        // (/mnt/media_rw/XXXX = FUSE mentah, /mnt/runtime/* = namespace,
        // /storage/self/* = symlink primer): tolak prefix, bukan exact saja.
        if (n.equals("/mnt/media_rw") || n.startsWith("/mnt/media_rw/")
                || n.equals("/mnt/runtime") || n.startsWith("/mnt/runtime/")
                || n.equals("/mnt/runtime_default") || n.startsWith("/mnt/runtime_default/")
                || n.equals("/storage/self") || n.startsWith("/storage/self/")) {
            return false;
        }
        // Folder data wajib subfolder (mis. /sdcard/vaultwarden): root storage
        // (/sdcard, /storage/emulated/0, /mnt/media_rw) membuat DB+web-vault+log
        // tercecer di root dan rawan terhapus.
        if (n.equals("/sdcard") || n.equals("/storage/emulated")
                || n.equals("/storage/emulated/0") || n.equals("/storage/self")
                || n.equals("/storage/sdcard0") || n.equals("/mnt/sdcard")
                || n.equals("/mnt/media_rw") || n.equals("/mnt/runtime")) {
            return false;
        }
        int segmenIsi = 0;
        for (String s : POLA_SLASH.split(n)) {
            if (!s.isEmpty()) {
                segmenIsi++;
            }
        }
        return segmenIsi >= 2;
    }

    /** Kembalikan folder data aman atau bawaan segar bila input berbahaya. Murni. */
    public static String amankanDataDir(String d) {
        return dataDirAman(d) ? d.trim() : dataDirBawaanSegar();
    }

    /** True bila path kanonis folder data lolos aturan yang sama. Murni I/O.
     *  Menutup celah symlink (/sdcard/vault -> /data/...) yang lolos cek string:
     *  symlink di-resolve dulu via getCanonicalPath baru dinilai. */
    static boolean dataDirKanonisAman(String d) {
        if (d == null) {
            return false;
        }
        try {
            return dataDirAman(new File(d.trim()).getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    /** Baca port tersimpan (murni, tanpa tulis disk agar aman dipanggil tiap detik UI).
     *  Migrasi 8080 -> default hanya lewat migrasiPortSekali() saat service dibuat.
     *  Nilai rusak (huruf/kosong/di luar 1024-65535) jatuh ke default agar health
     *  check tak membangun URL invalid lalu restart beruntun. Port privileged
     *  (<1024) butuh root dan selalu gagal bind di STB/HP tanpa root. */
    public static String effectivePort(SharedPreferences sp) {
        // Baca tahan korup: prefs korup di tengah jalan tak boleh
        // ClassCastException tiap detik UI/health (jatuh ke default).
        String p = null;
        try {
            p = sp == null ? null : TgBackup.amanString(sp, KEY_PORT, DEFAULT_PORT);
        } catch (Exception ignored) {
        }
        return normalisasiPort(p);
    }

    /** Port valid 1024-65535, selain itu pakai default (murni agar bisa diuji).
     *  Port privileged (<1024) butuh root: sebelumnya lolos lalu FATAL tiap
     *  Start. Kini jatuh ke default agar prefs lama berisi 80/443 tetap bisa start. */
    static String normalisasiPort(String p) {
        if (p != null) {
            try {
                int n = Integer.parseInt(p.trim());
                if (n >= 1024 && n <= 65535) {
                    return String.valueOf(n);
                }
            } catch (Exception ignored) {
            }
        }
        return DEFAULT_PORT;
    }

    /** Kutip path untuk nilai ROCKET_TLS (escape backslash + kutip). Murni. */
    static String kutipRocket(String path) {
        if (path == null) {
            return "";
        }
        return path.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Penanda migrasi port selesai (agar port 8080 pilihan user tak ditimpa). */
    public static final String KEY_PORT_MIGRATED = "port_migrated_8088";

    /** True bila migrasi 8080 -> default perlu jalan (murni, mudah diuji). */
    static boolean perluMigrasiPort(String tersimpan, boolean sudahMigrasi) {
        return !sudahMigrasi && "8080".equals(tersimpan == null ? null : tersimpan.trim());
    }

    /** Migrasi default lama 8080 -> default baru, sekali saja (dipanggil onCreate/start).
     *  Flag mencegah port 8080 yang disengaja user ikut tergusur di start berikutnya. */
    public static void migrasiPortSekali(SharedPreferences sp) {
        if (sp == null) {
            return;
        }
        try {
            if (perluMigrasiPort(TgBackup.amanString(sp, KEY_PORT, DEFAULT_PORT),
                    TgBackup.amanBoolean(sp, KEY_PORT_MIGRATED, false))) {
                sp.edit().putString(KEY_PORT, DEFAULT_PORT).apply();
            }
            sp.edit().putBoolean(KEY_PORT_MIGRATED, true).apply();
        } catch (Exception ignored) {
        }
    }

    private String currentPort() {
        return effectivePort(getSharedPreferences(PREFS, MODE_PRIVATE));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Vaultwarden server",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    /** Jalankan startServer di worker thread (unduh binary butuh jaringan;
     *  di main thread Android memblokir dengan NetworkOnMainThreadException).
     *  Guard mencegah Start ganda saat unduh binary masih berjalan. */
    private void startServerAsync() {
        if (!starting.compareAndSet(false, true)) {
            appendLog("[app] Start masih berjalan (unduh binary?), dilewati.");
            return;
        }
        // Pool lama (unduh binary menit-lama, bukan thread baru per Start).
        Util.jalankanLama(() -> {
            try {
                startServer();
            } finally {
                starting.set(false);
            }
        });
    }

    private void startServer() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            TgBackup.healkanStringPrefs(this);
        } catch (Exception ignored) {
        }
        String dataDir = TgBackup.amanString(sp, KEY_DATA_DIR, dataDirBawaanSegar());
        if (!dataDirAman(dataDir) || !dataDirKanonisAman(dataDir)) {
            dataDir = dataDirBawaanSegar();
            appendLog("[app] Folder data tidak valid, pakai bawaan: " + dataDir);
            sp.edit().putString(KEY_DATA_DIR, dataDir).apply();
        } else {
            dataDir = dataDir.trim();
        }
        String port = currentPort();
        // Transparansi fallback: currentPort() sudah menormalisasi (<1024/invalid -> default),
        // jadi bandingkan dengan nilai mentah agar user paham port 80/443 tak bisa dipakai tanpa root.
        try {
            String mentah = TgBackup.amanString(sp, KEY_PORT, DEFAULT_PORT);
            if (mentah != null && !port.equals(mentah.trim()) && !mentah.trim().isEmpty()) {
                appendLog("[app] Port '" + mentah.trim() + "' tidak valid/privileged - pakai default " + port
                        + ". Pakai port >= 1024 di Settings bila ingin port lain.");
            }
        } catch (Exception ignored) {
        }

        File dataFolder = new File(dataDir);
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            setStatus("Gagal membuat folder: " + dataDir);
            appendLog("[app] Gagal membuat folder data: " + dataDir);
            return;
        }
        // Symlink folder data yang ditukar sesudah cek awal (TOCTOU) membuat
        // unduhan web-vault/tls tertulis ke lokasi asing: kunci ke kanonis
        // dan pakai kanonis ke depannya, bukan string mentah prefs.
        try {
            String kanonAwal = dataFolder.getCanonicalPath();
            if (!dataDirAman(kanonAwal)) {
                throw new IOException("Folder data menunjuk lokasi tak valid.");
            }
            dataDir = kanonAwal;
            dataFolder = new File(kanonAwal);
            sp.edit().putString(KEY_DATA_DIR, dataDir).apply();
        } catch (IOException e) {
            setStatus("Folder data tidak valid - server TIDAK start.");
            appendLog("[app] FATAL: folder data berubah/tak valid sesudah dibuat;"
                    + " dibatalkan agar tak menulis ke folder asing.");
            return;
        } catch (Exception ignored) {
        }
        if (!dataFolder.canWrite()) {
            setStatus("Folder tidak bisa ditulis: " + dataDir);
            appendLog("[app] Folder data tidak writable: " + dataDir
                    + " - beri izin Storage/Semua file di Pengaturan HP, lalu Start ulang.");
            return;
        }
        if (dataDir.startsWith("/sdcard/")
                || dataDir.startsWith("/storage/") || dataDir.equals("/sdcard")) {
            appendLog("[app] PERINGATAN: folder data di storage bersama - DB plaintext"
                    + " bisa dibaca app lain berizin storage. PIN hanya mengunci UI/bot,"
                    + " bukan mengenkripsi database.");
        }

        // Bersihkan sisa unduhan gagal agar tidak memakan storage.
        cleanupTempFiles(dataDir);
        appendLog("[app] Perangkat: " + KernelCompat.infoBaris(
                KernelCompat.kernelSekarang(), Build.VERSION.SDK_INT));
        File binary = ensureBinary();
        if (binary == null) {
            return;
        }

        // Kernel lama: pastikan shim getrandom, lalu uji --version sekali lagi
        // dengan shim agar smoke test menilai kondisi start yang sebenarnya.
        boolean legacy = KernelCompat.legacyPerangkat();
        File shim = null;
        if (legacy) {
            shim = ensureShim();
            if (shim == null) {
                return;
            }
            if (!detectBinaryVersion(binary)) {
                appendLog("[app] FATAL: binary gagal --version walau shim terpasang"
                        + " - start dibatalkan.");
                setStatus("Binary tidak jalan di perangkat ini - cek log.");
                return;
            }
            appendLog("[app] Shim getrandom siap - start dengan LD_PRELOAD.");
        }

        // Smoke test kernel: tanpa shim, binary panic getrandom() di kernel 3.x.
        // Digagalkan di sini dengan pesan jelas — jangan sampai start setengah jalan.
        if (isKernelRandomPanic(lastVersionOutput)) {
            String kernel = KernelCompat.kernelSekarang();
            String saran = KernelCompat.saranShimGagal(kernel.isEmpty() ? "?" : kernel);
            autoRestart = false;
            setStatus("Binary tidak cocok kernel STB - dihentikan.\n" + saran);
            appendLog("[app] FATAL: " + saran + " Web-vault & TLS tidak masalah.");
            writeCrashLog("binary tak cocok kernel (smoke test)");
            TgBackup.sendMessage(this, "Binary tidak cocok kernel STB lama"
                    + " (getrandom errno=22) - auto-restart dimatikan.\n" + saran);
            return;
        }

        // Bersihkan proses vaultwarden lama yang masih nyangkut (biasanya masih
        // memegang port) sebelum start - penyebab utama "looping" saat start.
        killStaleVaultwarden();
        int portNum = -1;
        try {
            portNum = Integer.parseInt(port.trim());
        } catch (Exception ignored) {
        }
        // Sumber kebenaran tunggal bersama normalisasiPort(): <1024 butuh root
        // dan tak pernah bisa bind tanpa root, jadi sembuhkan ke default di sini
        // (persist agar prefs lama berisi 80/443 tak FATAL tiap Start).
        // Cek isPortBusy di bawah hanya saran pra-start (TOCTOU bind-lalu-lepas):
        // penentu sah adalah gagal bind Rocket saat exec + mitigasi portDirebut.
        if (portNum < 1024 || portNum > 65535) {
            appendLog("[app] Port tidak valid/privileged ('" + port + "') - pakai default " + DEFAULT_PORT + ".");
            port = DEFAULT_PORT;
            portNum = Integer.parseInt(DEFAULT_PORT);
            sp.edit().putString(KEY_PORT, port).apply();
        }
        if (isPortBusy(portNum)) {
            if (portButuhRoot(portNum)) {
                setStatus("Port " + port.trim() + " butuh akses root.\n"
                        + "Pakai port >= 1024 (mis. 8088) di Settings, lalu Start lagi.");
                appendLog("[app] Port " + port.trim() + " butuh root (privileged)"
                        + " - server TIDAK start. Ganti ke port >= 1024.");
                TgBackup.sendMessage(this, "Gagal start: port " + port.trim()
                        + " butuh akses root. Ganti ke port >= 1024 lalu coba lagi.");
                return;
            }
            setStatus("Port " + port.trim() + " sedang dipakai proses lain.\n"
                    + "Stop server lain / ganti port di Settings, lalu Start lagi.");
            appendLog("[app] Port " + port.trim() + " sedang dipakai - server TIDAK start (cegah loop).");
            TgBackup.sendMessage(this, "Gagal start: port " + port.trim()
                    + " sedang dipakai proses lain. Stop server lain / ganti port lalu coba lagi.");
            return;
        }

        try {
            // HTTPS-only: HTTP tak bisa dipakai, server selalu TLS (fail-closed).
            sp.edit().putBoolean(KEY_HTTPS, true).apply();
            boolean https = true;
            String scheme = "https";
            File tlsDir = prepareTls(dataFolder);
            if (tlsDir == null) {
                appendLog("[app] FATAL: sertifikat TLS gagal dibuat - server TIDAK start.");
                setStatus("Gagal buat sertifikat TLS - server tidak start.\n"
                        + "Cek sisa storage & tanggal/jam STB, lalu Start lagi.");
                TgBackup.sendMessage(this, "Gagal start: sertifikat TLS gagal dibuat. "
                        + "Cek sisa storage & tanggal/jam STB lalu coba lagi.");
                return;
            }
            long certDays = TlsCert.daysLeft(new File(tlsDir, "cert.pem"));
            if (certDays >= 0 && certDays < 30) {
                appendLog("[app] PERINGATAN: sertifikat TLS tinggal " + certDays
                        + " hari. Sertifikat dibuat ulang otomatis saat IP berubah.");
            } else if (certDays == -2) {
                // Fail-closed: sertifikat belum valid (jam STB miring ke masa lalu)
                // membuat semua klien menolak TLS; start hanya membuang health-check
                // dan restart beruntun. Minta betulkan jam dulu.
                appendLog("[app] FATAL: jam STB miring (sertifikat belum valid)"
                        + " - server TIDAK start. Aktifkan Tanggal & waktu otomatis,"
                        + " lalu Start lagi.");
                setStatus("Jam STB salah (sertifikat belum valid) - server tidak start.\n"
                        + "Aktifkan Tanggal & waktu otomatis, lalu Start lagi.");
                TgBackup.sendMessage(this, "Gagal start: tanggal & jam STB salah"
                        + " (sertifikat TLS belum valid). Aktifkan Tanggal & waktu"
                        + " otomatis lalu coba lagi.");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath());
            pb.environment().put("DATA_FOLDER", dataDir);
            pb.environment().put("ROCKET_ADDRESS", "0.0.0.0");
            pb.environment().put("ROCKET_PORT", port);
            // STB 1 GB (mis. ZTE B860H): 1 worker + pool DB kecil agar
            // tidak dibunuh LowMemoryKiller tepat setelah "Rocket has launched"
            // (exit 9/SIGKILL tanpa panic). 1 worker cukup untuk pemakaian rumahan.
            pb.environment().put("ROCKET_WORKERS", "1");
            pb.environment().put("DATABASE_MAX_CONNS", "2");

            // Admin token: tolak karakter kontrol/spasi agar env Rocket tak rusak.
            String adminToken = TgBackup.amanString(sp, KEY_ADMIN_TOKEN, "");
            if (adminToken != null && !adminToken.trim().isEmpty()) {
                String bersih = adminToken.trim();
                if (!tokenAdminValid(bersih)) {
                    appendLog("[app] FATAL: admin token mengandung spasi/baris baru"
                            + " - server TIDAK start. Perbaiki di Settings.");
                    setStatus("Admin token tak valid (spasi/baris baru) - perbaiki di Settings.");
                    TgBackup.sendMessage(this, "Gagal start: admin token tak valid"
                            + " (spasi/baris baru). Perbaiki di Settings.");
                    return;
                }
                pb.environment().put("ADMIN_TOKEN", bersih);
                appendLog("[app] Admin token diaktifkan.");
            }

            // Web vault: prioritaskan yang di-update di dataDir, baru fallback ke bundled
            File localWv = new File(dataFolder, "web-vault/index.html");
            if (localWv.exists()) {
                pb.environment().put("WEB_VAULT_ENABLED", "true");
                pb.environment().put("WEB_VAULT_FOLDER", localWv.getParentFile().getAbsolutePath());
                appendLog("[app] Web vault (updated): " + localWv.getParentFile().getAbsolutePath());
            } else {
                File webVault = extractWebVault();
                if (webVault != null) {
                    pb.environment().put("WEB_VAULT_ENABLED", "true");
                    pb.environment().put("WEB_VAULT_FOLDER", webVault.getAbsolutePath());
                } else {
                    pb.environment().put("WEB_VAULT_ENABLED", "false");
                }
            }

            String tlsCert = new File(tlsDir, "cert.pem").getAbsolutePath();
            String tlsKey = new File(tlsDir, "key.pem").getAbsolutePath();
            pb.environment().put("ROCKET_TLS", "{certs=\"" + kutipRocket(tlsCert) + "\",key=\"" + kutipRocket(tlsKey) + "\"}");
            appendLog("[app] TLS cert: " + tlsCert);
            appendLog("[app] TLS key:  " + tlsKey);
            pb.environment().put("RUST_LOG", "info");
            if (shim != null) {
                pb.environment().put("LD_PRELOAD", shim.getAbsolutePath());
                appendLog("[app] LD_PRELOAD shim getrandom aktif.");
            }
            // Selalu IP LAN (fitur domain lokal dihapus: butuh DNS sendiri di jaringan).
            String domain = scheme + "://" + formatHostUntukUrl(lanHost()) + ":" + port;
            pb.environment().put("DOMAIN", domain);
            pb.redirectErrorStream(true);

            runningDataDir = dataDir;
            runningPort = port;
            runningHttps = https;
            runningAdminToken = sidikTokenAdmin(adminToken);
            String wvFrom = Updater.webVaultFromVersion(this);
            runningWvFrom = wvFrom == null ? "" : wvFrom;
            runningLanHost = lanHost();
            ipBerubahDiperingatkan = "";
            ipBaruKandidat = "";
            ipBaruHitung = 0;

            // Validasi ulang tepat sebelum exec: unduh binary/web-vault di atas
            // makan waktu bermenit-menit; symlink folder data yang ditukar di
            // tengah jalan (TOCTOU) wajib menggagalkan start, bukan meluncurkan
            // server di folder asing.
            if (!dataDirKanonisAman(dataDir)) {
                throw new IOException("Folder data berubah/tak valid saat start;"
                        + " dibatalkan agar server tak jalan di folder asing.");
            }
            // Stop yang ditekan selama persiapan (unduh binary ber-menit-menit)
            // membatalkan start di sini: jangan luncurkan server tanpa diminta.
            if (batalStart) {
                batalStart = false;
                appendLog("[app] Start dibatalkan (Stop ditekan saat persiapan)"
                        + " - tekan Start lagi bila ingin jalan.");
                setStatus("Stopped");
                return;
            }
            process = pb.start();
            // Proses baru milik start ini: hapus tanda stop lama agar crash
            // dini tak dianggap "stop disengaja" (race stopper 5-8 dtk).
            // Tanda milik proses lama dibuang total karena generasi berganti.
            prosesStopDisengaja = null;
            acquireWakeLock();
            running = true;
            // Riwayat restart milik kejadian lama: bersihkan agar /status tak
            // menampilkan "Restart: Nx" basi setelah start bersih yang sukses.
            synchronized (RESTART_TIMES) {
                RESTART_TIMES.clear();
                RESTART_REASONS.clear();
            }
            healthFails.set(0);
            lastStartTime = System.currentTimeMillis();
            lastStartElapsed = SystemClock.elapsedRealtime();
            restartAttempt.set(0);

            // Siram buffer milik folder lama dulu agar log tak tecampur ke file baru.
            flushLogFile();
            logFile = new File(dataFolder, "vaultwarden.log");

            setStatus("Running (PID " + getPid(process) + ")\nData: " + dataDir
                    + "\nURL lokal (di HP): " + scheme + "://127.0.0.1:" + port
                    + "\nURL jaringan (dari PC/laptop): " + domain);
            TgBackup.sendMessage(this, "Server jalan:\n" + statusLine);
            TgBackup.notifyLowStorage(this);

            final Process p = process;
            Thread reader = new Thread(() -> pumpOutput(p), "vw-output");
            reader.setDaemon(true);
            reader.start();

            Thread watcher = new Thread(() -> watchProcess(p), "vw-watch");
            watcher.setDaemon(true);
            watcher.start();

            appendLog("[app] start: " + binary.getAbsolutePath()
                    + " | versi: " + binaryVersion
                    + " | data=" + dataDir + " | port=" + port);
            for (String panduan : panduanLoginBitwarden(scheme, port, formatHostUntukUrl(lanHost()))) {
                appendLog(panduan);
            }
        } catch (Exception e) {
            process = null;
            running = false;
            // Bersihkan penanda jalan agar health tak menunjuk
            // port/folder basi walau status sudah "Gagal start".
            runningDataDir = "";
            runningPort = "";
            runningHttps = false;
            runningAdminToken = "";
            runningWvFrom = "";
            runningLanHost = "";
            ipBerubahDiperingatkan = "";
            ipBaruKandidat = "";
            ipBaruHitung = 0;
            releaseWakeLock();
            appendLog("[app] ERROR start: " + e);
            setStatus("Gagal start: " + e.getMessage());
        }
    }

    private void stopServer() {
        if (process != null) {
            final Process p = process;
            // Tandai stop sengaja agar watchProcess abaikan exit (status tetap
            // "Menghentikan..."). Proses dipertahankan sampai benar-benar mati agar
            // stopAndWait()/restore tak menimpa DB selagi proses lama hidup.
            // Per-proses agar Start baru tak ikut ditandai.
            tandaiStopDisengaja(p);
            // Jangan klaim "Stopped" selagi proses lama masih dimatikan
            // (hingga 8 dtk): status + flag dibersihkan thread stopper agar
            // UI/bot/restore tak mengira DB sudah bebas dikunci.
            setStatus("Menghentikan...");
            p.destroy();
            Thread stopper = new Thread(() -> {
                try {
                    if (!waitForOrKill(p, 5000)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            p.destroyForcibly();
                        } else {
                            p.destroy();
                        }
                        try {
                            waitForOrKill(p, 3000);
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (process == p) {
                        process = null;
                        running = false;
                        runningDataDir = "";
                        runningPort = "";
                        runningHttps = false;
                        runningAdminToken = "";
                        runningWvFrom = "";
                        runningLanHost = "";
                        ipBerubahDiperingatkan = "";
            ipBaruKandidat = "";
            ipBaruHitung = 0;
                        releaseWakeLock();
                        flushLogFile();
                        setStatus("Stopped");
                    }
                    hapusTandaStop(p);
                }
            }, "vw-stop");
            stopper.setDaemon(true);
            stopper.start();
        } else {
            setStatus("Stopped");
            prosesStopDisengaja = null;
            running = false;
            runningDataDir = "";
            runningPort = "";
            runningHttps = false;
            runningAdminToken = "";
            runningWvFrom = "";
            runningLanHost = "";
            ipBerubahDiperingatkan = "";
            ipBaruKandidat = "";
            ipBaruHitung = 0;
            releaseWakeLock();
            flushLogFile();
        }
        // Pesan tetap dikirim langsung; flag/DB dibersihkan thread stopper
        // agar tak ada yang mengira proses lama sudah mati sebelum waktunya.
        TgBackup.sendMessage(this, "Server dihentikan.");
    }

    private void watchProcess(Process p) {
        try {
            int code = p.waitFor();
            if (isStopDisengaja(p) || process != p) {
                // Stop sengaja atau sudah diganti proses baru: jangan timpa status.
                if (process == p) {
                    process = null;
                }
                return;
            }
            if (process == p) {
                process = null;
                running = false;
                releaseWakeLock();
                appendLog("[app] process exit: " + code);
                String tail = tailLog(18);
                if (isKernelRandomPanic(tail)) {
                    // Binary Rust terbaru butuh getrandom() kernel baru; di kernel
                    // STB Android 5/6 (errno=22) selalu panic saat start.
                    // Retry tidak ada gunanya — langsung berhenti + beri saran.
                    autoRestart = false;
                    String kernel2 = KernelCompat.kernelSekarang();
                    String saran = KernelCompat.saranShimGagal(
                            kernel2.isEmpty() ? "?" : kernel2);
                    setStatus("Binary tidak cocok kernel STB - dihentikan.\n" + saran);
                    appendLog("[app] FATAL: " + saran
                            + " Web-vault & TLS tidak masalah.");
                    writeCrashLog("binary tak cocok kernel (getrandom)");
                    TgBackup.sendMessage(this, "Binary tidak cocok kernel STB lama"
                            + " (getrandom errno=22) - auto-restart dimatikan.\n" + saran);
                    return;
                }
                if (portDirebut(tail)) {
                    // Port direbut proses lain di jeda cek-vs-start (TOCTOU):
                    // restart tak ada gunanya — langsung berhenti + beri saran.
                    autoRestart = false;
                    int portNyata = portLoopback();
                    setStatus("Port " + portNyata + " direbut proses lain - dihentikan.\n"
                            + "Stop server lain / ganti port di Settings, lalu Start lagi.");
                    appendLog("[app] FATAL: bind gagal, port " + portNyata
                            + " direbut proses lain - auto-restart dimatikan.");
                    writeCrashLog("port direbut (bind gagal)");
                    TgBackup.sendMessage(this, "Gagal start: port " + portNyata
                            + " direbut proses lain. Stop server lain / ganti port"
                            + " lalu Start lagi.");
                    return;
                }
                if (autoRestart) {
                    writeCrashLog("crash (exit " + code + ")");
                    if (recordRestart("crash (exit " + code + ")")) {
                        // Loop terdeteksi: recordRestart sudah set status + kirim Telegram.
                    } else {
                        String saran = saranCrash(code);
                        if (!saran.isEmpty()) {
                            appendLog("[app] " + saran);
                        }
                        TgBackup.sendMessage(this, "Server crash (exit " + code + ")"
                                + (saran.isEmpty() ? "" : "\n" + saran)
                                + "\n" + shorten(tail, 500) + "\nRestart otomatis...");
                        setStatus("Server crash (exit " + code + ")"
                                + (saran.isEmpty() ? "" : "\n" + saran)
                                + " - restart otomatis\n" + shorten(tail, 250));
                        scheduleRestart();
                    }
                } else {
                    String saran = saranCrash(code);
                    setStatus("Stopped (exit code " + code + ")"
                            + (saran.isEmpty() ? "" : "\n" + saran));
                    if (!saran.isEmpty()) {
                        appendLog("[app] " + saran);
                    }
                }
            }
        } catch (InterruptedException ignored) {
        }
    }

    /** Restart tertunda sebagai field (bukan anonim) agar onDestroy/STOP bisa
     *  membatalkannya; tanpa ini restart jalan di service yang sudah mati. */
    private final Runnable restartTunda = new Runnable() {
        @Override
        public void run() {
            if (healthActive && autoRestart && (process == null || !alive(process))) {
                startServerAsync();
            }
        }
    };

    private void scheduleRestart() {
        synchronized (KUNCI_RESTART) {
            scheduleRestartTerkunci();
        }
    }

    private void scheduleRestartTerkunci() {
        long uptime = SystemClock.elapsedRealtime() - lastStartElapsed;
        if (uptime > 60_000) {
            restartAttempt.set(0);
        }
        if (restartAttempt.get() >= RESTART_DELAYS.length) {
            autoRestart = false;
            healthActive = false;
            mainHandler.removeCallbacks(healthTick);
            String tail = tailLog(18);
            setStatus("Server berhenti - gagal restart 5x.\n" + shorten(tail, 300));
            appendLog("[app] Berhenti mencoba restart setelah 5 kegagalan.");
            writeCrashLog("restart 5x gagal");
            TgBackup.sendPenting(this, "Server berhenti: gagal restart 5x.\n"
                    + shorten(tail, 600));
            return;
        }
        int coba = restartAttempt.getAndIncrement();
        long delay = RESTART_DELAYS[coba];
        setStatus("Server crash - restart dalam " + (delay / 1000) + " dtk (coba " + (coba + 1) + ")");
        appendLog("[app] Crash terdeteksi, restart dalam " + delay + " ms");
        mainHandler.removeCallbacks(restartTunda);
        mainHandler.postDelayed(restartTunda, delay);
    }

    /** Saran spesifik untuk exit code proses (logika murni agar bisa diuji).
     *  Exit 9 = SIGKILL tanpa panic: di STB 1 GB hampir selalu LowMemoryKiller
     *  (RAM penuh) tepat setelah "Rocket has launched", bukan shim getrandom
     *  (getrandom panic = exit 101/1 + pesan getrandom). */
    static String saranCrash(int code) {
        if (code == 9) {
            return "Proses dihentikan sistem (exit 9/SIGKILL), kemungkinan RAM penuh. "
                    + "Tutup aplikasi lain / reboot STB lalu Start lagi.";
        }
        return "";
    }

    /** Saran [login] untuk satu baris output Vaultwarden; null bila baris tak terkait login.
     *  Murni agar bisa diuji unit (tanpa runtime Android). */
    static String saranLoginUntukBaris(String baris, boolean httpsAktif) {
        if (baris == null || baris.isEmpty()) {
            return null;
        }
        // Tanpa toLowerCase (alokasi per baris log deras): cocok abaikan-huruf langsung.
        // Saring murah dulu agar baris noise (info biasa) keluar sebelum pindai mahal.
        if (!Util.mengandungAbaikanHuruf(baris, "invalid")
                && !Util.mengandungAbaikanHuruf(baris, "password")
                && !Util.mengandungAbaikanHuruf(baris, "credential")
                && !Util.mengandungAbaikanHuruf(baris, "login")
                && !Util.mengandungAbaikanHuruf(baris, "fail")
                && !Util.mengandungAbaikanHuruf(baris, "wrong")
                && !Util.mengandungAbaikanHuruf(baris, "incorrect")
                && !Util.mengandungAbaikanHuruf(baris, "token")
                && !Util.mengandungAbaikanHuruf(baris, "2fa")
                && !Util.mengandungAbaikanHuruf(baris, "totp")
                && !Util.mengandungAbaikanHuruf(baris, "two-factor")
                && !Util.mengandungAbaikanHuruf(baris, "two factor")
                && !Util.mengandungAbaikanHuruf(baris, "tls")
                && !Util.mengandungAbaikanHuruf(baris, "ssl")
                && !Util.mengandungAbaikanHuruf(baris, "certificate")
                && !Util.mengandungAbaikanHuruf(baris, "handshake")) {
            return null;
        }
        if (Util.mengandungAbaikanHuruf(baris, "invalid username or password")
                || Util.mengandungAbaikanHuruf(baris, "invalid credentials")
                || Util.mengandungAbaikanHuruf(baris, "username or password is incorrect")
                || Util.mengandungAbaikanHuruf(baris, "wrong password")
                || Util.mengandungAbaikanHuruf(baris, "incorrect password")
                || Util.mengandungAbaikanHuruf(baris, "login failed")
                || Util.mengandungAbaikanHuruf(baris, "failed login")
                || Util.mengandungAbaikanHuruf(baris, "failed log-in")
                || (Util.mengandungAbaikanHuruf(baris, "/identity/connect/token") && Util.mengandungAbaikanHuruf(baris, "401"))
                || (Util.mengandungAbaikanHuruf(baris, "invalid") && (Util.mengandungAbaikanHuruf(baris, "credential") || Util.mengandungAbaikanHuruf(baris, "password")))) {
            return "[login] Login aplikasi Bitwarden gagal: email/password salah atau akun belum "
                    + "terdaftar. Daftar dulu di web-vault (Create Account), lalu login di aplikasi "
                    + "dengan email+password itu (bukan admin token).";
        }
        if (Util.mengandungAbaikanHuruf(baris, "two-factor") || Util.mengandungAbaikanHuruf(baris, "two factor")
                || Util.mengandungAbaikanHuruf(baris, "2fa") || Util.mengandungAbaikanHuruf(baris, "totp")) {
            return "[login] Login butuh kode 2FA: buka aplikasi authenticator lalu masukkan "
                    + "kode 6 digit di aplikasi Bitwarden.";
        }
        boolean tls = Util.mengandungAbaikanHuruf(baris, "certificate") || Util.mengandungAbaikanHuruf(baris, "handshake")
                || Util.mengandungAbaikanHuruf(baris, "tls") || Util.mengandungAbaikanHuruf(baris, "ssl");
        boolean gagal = Util.mengandungAbaikanHuruf(baris, "unknown") || Util.mengandungAbaikanHuruf(baris, "alert") || Util.mengandungAbaikanHuruf(baris, "fail")
                || Util.mengandungAbaikanHuruf(baris, "error") || Util.mengandungAbaikanHuruf(baris, "abort") || Util.mengandungAbaikanHuruf(baris, "refus")
                || Util.mengandungAbaikanHuruf(baris, "ditolak") || Util.mengandungAbaikanHuruf(baris, "verify");
        if (tls && gagal) {
            return "[login] Koneksi aman (TLS) gagal: install dulu CA lewat tombol Install Cert "
                    + "/ Bagikan CA di Settings (jenis \"CA certificate\"), lalu login lagi. "
                    + "Bila dulu bisa lalu gagal: Reset Sertifikat lalu install ulang CA di semua HP.";
        }
        return null;
    }

    /** True bila hint [login] boleh tampil (throttle 60 dtk). Murni agar bisa diuji. */
    static boolean bolehHintLogin(long kiniElapsed, long terakhirElapsed) {
        return kiniElapsed - terakhirElapsed >= LOGIN_HINT_JEDA_MS;
    }

    /** Baris panduan [login] aplikasi Bitwarden saat start (URL benar + HTTPS + akun).
     *  Murni agar bisa diuji unit (tanpa runtime Android). */
    static java.util.List<String> panduanLoginBitwarden(String scheme, String port, String lanIp) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        String ip = (lanIp == null || lanIp.isEmpty()) ? "<IP-STB>" : lanIp.trim();
        String p = (port == null || port.isEmpty()) ? DEFAULT_PORT : port.trim();
        String urlBenar = "https://" + ip + ":" + p;
        out.add("[login] Server URL di aplikasi Bitwarden: " + urlBenar
                + " (satu WiFi, tanpa /#/ di belakang; "
                + "bukan 127.0.0.1 dari HP lain).");
        out.add("[login] HTTPS WAJIB: install dulu CA lewat tombol Install Cert / Bagikan CA "
                + "di Settings (jenis \"CA certificate\") agar HP percaya ke server ini. "
                + "Bila IP STB berubah (DHCP): Start ulang lalu install ulang CA.");
        out.add("[login] Belum punya akun? Buka web-vault di browser > Create Account dulu, "
                + "baru login di aplikasi dengan email+password itu (bukan admin token).");
        return out;
    }

    /** True bila log mengandung gagal acak kernel lama: panic getrandom Rust
     *  ("failed to generate random data" dari std::sys::random) atau gagal
     *  ticketer TLS Rocket ("bad TLS ticketer: failed to get random bytes"
     *  dari ring/rustls) — tanda binary tidak cocok dengan kernel Android
     *  lama (STB Android 5/6, errno=22). Server selalu HTTPS sehingga kedua
     *  varian sama-sama fatal saat start.
     *  Package-private agar bisa diuji unit (tanpa runtime Android). */
    static boolean isKernelRandomPanic(String logTail) {
        if (logTail == null) {
            return false;
        }
        if (logTail.contains("failed to generate random data")) {
            return true;
        }
        if (logTail.contains("bad TLS ticketer")
                || logTail.contains("failed to get random bytes")) {
            return true;
        }
        String rendah = logTail.toLowerCase(Locale.US);
        return rendah.contains("panicked") && rendah.contains("getrandom");
    }

    /** True bila ekor log menunjukkan bind gagal karena port direbut proses
     *  lain (EADDRINUSE): restart tak ada gunanya, langsung berhenti + saran.
     *  Package-private agar bisa diuji unit (tanpa runtime Android). */
    static boolean portDirebut(String logTail) {
        if (logTail == null || logTail.isEmpty()) {
            return false;
        }
        String r = logTail.toLowerCase(Locale.US);
        return r.contains("address already in use") || r.contains("address in use")
                || r.contains("eaddrinuse") || r.contains("os error 98");
    }

    /** True bila baris output binary hanya noise linker STB lama
     *  ("WARNING: linker: ... unsupported flags DT_FLAGS_1 ...") — tidak fatal,
     *  disaring dari deteksi versi agar versi terbaca benar.
     *  Package-private agar bisa diuji unit (tanpa runtime Android). */
    static boolean isNoiseLinker(String baris) {
        return baris != null && baris.trim().startsWith("WARNING: linker");
    }

    /** Catat restart otomatis + deteksi loop. Return true bila harus berhenti
     *  (≥3 restart dalam 5 menit): matikan auto-restart, tulis crash log,
     *  beri tahu via status & Telegram. */
    private boolean recordRestart(String reason) {
        // Monotonik: wall-clock yang melompat (NTP/user) memicu "restart berulang"
        // palsu atau menyembunyikan loop asli; stempel tampil tetap wall-clock.
        long now = SystemClock.elapsedRealtime();
        String stamp;
        stamp = LOG_TS.get().format(new Date());
        synchronized (RESTART_TIMES) {
            RESTART_TIMES.add(now);
            RESTART_REASONS.add(stamp + " " + reason);
            while (RESTART_TIMES.size() > RESTART_HISTORY_MAX) {
                RESTART_TIMES.remove(0);
                RESTART_REASONS.remove(0);
            }
            int n = hitungRestartBaru(RESTART_TIMES, now);
            if (n >= RESTART_WINDOW_MAX) {
                autoRestart = false;
                String tail = tailLog(14);
                setStatus("Restart berulang (" + n + "x dalam 5 mnt) - dihentikan.\n"
                        + shorten(tail, 300));
                appendLog("[app] Restart berulang (" + n
                        + "x dalam 5 mnt) - auto-restart dimatikan.");
                writeCrashLog("restart loop (" + n + "x/5mnt)");
                TgBackup.sendPenting(this, "Server restart berulang (" + n
                        + "x dalam 5 menit) - auto-restart dimatikan.\n"
                        + shorten(tail, 600));
                return true;
            }
        }
        return false;
    }

    /** Hitung restart dalam jendela loop (murni agar bisa unit test). */
    static int hitungRestartBaru(java.util.List<Long> riwayat, long kini) {
        int n = 0;
        for (long t : riwayat) {
            if (kini - t <= RESTART_WINDOW_MS) {
                n++;
            }
        }
        return n;
    }

    /** Ringkasan riwayat restart untuk UI/Telegram; kosong bila tidak pernah restart. */
    public static String restartSummary() {
        synchronized (RESTART_TIMES) {
            if (RESTART_REASONS.isEmpty()) {
                return "";
            }
            String last = RESTART_REASONS.get(RESTART_REASONS.size() - 1);
            return "Restart: " + RESTART_REASONS.size() + "x (terakhir " + last + ")";
        }
    }

    /** Isi crash log terakhir (crash-last.log internal); null bila belum ada crash. */
    public static String crashLogText(Context ctx) {
        File f = new File(ctx.getFilesDir(), CRASH_LOG_NAME);
        if (!f.exists()) {
            return null;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Tulis ~100 baris log terakhir ke file internal (crash-last.log). */
    private void writeCrashLog(String reason) {
        try {
            String stamp;
            stamp = LOG_TS.get().format(new Date());
            // Samarkan dulu: file ini dibaca dialog crash + dikirim /crashlog
            // ke Telegram (keluar perangkat), seperti jalur share/clipboard.
            String ekor = tailLog(100);
            String body = "=== " + stamp + " [" + reason + "] ===\n"
                    + (LogActivity.butuhSamaran(ekor) ? LogActivity.samarkanLog(ekor) : ekor) + "\n";
            try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                    new FileOutputStream(new File(getFilesDir(), CRASH_LOG_NAME), false),
                    StandardCharsets.UTF_8)) {
                w.write(body);
            }
        } catch (Exception ignored) {
        }
    }

    private void pumpOutput(Process p) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Hint [login]: jelaskan ke log realtime mengapa aplikasi Bitwarden
                // gagal login (kredensial salah, 2FA, atau TLS self-signed ditolak app).
                // Dihitung SEBELUM filter noise agar handshake gagal tetap bersaran.
                // Gerbang throttle dulu: saat ditahan hasilnya tak dipakai sehingga
                // pindai mahal (lowercase + puluhan contains) boleh dilewati.
                String hintLogin = bolehHintLogin(SystemClock.elapsedRealtime(),
                        loginHintTerakhirElapsed)
                        ? saranLoginUntukBaris(line, runningHttps) : null;
                boolean noise = line.contains("CertificateUnknown")
                        || line.contains("tls handshake with 127.0.0.1")
                        || line.contains("Detected TLS-enabled liftoff")
                        || line.contains("unsupported flags DT_FLAGS_1")
                        || line.contains("Shield has enabled a default HSTS policy");
                if (hintLogin != null) {
                    long kini = SystemClock.elapsedRealtime();
                    if (bolehHintLogin(kini, loginHintTerakhirElapsed)) {
                        loginHintTerakhirElapsed = kini;
                        if (noise) {
                            appendLog(hintLogin);
                        } else {
                            appendLog(line);
                            appendLog(hintLogin);
                        }
                    } else if (!noise) {
                        appendLog(line);
                    }
                    continue;
                }
                // Redam log bising yang tidak berguna: handshake TLS lokal
                // (cert self-signed), peringatan HSTS bawaan Vaultwarden, dan
                // warning linker DT_FLAGS_1 di STB lama (tidak fatal).
                if (noise) {
                    continue;
                }
                appendLog(line);
            }
        } catch (Exception ignored) {
        }
    }

    // Buffer tulis file log: flush tiap 16 KB agar tidak buka-tutup file per baris.
    private static final int LOG_FILE_FLUSH_CHARS = 16 * 1024;
    private static final StringBuilder logFileBuf = new StringBuilder();
    private static final Object LOG_FILE_LOCK = new Object();

    /** Date milik thread (format pakai ulang tanpa new Date per baris log deras). */
    private static final ThreadLocal<java.util.Date> LOG_TGL =
            new ThreadLocal<java.util.Date>() {
                @Override protected java.util.Date initialValue() {
                    return new java.util.Date();
                }
            };

    private void appendLog(String line) {
        if (line == null) {
            return;
        }
        String stamp;
        java.util.Date tgl = LOG_TGL.get();
        tgl.setTime(System.currentTimeMillis());
        stamp = LOG_TS.get().format(tgl);
        String entry = stamp + " " + line;
        synchronized (logBuffer) {
            logBuffer.append(entry).append('\n');
            pangkasBufferTerkunci();
            logVer++;
        }
        if (logFile != null) {
            synchronized (LOG_FILE_LOCK) {
                logFileBuf.append(entry).append('\n');
                if (logFileBuf.length() >= LOG_FILE_FLUSH_CHARS) {
                    flushLogFileLocked();
                }
            }
        }
    }

    /** Tulis buffer log ke file (panggil saat stop/destroy agar tak ada yang hilang). */
    private static void flushLogFile() {
        synchronized (LOG_FILE_LOCK) {
            flushLogFileLocked();
        }
    }

    private static void flushLogFileLocked() {
        if (logFileBuf.length() == 0) {
            return;
        }
        File f = logFile;
        if (f == null) {
            logFileBuf.setLength(0);
            return;
        }
        // Rotasi DULU bila penuh: tulis-dulu-rotasi-kemudian + rename gagal
        // = truncate menghapus tulisan yang baru saja ditulis.
        if (f.length() >= MAX_LOG_FILE) {
            File old = new File(f.getParentFile(), f.getName() + ".1");
            if (old.exists()) {
                old.delete();
            }
            if (!f.renameTo(old)) {
                try {
                    new FileOutputStream(f, false).close();
                } catch (Exception ignored) {
                }
            }
        }
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            w.write(logFileBuf.toString());
        } catch (Exception ignored) {
        } finally {
            logFileBuf.setLength(0);
        }
    }

    // ─── Health check (/alive) ─────────────────────────────────────────

    private void checkHealthOnce() {
        peringatkanIpBerubah();
        HasilPing h = pingRinci(this);
        if (h.sehat) {
            // Sehat via /api/config tapi /alive 5xx beruntun = DB rusak yang
            // tak pernah pulih sendiri: restart setelah ambang, bukan selamanya.
            if (aliveRusakTapiConfigSehat(h.aliveCode, h.configCode)) {
                int n = configSajaBeruntun.incrementAndGet();
                if (n >= BATAS_CONFIG_SAJA_BERUNTUN) {
                    configSajaBeruntun.set(0);
                    healthFail("DB rusak beruntun (/alive 5xx tapi /api/config 200, "
                            + BATAS_CONFIG_SAJA_BERUNTUN + "x) — " + h.rincian);
                    return;
                }
                appendLog("[health] /alive 5xx tapi /api/config 200 (DB mungkin rusak,"
                        + " ke-" + n + "/" + BATAS_CONFIG_SAJA_BERUNTUN + ").");
                return;
            }
            configSajaBeruntun.set(0);
            healthFails.set(0);
            healthTcpLolos.set(0);
            return;
        }
        configSajaBeruntun.set(0);
        healthFail("tidak merespon (" + h.rincian + ")");
    }

    /** Bandingkan IP LAN kini dengan snapshot start: DOMAIN Vaultwarden hanya
     *  dibaca saat start sehingga tautan reset password menunjuk IP lama bila
     *  DHCP/WiFi berubah. Peringatkan sekali per IP baru (log + Telegram),
     *  bukan restart otomatis: loopback tetap sehat. */
    private void peringatkanIpBerubah() {
        try {
            if (!running || runningLanHost == null || runningLanHost.isEmpty()) {
                return;
            }
            String kini = lanHost();
            if (kini == null || kini.isEmpty() || kini.equals(runningLanHost)) {
                ipBaruKandidat = "";
                ipBaruHitung = 0;
                return;
            }
            if (kini.equals(ipBaruKandidat)) {
                ipBaruHitung++;
            } else {
                ipBaruKandidat = kini;
                ipBaruHitung = 1;
            }
            if (!kini.equals(ipBerubahDiperingatkan)) {
                ipBerubahDiperingatkan = kini;
                appendLog("[app] IP LAN berubah (" + runningLanHost + " -> " + kini + "):"
                        + " DOMAIN server masih menunjuk IP lama."
                        + " Restart otomatis dijadwalkan bila IP baru stabil.");
                TgBackup.sendMessage(this, "IP LAN berubah (" + runningLanHost + " -> " + kini + ")."
                        + " Server restart otomatis agar tautan memakai IP baru.");
            }
            if (ipBaruHitung >= 2 && healthActive && isProcessAlive()) {
                ipBaruKandidat = "";
                ipBaruHitung = 0;
                appendLog("[health] IP baru stabil 2x tick - restart agar DOMAIN ikut IP baru.");
                scheduleRestart();
            }
        } catch (Exception ignored) {
        }
    }

    private void healthFail(String reason) {
        // Stop ditekan saat cek health terbang: jangan hidupkan ulang server
        // yang baru dihentikan user (autoRestart=true di bawah membatalkannya).
        if (!healthActive) {
            return;
        }
        int gagal = healthFails.incrementAndGet();
        appendLog("[health] /alive gagal: " + reason + " (ke-" + gagal + "/3)");
        if (gagal >= 3) {
            // Pengaman positif-palsu: /alive butuh DB sehingga di STB lambat
            // bisa gagal sementara server tetap melayani (/api/config 200 di log).
            // Bila proses hidup dan port masih menerima TCP, jangan bunuh server.
            boolean prosesHidup = process != null && alive(process);
            boolean tcpOk = tcpTersambung(portLoopback());
            if (prosesHidup && tcpOk) {
                int lolos = healthTcpLolos.incrementAndGet();
                if (lolos < 3) {
                    appendLog("[health] 3x gagal tapi port masih tersambung"
                            + " - server TIDAK dihentikan, coba lagi (" + lolos + "/3).");
                    healthFails.set(2);
                    return;
                }
                appendLog("[health] /alive gagal 9x tapi TCP hidup"
                        + " - dianggap gantung, server dihentikan.");
            }
            // Jangan terminal: coba restart otomatis terbatas (maks 5x via
            // scheduleRestart) agar gangguan sesaat tak mematikan server
            // selamanya; spam Telegram dicegah karena tiap episode hanya
            // kirim satu pesan di bawah + scheduleRestart membatasi percobaan.
            autoRestart = true;
            healthFails.set(0);
            healthTcpLolos.set(0);
            // Flag mati tanpa syarat (bukan hanya bila proses masih ada):
            // proses bisa mati tepat di jeda cek-vs-eksekusi sehingga p null.
            running = false;
            runningDataDir = "";
            runningPort = "";
            runningHttps = false;
            runningAdminToken = "";
            runningWvFrom = "";
            runningLanHost = "";
            ipBerubahDiperingatkan = "";
            ipBaruKandidat = "";
            ipBaruHitung = 0;
            setStatus("Server tidak sehat - restart otomatis.");
            appendLog("[health] 3x gagal beruntun - restart otomatis.");
            writeCrashLog("health 3x");
            TgBackup.sendMessage(this, "Server tidak sehat (3x gagal /alive) - restart otomatis.\n"
                    + shorten(tailLog(15), 500));
            final Process p = process;
            if (p != null) {
                tandaiStopDisengaja(p);
                releaseWakeLock();
                p.destroy();
                // Tunggu sinkron di worker health (bukan UI): restartTunda
                // 2 dtk yang menyala selagi proses lama masih sekarat akan
                // dilewati sekali lalu restart hilang diam-diam. Mati dulu,
                // baru jadwalkan.
                try {
                    if (!waitForOrKill(p, 5000)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            p.destroyForcibly();
                        } else {
                            p.destroy();
                        }
                        try {
                            waitForOrKill(p, 3000);
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (process == p) {
                        process = null;
                    }
                    hapusTandaStop(p);
                }
            }
            // Rem loop seperti jalur crash: tanpa ini restart health (uptime
            // selalu >60 dtk sehingga restartAttempt selalu di-reset)
            // jalan selamanya + spam Telegram tiap siklus bila DB rusak permanen.
            if (recordRestart("health 3x")) {
                return;
            }
            // Lanjut restart terbatas (maks 5x, backoff di scheduleRestart).
            scheduleRestart();
            return;
        }
        // Toleransi: gagal 1-2x hanya dicatat, tunggu cek berikutnya.
        // Restart/penghentian hanya setelah 3x gagal beruntun agar
        // timeout sesaat tidak memicu restart penuh.
        return;
    }

    private static volatile javax.net.ssl.SSLSocketFactory sslFactory;
    /** Cap CA aktif (mtime+ukuran+hash isi); cache factory gugur bila CA regenerasi. */
    private static volatile long sslCaCap = -1L;

    /** Trust khusus health-check loopback: pin CA milik app; gagal tertutup
     *  bila CA tak tersedia (tanpa fallback trust-all agar server 200 palsu
     *  milik app lokal lain tak menutupi outage). Tak dipakai koneksi luar. */
    private static javax.net.ssl.SSLSocketFactory loopbackSslFactory(Context ctx) throws Exception {
        long cap = capCaAktif(ctx);
        javax.net.ssl.SSLSocketFactory f = sslFactory;
        if (f == null || cap != sslCaCap) {
            synchronized (ServerService.class) {
                // Hitung ulang di dalam kunci (reentrant): cap di luar bisa basi bila
                // ca.pem regenerasi antar baca-cap dan masuk-kunci, lalu factory basi
                // menimpa factory segar milik thread lain (cermin HttpsCompat).
                cap = capCaAktif(ctx);
                f = sslFactory;
                if (f == null || cap != sslCaCap) {
                    javax.net.ssl.SSLSocketFactory pinned = cobaPinnedCa(ctx);
                    if (pinned == null) {
                        throw new java.io.IOException(
                                "CA lokal tidak tersedia untuk health check.");
                    }
                    sslFactory = pinned;
                    sslCaCap = cap;
                    f = sslFactory;
                }
            }
        }
        return f;
    }

    /** Acak bersama init SSL (thread-safe): seed /dev/urandom kernel lama bisa blokir. */
    private static final java.security.SecureRandom ACAK_SSL = new java.security.SecureRandom();

    static java.security.SecureRandom acakSsl() {
        return ACAK_SSL;
    }

    /** Kunci stat murah (mtime+ukuran) untuk capCaAktif: bila stat tak berubah,
     *  cap penuh dipakai ulang tanpa baca+hash seluruh isi di tiap health-check. */

    private static volatile long capCaStat = Long.MIN_VALUE;
    private static volatile long capCaNilai = 0L;
    /** Waktu stat CA terakhir (elapsedRealtime): stat ulang maks 1x/menit. */
    private static volatile long capCaWaktu = 0;

    /** Cap file CA aktif agar factory segar setelah regenerasi cert (ganti IP). */
    private static long capCaAktif(Context ctx) {
        // Gerbang TTL 60 dtk tanpa kunci/syscall: ca.pem hanya berubah saat
        // Start/restore/ganti folder (semua menginvalidasi eksplisit di
        // prepareTls), sehingga stat (getFilesDir + isFile + mtime + length)
        // tiap cobaKode (1-2x per health tick + ping perintah Telegram)
        // mubazir di eMMC STB lama + berebut kunci kelas dengan thread UI.
        long kini = SystemClock.elapsedRealtime();
        if (capCaStat != Long.MIN_VALUE && kini - capCaWaktu < 60_000) {
            return capCaNilai;
        }
        // Sinkron (reentrant, cermin HttpsCompat.capOverride): dua thread
        // (health-tick + ping UI) tak boleh berlomba baca-tulis capCaStat/
        // capCaNilai — satu bisa menimpa hasil segar dengan nilai basi.
        synchronized (ServerService.class) {
        // Cek ulang dalam kunci: thread lain bisa menyegarkan saat antre.
        long kini2 = SystemClock.elapsedRealtime();
        if (capCaStat != Long.MIN_VALUE && kini2 - capCaWaktu < 60_000) {
            return capCaNilai;
        }
        try {
            java.io.File ca = null;
            try {
                ca = new java.io.File(ctx.getFilesDir(), "tls/ca.pem");
            } catch (Exception ignored) {
            }
            if ((ca == null || !ca.isFile()) && ctx != null) {
                try {
                    android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    String dataDir = TgBackup.amanString(sp, KEY_DATA_DIR, dataDirBawaanSegar());
                    if (dataDir == null || dataDir.trim().isEmpty()) {
                        dataDir = dataDirBawaanSegar();
                    }
                    ca = new java.io.File(dataDir, "tls/ca.pem");
                } catch (Exception ignored) {
                }
            }
            if (ca != null && ca.isFile()) {
                long stat = ca.lastModified() * 31 + ca.length();
                if (stat == capCaStat) {
                    // Stat cocok: perbarui stempel agar gerbang TTL 60 dtk
                    // tak jatuh ke jalur lambat di tiap tick berikut.
                    capCaWaktu = kini2;
                    return capCaNilai;
                }
                // Tanpa perkalian raksasa (rawan overflow): gabung mtime + panjang
                // lalu campur hash SELURUH isi agar perubahan ekor file tak lolos
                // (sama seperti HttpsCompat.capOverride; mtime FAT 2 detik saja
                // tak cukup membedakan CA hasil regenerasi cepat — factory basi
                // bikin health HTTPS gagal + restart beruntun).
                long h = stat;
                try (java.io.InputStream in = new java.io.FileInputStream(ca)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        h = h * 31 + (java.util.Arrays.hashCode(
                                java.util.Arrays.copyOf(buf, n)) & 0xffffffffL);
                    }
                } catch (Exception ignored) {
                }
                capCaStat = stat;
                capCaNilai = h;
                capCaWaktu = kini2;
                return h;
            }
        } catch (Exception ignored) {
        }
        // CA tak ada/rusak: stempel juga agar tick tak menghujani stat
        // tiap 30 dtk; prepareTls mereset saat sertifikat lahir.
        capCaWaktu = kini2;
        return 0L;
        }
    }

    /** Muat tls/ca.pem milik app sebagai trust anchor bila tersedia. */
    private static javax.net.ssl.SSLSocketFactory cobaPinnedCa(Context ctx) {
        try {
            if (ctx == null) return null;
            // Cert aktif tinggal di internal (migrasi dari /sdcard); cek internal
            // dulu agar CA sesuai cert yang dipakai Rocket, baru fallback lama.
            java.io.File ca = new java.io.File(ctx.getFilesDir(), "tls/ca.pem");
            if (!ca.isFile()) {
                android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String dataDir = TgBackup.amanString(sp, KEY_DATA_DIR, dataDirBawaanSegar());
                if (dataDir == null || dataDir.trim().isEmpty()) dataDir = dataDirBawaanSegar();
                ca = new java.io.File(dataDir, "tls/ca.pem");
            }
            if (!ca.isFile()) return null;
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.KeyStore ks = java.security.KeyStore.getInstance(
                    java.security.KeyStore.getDefaultType());
            ks.load(null, null);
            try (java.io.InputStream in = new java.io.FileInputStream(ca)) {
                int i = 0;
                for (java.security.cert.Certificate cert : cf.generateCertificates(in)) {
                    ks.setCertificateEntry("ca-" + (i++), cert);
                }
            }
            if (ksKosong(ks)) return null;
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tmf.getTrustManagers(), acakSsl());
            return sc.getSocketFactory();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean ksKosong(java.security.KeyStore ks) {
        try {
            return !ks.aliases().hasMoreElements();
        } catch (Exception e) {
            return true;
        }
    }

    private static volatile long ipCacheTime = 0;
    private static volatile String ipCache = "";
    /** Kunci pasangan cache IP: dibaca UI thread tiap detik sekaligus health
     *  worker, tulis berurutan tanpa kunci bisa memberi versi campur. */
    private static final Object IP_LOCK = new Object();

    /** True bila cache IP masih segar (umur < 60 dtk, isi tak kosong). Murni.
     *  IP LAN/STB jarang berubah; TTL 60 dtk (dulu 3 dtk) agar tick UI 1-2 dtk
     *  + health 30 dtk tak membayar enumerasi interface (ioctl per kartu +
     *  isUp + sortir) tiap detik di CPU ARMv7. Keterlambatan deteksi IP baru
     *  maks 1 menit masih aman (restart butuh IP stabil 2x tick). */
    static boolean ipCacheSegar(long kini, long cacheTime, String cache) {
        return cache != null && !cache.isEmpty() && kini >= cacheTime
                && kini - cacheTime < 60_000;
    }
    private static volatile long collectCacheTime = 0;
    private static volatile List<String> collectCache = new ArrayList<>();
    private static final Object COLLECT_LOCK = new Object();

    /** IP lokal pertama (untuk akses dari perangkat lain di jaringan sama).
     *  Di-cache 60 detik agar tidak enumerasi network interface tiap detik (dipanggil UI). */
    public static String localIp() {
        long now = SystemClock.elapsedRealtime();
        synchronized (IP_LOCK) {
            if (ipCacheSegar(now, ipCacheTime, ipCache)) {
                return ipCache;
            }
        }
        List<String> ips = collectIps();
        String pilih = "127.0.0.1";
        if (!ips.isEmpty()) {
            pilih = ips.get(0);
            for (String ip : ips) {
                if (ip != null && !ip.isEmpty() && ip.indexOf(':') < 0) {
                    pilih = ip;
                    break;
                }
            }
        }
        synchronized (IP_LOCK) {
            ipCache = pilih;
            ipCacheTime = now;
        }
        return pilih;
    }

    /** URL akses lengkap dari perangkat lain (selalu HTTPS; HTTP tak bisa dipakai). */
    public static String localUrl(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String port = effectivePort(sp);
        return "https://" + formatHostUntukUrl(localIp()) + ":" + port.trim();
    }

    private void setStatus(String text) {
        statusLine = text;
    }

    /** Bersihkan sisa ekstrak yatim; file .tmp unduhan dipertahankan untuk resume.
     *  Dikunci KUNCI_WEBVAULT bersama Updater agar sapu tak berebut rename swap.
     *  Staging yang sedang diekstrak/ditukar dilindungi registry STAGING_AKTIF
     *  (nama unik saja tak cukup: polanya cocok ke semua staging); yang dibuang
     *  hanya yatim crash/versi lama. */
    private void cleanupTempFiles(String dataDir) {
        synchronized (Updater.KUNCI_WEBVAULT) {
            cleanupTempFilesTerkunci(dataDir);
        }
    }

    private void cleanupTempFilesTerkunci(String dataDir) {
        // File .tmp unduhan (binary/web-vault) SENGAJA dipertahankan agar Start
        // berikutnya melanjutkan via rentang byte HTTPS (Range, hemat kuota ~15/35 MB).
        // Updater menghapusnya sendiri bila korup/checksum tak cocok.
        // Hanya folder staging yatim yang dibersihkan di sini (termasuk
        // web-vault.new lama + web-vault.new-<cap> unik yang yatim crash).
        try {
            File induk = new File(dataDir);
            File[] isi = induk.listFiles();
            if (isi != null) {
                for (File f : isi) {
                    if (Updater.sisaStagingWebVault(f.getName())
                            && !Updater.stagingAktif(f.getName())) {
                        Updater.deleteRecursive(f);
                    }
                }
            }
        } catch (Exception ignored) {
            Updater.deleteRecursive(new File(dataDir, "web-vault.new"));
        }
        // Pulihkan sisa swap web-vault yang terpotong crash: bila folder aktif
        // hilang/rusak tapi .bak ada, kembalikan; bila aktif sehat, buang .bak.
        try {
            java.io.File target = new java.io.File(dataDir, "web-vault");
            java.io.File bak = new java.io.File(dataDir, "web-vault.bak");
            if (bak.exists()) {
                boolean aktifSehat = target.exists()
                        && new java.io.File(target, "index.html").exists();
                if (!aktifSehat) {
                    Updater.deleteRecursive(target);
                    if (!bak.renameTo(target)) {
                        Updater.deleteRecursive(bak);
                    }
                } else {
                    Updater.deleteRecursive(bak);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Pastikan binary vaultwarden siap dipakai. Prioritas:
     *  1) binary yang ditaruh manual di folder data (mis. /sdcard/vaultwarden) —
     *     disalin ke internal karena /sdcard tidak bisa dieksekusi (noexec),
     *  2) cache internal milik APK ini (tidak diunduh ulang),
     *  3) unduh dari release repo (dibangun dari sumber resmi, verifikasi SHA-256).
     *  Return null bila gagal (status sudah diisi pesan). */
    private File ensureBinary() {
        File binDir = new File(getFilesDir(), "bin");
        if (!binDir.exists() && !binDir.mkdirs()) {
            appendLog("[app] Gagal membuat folder binary.");
            return null;
        }
        // STB kernel lama: pastikan shim duluan agar semua smoke test --version
        // di bawah (cache, manual, unduhan) menilai kondisi start sebenarnya.
        // Tanpa ini binary bagus gagal uji saat shim belum ada (bin kosong).
        // Murah bila shim sudah valid (tanpa jaringan).
        if (KernelCompat.legacyPerangkat()) {
            try {
                Updater.ensureShimFile(this);
            } catch (Exception abaikan) {
                // Gagal unduh shim (mis. offline): smoke test di bawah yang
                // menentukan, lalu pesan unduh binary yang menjelaskan.
            }
        }
        File out = new File(binDir, "vaultwarden-" + ABI);
        File verFile = new File(binDir, "version.txt");
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String dataDir = TgBackup.amanString(sp, KEY_DATA_DIR, dataDirBawaanSegar());
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = dataDirBawaanSegar();
        }

        // Revisi patch binary: bila CI memperbaiki binary tanpa ganti versi Vaultwarden
        // (mis. patch TLS favicon), cache lama wajib diunduh ulang sekali.
        boolean butuhRefresh = perluRefreshPatch(TgBackup.amanString(sp, KEY_BIN_PATCH, ""));

        // 1) Binary update terbaru hasil tombol Perbarui (KEY_UPDATE_VERSION).
        //    Didahulukan agar Start tidak memakai binary lama selamanya.
        //    Dilewati bila revisi patch berubah agar binary basi tidak dipakai terus.
        if (!butuhRefresh && isValidBinary(out)) {
            String updated = TgBackup.amanString(sp, KEY_UPDATE_VERSION, "");
            if (updated != null && !updated.isEmpty()) {
                try {
                    if (detectBinaryVersion(out)) {
                        String pin = pinBinaryTersimpan(sp);
                        if (!cacheSesuaiPin(pin, binaryVersion)) {
                            String realCache = Updater.parseBinaryVersion(binaryVersion);
                            appendLog("[app] Binary cache v"
                                    + (realCache == null ? "?" : realCache)
                                    + " bukan versi pilihan v" + pin
                                    + " - unduh ulang versi pilihan.");
                        } else {
                            appendLog("[app] Binary update terbaru dipakai: " + out.getAbsolutePath());
                            // Visibilitas: file manual tak dipakai selama cache update
                            // aktif (bukan diabaikan diam-diam seperti sebelumnya).
                            File manualLewat = new File(dataDir, "vaultwarden-" + ABI);
                            if (isValidBinary(manualLewat)) {
                                appendLog("[app] Binary manual di folder data dilewati:"
                                        + " cache update (" + updated + ") sedang aktif.");
                            }
                            return out;
                        }
                    } else {
                        appendLog("[app] Binary update gagal smoke test --version - diunduh ulang.");
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // 2) Binary dari folder data (user menaruh sendiri di /sdcard/vaultwarden).
        // Cek kanonis dini: symlink folder data yang ditukar sebelum salin
        // wajib menggagalkan jalur manual, bukan menyalin file asing.
        boolean dataKanonisOk = dataDirKanonisAman(dataDir);
        if (!dataKanonisOk) {
            appendLog("[app] Folder data tak valid/symlink asing - jalur manual dilewati.");
        }
        File userBin = new File(dataDir, "vaultwarden-" + ABI);
        if (dataKanonisOk && isValidBinary(userBin)) {
            String wantSha = TgBackup.amanString(sp, KEY_BIN_SHA, "");
            if (wantSha != null && !wantSha.trim().isEmpty()) {
                // Salin dulu ke internal privat, baru hash + uji salinannya:
                // file di /sdcard bisa ditukar app lain di jeda hash-vs-salin
                // (TOCTOU) sehingga byte yang dieksekusi tak terverifikasi.
                // Salinan internal (privat milik app) tak bisa ditukar pihak luar.
                File tmpManual = new File(binDir, "vaultwarden-" + ABI + ".manual-tmp");
                try {
                    copyBinary(userBin, tmpManual);
                    String gotSha = Updater.sha256Hex(tmpManual);
                    if (gotSha == null || !gotSha.equalsIgnoreCase(wantSha.trim())) {
                        appendLog("[app] Binary manual DITOLAK: SHA-256 tidak cocok"
                                + " dengan pengaturan. Cek kembali file/SHA-nya.");
                        setStatus("Binary manual ditolak: SHA-256 tidak cocok.");
                        TgBackup.sendMessage(this, "Binary manual ditolak: SHA-256 tidak cocok.");
                    } else {
                    // Uji di file sementara dulu: binary manual korup tak boleh
                    // menghancurkan cache yang sedang jalan (STB offline tak bisa
                    // unduh ulang). Penimpaan hanya bila smoke test lolos.
                    if (!detectBinaryVersion(tmpManual)) {
                            appendLog("[app] Binary manual GAGAL smoke test --version"
                                    + " (arsitektur salah/rusak?) - diabaikan,"
                                    + " cache lama dipertahankan.");
                        } else if (!dataDirKanonisAman(dataDir)) {
                            appendLog("[app] Binary manual DITOLAK: folder data berubah"
                                    + " saat penyalinan (symlink asing?) - dibatalkan.");
                        } else {
                            String pinManual = pinBinaryTersimpan(sp);
                            String realManual = Updater.parseBinaryVersion(binaryVersion);
                            if (!cacheSesuaiPin(pinManual, binaryVersion)) {
                                appendLog("[app] Binary manual DITOLAK: versi v"
                                        + (realManual == null ? "?" : realManual)
                                        + " bukan versi pilihan v" + pinManual + ".");
                                setStatus("Binary manual ditolak: bukan versi pilihan v"
                                        + pinManual + ".");
                                TgBackup.sendMessage(this, "Binary manual ditolak:"
                                        + " bukan versi pilihan v" + pinManual + ".");
                            } else {
                                gantiAtomik(tmpManual, out);
                                writeText(verFile, Updater.appVersionName(this));
                                // Catat versi terpasang agar cek update tak mengunduh ulang
                                // binary manual yang memang lebih lama dari rilis.
                                if (realManual != null && !realManual.isEmpty()) {
                                    sp.edit().putString(KEY_UPDATE_VERSION, realManual).apply();
                                }
                                appendLog("[app] Binary dari folder data dipakai (SHA-256 cocok).");
                                return out;
                            }
                            }
                        }
                    } catch (Exception e) {
                        appendLog("[app] Gagal memakai binary dari folder data: " + e);
                    } finally {
                        try {
                            tmpManual.delete();
                        } catch (Exception ignored) {
                        }
                    }
            } else {
                appendLog("[app] Binary manual DITOLAK: SHA-256 belum diisi"
                        + " di pengaturan. Isi SHA-256 dulu demi keamanan.");
                setStatus("Binary manual ditolak: SHA-256 belum diisi di pengaturan.");
                TgBackup.sendMessage(this, "Binary manual ditolak: SHA-256 belum diisi.");
            }
        }

        // 3) Cache internal milik APK ini (version.txt = versi APK saat diunduh).
        //    Dilewati bila revisi patch berubah agar binary basi tidak dipakai terus.
        if (!butuhRefresh && isValidBinary(out) && verFile.exists()) {
            try {
                if (Updater.appVersionName(this).equals(readText(verFile))) {
                    if (detectBinaryVersion(out)) {
                        String pinCache = pinBinaryTersimpan(sp);
                        if (!cacheSesuaiPin(pinCache, binaryVersion)) {
                            String realCache2 = Updater.parseBinaryVersion(binaryVersion);
                            appendLog("[app] Binary cache v"
                                    + (realCache2 == null ? "?" : realCache2)
                                    + " bukan versi pilihan v" + pinCache
                                    + " - unduh ulang versi pilihan.");
                        } else {
                            return out;
                        }
                    } else {
                        appendLog("[app] Binary cache gagal smoke test --version - diunduh ulang.");
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 4) Unduh dari release repo.
        // Throttle: unduhan perbaikan yang terakhir gagal tak dicoba tiap Start
        // bila cache valid masih ada (hemat kuota + Start cepat); dicoba lagi
        // setelah jeda. Instalasi pertama (tanpa cache) selalu mencoba.
        if (butuhRefresh && isValidBinary(out)) {
            // Baca tahan-korup seperti pembaca kunci yang sama di AutoUpdate:
            // getLong mentah melempar ClassCastException tiap Start bila prefs
            // dikorup bertipe String lalu jatuh ke 0 (coba unduh tiap Start,
            // bakar kuota, tak sembuh sendiri).
            long gagalAt = TgBackup.amanLong(sp, KEY_BIN_DL_GAGAL_AT, 0);
            if (!bolehCobaUnduhLagi(gagalAt, SystemClock.elapsedRealtime())) {
                try {
                    if (detectBinaryVersion(out)
                            && cacheSesuaiPin(pinBinaryTersimpan(sp), binaryVersion)) {
                        String realTunda = Updater.parseBinaryVersion(binaryVersion);
                        appendLog("[app] Pakai binary cache v"
                                + (realTunda == null ? "?" : realTunda)
                                + "; unduh perbaikan dicoba lagi nanti.");
                        return out;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        try {
            String msg = Updater.downloadBinary(this, out);
            appendLog("[app] " + msg);
            if (butuhRefresh) {
                appendLog("[app] Binary perbaikan (patch favicon) terpasang.");
            }
            sp.edit().putString(KEY_BIN_PATCH, String.valueOf(BIN_PATCH_REV))
                    .remove(KEY_BIN_DL_GAGAL_AT).apply();
            writeText(verFile, Updater.appVersionName(this));
            if (!detectBinaryVersion(out)) {
                appendLog("[app] FATAL: binary hasil unduh gagal smoke test --version"
                        + " (arsitektur salah/rusak?) - tidak dipakai.");
                setStatus("Binary hasil unduh rusak - coba Start ulang.");
                try {
                    out.delete();
                    verFile.delete();
                } catch (Exception ignored) {
                }
                return null;
            }
            return out;
        } catch (Exception e) {
            // Catat kegagalan unduhan perbaikan agar Start berikut tak
            // langsung mencoba lagi bila cache valid masih ada (throttle di
            // atas). Kegagalan lain (instalasi pertama/kuncian) tak dicatat
            // agar tetap dicoba tiap Start sesuai desain.
            if (butuhRefresh) {
                try {
                    sp.edit().putLong(KEY_BIN_DL_GAGAL_AT,
                            SystemClock.elapsedRealtime()).apply();
                } catch (Exception ignored) {
                }
            }
            // Versi pilihan gagal dipenuhi (offline/asset belum ada) tapi cache
            // valid masih ada: pakai cache agar server tetap jalan; kuncian
            // dicoba lagi saat Start berikutnya (bukan gagal total).
            // Kuncian versi dihormati: cache beda versi tak boleh dipakai
            // diam-diam agar user tak jalan di versi yang salah.
            if (isValidBinary(out)) {
                try {
                    if (detectBinaryVersion(out)) {
                        String pinDarurat = pinBinaryTersimpan(sp);
                        if (!cacheSesuaiPin(pinDarurat, binaryVersion)) {
                            String realTolak = Updater.parseBinaryVersion(binaryVersion);
                            appendLog("[app] Versi pilihan v" + pinDarurat
                                    + " gagal diunduh; cache v"
                                    + (realTolak == null ? "?" : realTolak)
                                    + " bukan versi pilihan - server TIDAK start.");
                            setStatus("Versi pilihan v" + pinDarurat
                                    + " gagal diunduh; cache beda versi ditolak.");
                        } else {
                            String realDarurat = Updater.parseBinaryVersion(binaryVersion);
                            appendLog("[app] Versi pilihan gagal diunduh ("
                                    + e.getMessage() + ") - pakai binary cache v"
                                    + (realDarurat == null ? "?" : realDarurat)
                                    + " sementara.");
                            return out;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            // Pesan Updater sudah ramah (isi saran koneksi); jangan ditimpa pesan generik.
            String ramah = e.getMessage() != null && e.getMessage().contains("Cek ")
                    ? e.getMessage() : Updater.pesanGalatUnduh("Unduh binary", e);
            appendLog("[app] Gagal unduh binary: " + e
                    + "\n[app] Saran: " + Updater.saranKoneksi(e)
                    + " Bila STB offline, pakai cara manual di README"
                    + " (taruh binary di folder data).");
            setStatus("Binary belum tersedia - " + ramah);
            return null;
        }
    }

    private boolean isValidBinary(File f) {
        if (f == null || !f.exists() || f.length() < 1_000_000) {
            return false;
        }
        try (InputStream in = new java.io.FileInputStream(f)) {
            // Header ELF 20 byte: magic (0-3) + e_machine little-endian (18-19).
            // 40 = ARM; tolak x86_64 (62)/AArch64 (183) yang lolos cek magic.
            byte[] h = new byte[20];
            int off = 0;
            while (off < h.length) {
                int n = in.read(h, off, h.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            if (off < h.length || h[0] != 0x7F || h[1] != 'E'
                    || h[2] != 'L' || h[3] != 'F') {
                return false;
            }
            int machine = (h[18] & 0xFF) | ((h[19] & 0xFF) << 8);
            return machine == 40;
        } catch (Exception e) {
            return false;
        }
    }

    private void copyBinary(File src, File dst) throws IOException {
        // Tulis ke tmp + rename agar biner parsial (storage penuh/crash) tak
        // lolos isValidBinary() yang cek ukuran + magic + e_machine ARM.
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        try {
            try (InputStream in = new java.io.FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
                fos.getFD().sync();
            }
            // ownerOnly=true: hanya UID app yang membaca binary (proses anak jalan
            // sebagai UID sama) — tidak perlu world-readable.
            if (!tmp.setReadable(true, true) || !tmp.setExecutable(true, true)) {
                throw new IOException("Gagal pasang izin eksekusi binary (storage tak dukung exec).");
            }
            gantiAtomik(tmp, dst);
        } catch (IOException | RuntimeException e) {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    private File extractWebVault() {
        // APK tidak lagi membundel web-vault; unduh sekali lewat tombol
        // "Update Web Vault" (tersimpan di <data>/web-vault dan dipakai ulang).
        File dir = new File(getFilesDir(), "web-vault");
        File index = new File(dir, "index.html");
        if (index.exists()) {
            return dir;
        }
        appendLog("[app] Web vault belum terpasang - tekan 'Update Web Vault' "
                + "untuk mengunduh sekali (~35 MB).");
        return null;
    }

    /** Output mentah "--version" terakhir (maks ~8 KB) untuk smoke test panic kernel. */
    static volatile String lastVersionOutput = "";

    /** Pastikan shim getrandom ada (khusus kernel lama); null + status bila gagal. */
    private File ensureShim() {
        try {
            File shim = Updater.ensureShimFile(this);
            appendLog("[app] Shim getrandom dipakai: " + shim.getAbsolutePath());
            return shim;
        } catch (Exception e) {
            String ramah = e.getMessage() != null ? e.getMessage() : e.toString();
            appendLog("[app] Gagal pasang shim getrandom: " + e
                    + "\n[app] Saran: " + Updater.saranKoneksi(
                            e instanceof Exception ? (Exception) e : null));
            setStatus("Shim getrandom belum tersedia - " + ramah);
            return null;
        }
    }

    /** Kapan smoke test --version terakhir gagal (elapsed; anti unduh ulang tiap Start). */
    private static volatile long smokeGagalAt = 0;
    /** Jeda ulang smoke test sesudah gagal (hemat proses di STB lambat). */
    static final long SMOKE_GAGAL_TTL_MS = 60_000;
    /** True bila smoke test gagal belum lama (murni agar bisa unit test). */
    static boolean smokeGagalBaruSaja(long kiniElapsed, long gagalAt, long jedaMs) {
        if (gagalAt == 0 || kiniElapsed < gagalAt) {
            return false;
        }
        return kiniElapsed - gagalAt < jedaMs;
    }
    /** Smoke test --version; false bila binary tak bisa dieksekusi. */
    private boolean detectBinaryVersion(File binary) {
        Process p = null;
        BufferedReader r = null;
        // Di luar try agar terlihat di catch: lokal di dalam try tak tampak
        // di catch (gagal kompilasi cannot find symbol).
        final java.util.concurrent.atomic.AtomicBoolean versiSelesai =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread versiWatchdog = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(), "--version")
                    .redirectErrorStream(true);
            if (KernelCompat.legacyPerangkat()) {
                File shim = new File(getFilesDir(), "bin/" + KernelCompat.SHIM_ASSET);
                if (shim.exists()) {
                    pb.environment().put("LD_PRELOAD", shim.getAbsolutePath());
                }
            }
            p = pb.start();
            // Watchdog 10 detik: readLine() memblokir selamanya bila binary
            // macet tanpa output; destroy terjadwal menutup pipa sehingga
            // baca balik EOF dan thread Start tak nyangkut "Bekerja...".
            final Process versiProc = p;
            // AtomicBoolean (bukan boolean[]): tulis thread Start wajib terlihat
            // thread watchdog tanpa synchronized, bila tidak watchdog bisa
            // destroy proses yang sudah selesai atau melewatkan timeout.
            versiWatchdog = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!versiSelesai.get()) {
                        try {
                            versiProc.destroy();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }, "vw-version-watchdog");
            versiWatchdog.setDaemon(true);
            versiWatchdog.start();
            r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            // Cukup 2 baris versi pertama (saring noise linker STB lama), lalu
            // matikan proses sedini mungkin agar Start tak tertahan belasan detik
            // bila binary aneh/macet.
            String first = null;
            String baris;
            int dibaca = 0;
            // Batas total 50 baris (termasuk noise): binary aneh yang mengoceh
            // tanpa henti tak boleh menggantung thread Start selamanya.
            int total = 0;
            while (total < 50 && (baris = r.readLine()) != null) {
                total++;
                if (isNoiseLinker(baris)) {
                    continue;
                }
                if (first == null) {
                    first = baris;
                }
                if (++dibaca >= 2) {
                    break;
                }
            }
            versiSelesai.set(true);
            versiWatchdog.interrupt();
            lastVersionOutput = first == null ? "" : first.trim();
            smokeGagalAt = 0;
            p.destroy();
            try {
                // Timeout 2 detik di SEMUA API (waitFor(timeout) hanya API 26+).
                waitForOrKill(p, 2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (first == null || first.trim().isEmpty()) {
                binaryVersion = "?";
                lastVersionOutput = "";
                smokeGagalAt = SystemClock.elapsedRealtime();
                return false;
            }
            binaryVersion = first.trim();
            return true;
        } catch (Exception e) {
            // Hentikan watchdog yang menunggu 10 detik agar thread Start gagal
            // tak menyisakan thread daemon tiap Start (bocor saat exec ditolak).
            try {
                versiSelesai.set(true);
            } catch (Exception ignored) {
            }
            try {
                if (versiWatchdog != null) {
                    versiWatchdog.interrupt();
                }
            } catch (Exception ignored) {
            }
            binaryVersion = "?";
            lastVersionOutput = "";
            smokeGagalAt = SystemClock.elapsedRealtime();
            return false;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
            if (p != null) {
                p.destroy();
            }
        }
    }

    private static String lanHost() {
        List<String> ips = collectIps();
        if (ips.isEmpty()) {
            return "127.0.0.1";
        }
        // Prefer IPv4 untuk DOMAIN/URL: IPv6 tanpa kurung merusak parsing port.
        for (String ip : ips) {
            if (ip != null && !ip.isEmpty() && ip.indexOf(':') < 0) {
                return ip;
            }
        }
        return ips.get(0);
    }

    /** Format host untuk URL: IPv6 dibungkus kurung agar port tak ambigu. Murni. */
    static String formatHostUntukUrl(String host) {
        if (host == null || host.isEmpty()) {
            return "127.0.0.1";
        }
        String h = host.trim();
        if (h.indexOf(':') >= 0 && !(h.startsWith("[") && h.endsWith("]"))) {
            return "[" + h + "]";
        }
        return h;
    }

    private static List<String> collectIps() {
        synchronized (COLLECT_LOCK) {
            // Monotonik: wall-clock mundur membuat now-cache negatif dan cache basi beku.
            long now = SystemClock.elapsedRealtime();
            // TTL 60 dtk (dulu 5 dtk): tick health 30 dtk/2 mnt + URL UI 10 dtk
            // selalu gagal-hit lalu membayar getNetworkInterfaces + isUp()
            // tiap kartu tiap tick. IP praktis statis sehingga basi 1 menit
            // tak masalah; Start selalu bisa paksa segar via writeText ips.txt.
            if (now - collectCacheTime < 60_000 && !collectCache.isEmpty()) {
                return collectCache;
            }
            List<String> ips = new ArrayList<>();
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) {
                        continue;
                    }
                    for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                        if (addr instanceof java.net.Inet4Address) {
                            ips.add(addr.getHostAddress());
                        } else if (addr instanceof java.net.Inet6Address) {
                            // Kupas zona (%wlan0) agar stabil sebagai SAN sertifikat.
                            String mentah = addr.getHostAddress();
                            int persen = mentah.indexOf('%');
                            String bersih = persen < 0 ? mentah : mentah.substring(0, persen);
                            if (!bersih.isEmpty() && !bersih.equalsIgnoreCase("::1")
                                    && !bersih.startsWith("fe80:")
                                    && !bersih.startsWith("FE80:")) {
                                ips.add(bersih);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            // Urutkan: urutan enumerasi interface tak stabil sehingga string
            // ips.txt bisa beda tiap Start dan memicu regen sertifikat sia-sia.
            Collections.sort(ips);
            // Tak-berubah: pemanggil hanya membaca (kecuali prepareTls, ia menyalin
            // sendiri) sehingga cache-hit tanpa alokasi salinan per panggilan.
            collectCache = Collections.unmodifiableList(ips);
            collectCacheTime = now;
            return collectCache;
        }
    }

    private File prepareTls(File dataFolder) {
        try {
            List<String> ips = new ArrayList<>(collectIps());
            ips.add(0, "127.0.0.1");
            ips.add("::1");
            // SAN DNS sengaja kosong: fitur domain lokal dihapus (butuh DNS
            // sendiri di jaringan). TlsCert.daftarDns dipertahankan sebagai
            // util teruji untuk pemakaian mendatang; format "|dns=" penanda
            // dipertahankan agar ips.txt lama tak memicu regen massal.
            List<String> dns = new ArrayList<>();
            String cur = joinIps(ips) + "|dns=" + joinIps(dns);

            // Kunci TLS di internal (bukan /sdcard FAT yang chmod-nya tak berlaku):
            // migrasi sekali dari folder data lama agar CA tetap sama (HP lain
            // tak perlu install ulang), lalu pakai internal seterusnya.
            File internal = new File(getFilesDir(), "tls");
            File tlsLama = new File(dataFolder, "tls");
            migrasiTlsKeInternal(tlsLama, internal);
            File ipFile = new File(internal, "ips.txt");
            File dir = ensureCertWithIps(internal, ipFile, ips, dns, cur);
            if (dir == null) {
                File lama = new File(dataFolder, "tls");
                dir = ensureCertWithIps(lama, new File(lama, "ips.txt"), ips, dns, cur);
            }
            if (dir != null) {
                bersihKunciTlsLama(tlsLama, dir, internal);
                appendLog("[app] Sertifikat HTTPS: " + new File(dir, "cert.pem").getAbsolutePath());
                appendLog("[app] CA untuk HP lain: " + new File(dir, "ca.pem").getAbsolutePath());
                try {
                    String kanon = dir.getCanonicalPath();
                    if (kanon.startsWith("/sdcard")
                            || kanon.startsWith("/storage")) {
                        appendLog("[app] PERINGATAN: kunci TLS di storage publik"
                                + " (FAT, chmod tak berlaku). Siapa pun berizin baca storage"
                                + " bisa menyalin ca-key.pem/key.pem.");
                    }
                } catch (Exception ignored) {
                }
            }
            return dir;
        } catch (Exception e) {
            appendLog("[app] Gagal siapkan TLS: " + e);
            return null;
        }
    }

    /** Salin tls lama (/sdcard) ke internal sekali bila internal kosong.
     *  Best-effort: gagal migrasi bukan fatal (fallback folder lama dipakai). */
    private void migrasiTlsKeInternal(File lama, File baru) {
        try {
            if (!new File(lama, "ca.pem").isFile()
                    || !new File(lama, "ca-key.pem").isFile()) {
                return;
            }
            if (new File(baru, "ca.pem").isFile()
                    && new File(baru, "ca-key.pem").isFile()) {
                return;
            }
            if (!baru.exists() && !baru.mkdirs()) {
                return;
            }
            for (String nama : new String[]{"ca.pem", "ca-key.pem", "cert.pem",
                    "key.pem", "ips.txt", "version.txt"}) {
                File asal = new File(lama, nama);
                File tujuan = new File(baru, nama);
                if (asal.isFile() && !tujuan.isFile()) {
                    TgBackup.copyFile(asal, tujuan);
                }
            }
            appendLog("[app] TLS dimigrasi ke internal (kunci tak lagi di /sdcard).");
        } catch (Exception ignored) {
        }
    }

    /** Hapus kunci privat lama di folder data publik setelah internal terbukti jadi.
     *  Hanya ca-key.pem/key.pem; sertifikat publik dibiarkan untuk fallback bila
     *  suatu saat internal gagal. Best-effort: kunci di /sdcard (FAT) bisa dibaca
     *  app berizin storage sehingga tak boleh mengendap pasca-migrasi. */
    private void bersihKunciTlsLama(File lama, File dirAktif, File internal) {
        try {
            if (lama == null || dirAktif == null || internal == null) {
                return;
            }
            String kanonAktif = dirAktif.getCanonicalPath();
            String kanonInternal = internal.getCanonicalPath();
            // Fallback folder lama: kunci masih dipakai, jangan hapus.
            if (!kanonAktif.equals(kanonInternal)) {
                return;
            }
            String kanonLama = lama.getCanonicalPath();
            // Folder data memang di internal: tak ada yang perlu dibersihkan.
            if (kanonLama.equals(kanonInternal)) {
                return;
            }
            for (String nama : new String[]{"ca-key.pem", "key.pem"}) {
                try {
                    File kunci = new File(lama, nama);
                    if (kunci.isFile() && !kunci.delete()) {
                        appendLog("[app] PERINGATAN: kunci lama " + nama
                                + " tak terhapus dari storage publik - hapus manual.");
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private File ensureCertWithIps(File tlsDir, File ipFile, List<String> ips,
            List<String> dns, String cur) throws Exception {
        String saved = readText(ipFile);
        // Regen dipaksa via flag (bukan hapus-dulu): TlsCert.ensure menukar
        // atomik (.baru -> aktif) sehingga leaf lama yang masih bagus tetap
        // dipakai bila generate gagal (mis. storage penuh) — server tak mati
        // sia-sia. Hanya leaf yang dibuat ulang; CA (ca.pem) dipertahankan
        // agar HP lain tak perlu install ulang. Penanda hilang (saved null)
        // ikut regen: daun lama bisa berisi SAN basi.
        boolean ipBerubah = saved == null || !saved.equals(cur);
        if (ipBerubah) {
            appendLog("[app] IP berubah - regenerasi sertifikat server (CA tetap).");
        }
        File dir = TlsCert.ensure(tlsDir, ips, dns, ipBerubah);
        if (dir != null) {
            writeText(ipFile, cur);
            // Sertifikat bisa baru dibuat: buang cache SSL agar health check
            // memakai CA terbaru, bukan trust-all lama. Cap CA ikut reset
            // agar gerbang TTL tak menyajikan cap basi 60 dtk.
            sslFactory = null;
            capCaStat = Long.MIN_VALUE;
            capCaWaktu = 0;
        }
        return dir;
    }

    private static String joinIps(List<String> ips) {
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(ip);
        }
        return sb.toString();
    }

    private static String readText(File f) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeText(File f, String text) throws Exception {
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new FileOutputStream(f, false), StandardCharsets.UTF_8)) {
            w.write(text);
        }
    }




    private String getPid(Process p) {
        int pid = pidOf(p);
        return pid < 0 ? "?" : String.valueOf(pid);
    }

    // Cache Method "pid" (API 26+): lookup refleksi hanya sekali, bukan tiap panggil.
    private static volatile java.lang.reflect.Method pidMethod;
    private static volatile boolean pidMethodLookedUp = false;

    private static int pidOf(Process p) {
        if (p == null) {
            return -1;
        }
        try {
            java.lang.reflect.Method m = pidMethod;
            if (m == null && !pidMethodLookedUp) {
                synchronized (ServerService.class) {
                    if (!pidMethodLookedUp) {
                        try {
                            pidMethod = Process.class.getMethod("pid");
                        } catch (Throwable ignored) {
                        }
                        pidMethodLookedUp = true;
                    }
                    m = pidMethod;
                }
            }
            if (m == null) {
                return -1;
            }
            // Process.pid() mengembalikan long (bukan int): pakai Number agar tak ClassCastException.
            return ((Number) m.invoke(p)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    // PID anak vaultwarden di-cache 30 dtk agar info RAM (dipanggil UI tiap
    // 5 dtk) tidak memindai /proc terus-menerus.
    private static volatile int cachedChildPid = -1;
    private static volatile long cachedChildPidAt = 0;
    // TTL 120 dtk (PID stabil selama proses hidup): pindai /proc penuh
    // tiap 30 dtk boros I/O di API<26 yang selalu lewat jalur ini.
    private static final long CHILD_PID_TTL_MS = 120_000;

    /** RAM (VmRSS, kB) proses vaultwarden; -1 bila tidak terbaca. */
    public static long processRssKb() {
        // Server mati tak punya PID: jangan pindai /proc sia-sia.
        if (!running) {
            return -1;
        }
        int pid = -1;
        Process p = process;
        if (p != null) {
            pid = pidOf(p);
        }
        if (pid < 0) {
            long now = SystemClock.elapsedRealtime();
            int cached = cachedChildPid;
            if (cached >= 0 && now - cachedChildPidAt < CHILD_PID_TTL_MS
                    && pidMilikiServer(cached)) {
                pid = cached;
            } else {
                pid = findChildPid();
                cachedChildPid = pid;
                cachedChildPidAt = now;
            }
        }
        if (pid < 0) {
            return -1;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream("/proc/" + pid + "/status"),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    // Tanpa split regex (compile Pattern per baris): "VmRSS:   1234 kB".
                    String angka = line.substring(6).trim();
                    int sp = angka.indexOf(' ');
                    if (sp >= 0) {
                        angka = angka.substring(0, sp);
                    }
                    try {
                        return Long.parseLong(angka);
                    } catch (NumberFormatException nfe) {
                        return -1;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Cari pid anak vaultwarden via /proc (fallback API < 26 tanpa Process.pid()). */
    private static int findChildPid() {
        try {
            File[] dirs = new File("/proc").listFiles();
            if (dirs == null) {
                return -1;
            }
            int myUid = android.os.Process.myUid();
            int myPid = android.os.Process.myPid();
            for (File d : dirs) {
                String name = d.getName();
                if (name.isEmpty() || !Character.isDigit(name.charAt(0))) {
                    continue;
                }
                try {
                    int pid = Integer.parseInt(name);
                    if (pid == myPid) {
                        continue;
                    }
                    if (readProcUid(d) != myUid) {
                        continue;
                    }
                    String cmd = readProcCmdline(d);
                    if (cmdlineServer(cmd)) {
                        return pid;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Bunuh proses vaultwarden lama (UID sama, bukan proses kita) yang masih
     *  nyangkut & memegang port setelah app di-restart/update. */
    private void killStaleVaultwarden() {
        try {
            File[] dirs = new File("/proc").listFiles();
            if (dirs == null) {
                return;
            }
            int myUid = android.os.Process.myUid();
            int myPid = android.os.Process.myPid();
            int childPid = runningChildPid();
            for (File d : dirs) {
                String name = d.getName();
                if (name.isEmpty() || !Character.isDigit(name.charAt(0))) {
                    continue;
                }
                try {
                    int pid = Integer.parseInt(name);
                    if (pid == myPid || pid == childPid) {
                        continue;
                    }
                    if (readProcUid(d) != myUid) {
                        continue;
                    }
                    String cmd = readProcCmdline(d);
                    if (bolehBunuhBasi(cmd)) {
                        // Verifikasi ulang tepat sebelum bunuh: start konkuren
                        // (ketuk Start 2x / auto-restart vs manual) bisa lahir di
                        // jeda cek-vs-bunuh, dan PID bisa dipakai ulang proses
                        // lain se-UID. Tanpa ini server fresh ikut terbunuh.
                        if (pid == myPid || pid == runningChildPid()) {
                            continue;
                        }
                        if (!pidMilikiServer(pid)) {
                            continue;
                        }
                        android.os.Process.killProcess(pid);
                        appendLog("[app] Proses vaultwarden lama (pid " + pid + ") dibersihkan.");
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** True bila cmdline milik binary server app (bukan smoke-test
     *  "--version" milik flow update/start konkuren). Murni.
     *  Ketat: path internal selalu ".../bin/vaultwarden-armeabi-v7a";
     *  substring longgar "bin/vaultwarden" berisiko menjodohkan file
     *  asing se-UID bila skema exec bertambah nanti. */
    static boolean cmdlineServer(String cmd) {
        return cmd != null && cmd.contains("/bin/vaultwarden-")
                && !cmd.contains("--version");
    }

    /** True bila cmdline milik server basi yang boleh dibunuh. Murni. */
    static boolean bolehBunuhBasi(String cmd) {
        return cmdlineServer(cmd);
    }

    /** True bila pid masih milik binary server (anti PID-reuse basi di cache). */
    static boolean pidMilikiServer(int pid) {
        if (pid < 0) {
            return false;
        }
        try {
            return cmdlineServer(readProcCmdline(new File("/proc/" + pid)));
        } catch (Exception e) {
            return false;
        }
    }

    private int runningChildPid() {
        return pidOf(process);
    }

    private static String readProcCmdline(File dir) {
        File f = new File(dir, "cmdline");
        if (!f.canRead()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8)
                    .replace('\u0000', ' ').trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static int readProcUid(File dir) {
        try (BufferedReader r = new BufferedReader(
                new java.io.FileReader(new File(dir, "status")))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    // Parse manual tanpa split regex: dipanggil per kandidat
                    // saat pindai /proc di STB lama.
                    int i = 4;
                    int n = line.length();
                    while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                        i++;
                    }
                    int j = i;
                    while (j < n && line.charAt(j) >= '0' && line.charAt(j) <= '9') {
                        j++;
                    }
                    if (j > i) {
                        return Integer.parseInt(line.substring(i, j));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** True bila port sedang dipakai proses lain (listening).
     *  Cek dua tumpukan (0.0.0.0 dan "::"): Rocket melayani keduanya,
     *  jadi sibuk bila salah satu tak bisa bind. Pengecualian: di perangkat
     *  tanpa stack IPv6, gagal "::" bukan berarti sibuk (lihat bisaBind).
     *  Saran pra-start saja (TOCTOU bind-lalu-lepas): penentu sah adalah
     *  gagal bind Rocket saat exec + mitigasi portDirebut di watchProcess. */
    public static boolean isPortBusy(int port) {
        if (port < 1 || port > 65535) {
            return true;
        }
        // Rocket melayani dua tumpukan; sibuk bila salah satu tak bisa bind.
        // Perangkat tanpa stack IPv6 mengembalikan bebas untuk "::" di bisaBind.
        return !bisaBind("0.0.0.0", port) || !bisaBind("::", port);
    }

    /** True bila alamat:port masih bisa di-bind (bebas). Murni agar bisa diuji. */
    static boolean bisaBind(String host, int port) {
        try (ServerSocket s = new ServerSocket()) {
            // REUSEADDR agar Start langsung setelah Stop tak dikira "port sibuk"
            // (TIME_WAIT). Dipanggil per tumpukan oleh isPortBusy (0.0.0.0 dan ::).
            try {
                s.setReuseAddress(true);
            } catch (Exception ignored) {
            }
            s.bind(new InetSocketAddress(host, port));
            return true;
        } catch (Exception e) {
            // Perangkat tanpa stack IPv6 gagal bind "::" walau IPv4 bebas:
            // kegagalan keluarga protokol bukan berarti port sibuk.
            if (host.contains(":")) {
                String rendah = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.US);
                if (rendah.contains("family") || rendah.contains("protonosupport")
                        || rendah.contains("eafnosupport") || rendah.contains("not supported")) {
                    return true;
                }
            }
            return false;
        }
    }

    /** True bila bind gagal karena hak akses (port privileged <1024 tanpa
     *  root), bukan karena dipakai proses lain. Murni I/O agar bisa diuji. */
    static boolean gagalIzinBind(String host, int port) {
        try (ServerSocket s = new ServerSocket()) {
            try {
                s.setReuseAddress(true);
            } catch (Exception ignored) {
            }
            s.bind(new InetSocketAddress(host, port));
            return false;
        } catch (Exception e) {
            String rendah = String.valueOf(e.getMessage())
                    .toLowerCase(java.util.Locale.US);
            return rendah.contains("eacces") || rendah.contains("permission denied");
        }
    }

    /** True bila port tak bisa dipakai karena butuh root (privileged),
     *  agar pesan galat tak menuduh "dipakai proses lain". */
    public static boolean portButuhRoot(int port) {
        if (port < 1 || port > 65535) {
            return false;
        }
        return gagalIzinBind("0.0.0.0", port) || gagalIzinBind("::", port);
    }

    /** N baris terakhir log (tanpa baris kosong), untuk pesan crash.
     *  Pindai mundur dari akhir buffer: hanya ekor kecil yang disalin,
     *  bukan seluruh buffer (≤300 KB) + split ribuan baris. */
    static String tailLog(int lines) {
        synchronized (logBuffer) {
            int len = logBuffer.length();
            java.util.ArrayList<String> ambil = new java.util.ArrayList<>(Math.max(0, lines));
            int end = len;
            for (int i = len - 1; i >= -1 && ambil.size() < lines; i--) {
                if (i < 0 || logBuffer.charAt(i) == '\n') {
                    int start = i + 1;
                    int e = end;
                    while (e > start && (logBuffer.charAt(e - 1) == '\r'
                            || logBuffer.charAt(e - 1) == '\n')) {
                        e--;
                    }
                    if (e > start && !rentangKosong(logBuffer, start, e)) {
                        ambil.add(logBuffer.substring(start, e));
                    }
                    end = i;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int k = ambil.size() - 1; k >= 0; k--) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(ambil.get(k));
            }
            return sb.toString();
        }
    }

    /** True bila rentang buffer hanya whitespace (tanpa alokasi substring). Murni. */
    static boolean rentangKosong(CharSequence s, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Panjang buffer log tanpa menyalin (untuk deteksi perubahan murah). */
    public static int logLength() {
        synchronized (logBuffer) {
            return logBuffer.length();
        }
    }

    /** Versi log monotonik: kunci refresh UI (anti balapan trim+append). */
    public static long logVersion() {
        return logVer;
    }

    /** Tulis satu baris log dari mana saja (UI/bot): stempel + trim + versi.
     *  Wajib dipakai penulis luar agar tak lewati batas 300 KB dan UI refresh. */
    public static void catatLog(String line) {
        if (line == null) {
            return;
        }
        String stamp;
        stamp = LOG_TS.get().format(new java.util.Date());
        String entry = stamp + " " + line;
        synchronized (logBuffer) {
            logBuffer.append(entry).append('\n');
            pangkasBufferTerkunci();
            logVer++;
        }
    }

    /** Pangkas buffer log di batas code-point (wajib dalam lock logBuffer).
     *  Belah pasangan surrogate emoji menghasilkan lone surrogate (tofu di
     *  layar / 400 Telegram) sehingga titik potong digeser bila perlu. */
    private static void pangkasBufferTerkunci() {
        if (logBuffer.length() > LOG_TRIM_THRESHOLD) {
            int potongBuf = logBuffer.length() - MAX_LOG_CHARS / 2;
            if (potongBuf > 0 && potongBuf < logBuffer.length()
                    && Character.isLowSurrogate(logBuffer.charAt(potongBuf))
                    && Character.isHighSurrogate(logBuffer.charAt(potongBuf - 1))) {
                potongBuf++;
            }
            logBuffer.delete(0, potongBuf);
        }
    }

    private static String shorten(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        int mulai = s.length() - max;
        // Jangan belah pasangan surrogate emoji (lone surrogate = teks rusak).
        if (mulai > 0 && Character.isLowSurrogate(s.charAt(mulai))
                && Character.isHighSurrogate(s.charAt(mulai - 1))) {
            mulai++;
        }
        return s.substring(mulai);
    }

    private boolean waitForOrKill(Process p, long timeoutMillis) throws InterruptedException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        // Pra-Oreo tanpa waitFor(timeout): waiter join lebih murah daripada
        // poll alive() tiap 500 ms — exitValue() melempar + isi stack trace
        // tiap proses masih hidup di ART lama.
        final java.util.concurrent.atomic.AtomicBoolean mati =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread penunggu = new Thread(() -> {
            try {
                p.waitFor();
            } catch (Exception ignored) {
            } finally {
                mati.set(true);
            }
        }, "vw-wait");
        penunggu.setDaemon(true);
        penunggu.start();
        penunggu.join(timeoutMillis);
        return mati.get();
    }

    /** Perpanjang wakelock bila server masih jalan tapi kunci lepas/kedaluwarsa.
     *  acquire() memakai timeout 12 jam agar lint lolos; tanpa segarkan,
     *  server jalan >12 jam kena Doze lalu health gagal. Dipanggil tiap healthTick. */
    private void jagaWakeLock() {
        try {
            boolean perlu;
            synchronized (ServerService.class) {
                perlu = running && autoRestart && (wakeLock == null || !wakeLock.isHeld());
            }
            if (perlu) {
                acquireWakeLock();
            }
        } catch (Exception ignored) {
        }
    }

    private void acquireWakeLock() {
        // Lepas dulu bila ada sisa (tak boleh menimpa field: kunci lama bocor
        // sampai timeout 12 jam bila referensinya hilang).
        synchronized (ServerService.class) {
            releaseWakeLockDalam();
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vaultwarden:server");
                    // Timeout 12 jam: bila jalur stop()/destroy terlewat, kunci
                    // tetap dilepas sistem agar baterai tak terkuras selamanya.
                    // Selalu pakai timeout agar lint WakelockTimeout lolos dan
                    // baterai tak terkuras bila release terlewat.
                    try {
                        wakeLock.acquire(12L * 3600 * 1000L);
                    } catch (Exception e) {
                        wakeLock = null;
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Badan pelepas tanpa kunci ulang (dipanggil dari dalam blok sinkron). */
    private void releaseWakeLockDalam() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        wakeLock = null;
    }

    private void releaseWakeLock() {
        synchronized (ServerService.class) {
            releaseWakeLockDalam();
        }
    }

    @Override
    public void onDestroy() {
        // Jangan nol-kan autoRestart/healthActive: keduanya statis agar selamat
        // dari recreate transien tanpa intent baru. Jalur STOP eksplisit sudah
        // mematikannya sebelum stopSelf; membersihkan di sini menghentikan
        // monitoring diam-diam selagi binary mungkin masih jalan.
        mainHandler.removeCallbacks(healthTick);
        mainHandler.removeCallbacks(restartTunda);
        flushLogFile();
        // Selalu destroy proses sisa di sini; aman karena jalur restart/start ulang
        // akan membuat proses baru. Tanpa ini proses yatim bocor saat service mati.
        if (process != null) {
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
        // WakeLock hanya dilepas bila proses benar-benar mati; recreate transien
        // saat server jalan mempertahankannya (dipasang ulang di onCreate).
        if (!isProcessAlive()) {
            releaseWakeLock();
        }
    }
}
