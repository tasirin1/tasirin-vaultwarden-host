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
    static final int MENU_REV = 4;
    private static final long POLL_INTERVAL_MS = 20_000;
    private static final long STALE_MSG_MS = 5 * 60_000;
    private static final AtomicBoolean POLLING = new AtomicBoolean(false);
    /** Kunci tugas berat bot agar backup/restore/update tak jalan bersamaan. */
    private static final AtomicBoolean TUGAS_BERAT = new AtomicBoolean(false);
    /** Wall-clock terbesar yang pernah terlihat (deteksi jam mundur/NTP).
     *  Bila jam mundur jauh, tombol/pesan basi diperlakukan kedaluwarsa (fail-closed). */
    private static volatile long wallMaksTelegram = 0;
    /** Kunci prefs maks wall-clock agar deteksi rollback selamat dari restart. */
    static final String KEY_TG_WALL_MAKS = "tg_wall_maks";
    /** Peringatan jam-mundur dibatasi 1x/jam (jam monotonik): tiap poll 20 dtk
     *  mengirim pesan = spam + self-DoS bila maks macet di masa depan. */
    static final long PERINGATAN_MUNDUR_MS = 3600_000;
    static volatile long peringatanMundurPada = 0;

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
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /** Kirim tugas bot tanpa lempar bila antrean penuh (fail-safe STB). */
    static void jalankanBg(Runnable r) {
        cobaJalankanBg(r);
    }

    /** Tawarkan tugas ke pool; false bila antrean penuh (agar kunci tugas
     *  berat bisa dilepas pemanggil, bukan macet selamanya). Murni alur. */
    static boolean cobaJalankanBg(Runnable r) {
        try {
            BG.execute(r);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            return false;
        } catch (Exception ignored) {
            return false;
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
        String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
        String chat = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, ""));
        PendingIntent pi = pendingIntent(ctx);
        // Chat kosong ikut membatalkan: pollOnce butuh keduanya, alarm tanpa
        // chat hanya membangunkan perangkat tiap 20 detik tanpa hasil.
        if (token.isEmpty() || chat.isEmpty()) {
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
                {"alive", "Cek sehat HTTPS /alive"},
                {"backup", "Backup database sekarang"},
                {"restore", "Restore backup terakhir"},
                {"ca", "Kirim CA HTTPS ke chat ini"},
                {"cabackup", "Backup CA ke storage STB"},
                {"careset", "Reset sertifikat (CA baru)"},
                {"crashlog", "Kirim crash log terakhir"},
                {"versi", "Lihat versi binary & web-vault"},
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
        // Fallback legacy untuk pesan versi lama ("Web vault updated di ...");
        // kata "updated" bebas terlalu longgar (false-positive bila redaksi berubah).
        return msg.contains("Web vault updated");
    }

    /** Lolos string untuk payload JSON manual (tanpa pustaka). Murni. */
    static String lolosJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder o = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                o.append("\\\"");
            } else if (c == '\\') {
                o.append("\\\\");
            } else if (c == '\n') {
                o.append("\\n");
            } else if (c == '\r') {
                o.append("\\r");
            } else if (c == '\t') {
                o.append("\\t");
            } else if (c < 0x20) {
                // Locale.US: %x di locale berdigit non-Latin (mis. ar-EG)
                // menghasilkan digit non-ASCII sehingga JSON invalid.
                o.append(String.format(Locale.US, "\\u%04x", (int) c));
            } else {
                o.append(c);
            }
        }
        return o.toString();
    }

    /** Payload JSON setMyCommands (string manual agar bisa di-unit-test JVM). */
    static String menuPayload() {
        StringBuilder sb = new StringBuilder("{\"commands\":[");
        String[][] daftar = daftarPerintahMenu();
        for (int i = 0; i < daftar.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"command\":\"").append(lolosJson(daftar[i][0]))
                    .append("\",\"description\":\"").append(lolosJson(daftar[i][1])).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Daftarkan menu perintah ke BotFather API (best-effort, sekali per token). */
    static void refreshMenuAsync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        final String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
        if (token.isEmpty()) {
            return;
        }
        final int hash = token.hashCode() * 31 + MENU_REV;
        if (TgBackup.amanInt(sp, KEY_TG_MENU_HASH, 0) == hash) {
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
                InputStream mentah = (code >= 200 && code < 300)
                        ? c.getInputStream() : c.getErrorStream();
                String balasan = "";
                if (mentah != null) {
                    try (InputStream is = mentah) {
                        balasan = TgBackup.bacaResponsBatas(is);
                    }
                }
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
            String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
            String chat = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, ""));
            if (token.isEmpty() || chat.isEmpty()) {
                return;
            }
            long offset = TgBackup.amanLong(sp, KEY_TG_OFFSET, 0);
            String chatResmi = chat.trim();
            muatWallMaks(ctx);
            // Token bot selalu ada di path URL (desain API Telegram
            // "bot<token>/metode" tak bisa dihindari); POST hanya menjaga
            // parameter offset/timeout tak ikut nangkring di URL/proxy-log.
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
                    int updateRusak = 0;
                    String contohGalatUpdate = null;
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
                                    // Fail-closed: jam tak dipercaya (rollback/NTP),
                                    // perintah berbahaya tak boleh jalan dari pesan
                                    // basi yang di-replay. Offset tetap maju agar
                                    // tak diproses ulang; maks dipertahankan agar
                                    // pulih sendiri saat jam kembali normal.
                                    if (catatMundurDanBolehIngatkan(kini)) {
                                        rollbackDitolak++;
                                    }
                                    continue;
                                } else {
                                    catatWall(kini);
                                }
                                if (!Util.pesanSegar(dateMs, kini,
                                        STALE_MSG_MS)) {
                                    continue;
                                }
                                String text = msg.optString("text", "").trim();
                                boolean terotorisasi = handleCommand(ctx, text);
                                // PIN yang lolos menempel di riwayat dan bisa dipakai
                                // ulang pengintip: hapus best-effort (perlu izin hapus;
                                // gagal diam-diam, di poll thread sendiri). Upaya
                                // GAGAL sengaja dipertahankan sebagai bukti brute-force.
                                int idPesan = msg.optInt("message_id", 0);
                                if (idPesan != 0 && terotorisasi && perintahBerbahaya(text)
                                        && pinPerangkatAktif(ctx)) {
                                    hapusPesanPerintah(ctx, c.optLong("id", -1), idPesan);
                                }
                            }
                        } catch (Exception eUpdate) {
                            updateRusak++;
                            if (contohGalatUpdate == null) {
                                contohGalatUpdate = String.valueOf(eUpdate);
                            }
                        }
                    }
                    if (updateRusak > 0) {
                        String contoh = contohGalatUpdate == null ? "?" : contohGalatUpdate;
                        if (contoh.length() > 120) {
                            contoh = contoh.substring(0, 120);
                        }
                        ServerService.catatLog("[tg] " + updateRusak
                                + " update dilewati (format tak dikenal, offset tetap maju): "
                                + contoh);
                    }
                }
                if (rollbackDitolak > 0) {
                    TgBackup.sendMessage(ctx, "Jam STB sempat mundur drastis;"
                            + " perintah ditolak sementara. Periksa tanggal & jam STB.");
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
            simpanWallMaks(ctx);
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
                {"Backup CA", "/cabackup"}, {"Reset CA", "/careset"},
                {"Versi", "/versi"},
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
                sb.append("{\"text\":\"").append(lolosJson(tombol[j][0]))
                        .append("\",\"callback_data\":\"").append(lolosJson(tombol[j][1]))
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
            JSONObject pesan = cb.optJSONObject("message");
            JSONObject ruang = pesan != null ? pesan.optJSONObject("chat") : null;
            long idRuang = ruang != null ? ruang.optLong("id", -1) : -1;
            String namaRuang = ruang != null ? ruang.optString("username", "") : "";
            // Satu pintu auth (cocokChat: trim + tanpa peka huruf): wajib ruang
            // resmi. Pengirim resmi saja tak cukup agar keyboard yang bocor /
            // diteruskan ke chat lain tak bisa mengeksekusi perintah dari luar.
            // (Tombol berbahaya tetap butuh PIN yang tak bisa dibawa tombol.)
            if (!Util.cocokChat(chatResmi, idRuang, namaRuang)) {
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
                    // Fail-closed seperti pesan: tombol ditolak sementara saat
                    // jam tak dipercaya (peringatan dibatasi 1x/jam).
                    boolean ingatkan = catatMundurDanBolehIngatkan(kini);
                    jawabCallback(ctx, cb.optString("id", ""));
                    if (ingatkan) {
                        TgBackup.sendMessage(ctx, "Jam STB sempat mundur drastis;"
                                + " tombol ditolak sementara. Periksa tanggal & jam STB.");
                    }
                    return;
                } else {
                    catatWall(kini);
                }
                if (tombolKedaluwarsa(tgl, kini)) {
                    jawabCallback(ctx, cb.optString("id", ""));
                    TgBackup.sendMessage(ctx, "Tombol sudah kedaluwarsa (>24 jam)."
                            + " Minta keyboard baru dengan /help lalu coba lagi.");
                    return;
                }
            }
            jawabCallback(ctx, cb.optString("id", ""));
            String data = cb.optString("data", "").trim();
            // Data tombol hanya "/perintah" tanpa PIN (tombol tak bisa membawa
            // PIN): tak ada rahasia di riwayat yang perlu dihapus seperti
            // pesan ketik di pollOnce (hapusPesanPerintah khusus pesan ber-PIN).
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

    /** True bila teks adalah perintah berbahaya (wajib PIN bila PIN aktif).
     *  Perintah yang mengubah keadaan (start/stop/restart/backup/restore/
     *  update/webvault/careset). Perintah baca (status/log/uptime/alive/
     *  help/ca/cabackup/versi) cukup auth chat agar tombol inline tetap
     *  bisa dipakai saat PIN aktif (tombol tak bisa membawa PIN).
     *  /crashlog tak ada di daftar ini tapi tetap wajib PIN bila PIN aktif
     *  (memuat path folder data; diperiksa sendiri di handleCommand) —
     *  jangan anggap cukup auth chat. Murni agar bisa unit test; dipakai
     *  tombol inline & hapus pesan PIN. */
    static boolean perintahBerbahaya(String text) {
        String cmd = namaPerintah(text);
        return cmd.equals("start") || cmd.equals("stop") || cmd.equals("restart")
                || cmd.equals("backup") || cmd.equals("restore")
                || cmd.equals("update") || cmd.equals("webvault")
                || cmd.equals("careset");
    }

    /** True bila perintah tombol wajib PIN tapi tak bisa dibawa tombol.
     *  Perintah berbahaya butuh PIN di akhir argumen; tombol hanya kirim
     *  "/perintah" tanpa PIN sehingga selalu ditolak bila PIN aktif. */
    static boolean perintahBerbahayaTombol(String data) {
        return perintahBerbahaya(data);
    }

    /** True bila tombol ini tak bisa jalan karena PIN aktif (beri tahu user). */
    static boolean perluPinTombol(android.content.Context ctx, String data) {
        if (!perintahBerbahayaTombol(data)) {
            return false;
        }
        return pinPerangkatAktif(ctx);
    }

    /** True bila PIN perangkat aktif (pintu kedua perintah berbahaya).
     *  Fail-closed: prefs tak terbaca (penyimpanan rusak) dianggap PIN aktif
     *  sehingga tombol berbahaya ditolak; pesan ketik pun aman karena
     *  getSharedPreferences yang melempar membuat pollOnce melewatkan update
     *  itu via catch, bukan mengeksekusinya tanpa PIN. */
    static boolean pinPerangkatAktif(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            return TgBackup.pinAktif(TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false),
                    TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, ""));
        } catch (Exception e) {
            return true;
        }
    }

    /** Hapus pesan perintah ber-PIN dari chat (anti intip riwayat).
     *  Best-effort: butuh hak hapus (di grup bot harus admin); gagal
     *  diam-diam, keamanan tak bergantung padanya (lockout + PIN tetap jalan). */
    private static void hapusPesanPerintah(Context ctx, long chatId, int messageId) {
        if (chatId <= 0 || messageId == 0) {
            return;
        }
        HttpURLConnection c = null;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
            if (token.isEmpty()) {
                return;
            }
            byte[] body = ("chat_id=" + chatId + "&message_id=" + messageId)
                    .getBytes(StandardCharsets.UTF_8);
            c = TgBackup.bukaPostTelegram(ctx.getApplicationContext(),
                    TG_API + token + "/deleteMessage", body,
                    "application/x-www-form-urlencoded", 15000, 30000);
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
                String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
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

    /** Tangani satu perintah; true bila perintah berbahaya lolos otorisasi
     *  (PIN terverifikasi atau PIN mati) sehingga pemanggil boleh menghapus
     *  pesan dari riwayat. False berarti PIN gagal/hilang: pesan dipertahankan
     *  sebagai bukti forensik brute-force. */
    private static boolean handleCommand(Context ctx, String text) {
        boolean berbahayaTerotorisasi = false;
        if (!text.startsWith("/")) {
            return false;
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
                berbahayaTerotorisasi = true;
                if (ServerService.running || ServerService.isProcessAlive()) {
                    TgBackup.sendMessage(ctx, "Server sudah jalan.");
                } else {
                    try {
                        if (ServerService.start(ctx)) {
                            TgBackup.sendMessage(ctx, "Perintah diterima: server start...");
                        } else {
                            TgBackup.sendMessage(ctx, "Gagal start dari background (batasan Android). "
                                    + "Buka app lalu tekan Start, atau aktifkan 'Auto start saat boot' "
                                    + "lalu reboot HP.");
                        }
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
                berbahayaTerotorisasi = true;
                try {
                    if (ServerService.stop(ctx)) {
                        TgBackup.sendMessage(ctx, "Perintah diterima: server stop...");
                    } else {
                        TgBackup.sendMessage(ctx, "Gagal stop dari background (batasan Android). "
                                + "Buka app lalu tekan Stop.");
                    }
                } catch (Throwable t) {
                    TgBackup.sendMessage(ctx, "Gagal stop dari background (batasan Android). "
                            + "Buka app lalu tekan Stop.");
                }
                break;
            case "/restart":
                if (authDangerous(ctx, arg) == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
                try {
                    if (ServerService.restart(ctx)) {
                        TgBackup.sendMessage(ctx, "Perintah diterima: server restart...");
                    } else {
                        TgBackup.sendMessage(ctx, "Restart hanya bisa saat server berjalan.");
                    }
                } catch (Throwable t) {
                    TgBackup.sendMessage(ctx, "Restart hanya bisa saat server berjalan.");
                }
                break;
            case "/backup":
                if (authDangerous(ctx, arg) == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
                runBeratDenganKunci(ctx, () -> {
                    try {
                        TgBackup.sendMessage(ctx, TgBackup.backupNow(ctx));
                    } catch (Exception e) {
                        TgBackup.sendMessage(ctx, TgBackup.pesanGalatBackup(e));
                    }
                });
                break;
            case "/restore":
                String cleanRestore = authDangerous(ctx, arg);
                if (cleanRestore == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
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
            case "/cabackup":
                // File publik (tanpa kunci privat): tanpa PIN seperti /ca.
                runWithWakeLock(ctx, () -> {
                    try {
                        TgBackup.sendMessage(ctx, TgBackup.backupCaKeStorage(ctx));
                    } catch (Exception e) {
                        TgBackup.sendMessage(ctx, "Backup CA gagal: " + e.getMessage());
                    }
                });
                break;
            case "/careset":
                if (authDangerous(ctx, arg) == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
                // Kunci tugas berat (bukan runWithWakeLock polos): reset menghapus
                // file tls satu per satu sehingga backup konkuren bisa menangkap
                // setengah set (CA baru + key lama). Serial dengan backup/restore.
                runBeratDenganKunci(ctx, () -> {
                    try {
                        TgBackup.sendMessage(ctx, TgBackup.resetSertifikat(ctx));
                    } catch (Exception e) {
                        TgBackup.sendMessage(ctx, "Reset sertifikat gagal: " + e.getMessage());
                    }
                });
                break;
            case "/status":
                // Perintah baca cukup auth chat (tanpa PIN) agar tombol inline
                // tetap jalan saat PIN aktif; tulis tetap lewat authDangerous.
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
                        ? "Server sehat (HTTPS 200 /alive)."
                        : "Server TIDAK merespon /alive!");
                break;
            case "/update":
                String bersihUpdate = authDangerous(ctx, arg);
                if (bersihUpdate == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
                if (!argumenVersiValid(bersihUpdate)) {
                    TgBackup.sendMessage(ctx, "Versi tidak valid."
                            + " Contoh: /update 1.32.0 atau /update terbaru");
                    break;
                }
                final boolean kunciUpdate = !bersihUpdate.isEmpty();
                final String versiUpdate = Updater.normalisasiPinVersi(bersihUpdate);
                runBeratDenganKunci(ctx, () -> {
                    String pinLama = Updater.kuncianBinary(ctx);
                    try {
                        boolean was = ServerService.running || ServerService.isProcessAlive();
                        // Versi eksplisit ikut dikunci agar auto-update tak menaikkan lagi;
                        // "terbaru" melepas kuncian. Pin lama dikembalikan bila
                        // unduh gagal agar typo tak mengunci perangkat ke versi rusak.
                        if (kunciUpdate) {
                            if (!Updater.simpanKuncianBinary(ctx, bersihUpdate)) {
                                TgBackup.sendMessage(ctx, "Versi tidak valid."
                                        + " Contoh: /update 1.32.0 atau /update terbaru");
                                return;
                            }
                        }
                        // Shim dulu agar uji --version di dalam tryUpdate lolos di kernel lama.
                        String imbuhShim = "";
                        if (KernelCompat.isLegacyDevice(KernelCompat.kernelSekarang())) {
                            try {
                                Updater.ensureShimFile(ctx);
                                imbuhShim = " Shim getrandom siap.";
                            } catch (Exception se) {
                                imbuhShim = " Shim gagal: " + se.getMessage();
                            }
                        }
                        String msg = Updater.tryUpdateVersi(ctx, versiUpdate) + imbuhShim;
                        if (Updater.binaryBerubah(msg)) {
                            if (was) {
                                if (ServerService.restart(ctx)) {
                                    TgBackup.sendMessage(ctx, msg + " Restart otomatis...");
                                } else {
                                    TgBackup.sendMessage(ctx, msg + " Restart manual dari app"
                                            + " (batasan background Android).");
                                }
                            } else {
                                TgBackup.sendMessage(ctx, msg + " Tekan /start untuk memakai.");
                            }
                        } else {
                            TgBackup.sendMessage(ctx, msg);
                        }
                    } catch (Exception e) {
                        // Gagal unduh: kembalikan pin lama bila tadi mengunci versi
                        // konkret (bukan melepas ke terbaru) agar perangkat tak
                        // terkunci ke versi yang tak pernah terpasang.
                        if (kunciUpdate && versiUpdate != null) {
                            Updater.simpanKuncianBinary(ctx,
                                    pinLama == null ? "" : pinLama);
                        }
                        String ramah = e.getMessage() != null && e.getMessage().contains("Cek ")
                                ? e.getMessage() : Updater.pesanGalatUnduh("Unduh binary", e);
                        TgBackup.sendMessage(ctx, "Update gagal: " + ramah);
                    }
                });
                break;
            case "/webvault":
                String bersihWv = authDangerous(ctx, arg);
                if (bersihWv == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
                if (!argumenVersiValid(bersihWv)) {
                    TgBackup.sendMessage(ctx, "Versi tidak valid."
                            + " Contoh: /webvault 1.32.0 atau /webvault terbaru");
                    break;
                }
                final boolean kunciWv = !bersihWv.isEmpty();
                final String versiWv = Updater.normalisasiPinVersi(bersihWv);
                runBeratDenganKunci(ctx, () -> {
                    String pinLamaWv = Updater.kuncianWebVault(ctx);
                    try {
                        boolean was = ServerService.running || ServerService.isProcessAlive();
                        // Pin lama dikembalikan bila unduh gagal (lihat /update).
                        if (kunciWv) {
                            if (!Updater.simpanKuncianWebVault(ctx, bersihWv)) {
                                TgBackup.sendMessage(ctx, "Versi tidak valid."
                                        + " Contoh: /webvault 1.32.0 atau /webvault terbaru");
                                return;
                            }
                        }
                        String msg = Updater.updateWebVaultVersi(ctx, versiWv);
                        if (webVaultBerubah(msg)) {
                            if (was) {
                                if (ServerService.restart(ctx)) {
                                    TgBackup.sendMessage(ctx, msg + " Restart otomatis...");
                                } else {
                                    TgBackup.sendMessage(ctx, msg + " Restart manual dari app"
                                            + " (batasan background Android).");
                                }
                            } else {
                                TgBackup.sendMessage(ctx, msg + " Tekan /start untuk memakai.");
                            }
                        } else {
                            TgBackup.sendMessage(ctx, msg);
                        }
                    } catch (Exception e) {
                        if (kunciWv && versiWv != null) {
                            Updater.simpanKuncianWebVault(ctx,
                                    pinLamaWv == null ? "" : pinLamaWv);
                        }
                        String ramah = e.getMessage() != null && e.getMessage().contains("Cek ")
                                ? e.getMessage() : Updater.pesanGalatUnduh("Unduh web-vault", e);
                        TgBackup.sendMessage(ctx, "Update web-vault gagal: " + ramah);
                    }
                });
                break;
            case "/versi":
                TgBackup.sendMessage(ctx, teksVersi(ctx));
                break;
            case "/crashlog":
                // Crash log memuat path folder data: wajib PIN bila PIN aktif.
                if (pinPerangkatAktif(ctx)) {
                    if (authDangerous(ctx, arg) == null) {
                        break;
                    }
                    berbahayaTerotorisasi = true;
                }
                String crash = ServerService.crashLogText(ctx);
                if (crash == null || crash.trim().isEmpty()) {
                    TgBackup.sendMessage(ctx, "Belum ada crash log tersimpan.");
                } else {
                    TgBackup.sendMessage(ctx, potongEkor(crash, 3500));
                }
                break;
            case "/help":
                TgBackup.sendMessageKb(ctx, "Perintah: /status  /log  /uptime  /alive  /backup  /restore  /ca\n"
                        + "/cabackup  /careset  /crashlog  /versi  /update  /webvault  /restart  /start  /stop  /help\n"
                        + "Kunci versi lawas: /update 1.32.0 (binary), /webvault 1.32.0;"
                        + " lepas kunci: /update terbaru. Lihat /versi.\n"
                        + "Ketuk tombol di bawah agar tak perlu mengetik.\n"
                        + "Bila PIN app aktif, /start /stop /restart /backup /update /webvault /restore /careset /crashlog butuh PIN"
                        + " (mis. /stop 123456 atau /stop PIN:123456; bila PIN ber-spasi: /stop PIN:\"kunci saya\").", keyboardPerintah());
                break;
            default:
                TgBackup.sendMessage(ctx, "Perintah tidak dikenal. Ketik /help");
        }
        return berbahayaTerotorisasi;
    }

    /** True bila argumen /restore adalah konfirmasi eksplisit. Sengaja sempit
     *  ("ya"/"yes"/"konfirmasi"/"confirm"): kata umum seperti "ok"/"y"/"lanjut"
     *  yang nyasar tepat setelah info restore tak boleh memicu timpa database. Murni. */
    static boolean isRestoreConfirm(String arg) {
        if (arg == null) {
            return false;
        }
        String a = arg.trim().toLowerCase(Locale.US);
        return a.equals("ya") || a.equals("yes") || a.equals("konfirmasi")
                || a.equals("confirm");
    }

    /** Teks konfirmasi /restore: info backup terakhir + cara konfirmasi. */
    static String restoreInfoText(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String name = TgBackup.amanString(sp, TgBackup.KEY_TG_LAST_NAME, "");
        String fileId = TgBackup.amanString(sp, TgBackup.KEY_TG_LAST_FILE, "");
        long last = TgBackup.amanLong(sp, TgBackup.KEY_TG_LAST, 0);
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
        String pass = TgBackup.amanString(sp, TgBackup.KEY_TG_PASS, "");
        File tmp = new File(ctx.getCacheDir(), "vwtg-restore-bot.zip");
        File plain = new File(ctx.getCacheDir(), "vwtg-restore-bot-dec.zip");
        try {
            TgBackup.downloadLastBackup(ctx, tmp);
            File zip = tmp;
            if (TgBackup.isEncrypted(tmp)) {
                if (pass == null || pass.trim().isEmpty()) {
                    throw new IOException("Backup terenkripsi"
                            + " - isi password backup di pengaturan dulu.");
                }
                TgBackup.decryptFile(tmp, plain, pass.trim());
                try {
                    tmp.delete();
                } catch (Exception ignored) {
                }
                zip = plain;
            }
            return TgBackup.restoreFromZip(ctx, zip);
        } finally {
            // Bersihkan sisa cache (termasuk potongan unduhan gagal) agar isi DB tak tertinggal.
            // Jalur sukses sudah dihapus restoreFromZip via bolehHapusFile; hapus ulang aman (no-op).
            try {
                if (plain.exists()) {
                    plain.delete();
                }
            } catch (Exception ignored) {
            }
            try {
                if (tmp.exists()) {
                    tmp.delete();
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
        // Baca tahan korup: prefs edit manual bertipe salah tak boleh
        // melempar ClassCastException di thread polling bot.
        String hash = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
        // Fail-closed: PIN aktif tanpa hash (prefs rusak) tetap minta PIN
        // (verifikasi hash kosong selalu gagal) agar perintah berbahaya tak
        // lolos tanpa kunci; pulihkan via buka Settings (PIN mati otomatis).
        boolean need = TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false);
        String t = arg == null ? "" : arg.trim();
        if (!need) {
            return t;
        }
        // Wall-clock agar reboot tak mereset lockout PIN bot.
        long sekarang = System.currentTimeMillis();
        long sisa = PinGate.sisaKunciMs(ctx, sekarang);
        if (sisa > 0) {
            TgBackup.sendPenting(ctx, "PIN terkunci sementara (kebanyakan gagal)."
                    + " Coba lagi " + ((sisa + 59000) / 60000) + " menit.");
            return null;
        }
        String[] pisah = pisahkanPin(t);
        String rest = pisah[0];
        String pin = pisah[1];
        if (pin.isEmpty()) {
            TgBackup.sendMessage(ctx, "Perintah ini butuh PIN app"
                    + " (mis. /stop 123456 atau /stop PIN:123456; bila PIN ber-spasi: /stop PIN:\"kunci saya\").");
            return null;
        }
        boolean cocok = PinCrypto.verify(hash, pin);
        PinGate.catatHasil(ctx, cocok, sekarang);
        if (cocok) {
            if (PinCrypto.perluUpgradeHash(hash)) {
                try {
                    sp.edit().putString(PinGate.KEY_PIN_HASH, PinCrypto.hash(pin)).apply();
                } catch (Exception ignored) {
                }
            }
            return rest;
        }
        TgBackup.sendMessage(ctx, "Perintah ini butuh PIN app"
                + " (mis. /stop 123456 atau /stop PIN:123456; bila PIN ber-spasi: /stop PIN:\"kunci saya\").");
        return null;
    }

    /** True bila argumen versi perintah valid: kosong (ikut target), "terbaru"/
     *  "latest" (lepas kunci), atau nomor versi. Murni agar bisa unit test. */
    static boolean argumenVersiValid(String arg) {
        if (arg == null) {
            return true;
        }
        String t = arg.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("terbaru") || t.equalsIgnoreCase("latest")) {
            return true;
        }
        return Updater.normalisasiPinVersi(t) != null;
    }

    /** Ringkasan versi binary/web-vault + status kuncian (perintah /versi). */
    static String teksVersi(Context ctx) {
        String bin = Updater.currentServerVersion(ctx);
        String dataDir = ServerService.DEFAULT_DATA_DIR;
        try {
            dataDir = TgBackup.amanString(
                    ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE),
                    ServerService.KEY_DATA_DIR, ServerService.DEFAULT_DATA_DIR);
        } catch (Exception ignored) {
        }
        String wv = null;
        try {
            wv = Updater.readWvVersion(new java.io.File(dataDir, "web-vault/vw-version.json"));
        } catch (Exception ignored) {
        }
        String pinB = Updater.kuncianBinary(ctx);
        String pinW = Updater.kuncianWebVault(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("Binary: ").append(bin == null || bin.isEmpty() ? "?" : "v" + bin);
        if (pinB != null && !pinB.isEmpty()) {
            sb.append(" (terkunci v").append(pinB).append(')');
        }
        sb.append("\nWeb vault: ").append(wv == null || wv.isEmpty() ? "belum terpasang" : "v" + wv);
        if (pinW != null && !pinW.isEmpty()) {
            sb.append(" (terkunci v").append(pinW).append(')');
        }
        return sb.toString();
    }

    /** Pisahkan PIN dari argumen perintah (murni agar bisa unit test).
     *  Format eksplisit "PIN:..." diutamakan: menelan sisa baris sebagai PIN
     *  (wajib di akhir argumen) sehingga PIN ber-spasi tetap bisa dipakai;
     *  kutip mengapit penuh dikupas. Wajib untuk PIN alfanumerik; fallback
     *  kata terakhir dipertahankan karena alur "/restore YA 123456"
     *  mengandalkannya (kata "YA" adalah argumen). Kata pendek (<4) tak pernah
     *  dimakan agar argumen seperti "YA" tak hilang dan lockout tak bertambah
     *  sia-sia. Return {sisa, pin}. */
    static String[] pisahkanPin(String arg) {
        String t = arg == null ? "" : arg.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)\\bPIN\\s*:\\s*(.+)").matcher(t);
        if (m.find()) {
            String mentah = m.group(1).trim();
            // Bentuk eksplisit menelan sisa baris sebagai PIN agar PIN ber-spasi
            // tetap bisa dipakai via bot. Tanda kutip mengapit penuh dikupas.
            String pin = mentah;
            if (mentah.length() >= 2 && ((mentah.startsWith("\"") && mentah.endsWith("\""))
                    || (mentah.startsWith("'") && mentah.endsWith("'")))) {
                pin = mentah.substring(1, mentah.length() - 1);
            }
            if (pin.isEmpty()) {
                return new String[]{t, ""};
            }
            String sisa = t.substring(0, m.start()).trim().replaceAll("\\s+", " ");
            return new String[]{sisa, pin};
        }
        int i = t.lastIndexOf(' ');
        if (i < 0) {
            // Kata tunggal pendek bukan PIN: minta PIN eksplisit agar tak lockout sia-sia.
            // Kata kunci versi ("terbaru"/"latest") bukan PIN: jangan dimakan agar
            // /update terbaru tak terkunci sia-sia saat PIN aktif.
            if (t.equalsIgnoreCase("terbaru") || t.equalsIgnoreCase("latest")) {
                return new String[]{t, ""};
            }
            // Kata tunggal hanya numerik 4+ digit yang dimakan sebagai PIN
            // (legasi "/stop 123456"): kata huruf seperti "webvault" adalah
            // argumen salah ketik, bukan PIN — makan sebagai PIN menambah
            // hitungan lockout sia-sia. PIN alfanumerik kata tunggal wajib
            // bentuk eksplisit "PIN:ab12" (diutamakan di atas).
            if (t.matches("[0-9]{4,}")) {
                return new String[]{"", t};
            }
            return new String[]{t, ""};
        }
        String kandidat = t.substring(i + 1);
        // Fallback legasi "/restore YA 123456": kata terakhir min 4 char tanpa
        // spasi yang berdigit ATAU bersimbol dianggap PIN (UI memakai
        // textPassword bebas simbol seperti p@ss!9); kata huruf murni tanpa
        // digit/simbol seperti "webvault" adalah argumen (selaras cabang
        // kata-tunggal di atas) agar tak menambah lockout sia-sia. PIN tanpa
        // digit dan tanpa simbol wajib bentuk eksplisit "PIN:".
        // Kata kunci versi ("terbaru"/"latest") bukan PIN agar /update terbaru
        // + PIN tetap membawa versi ("terbaru 123456" -> sisa "terbaru").
        if (kandidat.equalsIgnoreCase("terbaru") || kandidat.equalsIgnoreCase("latest")) {
            return new String[]{t, ""};
        }
        // Token "PIN:" telanjang (tanpa isi) bukan PIN: jangan dimakan agar
        // salah ketik "/stop PIN:" tak menambah hitungan lockout sia-sia.
        if (kandidat.equalsIgnoreCase("PIN:")) {
            return new String[]{t, ""};
        }
        if (kandidat.matches("\\S{4,}")
                && (kandidat.matches(".*[0-9].*") || kandidat.matches(".*[^A-Za-z0-9].*"))) {
            return new String[]{t.substring(0, i).trim(), kandidat};
        }
        return new String[]{t, ""};
    }

    /** Jalankan tugas berat bot saling-menunggu; tolak halus bila sibuk. */
    private static void runBeratDenganKunci(Context ctx, Runnable task) {
        if (!TUGAS_BERAT.compareAndSet(false, true)) {
            TgBackup.sendMessage(ctx, "Tugas lain masih berjalan, coba lagi sebentar.");
            return;
        }
        boolean masuk = runWithWakeLock(ctx, () -> {
            try {
                task.run();
            } finally {
                TUGAS_BERAT.set(false);
            }
        });
        if (!masuk) {
            // Antrean pool penuh: tugas tak pernah jalan sehingga finally di atas
            // tak tercapai — lepas kunci di sini agar perintah berikut tak
            // ditolak selamanya.
            TUGAS_BERAT.set(false);
            TgBackup.sendMessage(ctx, "Sistem sibuk, coba lagi sebentar.");
        }
    }

    /** Jalankan tugas berat di pool + partial wake lock.
     *  Wake lock best-effort: gagal pasang (pm null / ditolak sistem) tidak
     *  boleh menggagalkan task — flag TUGAS_BERAT milik pemanggil selalu
     *  direset lewat finally task itu sendiri bila submit berhasil.
     *  Return false bila antrean pool penuh (pemanggil wajib melepas kuncinya). */
    private static boolean runWithWakeLock(Context ctx, Runnable task) {
        // Wakelock dipasang di thread pemanggil (masih di bawah wakelock
        // receiver 60 dtk), lalu dilepas di thread BG setelah tugas selesai.
        // Bila dipasang di dalam BG, ada celah Doze antara submit-vs-acquire.
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vaultwarden:tgbot-task");
                wl.acquire(10 * 60 * 1000L);
            }
        } catch (Exception ignored) {
            wl = null;
        }
        final PowerManager.WakeLock milik = wl;
        boolean masuk = cobaJalankanBg(() -> {
            try {
                task.run();
            } finally {
                try {
                    if (milik != null && milik.isHeld()) {
                        milik.release();
                    }
                } catch (Exception ignored) {
                }
            }
        });
        if (!masuk) {
            try {
                if (milik != null && milik.isHeld()) {
                    milik.release();
                }
            } catch (Exception ignored) {
            }
        }
        return masuk;
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

    /** Muat maks wall-clock dari prefs (deteksi rollback lintas restart). */
    static void muatWallMaks(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            long s = TgBackup.amanLong(sp, KEY_TG_WALL_MAKS, 0);
            if (s > wallMaksTelegram) {
                wallMaksTelegram = s;
            }
        } catch (Exception ignored) {
        }
    }

    /** Simpan maks wall-clock ke prefs bila berubah (poll tiap 20 dtk). */
    static void simpanWallMaks(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            if (TgBackup.amanLong(sp, KEY_TG_WALL_MAKS, 0) == wallMaksTelegram) {
                return;
            }
            sp.edit().putLong(KEY_TG_WALL_MAKS, wallMaksTelegram).apply();
        } catch (Exception ignored) {
        }
    }

    /** Murni: true bila peringatan jam-mundur boleh dikirim (maks 1x/jam).
     *  kiniElapsed wajib monotonik (elapsedRealtime); reboot (kini < terakhir)
     *  dianggap jatuh tempo agar tak bisu selamanya. */
    static boolean peringatanMundurJatuhTempo(long kiniElapsed, long terakhir) {
        if (terakhir == 0) {
            return true;
        }
        if (kiniElapsed < terakhir) {
            return true;
        }
        return kiniElapsed - terakhir >= PERINGATAN_MUNDUR_MS;
    }

    /** Tangani jam mundur tanpa mengunci bot: tanda air MAKSIMUM dipertahankan
     *  (jangan dijepit ke kini agar penyerang tak bisa menurunkannya bertahap
     *  lewat rollback beruntun); pulih sendiri saat jam kembali melewati maks.
     *  Lalu true bila peringatan boleh dikirim (dibatasi 1x/jam via monotonik). */
    static boolean catatMundurDanBolehIngatkan(long kini) {
        long e;
        try {
            e = SystemClock.elapsedRealtime();
        } catch (RuntimeException ex) {
            return true;
        }
        if (peringatanMundurJatuhTempo(e, peringatanMundurPada)) {
            peringatanMundurPada = e;
            return true;
        }
        return false;
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
        // Samarkan dulu (baru potong): /log keluar perangkat via Telegram.
        String log = LogActivity.samarkanLog(ServerService.tailLog(30));
        if (log.isEmpty()) {
            return "Log kosong.";
        }
        return potongEkor(log, 3500);
    }

    /** Potong ekor teks maks N char di batas baris (tak memenggal tengah baris).
     *  Murni agar bisa unit test. */
    static String potongEkor(String teks, int maks) {
        if (teks == null || teks.length() <= maks) {
            return teks == null ? "" : teks;
        }
        int mulai = teks.length() - maks;
        // Jangan belah pasangan surrogate emoji: geser ke batas code-point.
        if (mulai > 0 && Character.isLowSurrogate(teks.charAt(mulai))
                && Character.isHighSurrogate(teks.charAt(mulai - 1))) {
            mulai++;
        }
        int nl = teks.indexOf('\n', mulai);
        if (nl >= 0 && nl + 1 < teks.length()) {
            return "..." + teks.substring(nl + 1);
        }
        return "..." + teks.substring(mulai);
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
        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.DEFAULT_DATA_DIR);
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = ServerService.DEFAULT_DATA_DIR;
        }
        String version = ServerService.binaryVersion.isEmpty()
                ? "?" : ServerService.binaryVersion;
        File db = new File(dataDir, "db.sqlite3");
        String dbInfo = db.exists() ? TgBackup.humanBytes(db.length()) : "belum ada";
        long lastBackup = TgBackup.amanLong(sp, TgBackup.KEY_TG_LAST, 0);
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

    /** RequestCode alarm polling bot. Alokasi terpusat agar tak bentrok:
     *  0 = jadwal harian (TgBackup.REQ_HARIAN), 3 = polling bot (di sini),
     *  101 = tunda-boot (TgBackup.REQ_TUNDA_BOOT). Nilai historis dipertahankan
     *  agar alarm lama yang sudah terjadwal tetap bisa dibatalkan. */
    static final int REQ_POLL = 3;

    private static PendingIntent pendingIntent(Context ctx) {
        Intent i = new Intent(ctx, TgBotReceiver.class).setAction(ACTION_POLL);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, REQ_POLL, i, flags);
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
            InputStream mentah = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            if (mentah == null) {
                return null;
            }
            try (InputStream is = mentah) {
                return TgBackup.bacaResponsBatas(is);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
