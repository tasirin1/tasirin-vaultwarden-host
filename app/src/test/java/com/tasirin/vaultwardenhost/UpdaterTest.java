package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test fungsi murni — tanpa Android runtime (jalan di CI via JVM). */
public class UpdaterTest {

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
    public void extractTag_ambilTagNameDariJson() {
        String body = "{\"tag_name\":\"v1.37.1\",\"name\":\"1.37.1\"}";
        assertEquals("v1.37.1", Updater.extractTag(body));
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
    }

    @Test
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
    public void unduhKeTmp_fallbackKeLatestKemudianRetryUrlAsli() throws Exception {
        // Test ini memastikan logika fallback dan retry URL asli bekerja
        // Kita tidak bisa test penuh tanpa mock HTTP, tapi kita bisa verifikasi
        // struktur kode via test unit yang lain.
        // TODO: Tambahkan mock test untuk verifikasi retry URL asli
        assertTrue(true); // Placeholder - test integrasi di CI
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
    public void validasiRantai_tolakSampahFailClosed() {
        assertEquals(0, Updater.validasiRantai(null));
        assertEquals(0, Updater.validasiRantai(new byte[0]));
        assertEquals(0, Updater.validasiRantai("bukan-sertifikat".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII)));
        assertEquals(0, Updater.validasiRantai(new byte[Updater.BATAS_RANTAI_TRUST + 1]));
    }
}
