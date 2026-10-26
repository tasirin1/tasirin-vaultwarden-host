package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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

    private static String ulang(char c, int n) {
        char[] a = new char[n];
        java.util.Arrays.fill(a, c);
        return new String(a);
    }

    @Test
    public void pratinjauLogTakMemotongBaris() {

        String kecil = "a\nbb\nccc\n";
        assertEquals(kecil.length(),
                MainActivity.ujungBarisUtuh(kecil, 0, 8192, kecil.length()));

        String banyak = "baris-1\nbaris-2\nbaris-3\n";
        assertEquals(8, MainActivity.ujungBarisUtuh(banyak, 0, 9, banyak.length()));
        assertEquals(8, MainActivity.ujungBarisUtuh(banyak, 0, 7, banyak.length()));

        String raksasa = ulang('x', 9000) + "\nbaris\n";
        assertEquals(-1, MainActivity.ujungBarisUtuh(raksasa, 0, 8192, raksasa.length()));

        assertEquals(9001,
                MainActivity.awalBarisSetelah(raksasa, 8193, raksasa.length()));

        assertEquals(9000, MainActivity.awalBarisSetelah(ulang('x', 9000), 8193, 9000));

        assertEquals(0, MainActivity.awalBarisSetelah("", 0, 0));
        assertEquals(0, MainActivity.awalBarisSetelah(null, 0, 0));
        assertEquals(3, MainActivity.awalBarisSetelah("ab\ncd\n", -5, 6));
        assertEquals(3, MainActivity.awalBarisSetelah("ab\ncd\n", 0, 3));
    }

    @Test
    public void pinMenahanKontenHanyaSaatPinWajib() {
        assertTrue(MainActivity.pinMenahanKonten(true, false, false));
        assertTrue(MainActivity.pinMenahanKonten(false, true, false));
        assertFalse(MainActivity.pinMenahanKonten(false, true, true));
        assertFalse(MainActivity.pinMenahanKonten(false, false, false));
    }
}
