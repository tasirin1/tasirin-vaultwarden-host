package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class SettingsActivityTest {

    @Test
    public void galatPortTerimaKosongDanRentang() {
        assertNull(SettingsActivity.galatPort(null, "SALAH"));
        assertNull(SettingsActivity.galatPort("", "SALAH"));
        assertNull(SettingsActivity.galatPort("1024", "SALAH"));
        assertNull(SettingsActivity.galatPort("8088", "SALAH"));
        assertNull(SettingsActivity.galatPort("65535", "SALAH"));
    }

    @Test
    public void galatPortTolakDiLuarRentang() {
        assertEquals("SALAH", SettingsActivity.galatPort("0", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatPort("1", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatPort("80", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatPort("443", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatPort("1023", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatPort("65536", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatPort("abc", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatPort("-5", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatPort("80.8", "SALAH"));
    }

    @Test
    public void batasExportBuangTerlamaDulu() {
        java.util.Map<String, Long> peta = new java.util.HashMap<>();
        peta.put("app-config-1.json", 1000L);
        peta.put("app-config-2.json", 2000L);
        peta.put("app-config-3.json", 3000L);
        peta.put("app-config-4.json", 4000L);
        java.util.List<String> hapus =
                SettingsActivity.pilihHapusBatasExport(peta, 3, null);
        assertEquals(1, hapus.size());
        assertEquals("app-config-1.json", hapus.get(0));
    }

    @Test
    public void batasExportPertahankanPendingSegar() {
        java.util.Map<String, Long> peta = new java.util.HashMap<>();
        peta.put("app-config-lama.json", 1000L);
        peta.put("app-config-a.json", 2000L);
        peta.put("app-config-b.json", 3000L);
        peta.put("app-config-c.json", 4000L);
        java.util.List<String> hapus = SettingsActivity.pilihHapusBatasExport(
                peta, 3, "app-config-lama.json");
        assertEquals(1, hapus.size());
        assertEquals("app-config-a.json", hapus.get(0));
    }

    @Test
    public void galatFolderTerimaKosongDanBawaan() {
        assertNull(SettingsActivity.galatFolder(null, "SALAH"));
        assertNull(SettingsActivity.galatFolder("", "SALAH"));
        assertNull(SettingsActivity.galatFolder(ServerService.DEFAULT_DATA_DIR, "SALAH"));
    }

    @Test
    public void galatFolderTolakTraversalDanRelatif() {
        assertEquals("SALAH", SettingsActivity.galatFolder("/sdcard/../data", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatFolder("relatif/folder", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatFolder("/data", "SALAH"));
    }

    @Test
    public void buatTokenAcak24Alfanumerik() {
        String t = SettingsActivity.buatTokenAcak(new java.security.SecureRandom());
        assertEquals(24, t.length());
        assertTrue(t.matches("[A-Za-z0-9]+"));
        String lain = SettingsActivity.buatTokenAcak(new java.security.SecureRandom());
        assertTrue(lain.matches("[A-Za-z0-9]+"));
        assertFalse(t.equals(lain));
    }

    @Test
    public void buatTokenAcakTolakRandomLemah() {
        try {
            SettingsActivity.buatTokenAcak(new java.util.Random(7));
            fail("Random biasa wajib ditolak");
        } catch (IllegalArgumentException diharapkan) {
        }
    }

    @Test
    public void badgeHttpsBerjalanDanBerhenti() {
        assertEquals("AKTIF",
                SettingsActivity.teksBadgeHttps(true, "", "AKTIF", "BELUM"));
        assertEquals("AKTIF \u2022 Sertifikat TLS: 10 hari",
                SettingsActivity.teksBadgeHttps(true, "Sertifikat TLS: 10 hari", "AKTIF", "BELUM"));
        assertEquals("Sertifikat TLS: 10 hari",
                SettingsActivity.teksBadgeHttps(false, "Sertifikat TLS: 10 hari", "AKTIF", "BELUM"));
        assertEquals("BELUM",
                SettingsActivity.teksBadgeHttps(false, "", "AKTIF", "BELUM"));
        assertEquals("BELUM",
                SettingsActivity.teksBadgeHttps(false, null, "AKTIF", "BELUM"));
    }

    @Test
    public void galatAdminKosongBolehIsiMinimal8() {
        assertNull(SettingsActivity.galatAdmin(null, "SALAH"));
        assertNull(SettingsActivity.galatAdmin("", "SALAH"));
        assertNull(SettingsActivity.galatAdmin("12345678", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatAdmin("pendek", "SALAH"));
    }

    @Test
    public void galatTgTokenFormatBot() {
        assertNull(SettingsActivity.galatTgToken(null, "SALAH"));
        assertNull(SettingsActivity.galatTgToken("", "SALAH"));
        assertNull(SettingsActivity.galatTgToken("123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatTgToken("tanpa-kolon", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatTgToken("123:pendek", "SALAH"));
    }

    @Test
    public void galatChatHarusAngka() {
        assertNull(SettingsActivity.galatChat(null, "SALAH"));
        assertNull(SettingsActivity.galatChat("", "SALAH"));
        assertNull(SettingsActivity.galatChat("123456789", "SALAH"));
        assertNull(SettingsActivity.galatChat("-1001234567890", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatChat("abc", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatChat("12.5", "SALAH"));

        assertNull(SettingsActivity.galatChat("@namabot", "SALAH"));
        assertNull(SettingsActivity.galatChat("namabot123", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatChat("@12345", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatChat("@ab", "SALAH"));
    }

    @Test
    public void galatChatDaftarMultiAkun() {
        assertNull(SettingsActivity.galatChat("123,456", "SALAH"));
        assertNull(SettingsActivity.galatChat("123; @namabot", "SALAH"));
        assertNull(SettingsActivity.galatChat("123 456", "SALAH"));
        assertNull(SettingsActivity.galatChat("+628123456789", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatChat("0123", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatChat("123, abc", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatChat("1,2,3,4,5,6", "SALAH"));
    }

    @Test
    public void galatChatSatuEntriTanpaAlokasi() {

        assertNull(SettingsActivity.galatChat("  123456789  ", "SALAH"));
        assertNull(SettingsActivity.galatChat("  @namabot  ", "SALAH"));
        assertEquals("SALAH", SettingsActivity.galatChat("  abc  ", "SALAH"));
        assertNull(SettingsActivity.galatChat("   ", "SALAH"));
    }

    @Test
    public void sisaEnkripTmpHanyaPrefixKhusus() {
        assertTrue(SettingsActivity.sisaEnkripTmp("app-config-enctmp-20240101-120000-abcdef.json"));
        assertFalse(SettingsActivity.sisaEnkripTmp("app-config-20240101-120000-abcdef.json"));
        assertFalse(SettingsActivity.sisaEnkripTmp("app-config-enctmp-20240101-120000-abcdef.enc"));
        assertFalse(SettingsActivity.sisaEnkripTmp(null));
        assertFalse(SettingsActivity.sisaEnkripTmp(""));
    }

    @Test
    public void imporDiblokirTepatDiBatasPin() {
        assertFalse(SettingsActivity.imporDiblokir(0));
        assertFalse(SettingsActivity.imporDiblokir(4));
        assertTrue(SettingsActivity.imporDiblokir(5));
        assertTrue(SettingsActivity.imporDiblokir(99));
    }

    @Test
    public void startDitahanSaatTugasDataUiAtauLatar() {

        assertFalse(SettingsActivity.startDitahanSaatTugasData(false, false));
        assertTrue(SettingsActivity.startDitahanSaatTugasData(true, false));
        assertTrue(SettingsActivity.startDitahanSaatTugasData(false, true));
        assertTrue(SettingsActivity.startDitahanSaatTugasData(true, true));
    }

    @Test
    public void sidikBerkasStabilDanHeks() throws Exception {
        java.io.File f = java.io.File.createTempFile("vwuji", ".bin");
        try {
            java.nio.file.Files.write(f.toPath(), "isi-uji".getBytes("UTF-8"));
            String a = SettingsActivity.sidikBerkas(f);
            String b = SettingsActivity.sidikBerkas(f);
            assertEquals(64, a.length());
            assertTrue(a.matches("[0-9a-f]+"));
            assertEquals(a, b);
        } finally {
            f.delete();
        }
        assertEquals("", SettingsActivity.sidikBerkas(null));
        assertEquals("", SettingsActivity.sidikBerkas(new java.io.File("/jalan/tak/ada/uji.json")));
    }

    @Test
    public void budgetImporKumulatifDanBersih() {
        String kunci = "uji-budget-" + System.nanoTime();
        assertEquals(0, SettingsActivity.ambilGagalImpor(kunci));
        SettingsActivity.simpanGagalImpor(kunci, 3);
        assertEquals(3, SettingsActivity.ambilGagalImpor(kunci));
        SettingsActivity.simpanGagalImpor(kunci, 5);
        assertTrue(SettingsActivity.imporDiblokir(SettingsActivity.ambilGagalImpor(kunci)));
        SettingsActivity.simpanGagalImpor(kunci, 0);
        assertEquals(0, SettingsActivity.ambilGagalImpor(kunci));
    }

    @Test
    public void gagalImporKemasPulihDanToleranRusak() {
        String kunci = "uji-kemas-" + System.nanoTime();
        SettingsActivity.simpanGagalImpor(kunci, 4);
        String kemasan = SettingsActivity.kemasGagalImpor();
        assertTrue(kemasan.contains(kunci + ":4"));
        SettingsActivity.simpanGagalImpor(kunci, 0);
        assertEquals(0, SettingsActivity.ambilGagalImpor(kunci));
        SettingsActivity.pulihkanGagalImpor(kemasan);
        assertEquals(4, SettingsActivity.ambilGagalImpor(kunci));
        SettingsActivity.pulihkanGagalImpor(null);
        SettingsActivity.pulihkanGagalImpor("");
        SettingsActivity.pulihkanGagalImpor(";,abc:xyz,:5,k2:-3,tanpatitik");
        assertEquals(0, SettingsActivity.ambilGagalImpor("abc"));
        assertEquals(0, SettingsActivity.ambilGagalImpor("k2"));
        SettingsActivity.simpanGagalImpor(kunci, 0);
    }

    @Test
    public void gantiPinButuhKonfirmasiHanyaSaatPinAktifDanHashAda() {

        assertTrue(SettingsActivity.perluKonfirmasiGantiPin(true, true));

        assertFalse(SettingsActivity.perluKonfirmasiGantiPin(true, false));

        assertFalse(SettingsActivity.perluKonfirmasiGantiPin(false, true));
        assertFalse(SettingsActivity.perluKonfirmasiGantiPin(false, false));
    }

    @Test
    public void aktivasiPinBasiTidakBolehMenyalakanPin() {

        assertTrue(SettingsActivity.aktivasiPinMasihBerlaku(3, 3));
        assertFalse(SettingsActivity.aktivasiPinMasihBerlaku(2, 3));
    }

    @Test
    public void dialogPinDilarangSejakOnDestroy() {

        assertTrue(SettingsActivity.bolehDialogPin(true, false, false));
        assertFalse(SettingsActivity.bolehDialogPin(false, false, false));
        assertFalse(SettingsActivity.bolehDialogPin(true, true, false));
        assertFalse(SettingsActivity.bolehDialogPin(true, false, true));
    }
}
