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
    /** Wizard setup 3 langkah sudah pernah tampil/selesai. */
    private static final String KEY_WIZARD_DONE = "wizard_selesai";

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Satu instans loop refresh (selaras MainActivity.refreshLoop): tiap
     *  postDelayed/post method-ref baru adalah objek beda sehingga titikan
     *  basi tak terbatalkan — rantai ganda menumpuk permanen tiap onResume/
     *  setBusy/appendUiLog. Semua titik memakai ini + hapus-sebelum-post. */
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
    /** Hash PIN (PBKDF2 30rb iterasi) di worker bersama: dulu satu executor
     *  non-daemon per instance SettingsActivity sehingga tiap rotasi layar
     *  membocorkan satu thread (stack ~1 MB) selamanya di STB 1 GB.
     *  Daemon + bersama: payload kecil (hash ~1 dtk, ter-debounce 800 ms)
     *  sehingga antrean tak perlu per instance; batalkan via pinPending. */
    private static final java.util.concurrent.ExecutorService pinExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "vw-pin-hash");
                t.setDaemon(true);
                return t;
            });
    private volatile int pinSeq;
    private volatile java.util.concurrent.Future<?> pinPending;
    /** Jadwal debounce ketikan PIN: hash (PBKDF2 30rb) baru dikirim ke
     *  worker sesudah 800 ms tanpa ketikan agar tak menumpuk di CPU lemah STB. */
    private Runnable pinTunda = null;
    /** Kandidat PIN yang sudah ditawarkan lewat dialog "Ganti PIN?" agar
     *  dialog tak muncul berulang untuk isi field yang sama (mis. user
     *  menggeser kursor lalu berhenti mengetik lagi). */
    private String pinGantiKandidat = null;
    /** Hash PIN terbaru dari worker yang belum disimpan ke prefs: prefs hanya
     *  menerima nilai settle agar PIN parsial tak jadi PIN valid bila app mati
     *  di tengah mengetik. */
    private volatile String pinHashSiap = null;
    /** True selama aktivasi PIN (centang) berjalan di thread sendiri.
     *  Ketikan/tekan remote ganda di STB selama flag ini tak boleh menggugurkan
     *  aktivasi (dulu nomor urut + antrean single-thread membuang hasilnya di
     *  CPU lambat sehingga centang "tidak bisa aktif"). */
    private volatile boolean pinAktifJalan = false;
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
    /** File config plaintext sementara yang menunggu dibagikan: dipertahankan
     *  hingga basi 2 menit (target async membaca setelah chooser kembali).
     *  Volatile karena ditulis worker exportConfig dan dibaca UI thread. */
    private volatile File exportPlainTertunda = null;
    /** Batas simpan file config plaintext sementara (2 menit, sapu basi). */
    static final long EXPORT_PLAIN_TTL_MS = 2L * 60 * 1000;
    /** Umur maksimum arsip backup Telegram yang diunduh untuk restore dan
     *  menunggu dialog konfirmasi (1 jam, sapu saat Settings dibuka). */
    static final long RESTORE_TG_TTL_MS = 60L * 60 * 1000;
    /** Prefix plaintext sementara khusus cabang terenkripsi: tak pernah
     *  dibagikan sehingga selalu disapu tanpa pandang umur (sisa kill -9 di
     *  jendela tulis-enkripsi-hapus tak boleh mengendap sampai basi). */
    static final String PREFIX_ENCTMP = "app-config-enctmp-";

    /** True bila nama file adalah sisa plaintext sementara cabang terenkripsi
     *  (tak pernah jadi target bagi sehingga aman disapu kapan pun). Murni. */
    static boolean sisaEnkripTmp(String nama) {
        return nama != null && nama.startsWith(PREFIX_ENCTMP) && nama.endsWith(".json");
    }

    /** Gagal dekrip import per sidik berkas (anti buka-ulang dialog dapat jatah
     *  5x baru: berkas yang sama dihitung kumulatif selama proses hidup).
     *  Penahan ketuk-brute-force saja: pemegang HP bisa lihat password di
     *  Settings, dan file curian bisa dibobol offline di mesin sendiri. */
    private static final java.util.Map<String, Integer> GAGAL_IMPOR_DIGEST =
            new java.util.HashMap<>();

    /** Sidik SHA-256 hex isi berkas (kunci budget gagal lintas dialog);
     *  "" bila tak terbaca (semua yang tak terbaca berbagi satu budget). */
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
            // Berkas null/tak-ada: tetap "" (kontrak unit test; berkas seperti
            // itu takkan sampai ke alur impor). Berkas ADA tapi gagal dibaca
            // (izin/IO): fallback stabil per path agar budget 5x tetap
            // terakumulasi lintas dialog (bukan kunci unik tiap panggil yang
            // memberi jatah baru terus + membocorkan entri map tiap coba).
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

    /** True bila budget tebak password berkas ini sudah habis. Murni. */
    static boolean imporDiblokir(int gagal) {
        return gagal >= PinCrypto.MAX_GAGAL;
    }

    /** True bila Start wajib ditahan karena tugas backup/restore masih
     *  berjalan (form bisa berisi nilai pra-restore dan prefs sedang
     *  ditimpa hasil restore). Murni. */
    static boolean startDitahanSaatTugasData(boolean tugasUi, boolean tugasData) {
        return tugasUi || tugasData;
    }

    /** Ambil gagal kumulatif sidik ini (0 bila belum ada). */
    static synchronized int ambilGagalImpor(String sidik) {
        try {
            Integer v = GAGAL_IMPOR_DIGEST.get(sidik == null ? "" : sidik);
            return v == null ? 0 : v;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Batas entri budget impor agar impor massal berkas berbeda tak
     *  menumpuk map statis tanpa batas di STB 1 GB (sukses menghapus, jadi
     *  cap hanya menahan spam gagal). */
    private static final int MAKS_SIDIK_IMPOR = 128;

    /** Simpan gagal kumulatif sidik ini (<=0 menghapus agar map tak bocor). */
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
    /** Kapan export plaintext dibuat (jam elapsed monotonik, bukan jam dinding
     *  agar jam HP yang dimundurkan tak memperpanjang pajanan secret di cache). */
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

    /** Acak bersama tombol dadu: new SecureRandom tiap ketuk bisa blokir
     *  seed di kernel lama (sekali-per-proses di sini). */
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
    /** Cache URL lokal 10 dtk (pola MainActivity): localUrl (prefs + port
     *  + IP) tiap 1 dtk sampah + lookup prefs sia-sia di ART lama. */
    private String cacheUrlSet = "";
    private long cacheWaktuSet = 0;
    /** Cache teks status/tombol/badge agar tick 500 ms-1 dtk tak getString +
     *  alokasi ulang tiap kali di STB lama (diisi sekali di onCreate). */
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
    /** Validasi folder/port terakhir: hanya mengunci Start, bukan Stop. */
    private boolean inputStartValid = true;
    private String memoCocokA = "\0";
    private String memoCocokB = "\0";
    private boolean memoCocokHasil = false;
    private String memoCocokA2 = "\0";
    private String memoCocokB2 = "\0";
    private boolean memoCocokHasil2 = false;
    /** Cache prefs deteksi "setting berubah" (selaras MainActivity.tickCachePrefs):
     *  baca prefs + parse port + sidik token tiap tick 1 dtk boros di STB lama;
     *  segarkan tiap 5 tick + paksa saat prefs ditulis lokal/import/resume. */
    private int tickCacheUbah = 0;
    private volatile boolean paksaCacheUbah = true;
    private String cacheUbahD = "";
    private String cacheUbahP = "";
    private String cacheUbahA = "";
    /** Cache teks status isian: perbaruiStatusIsian jalan tiap ketikan
     *  sehingga setText yang isinya sama (tetap memicu layout) dilewati. */
    private String lastStatusAdmin = "";
    private String lastStatusBot = "";
    /** Template pesan galat inline (5x getString per ketikan boros di ART lama):
     *  dimuat sekali di onCreate (ganti bahasa recreate activity). */
    private String msgFolderSalah = "";
    private String msgPortSalah = "";
    private String msgAdminSalah = "";
    private String msgTokenSalah = "";
    private String msgChatSalah = "";

    /** Status kotor terakhir per label: setText/setColor yang isinya sama
     *  tetap memicu layout sehingga wajib dilewati bila tak berubah. */
    private final java.util.Map<TextView, Boolean> labelKotorCache =
            new java.util.IdentityHashMap<>();
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
    /** Status tampil baris baterai terakhir (null = belum pernah dihitung):
     *  dipakai menyambung ulang rantai fokus D-pad hanya saat berubah. */
    private Boolean lastBatteryTampil = null;

    /** Guard agar onResume beruntun tak menumpuk dialog PIN. */
    private boolean pinBukaJalan = false;

    /** Status buka PIN via PinGate (satu sumber, simetris dengan MainActivity). */
    static boolean pinBaruSajaDibuka() {
        return PinGate.dalamGraceBersama();
    }

    /** Catat buka PIN sebagai milik bersama (tanpa panggil balik) agar tak rekursi.
     *  Satu-satunya sumber keputusan grace adalah PinGate; tanpa cermin lokal. */
    static void catatPinDibuka() {
        if (!PinGate.dalamGraceBersama()) {
            PinGate.bukaKunciBersama();
        }
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
        // Sisa salinan clipboard yang penghapusnya tak sempat jalan karena
        // proses mati dibersihkan/dipasang ulang di sini (satu pintu LogActivity).
        LogActivity.bersihkanClipBasiJikaAda(this);
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
        batteryBtn = findViewById(R.id.batteryBtn);
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

        startStopBawah.setOnClickListener(v -> aksiStartStop());
        updateBtn.setOnClickListener(v -> runBusy(this::checkForUpdate));
        revertBtn.setOnClickListener(v -> confirm("Reset Binary",
                "Delete the saved binary. The chosen version (or latest if unpinned)"
                        + " will be re-downloaded automatically on next Start"
                        + " (needs internet). Continue?",
                () -> runBusy(this::revertToBundled)));
        installCertBtn.setOnClickListener(v -> installCertificate());
        shareCaBtn = findViewById(R.id.shareCa);
        if (shareCaBtn != null) {
            shareCaBtn.setOnClickListener(v -> bagikanCa());
        }
        resetCertBtn = findViewById(R.id.resetCert);
        if (resetCertBtn != null) {
            resetCertBtn.setOnClickListener(v -> confirm("Reset Certificate",
                    "Delete the old CA + certificates and create a new CA on next Start. "
                            + "Every phone must reinstall the new CA. Continue?",
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
                + portEfektifUntukSalin(), "Local URL copied"));
        randomAdminBtn.setOnClickListener(v -> {
            adminTokenInput.setText(buatTokenAcak(ACAK_DADU));
            toast("Random token created — press Start to apply.");
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
        // Keraskan hash PIN legasi di rest (tanpa menunggu login),
        // untuk pemakaian settings-only yang tak buka MainActivity.
        try {
            final android.content.Context appPin = getApplicationContext();
            Util.jalankanBg(() -> PinGate.kuatkanHashDini(appPin));
        } catch (Exception ignored) {
        }
        bersihkanPin(pinInput);
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
        segarkanLabelVersi();

        autoStartCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_AUTO_START, checked).apply());
        tgAutoCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            SharedPreferences spTg = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            // setChecked programatik (import config / restore) memicu listener
            // ini tanpa aksi user: jangan berpura-pura memberi tahu
            // "enabled/disabled". Jadwal tetap dipasang ulang (idempoten).
            boolean lama = TgBackup.amanBoolean(spTg, TgBackup.KEY_TG_AUTO, false);
            spTg.edit().putBoolean(TgBackup.KEY_TG_AUTO, checked).apply();
            TgBackup.schedule(SettingsActivity.this, checked);
            if (lama != checked) {
                toast(checked ? "Automatic backup enabled." : "Automatic backup disabled.");
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
                // Batalkan jadwal debounce lama; Future antre ikut dibatalkan.
                // PBKDF2 yang sudah jalan tak bisa diinterupsi, jadi hash baru
                // dikirim SESUDAH 800 ms tanpa ketikan (satu hash per jeda,
                // bukan satu per ketikan yang menumpuk di CPU lemah STB).
                if (pinTunda != null) {
                    ui.removeCallbacks(pinTunda);
                    pinTunda = null;
                }
                java.util.concurrent.Future<?> basi = pinPending;
                if (basi != null && !basi.isDone()) {
                    basi.cancel(false);
                }
                // Hash ditahan di memori dulu: prefs hanya menerima nilai yang
                // sudah settle (800 ms tanpa ketikan) agar app yang mati di
                // tengah mengetik tak meninggalkan hash PIN parsial.
                // PIN mentah tak disimpan di field agar tak mengendap di heap.
                pinHashSiap = null;
                if (s.length() < 4) {
                    // PIN pendek bukan PIN valid: cukup jangan tulis hash baru.
                    // JANGAN hapus hash / matikan PIN yang tersimpan — dulu cabang
                    // ini melakukannya tanpa konfirmasi, sehingga mengetik satu
                    // karakter saat PIN aktif langsung mematikan proteksi
                    // (fail-open), dan PIN baru (>=4 karakter) tak pernah
                    // tersimpan karena centang sudah dilepas. PIN hanya dimatikan
                    // lewat centang, yang memakai dialog konfirmasi.
                    return;
                }
                final String pin = s.toString();
                final int seq = pinSeq;
                pinTunda = () -> {
                    // Tandai sudah dijalankan agar onDestroy tak mengulang submit.
                    pinTunda = null;
                    if (seq != pinSeq) {
                        return;
                    }
                    // Baca centang di UI thread (runnable ini dijalankan handler
                    // UI): worker tak boleh menyentuh View setelah activity mati.
                    final boolean pinAktif = pinEnabledCheck != null
                            && pinEnabledCheck.isChecked();
                    final boolean ganti = perluKonfirmasiGantiPin(pinAktif, adaHashPinTersimpan());
                    if (!ganti) {
                        tulisHashPin(pin, seq, pinAktif, false);
                        return;
                    }
                    // PIN sudah aktif: ketikan di field TIDAK boleh langsung
                    // menimpa hash (dulu begitu — salah ketik/salah tempel satu
                    // kali langsung mengunci app di luar). Minta konfirmasi
                    // sekali per isi field; batal = field dikosongkan agar tak
                    // terlihat seolah PIN tersimpan sudah berubah.
                    if (pin.equals(pinGantiKandidat)) {
                        return;
                    }
                    pinGantiKandidat = pin;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    // Dialog langsung (bukan confirm()) karena tombol Batal
                    // harus mengosongkan field: isi field yang tak tersimpan
                    // membuat user mengira PIN sudah berganti.
                    // SettingsActivity.this: di dalam lambda TextWatcher, `this`
                    // menunjuk anonim TextWatcher (bukan Activity) — dulu
                    // membuat build gagal "cannot be converted to Context".
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle(getString(R.string.pin_ganti_judul))
                            .setMessage(getString(R.string.pin_ganti_pesan))
                            .setPositiveButton(android.R.string.ok,
                                    (d, w) -> tulisHashPin(pin, seq, true, true))
                            .setNegativeButton(getString(R.string.batal), (d, w) -> {
                                pinGantiKandidat = null;
                                bersihkanPin(pinInput);
                            })
                            .show();
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
                // Aktivasi masih jalan (biasanya tekan remote ganda di STB):
                // abaikan toggle dan kembalikan centang.
                pinCentangProgram = true;
                b.setChecked(true);
                pinCentangProgram = false;
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
                toast("Enter a PIN first (min. 4 letters/digits).");
                pinCentangProgram = true;
                b.setChecked(false);
                pinCentangProgram = false;
                return;
            }
            // Selalu hash ulang dari field saat itu di thread sendiri (PBKDF2
            // berat, tak boleh di UI thread): tanpa memakai cache pinHashSiap agar
            // centang tak pernah memakai hash basi/parsial (kunci permanen).
            // Thread sendiri (bukan antrean pinExec) agar tak antre di belakang
            // hash debounce di CPU lemah STB; ketikan/tekan ganda selama proses
            // tak menggugurkan hasil. Checkbox dibiarkan tercentang + tetap
            // enabled agar fokus D-pad STB tak hilang.
            // Jadwal debounce ketikan ikut dibatalkan agar tinggal satu hash.
            if (pinTunda != null) {
                ui.removeCallbacks(pinTunda);
                pinTunda = null;
            }
            java.util.concurrent.Future<?> basiAktif = pinPending;
            if (basiAktif != null && !basiAktif.isDone()) {
                basiAktif.cancel(false);
            }
            // Batalkan nilai settle debounce lama agar tulis otomatisnya tak
            // menimpa hasil aktivasi.
            pinSeq++;
            // PIN dari centang ini sudah disetujui user: kandidat dialog
            // ganti-PIN direset agar edit berikutnya ditanya lagi.
            pinGantiKandidat = null;
            final String pinBaru = fieldPin;
            pinAktifJalan = true;
            toast("Processing PIN…");
            boolean masuk = Util.jalankanLama(() -> {
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
                pinHashSiap = hasil;
                // Satu apply atomis: hash dan flag on ditulis bersama agar
                // crash di antaranya tak meninggalkan hash basi tanpa flag.
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                        .edit().putString(PinGate.KEY_PIN_HASH, hasil)
                        .putBoolean(PinGate.KEY_PIN_ON, true).apply();
                pinAktifJalan = false;
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    pinCentangProgram = true;
                    pinEnabledCheck.setChecked(true);
                    pinCentangProgram = false;
                    toast("PIN is active.");
                });
            });
            // Antrean penuh (tugas tak pernah jalan): jangan biarkan pinAktifJalan
            // true + centang menyala padahal PIN_ON tak pernah ditulis.
            if (!masuk) {
                pinAktifJalan = false;
                pinCentangProgram = true;
                pinEnabledCheck.setChecked(false);
                pinCentangProgram = false;
                toast(getString(R.string.pin_proses_gagal));
            }
        });

        // Izin storage untuk semua Android (izin tulis runtime; targetSdk 28
        // tetap legacy di Android 11+, All files tidak diwajibkan).
        StoragePerm.mintaIzinBilaPerlu(this, REQ_WRITE);

        bundledVersion = Updater.readBundledVersionLabel(this);
        bundledRaw = Updater.readBundledVersionRaw(this);
        try {
            appVersion = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        ui.post(refreshLoop);

        // Auto-update check on launch
        Util.jalankanLama(this::autoUpdateCheck);
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
        paksaCacheUbah = true;
        ui.removeCallbacks(refreshLoop);
        ui.post(refreshLoop);
        // Auto-lock PIN setiap kali app kembali ke depan
        maybeShowPinLock();
    }

    @Override
    protected void onPause() {
        super.onPause();
        siramTulisTertunda();
        // Editan folder/port/token yang valid jangan hilang saat Back: dulu
        // ketiganya hanya ditulis ketika tombol Start ditekan.
        simpanPengaturanValid();
        refreshActive = false;
        ui.removeCallbacks(refreshLoop);
    }

    /** Simpan folder/port/admin token yang valid saat layar ditinggalkan.
     *  Nilai tak valid dilewati agar editan setengah jadi tak menimpa setelan
     *  lama; saat backup/restore berjalan tulis ditunda karena prefs bisa
     *  sedang ditimpa hasil restore. */
    private void simpanPengaturanValid() {
        if (tugasBerjalan.get() || TgBackup.tugasDataJalan()) {
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

    /** Tambah pasangan kunci/nilai ke editor hanya bila beda dari prefs agar
     *  onPause tidak menulis disk percuma tiap kali. */
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
            if (!StoragePerm.tulisDiizinkan(permissions, grantResults)) {
                StoragePerm.tanganiPenolakan(this);
            }
        }
    }

    private void saveAndStart() {
        // Tugas backup/restore sedang berjalan: form bisa berisi nilai pra-restore
        // dan prefs bisa ditimpa hasil restore. Tanpa guard, Start menulis
        // folder/port/token basi di tengah restore lalu menyalakan server.
        if (startDitahanSaatTugasData(tugasBerjalan.get(), TgBackup.tugasDataJalan())) {
            toast("Still working, please wait.");
            appendUiLog("[app] Start dibatalkan: tugas backup/restore masih berjalan.");
            return;
        }
        String dataDir = dataDirInput.getText().toString().trim();
        String port = portInput.getText().toString().trim();
        String adminToken = adminTokenInput.getText().toString().trim();

        // Tombol Start sudah dikunci saat token <8 karakter; cek lagi di sini
        // agar jalur programatik (isi otomatis) tak menyimpan token lemah.
        if (galatAdmin(adminToken, msgAdminSalah) != null) {
            toast(msgAdminSalah);
            appendUiLog("[app] Admin token terlalu pendek - Start dibatalkan.");
            adminTokenInput.requestFocus();
            return;
        }

        String dataDirEfektif = ServerService.amankanDataDir(dataDir);
        if ((!ServerService.dataDirAman(dataDir) || !ServerService.dataDirKanonisAman(dataDir))
                && !TextUtils.isEmpty(dataDir)) {
            toast("Invalid data folder, using default.");
            appendUiLog("[app] Folder data tidak valid, pakai bawaan: " + ServerService.dataDirBawaanSegar());
        }
        // Folder eksternal tanpa izin pasti gagal writable — minta dulu, batalkan Start.
        if (!StoragePerm.siapStart(this, dataDirEfektif, REQ_WRITE)) {
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
            toast("Port must be a number 1-65535.");
            appendUiLog("[app] Port tidak valid: '" + port + "' - Start dibatalkan.");
            return;
        }
        if (portNum < 1024) {
            // Selaras galatPort (inline) + startServer: port privileged butuh
            // root dan selalu gagal bind. Sembuhkan ke default di depan agar
            // prefs/UI/server sepakat (sebelumnya tersimpan mentah lalu Start
            // jalan di port berbeda diam-diam).
            portEfektif = ServerService.normalisasiPort(portEfektif);
            portNum = Integer.parseInt(portEfektif);
            portInput.setText(portEfektif);
            validasiInline();
            toast("Port <1024 needs root — using default " + portEfektif + ".");
            appendUiLog("[app] Port privileged '" + port + "' - pakai default "
                    + portEfektif + ".");
        }

        SharedPreferences.Editor ed = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit();
        ed.putString(ServerService.KEY_DATA_DIR, dataDirEfektif);
        ed.putString(ServerService.KEY_PORT, portEfektif);
        ed.putString(ServerService.KEY_ADMIN_TOKEN, adminToken);
        ed.apply();
        paksaCacheUbah = true;
        // Cek port di worker (2x bind ServerSocket di UI thread berisiko ANR
        // di tumpukan lambat): prefs sudah tersimpan di atas, dialog tetap di UI.
        final int portCekPort = portNum;
        final String dirCekPort = dataDirEfektif;
        boolean masukCek = Util.jalankanBg(() -> {
            final boolean portSibuk =
                    portCekPort > 0 && ServerService.isPortBusy(portCekPort);
            // Cek izin bind di worker juga (2x ServerSocket di UI thread = ANR
            // di tumpukan lambat): short-circuit agar port bebas tak membayar
            // 2 bind tambahan yang hasilnya pasti false.
            final boolean butuhRoot = portSibuk && ServerService.portButuhRoot(portCekPort);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (portSibuk) {
                    if (butuhRoot) {
                        new AlertDialog.Builder(this)
                                .setTitle("Port " + portCekPort + " needs root access")
                                .setMessage("Main port (" + portCekPort + ") below 1024 can only be"
                                        + " used with root access.\n"
                                        + "Change the port to >= 1024 (e.g. 8088) above.")
                                .setPositiveButton("OK", null)
                                .show();
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Port " + portCekPort + " is in use")
                            .setMessage("Main port (" + portCekPort + ") is already used by another process.\n"
                                    + "Stop the other app using it, change the port above, "
                                    + "or restart the phone first.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                if (!Updater.webVaultReady(this, dirCekPort)) {
                    // APK baru tidak membundel web-vault (ukuran jauh lebih kecil);
                    // unduh sekali saat Start pertama bila diizinkan.
                    new AlertDialog.Builder(this)
                            .setTitle("Web vault not installed")
                            .setMessage("The APK excludes the web vault to stay small.\n"
                                    + "Download once (~35 MB) so the web UI can be opened from a browser?\n\n"
                                    + "Without the web vault, the server and Bitwarden apps still work normally.")
                            .setPositiveButton("Download & Start", (d, w) -> {
                                // App context: unduh 35 MB di worker tak boleh menahan
                                // Activity yang sudah di-back (bocor) + start dari
                                // konteks mati (selaras MainActivity).
                                final android.content.Context appCtx = getApplicationContext();
                                runBusy(() -> {
                                    boolean gagal = false;
                                    try {
                                        String msg = Updater.updateWebVault(appCtx);
                                        appendUiLog("[app] " + msg);
                                    } catch (Exception e) {
                                        gagal = true;
                                        toast("Web-vault download failed"
                                                + " \u2014 server was not started: " + e.getMessage());
                                        appendUiLog("[app] Web-vault download failed: " + e);
                                    }
                                    if (!gagal && ServerService.start(appCtx)) {
                                        // Backup otomatis baru dijadwalkan setelah
                                        // server benar-benar diminta start; unduh
                                        // gagal/ditolak = jangan buat backup palsu.
                                        maybeAutoBackup();
                                    }
                                });
                            })
                            .setNegativeButton("Start without web vault", (d, w) -> {
                                if (ServerService.start(this)) {
                                    maybeAutoBackup();
                                }
                            })
                            .show();
                    return;
                }
                if (ServerService.start(this)) {
                    maybeAutoBackup();
                }
            });
        });
        if (!masukCek) {
            // Antrean ringan penuh: beri tahu, jangan biarkan Start diam saja
            // (dulu hasil jalankanBg diabaikan sehingga tombol tak bereaksi).
            toast(getString(R.string.pin_sibuk));
            appendUiLog("[app] Cek port ditolak: antrean tugas penuh.");
        }
    }

    private void refreshFromService() {
        // Runtuhkan rantai ganda jadi satu (selaras MainActivity): tiap
        // suntikan "refresh sekarang" ikut menjadwalkan penerus abadi.
        ui.removeCallbacks(refreshLoop);
        // Worker (appendUiLog/setBusy) bisa memicu refresh sesudah Activity
        // dihancurkan; menyentuh view mati hanya membuang kerja + berisiko
        // memunculkan dialog bertoken mati.
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // Sedang sibuk (update/backup)? Kunci chip status: progress unduh bila ada,
        // selain itu "Bekerja…" — jangan biarkan polling menimpa dengan status lama.
        if (uiBusy) {
            String dl = Updater.downloadStatus;
            String sibuk = dl.isEmpty() ? teksSibuk : dl;
            if (!sibuk.equals(lastBusyText)) {
                lastBusyText = sibuk;
                statusView.setText(sibuk);
                statusView.setBackgroundResource(R.drawable.bg_status_busy);
            }
            lastShownStatus = "";
            int persen = Updater.persenUnduhan(dl);
            // Bar ganda fungsi: progress download bila persen diketahui,
            // loading berjalan (indeterminate) bila sibuk tanpa persen
            // (total tak diketahui / kerja non-unduh seperti backup/restore).
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
            return;
        }

        lastBusyText = "";
        // Chip status: Berjalan / Berhenti + tombol Start/Stop tunggal
        boolean running = ServerService.running;
        perbaruiEnabledStartStop();
        String statusText = running ? teksBerjalan : teksBerhenti;
        // Tanpa concat tiap tick (dulu statusText+"|"+... tiap 500/1000 ms):
        // teks hanya turunan running sehingga literal on/off cukup.
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
            // Ikon Stop di atas latar biru "primary" terbaca seperti tombol
            // aksi biasa; samakan dengan layar awal (merah saat berjalan).
            startStopBawah.setBackgroundResource(running
                    ? R.drawable.bg_btn_stop : R.drawable.bg_btn_primary);
        }

        // Peringatan bila setting diubah tapi server belum di-restart
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
            // Trim sekali per nilai (dulu 2x tiap tick): hemat alokasi String di STB lama.
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

        // Peringatan bila update binary tersedia tapi belum dipasang
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
                pendingVersion = null; // update sudah terpasang
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
                    pendingVersion = null; // update sudah terpasang
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
            StringBuilder versiB = new StringBuilder("App ").append(appVersion);
            if (!binVerSet.isEmpty()) {
                versiB.append(" \u00B7 Binary: ").append(binVerSet);
            } else {
                versiB.append(" \u00B7 ").append(bundledSet);
            }
            versiCache = versiB.toString();
        }
        String version = versiCache;
        // I/O berat (stat DB, jalan folder backup/web-vault) di worker thread;
        // UI thread hanya membaca nilai cache agar tidak jank/ANR.
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
                // Antrean penuh: lepas flag agar info berat dicoba lagi nanti
                // (dulu flag tertahan true sehingga info tak pernah disegarkan).
                heavyRunning.set(false);
            }
        }
        StringBuilder fullB = new StringBuilder(version);
        // Cache saja bila worker berat jalan (lihat dbInfoLine): cegah hitung
        // ganda + I/O (/proc/statfs/jalan folder) di UI thread saat cache dingin.
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

        // Baris "Izinkan akses penuh" hanya muncul bila battery optimization aktif.
        // Hasil IPC di-cache 30 dtk agar refresh tiap detik tidak membebani binder.
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
        // Rantai fokus D-pad disambung ulang saat status baris berubah: tombol
        // baterai yang GONE tak boleh tetap jadi tujuan nextFocus (fokus
        // berhenti/melompat sehingga remote TV tersangkut).
        if (lastBatteryTampil == null || lastBatteryTampil != tampilBaterai) {
            lastBatteryTampil = tampilBaterai;
            perbaikiFokusBaterai(tampilBaterai);
        }

        if (refreshActive) {
            ui.postDelayed(refreshLoop, 1000);
        }
    }

    /** Dialog Tentang: satu implementasi bersama MainActivity (dulu salinan
     *  HTML sendiri dengan isi berbeda sehingga info versi tak sinkron). */
    private void showAboutDialog() {
        AboutDialog.tampil(this);
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
                toast("No CA yet. Press Start first so a CA is created.");
                return;
            }
            Uri uri = Uri.parse("content://" + FileShareProvider.AUTHORITY
                    + Uri.encode(cert.getAbsolutePath(), "/"));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/x-x509-ca-cert");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            toast("Failed to open installer: " + e.getMessage());
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
                toast("No CA yet. Press Start first so a CA is created.");
                return;
            }
            Uri uri = Uri.parse("content://" + FileShareProvider.AUTHORITY
                    + Uri.encode(cert.getAbsolutePath(), "/"));
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/x-x509-ca-cert");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(Intent.createChooser(send, "Share CA to another phone"));
            } catch (Exception e2) {
                toast("Share failed: " + e2.getMessage());
            }
        } catch (Exception e) {
            toast("Failed to share CA: " + e.getMessage());
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
            toast("Certificate reset failed: " + e.getMessage());
            appendUiLog("[app] Certificate reset failed: " + e.getMessage());
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
                // Toast sekali per versi (selaras MainActivity).
                android.content.SharedPreferences psp = getSharedPreferences(
                        ServerService.PREFS, MODE_PRIVATE);
                if (AutoUpdate.tawarkanBaru(
                        TgBackup.amanString(psp, AutoUpdate.KEY_TAWARAN_UPDATE, ""),
                        versi)) {
                    psp.edit().putString(AutoUpdate.KEY_TAWARAN_UPDATE, versi).apply();
                    SettingsActivity.this.toast("Update available: v" + versi);
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
            if (!Updater.webVaultReady(this, dataDir)) {
                return;
            }
            String marker = Updater.webVaultFromVersion(this);
            final String updated = marker != null ? marker
                    : Updater.readWvVersion(new File(dataDir, "web-vault/vw-version.json"));
            if (updated == null) {
                return;
            }
            String server = Updater.currentServerVersion(this);
            if (server == null || server.equals(updated)) {
                return;
            }
            final String key = "wv_notified_" + server;
            if (TgBackup.amanBoolean(sp, key, false)) {
                return;
            }
            final String pinWvDlg = Updater.kuncianWebVault(this);
            final boolean dikunciDlg = pinWvDlg != null && !pinWvDlg.isEmpty();
            ui.post(() -> {
                // Cek update berjalan di worker: Activity bisa sudah ditutup
                // saat hasilnya datang. Dialog dengan token mati = BadTokenException.
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                // Tandai baru setelah dialog benar-benar tampil; bila dibatalkan
                // karena Activity mati, tawaran tetap muncul di kunjungan berikut.
                sp.edit().putBoolean(key, true).apply();
                new AlertDialog.Builder(this)
                        .setTitle("Web Vault update available")
                        .setMessage("Current web vault v" + updated
                                + ", while the server is v" + server + ".\n\n"
                                + (dikunciDlg
                                        ? "Web vault version pinned to v" + pinWvDlg
                                                + ": sync now? (one ~35 MB download, "
                                                + "takes effect after a server restart)"
                                        : "Update the web vault now? (one ~35 MB download, "
                                                + "takes effect after a server restart)"))
                        .setPositiveButton("Update & Restart",
                                (d, w) -> runWebVaultUpdate(true))
                        .setNeutralButton("Update only",
                                (d, w) -> runWebVaultUpdate(false))
                        .setNegativeButton("Later", null)
                        .show();
            });
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
                toast("Web-vault update failed: " + e.getMessage());
                appendUiLog("[app] Web-vault update failed: " + e);
            }
        });
    }

    // ─── Battery optimization ───────────────────────────────────────────

    /** Sambung ulang rantai fokus D-pad di sekitar baris baterai. Saat baris
     *  GONE, autoStart langsung menuju autoUpdate (dan sebaliknya) agar fokus
     *  tak berhenti di tombol yang tak tampil. */
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
            toast("Battery optimization is for Android 6+ only.");
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("Already exempt from battery optimization.");
            return;
        }
        try {
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            toast("Failed to open settings: " + e.getMessage());
        }
    }

    // ─── Backup & Restore database lokal ────────────────────────────────

    private void backupDatabase(String dataDirMentah) {
        // Kunci data bersama (sama dengan backup bot & restore UI). Kedua jalur
        // memakai file temp verifikasi yang sama, jadi tak boleh tumpang tindih:
        // tanpa kunci, satu pihak bisa menghapus temp pihak lain lalu backup sah
        // dinyatakan korup dan dibuang.
        if (!TgBackup.kunciBackup()) {
            toast("Another backup/restore task is already running, try again shortly.");
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
                toast("Invalid data folder — reverted to default.");
                appendUiLog("[app] Folder data tidak valid: '" + dataDir + "' - direset ke bawaan.");
                dataDir = ServerService.dataDirBawaanSegar();
            }

            File dbFile = new File(dataDir, "db.sqlite3");
            if (!dbFile.exists()) {
                toast("No database yet: " + dbFile.getAbsolutePath());
                return;
            }
            // Tulis WAL ke DB utama dulu agar backup konsisten seperti backup Telegram.
            TgBackup.checkpointWal(dbFile);
            long free = TgBackup.freeBytes(this, dataDir);
            if (free >= 0 && free < 50L * 1024 * 1024) {
                toast("Warning: storage almost full, only " + TgBackup.humanBytes(free));
                appendUiLog("[app] Peringatan storage tinggal " + TgBackup.humanBytes(free));
            }

            File backupDir = new File(dataDir, "backups");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }

            // Tolak backup DB korup diam-diam seperti jalur Telegram; DB yang
            // sedang terkunci sesaat (SIBUK) bukan korup — beri pesan coba lagi.
            try {
                String rusakDb = TgBackup.cekIntegritasDbUlang(dbFile);
                if (TgBackup.dbSibuk(rusakDb)) {
                    toast("Database busy (server is writing) — backup cancelled, try again shortly.");
                    appendUiLog("[app] Backup dibatalkan: DB sibuk (" + rusakDb + ").");
                    return;
                }
                if (rusakDb != null) {
                    toast("Corrupt database (" + rusakDb + ") — backup cancelled.");
                    appendUiLog("[app] Backup dibatalkan: DB korup (" + rusakDb + ").");
                    return;
                }
            } catch (Exception abaikan) {
                // JVM unit test tanpa SQLite Android / galat SQLite vegetatif:
                // lanjut tanpa cek. Hanya Exception — Error (OOM dsb) tak boleh
                // ditelan diam-diam lalu backup tetap dijalankan.
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
                    throw new java.io.IOException("Failed to replace the old backup.");
                }
                if (!tmpZip.renameTo(backup)) {
                    throw new java.io.IOException("Failed to install the backup.");
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
                    toast("Corrupt backup (" + galatIsi + ") — discarded, try again.");
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
            toast("Backup saved:\n" + backup.getAbsolutePath());
            appendUiLog("[app] Backup DB: " + backup.getName() + " (" + backup.length() + " bytes)");
        } catch (Exception e) {
            toast("Backup failed: " + e.getMessage());
            appendUiLog("[app] Backup failed: " + e);
        }
    }

    /** Sisakan 10 export terenkripsi terbaru agar folder backups tak penuh export lama. */
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
            // Urut langsung via lastModified (tanpa peta File->Long: daftar
            // export hanya segelintir berkas sehingga stat per banding murah
            // dan tanpa autoboxing + lookup peta per banding).
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

    // File picker klasik tanpa androidx agar APK tetap kecil + kompatibel API 21.
    @SuppressWarnings("deprecation")
    private void pickRestoreFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_RESTORE);
        } catch (Exception e) {
            toast("Failed to open the file picker: " + e.getMessage());
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
                    "The current database will be replaced with the selected file. "
                            + "An automatic backup is made first. Continue?",
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
            // Chooser kembali bukan tanda target selesai membaca: aplikasi async
            // (Gmail/Drive) mengunggah di latar setelah kita kembali. Jangan
            // hapus di sini; sapu basi (<2 menit dipertahankan, lihat EXPORT_PLAIN_TTL_MS).
            sapuExportPlainBasi();
        } else if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            confirm("Import Settings",
                    "All app settings will be replaced from this file. Continue?",
                    () -> runBusy(() -> importConfig(uri)));
        }
    }

    private void restoreDatabase(Uri uri, String dataDirMentah) {
        // Satu tugas data dalam satu waktu (lihat kunciRestore/kunciBackup):
        // bot /restore, tombol UI, dan backup terjadwal menulis/membaca
        // folder data yang sama sehingga tak boleh tumpang tindih.
        if (!TgBackup.kunciRestore()) {
            toast("Another backup/restore task is already running, try again shortly.");
            appendUiLog("[app] Restore ditolak: tugas backup/restore lain sedang berjalan.");
            return;
        }
        try {
            // Kunci data sampai ekstraksi selesai: Start yang datang setelah
            // ini ditolak, dan Start yang sudah jalan ditunggu selesai.
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
            toast("Invalid data folder — reverted to default.");
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
                toast("Server failed to stop — restore cancelled to protect the DB.");
                appendUiLog("[app] Restore dibatalkan: server masih berjalan.");
                return;
            }
            if (ServerService.isProcessAlive()) {
                toast("Server is still running — restore cancelled to protect the DB.");
                appendUiLog("[app] Restore dibatalkan: server masih berjalan.");
                return;
            }
            // Proses yatim (anak server yang selamat saat app dibunuh sistem)
            // tak terlihat isProcessAlive tetapi masih memegang db.sqlite3.
            if (!ServerService.bereskanServerYatim()) {
                toast("An old server process is still running — restore cancelled to protect the DB.");
                appendUiLog("[app] Restore dibatalkan: proses vaultwarden yatim masih hidup.");
                return;
            }
            // Sisa staging restore lama (kill di tengah) tak boleh ikut
            // terbaca sebagai bagian backup baru.
            TgBackup.hapusStagingRestore(new File(dataDir));
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
                    toast("Restore failed: file cannot be opened.");
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
                        // Entri DB ditulis ke staging, bukan DB hidup: hasil
                        // ekstrak divalidasi dulu lalu dipasang atomik.
                        String staging = TgBackup.namaStagingRestore(name);
                        if (staging != null && entry.isDirectory()) {
                            // "db.sqlite3/" sebagai direktori bukan DB sah.
                            zis.closeEntry();
                            continue;
                        }
                        File out = new File(dataFolder, staging != null ? staging : name);
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
                        toast("Empty file — restore cancelled.");
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
                        toast("File is not a SQLite database — restore cancelled.");
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
                                throw new java.io.IOException("Database file exceeds "
                                        + Util.BATAS_UNZIP_RESTORE + " bytes.");
                            }
                            fos.write(buf, 0, len);
                        }
                    }
                    if (salin < 512) {
                        throw new java.io.IOException("Database file truncated (not a complete SQLite file).");
                    }
                    // WAL lama milik DB lama: buang agar tak ditempel ke DB baru
                    // (checkpoint sebelum restore sudah mengosongkannya).
                    TgBackup.hapusWalShm(new File(dataDir));
                    restored = true;
                }
            }
            if (!restored) {
                // Zip berisi -wal/-shm/tls tanpa db.sqlite3: buang staging DB
                // (WAL asing tak boleh menempel ke DB lama) + kembalikan TLS
                // yang ikut tertimpa dari potret.
                TgBackup.hapusStagingRestore(new File(dataDir));
                TgBackup.hapusWalShm(new File(dataDir));
                TgBackup.kembalikanTls(new File(dataDir, "tls"), tlsAsal);
                toast("Backup file does not contain db.sqlite3.");
                appendUiLog("[app] Restore gagal: file zip tanpa db.sqlite3");
                return;
            }
            // Validasi + pasang DB staging secara atomik: DB lama baru diganti
            // setelah backup lolos header + quick_check, jadi kill di tengah
            // ekstraksi tak bisa meninggalkan db.sqlite3 parsial.
            String galatDb = TgBackup.pasangDbRestoreAtomik(new File(dataDir));
            if (galatDb != null) {
                TgBackup.hapusStagingRestore(new File(dataDir));
                TgBackup.kembalikanTls(new File(dataDir, "tls"), tlsAsal);
                toast("Corrupt backup (" + galatDb + ") — old database kept.");
                appendUiLog("[app] Restore gagal: " + galatDb + ", DB lama dipertahankan.");
                return;
            }
            // Restore valid: baru sekarang identitas + pengaturan ikut berganti.
            // Runtime membaca TLS internal dulu: sinkronkan hasil restore agar
            // identitas server benar-benar berganti (bukan memakai CA lama diam-diam).
            TgBackup.sinkronTlsKeInternal(SettingsActivity.this, new File(dataDir));
            if (zipCfg != null) {
                paksaCacheUbah = true;
                TgBackup.applyPrefsFromJson(SettingsActivity.this,
                        zipCfg.optJSONObject("prefs"));
                sanitizePortPref();
                // Sisi data (jadwal backup/bot) dikerjakan langsung, bukan di
                // dalam gerbang UI: restore bisa selesai sesudah Activity
                // dihancurkan, dan jadwal dari prefs hasil restore tetap wajib
                // terpasang walau tampilan tak perlu disegarkan lagi.
                SharedPreferences sp2 = getSharedPreferences(
                        ServerService.PREFS, MODE_PRIVATE);
                TgBackup.schedule(SettingsActivity.this,
                        TgBackup.amanBoolean(sp2, TgBackup.KEY_TG_AUTO, false));
                TgBot.schedule(SettingsActivity.this);
                appendUiLog("[app] Pengaturan dari backup ikut diterapkan.");
                uiPost(this::reloadSettingsFromPrefs);
            }
            toast("Database restored. Restart the server to use it.");
            appendUiLog("[app] DB direstore. Ukuran: " + dbFile.length() + " bytes");
        } catch (Exception e) {
            // Gagal sebelum swap: DB lama belum tersentuh, cukup buang staging.
            // Gagal sesudah swap: pulihkan dari salinan pengaman lewat staging
            // + rename agar mati di tengah rollback tak meninggalkan DB parsial.
            try {
                TgBackup.kembalikanTls(new File(dataDir, "tls"), tlsAsal);
                TgBackup.pulihkanDbLama(new File(dataDir), dbFile, preBackup);
                appendUiLog(preBackup != null && preBackup.exists()
                        ? "[app] Restore gagal: database lama dikembalikan."
                        : "[app] Restore gagal: file parsial dibuang.");
            } catch (Exception ignored) {
            }
            toast("Restore failed: " + e.getMessage());
            appendUiLog("[app] Restore failed: " + e);
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
                toast("Invalid data folder, using default.");
            }
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .putString(ServerService.KEY_DATA_DIR, aman).apply();
            paksaCacheUbah = true;
        }
        runBusy(() -> {
            // Nama unik per unduhan: dialog konfirmasi pertama yang menggantung
            // tak boleh dibajak unduhan kedua yang menimpa file tetap yang sama.
            // Prefix vwtg-restore dipertahankan agar tetap dilarang dibagikan
            // (lihat FileShareProvider.berkasSementara).
            File tmp = new File(getCacheDir(),
                    "vwtg-restore-" + TgBackup.stempelUnik() + ".zip");
            try {
                String name = TgBackup.downloadLastBackup(SettingsActivity.this, tmp);
                final String fname = name;
                // Arsip terenkripsi tetap utuh sampai user menekan Ya; dekrip
                // dikerjakan sesudah konfirmasi agar plaintext tak mengendap
                // di cache bila dialog tak jadi dijawab.
                ui.post(() -> {
                    // Activity sudah tutup: jangan tampilkan dialog bertoken
                    // mati, dan jangan tinggalkan arsip unduhan di cache.
                    if (isFinishing() || isDestroyed()) {
                        try {
                            tmp.delete();
                        } catch (Exception ignored) {
                        }
                        return;
                    }
                    confirm("Restore from Telegram",
                            "Use backup '" + fname + "'? The server will be stopped first. Continue?",
                            () -> runBusy(() -> restoreTelegramTerkonfirmasi(tmp)),
                            // Batal = arsip tak dipakai: hapus sekarang, jangan
                            // menunggu sapu basi 1 jam.
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
                toast("Failed to fetch backup: " + e.getMessage());
                appendUiLog("[tg] Failed to fetch backup: " + e);
            }
        });
    }

    // Dekrip (bila perlu) + restore sesudah user konfirmasi; arsip unduhan
    // maupun plaintext selalu dibersihkan dari cache sesudahnya.
    private void restoreTelegramTerkonfirmasi(File unduhan) {
        // Pasangan unik dari file unduhan (bukan nama tetap): dua dialog
        // terkonfirmasi berurutan tak boleh berebut plaintext yang sama.
        File plain = new File(getCacheDir(),
                unduhan.getName().replace(".zip", "-dec.zip"));
        try {
            File zip = unduhan;
            SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String pass = TgBackup.amanString(sp, TgBackup.KEY_TG_PASS, "");
            if (TgBackup.isEncrypted(unduhan)) {
                if (pass == null || pass.trim().isEmpty()) {
                    toast("Backup is encrypted \u2014 enter the backup password first.");
                    return;
                }
                TgBackup.decryptFile(unduhan, plain, pass.trim());
                zip = plain;
            }
            restoreFromZip(zip);
        } catch (Exception e) {
            toast("Restore failed: " + e.getMessage());
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

    // Restore Telegram didelegasikan ke TgBackup agar satu implementasi:
    // stop server, pre-backup, validasi SQLite + rollback, terapkan pengaturan,
    // dan jaga identitas bot. Folder yang diketik (belum di-Start) disimpan
    // dulu ke prefs di UI thread agar dipakai sebagai folder tujuan.
    private void restoreFromZip(File zip) {
        if (!TgBackup.kunciRestore()) {
            toast("Another backup/restore task is already running, try again shortly.");
            appendUiLog("[app] Restore ditolak: tugas backup/restore lain sedang berjalan.");
            return;
        }
        try {
            appendUiLog("[app] Menghentikan server sebelum restore...");
            String msg = TgBackup.restoreFromZip(this, zip);
            uiPost(() -> {
                reloadSettingsFromPrefs();
                toast("Restore done. Press Start to run it.");
            });
            appendUiLog("[app] " + msg);
        } catch (Exception e) {
            toast("Restore failed: " + e.getMessage());
            appendUiLog("[app] Restore failed: " + e);
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
        confirm("Export Settings",
                "The file contains no token/PIN/password (they stay on this device)."
                        + (terkunci ? " File is encrypted with the backup password."
                                : " Without a backup password this file is PLAINTEXT and readable"
                                        + " by other apps — store it carefully.")
                        + " Still, do not share it with others. Continue?",
                () -> {
                    // Baca widget di UI thread (bukan di worker runBusy).
                    String dir = dataDirInput.getText() == null ? ""
                            : dataDirInput.getText().toString();
                    runBusy(() -> exportConfig(dir));
                });
    }

    /** Maks file export config di cache (batasi tumpukan export berulang). */
    static final int BATAS_FILE_EXPORT = 3;

    /** Pilih nama file yang wajib dihapus agar sisa tak melebihi batas:
     *  yang terlama dibuang dulu, nama yang dikecualikan dipertahankan tapi
     *  tetap memakan jatah (tanpa ini pending basi + 3 file = 4 menumpuk
     *  tiap siklus). Murni agar bisa unit test (peta nama -> lastModified). */
    static java.util.List<String> pilihHapusBatasExport(
            java.util.Map<String, Long> namaKeWaktu, int batas, String kecualikan) {
        java.util.List<java.util.Map.Entry<String, Long>> semua =
                new java.util.ArrayList<>(namaKeWaktu.entrySet());
        java.util.Collections.sort(semua,
                (a, b) -> Long.compare(b.getValue(), a.getValue()));
        java.util.List<String> hapus = new java.util.ArrayList<>();
        // Yang dikecualikan tetap memakan satu jatah: tanpa ini pending basi
        // + 3 file lolos cap (+1 tiap siklus). Jatah sisa untuk yang lain.
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

    /** Sapu sisa config plaintext sementara di cache internal. File pending yang
     *  masih segar (<2 menit) dilewati: target berbagi async (Gmail/Drive) membaca
     *  stream setelah chooser kembali, jadi hapus langsung membuat kirim gagal.
     *  Pembersihan mengandalkan sapu basi di onCreate/onResume/onDestroy. */
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
                        // Sisa plaintext cabang terenkripsi tak pernah dibagikan:
                        // sapu tanpa pandang umur agar sisa kill di jendela
                        // tulis-enkripsi-hapus tak mengendap sampai basi.
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
                        // Hanya sapu yang basi agar target async tak kehilangan file.
                        // mtime berbasis jam dinding: jam yang dimundurkan membuat
                        // umur negatif — anggap segar (disapu belakangan), jangan
                        // hapus file yang mungkin masih dibaca target berbagi.
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
                        // Arsip backup Telegram sisa proses yang mati saat dialog
                        // konfirmasi terbuka (atau dibatalkan versi lama yang
                        // belum menghapus). Bisa puluhan MB dan berisi DB:
                        // sapu yang sudah basi, tapi jangan sentuh unduhan yang
                        // sedang menunggu jawaban dialog.
                        long umur = System.currentTimeMillis() - f.lastModified();
                        if (umur < 0) {
                            umur = 0;
                        }
                        if (umur > RESTORE_TG_TTL_MS) {
                            f.delete();
                        }
                    }
                }
            }
            batasiFileExportCache(pendingNamaSegar, sisa);
        } catch (Exception ignored) {
        }
    }

    /** Varian hemat I/O: pakai ulang hasil listFiles() pemanggil agar direktori
     *  cache tak dienumerasi dua kali (dulu sapu + batas masing-masing stat). */
    private void batasiFileExportCache(String pendingNamaSegar, File[] kini) {
        try {
            if (kini == null) {
                return;
            }
            java.util.Map<String, Long> peta = new java.util.HashMap<>();
            java.util.Map<String, File> berkas = new java.util.HashMap<>();
            for (File f : kini) {
                String n = f.getName();
                // Sisa enctmp sudah disapu tanpa pandang umur di atas; jangan
                // biarkan ia memakan jatah cap milik file bagi yang pending.
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

    /** Sapu file config plaintext yang sudah basi (best-effort, dipakai
     *  onCreate/onResume/onDestroy). File pending yang masih segar (<2 menit)
     *  dipertahankan. */
    private void sapuExportPlainBasi() {
        try {
            File pending = exportPlainTertunda;
            if (pending != null
                    && SystemClock.elapsedRealtime() - exportPlainPada >= EXPORT_PLAIN_TTL_MS) {
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
                toast("Invalid data folder — reverted to default.");
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
                File plain = new File(getCacheDir(), PREFIX_ENCTMP + ts + ".json");
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
                    // Via result: plaintext disapu saat user kembali (TTL 2 menit)
                    // agar tak mengendap di cache (target membaca
                    // stream saat ia foreground, sebelum kita kembali).
                    startActivityForResult(
                            Intent.createChooser(send, "Share configuration file"),
                            REQ_SHARE_CONFIG);
                } catch (Exception e2) {
                    exportPlainTertunda = null;
                    toast("File saved: " + path);
                    appendUiLog("[app] Config tersimpan (bagi gagal): " + path);
                    return;
                }
                toast("Configuration exported: " + path);
                appendUiLog("[app] Config export: " + path);
            });
        } catch (Exception e) {
            toast("Config export failed: " + e.getMessage());
            appendUiLog("[app] Config export failed: " + e);
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
            toast("Failed to open the file picker: " + e.getMessage());
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
                    toast("Config import failed: file cannot be opened.");
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
                                "File too large (>512 KB) — not a valid config.");
                    }
                    fos.write(buf, 0, n);
                }
            }
            File src = tmp;
            if (TgBackup.isEncrypted(tmp)) {
                // Budget lintas dialog (kunci sidik isi, bukan path tmp yang unik
                // tiap impor): buka-ulang berkas yang sama tak boleh dapat 5x baru.
                String sidikImpor = sidikBerkas(tmp);
                if (imporDiblokir(ambilGagalImpor(sidikImpor))) {
                    tmp.delete();
                    sapuSisaImpor();
                    toast("Too many wrong attempts — import cancelled.");
                    appendUiLog("[app] Import diblokir: password salah 5x (berkas sama)");
                    return;
                }
                SharedPreferences sp0 = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
                String pass0 = TgBackup.amanString(sp0, TgBackup.KEY_TG_PASS, "");
                if (pass0 == null || pass0.trim().isEmpty()) {
                    // Tanpa password perangkat, langsung tanya password asal
                    // (file bisa dari password mana pun, bukan harus kini).
                    tanyaPasswordImpor(tmp, capImpor,
                            "Config is encrypted — enter the password used when the file was created.",
                            sidikImpor);
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
                            "Device password does not match — enter the password used when the file was created.",
                            sidikImpor);
                    return;
                }
                tmp.delete();
                src = plain;
            }
            terapkanImporJson(src);
        } catch (Exception e) {
            sapuSisaImpor();
            toast("Config import failed: " + e.getMessage());
            appendUiLog("[app] Config import failed: " + e);
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
                toast("Invalid config file (not this app's export).");
                appendUiLog("[app] Import ditolak: marker app tidak cocok");
                return;
            }
            JSONObject prefs = root.optJSONObject("prefs");
            if (prefs == null) {
                toast("Invalid config file.");
                return;
            }
            paksaCacheUbah = true;
            TgBackup.applyPrefsFromJson(this, prefs);
            sanitizePortPref();
            // Flag wizard + jadwal = efek data, bukan tampilan: kerjakan di
            // luar gerbang UI agar impor yang selesai sesudah Activity mati
            // tetap menandai wizard selesai dan memasang jadwal.
            SharedPreferences sp2 = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            sp2.edit().putBoolean(KEY_WIZARD_DONE, true).apply();
            TgBackup.schedule(SettingsActivity.this,
                    TgBackup.amanBoolean(sp2, TgBackup.KEY_TG_AUTO, false));
            TgBot.schedule(SettingsActivity.this);
            appendUiLog("[app] Config import selesai.");
            uiPost(() -> {
                reloadSettingsFromPrefs();
                toast("Settings imported. Press Start to apply.");
            });
        } catch (Exception e) {
            sapuSisaImpor();
            toast("Config import failed: " + e.getMessage());
            appendUiLog("[app] Config import failed: " + e);
        }
    }

    /** Minta password asal file config terenkripsi lalu coba impor.
     *  File dari password lama tak bisa dibuka password perangkat kini.
     *  Password asal hanya dipakai dekrip; password perangkat tak diubah
     *  (applyPrefsFromJson mempertahankan tg_pass perangkat). */
    private void tanyaPasswordImpor(final File tmpEnkrip, final String capImpor,
            String alasan, final String sidikImpor) {
        final String pesan = alasan;
        // importConfig jalan di worker runBusy: dialog wajib dibuat di UI thread
        // (ViewRoot tanpa Looper = crash). Seluruh alur dialog di bawah ini.
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
                    .setTitle("Config file password")
                    .setMessage(pesan)
                    .setView(input)
                    .setPositiveButton("Try import", null)
                    .setNegativeButton("Cancel", (d, w) -> {
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
            // Benih dari budget lintas dialog: buka-ulang berkas yang sama
            // melanjutkan hitungan, bukan mengulang dari nol.
            final int[] gagalImpor = {ambilGagalImpor(sidikImpor)};
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        String coba = input.getText().toString();
                        if (coba == null || coba.trim().isEmpty()) {
                            input.setError("Enter the password first");
                            return;
                        }
                        final String rahasia = coba.trim();
                        // PBKDF2 100rb iterasi di worker (bukan UI): dialog kini
                        // tampil di UI thread dan dekrip di sini macetkan STB.
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
                                    // Batas percobaan selaras lockout PIN (5x):
                                    // hitungan kumulatif per berkas sehingga
                                    // buka-ulang dialog tak memberi jatah baru.
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
                                        toast("Too many wrong attempts — import cancelled.");
                                        appendUiLog("[app] Import dibatalkan: password salah 5x");
                                    } else {
                                        input.setError("Wrong password / corrupt file (" + sudah + "/5)");
                                        appendUiLog("[app] Import ditolak: password asal tak cocok");
                                    }
                                });
                            }
                        });
                        if (!masukImpor) {
                            // Antrean penuh: aktifkan lagi tombol dialog agar
                            // tak terkunci permanen, beri tahu user.
                            v.setEnabled(true);
                            toast(getString(R.string.pin_sibuk));
                            appendUiLog("[app] Impor ditunda: antrean tugas penuh.");
                        }
                    }));
            dialog.show();
        } catch (Exception e) {
            try {
                tmpEnkrip.delete();
            } catch (Exception ignored) {
            }
            toast("Config import failed: " + e.getMessage());
        }
        });
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
        // SetChecked programatik jangan memicu listener: tanpa penjaga ini
        // import config bisa memunculkan konfirmasi "matikan PIN" palsu, atau
        // (bila config ber-PIN sementara kolom PIN kosong) mencentang lalu
        // membatalkannya sendiri sehingga centang desync dengan prefs.
        pinCentangProgram = true;
        pinEnabledCheck.setChecked(TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false));
        pinCentangProgram = false;
        validasiInline();
    }

    private void copyLocalUrl() {
        String url = ServerService.localUrl(this);
        if (url.contains("127.0.0.1") || url.contains("localhost")) {
            salinTeks(url, "Loopback URL copied (LAN IP not detected)");
        } else {
            salinTeks(url, "Network URL copied");
        }
    }

    /** Port dari form untuk tombol salin URL lokal (selaras service:
     *  rusak/privileged jatuh ke default agar URL salinan selalu valid). */
    private String portEfektifUntukSalin() {
        CharSequence cs = portInput == null ? null : portInput.getText();
        return ServerService.normalisasiPort(cs == null ? "" : cs.toString().trim());
    }

    /** Baca maksimal max byte; lempar bila lebih (tolak file raksasa agar tidak OOM). */
    private static byte[] readCapped(InputStream in, int max) throws Exception {
        // Pra-alokasi 8 KB selaras buffer baca (alasan sama dengan bacaTerbatas).
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) {
                throw new java.io.IOException("File too large (>512 KB) — not a valid config.");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** Kembalikan port ke default bila hasil import bukan angka 1-65535. */
    private void sanitizePortPref() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        // Selaras normalisasiPort service (1024-65535): port privileged (<1024)
        // butuh root dan selalu gagal bind, jadi jangan dipertahankan diam-diam.
        // (amanString tak pernah lempar ClassCastException: sembuh sendiri.)
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

    // ─── PIN lock ───────────────────────────────────────────────────────

    private void maybeShowPinLock() {
        // Aturan PIN mati / grace / hash rusak ada di PinGate (satu pintu,
        // diuji); activity hanya membuka layar kunci + guard anti-tumpuk.
        if (!PinGate.perlukanLayarPin(this) || pinBukaJalan) {
            return;
        }
        pinBukaJalan = true;
        // Halaman login PIN layar penuh (bukan popup): hasil via onActivityResult.
        try {
            startActivityForResult(new Intent(this, PinActivity.class), REQ_PIN);
        } catch (Exception e) {
            pinBukaJalan = false;
        }
    }

    /** Isi folder backups dengan cache 5 dtk (dipakai dua baris info). */
    private File[] daftarBackup(File backupDir) {
        if (backupDir == null) {
            return null;
        }
        // Jam monotonik: wall-clock mundur (NTP) membekukan daftar sampai jam mengejar.
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

    /** Baris info DB. bolehHitung=false = cache saja (tanpa I/O stat):
     *  dipakai UI thread saat worker berat sedang menghitung agar buka
     *  Settings tak jank dua kali (worker me-repaint saat selesai). */
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
            dbLine = "DB: " + TgBackup.humanBytes(db.length()) + " | Local backups: " + n;
        } catch (Exception e) {
            dbLine = "";
        }
        return dbLine;
    }

    /** Rincian storage: DB, backup lokal, web-vault, binary (satu baris ringkas).
     *  bolehHitung=false = cache saja (lihat dbInfoLine). */
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

    /** Baris info versi web-vault (bundled/updated) + peringatan bila beda dari server.
     *  bolehHitung=false = cache saja (lihat dbInfoLine). */
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
            String updated = Updater.webVaultFromVersion(this);
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
            StringBuilder sb = new StringBuilder("Web vault: ").append(wv)
                    .append(updated != null ? " (updated)" : " (bundled)");
            String server = Updater.currentServerVersion(this);
            if (server != null && !server.equals(wv)) {
                sb.append(" \u26A0 server version differs v").append(server);
            }
            wvLine = sb.toString();
        } catch (Exception e) {
            wvLine = "";
        }
        return wvLine;
    }

    /** RAM proses server, uptime, dan sisa storage (satu baris ringkas). */
    /** Baris info RAM/uptime/storage (lihat dbInfoLine soal bolehHitung). */
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
        long free = TgBackup.freeBytes(this, dataDir);
        if (free > 0) {
            if (sb.length() > 0) {
                sb.append(" \u00B7 ");
            }
            sb.append("Free ").append(TgBackup.humanBytes(free));
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
    /** Baris info sertifikat (lihat dbInfoLine soal bolehHitung). */
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
                return "\u26A0 TLS certificate expires in " + days + " days";
            }
            return "TLS certificate: " + days + " days";
        } catch (Exception e) {
            return "";
        }
    }


    // ─── Update binary (unduh per tag versi resmi) ──────────────────────

    private void checkForUpdate() {
        try {
            appendUiLog("[app] Mengecek update dari sumber resmi...");
            // Shim dulu agar uji --version di dalam tryUpdate lolos di kernel lama.
            String imbuhShim = "";
            if (KernelCompat.legacyPerangkat()) {
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
            toast("Update check failed: " + e.getMessage());
            appendUiLog("[app] Update check failed: " + e);
        }
    }

    // ─── Pilih versi binary / web-vault (kunci versi) ───────────────────

    /** Teks tombol pilih versi: "Terbaru (otomatis)" atau "vX (terkunci)". */
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
        // Dipanggil via ui.post dari worker daftar versi: activity bisa sudah
        // finishing/destroyed saat hasil datang -> show() melempar
        // WindowManager$BadTokenException. Cukup lewati.
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
        input.setHint(R.string.versi_manual_hint);
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
                            ? msg + " Tekan Start untuk memakai." : msg);
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
                    toast("Failed to install binary: " + e.getMessage());
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
                    lastWvCheck = 0; // paksa baca ulang info versi web-vault
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
                    toast("Web-vault update failed: " + e.getMessage());
                    appendUiLog("[app] Web-vault update failed: " + e);
                }
            });
        }
    }

    private void revertToBundled() {
        // Jangan cabut binary dari bawah proses yang hidup: proses tetap jalan
        // dengan inode lama tapi penanda versi ikut terhapus sehingga restart
        // berikut gagal sebelum unduh ulang selesai.
        if (ServerService.running || ServerService.isProcessAlive()) {
            toast("Stop the server before resetting the binary.");
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
            toast("Binary deleted. It will be re-downloaded (chosen version) on Start.");
            appendUiLog("[app] Binary di-reset; akan diunduh ulang saat Start.");
        } else {
            toast("No saved binary.");
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
                uiPost(r);
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

    /** Teks ringkasan per kartu dari prefs + input saat ini. */
    /** Ada isi non-spasi tanpa alokasi toString+trim (gerbang murah tiap ketikan). Murni. */
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

    /** Perbarui label status "terisi/belum diisi" di kartu Admin Token dan
     *  Telegram. Dulu satu paket dengan ringkasan kartu ber-Ubah, tetapi baris
     *  ringkasan itu selalu GONE (tak pernah dimunculkan) sehingga hanya
     *  membuang 5 getString + regex kuncian tiap ketikan. */
    private void perbaruiStatusIsian() {
        if (adminTokenInput == null) {
            return;
        }
        // Dipanggil juga dari catch blok runBusy (worker thread): sentuh View
        // hanya boleh di UI thread, kalau tidak Android melempar
        // CalledFromWrongThreadException (crash saat pasang versi gagal).
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

    /** Salin teks ke clipboard dengan toast ramah + bersih otomatis
     *  yang tahan mati proses (satu pintu LogActivity agar token rahasia
     *  tak mengendap bila app dibunuh dalam 30 dtk). */
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

    /** Bubuhkan ikon peringatan di depan pesan galat (saran 11). */
    private void awaliGalat(TextView baris, int stringId) {
        if (baris != null) {
            baris.setText(getString(R.string.galat_awalan, getString(stringId)));
        }
    }

    /** Validasi inline setiap ketikan (tanpa toast agar tak berisik). */
    /** Tampilkan galat inline tanpa layout sia-sia: setText/setVisibility
     *  yang isinya sama tetap memicu layout, jadi dilewati via tag cache.
     *  Tag menyimpan teks tampil terakhir ("" = valid). Aman API 21. */
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

    /** Samakan visibilitas tanpa layout sia-sia tiap ketikan. */
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

    /** Tombol bawah = Start saat berhenti, Stop saat jalan. Stop wajib selalu
     *  bisa ditekan walau input folder/port sedang tidak valid (dulu Stop ikut
     *  mati sehingga server tak bisa dihentikan); Start hanya aktif bila valid. */
    private void perbaruiEnabledStartStop() {
        if (startStopBawah == null) {
            return;
        }
        boolean boleh = ServerService.running || inputStartValid;
        if (startStopBawah.isEnabled() != boleh) {
            startStopBawah.setEnabled(boleh);
        }
    }

    /** Titik "●" + warna merah pada label yang nilainya beda dari server berjalan. */
    // getColor(int) lawas sengaja agar satu jalur kode untuk API 21-32.
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

    /** Galat inline untuk kolom port; null bila valid (kosong = bawaan). Murni.
     *  Pesan (msgSalah) diinjeksi dari resources agar teks hanya hidup di strings.xml. */
    static String galatPort(String port, String msgSalah) {
        // Trim sekali (dulu cek + parseInt trim sendiri; jalan tiap ketikan).
        String isi = port == null ? "" : port.trim();
        if (isi.isEmpty()) {
            return null;
        }
        try {
            int p = Integer.parseInt(isi);
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
        // Trim sekali di sini (dataDirAman trim lagi tapi atas string bersih
        // me-return this tanpa alokasi; jalan tiap ketikan).
        String isi = folder == null ? "" : folder.trim();
        if (isi.isEmpty()) {
            return null;
        }
        return ServerService.dataDirAman(isi) ? null : msgSalah;
    }

    /** Galat Admin Token; null bila valid (kosong = boleh, isi = minimal 8). Murni.
     *  Pesan (msgSalah) diinjeksi dari resources agar teks hanya hidup di strings.xml. */
    static String galatAdmin(String token, String msgSalah) {
        // Trim sekali (dulu cek + length trim sendiri; jalan tiap ketikan).
        String isi = token == null ? "" : token.trim();
        if (isi.isEmpty()) {
            return null;
        }
        return isi.length() >= 8 ? null : msgSalah;
    }

    /** Galat token bot Telegram; null bila valid (kosong = boleh). Murni.
     *  Pesan (msgSalah) diinjeksi dari resources agar teks hanya hidup di strings.xml. */
    static String galatTgToken(String token, String msgSalah) {
        // Trim sekali (dulu cek + assign trim sendiri; jalan tiap ketikan).
        String isi = token == null ? "" : token.trim();
        if (isi.isEmpty()) {
            return null;
        }
        return isi.contains(":") && isi.length() >= 20 ? null : msgSalah;
    }

    /** Galat Chat ID; null bila valid (kosong = boleh). Tiap entri ikut satu
     *  validator Util.chatTunggalValid (ID numerik opsional +/-, atau username).
     *  Pesan (msgSalah) diinjeksi dari resources agar teks hanya hidup di strings.xml.
     *  Murni. */
    static String galatChat(String chat, String msgSalah) {
        // Jalur cepat tanpa alokasi: ketikan umum satu chat tanpa pemisah
        // tak perlu ArrayList + contains() kuadrat tiap ketikan. Setara
        // pecahDaftarChat untuk satu token (sama-sama trim lalu validasi).
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

    private void confirm(String title, String message, final Runnable action) {
        confirm(title, message, action, null);
    }

    /** Varian dengan aksi saat dibatalkan (mis. hapus file sementara yang sudah
     *  terunduh). Batal karena Activity mati juga menjalankan {@code batal}. */
    private void confirm(String title, String message, final Runnable action,
                         final Runnable batal) {
        if (isFinishing() || isDestroyed()) {
            if (batal != null) {
                batal.run();
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Yes", (d, w) -> action.run())
                .setNegativeButton("Cancel", (d, w) -> {
                    if (batal != null) {
                        batal.run();
                    }
                })
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

    /** Dipakai dialog Tentang (AboutDialog): arahkan fokus ke tombol Update
     *  di kartu, bukan membuka instance Settings kedua. */
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

    /** Nonaktifkan tombol aksi + tampilkan "Sedang bekerja…" selama operasi. */
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
            toast("Still working, please wait.");
            return;
        }
        setBusy(true);
        // Pool lama (bukan ringan): semua tugas runBusy menit-lama (unduh
        // binary/web-vault, backup/restore/unggah Telegram, generate RSA)
        // sehingga di pool ringan memonopoli thread + membuat cek port dan
        // refresh terbuang di STB 1 GB (lihat BG_LAMA di Util).
        boolean masuk = Util.jalankanLama(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                // Tanpa ini exception tak terduga membunuh thread diam-diam:
                // UI kembali idle tanpa pesan. Catat + beri tahu user.
                // Kembalikan status interrupt agar kode hilir yang memeriksanya
                // tak buta (thread ini tak memakai cancel, tapi higienis).
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
                    toast("Task failed: " + (t.getMessage() == null ? t.toString() : t.getMessage()));
                } catch (Exception ignored) {
                }
            } finally {
                tugasBerjalan.set(false);
                setBusy(false);
            }
        });
        if (!masuk) {
            // Antrean penuh: lepas status sibuk agar tombol tak terkunci
            // permanen sampai Activity dibuat ulang.
            tugasBerjalan.set(false);
            setBusy(false);
            toast("Task queue is full, try again shortly.");
        }
    }

    private void appendUiLog(String line) {
        ServerService.catatLog(line);
        // Jam elapsed (bukan dinding): jam yang dimundurkan membuat selisih
        // negatif sehingga refresh macet sampai jam menyusul. Ledakan log
        // (mis. output binary) tidak boleh membanjiri UI thread.
        long now = SystemClock.elapsedRealtime();
        if (now - lastUiLogRefresh > 500) {
            lastUiLogRefresh = now;
            ui.post(refreshLoop);
        }
    }

    /** Bersihkan field PIN agar tak mengendap di Editable/histori view.
     *  clear() dulu (buang buffer), setText("") sebagai fallback. */
    /** True bila PIN aktif DAN sudah ada hash tersimpan: mengetik PIN baru
     *  berarti MENGGANTI PIN lama sehingga wajib konfirmasi. Murni. */
    static boolean perluKonfirmasiGantiPin(boolean pinAktif, boolean adaHash) {
        return pinAktif && adaHash;
    }

    /** True bila prefs sudah menyimpan hash PIN (dibaca jarang: sekali per jeda ketik). */
    private boolean adaHashPinTersimpan() {
        try {
            String h = TgBackup.amanString(getSharedPreferences(
                    ServerService.PREFS, MODE_PRIVATE), PinGate.KEY_PIN_HASH, "");
            return h != null && !h.trim().isEmpty();
        } catch (Exception e) {
            // Fail-closed: anggap sudah ada hash -> minta konfirmasi.
            return true;
        }
    }

    /** Hash PIN di worker lalu tulis ke prefs bila nomor urut masih segar.
     *  Ditulis hanya bila PIN aktif (centang menyala) agar toggle mati tak
     *  meninggalkan hash basi. */
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
            pinHashSiap = h;
            if (h == null) {
                return;
            }
            // Nomor urut cukup sebagai penanda settle; hash bersalt tak perlu
            // pasangan plaintext di field.
            if (seq == pinSeq && h.equals(pinHashSiap)
                    && !pinAktifJalan && pinAktif) {
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

    /** Posting ke UI dengan gerbang siklus hidup: worker bisa menyelesaikan
     *  tugas sesudah Activity dihancurkan (callback sudah terpasang sebelum
     *  onDestroy membuang antrean), dan dialog/startActivityForResult di
     *  Activity mati melempar BadTokenException / memunculkan jendela hantu.
     *  Untuk pekerjaan UI yang menyentuh view/dialog; perubahan data murni
     *  boleh tetap memakai ui.post langsung. */
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
        private String nilaiTerbaru = "";
        private int generasiTulis = 0;
        // Satu Runnable dipakai ulang: tanpa new Runnable/ArrayList per karakter.
        // Method reference (bukan lambda self-reference) agar javac lolos.
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
            // Debounce: mengetik token panjang tak menulis flash per karakter.
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
        try {
            siramTulisTertunda();
        } catch (Exception ignored) {
        }
        pinHashSiap = null;
        // Ketikan PIN yang masih dalam jeda 800 ms jangan hilang saat Back:
        // jalankan sekarang (submit ke worker) sebelum antrean UI dibuang.
        Runnable tundaPin = pinTunda;
        pinTunda = null;
        if (tundaPin != null) {
            ui.removeCallbacks(tundaPin);
            tundaPin.run();
        }
        sapuExportPlainBasi();
        // Executor hash PIN milik kelas (bersama + daemon): tanpa shutdown agar
        // hash yang antre tepat sebelum keluar/rotasi tetap tersimpan di prefs
        // (satu hash 30rb cuma ~1 dtk); thread daemon tunggal tak menahan
        // proses dan tak bocor per instance. Callback debounce UI ikut dibuang
        // via removeCallbacks di bawah.
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }
}
