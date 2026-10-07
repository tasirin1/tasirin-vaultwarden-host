package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit test fungsi murni halaman login PIN — tanpa Android runtime. */
public class PinActivityTest {

    @Test
    public void teksSisaKunciCeilingMenit() {
        assertEquals("Terkunci, coba lagi 1 menit.", PinActivity.teksSisaKunci(1));
        assertEquals("Terkunci, coba lagi 1 menit.", PinActivity.teksSisaKunci(60000));
        assertEquals("Terkunci, coba lagi 2 menit.", PinActivity.teksSisaKunci(60001));
        assertEquals("Terkunci, coba lagi 5 menit.", PinActivity.teksSisaKunci(300000));
    }

    @Test
    public void teksSisaKunciNolTetapSatuMenit() {
        assertEquals("Terkunci, coba lagi 1 menit.", PinActivity.teksSisaKunci(0));
    }
}
