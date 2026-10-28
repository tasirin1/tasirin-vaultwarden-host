package com.tasirin.vaultwardenhost;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

public class ServerService extends Service {

    public static final String ACTION_START = "com.tasirin.vaultwardenhost.START";
    public static final String ACTION_STOP = "com.tasirin.vaultwardenhost.STOP";
    public static final String ACTION_RESTART = "com.tasirin.vaultwardenhost.RESTART";
    public static final String ACTION_TG_BACKUP = "com.tasirin.vaultwardenhost.TG_BACKUP";

    public static final String PREFS = "vw_prefs";

    @Deprecated
    public static final String DEFAULT_DATA_DIR = defaultDataDir();

    public static String dataDirBawaanSegar() {
        return defaultDataDir();
    }

    private static final String DATA_DIR_CADANGAN = "/sdcard/vaultwarden";

    private static volatile String cacheDirBawaan = null;

    @SuppressWarnings("deprecation")
    private static String defaultDataDir() {
        String c = cacheDirBawaan;
        if (c != null) {
            return c;
        }
        String hasil = null;
        try {
            java.io.File ext = android.os.Environment.getExternalStorageDirectory();
            if (ext != null) {
                hasil = new java.io.File(ext, "vaultwarden").getAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        if (hasil == null || hasil.trim().isEmpty()) {

            return DATA_DIR_CADANGAN;
        }
        cacheDirBawaan = hasil;
        return hasil;
    }
    public static final String DEFAULT_PORT = "8088";

    public static final String ABI = "armeabi-v7a";
    public static final String KEY_DATA_DIR = "data_dir";
    public static final String KEY_PORT = "port";
    public static final String KEY_AUTO_START = "auto_start";

    static final String KEY_SERVER_DIMINTA = "server_diminta";
    public static final String KEY_UPDATE_VERSION = "update_version";

    public static final String KEY_HTTPS = "https";
    public static final String KEY_ADMIN_TOKEN = "admin_token";
    public static final String KEY_AUTO_UPDATE = "auto_update_binary";
    public static final String KEY_AUTO_UPDATE_WV = "auto_update_webvault";
    public static final String KEY_AUTO_RESTART_UPDATE = "auto_restart_update";

    public static final String KEY_BIN_SHA = "bin_sha";

    public static final String KEY_BIN_PATCH = "bin_patch_rev";

    public static final String KEY_BIN_MANUAL_FP = "bin_manual_fp";

    public static final int BIN_PATCH_REV = 3;

    public static final String KEY_BIN_PILIH = "bin_pin_version";

    public static final String KEY_WV_PILIH = "wv_pin_version";

    // Stempel dua kunci ini adalah jam dinding (currentTimeMillis), BUKAN
    // elapsedRealtime: jeda 6 jam harus bertahan lintas reboot (Audit 478).
    public static final String KEY_BIN_DL_GAGAL_AT = "bin_dl_gagal_at";
    public static final String KEY_WV_DL_GAGAL_AT = "wv_dl_gagal_at";

    static final long TUNDA_ULANG_UNDUH_MS = 6L * 60 * 60 * 1000;

    private static volatile long loginHintTerakhirElapsed = 0;
    static final long LOGIN_HINT_JEDA_MS = 60 * 1000;

    private static volatile long loginPindaiTerakhirElapsed = 0;
    static final long LOGIN_PINDAI_JEDA_MS = 10 * 1000;

    static boolean tokenAdminValid(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c <= ' ' || c == 127) {
                return false;
            }
        }
        return true;
    }

    static boolean perluRefreshPatch(String tersimpan) {
        return tersimpan == null || !tersimpan.equals(String.valueOf(BIN_PATCH_REV));
    }

    static String sidikManualFile(File f) {
        if (f == null || !f.isFile()) {
            return "";
        }
        return f.length() + ":" + f.lastModified();
    }

    static boolean manualPerluValidasi(String shaDiatur, String sidikTersimpan, File userBin) {
        if (shaDiatur == null || shaDiatur.trim().isEmpty()) {
            return false;
        }
        String lama = sidikTersimpan == null ? "" : sidikTersimpan;
        return !sidikManualFile(userBin).equals(lama);
    }

    static boolean bolehCobaUnduhLagi(long gagalAt, long sekarang) {
        if (gagalAt <= 0) {
            return true;
        }
        if (gagalAt > sekarang) {

            return true;
        }
        return sekarang - gagalAt >= TUNDA_ULANG_UNDUH_MS;
    }

    private static final Object KUNCI_SIDIK = new Object();
    private static long sidikTokenKunci = 0;
    private static int sidikTokenPanjang = -1;
    private static String sidikTokenHasil = "";

