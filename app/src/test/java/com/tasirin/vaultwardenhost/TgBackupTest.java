package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Unit test util murni (tanpa Android runtime). */
public class TgBackupTest {

    @Test
    public void cleanupPreTakMengusirBackupRutin() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("vwretensi").toFile();
        for (int i = 0; i < 10; i++) {
            java.io.File f = new java.io.File(dir, "backup-telegram-" + i + ".zip");
            f.createNewFile();
            f.setLastModified(100000L + i * 5000L);
        }
        for (int i = 0; i < 5; i++) {
            java.io.File f = new java.io.File(dir, "db-backup-1-pre" + i + "-pre.sqlite3");
            f.createNewFile();
            f.setLastModified(900000L + i * 5000L);
        }
        TgBackup.cleanupOldBackups(dir);
        int rutin = 0;
        int pre = 0;
        for (java.io.File f : dir.listFiles()) {
            if (f.getName().startsWith("backup-telegram-")) {
                rutin++;
            }
            if (TgBackup.isPreRestore(f.getName())) {
                pre++;
            }
        }
        assertEquals(10, rutin);
        assertEquals(TgBackup.KEEP_PRE_RESTORE, pre);
    }

    @Test
    public void cleanupOldBackups_nolAman() {
        TgBackup.cleanupOldBackups(null);
        TgBackup.cleanupOldBackups(new java.io.File("/jalan/tak/ada/vw-uji-nol"));
    }

    @Test
    public void filePathTelegram_tolakTraversal() {
        assertTrue(TgBackup.filePathTelegramAman("documents/backup-telegram-abc.zip"));
        assertTrue(TgBackup.filePathTelegramAman("f/1.zip"));
        assertFalse(TgBackup.filePathTelegramAman("../../etc/passwd"));
        assertFalse(TgBackup.filePathTelegramAman("/absolut.zip"));
        assertFalse(TgBackup.filePathTelegramAman("a\\b.zip"));
        assertFalse(TgBackup.filePathTelegramAman(""));
        assertFalse(TgBackup.filePathTelegramAman(null));
        assertFalse(TgBackup.filePathTelegramAman("documents/f.zip?x=1"));
        assertFalse(TgBackup.filePathTelegramAman("documents/f.zip#frag"));
        assertFalse(TgBackup.filePathTelegramAman("documents/f%20.zip"));
        assertFalse(TgBackup.filePathTelegramAman("documents/a:b.zip"));
        assertTrue(TgBackup.filePathTelegramAman("photos/file_2.jpg"));
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
    public void sanitasiNamaFile_takBelahEmoji() {
        StringBuilder sb = new StringBuilder("xxx").append("\uD83D\uDE00");
        for (int i = 0; i < 119; i++) {
            sb.append('y');
        }
        String r = TgBackup.sanitasiNamaFile(sb.toString());
        assertEquals(119, r.length());
        for (int i = 0; i < r.length(); i++) {
            assertFalse(Character.isLowSurrogate(r.charAt(i))
                    && (i == 0 || !Character.isHighSurrogate(r.charAt(i - 1))));
        }
    }

    @Test
    public void fileSizeDariRespons_bacaAngkaAman() {
        assertEquals(12345L, TgBackup.fileSizeDariRespons("{\"ok\":true,\"result\":{\"file_path\":\"d/f.zip\",\"file_size\":12345}}"));
        assertEquals(-1L, TgBackup.fileSizeDariRespons("{\"ok\":true}"));
        assertEquals(-1L, TgBackup.fileSizeDariRespons(null));
        // Spasi / baris baru di sekitar titik-dua wajib tetap terbaca.
        assertEquals(12345L, TgBackup.fileSizeDariRespons("{\"ok\" : true, \"result\" : {\"file_path\" : \"d/f.zip\", \"file_size\" : 12345}}"));
        assertEquals(99L, TgBackup.fileSizeDariRespons("{\"ok\":true,\"result\":{\"file_size\":99}}"));
        assertEquals(7L, TgBackup.fileSizeDariRespons("{\"file_size\"\n:\n7}"));
        assertEquals(-1L, TgBackup.fileSizeDariRespons("{\"ok\":true,\"result\":{\"file_size2\":99}}"));
        assertEquals(-1L, TgBackup.fileSizeDariRespons("{\"ok\":true,\"result\":{\"file_size\":}}"));
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

    /** Tulis backup terenkripsi gaya VWB1/VWB2/VWB3 manual (header +
     *  AES-GCM) untuk uji kompatibel mundur dan lintas perangkat. */
    private static void tulisTerenkripsiManual(java.io.File keluar, String magic,
            int iter, String pass, boolean sha256, byte[] data) throws Exception {
        java.security.SecureRandom acak = new java.security.SecureRandom();
        byte[] salt = new byte[16];
        acak.nextBytes(salt);
        byte[] iv = new byte[12];
        acak.nextBytes(iv);
        byte[] kunci;
        int it = iter > 0 ? iter : 100000;
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                pass.toCharArray(), salt, it, 256);
        try {
            kunci = javax.crypto.SecretKeyFactory.getInstance(
                    sha256 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
        if (iter > 0 && "VWB3".equals(magic)) {
            // Jalur STB: KDF manual wajib hasilkan kunci yang sama.
            byte[] manual = com.tasirin.vaultwardenhost.PinCrypto
                    .pbkdf2Manual(pass, salt, iter);
            org.junit.Assert.assertArrayEquals(kunci, manual);
            kunci = manual;
        }
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(
                kunci, "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(keluar)) {
            o.write(magic.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if ("VWB3".equals(magic)) {
                o.write(new byte[]{(byte) (it >>> 24), (byte) (it >>> 16),
                        (byte) (it >>> 8), (byte) it});
            }
            o.write(salt);
            o.write(iv);
            o.write(c.doFinal(data));
        }
    }

    @Test
    public void vwb3_iterasiRinganLintasPerangkat() throws Exception {
        // File gaya STB lama (VWB3 iterasi 30rb via KDF manual) wajib terbaca
        // di perangkat modern (factory) dan sebaliknya — iterasi ikut header.
        java.io.File enc = java.io.File.createTempFile("vw-v3ringan", ".enc");
        java.io.File pulih = new java.io.File(enc.getParentFile(),
                "vw-v3pulih-" + System.nanoTime() + ".bin");
        byte[] data = "backup dari stb lama".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        try {
            tulisTerenkripsiManual(enc, "VWB3", 30000, "katasandi", true, data);
            assertEquals(TgBackup.ENC_MAGIC_V3, TgBackup.magicEnkripsi(enc));
            assertTrue(TgBackup.isEncrypted(enc));
            TgBackup.decryptFile(enc, pulih, "katasandi");
            org.junit.Assert.assertArrayEquals(data,
                    java.nio.file.Files.readAllBytes(pulih.toPath()));
        } finally {
            enc.delete();
            pulih.delete();
        }
    }

    @Test
    public void vwb3_iterasiRaksasaDitolakFailClosed() throws Exception {
        // Header utak-atik beriterasi 1 jt wajib ditolak sebelum KDF membakar
        // CPU STB; tak ada plaintext parsial yang tersisa.
        java.io.File enc = java.io.File.createTempFile("vw-v3jahat", ".enc");
        java.io.File pulih = new java.io.File(enc.getParentFile(),
                "vw-v3jahatpulih-" + System.nanoTime() + ".bin");
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(enc)) {
            o.write("VWB3".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            o.write(new byte[]{0, 15, 66, 64});
            byte[] isi = new byte[28];
            new java.security.SecureRandom().nextBytes(isi);
            o.write(isi);
            o.write(new byte[32]);
        }
        try {
            TgBackup.decryptFile(enc, pulih, "katasandi");
            org.junit.Assert.fail("iterasi raksasa wajib ditolak");
        } catch (Exception e) {
        }
        assertFalse(pulih.exists());
    }

    @Test
    public void iterByte_rondtrip() {
        org.junit.Assert.assertEquals(30000,
                TgBackup.byteKeIter(TgBackup.iterKeByte(30000)));
        org.junit.Assert.assertEquals(100000,
                TgBackup.byteKeIter(TgBackup.iterKeByte(100000)));
        org.junit.Assert.assertEquals(-1, TgBackup.byteKeIter(null));
        org.junit.Assert.assertEquals(-1, TgBackup.byteKeIter(new byte[]{1, 2, 3}));
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
    public void magicBaru_dikenaliDanKompatibelLama() throws Exception {
        java.io.File asli = java.io.File.createTempFile("vw-magic", ".bin");
        java.io.File enc = java.io.File.createTempFile("vw-magic", ".enc");
        java.io.File pulih = new java.io.File(enc.getParentFile(), "vw-pulih-" + System.nanoTime() + ".bin");
        java.io.File lawas = new java.io.File(enc.getParentFile(), "vw-lawas-" + System.nanoTime() + ".enc");
        java.io.File pulihLawas = new java.io.File(enc.getParentFile(), "vw-pulihlawas-" + System.nanoTime() + ".bin");
        byte[] data = "uji magic enkripsi".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(asli)) {
                o.write(data);
            }
            TgBackup.encryptFile(asli, enc, "katasandi");
            assertEquals(TgBackup.ENC_MAGIC_V3, TgBackup.magicEnkripsi(enc));
            assertTrue(TgBackup.isEncrypted(enc));
            TgBackup.decryptFile(enc, pulih, "katasandi");
            org.junit.Assert.assertArrayEquals(data, java.nio.file.Files.readAllBytes(pulih.toPath()));
            // Kompatibel mundur: file VWB2 (SHA256 100rb, tanpa iterasi header)
            // dan VWB1 (SHA1 100rb) rakitan manual tetap terbaca.
            tulisTerenkripsiManual(lawas, "VWB2", 0, "katasandi", true, data);
            assertEquals("VWB2", TgBackup.magicEnkripsi(lawas));
            assertTrue(TgBackup.isEncrypted(lawas));
            TgBackup.decryptFile(lawas, pulihLawas, "katasandi");
            org.junit.Assert.assertArrayEquals(data, java.nio.file.Files.readAllBytes(pulihLawas.toPath()));
            java.io.File lawas1 = new java.io.File(enc.getParentFile(),
                    "vw-lawas1-" + System.nanoTime() + ".enc");
            java.io.File pulihLawas1 = new java.io.File(enc.getParentFile(),
                    "vw-pulihlawas1-" + System.nanoTime() + ".bin");
            try {
                tulisTerenkripsiManual(lawas1, "VWB1", 0, "katasandi", false, data);
                assertEquals("VWB1", TgBackup.magicEnkripsi(lawas1));
                TgBackup.decryptFile(lawas1, pulihLawas1, "katasandi");
                org.junit.Assert.assertArrayEquals(data,
                        java.nio.file.Files.readAllBytes(pulihLawas1.toPath()));
            } finally {
                lawas1.delete();
                pulihLawas1.delete();
            }
        } finally {
            asli.delete();
            enc.delete();
            pulih.delete();
            lawas.delete();
            pulihLawas.delete();
        }
    }

    @Test
    public void decryptFile_passwordSalahGagal() throws Exception {
        java.io.File asli = java.io.File.createTempFile("vw-salah", ".bin");
        java.io.File enc = java.io.File.createTempFile("vw-salah", ".enc");
        java.io.File pulih = new java.io.File(enc.getParentFile(), "vw-salah-pulih-" + System.nanoTime() + ".bin");
        try {
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(asli)) {
                o.write("data penting".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            TgBackup.encryptFile(asli, enc, "benar123");
            try {
                TgBackup.decryptFile(enc, pulih, "salah456");
                org.junit.Assert.fail("password salah wajib gagal");
            } catch (Exception e) {
            }
            assertFalse(pulih.exists());
        } finally {
            asli.delete();
            enc.delete();
            pulih.delete();
        }
    }

    @Test
    public void decryptFile_gagalPertahankanFileLama() throws Exception {
        java.io.File asli = java.io.File.createTempFile("vw-aman", ".bin");
        java.io.File enc = java.io.File.createTempFile("vw-aman", ".enc");
        java.io.File korban = new java.io.File(enc.getParentFile(), "vw-korban-" + System.nanoTime() + ".bin");
        byte[] lama = "file baik jangan dihapus".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(asli)) {
                o.write("data penting".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            TgBackup.encryptFile(asli, enc, "benar123");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(korban)) {
                o.write(lama);
            }
            try {
                TgBackup.decryptFile(enc, korban, "salah456");
                org.junit.Assert.fail("password salah wajib gagal");
            } catch (Exception e) {
            }
            org.junit.Assert.assertArrayEquals(lama, java.nio.file.Files.readAllBytes(korban.toPath()));
        } finally {
            asli.delete();
            enc.delete();
            korban.delete();
        }
    }

    @Test
    public void encryptFile_gagalPertahankanFileLama() throws Exception {
        java.io.File hilang = new java.io.File("/tidak/ada/masuk.bin");
        java.io.File korban = java.io.File.createTempFile("vw-amanenc", ".enc");
        byte[] lama = "enc baik jangan dihapus".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(korban)) {
                o.write(lama);
            }
            try {
                TgBackup.encryptFile(hilang, korban, "rahasia");
                org.junit.Assert.fail("masukan hilang wajib gagal");
            } catch (Exception e) {
            }
            org.junit.Assert.assertArrayEquals(lama, java.nio.file.Files.readAllBytes(korban.toPath()));
        } finally {
            korban.delete();
        }
    }

    @Test
    public void satuDesimal_carryDanRaksasa() {
        assertEquals("1.0 MB", TgBackup.satuDesimal(1048575, 1048576, "MB"));
        String raksasa = TgBackup.satuDesimal(Long.MAX_VALUE, 1024, "KB");
        assertTrue(raksasa.endsWith(" KB"));
        assertFalse(raksasa.startsWith("-"));
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
    public void ukuranIsiZip_menjumlahkanEntri() throws Exception {
        java.io.File zip = java.io.File.createTempFile("vw-isi-", ".zip");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
                zos.putNextEntry(new ZipEntry("db.sqlite3"));
                zos.write(new byte[1234]);
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("tls/"));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("tls/ca.pem"));
                zos.write(new byte[16]);
                zos.closeEntry();
            }
            assertEquals(1250L, TgBackup.ukuranIsiZip(zip));
            assertEquals(-1L, TgBackup.ukuranIsiZip(
                    new java.io.File(zip.getParentFile(), "tak-ada-uji.zip")));
        } finally {
            zip.delete();
        }
    }

    @Test
    public void pindaiZipRestore_sekaliJalanAmbilConfigDanUkuran() throws Exception {
        java.io.File zip = java.io.File.createTempFile("vw-pindai-", ".zip");
        try {
            String cfg = "{\"port\":\"8088\"}";
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
                zos.putNextEntry(new ZipEntry("vaultwarden/app-config.json"));
                zos.write(cfg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("vaultwarden/db.sqlite3"));
                zos.write(new byte[10]);
                zos.closeEntry();
            }
            TgBackup.PindaiZip p = TgBackup.pindaiZipRestore(zip);
            assertEquals(cfg, p.config);
            assertEquals(cfg.length() + 10L, p.ukuran);
        } finally {
            zip.delete();
        }
        TgBackup.PindaiZip hilang = TgBackup.pindaiZipRestore(
                new java.io.File(zip.getParentFile(), "tak-ada-pindai.zip"));
        assertNull(hilang.config);
        assertEquals(-1L, hilang.ukuran);
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
    public void tundaBilaBootBaru_lewatBilaUptimeLama() {
        assertFalse(TgBackup.tundaBilaBootBaruPada(null, "uji", 3600_000L));
    }

    @Test
    public void tundaBilaBootBaru_wrapperTakCrash() {
        boolean hasil;
        try {
            // Di JVM murni jam SystemClock stub (melempar) atau 0
            // (default-value): keduanya jatuh ke jalur tunda tanpa crash.
            // Di perangkat asli hasilnya ikut uptime (>5 mnt = false),
            // tapi uji ini hanya jalan di JVM host sehingga deterministik.
            hasil = TgBackup.tundaBilaBootBaru(null, "uji");
        } catch (RuntimeException e) {
            hasil = TgBackup.tundaBilaBootBaruPada(null, "uji", 0);
        }
        assertTrue(hasil);
    }

    @Test
    public void tundaBilaBootBaru_tundaBilaBootBaru() {
        // ctx null: jadwal alarm gagal (ditangkap di dalam) tapi status tunda
        // tetap true + tercatat di log; deterministik di JVM.
        assertTrue(TgBackup.tundaBilaBootBaruPada(null, "uji", 0));
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
    public void shaHexValid_hanya64Hex() {
        assertTrue(TgBackup.shaHexValid(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertTrue(TgBackup.shaHexValid(
                "  0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF  "));
        assertFalse(TgBackup.shaHexValid(null));
        assertFalse(TgBackup.shaHexValid(""));
        assertFalse(TgBackup.shaHexValid("abc"));
        assertFalse(TgBackup.shaHexValid(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde"));
        assertFalse(TgBackup.shaHexValid(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeg"));
    }

    @Test
    public void chatIdAman_tolakAneh() {
        assertTrue(TgBackup.chatIdAman("123456789"));
        assertTrue(TgBackup.chatIdAman("-1001234567890"));
        assertTrue(TgBackup.chatIdAman("@nama_pengguna"));
        assertTrue(TgBackup.chatIdAman("  123456789  "));
        assertFalse(TgBackup.chatIdAman(null));
        assertFalse(TgBackup.chatIdAman(""));
        assertFalse(TgBackup.chatIdAman("   "));
        assertFalse(TgBackup.chatIdAman("123 456"));
        assertFalse(TgBackup.chatIdAman("id\njahat"));
        assertFalse(TgBackup.chatIdAman("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
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
    public void sapuTmpPlainBasi_hanyaBasiDanBukanAktif() throws Exception {
        java.io.File dir = new java.io.File(
                System.getProperty("java.io.tmpdir"), "vwtg-dectest-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        try {
            java.io.File basi = new java.io.File(dir, "vwtg-dec-a.zip.tmp");
            java.io.File segar = new java.io.File(dir, "vwtg-dec-b.zip.tmp");
            java.io.File lain = new java.io.File(dir, "app-config-x.json");
            // File verifikasi plaintext (nama unik) juga wajib disapu saat basi.
            java.io.File basiIsi = new java.io.File(dir, "vwtg-plain-isi-db-a.sqlite3");
            java.io.File segarIsi = new java.io.File(dir, "vwtg-plain-roundtrip-b.zip");
            assertTrue(basi.createNewFile());
            assertTrue(segar.createNewFile());
            assertTrue(lain.createNewFile());
            assertTrue(basiIsi.createNewFile());
            assertTrue(segarIsi.createNewFile());
            long basiMs = System.currentTimeMillis() - TgBackup.UMUR_DEC_TMP_MS - 60_000L;
            assertTrue(basi.setLastModified(basiMs));
            assertTrue(basiIsi.setLastModified(basiMs));
            TgBackup.sapuTmpPlainBasi(dir, segar.getName(), segarIsi.getName());
            assertFalse(basi.exists());
            assertFalse(basiIsi.exists());
            assertTrue(segar.exists());
            assertTrue(segarIsi.exists());
            assertTrue(lain.exists());
            TgBackup.sapuTmpPlainBasi(dir, null);
            assertTrue(segar.exists());
            assertTrue(segarIsi.exists());
        } finally {
            for (java.io.File f : dir.listFiles()) {
                f.delete();
            }
            dir.delete();
        }
    }

    @Test
    public void berkasPlainSementaraUnikDanBerawalanVwtg() {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"));
        java.io.File a = TgBackup.berkasPlainSementara(dir, "isi-db", ".sqlite3");
        java.io.File b = TgBackup.berkasPlainSementara(dir, "isi-db", ".sqlite3");
        assertTrue(a.getName().startsWith(TgBackup.PREFIX_PLAIN_TMP));
        assertTrue(a.getName().endsWith(".sqlite3"));
        // Nama unik: dua backup (jadwal + bot) tak boleh berebut satu file.
        assertNotEquals(a.getName(), b.getName());
        // Awalan vwtg- otomatis dilarang dibagikan via FileShareProvider.
        assertTrue(com.tasirin.vaultwardenhost.FileShareProvider.berkasSementara(a.getName()));
    }

    @Test
    public void redaksiDetailGalatBuangTokenDanRapikanBaris() {
        // URL bot yang tercecer di body proxy/captive portal tak boleh masuk log
        // (log bisa diekspor / dikirim /log): token pola <angka>:<rahasia> diredaksi.
        assertEquals("<token>",
                TgBackup.redaksiDetailGalat("123456789:AAHfoo_bar-bazQUUX1234"));
        assertEquals("HTTP 400 {\"ok\":false}",
                TgBackup.redaksiDetailGalat("HTTP 400\n  {\"ok\":false} "));
        // Jam/waktu bukan token (kiri < 5 angka).
        assertEquals("12:34:56 mulai", TgBackup.redaksiDetailGalat("12:34:56 mulai"));
        assertEquals("", TgBackup.redaksiDetailGalat(null));
        // Panjang tetap dibatasi 200 + elipsis.
        String panjang = TgBackup.redaksiDetailGalat(new String(new char[300]).replace('\0', 'a'));
        assertEquals(203, panjang.length());
    }

    @Test
    public void jedaRetryAfterMsDibatasiSatuSampaiLimaDetik() {
        // Balasan 429 Telegram: pakai parameters.retry_after, tetapi antrean
        // pesan STB tak boleh tertahan lama -> dibatasi 1-5 dtk.
        assertEquals(3000L, TgBackup.jedaRetryAfterMs(
                "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":3}}"));
        assertEquals(1000L, TgBackup.jedaRetryAfterMs(
                "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":0}}"));
        assertEquals(5000L, TgBackup.jedaRetryAfterMs(
                "{\"ok\":false,\"parameters\":{\"retry_after\":120}}"));
        // Tak terbaca/None: default 1 dtk (tetap beri jeda sebelum ulang).
        assertEquals(1000L, TgBackup.jedaRetryAfterMs("{\"ok\":false}"));
        assertEquals(1000L, TgBackup.jedaRetryAfterMs(null));
    }

    @Test
    public void kunciIntLongTerdaftar() {
        assertTrue(TgBackup.KUNCI_INT.contains("pin_gagal"));
        assertFalse(TgBackup.KUNCI_INT.contains(TgBot.KEY_TG_MENU_HASH));
        assertTrue(TgBackup.KUNCI_STRING.contains(TgBot.KEY_TG_MENU_FP));
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
        assertTrue(TgBackup.stempelUnik().matches("\\d{8}-\\d{6}-\\d{3}-[0-9a-f]{12}"));
        assertFalse(TgBackup.stempelUnik().equals(TgBackup.stempelUnik())
                && TgBackup.stempelUnik().equals(TgBackup.stempelUnik()));
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
        assertTrue(TgBackup.SECRET_PREF_KEYS.contains(TgBackup.KEY_ALARM_SECRET));
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
    public void pesanGalatBackup_jamWajarPakaiIntiSekali() {
        // Jam dibaca sekali (cek vs pesan satu instant): hasil memakai inti
        // + saran koneksi, bukan cabang jam salah.
        String out = TgBackup.pesanGalatBackup(new java.io.IOException("jaringan putus"));
        assertTrue(out.startsWith("Backup gagal: jaringan putus"));
    }

    @Test
    public void pesanJamStbSalah_sebutCaraBetulkan() {
        String pesan = TgBackup.pesanJamStbSalah(1420070424000L);
        assertTrue(pesan.contains("Tanggal & jam STB salah"));
        assertTrue(pesan.contains("otomatis"));
        assertTrue(pesan.contains("2015"));
    }

    @Test
    public void pesanPenting_utamakanKabarKritis() {
        assertTrue(TgBackup.pesanPenting("Restore gagal: file rusak."));
        assertTrue(TgBackup.pesanPenting("PIN terkunci sementara."));
        assertTrue(TgBackup.pesanPenting("Server berhenti: gagal restart 5x."));
        assertFalse(TgBackup.pesanPenting("Server jalan."));
        assertFalse(TgBackup.pesanPenting("Backup harian terunggah."));
        assertFalse(TgBackup.pesanPenting(null));
        assertFalse(TgBackup.pesanPenting(""));
    }

    @Test
    public void copyFile_menyalinUtuhDenganPanjangSama() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("vwcopy").toFile();
        java.io.File asal = new java.io.File(dir, "asal.bin");
        java.io.File salin = new java.io.File(dir, "salin.bin");
        byte[] isi = new byte[70000];
        new java.util.Random(7).nextBytes(isi);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(asal);
        fos.write(isi);
        fos.close();
        TgBackup.copyFile(asal, salin);
        assertEquals(asal.length(), salin.length());
        assertEquals(isi.length, (int) salin.length());
    }

    @Test
    public void potretKembalikanTls_bulatkembali() throws Exception {
        java.io.File tls = java.nio.file.Files.createTempDirectory("vwtls").toFile();
        tulisTeks(new java.io.File(tls, "ca.pem"), "CA-LAMA");
        tulisTeks(new java.io.File(tls, "key.pem"), "KEY-LAMA");
        java.util.Map<String, byte[]> potret = TgBackup.potretTls(tls);
        // Simulasi ekstraksi zip: timpa yang ada + buat berkas baru.
        tulisTeks(new java.io.File(tls, "ca.pem"), "CA-ZIP");
        tulisTeks(new java.io.File(tls, "cert.pem"), "CERT-ZIP");
        TgBackup.kembalikanTls(tls, potret);
        assertEquals("CA-LAMA", bacaTeks(new java.io.File(tls, "ca.pem")));
        assertEquals("KEY-LAMA", bacaTeks(new java.io.File(tls, "key.pem")));
        assertFalse(new java.io.File(tls, "cert.pem").exists());
        // Potret null = no-op (jalur non-zip).
        TgBackup.kembalikanTls(tls, null);
        assertEquals("CA-LAMA", bacaTeks(new java.io.File(tls, "ca.pem")));
    }

    private static void tulisTeks(java.io.File f, String isi) throws Exception {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        try {
            fos.write(isi.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            fos.close();
        }
    }

    private static String bacaTeks(java.io.File f) throws Exception {
        byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    public void normalisasiEntriZip_tolakBungkusBersarang() {
        // Satu pembungkus tetap diterima (kompatibel zip manual).
        assertEquals("db.sqlite3",
                TgBackup.normalisasiEntriZip("vaultwarden/db.sqlite3"));
        assertEquals("tls/cert.pem",
                TgBackup.normalisasiEntriZip("vaultwarden/tls/cert.pem"));
        // Sarang dua tingkat ditolak walau ujungnya nama sah.
        assertEquals(null, TgBackup.normalisasiEntriZip("a/b/db.sqlite3"));
        assertEquals(null,
                TgBackup.normalisasiEntriZip("vaultwarden/sub/tls/cert.pem"));
        // Awalan "./" dikupas (jinak), "../" tetap ditolak.
        assertEquals("db.sqlite3", TgBackup.normalisasiEntriZip("./db.sqlite3"));
        assertEquals(null, TgBackup.normalisasiEntriZip("../db.sqlite3"));
    }

    @Test
    public void bolehUnduhUlangTelegram_batas20MB() {
        assertTrue(TgBackup.bolehUnduhUlangTelegram(-1));
        assertTrue(TgBackup.bolehUnduhUlangTelegram(0));
        assertTrue(TgBackup.bolehUnduhUlangTelegram(20L * 1024 * 1024));
        assertFalse(TgBackup.bolehUnduhUlangTelegram(20L * 1024 * 1024 + 1));
    }

    @Test
    public void kunciBackupDanRestoreSalingMengesampingkan() {
        // Satu kunci bersama: backup tak boleh jalan bersamaan dengan restore.
        assertTrue(TgBackup.kunciBackup());
        try {
            assertFalse(TgBackup.kunciRestore());
            assertFalse(TgBackup.kunciBackup());
        } finally {
            TgBackup.lepasBackup();
        }
        assertTrue(TgBackup.kunciRestore());
        try {
            assertFalse(TgBackup.kunciBackup());
        } finally {
            TgBackup.lepasRestore();
        }
    }

    @Test
    public void kunciRestoreSalingMengesampingkan() {
        assertTrue(TgBackup.kunciRestore());
        try {
            assertFalse(TgBackup.kunciRestore());
        } finally {
            TgBackup.lepasRestore();
        }
        assertTrue(TgBackup.kunciRestore());
        TgBackup.lepasRestore();
    }

    @Test
    public void pecahPesan_batasiPanjangDanJagaSurrogate() {
        java.util.List<String> satu = TgBackup.pecahPesan("halo", 4000);
        assertEquals(1, satu.size());
        assertEquals("halo", satu.get(0));
        StringBuilder panjang = new StringBuilder();
        for (int i = 0; i < 9000; i++) {
            panjang.append('a');
        }
        java.util.List<String> bagian = TgBackup.pecahPesan(
                panjang.toString(), TgBackup.BATAS_PESAN_TELEGRAM);
        assertEquals(3, bagian.size());
        assertEquals(4000, bagian.get(0).length());
        assertEquals(4000, bagian.get(1).length());
        assertEquals(1000, bagian.get(2).length());
        StringBuilder gabung = new StringBuilder();
        for (String b : bagian) {
            gabung.append(b);
        }
        assertEquals(panjang.toString(), gabung.toString());
        // Emoji di batas potong tak boleh terbelah (high surrogate ikut pindah).
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 3999; i++) {
            emoji.append('x');
        }
        emoji.append("\uD83D\uDE00rest");
        java.util.List<String> e = TgBackup.pecahPesan(emoji.toString(), 4000);
        assertEquals(2, e.size());
        assertEquals(3999, e.get(0).length());
        assertTrue(e.get(1).startsWith("\uD83D\uDE00"));
        assertEquals(1, TgBackup.pecahPesan("", 4000).size());
        assertEquals(1, TgBackup.pecahPesan(null, 4000).size());
    }

    @Test
    public void pecahPesan_utamakanNewlineDanGabungUtuh() {
        String msg = "baris1\nbaris2\nbaris3";
        java.util.List<String> b = TgBackup.pecahPesan(msg, 10);
        StringBuilder gabung = new StringBuilder();
        for (String x : b) {
            gabung.append(x);
            assertTrue(x.length() <= 10 + 16);
        }
        assertEquals(msg, gabung.toString());
        assertTrue(b.size() >= 2);
        assertTrue(TgBackup.ekorGrapheme('\u0301'));
        assertTrue(TgBackup.ekorGrapheme('\u200D'));
        assertFalse(TgBackup.ekorGrapheme('a'));
    }

    @Test
    public void bolehTulisRestore_hanyaDbDanTlsResmi() {
        assertTrue(TgBackup.bolehTulisRestore("db.sqlite3"));
        assertTrue(TgBackup.bolehTulisRestore("db.sqlite3-wal"));
        assertTrue(TgBackup.bolehTulisRestore("tls/cert.pem"));
        assertTrue(TgBackup.bolehTulisRestore("tls"));
        assertFalse(TgBackup.bolehTulisRestore("app-config.json"));
        assertFalse(TgBackup.bolehTulisRestore("db.sqlite3-evil"));
        assertFalse(TgBackup.bolehTulisRestore("tls/evil.sh"));
        assertFalse(TgBackup.bolehTulisRestore(null));
    }

    @Test
    public void backupOtomatisWajibPassword() {
        assertTrue(TgBackup.bolehBackupOtomatis("rahasia123"));
        assertTrue(TgBackup.bolehBackupOtomatis("  rahasia  "));
        assertFalse(TgBackup.bolehBackupOtomatis(""));
        assertFalse(TgBackup.bolehBackupOtomatis("   "));
        assertFalse(TgBackup.bolehBackupOtomatis(null));
        assertTrue(TgBackup.pesanTolakPlainOtomatis().contains("password"));
    }

    @Test
    public void semuaChatAmanTerimaDaftarMultiAkun() {
        assertTrue(TgBackup.semuaChatAman("123456789"));
        assertTrue(TgBackup.semuaChatAman("123,456"));
        assertTrue(TgBackup.semuaChatAman("123; @namabot"));
        assertFalse(TgBackup.semuaChatAman(""));
        assertFalse(TgBackup.semuaChatAman(null));
        assertFalse(TgBackup.semuaChatAman("123, abc"));
        assertFalse(TgBackup.semuaChatAman("1,2,3,4,5,6"));
        assertEquals(2, TgBackup.daftarChatTujuan("123, 456,123").size());
    }

    @Test
    public void kirimKeSatuChatAbaikanMasukanTakSah() {
        // Context null / chat kosong tak boleh melempar: antrean tak tersentuh.
        TgBackup.sendMessageKe(null, "123456789", "halo");
        TgBackup.sendMessageKbKe(null, "123456789", "halo", null);
        TgBackup.sendPentingKe(null, "", "halo");
        TgBackup.sendMessageKe(null, null, null);
    }

    @Test
    public void tujuanBalasanHanyaChatPengirim() {
        // Balasan perintah tak boleh menyiarkan ke semua chat config: satu
        // tujuan saja, yaitu chat pengirim (termasuk bila itu grup).
        assertEquals(java.util.Arrays.asList("123456789"),
                TgBackup.tujuanBalasan("123456789"));
        assertEquals(java.util.Arrays.asList("123456789"),
                TgBackup.tujuanBalasan("  123456789  "));
        assertEquals(java.util.Arrays.asList("-1001234567890"),
                TgBackup.tujuanBalasan("-1001234567890"));
        assertNull(TgBackup.tujuanBalasan("123, 456"));
        assertNull(TgBackup.tujuanBalasan(""));
        assertNull(TgBackup.tujuanBalasan(null));
    }

    @Test
    public void dbSibuk_bedakanKunciSesaatDariKorup() {
        assertTrue(TgBackup.dbSibuk("SIBUK: database is locked"));
        assertFalse(TgBackup.dbSibuk("no such table: main"));
        assertFalse(TgBackup.dbSibuk("file is not a database"));
        assertFalse(TgBackup.dbSibuk(null));
        assertFalse(TgBackup.dbSibuk(""));
    }
    @Test
    public void tulisAscii_byteIdentikWriteBytes() throws Exception {
        String[] sampel = {"--boundary\r\n", "Content-Type: application/octet-stream\r\n\r\n",
                "nama berkas abc-123_ Z\r\n", ""};
        for (String x : sampel) {
            java.io.ByteArrayOutputStream a = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            TgBackup.tulisAscii(a, x);
            java.io.DataOutputStream dos = new java.io.DataOutputStream(b);
            dos.writeBytes(x);
            dos.flush();
            assertEquals(new String(b.toByteArray(), "ISO-8859-1"),
                    new String(a.toByteArray(), "ISO-8859-1"));
        }
        TgBackup.tulisAscii(new java.io.ByteArrayOutputStream(), null);
    }
    @Test
    public void bangunParamPesan_tanpaKeyboardDanDenganKeyboard() {
        assertEquals("chat_id=123&text=halo",
                TgBackup.bangunParamPesan("123", "halo", null));
        assertEquals("chat_id=123&text=halo&reply_markup=KB",
                TgBackup.bangunParamPesan("123", "halo", "KB"));
        assertEquals("chat_id=&text=",
                TgBackup.bangunParamPesan(null, null, null));
    }

    @Test
    public void bangunParamEdit_sertakanMessageIdDanKeyboard() {
        assertEquals("chat_id=123&message_id=7&text=menu",
                TgBackup.bangunParamEdit("123", "7", "menu", null));
        assertEquals("chat_id=123&message_id=7&text=menu&reply_markup=KB",
                TgBackup.bangunParamEdit("123", "7", "menu", "KB"));
        assertEquals("chat_id=&message_id=&text=",
                TgBackup.bangunParamEdit(null, null, null, null));
    }

    @Test
    public void editMessageKb_abaikanMasukanTakSah() {
        // Context hilang (service dibunuh) atau ID tak sah: edit menu hanya
        // dilewati, bukan melempar keluar ke pemanggil bot.
        TgBackup.editMessageKb(null, 1, 1, "menu", null);
        TgBackup.editMessageKb(null, 0, 0, null, null);
    }

    @Test
    public void tugasDataJalan_mengikutiKunciRestore() {
        assertFalse(TgBackup.tugasDataJalan());
        assertTrue(TgBackup.kunciRestore());
        try {
            assertTrue(TgBackup.tugasDataJalan());
        } finally {
            TgBackup.lepasRestore();
        }
        assertFalse(TgBackup.tugasDataJalan());
    }

    @Test
    public void hapusWalShmBuangWalDanShmLama() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("vwwal").toFile();
        File wal = new File(dir, "db.sqlite3-wal");
        File shm = new File(dir, "db.sqlite3-shm");
        assertTrue(wal.createNewFile());
        assertTrue(shm.createNewFile());
        TgBackup.hapusWalShm(dir);
        assertFalse(wal.exists());
        assertFalse(shm.exists());
        TgBackup.hapusWalShm(null);
    }

    @Test
    public void namaStagingRestoreIkutPenamaanWalSqlite() {
        assertEquals("db.sqlite3.restore-tmp", TgBackup.namaStagingRestore("db.sqlite3"));
        String dbStaging = TgBackup.namaStagingRestore("db.sqlite3");
        // WAL/SHM staging wajib mengikuti turunan SQLite dari DB staging agar
        // checkpoint staging menemukan kompanionnya.
        assertEquals(dbStaging + "-wal", TgBackup.namaStagingRestore("db.sqlite3-wal"));
        assertEquals(dbStaging + "-shm", TgBackup.namaStagingRestore("db.sqlite3-shm"));
        assertNull(TgBackup.namaStagingRestore("tls/ca.pem"));
        assertNull(TgBackup.namaStagingRestore("db.sqlite3.bak"));
        assertNull(TgBackup.namaStagingRestore(null));
    }

    @Test
    public void hapusStagingRestoreBersihkanSemuaSisa() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("vwstage").toFile();
        String[] asal = {"db.sqlite3", "db.sqlite3-wal", "db.sqlite3-shm"};
        for (String nama : asal) {
            assertTrue(new File(dir, TgBackup.namaStagingRestore(nama)).createNewFile());
        }
        TgBackup.hapusStagingRestore(dir);
        for (String nama : asal) {
            assertFalse(new File(dir, TgBackup.namaStagingRestore(nama)).exists());
        }
        TgBackup.hapusStagingRestore(null);
    }

    @Test
    public void pasangDbRestoreAtomikTolakSampahTanpaSentuhDbLama() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("vwpassang").toFile();
        File db = new File(dir, "db.sqlite3");
        java.nio.file.Files.write(db.toPath(),
                "DB-LAMA".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File staging = new File(dir, TgBackup.namaStagingRestore("db.sqlite3"));
        java.nio.file.Files.write(staging.toPath(),
                "bukan sqlite".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("bukan SQLite", TgBackup.pasangDbRestoreAtomik(dir));
        assertEquals("DB-LAMA", new String(java.nio.file.Files.readAllBytes(db.toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
        // Tanpa staging: restore ditolak, DB lama tetap.
        assertTrue(staging.delete());
        assertEquals("tidak berisi db.sqlite3", TgBackup.pasangDbRestoreAtomik(dir));
        assertEquals("DB-LAMA", new String(java.nio.file.Files.readAllBytes(db.toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void pasangDbDariSalinanGantiDbDanBuangWalShm() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("vwsalin").toFile();
        File db = new File(dir, "db.sqlite3");
        java.nio.file.Files.write(db.toPath(),
                "DB-BARU".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(new File(dir, "db.sqlite3-wal").createNewFile());
        assertTrue(new File(dir, "db.sqlite3-shm").createNewFile());
        File salinan = new File(dir, "db-backup-pre.sqlite3");
        java.nio.file.Files.write(salinan.toPath(),
                "DB-LAMA".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        TgBackup.pasangDbDariSalinan(dir, salinan);
        assertEquals("DB-LAMA", new String(java.nio.file.Files.readAllBytes(db.toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(new File(dir, "db.sqlite3-wal").exists());
        assertFalse(new File(dir, "db.sqlite3-shm").exists());
        assertFalse(new File(dir, TgBackup.namaStagingRestore("db.sqlite3")).exists());
    }

    @Test
    public void pulihkanDbLamaPakaiSalinanAtauBuangDb() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("vwpulih").toFile();
        File db = new File(dir, "db.sqlite3");
        File salinan = new File(dir, "db-backup-pre.sqlite3");
        java.nio.file.Files.write(db.toPath(),
                "DB-BARU".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(salinan.toPath(),
                "DB-LAMA".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        TgBackup.pulihkanDbLama(dir, db, salinan);
        assertEquals("DB-LAMA", new String(java.nio.file.Files.readAllBytes(db.toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
        // Tanpa salinan (install baru): DB parsial dibuang, bukan dibiarkan.
        TgBackup.pulihkanDbLama(dir, db, null);
        assertFalse(db.exists());
    }

    @Test
    public void rahasiaAlarm_pakaiSesiProsesBilaTersimpanGagal() {
        // commit() gagal (storage penuh) membuat prefs kosong; sesi proses
        // wajib menang agar alarm yang sudah terjadwal tetap cocok.
        String sesi = "0123456789abcdef0123456789abcdef";
        String lain = "fedcba9876543210fedcba9876543210";
        assertEquals(sesi, TgBackup.pilihRahasiaAlarm(sesi, lain));
        assertEquals(lain, TgBackup.pilihRahasiaAlarm(null, lain));
        assertEquals(lain, TgBackup.pilihRahasiaAlarm("bukan-hex", lain));
        assertNull(TgBackup.pilihRahasiaAlarm(null, null));
        assertNull(TgBackup.pilihRahasiaAlarm("", "xyz"));
    }

    @Test
    public void folderCekRuangBackupIkutCacheSaatTerenkripsi() {
        // Zip plaintext dibangun di cache internal saat terenkripsi, hasil
        // akhirnya di folder data: kedua volume wajib diukur.
        assertEquals(java.util.Arrays.asList("/cache", "/data"),
                TgBackup.folderCekRuangBackup("/data", true, "/cache"));
        // Tanpa enkripsi zip langsung ke folder data; cache tak dipakai.
        assertEquals(java.util.Arrays.asList("/data"),
                TgBackup.folderCekRuangBackup("/data", false, "/cache"));
        // Cache sama dengan folder data (perangkat tanpa partisi terpisah):
        // jangan diukur dua kali.
        assertEquals(java.util.Arrays.asList("/data"),
                TgBackup.folderCekRuangBackup("/data", true, "/data"));
        assertTrue(TgBackup.folderCekRuangBackup(null, true, null).isEmpty());
        assertTrue(TgBackup.folderCekRuangBackup("  ", false, " ").isEmpty());
    }

    @Test
    public void ruangBackupDibutuhkanIkutUkuranDb() {
        long mb = 1024L * 1024L;
        // Lantai 10 MB: DB kosong/kecil tetap butuh ruang kerja minimum.
        assertEquals(10 * mb, TgBackup.ruangBackupDibutuhkan(0, false, false));
        assertEquals(10 * mb, TgBackup.ruangBackupDibutuhkan(2 * mb, false, false));
        // Folder data menerima hasil akhir zip/.enc ≈ isi DB + margin 5 MB.
        assertEquals(105 * mb, TgBackup.ruangBackupDibutuhkan(100 * mb, false, false));
        assertEquals(105 * mb, TgBackup.ruangBackupDibutuhkan(100 * mb, false, true));
        // Cache terenkripsi: zip plaintext + salinan verifikasi = 2x isi DB.
        assertEquals(205 * mb, TgBackup.ruangBackupDibutuhkan(100 * mb, true, true));
        assertEquals(105 * mb, TgBackup.ruangBackupDibutuhkan(100 * mb, true, false));
        // Panjang negatif (berkas tak terbaca) diperlakukan nol.
        assertEquals(10 * mb, TgBackup.ruangBackupDibutuhkan(-5, true, true));
    }

    @Test
    public void panjangBerkasAmanUntukBerkasHilang() throws Exception {
        assertEquals(0L, TgBackup.panjangBerkas(null));
        assertEquals(0L, TgBackup.panjangBerkas(new File("/tidak/ada/berkas-vw")));
        File f = java.nio.file.Files.createTempFile("vwpanjang", ".bin").toFile();
        try {
            java.nio.file.Files.write(f.toPath(), new byte[]{1, 2, 3, 4, 5});
            assertEquals(5L, TgBackup.panjangBerkas(f));
        } finally {
            f.delete();
        }
    }

    @Test
    public void cekIntegritasDbUlangTakMelemparTanpaSqlite() throws Exception {
        File f = java.nio.file.Files.createTempFile("vwcek", ".sqlite3").toFile();
        java.nio.file.Files.write(f.toPath(), "bukan-db".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        String hasil = TgBackup.cekIntegritasDbUlang(f);
        // JVM tanpa SQLite Android: galat dikembalikan sebagai pesan (bukan
        // dilempar) dan tak boleh berlabel SIBUK agar UI tak menyuruh coba lagi.
        assertNotNull(hasil);
        assertFalse(TgBackup.dbSibuk(hasil));
    }
}


