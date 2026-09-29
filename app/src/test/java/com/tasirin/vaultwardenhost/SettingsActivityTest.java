package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test fungsi murni Settings — tanpa Android runtime (jalan di CI via JVM). */
public class SettingsActivityTest {

    @Test
    public void galatPortTerimaKosongDanRentang() {
        assertNull(SettingsActivity.galatPort(null));
        assertNull(SettingsActivity.galatPort(""));
        assertNull(SettingsActivity.galatPort("1"));
        assertNull(SettingsActivity.galatPort("8088"));
        assertNull(SettingsActivity.galatPort("65535"));
    }

    @Test
    public void galatPortTolakDiLuarRentang() {
        assertNotNull(SettingsActivity.galatPort("0"));
        assertNotNull(SettingsActivity.galatPort("65536"));
        assertNotNull(SettingsActivity.galatPort("abc"));
        assertNotNull(SettingsActivity.galatPort("-5"));
        assertNotNull(SettingsActivity.galatPort("80.8"));
    }

    @Test
    public void galatFolderTerimaKosongDanBawaan() {
        assertNull(SettingsActivity.galatFolder(null));
        assertNull(SettingsActivity.galatFolder(""));
        assertNull(SettingsActivity.galatFolder(ServerService.DEFAULT_DATA_DIR));
    }

    @Test
    public void galatFolderTolakTraversalDanRelatif() {
        assertNotNull(SettingsActivity.galatFolder("/sdcard/../data"));
        assertNotNull(SettingsActivity.galatFolder("relatif/folder"));
        assertNotNull(SettingsActivity.galatFolder("/data"));
    }

    @Test
    public void buatTokenAcak24Alfanumerik() {
        String t = SettingsActivity.buatTokenAcak(new java.util.Random(7));
        assertEquals(24, t.length());
        assertTrue(t.matches("[A-Za-z0-9]+"));
        String lain = SettingsActivity.buatTokenAcak(new java.util.Random(8));
        assertTrue(lain.matches("[A-Za-z0-9]+"));
        assertFalse(t.equals(lain));
    }

    @Test
    public void badgeHttpsBerjalanDanBerhenti() {
        assertEquals("HTTPS aktif",
                SettingsActivity.teksBadgeHttps(true, ""));
        assertEquals("HTTPS aktif \u2022 Sertifikat TLS: 10 hari",
                SettingsActivity.teksBadgeHttps(true, "Sertifikat TLS: 10 hari"));
        assertEquals("Sertifikat TLS: 10 hari",
                SettingsActivity.teksBadgeHttps(false, "Sertifikat TLS: 10 hari"));
        assertTrue(SettingsActivity.teksBadgeHttps(false, "").contains("Start"));
        assertTrue(SettingsActivity.teksBadgeHttps(false, null).contains("Start"));
    }

    @Test
    public void wizardHanyaUntukInstalasiBaru() {
        assertFalse(SettingsActivity.perluWizard(true, "", "", "", ""));
        assertTrue(SettingsActivity.perluWizard(false, "", "", "", ""));
        assertTrue(SettingsActivity.perluWizard(false, null, null, null, null));
        assertFalse(SettingsActivity.perluWizard(false, "/sdcard/data-saya", "", "", ""));
        assertFalse(SettingsActivity.perluWizard(false, "", "9090", "", ""));
        assertFalse(SettingsActivity.perluWizard(false, "", "", "token", ""));
        assertFalse(SettingsActivity.perluWizard(false, "", "", "", "1.37.3"));
    }
}
