package com.vaultwarden.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ScrollView;
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
import java.security.MessageDigest;
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
    private static final String KEY_PIN = "pin_hash";
    private static final String KEY_PIN_ON = "pin_on";
    // Cek versi dari sumber RESMI Vaultwarden (dani-garcia/vaultwarden).
    private static final String OFFICIAL_API = "https://api.github.com/repos/dani-garcia/vaultwarden/releases/latest";
    // Binary Android di-host di repo build (resmi tidak menyediakan biner Android).
    private static final String RELEASE_URL = "https://github.com/tasirin1/vaultwardenhostingandroid/releases/download/";
    private static final String WV_UPDATE_URL = "https://github.com/tasirin1/vaultwardenhostingandroid/releases/latest/download/web-vault.zip";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText dataDirInput;
    private EditText portInput;
    private EditText adminTokenInput;
    private CheckBox autoStartCheck;
    private CheckBox httpsCheck;
    private CheckBox tgAutoCheck;
    private EditText tgTokenInput;
    private EditText tgChatInput;
    private EditText backupPassInput;
    private EditText pinInput;
    private CheckBox pinEnabledCheck;
    private Button restoreTgBtn;
    private TextView statusView;
    private TextView versionView;
    private TextView logView;
    private ScrollView logScroll;
    private Button logToggleBtn;
    private Button updateBtn;
    private Button revertBtn;
    private Button updateWvBtn;
    private Button backupDbBtn;
    private Button restoreDbBtn;
    private Button backupTgBtn;

    private String bundledVersion = "?";
    private String lastShownStatus = "";
    private String lastShownVersion = "";
    private int lastShownLogLen = -1;

    private static boolean unlocked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dataDirInput = findViewById(R.id.dataDir);
        portInput = findViewById(R.id.port);
        adminTokenInput = findViewById(R.id.adminToken);
        autoStartCheck = findViewById(R.id.autoStart);
        httpsCheck = findViewById(R.id.https);
        tgTokenInput = findViewById(R.id.tgToken);
        tgChatInput = findViewById(R.id.tgChat);
        tgAutoCheck = findViewById(R.id.tgAuto);
        backupPassInput = findViewById(R.id.backupPass);
        pinInput = findViewById(R.id.pinInput);
        pinEnabledCheck = findViewById(R.id.pinEnabled);
        statusView = findViewById(R.id.status);
        versionView = findViewById(R.id.version);
        logView = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        logToggleBtn = findViewById(R.id.logToggle);

        Button startBtn = findViewById(R.id.start);
        Button stopBtn = findViewById(R.id.stop);
        Button openBtn = findViewById(R.id.open);
        updateBtn = findViewById(R.id.update);
        revertBtn = findViewById(R.id.revert);
        Button certBtn = findViewById(R.id.cert);
        updateWvBtn = findViewById(R.id.updateWv);
        backupDbBtn = findViewById(R.id.backupDb);
        restoreDbBtn = findViewById(R.id.restoreDb);
        Button batteryBtn = findViewById(R.id.batteryBtn);
        backupTgBtn = findViewById(R.id.backupTg);
        restoreTgBtn = findViewById(R.id.restoreTg);
        Button showAdminBtn = findViewById(R.id.showAdmin);
        Button showTgBtn = findViewById(R.id.showTg);
        Button showPassBtn = findViewById(R.id.showPass);

        startBtn.setOnClickListener(v -> saveAndStart());
        stopBtn.setOnClickListener(v -> ServerService.stop(this));
        openBtn.setOnClickListener(v -> openWebUi());
        updateBtn.setOnClickListener(v -> runBusy(this::checkForUpdate));
        revertBtn.setOnClickListener(v -> confirm("Revert ke binary bawaan",
                "Binary update akan dihapus dan diganti dengan binary bawaan APK. Lanjutkan?",
                () -> runBusy(this::revertToBundled)));
        certBtn.setOnClickListener(v -> showCertHelp());
        updateWvBtn.setOnClickListener(v -> runBusy(this::updateWebVault));
        backupDbBtn.setOnClickListener(v -> runBusy(this::backupDatabase));
        restoreDbBtn.setOnClickListener(v -> pickRestoreFile());
        batteryBtn.setOnClickListener(v -> requestBatteryExemption());
        backupTgBtn.setOnClickListener(v -> runBusy(() -> {
            try {
                String msg = TgBackup.backupNow(MainActivity.this);
                toast(msg);
                appendUiLog("[tg] " + msg);
            } catch (Exception e) {
                toast("Backup gagal: " + e.getMessage());
                appendUiLog("[tg] Gagal backup: " + e);
            }
        }));
        logToggleBtn.setOnClickListener(v -> toggleLog());
        restoreTgBtn.setOnClickListener(v -> restoreFromTelegram());
        showAdminBtn.setOnClickListener(v -> togglePassword(adminTokenInput, showAdminBtn));
        showTgBtn.setOnClickListener(v -> togglePassword(tgTokenInput, showTgBtn));
        showPassBtn.setOnClickListener(v -> togglePassword(backupPassInput, showPassBtn));

        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        dataDirInput.setText(sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR));
        portInput.setText(sp.getString(ServerService.KEY_PORT, DEFAULT_PORT));
        adminTokenInput.setText(sp.getString(ServerService.KEY_ADMIN_TOKEN, ""));
        autoStartCheck.setChecked(sp.getBoolean(ServerService.KEY_AUTO_START, false));
        httpsCheck.setChecked(sp.getBoolean(ServerService.KEY_HTTPS, false));
        tgTokenInput.setText(sp.getString(TgBackup.KEY_TG_TOKEN, ""));
        tgChatInput.setText(sp.getString(TgBackup.KEY_TG_CHAT, ""));
        tgAutoCheck.setChecked(sp.getBoolean(TgBackup.KEY_TG_AUTO, false));
        backupPassInput.setText(sp.getString(TgBackup.KEY_TG_PASS, ""));
        pinInput.setText("");
        pinEnabledCheck.setChecked(sp.getBoolean(KEY_PIN_ON, false));

        autoStartCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_AUTO_START, checked).apply());
        httpsCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_HTTPS, checked).apply());
        tgAutoCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(TgBackup.KEY_TG_AUTO, checked).apply();
            TgBackup.schedule(MainActivity.this, checked);
            toast(checked ? "Backup harian diaktifkan." : "Backup harian dimatikan.");
        });
        tgTokenInput.addTextChangedListener(new SimpleTextWatcher(TgBackup.KEY_TG_TOKEN));
        tgChatInput.addTextChangedListener(new SimpleTextWatcher(TgBackup.KEY_TG_CHAT));
        backupPassInput.addTextChangedListener(new SimpleTextWatcher(TgBackup.KEY_TG_PASS));
        pinInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                SharedPreferences.Editor ed = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit();
                if (s.length() >= 4) {
                    ed.putString(KEY_PIN, sha256(s.toString()));
                } else {
                    ed.remove(KEY_PIN);
                }
                ed.apply();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        pinEnabledCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            SharedPreferences sp2 = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            if (checked && sp2.getString(KEY_PIN, "").isEmpty()) {
                toast("Isi PIN dulu (minimal 4 digit).");
                b.setChecked(false);
                return;
            }
            sp2.edit().putBoolean(KEY_PIN_ON, checked).apply();
        });

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
        // Pastikan jadwal backup harian tetap terpasang
        TgBackup.schedule(this, sp.getBoolean(TgBackup.KEY_TG_AUTO, false));

        // Kunci app dengan PIN
        maybeShowPinLock();
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

        // Peringatan bila setting diubah tapi server belum di-restart
        String hint = "";
        if (ServerService.running) {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String d = sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
            String p = sp.getString(ServerService.KEY_PORT, DEFAULT_PORT);
            boolean h = sp.getBoolean(ServerService.KEY_HTTPS, false);
            String a = sp.getString(ServerService.KEY_ADMIN_TOKEN, "");
            if (!d.equals(ServerService.runningDataDir)
                    || !p.equals(ServerService.runningPort)
                    || h != ServerService.runningHttps
                    || !a.equals(ServerService.runningAdminToken)) {
                hint = "\n\u26A0 Setting diubah \u2014 restart server agar berlaku";
            }
        }
        String display = status + hint;
        if (!display.equals(lastShownStatus)) {
            statusView.setText(display);
            lastShownStatus = display;
        }

        String version = bundledVersion;
        if (!ServerService.binaryVersion.isEmpty()) {
            version = "Binary: " + ServerService.binaryVersion;
        }
        String dbInfo = dbInfoLine();
        String full = dbInfo.isEmpty() ? version : version + "\n" + dbInfo;
        if (!full.equals(lastShownVersion)) {
            versionView.setText(full);
            lastShownVersion = full;
        }

        synchronized (ServerService.logBuffer) {
            int len = ServerService.logBuffer.length();
            if (len != lastShownLogLen) {
                logView.setText(ServerService.logBuffer.toString());
                lastShownLogLen = len;
                if (logScroll != null) {
                    logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
                }
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

    // ─── Auto-update check (versi binary yang benar-benar dipakai) ──────

    private void autoUpdateCheck() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(OFFICIAL_API).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) {
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            String latest = normVersion(extractTag(sb.toString()));
            if (latest == null) {
                return;
            }
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String updated = sp.getString(ServerService.KEY_UPDATE_VERSION, "");
            String current = normVersion(updated != null && !updated.isEmpty()
                    ? updated : readBundledVersionRaw());
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

    // ─── Update web-vault ───────────────────────────────────────────────

    private void updateWebVault() {
        try {
            appendUiLog("[app] Mengunduh web-vault terbaru...");
            String dataDir = dataDirInput.getText().toString().trim();
            if (TextUtils.isEmpty(dataDir)) {
                dataDir = DEFAULT_DATA_DIR;
            }

            File dataFolder = new File(dataDir);
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

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
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        file.delete();
    }

    // ─── Battery optimization ───────────────────────────────────────────

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
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            toast("Gagal membuka pengaturan: " + e.getMessage());
        }
    }

    // ─── Backup & Restore database lokal ────────────────────────────────

    private void backupDatabase() {
        try {
            String dataDir = dataDirInput.getText().toString().trim();
            if (TextUtils.isEmpty(dataDir)) {
                dataDir = DEFAULT_DATA_DIR;
            }

            File dbFile = new File(dataDir, "db.sqlite3");
            if (!dbFile.exists()) {
                toast("Database belum ada: " + dbFile.getAbsolutePath());
                return;
            }
            long free = TgBackup.freeBytes(dataDir);
            if (free < 50L * 1024 * 1024) {
                toast("Peringatan: sisa penyimpanan tinggal " + TgBackup.humanBytes(free));
                appendUiLog("[app] Peringatan storage tinggal " + TgBackup.humanBytes(free));
            }

            File backupDir = new File(dataDir, "backups");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File backup = new File(backupDir, "db-backup-" + timestamp + ".sqlite3");

            copyFile(dbFile, backup);
            TgBackup.cleanupOldBackups(backupDir);
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
            final Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            confirm("Restore Database",
                    "Database saat ini akan diganti dengan file yang dipilih. "
                            + "Backup otomatis dibuat dulu. Lanjutkan?",
                    () -> runBusy(() -> restoreDatabase(uri)));
        }
    }

    private void restoreDatabase(Uri uri) {
        try {
            String dataDir = dataDirInput.getText().toString().trim();
            if (TextUtils.isEmpty(dataDir)) {
                dataDir = DEFAULT_DATA_DIR;
            }

            File dbFile = new File(dataDir, "db.sqlite3");
            if (dbFile.exists()) {
                File backupDir = new File(dataDir, "backups");
                if (!backupDir.exists()) {
                    backupDir.mkdirs();
                }
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss-pre", Locale.US).format(new Date());
                copyFile(dbFile, new File(backupDir, "db-backup-" + ts + ".sqlite3"));
                TgBackup.cleanupOldBackups(backupDir);
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
            appendUiLog("[app] DB direstore. Ukuran: " + dbFile.length() + " bytes");
        } catch (Exception e) {
            toast("Gagal restore: " + e.getMessage());
            appendUiLog("[app] Gagal restore: " + e);
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

    // ─── Restore dari Telegram ──────────────────────────────────────────

    private void restoreFromTelegram() {
        runBusy(() -> {
            try {
                File tmp = new File(getCacheDir(), "vwtg-restore.zip");
                String name = TgBackup.downloadLastBackup(MainActivity.this, tmp);

                File zip = tmp;
                SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
                String pass = sp.getString(TgBackup.KEY_TG_PASS, "");
                if (TgBackup.isEncrypted(tmp)) {
                    if (pass == null || pass.trim().isEmpty()) {
                        toast("Backup terenkripsi \u2014 isi password backup dulu.");
                        return;
                    }
                    File plain = new File(getCacheDir(), "vwtg-restore-dec.zip");
                    TgBackup.decryptFile(tmp, plain, pass.trim());
                    zip = plain;
                }
                final File finalZip = zip;
                final String fname = name;
                ui.post(() -> confirm("Restore dari Telegram",
                        "Gunakan backup '" + fname + "'? Server akan dihentikan dulu. Lanjutkan?",
                        () -> runBusy(() -> restoreFromZip(finalZip))));
            } catch (Exception e) {
                toast("Gagal ambil backup: " + e.getMessage());
                appendUiLog("[tg] Gagal ambil backup: " + e);
            }
        });
    }

    private void restoreFromZip(File zip) {
        try {
            if (ServerService.isProcessAlive()) {
                appendUiLog("[app] Menghentikan server sebelum restore...");
                ServerService.stopAndWait(this, 8000);
            }

            String dataDir = dataDirInput.getText().toString().trim();
            if (TextUtils.isEmpty(dataDir)) {
                dataDir = DEFAULT_DATA_DIR;
            }
            File dataFolder = new File(dataDir);
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            File dbFile = new File(dataFolder, "db.sqlite3");
            if (dbFile.exists()) {
                File backupDir = new File(dataFolder, "backups");
                if (!backupDir.exists()) {
                    backupDir.mkdirs();
                }
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss-pre", Locale.US).format(new Date());
                copyFile(dbFile, new File(backupDir, "db-backup-" + ts + ".sqlite3"));
                TgBackup.cleanupOldBackups(backupDir);
            }

            byte[] buf = new byte[64 * 1024];
            try (ZipInputStream zis = new ZipInputStream(
                    new java.io.FileInputStream(zip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File outFile = new File(dataFolder, entry.getName());
                    if (!outFile.getCanonicalPath().startsWith(dataFolder.getCanonicalPath())) {
                        continue;
                    }
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
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
            zip.delete();
            toast("Restore selesai. Tekan Start untuk menjalankan.");
            appendUiLog("[app] Restore dari Telegram selesai.");
        } catch (Exception e) {
            toast("Gagal restore: " + e.getMessage());
            appendUiLog("[app] Gagal restore: " + e);
        }
    }

    // ─── PIN lock ───────────────────────────────────────────────────────

    private void maybeShowPinLock() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        if (!sp.getBoolean(KEY_PIN_ON, false) || unlocked) {
            return;
        }
        final String pinHash = sp.getString(KEY_PIN, "");
        if (pinHash == null || pinHash.isEmpty()) {
            return;
        }
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setMaxLines(1);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Masukkan PIN")
                .setMessage("App dikunci")
                .setView(input)
                .setPositiveButton("Buka", null)
                .setNegativeButton("Keluar", (d, w) -> finish())
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String entered = input.getText().toString();
                    if (pinHash.equals(sha256(entered))) {
                        unlocked = true;
                        dialog.dismiss();
                    } else {
                        input.setError("PIN salah");
                    }
                }));
        dialog.show();
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return text;
        }
    }

    private String dbInfoLine() {
        try {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String dataDir = sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
            File db = new File(dataDir, "db.sqlite3");
            if (!db.exists()) {
                return "";
            }
            File backupDir = new File(dataDir, "backups");
            File[] files = backupDir.listFiles();
            int n = files == null ? 0 : files.length;
            return "DB: " + TgBackup.humanBytes(db.length()) + " | Backup lokal: " + n;
        } catch (Exception e) {
            return "";
        }
    }

    // ─── Update binary (unduh per tag versi resmi) ──────────────────────

    private void checkForUpdate() {
        try {
            appendUiLog("[app] Mengecek update dari sumber resmi...");
            HttpURLConnection conn = (HttpURLConnection) new URL(OFFICIAL_API).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code != 200) {
                toast("Gagal cek update (HTTP " + code + ").");
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            String latest = normVersion(extractTag(sb.toString()));
            if (latest == null) {
                toast("Tidak bisa baca versi terbaru.");
                return;
            }
            appendUiLog("[app] Versi resmi terbaru: v" + latest);

            String abi = ServerService.getAbi();
            if (abi == null) {
                toast("ABI tidak didukung.");
                return;
            }

            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String updated = sp.getString(ServerService.KEY_UPDATE_VERSION, "");
            String current = normVersion(updated != null && !updated.isEmpty()
                    ? updated : readBundledVersionRaw());
            if (current != null && current.equals(latest)) {
                toast("Sudah versi terbaru: v" + latest);
                return;
            }

            // Unduh per tag resmi; kalau build belum ada, beri tahu menunggu build otomatis
            String assetUrl = RELEASE_URL + "v" + latest + "/vaultwarden-" + abi;
            appendUiLog("[app] Mengunduh binary Android v" + latest
                    + " dari repo build (resmi tidak menyediakan biner Android).");

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
            if (code == 404) {
                toast("Build Android v" + latest
                        + " belum tersedia (build otomatis ~6 jam). Coba lagi nanti.");
                return;
            }
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
            if (out.exists()) {
                out.delete();
            }
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
            appendUiLog("[app] Update v" + latest + " terpasang. ("
                    + (size > 0 ? size : "?") + " bytes)");
        } catch (Exception e) {
            toast("Gagal cek update: " + e.getMessage());
            appendUiLog("[app] Gagal cek update: " + e);
        }
    }

    private void revertToBundled() {
        String abi = ServerService.getAbi();
        if (abi == null) {
            return;
        }
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
            return n == 4 && magic[0] == 0x7F && magic[1] == 'E'
                    && magic[2] == 'L' && magic[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    private String normVersion(String v) {
        if (v == null) {
            return null;
        }
        return v.startsWith("v") ? v.substring(1) : v;
    }

    private String extractTag(String body) {
        String key = "\"tag_name\":";
        int i = body.indexOf(key);
        if (i < 0) {
            return null;
        }
        int s = body.indexOf('"', i + key.length());
        int e = body.indexOf('"', s + 1);
        if (s < 0 || e < 0) {
            return null;
        }
        return body.substring(s + 1, e);
    }

    // ─── UI helpers ─────────────────────────────────────────────────────

    private void togglePassword(EditText et, Button btn) {
        boolean hidden = (et.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
        et.setInputType(hidden
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setSelection(et.getText().length());
        btn.setText(hidden ? "Sembunyi" : "Lihat");
    }

    private void toggleLog() {
        boolean visible = logScroll.getVisibility() == View.VISIBLE;
        logScroll.setVisibility(visible ? View.GONE : View.VISIBLE);
        logToggleBtn.setText(visible ? "Tampilkan" : "Sembunyikan");
        if (!visible) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void confirm(String title, String message, final Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Ya", (d, w) -> action.run())
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Nonaktifkan tombol aksi + tampilkan "Sedang bekerja…" selama operasi. */
    private void setBusy(final boolean busy) {
        ui.post(() -> {
            updateBtn.setEnabled(!busy);
            revertBtn.setEnabled(!busy);
            updateWvBtn.setEnabled(!busy);
            backupDbBtn.setEnabled(!busy);
            restoreDbBtn.setEnabled(!busy);
            backupTgBtn.setEnabled(!busy);
            restoreTgBtn.setEnabled(!busy);
            if (busy) {
                statusView.setText("Sedang bekerja\u2026");
                lastShownStatus = "";
            } else {
                refreshFromService();
            }
        });
    }

    private void runBusy(final Runnable task) {
        setBusy(true);
        new Thread(() -> {
            try {
                task.run();
            } finally {
                setBusy(false);
            }
        }, "vw-task").start();
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

    /** Simpan nilai EditText ke prefs begitu berubah. */
    private class SimpleTextWatcher implements android.text.TextWatcher {
        private final String key;

        SimpleTextWatcher(String key) {
            this.key = key;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .putString(key, s.toString()).apply();
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }
}
