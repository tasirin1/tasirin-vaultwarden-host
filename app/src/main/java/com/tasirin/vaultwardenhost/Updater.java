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

public final class Updater {

    private static final String OFFICIAL_API =
            "https://api.github.com/repos/dani-garcia/vaultwarden/releases/latest";

    private static final String RELEASE_URL =
            "https://github.com/tasirin1/tasirin-vaultwarden-host/releases/download/";
    private static final String RELEASE_LATEST_URL =
            "https://github.com/tasirin1/tasirin-vaultwarden-host/releases/latest/download/";
    private static final String WV_UPDATE_URL =
            RELEASE_LATEST_URL + "web-vault.zip";

    static String binaryAssetUrl(String latest, String abi) {
        String t = latest == null ? "" : latest.trim();
        if (t.startsWith("v") || t.startsWith("V")) {
            t = t.substring(1);
        }
        if (!t.isEmpty()) {
            return RELEASE_URL + "v" + t + "/vaultwarden-" + abi;
        }
        return RELEASE_LATEST_URL + "vaultwarden-" + abi;
    }

    static String binaryFallbackUrl() {
        return RELEASE_LATEST_URL + "vaultwarden-" + ServerService.ABI;
    }

    static String urlAwalUnduhBinary(boolean fallbackTercatat, String assetUrl) {
        return fallbackTercatat ? binaryFallbackUrl() : assetUrl;
    }

    static String shimAssetUrl(String latest) {
        String t = latest == null ? "" : latest.trim();
        if (t.startsWith("v") || t.startsWith("V")) {
            t = t.substring(1);
        }
        if (!t.isEmpty()) {
            return RELEASE_URL + "v" + t + "/" + KernelCompat.SHIM_ASSET;
        }
        return RELEASE_LATEST_URL + KernelCompat.SHIM_ASSET;
    }

    private static final String RELEASE_LIST_API =
            "https://api.github.com/repos/tasirin1/tasirin-vaultwarden-host/releases?per_page=30";

    static String normalisasiPinVersi(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        if (t.startsWith("v") || t.startsWith("V")) {
            t = t.substring(1);
        }
        if (t.isEmpty()
                || !POLA_PIN_VERSI.matcher(t).matches()) {
            return null;
        }

        int plus = t.indexOf("+");
        return plus < 0 ? t : t.substring(0, plus);
    }

    static String saranStabilPrerelease(String diminta) {
        String t = normalisasiPinVersi(diminta);
        if (t == null) {
            return "";
        }
        int dash = t.indexOf('-');
        if (dash <= 0) {
            return "";
        }
        return "; rilis repo ini stabil - coba v" + t.substring(0, dash);
    }

