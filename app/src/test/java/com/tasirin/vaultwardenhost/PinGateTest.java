package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit test keputusan murni layar kunci PIN (tanpa Android runtime). */
public class PinGateTest {

    @Test
    public void pinMatiTidakMembukaLayar() {
        assertEquals(PinGate.KUNCI_MATI,
                PinGate.putusKunci(false, false, "pbkdf2"));
        // Grace basi saat PIN mati tetap dianggap mati (kunciBersama di wrapper).
        assertEquals(PinGate.KUNCI_MATI,
                PinGate.putusKunci(false, true, ""));
    }

    @Test
    public void graceMencegahPinUlang() {
        assertEquals(PinGate.KUNCI_GRACE,
                PinGate.putusKunci(true, true, "pbkdf2"));
        // Grace menang walau hash sempat kosong: PIN tak dimatikan diam-diam
        // hanya karena layar dibuka dalam grace.
        assertEquals(PinGate.KUNCI_GRACE,
                PinGate.putusKunci(true, true, null));
    }

    @Test
    public void hashHilangMemaksaPerbaikan() {
        assertEquals(PinGate.KUNCI_RUSAK, PinGate.putusKunci(true, false, null));
        assertEquals(PinGate.KUNCI_RUSAK, PinGate.putusKunci(true, false, ""));
    }

    @Test
    public void pinAktifTanpaGraceMembukaLayar() {
        assertEquals(PinGate.KUNCI_BUKA,
                PinGate.putusKunci(true, false, "pbkdf2"));
    }
}
