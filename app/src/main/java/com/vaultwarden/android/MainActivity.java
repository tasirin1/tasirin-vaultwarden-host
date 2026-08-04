package com.vaultwarden.android;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    private static final int REQ_WRITE = 1001;
    private static final int REQ_RESTORE = 1002;
    private static final String DEFAULT_DATA_DIR = "/sdcard/vaultwarden";
    private static final String DEFAULT_PORT = "8080";
    private static final String UPDATE_URL = "https://github.com/tasirin1/vaultwardenhostingandroid/releases/latest/download/";
    private static final String LATEST_API = "https://api.github.com/repos/tasirin1/vaultwardenhostingandroid/releases/latest";
    private static final String WV_UPDATE_URL = UPDATE_URL + "web-vault.zip";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText dataDirInput;
    private EditText portInput;
    private EditText adminTokenInput;
    private CheckBox autoStartCheck;
    private CheckBox httpsCheck;
    private TextView statusView;
    private TextView versionView;
    private TextView logView;

    private String bundledVersion = "?";
    private String lastShownStatus = "";
    private String lastShownVersion = "";
    private int lastShownLogLen = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dataDirInput = findViewById(R.id.dataDir);
        portInput = findViewById(R.id.port);
        adminTokenInput = findViewById(R.id.adminToken);
        autoStartCheck = findViewById(R.id.autoStart);
        httpsCheck = findViewById(R.id.https);
        statusView = findViewById(R.id.status);
        versionView = findViewById(R.id.version);
        logView = findViewById(R.id.log);

        Button startBtn = findViewById(R.id.start);
        Button stopBtn = findViewById(R.id.stop);
        Button openBtn = findViewById(R.id.open);
        Button updateBtn = findViewById(R.id.update);
        Button revertBtn = findViewById(R.id.revert);
        Button certBtn = findViewById(R.id.cert);
        Button updateWvBtn = findViewById(R.id.updateWv);
        Button backupDbBtn = findViewById(R.id.backupDb);
        Button restoreDbBtn = findViewById(R.id.restoreDb);
        Button batteryBtn = findViewById(R.id.batteryBtn);

        startBtn.setOnClickListener(v -> saveAndStart());
        stopBtn.setOnClickListener(v -> ServerService.stop(this));
        openBtn.setOnClickListener(v -> openWebUi());
        updateBtn.setOnClickListener(v -> new Thread(this::checkForUpdate, "vw-update").start());
        revertBtn.setOnClickListener(v -> revertToBundled());
        certBtn.setOnClickListener(v -> showCertHelp());
        updateWvBtn.setOnClickListener(v -> new Thread(this::updateWebVault, "vw-wv-update").start());
        backupDbBtn.setOnClickListener(v -> backupDatabase());
        restoreDbBtn.setOnClickListener(v -> pickRestoreFile());
        batteryBtn.setOnClickListener(v -> requestBatteryExemption());

        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        dataDirInput.setText(sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR));
        portInput.setText(sp.getString(ServerService.KEY_PORT, DEFAULT_PORT));
        adminTokenInput.setText(sp.getString(ServerService.KEY_ADMIN_TOKEN, ""));
        autoStartCheck.setChecked(sp.getBoolean(ServerService.KEY_AUTO_START, false));
        httpsCheck.setChecked(sp.getBoolean(ServerService.KEY_HTTPS, false));

        autoStartCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_AUTO_START, checked).apply());
        httpsCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_HTTPS, checked).apply());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            }
        }

        bundledVersion = readBundledVersion();
        ui.post(this::refreshFromService);

        // Auto-update check on launch
        new Thread(this::autoUpdateCheck, "vw-auto-check").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(this::refreshFromService);
    }

    private void saveAndStart() {
        String dataDir = dataDirInput.getText().toString().trim();
        String port = portInput.getText().toString().trim();
        String adminToken = adminTokenInput.getText().toString().trim();

        SharedPreferences.Editor ed = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit();
        ed.putString(ServerService.KEY_DATA_DIR, TextUtils.isEmpty(dataDir) ? DEFAULT_DATA_DIR : dataDir);
        ed.putString(ServerService.KEY_PORT, TextUtils.isEmpty(port) ? DEFAULT_PORT : port);
        ed.putString(ServerService.KEY_ADMIN_TOKEN, adminToken);
        ed.apply();

        ServerService.start(this);
    }

    private void refreshFromService() {
        String status = ServerService.statusLine;
        if (status == null || status.isEmpty()) {
            status = ServerService.running ? "Running..." : "Stopped";
        }
        if (!status.equals(lastShownStatus)) {
            statusView.setText(status);
            lastShownStatus = status;
        }

        String version = bundledVersion;
        if (!ServerService.binaryVersion.isEmpty()) {
            version = "Binary: " + ServerService.binaryVersion;
        }
        if (!version.equals(lastShownVersion)) {
            versionView.setText(version);
            lastShownVersion = version;
        }

        synchronized (ServerService.logBuffer) {
            int len = ServerService.logBuffer.length();
            if (len != lastShownLogLen) {
                logView.setText(ServerService.logBuffer.toString());
                lastShownLogLen = len;
            }
        }

        ui.postDelayed(this::refreshFromService, 1000);
    }

    private String readBundledVersion() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getAssets().open("vw_version.txt"), StandardCharsets.UTF_8))) {
            String v = r.readLine();
            return (v == null || v.trim().isEmpty()) ? "?" : "Bundled: " + v.trim();
        } catch (Exception e) {
            return "Bundled: ?";
        }
    }

    private void openWebUi() {
        String port = portInput.getText().toString().trim();
        if (TextUtils.isEmpty(port)) {
            port = DEFAULT_PORT;
        }
        String scheme = httpsCheck.isChecked() ? "https" : "http";
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(scheme + "://127.0.0.1:" + port)));
        } catch (Exception e) {
            toast("Tidak bisa membuka browser: " + e.getMessage());
        }
    }

    private void showCertHelp() {
        String dataDir = dataDirInput.getText().toString().trim();
        if (TextUtils.isEmpty(dataDir)) {
            dataDir = DEFAULT_DATA_DIR;
        }
        toast("Sertifikat: " + dataDir + "/tls/cert.pem\n"
                + "Install: Settings > Security > Install certificate > CA certificate\n"
                + "HTTPS aktif setelah Start berikutnya (browser akan tetap menampilkan peringatan self-signed).");
    }

    // ─── Feature 2: Auto-update check on launch ─────────────────────────

    private void autoUpdateCheck() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(LATEST_API).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) return;
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            String latest = extractTag(sb.toString());
            if (latest == null) return;
            String current = readBundledVersionRaw();
            if (current != null && !current.equals(latest)) {
                ui.post(() -> toast("Update tersedia: v" + latest));
                showUpdateNotification(latest);
            }
        } catch (Exception ignored) {
        }
    }

    private String readBundledVersionRaw() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getAssets().open("vw_version.txt"), StandardCharsets.UTF_8))) {
            String v = r.readLine();
            return (v == null || v.trim().isEmpty()) ? null : v.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private void showUpdateNotification(String version) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel("vw_updates",
                        "Vaultwarden Update", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
        }
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);
        android.app.Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new android.app.Notification.Builder(this, "vw_updates");
        } else {
            b = new android.app.Notification.Builder(this);
        }
        android.app.Notification n = b.setContentTitle("Vaultwarden Update")
                .setContentText("v" + version + " tersedia")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(2, n);
        }
    }

    // ─── Feature 1: Update web-vault ─────────────────────────────────────

    private void updateWebVault() {
        try {
            appendUiLog("[app] Mengunduh web-vault terbaru...");
            String dataDir = dataDirInput.getText().toString().trim();
            if (TextUtils.isEmpty(dataDir)) dataDir = DEFAULT_DATA_DIR;

            File dataFolder = new File(dataDir);
            if (!dataFolder.exists()) dataFolder.mkdirs();

            File targetDir = new File(dataFolder, "web-vault");
            File tmpZip = new File(dataFolder, "web-vault.zip.tmp");

            HttpURLConnection dl = (HttpURLConnection) new URL(WV_UPDATE_URL).openConnection();
            dl.setConnectTimeout(20000);
            dl.setReadTimeout(120000);
            dl.setInstanceFollowRedirects(true);
            int code = dl.getResponseCode();
            if (code != 200) {
                toast("Gagal unduh web-vault (HTTP " + code + ")");
                return;
            }
            long size = dl.getContentLength();
            try (InputStream in = dl.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tmpZip)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            if (tmpZip.length() < 1000) {
                tmpZip.delete();
                toast("File web-vault tidak valid.");
                return;
            }

            // Hapus web-vault lama
            deleteRecursive(targetDir);
            targetDir.mkdirs();

            // Ekstrak zip
            byte[] buf = new byte[64 * 1024];
            try (ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(tmpZip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        new File(targetDir, entry.getName()).mkdirs();
                    } else {
                        File outFile = new File(targetDir, entry.getName());
                        outFile.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            int n;
                            while ((n = zis.read(buf)) > 0) {
                                fos.write(buf, 0, n);
                            }
                        }
                    }
                }
            }
            tmpZip.delete();

            File index = new File(targetDir, "index.html");
            if (index.exists()) {
                toast("Web vault updated! Restart server untuk memakai.");
                appendUiLog("[app] Web vault updated di " + targetDir.getAbsolutePath());
            } else {
                toast("Web vault updated tapi index.html tidak ditemukan.");
            }
        } catch (Exception e) {
            toast("Gagal update web-vault: " + e.getMessage());
            appendUiLog("[app] Gagal update web-vault: " + e);
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        file.delete();
    }

    // ─── Feature 3: Battery optimization exemption ───────────────────────

    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("Battery optimization hanya untuk Android 6+.");
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("Sudah dikecualikan dari battery optimization.");
            return;
        }
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            toast("Gagal membuka pengaturan: " + e.getMessage());
        }
    }

    // ─── Feature 4: Backup & Restore database ────────────────────────────

    private void backupDatabase() {
        try {
            String dataDir = dataDirInput.getText().toString().trim();
            if (TextUtils.isEmpty(dataDir)) dataDir = DEFAULT_DATA_DIR;

            File dbFile = new File(dataDir, "db.sqlite3");
            if (!dbFile.exists()) {
                toast("Database belum ada: " + dbFile.getAbsolutePath());
                return;
            }

            File backupDir = new File(dataDir, "backups");
            if (!backupDir.exists()) backupDir.mkdirs();

            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File backup = new File(backupDir, "db-backup-" + timestamp + ".sqlite3");

            copyFile(dbFile, backup);
            toast("Backup tersimpan:\n" + backup.getAbsolutePath());
            appendUiLog("[app] Backup DB: " + backup.getName() + " (" + backup.length() + " bytes)");
        } catch (Exception e) {
            toast("Gagal backup: " + e.getMessage());
            appendUiLog("[app] Gagal backup: " + e);
        }
    }

    private void pickRestoreFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_RESTORE);
        } catch (Exception e) {
            toast("Gagal membuka file picker: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RESTORE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                String dataDir = dataDirInput.getText().toString().trim();
                if (TextUtils.isEmpty(dataDir)) dataDir = DEFAULT_DATA_DIR;

                File dbFile = new File(dataDir, "db.sqlite3");
                if (dbFile.exists()) {
                    // Backup dulu sebelum restore
                    File backupDir = new File(dataDir, "backups");
                    if (!backupDir.exists()) backupDir.mkdirs();
                    String ts = new SimpleDateFormat("yyyyMMdd-HHmmss-pre", Locale.US).format(new Date());
                    copyFile(dbFile, new File(backupDir, "db-backup-" + ts + ".sqlite3"));
                }

                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(dbFile)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                }
                toast("Database direstore. Restart server untuk memakai.");
                appendUiLog("[app] DB direstore dari URI. Ukuran: " + dbFile.length() + " bytes");
            } catch (Exception e) {
                toast("Gagal restore: " + e.getMessage());
                appendUiLog("[app] Gagal restore: " + e);
            }
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
    }

    // ─── Update binary ───────────────────────────────────────────────────

    private void checkForUpdate() {
        try {
            appendUiLog("[app] Mengecek update...");
            HttpURLConnection conn = (HttpURLConnection) new URL(LATEST_API).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code != 200) {
                toast("Gagal cek update (HTTP " + code + ").");
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            String latest = extractTag(sb.toString());
            if (latest == null) {
                toast("Tidak bisa baca versi terbaru.");
                return;
            }

            String abi = ServerService.getAbi();
            if (abi == null) {
                toast("ABI tidak didukung.");
                return;
            }

            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String current = sp.getString(ServerService.KEY_UPDATE_VERSION, null);
            if (latest.equals(current)) {
                toast("Sudah versi terbaru: v" + latest);
                return;
            }

            String assetUrl = UPDATE_URL + "vaultwarden-" + abi;
            appendUiLog("[app] Mengunduh update v" + latest + " dari " + assetUrl);

            File out = new File(getFilesDir(), "bin/vaultwarden-" + abi);
            File tmp = new File(getFilesDir(), "bin/vaultwarden-" + abi + ".tmp");
            File binDir = out.getParentFile();
            if (binDir != null && !binDir.exists()) {
                binDir.mkdirs();
            }
            HttpURLConnection dl = (HttpURLConnection) new URL(assetUrl).openConnection();
            dl.setConnectTimeout(20000);
            dl.setReadTimeout(60000);
            dl.setInstanceFollowRedirects(true);
            code = dl.getResponseCode();
            if (code != 200) {
                toast("Unduhan gagal (HTTP " + code + ").");
                return;
            }
            long size = dl.getContentLength();
            try (InputStream in = dl.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            if (tmp.length() < 1_000_000 || !isElf(tmp)) {
                tmp.delete();
                toast("File update tidak valid.");
                return;
            }
            if (out.exists()) out.delete();
            if (!tmp.renameTo(out)) {
                tmp.delete();
                toast("Gagal menyimpan update.");
                return;
            }
            out.setReadable(true, false);
            out.setExecutable(true, false);

            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .putString(ServerService.KEY_UPDATE_VERSION, latest).apply();
            ServerService.binaryVersion = "";
            toast("Update v" + latest + " terpasang. Tekan Start untuk memakai.");
            appendUiLog("[app] Update v" + latest + " terpasang. (" + (size > 0 ? size : "?") + " bytes)");
        } catch (Exception e) {
            toast("Gagal cek update: " + e.getMessage());
            appendUiLog("[app] Gagal cek update: " + e);
        }
    }

    private void revertToBundled() {
        String abi = ServerService.getAbi();
        if (abi == null) return;
        File out = new File(getFilesDir(), "bin/vaultwarden-" + abi);
        if (out.exists() && out.delete()) {
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .remove(ServerService.KEY_UPDATE_VERSION).apply();
            ServerService.binaryVersion = "";
            toast("Binary bawaan dipulihkan. Tekan Start.");
            appendUiLog("[app] Kembali ke binary bawaan APK.");
        } else {
            toast("Tidak ada update terpasang.");
        }
    }

    private boolean isElf(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] magic = new byte[4];
            int n = in.read(magic);
            return n == 4 && magic[0] == 0x7F && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    private String extractTag(String body) {
        String key = "\"tag_name\":";
        int i = body.indexOf(key);
        if (i < 0) return null;
        int s = body.indexOf('"', i + key.length());
        int e = body.indexOf('"', s + 1);
        if (s < 0 || e < 0) return null;
        return body.substring(s + 1, e);
    }

    private void appendUiLog(String line) {
        synchronized (ServerService.logBuffer) {
            ServerService.logBuffer.append(line).append('\n');
        }
        ui.post(this::refreshFromService);
    }

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }
}
