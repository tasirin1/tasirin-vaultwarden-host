package com.tasirin.vaultwardenhost;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Html;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Layar awal sederhana: status server + Start/Stop + log realtime + simpan .txt.
 *  Semua pengaturan pindah ke SettingsActivity lewat tombol titik tiga. */
public class MainActivity extends Activity {

    private static final int REQ_WRITE = 1001;
    private static final String DEFAULT_DATA_DIR = ServerService.DEFAULT_DATA_DIR;
    private static final String DEFAULT_PORT = ServerService.DEFAULT_PORT;
    private static final String KEY_PIN = "pin_hash";
    private static final String KEY_PIN_ON = "pin_on";
    private static final String KEY_HOME_LOG_EXPANDED = "home_log_expanded";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private TextView versionView;
    private TextView netInfoView;
    private TextView uptimeView;
    private TextView restartHint;
    private Button updateBtn;
    private Button logToggleBtn;
    private TextView homeLogView;
    private ScrollView homeLogScroll;
    private TextView homeLogCount;
    private Button startStopBtn;
    private Button overflowBtn;
    private Button homeSaveBtn;

    private volatile String pendingVersion = null;
    private String appVersion = "";
    private String bundledVersion = "?";
    private String bundledRaw = null;
    private String lastShownStatus = "";
    private String lastShownNet = "";
    private String lastShownVersion = "";
    private String lastShownUptime = "";
    private boolean lastUpdBtnVisible = true; // paksa selaras rantai fokus saat refresh pertama
    private String lastUpdText = ""; // cegah setText+layout tiap tick saat teks sama
    private boolean homeLogExpanded = true;
    private int lastLogLen = 0;
    private int lineCount = 0;
    private boolean hintShown = false;
    private boolean refreshActive = true;
    private volatile boolean uiBusy = false;
    private long lastUiLogRefresh = 0;

    private static volatile boolean unlocked = false;
    /** Kapan MainActivity terakhir pause; kunci PIN baru muncul bila >60 detik. */
    private static volatile long pauseStamp = 0;
    private static final long PIN_GRACE_MS = 60_000;

    /** Berbagi status buka PIN dengan Settings agar tidak diminta dua kali. */
    static boolean pinBaruSajaDibuka() {
        return unlocked && System.currentTimeMillis() - pauseStamp < PIN_GRACE_MS;
    }

    /** Catat buka PIN dari layar lain sebagai milik bersama. */
    static void catatPinDibuka() {
        unlocked = true;
        pauseStamp = System.currentTimeMillis();
    }

    @Override
    // getPackageInfo lama sengaja agar satu jalur kode untuk API 21-32.
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        // Splash ditampilkan lewat theme manifest, ganti ke tema utama di sini.
        setTheme(R.style.Theme_TasirinVaultwardenHost);
        super.onCreate(savedInstanceState);
        TgBackup.migrateAutoPref(this);
        // Privasi: nonaktifkan screenshot + preview recents dikosongkan.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        versionView = findViewById(R.id.version);
        netInfoView = findViewById(R.id.netInfo);
        uptimeView = findViewById(R.id.uptimeInfo);
        restartHint = findViewById(R.id.restartHint);
        updateBtn = findViewById(R.id.updateBtn);
        logToggleBtn = findViewById(R.id.logToggle);
        homeLogView = findViewById(R.id.homeLog);
        homeLogScroll = findViewById(R.id.homeLogScroll);
        homeLogCount = findViewById(R.id.homeLogCount);
        startStopBtn = findViewById(R.id.startStop);
        overflowBtn = findViewById(R.id.overflowBtn);
        homeSaveBtn = findViewById(R.id.homeSaveLog);

