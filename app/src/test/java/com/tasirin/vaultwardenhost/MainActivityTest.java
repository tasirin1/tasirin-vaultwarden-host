package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test pewarna log layar awal (logika murni tanpa runtime Android). */
public class MainActivityTest {

    @Test
    public void indeksTakPekaAscii() {
        assertEquals(6, MainActivity.indeksTakPeka("tls ERROR sini", "error"));
        assertEquals(-1, MainActivity.indeksTakPeka("log bersih", "error"));
        assertEquals(-1, MainActivity.indeksTakPeka("ab", "abc"));
    }

    @Test
    public void muatKataRentangSetaraWrapper() {
        String teks = "baris bersih\ntls ERROR gagal\nawas deprecated\n";
        String[] galat = {"error", "gagal"};
        assertTrue(MainActivity.muatKataRentang(teks, 13, 27, galat));
        assertFalse(MainActivity.muatKataRentang(teks, 0, 12, galat));
        assertTrue(MainActivity.muatKata("tls error", galat));
        assertFalse(MainActivity.muatKata("log bersih", galat));
    }
}
