package com.tasirin.vaultwardenhost;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Layar awal sederhana: status server + Start/Stop + log realtime.
 *  Semua pengaturan pindah ke SettingsActivity lewat tombol titik tiga. */
public class MainActivity extends Activity {

    private static final int REQ_WRITE = 1001;
    private static final String DEFAULT_DATA_DIR = ServerService.DEFAULT_DATA_DIR;
    private static final String DEFAULT_PORT = ServerService.DEFAULT_PORT;
    /** Ekor log layar awal dibatasi agar STB RAM kecil tidak patah (item saran 6). */
    private static final int MAKS_BARIS_LOG = 150;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private View statusBanner;
    private View statusDot;
    private View heroBar;
    private TextView versionView;
    private TextView netInfoView;
    private TextView uptimeView;
    private TextView restartHint;
    private Button updateBtn;
    private TextView homeLogView;
    private ScrollView homeLogScroll;
    private Button startStopBtn;
    private Button overflowBtn;

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
    private int lastLogLen = 0;
    /** Ikuti ekor log otomatis; mati saat pengguna menggulir manual (saran 6). */
    private boolean ikutiLog = true;
    /** Status tombol Start/Stop yang sedang tampil (hindari set tiap tick). */
    private boolean tombolJalan = false;
    private boolean tombolSelaras = false;
    private boolean hintShown = false;
    private boolean refreshActive = true;
    private volatile boolean uiBusy = false;
    private long lastUiLogRefresh = 0;

    private static volatile boolean unlocked = false;
    /** Kapan PIN terakhir cocok (jangkar grace); pindah activity tak memperpanjang. */
    private static volatile long unlockAt = 0;
    /** Kapan MainActivity terakhir pause (diagnostik, bukan jangkar grace). */
    private static volatile long pauseStamp = 0;
    private static final long PIN_GRACE_MS = 60_000;
    /** Guard agar onResume beruntun tak menumpuk dialog PIN. */
    private boolean pinDialogTampil = false;

    /** Berbagi status buka PIN dengan Settings agar tidak diminta dua kali. */
    static boolean pinBaruSajaDibuka() {
        return unlocked && SystemClock.elapsedRealtime() - unlockAt < PIN_GRACE_MS;
    }

    /** Kapan PIN dibuka (untuk salin grace antar activity tanpa perpanjangan). */
    static long kapanDibuka() {
        return unlockAt;
    }

    /** Catat buka PIN dari layar lain sebagai milik bersama. */
    static void catatPinDibuka() {
        unlocked = true;
        unlockAt = SystemClock.elapsedRealtime();
        pauseStamp = unlockAt;
        // Sebarkan ke Settings (set miliknya saja, tanpa panggil balik).
        try {
            SettingsActivity.catatPinDibuka();
        } catch (Exception ignored) {
        }
    }

    @Override
    // getPackageInfo lama sengaja agar satu jalur kode untuk API 21-32.
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        // Splash ditampilkan lewat theme manifest, ganti ke tema utama di sini.
        setTheme(R.style.Theme_TasirinVaultwardenHost);
        super.onCreate(savedInstanceState);
        TgBackup.healkanStringPrefs(this);
        TgBackup.migrateAutoPref(this);
        // Privasi: nonaktifkan screenshot + preview recents dikosongkan.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        statusBanner = findViewById(R.id.statusBanner);
        statusDot = findViewById(R.id.statusDot);
        heroBar = findViewById(R.id.heroBar);
        versionView = findViewById(R.id.version);
        netInfoView = findViewById(R.id.netInfo);
        uptimeView = findViewById(R.id.uptimeInfo);
        restartHint = findViewById(R.id.restartHint);
        updateBtn = findViewById(R.id.updateBtn);
        homeLogView = findViewById(R.id.homeLog);
        homeLogScroll = findViewById(R.id.homeLogScroll);
        startStopBtn = findViewById(R.id.startStop);
        overflowBtn = findViewById(R.id.overflowBtn);

