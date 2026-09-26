package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test banding token status web (tanpa Android runtime). */
public class ControlServerTest {

    @Test
    public void tokenSamaDiterima() {
        assertTrue(ControlServer.tokenCocok("rahasia123", "rahasia123"));
    }

    @Test
    public void spasiSalinanDiabaikan() {
        assertTrue(ControlServer.tokenCocok("rahasia123", "  rahasia123\n"));
        assertTrue(ControlServer.tokenCocok("  rahasia123  ", "rahasia123"));
    }

    @Test
    public void tokenSalahDitolak() {
        assertFalse(ControlServer.tokenCocok("rahasia123", "rahasia124"));
        assertFalse(ControlServer.tokenCocok("rahasia123", ""));
        assertFalse(ControlServer.tokenCocok("rahasia123", null));
        assertFalse(ControlServer.tokenCocok(null, "rahasia123"));
    }

    @Test
    public void timeoutSisa_dibatasiDeadlineTotal() {
        assertEquals(8000, ControlServer.timeoutSisaMs(1000, 21000, 8000));
        assertEquals(2000, ControlServer.timeoutSisaMs(19000, 21000, 8000));
        assertEquals(0, ControlServer.timeoutSisaMs(21000, 21000, 8000));
        assertEquals(0, ControlServer.timeoutSisaMs(22000, 21000, 8000));
    }

    @Test
    public void ssePerIpDibatasiSatu() {
        java.util.Map<String, Integer> hitung = new java.util.HashMap<>();
        assertTrue(ControlServer.ssePerIpBoleh(hitung, "10.0.0.2", 1));
        hitung.put("10.0.0.2", 1);
        assertFalse(ControlServer.ssePerIpBoleh(hitung, "10.0.0.2", 1));
        assertTrue(ControlServer.ssePerIpBoleh(hitung, "10.0.0.3", 1));
        assertFalse(ControlServer.ssePerIpBoleh(hitung, null, 1));
        assertFalse(ControlServer.ssePerIpBoleh(null, "10.0.0.2", 1));
    }

    @Test
    public void hostDariAlamatBuangPortSumber() throws Exception {
        java.net.SocketAddress a1 = new java.net.InetSocketAddress("10.0.0.2", 54321);
        java.net.SocketAddress a2 = new java.net.InetSocketAddress("10.0.0.2", 59999);
        assertEquals("10.0.0.2", ControlServer.hostDariAlamat(a1));
        assertEquals(ControlServer.hostDariAlamat(a1), ControlServer.hostDariAlamat(a2));
        assertEquals("tak-dikenal", ControlServer.hostDariAlamat(null));
    }

    @Test
    public void hostDariAlamatDukungIpv6() throws Exception {
        java.net.SocketAddress a = new java.net.InetSocketAddress(
                java.net.InetAddress.getByName("::1"), 1234);
        assertEquals("0:0:0:0:0:0:0:1", ControlServer.hostDariAlamat(a));
    }

    @Test
    public void tokenKosongTakCocok() {
        assertFalse(ControlServer.tokenCocok("", ""));
        assertFalse(ControlServer.tokenCocok("   ", "   "));
    }
}
