package com.tasirin.vaultwardenhost;

/** Kompatibilitas kernel STB lama (logika murni tanpa API Android agar bisa diuji JVM).
 *
 * <p>Binary Vaultwarden terbaru (Rust modern) memanggil syscall
 * {@code getrandom()} yang baru ada di kernel 3.17+. STB Android 5/6
 * (mis. ZTE B860H, kernel 3.14.x) menjawab {@code EINVAL (errno=22)}
 * sehingga binary langsung panic (exit 101) setiap start.
 * Perangkat seperti itu wajib memakai channel binary legacy. */
public final class KernelCompat {

    /** Versi Vaultwarden legacy yang dipin untuk STB kernel lama. */
    public static final String LEGACY_VW_VERSION = "1.29.2";
    /** Nama asset binary modern di release repo. */
    public static final String MODERN_ASSET = "vaultwarden-armeabi-v7a";
    /** Nama asset binary legacy di release repo (dipublish berdampingan). */
    public static final String LEGACY_ASSET = "vaultwarden-armeabi-v7a-legacy";

    private KernelCompat() {
    }

    /** True bila kernel terlalu tua untuk getrandom() (mayor < 3, atau 3.x dengan minor < 17).
     *  Versi tak dikenal/kosong dianggap modern (smoke test binary tetap jadi jaring pengaman). */
    public static boolean isLegacyKernel(String osVersion) {
        int[] v = parseKernel(osVersion);
        if (v == null) {
            return false;
        }
        if (v[0] < 3) {
            return true;
        }
        return v[0] == 3 && v[1] < 17;
    }

    /** True bila perangkat butuh channel legacy (penentu tunggal: versi kernel). */
    public static boolean isLegacyDevice(String osVersion) {
        return isLegacyKernel(osVersion);
    }

    /** Versi kernel perangkat ini (System.getProperty("os.version")), "" bila tak terbaca. */
    public static String kernelSekarang() {
        try {
            String v = System.getProperty("os.version");
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /** Nama asset binary sesuai channel (legacy/modern). */
    public static String binaryAsset(boolean legacy) {
        return legacy ? LEGACY_ASSET : MODERN_ASSET;
    }

    /** Nama channel untuk log/UI ("legacy"/"modern"). */
    public static String channelName(boolean legacy) {
        return legacy ? "legacy" : "modern";
    }

    /** Satu baris info perangkat untuk log (mis. "kernel 3.14.29-gabc | SDK 23 | channel legacy"). */
    public static String infoBaris(String osVersion, int sdkInt) {
        String kernel = osVersion == null || osVersion.isEmpty() ? "?" : osVersion;
        return "kernel " + kernel + " | SDK " + sdkInt
                + " | channel " + channelName(isLegacyDevice(osVersion));
    }

    /** Saran perbaikan (Bahasa Indonesia) saat binary modern panic di kernel lama. */
    public static String saranLegacy(String osVersion, int sdkInt) {
        return "Binary tidak cocok dengan kernel STB lama (kernel " + osVersion
                + ", getrandom errno=22). Solusi otomatis: tekan Cek Update"
                + " untuk memasang binary legacy v" + LEGACY_VW_VERSION
                + " (" + LEGACY_ASSET + "), lalu Start lagi."
                + " Cara manual: taruh binary legacy yang cocok Android 6"
                + " (vaultwarden-armeabi-v7a) di folder data,"
                + " isi SHA-256-nya di pengaturan, lalu Start lagi.";
    }

    /** Parse "3.14.29-gabc" -> {3,14}; null bila tak terpola. Package-private agar bisa diuji. */
    static int[] parseKernel(String osVersion) {
        if (osVersion == null) {
            return null;
        }
        String s = osVersion.trim();
        if (s.isEmpty()) {
            return null;
        }
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
            i++;
        }
        String[] part = s.substring(0, i).split("\\.");
        if (part.length < 2) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(part[0]), Integer.parseInt(part[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
