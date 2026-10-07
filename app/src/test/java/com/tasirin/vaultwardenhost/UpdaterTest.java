package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test fungsi murni — tanpa Android runtime (jalan di CI via JVM). */
public class UpdaterTest {

    @Test
    public void fallbackBaruSaja_hematUnduhUlang() {
        long at = 1000000L;
        assertTrue(Updater.fallbackBaruSaja("1.37.3", true, "1.37.3",
                at, at + 3600_000L));
        assertFalse(Updater.fallbackBaruSaja("1.37.4", true, "1.37.3",
                at, at + 3600_000L));
        assertFalse(Updater.fallbackBaruSaja("1.37.3", false, "1.37.3",
                at, at + 3600_000L));
        assertFalse(Updater.fallbackBaruSaja("1.37.3", true, "1.37.3",
                at, at + 6L * 3600 * 1000 + 1));
        assertFalse(Updater.fallbackBaruSaja("1.37.3", true, "1.37.3",
                0, at + 1000));
        assertFalse(Updater.fallbackBaruSaja(null, true, "1.37.3",
                at, at + 1000));
    }

    @Test
    public void hostBerubah_deteksiLintasHost() {
        assertFalse(Updater.hostBerubah("https://github.com/a/b", "https://github.com/c/d"));
        assertFalse(Updater.hostBerubah("https://github.com:443/a", "https://github.com/b"));
        assertTrue(Updater.hostBerubah("https://github.com/a", "https://objects.githubusercontent.com/b"));
        assertTrue(Updater.hostBerubah("https://github.com/a", "http://github.com/a"));
        assertTrue(Updater.hostBerubah("https://github.com/a", "bukan-url"));
    }

    @Test
    public void tautanSimbol_bedakanLinkDanBiasa() throws Exception {
        java.io.File dir = new java.io.File(
                System.getProperty("java.io.tmpdir"),
                "vw-link-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        java.io.File biasa = new java.io.File(dir, "biasa.txt");
        assertTrue(biasa.createNewFile());
        assertFalse(Updater.tautanSimbol(biasa));
        try {
            java.nio.file.Path link = new java.io.File(dir, "taut").toPath();
            java.nio.file.Files.createSymbolicLink(link, biasa.toPath());
            assertTrue(Updater.tautanSimbol(link.toFile()));
            link.toFile().delete();
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // FS tanpa symlink: lewati bagian link, file biasa tetap teruji.
        }
        biasa.delete();
        dir.delete();
    }

    @Test
    public void kunciUnduh_samaUntukPathSama() throws Exception {
        java.io.File a = new java.io.File("/tmp/vw-bin-test");
        assertTrue(Updater.kunciUnduh(a) == Updater.kunciUnduh(a));
    }

    @Test
    public void gantiAtomik_tolakPathNull() {
        try {
            ServerService.gantiAtomik(null, new java.io.File("/tmp/x"));
            org.junit.Assert.fail("wajib lempar bila tmp null");
        } catch (java.io.IOException diharapkan) {
        }
        try {
            ServerService.gantiAtomik(new java.io.File("/tmp/x"), null);
            org.junit.Assert.fail("wajib lempar bila out null");
        } catch (java.io.IOException diharapkan) {
        }
    }

    @Test
    public void gantiAtomik_pulihkanLamaBilaGagal() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("vwatom").toFile();
        java.io.File out = new java.io.File(dir, "bin");
        java.io.File tmp = new java.io.File(dir, "bin.tmp");
        java.nio.file.Files.write(out.toPath(), "lama".getBytes("UTF-8"));
        java.nio.file.Files.write(tmp.toPath(), "baru".getBytes("UTF-8"));
        ServerService.gantiAtomik(tmp, out);
        assertEquals("baru", new String(java.nio.file.Files.readAllBytes(out.toPath()), "UTF-8"));
    }

    @Test
    public void normVersion_hapusHurufV() {
        assertEquals("1.37.1", Updater.normVersion("v1.37.1"));
        assertEquals("1.37.1", Updater.normVersion("1.37.1"));
        assertEquals("", Updater.normVersion("v"));
    }

    @Test
    public void normVersion_nullTetapNull() {
        assertNull(Updater.normVersion(null));
    }

    @Test
    public void normVersion_kupasSpasiDanHurufV() {
        assertEquals("1.32.0", Updater.normVersion(" v1.32.0 "));
        assertEquals("1.32.0", Updater.normVersion("  1.32.0"));
        assertEquals("1.32.0", Updater.normVersion("V1.32.0"));
        assertEquals("1.32.0", Updater.normVersion("v1.32.0+build.5"));
    }

    @Test
    public void versiCocok_toleranSpasiDanHurufV() {
        assertTrue(Updater.versiCocok(" v1.32.0 ", "1.32.0"));
        assertTrue(Updater.versiCocok("v1.32.0", "1.32.0"));
        assertTrue(Updater.versiCocok("1.32.0+build.5", "v1.32.0"));
    }

    @Test
    public void fallbackBaruSaja_toleranHurufV() {
        long kini = 1000000L;
        assertTrue(Updater.fallbackBaruSaja("1.32.0", true, "v1.32.0",
                kini - 1000, kini));
    }

