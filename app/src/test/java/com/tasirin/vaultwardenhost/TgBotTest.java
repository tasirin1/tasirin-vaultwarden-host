package com.tasirin.vaultwardenhost;

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
    public void restoreConfirm_tolakKosongDanAsing() {
        assertFalse(TgBot.isRestoreConfirm(null));
        assertFalse(TgBot.isRestoreConfirm(""));
        assertFalse(TgBot.isRestoreConfirm("tidak"));
        assertFalse(TgBot.isRestoreConfirm("backup"));
        assertFalse(TgBot.isRestoreConfirm("YA sekarang"));
    }
}
