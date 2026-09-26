package com.tasirin.vaultwardenhost;

import android.annotation.SuppressLint;
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
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
    static final int MENU_REV = 3;
    private static final long POLL_INTERVAL_MS = 20_000;
    private static final long STALE_MSG_MS = 5 * 60_000;
    private static final AtomicBoolean POLLING = new AtomicBoolean(false);
    /** Kunci tugas berat bot agar backup/restore/update tak jalan bersamaan. */
    private static final AtomicBoolean TUGAS_BERAT = new AtomicBoolean(false);
    /** Wall-clock terbesar yang pernah terlihat (deteksi jam mundur/NTP).
     *  Bila jam mundur jauh, tombol/pesan basi diperlakukan kedaluwarsa (fail-closed). */
    private static volatile long wallMaksTelegram = 0;

    private static final String TG_API = "https://api.telegram.org/bot";

    // Satu pool kecil untuk tugas bot (perintah, callback, menu): hemat thread
    // dibanding new Thread per ketukan/perintah di STB 1 GB.
    // Antrean dibatasi agar spam update tak menumpuk OOM di STB 1 GB.
    private static final java.util.concurrent.ExecutorService BG =
            new java.util.concurrent.ThreadPoolExecutor(3, 3, 0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<Runnable>(32), r -> {
                Thread t = new Thread(r, "vw-tgbot-bg");
                t.setDaemon(true);
                return t;
            }, new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());

    /** Kirim tugas bot tanpa lempar bila antrean penuh (fail-safe STB). */
    static void jalankanBg(Runnable r) {
        try {
            BG.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        } catch (Exception ignored) {
        }
    }

    private TgBot() {
    }

    /** Pasang alarm polling tiap 20 detik; dibatalkan bila bot belum dikonfigurasi. */
    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String token = Util.amanTrim(sp.getString(TgBackup.KEY_TG_TOKEN, ""));
        PendingIntent pi = pendingIntent(ctx);
        if (token.isEmpty()) {
            am.cancel(pi);
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
                {"ca", "Kirim CA HTTPS ke chat ini"},
                {"crashlog", "Kirim crash log terakhir"},
                {"update", "Update binary + restart"},
                {"webvault", "Update web vault + restart"},
                {"start", "Start server"},
                {"stop", "Stop server"},
                {"restart", "Restart server"},
                {"help", "Daftar perintah"},
        };
    }

    /** True bila pesan hasil update web-vault berarti file berubah (perlu restart).
     *  Cek marker mesin dulu, fallback substring lama untuk pesan versi lama. */
    static boolean webVaultBerubah(String msg) {
        if (msg == null) {
            return false;
        }
        if (msg.contains(Updater.WV_UPDATED_MARKER)) {
            return true;
        }
        return msg.contains("updated");
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
        final String token = Util.amanTrim(sp.getString(TgBackup.KEY_TG_TOKEN, ""));
        if (token.isEmpty()) {
            return;
        }
        final int hash = token.hashCode() * 31 + MENU_REV;
        if (sp.getInt(KEY_TG_MENU_HASH, 0) == hash) {
            return;
        }
        final String payload = menuPayload();
        jalankanBg(() -> {
            HttpURLConnection c = null;
            try {
                byte[] body = payload.getBytes(StandardCharsets.UTF_8);
                c = TgBackup.bukaPostTelegram(app, TG_API + token + "/setMyCommands",
                        body, "application/json", 15000, 30000);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
                TgBackup.tolakRedirectTelegram(c);
                int code = c.getResponseCode();
                InputStream is = (code >= 200 && code < 300)
                        ? c.getInputStream() : c.getErrorStream();
                String balasan = is != null ? TgBackup.bacaResponsBatas(is) : "";
                if (code == 200 && balasan.contains("\"ok\":true")) {
                    app.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_TG_MENU_HASH, hash).apply();
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        });
    }

    /** Cek perintah baru dari bot & balas; silent bila bot/chat belum diisi. */
    // commit() offset di bawah disengaja (sinkron anti-replay, lihat komentar) — bukan apply().
    @SuppressLint("ApplySharedPref")
    public static void pollOnce(Context ctx) {
        // Long-poll vs alarm bisa tumpang tindih: satu saja jalan.
        if (!POLLING.compareAndSet(false, true)) {
            return;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
            String token = Util.amanTrim(sp.getString(TgBackup.KEY_TG_TOKEN, ""));
            String chat = Util.amanTrim(sp.getString(TgBackup.KEY_TG_CHAT, ""));
            if (token.isEmpty() || chat.isEmpty()) {
                return;
            }
            long offset = sp.getLong(KEY_TG_OFFSET, 0);
            String chatResmi = chat.trim();
            // POST (bukan GET): token bot tidak bocor ke log URL/proxy.
            String body = httpPostForm(ctx, TG_API + token + "/getUpdates",
                    "offset=" + offset + "&timeout=15&limit=10");
            if (body == null) {
                return;
            }
            long newOffset = offset;
            int rollbackDitolak = 0;
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
                            // Hanya layani chat resmi (ID numerik atau @username).
                            String namaUser = c.optString("username", "");
                            if (Util.cocokChat(chatResmi, c.optLong("id", -1), namaUser)) {
                                // Perintah basi (>5 mnt, mis. /stop tertunda saat bot
                                // offline) wajib diabaikan; offset tetap maju.
                                // Pesan masa depan (jam STB lambat) jangan dibuang.
                                long dateMs = msg.optLong("date", 0) * 1000L;
                                long kini = System.currentTimeMillis();
                                if (jamMundur(kini)) {
                                    rollbackDitolak++;
                                    continue;
                                }
                                catatWall(kini);
                                if (!Util.pesanSegar(dateMs, kini,
                                        STALE_MSG_MS)) {
                                    continue;
                                }
                                String text = msg.optString("text", "").trim();
                                handleCommand(ctx, text);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (rollbackDitolak > 0) {
                    TgBackup.sendMessage(ctx, "Jam STB mundur drastis terdeteksi;"
                            + " " + rollbackDitolak + " perintah diabaikan demi keamanan."
                            + " Periksa tanggal & jam STB, lalu kirim ulang perintah.");
                }
            } finally {
                if (newOffset != offset) {
                    // commit() sinkron: offset wajib awet sebelum perintah
                    // berikutnya dibaca agar /stop-restore tak replay bila
                    // proses mati tepat setelah polling.
                    sp.edit().putLong(KEY_TG_OFFSET, newOffset).commit();
                }
            }
        } catch (Exception ignored) {
        } finally {
            POLLING.set(false);
        }
    }

    /** Parse chat ID konfigurasi sekali; Long.MIN_VALUE bila tak numerik
     *  (= tak ada pesan yang cocok, aman). Murni agar bisa unit test. */
    static long parseChatId(String chat) {
        if (chat == null) {
            return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(chat.trim());
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
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
                {"Bantuan", "/help"}, {"CA", "/ca"},
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

    /** Nama perintah tanpa `/`, argumen, dan imbuhan `@namabot` grup. Murni. */
    static String namaPerintah(String text) {
        if (text == null) {
            return "";
        }
        String pertama = text.trim().split("\\s+")[0].toLowerCase(Locale.US);
        if (pertama.startsWith("/")) {
            pertama = pertama.substring(1);
        }
        int at = pertama.indexOf('@');
        if (at >= 0) {
            pertama = pertama.substring(0, at);
        }
        return pertama;
    }

    /** True bila data callback adalah perintah bot yang dikenal. */
    static boolean callbackDataValid(String data) {
        if (data == null || !data.trim().startsWith("/")) {
            return false;
        }
        String cmd = namaPerintah(data);
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
            String namaRuang = ruang != null ? ruang.optString("username", "") : "";
            if (!Util.cocokChat(chatResmi, ruang != null ? ruang.optLong("id", -1) : -1,
                    namaRuang)
                    && !chatResmi.equals(dari)) {
                return;
            }
            // Tombol inline diberi umur 24 jam: tanpa batas, keyboard lama yang
            // bocor bisa di-replay selamanya. Batas 5 menit (seperti pesan ketik)
            // terlalu pendek untuk tombol, 24 jam komprominya.
            // Tanpa message tak ada tanggal: tolak agar tak bisa di-replay selamanya.
            if (pesan == null) {
                jawabCallback(ctx, cb.optString("id", ""));
                TgBackup.sendMessage(ctx, "Tombol sudah kedaluwarsa (>24 jam)."
                        + " Minta keyboard baru dengan /help lalu coba lagi.");
                return;
            }
            if (pesan != null) {
                long tgl = pesan.optLong("date", 0) * 1000L;
                long kini = System.currentTimeMillis();
                if (jamMundur(kini)) {
                    jawabCallback(ctx, cb.optString("id", ""));
                    TgBackup.sendMessage(ctx, "Tombol ditolak: jam STB mundur drastis."
                            + " Minta keyboard baru dengan /help lalu coba lagi.");
                    return;
                }
                catatWall(kini);
                if (tombolKedaluwarsa(tgl, kini)) {
                    jawabCallback(ctx, cb.optString("id", ""));
                    TgBackup.sendMessage(ctx, "Tombol sudah kedaluwarsa (>24 jam)."
                            + " Minta keyboard baru dengan /help lalu coba lagi.");
                    return;
                }
            }
            jawabCallback(ctx, cb.optString("id", ""));
            String data = cb.optString("data", "").trim();
            if (callbackDataValid(data)) {
                if (perluPinTombol(ctx, data)) {
                    TgBackup.sendMessage(ctx, "Tombol tak bisa membawa PIN."
                            + " Ketik manual mis. " + data + " 123456.");
                } else {
                    handleCommand(ctx, data);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** True bila perintah tombol wajib PIN tapi tak bisa dibawa tombol.
     *  Perintah berbahaya butuh PIN di akhir argumen; tombol hanya kirim
     *  "/perintah" tanpa PIN sehingga selalu ditolak bila PIN aktif. */
    static boolean perintahBerbahayaTombol(String data) {
        String cmd = namaPerintah(data);
        return cmd.equals("start") || cmd.equals("stop") || cmd.equals("restart")
                || cmd.equals("backup") || cmd.equals("restore") || cmd.equals("log")
                || cmd.equals("crashlog") || cmd.equals("update")
                || cmd.equals("webvault");
    }

    /** True bila tombol ini tak bisa jalan karena PIN aktif (beri tahu user). */
    static boolean perluPinTombol(android.content.Context ctx, String data) {
        if (!perintahBerbahayaTombol(data)) {
            return false;
        }
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(
                    ServerService.PREFS, android.content.Context.MODE_PRIVATE);
            String hash = sp.getString("pin_hash", "");
            return sp.getBoolean("pin_on", false) && hash != null && !hash.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Tutup spinner loading di tombol (best-effort, thread sendiri). */
    private static void jawabCallback(Context ctx, String callbackId) {
        if (callbackId == null || callbackId.isEmpty()) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        jalankanBg(() -> {
            HttpURLConnection c = null;
            try {
                SharedPreferences sp = app.getSharedPreferences(ServerService.PREFS,
                        Context.MODE_PRIVATE);
                String token = Util.amanTrim(sp.getString(TgBackup.KEY_TG_TOKEN, ""));
                if (token.isEmpty()) {
                    return;
                }
                byte[] body = ("callback_query_id="
                        + URLEncoder.encode(callbackId, "UTF-8"))
                        .getBytes(StandardCharsets.UTF_8);
                c = TgBackup.bukaPostTelegram(app,
                        TG_API + token + "/answerCallbackQuery", body,
                        "application/x-www-form-urlencoded", 15000, 30000);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
                TgBackup.tolakRedirectTelegram(c);
                c.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        });
    }

    private static void handleCommand(Context ctx, String text) {
        if (!text.startsWith("/")) {
            return;
        }
        String cmd = "/" + namaPerintah(text);
        String arg = "";
        int space = text.indexOf(' ');
        if (space >= 0) {
            arg = text.substring(space + 1).trim();
        }
        switch (cmd) {
            case "/start":
                if (authDangerous(ctx, arg) == null) {
                    break;
                }
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
                if (authDangerous(ctx, arg) == null) {
                    break;
                }
                try {
                    ServerService.restart(ctx);
                    TgBackup.sendMessage(ctx, "Perintah diterima: server restart...");
                } catch (Throwable t) {
                    TgBackup.sendMessage(ctx, "Restart hanya bisa saat server berjalan.");
                }
                break;
            case "/backup":
                if (authDangerous(ctx, arg) == null) {
                    break;
                }
                runBeratDenganKunci(ctx, () -> {
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
                    runBeratDenganKunci(ctx, () -> {
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
            case "/ca":
                // CA publik (tanpa kunci privat): tanpa PIN seperti /status.
                runWithWakeLock(ctx, () -> {
                    try {
                        TgBackup.sendMessage(ctx, TgBackup.kirimCa(ctx));
                    } catch (Exception e) {
                        TgBackup.sendMessage(ctx, "Kirim CA gagal: " + e.getMessage());
                    }
                });
                break;
            case "/status":
                TgBackup.sendMessage(ctx, statusText(ctx));
                break;
            case "/log":
                if (authDangerous(ctx, arg) == null) {
                    break;
                }
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
                runBeratDenganKunci(ctx, () -> {
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
                if (authDangerous(ctx, arg) == null) {
                    break;
                }
                runBeratDenganKunci(ctx, () -> {
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
                if (authDangerous(ctx, arg) == null) {
                    break;
                }
                String crash = ServerService.crashLogText(ctx);
                if (crash == null || crash.trim().isEmpty()) {
                    TgBackup.sendMessage(ctx, "Belum ada crash log tersimpan.");
                } else {
                    TgBackup.sendMessage(ctx, crash.length() > 3500
                            ? crash.substring(crash.length() - 3500) : crash);
                }
                break;
            case "/help":
                TgBackup.sendMessageKb(ctx, "Perintah: /status  /log  /uptime  /alive  /backup  /restore  /ca\n"
                        + "/crashlog  /update  /webvault  /restart  /start  /stop  /help\n"
                        + "Ketuk tombol di bawah agar tak perlu mengetik.\n"
                        + "Bila PIN app aktif, /start /stop /restart /backup /log /crashlog /update /webvault /restore wajib diakhiri PIN"
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
        File plain = null;
        if (TgBackup.isEncrypted(tmp)) {
            if (pass == null || pass.trim().isEmpty()) {
                throw new IOException("Backup terenkripsi"
                        + " - isi password backup di pengaturan dulu.");
            }
            plain = new File(ctx.getCacheDir(), "vwtg-restore-bot-dec.zip");
            TgBackup.decryptFile(tmp, plain, pass.trim());
            tmp.delete();
            zip = plain;
        }
        try {
            return TgBackup.restoreFromZip(ctx, zip);
        } finally {
            // Jangan sisakan plaintext dekrip di cache bila restore gagal
            // (jalur sukses sudah dihapus restoreFromZip via bolehHapusFile).
            try {
                if (plain != null && plain.exists()) {
                    plain.delete();
                }
            } catch (Exception ignored) {
            }
        }
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
        // Wall-clock agar reboot tak mereset lockout PIN bot.
        long sekarang = System.currentTimeMillis();
        long sisa = PinGate.sisaKunciMs(ctx, sekarang);
        if (sisa > 0) {
            TgBackup.sendMessage(ctx, "PIN terkunci sementara (kebanyakan gagal)."
                    + " Coba lagi " + ((sisa + 59000) / 60000) + " menit.");
            return null;
        }
        int i = t.lastIndexOf(' ');
        String pin = i < 0 ? t : t.substring(i + 1);
        String rest = i < 0 ? "" : t.substring(0, i).trim();
        boolean cocok = !pin.isEmpty() && PinCrypto.verify(hash, pin);
        PinGate.catatHasil(ctx, cocok, sekarang);
        if (cocok) {
            if (!PinCrypto.isNewFormat(hash)) {
                try {
                    sp.edit().putString("pin_hash", PinCrypto.hash(pin)).apply();
                } catch (Exception ignored) {
                }
            }
            return rest;
        }
        TgBackup.sendMessage(ctx, "Perintah ini butuh PIN app di akhir"
                + " (mis. /stop 123456). Aktifkan PIN di pengaturan bila belum.");
        return null;
    }

    /** Jalankan tugas berat bot saling-menunggu; tolak halus bila sibuk. */
    private static void runBeratDenganKunci(Context ctx, Runnable task) {
        if (!TUGAS_BERAT.compareAndSet(false, true)) {
            TgBackup.sendMessage(ctx, "Tugas lain masih berjalan, coba lagi sebentar.");
            return;
        }
        runWithWakeLock(ctx, () -> {
            try {
                task.run();
            } finally {
                TUGAS_BERAT.set(false);
            }
        });
    }

    /** Jalankan tugas berat di pool + partial wake lock.
     *  Wake lock best-effort: gagal pasang (pm null / ditolak sistem) tidak
     *  boleh menggagalkan task — flag TUGAS_BERAT milik pemanggil selalu
     *  direset lewat finally task itu sendiri. */
    private static void runWithWakeLock(Context ctx, Runnable task) {
        jalankanBg(() -> {
            PowerManager.WakeLock wl = null;
            try {
                try {
                    PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vaultwarden:tgbot-task");
                        wl.acquire(10 * 60 * 1000L);
                    }
                } catch (Exception ignored) {
                    wl = null;
                }
                task.run();
            } finally {
                try {
                    if (wl != null && wl.isHeld()) {
                        wl.release();
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** Batas umur tombol inline (24 jam) + toleransi jam miring (5 menit). Murni. */
    static final long TOMBOL_KEDALUWARSA_MS = 24L * 3600 * 1000;
    static final long TOLERANSI_JAM_MS = 5 * 60_000;

    /** True bila jam mundur jauh dari yang pernah terlihat (rollback/NTP) — fail-closed. */
    static boolean jamMundur(long kini) {
        long maks = wallMaksTelegram;
        return maks > 0 && kini < maks - TOLERANSI_JAM_MS;
    }

    /** Catat wall-clock monoton naik untuk deteksi rollback berikutnya. */
    static void catatWall(long kini) {
        long m = wallMaksTelegram;
        if (kini > m) {
            wallMaksTelegram = kini;
        }
    }

    /** True bila tombol inline sudah tak berlaku: terlalu tua, tanpa tanggal,
     *  atau bertanggal masa depan tak wajar (jam STB ngaco / replay) — fail-closed. Murni. */
    static boolean tombolKedaluwarsa(long tglMs, long sekarang) {
        if (tglMs <= 0) {
            return true;
        }
        if (tglMs > sekarang + TOLERANSI_JAM_MS) {
            return true;
        }
        return sekarang - tglMs > TOMBOL_KEDALUWARSA_MS;
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

    private static String httpPostForm(Context ctx, String url, String param) {
        HttpURLConnection c = null;
        try {
            byte[] body = param.getBytes(StandardCharsets.UTF_8);
            c = TgBackup.bukaPostTelegram(ctx, url, body,
                    "application/x-www-form-urlencoded", 15000, 35000);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            TgBackup.tolakRedirectTelegram(c);
            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            if (is == null) {
                return null;
            }
            return TgBackup.bacaResponsBatas(is);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
