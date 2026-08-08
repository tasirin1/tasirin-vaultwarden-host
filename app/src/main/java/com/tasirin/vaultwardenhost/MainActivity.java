package com.tasirin.vaultwardenhost;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.Html;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final int REQ_WRITE = 1001;
    private static final int REQ_RESTORE = 1002;
    private static final int REQ_IMPORT = 1003;
    private static final String DEFAULT_DATA_DIR = ServerService.DEFAULT_DATA_DIR;
    private static final String DEFAULT_PORT = ServerService.DEFAULT_PORT;
    private static final String KEY_PIN = "pin_hash";
    private static final String KEY_PIN_ON = "pin_on";
    private static final String KEY_TG_NOTIFIED = "tg_notified_version";
    private static final String KEY_ADVANCED_OPEN = "advanced_open";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText dataDirInput;
    private EditText portInput;
    private EditText adminTokenInput;
    private CheckBox autoStartCheck;
    private CheckBox httpsCheck;
    private CheckBox tgAutoCheck;
    private CheckBox backupOnStartCheck;
    private EditText tgTokenInput;
    private EditText tgChatInput;
    private EditText backupPassInput;
    private EditText pinInput;
    private CheckBox pinEnabledCheck;
    private Button restoreTgBtn;
    private TextView statusView;
    private TextView versionView;
    private TextView netInfoView;
    private TextView restartHint;
    private TextView updateHint;
    private CheckBox autoUpdateCb;
    private volatile String pendingVersion = null;
    private Button logOpenBtn;
    private Button startStopBtn;
    private Button advancedToggleBtn;
    private LinearLayout advancedPanel;
    private LinearLayout batteryRow;
    private Button copyUrlBtn;
    private Button exportCfgBtn;
    private Button importCfgBtn;
    private Button installCertBtn;
    private Button updateBtn;
    private Button revertBtn;
    private Button updateWvBtn;
    private Button backupDbBtn;
    private Button restoreDbBtn;
    private Button backupTgBtn;
    private Button aboutBtn;
    private Button statusWebBtn;
    private CheckBox tgFullCheck;

    private String bundledVersion = "?";
    private String appVersion = "";
    private String lastShownStatus = "";
    private String lastShownVersion = "";
    private String lastShownNet = "";
    private boolean advancedOpen = false;
    private boolean refreshActive = true;
    private long lastWvCheck = 0;
    private String wvLine = "";
    private long lastDbCheck = 0;
    private String dbLine = "";
    private static final long WV_CHECK_MS = 10_000;
    private static final long DB_CHECK_MS = 5_000;

    private static boolean unlocked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Splash ditampilkan lewat theme manifest, ganti ke tema utama di sini.
        setTheme(R.style.Theme_TasirinVaultwardenHost);
        super.onCreate(savedInstanceState);
        // Privasi: nonaktifkan screenshot + preview recents dikosongkan
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);

        dataDirInput = findViewById(R.id.dataDir);
        portInput = findViewById(R.id.port);
        adminTokenInput = findViewById(R.id.adminToken);
        autoStartCheck = findViewById(R.id.autoStart);
        httpsCheck = findViewById(R.id.https);
        tgTokenInput = findViewById(R.id.tgToken);
        tgChatInput = findViewById(R.id.tgChat);
        tgAutoCheck = findViewById(R.id.tgAuto);
        backupOnStartCheck = findViewById(R.id.backupOnStart);
        backupPassInput = findViewById(R.id.backupPass);
        pinInput = findViewById(R.id.pinInput);
        pinEnabledCheck = findViewById(R.id.pinEnabled);
        statusView = findViewById(R.id.status);
        versionView = findViewById(R.id.version);
        netInfoView = findViewById(R.id.netInfo);
        restartHint = findViewById(R.id.restartHint);
        updateHint = findViewById(R.id.updateHint);
        autoUpdateCb = findViewById(R.id.autoUpdate);
        logOpenBtn = findViewById(R.id.logOpen);
        startStopBtn = findViewById(R.id.startStop);
        advancedToggleBtn = findViewById(R.id.advancedToggle);
        advancedPanel = findViewById(R.id.advancedPanel);
        batteryRow = findViewById(R.id.batteryRow);

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
        copyUrlBtn = findViewById(R.id.copyUrl);
        exportCfgBtn = findViewById(R.id.exportCfg);
        importCfgBtn = findViewById(R.id.importCfg);
        installCertBtn = findViewById(R.id.installCert);
        Button showAdminBtn = findViewById(R.id.showAdmin);
        Button showTgBtn = findViewById(R.id.showTg);
        Button showPassBtn = findViewById(R.id.showPass);
        aboutBtn = findViewById(R.id.aboutBtn);
        statusWebBtn = findViewById(R.id.statusWeb);
        tgFullCheck = findViewById(R.id.tgFull);

        startStopBtn.setOnClickListener(v -> {
            if (ServerService.running) {
                ServerService.stop(this);
            } else {
                saveAndStart();
            }
        });
        openBtn.setOnClickListener(v -> openWebUi());
        updateBtn.setOnClickListener(v -> runBusy(this::checkForUpdate));
        revertBtn.setOnClickListener(v -> confirm("Reset Binary",
                "Hapus binary tersimpan. Versi terbaru akan diunduh ulang "
                        + "otomatis saat Start berikutnya (perlu internet). Lanjutkan?",
                () -> runBusy(this::revertToBundled)));
        certBtn.setOnClickListener(v -> showCertHelp());
        installCertBtn.setOnClickListener(v -> installCertificate());
        updateWvBtn.setOnClickListener(v -> runWebVaultUpdate(false));
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
        logOpenBtn.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        advancedToggleBtn.setOnClickListener(v -> setAdvancedOpen(!advancedOpen));
        restoreTgBtn.setOnClickListener(v -> restoreFromTelegram());
        copyUrlBtn.setOnClickListener(v -> copyLocalUrl());
        exportCfgBtn.setOnClickListener(v -> runBusy(this::exportConfig));
        importCfgBtn.setOnClickListener(v -> pickImportFile());
        showAdminBtn.setOnClickListener(v -> togglePassword(adminTokenInput, showAdminBtn));
        showTgBtn.setOnClickListener(v -> togglePassword(tgTokenInput, showTgBtn));
        showPassBtn.setOnClickListener(v -> togglePassword(backupPassInput, showPassBtn));
        aboutBtn.setOnClickListener(v -> showAboutDialog());
        statusWebBtn.setOnClickListener(v -> openStatusWeb());

        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        dataDirInput.setText(sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR));
        portInput.setText(ServerService.effectivePort(sp));
        adminTokenInput.setText(sp.getString(ServerService.KEY_ADMIN_TOKEN, ""));
        autoStartCheck.setChecked(sp.getBoolean(ServerService.KEY_AUTO_START, false));
        httpsCheck.setChecked(sp.getBoolean(ServerService.KEY_HTTPS, false));
        tgTokenInput.setText(sp.getString(TgBackup.KEY_TG_TOKEN, ""));
        tgChatInput.setText(sp.getString(TgBackup.KEY_TG_CHAT, ""));
        tgAutoCheck.setChecked(sp.getBoolean(TgBackup.KEY_TG_AUTO, false));
        backupOnStartCheck.setChecked(sp.getBoolean(TgBackup.KEY_TG_BACKUP_ON_START, false));
        autoUpdateCb.setChecked(sp.getBoolean(ServerService.KEY_AUTO_UPDATE, false));
        backupPassInput.setText(sp.getString(TgBackup.KEY_TG_PASS, ""));
        tgFullCheck.setChecked(sp.getBoolean(TgBackup.KEY_TG_FULL, false));
        pinInput.setText("");
        pinEnabledCheck.setChecked(sp.getBoolean(KEY_PIN_ON, false));
        setAdvancedOpen(sp.getBoolean(KEY_ADVANCED_OPEN, false));

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
        backupOnStartCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(TgBackup.KEY_TG_BACKUP_ON_START, checked).apply());
        autoUpdateCb.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_AUTO_UPDATE, checked).apply());
        tgFullCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(TgBackup.KEY_TG_FULL, checked).apply());
        tgTokenInput.addTextChangedListener(new SimpleTextWatcher(TgBackup.KEY_TG_TOKEN));
        tgChatInput.addTextChangedListener(new SimpleTextWatcher(TgBackup.KEY_TG_CHAT));
        tgTokenInput.addTextChangedListener(onTextChanged(() -> TgBot.schedule(this)));
        tgChatInput.addTextChangedListener(onTextChanged(() -> TgBot.schedule(this)));
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
        try {
            appVersion = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        ui.post(this::refreshFromService);

        // Auto-update check on launch
        new Thread(this::autoUpdateCheck, "vw-auto-check").start();
        // Pastikan jadwal backup harian tetap terpasang
        TgBackup.schedule(this, sp.getBoolean(TgBackup.KEY_TG_AUTO, false));
        // Remote kontrol via Telegram bot
        TgBot.schedule(this);

    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshActive = true;
        ui.post(this::refreshFromService);
        // Auto-lock PIN setiap kali app kembali ke depan
        maybeShowPinLock();
    }

    @Override
    protected void onPause() {
        super.onPause();
        refreshActive = false;
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        if (sp.getBoolean(KEY_PIN_ON, false)) {
            unlocked = false;
        }
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

        if (!webVaultReady(dataDir)) {
            // APK baru tidak membundel web-vault (ukuran jauh lebih kecil);
            // unduh sekali saat Start pertama bila diizinkan.
            new AlertDialog.Builder(this)
                    .setTitle("Web vault belum terpasang")
                    .setMessage("APK baru tidak lagi menyertakan web vault agar ukurannya kecil.\n"
                            + "Unduh sekali (~35 MB) supaya web UI bisa dibuka dari browser?\n\n"
                            + "Tanpa web vault, server dan aplikasi Bitwarden tetap jalan normal.")
                    .setPositiveButton("Unduh & Start", (d, w) -> {
                        runBusy(() -> {
                            try {
                                String msg = Updater.updateWebVault(this);
                                appendUiLog("[app] " + msg);
                            } catch (Exception e) {
                                toast("Gagal unduh web-vault: " + e.getMessage());
                                appendUiLog("[app] Gagal unduh web-vault: " + e);
                            }
                            ServerService.start(this);
                        });
                        maybeAutoBackup();
                    })
                    .setNegativeButton("Start tanpa web vault", (d, w) -> {
                        ServerService.start(this);
                        maybeAutoBackup();
                    })
                    .show();
            return;
        }
        ServerService.start(this);
        maybeAutoBackup();
    }

    private boolean webVaultReady(String dataDir) {
        return new File(dataDir, "web-vault/index.html").exists()
                || new File(getFilesDir(), "web-vault/index.html").exists();
    }

    private void refreshFromService() {
        // Chip status: Berjalan / Berhenti + tombol Start/Stop tunggal
        boolean running = ServerService.running;
        String statusText = running ? "Berjalan" : "Berhenti";
        String key = statusText + "|" + (running ? "on" : "off");
        if (!key.equals(lastShownStatus)) {
            statusView.setText(statusText);
            statusView.setBackgroundResource(running
                    ? R.drawable.bg_status_running : R.drawable.bg_status_stopped);
            lastShownStatus = key;
        }
        String btnText = running ? "Stop" : "Start";
        if (!btnText.equals(startStopBtn.getText().toString())) {
            startStopBtn.setText(btnText);
        }

        // Peringatan bila setting diubah tapi server belum di-restart
        boolean changed = false;
        if (running) {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String d = sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
            String p = ServerService.effectivePort(sp);
            boolean h = sp.getBoolean(ServerService.KEY_HTTPS, false);
            String a = sp.getString(ServerService.KEY_ADMIN_TOKEN, "");
            changed = !d.equals(ServerService.runningDataDir)
                    || !p.equals(ServerService.runningPort)
                    || h != ServerService.runningHttps
                    || !a.equals(ServerService.runningAdminToken);
        }
        restartHint.setVisibility(changed ? View.VISIBLE : View.GONE);

        // Peringatan bila update binary tersedia tapi belum dipasang
        boolean updAvail = false;
        if (pendingVersion != null) {
            SharedPreferences psp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String up = psp.getString(ServerService.KEY_UPDATE_VERSION, "");
            String cur = Updater.normVersion(up != null && !up.isEmpty()
                    ? up : Updater.readBundledVersionRaw(this));
            if (cur != null && cur.equals(pendingVersion)) {
                pendingVersion = null; // update sudah terpasang
            } else {
                updAvail = !running;
            }
        }
        if (updAvail) {
            updateHint.setText("\u26A0 Update v" + pendingVersion
                    + " tersedia \u2014 tekan Cek Update");
            updateHint.setVisibility(View.VISIBLE);
        } else {
            updateHint.setVisibility(View.GONE);
        }

        String version = "App " + appVersion;
        if (!ServerService.binaryVersion.isEmpty()) {
            version += " \u00B7 Binary: " + ServerService.binaryVersion;
        } else {
            version += " \u00B7 " + bundledVersion;
        }
        String dbInfo = dbInfoLine();
        String full = dbInfo.isEmpty() ? version : version + "\n" + dbInfo;
        String wv = webVaultInfoLine();
        if (!wv.isEmpty()) {
            full += "\n" + wv;
        }
        if (!full.equals(lastShownVersion)) {
            versionView.setText(full);
            lastShownVersion = full;
        }

        String net = ServerService.localUrl(this);
        if (!net.equals(lastShownNet)) {
            netInfoView.setText(net);
            lastShownNet = net;
        }

        // Baris "Izinkan akses penuh" hanya muncul bila battery optimization aktif
        boolean needBattery = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
        if (needBattery) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            needBattery = pm == null || !pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        batteryRow.setVisibility(needBattery ? View.VISIBLE : View.GONE);

        if (refreshActive) {
            ui.postDelayed(this::refreshFromService, 1000);
        }
    }

    private String readBundledVersion() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getAssets().open("vw_version.txt"), StandardCharsets.UTF_8))) {
            String v = r.readLine();
            return (v == null || v.trim().isEmpty()) ? "?" : "Versi: " + v.trim();
        } catch (Exception e) {
            return "Versi: ?";
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

    private void openStatusWeb() {
        if (!ServerService.running || !ControlServer.running
                || ControlServer.listeningPort <= 0) {
            toast("Status web belum aktif. Start server dulu.");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://" + ServerService.localIp()
                            + ":" + ControlServer.listeningPort)));
        } catch (Exception e) {
            toast("Gagal membuka status web: " + e.getMessage());
        }
    }

    private void showAboutDialog() {
        String dataDir = dataDirInput.getText().toString().trim();
        if (TextUtils.isEmpty(dataDir)) {
            dataDir = DEFAULT_DATA_DIR;
        }
        String bin = currentServerVersion();
        String wv = readWvVersion(new File(dataDir, "web-vault/vw-version.json"));
        StringBuilder html = new StringBuilder();
        html.append("<b>Tasirin Vaultwarden Host</b><br/>")
                .append("Menjalankan server <b>Vaultwarden</b> (Bitwarden-compatible) "
                        + "langsung di Android 5+ / TV.<br/><br/>")
                .append("Versi app: <b>").append(appVersion.isEmpty() ? "?" : appVersion)
                .append("</b><br/>")
                .append("Binary server: <b>")
                .append(bin == null ? "?" : "v" + bin).append("</b><br/>");
        if (wv != null) {
            html.append("Web vault: <b>v").append(wv).append("</b><br/>");
        }
        html.append("Perangkat: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")<br/><br/>")
                .append("Cara pakai:<br/>")
                .append("1. Isi folder data &amp; port<br/>")
                .append("2. Tekan <b>Start</b><br/>")
                .append("3. Buka URL di baris atas lewat browser<br/>")
                .append("4. Opsional: hubungkan bot Telegram untuk kontrol jarak jauh<br/><br/>")
                .append("Sumber kode: <a href=\"https://github.com/tasirin1/"
                        + "tasirin-vaultwarden-host\">github.com/tasirin1/"
                        + "tasirin-vaultwarden-host</a><br/>")
                .append("Lisensi: GPL-3.0 (aplikasi) \u00B7 AGPL-3.0 (Vaultwarden)");

        TextView tv = new TextView(this);
        float d = getResources().getDisplayMetrics().density;
        tv.setPadding((int) (20 * d), (int) (16 * d), (int) (20 * d), (int) (8 * d));
        tv.setTextSize(13);
        tv.setText(Html.fromHtml(html.toString()));
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setLinkTextColor(0xFF1E88E5);
        new AlertDialog.Builder(this)
                .setTitle("Tentang")
                .setView(tv)
                .setPositiveButton("Tutup", null)
                .show();
    }

    private void showCertHelp() {
        String dataDir = dataDirInput.getText().toString().trim();
        if (TextUtils.isEmpty(dataDir)) {
            dataDir = DEFAULT_DATA_DIR;
        }
        new AlertDialog.Builder(this)
                .setTitle("Cara install sertifikat")
                .setMessage("File: " + dataDir + "/tls/cert.pem\n\n"
                        + "1. Tekan tombol \"Install Cert\" - installer Android langsung terbuka "
                        + "dalam mode CA certificate.\n\n"
                        + "2. Kalau manual: Settings > Security > Install certificate "
                        + "> CA certificate, lalu pilih cert.pem.\n\n"
                        + "PENTING:\n"
                        + "- Pilih jenis \"CA certificate\", BUKAN \"User certificate\". "
                        + "Kalau muncul \"butuh private key\", itu karena kamu memilih "
                        + "\"User certificate\".\n"
                        + "- key.pem milik server - JANGAN di-install atau disebar.\n"
                        + "- HTTPS aktif setelah Start berikutnya (browser tetap menampilkan "
                        + "peringatan self-signed pada koneksi pertama).")
                .setPositiveButton("Oke", null)
                .show();
    }

    /** Buka installer sertifikat Android langsung dalam mode CA (tanpa pilih manual). */
    private void installCertificate() {
        try {
            String dataDir = dataDirInput.getText().toString().trim();
            if (TextUtils.isEmpty(dataDir)) {
                dataDir = DEFAULT_DATA_DIR;
            }
            File cert = new File(dataDir, "tls/cert.pem");
            if (!cert.exists()) {
                toast("Sertifikat belum ada. Aktifkan HTTPS lalu tekan Start dulu.");
                return;
            }
            Uri uri = Uri.parse("content://" + FileShareProvider.AUTHORITY
                    + cert.getAbsolutePath());
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/x-x509-ca-cert");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            toast("Gagal membuka installer: " + e.getMessage());
        }
    }

    // ─── Auto-update check (versi binary yang benar-benar dipakai) ──────

    private void autoUpdateCheck() {
        try {
            String latest = Updater.latestVersion(this);
            if (latest == null) {
                return;
            }
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String updated = sp.getString(ServerService.KEY_UPDATE_VERSION, "");
            String current = Updater.normVersion(updated != null && !updated.isEmpty()
                    ? updated : Updater.readBundledVersionRaw(this));
            if (current != null && !current.equals(latest)) {
                if (sp.getBoolean(ServerService.KEY_AUTO_UPDATE, false)
                        && isUnmeteredNetwork()) {
                    // Auto-update: pasang binary langsung (hanya WiFi/ethernet)
                    try {
                        String msg = Updater.tryUpdate(this);
                        pendingVersion = null;
                        ui.post(() -> {
                            toast(msg);
                            appendUiLog("[app] " + msg);
                        });
                    } catch (Exception e) {
                        ui.post(() -> appendUiLog("[app] Auto-update gagal: " + e.getMessage()));
                        pendingVersion = latest;
                        showUpdateNotification(latest);
                    }
                } else {
                    pendingVersion = latest;
                    ui.post(() -> toast("Update tersedia: v" + latest));
                    showUpdateNotification(latest);
                    // Notifikasi ke Telegram cukup sekali per versi
                    if (!latest.equals(sp.getString(KEY_TG_NOTIFIED, ""))) {
                        sp.edit().putString(KEY_TG_NOTIFIED, latest).apply();
                        TgBackup.sendMessage(this, "Update Vaultwarden v" + latest
                                + " tersedia. Kirim /update ke bot untuk memasang dari jauh.");
                    }
                }
            }
            TgBackup.notifyLowStorage(this);
            autoOfferWebVaultUpdate();
        } catch (Exception ignored) {
        }
    }

    /** Auto-update binary hanya di jaringan non-kuota (WiFi/ethernet). */
    private boolean isUnmeteredNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return !cm.isActiveNetworkMetered();
            }
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && (ni.getType() == ConnectivityManager.TYPE_WIFI
                    || ni.getType() == ConnectivityManager.TYPE_ETHERNET);
        } catch (Exception e) {
            return false;
        }
    }

    /** Tawarkan update web vault sekali per versi bila versinya beda dari server. */
    private void autoOfferWebVaultUpdate() {
        try {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String dataDir = sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
            if (!webVaultReady(dataDir)) {
                return;
            }
            String updated = Updater.webVaultFromVersion(this);
            if (updated == null) {
                updated = readWvVersion(new File(dataDir, "web-vault/vw-version.json"));
            }
            if (updated == null) {
                return;
            }
            String server = currentServerVersion();
            if (server == null || server.equals(updated)) {
                return;
            }
            final String key = "wv_notified_" + server;
            if (sp.getBoolean(key, false)) {
                return;
            }
            sp.edit().putBoolean(key, true).apply();
            ui.post(() -> new AlertDialog.Builder(this)
                    .setTitle("Update Web Vault tersedia")
                    .setMessage("Web vault saat ini v" + updated
                            + ", sedangkan server v" + server + ".\n\n"
                            + "Update web vault sekarang? (unduh sekali ~35 MB, "
                            + "berlaku setelah server di-restart)")
                    .setPositiveButton("Update & Restart",
                            (d, w) -> runWebVaultUpdate(true))
                    .setNeutralButton("Update saja",
                            (d, w) -> runWebVaultUpdate(false))
                    .setNegativeButton("Nanti", null)
                    .show());
        } catch (Exception ignored) {
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
                .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(2, n);
        }
    }

    // ─── Update web-vault ───────────────────────────────────────────────

    /** Unduh web-vault; opsional restart server agar update langsung berlaku. */
    private void runWebVaultUpdate(boolean restartAfter) {
        runBusy(() -> {
            try {
                appendUiLog("[app] Mengunduh web-vault terbaru...");
                String msg = Updater.updateWebVault(this);
                toast(msg);
                appendUiLog("[app] " + msg);
                lastWvCheck = 0; // paksa baca ulang info versi web-vault
                if (restartAfter && ServerService.running) {
                    appendUiLog("[app] Restart server agar web vault berlaku...");
                    ServerService.restart(this);
                }
            } catch (Exception e) {
                toast("Gagal update web-vault: " + e.getMessage());
                appendUiLog("[app] Gagal update web-vault: " + e);
            }
        });
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
        } else if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            confirm("Import Pengaturan",
                    "Semua pengaturan app akan diganti dari file ini. Lanjutkan?",
                    () -> runBusy(() -> importConfig(uri)));
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

            // Baca config dulu (bila backup lengkap) untuk tahu folder data tujuan.
            JSONObject cfg = null;
            try (ZipInputStream probe = new ZipInputStream(
                    new java.io.FileInputStream(zip))) {
                ZipEntry e;
                while ((e = probe.getNextEntry()) != null) {
                    if ("app-config.json".equals(e.getName())) {
                        cfg = new JSONObject(
                                new String(readAll(probe), StandardCharsets.UTF_8));
                        break;
                    }
                }
            }
            String dataDir = dataDirInput.getText().toString().trim();
            if (TextUtils.isEmpty(dataDir)) {
                dataDir = DEFAULT_DATA_DIR;
            }
            if (cfg != null) {
                JSONObject cfgPrefs = cfg.optJSONObject("prefs");
                if (cfgPrefs != null && cfgPrefs.has(ServerService.KEY_DATA_DIR)) {
                    String d = cfgPrefs.optString(ServerService.KEY_DATA_DIR, "").trim();
                    if (!d.isEmpty()) {
                        dataDir = d;
                    }
                }
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
                    if ("app-config.json".equals(entry.getName())) {
                        continue; // pengaturan diterapkan langsung, tidak ditulis ke disk
                    }
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
            if (cfg != null) {
                applyPrefs(cfg.optJSONObject("prefs"));
            }
            ui.post(() -> {
                reloadSettingsFromPrefs();
                SharedPreferences sp2 = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
                TgBackup.schedule(MainActivity.this,
                        sp2.getBoolean(TgBackup.KEY_TG_AUTO, false));
                TgBot.schedule(MainActivity.this);
                toast("Restore selesai. Tekan Start untuk menjalankan.");
            });
            appendUiLog("[app] Restore dari Telegram selesai" + (cfg != null ? " (lengkap)" : "") + ".");
        } catch (Exception e) {
            toast("Gagal restore: " + e.getMessage());
            appendUiLog("[app] Gagal restore: " + e);
        }
    }

    /** Terapkan seluruh prefs dari JSON (clear + tulis ulang). */
    private void applyPrefs(JSONObject prefs) throws Exception {
        if (prefs == null) {
            return;
        }
        SharedPreferences.Editor ed = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit();
        ed.clear();
        Iterator<String> keys = prefs.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = prefs.get(k);
            if (v instanceof String) {
                ed.putString(k, (String) v);
            } else if (v instanceof Boolean) {
                ed.putBoolean(k, (Boolean) v);
            } else if (v instanceof Integer) {
                ed.putInt(k, (Integer) v);
            } else if (v instanceof Long) {
                ed.putLong(k, (Long) v);
            } else if (v instanceof Double) {
                ed.putLong(k, ((Double) v).longValue());
            }
        }
        ed.apply();
    }

    // ─── Export / Import pengaturan ─────────────────────────────────────

    private void exportConfig() {
        try {
            String dataDir = dataDirInput.getText().toString().trim();
            if (TextUtils.isEmpty(dataDir)) {
                dataDir = DEFAULT_DATA_DIR;
            }
            File backupDir = new File(dataDir, "backups");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File out = new File(backupDir, "app-config-" + ts + ".json");

            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            byte[] bytes = TgBackup.configJson(sp).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
            final String path = out.getAbsolutePath();
            ui.post(() -> {
                Uri uri = Uri.parse("content://" + FileShareProvider.AUTHORITY + path);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("application/json");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(send, "Bagikan file konfigurasi"));
                } catch (Exception e2) {
                    toast("File tersimpan: " + path);
                }
                toast("Konfigurasi diekspor: " + path);
                appendUiLog("[app] Config export: " + path);
            });
        } catch (Exception e) {
            toast("Gagal export config: " + e.getMessage());
            appendUiLog("[app] Gagal export config: " + e);
        }
    }

    private void pickImportFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/json");
            startActivityForResult(intent, REQ_IMPORT);
        } catch (Exception e) {
            toast("Gagal membuka file picker: " + e.getMessage());
        }
    }

    private void importConfig(Uri uri) {
        try {
            String json;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                json = new String(readAll(in), StandardCharsets.UTF_8);
            }
            JSONObject root = new JSONObject(json);
            JSONObject prefs = root.optJSONObject("prefs");
            if (prefs == null) {
                toast("File config tidak valid.");
                return;
            }
            applyPrefs(prefs);
            ui.post(() -> {
                reloadSettingsFromPrefs();
                SharedPreferences sp2 = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
                TgBackup.schedule(MainActivity.this,
                        sp2.getBoolean(TgBackup.KEY_TG_AUTO, false));
                TgBot.schedule(MainActivity.this);
                toast("Pengaturan diimpor. Tekan Start agar berlaku.");
                appendUiLog("[app] Config import selesai.");
            });
        } catch (Exception e) {
            toast("Gagal import config: " + e.getMessage());
            appendUiLog("[app] Gagal import config: " + e);
        }
    }

    /** Muat ulang isi form dari prefs (dipakai setelah import config). */
    private void reloadSettingsFromPrefs() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        dataDirInput.setText(sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR));
        portInput.setText(ServerService.effectivePort(sp));
        adminTokenInput.setText(sp.getString(ServerService.KEY_ADMIN_TOKEN, ""));
        autoStartCheck.setChecked(sp.getBoolean(ServerService.KEY_AUTO_START, false));
        httpsCheck.setChecked(sp.getBoolean(ServerService.KEY_HTTPS, false));
        tgTokenInput.setText(sp.getString(TgBackup.KEY_TG_TOKEN, ""));
        tgChatInput.setText(sp.getString(TgBackup.KEY_TG_CHAT, ""));
        tgAutoCheck.setChecked(sp.getBoolean(TgBackup.KEY_TG_AUTO, false));
        backupOnStartCheck.setChecked(sp.getBoolean(TgBackup.KEY_TG_BACKUP_ON_START, false));
        autoUpdateCb.setChecked(sp.getBoolean(ServerService.KEY_AUTO_UPDATE, false));
        backupPassInput.setText(sp.getString(TgBackup.KEY_TG_PASS, ""));
        tgFullCheck.setChecked(sp.getBoolean(TgBackup.KEY_TG_FULL, false));
        pinEnabledCheck.setChecked(sp.getBoolean(KEY_PIN_ON, false));
    }

    private void copyLocalUrl() {
        String url = ServerService.localUrl(this);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("vaultwarden-url", url));
            toast("URL disalin: " + url);
        } else {
            toast(url);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
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
        long now = System.currentTimeMillis();
        if (now - lastDbCheck < DB_CHECK_MS) {
            return dbLine;
        }
        lastDbCheck = now;
        try {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String dataDir = sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
            File db = new File(dataDir, "db.sqlite3");
            if (!db.exists()) {
                dbLine = "";
                return dbLine;
            }
            File backupDir = new File(dataDir, "backups");
            File[] files = backupDir.listFiles();
            int n = files == null ? 0 : files.length;
            dbLine = "DB: " + TgBackup.humanBytes(db.length()) + " | Backup lokal: " + n;
        } catch (Exception e) {
            dbLine = "";
        }
        return dbLine;
    }

    /** Baris info versi web-vault (bundled/updated) + peringatan bila beda dari server. */
    private String webVaultInfoLine() {
        long now = System.currentTimeMillis();
        if (now - lastWvCheck < WV_CHECK_MS) {
            return wvLine;
        }
        lastWvCheck = now;
        try {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String dataDir = sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
            String updated = Updater.webVaultFromVersion(this);
            if (updated == null) {
                updated = readWvVersion(new File(dataDir, "web-vault/vw-version.json"));
            }
            String bundled = Updater.readBundledVersionRaw(this);
            String wv = updated != null ? updated
                    : (bundled != null ? Updater.normVersion(bundled) : null);
            if (wv == null) {
                wvLine = "";
                return wvLine;
            }
            StringBuilder sb = new StringBuilder("Web vault: ").append(wv)
                    .append(updated != null ? " (updated)" : " (bundled)");
            String server = currentServerVersion();
            if (server != null && !server.equals(wv)) {
                sb.append(" \u26A0 beda versi server v").append(server);
            }
            wvLine = sb.toString();
        } catch (Exception e) {
            wvLine = "";
        }
        return wvLine;
    }

    /** Versi dari file vw-version.json (web-vault yang sudah di-update) atau null. */
    private String readWvVersion(File f) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            String v = new JSONObject(sb.toString()).optString("version", "");
            return v.isEmpty() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    /** Versi binary yang benar-benar dipakai server saat ini. */
    private String currentServerVersion() {
        if (!ServerService.binaryVersion.isEmpty()) {
            return ServerService.binaryVersion;
        }
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String updated = sp.getString(ServerService.KEY_UPDATE_VERSION, "");
        if (updated != null && !updated.isEmpty()) {
            return updated;
        }
        return Updater.readBundledVersionRaw(this);
    }

    // ─── Update binary (unduh per tag versi resmi) ──────────────────────

    private void checkForUpdate() {
        try {
            appendUiLog("[app] Mengecek update dari sumber resmi...");
            String msg = Updater.tryUpdate(this);
            if (msg.startsWith("Update v")) {
                pendingVersion = null;
            }
            appendUiLog("[app] " + msg);
            toast(msg.startsWith("Update v")
                    ? msg + " Tekan Start untuk memakai."
                    : msg);
        } catch (Exception e) {
            toast("Gagal cek update: " + e.getMessage());
            appendUiLog("[app] Gagal cek update: " + e);
        }
    }

    private void revertToBundled() {
        File out = new File(getFilesDir(), "bin/vaultwarden-" + ServerService.ABI);
        new File(getFilesDir(), "bin/version.txt").delete();
        if (out.exists() && out.delete()) {
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .remove(ServerService.KEY_UPDATE_VERSION).apply();
            ServerService.binaryVersion = "";
            toast("Binary dihapus. Akan diunduh ulang saat Start.");
            appendUiLog("[app] Binary di-reset; akan diunduh ulang saat Start.");
        } else {
            toast("Tidak ada binary tersimpan.");
        }
    }

    /** Sorot baris GAGAL/ERROR/FAILED merah dan kata kunci pencarian kuning. */
    private void togglePassword(EditText et, Button btn) {
        boolean hidden = (et.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
        et.setInputType(hidden
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setSelection(et.getText().length());
        btn.setText(hidden ? "Sembunyi" : "Lihat");
    }

    /** Backup Telegram otomatis saat Start (maks. sekali per 24 jam). */
    private void maybeAutoBackup() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        if (!sp.getBoolean(TgBackup.KEY_TG_BACKUP_ON_START, false)) {
            return;
        }
        String token = sp.getString(TgBackup.KEY_TG_TOKEN, "").trim();
        String chat = sp.getString(TgBackup.KEY_TG_CHAT, "").trim();
        if (token.isEmpty() || chat.isEmpty()) {
            appendUiLog("[tg] Backup otomatis saat Start dilewati: token/chat belum diisi.");
            return;
        }
        long last = sp.getLong(TgBackup.KEY_TG_LAST, 0);
        if (System.currentTimeMillis() - last < TgBackup.TG_INTERVAL_MS) {
            return; // backup terakhir masih kurang dari 24 jam yang lalu
        }
        appendUiLog("[tg] Backup otomatis saat Start akan dijalankan...");
        new Thread(() -> {
            try {
                Thread.sleep(5000); // tunggu sebentar agar DB terbentuk setelah start
                final String msg = TgBackup.backupNow(MainActivity.this);
                ui.post(() -> {
                    toast(msg);
                    appendUiLog("[tg] " + msg);
                });
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                appendUiLog("[tg] Gagal backup otomatis saat Start: " + e);
            }
        }, "vw-tg-onstart").start();
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
    private void setAdvancedOpen(boolean open) {
        advancedOpen = open;
        advancedPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        advancedToggleBtn.setText(open ? "Lanjutan \u25BE" : "Lanjutan \u25B8");
        getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ADVANCED_OPEN, open).apply();
    }

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
                statusView.setText("Bekerja\u2026");
                statusView.setBackgroundResource(R.drawable.bg_status_busy);
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

    /** Watcher ringkas untuk aksi onTextChanged tanpa boilerplate. */
    private android.text.TextWatcher onTextChanged(final Runnable r) {
        return new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                r.run();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        };
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
