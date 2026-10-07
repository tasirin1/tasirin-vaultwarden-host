package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit test fungsi murni halaman login PIN — tanpa Android runtime. */
public class PinActivityTest {

    @Test
    public void teksSisaKunciCeilingMenit() {
        assertEquals("Locked, try again in 1 minute.", PinActivity.teksSisaKunci(1));
        assertEquals("Locked, try again in 1 minute.", PinActivity.teksSisaKunci(60000));
        assertEquals("Locked, try again in 2 minutes.", PinActivity.teksSisaKunci(60001));
        assertEquals("Locked, try again in 5 minutes.", PinActivity.teksSisaKunci(300000));
    }

    @Test
    public void teksSisaKunciNolTetapSatuMenit() {
        assertEquals("Locked, try again in 1 minute.", PinActivity.teksSisaKunci(0));
    }
}
