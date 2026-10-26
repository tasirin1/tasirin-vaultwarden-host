package com.tasirin.vaultwardenhost;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipOutputStream;

import org.json.JSONObject;

public class SettingsActivity extends Activity {

    private static final int REQ_WRITE = 1001;
    private static final int REQ_PIN = 1005;
    private static final int REQ_RESTORE = 1002;
    private static final int REQ_IMPORT = 1003;
    private static final int REQ_SHARE_CONFIG = 1004;
    private static final String DEFAULT_PORT = ServerService.DEFAULT_PORT;

    private static final String KEY_WIZARD_DONE = "wizard_selesai";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refreshLoop = this::refreshFromService;

    private EditText dataDirInput;
    private EditText portInput;
    private EditText adminTokenInput;
    private CheckBox autoStartCheck;
    private CheckBox tgAutoCheck;
    private EditText tgTokenInput;
    private EditText tgChatInput;
    private EditText backupPassInput;
    private EditText binShaInput;
    private EditText pinInput;
    private CheckBox pinEnabledCheck;

    private static final java.util.concurrent.ExecutorService pinExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "vw-pin-hash");
                t.setDaemon(true);
                return t;
            });
    private volatile int pinSeq;
    private volatile java.util.concurrent.Future<?> pinPending;

    private Runnable pinTunda = null;

    private boolean pinDialogBoleh = true;

    private String pinGantiKandidat = null;

    private volatile boolean pinAktifJalan = false;

    private final Object kunciAktifPin = new Object();
    private int epAktifPin = 0;

    private boolean mulaiUlangSetelahIzin = false;

    private static final String STATE_MULAI_ULANG_IZIN = "mulai_ulang_izin";
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
    private android.widget.ScrollView settingsScroll;
    private LinearLayout batteryRow;
    private Button batteryBtn;
    private Button copyUrlBtn;
    private Button exportCfgBtn;
    private Button importCfgBtn;

    private volatile File exportPlainTertunda = null;

    static final long EXPORT_PLAIN_TTL_MS = 2L * 60 * 1000;

    static final long RESTORE_TG_TTL_MS = 60L * 60 * 1000;

    static final String PREFIX_ENCTMP = "app-config-enctmp-";

    static boolean sisaEnkripTmp(String nama) {
        return nama != null && nama.startsWith(PREFIX_ENCTMP) && nama.endsWith(".json");
    }

    private static final java.util.Map<String, Integer> GAGAL_IMPOR_DIGEST =
            new java.util.HashMap<>();

    static String sidikBerkas(java.io.File f) {
        try {
            java.security.MessageDigest md = Util.mdSha256();
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            byte[] d = md.digest();
            return Util.heks(d);
        } catch (Exception e) {

            try {
                if (f != null && f.isFile()) {
                    return "baca-gagal-" + f.getAbsolutePath().hashCode()
                            + "-" + f.length();
                }
            } catch (Exception ignored) {
            }
            return "";
        }
    }

    static boolean imporDiblokir(int gagal) {
        return gagal >= PinCrypto.MAX_GAGAL;
    }

    static boolean startDitahanSaatTugasData(boolean tugasUi, boolean tugasData) {
        return tugasUi || tugasData;
    }

    static synchronized int ambilGagalImpor(String sidik) {
        try {
            Integer v = GAGAL_IMPOR_DIGEST.get(sidik == null ? "" : sidik);
            return v == null ? 0 : v;
        } catch (Exception e) {
            return 0;
        }
    }

    private static final int MAKS_SIDIK_IMPOR = 128;

    static synchronized void simpanGagalImpor(String sidik, int gagal) {
        try {
            String k = sidik == null ? "" : sidik;
            if (gagal <= 0) {
                GAGAL_IMPOR_DIGEST.remove(k);
            } else if (GAGAL_IMPOR_DIGEST.containsKey(k)
                    || GAGAL_IMPOR_DIGEST.size() < MAKS_SIDIK_IMPOR) {
                GAGAL_IMPOR_DIGEST.put(k, gagal);
            }
        } catch (Exception ignored) {
        }
    }

    private volatile long exportPlainPada = 0;
    private Button installCertBtn;
    private Button shareCaBtn;
    private Button resetCertBtn;
    private Button updateBtn;
    private Button revertBtn;
    private Button updateWvBtn;
    private Button pilihBinBtn;
    private Button pilihWvBtn;
    private Button backupDbBtn;
    private Button restoreDbBtn;
    private Button backupTgBtn;
    private Button aboutBtn;
    private Button startStopBawah;
    private Button copyAdminBtn;
    private Button copyTgBtn;
    private Button showAdminBtn;
    private Button showTgBtn;
    private Button showPassBtn;
    private Button randomAdminBtn;
    private Button copyLoopbackBtn;
    private TextView adminStatus;
    private TextView tgStatus;
    private TextView adminError;
    private TextView tokenError;
    private TextView chatError;
    private Button certInfoToggle;
    private TextView certHintView;
    private TextView httpsBadge;
    private TextView dataDirError;
    private TextView portError;
    private TextView labelDataDir;
    private TextView labelPort;
    private TextView labelAdmin;
    private ProgressBar unduhBar;
    private android.content.res.ColorStateList warnaLabelBawaan;

    private static final String KEY_SEC_SERVER = "sec_buka_server";
    private static final String KEY_SEC_KEAMANAN = "sec_buka_keamanan";
    private static final String KEY_SEC_RAWAT = "sec_buka_rawat";
    private static final String KEY_SEC_TELEGRAM = "sec_buka_telegram";
    private static final String KEY_SEC_LOG = "sec_buka_log";

    private static final java.security.SecureRandom ACAK_DADU =
            new java.security.SecureRandom();

    private String bundledVersion = "?";
    private String bundledRaw = null;
    private String appVersion = "";
    private String lastShownStatus = "";
    private String lastShownVersion = "";
    private String lastVersiApp = "\0";
    private String lastVersiBin = "\0";
    private String lastVersiBundled = "\0";
    private String versiCache = "";
    private String lastShownNet = "";

    private String cacheUrlSet = "";
    private long cacheWaktuSet = 0;

    private String teksBerjalan = "";
    private String teksBerhenti = "";
    private String teksMulai = "";
    private String teksStop = "";
    private String teksSibuk = "";
    private String teksHttpsAktif = "";
    private String teksHttpsKosong = "";
    private String lastStartStopText = "";
    private String lastBusyText = "";
    private String lastShownBadge = "";
    private String lastUpdHint = "";
    private boolean lastUpdVisible = false;

    private boolean inputStartValid = true;
    private String memoCocokA = "\0";
    private String memoCocokB = "\0";
    private boolean memoCocokHasil = false;
    private String memoCocokA2 = "\0";
    private String memoCocokB2 = "\0";
    private boolean memoCocokHasil2 = false;

    private int tickCacheUbah = 0;
    private volatile boolean paksaCacheUbah = true;
    private String cacheUbahD = "";
    private String cacheUbahP = "";
    private String cacheUbahA = "";

    private String lastStatusAdmin = "";
    private String lastStatusBot = "";

    private String msgFolderSalah = "";
    private String msgPortSalah = "";
    private String msgAdminSalah = "";
    private String msgTokenSalah = "";
    private String msgChatSalah = "";

    private final java.util.Map<TextView, Boolean> labelKotorCache =
            new java.util.IdentityHashMap<>();
    private boolean refreshActive = true;
    private volatile boolean uiBusy = false;

    private final AtomicBoolean tugasBerjalan = new AtomicBoolean(false);

    private boolean pinCentangProgram = false;
    private long lastWvCheck = 0;
    private String wvLine = "";
    private long lastDbCheck = 0;
    private String dbLine = "";

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

    private final AtomicBoolean heavyRunning = new AtomicBoolean(false);
    private long lastSysCheck = 0;
    private String sysLine = "";
    private long lastCertCheck = 0;
    private String certLine = "";
    private long lastUiLogRefresh = 0;
    private static final long BATTERY_CHECK_MS = 30_000;
    private long lastBatteryCheck = 0;
    private boolean needBatteryCached = false;

    private Boolean lastBatteryTampil = null;

    private boolean pinBukaJalan = false;

    static void catatPinDibuka() {
        if (!PinGate.dalamGraceBersama()) {
            PinGate.bukaKunciBersama();
        }
    }

    @Override

    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {

        setTheme(R.style.Theme_TasirinVaultwardenHost);
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mulaiUlangSetelahIzin = savedInstanceState.getBoolean(
                    STATE_MULAI_ULANG_IZIN, false);
        }
        TgBackup.healkanStringPrefs(this);
        TgBackup.migrateAutoPref(this);

        sapuCacheLatar();

        LogActivity.bersihkanClipBasiJikaAda(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_settings);

        muatTampilan();
        pasangAksi();
        SharedPreferences sp = muatPreferensi();
        pasangSeksi();
        pasangListener();
        mulaiRefresh(sp);

    }

    private void muatTampilan() {
        dataDirInput = findViewById(R.id.dataDir);
        portInput = findViewById(R.id.port);
        adminTokenInput = findViewById(R.id.adminToken);
        autoStartCheck = findViewById(R.id.autoStart);
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
        settingsScroll = findViewById(R.id.settingsScroll);
        batteryRow = findViewById(R.id.batteryRow);

        updateBtn = findViewById(R.id.update);
        revertBtn = findViewById(R.id.revert);
        updateWvBtn = findViewById(R.id.updateWv);
        pilihBinBtn = findViewById(R.id.pilihBin);
        pilihWvBtn = findViewById(R.id.pilihWv);
        if (pilihBinBtn != null) {
            pilihBinBtn.setOnClickListener(v -> pilihVersiDialog(true));
        }
        if (pilihWvBtn != null) {
            pilihWvBtn.setOnClickListener(v -> pilihVersiDialog(false));
        }
        backupDbBtn = findViewById(R.id.backupDb);
        restoreDbBtn = findViewById(R.id.restoreDb);
        batteryBtn = findViewById(R.id.batteryBtn);
        backupTgBtn = findViewById(R.id.backupTg);
        restoreTgBtn = findViewById(R.id.restoreTg);
        copyUrlBtn = findViewById(R.id.copyUrl);
        exportCfgBtn = findViewById(R.id.exportCfg);
        importCfgBtn = findViewById(R.id.importCfg);
        installCertBtn = findViewById(R.id.installCert);
        showAdminBtn = findViewById(R.id.showAdmin);
        showTgBtn = findViewById(R.id.showTg);
        showPassBtn = findViewById(R.id.showPass);
        aboutBtn = findViewById(R.id.aboutBtn);
        startStopBawah = findViewById(R.id.startStopBawah);
        httpsBadge = findViewById(R.id.httpsBadge);
        dataDirError = findViewById(R.id.dataDirError);
        portError = findViewById(R.id.portError);
        labelDataDir = findViewById(R.id.labelDataDir);
        labelPort = findViewById(R.id.labelPort);
        labelAdmin = findViewById(R.id.labelAdmin);
        unduhBar = findViewById(R.id.unduhBar);
        warnaLabelBawaan = labelDataDir.getTextColors();
        teksBerjalan = getString(R.string.running);
        teksBerhenti = getString(R.string.stopped);
        msgFolderSalah = getString(R.string.folder_error);
        msgPortSalah = getString(R.string.port_error);
        msgAdminSalah = getString(R.string.admin_error);
        msgTokenSalah = getString(R.string.token_error);
        msgChatSalah = getString(R.string.chat_error);
        teksMulai = getString(R.string.start);
        teksStop = getString(R.string.stop);
        teksSibuk = getString(R.string.busy_work);
        teksHttpsAktif = getString(R.string.https_badge_on);
        teksHttpsKosong = getString(R.string.https_badge_none);
        randomAdminBtn = findViewById(R.id.randomAdmin);
        copyLoopbackBtn = findViewById(R.id.copyLoopback);
        copyAdminBtn = findViewById(R.id.copyAdmin);
        copyTgBtn = findViewById(R.id.copyTg);
        adminStatus = findViewById(R.id.adminStatus);
        tgStatus = findViewById(R.id.tgStatus);
        adminError = findViewById(R.id.adminError);
        tokenError = findViewById(R.id.tokenError);
        chatError = findViewById(R.id.chatError);
        awaliGalat(adminError, R.string.admin_error);
        awaliGalat(tokenError, R.string.token_error);
        awaliGalat(chatError, R.string.chat_error);
        certInfoToggle = findViewById(R.id.certInfoToggle);
        certHintView = findViewById(R.id.certHint);
    }

    private void pasangAksi() {
        startStopBawah.setOnClickListener(v -> aksiStartStop());
        updateBtn.setOnClickListener(v -> runBusy(this::checkForUpdate));
        revertBtn.setOnClickListener(v -> confirm(getString(R.string.reset_biner_judul),
                getString(R.string.reset_biner_pesan),
                () -> runBusy(this::revertToBundled)));
        installCertBtn.setOnClickListener(v -> installCertificate());
        shareCaBtn = findViewById(R.id.shareCa);
        if (shareCaBtn != null) {
            shareCaBtn.setOnClickListener(v -> bagikanCa());
        }
        resetCertBtn = findViewById(R.id.resetCert);
        if (resetCertBtn != null) {
            resetCertBtn.setOnClickListener(v -> confirm(getString(R.string.reset_sert_judul),
                    getString(R.string.reset_sert_pesan),
                    () -> runBusy(this::resetSertifikat)));
        }
        updateWvBtn.setOnClickListener(v -> runWebVaultUpdate(true));
        backupDbBtn.setOnClickListener(v -> {

            String dir = dataDirInput.getText() == null ? ""
                    : dataDirInput.getText().toString();
            runBusy(() -> backupDatabase(dir));
        });
        restoreDbBtn.setOnClickListener(v -> pickRestoreFile());
        batteryBtn.setOnClickListener(v -> requestBatteryExemption());
        backupTgBtn.setOnClickListener(v -> runBusy(() -> {
            try {
                String msg = TgBackup.backupNow(SettingsActivity.this);
                toast(msg);
                appendUiLog("[tg] " + msg);
            } catch (Exception e) {
                String ramah = TgBackup.pesanGalatBackup(e);
                toast(ramah);
                appendUiLog("[tg] " + ramah + " (" + e + ")");
            }
        }));
        ImageButton settingsBackBtn = findViewById(R.id.settingsBack);
        if (settingsBackBtn != null) {
            settingsBackBtn.setOnClickListener(v -> finish());
        }
        logOpenBtn.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        restoreTgBtn.setOnClickListener(v -> restoreFromTelegram());
        copyUrlBtn.setOnClickListener(v -> copyLocalUrl());
        if (netInfoView != null) {
            netInfoView.setOnClickListener(v -> copyLocalUrl());
        }
        copyLoopbackBtn.setOnClickListener(v -> salinTeks("https://127.0.0.1:"
                + portEfektifUntukSalin(), getString(R.string.url_lokal_disalin)));
        randomAdminBtn.setOnClickListener(v -> {
            adminTokenInput.setText(buatTokenAcak(ACAK_DADU));
            toast(getString(R.string.acak_dibuat));
        });
        exportCfgBtn.setOnClickListener(v -> mintaExport());
        importCfgBtn.setOnClickListener(v -> pickImportFile());
        showAdminBtn.setOnClickListener(v -> togglePassword(adminTokenInput, showAdminBtn));
        showTgBtn.setOnClickListener(v -> togglePassword(tgTokenInput, showTgBtn));
        showPassBtn.setOnClickListener(v -> togglePassword(backupPassInput, showPassBtn));
        if (copyAdminBtn != null) {
            copyAdminBtn.setOnClickListener(v -> salinTeks(adminTokenInput.getText().toString(),
                    getString(R.string.label_admin_token)));
        }
        if (copyTgBtn != null) {
            copyTgBtn.setOnClickListener(v -> salinTeks(tgTokenInput.getText().toString(),
                    getString(R.string.label_bot_token)));
        }
        if (certInfoToggle != null) {
            certInfoToggle.setOnClickListener(v -> {
                boolean buka = certHintView != null
                        && certHintView.getVisibility() != View.VISIBLE;
                if (certHintView != null) {
                    certHintView.setVisibility(buka ? View.VISIBLE : View.GONE);
                }
                certInfoToggle.setText(getString(buka
                        ? R.string.cert_info_tutup : R.string.cert_info_buka));
            });
        }
        aboutBtn.setOnClickListener(v -> showAboutDialog());
    }

    private SharedPreferences muatPreferensi() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        dataDirInput.setText(TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar()));
        portInput.setText(ServerService.effectivePort(sp));
        adminTokenInput.setText(TgBackup.amanString(sp, ServerService.KEY_ADMIN_TOKEN, ""));
        autoStartCheck.setChecked(TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_START, false));
        sp.edit().putBoolean(ServerService.KEY_HTTPS, true).apply();
        tgTokenInput.setText(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
        tgChatInput.setText(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, ""));
        tgAutoCheck.setChecked(TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false));
        autoUpdateCb.setChecked(TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_UPDATE, false));
        autoUpdateWvCb.setChecked(TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_UPDATE_WV, false));
        autoRestartCb.setChecked(TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_RESTART_UPDATE, false));
        backupPassInput.setText(TgBackup.amanString(sp, TgBackup.KEY_TG_PASS, ""));
        binShaInput.setText(TgBackup.amanString(sp, ServerService.KEY_BIN_SHA, ""));

        try {
            final android.content.Context appPin = getApplicationContext();
            Util.jalankanBg(() -> PinGate.kuatkanHashDini(appPin));
        } catch (Exception ignored) {
        }
        bersihkanPin(pinInput);
        pinEnabledCheck.setChecked(TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false));
        return sp;
    }

    private void pasangSeksi() {
        pasangSeksi(R.id.headerServer, R.id.bodyServer, R.id.chevronServer, KEY_SEC_SERVER);
        pasangSeksi(R.id.headerKeamanan, R.id.bodyKeamanan, R.id.chevronKeamanan, KEY_SEC_KEAMANAN);
        pasangSeksi(R.id.headerRawat, R.id.bodyRawat, R.id.chevronRawat, KEY_SEC_RAWAT);
        pasangSeksi(R.id.headerTelegram, R.id.bodyTelegram, R.id.chevronTelegram, KEY_SEC_TELEGRAM);
        pasangSeksi(R.id.headerLog, R.id.bodyLog, R.id.chevronLog, KEY_SEC_LOG);
        pasangChip(R.id.navServer, R.id.headerServer, R.id.bodyServer, R.id.chevronServer, KEY_SEC_SERVER);
        pasangChip(R.id.navKeamanan, R.id.headerKeamanan, R.id.bodyKeamanan, R.id.chevronKeamanan,
                KEY_SEC_KEAMANAN);
        pasangChip(R.id.navRawat, R.id.headerRawat, R.id.bodyRawat, R.id.chevronRawat, KEY_SEC_RAWAT);
        pasangChip(R.id.navTelegram, R.id.headerTelegram, R.id.bodyTelegram, R.id.chevronTelegram,
                KEY_SEC_TELEGRAM);
        pasangChip(R.id.navLog, R.id.headerLog, R.id.bodyLog, R.id.chevronLog, KEY_SEC_LOG);
        segarkanLabelVersi();
    }

    private void pasangListener() {
        autoStartCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_AUTO_START, checked).apply());
        tgAutoCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            SharedPreferences spTg = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);

            boolean lama = TgBackup.amanBoolean(spTg, TgBackup.KEY_TG_AUTO, false);
            spTg.edit().putBoolean(TgBackup.KEY_TG_AUTO, checked).apply();
            TgBackup.schedule(SettingsActivity.this, checked);
            if (lama != checked) {
                toast(getString(checked ? R.string.backup_auto_aktif : R.string.backup_auto_mati));
            }
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

        tgTokenInput.addTextChangedListener(onTextChanged(this::scheduleBotDebounced));
        tgChatInput.addTextChangedListener(onTextChanged(this::scheduleBotDebounced));
        backupPassInput.addTextChangedListener(new SimpleTextWatcher(TgBackup.KEY_TG_PASS));
        binShaInput.addTextChangedListener(new SimpleTextWatcher(ServerService.KEY_BIN_SHA));
        dataDirInput.addTextChangedListener(onTextChanged(this::validasiInline));
        portInput.addTextChangedListener(onTextChanged(this::validasiInline));
        adminTokenInput.addTextChangedListener(onTextChanged(this::validasiInline));
        tgTokenInput.addTextChangedListener(onTextChanged(this::validasiInline));
        tgChatInput.addTextChangedListener(onTextChanged(this::validasiInline));
        validasiInline();
        pinInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                pinSeq++;

                if (pinTunda != null) {
                    ui.removeCallbacks(pinTunda);
                    pinTunda = null;
                }
                java.util.concurrent.Future<?> basi = pinPending;
                if (basi != null && !basi.isDone()) {
                    basi.cancel(false);
                }

                if (s.length() < 4) {

                    return;
                }
                final String pin = s.toString();
                final int seq = pinSeq;
                pinTunda = () -> {

                    pinTunda = null;
                    if (seq != pinSeq) {
                        return;
                    }

                    final boolean pinAktif = pinEnabledCheck != null
                            && pinEnabledCheck.isChecked();
                    final boolean ganti = perluKonfirmasiGantiPin(pinAktif, adaHashPinTersimpan());
                    if (!ganti) {
                        tulisHashPin(pin, seq, pinAktif, false);
                        return;
                    }

                    if (pin.equals(pinGantiKandidat)) {
                        return;
                    }
                    pinGantiKandidat = pin;
                    if (!bolehDialogPin(pinDialogBoleh, isFinishing(), isDestroyed())) {

                        pinGantiKandidat = null;
                        return;
                    }

                    DialogAman.tampil(SettingsActivity.this,
                            new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle(getString(R.string.pin_ganti_judul))
                            .setMessage(getString(R.string.pin_ganti_pesan))
                            .setPositiveButton(android.R.string.ok,
                                    (d, w) -> tulisHashPin(pin, seq, true, true))
                            .setNegativeButton(getString(R.string.batal), (d, w) -> {
                                pinGantiKandidat = null;
                                bersihkanPin(pinInput);
                            })
                            .create());
                };
                ui.postDelayed(pinTunda, 800);
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        pinEnabledCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            if (pinCentangProgram) {
                return;
            }
            if (pinAktifJalan) {

                pinCentangProgram = true;
                b.setChecked(true);
                pinCentangProgram = false;
                return;
            }
            if (!checked) {

                pinCentangProgram = true;
                b.setChecked(true);
                pinCentangProgram = false;
                confirm(getString(R.string.pin_off_judul), getString(R.string.pin_off_pesan),
                        () -> {

                            synchronized (kunciAktifPin) {
                                epAktifPin++;
                                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                                        .edit().putBoolean(PinGate.KEY_PIN_ON, false).apply();
                            }
                            pinCentangProgram = true;
                            b.setChecked(false);
                            pinCentangProgram = false;
                        });
                return;
            }
            String fieldPin = pinInput.getText() == null ? ""
                    : pinInput.getText().toString();
            if (fieldPin.length() < 4) {

                toast(getString(R.string.pin_min));
                pinCentangProgram = true;
                b.setChecked(false);
                pinCentangProgram = false;
                return;
            }

            if (pinTunda != null) {
                ui.removeCallbacks(pinTunda);
                pinTunda = null;
            }
            java.util.concurrent.Future<?> basiAktif = pinPending;
            if (basiAktif != null && !basiAktif.isDone()) {
                basiAktif.cancel(false);
            }

            pinSeq++;

            pinGantiKandidat = null;
            final String pinBaru = fieldPin;
            final int epAktif;
            synchronized (kunciAktifPin) {
                epAktif = ++epAktifPin;
            }
            pinAktifJalan = true;
            toast(getString(R.string.pin_diproses));
            boolean masuk = Util.jalankanPin(() -> {
                String hasil;
                try {
                    hasil = PinCrypto.hash(pinBaru);
                } catch (Exception e) {
                    hasil = null;
                }
                if (hasil == null) {
                    pinAktifJalan = false;
                    ui.post(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        pinCentangProgram = true;
                        pinEnabledCheck.setChecked(false);
                        pinCentangProgram = false;
                        toast(getString(R.string.pin_proses_gagal));
                    });
                    return;
                }

                boolean diterapkan;
                synchronized (kunciAktifPin) {
                    diterapkan = aktivasiPinMasihBerlaku(epAktif, epAktifPin);
                    if (diterapkan) {
                        getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                                .edit().putString(PinGate.KEY_PIN_HASH, hasil)
                                .putBoolean(PinGate.KEY_PIN_ON, true).apply();
                    }
                }
                pinAktifJalan = false;
                if (!diterapkan) {

                    ui.post(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        pinCentangProgram = true;
                        pinEnabledCheck.setChecked(false);
                        pinCentangProgram = false;
                    });
                    return;
                }
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    pinCentangProgram = true;
                    pinEnabledCheck.setChecked(true);
                    pinCentangProgram = false;
                    toast(getString(R.string.pin_aktif));
                });
            });

            if (!masuk) {
                pinAktifJalan = false;
                pinCentangProgram = true;
                pinEnabledCheck.setChecked(false);
                pinCentangProgram = false;
                toast(getString(R.string.pin_proses_gagal));
            }
        });
    }

    private void mulaiRefresh(SharedPreferences sp) {
        StoragePerm.mintaIzinBilaPerlu(this, REQ_WRITE);

        bundledVersion = Updater.readBundledVersionLabel(this);
        bundledRaw = Updater.readBundledVersionRaw(this);
        try {
            appVersion = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        ui.post(refreshLoop);

        Util.jalankanLama(this::autoUpdateCheck);

        TgBackup.schedule(this, TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false));

        TgBot.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sapuCacheLatar();
        refreshActive = true;
        paksaCacheUbah = true;
        ui.removeCallbacks(refreshLoop);
        ui.post(refreshLoop);

        maybeShowPinLock();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(STATE_MULAI_ULANG_IZIN, mulaiUlangSetelahIzin);
    }

    @Override
    protected void onPause() {
        super.onPause();
        siramTulisTertunda();

        simpanPengaturanValid();
        refreshActive = false;
        ui.removeCallbacks(refreshLoop);
    }

    private void simpanPengaturanValid() {
        if (TgBackup.restoreJalan()) {
            return;
        }
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        SharedPreferences.Editor ed = null;
        String dataDir = dataDirInput.getText().toString();
        if (galatFolder(dataDir, msgFolderSalah) == null) {
            ed = tulisBilaBeda(ed, sp, ServerService.KEY_DATA_DIR,
                    ServerService.amankanDataDir(dataDir));
        }
        String port = portInput.getText().toString();
        if (galatPort(port, msgPortSalah) == null) {
            String p = port.trim();
            ed = tulisBilaBeda(ed, sp, ServerService.KEY_PORT,
                    p.isEmpty() ? DEFAULT_PORT : p);
        }
        String admin = adminTokenInput.getText().toString();
        if (galatAdmin(admin, msgAdminSalah) == null) {
            ed = tulisBilaBeda(ed, sp, ServerService.KEY_ADMIN_TOKEN, admin.trim());
        }
        if (ed != null) {
            ed.apply();
            paksaCacheUbah = true;
        }
    }

    private SharedPreferences.Editor tulisBilaBeda(SharedPreferences.Editor ed,
            SharedPreferences sp, String kunci, String nilai) {
        if (nilai.equals(TgBackup.amanString(sp, kunci, ""))) {
            return ed;
        }
        if (ed == null) {
            ed = sp.edit();
        }
        ed.putString(kunci, nilai);
        return ed;
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

    private void saveAndStart() {
        mulaiUlangSetelahIzin = false;

        if (startDitahanSaatTugasData(tugasBerjalan.get(), TgBackup.tugasDataJalan())) {
            toast(getString(R.string.masih_bekerja));
            appendUiLog("[app] Start dibatalkan: tugas backup/restore masih berjalan.");
            return;
        }
        String dataDir = dataDirInput.getText().toString().trim();
        String port = portInput.getText().toString().trim();
        String adminToken = adminTokenInput.getText().toString().trim();

        if (galatAdmin(adminToken, msgAdminSalah) != null) {
            toast(msgAdminSalah);
            appendUiLog("[app] Admin token terlalu pendek - Start dibatalkan.");
            adminTokenInput.requestFocus();
            return;
        }

        String dataDirEfektif = ServerService.amankanDataDir(dataDir);
        if ((!ServerService.dataDirAman(dataDir) || !ServerService.dataDirKanonisAman(dataDir))
                && !TextUtils.isEmpty(dataDir)) {
            toast(getString(R.string.folder_bawaan));
            appendUiLog("[app] Folder data tidak valid, pakai bawaan: " + ServerService.dataDirBawaanSegar());
        }

        if (!StoragePerm.siapStart(this, dataDirEfektif, REQ_WRITE)) {

            mulaiUlangSetelahIzin = true;
            appendUiLog("[app] Start dibatalkan: izin penyimpanan belum diberikan.");
            return;
        }

        String portEfektif = TextUtils.isEmpty(port) ? DEFAULT_PORT : port;
        int portNum = -1;
        try {
            portNum = Integer.parseInt(portEfektif);
        } catch (Exception ignored) {
        }
        if (portNum < 1 || portNum > 65535) {
            toast(getString(R.string.port_angka));
            appendUiLog("[app] Port tidak valid: '" + port + "' - Start dibatalkan.");
            return;
        }
        if (portNum < 1024) {

            portEfektif = ServerService.normalisasiPort(portEfektif);
            portNum = Integer.parseInt(portEfektif);
            portInput.setText(portEfektif);
            validasiInline();
            toast(getString(R.string.port_kecil_bawaan, portEfektif));
            appendUiLog("[app] Port privileged '" + port + "' - pakai default "
                    + portEfektif + ".");
        }

        SharedPreferences.Editor ed = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit();
        ed.putString(ServerService.KEY_DATA_DIR, dataDirEfektif);
        ed.putString(ServerService.KEY_PORT, portEfektif);
        ed.putString(ServerService.KEY_ADMIN_TOKEN, adminToken);
        ed.apply();
        paksaCacheUbah = true;

        final int portCekPort = portNum;
        final String dirCekPort = dataDirEfektif;
        boolean masukCek = Util.jalankanBg(() -> {
            ServerService.bereskanServerYatim();
            final boolean portSibuk =
                    portCekPort > 0 && ServerService.isPortBusy(portCekPort);

            final boolean butuhRoot = portSibuk && ServerService.portButuhRoot(portCekPort);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (portSibuk) {
                    if (butuhRoot) {
                        DialogAman.tampil(this, new AlertDialog.Builder(this)
                                .setTitle(getString(R.string.port_root_judul, portCekPort))
                                .setMessage(getString(R.string.port_root_pesan_setelan, portCekPort))
                                .setPositiveButton(R.string.ok, null)
                                .create());
                        return;
                    }
                    DialogAman.tampil(this, new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.port_dipakai_judul, portCekPort))
                            .setMessage(getString(R.string.port_dipakai_pesan_setelan, portCekPort))
                            .setPositiveButton(R.string.ok, null)
                            .create());
                    return;
                }

                if (!Updater.webVaultReady(this, dirCekPort)) {

                    DialogAman.tampil(this, new AlertDialog.Builder(this)
                            .setTitle(R.string.wv_belum_judul)
                            .setMessage(R.string.wv_belum_pesan)
                            .setPositiveButton(R.string.wv_unduh_mulai, (d, w) -> {

                                final android.content.Context appCtx = getApplicationContext();
                                runBusy(() -> {
                                    boolean gagal = false;
                                    try {
                                        String msg = Updater.updateWebVault(appCtx);
                                        appendUiLog("[app] " + msg);
                                    } catch (Exception e) {
                                        gagal = true;
                                        toast(getString(R.string.wv_unduh_gagal_galat,
                                                String.valueOf(e.getMessage())));
                                        appendUiLog("[app] Web-vault download failed: " + e);
                                    }
                                    if (!gagal && ServerService.start(appCtx)) {

                                        maybeAutoBackup();
                                    }
                                });
                            })
                            .setNegativeButton(R.string.wv_mulai_tanpa, (d, w) -> {
                                if (ServerService.start(this)) {
                                    maybeAutoBackup();
                                }
                            })
                            .create());
                    return;
                }
                if (ServerService.start(this)) {
                    maybeAutoBackup();
                }
            });
        });
        if (!masukCek) {

            toast(getString(R.string.pin_sibuk));
            appendUiLog("[app] Cek port ditolak: antrean tugas penuh.");
        }
    }

    private void refreshFromService() {

        ui.removeCallbacks(refreshLoop);

        if (isFinishing() || isDestroyed()) {
            return;
        }

        if (tampilkanSibukUnduhan()) {
            return;
        }

        lastBusyText = "";

        boolean running = ServerService.running;
        perbaruiStatusServer(running);
        perbaruiPenandaUbah(running);
        perbaruiPetunjukUpdate(running);
        String version = teksVersiPerangkat();
        perbaruiKartuInfo(running, version);
        perbaruiUrlLokal();
        perbaruiBaterai();

        if (refreshActive) {
            ui.postDelayed(refreshLoop, 1000);
        }
    }

    private boolean tampilkanSibukUnduhan() {
        if (!uiBusy) {
            return false;
        }
        String dl = Updater.downloadStatus;
        String sibuk = dl.isEmpty() ? teksSibuk : dl;
        if (!sibuk.equals(lastBusyText)) {
            lastBusyText = sibuk;
            statusView.setText(sibuk);
            statusView.setBackgroundResource(R.drawable.bg_status_busy);
        }
        lastShownStatus = "";
        int persen = Updater.persenUnduhan(dl);

        unduhBar.setVisibility(View.VISIBLE);
        if (persen >= 0) {
            unduhBar.setIndeterminate(false);
            unduhBar.setProgress(persen);
        } else {
            unduhBar.setIndeterminate(true);
        }
        if (refreshActive) {
            ui.postDelayed(refreshLoop, 1500);
        }
        return true;
    }

    private void perbaruiStatusServer(boolean running) {
        perbaruiEnabledStartStop();
        String statusText = running ? teksBerjalan : teksBerhenti;

        String key = running ? "on" : "off";
        if (!key.equals(lastShownStatus)) {
            statusView.setText(statusText);
            statusView.setBackgroundResource(running
                    ? R.drawable.bg_status_running : R.drawable.bg_status_stopped);
            lastShownStatus = key;
        }
        unduhBar.setVisibility(View.GONE);
        unduhBar.setIndeterminate(false);
        String btnText = running ? teksStop : teksMulai;
        if (!btnText.equals(lastStartStopText)) {
            lastStartStopText = btnText;
            startStopBawah.setText(btnText);
            startStopBawah.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    running ? R.drawable.ic_stop : R.drawable.ic_play, 0, 0, 0);

            startStopBawah.setBackgroundResource(running
                    ? R.drawable.bg_btn_stop : R.drawable.bg_btn_primary);
        }
    }

    private void perbaruiPenandaUbah(boolean running) {
        boolean changed = false;
        if (running) {
            if (paksaCacheUbah || tickCacheUbah++ % 5 == 0) {
                paksaCacheUbah = false;
                SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
                String d = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
                if (d == null || d.trim().isEmpty()) {
                    d = ServerService.dataDirBawaanSegar();
                }
                cacheUbahD = d;
                cacheUbahP = ServerService.effectivePort(sp);
                String a = TgBackup.amanString(sp, ServerService.KEY_ADMIN_TOKEN, "");
                cacheUbahA = a == null ? "" : a;
            }
            String d = cacheUbahD;
            String p = cacheUbahP;
            String a = cacheUbahA;
            String rd = ServerService.runningDataDir == null ? "" : ServerService.runningDataDir;
            String rp = ServerService.runningPort == null ? "" : ServerService.runningPort;
            String ra = ServerService.runningAdminToken == null ? "" : ServerService.runningAdminToken;

            String dRapi = d.trim();
            String pRapi = p.trim();
            String rdRapi = rd.trim();
            String rpRapi = rp.trim();
            String raRapi = ra == null ? "" : ra.trim();
            boolean bedaData = !dRapi.equals(rdRapi);
            boolean bedaPort = !pRapi.equals(rpRapi);
            boolean bedaAdmin = !ServerService.sidikTokenAdmin(a).equals(raRapi);
            changed = bedaData || bedaPort || bedaAdmin;
            tandaiLabel(labelDataDir, R.string.folder_data, bedaData);
            tandaiLabel(labelPort, R.string.port, bedaPort);
            tandaiLabel(labelAdmin, R.string.admin_token, bedaAdmin);
        } else {
            tandaiLabel(labelDataDir, R.string.folder_data, false);
            tandaiLabel(labelPort, R.string.port, false);
            tandaiLabel(labelAdmin, R.string.admin_token, false);
        }
        restartHint.setVisibility(changed ? View.VISIBLE : View.GONE);
    }

    private void perbaruiPetunjukUpdate(boolean running) {
        boolean updAvail = false;
        String pendSet = pendingVersion;
        if (pendSet != null) {
            SharedPreferences psp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String real = Updater.parseBinaryVersion(ServerService.binaryVersion);
            String raSet = real == null ? "" : real;
            boolean cocok1;
            if (raSet.equals(memoCocokA) && pendSet.equals(memoCocokB)) {
                cocok1 = memoCocokHasil;
            } else {
                cocok1 = Updater.versiCocok(real, pendSet);
                memoCocokA = raSet;
                memoCocokB = pendSet;
                memoCocokHasil = cocok1;
            }
            if (cocok1) {
                psp.edit().putString(ServerService.KEY_UPDATE_VERSION, pendSet).apply();
                pendingVersion = null;
            } else {
                String up = TgBackup.amanString(psp, ServerService.KEY_UPDATE_VERSION, "");
                String cur = real != null ? real : Updater.normVersion(up != null && !up.isEmpty()
                        ? up : bundledRaw);
                String caSet = cur == null ? "" : cur;
                boolean cocok2;
                if (caSet.equals(memoCocokA2) && pendSet.equals(memoCocokB2)) {
                    cocok2 = memoCocokHasil2;
                } else {
                    cocok2 = Updater.versiCocok(cur, pendSet);
                    memoCocokA2 = caSet;
                    memoCocokB2 = pendSet;
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
            String teksUpd = getString(R.string.update_available, pendingVersion);
            if (!teksUpd.equals(lastUpdHint)) {
                lastUpdHint = teksUpd;
                updateHint.setText(teksUpd);
            }
            if (!lastUpdVisible) {
                lastUpdVisible = true;
                updateHint.setVisibility(View.VISIBLE);
            }
        } else {
            lastUpdHint = "";
            if (lastUpdVisible) {
                lastUpdVisible = false;
                updateHint.setVisibility(View.GONE);
            }
        }
    }

    private String teksVersiPerangkat() {
        String binVerSet = ServerService.binaryVersion;
        if (binVerSet == null) {
            binVerSet = "";
        }
        String bundledSet = bundledVersion == null ? "" : bundledVersion;
        if (!appVersion.equals(lastVersiApp) || !binVerSet.equals(lastVersiBin)
                || !bundledSet.equals(lastVersiBundled)) {
            lastVersiApp = appVersion;
            lastVersiBin = binVerSet;
            lastVersiBundled = bundledSet;
            versiCache = binVerSet.isEmpty()
                    ? getString(R.string.versi_app, appVersion, bundledSet)
                    : getString(R.string.versi_app_biner, appVersion, binVerSet);
        }
        return versiCache;
    }

    private void perbaruiKartuInfo(boolean running, String version) {
        long nowHeavy = SystemClock.elapsedRealtime();
        boolean heavyDue = nowHeavy - lastDbCheck >= DB_CHECK_MS
                || nowHeavy - lastStorageCheck >= STORAGE_CHECK_MS
                || nowHeavy - lastWvCheck >= WV_CHECK_MS
                || nowHeavy - lastSysCheck >= SYS_CHECK_MS
                || nowHeavy - lastCertCheck >= CERT_CHECK_MS;
        if (heavyDue && heavyRunning.compareAndSet(false, true)) {
            boolean masuk = Util.jalankanBg(() -> {
                try {
                    dbInfoLine(true);
                    storageInfoLine(true);
                    webVaultInfoLine(true);
                    sysInfoLine(true);
                    certInfoLine(true);
                } finally {
                    heavyRunning.set(false);
                    ui.post(() -> {
                        if (refreshActive) {
                            refreshFromService();
                        }
                    });
                }
            });
            if (!masuk) {

                heavyRunning.set(false);
            }
        }
        StringBuilder fullB = new StringBuilder(version);

        boolean hitungUi = !heavyRunning.get();
        String dbInfo = dbInfoLine(hitungUi);
        if (!dbInfo.isEmpty()) {
            fullB.append("\n").append(dbInfo);
        }
        String wv = webVaultInfoLine(hitungUi);
        if (!wv.isEmpty()) {
            fullB.append("\n").append(wv);
        }
        String sys = sysInfoLine(hitungUi);
        if (!sys.isEmpty()) {
            fullB.append("\n").append(sys);
        }
        String storage = storageInfoLine(hitungUi);
        if (!storage.isEmpty()) {
            fullB.append("\n").append(storage);
        }
        String cert = certInfoLine(hitungUi);
        if (!cert.isEmpty()) {
            fullB.append("\n").append(cert);
        }
        String full = fullB.toString();
        String badge = teksBadgeHttps(running, cert, teksHttpsAktif, teksHttpsKosong);
        if (!badge.equals(lastShownBadge)) {
            lastShownBadge = badge;
            httpsBadge.setText(badge);
        }
        if (!full.equals(lastShownVersion)) {
            versionView.setText(full);
            lastShownVersion = full;
        }
    }

    private void perbaruiUrlLokal() {
        String net;
        long nowUrl = SystemClock.elapsedRealtime();
        if (!cacheUrlSet.isEmpty() && nowUrl - cacheWaktuSet < 10000) {
            net = cacheUrlSet;
        } else {
            net = ServerService.localUrl(this);
            cacheUrlSet = net;
            cacheWaktuSet = nowUrl;
        }
        if (!net.equals(lastShownNet)) {
            netInfoView.setText(net);
            lastShownNet = net;
        }
    }

    private void perbaruiBaterai() {
        long nowBattery = SystemClock.elapsedRealtime();
        if (nowBattery - lastBatteryCheck >= BATTERY_CHECK_MS) {
            lastBatteryCheck = nowBattery;
            boolean need = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
            if (need) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                need = pm == null || !pm.isIgnoringBatteryOptimizations(getPackageName());
            }
            needBatteryCached = need;
        }
        boolean tampilBaterai = needBatteryCached;
        batteryRow.setVisibility(tampilBaterai ? View.VISIBLE : View.GONE);

        if (lastBatteryTampil == null || lastBatteryTampil != tampilBaterai) {
            lastBatteryTampil = tampilBaterai;
            perbaikiFokusBaterai(tampilBaterai);
        }
    }


    private void showAboutDialog() {
        AboutDialog.tampil(this);
    }

    private void installCertificate() {
        try {

            File cert = new File(getFilesDir(), "tls/ca.pem");
            if (!cert.exists()) {
                String dataDir = dataDirInput.getText().toString().trim();
                if (TextUtils.isEmpty(dataDir)) {
                    dataDir = ServerService.dataDirBawaanSegar();
                }
                cert = new File(dataDir, "tls/ca.pem");
            }
            if (!cert.exists()) {
                toast(getString(R.string.ca_belum));
                return;
            }
            Uri uri = Uri.parse("content://" + FileShareProvider.AUTHORITY
                    + Uri.encode(cert.getAbsolutePath(), "/"));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/x-x509-ca-cert");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            toast(getString(R.string.ca_installer_gagal, String.valueOf(e.getMessage())));
        }
    }

    private void bagikanCa() {
        try {
            File cert = new File(getFilesDir(), "tls/ca.pem");
            if (!cert.exists()) {
                String dataDir = dataDirInput.getText().toString().trim();
                if (TextUtils.isEmpty(dataDir)) {
                    dataDir = ServerService.dataDirBawaanSegar();
                }
                cert = new File(dataDir, "tls/ca.pem");
            }
            if (!cert.exists()) {
                toast(getString(R.string.ca_belum));
                return;
            }
            Uri uri = Uri.parse("content://" + FileShareProvider.AUTHORITY
                    + Uri.encode(cert.getAbsolutePath(), "/"));
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/x-x509-ca-cert");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(Intent.createChooser(send,
                        getString(R.string.ca_bagikan_chooser)));
            } catch (Exception e2) {
                toast(getString(R.string.bagikan_gagal, String.valueOf(e2.getMessage())));
            }
        } catch (Exception e) {
            toast(getString(R.string.ca_bagikan_gagal, String.valueOf(e.getMessage())));
        }
    }

    private void resetSertifikat() {
        try {
            String msg = TgBackup.resetSertifikat(this);
            lastCertCheck = 0;
            certLine = "";
            toast(msg);
            appendUiLog("[app] " + msg);
        } catch (Exception e) {
            toast(getString(R.string.ca_reset_gagal, String.valueOf(e.getMessage())));
            appendUiLog("[app] Certificate reset failed: " + e.getMessage());
        }
    }

    private void autoUpdateCheck() {
        AutoUpdate.cek(this, new AutoUpdate.Aksi() {
            @Override public void toast(String pesan) {
                SettingsActivity.this.toast(pesan);
            }
            @Override public void catat(String baris) {
                appendUiLog(baris);
            }
            @Override public void kabariTersedia(String versi) {

                android.content.SharedPreferences psp = getSharedPreferences(
                        ServerService.PREFS, MODE_PRIVATE);
                if (AutoUpdate.tawarkanBaru(
                        TgBackup.amanString(psp, AutoUpdate.KEY_TAWARAN_UPDATE, ""),
                        versi)) {
                    psp.edit().putString(AutoUpdate.KEY_TAWARAN_UPDATE, versi).apply();
                    SettingsActivity.this.toast(getString(R.string.update_tersedia_singkat, versi));
                }
            }
            @Override public void tawarkanWebVault() {
                autoOfferWebVaultUpdate();
            }
            @Override public boolean webVaultSiap(String dataDir) {
                return Updater.webVaultReady(SettingsActivity.this, dataDir);
            }
            @Override public void restartServer() {
                ServerService.restart(SettingsActivity.this);
            }
        }, new AutoUpdate.AturPending() {

            @Override public void atur(String versi) {
                try {
                    ui.post(() -> pendingVersion = versi);
                } catch (Exception e) {
                    pendingVersion = versi;
                }
            }
        }, true);
    }

    private void autoOfferWebVaultUpdate() {
        try {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
            if (!Updater.webVaultReady(this, dataDir)) {
                return;
            }
            final String updated = Updater.webVaultFromVersion(this);
            String server = Updater.currentServerVersion(this);
            if (!Updater.wvBedaVersiServer(updated, server)) {
                return;
            }
            final String key = "wv_notified_" + server;
            if (TgBackup.amanBoolean(sp, key, false)) {
                return;
            }
            final String pinWvDlg = Updater.kuncianWebVault(this);
            final boolean dikunciDlg = pinWvDlg != null && !pinWvDlg.isEmpty();
            ui.post(() -> {

                if (isFinishing() || isDestroyed()) {
                    return;
                }

                sp.edit().putBoolean(key, true).apply();
                DialogAman.tampil(this, new AlertDialog.Builder(this)
                        .setTitle(R.string.wv_update_judul)
                        .setMessage(dikunciDlg
                                ? getString(R.string.wv_update_pesan_kunci,
                                        updated, server, pinWvDlg)
                                : getString(R.string.wv_update_pesan, updated, server))
                        .setPositiveButton(R.string.wv_update_restart,
                                (d, w) -> runWebVaultUpdate(true))
                        .setNeutralButton(R.string.wv_update_saja,
                                (d, w) -> runWebVaultUpdate(false))
                        .setNegativeButton(R.string.nanti, null)
                        .create());
            });
        } catch (Exception ignored) {
        }
    }

    private void runWebVaultUpdate(boolean restartAfter) {

        final android.content.Context app = getApplicationContext();
        runBusy(() -> {
            try {
                appendUiLog("[app] Mengunduh web-vault terbaru...");
                String msg = Updater.updateWebVault(app);
                toast(msg);
                appendUiLog("[app] " + msg);
                lastWvCheck = 0;
                if (restartAfter && ServerService.running && TgBot.webVaultBerubah(msg)) {
                    appendUiLog("[app] Restart server agar web vault berlaku...");
                    ServerService.restart(app);
                }
            } catch (Exception e) {
                toast(getString(R.string.wv_update_gagal, String.valueOf(e.getMessage())));
                appendUiLog("[app] Web-vault update failed: " + e);
            }
        });
    }

    private void perbaikiFokusBaterai(boolean barisTampil) {
        if (autoStartCheck == null || autoUpdateCb == null) {
            return;
        }
        if (barisTampil && batteryBtn != null) {
            autoStartCheck.setNextFocusDownId(R.id.batteryBtn);
            autoUpdateCb.setNextFocusUpId(R.id.batteryBtn);
        } else {
            autoStartCheck.setNextFocusDownId(R.id.autoUpdate);
            autoUpdateCb.setNextFocusUpId(R.id.autoStart);
        }
    }

    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast(getString(R.string.baterai_android6));
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast(getString(R.string.baterai_sudah));
            return;
        }
        try {
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            toast(getString(R.string.setelan_buka_gagal, String.valueOf(e.getMessage())));
        }
    }

    private void backupDatabase(String dataDirMentah) {

        if (!TgBackup.kunciBackup()) {
            toast(getString(R.string.tugas_lain_jalan));
            appendUiLog("[app] Backup ditolak: tugas backup/restore lain sedang berjalan.");
            return;
        }
        try {
            backupDatabaseIsi(dataDirMentah);
        } finally {
            TgBackup.lepasBackup();
        }
    }

    private void backupDatabaseIsi(String dataDirMentah) {
        try {
            String dataDir = dataDirMentah == null ? "" : dataDirMentah.trim();
            if (TextUtils.isEmpty(dataDir)) {
                dataDir = ServerService.dataDirBawaanSegar();
            }
            if (!ServerService.dataDirAman(dataDir)
                    || !ServerService.dataDirKanonisAman(dataDir)) {
                toast(getString(R.string.folder_tidak_valid));
                appendUiLog("[app] Folder data tidak valid: '" + dataDir + "' - direset ke bawaan.");
                dataDir = ServerService.dataDirBawaanSegar();
            }

            File dbFile = new File(dataDir, "db.sqlite3");
            if (!dbFile.exists()) {
                toast(getString(R.string.db_belum_ada, dbFile.getAbsolutePath()));
                return;
            }

            TgBackup.checkpointWal(dbFile);
            long free = TgBackup.freeBytes(this, dataDir);
            if (free >= 0 && free < 50L * 1024 * 1024) {
                toast(getString(R.string.penyimpanan_hampir_penuh, TgBackup.humanBytes(free)));
                appendUiLog("[app] Peringatan storage tinggal " + TgBackup.humanBytes(free));
            }

            File backupDir = new File(dataDir, "backups");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }

            try {
                String rusakDb = TgBackup.cekIntegritasDbUlang(dbFile);
                if (TgBackup.dbSibuk(rusakDb)) {
                    toast(getString(R.string.db_sibuk_backup));
                    appendUiLog("[app] Backup dibatalkan: DB sibuk (" + rusakDb + ").");
                    return;
                }
                if (rusakDb != null) {
                    toast(getString(R.string.db_rusak_backup, rusakDb));
                    appendUiLog("[app] Backup dibatalkan: DB korup (" + rusakDb + ").");
                    return;
                }
            } catch (Exception abaikan) {

            }
            String timestamp = TgBackup.stempelUnik();
            File backup = new File(backupDir, "db-backup-" + timestamp + ".zip");

            File tmpZip = new File(backupDir, backup.getName() + ".tmp");

            String[] names = {"db.sqlite3", "db.sqlite3-wal", "db.sqlite3-shm"};
            try {
                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmpZip))) {
                    byte[] buf = new byte[64 * 1024];
                    for (String name : names) {
                        File f = new File(dataDir, name);
                        if (f.exists() && f.length() > 0) {
                            zos.putNextEntry(new ZipEntry(name));
                            try (InputStream in = new java.io.FileInputStream(f)) {
                                int n;
                                while ((n = in.read(buf)) != -1) {
                                    zos.write(buf, 0, n);
                                }
                            }
                            zos.closeEntry();
                        }
                    }
                }
                if (backup.exists() && !backup.delete()) {
                    throw new java.io.IOException(getString(R.string.galat_backup_ganti));
                }
                if (!tmpZip.renameTo(backup)) {
                    throw new java.io.IOException(getString(R.string.galat_backup_pasang));
                }
            } catch (Exception e) {
                try {
                    tmpZip.delete();
                } catch (Exception ignored) {
                }
                throw e;
            }

            File tmpIsi = TgBackup.berkasPlainSementara(
                    getCacheDir(), "isi-db", ".sqlite3");
            TgBackup.sapuTmpPlainBasi(getCacheDir(), tmpIsi.getName());
            try {
                String galatIsi = TgBackup.verifikasiIsiDbZip(backup, tmpIsi);
                if (galatIsi != null) {
                    backup.delete();
                    toast(getString(R.string.backup_rusak_dibuang, galatIsi));
                    appendUiLog("[app] Backup dibuang: isi DB korup (" + galatIsi + ").");
                    return;
                }
            } finally {
                try {
                    tmpIsi.delete();
                } catch (Exception ignored) {
                }
            }
            TgBackup.cleanupOldBackups(backupDir);
            toast(getString(R.string.backup_tersimpan, backup.getAbsolutePath()));
            appendUiLog("[app] Backup DB: " + backup.getName() + " (" + backup.length() + " bytes)");
        } catch (Exception e) {
            toast(getString(R.string.backup_gagal, String.valueOf(e.getMessage())));
            appendUiLog("[app] Backup failed: " + e);
        }
    }

    private void sapuExportEnkripLama(File backupDir) {
        try {
            File[] semua = backupDir == null ? null : backupDir.listFiles();
            if (semua == null) {
                return;
            }
            java.util.ArrayList<File> daftar = new java.util.ArrayList<>();
            for (File f : semua) {
                String n = f.getName();
                if (n.startsWith("app-config-") && n.endsWith(".json.enc")) {
                    daftar.add(f);
                }
            }

            java.util.Collections.sort(daftar,
                    (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
            for (int i = 10; i < daftar.size(); i++) {
                try {
                    if (!daftar.get(i).delete()) {
                        appendUiLog("[app] Gagal hapus export lama: "
                                + daftar.get(i).getName());
                    }
                } catch (Exception e) {
                    appendUiLog("[app] Gagal hapus export lama: " + e);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void pickRestoreFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_RESTORE);
        } catch (Exception e) {
            toast(getString(R.string.pemilih_berkas_gagal, String.valueOf(e.getMessage())));
        }
    }

    @Override

    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RESTORE && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            String dir = dataDirInput.getText() == null ? ""
                    : dataDirInput.getText().toString().trim();
            if (TextUtils.isEmpty(dir)) {
                dir = ServerService.dataDirBawaanSegar();
            }
            final String dataDir = dir;
            confirm(getString(R.string.restore_db_judul),
                    getString(R.string.restore_db_pesan),
                    () -> runBusy(() -> restoreDatabase(uri, dataDir)));
        }
        if (requestCode == REQ_PIN) {
            pinBukaJalan = false;
            if (resultCode == RESULT_OK) {
                catatPinDibuka();
            } else {
                finish();
            }
            return;
        } else if (requestCode == REQ_SHARE_CONFIG) {

            sapuExportPlainBasi();
        } else if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            confirm(getString(R.string.impor_setelan_judul),
                    getString(R.string.impor_setelan_pesan),
                    () -> runBusy(() -> importConfig(uri)));
        }
    }

    private void restoreDatabase(Uri uri, String dataDirMentah) {

        if (!TgBackup.kunciRestore()) {
            toast(getString(R.string.tugas_lain_jalan));
            appendUiLog("[app] Restore ditolak: tugas backup/restore lain sedang berjalan.");
            return;
        }
        try {

            ServerService.kunciData();
            try {
                restoreDatabaseIsi(uri, dataDirMentah);
            } finally {
                ServerService.lepasKunciData();
            }
        } finally {
            TgBackup.lepasRestore();
        }
    }

    private void restoreDatabaseIsi(Uri uri, String dataDirMentah) {
        String dataDir = dataDirMentah == null ? "" : dataDirMentah.trim();
        if (TextUtils.isEmpty(dataDir)) {
            dataDir = ServerService.dataDirBawaanSegar();
        }
        if (!ServerService.dataDirAman(dataDir)
                || !ServerService.dataDirKanonisAman(dataDir)) {
            toast(getString(R.string.folder_tidak_valid));
            appendUiLog("[app] Folder data tidak valid: '" + dataDir + "' - direset ke bawaan.");
            dataDir = ServerService.dataDirBawaanSegar();
        }
        File dbFile = new File(dataDir, "db.sqlite3");
        File preBackup = null;

        java.util.Map<String, byte[]> tlsAsal = null;
        JSONObject zipCfg = null;
        try {

            if (!ServerService.stopAndWait(SettingsActivity.this,
                    ServerService.ANGGARAN_STOP_MIN_MS)) {
                toast(getString(R.string.restore_server_gagal_stop));
                appendUiLog("[app] Restore dibatalkan: server masih berjalan.");
                return;
            }
            if (ServerService.isProcessAlive()) {
                toast(getString(R.string.restore_server_jalan));
                appendUiLog("[app] Restore dibatalkan: server masih berjalan.");
                return;
            }

            if (!ServerService.bereskanServerYatim()) {
                toast(getString(R.string.restore_proses_lama));
                appendUiLog("[app] Restore dibatalkan: proses vaultwarden yatim masih hidup.");
                return;
            }

            TgBackup.hapusStagingRestore(new File(dataDir));
            if (dbFile.exists()) {
                File backupDir = new File(dataDir, "backups");
                if (!backupDir.exists()) {
                    backupDir.mkdirs();
                }

                TgBackup.checkpointWal(dbFile);
                String ts = TgBackup.stempelUnik() + "-pre";
                preBackup = new File(backupDir, "db-backup-" + ts + ".sqlite3");
                TgBackup.copyFile(dbFile, preBackup);
                TgBackup.cleanupOldBackups(backupDir);

                TgBackup.hapusWalShm(new File(dataDir));
            }

            boolean restored = false;
            java.util.List<String> tlsTerStaging = new java.util.ArrayList<>();
            byte[] buf = new byte[64 * 1024];
            try (InputStream raw = getContentResolver().openInputStream(uri)) {
                if (raw == null) {
                    toast(getString(R.string.restore_berkas_gagal));
                    appendUiLog("[app] Restore gagal: stream null");
                    return;
                }

                PushbackInputStream in = new PushbackInputStream(raw, 2);
                byte[] magic = new byte[2];
                int n = 0;
                while (n < 2) {
                    int r = in.read(magic, n, 2 - n);
                    if (r <= 0) {
                        break;
                    }
                    n += r;
                }
                boolean isZip = n == 2 && magic[0] == 'P' && magic[1] == 'K';
                if (isZip) {
                    in.unread(magic, 0, n);

                    File dataFolder = new File(dataDir);
                    tlsAsal = TgBackup.potretTls(new File(dataFolder, "tls"));
                    String canonBase = dataFolder.getCanonicalPath();
                    String awalanAman = canonBase + File.separator;
                    long totalUnzip = 0;
                    int jumlahEntri = 0;
                    ZipInputStream zis = new ZipInputStream(in);
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {

                        jumlahEntri++;
                        try {
                            Util.tambahUkuranUnzip(0, 0, Util.BATAS_UNZIP_RESTORE, jumlahEntri,
                                    Util.BATAS_JUMLAH_ENTRI);
                        } catch (java.io.IOException e) {
                            throw e;
                        }
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

                        if (!TgBackup.bolehTulisRestore(name)) {
                            zis.closeEntry();
                            continue;
                        }
                        if ("tls".equals(name) && !entry.isDirectory()) {

                            zis.closeEntry();
                            continue;
                        }

                        String staging = TgBackup.namaStagingRestore(name);
                        String stagingTls = TgBackup.namaStagingTls(name);
                        if (staging != null && entry.isDirectory()) {

                            zis.closeEntry();
                            continue;
                        }
                        File out = new File(dataFolder, staging != null ? staging
                                : (stagingTls != null ? stagingTls : name));

                        String kanon = out.getCanonicalPath();
                        if (!kanon.equals(canonBase) && !kanon.startsWith(awalanAman)) {
                            zis.closeEntry();
                            continue;
                        }
                        if (entry.isDirectory()) {
                            out.mkdirs();
                        } else {
                            File parent = out.getParentFile();
                            if (parent != null) {
                                parent.mkdirs();
                            }
                            try (FileOutputStream fos = new FileOutputStream(out)) {
                                int len;
                                while ((len = zis.read(buf)) != -1) {
                                    totalUnzip = Util.tambahUkuranUnzip(totalUnzip, len,
                                            Util.BATAS_UNZIP_RESTORE, jumlahEntri,
                                            Util.BATAS_JUMLAH_ENTRI);
                                    fos.write(buf, 0, len);
                                }
                                if (staging != null || stagingTls != null) {
                                    fos.flush();
                                    try {
                                        fos.getFD().sync();
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                            if (stagingTls != null) {
                                tlsTerStaging.add(name);
                            }
                        }
                        zis.closeEntry();
                        if (!entry.isDirectory() && name.equals("db.sqlite3")) {
                            restored = true;
                        }
                    }

                } else {

                    if (n <= 0) {
                        toast(getString(R.string.restore_kosong));
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
                        if (r <= 0) {
                            break;
                        }
                        off += r;
                    }
                    byte[] sqliteMagic = TgBackup.MAGIC_SQLITE;
                    boolean ok = off == head.length;
                    for (int i = 0; ok && i < sqliteMagic.length; i++) {
                        if (head[i] != sqliteMagic[i]) {
                            ok = false;
                        }
                    }
                    if (!ok) {
                        toast(getString(R.string.restore_bukan_sqlite));
                        appendUiLog("[app] Restore gagal: header SQLite tidak cocok");
                        return;
                    }
                    long salin = off;
                    File stagingDb = new File(dataDir,
                            TgBackup.namaStagingRestore("db.sqlite3"));
                    try (FileOutputStream fos = new FileOutputStream(stagingDb)) {
                        fos.write(head, 0, off);
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            salin += len;
                            if (salin > Util.BATAS_UNZIP_RESTORE) {

                                throw new java.io.IOException(getString(R.string.galat_db_besar,
                                        TgBackup.humanBytes(Util.BATAS_UNZIP_RESTORE)));
                            }
                            fos.write(buf, 0, len);
                        }
                    }
                    if (salin < 512) {
                        throw new java.io.IOException(getString(R.string.galat_db_terpotong));
                    }

                    TgBackup.hapusWalShm(new File(dataDir));
                    restored = true;
                }
            }
            if (!restored) {

                TgBackup.hapusStagingRestore(new File(dataDir));
                TgBackup.hapusStagingTls(new File(dataDir));
                TgBackup.hapusWalShm(new File(dataDir));
                TgBackup.kembalikanTls(new File(dataDir, "tls"), tlsAsal);
                toast(getString(R.string.restore_tanpa_db));
                appendUiLog("[app] Restore gagal: file zip tanpa db.sqlite3");
                return;
            }

            String galatDb = TgBackup.pasangDbRestoreAtomik(new File(dataDir));
            if (galatDb != null) {
                TgBackup.hapusStagingRestore(new File(dataDir));
                TgBackup.hapusStagingTls(new File(dataDir));
                TgBackup.kembalikanTls(new File(dataDir, "tls"), tlsAsal);
                toast(getString(R.string.restore_backup_rusak, galatDb));
                appendUiLog("[app] Restore gagal: " + galatDb + ", DB lama dipertahankan.");
                return;
            }

            TgBackup.pasangTlsRestore(new File(dataDir), tlsTerStaging);
            TgBackup.sinkronTlsKeInternal(SettingsActivity.this, new File(dataDir));
            if (zipCfg != null) {
                paksaCacheUbah = true;
                TgBackup.applyPrefsFromJson(SettingsActivity.this,
                        zipCfg.optJSONObject("prefs"));
                sanitizePortPref();

                SharedPreferences sp2 = getSharedPreferences(
                        ServerService.PREFS, MODE_PRIVATE);
                TgBackup.schedule(SettingsActivity.this,
                        TgBackup.amanBoolean(sp2, TgBackup.KEY_TG_AUTO, false));
                TgBot.schedule(SettingsActivity.this);
                appendUiLog("[app] Pengaturan dari backup ikut diterapkan.");
                uiPost(this::reloadSettingsFromPrefs);
            }
            toast(getString(R.string.restore_selesai_restart));
            appendUiLog("[app] DB direstore. Ukuran: " + dbFile.length() + " bytes");
        } catch (Exception e) {

            try {
                TgBackup.hapusStagingTls(new File(dataDir));
                TgBackup.kembalikanTls(new File(dataDir, "tls"), tlsAsal);
                TgBackup.pulihkanDbLama(new File(dataDir), dbFile, preBackup);
                appendUiLog(preBackup != null && preBackup.exists()
                        ? "[app] Restore gagal: database lama dikembalikan."
                        : "[app] Restore gagal: file parsial dibuang.");
            } catch (Exception ignored) {
            }
            toast(getString(R.string.restore_gagal, String.valueOf(e.getMessage())));
            appendUiLog("[app] Restore failed: " + e);
        }
    }

    private void restoreFromTelegram() {
        String inputDir = dataDirInput.getText().toString().trim();
        if (!TextUtils.isEmpty(inputDir)) {

            String aman = ServerService.amankanDataDir(inputDir);
            if (!ServerService.dataDirAman(inputDir) || !ServerService.dataDirKanonisAman(inputDir)) {
                toast(getString(R.string.folder_bawaan));
            }
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .putString(ServerService.KEY_DATA_DIR, aman).apply();
            paksaCacheUbah = true;
        }
        runBusy(() -> {

            File tmp = new File(getCacheDir(),
                    "vwtg-restore-" + TgBackup.stempelUnik() + ".zip");
            try {

                String name = TgBackup.downloadLastBackup(getApplicationContext(), tmp);
                final String fname = name;

                ui.post(() -> {

                    if (isFinishing() || isDestroyed()) {
                        try {
                            tmp.delete();
                        } catch (Exception ignored) {
                        }
                        return;
                    }
                    confirm(getString(R.string.restore_tg_judul),
                            getString(R.string.restore_tg_pesan, fname),
                            () -> runBusy(() -> restoreTelegramTerkonfirmasi(tmp)),

                            () -> {
                                try {
                                    tmp.delete();
                                } catch (Exception ignored) {
                                }
                            });
                });
            } catch (Exception e) {
                try {
                    tmp.delete();
                } catch (Exception ignored) {
                }
                toast(getString(R.string.backup_ambil_gagal, String.valueOf(e.getMessage())));
                appendUiLog("[tg] Failed to fetch backup: " + e);
            }
        });
    }

    private void restoreTelegramTerkonfirmasi(File unduhan) {

        File plain = new File(getCacheDir(),
                unduhan.getName().replace(".zip", "-dec.zip"));
        try {
            File zip = unduhan;
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String pass = TgBackup.amanString(sp, TgBackup.KEY_TG_PASS, "");
            if (TgBackup.isEncrypted(unduhan)) {
                if (pass == null || pass.trim().isEmpty()) {
                    toast(getString(R.string.backup_terenkripsi));
                    return;
                }
                TgBackup.decryptFile(unduhan, plain, pass.trim());
                zip = plain;
            }
            restoreFromZip(zip);
        } catch (Exception e) {
            toast(getString(R.string.restore_gagal, String.valueOf(e.getMessage())));
            appendUiLog("[app] Restore failed: " + e);
        } finally {
            try {
                unduhan.delete();
            } catch (Exception ignored) {
            }
            try {
                plain.delete();
            } catch (Exception ignored) {
            }
        }
    }

    private void restoreFromZip(File zip) {
        if (!TgBackup.kunciRestore()) {
            toast(getString(R.string.tugas_lain_jalan));
            appendUiLog("[app] Restore ditolak: tugas backup/restore lain sedang berjalan.");
            return;
        }
        try {
            appendUiLog("[app] Menghentikan server sebelum restore...");

            String msg = TgBackup.restoreFromZip(getApplicationContext(), zip);
            uiPost(() -> {
                reloadSettingsFromPrefs();
                toast(getString(R.string.restore_selesai_mulai));
            });
            appendUiLog("[app] " + msg);
        } catch (Exception e) {
            toast(getString(R.string.restore_gagal, String.valueOf(e.getMessage())));
            appendUiLog("[app] Restore failed: " + e);
        } finally {
            TgBackup.lepasRestore();
        }
    }

    private void mintaExport() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String pass = TgBackup.amanString(sp, TgBackup.KEY_TG_PASS, "");
        boolean terkunci = pass != null && !pass.trim().isEmpty();
        confirm(getString(R.string.ekspor_setelan_judul),
                terkunci ? getString(R.string.ekspor_setelan_pesan_enkrip)
                        : getString(R.string.ekspor_setelan_pesan_plain),
                () -> {

                    String dir = dataDirInput.getText() == null ? ""
                            : dataDirInput.getText().toString();
                    runBusy(() -> exportConfig(dir));
                });
    }

    static final int BATAS_FILE_EXPORT = 3;

    static java.util.List<String> pilihHapusBatasExport(
            java.util.Map<String, Long> namaKeWaktu, int batas, String kecualikan) {
        java.util.List<java.util.Map.Entry<String, Long>> semua =
                new java.util.ArrayList<>(namaKeWaktu.entrySet());
        java.util.Collections.sort(semua,
                (a, b) -> Long.compare(b.getValue(), a.getValue()));
        java.util.List<String> hapus = new java.util.ArrayList<>();

        int jatah = batas;
        if (kecualikan != null && namaKeWaktu.containsKey(kecualikan) && jatah > 0) {
            jatah--;
        }
        int simpan = 0;
        for (java.util.Map.Entry<String, Long> e : semua) {
            if (kecualikan != null && kecualikan.equals(e.getKey())) {
                continue;
            }
            simpan++;
            if (simpan > jatah) {
                hapus.add(e.getKey());
            }
        }
        return hapus;
    }

    private void bersihkanExportPlainCache() {
        try {
            File pending = exportPlainTertunda;
            String pendingPath = pending == null ? null : pending.getAbsolutePath();
            boolean segar = pending != null
                    && SystemClock.elapsedRealtime() - exportPlainPada < EXPORT_PLAIN_TTL_MS;
            String pendingNamaSegar = segar && pending != null ? pending.getName() : null;
            File[] sisa = getCacheDir().listFiles();
            if (sisa != null) {
                for (File f : sisa) {
                    String n = f.getName();
                    if (n.startsWith("app-config-") && n.endsWith(".json")) {

                        if (sisaEnkripTmp(n)) {
                            f.delete();
                            continue;
                        }
                        boolean pendingSegar = pendingPath != null
                                && pendingPath.equals(f.getAbsolutePath())
                                && segar;
                        if (pendingSegar) {
                            continue;
                        }

                        long umur = System.currentTimeMillis() - f.lastModified();
                        if (umur < 0) {
                            umur = 0;
                        }
                        if (umur > EXPORT_PLAIN_TTL_MS) {
                            f.delete();
                        }
                        continue;
                    }
                    if (n.startsWith("vwtg-restore-") && n.endsWith(".zip")) {

                        long umur = System.currentTimeMillis() - f.lastModified();
                        if (umur < 0) {
                            umur = 0;
                        }
                        if (umur > RESTORE_TG_TTL_MS) {
                            f.delete();
                        }
                        continue;
                    }
                    if (n.startsWith(TgBackup.PREFIX_PLAIN_TMP)) {

                        long umur = System.currentTimeMillis() - f.lastModified();
                        if (umur < 0) {
                            umur = 0;
                        }
                        if (umur > TgBackup.UMUR_DEC_TMP_MS) {
                            f.delete();
                        }
                    }
                }
            }
            batasiFileExportCache(pendingNamaSegar, sisa);
        } catch (Exception ignored) {
        }
    }

    private void batasiFileExportCache(String pendingNamaSegar, File[] kini) {
        try {
            if (kini == null) {
                return;
            }
            java.util.Map<String, Long> peta = new java.util.HashMap<>();
            java.util.Map<String, File> berkas = new java.util.HashMap<>();
            for (File f : kini) {
                String n = f.getName();

                if (n.startsWith("app-config-") && n.endsWith(".json") && !sisaEnkripTmp(n)) {
                    peta.put(n, f.lastModified());
                    berkas.put(n, f);
                }
            }
            for (String n : pilihHapusBatasExport(peta, BATAS_FILE_EXPORT, pendingNamaSegar)) {
                try {
                    File t = berkas.get(n);
                    if (t != null) {
                        t.delete();
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean sapuCacheJalan =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void sapuCacheLatar() {
        if (!sapuCacheJalan.compareAndSet(false, true)) {
            return;
        }
        boolean masuk = Util.jalankanBg(() -> {
            try {
                sapuExportPlainBasi();
                sapuSisaImpor();
            } finally {
                sapuCacheJalan.set(false);
            }
        });
        if (!masuk) {

            sapuCacheJalan.set(false);
        }
    }

    private void sapuExportPlainBasi() {
        try {
            File pending = exportPlainTertunda;
            if (pending != null
                    && SystemClock.elapsedRealtime() - exportPlainPada >= EXPORT_PLAIN_TTL_MS) {
                pending.delete();

                if (exportPlainTertunda == pending) {
                    exportPlainTertunda = null;
                }
            }
        } catch (Exception ignored) {
        } finally {
            bersihkanExportPlainCache();
        }
    }

    private void exportConfig(String dataDirMentah) {
        try {
            String dataDir = dataDirMentah == null ? "" : dataDirMentah.trim();
            if (TextUtils.isEmpty(dataDir)) {
                dataDir = ServerService.dataDirBawaanSegar();
            }
            if (!ServerService.dataDirAman(dataDir)
                    || !ServerService.dataDirKanonisAman(dataDir)) {
                toast(getString(R.string.folder_tidak_valid));
                appendUiLog("[app] Folder data tidak valid: '" + dataDir + "' - direset ke bawaan.");
                dataDir = ServerService.dataDirBawaanSegar();
            }
            File backupDir = new File(dataDir, "backups");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }
            String ts = TgBackup.stempelUnik();
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            byte[] bytes = TgBackup.configJson(sp).getBytes(StandardCharsets.UTF_8);

            bersihkanExportPlainCache();

            String pass = TgBackup.amanString(sp, TgBackup.KEY_TG_PASS, "");
            final File out;
            final String mime;
            if (pass != null && !pass.trim().isEmpty()) {

                File plain = new File(getCacheDir(), PREFIX_ENCTMP + ts + ".json");
                File enc = new File(backupDir, "app-config-" + ts + ".json.enc");
                try {
                    try (FileOutputStream fos = new FileOutputStream(plain)) {
                        fos.write(bytes);
                    }
                    TgBackup.encryptFile(plain, enc, pass.trim());
                } finally {

                    try {
                        plain.delete();
                    } catch (Exception ignored) {
                    }
                }
                out = enc;
                mime = "application/octet-stream";
            } else {
                out = new File(getCacheDir(), "app-config-" + ts + ".json");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(bytes);
                }
                mime = "application/json";
            }
            sapuExportEnkripLama(backupDir);
            final String path = out.getAbsolutePath();
            final boolean plain = !path.endsWith(".enc");
            if (plain) {
                exportPlainTertunda = new File(path);
                exportPlainPada = SystemClock.elapsedRealtime();
            }
            uiPost(() -> {
                Uri uri = Uri.parse("content://" + FileShareProvider.AUTHORITY + Uri.encode(path, "/"));
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {

                    startActivityForResult(
                            Intent.createChooser(send,
                                    getString(R.string.config_bagikan_chooser)),
                            REQ_SHARE_CONFIG);
                } catch (Exception e2) {
                    exportPlainTertunda = null;
                    toast(getString(R.string.berkas_tersimpan, path));
                    appendUiLog("[app] Config tersimpan (bagi gagal): " + path);
                    return;
                }
                toast(getString(R.string.config_diekspor, path));
                appendUiLog("[app] Config export: " + path);
            });
        } catch (Exception e) {
            toast(getString(R.string.config_ekspor_gagal, String.valueOf(e.getMessage())));
            appendUiLog("[app] Config export failed: " + e);
        }
    }

    @SuppressWarnings("deprecation")
    private void pickImportFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);

            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/json", "application/octet-stream"});
            startActivityForResult(intent, REQ_IMPORT);
        } catch (Exception e) {
            toast(getString(R.string.pemilih_berkas_gagal, String.valueOf(e.getMessage())));
        }
    }

    private void importConfig(Uri uri) {
        try {
            String capImpor = TgBackup.stempelUnik();
            File tmp = new File(getCacheDir(), "vwcfg-import-" + capImpor + ".bin");

            try (InputStream awal = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                if (awal == null) {
                    toast(getString(R.string.config_impor_berkas));
                    appendUiLog("[app] Import ditolak: stream file null");
                    tmp.delete();
                    return;
                }
                byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while ((n = awal.read(buf)) != -1) {
                    total += n;
                    if (total > 512 * 1024) {
                        throw new java.io.IOException(getString(R.string.config_terlalu_besar));
                    }
                    fos.write(buf, 0, n);
                }
            }
            File src = tmp;
            if (TgBackup.isEncrypted(tmp)) {

                String sidikImpor = sidikBerkas(tmp);
                if (imporDiblokir(ambilGagalImpor(sidikImpor))) {
                    tmp.delete();
                    sapuSisaImpor();
                    toast(getString(R.string.config_impor_salah_terlalu));
                    appendUiLog("[app] Import diblokir: password salah 5x (berkas sama)");
                    return;
                }
                SharedPreferences sp0 = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
                String pass0 = TgBackup.amanString(sp0, TgBackup.KEY_TG_PASS, "");
                if (pass0 == null || pass0.trim().isEmpty()) {

                    tanyaPasswordImpor(tmp, capImpor,
                            getString(R.string.config_terenkripsi_tanya),
                            sidikImpor);
                    return;
                }
                File plain = new File(getCacheDir(), "vwcfg-import-" + capImpor + "-dec.json");
                try {
                    TgBackup.decryptFile(tmp, plain, pass0.trim());
                } catch (Exception e) {

                    appendUiLog("[app] Import: password perangkat tak cocok, tanya password asal");
                    tanyaPasswordImpor(tmp, capImpor,
                            getString(R.string.config_sandi_beda),
                            sidikImpor);
                    return;
                }
                tmp.delete();
                src = plain;
            }
            terapkanImporJson(src);
        } catch (Exception e) {
            sapuSisaImpor();
            toast(getString(R.string.config_impor_gagal, String.valueOf(e.getMessage())));
            appendUiLog("[app] Config import failed: " + e);
        }
    }

    private void terapkanImporJson(File src) {
        try {
            String json;
            try (InputStream in = new java.io.FileInputStream(src)) {
                json = new String(readCapped(in, 512 * 1024,
                        getString(R.string.config_terlalu_besar)), StandardCharsets.UTF_8);
            }
            src.delete();
            JSONObject root = new JSONObject(json);
            if (!"tasirin-vaultwarden-host".equals(root.optString("app", ""))) {
                toast(getString(R.string.config_tidak_valid_app));
                appendUiLog("[app] Import ditolak: marker app tidak cocok");
                return;
            }
            JSONObject prefs = root.optJSONObject("prefs");
            if (prefs == null) {
                toast(getString(R.string.config_tidak_valid));
                return;
            }
            paksaCacheUbah = true;
            TgBackup.applyPrefsFromJson(this, prefs);
            sanitizePortPref();

            SharedPreferences sp2 = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            sp2.edit().putBoolean(KEY_WIZARD_DONE, true).apply();
            TgBackup.schedule(SettingsActivity.this,
                    TgBackup.amanBoolean(sp2, TgBackup.KEY_TG_AUTO, false));
            TgBot.schedule(SettingsActivity.this);
            appendUiLog("[app] Config import selesai.");
            uiPost(() -> {
                reloadSettingsFromPrefs();
                toast(getString(R.string.config_diimpor));
            });
        } catch (Exception e) {
            sapuSisaImpor();
            toast(getString(R.string.config_impor_gagal, String.valueOf(e.getMessage())));
            appendUiLog("[app] Config import failed: " + e);
        }
    }

    private void tanyaPasswordImpor(final File tmpEnkrip, final String capImpor,
            String alasan, final String sidikImpor) {
        final String pesan = alasan;

        ui.post(() -> {
        try {
            if (isFinishing() || isDestroyed()) {
                tmpEnkrip.delete();
                return;
            }
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setMaxLines(1);
            final AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.config_sandi_judul)
                    .setMessage(pesan)
                    .setView(input)
                    .setPositiveButton(R.string.config_coba_impor, null)
                    .setNegativeButton(R.string.batal, (d, w) -> {
                        try {
                            tmpEnkrip.delete();
                        } catch (Exception ignored) {
                        }
                    })
                    .create();
            dialog.setCanceledOnTouchOutside(false);

            dialog.setOnDismissListener(d -> {
                try {
                    tmpEnkrip.delete();
                } catch (Exception ignored) {
                }
            });

            final int[] gagalImpor = {ambilGagalImpor(sidikImpor)};
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        String coba = input.getText().toString();
                        if (coba == null || coba.trim().isEmpty()) {
                            input.setError(getString(R.string.sandi_dulu));
                            return;
                        }
                        final String rahasia = coba.trim();

                        v.setEnabled(false);
                        boolean masukImpor = Util.jalankanBg(() -> {
                            try {
                                File plain = new File(getCacheDir(),
                                        "vwcfg-import-" + capImpor + "-dec.json");
                                TgBackup.decryptFile(tmpEnkrip, plain, rahasia);
                                tmpEnkrip.delete();
                                simpanGagalImpor(sidikImpor, 0);
                                ui.post(() -> {
                                    try {
                                        dialog.dismiss();
                                    } catch (Exception ignored) {
                                    }
                                });
                                terapkanImporJson(plain);
                            } catch (Exception e) {
                                ui.post(() -> {
                                    try {
                                        v.setEnabled(true);
                                    } catch (Exception ignored) {
                                    }

                                    int sudah = gagalImpor[0] + 1;
                                    gagalImpor[0] = sudah;
                                    simpanGagalImpor(sidikImpor, sudah);
                                    if (imporDiblokir(sudah)) {
                                        try {
                                            tmpEnkrip.delete();
                                        } catch (Exception ignored) {
                                        }
                                        try {
                                            dialog.dismiss();
                                        } catch (Exception ignored2) {
                                        }
                                        toast(getString(R.string.config_impor_salah_terlalu));
                                        appendUiLog("[app] Import dibatalkan: password salah 5x");
                                    } else {
                                        input.setError(getString(R.string.sandi_salah, sudah));
                                        appendUiLog("[app] Import ditolak: password asal tak cocok");
                                    }
                                });
                            }
                        });
                        if (!masukImpor) {

                            v.setEnabled(true);
                            toast(getString(R.string.pin_sibuk));
                            appendUiLog("[app] Impor ditunda: antrean tugas penuh.");
                        }
                    }));
            DialogAman.tampil(this, dialog);
        } catch (Exception e) {
            try {
                tmpEnkrip.delete();
            } catch (Exception ignored) {
            }
            toast(getString(R.string.config_impor_gagal, String.valueOf(e.getMessage())));
        }
        });
    }

    private void sapuSisaImpor() {
        try {
            File[] sisa = getCacheDir().listFiles();
            if (sisa != null) {
                for (File f : sisa) {
                    String n = f.getName();
                    if (n.startsWith("vwcfg-import")) {
                        f.delete();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void reloadSettingsFromPrefs() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        dataDirInput.setText(TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar()));
        portInput.setText(ServerService.effectivePort(sp));
        adminTokenInput.setText(TgBackup.amanString(sp, ServerService.KEY_ADMIN_TOKEN, ""));
        autoStartCheck.setChecked(TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_START, false));
        tgTokenInput.setText(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
        tgChatInput.setText(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, ""));
        tgAutoCheck.setChecked(TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false));
        autoUpdateCb.setChecked(TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_UPDATE, false));
        autoUpdateWvCb.setChecked(TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_UPDATE_WV, false));
        autoRestartCb.setChecked(TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_RESTART_UPDATE, false));
        backupPassInput.setText(TgBackup.amanString(sp, TgBackup.KEY_TG_PASS, ""));
        binShaInput.setText(TgBackup.amanString(sp, ServerService.KEY_BIN_SHA, ""));

        pinCentangProgram = true;
        pinEnabledCheck.setChecked(TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false));
        pinCentangProgram = false;
        validasiInline();
    }

    private void copyLocalUrl() {
        String url = ServerService.localUrl(this);
        if (url.contains("127.0.0.1") || url.contains("localhost")) {
            salinTeks(url, getString(R.string.url_loopback_disalin));
        } else {
            salinTeks(url, getString(R.string.url_jaringan_disalin));
        }
    }

    private String portEfektifUntukSalin() {
        CharSequence cs = portInput == null ? null : portInput.getText();
        return ServerService.normalisasiPort(cs == null ? "" : cs.toString().trim());
    }

    private static byte[] readCapped(InputStream in, int max, String pesanBesar)
            throws Exception {

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) {
                throw new java.io.IOException(pesanBesar);
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private void sanitizePortPref() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);

        String p = TgBackup.amanString(sp, ServerService.KEY_PORT, DEFAULT_PORT);
        try {
            int pn = Integer.parseInt(p.trim());
            if (pn >= 1024 && pn <= 65535) {
                return;
            }
        } catch (Exception ignored) {
        }
        sp.edit().putString(ServerService.KEY_PORT, DEFAULT_PORT).apply();
        paksaCacheUbah = true;
        appendUiLog("[app] Port hasil import tidak valid/privileged - kembali ke " + DEFAULT_PORT + ".");
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

    private File[] daftarBackup(File backupDir) {
        if (backupDir == null) {
            return null;
        }

        long now = SystemClock.elapsedRealtime();
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

    private String dbInfoLine(boolean bolehHitung) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastDbCheck < DB_CHECK_MS) {
            return dbLine;
        }
        if (!bolehHitung) {
            return dbLine;
        }
        lastDbCheck = now;
        try {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
            File db = new File(dataDir, "db.sqlite3");
            if (!db.exists()) {
                dbLine = "";
                return dbLine;
            }
            File[] files = daftarBackup(new File(dataDir, "backups"));
            int n = files == null ? 0 : files.length;
            dbLine = getString(R.string.info_db, TgBackup.humanBytes(db.length()), n);
        } catch (Exception e) {
            dbLine = "";
        }
        return dbLine;
    }

    private String storageInfoLine(boolean bolehHitung) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastStorageCheck < STORAGE_CHECK_MS) {
            return storageLine;
        }
        if (!bolehHitung) {
            return storageLine;
        }
        lastStorageCheck = now;
        try {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
            File data = new File(dataDir);
            File db = new File(data, "db.sqlite3");
            File backups = new File(data, "backups");
            File webVault = new File(data, "web-vault");
            File bin = new File(getFilesDir(), "bin/vaultwarden-" + ServerService.ABI);
            StringBuilder sb = new StringBuilder(getString(R.string.info_storage)).append(' ');
            boolean first = true;
            if (db.exists()) {
                sb.append(getString(R.string.info_db_item, TgBackup.humanBytes(db.length())));
                first = false;
            }
            File[] files = daftarBackup(backups);
            if (files != null && files.length > 0) {
                if (!first) {
                    sb.append(" \u00B7 ");
                }
                sb.append(getString(R.string.info_backup_item,
                        TgBackup.humanBytes(TgBackup.folderBytesCached(backups)), files.length));
                first = false;
            }
            long wvBytes = TgBackup.folderBytesCached(webVault);
            if (wvBytes > 0) {
                if (!first) {
                    sb.append(" \u00B7 ");
                }
                sb.append(getString(R.string.info_web_item, TgBackup.humanBytes(wvBytes)));
                first = false;
            }
            if (bin.exists()) {
                if (!first) {
                    sb.append(" \u00B7 ");
                }
                sb.append(getString(R.string.info_binary_item, TgBackup.humanBytes(bin.length())));
            }
            storageLine = sb.toString();
        } catch (Exception e) {
            storageLine = "";
        }
        return storageLine;
    }

    private String webVaultInfoLine(boolean bolehHitung) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastWvCheck < WV_CHECK_MS) {
            return wvLine;
        }
        if (!bolehHitung) {
            return wvLine;
        }
        lastWvCheck = now;
        try {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());

            String penanda = Updater.webVaultFromVersion(this);
            String updated = penanda;
            if (updated == null) {
                updated = Updater.readWvVersion(new File(dataDir, "web-vault/vw-version.json"));
            }
            String bundled = bundledRaw != null ? bundledRaw : Updater.readBundledVersionRaw(this);
            String wv = updated != null ? updated
                    : (bundled != null ? Updater.normVersion(bundled) : null);
            if (wv == null) {
                wvLine = "";
                return wvLine;
            }
            StringBuilder sb = new StringBuilder(getString(R.string.info_wv, wv,
                    getString(updated != null ? R.string.info_wv_updated
                            : R.string.info_wv_bundled)));
            String server = Updater.currentServerVersion(this);
            if (Updater.wvBedaVersiServer(penanda, server)) {
                sb.append(' ').append(getString(R.string.info_wv_beda, server));
            }
            wvLine = sb.toString();
        } catch (Exception e) {
            wvLine = "";
        }
        return wvLine;
    }

    private String sysInfoLine(boolean bolehHitung) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSysCheck < SYS_CHECK_MS) {
            return sysLine;
        }
        if (!bolehHitung) {
            return sysLine;
        }
        lastSysCheck = now;
        StringBuilder sb = new StringBuilder();
        if (ServerService.running) {
            long rss = ServerService.processRssKb();
            if (rss > 0) {
                sb.append(getString(R.string.info_ram, TgBackup.humanBytes(rss * 1024)));
            }
            long up = ServerService.uptimeMs();
            if (up > 0) {
                if (sb.length() > 0) {
                    sb.append(" \u00B7 ");
                }
                sb.append(getString(R.string.info_uptime, TgBot.durationText(up)));
            }
        }
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
        long free = TgBackup.freeBytes(this, dataDir);
        if (free > 0) {
            if (sb.length() > 0) {
                sb.append(" \u00B7 ");
            }
            sb.append(getString(R.string.info_free, TgBackup.humanBytes(free)));
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

    private String certInfoLine(boolean bolehHitung) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCertCheck < CERT_CHECK_MS) {
            return certLine;
        }
        if (!bolehHitung) {
            return certLine;
        }
        lastCertCheck = now;
        certLine = certInfoLineInner();
        return certLine;
    }

    private String certInfoLineInner() {
        try {

            File cert = new File(getFilesDir(), "tls/cert.pem");
            if (!cert.exists()) {
                SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
                String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
                cert = new File(dataDir, "tls/cert.pem");
            }
            if (!cert.exists()) {
                return "";
            }
            long days = TlsCert.daysLeft(cert);
            if (days < 0) {
                return "";
            }

            int jumlah = days > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) days;
            if (days < 30) {
                return getResources().getQuantityString(R.plurals.info_tls_exp, jumlah, days);
            }
            return getResources().getQuantityString(R.plurals.info_tls, jumlah, days);
        } catch (Exception e) {
            return "";
        }
    }

    private void checkForUpdate() {
        try {
            appendUiLog("[app] Mengecek update dari sumber resmi...");

            final android.content.Context app = getApplicationContext();

            String imbuhShimLog = "";
            String imbuhShimUi = "";
            if (KernelCompat.legacyPerangkat()) {
                try {
                    Updater.ensureShimFile(app);
                    imbuhShimLog = " Shim getrandom siap.";
                    imbuhShimUi = " " + getString(R.string.shim_siap);
                } catch (Exception se) {
                    imbuhShimLog = " Shim gagal: " + se.getMessage();
                    imbuhShimUi = " " + getString(R.string.shim_gagal,
                            String.valueOf(se.getMessage()));
                }
            }
            String msg = Updater.tryUpdate(app);
            if (Updater.binaryBerubah(msg)) {
                pendingVersion = null;
            }
            appendUiLog("[app] " + msg + imbuhShimLog);
            toast(Updater.binaryBerubah(msg)
                    ? msg + imbuhShimUi + " " + getString(R.string.update_press_start)
                    : msg + imbuhShimUi);
        } catch (Exception e) {
            toast(getString(R.string.cek_update_gagal, String.valueOf(e.getMessage())));
            appendUiLog("[app] Update check failed: " + e);
        }
    }

    private void segarkanLabelVersi() {
        uiPost(() -> {
            try {
                SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
                String b = Updater.normalisasiPinVersi(
                        TgBackup.amanString(sp, ServerService.KEY_BIN_PILIH, ""));
                String w = Updater.normalisasiPinVersi(
                        TgBackup.amanString(sp, ServerService.KEY_WV_PILIH, ""));
                if (pilihBinBtn != null) {
                    pilihBinBtn.setText(getString(R.string.pilih_versi_binary, labelKuncian(b)));
                }
                if (pilihWvBtn != null) {
                    pilihWvBtn.setText(
                            getString(R.string.pilih_versi_webvault, labelKuncian(w)));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private String labelKuncian(String pin) {
        if (pin == null || pin.isEmpty()) {
            return getString(R.string.versi_terbaru_otomatis);
        }
        return getString(R.string.versi_terkunci, pin);
    }

    private void pilihVersiDialog(final boolean untukBinary) {
        runBusy(() -> {
            try {
                appendUiLog("[app] Memuat daftar versi...");
                java.util.List<String> versi = new java.util.ArrayList<>();
                for (String v : Updater.daftarVersiTersedia(this, 30)) {
                    String normal = Updater.normalisasiPinVersi(v);
                    if (normal != null && !versi.contains(normal)) {
                        versi.add(normal);
                    }
                }

                String pin = untukBinary ? Updater.kuncianBinary(this)
                        : Updater.kuncianWebVault(this);
                if (pin != null && !pin.isEmpty() && !versi.contains(pin)) {
                    versi.add(pin);
                }
                final java.util.List<String> tetap = versi;
                final String pinTetap = pin;
                ui.post(() -> tampilDialogVersi(untukBinary, tetap, pinTetap));
            } catch (Exception e) {
                toast(getString(R.string.daftar_versi_gagal));
                appendUiLog("[app] Gagal memuat daftar versi: " + e);
            }
        });
    }

    private void tampilDialogVersi(final boolean untukBinary,
            final java.util.List<String> versi, String pin) {

        if (isFinishing() || isDestroyed()) {
            return;
        }
        final String[] nama = new String[versi.size() + 1];
        nama[0] = getString(R.string.versi_terbaru_otomatis);
        for (int i = 0; i < versi.size(); i++) {
            nama[i + 1] = "v" + versi.get(i);
        }
        int awal = 0;
        if (pin != null && !pin.isEmpty()) {
            int idx = versi.indexOf(pin);
            if (idx >= 0) {
                awal = idx + 1;
            }
        }
        final int[] pilih = {awal};
        DialogAman.tampil(this, new AlertDialog.Builder(this)
                .setTitle(untukBinary ? R.string.dialog_judul_versi_binary
                        : R.string.dialog_judul_versi_webvault)
                .setSingleChoiceItems(nama, awal, (d, which) -> pilih[0] = which)
                .setPositiveButton(R.string.pasang, (d, w) -> {
                    String v = pilih[0] == 0 ? "" : versi.get(pilih[0] - 1);
                    pasangVersiDipilih(untukBinary, v);
                })
                .setNeutralButton(R.string.isi_manual,
                        (d, w) -> dialogManualVersi(untukBinary))
                .setNegativeButton(R.string.batal, null)
                .create());
    }

    private void dialogManualVersi(final boolean untukBinary) {
        final EditText input = new EditText(this);
        input.setHint(R.string.versi_manual_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        String pin = untukBinary ? Updater.kuncianBinary(this)
                : Updater.kuncianWebVault(this);
        if (pin != null && !pin.isEmpty()) {
            input.setText(pin);
        }
        DialogAman.tampil(this, new AlertDialog.Builder(this)
                .setTitle(R.string.versi_manual_judul)
                .setView(input)
                .setPositiveButton(R.string.pasang, (d, w) -> {
                    String t = input.getText().toString().trim();
                    String normal = t.isEmpty() ? "" : Updater.normalisasiPinVersi(t);
                    if (normal == null) {
                        toast(getString(R.string.versi_tidak_valid));
                        return;
                    }
                    pasangVersiDipilih(untukBinary, normal);
                })
                .setNegativeButton(R.string.batal, null)
                .create());
    }

    private void pasangVersiDipilih(final boolean untukBinary, final String versi) {
        final String pinLama = untukBinary ? Updater.kuncianBinary(this)
                : Updater.kuncianWebVault(this);
        boolean tersimpan = untukBinary
                ? Updater.simpanKuncianBinary(this, versi)
                : Updater.simpanKuncianWebVault(this, versi);
        if (!tersimpan) {
            toast(getString(R.string.versi_tidak_valid));
            return;
        }
        segarkanLabelVersi();
        perbaruiStatusIsian();
        final String target = (versi == null || versi.isEmpty()) ? null : versi;
        final String pinLamaFinal = pinLama == null ? "" : pinLama;
        if (untukBinary) {
            runBusy(() -> {
                try {
                    appendUiLog("[app] Memasang binary v"
                            + (target == null ? "terbaru" : target) + "...");
                    if (KernelCompat.legacyPerangkat()) {
                        try {
                            Updater.ensureShimFile(this);
                        } catch (Exception se) {
                            appendUiLog("[app] Shim gagal: " + se.getMessage());
                        }
                    }
                    String msg = Updater.tryUpdateVersi(this, target);
                    if (Updater.binaryBerubah(msg)) {
                        pendingVersion = null;
                    }
                    appendUiLog("[app] " + msg);
                    toast(Updater.binaryBerubah(msg)
                            ? msg + " Press Start to use it." : msg);
                } catch (Exception e) {
                    if (target != null) {
                        if (untukBinary) {
                            Updater.simpanKuncianBinary(SettingsActivity.this, pinLamaFinal);
                        } else {
                            Updater.simpanKuncianWebVault(SettingsActivity.this, pinLamaFinal);
                        }
                        segarkanLabelVersi();
                        perbaruiStatusIsian();
                    }
                    toast(getString(R.string.biner_pasang_gagal, String.valueOf(e.getMessage())));
                    appendUiLog("[app] Failed to install binary: " + e);
                }
            });
        } else {
            runBusy(() -> {
                try {
                    appendUiLog("[app] Mengunduh web-vault v"
                            + (target == null ? "terbaru" : target) + "...");
                    String msg = Updater.updateWebVaultVersi(this, target);
                    toast(msg);
                    appendUiLog("[app] " + msg);
                    lastWvCheck = 0;
                } catch (Exception e) {
                    if (target != null) {
                        if (untukBinary) {
                            Updater.simpanKuncianBinary(SettingsActivity.this, pinLamaFinal);
                        } else {
                            Updater.simpanKuncianWebVault(SettingsActivity.this, pinLamaFinal);
                        }
                        segarkanLabelVersi();
                        perbaruiStatusIsian();
                    }
                    toast(getString(R.string.wv_update_gagal, String.valueOf(e.getMessage())));
                    appendUiLog("[app] Web-vault update failed: " + e);
                }
            });
        }
    }

    private void revertToBundled() {

        if (ServerService.running || ServerService.isProcessAlive()) {
            toast(getString(R.string.biner_stop_dulu));
            appendUiLog("[app] Reset binary ditolak: server masih berjalan - Stop dulu.");
            return;
        }
        File out = new File(getFilesDir(), "bin/vaultwarden-" + ServerService.ABI);
        new File(getFilesDir(), "bin/version.txt").delete();
        if (out.exists() && out.delete()) {
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .remove(ServerService.KEY_UPDATE_VERSION)
                    .remove(ServerService.KEY_BIN_DL_GAGAL_AT).apply();
            ServerService.binaryVersion = "";
            toast(getString(R.string.biner_dihapus));
            appendUiLog("[app] Binary di-reset; akan diunduh ulang saat Start.");
        } else {
            toast(getString(R.string.biner_tidak_ada));
        }
    }

    private void togglePassword(EditText et, Button btn) {
        boolean hidden = (et.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
        et.setInputType(hidden
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setSelection(et.getText().length());
        btn.setText(hidden ? getString(R.string.hide) : getString(R.string.show));
    }

    private void maybeAutoBackup() {
        TgBackup.maybeAutoBackup(this, new TgBackup.BackupStartUi() {
            @Override public void toast(String s) {
                SettingsActivity.this.toast(s);
            }
            @Override public void catat(String s) {
                appendUiLog(s);
            }
            @Override public void jalankanUi(Runnable r) {
                uiPost(r);
            }
        });
    }

    private void aksiStartStop() {

        getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_WIZARD_DONE, true).apply();
        if (ServerService.running) {
            ServerService.stop(this);
        } else {
            saveAndStart();
        }
    }

    private static boolean adaIsiTrim(CharSequence s) {
        if (s == null) {
            return false;
        }
        int n = s.length();
        int a = 0;
        while (a < n && s.charAt(a) <= ' ') {
            a++;
        }
        if (a >= n) {
            return false;
        }
        int b = n;
        while (b > a && s.charAt(b - 1) <= ' ') {
            b--;
        }
        return b > a;
    }

    private void perbaruiStatusIsian() {
        if (adminTokenInput == null) {
            return;
        }

        android.os.Looper utama = android.os.Looper.getMainLooper();
        if (utama != null && android.os.Looper.myLooper() != utama) {
            uiPost(this::perbaruiStatusIsian);
            return;
        }
        boolean adminIsi = adaIsiTrim(adminTokenInput.getText());
        boolean botIsi = tgTokenInput != null && adaIsiTrim(tgTokenInput.getText())
                && tgChatInput != null && adaIsiTrim(tgChatInput.getText());
        String statusAdmin = getString(R.string.status_admin, adminIsi
                ? getString(R.string.terisi) : getString(R.string.belum_diisi));
        if (adminStatus != null && !statusAdmin.equals(lastStatusAdmin)) {
            lastStatusAdmin = statusAdmin;
            adminStatus.setText(statusAdmin);
        }
        String statusBot = getString(R.string.status_bot, botIsi
                ? getString(R.string.terisi) : getString(R.string.belum_diisi));
        if (tgStatus != null && !statusBot.equals(lastStatusBot)) {
            lastStatusBot = statusBot;
            tgStatus.setText(statusBot);
        }
    }

    private void salinTeks(String isi, String label) {
        if (isi == null || isi.trim().isEmpty()) {
            toast(label + " " + getString(R.string.belum_diisi));
            return;
        }
        if (LogActivity.salinBersihOtomatis(this, label, isi)) {
            toast(label + " " + getString(R.string.disalin));
        } else {
            toast(getString(R.string.galat_awalan, label));
        }
    }

    private void awaliGalat(TextView baris, int stringId) {
        if (baris != null) {
            baris.setText(getString(R.string.galat_awalan, getString(stringId)));
        }
    }

    private void setTeksGalat(android.widget.TextView err, String galat) {
        String tampil = galat == null ? ""
                : getString(R.string.galat_awalan, galat);
        Object dulu = err.getTag();
        if (tampil.equals(dulu)) {
            return;
        }
        err.setTag(tampil);
        err.setText(tampil);
        int vis = galat == null ? View.GONE : View.VISIBLE;
        if (err.getVisibility() != vis) {
            err.setVisibility(vis);
        }
    }

    private static void samakanVisibilitas(View v, int vis) {
        if (v != null && v.getVisibility() != vis) {
            v.setVisibility(vis);
        }
    }

    private void validasiInline() {
        String gData = galatFolder(dataDirInput.getText().toString(), msgFolderSalah);
        setTeksGalat(dataDirError, gData);
        String gPort = galatPort(portInput.getText().toString(), msgPortSalah);
        setTeksGalat(portError, gPort);
        String gAdmin = adminTokenInput != null ? galatAdmin(adminTokenInput.getText().toString(),
                msgAdminSalah) : null;
        samakanVisibilitas(adminError, gAdmin == null ? View.GONE : View.VISIBLE);
        String gTok = tgTokenInput != null ? galatTgToken(tgTokenInput.getText().toString(),
                msgTokenSalah) : null;
        samakanVisibilitas(tokenError, gTok == null ? View.GONE : View.VISIBLE);
        String gChat = tgChatInput != null ? galatChat(tgChatInput.getText().toString(),
                msgChatSalah) : null;
        samakanVisibilitas(chatError, gChat == null ? View.GONE : View.VISIBLE);
        inputStartValid = gData == null && gPort == null && gAdmin == null;
        perbaruiEnabledStartStop();
        perbaruiStatusIsian();
    }

    private void perbaruiEnabledStartStop() {
        if (startStopBawah == null) {
            return;
        }
        boolean boleh = ServerService.running || inputStartValid;
        if (startStopBawah.isEnabled() != boleh) {
            startStopBawah.setEnabled(boleh);
        }
    }

    @SuppressWarnings("deprecation")
    private void tandaiLabel(TextView label, int stringId, boolean kotor) {
        Boolean dulu = labelKotorCache.get(label);
        if (dulu != null && dulu == kotor) {
            return;
        }
        labelKotorCache.put(label, kotor);
        if (kotor) {
            label.setText(getString(R.string.label_kotor, getString(stringId)));
            label.setTextColor(getResources().getColor(R.color.status_off));
        } else {
            label.setText(getString(stringId));
            label.setTextColor(warnaLabelBawaan);
        }
    }

    static String galatPort(String port, String msgSalah) {

        String isi = port == null ? "" : port.trim();
        if (isi.isEmpty()) {
            return null;
        }
        try {
            int p = Integer.parseInt(isi);

            if (p >= 1024 && p <= 65535) {
                return null;
            }
        } catch (Exception ignored) {
        }
        return msgSalah;
    }

    static String galatFolder(String folder, String msgSalah) {

        String isi = folder == null ? "" : folder.trim();
        if (isi.isEmpty()) {
            return null;
        }
        return ServerService.dataDirAman(isi) ? null : msgSalah;
    }

    static String galatAdmin(String token, String msgSalah) {

        String isi = token == null ? "" : token.trim();
        if (isi.isEmpty()) {
            return null;
        }
        return isi.length() >= 8 ? null : msgSalah;
    }

    static String galatTgToken(String token, String msgSalah) {

        String isi = token == null ? "" : token.trim();
        if (isi.isEmpty()) {
            return null;
        }
        return isi.contains(":") && isi.length() >= 20 ? null : msgSalah;
    }

    static String galatChat(String chat, String msgSalah) {

        if (chat != null) {
            String rapi = chat.trim();
            if (rapi.isEmpty()) {
                return null;
            }
            boolean adaPisah = false;
            for (int i = 0; i < rapi.length(); i++) {
                char c = rapi.charAt(i);
                if (c == ',' || c == ';' || c == '\n' || c == '\r'
                        || c == '\t' || c == ' ') {
                    adaPisah = true;
                    break;
                }
            }
            if (!adaPisah) {
                return Util.chatTunggalValid(rapi) ? null : msgSalah;
            }
        }
        java.util.List<String> daftar = Util.pecahDaftarChat(chat);
        if (daftar.isEmpty()) {
            return null;
        }
        if (daftar.size() > Util.MAKS_CHAT_TUJUAN) {
            return msgSalah;
        }
        for (int i = 0; i < daftar.size(); i++) {
            if (!Util.chatTunggalValid(daftar.get(i))) {
                return msgSalah;
            }
        }
        return null;
    }

    static String buatTokenAcak(java.util.Random rnd) {

        if (!(rnd instanceof java.security.SecureRandom)) {
            throw new IllegalArgumentException("Token acak wajib SecureRandom.");
        }
        String abjad = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(abjad.charAt(rnd.nextInt(abjad.length())));
        }
        return sb.toString();
    }

    static String teksBadgeHttps(boolean berjalan, String barisSert,
            String msgAktif, String msgBelumAda) {
        boolean ada = barisSert != null && !barisSert.isEmpty();
        if (berjalan) {
            return ada ? msgAktif + " \u2022 " + barisSert : msgAktif;
        }
        return ada ? barisSert : msgBelumAda;
    }

    private void confirm(String title, String message, final Runnable action) {
        confirm(title, message, action, null);
    }

    private void confirm(String title, String message, final Runnable action,
                         final Runnable batal) {
        if (isFinishing() || isDestroyed()) {
            if (batal != null) {
                batal.run();
            }
            return;
        }
        DialogAman.tampil(this, new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ya, (d, w) -> action.run())
                .setNegativeButton(R.string.batal, (d, w) -> {
                    if (batal != null) {
                        batal.run();
                    }
                })
                .create());
    }

    private void pasangSeksi(int idHeader, final int idBadan, final int idChevron, final String kunci) {
        View header = findViewById(idHeader);
        if (header == null) {
            return;
        }
        boolean buka = TgBackup.amanBoolean(getSharedPreferences(ServerService.PREFS, MODE_PRIVATE), kunci, true);
        terapkanSeksi(idBadan, idChevron, buka);
        perbaikiRantaiFokus();
        header.setOnClickListener(v -> {
            boolean kini = !seksiTerbuka(idBadan);
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit().putBoolean(kunci, kini).apply();
            terapkanSeksi(idBadan, idChevron, kini);
            perbaikiRantaiFokus();
        });
    }

    private void perbaikiRantaiFokus() {
        aturFokusBawah(R.id.headerServer, R.id.bodyServer, R.id.netInfo, R.id.headerKeamanan);
        aturFokusBawah(R.id.headerKeamanan, R.id.bodyKeamanan, R.id.adminToken, R.id.headerRawat);
        aturFokusBawah(R.id.headerRawat, R.id.bodyRawat, R.id.autoStart, R.id.headerTelegram);
        aturFokusBawah(R.id.headerTelegram, R.id.bodyTelegram, R.id.tgToken, R.id.headerLog);
        aturFokusBawah(R.id.headerLog, R.id.bodyLog, R.id.logOpen, R.id.aboutBtn);
    }

    private void aturFokusBawah(int idHeader, int idBadan, int idIsi, int idLanjut) {
        View header = findViewById(idHeader);
        if (header == null) {
            return;
        }
        header.setNextFocusDownId(seksiTerbuka(idBadan) ? idIsi : idLanjut);
    }

    private void pasangChip(int idChip, final int idHeader, final int idBadan,
            final int idChevron, final String kunci) {
        View chip = findViewById(idChip);
        if (chip == null) {
            return;
        }
        chip.setOnClickListener(v -> loncatKeSeksi(idHeader, idBadan, idChevron, kunci));
    }

    private boolean seksiTerbuka(int idBadan) {
        View badan = findViewById(idBadan);
        return badan != null && badan.getVisibility() == View.VISIBLE;
    }

    private void terapkanSeksi(int idBadan, int idChevron, boolean buka) {
        View badan = findViewById(idBadan);
        if (badan != null) {
            badan.setVisibility(buka ? View.VISIBLE : View.GONE);
        }
        TextView chev = findViewById(idChevron);
        if (chev != null) {
            chev.setText(buka ? "\u25B4" : "\u25BE");
        }
    }

    private void loncatKeSeksi(final int idHeader, final int idBadan,
            final int idChevron, final String kunci) {
        getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit().putBoolean(kunci, true).apply();
        terapkanSeksi(idBadan, idChevron, true);
        perbaikiRantaiFokus();
        final View jangkar = findViewById(idHeader);
        if (settingsScroll == null || jangkar == null) {
            return;
        }
        jangkar.requestFocus();
        settingsScroll.post(() -> {
            try {
                android.graphics.Rect kotak = new android.graphics.Rect();
                jangkar.getDrawingRect(kotak);
                settingsScroll.offsetDescendantRectToMyCoords(jangkar, kotak);
                settingsScroll.smoothScrollTo(0, kotak.top);
            } catch (Exception ignored) {
            }
        });
    }

    void fokuskanTombolUpdate() {
        if (isFinishing() || isDestroyed() || updateBtn == null) {
            return;
        }
        updateBtn.requestFocus();
        if (settingsScroll == null) {
            return;
        }
        settingsScroll.post(() -> {
            try {
                android.graphics.Rect kotak = new android.graphics.Rect();
                updateBtn.getDrawingRect(kotak);
                settingsScroll.offsetDescendantRectToMyCoords(updateBtn, kotak);
                settingsScroll.smoothScrollTo(0, kotak.top);
            } catch (Exception ignored) {
            }
        });
    }

    private void setBusy(final boolean busy) {
        uiBusy = busy;
        uiPost(() -> {
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
            toast(getString(R.string.masih_bekerja));
            return;
        }
        setBusy(true);

        boolean masuk = Util.jalankanLama(() -> {
            try {
                task.run();
            } catch (Throwable t) {

                if (t instanceof InterruptedException) {
                    try {
                        Thread.currentThread().interrupt();
                    } catch (Exception ignored) {
                    }
                }
                try {
                    appendUiLog("[app] Tugas gagal: " + t);
                } catch (Exception ignored) {
                }
                try {
                    toast(getString(R.string.tugas_gagal,
                    t.getMessage() == null ? t.toString() : t.getMessage()));
                } catch (Exception ignored) {
                }
            } finally {
                tugasBerjalan.set(false);
                setBusy(false);
            }
        });
        if (!masuk) {

            tugasBerjalan.set(false);
            setBusy(false);
            toast(getString(R.string.antrean_penuh));
        }
    }

    private void appendUiLog(String line) {
        ServerService.catatLog(line);

        long now = SystemClock.elapsedRealtime();
        if (now - lastUiLogRefresh > 500) {
            lastUiLogRefresh = now;
            ui.post(refreshLoop);
        }
    }

    static boolean perluKonfirmasiGantiPin(boolean pinAktif, boolean adaHash) {
        return pinAktif && adaHash;
    }

    static boolean aktivasiPinMasihBerlaku(int epPermintaan, int epTerbaru) {
        return epPermintaan == epTerbaru;
    }

    static boolean bolehDialogPin(boolean dialogBoleh, boolean finishing,
                                  boolean destroyed) {
        return dialogBoleh && !finishing && !destroyed;
    }

    private boolean adaHashPinTersimpan() {
        try {
            String h = TgBackup.amanString(getSharedPreferences(
                    ServerService.PREFS, MODE_PRIVATE), PinGate.KEY_PIN_HASH, "");
            return h != null && !h.trim().isEmpty();
        } catch (Exception e) {

            return true;
        }
    }

    private void tulisHashPin(final String pin, final int seq,
            final boolean pinAktif, final boolean beriKabar) {
        pinPending = pinExec.submit(() -> {
            String h;
            try {
                h = PinCrypto.hash(pin);
            } catch (Exception e) {
                h = null;
            }
            if (seq != pinSeq) {
                return;
            }
            if (h == null) {
                return;
            }

            if (!pinAktifJalan && pinAktif) {
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                        .edit().putString(PinGate.KEY_PIN_HASH, h).apply();
                if (beriKabar) {
                    toast(getString(R.string.pin_diperbarui));
                }
            }
        });
    }

    private void bersihkanPin(android.widget.EditText v) {
        try {
            if (v.getText() != null) {
                v.getText().clear();
            }
        } catch (Exception ignored) {
        }
        try {
            v.setText("");
        } catch (Exception ignored) {
        }
    }

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private void uiPost(Runnable r) {
        if (r == null) {
            return;
        }
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            r.run();
        });
    }

    private boolean jadwalBotTertunda = false;

    private final Runnable scheduleBotRunnable = () -> {
        jadwalBotTertunda = false;
        TgBot.schedule(SettingsActivity.this);
    };

    private void scheduleBotDebounced() {
        ui.removeCallbacks(scheduleBotRunnable);
        jadwalBotTertunda = true;
        ui.postDelayed(scheduleBotRunnable, 1000);
    }

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

    private final java.util.List<Runnable> tulisTundaAktif = new java.util.ArrayList<>();

    private void siramTulisTertunda() {
        java.util.List<Runnable> salin;
        synchronized (tulisTundaAktif) {
            if (tulisTundaAktif.isEmpty() && !jadwalBotTertunda) {
                return;
            }
            salin = new java.util.ArrayList<>(tulisTundaAktif);
            tulisTundaAktif.clear();
        }
        for (Runnable r : salin) {
            try {
                ui.removeCallbacks(r);
            } catch (Exception ignored) {
            }
            try {
                r.run();
            } catch (Exception ignored) {
            }
        }
        if (jadwalBotTertunda) {
            jadwalBotTertunda = false;
            try {
                ui.removeCallbacks(scheduleBotRunnable);
            } catch (Exception ignored) {
            }
            try {
                scheduleBotRunnable.run();
            } catch (Exception ignored) {
            }
        }
    }

    private class SimpleTextWatcher implements android.text.TextWatcher {
        private final String key;
        private String nilaiTerbaru = "";
        private int generasiTulis = 0;

        private final Runnable tugasTunda = this::tulisNilaiTunda;

        private void tulisNilaiTunda() {
            String nilai;
            int gen;
            synchronized (tulisTundaAktif) {
                nilai = nilaiTerbaru;
                gen = generasiTulis;
            }
            try {
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                        .edit().putString(key, nilai).apply();
            } catch (Exception ignored) {
            }
            synchronized (tulisTundaAktif) {
                if (gen == generasiTulis) {
                    tulisTundaAktif.remove(tugasTunda);
                }
            }
        }

        SimpleTextWatcher(String key) {
            this.key = key;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {

            synchronized (tulisTundaAktif) {
                nilaiTerbaru = s == null ? "" : s.toString();
                generasiTulis++;
                if (!tulisTundaAktif.contains(tugasTunda)) {
                    tulisTundaAktif.add(tugasTunda);
                }
            }
            ui.removeCallbacks(tugasTunda);
            ui.postDelayed(tugasTunda, 400);
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
        }
    }

    @Override
    protected void onDestroy() {

        DialogAman.tutup(this);
        try {
            siramTulisTertunda();
        } catch (Exception ignored) {
        }

        pinDialogBoleh = false;

        synchronized (kunciAktifPin) {
            epAktifPin++;
        }
        Runnable tundaPin = pinTunda;
        pinTunda = null;
        if (tundaPin != null) {
            ui.removeCallbacks(tundaPin);
            tundaPin.run();
        }
        sapuExportPlainBasi();

        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }
}
