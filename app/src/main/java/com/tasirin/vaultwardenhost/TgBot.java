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
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/** Remote kontrol Vaultwarden Host lewat Telegram bot (long polling getUpdates). */
public final class TgBot {

    public static final String ACTION_POLL = "com.tasirin.vaultwardenhost.TG_POLL";
    static final String KEY_TG_OFFSET = "tg_bot_offset";
    static final String KEY_TG_MENU_HASH = "tg_menu_hash";
    /** Sidik menu perintah (ganti hashCode int yang rawan kolisi 32-bit). */
    static final String KEY_TG_MENU_FP = "tg_menu_fp";
    static final int MENU_REV = 5;
    /** Awalan kunci prefs aksi tombol ber-PIN yang menunggu balasan PIN. */
    private static final String KEY_TG_PENDING_PREFIX = "tg_pending_aksi_";
    /** Umur aksi tombol ber-PIN: PIN harus dibalas < 5 menit (seperti Red-Eye). */
    static final long PENDING_AKSI_MS = 5 * 60_000L;
    /** Panel menu tombol; callback_data = "m:" + panel. */
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
    /** Chat grup yang sudah diperingatkan (sekali per proses agar tak spam). */
    private static volatile String grupDiperingatkan = "";
    /** Kunci tugas berat bot agar backup/restore/update tak jalan bersamaan. */
    private static final AtomicBoolean TUGAS_BERAT = new AtomicBoolean(false);
    /** Wall-clock terbesar yang pernah terlihat (deteksi jam mundur/NTP).
     *  Bila jam mundur jauh, tombol/pesan basi diperlakukan kedaluwarsa (fail-closed). */
    private static volatile long wallMaksTelegram = 0;
    /** Nilai wall-maks yang sudah sinkron dengan prefs di proses ini
     *  (MIN_VALUE = belum sinkron): simpanWallMaks + muatWallMaks satu-satunya
     *  penulis/pembaca sehingga baca prefs tiap 20 dtk tak perlu. */
    private static volatile long wallMaksTersimpan = Long.MIN_VALUE;
    /** Kunci tanda air wall-clock: baca-ubah-tulis wajib atomik agar update
     *  maksimum tak hilang saat dua poll tumpang tindih. */
    private static final Object KUNCI_WALL = new Object();
    /** Kunci prefs maks wall-clock agar deteksi rollback selamat dari restart. */
    static final String KEY_TG_WALL_MAKS = "tg_wall_maks";
    /** Sudah diperingatkan sekali bahwa bot tak bisa hapus pesan ber-PIN. */
    static final String KEY_TG_HAPUS_WARN = "tg_hapus_warn";
    /** Uji hapus sekali lolos (bot punya hak hapus di chat). */
    static final String KEY_TG_HAPUS_OK = "tg_hapus_ok";
    /** Peringatan jam-mundur dibatasi 1x/jam (jam monotonik): tiap poll 20 dtk
     *  mengirim pesan = spam + self-DoS bila maks macet di masa depan. */
    static final long PERINGATAN_MUNDUR_MS = 3600_000;
    static volatile long peringatanMundurPada = 0;

    private static final String TG_API = "https://api.telegram.org/bot";

