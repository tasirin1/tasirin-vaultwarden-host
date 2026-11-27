package com.tasirin.vaultwardenhost;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

// Audit 2764: inti kripto murni (tanpa kelas Android)
// untuk nilai rahasia SharedPreferences. Format gabungan:
// IV 12 byte || ciphertext+tag GCM. Kunci AES diserahkan
// pemanggil (di aplikasi: AndroidKeyStore).
final class RahasiaKripto {

    static final String AWALAN = "enc1:";
    private static final int PANJANG_IV = 12;

    private RahasiaKripto() {
    }

    static byte[] enkripsi(SecretKey kunci, byte[] nilai) throws Exception {
        byte[] iv = new byte[PANJANG_IV];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, kunci, new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(nilai);
        byte[] hasil = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, hasil, 0, iv.length);
        System.arraycopy(ct, 0, hasil, iv.length, ct.length);
        return hasil;
    }

    static byte[] dekripsi(SecretKey kunci, byte[] gabungan) throws Exception {
        if (gabungan == null || gabungan.length <= PANJANG_IV) {
            throw new IllegalArgumentException("data terenkripsi terlalu pendek");
        }
        byte[] iv = new byte[PANJANG_IV];
        System.arraycopy(gabungan, 0, iv, 0, PANJANG_IV);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, kunci, new GCMParameterSpec(128, iv));
        return c.doFinal(gabungan, PANJANG_IV, gabungan.length - PANJANG_IV);
    }
}
