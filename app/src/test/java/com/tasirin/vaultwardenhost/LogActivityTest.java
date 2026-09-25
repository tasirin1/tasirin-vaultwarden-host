package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test penyamaran secret di log (tanpa Android runtime). */
public class LogActivityTest {

    @Test
    public void tokenBotMentahDisamarkan() {
        String r = LogActivity.samarkanLog("gagal: https://api.telegram.org/bot123456:ABCdefGhI_JKL-mnop");
        assertFalse(r.contains("ABCdefGhI_JKL-mnop"));
        assertTrue(r.contains("***"));
    }

    @Test
    public void adminTokenEnvDisamarkan() {
        String r = LogActivity.samarkanLog("env ADMIN_TOKEN=rahasia123 lanjut");
        assertFalse(r.contains("rahasia123"));
    }

    @Test
    public void bearerDisamarkan() {
        String r = LogActivity.samarkanLog("Authorization: Bearer rahasia123");
        assertFalse(r.contains("rahasia123"));
    }

    @Test
    public void logNormalTakBerubah() {
        String r = LogActivity.samarkanLog("server jalan di port 8088");
        assertTrue(r.contains("8088"));
    }
}
