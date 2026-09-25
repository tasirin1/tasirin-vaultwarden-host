package com.tasirin.vaultwardenhost;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

    /** URL asset binary untuk versi resmi tertentu; fallback latest bila versi tak dikenal.
     *  Package-private agar bisa diuji unit (regresi slash hilang = HTTP 404 terus). */
    static String binaryAssetUrl(String latest, String abi) {
        if (latest != null && !latest.isEmpty()) {
            return RELEASE_URL + "v" + latest + "/vaultwarden-" + abi;
        }
        return RELEASE_LATEST_URL + "vaultwarden-" + abi;
    }

    /** URL asset shim getrandom untuk versi resmi tertentu; fallback latest bila tak dikenal. */
    static String shimAssetUrl(String latest) {
        if (latest != null && !latest.isEmpty()) {
            return RELEASE_URL + "v" + latest + "/" + KernelCompat.SHIM_ASSET;
        }
        return RELEASE_LATEST_URL + KernelCompat.SHIM_ASSET;
    }

    private static final long MIN_FREE_FOR_WEBVAULT = 150L * 1024 * 1024;
    // Penanda versi vaultwarden pemilik web-vault yang terpasang (supaya tidak
    // mengunduh ulang ~35 MB tiap kali tombol "Update Web Vault" ditekan).
    private static final String KEY_WV_FROM = "wv_from_version";
    // Status unduhan yang sedang berjalan (dibaca UI realtime); kosong = tidak ada unduhan.
    public static volatile String downloadStatus = "";
    // Unduhan di STB sering timeout TCP ke github.com; coba ulang 3x sebelum gagal.
    private static final int MAX_COBA_UNDUH = 3;

    private Updater() {
    }

    /** Buka koneksi HTTPS ke GitHub yang ramah Android 5/6
     *  (TLS 1.2 + trust anchor tambahan + User-Agent).
     *  Redirect hanya diikuti bila tetap https (cegah downgrade ke http). */
    private static HttpURLConnection open(Context ctx, String url,
                                          int connectMs, int readMs) throws Exception {
        return bukaIkutiRedirect(ctx, url, connectMs, readMs, 0);
    }

    /** Buka koneksi unduh; tambah header Range bila melanjutkan file terputus.
     *  Range dipasang sebelum connect agar resume tetap jalan (header sesudah
     *  getResponseCode tak berpengaruh). Redirect bawa ulang header Range. */
    private static HttpURLConnection openRange(Context ctx, String url, long resumeFrom,
                                               int connectMs, int readMs) throws Exception {
        return bukaIkutiRedirect(ctx, url, connectMs, readMs, resumeFrom);
    }

    /** Ikuti redirect manual maks 5x, hanya ke https (cegah downgrade http). */
    private static HttpURLConnection bukaIkutiRedirect(Context ctx, String url,
            int connectMs, int readMs, long resumeFrom) throws Exception {
        String kini = url;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(kini).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) TasirinVaultwardenHost");
            if (resumeFrom > 0) {
                c.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
            }
            HttpsCompat.apply(c, ctx);
            int kode = c.getResponseCode();
            if (kode == 301 || kode == 302 || kode == 303 || kode == 307 || kode == 308) {
                String lok = c.getHeaderField("Location");
                c.disconnect();
                if (lok == null || lok.isEmpty()
                        || !Util.bolehIkutiRedirect(kini, lok)) {
                    throw new IOException("Redirect tidak aman/ditolak: " + lok);
                }
                kini = Util.sambungRedirect(kini, lok);
                continue;
            }
            return c;
        }
        throw new IOException("Terlalu banyak redirect.");
    }

    /** Saran perbaikan koneksi (Bahasa Indonesia) berdasarkan jenis galat. */
    static String saranKoneksi(Exception e) {
        if (e == null) {
            return "Cek internet STB (buka github.com di browser), lalu tekan Start lagi.";
        }
        String gabung = (e.getClass().getSimpleName() + " " + String.valueOf(e.getMessage()))
                .toLowerCase(Locale.US);
        if (gabung.contains("unknownhost") || gabung.contains("no address")
                || gabung.contains("unable to resolve")) {
            return "DNS gagal (nama github.com tidak ketemu). Cek internet STB,"
                    + " coba hotspot HP / ganti DNS, lalu tekan Start lagi.";
        }
        if (isDnsHijackKeIpLokal(gabung) && gabung.contains("github")) {
            return "github.com malah mengarah ke IP lokal/router (bukan IP GitHub asli)."
                    + " DNS dibajak / WiFi pakai portal login / proxy ISP."
                    + " Buka github.com di browser (login dulu bila diminta),"
                    + " coba hotspot HP / ganti DNS, lalu tekan Start lagi.";
        }
        if (gabung.contains("timed out") || gabung.contains("timeout")
                || gabung.contains("failed to connect") || gabung.contains("econn")
                || gabung.contains("unreachable") || gabung.contains("etimedout")) {
            return "Koneksi ke GitHub timeout/diblokir. Cek WiFi ada internet"
                    + " (buka github.com di browser), cek tanggal & jam STB,"
                    + " coba hotspot HP, lalu tekan Start lagi.";
        }
        if (gabung.contains("ssl") || gabung.contains("certificate")
                || gabung.contains("handshake") || gabung.contains("tls")) {
            return "TLS gagal di Android 5/6. Cek tanggal & jam STB sudah benar,"
                    + " lalu coba lagi.";
        }
        if (gabung.contains("403") || gabung.contains("429") || gabung.contains("rate")) {
            return "GitHub membatasi sementara (rate-limit). Tunggu +-15 menit,"
                    + " lalu coba lagi.";
        }
        if (gabung.contains("404") || gabung.contains("belum tersedia")) {
            return "File belum tersedia di rilis (build +-6 jam). Coba lagi nanti.";
        }
        return "Cek internet STB (buka github.com di browser), lalu tekan Start lagi.";
    }

    /** Bungkus galat unduh dengan saran aksi agar log/toast langsung bisa ditindak. */
    static String pesanGalatUnduh(String aksi, Exception e) {
        String inti = (e == null || e.getMessage() == null) ? String.valueOf(e) : e.getMessage();
        String rendah = ((e != null ? e.getClass().getSimpleName() + " " : "")
                + String.valueOf(e != null ? e.getMessage() : "")).toLowerCase(Locale.US);
        if (e instanceof java.util.zip.ZipException
                || rendah.contains("zipexception")
                || rendah.contains("invalid stored block")
                || rendah.contains("invalid block")
                || rendah.contains("not a zip")) {
            return aksi + " gagal: file zip korup (" + inti + "). File parsial dihapus,"
                    + " aman diulang. " + saranKoneksi(e);
        }
        return aksi + " gagal: " + inti + ". " + saranKoneksi(e);
    }

    /** True bila pesan galat menunjukkan github.com resolve ke IP lokal/router
     *  (mis. "failed to connect to github.com/192.168.100.1") — pola khas DNS
     *  dibajak / captive portal / proxy ISP, bukan IP GitHub asli. */
    static boolean isDnsHijackKeIpLokal(String pesanRendah) {
        if (pesanRendah == null) {
            return false;
        }
        return pesanRendah.contains("/192.168.") || pesanRendah.contains("/10.")
                || pesanRendah.contains("/172.16.") || pesanRendah.contains("/172.17.")
                || pesanRendah.contains("/172.18.") || pesanRendah.contains("/172.19.")
                || pesanRendah.contains("/172.2") || pesanRendah.contains("/172.30.")
                || pesanRendah.contains("/172.31.");
    }

    /** True bila unduhan lanjutan harus diulang dari nol: server menolak Range
     *  (HTTP 416) atau mengabaikannya (HTTP 200 padahal kirim Range). */
    static boolean perluResetResume(int kodeHttp, long lanjutDari) {
        return lanjutDari > 0 && (kodeHttp == 416 || kodeHttp == 200);
    }

    /** Klasifikasi galat unduh yang layak dicoba ulang (dipakai binary & shim).
     *  Murni agar bisa unit test. */
    static boolean bolehCobaLagiUnduh(Exception e) {
        String rendah = String.valueOf(e == null ? null : e.getMessage()).toLowerCase(Locale.US);
        // 404 versi (file memang belum ada) jangan di-retry sia-sia.
        if (rendah.contains("404") || rendah.contains("belum tersedia")) {
            return false;
        }
        return rendah.contains("timed out") || rendah.contains("timeout")
                || rendah.contains("failed to connect") || rendah.contains("econn")
                || rendah.contains("unreachable") || rendah.contains("reset")
                || rendah.contains("broken pipe") || rendah.contains("http");
    }

    /** Tunda 3 detik sebelum percobaan unduh berikut; false bila diinterupsi. */
    static boolean tundaCobaLagiUnduh(int coba) {
        downloadStatus = "Koneksi putus, coba lagi " + (coba + 1) + "/" + MAX_COBA_UNDUH + "...";
        try {
            Thread.sleep(3000);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Hook fallback URL bila unduhan 404 (mis. rilis versi belum memuat asset).
     *  Return URL pengganti, atau null bila tidak ada. Boleh lempar IOException
     *  berpesan final (mis. "belum tersedia") agar langsung gagal manis. */
    interface UrlCadangan {
        String ganti(String url, int kode) throws IOException;
    }

    /** Unduh satu file ke tmp dengan resume + retry + hash (dipakai binary,
     *  shim, dan web-vault agar tiga loop ~60 baris tak duplikat).
     *  Return hex SHA-256; lempar IOException terakhir bila gagal. */
    static String unduhKeTmp(Context ctx, String url, File tmp, String label,
                              int connectMs, int readMs, UrlCadangan cadangan)
            throws IOException {
        Exception gagal = null;
        String originalUrl = url;  // Simpan URL asli untuk retry
        boolean fallbackUsed = false;
        for (int coba = 1; coba <= MAX_COBA_UNDUH; coba++) {
            // Lanjutkan unduhan terputus (hemat kuota); server GitHub dukung Range.
            long resumeFrom = tmp.exists() ? tmp.length() : 0;
            HttpURLConnection dl = null;
            try {
                dl = openRange(ctx, url, resumeFrom, connectMs, readMs);
                int code = dl.getResponseCode();
                if (code == 404 && cadangan != null && !fallbackUsed) {
                    String alt = cadangan.ganti(url, code);
                    if (alt != null && !alt.equals(url)) {
                        dl.disconnect();
                        dl = null;
                        resumeFrom = 0;
                        tmp.delete();
                        url = alt;
                        fallbackUsed = true;
                        dl = open(ctx, url, connectMs, readMs);
                        code = dl.getResponseCode();
                    }
                }
                if (perluResetResume(code, resumeFrom)) {
                    // HTTP 416 = Range ditolak; HTTP 200 = server mengabaikan
                    // Range. Ulang dari nol agar file tidak korup.
                    dl.disconnect();
                    tmp.delete();
                    resumeFrom = 0;
                    dl = open(ctx, url, connectMs, readMs);
                    code = dl.getResponseCode();
                }
                if (code != 200 && code != 206) {
                    throw new IOException("Unduhan gagal (HTTP " + code + ").");
                }
                return salinSambilHash(dl, tmp, code, resumeFrom, label);
            } catch (IOException e) {
                gagal = e;
                // Bila fallback sudah dipakai dan gagal, coba URL asli di percobaan berikut.
                // Isi URL beda wajib mulai dari nol: buang parsial agar tak campur
                // (sebelumnya resume menumpuk bytes 2 asset + hash prefix salah).
                if (fallbackUsed && coba < MAX_COBA_UNDUH) {
                    url = originalUrl;
                    fallbackUsed = false;
                    try {
                        tmp.delete();
                    } catch (Exception ignored) {
                    }
                }
                if (!bolehCobaLagiUnduh(e) || coba >= MAX_COBA_UNDUH
                        || !tundaCobaLagiUnduh(coba)) {
                    break;
                }
            } catch (Exception e) {
                gagal = e;
                break;
            } finally {
                if (dl != null) {
                    dl.disconnect();
                }
            }
        }
        if (gagal instanceof IOException) {
            throw (IOException) gagal;
        }
        throw new IOException(gagal == null ? "koneksi gagal" : String.valueOf(gagal));
    }

    /** Salin body unduhan ke file sementara sambil menghitung SHA-256
     *  (dipakai binary & shim agar tak baca ulang file ~15 MB). Return hex digest. */
    static String salinSambilHash(HttpURLConnection dl, File tmp, int kodeHttp,
                                  long lanjutDari, String label) throws IOException {
        long total = dl.getContentLength();
        if (kodeHttp == 206 && total >= 0) {
            total += lanjutDari;
        }
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 tidak tersedia: " + e.getMessage());
        }
        if (lanjutDari > 0) {
            digestPrefix(md, tmp, lanjutDari);
        }
        try (InputStream in = dl.getInputStream();
             FileOutputStream fos = new FileOutputStream(tmp, kodeHttp == 206)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            long done = lanjutDari;
            long lastReport = done;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                if (done - lastReport >= 256 * 1024) {
                    lastReport = done;
                    reportDownload(label, done, total);
                }
            }
        }
        return toHex(md.digest());
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
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line);
                    }
                }
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
            throw new IOException("Tidak bisa baca versi terbaru. " + saranKoneksi(null));
        }
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        // Revisi patch binary: CI bisa memperbaiki binary tanpa ganti versi Vaultwarden
        // (mis. patch TLS favicon). Versi sama tapi patch lama wajib diunduh ulang sekali.
        boolean butuhRefresh = ServerService.perluRefreshPatch(
                sp.getString(ServerService.KEY_BIN_PATCH, ""));
        String real = parseBinaryVersion(ServerService.binaryVersion);
        if (!butuhRefresh && real != null && real.equals(latest)) {
            // Binary asli sudah terbaru tapi penanda basi - perbaiki agar popup tidak looping.
            sp.edit().putString(ServerService.KEY_UPDATE_VERSION, latest).apply();
            return "Sudah versi terbaru: v" + latest;
        }
        String updated = sp.getString(ServerService.KEY_UPDATE_VERSION, "");
        String current = real != null ? real : normVersion(updated != null && !updated.isEmpty()
                ? updated : readBundledVersionRaw(ctx));
        if (!butuhRefresh && current != null && current.equals(latest)) {
            return "Sudah versi terbaru: v" + latest;
        }

        File out = new File(ctx.getFilesDir(), "bin/vaultwarden-" + ServerService.ABI);
        String msg = downloadBinary(ctx, out);
        sp.edit().putString(ServerService.KEY_BIN_PATCH,
                String.valueOf(ServerService.BIN_PATCH_REV)).apply();
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
        String assetUrl = binaryAssetUrl(latest, ServerService.ABI);
        File binDir = out.getParentFile();
        if (binDir != null && !binDir.exists()) {
            binDir.mkdirs();
        }
        File tmp = new File(binDir, out.getName() + ".tmp");
        // Unduh dengan retry (koneksi STB/Android 6 sering timeout TCP ke github.com).
        // File parsial dipertahankan agar percobaan berikut melanjutkan via Range.
        final boolean[] pakaiTerbaru = {false};
        final String[] urlAkhir = {assetUrl};
        String digestHex;
        try {
            digestHex = unduhKeTmp(ctx, assetUrl, tmp, "binary", 20000, 60000,
                    new UrlCadangan() {
                        @Override
                        public String ganti(String url, int kode) throws IOException {
                            if (known && !pakaiTerbaru[0] && url.equals(urlAkhir[0])) {
                                // Rilis versi ini belum ada / sedang dibuat ulang CI -
                                // pakai binary rilis terbaru repo agar tetap bisa Start.
                                pakaiTerbaru[0] = true;
                                urlAkhir[0] = RELEASE_LATEST_URL + "vaultwarden-"
                                        + ServerService.ABI;
                                return urlAkhir[0];
                            }
                            throw new IOException(known
                                    ? "Build Android v" + latest + " belum tersedia"
                                            + " (build otomatis ~6 jam). Coba lagi nanti."
                                    : "Release binary Android belum tersedia. Coba lagi nanti.");
                        }
                    });
        } catch (IOException e) {
            if (String.valueOf(e.getMessage()).contains("belum tersedia")) {
                throw e;
            }
            throw new IOException(pesanGalatUnduh("Unduh binary", e));
        }
        boolean fallback = pakaiTerbaru[0];
        String expectedSha = fetchChecksum(ctx, urlAkhir[0] + ".sha256", 20000, 60000);
        if (expectedSha == null) {
            // File parsial dipertahankan agar bisa dilanjutkan (gagal ambil checksum
            // biasanya soal jaringan, bukan file korup).
            throw new IOException("Checksum SHA-256 tidak ditemukan di release"
                    + " - update dibatalkan demi keamanan. " + saranKoneksi(null));
        }
        if (!expectedHexEquals(expectedSha, digestHex)) {
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
        String installed = detectVersion(ctx, out);
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

    /** True bila file shim valid (ELF + ukuran wajar). */
    static boolean shimValid(File f) {
        return f != null && f.exists()
                && f.length() >= KernelCompat.SHIM_MIN_BYTES && isElf(f);
    }

    /** Pastikan shim getrandom tersedia (khusus kernel lama); unduh bila belum ada/rusak.
     *  Return file shim. Lempar IOException berbahasa Indonesia bila gagal. */
    public static File ensureShimFile(Context ctx) throws Exception {
        File out = new File(ctx.getFilesDir(), "bin/" + KernelCompat.SHIM_ASSET);
        if (shimValid(out) && appVersionName(ctx).equals(bacaTagShim(out.getParentFile()))) {
            return out;
        }
        downloadShim(ctx, out);
        if (!shimValid(out)) {
            throw new IOException("File shim tidak valid (ukuran "
                    + (out.exists() ? out.length() : 0) + " byte).");
        }
        return out;
    }

    /** Unduh shim getrandom dari release repo ke out (verifikasi SHA-256). */
    public static String downloadShim(Context ctx, File out) throws Exception {
        try {
            return downloadShimInner(ctx, out);
        } finally {
            downloadStatus = "";
        }
    }

    private static String downloadShimInner(Context ctx, File out) throws Exception {
        String latest = latestVersion(ctx);
        boolean known = latest != null && !latest.isEmpty();
        String assetUrl = shimAssetUrl(latest);
        File binDir = out.getParentFile();
        if (binDir != null && !binDir.exists()) {
            binDir.mkdirs();
        }
        File tmp = new File(binDir, out.getName() + ".tmp");
        final String[] urlShimAkhir = {assetUrl};
        String digestHex;
        try {
            digestHex = unduhKeTmp(ctx, assetUrl, tmp, "shim", 20000, 30000,
                    new UrlCadangan() {
                        @Override
                        public String ganti(String url, int kode) throws IOException {
                            if (known && !url.startsWith(RELEASE_LATEST_URL)) {
                                // Rilis versi ini belum memuat shim -
                                // coba rilis terbaru repo seperti binary.
                                urlShimAkhir[0] = RELEASE_LATEST_URL + KernelCompat.SHIM_ASSET;
                                return urlShimAkhir[0];
                            }
                            if (!known) {
                                return null;
                            }
                            throw new IOException("Shim getrandom belum tersedia di rilis v"
                                    + latest + " (build CI ~6 jam). Coba lagi nanti.");
                        }
                    });
        } catch (IOException e) {
            if (String.valueOf(e.getMessage()).contains("belum tersedia")) {
                throw e;
            }
            throw new IOException(pesanGalatUnduh("Unduh shim", e));
        }
        String expectedSha = fetchChecksum(ctx, urlShimAkhir[0] + ".sha256", 20000, 30000);
        if (expectedSha == null) {
            throw new IOException("Checksum SHA-256 tidak ditemukan di release"
                    + " - update dibatalkan demi keamanan. " + saranKoneksi(null));
        }
        if (!expectedHexEquals(expectedSha, digestHex)) {
            tmp.delete();
            throw new IOException("Checksum SHA-256 tidak cocok; update dibatalkan.");
        }
        if (tmp.length() < KernelCompat.SHIM_MIN_BYTES || !isElf(tmp)) {
            long ukuran = tmp.length();
            tmp.delete();
            throw new IOException("File shim tidak valid (ukuran " + ukuran + " byte).");
        }
        if (out.exists()) {
            out.delete();
        }
        if (!tmp.renameTo(out)) {
            tmp.delete();
            throw new IOException("Gagal menyimpan shim.");
        }
        out.setReadable(true, true);
        out.setExecutable(true, true);
        tulisTagShim(binDir, appVersionName(ctx));
        return "Shim getrandom terpasang.";
    }

    /** Tandai shim dengan versi APK pemiliknya (untuk reuse saat Start). */
    private static void tulisTagShim(File binDir, String apkVersion) {
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new FileOutputStream(new File(binDir, "shim-tag.txt"), false),
                StandardCharsets.UTF_8)) {
            w.write(apkVersion);
        } catch (Exception ignored) {
        }
    }

    /** Tag APK pemilik shim, atau null bila belum pernah dipasang. */
    private static String bacaTagShim(File binDir) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(binDir, "shim-tag.txt")), StandardCharsets.UTF_8))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
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
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new FileOutputStream(new File(binDir, "version.txt"), false),
                StandardCharsets.UTF_8)) {
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

        // Unduh dengan retry (koneksi STB/Android 6 sering timeout ke github.com).
        // File parsial dipertahankan agar percobaan berikut melanjutkan via Range.
        // Tanpa probe 404 terpisah: fallback latest ditangani di loop (hemat 1 request).
        final boolean[] wvLewatTerbaru = {false};
        final String[] urlZipAkhir = {zipUrl};
        String wvDigestHex;
        try {
            wvDigestHex = unduhKeTmp(ctx, zipUrl, tmpZip, "web vault", 20000, 120000,
                    new UrlCadangan() {
                        @Override
                        public String ganti(String url, int kode) {
                            if (latest != null && !wvLewatTerbaru[0]
                                    && url.equals(urlZipAkhir[0])) {
                                wvLewatTerbaru[0] = true;
                                urlZipAkhir[0] = WV_UPDATE_URL;
                                return urlZipAkhir[0];
                            }
                            return null;
                        }
                    });
        } catch (IOException e) {
            throw new IOException(pesanGalatUnduh("Unduh web-vault", e));
        }
        boolean wvFallback = wvLewatTerbaru[0];
        if (wvFallback) {
            shaUrl = RELEASE_LATEST_URL + "web-vault.zip.sha256";
        }
        if (tmpZip.length() < 1000) {
            tmpZip.delete();
            throw new IOException(pesanGalatUnduh("Unduh web-vault",
                    new IOException("file tidak valid")));
        }
        String expectedSha = fetchChecksum(ctx, shaUrl, 20000, 60000);
        if (expectedSha == null) {
            // Zip parsial dipertahankan agar bisa dilanjutkan via Range.
            throw new IOException("Checksum SHA-256 web-vault tidak ditemukan"
                    + " - update dibatalkan demi keamanan. " + saranKoneksi(null));
        }
        if (!expectedHexEquals(expectedSha, wvDigestHex)) {
            tmpZip.delete();
            throw new IOException("Checksum SHA-256 web-vault tidak cocok; file dihapus,"
                    + " aman diulang. " + saranKoneksi(null));
        }

        // Ekstrak ke folder sementara dulu; web-vault lama baru diganti bila
        // hasil ekstrak valid (hindari tanpa web UI saat unduhan korup).
        File newDir = new File(dataFolder, "web-vault.new");
        deleteRecursive(newDir);
        newDir.mkdirs();
        String kanonBasis;
        try {
            kanonBasis = newDir.getCanonicalPath();
        } catch (Exception e) {
            deleteRecursive(newDir);
            throw new IOException("Gagal menyiapkan folder web-vault: " + e.getMessage());
        }
        String awalanAman = kanonBasis + File.separator;
        byte[] buf = new byte[64 * 1024];
        long totalUnzip = 0;
        int jumlahEntri = 0;
        try {
            try (ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(tmpZip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String namaEntri = entry.getName();
                    // Cek zip-slip leksikal tanpa syscall canonical per entri.
                    if (!amanEntriZip(namaEntri)) {
                        continue;
                    }
                    File outFile = new File(newDir, namaEntri);
                    // Verifikasi kanonis agar symlink/entri licik tak keluar folder.
                    try {
                        String kanon = outFile.getCanonicalPath();
                        if (!kanon.equals(kanonBasis) && !kanon.startsWith(awalanAman)) {
                            zis.closeEntry();
                            continue;
                        }
                    } catch (Exception e) {
                        try {
                            zis.closeEntry();
                        } catch (Exception ignored) {
                        }
                        continue;
                    }
                    jumlahEntri++;
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
                                totalUnzip = Util.tambahUkuranUnzip(totalUnzip, n,
                                        Util.BATAS_UNZIP_WEBVAULT, jumlahEntri,
                                        Util.BATAS_JUMLAH_ENTRI);
                                fos.write(buf, 0, n);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }
        } catch (java.util.zip.ZipException e) {
            // Zip korup di tengah ekstrak (mis. "invalid stored block lengths"
            // karena unduhan terpotong): buang hasil + sisa zip agar Start
            // berikutnya unduh ulang bersih, versi lama tetap dipakai.
            deleteRecursive(newDir);
            tmpZip.delete();
            throw new IOException(pesanGalatUnduh("Unduh web-vault", e));
        } catch (IOException e) {
            String rendah = (e.getClass().getSimpleName() + " " + String.valueOf(e.getMessage()))
                    .toLowerCase(Locale.US);
            if (rendah.contains("zipexception") || rendah.contains("stored block")
                    || rendah.contains("invalid block") || rendah.contains("eocd")
                    || rendah.contains("truncated") || rendah.contains("not a zip")) {
                deleteRecursive(newDir);
                tmpZip.delete();
                throw new IOException(pesanGalatUnduh("Unduh web-vault", e));
            }
            deleteRecursive(newDir);
            throw e;
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

    private static volatile String sBundledVersion;
    private static volatile boolean sBundledLoaded;

    /** Versi Vaultwarden yang dibundel di APK (tanpa huruf v) atau null.
     *  Dibaca sekali lalu cache (konstan selama runtime; dipanggil tiap detik UI). */
    public static String readBundledVersionRaw(Context ctx) {
        if (sBundledLoaded) {
            return sBundledVersion;
        }
        String v = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("vw_version.txt"), StandardCharsets.UTF_8))) {
            String baris = r.readLine();
            v = (baris == null || baris.trim().isEmpty()) ? null : baris.trim();
        } catch (Exception ignored) {
        }
        sBundledVersion = v;
        sBundledLoaded = true;
        return v;
    }

    /** Versi binary yang benar-benar dipakai server saat ini (x.y.z). */
    public static String currentServerVersion(Context ctx) {
        String real = parseBinaryVersion(ServerService.binaryVersion);
        if (real != null) {
            return real;
        }
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String updated = sp.getString(ServerService.KEY_UPDATE_VERSION, "");
        if (updated != null && !updated.isEmpty()) {
            return updated;
        }
        return readBundledVersionRaw(ctx);
    }

    /** Versi dari file vw-version.json web-vault, atau null bila tak terbaca. */
    public static String readWvVersion(File f) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            String v = new org.json.JSONObject(sb.toString()).optString("version", "");
            return v.isEmpty() ? null : v;
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

    // Pola versi di-compile sekali (dipanggil tiap detik dari UI & status web).
    private static final java.util.regex.Pattern VERSION_PATTERN =
            java.util.regex.Pattern.compile("\\d+\\.\\d+\\.\\d+");

    /** Ambil "x.y.z" dari output "--version" ("vaultwarden 1.37.3" -> "1.37.3"). */
    static String parseBinaryVersion(String raw) {
        if (raw == null) {
            return null;
        }
        java.util.regex.Matcher m = VERSION_PATTERN.matcher(raw);
        return m.find() ? m.group() : null;
    }

    /** Jalankan binary --version; kembalikan "x.y.z" atau null bila gagal. */
    static String detectVersion(File binary) {
        return detectVersion(null, binary);
    }

    /** Sama, tapi memakai shim getrandom bila perangkat kernel lama (agar --version lolos). */
    static String detectVersion(Context ctx, File binary) {
        try {
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(), "--version")
                    .redirectErrorStream(true);
            if (ctx != null && KernelCompat.isLegacyDevice(KernelCompat.kernelSekarang())) {
                File shim = new File(ctx.getFilesDir(), "bin/" + KernelCompat.SHIM_ASSET);
                if (shim.exists()) {
                    pb.environment().put("LD_PRELOAD", shim.getAbsolutePath());
                }
            }
            Process p = pb.start();
            // Baca sampai baris bermakna: baris pertama di STB lama adalah
            // noise linker ("WARNING: linker: ..."), bukan versi.
            String first = null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                String baris;
                for (int i = 0; i < 10 && (baris = r.readLine()) != null; i++) {
                    if (ServerService.isNoiseLinker(baris)) {
                        continue;
                    }
                    first = baris;
                    break;
                }
            }
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
        for (int coba = 1; coba <= 2; coba++) {
            HttpURLConnection c = null;
            try {
                c = open(ctx, url, connectMs, readMs);
                if (c.getResponseCode() != 200) {
                    return null;
                }
                String line;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        c.getInputStream(), StandardCharsets.UTF_8))) {
                    line = r.readLine();
                }
                if (line == null) {
                    return null;
                }
                String hex = line.trim().split("\\s+")[0];
                return hex.length() == 64 ? hex.toLowerCase(Locale.US) : null;
            } catch (Exception e) {
                if (coba >= 2) {
                    return null;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
        return null;
    }

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /** Byte -> hex kecil tanpa String.format per byte (32x lebih murah). */
    static String toHex(byte[] digest) {
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int v = digest[i] & 0xFF;
            out[i * 2] = HEX_DIGITS[v >>> 4];
            out[i * 2 + 1] = HEX_DIGITS[v & 0x0F];
        }
        return new String(out);
    }

    /** Hash awalan file yang sudah terunduh (untuk unduhan lanjutan/Range). */
    private static void digestPrefix(java.security.MessageDigest md, File f, long len)
            throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            long left = len;
            int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                md.update(buf, 0, n);
                left -= n;
            }
        }
    }

    /** Banding hex checksum tanpa alokasi string sementara (case-insensitive). */
    static boolean expectedHexEquals(String expectedHex, String gotHex) {
        return gotHex != null && expectedHex != null
                && expectedHex.trim().equalsIgnoreCase(gotHex);
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
            return toHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /** True bila nama entri zip aman dari zip-slip (tanpa I/O canonical).
     *  Murni agar bisa unit test. */
    static boolean amanEntriZip(String nama) {
        if (nama == null || nama.isEmpty()) {
            return false;
        }
        String n = nama.replace('\\', '/');
        if (n.isEmpty() || n.charAt(0) == '/' || n.contains(":")
                || n.equals("..") || n.startsWith("../")
                || n.contains("/../") || n.endsWith("/..")) {
            return false;
        }
        // Tolak entri "." atau "./" (current dir) yang bisa berbahaya saat diekstrak
        if (n.equals(".") || n.startsWith("./")) {
            return false;
        }
        return true;
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
