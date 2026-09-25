package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test logika izin storage murni (tanpa Android runtime). */
public class StoragePermTest {

    @Test
    public void kelolaSemuaFileMulaiApi30() {
        assertFalse(StoragePerm.butuhKelolaSemuaFile(29));
        assertFalse(StoragePerm.butuhKelolaSemuaFile(28));
        assertTrue(StoragePerm.butuhKelolaSemuaFile(30));
        assertTrue(StoragePerm.butuhKelolaSemuaFile(34));
    }

    @Test
    public void izinRuntimeKosongSebelumMarshmallow() {
        assertEquals(0, StoragePerm.izinRuntime(21).length);
        assertEquals(0, StoragePerm.izinRuntime(22).length);
        assertEquals(2, StoragePerm.izinRuntime(23).length);
        assertEquals(2, StoragePerm.izinRuntime(34).length);
    }

    @Test
    public void folderEksternalButuhIzin() {
        assertTrue(StoragePerm.butuhIzinEksternal("/sdcard/vaultwarden"));
        assertTrue(StoragePerm.butuhIzinEksternal("/storage/emulated/0/vaultwarden"));
        assertTrue(StoragePerm.butuhIzinEksternal("/mnt/sdcard/vaultwarden"));
        assertTrue(StoragePerm.butuhIzinEksternal(null));
        assertTrue(StoragePerm.butuhIzinEksternal("  "));
    }

    @Test
    public void cukupAkses_api30HanyaKelolaSemuaFile() {
        assertTrue(StoragePerm.cukupAkses(30, true, false));
        assertTrue(StoragePerm.cukupAkses(34, true, true));
        assertFalse(StoragePerm.cukupAkses(30, false, true));
        assertFalse(StoragePerm.cukupAkses(33, false, false));
    }

    @Test
    public void cukupAkses_apiLamaPakaiIzinRuntime() {
        assertTrue(StoragePerm.cukupAkses(29, false, true));
        assertFalse(StoragePerm.cukupAkses(29, false, false));
        assertTrue(StoragePerm.cukupAkses(23, false, true));
        assertFalse(StoragePerm.cukupAkses(28, false, false));
        assertTrue(StoragePerm.cukupAkses(21, false, false));
        assertTrue(StoragePerm.cukupAkses(22, false, false));
    }

    @Test
    public void tulisDiizinkan_cekNamaIzinBukanIndeks() {
        String tulis = "android.permission.WRITE_EXTERNAL_STORAGE";
        String baca = "android.permission.READ_EXTERNAL_STORAGE";
        assertTrue(StoragePerm.tulisDiizinkan(
                new String[]{baca, tulis}, new int[]{-1, 0}));
        assertFalse(StoragePerm.tulisDiizinkan(
                new String[]{tulis, baca}, new int[]{-1, 0}));
        assertFalse(StoragePerm.tulisDiizinkan(
                new String[]{baca}, new int[]{0}));
        assertFalse(StoragePerm.tulisDiizinkan(null, new int[]{0}));
        assertFalse(StoragePerm.tulisDiizinkan(new String[]{tulis}, null));
        assertFalse(StoragePerm.tulisDiizinkan(new String[0], new int[0]));
    }

    @Test
    public void folderInternalTakButuhIzin() {
        assertFalse(StoragePerm.butuhIzinEksternal("/data/data/com.tasirin.vaultwardenhost/files"));
        assertFalse(StoragePerm.butuhIzinEksternal("/data/user/0/com.tasirin.vaultwardenhost/files"));
    }
}
