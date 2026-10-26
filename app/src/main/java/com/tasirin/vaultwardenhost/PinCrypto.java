package com.tasirin.vaultwardenhost;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PinCrypto {

    public static final int MAX_GAGAL = 5;
    public static final long KUNCI_MS = 5 * 60_000;

    private static final String PREFIX = "PBKDF2$";

    static final String PREFIX_BUNGKUS = "PBKDF2W$";

    private static final int ITERATIONS = 30_000;

    static final int ITERASI_LEGASI = 12_000;

    private static final class Acak {
        static final java.security.SecureRandom ISI = new java.security.SecureRandom();
    }

    private static volatile Boolean pabrikAda = null;

    static final int ITERASI_MINIMAL = 10_000;

    static final int MAKS_VERIFIKASI = 120_000;

    static final int HEKS_MAKSIMAL = 128;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private PinCrypto() {
    }

    public static String hash(String pin) {
        if (pin == null) {
            pin = "";
        }
        byte[] salt = new byte[SALT_BYTES];
        Acak.ISI.nextBytes(salt);
        int iter = iterasiStandar();
        byte[] dk = derive(pin, salt, iter);
        return PREFIX + iter + "$" + hex(salt) + "$" + hex(dk);
    }

    public static boolean isNewFormat(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public static boolean perluMigrasi(String stored) {
        return stored != null && !stored.isEmpty()
                && !isNewFormat(stored) && !isFormatBungkus(stored);
    }

    static String bungkusLegasi(String stored) {
        String dalam = Util.rendahAscii(stored == null ? "" : stored.trim());
        if (!hashLegasiValidTernormalisasi(dalam)) {
            return null;
        }
        byte[] salt = new byte[SALT_BYTES];
        Acak.ISI.nextBytes(salt);
        int iter = iterasiStandar();
        byte[] dk = derive(dalam, salt, iter);
        return PREFIX_BUNGKUS + iter + "$" + hex(salt) + "$" + hex(dk);
    }

    private static boolean hashLegasiValidTernormalisasi(String l) {
        if (l == null || l.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            char c = l.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    static boolean isFormatBungkus(String stored) {
        return stored != null && stored.startsWith(PREFIX_BUNGKUS);
    }

    public static boolean perluUpgradeHash(String stored) {
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        if (isFormatBungkus(stored)) {
            return true;
        }
        if (!isNewFormat(stored)) {
            return true;
        }
        try {
            String[] parts = Util.pecahPin(stored);
            if (parts == null || parts.length != 4) {
                return true;
            }
            int iter = Integer.parseInt(parts[1]);

            byte[] salt = unhex(parts[2]);
            byte[] want = unhex(parts[3]);
            if (salt == null || want == null || salt.length != 16 || want.length != 32) {
                return true;
            }
            return iter != iterasiStandar();
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean verify(String stored, String pin) {

        if (stored == null || stored.length() > 512) {
            return false;
        }

        if (stored.isEmpty() || pin == null || pin.isEmpty()) {
            return false;
        }
        try {
            if (isNewFormat(stored)) {
                String[] parts = Util.pecahPin(stored);
                if (parts == null || parts.length != 4) {
                    return false;
                }
                return verifikasiPbkdf2(parts, pin);
            }
            if (isFormatBungkus(stored)) {
                String[] parts = Util.pecahPin(stored);
                if (parts == null || parts.length != 4) {
                    return false;
                }
                return verifikasiPbkdf2(parts, sha256(pin));
            }

            return samakanHexLegasi(sha256(pin), stored);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean verifikasiPbkdf2(String[] parts, String input) {
        int iter;
        try {
            iter = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return false;
        }

        if (iter < ITERASI_MINIMAL || iter > MAKS_VERIFIKASI) {
            return false;
        }

        if (parts[2].length() > HEKS_MAKSIMAL || parts[3].length() > HEKS_MAKSIMAL) {
            return false;
        }
        byte[] salt = unhex(parts[2]);
        byte[] want = unhex(parts[3]);

        if (salt == null || want == null || salt.length != 16
                || want.length != 32) {
            return false;
        }
        byte[] got = derive(input, salt, iter);
        return MessageDigest.isEqual(got, want);
    }

    public static long sisaKunciMs(int gagal, long terkunciSampai, long sekarang) {
        if (gagal < MAX_GAGAL) {
            return 0;
        }
        if (terkunciSampai <= 0) {
            return KUNCI_MS;
        }
        if (sekarang >= terkunciSampai) {
            return 0;
        }
        return Math.min(terkunciSampai - sekarang, KUNCI_MS);
    }

    public static long kunciBerikutnyaMs(int gagalSesudah, long sekarang) {
        if (gagalSesudah < MAX_GAGAL) {
            return 0;
        }
        return sekarang + KUNCI_MS;
    }

    public static long sisaKunciElapsed(long terkunciElapsed, long sekarangElapsed) {
        long sisa = terkunciElapsed - sekarangElapsed;
        if (sisa <= 0 || sisa > KUNCI_MS) {
            return 0;
        }
        return sisa;
    }

    public static long kunciElapsedBerikutnyaMs(int gagalSesudah, long sekarangElapsed) {
        if (gagalSesudah < MAX_GAGAL) {
            return 0;
        }
        return sekarangElapsed + KUNCI_MS;
    }

    static boolean pabrikHilangPermanen(Throwable t) {
        return t instanceof java.security.NoSuchAlgorithmException;
    }

    static boolean pabrikTersedia() {
        Boolean c = pabrikAda;
        if (c != null) {
            return c.booleanValue();
        }
        boolean ada;
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            ada = true;
        } catch (Exception e) {
            if (!pabrikHilangPermanen(e)) {

                return false;
            }
            ada = false;
        }
        pabrikAda = ada ? Boolean.TRUE : Boolean.FALSE;
        return ada;
    }

    static int iterasiStandar(boolean pabrik) {
        return pabrik ? ITERATIONS : ITERASI_LEGASI;
    }

    static int iterasiStandar() {
        return iterasiStandar(pabrikTersedia());
    }

    private static byte[] derive(String pin, byte[] salt, int iter) {

        char[] chars = pin == null ? new char[0] : pin.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iter, HASH_BITS);
        try {
            if (pabrikTersedia()) {
                try {
                    SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                    return f.generateSecret(spec).getEncoded();
                } catch (Exception e) {

                    if (pabrikHilangPermanen(e)) {
                        pabrikAda = Boolean.FALSE;
                    }
                }
            }

            return pbkdf2Manual(pin, salt, iter);
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(chars, '\0');
        }
    }

    static byte[] pbkdf2Manual(String pin, byte[] salt, int iter) {
        if (iter < 1) {
            throw new IllegalStateException("PBKDF2 tidak tersedia");
        }
        byte[] sandi = pin == null
                ? new byte[0] : pin.getBytes(StandardCharsets.UTF_8);
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(sandi, "HmacSHA256"));
            byte[] blok = new byte[salt.length + 4];
            System.arraycopy(salt, 0, blok, 0, salt.length);
            blok[salt.length + 3] = 1;

            byte[] u = mac.doFinal(blok);
            byte[] hasil = u.clone();
            byte[] keluar = new byte[u.length];
            for (int i = 1; i < iter; i++) {
                mac.update(u, 0, u.length);
                mac.doFinal(keluar, 0);
                for (int j = 0; j < hasil.length; j++) {
                    hasil[j] ^= keluar[j];
                }
                byte[] ganti = u;
                u = keluar;
                keluar = ganti;
            }
            return hasil;
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 tidak tersedia", e);
        } finally {
            java.util.Arrays.fill(sandi, (byte) 0);
        }
    }

    static String sha256(String pin) {
        try {

            java.security.MessageDigest md = Util.mdSha256();
            return hex(md.digest(pin.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean samakanHexLegasi(String hexKecil, String stored) {
        if (hexKecil == null || stored == null) {
            return false;
        }
        int n = stored.length();
        int awal = 0;
        while (awal < n && stored.charAt(awal) <= ' ') {
            awal++;
        }
        int akhir = n;
        while (akhir > awal && stored.charAt(akhir - 1) <= ' ') {
            akhir--;
        }
        if (akhir - awal != hexKecil.length()) {
            return false;
        }
        int beda = 0;
        for (int i = 0; i < hexKecil.length(); i++) {
            char c = stored.charAt(awal + i);
            if (c >= 'A' && c <= 'F') {
                c = (char) (c + 32);
            }
            beda |= c ^ hexKecil.charAt(i);
        }
        return beda == 0;
    }

    static String hex(byte[] data) {
        return Util.heks(data);
    }

    private static byte[] unhex(String s) {
        if (s == null || (s.length() & 1) != 0 || s.isEmpty()) {
            return null;
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
