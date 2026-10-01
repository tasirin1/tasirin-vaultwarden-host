package com.tasirin.vaultwardenhost;

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
     *  kini ikut Environment dengan fallback lama agar tetap bisa start. */
    public static final String DEFAULT_DATA_DIR = defaultDataDir();

    // getExternalStorageDirectory lawas sengaja agar satu jalur kode untuk API 21-32.
    @SuppressWarnings("deprecation")
    private static String defaultDataDir() {
        try {
            java.io.File ext = android.os.Environment.getExternalStorageDirectory();
            if (ext != null) {
                return new java.io.File(ext, "vaultwarden").getAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return "/sdcard/vaultwarden";
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

    /** Kuncian versi binary user ("" = ikuti terbaru). Murni prefs. */
    static String pinBinaryTersimpan(SharedPreferences sp) {
        try {
            String pin = Updater.normalisasiPinVersi(
                    sp == null ? null : sp.getString(KEY_BIN_PILIH, ""));
            return pin == null ? "" : pin;
        } catch (Exception e) {
            return "";
        }
    }

    /** True bila binary cache (hasil smoke test di binaryVersion) boleh dipakai:
     *  tanpa kuncian selalu boleh; bila dikunci wajib sama dengan kuncian.
     *  Murni agar bisa unit test. */
    static boolean cacheSesuaiPin(String pin, String terdeteksi) {
        if (pin == null || pin.isEmpty()) {
            return true;
        }
        String real = Updater.parseBinaryVersion(terdeteksi);
        return real != null && Updater.bandingVersi(real, pin) == 0;
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
    private static final SimpleDateFormat LOG_TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

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
    public static volatile String runningAdminToken = "";
    /** Penanda versi web-vault saat server start (untuk hint restart di MainActivity). */
    public static volatile String runningWvFrom = "";

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
    /** Lolos TCP beruntun saat /alive gagal tapi port tersambung (anti livelock health). */
    private final java.util.concurrent.atomic.AtomicInteger healthTcpLolos =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** True bila Stop ditekan saat start masih persiapan (unduh binary):
     *  start dibatalkan tepat sebelum exec agar server tak jalan tanpa diminta. */
    private static volatile boolean batalStart = false;
    private static final java.util.concurrent.atomic.AtomicBoolean starting = new java.util.concurrent.atomic.AtomicBoolean(false);
    private PowerManager.WakeLock wakeLock;
    private static volatile File logFile;
    private boolean autoRestart = false;
    private int restartAttempt = 0;
    private static volatile long lastStartTime = 0;
    /** Jangkar monotonik start (elapsedRealtime); wall-clock bisa mundur. */
    private static volatile long lastStartElapsed = 0;
    private final java.util.concurrent.atomic.AtomicInteger healthFails = new java.util.concurrent.atomic.AtomicInteger(0);

    private volatile boolean healthActive = false;
    /** Cek health yang sedang jalan: tiap tick hanya satu (timeout total
     *  worst-case 32 dtk > interval cepat 30 dtk sehingga thread bisa
     *  menumpuk bila server macet). */
    private final java.util.concurrent.atomic.AtomicBoolean healthBerjalan =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Runnable healthTick = new Runnable() {
        @Override
        public void run() {
            // Jangan repost bila service sudah berhenti (cegah bocor handler).
            if (!healthActive) {
                return;
            }
            // Adaptif: tiap 30 detik di 5 menit pertama (crash dini cepat
            // ketahuan), lalu tiap 2 menit setelah server stabil.
            long delay = HEALTH_INTERVAL_MS;
            long up = SystemClock.elapsedRealtime() - lastStartElapsed;
            if (lastStartElapsed > 0 && up < HEALTH_FAST_MS) {
                delay = HEALTH_FAST_INTERVAL_MS;
            }
            mainHandler.postDelayed(this, delay);
            if (process == null || !alive(process) || !running) {
                return;
            }
            if (!healthBerjalan.compareAndSet(false, true)) {
                return;
            }
            new Thread(() -> {
                try {
                    checkHealthOnce();
                } finally {
                    healthBerjalan.set(false);
                }
            }, "vw-health").start();
        }
    };

    public static boolean start(Context context) {
        return mulaiService(context, ACTION_START);
    }

    /** Start service tahan penolakan background Android 12+
     *  (ForegroundServiceStartNotAllowedException): catat ke log, jangan crash
     *  pemanggil (bot/receiver sudah memberi tahu user secara terpisah). */
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
                try {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean(KEY_START_TERTUNDA, true).apply();
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
                    bak.delete();
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
        int alive = cobaKode(ctx, scheme, p, "/alive", https);
        String aliveErr = aliveErrTerakhir;
        int config = -1;
        String configErr = "";
        // /alive butuh DB; fallback ringan /api/config memastikan server
        // yang masih melayani tidak dibunuh sia-sia (kasus log: config 200).
        if (alive != 200) {
            config = cobaKode(ctx, scheme, p, "/api/config", https);
            configErr = aliveErrTerakhir;
        } else {
            config = -2;
        }
        boolean sehat = sehatDariKode(alive, config);
        String rincian;
        if (sehat) {
            rincian = alive == 200 ? "alive 200" : "config 200 (alive " + ringkasKode(alive, aliveErr) + ")";
        } else {
            rincian = "alive " + ringkasKode(alive, aliveErr)
                    + ", config " + ringkasKode(config, configErr);
        }
        return new HasilPing(sehat, alive, config, rincian);
    }

    private static volatile String aliveErrTerakhir = "";

    /** Satu request GET loopback; balas kode HTTP atau -1 bila gagal jaring/TLS. */
    private static int cobaKode(Context ctx, String scheme, String port, String path, boolean https) {
        HttpURLConnection c = null;
        try {
            aliveErrTerakhir = "";
            c = (HttpURLConnection) new URL(
                    scheme + "://127.0.0.1:" + port + path).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (https) {
                HttpsURLConnection hc = (HttpsURLConnection) c;
                hc.setSSLSocketFactory(loopbackSslFactory(ctx));
                hc.setHostnameVerifier((host, session) ->
                        "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
            }
            return c.getResponseCode();
        } catch (Exception e) {
            String m = e.getClass().getSimpleName();
            String msg = e.getMessage();
            if (msg != null && !msg.isEmpty() && msg.length() > 80) {
                msg = msg.substring(0, 80);
            }
            aliveErrTerakhir = msg == null || msg.isEmpty() ? m : m + ": " + msg;
            return -1;
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
     *  membatalkan operasi file DB agar SQLite tidak korup). */
    public static boolean stopAndWait(Context context, long timeoutMs) {
        if (!isProcessAlive()) {
            return true;
        }
        stop(context);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline && isProcessAlive()) {
            try {
                Thread.sleep(200);
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            autoRestart = false;
            healthActive = false;
            mainHandler.removeCallbacks(healthTick);
            mainHandler.removeCallbacks(restartTunda);
            batalStart = true;
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            autoRestart = true;
            batalStart = false;
            startForegroundCompat();
            new Thread(() -> {
                appendLog("[app] Restart diminta via Telegram.");
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
                mainHandler.post(() -> {
                    if (autoRestart) {
                        startServerAsync();
                    }
                });
            }, "vw-restart").start();
            return START_NOT_STICKY;
        }
        if (ACTION_TG_BACKUP.equals(action)) {
            startForegroundCompat();
            new Thread(() -> {
                try {
                    SharedPreferences cek = getSharedPreferences(PREFS, MODE_PRIVATE);
                    if (!cek.getBoolean(TgBackup.KEY_TG_AUTO, false)) {
                        // Alarm basi/duplikat yang terlanjur terjadwal tak boleh
                        // mengunggah setelah user mematikan auto-backup.
                        appendLog("[tg] Backup terjadwal dilewati: auto-backup sudah dimatikan.");
                        return;
                    }
                    long last = TgBackup.amanLong(cek, TgBackup.KEY_TG_LAST, 0);
                    if (last > 0 && !TgBackup.sudahGantiHari(last, System.currentTimeMillis())) {
                        appendLog("[tg] Backup hari ini sudah ada - terjadwal dilewati.");
                    } else {
                        try {
                            TgBackup.tungguBootStabil();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        String msg = TgBackup.backupNow(this);
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
                    TgBackup.schedule(this,
                            TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false));
                    if (process == null || !alive(process)) {
                        stopForeground(true);
                        stopSelf();
                    }
                }
            }, "vw-tg-sched").start();
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
        return START_NOT_STICKY;
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
        Notification n = b.setContentTitle("Vaultwarden Host")
                .setContentText("Server aktif di port " + currentPort())
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
        // Tolak traversal per segmen agar /sdcard/../data tak lolos, tapi
        // folder sah bernama "my..folder" tetap diterima.
        if (t.contains("//") || t.contains("\\")) {
            return false;
        }
        for (String segmen : t.split("/")) {
            if (segmen.equals("..")) {
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
        if (n.equals("/system") || n.startsWith("/system/") || n.equals("/vendor")
                || n.startsWith("/vendor/") || n.equals("/proc") || n.startsWith("/proc/")
                || n.equals("/sys") || n.startsWith("/sys/") || n.equals("/dev")
                || n.startsWith("/dev/")) {
            return false;
        }
        if ((n.equals("/data") || n.startsWith("/data/"))
                && !n.startsWith("/data/data/") && !n.startsWith("/data/user/")) {
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
        for (String s : n.split("/")) {
            if (!s.isEmpty()) {
                segmenIsi++;
            }
        }
        return segmenIsi >= 2;
    }

    /** Kembalikan folder data aman atau bawaan bila input berbahaya. Murni. */
    public static String amankanDataDir(String d) {
        return dataDirAman(d) ? d.trim() : DEFAULT_DATA_DIR;
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
     *  Nilai rusak (huruf/kosong/di luar 1-65535) jatuh ke default agar health
     *  check tak membangun URL invalid lalu restart beruntun. */
    public static String effectivePort(SharedPreferences sp) {
        // Baca tahan korup: prefs korup di tengah jalan tak boleh
        // ClassCastException tiap detik UI/health (jatuh ke default).
        String p = null;
        try {
            p = sp == null ? null : sp.getString(KEY_PORT, DEFAULT_PORT);
        } catch (Exception ignored) {
        }
        return normalisasiPort(p);
    }

    /** Port valid 1-65535, selain itu pakai default (murni agar bisa diuji). */
    static String normalisasiPort(String p) {
        if (p != null) {
            try {
                int n = Integer.parseInt(p.trim());
                if (n >= 1 && n <= 65535) {
                    return String.valueOf(n);
                }
            } catch (Exception ignored) {
            }
        }
        return DEFAULT_PORT;
    }

    /** Penanda migrasi port selesai (agar port 8080 pilihan user tak ditimpa). */
    public static final String KEY_PORT_MIGRATED = "port_migrated_8088";

    /** True bila migrasi 8080 -> default perlu jalan (murni, mudah diuji). */
    static boolean perluMigrasiPort(String tersimpan, boolean sudahMigrasi) {
        return !sudahMigrasi && "8080".equals(tersimpan);
    }

    /** Migrasi default lama 8080 -> default baru, sekali saja (dipanggil onCreate/start).
     *  Flag mencegah port 8080 yang disengaja user ikut tergusur di start berikutnya. */
    public static void migrasiPortSekali(SharedPreferences sp) {
        try {
            if (perluMigrasiPort(sp.getString(KEY_PORT, DEFAULT_PORT),
                    sp.getBoolean(KEY_PORT_MIGRATED, false))) {
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
        new Thread(() -> {
            try {
                startServer();
            } finally {
                starting.set(false);
            }
        }, "vw-start").start();
    }

    private void startServer() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            TgBackup.healkanStringPrefs(this);
        } catch (Exception ignored) {
        }
        String dataDir = TgBackup.amanString(sp, KEY_DATA_DIR, DEFAULT_DATA_DIR);
        if (!dataDirAman(dataDir) || !dataDirKanonisAman(dataDir)) {
            appendLog("[app] Folder data tidak valid, pakai bawaan: " + DEFAULT_DATA_DIR);
            dataDir = DEFAULT_DATA_DIR;
            sp.edit().putString(KEY_DATA_DIR, dataDir).apply();
        } else {
            dataDir = dataDir.trim();
        }
        String port = currentPort();

        File dataFolder = new File(dataDir);
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            setStatus("Gagal membuat folder: " + dataDir);
            appendLog("[app] Gagal membuat folder data: " + dataDir);
            return;
        }
        if (!dataFolder.canWrite()) {
            setStatus("Folder tidak bisa ditulis: " + dataDir);
            appendLog("[app] Folder data tidak writable: " + dataDir
                    + " - beri izin Storage/Semua file di Pengaturan HP, lalu Start ulang.");
            return;
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
        boolean legacy = KernelCompat.isLegacyDevice(KernelCompat.kernelSekarang());
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
        if (portNum < 1 || portNum > 65535) {
            appendLog("[app] Port tidak valid ('" + port + "') - pakai default " + DEFAULT_PORT + ".");
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
                appendLog("[app] PERINGATAN: jam STB miring (sertifikat belum valid)."
                        + " Betulkan tanggal & jam agar HTTPS stabil.");
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
            pb.environment().put("ROCKET_TLS", "{certs=\"" + tlsCert + "\",key=\"" + tlsKey + "\"}");
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
            runningAdminToken = adminToken == null ? "" : adminToken.trim();
            String wvFrom = Updater.webVaultFromVersion(this);
            runningWvFrom = wvFrom == null ? "" : wvFrom;

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
            restartAttempt = 0;

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
            releaseWakeLock();
            appendLog("[app] ERROR start: " + e);
            setStatus("Gagal start: " + e.getMessage());
        }
    }

    private void stopServer() {
        if (process != null) {
            final Process p = process;
            // Tandai stop sengaja agar watchProcess abaikan exit (status tetap
            // "Stopped"). Proses dipertahankan sampai benar-benar mati agar
            // stopAndWait()/restore tak menimpa DB selagi proses lama hidup.
            // Per-proses agar Start baru tak ikut ditandai.
            tandaiStopDisengaja(p);
            setStatus("Stopped");
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
                    }
                    hapusTandaStop(p);
                }
            }, "vw-stop");
            stopper.setDaemon(true);
            stopper.start();
        } else {
            setStatus("Stopped");
            prosesStopDisengaja = null;
        }
        running = false;
        runningDataDir = "";
        runningPort = "";
        runningHttps = false;
        runningAdminToken = "";
        runningWvFrom = "";
        releaseWakeLock();
        flushLogFile();
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
        long uptime = SystemClock.elapsedRealtime() - lastStartElapsed;
        if (uptime > 60_000) {
            restartAttempt = 0;
        }
        if (restartAttempt >= RESTART_DELAYS.length) {
            autoRestart = false;
            String tail = tailLog(18);
            setStatus("Server berhenti - gagal restart 5x.\n" + shorten(tail, 300));
            appendLog("[app] Berhenti mencoba restart setelah 5 kegagalan.");
            writeCrashLog("restart 5x gagal");
            TgBackup.sendMessage(this, "Server berhenti: gagal restart 5x.\n"
                    + shorten(tail, 600));
            return;
        }
        long delay = RESTART_DELAYS[restartAttempt++];
        setStatus("Server crash - restart dalam " + (delay / 1000) + " dtk (coba " + restartAttempt + ")");
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
        String r = baris.toLowerCase(Locale.US);
        if (r.contains("invalid username or password")
                || r.contains("invalid credentials")
                || r.contains("username or password is incorrect")
                || r.contains("wrong password")
                || r.contains("incorrect password")
                || r.contains("login failed")
                || r.contains("failed login")
                || r.contains("failed log-in")
                || (r.contains("/identity/connect/token") && r.contains("401"))
                || (r.contains("invalid") && (r.contains("credential") || r.contains("password")))) {
            return "[login] Login aplikasi Bitwarden gagal: email/password salah atau akun belum "
                    + "terdaftar. Daftar dulu di web-vault (Create Account), lalu login di aplikasi "
                    + "dengan email+password itu (bukan admin token).";
        }
        if (r.contains("two-factor") || r.contains("two factor")
                || r.contains("2fa") || r.contains("totp")) {
            return "[login] Login butuh kode 2FA: buka aplikasi authenticator lalu masukkan "
                    + "kode 6 digit di aplikasi Bitwarden.";
        }
        boolean tls = r.contains("certificate") || r.contains("handshake")
                || r.contains("tls") || r.contains("ssl");
        boolean gagal = r.contains("unknown") || r.contains("alert") || r.contains("fail")
                || r.contains("error") || r.contains("abort") || r.contains("refus")
                || r.contains("ditolak") || r.contains("verify");
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
        synchronized (LOG_TS) {
            stamp = LOG_TS.format(new Date());
        }
        synchronized (RESTART_TIMES) {
            RESTART_TIMES.add(now);
            RESTART_REASONS.add(stamp + " " + reason);
            while (RESTART_TIMES.size() > RESTART_HISTORY_MAX) {
                RESTART_TIMES.remove(0);
                RESTART_REASONS.remove(0);
            }
            int n = 0;
            for (long t : RESTART_TIMES) {
                if (now - t <= RESTART_WINDOW_MS) {
                    n++;
                }
            }
            if (n >= RESTART_WINDOW_MAX) {
                autoRestart = false;
                String tail = tailLog(14);
                setStatus("Restart berulang (" + n + "x dalam 5 mnt) - dihentikan.\n"
                        + shorten(tail, 300));
                appendLog("[app] Restart berulang (" + n
                        + "x dalam 5 mnt) - auto-restart dimatikan.");
                writeCrashLog("restart loop (" + n + "x/5mnt)");
                TgBackup.sendMessage(this, "Server restart berulang (" + n
                        + "x dalam 5 menit) - auto-restart dimatikan.\n"
                        + shorten(tail, 600));
                return true;
            }
        }
        return false;
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
            synchronized (LOG_TS) {
                stamp = LOG_TS.format(new Date());
            }
            // Samarkan dulu: file ini dibaca dialog crash + dikirim /crashlog
            // ke Telegram (keluar perangkat), seperti jalur share/clipboard.
            String body = "=== " + stamp + " [" + reason + "] ===\n"
                    + LogActivity.samarkanLog(tailLog(100)) + "\n";
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
                String hintLogin = saranLoginUntukBaris(line, runningHttps);
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

    private void appendLog(String line) {
        if (line == null) {
            return;
        }
        String stamp;
        synchronized (LOG_TS) {
            stamp = LOG_TS.format(new Date());
        }
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
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            w.write(logFileBuf.toString());
        } catch (Exception ignored) {
        } finally {
            logFileBuf.setLength(0);
        }
        if (f.length() > MAX_LOG_FILE) {
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
    }

    // ─── Health check (/alive) ─────────────────────────────────────────

    private void checkHealthOnce() {
        HasilPing h = pingRinci(this);
        if (h.sehat) {
            healthFails.set(0);
            healthTcpLolos.set(0);
            return;
        }
        healthFail("tidak merespon (" + h.rincian + ")");
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
                Thread killer = new Thread(() -> {
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
                }, "vw-health-stop");
                killer.setDaemon(true);
                killer.start();
            }
            // Lanjut restart terbatas (maks 5x, backoff di scheduleRestart).
            // Tanda stop disengaja dipertahankan sampai killer selesai agar
            // watchProcess tak ikut menjadwalkan restart ganda; start baru
            // membersihkannya sendiri (prosesStopDisengaja = null).
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

    /** Cap file CA aktif agar factory segar setelah regenerasi cert (ganti IP). */
    private static long capCaAktif(Context ctx) {
        try {
            java.io.File ca = null;
            try {
                ca = new java.io.File(ctx.getFilesDir(), "tls/ca.pem");
            } catch (Exception ignored) {
            }
            if ((ca == null || !ca.isFile()) && ctx != null) {
                try {
                    android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    String dataDir = sp.getString(KEY_DATA_DIR, DEFAULT_DATA_DIR);
                    if (dataDir == null || dataDir.trim().isEmpty()) {
                        dataDir = DEFAULT_DATA_DIR;
                    }
                    ca = new java.io.File(dataDir, "tls/ca.pem");
                } catch (Exception ignored) {
                }
            }
            if (ca != null && ca.isFile()) {
                // Tanpa perkalian raksasa (rawan overflow): gabung mtime + panjang
                // lalu campur hash SELURUH isi agar perubahan ekor file tak lolos
                // (sama seperti HttpsCompat.capOverride; mtime FAT 2 detik saja
                // tak cukup membedakan CA hasil regenerasi cepat — factory basi
                // bikin health HTTPS gagal + restart beruntun).
                long h = ca.lastModified() * 31 + ca.length();
                try (java.io.InputStream in = new java.io.FileInputStream(ca)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        h = h * 31 + (java.util.Arrays.hashCode(
                                java.util.Arrays.copyOf(buf, n)) & 0xffffffffL);
                    }
                } catch (Exception ignored) {
                }
                return h;
            }
        } catch (Exception ignored) {
        }
        return 0L;
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
                String dataDir = sp.getString(KEY_DATA_DIR, DEFAULT_DATA_DIR);
                if (dataDir == null || dataDir.trim().isEmpty()) dataDir = DEFAULT_DATA_DIR;
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
            sc.init(null, tmf.getTrustManagers(), new SecureRandom());
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
    private static volatile long collectCacheTime = 0;
    private static volatile List<String> collectCache = new ArrayList<>();
    private static final Object COLLECT_LOCK = new Object();

    /** IP lokal pertama (untuk akses dari perangkat lain di jaringan sama).
     *  Di-cache 3 detik agar tidak enumerasi network interface tiap detik (dipanggil UI). */
    public static String localIp() {
        long now = SystemClock.elapsedRealtime();
        if (now - ipCacheTime < 3000 && !ipCache.isEmpty()) {
            return ipCache;
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
        ipCache = pilih;
        ipCacheTime = now;
        return ipCache;
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
     *  Dikunci KUNCI_WEBVAULT bersama Updater agar Start tak membuang web-vault.new
     *  yang sedang diekstrak update bot (balapan hemat kuota 35 MB). */
    private void cleanupTempFiles(String dataDir) {
        synchronized (Updater.KUNCI_WEBVAULT) {
            cleanupTempFilesTerkunci(dataDir);
        }
    }

    private void cleanupTempFilesTerkunci(String dataDir) {
        // File .tmp unduhan (binary/web-vault) SENGAJA dipertahankan agar Start
        // berikutnya melanjutkan via HTTP Range (hemat kuota ~15/35 MB).
        // Updater menghapusnya sendiri bila korup/checksum tak cocok.
        // Hanya folder ekstrak yatim yang dibersihkan di sini.
        deleteRecursive(new File(dataDir, "web-vault.new"));
        // Pulihkan sisa swap web-vault yang terpotong crash: bila folder aktif
        // hilang/rusak tapi .bak ada, kembalikan; bila aktif sehat, buang .bak.
        try {
            java.io.File target = new java.io.File(dataDir, "web-vault");
            java.io.File bak = new java.io.File(dataDir, "web-vault.bak");
            if (bak.exists()) {
                boolean aktifSehat = target.exists()
                        && new java.io.File(target, "index.html").exists();
                if (!aktifSehat) {
                    deleteRecursive(target);
                    if (!bak.renameTo(target)) {
                        deleteRecursive(bak);
                    }
                } else {
                    deleteRecursive(bak);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        file.delete();
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
        if (KernelCompat.isLegacyDevice(KernelCompat.kernelSekarang())) {
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
        String dataDir = sp.getString(KEY_DATA_DIR, DEFAULT_DATA_DIR);
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = DEFAULT_DATA_DIR;
        }

        // Revisi patch binary: bila CI memperbaiki binary tanpa ganti versi Vaultwarden
        // (mis. patch TLS favicon), cache lama wajib diunduh ulang sekali.
        boolean butuhRefresh = perluRefreshPatch(sp.getString(KEY_BIN_PATCH, ""));

        // 1) Binary update terbaru hasil tombol Perbarui (KEY_UPDATE_VERSION).
        //    Didahulukan agar Start tidak memakai binary lama selamanya.
        //    Dilewati bila revisi patch berubah agar binary basi tidak dipakai terus.
        if (!butuhRefresh && isValidBinary(out)) {
            String updated = sp.getString(KEY_UPDATE_VERSION, "");
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
            String wantSha = sp.getString(KEY_BIN_SHA, "");
            if (wantSha != null && !wantSha.trim().isEmpty()) {
                String gotSha = Updater.sha256Hex(userBin);
                if (gotSha == null || !gotSha.equalsIgnoreCase(wantSha.trim())) {
                    appendLog("[app] Binary manual DITOLAK: SHA-256 tidak cocok"
                            + " dengan pengaturan. Cek kembali file/SHA-nya.");
                    setStatus("Binary manual ditolak: SHA-256 tidak cocok.");
                    TgBackup.sendMessage(this, "Binary manual ditolak: SHA-256 tidak cocok.");
                } else {
                    // Uji di file sementara dulu: binary manual korup tak boleh
                    // menghancurkan cache yang sedang jalan (STB offline tak bisa
                    // unduh ulang). Penimpaan hanya bila smoke test lolos.
                    File tmpManual = new File(binDir, "vaultwarden-" + ABI + ".manual-tmp");
                    try {
                        copyBinary(userBin, tmpManual);
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
                                appendLog("[app] Binary dari folder data dipakai (SHA-256 cocok).");
                                return out;
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
        try {
            String msg = Updater.downloadBinary(this, out);
            appendLog("[app] " + msg);
            if (butuhRefresh) {
                appendLog("[app] Binary perbaikan (patch favicon) terpasang.");
            }
            sp.edit().putString(KEY_BIN_PATCH, String.valueOf(BIN_PATCH_REV)).apply();
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
            tmp.setReadable(true, true);
            tmp.setExecutable(true, true);
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

    /** Smoke test --version; false bila binary tak bisa dieksekusi. */
    private boolean detectBinaryVersion(File binary) {
        Process p = null;
        BufferedReader r = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(), "--version")
                    .redirectErrorStream(true);
            if (KernelCompat.isLegacyDevice(KernelCompat.kernelSekarang())) {
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
            final boolean[] versiSelesai = new boolean[1];
            Thread versiWatchdog = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!versiSelesai[0]) {
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
            versiSelesai[0] = true;
            versiWatchdog.interrupt();
            lastVersionOutput = first == null ? "" : first.trim();
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
                return false;
            }
            binaryVersion = first.trim();
            return true;
        } catch (Exception e) {
            binaryVersion = "?";
            lastVersionOutput = "";
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
            if (now - collectCacheTime < 5000 && !collectCache.isEmpty()) {
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
            List<String> dns = new ArrayList<>();
            String cur = joinIps(ips) + "|dns=" + joinIps(dns);

            // Kunci TLS di internal (bukan /sdcard FAT yang chmod-nya tak berlaku):
            // migrasi sekali dari folder data lama agar CA tetap sama (HP lain
            // tak perlu install ulang), lalu pakai internal seterusnya.
            File internal = new File(getFilesDir(), "tls");
            migrasiTlsKeInternal(new File(dataFolder, "tls"), internal);
            File ipFile = new File(internal, "ips.txt");
            File dir = ensureCertWithIps(internal, ipFile, ips, dns, cur);
            if (dir == null) {
                File lama = new File(dataFolder, "tls");
                dir = ensureCertWithIps(lama, new File(lama, "ips.txt"), ips, dns, cur);
            }
            if (dir != null) {
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

    private File ensureCertWithIps(File tlsDir, File ipFile, List<String> ips,
            List<String> dns, String cur) throws Exception {
        String saved = readText(ipFile);
        if (saved == null || !saved.equals(cur)) {
            // Hanya leaf yang dibuat ulang; CA (ca.pem) dipertahankan agar HP lain
            // tak perlu install ulang, jadi version.txt jangan dihapus.
            // Penanda hilang (saved null) ikut regen: daun lama bisa berisi SAN basi.
            appendLog("[app] IP berubah - regenerasi sertifikat server (CA tetap).");
            new File(tlsDir, "cert.pem").delete();
            new File(tlsDir, "key.pem").delete();
            ipFile.delete();
        }
        File dir = TlsCert.ensure(tlsDir, ips, dns);
        if (dir != null) {
            writeText(ipFile, cur);
            // Sertifikat bisa baru dibuat: buang cache SSL agar health check
            // memakai CA terbaru, bukan trust-all lama.
            sslFactory = null;
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
    private static final long CHILD_PID_TTL_MS = 30_000;

    /** RAM (VmRSS, kB) proses vaultwarden; -1 bila tidak terbaca. */
    public static long processRssKb() {
        int pid = -1;
        Process p = process;
        if (p != null) {
            pid = pidOf(p);
        }
        if (pid < 0) {
            long now = SystemClock.elapsedRealtime();
            int cached = cachedChildPid;
            if (cached >= 0 && now - cachedChildPidAt < CHILD_PID_TTL_MS) {
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
                    if (cmd != null && cmd.contains("bin/vaultwarden")) {
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
                        android.os.Process.killProcess(pid);
                        appendLog("[app] Proses vaultwarden lama (pid " + pid + ") dibersihkan.");
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** True bila cmdline milik server basi yang boleh dibunuh: binary app
     *  yang nyangkut, BUKAN smoke-test "--version" milik flow update/start
     *  konkuren. Murni agar bisa unit test. */
    static boolean bolehBunuhBasi(String cmd) {
        return cmd != null && cmd.contains("bin/vaultwarden")
                && !cmd.contains("--version");
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
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        return Integer.parseInt(parts[1]);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** True bila port sedang dipakai proses lain (listening).
     *  Cek IPv4 dan IPv6: pendengar IPv6-only lolos cek IPv4 lalu membuat
     *  Rocket gagal bind (crash-loop) bila hanya satu sisi diperiksa. */
    public static boolean isPortBusy(int port) {
        if (port < 1 || port > 65535) {
            return true;
        }
        return !bisaBind("0.0.0.0", port) || !bisaBind("::", port);
    }

    /** True bila alamat:port masih bisa di-bind (bebas). Murni agar bisa diuji. */
    static boolean bisaBind(String host, int port) {
        try (ServerSocket s = new ServerSocket()) {
            // REUSEADDR agar Start langsung setelah Stop tak dikira "port sibuk"
            // (TIME_WAIT). Cek dual-stack tetap lewat dua panggilan IPv4 + IPv6
            // oleh isPortBusy.
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

    /** True bila string hanya berisi whitespace (tanpa alokasi trim). Murni. */
    static boolean barisKosong(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
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
        synchronized (LOG_TS) {
            stamp = LOG_TS.format(new java.util.Date());
        }
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
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!alive(p)) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vaultwarden:server");
                // Timeout 12 jam: bila jalur stop()/destroy terlewat, kunci
                // tetap dilepas sistem agar baterai tak terkuras selamanya.
                try {
                    wakeLock.acquire(12L * 3600 * 1000L);
                } catch (Exception e) {
                    wakeLock.acquire();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        healthActive = false;
        autoRestart = false;
        mainHandler.removeCallbacks(healthTick);
        mainHandler.removeCallbacks(restartTunda);
        flushLogFile();
        super.onDestroy();
        releaseWakeLock();
    }
}
