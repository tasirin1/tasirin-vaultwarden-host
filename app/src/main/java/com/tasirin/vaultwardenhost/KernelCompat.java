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

    /** Hasil legacy per proses (kernel tak berubah tanpa reboot): hemat parse
     *  ulang tiap Start/health di STB lama. Null = belum dihitung. */
    private static volatile Boolean legacyMemo = null;

    /** Varian memo: baca + parse kernel sekali per proses (murni di dalam). */
    public static boolean legacyPerangkat() {
        Boolean m = legacyMemo;
        if (m == null) {
            m = Boolean.valueOf(isLegacyKernel(kernelSekarang()));
            legacyMemo = m;
        }
        return m.booleanValue();
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
                + " | channel " + channelName(isLegacyKernel(osVersion));
    }

    /** Saran perbaikan (Bahasa Indonesia) bila binary tetap panic walau shim dipasang. */
    public static String saranShimGagal(String osVersion) {
        return "Binary tetap crash di kernel STB lama (kernel " + osVersion
                + ", getrandom/syscall errno=22) walau shim dipasang."
                + " Coba tekan Cek Update (" + SHIM_ASSET + " diunduh otomatis,"
                + " butuh internet), lalu Start lagi.";
    }

    /** Batas jenuh parse kernel: versi riil 1-2 digit; di atasnya hasilnya
     *  sama (modern) sehingga dijenuhkan agar akumulasi tak overflow int
     *  lalu terbaca <3 (salah legacy). */
    private static final int BATAS_VERSI_KERNEL = 100000;

    /** Parse "3.14.29-gabc" -> {3,14}; null bila tak terpola. Package-private agar bisa diuji.
     *  Satu pass tanpa alokasi (dulu trim + 4 substring + indexOf): hemat di
     *  STB lama walau memo per proses. Digit ASCII eksplisit (dulu
     *  Character.isDigit menerima digit Unicode yang pasti gagal parseInt
     *  lalu jatuh ke catch NumberFormatException tiap Start). Jenuh di atas
     *  batas (dulu parseInt melempar untuk angka raksasa): hasil legacy sama
     *  (modern), tanpa alokasi eksepsi. */
    static int[] parseKernel(String osVersion) {
        if (osVersion == null) {
            return null;
        }
        int n = osVersion.length();
        int awal = 0;
        while (awal < n && osVersion.charAt(awal) <= ' ') {
            awal++;
        }
        int akhir = n;
        while (akhir > awal && osVersion.charAt(akhir - 1) <= ' ') {
            akhir--;
        }
        if (awal >= akhir) {
            return null;
        }
        int i = awal;
        int mayor = 0;
        boolean adaMayor = false;
        while (i < akhir) {
            char c = osVersion.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            adaMayor = true;
            mayor = mayor * 10 + (c - '0');
            if (mayor > BATAS_VERSI_KERNEL) {
                mayor = BATAS_VERSI_KERNEL;
            }
            i++;
        }
        if (!adaMayor || i >= akhir || osVersion.charAt(i) != '.') {
            return null;
        }
        i++;
        int minor = 0;
        boolean adaMinor = false;
        while (i < akhir) {
            char c = osVersion.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            adaMinor = true;
            minor = minor * 10 + (c - '0');
            if (minor > BATAS_VERSI_KERNEL) {
                minor = BATAS_VERSI_KERNEL;
            }
            i++;
        }
        if (!adaMinor) {
            return null;
        }
        return new int[]{mayor, minor};
    }
}