    @Test
    public void extractTag_ambilTagNameDariJson() {
        String body = "{\"tag_name\":\"v1.37.1\",\"name\":\"1.37.1\"}";
        assertEquals("v1.37.1", Updater.extractTag(body));
    }

    @Test
    public void persenUnduhanAmbilAngkaPersen() {
        assertEquals(34, Updater.persenUnduhan("Unduh binary 12 MB/35 MB (34%)"));
        assertEquals(100, Updater.persenUnduhan("Unduh web-vault 35 MB/35 MB (100%)"));
        assertEquals(0, Updater.persenUnduhan("Unduh x (0%)"));
        assertEquals(-1, Updater.persenUnduhan("Unduh binary 12 MB..."));
        assertEquals(-1, Updater.persenUnduhan("Bekerja\u2026"));
        assertEquals(-1, Updater.persenUnduhan(""));
        assertEquals(-1, Updater.persenUnduhan(null));
        assertEquals(-1, Updater.persenUnduhan("Unduh x (120%)"));
    }

    @Test
    public void parseBinaryVersion_ambilXyzDariOutputVersion() {
        assertEquals("1.37.3", Updater.parseBinaryVersion("vaultwarden 1.37.3"));
        assertEquals("1.37.3", Updater.parseBinaryVersion("1.37.3"));
        assertEquals("1.37.3", Updater.parseBinaryVersion("vaultwarden 1.37.3 (abc123)"));
        assertNull(Updater.parseBinaryVersion("?"));
        assertNull(Updater.parseBinaryVersion(""));
        assertNull(Updater.parseBinaryVersion(null));
    }

    @Test
    public void parseBinaryVersion_noiseLinkerBukanVersi() {
        assertNull(Updater.parseBinaryVersion(
                "WARNING: linker: vaultwarden-armeabi-v7a: unsupported flags DT_FLAGS_1=0x8000001"));
    }

    @Test
    public void saranKoneksi_timeoutMenyebutGithubDanHotspot() {
        Exception e = new java.net.SocketTimeoutException(
                "failed to connect to github.com/20.205.243.166 (port 443) after 20000ms");
        String saran = Updater.saranKoneksi(e);
        assertTrue(saran.contains("GitHub"));
        assertTrue(saran.contains("hotspot"));
    }

    @Test
    public void saranKoneksi_dnsGagalMenyebutDns() {
        Exception e = new java.net.UnknownHostException("Unable to resolve host");
        assertTrue(Updater.saranKoneksi(e).contains("DNS"));
    }

    @Test
    public void saranKoneksi_nullTetapAdaSaran() {
        assertTrue(Updater.saranKoneksi(null).contains("github.com"));
    }

    @Test
    public void pesanGalatUnduh_memuatAksiDanSaran() {
        Exception e = new java.net.SocketTimeoutException("failed to connect");
        String pesan = Updater.pesanGalatUnduh("Unduh binary", e);
        assertTrue(pesan.contains("Unduh binary gagal"));
        assertTrue(pesan.contains("hotspot"));
    }

    @Test
    public void pesanGalatUnduh_webVaultMemuatAksiDanSaran() {
        Exception e = new java.net.SocketTimeoutException(
                "failed to connect to github.com/20.205.243.166 (port 443) after 20000ms");
        String pesan = Updater.pesanGalatUnduh("Unduh web-vault", e);
        assertTrue(pesan.contains("Unduh web-vault gagal"));
        assertTrue(pesan.contains("GitHub"));
    }

    @Test
    public void expectedHexEquals_cocokAbaikanCaseDanSpasi() {
        String hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertTrue(Updater.expectedHexEquals(hex, hex));
        assertTrue(Updater.expectedHexEquals(hex, hex.toUpperCase(java.util.Locale.US)));
        assertTrue(Updater.expectedHexEquals("  " + hex + "  ", hex));
    }

    @Test
    public void expectedHexEquals_bedaAtauNullTidakCocok() {
        String hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String beda = "ff23456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertTrue(!Updater.expectedHexEquals(hex, beda));
        assertTrue(!Updater.expectedHexEquals(hex, null));
        assertTrue(!Updater.expectedHexEquals(null, hex));
        assertTrue(!Updater.expectedHexEquals(null, null));
    }

    @Test
    public void saranKoneksi_dnsHijackKeIpLokalMenyebutPortal() {
        Exception e = new java.net.ConnectException(
                "failed to connect to github.com/192.168.100.1 (port 443) after 20000ms:"
                        + " isConnected failed: ECONNREFUSED (Connection refused)");
        String saran = Updater.saranKoneksi(e);
        assertTrue(saran.contains("IP lokal"));
        assertTrue(saran.contains("hotspot"));
    }

