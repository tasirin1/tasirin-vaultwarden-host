package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test pewarna log layar awal (logika murni tanpa runtime Android). */
public class MainActivityTest {

    @Test
    public void indeksTakPekaAscii() {
        assertEquals(4, MainActivity.indeksTakPekaRentang("tls ERROR sini", 0, 14, "error"));
        assertEquals(-1, MainActivity.indeksTakPekaRentang("log bersih", 0, 10, "error"));
        assertEquals(-1, MainActivity.indeksTakPekaRentang("ab", 0, 2, "abc"));
        assertEquals(-1, MainActivity.indeksTakPekaRentang("tls error", 0, 4, "error"));
    }

    @Test
    public void muatKataRentangHormatiBatasRentang() {
        String teks = "baris bersih\ntls ERROR gagal\nawas deprecated\n";
        String[] galat = {"error", "gagal"};
        assertTrue(MainActivity.muatKataRentang(teks, 13, 27, galat));
        assertFalse(MainActivity.muatKataRentang(teks, 0, 12, galat));
        assertTrue(MainActivity.muatKataRentang("tls error", 0, 9, galat));
        assertFalse(MainActivity.muatKataRentang("log bersih", 0, 10, galat));
    }
}
