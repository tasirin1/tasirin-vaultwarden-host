package com.tasirin.vaultwardenhost;

import java.io.File;
import java.io.IOException;

/** Kumpulan logika murni lintas modul (murni JVM agar bisa unit test).
 *  Dibuat untuk menampung duplikasi yang tersebar: cocok chat, redirect aman,
 *  batas unzip, dan kebersihan file temp. */
public final class Util {

    /** Batas total unzip web-vault (~35 MB zip -> maks 300 MB, anti zip-bomb). */
    public static final long BATAS_UNZIP_WEBVAULT = 300L * 1024 * 1024;
    /** Batas total unzip restore database + tls (anti zip-bomb di STB 1 GB). */
    public static final long BATAS_UNZIP_RESTORE = 200L * 1024 * 1024;
    /** Batas jumlah entri zip (anti zip-bomb entri kecil). */
    public static final int BATAS_JUMLAH_ENTRI = 20000;

    private Util() {
    }

    /** Trim aman untuk hasil prefs yang bisa null (tanpa NPE). Murni. */
    public static String amanTrim(String s) {
        return s == null ? "" : s.trim();
    }

    /** True bila pesan Telegram berasal dari chat resmi.
      *  Dukung ID numerik ("123456") dan username ("@nama" / "nama",
      *  tanpa peka huruf). Murni agar bisa unit test.
      *  Catatan keamanan: username (@nama) tidak aman sebagai identitas —
      *  username bisa dilepas lalu diklaim orang lain, beda dengan ID numerik
      *  yang permanen. Demi kompatibilitas lama, username tetap dicocokkan,
      *  tapi pemakaian username dicatat sebagai peringatan sekali per proses
      *  dan pemilik dianjurkan memakai ID numerik di pengaturan bot. */
    private static volatile boolean peringatanUsernameSudah = false;

