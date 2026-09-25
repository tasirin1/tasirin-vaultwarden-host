package com.tasirin.vaultwardenhost;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
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

/** Backup database ke Telegram + jadwal harian via AlarmManager. */
public final class TgBackup {

    public static final String KEY_TG_TOKEN = "tg_token";
    public static final String KEY_TG_CHAT = "tg_chat";
    public static final String KEY_TG_AUTO = "tg_auto";
    public static final String KEY_TG_LAST = "tg_last_backup";
    public static final String KEY_TG_PASS = "tg_pass";
    public static final String KEY_TG_LAST_FILE = "tg_last_file";
    public static final String KEY_TG_LAST_NAME = "tg_last_name";
    private static final String KEY_TG_LOW_STORAGE = "tg_low_storage_notified";
    public static final long TG_INTERVAL_MS = 24L * 3600 * 1000;

    /** Migrasi sekali jalan: gabungan toggle lama ke KEY_TG_AUTO. */
    public static void migrateAutoPref(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        if (sp.contains("tg_backup_on_start")) {
            boolean lama = sp.getBoolean("tg_backup_on_start", false);
            if (lama && !sp.getBoolean(KEY_TG_AUTO, false)) {
                sp.edit().putBoolean(KEY_TG_AUTO, true).apply();
            }
            sp.edit().remove("tg_backup_on_start").apply();
        }
        if (sp.contains("tg_full")) {
            sp.edit().remove("tg_full").apply();
        }
    }

