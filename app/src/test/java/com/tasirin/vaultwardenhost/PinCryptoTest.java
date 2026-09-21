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
    public void inputRusakDitolak() {
        assertFalse(PinCrypto.verify(null, "1"));
        assertFalse(PinCrypto.verify("", "1"));
        assertFalse(PinCrypto.verify(PinCrypto.hash("1"), null));
        assertFalse(PinCrypto.verify("PBKDF2$xxx", "1"));
        assertFalse(PinCrypto.verify("PBKDF2$0$zz$zz", "1"));
    }
}
