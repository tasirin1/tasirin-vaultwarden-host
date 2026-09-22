package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

/** Unit test util murni (tanpa Android runtime). */
public class TgBackupTest {

    @Test
    public void humanBytes_skalaBenar() {
        assertEquals("0 B", TgBackup.humanBytes(0));
        assertEquals("512 B", TgBackup.humanBytes(512));
        assertEquals("1.0 KB", TgBackup.humanBytes(1024));
        assertEquals("1.5 KB", TgBackup.humanBytes(1536));
        assertEquals("2.0 MB", TgBackup.humanBytes(2 * 1048576));
        assertEquals("1.0 GB", TgBackup.humanBytes(1073741824L));
        assertEquals("?", TgBackup.humanBytes(-1));
    }

    @Test
    public void dbSiap_butuhFileBerisiDanBerheader() throws Exception {
        assertFalse(TgBackup.dbSiap(null));
        assertFalse(TgBackup.dbSiap(new File("/tidak/ada/db.sqlite3")));
        File kosong = File.createTempFile("dbkosong", ".sqlite3");
        assertFalse(TgBackup.dbSiap(kosong));
        try (FileOutputStream o = new FileOutputStream(kosong)) {
            o.write(new byte[]{1, 2, 3});
        }
        assertFalse(TgBackup.dbSiap(kosong));
        File valid = File.createTempFile("dbvalid", ".sqlite3");
        try (FileOutputStream o = new FileOutputStream(valid)) {
            o.write("SQLite format 3\0sampel".getBytes("UTF-8"));
        }
        assertTrue(TgBackup.dbSiap(valid));
        kosong.delete();
        valid.delete();
    }

    @Test
    public void sudahGantiHari_bedaHariKalender() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(2026, java.util.Calendar.SEPTEMBER, 21, 23, 59, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        long malam = c.getTimeInMillis();
        c.set(2026, java.util.Calendar.SEPTEMBER, 22, 0, 1, 0);
        long besok = c.getTimeInMillis();
        assertTrue(TgBackup.sudahGantiHari(malam, besok));
        assertFalse(TgBackup.sudahGantiHari(malam, malam + 30_000));
        assertFalse(TgBackup.sudahGantiHari(besok, besok));
        assertTrue(TgBackup.sudahGantiHari(0, besok));
    }

    @Test
    public void nextMidnight_jamSatuPagiBesok() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(2026, java.util.Calendar.SEPTEMBER, 21, 15, 30, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        long hasil = TgBackup.nextMidnight(c.getTimeInMillis());
        java.util.Calendar h = java.util.Calendar.getInstance();
        h.setTimeInMillis(hasil);
        assertEquals(22, h.get(java.util.Calendar.DAY_OF_MONTH));
        assertEquals(0, h.get(java.util.Calendar.HOUR_OF_DAY));
        assertEquals(1, h.get(java.util.Calendar.MINUTE));
        assertTrue(hasil > c.getTimeInMillis());
    }

    @Test
    public void pinAktif_butuhHash() {
        assertTrue(TgBackup.pinAktif(true, "PBKDF2$120000$aa$bb"));
        assertFalse(TgBackup.pinAktif(true, ""));
        assertFalse(TgBackup.pinAktif(true, null));
        assertFalse(TgBackup.pinAktif(false, "PBKDF2$120000$aa$bb"));
        assertFalse(TgBackup.pinAktif(false, ""));
    }

    @Test
    public void secretTakIkutBackup() {
        assertTrue(TgBackup.SECRET_PREF_KEYS.contains("admin_token"));
        assertTrue(TgBackup.SECRET_PREF_KEYS.contains("tg_token"));
        assertTrue(TgBackup.SECRET_PREF_KEYS.contains("tg_chat"));
        assertTrue(TgBackup.SECRET_PREF_KEYS.contains("tg_pass"));
        assertTrue(TgBackup.SECRET_PREF_KEYS.contains("pin_hash"));
    }

    @Test
    public void normalisasiEntriZip_terimaTopLevel() {
        assertEquals("db.sqlite3", TgBackup.normalisasiEntriZip("db.sqlite3"));
        assertEquals("db.sqlite3-wal", TgBackup.normalisasiEntriZip("db.sqlite3-wal"));
        assertEquals("db.sqlite3-shm", TgBackup.normalisasiEntriZip("db.sqlite3-shm"));
        assertEquals("tls/cert.pem", TgBackup.normalisasiEntriZip("tls/cert.pem"));
        assertEquals("tls/ca.pem", TgBackup.normalisasiEntriZip("tls/ca.pem"));
        assertEquals("app-config.json", TgBackup.normalisasiEntriZip("app-config.json"));
    }

    @Test
    public void normalisasiEntriZip_kupasFolderPembungkus() {
        assertEquals("db.sqlite3",
                TgBackup.normalisasiEntriZip("vaultwarden/db.sqlite3"));
        assertEquals("tls/cert.pem",
                TgBackup.normalisasiEntriZip("vaultwarden/tls/cert.pem"));
        assertEquals("app-config.json",
                TgBackup.normalisasiEntriZip("data/app-config.json"));
    }

    @Test
    public void normalisasiEntriZip_tolakLicikDanAsing() {
        assertEquals(null, TgBackup.normalisasiEntriZip(null));
        assertEquals(null, TgBackup.normalisasiEntriZip(""));
        assertEquals(null, TgBackup.normalisasiEntriZip("../evil.sqlite3"));
        assertEquals(null, TgBackup.normalisasiEntriZip("a/../../evil"));
        assertEquals(null, TgBackup.normalisasiEntriZip("C:/data/db.sqlite3"));
        assertEquals("db.sqlite3", TgBackup.normalisasiEntriZip("/db.sqlite3"));
        assertEquals(null, TgBackup.normalisasiEntriZip("foto.jpg"));
        assertEquals("db.sqlite3", TgBackup.normalisasiEntriZip("backups/db.sqlite3"));
    }
}
