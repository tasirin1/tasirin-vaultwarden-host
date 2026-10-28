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

public class LogActivity extends Activity {

    private static final int REQ_WRITE = 1002;

    private static final String STATE_SIMPAN_ULANG_IZIN = "simpan_ulang_izin";
    private boolean simpanUlangSetelahIzin = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logView;
    private ScrollView logScroll;
    private TextView logCount;
    private String logSearch = "";
    private boolean logAutoScroll = false;

    private final Runnable logTick = new Runnable() {
        @Override
        public void run() {
            if (isDestroyed() || isFinishing()) {
                return;
            }
            refreshLog();
            ui.postDelayed(this, 1500);
        }
    };

    private final Runnable refreshCari = new Runnable() {
        @Override
        public void run() {

            if (isDestroyed() || isFinishing()) {
                return;
            }
            refreshLog();
        }
    };

    private final java.util.concurrent.atomic.AtomicBoolean redaksiJalan =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private String lastLogKey = null;
    private int lastLogLen = 0;
    private String teksTampilTerakhir = null;
    private int barisTampilTerakhir = 0;
    private long lastLogVer = -1;
    private int lineCount = 0;

    private int warnaSorotCari = 0xFFFFE082;
    private int warnaGalat = 0xFFFF0000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            simpanUlangSetelahIzin = savedInstanceState.getBoolean(
                    STATE_SIMPAN_ULANG_IZIN, false);
        }

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
        ui.postDelayed(logTick, 1500);
    }

    @Override
    protected void onResume() {
        super.onResume();

        boolean bolehTampil = false;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
            bolehTampil = logBolehTampil(
                    TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false),
                    PinGate.dalamGraceBersama());
        } catch (Exception ignored) {
        }
        if (!bolehTampil) {
            finish();
            return;
        }
        refreshLog();
        tampilBagikanTertunda();
        ui.removeCallbacks(logTick);
        ui.postDelayed(logTick, 1500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(logTick);
        ui.removeCallbacks(refreshCari);
    }

    @Override
    protected void onDestroy() {
        DialogAman.tutup(this);
        if (isFinishing()) {
            synchronized (KUNCI_BAGIKAN) {
                bagikanTertunda = null;
            }
        }
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }

    private void refreshLog() {

        long ver = ServerService.logVersion();
        int len = ServerService.logLength();
        if (len < lastLogLen || ver < lastLogVer) {

            lastLogLen = 0;
            lastLogVer = ver;
        }

        String key = ver + "\u0000" + len + "\u0000" + logSearch;
        if (key.equals(lastLogKey)) {
            return;
        }

        if (!redaksiJalan.compareAndSet(false, true)) {
            lastLogKey = null;
            return;
        }
        lastLogKey = key;
        lastLogVer = ver;
        lastLogLen = len;
        final String kunci = key;
        final String cari = logSearch;
        final boolean ikutBawah = logAutoScroll;
        final int prevScroll = logScroll.getScrollY();

        boolean masuk = Util.jalankanBg(() -> {
            try {
                final String text;
                synchronized (ServerService.logBuffer) {
                    int n = ServerService.logBuffer.length();
                    int dari = n > BATAS_TAMPIL_LOG + 512
                            ? n - (BATAS_TAMPIL_LOG + 512) : 0;
                    text = ServerService.logBuffer.substring(dari);
                }

                String tampil = text.length() > BATAS_TAMPIL_LOG
                        ? potongEkorBaris(text, BATAS_TAMPIL_LOG) : text;

                int hitung = hitungBaris(tampil);

                String tampilAman = butuhSamaran(tampil) ? samarkanLog(tampil) : tampil;
                final CharSequence sorot = highlightLog(tampilAman, cari);
                final int baris = hitung;
                final boolean bisaDelta = (cari == null || cari.isEmpty())
                        && teksTampilTerakhir != null
                        && teksTampilTerakhir.endsWith("\n")
                        && tampil.length() > teksTampilTerakhir.length()
                        && tampil.startsWith(teksTampilTerakhir);
                final String deltaMentah = bisaDelta
                        ? tampil.substring(teksTampilTerakhir.length()) : null;
                final CharSequence sorotDelta = bisaDelta
                        ? highlightLog(butuhSamaran(deltaMentah)
                                ? samarkanLog(deltaMentah) : deltaMentah, cari)
                        : null;
                final int barisTotal = bisaDelta
                        ? barisTampilTerakhir + hitungBaris(deltaMentah) : hitung;
                ui.post(() -> {
                    try {

                        if (!kunci.equals(lastLogKey)) {
                            return;
                        }
                        if (isDestroyed() || isFinishing()) {
                            return;
                        }
                        lineCount = barisTotal;
                        logCount.setText(getString(R.string.log_lines, lineCount));
                        if (bisaDelta) {
                            logView.append(sorotDelta);
                        } else {
                            logView.setText(sorot);
                        }
                        teksTampilTerakhir = tampil;
                        barisTampilTerakhir = barisTotal;
                        if (ikutBawah) {
                            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
                        } else {
                            logScroll.post(() -> {
                                View child = logScroll.getChildAt(0);
                                int max = (child == null ? 0 : child.getHeight())
                                        - logScroll.getHeight();
                                logScroll.scrollTo(0, Math.max(0, Math.min(prevScroll, max)));
                            });
                        }
                    } finally {
                        redaksiJalan.set(false);
                    }
                });
            } catch (Exception e) {
                redaksiJalan.set(false);
            }
        });
        if (!masuk) {

            redaksiJalan.set(false);
            lastLogKey = null;
        }
    }

    @SuppressWarnings("deprecation")
    private int warnaTema(int id) {
        return getResources().getColor(id);
    }

    private CharSequence highlightLog(String text, String q) {

        if (text.length() > 150_000) {
            return text;
        }
        boolean adaQ = q != null && !q.isEmpty();

        boolean qAscii = adaQ && asciiSemua(q);
        if (adaQ && !qAscii) {
            return highlightLambat(text, q);
        }
        SpannableStringBuilder sb = null;
        int qlen = adaQ ? q.length() : 0;
        int lineStart = 0;
        int total = text.length();
        while (lineStart <= total) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = total;
            }
            boolean galat = false;
            if (adaQ) {
                int j = lineStart;
                while (j + qlen <= lineEnd) {
                    if (cocokAsciiRentang(text, j, q)) {
                        if (sb == null) {
                            sb = new SpannableStringBuilder(text);
                        }
                        sb.setSpan(new BackgroundColorSpan(warnaSorotCari),
                                j, j + qlen,
                                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                        j += qlen;
                        continue;
                    }
                    if (!galat && awalGalatAt(text, j, lineEnd)) {
                        galat = true;
                    }
                    j++;
                }

                if (!galat) {
                    galat = pindaiGalatBaris(text, Math.max(j, lineStart), lineEnd);
                }
            } else {
                galat = pindaiGalatBaris(text, lineStart, lineEnd);
            }
            if (galat) {
                if (sb == null) {
                    sb = new SpannableStringBuilder(text);
                }
                sb.setSpan(new ForegroundColorSpan(warnaGalat),
                        lineStart, lineEnd,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (lineEnd == total) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        return sb != null ? sb : text;
    }

    private static boolean pindaiGalatBaris(String text, int s, int e) {
        for (int j = s; j < e; j++) {
            if (awalGalatAt(text, j, e)) {
                return true;
            }
        }
        return false;
    }

    private static boolean awalGalatAt(String text, int pos, int e) {
        char c = text.charAt(pos);
        if (c >= 'a' && c <= 'z') {
            c = (char) (c - 32);
        }
        if (c == 'G') {
            return cocokAsciiRentangKata(text, pos, e, "GAGAL");
        }
        if (c == 'E') {
            return cocokAsciiRentangKata(text, pos, e, "ERROR");
        }
        if (c == 'F') {
            return cocokAsciiRentangKata(text, pos, e, "FAILED");
        }
        return false;
    }

    private static boolean cocokAsciiRentangKata(String text, int pos, int e, String kata) {
        int m = kata.length();
        if (pos + m > e) {
            return false;
        }
        for (int j = 0; j < m; j++) {
            if (!Util.samaHurufAscii(text.charAt(pos + j), kata.charAt(j))) {
                return false;
            }
        }
        return true;
    }

    private static boolean cocokAsciiRentang(String text, int pos, String kunci) {
        int m = kunci.length();
        if (pos + m > text.length()) {
            return false;
        }
        for (int j = 0; j < m; j++) {
            if (!Util.samaHurufAscii(text.charAt(pos + j), kunci.charAt(j))) {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiSemua(String k) {
        for (int i = 0; i < k.length(); i++) {
            if (k.charAt(i) >= 128) {
                return false;
            }
        }
        return true;
    }

    private CharSequence highlightLambat(String text, String q) {
        SpannableStringBuilder sb = null;
        int from = 0;
        while (from + q.length() <= text.length()) {
            int idx = rangeIndexOf(text, q, from, text.length());
            if (idx < 0) {
                break;
            }
            if (sb == null) {
                sb = new SpannableStringBuilder(text);
            }
            sb.setSpan(new BackgroundColorSpan(warnaSorotCari),
                    idx, idx + q.length(),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = idx + q.length();
        }
        int lineStart = 0;
        int total = text.length();
        while (lineStart <= total) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = total;
            }
            if (pindaiGalatBaris(text, lineStart, lineEnd)) {
                if (sb == null) {
                    sb = new SpannableStringBuilder(text);
                }
                sb.setSpan(new ForegroundColorSpan(warnaGalat),
                        lineStart, lineEnd,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (lineEnd == total) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        return sb != null ? sb : text;
    }

    private static int rangeIndexOf(String text, String keyword, int from, int end) {
        int max = end - keyword.length();
        boolean ascii = true;
        for (int k = 0; k < keyword.length(); k++) {
            if (keyword.charAt(k) >= 128) {
                ascii = false;
                break;
            }
        }
        for (int i = Math.max(from, 0); i <= max; i++) {
            boolean cocok;
            if (ascii) {
                int j = 0;
                while (j < keyword.length()
                        && Util.samaHurufAscii(text.charAt(i + j), keyword.charAt(j))) {
                    j++;
                }
                cocok = j >= keyword.length();
            } else {
                cocok = text.regionMatches(true, i, keyword, 0, keyword.length());
            }
            if (cocok) {
                return i;
            }
        }
        return -1;
    }

    private void showCrashDialog() {

        boolean masuk = Util.jalankanBg(() -> {

            String mentahCrash = ServerService.crashLogText(LogActivity.this);
            final String crash = butuhSamaran(mentahCrash) ? samarkanLog(mentahCrash) : mentahCrash;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (crash == null || crash.trim().isEmpty()) {
                    toast(getString(R.string.log_crash_belum_ada));
                    return;
                }
                tampilDialogCrash(crash);
            });
        });
        if (!masuk) {
            toast(getString(R.string.antrean_penuh));
        }
    }

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
        DialogAman.tampil(this, new AlertDialog.Builder(this)
                .setTitle(R.string.log_crash_judul)
                .setView(sv)
                .setPositiveButton(R.string.salin, (dlg, w) -> {
                    salinClipboardBersihOtomatis("vaultwarden-crash", crash);
                    toast(getString(R.string.log_crash_disalin));
                })
                .setNegativeButton(getString(R.string.close), null)
                .create());
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

    private static final java.util.regex.Pattern POLA_TOKEN_MENTAH =
            java.util.regex.Pattern.compile("(?<!\\w)\\d{6,12}:[A-Za-z0-9_-]{10,}");
    private static final java.util.regex.Pattern POLA_BOT_URL =
            java.util.regex.Pattern.compile("(?i)(api\\.telegram\\.org/bot)[A-Za-z0-9_-]+:[A-Za-z0-9_-]{6,}");
    private static final java.util.regex.Pattern POLA_ADMIN_ENV =
            java.util.regex.Pattern.compile("(?i)(ADMIN_TOKEN\\s*=\\s*)[^\\s]+");
    private static final java.util.regex.Pattern POLA_BEARER =
            java.util.regex.Pattern.compile("(?i)(Authorization\\s*:\\s*Bearer\\s+)\\S+");

    private static final java.util.regex.Pattern POLA_KREDENSIAL_NILAI =
            java.util.regex.Pattern.compile("(?i)((?:tg_token|tg_chat|pin_hash|admin_token|alarm_secret|tg_last_file|bin_sha)\\s*[:=]\\s*)([^\\s&,;\"']+)");

    private static final java.util.regex.Pattern POLA_IP_PRIVAT =
            java.util.regex.Pattern.compile("\\b(192\\.168\\.\\d{1,3}\\.\\d{1,3}|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2[0-9]|3[01])\\.\\d{1,3}\\.\\d{1,3})(:\\d+)?\\b");

    private static final java.util.regex.Pattern POLA_EMAIL =
            java.util.regex.Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    private static final java.util.regex.Pattern POLA_IP_PUBLIK =
            java.util.regex.Pattern.compile("\\b(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(?:\\.(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}\\b");

    private static final java.util.regex.Pattern POLA_DOMAIN =
            java.util.regex.Pattern.compile("(?i)((?:\\bDOMAIN\\b)\\s*[:=]\\s*)([^\\s&,;\"']+)");

    private static final java.util.regex.Pattern POLA_SANDI_SPASI =
            java.util.regex.Pattern.compile("(?i)((?:tg_pass)\\s*[:=]\\s*)([^\n&]+?)"
                    + "(?=\\s+(?:tg_token|tg_chat|pin_hash|admin_token|DOMAIN|chat_id|token)\\s*[:=]|\\n|$)");

    static boolean logBolehTampil(boolean pinOn, boolean grace) {
        return !pinOn || grace;
    }

    static boolean butuhSamaran(String t) {
        if (t == null || t.isEmpty()) {
            return false;
        }

        if (pindaiKunciRahasia(t)) {
            return true;
        }
        return t.contains("192.168.") || t.contains("10.")
                || t.contains("172.") || t.contains("127.0.0.1:")
                || adaEmail(t) || adaIpPublik(t);
    }

    static boolean pindaiKunciRahasia(String t) {
        int n = t.length();
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }
            switch (c) {
                case 't':
                    if (cocokKunciAt(t, i, "token") || cocokKunciAt(t, i, "tg_")) {
                        return true;
                    }
                    break;
                case 'b':
                    if (cocokKunciAt(t, i, "bot") || cocokKunciAt(t, i, "bearer")
                            || cocokKunciAt(t, i, "bin_sha")) {
                        return true;
                    }
                    break;
                case 'a':
                    if (cocokKunciAt(t, i, "admin") || cocokKunciAt(t, i, "alarm_secret")
                            || cocokKunciAt(t, i, "api.telegram.org")) {
                        return true;
                    }
                    break;
                case 'd':
                    if (cocokKunciAt(t, i, "domain")) {
                        return true;
                    }
                    break;
                case 'p':
                    if (cocokKunciAt(t, i, "pin")) {
                        return true;
                    }
                    break;
                case 'c':
                    if (cocokKunciAt(t, i, "chat_id") || cocokKunciAt(t, i, "chatid")) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    private static final int BIT_TOKEN_EQ = 1;
    private static final int BIT_CHAT = 1 << 1;
    private static final int BIT_BOT = 1 << 2;
    private static final int BIT_TELEGRAM = 1 << 3;
    private static final int BIT_ADMIN = 1 << 4;
    private static final int BIT_BEARER = 1 << 5;
    private static final int BIT_TG_PASS = 1 << 6;
    private static final int BIT_TG = 1 << 7;
    private static final int BIT_PIN = 1 << 8;
    private static final int BIT_ALARM = 1 << 9;
    private static final int BIT_BINSHA = 1 << 10;
    private static final int BIT_DOMAIN = 1 << 11;

    private static int topengKunci(String t) {
        int topeng = 0;
        int n = t.length();
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }
            switch (c) {
                case 't':

                    if (cocokKunciAt(t, i, "token")) {
                        topeng |= BIT_TOKEN_EQ;
                    }
                    if (cocokKunciAt(t, i, "tg_pass")) {
                        topeng |= BIT_TG_PASS | BIT_TG;
                    } else if (cocokKunciAt(t, i, "tg_")) {
                        topeng |= BIT_TG;
                    }
                    if (cocokKunciAt(t, i, "telegram")) {
                        topeng |= BIT_TELEGRAM;
                    }
                    break;
                case 'c':
                    if (cocokKunciAt(t, i, "chat_id") || cocokKunciAt(t, i, "chatid")) {
                        topeng |= BIT_CHAT;
                    }
                    break;
                case 'b':
                    if (cocokKunciAt(t, i, "bot")) {
                        topeng |= BIT_BOT;
                    }
                    if (cocokKunciAt(t, i, "bearer")) {
                        topeng |= BIT_BEARER;
                    }
                    if (cocokKunciAt(t, i, "bin_sha")) {
                        topeng |= BIT_BINSHA;
                    }
                    break;
                case 'a':
                    if (cocokKunciAt(t, i, "admin_token")) {
                        topeng |= BIT_ADMIN;
                    } else if (cocokKunciAt(t, i, "admin")) {
                        topeng |= BIT_ADMIN;
                    }
                    if (cocokKunciAt(t, i, "alarm")) {
                        topeng |= BIT_ALARM;
                    }
                    if (cocokKunciAt(t, i, "api.telegram.org")) {
                        topeng |= BIT_TELEGRAM;
                    }
                    break;
                case 'p':
                    if (cocokKunciAt(t, i, "pin")) {
                        topeng |= BIT_PIN;
                    }
                    break;
                case 'd':
                    if (cocokKunciAt(t, i, "domain")) {
                        topeng |= BIT_DOMAIN;
                    }
                    break;
                default:
                    break;
            }
            if (topeng == (BIT_TOKEN_EQ | BIT_CHAT | BIT_BOT | BIT_TELEGRAM | BIT_ADMIN
                    | BIT_BEARER | BIT_TG_PASS | BIT_TG | BIT_PIN | BIT_ALARM
                    | BIT_BINSHA | BIT_DOMAIN)) {
                break;
            }
        }
        return topeng;
    }

    private static boolean cocokKunciAt(String t, int pos, String kunci) {
        int m = kunci.length();
        if (pos + m > t.length()) {
            return false;
        }
        for (int j = 0; j < m; j++) {
            if (!Util.samaHurufAscii(t.charAt(pos + j), kunci.charAt(j))) {
                return false;
            }
        }
        return true;
    }

    static String samarkanLog(String log) {
        if (log == null) {
            return "";
        }

        String r = log;
        int topeng = topengKunci(r);
        if ((topeng & BIT_TOKEN_EQ) != 0) {
            r = POLA_TOKEN_URL.matcher(r).replaceAll("$1***");
        }

        if ((topeng & (BIT_TOKEN_EQ | BIT_TG | BIT_ADMIN | BIT_PIN)) != 0) {
            if (r.indexOf('"') >= 0) {
                r = POLA_TOKEN_JSON.matcher(r).replaceAll("$1***\"");
            }
            if (r.indexOf('\'') >= 0) {
                r = POLA_TOKEN_JSON_SQ.matcher(r).replaceAll("$1***'");
            }
        }
        if ((topeng & BIT_CHAT) != 0) {
            r = POLA_CHAT_ID.matcher(r).replaceAll("$1***");
        }
        if ((topeng & BIT_BOT) != 0) {
            r = POLA_BOT_TOKEN.matcher(r).replaceAll("bot***:***");
        }

        if (adaBentukTokenMentah(r)) {
            r = POLA_TOKEN_MENTAH.matcher(r).replaceAll("***:***");
        }

        if ((topeng & BIT_TELEGRAM) != 0) {
            r = POLA_BOT_URL.matcher(r).replaceAll("$1***:***");
        }
        if ((topeng & BIT_ADMIN) != 0) {
            r = POLA_ADMIN_ENV.matcher(r).replaceAll("$1***");
        }
        if ((topeng & BIT_BEARER) != 0) {
            r = POLA_BEARER.matcher(r).replaceAll("$1***");
        }
        if ((topeng & BIT_TG_PASS) != 0) {
            r = POLA_SANDI_SPASI.matcher(r).replaceAll("$1***");
        }
        if ((topeng & (BIT_TG | BIT_PIN | BIT_ADMIN | BIT_ALARM | BIT_BINSHA)) != 0) {
            r = POLA_KREDENSIAL_NILAI.matcher(r).replaceAll("$1***");
        }
        if ((topeng & BIT_DOMAIN) != 0) {
            r = POLA_DOMAIN.matcher(r).replaceAll("$1***");
        }

        if (adaIpPrivat(r)) {
            r = POLA_IP_PRIVAT.matcher(r).replaceAll("[ip-privat]");
        }
        if (adaEmail(r)) {
            r = POLA_EMAIL.matcher(r).replaceAll("[email]");
        }
        if (adaIpPublik(r)) {
            r = POLA_IP_PUBLIK.matcher(r).replaceAll("[ip-publik]");
        }
        return r;
    }

    static boolean adaBentukTokenMentah(String t) {
        if (t == null) {
            return false;
        }
        int n = t.length();
        int digit = 0;
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') {
                digit++;
                continue;
            }
            if (c == ':' && digit >= 6) {
                int j = i + 1;
                int k = 0;
                while (j < n && k < 10) {
                    char d = t.charAt(j);
                    if ((d >= 'A' && d <= 'Z') || (d >= 'a' && d <= 'z')
                            || (d >= '0' && d <= '9') || d == '_' || d == '-') {
                        k++;
                        j++;
                    } else {
                        break;
                    }
                }
                if (k >= 10) {
                    return true;
                }
            }
            digit = 0;
        }
        return false;
    }

    static boolean adaEmail(String t) {
        return t != null && POLA_EMAIL.matcher(t).find();
    }

    static boolean adaIpPublik(String t) {
        return t != null && POLA_IP_PUBLIK.matcher(t).find();
    }

    static boolean adaIpPrivat(String t) {
        if (t == null) {
            return false;
        }
        int n = t.length();
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            if (c != '1') {
                continue;
            }
            if (t.startsWith("192.168.", i) || t.startsWith("127.0.0.1:", i)) {
                return true;
            }

            boolean sepuluh = t.startsWith("10.", i);
            boolean seratusTujuh = !sepuluh && t.startsWith("172.", i);
            if ((sepuluh && i + 3 < n) || (seratusTujuh && i + 4 < n)) {
                char ikut = t.charAt(i + (sepuluh ? 3 : 4));
                if (ikut >= '0' && ikut <= '9') {
                    return true;
                }
            }
        }
        return false;
    }

    private static final Object KUNCI_BAGIKAN = new Object();
    private static volatile String bagikanTertunda = null;

    private void shareLog() {

        toast(getString(R.string.log_menyiapkan));
        boolean masuk = Util.jalankanBg(() -> {
            final String mentah;
            synchronized (ServerService.logBuffer) {
                mentah = ServerService.logBuffer.toString();
            }
            final String log = butuhSamaran(mentah) ? samarkanLog(mentah) : mentah;
            if (log.isEmpty()) {
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    toast(getString(R.string.log_kosong));
                });
                return;
            }
            synchronized (KUNCI_BAGIKAN) {
                bagikanTertunda = log;
            }
            ui.post(() -> tampilBagikanTertunda());
        });
        if (!masuk) {
            toast(getString(R.string.antrean_penuh));
        }
    }

    private void tampilBagikanTertunda() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        final String log;
        synchronized (KUNCI_BAGIKAN) {
            log = bagikanTertunda;
            bagikanTertunda = null;
        }
        if (log == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_bagikan_subjek));
        send.putExtra(Intent.EXTRA_TEXT, log);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.log_bagikan)));
        } catch (Exception e) {
            toast(getString(R.string.log_bagikan_gagal, String.valueOf(e.getMessage())));
        }
    }

    static final String KEY_CLIP_HASH = "clip_hash";
    static final String KEY_CLIP_KEDALUWARSA = "clip_kedaluwarsa";
    static final long CLIP_BERSIH_MS = 30_000;

    static final int BATAS_TAMPIL_LOG = 100_000;

    static int hitungBaris(CharSequence teks) {
        if (teks == null || teks.length() == 0) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < teks.length(); i++) {
            if (teks.charAt(i) == '\n') {
                n++;
            }
        }
        if (teks.charAt(teks.length() - 1) != '\n') {
            n++;
        }
        return n;
    }

    static String sidikClip(String s) {
        if (s == null) {
            return "";
        }
        try {
            byte[] h = Util.mdSha256().digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Util.heks(h);
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean salinBersihOtomatis(Context ctx, String label, String isi) {
        if (ctx == null || isi == null) {
            return false;
        }
        final String etiket = label == null ? "vaultwarden" : label;
        final Context app;
        try {
            Context a = ctx.getApplicationContext();
            app = a != null ? a : ctx;
        } catch (Exception e) {
            return false;
        }
        final ClipboardManager cm;
        try {
            cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        } catch (Exception e) {
            return false;
        }
        if (cm == null) {
            return false;
        }
        try {
            cm.setPrimaryClip(ClipData.newPlainText(etiket, isi));
        } catch (Exception e) {
            return false;
        }
        final String sidik = sidikClip(isi);
        final long kedaluwarsa;
        try {
            kedaluwarsa = android.os.SystemClock.elapsedRealtime() + CLIP_BERSIH_MS;
        } catch (Exception e) {
            return true;
        }
        try {
            app.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_CLIP_HASH, sidik)
                    .putLong(KEY_CLIP_KEDALUWARSA, kedaluwarsa).apply();
        } catch (Exception ignored) {
        }

        try {
            Util.postTunda(() -> {
                try {
                    bersihkanBilaIsiKita(app, etiket, sidik);
                } catch (Exception ignored) {
                } finally {
                    hapusPenandaClipJikaCocok(app, sidik);
                }
            }, CLIP_BERSIH_MS);
        } catch (Exception ignored) {
        }
        return true;
    }

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
            Util.postTunda(() -> {
                bersihkanBilaIsiKita(app, "vaultwarden", sidik);
            }, sisa);
            return;
        }
        bersihkanBilaIsiKita(app, "vaultwarden", sidik);
    }

    private static void bersihkanBilaIsiKita(Context ctx, String label, String sidikTangkap) {
        if (sidikTangkap == null || sidikTangkap.isEmpty()) {
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return;
            }
            android.content.ClipData cur = cm.getPrimaryClip();
            if (cur != null && cur.getItemCount() > 0 && cur.getItemAt(0) != null
                    && sidikTangkap.equals(sidikClip(String.valueOf(cur.getItemAt(0).getText())))) {
                cm.setPrimaryClip(ClipData.newPlainText(label == null ? "vaultwarden" : label, ""));
            }
        } catch (Exception ignored) {
        } finally {
            hapusPenandaClipJikaCocok(ctx, sidikTangkap);
        }
    }

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

    private boolean salinClipboardBersihOtomatis(String label, String isi) {
        return salinBersihOtomatis(this, label, isi);
    }

    private void bersihkanClipBasiJikaAda() {
        bersihkanClipBasiJikaAda(this);
    }

    static final long BATAS_CLIP_WALL_MS = 400_000_000_000L;

    static boolean clipPakaiWallClock(long kedaluwarsa) {
        return kedaluwarsa > BATAS_CLIP_WALL_MS;
    }

    static long sisaClipMs(long kedaluwarsa) {
        if (kedaluwarsa <= 0) {
            return 0;
        }
        if (clipPakaiWallClock(kedaluwarsa)) {
            return Math.max(0, kedaluwarsa - System.currentTimeMillis());
        }
        try {
            return sisaClipMs(kedaluwarsa, android.os.SystemClock.elapsedRealtime());
        } catch (Exception e) {
            return 0;
        }
    }

    static long sisaClipMs(long kedaluwarsa, long kiniElapsed) {
        if (kedaluwarsa <= 0) {
            return 0;
        }
        return Math.max(0, kedaluwarsa - kiniElapsed);
    }

    static String potongEkorBaris(String text, int maks) {
        if (text == null || maks <= 0) {
            return "";
        }
        if (text.length() <= maks) {
            return text;
        }
        int akhir = text.length();
        while (akhir > 0 && text.charAt(akhir - 1) == '\n') {
            akhir--;
        }
        if (akhir <= 0) {
            return "";
        }
        if (akhir <= maks) {
            return text.substring(0, akhir);
        }
        int mulai = akhir - maks;

        int nl = text.indexOf('\n', mulai);
        if (nl >= 0 && nl + 1 < akhir) {
            return text.substring(nl + 1, akhir);
        }
        return text.substring(mulai, akhir);
    }

    private void copyLog() {

        toast(getString(R.string.log_menyiapkan));
        boolean masuk = Util.jalankanBg(() -> {
            final String mentah;
            synchronized (ServerService.logBuffer) {
                mentah = ServerService.logBuffer.toString();
            }
            final String log = butuhSamaran(mentah) ? samarkanLog(mentah) : mentah;
            if (log.isEmpty()) {
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    toast(getString(R.string.log_kosong));
                });
                return;
            }
            final boolean tersalin = salinClipboardBersihOtomatis("vaultwarden-log", log);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                toast(getString(tersalin
                        ? R.string.log_disalin : R.string.papan_klip_tak_tersedia));
            });
        });
        if (!masuk) {
            toast(getString(R.string.antrean_penuh));
        }
    }

    private void exportLogTxt() {

        if (Build.VERSION.SDK_INT < 29 && !StoragePerm.sudahPunyaAkses(this)) {
            simpanUlangSetelahIzin = true;

            if (!StoragePerm.mintaIzinBilaPerlu(this, REQ_WRITE)) {
                simpanUlangSetelahIzin = false;
                toast(getString(R.string.izin_simpan_belum));
            }
            return;
        }

        toast(getString(R.string.log_menyimpan));
        boolean masuk = Util.jalankanBg(() -> {
            final String log;
            synchronized (ServerService.logBuffer) {

                log = ServerService.logBuffer.toString();
            }
            final String nama = LogExport.simpanKeDownload(LogActivity.this, log);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                toast(nama != null ? getString(R.string.log_tersimpan, nama)
                        : getString(R.string.log_gagal_simpan));
            });
        });
        if (!masuk) {
            toast(getString(R.string.antrean_penuh));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(STATE_SIMPAN_ULANG_IZIN, simpanUlangSetelahIzin);
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

        final String teks = msg == null ? "" : msg;
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            try {
                Toast.makeText(LogActivity.this, teks, Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
            }
        });
    }
}