    @Test
    public void isDnsHijackKeIpLokal_mendeteksiIpPrivatSetelahSlash() {
        assertTrue(Updater.isDnsHijackKeIpLokal(
                "failed to connect to github.com/192.168.100.1 (port 443)".toLowerCase(
                        java.util.Locale.US)));
        assertTrue(Updater.isDnsHijackKeIpLokal(
                "failed to connect to github.com/10.0.0.1 (port 443)".toLowerCase(
                        java.util.Locale.US)));
        assertTrue(!Updater.isDnsHijackKeIpLokal(
                "failed to connect to github.com/20.205.243.166 (port 443)".toLowerCase(
                        java.util.Locale.US)));
        assertTrue(Updater.isDnsHijackKeIpLokal(
                "failed to connect to github.com/127.0.0.1 (port 443)".toLowerCase(
                        java.util.Locale.US)));
        assertTrue(Updater.isDnsHijackKeIpLokal(
                "failed to connect to github.com/100.64.0.1 (port 443)".toLowerCase(
                        java.util.Locale.US)));
        assertTrue(!Updater.isDnsHijackKeIpLokal(null));
    }

    private static java.net.HttpURLConnection koneksiPalsu(final String contentRange)
            throws Exception {
        return new java.net.HttpURLConnection(
                new java.net.URL("https://contoh.invalid/f")) {
            @Override
            public void connect() {
            }

            @Override
            public void disconnect() {
            }

            @Override
            public boolean usingProxy() {
                return false;
            }

            @Override
            public String getHeaderField(String name) {
                return "Content-Range".equalsIgnoreCase(name) ? contentRange : null;
            }
        };
    }

    @Test
    public void rangeCocok_hanyaTerimaAwalSesuaiParsial() throws Exception {
        assertTrue(Updater.rangeCocok(koneksiPalsu("bytes 1024-2047/4096"), 1024));
        assertFalse(Updater.rangeCocok(koneksiPalsu("bytes 0-1023/4096"), 1024));
        assertFalse(Updater.rangeCocok(koneksiPalsu(null), 1024));
        assertFalse(Updater.rangeCocok(koneksiPalsu("bytes */4096"), 1024));
        assertFalse(Updater.rangeCocok(koneksiPalsu("items 1024-2047/4096"), 1024));
        assertTrue(Updater.rangeCocok(koneksiPalsu(null), 0));
    }

