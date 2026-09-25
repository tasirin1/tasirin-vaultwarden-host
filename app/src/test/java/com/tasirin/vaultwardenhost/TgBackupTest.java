package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    public void caAktif_utamakanInternal() throws Exception {
        File internal = File.createTempFile("ca-internal", ".pem");
        File lama = File.createTempFile("ca-lama", ".pem");
        assertEquals(internal, TgBackup.caAktif(internal, lama));
        assertEquals(lama, TgBackup.caAktif(new File("/tidak/ada/ca.pem"), lama));
        // Keduanya tak ada: kembalikan fallback agar pemanggil bisa lapor galat jelas.
        assertEquals(new File("/tidak/ada/juga.pem"),
                TgBackup.caAktif(new File("/tidak/ada/ca.pem"), new File("/tidak/ada/juga.pem")));
        internal.delete();
        lama.delete();
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
            byte[] magic = "SQLite format 3\0".getBytes("UTF-8");
            o.write(magic);
            o.write(new byte[1024 - magic.length]);
        }
        assertTrue(TgBackup.dbSiap(valid));
        File buntung = File.createTempFile("dbbuntung", ".sqlite3");
        try (FileOutputStream o = new FileOutputStream(buntung)) {
            o.write("SQLite format 3\0sampel".getBytes("UTF-8"));
        }
        assertFalse(TgBackup.dbSiap(buntung));
        kosong.delete();
        valid.delete();
        buntung.delete();
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
    public void verifikasiZip_tolakDbBuntung() throws Exception {
        File zip = File.createTempFile("vwzip", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("db.sqlite3"));
            zos.write("SQLite format 3\0sampel".getBytes("UTF-8"));
            zos.closeEntry();
        }
        assertNotNull(TgBackup.verifikasiZip(zip));
        zip.delete();
    }

    @Test
    public void backupTimestamp_unikPerMilidetik() {
        assertTrue(TgBackup.backupTimestamp().matches("\\d{8}-\\d{6}-\\d{3}"));
    }

    @Test
    public void namaBackupUnik_tambahSuffixBilaDipakai() {
        java.util.Set<String> kosong = new java.util.HashSet<>();
        assertEquals("a.zip", TgBackup.namaBackupUnik("a.zip", kosong));
        assertEquals("a.zip", TgBackup.namaBackupUnik("a.zip", null));
        java.util.Set<String> ada = new java.util.HashSet<>(
                java.util.Arrays.asList("a.zip", "a-1.zip"));
        assertEquals("a-2.zip", TgBackup.namaBackupUnik("a.zip", ada));
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

    @Test
    public void verifikasiZip_terimaBackupValid() throws Exception {
        File zip = File.createTempFile("vwbaik", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("db.sqlite3"));
            zos.write("SQLite format 3\0isi-palsu".getBytes("UTF-8"));
            zos.closeEntry();
        }
        assertNull(TgBackup.verifikasiZip(zip));
        zip.delete();
    }

    @Test
    public void extractFileId_dariJsonDanFallbackManual() {
        String json = "{\"ok\":true,\"result\":{\"document\":{"
                + "\"file_id\":\"ABC123\",\"file_name\":\"b.zip\"},"
                + "\"chat\":{\"id\":1}}}";
        assertEquals("ABC123", TgBackup.extractFileId(json));
        assertEquals("", TgBackup.extractFileId("{\"ok\":false}"));
        assertEquals("", TgBackup.extractFileId(null));
        assertEquals("", TgBackup.extractFileId("bukan json"));
        // Fallback manual bila struktur result/document hilang
        assertEquals("ZZZ9", TgBackup.extractFileId(
                "bla \"document\":{\"file_id\":\"ZZZ9\"} ekor"));
    }

    @Test
    public void bacaTerbatas_tolakMelebihiBatas() throws Exception {
        byte[] kecil = new byte[100];
        assertEquals(100, TgBackup.bacaTerbatas(
                new java.io.ByteArrayInputStream(kecil), 1024).length);
        byte[] besar = new byte[TgBackup.BATAS_CONFIG_JSON + 1];
        try {
            TgBackup.bacaTerbatas(
                    new java.io.ByteArrayInputStream(besar), TgBackup.BATAS_CONFIG_JSON);
            org.junit.Assert.fail("wajib lempar IOException saat over-batas");
        } catch (java.io.IOException diharapkan) {
        }
    }

    @Test
    public void satuDesimal_formatSamaSepertiDulu() {
        assertEquals("1.5 KB", TgBackup.satuDesimal(1536, 1024, "KB"));
        assertEquals("2.0 MB", TgBackup.satuDesimal(2 * 1048576, 1048576, "MB"));
        assertEquals("1.0 GB", TgBackup.satuDesimal(1073741824, 1073741824, "GB"));
        assertEquals("1.5 KB", TgBackup.humanBytes(1536));
        assertEquals("2.0 MB", TgBackup.humanBytes(2 * 1048576));
        assertEquals("1.0 GB", TgBackup.humanBytes(1073741824L));
    }

    @Test
    public void normalisasiEntriZip_tolakSiblingLicik() {
        // Entri berawalan nama folder data tapi di luar folder (tanpa separator)
        // wajib ditolak di allowlist, bukan hanya di cek canonical.
        assertEquals(null, TgBackup.normalisasiEntriZip("../vaultwarden-evil/x"));
        assertEquals(null, TgBackup.normalisasiEntriZip("db.sqlite3/../../evil"));
    }

    @Test
    public void verifikasiZip_tolakKorup() throws Exception {
        assertNotNull(TgBackup.verifikasiZip(null));
        assertNotNull(TgBackup.verifikasiZip(new File("/tidak/ada.zip")));
        File tanpaDb = File.createTempFile("vwtanpadb", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tanpaDb))) {
            zos.putNextEntry(new ZipEntry("catatan.txt"));
            zos.write("halo".getBytes("UTF-8"));
            zos.closeEntry();
        }
        assertNotNull(TgBackup.verifikasiZip(tanpaDb));
        tanpaDb.delete();
        File headerSalah = File.createTempFile("vwsalah", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(headerSalah))) {
            zos.putNextEntry(new ZipEntry("db.sqlite3"));
            zos.write("bukan-database-palsu-!!".getBytes("UTF-8"));
            zos.closeEntry();
        }
        assertNotNull(TgBackup.verifikasiZip(headerSalah));
        headerSalah.delete();
        File sampah = File.createTempFile("vwsampah", ".zip");
        try (FileOutputStream o = new FileOutputStream(sampah)) {
            o.write("bukan zip sama sekali".getBytes("UTF-8"));
        }
        assertNotNull(TgBackup.verifikasiZip(sampah));
        sampah.delete();
    }
}
