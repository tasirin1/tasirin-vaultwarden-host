package com.vaultwarden.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Backup database ke Telegram + jadwal harian via AlarmManager. */
public final class TgBackup {

    public static final String KEY_TG_TOKEN = "tg_token";
    public static final String KEY_TG_CHAT = "tg_chat";
    public static final String KEY_TG_AUTO = "tg_auto";
    public static final String KEY_TG_LAST = "tg_last_backup";
    public static final long TG_INTERVAL_MS = 24L * 3600 * 1000;

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

        File zip = createBackupZip(dataDir);
        uploadTelegram(token, chat, zip);
        sp.edit().putLong(KEY_TG_LAST, System.currentTimeMillis()).apply();
        return "Backup terkirim ke Telegram \u2713 (" + zip.getName() + ", " + zip.length() + " bytes)";
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

    private static void uploadTelegram(String token, String chatId, File file) throws Exception {
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
