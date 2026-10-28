package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KernelCompatTest {

    @Test
    public void legacyMemoKonsistenDenganMurni() {
        boolean harapan = KernelCompat.isLegacyKernel(
                System.getProperty("os.version"));
        // Sifat yang diuji memang konsistensi memo vs jalur murni pada mesin
        // ini; nilai eksplisit isLegacyKernel dikunci uji-uji di bawah.
        assertEquals(harapan, KernelCompat.legacyPerangkat());
    }

    @Test
    public void kernel314StbTerdeteksiLegacy() {
        assertTrue(KernelCompat.isLegacyKernel("3.14.29"));
        assertTrue(KernelCompat.isLegacyKernel("3.14.29-g1234567"));
        assertTrue(KernelCompat.isLegacyKernel("3.10.0"));
        assertTrue(KernelCompat.isLegacyKernel("2.6.32"));
    }

    @Test
    public void kernelBaruBukanLegacy() {
        assertFalse(KernelCompat.isLegacyKernel("3.17.0"));
        assertFalse(KernelCompat.isLegacyKernel("3.18.140-gabc"));
        assertFalse(KernelCompat.isLegacyKernel("4.4.126"));
        assertFalse(KernelCompat.isLegacyKernel("4.9.112+"));
        assertFalse(KernelCompat.isLegacyKernel("5.4.0"));
    }

    @Test
    public void parseKernelTanpaAlokasiSetara() {

        assertEquals(14, KernelCompat.parseKernel("3.14.29-g123")[1]);
        assertEquals(17, KernelCompat.parseKernel("  03.017  ")[1]);
        assertEquals(5, KernelCompat.parseKernel("3.5.")[1]);
        assertEquals(null, KernelCompat.parseKernel("3."));
        assertEquals(null, KernelCompat.parseKernel(".5"));
        assertEquals(null, KernelCompat.parseKernel("3..5"));
        assertEquals(null, KernelCompat.parseKernel("v3.14"));
        assertEquals(null, KernelCompat.parseKernel("+3.14"));

        assertEquals(null, KernelCompat.parseKernel("\u0663.\u0661\u0664"));

        assertFalse(KernelCompat.isLegacyKernel("99999999999999999999.5"));
        assertFalse(KernelCompat.isLegacyKernel("3.99999999999999999999"));
    }

    @Test
    public void kernelTakDikenalDianggapModern() {
        assertFalse(KernelCompat.isLegacyKernel(null));
        assertFalse(KernelCompat.isLegacyKernel(""));
        assertFalse(KernelCompat.isLegacyKernel("abc"));
        assertFalse(KernelCompat.isLegacyKernel("3"));
    }

    @Test
    public void infoBarisMemuatKernelDanChannel() {
        String info = KernelCompat.infoBaris("3.14.29", 23);
        assertTrue(info.contains("3.14.29"));
        assertTrue(info.contains("23"));
        assertTrue(info.contains("legacy"));
        assertTrue(KernelCompat.infoBaris("4.4.126", 25).contains("modern"));
    }

    @Test
    public void namaAssetShimTetap() {
        assertEquals("libgetrandom-shim-armeabi-v7a.so", KernelCompat.SHIM_ASSET);
    }

    @Test
    public void saranShimGagalMenyebutLangkahKonkret() {
        String saran = KernelCompat.saranShimGagal("3.14.29");
        assertTrue(saran.contains("Cek Update"));
        assertTrue(saran.contains(KernelCompat.SHIM_ASSET));
    }
}
