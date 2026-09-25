package com.tasirin.vaultwardenhost;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipOutputStream;

import org.json.JSONObject;

public class SettingsActivity extends Activity {

    private static final int REQ_WRITE = 1001;
    private static final int REQ_RESTORE = 1002;
    private static final int REQ_IMPORT = 1003;
    private static final String DEFAULT_DATA_DIR = ServerService.DEFAULT_DATA_DIR;
    private static final String DEFAULT_PORT = ServerService.DEFAULT_PORT;
    private static final String KEY_PIN = "pin_hash";
    private static final String KEY_PIN_ON = "pin_on";
    private static final String KEY_ADVANCED_OPEN = "advanced_open";

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
    private EditText binShaInput;
    private EditText pinInput;
    private CheckBox pinEnabledCheck;
    /** Hash PIN (PBKDF2 120rb iterasi) di worker agar tiap ketikan tak macetkan UI. */
    private final java.util.concurrent.ExecutorService pinExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int pinSeq;
    private volatile java.util.concurrent.Future<?> pinPending;
    private Button restoreTgBtn;
    private TextView statusView;
    private TextView versionView;
    private TextView netInfoView;
    private TextView restartHint;
    private TextView updateHint;
    private CheckBox autoUpdateCb;
    private CheckBox autoUpdateWvCb;
    private CheckBox autoRestartCb;
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
    private Button shareCaBtn;
    private Button updateBtn;
    private Button revertBtn;
    private Button updateWvBtn;
    private Button backupDbBtn;
    private Button restoreDbBtn;
    private Button backupTgBtn;
    private Button aboutBtn;

    private String bundledVersion = "?";
    private String bundledRaw = null;
    private String appVersion = "";
    private String lastShownStatus = "";
    private String lastShownVersion = "";
    private String lastShownNet = "";
    private boolean advancedOpen = false;
    private boolean refreshActive = true;
    private volatile boolean uiBusy = false;
    /** Cegah dua tugas berat (update/backup/restore) tumpang tindih. */
    private final AtomicBoolean tugasBerjalan = new AtomicBoolean(false);
    /** Guard agar setChecked programatik tak memicu ulang listener PIN. */
    private boolean pinCentangProgram = false;
    private long lastWvCheck = 0;
    private String wvLine = "";
    private long lastDbCheck = 0;
    private String dbLine = "";
    /** Cache daftar isi folder backups (TTL 5 dtk) agar dua info tak listFiles 2x. */
    private File[] cacheDaftarBackup = null;
    private String cacheDaftarBackupDir = "";
    private long cacheDaftarBackupAt = 0;
    private static final long DAFTAR_BACKUP_TTL_MS = 5_000;
    private long lastStorageCheck = 0;
    private String storageLine = "";
    private static final long WV_CHECK_MS = 10_000;
    private static final long DB_CHECK_MS = 5_000;
    private static final long STORAGE_CHECK_MS = 5_000;
    private static final long SYS_CHECK_MS = 5_000;
    private static final long CERT_CHECK_MS = 30_000;
    /** Guard agar I/O info berat hanya jalan satu worker dalam satu waktu. */
    private final AtomicBoolean heavyRunning = new AtomicBoolean(false);
    private long lastSysCheck = 0;
    private String sysLine = "";
    private long lastCertCheck = 0;
    private String certLine = "";
    private long lastUiLogRefresh = 0;
    private static final long BATTERY_CHECK_MS = 30_000;
    private long lastBatteryCheck = 0;
    private boolean needBatteryCached = false;

    private static boolean unlocked = false;
    /** Kapan MainActivity terakhir pause; kunci PIN baru muncul bila >60 detik. */
    private static long pauseStamp = 0;
    private static final long PIN_GRACE_MS = 60_000;

