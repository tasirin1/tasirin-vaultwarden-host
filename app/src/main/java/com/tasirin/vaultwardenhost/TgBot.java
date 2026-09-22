package com.tasirin.vaultwardenhost;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Remote kontrol Vaultwarden Host lewat Telegram bot (long polling getUpdates). */
public final class TgBot {

    public static final String ACTION_POLL = "com.tasirin.vaultwardenhost.TG_POLL";
    static final String KEY_TG_OFFSET = "tg_bot_offset";
    static final String KEY_TG_MENU_HASH = "tg_menu_hash";
    static final int MENU_REV = 2;
    private static final long POLL_INTERVAL_MS = 20_000;
    private static final long STALE_MSG_MS = 5 * 60_000;
    private static final AtomicBoolean POLLING = new AtomicBoolean(false);

    private static final String TG_API = "https://api.telegram.org/bot";

    private TgBot() {
    }

    /** Pasang alarm polling tiap 60 detik; dibatalkan bila bot belum dikonfigurasi. */
    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pendingIntent(ctx);
        am.cancel(pi);
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String token = sp.getString(TgBackup.KEY_TG_TOKEN, "").trim();
        if (token.isEmpty()) {
            return;
        }
        long trigger = SystemClock.elapsedRealtime() + 10_000;
        // Inexact: boleh di-batch sistem dengan alarm lain (hemat baterai);
        // long-poll 15 dtk + alarm 20 dtk = perintah dibaca < 1 menit.
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger,
                POLL_INTERVAL_MS, pi);
        refreshMenuAsync(ctx);
    }

    /** Daftar perintah untuk menu bot Telegram (tombol `/`). */
    static String[][] daftarPerintahMenu() {
        return new String[][]{
                {"status", "Status lengkap server"},
                {"log", "Potongan log terakhir"},
                {"uptime", "Lama server berjalan"},
                {"alive", "Cek sehat HTTP /alive"},
                {"backup", "Backup database sekarang"},
                {"restore", "Restore backup terakhir"},
                {"crashlog", "Kirim crash log terakhir"},
                {"update", "Update binary + restart"},
                {"webvault", "Update web vault + restart"},
                {"start", "Start server"},
                {"stop", "Stop server"},
                {"restart", "Restart server"},
                {"help", "Daftar perintah"},
        };
    }

    /** True bila pesan hasil update web-vault berarti file berubah (perlu restart). */
    static boolean webVaultBerubah(String msg) {
        return msg != null && msg.contains("updated");
    }

    /** Payload JSON setMyCommands (string manual agar bisa di-unit-test JVM). */
    static String menuPayload() {
        StringBuilder sb = new StringBuilder("{\"commands\":[");
        String[][] daftar = daftarPerintahMenu();
        for (int i = 0; i < daftar.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"command\":\"").append(daftar[i][0])
                    .append("\",\"description\":\"").append(daftar[i][1]).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Daftarkan menu perintah ke BotFather API (best-effort, sekali per token). */
    static void refreshMenuAsync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        final String token = sp.getString(TgBackup.KEY_TG_TOKEN, "").trim();
        if (token.isEmpty()) {
            return;
        }
        final int hash = token.hashCode() * 31 + MENU_REV;
        if (sp.getInt(KEY_TG_MENU_HASH, 0) == hash) {
            return;
        }
        final String payload = menuPayload();
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                byte[] body = payload.getBytes(StandardCharsets.UTF_8);
                c = (HttpURLConnection) new URL(TG_API + token + "/setMyCommands")
                        .openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Content-Length", String.valueOf(body.length));
                HttpsCompat.apply(c, app);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
                int code = c.getResponseCode();
                InputStream is = (code >= 200 && code < 300)
                        ? c.getInputStream() : c.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (is != null) {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String baris;
                        while ((baris = r.readLine()) != null) {
                            sb.append(baris);
                        }
                    }
                }
                if (code == 200 && sb.toString().contains("\"ok\":true")) {
                    app.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_TG_MENU_HASH, hash).apply();
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }, "vw-tgmenu").start();
    }

    /** Cek perintah baru dari bot & balas; silent bila bot/chat belum diisi. */
    public static void pollOnce(Context ctx) {
        // Long-poll vs alarm bisa tumpang tindih: satu saja jalan.
        if (!POLLING.compareAndSet(false, true)) {
            return;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
            String token = sp.getString(TgBackup.KEY_TG_TOKEN, "").trim();
            String chat = sp.getString(TgBackup.KEY_TG_CHAT, "").trim();
            if (token.isEmpty() || chat.isEmpty()) {
                return;
            }
            long offset = sp.getLong(KEY_TG_OFFSET, 0);
            String url = TG_API + token + "/getUpdates?offset=" + offset + "&timeout=15&limit=10";
            String body = httpGet(ctx, url);
            if (body == null) {
                return;
            }
            long newOffset = offset;
            try {
                JSONObject root = new JSONObject(body);
                JSONArray arr = root.optJSONArray("result");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject upd = arr.optJSONObject(i);
                        if (upd == null) {
                            continue;
                        }
                        newOffset = Math.max(newOffset, upd.optLong("update_id", 0) + 1);
                        try {
                            JSONObject cb = upd.optJSONObject("callback_query");
                            if (cb != null) {
                                tanganiCallback(ctx, cb, chat.trim());
                                continue;
                            }
                            JSONObject msg = upd.optJSONObject("message");
                            if (msg == null) {
                                continue;
                            }
                            JSONObject c = msg.optJSONObject("chat");
                            if (c == null) {
                                continue;
                            }
                            // Hanya layani chat yang dikonfigurasi di pengaturan
                            if (String.valueOf(c.optLong("id", -1)).equals(chat.trim())) {
                                // Perintah basi (>5 mnt, mis. /stop tertunda saat bot
                                // offline) wajib diabaikan; offset tetap maju.
                                long dateMs = msg.optLong("date", 0) * 1000L;
                                if (dateMs > 0 && System.currentTimeMillis() - dateMs
                                        > STALE_MSG_MS) {
                                    continue;
                                }
                                String text = msg.optString("text", "").trim();
                                handleCommand(ctx, text);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            } finally {
                if (newOffset != offset) {
                    sp.edit().putLong(KEY_TG_OFFSET, newOffset).apply();
                }
            }
        } catch (Exception ignored) {
        } finally {
            POLLING.set(false);
        }
    }

    /** Keyboard inline agar tak perlu mengetik perintah (logika murni). */
    static String keyboardPerintah() {
        String[][] tombol = {
                {"Status", "/status"}, {"Log", "/log"},
                {"Uptime", "/uptime"}, {"Sehat", "/alive"},
                {"Backup", "/backup"}, {"Restore", "/restore"},
                {"Crash log", "/crashlog"}, {"Update", "/update"},
                {"Web vault", "/webvault"}, {"Start", "/start"},
                {"Stop", "/stop"}, {"Restart", "/restart"},
                {"Bantuan", "/help"},
        };
        StringBuilder sb = new StringBuilder("{\"inline_keyboard\":[");
        for (int i = 0; i < tombol.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[');
            for (int j = i; j < i + 2 && j < tombol.length; j++) {
                if (j > i) {
                    sb.append(',');
                }
                sb.append("{\"text\":\"").append(tombol[j][0])
                        .append("\",\"callback_data\":\"").append(tombol[j][1])
                        .append("\"}");
            }
            sb.append(']');
        }
        return sb.append("]}").toString();
    }

    /** True bila data callback adalah perintah bot yang dikenal. */
    static boolean callbackDataValid(String data) {
        if (data == null || !data.startsWith("/")) {
            return false;
        }
        String cmd = data.split("\\s+")[0].toLowerCase(Locale.US).substring(1);
        for (String[] c : daftarPerintahMenu()) {
            if (c[0].equals(cmd)) {
                return true;
            }
        }
        return false;
    }

    /** Balas ketukan tombol inline: hanya dari chat resmi yang dijalankan. */
    private static void tanganiCallback(Context ctx, JSONObject cb, String chatResmi) {
        try {
            String dari = "";
            JSONObject pesan = cb.optJSONObject("message");
            JSONObject ruang = pesan != null ? pesan.optJSONObject("chat") : null;
            if (ruang != null) {
                dari = String.valueOf(ruang.optLong("id", -1));
            } else {
                JSONObject pengirim = cb.optJSONObject("from");
                if (pengirim != null) {
                    dari = String.valueOf(pengirim.optLong("id", -1));
                }
            }
            if (!chatResmi.equals(dari)) {
                return;
            }
            long dateMs = pesan != null ? pesan.optLong("date", 0) * 1000L : 0;
            if (dateMs > 0 && System.currentTimeMillis() - dateMs > STALE_MSG_MS) {
                return;
            }
            jawabCallback(ctx, cb.optString("id", ""));
            String data = cb.optString("data", "").trim();
            if (callbackDataValid(data)) {
                handleCommand(ctx, data);
            }
        } catch (Exception ignored) {
        }
    }

    /** Tutup spinner loading di tombol (best-effort, thread sendiri). */
    private static void jawabCallback(Context ctx, String callbackId) {
        if (callbackId == null || callbackId.isEmpty()) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                SharedPreferences sp = app.getSharedPreferences(ServerService.PREFS,
                        Context.MODE_PRIVATE);
                String token = sp.getString(TgBackup.KEY_TG_TOKEN, "").trim();
                if (token.isEmpty()) {
                    return;
                }
                byte[] body = ("callback_query_id="
                        + URLEncoder.encode(callbackId, "UTF-8"))
                        .getBytes(StandardCharsets.UTF_8);
                c = (HttpURLConnection) new URL(TG_API + token + "/answerCallbackQuery")
                        .openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded");
                c.setRequestProperty("Content-Length", String.valueOf(body.length));
                HttpsCompat.apply(c, app);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
                c.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }, "vw-tgcb").start();
    }

    private static void handleCommand(Context ctx, String text) {
        if (!text.startsWith("/")) {
            return;
        }
        String cmd = text.split("\\s+")[0].toLowerCase(Locale.US);
        String arg = "";
        int space = text.indexOf(' ');
        if (space >= 0) {
            arg = text.substring(space + 1).trim();
        }
        switch (cmd) {
            case "/start":
                if (ServerService.running || ServerService.isProcessAlive()) {
                    TgBackup.sendMessage(ctx, "Server sudah jalan.");
                } else {
                    try {
                        ServerService.start(ctx);
                        TgBackup.sendMessage(ctx, "Perintah diterima: server start...");
                    } catch (Throwable t) {
                        TgBackup.sendMessage(ctx, "Gagal start dari background (batasan Android). "
                                + "Buka app lalu tekan Start, atau aktifkan 'Auto start saat boot' "
                                + "lalu reboot HP.");
                    }
                }
                break;
            case "/stop":
                if (authDangerous(ctx, arg) == null) {
                    break;
                }
                try {
                    ServerService.stop(ctx);
                    TgBackup.sendMessage(ctx, "Perintah diterima: server stop...");
                } catch (Throwable t) {
                    TgBackup.sendMessage(ctx, "Gagal stop dari background (batasan Android). "
                            + "Buka app lalu tekan Stop.");
                }
                break;
            case "/restart":
                try {
                    ServerService.restart(ctx);
                    TgBackup.sendMessage(ctx, "Perintah diterima: server restart...");
                } catch (Throwable t) {
                    TgBackup.sendMessage(ctx, "Restart hanya bisa saat server berjalan.");
                }
                break;
            case "/backup":
                runWithWakeLock(ctx, () -> {
                    try {
                        TgBackup.sendMessage(ctx, TgBackup.backupNow(ctx));
                    } catch (Exception e) {
                        TgBackup.sendMessage(ctx, "Backup gagal: " + e.getMessage());
                    }
                });
                break;
            case "/restore":
                String cleanRestore = authDangerous(ctx, arg);
                if (cleanRestore == null) {
                    break;
                }
                if (isRestoreConfirm(cleanRestore)) {
                    runWithWakeLock(ctx, () -> {
                        try {
                            TgBackup.sendMessage(ctx, "Mengunduh backup terakhir...");
                            TgBackup.sendMessage(ctx, doRestore(ctx));
                        } catch (Exception e) {
                            TgBackup.sendMessage(ctx, "Restore gagal: " + e.getMessage());
                        }
                    });
                } else {
                    TgBackup.sendMessage(ctx, restoreInfoText(ctx));
                }
                break;
            case "/status":
                TgBackup.sendMessage(ctx, statusText(ctx));
                break;
            case "/log":
                TgBackup.sendMessage(ctx, tailLog());
                break;
            case "/uptime":
                long up = ServerService.uptimeMs();
                if (up <= 0) {
                    TgBackup.sendMessage(ctx, "Server sedang berhenti.");
                } else {
                    TgBackup.sendMessage(ctx, "Server jalan selama " + durationText(up) + ".");
                }
                break;
            case "/alive":
                TgBackup.sendMessage(ctx, ServerService.pingAlive(ctx)
                        ? "Server sehat (HTTP 200 /alive)."
                        : "Server TIDAK merespon /alive!");
                break;
            case "/update":
                if (authDangerous(ctx, arg) == null) {
                    break;
                }
                runWithWakeLock(ctx, () -> {
                    try {
                        boolean was = ServerService.running || ServerService.isProcessAlive();
                        String msg = Updater.tryUpdate(ctx);
                        if (KernelCompat.isLegacyDevice(KernelCompat.kernelSekarang())) {
                            try {
                                Updater.ensureShimFile(ctx);
                                msg += " Shim getrandom siap.";
                            } catch (Exception se) {
                                msg += " Shim gagal: " + se.getMessage();
                            }
                        }
                        if (msg.startsWith("Update v")) {
                            if (was) {
                                TgBackup.sendMessage(ctx, msg + " Restart otomatis...");
                                ServerService.restart(ctx);
                            } else {
                                TgBackup.sendMessage(ctx, msg + " Tekan /start untuk memakai.");
                            }
                        } else {
                            TgBackup.sendMessage(ctx, msg);
                        }
                    } catch (Exception e) {
                        String ramah = e.getMessage() != null && e.getMessage().contains("Cek ")
                                ? e.getMessage() : Updater.pesanGalatUnduh("Unduh binary", e);
                        TgBackup.sendMessage(ctx, "Update gagal: " + ramah);
                    }
                });
                break;
            case "/webvault":
                runWithWakeLock(ctx, () -> {
                    try {
                        boolean was = ServerService.running || ServerService.isProcessAlive();
                        String msg = Updater.updateWebVault(ctx);
                        if (webVaultBerubah(msg)) {
                            if (was) {
                                TgBackup.sendMessage(ctx, msg + " Restart otomatis...");
                                ServerService.restart(ctx);
                            } else {
                                TgBackup.sendMessage(ctx, msg + " Tekan /start untuk memakai.");
                            }
                        } else {
                            TgBackup.sendMessage(ctx, msg);
                        }
                    } catch (Exception e) {
                        String ramah = e.getMessage() != null && e.getMessage().contains("Cek ")
                                ? e.getMessage() : Updater.pesanGalatUnduh("Unduh web-vault", e);
                        TgBackup.sendMessage(ctx, "Update web-vault gagal: " + ramah);
                    }
                });
                break;
            case "/crashlog":
                String crash = ServerService.crashLogText(ctx);
                if (crash == null || crash.trim().isEmpty()) {
                    TgBackup.sendMessage(ctx, "Belum ada crash log tersimpan.");
                } else {
                    TgBackup.sendMessage(ctx, crash.length() > 3500
                            ? crash.substring(crash.length() - 3500) : crash);
                }
                break;
            case "/help":
                TgBackup.sendMessageKb(ctx, "Perintah: /status  /log  /uptime  /alive  /backup  /restore\n"
                        + "/crashlog  /update  /webvault  /restart  /start  /stop  /help\n"
                        + "Ketuk tombol di bawah agar tak perlu mengetik.\n"
                        + "Bila PIN app aktif, /stop /update /restore wajib diakhiri PIN"
                        + " (mis. /stop 123456).", keyboardPerintah());
                break;
            default:
                TgBackup.sendMessage(ctx, "Perintah tidak dikenal. Ketik /help");
        }
    }

    /** True bila argumen /restore adalah kata konfirmasi (YA/YES/Y/OK/KONFIRMASI/LANJUT). */
    static boolean isRestoreConfirm(String arg) {
        if (arg == null) {
            return false;
        }
        String a = arg.trim().toLowerCase(Locale.US);
        return a.equals("ya") || a.equals("yes") || a.equals("y")
                || a.equals("ok") || a.equals("konfirmasi") || a.equals("lanjut");
    }

    /** Teks konfirmasi /restore: info backup terakhir + cara konfirmasi. */
    static String restoreInfoText(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String name = sp.getString(TgBackup.KEY_TG_LAST_NAME, "");
        String fileId = sp.getString(TgBackup.KEY_TG_LAST_FILE, "");
        long last = sp.getLong(TgBackup.KEY_TG_LAST, 0);
        if ((name == null || name.isEmpty()) && (fileId == null || fileId.isEmpty())) {
            return "Belum ada backup terkirim dari app ini. Kirim /backup dulu.";
        }
        if (name == null || name.isEmpty()) {
            name = "backup terakhir";
        }
        String tgl = last > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(last))
                : "?";
        return "Backup terakhir: " + name + " (" + tgl + ").\n"
                + "Server akan dihentikan & database ditimpa."
                + " Balas /restore YA untuk lanjut.";
    }

    /** Unduh backup terakhir dari Telegram lalu restore; kembalikan ringkasan hasil. */
    static String doRestore(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String pass = sp.getString(TgBackup.KEY_TG_PASS, "");
        File tmp = new File(ctx.getCacheDir(), "vwtg-restore-bot.zip");
        TgBackup.downloadLastBackup(ctx, tmp);
        File zip = tmp;
        if (TgBackup.isEncrypted(tmp)) {
            if (pass == null || pass.trim().isEmpty()) {
                throw new IOException("Backup terenkripsi"
                        + " - isi password backup di pengaturan dulu.");
            }
            File plain = new File(ctx.getCacheDir(), "vwtg-restore-bot-dec.zip");
            TgBackup.decryptFile(tmp, plain, pass.trim());
            tmp.delete();
            zip = plain;
        }
        return TgBackup.restoreFromZip(ctx, zip);
    }

    /** Otorisasi perintah berbahaya (/stop, /update, /restore).
     *  Bila PIN app aktif, kata terakhir argumen wajib PIN yang benar;
     *  kembalikan argumen bersih (tanpa PIN), atau null (pesan sudah dikirim). */
    static String authDangerous(Context ctx, String arg) {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String hash = sp.getString("pin_hash", "");
        boolean need = sp.getBoolean("pin_on", false)
                && hash != null && !hash.isEmpty();
        String t = arg == null ? "" : arg.trim();
        if (!need) {
            return t;
        }
        int i = t.lastIndexOf(' ');
        String pin = i < 0 ? t : t.substring(i + 1);
        String rest = i < 0 ? "" : t.substring(0, i).trim();
        if (!pin.isEmpty() && PinCrypto.verify(hash, pin)) {
            return rest;
        }
        TgBackup.sendMessage(ctx, "Perintah ini butuh PIN app di akhir"
                + " (mis. /stop 123456). Aktifkan PIN di pengaturan bila belum.");
        return null;
    }

    /** Jalankan tugas berat di thread sendiri + partial wake lock. */
    private static void runWithWakeLock(Context ctx, Runnable task) {
        new Thread(() -> {
            PowerManager.WakeLock wl = null;
            try {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vaultwarden:tgbot-task");
                wl.acquire(10 * 60 * 1000L);
                task.run();
            } finally {
                if (wl != null && wl.isHeld()) {
                    wl.release();
                }
            }
        }, "vw-tgbot-task").start();
    }

    /** 30 baris terakhir log (maks ~3500 karakter, batas aman Telegram). */
    private static String tailLog() {
        String log = ServerService.tailLog(30);
        if (log.isEmpty()) {
            return "Log kosong.";
        }
        return log.length() > 3500 ? "..." + log.substring(log.length() - 3500) : log;
    }

    static String durationText(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        if (h > 0) {
            return h + " jam " + m + " menit";
        }
        if (m > 0) {
            return m + " menit " + (s % 60) + " detik";
        }
        return s + " detik";
    }

    /** Ringkasan status untuk dibalas ke Telegram. */
    static String statusText(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String dataDir = sp.getString(ServerService.KEY_DATA_DIR, ServerService.DEFAULT_DATA_DIR);
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = ServerService.DEFAULT_DATA_DIR;
        }
        String version = ServerService.binaryVersion.isEmpty()
                ? "?" : ServerService.binaryVersion;
        File db = new File(dataDir, "db.sqlite3");
        String dbInfo = db.exists() ? TgBackup.humanBytes(db.length()) : "belum ada";
        long lastBackup = sp.getLong(TgBackup.KEY_TG_LAST, 0);
        String backupInfo = lastBackup > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(lastBackup))
                : "belum pernah";
        String wv = Updater.webVaultFromVersion(ctx);
        String wvInfo = wv != null ? wv : "belum ada";
        long rss = ServerService.processRssKb();
        String ram = rss > 0 ? TgBackup.humanBytes(rss * 1024) : "?";
        long up = ServerService.uptimeMs();
        String uptime = up > 0 ? durationText(up) : "-";
        long free = TgBackup.freeBytes(dataDir);
        String restarts = ServerService.restartSummary();
        StringBuilder sb = new StringBuilder();
        sb.append("Vaultwarden Host\n")
                .append("Status: ").append(ServerService.running ? "Running" : "Stopped").append("\n")
                .append("Versi: ").append(version).append("\n")
                .append("Web vault: ").append(wvInfo).append("\n")
                .append("Data: ").append(dataDir).append("\n")
                .append("DB: ").append(dbInfo).append("\n")
                .append("Backup TG: ").append(backupInfo).append("\n")
                .append("RAM: ").append(ram).append("\n")
                .append("Uptime: ").append(uptime).append("\n")
                .append("Sisa: ").append(free < 0 ? "?" : TgBackup.humanBytes(free)).append("\n")
                .append("URL: ").append(ServerService.localUrl(ctx));
        if (!restarts.isEmpty()) {
            sb.append("\n").append(restarts);
        }
        return sb.toString();
    }

    private static PendingIntent pendingIntent(Context ctx) {
        Intent i = new Intent(ctx, TgBotReceiver.class).setAction(ACTION_POLL);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, 3, i, flags);
    }

    private static String httpGet(Context ctx, String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(70000);
            c.setRequestMethod("GET");
            HttpsCompat.apply(c, ctx);
            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            if (is == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