    static String kuncianBinary(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            return normalisasiPinVersi(TgBackup.amanString(sp,
                    ServerService.KEY_BIN_PILIH, ""));
        } catch (Exception e) {
            return null;
        }
    }

    static String kuncianWebVault(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            return normalisasiPinVersi(TgBackup.amanString(sp,
                    ServerService.KEY_WV_PILIH, ""));
        } catch (Exception e) {
            return null;
        }
    }

    static boolean adaKuncianBinary(Context ctx) {
        String k = kuncianBinary(ctx);
        return k != null && !k.isEmpty();
    }

    static boolean adaKuncianWebVault(Context ctx) {
        String k = kuncianWebVault(ctx);
        return k != null && !k.isEmpty();
    }

    static String pilihTarget(String kuncian, String latest) {
        if (kuncian != null && !kuncian.isEmpty()) {
            return kuncian;
        }
        return latest;
    }

    public static String versiTargetBinary(Context ctx) {
        String pin = kuncianBinary(ctx);
        if (pin != null && !pin.isEmpty()) {
            return pin;
        }
        return pilihTarget(pin, latestVersion(ctx));
    }

    public static String versiTargetWebVault(Context ctx) {
        String pin = kuncianWebVault(ctx);
        if (pin != null && !pin.isEmpty()) {
            return pin;
        }
        return pilihTarget(pin, latestVersion(ctx));
    }

    public static boolean simpanKuncianBinary(Context ctx, String versi) {
        return simpanKuncian(ctx, ServerService.KEY_BIN_PILIH, versi);
    }

    public static boolean simpanKuncianWebVault(Context ctx, String versi) {
        return simpanKuncian(ctx, ServerService.KEY_WV_PILIH, versi);
    }

    static boolean simpanKuncian(Context ctx, String kunci, String versi) {
        String t = versi == null ? "" : versi.trim();
        if (t.equalsIgnoreCase("terbaru") || t.equalsIgnoreCase("latest")) {
            t = "";
        }
        String normal = t.isEmpty() ? "" : normalisasiPinVersi(t);
        if (normal == null) {
            return false;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    ServerService.PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(kunci, normal).apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static java.util.List<String> parseDaftarTag(String body, int maks) {
        return pindaiTag(body, maks);
    }

    private static java.util.List<String> pindaiTag(String body, int maks) {
        java.util.List<String> keluar = new java.util.ArrayList<>();
        if (body == null || maks <= 0) {
            return keluar;
        }
        String kunci = "\"tag_name\"";
        int n = body.length();
        boolean dalamString = false;
        boolean escape = false;
        for (int i = 0; i < n && keluar.size() < maks; i++) {
            char c = body.charAt(i);
            if (dalamString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    dalamString = false;
                }
                continue;
            }
            if (c != '"') {
                continue;
            }
            if (!body.startsWith(kunci, i)) {
                dalamString = true;
                continue;
            }
            int j = i + kunci.length();
            while (j < n && Character.isWhitespace(body.charAt(j))) {
                j++;
            }
            if (j >= n || body.charAt(j) != ':') {
                dalamString = true;
                continue;
            }
            j++;
            while (j < n && Character.isWhitespace(body.charAt(j))) {
                j++;
            }
            if (j >= n || body.charAt(j) != '"') {
                dalamString = true;
                continue;
            }
            StringBuilder tag = new StringBuilder();
            j++;
            boolean esc = false;
            while (j < n) {
                char d = body.charAt(j);
                if (esc) {
                    tag.append(d);
                    esc = false;
                } else if (d == '\\') {
                    esc = true;
                } else if (d == '"') {
                    break;
                } else {
                    tag.append(d);
                }
                j++;
            }
            i = j;
            if (tag.length() > 0) {
                keluar.add(tag.toString());
            }
        }
        return keluar;
    }

    public static java.util.List<String> daftarVersiTersedia(Context ctx, int maks) {
        java.util.List<String> keluar = new java.util.ArrayList<>();
        if (maks <= 0) {
            return keluar;
        }
        HttpURLConnection c = null;
        try {
            c = open(ctx, RELEASE_LIST_API, 10000, 15000);
            if (c.getResponseCode() != 200) {
                return keluar;
            }
            String body;
            try (InputStream is = c.getInputStream()) {
                body = TgBackup.bacaResponsBatas(is);
            }
            for (String tag : parseDaftarTag(body, maks * 2)) {
                String v = normVersion(tag);
                if (v != null && !v.isEmpty() && !keluar.contains(v)) {
                    keluar.add(v);
                }
                if (keluar.size() >= maks) {
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return keluar;
    }

    private static final long MIN_FREE_FOR_WEBVAULT = 150L * 1024 * 1024;

    private static final String KEY_WV_FROM = "wv_from_version";

    static final String KEY_WV_SHA = "wv_sha";

    static final String KEY_WV_FALLBACK_FOR = "wv_fallback_for";

    static final String KEY_BIN_FALLBACK_FOR = "bin_fallback_for";

    static final String KEY_BIN_FALLBACK_AT = "bin_fallback_at";

    static final long WV_FALLBACK_TUNDA_MS = 6L * 3600 * 1000;

    static boolean fallbackTercatatUntuk(String latest, String untuk) {
        return latest != null && !latest.isEmpty() && versiCocok(latest, untuk);
    }

    static boolean fallbackBinaryBerlaku(String latest, String untuk, long at,
            long kiniWall) {
        if (latest == null || latest.isEmpty()) {
            return false;
        }
        if (!versiCocok(latest, untuk)) {
            return false;
        }
        if (at <= 0 || kiniWall < at) {
            return false;
        }
        return kiniWall - at < WV_FALLBACK_TUNDA_MS;
    }

    public static volatile String downloadStatus = "";

    private static final java.util.concurrent.atomic.AtomicInteger unduhanAktif =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final java.util.Set<String> TMP_UNDUH_AKTIF =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    static void mulaiUnduhan() {
        unduhanAktif.incrementAndGet();
    }

    static void selesaiUnduhan() {
        int sisa = unduhanAktif.decrementAndGet();
        if (sisa <= 0) {
            unduhanAktif.set(0);
            downloadStatus = "";
        }
    }

    private static final int MAX_COBA_UNDUH = 3;

    private Updater() {
    }

    private static HttpURLConnection open(Context ctx, String url,
                                          int connectMs, int readMs) throws Exception {
        return bukaIkutiRedirect(ctx, url, connectMs, readMs, 0);
    }

    private static HttpURLConnection openRange(Context ctx, String url, long resumeFrom,
                                               int connectMs, int readMs) throws Exception {
        return bukaIkutiRedirect(ctx, url, connectMs, readMs, resumeFrom);
    }

    private static HttpURLConnection bukaIkutiRedirect(Context ctx, String url,
            int connectMs, int readMs, long resumeFrom) throws Exception {
        String kini = url;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(kini).openConnection();

            try {
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
                            || !Util.bolehIkutiRedirectGithub(kini, lok)) {
                        throw new IOException("Redirect tidak aman/ditolak: " + lok);
                    }
                    String berikut = Util.sambungRedirect(kini, lok);

                    if (berikut == null || berikut.equals(kini)) {
                        throw new IOException("Redirect tidak aman/ditolak (downgrade?): " + lok);
                    }

                    kini = berikut;
                    continue;
                }
                return c;
            } catch (Exception e) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
                throw e;
            }
        }
        throw new IOException("Terlalu banyak redirect.");
    }

    static String rantaiGalat(Exception e) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<Object> jumpa = new java.util.HashSet<>();
        for (Throwable t = e; t != null && jumpa.add(t); t = t.getCause()) {
            try {
                sb.append(String.valueOf(t.getClass().getSimpleName())).append(' ');
                sb.append(String.valueOf(t.getMessage())).append(' ');
            } catch (Exception ignored) {
            }
        }
        return Util.rendahAscii(sb.toString());
    }

    static boolean galatJamSertifikat(Exception e) {
        if (e == null) {
            return false;
        }
        String rantai = rantaiGalat(e);
        return rantai.contains("not valid until")
                || rantai.contains("not yet valid")
                || rantai.contains("not valid after")
                || rantai.contains("certificate_expired")
                || rantai.contains("certificate expired")
                || (rantai.contains("certpathvalidatorexception")
                        && rantai.contains("certificate"));
    }

    static String tanggalStbTerbaca() {
        return Util.tanggalStb(System.currentTimeMillis());
    }

    static String saranKoneksi(Exception e) {
        if (e == null) {
            return "Cek internet STB (buka github.com di browser), lalu tekan Start lagi.";
        }
        String gabung = rantaiGalat(e);
        if (galatJamSertifikat(e)) {
            boolean terlaluLama = gabung.contains("not valid until")
                    || gabung.contains("not yet valid");
            String terbaca = tanggalStbTerbaca();
            if (terlaluLama) {
                return "Tanggal & jam STB terlalu lama (terbaca " + terbaca + ")."
                        + " Sertifikat server belum berlaku menurut jam itu."
                        + " Aktifkan Tanggal & waktu otomatis di Pengaturan STB"
                        + " (butuh internet), atau atur manual ke hari ini, lalu ulangi.";
            }
            return "Tanggal & jam STB salah (terbaca " + terbaca + ")."
                    + " Sertifikat server ditolak menurut jam itu."
                    + " Aktifkan Tanggal & waktu otomatis di Pengaturan STB"
                    + " (butuh internet), atau atur manual ke hari ini, lalu ulangi.";
        }
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
            return "File belum tersedia di rilis (build +-12 jam). Coba lagi nanti.";
        }
        return "Cek internet STB (buka github.com di browser), lalu tekan Start lagi.";
    }

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

    static boolean isDnsHijackKeIpLokal(String pesanRendah) {
        if (pesanRendah == null) {
            return false;
        }
        return pesanRendah.contains("/192.168.") || pesanRendah.contains("/10.")
                || pesanRendah.contains("/127.") || pesanRendah.contains("/0.0.0.0")
                || pesanRendah.contains("/100.") || pesanRendah.contains("fe80")
                || pesanRendah.contains("fd00") || pesanRendah.contains("::1")
                || pesanRendah.contains("/172.16.") || pesanRendah.contains("/172.17.")
                || pesanRendah.contains("/172.18.") || pesanRendah.contains("/172.19.")
                || pesanRendah.contains("/172.2") || pesanRendah.contains("/172.30.")
                || pesanRendah.contains("/172.31.");
    }

    static boolean perluResetResume(int kodeHttp, long lanjutDari) {
        return lanjutDari > 0 && (kodeHttp == 416 || kodeHttp == 200);
    }

    static long totalDariContentRange(String header) {
        if (header == null) {
            return -1;
        }
        int garis = header.lastIndexOf('/');
        if (garis < 0 || garis + 1 >= header.length()) {
            return -1;
        }
        try {
            long total = Long.parseLong(header.substring(garis + 1).trim());
            return total > 0 ? total : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    static boolean rangeCocok(HttpURLConnection dl, long lanjutDari) {
        if (lanjutDari <= 0) {
            return true;
        }
        try {
            String v = dl.getHeaderField("Content-Range");
            if (v == null) {
                return false;
            }
            String rendah = v.trim().toLowerCase(Locale.US);
            if (!rendah.startsWith("bytes ")) {
                return false;
            }
            String sisa = rendah.substring(6).trim();
            int strip = sisa.indexOf('-');
            if (strip <= 0) {
                return false;
            }
            return Long.parseLong(sisa.substring(0, strip).trim()) == lanjutDari;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean bolehCobaLagiUnduh(Exception e) {
        String rendah = String.valueOf(e == null ? null : e.getMessage()).toLowerCase(Locale.US);

        if (rendah.contains("404") || rendah.contains("belum tersedia")) {
            return false;
        }

        if (rendah.contains("parsial") || rendah.contains("kurang")
                || rendah.contains("terpotong") || rendah.contains("truncat")
                || rendah.contains("content-range") || rendah.contains("416")
                || rendah.contains("checksum") || rendah.contains("tidak cocok")
                || rendah.contains("mismatch") || rendah.contains("corrupt")
                || rendah.contains("rusak")) {
            return true;
        }

        return rendah.contains("timed out") || rendah.contains("timeout")
                || rendah.contains("failed to connect") || rendah.contains("econn")
                || rendah.contains("unreachable") || rendah.contains("reset")
                || rendah.contains("terputus")
                || rendah.contains("broken pipe") || rendah.contains("http");
    }

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

    interface UrlCadangan {
        String ganti(String url, int kode) throws IOException;
    }

    public interface CekBatal {
        boolean dibatalkan();
    }

    public static final Object KUNCI_WEBVAULT = new Object();

    private static final Object KUNCI_UPDATE_WV = new Object();

    private static final java.util.Set<String> STAGING_AKTIF =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    static void tandaiStagingAktif(java.io.File dir) {
        if (dir != null && dir.getName() != null) {
            STAGING_AKTIF.add(dir.getName());
        }
    }

    static void lepasStagingAktif(java.io.File dir) {
        if (dir != null && dir.getName() != null) {
            STAGING_AKTIF.remove(dir.getName());
        }
    }

    static boolean stagingAktif(String nama) {
        return nama != null && STAGING_AKTIF.contains(nama);
    }

    static java.io.File stagingWebVault(java.io.File dataFolder) {
        return new java.io.File(dataFolder, "web-vault.new-" + TgBackup.stempelUnik());
    }

    static boolean sisaStagingWebVault(String nama) {
        if (nama == null) {
            return false;
        }
        return nama.equals("web-vault.new") || nama.startsWith("web-vault.new-");
    }

    static String namaTmpUnduh(String namaDasar, String versi) {
        String v = normVersion(versi);
        return namaDasar + "." + (v == null || v.isEmpty() ? "terbaru" : v) + ".tmp";
    }

    private static void sapuTmpUnduhBasi(File dir, String namaDasar, File kecuali) {
        try {
            File[] isi = dir == null ? null : dir.listFiles();
            if (isi == null) {
                return;
            }
            String awalan = namaDasar + ".";
            String kecualiPath = kecuali == null ? "" : kecuali.getAbsolutePath();
            long batas = System.currentTimeMillis() - 10L * 60L * 1000L;
            for (File f : isi) {
                String n = f.getName();
                if (!n.startsWith(awalan) || !n.endsWith(".tmp")
                        || f.getAbsolutePath().equals(kecualiPath)
                        || TMP_UNDUH_AKTIF.contains(f.getAbsolutePath())) {
                    continue;
                }
                try {
                    if (f.isFile() && f.lastModified() < batas) {
                        f.delete();
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, KunciUnduh> KUNCI_UNDUH =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final Object KUNCI_PETA = new Object();

    private static final class KunciUnduh {
        final Object mutex = new Object();
        int pemakai;
    }

    private static String pathKunciUnduh(File tmp) {
        try {
            return tmp.getCanonicalPath();
        } catch (Exception e) {
            return tmp.getAbsolutePath();
        }
    }

    static Object kunciUnduh(File tmp) {
        String kunci = pathKunciUnduh(tmp);
        synchronized (KUNCI_PETA) {
            KunciUnduh isi = KUNCI_UNDUH.get(kunci);
            if (isi == null) {
                isi = new KunciUnduh();
                KUNCI_UNDUH.put(kunci, isi);
            }
            isi.pemakai++;
            return isi.mutex;
        }
    }

    static void lepasKunciUnduh(File tmp) {
        String kunci = pathKunciUnduh(tmp);
        synchronized (KUNCI_PETA) {
            KunciUnduh isi = KUNCI_UNDUH.get(kunci);
            if (isi == null) {
                return;
            }
            isi.pemakai--;
            if (isi.pemakai <= 0) {
                KUNCI_UNDUH.remove(kunci);
            }
        }
    }

    static void pasangHasilUnduh(File tmp, File out, String expectedSha)
            throws IOException {
        Object kunciOut = kunciUnduh(out);
        Object kunciTmp = kunciUnduh(tmp);
        try {
            synchronized (kunciOut) {
                synchronized (kunciTmp) {
                    if (!tmp.isFile()) {

                        if (expectedHexEquals(expectedSha, sha256Hex(out))) {
                            return;
                        }
                        throw new IOException("Berkas unduhan sementara hilang;"
                                + " ulangi unduhan.");
                    }
                    if (!expectedHexEquals(expectedSha, sha256Hex(tmp))) {
                        tmp.delete();
                        throw new IOException("Checksum SHA-256 tidak cocok; update dibatalkan.");
                    }
                    ServerService.gantiAtomik(tmp, out);
                }
            }
        } finally {
            lepasKunciUnduh(tmp);
            lepasKunciUnduh(out);
        }
    }

    interface ShaTarget {
        String ambil(String urlAkhir) throws IOException;
    }

    static ShaTarget shaDariRilis(final Context ctx, final int connectMs, final int readMs,
                                  final String pesanNull, final String[] simpan) {
        return new ShaTarget() {
            @Override
            public String ambil(String urlAkhir) throws IOException {
                HasilChecksum hasil = fetchChecksumRinci(ctx, urlAkhir + ".sha256",
                        connectMs, readMs);
                if (hasil.hex == null) {

                    throw new IOException(pesanNull
                            + (hasil.sementara
                                    ? " karena gangguan jaringan/rate-limit - coba lagi nanti."
                                    : " (aset belum terbit di rilis).")
                            + " Update dibatalkan demi keamanan. " + saranKoneksi(null));
                }
                simpan[0] = hasil.hex;
                return hasil.hex;
            }
        };
    }

    static String unduhKeTmp(Context ctx, String urlAwal, File tmp, String label,
                              int connectMs, int readMs, UrlCadangan cadangan,
                              ShaTarget sha)
            throws IOException {
        return unduhKeTmp(ctx, urlAwal, tmp, label, connectMs, readMs, cadangan, sha, null);
    }

    static String unduhKeTmp(Context ctx, String urlAwal, File tmp, String label,
                              int connectMs, int readMs, UrlCadangan cadangan,
                              ShaTarget sha, CekBatal batal)
            throws IOException {
        final String[] url = {urlAwal};
        final boolean[] fallbackUsed = {false};
        Exception gagal = null;
        int mismatchSha = 0;
        TMP_UNDUH_AKTIF.add(tmp.getAbsolutePath());
        for (int coba = 1; coba <= MAX_COBA_UNDUH; coba++) {
            try {
                if (dibatalkan(batal)) {
                    throw new IOException(pesanDibatalkan(label));
                }
                String digest;
                Object kunciTmp = kunciUnduh(tmp);
                try {
                    synchronized (kunciTmp) {
                        digest = unduhSatuPercobaan(ctx, url, fallbackUsed, tmp, label,
                                connectMs, readMs, cadangan, batal);
                    }
                } finally {
                    lepasKunciUnduh(tmp);
                }
                if (sha != null) {
                    String harap = sha.ambil(url[0]);
                    if (!expectedHexEquals(harap, digest)) {

                        mismatchSha++;
                        throw new IOException(mismatchSha >= 2
                                ? "Checksum SHA-256 tidak cocok setelah 2 percobaan;"
                                        + " update dibatalkan (aset rilis mungkin korup)."
                                : "Checksum SHA-256 tidak cocok;"
                                        + " unduhan diulang dari nol.");
                    }
                }
                if (dibatalkan(batal)) {
                    throw new IOException(pesanDibatalkan(label));
                }
                TMP_UNDUH_AKTIF.remove(tmp.getAbsolutePath());
                return digest;
            } catch (IOException e) {
                gagal = e;
                buangParsialRusak(tmp, e);

                if (!bolehCobaLagiUnduh(e) || coba >= MAX_COBA_UNDUH
                        || mismatchSha >= 2) {
                    break;
                }
                if (!tundaCobaLagiUnduh(coba)) {
                    break;
                }
            } catch (Exception e) {
                gagal = e;
                break;
            }
        }
        TMP_UNDUH_AKTIF.remove(tmp.getAbsolutePath());
        if (gagal instanceof IOException) {
            throw (IOException) gagal;
        }
        throw new IOException(gagal == null ? "koneksi gagal" : String.valueOf(gagal));
    }

    static boolean dibatalkan(CekBatal batal) {
        try {
            return batal != null && batal.dibatalkan();
        } catch (Exception e) {

            return false;
        }
    }

    static final String TANDA_DIBATALKAN = "dibatalkan (Stop ditekan)";

    static String pesanDibatalkan(String label) {
        return "Unduhan " + label + " " + TANDA_DIBATALKAN + " - tidak dilanjutkan.";
    }

    static boolean galatDibatalkan(Exception e) {
        return String.valueOf(e == null ? null : e.getMessage())
                .toLowerCase(Locale.US)
                .contains(TANDA_DIBATALKAN.toLowerCase(Locale.US));
    }

    private static void buangParsialRusak(File tmp, IOException e) {
        try {
            String pesan = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
            if (pesan.contains("parsial") || pesan.contains("kurang")
                    || pesan.contains("terpotong") || pesan.contains("truncat")
                    || pesan.contains("content-range") || pesan.contains("416")
                    || pesan.contains("checksum") || pesan.contains("tidak cocok")
                    || pesan.contains("mismatch") || pesan.contains("corrupt")
                    || pesan.contains("rusak") || pesan.contains("melebihi batas")) {
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static String unduhSatuPercobaan(Context ctx, String[] url, boolean[] fallbackUsed,
                              File tmp, String label,
                              int connectMs, int readMs, UrlCadangan cadangan,
                              CekBatal batal)
            throws Exception {

        long resumeFrom = 0;
        try {
            if (tmp.isFile()) {
                resumeFrom = tmp.length();
            } else if (tmp.exists()) {
                try { tmp.delete(); } catch (Exception ignoredDel) { }
            }
        } catch (Exception ignored) {
            resumeFrom = 0;
        }
        HttpURLConnection dl = null;
        try {
            dl = openRange(ctx, url[0], resumeFrom, connectMs, readMs);
            int code = dl.getResponseCode();
            if (code == 404 && cadangan != null && !fallbackUsed[0]) {
                String alt = cadangan.ganti(url[0], code);
                if (alt != null && !alt.equals(url[0])) {
                    dl.disconnect();
                    dl = null;
                    resumeFrom = 0;
                    tmp.delete();
                    url[0] = alt;
                    fallbackUsed[0] = true;
                    dl = open(ctx, url[0], connectMs, readMs);
                    code = dl.getResponseCode();
                }
            }
            if (code == 416 && resumeFrom > 0
                    && totalDariContentRange(
                            dl.getHeaderField("Content-Range")) == resumeFrom) {

                String hexLengkap = sha256Hex(tmp);
                if (hexLengkap != null) {
                    return hexLengkap;
                }
            }
            if (perluResetResume(code, resumeFrom)) {

                dl.disconnect();
                tmp.delete();
                resumeFrom = 0;
                dl = open(ctx, url[0], connectMs, readMs);
                code = dl.getResponseCode();
            }
            if (code == 206 && resumeFrom > 0 && !rangeCocok(dl, resumeFrom)) {

                dl.disconnect();
                tmp.delete();
                resumeFrom = 0;
                dl = open(ctx, url[0], connectMs, readMs);
                code = dl.getResponseCode();
            }
            if (code != 200 && code != 206) {
                throw new IOException("Unduhan gagal (HTTP " + code + ").");
            }
            return salinSambilHash(dl, tmp, code, resumeFrom, label, batal);
        } finally {
            if (dl != null) {
                dl.disconnect();
            }
        }
    }

    static final long BATAS_UNDUH_BINARY = 128L * 1024 * 1024;

    static final long BATAS_UNDUH_SHIM = 8L * 1024 * 1024;

    static final long BATAS_UNDUH_WEBVAULT = 150L * 1024 * 1024;

    static long batasUnduhUntuk(String label) {
        if ("binary".equals(label)) {
            return BATAS_UNDUH_BINARY;
        }
        if ("shim".equals(label)) {
            return BATAS_UNDUH_SHIM;
        }
        if ("web vault".equals(label)) {
            return BATAS_UNDUH_WEBVAULT;
        }
        return 0;
    }

    static boolean unduhanMelebihiBatas(long done, long batas) {
        return batas > 0 && done > batas;
    }

    static String pesanMelebihiBatas(String label, long batas) {
        return "Unduhan " + label + " melebihi batas ukuran " + batas
                + " byte - unduhan dibatalkan.";
    }

    static String salinSambilHash(HttpURLConnection dl, File tmp, int kodeHttp,
                                  long lanjutDari, String label) throws IOException {
        return salinSambilHash(dl, tmp, kodeHttp, lanjutDari, label, null);
    }

    static String salinSambilHash(HttpURLConnection dl, File tmp, int kodeHttp,
                                  long lanjutDari, String label, CekBatal batal)
            throws IOException {
        long total = panjangKonten(dl);
        if (kodeHttp == 206 && total >= 0) {
            total += lanjutDari;
        }
        long batas = batasUnduhUntuk(label);
        if (unduhanMelebihiBatas(total, batas)) {
            throw new IOException(pesanMelebihiBatas(label, batas));
        }
        java.security.MessageDigest md;
        try {
            md = Util.mdSha256();
        } catch (Exception e) {
            throw new IOException("SHA-256 tidak tersedia: " + e.getMessage());
        }
        long done = lanjutDari;
        if (lanjutDari > 0) {

            long capPanjang = tmp.length();
            digestPrefix(md, tmp, lanjutDari);
            if (tmp.length() != capPanjang) {
                throw new IOException("File parsial berubah saat di-hash"
                        + " - unduh ulang dari nol.");
            }
        }
        try (InputStream in = dl.getInputStream();
             FileOutputStream fos = new FileOutputStream(tmp, kodeHttp == 206)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            long lastReport = done;
            while ((n = in.read(buf)) != -1) {
                if (dibatalkan(batal)) {

                    throw new IOException(pesanDibatalkan(label));
                }
                fos.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                if (unduhanMelebihiBatas(done, batas)) {
                    throw new IOException(pesanMelebihiBatas(label, batas));
                }
                if (done - lastReport >= 256 * 1024) {
                    lastReport = done;
                    reportDownload(label, done, total);
                }
            }
        }
        if (badanTerpotong(done, total)) {

            throw new IOException("Unduhan terputus di " + done + " dari " + total
                    + " byte - dilanjutkan dari titik terakhir.");
        }
        return toHex(md.digest());
    }

    static boolean badanTerpotong(long done, long total) {
        return total >= 0 && done != total;
    }

    private static final long VERSION_TTL_MS = 15 * 60 * 1000L;
    private static final Object KUNCI_VERSI = new Object();
    private static volatile String sLatestVersion;
    private static volatile long sLatestAt;

    static final long VERSION_GAGAL_TTL_MS = 60_000;
    private static volatile long sLatestGagalAt = 0;

    static boolean gagalBaruSaja(long kiniElapsed, long gagalAt, long jedaMs) {
        if (gagalAt == 0 || kiniElapsed < gagalAt) {
            return false;
        }
        return kiniElapsed - gagalAt < jedaMs;
    }

    public static String latestVersion(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        String cached;
        synchronized (KUNCI_VERSI) {
            cached = sLatestVersion;
            if (cached != null && now - sLatestAt < VERSION_TTL_MS) {
                return cached;
            }

            if (gagalBaruSaja(now, sLatestGagalAt, VERSION_GAGAL_TTL_MS)) {
                return cached;
            }
        }
        HttpURLConnection conn = null;
        try {
            conn = open(ctx, OFFICIAL_API, 10000, 10000);
            int code = conn.getResponseCode();
            if (code == 200) {
                String v;
                try (java.io.InputStream is = conn.getInputStream()) {
                    v = normVersion(extractTag(
                            TgBackup.bacaResponsBatas(is)));
                }
                if (v != null && !v.isEmpty()) {
                    synchronized (KUNCI_VERSI) {
                        sLatestVersion = v;
                        sLatestAt = now;
                    }
                    return v;
                }
            }

        } catch (Exception ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        synchronized (KUNCI_VERSI) {
            sLatestGagalAt = now;
        }
        return cached;
    }

    public static String tryUpdate(Context ctx) throws Exception {

        String pin = kuncianBinary(ctx);
        return tryUpdateVersi(ctx, (pin == null || pin.isEmpty()) ? null : pin);
    }

    public static String tryUpdateVersi(Context ctx, String versiDiminta) throws Exception {

        boolean paksa = versiDiminta != null && !versiDiminta.isEmpty();
        String latest = paksa ? versiDiminta : latestVersion(ctx);
        if (latest == null) {
            throw new IOException("Tidak bisa baca versi terbaru. " + saranKoneksi(null));
        }
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);

        boolean butuhRefresh = ServerService.perluRefreshPatch(
                TgBackup.amanString(sp, ServerService.KEY_BIN_PATCH, ""));
        String sebutan = (paksa || adaKuncianBinary(ctx))
                ? "Sudah versi pilihan: v" : "Sudah versi terbaru: v";
        String realJalan = parseBinaryVersion(ServerService.binaryVersion);

        if (!paksa && !butuhRefresh && bandingVersi(realJalan, latest) >= 0) {

            sp.edit().putString(ServerService.KEY_UPDATE_VERSION, latest).apply();
            return sebutan + latest;
        }

        String realBerkas = butuhRefresh ? null : versiFileBinary(ctx);
        if (unduhBolehDilewati(paksa, butuhRefresh, realBerkas, realJalan, latest)) {

            sp.edit().putString(ServerService.KEY_UPDATE_VERSION, latest).apply();
            if (ServerService.running && bandingVersi(realJalan, latest) < 0) {
                return "Binary v" + latest + " sudah terpasang; mulai ulang server"
                        + " agar dipakai. " + BINARY_UPDATED_MARKER;
            }
            return sebutan + latest;
        }

        if (!paksa && !butuhRefresh && !adaKuncianBinary(ctx)
                && fallbackTercatatUntuk(latest,
                        TgBackup.amanString(sp, KEY_BIN_FALLBACK_FOR, ""))) {
            long sekarang = System.currentTimeMillis();
            if (dalamJedaCek(TgBackup.amanLong(sp, KEY_CEK_GANGGUAN_AT, 0), sekarang)) {

                return "Cek pembaruan tertunda (jaringan GitHub terganggu); binary"
                        + " rilis sebelumnya tetap dipakai.";
            }
            HasilChecksum probe = fetchChecksumRinci(ctx,
                    binaryAssetUrl(latest, ServerService.ABI) + ".sha256",
                    20000, 20000);
            if (probe.hex == null) {
                if (probe.sementara) {
                    sp.edit().putLong(KEY_CEK_GANGGUAN_AT, sekarang).apply();
                    return "Cek pembaruan terganggu jaringan; binary rilis sebelumnya"
                            + " tetap dipakai (dicek ulang nanti).";
                }
                return "Build Android v" + latest + " belum tersedia; binary rilis"
                        + " sebelumnya tetap dipakai (dicek ulang otomatis nanti).";
            }
            sp.edit().remove(KEY_BIN_FALLBACK_FOR)
                    .remove(KEY_BIN_FALLBACK_AT)
                    .remove(KEY_CEK_GANGGUAN_AT).apply();
        }
        String updated = TgBackup.amanString(sp, ServerService.KEY_UPDATE_VERSION, "");
        String current = realJalan != null ? realJalan : normVersion(updated != null && !updated.isEmpty()
                ? updated : readBundledVersionRaw(ctx));
        if (!paksa && !butuhRefresh && bandingVersi(current, latest) >= 0) {
            return sebutan + latest;
        }

        File out = new File(ctx.getFilesDir(), "bin/vaultwarden-" + ServerService.ABI);
        String msg = downloadBinaryVersi(ctx, out, latest);
        sp.edit().putString(ServerService.KEY_BIN_PATCH,
                String.valueOf(ServerService.BIN_PATCH_REV)).apply();
        return msg;
    }

    static String versiFileBinary(Context ctx) {
        try {
            File berkas = new File(ctx.getFilesDir(),
                    "bin/vaultwarden-" + ServerService.ABI);
            if (berkas == null || !berkas.isFile() || berkas.length() < 1_000_000) {
                return null;
            }
            return detectVersion(ctx, berkas);
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean unduhBolehDilewati(boolean paksa, boolean butuhRefresh,
            String versiBerkas, String versiJalan, String target) {

        if (butuhRefresh) {
            return false;
        }
        if (target == null || target.isEmpty()) {
            return false;
        }
        if (paksa) {
            return versiBerkas != null && !versiBerkas.isEmpty()
                    && versiCocok(versiBerkas, target);
        }
        String acuan = (versiBerkas != null && !versiBerkas.isEmpty())
                ? versiBerkas : versiJalan;
        return bandingVersi(acuan, target) >= 0;
    }

    public static String downloadBinary(Context ctx, File out) throws Exception {
        return downloadBinary(ctx, out, null);
    }

    public static String downloadBinary(Context ctx, File out, CekBatal batal)
            throws Exception {
        return downloadBinaryVersi(ctx, out, versiTargetBinary(ctx), batal);
    }

    public static String downloadBinaryVersi(Context ctx, File out, String versi) throws Exception {
        return downloadBinaryVersi(ctx, out, versi, null);
    }

    public static String downloadBinaryVersi(Context ctx, File out, String versi,
                                             CekBatal batal) throws Exception {
        mulaiUnduhan();
        try {
            return downloadBinaryInner(ctx, out, versi, batal);
        } finally {
            selesaiUnduhan();
        }
    }

    private static String downloadBinaryInner(Context ctx, File out, String diminta,
                                              CekBatal batal) throws Exception {
        String latest = diminta;

        boolean known = latest != null && !latest.isEmpty();

        boolean bolehFallback = !adaKuncianBinary(ctx);
        String assetUrl = binaryAssetUrl(latest, ServerService.ABI);

        SharedPreferences spBin = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        boolean fallbackTercatat = bolehFallback && known && fallbackBinaryBerlaku(
                latest,
                TgBackup.amanString(spBin, KEY_BIN_FALLBACK_FOR, ""),
                TgBackup.amanLong(spBin, KEY_BIN_FALLBACK_AT, 0),
                System.currentTimeMillis());
        File binDir = out.getParentFile();
        if (binDir != null && !binDir.exists()
                && !binDir.mkdirs() && !binDir.exists()) {
            throw new IOException("Gagal membuat folder binary: " + binDir
                    + " - cek sisa storage & izin Storage, lalu ulangi.");
        }

        try {
            File cekDir = (binDir != null && binDir.exists()) ? binDir : ctx.getFilesDir();
            long bebas = TgBackup.freeBytes(ctx, cekDir.getAbsolutePath());
            if (bebas > 0 && bebas < 50L * 1024 * 1024) {
                throw new IOException("Ruang storage kurang dari 50 MB"
                        + " - kosongkan dulu lalu tekan Start lagi.");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception ignored) {
        }

        if (KernelCompat.legacyPerangkat()) {
            File shimAwal = new File(ctx.getFilesDir(),
                    "bin/" + KernelCompat.SHIM_ASSET);
            if (!shimValid(shimAwal)) {
                try {
                    ensureShimFile(ctx);
                } catch (Exception e) {
                    String m = e.getMessage();
                    throw new IOException(m != null && !m.isEmpty()
                            ? "Shim getrandom gagal: " + m
                            : pesanUjiAsapGagal(
                                    KernelCompat.kernelSekarang(), false));
                }
            }
        }
        File tmp = new File(binDir, namaTmpUnduh(out.getName(), latest));

        sapuTmpUnduhBasi(binDir, out.getName(), tmp);

        final boolean[] pakaiTerbaru = {fallbackTercatat};
        final String[] urlAkhir = {urlAwalUnduhBinary(fallbackTercatat, assetUrl)};
        final String[] shaDapat = {null};
        boolean fallback;
        String asap;
        Object kunciTmpBin = kunciUnduh(tmp);
        synchronized (kunciTmpBin) {
            try {
                try {

                    unduhKeTmp(ctx, urlAkhir[0], tmp, "binary", 20000, 60000,
                            new UrlCadangan() {
                                @Override
                                public String ganti(String url, int kode) throws IOException {
                                    if (bolehFallback && known && !pakaiTerbaru[0]
                                            && url.equals(urlAkhir[0])) {

                                        pakaiTerbaru[0] = true;
                                        urlAkhir[0] = binaryFallbackUrl();
                                        return urlAkhir[0];
                                    }
                                    throw new IOException(known
                                            ? "Build Android v" + latest + " belum tersedia"
                                                    + saranStabilPrerelease(latest)
                                                    + " (build otomatis ~12 jam). Coba lagi nanti."
                                            : "Release binary Android belum tersedia. Coba lagi nanti.");
                                }
                            },
                            shaDariRilis(ctx, 20000, 60000,
                                    "Verifikasi SHA-256 tidak bisa diambil dari release", shaDapat),
                            batal);
                } catch (IOException e) {

                    if (galatDibatalkan(e)) {
                        throw e;
                    }
                    if (String.valueOf(e.getMessage()).contains("belum tersedia")) {
                        throw e;
                    }
                    throw new IOException(pesanGalatUnduh("Unduh binary", e));
                }
                fallback = pakaiTerbaru[0];

                String expectedSha = shaDapat[0];
                if (tmp.length() < 1_000_000 || !isElf(tmp)) {
                    tmp.delete();
                    throw new IOException("File update tidak valid.");
                }

                tmp.setReadable(true, true);
                tmp.setExecutable(true, true);

                if (KernelCompat.legacyPerangkat()) {
                    try {
                        ensureShimFile(ctx);
                    } catch (Exception abaikan) {

                    }
                }
                asap = detectVersion(ctx, tmp);
                if (asap == null) {
                    boolean shimAda = shimValid(new File(ctx.getFilesDir(),
                            "bin/" + KernelCompat.SHIM_ASSET));
                    tmp.delete();
                    throw new IOException(pesanUjiAsapGagal(
                            KernelCompat.kernelSekarang(), shimAda));
                }
                try {
                    pasangHasilUnduh(tmp, out, expectedSha);
                } catch (IOException e) {
                    try {
                        tmp.delete();
                    } catch (Exception ignored) {
                    }
                    throw e;
                }
            } finally {
                lepasKunciUnduh(tmp);
            }
        }

        out.setReadable(true, true);
        out.setExecutable(true, true);

        if (known) {
            if (fallback) {
                spBin.edit().putString(KEY_BIN_FALLBACK_FOR, latest)
                        .putLong(KEY_BIN_FALLBACK_AT, System.currentTimeMillis())
                        .apply();
            } else {
                spBin.edit().remove(KEY_BIN_FALLBACK_FOR)
                        .remove(KEY_BIN_FALLBACK_AT).apply();
            }
        }
        writeVersionTag(binDir, appVersionName(ctx));

        if (!asap.isEmpty()) {
            ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(ServerService.KEY_UPDATE_VERSION, asap).apply();
        }
        ServerService.binaryVersion = "";
        return "Update v" + asap + " terpasang. " + BINARY_UPDATED_MARKER;
    }

    static final String BINARY_UPDATED_MARKER = "[bin-updated]";

    static boolean binaryBerubah(String msg) {
        if (msg == null) {
            return false;
        }
        if (msg.contains(BINARY_UPDATED_MARKER)) {
            return true;
        }
        return msg.startsWith("Update v");
    }

    static String pesanUjiAsapGagal(String kernel, boolean shimAda) {
        if (KernelCompat.isLegacyKernel(kernel == null ? "" : kernel) && !shimAda) {
            return "File update tidak valid (gagal uji jalan --version;"
                    + " shim getrandom belum terpasang)."
                    + " Cek internet lalu tekan Start lagi agar shim ikut terunduh.";
        }
        return "File update tidak valid (gagal uji jalan --version).";
    }

    static boolean shimValid(File f) {
        return f != null && f.exists()
                && f.length() >= KernelCompat.SHIM_MIN_BYTES && isElf(f);
    }

    public static File ensureShimFile(Context ctx) throws Exception {
        File out = new File(ctx.getFilesDir(), "bin/" + KernelCompat.SHIM_ASSET);
        if (shimValid(out)) {
            return out;
        }
        downloadShim(ctx, out);
        if (!shimValid(out)) {
            throw new IOException("File shim tidak valid (ukuran "
                    + (out.exists() ? out.length() : 0) + " byte).");
        }
        return out;
    }

    public static String downloadShim(Context ctx, File out) throws Exception {
        mulaiUnduhan();
        try {
            return downloadShimInner(ctx, out);
        } finally {
            selesaiUnduhan();
        }
    }

    private static String downloadShimInner(Context ctx, File out) throws Exception {
        String latest = latestVersion(ctx);
        boolean known = latest != null && !latest.isEmpty();
        String assetUrl = shimAssetUrl(latest);
        File binDir = out.getParentFile();
        if (binDir != null && !binDir.exists()
                && !binDir.mkdirs() && !binDir.exists()) {
            throw new IOException("Gagal membuat folder binary: " + binDir
                    + " - cek sisa storage & izin Storage, lalu ulangi.");
        }
        File tmp = new File(binDir, namaTmpUnduh(out.getName(), latest));
        sapuTmpUnduhBasi(binDir, out.getName(), tmp);
        final String[] urlShimAkhir = {assetUrl};
        final String[] shaDapat = {null};
        try {
            unduhKeTmp(ctx, assetUrl, tmp, "shim", 20000, 30000,
                    new UrlCadangan() {
                        @Override
                        public String ganti(String url, int kode) throws IOException {
                            if (known && !url.startsWith(RELEASE_LATEST_URL)) {

                                urlShimAkhir[0] = RELEASE_LATEST_URL + KernelCompat.SHIM_ASSET;
                                return urlShimAkhir[0];
                            }
                            if (!known) {
                                return null;
                            }
                            throw new IOException("Shim getrandom belum tersedia di rilis v"
                                    + latest + " (build CI ~12 jam). Coba lagi nanti.");
                        }
                    },
                    shaDariRilis(ctx, 20000, 30000,
                            "Verifikasi SHA-256 tidak bisa diambil dari release", shaDapat));
        } catch (IOException e) {
            if (String.valueOf(e.getMessage()).contains("belum tersedia")) {
                throw e;
            }
            throw new IOException(pesanGalatUnduh("Unduh shim", e));
        }

        String expectedSha = shaDapat[0];
        if (tmp.length() < KernelCompat.SHIM_MIN_BYTES || !isElf(tmp)) {
            long ukuran = tmp.length();
            tmp.delete();
            throw new IOException("File shim tidak valid (ukuran " + ukuran + " byte).");
        }

        try {
            pasangHasilUnduh(tmp, out, expectedSha);
        } catch (IOException e) {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
            throw new IOException("Gagal menyimpan shim: " + e.getMessage());
        }
        out.setReadable(true, true);
        out.setExecutable(true, true);
        return "Shim getrandom terpasang.";
    }

    static int persenUnduhan(String status) {
        if (status == null) {
            return -1;
        }
        int buka = status.lastIndexOf('(');
        int tutup = status.lastIndexOf("%)");
        if (buka < 0 || tutup < 0 || tutup <= buka + 1) {
            return -1;
        }
        String angka = status.substring(buka + 1, tutup).trim();

        if (angka.isEmpty() || angka.length() > 3) {
            return -1;
        }
        for (int i = 0; i < angka.length(); i++) {
            char c = angka.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        try {
            int p = Integer.parseInt(angka);
            return (p >= 0 && p <= 100) ? p : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    static void reportDownload(String label, long done, long total) {
        if (total > 0) {
            int pct = (int) (done * 100 / total);
            if (pct < 0) {
                pct = 0;
            } else if (pct > 100) {
                pct = 100;
            }
            downloadStatus = "Unduh " + label + " " + TgBackup.humanBytes(done)
                    + "/" + TgBackup.humanBytes(total) + " (" + pct + "%)";
        } else {
            downloadStatus = "Unduh " + label + " " + TgBackup.humanBytes(done) + "...";
        }
    }

    private static void writeVersionTag(File binDir, String apkVersion) {
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new FileOutputStream(new File(binDir, "version.txt"), false),
                StandardCharsets.UTF_8)) {
            w.write(apkVersion);
        } catch (Exception ignored) {
        }
    }

    public static String webVaultFromVersion(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String v = TgBackup.amanString(sp, KEY_WV_FROM, "");
        return (v == null || v.isEmpty()) ? null : v;
    }

    static boolean wvBedaVersiServer(String penanda, String versiServer) {
        if (penanda == null || penanda.trim().isEmpty()) {
            return false;
        }
        if (versiServer == null || versiServer.trim().isEmpty()) {
            return false;
        }
        return !versiCocok(penanda, versiServer);
    }

    @SuppressWarnings("deprecation")
    public static String appVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static final String WV_UPDATED_MARKER = "[wv-updated]";

    public static boolean webVaultReady(Context ctx, String dataDir) {
        try {
            if (dataDir != null && wvPayloadAda(new File(dataDir, "web-vault"))) {
                return true;
            }
            return ctx != null && wvPayloadAda(new File(ctx.getFilesDir(), "web-vault"));
        } catch (Exception e) {
            return false;
        }
    }

    static boolean wvPayloadAda(File targetDir) {
        if (targetDir == null) {
            return false;
        }
        File idx = new File(targetDir, "index.html");
        return idx.isFile() && idx.length() > 0;
    }

    public static String updateWebVault(Context ctx) throws Exception {
        return updateWebVaultVersi(ctx, versiTargetWebVault(ctx));
    }

    public static String updateWebVaultVersi(Context ctx, String versi) throws Exception {
        synchronized (KUNCI_UPDATE_WV) {
            mulaiUnduhan();
            try {
                return updateWebVaultInner(ctx, versi);
            } finally {
                selesaiUnduhan();
            }
        }
    }

    static String versiWvUntukCap(String latest, boolean fallback) {
        return (latest != null && !fallback) ? latest : null;
    }

    private static String updateWebVaultInner(Context ctx, String diminta) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());

        if (dataDir == null || dataDir.trim().isEmpty()
                || !ServerService.dataDirAman(dataDir)
                || !ServerService.dataDirKanonisAman(dataDir)) {
            dataDir = ServerService.dataDirBawaanSegar();
        } else {
            dataDir = dataDir.trim();
        }

        File dataFolder = new File(dataDir);
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("Gagal membuat folder data: " + dataDir);
        }
        if (!dataFolder.canWrite()) {
            throw new IOException("Folder data tidak bisa ditulis: " + dataDir
                    + " - beri izin Storage/Semua file, lalu ulangi.");
        }

        File targetDir = new File(dataFolder, "web-vault");

        String latest = diminta != null ? diminta : latestVersion(ctx);

        File tmpZip = new File(dataFolder, namaTmpUnduh("web-vault.zip", latest));

        sapuTmpUnduhBasi(dataFolder, "web-vault.zip", tmpZip);

        boolean wvExists = wvPayloadAda(targetDir);
        String installed = TgBackup.amanString(sp, KEY_WV_FROM, "");

        if (wvExists && latest != null && !installed.isEmpty()
                && versiCocok(installed, latest)) {
            return "Web vault sudah versi terbaru: v" + latest;
        }
        if (wvExists && latest == null && !installed.isEmpty()) {
            return "Web vault v" + installed
                    + " terpasang; cek versi terbaru gagal (koneksi/rate-limit). Coba lagi nanti.";
        }
        if (wvExists && latest == null && installed.isEmpty()) {
            return "Web vault terpasang (versi tak dikenal);"
                    + " cek versi terbaru gagal (koneksi/rate-limit)."
                    + " Coba lagi nanti agar hemat kuota.";
        }

        if (wvExists && !adaKuncianWebVault(ctx)
                && fallbackTercatatUntuk(latest,
                        TgBackup.amanString(sp, KEY_WV_FALLBACK_FOR, ""))) {
            long sekarang = System.currentTimeMillis();
            if (dalamJedaCek(TgBackup.amanLong(sp, KEY_CEK_GANGGUAN_AT, 0), sekarang)) {
                return "Cek web vault tertunda (jaringan GitHub terganggu);"
                        + " versi terpasang tetap dipakai.";
            }
            HasilChecksum probe = fetchChecksumRinci(ctx,
                    RELEASE_URL + "v" + latest + "/web-vault.zip.sha256",
                    10000, 15000);
            if (probe.hex == null) {
                if (probe.sementara) {
                    sp.edit().putLong(KEY_CEK_GANGGUAN_AT, sekarang).apply();
                    return "Cek web vault terganggu jaringan; versi terpasang tetap"
                            + " dipakai (dicek ulang nanti).";
                }
                return "Web vault v" + latest + " belum tersedia; versi terpasang"
                        + " tetap dipakai (dicek ulang otomatis nanti).";
            }
            sp.edit().remove(KEY_WV_FALLBACK_FOR).remove(KEY_CEK_GANGGUAN_AT).apply();
        }

        if (wvExists && installed.isEmpty() && latest != null) {
            String sidikLama = TgBackup.amanString(sp, KEY_WV_SHA, "");
            if (!sidikLama.isEmpty()) {
                long sekarang = System.currentTimeMillis();
                if (dalamJedaCek(TgBackup.amanLong(sp, KEY_CEK_GANGGUAN_AT, 0), sekarang)) {
                    return "Cek web vault tertunda (jaringan GitHub terganggu);"
                            + " versi terpasang tetap dipakai.";
                }
                HasilChecksum shaTarget = fetchChecksumRinci(ctx,
                        RELEASE_URL + "v" + latest + "/web-vault.zip.sha256",
                        10000, 15000);
                if (shaTarget.sementara) {

                    sp.edit().putLong(KEY_CEK_GANGGUAN_AT, sekarang).apply();
                    return "Cek web vault terganggu jaringan; versi terpasang tetap"
                            + " dipakai (dicek ulang nanti).";
                }
                if (shaTarget.hex != null && expectedHexEquals(shaTarget.hex, sidikLama)) {
                    sp.edit().putString(KEY_WV_FROM, latest).apply();
                    return "Web vault sudah versi terbaru: v" + latest;
                }
            }
        }

        long free = TgBackup.freeBytes(ctx, dataDir);
        if (free >= 0 && free < MIN_FREE_FOR_WEBVAULT) {
            throw new IOException("Sisa penyimpanan tinggal " + TgBackup.humanBytes(free)
                    + " - butuh minimal 150 MB untuk update web-vault.");
        }

        String zipUrl = latest != null ? RELEASE_URL + "v" + latest + "/web-vault.zip"
                : WV_UPDATE_URL;

        final boolean[] wvLewatTerbaru = {false};
        final String[] urlZipAkhir = {zipUrl};
        final String[] shaDapat = {null};
        try {
            unduhKeTmp(ctx, zipUrl, tmpZip, "web vault", 20000, 120000,
                    new UrlCadangan() {
                        @Override
                        public String ganti(String url, int kode) {
                            if (!adaKuncianWebVault(ctx) && latest != null
                                    && !wvLewatTerbaru[0]
                                    && url.equals(urlZipAkhir[0])) {
                                wvLewatTerbaru[0] = true;
                                urlZipAkhir[0] = WV_UPDATE_URL;
                                return urlZipAkhir[0];
                            }
                            return null;
                        }
                    },
                    shaDariRilis(ctx, 20000, 60000,
                            "Verifikasi SHA-256 web-vault tidak bisa diambil", shaDapat));
        } catch (IOException e) {
            throw new IOException(pesanGalatUnduh("Unduh web-vault", e));
        }

        boolean wvFallback = wvLewatTerbaru[0];
        if (tmpZip.length() < 1000) {
            tmpZip.delete();
            throw new IOException(pesanGalatUnduh("Unduh web-vault",
                    new IOException("file tidak valid")));
        }

        String expectedSha = shaDapat[0];

        File newDir = stagingWebVault(dataFolder);
        deleteRecursive(newDir);
        newDir.mkdirs();
        tandaiStagingAktif(newDir);
        String kanonBasis;
        try {
            kanonBasis = newDir.getCanonicalPath();
        } catch (Exception e) {
            lepasStagingAktif(newDir);
            deleteRecursive(newDir);
            throw new IOException("Gagal menyiapkan folder web-vault: " + e.getMessage());
        }
        String awalanAman = kanonBasis + File.separator;
        byte[] buf = new byte[64 * 1024];

        java.util.Set<String> dirSiap = new java.util.HashSet<>();

        boolean terpasang = false;
        try {
            long totalUnzip = 0;
            int jumlahEntri = 0;
            try {
                try (ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(tmpZip))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String namaEntri = entry.getName();
                        jumlahEntri++;

                        Util.tambahUkuranUnzip(0, 0, Util.BATAS_UNZIP_WEBVAULT,
                                jumlahEntri, Util.BATAS_JUMLAH_ENTRI);

                        if (!amanEntriZip(namaEntri)) {
                            zis.closeEntry();
                            continue;
                        }
                        File outFile = new File(newDir, namaEntri);

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
                        if (entry.isDirectory()) {
                            if (dirSiap.add(outFile.getAbsolutePath())) {
                                outFile.mkdirs();
                            }
                        } else {
                            File parent = outFile.getParentFile();
                            if (parent != null
                                    && dirSiap.add(parent.getAbsolutePath())) {
                                parent.mkdirs();
                            }
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                int n;
                                while ((n = zis.read(buf)) != -1) {
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

                lepasStagingAktif(newDir);
                deleteRecursive(newDir);
                tmpZip.delete();
                throw new IOException(pesanGalatUnduh("Unduh web-vault", e));
            } catch (IOException e) {
                String rendah = (e.getClass().getSimpleName() + " " + String.valueOf(e.getMessage()))
                        .toLowerCase(Locale.US);
                if (rendah.contains("zipexception") || rendah.contains("stored block")
                        || rendah.contains("invalid block") || rendah.contains("eocd")
                        || rendah.contains("truncated") || rendah.contains("not a zip")) {
                    lepasStagingAktif(newDir);
                    deleteRecursive(newDir);
                    tmpZip.delete();
                    throw new IOException(pesanGalatUnduh("Unduh web-vault", e));
                }
                lepasStagingAktif(newDir);
                deleteRecursive(newDir);
                throw e;
            }
            tmpZip.delete();

            File index = new File(newDir, "index.html");
            if (!index.exists()) {
                lepasStagingAktif(newDir);
                deleteRecursive(newDir);
                throw new IOException("Web vault updated tapi index.html tidak ditemukan"
                        + " - versi lama dipertahankan.");
            }

            synchronized (KUNCI_WEBVAULT) {
                File bakDir = new File(dataFolder, "web-vault.bak");
                deleteRecursive(bakDir);
                boolean adaLama = targetDir.exists();
                if (adaLama && !targetDir.renameTo(bakDir)) {

                    lepasStagingAktif(newDir);
                    deleteRecursive(newDir);
                    throw new IOException("Gagal mencadangkan web vault lama"
                            + " - versi lama dipertahankan.");
                }
                if (!newDir.renameTo(targetDir)) {
                    lepasStagingAktif(newDir);
                    deleteRecursive(newDir);
                    if (adaLama) {
                        bakDir.renameTo(targetDir);
                    }
                    throw new IOException("Gagal memasang web vault baru"
                            + " - versi lama dipertahankan.");
                }
                deleteRecursive(bakDir);
                terpasang = true;
                lepasStagingAktif(newDir);
            }
        } finally {
            lepasStagingAktif(newDir);
            if (!terpasang) {
                try {
                    deleteRecursive(newDir);
                } catch (Throwable ignored) {

                }
            }
        }
        String capWv = versiWvUntukCap(latest, wvFallback);
        if (capWv != null) {
            sp.edit().putString(KEY_WV_FROM, capWv).apply();
        }

        if (expectedSha != null && !expectedSha.isEmpty()) {
            sp.edit().putString(KEY_WV_SHA, expectedSha).apply();
        }
        if (latest != null && !wvFallback) {
            sp.edit().remove(KEY_WV_FALLBACK_FOR).apply();
        } else if (latest != null && wvFallback) {
            sp.edit().putString(KEY_WV_FALLBACK_FOR, latest).apply();
        }
        return "Web vault updated di " + targetDir.getAbsolutePath() + " " + WV_UPDATED_MARKER;
    }

    private static volatile String sBundledVersion;
    private static volatile boolean sBundledLoaded;

    public static String readBundledVersionRaw(Context ctx) {
        synchronized (KUNCI_VERSI) {
            if (sBundledLoaded) {
                return sBundledVersion;
            }
        }
        String v = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("vw_version.txt"), StandardCharsets.UTF_8))) {
            String baris = r.readLine();

            String bRapi = baris == null ? "" : baris.trim();
            v = bRapi.isEmpty() ? null : bRapi;
        } catch (Exception ignored) {
        }
        synchronized (KUNCI_VERSI) {
            if (!sBundledLoaded) {
                sBundledVersion = v;
                sBundledLoaded = true;
            }
            return sBundledVersion;
        }
    }

    public static String readBundledVersionLabel(Context ctx) {
        String v = readBundledVersionRaw(ctx);
        return (v == null || v.isEmpty()) ? "Version: ?" : "Version: " + v;
    }

    public static String currentServerVersion(Context ctx) {
        String real = parseBinaryVersion(ServerService.binaryVersion);
        if (real != null) {
            return real;
        }
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                Context.MODE_PRIVATE);
        String updated = TgBackup.amanString(sp, ServerService.KEY_UPDATE_VERSION, "");
        if (updated != null && !updated.isEmpty()) {
            return updated;
        }
        return readBundledVersionRaw(ctx);
    }

    static final int BATAS_WV_VERSION = 16 * 1024;

    public static String readWvVersion(File f) {
        if (f == null || !f.isFile() || f.length() > BATAS_WV_VERSION) {
            return null;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
                if (sb.length() > BATAS_WV_VERSION) {
                    return null;
                }
            }
            String v = ambilNilaiJson(sb.toString(), "version");
            return (v == null || v.isEmpty()) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    static String ambilNilaiJson(String json, String kunci) {
        if (json == null || kunci == null || kunci.isEmpty()) {
            return null;
        }
        String cari = "\"" + kunci + "\"";
        int i = json.indexOf(cari);
        if (i < 0) {
            return null;
        }
        int titikDua = json.indexOf(':', i + cari.length());
        if (titikDua < 0) {
            return null;
        }
        int awal = titikDua + 1;
        while (awal < json.length() && Character.isWhitespace(json.charAt(awal))) {
            awal++;
        }
        if (awal >= json.length() || json.charAt(awal) != '\"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int j = awal + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (c == '\\' && j + 1 < json.length()) {
                char n = json.charAt(++j);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    default: sb.append(n); break;
                }
            } else if (c == '\"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    public static String normVersion(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        if (t.startsWith("v") || t.startsWith("V")) {
            t = t.substring(1);
        }

        int plus = t.indexOf("+");
        String hasil = plus < 0 ? t : t.substring(0, plus);
        if (hasil.isEmpty()) {
            return hasil;
        }

        if (!POLA_TAG_AMAN.matcher(hasil).matches()) {
            return null;
        }
        return hasil;
    }

    static boolean versiCocok(String a, String b) {

        String ra = normVersion(a);
        String rb = normVersion(b);
        if (ra == null || ra.isEmpty() || rb == null || rb.isEmpty()) {
            return false;
        }
        return bandingVersi(ra, rb) == 0;
    }

    static int bandingVersi(String a, String b) {
        int[] va = uraiVersi(a);
        int[] vb = uraiVersi(b);
        int n = Math.max(va.length, vb.length);
        for (int i = 0; i < n; i++) {
            int x = i < va.length ? va[i] : 0;
            int y = i < vb.length ? vb[i] : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    private static int[] uraiVersi(String v) {
        if (v == null) {
            return new int[0];
        }
        String t = v.trim();
        if (t.startsWith("v") || t.startsWith("V")) {
            t = t.substring(1);
        }
        if (t.isEmpty()) {
            return new int[0];
        }

        int nSeg = 1;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '.') {
                nSeg++;
            }
        }
        int[] keluar = new int[nSeg];
        int hitung = 0;
        int awal = 0;
        for (int i = 0; i <= t.length(); i++) {
            if (i == t.length() || t.charAt(i) == '.') {
                int angka = angkaAwalan(t.substring(awal, i));
                awal = i + 1;
                if (angka < 0) {
                    boolean ekor = true;
                    for (int j = i; j < t.length(); j++) {
                        if (t.charAt(j) != '.') {
                            ekor = false;
                            break;
                        }
                    }
                    if (ekor) {
                        break;
                    }
                    return new int[0];
                }
                keluar[hitung++] = angka;
            }
        }
        if (hitung == keluar.length) {
            return keluar;
        }
        return java.util.Arrays.copyOf(keluar, hitung);
    }

    static int angkaAwalan(String segmen) {
        if (segmen == null) {
            return -1;
        }
        String t = segmen.trim();
        int n = 0;
        boolean ada = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            ada = true;
            n = n * 10 + (c - '0');
            if (n > 1000000) {
                return 1000000;
            }
        }
        return ada ? n : -1;
    }

    private static final java.util.regex.Pattern POLA_PIN_VERSI =
            java.util.regex.Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(-[A-Za-z0-9.]+)?(\\+[A-Za-z0-9.-]+)?");
    private static final java.util.regex.Pattern POLA_TAG_AMAN =
            java.util.regex.Pattern.compile("[0-9][0-9A-Za-z.\\-]*");
    private static final java.util.regex.Pattern POLA_SPASI =
            java.util.regex.Pattern.compile("\\s+");

    static boolean hex64(String s) {
        if (s == null || s.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static final java.util.regex.Pattern VERSION_PATTERN =
            java.util.regex.Pattern.compile("\\d+\\.\\d+\\.\\d+");

    private static final class MemoVersi {
        final String masuk;
        final String hasil;

        MemoVersi(String masuk, String hasil) {
            this.masuk = masuk;
            this.hasil = hasil;
        }
    }

    private static volatile MemoVersi memoBin = null;

    static String parseBinaryVersion(String raw) {
        if (raw == null) {
            return null;
        }
        MemoVersi memo = memoBin;
        if (memo != null && raw.equals(memo.masuk)) {
            return memo.hasil;
        }
        java.util.regex.Matcher m = VERSION_PATTERN.matcher(raw);
        String hasil = m.find() ? m.group() : null;
        memoBin = new MemoVersi(raw, hasil);
        return hasil;
    }

    static String detectVersion(Context ctx, File binary) {

        final java.util.concurrent.atomic.AtomicBoolean versiSelesai =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread versiWatchdog = null;
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(), "--version")
                    .redirectErrorStream(true);
            if (ctx != null && KernelCompat.legacyPerangkat()) {
                File shim = new File(ctx.getFilesDir(), "bin/" + KernelCompat.SHIM_ASSET);
                if (shim.exists()) {
                    pb.environment().put("LD_PRELOAD", shim.getAbsolutePath());
                }
            }
            p = pb.start();

            versiWatchdog = Util.pasangWatchdogProses(p, versiSelesai, 10000,
                    "vw-detect-watchdog");

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
            versiSelesai.set(true);
            versiWatchdog.interrupt();

            boolean mati;
            try {
                mati = ServerService.waitForOrKill(p, 10_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
            return mati ? parseBinaryVersion(first) : null;
        } catch (Exception e) {
            return null;
        } finally {

            versiSelesai.set(true);
            try {
                if (versiWatchdog != null) {
                    versiWatchdog.interrupt();
                }
            } catch (Exception ignored) {
            }
            ServerService.matikanProsesTegas(p, 2000);
        }
    }

    static String extractTag(String body) {
        if (body == null) {
            return null;
        }
        java.util.List<String> tag = pindaiTag(body, 1);
        return tag.isEmpty() ? null : tag.get(0);
    }

    static boolean isElf(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] h = new byte[20];
            int baca = 0;
            while (baca < h.length) {
                int n = in.read(h, baca, h.length - baca);
                if (n < 0) {
                    break;
                }
                baca += n;
            }
            if (baca < 20 || h[0] != 0x7F || h[1] != 'E'
                    || h[2] != 'L' || h[3] != 'F') {
                return false;
            }

            int mesin;
            if (h[5] == 2) {
                mesin = ((h[18] & 0xFF) << 8) | (h[19] & 0xFF);
            } else {
                mesin = (h[18] & 0xFF) | ((h[19] & 0xFF) << 8);
            }
            return mesin == 40;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean kodeSementara(int kode) {
        return kode == 403 || kode == 408 || kode == 429 || kode >= 500;
    }

    private static String fetchChecksum(Context ctx, String url,
                                        int connectMs, int readMs) {
        return fetchChecksumRinci(ctx, url, connectMs, readMs).hex;
    }

    private static HasilChecksum fetchChecksumRinci(Context ctx, String url,
                                                    int connectMs, int readMs) {

        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) {
            return HasilChecksum.BELUM_TERBIT;
        }
        for (int coba = 1; coba <= 2; coba++) {
            HttpURLConnection c = null;
            try {
                c = open(ctx, url, connectMs, readMs);
                int kode = c.getResponseCode();
                if (kode != 200) {

                    if (kodeSementara(kode)) {
                        throw new IOException("HTTP " + kode);
                    }
                    return HasilChecksum.BELUM_TERBIT;
                }
                java.util.List<String> baris = new java.util.ArrayList<>();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        c.getInputStream(), StandardCharsets.UTF_8))) {

                    for (int i = 0; i < 200; i++) {
                        String b = r.readLine();
                        if (b == null) {
                            break;
                        }
                        if (b.length() > 4096) {
                            b = b.substring(0, 4096);
                        }
                        baris.add(b);
                    }
                }
                if (baris.isEmpty()) {
                    return HasilChecksum.BELUM_TERBIT;
                }

                String hex = pindaiHexDariBaris(baris, namaAssetDariUrl(url));
                return hex == null ? HasilChecksum.BELUM_TERBIT
                        : new HasilChecksum(hex, false);
            } catch (Exception e) {
                if (coba >= 2) {
                    return HasilChecksum.GANGGUAN;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return HasilChecksum.GANGGUAN;
                }
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
        return HasilChecksum.GANGGUAN;
    }

    static final class HasilChecksum {
        final String hex;

        final boolean sementara;

        private HasilChecksum(String hex, boolean sementara) {
            this.hex = hex;
            this.sementara = sementara;
        }

        static final HasilChecksum BELUM_TERBIT = new HasilChecksum(null, false);

        static final HasilChecksum GANGGUAN = new HasilChecksum(null, true);
    }

    static final long JEDA_CEK_GANGGUAN_MS = 15L * 60L * 1000L;

    static final String KEY_CEK_GANGGUAN_AT = "cek_gangguan_at";

    static boolean dalamJedaCek(long terakhirGagal, long sekarang) {
        return terakhirGagal > 0 && sekarang >= terakhirGagal
                && sekarang - terakhirGagal < JEDA_CEK_GANGGUAN_MS;
    }

    static String pindaiHexDariBaris(java.util.List<String> baris, String namaAsset) {
        if (baris == null) {
            return null;
        }
        String kunci = namaAsset == null ? "" : namaAsset.trim().toLowerCase(java.util.Locale.US);
        if (!kunci.isEmpty()) {
            for (String b : baris) {
                if (b != null && mengandungAbaikanHuruf(b, kunci)) {
                    String dapat = pindaiHexChecksum(b);
                    if (dapat != null) {
                        return dapat;
                    }
                }
            }
        }
        for (String b : baris) {
            String dapat = pindaiHexChecksum(b);
            if (dapat != null) {
                return dapat;
            }
        }
        return null;
    }

    static String namaAssetDariUrl(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        int potong = u.length();
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c == '?' || c == '#') {
                potong = i;
                break;
            }
        }
        u = u.substring(0, potong);
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        int miring = u.lastIndexOf('/');
        String nama = miring < 0 ? u : u.substring(miring + 1);
        if (nama.endsWith(".sha256")) {
            nama = nama.substring(0, nama.length() - ".sha256".length());
        }
        return nama;
    }

    static boolean mengandungAbaikanHuruf(String hay, String jarumKecil) {
        if (hay == null || jarumKecil == null || jarumKecil.isEmpty()) {
            return false;
        }
        int n = hay.length();
        int m = jarumKecil.length();
        if (m > n) {
            return false;
        }
        for (int i = 0; i <= n - m; i++) {
            if (hay.regionMatches(true, i, jarumKecil, 0, m)) {
                return true;
            }
        }
        return false;
    }

    static String pindaiHexChecksum(String line) {
        if (line == null) {
            return null;
        }

        String bersih = line.indexOf('\uFEFF') >= 0 ? line.replace("\uFEFF", "") : line;
        bersih = bersih.trim();
        if (bersih.isEmpty()) {
            return null;
        }
        for (String tok : POLA_SPASI.split(bersih)) {

            int dari = 0;
            while (true) {
                int eq = tok.indexOf('=', dari);
                String bagian = eq < 0 ? tok.substring(dari) : tok.substring(dari, eq);
                String t = bagian.trim();
                if (hex64(t)) {
                    return t.toLowerCase(Locale.US);
                }
                if (eq < 0) {
                    break;
                }
                dari = eq + 1;
            }
        }
        return null;
    }

    static String toHex(byte[] digest) {
        return Util.heks(digest);
    }

    static long panjangKonten(HttpURLConnection dl) {
        try {
            String v = dl.getHeaderField("Content-Length");
            if (v != null) {
                long n = Long.parseLong(v.trim());
                if (n >= 0) {
                    return n;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static void digestPrefix(java.security.MessageDigest md, File f, long len)
            throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            long left = len;
            int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) != -1) {
                md.update(buf, 0, n);
                left -= n;
            }

            if (left > 0) {
                throw new IOException("File parsial rusak (kurang " + left
                        + " byte) - unduh ulang dari nol.");
            }
        }
    }

    static boolean expectedHexEquals(String expectedHex, String gotHex) {
        if (expectedHex == null || gotHex == null) {
            return false;
        }

        int aAwal = 0;
        int aAkhir = expectedHex.length();
        while (aAwal < aAkhir && expectedHex.charAt(aAwal) <= ' ') {
            aAwal++;
        }
        while (aAkhir > aAwal && expectedHex.charAt(aAkhir - 1) <= ' ') {
            aAkhir--;
        }
        int bAwal = 0;
        int bAkhir = gotHex.length();
        while (bAwal < bAkhir && gotHex.charAt(bAwal) <= ' ') {
            bAwal++;
        }
        while (bAkhir > bAwal && gotHex.charAt(bAkhir - 1) <= ' ') {
            bAkhir--;
        }
        if (aAkhir - aAwal != bAkhir - bAwal) {
            return false;
        }
        int beda = 0;
        for (int i = 0; i < aAkhir - aAwal; i++) {
            char ca = expectedHex.charAt(aAwal + i);
            char cb = gotHex.charAt(bAwal + i);
            if (ca >= 'A' && ca <= 'F') {
                ca = (char) (ca + 32);
            }
            if (cb >= 'A' && cb <= 'F') {
                cb = (char) (cb + 32);
            }
            beda |= ca ^ cb;
        }
        return beda == 0;
    }

    static String sha256Hex(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = Util.mdSha256();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    static boolean amanEntriZip(String nama) {
        if (nama == null || nama.isEmpty()) {
            return false;
        }
        String n = nama.replace('\\', '/');
        if (n.isEmpty() || n.charAt(0) == '/' || n.contains(":") || n.contains("//")
                || n.equals("..") || n.startsWith("../")
                || n.contains("/../") || n.endsWith("/..")) {
            return false;
        }

        if (n.equals(".") || n.startsWith("./")
                || n.contains("/./") || n.endsWith("/.")) {
            return false;
        }

        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    static boolean tautanSimbol(File file) {
        if (file == null) {
            return false;
        }
        try {
            File absolut = file.getAbsoluteFile();
            File induk = absolut.getParentFile();
            if (induk == null) {
                return false;
            }
            File kanonTanpaTautan = new File(induk.getCanonicalFile(), absolut.getName());
            return !absolut.getCanonicalFile().equals(kanonTanpaTautan);
        } catch (Exception e) {

            return true;
        }
    }

    static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        if (tautanSimbol(file)) {
            file.delete();
            return;
        }
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
