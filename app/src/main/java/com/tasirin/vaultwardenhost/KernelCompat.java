package com.tasirin.vaultwardenhost;

public final class KernelCompat {

    public static final String SHIM_ASSET = "libgetrandom-shim-armeabi-v7a.so";

    public static final long SHIM_MIN_BYTES = 1024;

    private KernelCompat() {
    }

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

    private static volatile Boolean legacyMemo = null;

    public static boolean legacyPerangkat() {
        Boolean m = legacyMemo;
        if (m == null) {
            m = Boolean.valueOf(isLegacyKernel(kernelSekarang()));
            legacyMemo = m;
        }
        return m.booleanValue();
    }

    public static String kernelSekarang() {
        try {
            String v = System.getProperty("os.version");
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    public static String channelName(boolean legacy) {
        return legacy ? "legacy" : "modern";
    }

    public static String infoBaris(String osVersion, int sdkInt) {
        String kernel = osVersion == null || osVersion.isEmpty() ? "?" : osVersion;
        return "kernel " + kernel + " | SDK " + sdkInt
                + " | channel " + channelName(isLegacyKernel(osVersion));
    }

    static boolean arsitekturTakDidukung(String arch) {
        if (arch == null) {
            return false;
        }
        String a = arch.trim().toLowerCase(java.util.Locale.ROOT);
        if (a.isEmpty()) {
            return false;
        }
        return !(a.startsWith("arm") || a.equals("aarch64") || a.contains("arm64"));
    }

    static String saranArsitektur(String arch) {
        return "Perangkat ini berarsitektur " + arch + ", bukan ARM: binary dan"
                + " shim Vaultwarden (armeabi-v7a) tidak bisa berjalan di sini,"
                + " dan mengunduh ulang tidak akan membantu.";
    }

    public static String saranShimGagal(String osVersion) {
        return "Binary tetap crash di kernel STB lama (kernel " + osVersion
                + ", getrandom/syscall errno=22) walau shim dipasang."
                + " Coba tekan Cek Update (" + SHIM_ASSET + " diunduh otomatis,"
                + " butuh internet), lalu Start lagi.";
    }

    private static final int BATAS_VERSI_KERNEL = 100000;

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
