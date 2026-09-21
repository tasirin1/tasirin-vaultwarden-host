package com.tasirin.vaultwardenhost;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Hash & verifikasi PIN app (PBKDF2-HMAC-SHA256 + salt acak).
 *  Format simpan: {@code PBKDF2$iterasi$salthex$hashhex}.
 *  Menerima hash lama (SHA-256 tanpa salt, 64 hex) agar bisa diverifikasi
 *  sekali lalu dimigrasi ke format baru. Murni JVM — bisa unit test. */
public final class PinCrypto {

    private static final String PREFIX = "PBKDF2$";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private PinCrypto() {
    }

    /** Buat hash baru untuk PIN (salt acak tiap panggilan). */
    public static String hash(String pin) {
        if (pin == null) {
            pin = "";
        }
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] dk = derive(pin, salt, ITERATIONS);
        return PREFIX + ITERATIONS + "$" + hex(salt) + "$" + hex(dk);
    }

    /** True bila tersimpan dalam format baru (bukan hash lama). */
    public static boolean isNewFormat(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** Verifikasi PIN terhadap hash lama maupun baru (perbandingan konstan). */
    public static boolean verify(String stored, String pin) {
        if (stored == null || stored.isEmpty() || pin == null) {
            return false;
        }
        try {
            if (isNewFormat(stored)) {
                String[] parts = stored.split("\\$", -1);
                if (parts.length != 4) {
                    return false;
                }
                int iter = Integer.parseInt(parts[1]);
                if (iter <= 0 || iter > 1_000_000) {
                    return false;
                }
                byte[] salt = unhex(parts[2]);
                byte[] want = unhex(parts[3]);
                if (salt == null || want == null || salt.length == 0
                        || want.length != HASH_BITS / 8) {
                    return false;
                }
                byte[] got = derive(pin, salt, iter);
                return MessageDigest.isEqual(got, want);
            }
            // Legasi: SHA-256 tanpa salt — cocokkan lalu migrasi via hash().
            return slowHexEquals(sha256(pin), stored);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derive(String pin, byte[] salt, int iter) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iter, HASH_BITS);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            // PBKDF2-HMAC-SHA256 dijamin ada di Android & JVM; bila hilang,
            // gagal lantang (verify() menangkapnya sebagai false).
            throw new IllegalStateException("PBKDF2 tidak tersedia", e);
        } finally {
            spec.clearPassword();
        }
    }

    static String sha256(String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(pin.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean slowHexEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),
                b.getBytes(StandardCharsets.US_ASCII));
    }

    static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte v : data) {
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
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
