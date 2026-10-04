package com.tasirin.vaultwardenhost;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


/** Halaman log server realtime layar penuh (di-port dari LogActivity download manager). */
public class LogActivity extends Activity {

    private static final int REQ_WRITE = 1002;
    private boolean simpanUlangSetelahIzin = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logView;
    private ScrollView logScroll;
    private TextView logCount;
    private String logSearch = "";
    private boolean logAutoScroll = false;
    /** Polling log tiap detik; dihentikan di onPause agar tak sedot CPU/baterai
     *  saat layar log tak terlihat (Home), jalan lagi di onResume. */
    private final Runnable logTick = new Runnable() {
        @Override
        public void run() {
            if (isDestroyed() || isFinishing()) {
                return;
            }
            refreshLog();
            ui.postDelayed(this, 1000);
        }
    };
    /** Tunda refresh pencarian 300 ms: tiap ketikan menyalin buffer 300 KB
     *  di UI thread sehingga mengetik di STB 1 GB tersendat tanpa jeda ini. */
    private final Runnable refreshCari = new Runnable() {
        @Override
        public void run() {
            refreshLog();
        }
    };
    private String lastLogKey = null;
    private int lastLogLen = 0;
    private long lastLogVer = -1;
    private int lineCount = 0;
    /** Warna sorot pencarian + baris galat dari tema (sinkron terang/gelap). */
    private int warnaSorotCari = 0xFFFFE082;
    private int warnaGalat = 0xFFFF0000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Privasi: nonaktifkan screenshot + preview recents dikosongkan (sama dengan MainActivity).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(savedInstanceState);
        // Sisa salinan clipboard yang penghapus 60 dtk-nya tak sempat jalan
        // karena proses mati dibersihkan (atau dipasang ulang) di sini.
        bersihkanClipBasiJikaAda();
        setContentView(R.layout.activity_log);

        logView = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        logCount = findViewById(R.id.logCount);
        EditText searchInput = findViewById(R.id.logSearch);
        CheckBox autoScrollCheck = findViewById(R.id.logAutoScroll);
        Button backBtn = findViewById(R.id.logBack);
        Button saveBtn = findViewById(R.id.logSave);
        Button shareBtn = findViewById(R.id.logShare);
        Button copyBtn = findViewById(R.id.logCopy);
        Button clearBtn = findViewById(R.id.logClear);
        Button crashBtn = findViewById(R.id.logCrash);

        warnaSorotCari = warnaTema(R.color.search_highlight);
        warnaGalat = warnaTema(R.color.log_error);

