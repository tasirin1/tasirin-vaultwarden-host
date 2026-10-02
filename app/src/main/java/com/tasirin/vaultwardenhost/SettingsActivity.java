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
import android.widget.ProgressBar;
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
    private static final int REQ_SHARE_CONFIG = 1004;
    private static final String DEFAULT_PORT = ServerService.DEFAULT_PORT;
    /** Wizard setup 3 langkah sudah pernah tampil/selesai. */
    private static final String KEY_WIZARD_DONE = "wizard_selesai";

    private final Handler ui = new Handler(Looper.getMainLooper());

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
    /** Hash PIN (PBKDF2 120rb iterasi) di worker agar tiap ketikan tak macetkan UI. */
    private final java.util.concurrent.ExecutorService pinExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int pinSeq;
    private volatile java.util.concurrent.Future<?> pinPending;
    /** Hash PIN terbaru dari worker yang belum disimpan ke prefs: prefs hanya
     *  menerima nilai settle agar PIN parsial tak jadi PIN valid bila app mati
     *  di tengah mengetik. */
    private volatile String pinHashSiap = null;
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
    private Button copyUrlBtn;
    private Button exportCfgBtn;
    private Button importCfgBtn;
    /** File config plaintext sementara yang menunggu dibagikan: dipertahankan
     *  hingga basi 10 menit (target async membaca setelah chooser kembali).
     *  Volatile karena ditulis worker exportConfig dan dibaca UI thread. */
    private volatile File exportPlainTertunda = null;
    /** Batas simpan file config plaintext sementara (10 menit, sapu basi). */
    static final long EXPORT_PLAIN_TTL_MS = 10L * 60 * 1000;
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
    private TextView adminStatus;
    private TextView tgStatus;
    private TextView adminError;
    private TextView tokenError;
    private TextView chatError;
    private Button certInfoToggle;
    private TextView certHintView;
    private LinearLayout secDetail;
    private TextView secRingkasan;
    private LinearLayout rawatDetail;
    private TextView rawatRingkasan;
    private Button rawatUbah;
    private LinearLayout tgDetail;
    private TextView tgRingkasan;
    private boolean secExpanded = false;
    private boolean rawatExpanded = false;
    private boolean tgExpanded = false;
    private TextView httpsBadge;
    private TextView dataDirError;
    private TextView portError;
    private TextView labelDataDir;
    private TextView labelPort;
    private TextView labelAdmin;
    private ProgressBar unduhBar;
    private android.content.res.ColorStateList warnaLabelBawaan;
    /** Status buka tiap seksi (gaya Download Manager, tersimpan di prefs). */
    private static final String KEY_SEC_SERVER = "sec_buka_server";
    private static final String KEY_SEC_KEAMANAN = "sec_buka_keamanan";
    private static final String KEY_SEC_RAWAT = "sec_buka_rawat";
    private static final String KEY_SEC_TELEGRAM = "sec_buka_telegram";
    private static final String KEY_SEC_LOG = "sec_buka_log";

    private String bundledVersion = "?";
    private String bundledRaw = null;
    private String appVersion = "";
    private String lastShownStatus = "";
    private String lastShownVersion = "";
    private String lastShownNet = "";
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

    private static volatile boolean unlocked = false;
    /** Kapan PIN terakhir cocok (jangkar grace); pindah activity tak memperpanjang. */
    private static volatile long unlockAt = 0;
    /** Kapan Settings terakhir pause (diagnostik, bukan jangkar grace). */
    private static volatile long pauseStamp = 0;
    private static final long PIN_GRACE_MS = 60_000;
    /** Guard agar onResume beruntun tak menumpuk dialog PIN. */
    private boolean pinDialogTampil = false;

    /** Status buka PIN via PinGate (satu sumber, simetris dengan MainActivity). */
    static boolean pinBaruSajaDibuka() {
        return PinGate.dalamGraceBersama();
    }

    /** Kapan PIN dibuka (jangkar grace bersama, tanpa perpanjangan). */
    static long kapanDibuka() {
        return PinGate.kapanBukaBersama();
    }

    /** Catat buka PIN sebagai milik bersama (tanpa panggil balik) agar tak rekursi. */
    static void catatPinDibuka() {
        PinGate.bukaKunciBersama();
        unlocked = true;
        unlockAt = PinGate.kapanBukaBersama();
        pauseStamp = unlockAt;
    }

    @Override
    // getPackageInfo lama sengaja agar satu jalur kode untuk API 21-32 (varian Flags butuh API 33+).
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        // Splash ditampilkan lewat theme manifest, ganti ke tema utama di sini.
        setTheme(R.style.Theme_TasirinVaultwardenHost);
        super.onCreate(savedInstanceState);
        TgBackup.healkanStringPrefs(this);
        TgBackup.migrateAutoPref(this);
        // Sisa export plaintext sesi yang mati (dibunuh saat chooser terbuka)
        // tak boleh mengendap di cache: sapu sekali saat buka.
        bersihkanExportPlainCache();
        // Privasi: nonaktifkan screenshot + preview recents dikosongkan
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_settings);

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
        startStopBawah = findViewById(R.id.startStopBawah);
        httpsBadge = findViewById(R.id.httpsBadge);
        dataDirError = findViewById(R.id.dataDirError);
        portError = findViewById(R.id.portError);
        labelDataDir = findViewById(R.id.labelDataDir);
        labelPort = findViewById(R.id.labelPort);
        labelAdmin = findViewById(R.id.labelAdmin);
        unduhBar = findViewById(R.id.unduhBar);
        warnaLabelBawaan = labelDataDir.getTextColors();
        Button randomAdminBtn = findViewById(R.id.randomAdmin);
        Button copyLoopbackBtn = findViewById(R.id.copyLoopback);
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
        secDetail = findViewById(R.id.secDetail);
        secRingkasan = findViewById(R.id.secRingkasan);
        rawatDetail = findViewById(R.id.rawatDetail);
        rawatRingkasan = findViewById(R.id.rawatRingkasan);
        rawatUbah = findViewById(R.id.rawatUbah);
        tgDetail = findViewById(R.id.tgDetail);
        tgRingkasan = findViewById(R.id.tgRingkasan);

        startStopBawah.setOnClickListener(v -> aksiStartStop());
        updateBtn.setOnClickListener(v -> runBusy(this::checkForUpdate));
        revertBtn.setOnClickListener(v -> confirm("Reset Binary",
                "Hapus binary tersimpan. Versi pilihan (atau terbaru bila tak dikunci)"
                        + " akan diunduh ulang otomatis saat Start berikutnya"
                        + " (perlu internet). Lanjutkan?",
                () -> runBusy(this::revertToBundled)));
        installCertBtn.setOnClickListener(v -> installCertificate());
        shareCaBtn = findViewById(R.id.shareCa);
        if (shareCaBtn != null) {
            shareCaBtn.setOnClickListener(v -> bagikanCa());
        }
        resetCertBtn = findViewById(R.id.resetCert);
        if (resetCertBtn != null) {
            resetCertBtn.setOnClickListener(v -> confirm("Reset Sertifikat",
                    "Hapus CA + sertifikat lama dan buat CA baru saat Start berikutnya. "
                            + "Semua HP wajib install ulang CA baru. Lanjutkan?",
                    () -> runBusy(this::resetSertifikat)));
        }
        updateWvBtn.setOnClickListener(v -> runWebVaultUpdate(true));
        backupDbBtn.setOnClickListener(v -> {
            // Baca widget di UI thread (bukan di worker runBusy) lalu validasi
            // seperti saveAndStart agar path berbahaya jatuh ke bawaan.
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
        Button settingsBackBtn = findViewById(R.id.settingsBack);
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
                + portEfektifUntukSalin(), "URL lokal disalin"));
        randomAdminBtn.setOnClickListener(v -> {
            adminTokenInput.setText(buatTokenAcak(new java.security.SecureRandom()));
            toast("Token acak dibuat — tekan Start agar berlaku.");
        });
        exportCfgBtn.setOnClickListener(v -> mintaExport());
        importCfgBtn.setOnClickListener(v -> pickImportFile());
        showAdminBtn.setOnClickListener(v -> togglePassword(adminTokenInput, showAdminBtn));
        showTgBtn.setOnClickListener(v -> togglePassword(tgTokenInput, showTgBtn));
        showPassBtn.setOnClickListener(v -> togglePassword(backupPassInput, showPassBtn));
        if (copyAdminBtn != null) {
            copyAdminBtn.setOnClickListener(v -> salinTeks(adminTokenInput.getText().toString(), "Admin Token"));
        }
        if (copyTgBtn != null) {
            copyTgBtn.setOnClickListener(v -> salinTeks(tgTokenInput.getText().toString(), "Bot token"));
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
        Button secUbah = findViewById(R.id.secUbah);
        if (secUbah != null) {
            secUbah.setOnClickListener(v -> {
                secExpanded = true;
                tampilkanDetailPenuh();
            });
        }
        if (rawatUbah != null) {
            rawatUbah.setOnClickListener(v -> {
                rawatExpanded = true;
                tampilkanDetailPenuh();
            });
        }
        Button tgUbah = findViewById(R.id.tgUbah);
        if (tgUbah != null) {
            tgUbah.setOnClickListener(v -> {
                tgExpanded = true;
                tampilkanDetailPenuh();
            });
        }
        aboutBtn.setOnClickListener(v -> showAboutDialog());

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
        pinInput.setText("");
        pinEnabledCheck.setChecked(TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false));
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
        tampilkanDetailPenuh();
        segarkanLabelVersi();

        autoStartCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_AUTO_START, checked).apply());
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
                java.util.concurrent.Future<?> basi = pinPending;
                if (basi != null && !basi.isDone()) {
                    basi.cancel(true);
                }
                // Hash ditahan di memori dulu: prefs hanya menerima nilai yang
                // sudah settle (800 ms tanpa ketikan) agar app yang mati di
                // tengah mengetik tak meninggalkan hash PIN parsial.
                // PIN mentah tak disimpan di field agar tak mengendap di heap.
                pinHashSiap = null;
                if (s.length() < 4) {
                    // PIN pendek bukan PIN valid: hapus hash DAN matikan PIN agar
                    // tak ada status pin_on=true tanpa hash (fail-open di kunci).
                    getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                            .edit().remove(PinGate.KEY_PIN_HASH).putBoolean(PinGate.KEY_PIN_ON, false).apply();
                    return;
                }
                final String pin = s.toString();
                final int seq = pinSeq;
                pinPending = pinExec.submit(() -> {
                    String h = PinCrypto.hash(pin);
                    if (seq != pinSeq) {
                        return;
                    }
                    pinHashSiap = h;
                    ui.postDelayed(() -> {
                        // Nomor urut cukup sebagai penanda settle; hash bersalt
                        // tak perlu pasangan plaintext di field. Hash otomatis
                        // hanya ditulis bila PIN aktif agar toggle mati tak
                        // meninggalkan hash basi di prefs.
                        if (seq == pinSeq && h.equals(pinHashSiap)
                                && pinEnabledCheck.isChecked()) {
                            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                                    .edit().putString(PinGate.KEY_PIN_HASH, h).apply();
                        }
                    }, 800);
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
                // Kembalikan centang dulu; lepas hanya bila user menekan Ya.
                pinCentangProgram = true;
                b.setChecked(true);
                pinCentangProgram = false;
                confirm(getString(R.string.pin_off_judul), getString(R.string.pin_off_pesan),
                        () -> {
                            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                                    .edit().putBoolean(PinGate.KEY_PIN_ON, false).apply();
                            pinCentangProgram = true;
                            b.setChecked(false);
                            pinCentangProgram = false;
                        });
                return;
            }
            String fieldPin = pinInput.getText() == null ? ""
                    : pinInput.getText().toString();
            if (fieldPin.length() < 4) {
                // Hash lama di prefs tak boleh dipakai: aktif dengan field
                // pendek/kosong menghidupkan PIN lama yang tak terlihat user.
                toast("Isi PIN dulu (minimal 4 karakter huruf/angka).");
                pinCentangProgram = true;
                b.setChecked(false);
                pinCentangProgram = false;
                return;
            }
            // Selalu hash ulang dari field saat itu di worker (PBKDF2 berat,
            // tak boleh di UI thread): tanpa memakai cache pinHashSiap agar
            // centang tak pernah memakai hash basi/parsial (kunci permanen).
            java.util.concurrent.Future<?> basiAktif = pinPending;
            if (basiAktif != null && !basiAktif.isDone()) {
                basiAktif.cancel(true);
            }
            final String pinBaru = fieldPin;
            final int seqBaru = ++pinSeq;
            pinHashSiap = null;
            pinCentangProgram = true;
            b.setChecked(false);
            pinCentangProgram = false;
            toast("PIN diproses...");
            pinPending = pinExec.submit(() -> {
                String h = PinCrypto.hash(pinBaru);
                if (seqBaru != pinSeq) {
                    return;
                }
                pinHashSiap = h;
                // Satu apply atomis: hash dan flag on ditulis bersama agar
                // crash di antaranya tak meninggalkan hash basi tanpa flag.
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                        .edit().putString(PinGate.KEY_PIN_HASH, h)
                        .putBoolean(PinGate.KEY_PIN_ON, true).apply();
                ui.post(() -> {
                    if (seqBaru != pinSeq) {
                        return;
                    }
                    pinCentangProgram = true;
                    pinEnabledCheck.setChecked(true);
                    pinCentangProgram = false;
                    toast("PIN aktif.");
                });
            });
        });

        // Izin storage untuk semua Android (biasa di 6-10, All files di 11+).
        StoragePerm.mintaIzinBilaPerlu(this, REQ_WRITE);

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
        TgBackup.schedule(this, TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false));
        // Remote kontrol via Telegram bot
        TgBot.schedule(this);
        // Tanpa wizard awal (gaya Download Manager: bawaan langsung benar).

    }

    @Override
    protected void onResume() {
        super.onResume();
        sapuExportPlainBasi();
        sapuSisaImpor();
        refreshActive = true;
        ui.post(this::refreshFromService);
        // Auto-lock PIN setiap kali app kembali ke depan
        maybeShowPinLock();
    }

    @Override
    protected void onPause() {
        super.onPause();
        siramTulisTertunda();
        refreshActive = false;
        // Jangan kunci langsung (pindah ke LogActivity bukan keluar app);
        // maybeShowPinLock mengunci bila jeda > PIN_GRACE_MS.
        pauseStamp = SystemClock.elapsedRealtime();
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

    private void saveAndStart() {
        String dataDir = dataDirInput.getText().toString().trim();
        String port = portInput.getText().toString().trim();
        String adminToken = adminTokenInput.getText().toString().trim();

        String dataDirEfektif = ServerService.amankanDataDir(dataDir);
        if ((!ServerService.dataDirAman(dataDir) || !ServerService.dataDirKanonisAman(dataDir))
                && !TextUtils.isEmpty(dataDir)) {
            toast("Folder data tidak valid, pakai bawaan.");
            appendUiLog("[app] Folder data tidak valid, pakai bawaan: " + ServerService.dataDirBawaanSegar());
        }
        // Folder eksternal tanpa izin pasti gagal writable — minta dulu, batalkan Start.
        if (!StoragePerm.siapStart(this, dataDirEfektif, REQ_WRITE)) {
            appendUiLog("[app] Start dibatalkan: izin penyimpanan belum diberikan.");
            return;
        }

        String portEfektif = TextUtils.isEmpty(port) ? DEFAULT_PORT : port.trim();
        int portNum = -1;
        try {
            portNum = Integer.parseInt(portEfektif);
        } catch (Exception ignored) {
        }
        if (portNum < 1 || portNum > 65535) {
            toast("Port harus angka 1-65535.");
            appendUiLog("[app] Port tidak valid: '" + port + "' - Start dibatalkan.");
            return;
        }

        SharedPreferences.Editor ed = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit();
        ed.putString(ServerService.KEY_DATA_DIR, dataDirEfektif);
        ed.putString(ServerService.KEY_PORT, portEfektif);
        ed.putString(ServerService.KEY_ADMIN_TOKEN, adminToken);
        ed.apply();
        if (portNum > 0 && ServerService.isPortBusy(portNum)) {
            if (ServerService.portButuhRoot(portNum)) {
                new AlertDialog.Builder(this)
                        .setTitle("Port " + portNum + " butuh akses root")
                        .setMessage("Port (" + portNum + ") di bawah 1024 hanya bisa"
                                + " dipakai dengan akses root.\n"
                                + "Ganti port ke >= 1024 (mis. 8088) di atas.")
                        .setPositiveButton("Oke", null)
                        .show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Port " + portNum + " sedang dipakai")
                    .setMessage("Port utama (" + portNum + ") sudah dipakai proses lain.\n"
                            + "Stop aplikasi lain yang memakainya, ganti port di atas, "
                            + "atau restart HP dulu.")
                    .setPositiveButton("Oke", null)
                    .show();
            return;
        }

        if (!webVaultReady(dataDirEfektif)) {
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
            int persen = Updater.persenUnduhan(dl);
            unduhBar.setVisibility(persen >= 0 ? View.VISIBLE : View.GONE);
            if (persen >= 0) {
                unduhBar.setProgress(persen);
            }
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
        unduhBar.setVisibility(View.GONE);
        String btnText = running ? getString(R.string.stop)
                : getString(R.string.start);
        if (!btnText.equals(startStopBawah.getText().toString())) {
            startStopBawah.setText(btnText);
            startStopBawah.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    running ? R.drawable.ic_stop : R.drawable.ic_play, 0, 0, 0);
        }

        // Peringatan bila setting diubah tapi server belum di-restart
        boolean changed = false;
        if (running) {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String d = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
            if (d == null || d.trim().isEmpty()) {
                d = ServerService.dataDirBawaanSegar();
            }
            String p = ServerService.effectivePort(sp);
            String a = TgBackup.amanString(sp, ServerService.KEY_ADMIN_TOKEN, "");
            if (a == null) {
                a = "";
            }
            String rd = ServerService.runningDataDir == null ? "" : ServerService.runningDataDir;
            String rp = ServerService.runningPort == null ? "" : ServerService.runningPort;
            String ra = ServerService.runningAdminToken == null ? "" : ServerService.runningAdminToken;
            boolean bedaData = !d.trim().equals(rd.trim());
            boolean bedaPort = !p.trim().equals(rp.trim());
            boolean bedaAdmin = !ServerService.sidikTokenAdmin(a).equals(ra == null ? "" : ra.trim());
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

        // Peringatan bila update binary tersedia tapi belum dipasang
        boolean updAvail = false;
        if (pendingVersion != null) {
            SharedPreferences psp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String real = Updater.parseBinaryVersion(ServerService.binaryVersion);
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
        httpsBadge.setText(teksBadgeHttps(running, cert,
                getString(R.string.https_badge_on), getString(R.string.https_badge_none)));
        if (!full.equals(lastShownVersion)) {
            versionView.setText(full);
            lastShownVersion = full;
        }

        String net = ServerService.localUrl(this);
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

    // Html.fromHtml lama untuk API 21-23; jalur modern dipakai bila API >= 24.
    @SuppressWarnings("deprecation")
    private void showAboutDialog() {
        String dataDir = dataDirInput.getText().toString().trim();
        if (TextUtils.isEmpty(dataDir)) {
            dataDir = ServerService.dataDirBawaanSegar();
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
        tv.setLinkTextColor(getResources().getColor(R.color.accent));
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
                    dataDir = ServerService.dataDirBawaanSegar();
                }
                cert = new File(dataDir, "tls/ca.pem");
            }
            if (!cert.exists()) {
                toast("CA belum ada. Tekan Start dulu agar CA dibuat.");
                return;
            }
            Uri uri = Uri.parse("content://" + FileShareProvider.AUTHORITY
                    + Uri.encode(cert.getAbsolutePath(), "/"));
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
                    dataDir = ServerService.dataDirBawaanSegar();
                }
                cert = new File(dataDir, "tls/ca.pem");
            }
            if (!cert.exists()) {
                toast("CA belum ada. Tekan Start dulu agar CA dibuat.");
                return;
            }
            Uri uri = Uri.parse("content://" + FileShareProvider.AUTHORITY
                    + Uri.encode(cert.getAbsolutePath(), "/"));
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

    /** Reset sertifikat via tombol Settings: hapus CA + leaf, lapor ke log UI. */
    private void resetSertifikat() {
        try {
            String msg = TgBackup.resetSertifikat(this);
            lastCertCheck = 0;
            certLine = "";
            toast(msg);
            appendUiLog("[app] " + msg);
        } catch (Exception e) {
            toast("Reset sertifikat gagal: " + e.getMessage());
            appendUiLog("[app] Reset sertifikat gagal: " + e.getMessage());
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
            // Dipanggil dari worker thread AutoUpdate: post ke UI agar tulis
            // terkurung di UI thread (baca banner/status juga di UI).
            @Override public void atur(String versi) {
                try {
                    ui.post(() -> pendingVersion = versi);
                } catch (Exception e) {
                    pendingVersion = versi;
                }
            }
        }, true);
    }

    /** Tawarkan update web vault sekali per versi bila versinya beda dari server. */
    private void autoOfferWebVaultUpdate() {
        try {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
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
            if (TgBackup.amanBoolean(sp, key, false)) {
                return;
            }
            sp.edit().putBoolean(key, true).apply();
            final String pinWvDlg = Updater.kuncianWebVault(this);
            final boolean dikunciDlg = pinWvDlg != null && !pinWvDlg.isEmpty();
            ui.post(() -> new AlertDialog.Builder(this)
                    .setTitle("Update Web Vault tersedia")
                    .setMessage("Web vault saat ini v" + updated
                            + ", sedangkan server v" + server + ".\n\n"
                            + (dikunciDlg
                                    ? "Versi web vault dikunci ke v" + pinWvDlg
                                            + ": selaraskan sekarang? (unduh sekali ~35 MB, "
                                            + "berlaku setelah server di-restart)"
                                    : "Update web vault sekarang? (unduh sekali ~35 MB, "
                                            + "berlaku setelah server di-restart)"))
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

    private void backupDatabase(String dataDirMentah) {
        try {
            String dataDir = dataDirMentah == null ? "" : dataDirMentah.trim();
            if (TextUtils.isEmpty(dataDir)) {
                dataDir = ServerService.dataDirBawaanSegar();
            }
            if (!ServerService.dataDirAman(dataDir)
                    || !ServerService.dataDirKanonisAman(dataDir)) {
                toast("Folder data tidak valid - dikembalikan ke bawaan.");
                appendUiLog("[app] Folder data tidak valid: '" + dataDir + "' - direset ke bawaan.");
                dataDir = ServerService.dataDirBawaanSegar();
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

            // Tolak backup DB korup diam-diam seperti jalur Telegram.
            try {
                String rusakDb = TgBackup.cekIntegritasDb(dbFile);
                if (rusakDb != null) {
                    toast("Database korup (" + rusakDb + ") - backup dibatalkan.");
                    appendUiLog("[app] Backup dibatalkan: DB korup (" + rusakDb + ").");
                    return;
                }
            } catch (Throwable abaikan) {
                // JVM unit test tanpa SQLite Android: lanjut tanpa cek.
            }
            String timestamp = TgBackup.stempelUnik();
            File backup = new File(backupDir, "db-backup-" + timestamp + ".zip");
            // Tulis ke tmp + rename agar zip parsial tak masuk retensi.
            File tmpZip = new File(backupDir, backup.getName() + ".tmp");

            // Zip DB + WAL/SHM agar konsisten walau server sedang berjalan.
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
                    throw new java.io.IOException("Gagal mengganti backup lama.");
                }
                if (!tmpZip.renameTo(backup)) {
                    throw new java.io.IOException("Gagal memasang backup.");
                }
            } catch (Exception e) {
                try {
                    tmpZip.delete();
                } catch (Exception ignored) {
                }
                throw e;
            }
            // Verifikasi isi DB dari hasil ekstrak agar backup robek
            // tak tersimpan diam-diam (magic saja tak cukup).
            File tmpIsi = new File(getCacheDir(), "verifikasi-isi-db.sqlite3");
            try {
                String galatIsi = TgBackup.verifikasiIsiDbZip(backup, tmpIsi);
                if (galatIsi != null) {
                    backup.delete();
                    toast("Backup rusak (" + galatIsi + ") - dibuang, coba lagi.");
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
            String dir = dataDirInput.getText() == null ? ""
                    : dataDirInput.getText().toString().trim();
            if (TextUtils.isEmpty(dir)) {
                dir = ServerService.dataDirBawaanSegar();
            }
            final String dataDir = dir;
            confirm("Restore Database",
                    "Database saat ini akan diganti dengan file yang dipilih. "
                            + "Backup otomatis dibuat dulu. Lanjutkan?",
                    () -> runBusy(() -> restoreDatabase(uri, dataDir)));
        } else if (requestCode == REQ_SHARE_CONFIG) {
            // Chooser kembali bukan tanda target selesai membaca: aplikasi async
            // (Gmail/Drive) mengunggah di latar setelah kita kembali. Jangan
            // hapus di sini; sapu basi (<10 menit dipertahankan, lihat EXPORT_PLAIN_TTL_MS).
            sapuExportPlainBasi();
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

    private void restoreDatabase(Uri uri, String dataDirMentah) {
        // Satu restore dalam satu waktu (lihat kunciRestore): bot /restore dan
        // tombol UI menulis folder data yang sama.
        if (!TgBackup.kunciRestore()) {
            toast("Restore lain sedang berjalan, coba lagi sebentar.");
            appendUiLog("[app] Restore ditolak: restore lain sedang berjalan.");
            return;
        }
        try {
            restoreDatabaseIsi(uri, dataDirMentah);
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
            toast("Folder data tidak valid - dikembalikan ke bawaan.");
            appendUiLog("[app] Folder data tidak valid: '" + dataDir + "' - direset ke bawaan.");
            dataDir = ServerService.dataDirBawaanSegar();
        }
        File dbFile = new File(dataDir, "db.sqlite3");
        File preBackup = null;
        // Scope method (bukan dalam try): dipakai juga di catch-all rollback.
        // Diisi jalur zip di bawah; null di jalur non-zip (rollback TLS = no-op,
        // prefs tak ada yang diterapkan).
        java.util.Map<String, byte[]> tlsAsal = null;
        JSONObject zipCfg = null;
        try {
            // Hentikan server dulu: menimpa SQLite yang hidup merusak DB.
            if (ServerService.isProcessAlive()
                    && !ServerService.stopAndWait(SettingsActivity.this, 8000)) {
                toast("Server gagal berhenti - restore dibatalkan agar DB tidak korup.");
                appendUiLog("[app] Restore dibatalkan: server masih berjalan.");
                return;
            }
            if (ServerService.isProcessAlive()) {
                toast("Server masih berjalan - restore dibatalkan agar DB tidak korup.");
                appendUiLog("[app] Restore dibatalkan: server masih berjalan.");
                return;
            }
            if (dbFile.exists()) {
                File backupDir = new File(dataDir, "backups");
                if (!backupDir.exists()) {
                    backupDir.mkdirs();
                }
                // Satukan WAL ke DB utama agar salinan pengaman tak basi.
                TgBackup.checkpointWal(dbFile);
                String ts = TgBackup.stempelUnik() + "-pre";
                preBackup = new File(backupDir, "db-backup-" + ts + ".sqlite3");
                TgBackup.copyFile(dbFile, preBackup);
                TgBackup.cleanupOldBackups(backupDir);
                // Buang -wal/-shm lama: milik DB lama, korup bila ditempel ke DB baru.
                TgBackup.hapusWalShm(new File(dataDir));
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
                    // Backup lokal .zip berisi db.sqlite3 (+wal/shm);
                    // backup lengkap juga memuat tls/* + app-config.json.
                    File dataFolder = new File(dataDir);
                    tlsAsal = TgBackup.potretTls(new File(dataFolder, "tls"));
                    String canonBase = dataFolder.getCanonicalPath();
                    String awalanAman = canonBase + File.separator;
                    long totalUnzip = 0;
                    int jumlahEntri = 0;
                    ZipInputStream zis = new ZipInputStream(in);
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        // Hitung semua entri sejak awal agar zip berisi ribuan
                        // entri sampah tetap kena batas anti zip-bomb.
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
                        // Allowlist satu pintu bersama restore Telegram
                        // (TgBackup.bolehTulisRestore) agar tak drift.
                        if (!TgBackup.bolehTulisRestore(name)) {
                            zis.closeEntry();
                            continue;
                        }
                        if ("tls".equals(name) && !entry.isDirectory()) {
                            // "tls" hanya sah sebagai direktori (lihat restore Telegram).
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
                            }
                        }
                        zis.closeEntry();
                        if (!entry.isDirectory() && name.equals("db.sqlite3")) {
                            restored = true;
                        }
                    }
                    // Efek-samping (sinkron TLS + prefs) dikerjakan SETELAH
                    // validasi di bawah: restore ditolak tak boleh mengubah apa pun.
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
                        if (r <= 0) {
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
                    long salin = off;
                    try (FileOutputStream fos = new FileOutputStream(dbFile)) {
                        fos.write(head, 0, off);
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            salin += len;
                            if (salin > Util.BATAS_UNZIP_RESTORE) {
                                throw new java.io.IOException("File database melebihi batas "
                                        + Util.BATAS_UNZIP_RESTORE + " byte.");
                            }
                            fos.write(buf, 0, len);
                        }
                    }
                    if (salin < 512) {
                        throw new java.io.IOException("File database terpotong (bukan SQLite utuh).");
                    }
                    // WAL lama milik DB lama: buang agar tak ditempel ke DB baru.
                    TgBackup.hapusWalShm(new File(dataDir));
                    restored = true;
                }
            }
            if (!restored) {
                // Zip berisi -wal/-shm/tls tanpa db.sqlite3: WAL asing sudah
                // tertulis menimpa milik DB lama — buang agar tak ditempel
                // ke DB lama saat Start (selaras restore Telegram). TLS
                // yang ikut tertimpa dikembalikan dari potret.
                TgBackup.hapusWalShm(new File(dataDir));
                TgBackup.kembalikanTls(new File(dataDir, "tls"), tlsAsal);
                toast("File backup tidak berisi db.sqlite3.");
                appendUiLog("[app] Restore gagal: file zip tanpa db.sqlite3");
                return;
            }
            if (!TgBackup.isSqliteFile(dbFile)) {
                if (preBackup != null && preBackup.exists()) {
                    TgBackup.hapusWalShm(new File(dataDir));
                    TgBackup.copyFile(preBackup, dbFile);
                } else {
                    dbFile.delete();
                }
                TgBackup.kembalikanTls(new File(dataDir, "tls"), tlsAsal);
                toast("Backup rusak (bukan SQLite) - database lama dikembalikan.");
                appendUiLog("[app] Restore gagal: header SQLite tidak cocok, rollback.");
                return;
            }
            try {
                String rusak = TgBackup.cekIntegritasDb(dbFile);
                if (rusak != null) {
                    if (preBackup != null && preBackup.exists()) {
                        TgBackup.hapusWalShm(new File(dataDir));
                        TgBackup.copyFile(preBackup, dbFile);
                    } else {
                        dbFile.delete();
                    }
                    TgBackup.kembalikanTls(new File(dataDir, "tls"), tlsAsal);
                    toast("Backup rusak (DB korup) - database lama dikembalikan.");
                    appendUiLog("[app] Restore gagal: quick_check korup (" + rusak + "), rollback.");
                    return;
                }
            } catch (Throwable abaikan) {
                // Unit test JVM tanpa SQLite Android: lewati quick_check.
            }
            // Restore valid: baru sekarang identitas + pengaturan ikut berganti.
            // Runtime membaca TLS internal dulu: sinkronkan hasil restore agar
            // identitas server benar-benar berganti (bukan memakai CA lama diam-diam).
            TgBackup.sinkronTlsKeInternal(SettingsActivity.this, new File(dataDir));
            if (zipCfg != null) {
                TgBackup.applyPrefsFromJson(SettingsActivity.this,
                        zipCfg.optJSONObject("prefs"));
                sanitizePortPref();
                ui.post(() -> {
                    reloadSettingsFromPrefs();
                    SharedPreferences sp2 = getSharedPreferences(
                            ServerService.PREFS, MODE_PRIVATE);
                    TgBackup.schedule(SettingsActivity.this,
                            TgBackup.amanBoolean(sp2, TgBackup.KEY_TG_AUTO, false));
                    TgBot.schedule(SettingsActivity.this);
                    appendUiLog("[app] Pengaturan dari backup ikut diterapkan.");
                });
            }
            toast("Database direstore. Restart server untuk memakai.");
            appendUiLog("[app] DB direstore. Ukuran: " + dbFile.length() + " bytes");
        } catch (Exception e) {
            // Tulis parsial (mis. batas ukuran) wajib dikembalikan dari salinan pengaman.
            // Tanpa salinan (install baru), buang DB parsial agar tak dipakai saat Start.
            try {
                TgBackup.kembalikanTls(new File(dataDir, "tls"), tlsAsal);
                if (preBackup != null && preBackup.exists()) {
                    TgBackup.hapusWalShm(new File(dataDir));
                    TgBackup.copyFile(preBackup, dbFile);
                    appendUiLog("[app] Restore gagal: database lama dikembalikan.");
                } else {
                    TgBackup.hapusWalShm(new File(dataDir));
                    dbFile.delete();
                    appendUiLog("[app] Restore gagal: file parsial dibuang.");
                }
            } catch (Exception ignored) {
            }
            toast("Gagal restore: " + e.getMessage());
            appendUiLog("[app] Gagal restore: " + e);
        }
    }

    // ─── Restore dari Telegram ──────────────────────────────────────────

    private void restoreFromTelegram() {
        String inputDir = dataDirInput.getText().toString().trim();
        if (!TextUtils.isEmpty(inputDir)) {
            // Sanitasi seperti saveAndStart: path berbahaya jatuh ke bawaan agar
            // prefs tak keracunan sebelum restore berjalan.
            String aman = ServerService.amankanDataDir(inputDir);
            if (!ServerService.dataDirAman(inputDir) || !ServerService.dataDirKanonisAman(inputDir)) {
                toast("Folder data tidak valid, pakai bawaan.");
            }
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .putString(ServerService.KEY_DATA_DIR, aman).apply();
        }
        runBusy(() -> {
            File tmp = new File(getCacheDir(), "vwtg-restore.zip");
            try {
                String name = TgBackup.downloadLastBackup(SettingsActivity.this, tmp);
                final String fname = name;
                // Arsip terenkripsi tetap utuh sampai user menekan Ya; dekrip
                // dikerjakan sesudah konfirmasi agar plaintext tak mengendap
                // di cache bila dialog tak jadi dijawab.
                ui.post(() -> confirm("Restore dari Telegram",
                        "Gunakan backup '" + fname + "'? Server akan dihentikan dulu. Lanjutkan?",
                        () -> runBusy(() -> restoreTelegramTerkonfirmasi(tmp))));
            } catch (Exception e) {
                try {
                    tmp.delete();
                } catch (Exception ignored) {
                }
                toast("Gagal ambil backup: " + e.getMessage());
                appendUiLog("[tg] Gagal ambil backup: " + e);
            }
        });
    }

    // Dekrip (bila perlu) + restore sesudah user konfirmasi; arsip unduhan
    // maupun plaintext selalu dibersihkan dari cache sesudahnya.
    private void restoreTelegramTerkonfirmasi(File unduhan) {
        File plain = new File(getCacheDir(), "vwtg-restore-dec.zip");
        try {
            File zip = unduhan;
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String pass = TgBackup.amanString(sp, TgBackup.KEY_TG_PASS, "");
            if (TgBackup.isEncrypted(unduhan)) {
                if (pass == null || pass.trim().isEmpty()) {
                    toast("Backup terenkripsi \u2014 isi password backup dulu.");
                    return;
                }
                TgBackup.decryptFile(unduhan, plain, pass.trim());
                zip = plain;
            }
            restoreFromZip(zip);
        } catch (Exception e) {
            toast("Gagal restore: " + e.getMessage());
            appendUiLog("[app] Gagal restore: " + e);
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

    // Restore Telegram didelegasikan ke TgBackup agar satu implementasi:
    // stop server, pre-backup, validasi SQLite + rollback, terapkan pengaturan,
    // dan jaga identitas bot. Folder yang diketik (belum di-Start) disimpan
    // dulu ke prefs di UI thread agar dipakai sebagai folder tujuan.
    private void restoreFromZip(File zip) {
        if (!TgBackup.kunciRestore()) {
            toast("Restore lain sedang berjalan, coba lagi sebentar.");
            appendUiLog("[app] Restore ditolak: restore lain sedang berjalan.");
            return;
        }
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
        } finally {
            TgBackup.lepasRestore();
        }
    }

    // ─── Export / Import pengaturan ─────────────────────────────────────

    /** Konfirmasi export: tanpa password backup, file berupa plaintext di folder
     *  backups (terbaca app lain) — ingatkan eksplisit sebelum tulis. */
    private void mintaExport() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String pass = TgBackup.amanString(sp, TgBackup.KEY_TG_PASS, "");
        boolean terkunci = pass != null && !pass.trim().isEmpty();
        confirm("Export Pengaturan",
                "File tidak membawa token/PIN/password (tetap di perangkat ini)."
                        + (terkunci ? " File terenkripsi dengan password backup."
                                : " Tanpa password backup file ini PLAINTEXT dan terbaca"
                                        + " aplikasi lain — simpan hati-hati.")
                        + " Tetap jangan bagikan ke orang lain. Lanjutkan?",
                () -> {
                    // Baca widget di UI thread (bukan di worker runBusy).
                    String dir = dataDirInput.getText() == null ? ""
                            : dataDirInput.getText().toString();
                    runBusy(() -> exportConfig(dir));
                });
    }

    /** Sapu sisa config plaintext sementara di cache internal. File pending yang
     *  masih segar (<10 menit) dilewati: target berbagi async (Gmail/Drive) membaca
     *  stream setelah chooser kembali, jadi hapus langsung membuat kirim gagal.
     *  Pembersihan mengandalkan sapu basi di onResume/onDestroy. */
    private void bersihkanExportPlainCache() {
        try {
            File pending = exportPlainTertunda;
            String pendingPath = pending == null ? null : pending.getAbsolutePath();
            File[] sisa = getCacheDir().listFiles();
            if (sisa != null) {
                for (File f : sisa) {
                    String n = f.getName();
                    if (n.startsWith("app-config-") && n.endsWith(".json")) {
                        boolean pendingSegar = pendingPath != null
                                && pendingPath.equals(f.getAbsolutePath())
                                && System.currentTimeMillis() - exportPlainPada < EXPORT_PLAIN_TTL_MS;
                        if (pendingSegar) {
                            continue;
                        }
                        // Hanya sapu yang basi agar target async tak kehilangan file.
                        long umur = System.currentTimeMillis() - f.lastModified();
                        if (umur < 0 || umur > EXPORT_PLAIN_TTL_MS) {
                            f.delete();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Sapu file config plaintext yang sudah basi (best-effort, dipakai
     *  onResume/onDestroy). File pending yang masih segar (<10 menit) dipertahankan. */
    private void sapuExportPlainBasi() {
        try {
            File pending = exportPlainTertunda;
            if (pending != null
                    && System.currentTimeMillis() - exportPlainPada >= EXPORT_PLAIN_TTL_MS) {
                pending.delete();
                exportPlainTertunda = null;
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
                toast("Folder data tidak valid - dikembalikan ke bawaan.");
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
            // Bersihkan export plaintext lama di kedua cabang agar cache tak
            // menumpuk (sebelumnya hanya cabang plaintext yang membersihkan).
            bersihkanExportPlainCache();
            // Kredensial tak ikut export (tetap di perangkat); enkripsi bila password backup diisi.
            String pass = TgBackup.amanString(sp, TgBackup.KEY_TG_PASS, "");
            final File out;
            final String mime;
            if (pass != null && !pass.trim().isEmpty()) {
                // Plaintext sementara di cache internal (bukan storage publik)
                // agar pemindai media/app lain tak sempat membacanya.
                File plain = new File(getCacheDir(), "app-config-" + ts + ".json");
                File enc = new File(backupDir, "app-config-" + ts + ".json.enc");
                try {
                    try (FileOutputStream fos = new FileOutputStream(plain)) {
                        fos.write(bytes);
                    }
                    TgBackup.encryptFile(plain, enc, pass.trim());
                } finally {
                    // Jangan sisakan config plaintext sementara bila enkripsi gagal.
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
            final String path = out.getAbsolutePath();
            final boolean plain = !path.endsWith(".enc");
            if (plain) {
                exportPlainTertunda = new File(path);
                exportPlainPada = System.currentTimeMillis();
            }
            ui.post(() -> {
                Uri uri = Uri.parse("content://" + FileShareProvider.AUTHORITY + Uri.encode(path, "/"));
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    // Via result: plaintext dihapus saat user kembali dari
                    // chooser agar tak mengendap di cache (target membaca
                    // stream saat ia foreground, sebelum kita kembali).
                    startActivityForResult(
                            Intent.createChooser(send, "Bagikan file konfigurasi"),
                            REQ_SHARE_CONFIG);
                } catch (Exception e2) {
                    exportPlainTertunda = null;
                    toast("File tersimpan: " + path);
                    appendUiLog("[app] Config tersimpan (bagi gagal): " + path);
                    return;
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
            String capImpor = TgBackup.stempelUnik();
            File tmp = new File(getCacheDir(), "vwcfg-import-" + capImpor + ".bin");
            // Stream dibuka langsung di try-with-resources: bila FileOutputStream
            // gagal dibuat, stream tetap tertutup (lolos cek Recycle lint).
            try (InputStream awal = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                if (awal == null) {
                    toast("Gagal import config: file tidak bisa dibuka.");
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
                        throw new java.io.IOException(
                                "File terlalu besar (>512 KB) - bukan config valid.");
                    }
                    fos.write(buf, 0, n);
                }
            }
            File src = tmp;
            if (TgBackup.isEncrypted(tmp)) {
                SharedPreferences sp0 = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
                String pass0 = TgBackup.amanString(sp0, TgBackup.KEY_TG_PASS, "");
                if (pass0 == null || pass0.trim().isEmpty()) {
                    // Tanpa password perangkat, langsung tanya password asal
                    // (file bisa dari password mana pun, bukan harus kini).
                    tanyaPasswordImpor(tmp, capImpor,
                            "Config terenkripsi - masukkan password saat file dibuat.");
                    return;
                }
                File plain = new File(getCacheDir(), "vwcfg-import-" + capImpor + "-dec.json");
                try {
                    TgBackup.decryptFile(tmp, plain, pass0.trim());
                } catch (Exception e) {
                    // Password perangkat tak cocok (file terenkripsi password
                    // lama): tanya password asal tanpa mengubah password perangkat.
                    appendUiLog("[app] Import: password perangkat tak cocok, tanya password asal");
                    tanyaPasswordImpor(tmp, capImpor,
                            "Password perangkat tak cocok - masukkan password saat file dibuat.");
                    return;
                }
                tmp.delete();
                src = plain;
            }
            terapkanImporJson(src);
        } catch (Exception e) {
            sapuSisaImpor();
            toast("Gagal import config: " + e.getMessage());
            appendUiLog("[app] Gagal import config: " + e);
        }
    }

    /** Terapkan berkas config plaintext hasil impor/dekrip. */
    private void terapkanImporJson(File src) {
        try {
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
            sp2.edit().putBoolean(KEY_WIZARD_DONE, true).apply();
            TgBackup.schedule(SettingsActivity.this,
                    TgBackup.amanBoolean(sp2, TgBackup.KEY_TG_AUTO, false));
            TgBot.schedule(SettingsActivity.this);
            toast("Pengaturan diimpor. Tekan Start agar berlaku.");
            appendUiLog("[app] Config import selesai.");
        });
        } catch (Exception e) {
            sapuSisaImpor();
            toast("Gagal import config: " + e.getMessage());
            appendUiLog("[app] Gagal import config: " + e);
        }
    }

    /** Minta password asal file config terenkripsi lalu coba impor.
     *  File dari password lama tak bisa dibuka password perangkat kini.
     *  Password asal hanya dipakai dekrip; password perangkat tak diubah
     *  (applyPrefsFromJson mempertahankan tg_pass perangkat). */
    private void tanyaPasswordImpor(final File tmpEnkrip, final String capImpor,
            String alasan) {
        try {
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setMaxLines(1);
            final AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Password file config")
                    .setMessage(alasan)
                    .setView(input)
                    .setPositiveButton("Coba impor", null)
                    .setNegativeButton("Batal", (d, w) -> {
                        try {
                            tmpEnkrip.delete();
                        } catch (Exception ignored) {
                        }
                    })
                    .create();
            dialog.setCanceledOnTouchOutside(false);
            // Back menutup dialog tanpa lewat tombol Batal: sapu tmp terenkripsi
            // juga di sini agar vwcfg-import-*.bin tak mengendap di cache.
            // Aman: jalur sukses/Batal sudah menghapusnya duluan (no-op ganda).
            dialog.setOnDismissListener(d -> {
                try {
                    tmpEnkrip.delete();
                } catch (Exception ignored) {
                }
            });
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        String coba = input.getText().toString();
                        if (coba == null || coba.trim().isEmpty()) {
                            input.setError("Isi password dulu");
                            return;
                        }
                        try {
                            File plain = new File(getCacheDir(),
                                    "vwcfg-import-" + capImpor + "-dec.json");
                            TgBackup.decryptFile(tmpEnkrip, plain, coba.trim());
                            tmpEnkrip.delete();
                            dialog.dismiss();
                            terapkanImporJson(plain);
                        } catch (Exception e) {
                            input.setError("Password salah / file rusak");
                            appendUiLog("[app] Import ditolak: password asal tak cocok");
                        }
                    }));
            dialog.show();
        } catch (Exception e) {
            try {
                tmpEnkrip.delete();
            } catch (Exception ignored) {
            }
            toast("Gagal import config: " + e.getMessage());
        }
    }

    /** Sapu sisa file import config (pola vwcfg-import*, termasuk nama tetap lama). */
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

    /** Muat ulang isi form dari prefs (dipakai setelah import config). */
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
        pinEnabledCheck.setChecked(TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false));
        validasiInline();
    }

    private void copyLocalUrl() {
        salinTeks(ServerService.localUrl(this), "URL jaringan disalin");
    }

    /** Port dari form (atau bawaan bila kosong) untuk tombol salin URL lokal. */
    private String portEfektifUntukSalin() {
        String p = portInput.getText().toString().trim();
        return p.isEmpty() ? DEFAULT_PORT : p;
    }

    /** Baca maksimal max byte; lempar bila lebih (tolak file raksasa agar tidak OOM). */
    private static byte[] readCapped(InputStream in, int max) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
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
        String p;
        try {
            p = TgBackup.amanString(sp, ServerService.KEY_PORT, DEFAULT_PORT);
        } catch (ClassCastException e) {
            // Port telanjur tersimpan bukan-String (impor lama/config manual):
            // kembalikan default agar Settings/Start tak crash berulang.
            sp.edit().putString(ServerService.KEY_PORT, DEFAULT_PORT).apply();
            return;
        }
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
        if (!TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false)) {
            // PIN mati: buang status buka basi agar PIN yang diaktifkan lagi
            // dalam grace lama tak dianggap sudah dibuka tanpa entri baru.
            PinGate.kunciBersama();
            unlocked = false;
            unlockAt = 0;
            return;
        }
        // Sudah dibuka di layar lain dalam grace: jangan minta lagi (tanpa perpanjangan).
        if (PinGate.dalamGraceBersama()) {
            unlocked = true;
            unlockAt = PinGate.kapanBukaBersama();
            return;
        }
        long deltaGrace = SystemClock.elapsedRealtime() - unlockAt;
        if (unlocked && deltaGrace >= 0 && deltaGrace < PIN_GRACE_MS) {
            return;
        }
        unlocked = false;
        final String pinHash = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
        if (pinHash == null || pinHash.isEmpty()) {
            // PIN aktif tanpa hash (mis. prefs rusak): matikan PIN + catat agar
            // tak terbuka diam-diam tanpa kunci (fail-open).
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
                    final android.content.Context appCtx = getApplicationContext();
                    new Thread(() -> {
                        boolean cocok = PinCrypto.verify(pinHash, entered);
                        PinGate.catatHasil(appCtx, cocok,
                                System.currentTimeMillis());
                        if (cocok && PinCrypto.perluUpgradeHash(pinHash)) {
                            // Migrasi hash lama/lemah ke PBKDF2 120k (sudah di worker).
                            sp.edit().putString(PinGate.KEY_PIN_HASH, PinCrypto.hash(entered)).apply();
                        }
                        final boolean hasil = cocok;
                        ui.post(() -> {
                            ok.setEnabled(true);
                            if (hasil) {
                                PinGate.bukaKunciBersama();
                                unlocked = true;
                                unlockAt = PinGate.kapanBukaBersama();
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
            String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
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
            String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
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
            String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
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
        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
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
            // Shim dulu agar uji --version di dalam tryUpdate lolos di kernel lama.
            String imbuhShim = "";
            if (KernelCompat.isLegacyDevice(KernelCompat.kernelSekarang())) {
                try {
                    Updater.ensureShimFile(this);
                    imbuhShim = " Shim getrandom siap.";
                } catch (Exception se) {
                    imbuhShim = " Shim gagal: " + se.getMessage();
                }
            }
            String msg = Updater.tryUpdate(this) + imbuhShim;
            if (Updater.binaryBerubah(msg)) {
                pendingVersion = null;
            }
            appendUiLog("[app] " + msg);
            toast(Updater.binaryBerubah(msg)
                    ? msg + " Tekan Start untuk memakai."
                    : msg);
        } catch (Exception e) {
            toast("Gagal cek update: " + e.getMessage());
            appendUiLog("[app] Gagal cek update: " + e);
        }
    }

    // ─── Pilih versi binary / web-vault (kunci versi) ───────────────────

    /** Teks tombol pilih versi: "Terbaru (otomatis)" atau "vX (terkunci)". */
    private void segarkanLabelVersi() {
        ui.post(() -> {
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

    /** Sufiks ringkasan kartu rawat bila ada versi dikunci. */
    private String teksKuncian() {
        try {
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String b = Updater.normalisasiPinVersi(
                    TgBackup.amanString(sp, ServerService.KEY_BIN_PILIH, ""));
            String w = Updater.normalisasiPinVersi(
                    TgBackup.amanString(sp, ServerService.KEY_WV_PILIH, ""));
            boolean adaB = b != null && !b.isEmpty();
            boolean adaW = w != null && !w.isEmpty();
            if (!adaB && !adaW) {
                return "";
            }
            if (adaB && adaW && b.equals(w)) {
                return ", terkunci v" + b;
            }
            return ", kunci bin:" + (adaB ? "v" + b : "terbaru")
                    + " wv:" + (adaW ? "v" + w : "terbaru");
        } catch (Exception e) {
            return "";
        }
    }

    /** Dialog pilih versi (daftar dari rilis repo + opsi terbaru + isi manual). */
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
                // Kuncian lama tetap bisa dipilih walau tak ada di daftar.
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
        new AlertDialog.Builder(this)
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
                .show();
    }

    private void dialogManualVersi(final boolean untukBinary) {
        final EditText input = new EditText(this);
        input.setHint("mis. 1.32.0 (kosongkan = terbaru)");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        String pin = untukBinary ? Updater.kuncianBinary(this)
                : Updater.kuncianWebVault(this);
        if (pin != null && !pin.isEmpty()) {
            input.setText(pin);
        }
        new AlertDialog.Builder(this)
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
                .show();
    }

    /** Simpan kuncian lalu pasang versi itu (downgrade pun boleh).
     *  Pin lama dikembalikan bila pasang gagal agar typo tak mengunci
     *  perangkat ke versi yang tak pernah terpasang. */
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
        refreshRingkasan();
        final String target = (versi == null || versi.isEmpty()) ? null : versi;
        final String pinLamaFinal = pinLama == null ? "" : pinLama;
        if (untukBinary) {
            runBusy(() -> {
                try {
                    appendUiLog("[app] Memasang binary v"
                            + (target == null ? "terbaru" : target) + "...");
                    if (KernelCompat.isLegacyDevice(KernelCompat.kernelSekarang())) {
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
                            ? msg + " Tekan Start untuk memakai." : msg);
                } catch (Exception e) {
                    if (target != null) {
                        if (untukBinary) {
                            Updater.simpanKuncianBinary(SettingsActivity.this, pinLamaFinal);
                        } else {
                            Updater.simpanKuncianWebVault(SettingsActivity.this, pinLamaFinal);
                        }
                        segarkanLabelVersi();
                        refreshRingkasan();
                    }
                    toast("Gagal pasang binary: " + e.getMessage());
                    appendUiLog("[app] Gagal pasang binary: " + e);
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
                    lastWvCheck = 0; // paksa baca ulang info versi web-vault
                } catch (Exception e) {
                    if (target != null) {
                        if (untukBinary) {
                            Updater.simpanKuncianBinary(SettingsActivity.this, pinLamaFinal);
                        } else {
                            Updater.simpanKuncianWebVault(SettingsActivity.this, pinLamaFinal);
                        }
                        segarkanLabelVersi();
                        refreshRingkasan();
                    }
                    toast("Gagal update web-vault: " + e.getMessage());
                    appendUiLog("[app] Gagal update web-vault: " + e);
                }
            });
        }
    }

    private void revertToBundled() {
        // Jangan cabut binary dari bawah proses yang hidup: proses tetap jalan
        // dengan inode lama tapi penanda versi ikut terhapus sehingga restart
        // berikut gagal sebelum unduh ulang selesai.
        if (ServerService.running || ServerService.isProcessAlive()) {
            toast("Stop server dulu sebelum reset binary.");
            appendUiLog("[app] Reset binary ditolak: server masih berjalan - Stop dulu.");
            return;
        }
        File out = new File(getFilesDir(), "bin/vaultwarden-" + ServerService.ABI);
        new File(getFilesDir(), "bin/version.txt").delete();
        if (out.exists() && out.delete()) {
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .remove(ServerService.KEY_UPDATE_VERSION).apply();
            ServerService.binaryVersion = "";
            toast("Binary dihapus. Akan diunduh ulang (versi pilihan) saat Start.");
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

    /** Start bila berhenti, Stop bila berjalan (tombol atas + bilah bawah memakai ini). */
    private void aksiStartStop() {
        // Wizard dianggap selesai begitu user menekan Start/Stop pertama kali.
        getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_WIZARD_DONE, true).apply();
        if (ServerService.running) {
            ServerService.stop(this);
        } else {
            saveAndStart();
        }
    }

    /** Semua detail selalu tampil penuh (lipatan hanya per seksi, bukan mode). */
    private void tampilkanDetailPenuh() {
        refreshRingkasan();
        aturDetail(secDetail, null, true);
        aturDetail(rawatDetail, null, true);
        aturDetail(tgDetail, null, true);
        aturRingkasan(secRingkasan, R.id.secUbah, false);
        aturRingkasan(rawatRingkasan, R.id.rawatUbah, false);
        aturRingkasan(tgRingkasan, R.id.tgUbah, false);
    }

    private void aturDetail(LinearLayout wadah, Object takDipakai, boolean tampil) {
        if (wadah != null) {
            wadah.setVisibility(tampil ? View.VISIBLE : View.GONE);
        }
    }

    private void aturRingkasan(TextView ringkas, int idUbah, boolean tampil) {
        if (ringkas != null) {
            ringkas.setVisibility(tampil ? View.VISIBLE : View.GONE);
        }
        Button ubah = findViewById(idUbah);
        if (ubah != null) {
            ubah.setVisibility(tampil ? View.VISIBLE : View.GONE);
        }
        android.view.View baris = ringkas != null ? (android.view.View) ringkas.getParent() : null;
        if (baris != null) {
            try {
                if (baris.getId() != View.NO_ID) {
                    baris.setVisibility(tampil ? View.VISIBLE : View.GONE);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Teks ringkasan per kartu dari prefs + input saat ini. */
    private void refreshRingkasan() {
        if (pinEnabledCheck == null || adminTokenInput == null) {
            return;
        }
        String pinAktif = pinEnabledCheck.isChecked() ? "PIN aktif" : "PIN mati";
        String admin = !adminTokenInput.getText().toString().trim().isEmpty()
                ? getString(R.string.terisi) : getString(R.string.belum_diisi);
        if (secRingkasan != null) {
            secRingkasan.setText(getString(R.string.ringkas_keamanan, pinAktif + ", token " + admin));
        }
        boolean au = autoUpdateCb != null && autoUpdateCb.isChecked();
        if (rawatRingkasan != null) {
            rawatRingkasan.setText(getString(R.string.ringkas_rawat,
                    (au ? "auto-update aktif" : "auto-update mati") + teksKuncian()));
        }
        boolean botIsi = tgTokenInput != null && !tgTokenInput.getText().toString().trim().isEmpty()
                && tgChatInput != null && !tgChatInput.getText().toString().trim().isEmpty();
        if (tgRingkasan != null) {
            tgRingkasan.setText(getString(R.string.ringkas_telegram,
                    botIsi ? getString(R.string.terisi) : getString(R.string.belum_diisi)));
        }
        if (adminStatus != null) {
            adminStatus.setText(getString(R.string.status_admin, adminTokenInput.getText().toString().trim().isEmpty()
                    ? getString(R.string.belum_diisi) : getString(R.string.terisi)));
        }
        if (tgStatus != null) {
            tgStatus.setText(getString(R.string.status_bot, botIsi ? getString(R.string.terisi) : getString(R.string.belum_diisi)));
        }
    }

    /** Salin teks ke clipboard dengan toast ramah. */
    private void salinTeks(String isi, String label) {
        if (isi == null || isi.trim().isEmpty()) {
            toast(label + " " + getString(R.string.belum_diisi));
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            final String salin = isi;
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, salin));
            // Otomatis bersihkan clipboard 60 dtk agar token rahasia
            // (admin/bot) tak mengendap dan disadap app lain.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.content.ClipData cur = cm.getPrimaryClip();
                    if (cur != null && cur.getItemCount() > 0
                            && cur.getItemAt(0) != null
                            && salin.equals(String.valueOf(cur.getItemAt(0).getText()))) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, ""));
                    }
                } catch (Exception ignored) {
                }
            }, 60_000);
        }
        toast(label + " " + getString(R.string.disalin));
    }

    /** Bubuhkan ikon peringatan di depan pesan galat (saran 11). */
    private void awaliGalat(TextView baris, int stringId) {
        if (baris != null) {
            baris.setText(getString(R.string.galat_awalan, getString(stringId)));
        }
    }

    /** Validasi inline setiap ketikan (tanpa toast agar tak berisik). */
    private void validasiInline() {
        String gData = galatFolder(dataDirInput.getText().toString(),
                getString(R.string.folder_error));
        dataDirError.setText(gData == null ? ""
                : getString(R.string.galat_awalan, gData));
        dataDirError.setVisibility(gData == null ? View.GONE : View.VISIBLE);
        String gPort = galatPort(portInput.getText().toString(),
                getString(R.string.port_error));
        portError.setText(gPort == null ? ""
                : getString(R.string.galat_awalan, gPort));
        portError.setVisibility(gPort == null ? View.GONE : View.VISIBLE);
        String gAdmin = adminTokenInput != null ? galatAdmin(adminTokenInput.getText().toString(),
                getString(R.string.admin_error)) : null;
        if (adminError != null) {
            adminError.setVisibility(gAdmin == null ? View.GONE : View.VISIBLE);
        }
        String gTok = tgTokenInput != null ? galatTgToken(tgTokenInput.getText().toString(),
                getString(R.string.token_error)) : null;
        if (tokenError != null) {
            tokenError.setVisibility(gTok == null ? View.GONE : View.VISIBLE);
        }
        String gChat = tgChatInput != null ? galatChat(tgChatInput.getText().toString(),
                getString(R.string.chat_error)) : null;
        if (chatError != null) {
            chatError.setVisibility(gChat == null ? View.GONE : View.VISIBLE);
        }
        boolean blokirStart = gData != null || gPort != null;
        if (startStopBawah != null) {
            startStopBawah.setEnabled(!blokirStart);
        }
        refreshRingkasan();
    }

    /** Titik "●" + warna merah pada label yang nilainya beda dari server berjalan. */
    // getColor(int) lawas sengaja agar satu jalur kode untuk API 21-32.
    @SuppressWarnings("deprecation")
    private void tandaiLabel(TextView label, int stringId, boolean kotor) {
        if (kotor) {
            label.setText(getString(R.string.label_kotor, getString(stringId)));
            label.setTextColor(getResources().getColor(R.color.status_off));
        } else {
            label.setText(getString(stringId));
            label.setTextColor(warnaLabelBawaan);
        }
    }

    /** Tampilkan wizard bila instalasi baru; tandai selesai agar sekali saja. */
    private void maybeShowWizard() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        if (TgBackup.amanBoolean(sp, KEY_WIZARD_DONE, false)) {
            return;
        }
        String d = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
        String p = TgBackup.amanString(sp, ServerService.KEY_PORT, DEFAULT_PORT);
        String a = TgBackup.amanString(sp, ServerService.KEY_ADMIN_TOKEN, "");
        String uv = TgBackup.amanString(sp, ServerService.KEY_UPDATE_VERSION, "");
        if (!perluWizard(false, d, p, a, uv) || ServerService.running
                || !ServerService.binaryVersion.isEmpty()) {
            sp.edit().putBoolean(KEY_WIZARD_DONE, true).apply();
            return;
        }
        tampilWizardGabungan();
    }

    private void wizardSelesai() {
        getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_WIZARD_DONE, true).apply();
    }

    private android.widget.EditText inputWizard(String isi, String hint, int tipe) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(tipe);
        input.setText(isi);
        input.setHint(hint);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        return input;
    }

    /** Wizard sekali dialog: folder + port dalam satu layar. */
    private void tampilWizardGabungan() {
        android.widget.LinearLayout wadah = new android.widget.LinearLayout(this);
        wadah.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        wadah.setPadding(pad, pad / 2, pad, pad / 2);
        final android.widget.EditText folderInput = inputWizard(
                dataDirInput.getText().toString(), getString(R.string.default_data_dir),
                android.text.InputType.TYPE_CLASS_TEXT);
        final android.widget.EditText portInputWiz = inputWizard(
                portInput.getText().toString(), getString(R.string.default_port),
                android.text.InputType.TYPE_CLASS_NUMBER);
        wadah.addView(folderInput);
        wadah.addView(portInputWiz);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.wiz_judul))
                .setMessage(getString(R.string.wiz_pesan))
                .setView(wadah)
                .setPositiveButton(getString(R.string.wiz_mulai), (d, w) -> {
                    dataDirInput.setText(folderInput.getText().toString().trim());
                    portInput.setText(portInputWiz.getText().toString().trim());
                    validasiInline();
                    String g1 = galatFolder(dataDirInput.getText().toString(),
                            getString(R.string.folder_error));
                    String g2 = galatPort(portInput.getText().toString(),
                            getString(R.string.port_error));
                    if (g1 != null) {
                        toast(g1);
                        tampilWizardGabungan();
                        return;
                    }
                    if (g2 != null) {
                        toast(g2);
                        tampilWizardGabungan();
                        return;
                    }
                    wizardSelesai();
                    aksiStartStop();
                })
                .setNegativeButton(getString(R.string.wiz_nanti), (d, w) -> {
                    dataDirInput.setText(folderInput.getText().toString().trim());
                    portInput.setText(portInputWiz.getText().toString().trim());
                    wizardSelesai();
                })
                .setNeutralButton(getString(R.string.wiz_lewati), (d, w) -> wizardSelesai())
                .setCancelable(false)
                .show();
    }

    /** Galat inline untuk kolom port; null bila valid (kosong = bawaan). Murni.
     *  Pesan (msgSalah) diinjeksi dari resources agar teks hanya hidup di strings.xml. */
    static String galatPort(String port, String msgSalah) {
        if (port == null || port.trim().isEmpty()) {
            return null;
        }
        try {
            int p = Integer.parseInt(port.trim());
            // <1024 butuh root dan selalu gagal bind: tolak di input agar
            // tak FATAL saat Start (selaras ServerService.normalisasiPort).
            if (p >= 1024 && p <= 65535) {
                return null;
            }
        } catch (Exception ignored) {
        }
        return msgSalah;
    }

    /** Galat inline untuk kolom folder; null bila valid (kosong = bawaan). Murni.
     *  Pesan (msgSalah) diinjeksi dari resources agar teks hanya hidup di strings.xml. */
    static String galatFolder(String folder, String msgSalah) {
        if (folder == null || folder.trim().isEmpty()) {
            return null;
        }
        return ServerService.dataDirAman(folder) ? null : msgSalah;
    }

    /** Galat Admin Token; null bila valid (kosong = boleh, isi = minimal 8). Murni.
     *  Pesan (msgSalah) diinjeksi dari resources agar teks hanya hidup di strings.xml. */
    static String galatAdmin(String token, String msgSalah) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        return token.trim().length() >= 8 ? null : msgSalah;
    }

    /** Galat token bot Telegram; null bila valid (kosong = boleh). Murni.
     *  Pesan (msgSalah) diinjeksi dari resources agar teks hanya hidup di strings.xml. */
    static String galatTgToken(String token, String msgSalah) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        String isi = token.trim();
        return isi.contains(":") && isi.length() >= 20 ? null : msgSalah;
    }

    /** Galat Chat ID; null bila valid (kosong = boleh). ID numerik atau
     *  username ("@nama", selaras dengan Util.cocokChat). Murni.
     *  Pesan (msgSalah) diinjeksi dari resources agar teks hanya hidup di strings.xml. */
    static String galatChat(String chat, String msgSalah) {
        if (chat == null || chat.trim().isEmpty()) {
            return null;
        }
        String isi = chat.trim();
        if (isi.matches("-?\\d+")) {
            return null;
        }
        String nama = isi.startsWith("@") ? isi.substring(1) : isi;
        if (!nama.isEmpty() && !nama.matches("[0-9]+")
                && nama.matches("[A-Za-z0-9_]{5,}")) {
            return null;
        }
        return msgSalah;
    }

    /** Token admin acak 24 karakter [A-Za-z0-9]. Murni (Random diinjeksi agar bisa diuji). */
    static String buatTokenAcak(java.util.Random rnd) {
        // Fail-fast: token admin adalah rahasia sehingga java.util.Random biasa
        // (seed 48-bit, bisa ditebak) dilarang; pemanggil produksi memakai
        // SecureRandom, overload Random hanya untuk uji format.
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

    /** Teks badge HTTPS inline; barisSert dari certInfoLine (kosong bila belum ada). Murni.
     *  Pesan (msgAktif/msgBelumAda) diinjeksi dari resources agar teks hanya hidup di strings.xml. */
    static String teksBadgeHttps(boolean berjalan, String barisSert,
            String msgAktif, String msgBelumAda) {
        boolean ada = barisSert != null && !barisSert.isEmpty();
        if (berjalan) {
            return ada ? msgAktif + " \u2022 " + barisSert : msgAktif;
        }
        return ada ? barisSert : msgBelumAda;
    }

    /** True bila wizard perlu tampil: belum selesai dan belum pernah di-setup. Murni. */
    static boolean perluWizard(boolean sudahSelesai, String dataDir, String port,
            String admin, String versiTersimpan) {
        if (sudahSelesai) {
            return false;
        }
        boolean ubahan = (dataDir != null && !dataDir.trim().isEmpty()
                && !dataDir.trim().equals(ServerService.dataDirBawaanSegar()))
                || (port != null && !port.trim().isEmpty()
                && !port.trim().equals(ServerService.DEFAULT_PORT))
                || (admin != null && !admin.trim().isEmpty())
                || (versiTersimpan != null && !versiTersimpan.isEmpty());
        return !ubahan;
    }

    private void confirm(String title, String message, final Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Ya", (d, w) -> action.run())
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Pasang lipatan seksi ala Download Manager (status buka tersimpan di prefs). */
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

    /** Chip navigasi: buka seksi lalu gulir ke judulnya. */
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

    /** Nonaktifkan tombol aksi + tampilkan "Sedang bekerja…" selama operasi. */
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

    private boolean jadwalBotTertunda = false;

    private final Runnable scheduleBotRunnable = () -> {
        jadwalBotTertunda = false;
        TgBot.schedule(SettingsActivity.this);
    };

    /** Pasang ulang jadwal bot maksimal 1x/detik saat token/chat diketik. */
    private void scheduleBotDebounced() {
        ui.removeCallbacks(scheduleBotRunnable);
        jadwalBotTertunda = true;
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

    /** Antrean tulis prefs debounce agar bisa disiram saat pause (lihat siramTulisTertunda). */
    private final java.util.List<Runnable> tulisTundaAktif = new java.util.ArrayList<>();

    /** Tulis prefs debounce yang masih antre agar ketikan terakhir tak hilang
     *  saat pause/destroy (ui.removeCallbacks membatalkannya). Tulis prefs
     *  kecil sehingga aman sinkron di UI thread. */
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
                synchronized (tulisTundaAktif) {
                    tulisTundaAktif.remove(tugasTunda);
                }
            }
            final Runnable[] wadah = new Runnable[1];
            wadah[0] = () -> {
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                        .edit().putString(key, nilai).apply();
                synchronized (tulisTundaAktif) {
                    tulisTundaAktif.remove(wadah[0]);
                }
            };
            tugasTunda = wadah[0];
            synchronized (tulisTundaAktif) {
                tulisTundaAktif.add(tugasTunda);
            }
            ui.postDelayed(tugasTunda, 400);
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
        }
    }

    @Override
    protected void onDestroy() {
        try {
            siramTulisTertunda();
        } catch (Exception ignored) {
        }
        pinHashSiap = null;
        sapuExportPlainBasi();
        pinExec.shutdownNow();
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }
}
