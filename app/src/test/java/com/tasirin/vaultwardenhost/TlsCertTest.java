package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Unit test logika murni TLS — tanpa Android runtime (jalan di CI via JVM). */
public class TlsCertTest {

    @Test
    public void namaFileCaDanLeafSesuaiSkema() {
        assertEquals("ca.pem", TlsCert.CA_CERT_FILE);
        assertEquals("ca-key.pem", TlsCert.CA_KEY_FILE);
        assertEquals("cert.pem", TlsCert.LEAF_CERT_FILE);
        assertEquals("key.pem", TlsCert.LEAF_KEY_FILE);
    }

    @Test
    public void ipv4ValidDiuraiBenar() {
        assertArrayEquals(new byte[]{(byte) 192, (byte) 168, 1, 10},
                TlsCert.ipv4("192.168.1.10"));
        assertArrayEquals(new byte[]{127, 0, 0, 1}, TlsCert.ipv4("127.0.0.1"));
        assertArrayEquals(new byte[]{10, 0, 0, 2}, TlsCert.ipv4("10.0.0.2"));
    }

    @Test
    public void ipv4TakValidHasilNull() {
        assertNull(TlsCert.ipv4(null));
        assertNull(TlsCert.ipv4(""));
        assertNull(TlsCert.ipv4("abc"));
        assertNull(TlsCert.ipv4("1.2.3"));
        assertNull(TlsCert.ipv4("1.2.3.4.5"));
        assertNull(TlsCert.ipv4("256.1.1.1"));
        assertNull(TlsCert.ipv4("192.168.1.x"));
        assertNull(TlsCert.ipv4("::1"));
    }

    @Test
    public void ipv6ValidDiuraiBenar() {
        assertEquals(16, TlsCert.ipv6("::1").length);
        assertEquals(16, TlsCert.ipv6("fe80::1").length);
        assertEquals(16, TlsCert.ipv6("2001:db8:0:0:0:0:2:1").length);
        assertEquals(16, TlsCert.ipv6("[fe80::1]").length);
    }

    @Test
    public void ipv6TakValidHasilNull() {
        assertNull(TlsCert.ipv6(null));
        assertNull(TlsCert.ipv6(""));
        assertNull(TlsCert.ipv6("192.168.1.1"));
        assertNull(TlsCert.ipv6("bukan-ip"));
        assertNull(TlsCert.ipv6("1::2::3"));
        assertNull(TlsCert.ipv6("gggg::1"));
    }

    @Test
    public void ipv6EmbeddedIpv4DiuraiBenar() {
        byte[] b = TlsCert.ipv6("::ffff:192.168.1.1");
        assertEquals(16, b.length);
        assertEquals((byte) 0xFF, b[10]);
        assertEquals((byte) 0xFF, b[11]);
        assertEquals((byte) 192, b[12]);
        assertEquals((byte) 168, b[13]);
        assertEquals((byte) 1, b[14]);
        assertEquals((byte) 1, b[15]);
    }

    @Test
    public void namaDnsTolakIpv6() {
        assertFalse(TlsCert.namaDnsValid("::1"));
        assertFalse(TlsCert.namaDnsValid("fe80::1"));
    }

    @Test
    public void versiLamaDitolakVersiBaruDiterima() throws Exception {
        File dir = Files.createTempDirectory("tlscert").toFile();
        assertFalse(TlsCert.certVersionOk(dir));
        Files.write(new File(dir, "version.txt").toPath(), "4\n".getBytes(StandardCharsets.US_ASCII));
        assertFalse(TlsCert.certVersionOk(dir));
        Files.write(new File(dir, "version.txt").toPath(), "6\n".getBytes(StandardCharsets.US_ASCII));
        assertTrue(TlsCert.certVersionOk(dir));
        Files.write(new File(dir, "version.txt").toPath(), "rusak".getBytes(StandardCharsets.US_ASCII));
        assertFalse(TlsCert.certVersionOk(dir));
    }

    @Test
    public void namaDnsValidTerimaLokalTolakIpDanRusak() {
        assertTrue(TlsCert.namaDnsValid("vault.lan"));
        assertTrue(TlsCert.namaDnsValid("server"));
        assertTrue(TlsCert.namaDnsValid("vaultwarden-rumah.home"));
        assertTrue(TlsCert.namaDnsValid("a.b.c.local"));
        assertFalse(TlsCert.namaDnsValid(null));
        assertFalse(TlsCert.namaDnsValid(""));
        assertFalse(TlsCert.namaDnsValid("192.168.1.10"));
        assertFalse(TlsCert.namaDnsValid("va ult.lan"));
        assertFalse(TlsCert.namaDnsValid("-salah.lan"));
        assertFalse(TlsCert.namaDnsValid("salah-.lan"));
        assertFalse(TlsCert.namaDnsValid("VAULT.LAN".toLowerCase(java.util.Locale.US) + "!"));
    }

    @Test
    public void daftarDnsBersihUnikBatas() {
        assertEquals(java.util.Arrays.asList("vault.lan", "server"),
                TlsCert.daftarDns("vault.lan, server vault.lan"));
        assertEquals(java.util.Collections.emptyList(), TlsCert.daftarDns(null));
        assertEquals(java.util.Collections.emptyList(), TlsCert.daftarDns("192.168.1.10"));
        assertEquals(java.util.Collections.singletonList("vault.lan"),
                TlsCert.daftarDns("  VAULT.LAN. "));
    }

    @Test
    public void caHilangAtauRusakDianggapTakOk() throws Exception {
        File dir = Files.createTempDirectory("tlsca").toFile();
        Files.write(new File(dir, "version.txt").toPath(), "5\n".getBytes(StandardCharsets.US_ASCII));
        File ca = new File(dir, "ca.pem");
        File key = new File(dir, "ca-key.pem");
        assertFalse(TlsCert.caOk(ca, key, dir));
        Files.write(ca.toPath(), "bukan-sertifikat".getBytes(StandardCharsets.US_ASCII));
        Files.write(key.toPath(), "bukan-kunci".getBytes(StandardCharsets.US_ASCII));
        assertFalse(TlsCert.caOk(ca, key, dir));
    }
}
