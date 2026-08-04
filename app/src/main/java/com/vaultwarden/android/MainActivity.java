package com.vaultwarden.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int REQ_WRITE = 1001;
    private static final String DEFAULT_DATA_DIR = "/sdcard/vaultwarden";
    private static final String DEFAULT_PORT = "8080";
    private static final String UPDATE_URL = "https://github.com/tasirin1/vaultwardenhostingandroid/releases/latest/download/";
    private static final String LATEST_API = "https://api.github.com/repos/tasirin1/vaultwardenhostingandroid/releases/latest";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText dataDirInput;
    private EditText portInput;
    private CheckBox autoStartCheck;
    private TextView statusView;
    private TextView versionView;
    private TextView logView;
    private ScrollView scrollView;

    private String bundledVersion = "?";
    private String lastShownStatus = "";
    private String lastShownVersion = "";
    private int lastShownLogLen = -1;
    private String lastSavedDataDir = "";
    private String lastSavedPort = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dataDirInput = findViewById(R.id.dataDir);
        portInput = findViewById(R.id.port);
        autoStartCheck = findViewById(R.id.autoStart);
        statusView = findViewById(R.id.status);
        versionView = findViewById(R.id.version);
        logView = findViewById(R.id.log);
        scrollView = findViewById(R.id.scroll);

        Button startBtn = findViewById(R.id.start);
        Button stopBtn = findViewById(R.id.stop);
        Button openBtn = findViewById(R.id.open);
        Button updateBtn = findViewById(R.id.update);
        Button revertBtn = findViewById(R.id.revert);

        startBtn.setOnClickListener(v -> ServerService.start(this));
        stopBtn.setOnClickListener(v -> ServerService.stop(this));
        openBtn.setOnClickListener(v -> openWebUi());
        updateBtn.setOnClickListener(v -> new Thread(this::checkForUpdate, "vw-update").start());
        revertBtn.setOnClickListener(v -> revertToBundled());

        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        dataDirInput.setText(sp.getString(ServerService.KEY_DATA_DIR, DEFAULT_DATA_DIR));
        portInput.setText(sp.getString(ServerService.KEY_PORT, DEFAULT_PORT));
        autoStartCheck.setChecked(sp.getBoolean(ServerService.KEY_AUTO_START, false));

        autoStartCheck.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ServerService.KEY_AUTO_START, checked).apply());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            }
        }

        bundledVersion = readBundledVersion();
        ui.post(this::refreshFromService);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(this::refreshFromService);
    }

    /** Update tampilan dari status service (dipanggil periodik). */
    private void refreshFromService() {
        String status = ServerService.statusLine;
        if (status == null || status.isEmpty()) {
            status = ServerService.running ? "Running..." : "Stopped";
        }
        if (!status.equals(lastShownStatus)) {
            statusView.setText(status);
            lastShownStatus = status;
        }

        String version = "Vaultwarden bawaan: " + bundledVersion
                + " | binary: " + (ServerService.binaryVersion.isEmpty() ? "-" : ServerService.binaryVersion)
                + " | update terpasang: " + getSharedPreferences(ServerService.PREFS, MODE_PRIVATE)
                        .getString(ServerService.KEY_UPDATE_VERSION, "-");
        if (!version.equals(lastShownVersion)) {
            versionView.setText(version);
            lastShownVersion = version;
        }

        synchronized (ServerService.logBuffer) {
            if (ServerService.logBuffer.length() != lastShownLogLen) {
                logView.setText(ServerService.logBuffer.toString());
                lastShownLogLen = ServerService.logBuffer.length();
                scrollView.fullScroll(View.FOCUS_DOWN);
            }
        }

        // Simpan setting bila user ganti (dipakai oleh boot auto-start).
        String dataDir = dataDirInput.getText().toString().trim();
        String port = portInput.getText().toString().trim();
        if (!TextUtils.isEmpty(dataDir) && !TextUtils.isEmpty(port)
                && (!dataDir.equals(lastSavedDataDir) || !port.equals(lastSavedPort))) {
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .putString(ServerService.KEY_DATA_DIR, dataDir)
                    .putString(ServerService.KEY_PORT, port)
                    .apply();
            lastSavedDataDir = dataDir;
            lastSavedPort = port;
        }

        ui.postDelayed(this::refreshFromService, 1000);
    }

    private String readBundledVersion() {
        try (InputStream in = getAssets().open("vw_version.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String v = r.readLine();
            return (v == null || v.trim().isEmpty()) ? "?" : v.trim();
        } catch (Exception e) {
            return "?";
        }
    }

    private void checkForUpdate() {
        String abi = ServerService.getAbi();
        if (abi == null) {
            toast("ABI tidak didukung: " + TextUtils.join(", ", Build.SUPPORTED_ABIS));
            return;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(LATEST_API).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            String body;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
                body = sb.toString();
            }
            String latestTag = extractTag(body);
            if (latestTag == null) {
                toast("Gagal membaca release terbaru.");
                return;
            }
            String latest = latestTag.startsWith("v") ? latestTag.substring(1) : latestTag;
            if (latest.equals(bundledVersion)) {
                toast("Sudah versi terbaru (v" + bundledVersion + ").");
                return;
            }

            String assetUrl = UPDATE_URL + "vaultwarden-" + abi;
            toast("Update v" + latest + " ditemukan, mengunduh...");
            appendUiLog("[app] Mengunduh update v" + latest + " dari " + assetUrl);

            File out = new File(getFilesDir(), "bin/vaultwarden-" + abi);
            File tmp = new File(getFilesDir(), "bin/vaultwarden-" + abi + ".tmp");
            File binDir = out.getParentFile();
            if (binDir != null && !binDir.exists()) {
                binDir.mkdirs();
            }
            HttpURLConnection dl = (HttpURLConnection) new URL(assetUrl).openConnection();
            dl.setConnectTimeout(20000);
            dl.setReadTimeout(60000);
            dl.setInstanceFollowRedirects(true);
            int code = dl.getResponseCode();
            if (code != 200) {
                toast("Unduhan gagal (HTTP " + code + ").");
                return;
            }
            long size = dl.getContentLength();
            try (InputStream in = dl.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            if (tmp.length() < 1_000_000 || !isElf(tmp)) {
                tmp.delete();
                toast("File update tidak valid.");
                return;
            }
            if (out.exists()) {
                out.delete();
            }
            if (!tmp.renameTo(out)) {
                tmp.delete();
                toast("Gagal menyimpan update.");
                return;
            }
            out.setReadable(true, false);
            out.setExecutable(true, false);

            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .putString(ServerService.KEY_UPDATE_VERSION, latest).apply();
            ServerService.binaryVersion = "";
            toast("Update v" + latest + " terpasang. Tekan Start untuk memakai.");
            appendUiLog("[app] Update v" + latest + " terpasang. (" + (size > 0 ? size : "?") + " bytes)");
        } catch (Exception e) {
            toast("Gagal cek update: " + e.getMessage());
            appendUiLog("[app] Gagal cek update: " + e);
        }
    }

    private void revertToBundled() {
        String abi = ServerService.getAbi();
        if (abi == null) {
            return;
        }
        File out = new File(getFilesDir(), "bin/vaultwarden-" + abi);
        if (out.exists() && out.delete()) {
            getSharedPreferences(ServerService.PREFS, MODE_PRIVATE).edit()
                    .remove(ServerService.KEY_UPDATE_VERSION).apply();
            ServerService.binaryVersion = "";
            toast("Binary bawaan dipulihkan. Tekan Start.");
            appendUiLog("[app] Kembali ke binary bawaan APK.");
        } else {
            toast("Tidak ada update terpasang.");
        }
    }

    private boolean isElf(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] magic = new byte[4];
            int n = in.read(magic);
            return n == 4 && magic[0] == 0x7F && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    private String extractTag(String body) {
        String key = "\"tag_name\":";
        int i = body.indexOf(key);
        if (i < 0) {
            return null;
        }
        int s = body.indexOf('"', i + key.length());
        int e = body.indexOf('"', s + 1);
        if (s < 0 || e < 0) {
            return null;
        }
        return body.substring(s + 1, e);
    }

    private void openWebUi() {
        String port = portInput.getText().toString().trim();
        if (TextUtils.isEmpty(port)) {
            port = DEFAULT_PORT;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://127.0.0.1:" + port)));
        } catch (Exception e) {
            toast("Tidak bisa membuka browser: " + e.getMessage());
        }
    }

    private void appendUiLog(String line) {
        synchronized (ServerService.logBuffer) {
            ServerService.logBuffer.append(line).append('\n');
        }
        ui.post(this::refreshFromService);
    }

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }
}
