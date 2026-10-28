package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LogExportTest {

    @Test
    public void namaLogFormatStempelDanAkhiranAcakHex() {
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        String a = LogExport.namaLog("20261028-120000-123", rnd);
        String b = LogExport.namaLog("20261028-120000-123", rnd);
        assertTrue(a.startsWith("tasirin-vaultwarden-host-log-20261028-120000-123-"));
        assertTrue(a.endsWith(".txt"));
        String acak = a.substring(a.lastIndexOf('-') + 1, a.length() - 4);
        assertTrue(acak.matches("[0-9a-f]{12}"));
        assertFalse(a.equals(b));
    }
}