        startStopBtn.setOnClickListener(v -> {
            if (ServerService.running) {
                ServerService.stop(this);
            } else {
                saveAndStart();
            }
        });
        overflowBtn.setOnClickListener(v -> showOverflowMenu());
        homeSaveBtn.setOnClickListener(v -> exportHomeLogTxt());
        // Ketuk URL untuk menyalin (pengganti tombol Salin URL yang pindah ke Settings)
        netInfoView.setOnClickListener(v -> copyShownUrl());
        // Tombol update melompat ke Settings tempat Cek Update berada
        updateBtn.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        logToggleBtn.setOnClickListener(v -> setHomeLogExpanded(!homeLogExpanded));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            }
        }

        try {
            appVersion = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        bundledVersion = readBundledVersion();
        bundledRaw = Updater.readBundledVersionRaw(this);
        ui.post(this::refreshFromService);

        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        homeLogExpanded = sp.getBoolean(KEY_HOME_LOG_EXPANDED, true);
        applyHomeLogExpanded();
        // Cek update otomatis saat dibuka
        new Thread(this::autoUpdateCheck, "vw-auto-check").start();
        // Pastikan jadwal backup harian tetap terpasang
        TgBackup.schedule(this, sp.getBoolean(TgBackup.KEY_TG_AUTO, false));
        // Remote kontrol via Telegram bot
        TgBot.schedule(this);
        // Susulan backup boot yang ditolak sistem (Android 12+ batasi start background).
        if (sp.getBoolean("tg_backup_tertunda", false)) {
            try {
                sp.edit().remove("tg_backup_tertunda").apply();
            } catch (Exception ignored) {
            }
            final android.content.Context app = getApplicationContext();
            new Thread(() -> {
                try {
                    String msg = TgBackup.backupNow(app);
                    ServerService.catatLog("[tg] " + msg + " (susulan boot).");
                } catch (Exception e) {
                    ServerService.catatLog("[tg] Backup susulan boot gagal: " + e.getMessage());
                }
            }, "vw-boot-susulan").start();
        }
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
        // Jangan kunci langsung (pindah ke Settings/Log bukan keluar app);
        // maybeShowPinLock mengunci bila jeda > PIN_GRACE_MS.
        pauseStamp = System.currentTimeMillis();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }

    /** Titik tiga: pilihan Settings dan Tentang (ramah D-pad). */
    private void showOverflowMenu() {
        final String[] items = {getString(R.string.open_settings), getString(R.string.about)};
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.menu))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(this, SettingsActivity.class));
                    } else {
                        showAboutDialog();
                    }
                })
                .show();
    }

    private void saveAndStart() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String dataDir = sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
        if (TextUtils.isEmpty(dataDir)) {
            dataDir = DEFAULT_DATA_DIR;
        }
        String port = ServerService.effectivePort(sp);
        final String finalDataDir = dataDir;

        int portNum = -1;
        try {
            portNum = Integer.parseInt(port.trim());
        } catch (Exception ignored) {
        }
        if (portNum < 1 || portNum > 65535) {
            toast("Port harus angka 1-65535. Ubah di Settings.");
            appendUiLog("[app] Port tidak valid: '" + port + "' - Start dibatalkan.");
            return;
        }
        if (ServerService.isPortBusy(portNum)) {
            new AlertDialog.Builder(this)
                    .setTitle("Port " + portNum + " sedang dipakai")
                    .setMessage("Port utama (" + portNum + ") sudah dipakai proses lain.\n"
                            + "Stop aplikasi lain yang memakainya, ganti port di Settings, "
                            + "atau restart HP dulu.")
                    .setPositiveButton("Oke", null)
                    .show();
            return;
        }

        if (!webVaultReady(finalDataDir)) {
            // APK tidak membundel web-vault; unduh sekali saat Start pertama bila diizinkan.
            new AlertDialog.Builder(this)
                    .setTitle("Web vault belum terpasang")
                    .setMessage("APK tidak menyertakan web vault agar ukurannya kecil.\n"
                            + "Unduh sekali (~35 MB) supaya web UI bisa dibuka dari browser?\n\n"
                            + "Tanpa web vault, server dan aplikasi Bitwarden tetap jalan normal.")
                    .setPositiveButton("Unduh & Start", (d, w) -> {
                        setBusy(true);
                        new Thread(() -> {
                            try {
                                String msg = Updater.updateWebVault(this);
                                appendUiLog("[app] " + msg);
                            } catch (Exception e) {
                                toast("Gagal unduh web-vault: " + e.getMessage());
                                appendUiLog("[app] Gagal unduh web-vault: " + e);
                            } finally {
                                setBusy(false);
                            }
                            ServerService.start(this);
                        }, "vw-task").start();
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
        // Sedang sibuk (unduh web-vault)? Kunci chip status agar tidak tertimpa polling.
        if (uiBusy) {
            String dl = Updater.downloadStatus;
            statusView.setText(dl.isEmpty() ? getString(R.string.busy_work) : dl);
            statusView.setBackgroundResource(R.drawable.bg_status_busy);
            lastShownStatus = "";
            refreshHomeLog();
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

        // Peringatan bila setting diubah di Settings tapi server belum di-restart
        boolean changed = false;
        if (running) {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String d = sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
            if (d == null || d.trim().isEmpty()) {
                d = DEFAULT_DATA_DIR;
            }
            String p = ServerService.effectivePort(sp);
            boolean h = sp.getBoolean(ServerService.KEY_HTTPS, false);
            String a = sp.getString(ServerService.KEY_ADMIN_TOKEN, "");
            if (a == null) {
                a = "";
            }
            String rd = ServerService.runningDataDir == null ? "" : ServerService.runningDataDir;
            String rp = ServerService.runningPort == null ? "" : ServerService.runningPort;
            String ra = ServerService.runningAdminToken == null ? "" : ServerService.runningAdminToken;
            changed = !d.equals(rd)
                    || !p.equals(rp)
                    || h != ServerService.runningHttps
                    || !a.equals(ra);
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
            String teksUpd = getString(R.string.update_open_settings, pendingVersion);
            if (!teksUpd.equals(lastUpdText)) {
                lastUpdText = teksUpd;
                updateBtn.setText(teksUpd);
            }
            updateBtn.setVisibility(View.VISIBLE);
        } else {
            updateBtn.setVisibility(View.GONE);
        }
        // Rantai D-pad tak boleh menunjuk ke tombol yang gone: alihkan tetangga
        if (updAvail != lastUpdBtnVisible) {
            lastUpdBtnVisible = updAvail;
            netInfoView.setNextFocusDownId(updAvail ? R.id.updateBtn : R.id.logToggle);
            logToggleBtn.setNextFocusUpId(updAvail ? R.id.updateBtn : R.id.netInfo);
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

        String uptime = "";
        if (running) {
            long up = ServerService.uptimeMs();
            if (up > 0) {
                uptime = getString(R.string.uptime_format, TgBot.durationText(up));
            }
        }
        if (!uptime.equals(lastShownUptime)) {
            lastShownUptime = uptime;
            uptimeView.setText(uptime);
            uptimeView.setVisibility(uptime.isEmpty() ? View.GONE : View.VISIBLE);
        }

        String version = "App " + appVersion;
        if (!ServerService.binaryVersion.isEmpty()) {
            version += " \u00B7 Binary: " + ServerService.binaryVersion;
        } else {
            version += " \u00B7 " + bundledVersion;
        }
        if (!version.equals(lastShownVersion)) {
            versionView.setText(version);
            lastShownVersion = version;
        }

        refreshHomeLog();

        if (refreshActive) {
            ui.postDelayed(this::refreshFromService, 1000);
        }
    }

    /** Pratinjau log realtime di layar awal (ringan, tanpa pencarian). */
    private void refreshHomeLog() {
        // Hanya tempel selisih baris baru (delta): salin+setText seluruh buffer
        // (<=300 KB) tiap 500 ms bikin UI patah-patah saat log deras.
        int len = ServerService.logLength();
        if (len < lastLogLen) {
            lineCount = 0;
            lastLogLen = 0;
            homeLogView.setText("");
            hintShown = false;
        }
        String delta = "";
        synchronized (ServerService.logBuffer) {
            int n = ServerService.logBuffer.length();
            if (n > lastLogLen) {
                delta = ServerService.logBuffer.substring(lastLogLen, n);
            }
        }
        if (delta.isEmpty()) {
            if (!hintShown && homeLogView.length() == 0) {
                homeLogView.setText(getString(R.string.log_empty_hint));
                hintShown = true;
            }
            return;
        }
        if (hintShown) {
            homeLogView.setText("");
            hintShown = false;
        }
        for (int i = 0; i < delta.length(); i++) {
            if (delta.charAt(i) == '\n') {
                lineCount++;
            }
        }
        lastLogLen += delta.length();
        homeLogView.append(delta);
        // TextView 300 KB + fullScroll tiap 500 ms bikin STB patah: tampilkan ekor saja.
        if (homeLogView.length() > 40000) {
            CharSequence penuh = homeLogView.getText();
            int potong = penuh.length() - 30000;
            int nl = -1;
            for (int i = potong; i < penuh.length(); i++) {
                if (penuh.charAt(i) == '\n') {
                    nl = i + 1;
                    break;
                }
            }
            homeLogView.setText(penuh.subSequence(nl < 0 ? potong : nl, penuh.length()));
        }
        homeLogCount.setText(getString(R.string.log_lines, lineCount));
        homeLogScroll.post(() -> homeLogScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Salin URL yang tampil di kartu info (pengganti tombol Salin URL). */
    private void copyShownUrl() {
        String url = ServerService.localUrl(this);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("vaultwarden-url", url));
            toast(getString(R.string.url_copied, url));
        } else {
            toast(url);
        }
    }

    /** Ciutkan/bentangkan pratinjau log; pilihan disimpan di prefs. */
    private void setHomeLogExpanded(boolean expanded) {
        homeLogExpanded = expanded;
        getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_HOME_LOG_EXPANDED, expanded).apply();
        applyHomeLogExpanded();
    }

    private void applyHomeLogExpanded() {
        homeLogScroll.setVisibility(homeLogExpanded ? View.VISIBLE : View.GONE);
        homeLogCount.setVisibility(homeLogExpanded ? View.VISIBLE : View.GONE);
        logToggleBtn.setText(getString(homeLogExpanded
                ? R.string.collapse : R.string.expand));
    }

    // Html.fromHtml lama untuk API 21-23; jalur modern dipakai bila API >= 24.
    @SuppressWarnings("deprecation")
    private void showAboutDialog() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String dataDir = sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
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
                .append("1. Atur folder data &amp; port di <b>Settings</b> (titik tiga)<br/>")
                .append("2. Tekan <b>Start</b><br/>")
                .append("3. Buka URL di layar utama lewat browser<br/><br/>")
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

/** Simpan log ke .txt di Download (satu implementasi di LogExport). */
    private void exportHomeLogTxt() {
        String log;
        synchronized (ServerService.logBuffer) {
            log = ServerService.logBuffer.toString();
        }
        String nama = LogExport.simpanKeDownload(this, log);
        toast(nama != null ? "Log disimpan: Download/" + nama : "Gagal menyimpan log");
    }

    // ─── Auto-update check (versi binary yang benar-benar dipakai) ──────

    private void autoUpdateCheck() {
        AutoUpdate.cek(this, new AutoUpdate.Aksi() {
            @Override public void toast(String pesan) {
                MainActivity.this.toast(pesan);
            }
            @Override public void catat(String baris) {
                appendUiLog(baris);
            }
            @Override public void kabariTersedia(String versi) {
                MainActivity.this.toast("Update tersedia: v" + versi
                        + " - buka Settings untuk update.");
            }
            @Override public void tawarkanWebVault() {
            }
            @Override public boolean webVaultSiap(String dataDir) {
                return false;
            }
            @Override public void restartServer() {
            }
        }, new AutoUpdate.AturPending() {
            @Override public void atur(String versi) {
                pendingVersion = versi;
            }
        }, false);
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

    /** Versi dari file vw-version.json (satu implementasi di Updater). */
    private String readWvVersion(File f) {
        return Updater.readWvVersion(f);
    }

    /** Versi binary yang benar-benar dipakai server saat ini (satu di Updater). */
    private String currentServerVersion() {
        return Updater.currentServerVersion(this);
    }

    /** Backup Telegram otomatis saat Start (satu implementasi di TgBackup). */
    private void maybeAutoBackup() {
        TgBackup.maybeAutoBackup(this, new TgBackup.BackupStartUi() {
            @Override public void toast(String s) {
                MainActivity.this.toast(s);
            }
            @Override public void catat(String s) {
                appendUiLog(s);
            }
            @Override public void jalankanUi(Runnable r) {
                ui.post(r);
            }
        });
    }

    // ─── Kunci PIN ──────────────────────────────────────────────────────

    private void maybeShowPinLock() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        if (!sp.getBoolean(KEY_PIN_ON, false)) {
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
                    long sisa = PinGate.sisaKunciMs(MainActivity.this,
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
                        PinGate.catatHasil(MainActivity.this, cocok,
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
                                catatPinDibuka();
                                dialog.dismiss();
                            } else {
                                input.setError("PIN salah");
                            }
                        });
                    }, "vw-pin-check").start();
                }));
        dialog.show();
    }

    private void setBusy(final boolean busy) {
        uiBusy = busy;
        ui.post(() -> {
            startStopBtn.setEnabled(!busy);
            homeSaveBtn.setEnabled(!busy);
            if (busy) {
                statusView.setText(getString(R.string.busy_work));
                statusView.setBackgroundResource(R.drawable.bg_status_busy);
                lastShownStatus = "";
            } else {
                refreshFromService();
            }
        });
    }

    private void appendUiLog(String line) {
        if (line == null) {
            return;
        }
        ServerService.catatLog(line);
        // Ledakan log tidak boleh membanjiri UI thread.
        long now = System.currentTimeMillis();
        if (now - lastUiLogRefresh > 500) {
            lastUiLogRefresh = now;
            ui.post(this::refreshFromService);
        }
    }

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}
