package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test logika murni perintah Telegram (tanpa Android runtime). */
public class TgBotTest {

    @Test
    public void restoreConfirm_terimaVarianYa() {
        assertTrue(TgBot.isRestoreConfirm("YA"));
        assertTrue(TgBot.isRestoreConfirm("ya"));
        assertTrue(TgBot.isRestoreConfirm(" yes "));
        assertTrue(TgBot.isRestoreConfirm("Y"));
        assertTrue(TgBot.isRestoreConfirm("ok"));
        assertTrue(TgBot.isRestoreConfirm("konfirmasi"));
        assertTrue(TgBot.isRestoreConfirm("lanjut"));
    }

    @Test
    public void menuPayload_memuatSemuaPerintah() {
        String json = TgBot.menuPayload();
        assertTrue(json.startsWith("{\"commands\":["));
        for (String c : new String[]{"status", "log", "uptime", "alive", "backup",
                "restore", "crashlog", "update", "webvault", "start", "stop",
                "restart", "help"}) {
            assertTrue("hilang: " + c, json.contains("\"" + c + "\""));
        }
        assertEquals(13, TgBot.daftarPerintahMenu().length);
    }

    @Test
    public void webVaultBerubah_hanyaPesanUpdated() {
        assertTrue(TgBot.webVaultBerubah("Web vault updated di /sdcard/vaultwarden/web-vault"));
        assertFalse(TgBot.webVaultBerubah("Web vault sudah versi terbaru: v1.37.3"));
        assertFalse(TgBot.webVaultBerubah("Web vault v1.37.2 terpasang; cek versi gagal."));
        assertFalse(TgBot.webVaultBerubah(null));
        assertFalse(TgBot.webVaultBerubah(""));
    }

    @Test
    public void keyboardPerintah_memuatSemuaTombol() {
        String json = TgBot.keyboardPerintah();
        assertTrue(json.startsWith("{\"inline_keyboard\":["));
        for (String c : new String[]{"/status", "/log", "/uptime", "/alive", "/backup",
                "/restore", "/crashlog", "/update", "/webvault", "/start", "/stop",
                "/restart", "/help"}) {
            assertTrue("hilang: " + c, json.contains("\"" + c + "\""));
        }
    }

    @Test
    public void callbackDataValid_hanyaPerintahDikenal() {
        assertTrue(TgBot.callbackDataValid("/status"));
        assertTrue(TgBot.callbackDataValid("/stop 123456"));
        assertFalse(TgBot.callbackDataValid("/hapus"));
        assertFalse(TgBot.callbackDataValid("status"));
        assertFalse(TgBot.callbackDataValid(""));
        assertFalse(TgBot.callbackDataValid(null));
    }

    @Test
    public void restoreConfirm_tolakKosongDanAsing() {
        assertFalse(TgBot.isRestoreConfirm(null));
        assertFalse(TgBot.isRestoreConfirm(""));
        assertFalse(TgBot.isRestoreConfirm("tidak"));
        assertFalse(TgBot.isRestoreConfirm("backup"));
        assertFalse(TgBot.isRestoreConfirm("YA sekarang"));
    }
}
