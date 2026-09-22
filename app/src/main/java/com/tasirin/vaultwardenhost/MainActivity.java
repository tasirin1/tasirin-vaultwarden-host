package com.tasirin.vaultwardenhost;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
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
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Layar awal sederhana: status server + Start/Stop + log realtime + simpan .txt.
 *  Semua pengaturan pindah ke SettingsActivity lewat tombol titik tiga. */
public class MainActivity extends Activity {

    private static final int REQ_WRITE = 1001;
    private static final String DEFAULT_DATA_DIR = ServerService.DEFAULT_DATA_DIR;
    private static final String DEFAULT_PORT = ServerService.DEFAULT_PORT;
    private static final String KEY_PIN = "pin_hash";
    private static final String KEY_PIN_ON = "pin_on";
    private static final String KEY_TG_NOTIFIED = "tg_notified_version";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private TextView netInfoView;
    private TextView restartHint;
    private TextView updateHint;
    private TextView homeLogView;
    private ScrollView homeLogScroll;
    private TextView homeLogCount;
    private Button startStopBtn;
    private Button overflowBtn;
    private Button homeSaveBtn;
    private Button homeOpenBtn;

    private volatile String pendingVersion = null;
    private String appVersion = "";
    private String lastShownStatus = "";
    private String lastShownNet = "";
    private String lastLogKey = null;
    private int lastLogLen = 0;
    private int lineCount = 0;
    private boolean refreshActive = true;
    private boolean uiBusy = false;
    private long lastUiLogRefresh = 0;

    private static boolean unlocked = false;
    /** Kapan MainActivity terakhir pause; kunci PIN baru muncul bila >60 detik. */
    private static long pauseStamp = 0;
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
        // Privasi: nonaktifkan screenshot + preview recents dikosongkan.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        netInfoView = findViewById(R.id.netInfo);
        restartHint = findViewById(R.id.restartHint);
        updateHint = findViewById(R.id.updateHint);
        homeLogView = findViewById(R.id.homeLog);
        homeLogScroll = findViewById(R.id.homeLogScroll);
        homeLogCount = findViewById(R.id.homeLogCount);
        startStopBtn = findViewById(R.id.startStop);
        overflowBtn = findViewById(R.id.overflowBtn);
        homeSaveBtn = findViewById(R.id.homeSaveLog);
        homeOpenBtn = findViewById(R.id.homeOpenLog);

