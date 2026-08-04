package com.vaultwarden.android;

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
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ServerService extends Service {

    public static final String ACTION_START = "com.vaultwarden.android.START";
    public static final String ACTION_STOP = "com.vaultwarden.android.STOP";

    public static final String PREFS = "vw_prefs";
    public static final String KEY_DATA_DIR = "data_dir";
    public static final String KEY_PORT = "port";
    public static final String KEY_AUTO_START = "auto_start";
    public static final String KEY_UPDATE_VERSION = "update_version";

    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "vaultwarden_server";
    private static final int MAX_LOG_CHARS = 300_000;

    /** Keadaan yang dibaca UI. */
    public static volatile boolean running = false;
    public static volatile String statusLine = "Stopped";
    public static volatile String binaryVersion = "";
    public static final StringBuilder logBuffer = new StringBuilder();

    private Process process;
    private PowerManager.WakeLock wakeLock;
    private File logFile;

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

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        if (process == null || !process.isAlive()) {
            startServer();
        }
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
                .build();
        startForeground(NOTIF_ID, n);
    }

    private String currentPort() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String p = sp.getString(KEY_PORT, "8080");
        return (p == null || p.trim().isEmpty()) ? "8080" : p.trim();
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
        String dataDir = sp.getString(KEY_DATA_DIR, "/sdcard/vaultwarden");
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = "/sdcard/vaultwarden";
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

        File binary = extractBinary();
        if (binary == null) {
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath());
            pb.environment().put("DATA_FOLDER", dataDir);
            pb.environment().put("ROCKET_ADDRESS", "0.0.0.0");
            pb.environment().put("ROCKET_PORT", port);
            pb.environment().put("ROCKET_WORKERS", "2");
            File webVault = extractWebVault();
            if (webVault != null) {
                pb.environment().put("WEB_VAULT_ENABLED", "true");
                pb.environment().put("WEB_VAULT_FOLDER", webVault.getAbsolutePath());
            } else {
                pb.environment().put("WEB_VAULT_ENABLED", "false");
            }
            pb.environment().put("RUST_LOG", "info");
            String domain = detectDomain(port);
            pb.environment().put("DOMAIN", domain);
            pb.redirectErrorStream(true);

            process = pb.start();
            acquireWakeLock();

            logFile = new File(dataFolder, "vaultwarden.log");
            running = true;
            setStatus("Running (PID " + getPid(process) + ")\nData: " + dataDir
                    + "\nURL lokal (di HP): http://127.0.0.1:" + port
                    + "\nURL jaringan (dari PC/laptop): " + domain);

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
        releaseWakeLock();
    }

    private void watchProcess(Process p) {
        try {
            int code = p.waitFor();
            if (process == p) {
                process = null;
                running = false;
                releaseWakeLock();
                setStatus("Stopped (exit code " + code + ")");
                appendLog("[app] process exit: " + code);
            }
        } catch (InterruptedException ignored) {
        }
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
        synchronized (logBuffer) {
            logBuffer.append(line).append('\n');
            if (logBuffer.length() > MAX_LOG_CHARS) {
                logBuffer.delete(0, logBuffer.length() - MAX_LOG_CHARS / 2);
            }
        }
        if (logFile != null) {
            try (FileWriter w = new FileWriter(logFile, true)) {
                w.write(line + "\n");
            } catch (Exception ignored) {
            }
        }
    }

    private void setStatus(String text) {
        statusLine = text;
    }

    private File extractBinary() {
        String abi = getAbi();
        if (abi == null) {
            appendLog("[app] ABI tidak didukung.");
            return null;
        }
        try {
            File binDir = new File(getFilesDir(), "bin");
            if (!binDir.exists() && !binDir.mkdirs()) {
                appendLog("[app] Gagal membuat folder binary.");
                return null;
            }
            File out = new File(binDir, "vaultwarden-" + abi);
            boolean valid = out.exists() && out.length() > 1_000_000;
            if (!valid) {
                try (InputStream in = getAssets().open("bin/" + abi + "/vaultwarden");
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                }
            }
            out.setReadable(true, false);
            out.setExecutable(true, false);
            detectBinaryVersion(out);
            return out;
        } catch (Exception e) {
            appendLog("[app] Gagal ekstrak binary: " + e);
            return null;
        }
    }

    /** Ekstrak web vault dari assets (web-vault.zip) ke app data bila belum ada. */
    private File extractWebVault() {
        try {
            File dir = new File(getFilesDir(), "web-vault");
            File index = new File(dir, "index.html");
            if (index.exists()) {
                return dir;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            String dirPath = dir.getCanonicalPath();
            try (InputStream in = getAssets().open("web-vault.zip");
                 ZipInputStream zis = new ZipInputStream(in)) {
                ZipEntry entry;
                byte[] buf = new byte[64 * 1024];
                while ((entry = zis.getNextEntry()) != null) {
                    File outFile = new File(dir, entry.getName());
                    if (!outFile.getCanonicalPath().startsWith(dirPath + File.separator)) {
                        continue; // anti zip-slip
                    }
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        if (outFile.getParentFile() != null) {
                            outFile.getParentFile().mkdirs();
                        }
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

    /** ABI yang didukung untuk perangkat ini, atau null. */
    public static String getAbi() {
        for (String a : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(a) || "armeabi-v7a".equals(a) || "x86_64".equals(a)) {
                return a;
            }
        }
        return null;
    }

    private String detectDomain(String port) {
        try {
            List<InetAddress> addresses = new ArrayList<>();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address) {
                        addresses.add(addr);
                    }
                }
            }
            if (!addresses.isEmpty()) {
                return "http://" + addresses.get(0).getHostAddress() + ":" + port;
            }
        } catch (Exception ignored) {
        }
        return "http://localhost:" + port;
    }

    private String getPid(Process p) {
        try {
            java.lang.reflect.Method pid = Process.class.getMethod("pid");
            return String.valueOf(pid.invoke(p));
        } catch (Throwable t) {
            return "?";
        }
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
    }
}
