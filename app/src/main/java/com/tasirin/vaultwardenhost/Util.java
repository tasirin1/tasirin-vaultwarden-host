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
     *  tanpa peka huruf). Murni agar bisa unit test. */
    public static boolean cocokChat(String config, long id, String username) {
        if (config == null) {
            return false;
        }
        String c = config.trim();
        if (c.isEmpty()) {
            return false;
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
        return norm.equalsIgnoreCase(username.trim().replaceFirst("^@", ""));
    }

    /** True bila pesan masih segar (tidak basi). Pesan masa depan (jam STB
     *  lambat) jangan dibuang: hanya pesan lama yang ditolak. Murni. */
    public static boolean pesanSegar(long dateMs, long sekarang, long basiMs) {
        // Tanpa tanggal = basi (fail-closed): perintah berbahaya basi
        // (mis. /stop tertunda) tak boleh lolos karena field date hilang.
        if (dateMs <= 0) {
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
            return true;
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

    /** Selesaikan URL redirect relatif terhadap URL dasar. Murni. */
    public static String sambungRedirect(String dasar, String lokasi) {
        if (lokasi == null) {
            return dasar;
        }
        String l = lokasi.trim();
        if (l.regionMatches(true, 0, "http://", 0, 7) || l.regionMatches(true, 0, "https://", 0, 8)) {
            return l;
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
