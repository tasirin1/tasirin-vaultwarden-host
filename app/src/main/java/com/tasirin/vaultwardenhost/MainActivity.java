package com.tasirin.vaultwardenhost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_WRITE = 1001;
    private static final int REQ_PIN = 1002;
    private static final String DEFAULT_PORT = ServerService.DEFAULT_PORT;

    private static final int MAKS_BARIS_LOG = 150;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refreshLoop = this::refreshFromService;

    private TextView statusView;
    private View statusBanner;
    private View statusDot;
    private TextView versionView;
    private TextView netInfoView;
    private TextView uptimeView;
    private TextView restartHint;
    private Button updateBtn;
    private TextView homeLogView;
    private ScrollView homeLogScroll;
    private Button startStopBtn;
    private ImageButton overflowBtn;

    private volatile String pendingVersion = null;
    private String appVersion = "";
    private String bundledVersion = "?";
    private String bundledRaw = null;
    private String lastShownStatus = "";

    private String teksBerjalan = "";
    private String teksBerhenti = "";
    private String teksSibuk = "";
    private String lastShownNet = "";
    private String lastShownVersion = "";
    private String lastShownUptime = "";
    private boolean lastUpdBtnVisible = true;
    private String lastUpdText = "";
    private String memoCocokA = "\0";
    private String memoCocokB = "\0";
    private boolean memoCocokHasil = false;
    private String memoCocokA2 = "\0";
    private String memoCocokB2 = "\0";
    private boolean memoCocokHasil2 = false;
    private String lastVersiApp = "\0";
    private String lastVersiBin = "\0";
    private String lastVersiBundled = "\0";
    private long lastUpDetik = -1;
    private int lastLogLen = 0;

    private int tickCachePrefs = 0;
    private boolean paksaCachePrefs = true;
    private String cacheD = "";
    private String cacheP = "";
    private String cacheA = "";
    private String cacheWv = "";

    private boolean ikutiLog = true;

    private boolean tombolJalan = false;
    private boolean tombolSelaras = false;
    private boolean hintShown = false;
    private boolean refreshActive = true;

    private boolean mulaiUlangSetelahIzin = false;
    private boolean startTertundaPort = false;
    private boolean startTertundaDariPutaran = false;

    private static final String STATE_MULAI_ULANG_IZIN = "mulai_ulang_izin";
    private static final String STATE_START_TERTUNDA_PORT = "start_tertunda_port";

    private static volatile boolean uiBusy = false;
    private long lastUiLogRefresh = 0;

    private static volatile String cacheUrl = "";
    private static volatile long cacheWaktu = 0;

    private boolean pinBukaJalan = false;

    static void catatPinDibuka() {
        if (!PinGate.dalamGraceBersama()) {
            PinGate.bukaKunciBersama();
        }
    }

    @Override

    @SuppressWarnings("deprecation")

    @SuppressLint("ApplySharedPref")
    protected void onCreate(Bundle savedInstanceState) {

        setTheme(R.style.Theme_TasirinVaultwardenHost);
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mulaiUlangSetelahIzin = savedInstanceState.getBoolean(
                    STATE_MULAI_ULANG_IZIN, false);
            startTertundaDariPutaran = savedInstanceState.getBoolean(
                    STATE_START_TERTUNDA_PORT, false);
        }
        TgBackup.healkanStringPrefs(this);
        TgBackup.migrateAutoPref(this);

        LogActivity.bersihkanClipBasiJikaAda(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        statusBanner = findViewById(R.id.statusBanner);
        statusDot = findViewById(R.id.statusDot);
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

        netInfoView.setOnClickListener(v -> copyShownUrl());

        updateBtn.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        homeLogScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (homeLogScroll.getChildCount() > 0) {
                ikutiLog = sedangDiBawah();
            }
        });

        StoragePerm.mintaIzinBilaPerlu(this, REQ_WRITE);

        try {
            android.content.pm.PackageInfo infoBar = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            if (infoBar != null) {
                if (infoBar.versionName != null) {
                    appVersion = infoBar.versionName;
                }
                TextView subBar = findViewById(R.id.toolbarSubtitle);
                if (subBar != null) {
                    subBar.setText(getString(R.string.toolbar_subtitle,
                            appVersion.isEmpty() ? "?" : appVersion, infoBar.versionCode));
                }
            }
        } catch (Exception ignored) {
        }
        bundledVersion = Updater.readBundledVersionLabel(this);
        bundledRaw = Updater.readBundledVersionRaw(this);
        teksBerjalan = getString(R.string.running);
        teksBerhenti = getString(R.string.stopped);
        teksSibuk = getString(R.string.busy_work);
        ui.post(refreshLoop);

        if (uiBusy) {
            setBusy(true);
        }

        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);

        Util.jalankanLama(this::autoUpdateCheck);

        final android.content.Context appPin = getApplicationContext();
        Util.jalankanBg(() -> PinGate.kuatkanHashDini(appPin));

        TgBackup.schedule(this, TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false));

        TgBot.schedule(this);

        if (TgBackup.amanBoolean(sp, TgBackup.KEY_BACKUP_TERTUNDA, false)) {

            try {
                sp.edit().remove(TgBackup.KEY_BACKUP_TERTUNDA).commit();
            } catch (Exception ignored) {
            }

            if (!TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false)) {
                ServerService.catatLog("[tg] Backup susulan boot dilewati:"
                        + " auto-backup sudah dimatikan.");
            } else {
            final android.content.Context app = getApplicationContext();
            Util.jalankanBg(() -> {
                try {

                    if (!TgBackup.bolehBackupOtomatis(
                            TgBackup.amanString(sp, TgBackup.KEY_TG_PASS, ""))) {
                        ServerService.catatLog("[tg] " + TgBackup.pesanTolakPlainOtomatis()
                                + " (susulan boot).");
                        return;
                    }

                    if (TgBackup.tundaBilaBootBaru(app, "backup susulan boot")) {
                        return;
                    }

                    String msg = TgBackup.backupOtomatis(app);
                    ServerService.catatLog("[tg] " + msg + " (susulan boot).");
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    ServerService.catatLog("[tg] " + TgBackup.pesanGalatBackup(e) + " (susulan boot).");
                }
            });
            }
        }

        if (TgBackup.amanBoolean(sp, ServerService.KEY_START_TERTUNDA, false)) {
            if (TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_START, false)
                    && !ServerService.running) {
                appendUiLog("[app] Auto-start susulan: start background sempat ditolak sistem.");

                boolean diterima = false;
                try {
                    diterima = ServerService.start(this);
                } catch (Exception ignored) {
                }
                if (diterima) {
                    try {
                        sp.edit().remove(ServerService.KEY_START_TERTUNDA).apply();
                    } catch (Exception ignored2) {
                    }
                }
            } else {

                try {
                    sp.edit().remove(ServerService.KEY_START_TERTUNDA).apply();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshActive = true;
        paksaCachePrefs = true;
        maybeShowPinLock();

        if (startTertundaDariPutaran) {
            startTertundaDariPutaran = false;
            saveAndStart();
        }

        ui.removeCallbacks(refreshLoop);
        if (!pinBukaJalan) {
            ui.post(refreshLoop);
        }
    }

    static boolean pinMenahanKonten(boolean pinBukaJalan, boolean pinAktif,
            boolean dalamGrace) {
        if (pinBukaJalan) {
            return true;
        }
        return pinAktif && !dalamGrace;
    }

    private boolean pinMenahanKonten() {
        try {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            return pinMenahanKonten(pinBukaJalan,
                    TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false),
                    PinGate.dalamGraceBersama(),
                    !TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "").isEmpty());
        } catch (Exception e) {
            return pinBukaJalan;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PIN) {
            pinBukaJalan = false;
            if (resultCode == RESULT_OK) {
                catatPinDibuka();
            } else {
                finish();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode == REQ_WRITE) {
            boolean diberi = StoragePerm.tulisDiizinkan(permissions, grantResults);
            if (!diberi) {
                StoragePerm.tanganiPenolakan(this);
            }

            boolean lanjut = mulaiUlangSetelahIzin;
            mulaiUlangSetelahIzin = false;
            if (diberi && lanjut && !isFinishing() && !isDestroyed()) {
                saveAndStart();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        refreshActive = false;
        ui.removeCallbacks(refreshLoop);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(STATE_MULAI_ULANG_IZIN, mulaiUlangSetelahIzin);
        outState.putBoolean(STATE_START_TERTUNDA_PORT, startTertundaPort);
    }

    @Override
    protected void onDestroy() {

        DialogAman.tutup(this);
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }

    private void showOverflowMenu() {
        PopupMenu popup = new PopupMenu(this, overflowBtn, Gravity.END);
        popup.getMenu().add(0, 1, 0, getString(R.string.open_settings));
        popup.getMenu().add(0, 2, 0, getString(R.string.about));
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else {
                showAboutDialog();
            }
            return true;
        });
        popup.show();
    }

    private void saveAndStart() {
        mulaiUlangSetelahIzin = false;
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
        if (TextUtils.isEmpty(dataDir)) {
            dataDir = ServerService.dataDirBawaanSegar();
        }

        if (!ServerService.dataDirAman(dataDir)
                || !ServerService.dataDirKanonisAman(dataDir)) {
            toast(getString(R.string.folder_tidak_valid));
            appendUiLog("[app] Folder data tidak valid: '" + dataDir + "' - direset ke bawaan.");
            try {
                sp.edit().putString(ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar()).apply();
            } catch (Exception ignored) {
            }
            dataDir = ServerService.dataDirBawaanSegar();
        }

        if (!StoragePerm.siapStart(this, dataDir, REQ_WRITE)) {

            mulaiUlangSetelahIzin = true;
            appendUiLog("[app] Start dibatalkan: izin penyimpanan belum diberikan.");
            return;
        }
        final String finalDataDir = dataDir;

        final int portFix = Integer.parseInt(ServerService.effectivePort(sp));

        startTertundaPort = true;
        setBusy(true);
        boolean masuk = Util.jalankanBg(() -> {
            ServerService.bereskanServerYatim();
            final boolean busy = ServerService.isPortBusy(portFix);
            final boolean butuhRoot = busy && ServerService.portButuhRoot(portFix);
            ui.post(() -> {
                startTertundaPort = false;
                setBusy(false);
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (butuhRoot) {
                    DialogAman.tampil(MainActivity.this, new AlertDialog.Builder(MainActivity.this)
                            .setTitle(getString(R.string.port_root_judul, portFix))
                            .setMessage(getString(R.string.port_root_pesan, portFix))
                            .setPositiveButton(R.string.ok, null)
                            .create());
                    return;
                }
                if (busy) {
                    DialogAman.tampil(MainActivity.this, new AlertDialog.Builder(MainActivity.this)
                            .setTitle(getString(R.string.port_dipakai_judul, portFix))
                            .setMessage(getString(R.string.port_dipakai_pesan, portFix))
                            .setPositiveButton(R.string.ok, null)
                            .create());
                    return;
                }
                lanjutStart(finalDataDir);
            });
        });
        if (!masuk) {

            startTertundaPort = false;
            setBusy(false);
            toast(getString(R.string.antrean_penuh));
        }
    }

    private void lanjutStart(String dataDir) {
        final String finalDataDir = dataDir;
        if (!Updater.webVaultReady(this, finalDataDir)) {

            DialogAman.tampil(this, new AlertDialog.Builder(this)
                    .setTitle(R.string.wv_belum_judul)
                    .setMessage(R.string.wv_belum_pesan)
                    .setPositiveButton(R.string.wv_unduh_mulai, (d, w) -> {
                        setBusy(true);

                        final android.content.Context appCtx = getApplicationContext();
                        boolean masuk = Util.jalankanLama(() -> {
                            String msg;
                            boolean gagal = false;
                            try {
                                msg = Updater.updateWebVault(appCtx);
                            } catch (Exception e) {
                                gagal = true;
                                msg = "Web-vault download failed: " + e;
                            }
                            final String info = "[app] " + msg;
                            final boolean gagalFinal = gagal;

                            if (!gagal) {
                                ServerService.start(appCtx);
                                maybeAutoBackup();
                            }
                            ui.post(() -> {

                                setBusy(false);
                                if (isFinishing() || isDestroyed()) {
                                    return;
                                }
                                appendUiLog(info);
                                if (gagalFinal) {
                                    toast(getString(R.string.wv_unduh_gagal_server));
                                }
                            });
                        });
                        if (!masuk) {

                            setBusy(false);
                            toast(getString(R.string.antrean_penuh));
                        }
                    })
                    .setNegativeButton(R.string.wv_mulai_tanpa, (d, w) -> {
                        ServerService.start(this);
                        maybeAutoBackup();
                    })
                    .create());
            return;
        }
        ServerService.start(this);
        maybeAutoBackup();
    }

    private void refreshFromService() {
        if (!uiHidup()) {
            return;
        }
        if (pinMenahanKonten()) {
            return;
        }

        ui.removeCallbacks(refreshLoop);

        if (uiBusy) {
            String dl = Updater.downloadStatus;
            String sibuk = dl.isEmpty()
                    ? (teksSibuk.isEmpty() ? getString(R.string.busy_work) : teksSibuk)
                    : dl;
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
                ui.postDelayed(refreshLoop, 1500);
            }
            return;
        }

        boolean running = ServerService.running;

        String statusText = running ? teksBerjalan : teksBerhenti;
        String key = running ? "on" : "off";
        if (!key.equals(lastShownStatus)) {
            statusView.setText(statusText);
            statusView.setBackgroundResource(0);
            statusBanner.setBackgroundResource(running
                    ? R.drawable.bg_status_running : R.drawable.bg_status_stopped);
            statusDot.setBackgroundResource(running
                    ? R.drawable.bg_dot_running : R.drawable.bg_dot_stopped);
            lastShownStatus = key;
        }

        if (!tombolSelaras || tombolJalan != running) {
            tombolJalan = running;
            tombolSelaras = true;
            startStopBtn.setText(getString(running ? R.string.stop : R.string.start));
            startStopBtn.setBackgroundResource(running
                    ? R.drawable.bg_btn_stop : R.drawable.bg_btn_primary);
            startStopBtn.setContentDescription(
                    getString(running ? R.string.stop_desc : R.string.start_desc));
        }

        boolean changed = false;
        if (running) {
            if (paksaCachePrefs || tickCachePrefs++ % 5 == 0) {
                paksaCachePrefs = false;
                SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);

                String d = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
                if (d == null || d.trim().isEmpty()) {
                    d = ServerService.dataDirBawaanSegar();
                }
                cacheD = d;
                cacheP = ServerService.effectivePort(sp);
                String ca = TgBackup.amanString(sp, ServerService.KEY_ADMIN_TOKEN, "");
                cacheA = ca == null ? "" : ca;
                String wvCache = Updater.webVaultFromVersion(this);
                cacheWv = wvCache == null ? "" : wvCache;
            }
            String d = cacheD;
            String p = cacheP;
            String a = cacheA;
            String rd = ServerService.runningDataDir == null ? "" : ServerService.runningDataDir;
            String rp = ServerService.runningPort == null ? "" : ServerService.runningPort;
            String ra = ServerService.runningAdminToken == null ? "" : ServerService.runningAdminToken;
            String wv = cacheWv;
            String rwv = ServerService.runningWvFrom == null ? "" : ServerService.runningWvFrom;

            String dRapi = d.trim();
            String pRapi = p.trim();
            String rdRapi = rd.trim();
            String rpRapi = rp.trim();
            String raRapi = ra.trim();
            changed = !dRapi.equals(rdRapi)
                    || !pRapi.equals(rpRapi)
                    || !ServerService.sidikTokenAdmin(a).equals(raRapi)
                    || !wv.equals(rwv);
        }
        restartHint.setVisibility(changed ? View.VISIBLE : View.GONE);

        boolean updAvail = false;
        String pend = pendingVersion;
        if (pend != null) {
            SharedPreferences psp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String real = Updater.parseBinaryVersion(ServerService.binaryVersion);

            String ra = real == null ? "" : real;
            boolean cocok1;
            if (ra.equals(memoCocokA) && pend.equals(memoCocokB)) {
                cocok1 = memoCocokHasil;
            } else {
                cocok1 = Updater.versiCocok(real, pend);
                memoCocokA = ra;
                memoCocokB = pend;
                memoCocokHasil = cocok1;
            }

            if (cocok1) {
                psp.edit().putString(ServerService.KEY_UPDATE_VERSION, pend).apply();
                pendingVersion = null;
            } else {
                String up = TgBackup.amanString(psp, ServerService.KEY_UPDATE_VERSION, "");
                String cur = real != null ? real : Updater.normVersion(up != null && !up.isEmpty()
                        ? up : bundledRaw);
                String ca = cur == null ? "" : cur;
                boolean cocok2;
                if (ca.equals(memoCocokA2) && pend.equals(memoCocokB2)) {
                    cocok2 = memoCocokHasil2;
                } else {
                    cocok2 = Updater.versiCocok(cur, pend);
                    memoCocokA2 = ca;
                    memoCocokB2 = pend;
                    memoCocokHasil2 = cocok2;
                }
                if (cocok2) {
                    pendingVersion = null;
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

        if (updAvail != lastUpdBtnVisible) {
            lastUpdBtnVisible = updAvail;
            netInfoView.setNextFocusDownId(updAvail ? R.id.updateBtn : R.id.startStop);
            startStopBtn.setNextFocusUpId(updAvail ? R.id.updateBtn : R.id.netInfo);
        }

        String net;
        long now = SystemClock.elapsedRealtime();
        if (!cacheUrl.isEmpty() && now - cacheWaktu < 10000) {
            net = cacheUrl;
        } else {
            net = ServerService.localUrl(this);
            cacheUrl = net;
            cacheWaktu = now;
        }
        if (!net.equals(lastShownNet)) {
            netInfoView.setText(net);
            lastShownNet = net;
        }

        long upDetik = -1;
        if (running) {
            long up = ServerService.uptimeMs();
            if (up > 0) {
                upDetik = up / 1000;
            }
        }
        if (upDetik != lastUpDetik) {
            lastUpDetik = upDetik;
            String uptime = "";
            if (upDetik >= 0) {
                uptime = getString(R.string.uptime_format, TgBot.durationText(upDetik * 1000));
            }
            if (!uptime.equals(lastShownUptime)) {
                lastShownUptime = uptime;
                uptimeView.setText(uptime);
                uptimeView.setVisibility(uptime.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }

        String binVer = ServerService.binaryVersion;
        if (binVer == null) {
            binVer = "";
        }
        if (!appVersion.equals(lastVersiApp) || !binVer.equals(lastVersiBin)
                || !bundledVersion.equals(lastVersiBundled)) {
            lastVersiApp = appVersion;
            lastVersiBin = binVer;
            lastVersiBundled = bundledVersion;
            String version = binVer.isEmpty()
                    ? getString(R.string.versi_app, appVersion, bundledVersion)
                    : getString(R.string.versi_app_biner, appVersion, binVer);
            if (!version.equals(lastShownVersion)) {
                versionView.setText(version);
                lastShownVersion = version;
            }
        }

        refreshHomeLog();

        if (refreshActive) {

            ui.postDelayed(refreshLoop, ServerService.running ? 1000 : 2000);
        }
    }

    private void refreshHomeLog() {

        String delta = "";
        synchronized (ServerService.logBuffer) {
            int n = ServerService.logBuffer.length();

            if (n < lastLogLen || n - lastLogLen > BATAS_LOMPATAN) {
                lastLogLen = awalBarisSetelah(ServerService.logBuffer,
                        Math.max(0, n - BATAS_DELTA_TICK), n);
                homeLogView.setText("");
                hintShown = false;
            } else if (lastLogLen > 0 && lastLogLen <= n
                    && ServerService.logBuffer.charAt(lastLogLen - 1) != '\n') {

                lastLogLen = awalBarisSetelah(ServerService.logBuffer, lastLogLen, n);
            }
            if (n > lastLogLen) {

                int akhir = ujungBarisUtuh(ServerService.logBuffer, lastLogLen,
                        lastLogLen + BATAS_DELTA_TICK, n);
                if (akhir > lastLogLen) {
                    delta = ServerService.logBuffer.substring(lastLogLen, akhir);
                    lastLogLen = akhir;
                } else if (akhir < 0) {

                    lastLogLen = n;
                }
            }
        }

        if (!delta.isEmpty() && LogActivity.butuhSamaran(delta)) {
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

        tempelLogBerwarna(delta);

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

            if (awalTampil > 0 && awalTampil < penuh.length()
                    && Character.isLowSurrogate(penuh.charAt(awalTampil))
                    && Character.isHighSurrogate(penuh.charAt(awalTampil - 1))) {
                awalTampil++;
            }
            homeLogView.setText(penuh.subSequence(awalTampil, penuh.length()));
        }

        if (homeLogView.length() > 25000) {
            int total = hitungBaris(homeLogView.getText());
            if (total > MAKS_BARIS_LOG) {
                homeLogView.setText(buangBarisDepan(homeLogView.getText(), total - MAKS_BARIS_LOG));
            }
        }

        if (ikutiLog) {
            homeLogScroll.post(() -> homeLogScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private boolean sedangDiBawah() {
        if (homeLogScroll.getChildCount() == 0) {
            return true;
        }
        View anak = homeLogScroll.getChildAt(0);
        int sisa = anak.getBottom() - (homeLogScroll.getHeight() + homeLogScroll.getScrollY());
        return sisa <= 8;
    }

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

    static int awalBarisSetelah(CharSequence buf, int dari, int n) {
        if (buf == null || n <= 0) {
            return 0;
        }
        for (int i = Math.max(0, dari); i < n; i++) {
            if (buf.charAt(i) == '\n') {
                return i + 1;
            }
        }
        return n;
    }

    static int ujungBarisUtuh(CharSequence buf, int mulai, int batas, int n) {
        if (buf == null || n <= mulai) {
            return mulai;
        }
        if (batas >= n) {
            return n;
        }
        for (int i = Math.min(batas, n - 1); i > mulai; i--) {
            if (buf.charAt(i) == '\n') {
                return i + 1;
            }
        }
        return -1;
    }

    private int warnaGalatCache = 0;
    private int warnaAwasCache = 0;
    private boolean warnaCacheSiap = false;

    private static final int BATAS_DELTA_TICK = 8192;

    private static final int BATAS_LOMPATAN = BATAS_DELTA_TICK * 4;

    @SuppressWarnings("deprecation")
    private void tempelLogBerwarna(String delta) {
        if (!warnaCacheSiap) {
            warnaGalatCache = getResources().getColor(R.color.log_error);
            warnaAwasCache = getResources().getColor(R.color.log_warn);
            warnaCacheSiap = true;
        }
        int warnaGalat = warnaGalatCache;
        int warnaAwas = warnaAwasCache;
        SpannableStringBuilder tempel = new SpannableStringBuilder();
        int mulai = 0;
        for (int i = 0; i <= delta.length(); i++) {
            if (i == delta.length() || delta.charAt(i) == '\n') {

                if (i < delta.length() || i > mulai) {
                    int awal = tempel.length();
                    tempel.append(delta, mulai, i);
                    if (i < delta.length()) {
                        tempel.append("\n");
                    }
                    if (muatKataRentang(delta, mulai, i, KATA_GALAT)) {
                        tempel.setSpan(new ForegroundColorSpan(warnaGalat), awal,
                                awal + (i - mulai), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else if (muatKataRentang(delta, mulai, i, KATA_AWAS)) {
                        tempel.setSpan(new ForegroundColorSpan(warnaAwas), awal,
                                awal + (i - mulai), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                mulai = i + 1;
            }
        }
        homeLogView.append(tempel);
    }

    private static final String[] KATA_GALAT =
            {"error", "exception", "panic", "gagal", "fatal", "traceback"};
    private static final String[] KATA_AWAS =
            {"warn", "peringatan", "deprecated", "awas"};

    static int indeksTakPekaRentang(String s, int dari, int sampai, String kata) {
        int m = kata.length();
        if (m == 0 || dari < 0 || sampai > s.length() || dari > sampai) {
            return -1;
        }
        for (int i = dari; i + m <= sampai; i++) {
            if (!Util.samaHurufAscii(s.charAt(i), kata.charAt(0))) {
                continue;
            }
            int j = 1;
            while (j < m && Util.samaHurufAscii(s.charAt(i + j), kata.charAt(j))) {
                j++;
            }
            if (j == m) {
                return i;
            }
        }
        return -1;
    }

    static boolean muatKataRentang(String teks, int mulai, int akhir, String[] daftar) {
        if (teks == null || daftar == null) {
            return false;
        }
        for (String kata : daftar) {
            if (indeksTakPekaRentang(teks, mulai, akhir, kata) >= 0) {
                return true;
            }
        }
        return false;
    }

    private void copyShownUrl() {
        String url = ServerService.localUrl(this);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && LogActivity.salinBersihOtomatis(this, "vaultwarden-url", url)) {
            if (url.contains("127.0.0.1") || url.contains("localhost")) {
                toast(getString(R.string.url_disalin_loopback, url));
            } else {
                toast(getString(R.string.url_copied, url));
            }
        } else {
            toast(url);
        }
    }

    private void showAboutDialog() {
        AboutDialog.tampil(this);
    }

    private void autoUpdateCheck() {
        AutoUpdate.cek(this, new AutoUpdate.Aksi() {
            @Override public void toast(String pesan) {
                MainActivity.this.toast(pesan);
            }
            @Override public void catat(String baris) {
                appendUiLog(baris);
            }
            @Override public void kabariTersedia(String versi) {

                SharedPreferences psp = getSharedPreferences(ServerService.PREFS,
                        MODE_PRIVATE);
                if (AutoUpdate.tawarkanBaru(
                        TgBackup.amanString(psp, AutoUpdate.KEY_TAWARAN_UPDATE, ""),
                        versi)) {
                    if (!uiHidup()) {
                        return;
                    }
                    psp.edit().putString(AutoUpdate.KEY_TAWARAN_UPDATE, versi).apply();
                    MainActivity.this.toast(getString(R.string.update_tersedia, versi));
                }
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
                try {
                    ui.post(() -> pendingVersion = versi);
                } catch (Exception e) {
                    pendingVersion = versi;
                }
            }
        }, false);
    }

    private void maybeAutoBackup() {
        TgBackup.maybeAutoBackup(this, new TgBackup.BackupStartUi() {
            @Override public void toast(String s) {
                MainActivity.this.toast(s);
            }
            @Override public void catat(String s) {
                appendUiLog(s);
            }
            @Override public void jalankanUi(Runnable r) {
                ui.post(() -> {
                    if (uiHidup()) {
                        r.run();
                    }
                });
            }
        });
    }

    private void maybeShowPinLock() {

        if (!PinGate.perlukanLayarPin(this) || pinBukaJalan) {
            return;
        }
        pinBukaJalan = true;

        try {
            startActivityForResult(new Intent(this, PinActivity.class), REQ_PIN);
        } catch (Exception e) {
            pinBukaJalan = false;
        }
    }

    private boolean uiHidup() {
        return !isFinishing() && !isDestroyed();
    }

    private void setBusy(final boolean busy) {
        uiBusy = busy;
        ui.post(() -> {
            if (!uiHidup()) {
                return;
            }
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

        long now = SystemClock.elapsedRealtime();
        if (now - lastUiLogRefresh > 500) {
            lastUiLogRefresh = now;
            ui.post(refreshLoop);
        }
    }

    private void toast(String message) {
        ui.post(() -> {
            if (uiHidup()) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        });
    }
}