    private static java.io.File berkasElf(byte em0, byte em1, int total) throws Exception {
        java.io.File f = java.io.File.createTempFile("elfuji", ".bin");
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
            byte[] h = new byte[total];
            h[0] = 0x7F;
            h[1] = 'E';
            h[2] = 'L';
            h[3] = 'F';
            h[4] = 1;
            h[5] = 1;
            // e_type ET_DYN=3 di 16-17: guard agar implementasi tak tertukar
            // membaca e_type (3 != 40 sehingga binary valid akan ditolak).
            if (total > 16) {
                h[16] = 3;
            }
            if (total > 17) {
                h[17] = 0;
            }
            if (total >= 20) {
                h[18] = em0;
                h[19] = em1;
            }
            o.write(h);
        }
        return f;
    }

    @Test
    public void isElf_hanyaArm32Bit() throws Exception {
        java.io.File arm = berkasElf((byte) 40, (byte) 0, 20);
        java.io.File x86 = berkasElf((byte) 62, (byte) 0, 20);
        java.io.File pendek = berkasElf((byte) 40, (byte) 0, 10);
        java.io.File acak = java.io.File.createTempFile("bukanelf", ".bin");
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(acak)) {
            o.write("MZpaijo".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        try {
            assertTrue(Updater.isElf(arm));
            assertFalse(Updater.isElf(x86));
            assertFalse(Updater.isElf(pendek));
            assertFalse(Updater.isElf(acak));
            assertFalse(Updater.isElf(new java.io.File("/tidak/ada/elf.bin")));
        } finally {
            arm.delete();
            x86.delete();
            pendek.delete();
            acak.delete();
        }
    }

    @Test
    public void gagalBaruSaja_batasiHantamanApi() {
        assertFalse(Updater.gagalBaruSaja(1000, 0, 60_000));
        assertTrue(Updater.gagalBaruSaja(1000, 1000, 60_000));
        assertTrue(Updater.gagalBaruSaja(1000 + 59_999, 1000, 60_000));
        assertFalse(Updater.gagalBaruSaja(1000 + 60_000, 1000, 60_000));
        assertFalse(Updater.gagalBaruSaja(500, 1000, 60_000));
    }

    @Test
    public void perluResetResume_416Atau200DenganParsialMintaUlangDariNol() {
        assertTrue(Updater.perluResetResume(416, 1024));
        assertTrue(Updater.perluResetResume(200, 1024));
        assertTrue(!Updater.perluResetResume(206, 1024));
        assertTrue(!Updater.perluResetResume(200, 0));
        assertTrue(!Updater.perluResetResume(416, 0));
        assertTrue(!Updater.perluResetResume(500, 1024));
    }

    @Test
    public void pesanGalatUnduh_zipKorupMenyebutFileDihapusDanAmanDiulang() {
        Exception e = new java.util.zip.ZipException("invalid stored block lengths");
        String pesan = Updater.pesanGalatUnduh("Unduh web-vault", e);
        assertTrue(pesan.contains("zip korup"));
        assertTrue(pesan.contains("aman diulang"));
    }

    @Test
    public void assetUrl_memakaiSlashAntaraVersiDanNama() {
        String bin = Updater.binaryAssetUrl("1.37.3", "armeabi-v7a");
        assertEquals("https://github.com/tasirin1/tasirin-vaultwarden-host"
                + "/releases/download/v1.37.3/vaultwarden-armeabi-v7a", bin);
        String shim = Updater.shimAssetUrl("1.37.3");
        assertEquals("https://github.com/tasirin1/tasirin-vaultwarden-host"
                + "/releases/download/v1.37.3/" + KernelCompat.SHIM_ASSET, shim);
    }

    @Test
    public void assetUrl_awalanVTakGanda() {
        String bin = Updater.binaryAssetUrl("v1.37.3", "armeabi-v7a");
        assertTrue(bin.endsWith("/v1.37.3/vaultwarden-armeabi-v7a"));
        assertTrue(!bin.contains("vv"));
        String shim = Updater.shimAssetUrl("V1.37.3");
        assertTrue(!shim.contains("vv") && !shim.contains("vV"));
        assertTrue(Updater.binaryAssetUrl("   ", "armeabi-v7a").contains("/latest/download/"));
        assertTrue(Updater.shimAssetUrl(" v ").contains("/latest/download/"));
    }

    @Test
    public void assetUrl_tanpaVersiPakaiLatestDownload() {
        assertTrue(Updater.binaryAssetUrl(null, "armeabi-v7a").contains("/latest/download/"));
        assertTrue(Updater.shimAssetUrl("").contains("/latest/download/"));
    }

    @Test
    public void saranKoneksi_belumTersediaMenyebutRilis() {
        Exception e = new java.io.IOException(
                "Shim getrandom belum tersedia di rilis v1.37.3 (build CI ~6 jam). Coba lagi nanti.");
        assertTrue(Updater.saranKoneksi(e).contains("belum tersedia"));
    }

    @Test
    public void extractTag_abaikanLiteralDiDalamBody() {
        String body = "{\"body\":\"tolong abaikan \\\"tag_name\\\":\\\"v0.0.0\\\" sampah\","
                + "\"tag_name\":\"v1.37.1\"}";
        assertEquals("v1.37.1", Updater.extractTag(body));
    }

    @Test
    public void reportDownload_jepitPersenNolSampaiSeratus() {
        Updater.reportDownload("binary", 104, 100);
        assertEquals(100, Updater.persenUnduhan(Updater.downloadStatus));
        Updater.reportDownload("binary", -5, 100);
        assertEquals(0, Updater.persenUnduhan(Updater.downloadStatus));
        Updater.reportDownload("binary", 34, 100);
        assertEquals(34, Updater.persenUnduhan(Updater.downloadStatus));
    }

    @Test
    public void extractTag_tanpaTagNameMengembalikanNull() {
        assertNull(Updater.extractTag("{\"name\":\"x\"}"));
        assertNull(Updater.extractTag(""));
        assertNull(Updater.extractTag(null));
    }

    @Test
    public void shimValid_terimaShimRilis2712Byte() throws Exception {
        // Regresi: shim rilis v1.37.3 hanya 2712 byte (stripped) sehingga
        // batas minimum lama 4096 byte selalu menolaknya sebagai tidak valid.
        assertTrue(KernelCompat.SHIM_MIN_BYTES <= 2712);
        java.io.File elf = buatBerkasElf(2712);
        try {
            assertTrue(Updater.shimValid(elf));
        } finally {
            elf.delete();
        }
    }

    @Test
    public void shimValid_tolakBukanElfDanTerlaluKecil() throws Exception {
        java.io.File bukanElf = buatBerkasBukanElf(2712);
        try {
            assertFalse(Updater.shimValid(bukanElf));
        } finally {
            bukanElf.delete();
        }
        java.io.File kecil = buatBerkasElf(512);
        try {
            assertFalse(Updater.shimValid(kecil));
        } finally {
            kecil.delete();
        }
    }

    @Test
    public void amanEntriZip_tolakTraversalTanpaCanonical() {
        assertTrue(Updater.amanEntriZip("index.html"));
        assertTrue(Updater.amanEntriZip("js/app.js"));
        assertFalse(Updater.amanEntriZip("../evil.sh"));
        assertFalse(Updater.amanEntriZip("js/../../evil.sh"));
        assertFalse(Updater.amanEntriZip("/etc/passwd"));
        assertFalse(Updater.amanEntriZip("C:evil"));
        assertFalse(Updater.amanEntriZip(""));
        assertFalse(Updater.amanEntriZip(null));
        // Tolak entri "." dan "./" (current dir)
        assertFalse(Updater.amanEntriZip("."));
        assertFalse(Updater.amanEntriZip("./file.txt"));
        assertFalse(Updater.amanEntriZip("dir/./file.txt"));
        assertFalse(Updater.amanEntriZip("js/app\u0000.js"));
        assertFalse(Updater.amanEntriZip("js/app\u001F.js"));
    }

    @Test
    public void bolehCobaLagiUnduh_parsialDiulangDariNol() {
        assertTrue(Updater.bolehCobaLagiUnduh(
                new java.io.IOException("File parsial rusak (kurang 10 byte) - unduh ulang dari nol.")));
    }

    public void bolehCobaLagiUnduh_hanyaGalatJaringan() {
        assertTrue(Updater.bolehCobaLagiUnduh(
                new java.io.IOException("failed to connect to github.com")));
        assertTrue(Updater.bolehCobaLagiUnduh(
                new java.net.SocketTimeoutException("timed out")));
        assertTrue(Updater.bolehCobaLagiUnduh(
                new java.io.IOException("Unduhan gagal (HTTP 500).")));
        assertFalse(Updater.bolehCobaLagiUnduh(
                new java.io.IOException("Unduhan gagal (HTTP 404).")));
        assertFalse(Updater.bolehCobaLagiUnduh(
                new java.io.IOException("Build Android v1.2 belum tersedia.")));
        assertFalse(Updater.bolehCobaLagiUnduh(
                new java.io.IOException((String) null)));
    }

    @Test
    public void panjangKonten_bacaHeaderLong() throws Exception {
        java.net.HttpURLConnection c = new java.net.HttpURLConnection(
                new java.net.URL("http://127.0.0.1/")) {
            @Override public void connect() { }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
            @Override public String getHeaderField(String n) {
                return "Content-Length".equalsIgnoreCase(n) ? "3221225472" : null;
            }
        };
        assertEquals(3221225472L, Updater.panjangKonten(c));
    }

    @Test
    public void panjangKonten_tanpaHeaderPakaiInt() throws Exception {
        java.net.HttpURLConnection c = new java.net.HttpURLConnection(
                new java.net.URL("http://127.0.0.1/")) {
            @Override public void connect() { }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
        };
        assertEquals(-1, c.getContentLength());
        assertEquals(-1L, Updater.panjangKonten(c));
    }

    private static java.io.File buatBerkasElf(int ukuran) throws Exception {
        java.io.File f = java.io.File.createTempFile("shim", ".so");
        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
        try {
            // Header ELF ARM 32-bit valid (magic + EI_DATA little-endian +
            // e_type ET_DYN=3 + e_machine EM_ARM=40) agar isElf() menerimanya.
            byte[] kepala = new byte[ukuran];
            kepala[0] = 0x7F;
            kepala[1] = (byte) 'E';
            kepala[2] = (byte) 'L';
            kepala[3] = (byte) 'F';
            if (ukuran > 5) {
                kepala[5] = 1;
            }
            if (ukuran > 16) {
                kepala[16] = 3;
            }
            if (ukuran > 18) {
                kepala[18] = 40;
            }
            o.write(kepala);
        } finally {
            o.close();
        }
        return f;
    }

    private static java.io.File buatBerkasBukanElf(int ukuran) throws Exception {
        java.io.File f = java.io.File.createTempFile("shim", ".so");
        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
        try {
            o.write("<html>bukan elf</html>".getBytes("UTF-8"));
            for (int i = 24; i < ukuran; i++) {
                o.write(0);
            }
        } finally {
            o.close();
        }
        return f;
    }

    @Test
    public void bandingVersi_terurutNumerik() {
        assertTrue(Updater.bandingVersi("1.37.3", "1.37.3") == 0);
        assertTrue(Updater.bandingVersi("1.37", "1.37.0") == 0);
        assertTrue(Updater.bandingVersi("1.9", "1.10") < 0);
        assertTrue(Updater.bandingVersi("1.38", "1.37.9") > 0);
        assertTrue(Updater.bandingVersi("v1.37.1", "1.37.1") == 0);
        assertTrue(Updater.bandingVersi("2.0", "1.99.99") > 0);
    }

    @Test
    public void angkaAwalan_tahanSufiks() {
        assertEquals(37, Updater.angkaAwalan("37"));
        assertEquals(37, Updater.angkaAwalan("37-beta"));
        assertEquals(3, Updater.angkaAwalan(" 3a "));
        assertEquals(-1, Updater.angkaAwalan("beta"));
        assertEquals(-1, Updater.angkaAwalan(""));
        assertEquals(-1, Updater.angkaAwalan(null));
    }

    @Test
    public void versiCocok_toleranFormat() {
        assertTrue(Updater.versiCocok("1.37.3", "1.37.3"));
        assertTrue(Updater.versiCocok("v1.37.3", "1.37.3"));
        assertTrue(Updater.versiCocok("1.37.3-beta", "1.37.3"));
        assertTrue(Updater.versiCocok("1.37", "1.37.0"));
        assertFalse(Updater.versiCocok("1.37.3", "1.37.4"));
        assertFalse(Updater.versiCocok(null, "1.37.3"));
        assertFalse(Updater.versiCocok("1.37.3", null));
        assertFalse(Updater.versiCocok("", "1.37.3"));
    }

    @Test
    public void bandingVersi_sufiksDiabaikan() {
        assertTrue(Updater.bandingVersi("1.37.3-beta", "1.37.3") == 0);
        assertTrue(Updater.bandingVersi("1.37.3", "1.37.3-beta") == 0);
    }

    @Test
    public void bandingVersi_takDikenalDianggapTertua() {
        assertTrue(Updater.bandingVersi(null, "1.37.1") < 0);
        assertTrue(Updater.bandingVersi("", "1.37.1") < 0);
        assertTrue(Updater.bandingVersi("rusak", "1.37.1") < 0);
    }

    @Test
    public void binaryBerubah_markerDuluFallbackLama() {
        assertTrue(Updater.binaryBerubah("Update v1.37.1 terpasang. [bin-updated]"));
        assertTrue(Updater.binaryBerubah("Update v1.37.1 terpasang."));
        assertFalse(Updater.binaryBerubah("Sudah versi terbaru: v1.37.1"));
        assertFalse(Updater.binaryBerubah("Binary rilis terbaru terpasang (v1.0 belum tersedia di repo)."));
        assertFalse(Updater.binaryBerubah(null));
        assertFalse(Updater.binaryBerubah(""));
    }

    @Test
    public void galatJamSertifikat_kenalNotValidUntilDiCause() {
        Exception inti = new javax.security.cert.CertificateException(
                "Could not validate certificate: Certificate not valid until"
                        + " Tue Nov 11 22:14:09 GMT+07:00 2025"
                        + " (compared to Thu Jan 01 07:00:24 GMT+07:00 2015)");
        Exception bungkus = new javax.net.ssl.SSLHandshakeException("Handshake failed");
        bungkus.initCause(inti);
        assertTrue(Updater.galatJamSertifikat(bungkus));
    }

    @Test
    public void galatJamSertifikat_tolakGalatLain() {
        assertFalse(Updater.galatJamSertifikat(
                new java.net.SocketTimeoutException("failed to connect")));
        assertFalse(Updater.galatJamSertifikat(null));
    }

    @Test
    public void saranKoneksi_jamSalahSebutTanggalOtomatis() {
        Exception e = new javax.net.ssl.SSLHandshakeException(
                "Certificate not valid until Tue Nov 11 2025 (compared to Thu Jan 01 2015)");
        String saran = Updater.saranKoneksi(e);
        assertTrue(saran.contains("Tanggal & jam STB"));
        assertTrue(saran.contains("otomatis"));
    }

    @Test
    public void pesanUjiAsapGagal_legacyTanpaShimSebutShim() {
        String pesan = Updater.pesanUjiAsapGagal("3.14.29", false);
        assertTrue(pesan.contains("gagal uji jalan"));
        assertTrue(pesan.contains("shim"));
    }

    @Test
    public void pesanUjiAsapGagal_legacyDenganShimSingkat() {
        String pesan = Updater.pesanUjiAsapGagal("3.14.29", true);
        assertTrue(pesan.contains("gagal uji jalan"));
        assertFalse(pesan.contains("shim"));
    }

    @Test
    public void pesanUjiAsapGagal_modernTakSebutShim() {
        assertFalse(Updater.pesanUjiAsapGagal("4.4.126", false).contains("shim"));
        assertFalse(Updater.pesanUjiAsapGagal(null, false).contains("shim"));
        assertFalse(Updater.pesanUjiAsapGagal("", true).contains("shim"));
    }

    @Test
    public void readWvVersion_bacaNormalDanTolakJumbo() throws Exception {
        java.io.File kecil = java.io.File.createTempFile("vw-ver-", ".json");
        try {
            java.nio.file.Files.write(kecil.toPath(),
                    "{\"version\": \"1.32.0\"}".getBytes("UTF-8"));
            assertEquals("1.32.0", Updater.readWvVersion(kecil));
        } finally {
            kecil.delete();
        }
        java.io.File jumbo = java.io.File.createTempFile("vw-ver-big-", ".json");
        try {
            byte[] isi = new byte[Updater.BATAS_WV_VERSION + 1024];
            java.util.Arrays.fill(isi, (byte) 'x');
            java.nio.file.Files.write(jumbo.toPath(), isi);
            assertNull(Updater.readWvVersion(jumbo));
        } finally {
            jumbo.delete();
        }
        assertNull(Updater.readWvVersion(null));
        assertNull(Updater.readWvVersion(new java.io.File("/tmp/vw-tidak-ada-xyz.json")));
    }


    @Test
    public void normalisasiPinVersi_kupasBuildMetadata() {
        assertEquals("1.32.0", Updater.normalisasiPinVersi("1.32.0+build5"));
        assertEquals("1.32.0", Updater.normalisasiPinVersi("v1.32.0+build.1"));
        assertEquals("1.32.0", Updater.normalisasiPinVersi("1.32.0"));
        assertNull(Updater.normalisasiPinVersi("1.32+aneh!"));
    }

    @Test
    public void normalisasiPinVersi_validDanInvalid() {
        assertEquals("1.32.0", Updater.normalisasiPinVersi("1.32.0"));
        assertEquals("1.32.0", Updater.normalisasiPinVersi("v1.32.0"));
        assertEquals("1.32.0", Updater.normalisasiPinVersi("  V1.32.0  "));
        assertNull(Updater.normalisasiPinVersi("1.32"));
        assertEquals("1.37.3-beta", Updater.normalisasiPinVersi("1.37.3-beta"));
        assertNull(Updater.normalisasiPinVersi("terbaru"));
        assertNull(Updater.normalisasiPinVersi("1.32.x"));
        assertNull(Updater.normalisasiPinVersi("abc"));
        assertNull(Updater.normalisasiPinVersi("1"));
        assertNull(Updater.normalisasiPinVersi(""));
        assertNull(Updater.normalisasiPinVersi(null));
    }

    @Test
    public void saranStabilPrerelease_sarankanStabil() {
        assertEquals("; rilis repo ini stabil - coba v1.37.3",
                Updater.saranStabilPrerelease("1.37.3-beta"));
        assertEquals("", Updater.saranStabilPrerelease("1.37.3"));
        assertEquals("", Updater.saranStabilPrerelease("v1.32.0"));
        assertEquals("", Updater.saranStabilPrerelease("terbaru"));
        assertEquals("", Updater.saranStabilPrerelease(null));
    }

    @Test
    public void parseDaftarTag_ambilSemuaTag() {
        String body = "[{\"tag_name\":\"v1.37.3\"},{\"tag_name\":\"v1.32.0\"}]";
        java.util.List<String> d = Updater.parseDaftarTag(body, 10);
        assertEquals(2, d.size());
        assertEquals("v1.37.3", d.get(0));
        assertEquals("v1.32.0", d.get(1));
    }

    @Test
    public void parseDaftarTag_batasiMaksDanTolakNull() {
        String body = "[{\"tag_name\":\"v1.3\"},{\"tag_name\":\"v1.2\"}]";
        assertEquals(1, Updater.parseDaftarTag(body, 1).size());
        assertTrue(Updater.parseDaftarTag(null, 5).isEmpty());
        assertTrue(Updater.parseDaftarTag(body, 0).isEmpty());
        assertTrue(Updater.parseDaftarTag("[]", 5).isEmpty());
    }

    @Test
    public void pilihTarget_kuncianMenangAtasTerbaru() {
        assertEquals("1.32.0", Updater.pilihTarget("1.32.0", "1.37.3"));
        assertEquals("1.37.3", Updater.pilihTarget(null, "1.37.3"));
        assertEquals("1.37.3", Updater.pilihTarget("", "1.37.3"));
        assertNull(Updater.pilihTarget("", null));
    }

    @Test
    public void normVersion_kupasHurufVBesarJuga() {
        assertEquals("1.37.3", Updater.normVersion("v1.37.3"));
        assertEquals("1.37.3", Updater.normVersion("V1.37.3"));
        assertEquals("1.37.3", Updater.normVersion("1.37.3"));
        assertNull(Updater.normVersion(null));
        assertTrue(Updater.versiCocok("V1.37.3", "v1.37.3"));
    }

    @Test
    public void stagingWebVault_unikDanDikenaliSapu() {
        java.io.File data = new java.io.File(
                System.getProperty("java.io.tmpdir"), "vw-data");
        java.io.File a = Updater.stagingWebVault(data);
        java.io.File b = Updater.stagingWebVault(data);
        assertFalse(a.getAbsolutePath().equals(b.getAbsolutePath()));
        assertTrue(Updater.sisaStagingWebVault(a.getName()));
        assertTrue(Updater.sisaStagingWebVault("web-vault.new"));
        assertFalse(Updater.sisaStagingWebVault("web-vault.newbie"));
        assertFalse(Updater.sisaStagingWebVault("web-vault"));
        assertFalse(Updater.sisaStagingWebVault(null));
    }

    @Test
    public void validasiRantai_tolakSampahFailClosed() {
        assertEquals(0, Updater.validasiRantai(null));
        assertEquals(0, Updater.validasiRantai(new byte[0]));
        assertEquals(0, Updater.validasiRantai("bukan-sertifikat".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII)));
        assertEquals(0, Updater.validasiRantai(new byte[Updater.BATAS_RANTAI_TRUST + 1]));
    }

    @Test
    public void pindaiChecksumDukungBsd() {
        String hex = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        assertEquals(hex, Updater.pindaiHexChecksum(hex + "  web-vault.zip"));
        assertEquals(hex, Updater.pindaiHexChecksum("SHA256 (web-vault.zip) = " + hex));
        assertNull(Updater.pindaiHexChecksum("SHA256 (web-vault.zip) = bukanhex"));
        assertNull(Updater.pindaiHexChecksum(null));
        assertNull(Updater.pindaiHexChecksum(""));
    }

    @Test
    public void pindaiHexDariBaris_lewatiBarisSampah() {
        String hex = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        assertNull(Updater.pindaiHexDariBaris(null));
        assertNull(Updater.pindaiHexDariBaris(java.util.Collections.<String>emptyList()));
        assertNull(Updater.pindaiHexDariBaris(java.util.Arrays.asList("", "# komentar", "bukanhex")));
        assertEquals(hex, Updater.pindaiHexDariBaris(java.util.Arrays.asList(
                "# komentar", "SHA256 (lama.zip) = bukanhex", hex + "  baru.zip")));
    }

    @Test
    public void namaAssetDariUrl_kupasVersiDanSha256() {
        assertEquals("web-vault.zip", Updater.namaAssetDariUrl(
                "https://github.com/x/y/releases/download/v1.2.3/web-vault.zip"));
        assertEquals("vaultwarden-armeabi-v7a", Updater.namaAssetDariUrl(
                "https://github.com/x/y/releases/download/v1.2.3/vaultwarden-armeabi-v7a.sha256"));
        assertEquals("web-vault.zip", Updater.namaAssetDariUrl(
                "https://example.com/a/web-vault.zip?foo=1#bar"));
        assertEquals("", Updater.namaAssetDariUrl(null));
        assertEquals("", Updater.namaAssetDariUrl(""));
    }

    @Test
    public void pindaiHexDariBaris_utamakanNamaAsset() {
        String hexLama = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String hexBaru = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        java.util.List<String> baris = java.util.Arrays.asList(
                hexLama + "  vaultwarden-armeabi-v7a", hexBaru + "  web-vault.zip");
        assertEquals(hexBaru, Updater.pindaiHexDariBaris(baris, "web-vault.zip"));
        assertEquals(hexLama, Updater.pindaiHexDariBaris(baris, "vaultwarden-armeabi-v7a"));
        // Tanpa nama: fallback hex pertama (kompatibel perilaku lama).
        assertEquals(hexLama, Updater.pindaiHexDariBaris(baris, null));
        assertEquals(hexLama, Updater.pindaiHexDariBaris(baris));
    }

    @Test
    public void bolehCobaLagiUnduh_checksumCampuranDiulangDariNol() {
        assertTrue(Updater.bolehCobaLagiUnduh(
                new java.io.IOException("Checksum SHA-256 tidak cocok; update dibatalkan.")));
        assertTrue(Updater.bolehCobaLagiUnduh(
                new java.io.IOException("File parsial rusak (kurang 10 byte) - unduh ulang dari nol.")));
        assertFalse(Updater.bolehCobaLagiUnduh(
                new java.io.IOException("Unduhan gagal (HTTP 404).")));
    }

    @Test
    public void unduhDilewatiBilaBerkasSudahTerbaru() {
        assertTrue(Updater.unduhBolehDilewati(false, false, "1.37.3", "1.36.0", "1.37.3"));
        assertTrue(Updater.unduhBolehDilewati(false, false, "1.38.0", "1.37.3", "1.37.3"));
        assertTrue(Updater.unduhBolehDilewati(false, false, null, "1.37.3", "1.37.3"));
    }

    @Test
    public void unduhJalanBilaBerkasTertinggal() {
        assertFalse(Updater.unduhBolehDilewati(false, false, "1.36.0", "1.36.0", "1.37.3"));
        assertFalse(Updater.unduhBolehDilewati(false, false, null, null, "1.37.3"));
        assertFalse(Updater.unduhBolehDilewati(false, false, "1.37.3", "1.37.3", null));
        assertFalse(Updater.unduhBolehDilewati(false, false, "1.37.3", "1.37.3", ""));
    }

    @Test
    public void unduhTetapJalanBilaPatchBasi() {
        assertFalse(Updater.unduhBolehDilewati(false, true, "1.37.3", "1.37.3", "1.37.3"));
        assertFalse(Updater.unduhBolehDilewati(true, true, "1.32.0", "1.32.0", "1.32.0"));
    }

    @Test
    public void unduhPaksaDilewatiHanyaBilaBerkasCocok() {
        assertTrue(Updater.unduhBolehDilewati(true, false, "1.32.0", "1.37.3", "1.32.0"));
        assertTrue(Updater.unduhBolehDilewati(true, false, "v1.32.0", null, "1.32.0"));
        assertFalse(Updater.unduhBolehDilewati(true, false, "1.37.3", "1.37.3", "1.32.0"));
        assertFalse(Updater.unduhBolehDilewati(true, false, null, "1.32.0", "1.32.0"));
    }

    @Test
    public void capWvRedirectDariJson() {
        assertEquals("1.37.4", Updater.versiWvUntukCap("1.37.4", false, null));
        assertEquals("1.37.4", Updater.versiWvUntukCap(null, false, "1.37.4"));
        assertNull(Updater.versiWvUntukCap(null, false, null));
        assertNull(Updater.versiWvUntukCap(null, false, ""));
        assertNull(Updater.versiWvUntukCap("1.37.4", true, "1.37.4"));
    }

    @Test
    public void kuncianDuaBagianDitolak() {
        // "1.32" lolos bandingVersi tapi URL asset v1.32 selalu 404:
        // wajib ditolak di normalisasi agar pin lama bertahan.
        assertNull(Updater.normalisasiPinVersi("1.32"));
        assertNull(Updater.normalisasiPinVersi("v1.32"));
        assertEquals("1.32.0", Updater.normalisasiPinVersi("1.32.0"));
    }
}
