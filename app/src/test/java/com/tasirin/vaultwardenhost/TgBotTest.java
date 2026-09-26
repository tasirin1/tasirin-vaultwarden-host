package com.tasirin.vaultwardenhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit test logika murni perintah Telegram (tanpa Android runtime). */
public class TgBotTest {

    @Test
    public void parseChatId_numerikSekali() {
        assertEquals(123456789L, TgBot.parseChatId("123456789"));
        assertEquals(123456789L, TgBot.parseChatId("  123456789  "));
        assertEquals(Long.MIN_VALUE, TgBot.parseChatId("bukan-angka"));
        assertEquals(Long.MIN_VALUE, TgBot.parseChatId(""));
        assertEquals(Long.MIN_VALUE, TgBot.parseChatId(null));
    }

    @Test
    public void tombolKedaluwarsa_batas24Jam() {
        long kini = 1_000_000_000L;
        assertTrue(TgBot.tombolKedaluwarsa(0, kini));
        assertFalse(TgBot.tombolKedaluwarsa(kini, kini));
        assertFalse(TgBot.tombolKedaluwarsa(kini - 23L * 3600 * 1000, kini));
        assertTrue(TgBot.tombolKedaluwarsa(kini - 25L * 3600 * 1000, kini));
    }

    @Test
    public void tombolKedaluwarsa_tanggalMasaDepanDitolak() {
        long kini = 1_000_000_000L;
        assertFalse(TgBot.tombolKedaluwarsa(kini + 60_000L, kini));
        assertTrue(TgBot.tombolKedaluwarsa(kini + 3600_000L, kini));
    }

    @Test
    public void peringatanMundur_maksSatuKaliSejam() {
        assertTrue(TgBot.peringatanMundurJatuhTempo(1000, 0));
        assertFalse(TgBot.peringatanMundurJatuhTempo(1000, 1000));
        assertFalse(TgBot.peringatanMundurJatuhTempo(1000 + 3599_999L, 1000));
        assertTrue(TgBot.peringatanMundurJatuhTempo(1000 + 3600_000L, 1000));
        assertTrue(TgBot.peringatanMundurJatuhTempo(500, 1000));
    }

    @Test
    public void restoreConfirm_terimaVarianYa() {
        assertTrue(TgBot.isRestoreConfirm("YA"));
        assertTrue(TgBot.isRestoreConfirm("ya"));
        assertTrue(TgBot.isRestoreConfirm(" yes "));
        assertTrue(TgBot.isRestoreConfirm("konfirmasi"));
        assertTrue(TgBot.isRestoreConfirm(" Konfirmasi "));
    }

    @Test
    public void menuPayload_memuatSemuaPerintah() {
        String json = TgBot.menuPayload();
        assertTrue(json.startsWith("{\"commands\":["));
        for (String c : new String[]{"status", "log", "uptime", "alive", "backup",
                "restore", "ca", "crashlog", "update", "webvault", "start", "stop",
                "restart", "help"}) {
            assertTrue("hilang: " + c, json.contains("\"" + c + "\""));
        }
        assertEquals(14, TgBot.daftarPerintahMenu().length);
    }

    @Test
    public void webVaultBerubah_hanyaPesanUpdated() {
        assertTrue(TgBot.webVaultBerubah("Web vault updated di /sdcard/vaultwarden/web-vault"));
        assertFalse(TgBot.webVaultBerubah("Web vault sudah versi terbaru: v1.37.3"));
        assertFalse(TgBot.webVaultBerubah("Web vault v1.37.2 terpasang; cek versi gagal."));
        assertFalse(TgBot.webVaultBerubah("Database updated di /sdcard/vaultwarden"));
        assertFalse(TgBot.webVaultBerubah(null));
        assertFalse(TgBot.webVaultBerubah(""));
    }

    @Test
    public void webVaultBerubah_markerMesin() {
        assertTrue(TgBot.webVaultBerubah("Web vault updated di /x " + Updater.WV_UPDATED_MARKER));
        assertTrue(TgBot.webVaultBerubah("Web vault updated di /sdcard/vaultwarden/web-vault"));
    }

    @Test
    public void keyboardPerintah_memuatSemuaTombol() {
        String json = TgBot.keyboardPerintah();
        assertTrue(json.startsWith("{\"inline_keyboard\":["));
        for (String c : new String[]{"/status", "/log", "/uptime", "/alive", "/backup",
                "/restore", "/ca", "/crashlog", "/update", "/webvault", "/start", "/stop",
                "/restart", "/help"}) {
            assertTrue("hilang: " + c, json.contains("\"" + c + "\""));
        }
    }

    @Test
    public void namaPerintah_kupasSuffixAtBotGrup() {
        assertEquals("ca", TgBot.namaPerintah("/ca"));
        assertEquals("ca", TgBot.namaPerintah("/ca@NamaBot"));
        assertEquals("ca", TgBot.namaPerintah("/CA@NamaBot arg"));
        assertEquals("stop", TgBot.namaPerintah("/stop@Bot 123456"));
        assertEquals("", TgBot.namaPerintah(null));
    }

    @Test
    public void callbackDataValid_hanyaPerintahDikenal() {
        assertTrue(TgBot.callbackDataValid("/status"));
        assertTrue(TgBot.callbackDataValid("/ca"));
        assertTrue(TgBot.callbackDataValid("/stop 123456"));
        assertTrue(TgBot.callbackDataValid("/ca@NamaBot"));
        assertTrue(TgBot.callbackDataValid("/stop@Bot 123456"));
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
        // Kata umum yang dulu diterima kini ditolak (anti-restore nyasar).
        assertFalse(TgBot.isRestoreConfirm("ok"));
        assertFalse(TgBot.isRestoreConfirm("Y"));
        assertFalse(TgBot.isRestoreConfirm("lanjut"));
    }
}