    static long sidikCepat(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    static String sidikTokenAdmin(String token) {
        String bersih = token == null ? "" : token.trim();
        if (bersih.isEmpty()) {
            synchronized (KUNCI_SIDIK) {
                sidikTokenKunci = 0;
                sidikTokenPanjang = -1;
                sidikTokenHasil = "";
            }
            return "";
        }
        long kunci = sidikCepat(bersih);
        synchronized (KUNCI_SIDIK) {
            if (sidikTokenPanjang == bersih.length() && sidikTokenKunci == kunci) {
                return sidikTokenHasil;
            }
        }
        String hasil;
        try {
            byte[] h = Util.mdSha256().digest(bersih.getBytes(StandardCharsets.UTF_8));
            hasil = Updater.toHex(h);
        } catch (Exception e) {
            return "";
        }
        synchronized (KUNCI_SIDIK) {
            sidikTokenKunci = kunci;
            sidikTokenPanjang = bersih.length();
            sidikTokenHasil = hasil;
        }
        return hasil;
    }

    static void bersihkanCacheSidikToken() {
        synchronized (KUNCI_SIDIK) {
            sidikTokenKunci = 0;
            sidikTokenPanjang = -1;
            sidikTokenHasil = "";
        }
    }

    static String potongPesanGalat(String msg, int maks) {
        if (maks <= 0) {
            return "";
        }
        if (msg == null || msg.length() <= maks) {
            return msg == null ? "" : msg;
        }
        String potong = msg.substring(0, maks);
        if (Character.isHighSurrogate(potong.charAt(potong.length() - 1))) {
            potong = potong.substring(0, potong.length() - 1);
        }
        return potong;
    }

    static String pinBinaryTersimpan(SharedPreferences sp) {
        try {
            String pin = Updater.normalisasiPinVersi(
                    sp == null ? null : TgBackup.amanString(sp, KEY_BIN_PILIH, ""));
            return pin == null ? "" : pin;
        } catch (Exception e) {
            return "";
        }
    }

    private static final java.util.regex.Pattern POLA_SUFIKS_VERSI =
            java.util.regex.Pattern.compile("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?");

    static boolean cacheSesuaiPin(String pin, String terdeteksi) {
        if (pin == null || pin.isEmpty()) {
            return true;
        }
        String real = Updater.parseBinaryVersion(terdeteksi);
        if (real == null || Updater.bandingVersi(real, pin) != 0) {
            return false;
        }
        return sufiksPrerelease(terdeteksi).equalsIgnoreCase(sufiksPrerelease(pin));
    }

    static String sufiksPrerelease(String v) {
        if (v == null) {
            return "";
        }
        java.util.regex.Matcher m = POLA_SUFIKS_VERSI.matcher(v.trim());
        if (!m.find() || m.group(1) == null) {
            return "";
        }
        return m.group(1);
    }

    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "vaultwarden_server";
    private static final int MAX_LOG_CHARS = 300_000;

    private static final int LOG_TRIM_THRESHOLD = MAX_LOG_CHARS + 32 * 1024;
    private static final long MAX_LOG_FILE = 2L * 1024 * 1024;
    private static final long HEALTH_INTERVAL_MS = 2 * 60 * 1000;
    private static final long HEALTH_FAST_MS = 5 * 60 * 1000;
    private static final long HEALTH_FAST_INTERVAL_MS = 30 * 1000;

    private static final ThreadLocal<SimpleDateFormat> LOG_TS =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
                }
            };

    public static volatile boolean running = false;
    public static volatile String statusLine = "Stopped";

    private volatile boolean notifForeground = false;
    public static volatile String binaryVersion = "";
    public static final StringBuilder logBuffer = new StringBuilder();

    private static volatile long logVer = 0;

    public static volatile String runningDataDir = "";
    public static volatile String runningPort = "";

    public static volatile String runningAdminToken = "";

    public static volatile String runningWvFrom = "";

    public static volatile String runningLanHost = "";

    private static volatile String ipBerubahDiperingatkan = "";

    private static volatile String ipBaruKandidat = "";
    private static volatile int ipBaruHitung = 0;
    private static volatile String ipRestartDiminta = "";
    private static volatile long ipBaruSejak = 0;
    private static final long IP_STABIL_MS = 60_000;

    private static final long[] RESTART_DELAYS = {2000, 5000, 10000, 20000, 40000};

    private static final long RESTART_WINDOW_MS = 5 * 60 * 1000L;
    private static final int RESTART_WINDOW_MAX = 3;
    private static final int RESTART_HISTORY_MAX = 10;
    private static final String CRASH_LOG_NAME = "crash-last.log";
    private static final List<Long> RESTART_TIMES = new ArrayList<>();
    private static final List<String> RESTART_REASONS = new ArrayList<>();

    private static void posUtama(Runnable r, long tundaMs) {
        Util.postTunda(r, tundaMs);
    }
    private static void batalUtama(Runnable r) {
        Util.batalkanTunda(r);
    }

    private static void resetPenandaJalan() {
        bersihkanCacheSidikToken();
        runningDataDir = "";
        runningPort = "";
        runningAdminToken = "";
        runningWvFrom = "";
        runningLanHost = "";
        ipBerubahDiperingatkan = "";
        ipBaruKandidat = "";
        ipBaruHitung = 0;
        ipRestartDiminta = "";
        ipBaruSejak = 0;
    }

    static boolean lepasProses(Process p, Runnable efek) {
        if (p == null) {
            return false;
        }
        synchronized (KUNCI_PROSET) {
            if (process != p) {
                return false;
            }
            process = null;
            running = false;
            resetPenandaJalan();
        }
        if (efek != null) {
            efek.run();
        }
        return true;
    }

    private void efekProsesBerhenti() {
        flushLogFile();
        setStatusBilaBerhenti("Stopped");
        releaseWakeLockBilaBerhenti();
    }

    private void setStatusBilaBerhenti(String text) {
        synchronized (KUNCI_PROSET) {
            if (running || process != null || starting.get()) {
                return;
            }
            statusLine = text;
        }
        perbaruiNotifikasi();
    }

    private void releaseWakeLockBilaBerhenti() {
        if (running || process != null) {
            return;
        }
        synchronized (KUNCI_WAKELOCK) {
            if (running || process != null) {
                return;
            }
            releaseWakeLockDalam();
        }
    }

    private static volatile Process process;

    private static volatile Thread pembacaOutput = null;

    private static volatile Process prosesStopDisengaja = null;

    private static final java.util.concurrent.atomic.AtomicInteger healthTcpLolos =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private static volatile boolean batalStart = false;

    private static final java.util.concurrent.atomic.AtomicLong nomorStop =
            new java.util.concurrent.atomic.AtomicLong();

    private static volatile boolean mulaiSetelahProsesMati = false;
    private static final java.util.concurrent.atomic.AtomicBoolean starting = new java.util.concurrent.atomic.AtomicBoolean(false);

    private static volatile boolean startSusulanDiminta = false;

    private static final Object KUNCI_PROSET = new Object();

    private volatile int startIdTerakhir = 0;

    private volatile int idEpisodaProses = -1;

    private static final java.util.concurrent.locks.ReentrantLock KUNCI_DATA =
            new java.util.concurrent.locks.ReentrantLock();

    private static final java.util.concurrent.atomic.AtomicInteger startTertundaData =
            new java.util.concurrent.atomic.AtomicInteger(0);

    static final int BATAS_START_TERTUNDA_DATA = 60;

    static final long BATAS_TUNGGU_KUNCI_DATA_MS = 5L * 60 * 1000;

    public static void kunciData() {
        KUNCI_DATA.lock();
    }

    public static void lepasKunciData() {
        if (KUNCI_DATA.isHeldByCurrentThread()) {
            KUNCI_DATA.unlock();
        }
    }

    private static PowerManager.WakeLock wakeLock;

    private static final Object KUNCI_WAKELOCK = new Object();
    private static volatile File logFile;

    private static volatile boolean autoRestart = false;

    private static final java.util.concurrent.atomic.AtomicInteger restartAttempt =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private static final Object KUNCI_RESTART = new Object();

    private static volatile long lastStartElapsed = 0;

    private static final java.util.concurrent.atomic.AtomicInteger healthFails = new java.util.concurrent.atomic.AtomicInteger(0);

    private static final java.util.concurrent.atomic.AtomicInteger configSajaBeruntun =
            new java.util.concurrent.atomic.AtomicInteger(0);

    static final int BATAS_CONFIG_SAJA_BERUNTUN = 10;

    private static volatile boolean healthActive = false;

    private static final java.util.concurrent.atomic.AtomicBoolean healthBerjalan =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Runnable healthTick = new Runnable() {
        @Override
        public void run() {

            if (!healthActive || !autoRestart) {
                return;
            }
            jagaWakeLock();

            long delay = HEALTH_INTERVAL_MS;
            long up = SystemClock.elapsedRealtime() - lastStartElapsed;
            if (lastStartElapsed > 0 && up < HEALTH_FAST_MS) {
                delay = HEALTH_FAST_INTERVAL_MS;
            }
            if (process == null || !alive(process) || !running) {

                posUtama(this, delay);
                return;
            }
            if (!healthBerjalan.compareAndSet(false, true)) {
                posUtama(this, delay);
                return;
            }
            posUtama(this, delay);

            final Process pCek = process;
            final int epCek = idEpisodaProsesAktif();

            boolean masuk = Util.jalankanBg(() -> {
                try {
                    checkHealthOnce(pCek, epCek);
                } finally {
                    healthBerjalan.set(false);
                }
            });
            if (!masuk) {

                healthBerjalan.set(false);
            }
        }
    };

    public static boolean start(Context context) {
        return mulaiService(context, ACTION_START);
    }

    @SuppressLint("ApplySharedPref")
    private static boolean mulaiService(Context context, String aksi) {
        try {
            Intent i = new Intent(context, ServerService.class).setAction(aksi);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
            return true;
        } catch (Exception e) {
            catatLog("[app] Gagal start service (" + aksi + "): " + e);
            if (ACTION_START.equals(aksi)) {

                try {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean(KEY_START_TERTUNDA, true).commit();
                } catch (Exception ignored) {
                }
            }
            return false;
        }
    }

    public static boolean stop(Context context) {
        return mulaiService(context, ACTION_STOP);
    }

    public static boolean restart(Context context) {
        return mulaiService(context, ACTION_RESTART);
    }

    public static final String KEY_START_TERTUNDA = "auto_start_tertunda";

    public static boolean backupNow(Context context) {
        return mulaiService(context, ACTION_TG_BACKUP);
    }

    public static boolean isProcessAlive() {
        Process p = process;
        return alive(p);
    }

    private static boolean isStopDisengaja(Process p) {
        return p != null && p == prosesStopDisengaja;
    }

    private static void tandaiStopDisengaja(Process p) {
        if (p != null) {
            prosesStopDisengaja = p;
        }
    }

    private static void hapusTandaStop(Process p) {
        if (p != null && prosesStopDisengaja == p) {
            prosesStopDisengaja = null;
        }
    }

    static boolean pulihkanBakBiner(File out, File bak) {
        if (out == null || bak == null || !bak.isFile()
                || bak.length() < 1_000_000 || !Updater.isElf(bak)) {
            return false;
        }
        if (out.isFile() && out.length() >= 1_000_000 && Updater.isElf(out)) {
            return false;
        }
        return bak.renameTo(out);
    }

    static void gantiAtomik(File tmp, File out) throws java.io.IOException {
        if (tmp == null || out == null || out.getParentFile() == null) {
            throw new java.io.IOException("Path binary tidak valid.");
        }
        File bak = new File(out.getParentFile(), out.getName() + ".bak");
        try {
            bak.delete();
        } catch (Exception ignored) {
        }
        if (out.exists() && !out.renameTo(bak)) {
            throw new java.io.IOException("Gagal mencadangkan binary lama.");
        }
        boolean ok = false;
        try {

            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(tmp);
                try {
                    fis.getFD().sync();
                } finally {
                    try {
                        fis.close();
                    } catch (Exception ignored2) {
                    }
                }
            } catch (Exception ignored) {
            }
            if (!tmp.renameTo(out)) {
                throw new java.io.IOException("Gagal memasang binary.");
            }
            ok = true;
        } finally {
            if (!ok && bak.exists()) {
                try {
                    if (!out.exists()) {
                        bak.renameTo(out);
                    }
                } catch (Exception ignored) {
                }
            } else if (ok) {
                try {
                    bak.delete();
                } catch (Exception ignored) {
                }

                try {
                    java.io.File dir = out.getParentFile();
                    if (dir != null) {
                        java.io.FileDescriptor fd = android.system.Os.open(
                                dir.getAbsolutePath(),
                                android.system.OsConstants.O_RDONLY, 0);
                        try {
                            android.system.Os.fsync(fd);
                        } finally {
                            try {
                                android.system.Os.close(fd);
                            } catch (Exception ignored2) {
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    static void pulihkanBakTersisa(File dataFolder) {
        if (dataFolder == null) {
            return;
        }
        pulihkanBakDiFolder(dataFolder,
                new String[]{"db.sqlite3", "db.sqlite3-wal", "db.sqlite3-shm"});
        pulihkanBakDiFolder(new File(dataFolder, "tls"),
                new String[]{"ca.pem", "cert.pem", "key.pem", "ca-key.pem"});
        pulihkanBakDiFolder(new File(dataFolder, "backups"),
                namaAsal -> TgBackup.isFileBackup(namaAsal));
    }

    static boolean bakNamaSah(String namaAsal, String[] namaSah) {
        if (namaAsal == null || namaSah == null) {
            return false;
        }
        for (String sah : namaSah) {
            if (sah.equals(namaAsal)) {
                return true;
            }
        }
        return false;
    }

    interface PenilaiNamaBak {
        boolean sah(String namaAsal);
    }

    private static void pulihkanBakDiFolder(File folder, String[] namaSah) {
        pulihkanBakDiFolder(folder, namaAsal -> bakNamaSah(namaAsal, namaSah));
    }

    private static void pulihkanBakDiFolder(File folder, PenilaiNamaBak penilai) {
        File[] isi;
        try {
            isi = folder.listFiles();
        } catch (Exception e) {
            return;
        }
        if (isi == null) {
            return;
        }
        for (File bak : isi) {
            try {
                String nama = bak.getName();
                if (!bak.isFile() || !nama.endsWith(".bak")) {
                    continue;
                }
                String namaAsal = nama.substring(0, nama.length() - 4);
                if (!penilai.sah(namaAsal)) {
                    continue;
                }
                File asal = new File(folder, namaAsal);
                if (asal.exists()) {
                    if (bak.delete()) {
                        catatLog("[app] Berkas .bak sisa swap dihapus: " + nama);
                    }
                } else if (bak.renameTo(asal)) {
                    catatLog("[app] Berkas .bak dipulihkan (swap terpotong): "
                            + asal.getName());
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean alive(Process p) {
        if (p == null) {
            return false;
        }
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    public static void clearLog() {
        synchronized (logBuffer) {
            logBuffer.setLength(0);
            logVer++;
        }

        synchronized (LOG_FILE_LOCK) {
            logFileBuf.setLength(0);
            File f = logFile;
            if (f != null) {
                f.delete();

                File lama = f.getParentFile() == null ? null
                        : new File(f.getParentFile(), f.getName() + ".1");
                if (lama != null) {
                    lama.delete();
                }
            }
        }
    }

    public static long uptimeMs() {
        if (!running) {
            return 0;
        }
        long t = lastStartElapsed;
        return t == 0 ? 0 : Math.max(0, SystemClock.elapsedRealtime() - t);
    }

    public static boolean pingAlive(Context ctx) {
        return pingRinci(ctx).sehat;
    }

    static final class HasilPing {
        final boolean sehat;
        final int aliveCode;
        final int configCode;
        final String rincian;
        HasilPing(boolean sehat, int aliveCode, int configCode, String rincian) {
            this.sehat = sehat;
            this.aliveCode = aliveCode;
            this.configCode = configCode;
            this.rincian = rincian;
        }
    }

    static boolean sehatDariKode(int aliveCode, int configCode) {
        return aliveCode == 200 || configCode == 200;
    }

    static boolean aliveRusakTapiConfigSehat(int aliveCode, int configCode) {
        return aliveCode >= 500 && aliveCode < 600 && configCode == 200;
    }

    static HasilPing pingRinci(Context ctx) {
        String port;
        try {
            if (!runningPort.isEmpty()) {
                port = runningPort;
            } else {
                SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                port = effectivePort(sp);
            }
        } catch (Exception e) {
            return new HasilPing(false, -1, -1, "baca prefs gagal");
        }

        String p = port == null ? "" : port;

        try {
            int pn = Integer.parseInt(p);
            if (!tcpTersambung(pn)) {
                return new HasilPing(false, -1, -1, "port tertutup");
            }
        } catch (Exception ignored) {
        }
        HasilCoba alive = cobaKode(ctx, p, "/alive");
        HasilCoba config;

        if (alive.kode != 200 && alive.kode != -1) {
            config = cobaKode(ctx, p, "/api/config");
        } else {
            config = new HasilCoba(-2, "");
        }
        boolean sehat = sehatDariKode(alive.kode, config.kode);
        String rincian;
        if (sehat) {
            rincian = alive.kode == 200 ? "alive 200" : "config 200 (alive " + ringkasKode(alive.kode, alive.galat) + ")";
        } else {
            rincian = "alive " + ringkasKode(alive.kode, alive.galat)
                    + ", config " + ringkasKode(config.kode, config.galat);
        }
        return new HasilPing(sehat, alive.kode, config.kode, rincian);
    }

    private static final javax.net.ssl.HostnameVerifier VERIF_LOOPBACK =
            (host, session) -> "127.0.0.1".equals(host)
                    || "localhost".equalsIgnoreCase(host);

    static final class HasilCoba {
        final int kode;
        final String galat;
        HasilCoba(int kode, String galat) {
            this.kode = kode;
            this.galat = galat == null ? "" : galat;
        }
    }

    private static HasilCoba cobaKode(Context ctx, String port, String path) {
        HttpsURLConnection c = null;
        try {
            c = (HttpsURLConnection) new URL(
                    "https://127.0.0.1:" + port + path).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setSSLSocketFactory(loopbackSslFactory(ctx));
            c.setHostnameVerifier(VERIF_LOOPBACK);
            return new HasilCoba(c.getResponseCode(), "");
        } catch (Exception e) {
            String m = e.getClass().getSimpleName();
            String msg = e.getMessage();
            msg = potongPesanGalat(msg == null ? "" : msg, 80);
            String galat = msg == null || msg.isEmpty() ? m : m + ": " + msg;
            return new HasilCoba(-1, galat);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    static String ringkasKode(int code, String err) {
        if (code > 0) {
            return String.valueOf(code);
        }
        if (code == -2) {
            return "dilewati";
        }
        return err == null || err.isEmpty() ? "tak tersambung" : "tak tersambung (" + err + ")";
    }

    static boolean tcpTersambung(int portNum) {
        if (portNum < 1 || portNum > 65535) {
            return false;
        }
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new InetSocketAddress("127.0.0.1", portNum), 3000);
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    int portLoopback() {
        try {
            String p = !runningPort.isEmpty() ? runningPort
                    : effectivePort(getSharedPreferences(PREFS, MODE_PRIVATE));

            if (!portTampakAngka(p)) {
                return -1;
            }
            return Integer.parseInt(p);
        } catch (Exception e) {
            return -1;
        }
    }

    public static final long ANGGARAN_STOP_MIN_MS = 15000;

    public static boolean stopAndWait(Context context, long timeoutMs) {

        try {
            android.os.Looper ui = android.os.Looper.getMainLooper();
            if (ui != null && android.os.Looper.myLooper() == ui) {
                catatLog("[server] stopAndWait di UI thread, batal");
                return false;
            }
        } catch (Exception ignored) {
        }

        if (!isProcessAlive() && !starting.get()) {
            return true;
        }
        stop(context);

        long anggaran = timeoutMs < ANGGARAN_STOP_MIN_MS
                ? ANGGARAN_STOP_MIN_MS : timeoutMs;
        long deadline = SystemClock.elapsedRealtime() + anggaran;
        while (SystemClock.elapsedRealtime() < deadline
                && (isProcessAlive() || starting.get())) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isProcessAlive() && !starting.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            TgBackup.healkanStringPrefs(this);
            migrasiPortSekali(getSharedPreferences(PREFS, MODE_PRIVATE));
        } catch (Exception ignored) {
        }
        createChannel();

        try {
            final android.content.Context appPin = getApplicationContext();
            Util.jalankanBg(() -> PinGate.kuatkanHashDini(appPin));
        } catch (Exception ignored) {
        }

        try {
            if (running && autoRestart && healthActive) {

                try {
                    startForegroundCompat();
                } catch (Exception ignoredFg) {
                }
                if (isProcessAlive()) {
                    jagaWakeLock();
                    batalUtama(healthTick);
                    posUtama(healthTick, HEALTH_FAST_INTERVAL_MS);
                } else if (running) {
                    scheduleRestart();
                    batalUtama(healthTick);
                    posUtama(healthTick, HEALTH_FAST_INTERVAL_MS);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startIdTerakhir = startId;
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {

            try {
                startForegroundCompat();
            } catch (Exception ignoredFg) {
            }
            autoRestart = false;
            healthActive = false;
            simpanNiatJalan(false);
            batalUtama(healthTick);
            batalUtama(restartTunda);
            batalStart = true;
            startTertundaData.set(0);

            nomorStop.incrementAndGet();

            mulaiSetelahProsesMati = false;

            startSusulanDiminta = false;
            stopServer();

            final int stopId = startId;

            boolean masuk = Util.jalankanLama(() -> {
                try {
                    long tenggat = SystemClock.elapsedRealtime() + 9000;
                    while (SystemClock.elapsedRealtime() < tenggat && isProcessAlive()) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Exception ignored) {
                } finally {

                    if (!niatJalanTersimpan()) {
                        turunkanForeground(true);
                        try {
                            stopSelf(stopId);
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
            if (!masuk) {

                Util.postTunda(() -> {
                    if (niatJalanTersimpan()) {
                        return;
                    }
                    turunkanForeground(true);
                    try {
                        stopSelf(stopId);
                    } catch (Exception ignored) {
                    }
                }, 3000);
            }
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            autoRestart = true;
            startForegroundCompat();

            boolean masuk = Util.jalankanLama(() -> {
                appendLog("[app] Restart server diminta.");

                if (restartDilewati(starting.get(), running, alive(process))) {
                    ipRestartDiminta = "";
                    if (starting.get()) {
                        appendLog("[app] Restart dilewati: persiapan start"
                                + " sedang berjalan; penanda IP dibersihkan"
                                + " agar pemantau kesehatan meminta lagi.");
                    } else {
                        appendLog("[app] Restart dilewati: server tidak berjalan.");
                        turunkanForegroundBilaTakDiminta(startId);
                    }
                    return;
                }

                final Process p;
                synchronized (KUNCI_PROSET) {
                    p = process;
                    if (p != null) {
                        tandaiStopDisengaja(p);
                        running = false;
                    }
                }
                if (p != null) {
                    releaseWakeLock();
                    try {
                        matikanProsesTegas(p, 5000);
                    } finally {
                        lepasProses(p, null);
                        hapusTandaStop(p);
                    }
                }

                final android.content.Context appCtx = getApplicationContext();
                try {
                    android.os.Looper ui = android.os.Looper.getMainLooper();
                    if (ui == null) {
                        if (autoRestart) {
                            ServerService.start(appCtx);
                        }
                    } else {

                        Util.postUtama(() -> {
                            if (autoRestart) {
                                ServerService.start(appCtx);
                            }
                        });
                    }
                } catch (Exception ignored) {
                    try {
                        if (autoRestart) {
                            ServerService.start(appCtx);
                        }
                    } catch (Exception ignored2) {
                    }
                }
            });
            if (!masuk) {
                ipRestartDiminta = "";
                appendLog("[app] Restart gagal: antrean tugas penuh - coba lagi sebentar.");
                TgBackup.sendMessage(this,
                        "Restart gagal: tugas lain sedang berjalan. Coba lagi sebentar.");
                if (!starting.get()) {
                    turunkanForegroundBilaTakDiminta(startId);
                }
            }
            return START_NOT_STICKY;
        }
        if (ACTION_TG_BACKUP.equals(action)) {
            startForegroundCompat();

            boolean masuk = Util.jalankanLama(() -> {
                try {
                    SharedPreferences cek = getSharedPreferences(PREFS, MODE_PRIVATE);
                    if (!TgBackup.amanBoolean(cek, TgBackup.KEY_TG_AUTO, false)) {

                        appendLog("[tg] Backup terjadwal dilewati: auto-backup sudah dimatikan.");
                        return;
                    }
                    long last = TgBackup.amanLong(cek, TgBackup.KEY_TG_LAST, 0);
                    if (last > 0 && !TgBackup.sudahGantiHari(last, System.currentTimeMillis())) {
                        appendLog("[tg] Backup hari ini sudah ada - terjadwal dilewati.");
                    } else if (!TgBackup.bolehBackupOtomatis(
                            TgBackup.amanString(cek, TgBackup.KEY_TG_PASS, ""))) {
                        String tolak = TgBackup.pesanTolakPlainOtomatis();
                        appendLog("[tg] " + tolak);
                        TgBackup.sendMessage(this, "Backup otomatis GAGAL: " + tolak);
                    } else {

                        if (TgBackup.tundaBilaBootBaru(this, "backup terjadwal")) {
                            return;
                        }

                        String msg = TgBackup.backupOtomatis(this);
                        appendLog("[tg] " + msg);
                        TgBackup.sendMessage(this, "Backup otomatis: " + msg);
                    }
                } catch (Exception e) {
                    String ramah = TgBackup.pesanGalatBackup(e);
                    appendLog("[tg] " + ramah + " (" + e + ")");
                    TgBackup.sendMessage(this, "Backup otomatis GAGAL: " + ramah);
                } finally {
                    selesaikanBackupTerjadwal(startId);
                }
            });
            if (!masuk) {

                appendLog("[tg] Backup terjadwal digeser: antrean tugas penuh"
                        + " - jadwal berikutnya tetap dipasang.");
                selesaikanBackupTerjadwal(startId);
            }
            return START_NOT_STICKY;
        }

        if (intent == null) {
            if (!niatJalanTersimpan()) {
                return START_NOT_STICKY;
            }
            autoRestart = true;
            batalStart = false;
            startTertundaData.set(0);
            healthActive = true;
            startForegroundCompat();
            mulaiSetelahProsesMati = false;
            if (process == null || !alive(process)) {
                startServerAsync();
            } else if (isStopDisengaja(process)) {

                mulaiSetelahProsesMati = true;
            }
            batalUtama(healthTick);
            posUtama(healthTick, HEALTH_FAST_INTERVAL_MS);
            return START_STICKY;
        }

        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }
        autoRestart = true;
        batalStart = false;
        startTertundaData.set(0);
        healthActive = true;
        simpanNiatJalan(true);

        lupakanRiwayatRestart();
        startForegroundCompat();
        mulaiSetelahProsesMati = false;
        if (process == null || !alive(process)) {
            startServerAsync();
        } else if (isStopDisengaja(process)) {

            mulaiSetelahProsesMati = true;
        }
        batalUtama(healthTick);
        posUtama(healthTick, HEALTH_FAST_INTERVAL_MS);

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("ApplySharedPref")
    private void simpanNiatJalan(boolean jalan) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_SERVER_DIMINTA, jalan).commit();
        } catch (Exception ignored) {
        }
    }

    private boolean niatJalanTersimpan() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(KEY_SERVER_DIMINTA, false);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean restartDilewati(boolean starting, boolean running,
            boolean prosesHidup) {
        return starting || (!running && !prosesHidup);
    }

    private void turunkanForegroundBilaTakDiminta(int startId) {
        if (niatJalanTersimpan()) {
            return;
        }
        turunkanForeground(true);
        try {
            stopSelf(startId);
        } catch (Exception ignored) {
        }
    }

    private void selesaikanBackupTerjadwal(int startId) {

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean autoAktif = TgBackup.amanBoolean(sp, TgBackup.KEY_TG_AUTO, false);
        TgBackup.schedule(this, autoAktif);
        boolean serverJalan = process != null && alive(process);

        boolean mulai = starting.get();
        if (!serverJalan && !mulai && !autoAktif) {
            turunkanForeground(true);
            stopSelf(startId);
        } else if (!serverJalan && !mulai) {

            try {
                stopSelf(startId);
            } catch (Exception ignored) {
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void startForegroundCompat() {

        startForeground(NOTIF_ID, bangunNotifikasi());
        notifForeground = true;
    }

    @SuppressWarnings("deprecation")
    private Notification bangunNotifikasi() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        boolean jalan = running || alive(process);
        return b.setContentTitle("Vaultwarden Host")
                .setContentText(teksNotifikasi(jalan, portTampil(), statusLine))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
    }

    static String teksNotifikasi(boolean jalan, String port, String status) {
        if (jalan) {
            return "Server aktif di port " + (port == null || port.isEmpty() ? "?" : port);
        }
        String s = status == null ? "" : status.trim();
        int baris = s.indexOf('\n');
        if (baris >= 0) {
            s = s.substring(0, baris).trim();
        }
        if (s.isEmpty()) {
            return "Menjalankan tugas latar...";
        }
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    private void perbaruiNotifikasi() {
        if (!notifForeground) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, bangunNotifikasi());
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void turunkanForeground(boolean hapus) {
        notifForeground = false;
        try {
            stopForeground(hapus);
        } catch (Exception ignored) {
        }
    }

    public static boolean dataDirAman(String d) {
        if (d == null) {
            return false;
        }
        String t = d.trim();
        if (t.isEmpty() || !t.startsWith("/")) {
            return false;
        }
        if (t.contains("\"") || t.contains("\n") || t.contains("\r") || t.contains("\0")) {
            return false;
        }

        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 || c == 0x7F || c == 0xFEFF
                    || (c >= 0x200B && c <= 0x200F)
                    || (c >= 0x202A && c <= 0x202E)
                    || (c >= 0x2066 && c <= 0x2069)) {
                return false;
            }
        }

        if (t.contains("//") || t.contains("\\")) {
            return false;
        }

        if (t.contains(",") || t.contains("{") || t.contains("}")) {
            return false;
        }

        if (t.length() > 512) {
            return false;
        }

        int segmenIsiAwal = 0;
        int pos = 0;
        int batasT = t.length();
        while (pos <= batasT) {
            int miring = t.indexOf('/', pos);
            if (miring < 0) {
                miring = batasT;
            }
            int panjangSeg = miring - pos;
            if (panjangSeg == 1 && t.charAt(pos) == '.') {
                return false;
            }
            if (panjangSeg == 2 && t.charAt(pos) == '.'
                    && t.charAt(pos + 1) == '.') {
                return false;
            }
            if (panjangSeg > 0) {

                int byteSeg = 0;
                int k = pos;
                while (k < miring) {
                    char c = t.charAt(k);
                    if (c < 0x80) {
                        byteSeg += 1;
                        k++;
                    } else if (c < 0x800) {
                        byteSeg += 2;
                        k++;
                    } else if (Character.isHighSurrogate(c) && k + 1 < miring
                            && Character.isLowSurrogate(t.charAt(k + 1))) {
                        byteSeg += 4;
                        k += 2;
                    } else {
                        byteSeg += 3;
                        k++;
                    }
                    if (byteSeg > 255) {
                        return false;
                    }
                }
                segmenIsiAwal++;
            }
            pos = miring + 1;
        }

        String n = t;
        while (n.length() > 1 && n.endsWith("/")) {
            n = n.substring(0, n.length() - 1);
        }
        if (n.equals("/") || n.equals("/system") || n.equals("/data") || n.equals("/vendor")
                || n.equals("/proc") || n.equals("/sys") || n.equals("/dev")) {
            return false;
        }

        if (n.startsWith("/system/") || n.startsWith("/vendor/")
                || n.startsWith("/proc/") || n.startsWith("/sys/")
                || n.startsWith("/dev/")) {
            return false;
        }
        if (n.equals("/data") || n.startsWith("/data/")) {
            String pkg = "com.tasirin.vaultwardenhost";
            boolean sendiri = n.equals("/data/data/" + pkg)
                    || n.startsWith("/data/data/" + pkg + "/");
            if (!sendiri && n.startsWith("/data/user/")) {
                String sisa = n.substring("/data/user/".length());
                int slash = sisa.indexOf('/');
                if (slash < 0) {
                    sendiri = sisa.equals(pkg);
                } else {
                    String seg0 = sisa.substring(0, slash);
                    String rest = sisa.substring(slash + 1);
                    if (seg0.equals(pkg)) {
                        sendiri = true;
                    } else if (Util.semuaDigit(seg0)) {
                        sendiri = rest.equals(pkg) || rest.startsWith(pkg + "/");
                    }
                }
            }
            if (!sendiri) {
                return false;
            }
        }

        if (n.equals("/mnt/media_rw") || n.startsWith("/mnt/media_rw/")
                || n.equals("/mnt/runtime") || n.startsWith("/mnt/runtime/")
                || n.equals("/mnt/runtime_default") || n.startsWith("/mnt/runtime_default/")
                || n.equals("/storage/self") || n.startsWith("/storage/self/")) {
            return false;
        }

        if (n.equals("/sdcard") || n.equals("/storage/emulated")
                || n.equals("/storage/emulated/0") || n.equals("/storage/self")
                || n.equals("/storage/sdcard0") || n.equals("/mnt/sdcard")
                || n.equals("/mnt/media_rw") || n.equals("/mnt/runtime")) {
            return false;
        }

        return segmenIsiAwal >= 2;
    }

    public static String amankanDataDir(String d) {
        return dataDirAman(d) ? d.trim() : dataDirBawaanSegar();
    }

    static boolean dataDirKanonisAman(String d) {
        if (d == null) {
            return false;
        }
        try {
            return dataDirAman(new File(d.trim()).getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    public static String effectivePort(SharedPreferences sp) {

        String p = null;
        try {
            p = sp == null ? null : TgBackup.amanString(sp, KEY_PORT, DEFAULT_PORT);
        } catch (Exception ignored) {
        }
        return normalisasiPort(p);
    }

    static String normalisasiPort(String p) {
        String t = p == null ? "" : p.trim();

        if (!portTampakAngka(t)) {
            return DEFAULT_PORT;
        }
        try {
            int n = Integer.parseInt(t);
            if (n >= 1024 && n <= 65535) {
                return String.valueOf(n);
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_PORT;
    }

    static boolean portTampakAngka(String t) {
        if (t == null || t.isEmpty() || t.length() > 6) {
            return false;
        }
        int i = 0;
        if (t.charAt(0) == '+' || t.charAt(0) == '-') {
            i = 1;
        }
        if (i >= t.length()) {
            return false;
        }
        for (; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    static String kutipRocket(String path) {
        if (path == null) {
            return "";
        }
        return path.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static final String KEY_PORT_MIGRATED = "port_migrated_8088";

    static boolean perluMigrasiPort(String tersimpan, boolean sudahMigrasi) {
        return !sudahMigrasi && "8080".equals(tersimpan == null ? null : tersimpan.trim());
    }

    public static void migrasiPortSekali(SharedPreferences sp) {
        if (sp == null) {
            return;
        }
        try {
            if (perluMigrasiPort(TgBackup.amanString(sp, KEY_PORT, DEFAULT_PORT),
                    TgBackup.amanBoolean(sp, KEY_PORT_MIGRATED, false))) {
                sp.edit().putString(KEY_PORT, DEFAULT_PORT).apply();
            }
            sp.edit().putBoolean(KEY_PORT_MIGRATED, true).apply();
        } catch (Exception ignored) {
        }
    }

    private String currentPort() {
        return effectivePort(getSharedPreferences(PREFS, MODE_PRIVATE));
    }

    private String portTampil() {
        if (!runningPort.isEmpty()) {
            return runningPort;
        }
        return currentPort();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Vaultwarden server",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void startServerAsync() {
        final int idAwal = startIdTerakhir;
        if (!starting.compareAndSet(false, true)) {

            startSusulanDiminta = true;
            appendLog("[app] Start masih berjalan (unduh binary?), dijalankan setelahnya.");
            return;
        }

        boolean masuk = Util.jalankanLama(() -> {
            boolean kunci = false;

            boolean tunda = false;
            idEpisodaProses = -1;
            try {
                kunci = KUNCI_DATA.tryLock();
                if (!kunci) {

                    int n = startTertundaData.getAndIncrement();
                    if (n == 0) {
                        appendLog("[app] Start ditunda: operasi data (restore/reset)"
                                + " sedang berjalan, dicoba lagi otomatis.");
                        setStatus("Restore/reset sedang berjalan - Start menyusul otomatis.");
                    }
                    if (n < BATAS_START_TERTUNDA_DATA) {
                        tunda = true;
                        batalUtama(restartTunda);
                        posUtama(restartTunda, 5000);
                    } else {
                        startTertundaData.set(0);
                        setStatus("Restore/reset masih berjalan - tekan Start setelah selesai.");
                        appendLog("[app] Start tertunda dihentikan setelah "
                                + BATAS_START_TERTUNDA_DATA + " percobaan;"
                                + " tekan Start lagi setelah restore/reset selesai.");
                    }
                    return;
                }
                startTertundaData.set(0);
                startServer();
            } finally {

                lepasKunciData();

                if (startGagalPermanen(running,
                        process != null && alive(process), tunda,
                        idAwal == startIdTerakhir, idEpisodaProses >= 0)) {
                    akhiriEpisodeGagal(idAwal);
                }
                starting.set(false);

                if (startSusulanDiminta) {
                    startSusulanDiminta = false;
                    if (perluStartSusulan(!tunda, running,
                            process != null && alive(process),
                            niatJalanTersimpan())) {
                        appendLog("[app] Start susulan dijalankan.");
                        startServerAsync();
                    }
                }
            }
        });
        if (!masuk) {

            starting.set(false);
            appendLog("[app] Antrean tugas penuh - Start ditunda, coba lagi.");
            setStatus("Antrean penuh - Start ditunda. Tekan Start lagi.");
        }
    }

    static boolean startGagalPermanen(boolean running, boolean prosesHidup,
                                     boolean ditunda, boolean intentTerbaru,
                                     boolean prosesPernahLahir) {
        return intentTerbaru && !ditunda && !prosesPernahLahir
                && !running && !prosesHidup;
    }

    static boolean bolehUmumkanJalan(boolean prosesSama, boolean running,
                                     boolean prosesHidup, boolean episodeTerbaru) {
        return prosesSama && running && prosesHidup && episodeTerbaru;
    }

    static boolean perluStartSusulan(boolean takDitunda, boolean running,
                                     boolean prosesHidup, boolean niatJalan) {
        return takDitunda && !running && !prosesHidup && niatJalan;
    }

    private void akhiriEpisodeGagal(int idAwal) {
        if (idAwal != startIdTerakhir) {
            return;
        }
        healthActive = false;

        running = false;
        resetPenandaJalan();
        releaseWakeLock();
        batalUtama(healthTick);
        batalUtama(restartTunda);

        simpanNiatJalan(false);
        SharedPreferences spGagal = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!TgBackup.amanBoolean(spGagal, TgBackup.KEY_TG_AUTO, false)) {
            turunkanForeground(true);
            try {

                stopSelfResult(idAwal);
            } catch (Exception ignored) {
            }
        }
    }

    private int idEpisodaProsesAktif() {
        int id = idEpisodaProses;
        return id >= 0 ? id : startIdTerakhir;
    }

    private boolean tungguKunciDataStart() {
        long tenggat = SystemClock.elapsedRealtime() + BATAS_TUNGGU_KUNCI_DATA_MS;
        while (true) {
            if (batalStart) {
                batalStart = false;
                appendLog("[app] Start dibatalkan (Stop ditekan saat persiapan)"
                        + " - tekan Start lagi bila ingin jalan.");
                setStatus("Stopped");
                return false;
            }
            long sisa = tenggat - SystemClock.elapsedRealtime();
            if (sisa <= 0) {
                appendLog("[app] Start dibatalkan: operasi data (restore/reset)"
                        + " masih berjalan lebih dari 5 menit - tekan Start lagi"
                        + " setelah selesai.");
                setStatus("Restore/reset masih berjalan - server tidak start.\n"
                        + "Tekan Start lagi setelah operasi data selesai.");
                return false;
            }
            try {
                if (KUNCI_DATA.tryLock(Math.min(1000L, sisa),
                        java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void startServer() {

        final int idEpisoda = startIdTerakhir;

        final long stopAwal = nomorStop.get();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            TgBackup.healkanStringPrefs(this);
        } catch (Exception ignored) {
        }
        SesiStart sesi = new SesiStart();
        try {
            if (!siapkanFolderData(sp, sesi)) {
                return;
            }
            if (!pilihBiner(stopAwal, sesi)) {
                return;
            }
            if (!siapkanShim(sesi)) {
                return;
            }
            if (!siapkanProsesDanPort(sesi)) {
                return;
            }
            if (!siapkanTls(sp, sesi)) {
                return;
            }
            ProcessBuilder pb = bangunEnv(sp, sesi);
            if (pb == null) {
                return;
            }
            jalankanProses(sesi, pb, idEpisoda);
        } catch (Exception e) {

            Process gagal;
            synchronized (KUNCI_PROSET) {

                gagal = process;
                process = null;
                running = false;

                idEpisodaProses = -1;
            }
            matikanProsesTegas(gagal, 3000);

            resetPenandaJalan();
            releaseWakeLock();
            appendLog("[app] ERROR start: " + e);
            setStatus("Gagal start: " + e.getMessage());
        }
    }


    private static final class SesiStart {
        String dataDir;
        File dataFolder;
        String port;
        File binary;
        File shim;
        File tlsDir;
        String hostSegar;
        String domain;
        String adminToken;
    }

    private boolean siapkanFolderData(SharedPreferences sp, SesiStart sesi) {
        sesi.dataDir = TgBackup.amanString(sp, KEY_DATA_DIR, dataDirBawaanSegar());
        if (!dataDirAman(sesi.dataDir) || !dataDirKanonisAman(sesi.dataDir)) {
            sesi.dataDir = dataDirBawaanSegar();
            appendLog("[app] Folder data tidak valid, pakai bawaan: " + sesi.dataDir);
            sp.edit().putString(KEY_DATA_DIR, sesi.dataDir).apply();
        } else {
            sesi.dataDir = sesi.dataDir.trim();
        }
        sesi.port = currentPort();

        try {
            String mentah = TgBackup.amanString(sp, KEY_PORT, DEFAULT_PORT);

            String mRapi = mentah == null ? "" : mentah.trim();
            if (!mRapi.isEmpty() && !sesi.port.equals(mRapi)) {
                appendLog("[app] Port '" + mRapi + "' tidak valid/privileged - pakai default " + sesi.port
                        + ". Pakai port >= 1024 di Settings bila ingin port lain.");
            }
        } catch (Exception ignored) {
        }

        sesi.dataFolder = new File(sesi.dataDir);
        if (!sesi.dataFolder.exists() && !sesi.dataFolder.mkdirs()) {
            setStatus("Gagal membuat folder: " + sesi.dataDir);
            appendLog("[app] Gagal membuat folder data: " + sesi.dataDir);
            return false;
        }

        try {
            String kanonAwal = sesi.dataFolder.getCanonicalPath();
            if (!dataDirAman(kanonAwal)) {
                throw new IOException("Folder data menunjuk lokasi tak valid.");
            }
            sesi.dataDir = kanonAwal;
            sesi.dataFolder = new File(kanonAwal);
            sp.edit().putString(KEY_DATA_DIR, sesi.dataDir).apply();
        } catch (IOException e) {
            setStatus("Folder data tidak valid - server TIDAK start.");
            appendLog("[app] FATAL: folder data berubah/tak valid sesudah dibuat;"
                    + " dibatalkan agar tak menulis ke folder asing.");
            return false;
        } catch (Exception ignored) {
        }
        if (!sesi.dataFolder.canWrite()) {
            setStatus("Folder tidak bisa ditulis: " + sesi.dataDir);
            appendLog("[app] Folder data tidak writable: " + sesi.dataDir
                    + " - beri izin Storage/Semua file di Pengaturan HP, lalu Start ulang.");
            return false;
        }
        if (sesi.dataDir.startsWith("/sdcard/")
                || sesi.dataDir.startsWith("/storage/") || sesi.dataDir.equals("/sdcard")) {
            appendLog("[app] PERINGATAN: folder data di storage bersama - DB plaintext"
                    + " bisa dibaca app lain berizin storage. PIN hanya mengunci UI/bot,"
                    + " bukan mengenkripsi database.");
        }
        return true;
    }

    private boolean pilihBiner(long stopAwal, SesiStart sesi) {
        pulihkanBakTersisa(new File(sesi.dataDir));
        cleanupTempFiles(sesi.dataDir);
        appendLog("[app] Perangkat: " + KernelCompat.infoBaris(
                KernelCompat.kernelSekarang(), Build.VERSION.SDK_INT));

        lepasKunciData();
        sesi.binary = ensureBinary(stopAwal);
        if (sesi.binary == null) {
            return false;
        }
        return true;
    }

    private boolean siapkanShim(SesiStart sesi) {
        boolean legacy = KernelCompat.legacyPerangkat();
        sesi.shim = null;
        if (legacy) {
            sesi.shim = ensureShim();
            if (sesi.shim == null) {
                return false;
            }
            if (!detectBinaryVersion(sesi.binary)) {
                appendLog("[app] FATAL: binary gagal --version walau shim terpasang"
                        + " - start dibatalkan.");
                setStatus("Binary tidak jalan di perangkat ini - cek log.");
                return false;
            }
            appendLog("[app] Shim getrandom siap - start dengan LD_PRELOAD.");
        }

        if (isKernelRandomPanic(lastVersionOutput)) {
            String kernel = KernelCompat.kernelSekarang();
            String saran = KernelCompat.saranShimGagal(kernel.isEmpty() ? "?" : kernel);
            autoRestart = false;
            setStatus("Binary tidak cocok kernel STB - dihentikan.\n" + saran);
            appendLog("[app] FATAL: " + saran + " Web-vault & TLS tidak masalah.");
            writeCrashLog("binary tak cocok kernel (smoke test)");
            TgBackup.sendMessage(this, "Binary tidak cocok kernel STB lama"
                    + " (getrandom errno=22) - auto-restart dimatikan.\n" + saran);
            return false;
        }
        return true;
    }

    private boolean siapkanProsesDanPort(SesiStart sesi) {
        killStaleVaultwarden();

        int portNum = Integer.parseInt(sesi.port);
        if (isPortBusy(portNum)) {

            setStatus("Port " + sesi.port + " sedang dipakai proses lain.\n"
                    + "Stop server lain / ganti port di Settings, lalu Start lagi.");
            appendLog("[app] Port " + sesi.port + " sedang dipakai - server TIDAK start (cegah loop).");
            TgBackup.sendMessage(this, "Gagal start: port " + sesi.port
                    + " sedang dipakai proses lain. Stop server lain / ganti port lalu coba lagi.");
            return false;
        }
        return true;
    }

    private boolean siapkanTls(SharedPreferences sp, SesiStart sesi) {
        if (!tungguKunciDataStart()) {
            return false;
        }

        sp.edit().putBoolean(KEY_HTTPS, true).apply();
        sesi.tlsDir = prepareTls(sesi.dataFolder);
        if (sesi.tlsDir == null) {
            appendLog("[app] FATAL: sertifikat TLS gagal dibuat - server TIDAK start.");
            setStatus("Gagal buat sertifikat TLS - server tidak start.\n"
                    + "Cek sisa storage & tanggal/jam STB, lalu Start lagi.");
            TgBackup.sendMessage(this, "Gagal start: sertifikat TLS gagal dibuat. "
                    + "Cek sisa storage & tanggal/jam STB lalu coba lagi.");
            return false;
        }
        long certDays = TlsCert.daysLeft(new File(sesi.tlsDir, "cert.pem"));
        if (TlsCert.daysLeft(new File(sesi.tlsDir, "ca.pem")) == -2) {

            appendLog("[app] FATAL: jam STB miring (CA belum valid)"
                    + " - CA dipertahankan, server TIDAK start. Aktifkan"
                    + " Tanggal & waktu otomatis, lalu Start lagi.");
            setStatus("Jam STB salah (CA belum valid) - server tidak start.\n"
                    + "Aktifkan Tanggal & waktu otomatis, lalu Start lagi.");
            TgBackup.sendMessage(this, "Gagal start: tanggal & jam STB salah"
                    + " (CA TLS belum valid). CA tidak diganti; aktifkan"
                    + " Tanggal & waktu otomatis lalu coba lagi.");
            return false;
        }
        if (certDays >= 0 && certDays < 30) {
            appendLog("[app] PERINGATAN: sertifikat TLS tinggal " + certDays
                    + " hari. Sertifikat dibuat ulang otomatis saat IP berubah.");
        } else if (certDays == -2) {

            appendLog("[app] FATAL: jam STB miring (sertifikat belum valid)"
                    + " - server TIDAK start. Aktifkan Tanggal & waktu otomatis,"
                    + " lalu Start lagi.");
            setStatus("Jam STB salah (sertifikat belum valid) - server tidak start.\n"
                    + "Aktifkan Tanggal & waktu otomatis, lalu Start lagi.");
            TgBackup.sendMessage(this, "Gagal start: tanggal & jam STB salah"
                    + " (sertifikat TLS belum valid). Aktifkan Tanggal & waktu"
                    + " otomatis lalu coba lagi.");
            return false;
        }
        return true;
    }

    private ProcessBuilder bangunEnv(SharedPreferences sp, SesiStart sesi) {
        ProcessBuilder pb = new ProcessBuilder(sesi.binary.getAbsolutePath());
        pb.environment().put("DATA_FOLDER", sesi.dataDir);
        pb.environment().put("ROCKET_ADDRESS", "0.0.0.0");
        pb.environment().put("ROCKET_PORT", sesi.port);

        pb.environment().put("ROCKET_WORKERS", "1");
        pb.environment().put("DATABASE_MAX_CONNS", "2");

        sesi.adminToken = TgBackup.amanString(sp, KEY_ADMIN_TOKEN, "");

        String bersih = sesi.adminToken == null ? "" : sesi.adminToken.trim();
        if (!bersih.isEmpty()) {
            if (!tokenAdminValid(bersih)) {
                appendLog("[app] FATAL: admin token mengandung spasi/baris baru"
                        + " - server TIDAK start. Perbaiki di Settings.");
                setStatus("Admin token tak valid (spasi/baris baru) - perbaiki di Settings.");
                TgBackup.sendMessage(this, "Gagal start: admin token tak valid"
                        + " (spasi/baris baru). Perbaiki di Settings.");
                return null;
            }
            pb.environment().put("ADMIN_TOKEN", bersih);
            appendLog("[app] Admin token diaktifkan.");
        }

        File localWv = new File(sesi.dataFolder, "web-vault");
        if (Updater.wvPayloadAda(localWv)) {
            pb.environment().put("WEB_VAULT_ENABLED", "true");
            String folderWv = localWv.getAbsolutePath();
            pb.environment().put("WEB_VAULT_FOLDER", folderWv);
            appendLog("[app] Web vault (updated): " + folderWv);
        } else {
            File webVault = extractWebVault();
            if (webVault != null) {
                pb.environment().put("WEB_VAULT_ENABLED", "true");
                pb.environment().put("WEB_VAULT_FOLDER", webVault.getAbsolutePath());
            } else {
                pb.environment().put("WEB_VAULT_ENABLED", "false");
            }
        }

        String tlsCert = new File(sesi.tlsDir, "cert.pem").getAbsolutePath();
        String tlsKey = new File(sesi.tlsDir, "key.pem").getAbsolutePath();
        pb.environment().put("ROCKET_TLS", "{certs=\"" + kutipRocket(tlsCert) + "\",key=\"" + kutipRocket(tlsKey) + "\"}");
        appendLog("[app] TLS cert: " + tlsCert);
        appendLog("[app] TLS key:  " + tlsKey);
        pb.environment().put("RUST_LOG", "info");
        if (sesi.shim != null) {
            pb.environment().put("LD_PRELOAD", sesi.shim.getAbsolutePath());
            appendLog("[app] LD_PRELOAD shim getrandom aktif.");
        }

        sesi.hostSegar = lanHost();
        sesi.domain = "https://" + formatHostUntukUrl(sesi.hostSegar) + ":" + sesi.port;
        pb.environment().put("DOMAIN", sesi.domain);
        pb.redirectErrorStream(true);
        return pb;
    }

    private void jalankanProses(SesiStart sesi, ProcessBuilder pb, int idEpisoda)
            throws java.io.IOException {
        runningDataDir = sesi.dataDir;
        runningPort = sesi.port;
        runningAdminToken = sidikTokenAdmin(sesi.adminToken);
        String wvFrom = Updater.webVaultFromVersion(this);
        runningWvFrom = wvFrom == null ? "" : wvFrom;
        runningLanHost = sesi.hostSegar;
        ipBerubahDiperingatkan = "";
        ipBaruKandidat = "";
        ipBaruHitung = 0;
        ipRestartDiminta = "";
        ipBaruSejak = 0;

        if (!dataDirKanonisAman(sesi.dataDir)) {
            throw new IOException("Folder data berubah/tak valid saat start;"
                    + " dibatalkan agar server tak jalan di folder asing.");
        }

        boolean batal;

        Process lahir = null;
        synchronized (KUNCI_PROSET) {
            batal = batalStart;
            if (batal) {
                batalStart = false;
            } else {
                lahir = pb.start();
                process = lahir;

                prosesStopDisengaja = null;
                acquireWakeLock();
                running = true;

                idEpisodaProses = startIdTerakhir;
            }
        }
        if (batal) {
            appendLog("[app] Start dibatalkan (Stop ditekan saat persiapan)"
                    + " - tekan Start lagi bila ingin jalan.");

            if (idEpisoda == startIdTerakhir) {
                setStatus("Stopped");
            }
            return;
        }

        healthFails.set(0);
        healthTcpLolos.set(0);
        configSajaBeruntun.set(0);
        lastStartElapsed = SystemClock.elapsedRealtime();

        pindahLogFile(new File(sesi.dataFolder, "vaultwarden.log"));
        umumkanJalan(sesi, lahir);

        final Process p = lahir;
        Thread reader = new Thread(() -> pumpOutput(p), "vw-output");
        reader.setDaemon(true);
        pembacaOutput = reader;
        reader.start();

        Thread watcher = new Thread(() -> watchProcess(p), "vw-watch");
        watcher.setDaemon(true);
        watcher.start();

        appendLog("[app] start: " + sesi.binary.getAbsolutePath()
                + " | versi: " + binaryVersion
                + " | data=" + sesi.dataDir + " | port=" + sesi.port);

        for (String panduan : panduanLoginBitwarden(sesi.port, formatHostUntukUrl(sesi.hostSegar))) {
            appendLog(panduan);
        }
    }

    private void umumkanJalan(SesiStart sesi, Process lahir) {
        boolean bolehUmumkan;
        synchronized (KUNCI_PROSET) {
            bolehUmumkan = bolehUmumkanJalan(process == lahir, running,
                    alive(lahir), idEpisodaProses == startIdTerakhir);
        }
        if (bolehUmumkan) {
            setStatus("Running (PID " + getPid(lahir) + ")\nData: " + sesi.dataDir
                    + "\nURL lokal (di HP): https://127.0.0.1:" + sesi.port
                    + "\nURL jaringan (dari PC/laptop): " + sesi.domain);
            TgBackup.sendMessage(this, "Server jalan:\n" + statusLine);
        } else {
            appendLog("[app] Stop sudah diminta - status dan pesan"
                    + " \"Server jalan\" dilewati.");
        }
        TgBackup.notifyLowStorage(this);
    }

    private void stopServer() {

        final Process p;
        final boolean pengulangan;
        synchronized (KUNCI_PROSET) {
            batalStart = true;
            p = process;

            pengulangan = p != null && isStopDisengaja(p);
            if (p == null) {
                prosesStopDisengaja = null;
                running = false;
                resetPenandaJalan();
            } else {

                tandaiStopDisengaja(p);
            }
        }
        if (p != null) {

            setStatus("Menghentikan...");
            p.destroy();
            Thread stopper = new Thread(() -> {
                try {
                    matikanProsesTegas(p, 5000);
                } finally {

                    lepasProses(p, this::efekProsesBerhenti);
                    hapusTandaStop(p);

                    jalankanStartTertundaStop();
                }
            }, "vw-stop");
            stopper.setDaemon(true);
            stopper.start();
        } else {
            setStatus("Stopped");
            releaseWakeLock();
            flushLogFile();
        }

        if (p != null && !pengulangan) {
            TgBackup.sendMessage(this, "Server dihentikan.");
        }
    }

    private void jalankanStartTertundaStop() {
        if (!mulaiSetelahProsesMati) {
            return;
        }
        mulaiSetelahProsesMati = false;
        if (process != null && alive(process)) {

            return;
        }
        if (!niatJalanTersimpan()) {

            return;
        }
        appendLog("[app] Start tertunda dijalankan: proses lama sudah mati.");
        startServerAsync();
    }

    private static void tungguPembacaOutput() {
        Thread baca = pembacaOutput;
        if (baca == null || baca == Thread.currentThread()) {
            return;
        }
        try {
            baca.join(500);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void watchProcess(Process p) {
        try {
            int code = p.waitFor();

            tungguPembacaOutput();
            if (isStopDisengaja(p) || process != p) {

                lepasProses(p, this::efekProsesBerhenti);
                hapusTandaStop(p);

                jalankanStartTertundaStop();
                return;
            }
            if (lepasProses(p, this::releaseWakeLockBilaBerhenti)) {

                appendLog("[app] process exit: " + code);
                String tail = tailLog(18);
                if (isKernelRandomPanic(tail)) {

                    autoRestart = false;
                    String kernel2 = KernelCompat.kernelSekarang();
                    String saran = KernelCompat.saranShimGagal(
                            kernel2.isEmpty() ? "?" : kernel2);
                    setStatus("Binary tidak cocok kernel STB - dihentikan.\n" + saran);
                    appendLog("[app] FATAL: " + saran
                            + " Web-vault & TLS tidak masalah.");
                    writeCrashLog("binary tak cocok kernel (getrandom)");
                    TgBackup.sendMessage(this, "Binary tidak cocok kernel STB lama"
                            + " (getrandom errno=22) - auto-restart dimatikan.\n" + saran);

                    akhiriEpisodeGagal(idEpisodaProsesAktif());
                    return;
                }
                if (portDirebut(tail)) {

                    autoRestart = false;
                    int portNyata = portLoopback();
                    setStatus("Port " + portNyata + " direbut proses lain - dihentikan.\n"
                            + "Stop server lain / ganti port di Settings, lalu Start lagi.");
                    appendLog("[app] FATAL: bind gagal, port " + portNyata
                            + " direbut proses lain - auto-restart dimatikan.");
                    writeCrashLog("port direbut (bind gagal)");
                    TgBackup.sendMessage(this, "Gagal start: port " + portNyata
                            + " direbut proses lain. Stop server lain / ganti port"
                            + " lalu Start lagi.");
                    akhiriEpisodeGagal(idEpisodaProsesAktif());
                    return;
                }
                if (autoRestart) {
                    writeCrashLog("crash (exit " + code + ")");
                    if (recordRestart("crash (exit " + code + ")")) {

                    } else {
                        String saran = saranCrash(code);
                        if (!saran.isEmpty()) {
                            appendLog("[app] " + saran);
                        }
                        TgBackup.sendMessage(this, "Server crash (exit " + code + ")"
                                + (saran.isEmpty() ? "" : "\n" + saran)
                                + "\n" + shorten(tail, 500) + "\nRestart otomatis...");
                        setStatus("Server crash (exit " + code + ")"
                                + (saran.isEmpty() ? "" : "\n" + saran)
                                + " - restart otomatis\n" + shorten(tail, 250));
                        scheduleRestart();
                    }
                } else {
                    String saran = saranCrash(code);
                    setStatus("Stopped (exit code " + code + ")"
                            + (saran.isEmpty() ? "" : "\n" + saran));
                    if (!saran.isEmpty()) {
                        appendLog("[app] " + saran);
                    }
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private final Runnable restartTunda = new Runnable() {
        @Override
        public void run() {
            if (!healthActive || !autoRestart) {
                return;
            }
            if (starting.get()) {

                posUtama(restartTunda, 2000);
                return;
            }
            if (process == null || !alive(process)) {
                startServerAsync();
            }
        }
    };

    private static void lupakanRiwayatRestart() {
        synchronized (RESTART_TIMES) {
            RESTART_TIMES.clear();
            RESTART_REASONS.clear();
        }
        restartAttempt.set(0);
    }

    private void scheduleRestart() {
        boolean menyerah;
        synchronized (KUNCI_RESTART) {
            menyerah = scheduleRestartTerkunci();
        }
        if (menyerah) {

            akhiriEpisodeGagal(idEpisodaProsesAktif());
        }
    }

    private boolean scheduleRestartTerkunci() {
        long uptime = SystemClock.elapsedRealtime() - lastStartElapsed;
        if (uptime > 60_000) {
            restartAttempt.set(0);
        }
        if (restartAttempt.get() >= RESTART_DELAYS.length) {
            autoRestart = false;
            healthActive = false;
            batalUtama(healthTick);
            String tail = tailLog(18);
            setStatus("Server berhenti - gagal restart 5x.\n" + shorten(tail, 300));
            appendLog("[app] Berhenti mencoba restart setelah 5 kegagalan.");
            writeCrashLog("restart 5x gagal");
            TgBackup.sendPenting(this, "Server berhenti: gagal restart 5x.\n"
                    + shorten(tail, 600));
            return true;
        }
        int coba = restartAttempt.getAndIncrement();
        long delay = RESTART_DELAYS[coba];
        setStatus("Server crash - restart dalam " + (delay / 1000) + " dtk (coba " + (coba + 1) + ")");
        appendLog("[app] Crash terdeteksi, restart dalam " + delay + " ms");
        batalUtama(restartTunda);
        posUtama(restartTunda, delay);
        return false;
    }

    static String saranCrash(int code) {
        if (code == 9) {
            return "Proses dihentikan sistem (exit 9/SIGKILL), kemungkinan RAM penuh. "
                    + "Tutup aplikasi lain / reboot STB lalu Start lagi.";
        }
        return "";
    }

    static boolean minatLogin(String b) {
        if (b == null || b.isEmpty()) {
            return false;
        }
        int n = b.length();
        for (int i = 0; i < n; i++) {
            char c = b.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }
            switch (c) {
                case 'i':
                    if (cocokLoginAt(b, i, "invalid") || cocokLoginAt(b, i, "incorrect")) {
                        return true;
                    }
                    break;
                case 'p':
                    if (cocokLoginAt(b, i, "password")) {
                        return true;
                    }
                    break;
                case 'c':
                    if (cocokLoginAt(b, i, "credential") || cocokLoginAt(b, i, "certificate")) {
                        return true;
                    }
                    break;
                case 'l':
                    if (cocokLoginAt(b, i, "login")) {
                        return true;
                    }
                    break;
                case 'f':
                    if (cocokLoginAt(b, i, "fail")) {
                        return true;
                    }
                    break;
                case 'w':
                    if (cocokLoginAt(b, i, "wrong")) {
                        return true;
                    }
                    break;
                case 't':
                    if (cocokLoginAt(b, i, "token") || cocokLoginAt(b, i, "totp")
                            || cocokLoginAt(b, i, "two-factor") || cocokLoginAt(b, i, "two factor")
                            || cocokLoginAt(b, i, "tls")) {
                        return true;
                    }
                    break;
                case '2':
                    if (cocokLoginAt(b, i, "2fa")) {
                        return true;
                    }
                    break;
                case 's':
                    if (cocokLoginAt(b, i, "ssl")) {
                        return true;
                    }
                    break;
                case 'h':
                    if (cocokLoginAt(b, i, "handshake")) {
                        return true;
                    }
                    break;
                case '/':
                    if (cocokLoginAt(b, i, "/identity/connect/token")) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    private static boolean cocokLoginAt(String b, int pos, String kunci) {
        int m = kunci.length();
        if (pos + m > b.length()) {
            return false;
        }
        for (int j = 0; j < m; j++) {
            if (!Util.samaHurufAscii(b.charAt(pos + j), kunci.charAt(j))) {
                return false;
            }
        }
        return true;
    }

    static String saranLoginUntukBaris(String baris) {
        if (baris == null || baris.isEmpty()) {
            return null;
        }

        if (Util.mengandungAbaikanHuruf(baris, "127.0.0.1")
                || Util.mengandungAbaikanHuruf(baris, "localhost")) {
            return null;
        }

        if (!minatLogin(baris)) {
            return null;
        }
        if (Util.mengandungAbaikanHuruf(baris, "invalid username or password")
                || Util.mengandungAbaikanHuruf(baris, "invalid credentials")
                || Util.mengandungAbaikanHuruf(baris, "username or password is incorrect")
                || Util.mengandungAbaikanHuruf(baris, "wrong password")
                || Util.mengandungAbaikanHuruf(baris, "incorrect password")
                || Util.mengandungAbaikanHuruf(baris, "login failed")
                || Util.mengandungAbaikanHuruf(baris, "failed login")
                || Util.mengandungAbaikanHuruf(baris, "failed log-in")
                || (Util.mengandungAbaikanHuruf(baris, "/identity/connect/token") && Util.mengandungAbaikanHuruf(baris, "401"))
                || (Util.mengandungAbaikanHuruf(baris, "invalid") && (Util.mengandungAbaikanHuruf(baris, "credential") || Util.mengandungAbaikanHuruf(baris, "password")))) {
            return "[login] Login aplikasi Bitwarden gagal: email/password salah atau akun belum "
                    + "terdaftar. Daftar dulu di web-vault (Create Account), lalu login di aplikasi "
                    + "dengan email+password itu (bukan admin token).";
        }
        if (Util.mengandungAbaikanHuruf(baris, "two-factor") || Util.mengandungAbaikanHuruf(baris, "two factor")
                || Util.mengandungAbaikanHuruf(baris, "2fa") || Util.mengandungAbaikanHuruf(baris, "totp")) {
            return "[login] Login butuh kode 2FA: buka aplikasi authenticator lalu masukkan "
                    + "kode 6 digit di aplikasi Bitwarden.";
        }
        boolean tls = Util.mengandungAbaikanHuruf(baris, "certificate") || Util.mengandungAbaikanHuruf(baris, "handshake")
                || Util.mengandungAbaikanHuruf(baris, "tls") || Util.mengandungAbaikanHuruf(baris, "ssl");
        boolean gagal = Util.mengandungAbaikanHuruf(baris, "unknown") || Util.mengandungAbaikanHuruf(baris, "alert") || Util.mengandungAbaikanHuruf(baris, "fail")
                || Util.mengandungAbaikanHuruf(baris, "error") || Util.mengandungAbaikanHuruf(baris, "abort") || Util.mengandungAbaikanHuruf(baris, "refus")
                || Util.mengandungAbaikanHuruf(baris, "ditolak") || Util.mengandungAbaikanHuruf(baris, "verify");
        if (tls && gagal) {
            return "[login] Koneksi aman (TLS) gagal: install dulu CA lewat tombol Install Cert "
                    + "/ Bagikan CA di Settings (jenis \"CA certificate\"), lalu login lagi. "
                    + "Bila dulu bisa lalu gagal: Reset Sertifikat lalu install ulang CA di semua HP.";
        }
        return null;
    }

    static boolean bolehHintLogin(long kiniElapsed, long terakhirElapsed) {
        return kiniElapsed - terakhirElapsed >= LOGIN_HINT_JEDA_MS;
    }

    static boolean bolehPindaiLogin(long kiniElapsed, long terakhirElapsed) {
        return kiniElapsed - terakhirElapsed >= LOGIN_PINDAI_JEDA_MS;
    }

    static java.util.List<String> panduanLoginBitwarden(String port, String lanIp) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        String ip = (lanIp == null || lanIp.isEmpty()) ? "<IP-STB>" : lanIp.trim();
        String p = (port == null || port.isEmpty()) ? DEFAULT_PORT : port.trim();
        String urlBenar = "https://" + ip + ":" + p;
        out.add("[login] Server URL di aplikasi Bitwarden: " + urlBenar
                + " (satu WiFi, tanpa /#/ di belakang; "
                + "bukan 127.0.0.1 dari HP lain).");
        out.add("[login] HTTPS WAJIB: install dulu CA lewat tombol Install Cert / Bagikan CA "
                + "di Settings (jenis \"CA certificate\") agar HP percaya ke server ini. "
                + "Bila IP STB berubah (DHCP): Start ulang lalu install ulang CA.");
        out.add("[login] Belum punya akun? Buka web-vault di browser > Create Account dulu, "
                + "baru login di aplikasi dengan email+password itu (bukan admin token).");
        return out;
    }

    static boolean isKernelRandomPanic(String logTail) {
        if (logTail == null) {
            return false;
        }
        if (logTail.contains("failed to generate random data")) {
            return true;
        }
        if (logTail.contains("bad TLS ticketer")
                || logTail.contains("failed to get random bytes")) {
            return true;
        }
        return Util.mengandungAbaikanHuruf(logTail, "panicked")
                && Util.mengandungAbaikanHuruf(logTail, "getrandom");
    }

    static boolean portDirebut(String logTail) {
        if (logTail == null || logTail.isEmpty()) {
            return false;
        }
        return Util.mengandungAbaikanHuruf(logTail, "address already in use")
                || Util.mengandungAbaikanHuruf(logTail, "address in use")
                || Util.mengandungAbaikanHuruf(logTail, "eaddrinuse")
                || Util.mengandungAbaikanHuruf(logTail, "os error 98");
    }

    static boolean isNoiseLinker(String baris) {
        return baris != null && baris.trim().startsWith("WARNING: linker");
    }

    private boolean recordRestart(String reason) {

        long now = SystemClock.elapsedRealtime();
        String stamp;
        stamp = LOG_TS.get().format(tanggalLog());
        int n;
        synchronized (RESTART_TIMES) {

            while (!RESTART_TIMES.isEmpty()
                    && now - RESTART_TIMES.get(0) > RESTART_WINDOW_MS) {
                RESTART_TIMES.remove(0);
                RESTART_REASONS.remove(0);
            }
            RESTART_TIMES.add(now);
            RESTART_REASONS.add(stamp + " " + reason);
            while (RESTART_TIMES.size() > RESTART_HISTORY_MAX) {
                RESTART_TIMES.remove(0);
                RESTART_REASONS.remove(0);
            }
            n = hitungRestartBaru(RESTART_TIMES, now);
        }
        if (n >= RESTART_WINDOW_MAX) {
            autoRestart = false;
            String tail = tailLog(14);
            setStatus("Restart berulang (" + n + "x dalam 5 mnt) - dihentikan.\n"
                    + shorten(tail, 300));
            appendLog("[app] Restart berulang (" + n
                    + "x dalam 5 mnt) - auto-restart dimatikan.");
            writeCrashLog("restart loop (" + n + "x/5mnt)");
            TgBackup.sendPenting(this, "Server restart berulang (" + n
                    + "x dalam 5 menit) - auto-restart dimatikan.\n"
                    + shorten(tail, 600));

            akhiriEpisodeGagal(idEpisodaProsesAktif());
            return true;
        }
        return false;
    }

    static int hitungRestartBaru(java.util.List<Long> riwayat, long kini) {
        int n = 0;
        for (long t : riwayat) {
            if (kini - t <= RESTART_WINDOW_MS) {
                n++;
            }
        }
        return n;
    }

    public static String restartSummary() {
        synchronized (RESTART_TIMES) {
            int n = hitungRestartBaru(RESTART_TIMES, SystemClock.elapsedRealtime());
            if (n == 0 || RESTART_REASONS.isEmpty()) {
                return "";
            }
            String last = RESTART_REASONS.get(RESTART_REASONS.size() - 1);
            return "Restart: " + n + "x (terakhir " + last + ")";
        }
    }

    public static String crashLogText(Context ctx) {
        File f = new File(ctx.getFilesDir(), CRASH_LOG_NAME);
        if (!f.exists()) {
            return null;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCrashLog(String reason) {
        try {
            String stamp;
            stamp = LOG_TS.get().format(tanggalLog());

            String ekor = tailLog(100);
            String body = "=== " + stamp + " [" + reason + "] ===\n"
                    + (LogActivity.butuhSamaran(ekor) ? LogActivity.samarkanLog(ekor) : ekor) + "\n";
            try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                    new FileOutputStream(new File(getFilesDir(), CRASH_LOG_NAME), false),
                    StandardCharsets.UTF_8)) {
                w.write(body);
            }
        } catch (Exception ignored) {
        }
    }

    private void pumpOutput(Process p) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {

                long kini = SystemClock.elapsedRealtime();
                String hintLogin = null;
                if (bolehHintLogin(kini, loginHintTerakhirElapsed)
                        && bolehPindaiLogin(kini, loginPindaiTerakhirElapsed)) {
                    loginPindaiTerakhirElapsed = kini;
                    hintLogin = saranLoginUntukBaris(line);
                }
                boolean noise = line.contains("CertificateUnknown")
                        || line.contains("tls handshake with 127.0.0.1")
                        || line.contains("Detected TLS-enabled liftoff")
                        || line.contains("unsupported flags DT_FLAGS_1")
                        || line.contains("Shield has enabled a default HSTS policy");
                if (hintLogin != null) {

                    loginHintTerakhirElapsed = kini;
                    if (noise) {
                        appendLog(hintLogin);
                    } else {
                        appendLog(line);
                        appendLog(hintLogin);
                    }
                    continue;
                }

                if (noise) {
                    continue;
                }
                appendLog(line);
            }
        } catch (Exception ignored) {
        } finally {

            if (pembacaOutput == Thread.currentThread()) {
                pembacaOutput = null;
            }
        }
    }

    private static final int LOG_FILE_FLUSH_CHARS = 16 * 1024;
    private static final StringBuilder logFileBuf = new StringBuilder();
    private static final Object LOG_FILE_LOCK = new Object();

    private static java.util.Date tanggalLog() {
        java.util.Date tgl = LOG_TGL.get();
        tgl.setTime(System.currentTimeMillis());
        return tgl;
    }

    private static final ThreadLocal<java.util.Date> LOG_TGL =
            new ThreadLocal<java.util.Date>() {
                @Override protected java.util.Date initialValue() {
                    return new java.util.Date();
                }
            };

    private static String tulisBarisLog(String line) {

        String stamp;
        java.util.Date tgl = LOG_TGL.get();
        tgl.setTime(System.currentTimeMillis());
        stamp = LOG_TS.get().format(tgl);
        synchronized (logBuffer) {

            logBuffer.append(stamp).append(' ').append(line).append('\n');
            pangkasBufferTerkunci();
            logVer++;
        }
        return stamp;
    }

    private static void tulisKeBerkasLog(String stamp, String line) {
        synchronized (LOG_FILE_LOCK) {
            if (logFile == null) {
                return;
            }
            logFileBuf.append(stamp).append(' ').append(line).append('\n');
            if (logFileBuf.length() >= LOG_FILE_FLUSH_CHARS) {
                flushLogFileLocked();
            }
        }
    }

    private void appendLog(String line) {
        if (line == null) {
            return;
        }
        String stamp = tulisBarisLog(line);
        tulisKeBerkasLog(stamp, line);
    }

    private static void flushLogFile() {
        synchronized (LOG_FILE_LOCK) {
            flushLogFileLocked();
        }
    }

    static void pindahLogFile(File baru) {
        synchronized (LOG_FILE_LOCK) {
            flushLogFileLocked();
            logFile = baru;
        }
    }

    private static void flushLogFileLocked() {
        if (logFileBuf.length() == 0) {
            return;
        }
        File f = logFile;
        if (f == null) {
            logFileBuf.setLength(0);
            return;
        }

        if (f.length() >= MAX_LOG_FILE) {
            File old = new File(f.getParentFile(), f.getName() + ".1");
            if (old.exists()) {
                old.delete();
            }
            if (!f.renameTo(old)) {
                try {
                    new FileOutputStream(f, false).close();
                } catch (Exception ignored) {
                }
            }
        }
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            w.write(logFileBuf.toString());
        } catch (Exception ignored) {
        } finally {
            logFileBuf.setLength(0);
        }
    }

    static boolean pingMilikEpisodeSama(Process diukur, Process sekarang,
            int epDiukur, int epSekarang) {
        return diukur != null && diukur == sekarang
                && epDiukur >= 0 && epDiukur == epSekarang;
    }

    private void checkHealthOnce(Process pCek, int epCek) {
        if (!pingMilikEpisodeSama(pCek, process, epCek, idEpisodaProsesAktif())) {
            return;
        }
        peringatkanIpBerubah();
        HasilPing h = pingRinci(this);

        if (!pingMilikEpisodeSama(pCek, process, epCek, idEpisodaProsesAktif())) {
            return;
        }
        if (h.sehat) {

            if (aliveRusakTapiConfigSehat(h.aliveCode, h.configCode)) {

                healthFails.set(0);
                healthTcpLolos.set(0);
                int n = configSajaBeruntun.incrementAndGet();
                if (n >= BATAS_CONFIG_SAJA_BERUNTUN) {
                    configSajaBeruntun.set(0);
                    healthFail("DB rusak beruntun (/alive 5xx tapi /api/config 200, "
                            + BATAS_CONFIG_SAJA_BERUNTUN + "x) — " + h.rincian);
                    return;
                }
                appendLog("[health] /alive 5xx tapi /api/config 200 (DB mungkin rusak,"
                        + " ke-" + n + "/" + BATAS_CONFIG_SAJA_BERUNTUN + ").");
                return;
            }
            configSajaBeruntun.set(0);
            healthFails.set(0);
            healthTcpLolos.set(0);
            return;
        }
        configSajaBeruntun.set(0);
        healthFail("tidak merespon (" + h.rincian + ")");
    }

    static boolean restartIpBolehDiminta(int hitung, long sejakMs, long kiniMs,
            boolean healthAktif, boolean prosesHidup, String kini, String diminta) {
        return hitung >= 2 && sejakMs > 0 && kiniMs - sejakMs >= IP_STABIL_MS
                && healthAktif && prosesHidup
                && kini != null && !kini.isEmpty() && !kini.equals(diminta);
    }

    private void peringatkanIpBerubah() {
        try {
            if (!running || runningLanHost == null || runningLanHost.isEmpty()) {
                return;
            }
            String kini = lanHost();
            if (kini == null || kini.isEmpty() || kini.equals(runningLanHost)) {
                ipBaruKandidat = "";
                ipBaruHitung = 0;
                ipRestartDiminta = "";
                ipBaruSejak = 0;
                return;
            }
            if (kini.equals(ipBaruKandidat)) {
                ipBaruHitung++;
            } else {
                ipBaruKandidat = kini;
                ipBaruHitung = 1;
                ipBaruSejak = SystemClock.elapsedRealtime();
            }
            if (!kini.equals(ipBerubahDiperingatkan)) {
                ipBerubahDiperingatkan = kini;
                appendLog("[app] IP LAN berubah (" + runningLanHost + " -> " + kini + "):"
                        + " DOMAIN server masih menunjuk IP lama."
                        + " Server di-restart otomatis bila IP baru stabil.");
                TgBackup.sendMessage(this, "IP LAN berubah (" + runningLanHost + " -> " + kini + ")."
                        + " Server di-restart otomatis agar tautan memakai IP baru.");
            }
            if (restartIpBolehDiminta(ipBaruHitung, ipBaruSejak,
                    SystemClock.elapsedRealtime(), healthActive, isProcessAlive(),
                    kini, ipRestartDiminta)) {
                ipBaruKandidat = "";
                ipBaruHitung = 0;
                ipBaruSejak = 0;
                appendLog("[health] IP baru stabil 2x tick - restart agar DOMAIN ikut IP baru.");
                if (restart(getApplicationContext())) {
                    ipRestartDiminta = kini;
                } else {
                    appendLog("[app] Restart karena IP baru gagal diminta;"
                            + " akan dicoba lagi sesudah IP terbaca stabil.");
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void healthFail(String reason) {

        if (!healthActive) {
            return;
        }
        int gagal = healthFails.incrementAndGet();
        appendLog("[health] /alive gagal: " + reason + " (ke-" + gagal + "/3)");
        if (gagal >= 3) {

            boolean prosesHidup = process != null && alive(process);
            boolean tcpOk = tcpTersambung(portLoopback());
            if (prosesHidup && tcpOk) {
                int lolos = healthTcpLolos.incrementAndGet();
                if (lolos < 3) {
                    appendLog("[health] 3x gagal tapi port masih tersambung"
                            + " - server TIDAK dihentikan, coba lagi (" + lolos + "/3).");
                    healthFails.set(2);
                    return;
                }
                appendLog("[health] /alive gagal 9x tapi TCP hidup"
                        + " - dianggap gantung, server dihentikan.");
            }

            autoRestart = true;
            healthFails.set(0);
            healthTcpLolos.set(0);

            final Process p;
            synchronized (KUNCI_PROSET) {
                p = process;
                if (p != null) {
                    tandaiStopDisengaja(p);
                }
                running = false;
                resetPenandaJalan();
            }
            setStatus("Server tidak sehat - restart otomatis.");
            appendLog("[health] 3x gagal beruntun - restart otomatis.");
            writeCrashLog("health 3x");
            TgBackup.sendMessage(this, "Server tidak sehat (3x gagal /alive) - restart otomatis.\n"
                    + shorten(tailLog(15), 500));
            if (p != null) {
                releaseWakeLock();

                try {
                    matikanProsesTegas(p, 5000);
                } finally {

                    lepasProses(p, null);
                    hapusTandaStop(p);
                }
            }

            if (recordRestart("health 3x")) {
                return;
            }

            scheduleRestart();
            return;
        }

        return;
    }

    private static volatile javax.net.ssl.SSLSocketFactory sslFactory;

    private static volatile long sslCaCap = -1L;

    private static final Object KUNCI_SSL = new Object();

    private static javax.net.ssl.SSLSocketFactory loopbackSslFactory(Context ctx) throws Exception {
        long cap = capCaAktif(ctx);
        javax.net.ssl.SSLSocketFactory f = sslFactory;
        if (f == null || cap != sslCaCap) {
            synchronized (KUNCI_SSL) {

                cap = capCaAktif(ctx);
                f = sslFactory;
                if (f == null || cap != sslCaCap) {
                    javax.net.ssl.SSLSocketFactory pinned = cobaPinnedCa(ctx);
                    if (pinned == null) {
                        throw new java.io.IOException(
                                "CA lokal tidak tersedia untuk health check.");
                    }
                    sslFactory = pinned;
                    sslCaCap = cap;
                    f = sslFactory;
                }
            }
        }
        return f;
    }

    private static final java.security.SecureRandom ACAK_SSL = new java.security.SecureRandom();

    static java.security.SecureRandom acakSsl() {
        return ACAK_SSL;
    }

    private static volatile long capCaStat = Long.MIN_VALUE;
    private static volatile long capCaNilai = 0L;

    private static volatile long capCaWaktu = 0;

    private static long capCaAktif(Context ctx) {

        long kini = SystemClock.elapsedRealtime();
        if (capCaStat != Long.MIN_VALUE && kini - capCaWaktu < 60_000) {
            return capCaNilai;
        }

        synchronized (KUNCI_SSL) {

        long kini2 = SystemClock.elapsedRealtime();
        if (capCaStat != Long.MIN_VALUE && kini2 - capCaWaktu < 60_000) {
            return capCaNilai;
        }
        try {
            java.io.File ca = null;
            try {
                ca = new java.io.File(ctx.getFilesDir(), "tls/ca.pem");
            } catch (Exception ignored) {
            }
            if ((ca == null || !ca.isFile()) && ctx != null) {
                try {
                    android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    String dataDir = TgBackup.amanString(sp, KEY_DATA_DIR, dataDirBawaanSegar());
                    if (dataDir == null || dataDir.trim().isEmpty()) {
                        dataDir = dataDirBawaanSegar();
                    }
                    ca = new java.io.File(dataDir, "tls/ca.pem");
                } catch (Exception ignored) {
                }
            }
            if (ca != null && ca.isFile()) {
                long stat = ca.lastModified() * 31 + ca.length();
                if (stat == capCaStat) {

                    capCaWaktu = kini2;
                    return capCaNilai;
                }

                long h = stat;
                try (java.io.InputStream in = new java.io.FileInputStream(ca)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        for (int i = 0; i < n; i++) {
                            h = h * 31 + (buf[i] & 0xff);
                        }
                    }
                } catch (Exception ignored) {
                }
                capCaStat = stat;
                capCaNilai = h;
                capCaWaktu = kini2;
                return h;
            }
        } catch (Exception ignored) {
        }

        capCaStat = Long.MIN_VALUE;
        capCaNilai = 0L;
        capCaWaktu = kini2;
        return 0L;
        }
    }

    private static javax.net.ssl.SSLSocketFactory cobaPinnedCa(Context ctx) {
        try {
            if (ctx == null) return null;

            java.io.File ca = new java.io.File(ctx.getFilesDir(), "tls/ca.pem");
            if (!ca.isFile()) {
                android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String dataDir = TgBackup.amanString(sp, KEY_DATA_DIR, dataDirBawaanSegar());
                if (dataDir == null || dataDir.trim().isEmpty()) dataDir = dataDirBawaanSegar();
                ca = new java.io.File(dataDir, "tls/ca.pem");
            }
            if (!ca.isFile()) return null;
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.KeyStore ks = java.security.KeyStore.getInstance(
                    java.security.KeyStore.getDefaultType());
            ks.load(null, null);
            try (java.io.InputStream in = new java.io.FileInputStream(ca)) {
                int i = 0;
                for (java.security.cert.Certificate cert : cf.generateCertificates(in)) {
                    ks.setCertificateEntry("ca-" + (i++), cert);
                }
            }
            if (ksKosong(ks)) return null;
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tmf.getTrustManagers(), acakSsl());

            return HttpsCompat.bungkusTls12(sc.getSocketFactory());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean ksKosong(java.security.KeyStore ks) {
        try {
            return !ks.aliases().hasMoreElements();
        } catch (Exception e) {
            return true;
        }
    }

    private static volatile long ipCacheTime = 0;
    private static volatile String ipCache = "";

    private static final Object IP_LOCK = new Object();

    static boolean ipCacheSegar(long kini, long cacheTime, String cache) {
        return cache != null && !cache.isEmpty() && kini >= cacheTime
                && kini - cacheTime < 60_000;
    }

    static boolean collectCacheSegar(long kini, long cacheTime, boolean kosong) {
        long ttl = kosong ? 10_000L : 60_000L;
        return cacheTime > 0 && kini >= cacheTime && kini - cacheTime < ttl;
    }
    private static volatile long collectCacheTime = 0;
    private static volatile List<String> collectCache = new ArrayList<>();
    private static final Object COLLECT_LOCK = new Object();

    public static String localIp() {
        long now = SystemClock.elapsedRealtime();
        String lama;
        long lamaWaktu;
        synchronized (IP_LOCK) {
            if (ipCacheSegar(now, ipCacheTime, ipCache)) {
                return ipCache;
            }
            lama = ipCache;
            lamaWaktu = ipCacheTime;
        }

        if (lama != null && !lama.isEmpty() && lamaWaktu > 0) {
            jadwalSegarkanIp();
            return lama;
        }

        String segar = lanHost();
        synchronized (IP_LOCK) {
            ipCache = segar;
            ipCacheTime = now;
        }
        return segar;
    }

    private static void jadwalSegarkanIp() {
        Util.jalankanBg(() -> {
            long kini = SystemClock.elapsedRealtime();
            synchronized (IP_LOCK) {
                if (ipCacheSegar(kini, ipCacheTime, ipCache)) {
                    return;
                }
            }
            String segar = lanHost();
            synchronized (IP_LOCK) {
                ipCache = segar;
                ipCacheTime = kini;
            }
        });
    }

    public static String localUrl(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String port = !runningPort.isEmpty() ? runningPort : effectivePort(sp);

        return "https://" + formatHostUntukUrl(localIp()) + ":" + port;
    }

    private void setStatus(String text) {
        statusLine = text;

        perbaruiNotifikasi();
    }

    private void cleanupTempFiles(String dataDir) {
        synchronized (Updater.KUNCI_WEBVAULT) {
            cleanupTempFilesTerkunci(dataDir);
        }
    }

    private void cleanupTempFilesTerkunci(String dataDir) {

        try {
            File induk = new File(dataDir);
            File[] isi = induk.listFiles();
            if (isi != null) {
                for (File f : isi) {
                    if (Updater.sisaStagingWebVault(f.getName())
                            && !Updater.stagingAktif(f.getName())) {
                        Updater.deleteRecursive(f);
                    }
                }
            }
        } catch (Exception ignored) {
            Updater.deleteRecursive(new File(dataDir, "web-vault.new"));
        }

        try {
            java.io.File target = new java.io.File(dataDir, "web-vault");
            java.io.File bak = new java.io.File(dataDir, "web-vault.bak");
            if (bak.exists()) {
                boolean aktifSehat = Updater.wvPayloadAda(target);
                if (!aktifSehat) {
                    Updater.deleteRecursive(target);
                    if (!bak.renameTo(target)) {
                        Updater.deleteRecursive(bak);
                    }
                } else {
                    Updater.deleteRecursive(bak);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private File ensureBinary(long stopAwal) {
        File binDir = new File(getFilesDir(), "bin");
        if (!binDir.exists() && !binDir.mkdirs()) {
            appendLog("[app] Gagal membuat folder binary.");
            return null;
        }

        if (KernelCompat.legacyPerangkat()) {
            try {
                Updater.ensureShimFile(this);
            } catch (Exception abaikan) {

            }
        }
        File out = new File(binDir, "vaultwarden-" + ABI);
        File verFile = new File(binDir, "version.txt");

        File bakBin = new File(binDir, out.getName() + ".bak");
        if (bakBin.exists()) {
            if (pulihkanBakBiner(out, bakBin)) {
                appendLog("[app] Binary .bak dipulihkan (swap sebelumnya terpotong).");
            } else if (isValidBinary(out) || !isValidBinary(bakBin)) {

                try {
                    bakBin.delete();
                } catch (Exception ignored) {
                }
            }
        }
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String dataDir = TgBackup.amanString(sp, KEY_DATA_DIR, dataDirBawaanSegar());
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = dataDirBawaanSegar();
        }

        boolean butuhRefresh = perluRefreshPatch(TgBackup.amanString(sp, KEY_BIN_PATCH, ""));

        File userBin = new File(dataDir, "vaultwarden-" + ABI);
        boolean manualBerubah = manualPerluValidasi(
                TgBackup.amanString(sp, KEY_BIN_SHA, ""),
                TgBackup.amanString(sp, KEY_BIN_MANUAL_FP, ""), userBin);

        String pinShaBin = TgBackup.amanString(sp, Updater.KEY_BIN_SHA_PIN, "");
        boolean binUtuh = Updater.binaryUtuh(out, pinShaBin);
        if (!binUtuh) {
            appendLog("[app] Binary update gagal verifikasi SHA-256 tercatat"
                    + " - diunduh ulang.");
        }

        if (!butuhRefresh && !manualBerubah && isValidBinary(out) && binUtuh) {
            String updated = TgBackup.amanString(sp, KEY_UPDATE_VERSION, "");
            if (updated != null && !updated.isEmpty()) {
                try {
                    if (detectBinaryVersion(out)) {
                        String pin = pinBinaryTersimpan(sp);
                        if (!cacheSesuaiPin(pin, binaryVersion)) {
                            String realCache = Updater.parseBinaryVersion(binaryVersion);
                            appendLog("[app] Binary cache v"
                                    + (realCache == null ? "?" : realCache)
                                    + " bukan versi pilihan v" + pin
                                    + " - unduh ulang versi pilihan.");
                        } else {
                            appendLog("[app] Binary update terbaru dipakai: " + out.getAbsolutePath());
                            if (pinShaBin == null || pinShaBin.isEmpty()) {
                                try {
                                    sp.edit().putString(Updater.KEY_BIN_SHA_PIN,
                                            Updater.sha256Hex(out)).apply();
                                } catch (Exception ignored) {
                                }
                            }

                            if (isValidBinary(userBin)) {
                                appendLog("[app] Binary manual di folder data dilewati:"
                                        + " cache update (" + updated + ") sedang aktif.");
                            }
                            return out;
                        }
                    } else {
                        appendLog("[app] Binary update gagal smoke test --version - diunduh ulang.");
                    }
                } catch (Exception e) {

                    appendLog("[app] Cek binary update gagal: " + e
                            + " - lanjut jalur lain.");
                }
            }
        }

        boolean dataKanonisOk = dataDirKanonisAman(dataDir);
        if (!dataKanonisOk) {
            appendLog("[app] Folder data tak valid/symlink asing - jalur manual dilewati.");
        }
        if (dataKanonisOk && isValidBinary(userBin)) {
            String wantSha = TgBackup.amanString(sp, KEY_BIN_SHA, "");
            if (wantSha != null && !wantSha.trim().isEmpty()) {

                File tmpManual = new File(binDir, "vaultwarden-" + ABI + ".manual-tmp");

                String sidikAwal = sidikManualFile(userBin);
                try {
                    copyBinary(userBin, tmpManual);
                    String gotSha = Updater.sha256Hex(tmpManual);
                    if (gotSha == null || !gotSha.equalsIgnoreCase(wantSha.trim())) {
                        appendLog("[app] Binary manual DITOLAK: SHA-256 tidak cocok"
                                + " dengan pengaturan. Cek kembali file/SHA-nya.");
                        setStatus("Binary manual ditolak: SHA-256 tidak cocok.");
                        TgBackup.sendMessage(this, "Binary manual ditolak: SHA-256 tidak cocok.");
                    } else {

                    if (!detectBinaryVersion(tmpManual)) {
                            appendLog("[app] Binary manual GAGAL smoke test --version"
                                    + " (arsitektur salah/rusak?) - diabaikan,"
                                    + " cache lama dipertahankan.");
                        } else if (!dataDirKanonisAman(dataDir)) {
                            appendLog("[app] Binary manual DITOLAK: folder data berubah"
                                    + " saat penyalinan (symlink asing?) - dibatalkan.");
                        } else {
                            String pinManual = pinBinaryTersimpan(sp);
                            String realManual = Updater.parseBinaryVersion(binaryVersion);
                            if (!cacheSesuaiPin(pinManual, binaryVersion)) {
                                appendLog("[app] Binary manual DITOLAK: versi v"
                                        + (realManual == null ? "?" : realManual)
                                        + " bukan versi pilihan v" + pinManual + ".");
                                setStatus("Binary manual ditolak: bukan versi pilihan v"
                                        + pinManual + ".");
                                TgBackup.sendMessage(this, "Binary manual ditolak:"
                                        + " bukan versi pilihan v" + pinManual + ".");
                            } else {
                                gantiAtomik(tmpManual, out);
                                writeText(verFile, Updater.appVersionName(this));

                                sp.edit().putString(KEY_BIN_PATCH,
                                                String.valueOf(BIN_PATCH_REV))
                                        .putString(KEY_BIN_MANUAL_FP, sidikAwal)
                                        .apply();

                                if (realManual != null && !realManual.isEmpty()) {
                                    sp.edit().putString(KEY_UPDATE_VERSION, realManual).apply();
                                }
                                appendLog("[app] Binary dari folder data dipakai (SHA-256 cocok).");
                                return out;
                            }
                            }
                        }
                    } catch (Exception e) {
                        appendLog("[app] Gagal memakai binary dari folder data: " + e);
                    } finally {
                        try {
                            tmpManual.delete();
                        } catch (Exception ignored) {
                        }
                    }
            } else {
                appendLog("[app] Binary manual DITOLAK: SHA-256 belum diisi"
                        + " di pengaturan. Isi SHA-256 dulu demi keamanan.");
                setStatus("Binary manual ditolak: SHA-256 belum diisi di pengaturan.");
                TgBackup.sendMessage(this, "Binary manual ditolak: SHA-256 belum diisi.");
            }
        }

        if (!butuhRefresh && isValidBinary(out) && verFile.exists()) {
            try {
                if (Updater.appVersionName(this).equals(readText(verFile))) {
                    if (detectBinaryVersion(out)) {
                        String pinCache = pinBinaryTersimpan(sp);
                        if (!cacheSesuaiPin(pinCache, binaryVersion)) {
                            String realCache2 = Updater.parseBinaryVersion(binaryVersion);
                            appendLog("[app] Binary cache v"
                                    + (realCache2 == null ? "?" : realCache2)
                                    + " bukan versi pilihan v" + pinCache
                                    + " - unduh ulang versi pilihan.");
                        } else {
                            return out;
                        }
                    } else {
                        appendLog("[app] Binary cache gagal smoke test --version - diunduh ulang.");
                    }
                }
            } catch (Exception e) {
                appendLog("[app] Cek binary cache internal gagal: " + e
                        + " - lanjut jalur lain.");
            }
        }

        if (butuhRefresh && isValidBinary(out)) {

            long gagalAt = TgBackup.amanLong(sp, KEY_BIN_DL_GAGAL_AT, 0);
            if (!bolehCobaUnduhLagi(gagalAt, System.currentTimeMillis())) {
                try {
                    if (detectBinaryVersion(out)
                            && cacheSesuaiPin(pinBinaryTersimpan(sp), binaryVersion)) {
                        String realTunda = Updater.parseBinaryVersion(binaryVersion);
                        appendLog("[app] Pakai binary cache v"
                                + (realTunda == null ? "?" : realTunda)
                                + "; unduh perbaikan dicoba lagi nanti.");
                        return out;
                    }
                } catch (Exception e) {
                    appendLog("[app] Cek throttle unduhan gagal: " + e
                            + " - tetap coba unduh.");
                }
            }
        }
        try {
            String msg = Updater.downloadBinary(this, out,
                    () -> nomorStop.get() != stopAwal);
            appendLog("[app] " + msg);
            if (butuhRefresh) {
                appendLog("[app] Binary perbaikan (patch favicon) terpasang.");
            }
            sp.edit().putString(KEY_BIN_PATCH, String.valueOf(BIN_PATCH_REV))
                    .remove(KEY_BIN_DL_GAGAL_AT).apply();
            writeText(verFile, Updater.appVersionName(this));
            if (!detectBinaryVersion(out)) {
                appendLog("[app] FATAL: binary hasil unduh gagal smoke test --version"
                        + " (arsitektur salah/rusak?) - tidak dipakai.");
                setStatus("Binary hasil unduh rusak - coba Start ulang.");
                try {
                    out.delete();
                    verFile.delete();
                } catch (Exception ignored) {
                }
                return null;
            }
            return out;
        } catch (Exception e) {

            if (Updater.galatDibatalkan(e)) {
                appendLog("[app] Unduh binary dibatalkan (Stop ditekan)"
                        + " - server tidak dijalankan.");
                return null;
            }

            if (butuhRefresh) {
                try {
                    sp.edit().putLong(KEY_BIN_DL_GAGAL_AT,
                            System.currentTimeMillis()).apply();
                } catch (Exception ignored) {
                }
            }

            if (isValidBinary(out)) {
                try {
                    if (detectBinaryVersion(out)) {
                        String pinDarurat = pinBinaryTersimpan(sp);
                        if (!cacheSesuaiPin(pinDarurat, binaryVersion)) {
                            String realTolak = Updater.parseBinaryVersion(binaryVersion);
                            appendLog("[app] Versi pilihan v" + pinDarurat
                                    + " gagal diunduh; cache v"
                                    + (realTolak == null ? "?" : realTolak)
                                    + " bukan versi pilihan - server TIDAK start.");
                            setStatus("Versi pilihan v" + pinDarurat
                                    + " gagal diunduh; cache beda versi ditolak.");
                        } else {
                            String realDarurat = Updater.parseBinaryVersion(binaryVersion);
                            appendLog("[app] Versi pilihan gagal diunduh ("
                                    + e.getMessage() + ") - pakai binary cache v"
                                    + (realDarurat == null ? "?" : realDarurat)
                                    + " sementara.");
                            return out;
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            String msgBin = e.getMessage();
            String ramah = msgBin != null && msgBin.contains("Cek ")
                    ? msgBin : Updater.pesanGalatUnduh("Unduh binary", e);
            appendLog("[app] Gagal unduh binary: " + e
                    + "\n[app] Saran: " + Updater.saranKoneksi(e)
                    + " Bila STB offline, pakai cara manual di README"
                    + " (taruh binary di folder data).");
            setStatus("Binary belum tersedia - " + ramah);
            return null;
        }
    }

    private boolean isValidBinary(File f) {

        return f != null && f.exists() && f.length() >= 1_000_000 && Updater.isElf(f);
    }

    private void copyBinary(File src, File dst) throws IOException {

        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        try {
            try (InputStream in = new java.io.FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
                fos.getFD().sync();
            }

            if (!tmp.setReadable(true, true) || !tmp.setExecutable(true, true)) {
                throw new IOException("Gagal pasang izin eksekusi binary (storage tak dukung exec).");
            }
            gantiAtomik(tmp, dst);
        } catch (IOException | RuntimeException e) {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    private File extractWebVault() {

        File dir = new File(getFilesDir(), "web-vault");
        if (Updater.wvPayloadAda(dir)) {
            return dir;
        }
        appendLog("[app] Web vault belum terpasang - tekan 'Update Web Vault' "
                + "untuk mengunduh sekali (~35 MB).");
        return null;
    }

    static volatile String lastVersionOutput = "";

    private File ensureShim() {
        try {
            File shim = Updater.ensureShimFile(this);
            appendLog("[app] Shim getrandom dipakai: " + shim.getAbsolutePath());
            return shim;
        } catch (Exception e) {
            String msgShim = e.getMessage();
            String ramah = msgShim != null ? msgShim : e.toString();
            appendLog("[app] Gagal pasang shim getrandom: " + e
                    + "\n[app] Saran: " + Updater.saranKoneksi(e));
            setStatus("Shim getrandom belum tersedia - " + ramah);
            return null;
        }
    }

    private boolean detectBinaryVersion(File binary) {
        Process p = null;
        BufferedReader r = null;

        final java.util.concurrent.atomic.AtomicBoolean versiSelesai =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread versiWatchdog = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(), "--version")
                    .redirectErrorStream(true);
            if (KernelCompat.legacyPerangkat()) {
                File shim = new File(getFilesDir(), "bin/" + KernelCompat.SHIM_ASSET);
                if (Updater.shimValid(shim)) {
                    pb.environment().put("LD_PRELOAD", shim.getAbsolutePath());
                }
            }
            p = pb.start();

            versiWatchdog = Util.pasangWatchdogProses(p, versiSelesai, 10000,
                    "vw-version-watchdog");
            r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));

            String first = null;
            StringBuilder semua = new StringBuilder();
            String baris;
            int dibaca = 0;

            int total = 0;
            while (total < 50 && (baris = r.readLine()) != null) {
                total++;
                if (isNoiseLinker(baris)) {
                    continue;
                }
                if (first == null) {
                    first = baris;
                }
                if (semua.length() > 0) {
                    semua.append('\n');
                }
                semua.append(baris);
                if (++dibaca >= 2) {
                    break;
                }
            }
            versiSelesai.set(true);
            versiWatchdog.interrupt();

            lastVersionOutput = semua.length() == 0 ? "" : semua.toString().trim();

            matikanProsesTegas(p, 2000);
            if (first == null || first.trim().isEmpty()) {
                binaryVersion = "?";
                lastVersionOutput = "";
                return false;
            }
            binaryVersion = first.trim();
            return true;
        } catch (Exception e) {

            try {
                versiSelesai.set(true);
            } catch (Exception ignored) {
            }
            try {
                if (versiWatchdog != null) {
                    versiWatchdog.interrupt();
                }
            } catch (Exception ignored) {
            }
            binaryVersion = "?";
            lastVersionOutput = "";
            return false;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
            if (p != null) {

                matikanProsesTegas(p, 1000);
            }
        }
    }

    private static String lanHost() {
        List<String> ips = collectIps();
        if (ips.isEmpty()) {
            return "127.0.0.1";
        }

        for (String ip : ips) {
            if (ip != null && !ip.isEmpty() && ip.indexOf(':') < 0) {
                return ip;
            }
        }
        return ips.get(0);
    }

    static String formatHostUntukUrl(String host) {
        if (host == null || host.isEmpty()) {
            return "127.0.0.1";
        }
        String h = host.trim();
        if (h.indexOf(':') >= 0 && !(h.startsWith("[") && h.endsWith("]"))) {
            return "[" + h + "]";
        }
        return h;
    }

    static int peringkatAntarmuka(String nama) {
        if (nama == null) {
            return 3;
        }
        String n = nama.toLowerCase(java.util.Locale.US);
        if (n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("en")) {
            return 0;
        }
        if (n.startsWith("tun") || n.startsWith("tap") || n.startsWith("ppp")
                || n.startsWith("wg") || n.startsWith("ipsec")) {
            return 3;
        }
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp")) {
            return 2;
        }
        return 1;
    }

    private static List<String> collectIps() {
        synchronized (COLLECT_LOCK) {

            long now = SystemClock.elapsedRealtime();

            if (collectCacheSegar(now, collectCacheTime, collectCache.isEmpty())) {
                return collectCache;
            }
            List<String> ips = new ArrayList<>();
            try {
                List<NetworkInterface> antarmuka =
                        Collections.list(NetworkInterface.getNetworkInterfaces());
                Collections.sort(antarmuka, (a, b) -> Integer.compare(
                        peringkatAntarmuka(a.getName()), peringkatAntarmuka(b.getName())));
                for (NetworkInterface ni : antarmuka) {
                    if (!ni.isUp() || ni.isLoopback()) {
                        continue;
                    }
                    for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                        if (addr instanceof java.net.Inet4Address) {
                            String mentah = addr.getHostAddress();
                            if (mentah != null && !mentah.startsWith("169.254.")) {
                                ips.add(mentah);
                            }
                        } else if (addr instanceof java.net.Inet6Address) {

                            String mentah = addr.getHostAddress();
                            int persen = mentah.indexOf('%');
                            String bersih = persen < 0 ? mentah : mentah.substring(0, persen);
                            if (!bersih.isEmpty() && !bersih.equalsIgnoreCase("::1")
                                    && !addr.isLinkLocalAddress()) {
                                ips.add(bersih);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            collectCache = Collections.unmodifiableList(ips);
            collectCacheTime = now;
            return collectCache;
        }
    }

    private static String kanonAman(File f) {
        try {
            return f == null ? null : f.getCanonicalPath();
        } catch (Exception e) {
            return null;
        }
    }

    static boolean diStoragePublik(String kanon) {
        if (kanon == null) {
            return true;
        }
        return kanon.equals("/sdcard") || kanon.startsWith("/sdcard/")
                || kanon.equals("/storage") || kanon.startsWith("/storage/")
                || kanon.equals("/mnt/sdcard") || kanon.startsWith("/mnt/sdcard/")
                || kanon.equals("/mnt/media_rw") || kanon.startsWith("/mnt/media_rw/");
    }

    static boolean bolehFallbackTls(boolean publik, boolean siapPakai) {
        return !publik || siapPakai;
    }

    private File prepareTls(File dataFolder) {
        try {
            List<String> ips = new ArrayList<>(collectIps());
            ips.add(0, "127.0.0.1");
            ips.add("::1");

            String cur = joinIps(ips);

            File internal = new File(getFilesDir(), "tls");
            File tlsLama = new File(dataFolder, "tls");
            migrasiTlsKeInternal(tlsLama, internal);
            File ipFile = new File(internal, "ips.txt");
            File dir = ensureCertWithIps(internal, ipFile, ips, cur);
            if (dir == null) {

                File lama = new File(dataFolder, "tls");
                boolean publik = diStoragePublik(kanonAman(lama));
                boolean siap = TlsCert.siapPakaiTanpaTulis(lama);
                if (bolehFallbackTls(publik, siap)) {
                    if (publik) {
                        appendLog("[app] PERINGATAN: internal gagal, memakai TLS lama"
                                + " di storage publik apa adanya (tanpa menulis kunci).");
                        dir = lama;
                    } else {
                        dir = ensureCertWithIps(lama, new File(lama, "ips.txt"),
                                ips, cur);
                    }
                } else {
                    appendLog("[app] Gagal siapkan TLS di internal; kunci privat TIDAK"
                            + " dibuat di storage publik (" + lama.getAbsolutePath()
                            + "). Periksa ruang/izin penyimpanan internal, lalu Start ulang.");
                }
            }
            if (dir != null) {
                bersihKunciTlsLama(tlsLama, dir, internal);
                appendLog("[app] Sertifikat HTTPS: " + new File(dir, "cert.pem").getAbsolutePath());
                appendLog("[app] CA untuk HP lain: " + new File(dir, "ca.pem").getAbsolutePath());
                try {
                    String kanon = dir.getCanonicalPath();
                    if (kanon.startsWith("/sdcard")
                            || kanon.startsWith("/storage")) {
                        appendLog("[app] PERINGATAN: kunci TLS di storage publik"
                                + " (FAT, chmod tak berlaku). Siapa pun berizin baca storage"
                                + " bisa menyalin ca-key.pem/key.pem.");
                    }
                } catch (Exception ignored) {
                }
            }
            return dir;
        } catch (Exception e) {
            appendLog("[app] Gagal siapkan TLS: " + e);
            return null;
        }
    }

    private void migrasiTlsKeInternal(File lama, File baru) {
        try {
            if (!new File(lama, "ca.pem").isFile()
                    || !new File(lama, "ca-key.pem").isFile()) {
                return;
            }
            if (new File(baru, "ca.pem").isFile()
                    && new File(baru, "ca-key.pem").isFile()) {
                return;
            }
            if (!baru.exists() && !baru.mkdirs()) {
                return;
            }
            for (String nama : new String[]{"ca.pem", "ca-key.pem", "cert.pem",
                    "key.pem", "ips.txt", "version.txt"}) {
                File asal = new File(lama, nama);
                File tujuan = new File(baru, nama);
                if (asal.isFile() && !tujuan.isFile()) {
                    TgBackup.copyFile(asal, tujuan);
                }
            }
            appendLog("[app] TLS dimigrasi ke internal (kunci tak lagi di /sdcard).");
        } catch (Exception ignored) {
        }
    }

    private void bersihKunciTlsLama(File lama, File dirAktif, File internal) {
        try {
            if (lama == null || dirAktif == null || internal == null) {
                return;
            }
            String kanonAktif = dirAktif.getCanonicalPath();
            String kanonInternal = internal.getCanonicalPath();

            if (!kanonAktif.equals(kanonInternal)) {
                return;
            }
            String kanonLama = lama.getCanonicalPath();

            if (kanonLama.equals(kanonInternal)) {
                return;
            }
            for (String nama : new String[]{"ca-key.pem", "key.pem"}) {
                try {
                    File kunci = new File(lama, nama);
                    if (kunci.isFile() && !kunci.delete()) {
                        appendLog("[app] PERINGATAN: kunci lama " + nama
                                + " tak terhapus dari storage publik - hapus manual.");
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private File ensureCertWithIps(File tlsDir, File ipFile, List<String> ips,
            String cur) throws Exception {
        String saved = readText(ipFile);

        boolean ipBerubah = saved == null || !saved.equals(cur);
        if (ipBerubah) {
            appendLog("[app] IP berubah - regenerasi sertifikat server (CA tetap).");
        }
        File dir = TlsCert.ensure(tlsDir, ips, ipBerubah);
        if (dir != null) {
            writeText(ipFile, cur);

            synchronized (KUNCI_SSL) {
                sslFactory = null;
                sslCaCap = -1L;
                capCaStat = Long.MIN_VALUE;
                capCaWaktu = 0;
            }
        }
        return dir;
    }

    private static String joinIps(List<String> ips) {
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(ip);
        }
        return sb.toString();
    }

    private static String readText(File f) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeText(File f, String text) throws Exception {
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new FileOutputStream(f, false), StandardCharsets.UTF_8)) {
            w.write(text);
        }
    }

    private String getPid(Process p) {
        int pid = pidOf(p);
        return pid < 0 ? "?" : String.valueOf(pid);
    }

    private static volatile java.lang.reflect.Method pidMethod;
    private static volatile boolean pidMethodLookedUp = false;

    private static final Object KUNCI_PID = new Object();

    private static int pidOf(Process p) {
        if (p == null) {
            return -1;
        }
        try {
            java.lang.reflect.Method m = pidMethod;
            if (m == null && !pidMethodLookedUp) {
                synchronized (KUNCI_PID) {
                    if (!pidMethodLookedUp) {
                        try {
                            pidMethod = Process.class.getMethod("pid");
                        } catch (Throwable ignored) {
                        }
                        pidMethodLookedUp = true;
                    }
                    m = pidMethod;
                }
            }
            if (m == null) {
                return pidAnakDariProc(p);
            }

            return ((Number) m.invoke(p)).intValue();
        } catch (Throwable t) {
            return pidAnakDariProc(p);
        }
    }

    private static final java.util.Map<Process, Integer> CACHE_PID_PROSES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static int pidAnakDariProc(Process p) {
        try {
            p.exitValue();
            return -1;
        } catch (IllegalThreadStateException e) {
        } catch (Throwable t) {
            return -1;
        }
        Integer tersimpan = CACHE_PID_PROSES.get(p);
        if (tersimpan != null) {
            return tersimpan;
        }
        int myPid = android.os.Process.myPid();
        java.util.List<Integer> cocok = new java.util.ArrayList<>();
        for (int pid : kandidatPidSeUid()) {
            if (pid == myPid) {
                continue;
            }
            File dir = new File("/proc/" + pid);
            if (!cmdlineServer(readProcCmdline(dir))) {
                continue;
            }
            if (ppidDariStat(bacaBarisProc(new File(dir, "stat"))) == myPid) {
                cocok.add(pid);
            }
        }
        int hasil = pidDariKecocokan(cocok);
        if (hasil > 0) {
            CACHE_PID_PROSES.put(p, hasil);
        }
        return hasil;
    }

    static int pidDariKecocokan(java.util.List<Integer> cocok) {
        return cocok != null && cocok.size() == 1 ? cocok.get(0) : -1;
    }

    static int ppidDariStat(String stat) {
        if (stat == null) {
            return -1;
        }
        int tutup = stat.lastIndexOf(')');
        if (tutup < 0) {
            return -1;
        }
        int n = stat.length();
        int i = tutup + 1;
        while (i < n && (stat.charAt(i) == ' ' || stat.charAt(i) == '\t')) {
            i++;
        }
        while (i < n && stat.charAt(i) != ' ' && stat.charAt(i) != '\t') {
            i++;
        }
        while (i < n && (stat.charAt(i) == ' ' || stat.charAt(i) == '\t')) {
            i++;
        }
        int j = i;
        if (j < n && (stat.charAt(j) == '-' || stat.charAt(j) == '+')) {
            j++;
        }
        int awalAngka = j;
        while (j < n && stat.charAt(j) >= '0' && stat.charAt(j) <= '9') {
            j++;
        }
        if (j == awalAngka) {
            return -1;
        }
        try {
            return Integer.parseInt(stat.substring(i, j));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String bacaBarisProc(File f) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f),
                        StandardCharsets.UTF_8), 2048)) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static volatile int cachedChildPid = -1;
    private static volatile long cachedChildPidAt = 0;

    private static final long CHILD_PID_TTL_MS = 120_000;

    private static volatile long rssCacheKb = -1;
    private static volatile int rssCachePid = -1;
    private static volatile long rssCacheAt = 0;
    private static final long RSS_CACHE_TTL_MS = 5_000;

    public static long processRssKb() {

        if (!running) {
            return -1;
        }
        int pid = -1;
        Process p = process;
        if (p != null) {
            pid = pidOf(p);
        }
        if (pid < 0) {
            long now = SystemClock.elapsedRealtime();
            int cached = cachedChildPid;
            if (cached >= 0 && now - cachedChildPidAt < CHILD_PID_TTL_MS
                    && pidMilikiServer(cached)) {
                pid = cached;
            } else {
                pid = findChildPid();
                cachedChildPid = pid;
                cachedChildPidAt = now;
            }
        }
        if (pid < 0) {
            return -1;
        }
        long nowRss = SystemClock.elapsedRealtime();
        if (pid == rssCachePid && nowRss - rssCacheAt < RSS_CACHE_TTL_MS) {
            return rssCacheKb;
        }
        long hasil = -1;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream("/proc/" + pid + "/status"),
                        StandardCharsets.UTF_8), 2048)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {

                    String angka = line.substring(6).trim();
                    int sp = angka.indexOf(' ');
                    if (sp >= 0) {
                        angka = angka.substring(0, sp);
                    }
                    try {
                        hasil = Long.parseLong(angka);
                    } catch (NumberFormatException nfe) {
                        hasil = -1;
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        rssCachePid = pid;
        rssCacheKb = hasil;
        rssCacheAt = nowRss;
        return hasil;
    }

    private static final Object KUNCI_PROC = new Object();
    private static java.util.List<Integer> cachePidSeUid = null;
    private static long cachePidSeUidAt = 0;
    private static final long PID_CACHE_TTL_MS = 8000;

    private static void segarkanCachePid() {
        synchronized (KUNCI_PROC) {
            cachePidSeUid = null;
            cachePidSeUidAt = 0;
        }
    }

    private static java.util.List<Integer> kandidatPidSeUid() {
        long kiniCache = SystemClock.elapsedRealtime();
        synchronized (KUNCI_PROC) {
            if (cachePidSeUid != null && kiniCache - cachePidSeUidAt < PID_CACHE_TTL_MS) {
                return cachePidSeUid;
            }
        }
        java.util.List<Integer> keluar = new java.util.ArrayList<>();
        try {
            File[] dirs = new File("/proc").listFiles();
            if (dirs == null) {
                return keluar;
            }
            int myUid = android.os.Process.myUid();
            int myPid = android.os.Process.myPid();
            for (File d : dirs) {
                String name = d.getName();
                if (name.isEmpty() || !Character.isDigit(name.charAt(0))) {
                    continue;
                }
                try {
                    int pid = Integer.parseInt(name);
                    if (pid == myPid) {
                        continue;
                    }
                    if (readProcUid(d) != myUid) {
                        continue;
                    }
                    keluar.add(pid);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        java.util.List<Integer> beku =
                java.util.Collections.unmodifiableList(keluar);
        synchronized (KUNCI_PROC) {
            cachePidSeUid = beku;
            cachePidSeUidAt = SystemClock.elapsedRealtime();
        }
        return beku;
    }

    private static int findChildPid() {
        int myPid = android.os.Process.myPid();
        java.util.List<Integer> cocok = new java.util.ArrayList<>();
        try {
            for (int pid : kandidatPidSeUid()) {
                if (pid == myPid) {
                    continue;
                }
                try {
                    File dir = new File("/proc/" + pid);
                    if (!cmdlineServer(readProcCmdline(dir))) {
                        continue;
                    }
                    if (ppidDariStat(bacaBarisProc(new File(dir, "stat"))) == myPid) {
                        cocok.add(pid);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return pidDariKecocokan(cocok);
    }

    private void killStaleVaultwarden() {
        try {
            int myPid = android.os.Process.myPid();
            int childPid = runningChildPid();
            boolean anakHidupTakTeridentifikasi = isProcessAlive() && childPid < 0;
            for (int pid : kandidatPidSeUid()) {
                try {
                    if (pid == myPid || pid == childPid) {
                        continue;
                    }
                    String cmd = readProcCmdline(new File("/proc/" + pid));
                    if (bolehBunuhBasi(cmd)) {

                        if (pid == myPid || pid == runningChildPid()) {
                            continue;
                        }
                        // Anak yang BENAR-BENAR hidup tetapi PID-nya tak
                        // teridentifikasi (scan /proc ambigu — lazim di
                        // API 21-25 saat dua anak sempat hidup bersamaan)
                        // dan kandidat ini berinduk ke aplikasi ini: jangan
                        // dibunuh, bisa jadi ia yang sedang berjalan.
                        // childPid -1 TANPA proses hidup bukan ambiguitas
                        // (keadaan normal sesudah Stop): kandidat memang
                        // basi dan wajib dibersihkan (Audit 612, dikoreksi
                        // Audit 622). Yatim berinduk lain tetap dibersihkan.
                        int ppid = ppidDariStat(bacaBarisProc(
                                new File(new File("/proc/" + pid), "stat")));
                        if (lewatiBunuhBasi(anakHidupTakTeridentifikasi, ppid, myPid)) {
                            continue;
                        }
                        if (!pidMilikiServer(pid)) {
                            continue;
                        }
                        android.os.Process.killProcess(pid);
                        appendLog("[app] Proses vaultwarden lama (pid " + pid + ") dibersihkan.");
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean bereskanServerYatim() {
        int myPid = android.os.Process.myPid();
        if (isProcessAlive() && pidOf(process) < 0) {
            return false;
        }
        for (int percobaan = 0; percobaan < 3; percobaan++) {

            segarkanCachePid();
            int childPid = pidOf(process);
            boolean ada = false;
            for (int pid : kandidatPidSeUid()) {
                String cmd;
                try {
                    cmd = readProcCmdline(new File("/proc/" + pid));
                } catch (Exception e) {
                    continue;
                }
                if (!pidYatimServer(pid, myPid, childPid, cmd)) {
                    continue;
                }
                ada = true;

                if (!pidMilikiServer(pid)) {
                    continue;
                }
                if (pid == myPid || pid == childPid) {
                    continue;
                }
                try {
                    android.os.Process.killProcess(pid);
                    catatLog("[app] Proses vaultwarden yatim (pid " + pid
                            + ") dibersihkan sebelum restore.");
                } catch (Exception ignored) {
                }
            }
            if (!ada) {
                return true;
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        segarkanCachePid();
        int childPidAkhir = pidOf(process);
        for (int pid : kandidatPidSeUid()) {
            if (pid != myPid && pid != childPidAkhir && pidMilikiServer(pid)) {
                catatLog("[app] Proses vaultwarden yatim (pid " + pid
                        + ") masih hidup - restore dibatalkan.");
                return false;
            }
        }
        return true;
    }

    static boolean cmdlineServer(String cmd) {
        return cmd != null && cmd.contains("/bin/vaultwarden-")
                && !cmd.contains("--version");
    }

    static boolean bolehBunuhBasi(String cmd) {
        return cmdlineServer(cmd);
    }

    static boolean lewatiBunuhBasi(boolean anakHidupTakTeridentifikasi,
            int ppid, int myPid) {
        return anakHidupTakTeridentifikasi && ppid == myPid;
    }

    static boolean pidYatimServer(int pid, int myPid, int childPid, String cmd) {
        return pid >= 0 && pid != myPid && pid != childPid && cmdlineServer(cmd);
    }

    static boolean pidMilikiServer(int pid) {
        if (pid < 0) {
            return false;
        }
        try {
            return cmdlineServer(readProcCmdline(new File("/proc/" + pid)));
        } catch (Exception e) {
            return false;
        }
    }

    private int runningChildPid() {
        return pidOf(process);
    }

    private static String readProcCmdline(File dir) {
        File f = new File(dir, "cmdline");
        if (!f.canRead()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            // Baca sampai habis (satu read() tak menjamin buffer penuh)
            // dengan plafon 8192: cmdline yang terpotong dulu bisa
            // memutus pengecualian "--version" di cmdlineServer yang
            // letaknya paling akhir (Audit 611).
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() >= 8192) {
                    break;
                }
            }
            if (out.size() <= 0) {
                return null;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8)
                    .replace('\u0000', ' ').trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static int readProcUid(File dir) {

        try (BufferedReader r = new BufferedReader(
                new java.io.FileReader(new File(dir, "status")), 2048)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Uid:")) {

                    int i = 4;
                    int n = line.length();
                    while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                        i++;
                    }
                    int j = i;
                    while (j < n && line.charAt(j) >= '0' && line.charAt(j) <= '9') {
                        j++;
                    }
                    if (j > i) {
                        return Integer.parseInt(line.substring(i, j));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    public static boolean isPortBusy(int port) {
        if (port < 1 || port > 65535) {
            return true;
        }

        return !bisaBind("0.0.0.0", port) || !bisaBind("::", port);
    }

    static boolean bisaBind(String host, int port) {
        try (ServerSocket s = new ServerSocket()) {

            try {
                s.setReuseAddress(true);
            } catch (Exception ignored) {
            }
            s.bind(new InetSocketAddress(host, port));
            return true;
        } catch (Exception e) {

            if (host.contains(":")) {
                String rendah = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.US);
                if (rendah.contains("family") || rendah.contains("protonosupport")
                        || rendah.contains("eafnosupport") || rendah.contains("not supported")) {
                    return true;
                }
            }
            return false;
        }
    }

    static boolean gagalIzinBind(String host, int port) {
        try (ServerSocket s = new ServerSocket()) {
            try {
                s.setReuseAddress(true);
            } catch (Exception ignored) {
            }
            s.bind(new InetSocketAddress(host, port));
            return false;
        } catch (Exception e) {
            String rendah = String.valueOf(e.getMessage())
                    .toLowerCase(java.util.Locale.US);
            return rendah.contains("eacces") || rendah.contains("permission denied");
        }
    }

    public static boolean portButuhRoot(int port) {
        if (port < 1 || port > 65535) {
            return false;
        }
        return gagalIzinBind("0.0.0.0", port) || gagalIzinBind("::", port);
    }

    static String tailLog(int lines) {
        synchronized (logBuffer) {
            int len = logBuffer.length();

            int[] mulai = new int[Math.max(0, lines)];
            int[] akhir = new int[Math.max(0, lines)];
            int hitung = 0;
            int end = len;
            for (int i = len - 1; i >= -1 && hitung < mulai.length; i--) {
                if (i < 0 || logBuffer.charAt(i) == '\n') {
                    int start = i + 1;
                    int e = end;
                    while (e > start && (logBuffer.charAt(e - 1) == '\r'
                            || logBuffer.charAt(e - 1) == '\n')) {
                        e--;
                    }
                    if (e > start && !rentangKosong(logBuffer, start, e)) {
                        mulai[hitung] = start;
                        akhir[hitung] = e;
                        hitung++;
                    }
                    end = i;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int k = hitung - 1; k >= 0; k--) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(logBuffer, mulai[k], akhir[k]);
            }
            return sb.toString();
        }
    }

    static boolean rentangKosong(CharSequence s, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static int logLength() {
        synchronized (logBuffer) {
            return logBuffer.length();
        }
    }

    public static long logVersion() {
        return logVer;
    }

    public static void catatLog(String line) {
        if (line == null) {
            return;
        }
        String stamp = tulisBarisLog(line);
        tulisKeBerkasLog(stamp, line);
        flushLogFile();
    }

    private static void pangkasBufferTerkunci() {
        if (logBuffer.length() > LOG_TRIM_THRESHOLD) {
            int potongBuf = logBuffer.length() - MAX_LOG_CHARS / 2;
            if (potongBuf > 0 && potongBuf < logBuffer.length()
                    && Character.isLowSurrogate(logBuffer.charAt(potongBuf))
                    && Character.isHighSurrogate(logBuffer.charAt(potongBuf - 1))) {
                potongBuf++;
            }
            int batas = Math.min(logBuffer.length(), potongBuf + 4096);
            while (potongBuf < batas && logBuffer.charAt(potongBuf) != '\n') {
                potongBuf++;
            }
            if (potongBuf < logBuffer.length()) {
                potongBuf++;
            }
            logBuffer.delete(0, potongBuf);
        }
    }

    private static String shorten(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        int mulai = s.length() - max;

        if (mulai > 0 && Character.isLowSurrogate(s.charAt(mulai))
                && Character.isHighSurrogate(s.charAt(mulai - 1))) {
            mulai++;
        }
        return s.substring(mulai);
    }

    private static final class PenungguProses {
        final java.util.concurrent.atomic.AtomicBoolean mati =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread thread;
    }

    private static final java.util.Map<Process, PenungguProses> PENUNGGU_PROSES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static boolean waitForOrKill(Process p, long timeoutMillis) throws InterruptedException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        PenungguProses penunggu;
        synchronized (PENUNGGU_PROSES) {
            penunggu = PENUNGGU_PROSES.get(p);
            if (penunggu == null) {
                penunggu = new PenungguProses();
                PENUNGGU_PROSES.put(p, penunggu);
            }
            if (penunggu.thread == null) {
                final PenungguProses pegangan = penunggu;
                Thread t = new Thread(() -> {
                    try {
                        p.waitFor();
                    } catch (Exception ignored) {
                    } finally {
                        pegangan.mati.set(true);
                    }
                }, "vw-wait");
                t.setDaemon(true);
                penunggu.thread = t;
                t.start();
            }
        }
        penunggu.thread.join(timeoutMillis);
        return penunggu.mati.get();
    }

    static void matikanProsesTegas(Process p, long tungguMs) {
        if (p == null) {
            return;
        }
        try {
            p.destroy();
            if (waitForOrKill(p, tungguMs)) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                p.destroyForcibly();
            } else {
                p.destroy();
            }
            waitForOrKill(p, 3000);
        } catch (Exception ignored) {
        }
    }

    private void jagaWakeLock() {
        try {
            boolean perlu;
            synchronized (KUNCI_WAKELOCK) {
                perlu = running && autoRestart && (wakeLock == null || !wakeLock.isHeld());
            }
            if (perlu) {
                acquireWakeLock();
            }
        } catch (Exception ignored) {
        }
    }

    private void acquireWakeLock() {

        synchronized (KUNCI_WAKELOCK) {
            releaseWakeLockDalam();
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vaultwarden:server");

                    try {
                        wakeLock.acquire(12L * 3600 * 1000L);
                    } catch (Exception e) {
                        wakeLock = null;
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void releaseWakeLockDalam() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        wakeLock = null;
    }

    private void releaseWakeLock() {
        synchronized (KUNCI_WAKELOCK) {
            releaseWakeLockDalam();
        }
    }

    @Override
    public void onDestroy() {

        notifForeground = false;

        healthActive = false;
        batalStart = true;
        nomorStop.incrementAndGet();
        mulaiSetelahProsesMati = false;
        startSusulanDiminta = false;
        batalUtama(healthTick);
        batalUtama(restartTunda);
        flushLogFile();

        Process p = process;
        if (p != null) {
            tandaiStopDisengaja(p);
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
            Thread stopper = new Thread(() -> {
                try {
                    matikanProsesTegas(p, 5000);
                } catch (Exception ignored) {
                }
                try {
                    lepasProses(p, this::efekProsesBerhenti);
                } catch (Exception ignored) {
                }
                hapusTandaStop(p);
                jalankanStartTertundaStop();
            }, "vw-stop");
            stopper.setDaemon(true);
            stopper.start();
        }

        releaseWakeLock();
        super.onDestroy();
    }
}
