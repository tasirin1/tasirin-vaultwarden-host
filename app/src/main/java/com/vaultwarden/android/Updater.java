package com.vaultwarden.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
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

    private Updater() {
    }

    /** Versi resmi terbaru (tanpa huruf v) atau null bila gagal. */
    public static String latestVersion() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(OFFICIAL_API).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) {
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
        String latest = latestVersion();
        if (latest == null) {
            throw new java.io.IOException("Tidak bisa baca versi terbaru.");
        }
        String abi = ServerService.getAbi();
        if (abi == null) {
            throw new java.io.IOException("ABI tidak didukung.");
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
        HttpURLConnection dl = (HttpURLConnection) new URL(assetUrl).openConnection();
        dl.setConnectTimeout(20000);
        dl.setReadTimeout(60000);
        dl.setInstanceFollowRedirects(true);
        int code = dl.getResponseCode();
        if (code == 404) {
            return "Build Android v" + latest
                    + " belum tersedia (build otomatis ~6 jam). Coba lagi nanti.";
        }
        if (code != 200) {
            throw new java.io.IOException("Unduhan gagal (HTTP " + code + ").");
        }
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
            throw new java.io.IOException("File update tidak valid.");
        }
        if (out.exists()) {
            out.delete();
        }
        if (!tmp.renameTo(out)) {
            tmp.delete();
            throw new java.io.IOException("Gagal menyimpan update.");
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

        File dataFolder = new File(dataDir);
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File targetDir = new File(dataFolder, "web-vault");
        File tmpZip = new File(dataFolder, "web-vault.zip.tmp");

        HttpURLConnection dl = (HttpURLConnection) new URL(WV_UPDATE_URL).openConnection();
        dl.setConnectTimeout(20000);
        dl.setReadTimeout(120000);
        dl.setInstanceFollowRedirects(true);
        int code = dl.getResponseCode();
        if (code != 200) {
            throw new java.io.IOException("Gagal unduh web-vault (HTTP " + code + ")");
        }
        try (InputStream in = dl.getInputStream();
             FileOutputStream fos = new FileOutputStream(tmpZip)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
        if (tmpZip.length() < 1000) {
            tmpZip.delete();
            throw new java.io.IOException("File web-vault tidak valid.");
        }

        // Hapus web-vault lama
        deleteRecursive(targetDir);
        targetDir.mkdirs();

        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(tmpZip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    new File(targetDir, entry.getName()).mkdirs();
                } else {
                    File outFile = new File(targetDir, entry.getName());
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                    }
                }
            }
        }
        tmpZip.delete();

        File index = new File(targetDir, "index.html");
        return index.exists()
                ? "Web vault updated di " + targetDir.getAbsolutePath()
                : "Web vault updated tapi index.html tidak ditemukan.";
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
