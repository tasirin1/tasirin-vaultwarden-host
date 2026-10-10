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
        String t = latest == null ? "" : latest.trim();
        if (t.startsWith("v") || t.startsWith("V")) {
            t = t.substring(1);
        }
        if (!t.isEmpty()) {
            return RELEASE_URL + "v" + t + "/vaultwarden-" + abi;
        }
        return RELEASE_LATEST_URL + "vaultwarden-" + abi;
    }

    /** URL asset shim getrandom untuk versi resmi tertentu; fallback latest bila tak dikenal. */
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

    /** Daftar rilis repo build (sumber asset binary & web-vault Android). */
    private static final String RELEASE_LIST_API =
            "https://api.github.com/repos/tasirin1/tasirin-vaultwarden-host/releases?per_page=30";

    /** Validasi kuncian versi user ("1.32.0", "v1.32", "1.37.3-beta");
     *  kembalikan tanpa huruf v, atau null bila tak valid. Murni. */
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
        // Wajib 3 bagian (x.y.z): kuncian 2 bagian ("1.32") lolos banding
        // versi tapi URL asset v1.32 selalu 404. Tolak di sini agar
        // simpanKuncian gagal lantang dan pin lama bertahan.
        // Metadata build semver (+build) bukan bagian rilis: kupas agar
        // kuncian versi tetap cocok dengan tag asset tanpa fallback ke latest.
        int plus = t.indexOf("+");
        return plus < 0 ? t : t.substring(0, plus);
    }

    /** Saran versi stabil bila kuncian prerelease ("1.37.3-beta") tak ada assetnya:
     *  rilis repo ini mengikuti tag stabil upstream sehingga pin beta tak pernah
     *  resolve; kembalikan saran "; ..." atau kosong bila tak relevan. Murni. */
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

    /** Kuncian binary user (null = ikuti terbaru). */
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

    /** Kuncian web-vault user (null = ikuti terbaru). */
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

    /** Pilih target unduhan: kuncian valid menang atas terbaru. Murni. */
    static String pilihTarget(String kuncian, String latest) {
        if (kuncian != null && !kuncian.isEmpty()) {
            return kuncian;
        }
        return latest;
    }

    /** Versi binary yang dituju (kuncian user atau rilis resmi terbaru). */
    public static String versiTargetBinary(Context ctx) {
        return pilihTarget(kuncianBinary(ctx), latestVersion(ctx));
    }

    /** Versi web-vault yang dituju (kuncian user atau rilis resmi terbaru). */
    public static String versiTargetWebVault(Context ctx) {
        return pilihTarget(kuncianWebVault(ctx), latestVersion(ctx));
    }

    /** Simpan kuncian versi (null/kosong/"terbaru" = ikuti terbaru).
     *  Kembalikan false bila versi tak valid sehingga pin lama bertahan;
     *  pemanggil wajib memberi pesan, bukan mengira tersimpan. */
    public static boolean simpanKuncianBinary(Context ctx, String versi) {
        return simpanKuncian(ctx, ServerService.KEY_BIN_PILIH, versi);
    }

    /** Simpan kuncian versi (null/kosong/"terbaru" = ikuti terbaru).
     *  Kembalikan false bila versi tak valid sehingga pin lama bertahan. */
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

    /** Urai semua tag_name dari body JSON daftar rilis (terbaru dulu, maks N).
     *  Struktural seperti extractTag agar literal di dalam string tak ikut.
     *  Murni agar bisa unit test. */
    static java.util.List<String> parseDaftarTag(String body, int maks) {
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

    /** Daftar versi yang punya asset di repo build (terbaru dulu, maks N);
     *  kosong bila offline/rate-limit (pemanggil pakai input manual). */
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
    // Penanda versi vaultwarden pemilik web-vault yang terpasang (supaya tidak
    // mengunduh ulang ~35 MB tiap kali tombol "Update Web Vault" ditekan).
    private static final String KEY_WV_FROM = "wv_from_version";
    /** Versi yang dipasang via fallback rilis terbaru (asset versi belum terbit). */
    static final String KEY_WV_FALLBACK_FOR = "wv_fallback_for";
    /** Jangkar elapsed saat fallback dipasang (SystemClock, ms). */
    static final String KEY_WV_FALLBACK_AT = "wv_fallback_at";
    /** Jeda unduh ulang setelah pasang fallback (asset CI terbit ~12 jam). */
    static final long WV_FALLBACK_TUNDA_MS = 6L * 3600 * 1000;

    /** True bila fallback untuk versi ini baru dipasang dan web-vault ada:
     *  lewati unduh ulang 35 MB agar hemat kuota. Murni agar bisa unit test. */
    static boolean fallbackBaruSaja(String latest, boolean wvExists, String untuk,
            long at, long kiniElapsed) {
        if (!wvExists || latest == null || latest.isEmpty()) {
            return false;
        }
        if (!versiCocok(latest, untuk)) {
            return false;
        }
        if (at <= 0 || kiniElapsed < at) {
            return false;
        }
        return kiniElapsed - at < WV_FALLBACK_TUNDA_MS;
    }
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

    /** Ikuti redirect manual maks 5x, hanya https + host GitHub.
     *  Checksum (.sha256) diambil dari host yang sama sehingga redirect ke
     *  host sembarang = penyerang bisa menyajikan binary + SHA cocok. */
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
                        || !Util.bolehIkutiRedirectGithub(kini, lok)) {
                    throw new IOException("Redirect tidak aman/ditolak: " + lok);
                }
                String berikut = Util.sambungRedirect(kini, lok);
                // sambungRedirect fail-closed menahan downgrade http:// di URL dasar
                // (berikut == kini): laporkan sebagai penolakan downgrade yang jelas,
                // bukan "Terlalu banyak redirect" yang menyesatkan.
                if (berikut == null || berikut.equals(kini)) {
                    throw new IOException("Redirect tidak aman/ditolak (downgrade?): " + lok);
                }
                if (hostBerubah(kini, berikut)) {
                    // Lintas host (github.com -> CDN): offset resume milik file
                    // host lama tak berlaku di host baru (campur dua asset lalu
                    // gagal SHA + buang kuota). Ulang dari nol.
                    resumeFrom = 0;
                }
                kini = berikut;
                continue;
            }
            return c;
        }
        throw new IOException("Terlalu banyak redirect.");
    }

    /** True bila host/skema/port dua URL berbeda (murni agar bisa unit test).
     *  Gagal urai = anggap berubah (arah aman: unduh ulang dari nol, bukan
     *  campur byte dua asset). Port bawaan skema (-1 = 443 https / 80 http)
     *  disamakan agar redirect berport eksplisit tak mengulang sia-sia. */
    static boolean hostBerubah(String a, String b) {
        try {
            java.net.URL ua = new java.net.URL(a);
            java.net.URL ub = new java.net.URL(b);
            if (!ua.getProtocol().equalsIgnoreCase(ub.getProtocol())) {
                return true;
            }
            int pa = ua.getPort() < 0 ? ua.getDefaultPort() : ua.getPort();
            int pb = ub.getPort() < 0 ? ub.getDefaultPort() : ub.getPort();
            if (pa != pb) {
                return true;
            }
            String ha = ua.getHost() == null ? "" : ua.getHost().toLowerCase(java.util.Locale.US);
            String hb = ub.getHost() == null ? "" : ub.getHost().toLowerCase(java.util.Locale.US);
            return !ha.equals(hb);
        } catch (Exception e) {
            return true;
        }
    }

    /** Gabungan pesan + seluruh cause direndahkan: bungkus SSL sering
     *  menyembunyikan inti (mis. ExtCertPathValidatorException) di cause. */
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
        return sb.toString().toLowerCase(Locale.US);
    }

    /** True bila galat TLS disebabkan jam STB salah (sertifikat belum berlaku /
     *  sudah kadaluarsa menurut jam perangkat). Murni lewat rantai pesan. */
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

    /** Format tanggal STB per thread: SimpleDateFormat init berat dan tak
     *  thread-safe sehingga tak boleh dibuat baru tiap pesan galat maupun
     *  dipakai bersama antar thread. */
    private static final ThreadLocal<java.text.SimpleDateFormat> FMT_STB =
            new ThreadLocal<java.text.SimpleDateFormat>() {
                @Override protected java.text.SimpleDateFormat initialValue() {
                    return new java.text.SimpleDateFormat(
                            "d MMM yyyy HH:mm", new Locale("id", "ID"));
                }
            };

    /** Tanggal jam STB terbaca saat ini (untuk pesan galat jam salah). */
    static String tanggalStbTerbaca() {
        try {
            return FMT_STB.get().format(new java.util.Date(System.currentTimeMillis()));
        } catch (Exception ignored) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    /** Saran perbaikan koneksi (Bahasa Indonesia) berdasarkan jenis galat. */
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
                || pesanRendah.contains("/127.") || pesanRendah.contains("/0.0.0.0")
                || pesanRendah.contains("/100.") || pesanRendah.contains("fe80")
                || pesanRendah.contains("fd00") || pesanRendah.contains("::1")
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

    /** True bila respons 206 benar melanjutkan dari byte yang diminta: awal
     *  Content-Range wajib sama dengan ukuran file parsial. Tanpa cek ini,
     *  proksi menyimpang bisa mencampur byte dan baru ketahuan di checksum
     *  setelah kuota terbuang. Murni agar bisa unit test. */
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

    /** Klasifikasi galat unduh yang layak dicoba ulang (dipakai binary & shim).
     *  Murni agar bisa unit test. */
    static boolean bolehCobaLagiUnduh(Exception e) {
        String rendah = String.valueOf(e == null ? null : e.getMessage()).toLowerCase(Locale.US);
        // 404 versi (file memang belum ada) jangan di-retry sia-sia.
        if (rendah.contains("404") || rendah.contains("belum tersedia")) {
            return false;
        }
        // File parsial terpotong (storage disentuh saat unduh) atau campuran
        // byte resume salah (Content-Range tak cocok, checksum gagal): buang
        // lalu unduh ulang dari nol, bukan gagal fatal.
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

    /** Kunci swap web-vault: dipakai Updater + ServerService.cleanupTempFiles
     *  agar Start dan dua update konkuren tak berebut folder staging yang sama.
     *  Tiap update mengekstrak ke staging unik (stagingWebVault) lalu tukar di
     *  bawah kunci ini; cleanup menyapu sisa staging yatim (melewati STAGING_AKTIF). */
    public static final Object KUNCI_WEBVAULT = new Object();

    /** Nama staging web-vault yang sedang diekstrak/ditukar (bukan yatim).
     *  Melindungi dari cleanup Start: pola sisaStagingWebVault cocok ke SEMUA
     *  web-vault.new-<cap> termasuk yang aktif, jadi update menandai stagingnya
     *  di sini dan melepas di semua titik keluar; cleanup melewati nama terdaftar.
     *  Murni-JVM (tanpa API Android). */
    private static final java.util.Set<String> STAGING_AKTIF =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** Tandai staging aktif agar cleanup tak menyapunya. Murni. */
    static void tandaiStagingAktif(java.io.File dir) {
        if (dir != null && dir.getName() != null) {
            STAGING_AKTIF.add(dir.getName());
        }
    }

    /** Lepas tanda aktif (dipanggil di semua titik keluar ekstrak/swap). Murni. */
    static void lepasStagingAktif(java.io.File dir) {
        if (dir != null && dir.getName() != null) {
            STAGING_AKTIF.remove(dir.getName());
        }
    }

    /** True bila nama staging sedang aktif (dilindungi dari cleanup). Murni. */
    static boolean stagingAktif(String nama) {
        return nama != null && STAGING_AKTIF.contains(nama);
    }

    /** Folder staging unik untuk satu kali ekstrak web-vault
     *  (mis. web-vault.new-20261001-120000-000-ab12): update UI dan bot yang
     *  jalan bersamaan tak lagi menimpa hasil ekstrak satu sama lain.
     *  Nama unik saja TAK cukup melawan cleanup Start (pola sisaStagingWebVault
     *  cocok ke web-vault.new-<cap>): yang melindungi staging aktif adalah
     *  registry STAGING_AKTIF di atas. Murni. */
    static java.io.File stagingWebVault(java.io.File dataFolder) {
        return new java.io.File(dataFolder, "web-vault.new-" + TgBackup.stempelUnik());
    }

    /** True bila nama file adalah sisa staging ekstrak web-vault (lama
     *  "web-vault.new" bersama maupun unik "web-vault.new-<cap>"): yatim yang
     *  aman disapu cleanup. "web-vault.newbie" bukan staging. Murni. */
    static boolean sisaStagingWebVault(String nama) {
        if (nama == null) {
            return false;
        }
        return nama.equals("web-vault.new") || nama.startsWith("web-vault.new-");
    }

    /** Kunci unduhan per file agar binary/shim/web-vault tak saling blokir.
     *  Dulu `synchronized` per-kelas: Start tertahan menit saat update lain jalan.
     *  Kunci = objek per path kanonis (tanpa String.intern global yang tak pernah GC
     *  dan bisa kontensi lintas-JVM). */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> KUNCI_UNDUH =
            new java.util.concurrent.ConcurrentHashMap<>();
    static Object kunciUnduh(File tmp) {
        String kunci;
        try {
            kunci = tmp.getCanonicalPath();
        } catch (Exception e) {
            kunci = tmp.getAbsolutePath();
        }
        Object ada = KUNCI_UNDUH.get(kunci);
        if (ada != null) {
            return ada;
        }
        Object buat = new Object();
        Object menang = KUNCI_UNDUH.putIfAbsent(kunci, buat);
        return menang != null ? menang : buat;
    }

    /** Unduh satu file ke tmp dengan resume + retry + hash (dipakai binary,
     *  shim, dan web-vault agar tiga loop ~60 baris tak duplikat).
     *  Return hex SHA-256; lempar IOException terakhir bila gagal.
     *  Kunci per-file hanya dipegang selama percobaan jaringan; jeda 3 detik
     *  antar percobaan jalan DI LUAR kunci agar tak menahan pemanggil lain. */
    static String unduhKeTmp(Context ctx, String urlAwal, File tmp, String label,
                              int connectMs, int readMs, UrlCadangan cadangan)
            throws IOException {
        final String[] url = {urlAwal};
        final boolean[] fallbackUsed = {false};
        Exception gagal = null;
        for (int coba = 1; coba <= MAX_COBA_UNDUH; coba++) {
            try {
                synchronized (kunciUnduh(tmp)) {
                    return unduhSatuPercobaan(ctx, url, fallbackUsed, tmp, label,
                            connectMs, readMs, cadangan);
                }
            } catch (IOException e) {
                gagal = e;
                buangParsialRusak(tmp, e);
                // Fallback sengaja dipertahankan untuk semua percobaan berikut:
                // kembali ke URL asli menumpuk bytes dua asset berbeda dan membuat
                // digest tak lagi sesuai checksum URL fallback (gagal verifikasi
                // palsu). URL versi yang 404 tetap 404; unduhan berikut akan
                // mengambilnya lagi bila rilis sudah jadi.
                if (!bolehCobaLagiUnduh(e) || coba >= MAX_COBA_UNDUH) {
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
        if (gagal instanceof IOException) {
            throw (IOException) gagal;
        }
        throw new IOException(gagal == null ? "koneksi gagal" : String.valueOf(gagal));
    }

    /** Buang file parsial terpotong/campuran agar percobaan berikut unduh ulang
     *  dari nol (resume dari file rusak pasti gagal lagi, termasuk campuran
     *  byte resume salah yang baru ketahuan di checksum). */
    private static void buangParsialRusak(File tmp, IOException e) {
        try {
            String pesan = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
            if (pesan.contains("parsial") || pesan.contains("kurang")
                    || pesan.contains("terpotong") || pesan.contains("truncat")
                    || pesan.contains("content-range") || pesan.contains("416")
                    || pesan.contains("checksum") || pesan.contains("tidak cocok")
                    || pesan.contains("mismatch") || pesan.contains("corrupt")
                    || pesan.contains("rusak")) {
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    /** Satu percobaan unduh; wajib dipanggil dengan kunci per-file dipegang.
     *  Melempar Exception (bukan hanya IOException) karena open() TLS/proksi
     *  melempar checked Exception umum; unduhKeTmp sudah menangkap dan
     *  membungkusnya jadi IOException. */
    private static String unduhSatuPercobaan(Context ctx, String[] url, boolean[] fallbackUsed,
                              File tmp, String label,
                              int connectMs, int readMs, UrlCadangan cadangan)
            throws Exception {
        // Lanjutkan unduhan terputus (hemat kuota); server GitHub dukung Range.
        // Wajib isFile: path temp yang diduduki direktori membuat
        // FileOutputStream gagal terus sampai dibersihkan manual.
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
            if (perluResetResume(code, resumeFrom)) {
                // HTTP 416 = Range ditolak; HTTP 200 = server mengabaikan
                // Range. Ulang dari nol agar file tidak korup.
                dl.disconnect();
                tmp.delete();
                resumeFrom = 0;
                dl = open(ctx, url[0], connectMs, readMs);
                code = dl.getResponseCode();
            }
            if (code == 206 && resumeFrom > 0 && !rangeCocok(dl, resumeFrom)) {
                // Klaim 206 tapi awal Content-Range tak cocok dengan bytes
                // lanjutan (proksi menyimpang): buang parsial dan ulang
                // dari nol sebelum kuota terbuang sia-sia.
                dl.disconnect();
                tmp.delete();
                resumeFrom = 0;
                dl = open(ctx, url[0], connectMs, readMs);
                code = dl.getResponseCode();
            }
            if (code != 200 && code != 206) {
                throw new IOException("Unduhan gagal (HTTP " + code + ").");
            }
            return salinSambilHash(dl, tmp, code, resumeFrom, label);
        } finally {
            if (dl != null) {
                dl.disconnect();
            }
        }
    }

    /** Salin body unduhan ke file sementara sambil menghitung SHA-256
     *  (dipakai binary & shim agar tak baca ulang file ~15 MB). Return hex digest. */
    static String salinSambilHash(HttpURLConnection dl, File tmp, int kodeHttp,
                                  long lanjutDari, String label) throws IOException {
        long total = panjangKonten(dl);
        if (kodeHttp == 206 && total >= 0) {
            total += lanjutDari;
        }
        java.security.MessageDigest md;
        try {
            md = Util.mdSha256();
        } catch (Exception e) {
            throw new IOException("SHA-256 tidak tersedia: " + e.getMessage());
        }
        if (lanjutDari > 0) {
            // Kunci TOCTOU: file bisa berubah (cleanup/storage) antara baca
            // prefix dan append sehingga hash basi + data campur. Batalkan
            // lantang agar pemanggil unduh ulang dari nol.
            long capPanjang = tmp.length();
            long capUbah = tmp.lastModified();
            digestPrefix(md, tmp, lanjutDari);
            if (tmp.length() != capPanjang || tmp.lastModified() != capUbah) {
                throw new IOException("File parsial berubah saat di-hash"
                        + " - unduh ulang dari nol.");
            }
        }
        try (InputStream in = dl.getInputStream();
             FileOutputStream fos = new FileOutputStream(tmp, kodeHttp == 206)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            long done = lanjutDari;
            long lastReport = done;
            while ((n = in.read(buf)) != -1) {
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
    private static final Object KUNCI_VERSI = new Object();
    private static volatile String sLatestVersion;
    private static volatile long sLatestAt;
    /** Jeda ulang cek versi sesudah gagal (rate-limit/offline): 60 dtk. */
    static final long VERSION_GAGAL_TTL_MS = 60_000;
    private static volatile long sLatestGagalAt = 0;

    /** Murni: true bila cek terakhir gagal dan masih dalam jeda (jangan hantam API).
     *  kini<gagalAt (reboot me-reset elapsed) dianggap basi agar tetap coba. */
    static boolean gagalBaruSaja(long kiniElapsed, long gagalAt, long jedaMs) {
        if (gagalAt == 0 || kiniElapsed < gagalAt) {
            return false;
        }
        return kiniElapsed - gagalAt < jedaMs;
    }

    /** Versi resmi terbaru (tanpa huruf v) atau null bila belum pernah dapat. */
    public static String latestVersion(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        String cached;
        synchronized (KUNCI_VERSI) {
            cached = sLatestVersion;
            if (cached != null && now - sLatestAt < VERSION_TTL_MS) {
                return cached;
            }
            // Throttle berlaku juga saat cache basi ada: tiap Start saat offline
            // tak menghantam API (kembalikan cache basi selama jeda gagal).
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
            // Kode lain (mis. 403/429 rate-limit): pakai cache lama kalau ada.
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

    /** Unduh & pasang update binary ke versi target (kuncian user atau terbaru).
     *  Return pesan hasil. Lempar Exception bila gagal. */
    public static String tryUpdate(Context ctx) throws Exception {
        // Tanpa kuncian teruskan null (jalur ikuti-terbaru, ada cek "sudah terbaru");
        // teruskan versi bila dikunci agar jalur paksa tetap dipakai.
        String pin = kuncianBinary(ctx);
        return tryUpdateVersi(ctx, (pin == null || pin.isEmpty()) ? null : pin);
    }

    /** Unduh & pasang binary ke versi persis yang diminta (boleh lebih lama dari
     *  rilis terbaru = downgrade). Pemanggil menyimpan kuncian dulu agar
     *  auto-update tak langsung menaikkan lagi. Versi null = ikuti terbaru. */
    public static String tryUpdateVersi(Context ctx, String versiDiminta) throws Exception {
        // Versi eksplisit (pilihan user) dipasang bila file belum cocok —
        // termasuk downgrade; file yang sudah persis tak diunduh ulang.
        boolean paksa = versiDiminta != null && !versiDiminta.isEmpty();
        String latest = paksa ? versiDiminta : latestVersion(ctx);
        if (latest == null) {
            throw new IOException("Tidak bisa baca versi terbaru. " + saranKoneksi(null));
        }
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        // Revisi patch binary: CI bisa memperbaiki binary tanpa ganti versi Vaultwarden
        // (mis. patch TLS favicon). Versi sama tapi patch lama wajib diunduh ulang sekali.
        boolean butuhRefresh = ServerService.perluRefreshPatch(
                TgBackup.amanString(sp, ServerService.KEY_BIN_PATCH, ""));
        String sebutan = (paksa || adaKuncianBinary(ctx))
                ? "Sudah versi pilihan: v" : "Sudah versi terbaru: v";
        String realJalan = parseBinaryVersion(ServerService.binaryVersion);
        // Banding terurut: binary lebih baru dari rilis (mis. build lokal) tak
        // ikut di-downgrade; hanya versi lebih lama yang diunduh ulang.
        if (!paksa && !butuhRefresh && bandingVersi(realJalan, latest) >= 0) {
            // Binary asli sudah terbaru tapi penanda basi - perbaiki agar popup tidak looping.
            sp.edit().putString(ServerService.KEY_UPDATE_VERSION, latest).apply();
            return sebutan + latest;
        }
        // File didahulukan sebelum mengunduh ulang: binary yang sudah terunduh
        // tapi belum dipakai (server masih jalan versi lama / belum restart)
        // atau versi pilihan yang sudah terpasang tak boleh diunduh ulang
        // tiap cek — inilah sumber unduh berulang yang hanya sembuh di-reset.
        String realBerkas = butuhRefresh ? null : versiFileBinary(ctx);
        if (unduhBolehDilewati(paksa, butuhRefresh, realBerkas, realJalan, latest)) {
            // Penanda ikut dibetulkan agar banner update tak looping.
            sp.edit().putString(ServerService.KEY_UPDATE_VERSION, latest).apply();
            if (ServerService.running && bandingVersi(realJalan, latest) < 0) {
                return "Binary v" + latest + " sudah terpasang; mulai ulang server"
                        + " agar dipakai. " + BINARY_UPDATED_MARKER;
            }
            return sebutan + latest;
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

    /** Versi binary di file internal (uji --version); null bila tak ada/gagal.
     *  Dipakai agar cek update menilai file, bukan hanya proses yang sedang jalan. */
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

    /** True bila unduh boleh dilewati tanpa jaringan. Murni agar bisa unit test.
     *  Versi eksplisit (paksa) dilewati hanya bila file sudah persis versi
     *  diminta — termasuk menolak unduh ulang tiap cek saat kuncian aktif;
     *  downgrade tetap jalan bila file beda. Jalur ikuti-terbaru memakai file
     *  dulu (sudah terunduh tapi belum restart) baru versi jalan. */
    static boolean unduhBolehDilewati(boolean paksa, boolean butuhRefresh,
            String versiBerkas, String versiJalan, String target) {
        // Patch CI tanpa ganti versi wajib dipasang ulang sekali: lewati
        // semua jalan pintas agar binary basi benar-benar diganti.
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

    /** Unduh binary versi terbaru dari release repo ke out (verifikasi SHA-256).
     *  Dipakai saat Start bila binary belum ada (tidak lagi dibundel di APK). */
    public static String downloadBinary(Context ctx, File out) throws Exception {
        return downloadBinaryVersi(ctx, out, versiTargetBinary(ctx));
    }

    /** Unduh binary versi persis yang diminta (null = terbaru) ke out. */
    public static String downloadBinaryVersi(Context ctx, File out, String versi) throws Exception {
        try {
            return downloadBinaryInner(ctx, out, versi);
        } finally {
            downloadStatus = "";
        }
    }

    private static String downloadBinaryInner(Context ctx, File out) throws Exception {
        return downloadBinaryInner(ctx, out, latestVersion(ctx));
    }

    private static String downloadBinaryInner(Context ctx, File out, String diminta) throws Exception {
        String latest = diminta;
        // Bila API versi sedang gagal (rate-limit/TLS), tetap bisa unduh lewat
        // redirect "latest/download" tanpa perlu tahu nomor versi.
        boolean known = latest != null && !latest.isEmpty();
        // Versi kuncian user tak boleh diam-diam diganti rilis terbaru bila
        // asset-nya belum ada: gagal lantang agar user tahu pin-nya bermasalah.
        boolean bolehFallback = !adaKuncianBinary(ctx);
        String assetUrl = binaryAssetUrl(latest, ServerService.ABI);
        File binDir = out.getParentFile();
        if (binDir != null && !binDir.exists()
                && !binDir.mkdirs() && !binDir.exists()) {
            throw new IOException("Gagal membuat folder binary: " + binDir
                    + " - cek sisa storage & izin Storage, lalu ulangi.");
        }
        // Fail-fast ruang bebas: unduh binary ~20 MB tanpa cek mengulang
        // gagal-checksum (file terpotong) dan membakar kuota tiap Start.
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
        // Fail-fast shim (kernel lama): uji asap --version di bawah pasti
        // gagal tanpa shim valid, jadi pastikan DULU sebelum menghabiskan
        // ~20 MB kuota. Gagal di sini langsung lapor tanpa mengunduh —
        // inilah yang dulu terlihat sebagai "unduh berulang tiap cek".
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
                            if (bolehFallback && known && !pakaiTerbaru[0]
                                    && url.equals(urlAkhir[0])) {
                                // Rilis versi ini belum ada / sedang dibuat ulang CI -
                                // pakai binary rilis terbaru repo agar tetap bisa Start.
                                pakaiTerbaru[0] = true;
                                urlAkhir[0] = RELEASE_LATEST_URL + "vaultwarden-"
                                        + ServerService.ABI;
                                return urlAkhir[0];
                            }
                            throw new IOException(known
                                    ? "Build Android v" + latest + " belum tersedia"
                                            + saranStabilPrerelease(latest)
                                            + " (build otomatis ~12 jam). Coba lagi nanti."
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
        // Uji asap di file tmp dulu (--version wajib jalan): binary bagus yang
        // sedang terpasang baru diganti bila unduhan terbukti bisa dieksekusi.
        // Sebelumnya out dihapus sebelum uji sehingga unduhan rusak tetap
        // dilapor "terpasang" (+ marker) dan auto-restart menendang server
        // ke binary rusak.
        tmp.setReadable(true, true);
        tmp.setExecutable(true, true);
        // STB kernel lama (mis. 3.14): binary butuh shim LD_PRELOAD agar uji
        // --version lolos. Shim wajib dipastikan ADA SEBELUM uji asap, bukan
        // sesudah (dulu folder bin kosong selalu gagal di sini karena shim
        // belum terpasang, padahal binary-nya bagus).
        if (KernelCompat.legacyPerangkat()) {
            try {
                ensureShimFile(ctx);
            } catch (Exception abaikan) {
                // Bila shim lama masih ada, detectVersion tetap memakainya.
                // Bila tak ada sama sekali, uji di bawah gagal dengan pesan
                // yang menyebut shim agar langkah user jelas.
            }
        }
        String asap = detectVersion(ctx, tmp);
        if (asap == null) {
            boolean shimAda = shimValid(new File(ctx.getFilesDir(),
                    "bin/" + KernelCompat.SHIM_ASSET));
            tmp.delete();
            throw new IOException(pesanUjiAsapGagal(
                    KernelCompat.kernelSekarang(), shimAda));
        }
        try {
            ServerService.gantiAtomik(tmp, out);
        } catch (IOException e) {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
            throw e;
        }
        // ownerOnly=true: hanya UID app yang membaca binary — tidak world-readable.
        out.setReadable(true, true);
        out.setExecutable(true, true);
        writeVersionTag(binDir, appVersionName(ctx));
        String installed = asap;
        String effective = installed != null ? installed
                : (!fallback ? latest : null);
        if (effective != null && !effective.isEmpty()) {
            ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(ServerService.KEY_UPDATE_VERSION, effective).apply();
        }
        ServerService.binaryVersion = "";
        if (installed != null) {
            return "Update v" + installed + " terpasang. " + BINARY_UPDATED_MARKER;
        }
        return fallback
                ? "Binary rilis terbaru terpasang (v" + latest + " belum tersedia di repo)."
                : "Update v" + (latest != null ? latest : "?") + " terpasang. "
                        + BINARY_UPDATED_MARKER;
    }

    /** Penanda mesin "binary benar-benar diganti" (seperti WV_UPDATED_MARKER).
     *  Pemanggil (UI/bot) wajib pakai binaryBerubah(), bukan startsWith,
     *  agar perubahan redaksi pesan tak membunuh auto-restart diam-diam. */
    static final String BINARY_UPDATED_MARKER = "[bin-updated]";

    /** True bila pesan hasil tryUpdate berarti binary diganti (perlu restart).
     *  Cek marker mesin dulu, fallback awalan lama untuk pesan versi lama. Murni. */
    static boolean binaryBerubah(String msg) {
        if (msg == null) {
            return false;
        }
        if (msg.contains(BINARY_UPDATED_MARKER)) {
            return true;
        }
        return msg.startsWith("Update v");
    }

    /** Pesan gagal uji asap --version; di kernel lama tanpa shim sebut shim
     *  agar langkah user jelas (murni agar bisa diuji unit). */
    static String pesanUjiAsapGagal(String kernel, boolean shimAda) {
        if (KernelCompat.isLegacyKernel(kernel == null ? "" : kernel) && !shimAda) {
            return "File update tidak valid (gagal uji jalan --version;"
                    + " shim getrandom belum terpasang)."
                    + " Cek internet lalu tekan Start lagi agar shim ikut terunduh.";
        }
        return "File update tidak valid (gagal uji jalan --version).";
    }

    /** Nama asset rantai trust GitHub bila kelak diterbitkan di rilis repo. */
    static final String TRUST_CHAIN_ASSET = "github-chain.pem";
    /** Batas ukuran rantai (bawaan ~4 KB; 64 KB longgar anti OOM di STB). */
    static final int BATAS_RANTAI_TRUST = 64 * 1024;
    /** Penanda hari refresh anchor terakhir (agar maks 1x sehari). */
    static final String KEY_TRUST_TGL = "trust_anchor_tgl";

    /** Hitung sertifikat X.509 dalam blob PEM/DER; 0 bila sampah. Murni. */
    static int validasiRantai(byte[] blob) {
        if (blob == null || blob.length == 0 || blob.length > BATAS_RANTAI_TRUST) {
            return 0;
        }
        try {
            java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            int n = 0;
            for (Object o : cf.generateCertificates(
                    new java.io.ByteArrayInputStream(blob))) {
                if (o != null) {
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Segarkan anchor TLS dari rilis repo (best-effort, maks 1x sehari).
     *  Return true bila override baru terpasang. Gagal diam (404/validasi):
     *  anchor bawaan tetap dipakai sehingga tak ada regresi sebelum asset
     *  diterbitkan. Dipasang atomik (tmp + rename) agar crash tak merusak. */
    static boolean segarkanTrustAnchor(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS,
                    Context.MODE_PRIVATE);
            String hari = String.valueOf(System.currentTimeMillis() / 86400000L);
            if (hari.equals(TgBackup.amanString(sp, KEY_TRUST_TGL, ""))) {
                return false;
            }
            HttpURLConnection c = null;
            byte[] blob;
            try {
                c = open(ctx, RELEASE_LATEST_URL + TRUST_CHAIN_ASSET, 10000, 10000);
                if (c.getResponseCode() != 200) {
                    // Negatif definitif (mis. 404 sebelum asset diterbitkan):
                    // tandai hari agar tak menghantam jaringan tiap Start
                    // (sesuai janji "maks 1x sehari" di atas).
                    sp.edit().putString(KEY_TRUST_TGL, hari).apply();
                    return false;
                }
                blob = TgBackup.bacaTerbatas(c.getInputStream(), BATAS_RANTAI_TRUST + 1);
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
            if (validasiRantai(blob) < 1) {
                sp.edit().putString(KEY_TRUST_TGL, hari).apply();
                return false;
            }
            // Bila rilis menyertakan .sha256 pendamping, wajib cocok (lapis
            // integritas di luar TLS); bila belum diterbitkan (404), TLS saja
            // yang menjamin seperti sebelumnya.
            String sisi = fetchChecksum(ctx,
                    RELEASE_LATEST_URL + TRUST_CHAIN_ASSET + ".sha256", 10000, 10000);
            if (sisi != null) {
                String dapat;
                try {
                    dapat = toHex(Util.mdSha256().digest(blob));
                } catch (Exception e) {
                    return false;
                }
                if (!expectedHexEquals(sisi, dapat)) {
                    sp.edit().putString(KEY_TRUST_TGL, hari).apply();
                    return false;
                }
            }
            File dir = new File(ctx.getFilesDir(), "certs");
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File tmp = new File(dir, TRUST_CHAIN_ASSET + ".tmp");
            File dst = new File(dir, TRUST_CHAIN_ASSET);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(blob);
                fos.getFD().sync();
            }
            try {
                ServerService.gantiAtomik(tmp, dst);
            } catch (java.io.IOException e) {
                try {
                    tmp.delete();
                } catch (Exception ignored) {
                }
                return false;
            }
            sp.edit().putString(KEY_TRUST_TGL, hari).apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
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
        if (binDir != null && !binDir.exists()
                && !binDir.mkdirs() && !binDir.exists()) {
            throw new IOException("Gagal membuat folder binary: " + binDir
                    + " - cek sisa storage & izin Storage, lalu ulangi.");
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
                                    + latest + " (build CI ~12 jam). Coba lagi nanti.");
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
        try {
            ServerService.gantiAtomik(tmp, out);
        } catch (IOException e) {
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
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

    /** Persen (0-100) dari status "Unduh ... (NN%)"; -1 bila tak ada angka persen. Murni. */
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
        // Saring dulu: status malformasi ("(abc%)") melempar + isi stack trace
        // tiap 500 ms di ART lama selama unduh berjalan.
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

    /** Perbarui status unduhan untuk UI (persen + ukuran bila total diketahui). */
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
        String v = TgBackup.amanString(sp, KEY_WV_FROM, "");
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

    /** Penanda mesin di pesan sukses web-vault (jangan diubah tanpa update
     *  TgBot.webVaultBerubah + AutoUpdate): API pesan-string rapuh bila hanya
     *  mengandalkan kata "updated". */
    static final String WV_UPDATED_MARKER = "[wv-updated]";

    /** Unduh & ekstrak web-vault ke folder data; return pesan hasil.
     *  Mengikuti versi target (kuncian user atau terbaru). */
    public static String updateWebVault(Context ctx) throws Exception {
        return updateWebVaultVersi(ctx, versiTargetWebVault(ctx));
    }

    /** Unduh & ekstrak web-vault versi persis yang diminta (null = terbaru).
     *  Pemanggil menyimpan kuncian dulu agar auto-update tak langsung menaikkan lagi. */
    public static String updateWebVaultVersi(Context ctx, String versi) throws Exception {
        try {
            return updateWebVaultInner(ctx, versi);
        } finally {
            downloadStatus = "";
        }
    }

    /** Versi web-vault yang dicap sebagai terpasang (null = jangan cap).
     *  Instal via redirect (latest==null) wajib cap dari vw-version.json
     *  hasil ekstrak agar cek berikut tak unduh ulang 35 MB. Murni. */
    static String versiWvUntukCap(String latest, boolean fallback, String dariJson) {
        if (latest != null && !fallback) {
            return latest;
        }
        if (latest == null && dariJson != null && !dariJson.isEmpty()) {
            return dariJson;
        }
        return null;
    }

    private static String updateWebVaultInner(Context ctx) throws Exception {
        return updateWebVaultInner(ctx, versiTargetWebVault(ctx));
    }

    private static String updateWebVaultInner(Context ctx, String diminta) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR, ServerService.dataDirBawaanSegar());
        // Validasi seperti restore/startServer: prefs utak-atik tak boleh
        // mengarahkan unduhan+ekstrak 35 MB ke folder arbitrer/sistem.
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
        File tmpZip = new File(dataFolder, "web-vault.zip.tmp");

        String latest = diminta;

        // Sudah terpasang versi yang sama? Jangan unduh ulang 35 MB.
        boolean wvExists = new File(targetDir, "vw-version.json").exists()
                || new File(targetDir, "index.html").exists();
        String installed = TgBackup.amanString(sp, KEY_WV_FROM, "");
        if (wvExists && latest != null) {
            // Penanda lama (sebelum fitur ini): pakai versi server yang terdeteksi.
            String known = !installed.isEmpty() ? installed
                    : parseBinaryVersion(ServerService.binaryVersion);
            if (known != null && !known.isEmpty() && versiCocok(known, latest)) {
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
        if (wvExists && latest == null && installed.isEmpty()) {
            return "Web vault terpasang (versi tak dikenal);"
                    + " cek versi terbaru gagal (koneksi/rate-limit)."
                    + " Coba lagi nanti agar hemat kuota.";
        }
        // Fallback baru dipasang (asset versi ini belum terbit di CI):
        // jangan unduh ulang 35 MB tiap cek; versi terpasang tetap dipakai.
        if (fallbackBaruSaja(latest, wvExists,
                TgBackup.amanString(sp, KEY_WV_FALLBACK_FOR, ""),
                TgBackup.amanLong(sp, KEY_WV_FALLBACK_AT, 0),
                SystemClock.elapsedRealtime())) {
            return "Web vault v" + latest + " sudah dipasang via rilis terbaru;"
                    + " cek ulang dilewati agar hemat kuota. Coba lagi nanti.";
        }

        // Cek ruang hanya bila benar-benar akan mengunduh.
        long free = TgBackup.freeBytes(ctx, dataDir);
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
                            if (!adaKuncianWebVault(ctx) && latest != null
                                    && !wvLewatTerbaru[0]
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

        // Ekstrak ke folder sementara unik dulu; web-vault lama baru diganti
        // bila hasil ekstrak valid (hindari tanpa web UI saat unduhan korup).
        // Unik per panggilan: update bot + UI yang bersamaan dan cleanup Start
        // tak berebut/terbuang di tengah ekstrak seperti staging bersama dulu.
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
        long totalUnzip = 0;
        int jumlahEntri = 0;
        try {
            try (ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(tmpZip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String namaEntri = entry.getName();
                    jumlahEntri++;
                    try {
                        Util.tambahUkuranUnzip(0, 0, Util.BATAS_UNZIP_WEBVAULT, jumlahEntri, Util.BATAS_JUMLAH_ENTRI);
                    } catch (java.io.IOException e) {
                        throw e;
                    }
                    // Cek zip-slip leksikal tanpa syscall canonical per entri.
                    if (!amanEntriZip(namaEntri)) {
                        zis.closeEntry();
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
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        File parent = outFile.getParentFile();
                        if (parent != null) {
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
            // Zip korup di tengah ekstrak (mis. "invalid stored block lengths"
            // karena unduhan terpotong): buang hasil + sisa zip agar Start
            // berikutnya unduh ulang bersih, versi lama tetap dipakai.
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
        // Tukar via .bak agar crash di tengah tak menghilangkan web-vault lama.
        // Dikunci bersama cleanupTempFiles agar swap tak berebut rename target.
        synchronized (KUNCI_WEBVAULT) {
            File bakDir = new File(dataFolder, "web-vault.bak");
            deleteRecursive(bakDir);
            boolean adaLama = targetDir.exists();
            if (adaLama && !targetDir.renameTo(bakDir)) {
                // Gagal mencadangkan (mis. storage penuh): JANGAN hapus versi lama.
                // Batalkan update agar web UI tetap ada; buang hasil baru, coba lagi nanti.
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
            lepasStagingAktif(newDir);
        }
        String capWv = versiWvUntukCap(latest, wvFallback,
                readWvVersion(new File(targetDir, "vw-version.json")));
        if (capWv != null) {
            sp.edit().putString(KEY_WV_FROM, capWv).apply();
        }
        if (latest != null && !wvFallback) {
            sp.edit().remove(KEY_WV_FALLBACK_FOR).remove(KEY_WV_FALLBACK_AT).apply();
        } else if (latest != null && wvFallback) {
            sp.edit().putString(KEY_WV_FALLBACK_FOR, latest)
                    .putLong(KEY_WV_FALLBACK_AT, SystemClock.elapsedRealtime()).apply();
        }
        return "Web vault updated di " + targetDir.getAbsolutePath() + " " + WV_UPDATED_MARKER;
    }

    private static volatile String sBundledVersion;
    private static volatile boolean sBundledLoaded;

    /** Versi Vaultwarden yang dibundel di APK (tanpa huruf v) atau null.
     *  Dibaca sekali lalu cache (konstan selama runtime; dipanggil tiap detik UI). */
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
            v = (baris == null || baris.trim().isEmpty()) ? null : baris.trim();
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

    /** Label "Version: x" untuk dialog tentang (satu implementasi dipakai
     *  MainActivity + SettingsActivity agar tak duplikat). */
    public static String readBundledVersionLabel(Context ctx) {
        String v = readBundledVersionRaw(ctx);
        return (v == null || v.isEmpty()) ? "Version: ?" : "Version: " + v;
    }

    /** Versi binary yang benar-benar dipakai server saat ini (x.y.z). */
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

    /** Batas baca vw-version.json (file wajar <1 KB; tolak jumbo korup). */
    static final int BATAS_WV_VERSION = 16 * 1024;

    /** Versi dari file vw-version.json web-vault, atau null bila tak terbaca.
     *  Baca dibatasi agar file korup jumbo tak memenuhi heap STB. */
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

    /** Ambil nilai string kunci JSON datar ("kunci": "nilai") tanpa org.json
     *  (org.json di android.jar hanya stub yang melempar di unit test JVM).
     *  Murni agar bisa unit test. */
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
        // Metadata semver (+build) bukan bagian rilis: kupas agar penanda
        // "1.32.0+build" cocok dengan "1.32.0" (selaras normalisasiPinVersi).
        int plus = t.indexOf("+");
        String hasil = plus < 0 ? t : t.substring(0, plus);
        if (hasil.isEmpty()) {
            return hasil;
        }
        // Bentuk wajib mirip versi (digit awalan, tanpa slash/spasi/kuot):
        // tag API aneh ("../../x", "1.32/evil") ditolak null agar tak ditempel
        // mentah ke URL asset (404 + unduh gagal). Pemanggil sudah null-aman.
        if (!POLA_TAG_AMAN.matcher(hasil).matches()) {
            return null;
        }
        return hasil;
    }

    /** True bila dua versi menunjuk rilis yang sama ("v"-prefix, segmen
     *  hilang, dan sufiks beta diabaikan): banner update tak macet gara-gara
     *  beda format tulisan. Null tak pernah cocok. Murni agar bisa unit test. */
    static boolean versiCocok(String a, String b) {
        if (a == null || b == null || a.trim().isEmpty() || b.trim().isEmpty()) {
            return false;
        }
        return bandingVersi(normVersion(a), normVersion(b)) == 0;
    }

    /** Banding versi numerik per segmen ("1.9" < "1.10", "1.37" = "1.37.0").
     *  Tak dikenal (null/kosong) dianggap paling tua agar jalur update tetap
     *  jalan. Murni agar bisa unit test. */
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

    /** Urai "1.37.3" jadi {1,37,3}; rusak/null jadi array kosong (paling tua).
     *  Tahan sufiks ("1.37.3-beta", "3a" -> angka awalan) agar versi beta tak
     *  dianggap paling tua lalu memicu unduh ulang sia-sia. */
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
        String[] bagian = POLA_TITIK.split(t);
        int[] keluar = new int[bagian.length];
        for (int i = 0; i < bagian.length; i++) {
            keluar[i] = angkaAwalan(bagian[i]);
            if (keluar[i] < 0) {
                return new int[0];
            }
        }
        return keluar;
    }

    /** Angka desimal di awal segmen ("37-beta" -> 37, " 3a " -> 3); -1 bila tanpa digit. Murni. */
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

    /** Pola statis: matches/split kompilasi regex tiap panggil di STB lama. */
    private static final java.util.regex.Pattern POLA_PIN_VERSI =
            java.util.regex.Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(-[A-Za-z0-9.]+)?(\\+[A-Za-z0-9.-]+)?");
    private static final java.util.regex.Pattern POLA_TAG_AMAN =
            java.util.regex.Pattern.compile("[0-9][0-9A-Za-z.\\-]*");
    private static final java.util.regex.Pattern POLA_SPASI =
            java.util.regex.Pattern.compile("\\s+");
    private static final java.util.regex.Pattern POLA_TITIK =
            java.util.regex.Pattern.compile("\\.");
    private static final java.util.regex.Pattern POLA_SAMA_DENGAN =
            java.util.regex.Pattern.compile("=");

    /** True bila s 64 digit heksa (tanpa kompilasi regex matches). */
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

    // Pola versi di-compile sekali (dipanggil tiap detik dari UI).
    private static final java.util.regex.Pattern VERSION_PATTERN =
            java.util.regex.Pattern.compile("\\d+\\.\\d+\\.\\d+");

    /** Memo hasil parse versi binary: dipanggil tiap detik dari UI (lihat
     *  komentar VERSION_PATTERN) sehingga alokasi Matcher per tick jadi
     *  sampah di ART lama; input hanya berganti tiap Start. Hit (termasuk
     *  null) tanpa regex ulang; balapan tulis jinak (idempoten). */
    private static volatile String memoBinMasuk = null;
    private static volatile String memoBinHasil = null;

    /** Ambil "x.y.z" dari output "--version" ("vaultwarden 1.37.3" -> "1.37.3"). */
    static String parseBinaryVersion(String raw) {
        if (raw == null) {
            return null;
        }
        String masuk = memoBinMasuk;
        if (masuk != null && raw.equals(masuk)) {
            return memoBinHasil;
        }
        java.util.regex.Matcher m = VERSION_PATTERN.matcher(raw);
        String hasil = m.find() ? m.group() : null;
        memoBinMasuk = raw;
        memoBinHasil = hasil;
        return hasil;
    }

    /** Jalankan binary --version; kembalikan "x.y.z" atau null bila gagal. */
    static String detectVersion(File binary) {
        return detectVersion(null, binary);
    }

    /** Sama, tapi memakai shim getrandom bila perangkat kernel lama (agar --version lolos). */
    static String detectVersion(Context ctx, File binary) {
        // Di luar try agar terlihat di catch: lokal di dalam try tak tampak
        // di catch (pola sama ServerService detectBinaryVersion).
        final java.util.concurrent.atomic.AtomicBoolean versiSelesai =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread versiWatchdog = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(), "--version")
                    .redirectErrorStream(true);
            if (ctx != null && KernelCompat.legacyPerangkat()) {
                File shim = new File(ctx.getFilesDir(), "bin/" + KernelCompat.SHIM_ASSET);
                if (shim.exists()) {
                    pb.environment().put("LD_PRELOAD", shim.getAbsolutePath());
                }
            }
            Process p = pb.start();
            // Watchdog 10 detik: readLine() memblokir selamanya bila binary
            // macet tanpa output; destroy terjadwal menutup pipa sehingga
            // baca balik EOF (batas tungguAtauBunuh di bawah saja tak cukup).
            final Process versiProc = p;
            // AtomicBoolean (bukan boolean[]): tulis thread update wajib terlihat
            // thread watchdog tanpa synchronized (duplikat pola ServerService).
            versiWatchdog = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!versiSelesai.get()) {
                        try {
                            versiProc.destroy();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }, "vw-detect-watchdog");
            versiWatchdog.setDaemon(true);
            versiWatchdog.start();
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
            versiSelesai.set(true);
            versiWatchdog.interrupt();
            // Batas 10 detik: binary macet tak boleh menggantung thread update
            // selamanya (di Settings itu mengunci semua tombol berat).
            if (!tungguAtauBunuh(p, 10_000)) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
                return null;
            }
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
            return parseBinaryVersion(first);
        } catch (Exception e) {
            // Hentikan watchdog agar tak bocor tiap detect gagal (pola ServerService 2851).
            versiSelesai.set(true);
            try {
                versiWatchdog.interrupt();
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    /** Tunggu proses --version maks timeout lalu bunuh bila macet; true bila sudah mati.
     *  waitFor(timeout) hanya API 26+, jadi polling exitValue agar API 21 aman. */
    static boolean tungguAtauBunuh(Process p, long timeoutMs) {
        long batas = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < batas) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException e) {
                // Masih hidup: tunggu sebentar lagi.
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try {
            p.destroy();
        } catch (Exception ignored) {
        }
        try {
            p.exitValue();
            return true;
        } catch (IllegalThreadStateException e) {
            return false;
        }
    }

    /** Package-private agar bisa diuji unit (tanpa jaringan).
     *  Cari kunci "tag_name" struktural (di luar literal string, hormati
     *  escape) agar teks body yang memuat literal itu tak salah dibaca.
     *  Sengaja tanpa org.json: stub android.jar di uji JVM tak memparse. */
    static String extractTag(String body) {
        if (body == null) {
            return null;
        }
        String kunci = "\"tag_name\"";
        int n = body.length();
        boolean dalamString = false;
        boolean escape = false;
        for (int i = 0; i < n; i++) {
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
                return null;
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
            return tag.length() == 0 ? null : tag.toString();
        }
        return null;
    }

    /** True bila file ELF ARM 32-bit (magic + e_machine == EM_ARM).
     *  Cek magic saja meloloskan binary x86/acak; STB butuh ARM.
     *  Package-private agar bisa diuji unit. */
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
            // e_machine di offset 18 (EM_ARM = 40); offset 16 adalah e_type.
            // Endianness ikut EI_DATA (offset 5): 1 = little, 2 = big.
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

    /** Baca file .sha256 GitHub (format "<hex>  <nama>"); return hex atau null bila gagal. */
    private static String fetchChecksum(Context ctx, String url,
                                        int connectMs, int readMs) {
        // Checksum + binary satu channel TLS: minimal tolak http polos
        // fail-closed agar downgrade tak bisa menyuntik checksum palsu.
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) {
            return null;
        }
        for (int coba = 1; coba <= 2; coba++) {
            HttpURLConnection c = null;
            try {
                c = open(ctx, url, connectMs, readMs);
                if (c.getResponseCode() != 200) {
                    return null;
                }
                java.util.List<String> baris = new java.util.ArrayList<>();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        c.getInputStream(), StandardCharsets.UTF_8))) {
                    // Cap 200 baris x 4 KB: file checksum multi-asset bisa
                    // puluhan baris; baris tanpa newline dipotong agar tak
                    // menumpuk di memori STB 1 GB.
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
                    return null;
                }
                // Pindai semua token tiap baris (bukan hanya token pertama):
                // format BSD ("SHA256 (berkas) = <hex>") menaruh hex di akhir,
                // token pertama "SHA256" selalu gagal dan update abort permanen.
                // Baris diutamakan yang menyebut nama asset yang diminta agar
                // file multi-baris tak tertukar antar asset; fallback hex
                // pertama untuk file checksum mentah satu baris.
                // Kecocokan final tetap diverifikasi caller (mismatch = batal,
                // fail-closed) sehingga baris asing tak bisa lolos diam-diam.
                return pindaiHexDariBaris(baris, namaAssetDariUrl(url));
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

    /** Pindai daftar baris checksum, kembalikan 64-hex valid pertama.
     *  Baris tanpa hex dilewati (komentar/kosong); null bila tak ada yang
     *  valid. Murni agar bisa unit test. */
    static String pindaiHexDariBaris(java.util.List<String> baris) {
        return pindaiHexDariBaris(baris, null);
    }

    /** Varian yang mengutamakan baris berisi nama asset yang diminta.
     *  Bila tak ada baris yang menyebut nama (checksum mentah satu baris),
     *  jatuh ke hex valid pertama. Murni agar bisa unit test. */
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

    /** Nama asset dari URL rilis (".../v1.2.3/web-vault.zip" -> "web-vault.zip").
     *  Query/fragment dikupas; "" bila tak terpola. Murni agar bisa unit test. */
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

    /** Contains tanpa peka-huruf tanpa alokasi salinan lowercase (pindai
     *  checksum tak menyalin tiap baris). Jarum wajib sudah huruf kecil.
     *  Murni agar bisa unit test. */
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

    /** Pindai satu baris checksum dan kembalikan 64-hex pertama yang
     *  ditemukan (mndukung format raw maupun BSD "SHA256 (f) = hex"). Murni. */
    static String pindaiHexChecksum(String line) {
        if (line == null) {
            return null;
        }
        String bersih = line.replace("\uFEFF", "").trim();
        if (bersih.isEmpty()) {
            return null;
        }
        for (String tok : POLA_SPASI.split(bersih)) {
            // Belah '=' juga agar format tanpa spasi ("SHA256(f)=<hex>")
            // ikut dikenali; format BSD berspasi tetap lolos seperti dulu.
            for (String bagian : POLA_SAMA_DENGAN.split(tok, -1)) {
                String t = bagian.trim();
                if (hex64(t)) {
                    return t.toLowerCase(Locale.US);
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

    /** Panjang body dari header Content-Length sebagai long (mendukung
     *  file >2 GB dan chunked tanpa getContentLengthLong agar aman API 21). */
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
        try {
            return dl.getContentLength();
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** Hash awalan file yang sudah terunduh (untuk unduhan lanjutan/Range). */
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
            // Parsial terpotong (cleanup/storage bersamaan): jangan hash pendek
            // lalu append — checksum pasti gagal dengan pesan menyesatkan.
            // Gagal lantang agar pemanggil unduh ulang dari nol.
            if (left > 0) {
                throw new IOException("File parsial rusak (kurang " + left
                        + " byte) - unduh ulang dari nol.");
            }
        }
    }

    /** Banding hex checksum constant-time (case-insensitive); null tak cocok. */
    static boolean expectedHexEquals(String expectedHex, String gotHex) {
        if (expectedHex == null || gotHex == null) {
            return false;
        }
        byte[] a = expectedHex.trim().toLowerCase(Locale.US)
                .getBytes(StandardCharsets.UTF_8);
        byte[] b = gotHex.trim().toLowerCase(Locale.US)
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /** SHA-256 file sebagai hex kecil; null bila gagal dibaca. */
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

    /** True bila nama entri zip aman dari zip-slip (tanpa I/O canonical).
     *  Murni agar bisa unit test. */
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
        // Tolak entri "." atau "./" (current dir) yang bisa berbahaya saat diekstrak,
        // termasuk segmen "/./" di tengah ("dir/./file.txt").
        if (n.equals(".") || n.startsWith("./")
                || n.contains("/./") || n.endsWith("/.")) {
            return false;
        }
        // Tolak karakter kontrol/NUL/DEL di nama zip (licik di log/listing,
        // atau memecah header saat diekstrak di tool lain).
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** True bila path adalah tautan (symlink) atau memuat segmen tak-kanonis:
     *  jangan telusuri isinya, hapus link-nya saja. Konservatif: path normal
     *  selalu kanonis==absolut. Murni I/O agar bisa unit test JVM. */
    static boolean tautanSimbol(File file) {
        if (file == null) {
            return false;
        }
        try {
            return !file.getCanonicalPath().equals(file.getAbsolutePath());
        } catch (Exception e) {
            // Fail-closed: path yang tak bisa dikanoniskan (izin/SELinux/IO)
            // dianggap tautan agar deleteRecursive hanya menghapus link-nya
            // dan tak merekursi ke target di luar folder.
            return true;
        }
    }

    /** Hapus rekursif aman-symlink (milik bersama, dipakai ServerService juga). */
    static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        // Symlink direktori: isDirectory() true lalu listFiles() menghapus isi
        // TARGET di luar folder. Hapus link-nya saja.
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
