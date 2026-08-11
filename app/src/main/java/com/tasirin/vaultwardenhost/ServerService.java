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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
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
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class ServerService extends Service {

    public static final String ACTION_START = "com.tasirin.vaultwardenhost.START";
    public static final String ACTION_STOP = "com.tasirin.vaultwardenhost.STOP";
    public static final String ACTION_RESTART = "com.tasirin.vaultwardenhost.RESTART";
    public static final String ACTION_TG_BACKUP = "com.tasirin.vaultwardenhost.TG_BACKUP";

    public static final String PREFS = "vw_prefs";
    public static final String DEFAULT_DATA_DIR = "/sdcard/vaultwarden";
    public static final String DEFAULT_PORT = "8088";
    /** Binary selalu 32-bit ARM (armeabi-v7a); HP arm64 tetap jalan via compat mode. */
    public static final String ABI = "armeabi-v7a";
    public static final String KEY_DATA_DIR = "data_dir";
    public static final String KEY_PORT = "port";
    public static final String KEY_AUTO_START = "auto_start";
    public static final String KEY_UPDATE_VERSION = "update_version";
    public static final String KEY_HTTPS = "https";
    public static final String KEY_ADMIN_TOKEN = "admin_token";
    public static final String KEY_AUTO_UPDATE = "auto_update_binary";
    public static final String KEY_AUTO_UPDATE_WV = "auto_update_webvault";
    public static final String KEY_AUTO_RESTART_UPDATE = "auto_restart_update";

    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "vaultwarden_server";
    private static final int MAX_LOG_CHARS = 300_000;
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

    /** Snapshot konfigurasi server yang sedang berjalan (untuk peringatan restart). */
    public static volatile String runningDataDir = "";
    public static volatile String runningPort = "";
    public static volatile boolean runningHttps = false;
    public static volatile String runningAdminToken = "";

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
    private static volatile boolean starting = false;
    private PowerManager.WakeLock wakeLock;
    private static volatile File logFile;
    private boolean autoRestart = false;
    private int restartAttempt = 0;
    private static volatile long lastStartTime = 0;
    private ControlServer controlServer;
    private volatile int healthFails = 0;

    private final Runnable healthTick = new Runnable() {
        @Override
        public void run() {
            // Adaptif: tiap 30 detik di 5 menit pertama (crash dini cepat
            // ketahuan), lalu tiap 2 menit setelah server stabil.
            long delay = HEALTH_INTERVAL_MS;
            long up = System.currentTimeMillis() - lastStartTime;
            if (lastStartTime > 0 && up < HEALTH_FAST_MS) {
                delay = HEALTH_FAST_INTERVAL_MS;
            }
            mainHandler.postDelayed(this, delay);
            if (process == null || !alive(process) || !running) {
                return;
            }
            new Thread(ServerService.this::checkHealthOnce, "vw-health").start();
        }
    };

    public static void start(Context context) {
        Intent i = new Intent(context, ServerService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, ServerService.class).setAction(ACTION_STOP));
    }

    /** Restart proses server tanpa mematikan service (dipakai dari perintah bot). */
    public static void restart(Context context) {
        context.startService(new Intent(context, ServerService.class).setAction(ACTION_RESTART));
    }

    /** Jalankan backup Telegram terjadwal (via AlarmReceiver). */
    public static void backupNow(Context context) {
        Intent i = new Intent(context, ServerService.class).setAction(ACTION_TG_BACKUP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    /** Apakah proses vaultwarden masih hidup (dipakai sebelum restore DB). */
    public static boolean isProcessAlive() {
        Process p = process;
        return alive(p);
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
        }
        File f = logFile;
        if (f != null) {
            f.delete();
        }
    }

    /** Lama server sudah berjalan (ms); 0 bila sedang berhenti. */
    public static long uptimeMs() {
        if (!running) {
            return 0;
        }
        long t = lastStartTime;
        return t == 0 ? 0 : System.currentTimeMillis() - t;
    }

    /** Cek /alive sekali tanpa efek samping; true bila sehat (HTTP 200). */
    public static boolean pingAlive(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            boolean https = sp.getBoolean(KEY_HTTPS, false);
            String port = effectivePort(sp);
            String scheme = https ? "https" : "http";
            HttpURLConnection c = (HttpURLConnection) new URL(
                    scheme + "://127.0.0.1:" + port.trim() + "/alive").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            if (https) {
                HttpsURLConnection hc = (HttpsURLConnection) c;
                hc.setSSLSocketFactory(trustAllSslFactory());
                hc.setHostnameVerifier((host, session) -> true);
            }
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Stop server dan tunggu proses benar-benar mati. */
    public static void stopAndWait(Context context, long timeoutMs) {
        if (!isProcessAlive()) {
            return;
        }
        stop(context);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && isProcessAlive()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            autoRestart = false;
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            startForegroundCompat();
            new Thread(() -> {
                appendLog("[app] Restart diminta via Telegram.");
                if (process != null) {
                    final Process p = process;
                    process = null;
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
                        }
                    } catch (Exception ignored) {
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
                    String msg = TgBackup.backupNow(this);
                    appendLog("[tg] " + msg);
                    TgBackup.sendMessage(this, "Backup otomatis: " + msg);
                } catch (Exception e) {
                    appendLog("[tg] Gagal backup terjadwal: " + e);
                    TgBackup.sendMessage(this, "Backup otomatis GAGAL: " + e.getMessage());
                } finally {
                    // Jadwalkan ulang 24 jam dari sekarang (tepat waktu), selama masih aktif.
                    SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                    TgBackup.schedule(this, sp.getBoolean(TgBackup.KEY_TG_AUTO, false));
                    if (process == null || !alive(process)) {
                        stopForeground(true);
                        stopSelf();
                    }
                }
            }, "vw-tg-sched").start();
            return START_NOT_STICKY;
        }
        autoRestart = true;
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

    /** Baca port tersimpan; migrasikan default lama 8080 -> default baru sekali. */
    public static String effectivePort(SharedPreferences sp) {
        String p = sp.getString(KEY_PORT, DEFAULT_PORT);
        if ("8080".equals(p)) {
            p = DEFAULT_PORT;
            sp.edit().putString(KEY_PORT, p).apply();
        }
        return (p == null || p.trim().isEmpty()) ? DEFAULT_PORT : p.trim();
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
        if (starting) {
            appendLog("[app] Start masih berjalan (unduh binary?), dilewati.");
            return;
        }
        starting = true;
        new Thread(() -> {
            try {
                startServer();
            } finally {
                starting = false;
            }
        }, "vw-start").start();
    }

    private void startServer() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String dataDir = sp.getString(KEY_DATA_DIR, DEFAULT_DATA_DIR);
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = DEFAULT_DATA_DIR;
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
            appendLog("[app] Folder data tidak writable: " + dataDir);
            return;
        }

        // Bersihkan sisa unduhan gagal agar tidak memakan storage.
        cleanupTempFiles(dataDir);
        File binary = ensureBinary();
        if (binary == null) {
            return;
        }

        // Bersihkan proses vaultwarden lama yang masih nyangkut (biasanya masih
        // memegang port) sebelum start - penyebab utama "looping" saat start.
        killStaleVaultwarden();
        int portNum = Integer.parseInt(DEFAULT_PORT);
        try {
            portNum = Integer.parseInt(port.trim());
        } catch (Exception ignored) {
        }
        if (isPortBusy(portNum)) {
            setStatus("Port " + port.trim() + " sedang dipakai proses lain.\n"
                    + "Stop server lain / restart HP dulu, lalu Start lagi.");
            appendLog("[app] Port " + port.trim() + " sedang dipakai - server TIDAK start (cegah loop).");
            TgBackup.sendMessage(this, "Gagal start: port " + port.trim()
                    + " sedang dipakai proses lain. Restart HP lalu coba lagi.");
            return;
        }

        try {
            boolean https = sp.getBoolean(KEY_HTTPS, false);
            String scheme = "http";
            File tlsDir = null;
            if (https) {
                tlsDir = prepareTls(dataFolder);
                if (tlsDir == null) {
                    appendLog("[app] HTTPS diaktifkan tapi sertifikat gagal dibuat; pakai HTTP.");
                    setStatus("Gagal buat sertifikat - lanjut HTTP");
                } else {
                    scheme = "https";
                    long certDays = TlsCert.daysLeft(new File(tlsDir, "cert.pem"));
                    if (certDays >= 0 && certDays < 30) {
                        appendLog("[app] PERINGATAN: sertifikat TLS tinggal " + certDays
                                + " hari. Sertifikat dibuat ulang otomatis saat IP berubah.");
                    }
                }
            }

            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath());
            pb.environment().put("DATA_FOLDER", dataDir);
            pb.environment().put("ROCKET_ADDRESS", "0.0.0.0");
            pb.environment().put("ROCKET_PORT", port);
            pb.environment().put("ROCKET_WORKERS", "2");

            // Admin token
            String adminToken = sp.getString(KEY_ADMIN_TOKEN, "");
            if (adminToken != null && !adminToken.trim().isEmpty()) {
                pb.environment().put("ADMIN_TOKEN", adminToken.trim());
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

            if (tlsDir != null) {
                String tlsCert = new File(tlsDir, "cert.pem").getAbsolutePath();
                String tlsKey = new File(tlsDir, "key.pem").getAbsolutePath();
                pb.environment().put("ROCKET_TLS", "{certs=\"" + tlsCert + "\",key=\"" + tlsKey + "\"}");
                appendLog("[app] TLS cert: " + tlsCert);
                appendLog("[app] TLS key:  " + tlsKey);
            }
            pb.environment().put("RUST_LOG", "info");
            String domain = scheme + "://" + detectHostPort(port);
            pb.environment().put("DOMAIN", domain);
            pb.redirectErrorStream(true);

            runningDataDir = dataDir;
            runningPort = port;
            runningHttps = https;
            runningAdminToken = adminToken == null ? "" : adminToken.trim();

            process = pb.start();
            acquireWakeLock();
            running = true;
            healthFails = 0;
            lastStartTime = System.currentTimeMillis();
            restartAttempt = 0;

            logFile = new File(dataFolder, "vaultwarden.log");

            if (controlServer != null) {
                controlServer.stop();
            }
            controlServer = new ControlServer(this);
            boolean ctrlOk = false;
            for (int cp = portNum + 1; cp <= portNum + 10 && !ctrlOk; cp++) {
                ctrlOk = controlServer.start(cp);
            }
            String ctrlUrl = "";
            if (ctrlOk) {
                ctrlUrl = scheme + "://" + lanHost() + ":" + ControlServer.listeningPort;
                appendLog("[app] Status web (log realtime): " + ctrlUrl);
            } else {
                appendLog("[app] Status web tidak start: port " + (portNum + 1)
                        + "-" + (portNum + 10) + " semuanya dipakai.");
            }
            setStatus("Running (PID " + getPid(process) + ")\nData: " + dataDir
                    + "\nURL lokal (di HP): " + scheme + "://127.0.0.1:" + port
                    + "\nURL jaringan (dari PC/laptop): " + domain
                    + (ctrlOk ? "\nStatus web: " + ctrlUrl : ""));
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
        } catch (Exception e) {
            process = null;
            running = false;
            releaseWakeLock();
            appendLog("[app] ERROR start: " + e);
            setStatus("Gagal start: " + e.getMessage());
        }
    }

    private void stopServer() {
        if (process != null) {
            final Process p = process;
            setStatus("Stopping...");
            p.destroy();
            Thread stopper = new Thread(() -> {
                try {
                    if (!waitForOrKill(p, 5000)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            p.destroyForcibly();
                        } else {
                            p.destroy();
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "vw-stop");
            stopper.setDaemon(true);
            stopper.start();
        } else {
            setStatus("Stopped");
        }
        running = false;
        runningDataDir = "";
        runningPort = "";
        runningHttps = false;
        runningAdminToken = "";
        releaseWakeLock();
        if (controlServer != null) {
            controlServer.stop();
            controlServer = null;
        }
        TgBackup.sendMessage(this, "Server dihentikan.");
    }

    private void watchProcess(Process p) {
        try {
            int code = p.waitFor();
            if (process == p) {
                process = null;
                running = false;
                releaseWakeLock();
                appendLog("[app] process exit: " + code);
                String tail = tailLog(18);
                if (autoRestart) {
                    writeCrashLog("crash (exit " + code + ")");
                    if (recordRestart("crash (exit " + code + ")")) {
                        // Loop terdeteksi: recordRestart sudah set status + kirim Telegram.
                    } else {
                        TgBackup.sendMessage(this, "Server crash (exit " + code
                                + ")\n" + shorten(tail, 500) + "\nRestart otomatis...");
                        setStatus("Server crash (exit " + code
                                + ") - restart otomatis\n" + shorten(tail, 250));
                        scheduleRestart();
                    }
                } else {
                    setStatus("Stopped (exit code " + code + ")");
                }
            }
        } catch (InterruptedException ignored) {
        }
    }

    private void scheduleRestart() {
        long uptime = System.currentTimeMillis() - lastStartTime;
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
        mainHandler.postDelayed(() -> {
            if (autoRestart && (process == null || !alive(process))) {
                startServerAsync();
            }
        }, delay);
    }

    /** Catat restart otomatis + deteksi loop. Return true bila harus berhenti
     *  (≥3 restart dalam 5 menit): matikan auto-restart, tulis crash log,
     *  beri tahu via status & Telegram. */
    private boolean recordRestart(String reason) {
        long now = System.currentTimeMillis();
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
            String body = "=== " + stamp + " [" + reason + "] ===\n" + tailLog(100) + "\n";
            try (FileWriter w = new FileWriter(new File(getFilesDir(), CRASH_LOG_NAME), false)) {
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
                // Redam log bising yang tidak berguna: handshake TLS lokal
                // (cert self-signed) dan peringatan HSTS bawaan Vaultwarden.
                if (line.contains("CertificateUnknown")
                        || line.contains("tls handshake with 127.0.0.1")
                        || line.contains("Detected TLS-enabled liftoff")
                        || line.contains("Shield has enabled a default HSTS policy")) {
                    continue;
                }
                appendLog(line);
            }
        } catch (Exception ignored) {
        }
    }

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
            if (logBuffer.length() > MAX_LOG_CHARS) {
                logBuffer.delete(0, logBuffer.length() - MAX_LOG_CHARS / 2);
            }
        }
        if (logFile != null) {
            try (FileWriter w = new FileWriter(logFile, true)) {
                w.write(entry + "\n");
            } catch (Exception ignored) {
            }
            if (logFile.length() > MAX_LOG_FILE) {
                File old = new File(logFile.getParentFile(), logFile.getName() + ".1");
                if (old.exists()) {
                    old.delete();
                }
                logFile.renameTo(old);
            }
        }
    }

    // ─── Health check (/alive) ─────────────────────────────────────────

    private void checkHealthOnce() {
        if (pingAlive(this)) {
            healthFails = 0;
            return;
        }
        healthFail("tidak merespon /alive");
    }

    private void healthFail(String reason) {
        healthFails++;
        appendLog("[health] /alive gagal: " + reason + " (ke-" + healthFails + "/3)");
        if (healthFails >= 3) {
            autoRestart = false;
            setStatus("Server tidak sehat - berhenti.");
            appendLog("[health] 3x gagal beruntun - server dihentikan.");
            writeCrashLog("health 3x");
            TgBackup.sendMessage(this, "Server tidak sehat (3x gagal /alive) - dihentikan.\n"
                    + shorten(tailLog(15), 500));
            if (process != null) {
                final Process p = process;
                process = null;
                running = false;
                releaseWakeLock();
                p.destroy();
            }
            return;
        }
        boolean loop = recordRestart("health " + healthFails + "/3");
        if (!loop) {
            TgBackup.sendMessage(this,
                    "Server tidak sehat (" + healthFails + "/3) - restart otomatis...");
            appendLog("[health] Restart otomatis...");
        }
        if (process != null) {
            final Process p = process;
            process = null;
            running = false;
            releaseWakeLock();
            p.destroy();
        }
        mainHandler.post(() -> {
            healthFails = 0;
            if (autoRestart) {
                startServerAsync();
            }
        });
    }

    private static javax.net.ssl.SSLSocketFactory sslFactory;

    private static javax.net.ssl.SSLSocketFactory trustAllSslFactory() throws Exception {
        if (sslFactory == null) {
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tm, new SecureRandom());
            sslFactory = sc.getSocketFactory();
        }
        return sslFactory;
    }

    private static volatile long ipCacheTime = 0;
    private static volatile String ipCache = "";

    /** IP lokal pertama (untuk akses dari perangkat lain di jaringan sama).
     *  Di-cache 3 detik agar tidak enumerasi network interface tiap detik (dipanggil UI). */
    public static String localIp() {
        long now = System.currentTimeMillis();
        if (now - ipCacheTime < 3000 && !ipCache.isEmpty()) {
            return ipCache;
        }
        List<String> ips = collectIps();
        ipCache = ips.isEmpty() ? "127.0.0.1" : ips.get(0);
        ipCacheTime = now;
        return ipCache;
    }

    /** URL akses lengkap dari perangkat lain, mengikuti setting port & HTTPS. */
    public static String localUrl(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean https = sp.getBoolean(KEY_HTTPS, false);
        String port = effectivePort(sp);
        return (https ? "https" : "http") + "://" + localIp() + ":" + port.trim();
    }

    private void setStatus(String text) {
        statusLine = text;
    }

    /** Hapus file .tmp sisa unduhan gagal (binary & web-vault). */
    private void cleanupTempFiles(String dataDir) {
        File binDir = new File(getFilesDir(), "bin");
        File[] bins = binDir.listFiles();
        if (bins != null) {
            for (File f : bins) {
                if (f.getName().endsWith(".tmp")) {
                    f.delete();
                }
            }
        }
        new File(dataDir, "web-vault.zip.tmp").delete();
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
        File out = new File(binDir, "vaultwarden-" + ABI);
        File verFile = new File(binDir, "version.txt");
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String dataDir = sp.getString(KEY_DATA_DIR, DEFAULT_DATA_DIR);
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = DEFAULT_DATA_DIR;
        }

        // 1) Binary update terbaru hasil tombol Perbarui (KEY_UPDATE_VERSION).
        //    Didahulukan agar Start tidak memakai binary lama selamanya.
        if (isValidBinary(out)) {
            String updated = sp.getString(KEY_UPDATE_VERSION, "");
            if (updated != null && !updated.isEmpty()) {
                try {
                    detectBinaryVersion(out);
                    appendLog("[app] Binary update terbaru dipakai: " + out.getAbsolutePath());
                    return out;
                } catch (Exception ignored) {
                }
            }
        }

        // 2) Binary dari folder data (user menaruh sendiri di /sdcard/vaultwarden).
        File userBin = new File(dataDir, "vaultwarden-" + ABI);
        if (isValidBinary(userBin)) {
            try {
                copyBinary(userBin, out);
                writeText(verFile, Updater.appVersionName(this));
                appendLog("[app] Binary dari folder data dipakai: " + userBin.getAbsolutePath());
                detectBinaryVersion(out);
                return out;
            } catch (Exception e) {
                appendLog("[app] Gagal memakai binary dari folder data: " + e);
            }
        }

        // 2) Cache internal milik APK ini (version.txt = versi APK saat diunduh).
        if (isValidBinary(out) && verFile.exists()) {
            try {
                if (Updater.appVersionName(this).equals(readText(verFile))) {
                    detectBinaryVersion(out);
                    return out;
                }
            } catch (Exception ignored) {
            }
        }

        // 3) Unduh dari release repo.
        try {
            String msg = Updater.downloadBinary(this, out);
            appendLog("[app] " + msg);
            writeText(verFile, Updater.appVersionName(this));
            detectBinaryVersion(out);
            return out;
        } catch (Exception e) {
            appendLog("[app] Gagal unduh binary: " + e);
            setStatus("Binary belum tersedia - unduh gagal: " + e.getMessage()
                    + "\nCek koneksi internet, lalu tekan Start lagi.");
            return null;
        }
    }

    private boolean isValidBinary(File f) {
        if (f == null || !f.exists() || f.length() < 1_000_000) {
            return false;
        }
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] magic = new byte[4];
            int n = in.read(magic);
            return n == 4 && magic[0] == 0x7F && magic[1] == 'E'
                    && magic[2] == 'L' && magic[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    private void copyBinary(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
        // ownerOnly=true: hanya UID app yang membaca binary (proses anak jalan
        // sebagai UID sama) — tidak perlu world-readable.
        dst.setReadable(true, true);
        dst.setExecutable(true, false);
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

    private void detectBinaryVersion(File binary) {
        try {
            Process p = new ProcessBuilder(binary.getAbsolutePath(), "--version")
                    .redirectErrorStream(true)
                    .start();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String first = r.readLine();
            r.close();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!p.waitFor(15, TimeUnit.SECONDS)) {
                    p.destroy();
                }
            } else {
                p.waitFor();
            }
            binaryVersion = (first == null || first.trim().isEmpty()) ? "?" : first.trim();
        } catch (Exception e) {
            binaryVersion = "?";
        }
    }

    private static String lanHost() {
        List<String> ips = collectIps();
        return ips.isEmpty() ? "127.0.0.1" : ips.get(0);
    }

    private String detectHostPort(String port) {
        List<String> ips = collectIps();
        if (!ips.isEmpty()) {
            return ips.get(0) + ":" + port;
        }
        return "localhost:" + port;
    }

    private static List<String> collectIps() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return ips;
    }

    private File prepareTls(File dataFolder) {
        try {
            List<String> ips = collectIps();
            ips.add(0, "127.0.0.1");
            String cur = joinIps(ips);

            File tlsDir = new File(dataFolder, "tls");
            File ipFile = new File(tlsDir, "ips.txt");
            File dir = ensureCertWithIps(tlsDir, ipFile, ips, cur);
            if (dir == null) {
                File alt = new File(getFilesDir(), "tls");
                dir = ensureCertWithIps(alt, new File(alt, "ips.txt"), ips, cur);
            }
            if (dir != null) {
                appendLog("[app] Sertifikat HTTPS: " + new File(dir, "cert.pem").getAbsolutePath());
            }
            return dir;
        } catch (Exception e) {
            appendLog("[app] Gagal siapkan TLS: " + e);
            return null;
        }
    }

    private File ensureCertWithIps(File tlsDir, File ipFile, List<String> ips, String cur) throws Exception {
        String saved = readText(ipFile);
        if (saved != null && !saved.equals(cur)) {
            appendLog("[app] IP berubah - regenerasi sertifikat.");
            new File(tlsDir, "cert.pem").delete();
            new File(tlsDir, "key.pem").delete();
            new File(tlsDir, "version.txt").delete();
            ipFile.delete();
        }
        File dir = TlsCert.ensure(tlsDir, ips);
        if (dir != null) {
            writeText(ipFile, cur);
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
        try (FileWriter w = new FileWriter(f)) {
            w.write(text);
        }
    }




    private String getPid(Process p) {
        int pid = pidOf(p);
        return pid < 0 ? "?" : String.valueOf(pid);
    }

    private static int pidOf(Process p) {
        if (p == null) {
            return -1;
        }
        try {
            return (Integer) Process.class.getMethod("pid").invoke(p);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** RAM (VmRSS, kB) proses vaultwarden; -1 bila tidak terbaca. */
    public static long processRssKb() {
        int pid = -1;
        Process p = process;
        if (p != null) {
            pid = pidOf(p);
        }
        if (pid < 0) {
            pid = findChildPid();
        }
        if (pid < 0) {
            return -1;
        }
        try (BufferedReader r = new BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]);
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
                    if (cmd != null && cmd.contains("bin/vaultwarden")) {
                        android.os.Process.killProcess(pid);
                        appendLog("[app] Proses vaultwarden lama (pid " + pid + ") dibersihkan.");
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
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
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                sb.append((char) (buf[i] & 0xFF));
            }
            return sb.toString().replace('\u0000', ' ').trim();
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

    /** True bila port sedang dipakai proses lain (listening). */
    public static boolean isPortBusy(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** N baris terakhir log (tanpa baris kosong), untuk pesan crash. */
    private static String tailLog(int lines) {
        synchronized (logBuffer) {
            String all = logBuffer.toString();
            String[] arr = all.split("\n");
            int from = Math.max(0, arr.length - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < arr.length; i++) {
                if (!arr[i].trim().isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(arr[i]);
                }
            }
            return sb.toString();
        }
    }

    private static String shorten(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(s.length() - max);
    }

    private boolean waitForOrKill(Process p, long timeoutMillis) throws InterruptedException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
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
                wakeLock.acquire();
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
        super.onDestroy();
        releaseWakeLock();
        if (controlServer != null) {
            controlServer.stop();
            controlServer = null;
        }
    }
}
