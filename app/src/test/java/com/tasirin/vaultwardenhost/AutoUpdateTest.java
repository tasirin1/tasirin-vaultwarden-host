package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoUpdateTest {


    @Test
    public void cekBerkalaAktifMengikutiSakelar() {
        // Audit 2858: alarm cek harian hanya hidup bila
        // salah satu auto-update menyala.
        org.junit.Assert.assertTrue(AutoUpdate.cekBerkalaAktif(true, false));
        org.junit.Assert.assertTrue(AutoUpdate.cekBerkalaAktif(false, true));
        org.junit.Assert.assertTrue(AutoUpdate.cekBerkalaAktif(true, true));
        org.junit.Assert.assertFalse(AutoUpdate.cekBerkalaAktif(false, false));
    }

    @Test
    public void hasilUpdateTertunda_kenaliTigaBentukProduksi() {
        // Audit 2862.
        assertTrue(AutoUpdate.hasilUpdateTertunda(
                "Cek pembaruan tertunda (jaringan GitHub terganggu); binary"
                        + " rilis sebelumnya tetap dipakai."));
        assertTrue(AutoUpdate.hasilUpdateTertunda(
                "Cek pembaruan terganggu jaringan; binary rilis sebelumnya"
                        + " tetap dipakai."));
        assertTrue(AutoUpdate.hasilUpdateTertunda(
                "Build Android v1.37.5 belum tersedia; binary rilis"
                        + " sebelumnya tetap dipakai."));
        assertFalse(AutoUpdate.hasilUpdateTertunda("Sudah versi terbaru."));
        assertFalse(AutoUpdate.hasilUpdateTertunda(null));
    }

    @Test
    public void lewatiBilaBaruGagalDanCacheAda() {
        long sekarang = 10_000_000L;
        assertTrue(AutoUpdate.bolehLewatiCobaLagi(sekarang - 1000, sekarang));
    }

    @Test
    public void cobaLagiBilaJedaTerlampaui() {
        long sekarang = 10_000_000L;
        assertFalse(AutoUpdate.bolehLewatiCobaLagi(
                sekarang - ServerService.TUNDA_ULANG_UNDUH_MS - 1, sekarang));
    }

    @Test
    public void tetapCobaBilaTanpaCache() {
        long sekarang = 10_000_000L;
        // Audit 2863: tanpa cache pun backoff berlaku.
        assertTrue(AutoUpdate.bolehLewatiCobaLagi(sekarang - 1000, sekarang));
        assertFalse(AutoUpdate.bolehLewatiCobaLagi(0, sekarang));
    }

    @Test
    public void tawarkanBaruSekaliPerVersi() {
        assertTrue(AutoUpdate.tawarkanBaru("", "1.37.4"));
        assertFalse(AutoUpdate.tawarkanBaru("1.37.4", "1.37.4"));
        assertFalse(AutoUpdate.tawarkanBaru("v1.37.4", "1.37.4"));
        assertTrue(AutoUpdate.tawarkanBaru("1.37.3", "1.37.4"));
    }

    @Test
    public void catatPenandaPengumumanSesudahDicobaTanpaGerbangNotifikasi() {
        assertTrue(AutoUpdate.catatPenandaPengumuman("", "1.37.4", true));
        assertTrue(AutoUpdate.catatPenandaPengumuman("1.37.3", "1.37.4", true));
        assertFalse(AutoUpdate.catatPenandaPengumuman("1.37.4", "1.37.4", true));
        assertFalse(AutoUpdate.catatPenandaPengumuman("v1.37.4", "1.37.4", true));
        assertFalse(AutoUpdate.catatPenandaPengumuman("", "1.37.4", false));
    }

    @Test
    public void tetapCobaBilaBelumPernahGagal() {
        // Audit 2863: tanda tangan menyusut (syarat cache
        // >= 1 MB dibuang) - belum pernah gagal berarti
        // stempel 0, dan lewati-coba-lagi selalu false.
        assertFalse(AutoUpdate.bolehLewatiCobaLagi(0, 10_000_000L));
    }
}