        autoScrollCheck.setChecked(logAutoScroll);
        autoScrollCheck.setOnCheckedChangeListener((b, checked) -> logAutoScroll = checked);
        backBtn.setOnClickListener(v -> finish());
        saveBtn.setOnClickListener(v -> exportLogTxt());
        shareBtn.setOnClickListener(v -> shareLog());
        copyBtn.setOnClickListener(v -> copyLog());
        crashBtn.setOnClickListener(v -> showCrashDialog());
        clearBtn.setOnClickListener(v -> {
            ServerService.clearLog();
            lastLogKey = null;
            lastLogVer = -1;
            refreshLog();
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                logSearch = s == null ? "" : s.toString();
                ui.removeCallbacks(refreshCari);
                ui.postDelayed(refreshCari, 300);
            }
        });

        refreshLog();
        ui.postDelayed(logTick, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Sesi melewati grace PIN: tutup log dan kembalikan ke MainActivity
        // (yang meminta PIN). Tanpa ini log penuh terlihat tanpa kunci.
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            if (TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false)
                    && !MainActivity.pinBaruSajaDibuka()
                    && !SettingsActivity.pinBaruSajaDibuka()) {
                finish();
                return;
            }
        } catch (Exception ignored) {
        }
        refreshLog();
        ui.removeCallbacks(logTick);
        ui.postDelayed(logTick, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(logTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }

    private void refreshLog() {
        // Salinan 300 KB hanya dibuat bila panjang berubah (hampir selalu sama
        // antar refresh tiap detik); sebelumnya menyalin dulu baru membandingkan.
        long ver = ServerService.logVersion();
        int len = ServerService.logLength();
        if (len < lastLogLen || ver < lastLogVer) {
            // Log terpotong (trim buffer) - hitung ulang dari awal di renderLog.
            // Jangan set lineCount = 0 di sini karena renderLog akan menghitung ulang.
            lastLogLen = 0;
            lastLogVer = ver;
        }
        // Konten log append-only (trim hanya memendekkan) - panjang cukup sebagai
        // penanda perubahan, tanpa perlu menyalin/membandingkan teks 300 KB tiap detik.
        String key = ver + "\u0000" + len + "\u0000" + logSearch;
        if (key.equals(lastLogKey)) {
            return;
        }
        lastLogKey = key;
        lastLogVer = ver;
        String text;
        synchronized (ServerService.logBuffer) {
            text = ServerService.logBuffer.toString();
        }
        // Hitung ulang lineCount dari awal teks saat ini (handle trim dengan benar)
        lineCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineCount++;
            }
        }
        lastLogLen = len;
        logCount.setText(getString(R.string.log_lines, lineCount));
        int prevScroll = logScroll.getScrollY();
        logView.setText(highlightLog(text, logSearch));
        if (logAutoScroll) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        } else {
            logScroll.post(() -> {
                View child = logScroll.getChildAt(0);
                int max = (child == null ? 0 : child.getHeight()) - logScroll.getHeight();
                logScroll.scrollTo(0, Math.max(0, Math.min(prevScroll, max)));
            });
        }
    }

    // getColor(int) lawas sengaja agar satu jalur kode untuk API 21-32.
    @SuppressWarnings("deprecation")
    private int warnaTema(int id) {
        return getResources().getColor(id);
    }

    /** Sorot baris GAGAL/ERROR/FAILED dan kata kunci pencarian (warna tema).
     *  Tanpa toLowerCase()/substring() per baris: pencocokan case-insensitive
     *  via regionMatches agar tidak ada salinan besar tiap refresh. */
    private CharSequence highlightLog(String text, String q) {
        // Buffer bisa 300 KB: membangun ribuan span tiap detik bikin STB patah.
        // Di atas 150 KB tampilkan polos (pencarian teks browser tetap jalan).
        if (text.length() > 150_000) {
            return text;
        }
        if ((q == null || q.isEmpty()) && rangeIndexOf(text, "GAGAL", 0, text.length()) < 0
                && rangeIndexOf(text, "ERROR", 0, text.length()) < 0
                && rangeIndexOf(text, "FAILED", 0, text.length()) < 0) {
            return text;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        if (q != null && !q.isEmpty()) {
            int from = 0;
            while (from + q.length() <= text.length()) {
                int idx = rangeIndexOf(text, q, from, text.length());
                if (idx < 0) {
                    break;
                }
                sb.setSpan(new BackgroundColorSpan(warnaSorotCari),
                        idx, idx + q.length(),
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                from = idx + q.length();
            }
        }
        int lineStart = 0;
        int total = text.length();
        while (lineStart <= total) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = total;
            }
            if (rangeIndexOf(text, "GAGAL", lineStart, lineEnd) >= 0
                    || rangeIndexOf(text, "ERROR", lineStart, lineEnd) >= 0
                    || rangeIndexOf(text, "FAILED", lineStart, lineEnd) >= 0) {
                sb.setSpan(new ForegroundColorSpan(warnaGalat),
                        lineStart, lineEnd,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (lineEnd == total) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        return sb;
    }

    /** indexOf case-insensitive dalam rentang [from, end) tanpa alokasi baru. */
    private static int rangeIndexOf(String text, String keyword, int from, int end) {
        int max = end - keyword.length();
        for (int i = Math.max(from, 0); i <= max; i++) {
            if (text.regionMatches(true, i, keyword, 0, keyword.length())) {
                return i;
            }
        }
        return -1;
    }

    /** Tampilkan dialog berisi crash log terakhir (bisa disalin). */
    private void showCrashDialog() {
        // Baca file di worker: storage STB lambat bisa ANR bila dibaca di UI thread.
        new Thread(() -> {
            // Samarkan ulang di sini (bukan andalkan isi file): pola samaran
            // bisa bertambah setelah file ditulis (mis. chat_id JSON), dan
            // jalur tampil/salin ini tak lewat shareLog/copyLog/export.
            final String crash = samarkanLog(ServerService.crashLogText(LogActivity.this));
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (crash == null || crash.trim().isEmpty()) {
                    toast("Belum ada crash log.");
                    return;
                }
                tampilDialogCrash(crash);
            });
        }, "vw-crashlog").start();
    }

    /** Bangun dialog crash-log di UI thread (dipanggil setelah baca worker selesai). */
    private void tampilDialogCrash(final String crash) {
        float d = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setText(crash);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int pad = (int) (12 * d);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Crash Log")
                .setView(sv)
                .setPositiveButton("Salin", (dlg, w) -> {
                    salinClipboardBersihOtomatis("vaultwarden-crash", crash);
                    toast("Crash log disalin ke clipboard.");
                })
                .setNegativeButton(getString(R.string.close), null)
                .show();
    }

    private static final java.util.regex.Pattern POLA_TOKEN_URL =
            java.util.regex.Pattern.compile("(?i)(token=)[^&\\s]+");
    private static final java.util.regex.Pattern POLA_TOKEN_JSON =
            java.util.regex.Pattern.compile("(?i)(\\\"(?:admin_token|tg_token|tg_chat|tg_pass|pin_hash|token)\\\"\\s*:\\s*\\\")[^\\\"]*\\\"");
    private static final java.util.regex.Pattern POLA_TOKEN_JSON_SQ =
            java.util.regex.Pattern.compile("(?i)('(?:admin_token|tg_token|tg_chat|tg_pass|pin_hash|token)'\\s*:\\s*')[^']*'");
    private static final java.util.regex.Pattern POLA_CHAT_ID =
            java.util.regex.Pattern.compile("(?i)(chat_id\\s*[\"']?\\s*[:=]\\s*[\"']?)[^&\\s,\"'}\\]]+");
    private static final java.util.regex.Pattern POLA_BOT_TOKEN =
            java.util.regex.Pattern.compile("bot\\d+:[A-Za-z0-9_-]{6,}");
    /** Token bot mentah tanpa awalan "bot" (mis. "123456:AAEc..." di pesan galat).
     *  Minimal 6 digit + 10 karakter: jam "12:30" (2 digit) dan port "8088"
     *  (4 digit) tetap aman karena awalan digit tak memenuhi syarat. */
    private static final java.util.regex.Pattern POLA_TOKEN_MENTAH =
            java.util.regex.Pattern.compile("(?<!\\w)\\d{6,12}:[A-Za-z0-9_-]{10,}");
    private static final java.util.regex.Pattern POLA_BOT_URL =
            java.util.regex.Pattern.compile("(?i)(api\\.telegram\\.org/bot)[A-Za-z0-9_-]+:[A-Za-z0-9_-]{6,}");
    private static final java.util.regex.Pattern POLA_ADMIN_ENV =
            java.util.regex.Pattern.compile("(?i)(ADMIN_TOKEN\\s*=\\s*)[^\\s]+");
    private static final java.util.regex.Pattern POLA_BEARER =
            java.util.regex.Pattern.compile("(?i)(Authorization\\s*:\\s*Bearer\\s+)\\S+");
    /** Kredensial bentuk mentah key=value (bukan JSON): tg_token=..., tg_chat=...,
     *  tg_pass=..., pin_hash=..., admin_token:... — tak ikut POLA_TOKEN_JSON
     *  (butuh tanda kutip) maupun POLA_CHAT_ID (butuh kata chat_id) sehingga
     *  disamarkan di sini. */
    private static final java.util.regex.Pattern POLA_KREDENSIAL_NILAI =
            java.util.regex.Pattern.compile("(?i)((?:tg_token|tg_chat|pin_hash|admin_token)\\s*[:=]\\s*)([^\\s&,;\"']+)");
    /** Nilai DOMAIN (https://IP:port) ikut disamarkan agar IP/port LAN
     *  tak bocor via bagi log. */
    private static final java.util.regex.Pattern POLA_DOMAIN =
            java.util.regex.Pattern.compile("(?i)((?:\\bDOMAIN\\b)\\s*[:=]\\s*)([^\\s&,;\"']+)");
    /** Kata sandi backup boleh ber-spasi sehingga nilainya disamarkan sampai
     *  akhir baris (bukan sampai spasi pertama) agar sisa frasa tak bocor.
     *  Kredensial lain valid tanpa spasi sehingga pola kata-tunggal di atas
     *  cukup dan tak menelan kata log sesudahnya. */
    private static final java.util.regex.Pattern POLA_SANDI_SPASI =
            java.util.regex.Pattern.compile("(?i)((?:tg_pass)\\s*[:=]\\s*)([^\n&]+)");

    static String samarkanLog(String log) {
        if (log == null) {
            return "";
        }
        // Samarkan token, chat_id, dan token bot agar tak bocor via bagi/clipboard.
        // Pola dikompilasi sekali (polling log tiap detik di STB 1 GB).
        String r = log;
        r = POLA_TOKEN_URL.matcher(r).replaceAll("$1***");
        // Format JSON ("tg_token": "abc") yang muncul di dump config juga disamarkan.
        r = POLA_TOKEN_JSON.matcher(r).replaceAll("$1***\"");
        r = POLA_TOKEN_JSON_SQ.matcher(r).replaceAll("$1***'");
        r = POLA_CHAT_ID.matcher(r).replaceAll("$1***");
        r = POLA_BOT_TOKEN.matcher(r).replaceAll("bot***:***");
        // Token mentah selalu disamarkan: pola menuntut 6-12 digit + 10+
        // karakter sehingga jam "12:30" dan teks biasa tak ikut rusak,
        // sementara token bocor di log generik tetap tertutup.
        r = POLA_TOKEN_MENTAH.matcher(r).replaceAll("***:***");
        // Token bot mentah tanpa awalan "bot" (mis. URL api.telegram.org/.../123:ABC
        // di pesan galat) + secret admin via env/query/Bearer.
        r = POLA_BOT_URL.matcher(r).replaceAll("$1***:***");
        r = POLA_ADMIN_ENV.matcher(r).replaceAll("$1***");
        r = POLA_BEARER.matcher(r).replaceAll("$1***");
        r = POLA_SANDI_SPASI.matcher(r).replaceAll("$1***");
        r = POLA_KREDENSIAL_NILAI.matcher(r).replaceAll("$1***");
        r = POLA_DOMAIN.matcher(r).replaceAll("$1***");
        return r;
    }

    private void shareLog() {
        String log;
        synchronized (ServerService.logBuffer) {
            log = samarkanLog(ServerService.logBuffer.toString());
        }
        if (log.isEmpty()) {
            toast("Log masih kosong.");
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Tasirin Vaultwarden Host - Log");
        send.putExtra(Intent.EXTRA_TEXT, log);
        try {
            startActivity(Intent.createChooser(send, "Bagikan log"));
        } catch (Exception e) {
            toast("Gagal membagikan log: " + e.getMessage());
        }
    }

    /** Kunci penanda salinan clipboard agar penghapus 30 dtk selamat dari mati proses. */
    static final String KEY_CLIP_HASH = "clip_hash";
    static final String KEY_CLIP_KEDALUWARSA = "clip_kedaluwarsa";
    static final long CLIP_BERSIH_MS = 30_000;

    /** Sidik SHA-256 isi clipboard (heks). Murni agar bisa unit test. */
    static String sidikClip(String s) {
        if (s == null) {
            return "";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte x : h) {
                sb.append("0123456789abcdef".charAt((x >> 4) & 15));
                sb.append("0123456789abcdef".charAt(x & 15));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Salin ke clipboard + bersih otomatis 30 dtk yang tahan mati proses.
     *  Satu pintu untuk Log/Settings/Main agar token rahasia tak mengendap
     *  bila proses mati sebelum penghapus jalan (sidik + kedaluwarsa di prefs,
     *  dipasang ulang saat activity dibuka lagi). Banding via sidik agar
     *  lambda tak menahan plaintext di heap. */
    public static void salinBersihOtomatis(Context ctx, String label, String isi) {
        if (ctx == null || isi == null) {
            return;
        }
        final String etiket = label == null ? "vaultwarden" : label;
        final Context app;
        try {
            Context a = ctx.getApplicationContext();
            app = a != null ? a : ctx;
        } catch (Exception e) {
            return;
        }
        final ClipboardManager cm;
        try {
            cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        } catch (Exception e) {
            return;
        }
        if (cm == null) {
            return;
        }
        try {
            cm.setPrimaryClip(ClipData.newPlainText(etiket, isi));
        } catch (Exception e) {
            return;
        }
        final String sidik = sidikClip(isi);
        final long kedaluwarsa;
        try {
            kedaluwarsa = android.os.SystemClock.elapsedRealtime() + CLIP_BERSIH_MS;
        } catch (Exception e) {
            return;
        }
        try {
            app.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_CLIP_HASH, sidik)
                    .putLong(KEY_CLIP_KEDALUWARSA, kedaluwarsa).apply();
        } catch (Exception ignored) {
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                bersihkanBilaIsiKita(app, etiket);
            } catch (Exception ignored) {
            } finally {
                hapusPenandaClipJikaCocok(app, sidik);
            }
        }, CLIP_BERSIH_MS);
    }

    /** Pasang ulang sisa timer clipboard bila masih dalam jendela, atau langsung
     *  bersihkan sisa salinan basi bila kedaluwarsa sudah lewat (tahan mati proses).
     *  Dipanggil tiap activity yang memakai clipboard dibuka. */
    public static void bersihkanClipBasiJikaAda(Context ctx) {
        if (ctx == null) {
            return;
        }
        final Context app;
        try {
            Context a = ctx.getApplicationContext();
            app = a != null ? a : ctx;
        } catch (Exception e) {
            return;
        }
        final String sidik;
        final long kedaluwarsa;
        try {
            android.content.SharedPreferences sp =
                    app.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
            sidik = sp.getString(KEY_CLIP_HASH, "");
            kedaluwarsa = sp.getLong(KEY_CLIP_KEDALUWARSA, 0);
        } catch (Exception e) {
            return;
        }
        if (sidik == null || sidik.isEmpty() || kedaluwarsa <= 0) {
            return;
        }
        long sisa = sisaClipMs(kedaluwarsa);
        if (sisa > 0) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                bersihkanBilaIsiKita(app, "vaultwarden");
            }, sisa);
            return;
        }
        bersihkanBilaIsiKita(app, "vaultwarden");
    }

    /** Bersihkan clipboard hanya bila isinya masih salinan kita (cocok sidik). */
    private static void bersihkanBilaIsiKita(Context ctx, String label) {
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
            String sidik = sp.getString(KEY_CLIP_HASH, "");
            if (sidik == null || sidik.isEmpty()) {
                return;
            }
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return;
            }
            android.content.ClipData cur = cm.getPrimaryClip();
            if (cur != null && cur.getItemCount() > 0 && cur.getItemAt(0) != null
                    && sidik.equals(sidikClip(String.valueOf(cur.getItemAt(0).getText())))) {
                cm.setPrimaryClip(ClipData.newPlainText(label == null ? "vaultwarden" : label, ""));
            }
        } catch (Exception ignored) {
        } finally {
            try {
                android.content.SharedPreferences sp =
                        ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
                String kini = sp.getString(KEY_CLIP_HASH, "");
                hapusPenandaClipJikaCocok(ctx, kini);
            } catch (Exception ignored2) {
            }
        }
    }

    /** Hapus penanda clipboard hanya bila masih milik sidik ini: timer basi
     *  tak boleh menghapus penanda salinan baru dari layar lain. */
    private static void hapusPenandaClipJikaCocok(Context ctx, String sidikKita) {
        if (ctx == null || sidikKita == null || sidikKita.isEmpty()) {
            return;
        }
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
            String kini = sp.getString(KEY_CLIP_HASH, "");
            if (!sidikKita.equals(kini)) {
                return;
            }
            sp.edit().remove(KEY_CLIP_HASH).remove(KEY_CLIP_KEDALUWARSA).apply();
        } catch (Exception ignored) {
        }
    }

    private void salinClipboardBersihOtomatis(String label, String isi) {
        salinBersihOtomatis(this, label, isi);
    }

    /** Pasang ulang sisa timer bila masih dalam jendela, atau langsung bersihkan
     *  sisa salinan basi bila kedaluwarsa sudah lewat saat dibuka kembali. */
    private void bersihkanClipBasiJikaAda() {
        bersihkanClipBasiJikaAda(this);
    }

    /** Sisa timer clipboard (ms); 0 bila kedaluwarsa. Nilai lawas era wall-clock
     *  (>1e11) dimigrasi dengan jam dinding, nilai baru memakai elapsedRealtime
     *  agar utak-atik tanggal tak memperpanjang sensitif log di clipboard. Murni. */
    static long sisaClipMs(long kedaluwarsa) {
        if (kedaluwarsa <= 0) {
            return 0;
        }
        if (kedaluwarsa > 100_000_000_000L) {
            return Math.max(0, kedaluwarsa - System.currentTimeMillis());
        }
        try {
            return sisaClipMs(kedaluwarsa, android.os.SystemClock.elapsedRealtime());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Varian murni 2-arg agar bisa unit test JVM (tanpa SystemClock). */
    static long sisaClipMs(long kedaluwarsa, long kiniElapsed) {
        if (kedaluwarsa <= 0) {
            return 0;
        }
        return Math.max(0, kedaluwarsa - kiniElapsed);
    }


    private void copyLog() {
        String log;
        synchronized (ServerService.logBuffer) {
            log = samarkanLog(ServerService.logBuffer.toString());
        }
        if (log.isEmpty()) {
            toast("Log masih kosong.");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) {
            toast("Clipboard tidak tersedia.");
            return;
        }
        salinClipboardBersihOtomatis("vaultwarden-log", log);
        toast("Log disalin ke clipboard.");
    }

/** Simpan log ke .txt di Download (satu implementasi di LogExport). */
    private void exportLogTxt() {
        // Android 6-9: tulis Download publik butuh izin runtime; tawarkan
        // izin ulang di sini agar tombol Simpan tak selalu "Gagal menyimpan
        // log" bila izin ditolak di layar utama (API 29+ via MediaStore).
        if (Build.VERSION.SDK_INT < 29 && !StoragePerm.sudahPunyaAkses(this)) {
            simpanUlangSetelahIzin = true;
            StoragePerm.mintaIzinBilaPerlu(this, REQ_WRITE);
            toast(getString(R.string.izin_storage_belum));
            return;
        }
        String log;
        synchronized (ServerService.logBuffer) {
            // Mentah saja: penyamaran token sekali di LogExport agar regex
            // berat tak jalan dua kali per export di STB 1 GB.
            log = ServerService.logBuffer.toString();
        }
        String nama = LogExport.simpanKeDownload(this, log);
        toast(nama != null ? "Log disimpan: Download/" + nama : "Gagal menyimpan log");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode == REQ_WRITE) {
            if (StoragePerm.tulisDiizinkan(permissions, grantResults)) {
                if (simpanUlangSetelahIzin) {
                    simpanUlangSetelahIzin = false;
                    exportLogTxt();
                }
            } else {
                simpanUlangSetelahIzin = false;
                StoragePerm.tanganiPenolakan(this);
            }
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
