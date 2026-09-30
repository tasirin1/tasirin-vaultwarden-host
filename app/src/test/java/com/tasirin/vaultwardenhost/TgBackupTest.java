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
    public void filePathTelegram_tolakTraversal() {
        assertTrue(TgBackup.filePathTelegramAman("documents/backup-telegram-abc.zip"));
        assertTrue(TgBackup.filePathTelegramAman("f/1.zip"));
        assertFalse(TgBackup.filePathTelegramAman("../../etc/passwd"));
        assertFalse(TgBackup.filePathTelegramAman("/absolut.zip"));
        assertFalse(TgBackup.filePathTelegramAman("a\\b.zip"));
        assertFalse(TgBackup.filePathTelegramAman(""));
        assertFalse(TgBackup.filePathTelegramAman(null));
    }

    @Test
    public void sanitasiNamaFile_tolakKutipCrlf() {
        assertEquals("backup.zip", TgBackup.sanitasiNamaFile(null));
        assertEquals("backup.zip", TgBackup.sanitasiNamaFile(""));
        assertEquals("db-backup.zip", TgBackup.sanitasiNamaFile("db-backup.zip"));
        assertEquals("evil.zip", TgBackup.sanitasiNamaFile("a/b/../evil.zip"));
        String licik = "a\".txt\"\r\n";
        String bersih = TgBackup.sanitasiNamaFile(licik);
        assertFalse(bersih.contains("\""));
        assertFalse(bersih.contains("\r"));
        assertFalse(bersih.contains("\n"));
    }

    @Test
    public void fileSizeDariRespons_bacaAngkaAman() {
        assertEquals(12345L, TgBackup.fileSizeDariRespons("{\"ok\":true,\"result\":{\"file_path\":\"d/f.zip\",\"file_size\":12345}}"));
        assertEquals(-1L, TgBackup.fileSizeDariRespons("{\"ok\":true}"));
        assertEquals(-1L, TgBackup.fileSizeDariRespons(null));
    }

    @Test
    public void encryptFile_hapusParsialSaatGagal() {
        java.io.File hilang = new java.io.File("/tidak/ada/masuk.bin");
        java.io.File keluar = new java.io.File(
                System.getProperty("java.io.tmpdir"),
                "vw-enc-gagal-" + System.nanoTime() + ".enc");
        try {
            TgBackup.encryptFile(hilang, keluar, "rahasia");
            org.junit.Assert.fail("wajib lempar bila masukan tak ada");
        } catch (Exception diharapkan) {
        }
        assertFalse(keluar.exists());
    }

    @Test
    public void encryptDecryptFile_rondtripUtuh() throws Exception {
        java.io.File asli = java.io.File.createTempFile("vw-asli", ".bin");
        java.io.File enc = java.io.File.createTempFile("vw-enc", ".enc");
        java.io.File pulih = new java.io.File(
                enc.getParentFile(), "vw-pulih-" + System.nanoTime() + ".bin");
        byte[] data = "data rahasia vaultwarden".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(asli)) {
            o.write(data);
        }
        try {
            TgBackup.encryptFile(asli, enc, "katasandi");
            TgBackup.decryptFile(enc, pulih, "katasandi");
            byte[] hasil = java.nio.file.Files.readAllBytes(pulih.toPath());
            org.junit.Assert.assertArrayEquals(data, hasil);
        } finally {
            asli.delete();
            enc.delete();
            pulih.delete();
        }
    }

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
    public void tlsAktifUntukBackup_utamakanInternal() throws Exception {
        java.io.File internal = new java.io.File(
                System.getProperty("java.io.tmpdir"), "vw-tls-int-" + System.nanoTime());
        java.io.File data = new java.io.File(
                System.getProperty("java.io.tmpdir"), "vw-tls-data-" + System.nanoTime());
        internal.mkdirs();
        data.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(internal, "ca.pem"))) {
            o.write("ca-internal".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try (FileOutputStream o = new FileOutputStream(new File(data, "ca.pem"))) {
            o.write("ca-basi".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try {
            assertEquals(internal, TgBackup.tlsAktifUntukBackup(internal, data));
            assertEquals(data, TgBackup.tlsAktifUntukBackup(
                    new File("/tidak/ada/tls"), data));
        } finally {
            new File(internal, "ca.pem").delete();
            new File(data, "ca.pem").delete();
            internal.delete();
            data.delete();
        }
    }

    @Test
    public void salinTls_menyalinEnamBerkas() throws Exception {
        java.io.File asal = new java.io.File(
                System.getProperty("java.io.tmpdir"), "vw-tls-asal-" + System.nanoTime());
        java.io.File tujuan = new java.io.File(
                System.getProperty("java.io.tmpdir"), "vw-tls-tuj-" + System.nanoTime());
        asal.mkdirs();
        for (String nama : new String[]{"ca.pem", "ca-key.pem", "cert.pem",
                "key.pem", "ips.txt", "version.txt"}) {
            try (FileOutputStream o = new FileOutputStream(new File(asal, nama))) {
                o.write(("isi-" + nama).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        try {
            assertEquals(6, TgBackup.salinTls(asal, tujuan));
            for (String nama : new String[]{"ca.pem", "cert.pem"}) {
                byte[] a = java.nio.file.Files.readAllBytes(new File(asal, nama).toPath());
                byte[] b = java.nio.file.Files.readAllBytes(new File(tujuan, nama).toPath());
                org.junit.Assert.assertArrayEquals(a, b);
            }
        } finally {
            for (java.io.File d : new java.io.File[]{asal, tujuan}) {
                java.io.File[] isi = d.listFiles();
                if (isi != null) {
                    for (java.io.File f : isi) {
                        f.delete();
                    }
                }
                d.delete();
            }
        }
    }

    @Test
    public void folderBytesWalk_symlinkMelingkarTakMeledak() throws Exception {
        java.nio.file.Path dasar = java.nio.file.Files.createTempDirectory("vw-walk-");
        java.nio.file.Path sub = java.nio.file.Files.createDirectory(dasar.resolve("sub"));
        java.nio.file.Files.write(sub.resolve("data.bin"), new byte[100]);
        try {
            java.nio.file.Files.createSymbolicLink(
                    dasar.resolve("loop"), dasar);
        } catch (Exception abaikan) {
            return;
        }
        try {
            long total = TgBackup.folderBytesWalk(dasar.toFile());
            assertTrue("hitung berlebih: " + total, total >= 100 && total < 100000);
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(dasar.resolve("loop"));
            } catch (Exception ignored) {
            }
            try {
                java.nio.file.Files.deleteIfExists(sub.resolve("data.bin"));
            } catch (Exception ignored) {
            }
            try {
                java.nio.file.Files.deleteIfExists(sub);
            } catch (Exception ignored) {
            }
            try {
                java.nio.file.Files.deleteIfExists(dasar);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void sisaTungguBootMs_limaMenitSetelahHidup() {
        assertEquals(5L * 60 * 1000, TgBackup.sisaTungguBootMs(0));
        assertEquals(1, TgBackup.sisaTungguBootMs(5L * 60 * 1000 - 1));
        assertEquals(0, TgBackup.sisaTungguBootMs(5L * 60 * 1000));
        assertEquals(0, TgBackup.sisaTungguBootMs(3600_000L));
        assertEquals(5L * 60 * 1000, TgBackup.sisaTungguBootMs(-100));
    }

    @Test
    public void bolehBackupSusulanBoot_hanyaHariBaruDanJamWajar() {
        long sehari = 24L * 3600 * 1000;
        long wajar = TgBackup.BATAS_JAM_WAJAR_MS + 10L * sehari;
        // Belum pernah backup + jam wajar: jalan (backup pertama).
        assertTrue(TgBackup.bolehBackupSusulanBoot(0, wajar));
        // Hari yang sama: jangan backup.
        assertFalse(TgBackup.bolehBackupSusulanBoot(wajar, wajar + 3600_000L));
        // Sudah ganti hari: langsung backup.
        assertTrue(TgBackup.bolehBackupSusulanBoot(wajar, wajar + sehari));
        // Jam reset ke 1999: jangan backup walau beda hari kalender.
        assertFalse(TgBackup.bolehBackupSusulanBoot(wajar, 915_148_800_000L));
        // Jam mundur di bawah backup terakhir: jangan backup.
        assertFalse(TgBackup.bolehBackupSusulanBoot(wajar + sehari, wajar));
    }

    @Test
    public void siapkanFileKirimCa_namaUnikDanIsiSama() throws Exception {
        File ca = File.createTempFile("ca-asli", ".pem");
        File cache = new File(System.getProperty("java.io.tmpdir"),
                "vw-ca-cache-" + System.nanoTime());
        cache.mkdirs();
        try (FileOutputStream o = new FileOutputStream(ca)) {
            o.write("isi-ca-publik".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try {
            File hasil = TgBackup.siapkanFileKirimCa(ca, cache, "20260929-120000-001");
            assertEquals("ca-cadangan-20260929-120000-001.pem", hasil.getName());
            assertTrue(hasil.isFile());
            byte[] asli = java.nio.file.Files.readAllBytes(ca.toPath());
            byte[] kirim = java.nio.file.Files.readAllBytes(hasil.toPath());
            org.junit.Assert.assertArrayEquals(asli, kirim);
        } finally {
            ca.delete();
            for (File f : cache.listFiles()) {
                f.delete();
            }
            cache.delete();
        }
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
    public void koersiBoolean_terimaStringAngka() {
        assertEquals(Boolean.TRUE, TgBackup.koersiBoolean(Boolean.TRUE));
        assertEquals(Boolean.TRUE, TgBackup.koersiBoolean("true"));
        assertEquals(Boolean.TRUE, TgBackup.koersiBoolean(" 1 "));
        assertEquals(Boolean.FALSE, TgBackup.koersiBoolean("false"));
        assertEquals(Boolean.FALSE, TgBackup.koersiBoolean(0));
        assertEquals(Boolean.TRUE, TgBackup.koersiBoolean(1));
        assertEquals(null, TgBackup.koersiBoolean("ya"));
        assertEquals(null, TgBackup.koersiBoolean(2));
        assertEquals(null, TgBackup.koersiBoolean(null));
    }

    @Test
    public void koersiLong_terimaStringAngka() {
        assertEquals(Long.valueOf(123), TgBackup.koersiLong(123));
        assertEquals(Long.valueOf(123), TgBackup.koersiLong(" 123 "));
        assertEquals(Long.valueOf(5_000_000_000L), TgBackup.koersiLong(5_000_000_000L));
        assertEquals(Long.valueOf(7), TgBackup.koersiLong(7.9));
        assertEquals(null, TgBackup.koersiLong("abc"));
        assertEquals(null, TgBackup.koersiLong(Boolean.TRUE));
        assertEquals(null, TgBackup.koersiLong(null));
    }

    @Test
    public void kunciIntLongTerdaftar() {
        assertTrue(TgBackup.KUNCI_INT.contains("pin_gagal"));
        assertTrue(TgBackup.KUNCI_INT.contains(TgBot.KEY_TG_MENU_HASH));
        assertTrue(TgBackup.KUNCI_LONG.contains("pin_kunci_sampai"));
        assertTrue(TgBackup.KUNCI_LONG.contains("pin_kunci_elapsed"));
        assertTrue(TgBackup.KUNCI_LONG.contains(TgBackup.KEY_TG_LAST));
        assertTrue(TgBackup.KUNCI_LONG.contains(TgBot.KEY_TG_OFFSET));
        assertTrue(TgBackup.KUNCI_LONG.contains(TgBot.KEY_TG_WALL_MAKS));
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
    public void pinOnHasilRestoreIkutPerangkat() {
        assertTrue(TgBackup.pinOnHasilRestore(true, "PBKDF2$120000$aa$bb"));
        assertFalse(TgBackup.pinOnHasilRestore(false, "PBKDF2$120000$aa$bb"));
        assertFalse(TgBackup.pinOnHasilRestore(true, ""));
        assertFalse(TgBackup.pinOnHasilRestore(true, null));
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
            // verifikasiZip menolak DB <512 byte: fixture valid wajib berukuran penuh
            byte[] magic = "SQLite format 3\0isi-palsu".getBytes("UTF-8");
            zos.write(magic);
            zos.write(new byte[1024 - magic.length]);
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
    public void adaSegmenDotDot_bedakanSubstringDanTraversal() {
        assertTrue(TgBackup.adaSegmenDotDot(".."));
        assertTrue(TgBackup.adaSegmenDotDot("../x"));
        assertTrue(TgBackup.adaSegmenDotDot("a/../b"));
        assertTrue(TgBackup.adaSegmenDotDot("a/../../evil"));
        assertFalse(TgBackup.adaSegmenDotDot("my..folder/x"));
        assertFalse(TgBackup.adaSegmenDotDot("db.sqlite3"));
        assertFalse(TgBackup.adaSegmenDotDot("tls/cert.pem"));
    }

    @Test
    public void normalisasiEntriZip_terimaNamaTitikGandaSah() {
        assertEquals("db.sqlite3",
                TgBackup.normalisasiEntriZip("my..folder/db.sqlite3"));
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

    @Test
    public void verifikasiIsiDbZip_tolakTanpaDbDanSampah() throws Exception {
        File tmp = File.createTempFile("vwisi", ".sqlite3");
        assertNotNull(TgBackup.verifikasiIsiDbZip(null, tmp));
        assertNotNull(TgBackup.verifikasiIsiDbZip(new File("/tidak/ada.zip"), tmp));
        File tanpaDb = File.createTempFile("vwisitanpadb", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tanpaDb))) {
            zos.putNextEntry(new ZipEntry("catatan.txt"));
            zos.write("halo".getBytes("UTF-8"));
            zos.closeEntry();
        }
        assertNotNull(TgBackup.verifikasiIsiDbZip(tanpaDb, tmp));
        tanpaDb.delete();
        File sampah = File.createTempFile("vwisisampah", ".zip");
        try (FileOutputStream o = new FileOutputStream(sampah)) {
            o.write("bukan zip sama sekali".getBytes("UTF-8"));
        }
        assertNotNull(TgBackup.verifikasiIsiDbZip(sampah, tmp));
        sampah.delete();
        tmp.delete();
    }

    @Test
    public void jamStbWajar_tolakReset2015() {
        assertFalse(TgBackup.jamStbWajar(1420070424000L));
        assertTrue(TgBackup.jamStbWajar(TgBackup.BATAS_JAM_WAJAR_MS));
        assertTrue(TgBackup.jamStbWajar(System.currentTimeMillis()));
    }

    @Test
    public void pesanJamStbSalah_sebutCaraBetulkan() {
        String pesan = TgBackup.pesanJamStbSalah(1420070424000L);
        assertTrue(pesan.contains("Tanggal & jam STB salah"));
        assertTrue(pesan.contains("otomatis"));
        assertTrue(pesan.contains("2015"));
    }

    @Test
    public void dbSibuk_bedakanKunciSesaatDariKorup() {
        assertTrue(TgBackup.dbSibuk("SIBUK: database is locked"));
        assertFalse(TgBackup.dbSibuk("no such table: main"));
        assertFalse(TgBackup.dbSibuk("file is not a database"));
        assertFalse(TgBackup.dbSibuk(null));
        assertFalse(TgBackup.dbSibuk(""));
    }
}
