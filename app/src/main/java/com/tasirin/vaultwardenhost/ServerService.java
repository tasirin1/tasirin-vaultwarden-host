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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private static volatile Process process;
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
            if (process == null || !process.isAlive() || !running) {
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
        return p != null && p.isAlive();
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
                        startServer();
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
                    if (process == null || !process.isAlive()) {
                        stopForeground(true);
                        stopSelf();
                    }
                }
            }, "vw-tg-sched").start();
            return START_NOT_STICKY;
        }
        autoRestart = true;
        startForegroundCompat();
        if (process == null || !process.isAlive()) {
            startServer();
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
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
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
        File binary = extractBinary();
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
            boolean ctrlOk = controlServer.start(portNum + 1);
            String ctrlUrl = "";
            if (ctrlOk) {
                ctrlUrl = scheme + "://" + lanHost() + ":" + ControlServer.listeningPort;
                appendLog("[app] Status web (log realtime): " + ctrlUrl);
            } else {
                appendLog("[app] Status web tidak start (port " + (portNum + 1) + " dipakai).");
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
                    TgBackup.sendMessage(this, "Server crash (exit " + code
                            + ")\n" + shorten(tail, 500) + "\nRestart otomatis...");
                    setStatus("Server crash (exit " + code
                            + ") - restart otomatis\n" + shorten(tail, 250));
                    scheduleRestart();
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
            TgBackup.sendMessage(this, "Server berhenti: gagal restart 5x.\n"
                    + shorten(tail, 600));
            return;
        }
        long delay = RESTART_DELAYS[restartAttempt++];
        setStatus("Server crash - restart dalam " + (delay / 1000) + " dtk (coba " + restartAttempt + ")");
        appendLog("[app] Crash terdeteksi, restart dalam " + delay + " ms");
        mainHandler.postDelayed(() -> {
            if (autoRestart && (process == null || !process.isAlive())) {
                startServer();
            }
        }, delay);
    }

    private void pumpOutput(Process p) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
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
            TgBackup.sendMessage(this, "Server tidak sehat (3x gagal /alive) - dihentikan.");
            if (process != null) {
                final Process p = process;
                process = null;
                running = false;
                releaseWakeLock();
                p.destroy();
            }
            return;
        }
        TgBackup.sendMessage(this,
                "Server tidak sehat (" + healthFails + "/3) - restart otomatis...");
        appendLog("[health] Restart otomatis...");
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
                startServer();
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

    private File extractBinary() {
        try {
            File binDir = new File(getFilesDir(), "bin");
            if (!binDir.exists() && !binDir.mkdirs()) {
                appendLog("[app] Gagal membuat folder binary.");
                return null;
            }
            File out = new File(binDir, "vaultwarden-" + ABI);
            // Binary bundel baru (APK update) bisa beda ukuran walau versi sama;
            // kecuali user sudah update binary manual (KEY_UPDATE_VERSION), jangan
            // timpa file update-nya. Ukuran file sama -> biarkan (tidak perlu baca ulang).
            long bundledSize = -1;
            try {
                bundledSize = getAssets().openFd("bin/" + ABI + "/vaultwarden").getLength();
            } catch (Exception ignored) {
            }
            boolean updated = !getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_UPDATE_VERSION, "").isEmpty();
            boolean valid = out.exists() && out.length() > 1_000_000
                    && (updated || bundledSize <= 0 || out.length() == bundledSize);
            if (!valid) {
                try (InputStream in = getAssets().open("bin/" + ABI + "/vaultwarden");
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                }
                out.setReadable(true, false);
                out.setExecutable(true, false);
                appendLog("[app] Binary diekstrak: " + out.getAbsolutePath());
            }
            detectBinaryVersion(out);
            return out;
        } catch (Exception e) {
            appendLog("[app] Gagal ekstrak binary: " + e);
            return null;
        }
    }

    private File extractWebVault() {
        try {
            File dir = new File(getFilesDir(), "web-vault");
            File index = new File(dir, "index.html");
            if (index.exists()) {
                return dir;
            }
            // APK baru tidak lagi membundel web-vault.zip (ukuran APK jauh lebih
            // kecil); web vault diunduh sekali lewat tombol "Update Web Vault".
            boolean bundled = false;
            try (InputStream check = getAssets().open("web-vault.zip")) {
                bundled = true;
            } catch (Exception ignored) {
            }
            if (!bundled) {
                appendLog("[app] Web vault belum terpasang - buka app lalu tekan 'Update Web Vault' untuk mengunduh sekali.");
                return null;
            }
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = getAssets().open("web-vault.zip");
                 ZipInputStream zis = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        new File(dir, entry.getName()).mkdirs();
                    } else {
                        File outFile = new File(dir, entry.getName());
                        if (!outFile.getCanonicalPath().startsWith(dir.getCanonicalPath())) {
                            continue;
                        }
                        outFile.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            int n;
                            while ((n = zis.read(buf)) > 0) {
                                fos.write(buf, 0, n);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }
            if (index.exists()) {
                appendLog("[app] Web vault siap di " + dir.getAbsolutePath());
                return dir;
            }
            return null;
        } catch (Exception e) {
            appendLog("[app] Gagal ekstrak web-vault: " + e);
            return null;
        }
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
    private static boolean isPortBusy(int port) {
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
            if (!p.isAlive()) {
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
