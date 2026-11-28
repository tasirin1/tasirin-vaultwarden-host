package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PinGateTest {

    @Test
    public void catatanHasilBaru_cocokBerartiBersihkan() {
        assertNull(PinGate.catatanHasilBaru(3, true, 1000L, 500L));
    }

    @Test
    public void catatanHasilBaru_gagalDariHitunganBaru() {
        long[] h = PinGate.catatanHasilBaru(0, false, 10_000L, 2_000L);
        assertEquals(1L, h[0]);
        assertEquals(PinCrypto.kunciBerikutnyaMs(1, 10_000L), h[1]);
        assertEquals(PinCrypto.kunciElapsedBerikutnyaMs(1, 2_000L), h[2]);
    }

    @Test
    public void catatanHasilBaru_plafonGagalDijaga() {
        long[] h = PinGate.catatanHasilBaru(10_000, false, 0L, 0L);
        assertEquals(10_000L, h[0]);
    }

    @Test
    public void catatHasilKonteksNullAman() {
        PinGate.catatHasil(null, true, 0L);
        PinGate.catatHasil(null, false, 0L);
    }

    @Test
    public void pinMatiTidakMembukaLayar() {
        assertEquals(PinGate.KUNCI_MATI,
                PinGate.putusKunci(false, false, "pbkdf2"));

        assertEquals(PinGate.KUNCI_MATI,
                PinGate.putusKunci(false, true, ""));
    }

    @Test
    public void graceMencegahPinUlang() {
        assertEquals(PinGate.KUNCI_GRACE,
                PinGate.putusKunci(true, true, "pbkdf2"));

        assertEquals(PinGate.KUNCI_GRACE,
                PinGate.putusKunci(true, true, null));
    }

    @Test
    public void hashHilangMemaksaPerbaikan() {
        assertEquals(PinGate.KUNCI_RUSAK, PinGate.putusKunci(true, false, null));
        assertEquals(PinGate.KUNCI_RUSAK, PinGate.putusKunci(true, false, ""));

        assertTrue(PinGate.wajibLayarPin(PinGate.KUNCI_RUSAK));
    }

    @Test
    public void wajibLayarPin_hanyaSaatTerkunci() {
        assertTrue(PinGate.wajibLayarPin(PinGate.KUNCI_BUKA));
        assertTrue(PinGate.wajibLayarPin(PinGate.KUNCI_RUSAK));
        assertFalse(PinGate.wajibLayarPin(PinGate.KUNCI_MATI));
        assertFalse(PinGate.wajibLayarPin(PinGate.KUNCI_GRACE));
    }

    @Test
    public void gagalBerikutnyaTerpagar() {
        // Audit 2803: plafon 10_000, tak pernah meluap.
        org.junit.Assert.assertEquals(1, PinGate.gagalBerikutnya(0));
        org.junit.Assert.assertEquals(10_000,
                PinGate.gagalBerikutnya(9_999));
        org.junit.Assert.assertEquals(10_000,
                PinGate.gagalBerikutnya(10_000));
        org.junit.Assert.assertEquals(10_000,
                PinGate.gagalBerikutnya(Integer.MAX_VALUE));
    }

    @Test
    public void pinAktifTanpaGraceMembukaLayar() {
        assertEquals(PinGate.KUNCI_BUKA,
                PinGate.putusKunci(true, false, "pbkdf2"));
    }
}