    private static final long LOW_STORAGE_BYTES = 500L * 1024 * 1024;
    // Satu worker berantre untuk pesan Telegram: hemat thread (sebelumnya satu
    // thread baru per pesan) sekaligus menjaga urutan pengiriman.
    private static final ExecutorService TG_MSG_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vw-tgmsg");
        t.setDaemon(true);
        return t;
    });

    private static final String ENC_MAGIC = "VWB1";

    private static final String TG_API = "https://api.telegram.org/bot";
    private static final int KEEP_BACKUPS = 10;

    private TgBackup() {
    }

    /** Kunci penamaan backup: dua backup se-milidetik (bot vs UI, create vs
     *  pre-backup) tak boleh memakai nama file yang sama. */
    private static final Object KUNCI_NAMA_BACKUP = new Object();

    /** Nama bebas tabrakan: tambah -1, -2 bila dasar sudah dipakai. */
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

    /** Daftar nama file di folder (kunci unik butuh tahu yang sudah ada). */
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

    /** True bila file DB siap di-backup (ada + berisi + header SQLite valid). */
    static boolean dbSiap(File f) {
        return f != null && f.isFile() && f.length() > 0 && isSqliteFile(f);
    }

    /** Tunggu DB terbentuk setelah Start (cek tiap 500 ms, maks 30 dtk) lalu backup.
     *  Pengganti sleep(5000) tebakan: di STB lambat DB belum tentu jadi dalam 5 dtk,
     *  di HP cepat tak perlu menunggu selama itu. */
    public static String backupTungguDb(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String dataDir = sp.getString(ServerService.KEY_DATA_DIR,
                ServerService.DEFAULT_DATA_DIR);
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = ServerService.DEFAULT_DATA_DIR;
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
        return backupNow(ctx);
    }

    /** Callback UI untuk backup otomatis saat Start (toast + catat + pos UI). */
    public interface BackupStartUi {
        void toast(String s);
        void catat(String s);
        void jalankanUi(Runnable r);
    }

    /** Backup Telegram otomatis saat Start (sekali sehari, bila hari berganti).
     *  Satu implementasi untuk Main & Settings (dulu duplikat). */
    public static void maybeAutoBackup(Context ctx, BackupStartUi ui) {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        if (!sp.getBoolean(KEY_TG_AUTO, false)) {
            return;
        }
        String token = Util.amanTrim(sp.getString(KEY_TG_TOKEN, ""));
        String chat = Util.amanTrim(sp.getString(KEY_TG_CHAT, ""));
        if (token.isEmpty() || chat.isEmpty()) {
            ui.catat("[tg] Backup otomatis saat Start dilewati: token/chat belum diisi.");
            return;
        }
        long last = sp.getLong(KEY_TG_LAST, 0);
        if (last > 0 && !sudahGantiHari(last, System.currentTimeMillis())) {
            return;
        }
        ui.catat("[tg] Backup otomatis saat Start akan dijalankan...");
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                final String msg = backupTungguDb(app);
                ui.jalankanUi(() -> {
                    ui.toast(msg);
                    ui.catat("[tg] " + msg);
                });
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                ui.catat("[tg] Gagal backup otomatis saat Start: " + e);
            }
        }, "vw-tg-onstart").start();
    }

    /** Backup sekarang; melempar Exception bila gagal. Mengembalikan pesan sukses. */
    public static String backupNow(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String token = Util.amanTrim(sp.getString(KEY_TG_TOKEN, ""));
        String chat = Util.amanTrim(sp.getString(KEY_TG_CHAT, ""));
        if (token.isEmpty() || chat.isEmpty()) {
            throw new IOException("Bot token / chat ID belum diisi.");
        }
        String dataDir = sp.getString(ServerService.KEY_DATA_DIR, ServerService.DEFAULT_DATA_DIR);
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = ServerService.DEFAULT_DATA_DIR;
        }
        File db = new File(dataDir, "db.sqlite3");
        if (!db.exists()) {
            throw new IOException("Database belum ada.");
        }

        long free = freeBytes(dataDir);
        if (free >= 0 && free < 10L * 1024 * 1024) {
            throw new IOException("Sisa penyimpanan tinggal " + (free / 1048576)
                    + " MB - backup dibatalkan.");
        }

        // Kunci konsistensi: pindahkan isi WAL ke DB utama dulu supaya salinan
        // file tidak menangkap transaksi setengah jalan saat server sedang jalan.
        checkpointWal(db);
        // Integritas SQLite penuh (bukan cuma magic header): tolak DB robek
        // akibat salin saat server menulis, sebelum diunggah ke Telegram.
        String cekDb = cekIntegritasDb(db);
        if (cekDb != null) {
            throw new IOException("Database korup (" + cekDb + ") - backup dibatalkan.");
        }

        // Backup selalu menyertakan pengaturan + sertifikat (checkbox dihapus).
        String passAwal = sp.getString(KEY_TG_PASS, "");
        boolean terenkripsi = passAwal != null && !passAwal.trim().isEmpty();
        // Tanpa password, backup melenggang plaintext ke cloud Telegram:
        // kunci privat TLS tidak ikut (sertifikat publik + DB tetap ikut).
        File zip = createBackupZip(dataDir, true, configJson(sp), terenkripsi);
        // Verifikasi sebelum diunggah: jangan kirim backup korup ke Telegram.
        String galat = verifikasiZip(zip);
        if (galat != null) {
            zip.delete();
            throw new IOException("Backup gagal verifikasi: " + galat);
        }
        File upload = zip;
        String pass = sp.getString(KEY_TG_PASS, "");
        if (pass != null && !pass.trim().isEmpty()) {
            File enc = new File(zip.getParentFile(), zip.getName() + ".enc");
            encryptFile(zip, enc, pass.trim());
            // Round-trip: pastikan hasil enkripsi bisa dibuka dengan password ini.
            // Round-trip di cache internal (bukan /sdcard) agar plaintext
            // sementara tak terekspos ke app lain di storage publik.
            File tmpDec = new File(ctx.getCacheDir(), "verifikasi-tmp.zip");
            try {
                decryptFile(enc, tmpDec, pass.trim());
                String galat2 = verifikasiZip(tmpDec);
                if (galat2 != null) {
                    throw new IOException("Backup gagal verifikasi setelah enkripsi: "
                            + galat2);
                }
            } finally {
                tmpDec.delete();
            }
            zip.delete();
            upload = enc;
        }
        String resp = uploadTelegram(ctx, token, chat, upload);
        String fileId = extractFileId(resp);
        sp.edit()
                .putLong(KEY_TG_LAST, System.currentTimeMillis())
                .putString(KEY_TG_LAST_FILE, fileId)
                .putString(KEY_TG_LAST_NAME, upload.getName())
                .apply();
        String peringatan = terenkripsi ? ""
                : " (TANPA enkripsi: kunci privat tak ikut; isi password backup"
                        + " agar terenkripsi penuh)";
        return "Backup terkirim ke Telegram \u2713 (" + upload.getName() + ", "
                + upload.length() + " bytes)" + peringatan;
    }

    /** Siapkan POST Telegram tanpa ikuti redirect otomatis.
     *  Redirect POST ditolak mentah (Telegram API tak memakai redirect;
     *  mengikuti redirect bisa bocorkan token bot ke host lain). */
    static HttpURLConnection bukaPostTelegram(Context ctx, String url, byte[] body,
            String contentType, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new java.net.URL(url).openConnection();
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

    /** Tolak redirect Telegram (fail-closed agar token tak bocor ke http/host lain). */
    static void tolakRedirectTelegram(HttpURLConnection c) throws java.io.IOException {
        int kode = c.getResponseCode();
        if (kode == 301 || kode == 302 || kode == 303 || kode == 307 || kode == 308) {
            String lok = c.getHeaderField("Location");
            throw new java.io.IOException("Redirect Telegram ditolak: " + lok);
        }
    }

    /** Buka URL file Telegram sambil ikuti redirect hanya yang https. */
    static HttpURLConnection bukaFileTelegram(Context ctx, String url,
            int connectMs, int readMs) throws Exception {
        String kini = url;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new java.net.URL(kini).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setInstanceFollowRedirects(false);
            HttpsCompat.apply(c, ctx);
            int kode = c.getResponseCode();
            if (kode == 301 || kode == 302 || kode == 303 || kode == 307 || kode == 308) {
                String lok = c.getHeaderField("Location");
                c.disconnect();
                if (lok == null || lok.isEmpty()
                        || !Util.bolehIkutiRedirect(kini, lok)) {
                    throw new java.io.IOException("Redirect Telegram tidak aman: " + lok);
                }
                kini = Util.sambungRedirect(kini, lok);
                continue;
            }
            return c;
        }
        throw new java.io.IOException("Terlalu banyak redirect Telegram.");
    }

    /** Kirim pesan teks ke chat ID yang dikonfigurasi (async, silent bila belum diisi).
     *  Async agar tidak pernah memblokir thread pemanggil (mis. main thread saat start/stop). */
    public static void sendMessage(Context ctx, String text) {
        kirimPesan(ctx, text, null);
    }

    /** Kirim pesan + keyboard inline (mis. tombol perintah di /help). */
    public static void sendMessageKb(Context ctx, String text, String markupJson) {
        kirimPesan(ctx, text, markupJson);
    }

    private static void kirimPesan(Context ctx, String text, String markupJson) {
        final Context app = ctx.getApplicationContext();
        final String msg = text == null ? "" : text;
        final String markup = markupJson;
        TG_MSG_EXEC.execute(() -> {
            try {
                SharedPreferences sp = app.getSharedPreferences(ServerService.PREFS,
                        Context.MODE_PRIVATE);
                String token = Util.amanTrim(sp.getString(KEY_TG_TOKEN, ""));
                String chat = Util.amanTrim(sp.getString(KEY_TG_CHAT, ""));
                if (token.isEmpty() || chat.isEmpty()) {
                    return;
                }
                // POST (bukan GET): token tidak bocor ke log URL/proxy.
                String param = "chat_id=" + URLEncoder.encode(chat, "UTF-8")
                        + "&text=" + URLEncoder.encode(msg, "UTF-8");
                if (markup != null && !markup.isEmpty()) {
                    param += "&reply_markup=" + URLEncoder.encode(markup, "UTF-8");
                }
                byte[] body = param.getBytes(StandardCharsets.UTF_8);
                HttpURLConnection conn = null;
                try {
                    conn = bukaPostTelegram(app, TG_API + token + "/sendMessage", body,
                            "application/x-www-form-urlencoded", 15000, 30000);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body);
                    }
                    tolakRedirectTelegram(conn);
                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 300)
                            ? conn.getInputStream() : conn.getErrorStream();
                    StringBuilder sb = new StringBuilder();
                    if (is != null) {
                        try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                sb.append(line);
                            }
                        }
                    }
                    if (code != 200 || !sb.toString().contains("\"ok\":true")) {
                        logTgFailure("kirim pesan", code, sb.toString());
                    }
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            } catch (Exception e) {
                logTgFailure("kirim pesan", -1, String.valueOf(e.getMessage()));
            }
        });
    }

    /** Paksa SQLite menulis isi WAL ke DB utama (best-effort; gagal = lanjut). */
    static void checkpointWal(File dbFile) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            android.database.Cursor c = db.rawQuery(
                    "PRAGMA wal_checkpoint(TRUNCATE);", null);
            if (c != null) {
                c.moveToFirst();
                c.close();
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
    }

    /** PRAGMA quick_check baca-saja; null bila OK, pesan singkat bila korup. */
    static String cekIntegritasDb(File dbFile) {
        SQLiteDatabase db = null;
        android.database.Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
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

    /** Catat kegagalan Telegram ke log app (isi respons dipotong 200 char). */
    static void logTgFailure(String aksi, int code, String detail) {
        try {
            String d = detail == null ? "" : detail;
            if (d.length() > 200) {
                d = d.substring(0, 200) + "...";
            }
            String line = "[tg] Gagal " + aksi + " (HTTP " + code + "): " + d;
            ServerService.catatLog(line);
        } catch (Exception ignored) {
        }
    }

    /** Kirim peringatan storage ke Telegram sekali saat sisa < 500 MB; reset bila lega. */
    public static void notifyLowStorage(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String dataDir = sp.getString(ServerService.KEY_DATA_DIR,
                    ServerService.DEFAULT_DATA_DIR);
            if (dataDir == null || dataDir.trim().isEmpty()) {
                dataDir = ServerService.DEFAULT_DATA_DIR;
            }
            long free = freeBytes(dataDir);
            if (free < 0) {
                return;
            }
            boolean low = free < LOW_STORAGE_BYTES;
            boolean notified = sp.getBoolean(KEY_TG_LOW_STORAGE, false);
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

    /** Verifikasi isi zip backup: wajib ada db.sqlite3 ber-header SQLite valid.
     *  Kembalikan null bila OK, atau pesan galat bila korup (tanpa Android runtime). */
    static String verifikasiZip(File zip) {
        if (zip == null || !zip.isFile() || zip.length() == 0) {
            return "file backup kosong/hilang";
        }
        try (ZipFile zf = new ZipFile(zip)) {
            java.util.zip.ZipEntry db = zf.getEntry("db.sqlite3");
            if (db == null) {
                return "tidak berisi db.sqlite3";
            }
            if (db.getSize() == 0) {
                return "db.sqlite3 kosong (0 byte)";
            }
            // Selaras isSqliteFile(): DB buntung bermagic valid wajib ditolak
            // sebelum diunggah, bukan baru gagal saat restore.
            if (db.getSize() >= 0 && db.getSize() < 512) {
                return "db.sqlite3 terpotong (<512 byte)";
            }
            // Baca 512 byte pertama: ukuran entri bisa -1 (tak diketahui,
            // zip streaming) sehingga cek <512 di atas lolos — hitung langsung
            // dari stream agar DB buntung bermagic valid tetap ditolak.
            byte[] head = new byte[512];
            int off = 0;
            try (InputStream in = zf.getInputStream(db)) {
                int n;
                while (off < head.length
                        && (n = in.read(head, off, head.length - off)) > 0) {
                    off += n;
                }
            }
            if (off < 512) {
                return "db.sqlite3 terpotong (<512 byte)";
            }
            byte[] want = "SQLite format 3\0".getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < want.length; i++) {
                if (head[i] != want[i]) {
                    return "db.sqlite3 bukan database SQLite valid";
                }
            }
            return null;
        } catch (Exception e) {
            String sebab = e.getMessage() != null ? e.getMessage() : e.toString();
            return "zip korup/tak terbaca (" + sebab + ")";
        }
    }

    /** Zip db + WAL (opsional config & sertifikat) ke <data>/backups/,
     *  lalu bersihkan backup lama (sisakan 10). Kunci privat hanya ikut bila
     *  backup terenkripsi (denganKunci=true); tanpa itu plaintext ke cloud. */
    private static File createBackupZip(String dataDir, boolean full,
                                        String configJson, boolean denganKunci)
            throws Exception {
        File dataFolder = new File(dataDir);
        File backupDir = new File(dataFolder, "backups");
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            throw new IOException("Gagal membuat folder backup");
        }
        String ts = backupTimestamp();
        File zip;
        File tmp;
        synchronized (KUNCI_NAMA_BACKUP) {
            // Nama unik + klaim .tmp di dalam kunci agar thread lain (bot vs UI)
            // tak memilih nama yang sama dalam milidetik yang sama; .tmp yang
            // diklaim langsung dibuat agar pemegang lain melihatnya.
            java.util.Set<String> ada = namaFileDi(backupDir);
            File z = null;
            File t = null;
            for (int coba = 0; coba < 100 && z == null; coba++) {
                String nama = namaBackupUnik("backup-telegram-" + ts + ".zip", ada);
                ada.add(nama);
                ada.add(nama + ".tmp");
                File candTmp = new File(backupDir, nama + ".tmp");
                try {
                    if (candTmp.createNewFile()) {
                        z = new File(backupDir, nama);
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
        // Tulis ke tmp + rename agar zip parsial (disk penuh/crash) tak masuk
        // retensi dan mengusir backup bagus via cleanupOldBackups().
        String[] names = {"db.sqlite3", "db.sqlite3-wal", "db.sqlite3-shm"};
        try {
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
                byte[] buf = new byte[64 * 1024];
                for (String name : names) {
                    addFileEntry(zos, buf, new File(dataFolder, name), name);
                }
            if (full) {
                if (configJson != null) {
                    zos.putNextEntry(new ZipEntry("app-config.json"));
                    zos.write(configJson.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
                addFileEntry(zos, buf, new File(dataFolder, "tls/ca.pem"), "tls/ca.pem");
                addFileEntry(zos, buf, new File(dataFolder, "tls/cert.pem"), "tls/cert.pem");
                if (denganKunci) {
                    addFileEntry(zos, buf, new File(dataFolder, "tls/ca-key.pem"),
                            "tls/ca-key.pem");
                    addFileEntry(zos, buf, new File(dataFolder, "tls/key.pem"), "tls/key.pem");
                }
            }
            }
            if (zip.exists() && !zip.delete()) {
                throw new IOException("Gagal mengganti backup lama");
            }
            if (!tmp.renameTo(zip)) {
                throw new IOException("Gagal memasang backup");
            }
        } catch (Exception e) {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
            throw e;
        }
        cleanupOldBackups(backupDir);
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
            while ((n = fis.read(buf)) > 0) {
                zos.write(buf, 0, n);
            }
        }
        zos.closeEntry();
    }

    /** Kredensial perangkat-lokal: tidak ikut backup/export agar token tidak
     *  mampir ke cloud Telegram / file polos. Restore/import mempertahankan
     *  nilai yang sudah ada di perangkat (lihat applyPrefsFromJson). */
    static final java.util.Set<String> SECRET_PREF_KEYS = new java.util.HashSet<>(
            java.util.Arrays.asList("admin_token", "tg_token", "tg_chat",
                    "tg_pass", "pin_hash", "pin_gagal", "pin_kunci_sampai"));

    /** JSON pengaturan (format sama dengan export/import config di app). */
    public static String configJson(SharedPreferences sp) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "tasirin-vaultwarden-host");
        root.put("version", 1);
        JSONObject prefs = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (SECRET_PREF_KEYS.contains(e.getKey())) {
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

    /** Hapus backup terlama di folder backups, sisakan KEEP_BACKUPS terbaru.
     *  Hanya file backup (backup-telegram-*, db-backup-*) yang dihitung;
     *  export pengaturan (app-config-*.json) tidak ikut terhapus. */
    /** True bila nama file adalah backup database (bukan export config). */
    public static boolean isFileBackup(String nama) {
        return nama != null && (nama.startsWith("backup-telegram-")
                || nama.startsWith("db-backup-"));
    }

    public static void cleanupOldBackups(File backupDir) {
        File[] all = backupDir.listFiles();
        if (all == null) {
            return;
        }
        java.util.List<File> list = new java.util.ArrayList<>();
        for (File f : all) {
            if (isFileBackup(f.getName())) {
                list.add(f);
            }
        }
        File[] files = list.toArray(new File[0]);
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = KEEP_BACKUPS; i < files.length; i++) {
            files[i].delete();
        }
    }

    /** Pilih file CA aktif: internal dulu, arsip folder data hanya fallback.
     *  Murni agar bisa unit test JVM. */
    static File caAktif(File internal, File lama) {
        if (internal != null && internal.isFile()) {
            return internal;
        }
        return lama;
    }

    /** Kirim CA HTTPS aktif (ca.pem publik, tanpa kunci privat) ke chat Telegram
     *  resmi. Di HP tujuan: simpan lalu install sebagai “CA certificate”. */
    public static String kirimCa(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String token = Util.amanTrim(sp.getString(KEY_TG_TOKEN, ""));
        String chat = Util.amanTrim(sp.getString(KEY_TG_CHAT, ""));
        if (token.isEmpty() || chat.isEmpty()) {
            throw new IOException("Bot token / chat ID belum diisi.");
        }
        String dataDir = sp.getString(ServerService.KEY_DATA_DIR,
                ServerService.DEFAULT_DATA_DIR);
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = ServerService.DEFAULT_DATA_DIR;
        }
        File ca = caAktif(new File(ctx.getFilesDir(), "tls/ca.pem"),
                new File(dataDir, "tls/ca.pem"));
        if (ca == null || !ca.isFile()) {
            throw new IOException("CA belum ada. Aktifkan HTTPS lalu tekan Start dulu.");
        }
        uploadTelegram(ctx, token, chat, ca);
        return "CA terkirim (ca.pem). Di HP tujuan: simpan lalu install"
                + " sebagai CA certificate (tanpa private key).";
    }

    private static String uploadTelegram(Context ctx, String token, String chatId, File file)
            throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(TG_API + token + "/sendDocument").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(180000);
            conn.setInstanceFollowRedirects(false);
            // Chunked: body backup (MB) mengalir langsung tanpa di-buffer penuh di RAM.
            conn.setChunkedStreamingMode(0);
            HttpsCompat.apply(conn, ctx);
            String boundary = "----vw" + System.currentTimeMillis() + "bound";
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream os = conn.getOutputStream();
                 DataOutputStream dos = new DataOutputStream(os)) {
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
                dos.writeBytes(chatId + "\r\n");
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\""
                        + file.getName() + "\"\r\n");
                dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = fis.read(buf)) > 0) {
                        dos.write(buf, 0, n);
                    }
                }
                dos.writeBytes("\r\n--" + boundary + "--\r\n");
                dos.flush();
            }

            tolakRedirectTelegram(conn);
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line);
                    }
                }
            }
            if (code != 200 || !sb.toString().contains("\"ok\":true")) {
                String body = sb.toString();
                if (body.length() > 200) {
                    body = body.substring(0, 200) + "...";
                }
                throw new IOException("Telegram HTTP " + code + ": " + body);
            }
            return sb.toString();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Ambil file_id dokumen dari respons sendDocument (JSONObject; fallback
     *  parsing manual bila format Telegram berubah). Murni agar bisa unit test. */
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

    /** Unduh backup terakhir yang pernah dikirim dari app ini. */
    public static String downloadLastBackup(Context ctx, File dest) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String token = Util.amanTrim(sp.getString(KEY_TG_TOKEN, ""));
        String fileId = sp.getString(KEY_TG_LAST_FILE, "");
        String name = sp.getString(KEY_TG_LAST_NAME, "backup.zip");
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
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
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
            // POST agar token tidak nangkring di URL (konsisten dengan sendMessage).
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
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        int start = sb.indexOf("\"file_path\":\"");
        if (start < 0) {
            throw new IOException("file_path tidak ditemukan");
        }
        start += "\"file_path\":\"".length();
        int end = sb.indexOf("\"", start);
        if (end < 0) {
            throw new IOException("file_path rusak");
        }
        return sb.substring(start, end);
    }

    // ─── Enkripsi AES-GCM (PBKDF2) ──────────────────────────────────────

    public static boolean isEncrypted(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] magic = new byte[4];
            return fis.read(magic) == 4
                    && ENC_MAGIC.equals(new String(magic, StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static void encryptFile(File in, File out, String pass) throws Exception {
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        SECURE_RANDOM.nextBytes(salt);
        SECURE_RANDOM.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(pass, salt), "AES"),
                new GCMParameterSpec(128, iv));
        try (FileInputStream fis = new FileInputStream(in);
             FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(ENC_MAGIC.getBytes(StandardCharsets.US_ASCII));
            fos.write(salt);
            fos.write(iv);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                byte[] o = c.update(buf, 0, n);
                if (o != null && o.length > 0) {
                    fos.write(o);
                }
            }
            fos.write(c.doFinal());
        }
    }

    /** Dekripsi + hapus output bila gagal (jangan sisakan plaintext parsial). */
    public static void decryptFile(File in, File out, String pass) throws Exception {
        try {
            decryptToFile(in, out, pass);
        } catch (Exception e) {
            try {
                out.delete();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    private static void decryptToFile(File in, File out, String pass) throws Exception {
        try {
            decryptWithKdf(in, out, pass, true);
        } catch (javax.crypto.BadPaddingException e) {
            // Fallback: backup lama memakai PBKDF2-HMAC-SHA1. Hanya untuk galat
            // autentikasi (password salah/KDF beda); galat I/O (disk penuh/hilang)
            // langsung dilempar agar tak 2x PBKDF2 sia-sia dan sebab asli tak tertutup.
            try {
                out.delete();
            } catch (Exception ignored) {
            }
            decryptWithKdf(in, out, pass, false);
        }
    }

    private static void decryptWithKdf(File in, File out, String pass, boolean sha256)
            throws Exception {
        try (FileInputStream fis = new FileInputStream(in);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] magic = new byte[4];
            if (fis.read(magic) != 4
                    || !ENC_MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException("File bukan backup terenkripsi");
            }
            byte[] salt = new byte[16];
            byte[] iv = new byte[12];
            readFully(fis, salt);
            readFully(fis, iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(deriveKey(pass, salt, sha256), "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                byte[] o = c.update(buf, 0, n);
                if (o != null && o.length > 0) {
                    fos.write(o);
                }
            }
            fos.write(c.doFinal());
        }
    }

    private static byte[] deriveKey(String pass, byte[] salt) throws Exception {
        return deriveKey(pass, salt, true);
    }

    /** KDF backup: SHA256 untuk file baru, SHA1 hanya fallback baca file lama. */
    private static byte[] deriveKey(String pass, byte[] salt, boolean sha256) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), salt, 100000, 256);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance(
                    sha256 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1");
            return f.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static void readFully(FileInputStream fis, byte[] out) throws IOException {
        int off = 0;
        while (off < out.length) {
            int n = fis.read(out, off, out.length - off);
            if (n < 0) {
                throw new IOException("File terenkripsi rusak");
            }
            off += n;
        }
    }

    // ─── Restore dari zip backup Telegram (dipakai tombol UI & bot /restore) ──

    /** Restore database dari file zip backup Telegram.
     *  Server dihentikan dulu; DB lama diamankan ke
     *  {@code backups/db-backup-*-pre.sqlite3} dan dikembalikan bila hasil
     *  restore bukan SQLite valid. Pengaturan di {@code app-config.json} ikut
     *  diterapkan, kecuali identitas bot (token/chat/password/offset) yang
     *  dipertahankan agar bot tetap terhubung. */
    public static String restoreFromZip(Context ctx, File zip) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String curDir = sp.getString(ServerService.KEY_DATA_DIR,
                ServerService.DEFAULT_DATA_DIR);
        if (curDir == null || curDir.trim().isEmpty()) {
            curDir = ServerService.DEFAULT_DATA_DIR;
        }

        // Selalu pulihkan ke folder data perangkat saat ini. Nilai data_dir
        // di dalam zip tak tepercaya (bisa menunjuk path arbitrer) sehingga
        // sengaja diabaikan; pengaturan non-rahasia tetap diterapkan
        // belakangan via applyPrefsFromJson (kecuali data_dir).
        JSONObject cfg = null;
        try (ZipInputStream probe = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = probe.getNextEntry()) != null) {
                if ("app-config.json".equals(e.getName())) {
                    cfg = new JSONObject(new String(readAllBytes(probe),
                            StandardCharsets.UTF_8));
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        String dataDir = curDir;
        File dataFolder = new File(dataDir);
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        if (ServerService.isProcessAlive()) {
            ServerService.stopAndWait(ctx, 8000);
        }

        File dbFile = new File(dataFolder, "db.sqlite3");
        File preBackup = null;
        if (dbFile.exists()) {
            File backupDir = new File(dataFolder, "backups");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }
            synchronized (KUNCI_NAMA_BACKUP) {
                String ts = backupTimestamp() + "-pre";
                String nama = namaBackupUnik("db-backup-" + ts + ".sqlite3",
                        namaFileDi(backupDir));
                preBackup = new File(backupDir, nama);
                copyFile(dbFile, preBackup);
            }
            cleanupOldBackups(backupDir);
        }

        byte[] buf = new byte[64 * 1024];
        long totalUnzip = 0;
        int jumlahEntri = 0;
        String basePath = dataFolder.getCanonicalPath();
        String awalanAman = basePath + File.separator;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                jumlahEntri++;
                totalUnzip = Util.tambahUkuranUnzip(totalUnzip, 0,
                        Util.BATAS_UNZIP_RESTORE, jumlahEntri,
                        Util.BATAS_JUMLAH_ENTRI);
                // Allowlist sama seperti restore UI lokal: hanya db.sqlite3*,
                // tls/*, dan app-config.json (diterapkan langsung, tak ditulis).
                String nama = normalisasiEntriZip(entry.getName());
                if (nama == null || "app-config.json".equals(nama)) {
                    try {
                        zis.closeEntry();
                    } catch (Exception ignored) {
                    }
                    continue;
                }
                File outFile = new File(dataFolder, nama);
                String kanon = outFile.getCanonicalPath();
                if (!kanon.equals(basePath) && !kanon.startsWith(awalanAman)) {
                    try {
                        zis.closeEntry();
                    } catch (Exception ignored) {
                    }
                    continue; // cegah zip-slip (mis. basePath-evil tanpa separator)
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
                        while ((n = zis.read(buf)) > 0) {
                            totalUnzip = Util.tambahUkuranUnzip(totalUnzip, n,
                                    Util.BATAS_UNZIP_RESTORE, jumlahEntri,
                                    Util.BATAS_JUMLAH_ENTRI);
                            fos.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
            }
        } finally {
            try {
                if (Util.bolehHapusFile(ctx.getCacheDir(), ctx.getFilesDir(), zip)) {
                    zip.delete();
                }
            } catch (Exception ignored) {
            }
        }
        if (!dbFile.exists()) {
            throw new IOException("Backup tidak berisi db.sqlite3.");
        }
        if (!isSqliteFile(dbFile)) {
            if (preBackup != null && preBackup.exists()) {
                copyFile(preBackup, dbFile);
            } else {
                dbFile.delete();
            }
            throw new IOException("Backup rusak (bukan SQLite)"
                    + " - database lama dikembalikan.");
        }
        try {
            String rusak = cekIntegritasDb(dbFile);
            if (rusak != null) {
                if (preBackup != null && preBackup.exists()) {
                    copyFile(preBackup, dbFile);
                } else {
                    dbFile.delete();
                }
                throw new IOException("Backup rusak (DB korup: " + rusak + ")"
                        + " - database lama dikembalikan.");
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable abaikan) {
            // Lingkungan unit test JVM tanpa SQLite Android: lewati quick_check.
        }
        boolean lengkap = false;
        if (cfg != null) {
            applyPrefsFromJson(ctx, cfg.optJSONObject("prefs"));
            lengkap = true;
        }
        SharedPreferences fresh = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        TgBackup.schedule(ctx, fresh.getBoolean(KEY_TG_AUTO, false));
        TgBot.schedule(ctx);
        return "Database direstore dari Telegram"
                + (lengkap ? " (lengkap, termasuk pengaturan)" : "")
                + ". Ukuran DB: " + dbFile.length() + " bytes."
                + " Tekan /start untuk menjalankan.";
    }

    /** PIN hanya aktif bila ada hash tersimpan; murni agar bisa unit test. */
    static boolean pinAktif(boolean pinOn, String pinHash) {
        return pinOn && pinHash != null && !pinHash.isEmpty();
    }

    /** Terapkan prefs dari JSON backup; identitas bot & penanda notifikasi
     *  dipertahankan agar bot tetap terhubung setelah restore. Kredensial
     *  tak pernah diimpor dari file (termasuk backup lama yang masih
     *  menyimpannya): nilai perangkat dipertahankan, atau dikosongkan bila
     *  perangkat memang tak punya. */
    static void applyPrefsFromJson(Context ctx, JSONObject prefs) throws Exception {
        if (prefs == null) {
            return;
        }
        SharedPreferences cur = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String keepToken = cur.getString(KEY_TG_TOKEN, "");
        String keepChat = cur.getString(KEY_TG_CHAT, "");
        String keepPass = cur.getString(KEY_TG_PASS, "");
        String keepAdmin = cur.getString(ServerService.KEY_ADMIN_TOKEN, "");
        String keepPinHash = cur.getString("pin_hash", "");
        int keepGagal = cur.getInt("pin_gagal", 0);
        long keepKunci = cur.getLong("pin_kunci_sampai", 0);
        long keepOffset = cur.getLong(TgBot.KEY_TG_OFFSET, 0);
        String keepNotified = cur.getString("tg_notified_version", "");
        String keepWvFrom = cur.getString("wv_from_version", "");
        java.util.Map<String, Boolean> keepWv = new java.util.HashMap<>();
        for (Map.Entry<String, ?> e : cur.getAll().entrySet()) {
            if (e.getKey().startsWith("wv_notified_")
                    && e.getValue() instanceof Boolean) {
                keepWv.put(e.getKey(), (Boolean) e.getValue());
            }
        }
        // Kunci pengaturan yang boleh dipulihkan dari zip tak tepercaya.
        // State perangkat (jadwal, offset, notifikasi, UI) selalu dipertahankan.
        java.util.Set<String> bolehImpor = new java.util.HashSet<String>(
                java.util.Arrays.asList(ServerService.KEY_PORT,
                        ServerService.KEY_AUTO_START, ServerService.KEY_UPDATE_VERSION,
                        ServerService.KEY_HTTPS, ServerService.KEY_AUTO_UPDATE,
                        ServerService.KEY_AUTO_UPDATE_WV,
                        ServerService.KEY_AUTO_RESTART_UPDATE,
                        ServerService.KEY_BIN_SHA, ServerService.KEY_PORT_MIGRATED,
                        KEY_TG_AUTO, "advanced_open", "domain_lokal"));
        SharedPreferences.Editor ed = cur.edit();
        // Tanpa clear(): timpa hanya kunci dari backup agar file rusak
        // tak menghapus seluruh pengaturan perangkat. Folder data selalu
        // milik perangkat (abaikan data_dir dari zip tak tepercaya).
        Iterator<String> keys = prefs.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (ServerService.KEY_DATA_DIR.equals(k)) {
                continue;
            }
            if (!bolehImpor.contains(k)) {
                continue;
            }
            Object v = prefs.get(k);
            if (v instanceof String) {
                ed.putString(k, (String) v);
            } else if (v instanceof Boolean) {
                ed.putBoolean(k, (Boolean) v);
            } else if (v instanceof Integer) {
                ed.putInt(k, (Integer) v);
            } else if (v instanceof Long) {
                ed.putLong(k, (Long) v);
            } else if (v instanceof Double) {
                double d = (Double) v;
                if (d == Math.rint(d) && !Double.isInfinite(d)) {
                    long l = (long) d;
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                        ed.putInt(k, (int) l);
                    } else {
                        ed.putLong(k, l);
                    }
                } else {
                    ed.putFloat(k, (float) d);
                }
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
            ed.putString("pin_hash", keepPinHash);
        } else {
            ed.remove("pin_hash");
        }
        // Konsistensi PIN: tanpa hash, kunci PIN wajib mati agar tak fail-open
        // (pin_on impor true + hash kosong = bot tanpa PIN & UI tak mengunci).
        // Counter brute-force selalu milik perangkat (backup tak boleh reset kunci).
        ed.putInt("pin_gagal", keepGagal);
        ed.putLong("pin_kunci_sampai", keepKunci);
        ed.putBoolean("pin_on", pinAktif(prefs.optBoolean("pin_on", false), keepPinHash));
        long importedOffset = prefs.has(TgBot.KEY_TG_OFFSET)
                ? prefs.optLong(TgBot.KEY_TG_OFFSET, 0) : 0;
        ed.putLong(TgBot.KEY_TG_OFFSET, Math.max(keepOffset, importedOffset));
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

    /** Salin file (dipakai juga restore/backup UI agar satu implementasi). */
    static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
    }

    /** Normalisasi nama entri zip jadi path relatif di folder data; null bila ditolak.
     *  Menerima entri top-level (db.sqlite3, tls/..., app-config.json) maupun yang
     *  terbungkus folder (mis. vaultwarden/db.sqlite3 dari zip manual): folder
     *  pembungkus dikupas. Entri licik (.., drive, absolut) selalu ditolak. */
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
        if (n.isEmpty() || n.contains("..") || n.contains(":")) {
            return null;
        }
        if (diterimaEntriZip(n)) {
            return n;
        }
        int i;
        while ((i = n.indexOf('/')) >= 0) {
            n = n.substring(i + 1);
            if (diterimaEntriZip(n)) {
                return n;
            }
        }
        return null;
    }

    private static boolean diterimaEntriZip(String n) {
        if (n.isEmpty() || n.contains("..") || n.contains(":")) {
            return false;
        }
        if (n.equals("app-config.json") || n.equals("tls")) {
            return true;
        }
        // Hanya 3 file DB resmi (awalan longgar mis. db.sqlite3-evil ditolak).
        if (n.equals("db.sqlite3") || n.equals("db.sqlite3-wal")
                || n.equals("db.sqlite3-shm")) {
            return true;
        }
        // Hanya 4 file TLS resmi; file asing di tls/ (mis. tls/evil.sh) ditolak.
        return n.equals("tls/ca.pem") || n.equals("tls/cert.pem")
                || n.equals("tls/key.pem") || n.equals("tls/ca-key.pem");
    }

    /** True bila file ber-header SQLite ("SQLite format 3\0"). */
    static boolean isSqliteFile(File f) {
        // Header saja tak cukup: DB terpotong 16 byte tetap bermagic valid.
        // SQLite terkecil (1 page) ratusan byte; tolak yang jelas buntung.
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
                if (n < 0) {
                    return false;
                }
                off += n;
            }
        } catch (Exception e) {
            return false;
        }
        byte[] magic = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < magic.length; i++) {
            if (head[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** Batas baca app-config.json dari zip tak tepercaya (anti zip-bomb di STB 1 GB). */
    static final int BATAS_CONFIG_JSON = 1024 * 1024;

    static byte[] readAllBytes(InputStream in) throws Exception {
        return bacaTerbatas(in, BATAS_CONFIG_JSON);
    }

    /** Baca stream sampai habis; lempar IOException bila melebihi batas byte. */
    static byte[] bacaTerbatas(InputStream in, long batas) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > batas) {
                throw new IOException("Ukuran data melebihi batas " + batas + " byte.");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    // ─── Util ───────────────────────────────────────────────────────────

    // Cache ukuran folder (TTL 60 dtk): jalan rekursif web-vault (~35 MB,
    // ribuan file) terlalu mahal untuk diulang tiap 5-10 detik oleh UI/status web.
    private static final java.util.Map<String, long[]> FOLDER_SIZE_CACHE =
            new java.util.HashMap<>();
    private static final long FOLDER_SIZE_TTL_MS = 60_000;

    /** Total byte isi folder (rekursif) dengan cache 60 dtk per path. */
    public static long folderBytesCached(File dir) {
        if (dir == null) {
            return 0;
        }
        // Kunci absolute path (tanpa I/O): getCanonicalPath() memanggil syscall
        // sehingga cache-hit pun tetap mahal bila dipakai sebagai kunci.
        String key = dir.getAbsolutePath();
        long now = System.currentTimeMillis();
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

    private static long folderBytesWalk(File file) {
        return folderBytesWalk(file, 0);
    }

    private static long folderBytesWalk(File file, int dalam) {
        // Batas 32 tingkat: symlink melingkar di folder data tak boleh
        // meledak jadi StackOverflowError (tak tertangkap catch Exception).
        if (dalam > 32) {
            return 0;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                long sum = 0;
                for (File c : children) {
                    sum += folderBytesWalk(c, dalam + 1);
                }
                return sum;
            }
            return 0;
        }
        return file.length();
    }

    /** Stamp "yyyyMMdd-HHmmss" untuk nama file backup/export (satu format). */
    public static String backupTimestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
    }

    /** Sisa ruang penyimpanan (bytes) pada partisi path, atau -1 bila gagal dibaca. */
    public static long freeBytes(String dirPath) {
        try {
            StatFs sf = new StatFs(dirPath);
            return sf.getAvailableBytes();
        } catch (Exception e) {
            return -1;
        }
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

    /** Format "x.y UNIT" tanpa String.format (dipanggil tiap refresh).
     *  Murni agar bisa unit test. */
    static String satuDesimal(long bytes, long unit, String suffix) {
        long sepuluh = (bytes * 10 + unit / 2) / unit;
        return (sepuluh / 10) + "." + (sepuluh % 10) + " " + suffix;
    }

    /** True bila backup terakhir beda hari kalender dengan sekarang (murni, bisa unit test).
     *  last<=0 (belum pernah backup) dianggap sudah ganti hari agar backup pertama jalan. */
    static boolean sudahGantiHari(long lastBackupMs, long sekarangMs) {
        if (lastBackupMs <= 0) {
            return true;
        }
        java.util.Calendar a = java.util.Calendar.getInstance();
        a.setTimeInMillis(lastBackupMs);
        java.util.Calendar b = java.util.Calendar.getInstance();
        b.setTimeInMillis(sekarangMs);
        return a.get(java.util.Calendar.YEAR) != b.get(java.util.Calendar.YEAR)
                || a.get(java.util.Calendar.DAY_OF_YEAR) != b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /** Waktu tengah malam berikutnya (00:01) dari acuan, murni agar bisa unit test.
     *  +1 menit supaya tanggal pasti sudah berganti dan terhindar dari balapan
     *  tepat di detik 00:00:00. */
    static long nextMidnight(long acuanMs) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(acuanMs);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 1);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** Jadwalkan backup harian via AlarmManager (atau batalkan bila enable=false).
     *  Alarm dipasang tepat saat tanggal berganti (tengah malam 00:01) +
     *  dijadwalkan ulang tiap selesai, jadi backup jalan sekali sehari.
     *  Dipakai alarm EXACT (tidak di-batch Doze). */
    public static void schedule(Context ctx, boolean enable) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent, flags);
        if (enable) {
            long trigger = nextMidnight(System.currentTimeMillis());
            boolean exact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
            if (exact && Build.VERSION.SDK_INT >= 31) {
                // Android 12+: exact alarm butuh izin/kuota; cek dulu agar
                // tak lempar SecurityException tiap pasang jadwal.
                try {
                    exact = am.canScheduleExactAlarms();
                } catch (Exception ignored) {
                    exact = false;
                }
            }
            try {
                if (exact) {
                    // Tepat waktu walau dalam Doze.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
                    } else {
                        am.set(AlarmManager.RTC_WAKEUP, trigger, pi);
                    }
                } else {
                    am.setInexactRepeating(AlarmManager.RTC_WAKEUP, trigger,
                            AlarmManager.INTERVAL_DAY, pi);
                }
            } catch (Exception e) {
                // Fallback aman bila perangkat menolak exact alarm.
                try {
                    am.setInexactRepeating(AlarmManager.RTC_WAKEUP, trigger,
                            AlarmManager.INTERVAL_DAY, pi);
                } catch (Exception ignored) {
                }
            }
        } else {
            am.cancel(pi);
        }
    }
}