    @Override
    // getPackageInfo lama sengaja agar satu jalur kode untuk API 21-32 (varian Flags butuh API 33+).
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        // Splash ditampilkan lewat theme manifest, ganti ke tema utama di sini.
        setTheme(R.style.Theme_TasirinVaultwardenHost);
        super.onCreate(savedInstanceState);
        TgBackup.migrateAutoPref(this);
        // Privasi: nonaktifkan screenshot + preview recents dikosongkan
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_settings);

        dataDirInput = findViewById(R.id.dataDir);
        portInput = findViewById(R.id.port);
        adminTokenInput = findViewById(R.id.adminToken);
        autoStartCheck = findViewById(R.id.autoStart);
        httpsCheck = findViewById(R.id.https);
        tgTokenInput = findViewById(R.id.tgToken);
        tgChatInput = findViewById(R.id.tgChat);
        tgAutoCheck = findViewById(R.id.tgAuto);
        backupPassInput = findViewById(R.id.backupPass);
        binShaInput = findViewById(R.id.binSha);
        pinInput = findViewById(R.id.pinInput);
        pinEnabledCheck = findViewById(R.id.pinEnabled);
        statusView = findViewById(R.id.status);
        versionView = findViewById(R.id.version);
        netInfoView = findViewById(R.id.netInfo);
        restartHint = findViewById(R.id.restartHint);
        updateHint = findViewById(R.id.updateHint);
        autoUpdateCb = findViewById(R.id.autoUpdate);
        autoUpdateWvCb = findViewById(R.id.autoUpdateWv);
        autoRestartCb = findViewById(R.id.autoRestart);
        logOpenBtn = findViewById(R.id.logOpen);
        startStopBtn = findViewById(R.id.startStop);
        advancedToggleBtn = findViewById(R.id.advancedToggle);
        advancedPanel = findViewById(R.id.advancedPanel);
        batteryRow = findViewById(R.id.batteryRow);

        Button openBtn = findViewById(R.id.open);
        updateBtn = findViewById(R.id.update);
        revertBtn = findViewById(R.id.revert);
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
        installCertBtn.setOnClickListener(v -> installCertificate());
        shareCaBtn = findViewById(R.id.shareCa);
        if (shareCaBtn != null) {
            shareCaBtn.setOnClickListener(v -> bagikanCa());
        }
        updateWvBtn.setOnClickListener(v -> runWebVaultUpdate(true));
        backupDbBtn.setOnClickListener(v -> runBusy(this::backupDatabase));
        restoreDbBtn.setOnClickListener(v -> pickRestoreFile());
        batteryBtn.setOnClickListener(v -> requestBatteryExemption());
        backupTgBtn.setOnClickListener(v -> runBusy(() -> {
            try {
                String msg = TgBackup.backupNow(SettingsActivity.this);
                toast(msg);
                appendUiLog("[tg] " + msg);
            } catch (Exception e) {
                toast("Backup gagal: " + e.getMessage());
                appendUiLog("[tg] Gagal backup: " + e);
            }
        }));
        Button settingsBackBtn = findViewById(R.id.settingsBack);
        if (settingsBackBtn != null) {
            settingsBackBtn.setOnClickListener(v -> finish());
        }
        logOpenBtn.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        advancedToggleBtn.setOnClickListener(v -> setAdvancedOpen(!advancedOpen));
        restoreTgBtn.setOnClickListener(v -> restoreFromTelegram());
        copyUrlBtn.setOnClickListener(v -> copyLocalUrl());
        exportCfgBtn.setOnClickListener(v -> confirm("Export Pengaturan",
                "File tidak membawa token/PIN/password (tetap di perangkat ini)."
                        + " Tetap jangan bagikan ke orang lain. Lanjutkan?",
                () -> runBusy(this::exportConfig)));
        importCfgBtn.setOnClickListener(v -> pickImportFile());
        showAdminBtn.setOnClickListener(v -> togglePassword(adminTokenInput, showAdminBtn));
        showTgBtn.setOnClickListener(v -> togglePassword(tgTokenInput, showTgBtn));
        showPassBtn.setOnClickListener(v -> togglePassword(backupPassInput, showPassBtn));
        aboutBtn.setOnClickListener(v -> showAboutDialog());

        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        dataDirInput.setText(sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR));
        portInput.setText(ServerService.effectivePort(sp));
        adminTokenInput.setText(sp.getString(ServerService.KEY_ADMIN_TOKEN, ""));
        autoStartCheck.setChecked(sp.getBoolean(ServerService.KEY_AUTO_START, false));
        httpsCheck.setChecked(sp.getBoolean(ServerService.KEY_HTTPS, false));
        tgTokenInput.setText(sp.getString(TgBackup.KEY_TG_TOKEN, ""));
        tgChatInput.setText(sp.getString(TgBackup.KEY_TG_CHAT, ""));
        tgAutoCheck.setChecked(sp.getBoolean(TgBackup.KEY_TG_AUTO, false));
        autoUpdateCb.setChecked(sp.getBoolean(ServerService.KEY_AUTO_UPDATE, false));
        autoUpdateWvCb.setChecked(sp.getBoolean(ServerService.KEY_AUTO_UPDATE_WV, false));
        autoRestartCb.setChecked(sp.getBoolean(ServerService.KEY_AUTO_RESTART_UPDATE, false));
        backupPassInput.setText(sp.getString(TgBackup.KEY_TG_PASS, ""));
        binShaInput.setText(sp.getString(ServerService.KEY_BIN_SHA, ""));
        pinInput.setText("");
        pinEnabledCheck.setChecked(sp.getBoolean(KEY_PIN_ON, false));
        setAdvancedOpen(sp.getBoolean(KEY_ADVANCED_OPEN, true));

        autoStartCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_AUTO_START, checked).apply());
        httpsCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_HTTPS, checked).apply());
        tgAutoCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(TgBackup.KEY_TG_AUTO, checked).apply();
            TgBackup.schedule(SettingsActivity.this, checked);
            toast(checked ? "Backup otomatis diaktifkan." : "Backup otomatis dimatikan.");
        });
        autoUpdateCb.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_AUTO_UPDATE, checked).apply());
        autoUpdateWvCb.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_AUTO_UPDATE_WV, checked).apply());
        autoRestartCb.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_AUTO_RESTART_UPDATE, checked).apply());
        tgTokenInput.addTextChangedListener(new SimpleTextWatcher(TgBackup.KEY_TG_TOKEN));
        tgChatInput.addTextChangedListener(new SimpleTextWatcher(TgBackup.KEY_TG_CHAT));
        // Jadwal bot di-debounce: jangan pasang ulang alarm tiap karakter.
        tgTokenInput.addTextChangedListener(onTextChanged(this::scheduleBotDebounced));
        tgChatInput.addTextChangedListener(onTextChanged(this::scheduleBotDebounced));
        backupPassInput.addTextChangedListener(new SimpleTextWatcher(TgBackup.KEY_TG_PASS));
        binShaInput.addTextChangedListener(new SimpleTextWatcher(ServerService.KEY_BIN_SHA));
        pinInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (s.length() < 4) {
                    pinSeq++;
                    getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                            .edit().remove(KEY_PIN).apply();
                    return;
                }
                final String pin = s.toString();
                final int seq = ++pinSeq;
                pinPending = pinExec.submit(() -> {
                    String h = PinCrypto.hash(pin);
                    if (seq == pinSeq) {
                        getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                                .edit().putString(KEY_PIN, h).apply();
                    }
                });
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        pinEnabledCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            if (pinCentangProgram) {
                return;
            }
            if (!checked) {
                // Mematikan PIN tak butuh hash: langsung simpan tanpa blokir UI.
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                        .edit().putBoolean(KEY_PIN_ON, false).apply();
                return;
            }
            java.util.concurrent.Future<?> antre = pinPending;
            if (antre != null && !antre.isDone()) {
                toast("PIN masih diproses, coba lagi sebentar.");
                pinCentangProgram = true;
                b.setChecked(false);
                pinCentangProgram = false;
                return;
            }
            SharedPreferences sp2 = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            if (sp2.getString(KEY_PIN, "").isEmpty()) {
                toast("Isi PIN dulu (minimal 4 digit).");
                pinCentangProgram = true;
                b.setChecked(false);
                pinCentangProgram = false;
                return;
            }
            sp2.edit().putBoolean(KEY_PIN_ON, true).apply();
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            }
        }

        bundledVersion = readBundledVersion();
        bundledRaw = Updater.readBundledVersionRaw(this);
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
        // Jangan kunci langsung (pindah ke LogActivity bukan keluar app);
        // maybeShowPinLock mengunci bila jeda > PIN_GRACE_MS.
        pauseStamp = System.currentTimeMillis();
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

        int portNum = -1;
        try {
            portNum = Integer.parseInt(port.trim());
        } catch (Exception ignored) {
        }
        if (!port.isEmpty() && (portNum < 1 || portNum > 65535)) {
            toast("Port harus angka 1-65535.");
            appendUiLog("[app] Port tidak valid: '" + port + "' - Start dibatalkan.");
            return;
        }
        if (portNum > 0 && ServerService.isPortBusy(portNum)) {
            new AlertDialog.Builder(this)
                    .setTitle("Port " + portNum + " sedang dipakai")
                    .setMessage("Port utama (" + portNum + ") sudah dipakai proses lain.\n"
                            + "Stop aplikasi lain yang memakainya, ganti port di atas, "
                            + "atau restart HP dulu.")
                    .setPositiveButton("Oke", null)
                    .show();
            return;
        }

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
        // Sedang sibuk (update/backup)? Kunci chip status: progress unduh bila ada,
        // selain itu "Bekerja…" — jangan biarkan polling menimpa dengan status lama.
        if (uiBusy) {
            String dl = Updater.downloadStatus;
            statusView.setText(dl.isEmpty() ? getString(R.string.busy_work) : dl);
            statusView.setBackgroundResource(R.drawable.bg_status_busy);
            lastShownStatus = "";
            if (refreshActive) {
                ui.postDelayed(this::refreshFromService, 500);
            }
            return;
        }

        // Chip status: Berjalan / Berhenti + tombol Start/Stop tunggal
        boolean running = ServerService.running;
        String statusText = running ? getString(R.string.running)
                : getString(R.string.stopped);
        String key = statusText + "|" + (running ? "on" : "off");
        if (!key.equals(lastShownStatus)) {
            statusView.setText(statusText);
            statusView.setBackgroundResource(running
                    ? R.drawable.bg_status_running : R.drawable.bg_status_stopped);
            lastShownStatus = key;
        }
        String btnText = running ? getString(R.string.stop)
                : getString(R.string.start);
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
            String real = Updater.parseBinaryVersion(ServerService.binaryVersion);
            if (real != null && real.equals(pendingVersion)) {
                psp.edit().putString(ServerService.KEY_UPDATE_VERSION, pendingVersion).apply();
                pendingVersion = null; // update sudah terpasang
            } else {
                String up = psp.getString(ServerService.KEY_UPDATE_VERSION, "");
                String cur = real != null ? real : Updater.normVersion(up != null && !up.isEmpty()
                        ? up : bundledRaw);
                if (cur != null && cur.equals(pendingVersion)) {
                    pendingVersion = null; // update sudah terpasang
                } else {
                    updAvail = !running;
                }
            }
        }
        if (updAvail) {
            updateHint.setText(getString(R.string.update_available, pendingVersion));
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
        // I/O berat (stat DB, jalan folder backup/web-vault) di worker thread;
        // UI thread hanya membaca nilai cache agar tidak jank/ANR.
        long nowHeavy = System.currentTimeMillis();
        boolean heavyDue = nowHeavy - lastDbCheck >= DB_CHECK_MS
                || nowHeavy - lastStorageCheck >= STORAGE_CHECK_MS
                || nowHeavy - lastWvCheck >= WV_CHECK_MS
                || nowHeavy - lastSysCheck >= SYS_CHECK_MS
                || nowHeavy - lastCertCheck >= CERT_CHECK_MS;
        if (heavyDue && heavyRunning.compareAndSet(false, true)) {
            new Thread(() -> {
                try {
                    dbInfoLine();
                    storageInfoLine();
                    webVaultInfoLine();
                    sysInfoLine();
                    certInfoLine();
                } finally {
                    heavyRunning.set(false);
                    ui.post(() -> {
                        if (refreshActive) {
                            refreshFromService();
                        }
                    });
                }
            }, "vw-ui-heavy").start();
        }
        String dbInfo = dbInfoLine();
        String full = dbInfo.isEmpty() ? version : version + "\n" + dbInfo;
        String wv = webVaultInfoLine();
        if (!wv.isEmpty()) {
            full += "\n" + wv;
        }
        String sys = sysInfoLine();
        if (!sys.isEmpty()) {
            full += "\n" + sys;
        }
        String storage = storageInfoLine();
        if (!storage.isEmpty()) {
            full += "\n" + storage;
        }
        String cert = certInfoLine();
        if (!cert.isEmpty()) {
            full += "\n" + cert;
        }
        if (!full.equals(lastShownVersion)) {
            versionView.setText(full);
            lastShownVersion = full;
        }

        String net = ServerService.localUrl(this);
        String netWarn = ServerService.netWarning(this);
        if (!netWarn.isEmpty()) {
            net += "\n" + netWarn;
        }
        if (!net.equals(lastShownNet)) {
            netInfoView.setText(net);
            lastShownNet = net;
        }

        // Baris "Izinkan akses penuh" hanya muncul bila battery optimization aktif.
        // Hasil IPC di-cache 30 dtk agar refresh tiap detik tidak membebani binder.
        long nowBattery = System.currentTimeMillis();
        if (nowBattery - lastBatteryCheck >= BATTERY_CHECK_MS) {
            lastBatteryCheck = nowBattery;
            boolean need = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
            if (need) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                need = pm == null || !pm.isIgnoringBatteryOptimizations(getPackageName());
            }
            needBatteryCached = need;
        }
        batteryRow.setVisibility(needBatteryCached ? View.VISIBLE : View.GONE);

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

    // Html.fromHtml lama untuk API 21-23; jalur modern dipakai bila API >= 24.
    @SuppressWarnings("deprecation")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tv.setText(Html.fromHtml(html.toString(), Html.FROM_HTML_MODE_LEGACY));
        } else {
            tv.setText(Html.fromHtml(html.toString()));
        }
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setLinkTextColor(0xFF1E88E5);
        new AlertDialog.Builder(this)
                .setTitle("Tentang")
                .setView(tv)
                .setPositiveButton("Tutup", null)
                .show();
    }

    /** Buka installer sertifikat Android langsung dalam mode CA (tanpa pilih manual). */
    private void installCertificate() {
        try {
            // CA aktif ada di internal (getFilesDir/tls) sejak migrasi TLS internal;
            // folder data lama (/sdcard) hanya fallback agar tombol tetap jalan.
            File cert = new File(getFilesDir(), "tls/ca.pem");
            if (!cert.exists()) {
                String dataDir = dataDirInput.getText().toString().trim();
                if (TextUtils.isEmpty(dataDir)) {
                    dataDir = DEFAULT_DATA_DIR;
                }
                cert = new File(dataDir, "tls/ca.pem");
            }
            if (!cert.exists()) {
                toast("CA belum ada. Aktifkan HTTPS lalu tekan Start dulu.");
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

    /** Kirim CA aktif (ca.pem saja, tanpa kunci privat) ke HP lain via aplikasi
     *  berbagi (Bluetooth/WhatsApp/Telegram). Di HP tujuan: simpan lalu install
     *  sebagai “CA certificate”. */
    private void bagikanCa() {
        try {
            File cert = new File(getFilesDir(), "tls/ca.pem");
            if (!cert.exists()) {
                String dataDir = dataDirInput.getText().toString().trim();
                if (TextUtils.isEmpty(dataDir)) {
                    dataDir = DEFAULT_DATA_DIR;
                }
                cert = new File(dataDir, "tls/ca.pem");
            }
            if (!cert.exists()) {
                toast("CA belum ada. Aktifkan HTTPS lalu tekan Start dulu.");
                return;
            }
            Uri uri = Uri.parse("content://" + FileShareProvider.AUTHORITY
                    + cert.getAbsolutePath());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/x-x509-ca-cert");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(Intent.createChooser(send, "Bagikan CA ke HP lain"));
            } catch (Exception e2) {
                toast("Gagal berbagi: " + e2.getMessage());
            }
        } catch (Exception e) {
            toast("Gagal berbagi CA: " + e.getMessage());
        }
    }

    // ─── Auto-update check (versi binary yang benar-benar dipakai) ──────

    private void autoUpdateCheck() {
        AutoUpdate.cek(this, new AutoUpdate.Aksi() {
            @Override public void toast(String pesan) {
                SettingsActivity.this.toast(pesan);
            }
            @Override public void catat(String baris) {
                appendUiLog(baris);
            }
            @Override public void kabariTersedia(String versi) {
                SettingsActivity.this.toast("Update tersedia: v" + versi);
            }
            @Override public void tawarkanWebVault() {
                autoOfferWebVaultUpdate();
            }
            @Override public boolean webVaultSiap(String dataDir) {
                return webVaultReady(dataDir);
            }
            @Override public void restartServer() {
                ServerService.restart(SettingsActivity.this);
            }
        }, new AutoUpdate.AturPending() {
            @Override public void atur(String versi) {
                pendingVersion = versi;
            }
        }, true);
    }

    /** Tawarkan update web vault sekali per versi bila versinya beda dari server. */
    private void autoOfferWebVaultUpdate() {
        try {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String dataDir = sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
            if (!webVaultReady(dataDir)) {
                return;
            }
            String marker = Updater.webVaultFromVersion(this);
            final String updated = marker != null ? marker
                    : readWvVersion(new File(dataDir, "web-vault/vw-version.json"));
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
                if (restartAfter && ServerService.running && TgBot.webVaultBerubah(msg)) {
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
            // Tulis WAL ke DB utama dulu agar backup konsisten seperti backup Telegram.
            TgBackup.checkpointWal(dbFile);
            long free = TgBackup.freeBytes(dataDir);
            if (free >= 0 && free < 50L * 1024 * 1024) {
                toast("Peringatan: sisa penyimpanan tinggal " + TgBackup.humanBytes(free));
                appendUiLog("[app] Peringatan storage tinggal " + TgBackup.humanBytes(free));
            }

            File backupDir = new File(dataDir, "backups");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }

            String timestamp = TgBackup.backupTimestamp();
            File backup = new File(backupDir, "db-backup-" + timestamp + ".zip");

            // Zip DB + WAL/SHM agar konsisten walau server sedang berjalan.
            String[] names = {"db.sqlite3", "db.sqlite3-wal", "db.sqlite3-shm"};
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(backup))) {
                byte[] buf = new byte[64 * 1024];
                for (String name : names) {
                    File f = new File(dataDir, name);
                    if (f.exists() && f.length() > 0) {
                        zos.putNextEntry(new ZipEntry(name));
                        try (InputStream in = new java.io.FileInputStream(f)) {
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                zos.write(buf, 0, n);
                            }
                        }
                        zos.closeEntry();
                    }
                }
            }
            TgBackup.cleanupOldBackups(backupDir);
            toast("Backup tersimpan:\n" + backup.getAbsolutePath());
            appendUiLog("[app] Backup DB: " + backup.getName() + " (" + backup.length() + " bytes)");
        } catch (Exception e) {
            toast("Gagal backup: " + e.getMessage());
            appendUiLog("[app] Gagal backup: " + e);
        }
    }

    // File picker klasik tanpa androidx agar APK tetap kecil + kompatibel API 21.
    @SuppressWarnings("deprecation")
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
    // Callback klasik pasangan startActivityForResult (tanpa androidx, API 21+).
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RESTORE && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            String dir = dataDirInput.getText().toString().trim();
            if (TextUtils.isEmpty(dir)) {
                dir = DEFAULT_DATA_DIR;
            }
            final String dataDir = dir;
            confirm("Restore Database",
                    "Database saat ini akan diganti dengan file yang dipilih. "
                            + "Backup otomatis dibuat dulu. Lanjutkan?",
                    () -> runBusy(() -> restoreDatabase(uri, dataDir)));
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

    private void restoreDatabase(Uri uri, String dataDir) {
        try {
            File dbFile = new File(dataDir, "db.sqlite3");
            File preBackup = null;
            if (dbFile.exists()) {
                File backupDir = new File(dataDir, "backups");
                if (!backupDir.exists()) {
                    backupDir.mkdirs();
                }
                String ts = TgBackup.backupTimestamp() + "-pre";
                preBackup = new File(backupDir, "db-backup-" + ts + ".sqlite3");
                TgBackup.copyFile(dbFile, preBackup);
                TgBackup.cleanupOldBackups(backupDir);
            }

            boolean restored = false;
            byte[] buf = new byte[64 * 1024];
            try (InputStream raw = getContentResolver().openInputStream(uri)) {
                if (raw == null) {
                    toast("Gagal restore: file tidak bisa dibuka.");
                    appendUiLog("[app] Restore gagal: stream null");
                    return;
                }
                // Pushback agar byte magic (PK) dikembalikan utuh sebelum
                // stream dibaca sebagai zip; tanpa ini header zip rusak dan
                // semua restore .zip gagal walau berisi db.sqlite3.
                PushbackInputStream in = new PushbackInputStream(raw, 2);
                byte[] magic = new byte[2];
                int n = in.read(magic);
                boolean isZip = n == 2 && magic[0] == 'P' && magic[1] == 'K';
                if (isZip) {
                    in.unread(magic, 0, n);
                    // Backup lokal .zip berisi db.sqlite3 (+wal/shm);
                    // backup lengkap juga memuat tls/* + app-config.json.
                    File dataFolder = new File(dataDir);
                    String canonBase = dataFolder.getCanonicalPath();
                    String awalanAman = canonBase + File.separator;
                    JSONObject zipCfg = null;
                    long totalUnzip = 0;
                    int jumlahEntri = 0;
                    ZipInputStream zis = new ZipInputStream(in);
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = TgBackup.normalisasiEntriZip(entry.getName());
                        if (name == null) {
                            zis.closeEntry();
                            continue;
                        }
                        if ("app-config.json".equals(name)) {
                            zipCfg = new JSONObject(new String(TgBackup.bacaTerbatas(
                                    zis, TgBackup.BATAS_CONFIG_JSON), StandardCharsets.UTF_8));
                            zis.closeEntry();
                            continue;
                        }
                        boolean dbPart = name.startsWith("db.sqlite3");
                        boolean tlsPart = name.startsWith("tls/") || name.equals("tls");
                        if (!dbPart && !tlsPart) {
                            zis.closeEntry();
                            continue;
                        }
                        File out = new File(dataFolder, name);
                        // Cegah zip-slip: entri licik (mis. db.sqlite3/../../x)
                        // tidak boleh keluar dari folder data (cek pakai separator
                        // agar folder sibling berawalan sama tak lolos).
                        String kanon = out.getCanonicalPath();
                        if (!kanon.equals(canonBase) && !kanon.startsWith(awalanAman)) {
                            zis.closeEntry();
                            continue;
                        }
                        jumlahEntri++;
                        if (entry.isDirectory()) {
                            out.mkdirs();
                        } else {
                            File parent = out.getParentFile();
                            if (parent != null) {
                                parent.mkdirs();
                            }
                            try (FileOutputStream fos = new FileOutputStream(out)) {
                                int len;
                                while ((len = zis.read(buf)) > 0) {
                                    totalUnzip = Util.tambahUkuranUnzip(totalUnzip, len,
                                            Util.BATAS_UNZIP_RESTORE, jumlahEntri,
                                            Util.BATAS_JUMLAH_ENTRI);
                                    fos.write(buf, 0, len);
                                }
                            }
                        }
                        zis.closeEntry();
                        restored = true;
                    }
                    if (zipCfg != null) {
                        TgBackup.applyPrefsFromJson(SettingsActivity.this,
                                zipCfg.optJSONObject("prefs"));
                        sanitizePortPref();
                        ui.post(() -> {
                            reloadSettingsFromPrefs();
                            SharedPreferences sp2 = getSharedPreferences(
                                    ServerService.PREFS, MODE_PRIVATE);
                            TgBackup.schedule(SettingsActivity.this,
                                    sp2.getBoolean(TgBackup.KEY_TG_AUTO, false));
                            TgBot.schedule(SettingsActivity.this);
                            appendUiLog("[app] Pengaturan dari backup ikut diterapkan.");
                        });
                    }
                } else {
                    // File .sqlite3 mentah (backup lama) - wajib header SQLite.
                    if (n <= 0) {
                        toast("File kosong - restore dibatalkan.");
                        appendUiLog("[app] Restore gagal: file kosong");
                        return;
                    }
                    byte[] head = new byte[16];
                    head[0] = magic[0];
                    if (n > 1) {
                        head[1] = magic[1];
                    }
                    int off = n;
                    while (off < head.length) {
                        int r = in.read(head, off, head.length - off);
                        if (r < 0) {
                            break;
                        }
                        off += r;
                    }
                    byte[] sqliteMagic = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
                    boolean ok = off == head.length;
                    for (int i = 0; ok && i < sqliteMagic.length; i++) {
                        if (head[i] != sqliteMagic[i]) {
                            ok = false;
                        }
                    }
                    if (!ok) {
                        toast("File bukan database SQLite - restore dibatalkan.");
                        appendUiLog("[app] Restore gagal: header SQLite tidak cocok");
                        return;
                    }
                    try (FileOutputStream fos = new FileOutputStream(dbFile)) {
                        fos.write(head, 0, off);
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            fos.write(buf, 0, len);
                        }
                    }
                    restored = true;
                }
            }
            if (!restored) {
                toast("File backup tidak berisi db.sqlite3.");
                appendUiLog("[app] Restore gagal: file zip tanpa db.sqlite3");
                return;
            }
            if (!TgBackup.isSqliteFile(dbFile)) {
                if (preBackup != null && preBackup.exists()) {
                    TgBackup.copyFile(preBackup, dbFile);
                } else {
                    dbFile.delete();
                }
                toast("Backup rusak (bukan SQLite) - database lama dikembalikan.");
                appendUiLog("[app] Restore gagal: header SQLite tidak cocok, rollback.");
                return;
            }
            toast("Database direstore. Restart server untuk memakai.");
            appendUiLog("[app] DB direstore. Ukuran: " + dbFile.length() + " bytes");
        } catch (Exception e) {
            toast("Gagal restore: " + e.getMessage());
            appendUiLog("[app] Gagal restore: " + e);
        }
    }

    // ─── Restore dari Telegram ──────────────────────────────────────────

    private void restoreFromTelegram() {
        String inputDir = dataDirInput.getText().toString().trim();
        if (!TextUtils.isEmpty(inputDir)) {
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .putString(ServerService.KEY_DATA_DIR, inputDir).apply();
        }
        runBusy(() -> {
            try {
                File tmp = new File(getCacheDir(), "vwtg-restore.zip");
                String name = TgBackup.downloadLastBackup(SettingsActivity.this, tmp);

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
                    tmp.delete();
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

    // Restore Telegram didelegasikan ke TgBackup agar satu implementasi:
    // stop server, pre-backup, validasi SQLite + rollback, terapkan pengaturan,
    // dan jaga identitas bot. Folder yang diketik (belum di-Start) disimpan
    // dulu ke prefs di UI thread agar dipakai sebagai folder tujuan.
    private void restoreFromZip(File zip) {
        try {
            appendUiLog("[app] Menghentikan server sebelum restore...");
            String msg = TgBackup.restoreFromZip(this, zip);
            ui.post(() -> {
                reloadSettingsFromPrefs();
                toast("Restore selesai. Tekan Start untuk menjalankan.");
            });
            appendUiLog("[app] " + msg);
        } catch (Exception e) {
            toast("Gagal restore: " + e.getMessage());
            appendUiLog("[app] Gagal restore: " + e);
        }
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
            String ts = TgBackup.backupTimestamp();
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            byte[] bytes = TgBackup.configJson(sp).getBytes(StandardCharsets.UTF_8);
            // Kredensial tak ikut export (tetap di perangkat); enkripsi bila password backup diisi.
            String pass = sp.getString(TgBackup.KEY_TG_PASS, "");
            final File out;
            final String mime;
            if (pass != null && !pass.trim().isEmpty()) {
                File plain = new File(backupDir, "app-config-" + ts + ".json");
                File enc = new File(backupDir, "app-config-" + ts + ".json.enc");
                try {
                    try (FileOutputStream fos = new FileOutputStream(plain)) {
                        fos.write(bytes);
                    }
                    TgBackup.encryptFile(plain, enc, pass.trim());
                } finally {
                    // Jangan sisakan config plaintext di storage publik bila enkripsi gagal.
                    try {
                        plain.delete();
                    } catch (Exception ignored) {
                    }
                }
                out = enc;
                mime = "application/octet-stream";
            } else {
                out = new File(backupDir, "app-config-" + ts + ".json");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(bytes);
                }
                mime = "application/json";
            }
            final String path = out.getAbsolutePath();
            ui.post(() -> {
                Uri uri = Uri.parse("content://" + FileShareProvider.AUTHORITY + path);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
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

    // File picker klasik tanpa androidx agar APK tetap kecil + kompatibel API 21.
    @SuppressWarnings("deprecation")
    private void pickImportFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            // Terima .json plaintext maupun .json.enc (export terenkripsi).
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/json", "application/octet-stream"});
            startActivityForResult(intent, REQ_IMPORT);
        } catch (Exception e) {
            toast("Gagal membuka file picker: " + e.getMessage());
        }
    }

    private void importConfig(Uri uri) {
        try {
            File tmp = new File(getCacheDir(), "vwcfg-import.bin");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 512 * 1024) {
                        throw new java.io.IOException(
                                "File terlalu besar (>512 KB) - bukan config valid.");
                    }
                    fos.write(buf, 0, n);
                }
            }
            File src = tmp;
            if (TgBackup.isEncrypted(tmp)) {
                SharedPreferences sp0 = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
                String pass0 = sp0.getString(TgBackup.KEY_TG_PASS, "");
                if (pass0 == null || pass0.trim().isEmpty()) {
                    toast("Config terenkripsi - isi password backup dulu.");
                    appendUiLog("[app] Import ditolak: config terenkripsi, password kosong");
                    tmp.delete();
                    return;
                }
                File plain = new File(getCacheDir(), "vwcfg-import-dec.json");
                TgBackup.decryptFile(tmp, plain, pass0.trim());
                tmp.delete();
                src = plain;
            }
            String json;
            try (InputStream in = new java.io.FileInputStream(src)) {
                json = new String(readCapped(in, 512 * 1024), StandardCharsets.UTF_8);
            }
            src.delete();
            JSONObject root = new JSONObject(json);
            if (!"tasirin-vaultwarden-host".equals(root.optString("app", ""))) {
                toast("File config tidak valid (bukan export app ini).");
                appendUiLog("[app] Import ditolak: marker app tidak cocok");
                return;
            }
            JSONObject prefs = root.optJSONObject("prefs");
            if (prefs == null) {
                toast("File config tidak valid.");
                return;
            }
            TgBackup.applyPrefsFromJson(this, prefs);
            sanitizePortPref();
            ui.post(() -> {
                reloadSettingsFromPrefs();
                SharedPreferences sp2 = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
                TgBackup.schedule(SettingsActivity.this,
                        sp2.getBoolean(TgBackup.KEY_TG_AUTO, false));
                TgBot.schedule(SettingsActivity.this);
                toast("Pengaturan diimpor. Tekan Start agar berlaku.");
                appendUiLog("[app] Config import selesai.");
            });
        } catch (Exception e) {
            new File(getCacheDir(), "vwcfg-import.bin").delete();
            new File(getCacheDir(), "vwcfg-import-dec.json").delete();
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
        autoUpdateCb.setChecked(sp.getBoolean(ServerService.KEY_AUTO_UPDATE, false));
        autoUpdateWvCb.setChecked(sp.getBoolean(ServerService.KEY_AUTO_UPDATE_WV, false));
        autoRestartCb.setChecked(sp.getBoolean(ServerService.KEY_AUTO_RESTART_UPDATE, false));
        backupPassInput.setText(sp.getString(TgBackup.KEY_TG_PASS, ""));
        binShaInput.setText(sp.getString(ServerService.KEY_BIN_SHA, ""));
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

    /** Baca maksimal max byte; lempar bila lebih (tolak file raksasa agar tidak OOM). */
    private static byte[] readCapped(InputStream in, int max) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                throw new java.io.IOException("File terlalu besar (>512 KB) - bukan config valid.");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** Kembalikan port ke default bila hasil import bukan angka 1-65535. */
    private void sanitizePortPref() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String p = sp.getString(ServerService.KEY_PORT, DEFAULT_PORT);
        try {
            int pn = Integer.parseInt(p.trim());
            if (pn >= 1 && pn <= 65535) {
                return;
            }
        } catch (Exception ignored) {
        }
        sp.edit().putString(ServerService.KEY_PORT, DEFAULT_PORT).apply();
        appendUiLog("[app] Port hasil import tidak valid - kembali ke " + DEFAULT_PORT + ".");
    }

    // ─── PIN lock ───────────────────────────────────────────────────────

    private void maybeShowPinLock() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        if (!sp.getBoolean(KEY_PIN_ON, false)) {
            return;
        }
        // Sudah dibuka di layar awal dalam 60 detik: jangan minta lagi.
        if (MainActivity.pinBaruSajaDibuka()) {
            unlocked = true;
            pauseStamp = System.currentTimeMillis();
            return;
        }
        if (unlocked && System.currentTimeMillis() - pauseStamp < PIN_GRACE_MS) {
            return;
        }
        unlocked = false;
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
                    final android.widget.Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    long sisa = PinGate.sisaKunciMs(SettingsActivity.this,
                            System.currentTimeMillis());
                    if (sisa > 0) {
                        input.setError("Terkunci, coba lagi "
                                + ((sisa + 59000) / 60000) + " menit.");
                        return;
                    }
                    ok.setEnabled(false);
                    input.setError("Memeriksa PIN...");
                    final String entered = input.getText().toString();
                    new Thread(() -> {
                        boolean cocok = PinCrypto.verify(pinHash, entered);
                        PinGate.catatHasil(SettingsActivity.this, cocok,
                                System.currentTimeMillis());
                        if (cocok && !PinCrypto.isNewFormat(pinHash)) {
                            // Migrasi hash lama (SHA-256 polos) ke PBKDF2 (sudah di worker).
                            sp.edit().putString(KEY_PIN, PinCrypto.hash(entered)).apply();
                        }
                        final boolean hasil = cocok;
                        ui.post(() -> {
                            ok.setEnabled(true);
                            if (hasil) {
                                unlocked = true;
                                MainActivity.catatPinDibuka();
                                dialog.dismiss();
                            } else {
                                input.setError("PIN salah");
                            }
                        });
                    }, "vw-pin-check").start();
                }));
        dialog.show();
    }

    /** Isi folder backups dengan cache 5 dtk (dipakai dua baris info). */
    private File[] daftarBackup(File backupDir) {
        long now = System.currentTimeMillis();
        String kunci = backupDir.getAbsolutePath();
        if (cacheDaftarBackup != null && kunci.equals(cacheDaftarBackupDir)
                && now - cacheDaftarBackupAt < DAFTAR_BACKUP_TTL_MS) {
            return cacheDaftarBackup;
        }
        File[] files = backupDir.listFiles();
        cacheDaftarBackup = files;
        cacheDaftarBackupDir = kunci;
        cacheDaftarBackupAt = now;
        return files;
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
            File[] files = daftarBackup(new File(dataDir, "backups"));
            int n = files == null ? 0 : files.length;
            dbLine = "DB: " + TgBackup.humanBytes(db.length()) + " | Backup lokal: " + n;
        } catch (Exception e) {
            dbLine = "";
        }
        return dbLine;
    }

    /** Rincian storage: DB, backup lokal, web-vault, binary (satu baris ringkas). */
    private String storageInfoLine() {
        long now = System.currentTimeMillis();
        if (now - lastStorageCheck < STORAGE_CHECK_MS) {
            return storageLine;
        }
        lastStorageCheck = now;
        try {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String dataDir = sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
            File data = new File(dataDir);
            File db = new File(data, "db.sqlite3");
            File backups = new File(data, "backups");
            File webVault = new File(data, "web-vault");
            File bin = new File(getFilesDir(), "bin/vaultwarden-" + ServerService.ABI);
            StringBuilder sb = new StringBuilder("Storage: ");
            boolean first = true;
            if (db.exists()) {
                sb.append("DB ").append(TgBackup.humanBytes(db.length()));
                first = false;
            }
            File[] files = daftarBackup(backups);
            if (files != null && files.length > 0) {
                if (!first) {
                    sb.append(" \u00B7 ");
                }
                sb.append("Backup ").append(TgBackup.humanBytes(TgBackup.folderBytesCached(backups)))
                        .append(" (").append(files.length).append(")");
                first = false;
            }
            long wvBytes = TgBackup.folderBytesCached(webVault);
            if (wvBytes > 0) {
                if (!first) {
                    sb.append(" \u00B7 ");
                }
                sb.append("Web ").append(TgBackup.humanBytes(wvBytes));
                first = false;
            }
            if (bin.exists()) {
                if (!first) {
                    sb.append(" \u00B7 ");
                }
                sb.append("Binary ").append(TgBackup.humanBytes(bin.length()));
            }
            storageLine = sb.toString();
        } catch (Exception e) {
            storageLine = "";
        }
        return storageLine;
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
            String bundled = bundledRaw != null ? bundledRaw : Updater.readBundledVersionRaw(this);
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

    /** RAM proses server, uptime, dan sisa storage (satu baris ringkas). */
    private String sysInfoLine() {
        long now = System.currentTimeMillis();
        if (now - lastSysCheck < SYS_CHECK_MS) {
            return sysLine;
        }
        lastSysCheck = now;
        StringBuilder sb = new StringBuilder();
        if (ServerService.running) {
            long rss = ServerService.processRssKb();
            if (rss > 0) {
                sb.append("RAM ").append(TgBackup.humanBytes(rss * 1024));
            }
            long up = ServerService.uptimeMs();
            if (up > 0) {
                if (sb.length() > 0) {
                    sb.append(" \u00B7 ");
                }
                sb.append("Uptime ").append(TgBot.durationText(up));
            }
        }
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String dataDir = sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
        long free = TgBackup.freeBytes(dataDir);
        if (free > 0) {
            if (sb.length() > 0) {
                sb.append(" \u00B7 ");
            }
            sb.append("Sisa ").append(TgBackup.humanBytes(free));
        }
        String restarts = ServerService.restartSummary();
        if (!restarts.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" \u00B7 ");
            }
            sb.append(restarts);
        }
        sysLine = sb.toString();
        return sysLine;
    }

    /** Sisa masa berlaku sertifikat TLS; kosong bila belum ada / tidak terbaca. */
    private String certInfoLine() {
        long now = System.currentTimeMillis();
        if (now - lastCertCheck < CERT_CHECK_MS) {
            return certLine;
        }
        lastCertCheck = now;
        certLine = certInfoLineInner();
        return certLine;
    }

    private String certInfoLineInner() {
        try {
            // Cert aktif di internal; folder data lama hanya fallback.
            File cert = new File(getFilesDir(), "tls/cert.pem");
            if (!cert.exists()) {
                SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
                String dataDir = sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
                cert = new File(dataDir, "tls/cert.pem");
            }
            if (!cert.exists()) {
                return "";
            }
            long days = TlsCert.daysLeft(cert);
            if (days < 0) {
                return "";
            }
            if (days < 30) {
                return "\u26A0 Sertifikat TLS tinggal " + days + " hari";
            }
            return "Sertifikat TLS: " + days + " hari";
        } catch (Exception e) {
            return "";
        }
    }

    /** Versi dari file vw-version.json (web-vault yang sudah di-update) atau null. */
    /** Versi dari file vw-version.json (satu implementasi di Updater). */
    private String readWvVersion(File f) {
        return Updater.readWvVersion(f);
    }

    /** Versi binary yang benar-benar dipakai server saat ini (x.y.z). */
    /** Versi binary yang benar-benar dipakai server saat ini (satu di Updater). */
    private String currentServerVersion() {
        return Updater.currentServerVersion(this);
    }

    // ─── Update binary (unduh per tag versi resmi) ──────────────────────

    private void checkForUpdate() {
        try {
            appendUiLog("[app] Mengecek update dari sumber resmi...");
            String msg = Updater.tryUpdate(this);
            if (KernelCompat.isLegacyDevice(KernelCompat.kernelSekarang())) {
                try {
                    Updater.ensureShimFile(this);
                    msg += " Shim getrandom siap.";
                } catch (Exception se) {
                    msg += " Shim gagal: " + se.getMessage();
                }
            }
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
        btn.setText(hidden ? getString(R.string.hide) : getString(R.string.show));
    }

    /** Backup Telegram otomatis saat Start (satu implementasi di TgBackup). */
    private void maybeAutoBackup() {
        TgBackup.maybeAutoBackup(this, new TgBackup.BackupStartUi() {
            @Override public void toast(String s) {
                SettingsActivity.this.toast(s);
            }
            @Override public void catat(String s) {
                appendUiLog(s);
            }
            @Override public void jalankanUi(Runnable r) {
                ui.post(r);
            }
        });
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
        advancedToggleBtn.setText(getString(open
                ? R.string.advanced_open : R.string.advanced_closed));
        getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ADVANCED_OPEN, open).apply();
    }

    private void setBusy(final boolean busy) {
        uiBusy = busy;
        ui.post(() -> {
            updateBtn.setEnabled(!busy);
            revertBtn.setEnabled(!busy);
            updateWvBtn.setEnabled(!busy);
            backupDbBtn.setEnabled(!busy);
            restoreDbBtn.setEnabled(!busy);
            backupTgBtn.setEnabled(!busy);
            restoreTgBtn.setEnabled(!busy);
            if (busy) {
                statusView.setText(getString(R.string.busy_work));
                statusView.setBackgroundResource(R.drawable.bg_status_busy);
                lastShownStatus = "";
            } else {
                refreshFromService();
            }
        });
    }

    private void runBusy(final Runnable task) {
        if (!tugasBerjalan.compareAndSet(false, true)) {
            toast("Masih bekerja, tunggu selesai.");
            return;
        }
        setBusy(true);
        new Thread(() -> {
            try {
                task.run();
            } finally {
                tugasBerjalan.set(false);
                setBusy(false);
            }
        }, "vw-task").start();
    }

    private void appendUiLog(String line) {
        ServerService.catatLog(line);
        // Ledakan log (mis. output binary) tidak boleh membanjiri UI thread.
        long now = System.currentTimeMillis();
        if (now - lastUiLogRefresh > 500) {
            lastUiLogRefresh = now;
            ui.post(this::refreshFromService);
        }
    }

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private final Runnable scheduleBotRunnable = () -> TgBot.schedule(SettingsActivity.this);

    /** Pasang ulang jadwal bot maksimal 1x/detik saat token/chat diketik. */
    private void scheduleBotDebounced() {
        ui.removeCallbacks(scheduleBotRunnable);
        ui.postDelayed(scheduleBotRunnable, 1000);
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
        private Runnable tugasTunda;

        SimpleTextWatcher(String key) {
            this.key = key;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
            // Debounce: mengetik token panjang tak menulis flash per karakter.
            final String nilai = s.toString();
            if (tugasTunda != null) {
                ui.removeCallbacks(tugasTunda);
            }
            tugasTunda = () -> getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                    .edit().putString(key, nilai).apply();
            ui.postDelayed(tugasTunda, 400);
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
        }
    }

    @Override
    protected void onDestroy() {
        pinExec.shutdownNow();
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }
}
