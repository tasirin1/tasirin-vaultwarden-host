package com.vaultwarden.android;

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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Remote kontrol Vaultwarden Host lewat Telegram bot (long polling getUpdates). */
public final class TgBot {

    public static final String ACTION_POLL = "com.vaultwarden.android.TG_POLL";
    private static final String KEY_TG_OFFSET = "tg_bot_offset";
    private static final long POLL_INTERVAL_MS = 60_000;

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
        long trigger = SystemClock.elapsedRealtime() + 30_000;
        am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, POLL_INTERVAL_MS, pi);
    }

    /** Cek perintah baru dari bot & balas; silent bila bot/chat belum diisi. */
    public static void pollOnce(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
            String token = sp.getString(TgBackup.KEY_TG_TOKEN, "").trim();
            String chat = sp.getString(TgBackup.KEY_TG_CHAT, "").trim();
            if (token.isEmpty() || chat.isEmpty()) {
                return;
            }
            long offset = sp.getLong(KEY_TG_OFFSET, 0);
            String url = TG_API + token + "/getUpdates?offset=" + offset + "&timeout=0&limit=10";
            String body = httpGet(url);
            if (body == null) {
                return;
            }
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("result");
            long newOffset = offset;
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject upd = arr.optJSONObject(i);
                    if (upd == null) {
                        continue;
                    }
                    newOffset = Math.max(newOffset, upd.optLong("update_id", 0) + 1);
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
                        String text = msg.optString("text", "").trim();
                        handleCommand(ctx, text);
                    }
                }
            }
            if (newOffset != offset) {
                sp.edit().putLong(KEY_TG_OFFSET, newOffset).apply();
            }
        } catch (Exception ignored) {
        }
    }

    private static void handleCommand(Context ctx, String text) {
        if (!text.startsWith("/")) {
            return;
        }
        String cmd = text.split("\\s+")[0].toLowerCase(Locale.US);
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
                runWithWakeLock(ctx, () -> {
                    try {
                        String msg = Updater.tryUpdate(ctx);
                        if (msg.startsWith("Update v")) {
                            TgBackup.sendMessage(ctx, msg + " Restart otomatis...");
                            ServerService.restart(ctx);
                        } else {
                            TgBackup.sendMessage(ctx, msg);
                        }
                    } catch (Exception e) {
                        TgBackup.sendMessage(ctx, "Update gagal: " + e.getMessage());
                    }
                });
                break;
            case "/webvault":
                runWithWakeLock(ctx, () -> {
                    try {
                        String msg = Updater.updateWebVault(ctx);
                        TgBackup.sendMessage(ctx, msg + " Restart server (/restart) agar berlaku.");
                    } catch (Exception e) {
                        TgBackup.sendMessage(ctx, "Update web-vault gagal: " + e.getMessage());
                    }
                });
                break;
            case "/help":
                TgBackup.sendMessage(ctx, "Perintah: /status  /log  /uptime  /alive  /backup\n"
                        + "/update  /webvault  /restart  /start  /stop  /help");
                break;
            default:
                TgBackup.sendMessage(ctx, "Perintah tidak dikenal. Ketik /help");
        }
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
        String all;
        synchronized (ServerService.logBuffer) {
            all = ServerService.logBuffer.toString();
        }
        if (all.isEmpty()) {
            return "Log kosong.";
        }
        String[] lines = all.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, lines.length - 30);
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        String log = sb.toString();
        return log.length() > 3500 ? "..." + log.substring(log.length() - 3500) : log;
    }

    private static String durationText(long ms) {
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
        return "Vaultwarden Host\n"
                + "Status: " + (ServerService.running ? "Running" : "Stopped") + "\n"
                + "Versi: " + version + "\n"
                + "Data: " + dataDir + "\n"
                + "DB: " + dbInfo + "\n"
                + "URL: " + ServerService.localUrl(ctx);
    }

    private static PendingIntent pendingIntent(Context ctx) {
        Intent i = new Intent(ctx, TgBotReceiver.class).setAction(ACTION_POLL);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, 3, i, flags);
    }

    private static String httpGet(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            if (is == null) {
                c.disconnect();
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
            c.disconnect();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
