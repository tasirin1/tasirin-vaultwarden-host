package com.vaultwarden.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Backup database ke Telegram + jadwal harian via AlarmManager. */
public final class TgBackup {

    public static final String KEY_TG_TOKEN = "tg_token";
    public static final String KEY_TG_CHAT = "tg_chat";
    public static final String KEY_TG_AUTO = "tg_auto";
    public static final String KEY_TG_BACKUP_ON_START = "tg_backup_on_start";
    public static final String KEY_TG_LAST = "tg_last_backup";
    public static final String KEY_TG_PASS = "tg_pass";
    public static final String KEY_TG_LAST_FILE = "tg_last_file";
    public static final String KEY_TG_LAST_NAME = "tg_last_name";
    public static final long TG_INTERVAL_MS = 24L * 3600 * 1000;

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
        String dataDir = sp.getString(ServerService.KEY_DATA_DIR, "/sdcard/vaultwarden");
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = "/sdcard/vaultwarden";
        }
        File db = new File(dataDir, "db.sqlite3");
        if (!db.exists()) {
            throw new IOException("Database belum ada.");
        }

        long free = freeBytes(dataDir);
        if (free < 10L * 1024 * 1024) {
            throw new IOException("Sisa penyimpanan tinggal " + (free / 1048576)
                    + " MB - backup dibatalkan.");
        }

        File zip = createBackupZip(dataDir);
        File upload = zip;
        String pass = sp.getString(KEY_TG_PASS, "");
        if (pass != null && !pass.trim().isEmpty()) {
            File enc = new File(zip.getParentFile(), zip.getName() + ".enc");
            encryptFile(zip, enc, pass.trim());
            zip.delete();
            upload = enc;
        }
        String resp = uploadTelegram(token, chat, upload);
        String fileId = extractFileId(resp);
        sp.edit()
                .putLong(KEY_TG_LAST, System.currentTimeMillis())
                .putString(KEY_TG_LAST_FILE, fileId)
                .putString(KEY_TG_LAST_NAME, upload.getName())
                .apply();
        return "Backup terkirim ke Telegram \u2713 (" + upload.getName() + ", "
                + upload.length() + " bytes)";
    }

    /** Kirim pesan teks ke chat ID yang dikonfigurasi; silent bila belum diisi. */
    public static void sendMessage(Context ctx, String text) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
            String token = sp.getString(KEY_TG_TOKEN, "").trim();
            String chat = sp.getString(KEY_TG_CHAT, "").trim();
            if (token.isEmpty() || chat.isEmpty()) {
                return;
            }
            String url = TG_API + token + "/sendMessage?chat_id="
                    + URLEncoder.encode(chat, "UTF-8")
                    + "&text=" + URLEncoder.encode(text, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.getInputStream().close();
            conn.disconnect();
        } catch (Exception ignored) {
        }
    }

    /** Zip db + WAL ke <data>/backups/, lalu bersihkan backup lama (sisakan 10). */
    private static File createBackupZip(String dataDir) throws Exception {
        File dataFolder = new File(dataDir);
        File backupDir = new File(dataFolder, "backups");
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            throw new IOException("Gagal membuat folder backup");
        }
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File zip = new File(backupDir, "backup-telegram-" + ts + ".zip");
        String[] names = {"db.sqlite3", "db.sqlite3-wal", "db.sqlite3-shm"};
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            byte[] buf = new byte[64 * 1024];
            for (String name : names) {
                File f = new File(dataFolder, name);
                if (!f.exists() || f.length() == 0) {
                    continue;
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
        }
        cleanupOldBackups(backupDir);
        return zip;
    }

    /** Hapus backup terlama di folder backups, sisakan KEEP_BACKUPS terbaru. */
    public static void cleanupOldBackups(File backupDir) {
        File[] files = backupDir.listFiles();
        if (files == null) {
            return;
        }
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

    private static String uploadTelegram(String token, String chatId, File file) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(TG_API + token + "/sendDocument").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(180000);
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
        conn.disconnect();
        if (code != 200 || !sb.toString().contains("\"ok\":true")) {
            throw new IOException("Telegram HTTP " + code + ": " + sb);
        }
        return sb.toString();
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
        String path = getFilePath(token, fileId);
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.telegram.org/file/bot" + token + "/" + path).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(true);
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
        conn.disconnect();
        return name;
    }

    private static String getFilePath(String token, String fileId) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.telegram.org/bot" + token + "/getFile?file_id=" + fileId).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("getFile gagal (HTTP " + code + ")");
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        conn.disconnect();
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

    public static void encryptFile(File in, File out, String pass) throws Exception {
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(iv);
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

    public static void decryptFile(File in, File out, String pass) throws Exception {
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
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(pass, salt), "AES"),
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
        PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), salt, 100000, 256);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
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

    // ─── Util ───────────────────────────────────────────────────────────

    /** Sisa ruang penyimpanan (bytes) pada partisi path, atau MAX bila gagal. */
    public static long freeBytes(String dirPath) {
        try {
            StatFs sf = new StatFs(dirPath);
            return sf.getAvailableBytes();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /** Jadwalkan backup harian via AlarmManager (atau batalkan bila enable=false). */
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
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + TG_INTERVAL_MS,
                    TG_INTERVAL_MS, pi);
        } else {
            am.cancel(pi);
        }
    }
}
