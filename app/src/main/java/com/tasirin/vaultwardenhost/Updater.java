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
    // Status unduhan yang sedang berjalan (dibaca UI realtime); kosong = tidak ada unduhan.
    public static volatile String downloadStatus = "";

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

    /** Buka koneksi unduh; tambah header Range bila melanjutkan file terputus. */
    private static HttpURLConnection openRange(Context ctx, String url, long resumeFrom,
                                               int connectMs, int readMs) throws Exception {
        HttpURLConnection c = open(ctx, url, connectMs, readMs);
        if (resumeFrom > 0) {
            c.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
        }
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
        HttpURLConnection conn = null;
        try {
            conn = open(ctx, OFFICIAL_API, 10000, 10000);
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
                String v = normVersion(extractTag(sb.toString()));
                if (v != null && !v.isEmpty()) {
                    sLatestVersion = v;
                    sLatestAt = now;
                    return v;
                }
            }
            // Kode lain (mis. 403/429 rate-limit): pakai cache lama kalau ada.
        } catch (Exception ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
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
        String real = parseBinaryVersion(ServerService.binaryVersion);
        if (real != null && real.equals(latest)) {
            // Binary asli sudah terbaru tapi penanda basi - perbaiki agar popup tidak looping.
            sp.edit().putString(ServerService.KEY_UPDATE_VERSION, latest).apply();
            return "Sudah versi terbaru: v" + latest;
        }
        String updated = sp.getString(ServerService.KEY_UPDATE_VERSION, "");
        String current = real != null ? real : normVersion(updated != null && !updated.isEmpty()
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
        try {
            return downloadBinaryInner(ctx, out);
        } finally {
            downloadStatus = "";
        }
    }

    private static String downloadBinaryInner(Context ctx, File out) throws Exception {
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
        // Lanjutkan unduhan terputus (hemat kuota); server GitHub dukung Range.
        long resumeFrom = tmp.exists() ? tmp.length() : 0;
        HttpURLConnection dl = openRange(ctx, assetUrl, resumeFrom, 20000, 60000);
        int code = dl.getResponseCode();
        boolean fallback = false;
        if (code == 404 && known) {
            // Rilis versi ini belum ada / sedang dibuat ulang CI -
            // pakai binary rilis terbaru repo agar tetap bisa Start.
            dl.disconnect();
            resumeFrom = 0;
            tmp.delete();
            assetUrl = RELEASE_LATEST_URL + "vaultwarden-" + ServerService.ABI;
            dl = open(ctx, assetUrl, 20000, 60000);
            code = dl.getResponseCode();
            fallback = code == 200;
        }
        if (code == 404) {
            dl.disconnect();
            throw new IOException(known
                    ? "Build Android v" + latest
                            + " belum tersedia (build otomatis ~6 jam). Coba lagi nanti."
                    : "Release binary Android belum tersedia. Coba lagi nanti.");
        }
        if (code == 200 && resumeFrom > 0) {
            // Server mengabaikan Range - mulai dari nol agar tidak korup.
            dl.disconnect();
            tmp.delete();
            resumeFrom = 0;
            dl = open(ctx, assetUrl, 20000, 60000);
            code = dl.getResponseCode();
        }
        if (code != 200 && code != 206) {
            dl.disconnect();
            throw new IOException("Unduhan gagal (HTTP " + code + ").");
        }
        long total = dl.getContentLength();
        if (code == 206 && total >= 0) {
            total += resumeFrom;
        }
        try (InputStream in = dl.getInputStream();
             FileOutputStream fos = new FileOutputStream(tmp, code == 206)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            long done = resumeFrom;
            long lastReport = done;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                done += n;
                if (done - lastReport >= 256 * 1024) {
                    lastReport = done;
                    reportDownload("binary", done, total);
                }
            }
        } finally {
            dl.disconnect();
        }
        String expectedSha = fetchChecksum(ctx, assetUrl + ".sha256", 20000, 60000);
        if (expectedSha == null) {
            tmp.delete();
            throw new IOException("Checksum SHA-256 tidak ditemukan di release"
                    + " - update dibatalkan demi keamanan. Coba lagi nanti.");
        }
        if (!matchesSha256(tmp, expectedSha)) {
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
        // ownerOnly=true: hanya UID app yang membaca binary — tidak world-readable.
        out.setReadable(true, true);
        out.setExecutable(true, true);
        writeVersionTag(binDir, appVersionName(ctx));
        String installed = detectVersion(out);
        String effective = installed != null ? installed
                : (!fallback ? latest : null);
        if (effective != null && !effective.isEmpty()) {
            ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(ServerService.KEY_UPDATE_VERSION, effective).apply();
        }
        ServerService.binaryVersion = "";
        if (installed != null) {
            return "Update v" + installed + " terpasang.";
        }
        return fallback
                ? "Binary rilis terbaru terpasang (v" + latest + " belum tersedia di repo)."
                : "Update v" + (latest != null ? latest : "?") + " terpasang.";
    }

    /** Perbarui status unduhan untuk UI (persen + ukuran bila total diketahui). */
    private static void reportDownload(String label, long done, long total) {
        if (total > 0) {
            int pct = (int) (done * 100 / total);
            downloadStatus = "Unduh " + label + " " + TgBackup.humanBytes(done)
                    + "/" + TgBackup.humanBytes(total) + " (" + pct + "%)";
        } else {
            downloadStatus = "Unduh " + label + " " + TgBackup.humanBytes(done) + "...";
        }
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

    // getPackageInfo lama sengaja agar satu jalur kode untuk API 21-32.
    @SuppressWarnings("deprecation")
    public static String appVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Unduh & ekstrak web-vault ke folder data; return pesan hasil. */
    public static String updateWebVault(Context ctx) throws Exception {
        try {
            return updateWebVaultInner(ctx);
        } finally {
            downloadStatus = "";
        }
    }

    private static String updateWebVaultInner(Context ctx) throws Exception {
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
                    : parseBinaryVersion(ServerService.binaryVersion);
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
        if (free >= 0 && free < MIN_FREE_FOR_WEBVAULT) {
            throw new IOException("Sisa penyimpanan tinggal " + TgBackup.humanBytes(free)
                    + " - butuh minimal 150 MB untuk update web-vault.");
        }

        String zipUrl = latest != null ? RELEASE_URL + "v" + latest + "/web-vault.zip"
                : WV_UPDATE_URL;
        String shaUrl = latest != null ? RELEASE_URL + "v" + latest + "/web-vault.zip.sha256"
                : RELEASE_LATEST_URL + "web-vault.zip.sha256";

        // Unduh dengan retry sekali bila gagal (koneksi Android 5/6 kadang putus).
        boolean wvFallback = false;
        if (latest != null) {
            HttpURLConnection probe = null;
            try {
                probe = open(ctx, zipUrl, 20000, 20000);
                if (probe.getResponseCode() == 404) {
                    zipUrl = WV_UPDATE_URL;
                    shaUrl = RELEASE_LATEST_URL + "web-vault.zip.sha256";
                    wvFallback = true;
                }
            } finally {
                if (probe != null) {
                    probe.disconnect();
                }
            }
        }
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            // Lanjutkan unduhan terputus (hemat kuota ~35 MB).
            long resumeFrom = tmpZip.exists() ? tmpZip.length() : 0;
            HttpURLConnection dl = null;
            try {
                dl = openRange(ctx, zipUrl, resumeFrom, 20000, 120000);
                int code = dl.getResponseCode();
                if (code == 200 && resumeFrom > 0) {
                    dl.disconnect();
                    tmpZip.delete();
                    resumeFrom = 0;
                    dl = open(ctx, zipUrl, 20000, 120000);
                    code = dl.getResponseCode();
                }
                if (code != 200 && code != 206) {
                    throw new IOException("Gagal unduh web-vault (HTTP " + code
                            + ") dari " + dl.getURL());
                }
                long total = dl.getContentLength();
                if (code == 206 && total >= 0) {
                    total += resumeFrom;
                }
                try (InputStream in = dl.getInputStream();
                     FileOutputStream fos = new FileOutputStream(tmpZip, code == 206)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    long done = resumeFrom;
                    long lastReport = done;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        done += n;
                        if (done - lastReport >= 256 * 1024) {
                            lastReport = done;
                            reportDownload("web vault", done, total);
                        }
                    }
                }
                lastErr = null;
                break;
            } catch (Exception e) {
                lastErr = e;
                if (attempt < 2) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                if (dl != null) {
                    dl.disconnect();
                }
            }
        }
        if (tmpZip.length() < 1000) {
            tmpZip.delete();
            throw lastErr != null ? lastErr
                    : new IOException("File web-vault tidak valid.");
        }
        String expectedSha = fetchChecksum(ctx, shaUrl, 20000, 60000);
        if (expectedSha == null) {
            tmpZip.delete();
            throw new IOException("Checksum SHA-256 web-vault tidak ditemukan"
                    + " - update dibatalkan demi keamanan. Coba lagi nanti.");
        }
        if (!matchesSha256(tmpZip, expectedSha)) {
            tmpZip.delete();
            throw new IOException("Checksum SHA-256 web-vault tidak cocok; update dibatalkan.");
        }

        // Ekstrak ke folder sementara dulu; web-vault lama baru diganti bila
        // hasil ekstrak valid (hindari tanpa web UI saat unduhan korup).
        File newDir = new File(dataFolder, "web-vault.new");
        deleteRecursive(newDir);
        newDir.mkdirs();
        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(tmpZip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(newDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(newDir.getCanonicalPath())) {
                    continue;
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
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

        File index = new File(newDir, "index.html");
        if (!index.exists()) {
            deleteRecursive(newDir);
            throw new IOException("Web vault updated tapi index.html tidak ditemukan"
                    + " - versi lama dipertahankan.");
        }
        deleteRecursive(targetDir);
        if (!newDir.renameTo(targetDir)) {
            deleteRecursive(newDir);
            throw new IOException("Gagal memasang web vault baru"
                    + " - versi lama dipertahankan.");
        }
        if (latest != null && !wvFallback) {
            sp.edit().putString(KEY_WV_FROM, latest).apply();
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

    /** Ambil "x.y.z" dari output "--version" ("vaultwarden 1.37.3" -> "1.37.3"). */
    static String parseBinaryVersion(String raw) {
        if (raw == null) {
            return null;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\d+\\.\\d+\\.\\d+").matcher(raw);
        return m.find() ? m.group() : null;
    }

    /** Jalankan binary --version; kembalikan "x.y.z" atau null bila gagal. */
    static String detectVersion(File binary) {
        try {
            Process p = new ProcessBuilder(binary.getAbsolutePath(), "--version")
                    .redirectErrorStream(true)
                    .start();
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8));
            String first = r.readLine();
            r.close();
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                p.destroy();
            }
            return parseBinaryVersion(first);
        } catch (Exception e) {
            return null;
        }
    }

    /** Package-private agar bisa diuji unit (tanpa jaringan). */
    static String extractTag(String body) {
        if (body == null) {
            return null;
        }
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
        HttpURLConnection c = null;
        try {
            c = open(ctx, url, connectMs, readMs);
            if (c.getResponseCode() != 200) {
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            r.close();
            if (line == null) {
                return null;
            }
            String hex = line.trim().split("\\s+")[0];
            return hex.length() == 64 ? hex.toLowerCase(Locale.US) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** SHA-256 file sebagai hex kecil; null bila gagal dibaca. */
    static String sha256Hex(File f) {
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
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Cocokkan SHA-256 file dengan hex yang diharapkan. */
    private static boolean matchesSha256(File f, String expectedHex) {
        String got = sha256Hex(f);
        return got != null && expectedHex.equals(got);
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
