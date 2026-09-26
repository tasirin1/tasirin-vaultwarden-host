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
    public void tokenKosongTakCocok() {
        assertFalse(ControlServer.tokenCocok("", ""));
        assertFalse(ControlServer.tokenCocok("   ", "   "));
    }
}
