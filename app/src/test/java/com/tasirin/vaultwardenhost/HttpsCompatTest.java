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
    public void cacheFactorySegar_batas24Jam() {
        // Audit 2851.
        long jam = 3600_000L;
        org.junit.Assert.assertTrue(HttpsCompat.cacheFactorySegar(1000L, 1000L + 23 * jam));
        org.junit.Assert.assertFalse(HttpsCompat.cacheFactorySegar(1000L, 1000L + 24 * jam));
        org.junit.Assert.assertFalse(HttpsCompat.cacheFactorySegar(0L, 1000L));
        org.junit.Assert.assertFalse(HttpsCompat.cacheFactorySegar(2000L, 1000L));
    }

    @Test
    public void peringatanRantaiBerkas_kedaluwarsaDanMenua() {
        // Audit 2849.
        long kini = 1_700_000_000_000L;
        long hari = 24L * 3600 * 1000;
        org.junit.Assert.assertNull(HttpsCompat.peringatanRantaiBerkas(
                new long[]{kini + 365 * hari}, kini));
        org.junit.Assert.assertNull(HttpsCompat.peringatanRantaiBerkas(
                new long[]{-1}, kini));
        org.junit.Assert.assertNotNull(HttpsCompat.peringatanRantaiBerkas(
                new long[]{kini - hari}, kini));
        org.junit.Assert.assertNotNull(HttpsCompat.peringatanRantaiBerkas(
                new long[]{kini + 365 * hari, kini + 30 * hari}, kini));
    }

    @Test
    public void trustSistemCukup_hanyaBilaAdaCaSistem() {
        assertFalse(HttpsCompat.trustSistemCukup(0));
        assertFalse(HttpsCompat.trustSistemCukup(-1));
        // Enumerasi terpotong (segelintir CA) juga tak cukup (Audit 839).
        assertFalse(HttpsCompat.trustSistemCukup(1));
        assertFalse(HttpsCompat.trustSistemCukup(49));
        assertTrue(HttpsCompat.trustSistemCukup(50));
        assertTrue(HttpsCompat.trustSistemCukup(140));
    }

    @Test
    public void bolehBangunTrust_jedaSesudahGagal() {
        assertTrue(HttpsCompat.bolehBangunTrust(0, 5_000));
        assertTrue(HttpsCompat.bolehBangunTrust(10_000, 5_000));
        assertFalse(HttpsCompat.bolehBangunTrust(10_000, 10_000));
        assertFalse(HttpsCompat.bolehBangunTrust(10_000, 69_999));
        assertTrue(HttpsCompat.bolehBangunTrust(10_000, 70_000));
    }

    @Test
    public void protokolCadangan_irisanMempertahankanUrutanPilihan() {
        org.junit.Assert.assertArrayEquals(new String[]{"TLSv1.2"},
                HttpsCompat.protokolCadangan(new String[]{"TLSv1.3", "TLSv1.2"},
                        new String[]{"TLSv1.2", "TLSv1.1"}));
        org.junit.Assert.assertArrayEquals(new String[]{"TLSv1.3", "TLSv1.2"},
                HttpsCompat.protokolCadangan(new String[]{"TLSv1.3", "TLSv1.2"},
                        new String[]{"TLSv1.2", "TLSv1.3"}));
        assertEquals(0, HttpsCompat.protokolCadangan(new String[]{"TLSv1.3"},
                new String[]{"TLSv1.2"}).length);
        assertEquals(0, HttpsCompat.protokolCadangan(null, new String[]{"TLSv1.2"}).length);
        assertTrue(HttpsCompat.semuaProtokolDidukung(new String[]{"TLSv1.2"},
                new String[]{"TLSv1.3", "TLSv1.2"}));
        org.junit.Assert.assertFalse(HttpsCompat.semuaProtokolDidukung(
                new String[]{"TLSv1.3"}, new String[]{"TLSv1.2"}));
        org.junit.Assert.assertFalse(HttpsCompat.semuaProtokolDidukung(
                new String[]{"TLSv1.2"}, null));
    }

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
    public void cipherCadangan_irisanMengikutiUrutanPilihan() {
        assertArrayEquals(new String[]{"A", "C"},
                HttpsCompat.cipherCadangan(new String[]{"A", "B", "C"},
                        new String[]{"C", "A"}));
        assertEquals(0, HttpsCompat.cipherCadangan(
                new String[]{"A", "B"}, new String[]{"X", "Y"}).length);
        assertEquals(0, HttpsCompat.cipherCadangan(null, new String[]{"A"}).length);
        assertEquals(0, HttpsCompat.cipherCadangan(new String[]{"A"}, null).length);
    }

    @Test
    public void saringanSiap_hanyaBilaKeduanyaTerisi() {
        org.junit.Assert.assertTrue(HttpsCompat.saringanSiap(
                new String[]{"TLSv1.2"},
                new String[]{"TLS_AES_128_GCM_SHA256"}));
        org.junit.Assert.assertFalse(HttpsCompat.saringanSiap(
                new String[0],
                new String[]{"TLS_AES_128_GCM_SHA256"}));
        org.junit.Assert.assertFalse(HttpsCompat.saringanSiap(
                new String[]{"TLSv1.2"}, new String[0]));
        org.junit.Assert.assertFalse(HttpsCompat.saringanSiap(null, null));
    }

    @Test
    public void saringCipher_buangLemahPertahankanUrutan() {
        String[] masuk = {
                "TLS_RSA_WITH_RC4_128_SHA",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_RSA_WITH_3DES_EDE_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA",
                "TLS_AES_256_GCM_SHA384",
                "TLS_RSA_WITH_AES_256_CBC_SHA"};
        // Audit 1515: RSA statis (baris terakhir) kini tersaring;
        // suite TLS 1.3 (TLS_AES_*) lolos sebagai FS bawaan.
        assertArrayEquals(new String[]{
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384"},
                HttpsCompat.saringCipher(masuk));
        assertEquals(0, HttpsCompat.saringCipher(
                new String[]{"TLS_RSA_WITH_RC4_128_SHA"}).length);
        assertEquals(0, HttpsCompat.saringCipher(null).length);
    }

    @Test
    public void cipherKuat_terimaModernTolakLemah() {
        assertTrue(HttpsCompat.cipherKuat("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"));
        // Audit 1515: RSA statis tanpa forward secrecy ditolak,
        // walau enkripsinya AES-256/GCM.
        assertFalse(HttpsCompat.cipherKuat("TLS_RSA_WITH_AES_256_CBC_SHA"));
        assertFalse(HttpsCompat.cipherKuat("TLS_RSA_WITH_AES_128_GCM_SHA256"));
        assertTrue(HttpsCompat.cipherKuat("TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"));
        assertTrue(HttpsCompat.cipherKuat("TLS_AES_128_GCM_SHA256"));

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
                HttpsCompat.gagalTrustTerakhir));
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
            // Audit 2852: daftar yang diumumkan kini
            // tersaring kuat, bukan daftar bawaan mentah.
            assertArrayEquals(
                    HttpsCompat.saringCipher(bawaan.getDefaultCipherSuites()),
                    bungkus.getDefaultCipherSuites());
        } catch (Exception e) {
            fail("bungkusTls12 melempar: " + e);
        }
    }

    // Audit 2935: nyalakan() fail-closed (Audit 2763) dulu
    // tanpa test sama sekali. Soket palsu tulisan tangan -
    // harness hanya JUnit, tanpa Mockito/Robolectric.
    static final class SoketPalsu extends javax.net.ssl.SSLSocket {
        final String[] protokolDukung;
        final String[] cipherDukung;
        String[] protokolAktif;
        String[] cipherAktif;

        SoketPalsu(String[] protokol, String[] cipher) {
            this.protokolDukung = protokol;
            this.cipherDukung = cipher;
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return cipherDukung;
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return cipherAktif;
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
            cipherAktif = suites;
        }

        @Override
        public String[] getSupportedProtocols() {
            return protokolDukung;
        }

        @Override
        public String[] getEnabledProtocols() {
            return protokolAktif;
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
            protokolAktif = protocols;
        }

        @Override
        public javax.net.ssl.SSLSession getSession() {
            return null;
        }

        @Override
        public void addHandshakeCompletedListener(
                javax.net.ssl.HandshakeCompletedListener listener) {
        }

        @Override
        public void removeHandshakeCompletedListener(
                javax.net.ssl.HandshakeCompletedListener listener) {
        }

        @Override
        public void startHandshake() {
        }

        @Override
        public void setUseClientMode(boolean mode) {
        }

        @Override
        public boolean getUseClientMode() {
            return true;
        }

        @Override
        public void setNeedClientAuth(boolean need) {
        }

        @Override
        public boolean getNeedClientAuth() {
            return false;
        }

        @Override
        public void setWantClientAuth(boolean want) {
        }

        @Override
        public boolean getWantClientAuth() {
            return false;
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
        }

        @Override
        public boolean getEnableSessionCreation() {
            return true;
        }
    }

    static final class PabrikPalsu extends javax.net.ssl.SSLSocketFactory {
        private final SoketPalsu soket;

        PabrikPalsu(SoketPalsu soket) {
            this.soket = soket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return new String[0];
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return new String[0];
        }

        @Override
        public java.net.Socket createSocket() {
            return soket;
        }

        @Override
        public java.net.Socket createSocket(String host, int port) {
            return soket;
        }

        @Override
        public java.net.Socket createSocket(String host, int port,
                java.net.InetAddress localHost, int localPort) {
            return soket;
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host,
                int port) {
            return soket;
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress address,
                int port, java.net.InetAddress localAddress,
                int localPort) {
            return soket;
        }

        @Override
        public java.net.Socket createSocket(java.net.Socket s, String host,
                int port, boolean autoClose) {
            return soket;
        }
    }

    private static void resetCacheTls() throws Exception {
        for (String nama : new String[]{"protokolCache", "cipherCache",
                "mentahProtokol", "mentahCipher"}) {
            java.lang.reflect.Field f =
                    HttpsCompat.class.getDeclaredField(nama);
            f.setAccessible(true);
            f.set(null, null);
        }
    }

    @Test
    public void nyalakan_gagalTertutupBilaTakAdaTls12() throws Exception {
        resetCacheTls();
        SoketPalsu soket = new SoketPalsu(
                new String[]{"TLSv1.1", "TLSv1"},
                new String[]{"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"});
        javax.net.ssl.SSLSocketFactory bungkus =
                HttpsCompat.bungkusTls12(new PabrikPalsu(soket));
        try {
            bungkus.createSocket();
            fail("nyalakan harus menggagalkan soket tanpa TLS 1.2+");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage().contains("TLS 1.2"));
        }
    }

    @Test
    public void nyalakan_menerapkanProtokolDanCipherSaringan()
            throws Exception {
        resetCacheTls();
        SoketPalsu soket = new SoketPalsu(
                new String[]{"TLSv1.2", "TLSv1.1"},
                new String[]{"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                        "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA"});
        javax.net.ssl.SSLSocketFactory bungkus =
                HttpsCompat.bungkusTls12(new PabrikPalsu(soket));
        assertTrue(bungkus.createSocket() == soket);
        assertArrayEquals(new String[]{"TLSv1.2"}, soket.protokolAktif);
        assertArrayEquals(
                new String[]{"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"},
                soket.cipherAktif);
    }
}
