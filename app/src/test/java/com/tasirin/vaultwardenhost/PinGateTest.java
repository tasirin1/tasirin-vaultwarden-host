package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PinGateTest {

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
    public void pinAktifTanpaGraceMembukaLayar() {
        assertEquals(PinGate.KUNCI_BUKA,
                PinGate.putusKunci(true, false, "pbkdf2"));
    }
}
