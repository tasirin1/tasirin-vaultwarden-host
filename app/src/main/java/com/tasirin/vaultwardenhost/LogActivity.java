package com.tasirin.vaultwardenhost;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Halaman log server realtime layar penuh (di-port dari LogActivity download manager). */
public class LogActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logView;
    private ScrollView logScroll;
    private TextView logCount;
    private String logSearch = "";
    private boolean logAutoScroll = false;
    private String lastLogKey = null;
    private int lastLogLen = 0;
    private int lineCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Privasi: nonaktifkan screenshot + preview recents dikosongkan (sama dengan MainActivity).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(savedInstanceState);
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
                refreshLog();
            }
        });

        refreshLog();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isDestroyed() || isFinishing()) {
                    return;
                }
                refreshLog();
                ui.postDelayed(this, 1000);
            }
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }

    private void refreshLog() {
        // Salinan 300 KB hanya dibuat bila panjang berubah (hampir selalu sama
        // antar refresh tiap detik); sebelumnya menyalin dulu baru membandingkan.
        int len = ServerService.logLength();
        if (len < lastLogLen) {
            // Log terpotong (trim buffer) - hitung ulang dari awal.
            lineCount = 0;
            lastLogLen = 0;
        }
        // Konten log append-only (trim hanya memendekkan) - panjang cukup sebagai
        // penanda perubahan, tanpa perlu menyalin/membandingkan teks 300 KB tiap detik.
        String key = len + "\u0000" + logSearch;
        if (key.equals(lastLogKey)) {
            return;
        }
        lastLogKey = key;
        String text;
        synchronized (ServerService.logBuffer) {
            text = ServerService.logBuffer.toString();
        }
        int n = Math.min(len, text.length());
        for (int i = lastLogLen; i < n; i++) {
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

    /** Sorot baris GAGAL/ERROR/FAILED merah dan kata kunci pencarian kuning.
     *  Tanpa toLowerCase()/substring() per baris: pencocokan case-insensitive
     *  via regionMatches agar tidak ada salinan besar tiap refresh. */
    private CharSequence highlightLog(String text, String q) {
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
                sb.setSpan(new BackgroundColorSpan(0xFFFFE082),
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
                sb.setSpan(new ForegroundColorSpan(Color.RED),
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
        String crash = ServerService.crashLogText(this);
        if (crash == null || crash.trim().isEmpty()) {
            toast("Belum ada crash log.");
            return;
        }
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
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("vaultwarden-crash", crash));
                    toast("Crash log disalin ke clipboard.");
                })
                .setNegativeButton(getString(R.string.close), null)
                .show();
    }

    static String samarkanLog(String log) {
        if (log == null) {
            return "";
        }
        // Samarkan nilai token di query (?token=...) agar tak ikut
        // terbagi ke app lain via ACTION_SEND / clipboard.
        return log.replaceAll("(?i)(token=)[^&\\s\\]]+", "$1***");
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
        cm.setPrimaryClip(ClipData.newPlainText("vaultwarden-log", log));
        toast("Log disalin ke clipboard.");
    }

    /** Simpan log ke .txt di Download (format header ala Tasirin). */
    // API lawas sengaja: Downloads publik pra-29 + getPackageInfo satu jalur untuk API 21-32.
    @SuppressWarnings("deprecation")
    private void exportLogTxt() {
        String log;
        synchronized (ServerService.logBuffer) {
            log = ServerService.logBuffer.toString();
        }
        StringBuilder header = new StringBuilder();
        header.append("=== Tasirin Vaultwarden Host - Log Server (realtime) ===\n");
        header.append("Waktu: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append('\n');
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            header.append("Versi app: ").append(info.versionName)
                    .append(" (build ").append(info.versionCode).append(")\n");
        } catch (Exception ignored) {
            header.append("Versi app: ?\n");
        }
        header.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        header.append("Perangkat: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append("\n\n");
        header.append(log.isEmpty() ? "(Belum ada aktivitas server)\n" : log);
        header.append('\n');

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String name = "tasirin-vaultwarden-host-log-" + stamp + ".txt";
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream out = resolver.openOutputStream(uri)) {
                        if (out != null) {
                            out.write(header.toString().getBytes(StandardCharsets.UTF_8));
                            ok = true;
                        } else {
                            resolver.delete(uri, null, null);
                        }
                    } catch (Exception e) {
                        resolver.delete(uri, null, null);
                    }
                    if (ok) {
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Downloads.IS_PENDING, 0);
                        resolver.update(uri, done, null, null);
                    }
                }
            } catch (Exception ignored) {
            }
        } else {
            try {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                    try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                            new FileOutputStream(new File(dir, name), false),
                            StandardCharsets.UTF_8)) {
                        w.write(header.toString());
                    }
                    ok = true;
                }
            } catch (Exception ignored) {
            }
        }
        toast(ok ? "Log disimpan: Download/" + name : "Gagal menyimpan log");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
