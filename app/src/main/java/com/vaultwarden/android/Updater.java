package com.vaultwarden.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Cek & pasang update binary vaultwarden dan web-vault (dipakai UI dan perintah bot). */
public final class Updater {

    // Cek versi dari sumber RESMI Vaultwarden (dani-garcia/vaultwarden).
    private static final String OFFICIAL_API =
            "https://api.github.com/repos/dani-garcia/vaultwarden/releases/latest";
    // Binary Android di-host di repo build (resmi tidak menyediakan biner Android).
    private static final String RELEASE_URL =
            "https://github.com/tasirin1/vaultwardenhostingandroid/releases/download/";
    private static final String WV_UPDATE_URL =
            "https://github.com/tasirin1/vaultwardenhostingandroid/releases/latest/download/web-vault.zip";
    private static final long MIN_FREE_FOR_WEBVAULT = 150L * 1024 * 1024;

    private Updater() {
    }

    /** Buka koneksi HTTPS ke GitHub yang ramah Android 5/6
     *  (TLS 1.2 + trust anchor tambahan + User-Agent). */
    private static HttpURLConnection open(Context ctx, String url,
                                          int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android) VaultwardenHost");
        HttpsCompat.apply(c, ctx);
        return c;
    }

    /** Versi resmi terbaru (tanpa huruf v) atau null bila gagal. */
    public static String latestVersion(Context ctx) {
        try {
            HttpURLConnection conn = open(ctx, OFFICIAL_API, 10000, 10000);
            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            conn.disconnect();
            return normVersion(extractTag(sb.toString()));
        } catch (Exception e) {
            return null;
        }
    }

    /** Unduh & pasang update binary; return pesan hasil. Lempar Exception bila gagal. */
    public static String tryUpdate(Context ctx) throws Exception {
        String latest = latestVersion(ctx);
        if (latest == null) {
            throw new IOException("Tidak bisa baca versi terbaru (cek koneksi/TLS).");
        }
        String abi = ServerService.getAbi();
        if (abi == null) {
            throw new IOException("ABI tidak didukung.");
        }

        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String updated = sp.getString(ServerService.KEY_UPDATE_VERSION, "");
        String current = normVersion(updated != null && !updated.isEmpty()
                ? updated : readBundledVersionRaw(ctx));
        if (current != null && current.equals(latest)) {
            return "Sudah versi terbaru: v" + latest;
        }

        // Unduh per tag resmi; kalau build belum ada, beri tahu menunggu build otomatis
        String assetUrl = RELEASE_URL + "v" + latest + "/vaultwarden-" + abi;
        File out = new File(ctx.getFilesDir(), "bin/vaultwarden-" + abi);
        File tmp = new File(ctx.getFilesDir(), "bin/vaultwarden-" + abi + ".tmp");
        File binDir = out.getParentFile();
        if (binDir != null && !binDir.exists()) {
            binDir.mkdirs();
        }
        HttpURLConnection dl = open(ctx, assetUrl, 20000, 60000);
        int code = dl.getResponseCode();
        if (code == 404) {
            dl.disconnect();
            return "Build Android v" + latest
                    + " belum tersedia (build otomatis ~6 jam). Coba lagi nanti.";
        }
        if (code != 200) {
            dl.disconnect();
            throw new IOException("Unduhan gagal (HTTP " + code + ").");
        }
        try (InputStream in = dl.getInputStream();
             FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        } finally {
            dl.disconnect();
        }
        if (tmp.length() < 1_000_000 || !isElf(tmp)) {
            tmp.delete();
            throw new IOException("File update tidak valid.");
        }
        if (out.exists()) {
            out.delete();
        }
        if (!tmp.renameTo(out)) {
            tmp.delete();
            throw new IOException("Gagal menyimpan update.");
        }
        out.setReadable(true, false);
        out.setExecutable(true, false);

        sp.edit().putString(ServerService.KEY_UPDATE_VERSION, latest).apply();
        ServerService.binaryVersion = "";
        return "Update v" + latest + " terpasang.";
    }

    /** Unduh & ekstrak web-vault ke folder data; return pesan hasil. */
    public static String updateWebVault(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String dataDir = sp.getString(ServerService.KEY_DATA_DIR, ServerService.DEFAULT_DATA_DIR);
        if (dataDir == null || dataDir.trim().isEmpty()) {
            dataDir = ServerService.DEFAULT_DATA_DIR;
        }

        long free = TgBackup.freeBytes(dataDir);
        if (free < MIN_FREE_FOR_WEBVAULT) {
            throw new IOException("Sisa penyimpanan tinggal " + TgBackup.humanBytes(free)
                    + " - butuh minimal 150 MB untuk update web-vault.");
        }

        File dataFolder = new File(dataDir);
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File targetDir = new File(dataFolder, "web-vault");
        File tmpZip = new File(dataFolder, "web-vault.zip.tmp");

        // Unduh dengan retry sekali bila gagal (koneksi Android 5/6 kadang putus).
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpURLConnection dl = open(ctx, WV_UPDATE_URL, 20000, 120000);
                int code = dl.getResponseCode();
                if (code != 200) {
                    throw new IOException("Gagal unduh web-vault (HTTP " + code
                            + ") dari " + dl.getURL());
                }
                try (InputStream in = dl.getInputStream();
                     FileOutputStream fos = new FileOutputStream(tmpZip)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                } finally {
                    dl.disconnect();
                }
                lastErr = null;
                break;
            } catch (Exception e) {
                lastErr = e;
                tmpZip.delete();
                if (attempt < 2) {
                    Thread.sleep(3000);
                }
            }
        }
        if (tmpZip.length() < 1000) {
            throw lastErr != null ? lastErr
                    : new IOException("File web-vault tidak valid.");
        }

        // Hapus web-vault lama
        deleteRecursive(targetDir);
        targetDir.mkdirs();

        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(tmpZip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(targetDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath())) {
                    continue;
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        tmpZip.delete();

        File index = new File(targetDir, "index.html");
        if (!index.exists()) {
            throw new IOException("Web vault updated tapi index.html tidak ditemukan.");
        }
        return "Web vault updated di " + targetDir.getAbsolutePath();
    }

    /** Versi Vaultwarden yang dibundel di APK (tanpa huruf v) atau null. */
    public static String readBundledVersionRaw(Context ctx) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("vw_version.txt"), StandardCharsets.UTF_8))) {
            String v = r.readLine();
            return (v == null || v.trim().isEmpty()) ? null : v.trim();
        } catch (Exception e) {
            return null;
        }
    }

    public static String normVersion(String v) {
        if (v == null) {
            return null;
        }
        return v.startsWith("v") ? v.substring(1) : v;
    }

    private static String extractTag(String body) {
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

    private static boolean isElf(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] magic = new byte[4];
            int n = in.read(magic);
            return n == 4 && magic[0] == 0x7F && magic[1] == 'E'
                    && magic[2] == 'L' && magic[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        file.delete();
    }
}