        startStopBtn.setOnClickListener(v -> {
            if (ServerService.running) {
                ServerService.stop(this);
            } else {
                saveAndStart();
            }
        });
        overflowBtn.setOnClickListener(v -> showOverflowMenu());
        homeSaveBtn.setOnClickListener(v -> exportHomeLogTxt());
        homeOpenBtn.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));

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
        ui.post(this::refreshFromService);

        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        // Cek update otomatis saat dibuka
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
                        ? up : Updater.readBundledVersionRaw(this));
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

        String net = ServerService.localUrl(this);
        if (!net.equals(lastShownNet)) {
            netInfoView.setText(net);
            lastShownNet = net;
        }

        refreshHomeLog();

        if (refreshActive) {
            ui.postDelayed(this::refreshFromService, 1000);
        }
    }

    /** Pratinjau log realtime di layar awal (ringan, tanpa pencarian). */
    private void refreshHomeLog() {
        int len = ServerService.logLength();
        if (len < lastLogLen) {
            lineCount = 0;
            lastLogLen = 0;
        }
        String key = len + "";
        if (key.equals(lastLogKey)) {
            return;
        }
        lastLogKey = key;
        String text;
        synchronized (ServerService.logBuffer) {
            text = ServerService.logBuffer.toString();
        }
        int n = Math.min(len, text.length());
        for (int i = lastLogLen; i < n; i++) {
            if (text.charAt(i) == '\n') {
                lineCount++;
            }
        }
        lastLogLen = len;
        homeLogCount.setText(getString(R.string.log_lines, lineCount));
        homeLogView.setText(text);
        homeLogScroll.post(() -> homeLogScroll.fullScroll(View.FOCUS_DOWN));
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

    /** Simpan log ke .txt di Download (format header ala Tasirin). */
    // API lawas sengaja: Downloads publik pra-29 + getPackageInfo satu jalur untuk API 21-32.
    @SuppressWarnings("deprecation")
    private void exportHomeLogTxt() {
        String log;
        synchronized (ServerService.logBuffer) {
            log = ServerService.logBuffer.toString();
        }
        StringBuilder header = new StringBuilder();
        header.append("=== Tasirin Vaultwarden Host - Log Server (realtime) ===\n");
        header.append("Waktu: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append('\n');
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            header.append("Versi app: ").append(info.versionName)
                    .append(" (build ").append(info.versionCode).append(")\n");
        } catch (Exception ignored) {
            header.append("Versi app: ?\n");
        }
        header.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        header.append("Perangkat: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append("\n\n");
        header.append(log.isEmpty() ? "(Belum ada aktivitas server)\n" : log);
        header.append('\n');

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String name = "tasirin-vaultwarden-host-log-" + stamp + ".txt";
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try {
                        OutputStream out = resolver.openOutputStream(uri);
                        if (out != null) {
                            out.write(header.toString().getBytes(StandardCharsets.UTF_8));
                            out.close();
                            ok = true;
                        } else {
                            resolver.delete(uri, null, null);
                        }
                    } catch (Exception e) {
                        resolver.delete(uri, null, null);
                    }
                    if (ok) {
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Downloads.IS_PENDING, 0);
                        resolver.update(uri, done, null, null);
                    }
                }
            } catch (Exception ignored) {
            }
        } else {
            try {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                    try (FileWriter w = new FileWriter(new File(dir, name))) {
                        w.write(header.toString());
                    }
                    ok = true;
                }
            } catch (Exception ignored) {
            }
        }
        toast(ok ? "Log disimpan: Download/" + name : "Gagal menyimpan log");
    }

    // ─── Auto-update check (versi binary yang benar-benar dipakai) ──────

    private void autoUpdateCheck() {
        try {
            String latest = Updater.latestVersion(this);
            if (latest == null) {
                return;
            }
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String real = Updater.parseBinaryVersion(ServerService.binaryVersion);
            String updated = sp.getString(ServerService.KEY_UPDATE_VERSION, "");
            String current = real != null ? real : Updater.normVersion(
                    updated != null && !updated.isEmpty()
                            ? updated : Updater.readBundledVersionRaw(this));
            if (real != null && real.equals(latest)) {
                sp.edit().putString(ServerService.KEY_UPDATE_VERSION, latest).apply();
                pendingVersion = null;
            } else if (current != null && !current.equals(latest)) {
                if (sp.getBoolean(ServerService.KEY_AUTO_UPDATE, false)
                        && isUnmeteredNetwork()) {
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
                    ui.post(() -> toast("Update tersedia: v" + latest
                            + " - buka Settings untuk update."));
                    if (!latest.equals(sp.getString(KEY_TG_NOTIFIED, ""))) {
                        sp.edit().putString(KEY_TG_NOTIFIED, latest).apply();
                        showUpdateNotification(latest);
                        TgBackup.sendMessage(this, "Update Vaultwarden v" + latest
                                + " tersedia. Kirim /update ke bot untuk memasang dari jauh.");
                    }
                }
            }
            TgBackup.notifyLowStorage(this);
        } catch (Exception ignored) {
        }
    }

    /** Auto-update binary hanya di jaringan non-kuota (WiFi/ethernet). */
    // API lawas sengaja untuk Android 5.0/5.1 (API 21/22); jalur modern dipakai bila API >= 23.
    @SuppressWarnings("deprecation")
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

    // Konstruktor Builder tanpa channel sengaja untuk pra-Oreo (API 21-25).
    @SuppressWarnings("deprecation")
    private void showUpdateNotification(String version) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
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
        // getSystemService(Class) baru di API 23; pakai string agar API 21/22 aman.
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(2, n);
        }
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
            String v = new org.json.JSONObject(sb.toString()).optString("version", "");
            return v.isEmpty() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    /** Versi binary yang benar-benar dipakai server saat ini (x.y.z). */
    private String currentServerVersion() {
        String real = Updater.parseBinaryVersion(ServerService.binaryVersion);
        if (real != null) {
            return real;
        }
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String updated = sp.getString(ServerService.KEY_UPDATE_VERSION, "");
        if (updated != null && !updated.isEmpty()) {
            return updated;
        }
        return Updater.readBundledVersionRaw(this);
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
                    appendUiLog("[app] " + msg);
                });
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                appendUiLog("[tg] Gagal backup otomatis saat Start: " + e);
            }
        }, "vw-tg-onstart").start();
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
                    String entered = input.getText().toString();
                    if (PinCrypto.verify(pinHash, entered)) {
                        // Migrasi hash lama (SHA-256 polos) ke PBKDF2.
                        if (!PinCrypto.isNewFormat(pinHash)) {
                            sp.edit().putString(KEY_PIN, PinCrypto.hash(entered)).apply();
                        }
                        unlocked = true;
                        catatPinDibuka();
                        dialog.dismiss();
                    } else {
                        input.setError("PIN salah");
                    }
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
        synchronized (ServerService.logBuffer) {
            ServerService.logBuffer.append(line).append('\n');
        }
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
