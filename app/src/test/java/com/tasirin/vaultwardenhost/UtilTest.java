package com.tasirin.vaultwardenhost;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.File;

public class UtilTest {
    @Test
    public void cocokChatNumerik() {
        assertTrue(Util.cocokChat("12345", 12345, ""));
        assertTrue(Util.cocokChat(" 12345 ", 12345, "lain"));
        assertFalse(Util.cocokChat("12345", 999, ""));
        assertFalse(Util.cocokChat("", 12345, ""));
    }

    @Test
    public void cocokChatUsername() {
        assertTrue(Util.cocokChat("@nama", 999, "nama"));
        assertTrue(Util.cocokChat("nama", 999, "@NAMA"));
        assertFalse(Util.cocokChat("@nama", 999, "lain"));
        assertFalse(Util.cocokChat("@nama", 999, null));
    }

    @Test
    public void pesanSegarToleranMasaDepan() {
        long kini = 1_000_000L;
        assertFalse(Util.pesanSegar(kini - 400_000L, kini, 300_000L));
        assertTrue(Util.pesanSegar(kini - 100_000L, kini, 300_000L));
        assertTrue(Util.pesanSegar(kini + 60_000L, kini, 300_000L));
        assertTrue(Util.pesanSegar(0, kini, 300_000L));
    }

    @Test
    public void tolakRedirectHttp() {
        assertTrue(Util.bolehIkutiRedirect("https://a/b", "https://c/d"));
        assertTrue(Util.bolehIkutiRedirect("https://a/b", "/d"));
        assertFalse(Util.bolehIkutiRedirect("https://a/b", "http://c/d"));
        assertFalse(Util.bolehIkutiRedirect("https://a/b", ""));
    }

    @Test
    public void sambungRedirectRelatif() {
        assertEquals("https://h:1/x", Util.sambungRedirect("https://h:1/a", "/x"));
        assertEquals("https://c/d", Util.sambungRedirect("https://a/b", "https://c/d"));
    }

    @Test
    public void batasUnzip() throws Exception {
        long t = Util.tambahUkuranUnzip(0, 10, 100, 1, 10);
        assertEquals(10, t);
        try {
            Util.tambahUkuranUnzip(90, 20, 100, 1, 10);
            fail("harus lempar batas ukuran");
        } catch (java.io.IOException diharapkan) {
        }
        try {
            Util.tambahUkuranUnzip(0, 1, 100, 11, 10);
            fail("harus lempar batas entri");
        } catch (java.io.IOException diharapkan) {
        }
    }

    @Test
    public void hapusHanyaTempInternal() throws Exception {
        File base = new File(System.getProperty("java.io.tmpdir"), "uji-util-" + System.nanoTime());
        File cache = new File(base, "cache");
        File files = new File(base, "files");
        cache.mkdirs();
        files.mkdirs();
        File dalam = new File(cache, "a.zip");
        dalam.createNewFile();
        assertTrue(Util.bolehHapusFile(cache, files, dalam));
        File luar = new File(base, "luar.zip");
        luar.createNewFile();
        assertFalse(Util.bolehHapusFile(cache, files, luar));
        dalam.delete();
        luar.delete();
        cache.delete();
        files.delete();
        base.delete();
    }
}
