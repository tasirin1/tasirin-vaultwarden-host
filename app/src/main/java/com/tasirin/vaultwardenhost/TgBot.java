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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TgBot {

    public static final String ACTION_POLL = "com.tasirin.vaultwardenhost.TG_POLL";
    static final String KEY_TG_OFFSET = "tg_bot_offset";
    static final String KEY_TG_MENU_HASH = "tg_menu_hash";

    static final String KEY_TG_MENU_FP = "tg_menu_fp";
    static final int MENU_REV = 5;

    static final String KEY_TG_PENDING_PREFIX = "tg_pending_aksi_";

    static final long PENDING_AKSI_MS = 5 * 60_000L;

    static final String MENU_UTAMA = "utama";
    static final String MENU_STATUS = "status";
    static final String MENU_KONTROL = "kontrol";
    static final String MENU_BACKUP = "backup";
    static final String MENU_UPDATE = "update";
    static final String MENU_SERTIFIKAT = "sertifikat";
    static final String MENU_INFO = "info";
    private static final long POLL_INTERVAL_MS = 20_000;
    private static final long STALE_MSG_MS = 5 * 60_000;
    private static final AtomicBoolean POLLING = new AtomicBoolean(false);

    private static volatile String grupDiperingatkan = "";

    private static final AtomicBoolean TUGAS_BERAT = new AtomicBoolean(false);

    // Jeda minimum antar tugas berat dan antar /restart manual
    // (Audit 589): kunci cegah-bersamaan saja tak menghentikan deretan
    // perintah yang masing-masing jalan penuh begitu yang sebelumnya
    // selesai (zip+unggah berulang, proses mati-nyala berulang).
    static final long JEDA_BERAT_MS = 30_000L;
    private static volatile long tugasBeratBerakhirMs = 0L;
    private static volatile long restartManualTerakhirMs = 0L;

    /** true bila kini masih di dalam jeda sesudah stempel berakhir.
     *  Jam elapsed yang mundur berarti gagal-terbuka: jangan pernah
     *  mengunci perintah selamanya. */
    static boolean dalamJedaBerat(long berakhirMs, long kiniMs) {
        return berakhirMs > 0 && kiniMs >= berakhirMs
                && kiniMs - berakhirMs < JEDA_BERAT_MS;
    }

    private static volatile long wallMaksTelegram = 0;

    private static volatile long wallMaksTersimpan = Long.MIN_VALUE;

    private static final Object KUNCI_WALL = new Object();

    static final String KEY_TG_WALL_MAKS = "tg_wall_maks";

    static final String KEY_TG_HAPUS_WARN = "tg_hapus_warn";

    static final String KEY_TG_HAPUS_OK = "tg_hapus_ok";

    static final long PERINGATAN_MUNDUR_MS = 3600_000;
    static volatile long peringatanMundurPada = 0;

    private static final String TG_API = "https://api.telegram.org/bot";

    private static final java.util.concurrent.RejectedExecutionHandler TOLAK_BERLOG =
            Util.penanganAntreanPenuh("TgBot");
    private static final java.util.concurrent.ExecutorService BG =
            new java.util.concurrent.ThreadPoolExecutor(3, 3, 0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<Runnable>(32), r -> {
                Thread t = new Thread(r, "vw-tgbot-bg");
                t.setDaemon(true);
                return t;
            }, TOLAK_BERLOG);

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

    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
        String chat = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, ""));
        PendingIntent pi = pendingIntent(ctx);

        if (token.isEmpty() || chat.isEmpty()) {
            am.cancel(pi);
            return;
        }
        long trigger = SystemClock.elapsedRealtime() + 10_000;

        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger,
                POLL_INTERVAL_MS, pi);
        refreshMenuAsync(ctx);
    }

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
                {"help", "Menu tombol & daftar perintah"},
        };
    }

    static String pesanAlive(boolean sehat) {
        return sehat ? "Server sehat (HTTPS 200 /alive)."
                : "Server TIDAK merespon /alive!";
    }

    static boolean webVaultBerubah(String msg) {
        if (msg == null) {
            return false;
        }
        if (msg.contains(Updater.WV_UPDATED_MARKER)) {
            return true;
        }

        return msg.contains("Web vault updated");
    }

    private static void tambahEscapeUnicode(StringBuilder o, char c) {
        o.append('\\').append('u');
        o.append(Util.HEKS[(c >> 12) & 15]);
        o.append(Util.HEKS[(c >> 8) & 15]);
        o.append(Util.HEKS[(c >> 4) & 15]);
        o.append(Util.HEKS[c & 15]);
    }

    private static final java.util.regex.Pattern POLA_PIN_MARKER =
            java.util.regex.Pattern.compile("(?is)\\bPIN\\s*:");

    private static final String PENANDA_PIN_HEX = "\u0001PINHEX:";

    static String pinKePenanda(String pin) {
        if (pin == null || pin.isEmpty()) {
            return "";
        }
        byte[] b = pin.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder o = new StringBuilder(PENANDA_PIN_HEX.length() + b.length * 2);
        o.append(PENANDA_PIN_HEX);
        for (byte x : b) {
            o.append(Util.HEKS[(x >> 4) & 15]).append(Util.HEKS[x & 15]);
        }
        return o.toString();
    }

    static String pinDariPenanda(String arg) {
        String t = arg == null ? "" : arg;
        int i = t.lastIndexOf(PENANDA_PIN_HEX);
        if (i < 0) {
            return "";
        }
        String h = t.substring(i + PENANDA_PIN_HEX.length()).trim();
        if (h.isEmpty() || (h.length() % 2) != 0) {
            return "";
        }
        byte[] b = new byte[h.length() / 2];
        for (int k = 0; k < b.length; k++) {
            int hi = hexNilai(h.charAt(2 * k));
            int lo = hexNilai(h.charAt(2 * k + 1));
            if (hi < 0 || lo < 0) {
                return "";
            }
            b[k] = (byte) ((hi << 4) | lo);
        }
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int hexNilai(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
    private static final java.util.regex.Pattern POLA_OK_TRUE =
            java.util.regex.Pattern.compile("\"ok\"\\s*:\\s*true");

    static boolean responsOk(String body) {
        if (body == null) {
            return false;
        }
        return POLA_OK_TRUE.matcher(body).find();
    }

    static String kupasAt(String u) {
        if (u == null) {
            return "";
        }
        String t = u.trim();
        while (t.startsWith("@") && t.length() > 1) {
            t = t.substring(1);
        }
        return t;
    }

    static String rapikanSpasi(String t) {

        if (t == null) {
            return "";
        }
        int n = t.length();
        StringBuilder o = new StringBuilder(n);
        boolean spasi = true;
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            boolean ws = c == ' ' || c == '\t' || c == '\n' || c == '\r';
            if (ws) {
                if (!spasi) {
                    o.append(' ');
                    spasi = true;
                }
            } else {
                o.append(c);
                spasi = false;
            }
        }
        int m = o.length();
        if (m > 0 && o.charAt(m - 1) == ' ') {
            o.setLength(m - 1);
        }
        return o.toString();
    }

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

                tambahEscapeUnicode(o, c);
            } else if (c == '\u2028' || c == '\u2029') {

                tambahEscapeUnicode(o, c);
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {

                    o.append(c).append(s.charAt(i + 1));
                    i++;
                } else {

                    tambahEscapeUnicode(o, c);
                }
            } else if (Character.isLowSurrogate(c)) {

                tambahEscapeUnicode(o, c);
            } else {
                o.append(c);
            }
        }
        return o.toString();
    }

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

    static String sidikMenu(String token) {
        String t = token == null ? "" : token.trim();
        if (t.isEmpty()) {
            return "";
        }
        try {
            byte[] h = Util.mdSha256().digest((t + "|" + MENU_REV).getBytes(StandardCharsets.UTF_8));

            return Updater.toHex(h);
        } catch (Exception e) {
            return "";
        }
    }

    private static volatile String galatPollTercatat = "";

    static void refreshMenuAsync(Context ctx) {
        if (ctx == null) {
            return;
        }
        final Context app;
        try {
            app = ctx.getApplicationContext();
        } catch (Exception ignored) {
            return;
        }
        if (app == null) {
            return;
        }
        SharedPreferences sp = app.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        final String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
        if (token.isEmpty()) {
            return;
        }
        final String sidik = sidikMenu(token);
        if (sidik.isEmpty()) {
            return;
        }
        if (sidik.equals(TgBackup.amanString(sp, KEY_TG_MENU_FP, ""))) {
            return;
        }
        final String payload = menuPayload();
        cobaJalankanBg(() -> {
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
                if (code == 200 && responsOk(balasan)) {
                    app.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                            .edit().putString(KEY_TG_MENU_FP, sidik)
                            .remove(KEY_TG_MENU_HASH).apply();
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        });
    }

    @SuppressLint("ApplySharedPref")
    public static void pollOnce(Context ctx) {

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

            String chatKonfig = chat;

            if (Util.daftarPerluAnggapGrup(chatKonfig) && !chatKonfig.equals(grupDiperingatkan)) {
                grupDiperingatkan = chatKonfig;
                boolean adaGrupId = false;
                java.util.List<String> daftarResmi = Util.pecahDaftarChat(chatKonfig);
                for (int gi = 0; gi < daftarResmi.size(); gi++) {
                    if (Util.chatAdalahGrup(daftarResmi.get(gi))) {
                        adaGrupId = true;
                        break;
                    }
                }
                if (adaGrupId) {
                    ServerService.catatLog("[tg] Chat bot menunjuk grup: semua anggota"
                            + " bisa mengirim perintah. Pakai chat pribadi dengan bot"
                            + " bila memungkinkan.");
                } else {
                    ServerService.catatLog("[tg] Chat bot memakai username, bukan ID numerik:"
                            + " diperlakukan seperti grup (selalu butuh PIN)."
                            + " Ganti ke ID numerik chat pribadi di pengaturan.");
                }
            }
            muatWallMaks(ctx);

            isiNamaBotBilaKosong(ctx, token);

            String body = httpPostForm(ctx, TG_API + token + "/getUpdates",
                    "offset=" + offset + "&timeout=8&limit=10");
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

                    long kiniPoll = System.currentTimeMillis();

                    if (wallMaksTelegram - kiniPoll > BATAS_LOMPATAN_JAM_MS) {
                        synchronized (KUNCI_WALL) {
                            wallMaksTelegram = kiniPoll;
                        }
                    }
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject upd = arr.optJSONObject(i);
                        if (upd == null) {
                            continue;
                        }
                        newOffset = Math.max(newOffset, upd.optLong("update_id", 0) + 1);
                        try {
                            JSONObject cb = upd.optJSONObject("callback_query");
                            if (cb != null) {
                                String dataCb = cb.optString("data", "");
                                String cmdCb = namaPerintah(dataCb);
                                if ("careset".equals(cmdCb)
                                        || "restore".equals(cmdCb)) {
                                    try {
                                        sp.edit().putLong(KEY_TG_OFFSET, newOffset).commit();
                                    } catch (Exception ignored) {
                                    }
                                }
                                tanganiCallback(ctx, cb, chatKonfig);
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

                            String namaUser = c.optString("username", "");
                            long idRuang = c.optLong("id", -1);
                            if (Util.cocokChatBanyak(chatKonfig, idRuang, namaUser)) {

                                long dateMs = msg.optLong("date", 0) * 1000L;
                                if (jamMundur(kiniPoll)) {

                                    if (catatMundurDanBolehIngatkan(kiniPoll)) {
                                        rollbackDitolak++;
                                    }
                                    String teksMundur = msg.optString("text", "")
                                            .trim();
                                    int idPesanMundur = msg.optInt("message_id", 0);
                                    if (idPesanMundur != 0
                                            && pinPerangkatAktif(ctx)) {
                                        boolean adaPending = false;
                                        boolean pendingBasi = false;
                                        if (!teksMundur.isEmpty()
                                                && !teksMundur.startsWith("/")) {
                                            AksiPending pb = ambilPending(ctx,
                                                    idRuang, kiniPoll);
                                            adaPending = !pb.cmd.isEmpty();
                                            pendingBasi = pb.kedaluwarsa;
                                        }
                                        if (pinBasiPerluHapus(teksMundur, adaPending,
                                                pendingBasi)) {
                                            boolean terhapus =
                                                    hapusPesanPerintah(ctx,
                                                            idRuang, idPesanMundur);
                                            peringatkanBilaTakBisaHapus(ctx,
                                                    terhapus, idRuang);
                                        }
                                    }
                                    continue;
                                } else {
                                    catatWall(kiniPoll);
                                }
                                if (!Util.pesanSegar(dateMs,
                                        System.currentTimeMillis(),
                                        STALE_MSG_MS)) {
                                    String teksBasi = msg.optString("text", "")
                                            .trim();
                                    int idPesanBasi = msg.optInt("message_id", 0);
                                    if (idPesanBasi != 0
                                            && pinPerangkatAktif(ctx)) {
                                        boolean adaPending = false;
                                        boolean pendingBasi = false;
                                        if (!teksBasi.isEmpty()
                                                && !teksBasi.startsWith("/")) {
                                            AksiPending pb = ambilPending(ctx,
                                                    idRuang, kiniPoll);
                                            adaPending = !pb.cmd.isEmpty();
                                            pendingBasi = pb.kedaluwarsa;
                                        }
                                        if (pinBasiPerluHapus(teksBasi, adaPending,
                                                pendingBasi)) {
                                            boolean terhapus =
                                                    hapusPesanPerintah(ctx,
                                                            idRuang, idPesanBasi);
                                            peringatkanBilaTakBisaHapus(ctx,
                                                    terhapus, idRuang);
                                        }
                                    }
                                    continue;
                                }
                                String text = msg.optString("text", "").trim();

                                AksiPending pending = AksiPending.TIDAK_ADA;
                                if (!text.isEmpty() && !text.startsWith("/")) {
                                    pending = ambilPending(ctx, idRuang,
                                            System.currentTimeMillis());
                                }
                                boolean pinPending = !pending.cmd.isEmpty();

                                String pin = pinPending ? pinDariBalasan(text) : null;
                                boolean obrolan = pinPending && pin == null;
                                if (pinPending) {

                                    try {
                                        sp.edit().putLong(KEY_TG_OFFSET, newOffset).commit();
                                    } catch (Exception ignored) {
                                    }
                                } else {

                                    if (text.startsWith("/")) {
                                        hapusPending(ctx, idRuang);
                                    }

                                    if (perintahPerluPin(text)) {
                                        try {
                                            sp.edit().putLong(KEY_TG_OFFSET, newOffset).commit();
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }
                                boolean pinBasi = pending.kedaluwarsa;
                                boolean terotorisasi = false;
                                if (pinPending) {
                                    if (obrolan) {

                                        balas(ctx, idRuang, "Balasan bukan PIN."
                                                + " Balas dengan PIN (min. 4 karakter berdigit),"
                                                + " atau bentuk PIN:... bila PIN hanya huruf"
                                                + " atau ber-spasi. Sesi masih terbuka;"
                                                + " kirim perintah lain atau tekan Batal"
                                                + " untuk membatalkan.");
                                    } else {
                                        terotorisasi = handlePinPending(ctx, pending.cmd,
                                                pin, idRuang);
                                    }
                                } else if (pinBasi) {

                                    balas(ctx, idRuang, "Sesi PIN sudah kedaluwarsa"
                                            + " (>5 menit) - aksi tidak dijalankan dan"
                                            + " pesan balasan dihapus dari riwayat."
                                            + " Ketuk tombol lagi untuk PIN baru.");
                                } else {
                                    terotorisasi = handleCommand(ctx, text, idRuang);
                                }

                                int idPesan = msg.optInt("message_id", 0);

                                boolean pinGrup = idRuang < 0 && pinTebakan(text) != null;
                                boolean hapusPin = wajibHapusPesanPin(pinBasi, pinGrup,
                                        pinPending, obrolan, terotorisasi, text);
                                if (idPesan != 0 && hapusPin && pinPerangkatAktif(ctx)) {
                                    boolean terhapus = hapusPesanPerintah(ctx,
                                            idRuang, idPesan);
                                    peringatkanBilaTakBisaHapus(ctx, terhapus, idRuang);
                                } else if (idPesan != 0 && "help".equals(namaPerintah(text))
                                        && pinPerangkatAktif(ctx)
                                        && !sudahUjiHapus(ctx)) {

                                    boolean terhapus = hapusPesanPerintah(ctx,
                                            idRuang, idPesan);
                                    peringatkanBilaTakBisaHapus(ctx, terhapus, idRuang);
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
                        String contoh = contohGalatUpdate == null ? "?"
                                : TgBackup.redaksiDetailGalat(contohGalatUpdate);
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

                    sp.edit().putLong(KEY_TG_OFFSET, newOffset).apply();
                }
            }
        } catch (Exception e) {

            try {
                String jenis = e.getClass().getSimpleName();
                if (!jenis.equals(galatPollTercatat)) {
                    galatPollTercatat = jenis;
                    ServerService.catatLog("[tg] Poll gagal (" + jenis + ").");
                }
            } catch (Exception ignored2) {
            }
        } finally {
            simpanWallMaks(ctx);
            POLLING.set(false);
        }
    }

    static String rakitKeyboard(String[][] tombol) {
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

    static boolean panelMenuValid(String panel) {
        if (panel == null) {
            return false;
        }
        String p = panel.trim();
        return p.equals(MENU_UTAMA) || p.equals(MENU_STATUS) || p.equals(MENU_KONTROL)
                || p.equals(MENU_BACKUP) || p.equals(MENU_UPDATE)
                || p.equals(MENU_SERTIFIKAT) || p.equals(MENU_INFO);
    }

    static String keyboardMenu(String panel) {
        String p = panelMenuValid(panel) ? panel.trim() : MENU_UTAMA;
        if (MENU_STATUS.equals(p)) {
            return rakitKeyboard(new String[][]{
                    {"Status lengkap", "/status"}, {"Log", "/log"},
                    {"Uptime", "/uptime"}, {"Cek sehat", "/alive"},
                    {"Versi", "/versi"}, {"Crash log", "/crashlog"},
                    {"Kembali", "m:" + MENU_UTAMA},
            });
        }
        if (MENU_KONTROL.equals(p)) {
            return rakitKeyboard(new String[][]{
                    {"Start", "/start"}, {"Stop", "/stop"},
                    {"Restart", "/restart"},
                    {"Kembali", "m:" + MENU_UTAMA},
            });
        }
        if (MENU_BACKUP.equals(p)) {
            return rakitKeyboard(new String[][]{
                    {"Backup sekarang", "/backup"}, {"Info restore", "/restore"},
                    {"Kembali", "m:" + MENU_UTAMA},
            });
        }
        if (MENU_UPDATE.equals(p)) {
            return rakitKeyboard(new String[][]{
                    {"Update binary", "/update"}, {"Update web vault", "/webvault"},
                    {"Versi", "/versi"},
                    {"Kembali", "m:" + MENU_UTAMA},
            });
        }
        if (MENU_SERTIFIKAT.equals(p)) {
            return rakitKeyboard(new String[][]{
                    {"Kirim CA", "/ca"}, {"Backup CA", "/cabackup"},
                    {"Reset CA", "/careset"},
                    {"Kembali", "m:" + MENU_UTAMA},
            });
        }
        if (MENU_INFO.equals(p)) {
            return rakitKeyboard(new String[][]{
                    {"Kembali", "m:" + MENU_UTAMA},
            });
        }
        return rakitKeyboard(new String[][]{
                {"Status", "m:" + MENU_STATUS}, {"Kontrol", "m:" + MENU_KONTROL},
                {"Backup", "m:" + MENU_BACKUP}, {"Update", "m:" + MENU_UPDATE},
                {"Sertifikat", "m:" + MENU_SERTIFIKAT}, {"Info", "m:" + MENU_INFO},
        });
    }

    private static String teksMenu(Context ctx, String panel) {
        String p = panelMenuValid(panel) ? panel.trim() : MENU_UTAMA;
        if (MENU_STATUS.equals(p)) {
            return "Status & diagnostik\n"
                    + "Pilih tombol di bawah. /status, /log, dan /crashlog memuat"
                    + " folder data, port, dan URL LAN sehingga butuh PIN bila PIN aktif.";
        }
        if (MENU_KONTROL.equals(p)) {
            return "Kontrol server\n"
                    + "Start menyalakan server, Stop menghentikannya, Restart saat server jalan.\n"
                    + "Bila PIN aktif, ketuk tombol lalu balas dengan PIN (berlaku 5 menit)."
                    + " Aksi ber-PIN hanya bisa dari chat pribadi; grup selalu ditolak.";
        }
        if (MENU_BACKUP.equals(p)) {
            return "Backup & restore\n"
                    + "Backup mengirim salinan database ke chat ini. Restore memakai backup"
                    + " terakhir: tekan Info restore untuk melihat tanggal, lalu ketik"
                    + " /restore YA (+ PIN bila aktif). Server dihentikan saat restore.";
        }
        if (MENU_UPDATE.equals(p)) {
            return "Update binary & web vault\n"
                    + "Update mengunduh dari rilis GitHub lalu restart otomatis bila server"
                    + " sedang jalan. Bila PIN aktif, ketuk tombol lalu balas dengan PIN."
                    + " Versi tertentu tetap bisa dikunci lewat teks: /update 1.32.0 (+ PIN).";
        }
        if (MENU_SERTIFIKAT.equals(p)) {
            return "Sertifikat HTTPS\n"
                    + "Kirim CA mengirim file publik untuk dipasang di perangkat."
                    + " Backup CA menyimpannya ke storage STB. Reset CA membuat sertifikat"
                    + " baru sehingga semua perangkat harus memasang CA lagi.";
        }
        if (MENU_INFO.equals(p)) {
            return "Info & bantuan\n"
                    + "Perintah teks: /status /log /uptime /alive /backup /restore /ca\n"
                    + "/cabackup /careset /crashlog /versi /update /webvault"
                    + " /restart /start /stop /help\n"
                    + "Kunci versi: /update 1.32.0, /webvault 1.32.0; lepas: /update terbaru.\n"
                    + "Bila PIN aktif: /start /stop /restart /backup /update /webvault"
                    + " /restore /careset /status /log /crashlog /ca /cabackup butuh PIN"
                    + " (mis. /stop 123456; PIN ber-spasi: /stop PIN:\"kunci saya\").\n"
                    + "Tombol ber-PIN meminta PIN lewat balasan chat (5 menit) dan pesan PIN"
                    + " dihapus otomatis bila bot punya izin hapus.";
        }
        String status = (ServerService.running || ServerService.isProcessAlive())
                ? "jalan" : "berhenti";
        return "Vaultwarden Host\n"
                + "Server: " + status + "\n"
                + "Pilih menu tombol di bawah. Ketuk Kembali untuk naik satu panel;"
                + " tombol menu berlaku 24 jam, kirim /help lagi bila kedaluwarsa.\n"
                + "Bila PIN aktif, tombol ber-PIN meminta PIN lewat balasan chat."
                + " Catatan: pesan /help pertamamu bisa dihapus bot sebagai uji"
                + " sekali izin hapus pesan ber-PIN.";
    }

    static String kecilkanRentang(String text, int mulai, int akhir) {
        int n = text == null ? 0 : text.length();
        if (mulai < 0) {
            mulai = 0;
        }
        if (akhir > n) {
            akhir = n;
        }
        if (mulai >= akhir) {
            return "";
        }
        char[] kecil = new char[akhir - mulai];
        for (int i = mulai; i < akhir; i++) {
            char c = text.charAt(i);
            kecil[i - mulai] = (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
        }
        return new String(kecil);
    }

    static String namaPerintah(String text) {
        if (text == null) {
            return "";
        }

        int n = text.length();
        int a = 0;
        while (a < n && Character.isWhitespace(text.charAt(a))) {
            a++;
        }
        int b = a;
        while (b < n && !Character.isWhitespace(text.charAt(b))) {
            b++;
        }
        if (a >= n) {
            return "";
        }
        int mulai = a;
        if (mulai < b && text.charAt(mulai) == '/') {
            mulai++;
        }
        int akhir = b;
        for (int i = mulai; i < b; i++) {
            if (text.charAt(i) == '@') {
                akhir = i;
                break;
            }
        }
        return kecilkanRentang(text, mulai, akhir);
    }

    static String ambilArgumen(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        int i = 0;
        while (i < t.length() && !Character.isWhitespace(t.charAt(i))) {
            i++;
        }
        if (i >= t.length()) {
            return "";
        }
        return t.substring(i).trim();
    }

    static final String KEY_TG_BOT_USER = "tg_bot_user";

    static final String KEY_TG_BOT_ID = "tg_bot_id";

    static String idBotDariToken(String token) {
        if (token == null) {
            return "";
        }
        String t = token.trim();
        int pisah = t.indexOf(':');
        if (pisah <= 0) {
            return "";
        }
        String id = t.substring(0, pisah).trim();

        if (id.isEmpty() || id.length() > 20) {
            return "";
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return "";
            }
        }
        return id;
    }

    static String namaBotTersimpan(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String u = TgBackup.amanString(sp, KEY_TG_BOT_USER, "");
            String bersih = u == null ? "" : u.trim();
            if (bersih.isEmpty()) {
                return "";
            }
            String idKini = idBotDariToken(
                    TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
            if (!idKini.isEmpty()
                    && !idKini.equals(TgBackup.amanString(sp, KEY_TG_BOT_ID, ""))) {

                return "";
            }
            return bersih;
        } catch (Exception e) {
            return "";
        }
    }

    static void simpanNamaBot(Context ctx, String username) {
        if (ctx == null || username == null || username.trim().isEmpty()) {
            return;
        }
        try {
            String bersih = Util.rendahAscii(kupasAt(username));
            if (!bersih.isEmpty()) {
                SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                        Context.MODE_PRIVATE);
                sp.edit().putString(KEY_TG_BOT_USER, bersih)
                        .putString(KEY_TG_BOT_ID, idBotDariToken(
                                TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, "")))
                        .apply();
            }
        } catch (Exception ignored) {
        }
    }

    private static volatile long getMeCobaAt = 0;
    private static volatile String getMeCobaToken = "";
    static final long GETME_ULANG_MS = 60L * 60 * 1000;

    static boolean bolehCobaGetMe(String tokenRapi, String tokenCoba, long cobaAt, long kini) {
        if (tokenRapi == null || tokenRapi.isEmpty()) {
            return false;
        }
        if (!tokenRapi.equals(tokenCoba)) {
            return true;
        }
        return cobaAt <= 0 || kini < cobaAt || kini - cobaAt >= GETME_ULANG_MS;
    }

    static void isiNamaBotBilaKosong(Context ctx, String token) {
        if (ctx == null || token == null || token.trim().isEmpty()) {
            return;
        }
        try {
            if (!namaBotTersimpan(ctx).isEmpty()) {
                return;
            }
        } catch (Exception ignored) {
            return;
        }
        if (!bolehCobaGetMe(token.trim(), getMeCobaToken, getMeCobaAt,
                SystemClock.elapsedRealtime())) {
            return;
        }
        final Context appCtx = ctx.getApplicationContext();
        final String tokenBg = token;
        Util.jalankanBg(() -> isiNamaBotSekarang(appCtx, tokenBg));
    }

    private static void isiNamaBotSekarang(Context ctx, String token) {
        if (ctx == null || token == null || token.trim().isEmpty()) {
            return;
        }
        try {
            if (!namaBotTersimpan(ctx).isEmpty()) {
                return;
            }
        } catch (Exception ignored) {
            return;
        }
        String tokRapi = token.trim();
        long kini = SystemClock.elapsedRealtime();
        if (!bolehCobaGetMe(tokRapi, getMeCobaToken, getMeCobaAt, kini)) {
            return;
        }
        getMeCobaToken = tokRapi;
        getMeCobaAt = kini;
        try {
            String nama = parseUsernameBot(httpPostForm(ctx, TG_API + token + "/getMe", ""));
            if (!nama.isEmpty()) {
                simpanNamaBot(ctx, nama);
            }
        } catch (Exception ignored) {
        }
    }

    private static final java.util.regex.Pattern POLA_USERNAME_GETME =
            java.util.regex.Pattern.compile("\"username\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    static String parseUsernameBot(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        try {
            if (!responsOk(body)) {
                return "";
            }
            int res = body.indexOf("\"result\"");
            if (res < 0) {
                return "";
            }
            java.util.regex.Matcher m = POLA_USERNAME_GETME.matcher(body.substring(res));
            if (!m.find()) {
                return "";
            }
            String mentah = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
            return kupasAt(mentah);
        } catch (Exception e) {
            return "";
        }
    }

    static String namaPerintahUntukBot(String text, String usernameBot) {
        if (text == null) {
            return "";
        }

        int n = text.length();
        int a = 0;
        while (a < n) {
            char c = text.charAt(a);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                break;
            }
            a++;
        }
        if (a >= n) {
            return "";
        }
        int b = a;
        while (b < n) {
            char c = text.charAt(b);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                break;
            }
            b++;
        }
        int mulai = a;
        if (mulai < b && text.charAt(mulai) == '/') {
            mulai++;
        }
        int akhir = b;
        for (int i = mulai; i < b; i++) {
            if (text.charAt(i) == '@') {
                akhir = i;
                break;
            }
        }
        String penuh = kecilkanRentang(text, mulai, b);
        if (akhir >= b) {
            return penuh;
        }
        int pisah = akhir - mulai;
        String cmd = penuh.substring(0, pisah);
        String suffix = penuh.substring(pisah + 1);
        if (suffix.isEmpty()) {
            return cmd;
        }
        if (usernameBot == null || usernameBot.trim().isEmpty()) {

            return "";
        }
        String milik = Util.rendahAscii(kupasAt(usernameBot));
        if (suffix.equals(milik)) {
            return cmd;
        }

        return "";
    }

    static boolean chatResmiAdalahGrup(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            return Util.daftarPerluAnggapGrup(
                    Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, "")));
        } catch (Exception e) {
            return true;
        }
    }

    static boolean callbackDataValid(String data) {
        if (data == null) {
            return false;
        }
        String d = data.trim();
        if (d.startsWith("m:")) {
            return panelMenuValid(d.substring(2));
        }
        if (!d.startsWith("/")) {
            return false;
        }
        String cmd = namaPerintah(d);
        for (String[] c : daftarPerintahMenu()) {
            if (c[0].equals(cmd)) {
                return true;
            }
        }
        return false;
    }

    private static void tanganiCallback(Context ctx, JSONObject cb, String chatKonfig) {
        try {
            JSONObject pesan = cb.optJSONObject("message");
            JSONObject ruang = pesan != null ? pesan.optJSONObject("chat") : null;
            long idRuang = ruang != null ? ruang.optLong("id", -1) : -1;
            String namaRuang = ruang != null ? ruang.optString("username", "") : "";

            if (!Util.cocokChatBanyak(chatKonfig, idRuang, namaRuang)) {

                jawabCallback(ctx, cb.optString("id", ""));
                return;
            }

            if (pesan == null) {
                jawabCallback(ctx, cb.optString("id", ""));
                balas(ctx, idRuang, "Tombol sudah kedaluwarsa (>24 jam)."
                        + " Minta keyboard baru dengan /help lalu coba lagi.");
                return;
            }
            long tgl = pesan.optLong("date", 0) * 1000L;
            long kini = System.currentTimeMillis();
            if (jamMundur(kini)) {

                boolean ingatkan = catatMundurDanBolehIngatkan(kini);
                jawabCallback(ctx, cb.optString("id", ""));
                if (ingatkan) {
                    balas(ctx, idRuang, "Jam STB sempat mundur drastis;"
                            + " tombol ditolak sementara. Periksa tanggal & jam STB.");
                }
                return;
            } else {
                catatWall(kini);
            }
            if (tombolKedaluwarsa(tgl, kini)) {
                jawabCallback(ctx, cb.optString("id", ""));
                balas(ctx, idRuang, "Tombol sudah kedaluwarsa (>24 jam)."
                        + " Minta keyboard baru dengan /help lalu coba lagi.");
                return;
            }
            jawabCallback(ctx, cb.optString("id", ""));
            String data = cb.optString("data", "").trim();
            int idPesan = pesan.optInt("message_id", 0);
            if (data.startsWith("m:")) {
                hapusPending(ctx, idRuang);
                String panel = data.substring(2).trim();
                if ("batal".equals(panel)) {
                    tampilkanMenu(ctx, idRuang, idPesan, MENU_UTAMA);
                } else if (panelMenuValid(panel)) {
                    tampilkanMenu(ctx, idRuang, idPesan, panel);
                }
                return;
            }

            if (callbackDataValid(data)) {
                hapusPending(ctx, idRuang);
                if (perluPinTombol(ctx, data)) {
                    mulaiAksiPin(ctx, idRuang, idPesan, data);
                } else {
                    handleCommand(ctx, data, idRuang);
                }
            }
        } catch (Exception e) {

            try {
                ServerService.catatLog("[tg] Gagal memproses tombol: "
                        + TgBackup.redaksiDetailGalat(String.valueOf(e)));
            } catch (Exception ignored) {
            }
        }
    }

    private static void tampilkanMenu(Context ctx, long chatId, int messageId, String panel) {
        String p = panelMenuValid(panel) ? panel.trim() : MENU_UTAMA;
        String teks = teksMenu(ctx, p);
        String kb = keyboardMenu(p);
        if (chatId != 0 && messageId != 0) {
            TgBackup.editMessageKb(ctx, chatId, messageId, teks, kb);
        } else {
            TgBackup.sendMessageKbKe(ctx, String.valueOf(chatId), teks, kb);
        }
    }

    static boolean pendingMasihBerlaku(long dibuat, long kini) {
        return dibuat > 0 && kini >= dibuat && kini - dibuat <= PENDING_AKSI_MS;
    }

    static final class AksiPending {
        static final AksiPending TIDAK_ADA = new AksiPending("", false);
        final String cmd;
        final boolean kedaluwarsa;

        private AksiPending(String cmd, boolean kedaluwarsa) {
            this.cmd = cmd;
            this.kedaluwarsa = kedaluwarsa;
        }
    }

    private static void simpanPending(Context ctx, long chatId, String cmd, long waktu) {
        try {
            ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_TG_PENDING_PREFIX + chatId,
                            waktu + "|" + cmd).apply();
        } catch (Exception ignored) {
        }
    }

    private static AksiPending ambilPending(Context ctx, long chatId, long kini) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String kunci = KEY_TG_PENDING_PREFIX + chatId;
            String isi = TgBackup.amanString(sp, kunci, "");
            if (isi == null || isi.isEmpty()) {
                return AksiPending.TIDAK_ADA;
            }
            int pisah = isi.indexOf('|');
            long waktu = 0;
            if (pisah > 0) {
                try {
                    waktu = Long.parseLong(isi.substring(0, pisah));
                } catch (Exception ignored) {
                    waktu = 0;
                }
            }
            String cmd = pisah > 0 ? isi.substring(pisah + 1) : "";
            boolean basi = !pendingMasihBerlaku(waktu, kini);
            if (basi || !perintahPerluPin(cmd)) {
                sp.edit().remove(kunci).apply();

                return new AksiPending("", basi);
            }
            return new AksiPending(cmd, false);
        } catch (Exception e) {
            return AksiPending.TIDAK_ADA;
        }
    }

    private static void hapusPending(Context ctx, long chatId) {
        if (chatId == 0) {
            return;
        }
        try {
            ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_TG_PENDING_PREFIX + chatId).apply();
        } catch (Exception ignored) {
        }
    }

    private static void mulaiAksiPin(Context ctx, long chatId, int messageId, String data) {
        if (chatId < 0 || !pinPerangkatAktif(ctx)) {
            handleCommand(ctx, data, chatId);
            return;
        }
        simpanPending(ctx, chatId, data, System.currentTimeMillis());
        String teks = "PIN diperlukan\n"
                + "Aksi: " + data + "\n"
                + "Balas pesan ini dengan PIN aplikasi (berlaku 5 menit)."
                + " Pesan PIN akan dihapus otomatis bila bot punya izin hapus.\n"
                + "Kirim perintah lain atau tekan Batal untuk membatalkan.";
        String kb = rakitKeyboard(new String[][]{
                {"Batal", "m:batal"}, {"Menu utama", "m:" + MENU_UTAMA},
        });
        if (messageId != 0) {
            TgBackup.editMessageKb(ctx, chatId, messageId, teks, kb);
        } else {
            TgBackup.sendMessageKbKe(ctx, String.valueOf(chatId), teks, kb);
        }
    }

    static String pinDariBalasan(String teks) {
        String t = teks == null ? "" : teks.trim();
        if (t.isEmpty() || t.length() > 128) {
            return null;
        }
        if (POLA_PIN_MARKER.matcher(t).lookingAt()) {
            String[] pisah = pisahkanPin(t);
            return pisah[1].isEmpty() ? null : pisah[1];
        }

        for (int i = 0; i < t.length(); i++) {
            if (Character.isWhitespace(t.charAt(i))) {
                return null;
            }
        }
        if (!Util.pinKataValid(t)) {
            return null;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') {
                return t;
            }
        }
        return null;
    }

    private static boolean handlePinPending(Context ctx, String cmd, String pin, long idRuang) {
        hapusPending(ctx, idRuang);

        if (idRuang < 0 || !perintahPerluPin(cmd)) {
            return false;
        }

        String p = pin == null ? "" : pin;
        if (!pinPerangkatAktif(ctx)) {

            return handleCommand(ctx, cmd, idRuang);
        }
        if (p.isEmpty() || p.length() > 128) {
            balas(ctx, idRuang, "PIN tidak valid. Ketuk tombol lagi untuk mencoba.");
            return false;
        }
        return handleCommand(ctx, cmd + " " + pinKePenanda(p), idRuang);
    }

    static boolean perintahBerbahaya(String text) {
        String cmd = namaPerintah(text);
        return cmd.equals("start") || cmd.equals("stop") || cmd.equals("restart")
                || cmd.equals("backup") || cmd.equals("restore")
                || cmd.equals("update") || cmd.equals("webvault")
                || cmd.equals("careset");
    }

    static boolean perintahPerluPin(String text) {
        String cmd = namaPerintah(text);
        return perintahBerbahaya(text) || "crashlog".equals(cmd) || "log".equals(cmd)
                || "status".equals(cmd) || "ca".equals(cmd) || "cabackup".equals(cmd);
    }

    static boolean pesanPinWajibHapus(String text) {
        return perintahPerluPin(text);
    }

    static boolean wajibHapusPesanPin(boolean pinBasi, boolean pinGrup,
                                      boolean pinPending, boolean obrolan,
                                      boolean terotorisasi, String text) {
        if (pinBasi || pinGrup) {
            return true;
        }
        if (pinPending && !obrolan) {
            return true;
        }
        if (pinTebakan(text) != null) {
            return true;
        }
        return terotorisasi && (pinPending || pesanPinWajibHapus(text));
    }

    static String pinTebakanArgumen(String arg) {
        String pin = pisahkanPin(arg == null ? "" : arg)[1];
        return pin.length() >= 4 ? pin : null;
    }

    static String pinTebakan(String text) {
        if (text == null || !perintahPerluPin(text)) {
            return null;
        }
        return pinTebakanArgumen(ambilArgumen(text));
    }

    static boolean pinBasiPerluHapus(String teks, boolean adaPending,
            boolean pendingBasi) {
        if (teks == null) {
            return false;
        }
        String t = teks.trim();
        if (pinTebakan(t) != null) {
            return true;
        }
        if (t.isEmpty() || t.startsWith("/")) {
            return false;
        }
        if (!adaPending && !pendingBasi) {
            return false;
        }
        return pinDariBalasan(t) != null;
    }

    static boolean perluPinTombol(android.content.Context ctx, String data) {
        if (!perintahPerluPin(data)) {
            return false;
        }
        return pinPerangkatAktif(ctx);
    }

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

    static final long BATAS_HAPUS_PESAN_MS = 60_000L;

    static boolean galatHapusSesaat(Exception e) {
        if (e == null) {
            return false;
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return false;
        }
        if (e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException
                || e instanceof java.net.UnknownHostException
                || e instanceof java.net.SocketException) {
            return true;
        }
        String m = e.getMessage();
        if (m != null && m.startsWith("Redirect Telegram ditolak")) {
            return false;
        }
        return e instanceof java.io.IOException;
    }

    private static String bacaResponsHapus(InputStream in, long mulaiMs)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (System.currentTimeMillis() - mulaiMs >= BATAS_HAPUS_PESAN_MS) {
                throw new IOException("Lewat tenggat hapus pesan.");
            }
            bos.write(buf, 0, n);
            if (bos.size() > 65536) {
                break;
            }
        }
        return bos.toString("UTF-8");
    }

    static boolean hapusPesanPerintah(Context ctx, long chatId, int messageId) {

        if (chatId == 0 || messageId == 0) {
            return false;
        }
        long mulaiMs = System.currentTimeMillis();
        for (int coba = 1; ; coba++) {
            boolean sesaat = false;
            HttpURLConnection c = null;
            try {
                SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                        Context.MODE_PRIVATE);
                String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
                if (token.isEmpty()) {
                    return false;
                }
                long sisaMs = BATAS_HAPUS_PESAN_MS
                        - (System.currentTimeMillis() - mulaiMs);
                if (sisaMs < 2000) {
                    return false;
                }
                int tSambung = (int) Math.min(15000, sisaMs / 2);
                int tBaca = (int) Math.min(30000, sisaMs - tSambung);
                byte[] body = ("chat_id=" + chatId + "&message_id=" + messageId)
                        .getBytes(StandardCharsets.UTF_8);
                c = TgBackup.bukaPostTelegram(ctx.getApplicationContext(),
                        TG_API + token + "/deleteMessage", body,
                        "application/x-www-form-urlencoded", tSambung, tBaca);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
                TgBackup.tolakRedirectTelegram(c);
                int kode = c.getResponseCode();
                try {
                    long sisaBadan = BATAS_HAPUS_PESAN_MS
                            - (System.currentTimeMillis() - mulaiMs);
                    c.setReadTimeout((int) Math.max(1000, sisaBadan));
                } catch (Exception ignored) {
                }
                if (kode != 200) {
                    sesaat = kode == 429 || kode >= 500;
                    if (!sesaat) {
                        return false;
                    }
                } else {
                    try (InputStream in = c.getInputStream()) {

                        if (responsOk(bacaResponsHapus(in, mulaiMs))) {
                            return true;
                        }
                        return false;
                    }
                }
            } catch (Exception e) {
                sesaat = galatHapusSesaat(e);
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
            if (!sesaat || coba >= 3
                    || System.currentTimeMillis() - mulaiMs >= BATAS_HAPUS_PESAN_MS) {
                return false;
            }
            try {
                Thread.sleep(coba * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    static void peringatkanBilaTakBisaHapus(Context ctx, boolean berhasil, long idRuang) {
        if (berhasil) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                        Context.MODE_PRIVATE);
                if (!TgBackup.amanBoolean(sp, KEY_TG_HAPUS_OK, false)
                        || TgBackup.amanBoolean(sp, KEY_TG_HAPUS_WARN, false)) {
                    sp.edit().putBoolean(KEY_TG_HAPUS_OK, true)
                            .remove(KEY_TG_HAPUS_WARN).apply();
                }
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            if (TgBackup.amanBoolean(sp, KEY_TG_HAPUS_WARN, false)) {
                return;
            }
            sp.edit().putBoolean(KEY_TG_HAPUS_WARN, true).apply();
        } catch (Exception ignored) {
            return;
        }
        balas(ctx, idRuang, "Peringatan: bot tak bisa menghapus pesan ber-PIN"
                + " dari riwayat chat (tanpa hak hapus; di grup jadikan bot admin)."
                + " Hapus manual pesan berisi PIN agar tak dipakai ulang pengintip.");
    }

    static boolean sudahUjiHapus(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            return TgBackup.amanBoolean(sp, KEY_TG_HAPUS_OK, false)
                    || TgBackup.amanBoolean(sp, KEY_TG_HAPUS_WARN, false);
        } catch (Exception e) {
            return true;
        }
    }

    private static void jawabCallback(Context ctx, String callbackId) {
        if (callbackId == null || callbackId.isEmpty()) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        cobaJalankanBg(() -> {
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

    private static void balas(Context ctx, long idRuang, String text) {
        TgBackup.sendMessageKe(ctx, String.valueOf(idRuang), text);
    }

    private static void balasPenting(Context ctx, long idRuang, String text) {
        TgBackup.sendPentingKe(ctx, String.valueOf(idRuang), text);
    }

    private static boolean handleCommand(Context ctx, String text, long idRuang) {
        if (text == null || !text.startsWith("/")) {
            return false;
        }

        if (idRuang < 0 && perintahBerbahaya(text)) {

            balas(ctx, idRuang, "Perintah berbahaya dari grup ditolak."
                    + " Ulangi dari chat pribadi dengan bot (pakai chat pribadi).");
            return false;
        }

        String dasar = namaPerintah(text);
        String verifikasi = namaPerintahUntukBot(text, namaBotTersimpan(ctx));
        if (text.contains("@") && !dasar.equals(verifikasi)) {
            return false;
        }
        String cmd = "/" + verifikasi;
        String arg = ambilArgumen(text);
        switch (cmd) {
            case "/start":
            case "/stop":
            case "/restart":
                return perintahKontrol(ctx, cmd, arg, idRuang);
            case "/backup":
            case "/restore":
                return perintahCadangan(ctx, cmd, arg, idRuang);
            case "/ca":
            case "/cabackup":
            case "/careset":
                return perintahSertifikat(ctx, cmd, arg, idRuang);
            case "/update":
            case "/webvault":
                return perintahUpdate(ctx, cmd, arg, idRuang);
            case "/status":
            case "/log":
            case "/uptime":
            case "/alive":
            case "/versi":
            case "/crashlog":
                return perintahInfo(ctx, cmd, arg, idRuang);
            case "/help":
                TgBackup.sendMessageKbKe(ctx, String.valueOf(idRuang), teksMenu(ctx, MENU_UTAMA),
                        keyboardMenu(MENU_UTAMA));
                return false;
            default:
                balas(ctx, idRuang, "Perintah tidak dikenal. Ketik /help");
                return false;
        }
    }

    private static boolean perintahKontrol(Context ctx, String cmd, String arg, long idRuang) {
        if (authDangerous(ctx, arg, idRuang) == null) {
            return false;
        }
        if ("/start".equals(cmd)) {
            if (ServerService.running || ServerService.isProcessAlive()) {
                balas(ctx, idRuang, "Server sudah jalan.");
            } else {
                try {
                    if (ServerService.start(ctx)) {
                        balas(ctx, idRuang, "Perintah diterima: server start...");
                    } else {
                        balas(ctx, idRuang, "Gagal start dari background (batasan Android). "
                                + "Buka app lalu tekan Start, atau aktifkan 'Auto start saat boot' "
                                + "lalu reboot HP.");
                    }
                } catch (Throwable t) {
                    balas(ctx, idRuang, "Gagal start dari background (batasan Android). "
                            + "Buka app lalu tekan Start, atau aktifkan 'Auto start saat boot' "
                            + "lalu reboot HP.");
                }
            }
            return true;
        }
        if ("/stop".equals(cmd)) {
            try {
                if (ServerService.stop(ctx)) {
                    balas(ctx, idRuang, "Perintah diterima: server stop...");
                } else {
                    balas(ctx, idRuang, "Gagal stop dari background (batasan Android). "
                            + "Buka app lalu tekan Stop.");
                }
            } catch (Throwable t) {
                balas(ctx, idRuang, "Gagal stop dari background (batasan Android). "
                        + "Buka app lalu tekan Stop.");
            }
            return true;
        }

        if (!ServerService.running && !ServerService.isProcessAlive()) {
            balas(ctx, idRuang, "Restart hanya bisa saat server berjalan.");
            return true;
        }
        long kiniRestart = SystemClock.elapsedRealtime();
        if (dalamJedaBerat(restartManualTerakhirMs, kiniRestart)) {
            balas(ctx, idRuang, "Restart baru saja dilakukan. Tunggu sebentar"
                    + " (±30 dtk) sebelum restart lagi.");
            return true;
        }
        restartManualTerakhirMs = kiniRestart;
        try {
            if (ServerService.restart(ctx)) {
                balas(ctx, idRuang, "Perintah diterima: server restart...");
            } else {
                balas(ctx, idRuang, "Restart hanya bisa saat server berjalan.");
            }
        } catch (Throwable t) {
            balas(ctx, idRuang, "Restart hanya bisa saat server berjalan.");
        }
        return true;
    }

    private static boolean perintahCadangan(Context ctx, String cmd, String arg, long idRuang) {
        if ("/backup".equals(cmd)) {
            if (authDangerous(ctx, arg, idRuang) == null) {
                return false;
            }
            runBeratDenganKunci(ctx, idRuang, () -> {
                try {
                    balas(ctx, idRuang, TgBackup.backupNow(ctx));
                } catch (Exception e) {
                    balas(ctx, idRuang, TgBackup.pesanGalatBackup(e));
                }
            });
            return true;
        }
        String cleanRestore = authDangerous(ctx, arg, idRuang);
        if (cleanRestore == null) {
            return false;
        }
        if (isRestoreConfirm(cleanRestore)) {
            runBeratDenganKunci(ctx, idRuang, () -> {
                try {
                    balas(ctx, idRuang, "Mengunduh backup terakhir...");
                    balas(ctx, idRuang, doRestore(ctx)
                            + " Tekan /start untuk menjalankan.");
                } catch (Exception e) {

                    String mentah = e.getMessage() == null
                            ? String.valueOf(e) : e.getMessage();
                    String aman = TgBackup.redaksiDetailGalat(mentah);
                    ServerService.catatLog("[tg] Restore gagal: " + aman);
                    balas(ctx, idRuang, "Restore gagal: " + aman + ". "
                            + Updater.saranKoneksi(e));
                }
            });
        } else {
            balas(ctx, idRuang, restoreInfoText(ctx));
        }
        return true;
    }

    private static boolean perintahSertifikat(Context ctx, String cmd, String arg, long idRuang) {
        if ("/careset".equals(cmd)) {
            if (authDangerous(ctx, arg, idRuang) == null) {
                return false;
            }
            runBeratDenganKunci(ctx, idRuang, () -> {
                try {
                    balas(ctx, idRuang, TgBackup.resetSertifikat(ctx));
                } catch (Exception e) {
                    balas(ctx, idRuang, "Reset sertifikat gagal: "
                            + TgBackup.redaksiDetailGalat(e.getMessage()));
                }
            });
            return true;
        }
        boolean perluPin = pinPerangkatAktif(ctx) || chatResmiAdalahGrup(ctx);
        if (perluPin) {
            if ("/ca".equals(cmd)) {
                if (authDangerous(ctx, arg, "ca", idRuang) == null) {
                    return false;
                }
            } else if (authDangerous(ctx, arg, "cabackup", idRuang) == null) {
                return false;
            }
        }
        if ("/ca".equals(cmd)) {
            runBeratDenganKunci(ctx, idRuang, () -> {
                try {
                    balas(ctx, idRuang, TgBackup.kirimCa(ctx));
                } catch (Exception e) {
                    balas(ctx, idRuang, "Kirim CA gagal: "
                            + TgBackup.redaksiDetailGalat(e.getMessage()));
                }
            });
        } else {
            runBeratDenganKunci(ctx, idRuang, () -> {
                try {
                    balas(ctx, idRuang, TgBackup.backupCaKeStorage(ctx));
                } catch (Exception e) {
                    balas(ctx, idRuang, "Backup CA gagal: "
                            + TgBackup.redaksiDetailGalat(e.getMessage()));
                }
            });
        }
        return perluPin;
    }

    private static boolean perintahUpdate(Context ctx, String cmd, String arg, long idRuang) {
        String bersih = authDangerous(ctx, arg, idRuang);
        if (bersih == null) {
            return false;
        }
        if (!argumenVersiValid(bersih)) {
            balas(ctx, idRuang, "Versi tidak valid."
                    + " Contoh: " + cmd + " 1.32.0 atau " + cmd + " terbaru");
            return true;
        }
        boolean kunci = !bersih.isEmpty();
        String versi = Updater.normalisasiPinVersi(bersih);
        if ("/update".equals(cmd)) {
            unduhBinerDariBot(ctx, idRuang, bersih, kunci, versi);
        } else {
            unduhWebVaultDariBot(ctx, idRuang, bersih, kunci, versi);
        }
        return true;
    }

    private static void unduhBinerDariBot(Context ctx, long idRuang, String versiDiminta,
                                          boolean kunci, String versi) {
        runBeratDenganKunci(ctx, idRuang, () -> {
            String pinLama = Updater.kuncianBinary(ctx);
            try {
                boolean was = ServerService.running || ServerService.isProcessAlive();
                if (kunci && !Updater.simpanKuncianBinary(ctx, versiDiminta)) {
                    balas(ctx, idRuang, "Versi tidak valid."
                            + " Contoh: /update 1.32.0 atau /update terbaru");
                    return;
                }
                String imbuhShim = "";
                if (KernelCompat.legacyPerangkat()) {
                    try {
                        Updater.ensureShimFile(ctx);
                        imbuhShim = " Shim getrandom siap.";
                    } catch (Exception se) {
                        imbuhShim = " Shim gagal: " + se.getMessage();
                    }
                }
                String msg = Updater.tryUpdateVersi(ctx, versi) + imbuhShim;
                if (Updater.binaryBerubah(msg)) {
                    AutoUpdate.batalkanNotifikasiUpdate(ctx);
                }
                balasHasilUnduh(ctx, idRuang, msg, Updater.binaryBerubah(msg), was);
            } catch (Exception e) {

                if (kunci && versi != null) {
                    Updater.simpanKuncianBinary(ctx,
                            pinLama == null ? "" : pinLama);
                }
                String pesan = e.getMessage();
                String ramah = pesan != null && pesan.contains("Cek ")
                        ? pesan : Updater.pesanGalatUnduh("Unduh binary", e);
                balas(ctx, idRuang, "Update gagal: " + ramah);
            }
        });
    }

    private static void unduhWebVaultDariBot(Context ctx, long idRuang, String versiDiminta,
                                             boolean kunci, String versi) {
        runBeratDenganKunci(ctx, idRuang, () -> {
            String pinLama = Updater.kuncianWebVault(ctx);
            try {
                boolean was = ServerService.running || ServerService.isProcessAlive();
                if (kunci && !Updater.simpanKuncianWebVault(ctx, versiDiminta)) {
                    balas(ctx, idRuang, "Versi tidak valid."
                            + " Contoh: /webvault 1.32.0 atau /webvault terbaru");
                    return;
                }
                String msg = Updater.updateWebVaultVersi(ctx, versi);
                balasHasilUnduh(ctx, idRuang, msg, webVaultBerubah(msg), was);
            } catch (Exception e) {
                if (kunci && versi != null) {
                    Updater.simpanKuncianWebVault(ctx,
                            pinLama == null ? "" : pinLama);
                }
                String pesan = e.getMessage();
                String ramah = pesan != null && pesan.contains("Cek ")
                        ? pesan : Updater.pesanGalatUnduh("Unduh web-vault", e);
                balas(ctx, idRuang, "Update web-vault gagal: " + ramah);
            }
        });
    }

    private static void balasHasilUnduh(Context ctx, long idRuang, String msg,
                                        boolean berubah, boolean was) {
        if (!berubah) {
            balas(ctx, idRuang, msg);
            return;
        }
        if (!was) {
            balas(ctx, idRuang, msg + " Tekan /start untuk memakai.");
            return;
        }
        if (ServerService.restart(ctx)) {
            balas(ctx, idRuang, msg + " Restart otomatis...");
        } else {
            balas(ctx, idRuang, msg + " Restart manual dari app"
                    + " (batasan background Android).");
        }
    }

    private static boolean perintahInfo(Context ctx, String cmd, String arg, long idRuang) {
        if ("/uptime".equals(cmd)) {
            long up = ServerService.uptimeMs();
            if (up <= 0) {
                balas(ctx, idRuang, "Server sedang berhenti.");
            } else {
                balas(ctx, idRuang, "Server jalan selama " + durationText(up) + ".");
            }
            return false;
        }
        if ("/alive".equals(cmd)) {
            if (!Util.jalankanBg(() -> balas(ctx, idRuang,
                    pesanAlive(ServerService.pingAlive(ctx))))) {
                balas(ctx, idRuang, "Sistem sibuk, coba lagi sebentar.");
            }
            return false;
        }
        if ("/versi".equals(cmd)) {
            balas(ctx, idRuang, teksVersi(ctx));
            return false;
        }
        boolean perluPin = pinPerangkatAktif(ctx) || chatResmiAdalahGrup(ctx);
        if (perluPin && authDangerous(ctx, arg, cmd.substring(1), idRuang) == null) {
            return false;
        }
        if ("/status".equals(cmd)) {
            balas(ctx, idRuang, statusText(ctx));
        } else if ("/log".equals(cmd)) {
            balas(ctx, idRuang, tailLog());
        } else {
            String crash = ServerService.crashLogText(ctx);
            if (crash == null || crash.trim().isEmpty()) {
                balas(ctx, idRuang, "Belum ada crash log tersimpan.");
            } else {
                balas(ctx, idRuang, potongEkor(crash, 3500));
            }
        }
        return perluPin;
    }

    static boolean isRestoreConfirm(String arg) {
        if (arg == null) {
            return false;
        }
        String a = Util.rendahAscii(arg.trim());
        return a.equals("ya") || a.equals("yes") || a.equals("konfirmasi")
                || a.equals("confirm");
    }

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
                ? Util.formatTanggal("yyyy-MM-dd HH:mm", new Date(last))
                : "?";
        return "Backup terakhir: " + name + " (" + tgl + ").\n"
                + "Server akan dihentikan & database ditimpa."
                + " Balas /restore YA untuk lanjut.";
    }

    static String doRestore(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String pass = TgBackup.amanString(sp, TgBackup.KEY_TG_PASS, "");

        File tmp = new File(ctx.getCacheDir(),
                "vwtg-restore-bot-" + TgBackup.stempelUnik() + ".zip");
        File plain = TgBackup.berkasPlainSementara(
                ctx.getCacheDir(), "restore-bot", ".zip");
        TgBackup.sapuTmpPlainBasi(ctx.getCacheDir(), plain.getName());
        boolean dikunci = false;
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

            if (!TgBackup.kunciRestore()) {
                throw new IOException("Tugas backup/restore lain sedang berjalan,"
                        + " coba lagi sebentar.");
            }
            dikunci = true;
            return TgBackup.restoreFromZip(ctx, zip);
        } finally {
            if (dikunci) {
                TgBackup.lepasRestore();
            }

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

    static String authDangerous(Context ctx, String arg, long idRuang) {

        return authDangerous(ctx, arg, null, idRuang);
    }

    static boolean wajibPinSaatPinMati(boolean berbahaya, boolean chatAnggapGrup) {
        return berbahaya || chatAnggapGrup;
    }

    static String authDangerous(Context ctx, String arg, String cmd, long idRuang) {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);

        String hash = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");

        boolean need = TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false);
        String t = arg == null ? "" : arg.trim();
        boolean berbahaya = cmd == null || cmd.trim().isEmpty()
                || perintahBerbahaya(cmd);
        if (idRuang < 0) {

            balas(ctx, idRuang, "Perintah ber-PIN hanya bisa dari chat pribadi"
                    + " bot. PIN yang diketik di grup terbaca anggota lain.");
            return null;
        }
        if (!need) {

            if (wajibPinSaatPinMati(berbahaya, chatResmiAdalahGrup(ctx))) {
                balas(ctx, idRuang, "Perintah ini butuh PIN app."
                        + " Aktifkan PIN dulu di Pengaturan, lalu ulangi dengan"
                        + " PIN (mis. /stop 123456 atau /stop PIN:123456).");
                return null;
            }
            return t;
        }

        long sekarang = System.currentTimeMillis();
        long sisa = PinGate.sisaKunciMs(ctx, sekarang);
        if (sisa > 0) {
            balasPenting(ctx, idRuang, "PIN terkunci sementara (kebanyakan gagal)."
                    + " Coba lagi " + ((sisa + 59000) / 60000) + " menit.");
            return null;
        }
        String[] pisah = pisahkanPin(t);
        String rest = pisah[0];
        String pin = pisah[1];

        if (pin.length() < 4) {
            balas(ctx, idRuang, "Perintah ini butuh PIN app"
                    + " (mis. /stop 123456 atau /stop PIN:123456; bila PIN ber-spasi: /stop PIN:\"kunci saya\";"
                    + " PIN huruf wajib bentuk PIN:ab12).");
            return null;
        }
        boolean cocok = PinCrypto.verify(hash, pin);
        if (cocok) {

            String segar = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
            if (segar != null && !segar.isEmpty() && !segar.equals(hash)) {
                hash = segar;
                cocok = PinCrypto.verify(hash, pin);
            }
        }
        PinGate.catatHasil(ctx, cocok, sekarang);
        if (cocok) {
            if (PinCrypto.perluUpgradeHash(hash)) {
                try {

                    String kini = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
                    if (hash.equals(kini)) {
                        sp.edit().putString(PinGate.KEY_PIN_HASH, PinCrypto.hash(pin)).apply();
                    }
                } catch (Exception ignored) {
                }
            }
            return rest;
        }
        balas(ctx, idRuang, "Perintah ini butuh PIN app"
                + " (mis. /stop 123456 atau /stop PIN:123456; bila PIN ber-spasi: /stop PIN:\"kunci saya\";"
                + " PIN huruf wajib bentuk PIN:ab12).");
        return null;
    }

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

    static String teksVersi(Context ctx) {
        String bin = Updater.currentServerVersion(ctx);
        String dataDir = null;
        try {
            dataDir = TgBackup.amanString(
                    ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE),
                    ServerService.KEY_DATA_DIR, null);
        } catch (Exception ignored) {
        }
        if (dataDir == null || dataDir.isEmpty()) {
            dataDir = ServerService.dataDirBawaanSegar();
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

    static String[] pisahkanPin(String arg) {
        String t = arg == null ? "" : arg.trim();

        int penanda = t.lastIndexOf(PENANDA_PIN_HEX);
        if (penanda >= 0) {
            String pin = pinDariPenanda(t);
            if (!pin.isEmpty()) {
                return new String[]{rapikanSpasi(t.substring(0, penanda).trim()), pin};
            }
        }

        java.util.regex.Matcher m = POLA_PIN_MARKER.matcher(t);

        int awalMatch = -1;
        int akhirMarker = -1;

        int awalKutip = -1;
        int akhirKutip = -1;
        while (m.find()) {
            awalMatch = m.start();
            akhirMarker = m.end();
            if (awalKutip < 0) {
                String calon = t.substring(akhirMarker).trim();
                if (!calon.isEmpty()
                        && (calon.charAt(0) == '"' || calon.charAt(0) == '\'')) {
                    awalKutip = m.start();
                    akhirKutip = m.end();
                }
            }
        }
        if (awalKutip >= 0) {
            awalMatch = awalKutip;
            akhirMarker = akhirKutip;
        }
        if (awalMatch >= 0) {
            String mentah = t.substring(akhirMarker).trim();

            String sebelum = rapikanSpasi(t.substring(0, awalMatch).trim());
            String pin;
            String sisa;
            if (mentah.length() >= 2 && (mentah.startsWith("\"") || mentah.startsWith("'"))) {
                char kutip = mentah.charAt(0);
                int tutup = mentah.indexOf(kutip, 1);
                if (tutup > 0) {
                    pin = mentah.substring(1, tutup);
                    String ekstra = mentah.substring(tutup + 1).trim();
                    sisa = sebelum;
                    if (!ekstra.isEmpty()) {
                        sisa = sebelum.isEmpty() ? rapikanSpasi(ekstra)
                                : rapikanSpasi(sebelum + " " + ekstra);
                    }
                } else {
                    pin = mentah;
                    sisa = sebelum;
                }
            } else {
                pin = mentah;
                sisa = sebelum;
            }
            if (pin.isEmpty()) {
                return new String[]{t, ""};
            }
            return new String[]{sisa, pin};
        }

        int i = -1;
        for (int k = t.length() - 1; k >= 0; k--) {
            if (Character.isWhitespace(t.charAt(k))) {
                i = k;
                break;
            }
        }
        if (i < 0) {

            if (t.equalsIgnoreCase("terbaru") || t.equalsIgnoreCase("latest")) {
                return new String[]{t, ""};
            }

            if (Util.numerik4Plus(t)) {
                return new String[]{"", t};
            }
            return new String[]{t, ""};
        }
        String kandidat = t.substring(i + 1);

        if (kandidat.equalsIgnoreCase("terbaru") || kandidat.equalsIgnoreCase("latest")) {
            return new String[]{t, ""};
        }

        if (kandidat.equalsIgnoreCase("PIN:")) {
            return new String[]{t, ""};
        }

        if (Updater.normalisasiPinVersi(kandidat) != null) {
            return new String[]{t, ""};
        }

        if (kandidat.contains(".") || kandidat.contains("/") || kandidat.contains("\\")) {
            return new String[]{t, ""};
        }
        if (Util.pinKataValid(kandidat)) {

            return new String[]{rapikanSpasi(t.substring(0, i).trim()), kandidat};
        }
        return new String[]{t, ""};
    }

    private static void runBeratDenganKunci(Context ctx, long idRuang, Runnable task) {
        if (dalamJedaBerat(tugasBeratBerakhirMs, SystemClock.elapsedRealtime())) {
            balas(ctx, idRuang, "Tugas berat baru saja selesai. Tunggu sebentar"
                    + " (±30 dtk) sebelum perintah berat berikutnya.");
            return;
        }
        if (!TUGAS_BERAT.compareAndSet(false, true)) {
            balas(ctx, idRuang, "Tugas lain masih berjalan, coba lagi sebentar.");
            return;
        }
        boolean masuk = runWithWakeLock(ctx, () -> {
            try {
                task.run();
            } finally {
                TUGAS_BERAT.set(false);
                tugasBeratBerakhirMs = SystemClock.elapsedRealtime();
            }
        });
        if (!masuk) {

            TUGAS_BERAT.set(false);
            tugasBeratBerakhirMs = SystemClock.elapsedRealtime();
            balas(ctx, idRuang, "Sistem sibuk, coba lagi sebentar.");
        }
    }

    static final long WAKELOCK_TUGAS_BERAT_MS = 20L * 60L * 1000L;

    static final long BATAS_JAGA_KUNCI_MS = 6L * 3600L * 1000L;

    static boolean kunciMasihDijaga(long mulaiMs, long kiniMs, boolean tugasSelesai) {
        if (tugasSelesai) {
            return false;
        }
        return kiniMs - mulaiMs < BATAS_JAGA_KUNCI_MS;
    }

    private static boolean runWithWakeLock(Context ctx, Runnable task) {

        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vaultwarden:tgbot-task");
                wl.setReferenceCounted(false);

                wl.acquire(WAKELOCK_TUGAS_BERAT_MS);
            }
        } catch (Exception ignored) {
            wl = null;
        }
        final PowerManager.WakeLock milik = wl;
        final AtomicBoolean selesai = new AtomicBoolean(false);
        boolean masuk = cobaJalankanBg(() -> {
            try {
                task.run();
            } finally {
                selesai.set(true);
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
            return false;
        }
        if (milik != null) {
            final long mulai = SystemClock.elapsedRealtime();
            final Runnable[] jaga = new Runnable[1];
            jaga[0] = () -> {
                if (!kunciMasihDijaga(mulai, SystemClock.elapsedRealtime(),
                        selesai.get())) {
                    return;
                }
                try {
                    milik.acquire(WAKELOCK_TUGAS_BERAT_MS);
                } catch (Exception ignored) {
                    return;
                }
                Util.postTunda(jaga[0], WAKELOCK_TUGAS_BERAT_MS / 2L);
            };
            Util.postTunda(jaga[0], WAKELOCK_TUGAS_BERAT_MS / 2L);
        }
        return true;
    }

    static final long TOMBOL_KEDALUWARSA_MS = 24L * 3600 * 1000;
    static final long TOLERANSI_JAM_MS = 5 * 60_000;

    static final long BATAS_LOMPATAN_JAM_MS = 30L * 24 * 3600 * 1000;

    static boolean wallBolehMaju(long maksLama, long kini) {
        if (kini < 1_000_000_000_000L) {
            return false;
        }
        return kini > maksLama
                && (maksLama <= 0 || kini - maksLama <= BATAS_LOMPATAN_JAM_MS);
    }

    static boolean jamMundur(long kini) {
        long maks = wallMaksTelegram;
        return maks > 0 && kini < maks - TOLERANSI_JAM_MS;
    }

    static void catatWall(long kini) {
        synchronized (KUNCI_WALL) {
            if (wallBolehMaju(wallMaksTelegram, kini)) {
                wallMaksTelegram = kini;
            }
        }
    }

    static void muatWallMaks(Context ctx) {

        if (wallMaksTersimpan != Long.MIN_VALUE) {
            return;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            long s = TgBackup.amanLong(sp, KEY_TG_WALL_MAKS, 0);

            if (s - System.currentTimeMillis() > BATAS_LOMPATAN_JAM_MS) {
                s = 0;
                try {
                    sp.edit().remove(KEY_TG_WALL_MAKS).apply();
                } catch (Exception ignored) {
                }
            }
            wallMaksTersimpan = s;
            synchronized (KUNCI_WALL) {
                if (s > wallMaksTelegram) {
                    wallMaksTelegram = s;
                }
            }
        } catch (Exception ignored) {
        }
    }

    static void simpanWallMaks(Context ctx) {
        try {
            long kini = wallMaksTelegram;
            if (kini == wallMaksTersimpan) {
                return;
            }
            SharedPreferences sp = ctx.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            if (wallMaksTersimpan == Long.MIN_VALUE) {

                long tersimpan = TgBackup.amanLong(sp, KEY_TG_WALL_MAKS, 0);
                wallMaksTersimpan = tersimpan;
                if (tersimpan == kini) {
                    return;
                }
            }
            sp.edit().putLong(KEY_TG_WALL_MAKS, kini).apply();
            wallMaksTersimpan = kini;
        } catch (Exception ignored) {
        }
    }

    static boolean peringatanMundurJatuhTempo(long kiniElapsed, long terakhir) {
        if (terakhir == 0) {
            return true;
        }
        if (kiniElapsed < terakhir) {
            return true;
        }
        return kiniElapsed - terakhir >= PERINGATAN_MUNDUR_MS;
    }

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

    static boolean tombolKedaluwarsa(long tglMs, long sekarang) {
        if (tglMs <= 0) {
            return true;
        }
        if (tglMs > sekarang + TOLERANSI_JAM_MS) {
            return true;
        }
        return sekarang - tglMs > TOMBOL_KEDALUWARSA_MS;
    }

    private static String tailLog() {

        String mentah = ServerService.tailLog(30);
        String log = LogActivity.butuhSamaran(mentah) ? LogActivity.samarkanLog(mentah) : mentah;
        if (log.isEmpty()) {
            return "Log kosong.";
        }
        return potongEkor(log, 3500);
    }

    static String potongEkor(String teks, int maks) {
        if (teks == null || teks.length() <= maks) {
            return teks == null ? "" : teks;
        }
        int mulai = teks.length() - maks;

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
        StringBuilder o = new StringBuilder(24);
        if (h > 0) {
            return o.append(h).append(" jam ").append(m).append(" menit").toString();
        }
        if (m > 0) {
            return o.append(m).append(" menit ").append(s % 60).append(" detik").toString();
        }
        return o.append(s).append(" detik").toString();
    }

    static String statusText(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = ServerService.dataDirBawaanSegar();
        }
        String version = ServerService.binaryVersion.isEmpty()
                ? "?" : ServerService.binaryVersion;
        File db = new File(dataDir, "db.sqlite3");
        String dbInfo = db.exists() ? TgBackup.humanBytes(db.length()) : "belum ada";
        long lastBackup = TgBackup.amanLong(sp, TgBackup.KEY_TG_LAST, 0);
        String backupInfo = lastBackup > 0
                ? Util.formatTanggal("yyyy-MM-dd HH:mm", new Date(lastBackup))
                : "belum pernah";
        String wv = Updater.webVaultFromVersion(ctx);
        String wvInfo = wv != null ? wv : "belum ada";
        long rss = ServerService.processRssKb();
        String ram = rss > 0 ? TgBackup.humanBytes(rss * 1024) : "?";
        long up = ServerService.uptimeMs();
        String uptime = up > 0 ? durationText(up) : "-";
        long free = TgBackup.freeBytes(ctx, dataDir);
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
                .append("URL: ").append(ServerService.localUrlDenganCatatan(ctx));
        if (!restarts.isEmpty()) {
            sb.append("\n").append(restarts);
        }
        return sb.toString();
    }

    static final int REQ_POLL = 3;

    private static PendingIntent pendingIntent(Context ctx) {

        Intent i = new Intent(ctx, TgBotReceiver.class).setAction(ACTION_POLL)
                .putExtra(AlarmReceiver.EXTRA_RAHASIA, TgBackup.rahasiaAlarm(ctx));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, REQ_POLL, i, flags);
    }

    static String ringkasGalatTelegram(String body, int kodeHttp) {
        int kode = kodeHttp;
        String desc = "";
        if (body != null) {
            int ik = body.indexOf("\"error_code\"");
            if (ik >= 0) {
                int j = ik + 12;
                while (j < body.length() && (body.charAt(j) < '0' || body.charAt(j) > '9')) {
                    j++;
                }
                int awal = j;
                while (j < body.length() && body.charAt(j) >= '0' && body.charAt(j) <= '9') {
                    j++;
                }
                if (j > awal) {
                    try {
                        kode = Integer.parseInt(body.substring(awal, j));
                    } catch (Exception ignored) {
                    }
                }
            }
            int id = body.indexOf("\"description\"");
            if (id >= 0) {
                int i = body.indexOf('"', id + 13);
                if (i >= 0) {
                    int j = i + 1;
                    while (j < body.length() && body.charAt(j) != '"') {
                        j++;
                    }
                    desc = body.substring(i + 1, j).trim();
                    if (desc.length() > 160) {
                        desc = desc.substring(0, 160);
                    }
                }
            }
        }
        if (kode <= 0) {
            return desc.isEmpty() ? "tanpa keterangan" : desc;
        }
        return desc.isEmpty() ? ("HTTP " + kode) : ("HTTP " + kode + ": " + desc);
    }

    private static void catatGalatPollSekali(String ringkas) {
        try {
            if (ringkas == null || ringkas.equals(galatPollTercatat)) {
                return;
            }
            galatPollTercatat = ringkas;
            ServerService.catatLog("[tg] Telegram menolak permintaan (" + ringkas + ").");
        } catch (Exception ignored) {
        }
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
            String isi = null;
            if (mentah != null) {
                try (InputStream is = mentah) {
                    isi = TgBackup.bacaResponsBatas(is);
                }
            }
            if (code < 200 || code >= 300) {

                catatGalatPollSekali(ringkasGalatTelegram(isi, code));
                return null;
            }
            return isi;
        } catch (Exception e) {

            catatGalatPollSekali(e.getClass().getSimpleName());
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
