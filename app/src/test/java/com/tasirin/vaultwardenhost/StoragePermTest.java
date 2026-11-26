package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
        assertEquals(1, StoragePerm.izinRuntime(23).length);
        assertEquals(1, StoragePerm.izinRuntime(29).length);
        assertEquals(0, StoragePerm.izinRuntime(30).length);
        assertEquals(0, StoragePerm.izinRuntime(34).length);
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
    public void folderPribadiAndroidDataTakButuhIzin() {
        assertFalse(StoragePerm.butuhIzinEksternal(
                "/storage/emulated/0/Android/data/com.tasirin.vaultwardenhost/files/vaultwarden"));
        assertFalse(StoragePerm.butuhIzinEksternal(
                "/storage/emulated/0/Android/data/com.tasirin.vaultwardenhost"));
        assertFalse(StoragePerm.butuhIzinEksternal(
                "/sdcard/Android/data/com.tasirin.vaultwardenhost/files/vaultwarden"));
        assertTrue(StoragePerm.butuhIzinEksternal(
                "/storage/emulated/0/Android/data/com.aplikasi.lain/files"));
        assertTrue(StoragePerm.butuhIzinEksternal(
                "/sdcard/cadangan/Android/data/com.tasirin.vaultwardenhost/files/vaultwarden"));
        assertTrue(StoragePerm.butuhIzinEksternal(
                "/storage/emulated/0/cadangan/Android/data/com.tasirin.vaultwardenhost"));
    }

    @Test
    public void folderPribadiAplikasiHanyaSesudahRoot() {
        assertTrue(StoragePerm.folderPribadiAplikasi(
                "/storage/emulated/0/Android/data/com.tasirin.vaultwardenhost/files/vaultwarden"));
        assertTrue(StoragePerm.folderPribadiAplikasi(
                "/mnt/sdcard/Android/data/com.tasirin.vaultwardenhost"));
        assertFalse(StoragePerm.folderPribadiAplikasi(
                "/sdcard/cadangan/Android/data/com.tasirin.vaultwardenhost/files/vaultwarden"));
        assertFalse(StoragePerm.folderPribadiAplikasi(
                "/data/data/com.tasirin.vaultwardenhost/files/vaultwarden"));
        assertFalse(StoragePerm.folderPribadiAplikasi(
                "/storage/emulated/0/Android/data/com.tasirin.vaultwardenhost.evil/files"));
        assertFalse(StoragePerm.folderPribadiAplikasi(
                "/storage/Android/data/com.tasirin.vaultwardenhost/files"));
        assertTrue(StoragePerm.folderPribadiAplikasi(
                "/storage/1234-5678/Android/data/com.tasirin.vaultwardenhost/files/vaultwarden"));
        // Audit 2778: akar /mnt/media_rw/<uuid> dua segmen.
        assertTrue(StoragePerm.folderPribadiAplikasi(
                "/mnt/media_rw/1234-5678/Android/data/com.tasirin.vaultwardenhost/files/vaultwarden"));
        assertFalse(StoragePerm.folderPribadiAplikasi(
                "/mnt/media_rw/1234-5678/Android/data/com.aplikasi.lain/files"));
        assertFalse(StoragePerm.folderPribadiAplikasi(
                "/mnt/media_rw/Android/data/com.tasirin.vaultwardenhost/files"));
        assertFalse(StoragePerm.butuhIzinEksternal(
                "/mnt/media_rw/1234-5678/Android/data/com.tasirin.vaultwardenhost/files/vaultwarden"));
        // Audit 2811: akar /mnt/expand/<uuid> dua segmen.
        assertTrue(StoragePerm.folderPribadiAplikasi(
                "/mnt/expand/1234-5678/Android/data/com.tasirin.vaultwardenhost/files/vaultwarden"));
        assertFalse(StoragePerm.folderPribadiAplikasi(
                "/mnt/expand/1234-5678/Android/data/com.aplikasi.lain/files"));
        assertFalse(StoragePerm.folderPribadiAplikasi(
                "/mnt/expand/Android/data/com.tasirin.vaultwardenhost/files"));
        assertFalse(StoragePerm.butuhIzinEksternal(
                "/mnt/expand/1234-5678/Android/data/com.tasirin.vaultwardenhost/files/vaultwarden"));
        assertFalse(StoragePerm.folderPribadiAplikasi(
                "/storage//Android/data/com.tasirin.vaultwardenhost/files"));
    }

    @Test
    public void cukupAkses_api30TolakGrantTulisLama() {
        assertTrue(StoragePerm.cukupAkses(30, true, false));
        assertTrue(StoragePerm.cukupAkses(34, true, true));

        assertFalse(StoragePerm.cukupAkses(30, false, true));
        assertFalse(StoragePerm.cukupAkses(33, false, true));
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
    public void awalanMiripBukanEksternal() {
        assertFalse(StoragePerm.butuhIzinEksternal("/sdcard2evil/vault"));
        assertFalse(StoragePerm.butuhIzinEksternal("/storagex"));
        assertTrue(StoragePerm.butuhIzinEksternal("/sdcard"));
        assertTrue(StoragePerm.butuhIzinEksternal("/sdcard/"));
    }

    @Test
    public void penolakanPermanenHanyaBilaRationaleMatiDanBelumDiizinkan() {
        assertTrue(StoragePerm.penolakanPermanen(false, false));
        assertFalse(StoragePerm.penolakanPermanen(true, false));
        assertFalse(StoragePerm.penolakanPermanen(false, true));
        assertFalse(StoragePerm.penolakanPermanen(true, true));
    }

    @Test
    public void folderInternalTakButuhIzin() {
        assertFalse(StoragePerm.butuhIzinEksternal("/data/data/com.tasirin.vaultwardenhost/files"));
        assertFalse(StoragePerm.butuhIzinEksternal("/data/user/0/com.tasirin.vaultwardenhost/files"));
    }
}