        startStopBtn.setOnClickListener(v -> {
            if (ServerService.running) {
                ServerService.stop(this);
            } else {
                saveAndStart();
            }
        });
        overflowBtn.setOnClickListener(v -> showOverflowMenu());
        // Ketuk URL untuk menyalin (pengganti tombol Salin URL yang pindah ke Settings)
        netInfoView.setOnClickListener(v -> copyShownUrl());
        // Tombol update melompat ke Settings tempat Cek Update berada
        updateBtn.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        // Pengguna menggulir manual = berhenti mengikuti ekor; kembali ke
        // bawah = ikuti lagi. Hemat CPU: cukup dengar perubahan gulir.
        homeLogScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (homeLogScroll.getChildCount() > 0) {
                ikutiLog = sedangDiBawah();
            }
        });

        // Izin storage untuk semua Android (biasa di 6-10, All files di 11+).
        StoragePerm.mintaIzinBilaPerlu(this, REQ_WRITE);

        try {
            appVersion = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        bundledVersion = readBundledVersion();
        bundledRaw = Updater.readBundledVersionRaw(this);
        ui.post(this::refreshFromService);

        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        // Cek update otomatis saat dibuka
        new Thread(this::autoUpdateCheck, "vw-auto-check").start();
        // Pastikan jadwal backup harian tetap terpasang
        TgBackup.schedule(this, TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false));
        // Remote kontrol via Telegram bot
        TgBot.schedule(this);
        // Susulan backup boot yang ditolak sistem (Android 12+ batasi start background).
        if (TgBackup.amanBoolean(sp, TgBackup.KEY_BACKUP_TERTUNDA, false)) {
            try {
                sp.edit().remove(TgBackup.KEY_BACKUP_TERTUNDA).apply();
            } catch (Exception ignored) {
            }
            // Alarm basi: user mematikan auto-backup sebelum app dibuka -
            // jangan mengunggah tanpa persetujuan (selaras ACTION_TG_BACKUP).
            if (!TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false)) {
                ServerService.catatLog("[tg] Backup susulan boot dilewati:"
                        + " auto-backup sudah dimatikan.");
            } else {
            final android.content.Context app = getApplicationContext();
            new Thread(() -> {
                try {
                    TgBackup.tungguBootStabil();
                    String msg = TgBackup.backupNow(app);
                    ServerService.catatLog("[tg] " + msg + " (susulan boot).");
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    ServerService.catatLog("[tg] " + TgBackup.pesanGalatBackup(e) + " (susulan boot).");
                }
            }, "vw-boot-susulan").start();
            }
        }
        // Susulan auto-start yang ditolak sistem dari background (Android 12+
        // membatasi start service): mulaiService menandainya, eksekusi di sini
        // saat app dibuka (sudah foreground sehingga diizinkan).
        if (TgBackup.amanBoolean(sp, ServerService.KEY_START_TERTUNDA, false)) {
            try {
                sp.edit().remove(ServerService.KEY_START_TERTUNDA).apply();
            } catch (Exception ignored) {
            }
            if (TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_START, false)
                    && !ServerService.running) {
                appendUiLog("[app] Auto-start susulan: start background sempat ditolak sistem.");
                ServerService.start(this);
            }
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode == REQ_WRITE) {
            if (!StoragePerm.tulisDiizinkan(permissions, grantResults)) {
                StoragePerm.tanganiPenolakan(this);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        refreshActive = false;
        // Jangan kunci langsung (pindah ke Settings/Log bukan keluar app);
        // maybeShowPinLock mengunci bila jeda > PIN_GRACE_MS.
        pauseStamp = SystemClock.elapsedRealtime();
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
        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
        if (TextUtils.isEmpty(dataDir)) {
            dataDir = DEFAULT_DATA_DIR;
        }
        // Tolak folder berbahaya lebih awal (traversal, root storage, area
        // sistem): service juga menolak, tapi UI wajib memberi tahu jelas
        // sebelum cek port agar tak gagal start secara misterius.
        if (!ServerService.dataDirAman(dataDir)
                || !ServerService.dataDirKanonisAman(dataDir)) {
            toast("Folder data tidak valid - dikembalikan ke bawaan di Settings.");
            appendUiLog("[app] Folder data tidak valid: '" + dataDir + "' - Start dibatalkan.");
            return;
        }
        // Folder eksternal tanpa izin pasti gagal writable — minta dulu, batalkan Start.
        if (!StoragePerm.siapStart(this, dataDir, REQ_WRITE)) {
            appendUiLog("[app] Start dibatalkan: izin penyimpanan belum diberikan.");
            return;
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
        // Cek bind di worker thread: bind ServerSocket di UI thread rawan ANR/
        // StrictMode, dan hasilnya tetap TOCTOU (service cek ulang sebelum start).
        final int portFix = portNum;
        setBusy(true);
        new Thread(() -> {
            final boolean busy = ServerService.isPortBusy(portFix);
            final boolean butuhRoot = busy && ServerService.portButuhRoot(portFix);
            ui.post(() -> {
                setBusy(false);
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (butuhRoot) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Port " + portFix + " butuh akses root")
                            .setMessage("Port utama (" + portFix + ") di bawah 1024 hanya bisa"
                                    + " dipakai dengan akses root.\n"
                                    + "Ganti port ke >= 1024 (mis. 8088) di Settings.")
                            .setPositiveButton("Oke", null)
                            .show();
                    return;
                }
                if (busy) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Port " + portFix + " sedang dipakai")
                            .setMessage("Port utama (" + portFix + ") sudah dipakai proses lain.\n"
                                    + "Stop aplikasi lain yang memakainya, ganti port di Settings, "
                                    + "atau restart HP dulu.")
                            .setPositiveButton("Oke", null)
                            .show();
                    return;
                }
                lanjutStart(finalDataDir);
            });
        }, "vw-port-check").start();
    }

    /** Lanjutan Start setelah cek port selesai (berjalan di UI thread). */
    private void lanjutStart(String dataDir) {
        final String finalDataDir = dataDir;
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
            String sibuk = dl.isEmpty() ? getString(R.string.busy_work) : dl;
            String kunciSibuk = "sibuk|" + sibuk;
            if (!kunciSibuk.equals(lastShownStatus)) {
                statusView.setText(sibuk);
                statusView.setBackgroundResource(0);
                statusBanner.setBackgroundResource(R.drawable.bg_status_busy);
                statusDot.setBackgroundResource(R.drawable.bg_dot_busy);
                lastShownStatus = kunciSibuk;
            }
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
            statusView.setBackgroundResource(0);
            statusBanner.setBackgroundResource(running
                    ? R.drawable.bg_status_running : R.drawable.bg_status_stopped);
            statusDot.setBackgroundResource(running
                    ? R.drawable.bg_dot_running : R.drawable.bg_dot_stopped);
            heroBar.setBackgroundResource(running
                    ? R.drawable.bg_hero_running : R.drawable.bg_hero);
            lastShownStatus = key;
        }
        // Tombol Stop merah + deskripsi aksesibilitas (saran 4)
        if (!tombolSelaras || tombolJalan != running) {
            tombolJalan = running;
            tombolSelaras = true;
            startStopBtn.setText(getString(running ? R.string.stop : R.string.start));
            startStopBtn.setBackgroundResource(running
                    ? R.drawable.bg_btn_stop : R.drawable.bg_btn_primary);
            startStopBtn.setContentDescription(
                    getString(running ? R.string.stop_desc : R.string.start_desc));
        }

        // Peringatan bila setting diubah di Settings tapi server belum di-restart
        boolean changed = false;
        if (running) {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            // Baca tahan korup: tipe prefs salah tak boleh melempar
            // ClassCastException tiap detik di UI thread (pola aman* bot).
            String d = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
            if (d == null || d.trim().isEmpty()) {
                d = DEFAULT_DATA_DIR;
            }
            String p = ServerService.effectivePort(sp);
            String a = TgBackup.amanString(sp, ServerService.KEY_ADMIN_TOKEN, "");
            if (a == null) {
                a = "";
            }
            String rd = ServerService.runningDataDir == null ? "" : ServerService.runningDataDir;
            String rp = ServerService.runningPort == null ? "" : ServerService.runningPort;
            String ra = ServerService.runningAdminToken == null ? "" : ServerService.runningAdminToken;
            String wv = Updater.webVaultFromVersion(this);
            if (wv == null) {
                wv = "";
            }
            String rwv = ServerService.runningWvFrom == null ? "" : ServerService.runningWvFrom;
            changed = !d.trim().equals(rd.trim())
                    || !p.trim().equals(rp.trim())
                    || !a.trim().equals(ra.trim())
                    || !wv.equals(rwv);
        }
        restartHint.setVisibility(changed ? View.VISIBLE : View.GONE);

        // Peringatan bila update binary tersedia tapi belum dipasang
        boolean updAvail = false;
        if (pendingVersion != null) {
            SharedPreferences psp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String real = Updater.parseBinaryVersion(ServerService.binaryVersion);
            // Fallthrough aman: baca di bawah pakai amanString (lihat cur/up).
            if (real != null && real.equals(pendingVersion)) {
                psp.edit().putString(ServerService.KEY_UPDATE_VERSION, pendingVersion).apply();
                pendingVersion = null; // update sudah terpasang
            } else {
                String up = TgBackup.amanString(psp, ServerService.KEY_UPDATE_VERSION, "");
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
        // Rantai D-pad tak boleh menunjuk ke tombol yang gone: status kini
        // di footer, jadi yang dialihkan adalah tetangga updateBtn (info URL & Start)
        if (updAvail != lastUpdBtnVisible) {
            lastUpdBtnVisible = updAvail;
            netInfoView.setNextFocusDownId(updAvail ? R.id.updateBtn : R.id.startStop);
            startStopBtn.setNextFocusUpId(updAvail ? R.id.updateBtn : R.id.netInfo);
        }

        String net = ServerService.localUrl(this);
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
        String delta = "";
        int mentah = 0;
        synchronized (ServerService.logBuffer) {
            int n = ServerService.logBuffer.length();
            // Cek trim di dalam lock: snapshot di luar lock bisa basi bila
            // catatLog memangkas buffer di jeda baca, membuat log beku
            // sampai buffer tumbuh melewati nilai basi.
            if (n < lastLogLen) {
                lastLogLen = 0;
                homeLogView.setText("");
                hintShown = false;
            }
            if (n > lastLogLen) {
                delta = ServerService.logBuffer.substring(lastLogLen, n);
                mentah = delta.length();
            }
        }
        // Samarkan seperti jalur LogActivity/bagi/Telegram: token bot dan
        // rahasia di teks exception tak boleh tampil mentah di layar.
        // Offset tetap maju pakai panjang mentah (samaran mengubah panjang).
        if (!delta.isEmpty()) {
            lastLogLen += mentah;
            delta = LogActivity.samarkanLog(delta);
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
        // lastLogLen sudah maju pakai panjang mentah di atas (samaran
        // mengubah panjang sehingga tak boleh dipakai untuk offset).
        tempelLogBerwarna(delta);
        // Pengaman memori STB: tampilkan ekor saja bila teks membengkak.
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
            int awalTampil = nl < 0 ? potong : nl;
            // Jangan belah pasangan surrogate emoji di titik potong.
            if (awalTampil > 0 && awalTampil < penuh.length()
                    && Character.isLowSurrogate(penuh.charAt(awalTampil))
                    && Character.isHighSurrogate(penuh.charAt(awalTampil - 1))) {
                awalTampil++;
            }
            homeLogView.setText(penuh.subSequence(awalTampil, penuh.length()));
        }
        // Batas 150 baris tampil: buang baris tertua dari depan.
        int total = hitungBaris(homeLogView.getText());
        if (total > MAKS_BARIS_LOG) {
            homeLogView.setText(buangBarisDepan(homeLogView.getText(), total - MAKS_BARIS_LOG));
        }
        // Gulir otomatis hanya bila pengguna tidak sedang membaca atas (saran 6).
        if (ikutiLog) {
            homeLogScroll.post(() -> homeLogScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** Ekor ScrollView sudah di bawah (toleransi 8px untuk pembulatan). */
    private boolean sedangDiBawah() {
        if (homeLogScroll.getChildCount() == 0) {
            return true;
        }
        View anak = homeLogScroll.getChildAt(0);
        int sisa = anak.getBottom() - (homeLogScroll.getHeight() + homeLogScroll.getScrollY());
        return sisa <= 8;
    }

    /** Jumlah baris = jumlah '\n' (satu baris terakhir tanpa newline ikut dihitung). */
    private int hitungBaris(CharSequence teks) {
        if (teks == null || teks.length() == 0) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < teks.length(); i++) {
            if (teks.charAt(i) == '\n') {
                n++;
            }
        }
        return teks.charAt(teks.length() - 1) == '\n' ? n : n + 1;
    }

    /** Buang sejumlah baris dari depan, span warna ikut terjaga. */
    private CharSequence buangBarisDepan(CharSequence teks, int buang) {
        int pos = 0;
        int ketemu = 0;
        while (pos < teks.length() && ketemu < buang) {
            if (teks.charAt(pos) == '\n') {
                ketemu++;
            }
            pos++;
        }
        return teks.subSequence(pos, teks.length());
    }

    // getColor(int) lawas sengaja agar satu jalur kode untuk API 21-32.
    @SuppressWarnings("deprecation")
    private void tempelLogBerwarna(String delta) {
        int warnaGalat = getResources().getColor(R.color.log_error);
        int warnaAwas = getResources().getColor(R.color.log_warn);
        SpannableStringBuilder tempel = new SpannableStringBuilder();
        int mulai = 0;
        for (int i = 0; i <= delta.length(); i++) {
            if (i == delta.length() || delta.charAt(i) == '\n') {
                String baris = delta.substring(mulai, i);
                // Sisa tanpa newline di ujung (baris parsial) ditempel apa adanya
                // agar chunk berikut menyambung; bukan baris kosong baru.
                if (i < delta.length() || !baris.isEmpty()) {
                    int awal = tempel.length();
                    tempel.append(baris);
                    if (i < delta.length()) {
                        tempel.append("\n");
                    }
                    String kecil = baris.toLowerCase(java.util.Locale.ROOT);
                    if (mengandung(kecil, "error", "exception", "panic", "gagal",
                            "fatal", "traceback")) {
                        tempel.setSpan(new ForegroundColorSpan(warnaGalat), awal,
                                awal + baris.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else if (mengandung(kecil, "warn", "peringatan", "deprecated", "awas")) {
                        tempel.setSpan(new ForegroundColorSpan(warnaAwas), awal,
                                awal + baris.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                mulai = i + 1;
            }
        }
        homeLogView.append(tempel);
    }

    /** Salah satu kata kunci muncul di baris (cocok sederhana, tanpa regex). */
    private boolean mengandung(String kecil, String... kata) {
        for (String k : kata) {
            if (kecil.contains(k)) {
                return true;
            }
        }
        return false;
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

    // Html.fromHtml lama untuk API 21-23; jalur modern dipakai bila API >= 24.
    @SuppressWarnings("deprecation")
    private void showAboutDialog() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR);
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
        tv.setLinkTextColor(getResources().getColor(R.color.accent));
        new AlertDialog.Builder(this)
                .setTitle("Tentang")
                .setView(tv)
                .setPositiveButton("Tutup", null)
                .show();
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
        if (!TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false)) {
            return;
        }
        // Sudah dibuka di Settings dalam 60 detik: jangan minta lagi (salin jangkar, tanpa perpanjangan).
        if (SettingsActivity.pinBaruSajaDibuka()) {
            unlocked = true;
            unlockAt = SettingsActivity.kapanDibuka();
            return;
        }
        if (unlocked && SystemClock.elapsedRealtime() - unlockAt < PIN_GRACE_MS) {
            return;
        }
        unlocked = false;
        final String pinHash = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
        if (pinHash == null || pinHash.isEmpty()) {
            try {
                sp.edit().putBoolean(PinGate.KEY_PIN_ON, false).apply();
            } catch (Exception ignored) {
            }
            ServerService.catatLog("[app] PIN dimatikan otomatis: hash hilang/rusak.");
            return;
        }
        if (pinDialogTampil) {
            return;
        }
        pinDialogTampil = true;
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setMaxLines(1);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Masukkan PIN")
                .setMessage("App dikunci")
                .setView(input)
                .setPositiveButton("Buka", null)
                .setNegativeButton("Keluar", (d, w) -> finish())
                .create();
        // Kunci dialog: Back/sentuh-luar tak boleh menutup tanpa PIN
        // (sebelumnya tombol Back melewatkan kunci app sepenuhnya).
        // Satu-satunya jalan keluar selain PIN: "Keluar" (finish).
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    final android.widget.Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    // Wall-clock agar reboot tak mereset lockout brute-force.
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
                            sp.edit().putString(PinGate.KEY_PIN_HASH, PinCrypto.hash(entered)).apply();
                        }
                        final boolean hasil = cocok;
                        ui.post(() -> {
                            ok.setEnabled(true);
                            if (hasil) {
                                catatPinDibuka();
                                dialog.dismiss();
                            } else {
                                input.setError("PIN salah");
                            }
                        });
                    }, "vw-pin-check").start();
                }));
        dialog.setOnDismissListener(d -> pinDialogTampil = false);
        dialog.show();
    }

    private void setBusy(final boolean busy) {
        uiBusy = busy;
        ui.post(() -> {
            startStopBtn.setEnabled(!busy);
            if (busy) {
                statusView.setText(getString(R.string.busy_work));
                statusView.setBackgroundResource(0);
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
