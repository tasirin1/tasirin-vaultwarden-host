package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test kripto PIN murni (tanpa Android runtime). */
public class PinCryptoTest {

    @Test
    public void hashLaluVerifikasi() {
        String h = PinCrypto.hash("123456");
        assertTrue(PinCrypto.isNewFormat(h));
        assertTrue(PinCrypto.verify(h, "123456"));
        assertFalse(PinCrypto.verify(h, "123457"));
        assertFalse(PinCrypto.verify(h, ""));
    }

    @Test
    public void saltAcakTiapHash() {
        assertFalse(PinCrypto.hash("1234").equals(PinCrypto.hash("1234")));
    }

    @Test
    public void hashLamaTetapDikenali() {
        String lama = PinCrypto.sha256("9999");
        assertFalse(PinCrypto.isNewFormat(lama));
        assertTrue(PinCrypto.verify(lama, "9999"));
        assertFalse(PinCrypto.verify(lama, "0000"));
    }

    @Test
    public void kunciLimaGagalBeruntun() {
        // Wall-clock agar reboot tak mereset lockout.
        long sekarang = 1_700_000_000_000L;
        assertTrue(PinCrypto.sisaKunciMs(4, 0, sekarang) == 0);
        long sampai = PinCrypto.kunciBerikutnyaMs(5, sekarang);
        assertTrue(sampai == sekarang + PinCrypto.KUNCI_MS);
        assertTrue(PinCrypto.sisaKunciMs(5, sampai, sekarang) == PinCrypto.KUNCI_MS);
        assertTrue(PinCrypto.sisaKunciMs(5, sampai, sampai) == 0);
        assertTrue(PinCrypto.sisaKunciMs(5, sampai, sampai + 1) == 0);
    }

    @Test
    public void kunciElapsedLamaDianggapKedaluwarsa() {
        // Format lama era elapsedRealtime tak boleh mengunci permanen.
        assertTrue(PinCrypto.sisaKunciMs(5, 1_300_000L, 1_700_000_000_000L) == 0);
    }

    @Test
    public void kunciWallBertahanSeolahReboot() {
        // Simulasi reboot: jam monoton reset tapi wall-clock jalan terus.
        long terkunci = 1_700_000_000_000L + PinCrypto.KUNCI_MS;
        assertTrue(PinCrypto.sisaKunciMs(5, terkunci, 1_700_000_000_000L + 60_000L) > 0);
    }

    @Test
    public void kunciWallBasiDianggapKedaluwarsa() {
        long wallBasi = 1_700_000_000_000L;
        long elapsed = 1_000_000L;
        assertTrue(PinCrypto.sisaKunciMs(5, wallBasi, elapsed) == 0);
    }

    @Test
    public void inputRusakDitolak() {
        assertFalse(PinCrypto.verify(null, "1"));
        assertFalse(PinCrypto.verify("", "1"));
        assertFalse(PinCrypto.verify(PinCrypto.hash("1"), null));
        assertFalse(PinCrypto.verify("PBKDF2$xxx", "1"));
        assertFalse(PinCrypto.verify("PBKDF2$0$zz$zz", "1"));
    }
}
