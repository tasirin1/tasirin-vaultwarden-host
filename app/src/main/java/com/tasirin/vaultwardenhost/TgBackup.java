package com.tasirin.vaultwardenhost;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.StatFs;

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

    /** Backup sekarang; melempar Exception bila gagal. Mengembalikan pesan sukses. */
    public static String backupNow(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String token = sp.getString(KEY_TG_TOKEN, "").trim();
        String chat = sp.getString(KEY_TG_CHAT, "").trim();
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

        // Backup selalu menyertakan pengaturan + sertifikat (checkbox dihapus).
        File zip = createBackupZip(dataDir, true, configJson(sp));
        File upload = zip;
        String pass = sp.getString(KEY_TG_PASS, "");
        if (pass != null && !pass.trim().isEmpty()) {
            File enc = new File(zip.getParentFile(), zip.getName() + ".enc");
            encryptFile(zip, enc, pass.trim());
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
        return "Backup terkirim ke Telegram \u2713 (" + upload.getName() + ", "
                + upload.length() + " bytes)";
    }

    /** Kirim pesan teks ke chat ID yang dikonfigurasi (async, silent bila belum diisi).
     *  Async agar tidak pernah memblokir thread pemanggil (mis. main thread saat start/stop). */
    public static void sendMessage(Context ctx, String text) {
        final Context app = ctx.getApplicationContext();
        final String msg = text == null ? "" : text;
        TG_MSG_EXEC.execute(() -> {
            try {
                SharedPreferences sp = app.getSharedPreferences(ServerService.PREFS,
                        Context.MODE_PRIVATE);
                String token = sp.getString(KEY_TG_TOKEN, "").trim();
                String chat = sp.getString(KEY_TG_CHAT, "").trim();
                if (token.isEmpty() || chat.isEmpty()) {
                    return;
                }
                // POST (bukan GET): token tidak bocor ke log URL/proxy.
                byte[] body = ("chat_id=" + URLEncoder.encode(chat, "UTF-8")
                        + "&text=" + URLEncoder.encode(msg, "UTF-8"))
                        .getBytes(StandardCharsets.UTF_8);
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(TG_API + token + "/sendMessage")
                            .openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("Content-Type",
                            "application/x-www-form-urlencoded");
                    conn.setRequestProperty("Content-Length", String.valueOf(body.length));
                    HttpsCompat.apply(conn, app);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body);
                    }
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

    /** Catat kegagalan Telegram ke log app (isi respons dipotong 200 char). */
    static void logTgFailure(String aksi, int code, String detail) {
        try {
            String d = detail == null ? "" : detail;
            if (d.length() > 200) {
                d = d.substring(0, 200) + "...";
            }
            String line = "[tg] Gagal " + aksi + " (HTTP " + code + "): " + d;
            synchronized (ServerService.logBuffer) {
                ServerService.logBuffer.append(line).append('\n');
            }
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

    /** Zip db + WAL (opsional config & sertifikat) ke <data>/backups/,
     *  lalu bersihkan backup lama (sisakan 10). */
    private static File createBackupZip(String dataDir, boolean full,
                                        String configJson) throws Exception {
        File dataFolder = new File(dataDir);
        File backupDir = new File(dataFolder, "backups");
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            throw new IOException("Gagal membuat folder backup");
        }
        String ts = backupTimestamp();
        File zip = new File(backupDir, "backup-telegram-" + ts + ".zip");
        String[] names = {"db.sqlite3", "db.sqlite3-wal", "db.sqlite3-shm"};
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
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
                addFileEntry(zos, buf, new File(dataFolder, "tls/cert.pem"), "tls/cert.pem");
                addFileEntry(zos, buf, new File(dataFolder, "tls/key.pem"), "tls/key.pem");
            }
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
                    "tg_pass", "pin_hash"));

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
    public static void cleanupOldBackups(File backupDir) {
        File[] all = backupDir.listFiles();
        if (all == null) {
            return;
        }
        java.util.List<File> list = new java.util.ArrayList<>();
        for (File f : all) {
            String n = f.getName();
            if (n.startsWith("backup-telegram-") || n.startsWith("db-backup-")) {
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

    private static String uploadTelegram(Context ctx, String token, String chatId, File file)
            throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(TG_API + token + "/sendDocument").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(180000);
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

    /** Ambil file_id dokumen dari respons sendDocument. */
    private static String extractFileId(String body) {
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
        String token = sp.getString(KEY_TG_TOKEN, "").trim();
        String fileId = sp.getString(KEY_TG_LAST_FILE, "");
        String name = sp.getString(KEY_TG_LAST_NAME, "backup.zip");
        if (token.isEmpty() || fileId.isEmpty()) {
            throw new IOException("Belum ada backup terkirim dari app ini.");
        }
        String path = getFilePath(ctx, token, fileId);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(
                    "https://api.telegram.org/file/bot" + token + "/" + path).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setInstanceFollowRedirects(true);
            HttpsCompat.apply(conn, ctx);
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
            conn = (HttpURLConnection) new URL(
                    "https://api.telegram.org/bot" + token + "/getFile?file_id=" + fileId).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            HttpsCompat.apply(conn, ctx);
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
                fos.write(c.update(buf, 0, n));
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
        } catch (Exception e) {
            // Fallback: backup lama memakai PBKDF2-HMAC-SHA1.
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
                fos.write(c.update(buf, 0, n));
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
        SecretKeyFactory f = SecretKeyFactory.getInstance(
                sha256 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1");
        byte[] key = f.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return key;
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

        // Baca config dulu (bila backup lengkap) untuk tahu folder data tujuan.
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
        }
        String dataDir = curDir;
        if (cfg != null) {
            JSONObject cfgPrefs = cfg.optJSONObject("prefs");
            if (cfgPrefs != null && cfgPrefs.has(ServerService.KEY_DATA_DIR)) {
                String d = cfgPrefs.optString(ServerService.KEY_DATA_DIR, "").trim();
                if (!d.isEmpty()) {
                    dataDir = d;
                }
            }
        }
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
            String ts = backupTimestamp() + "-pre";
            preBackup = new File(backupDir, "db-backup-" + ts + ".sqlite3");
            copyFile(dbFile, preBackup);
            cleanupOldBackups(backupDir);
        }

        byte[] buf = new byte[64 * 1024];
        String basePath = dataFolder.getCanonicalPath();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("app-config.json".equals(entry.getName())) {
                    continue; // pengaturan diterapkan langsung, tidak ditulis ke disk
                }
                File outFile = new File(dataFolder, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(basePath)) {
                    continue; // cegah zip-slip
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
                            fos.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
            }
        } finally {
            zip.delete();
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

    /** Terapkan prefs dari JSON backup; identitas bot & penanda notifikasi
     *  dipertahankan agar bot tetap terhubung setelah restore. */
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
        SharedPreferences.Editor ed = cur.edit();
        ed.clear();
        Iterator<String> keys = prefs.keys();
        while (keys.hasNext()) {
            String k = keys.next();
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
        }
        if (!keepPinHash.isEmpty()) {
            ed.putString("pin_hash", keepPinHash);
        }
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

    /** True bila file ber-header SQLite ("SQLite format 3\0"). */
    static boolean isSqliteFile(File f) {
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

    static byte[] readAllBytes(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
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
        String key;
        try {
            key = dir.getCanonicalPath();
        } catch (Exception e) {
            key = dir.getAbsolutePath();
        }
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
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                long sum = 0;
                for (File c : children) {
                    sum += folderBytesWalk(c);
                }
                return sum;
            }
            return 0;
        }
        return file.isFile() ? file.length() : 0;
    }

    /** Stamp "yyyyMMdd-HHmmss" untuk nama file backup/export (satu format). */
    public static String backupTimestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
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
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes < 1024L * 1024 * 1024 * 1024) {
            return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
        }
        return String.format(Locale.US, "%.1f TB", bytes / 1099511627776.0);
    }

    /** Jadwalkan backup harian via AlarmManager (atau batalkan bila enable=false).
     *  Dipakai alarm EXACT (tidak di-batch Doze) + dijadwalkan ulang tiap selesai,
     *  jadi waktu backup selalu tepat 24 jam dari backup sebelumnya. */
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
            long trigger = System.currentTimeMillis() + TG_INTERVAL_MS;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Tepat waktu walau dalam Doze; tanpa permission khusus karena targetSdk 28.
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, trigger, pi);
                }
            } catch (Exception e) {
                // Fallback aman bila perangkat menolak exact alarm.
                am.setInexactRepeating(AlarmManager.RTC_WAKEUP, trigger, TG_INTERVAL_MS, pi);
            }
        } else {
            am.cancel(pi);
        }
    }
}
