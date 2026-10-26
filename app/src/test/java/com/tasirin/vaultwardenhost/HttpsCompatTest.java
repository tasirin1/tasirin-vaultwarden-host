package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class HttpsCompatTest {

    @Test
    public void pilihProtokol_hanyaModernBerurutan() {
        assertArrayEquals(new String[]{"TLSv1.3", "TLSv1.2"},
                HttpsCompat.pilihProtokol(new String[]{"TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1"}));
        assertArrayEquals(new String[]{"TLSv1.2"},
                HttpsCompat.pilihProtokol(new String[]{"TLSv1", "TLSv1.2"}));
        assertEquals(0, HttpsCompat.pilihProtokol(new String[]{"TLSv1", "SSLv3"}).length);
        assertEquals(0, HttpsCompat.pilihProtokol(null).length);
        assertEquals(0, HttpsCompat.pilihProtokol(new String[0]).length);
    }

    @Test
    public void saringCipher_buangLemahPertahankanUrutan() {
        String[] masuk = {
                "TLS_RSA_WITH_RC4_128_SHA",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_RSA_WITH_3DES_EDE_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA",
                "TLS_RSA_WITH_AES_256_CBC_SHA"};
        assertArrayEquals(new String[]{
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_RSA_WITH_AES_256_CBC_SHA"},
                HttpsCompat.saringCipher(masuk));
        assertEquals(0, HttpsCompat.saringCipher(
                new String[]{"TLS_RSA_WITH_RC4_128_SHA"}).length);
        assertEquals(0, HttpsCompat.saringCipher(null).length);
    }

    @Test
    public void cipherKuat_terimaModernTolakLemah() {
        assertTrue(HttpsCompat.cipherKuat("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"));
        assertTrue(HttpsCompat.cipherKuat("TLS_RSA_WITH_AES_256_CBC_SHA"));

        assertTrue(HttpsCompat.cipherKuat("TLS_CHACHA20_POLY1305_SHA256"));
        assertTrue(HttpsCompat.cipherKuat("TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"));
        assertFalse(HttpsCompat.cipherKuat("TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA"));
        assertFalse(HttpsCompat.cipherKuat("TLS_RSA_WITH_RC4_128_SHA"));
        assertFalse(HttpsCompat.cipherKuat(null));
    }

    @Test
    public void sinyalGagalTrust_hanyaUntukKegagalanBaru() {
        assertTrue(HttpsCompat.sinyalGagalTrust(null, "chain kosong"));
        assertTrue(HttpsCompat.sinyalGagalTrust("chain kosong", "parse gagal"));
        assertFalse(HttpsCompat.sinyalGagalTrust("chain kosong", "chain kosong"));
        assertFalse(HttpsCompat.sinyalGagalTrust(null, null));
        HttpsCompat.catatGagalTrust(new Exception("uji-sinyal"));
        assertTrue("java.lang.Exception: uji-sinyal".equals(
                HttpsCompat.gagalTrustTerakhir()));
    }

    @Test
    public void aliasCaSistem_tolakCaPengguna() {
        assertTrue(HttpsCompat.aliasCaSistem("system:1"));
        assertTrue(HttpsCompat.aliasCaSistem("added:0"));
        assertFalse(HttpsCompat.aliasCaSistem("user:12"));
        assertFalse(HttpsCompat.aliasCaSistem(null));
    }

    @Test
    public void bungkusTls12_tolakNullTeruskanKuat() {
        assertNull(HttpsCompat.bungkusTls12(null));
        try {
            javax.net.ssl.SSLSocketFactory bawaan =
                    javax.net.ssl.SSLContext.getDefault().getSocketFactory();
            javax.net.ssl.SSLSocketFactory bungkus = HttpsCompat.bungkusTls12(bawaan);
            assertNotNull(bungkus);
            assertArrayEquals(bawaan.getDefaultCipherSuites(),
                    bungkus.getDefaultCipherSuites());
        } catch (Exception e) {
            fail("bungkusTls12 melempar: " + e);
        }
    }
}
