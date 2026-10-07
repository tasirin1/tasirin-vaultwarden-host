package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
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
    public void tokenMentahKonteksTelegramDisamarkan() {
        String mentah = "gagal kirim telegram 123456:AAEcDeFgHiJkLmNoPqRsTuVwXyZ0123456789 lanjut";
        String r = LogActivity.samarkanLog(mentah);
        assertFalse(r.contains("AAEcDeFgHiJkLmNoPqRsTuVwXyZ0123456789"));
        assertTrue(r.contains("***"));
    }

    @Test
    public void teksMiripTokenTanpaKonteksTetapDisamarkan() {
        String mentah = "proses 123456:AAEcDeFgHiJkLmNoPqRsTuVwXyZ0123456789 selesai";
        String r = LogActivity.samarkanLog(mentah);
        assertFalse(r.contains("AAEcDeFgHiJkLmNoPqRsTuVwXyZ0123456789"));
        assertTrue(r.contains("***:***"));
    }

    @Test
    public void kredensialMentahTelegramDisamarkan() {
        String r = LogActivity.samarkanLog("konfig tg_token=123456:ABCDEF rahasia lanjut");
        assertFalse(r.contains("123456:ABCDEF"));
        String c = LogActivity.samarkanLog("konfig tg_chat=987654 lanjut");
        assertFalse(c.contains("987654"));
    }


    @Test
    public void sandiSpasiDisamarkanPenuh() {
        String r = LogActivity.samarkanLog("cadangan tg_pass=kunci saya 9 selesai");
        assertFalse(r.contains("kunci saya 9"));
        assertFalse(r.contains("saya 9 selesai"));
        assertTrue(r.contains("tg_pass=***"));
    }

    @Test
    public void sandiSpasiBerhentiSebelumKunciBerikut() {
        String r = LogActivity.samarkanLog("cadangan tg_pass=kunci saya DOMAIN=https://192.168.1.5:8088 selesai");
        assertFalse(r.contains("kunci saya"));
        assertTrue(r.contains("tg_pass=***"));
        assertTrue(r.contains("DOMAIN=***"));
        assertFalse(r.contains("192.168.1.5"));
    }

    @Test
    public void tokenJsonKutipTunggalDisamarkan() {
        String r = LogActivity.samarkanLog("konfig 'tg_token': '123456:ABCDEF' lanjut");
        assertFalse(r.contains("123456:ABCDEF"));
    }

    @Test
    public void domainDisamarkan() {
        String r = LogActivity.samarkanLog("env DOMAIN=https://192.168.1.5:8088 lanjut");
        assertFalse(r.contains("192.168.1.5:8088"));
        assertTrue(r.contains("DOMAIN=***"));
    }

    @Test
    public void jamBiasaTakIkutDisamarkan() {
        String r = LogActivity.samarkanLog("server jalan di port 8088 jam 12:30");
        assertTrue(r.contains("12:30"));
    }

    @Test
    public void logNormalTakBerubah() {
        String r = LogActivity.samarkanLog("server jalan di port 8088");
        assertTrue(r.contains("8088"));
    }

    @Test
    public void tokenPendekTetapDisamarkan() {
        String r = LogActivity.samarkanLog("gagal: bot123456:ABCDEF123456 lanjut");
        assertFalse(r.contains("ABCDEF123456"));
        String m = LogActivity.samarkanLog("proses 123456:ABCDEF1234567890 selesai");
        assertFalse(m.contains("ABCDEF1234567890"));
    }

    @Test
    public void urlBotPendekTetapDisamarkan() {
        String r = LogActivity.samarkanLog("hubungi https://api.telegram.org/bot123:ABCDEF12 selesai");
        assertFalse(r.contains("ABCDEF12"));
    }

    @Test
    public void sidikClip_konsistenDanBeda() {
        String a = LogActivity.sidikClip("salinan log");
        String b = LogActivity.sidikClip("salinan log");
        String c = LogActivity.sidikClip("salinan lain");
        assertFalse(a.isEmpty());
        assertTrue(a.equals(b));
        assertFalse(a.equals(c));
    }

    @Test
    public void sidikClip_nullKosong() {
        assertTrue(LogActivity.sidikClip(null).isEmpty());
    }

    @Test
    public void clipPakaiWallClock_batas1982() {
        assertTrue(LogActivity.clipPakaiWallClock(400_000_000_001L));
        assertTrue(LogActivity.clipPakaiWallClock(System.currentTimeMillis()));
        assertFalse(LogActivity.clipPakaiWallClock(400_000_000_000L));
        assertFalse(LogActivity.clipPakaiWallClock(100_000_000_001L));
        assertFalse(LogActivity.clipPakaiWallClock(60_000L));
        assertFalse(LogActivity.clipPakaiWallClock(0L));
    }

    @Test
    public void sisaClip_hitungElapsedMurni() {
        assertEquals(5000, LogActivity.sisaClipMs(35000, 30000));
        assertEquals(0, LogActivity.sisaClipMs(30000, 30000));
        assertEquals(0, LogActivity.sisaClipMs(20000, 30000));
        assertEquals(0, LogActivity.sisaClipMs(0, 30000));
    }

    @Test
    public void portDanJamTakIkutDisamarkan() {
        String r = LogActivity.samarkanLog("server jalan di port 8088 jam 12:30");
        assertTrue(r.contains("8088"));
        assertTrue(r.contains("12:30"));
    }
}
