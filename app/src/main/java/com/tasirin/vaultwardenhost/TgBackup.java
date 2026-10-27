package com.tasirin.vaultwardenhost;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.annotation.SuppressLint;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.Comparator;
import java.util.UUID;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

public final class TgBackup {

    public static final String KEY_TG_TOKEN = "tg_token";
    public static final String KEY_TG_CHAT = "tg_chat";
    public static final String KEY_TG_AUTO = "tg_auto";
    public static final String KEY_TG_LAST = "tg_last_backup";
    public static final String KEY_TG_PASS = "tg_pass";
    public static final String KEY_TG_LAST_FILE = "tg_last_file";
    public static final String KEY_TG_LAST_NAME = "tg_last_name";

    public static final String KEY_BACKUP_TERTUNDA = "tg_backup_tertunda";
    private static final String KEY_TG_LOW_STORAGE = "tg_low_storage_notified";

    public static void migrateAutoPref(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            java.util.Map<String, ?> semua;
            try {
                semua = sp.getAll();
            } catch (Exception ignored) {
                return;
            }
            if (semua == null) {
                return;
            }
            if (semua.containsKey("tg_backup_on_start")) {
                Boolean lama = koersiBoolean(semua.get("tg_backup_on_start"));
                if (Boolean.TRUE.equals(lama)
                        && !Boolean.TRUE.equals(koersiBoolean(semua.get(KEY_TG_AUTO)))) {
                    sp.edit().putBoolean(KEY_TG_AUTO, true).apply();
                }
                sp.edit().remove("tg_backup_on_start").apply();
            }
            if (semua.containsKey("tg_full")) {
                sp.edit().remove("tg_full").apply();
            }
        } catch (Exception ignored) {
        }
    }

    private static final long LOW_STORAGE_BYTES = 500L * 1024 * 1024;

    static final byte[] MAGIC_SQLITE =
            "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    private static java.util.concurrent.ThreadFactory pabrikPesan(final String nama) {
        return r -> {
            Thread t = new Thread(r, nama);
            t.setDaemon(true);
            return t;
        };
    }

    private static java.util.concurrent.RejectedExecutionHandler tolakPesan(
            final String gugur, final String penuh, final String catat) {
        return (tugas, eksekutor) -> {
            try {
                if (!eksekutor.isShutdown()) {
                    eksekutor.getQueue().poll();
                    if (!eksekutor.getQueue().offer(tugas)) {
                        try {
                            android.util.Log.w("TgBackup", gugur);
                        } catch (Exception ignored2) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                android.util.Log.w("TgBackup", penuh);
            } catch (Exception ignored) {
            }
            try {
                ServerService.catatLog(catat);
            } catch (Exception ignored) {
            }
        };
    }

    private static final ExecutorService TG_MSG_EXEC =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<Runnable>(20),
                    pabrikPesan("vw-tgmsg"),
                    tolakPesan("Pesan gugur, antrean tetap penuh.",
                            "Antrean pesan Telegram penuh, pesan tertua dibuang.",
                            "[tg] Antrean pesan penuh, pesan tertua dibuang."));

    private static final String ENC_MAGIC = "VWB1";

    static final String ENC_MAGIC_V2 = "VWB2";

    static final String ENC_MAGIC_V3 = "VWB3";

    static boolean magicDikenal(String m) {
        return ENC_MAGIC.equals(m) || ENC_MAGIC_V2.equals(m) || ENC_MAGIC_V3.equals(m);
    }

    static int iterasiKdfBackup() {
        return PinCrypto.pabrikTersedia() ? 100_000 : 30_000;
    }

    static byte[] iterKeByte(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    static int byteKeIter(byte[] b) {
        if (b == null || b.length != 4) {
            return -1;
        }
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
                | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private static final String TG_API = "https://api.telegram.org/bot";
    private static final int KEEP_BACKUPS = 10;

    static final long BATAS_JAM_WAJAR_MS = 1704067200000L;

    private TgBackup() {
    }

    private static final Object KUNCI_NAMA_BACKUP = new Object();

    static String namaBackupUnik(String dasar, java.util.Set<String> dipakai) {
        if (dipakai == null || !dipakai.contains(dasar)) {
            return dasar;
        }
        int titik = dasar.lastIndexOf('.');
        String awal = titik < 0 ? dasar : dasar.substring(0, titik);
        String akhir = titik < 0 ? "" : dasar.substring(titik);
        int i = 1;
        String nama;
        do {
            nama = awal + "-" + (i++) + akhir;
        } while (dipakai.contains(nama));
        return nama;
    }

    static java.util.Set<String> namaFileDi(File dir) {
        java.util.Set<String> ada = new java.util.HashSet<>();
        File[] isi = dir == null ? null : dir.listFiles();
        if (isi != null) {
            for (File f : isi) {
                ada.add(f.getName());
            }
        }
        return ada;
    }

    static boolean dbSiap(File f) {
        return f != null && f.isFile() && f.length() > 0 && isSqliteFile(f);
    }

    public static final long TUNGGU_BOOT_MS = 5L * 60 * 1000;

    static final int REQ_TUNDA_BOOT = 101;

    static final int REQ_HARIAN = 0;

    static final String EXTRA_TUNDA_BOOT = "tunda_boot";

    static final String KEY_ALARM_SECRET = "alarm_secret";

    private static volatile String rahasiaAlarmSesi = null;

    @android.annotation.SuppressLint("ApplySharedPref")
    static String rahasiaAlarm(android.content.Context ctx) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(
                    ServerService.PREFS, android.content.Context.MODE_PRIVATE);
            String pilih = pilihRahasiaAlarm(rahasiaAlarmSesi,
                    amanString(sp, KEY_ALARM_SECRET, ""));
            if (pilih != null) {
                rahasiaAlarmSesi = pilih;
                return pilih;
            }
            byte[] b = new byte[16];
            SECURE_RANDOM.nextBytes(b);
            String buat = Util.heks(b);

            rahasiaAlarmSesi = buat;
            boolean tersimpan = false;
            try {

                tersimpan = sp.edit().putString(KEY_ALARM_SECRET, buat).commit();
            } catch (Exception ignored) {
            }
            if (!tersimpan) {
                ServerService.catatLog("[tg] Rahasia alarm gagal disimpan"
                        + " (storage penuh?) - alarm tetap diverifikasi lewat sesi"
                        + " proses ini; backup terjadwal bisa hilang bila proses mati.");
            }
            return buat;
        } catch (Exception e) {
            return "";
        }
    }

    static String pilihRahasiaAlarm(String sesi, String tersimpan) {
        if (hex32(sesi)) {
            return sesi;
        }
        return hex32(tersimpan) ? tersimpan : null;
    }

    static boolean rahasiaAlarmCocok(android.content.Context ctx, android.content.Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            String got = intent.getStringExtra(AlarmReceiver.EXTRA_RAHASIA);
            String want = rahasiaAlarm(ctx);
            if (got == null || want == null || got.isEmpty() || want.isEmpty()) {
                return false;
            }
            return java.security.MessageDigest.isEqual(
                    got.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    want.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    static boolean bolehBackupSusulanBoot(long lastBackupMs, long sekarangMs) {
        if (!jamStbWajar(sekarangMs)) {
            return false;
        }
        if (lastBackupMs > 0 && sekarangMs < lastBackupMs) {
            return false;
        }
        return sudahGantiHari(lastBackupMs, sekarangMs);
    }

    public static long sisaTungguBootMs(long elapsedMs) {
        long sisa = TUNGGU_BOOT_MS - Math.max(0, elapsedMs);
        return sisa > 0 ? sisa : 0;
    }

    public static boolean tundaBilaBootBaru(Context ctx, String label) {
        return tundaBilaBootBaruPada(ctx, label, SystemClock.elapsedRealtime());
    }

    static boolean tundaBilaBootBaruPada(Context ctx, String label, long elapsedMs) {
        if (sisaTungguBootMs(elapsedMs) <= 0) {
            return false;
        }
        boolean ok = jadwalTundaBoot(ctx);
        ServerService.catatLog("[tg] STB baru hidup - " + label + " ditunda"
                + " sampai sistem stabil"
                + (ok ? " (alarm terpasang)." : " (alarm gagal, coba jadwal berikut)."));
        return true;
    }

    public static boolean jadwalTundaBoot(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return false;
            }
            Intent intent = new Intent(ctx, AlarmReceiver.class)
                    .setAction(AlarmReceiver.ACTION_TUNDA)
                    .putExtra(EXTRA_TUNDA_BOOT, true)
                    .putExtra(AlarmReceiver.EXTRA_RAHASIA, rahasiaAlarm(ctx));
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ_TUNDA_BOOT, intent, flags);
            long kini = SystemClock.elapsedRealtime();
            long trigger = kini + sisaTungguBootMs(kini);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                            throw new SecurityException("exact alarm tak diizinkan");
                        }
                        am.setExactAndAllowWhileIdle(
                                AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
                    } catch (Exception e) {

                        am.setAndAllowWhileIdle(
                                AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
                    }
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
                }
                return true;
            } catch (Exception ignored) {
                return false;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String backupTungguDb(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String dataDir = amanString(sp, ServerService.KEY_DATA_DIR,
                ServerService.dataDirBawaanSegar());
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = ServerService.dataDirBawaanSegar();
        }
        File db = new File(dataDir, "db.sqlite3");
        long batas = SystemClock.elapsedRealtime() + 30_000;
        while (!dbSiap(db)) {
            if (SystemClock.elapsedRealtime() >= batas) {
                throw new IOException("Database belum siap 30 dtk setelah Start"
                        + " - backup otomatis dilewati.");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Backup otomatis dibatalkan.");
            }
        }

        if (tundaBilaBootBaru(ctx, "backup otomatis saat Start")) {
            return "STB baru hidup - backup otomatis saat Start ditunda"
                    + " sampai sistem stabil (alarm terpasang).";
        }

        return backupOtomatis(ctx);
    }

    public interface BackupStartUi {
        void toast(String s);
        void catat(String s);
        void jalankanUi(Runnable r);
    }

    static boolean jamStbWajar(long kiniMs) {
        return kiniMs >= BATAS_JAM_WAJAR_MS;
    }

    static String pesanJamStbSalah(long kiniMs) {
        String terbaca = Util.tanggalStb(kiniMs);
        return "Tanggal & jam STB salah (terbaca " + terbaca + ")."
                + " Sertifikat Telegram belum berlaku menurut jam itu sehingga backup GAGAL."
                + " Aktifkan Tanggal & waktu otomatis di Pengaturan STB"
                + " (butuh internet), atau atur manual ke hari ini, lalu ulangi backup.";
    }

    static String pesanGalatBackup(Exception e) {
        String inti = (e == null || e.getMessage() == null) ? String.valueOf(e) : e.getMessage();
        long kini = System.currentTimeMillis();
        if (!jamStbWajar(kini) || Updater.galatJamSertifikat(e)) {
            return "Backup gagal: " + pesanJamStbSalah(kini);
        }
        return "Backup gagal: " + redaksiDetailGalat(inti) + ". " + Updater.saranKoneksi(e);
    }

    public static void maybeAutoBackup(Context ctx, BackupStartUi ui) {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        if (!amanBoolean(sp, KEY_TG_AUTO, false)) {
            return;
        }
        String token = Util.amanTrim(amanString(sp, KEY_TG_TOKEN, ""));
        String chat = Util.amanTrim(amanString(sp, KEY_TG_CHAT, ""));
        if (token.isEmpty() || chat.isEmpty()) {
            ui.catat("[tg] Backup otomatis saat Start dilewati: token/chat belum diisi.");
            return;
        }
        long last = amanLong(sp, KEY_TG_LAST, 0);
        if (last > 0 && !sudahGantiHari(last, System.currentTimeMillis())) {
            return;
        }

        if (!bolehBackupOtomatis(amanString(sp, KEY_TG_PASS, ""))) {
            ui.catat("[tg] " + pesanTolakPlainOtomatis());
            return;
        }

        if (tugasDataJalan()) {
            ui.catat("[tg] Backup otomatis saat Start dilewati:"
                    + " tugas backup/restore lain sedang berjalan.");
            return;
        }
        ui.catat("[tg] Backup otomatis saat Start akan dijalankan...");
        final Context app = ctx.getApplicationContext();

        Util.jalankanLama(() -> {
            try {
                final String msg = backupTungguDb(app);
                ui.jalankanUi(() -> {
                    ui.toast(msg);
                    ui.catat("[tg] " + msg);
                });
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                ui.catat("[tg] " + pesanGalatBackup(e));
            }
        });
    }

    static boolean bolehBackupOtomatis(String pass) {
        return pass != null && !pass.trim().isEmpty();
    }

    static String pesanTolakPlainOtomatis() {
        return "Backup otomatis dilewati: isi password backup di"
                + " Pengaturan agar backup ke Telegram terenkripsi. Tanpa password,"
                + " database vault terkirim tanpa enkripsi — pakai tombol Backup"
                + " manual bila memang mau tanpa enkripsi.";
    }

    public static String backupOtomatis(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        if (!bolehBackupOtomatis(amanString(sp, KEY_TG_PASS, ""))) {
            throw new IOException(pesanTolakPlainOtomatis());
        }
        return backupNow(ctx);
    }

    public static String backupNow(Context ctx) throws Exception {
        if (!kunciBackup()) {
            throw new IOException("Tugas backup/restore lain sedang berjalan,"
                    + " coba lagi sebentar.");
        }
        try {
            return backupNowIsi(ctx);
        } finally {
            lepasBackup();
        }
    }

    private static String backupNowIsi(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String token = Util.amanTrim(amanString(sp, KEY_TG_TOKEN, ""));
        String chat = Util.amanTrim(amanString(sp, KEY_TG_CHAT, ""));
        if (token.isEmpty() || chat.isEmpty()) {
            throw new IOException("Bot token / chat ID belum diisi.");
        }
        long kini = System.currentTimeMillis();
        if (!jamStbWajar(kini)) {
            throw new IOException(pesanJamStbSalah(kini));
        }
        String dataDir = amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = ServerService.dataDirBawaanSegar();
        }
        File db = new File(dataDir, "db.sqlite3");
        if (!db.exists()) {
            throw new IOException("Database belum ada.");
        }

        String pass = amanString(sp, KEY_TG_PASS, "");
        boolean terenkripsi = pass != null && !pass.trim().isEmpty();

        String cachePath = "";
        try {
            File c = ctx.getCacheDir();
            cachePath = c == null ? "" : c.getAbsolutePath();
        } catch (Exception ignored) {
        }

        long isiDb = panjangBerkas(db)
                + panjangBerkas(new File(dataDir, "db.sqlite3-wal"))
                + panjangBerkas(new File(dataDir, "db.sqlite3-shm"));
        for (String folder : folderCekRuangBackup(dataDir, terenkripsi, cachePath)) {
            boolean folderCache = folder != null && cachePath != null
                    && folder.trim().equals(cachePath.trim());
            long butuh = ruangBackupDibutuhkan(isiDb, folderCache, terenkripsi);
            long free = freeBytes(ctx, folder);
            if (free >= 0 && free < butuh) {
                throw new IOException("Storage kurang untuk backup (butuh "
                        + humanBytes(butuh) + ", sisa " + humanBytes(free)
                        + " di " + folder + ") - kosongkan penyimpanan atau"
                        + " hapus backup lama lalu ulangi.");
            }
        }

        checkpointWal(db);

        String cekDb = cekIntegritasDbUlang(db);
        if (dbSibuk(cekDb)) {
            throw new IOException("Database sibuk (server sedang menulis)"
                    + " - backup dibatalkan, coba lagi sebentar.");
        }
        if (cekDb != null) {
            throw new IOException("Database korup (" + cekDb + ") - backup dibatalkan.");
        }

        File zip = null;

        File snapDir = new File(ctx.getCacheDir(), "vwsnap-" + System.nanoTime());
        sapuSnapshotBasi(ctx.getCacheDir());
        try {
            for (int percobaan = 1; percobaan <= 2; percobaan++) {
                if (zip != null) {
                    try {
                        zip.delete();
                    } catch (Exception ignored) {
                    }
                    zip = null;
                }

                String galatSnap = snapshotDbKonsisten(new File(dataDir), snapDir);
                if (galatSnap != null) {
                    if (percobaan < 2) {
                        continue;
                    }
                    throw new IOException("Backup dibatalkan: " + galatSnap
                            + " - coba lagi sebentar.");
                }
                zip = createBackupZip(ctx, dataDir, snapDir, true, configJson(sp),
                        terenkripsi,

                        terenkripsi ? ctx.getCacheDir() : null);

                String galat = verifikasiZip(zip);
                if (galat != null) {
                    if (percobaan < 2) {
                        continue;
                    }
                    zip.delete();
                    throw new IOException("Backup gagal verifikasi: " + galat);
                }

                File tmpIsi = berkasPlainSementara(ctx.getCacheDir(), "isi-db", ".sqlite3");
                sapuTmpPlainBasi(ctx.getCacheDir(), tmpIsi.getName());
                try {
                    String galatIsi = verifikasiIsiDbZip(zip, tmpIsi);
                    if (galatIsi != null) {
                        if (percobaan < 2) {
                            continue;
                        }
                        zip.delete();
                        throw new IOException("Backup gagal verifikasi isi DB: " + galatIsi);
                    }
                } finally {
                    try {
                        tmpIsi.delete();
                    } catch (Exception ignored) {
                    }
                }
                break;
            }
        } finally {
            hapusPohon(snapDir);
        }
        if (zip == null || !zip.exists()) {
            throw new IOException("Backup gagal dibuat.");
        }

        if (!terenkripsi) {
            cleanupOldBackups(new File(dataDir, "backups"));
        }
        File upload = zip;
        if (pass != null && !pass.trim().isEmpty()) {
            File backupDir = new File(dataDir, "backups");
            File enc = new File(backupDir,
                    namaBackupUnik(zip.getName() + ".enc", namaFileDi(backupDir)));

            File tmpDec = berkasPlainSementara(ctx.getCacheDir(), "roundtrip", ".zip");
            sapuTmpPlainBasi(ctx.getCacheDir(), tmpDec.getName());
            boolean sah = false;
            try {
                encryptFile(zip, enc, pass.trim());

                decryptFile(enc, tmpDec, pass.trim());
                String galat2 = verifikasiZip(tmpDec);
                if (galat2 != null) {
                    throw new IOException("Backup gagal verifikasi setelah enkripsi: "
                            + galat2);
                }
                sah = true;
            } finally {

                zip.delete();
                tmpDec.delete();
                if (!sah) {

                    enc.delete();
                }
            }
            upload = enc;

            cleanupOldBackups(backupDir);
        }
        if (!semuaChatAman(chat)) {
            throw new IOException("Chat ID Telegram tidak valid"
                    + " - periksa pengaturan bot.");
        }
        java.util.List<String> tujuan = daftarChatTujuan(chat);

        String respTerakhir = null;
        java.util.List<String> gagalChat = new java.util.ArrayList<>();
        for (int ci = 0; ci < tujuan.size(); ci++) {
            try {
                respTerakhir = uploadTelegram(ctx, token, tujuan.get(ci), upload);
            } catch (Exception e) {
                String pesan = e.getMessage();
                gagalChat.add(tujuan.get(ci) + ": "
                        + (pesan == null ? e.toString() : pesan));
            }
        }
        if (respTerakhir == null) {
            throw new IOException("Backup gagal ke semua chat ("
                    + gagalChat.size() + "/" + tujuan.size() + "): "
                    + gabungGagalChat(gagalChat));
        }
        String fileId = extractFileId(respTerakhir);
        String peringatanChat = gagalChat.isEmpty() ? ""
                : " (sebagian chat gagal: " + gabungGagalChat(gagalChat)
                        + " - dicoba lagi otomatis backup berikutnya)";

        SharedPreferences.Editor edLast = sp.edit()
                .putString(KEY_TG_LAST_FILE, fileId)
                .putString(KEY_TG_LAST_NAME, upload.getName());
        if (gagalChat.isEmpty()) {
            edLast.putLong(KEY_TG_LAST, System.currentTimeMillis());
        }
        edLast.apply();
        if (!gagalChat.isEmpty()) {
            ServerService.catatLog("[tg] Backup belum tuntas ("
                    + gagalChat.size() + "/" + tujuan.size()
                    + " chat gagal); stempel harian tidak dimajukan agar"
                    + " backup berikutnya mengulang.");
        }
        String peringatan = terenkripsi ? ""
                : " (TANPA enkripsi: kunci privat tak ikut; isi password backup"
                        + " agar terenkripsi penuh)";

        String batas = upload.length() > BATAS_UNDUH_TELEGRAM
                ? " (CATATAN: >20 MB sehingga tak bisa direstore via bot"
                        + " - unduh manual dari chat bila perlu restore)"
                : "";
        return "Backup terkirim ke Telegram \u2713 (" + upload.getName() + ", "
                + upload.length() + " bytes ke " + (tujuan.size() - gagalChat.size())
                + "/" + tujuan.size() + " chat)" + peringatanChat + peringatan + batas;
    }

    static String gabungGagalChat(java.util.List<String> gagal) {
        if (gagal == null || gagal.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gagal.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            String s = gagal.get(i);
            sb.append(s == null ? "" : s);
        }
        return sb.toString();
    }

    static HttpURLConnection bukaPostTelegram(Context ctx, String url, byte[] body,
            String contentType, int connectMs, int readMs) throws Exception {

        final java.net.URL tujuan;
        try {
            tujuan = new java.net.URL(url);
            String host = tujuan.getHost();
            if (!Util.hostTelegramAman(host)) {
                throw new java.io.IOException("Host Telegram tidak aman: " + host);
            }
        } catch (java.io.IOException e) {
            throw e;
        } catch (Exception e) {
            throw new java.io.IOException("URL Telegram tidak valid.");
        }
        HttpURLConnection c = (HttpURLConnection) tujuan.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Content-Type", contentType);
        c.setRequestProperty("Content-Length", String.valueOf(body.length));
        HttpsCompat.apply(c, ctx);
        return c;
    }

    static void tolakRedirectTelegram(HttpURLConnection c) throws java.io.IOException {
        int kode = c.getResponseCode();
        if (kode == 301 || kode == 302 || kode == 303 || kode == 307 || kode == 308) {
            String lok = c.getHeaderField("Location");
            throw new java.io.IOException("Redirect Telegram ditolak: " + lok);
        }
    }

    static HttpURLConnection bukaFileTelegram(Context ctx, String url,
            int connectMs, int readMs) throws Exception {
        String kini = url;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new java.net.URL(kini).openConnection();

            boolean serah = false;
            try {
                c.setConnectTimeout(connectMs);
                c.setReadTimeout(readMs);
                c.setInstanceFollowRedirects(false);
                HttpsCompat.apply(c, ctx);
                int kode = c.getResponseCode();
                if (kode == 301 || kode == 302 || kode == 303 || kode == 307 || kode == 308) {
                    String lok = c.getHeaderField("Location");
                    if (lok == null || lok.isEmpty()
                            || !Util.bolehIkutiRedirectTelegram(kini, lok)) {
                        throw new java.io.IOException("Redirect Telegram tidak aman: " + lok);
                    }
                    String berikut = Util.sambungRedirect(kini, lok);
                    if (berikut == null || berikut.equals(kini)) {
                        throw new java.io.IOException("Redirect Telegram tidak aman (downgrade?): " + lok);
                    }
                    kini = berikut;
                    continue;
                }
                serah = true;
                return c;
            } finally {
                if (!serah) {
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        throw new java.io.IOException("Terlalu banyak redirect Telegram.");
    }

    private static final ExecutorService TG_PENTING_EXEC =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<Runnable>(20),
                    pabrikPesan("vw-tgmsg-penting"),
                    tolakPesan("Pesan penting gugur, antrean penuh.",
                            "Antrean pesan penting penuh, pesan tertua dibuang.",
                            "[tg] Antrean pesan penting penuh, pesan tertua dibuang."));

    public static void sendMessage(Context ctx, String text) {
        kirimPesan(ctx, text, null);
    }

    public static void sendPenting(Context ctx, String text) {
        final Context app;
        try {
            app = ctx.getApplicationContext();
        } catch (Exception e) {
            return;
        }
        final String msg = text == null ? "" : text;
        try {
            TG_PENTING_EXEC.execute(() -> kirimSinkron(app, msg, null));
        } catch (Exception ignored) {
        }
    }

    public static boolean sendMessageKe(Context ctx, String chatId, String text) {
        return kirimPesanKe(ctx, chatId, text, null, false);
    }

    public static boolean sendPentingKe(Context ctx, String chatId, String text) {
        return kirimPesanKe(ctx, chatId, text, null, true);
    }

    public static boolean sendMessageKbKe(Context ctx, String chatId, String text, String markupJson) {
        return kirimPesanKe(ctx, chatId, text, markupJson, false);
    }

    private static boolean kirimPesanKe(Context ctx, String chatId, String text,
                                     String markupJson, boolean penting) {
        final Context app;
        try {
            app = ctx.getApplicationContext();
        } catch (Exception e) {
            return false;
        }
        final String id = Util.amanTrim(chatId);
        if (app == null || id.isEmpty()) {
            return false;
        }
        final String msg = text == null ? "" : text;
        final String markup = markupJson;
        try {
            if (penting) {
                TG_PENTING_EXEC.execute(() -> kirimSinkronKe(app, id, msg, null));
            } else {
                TG_MSG_EXEC.execute(() -> kirimSinkronKe(app, id, msg, markup));
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean editMessageKb(Context ctx, long chatId, int messageId,
                                     String text, String markupJson) {
        final Context app;
        try {
            app = ctx.getApplicationContext();
        } catch (Exception e) {
            return false;
        }
        if (app == null || chatId == 0 || messageId == 0) {
            return false;
        }
        final String msg = text == null ? "" : text;
        final String markup = markupJson;
        try {
            TG_MSG_EXEC.execute(() -> {
                if (!ubahSinkron(app, chatId, messageId, msg, markup)) {

                    kirimSinkronKe(app, String.valueOf(chatId), msg, markup);
                }
            });
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void kirimPesan(Context ctx, String text, String markupJson) {
        final Context app;
        try {
            app = ctx.getApplicationContext();
        } catch (Exception e) {
            return;
        }
        final String msg = text == null ? "" : text;
        final String markup = markupJson;
        try {
            if (markup == null && pesanPenting(msg)) {
                TG_PENTING_EXEC.execute(() -> kirimSinkron(app, msg, null));
            } else {
                TG_MSG_EXEC.execute(() -> kirimSinkron(app, msg, markup));
            }
        } catch (Exception ignored) {
        }
    }

    static boolean pesanPenting(String msg) {
        if (msg == null || msg.isEmpty()) {
            return false;
        }

        return Util.mengandungAbaikanHuruf(msg, "gagal")
                || Util.mengandungAbaikanHuruf(msg, "terkunci")
                || Util.mengandungAbaikanHuruf(msg, "berhenti")
                || Util.mengandungAbaikanHuruf(msg, "crash")
                || Util.mengandungAbaikanHuruf(msg, "restore")
                || Util.mengandungAbaikanHuruf(msg, "korup")
                || Util.mengandungAbaikanHuruf(msg, "darurat");
    }

    static final int BATAS_PESAN_TELEGRAM = 4000;

    static java.util.List<String> pecahPesan(String msg, int batas) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        String s = msg == null ? "" : msg;
        if (batas <= 0) {
            batas = BATAS_PESAN_TELEGRAM;
        }
        int i = 0;
        while (i < s.length()) {
            int akhir = Math.min(i + batas, s.length());
            if (akhir < s.length() && Character.isHighSurrogate(s.charAt(akhir - 1))
                    && Character.isLowSurrogate(s.charAt(akhir))) {
                akhir--;
            }

            if (akhir - i > 1) {
                int minNl = i + batas / 2;
                for (int k = akhir - 1; k > minNl; k--) {
                    if (s.charAt(k) == '\n') {
                        akhir = k + 1;
                        break;
                    }
                }
            }

            int tumbuh = 0;
            while (akhir < s.length() && tumbuh < 16 && ekorGrapheme(s.charAt(akhir))) {
                akhir++;
                tumbuh++;
            }

            if (akhir <= i) {
                akhir = Math.min(i + batas, s.length());
            }
            out.add(s.substring(i, akhir));
            i = akhir;
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    static String bangunParamPesan(String chatEnk, String teksEnk, String mkEnk) {
        String c = chatEnk == null ? "" : chatEnk;
        String t = teksEnk == null ? "" : teksEnk;
        StringBuilder b = new StringBuilder(8 + c.length()
                + 6 + t.length() + (mkEnk == null ? 0 : 14 + mkEnk.length()));
        b.append("chat_id=").append(c).append("&text=").append(t);
        if (mkEnk != null) {
            b.append("&reply_markup=").append(mkEnk);
        }
        return b.toString();
    }

    static String bangunParamEdit(String chatEnk, String pesanEnk, String teksEnk, String mkEnk) {
        String c = chatEnk == null ? "" : chatEnk;
        String p = pesanEnk == null ? "" : pesanEnk;
        String t = teksEnk == null ? "" : teksEnk;
        StringBuilder b = new StringBuilder(18 + c.length() + p.length()
                + 6 + t.length() + (mkEnk == null ? 0 : 14 + mkEnk.length()));
        b.append("chat_id=").append(c).append("&message_id=").append(p)
                .append("&text=").append(t);
        if (mkEnk != null) {
            b.append("&reply_markup=").append(mkEnk);
        }
        return b.toString();
    }

    static boolean ekorGrapheme(char c) {
        if (c == '\u200D' || c == '\uFE0E' || c == '\uFE0F') {
            return true;
        }
        int t = Character.getType(c);
        return t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK
                || t == Character.COMBINING_SPACING_MARK;
    }

    static java.util.List<String> daftarChatTujuan(String chatConfig) {
        return Util.pecahDaftarChat(chatConfig);
    }

    static boolean semuaChatAman(String chatConfig) {
        java.util.List<String> daftar = Util.pecahDaftarChat(chatConfig);
        if (daftar.isEmpty() || daftar.size() > Util.MAKS_CHAT_TUJUAN) {
            return false;
        }
        for (int i = 0; i < daftar.size(); i++) {
            if (!chatIdAman(daftar.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static void kirimSinkron(Context app, String msg, String markup) {
        try {
            SharedPreferences sp = app.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String token = Util.amanTrim(amanString(sp, KEY_TG_TOKEN, ""));
            String chat = Util.amanTrim(amanString(sp, KEY_TG_CHAT, ""));
            java.util.List<String> tujuan = daftarChatTujuan(chat);
            if (token.isEmpty() || tujuan.isEmpty()) {
                return;
            }

            if (tujuan.size() > Util.MAKS_CHAT_TUJUAN) {
                logTgFailure("kirim pesan", -1, "daftar chat melebihi batas");
                return;
            }
            kirimKeDaftar(app, token, tujuan, msg, markup);
        } catch (Exception e) {
            logTgFailure("kirim pesan", -1, String.valueOf(e.getMessage()));
        }
    }

    private static void kirimSinkronKe(Context app, String chatId, String msg, String markup) {
        try {
            java.util.List<String> satu = tujuanBalasan(chatId);
            if (satu == null) {
                logTgFailure("kirim balasan", -1, "chat tujuan tak valid");
                return;
            }
            SharedPreferences sp = app.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String token = Util.amanTrim(amanString(sp, KEY_TG_TOKEN, ""));
            if (token.isEmpty()) {
                return;
            }
            kirimKeDaftar(app, token, satu, msg, markup);
        } catch (Exception e) {
            logTgFailure("kirim balasan", -1, String.valueOf(e.getMessage()));
        }
    }

    static java.util.List<String> tujuanBalasan(String chatId) {
        String id = Util.amanTrim(chatId);
        if (!chatIdAman(id)) {
            return null;
        }
        java.util.List<String> satu = new java.util.ArrayList<String>(1);
        satu.add(id);
        return satu;
    }

    private static void kirimKeDaftar(Context app, String token,
                                      java.util.List<String> tujuan, String msg, String markup)
            throws Exception {

        java.util.List<String> bagian = pecahPesan(msg, BATAS_PESAN_TELEGRAM);

        java.util.List<String> enkChat = new java.util.ArrayList<String>(tujuan.size());
        for (int ci = 0; ci < tujuan.size(); ci++) {
            enkChat.add(URLEncoder.encode(tujuan.get(ci), "UTF-8"));
        }
        java.util.List<String> enkBagian = new java.util.ArrayList<String>(bagian.size());
        for (int bi = 0; bi < bagian.size(); bi++) {
            enkBagian.add(URLEncoder.encode(bagian.get(bi), "UTF-8"));
        }
        String enkMk = (markup == null || markup.isEmpty()) ? null
                : URLEncoder.encode(markup, "UTF-8");
        for (int ci = 0; ci < tujuan.size(); ci++) {
            String chatEnk = enkChat.get(ci);
            for (int bi = 0; bi < bagian.size(); bi++) {
                String mk = (bi == bagian.size() - 1) ? enkMk : null;

                String param = bangunParamPesan(chatEnk, enkBagian.get(bi), mk);
                byte[] body = param.getBytes(StandardCharsets.UTF_8);

                try {
                    kirimSatuPesan(app, token, body, bi, bagian.size());
                } catch (Exception e) {
                    logTgFailure("kirim pesan " + (bi + 1) + "/" + bagian.size(),
                            -1, String.valueOf(e.getMessage()));
                }
            }
        }
    }

    private static void kirimSatuPesan(Context app, String token, byte[] body,
                                       int bi, int total) throws Exception {
        for (int percobaan = 0; percobaan < 2; percobaan++) {
            HttpURLConnection conn = null;
            try {
                conn = bukaPostTelegram(app, TG_API + token + "/sendMessage", body,
                        "application/x-www-form-urlencoded", 15000, 30000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
                tolakRedirectTelegram(conn);
                int code = conn.getResponseCode();
                InputStream mentah = (code >= 200 && code < 300)
                        ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (mentah != null) {
                    try (InputStream is = mentah) {
                        sb.append(bacaResponsBatas(is));
                    }
                }
                if (code == 429 && percobaan == 0) {
                    long jeda = jedaRetryAfterMs(sb.toString());
                    ServerService.catatLog("[tg] Kena limit Telegram - tunggu "
                            + (jeda / 1000) + " dtk lalu coba lagi.");
                    Thread.sleep(jeda);
                    continue;
                }

                if (code != 200 || !TgBot.responsOk(sb.toString())) {
                    logTgFailure("kirim pesan " + (bi + 1) + "/" + total,
                            code, sb.toString());
                }
                return;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }

    static long jedaRetryAfterMs(String body) {
        if (body == null) {
            return 1000L;
        }
        int i = body.indexOf("\"retry_after\"");
        if (i < 0) {
            return 1000L;
        }
        int j = i + 13;
        while (j < body.length() && (body.charAt(j) < '0' || body.charAt(j) > '9')) {
            j++;
        }
        int awal = j;
        while (j < body.length() && body.charAt(j) >= '0' && body.charAt(j) <= '9') {
            j++;
        }
        if (j <= awal) {
            return 1000L;
        }
        long detik;
        try {
            detik = Long.parseLong(body.substring(awal, j));
        } catch (Exception e) {
            return 1000L;
        }
        long ms = detik * 1000L;
        if (ms < 1000L) {
            return 1000L;
        }
        return Math.min(ms, 5000L);
    }

    private static boolean ubahSinkron(Context app, long chatId, int messageId,
                                       String msg, String markup) {
        HttpURLConnection conn = null;
        try {
            SharedPreferences sp = app.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String token = Util.amanTrim(amanString(sp, KEY_TG_TOKEN, ""));
            if (token.isEmpty()) {
                return true;
            }
            java.util.List<String> bagian = pecahPesan(msg, BATAS_PESAN_TELEGRAM);
            if (bagian.size() != 1) {
                return false;
            }
            String chatEnk = URLEncoder.encode(String.valueOf(chatId), "UTF-8");
            String teksEnk = URLEncoder.encode(bagian.get(0), "UTF-8");
            String mkEnk = (markup == null || markup.isEmpty()) ? null
                    : URLEncoder.encode(markup, "UTF-8");
            String param = bangunParamEdit(chatEnk, String.valueOf(messageId),
                    teksEnk, mkEnk);
            byte[] body = param.getBytes(StandardCharsets.UTF_8);
            conn = bukaPostTelegram(app, TG_API + token + "/editMessageText", body,
                    "application/x-www-form-urlencoded", 15000, 30000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            tolakRedirectTelegram(conn);
            int code = conn.getResponseCode();
            InputStream mentah = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String balasan = "";
            if (mentah != null) {
                try (InputStream is = mentah) {
                    balasan = bacaResponsBatas(is);
                }
            }
            if (code == 200 && TgBot.responsOk(balasan)) {
                return true;
            }
            if (code == 400
                    && Util.mengandungAbaikanHuruf(balasan, "message is not modified")) {
                return true;
            }
            logTgFailure("ubah pesan", code, balasan);
            return false;
        } catch (Exception e) {
            logTgFailure("ubah pesan", -1, String.valueOf(e.getMessage()));
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    static boolean checkpointWal(File dbFile) {
        SQLiteDatabase db = null;
        boolean selesai = false;
        try {

            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            android.database.Cursor c = null;
            try {
                c = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE);", null);
                if (c != null && c.moveToFirst()) {

                    selesai = c.getInt(0) == 0;
                }
            } finally {
                if (c != null) {
                    try {
                        c.close();
                    } catch (Exception ignored2) {
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (db != null && db.isOpen()) {
                try {
                    db.close();
                } catch (Exception ignored) {
                }
            }
        }
        return selesai;
    }

    static final String[] BERKAS_DB = {"db.sqlite3", "db.sqlite3-wal", "db.sqlite3-shm"};

    static boolean salinBerkasDb(File dataFolder, File tujuan) {
        if (dataFolder == null || tujuan == null) {
            return false;
        }
        if (!tujuan.isDirectory() && !tujuan.mkdirs()) {
            return false;
        }
        try {
            for (String nama : BERKAS_DB) {
                File asal = new File(dataFolder, nama);
                if (!asal.isFile()) {
                    continue;
                }
                copyFile(asal, new File(tujuan, nama));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String snapshotDbKonsisten(File dataFolder, File tujuan) {
        File db = new File(dataFolder, "db.sqlite3");
        if (!db.isFile()) {
            return "Database belum ada.";
        }
        File wal = new File(dataFolder, "db.sqlite3-wal");
        File shm = new File(dataFolder, "db.sqlite3-shm");
        String galat = "salinan tidak stabil";
        for (int coba = 1; coba <= 3; coba++) {

            checkpointWal(db);
            long mDb = db.lastModified();
            long uDb = db.length();
            long mWal = wal.lastModified();
            long uWal = wal.length();
            long mShm = shm.lastModified();
            long uShm = shm.length();
            if (!salinBerkasDb(dataFolder, tujuan)) {
                galat = "gagal menyalin berkas database";
            } else if (db.lastModified() != mDb || db.length() != uDb
                    || wal.lastModified() != mWal || wal.length() != uWal
                    || shm.lastModified() != mShm || shm.length() != uShm) {

                galat = "database berubah saat disalin (server sedang menulis)";
            } else {
                String rusak = cekIntegritasDbSalinan(new File(tujuan, "db.sqlite3"));
                if (rusak == null) {
                    return null;
                }
                galat = dbSibuk(rusak) ? "database sibuk"
                        : "isi DB korup (" + rusak + ")";
            }
            hapusIsiFolder(tujuan);
            if (coba < 3) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return galat;
                }
            }
        }
        return galat;
    }

    static void sapuSnapshotBasi(File cacheDir) {
        if (cacheDir == null) {
            return;
        }
        File[] isi = cacheDir.listFiles();
        if (isi == null) {
            return;
        }
        for (File f : isi) {
            if (f != null && f.isDirectory() && f.getName().startsWith("vwsnap-")) {
                hapusPohon(f);
            }
        }
    }

    static void hapusIsiFolder(File folder) {
        if (folder == null) {
            return;
        }
        File[] isi = folder.listFiles();
        if (isi == null) {
            return;
        }
        for (File f : isi) {
            hapusPohon(f);
        }
    }

    static void hapusPohon(File f) {
        if (f == null) {
            return;
        }
        if (f.isDirectory()) {
            File[] isi = f.listFiles();
            if (isi != null) {
                for (File anak : isi) {
                    hapusPohon(anak);
                }
            }
        }
        try {
            f.delete();
        } catch (Exception ignored) {
        }
    }

    static String cekIntegritasDb(File dbFile) {
        return cekIntegritasDbMode(dbFile, SQLiteDatabase.OPEN_READONLY);
    }

    static String cekIntegritasDbSalinan(File dbFile) {
        return cekIntegritasDbMode(dbFile, SQLiteDatabase.OPEN_READWRITE);
    }

    private static String cekIntegritasDbMode(File dbFile, int mode) {
        SQLiteDatabase db = null;
        android.database.Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, mode);
            c = db.rawQuery("PRAGMA quick_check;", null);
            if (c != null && c.moveToFirst()) {
                String hasil = c.getString(0);
                if (hasil != null && !hasil.equalsIgnoreCase("ok")) {
                    return hasil;
                }
                return null;
            }
            return "quick_check tanpa hasil";
        } catch (Exception e) {
            String m = e.getMessage();
            String rendah = (e.toString() + " " + String.valueOf(m))
                    .toLowerCase(java.util.Locale.US);
            if (rendah.contains("busy") || rendah.contains("locked")) {
                return "SIBUK: " + (m == null || m.isEmpty() ? e.toString() : m);
            }
            return m == null || m.isEmpty() ? e.toString() : m;
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
            if (db != null && db.isOpen()) {
                try { db.close(); } catch (Exception ignored) {}
            }
        }
    }

    static boolean dbSibuk(String hasilCek) {
        return hasilCek != null && hasilCek.startsWith("SIBUK:");
    }

    static String cekIntegritasDbUlang(File dbFile) {
        String hasil = cekIntegritasDb(dbFile);
        for (int tunggu = 0; dbSibuk(hasil) && tunggu < 2; tunggu++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "SIBUK: cek dihentikan (interrupt)";
            }
            hasil = cekIntegritasDb(dbFile);
        }
        return hasil;
    }

    static String redaksiDetailGalat(String detail) {
        if (detail == null) {
            return "";
        }
        String d = detail.replaceAll("[0-9]{5,}:[A-Za-z0-9_-]{10,}", "<token>");

        d = d.replaceAll("https?://\\S+", "<url>");
        d = d.replaceAll("\\s+", " ").trim();
        return d.length() > 200 ? d.substring(0, 200) + "..." : d;
    }

    static void logTgFailure(String aksi, int code, String detail) {
        try {
            String line = "[tg] Gagal " + aksi + " (HTTP " + code + "): "
                    + redaksiDetailGalat(detail);
            ServerService.catatLog(line);
        } catch (Exception ignored) {
        }
    }

    public static void notifyLowStorage(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String dataDir = amanString(sp, ServerService.KEY_DATA_DIR,
                    ServerService.dataDirBawaanSegar());
            if (dataDir == null || dataDir.trim().isEmpty()) {
                dataDir = ServerService.dataDirBawaanSegar();
            }
            long free = freeBytes(ctx, dataDir);
            if (free < 0) {
                return;
            }
            boolean low = free < LOW_STORAGE_BYTES;
            boolean notified = amanBoolean(sp, KEY_TG_LOW_STORAGE, false);
            if (low && !notified) {
                sp.edit().putBoolean(KEY_TG_LOW_STORAGE, true).apply();
                sendMessage(ctx, "Peringatan storage: sisa " + humanBytes(free)
                        + " di " + dataDir + ". Segera backup & bersihkan!");
            } else if (!low && notified) {
                sp.edit().putBoolean(KEY_TG_LOW_STORAGE, false).apply();
            }
        } catch (Exception ignored) {
        }
    }

    static String verifikasiZip(File zip) {
        if (zip == null || !zip.isFile() || zip.length() == 0) {
            return "file backup kosong/hilang";
        }
        try (ZipFile zf = new ZipFile(zip)) {
            java.util.zip.ZipEntry db = zf.getEntry("db.sqlite3");
            if (db == null) {
                return "tidak berisi db.sqlite3";
            }
            if (zf.size() > Util.BATAS_JUMLAH_ENTRI) {
                return "terlalu banyak entri zip (" + zf.size() + ")";
            }
            if (db.getSize() == 0) {
                return "db.sqlite3 kosong (0 byte)";
            }

            if (db.getSize() >= 0 && db.getSize() < 512) {
                return "db.sqlite3 terpotong (<512 byte)";
            }

            byte[] head = new byte[512];
            int off = 0;
            try (InputStream in = zf.getInputStream(db)) {
                int n;
                while (off < head.length
                        && (n = in.read(head, off, head.length - off)) != -1) {
                    off += n;
                }
            }
            if (off < 512) {
                return "db.sqlite3 terpotong (<512 byte)";
            }
            byte[] want = MAGIC_SQLITE;
            for (int i = 0; i < want.length; i++) {
                if (head[i] != want[i]) {
                    return "db.sqlite3 bukan database SQLite valid";
                }
            }
            return null;
        } catch (Exception e) {
            String msgSebab = e.getMessage();
            String sebab = msgSebab != null ? msgSebab : e.toString();
            return "zip korup/tak terbaca (" + sebab + ")";
        }
    }

    static String verifikasiIsiDbZip(File zip, File tmpDb) {
        if (zip == null || tmpDb == null) {
            return "file backup hilang";
        }

        File dir = new File(tmpDb.getAbsoluteFile().getParentFile(),
                tmpDb.getName() + ".isi");
        hapusPohon(dir);
        try {
            try (ZipFile zf = new ZipFile(zip)) {
                if (zf.size() > Util.BATAS_JUMLAH_ENTRI) {
                    return "terlalu banyak entri zip (" + zf.size() + ")";
                }
                if (zf.getEntry("db.sqlite3") == null) {
                    return "tidak berisi db.sqlite3";
                }
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return "gagal menyiapkan folder verifikasi";
                }
                long total = 0;
                for (String nama : BERKAS_DB) {
                    java.util.zip.ZipEntry entri = zf.getEntry(nama);
                    if (entri == null) {
                        continue;
                    }
                    try (java.io.InputStream in = zf.getInputStream(entri);
                         java.io.FileOutputStream fos =
                                 new java.io.FileOutputStream(new File(dir, nama))) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            total += n;
                            if (total > Util.BATAS_UNZIP_RESTORE) {
                                return "isi DB melebihi batas aman ("
                                        + Util.BATAS_UNZIP_RESTORE + " byte)";
                            }
                            fos.write(buf, 0, n);
                        }
                    }
                }
            } catch (Exception e) {
                String msgSebab = e.getMessage();
                String sebab = msgSebab != null ? msgSebab : e.toString();
                return "zip tak terbaca (" + sebab + ")";
            }

            String rusak = cekIntegritasDbSalinan(new File(dir, "db.sqlite3"));
            if (rusak != null) {
                return "isi DB korup (" + rusak + ")";
            }
            return null;
        } finally {

            hapusPohon(dir);
        }
    }

    static File tlsAktifUntukBackup(File internalTls, File dataTls) {
        if (internalTls != null && new File(internalTls, "ca.pem").isFile()) {
            return internalTls;
        }
        return dataTls;
    }

    private static File tlsAktifUntukBackup(Context ctx, File dataFolder) {
        File dataTls = new File(dataFolder, "tls");
        try {
            return tlsAktifUntukBackup(new File(ctx.getFilesDir(), "tls"), dataTls);
        } catch (Exception ignored) {
            return dataTls;
        }
    }

    static final String[] BERKAS_TLS = {"ca.pem", "ca-key.pem", "cert.pem",
            "key.pem", "ips.txt", "version.txt"};

    static int salinTls(File asal, File tujuan) throws Exception {
        if (asal == null || tujuan == null) {
            return 0;
        }
        if (!tujuan.exists() && !tujuan.mkdirs()) {
            return 0;
        }
        int n = 0;
        for (String nama : BERKAS_TLS) {
            File dari = new File(asal, nama);
            if (dari.isFile()) {
                copyFile(dari, new File(tujuan, nama));
                n++;
            }
        }
        return n;
    }

    static java.util.Map<String, byte[]> potretTls(File tlsDir) {
        java.util.Map<String, byte[]> m = new java.util.LinkedHashMap<>();
        if (tlsDir == null) {
            return m;
        }
        for (String nama : BERKAS_TLS) {
            File f = new File(tlsDir, nama);
            if (!f.isFile()) {
                continue;
            }

            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                m.put(nama, bacaTerbatas(in, BATAS_TLS_POTRET));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    static void kembalikanTls(File tlsDir, java.util.Map<String, byte[]> potret) {
        if (tlsDir == null || potret == null) {
            return;
        }
        for (String nama : BERKAS_TLS) {
            try {
                File f = new File(tlsDir, nama);
                byte[] isi = potret.get(nama);
                if (isi == null) {
                    try {
                        f.delete();
                    } catch (Exception ignored) {
                    }
                } else {
                    java.io.FileOutputStream fos = null;
                    try {
                        fos = new java.io.FileOutputStream(f);
                        fos.write(isi);
                        fos.getFD().sync();
                    } finally {
                        if (fos != null) {
                            try {
                                fos.close();
                            } catch (Exception ignored2) {
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    static void sinkronTlsKeInternal(Context ctx, File dataFolder) {
        try {
            File asal = new File(dataFolder, "tls");
            if (!new File(asal, "ca.pem").isFile()) {
                return;
            }
            salinTls(asal, new File(ctx.getFilesDir(), "tls"));
        } catch (Exception ignored) {
        }
    }

    private static File createBackupZip(Context ctx, String dataDir, File sumberDb,
                                        boolean full, String configJson,
                                        boolean denganKunci, File dirZipSementara)
            throws Exception {
        File dataFolder = new File(dataDir);
        File backupDir = new File(dataFolder, "backups");
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            throw new IOException("Gagal membuat folder backup");
        }

        File dirZip = dirZipSementara != null ? dirZipSementara : backupDir;
        if (!dirZip.exists() && !dirZip.mkdirs()) {
            throw new IOException("Gagal menyiapkan folder backup sementara");
        }
        String ts = backupTimestamp();
        File zip;
        File tmp;
        synchronized (KUNCI_NAMA_BACKUP) {

            java.util.Set<String> ada = namaFileDi(dirZip);
            File z = null;
            File t = null;
            for (int coba = 0; coba < 100 && z == null; coba++) {
                String nama = namaBackupUnik("backup-telegram-" + ts + ".zip", ada);
                ada.add(nama);
                ada.add(nama + ".tmp");
                File candTmp = new File(dirZip, nama + ".tmp");
                try {
                    if (candTmp.createNewFile()) {
                        z = new File(dirZip, nama);
                        t = candTmp;
                    }
                } catch (Exception ignored) {
                }
            }
            if (z == null || t == null) {
                throw new IOException("Gagal menyiapkan file backup unik");
            }
            zip = z;
            tmp = t;
        }

        File asalDb = sumberDb != null ? sumberDb : dataFolder;
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                ZipOutputStream zos = new ZipOutputStream(fos);
                try {
                    byte[] buf = new byte[64 * 1024];
                    for (String name : BERKAS_DB) {
                        addFileEntry(zos, buf, new File(asalDb, name), name);
                    }
                    if (full) {
                        if (configJson != null) {
                            zos.putNextEntry(new ZipEntry("app-config.json"));
                            zos.write(configJson.getBytes(StandardCharsets.UTF_8));
                            zos.closeEntry();
                        }
                        File tlsSrc = tlsAktifUntukBackup(ctx, dataFolder);
                        addFileEntry(zos, buf, new File(tlsSrc, "ca.pem"), "tls/ca.pem");
                        addFileEntry(zos, buf, new File(tlsSrc, "cert.pem"), "tls/cert.pem");
                        if (denganKunci) {
                            addFileEntry(zos, buf, new File(tlsSrc, "ca-key.pem"),
                                    "tls/ca-key.pem");
                            addFileEntry(zos, buf, new File(tlsSrc, "key.pem"), "tls/key.pem");
                        }
                    }
                    zos.finish();
                    zos.flush();
                    fos.getFD().sync();
                } finally {
                    try {
                        zos.close();
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
            ServerService.gantiAtomik(tmp, zip);
        } catch (Exception e) {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
            throw e;
        }

        return zip;
    }

    private static void addFileEntry(ZipOutputStream zos, byte[] buf,
                                     File f, String name) throws Exception {
        if (!f.exists() || f.length() == 0) {
            return;
        }
        zos.putNextEntry(new ZipEntry(name));
        try (FileInputStream fis = new FileInputStream(f)) {
            int n;
            while ((n = fis.read(buf)) != -1) {
                zos.write(buf, 0, n);
            }
        }
        zos.closeEntry();
    }

    static final java.util.Set<String> SECRET_PREF_KEYS = new java.util.HashSet<>(
            java.util.Arrays.asList("admin_token", "tg_token", "tg_chat",
                    "tg_pass", PinGate.KEY_PIN_HASH, PinGate.KEY_GAGAL, PinGate.KEY_KUNCI_SAMPAI,
                    PinGate.KEY_KUNCI_ELAPSED, KEY_ALARM_SECRET, LogActivity.KEY_CLIP_HASH));

    static final java.util.Set<String> TAK_DIEKSPOR_KEYS = new java.util.HashSet<>(
            java.util.Arrays.asList(ServerService.KEY_DATA_DIR, KEY_TG_LAST,
                    TgBot.KEY_TG_OFFSET, TgBot.KEY_TG_WALL_MAKS,
                    TgBot.KEY_TG_HAPUS_OK, TgBot.KEY_TG_HAPUS_WARN,
                    "tg_notified_version", "wv_from_version",
                    "wv_fallback_for", "wv_fallback_at",
                    Updater.KEY_WV_SHA,
                    ServerService.KEY_BIN_DL_GAGAL_AT,
                    ServerService.KEY_BIN_MANUAL_FP,
                    AutoUpdate.KEY_TAWARAN_UPDATE,
                    KEY_BACKUP_TERTUNDA, ServerService.KEY_START_TERTUNDA,
                    LogActivity.KEY_CLIP_KEDALUWARSA));

    public static String configJson(SharedPreferences sp) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "tasirin-vaultwarden-host");
        root.put("version", 1);
        JSONObject prefs = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (SECRET_PREF_KEYS.contains(e.getKey())
                    || TAK_DIEKSPOR_KEYS.contains(e.getKey())
                    || e.getKey().startsWith("wv_notified_")) {
                continue;
            }
            Object v = e.getValue();
            if (v instanceof String) {
                prefs.put(e.getKey(), (String) v);
            } else if (v instanceof Boolean) {
                prefs.put(e.getKey(), (Boolean) v);
            } else if (v instanceof Integer) {
                prefs.put(e.getKey(), (Integer) v);
            } else if (v instanceof Long) {
                prefs.put(e.getKey(), (Long) v);
            } else if (v instanceof Float) {
                prefs.put(e.getKey(), (Float) v);
            }
        }
        root.put("prefs", prefs);
        return root.toString(2);
    }

    public static boolean isFileBackup(String nama) {
        if (nama == null || nama.endsWith(".tmp")) {
            return false;
        }
        return nama.startsWith("backup-telegram-") || nama.startsWith("db-backup-");
    }

    static final long TMP_BASI_MS = 60L * 60L * 1000L;

    static int panjangMinimumEnc(String magic) {
        if (ENC_MAGIC_V3.equals(magic)) {
            return 4 + 4 + 16 + 12 + 16;
        }
        if (ENC_MAGIC.equals(magic) || ENC_MAGIC_V2.equals(magic)) {
            return 4 + 16 + 12 + 16;
        }
        return -1;
    }

    static boolean encTerpotong(File f) {
        if (f == null || !f.getName().endsWith(".enc")) {
            return false;
        }
        String magic = magicEnkripsi(f);
        int min = magic == null ? panjangMinimumEnc(ENC_MAGIC) : panjangMinimumEnc(magic);
        return min > 0 && f.length() < min;
    }

    static final int KEEP_PRE_RESTORE = 2;

    static boolean isPreRestore(String nama) {
        return nama != null && nama.startsWith("db-backup-") && nama.endsWith("-pre.sqlite3");
    }

    public static void cleanupOldBackups(File backupDir) {
        if (backupDir == null) {
            return;
        }
        File[] all = backupDir.listFiles();
        if (all == null) {
            return;
        }

        long sekarang = System.currentTimeMillis();
        for (File f : all) {
            String nama = f.getName();
            if (nama.endsWith(".tmp")) {
                String dasar = nama.substring(0, nama.length() - 4);
                if (isFileBackup(dasar)) {
                    long cap = f.lastModified();
                    boolean basi = sekarang - cap > TMP_BASI_MS;
                    boolean depanStabil = cap - sekarang >= TMP_BASI_MS
                            && capDepanStabil(f);
                    if (basi || depanStabil) {
                        f.delete();
                    }
                }
                continue;
            }
            if (isFileBackup(nama) && encTerpotong(f)) {
                f.delete();
            }
        }

        all = backupDir.listFiles();
        if (all == null) {
            return;
        }

        java.util.List<File> biasa = new java.util.ArrayList<>();
        java.util.List<File> pre = new java.util.ArrayList<>();
        for (File f : all) {
            if (!isFileBackup(f.getName())) {
                continue;
            }
            if (isPreRestore(f.getName())) {
                pre.add(f);
            } else {
                biasa.add(f);
            }
        }

        final java.util.Map<File, Long> waktuUbah = new java.util.HashMap<>();
        for (File f : biasa) {
            waktuUbah.put(f, f.lastModified());
        }
        for (File f : pre) {
            waktuUbah.put(f, f.lastModified());
        }
        Comparator<File> terbaruDulu = new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                Long wa = waktuUbah.get(a);
                Long wb = waktuUbah.get(b);
                return Long.compare(wb == null ? 0 : wb, wa == null ? 0 : wa);
            }
        };
        File[] rutin = biasa.toArray(new File[0]);
        Arrays.sort(rutin, terbaruDulu);
        for (int i = KEEP_BACKUPS; i < rutin.length; i++) {
            rutin[i].delete();
        }
        File[] pengaman = pre.toArray(new File[0]);
        Arrays.sort(pengaman, terbaruDulu);
        for (int i = KEEP_PRE_RESTORE; i < pengaman.length; i++) {
            pengaman[i].delete();
        }
    }

    static File caAktif(File internal, File lama) {
        if (internal != null && internal.isFile()) {
            return internal;
        }
        return lama;
    }

    public static String kirimCa(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String token = Util.amanTrim(amanString(sp, KEY_TG_TOKEN, ""));
        String chat = Util.amanTrim(amanString(sp, KEY_TG_CHAT, ""));
        if (token.isEmpty() || chat.isEmpty()) {
            throw new IOException("Bot token / chat ID belum diisi.");
        }
        String dataDir = amanString(sp, ServerService.KEY_DATA_DIR,
                ServerService.dataDirBawaanSegar());
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = ServerService.dataDirBawaanSegar();
        }
        File ca = caAktif(new File(ctx.getFilesDir(), "tls/ca.pem"),
                new File(dataDir, "tls/ca.pem"));
        if (ca == null || !ca.isFile()) {
            throw new IOException("CA belum ada. Tekan Start dulu agar CA dibuat.");
        }
        File tmp = siapkanFileKirimCa(ca, ctx.getCacheDir(), stempelUnik());
        try {
            if (!semuaChatAman(chat)) {
                throw new IOException("Chat ID Telegram tidak valid"
                        + " - periksa pengaturan bot.");
            }
            java.util.List<String> tujuanCa = daftarChatTujuan(chat);
            for (int ci = 0; ci < tujuanCa.size(); ci++) {
                uploadTelegram(ctx, token, tujuanCa.get(ci), tmp);
            }
        } finally {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
        }
        return "CA terkirim (" + tmp.getName() + "). Di HP tujuan: pakai file"
                + " TERBARU ini lalu install sebagai CA certificate (tanpa private key)."
                + " File ca lama di riwayat chat tak berlaku lagi setelah reset.";
    }

    static File siapkanFileKirimCa(File caAktif, File dirCache, String timestamp)
            throws Exception {
        if (caAktif == null || !caAktif.isFile()) {
            throw new IOException("CA belum ada. Tekan Start dulu agar CA dibuat.");
        }
        if (dirCache == null) {
            throw new IOException("Folder cache tak valid.");
        }
        if (!dirCache.exists() && !dirCache.mkdirs()) {
            throw new IOException("Folder cache tak bisa dibuat.");
        }
        File tujuan = new File(dirCache, TlsCert.namaBackupCa(timestamp));
        copyFile(caAktif, tujuan);
        return tujuan;
    }

    public static String backupCaKeStorage(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String dataDir = amanString(sp, ServerService.KEY_DATA_DIR,
                ServerService.dataDirBawaanSegar());
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = ServerService.dataDirBawaanSegar();
        }
        File ca = caAktif(new File(ctx.getFilesDir(), "tls/ca.pem"),
                new File(dataDir, "tls/ca.pem"));
        if (ca == null || !ca.isFile()) {
            throw new IOException("CA belum ada. Tekan Start dulu agar CA dibuat.");
        }
        File tujuan = TlsCert.salinCaKeStorage(ca, new File(dataDir), stempelUnik());
        return "CA dibackup ke storage: " + tujuan.getAbsolutePath()
                + " (file publik, tanpa private key). Salin ke HP lain lalu install"
                + " sebagai CA certificate.";
    }

    public static String resetSertifikat(Context ctx) throws Exception {

        ServerService.kunciData();
        try {

            if (ServerService.isProcessAlive()) {
                throw new IOException("Server masih berjalan (HTTPS) - Stop dulu,"
                        + " lalu reset agar health check tak salah menilai.");
            }
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String dataDir = amanString(sp, ServerService.KEY_DATA_DIR,
                    ServerService.dataDirBawaanSegar());
            if (dataDir == null || dataDir.trim().isEmpty()) {
                dataDir = ServerService.dataDirBawaanSegar();
            }
            int hapus = TlsCert.resetTls(new File(ctx.getFilesDir(), "tls"))
                    + TlsCert.resetTls(new File(dataDir, "tls"));
            if (hapus == 0) {
                throw new IOException("Sertifikat belum ada, tak ada yang di-reset.");
            }
            return "Sertifikat di-reset (" + hapus + " file dihapus)."
                    + " Tekan Start untuk membuat CA baru, lalu install ulang CA"
                    + " di semua HP (Bagikan CA / /ca / backup storage).";
        } finally {
            ServerService.lepasKunciData();
        }
    }

    static final long BATAS_UPLOAD_TELEGRAM = 48L * 1024 * 1024;

    static boolean chatIdAman(String chat) {
        return Util.chatTunggalValid(chat);
    }

    static String sanitasiNamaFile(String nama) {
        if (nama == null || nama.trim().isEmpty()) {
            return "backup.zip";
        }
        String n = new java.io.File(nama.trim()).getName();
        n = n.replace("\r", "").replace("\n", "").replace("\"", "").replace(";", "");
        n = n.replace("/", "_").replace("\\", "_");
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            return "backup.zip";
        }
        if (n.length() <= 120) {
            return n;
        }
        int potong = n.length() - 120;

        if (Character.isLowSurrogate(n.charAt(potong))
                && Character.isHighSurrogate(n.charAt(potong - 1))) {
            potong++;
        }
        return n.substring(potong);
    }

    private static String uploadTelegram(Context ctx, String token, String chatId, File file)
            throws Exception {
        if (file == null || !file.isFile()) {
            throw new java.io.IOException("File backup tidak ditemukan.");
        }
        if (file.length() > BATAS_UPLOAD_TELEGRAM) {
            throw new java.io.IOException("File backup terlalu besar untuk Telegram"
                    + " (>48 MB). Ambil manual via Settings > Backup DB.");
        }

        if (!chatIdAman(chatId)) {
            throw new java.io.IOException("Chat ID Telegram tidak valid"
                    + " - periksa pengaturan bot.");
        }

        final java.net.URL tujuanKirim;
        try {
            tujuanKirim = new URL(TG_API + token + "/sendDocument");
            String hostKirim = tujuanKirim.getHost();
            if (!Util.hostTelegramAman(hostKirim)) {
                throw new java.io.IOException("Host Telegram tidak aman: " + hostKirim);
            }
        } catch (java.io.IOException e) {
            throw e;
        } catch (Exception e) {
            throw new java.io.IOException("URL Telegram tidak valid.");
        }
        for (int percobaan = 0; percobaan < 2; percobaan++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) tujuanKirim.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(20000);

                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(false);

                conn.setChunkedStreamingMode(0);
                HttpsCompat.apply(conn, ctx);
                String boundary;
                try {
                    byte[] acakB = new byte[8];
                    SECURE_RANDOM.nextBytes(acakB);
                    boundary = "----vw" + Updater.toHex(acakB) + "bound";
                } catch (Exception e) {

                    boundary = "----vw"
                            + java.util.UUID.randomUUID().toString().replace("-", "")
                            + "bound";
                }
                conn.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + boundary);

                try (OutputStream os = conn.getOutputStream()) {
                    tulisAscii(os, "--" + boundary + "\r\n");
                    tulisAscii(os, "Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
                    tulisAscii(os, chatId + "\r\n");
                    tulisAscii(os, "--" + boundary + "\r\n");
                    tulisAscii(os, "Content-Disposition: form-data; name=\"document\"; filename=\""
                            + sanitasiNamaFile(file.getName()) + "\"\r\n");
                    tulisAscii(os, "Content-Type: application/octet-stream\r\n\r\n");
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = fis.read(buf)) != -1) {
                            os.write(buf, 0, n);
                        }
                    }
                    tulisAscii(os, "\r\n--" + boundary + "--\r\n");
                    os.flush();
                }

                tolakRedirectTelegram(conn);
                int code = conn.getResponseCode();
                InputStream mentah = (code >= 200 && code < 300)
                        ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (mentah != null) {
                    try (InputStream is = mentah) {
                        sb.append(bacaResponsBatas(is));
                    }
                }

                if (code == 429 && percobaan == 0) {
                    long jeda = jedaRetryAfterMs(sb.toString());
                    ServerService.catatLog("[tg] Unggah kena limit Telegram - tunggu "
                            + (jeda / 1000) + " dtk lalu coba lagi.");
                    Thread.sleep(jeda);
                    continue;
                }

                if (code != 200 || !TgBot.responsOk(sb.toString())) {

                    throw new IOException("Telegram "
                            + TgBot.ringkasGalatTelegram(sb.toString(), code));
                }
                return sb.toString();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw new IOException("Telegram tetap membatasi unggahan.");
    }

    static String extractFileId(String body) {
        if (body != null) {
            try {
                JSONObject hasil = new JSONObject(body).optJSONObject("result");
                JSONObject dokumen = hasil != null ? hasil.optJSONObject("document") : null;
                String dariJson = dokumen != null ? dokumen.optString("file_id", "") : "";
                if (dariJson != null && !dariJson.isEmpty()) {
                    return dariJson;
                }
            } catch (Exception ignored) {
            }
        }
        return extractFileIdManual(body);
    }

    private static String extractFileIdManual(String body) {
        try {
            int doc = body.indexOf("\"document\":{");
            int start = body.indexOf("\"file_id\":\"", doc >= 0 ? doc : 0);
            if (start < 0) {
                return "";
            }
            start += "\"file_id\":\"".length();
            int end = body.indexOf('"', start);
            if (end < 0) {
                return "";
            }
            return body.substring(start, end);
        } catch (Exception e) {
            return "";
        }
    }

    public static String downloadLastBackup(Context ctx, File dest) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String token = Util.amanTrim(amanString(sp, KEY_TG_TOKEN, ""));
        String fileId = amanString(sp, KEY_TG_LAST_FILE, "");
        String name = amanString(sp, KEY_TG_LAST_NAME, "backup.zip");
        if (token.isEmpty() || fileId.isEmpty()) {
            throw new IOException("Belum ada backup terkirim dari app ini.");
        }
        String path = getFilePath(ctx, token, fileId);
        HttpURLConnection conn = null;
        try {
            conn = bukaFileTelegram(ctx,
                    "https://api.telegram.org/file/bot" + token + "/" + path,
                    20000, 120000);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("Unduh backup gagal (HTTP " + code + ")");
            }
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;

                    if (total > Util.BATAS_UNZIP_RESTORE) {
                        throw new IOException("Backup terlalu besar (>200 MB)"
                                + " - unduhan dibatalkan.");
                    }
                    fos.write(buf, 0, n);
                }
            } catch (Exception e) {
                try {
                    dest.delete();
                } catch (Exception ignored) {
                }
                throw e;
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return name;
    }

    private static String getFilePath(Context ctx, String token, String fileId) throws Exception {
        HttpURLConnection conn = null;
        StringBuilder sb = new StringBuilder();
        try {

            byte[] body = ("file_id=" + URLEncoder.encode(fileId, "UTF-8"))
                    .getBytes(StandardCharsets.UTF_8);
            conn = bukaPostTelegram(ctx,
                    "https://api.telegram.org/bot" + token + "/getFile", body,
                    "application/x-www-form-urlencoded", 15000, 15000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            tolakRedirectTelegram(conn);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("getFile gagal (HTTP " + code + ")");
            }
            try (InputStream is = conn.getInputStream()) {
                sb.append(bacaResponsBatas(is));
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        String hasil = parseFilePathTelegram(sb.toString());
        if (hasil == null || hasil.isEmpty()) {
            throw new IOException("file_path tidak ditemukan");
        }
        if (!filePathTelegramAman(hasil)) {
            throw new IOException("file_path tidak aman - unduhan dibatalkan.");
        }

        long ukuran = fileSizeDariRespons(sb.toString());
        if (!bolehUnduhUlangTelegram(ukuran)) {
            throw new IOException("Backup terlalu besar (" + humanBytes(ukuran)
                    + " > 20 MB) - Bot API Telegram tak bisa mengunduhnya kembali."
                    + " Unduh file backup manual dari chat Telegram, lalu restore"
                    + " lokal via Settings.");
        }
        if (ukuran > 0) {
            try {
                java.io.File cache = ctx.getCacheDir();
                if (cache != null) {
                    long sisa = freeBytes(ctx, cache.getAbsolutePath());
                    if (sisa >= 0 && ukuran > sisa) {
                        throw new IOException("Storage kurang (butuh "
                                + humanBytes(ukuran) + ", sisa " + humanBytes(sisa)
                                + ") - unduhan dibatalkan.");
                    }
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception ignored) {
            }
        }
        return hasil;
    }

    static boolean filePathTelegramAman(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
            return false;
        }

        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
            if (c == '?' || c == '#' || c == '%' || c == ':' || c == '@'
                    || c == '&' || c == '=' || c == '+' || c == ' ') {
                return false;
            }
        }
        return true;
    }

    static String parseFilePathTelegram(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        try {
            JSONObject resp = new JSONObject(body);
            if (!resp.optBoolean("ok", false)) {
                return "";
            }
            JSONObject res = resp.optJSONObject("result");
            if (res == null) {
                return "";
            }
            String path = res.optString("file_path", "");
            return path == null ? "" : path;
        } catch (Exception e) {
            return "";
        }
    }

    static final long BATAS_UNDUH_TELEGRAM = 20L * 1024 * 1024;

    static boolean bolehUnduhUlangTelegram(long ukuran) {
        return ukuran < 0 || ukuran <= BATAS_UNDUH_TELEGRAM;
    }

    static long fileSizeDariRespons(String body) {
        if (body == null) {
            return -1;
        }
        String kunci = "\"file_size\"";
        int k = body.indexOf(kunci);
        if (k < 0) {
            return -1;
        }
        int n = body.length();
        int i = k + kunci.length();
        while (i < n && (body.charAt(i) == ' ' || body.charAt(i) == '\t'
                || body.charAt(i) == '\n' || body.charAt(i) == '\r')) {
            i++;
        }
        if (i >= n || body.charAt(i) != ':') {
            return -1;
        }
        i++;
        while (i < n && (body.charAt(i) == ' ' || body.charAt(i) == '\t'
                || body.charAt(i) == '\n' || body.charAt(i) == '\r')) {
            i++;
        }
        int j = i;
        while (j < n && body.charAt(j) >= '0' && body.charAt(j) <= '9') {
            j++;
        }
        if (j == i || j - i > 19) {
            return -1;
        }
        try {
            return Long.parseLong(body.substring(i, j));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean isEncrypted(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] magic = new byte[4];
            int off = 0;
            while (off < magic.length) {
                int n = fis.read(magic, off, magic.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            if (off != 4) {
                return false;
            }
            String magicStr = new String(magic, StandardCharsets.US_ASCII);
            return magicDikenal(magicStr);
        } catch (Exception e) {
            return false;
        }
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static void encryptFile(File in, File out, String pass) throws Exception {
        boolean sudahAda = out != null && out.exists();
        try {
            tulisTerenkripsi(in, out, pass);
        } catch (Exception e) {

            if (!sudahAda) {
                try {
                    out.delete();
                } catch (Exception ignored) {
                }
            }
            throw e;
        }
    }

    private static void tulisTerenkripsi(File in, File out, String pass) throws Exception {
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        SECURE_RANDOM.nextBytes(salt);
        SECURE_RANDOM.nextBytes(iv);
        int iter = iterasiKdfBackup();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(pass, salt, true, iter), "AES"),
                new GCMParameterSpec(128, iv));

        File tmp = new File(out.getAbsolutePath() + ".tmp");
        try {
            try (FileInputStream fis = new FileInputStream(in);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(ENC_MAGIC_V3.getBytes(StandardCharsets.US_ASCII));
                fos.write(iterKeByte(iter));
                fos.write(salt);
                fos.write(iv);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    byte[] o = c.update(buf, 0, n);
                    if (o != null && o.length > 0) {
                        fos.write(o);
                    }
                }
                fos.write(c.doFinal());
                fos.flush();

                try {
                    fos.getFD().sync();
                } catch (Exception ignored) {
                }
            }
            ServerService.gantiAtomik(tmp, out);
        } finally {

            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    public static void decryptFile(File in, File out, String pass) throws Exception {
        boolean sudahAda = out != null && out.exists();
        try {
            decryptToFile(in, out, pass);
        } catch (Exception e) {

            if (!sudahAda) {
                try {
                    out.delete();
                } catch (Exception ignored) {
                }
            }
            throw e;
        }
    }

    static String magicEnkripsi(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] magic = new byte[4];
            readFully(fis, magic);
            String m = new String(magic, StandardCharsets.US_ASCII);
            if (magicDikenal(m)) {
                return m;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void decryptToFile(File in, File out, String pass) throws Exception {

        String magicAwal = magicEnkripsi(in);
        boolean baru = magicDikenal(magicAwal) && !ENC_MAGIC.equals(magicAwal);
        File induk = out.getParentFile();
        if (induk == null) {
            throw new java.io.IOException("Path keluaran tidak valid.");
        }

        String unik = Long.toHexString(System.nanoTime())
                + Long.toHexString(SECURE_RANDOM.nextLong());
        File tmp = new File(induk, PREFIX_DEC_TMP + unik + "-" + out.getName() + ".tmp");
        sapuTmpPlainBasi(induk, tmp.getName());
        try {
            tmp.delete();
        } catch (Exception ignored) {
        }
        try {
            try {
                decryptWithKdf(in, tmp, pass, true);
            } catch (javax.crypto.BadPaddingException e) {
                if (baru) {
                    throw e;
                }

                try {
                    tmp.delete();
                } catch (Exception ignored) {
                }
                decryptWithKdf(in, tmp, pass, false);
            }
            fsyncFile(tmp);
            ServerService.gantiAtomik(tmp, out);
        } finally {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
        }
    }

    static final String PREFIX_DEC_TMP = "vwtg-dec-";

    static final String PREFIX_PLAIN_TMP = "vwtg-plain-";

    static final long UMUR_DEC_TMP_MS = 10 * 60 * 1000L;

    static File berkasPlainSementara(File dir, String label, String akhiran) {
        String unik = Long.toHexString(System.nanoTime())
                + Long.toHexString(SECURE_RANDOM.nextLong());
        return new File(dir, PREFIX_PLAIN_TMP + label + "-" + unik + akhiran);
    }

    private static final java.util.Map<String, Long> CAP_DEPAN_TERLIHAT =
            new java.util.HashMap<String, Long>();

    private static boolean capDepanStabil(File f) {
        String kunci = f.getAbsolutePath();
        long cap = f.lastModified();
        synchronized (CAP_DEPAN_TERLIHAT) {
            Long terlihat = CAP_DEPAN_TERLIHAT.get(kunci);
            if (terlihat != null && terlihat.longValue() == cap) {
                CAP_DEPAN_TERLIHAT.remove(kunci);
                return true;
            }
            if (CAP_DEPAN_TERLIHAT.size() >= 128
                    && !CAP_DEPAN_TERLIHAT.containsKey(kunci)) {
                java.util.Iterator<String> it =
                        CAP_DEPAN_TERLIHAT.keySet().iterator();
                while (it.hasNext()) {
                    if (!new File(it.next()).exists()) {
                        it.remove();
                    }
                }
                if (CAP_DEPAN_TERLIHAT.size() >= 128) {
                    CAP_DEPAN_TERLIHAT.clear();
                }
            }
            CAP_DEPAN_TERLIHAT.put(kunci, Long.valueOf(cap));
            return false;
        }
    }

    static void sapuTmpPlainBasi(File dir, String... kecualikan) {
        if (dir == null) {
            return;
        }
        File[] isi;
        try {
            isi = dir.listFiles();
        } catch (Exception ignored) {
            return;
        }
        if (isi == null) {
            return;
        }
        long kini = System.currentTimeMillis();
        for (File f : isi) {
            try {
                String n = f.getName();
                boolean calon = (n.startsWith(PREFIX_DEC_TMP) && n.endsWith(".tmp"))
                        || n.startsWith(PREFIX_PLAIN_TMP);
                if (!calon) {
                    continue;
                }
                if (kecualikan != null && java.util.Arrays.asList(kecualikan).contains(n)) {
                    continue;
                }
                long umur = kini - f.lastModified();
                if (umur < 0) {
                    umur = 0;
                }
                boolean stempelDiMasaDepan = f.lastModified() - kini >= UMUR_DEC_TMP_MS;
                if (umur >= UMUR_DEC_TMP_MS && f.isFile()) {
                    f.delete();
                } else if (stempelDiMasaDepan && f.isFile() && capDepanStabil(f)) {
                    f.delete();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void fsyncFile(File f) {
        java.io.FileInputStream fis = null;
        try {
            fis = new java.io.FileInputStream(f);
            fis.getFD().sync();
        } catch (Exception ignored) {
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception ignored2) {
                }
            }
        }
    }

    private static void decryptWithKdf(File in, File out, String pass, boolean sha256)
            throws Exception {
        try {
            dekripsiSekali(in, out, pass, sha256, false);
        } catch (javax.crypto.BadPaddingException e) {
            if (!sha256) {
                throw e;
            }
            dekripsiSekali(in, out, pass, sha256, true);
        }
    }

    private static void dekripsiSekali(File in, File out, String pass, boolean sha256,
            boolean cadangan)
            throws Exception {
        try (FileInputStream fis = new FileInputStream(in);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] magic = new byte[4];

            try {
                readFully(fis, magic);
            } catch (IOException e) {
                throw new IOException("File bukan backup terenkripsi");
            }
            String magicStr = new String(magic, StandardCharsets.US_ASCII);
            if (!magicDikenal(magicStr)) {
                throw new IOException("File bukan backup terenkripsi");
            }
            int iter = 100000;
            if (ENC_MAGIC_V3.equals(magicStr)) {

                byte[] iterB = new byte[4];
                readFully(fis, iterB);
                iter = byteKeIter(iterB);
                if (iter < PinCrypto.ITERASI_MINIMAL || iter > PinCrypto.MAKS_VERIFIKASI) {
                    throw new IOException("Iterasi KDF backup tak valid: " + iter);
                }
            }
            byte[] salt = new byte[16];
            byte[] iv = new byte[12];
            readFully(fis, salt);
            readFully(fis, iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(deriveKey(pass, salt, sha256, iter, cadangan), "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) != -1) {
                byte[] o = c.update(buf, 0, n);
                if (o != null && o.length > 0) {
                    fos.write(o);
                }
            }
            fos.write(c.doFinal());
        }
    }

    private static byte[] deriveKey(String pass, byte[] salt, boolean sha256, int iter)
            throws Exception {
        return deriveKey(pass, salt, sha256, iter, false);
    }

    private static byte[] deriveKey(String pass, byte[] salt, boolean sha256, int iter,
            boolean cadangan)
            throws Exception {
        if (cadangan) {
            if (!sha256) {
                throw new IllegalStateException("PBKDF2 cadangan hanya untuk SHA-256");
            }
            return PinCrypto.deriveCadangan(pass, salt, iter);
        }

        char[] chars = pass == null ? new char[0] : pass.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iter, 256);
        try {
            try {
                SecretKeyFactory f = SecretKeyFactory.getInstance(
                        sha256 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1");
                return f.generateSecret(spec).getEncoded();
            } catch (Exception e) {

                if (!sha256) {
                    throw e;
                }
                return PinCrypto.pbkdf2Manual(pass, salt, iter);
            }
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(chars, '\0');
        }
    }

    private static void readFully(FileInputStream fis, byte[] out) throws IOException {
        int off = 0;
        while (off < out.length) {
            int n = fis.read(out, off, out.length - off);
            if (n <= 0) {
                throw new IOException("File terenkripsi rusak");
            }
            off += n;
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean TUGAS_DATA_JALAN =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static boolean kunciBackup() {
        return TUGAS_DATA_JALAN.compareAndSet(false, true);
    }

    public static void lepasBackup() {
        TUGAS_DATA_JALAN.set(false);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean RESTORE_JALAN =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static boolean kunciRestore() {
        boolean dapat = TUGAS_DATA_JALAN.compareAndSet(false, true);
        if (dapat) {
            RESTORE_JALAN.set(true);
        }
        return dapat;
    }

    public static void lepasRestore() {
        RESTORE_JALAN.set(false);
        TUGAS_DATA_JALAN.set(false);
    }

    public static boolean tugasDataJalan() {
        return TUGAS_DATA_JALAN.get();
    }

    public static boolean restoreJalan() {
        return RESTORE_JALAN.get();
    }

    static final class PindaiZip {
        final String config;
        final long ukuran;

        PindaiZip(String config, long ukuran) {
            this.config = config;
            this.ukuran = ukuran;
        }
    }

    static PindaiZip pindaiZipRestore(File zip) {
        if (zip == null || !zip.isFile()) {
            return new PindaiZip(null, -1);
        }
        String config = null;
        long total = 0;
        try (ZipFile zf = new ZipFile(zip)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                if (config == null
                        && "app-config.json".equals(normalisasiEntriZip(e.getName()))) {
                    try (java.io.InputStream in = zf.getInputStream(e)) {
                        config = new String(readAllBytes(in), StandardCharsets.UTF_8);
                    } catch (Exception ignored) {
                    }
                }
                long ukuran = e.getSize();
                if (ukuran < 0) {
                    continue;
                }
                total += ukuran;
                if (total > Util.BATAS_UNZIP_RESTORE) {

                    return new PindaiZip(config, Util.BATAS_UNZIP_RESTORE + 1);
                }
            }
        } catch (Exception e) {
            return new PindaiZip(config, -1);
        }
        return new PindaiZip(config, total);
    }

    public static String restoreFromZip(Context ctx, File zip) throws Exception {

        ServerService.kunciData();
        try {
            return restoreFromZipIsi(ctx, zip);
        } finally {
            ServerService.lepasKunciData();
        }
    }

    private static String restoreFromZipIsi(Context ctx, File zip) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String curDir = amanString(sp, ServerService.KEY_DATA_DIR,
                ServerService.dataDirBawaanSegar());
        if (curDir == null || curDir.trim().isEmpty()
                || !ServerService.dataDirAman(curDir)
                || !ServerService.dataDirKanonisAman(curDir)) {
            curDir = ServerService.dataDirBawaanSegar();
        }

        PindaiZip pindai = pindaiZipRestore(zip);
        JSONObject cfg = null;
        if (pindai.config != null) {
            try {
                cfg = new JSONObject(pindai.config);
            } catch (Exception ignored) {
            }
        }
        String dataDir = curDir;
        File dataFolder = new File(dataDir);
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File dbFile = new File(dataFolder, "db.sqlite3");

        long isiZip = pindai.ukuran;
        if (isiZip > Util.BATAS_UNZIP_RESTORE) {
            throw new IOException("Isi zip melebihi batas aman restore ("
                    + Util.BATAS_UNZIP_RESTORE + " byte) - restore dibatalkan.");
        }
        if (isiZip >= 0) {
            long butuh = isiZip + 5L * 1024 * 1024;
            long sisa = freeBytes(ctx, dataDir);
            if (sisa >= 0 && butuh > sisa) {
                throw new IOException("Storage kurang untuk restore (butuh "
                        + humanBytes(butuh) + ", sisa " + humanBytes(sisa)
                        + ") - kosongkan penyimpanan lalu ulangi.");
            }
        }

        if (!ServerService.stopAndWait(ctx, ServerService.ANGGARAN_STOP_MIN_MS)) {
            throw new IOException("Server gagal berhenti - restore dibatalkan"
                    + " agar database tidak korup. Stop manual lalu ulangi.");
        }
        if (ServerService.isProcessAlive()) {
            throw new IOException("Server masih berjalan - restore dibatalkan"
                    + " agar database tidak korup. Stop manual lalu ulangi.");
        }

        if (!ServerService.bereskanServerYatim()) {
            throw new IOException("Proses server lama masih berjalan - restore"
                    + " dibatalkan agar database tidak korup. Stop manual lalu ulangi.");
        }

        hapusStagingRestore(dataFolder);

        File preBackup = null;
        if (dbFile.exists()) {
            File backupDir = new File(dataFolder, "backups");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }
            checkpointWal(dbFile);
            String ts = stempelUnik() + "-pre";
            preBackup = new File(backupDir, "db-backup-" + ts + ".sqlite3");
            copyFile(dbFile, preBackup);
            cleanupOldBackups(backupDir);
            hapusWalShm(dataFolder);
        }

        java.util.Map<String, byte[]> tlsAsal = potretTls(new File(dataFolder, "tls"));
        byte[] buf = new byte[64 * 1024];
        long totalUnzip = 0;
        int jumlahEntri = 0;
        String basePath = dataFolder.getCanonicalPath();
        String awalanAman = basePath + File.separator;

        boolean adaDb = false;
        java.util.List<String> tlsTerStaging = new java.util.ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                jumlahEntri++;
                totalUnzip = Util.tambahUkuranUnzip(totalUnzip, 0,
                        Util.BATAS_UNZIP_RESTORE, jumlahEntri,
                        Util.BATAS_JUMLAH_ENTRI);

                String nama = normalisasiEntriZip(entry.getName());
                if (nama == null || "app-config.json".equals(nama)) {
                    try {
                        zis.closeEntry();
                    } catch (Exception ignored) {
                    }
                    continue;
                }
                if ("tls".equals(nama) && !entry.isDirectory()) {

                    try {
                        zis.closeEntry();
                    } catch (Exception ignored) {
                    }
                    continue;
                }

                if (!bolehTulisRestore(nama)) {
                    try {
                        zis.closeEntry();
                    } catch (Exception ignored) {
                    }
                    continue;
                }

                String staging = namaStagingRestore(nama);
                if (staging != null && entry.isDirectory()) {

                    try {
                        zis.closeEntry();
                    } catch (Exception ignored) {
                    }
                    continue;
                }
                if ("db.sqlite3".equals(nama)) {
                    adaDb = true;
                }
                String stagingTls = namaStagingTls(nama);
                String namaTulis = staging != null ? staging
                        : (stagingTls != null ? stagingTls : nama);
                File outFile = new File(dataFolder, namaTulis);
                String kanon = outFile.getCanonicalPath();
                if (!kanon.equals(basePath) && !kanon.startsWith(awalanAman)) {
                    try {
                        zis.closeEntry();
                    } catch (Exception ignored) {
                    }
                    continue;
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            totalUnzip = Util.tambahUkuranUnzip(totalUnzip, n,
                                    Util.BATAS_UNZIP_RESTORE, jumlahEntri,
                                    Util.BATAS_JUMLAH_ENTRI);
                            fos.write(buf, 0, n);
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
                        tlsTerStaging.add(nama);
                    }
                }
                zis.closeEntry();
            }
        } catch (Exception eEkstrak) {

            try {
                hapusStagingRestore(dataFolder);
                hapusStagingTls(dataFolder);
            } catch (Exception eBersih) {
                eEkstrak.addSuppressed(eBersih);
            }
            kembalikanTls(new File(dataFolder, "tls"), tlsAsal);
            throw eEkstrak;
        } finally {
            try {
                if (Util.bolehHapusFile(ctx.getCacheDir(), ctx.getFilesDir(), zip)) {
                    zip.delete();
                }
            } catch (Exception ignored) {
            }
        }

        if (!adaDb) {
            hapusStagingRestore(dataFolder);
            hapusStagingTls(dataFolder);
            kembalikanTls(new File(dataFolder, "tls"), tlsAsal);
            throw new IOException("Backup tidak berisi db.sqlite3.");
        }

        String galatDb;
        try {
            galatDb = pasangDbRestoreAtomik(dataFolder);
        } catch (Exception ePasang) {
            hapusStagingRestore(dataFolder);
            hapusStagingTls(dataFolder);
            kembalikanTls(new File(dataFolder, "tls"), tlsAsal);
            throw ePasang;
        }
        if (galatDb != null) {
            hapusStagingRestore(dataFolder);
            hapusStagingTls(dataFolder);
            kembalikanTls(new File(dataFolder, "tls"), tlsAsal);
            throw new IOException("Backup rusak (" + galatDb + ")"
                    + " - database lama dipertahankan.");
        }

        String peringatanTls = "";
        if (!tlsTerStaging.isEmpty() && !tlsRestoreLengkap(tlsTerStaging)) {
            hapusStagingTls(dataFolder);
            peringatanTls = " Sertifikat dari backup tidak dipasang karena"
                    + " kunci privatnya tidak ikut - TLS perangkat ini"
                    + " dipertahankan.";
        } else {
            try {
                pasangTlsRestore(dataFolder, tlsTerStaging);
            } catch (Exception eTls) {
                kembalikanTls(new File(dataFolder, "tls"), tlsAsal);
                throw eTls;
            }
        }
        sinkronTlsKeInternal(ctx, dataFolder);
        boolean lengkap = false;
        String peringatan = "";
        if (cfg != null) {
            try {
                applyPrefsFromJson(ctx, cfg.optJSONObject("prefs"));
                lengkap = true;
            } catch (Exception eSet) {

                peringatan = " Peringatan: pengaturan dari backup gagal diterapkan ("
                        + eSet.getMessage() + ").";
            }
        }
        SharedPreferences fresh = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        TgBackup.schedule(ctx, amanBoolean(fresh, KEY_TG_AUTO, false));
        TgBot.schedule(ctx);
        return "Database dipulihkan"
                + (lengkap ? " (lengkap, termasuk pengaturan)" : "")
                + ". Ukuran DB: " + dbFile.length() + " bytes."
                + (preBackup != null
                        ? " DB lama disimpan sebagai " + preBackup.getName()
                                + " di folder backups."
                        : "")
                + peringatanTls + peringatan;
    }

    static final String[] ENTRI_TLS_RESTORE = {"tls/ca.pem", "tls/ca-key.pem",
            "tls/cert.pem", "tls/key.pem"};

    static String namaStagingTls(String nama) {
        if (nama != null && nama.startsWith("tls/")) {
            return nama + ".restore-tmp";
        }
        return null;
    }

    static void hapusStagingTls(File dataFolder) {
        if (dataFolder == null) {
            return;
        }
        for (String nama : ENTRI_TLS_RESTORE) {
            new File(dataFolder, namaStagingTls(nama)).delete();
        }
    }

    static boolean tlsRestoreLengkap(java.util.List<String> daftar) {
        return daftar != null && daftar.contains("tls/ca-key.pem")
                && daftar.contains("tls/key.pem");
    }

    static void pasangTlsRestore(File dataFolder, java.util.List<String> daftar)
            throws Exception {
        for (String nama : daftar) {
            File staging = new File(dataFolder, namaStagingTls(nama));
            if (staging.isFile()) {
                ServerService.gantiAtomik(staging, new File(dataFolder, nama));
            }
        }
    }

    static String namaStagingRestore(String nama) {
        if ("db.sqlite3".equals(nama)) {
            return "db.sqlite3.restore-tmp";
        }
        if ("db.sqlite3-wal".equals(nama)) {
            return "db.sqlite3.restore-tmp-wal";
        }
        if ("db.sqlite3-shm".equals(nama)) {
            return "db.sqlite3.restore-tmp-shm";
        }
        return null;
    }

    static void hapusStagingRestore(File dataFolder) {
        if (dataFolder == null) {
            return;
        }
        String[] asal = {"db.sqlite3", "db.sqlite3-wal", "db.sqlite3-shm"};
        for (String nama : asal) {
            String staging = namaStagingRestore(nama);
            if (staging != null) {
                new File(dataFolder, staging).delete();
            }
        }
    }

    static String pasangDbRestoreAtomik(File dataFolder) throws Exception {
        File staging = new File(dataFolder, namaStagingRestore("db.sqlite3"));
        if (!staging.isFile()) {
            return "tidak berisi db.sqlite3";
        }

        checkpointWal(staging);
        sinkronBerkas(staging);
        if (!isSqliteFile(staging)) {
            return "bukan SQLite";
        }
        String rusak = cekIntegritasDb(staging);
        if (rusak != null) {
            return "DB korup: " + rusak;
        }

        ServerService.gantiAtomik(staging, new File(dataFolder, "db.sqlite3"));
        hapusWalShm(dataFolder);

        hapusStagingRestore(dataFolder);
        return null;
    }

    static void pasangDbDariSalinan(File dataFolder, File sumber) throws Exception {
        File staging = new File(dataFolder, namaStagingRestore("db.sqlite3"));
        hapusStagingRestore(dataFolder);
        hapusWalShm(dataFolder);
        copyFile(sumber, staging);
        sinkronBerkas(staging);
        if (!staging.renameTo(new File(dataFolder, "db.sqlite3"))) {
            throw new IOException("Gagal memasang database pengaman");
        }
    }

    private static void sinkronBerkas(File f) {
        try (FileOutputStream fos = new FileOutputStream(f, true)) {
            fos.getFD().sync();
        } catch (Exception ignored) {
        }
    }

    static void pulihkanDbLama(File dataFolder, File dbFile, File preBackup)
            throws Exception {
        if (preBackup != null && preBackup.exists()) {

            pasangDbDariSalinan(dataFolder, preBackup);
        } else {
            hapusStagingRestore(dataFolder);
            hapusWalShm(dataFolder);
            dbFile.delete();
        }
    }

    static boolean pinAktif(boolean pinOn, String pinHash) {
        return pinOn && pinHash != null && !pinHash.isEmpty();
    }

    static boolean pinOnHasilRestore(boolean pinPerangkat, String hashPerangkat) {
        return pinAktif(pinPerangkat, hashPerangkat);
    }

    static final java.util.Set<String> KUNCI_STRING = new java.util.HashSet<>(
            java.util.Arrays.asList(ServerService.KEY_DATA_DIR, ServerService.KEY_PORT,
                    ServerService.KEY_UPDATE_VERSION, ServerService.KEY_ADMIN_TOKEN,
                    ServerService.KEY_BIN_SHA, ServerService.KEY_BIN_PATCH,
                    ServerService.KEY_BIN_PILIH, ServerService.KEY_WV_PILIH,
                    KEY_TG_TOKEN, KEY_TG_CHAT, KEY_TG_PASS,
                    TgBot.KEY_TG_MENU_FP,
                    KEY_TG_LAST_FILE, KEY_TG_LAST_NAME,
                    "tg_notified_version", "wv_from_version",
                    "wv_fallback_for",
                    PinGate.KEY_PIN_HASH, "clip_hash"));

    static final java.util.Set<String> BOLEH_IMPOR = new java.util.HashSet<>(
            java.util.Arrays.asList(ServerService.KEY_PORT,
                    ServerService.KEY_AUTO_START, ServerService.KEY_UPDATE_VERSION,
                    ServerService.KEY_HTTPS, ServerService.KEY_AUTO_UPDATE,
                    ServerService.KEY_AUTO_UPDATE_WV,
                    ServerService.KEY_AUTO_RESTART_UPDATE,
                    ServerService.KEY_BIN_SHA, ServerService.KEY_PORT_MIGRATED,
                    ServerService.KEY_BIN_PILIH, ServerService.KEY_WV_PILIH,
                    KEY_TG_AUTO));

    static final java.util.Set<String> KUNCI_BOOLEAN = new java.util.HashSet<>(
            java.util.Arrays.asList(ServerService.KEY_AUTO_START, ServerService.KEY_HTTPS,
                    ServerService.KEY_AUTO_UPDATE, ServerService.KEY_AUTO_UPDATE_WV,
                    ServerService.KEY_AUTO_RESTART_UPDATE, ServerService.KEY_PORT_MIGRATED,
                    KEY_TG_AUTO, "wizard_selesai",
                    PinGate.KEY_PIN_ON, KEY_BACKUP_TERTUNDA,
                    ServerService.KEY_START_TERTUNDA,
                    "tg_low_storage_notified", "home_log_expanded",
                    TgBot.KEY_TG_HAPUS_OK, TgBot.KEY_TG_HAPUS_WARN));

    static final java.util.Set<String> KUNCI_INT = new java.util.HashSet<>(
            java.util.Arrays.asList(PinGate.KEY_GAGAL));

    static final java.util.Set<String> KUNCI_LONG = new java.util.HashSet<>(
            java.util.Arrays.asList(PinGate.KEY_KUNCI_SAMPAI, PinGate.KEY_KUNCI_ELAPSED,
                    KEY_TG_LAST, TgBot.KEY_TG_OFFSET, TgBot.KEY_TG_WALL_MAKS,
                    "wv_fallback_at", "clip_kedaluwarsa"));

    static boolean shaHexValid(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.length() != 64) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (Character.digit(t.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    static Boolean koersiBoolean(Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            long n = ((Number) v).longValue();
            if (n == 0 || n == 1) {
                return n == 1;
            }
            return null;
        }
        if (v instanceof String) {

            String t = (String) v;
            int dari = 0;
            int sampai = t.length();
            while (dari < sampai && t.charAt(dari) <= ' ') {
                dari++;
            }
            while (sampai > dari && t.charAt(sampai - 1) <= ' ') {
                sampai--;
            }
            int n = sampai - dari;
            if (n == 1) {
                char c = t.charAt(dari);
                if (c == '1') {
                    return Boolean.TRUE;
                }
                if (c == '0') {
                    return Boolean.FALSE;
                }
            } else if (n == 4 && cocokKata(t, dari, "true")) {
                return Boolean.TRUE;
            } else if (n == 5 && cocokKata(t, dari, "false")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static boolean cocokKata(String t, int dari, String kata) {
        for (int i = 0; i < kata.length(); i++) {
            if (!Util.samaHurufAscii(t.charAt(dari + i), kata.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static Long koersiLong(Object v) {
        if (v instanceof Long) {
            return (Long) v;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    static String amanString(SharedPreferences sp, String kunci, String bawaan) {
        if (sp == null) {
            return bawaan;
        }
        try {
            String v = sp.getString(kunci, bawaan);
            return v == null ? bawaan : v;
        } catch (ClassCastException e) {
            String sembuh = null;
            try {
                Object v = sp.getAll().get(kunci);
                if (v != null) {
                    sembuh = String.valueOf(v);
                }
            } catch (Exception ignored) {
            }
            try {
                if (sembuh != null) {
                    sp.edit().putString(kunci, sembuh).apply();
                    return sembuh;
                }
                sp.edit().remove(kunci).apply();
            } catch (Exception ignored) {
            }
            return bawaan;
        }
    }

    static boolean amanBoolean(SharedPreferences sp, String kunci, boolean bawaan) {
        if (sp == null) {
            return bawaan;
        }
        try {
            return sp.getBoolean(kunci, bawaan);
        } catch (ClassCastException e) {
            Boolean b = null;
            try {
                b = koersiBoolean(sp.getAll().get(kunci));
            } catch (Exception ignored) {
            }
            try {
                if (b != null) {
                    sp.edit().putBoolean(kunci, b).apply();
                    return b;
                }
                sp.edit().remove(kunci).apply();
            } catch (Exception ignored) {
            }
            return bawaan;
        }
    }

    static int amanInt(SharedPreferences sp, String kunci, int bawaan) {
        if (sp == null) {
            return bawaan;
        }
        try {
            return sp.getInt(kunci, bawaan);
        } catch (ClassCastException e) {
            Long n = null;
            try {
                Object v = sp.getAll().get(kunci);
                n = koersiLong(v);
            } catch (Exception ignored) {
            }
            try {
                if (n != null && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) {
                    sp.edit().putInt(kunci, n.intValue()).apply();
                    return n.intValue();
                }
                sp.edit().remove(kunci).apply();
            } catch (Exception ignored) {
            }
            return bawaan;
        }
    }

    static long amanLong(SharedPreferences sp, String kunci, long bawaan) {
        if (sp == null) {
            return bawaan;
        }
        try {
            return sp.getLong(kunci, bawaan);
        } catch (ClassCastException e) {
            Long n = null;
            try {
                n = koersiLong(sp.getAll().get(kunci));
            } catch (Exception ignored) {
            }
            try {
                if (n != null) {
                    sp.edit().putLong(kunci, n).apply();
                    return n;
                }
                sp.edit().remove(kunci).apply();
            } catch (Exception ignored) {
            }
            return bawaan;
        }
    }

    static long bacaStempelThrottle(Context ctx, String kunci) {
        if (ctx == null) {
            return 0;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            return amanLong(sp, kunci, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    static void simpanStempelThrottle(Context ctx, String kunci, long kini) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(kunci, kini).apply();
        } catch (Exception ignored) {
        }
    }

    static void healkanStringPrefs(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            java.util.Map<String, ?> semua;
            try {
                semua = sp.getAll();
            } catch (Exception ignored) {
                return;
            }
            if (semua == null) {
                return;
            }
            SharedPreferences.Editor ed = sp.edit();
            boolean ubah = false;
            for (Map.Entry<String, ?> e : semua.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                if (v == null) {
                    continue;
                }
                if (KUNCI_STRING.contains(k)) {
                    if (!(v instanceof String)) {
                        ed.putString(k, String.valueOf(v));
                        ubah = true;
                    }
                } else if (KUNCI_BOOLEAN.contains(k)) {
                    if (!(v instanceof Boolean)) {
                        Boolean b = koersiBoolean(v);
                        if (b == null) {
                            ed.remove(k);
                        } else {
                            ed.putBoolean(k, b);
                        }
                        ubah = true;
                    }
                } else if (KUNCI_INT.contains(k)) {
                    if (!(v instanceof Integer)) {
                        Long n = koersiLong(v);
                        if (n == null || n < Integer.MIN_VALUE
                                || n > Integer.MAX_VALUE) {
                            ed.remove(k);
                        } else {
                            ed.putInt(k, n.intValue());
                        }
                        ubah = true;
                    }
                } else if (KUNCI_LONG.contains(k)) {
                    if (!(v instanceof Long)) {
                        Long n = koersiLong(v);
                        if (n == null) {
                            ed.remove(k);
                        } else {
                            ed.putLong(k, n);
                        }
                        ubah = true;
                    }
                }
            }
            if (ubah) {
                ed.apply();
            }
        } catch (Exception ignored) {
        }
    }

    static void applyPrefsFromJson(Context ctx, JSONObject prefs) throws Exception {
        if (prefs == null) {
            return;
        }
        SharedPreferences cur = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String keepToken = amanString(cur, KEY_TG_TOKEN, "");
        String keepChat = amanString(cur, KEY_TG_CHAT, "");
        String keepPass = amanString(cur, KEY_TG_PASS, "");
        String keepAdmin = amanString(cur, ServerService.KEY_ADMIN_TOKEN, "");
        String keepPinHash = amanString(cur, PinGate.KEY_PIN_HASH, "");
        int keepGagal = amanInt(cur, PinGate.KEY_GAGAL, 0);
        long keepKunci = amanLong(cur, PinGate.KEY_KUNCI_SAMPAI, 0);
        long keepOffset = amanLong(cur, TgBot.KEY_TG_OFFSET, 0);
        String keepNotified = amanString(cur, "tg_notified_version", "");
        String keepWvFrom = amanString(cur, "wv_from_version", "");
        java.util.Map<String, Boolean> keepWv = new java.util.HashMap<>();
        for (Map.Entry<String, ?> e : cur.getAll().entrySet()) {
            if (e.getKey().startsWith("wv_notified_")
                    && e.getValue() instanceof Boolean) {
                keepWv.put(e.getKey(), (Boolean) e.getValue());
            }
        }

        SharedPreferences.Editor ed = cur.edit();

        Iterator<String> keys = prefs.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (ServerService.KEY_DATA_DIR.equals(k)) {
                continue;
            }
            if (!BOLEH_IMPOR.contains(k)) {
                continue;
            }
            Object v = prefs.get(k);

            if (KUNCI_STRING.contains(k)) {
                String teks = null;
                if (v instanceof String) {
                    teks = (String) v;
                } else if (v instanceof Number) {
                    teks = String.valueOf(v);
                }
                if (teks != null) {
                    if (ServerService.KEY_PORT.equals(k)) {

                        ed.putString(k, ServerService.normalisasiPort(teks));
                    } else if (ServerService.KEY_UPDATE_VERSION.equals(k)) {

                        String normal = Updater.normVersion(teks);
                        ed.putString(k, normal == null ? "" : normal);
                    } else if (ServerService.KEY_BIN_PILIH.equals(k)
                            || ServerService.KEY_WV_PILIH.equals(k)) {

                        String normal = Updater.normalisasiPinVersi(teks);
                        ed.putString(k, normal == null ? "" : normal);
                    } else if (ServerService.KEY_BIN_SHA.equals(k)) {

                        if (shaHexValid(teks)) {
                            ed.putString(k, teks.trim().toLowerCase(
                                    java.util.Locale.US));
                        }
                    } else {
                        ed.putString(k, teks);
                    }
                }
                continue;
            }
            if (KUNCI_BOOLEAN.contains(k)) {
                if (v instanceof Boolean) {
                    ed.putBoolean(k, (Boolean) v);
                } else {
                    Boolean b = koersiBoolean(v);
                    if (b != null) {
                        ed.putBoolean(k, b);
                    }
                }
                continue;
            }

        }
        ed.putString(KEY_TG_TOKEN, keepToken);
        ed.putString(KEY_TG_CHAT, keepChat);
        ed.putString(KEY_TG_PASS, keepPass);
        if (!keepAdmin.isEmpty()) {
            ed.putString(ServerService.KEY_ADMIN_TOKEN, keepAdmin);
        } else {
            ed.remove(ServerService.KEY_ADMIN_TOKEN);
        }
        if (!keepPinHash.isEmpty()) {
            ed.putString(PinGate.KEY_PIN_HASH, keepPinHash);
        } else {
            ed.remove(PinGate.KEY_PIN_HASH);
        }

        ed.putInt(PinGate.KEY_GAGAL, keepGagal);
        ed.putLong(PinGate.KEY_KUNCI_SAMPAI, keepKunci);
        ed.putBoolean(PinGate.KEY_PIN_ON,
                pinOnHasilRestore(amanBoolean(cur, PinGate.KEY_PIN_ON, false), keepPinHash));

        ed.putLong(TgBot.KEY_TG_OFFSET, keepOffset);
        if (!prefs.has("tg_notified_version") && !keepNotified.isEmpty()) {
            ed.putString("tg_notified_version", keepNotified);
        }
        if (!prefs.has("wv_from_version") && !keepWvFrom.isEmpty()) {
            ed.putString("wv_from_version", keepWvFrom);
        }
        for (Map.Entry<String, Boolean> e : keepWv.entrySet()) {
            if (!prefs.has(e.getKey())) {
                ed.putBoolean(e.getKey(), e.getValue());
            }
        }
        ed.apply();
    }

    static void hapusWalShm(File dataFolder) {
        if (dataFolder == null) {
            return;
        }
        new File(dataFolder, "db.sqlite3-wal").delete();
        new File(dataFolder, "db.sqlite3-shm").delete();
    }

    static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
            fos.getFD().sync();
        }

        if (src.length() != dst.length()) {
            try {
                dst.delete();
            } catch (Exception ignored) {
            }
            throw new IOException("Salin file tak lengkap (" + src.getName()
                    + ": " + src.length() + " -> " + dst.length() + ").");
        }
    }

    static boolean adaSegmenDotDot(String n) {

        if (n == null || n.isEmpty()) {
            return false;
        }
        int mulai = 0;
        int len = n.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || n.charAt(i) == '/') {
                int l = i - mulai;
                if (l == 2 && n.charAt(mulai) == '.' && n.charAt(mulai + 1) == '.') {
                    return true;
                }
                mulai = i + 1;
            }
        }
        return false;
    }

    static String normalisasiEntriZip(String name) {
        if (name == null) {
            return null;
        }
        String n = name.replace('\\', '/');
        while (n.startsWith("/")) {
            n = n.substring(1);
        }
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        if (n.isEmpty() || adaSegmenDotDot(n) || n.contains(":")) {
            return null;
        }
        if (diterimaEntriZip(n)) {
            return n;
        }

        int i = n.indexOf('/');
        if (i > 0) {
            String bungkus = n.substring(0, i);
            String sisa = n.substring(i + 1);
            if (!bungkus.isEmpty() && !bungkus.equals(".") && !bungkus.equals("..")
                    && !sisa.isEmpty() && diterimaEntriZip(sisa)) {
                return sisa;
            }
        }
        return null;
    }

    static boolean bolehTulisRestore(String nama) {
        if (nama == null) {
            return false;
        }
        if (nama.equals("db.sqlite3") || nama.equals("db.sqlite3-wal")
                || nama.equals("db.sqlite3-shm")) {
            return true;
        }
        return nama.equals("tls") || nama.equals("tls/ca.pem")
                || nama.equals("tls/cert.pem") || nama.equals("tls/key.pem")
                || nama.equals("tls/ca-key.pem");
    }

    private static boolean diterimaEntriZip(String n) {
        if (n.isEmpty() || adaSegmenDotDot(n) || n.contains(":")) {
            return false;
        }
        if (n.equals("app-config.json") || n.equals("tls")) {
            return true;
        }

        if (n.equals("db.sqlite3") || n.equals("db.sqlite3-wal")
                || n.equals("db.sqlite3-shm")) {
            return true;
        }

        return n.equals("tls/ca.pem") || n.equals("tls/cert.pem")
                || n.equals("tls/key.pem") || n.equals("tls/ca-key.pem");
    }

    static boolean isSqliteFile(File f) {

        try {
            if (f == null || f.length() < 512) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        byte[] head = new byte[16];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < head.length) {
                int n = in.read(head, off, head.length - off);
                if (n <= 0) {
                    return false;
                }
                off += n;
            }
        } catch (Exception e) {
            return false;
        }
        byte[] magic = MAGIC_SQLITE;
        for (int i = 0; i < magic.length; i++) {
            if (head[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    static final int BATAS_CONFIG_JSON = 1024 * 1024;

    static final int BATAS_TLS_POTRET = 1024 * 1024;

    static byte[] readAllBytes(InputStream in) throws Exception {
        return bacaTerbatas(in, BATAS_CONFIG_JSON);
    }

    static void tulisAscii(OutputStream os, String s) throws IOException {
        if (os == null || s == null || s.isEmpty()) {
            return;
        }
        int n = s.length();
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) s.charAt(i);
        }
        os.write(b);
    }

    static String bacaResponsBatas(InputStream in) {
        try {
            return new String(bacaTerbatas(in, 1024 * 1024), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static byte[] bacaTerbatas(InputStream in, long batas) throws Exception {

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[64 * 1024];
        int n;
        long total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > batas) {
                throw new IOException("Ukuran data melebihi batas " + batas + " byte.");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static final java.util.Map<String, long[]> FOLDER_SIZE_CACHE =
            new java.util.HashMap<>();
    private static final long FOLDER_SIZE_TTL_MS = 60_000;

    public static long folderBytesCached(File dir) {
        if (dir == null) {
            return 0;
        }

        String key = dir.getAbsolutePath();

        long now = SystemClock.elapsedRealtime();
        synchronized (FOLDER_SIZE_CACHE) {
            long[] hit = FOLDER_SIZE_CACHE.get(key);
            if (hit != null && now - hit[1] < FOLDER_SIZE_TTL_MS) {
                return hit[0];
            }
        }
        long size = folderBytesWalk(dir);
        synchronized (FOLDER_SIZE_CACHE) {
            if (FOLDER_SIZE_CACHE.size() > 64) {
                FOLDER_SIZE_CACHE.clear();
            }
            FOLDER_SIZE_CACHE.put(key, new long[]{size, now});
        }
        return size;
    }

    static long folderBytesWalk(File file) {
        return folderBytesWalk(file, 0, new java.util.HashSet<String>());
    }

    private static long folderBytesWalk(File file, int dalam,
            java.util.Set<String> kunjung) {

        if (dalam > 32) {
            return 0;
        }
        if (file.isDirectory()) {
            try {
                String kanon = file.getAbsolutePath();
                if (!kunjung.add(kanon)) {
                    return 0;
                }
            } catch (Exception ignored) {
            }
            File[] children = file.listFiles();
            if (children != null) {
                long sum = 0;
                for (File c : children) {
                    sum += folderBytesWalk(c, dalam + 1, kunjung);
                }
                return sum;
            }
            return 0;
        }
        return file.length();
    }

    static boolean hex32(String s) {
        if (s == null || s.length() != 32) {
            return false;
        }
        for (int i = 0; i < 32; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    static long hariLokal(long ms) {
        long offset = java.util.TimeZone.getDefault().getOffset(ms);
        return (ms + offset) / 86400000L;
    }

    public static String backupTimestamp() {
        return Util.formatTanggalSekarang("yyyyMMdd-HHmmss-SSS");
    }

    public static String stempelUnik() {
        long acak = SECURE_RANDOM.nextLong() & 0xFFFFFFFFFFFFL;
        return backupTimestamp() + "-" + Util.hex12(acak);
    }

    @SuppressLint("UsableSpace")
    public static long freeBytes(String dirPath) {
        try {
            StatFs sf = new StatFs(dirPath);
            return sf.getAvailableBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    static java.util.List<String> folderCekRuangBackup(String dataDir,
                                                      boolean terenkripsi,
                                                      String cacheDir) {
        java.util.ArrayList<String> hasil = new java.util.ArrayList<>(2);
        if (terenkripsi && cacheDir != null && !cacheDir.trim().isEmpty()) {
            hasil.add(cacheDir);
        }
        if (dataDir != null && !dataDir.trim().isEmpty()
                && !hasil.contains(dataDir)) {
            hasil.add(dataDir);
        }
        return hasil;
    }

    static long panjangBerkas(File f) {
        return f != null && f.isFile() ? f.length() : 0;
    }

    static long ruangBackupDibutuhkan(long isiDb, boolean folderCache,
                                      boolean terenkripsi) {
        long isi = Math.max(0L, isiDb);
        long butuh = (folderCache && terenkripsi) ? 2L * isi : isi;
        butuh += 5L * 1024 * 1024;
        return Math.max(butuh, 10L * 1024 * 1024);
    }

    private static final java.util.Map<String, long[]> MEMO_BEBAS =
            new java.util.HashMap<>();
    private static final long MEMO_BEBAS_TTL_MS = 30_000;

    public static long freeBytes(Context ctx, String dirPath) {
        if (dirPath != null) {
            long now = SystemClock.elapsedRealtime();
            synchronized (MEMO_BEBAS) {
                if (MEMO_BEBAS.size() > 16) {
                    MEMO_BEBAS.clear();
                }
                long[] hit = MEMO_BEBAS.get(dirPath);
                if (hit != null && now - hit[1] < MEMO_BEBAS_TTL_MS) {
                    return hit[0];
                }
            }
            long nilai = freeBytesAktual(ctx, dirPath);
            synchronized (MEMO_BEBAS) {
                MEMO_BEBAS.put(dirPath, new long[]{nilai, now});
            }
            return nilai;
        }
        return freeBytes(dirPath);
    }

    private static long freeBytesAktual(Context ctx, String dirPath) {
        if (ctx != null && dirPath != null && Build.VERSION.SDK_INT >= 26) {
            try {
                StorageManager sm = (StorageManager) ctx.getSystemService(
                        Context.STORAGE_SERVICE);
                if (sm != null) {
                    UUID uuid = sm.getUuidForPath(new File(dirPath));
                    if (uuid != null) {
                        return sm.getAllocatableBytes(uuid);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return freeBytes(dirPath);
    }

    public static String humanBytes(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return satuDesimal(bytes, 1024, "KB");
        }
        if (bytes < 1024L * 1024 * 1024) {
            return satuDesimal(bytes, 1048576, "MB");
        }
        if (bytes < 1024L * 1024 * 1024 * 1024) {
            return satuDesimal(bytes, 1073741824, "GB");
        }
        return satuDesimal(bytes, 1099511627776L, "TB");
    }

    static String satuDesimal(long bytes, long unit, String suffix) {
        if (unit <= 0) {
            return bytes + " " + suffix;
        }

        long bulat = bytes / unit;
        long desimal = (bytes % unit * 10 + unit / 2) / unit;
        if (desimal >= 10) {
            bulat += 1;
            desimal = 0;
        }
        return bulat + "." + desimal + " " + suffix;
    }

    static boolean sudahGantiHari(long lastBackupMs, long sekarangMs) {
        if (lastBackupMs <= 0) {
            return true;
        }
        return hariLokal(lastBackupMs) != hariLokal(sekarangMs);
    }

    static long nextMidnight(long acuanMs) {
        java.util.TimeZone zona = java.util.TimeZone.getDefault();
        long offset = zona.getOffset(acuanMs);
        long hari = (acuanMs + offset) / 86400000L;
        long kandidat = (hari + 1) * 86400000L - offset;
        long offsetTarget = zona.getOffset(kandidat);
        if (offsetTarget != offset) {
            kandidat = (hari + 1) * 86400000L - offsetTarget;
        }
        return kandidat + 60000L;
    }

    public static void schedule(Context ctx, boolean enable) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent intent = new Intent(ctx, AlarmReceiver.class)
                .setAction(AlarmReceiver.ACTION_HARIAN)
                .putExtra(AlarmReceiver.EXTRA_RAHASIA, rahasiaAlarm(ctx));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ_HARIAN, intent, flags);
        if (enable) {
            long trigger = nextMidnight(System.currentTimeMillis());
            boolean exact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
            if (exact && Build.VERSION.SDK_INT >= 31) {

                try {
                    exact = am.canScheduleExactAlarms();
                } catch (Exception ignored) {
                    exact = false;
                }
            }
            if (!exact) {
                ServerService.catatLog("[tg] Backup harian tidak tepat waktu: izin exact alarm"
                        + " ditolak - beri izin Alarms & reminders agar backup tengah malam pas.");
            }
            try {
                if (exact) {

                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
                } else {
                    am.setInexactRepeating(AlarmManager.RTC_WAKEUP, trigger,
                            AlarmManager.INTERVAL_DAY, pi);
                }
            } catch (Exception e) {

                try {
                    am.setInexactRepeating(AlarmManager.RTC_WAKEUP, trigger,
                            AlarmManager.INTERVAL_DAY, pi);
                } catch (Exception ignored) {
                }
            }
        } else {
            am.cancel(pi);

            try {
                Intent lawas = new Intent(ctx, AlarmReceiver.class);
                PendingIntent piLawas = PendingIntent.getBroadcast(ctx, REQ_HARIAN, lawas, flags);
                am.cancel(piLawas);
            } catch (Exception ignored) {
            }
        }
    }
}
