package com.tasirin.vaultwardenhost;

/** Kompatibilitas kernel STB lama (logika murni tanpa API Android agar bisa diuji JVM).
 *
 * <p>Binary Vaultwarden (Rust modern) mengambil acak via {@code getrandom()} libc
 * (std) dan via {@code syscall(SYS_getrandom)} mentah (crate getrandom 0.2
 * lewat ring ke ticketer TLS) yang di kernel STB Android 5/6 (mis. ZTE B860H,
 * kernel 3.14.x) gagal dengan {@code EINVAL (errno=22)} — tanpa fallback
 * /dev/urandom — sehingga Rust panic setiap start (exit 101) atau TLS Rocket
 * gagal ("bad TLS ticketer", exit 1, khusus HTTPS). Perangkat seperti itu
 * menjalankan binary yang sama via shim {@code LD_PRELOAD}
 * ({@link #SHIM_ASSET}) yang melayani kedua jalur dari /dev/urandom. */
public final class KernelCompat {

    /** Nama asset shim getrandom di release repo (sekitar 3 KB stripped, dibangun dari shim/). */
    public static final String SHIM_ASSET = "libgetrandom-shim-armeabi-v7a.so";
    /** Ukuran minimum shim yang valid (shim rilis ~2,7 KB stripped; batas 1 KB agar lolos). */
    public static final long SHIM_MIN_BYTES = 1024;

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

    /** True bila perangkat butuh shim getrandom (penentu tunggal: versi kernel). */
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

    /** Nama channel untuk log/UI ("legacy"=pakai shim / "modern"). */
    public static String channelName(boolean legacy) {
        return legacy ? "legacy" : "modern";
    }

    /** Satu baris info perangkat untuk log (mis. "kernel 3.14.29-gabc | SDK 23 | channel legacy"). */
    public static String infoBaris(String osVersion, int sdkInt) {
        String kernel = osVersion == null || osVersion.isEmpty() ? "?" : osVersion;
        return "kernel " + kernel + " | SDK " + sdkInt
                + " | channel " + channelName(isLegacyDevice(osVersion));
    }

    /** Saran perbaikan (Bahasa Indonesia) bila binary tetap panic walau shim dipasang. */
    public static String saranShimGagal(String osVersion) {
        return "Binary tetap crash di kernel STB lama (kernel " + osVersion
                + ", getrandom/syscall errno=22) walau shim dipasang."
                + " Coba tekan Cek Update (shim diunduh ulang), lalu Start lagi."
                + " Cara manual: taruh " + SHIM_ASSET + " dari halaman Release"
                + " ke folder data dengan nama yang sama, lalu Start lagi.";
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
