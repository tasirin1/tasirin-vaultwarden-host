package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit test pilihan TLS cache (murni, tanpa handshake). */
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
                "TLS_RSA_WITH_AES_256_CBC_SHA"};
        assertArrayEquals(new String[]{
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_RSA_WITH_AES_256_CBC_SHA"},
                HttpsCompat.saringCipher(masuk));
        assertEquals(0, HttpsCompat.saringCipher(
                new String[]{"TLS_RSA_WITH_RC4_128_SHA"}).length);
        assertEquals(0, HttpsCompat.saringCipher(null).length);
    }
}