    public static boolean cocokChat(String config, long id, String username) {
        if (config == null) {
            return false;
        }
        String c = config.trim();
        if (c.isEmpty()) {
            return false;
        }
        // ID tempel dari nomor telepon ("+628...") disamakan ke ID numerik
        // Telegram agar bot tak diam tanpa pesan galat.
        if (c.startsWith("+") && c.length() > 1) {
            c = c.substring(1).trim();
            if (c.isEmpty()) {
                return false;
            }
        }
        try {
            long want = Long.parseLong(c);
            return id == want;
        } catch (NumberFormatException ignored) {
        }
        String norm = c.startsWith("@") ? c.substring(1) : c;
        if (norm.isEmpty() || username == null) {
            return false;
        }
        // Username murni angka (mis. "@12345") diperlakukan sebagai ID agar
        // tak bisa diklaim lewat username; config numerik wajib cocok ID.
        if (norm.matches("[0-9]+")) {
            try {
                return id == Long.parseLong(norm);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        // Peringatan sekali: config masih memakai username (bukan ID numerik).
        // Username tetap dicocokkan agar kompatibel, tapi ID numerik dianjurkan.
        if (!peringatanUsernameSudah) {
            peringatanUsernameSudah = true;
            try {
                android.util.Log.w("Util", "[tg] Chat bot memakai username (@"
                        + norm + "), bukan ID numerik. Username bisa diklaim"
                        + " ulang orang lain — ganti ke ID numerik di pengaturan.");
            } catch (Throwable ignored) {
            }
        }
        return norm.equalsIgnoreCase(username.trim().replaceFirst("^@", ""));
    }

    /** True bila config chat menunjuk grup/supergrup (ID numerik negatif).
     *  Username (@grup) tak bisa dipastikan pemiliknya sehingga dianggap
     *  bukan grup. Murni agar bisa unit test. */
    public static boolean chatAdalahGrup(String config) {
        if (config == null) {
            return false;
        }
        String c = config.trim();
        if (c.isEmpty() || c.startsWith("@")) {
            return false;
        }
        if (c.startsWith("+") && c.length() > 1) {
            c = c.substring(1).trim();
        }
        try {
            return Long.parseLong(c) < 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /** True bila pesan masih segar (tidak basi). Pesan sedikit di masa depan
     *  (jam STB lambat) tetap diterima; masa depan jauh ditolak seperti tombol
     *  inline (toleransi selebar jendela basi) agar antrean Telegram bertanggal
     *  menyimpang tak bisa mengeksekusi perintah kapan pun. Murni. */
    public static boolean pesanSegar(long dateMs, long sekarang, long basiMs) {
        // Tanpa tanggal = basi (fail-closed): perintah berbahaya basi
        // (mis. /stop tertunda) tak boleh lolos karena field date hilang.
        if (dateMs <= 0) {
            return false;
        }
        if (dateMs > sekarang + Math.max(0, basiMs)) {
            return false;
        }
        if (dateMs > sekarang) {
            return true;
        }
        return sekarang - dateMs <= basiMs;
    }

    /** True bila redirect boleh diikuti: hanya skema https (atau path relatif).
     *  Cegah downgrade https -> http saat unduh binary/checksum. Murni. */
    public static boolean bolehIkutiRedirect(String asal, String lokasi) {
        if (lokasi == null || lokasi.isEmpty()) {
            return false;
        }
        String l = lokasi.trim();
        if (l.startsWith("//")) {
            // Protokol-relatif ganti host: hanya boleh se-host dengan asal
            // (mis. CDN), bukan host sembarang. Mutlak https tetap dicek
            // di sambungRedirect via warisan skema dasar.
            return hostSama(asal, l.substring(2));
        }
        if (l.startsWith("/")) {
            return true;
        }
        int kol = l.indexOf(':');
        if (kol < 0) {
            return true;
        }
        String skema = l.substring(0, kol).toLowerCase(java.util.Locale.US);
        return "https".equals(skema);
    }

    /** True bila host termasuk rilis GitHub (unduhan binary/web-vault).
     *  Redirect asset github.com hanya ke objects.githubusercontent.com /
     *  release-assets.githubusercontent.com; wildcard *.githubusercontent.com
     *  ditolak karena konten user (gist/raw) bisa menyajikan binary + sha256
     *  palsu yang cocok bila DNS/MITM. Murni agar bisa unit test. */
    public static boolean hostGithubAman(String host) {
        if (host == null) {
            return false;
        }
        // Kupas ":port" dulu seperti hostTelegramAman: cabang "//host"
        // memberi otoritas mentah sehingga redirect sah berport eksplisit
        // ("//github.com:443/...") tertolak tanpa ini.
        String kupas = kupasHostPort(host.trim());
        if (kupas == null || kupas.isEmpty()) {
            return false;
        }
        String h = normalisasiHost(kupas).toLowerCase(java.util.Locale.US);
        if (h.isEmpty()) {
            return false;
        }
        if (h.equals("github.com") || h.equals("api.github.com")
                || h.equals("codeload.github.com")
                || h.equals("objects.githubusercontent.com")
                || h.equals("release-assets.githubusercontent.com")) {
            return true;
        }
        // CDN produksi lama (github-production-release-asset-*.s3 / *.githubusercontent.com):
        // wildcard umum *.githubusercontent.com tetap ditolak (konten user bisa
        // menyajikan binary + sha256 palsu), hanya prefix produksi resmi yang lolos.
        if (h.startsWith("github-production-release-asset-")
                && h.endsWith(".githubusercontent.com")) {
            return true;
        }
        return false;
    }

    /** True bila host milik Telegram (unduhan file bot).
     *  URL unduh file membawa token bot di path sehingga host sembarang wajib
     *  ditolak: 302 nakal (MITM/CDN jahat) bisa membocorkan token. Keluarga
     *  telegram.org + cdn-telegram.org mencakup API dan CDN resmi. Murni. */
    public static boolean hostTelegramAman(String host) {
        if (host == null) {
            return false;
        }
        String kupas = kupasHostPort(host.trim());
        if (kupas == null || kupas.isEmpty()) {
            return false;
        }
        String h = normalisasiHost(kupas).toLowerCase(java.util.Locale.US);
        if (h.isEmpty()) {
            return false;
        }
        if (h.equals("telegram.org") || h.endsWith(".telegram.org")) {
            return true;
        }
        return h.equals("cdn-telegram.org") || h.endsWith(".cdn-telegram.org");
    }

    /** True bila redirect unduhan file Telegram boleh diikuti: https + host
     *  Telegram (lihat hostTelegramAman). Dipakai bukaFileTelegram agar token
     *  di path URL tak bocor ke host asing via 302. Murni agar bisa unit test. */
    public static boolean bolehIkutiRedirectTelegram(String asal, String lokasi) {
        if (!bolehIkutiRedirect(asal, lokasi)) {
            return false;
        }
        if (lokasi == null) {
            return false;
        }
        String l = lokasi.trim();
        if (l.startsWith("//")) {
            return hostTelegramAman(l.substring(2).split("[/?#]")[0]);
        }
        if (l.startsWith("/")) {
            return true;
        }
        int kol = l.indexOf(':');
        if (kol < 0) {
            try {
                String gabung = sambungRedirect(asal, l);
                return hostTelegramAman(new java.net.URL(gabung).getHost());
            } catch (Exception e) {
                return false;
            }
        }
        try {
            String skema = l.substring(0, kol).toLowerCase(java.util.Locale.US);
            if (!"https".equals(skema)) {
                return false;
            }
            return hostTelegramAman(new java.net.URL(l).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /** True bila redirect unduhan GitHub boleh diikuti: https + host GitHub.
     *  Dipakai Updater (binary/web-vault/shim).
     *  Murni agar bisa unit test. */
    public static boolean bolehIkutiRedirectGithub(String asal, String lokasi) {
        if (!bolehIkutiRedirect(asal, lokasi)) {
            return false;
        }
        if (lokasi == null) {
            return false;
        }
        String l = lokasi.trim();
        if (l.startsWith("//")) {
            return hostGithubAman(l.substring(2).split("[/?#]")[0]);
        }
        if (l.startsWith("/")) {
            return true;
        }
        int kol = l.indexOf(':');
        if (kol < 0) {
            try {
                String gabung = sambungRedirect(asal, l);
                return hostGithubAman(new java.net.URL(gabung).getHost());
            } catch (Exception e) {
                return false;
            }
        }
        try {
            String skema = l.substring(0, kol).toLowerCase(java.util.Locale.US);
            if (!"https".equals(skema)) {
                return false;
            }
            return hostGithubAman(new java.net.URL(l).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /** True bila sisa lokasi "//host/..." menunjuk host yang sama dengan asal.
     *  Murni agar bisa unit test. */
    static boolean hostSama(String asal, String sisa) {
        if (asal == null || sisa == null || sisa.isEmpty()) {
            return false;
        }
        try {
            String hostAsal = new java.net.URL(asal.trim()).getHost();
            if (hostAsal == null || hostAsal.isEmpty()) {
                return false;
            }
            int ujung = sisa.length();
            for (int i = 0; i < sisa.length(); i++) {
                char c = sisa.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    ujung = i;
                    break;
                }
            }
            String host = kupasHostPort(sisa.substring(0, ujung));
            return host != null && !host.isEmpty()
                    && host.equalsIgnoreCase(normalisasiHost(hostAsal));
        } catch (Exception e) {
            return false;
        }
    }

    /** Kupas "host", "host:port", "[v6]", "[v6]:port", atau literal IPv6 tanpa
     *  kurung-siku menjadi nama host (tanpa port/kurung). Null bila tak jelas.
     *  Potong-di-':'-pertama merusak IPv6 (isinya banyak ':') sehingga redirect
     *  se-host "[2001:db8::1]" dulu ditolak walau sah. Murni. */
    static String kupasHostPort(String hostPort) {
        if (hostPort == null || hostPort.isEmpty()) {
            return null;
        }
        if (hostPort.charAt(0) == '[') {
            int tutup = hostPort.indexOf(']');
            if (tutup < 0) {
                return null;
            }
            String ekor = hostPort.substring(tutup + 1);
            if (!ekor.isEmpty() && !ekor.matches(":[0-9]+")) {
                return null;
            }
            String dalam = hostPort.substring(1, tutup);
            return dalam.isEmpty() ? null : dalam;
        }
        int titikDua = 0;
        for (int i = 0; i < hostPort.length(); i++) {
            if (hostPort.charAt(i) == ':') {
                titikDua++;
            }
        }
        if (titikDua > 1) {
            return hostPort;
        }
        if (titikDua == 1) {
            int i = hostPort.indexOf(':');
            String h = hostPort.substring(0, i);
            String port = hostPort.substring(i + 1);
            // Port wajib angka: "host:abc" bukan otoritas valid sehingga
            // null (ditolak) alih-alih terkupas jadi host telanjang.
            if (h.isEmpty() || port.isEmpty() || !port.matches("[0-9]+")) {
                return null;
            }
            return h;
        }
        return hostPort;
    }

    /** Samakan bentuk host: buang kurung-siku IPv6 ("[::1]" -> "::1").
     *  getHost() tak pernah membawa port sehingga replaceFirst hapus-port
     *  yang lama mati (malah menggerogoti IPv6). Murni. */
    static String normalisasiHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim();
        if (h.length() >= 2 && h.charAt(0) == '['
                && h.charAt(h.length() - 1) == ']') {
            return h.substring(1, h.length() - 1);
        }
        return h;
    }

    /** Selesaikan URL redirect relatif terhadap URL dasar. Murni. */
    public static String sambungRedirect(String dasar, String lokasi) {
        if (lokasi == null) {
            return dasar;
        }
        String l = lokasi.trim();
        if (l.regionMatches(true, 0, "https://", 0, 8)) {
            return l;
        }
        // Fail-closed: absolut http:// tak pernah diteruskan (downgrade
        // cleartext). Pemanggil memvalidasi via bolehIkutiRedirect* dulu,
        // tapi pemanggil langsung yang lupa validasi tetap aman: tahan di
        // URL dasar agar loop redirect berakhir "Terlalu banyak redirect".
        if (l.regionMatches(true, 0, "http://", 0, 7)) {
            return dasar;
        }
        if (l.startsWith("//")) {
            // Protokol-relatif: warisi skema dasar (tetap https), ganti host.
            try {
                java.net.URL u = new java.net.URL(dasar);
                return u.getProtocol() + ":" + l;
            } catch (Exception e) {
                return dasar;
            }
        }
        if (l.startsWith("/")) {
            try {
                java.net.URL u = new java.net.URL(dasar);
                return u.getProtocol() + "://" + u.getHost()
                        + (u.getPort() < 0 ? "" : ":" + u.getPort()) + l;
            } catch (Exception e) {
                // Dasar rusak: jangan kembalikan path relatif mentah (pemanggil
                // gagal "no protocol" membingungkan) — tahan di URL terakhir
                // yang baik agar loop redirect berakhir "Terlalu banyak redirect".
                return dasar;
            }
        }
        try {
            return new java.net.URL(new java.net.URL(dasar), l).toString();
        } catch (Exception e) {
            return dasar;
        }
    }

    /** True bila file temp aman dihapus: hanya di dalam cache/files internal.
     *  Cegah hapus file user di /sdcard saat restore gagal. Murni. */
    public static boolean bolehHapusFile(File cacheDir, File filesDir, File target) {
        if (target == null) {
            return false;
        }
        try {
            String kanon = target.getCanonicalPath();
            if (cacheDir != null) {
                String c = cacheDir.getCanonicalPath();
                if (kanon.equals(c) || kanon.startsWith(c + File.separator)) {
                    return true;
                }
            }
            if (filesDir != null) {
                String f = filesDir.getCanonicalPath();
                if (kanon.equals(f) || kanon.startsWith(f + File.separator)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** Akumulasi ukuran unzip; lempar IOException bila lewati batas/maks entri.
     *  Murni agar bisa unit test. */
    public static long tambahUkuranUnzip(long total, long tambah, long batas,
            int jumlah, int batasJumlah) throws IOException {
        if (jumlah > batasJumlah) {
            throw new IOException("Terlalu banyak entri zip (" + jumlah + ").");
        }
        long hasil = total + Math.max(0, tambah);
        if (hasil > batas) {
            throw new IOException("Ukuran unzip melebihi batas " + batas + " byte.");
        }
        return hasil;
    }
}
