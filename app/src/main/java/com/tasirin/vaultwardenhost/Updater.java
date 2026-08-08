package com.tasirin.vaultwardenhost;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Cek & pasang update binary vaultwarden dan web-vault (dipakai UI dan perintah bot). */
public final class Updater {

    // Cek versi dari sumber RESMI Vaultwarden (dani-garcia/vaultwarden).
    private static final String OFFICIAL_API =
            "https://api.github.com/repos/dani-garcia/vaultwarden/releases/latest";
    // Binary Android di-host di repo build (resmi tidak menyediakan biner Android).
    private static final String RELEASE_URL =
            "https://github.com/tasirin1/tasirin-vaultwarden-host/releases/download/";
    private static final String RELEASE_LATEST_URL =
            "https://github.com/tasirin1/tasirin-vaultwarden-host/releases/latest/download/";
    private static final String WV_UPDATE_URL =
            RELEASE_LATEST_URL + "web-vault.zip";
    private static final long MIN_FREE_FOR_WEBVAULT = 150L * 1024 * 1024;
    // Penanda versi vaultwarden pemilik web-vault yang terpasang (supaya tidak
    // mengunduh ulang ~35 MB tiap kali tombol "Update Web Vault" ditekan).
    private static final String KEY_WV_FROM = "wv_from_version";

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
                "Mozilla/5.0 (Linux; Android) TasirinVaultwardenHost");
        HttpsCompat.apply(c, ctx);
        return c;
    }

    // Cache versi terbaru (TTL 15 menit) supaya tidak menabrak rate-limit
    // API GitHub saat Start diulang-ulang / koneksi Android 5/6 putus-putus.
    private static final long VERSION_TTL_MS = 15 * 60 * 1000L;
    private static volatile String sLatestVersion;
    private static volatile long sLatestAt;

    /** Versi resmi terbaru (tanpa huruf v) atau null bila belum pernah dapat. */
    public static String latestVersion(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        String cached = sLatestVersion;
        if (cached != null && now - sLatestAt < VERSION_TTL_MS) {
            return cached;
        }
        try {
            HttpURLConnection conn = open(ctx, OFFICIAL_API, 10000, 10000);
            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
                r.close();
                conn.disconnect();
                String v = normVersion(extractTag(sb.toString()));
                if (v != null && !v.isEmpty()) {
                    sLatestVersion = v;
                    sLatestAt = now;
                    return v;
                }
            } else {
                // 403/429 = rate-limit; pakai cache lama kalau ada.
                conn.disconnect();
            }
        } catch (Exception ignored) {
        }
        return cached;
    }

    /** Unduh & pasang update binary; return pesan hasil. Lempar Exception bila gagal. */
    public static String tryUpdate(Context ctx) throws Exception {
        String latest = latestVersion(ctx);
        if (latest == null) {
            throw new IOException("Tidak bisa baca versi terbaru (cek koneksi/TLS).");
        }
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String updated = sp.getString(ServerService.KEY_UPDATE_VERSION, "");
        String current = normVersion(updated != null && !updated.isEmpty()
                ? updated : readBundledVersionRaw(ctx));
        if (current != null && current.equals(latest)) {
            return "Sudah versi terbaru: v" + latest;
        }

        File out = new File(ctx.getFilesDir(), "bin/vaultwarden-" + ServerService.ABI);
        String msg = downloadBinary(ctx, out);
        return msg;
    }

    /** Unduh binary versi terbaru dari release repo ke out (verifikasi SHA-256).
     *  Dipakai saat Start bila binary belum ada (tidak lagi dibundel di APK). */
    public static String downloadBinary(Context ctx, File out) throws Exception {
        String latest = latestVersion(ctx);
        // Bila API versi sedang gagal (rate-limit/TLS), tetap bisa unduh lewat
        // redirect "latest/download" tanpa perlu tahu nomor versi.
        boolean known = latest != null && !latest.isEmpty();
        String assetUrl = (known ? RELEASE_URL + "v" + latest : RELEASE_LATEST_URL)
                + "vaultwarden-" + ServerService.ABI;
        File binDir = out.getParentFile();
        if (binDir != null && !binDir.exists()) {
            binDir.mkdirs();
        }
        File tmp = new File(binDir, out.getName() + ".tmp");
        HttpURLConnection dl = open(ctx, assetUrl, 20000, 60000);
        int code = dl.getResponseCode();
        if (code == 404) {
            dl.disconnect();
            throw new IOException(known
                    ? "Build Android v" + latest
                            + " belum tersedia (build otomatis ~6 jam). Coba lagi nanti."
                    : "Release binary Android belum tersedia. Coba lagi nanti.");
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
        String expectedSha = fetchChecksum(ctx, assetUrl + ".sha256", 20000, 60000);
        if (expectedSha != null && !matchesSha256(tmp, expectedSha)) {
            tmp.delete();
            throw new IOException("Checksum SHA-256 tidak cocok; update dibatalkan.");
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
        writeVersionTag(binDir, appVersionName(ctx));
        ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                .edit().putString(ServerService.KEY_UPDATE_VERSION,
                        latest != null ? latest : "").apply();
        ServerService.binaryVersion = "";
        return "Update v" + (latest != null ? latest : "?") + " terpasang.";
    }

    /** Tandai cache binary dengan versi APK pemiliknya (untuk reuse saat Start). */
    private static void writeVersionTag(File binDir, String apkVersion) {
        try (FileWriter w = new FileWriter(new File(binDir, "version.txt"))) {
            w.write(apkVersion);
        } catch (Exception ignored) {
        }
    }

    /** Versi vaultwarden pemilik web-vault yang terpasang (penanda), atau null. */
    public static String webVaultFromVersion(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_WV_FROM, "");
        return (v == null || v.isEmpty()) ? null : v;
    }

    public static String appVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
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

        String latest = latestVersion(ctx);

        // Sudah terpasang versi yang sama? Jangan unduh ulang 35 MB.
        boolean wvExists = new File(targetDir, "vw-version.json").exists()
                || new File(targetDir, "index.html").exists();
        String installed = sp.getString(KEY_WV_FROM, "");
        if (wvExists && latest != null) {
            // Penanda lama (sebelum fitur ini): pakai versi server yang terdeteksi.
            String known = !installed.isEmpty() ? installed
                    : ServerService.binaryVersion;
            if (known != null && !known.isEmpty() && known.equals(latest)) {
                if (installed.isEmpty()) {
                    sp.edit().putString(KEY_WV_FROM, latest).apply();
                }
                return "Web vault sudah versi terbaru: v" + latest;
            }
        }
        if (wvExists && latest == null && !installed.isEmpty()) {
            return "Web vault v" + installed
                    + " terpasang; cek versi terbaru gagal (koneksi/rate-limit). Coba lagi nanti.";
        }

        // Cek ruang hanya bila benar-benar akan mengunduh.
        long free = TgBackup.freeBytes(dataDir);
        if (free < MIN_FREE_FOR_WEBVAULT) {
            throw new IOException("Sisa penyimpanan tinggal " + TgBackup.humanBytes(free)
                    + " - butuh minimal 150 MB untuk update web-vault.");
        }

        String zipUrl = latest != null ? RELEASE_URL + "v" + latest + "/web-vault.zip"
                : WV_UPDATE_URL;
        String shaUrl = latest != null ? RELEASE_URL + "v" + latest + "/web-vault.zip.sha256"
                : RELEASE_LATEST_URL + "web-vault.zip.sha256";

        // Unduh dengan retry sekali bila gagal (koneksi Android 5/6 kadang putus).
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpURLConnection dl = open(ctx, zipUrl, 20000, 120000);
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
        String expectedSha = fetchChecksum(ctx, shaUrl, 20000, 60000);
        if (expectedSha != null && !matchesSha256(tmpZip, expectedSha)) {
            tmpZip.delete();
            throw new IOException("Checksum SHA-256 web-vault tidak cocok; update dibatalkan.");
        }

        // Hapus web-vault lama
        deleteRecursive(targetDir);
        targetDir.mkdirs();
        sp.edit().putString(KEY_WV_FROM, latest != null ? latest : "").apply();

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

    /** Baca file .sha256 GitHub (format "<hex>  <nama>"); return hex atau null bila gagal. */
    private static String fetchChecksum(Context ctx, String url,
                                        int connectMs, int readMs) {
        try {
            HttpURLConnection c = open(ctx, url, connectMs, readMs);
            int code = c.getResponseCode();
            if (code != 200) {
                c.disconnect();
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            r.close();
            c.disconnect();
            if (line == null) {
                return null;
            }
            String hex = line.trim().split("\\s+")[0];
            return hex.length() == 64 ? hex.toLowerCase(Locale.US) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Cocokkan SHA-256 file dengan hex yang diharapkan. */
    private static boolean matchesSha256(File f, String expectedHex) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return expectedHex.equals(sb.toString());
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
