package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

public class RahasiaKriptoTest {

    private static SecretKey kunci(byte isi) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, isi);
        return new SecretKeySpec(b, "AES");
    }

    @Test
    public void enkripsiDekripsiKembaliSama() throws Exception {
        SecretKey k = kunci((byte) 7);
        byte[] asal = "token-rahasia-123".getBytes(StandardCharsets.UTF_8);
        byte[] gab = RahasiaKripto.enkripsi(k, asal);
        assertFalse(java.util.Arrays.equals(asal, gab));
        assertArrayEquals(asal, RahasiaKripto.dekripsi(k, gab));
    }

    @Test
    public void enkripsiDuaKaliBerbedaIv() throws Exception {
        SecretKey k = kunci((byte) 7);
        byte[] asal = "sama".getBytes(StandardCharsets.UTF_8);
        byte[] a = RahasiaKripto.enkripsi(k, asal);
        byte[] b = RahasiaKripto.enkripsi(k, asal);
        assertFalse(java.util.Arrays.equals(a, b));
        assertArrayEquals(asal, RahasiaKripto.dekripsi(k, a));
        assertArrayEquals(asal, RahasiaKripto.dekripsi(k, b));
    }

    @Test
    public void dekripsiKunciLainGagal() throws Exception {
        byte[] gab = RahasiaKripto.enkripsi(kunci((byte) 7),
                "x".getBytes(StandardCharsets.UTF_8));
        try {
            RahasiaKripto.dekripsi(kunci((byte) 9), gab);
            fail("kunci lain harus gagal");
        } catch (Exception diharapkan) {
            org.junit.Assert.assertNotNull(diharapkan);
        }
    }

    @Test
    public void dekripsiDataRusakGagal() throws Exception {
        SecretKey k = kunci((byte) 7);
        byte[] gab = RahasiaKripto.enkripsi(k,
                "data".getBytes(StandardCharsets.UTF_8));
        gab[gab.length - 1] ^= 1;
        try {
            RahasiaKripto.dekripsi(k, gab);
            fail("data rusak harus gagal");
        } catch (Exception diharapkan) {
            org.junit.Assert.assertNotNull(diharapkan);
        }
        try {
            RahasiaKripto.dekripsi(k, new byte[4]);
            fail("data pendek harus gagal");
        } catch (Exception diharapkan) {
            org.junit.Assert.assertNotNull(diharapkan);
        }
    }
}
