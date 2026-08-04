package com.vaultwarden.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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

public class MainActivity extends Activity {

    private static final int REQ_WRITE = 1001;
    private static final String PREFS = "vw_prefs";
    private static final String DEFAULT_DATA_DIR = "/sdcard/vaultwarden";
    private static final String DEFAULT_PORT = "8080";
    private static final int MAX_LOG_CHARS = 200_000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText dataDirInput;
    private EditText portInput;
    private TextView statusView;
    private TextView logView;
    private ScrollView scrollView;

    private final StringBuilder logBuffer = new StringBuilder();
    private Process serverProcess;
    private PowerManager.WakeLock wakeLock;
    private File logFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dataDirInput = findViewById(R.id.dataDir);
        portInput = findViewById(R.id.port);
        statusView = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        scrollView = findViewById(R.id.scroll);

        Button startBtn = findViewById(R.id.start);
        Button stopBtn = findViewById(R.id.stop);
        Button openBtn = findViewById(R.id.open);
        startBtn.setOnClickListener(v -> startServer());
        stopBtn.setOnClickListener(v -> stopServer());
        openBtn.setOnClickListener(v -> openWebUi());

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        dataDirInput.setText(sp.getString("data_dir", DEFAULT_DATA_DIR));
        portInput.setText(sp.getString("port", DEFAULT_PORT));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            }
        }
    }

    private void startServer() {
        if (serverProcess != null && serverProcess.isAlive()) {
            toast("Server sudah berjalan");
            return;
        }

        String dataDir = dataDirInput.getText().toString().trim();
        if (TextUtils.isEmpty(dataDir)) {
            dataDir = DEFAULT_DATA_DIR;
        }
        String port = portInput.getText().toString().trim();
        if (TextUtils.isEmpty(port)) {
            port = DEFAULT_PORT;
        }

        File dataFolder = new File(dataDir);
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            toast("Gagal membuat folder: " + dataDir);
            return;
        }
        if (!dataFolder.canWrite()) {
            toast("Folder tidak bisa ditulis: " + dataDir + "\nPastikan izin storage diberikan.");
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
            pb.environment().put("WEB_VAULT_ENABLED", "false");
            pb.environment().put("RUST_LOG", "info");
            pb.environment().put("DOMAIN", detectDomain(port));
            pb.redirectErrorStream(true);

            serverProcess = pb.start();
            acquireWakeLock();

            logBuffer.setLength(0);
            logFile = new File(dataFolder, "vaultwarden.log");

            final Process p = serverProcess;
            Thread reader = new Thread(() -> pumpOutput(p), "vw-output");
            reader.setDaemon(true);
            reader.start();

            Thread watcher = new Thread(() -> watchProcess(p), "vw-watch");
            watcher.setDaemon(true);
            watcher.start();

            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("data_dir", dataDir)
                    .putString("port", port)
                    .apply();

            appendLog("[app] start: " + binary.getAbsolutePath()
                    + " | data=" + dataDir + " | port=" + port);
            updateStatus("Running (PID " + getPid(p) + ")\nData: " + dataDir
                    + "\nURL lokal: http://127.0.0.1:" + port);
        } catch (Exception e) {
            serverProcess = null;
            releaseWakeLock();
            appendLog("[app] ERROR start: " + e);
            toast("Gagal start: " + e.getMessage());
        }
    }

    private void stopServer() {
        if (serverProcess == null) {
            updateStatus("Stopped");
            releaseWakeLock();
            return;
        }
        final Process p = serverProcess;
        updateStatus("Stopping...");
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
        releaseWakeLock();
    }

    private void watchProcess(Process p) {
        try {
            int code = p.waitFor();
            ui.post(() -> {
                if (serverProcess == p) {
                    serverProcess = null;
                    releaseWakeLock();
                    updateStatus("Stopped (exit code " + code + ")");
                    appendLog("[app] process exit: " + code);
                }
            });
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
        ui.post(() -> {
            synchronized (logBuffer) {
                logView.setText(logBuffer.toString());
            }
            scrollView.fullScroll(View.FOCUS_DOWN);
        });
    }

    private File extractBinary() {
        String abi = null;
        for (String supported : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(supported)
                    || "armeabi-v7a".equals(supported)
                    || "x86_64".equals(supported)) {
                abi = supported;
                break;
            }
        }
        if (abi == null) {
            toast("ABI tidak didukung: " + TextUtils.join(", ", Build.SUPPORTED_ABIS));
            return null;
        }
        try {
            File binDir = new File(getFilesDir(), "bin");
            if (!binDir.exists() && !binDir.mkdirs()) {
                toast("Gagal membuat folder binary");
                return null;
            }
            File out = new File(binDir, "vaultwarden-" + abi);
            try (InputStream in = getAssets().open("bin/" + abi + "/vaultwarden");
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            out.setReadable(true, false);
            out.setExecutable(true, false);
            return out;
        } catch (Exception e) {
            appendLog("[app] Gagal ekstrak binary: " + e);
            toast("Binary vaultwarden tidak ditemukan di APK (" + abi + ").\n"
                    + "Gunakan APK hasil build GitHub Actions.");
            return null;
        }
    }

    private void openWebUi() {
        String port = portInput.getText().toString().trim();
        if (TextUtils.isEmpty(port)) {
            port = DEFAULT_PORT;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://127.0.0.1:" + port)));
        } catch (Exception e) {
            toast("Tidak bisa membuka browser: " + e.getMessage());
        }
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
        // Process.pid() tidak tersedia di stub Android pada semua versi API;
        // gunakan reflection agar tetap kompilasi dan aman di API < 26.
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

    private void updateStatus(String text) {
        ui.post(() -> statusView.setText(text));
    }

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        if (serverProcess != null) {
            serverProcess.destroy();
        }
    }
}