    // Satu pool kecil untuk tugas bot (perintah, callback, menu): hemat thread
    // dibanding new Thread per ketukan/perintah di STB 1 GB.
    // Antrean dibatasi agar spam update tak menumpuk OOM di STB 1 GB.
    // Satu pintu Util (tolak tugas baru saat antrean penuh + jejak log).
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
                {"help", "Menu tombol & daftar perintah"},
        };
    }

    /** Pesan hasil cek /alive (dipisah agar bisa diuji tanpa jaringan). Murni. */
    static String pesanAlive(boolean sehat) {
        return sehat ? "Server sehat (HTTPS 200 /alive)."
                : "Server TIDAK merespon /alive!";
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

    /** Tulis escape unicode manual: String.format per char boros Formatter+Locale. */
    private static void tambahEscapeUnicode(StringBuilder o, char c) {
        o.append('\\').append('u');
        o.append(Util.HEKS[(c >> 12) & 15]);
        o.append(Util.HEKS[(c >> 8) & 15]);
        o.append(Util.HEKS[(c >> 4) & 15]);
        o.append(Util.HEKS[c & 15]);
    }

    /** Pola statis: String.split/matches/replaceAll kompilasi regex tiap panggil.
     *  Validasi PIN numerik/kata kini loop char manual (Util) tanpa regex. */
    private static final java.util.regex.Pattern POLA_PIN_MARKER =
            java.util.regex.Pattern.compile("(?is)\\bPIN\\s*:");
    private static final java.util.regex.Pattern POLA_OK_TRUE =
            java.util.regex.Pattern.compile("\"ok\"\\s*:\\s*true");

    /** Tanggal status via cache terpusat Util (satu pintu, hemat ThreadLocal). */

    /** True bila body getMe mengandung ok-true (tanpa pindai regex DOTALL). */
    static boolean responsOk(String body) {
        if (body == null) {
            return false;
        }
        return POLA_OK_TRUE.matcher(body).find();
    }

    /** Kupas @ awalan tanpa regex (replaceFirst kompilasi tiap panggil). */
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

    /** Samarkan spasi/tab/newline ganda jadi satu spasi (pola statis). */
    static String rapikanSpasi(String t) {
        // Manual tanpa regex: tiap pesan Telegram masuk; hemat kompilasi di STB.
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
                tambahEscapeUnicode(o, c);
            } else if (c == '\u2028' || c == '\u2029') {
                // Pemisah baris Unicode memutus string di parser JS lawas —
                // escape agar payload tetap satu string valid.
                tambahEscapeUnicode(o, c);
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    // Pasangan surrogate valid (emoji): biarkan utuh.
                    o.append(c).append(s.charAt(i + 1));
                    i++;
                } else {
                    // Surrogate yatim tak bisa di-encode UTF-8/JSON valid.
                    tambahEscapeUnicode(o, c);
                }
            } else if (Character.isLowSurrogate(c)) {
                // Low surrogate yatim (tanpa high sebelumnya).
                tambahEscapeUnicode(o, c);
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

    /** Sidik pendaftaran menu (SHA-256 hex token + revisi menu; "" bila kosong/gagal).
     *  Ganti cap int hashCode() 32-bit yang bisa kolisi antar token berbeda
     *  (menu basi tak refresh) — murni agar bisa unit test. */
    static String sidikMenu(String token) {
        String t = token == null ? "" : token.trim();
        if (t.isEmpty()) {
            return "";
        }
        try {
            byte[] h = Util.mdSha256().digest((t + "|" + MENU_REV).getBytes(StandardCharsets.UTF_8));
            // Hex manual (bukan String.format per byte) agar murah di STB lama.
            return Updater.toHex(h);
        } catch (Exception e) {
            return "";
        }
    }

    /** Kelas galat poll terakhir yang sudah dicatat (anti-spam log tiap 20 dtk). */
    private static volatile String galatPollTercatat = "";

    /** Daftarkan menu perintah ke BotFather API (best-effort, sekali per token). */
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
            // chat sudah Util.amanTrim (tak-null + ter-trim): tanpa trim ulang per poll.
            // Multi-akun: config boleh daftar ID pisah koma, satu ID lama tetap jalan.
            String chatKonfig = chat;
            // ID negatif = grup/supergrup: semua anggota bisa memerintah bot
            // (sensitif tetap butuh PIN), jadi peringatkan sekali per proses
            // agar pemilik pindah ke chat pribadi bila memungkinkan.
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
            // Username bot sendiri wajib dikenal agar perintah grup ber-@suffix
            // milik bot ini tak dibuang diam-diam: isi sekali via getMe
            // best-effort (gagal = perilaku lama, perintah ber-@ ditolak).
            isiNamaBotBilaKosong(ctx, token);
            // Token bot selalu ada di path URL (desain API Telegram
            // "bot<token>/metode" tak bisa dihindari); POST hanya menjaga
            // parameter offset/timeout tak ikut nangkring di URL/proxy-log.
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
                    // Snapshot jam + id sekali per poll (bukan per pesan):
                    // syscall jam ×10 dan lookup "id" ×4 per pesan boros di
                    // STB lama; selisih µs antar-pesan tak berarti untuk
                    // batas basi 5 menit.
                    long kiniPoll = System.currentTimeMillis();
                    // Tanda air yang melompat jauh ke masa depan (RTC sempat
                    // salah) tak boleh membisukan bot sampai tahun itu tiba:
                    // rebase ke jam kini yang sudah benar sebelum cek mundur.
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
                            // Hanya layani chat resmi (ID numerik atau @username).
                            String namaUser = c.optString("username", "");
                            long idRuang = c.optLong("id", -1);
                            if (Util.cocokChatBanyak(chatKonfig, idRuang, namaUser)) {
                                // Perintah basi (>5 mnt, mis. /stop tertunda saat bot
                                // offline) wajib diabaikan; offset tetap maju.
                                // Pesan masa depan (jam STB lambat) jangan dibuang.
                                long dateMs = msg.optLong("date", 0) * 1000L;
                                if (jamMundur(kiniPoll)) {
                                    // Fail-closed: jam tak dipercaya (rollback/NTP),
                                    // perintah berbahaya tak boleh jalan dari pesan
                                    // basi yang di-replay. Offset tetap maju agar
                                    // tak diproses ulang; maks dipertahankan agar
                                    // pulih sendiri saat jam kembali normal.
                                    if (catatMundurDanBolehIngatkan(kiniPoll)) {
                                        rollbackDitolak++;
                                    }
                                    continue;
                                } else {
                                    catatWall(kiniPoll);
                                }
                                if (!Util.pesanSegar(dateMs, kiniPoll,
                                        STALE_MSG_MS)) {
                                    continue;
                                }
                                String text = msg.optString("text", "").trim();
                                // Tombol ber-PIN meminta PIN lewat balasan chat:
                                // teks non-perintah saat ada aksi pending = kandidat
                                // PIN, bukan perintah biasa. Sesi 5 menit & hanya chat
                                // pribadi (aksi ber-PIN dari grup tak pernah dibuat).
                                // AksiPending membedakan "tak ada sesi" dari "sesi
                                // basi" agar PIN yang datang terlambat tetap dikenali
                                // dan dihapus dari riwayat Telegram.
                                AksiPending pending = AksiPending.TIDAK_ADA;
                                if (!text.isEmpty() && !text.startsWith("/")) {
                                    pending = ambilPending(ctx, idRuang, kiniPoll);
                                }
                                boolean pinPending = !pending.cmd.isEmpty();
                                // Obrolan biasa selama sesi terbuka bukan percobaan
                                // PIN: jangan diteruskan ke verifikasi (lima pesan
                                // obrolan dulu mengunci PIN app 5 menit) dan jangan
                                // menutup sesi yang masih berlaku.
                                String pin = pinPending ? pinDariBalasan(text) : null;
                                boolean obrolan = pinPending && pin == null;
                                if (pinPending) {
                                    // At-most-once: PIN + aksi destruktif tak boleh
                                    // diputar ulang setelah crash/restart.
                                    try {
                                        sp.edit().putLong(KEY_TG_OFFSET, newOffset).commit();
                                    } catch (Exception ignored) {
                                    }
                                } else {
                                    // Perintah lain membatalkan sesi PIN yang menggantung.
                                    if (text.startsWith("/")) {
                                        hapusPending(ctx, idRuang);
                                    }
                                    // At-most-once untuk perintah sensitif: kunci offset
                                    // SEBELUM eksekusi agar crash di tengah handle tak
                                    // memutar ulang perintah destruktif saat hidup lagi
                                    // (PIN masih nangkring di riwayat). Perintah baca
                                    // tetap at-least-once via commit akhir di finally.
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
                                        // Sesi sengaja TIDAK ditutup: obrolan bukan
                                        // tebakan PIN. Petunjuk bentuk eksplisit agar
                                        // PIN huruf murni/ber-spasi tak gagal diam.
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
                                    // Aksi tidak dijalankan (sesi >5 menit). Balasan
                                    // apa pun setelah sesi basi tak bisa dibedakan dari
                                    // PIN yang terlambat, jadi pesannya dihapus sekali
                                    // (sesi langsung dibersihkan) agar PIN asli tak
                                    // menempel di riwayat cloud.
                                    balas(ctx, idRuang, "Sesi PIN sudah kedaluwarsa"
                                            + " (>5 menit) - aksi tidak dijalankan dan"
                                            + " pesan balasan dihapus dari riwayat."
                                            + " Ketuk tombol lagi untuk PIN baru.");
                                } else {
                                    terotorisasi = handleCommand(ctx, text, idRuang);
                                }
                                // PIN yang lolos menempel di riwayat dan bisa dipakai
                                // ulang pengintip: hapus best-effort (perlu izin hapus,
                                // di poll thread sendiri); gagal sekali diperingatkan
                                // agar pemilik tahu PIN masih nangkring di cloud.
                                // Upaya GAGAL sengaja dipertahankan sebagai bukti
                                // brute-force; PIN basi dihapus karena aksinya tak
                                // pernah dijalankan.
                                int idPesan = msg.optInt("message_id", 0);
                                // Grup: pesan yang membawa tebakan PIN dihapus
                                // walau perintahnya ditolak. Dulu penghapusan
                                // mensyaratkan terotorisasi, sehingga PIN asli
                                // yang salah ketik di grup menempel di riwayat
                                // dan bisa dipakai anggota lain di chat pribadi.
                                boolean pinGrup = idRuang < 0 && pinTebakan(text) != null;
                                boolean hapusPin = pinBasi || pinGrup
                                        || (terotorisasi
                                        && (pinPending || pesanPinWajibHapus(text)));
                                if (idPesan != 0 && hapusPin && pinPerangkatAktif(ctx)) {
                                    boolean terhapus = hapusPesanPerintah(ctx,
                                            idRuang, idPesan);
                                    peringatkanBilaTakBisaHapus(ctx, terhapus, idRuang);
                                } else if (idPesan != 0 && "help".equals(namaPerintah(text))
                                        && pinPerangkatAktif(ctx)
                                        && !sudahUjiHapus(ctx)) {
                                    // Uji hapus sekali saat /help (tanpa PIN di riwayat):
                                    // hapus pesan /help sendiri sebagai proba hak hapus.
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
                    // Peringatan jam mundur bersifat perangkat, bukan balasan
                    // satu perintah: tetap disiarkan ke semua chat config.
                    TgBackup.sendMessage(ctx, "Jam STB sempat mundur drastis;"
                            + " perintah ditolak sementara. Periksa tanggal & jam STB.");
                }
            } finally {
                if (newOffset != offset) {
                    // apply() asinkron cukup di sini (baca/idempoten): hemat fsync eMMC
                    // tiap 20 dtk di STB lama. Perintah berbahaya sudah dikunci
                    // commit() sinkron SEBELUM eksekusi di atas (anti-replay).
                    sp.edit().putLong(KEY_TG_OFFSET, newOffset).apply();
                }
            }
        } catch (Exception e) {
            // Jangan bungkam: tanpa token di pesan, catat kelas galat saja
            // (sekali per jenis) agar polling yang mati total bisa didiagnosa.
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

    /** Rakit inline keyboard 2 tombol per baris dari pasangan {label, callback}.
     *  Satu pintu agar menu berjenjang & prompt PIN tak menduplikasi perakitan JSON. Murni. */
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

    /** True bila nama panel menu dikenal (callback "m:..." tak sembarang). Murni. */
    static boolean panelMenuValid(String panel) {
        if (panel == null) {
            return false;
        }
        String p = panel.trim();
        return p.equals(MENU_UTAMA) || p.equals(MENU_STATUS) || p.equals(MENU_KONTROL)
                || p.equals(MENU_BACKUP) || p.equals(MENU_UPDATE)
                || p.equals(MENU_SERTIFIKAT) || p.equals(MENU_INFO);
    }

    /** Keyboard panel menu berjenjang; callback "m:<panel>" hanya navigasi
     *  (tak mengubah keadaan) sehingga aman ditekan dari chat resmi mana pun.
     *  Tombol perintah tetap memakai callback "/perintah" lama agar keyboard
     *  yang sudah beredar tidak mati. Murni agar bisa unit test. */
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

    /** Teks panel menu. Bahasa Indonesia mengikuti pesan bot; menu utama memuat
     *  status ringkas agar pemilik tak perlu membuka panel Status dulu. */
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

    /** Salin rentang [mulai, akhir) ke String huruf-kecil ASCII (satu pintu:
     *  dipakai namaPerintah + namaPerintahUntukBot agar loop lower tak ganda).
     *  Batas dijepit aman sehingga pemanggil tak perlu guard sendiri. Murni. */
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

    /** Nama perintah tanpa `/`, argumen, dan imbuhan `@namabot` grup. Murni. */
    static String namaPerintah(String text) {
        if (text == null) {
            return "";
        }
        // Pindai token pertama manual tanpa trim(): teks utuh tak disalin,
        // hanya token pendek yang di-lowercase (tiap pesan Telegram masuk).
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

    /** Argumen perintah: teks sesudah pemisah whitespace pertama (spasi/tab),
     *  di-trim. indexOf(' ') buta terhadap tab sehingga PIN sesudah tab
     *  selalu ditolak; helper murni ini satu pintu agar tak drift. Murni. */
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

    /** Kunci prefs username bot sendiri (hasil getMe, tanpa @, huruf kecil).
      *  Diisi best-effort saat poll getMe berhasil; dipakai memvalidasi suffix
      *  @namabot pada perintah grup. Belum ada getMe tersimpan = belum diketahui. */
    static final String KEY_TG_BOT_USER = "tg_bot_user";

    /** Kunci prefs ID bot (angka sebelum ':' pada token) pemilik username
      *  tersimpan. Token baru = bot lain = ID lain, jadi username lama tak bisa
      *  lolos validasi suffix setelah token diganti. */
    static final String KEY_TG_BOT_ID = "tg_bot_id";

    /** ID bot dari token Telegram ("12345:AAF..." -> "12345"); "" bila token
      *  tak berpola (kosong/salah ketik). Murni agar bisa unit test. */
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
        // ID bot Telegram selalu angka (maks ~19 digit); tolak yang lain agar
        // token salah ketik tak dianggap "bot baru" berulang tiap poll.
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

    /** Username bot sendiri dari prefs ("" bila belum diketahui ATAU token
      *  sudah berganti). Murni I/O prefs; fail-closed: nama basi milik bot lain
      *  diabaikan sehingga perintah grup ber-@ bot baru tak tertukar. */
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
                // Token diganti (atau penanda ID belum ada dari versi lama):
                // anggap belum dikenal, getMe berikutnya mengisi ulang.
                return "";
            }
            return bersih;
        } catch (Exception e) {
            return "";
        }
    }

    /** Simpan username bot sendiri + ID bot pemiliknya (best-effort, dipanggil
      *  setelah getMe lolos). */
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

    /** Throttle getMe gagal 1 jam per token: STB offline dulu membayar
     *  handshake TLS gagal tiap 20 dtk poll; token diganti = coba langsung. */
    private static volatile long getMeCobaAt = 0;
    private static volatile String getMeCobaToken = "";
    static final long GETME_ULANG_MS = 60L * 60 * 1000;

    /** True bila getMe boleh dicoba lagi untuk token ini (murni waktu). */
    static boolean bolehCobaGetMe(String tokenRapi, String tokenCoba, long cobaAt, long kini) {
        if (tokenRapi == null || tokenRapi.isEmpty()) {
            return false;
        }
        if (!tokenRapi.equals(tokenCoba)) {
            return true;
        }
        return cobaAt <= 0 || kini < cobaAt || kini - cobaAt >= GETME_ULANG_MS;
    }

    /** Isi username bot sendiri via getMe bila prefs masih kosong ATAU token
      *  sudah berganti (= ID bot berbeda, lihat {@link #namaBotTersimpan}).
      *  Dipanggil sekali per poll sampai tersimpan; gagal (offline/token salah)
      *  = diam, perilaku fail-closed lama dipertahankan. Username yang berganti
      *  tanpa ganti token (langka) baru terbaca setelah prefs dibersihkan. */
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

    /** Pola username dalam respons getMe (dibatasi isi "result"). */
    private static final java.util.regex.Pattern POLA_USERNAME_GETME =
            java.util.regex.Pattern.compile("\"username\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Kupas username bot dari respons getMe (murni agar bisa unit test).
      *  Kembalikan "" bila respons bukan JSON ok / tanpa username.
      *  Kupas manual tanpa org.json: android.jar di JVM unit test hanya
      *  stub yang melempar sehingga versi JSONObject selalu "" di CI. */
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

    /** Nama perintah terverifikasi @suffix grup (murni agar bisa unit test).
      *  Bila perintah mengandung @namabot: jalankan hanya bila suffix sama
      *  dengan username bot sendiri (tanpa peka huruf); suffix milik bot lain
      *  berarti pesan terusan untuk bot lain → kembalikan "" (abaikan).
      *  Bila username bot belum diketahui: terima hanya perintah tanpa @
      *  (fail-closed); yang ber-@ diabaikan sampai getMe tersimpan. */
    static String namaPerintahUntukBot(String text, String usernameBot) {
        if (text == null) {
            return "";
        }
        // Token pertama manual (tanpa trim+split array) untuk tiap perintah masuk.
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
            // Bot sendiri belum dikenal → tolak semua yang ber-@.
            return "";
        }
        String milik = Util.rendahAscii(kupasAt(usernameBot));
        if (suffix.equals(milik)) {
            return cmd;
        }
        // Suffix milik bot lain → abaikan.
        return "";
    }

    /** True bila chat resmi wajib diperlakukan seperti grup (selalu butuh PIN).
     *  Fail-closed: config username/tak-terurai dianggap grup karena pemiliknya
     *  tak pasti (lihat Util.chatPerluAnggapGrup). */
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

    /** True bila data callback adalah navigasi panel menu atau perintah bot
     *  yang dikenal. Navigasi "m:<panel>" tak mengubah keadaan (aman dari grup). */
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

    /** Balas ketukan tombol inline: hanya dari chat resmi yang dijalankan. */
    private static void tanganiCallback(Context ctx, JSONObject cb, String chatKonfig) {
        try {
            JSONObject pesan = cb.optJSONObject("message");
            JSONObject ruang = pesan != null ? pesan.optJSONObject("chat") : null;
            long idRuang = ruang != null ? ruang.optLong("id", -1) : -1;
            String namaRuang = ruang != null ? ruang.optString("username", "") : "";
            // Satu pintu auth (cocokChat: trim + tanpa peka huruf): wajib ruang
            // resmi. Pengirim resmi saja tak cukup agar keyboard yang bocor /
            // diteruskan ke chat lain tak bisa mengeksekusi perintah dari luar.
            // (Tombol berbahaya tetap butuh PIN yang tak bisa dibawa tombol.)
            if (!Util.cocokChatBanyak(chatKonfig, idRuang, namaRuang)) {
                // Tetap jawab agar spinner tak jadi oracle resmi/tidak resmi.
                jawabCallback(ctx, cb.optString("id", ""));
                return;
            }
            // Tombol inline diberi umur 24 jam: tanpa batas, keyboard lama yang
            // bocor bisa di-replay selamanya. Batas 5 menit (seperti pesan ketik)
            // terlalu pendek untuk tombol, 24 jam komprominya.
            // Tanpa message tak ada tanggal: tolak agar tak bisa di-replay selamanya.
            if (pesan == null) {
                jawabCallback(ctx, cb.optString("id", ""));
                balas(ctx, idRuang, "Tombol sudah kedaluwarsa (>24 jam)."
                        + " Minta keyboard baru dengan /help lalu coba lagi.");
                return;
            }
            long tgl = pesan.optLong("date", 0) * 1000L;
            long kini = System.currentTimeMillis();
            if (jamMundur(kini)) {
                // Fail-closed seperti pesan: tombol ditolak sementara saat
                // jam tak dipercaya (peringatan dibatasi 1x/jam).
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
            // Data tombol perintah hanya "/perintah" tanpa PIN. Bila perintah
            // itu dijaga PIN, tombol memulai prompt PIN 5 menit (PIN tak bisa
            // dibawa callback_data); selain itu langsung dijalankan.
            if (callbackDataValid(data)) {
                hapusPending(ctx, idRuang);
                if (perluPinTombol(ctx, data)) {
                    mulaiAksiPin(ctx, idRuang, idPesan, data);
                } else {
                    handleCommand(ctx, data, idRuang);
                }
            }
        } catch (Exception e) {
            // Jangan telan diam-diam: galat di tengah callback membuat tombol
            // seolah mati (spinner sudah dijawab, perintah tak dijalankan)
            // tanpa jejak di log. Detail diredaksi karena bisa memuat token.
            try {
                ServerService.catatLog("[tg] Gagal memproses tombol: "
                        + TgBackup.redaksiDetailGalat(String.valueOf(e)));
            } catch (Exception ignored) {
            }
        }
    }

    /** Tampilkan panel menu; edit pesan yang sama agar riwayat tak menumpuk.
     *  Bila edit gagal (pesan lama/bukan milik bot), TgBackup mengirim baru. */
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

    /** True bila aksi tombol ber-PIN yang menunggu balasan masih berlaku.
     *  Fail-closed saat jam mundur (kini < dibuat): sesi dianggap kedaluwarsa. Murni. */
    static boolean pendingMasihBerlaku(long dibuat, long kini) {
        return dibuat > 0 && kini >= dibuat && kini - dibuat <= PENDING_AKSI_MS;
    }

    /** Hasil membaca sesi PIN yang menunggu balasan chat: perintah yang
     *  menunggu (kosong bila tak ada sesi) + penanda sesi kedaluwarsa.
     *  Penanda itu perlu agar pesan PIN yang datang terlambat tetap dikenali
     *  sebagai PIN dan dihapus dari riwayat Telegram; dulu {@code ambilPending}
     *  mengembalikan "" untuk sesi basi sehingga pesan PIN-nya menempel
     *  selamanya di cloud. */
    static final class AksiPending {
        static final AksiPending TIDAK_ADA = new AksiPending("", false);
        final String cmd;
        final boolean kedaluwarsa;

        private AksiPending(String cmd, boolean kedaluwarsa) {
            this.cmd = cmd;
            this.kedaluwarsa = kedaluwarsa;
        }
    }

    /** Simpan aksi tombol yang menunggu PIN (satu per chat, ditimpa bila baru). */
    private static void simpanPending(Context ctx, long chatId, String cmd, long waktu) {
        try {
            ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_TG_PENDING_PREFIX + chatId,
                            waktu + "|" + cmd).apply();
        } catch (Exception ignored) {
        }
    }

    /** Ambil aksi pending yang masih segar; selain itu kunci dibersihkan
     *  dan hasilnya menandai sesi basi (bukan "tak ada sesi"). */
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
                // Hanya sesi yang benar-benar kedaluwarsa yang ditandai:
                // isi rusak/asing bukan PIN, jangan sampai pesan obrolan
                // pengguna ikut terhapus.
                return new AksiPending("", basi);
            }
            return new AksiPending(cmd, false);
        } catch (Exception e) {
            return AksiPending.TIDAK_ADA;
        }
    }

    /** Hapus aksi pending chat ini (dipanggil saat batal, perintah lain, atau selesai). */
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

    /** Tombol perintah ber-PIN: minta PIN lewat balasan chat (5 menit).
     *  Grup & PIN mati diarahkan ke handleCommand agar pesan penolakan yang
     *  sudah ada (tanpa membuat sesi PIN di grup) tetap satu pintu. */
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

    /** PIN dari balasan sesi tombol ber-PIN; null bila teks bukan percobaan PIN.
     *  Bentuk eksplisit "PIN:..." selalu diterima (PIN boleh ber-spasi), sedangkan
     *  tanpa penanda hanya satu token tanpa spasi minimal 4 karakter yang berdigit.
     *  Kata huruf murni ("halo"), obrolan banyak kata, dan teks terlalu panjang
     *  adalah obrolan biasa: dulu semuanya diteruskan ke verifikasi sebagai PIN,
     *  sehingga lima pesan obrolan di tengah sesi mengunci PIN app 5 menit
     *  (PinGate dipakai bersama layar kunci Main/Settings). PIN tanpa digit dan
     *  PIN ber-spasi tetap bisa lewat bentuk eksplisit. Murni. */
    static String pinDariBalasan(String teks) {
        String t = teks == null ? "" : teks.trim();
        if (t.isEmpty() || t.length() > 128) {
            return null;
        }
        if (POLA_PIN_MARKER.matcher(t).lookingAt()) {
            String[] pisah = pisahkanPin(t);
            return pisah[1].isEmpty() ? null : pisah[1];
        }
        // Tanpa penanda: PIN implisit wajib satu token (spasi berarti obrolan;
        // PIN ber-spasi memakai PIN:"kunci saya") dan mengandung digit agar
        // kalimat biasa seperti "tolong" tak membakar hitungan lockout.
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

    /** Balasan PIN untuk aksi tombol: jalankan perintahnya dengan PIN eksplisit.
     *  {@code pin} sudah dinormalisasi {@link #pinDariBalasan} (hanya dipanggil
     *  untuk percobaan PIN; obrolan biasa tak pernah sampai ke sini). Sesi selalu
     *  dihapus sebelum eksekusi (salah PIN = ketuk tombol lagi) agar PIN salah
     *  berulang tak menambah lockout tanpa sengaja. True bila lolos otorisasi
     *  sehingga pesan PIN boleh dihapus dari riwayat. */
    private static boolean handlePinPending(Context ctx, String cmd, String pin, long idRuang) {
        hapusPending(ctx, idRuang);
        // Sesi PIN tak pernah dibuat untuk grup (mulaiAksiPin menolak lebih dulu);
        // tetap ditutup di sini agar grup tak bisa menyelesaikan sesi nyasar.
        if (idRuang < 0 || !perintahPerluPin(cmd)) {
            return false;
        }
        String p = pin == null ? "" : pin.trim();
        if (!pinPerangkatAktif(ctx)) {
            // PIN dimatikan dari app saat prompt masih terbuka: jalankan tanpa
            // PIN (jangan menempelkan "PIN:..." yang merusak argumen /update).
            return handleCommand(ctx, cmd, idRuang);
        }
        if (p.isEmpty() || p.length() > 128) {
            balas(ctx, idRuang, "PIN tidak valid. Ketuk tombol lagi untuk mencoba.");
            return false;
        }
        return handleCommand(ctx, cmd + " PIN:" + p, idRuang);
    }

    /** True bila teks adalah perintah berbahaya (mengubah keadaan) sehingga
     *  wajib PIN bila PIN aktif. Perintah baca yang memuat data sensitif
     *  diurus terpisah oleh perintahPerluPin. Murni agar bisa unit test. */
    static boolean perintahBerbahaya(String text) {
        String cmd = namaPerintah(text);
        return cmd.equals("start") || cmd.equals("stop") || cmd.equals("restart")
                || cmd.equals("backup") || cmd.equals("restore")
                || cmd.equals("update") || cmd.equals("webvault")
                || cmd.equals("careset");
    }

    /** True bila perintah dijaga PIN saat PIN app aktif: perintah berbahaya
     *  plus pembaca data sensitif (/status /log /crashlog /ca /cabackup).
     *  Dipakai prompt PIN tombol & pembersih pesan ber-PIN. Murni. */
    static boolean perintahPerluPin(String text) {
        String cmd = namaPerintah(text);
        return perintahBerbahaya(text) || "crashlog".equals(cmd) || "log".equals(cmd)
                || "status".equals(cmd) || "ca".equals(cmd) || "cabackup".equals(cmd);
    }

    /** True bila pesan perintah yang lolos wajib dihapus dari riwayat chat
     *  (anti intip PIN): semua perintah yang dijaga PIN. Pesan ketik
     *  "/status 123456" atau "/ca 123456" membawa PIN asli sehingga wajib
     *  dihapus seperti perintah berbahaya lain. Upaya GAGAL tak dihapus
     *  (bukti brute-force) — pemanggil memutuskan via hasil otorisasi.
     *  Murni agar bisa unit test. */
    static boolean pesanPinWajibHapus(String text) {
        return perintahPerluPin(text);
    }

    /** PIN berbentuk tebakan (min. 4 karakter) di dalam argumen perintah,
     *  atau null bila argumen itu bukan tebakan PIN (kosong, pendek, versi,
     *  nama file, kata biasa). Murni: memakai {@link #pisahkanPin} yang sudah
     *  menyaring bentuk non-PIN agar salah ketik versi tak membakar lockout. */
    static String pinTebakanArgumen(String arg) {
        String pin = pisahkanPin(arg == null ? "" : arg)[1];
        return pin.length() >= 4 ? pin : null;
    }

    /** PIN yang diketik bersama perintah yang dijaga PIN (mis. "/stop 123456"
     *  atau "/stop PIN:ab12"), atau null. Dipakai jalur penolakan grup: PIN
     *  asli yang diketik di grup wajib dihapus dari riwayat dan tebakannya
     *  dihitung sebagai percobaan gagal. Murni agar bisa unit test. */
    static String pinTebakan(String text) {
        if (text == null || !perintahPerluPin(text)) {
            return null;
        }
        return pinTebakanArgumen(ambilArgumen(text));
    }

    /** Hitung tebakan PIN dari grup sebagai percobaan GAGAL, tanpa
     *  memverifikasinya: grup selalu ditolak sehingga verifikasi hanya akan
     *  menjadi oracle benar/salah bagi anggota grup.
     *
     *  Dulu kedua gerbang grup `return` sebelum PIN diurai, jadi
     *  `PinGate.catatHasil` tak pernah dipanggil: PIN bisa ditebak tanpa batas
     *  lewat grup (hasil tebakan tetap berlaku penuh di chat pribadi).
     *
     *  Hanya tebakan yang benar-benar berbentuk PIN (>= 4 karakter) yang
     *  dihitung — perintah tanpa PIN/argumen pendek sengaja tak dihitung agar
     *  anggota grup tak bisa mengunci PIN pemilik hanya dengan spam perintah
     *  (pelajaran yang sama dengan jalur chat pribadi). Saat PIN sudah
     *  terkunci, hitungan tidak ditambah agar lockout tak diperpanjang
     *  tanpa batas dari grup. */
    private static void hitungTebakanPinGrup(Context ctx, String tebakan) {
        if (tebakan == null || !pinPerangkatAktif(ctx)) {
            return;
        }
        long sekarang = System.currentTimeMillis();
        if (PinGate.sisaKunciMs(ctx, sekarang) > 0) {
            return;
        }
        PinGate.catatHasil(ctx, false, sekarang);
    }

    /** True bila tombol perintah ini tak bisa langsung jalan karena membawa
     *  data sensitif/aksi saat PIN aktif; tombol lalu meminta PIN lewat
     *  balasan chat (callback_data Telegram tak boleh membawa rahasia). */
    static boolean perluPinTombol(android.content.Context ctx, String data) {
        if (!perintahPerluPin(data)) {
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
     *  Best-effort: butuh hak hapus (di grup bot harus admin); keamanan tak
     *  bergantung padanya (lockout + PIN tetap jalan). Kembalikan true bila
     *  Telegram menjawab ok agar pemanggil bisa memperingatkan pemilik bila
     *  PIN lolos tetap nangkring di riwayat cloud. */
    static boolean hapusPesanPerintah(Context ctx, long chatId, int messageId) {
        // ID chat grup Telegram negatif (-100...): hanya 0 yang tak valid.
        // Guard lama (<= 0) membuat pesan ber-PIN di grup tak pernah dihapus
        // padahal justru di grup PIN paling rawan diintip semua anggota.
        if (chatId == 0 || messageId == 0) {
            return false;
        }
        HttpURLConnection c = null;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String token = Util.amanTrim(TgBackup.amanString(sp, TgBackup.KEY_TG_TOKEN, ""));
            if (token.isEmpty()) {
                return false;
            }
            byte[] body = ("chat_id=" + chatId + "&message_id=" + messageId)
                    .getBytes(StandardCharsets.UTF_8);
            c = TgBackup.bukaPostTelegram(ctx.getApplicationContext(),
                    TG_API + token + "/deleteMessage", body,
                    "application/x-www-form-urlencoded", 15000, 30000);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            TgBackup.tolakRedirectTelegram(c);
            int kode = c.getResponseCode();
            if (kode != 200) {
                return false;
            }
            try (InputStream in = c.getInputStream()) {
                // Kanonis via responsOk (toleran spasi "ok": true); contains mentah
                // gagal bila Telegram menyisip spasi dan menduplikasi logika ok-true.
                return responsOk(TgBackup.bacaResponsBatas(in));
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** Peringatkan sekali bila bot tak bisa menghapus pesan ber-PIN (tanpa hak
     *  hapus di chat): PIN yang lolos tetap bisa dibaca ulang dari riwayat
     *  cloud. Murni efek prefs + kirim; dipanggil dari poll thread sendiri. */
    static void peringatkanBilaTakBisaHapus(Context ctx, boolean berhasil, long idRuang) {
        if (berhasil) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                        Context.MODE_PRIVATE);
                if (!TgBackup.amanBoolean(sp, KEY_TG_HAPUS_OK, false)) {
                    sp.edit().putBoolean(KEY_TG_HAPUS_OK, true).apply();
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

    /** True bila uji hapus sudah tuntas (lolos sekali atau sudah diperingatkan).
     *  Murni agar bisa unit test: uji ulang berhenti setelah salah satunya tercatat. */
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

    /** Tutup spinner loading di tombol (best-effort, thread sendiri). */
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

    /** Balas HANYA ke chat pengirim perintah (bukan siaran ke semua chat
     *  config). Dipakai seluruh balasan bot: dulu /status, /log, /crashlog,
     *  /backup, /restore ... disiarkan sehingga grup ikut menerima path data,
     *  URL LAN/port, potongan log, dan nama berkas backup. */
    private static void balas(Context ctx, long idRuang, String text) {
        TgBackup.sendMessageKe(ctx, String.valueOf(idRuang), text);
    }

    /** Balasan penting (antrean prioritas) ke chat pengirim saja. */
    private static void balasPenting(Context ctx, long idRuang, String text) {
        TgBackup.sendPentingKe(ctx, String.valueOf(idRuang), text);
    }

    /** Tangani satu perintah; true bila perintah ber-PIN (/berbahaya + /status +
     *  /log + /crashlog) lolos otorisasi (PIN terverifikasi atau PIN mati) sehingga
     *  pemanggil boleh menghapus pesan dari riwayat. False berarti PIN
     *  gagal/hilang: pesan dipertahankan sebagai bukti forensik brute-force. */
    private static boolean handleCommand(Context ctx, String text, long idRuang) {
        boolean berbahayaTerotorisasi = false;
        if (text == null || !text.startsWith("/")) {
            return false;
        }
        // Keamanan grup: chat ID negatif = grup/supergrup yang semua anggotanya
        // bisa memerintah bot. Perintah berbahaya dari grup selalu ditolak —
        // ulangi dari chat pribadi dengan bot (tanpa pengecualian PIN grup).
        // Multi-akun: gerbang ikut ID chat (bukan config) agar admin pribadi
        // tetap bisa memerintah saat config juga memuat grup.
        if (idRuang < 0 && perintahBerbahaya(text)) {
            // PIN yang ikut diketik di grup: hitung sebagai tebakan gagal
            // (lockout) — dulu jalur ini return sebelum PinGate dihubungi.
            hitungTebakanPinGrup(ctx, pinTebakan(text));
            balas(ctx, idRuang, "Perintah berbahaya dari grup ditolak."
                    + " Ulangi dari chat pribadi dengan bot (pakai chat pribadi)."
                    + " Tebakan PIN dari grup dihitung sebagai percobaan gagal.");
            return false;
        }
        // Suffix @namabot grup: hanya milik bot ini yang dijalankan. Suffix
        // bot lain (atau ber-@ saat username bot belum diketahui) diabaikan
        // diam-diam agar tak membalas spam terusan bot lain.
        String dasar = namaPerintah(text);
        String verifikasi = namaPerintahUntukBot(text, namaBotTersimpan(ctx));
        if (text.contains("@") && !dasar.equals(verifikasi)) {
            return false;
        }
        String cmd = "/" + verifikasi;
        String arg = ambilArgumen(text);
        switch (cmd) {
            case "/start":
                if (authDangerous(ctx, arg, idRuang) == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
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
                break;
            case "/stop":
                if (authDangerous(ctx, arg, idRuang) == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
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
                break;
            case "/restart":
                if (authDangerous(ctx, arg, idRuang) == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
                // Cek lokal dulu agar pesan jujur: restart() hanya lapor
                // startService terkirim, bukan server sedang berjalan.
                if (!ServerService.running && !ServerService.isProcessAlive()) {
                    balas(ctx, idRuang, "Restart hanya bisa saat server berjalan.");
                    break;
                }
                try {
                    if (ServerService.restart(ctx)) {
                        balas(ctx, idRuang, "Perintah diterima: server restart...");
                    } else {
                        balas(ctx, idRuang, "Restart hanya bisa saat server berjalan.");
                    }
                } catch (Throwable t) {
                    balas(ctx, idRuang, "Restart hanya bisa saat server berjalan.");
                }
                break;
            case "/backup":
                if (authDangerous(ctx, arg, idRuang) == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
                runBeratDenganKunci(ctx, idRuang, () -> {
                    try {
                        balas(ctx, idRuang, TgBackup.backupNow(ctx));
                    } catch (Exception e) {
                        balas(ctx, idRuang, TgBackup.pesanGalatBackup(e));
                    }
                });
                break;
            case "/restore":
                String cleanRestore = authDangerous(ctx, arg, idRuang);
                if (cleanRestore == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
                if (isRestoreConfirm(cleanRestore)) {
                    runBeratDenganKunci(ctx, idRuang, () -> {
                        try {
                            balas(ctx, idRuang, "Mengunduh backup terakhir...");
                            balas(ctx, idRuang, doRestore(ctx)
                                    + " Tekan /start untuk menjalankan.");
                        } catch (Exception e) {
                            // Pesan ini disiarkan ke SEMUA chat (termasuk grup):
                            // detail mentah bisa memuat path internal atau header
                            // Location (URL portal dengan token). Redaksi dulu,
                            // detail teredaksi juga dicatat ke log app.
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
                break;
            case "/ca":
                // CA publik (tanpa kunci privat): butuh PIN bila PIN aktif,
                // dan dari grup selalu butuh PIN (tanpa pengecualian grup).
                if (pinPerangkatAktif(ctx) || chatResmiAdalahGrup(ctx)) {
                    if (authDangerous(ctx, arg, "ca", idRuang) == null) {
                        break;
                    }
                    berbahayaTerotorisasi = true;
                }
                runWithWakeLock(ctx, () -> {
                    try {
                        balas(ctx, idRuang, TgBackup.kirimCa(ctx));
                    } catch (Exception e) {
                        balas(ctx, idRuang, "Kirim CA gagal: " + e.getMessage());
                    }
                });
                break;
            case "/cabackup":
                // File publik (tanpa kunci privat): butuh PIN bila PIN aktif,
                // dan dari grup selalu butuh PIN (tanpa pengecualian grup).
                if (pinPerangkatAktif(ctx) || chatResmiAdalahGrup(ctx)) {
                    if (authDangerous(ctx, arg, "cabackup", idRuang) == null) {
                        break;
                    }
                    berbahayaTerotorisasi = true;
                }
                runWithWakeLock(ctx, () -> {
                    try {
                        balas(ctx, idRuang, TgBackup.backupCaKeStorage(ctx));
                    } catch (Exception e) {
                        balas(ctx, idRuang, "Backup CA gagal: " + e.getMessage());
                    }
                });
                break;
            case "/careset":
                if (authDangerous(ctx, arg, idRuang) == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
                // Kunci tugas berat (bukan runWithWakeLock polos): reset menghapus
                // file tls satu per satu sehingga backup konkuren bisa menangkap
                // setengah set (CA baru + key lama). Serial dengan backup/restore.
                runBeratDenganKunci(ctx, idRuang, () -> {
                    try {
                        balas(ctx, idRuang, TgBackup.resetSertifikat(ctx));
                    } catch (Exception e) {
                        balas(ctx, idRuang, "Reset sertifikat gagal: " + e.getMessage());
                    }
                });
                break;
            case "/status":
                // Status memuat folder data, versi binary/web-vault, waktu
                // backup, dan URL LAN: wajib PIN bila PIN aktif, sama seperti
                // /log dan /crashlog. Dari grup selalu butuh PIN (tanpa
                // pengecualian grup).
                if (pinPerangkatAktif(ctx) || chatResmiAdalahGrup(ctx)) {
                    if (authDangerous(ctx, arg, "status", idRuang) == null) {
                        break;
                    }
                    berbahayaTerotorisasi = true;
                }
                balas(ctx, idRuang, statusText(ctx));
                break;
            case "/log":
                // Log memuat path folder data, port, versi binary, URL LAN:
                // wajib PIN bila PIN aktif, sama seperti /crashlog. Dari grup
                // selalu butuh PIN (tanpa pengecualian grup).
                if (pinPerangkatAktif(ctx) || chatResmiAdalahGrup(ctx)) {
                    if (authDangerous(ctx, arg, "log", idRuang) == null) {
                        break;
                    }
                    berbahayaTerotorisasi = true;
                }
                balas(ctx, idRuang, tailLog());
                break;
            case "/uptime":
                long up = ServerService.uptimeMs();
                if (up <= 0) {
                    balas(ctx, idRuang, "Server sedang berhenti.");
                } else {
                    balas(ctx, idRuang, "Server jalan selama " + durationText(up) + ".");
                }
                break;
            case "/alive":
                // Ping /alive = gerbang TCP + sampai dua request HTTPS: bisa
                // beberapa detik. Dulu dijalankan inline di thread polling
                // tunggal sehingga anggota grup bisa menahan /stop atau
                // /restore dengan mengirim /alive berulang.
                if (!Util.jalankanBg(() -> balas(ctx, idRuang,
                        pesanAlive(ServerService.pingAlive(ctx))))) {
                    balas(ctx, idRuang, "Sistem sibuk, coba lagi sebentar.");
                }
                break;
            case "/update":
                String bersihUpdate = authDangerous(ctx, arg, idRuang);
                if (bersihUpdate == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
                if (!argumenVersiValid(bersihUpdate)) {
                    balas(ctx, idRuang, "Versi tidak valid."
                            + " Contoh: /update 1.32.0 atau /update terbaru");
                    break;
                }
                final boolean kunciUpdate = !bersihUpdate.isEmpty();
                final String versiUpdate = Updater.normalisasiPinVersi(bersihUpdate);
                runBeratDenganKunci(ctx, idRuang, () -> {
                    String pinLama = Updater.kuncianBinary(ctx);
                    try {
                        boolean was = ServerService.running || ServerService.isProcessAlive();
                        // Versi eksplisit ikut dikunci agar auto-update tak menaikkan lagi;
                        // "terbaru" melepas kuncian. Pin lama dikembalikan bila
                        // unduh gagal agar typo tak mengunci perangkat ke versi rusak.
                        if (kunciUpdate) {
                            if (!Updater.simpanKuncianBinary(ctx, bersihUpdate)) {
                                balas(ctx, idRuang, "Versi tidak valid."
                                        + " Contoh: /update 1.32.0 atau /update terbaru");
                                return;
                            }
                        }
                        // Shim dulu agar uji --version di dalam tryUpdate lolos di kernel lama.
                        String imbuhShim = "";
                        if (KernelCompat.legacyPerangkat()) {
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
                                    balas(ctx, idRuang, msg + " Restart otomatis...");
                                } else {
                                    balas(ctx, idRuang, msg + " Restart manual dari app"
                                            + " (batasan background Android).");
                                }
                            } else {
                                balas(ctx, idRuang, msg + " Tekan /start untuk memakai.");
                            }
                        } else {
                            balas(ctx, idRuang, msg);
                        }
                    } catch (Exception e) {
                        // Gagal unduh: kembalikan pin lama bila tadi mengunci versi
                        // konkret (bukan melepas ke terbaru) agar perangkat tak
                        // terkunci ke versi yang tak pernah terpasang.
                        if (kunciUpdate && versiUpdate != null) {
                            Updater.simpanKuncianBinary(ctx,
                                    pinLama == null ? "" : pinLama);
                        }
                        String pesan = e.getMessage();
                        String ramah = pesan != null && pesan.contains("Cek ")
                                ? pesan : Updater.pesanGalatUnduh("Unduh binary", e);
                        balas(ctx, idRuang, "Update gagal: " + ramah);
                    }
                });
                break;
            case "/webvault":
                String bersihWv = authDangerous(ctx, arg, idRuang);
                if (bersihWv == null) {
                    break;
                }
                berbahayaTerotorisasi = true;
                if (!argumenVersiValid(bersihWv)) {
                    balas(ctx, idRuang, "Versi tidak valid."
                            + " Contoh: /webvault 1.32.0 atau /webvault terbaru");
                    break;
                }
                final boolean kunciWv = !bersihWv.isEmpty();
                final String versiWv = Updater.normalisasiPinVersi(bersihWv);
                runBeratDenganKunci(ctx, idRuang, () -> {
                    String pinLamaWv = Updater.kuncianWebVault(ctx);
                    try {
                        boolean was = ServerService.running || ServerService.isProcessAlive();
                        // Pin lama dikembalikan bila unduh gagal (lihat /update).
                        if (kunciWv) {
                            if (!Updater.simpanKuncianWebVault(ctx, bersihWv)) {
                                balas(ctx, idRuang, "Versi tidak valid."
                                        + " Contoh: /webvault 1.32.0 atau /webvault terbaru");
                                return;
                            }
                        }
                        String msg = Updater.updateWebVaultVersi(ctx, versiWv);
                        if (webVaultBerubah(msg)) {
                            if (was) {
                                if (ServerService.restart(ctx)) {
                                    balas(ctx, idRuang, msg + " Restart otomatis...");
                                } else {
                                    balas(ctx, idRuang, msg + " Restart manual dari app"
                                            + " (batasan background Android).");
                                }
                            } else {
                                balas(ctx, idRuang, msg + " Tekan /start untuk memakai.");
                            }
                        } else {
                            balas(ctx, idRuang, msg);
                        }
                    } catch (Exception e) {
                        if (kunciWv && versiWv != null) {
                            Updater.simpanKuncianWebVault(ctx,
                                    pinLamaWv == null ? "" : pinLamaWv);
                        }
                        String pesan = e.getMessage();
                        String ramah = pesan != null && pesan.contains("Cek ")
                                ? pesan : Updater.pesanGalatUnduh("Unduh web-vault", e);
                        balas(ctx, idRuang, "Update web-vault gagal: " + ramah);
                    }
                });
                break;
            case "/versi":
                balas(ctx, idRuang, teksVersi(ctx));
                break;
            case "/crashlog":
                // Crash log memuat path folder data: wajib PIN bila PIN aktif.
                // Dari grup selalu butuh PIN (tanpa pengecualian grup).
                if (pinPerangkatAktif(ctx) || chatResmiAdalahGrup(ctx)) {
                    if (authDangerous(ctx, arg, "crashlog", idRuang) == null) {
                        break;
                    }
                    berbahayaTerotorisasi = true;
                }
                String crash = ServerService.crashLogText(ctx);
                if (crash == null || crash.trim().isEmpty()) {
                    balas(ctx, idRuang, "Belum ada crash log tersimpan.");
                } else {
                    balas(ctx, idRuang, potongEkor(crash, 3500));
                }
                break;
            case "/help":
                TgBackup.sendMessageKbKe(ctx, String.valueOf(idRuang), teksMenu(ctx, MENU_UTAMA),
                        keyboardMenu(MENU_UTAMA));
                break;
            default:
                balas(ctx, idRuang, "Perintah tidak dikenal. Ketik /help");
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
        String a = Util.rendahAscii(arg.trim());
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
                ? Util.formatTanggal("yyyy-MM-dd HH:mm", new Date(last))
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
        // Nama unik per operasi: dua /restore beruntun (atau /restore bot
        // berbarengan dengan restore dari Pengaturan) tak boleh berebut satu
        // file tetap. Hasil dekripsi memakai awalan plaintext (vwtg-plain-)
        // supaya dilarang dibagikan FileShareProvider, dan sisa proses yang
        // mati sebelum finally disapu di awal operasi berikutnya — dulu nama
        // tetap membuat plaintext DB mengendap dan bisa ditimpa operasi lain.
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
            // Kunci baru dipegang di sini (bukan sejak awal): unduhan jaringan
            // ber-menit-menit tak menyentuh folder data sehingga backup lain
            // boleh jalan bersamaan; saling kecualian hanya untuk eksekusi
            // restore yang menghentikan server dan menimpa database.
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
      *  kembalikan argumen bersih (tanpa PIN), atau null (pesan sudah dikirim).
      *  idRuang = ID chat pengirim; negatif berarti grup (PIN ditolak). */
    static String authDangerous(Context ctx, String arg, long idRuang) {
        // Tanpa nama perintah, anggap berbahaya (fail-closed): varian 3-arg
        // hanya dipakai perintah berbahaya sehingga PIN mati pun tetap ditolak.
        return authDangerous(ctx, arg, null, idRuang);
    }

    /** Varian ber-perintah: cmd menentukan izin saat PIN mati.
      *  Bila PIN mati dan chat pribadi: hanya perintah aman (non-berbahaya:
      *  help/versi/alive/uptime/status/log/crashlog/ca/cabackup) yang lolos
      *  langsung; perintah berbahaya (start/stop/restart/backup/restore/
      *  update/webvault/careset) tetap ditolak dan diminta aktifkan PIN dulu.
      *  Dari grup (ID negatif) perintah ber-PIN selalu ditolak. cmd null =
      *  perlakukan sebagai berbahaya. */
    static String authDangerous(Context ctx, String arg, String cmd, long idRuang) {
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
        boolean berbahaya = cmd == null || cmd.trim().isEmpty()
                || perintahBerbahaya(cmd);
        if (idRuang < 0) {
            // PIN yang diketik di grup terbaca semua anggota (hapus pesan cuma
            // best-effort) dan anggota mana pun bisa memicu aksi atau salah PIN
            // sampai pemilik terkunci (PinGate dipakai bersama app). Perintah
            // ber-PIN wajib dari chat pribadi; grup tetap menerima notifikasi,
            // backup, dan perintah yang tak dijaga PIN. Deteksi per chat (bukan
            // seluruh config) agar chat pribadi tetap bisa memerintah saat
            // config juga memuat grup.
            // Tebakan PIN tetap dihitung gagal: dulu jalur ini return sebelum
            // PinGate dihubungi sehingga tebakan dari grup tak terbatas.
            hitungTebakanPinGrup(ctx, pinTebakanArgumen(t));
            balas(ctx, idRuang, "Perintah ber-PIN hanya bisa dari chat pribadi"
                    + " bot. PIN yang diketik di grup terbaca anggota lain.");
            return null;
        }
        if (!need) {
            // PIN mati + chat pribadi: aman lolos, berbahaya tetap butuh PIN.
            if (berbahaya) {
                balas(ctx, idRuang, "Perintah ini butuh PIN app."
                        + " Aktifkan PIN dulu di Pengaturan, lalu ulangi dengan"
                        + " PIN (mis. /stop 123456 atau /stop PIN:123456).");
                return null;
            }
            return t;
        }
        // Wall-clock agar reboot tak mereset lockout PIN bot.
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
        // PIN valid min 4 karakter: tolak dini tanpa PBKDF2 agar spam bot tak DoS CPU.
        // Tebakan pendek tak dihitung gagal: PIN min 4 sehingga tak mungkin benar —
        // menghitungnya hanya membuka lockout-DoS (orang iseng mengunci pemilik
        // sah dari jauh dengan 5x spam 1 huruf); tanpa PIN sama sekali pun tak dihitung.
        if (pin.length() < 4) {
            balas(ctx, idRuang, "Perintah ini butuh PIN app"
                    + " (mis. /stop 123456 atau /stop PIN:123456; bila PIN ber-spasi: /stop PIN:\"kunci saya\";"
                    + " PIN huruf wajib bentuk PIN:ab12).");
            return null;
        }
        boolean cocok = PinCrypto.verify(hash, pin);
        if (cocok) {
            // PIN bisa diganti tepat saat PBKDF2 jalan: nilai ulang lawan
            // hash terbaru agar PIN lama tak lolos sekali. Hanya bayar 1x
            // PBKDF2 ekstra bila hash memang berganti (sangat jarang).
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
                    // Baca ulang: PIN bisa diganti layar lain saat verifikasi
                    // jalan; jangan timpa hash baru dengan hasil PIN lama.
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
        // Flag s (DOTALL): '.' menelan newline sehingga PIN/argumen
        // multi-baris tak terpotong di baris pertama.
        java.util.regex.Matcher m = POLA_PIN_MARKER.matcher(t);
        // Kemunculan TERAKHIR yang dipakai: tempelan "PIN:lama ... PIN:baru"
        // memakai yang baru; kemunculan tunggal tak berubah perilaku.
        // (Greedy (.+) selalu menelan sampai akhir sehingga find() pertama
        //  menutup peluang kemunculan berikut terbaca.)
        int awalMatch = -1;
        int akhirMarker = -1;
        while (m.find()) {
            awalMatch = m.start();
            akhirMarker = m.end();
        }
        if (awalMatch >= 0) {
            String mentah = t.substring(akhirMarker).trim();
            // Bentuk eksplisit: kutip mengapit diambil tepat (sisa sesudah
            // kutip tutup kembali jadi argumen), tanpa kutip menelan sisa
            // baris agar PIN ber-spasi tetap bisa dipakai via bot.
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
        // Pemisah whitespace terakhir (spasi/tab/newline): lastIndexOf(' ')
        // buta tab sehingga "YA<TAB>123456" tak pernah dikenali PIN-nya
        // (sekelas bug ambilArgumen). Murni.
        int i = -1;
        for (int k = t.length() - 1; k >= 0; k--) {
            if (Character.isWhitespace(t.charAt(k))) {
                i = k;
                break;
            }
        }
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
            if (Util.numerik4Plus(t)) {
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
        // Nomor versi ("1.32.0", "v1.32.0") bukan PIN: tanpa ini argumen versi
        // di posisi akhir (mis. "/update terbaru 1.32.0" yang lupa PIN) dimakan
        // sebagai PIN salah dan membakar 1x lockout; lima kali salah ketik
        // versi = PIN terkunci sia-sia. PIN numerik murni ("123456") tetap
        // dimakan di bawah; PIN berbentuk versi wajib eksplisit "PIN:...".
        if (Updater.normalisasiPinVersi(kandidat) != null) {
            return new String[]{t, ""};
        }
        // Kandidat mirip file/jalur/versi ("file.zip", "a/b", "1.32.x") bukan
        // PIN implisit: salah ketik versi/nama file tak boleh membakar lockout;
        // PIN bertitik/garis-miring wajib bentuk eksplisit PIN:... .
        if (kandidat.contains(".") || kandidat.contains("/") || kandidat.contains("\\")) {
            return new String[]{t, ""};
        }
        if (Util.pinKataValid(kandidat)) {
            // Sisa dinormalisasi seperti cabang eksplisit agar "YA <TAB>" dan
            // newline cocok isRestoreConfirm ("ya"), bukan gagal banding.
            return new String[]{rapikanSpasi(t.substring(0, i).trim()), kandidat};
        }
        return new String[]{t, ""};
    }

    /** Jalankan tugas berat bot saling-menunggu; tolak halus bila sibuk. */
    private static void runBeratDenganKunci(Context ctx, long idRuang, Runnable task) {
        if (!TUGAS_BERAT.compareAndSet(false, true)) {
            balas(ctx, idRuang, "Tugas lain masih berjalan, coba lagi sebentar.");
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
            balas(ctx, idRuang, "Sistem sibuk, coba lagi sebentar.");
        }
    }

    /** Batas waktu partial wake lock tugas berat bot (juga pengaman thread
     *  macet): cukup untuk unduh 35 MB di koneksi lambat, tetap berbatas. */
    static final long WAKELOCK_TUGAS_BERAT_MS = 20L * 60L * 1000L;

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
                // Batas ini hanya pengaman thread macet (kunci dilepas di
                // finally). 3 menit dulu TERLALU PENDEK: /update 20 MB dan
                // /webvault 35 MB di STB ber-kuota lambat (~60 KB/s) butuh
                // >9 menit, sehingga CPU tidur di tengah unduhan dan transfer
                // gagal. 20 menit menutup 35 MB pada ~30 KB/s.
                wl.acquire(WAKELOCK_TUGAS_BERAT_MS);
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

    /** Lompatan jam > 30 hari dianggap RTC salah, bukan waktu berjalan:
     *  mencatat/mempercayainya membuat bot bisu sampai tahun itu tiba. */
    static final long BATAS_LOMPATAN_JAM_MS = 30L * 24 * 3600 * 1000;

    /** True bila tanda air jam boleh maju ke {@code kini}. Murni agar bisa diuji.
     *  Nilai era 1970 (RTC belum diset) tak pernah jadi patokan; lompatan > 30
     *  hari (mis. RTC sempat menyetel 2030 lalu NTP membetulkan) ditolak supaya
     *  tanda air tak berubah jadi "tahun depan" dan membisukan semua perintah. */
    static boolean wallBolehMaju(long maksLama, long kini) {
        if (kini < 1_000_000_000_000L) {
            return false;
        }
        return kini > maksLama
                && (maksLama <= 0 || kini - maksLama <= BATAS_LOMPATAN_JAM_MS);
    }

    /** True bila jam mundur jauh dari yang pernah terlihat (rollback/NTP) — fail-closed. */
    static boolean jamMundur(long kini) {
        long maks = wallMaksTelegram;
        return maks > 0 && kini < maks - TOLERANSI_JAM_MS;
    }

    /** Catat wall-clock monoton naik untuk deteksi rollback berikutnya. */
    static void catatWall(long kini) {
        synchronized (KUNCI_WALL) {
            if (wallBolehMaju(wallMaksTelegram, kini)) {
                wallMaksTelegram = kini;
            }
        }
    }

    /** Muat maks wall-clock dari prefs (deteksi rollback lintas restart). */
    static void muatWallMaks(Context ctx) {
        // Sekali per proses: hanya simpanWallMaks/muatWallMaks yang tulis/baca
        // kunci ini di satu proses, jadi muat ulang tiap poll tak ada gunanya.
        if (wallMaksTersimpan != Long.MIN_VALUE) {
            return;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            long s = TgBackup.amanLong(sp, KEY_TG_WALL_MAKS, 0);
            // Tanda air racun (jam pernah melompat jauh ke depan) dibuang agar
            // bot tak bisu sampai tahun itu tiba. Saat jam masih 1970, nilai
            // sah 2026 pun ikut dibuang — catatWall akan memasang patokan baru
            // begitu jam wajar, tanpa merugikan fungsi apa pun.
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

    /** Simpan maks wall-clock ke prefs bila berubah (poll tiap 20 dtk). */
    static void simpanWallMaks(Context ctx) {
        try {
            long kini = wallMaksTelegram;
            if (kini == wallMaksTersimpan) {
                return;
            }
            SharedPreferences sp = ctx.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            if (wallMaksTersimpan == Long.MIN_VALUE) {
                // Belum sinkron di proses ini: baca sekali, bukan tiap poll.
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
        String mentah = ServerService.tailLog(30);
        String log = LogActivity.butuhSamaran(mentah) ? LogActivity.samarkanLog(mentah) : mentah;
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
        // StringBuilder eksplisit: concat "+" merakit String per operan di
        // tiap tick 1 dtk layar awal + tiap balasan status/uptime bot.
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

    /** Ringkasan status untuk dibalas ke Telegram. */
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
        // Alarm polling membawa rahasia anti-spoof selaras AlarmReceiver agar
        // TgBotReceiver bisa menolak explicit-intent palsu dari app lain.
        Intent i = new Intent(ctx, TgBotReceiver.class).setAction(ACTION_POLL)
                .putExtra(AlarmReceiver.EXTRA_RAHASIA, TgBackup.rahasiaAlarm(ctx));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, REQ_POLL, i, flags);
    }

    /** Ringkas body galat Telegram ("ok":false,"error_code":401,"description":
     *  "Unauthorized") jadi "HTTP 401: Unauthorized". Murni tanpa org.json agar
     *  bisa diuji JVM. Body mentah tak pernah dicatat: galat dari proxy/HTML bisa
     *  memuat URL bot<token> sehingga token bocor ke log/Telegram /log. */
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

    /** Catat galat polling sekali per jenis (bukan tiap 20 dtk) agar telemetri
     *  log STB tak banjir saat token dicabut / bot dipakai instans lain. */
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
                // Dulu body galat dibuang diam-diam: token dicabut (401), bot
                // dipakai instans lain (409 Conflict), atau kena limit (429)
                // membuat bot berhenti menjawab tanpa satu pun petunjuk.
                catatGalatPollSekali(ringkasGalatTelegram(isi, code));
                return null;
            }
            return isi;
        } catch (Exception e) {
            // Kelas galat saja: pesan exception (mis. MalformedURLException)
            // memuat URL bertoken sehingga tak boleh masuk log.
            catatGalatPollSekali(e.getClass().getSimpleName());
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
