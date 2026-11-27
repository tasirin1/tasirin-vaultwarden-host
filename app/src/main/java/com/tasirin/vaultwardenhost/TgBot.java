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
    static final String KEY_TG_OFFSET_BOT_ID = "tg_bot_offset_id";
    static final String KEY_TG_MENU_HASH = "tg_menu_hash";

    static final String KEY_TG_MENU_FP = "tg_menu_fp";
    static final int MENU_REV = 5;

    static final String KEY_TG_PENDING_PREFIX = "tg_pending_aksi_";
    static final String KEY_TG_WARN_ASING_PREFIX = "tg_warn_pin_asing_";

    static final long PENDING_AKSI_MS = 5 * 60_000L;

    static final String MENU_UTAMA = "utama";
    static final String MENU_STATUS = "status";
    static final String MENU_KONTROL = "kontrol";
    static final String MENU_BACKUP = "backup";
    static final String MENU_UPDATE = "update";
    static final String MENU_SERTIFIKAT = "sertifikat";
    static final String MENU_INFO = "info";
    // Audit 1463: irama poll dilonggarkan (20 dtk + long-poll 8 dtk
    // = ±3 koneksi HTTPS/menit 24 jam). Long-poll 50 dtk membuat
    // satu koneksi menunggu hampir seirama alarm 60 dtk; getUpdates
    // tetap kembali seketika saat ada update, jadi respons tak
    // berubah — hanya koneksi diam yang berkurang ±3x.
    private static final long POLL_INTERVAL_MS = 60_000;
    private static final long STALE_MSG_MS = 5 * 60_000;

    // Audit 2967: cap waktu kabar perintah-basi terakhir
    // (jatah balasan kedaluwarsa, sekali per 10 menit).
    private static volatile long kabarBasiTerakhir = 0;
    private static final AtomicBoolean POLLING = new AtomicBoolean(false);

    private static volatile String grupDiperingatkan = "";

    private static final AtomicBoolean TUGAS_BERAT = new AtomicBoolean(false);

    // Audit 2949: penghitung putaran titik awal
    // potret antrean hapus tertunda.
    private static final java.util.concurrent.atomic.AtomicInteger
            ROTASI_HAPUS_TERTUNDA =
            new java.util.concurrent.atomic.AtomicInteger();

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

    // Audit 2789: perintah info ringan dulu tanpa jeda sama
    // sekali - satu anggota grup bisa membanjiri antrean dengan
    // /log berulang. Jeda per chat 10 detik, pola yang sama
    // seperti jeda tugas berat (jam mundur = gagal-terbuka).
    static final long JEDA_INFO_MS = 10_000L;
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long>
            infoTerakhirPerChat = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean dalamJedaInfo(long terakhirMs, long kiniMs) {
        return terakhirMs > 0 && kiniMs >= terakhirMs
                && kiniMs - terakhirMs < JEDA_INFO_MS;
    }

    private static volatile long wallMaksTelegram = 0;

    private static volatile long wallMaksTersimpan = Long.MIN_VALUE;

    private static final Object KUNCI_WALL = new Object();

    private static final Object KUNCI_PERINGATAN = new Object();

    static final String KEY_TG_WALL_MAKS = "tg_wall_maks";

    static final String KEY_TG_HAPUS_WARN = "tg_hapus_warn";

    static final String KEY_TG_HAPUS_WARN_AT = "tg_hapus_warn_at";

    static final long JEDA_PERINGATAN_HAPUS_MS = 24L * 3600_000L;

    // Audit 2878: peringatan gagal-hapus berulang
    // berkala selama kegagalan berlanjut, bukan
    // sekali seumur kegagalan.
    static boolean bolehPeringatkanHapusLagi(long terakhirMs, long kiniMs) {
        return terakhirMs <= 0 || kiniMs < terakhirMs
                || kiniMs - terakhirMs >= JEDA_PERINGATAN_HAPUS_MS;
    }

    static final String KEY_TG_HAPUS_OK = "tg_hapus_ok";

    static final String KEY_TG_HAPUS_TERTUNDA = "tg_hapus_tertunda";

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
                {"login", "Login dengan PIN (sekali, sampai logout)"},
                {"logout", "Keluar dari sesi login bot"},
                {"help", "Menu tombol & daftar perintah"},
        };
    }

    // Audit 2992: pesan /alive membedakan sumber sehatnya -
    // dulu boolean sehat (yang bisa datang dari /api/config
    // 200 saja saat /alive 5xx = database rusak) selalu
    // dijawab "Server sehat (HTTPS 200 /alive)".
    static String pesanAliveRinci(ServerService.HasilPing h) {
        if (h == null) {
            return "Server TIDAK merespon /alive!";
        }
        if (h.aliveCode == 200) {
            return "Server sehat (HTTPS 200 /alive).";
        }
        if (h.sehat) {
            return "Server merespon lewat /api/config (200), tapi /alive "
                    + "memberi " + h.aliveCode + " - pola database "
                    + "bermasalah. Cek /log untuk rincian.";
        }
        return "Server TIDAK merespon /alive! (" + h.rincian + ")";
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
    // Audit 1010: sentinel negatif sebesar jendela jepit — nilai 0
    // bertabrakan dengan elapsedRealtime pada 10 menit pertama
    // sesudah boot sehingga upaya pulih pertama tertelan.
    private static volatile long hapusWebhookTerakhirMs =
            -10L * 60L * 1000L;

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
    static boolean pollPerluLanjutan(int jumlahUpdate) {
        return jumlahUpdate > 0;
    }

    // Peringatan lint ApplySharedPref ditekan dengan
    // sengaja: semua commit() di pollOnce menulis offset
    // Telegram yang wajib awet di disk sebelum update
    // diproses - proses mati sebelum flush apply()
    // membuat update yang kerja beratnya sudah berjalan
    // terkirim ulang (lihat catatan di badan metode).
    @SuppressLint("ApplySharedPref")
    public static void pollOnce(Context ctx) {

        if (!POLLING.compareAndSet(false, true)) {
            return;
        }
        final Context ctxPoll = ctx;
        int jumlahUpdate = 0;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
            String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
            String chat = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_CHAT, ""));
            if (token.isEmpty() || chat.isEmpty()) {
                return;
            }
            // Audit 2830: coba lagi penghapusan pesan
            // ber-PIN yang tertunda dari proses/poll
            // sebelumnya (dicatat persisten).
            cobaHapusTertunda(ctx);
            // Offset getUpdates adalah urutan per-bot (Audit 779): ganti ke
            // bot LAIN dengan offset warisan membuat semua perintah diabaikan
            // diam-diam selamanya. Selaraskan identitas pemilik offset dulu.
            String idKini = idBotDariToken(token);
            String idOffset = TgBackup.amanString(sp, KEY_TG_OFFSET_BOT_ID, "");
            if (offsetPerluReset(idOffset, idKini)) {
                sp.edit().putLong(KEY_TG_OFFSET, 0)
                        .putString(KEY_TG_OFFSET_BOT_ID, idKini).commit();
            } else if (!idKini.isEmpty() && !idKini.equals(idOffset)) {
                sp.edit().putString(KEY_TG_OFFSET_BOT_ID, idKini).apply();
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

            // Audit 1512: read timeout klien wajib di atas
            // long-poll 50 dtk — bawaan httpPostForm 35 dtk
            // memutus poll yang diam lebih dulu (residu 1463).
            String body = httpPostForm(ctx, TG_API + token + "/getUpdates",
                    "offset=" + offset + "&timeout=50&limit=10", 65000);
            if (body == null) {
                return;
            }
            long newOffset = offset;
            int rollbackDitolak = 0;
            try {
                JSONObject root = new JSONObject(body);
                JSONArray arr = root.optJSONArray("result");
                jumlahUpdate = arr == null ? 0 : arr.length();
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
                                try {
                                    sp.edit().putLong(KEY_TG_OFFSET, newOffset).commit();
                                } catch (Exception ignored) {
                                }
                                tanganiCallback(ctx, cb, chatKonfig);
                                continue;
                            }
                            // Audit 2889: suntingan pesan
                            // (edited_message) dulu jatuh ke
                            // continue sesudah offset maju -
                            // pesan yang disunting menjadi
                            // berisi PIN menetap di riwayat
                            // tanpa pernah dihapus. Suntingan
                            // kini melewati saringan hapus
                            // yang sama; perintah TIDAK
                            // pernah dijalankan dari suntingan.
                            JSONObject sunting = upd.optJSONObject(
                                    "edited_message");
                            if (sunting != null) {
                                JSONObject cSunting = sunting.optJSONObject(
                                        "chat");
                                if (cSunting != null
                                        && pinPerangkatAktif(ctx)) {
                                    long idRuangSunting = cSunting.optLong(
                                            "id", -1);
                                    String namaSunting = cSunting.optString(
                                            "username", "");
                                    int idPesanSunting = sunting.optInt(
                                            "message_id", 0);
                                    if (idPesanSunting != 0
                                            && pesanPerluHapus(
                                                    sunting.optString(
                                                            "text", ""),
                                                    sunting.optString(
                                                            "caption", ""),
                                                    namaBerkasDokumen(
                                                            sunting),
                                                    teksPoll(sunting))) {
                                        if (!Util.cocokChatBanyak(
                                                chatKonfig, idRuangSunting,
                                                namaSunting)) {
                                            hapusPesanAsingAsync(ctx,
                                                    idRuangSunting,
                                                    idPesanSunting);
                                        } else {
                                            hapusPesanAsync(ctx,
                                                    idRuangSunting,
                                                    idPesanSunting);
                                        }
                                    }
                                }
                                continue;
                            }
                            // Audit 2912: postingan kanal
                            // (channel_post dan
                            // edited_channel_post) dulu
                            // jatuh ke continue sesudah
                            // offset maju - PIN yang
                            // tertulis di kanal tempat
                            // bot menjadi admin tak
                            // pernah dihapus. Kini
                            // melewati saringan hapus
                            // yang sama (teks atau
                            // caption); perintah tidak
                            // pernah dijalankan dari
                            // postingan kanal.
                            JSONObject kanal = upd.optJSONObject(
                                    "channel_post");
                            if (kanal == null) {
                                kanal = upd.optJSONObject(
                                        "edited_channel_post");
                            }
                            if (kanal != null) {
                                JSONObject cKanal = kanal.optJSONObject(
                                        "chat");
                                if (cKanal != null
                                        && pinPerangkatAktif(ctx)) {
                                    long idRuangKanal = cKanal.optLong(
                                            "id", -1);
                                    String namaKanal = cKanal.optString(
                                            "username", "");
                                    int idPesanKanal = kanal.optInt(
                                            "message_id", 0);
                                    if (idPesanKanal != 0
                                            && pesanPerluHapus(
                                                    kanal.optString(
                                                            "text", ""),
                                                    kanal.optString(
                                                            "caption", ""),
                                                    namaBerkasDokumen(
                                                            kanal),
                                                    teksPoll(kanal))) {
                                        if (!Util.cocokChatBanyak(
                                                chatKonfig, idRuangKanal,
                                                namaKanal)) {
                                            hapusPesanAsingAsync(ctx,
                                                    idRuangKanal,
                                                    idPesanKanal);
                                        } else {
                                            hapusPesanAsync(ctx,
                                                    idRuangKanal,
                                                    idPesanKanal);
                                        }
                                    }
                                }
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
                            if (!Util.cocokChatBanyak(chatKonfig, idRuang, namaUser)) {
                                // Audit 2914: pesan media baru membawa teksnya di
                                // caption, bukan text - tanpa cadangan ini PIN di
                                // caption pesan baru tak pernah masuk saringan hapus
                                // (2905/2912 hanya menutup suntingan dan kanal).
                                // Audit 2967: pilihan teks kini
                                // dihitung di dalam pesanPerluHapus
                                // di bawah (teks/caption/nama berkas).
                                // Audit 2922: predikat gabungan yang sama
                                // dengan suntingan/kanal - balasan PIN utuh
                                // (pinDariBalasan) dulu tak diperiksa di sini.
                                if (pesanPerluHapus(
                                        msg.optString("text", ""),
                                        msg.optString("caption", ""),
                                        namaBerkasDokumen(msg),
                                        teksPoll(msg))
                                        && pinPerangkatAktif(ctx)) {
                                    hapusPesanAsingAsync(ctx, idRuang,
                                            msg.optInt("message_id", 0));
                                }
                                continue;
                            }
                            if (Util.cocokChatBanyak(chatKonfig, idRuang, namaUser)) {

                                long dateMs = msg.optLong("date", 0) * 1000L;
                                if (jamMundur(kiniPoll)) {

                                    if (catatMundurDanBolehIngatkan(kiniPoll)) {
                                        rollbackDitolak++;
                                    }
                                    String teksMundur = pilihTeksSuntingan(
                                            msg.optString("text", ""),
                                            msg.optString("caption", ""));
                                    int idPesanMundur = msg.optInt("message_id", 0);
                                    hapusBilaPinBasi(ctx, idRuang, teksMundur,
                                            namaBerkasDokumen(msg),
                                            teksPoll(msg),
                                            idPesanMundur, kiniPoll);
                                    continue;
                                } else {
                                    catatWall(kiniPoll);
                                }
                                if (!Util.pesanSegar(dateMs,
                                        System.currentTimeMillis(),
                                        STALE_MSG_MS)) {
                                    String teksBasi = pilihTeksSuntingan(
                                            msg.optString("text", ""),
                                            msg.optString("caption", ""));
                                    int idPesanBasi = msg.optInt("message_id", 0);
                                    hapusBilaPinBasi(ctx, idRuang, teksBasi,
                                            namaBerkasDokumen(msg),
                                            teksPoll(msg),
                                            idPesanBasi, kiniPoll);
                                    // Audit 2967: jangan buang diam-diam -
                                    // kabari (dijatah sekali per 10 menit
                                    // agar tumpukan pesan lama sesudah bot
                                    // lama mati tak membanjiri chat).
                                    long kiniBasi = System.currentTimeMillis();
                                    if (kiniBasi - kabarBasiTerakhir
                                            >= 10 * 60_000L) {
                                        kabarBasiTerakhir = kiniBasi;
                                        balas(ctx, idRuang, "Pesanmu sampai"
                                                + " terlalu lama (>5 menit)"
                                                + " - perintah lama tidak"
                                                + " dijalankan demi keamanan."
                                                + " Kirim ulang perintahnya"
                                                + " bila masih perlu.");
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

                                    if (perintahKomitDini(text)) {
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
                                // Audit 2938: pesan media baru di chat
                                // resmi membawa teksnya di caption -
                                // saring caption dengan predikat yang
                                // sama seperti suntingan/kanal, supaya
                                // PIN di caption ikut terhapus (jalur
                                // segar dulu hanya membaca text).
                                // Audit 2967: nama berkas dokumen
                                // ikut disaring di jalur resmi juga.
                                boolean hapusPin = wajibHapusPesanPin(pinBasi, pinGrup,
                                        pinPending, obrolan, terotorisasi, text)
                                        || teksSuntinganPerluHapus(
                                                msg.optString("caption", ""))
                                        || teksSuntinganPerluHapus(
                                                namaBerkasDokumen(msg))
                                        || teksSuntinganPerluHapus(
                                                teksPoll(msg));
                                if (idPesan != 0 && hapusPin && pinPerangkatAktif(ctx)) {
                                    hapusPesanAsync(ctx, idRuang, idPesan);
                                } else if (idPesan != 0 && "help".equals(namaPerintah(text))
                                        && pinPerangkatAktif(ctx)
                                        && !sudahUjiHapus(ctx)) {

                                    hapusPesanAsync(ctx, idRuang, idPesan);
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
                    // Audit 2893: commit() sinkron, bukan
                    // apply() - offset batch harus awet di
                    // disk sebelum pollOnce kembali, kalau
                    // tidak kematian proses sebelum flush
                    // apply membuat Telegram mengirim ulang
                    // update yang tugas beratnya sudah
                    // terantre/terjalan.
                    sp.edit().putLong(KEY_TG_OFFSET, newOffset).commit();
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
            // Audit 2588: update baru saja diproses berarti
            // user sedang aktif - poll berikutnya langsung
            // jalan lagi tanpa menunggu alarm 60 dtk yang
            // tak eksak, agar ketukan berikutnya tak ikut
            // mengantre semenit lebih. Rantai berhenti
            // sendiri saat satu poll pulang kosong
            // (long-poll 50 dtk tanpa update). POLLING
            // sudah dilepas di atas sebelum rantai dipasang,
            // jadi tak ada balapan dengan siklus alarm.
            if (pollPerluLanjutan(jumlahUpdate)) {
                cobaJalankanBg(() -> pollOnce(ctxPoll));
            }
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
                    {"\uD83D\uDCCA Status lengkap", "/status"}, {"\uD83D\uDCDC Log", "/log"},
                    {"\u23F1 Uptime", "/uptime"}, {"\uD83D\uDC93 Cek sehat", "/alive"},
                    {"\uD83C\uDFF7 Versi", "/versi"}, {"\uD83D\uDCA5 Crash log", "/crashlog"},
                    {"\uD83C\uDFE0 Menu utama", "m:" + MENU_UTAMA},
            });
        }
        if (MENU_KONTROL.equals(p)) {
            return rakitKeyboard(new String[][]{
                    {"\u25B6\uFE0F Start", "/start"}, {"\u23F9\uFE0F Stop", "/stop"},
                    {"\uD83D\uDD04 Restart", "/restart"},
                    {"\uD83D\uDEAA Logout", "/logout"},
                    {"\uD83C\uDFE0 Menu utama", "m:" + MENU_UTAMA},
            });
        }
        if (MENU_BACKUP.equals(p)) {
            return rakitKeyboard(new String[][]{
                    {"\uD83D\uDCBE Backup sekarang", "/backup"}, {"\u267B\uFE0F Info restore", "/restore"},
                    {"\uD83C\uDFE0 Menu utama", "m:" + MENU_UTAMA},
            });
        }
        if (MENU_UPDATE.equals(p)) {
            return rakitKeyboard(new String[][]{
                    {"\u2B06\uFE0F Update binary", "/update"}, {"\uD83C\uDF10 Update web vault", "/webvault"},
                    {"\uD83C\uDFF7 Versi", "/versi"},
                    {"\uD83C\uDFE0 Menu utama", "m:" + MENU_UTAMA},
            });
        }
        if (MENU_SERTIFIKAT.equals(p)) {
            return rakitKeyboard(new String[][]{
                    {"\uD83D\uDCDC Kirim CA", "/ca"}, {"\uD83D\uDDC4 Backup CA", "/cabackup"},
                    {"\u267B\uFE0F Reset CA", "/careset"},
                    {"\uD83C\uDFE0 Menu utama", "m:" + MENU_UTAMA},
            });
        }
        if (MENU_INFO.equals(p)) {
            return rakitKeyboard(new String[][]{
                    {"\uD83D\uDCD6 Bantuan", "/help"}, {"\uD83D\uDEAA Logout", "/logout"},
                    {"\uD83C\uDFE0 Menu utama", "m:" + MENU_UTAMA},
            });
        }
        String[] cepatKontrol = ServerService.running
                ? new String[]{"\u23F9\uFE0F Stop", "/stop"}
                : new String[]{"\u25B6\uFE0F Start", "/start"};
        return rakitKeyboard(new String[][]{
                {"\uD83D\uDCCA Status", "/status"}, cepatKontrol,
                {"\uD83D\uDCBE Backup", "/backup"}, {"\uD83D\uDD04 Restart", "/restart"},
                {"\uD83D\uDCCB Status & log", "m:" + MENU_STATUS}, {"\uD83C\uDF9B Kontrol", "m:" + MENU_KONTROL},
                {"\uD83D\uDDC4 Backup & restore", "m:" + MENU_BACKUP}, {"\u2B06\uFE0F Update", "m:" + MENU_UPDATE},
                {"\uD83D\uDD10 Sertifikat", "m:" + MENU_SERTIFIKAT}, {"\u2139\uFE0F Info", "m:" + MENU_INFO},
        });
    }

    private static String teksMenu(Context ctx, String panel) {
        return teksMenu(ctx, panel, 0);
    }

    private static String teksMenu(Context ctx, String panel, long chatId) {
        String p = panelMenuValid(panel) ? panel.trim() : MENU_UTAMA;
        if (MENU_STATUS.equals(p)) {
            return "\uD83D\uDCCB Status & diagnostik\n"
                    + "/status, /log, dan /crashlog memuat folder data, port,"
                    + " dan URL LAN \u2014 butuh login bila PIN aktif.";
        }
        if (MENU_KONTROL.equals(p)) {
            return "\uD83C\uDF9B Kontrol server\n"
                    + "Start menyalakan, Stop menghentikan, Restart saat server jalan.\n"
                    + "Bila PIN aktif dan belum login, tombol meminta PIN sekali"
                    + " untuk login. Aksi ber-PIN hanya dari chat pribadi.";
        }
        if (MENU_BACKUP.equals(p)) {
            return "\uD83D\uDDC4 Backup & restore\n"
                    + "Backup mengirim salinan database ke chat ini. Restore memakai"
                    + " backup terakhir: ketuk Info restore untuk tanggalnya, lalu"
                    + " ketik /restore YA. Server berhenti saat restore.";
        }
        if (MENU_UPDATE.equals(p)) {
            return "\u2B06\uFE0F Update binary & web vault\n"
                    + "Update mengunduh dari rilis GitHub lalu restart otomatis bila"
                    + " server sedang jalan. Versi tertentu bisa dikunci lewat teks:"
                    + " /update 1.32.0; lepas kunci: /update terbaru.";
        }
        if (MENU_SERTIFIKAT.equals(p)) {
            return "\uD83D\uDD10 Sertifikat HTTPS\n"
                    + "Kirim CA mengirim file publik untuk dipasang di perangkat."
                    + " Backup CA menyimpannya ke storage STB. Reset CA membuat"
                    + " sertifikat baru \u2014 semua perangkat harus pasang CA lagi.";
        }
        if (MENU_INFO.equals(p)) {
            return "\u2139\uFE0F Info & bantuan\n"
                    + "Perintah teks: /status /log /uptime /alive /versi /crashlog"
                    + " /backup /restore /ca /cabackup /careset /update /webvault"
                    + " /start /stop /restart /login /logout /help\n"
                    + "Login: /login <PIN> sekali, berlaku 7 hari (atau sampai"
                    + " /logout atau PIN diganti); sesudah login semua perintah"
                    + " jalan tanpa PIN."
                    + " Cara lama tetap bisa: /stop 123456.\n"
                    + "Pesan berisi PIN dihapus otomatis bila bot punya izin hapus.";
        }
        // Audit 2809: status server + status login hanya
        // untuk yang berhak tahu - PIN aktif dan chat belum
        // login berarti /help tak membocorkan apa pun soal
        // keadaan server (perintah info lain pun ber-PIN).
        boolean pinAktif = pinPerangkatAktif(ctx);
        boolean bolehStatus = !pinAktif
                || (chatId != 0 && sudahLogin(ctx, chatId));
        String status = "";
        if (bolehStatus) {
            status = "Server: " + ((ServerService.running
                    || ServerService.isProcessAlive())
                    ? "jalan" : "berhenti") + "\n";
        }
        String login = "";
        if (chatId != 0 && pinAktif && bolehStatus) {
            login = "Login: sudah \u2705\n";
        }
        return "\uD83E\uDD16 Vaultwarden Host\n"
                + status
                + login
                + "Pilih tombol di bawah. Tombol berlaku 24 jam, kirim /help"
                + " lagi bila kedaluwarsa. Catatan: pesan /help pertamamu bisa"
                + " dihapus bot sebagai uji sekali izin hapus pesan ber-PIN.";
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

    static final String KEY_TG_BOT_USER_AT = "tg_bot_user_at";

    static final long SEGAR_NAMA_BOT_MS = 7L * 24 * 3600 * 1000;

    // Audit 2869: nama bot disegarkan berkala - dulu
    // hanya diisi bila kosong, jadi username yang
    // berganti membuat semua perintah ber-sufiks
    // dibuang diam-diam selamanya.
    static boolean namaBotPerluSegar(String tersimpan, long diambilAt, long kini) {
        if (tersimpan == null || tersimpan.trim().isEmpty()) {
            return true;
        }
        if (diambilAt <= 0) {
            return true;
        }
        return kini < diambilAt || kini - diambilAt >= SEGAR_NAMA_BOT_MS;
    }

    static final String KEY_TG_BOT_ID = "tg_bot_id";

    // Keputusan murni reset offset (Audit 779): reset HANYA pada perubahan
    // identitas bot yang terbukti (kedua id terisi dan berbeda). Token baru
    // untuk bot yang sama mempertahankan offset karena antrean update
    // per-bot memang berlanjut; id kosong (token liar / catatan pertama)
    // tidak pernah memicu reset.
    static boolean offsetPerluReset(String idTersimpan, String idKini) {
        return idTersimpan != null && !idTersimpan.isEmpty()
                && idKini != null && !idKini.isEmpty()
                && !idTersimpan.equals(idKini);
    }

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
                        .putLong(KEY_TG_BOT_USER_AT, System.currentTimeMillis())
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
            SharedPreferences spSegar = ctx.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            if (!namaBotPerluSegar(namaBotTersimpan(ctx),
                    TgBackup.amanLong(spSegar, KEY_TG_BOT_USER_AT, 0),
                    System.currentTimeMillis())) {
                return;
            }
        } catch (Exception ignored) {
            return;
        }
        if (!bolehCobaGetMe(token.trim(), getMeCobaToken, getMeCobaAt,
                SystemClock.elapsedRealtime())) {
            return;
        }
        getMeCobaToken = token.trim();
        getMeCobaAt = SystemClock.elapsedRealtime();
        final Context appCtx = ctx.getApplicationContext();
        final String tokenBg = token;
        Util.jalankanBg(() -> isiNamaBotSekarang(appCtx, tokenBg));
    }

    private static void isiNamaBotSekarang(Context ctx, String token) {
        if (ctx == null || token == null || token.trim().isEmpty()) {
            return;
        }
        // Audit 2869: gerbang kesegaran ada di pemanggil
        // (+ throttle getMe) - di sini tak lagi menolak
        // hanya karena nama lama masih tersimpan, agar
        // penyegaran berkala benar-benar mengambil.
        String tokRapi = token.trim();
        getMeCobaToken = tokRapi;
        getMeCobaAt = SystemClock.elapsedRealtime();
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

    static boolean penekanDiizinkan(boolean ruangGrup, boolean penekanPemilik) {
        return !ruangGrup || penekanPemilik;
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
            if (idRuang < 0) {
                JSONObject penekan = cb.optJSONObject("from");
                long idPenekan = penekan != null ? penekan.optLong("id", 0) : 0;
                // Audit 2624: username penekan diteruskan -
                // entri konfigurasi @username sah di
                // Util.cocokChat dan pemeriksaan ruang
                // utama meneruskan username ruang.
                String namaPenekan = penekan != null
                        ? penekan.optString("username", "") : "";
                boolean pemilik = idPenekan > 0
                        && Util.cocokChatBanyak(chatKonfig, idPenekan, namaPenekan);
                if (!penekanDiizinkan(true, pemilik)) {
                    // Audit 2790: jangan diam - anggota grup yang
                    // bukan pemilik harus tahu tombolnya memang
                    // tak aktif untuk dia, bukan rusak/berhasil.
                    jawabCallback(ctx, cb.optString("id", ""),
                            "Tombol ini hanya aktif untuk pemilik yang"
                                    + " ID/username-nya terdaftar di Chat ID"
                                    + " bot (Settings). Kamu tidak terdaftar,"
                                    + " jadi tidak ada aksi yang dijalankan.");
                    return;
                }
            }

            if (pesan == null) {
                // Audit 1462: pesan null berarti ruang juga null
                // dan idRuang selalu -1 — balas() ke "-1" pasti
                // ditolak Telegram. Jawaban callback (toast) saja
                // sudah menyampaikan kedaluwarsanya ke penekan.
                jawabCallback(ctx, cb.optString("id", ""));
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

            if (data.startsWith("segar:")) {
                String cmdSegar = data.substring(6).trim();
                if (idRuang > 0 && perintahBisaSegarkan(cmdSegar)) {
                    hapusPending(ctx, idRuang);
                    boolean grupSegar = chatResmiAdalahGrup(ctx);
                    boolean pinAktifSegar = pinPerangkatAktif(ctx);
                    if (grupSegar && !pinAktifSegar) {
                        return;
                    }
                    if (segarWajibPrompt(perluPinTombol(ctx, cmdSegar),
                            grupSegar, pinAktifSegar, sudahLogin(ctx, idRuang))) {
                        mulaiAksiPin(ctx, idRuang, 0, cmdSegar);
                    } else {
                        segarkanLaporan(ctx, idRuang, idPesan, cmdSegar);
                    }
                }
                return;
            }
            if (callbackDataValid(data)) {
                hapusPending(ctx, idRuang);
                if (perluPinTombol(ctx, data) && !sudahLogin(ctx, idRuang)) {
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
        String teks = teksMenu(ctx, p, chatId);
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

    private static void simpanPending(Context ctx, long chatId, String cmd,
            long waktu, int idPrompt) {
        try {
            ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_TG_PENDING_PREFIX + chatId,
                            waktu + "|" + cmd + "|" + idPrompt).apply();
        } catch (Exception ignored) {
        }
    }

    // Audit 2968: pengurai nilai pending. Format kini
    // waktu|cmd|idPrompt; nilai lama waktu|cmd tetap terbaca
    // (idPrompt 0). Ekor id hanya diakui bila ada >= 2 pemisah
    // dan ekornya semua digit, agar cmd lama tak terpotong.
    static int idPromptDariIsiPending(String isi) {
        if (isi == null) {
            return 0;
        }
        int pertama = isi.indexOf('|');
        int terakhir = isi.lastIndexOf('|');
        if (pertama < 0 || terakhir <= pertama
                || terakhir == isi.length() - 1) {
            return 0;
        }
        String ekor = isi.substring(terakhir + 1).trim();
        for (int i = 0; i < ekor.length(); i++) {
            if (!Character.isDigit(ekor.charAt(i))) {
                return 0;
            }
        }
        try {
            return Integer.parseInt(ekor);
        } catch (Exception e) {
            return 0;
        }
    }

    static String cmdDariIsiPending(String isi) {
        if (isi == null) {
            return "";
        }
        int pisah = isi.indexOf('|');
        if (pisah < 0) {
            return "";
        }
        String sisa = isi.substring(pisah + 1);
        if (idPromptDariIsiPending(isi) != 0
                || (sisa.contains("|") && isi.lastIndexOf('|') > pisah
                        && isi.substring(isi.lastIndexOf('|') + 1)
                                .trim().matches("\\d+"))) {
            return sisa.substring(0, sisa.lastIndexOf('|'));
        }
        return sisa;
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
            String cmd = pisah > 0 ? cmdDariIsiPending(isi) : "";
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
        // Audit 2968: prompt lama (bila ada dan beda pesan)
        // diedit jadi pemberitahuan digantikan - dulu ia tetap
        // menampilkan aksi lama padahal PIN berikutnya
        // menjalankan aksi baru ini.
        int idPromptLama = 0;
        try {
            SharedPreferences spLama = ctx.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            idPromptLama = idPromptDariIsiPending(
                    TgBackup.amanString(spLama,
                            KEY_TG_PENDING_PREFIX + chatId, ""));
        } catch (Exception ignored) {
        }
        simpanPending(ctx, chatId, data, System.currentTimeMillis(), messageId);
        if (idPromptLama != 0 && idPromptLama != messageId) {
            String kbBatal = rakitKeyboard(new String[][]{
                    {"Batal", "m:batal"}, {"Menu utama", "m:" + MENU_UTAMA},
            });
            TgBackup.editMessageKb(ctx, chatId, idPromptLama,
                    "Prompt PIN ini digantikan prompt yang lebih baru.\n"
                            + "PIN yang kamu ketik berlaku untuk aksi"
                            + " terbaru: " + data + "\n"
                            + "Prompt lama ini tidak lagi berlaku.",
                    kbBatal);
        }
        String teks = "Login diperlukan\n"
                + "Aksi: " + data + "\n"
                + "Balas pesan ini dengan PIN aplikasi (berlaku 5 menit)."
                + " PIN yang benar sekaligus membuatmu login, jadi aksi"
                + " berikutnya jalan langsung tanpa PIN."
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
                || "status".equals(cmd) || "ca".equals(cmd) || "cabackup".equals(cmd)
                || "uptime".equals(cmd) || "alive".equals(cmd) || "versi".equals(cmd);
    }

    static boolean pesanPinWajibHapus(String text) {
        return perintahPerluPin(text);
    }

    static boolean perintahKomitDini(String text) {
        String cmd = namaPerintah(text);
        // Perintah berat (yang berjalan di antrean
        // asinkron runBeratDenganKunci) tercakup
        // komit dini lewat perintahPerluPin -
        // backup/restore/update/webvault/careset
        // adalah perintahBerbahaya dan ca/cabackup
        // tercantum harfiah di perintahPerluPin.
        // Audit 2913: daftar tujuh nama tambahan
        // yang ditulis 2893 di sini ternyata
        // HAMPA (ketujuhnya sudah tercakup
        // perintahPerluPin sejak sebelum 2893);
        // perubahan perilaku 2893 yang nyata
        // adalah apply()->commit() pada finally
        // pollOnce. Daftar mati itu kini dibuang
        // agar cakupan tidak menyesatkan.
        return perintahPerluPin(text) || "login".equals(cmd) || "logout".equals(cmd);
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
        // Audit 2868: PIN telanjang (teks utuh berbentuk
        // PIN) ikut dihapus walau tanpa pending - jalur
        // asing/suntingan sudah begitu lewat predikat
        // yang sama; chat resmi tak boleh kalah aman.
        if (pinDariBalasan(text) != null) {
            return true;
        }
        return terotorisasi && (pinPending || pesanPinWajibHapus(text));
    }

    static String pinTebakanArgumen(String arg) {
        String pin = pisahkanPin(arg == null ? "" : arg)[1];
        return pin.length() >= 4 ? pin : null;
    }

    // Audit 2889: teks suntingan perlu dihapus bila ia
    // membawa PIN dalam bentuk apa pun yang dikenal
    // jalur pesan biasa - argumen perintah ber-PIN
    // (pinTebakan) atau balasan PIN utuh (pinDariBalasan).
    // Audit 2905: pesan media (foto/video/
    // dokumen) menyimpan tulisannya di
    // caption, bukan text - teks suntingan
    // adalah text bila terisi, selain itu
    // caption. Tanpa ini PIN yang disunting
    // masuk ke caption lolos saringan 2889.
    static String pilihTeksSuntingan(String teks,
                                     String caption) {
        String t = teks == null ? "" : teks.trim();
        if (!t.isEmpty()) {
            return t;
        }
        return caption == null ? "" : caption.trim();
    }

    static boolean teksSuntinganPerluHapus(String teks) {
        return pinTebakan(teks) != null
                || pinDariBalasan(teks) != null;
    }

    // Audit 2967: pesan dokumen membawa teks
    // bebas ketiga - document.file_name - yang
    // tak pernah dibaca saringan mana pun:
    // berkas bernama persis PIN (atau memuatnya
    // sebagai nama + ekstensi) lolos dari semua
    // jalur hapus dan PIN menetap sebagai nama
    // berkas di riwayat chat. Nama berkas kini
    // disaring dengan predikat yang sama
    // seperti teks/caption di semua jalur.
    static String namaBerkasDokumen(JSONObject pesan) {
        if (pesan == null) {
            return "";
        }
        JSONObject dok = pesan.optJSONObject("document");
        if (dok == null) {
            return "";
        }
        return dok.optString("file_name", "");
    }

    static boolean pesanPerluHapus(String teks,
                                   String caption,
                                   String namaBerkas) {
        return pesanPerluHapus(teks, caption, namaBerkas, "");
    }

    // Audit 2976: pesan poll membawa teks bebas KEEMPAT -
    // pertanyaan + teks opsi - yang tak pernah dibaca
    // saringan mana pun: poll yang pertanyaan/opsi-nya
    // memuat PIN lolos dari semua jalur hapus dan PIN
    // menetap di riwayat chat. Teks poll kini diekstrak
    // dan disaring dengan predikat yang sama di semua
    // jalur, seperti nama berkas pada 2967.
    static String teksPoll(JSONObject pesan) {
        if (pesan == null) {
            return "";
        }
        JSONObject poll = pesan.optJSONObject("poll");
        if (poll == null) {
            return "";
        }
        org.json.JSONArray opsi = poll.optJSONArray("options");
        int n = opsi == null ? 0 : opsi.length();
        String[] daftar = new String[n];
        for (int i = 0; i < n; i++) {
            JSONObject o = opsi.optJSONObject(i);
            daftar[i] = o == null ? "" : o.optString("text", "");
        }
        return gabungTeksPoll(poll.optString("question", ""), daftar);
    }

    static String gabungTeksPoll(String pertanyaan, String[] opsi) {
        StringBuilder sb = new StringBuilder();
        if (pertanyaan != null) {
            sb.append(pertanyaan);
        }
        if (opsi != null) {
            for (String o : opsi) {
                if (o == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(o);
            }
        }
        return sb.toString();
    }

    static boolean pesanPerluHapus(String teks,
                                   String caption,
                                   String namaBerkas,
                                   String teksPoll) {
        return teksSuntinganPerluHapus(
                pilihTeksSuntingan(teks, caption))
                || teksSuntinganPerluHapus(namaBerkas)
                || teksSuntinganPerluHapus(teksPoll);
    }

    static String pinTebakan(String text) {
        if (text == null) {
            return null;
        }
        if ("login".equals(namaPerintah(text))) {
            return pinTebakanArgumen(ambilArgumen(text));
        }
        if (!perintahPerluPin(text)) {
            return null;
        }
        return pinTebakanArgumen(ambilArgumen(text));
    }

    // Audit 2588: penghapusan pesan ber-PIN (anggaran coba
    // sampai 60 dtk di jaringan buruk) tak lagi dikerjakan
    // sebaris di utas poll - satu pesan bisa menahan semua
    // update di belakangnya semenit penuh. Dikerjakan di
    // pool latar, peringatan kegagalannya ikut di dalamnya.
    // Pool penuh = jatuh ke cara lama sebaris, agar pesan
    // ber-PIN tak pernah dibiarkan tak terhapus diam-diam.
    private static void hapusPesanAsync(Context ctx, long idRuang, int idPesan) {
        // Audit 2830: catat persisten SEBELUM antre -
        // proses mati sebelum tugas jalan tak lagi
        // menghilangkan penghapusan ini selamanya.
        catatHapusTertunda(ctx, idRuang, idPesan, false);
        if (!cobaJalankanBg(() -> {
            if (!mulaiHapusTertunda(idRuang, idPesan, false)) {
                return;
            }
            try {
                if (!hapusTertundaMasihTercatat(ctx, idRuang, idPesan, false)) { return; }
                boolean terhapus = hapusPesanPerintah(ctx, idRuang, idPesan);
                if (terhapus) {
                    selesaikanHapusTertundaUntuk(ctx,
                            idRuang, idPesan, false);
                }
                peringatkanBilaTakBisaHapus(ctx, terhapus, idRuang);
            } finally {
                selesaiHapusTertunda(idRuang, idPesan, false);
            }
        })) {
            if (!mulaiHapusTertunda(idRuang, idPesan, false)) {
                return;
            }
            try {
                if (!hapusTertundaMasihTercatat(ctx, idRuang, idPesan, false)) { return; }
                boolean terhapus = hapusPesanPerintah(ctx, idRuang, idPesan);
                if (terhapus) {
                    selesaikanHapusTertundaUntuk(ctx,
                            idRuang, idPesan, false);
                }
                peringatkanBilaTakBisaHapus(ctx, terhapus, idRuang);
            } finally {
                selesaiHapusTertunda(idRuang, idPesan, false);
            }
        }
    }

    private static void hapusPesanAsingAsync(Context ctx, long idRuang, int idPesan) {
        // Audit 2830: catat persisten SEBELUM antre.
        catatHapusTertunda(ctx, idRuang, idPesan, true);
        if (!cobaJalankanBg(() -> {
            if (mulaiHapusTertunda(idRuang, idPesan, true)) {
                try {
                    if (!hapusTertundaMasihTercatat(ctx, idRuang, idPesan, true)) { return; }
                    if (!hapusPesanPerintah(ctx, idRuang, idPesan)) {
                        peringatkanPinChatAsing(ctx, idRuang);
                    } else {
                        selesaikanHapusTertundaUntuk(ctx,
                                idRuang, idPesan, true);
                    }
                } finally {
                    selesaiHapusTertunda(idRuang, idPesan, true);
                }
            }
        })) {
            if (mulaiHapusTertunda(idRuang, idPesan, true)) {
                try {
                    if (!hapusTertundaMasihTercatat(ctx, idRuang, idPesan, true)) { return; }
                    if (!hapusPesanPerintah(ctx, idRuang, idPesan)) {
                        peringatkanPinChatAsing(ctx, idRuang);
                    } else {
                        selesaikanHapusTertundaUntuk(ctx,
                                idRuang, idPesan, true);
                    }
                } finally {
                    selesaiHapusTertunda(idRuang, idPesan, true);
                }
            }
        }
    }

    // Audit 2830: daftar penghapusan tertunda
    // persisten (idRuang:idPesan:a/s per entri).
    // Sebelum 2588 penghapusan dikerjakan sebaris
    // di utas poll sehingga selalu dicoba di
    // putaran yang sama; sesudah dipindah ke pool
    // latar, proses yang mati sebelum tugasnya
    // jalan menghilangkan penghapusan itu tanpa
    // sisa. Kini dicatat lebih dulu, dicoba lagi
    // di awal tiap pollOnce, dan hanya dicoret
    // sesudah biner membuktikan terhapus.
    static String entriHapusTertunda(long idRuang, int idPesan,
            boolean asing) {
        return idRuang + ":" + idPesan + ":" + (asing ? "a" : "s");
    }

    // Audit 2836: entri yang tersimpan membawa
    // cap waktu catat pertama; pesan yang tak
    // akan pernah terhapus (sudah dihapus
    // manual, atau lewat jendela hapus 48 jam
    // Telegram) tidak boleh dicoba ulang di
    // tiap poll selamanya.
    static final long BATAS_UMUR_HAPUS_TERTUNDA_MS =
            48L * 3600_000L;

    // Audit 2878: himpunan hapus tertunda
    // dulu hanya dikendalikan dedup
    // identitas - laju masuk dari chat
    // asing/grup jauh melebihi laju keluar
    // (maks 10/poll, entri gagal menetap
    // 48 jam) sehingga prefs membengkak
    // dan disalin utuh di bawah kunci pada
    // tiap operasi. Kini dibatasi; yang
    // dikorbankan entri paling tua (cap
    // terkecil, warisan tanpa cap dan
    // entri rusak paling dulu).
    static final int BATAS_ENTRI_HAPUS_TERTUNDA = 256;

    static java.util.List<String>
            pangkasHapusTertunda(
                    java.util.Set<String> himpunan,
                    int batas) {
        java.util.List<String> buang =
                new java.util.ArrayList<>();
        if (himpunan == null
                || himpunan.size() <= batas) {
            return buang;
        }
        java.util.List<String> urut =
                new java.util.ArrayList<>(himpunan);
        // Audit 2890: urutan buang bukan lagi cap
        // semata - entri rusak dulu, lalu entri ASING
        // tertua, terakhir entri MILIK SENDIRI tertua.
        // Banjir entri asing tak lagi bisa mengorbankan
        // pesan ber-PIN sah yang penghapusannya sedang
        // gagal hanya karena capnya paling tua.
        java.util.Collections.sort(urut, (x, y) -> {
            long[] ux = uraiEntriHapusTertunda(x);
            long[] uy = uraiEntriHapusTertunda(y);
            int kx = ux == null ? 0 : (ux[2] == 1 ? 1 : 2);
            int ky = uy == null ? 0 : (uy[2] == 1 ? 1 : 2);
            if (kx != ky) {
                return Integer.compare(kx, ky);
            }
            long cx = ux == null ? -1 : ux[3];
            long cy = uy == null ? -1 : uy[3];
            return Long.compare(cx, cy);
        });
        int lebih = himpunan.size() - batas;
        for (int i = 0; i < lebih; i++) {
            buang.add(urut.get(i));
        }
        return buang;
    }

    static boolean samaIdentitasHapus(long[] u,
            long idRuang, int idPesan, boolean asing) {
        return u != null && u[0] == idRuang
                && u[1] == idPesan
                && (u[2] == 1) == asing;
    }

    // Audit 2876: penanda 2837 hanya mencegah
    // hapus BERSAMAAN; dua tugas yang mengantre
    // untuk pesan yang sama berjalan BERURUTAN
    // dan yang kedua menghapus ulang pesan yang
    // sudah terhapus -> 400 -> peringatan palsu
    // + bendera dedup menelan peringatan sah.
    // Kini tiap tugas memeriksa ulang saat ia
    // berjalan: entri yang sudah diselesaikan
    // tugas lain (atau kedaluwarsa) tak dicoba
    // lagi. Galat baca prefs = gagal-terbuka
    // (tetap hapus) agar pesan ber-PIN tak
    // pernah tertinggal karena galat baca.
    static boolean entriHapusMasihAda(
            java.util.Set<String> himpunan,
            long idRuang, int idPesan,
            boolean asing) {
        if (himpunan == null) {
            return false;
        }
        for (String e : himpunan) {
            if (samaIdentitasHapus(
                    uraiEntriHapusTertunda(e),
                    idRuang, idPesan, asing)) {
                return true;
            }
        }
        return false;
    }

    static boolean hapusTertundaMasihTercatat(
            Context ctx, long idRuang,
            int idPesan, boolean asing) {
        synchronized (KUNCI_PERINGATAN) {
            try {
                SharedPreferences sp =
                        ctx.getSharedPreferences(
                                ServerService.PREFS,
                                Context.MODE_PRIVATE);
                return entriHapusMasihAda(
                        sp.getStringSet(
                                KEY_TG_HAPUS_TERTUNDA,
                                null),
                        idRuang, idPesan, asing);
            } catch (Exception e) {
                return true;
            }
        }
    }

    static String entriHapusTertundaPenuh(long idRuang,
            int idPesan, boolean asing, long capMs,
            long capElapsedMs) {
        return entriHapusTertunda(idRuang, idPesan, asing)
                + ":" + capMs + ":" + capElapsedMs;
    }

    // Audit 2877: umur diukur dari cap
    // elapsedRealtime bila cap dan kini
    // dari boot yang sama (elapsed kini
    // tak lebih kecil) - jam dinding STB
    // yang melompat maju >48 jam tak lagi
    // membuang entri ber-PIN yang masih
    // muda, dan lompatan mundur tak
    // memperpanjang umurnya. Lintas boot
    // atau entri warisan tanpa cap elapsed
    // jatuh ke jam dinding seperti 2836.
    static boolean hapusTertundaKedaluwarsa(long capMs,
            long capElapsedMs, long kiniMs,
            long kiniElapsedMs) {
        if (capElapsedMs > 0
                && kiniElapsedMs >= capElapsedMs) {
            return kiniElapsedMs - capElapsedMs
                    > BATAS_UMUR_HAPUS_TERTUNDA_MS;
        }
        return capMs > 0
                && kiniMs - capMs
                        > BATAS_UMUR_HAPUS_TERTUNDA_MS;
    }

    // Audit 2837: identitas pesan yang sedang
    // dihapus satu tugas; tugas kedua untuk
    // pesan yang sama (async awal vs coba-ulang
    // poll) melewatkan gilirannya - yang kalah
    // balapan menerima 400 dan dulu memicu
    // peringatan palsu + memasang bendera
    // dedup yang menelan peringatan sah
    // berikutnya.
    private static final java.util.Set<String>
            HAPUS_SEDANG_JALAN = new java.util.HashSet<>();

    static boolean mulaiHapusTertunda(long idRuang,
            int idPesan, boolean asing) {
        synchronized (KUNCI_PERINGATAN) {
            return HAPUS_SEDANG_JALAN.add(
                    entriHapusTertunda(
                            idRuang, idPesan, asing));
        }
    }

    static void selesaiHapusTertunda(long idRuang,
            int idPesan, boolean asing) {
        synchronized (KUNCI_PERINGATAN) {
            HAPUS_SEDANG_JALAN.remove(
                    entriHapusTertunda(
                            idRuang, idPesan, asing));
        }
    }

    static long[] uraiEntriHapusTertunda(String entri) {
        if (entri == null) {
            return null;
        }
        String[] b = entri.split(":");
        if (b.length != 3 && b.length != 4
                && b.length != 5) {
            return null;
        }
        try {
            long ruang = Long.parseLong(b[0]);
            long pesan = Long.parseLong(b[1]);
            if (!b[2].equals("a") && !b[2].equals("s")) {
                return null;
            }
            // Audit 2836: bidang keempat =
            // cap waktu catat pertama (ms);
            // entri warisan 2830 tanpanya
            // terbaca 0.
            long cap = 0;
            if (b.length >= 4) {
                cap = Long.parseLong(b[3]);
                if (cap < 0) {
                    return null;
                }
            }
            // Audit 2877: bidang kelima =
            // cap elapsedRealtime saat catat
            // (jam monoton, kebal lompatan
            // jam dinding STB); entri lama
            // tanpanya terbaca 0 dan dinilai
            // dari jam dinding seperti dulu.
            long capElapsed = 0;
            if (b.length == 5) {
                capElapsed = Long.parseLong(b[4]);
                if (capElapsed < 0) {
                    return null;
                }
            }
            return new long[]{ruang, pesan,
                    b[2].equals("a") ? 1 : 0, cap,
                    capElapsed};
        } catch (Exception e) {
            return null;
        }
    }

    static void catatHapusTertunda(Context ctx, long idRuang,
            int idPesan, boolean asing) {
        if (ctx == null || idRuang == 0 || idPesan == 0) {
            return;
        }
        // Audit 2937: ruang entri yang terpangkas
        // dikumpulkan untuk diperingatkan sesudah
        // kunci lepas (jalur gugur lain sudah
        // memperingatkan: kedaluwarsa di
        // cobaHapusTertunda, gagal hapus di
        // peringatkanBilaTakBisaHapus).
        java.util.Set<Long> ruangMilikTerbuang =
                new java.util.HashSet<>();
        java.util.Set<Long> ruangAsingTerbuang =
                new java.util.HashSet<>();
        synchronized (KUNCI_PERINGATAN) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(
                        ServerService.PREFS, Context.MODE_PRIVATE);
                java.util.Set<String> kini =
                        new java.util.HashSet<>();
                java.util.Set<String> lama = sp.getStringSet(
                        KEY_TG_HAPUS_TERTUNDA, null);
                if (lama != null) {
                    kini.addAll(lama);
                }
                boolean sudahAda = false;
                for (String e : kini) {
                    if (samaIdentitasHapus(
                            uraiEntriHapusTertunda(e),
                            idRuang, idPesan, asing)) {
                        sudahAda = true;
                        break;
                    }
                }
                if (!sudahAda) {
                    int milikTerbuang = 0;
                    for (String buang
                            : pangkasHapusTertunda(
                                    kini,
                                    BATAS_ENTRI_HAPUS_TERTUNDA
                                            - 1)) {
                        long[] ub = uraiEntriHapusTertunda(
                                buang);
                        if (ub != null && ub[2] == 0) {
                            milikTerbuang++;
                            ruangMilikTerbuang.add(ub[0]);
                        } else if (ub != null) {
                            ruangAsingTerbuang.add(ub[0]);
                        }
                        kini.remove(buang);
                    }
                    // Audit 2890: pengorbanan entri milik
                    // sendiri (kasus terburuk: antrean
                    // seluruhnya milik) tak lagi senyap.
                    if (milikTerbuang > 0) {
                        ServerService.catatLog("[tg] Antrean hapus"
                                + " tertunda penuh: "
                                + milikTerbuang
                                + " entri hapus milik sendiri"
                                + " terbuang - pesan ber-PIN itu"
                                + " mungkin tak terhapus otomatis.");
                    }
                    kini.add(entriHapusTertundaPenuh(
                            idRuang, idPesan, asing,
                            System.currentTimeMillis(),
                            SystemClock
                                    .elapsedRealtime()));
                    sp.edit().putStringSet(
                            KEY_TG_HAPUS_TERTUNDA, kini).apply();
                }
            } catch (Exception ignored) {
            }
        }
        // Audit 2937: entri terpangkas batas 256
        // dulu gugur tanpa peringatan ke chat
        // (milik sendiri hanya log lokal 2890,
        // asing tak dicatat di mana pun) dan
        // tugas latarnya pulang senyap saat
        // entri tak tercatat lagi - pesan
        // ber-PIN bisa tetap ada tanpa owner
        // tahu. Peringatan kini dikirim dari
        // titik pangkasnya, sekali per ruang
        // per pangkasan, tunduk dedup/throttle
        // masing-masing jalur.
        // Audit 2948: peringatan pangkas jalur
        // milik sendiri memakai fungsi dedup yang
        // sama dengan 2936 - bendera basi dari
        // kegagalan lama menelannya mentah-mentah
        // (cacat yang sama tertinggal di jalur
        // pangkas). Pola 2936 di jalur
        // kedaluwarsa diterapkan di putaran
        // bawah (lihat 2958).
        // Audit 2958: bendera dedup itu boolean
        // GLOBAL, bukan per ruang - dibersihkan
        // sekali saja sebelum putaran (bentuk
        // awal 2948) hanya menyelamatkan ruang
        // pertama, karena panggilan pertama
        // memasangnya lagi dan ruang berikutnya
        // pulang tertelan di cek dedup. Kini
        // bendera dibersihkan sebelum SETIAP
        // panggilan di putaran ini, jadi niat
        // 2937 "sekali per ruang per pangkasan"
        // sungguh berlaku untuk semua ruang.
        for (long ruang : ruangMilikTerbuang) {
            synchronized (KUNCI_PERINGATAN) {
                try {
                    SharedPreferences spPangkas =
                            ctx.getSharedPreferences(
                                    ServerService.PREFS,
                                    Context.MODE_PRIVATE);
                    spPangkas.edit()
                            .remove(KEY_TG_HAPUS_WARN)
                            .apply();
                } catch (Exception ignored) {
                }
            }
            peringatkanBilaTakBisaHapus(ctx, false, ruang);
        }
        for (long ruang : ruangAsingTerbuang) {
            // Audit 2959: kesempatan terakhir -
            // lewati throttle (entri sudah
            // terpangkas permanen).
            peringatkanPinChatAsing(ctx, ruang, true);
        }
    }

    static void selesaikanHapusTertunda(Context ctx,
            String entri) {
        if (ctx == null) {
            return;
        }
        synchronized (KUNCI_PERINGATAN) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(
                        ServerService.PREFS, Context.MODE_PRIVATE);
                java.util.Set<String> lama = sp.getStringSet(
                        KEY_TG_HAPUS_TERTUNDA, null);
                if (lama == null || !lama.contains(entri)) {
                    return;
                }
                java.util.Set<String> kini =
                        new java.util.HashSet<>(lama);
                kini.remove(entri);
                sp.edit().putStringSet(
                        KEY_TG_HAPUS_TERTUNDA, kini).apply();
            } catch (Exception ignored) {
            }
        }
    }

    // Audit 2836: coret entri menurut identitas
    // dasarnya (id ruang + id pesan + jenis),
    // apa pun cap waktu yang tersimpan - jalur
    // async tak memegang untaian entri persisnya.
    static void selesaikanHapusTertundaUntuk(Context ctx,
            long idRuang, int idPesan, boolean asing) {
        if (ctx == null) {
            return;
        }
        synchronized (KUNCI_PERINGATAN) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(
                        ServerService.PREFS, Context.MODE_PRIVATE);
                java.util.Set<String> lama = sp.getStringSet(
                        KEY_TG_HAPUS_TERTUNDA, null);
                if (lama == null) {
                    return;
                }
                java.util.Set<String> kini =
                        new java.util.HashSet<>();
                boolean berubah = false;
                for (String e : lama) {
                    if (samaIdentitasHapus(
                            uraiEntriHapusTertunda(e),
                            idRuang, idPesan, asing)) {
                        berubah = true;
                        continue;
                    }
                    kini.add(e);
                }
                if (berubah) {
                    sp.edit().putStringSet(
                            KEY_TG_HAPUS_TERTUNDA, kini).apply();
                }
            } catch (Exception ignored) {
            }
        }
    }

    static void cobaHapusTertunda(Context ctx) {
        java.util.List<String> daftar =
                new java.util.ArrayList<>();
        synchronized (KUNCI_PERINGATAN) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(
                        ServerService.PREFS, Context.MODE_PRIVATE);
                java.util.Set<String> lama = sp.getStringSet(
                        KEY_TG_HAPUS_TERTUNDA, null);
                if (lama != null) {
                    daftar.addAll(lama);
                }
            } catch (Exception ignored) {
            }
        }
        // Audit 2949: putar titik awal potret
        // tiap sapuan. Potret himpunan tak
        // berurutan + batas 10/poll tanpa
        // putaran membuat entri gagal permanen
        // (pesan sudah dihapus manual) memenuhi
        // slot pertama selamanya - entri lain
        // yang kegagalannya sudah sembuh tak
        // pernah dicoba ulang sampai kedaluwarsa.
        if (daftar.size() > 1) {
            int putar = (int) (ROTASI_HAPUS_TERTUNDA
                    .getAndIncrement() % daftar.size());
            if (putar > 0) {
                java.util.Collections.rotate(
                        daftar, -putar);
            }
        }
        int diproses = 0;
        boolean warnKedaluwarsaTerkirim = false;
        for (String entri : daftar) {
            long[] u = uraiEntriHapusTertunda(entri);
            if (u == null) {
                selesaikanHapusTertunda(ctx, entri);
                continue;
            }
            final long idRuang = u[0];
            final int idPesan = (int) u[1];
            final boolean asing = u[2] == 1;
            // Audit 2836: entri kedaluwarsa
            // dibuang (peringatan terakhir
            // lewat jalur dedup yang sama);
            // entri warisan tanpa cap diberi
            // cap sekarang agar jamnya jalan.
            if (hapusTertundaKedaluwarsa(
                    u[3], u[4],
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime())) {
                selesaikanHapusTertunda(ctx, entri);
                if (asing) {
                    // Audit 2959: kesempatan
                    // terakhir - lewati throttle.
                    peringatkanPinChatAsing(ctx, idRuang, true);
                } else if (!warnKedaluwarsaTerkirim) {
                    warnKedaluwarsaTerkirim = true;
                    // Audit 2936: peringatan
                    // kedaluwarsa adalah
                    // kesempatan terakhir (entri
                    // tak akan dicoba lagi, jadi
                    // tak ada hapus berhasil yang
                    // membersihkan bendera dedup
                    // untuknya) - bendera generik
                    // dibersihkan dulu agar tak
                    // tertelan peringatan lama
                    // untuk pesan lain. Sekali
                    // per sapuan cukup: teksnya
                    // generik, entri kedaluwarsa
                    // lain di sapuan yang sama
                    // terwakili peringatan ini.
                    synchronized (KUNCI_PERINGATAN) {
                        try {
                            SharedPreferences spKedaluwarsa =
                                    ctx.getSharedPreferences(
                                            ServerService.PREFS,
                                            Context.MODE_PRIVATE);
                            spKedaluwarsa.edit()
                                    .remove(KEY_TG_HAPUS_WARN)
                                    .apply();
                        } catch (Exception ignored) {
                        }
                    }
                    peringatkanBilaTakBisaHapus(
                            ctx, false, idRuang);
                }
                continue;
            }
            if (u[3] == 0) {
                selesaikanHapusTertunda(ctx, entri);
                catatHapusTertunda(ctx, idRuang,
                        idPesan, asing);
            }
            if (diproses >= 10) {
                break;
            }
            diproses++;
            cobaJalankanBg(() -> {
                if (!mulaiHapusTertunda(
                        idRuang, idPesan, asing)) {
                    return;
                }
                try {
                    if (!hapusTertundaMasihTercatat(ctx, idRuang, idPesan, asing)) { return; }
                    boolean terhapus = hapusPesanPerintah(
                            ctx, idRuang, idPesan);
                    if (terhapus) {
                        selesaikanHapusTertundaUntuk(ctx,
                                idRuang, idPesan, asing);
                        // Audit 2969: keberhasilan coba-ulang
                        // juga membersihkan bendera peringatan
                        // hapus, seperti jalur segar - dulu
                        // cabang sukses ini melewatkannya.
                        if (!asing) {
                            peringatkanBilaTakBisaHapus(
                                    ctx, true, idRuang);
                        }
                    } else if (asing) {
                        peringatkanPinChatAsing(ctx, idRuang);
                    } else {
                        peringatkanBilaTakBisaHapus(
                                ctx, false, idRuang);
                    }
                } finally {
                    selesaiHapusTertunda(
                            idRuang, idPesan, asing);
                }
            });
        }
    }

    private static void hapusBilaPinBasi(Context ctx, long idRuang, String teks,
            int idPesan, long kiniPoll) {
        hapusBilaPinBasi(ctx, idRuang, teks, "", idPesan, kiniPoll);
    }

    private static void hapusBilaPinBasi(Context ctx, long idRuang, String teks,
            String namaBerkas, int idPesan, long kiniPoll) {
        hapusBilaPinBasi(ctx, idRuang, teks, namaBerkas, "", idPesan, kiniPoll);
    }

    // Audit 2967: jalur basi/mundur ikut
    // menyaring nama berkas dokumen - PIN
    // sebagai nama berkas juga menghapus
    // pesannya, seperti jalur lain.
    // Audit 2976: teks poll ikut disaring di
    // jalur ini juga.
    private static void hapusBilaPinBasi(Context ctx, long idRuang, String teks,
            String namaBerkas, String teksPoll, int idPesan, long kiniPoll) {
        if (idPesan == 0 || !pinPerangkatAktif(ctx)) {
            return;
        }
        boolean adaPending = false;
        boolean pendingBasi = false;
        if (!teks.isEmpty() && !teks.startsWith("/")) {
            AksiPending pb = ambilPending(ctx, idRuang, kiniPoll);
            adaPending = !pb.cmd.isEmpty();
            pendingBasi = pb.kedaluwarsa;
        }
        if (pinBasiPerluHapus(teks, adaPending, pendingBasi)
                || teksSuntinganPerluHapus(namaBerkas)
                || teksSuntinganPerluHapus(teksPoll)) {
            hapusPesanAsync(ctx, idRuang, idPesan);
        }
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

    static final String KEY_TG_LOGIN_CHAT = "tg_login_chat";
    static final String KEY_TG_LOGIN_HASH = "tg_login_hash";
    static final String KEY_TG_LOGIN_PREFIX = "tg_login_";

    static final long LOGIN_TTL_MS = 7L * 24 * 3600 * 1000;

    static String nilaiSesiLogin(String hash, long kiniMs) {
        return hash + "|" + kiniMs;
    }

    static String hashDariNilaiSesi(String nilai) {
        if (nilai == null) {
            return "";
        }
        int p = nilai.lastIndexOf('|');
        return p < 0 ? nilai : nilai.substring(0, p);
    }

    static boolean sesiLoginSegar(String nilai, String hashKini, long kiniMs) {
        if (nilai == null || hashKini == null || hashKini.isEmpty()) {
            return false;
        }
        int p = nilai.lastIndexOf('|');
        if (p < 0 || !nilai.substring(0, p).equals(hashKini)) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(nilai.substring(p + 1).trim());
        } catch (Exception e) {
            return false;
        }
        long umur = kiniMs - ts;
        return umur >= 0 && umur <= LOGIN_TTL_MS;
    }

    static boolean loginBerlaku(long chatTersimpan, long idRuang, String hashTersimpan,
            String hashKini, boolean pinOn) {
        return pinOn && idRuang > 0 && chatTersimpan == idRuang
                && hashTersimpan != null && !hashTersimpan.isEmpty()
                && hashTersimpan.equals(hashKini);
    }

    static String kunciSesiLogin(long idRuang) {
        return KEY_TG_LOGIN_PREFIX + idRuang;
    }

    static boolean sudahLogin(Context ctx, long idRuang) {
        if (ctx == null || idRuang < 0) {
            return false;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String hashKini = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
            boolean pinOn = TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false);
            String perChat = TgBackup.amanString(sp, kunciSesiLogin(idRuang), "");
            if (perChat != null && !perChat.isEmpty()) {
                return loginBerlaku(idRuang, idRuang, hashDariNilaiSesi(perChat),
                        hashKini, pinOn)
                        && sesiLoginSegar(perChat, hashKini,
                                System.currentTimeMillis());
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean simpanLogin(Context ctx, long idRuang, String hash) {
        if (ctx == null || idRuang <= 0 || hash == null || hash.isEmpty()) {
            return false;
        }
        try {
            // Audit 2961: commit() agar hasil tulisnya bisa
            // dilaporkan jujur ke pemanggil (apply() gagal sunyi).
            return ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(kunciSesiLogin(idRuang),
                            nilaiSesiLogin(hash, System.currentTimeMillis())).commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean simpanLoginBilaHashTetap(Context ctx, long idRuang,
            String hashTerbukti) {
        if (ctx == null || idRuang < 0 || hashTerbukti == null
                || hashTerbukti.isEmpty()) {
            return false;
        }
        synchronized (PinGate.KUNCI_PERCOBAAN) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(
                        ServerService.PREFS, Context.MODE_PRIVATE);
                String kini = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
                if (hashTerbukti.equals(kini)) {
                    return simpanLogin(ctx, idRuang, kini);
                }
                return false;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    static void hapusLogin(Context ctx, long idRuang) {
        if (ctx == null) {
            return;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor ed = sp.edit()
                    .remove(kunciSesiLogin(idRuang));
            if (TgBackup.amanLong(sp, KEY_TG_LOGIN_CHAT, 0) == idRuang) {
                ed.remove(KEY_TG_LOGIN_CHAT).remove(KEY_TG_LOGIN_HASH);
            }
            ed.apply();
        } catch (Exception ignored) {
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
            long jeda429Ms = 0;
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
                    if (kode == 429) {
                        // Audit 2877: hormati retry_after
                        // Telegram - badan galatnya memuat
                        // jeda yang diminta.
                        try (InputStream ein = c.getErrorStream()) {
                            if (ein != null) {
                                jeda429Ms = TgBackup.jedaRetryAfterMs(
                                        bacaResponsHapus(ein, mulaiMs));
                            }
                        } catch (Exception ignored) {
                        }
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
            // Audit 2877: tidur = yang lebih besar antara
            // backoff lama dan retry_after; bila jeda yang
            // diminta tak muat di sisa anggaran, menyerah
            // jujur (pemanggil memperingatkan pemilik).
            long tidurMs = Math.max(coba * 1000L, jeda429Ms);
            if (System.currentTimeMillis() - mulaiMs + tidurMs
                    >= BATAS_HAPUS_PESAN_MS) {
                return false;
            }
            try {
                Thread.sleep(tidurMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    static void peringatkanBilaTakBisaHapus(Context ctx, boolean berhasil, long idRuang) {
        if (berhasil) {
            // Audit 2819: cek-lalu-set bendera peringatan
            // diserialkan; pemanggil kini dari pool latar (2588).
            synchronized (KUNCI_PERINGATAN) {
                try {
                    SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                            Context.MODE_PRIVATE);
                    if (!TgBackup.amanBoolean(sp, KEY_TG_HAPUS_OK, false)
                            || TgBackup.amanBoolean(sp, KEY_TG_HAPUS_WARN, false)) {
                        sp.edit().putBoolean(KEY_TG_HAPUS_OK, true)
                                .remove(KEY_TG_HAPUS_WARN)
                                .remove(KEY_TG_HAPUS_WARN_AT).apply();
                    }
                } catch (Exception ignored) {
                }
            }
            return;
        }
        // Audit 2819: putusan + cap bendera atomik terhadap
        // tugas latar lain; kirim peringatan di luar kunci.
        synchronized (KUNCI_PERINGATAN) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                        Context.MODE_PRIVATE);
                // Audit 2878: gerbangnya stempel waktu,
                // bukan boolean sekali seumur kegagalan.
                if (!bolehPeringatkanHapusLagi(
                        TgBackup.amanLong(sp, KEY_TG_HAPUS_WARN_AT, 0),
                        System.currentTimeMillis())) {
                    return;
                }
                sp.edit().putBoolean(KEY_TG_HAPUS_WARN, true)
                        .putLong(KEY_TG_HAPUS_WARN_AT,
                                System.currentTimeMillis())
                        .apply();
            } catch (Exception ignored) {
                return;
            }
        }
        balas(ctx, idRuang, "Peringatan: bot tak bisa menghapus pesan ber-PIN"
                + " dari riwayat chat (tanpa hak hapus; di grup jadikan bot admin)."
                + " Hapus manual pesan berisi PIN agar tak dipakai ulang pengintip.");
    }

    static boolean segarWajibPrompt(boolean perluPinTombol, boolean grup,
            boolean pinAktif, boolean sudahLogin) {
        return (perluPinTombol || grup) && pinAktif && !sudahLogin;
    }

    static boolean bolehPeringatkanPinAsing(long terakhirMs, long kiniMs) {
        return terakhirMs <= 0 || kiniMs - terakhirMs >= 3600_000L;
    }

    // Audit 2633: cap peringatan per chat asing
    // tak boleh menumpuk selamanya - cap lebih
    // tua dari 30 hari kedaluwarsa dan jumlah
    // kunci dibatasi keras; sisanya dibuang
    // mulai dari yang tertua.
    static final long MASA_CAP_PERINGATAN_ASING_MS = 30L * 24 * 3600_000L;
    static final int BATAS_KUNCI_PERINGATAN_ASING = 64;

    static boolean capPeringatanKedaluwarsa(long terakhirMs, long kiniMs) {
        return terakhirMs <= 0
                || kiniMs - terakhirMs > MASA_CAP_PERINGATAN_ASING_MS;
    }

    static java.util.List<String> kunciPeringatanDiLuarBatas(
            java.util.Map<String, Long> cap, int batas) {
        java.util.List<String> buang = new java.util.ArrayList<>();
        if (cap == null || cap.size() <= batas) {
            return buang;
        }
        java.util.List<java.util.Map.Entry<String, Long>> urut =
                new java.util.ArrayList<>(cap.entrySet());
        java.util.Collections.sort(urut,
                new java.util.Comparator<java.util.Map.Entry<String, Long>>() {
                    @Override
                    public int compare(java.util.Map.Entry<String, Long> a,
                            java.util.Map.Entry<String, Long> b) {
                        return Long.compare(a.getValue(), b.getValue());
                    }
                });
        for (int i = 0; i < urut.size() - batas; i++) {
            buang.add(urut.get(i).getKey());
        }
        return buang;
    }

    static void peringatkanPinChatAsing(Context ctx, long idRuang) {
        peringatkanPinChatAsing(ctx, idRuang, false);
    }

    // Audit 2959: jalur kesempatan terakhir
    // (entri kedaluwarsa/terpangkas - entri
    // SUDAH dicoret dari antrean) memanggil
    // dengan lewatiThrottle=true: peringatan
    // terakhir itu tak boleh tertelan throttle
    // 1 jam per chat, persis alasan sisi
    // milik-sendiri membersihkan bendera
    // dedupnya di jalur kedaluwarsa (2936).
    // Cap tetap ditulis agar jalur biasa
    // sesudahnya tetap ter-throttle.
    static void peringatkanPinChatAsing(Context ctx, long idRuang,
            boolean lewatiThrottle) {
        if (ctx == null || idRuang == 0) {
            return;
        }
        // Audit 2819: cek cap + tulis cap diserialkan;
        // dua tugas latar tak lagi bisa sama-sama lolos.
        synchronized (KUNCI_PERINGATAN) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                        Context.MODE_PRIVATE);
                String kunci = KEY_TG_WARN_ASING_PREFIX + idRuang;
                long terakhir = TgBackup.amanLong(sp, kunci, 0);
                long kini = System.currentTimeMillis();
                if (!lewatiThrottle
                        && !bolehPeringatkanPinAsing(terakhir, kini)) {
                    return;
                }
                // Audit 2633: satu edit untuk cap baru
                // + sapuan kedaluwarsa + batas jumlah.
                SharedPreferences.Editor ed = sp.edit().putLong(kunci, kini);
                java.util.Map<String, Long> cap =
                        new java.util.HashMap<>();
                for (java.util.Map.Entry<String, ?> e
                        : sp.getAll().entrySet()) {
                    if (!e.getKey().startsWith(KEY_TG_WARN_ASING_PREFIX)
                            || e.getKey().equals(kunci)) {
                        continue;
                    }
                    long nilai = e.getValue() instanceof Long
                            ? (Long) e.getValue() : 0;
                    if (capPeringatanKedaluwarsa(nilai, kini)) {
                        ed.remove(e.getKey());
                    } else {
                        cap.put(e.getKey(), nilai);
                    }
                }
                cap.put(kunci, kini);
                for (String buang : kunciPeringatanDiLuarBatas(
                        cap, BATAS_KUNCI_PERINGATAN_ASING)) {
                    ed.remove(buang);
                }
                ed.apply();
            } catch (Exception e) {
                return;
            }
        }
        balas(ctx, idRuang, "Peringatan: pesan berisi PIN terdeteksi di chat ini,"
                + " padahal chat ini bukan chat resmi bot dan bot tak bisa"
                + " menghapusnya dari sini. Hapus manual pesan itu sekarang"
                + " dan pertimbangkan ganti PIN di aplikasi.");
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
        jawabCallback(ctx, callbackId, null);
    }

    // Audit 2790: varian berteks - teks tampil sebagai alert ke
    // penekan (answerCallbackQuery text + show_alert).
    private static void jawabCallback(Context ctx, String callbackId, String teks) {
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
                String isi = "callback_query_id="
                        + URLEncoder.encode(callbackId, "UTF-8");
                if (teks != null && !teks.isEmpty()) {
                    isi += "&text=" + URLEncoder.encode(teks, "UTF-8")
                            + "&show_alert=true";
                }
                byte[] body = isi.getBytes(StandardCharsets.UTF_8);
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
        // Audit 2588: balasan perintah lewat corong interaktif,
        // tak lagi mengantre di belakang notifikasi server.
        TgBackup.sendInteraktifKe(ctx, String.valueOf(idRuang), text);
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
            // Audit 2869: nama bot belum diketahui dan ini
            // chat pribadi - sufiks pasti tertuju ke bot
            // ini (chat pribadi hanya berisi satu bot),
            // jangan buang diam-diam. Grup tetap dibuang:
            // di sana banyak bot dan sufiks memang penapis.
            if (idRuang > 0 && namaBotTersimpan(ctx).isEmpty()) {
                verifikasi = dasar;
            } else {
                return false;
            }
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
            case "/login":
                return tanganiLogin(ctx, arg, idRuang);
            case "/logout":
                return tanganiLogout(ctx, idRuang);
            case "/help":
                TgBackup.sendMessageKbKe(ctx, String.valueOf(idRuang), teksMenu(ctx, MENU_UTAMA, idRuang),
                        keyboardMenu(MENU_UTAMA));
                return false;
            default:
                balas(ctx, idRuang, "Perintah tidak dikenal. Ketik /help");
                return false;
        }
    }

    private static boolean tanganiLogin(Context ctx, String arg, long idRuang) {
        if (idRuang < 0) {
            balas(ctx, idRuang, "Login hanya bisa dari chat pribadi bot.");
            return false;
        }
        if (!pinPerangkatAktif(ctx)) {
            balas(ctx, idRuang, "PIN aplikasi belum aktif, jadi login belum"
                    + " diperlukan. Aktifkan PIN dulu di Pengaturan aplikasi.");
            return false;
        }
        if (sudahLogin(ctx, idRuang)) {
            balas(ctx, idRuang, "Kamu sudah login. Semua perintah bisa langsung"
                    + " dipakai; kirim /logout untuk keluar.");
            return false;
        }
        String pin = pinTebakanArgumen(arg);
        if (pin == null) {
            balas(ctx, idRuang, "Kirim /login <PIN> dari chat pribadi, mis."
                    + " /login 123456 (PIN ber-spasi: /login PIN:\"kunci saya\")."
                    + " Login berlaku 7 hari, atau sampai /logout atau PIN diganti.");
            return false;
        }
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        long sekarang = System.currentTimeMillis();
        long sisa = PinGate.sisaKunciMs(ctx, sekarang);
        if (sisa > 0) {
            balasPenting(ctx, idRuang, "PIN terkunci sementara (kebanyakan gagal)."
                    + " Coba lagi " + ((sisa + 59000) / 60000) + " menit.");
            return true;
        }
        boolean cocok = false;
        long sisaKalah = 0;
        String hashTerbukti = "";
        synchronized (PinGate.KUNCI_PERCOBAAN) {
            String hash = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
            long sisa2 = PinGate.sisaKunciMs(ctx, System.currentTimeMillis());
            if (sisa2 > 0) {
                sisaKalah = sisa2;
            } else {
                cocok = PinCrypto.verify(hash == null ? "" : hash, pin);
                if (cocok) {
                    hashTerbukti = hash;
                }
                PinGate.catatHasil(ctx, cocok, sekarang);
            }
        }
        if (sisaKalah > 0) {
            balasPenting(ctx, idRuang, "PIN terkunci sementara (kebanyakan gagal)."
                    + " Coba lagi " + ((sisaKalah + 59000) / 60000) + " menit.");
            return true;
        }
        if (!cocok) {
            balas(ctx, idRuang, "PIN salah, login ditolak.");
            return true;
        }
        if (PinCrypto.perluUpgradeHash(hashTerbukti)) {
            try {
                String hashBaru = PinCrypto.hash(pin);
                synchronized (PinGate.KUNCI_PERCOBAAN) {
                    String kini = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
                    if (hashTerbukti.equals(kini)) {
                        sp.edit().putString(PinGate.KEY_PIN_HASH, hashBaru).apply();
                        hashTerbukti = hashBaru;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // Audit 2961: keberhasilan hanya dinyatakan bila
        // sesi benar tersimpan - dulu balasan sukses dikirim
        // juga saat penyimpanan diam-diam tak terjadi.
        if (!simpanLoginBilaHashTetap(ctx, idRuang, hashTerbukti)) {
            balas(ctx, idRuang, "PIN benar, tapi sesi login gagal disimpan"
                    + " (PIN mungkin baru diganti atau penyimpanan"
                    + " bermasalah). Coba /login lagi.");
            return true;
        }
        balas(ctx, idRuang, "Login berhasil. Semua perintah sekarang bisa"
                + " langsung dipakai tanpa PIN selama 7 hari (atau sampai"
                + " kamu /logout atau PIN diganti).");
        TgBackup.sendMessageKbKe(ctx, String.valueOf(idRuang),
                teksMenu(ctx, MENU_UTAMA, idRuang), keyboardMenu(MENU_UTAMA));
        return true;
    }

    private static boolean tanganiLogout(Context ctx, long idRuang) {
        if (idRuang < 0) {
            balas(ctx, idRuang, "Logout hanya bisa dari chat pribadi bot.");
            return false;
        }
        boolean sedang = sudahLogin(ctx, idRuang);
        hapusLogin(ctx, idRuang);
        balas(ctx, idRuang, sedang
                ? "Sudah logout. Perintah ber-PIN akan meminta login lagi."
                : "Kamu belum login.");
        return false;
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
        try {
            if (ServerService.restart(ctx)) {
                // Audit 2891: stempel jeda dipasang
                // SESUDAH restart terbukti berjalan -
                // dulu dipasang sebelumnya, jadi
                // restart gagal/tak berlaku tetap
                // mengunci 30 dtk dengan pesan keliru
                // "Restart baru saja dilakukan".
                restartManualTerakhirMs = kiniRestart;
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
                // Audit 3008: rollback pin untuk SEMUA kunci -
                // syarat versi != null dulu membuat "terbaru"
                // (normalisasinya null) tak pernah di-rollback,
                // jadi pin lama hilang permanen walau update
                // gagal, padahal pin baru sudah terlanjur
                // ditulis sebelum update dicoba.
                if (kunci) {
                    Updater.simpanKuncianBinary(ctx,
                            pinLama == null ? "" : pinLama);
                }
                String pesan = e.getMessage();
                String ramah = Updater.mengandungSaran(pesan)
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
                // Audit 3008: pola sama seperti unduhBinerDariBot.
                if (kunci) {
                    Updater.simpanKuncianWebVault(ctx,
                            pinLama == null ? "" : pinLama);
                }
                String pesan = e.getMessage();
                String ramah = Updater.mengandungSaran(pesan)
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

    static boolean perintahBisaSegarkan(String cmd) {
        return "/status".equals(cmd) || "/uptime".equals(cmd)
                || "/alive".equals(cmd) || "/versi".equals(cmd);
    }

    static String keyboardSegarkan(String cmd) {
        return rakitKeyboard(new String[][]{
                {"\uD83D\uDD04 Segarkan", "segar:" + cmd},
                {"\uD83C\uDFE0 Menu utama", "m:" + MENU_UTAMA},
        });
    }

    static void balasLaporan(Context ctx, long idRuang, String teks, String cmd) {
        if (teks != null && teks.length() <= 3500) {
            TgBackup.sendMessageKbKe(ctx, String.valueOf(idRuang), teks,
                    keyboardSegarkan(cmd));
        } else {
            balas(ctx, idRuang, teks);
        }
    }

    static void segarkanLaporan(Context ctx, long idRuang, int idPesan,
            String cmd) {
        if (ctx == null || idPesan == 0) {
            return;
        }
        String kb = keyboardSegarkan(cmd);
        if ("/alive".equals(cmd)) {
            if (!Util.jalankanBg(() -> TgBackup.editMessageKb(ctx, idRuang, idPesan,
                    pesanAliveRinci(ServerService.pingRinci(ctx)), kb))) {
                balas(ctx, idRuang, "Sistem sibuk, coba lagi sebentar.");
            }
            return;
        }
        String teks;
        if ("/status".equals(cmd)) {
            teks = statusText(ctx);
        } else if ("/versi".equals(cmd)) {
            teks = teksVersi(ctx);
        } else {
            long up = ServerService.uptimeMs();
            teks = up <= 0 ? "Server sedang berhenti."
                    : "Server jalan selama " + durationText(up) + ".";
        }
        TgBackup.editMessageKb(ctx, idRuang, idPesan, teks, kb);
    }

    private static boolean perintahInfo(Context ctx, String cmd, String arg, long idRuang) {
        // Audit 861: SEMUA perintah info kini melewati gerbang PIN yang
        // sama bila PIN aktif atau chat berupa grup. Dulu /uptime,
        // /alive, /versi menjawab lebih dulu sebelum gerbang — tak
        // konsisten dengan /status, /log, /crashlog di fungsi yang sama
        // dan niat pengecualiannya tak tercatat; kini perilakunya satu.
        boolean perluPin = pinPerangkatAktif(ctx) || chatResmiAdalahGrup(ctx);
        if (perluPin && authDangerous(ctx, arg, cmd.substring(1), idRuang) == null) {
            return false;
        }
        // Audit 2789: jeda info per chat - perintah yang lolos
        // gerbang tetap dibatasi agar tak membanjiri antrean.
        long kiniInfo = SystemClock.elapsedRealtime();
        Long infoSebelumnya = infoTerakhirPerChat.get(idRuang);
        if (infoSebelumnya != null && dalamJedaInfo(infoSebelumnya, kiniInfo)) {
            balas(ctx, idRuang, "Terlalu cepat - tunggu beberapa detik"
                    + " lalu coba lagi.");
            return perluPin;
        }
        infoTerakhirPerChat.put(idRuang, kiniInfo);
        if ("/uptime".equals(cmd)) {
            long up = ServerService.uptimeMs();
            if (up <= 0) {
                balasLaporan(ctx, idRuang, "Server sedang berhenti.", cmd);
            } else {
                balasLaporan(ctx, idRuang,
                        "Server jalan selama " + durationText(up) + ".", cmd);
            }
            return perluPin;
        }
        if ("/alive".equals(cmd)) {
            if (!Util.jalankanBg(() -> balasLaporan(ctx, idRuang,
                    pesanAliveRinci(ServerService.pingRinci(ctx)), "/alive"))) {
                balas(ctx, idRuang, "Sistem sibuk, coba lagi sebentar.");
            }
            return perluPin;
        }
        if ("/versi".equals(cmd)) {
            balasLaporan(ctx, idRuang, teksVersi(ctx), cmd);
            return perluPin;
        }
        if ("/status".equals(cmd)) {
            balasLaporan(ctx, idRuang, statusText(ctx), cmd);
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
        // Audit 1727: jangan tawarkan restore dari penanda milik
        // bot lain — file_id-nya tak berlaku untuk bot ini.
        if (TgBackup.penandaBotLain(
                TgBackup.amanString(sp, TgBackup.KEY_TG_LAST_BOT_ID, ""),
                idBotDariToken(TgBackup.amanString(sp,
                        TgBackup.KEY_TG_TOKEN, "")))) {
            return "Belum ada backup terkirim dari bot ini"
                    + " - backup terakhir dibuat dengan bot lain."
                    + " Kirim /backup dulu.";
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
            // Kunci tugas data diambil SEBELUM unduh + dekripsi (Audit
            // 850): dulu keduanya terlanjur dikerjakan lalu hasilnya
            // dibuang bila tugas lain sedang berjalan, dan berkas yang
            // dipulihkan bisa lebih baru daripada yang dikonfirmasi
            // pengguna karena penanda backup terakhir dibaca saat unduh
            // dimulai — kini penanda dibaca di dalam kunci, sesudah
            // prompt, tanpa tugas data lain berjalan.
            if (!TgBackup.kunciRestore()) {
                throw new IOException("Tugas backup/restore lain sedang berjalan,"
                        + " coba lagi sebentar.");
            }
            dikunci = true;
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

        if (sudahLogin(ctx, idRuang)) {
            return pisahkanPin(t)[0];
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
        boolean cocok = false;
        long sisaKalah = 0;
        synchronized (PinGate.KUNCI_PERCOBAAN) {
            // Audit 860: hash dibaca ulang SEGAR di dalam kunci sebelum
            // verifikasi pertama. Bacaan luar bisa basi dua arah: PIN lama
            // (sudah tertutup verifikasi ulang Audit 809) dan PIN BARU
            // yang benar ditolak + tercatat gagal karena diverifikasi ke
            // hash lama sesudah PIN diganti di bawah kunci yang sama.
            // Audit 864: bacaan segar selalu dipakai, termasuk bila
            // KOSONG (PIN baru saja dimatikan/direset di bawah kunci yang
            // sama) — fail-closed seperti jalur UI, bukan jatuh kembali
            // ke hash basi hasil bacaan luar kunci.
            String segarAwal = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
            hash = segarAwal == null ? "" : segarAwal;
            // Audit 791: cek ulang otoritatif di dalam kunci bersama —
            // tanpa ini percobaan bot dan UI bisa sama-sama lolos cek awal
            // dan sama-sama memverifikasi sebelum satu pun mencatat.
            long sisa2 = PinGate.sisaKunciMs(ctx, System.currentTimeMillis());
            if (sisa2 > 0) {
                sisaKalah = sisa2;
            } else {
                cocok = PinCrypto.verify(hash, pin);
                if (cocok) {

                    String segar = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
                    if (segar != null && !segar.isEmpty() && !segar.equals(hash)) {
                        hash = segar;
                        cocok = PinCrypto.verify(hash, pin);
                    }
                }
                PinGate.catatHasil(ctx, cocok, sekarang);
            }
        }
        if (sisaKalah > 0) {
            balasPenting(ctx, idRuang, "PIN terkunci sementara (kebanyakan gagal)."
                    + " Coba lagi " + ((sisaKalah + 59000) / 60000) + " menit.");
            return null;
        }
        if (cocok) {
            if (PinCrypto.perluUpgradeHash(hash)) {
                try {
                    String hashBaru = PinCrypto.hash(pin);
                    synchronized (PinGate.KUNCI_PERCOBAAN) {
                        String kini = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
                        if (hash.equals(kini)) {
                            sp.edit().putString(PinGate.KEY_PIN_HASH, hashBaru).apply();
                        }
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
            // Audit 2880: tugas DITOLAK (tak
            // pernah jalan) tak boleh memasang
            // stempel berakhir - perintah berat
            // berikutnya dalam 30 dtk dulu
            // ditolak dengan pesan keliru
            // "baru saja selesai".
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
                    // Audit 2608: acquire gagal TIDAK menghentikan
                    // rantai - tick berikutnya tetap dijadwalkan di
                    // bawah agar perpanjangan dicoba lagi, bukan
                    // berhenti diam-diam mengandalkan sisa timeout.
                }
                // Audit 2563: tugas bisa selesai di antara cek
                // kunciMasihDijaga di atas dan acquire ini - tanpa
                // cek ulang, acquire yatim menahan wakelock sampai
                // timeout-nya karena tick berikutnya berhenti
                // tanpa release.
                if (selesai.get()) {
                    try {
                        if (milik.isHeld()) {
                            milik.release();
                        }
                    } catch (Exception ignored) {
                    }
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

    // Audit 2970: menaiki kembali sampai setinggi
    // penanda tersimpan bukan lompatan mencurigakan -
    // perangkat sungguh pernah mencapai waktu itu.
    static boolean wallBolehCatat(long maksKini, long tersimpan, long kini) {
        if (wallBolehMaju(maksKini, kini)) {
            return true;
        }
        return kini >= 1_000_000_000_000L && kini > maksKini
                && tersimpan > 0 && kini <= tersimpan;
    }

    static void catatWall(long kini) {
        synchronized (KUNCI_WALL) {
            long tersimpan = wallMaksTersimpan == Long.MIN_VALUE
                    ? 0 : wallMaksTersimpan;
            if (wallBolehCatat(wallMaksTelegram, tersimpan, kini)) {
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

            // Audit 2970: penanda tersimpan TIDAK lagi
            // dihapus walau jauh di depan jam sekarang -
            // jam sekarang bisa jadi yang salah (boot
            // tertinggal). Ia hanya tidak ditanamkan ke
            // jangkar ingatan; catatWall boleh menaiki
            // kembali sampai setingginya (wallBolehCatat).
            wallMaksTersimpan = s;
            synchronized (KUNCI_WALL) {
                if (s > wallMaksTelegram
                        && s - System.currentTimeMillis()
                                <= BATAS_LOMPATAN_JAM_MS) {
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
        // Jalur Telegram memang satu bahasa (Indonesia) by design.
        return durationText(ms, "jam", "menit", "detik");
    }

    // Audit 991: varian ber-satuan dari pemanggil — layar utama dan
    // Settings mengisi format resource uptime dengan fungsi ini, jadi
    // satuannya mengikuti locale perangkat (resource dur_*), bukan
    // terkunci Indonesia seperti jalur Telegram.
    static String durationText(long ms, String jam, String menit, String detik) {

        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        StringBuilder o = new StringBuilder(24);
        if (h > 0) {
            return o.append(h).append(" ").append(jam).append(" ")
                    .append(m).append(" ").append(menit).toString();
        }
        if (m > 0) {
            return o.append(m).append(" ").append(menit).append(" ")
                    .append(s % 60).append(" ").append(detik).toString();
        }
        return o.append(s).append(" ").append(detik).toString();
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

    // Audit 1002: HTTP 409 pada getUpdates berarti webhook terpasang
    // atau ada konsumen getUpdates lain — tanpa penanganan, polling
    // mati selamanya dengan satu baris log generik. Catat penjelasan
    // yang berbeda dan coba hapus webhook (penyebab yang bisa sembuh
    // sendiri), dijepit 10 menit agar tidak berbadai.
    private static void tanganiKonflikPoll(Context ctx, String url) {
        try {
            long kini = SystemClock.elapsedRealtime();
            if (kini - hapusWebhookTerakhirMs < 10L * 60L * 1000L) {
                return;
            }
            hapusWebhookTerakhirMs = kini;
            ServerService.catatLog("[tg] Konflik polling (HTTP 409): bot sedang"
                    + " dipakai aplikasi lain atau webhook terpasang."
                    + " Mencoba menghapus webhook agar polling pulih.");
            String urlHapus = url.substring(0, url.length() - "getUpdates".length())
                    + "deleteWebhook";
            String hasil = httpPostForm(ctx, urlHapus, "");
            ServerService.catatLog(hasil != null
                    ? "[tg] Webhook terhapus; polling dicoba lagi seperti biasa."
                    : "[tg] Penghapusan webhook gagal; konflik mungkin dari"
                            + " konsumen getUpdates lain — matikan aplikasi"
                            + " lain yang memakai bot ini.");
            // Audit 2870: penghapusan webhook tak lagi
            // diam-diam - pemilik bisa saja memasangnya
            // sengaja untuk integrasi lain dan berhak
            // tahu integrasinya baru saja diputus.
            if (hasil != null) {
                TgBackup.sendMessage(ctx, "Webhook Telegram terhapus"
                        + " otomatis (konflik polling HTTP 409) agar bot"
                        + " aplikasi ini bisa menerima perintah lagi."
                        + " Bila kamu memasang webhook itu sengaja untuk"
                        + " keperluan lain, pasang kembali sesudah bot ini"
                        + " berhenti.");
            }
        } catch (Exception ignored) {
        }
    }

    private static String httpPostForm(Context ctx, String url, String param) {
        return httpPostForm(ctx, url, param, 35000);
    }

    private static String httpPostForm(Context ctx, String url, String param,
                                       int readMs) {
        HttpURLConnection c = null;
        try {
            byte[] body = param.getBytes(StandardCharsets.UTF_8);
            c = TgBackup.bukaPostTelegram(ctx, url, body,
                    "application/x-www-form-urlencoded", 15000, readMs);
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
                if (code == 409 && url.endsWith("/getUpdates")) {
                    tanganiKonflikPoll(ctx, url);
                }
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
