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
import android.app.Dialog;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
    private static final int REQ_PIN = 1002;
    private static final String DEFAULT_PORT = ServerService.DEFAULT_PORT;
    /** Ekor log layar awal dibatasi agar STB RAM kecil tidak patah (item saran 6). */
    private static final int MAKS_BARIS_LOG = 150;

    private final Handler ui = new Handler(Looper.getMainLooper());

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
    /** Teks status cache (pola Settings): tanpa getString tiap tick. */
    private String teksBerjalan = "";
    private String teksBerhenti = "";
    private String teksSibuk = "";
    private String lastShownNet = "";
    private String lastShownVersion = "";
    private String lastShownUptime = "";
    private boolean lastUpdBtnVisible = true; // paksa selaras rantai fokus saat refresh pertama
    private String lastUpdText = ""; // cegah setText+layout tiap tick saat teks sama
    private String memoCocokA = "\0"; // memo versiCocok(real, pending): regex tiap detik boros
    private String memoCocokB = "\0";
    private boolean memoCocokHasil = false;
    private String memoCocokA2 = "\0";
    private String memoCocokB2 = "\0";
    private boolean memoCocokHasil2 = false;
    private String lastVersiApp = "\0"; // gerbang versi sebelum StringBuilder tiap tick
    private String lastVersiBin = "\0";
    private String lastVersiBundled = "\0";
    private long lastUpDetik = -1; // gerbang uptime: durationText+getString hanya saat detik berubah
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
    // Cache URL lokal 10 detik: localUrl()/collectIps() tiap detik di UI thread
    // bikin ANR di STB lambat; pakai cache agar enumerasi jaringan jarang jalan.
    private static String cacheUrl = "";
    private static long cacheWaktu = 0;

    /** Kapan MainActivity terakhir pause (diagnostik, bukan jangkar grace). */
    private static volatile long pauseStamp = 0;
    /** Guard agar onResume beruntun tak menumpuk dialog PIN. */
    private boolean pinBukaJalan = false;

    /** Berbagi status buka PIN via PinGate (satu sumber, tanpa drift antar-activity). */
    static boolean pinBaruSajaDibuka() {
        return PinGate.dalamGraceBersama();
    }

    /** Kapan PIN dibuka (jangkar grace bersama, tanpa perpanjangan). */
    static long kapanDibuka() {
        return PinGate.kapanBukaBersama();
    }

    /** Catat buka PIN sebagai milik bersama (tanpa panggil balik antar-activity).
     *  Satu-satunya sumber keputusan grace adalah PinGate; tanpa cermin lokal. */
    static void catatPinDibuka() {
        if (!PinGate.dalamGraceBersama()) {
            PinGate.bukaKunciBersama();
        }
        pauseStamp = PinGate.kapanBukaBersama();
    }

    @Override
    // getPackageInfo lama sengaja agar satu jalur kode untuk API 21-32.
    @SuppressWarnings("deprecation")
    // commit() susulan di bawah disengaja (sinkron anti-ganda, lihat komentar) — bukan apply().
    @SuppressLint("ApplySharedPref")
    protected void onCreate(Bundle savedInstanceState) {
        // Splash ditampilkan lewat theme manifest, ganti ke tema utama di sini.
        setTheme(R.style.Theme_TasirinVaultwardenHost);
        super.onCreate(savedInstanceState);
        TgBackup.healkanStringPrefs(this);
        TgBackup.migrateAutoPref(this);
        // Sisa salinan clipboard yang penghapusnya tak sempat jalan karena
        // proses mati dibersihkan/dipasang ulang di sini (satu pintu LogActivity).
        LogActivity.bersihkanClipBasiJikaAda(this);
        // Privasi: nonaktifkan screenshot + preview recents dikosongkan.
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
        try {
            int buildBar = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionCode;
            TextView subBar = findViewById(R.id.toolbarSubtitle);
            subBar.setText(getString(R.string.toolbar_subtitle,
                    appVersion.isEmpty() ? "?" : appVersion, buildBar));
        } catch (Exception ignored) {
        }
        bundledVersion = readBundledVersion();
        bundledRaw = Updater.readBundledVersionRaw(this);
        teksBerjalan = getString(R.string.running);
        teksBerhenti = getString(R.string.stopped);
        teksSibuk = getString(R.string.busy_work);
        ui.post(this::refreshFromService);

        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        // Cek update otomatis saat dibuka
        Util.jalankanLama(this::autoUpdateCheck);
        // Keraskan hash PIN legasi di rest (tanpa menunggu login) agar prefs
        // bocor tak memberi SHA-256 tanpa salt yang retak dalam detik.
        final android.content.Context appPin = getApplicationContext();
        Util.jalankanBg(() -> PinGate.kuatkanHashDini(appPin));
        // Pastikan jadwal backup harian tetap terpasang
        TgBackup.schedule(this, TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false));
        // Remote kontrol via Telegram bot
        TgBot.schedule(this);
        // Susulan backup boot yang ditolak sistem (Android 12+ batasi start background).
        if (TgBackup.amanBoolean(sp, TgBackup.KEY_BACKUP_TERTUNDA, false)) {
            // commit() sinkron (bukan apply()): flag wajib hilang sebelum backup
            // susulan jalan agar kill tepat sesudahnya tak memicu backup ganda
            // di buka berikutnya, selaras AlarmReceiver/BootReceiver.
            try {
                sp.edit().remove(TgBackup.KEY_BACKUP_TERTUNDA).commit();
            } catch (Exception ignored) {
            }
            // Alarm basi: user mematikan auto-backup sebelum app dibuka -
            // jangan mengunggah tanpa persetujuan (selaras ACTION_TG_BACKUP).
            if (!TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false)) {
                ServerService.catatLog("[tg] Backup susulan boot dilewati:"
                        + " auto-backup sudah dimatikan.");
            } else {
            final android.content.Context app = getApplicationContext();
            Util.jalankanBg(() -> {
                try {
                    // Fail-fast: tanpa password otomatis wajib ditolak.
                    if (!TgBackup.bolehBackupOtomatis(
                            TgBackup.amanString(sp, TgBackup.KEY_TG_PASS, ""))) {
                        ServerService.catatLog("[tg] " + TgBackup.pesanTolakPlainOtomatis()
                                + " (susulan boot).");
                        return;
                    }
                    TgBackup.tungguBootStabil();
                    // Susulan boot = otomatis: wajib terenkripsi (fail-closed).
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
        // Susulan auto-start yang ditolak sistem dari background (Android 12+
        // membatasi start service): mulaiService menandainya, eksekusi di sini
        // saat app dibuka (sudah foreground sehingga diizinkan).
        if (TgBackup.amanBoolean(sp, ServerService.KEY_START_TERTUNDA, false)) {
            if (TgBackup.amanBoolean(sp, ServerService.KEY_AUTO_START, false)
                    && !ServerService.running) {
                appendUiLog("[app] Auto-start susulan: start background sempat ditolak sistem.");
                // Hapus flag hanya bila start sukses: gagal (lempar/false)
                // wajib dicoba lagi saat app dibuka berikutnya, bukan hilang
                // sampai reboot. Selaras BACKUP_TERTUNDA yang tak hangus sia-sia.
                try {
                    ServerService.start(this);
                } catch (Exception ignored) {
                }
                // Flag dihapus hanya bila server benar-benar jalan: intent
                // terkirim bukan jaminan exec sukses (unduh bisa gagal
                // kemudian); gagal wajib dicoba lagi buka-berikutnya.
                if (ServerService.running) {
                    try {
                        sp.edit().remove(ServerService.KEY_START_TERTUNDA).apply();
                    } catch (Exception ignored2) {
                    }
                }
            } else {
                // Auto-start mati / server sudah jalan: flag basi, buang.
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
        ui.post(this::refreshFromService);
        // Auto-lock PIN setiap kali app kembali ke depan
        maybeShowPinLock();
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
        // maybeShowPinLock mengunci bila jeda > PinGate.PIN_GRACE_MS.
        pauseStamp = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }

    /** Titik tiga: popup menempel di bawah tombol (ramah D-pad). */
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
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
        if (TextUtils.isEmpty(dataDir)) {
            dataDir = ServerService.dataDirBawaanSegar();
        }
        // Tolak folder berbahaya lebih awal (traversal, root storage, area
        // sistem): service juga menolak, tapi UI wajib memberi tahu jelas
        // sebelum cek port agar tak gagal start secara misterius.
        if (!ServerService.dataDirAman(dataDir)
                || !ServerService.dataDirKanonisAman(dataDir)) {
            toast("Invalid data folder — reverted to default.");
            appendUiLog("[app] Folder data tidak valid: '" + dataDir + "' - direset ke bawaan.");
            try {
                sp.edit().putString(ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar()).apply();
            } catch (Exception ignored) {
            }
            dataDir = ServerService.dataDirBawaanSegar();
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
        // effectivePort() sudah menormalisasi ke 1024-65535 (selaras
        // ServerService.normalisasiPort); penolakan di sini hanya jaring
        // pengaman dengan pesan yang sama agar tak menyesatkan.
        if (portNum < 1024 || portNum > 65535) {
            toast("Port must be a number 1024-65535 (<1024 needs root). Change it in Settings.");
            appendUiLog("[app] Port tidak valid: '" + port + "' - Start dibatalkan.");
            return;
        }
        // Cek bind di worker thread: bind ServerSocket di UI thread rawan ANR/
        // StrictMode, dan hasilnya tetap TOCTOU (service cek ulang sebelum start).
        final int portFix = portNum;
        setBusy(true);
        Util.jalankanBg(() -> {
            final boolean busy = ServerService.isPortBusy(portFix);
            final boolean butuhRoot = busy && ServerService.portButuhRoot(portFix);
            ui.post(() -> {
                setBusy(false);
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (butuhRoot) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Port " + portFix + " needs root access")
                            .setMessage("Main port (" + portFix + ") below 1024 can only be"
                                    + " used with root access.\n"
                                    + "Change the port to >= 1024 (e.g. 8088) in Settings.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                if (busy) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Port " + portFix + " is in use")
                            .setMessage("Main port (" + portFix + ") is already used by another process.\n"
                                    + "Stop the other app using it, change the port in Settings, "
                                    + "or restart the phone first.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                lanjutStart(finalDataDir);
            });
        });
    }

    /** Lanjutan Start setelah cek port selesai (berjalan di UI thread). */
    private void lanjutStart(String dataDir) {
        final String finalDataDir = dataDir;
        if (!webVaultReady(finalDataDir)) {
            // APK tidak membundel web-vault; unduh sekali saat Start pertama bila diizinkan.
            new AlertDialog.Builder(this)
                    .setTitle("Web vault not installed")
                    .setMessage("The APK excludes the web vault to stay small.\n"
                            + "Download once (~35 MB) so the web UI can be opened from a browser?\n\n"
                            + "Without the web vault, the server and Bitwarden apps still work normally.")
                    .setPositiveButton("Download & Start", (d, w) -> {
                        setBusy(true);
                        // Pakai app context di worker agar tekan back saat unduh
                        // 35 MB tak menahan Activity yang sudah destroy (bocor).
                        final android.content.Context appCtx = getApplicationContext();
                        Util.jalankanLama(() -> {
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
                            // Gagal unduh tak boleh auto-start tanpa web UI:
                            // pilihan tombolnya "Download & Start" satu paket.
                            // Start dulu baru update UI agar indikator sibuk
                            // tak mati sebelum server mulai jalan.
                            if (!gagal) {
                                ServerService.start(appCtx);
                            }
                            ui.post(() -> {
                                if (isFinishing() || isDestroyed()) {
                                    return;
                                }
                                appendUiLog(info);
                                if (gagalFinal) {
                                    toast("Web-vault download failed"
                                            + " — server was not started.");
                                }
                                setBusy(false);
                                // Backup otomatis di sini (bukan saat tekan tombol):
                                // DB baru ada sesudah server jalan; bila di depan,
                                // tunggu-DB 30 dtk hangus selama unduh 35 MB dan
                                // backup dilewati diam-diam.
                                if (!gagalFinal) {
                                    maybeAutoBackup();
                                }
                            });
                        });
                    })
                    .setNegativeButton("Start without web vault", (d, w) -> {
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
                ui.postDelayed(this::refreshFromService, 500);
            }
            return;
        }

        // Chip status: Berjalan / Berhenti + tombol Start/Stop tunggal
        boolean running = ServerService.running;
        // Tanpa getString + concat tiap 1-2 dtk (sampah di ART lama):
        // kunci boolean cukup karena cabang sibuk memakai namespace
        // "sibuk|..." yang tak bertabrakan dengan "on"/"off".
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
            String wv = Updater.webVaultFromVersion(this);
            if (wv == null) {
                wv = "";
            }
            String rwv = ServerService.runningWvFrom == null ? "" : ServerService.runningWvFrom;
            changed = !d.trim().equals(rd.trim())
                    || !p.trim().equals(rp.trim())
                    || !ServerService.sidikTokenAdmin(a).equals(ra.trim())
                    || !wv.equals(rwv);
        }
        restartHint.setVisibility(changed ? View.VISIBLE : View.GONE);

        // Peringatan bila update binary tersedia tapi belum dipasang
        boolean updAvail = false;
        String pend = pendingVersion;
        if (pend != null) {
            SharedPreferences psp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            String real = Updater.parseBinaryVersion(ServerService.binaryVersion);
            // Memo pasangan (real, pending): normVersion+regex tiap detik boros
            // di STB; hasil hanya berubah bila salah satunya berubah.
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
            // Fallthrough aman: baca di bawah pakai amanString (lihat cur/up).
            // Banding semantik (bukan equals mentah): "v"-prefix/sufiks beta
            // tak boleh membuat banner update macet padahal versi sama.
            if (cocok1) {
                psp.edit().putString(ServerService.KEY_UPDATE_VERSION, pend).apply();
                pendingVersion = null; // update sudah terpasang
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

        // Cache 10 detik agar tak enumerasi IP tiap tick UI (cegah ANR).
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

        // Gerbang detik: durationText+getString hanya saat detik berubah
        // (dulu dibangun tiap tick walau teks sama). Mati/stop langsung kosong.
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

        // Gerbang komponen sebelum StringBuilder: tanpa alokasi saat tak berubah.
        String binVer = ServerService.binaryVersion;
        if (binVer == null) {
            binVer = "";
        }
        if (!appVersion.equals(lastVersiApp) || !binVer.equals(lastVersiBin)
                || !bundledVersion.equals(lastVersiBundled)) {
            lastVersiApp = appVersion;
            lastVersiBin = binVer;
            lastVersiBundled = bundledVersion;
            StringBuilder versiB = new StringBuilder("App ").append(appVersion);
            if (!binVer.isEmpty()) {
                versiB.append(" \u00B7 Binary: ").append(binVer);
            } else {
                versiB.append(" \u00B7 ").append(bundledVersion);
            }
            String version = versiB.toString();
            if (!version.equals(lastShownVersion)) {
                versionView.setText(version);
                lastShownVersion = version;
            }
        }

        refreshHomeLog();

        if (refreshActive) {
            // Idle 2 dtk saat server mati: hemat bangun CPU di STB lama.
            ui.postDelayed(this::refreshFromService, ServerService.running ? 1000 : 2000);
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
        // Batas kerja per tick di UI thread: 13 pass regex di atas delta
        // puluhan KB tiap 500 ms bikin patah di STB lama. Potong di batas
        // baris utuh; sisa mengalir di tick berikut (urutan + baris utuh
        // terjaga sehingga rahasia tak lolos tersamar setengah).
        if (mentah > BATAS_DELTA_TICK) {
            int potong = delta.lastIndexOf('\n', BATAS_DELTA_TICK);
            if (potong <= 0) {
                potong = BATAS_DELTA_TICK;
                // Jangan belah pasangan surrogate di titik potong paksa.
                if (potong < delta.length()
                        && Character.isHighSurrogate(delta.charAt(potong - 1))
                        && Character.isLowSurrogate(delta.charAt(potong))) {
                    potong--;
                }
            } else {
                potong = potong + 1;
            }
            delta = delta.substring(0, potong);
            mentah = potong;
        }
        // Samarkan seperti jalur LogActivity/bagi/Telegram: token bot dan
        // rahasia di teks exception tak boleh tampil mentah di layar.
        // Offset tetap maju pakai panjang mentah (samaran mengubah panjang).
        if (!delta.isEmpty()) {
            lastLogLen += mentah;
            if (LogActivity.butuhSamaran(delta)) {
                delta = LogActivity.samarkanLog(delta);
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
        // Batas 150 baris tampil: hitungBaris memindai seluruh teks tiap
        // tick sehingga hanya jalan bila panjang mengisyaratkan over batas
        // (150 baris pendek ~15 KB; di bawah 25 KB pasti aman, hemat CPU STB).
        if (homeLogView.length() > 25000) {
            int total = hitungBaris(homeLogView.getText());
            if (total > MAKS_BARIS_LOG) {
                homeLogView.setText(buangBarisDepan(homeLogView.getText(), total - MAKS_BARIS_LOG));
            }
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

    /** Warna log di-cache: getColor tiap tick boros lookup resource di STB. */
    private int warnaGalatCache = 0;
    private int warnaAwasCache = 0;
    private boolean warnaCacheSiap = false;
    /** Batas olah log per tick layar awal (redaksi regex + span): sisa
     *  ditunda ke tick berikut agar UI thread tak tersedak log deras. */
    private static final int BATAS_DELTA_TICK = 8192;

    // getColor(int) lawas sengaja agar satu jalur kode untuk API 21-32.
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
                // Sisa tanpa newline di ujung (baris parsial) ditempel apa adanya
                // agar chunk berikut menyambung; bukan baris kosong baru.
                // Tanpa substring per baris: uji + tempel langsung dari rentang.
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

    /** Kata kunci pewarna log (huruf kecil semua, untuk cocok tanpa peka huruf). */
    private static final String[] KATA_GALAT =
            {"error", "exception", "panic", "gagal", "fatal", "traceback"};
    private static final String[] KATA_AWAS =
            {"warn", "peringatan", "deprecated", "awas"};

    /** True bila salah satu kata muncul tanpa peka huruf; tanpa salinan
     *  lowercase dan tanpa array varargs per baris (hemat di tick 500 ms). */
    static boolean muatKata(String baris, String[] daftar) {
        if (baris == null || daftar == null) {
            return false;
        }
        return muatKataRentang(baris, 0, baris.length(), daftar);
    }

    /** indeksOf tanpa peka huruf (ASCII): tanpa alokasi lowercase per baris,
     *  tanpa tabel Unicode Character.toLowerCase per char (kata kunci ASCII). */
    static int indeksTakPeka(String s, String kata) {
        return indeksTakPekaRentang(s, 0, s.length(), kata);
    }

    /** Varian rentang [dari, sampai): uji keyword langsung di buffer delta
     *  tanpa substring per baris (hemat puluhan alokasi per tick). Murni. */
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

    /** True bila salah satu kata muncul di rentang tanpa peka huruf. Murni. */
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

    /** Salin URL yang tampil di kartu info (pengganti tombol Salin URL).
     *  Bersih otomatis tahan mati proses via satu pintu LogActivity. */
    private void copyShownUrl() {
        String url = ServerService.localUrl(this);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && LogActivity.salinBersihOtomatis(this, "vaultwarden-url", url)) {
            if (url.contains("127.0.0.1") || url.contains("localhost")) {
                toast(getString(R.string.url_copied, url) + " (loopback — IP LAN tak terdeteksi)");
            } else {
                toast(getString(R.string.url_copied, url));
            }
        } else {
            toast(url);
        }
    }

    /** Tentang khas Tasirin Download Manager: ikon, judul, versi,
     *  baris ikon-teks, tombol GitHub + Cek Update, tombol tutup, footer. */
    @SuppressLint("InflateParams")
    private void showAboutDialog() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
        if (TextUtils.isEmpty(dataDir)) {
            dataDir = ServerService.dataDirBawaanSegar();
        }
        String bin = currentServerVersion();
        String wv = readWvVersion(new File(dataDir, "web-vault/vw-version.json"));
        int build = 0;
        try {
            build = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception ignored) {
        }
        String versi = appVersion.isEmpty() ? "?" : appVersion;
        StringBuilder infoVersi = new StringBuilder(versi);
        if (bin != null) {
            infoVersi.append(" \u00b7 binary v").append(bin);
        }
        if (wv != null) {
            infoVersi.append(" \u00b7 vault v").append(wv);
        }
        android.view.LayoutInflater pompa = android.view.LayoutInflater.from(this);
        android.view.View lihat = pompa.inflate(R.layout.dialog_about, null);
        TextView versiView = (TextView) lihat.findViewById(R.id.about_version);
        versiView.setText(getString(R.string.about_version, infoVersi.toString()));
        LinearLayout baris = (LinearLayout) lihat.findViewById(R.id.about_rows);
        String[] ikon = getResources().getStringArray(R.array.about_icons);
        String[] teks = getResources().getStringArray(R.array.about_rows);
        float kepadatan = getResources().getDisplayMetrics().density;
        int jarak = (int) (10 * kepadatan + 0.5f);
        int atas = (int) (6 * kepadatan + 0.5f);
        for (int i = 0; i < ikon.length && i < teks.length; i++) {
            LinearLayout sel = new LinearLayout(this);
            sel.setOrientation(LinearLayout.HORIZONTAL);
            sel.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.topMargin = atas;
            }
            TextView ikonView = new TextView(this);
            ikonView.setText(ikon[i]);
            ikonView.setTextSize(16);
            ikonView.setPadding(0, 0, jarak, 0);
            TextView teksView = new TextView(this);
            teksView.setText(teks[i]);
            teksView.setTextSize(13.5f);
            teksView.setTextColor(getResources().getColor(R.color.text_secondary));
            sel.addView(ikonView);
            sel.addView(teksView, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            baris.addView(sel, lp);
        }
        int targetSdk = 28;
        try {
            targetSdk = getPackageManager()
                    .getApplicationInfo(getPackageName(), 0).targetSdkVersion;
        } catch (Exception ignored) {
        }
        TextView kaki = (TextView) lihat.findViewById(R.id.about_footer);
        kaki.setText(getString(R.string.about_tech, 21, targetSdk, build));
        lihat.findViewById(R.id.btn_about_github).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://github.com/tasirin1/tasirin-vaultwarden-host")));
            } catch (Exception ignored) {
            }
        });
        lihat.findViewById(R.id.btn_about_update).setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, SettingsActivity.class));
            } catch (Exception ignored) {
            }
        });
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(lihat);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(
                    android.R.color.transparent);
            int lebarLayar = getResources().getDisplayMetrics().widthPixels;
            int lebar = Math.min((int) (560 * kepadatan + 0.5f),
                    lebarLayar - (int) (48 * kepadatan + 0.5f));
            if (lebar > 0) {
                dialog.getWindow().setLayout(lebar,
                        WindowManager.LayoutParams.WRAP_CONTENT);
            }
        }
        dialog.show();
        lihat.findViewById(R.id.btn_about_ok).setOnClickListener(v -> dialog.dismiss());
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
                // Toast sekali per versi: tiap buka app menawar ulang itu spam.
                SharedPreferences psp = getSharedPreferences(ServerService.PREFS,
                        MODE_PRIVATE);
                if (AutoUpdate.tawarkanBaru(
                        TgBackup.amanString(psp, AutoUpdate.KEY_TAWARAN_UPDATE, ""),
                        versi)) {
                    psp.edit().putString(AutoUpdate.KEY_TAWARAN_UPDATE, versi).apply();
                    MainActivity.this.toast("Update available: v" + versi
                            + " — open Settings to update.");
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
            // Dipanggil dari worker thread AutoUpdate: post ke UI agar tulis
            // terkurung di UI thread (baca banner/status juga di UI).
            @Override public void atur(String versi) {
                try {
                    ui.post(() -> pendingVersion = versi);
                } catch (Exception e) {
                    pendingVersion = versi;
                }
            }
        }, false);
    }

    private String readBundledVersion() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getAssets().open("vw_version.txt"), StandardCharsets.UTF_8))) {
            String v = r.readLine();
            return (v == null || v.trim().isEmpty()) ? "?" : "Version: " + v.trim();
        } catch (Exception e) {
            return "Version: ?";
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
            // PIN mati: buang status buka basi agar PIN yang diaktifkan lagi
            // dalam grace lama tak dianggap sudah dibuka tanpa entri baru.
            PinGate.kunciBersama();
            return;
        }
        // Sudah dibuka di layar lain dalam grace: jangan minta lagi (tanpa perpanjangan).
        if (PinGate.dalamGraceBersama()) {
            return;
        }
        // Satu-satunya sumber grace adalah PinGate (jangkar bersama antar-activity);
        // cermin lokal sengaja tak ada agar tak ada baca basi yang fail-open.
        // Bila sampai sini, grace sudah habis.
        final String pinHash = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
        if (pinHash == null || pinHash.isEmpty()) {
            try {
                sp.edit().putBoolean(PinGate.KEY_PIN_ON, false).apply();
            } catch (Exception ignored) {
            }
            ServerService.catatLog("[app] PIN dimatikan otomatis: hash hilang/rusak.");
            return;
        }
        if (pinBukaJalan) {
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
        // Jam elapsed (bukan dinding): jam yang dimundurkan membuat selisih
        // negatif sehingga refresh macet sampai jam menyusul. Ledakan log
        // tidak boleh membanjiri UI thread.
        long now = SystemClock.elapsedRealtime();
        if (now - lastUiLogRefresh > 500) {
            lastUiLogRefresh = now;
            ui.post(this::refreshFromService);
        }
    }

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}
