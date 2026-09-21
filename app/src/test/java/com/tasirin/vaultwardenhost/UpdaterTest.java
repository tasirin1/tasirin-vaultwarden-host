package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
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
}
